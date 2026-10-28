/*
 * hot-bootprep.c — Early boot pre-flight wrapper before exec'ing wland:
 *  1. Unpacks /var/local/fw/rmtfs/{oppostanvbk,oppodycnvbk}.img from .gz if staged (via libz.1.dylib).
 *  2. One-shot purges stale pre-Wave-5 IconsCache & MobileGestalt cache across all SystemGroup
 *     UUID/named containers so fixed HotMetal2 ("custom_fn") and /baseband ADT re-populate clean caches.
 *  3. Execs /var/local/bin/wland with original arguments.
 */
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

typedef void *gzFile;
typedef gzFile (*fn_gzopen_t)(const char *, const char *);
typedef int (*fn_gzread_t)(gzFile, void *, unsigned int);
typedef int (*fn_gzclose_t)(gzFile);

static int
unpack_gz_impl(const char *gz_path, const char *dst_path, off_t expected_size, int force)
{
	struct stat st;
	if (!force && stat(dst_path, &st) == 0 && st.st_size == expected_size) {
		return 0;
	}
	if (stat(gz_path, &st) != 0) {
		return 0;
	}
	void *zlib = dlopen("/usr/lib/libz.1.dylib", RTLD_NOW);
	if (!zlib) {
		return 0;
	}
	fn_gzopen_t p_gzopen = (fn_gzopen_t)dlsym(zlib, "gzopen");
	fn_gzread_t p_gzread = (fn_gzread_t)dlsym(zlib, "gzread");
	fn_gzclose_t p_gzclose = (fn_gzclose_t)dlsym(zlib, "gzclose");
	if (!p_gzopen || !p_gzread || !p_gzclose) {
		return 0;
	}

	char tmp_path[256];
	snprintf(tmp_path, sizeof(tmp_path), "%s.tmp", dst_path);
	gzFile gz = p_gzopen(gz_path, "rb");
	if (!gz) {
		return 0;
	}
	int ofd = open(tmp_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
	if (ofd < 0) {
		p_gzclose(gz);
		return 0;
	}
	static uint8_t buf[65536];
	off_t total = 0;
	for (;;) {
		int nr = p_gzread(gz, buf, sizeof(buf));
		if (nr <= 0) {
			break;
		}
		ssize_t off = 0;
		while (off < nr) {
			ssize_t nw = write(ofd, buf + off, (size_t)(nr - off));
			if (nw <= 0) {
				break;
			}
			off += nw;
		}
		total += off;
	}
	fsync(ofd);
	close(ofd);
	p_gzclose(gz);
	if (total == expected_size) {
		if (rename(tmp_path, dst_path) == 0) {
			unlink(gz_path);
			return 1;
		}
	}
	unlink(tmp_path);
	return 0;
}

static void
unpack_gz_if_needed(const char *gz_path, const char *dst_path, off_t expected_size)
{
	(void)unpack_gz_impl(gz_path, dst_path, expected_size, 0);
}

static int
unpack_gz_force_once(const char *gz_path, const char *dst_path, off_t expected_size)
{
	return unpack_gz_impl(gz_path, dst_path, expected_size, 1);
}

static void
purge_legacy_rmtfs_buffer(const char *buf_file)
{
	FILE *f = fopen(buf_file, "r");
	if (!f) {
		return;
	}
	long long fb = 0;
	unsigned long long fpa = 0;
	unsigned fver = 0;
	int n = fscanf(f, "%lld %llx %u", &fb, &fpa, &fver);
	fclose(f);
	if (n != 3 || fver < 5u) {
		unlink(buf_file);
	}
}

static void
purge_dir_recursive(const char *dir_path, int depth)
{
	if (depth > 5) {
		return;
	}
	DIR *d = opendir(dir_path);
	if (!d) {
		return;
	}
	struct dirent *de;
	char child[1024];
	while ((de = readdir(d)) != NULL) {
		if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0) {
			continue;
		}
		snprintf(child, sizeof(child), "%s/%s", dir_path, de->d_name);
		struct stat st;
		if (lstat(child, &st) == 0 && S_ISDIR(st.st_mode)) {
			purge_dir_recursive(child, depth + 1);
			rmdir(child);
		} else {
			unlink(child);
		}
	}
	closedir(d);
}

static void
purge_stale_caches_once(void)
{
	const char *sg_root = "/private/var/containers/Shared/SystemGroup";
	DIR *d = opendir(sg_root);
	if (d) {
		struct dirent *de;
		char p1[1024], p2[1024];
		while ((de = readdir(d)) != NULL) {
			if (de->d_name[0] == '.') {
				continue;
			}
			snprintf(p1, sizeof(p1), "%s/%s/Library/Caches/com.apple.IconsCache", sg_root, de->d_name);
			purge_dir_recursive(p1, 0);
			snprintf(p2, sizeof(p2), "%s/%s/Library/Caches/com.apple.MobileGestalt.plist", sg_root, de->d_name);
			unlink(p2);
		}
		closedir(d);
	}
	purge_dir_recursive("/private/var/containers/Shared/SystemGroup/systemgroup.com.apple.lsd.iconscache/Library/Caches", 0);
	purge_dir_recursive("/private/var/containers/Shared/SystemGroup/systemgroup.com.apple.mobilegestaltcache/Library/Caches", 0);
	purge_dir_recursive("/private/var/mobile/Library/Caches/com.apple.IconsCache", 0);
}

static void
ensure_keychain_dirs(void)
{
	mkdir("/private/var/Keychains", 0775);
	chown("/private/var/Keychains", 64, 64);
	chmod("/private/var/Keychains", 0775);

	mkdir("/private/var/Keychains/Analytics", 0775);
	chown("/private/var/Keychains/Analytics", 64, 64);
	chmod("/private/var/Keychains/Analytics", 0775);

	/*
	 * Case-sensitive HFSX rootfs has /private/var/Keychains, while parts of
	 * Security.framework / securityd look up lowercase /private/var/keychains.
	 * Merge any split-brain directory entries and alias keychains -> Keychains.
	 */
	struct stat kst;
	if (lstat("/private/var/keychains", &kst) == 0 && S_ISDIR(kst.st_mode)) {
		DIR *kd = opendir("/private/var/keychains");
		if (kd) {
			struct dirent *de;
			char src[512], dst[512];
			while ((de = readdir(kd)) != NULL) {
				if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0) {
					continue;
				}
				snprintf(src, sizeof(src), "/private/var/keychains/%s", de->d_name);
				snprintf(dst, sizeof(dst), "/private/var/Keychains/%s", de->d_name);
				struct stat dst_st;
				if (lstat(dst, &dst_st) != 0) {
					(void)rename(src, dst);
				} else {
					struct stat src_st;
					if (lstat(src, &src_st) == 0 && S_ISDIR(src_st.st_mode)) {
						purge_dir_recursive(src, 0);
						rmdir(src);
					} else {
						unlink(src);
					}
				}
			}
			closedir(kd);
		}
		rmdir("/private/var/keychains");
	}
	(void)symlink("Keychains", "/private/var/keychains");

	mkdir("/private/var/mobile/Library", 0755);
	mkdir("/private/var/mobile/Library/Keychains", 0775);
	chown("/private/var/mobile/Library/Keychains", 501, 64);
	chmod("/private/var/mobile/Library/Keychains", 0775);

	mkdir("/private/var/protected", 0755);
	chmod("/private/var/protected", 0755);
	mkdir("/private/var/protected/sfanalytics", 0775);
	chown("/private/var/protected/sfanalytics", 64, 64);
	chmod("/private/var/protected/sfanalytics", 0775);
}

int
main(int argc, char **argv, char **envp)
{
	(void)argc;
	ensure_keychain_dirs();
	mkdir("/private/var/local/fw", 0755);
	mkdir("/private/var/local/fw/rmtfs", 0755);
	unpack_gz_if_needed("/private/var/local/fw/rmtfs/oppostanvbk.img.gz",
	    "/private/var/local/fw/rmtfs/oppostanvbk.img", 10485760);
	unpack_gz_if_needed("/private/var/local/fw/rmtfs/oppodycnvbk.img.gz",
	    "/private/var/local/fw/rmtfs/oppodycnvbk.img", 10485760);

	const char *efs_stamp = "/private/var/local/fw/rmtfs/.wave6-efs-restored-v3";
	const char *rmtfs_buf = "/private/var/local/fw/rmtfs/.buffer";
	struct stat st;
	if (stat(efs_stamp, &st) != 0) {
		purge_legacy_rmtfs_buffer(rmtfs_buf);
		int r1 = unpack_gz_force_once("/private/var/local/fw/rmtfs/modemst1.img.gz",
		    "/private/var/local/fw/rmtfs/modemst1.img", 2097152);
		int r2 = unpack_gz_force_once("/private/var/local/fw/rmtfs/modemst2.img.gz",
		    "/private/var/local/fw/rmtfs/modemst2.img", 2097152);
		if (r1 || r2) {
			unlink(rmtfs_buf);
			int efd = open(efs_stamp, O_WRONLY | O_CREAT | O_TRUNC, 0644);
			if (efd >= 0) {
				(void)write(efd, "ok\n", 3);
				close(efd);
			}
		}
	}

	const char *stamp = "/private/var/local/.wave5-caches-purged-v3";
	if (stat(stamp, &st) != 0) {
		purge_stale_caches_once();
		int fd = open(stamp, O_WRONLY | O_CREAT | O_TRUNC, 0644);
		if (fd >= 0) {
			(void)write(fd, "ok\n", 3);
			close(fd);
		}
	}

	argv[0] = "/var/local/bin/wland";
	execve("/var/local/bin/wland", argv, envp);
	return 127;
}
