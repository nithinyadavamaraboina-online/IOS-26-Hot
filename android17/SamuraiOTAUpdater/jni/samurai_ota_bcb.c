/*
 * samurai_ota_bcb.c — Static ARM64 Unattended Recovery Block-Map & BCB Stager for RMX1931 (Android 17)
 *
 * Supports TWO zero-prompt unattended TWRP block.map modes:
 *   1. Compact OTAs (<= 380 MiB):
 *      Stages <zip_path> into /mnt/ota_cache/recovery/update.zip on /dev/block/by-name/cache (sda6)
 *      and writes /cache/recovery/block.map pointing to cache_blk.
 *   2. Full Atomic Block-Image System OTAs (> 380 MiB, e.g. ~1.85 GiB system.new.dat.br):
 *      Stages <zip_path> inside unencrypted /data/ota_raw/update.zip (created by init with encryption=None,
 *      with automatic fallback to /data/preloads/ota_raw/update.zip) on /dev/block/by-name/userdata (sda13),
 *      maps extents via FS_IOC_FIEMAP (FIEMAP_FLAG_SYNC) with FIBMAP fallback, verifies BOTH the 16-byte
 *      PK\x03\x04 header AND the 16-byte ZIP tail directly from raw userdata_blk via pread() (mathematically
 *      proving zero FBE/inline encryption), and writes the ~1 KiB /cache/recovery/block.map pointing to
 *      userdata_blk while arming /dev/block/by-name/misc with `--update_package=@/cache/recovery/block.map`.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/fiemap.h>
#include <linux/fs.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define CACHE_STAGE_MAX_BYTES (380ULL * 1024ULL * 1024ULL)

struct blk_range {
    uint64_t start;
    uint64_t end;
};

struct bootloader_message_2k {
    char command[32];
    char status[32];
    char recovery[768];
    char stage[32];
    char reserved[1184];
};

static int resolve_block_dev(const char *const *candidates, char *out_real, size_t out_sz) {
    for (size_t i = 0; candidates[i] != NULL; i++) {
        struct stat st;
        if (stat(candidates[i], &st) == 0 && S_ISBLK(st.st_mode)) {
            char resolved[PATH_MAX];
            if (realpath(candidates[i], resolved) != NULL) {
                snprintf(out_real, out_sz, "%s", resolved);
            } else {
                snprintf(out_real, out_sz, "%s", candidates[i]);
            }
            return 0;
        }
    }
    return -1;
}

static int same_file_path(const char *a, const char *b) {
    struct stat sa, sb;
    if (stat(a, &sa) == 0 && stat(b, &sb) == 0) {
        return (sa.st_dev == sb.st_dev && sa.st_ino == sb.st_ino) ? 1 : 0;
    }
    return 0;
}

static int copy_file_synced(int src_fd, const char *dst_path, uint64_t expected_size) {
    int dst_fd = open(dst_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (dst_fd < 0) {
        fprintf(stderr, "open(%s) failed: %s\n", dst_path, strerror(errno));
        return -1;
    }

    size_t chunk_sz = 1048576;
    uint8_t *buf = malloc(chunk_sz);
    if (!buf) {
        close(dst_fd);
        return -1;
    }

    if (lseek(src_fd, 0, SEEK_SET) < 0) {
        free(buf);
        close(dst_fd);
        return -1;
    }

    uint64_t total = 0;
    while (total < expected_size) {
        ssize_t r = read(src_fd, buf, chunk_sz);
        if (r < 0) {
            if (errno == EINTR) continue;
            fprintf(stderr, "read(src) failed: %s\n", strerror(errno));
            free(buf);
            close(dst_fd);
            return -1;
        }
        if (r == 0) break;

        ssize_t off = 0;
        while (off < r) {
            ssize_t w = write(dst_fd, buf + off, (size_t)(r - off));
            if (w < 0) {
                if (errno == EINTR) continue;
                fprintf(stderr, "write(%s) failed: %s\n", dst_path, strerror(errno));
                free(buf);
                close(dst_fd);
                return -1;
            }
            off += w;
        }
        total += (uint64_t)r;
    }

    free(buf);
    if (total != expected_size) {
        fprintf(stderr, "short copy: %llu != %llu\n",
                (unsigned long long)total, (unsigned long long)expected_size);
        close(dst_fd);
        return -1;
    }

    if (fsync(dst_fd) != 0) {
        fprintf(stderr, "fsync(%s) failed: %s\n", dst_path, strerror(errno));
        close(dst_fd);
        return -1;
    }
    syncfs(dst_fd);
    close(dst_fd);
    return 0;
}

static int append_range(struct blk_range **ranges, size_t *count, size_t *cap, uint64_t start, uint64_t end) {
    if (start >= end) return 0;
    if (*count > 0 && (*ranges)[*count - 1].end == start) {
        (*ranges)[*count - 1].end = end;
        return 0;
    }
    if (*count >= *cap) {
        size_t new_cap = (*cap == 0) ? 256 : (*cap * 2);
        struct blk_range *nr = realloc(*ranges, new_cap * sizeof(struct blk_range));
        if (!nr) return -1;
        *ranges = nr;
        *cap = new_cap;
    }
    (*ranges)[*count].start = start;
    (*ranges)[*count].end = end;
    (*count)++;
    return 0;
}

static int map_extents_fiemap(int fd, uint64_t file_size, uint32_t blksize,
                              struct blk_range **out_ranges, size_t *out_count) {
    const size_t max_ext = 512;
    size_t alloc_sz = sizeof(struct fiemap) + max_ext * sizeof(struct fiemap_extent);
    struct fiemap *fm = calloc(1, alloc_sz);
    if (!fm) return -1;

    uint64_t num_blocks = (file_size + (uint64_t)blksize - 1ULL) / (uint64_t)blksize;
    uint64_t mapped_blocks = 0;
    uint64_t cur_start = 0;
    struct blk_range *ranges = NULL;
    size_t count = 0, cap = 0;

    while (mapped_blocks < num_blocks) {
        memset(fm, 0, alloc_sz);
        fm->fm_start = cur_start;
        fm->fm_length = FIEMAP_MAX_OFFSET;
        fm->fm_flags = FIEMAP_FLAG_SYNC;
        fm->fm_extent_count = (uint32_t)max_ext;

        if (ioctl(fd, FS_IOC_FIEMAP, fm) != 0 || fm->fm_mapped_extents == 0) {
            free(fm);
            free(ranges);
            return -1;
        }

        int saw_last = 0;
        for (uint32_t i = 0; i < fm->fm_mapped_extents && mapped_blocks < num_blocks; i++) {
            const struct fiemap_extent *ext = &fm->fm_extents[i];
            uint32_t bad_flags = FIEMAP_EXTENT_UNKNOWN | FIEMAP_EXTENT_DELALLOC |
                                 FIEMAP_EXTENT_UNWRITTEN | FIEMAP_EXTENT_NOT_ALIGNED;
#ifdef FIEMAP_EXTENT_DATA_ENCRYPTED
            bad_flags |= FIEMAP_EXTENT_DATA_ENCRYPTED;
#endif
            if ((ext->fe_flags & bad_flags) != 0) {
                fprintf(stderr, "FIEMAP extent %u has unsupported/encrypted flags 0x%x\n",
                        i, ext->fe_flags);
                free(fm);
                free(ranges);
                return -1;
            }
            if ((ext->fe_logical % blksize) != 0 || (ext->fe_physical % blksize) != 0 ||
                (ext->fe_length % blksize) != 0) {
                free(fm);
                free(ranges);
                return -1;
            }
            uint64_t ext_log_blk = ext->fe_logical / blksize;
            if (ext_log_blk != mapped_blocks) {
                fprintf(stderr, "FIEMAP sparse hole detected at block %llu != %llu\n",
                        (unsigned long long)ext_log_blk, (unsigned long long)mapped_blocks);
                free(fm);
                free(ranges);
                return -1;
            }
            uint64_t ext_blks = ext->fe_length / blksize;
            if (mapped_blocks + ext_blks > num_blocks) {
                ext_blks = num_blocks - mapped_blocks;
            }
            uint64_t phys_start = ext->fe_physical / blksize;
            if (append_range(&ranges, &count, &cap, phys_start, phys_start + ext_blks) != 0) {
                free(fm);
                free(ranges);
                return -1;
            }
            mapped_blocks += ext_blks;
            cur_start = ext->fe_logical + ext->fe_length;
            if (ext->fe_flags & FIEMAP_EXTENT_LAST) {
                saw_last = 1;
                break;
            }
        }
        if (saw_last) break;
    }

    free(fm);
    if (mapped_blocks != num_blocks || count == 0) {
        free(ranges);
        return -1;
    }
    *out_ranges = ranges;
    *out_count = count;
    return 0;
}

static int map_extents_fibmap(int fd, uint64_t file_size, uint32_t blksize,
                              struct blk_range **out_ranges, size_t *out_count) {
    uint64_t num_blocks = (file_size + (uint64_t)blksize - 1ULL) / (uint64_t)blksize;
    struct blk_range *ranges = NULL;
    size_t count = 0, cap = 0;

    for (uint64_t i = 0; i < num_blocks; i++) {
        int b = (int)i;
        if (ioctl(fd, FIBMAP, &b) != 0 || b <= 0) {
            fprintf(stderr, "FIBMAP failed at logical block %llu (b=%d, err=%s)\n",
                    (unsigned long long)i, b, strerror(errno));
            free(ranges);
            return -1;
        }
        uint64_t phys = (uint64_t)(uint32_t)b;
        if (append_range(&ranges, &count, &cap, phys, phys + 1ULL) != 0) {
            free(ranges);
            return -1;
        }
    }
    *out_ranges = ranges;
    *out_count = count;
    return 0;
}

static int resolve_phys_byte_offset(const struct blk_range *ranges, size_t count,
                                    uint32_t blksize, uint64_t logical_off, off_t *out_phys) {
    uint64_t log_blk = logical_off / (uint64_t)blksize;
    uint64_t in_blk = logical_off % (uint64_t)blksize;
    uint64_t cur = 0;
    for (size_t i = 0; i < count; i++) {
        uint64_t span = ranges[i].end - ranges[i].start;
        if (log_blk >= cur && log_blk < cur + span) {
            uint64_t phys_blk = ranges[i].start + (log_blk - cur);
            *out_phys = (off_t)(phys_blk * (uint64_t)blksize + in_blk);
            return 0;
        }
        cur += span;
    }
    return -1;
}

static int verify_raw_unencrypted_zip(const char *mapped_file, const char *blk_dev,
                                      uint64_t file_size, uint32_t *out_blksize,
                                      struct blk_range **out_ranges, size_t *out_count) {
    int mfd = open(mapped_file, O_RDONLY | O_CLOEXEC);
    if (mfd < 0) {
        fprintf(stderr, "open(%s) failed: %s\n", mapped_file, strerror(errno));
        return -1;
    }
    fsync(mfd);
    syncfs(mfd);

    int blksize_i = 0;
    if (ioctl(mfd, FIGETBSZ, &blksize_i) != 0 || blksize_i <= 0) {
        blksize_i = 4096;
    }
    uint32_t blksize = (uint32_t)blksize_i;

    struct blk_range *ranges = NULL;
    size_t count = 0;
    if (map_extents_fiemap(mfd, file_size, blksize, &ranges, &count) != 0) {
        if (map_extents_fibmap(mfd, file_size, blksize, &ranges, &count) != 0) {
            close(mfd);
            return -1;
        }
    }

    uint8_t exp_head[16] = {0};
    uint8_t exp_tail[16] = {0};
    if (pread(mfd, exp_head, 16, 0) != 16 ||
        pread(mfd, exp_tail, 16, (off_t)(file_size - 16ULL)) != 16) {
        close(mfd);
        free(ranges);
        return -1;
    }
    close(mfd);

    int raw_fd = open(blk_dev, O_RDONLY | O_CLOEXEC);
    if (raw_fd < 0) {
        fprintf(stderr, "open(%s) raw verify failed: %s\n", blk_dev, strerror(errno));
        free(ranges);
        return -1;
    }
    ioctl(raw_fd, BLKFLSBUF, 0);

    for (size_t i = 0; i < 16; i++) {
        off_t ph_h = 0, ph_t = 0;
        uint8_t bh = 0, bt = 0;
        if (resolve_phys_byte_offset(ranges, count, blksize, (uint64_t)i, &ph_h) != 0 ||
            resolve_phys_byte_offset(ranges, count, blksize, (file_size - 16ULL) + (uint64_t)i, &ph_t) != 0 ||
            pread(raw_fd, &bh, 1, ph_h) != 1 ||
            pread(raw_fd, &bt, 1, ph_t) != 1 ||
            bh != exp_head[i] || bt != exp_tail[i]) {
            fprintf(stderr,
                    "Raw UFS plaintext check failed on %s for %s (byte %zu: head %02x!=%02x, tail %02x!=%02x)\n",
                    blk_dev, mapped_file, i, bh, exp_head[i], bt, exp_tail[i]);
            close(raw_fd);
            free(ranges);
            return -1;
        }
    }
    close(raw_fd);

    *out_blksize = blksize;
    *out_ranges = ranges;
    *out_count = count;
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <ota_zip_path> [--clear-cache]\n", argv[0]);
        return 2;
    }

    const char *const cache_cands[] = {
        "/dev/block/by-name/cache",
        "/dev/block/bootdevice/by-name/cache",
        "/dev/block/sda6",
        NULL
    };
    const char *const userdata_cands[] = {
        "/dev/block/by-name/userdata",
        "/dev/block/bootdevice/by-name/userdata",
        "/dev/block/sda13",
        NULL
    };
    const char *const misc_cands[] = {
        "/dev/block/by-name/misc",
        "/dev/block/bootdevice/by-name/misc",
        "/dev/block/sda3",
        NULL
    };

    char cache_blk[PATH_MAX];
    if (resolve_block_dev(cache_cands, cache_blk, sizeof(cache_blk)) != 0) {
        fprintf(stderr, "Failed to locate /cache block device\n");
        return 1;
    }

    if (strcmp(argv[1], "--clear-cache") == 0) {
        int had_armed_marker = (access("/data/ota_raw/.armed", F_OK) == 0);
        if (had_armed_marker) {
            unlink("/data/ota_raw/update.zip");
            unlink("/data/ota_raw/update.sha256");
            unlink("/data/ota_raw/.armed");
            unlink("/data/preloads/ota_raw/update.zip");
        }
        mkdir("/mnt/ota_cache", 0755);
        umount2("/mnt/ota_cache", MNT_DETACH);
        if (mount(cache_blk, "/mnt/ota_cache", "ext4", MS_NOATIME, NULL) == 0) {
            int had_map = (access("/mnt/ota_cache/recovery/block.map", F_OK) == 0);
            unlink("/mnt/ota_cache/recovery/update.zip");
            unlink("/mnt/ota_cache/recovery/block.map");
            unlink("/mnt/ota_cache/recovery/command");
            unlink("/mnt/ota_cache/recovery/openrecoveryscript");
            sync();
            umount2("/mnt/ota_cache", MNT_DETACH);
            if (had_map) {
                unlink("/data/ota_raw/update.zip");
                unlink("/data/ota_raw/update.sha256");
                unlink("/data/ota_raw/.armed");
                unlink("/data/preloads/ota_raw/update.zip");
            }
        }
        return 0;
    }

    char misc_blk[PATH_MAX];
    if (resolve_block_dev(misc_cands, misc_blk, sizeof(misc_blk)) != 0) {
        fprintf(stderr, "Failed to locate /misc block device\n");
        return 1;
    }

    const char *src_zip = argv[1];
    int src_fd = open(src_zip, O_RDONLY | O_CLOEXEC);
    if (src_fd < 0) {
        fprintf(stderr, "open(%s) failed: %s\n", src_zip, strerror(errno));
        return 1;
    }

    struct stat st;
    if (fstat(src_fd, &st) != 0 || st.st_size < 1024) {
        fprintf(stderr, "Invalid OTA zip size for %s\n", src_zip);
        close(src_fd);
        return 1;
    }
    uint64_t file_size = (uint64_t)st.st_size;

    uint8_t zip_hdr[4] = {0};
    if (pread(src_fd, zip_hdr, 4, 0) != 4 ||
        zip_hdr[0] != 0x50 || zip_hdr[1] != 0x4b || zip_hdr[2] != 0x03 || zip_hdr[3] != 0x04) {
        fprintf(stderr, "Invalid ZIP magic header in %s\n", src_zip);
        close(src_fd);
        return 1;
    }

    mkdir("/mnt", 0755);
    mkdir("/mnt/ota_cache", 0755);
    umount2("/mnt/ota_cache", MNT_DETACH);

    if (mount(cache_blk, "/mnt/ota_cache", "ext4", MS_NOATIME, NULL) != 0) {
        fprintf(stderr, "mount(%s -> /mnt/ota_cache) failed: %s\n", cache_blk, strerror(errno));
        close(src_fd);
        return 1;
    }

    mkdir("/mnt/ota_cache/recovery", 0770);
    unlink("/mnt/ota_cache/recovery/command");
    unlink("/mnt/ota_cache/recovery/openrecoveryscript");
    unlink("/mnt/ota_cache/recovery/block.map");
    unlink("/mnt/ota_cache/recovery/update.zip");

    char target_blk[PATH_MAX];
    uint32_t blksize = 4096;
    struct blk_range *ranges = NULL;
    size_t range_count = 0;

    if (file_size <= CACHE_STAGE_MAX_BYTES) {
        const char *dst_zip = "/mnt/ota_cache/recovery/update.zip";
        int rc = copy_file_synced(src_fd, dst_zip, file_size);
        close(src_fd);
        if (rc != 0) {
            umount2("/mnt/ota_cache", MNT_DETACH);
            return 1;
        }
        snprintf(target_blk, sizeof(target_blk), "%s", cache_blk);
        if (verify_raw_unencrypted_zip(dst_zip, target_blk, file_size, &blksize, &ranges, &range_count) != 0) {
            umount2("/mnt/ota_cache", MNT_DETACH);
            return 1;
        }
    } else {
        char userdata_blk[PATH_MAX];
        if (resolve_block_dev(userdata_cands, userdata_blk, sizeof(userdata_blk)) != 0) {
            fprintf(stderr, "Failed to locate /userdata block device for >380 MiB OTA\n");
            close(src_fd);
            umount2("/mnt/ota_cache", MNT_DETACH);
            return 1;
        }
        snprintf(target_blk, sizeof(target_blk), "%s", userdata_blk);

        const char *raw_cands[] = {
            "/data/ota_raw/update.zip",
            "/data/preloads/ota_raw/update.zip",
            NULL
        };
        int staged_ok = 0;
        for (size_t c = 0; raw_cands[c] != NULL; c++) {
            const char *cand_zip = raw_cands[c];
            char dir_buf[PATH_MAX];
            snprintf(dir_buf, sizeof(dir_buf), "%s", cand_zip);
            char *slash = strrchr(dir_buf, '/');
            if (slash) *slash = '\0';
            struct stat dst_dir_st;
            if (stat(dir_buf, &dst_dir_st) != 0 || !S_ISDIR(dst_dir_st.st_mode)) {
                continue;
            }
            if (!same_file_path(src_zip, cand_zip)) {
                if (copy_file_synced(src_fd, cand_zip, file_size) != 0) {
                    unlink(cand_zip);
                    continue;
                }
            } else {
                fsync(src_fd);
                syncfs(src_fd);
            }
            if (verify_raw_unencrypted_zip(cand_zip, target_blk, file_size, &blksize, &ranges, &range_count) == 0) {
                int afd = open("/data/ota_raw/.armed", O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
                if (afd >= 0) {
                    if (write(afd, "1\n", 2) == 2) {
                        fsync(afd);
                    }
                    close(afd);
                }
                staged_ok = 1;
                break;
            }
            if (!same_file_path(src_zip, cand_zip)) {
                unlink(cand_zip);
            }
        }
        close(src_fd);
        if (!staged_ok) {
            fprintf(stderr, "Failed to stage unencrypted >380 MiB OTA onto %s\n", target_blk);
            umount2("/mnt/ota_cache", MNT_DETACH);
            return 1;
        }
    }

    uint64_t num_blocks = (file_size + (uint64_t)blksize - 1ULL) / (uint64_t)blksize;

    /* Write AOSP otautil MemMapping::MapBlockFile format to /mnt/ota_cache/recovery/block.map */
    const char *map_path = "/mnt/ota_cache/recovery/block.map";
    FILE *mfp = fopen(map_path, "wb");
    if (!mfp) {
        fprintf(stderr, "fopen(%s) failed: %s\n", map_path, strerror(errno));
        free(ranges);
        umount2("/mnt/ota_cache", MNT_DETACH);
        return 1;
    }
    fprintf(mfp, "%s\n%llu %u\n%zu\n",
            target_blk,
            (unsigned long long)file_size,
            blksize,
            range_count);
    for (size_t r = 0; r < range_count; r++) {
        fprintf(mfp, "%llu %llu\n",
                (unsigned long long)ranges[r].start,
                (unsigned long long)ranges[r].end);
    }
    fflush(mfp);
    fsync(fileno(mfp));
    fclose(mfp);
    chmod(map_path, 0644);
    free(ranges);

    int dir_fd = open("/mnt/ota_cache/recovery", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dir_fd >= 0) {
        fsync(dir_fd);
        close(dir_fd);
    }
    sync();
    if (umount("/mnt/ota_cache") != 0) {
        umount2("/mnt/ota_cache", MNT_DETACH);
    }

    /* Write AOSP 2048-byte bootloader_message directly to /dev/block/by-name/misc */
    struct bootloader_message_2k bcb;
    memset(&bcb, 0, sizeof(bcb));
    snprintf(bcb.command, sizeof(bcb.command), "boot-recovery");
    snprintf(bcb.recovery, sizeof(bcb.recovery),
             "recovery\n--update_package=@/cache/recovery/block.map\n");

    int misc_fd = open(misc_blk, O_RDWR | O_CLOEXEC);
    if (misc_fd < 0) {
        fprintf(stderr, "open(%s) failed: %s\n", misc_blk, strerror(errno));
        return 1;
    }
    if (pwrite(misc_fd, &bcb, sizeof(bcb), 0) != (ssize_t)sizeof(bcb) || fsync(misc_fd) != 0) {
        fprintf(stderr, "pwrite/fsync(%s) failed: %s\n", misc_blk, strerror(errno));
        close(misc_fd);
        return 1;
    }

    struct bootloader_message_2k verify_bcb;
    memset(&verify_bcb, 0, sizeof(verify_bcb));
    if (pread(misc_fd, &verify_bcb, sizeof(verify_bcb), 0) != (ssize_t)sizeof(verify_bcb) ||
        strcmp(verify_bcb.command, "boot-recovery") != 0 ||
        strstr(verify_bcb.recovery, "@/cache/recovery/block.map") == NULL) {
        fprintf(stderr, "BCB readback mismatch on %s\n", misc_blk);
        close(misc_fd);
        return 1;
    }
    close(misc_fd);
    sync();

    printf("OK staged %s (%llu bytes, %llu blocks, %zu ranges) -> @/cache/recovery/block.map on %s; armed %s\n",
           src_zip,
           (unsigned long long)file_size,
           (unsigned long long)num_blocks,
           range_count,
           target_blk,
           misc_blk);
    return 0;
}
