/*
 * hotstored v2: Root installer & WebApp packager daemon for HotStore 2.0 (127.0.0.1:29170).
 *
 * Capabilities on iOS 26.5.2 (RMX1931 / SWEET):
 *   1. Unpacks decrypted .ipa archives (streaming ZIP32/ZIP64 via zlib).
 *   2. Thins universal binaries (accepting both arm64 and arm64e slices) and rewrites
 *      non-hint ARMv8.3 PAC opcodes in-place on arm64e Mach-Os (excluding LC_DATA_IN_CODE).
 *   3. Neutralizes __RESTRICT segments (so @userapps libhot_appfix.dylib always loads)
 *      and embeds clean 4 KB SHA-256 CodeDirectory + merged XML entitlements with
 *      CodeDirectory.ident == CFBundleIdentifier.
 *   4. Provisions isolated /private/var/mobile/Containers/Data/Application/<bundleId> and
 *      Shared/AppGroup/<group> directories owned by mobile:mobile (501:501).
 *   5. Packages 1-tap standalone native UIKit + WKWebView .app bundles (HotWebAppStub)
 *      with real App Store icons converted to PNG via ImageIO + CoreGraphics.
 *   6. Manages per-app HotMetal2 GPU / CPU mode (/var/local/mtl2-clients) and an optional
 *      toggleable Wi-Fi LAN .ipa dropzone on port 29172.
 */
#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>
#import <ImageIO/ImageIO.h>
#include <CommonCrypto/CommonDigest.h>
#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <libkern/OSByteOrder.h>
#include <mach-o/fat.h>
#include <mach-o/loader.h>
#include <net/if.h>
#include <netinet/in.h>
#include <pthread.h>
#include <signal.h>
#include <spawn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#include <zlib.h>

extern char **environ;

#define HOTSTORED_PORT        29170
#define HOTUPLOAD_PORT        29172
#define STAGE_ROOT              "/private/var/local/store-stage"
#define LOCAL_IPA_DIR           "/private/var/local/ipa"
#define STAGED_SYSTEM_APPS_DIR  "/private/var/staged_system_apps"
#define APP_PLACEHOLDERS_DIR    "/System/Library/AppPlaceholders"
#define STAGED_PLACEHOLDERS_DIR "/private/var/local/staged-placeholders"
#define REG_STAMP_PATH          "/private/var/local/.hotstore-registered-v4"
#define MTL2_CLIENTS_PATH       "/private/var/local/mtl2-clients"
#define APPFIX_LOG_PATH         "/private/var/local/appfix.log"
#define MARKER_FILE             ".hotstore_installed"

#ifndef CPU_SUBTYPE_ARM64E
#define CPU_SUBTYPE_ARM64E      ((cpu_subtype_t)2)
#endif

static pthread_mutex_t g_job_lock = PTHREAD_MUTEX_INITIALIZER;
static BOOL g_busy = NO;
static NSString *g_state = @"idle";
static double g_progress = 0.0;
static NSString *g_step = @"Ready";
static NSString *g_app_name = @"";
static NSString *g_bundle_id = @"";
static NSString *g_error = @"";

static pthread_mutex_t g_lan_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_lan_srv = -1;

static void set_job(NSString *state, double progress, NSString *step, NSString *appName, NSString *bundleId, NSString *err)
{
	pthread_mutex_lock(&g_job_lock);
	if (state) g_state = [state copy];
	if (progress >= 0.0) g_progress = progress;
	if (step) g_step = [step copy];
	if (appName) g_app_name = [appName copy];
	if (bundleId) g_bundle_id = [bundleId copy];
	if (err) g_error = [err copy];
	pthread_mutex_unlock(&g_job_lock);
}

static NSString *sanitize_slug(NSString *raw)
{
	if (raw.length == 0) return @"app";
	NSMutableString *out = [NSMutableString stringWithCapacity:raw.length];
	for (NSUInteger i = 0; i < raw.length; i++) {
		unichar c = [raw characterAtIndex:i];
		if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
		    (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') {
			[out appendFormat:@"%C", c];
		} else {
			[out appendString:@"_"];
		}
	}
	return out.length > 0 ? out : @"app";
}

static int run_tool_env(const char *prog, char *const argv[], char *const envp[])
{
	pid_t pid = 0;
	int status = 0;
	int rc = posix_spawn(&pid, prog, NULL, NULL, argv, envp ? envp : environ);
	if (rc != 0) {
		fprintf(stderr, "hotstored: posix_spawn %s failed: %d\n", prog, rc);
		return -1;
	}
	while (waitpid(pid, &status, 0) < 0) {
		if (errno != EINTR) return -1;
	}
	return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

static int run_tool(const char *prog, char *const argv[])
{
	return run_tool_env(prog, argv, environ);
}

static int run_lsreg_mobile_args(NSArray<NSString *> *args)
{
	char *mobile_env[] = {
		(char *)"PATH=/private/var/local/bin:/var/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
		(char *)"HOME=/var/mobile",
		(char *)"LSREG_UID=501",
		NULL
	};
	NSUInteger cnt = args.count;
	char **argv = (char **)calloc(cnt + 2, sizeof(char *));
	if (!argv) return -1;
	argv[0] = (char *)"/var/local/bin/lsreg";
	for (NSUInteger i = 0; i < cnt; i++) {
		argv[i + 1] = (char *)args[i].UTF8String;
	}
	argv[cnt + 1] = NULL;
	int rc = run_tool_env("/var/local/bin/lsreg", argv, mobile_env);
	free(argv);
	return rc;
}

static int run_lsreg_rebuild(void)
{
	int rc_mob = run_lsreg_mobile_args(@[@"-r"]);
	char *const argv[] = { (char *)"/var/local/bin/lsreg", (char *)"-r", NULL };
	int rc_root = run_tool("/var/local/bin/lsreg", argv);
	return rc_mob == 0 ? 0 : rc_root;
}

static void chown_chmod_recursive_uid(NSString *rootPath, uid_t uid, gid_t gid)
{
	NSFileManager *fm = [NSFileManager defaultManager];
	NSDirectoryEnumerator *en = [fm enumeratorAtPath:rootPath];
	chown(rootPath.fileSystemRepresentation, uid, gid);
	chmod(rootPath.fileSystemRepresentation, 0755);
	NSString *rel;
	while ((rel = [en nextObject]) != nil) {
		NSString *full = [rootPath stringByAppendingPathComponent:rel];
		const char *cpath = full.fileSystemRepresentation;
		struct stat st;
		if (lstat(cpath, &st) != 0) continue;
		lchown(cpath, uid, gid);
		if (S_ISLNK(st.st_mode)) continue;
		if (S_ISDIR(st.st_mode)) {
			chmod(cpath, 0755);
		}
	}
}

static void chown_chmod_recursive(NSString *rootPath)
{
	chown_chmod_recursive_uid(rootPath, 0, 0);
}

static void provision_app_containers(NSString *bundleId, NSArray *appGroups)
{
	NSFileManager *fm = [NSFileManager defaultManager];
	NSString *cleanBid = sanitize_slug(bundleId);
	NSString *base = [@"/private/var/mobile/Containers/Data/Application" stringByAppendingPathComponent:cleanBid];
	NSArray<NSString *> *subs = @[
		@"",
		@"Documents",
		@"Library",
		@"Library/Preferences",
		@"Library/Caches",
		@"Library/Application Support",
		@"Library/SplashBoard",
		@"SystemData",
		@"tmp",
	];
	for (NSString *s in subs) {
		NSString *p = s.length > 0 ? [base stringByAppendingPathComponent:s] : base;
		[fm createDirectoryAtPath:p withIntermediateDirectories:YES attributes:nil error:nil];
		chown(p.fileSystemRepresentation, 501, 501);
		chmod(p.fileSystemRepresentation, 0755);
	}
	for (id grp in appGroups) {
		if (![grp isKindOfClass:[NSString class]] || [(NSString *)grp length] == 0) continue;
		NSString *cleanGrp = sanitize_slug((NSString *)grp);
		NSString *gbase = [@"/private/var/mobile/Containers/Shared/AppGroup" stringByAppendingPathComponent:cleanGrp];
		for (NSString *gs in @[@"", @"Documents", @"Library", @"Library/Preferences", @"Library/Caches"]) {
			NSString *gp = gs.length > 0 ? [gbase stringByAppendingPathComponent:gs] : gbase;
			[fm createDirectoryAtPath:gp withIntermediateDirectories:YES attributes:nil error:nil];
			chown(gp.fileSystemRepresentation, 501, 501);
			chmod(gp.fileSystemRepresentation, 0755);
		}
	}
}

/* ---- Streaming ZIP32 / ZIP64 extractor via zlib ---- */

static uint16_t rd16le(const uint8_t *p) { uint16_t v; memcpy(&v, p, 2); return OSSwapLittleToHostInt16(v); }
static uint32_t rd32le(const uint8_t *p) { uint32_t v; memcpy(&v, p, 4); return OSSwapLittleToHostInt32(v); }
static uint64_t rd64le(const uint8_t *p) { uint64_t v; memcpy(&v, p, 8); return OSSwapLittleToHostInt64(v); }

static BOOL pread_all(int fd, void *buf, size_t len, off_t off)
{
	uint8_t *p = (uint8_t *)buf;
	size_t done = 0;
	while (done < len) {
		ssize_t n = pread(fd, p + done, len - done, off + (off_t)done);
		if (n <= 0) return NO;
		done += (size_t)n;
	}
	return YES;
}

static BOOL write_all(int fd, const void *buf, size_t len)
{
	const uint8_t *p = (const uint8_t *)buf;
	size_t done = 0;
	while (done < len) {
		ssize_t n = write(fd, p + done, len - done);
		if (n <= 0) {
			if (n < 0 && errno == EINTR) continue;
			return NO;
		}
		done += (size_t)n;
	}
	return YES;
}

static BOOL safe_rel_path(NSString *rel)
{
	if (rel.length == 0 || [rel hasPrefix:@"/"] || [rel containsString:@".."]) {
		for (NSString *comp in [rel pathComponents]) {
			if ([comp isEqualToString:@".."] || [comp isEqualToString:@"/"]) return NO;
		}
		if ([rel hasPrefix:@"/"]) return NO;
	}
	return rel.length > 0;
}

static BOOL extract_ipa_zip(NSString *ipaPath, NSString *destDir, double pStart, double pEnd, NSString **errOut)
{
	int fd = open(ipaPath.fileSystemRepresentation, O_RDONLY);
	if (fd < 0) {
		if (errOut) *errOut = [NSString stringWithFormat:@"Cannot open IPA (%s)", strerror(errno)];
		return NO;
	}
	struct stat st;
	if (fstat(fd, &st) != 0 || st.st_size < 22) {
		close(fd);
		if (errOut) *errOut = @"Invalid or empty .ipa file";
		return NO;
	}
	uint64_t fileSize = (uint64_t)st.st_size;
	size_t tailLen = (size_t)MIN((uint64_t)65558, fileSize);
	uint8_t *tail = (uint8_t *)malloc(tailLen);
	if (!tail || !pread_all(fd, tail, tailLen, (off_t)(fileSize - tailLen))) {
		free(tail);
		close(fd);
		if (errOut) *errOut = @"Failed reading ZIP directory";
		return NO;
	}

	ssize_t eocdPos = -1;
	for (ssize_t i = (ssize_t)tailLen - 22; i >= 0; i--) {
		if (rd32le(tail + i) == 0x06054b50u) {
			eocdPos = i;
			break;
		}
	}
	if (eocdPos < 0) {
		free(tail);
		close(fd);
		if (errOut) *errOut = @"Not a valid ZIP/.ipa archive (missing EOCD)";
		return NO;
	}

	uint64_t cdCount = rd16le(tail + eocdPos + 10);
	uint64_t cdSize  = rd32le(tail + eocdPos + 12);
	uint64_t cdOff   = rd32le(tail + eocdPos + 16);

	if (eocdPos >= 20 && rd32le(tail + eocdPos - 20) == 0x07064b50u) {
		uint64_t z64Off = rd64le(tail + eocdPos - 12);
		uint8_t z64[56];
		if (z64Off + 56 <= fileSize && pread_all(fd, z64, sizeof(z64), (off_t)z64Off) &&
		    rd32le(z64) == 0x06064b50u) {
			cdCount = rd64le(z64 + 32);
			cdSize  = rd64le(z64 + 40);
			cdOff   = rd64le(z64 + 48);
		}
	}
	free(tail);

	if (cdOff + cdSize > fileSize || cdSize > (128u << 20)) {
		close(fd);
		if (errOut) *errOut = @"Corrupt ZIP central directory bounds";
		return NO;
	}

	uint8_t *cd = (uint8_t *)malloc((size_t)cdSize);
	if (!cd || !pread_all(fd, cd, (size_t)cdSize, (off_t)cdOff)) {
		free(cd);
		close(fd);
		if (errOut) *errOut = @"Failed reading ZIP central directory";
		return NO;
	}

	NSFileManager *fm = [NSFileManager defaultManager];
	uint8_t *inBuf = (uint8_t *)malloc(65536);
	uint8_t *outBuf = (uint8_t *)malloc(65536);
	size_t pos = 0;
	BOOL ok = YES;

	for (uint64_t idx = 0; idx < cdCount && pos + 46 <= (size_t)cdSize; idx++) {
		if (rd32le(cd + pos) != 0x02014b50u) {
			ok = NO;
			if (errOut) *errOut = @"Corrupt central directory entry";
			break;
		}
		uint16_t method  = rd16le(cd + pos + 10);
		uint64_t compSz  = rd32le(cd + pos + 20);
		uint64_t uncompSz = rd32le(cd + pos + 24);
		uint16_t nameLen = rd16le(cd + pos + 28);
		uint16_t extraLen = rd16le(cd + pos + 30);
		uint16_t commLen = rd16le(cd + pos + 32);
		uint32_t extAttr = rd32le(cd + pos + 38);
		uint64_t lfhOff  = rd32le(cd + pos + 42);

		if (pos + 46 + nameLen + extraLen + commLen > (size_t)cdSize) {
			ok = NO;
			if (errOut) *errOut = @"Truncated central directory entry";
			break;
		}

		const uint8_t *extra = cd + pos + 46 + nameLen;
		size_t ep = 0;
		while (ep + 4 <= extraLen) {
			uint16_t tag = rd16le(extra + ep);
			uint16_t elen = rd16le(extra + ep + 2);
			if (ep + 4 + elen > extraLen) break;
			if (tag == 0x0001) {
				size_t zp = ep + 4;
				if (uncompSz == 0xffffffffu && zp + 8 <= ep + 4 + elen) { uncompSz = rd64le(extra + zp); zp += 8; }
				if (compSz == 0xffffffffu && zp + 8 <= ep + 4 + elen)   { compSz   = rd64le(extra + zp); zp += 8; }
				if (lfhOff == 0xffffffffu && zp + 8 <= ep + 4 + elen)   { lfhOff   = rd64le(extra + zp); zp += 8; }
			}
			ep += 4 + elen;
		}

		NSString *entryName = [[NSString alloc] initWithBytes:(cd + pos + 46) length:nameLen encoding:NSUTF8StringEncoding];
		if (!entryName) {
			entryName = [[NSString alloc] initWithBytes:(cd + pos + 46) length:nameLen encoding:NSISOLatin1StringEncoding];
		}
		pos += 46 + nameLen + extraLen + commLen;

		if (!entryName || ![entryName hasPrefix:@"Payload/"] || !safe_rel_path(entryName)) {
			continue;
		}

		if ((idx & 31) == 0 && cdCount > 0) {
			double frac = pStart + (pEnd - pStart) * ((double)idx / (double)cdCount);
			set_job(@"extracting", frac, [NSString stringWithFormat:@"Extracting (%llu/%llu)...",
			    (unsigned long long)idx, (unsigned long long)cdCount], nil, nil, nil);
		}

		NSString *outPath = [destDir stringByAppendingPathComponent:entryName];
		uint16_t unixMode = (uint16_t)(extAttr >> 16);
		BOOL isDir = [entryName hasSuffix:@"/"] || ((unixMode & S_IFMT) == S_IFDIR);
		BOOL isSymlink = ((unixMode & S_IFMT) == S_IFLNK);

		if (isDir) {
			[fm createDirectoryAtPath:outPath withIntermediateDirectories:YES attributes:nil error:nil];
			continue;
		}

		[fm createDirectoryAtPath:[outPath stringByDeletingLastPathComponent]
		    withIntermediateDirectories:YES attributes:nil error:nil];

		uint8_t lfh[30];
		if (lfhOff + 30 > fileSize || !pread_all(fd, lfh, 30, (off_t)lfhOff) || rd32le(lfh) != 0x04034b50u) {
			ok = NO;
			if (errOut) *errOut = @"Invalid local file header in IPA";
			break;
		}
		uint64_t dataOff = lfhOff + 30 + rd16le(lfh + 26) + rd16le(lfh + 28);
		if (dataOff + compSz > fileSize) {
			ok = NO;
			if (errOut) *errOut = @"Compressed entry exceeds archive size";
			break;
		}

		if (isSymlink) {
			if (uncompSz > 4096) continue;
			NSMutableData *symData = [NSMutableData dataWithCapacity:(NSUInteger)uncompSz];
			if (method == 0) {
				symData.length = (NSUInteger)compSz;
				if (!pread_all(fd, symData.mutableBytes, (size_t)compSz, (off_t)dataOff)) { ok = NO; break; }
			} else if (method == 8) {
				z_stream zs; memset(&zs, 0, sizeof(zs));
				if (inflateInit2(&zs, -MAX_WBITS) != Z_OK) { ok = NO; break; }
				uint64_t rem = compSz, cur = dataOff;
				while (rem > 0) {
					size_t chunk = (size_t)MIN(rem, (uint64_t)65536);
					if (!pread_all(fd, inBuf, chunk, (off_t)cur)) { ok = NO; break; }
					cur += chunk; rem -= chunk;
					zs.next_in = inBuf; zs.avail_in = (uInt)chunk;
					do {
						zs.next_out = outBuf; zs.avail_out = 65536;
						int zrc = inflate(&zs, rem == 0 ? Z_FINISH : Z_NO_FLUSH);
						if (zrc != Z_OK && zrc != Z_STREAM_END && zrc != Z_BUF_ERROR) { ok = NO; break; }
						size_t got = 65536 - zs.avail_out;
						if (got > 0) [symData appendBytes:outBuf length:got];
					} while (zs.avail_out == 0 && ok);
					if (!ok) break;
				}
				inflateEnd(&zs);
				if (!ok) break;
			} else {
				continue;
			}
			NSString *target = [[NSString alloc] initWithData:symData encoding:NSUTF8StringEncoding];
			if (target.length > 0 && ![target hasPrefix:@"/"]) {
				unlink(outPath.fileSystemRepresentation);
				symlink(target.fileSystemRepresentation, outPath.fileSystemRepresentation);
			}
			continue;
		}

		unlink(outPath.fileSystemRepresentation);
		int outFd = open(outPath.fileSystemRepresentation, O_CREAT | O_TRUNC | O_WRONLY,
		    (unixMode & 0111) ? 0755 : 0644);
		if (outFd < 0) {
			ok = NO;
			if (errOut) *errOut = [NSString stringWithFormat:@"Cannot create file %@", entryName];
			break;
		}

		if (method == 0) {
			uint64_t rem = compSz, cur = dataOff;
			while (rem > 0) {
				size_t chunk = (size_t)MIN(rem, (uint64_t)65536);
				if (!pread_all(fd, inBuf, chunk, (off_t)cur) || !write_all(outFd, inBuf, chunk)) {
					ok = NO; break;
				}
				cur += chunk; rem -= chunk;
			}
		} else if (method == 8) {
			z_stream zs;
			memset(&zs, 0, sizeof(zs));
			if (inflateInit2(&zs, -MAX_WBITS) != Z_OK) {
				close(outFd); ok = NO; break;
			}
			uint64_t rem = compSz, cur = dataOff;
			while (rem > 0) {
				size_t chunk = (size_t)MIN(rem, (uint64_t)65536);
				if (!pread_all(fd, inBuf, chunk, (off_t)cur)) { ok = NO; break; }
				cur += chunk; rem -= chunk;
				zs.next_in = inBuf;
				zs.avail_in = (uInt)chunk;
				do {
					zs.next_out = outBuf;
					zs.avail_out = 65536;
					int zrc = inflate(&zs, rem == 0 ? Z_FINISH : Z_NO_FLUSH);
					if (zrc != Z_OK && zrc != Z_STREAM_END && zrc != Z_BUF_ERROR) {
						ok = NO;
						if (errOut) *errOut = [NSString stringWithFormat:@"Decompression error (%d) in %@", zrc, entryName];
						break;
					}
					size_t got = 65536 - zs.avail_out;
					if (got > 0 && !write_all(outFd, outBuf, got)) { ok = NO; break; }
				} while (zs.avail_out == 0 && ok);
				if (!ok) break;
			}
			inflateEnd(&zs);
		} else {
			if (errOut) *errOut = [NSString stringWithFormat:@"Unsupported ZIP compression method %u in %@", method, entryName];
			ok = NO;
		}
		close(outFd);
		if (!ok) break;
	}

	free(inBuf);
	free(outBuf);
	free(cd);
	close(fd);
	return ok;
}

/* ---- Embedded ARMv8.3 PAC -> ARMv8.0 In-Place Rewriter (from tools/de_pac_fast.c) ---- */

static inline uint32_t rewrite_pac_insn(uint32_t insn, int *changed)
{
	if ((insn & 0xfffffbffu) == 0xd65f0bffu) {
		*changed = 1;
		return 0xd65f03c0u; /* retaa / retab -> ret */
	}
	if ((insn & 0xfffff81fu) == 0xd61f081fu ||
	    (insn & 0xfffff800u) == 0xd71f0800u) {
		*changed = 1;
		return 0xd61f0000u | (insn & 0x000003e0u); /* bra* -> br Xn */
	}
	if ((insn & 0xfffff81fu) == 0xd63f081fu ||
	    (insn & 0xfffff800u) == 0xd73f0800u) {
		*changed = 1;
		return 0xd63f0000u | (insn & 0x000003e0u); /* blra* -> blr Xn */
	}
	if ((insn & 0xffffe000u) == 0xdac10000u ||
	    (insn & 0xffffe3e0u) == 0xdac123e0u ||
	    (insn & 0xfffffbe0u) == 0xdac143e0u) {
		*changed = 1;
		return 0xd503201fu; /* pacia..autdzb, xpaci, xpacd -> nop */
	}
	if ((insn & 0xff200400u) == 0xf8200400u && (insn & 0x00400800u) == 0u) {
		uint32_t imm9 = (insn >> 12) & 0x1ffu;
		*changed = 1;
		return 0xf9400000u | (imm9 << 10) | (insn & 0x000003ffu); /* ldra* (W=0,S=0) -> ldr */
	}
	return insn;
}

typedef struct {
	uint32_t offset;
	uint16_t length;
	uint16_t kind;
} dice_entry_t;

static int cmp_dice_entry(const void *a, const void *b)
{
	uint32_t oa = ((const dice_entry_t *)a)->offset;
	uint32_t ob = ((const dice_entry_t *)b)->offset;
	return (oa > ob) - (oa < ob);
}

static uint64_t depac_macho_fd(int rwFd, const struct mach_header_64 *mhdr, const uint8_t *cmds, uint64_t fileSize)
{
	/* Collect LC_DATA_IN_CODE intervals if present */
	dice_entry_t *dice = NULL;
	size_t diceCount = 0;

	size_t off = 0;
	for (uint32_t i = 0; i < mhdr->ncmds && off + sizeof(struct load_command) <= mhdr->sizeofcmds; i++) {
		const struct load_command *lc = (const struct load_command *)(cmds + off);
		if (lc->cmdsize < sizeof(struct load_command) || off + lc->cmdsize > mhdr->sizeofcmds) break;
		if (lc->cmd == LC_DATA_IN_CODE && lc->cmdsize >= sizeof(struct linkedit_data_command)) {
			const struct linkedit_data_command *dc = (const struct linkedit_data_command *)lc;
			if (dc->datasize >= sizeof(dice_entry_t) &&
			    (uint64_t)dc->dataoff + dc->datasize <= fileSize &&
			    dc->datasize <= (16u << 20)) {
				dice = (dice_entry_t *)malloc(dc->datasize);
				if (dice && pread_all(rwFd, dice, dc->datasize, (off_t)dc->dataoff)) {
					diceCount = dc->datasize / sizeof(dice_entry_t);
					qsort(dice, diceCount, sizeof(dice_entry_t), cmp_dice_entry);
				}
			}
			break;
		}
		off += lc->cmdsize;
	}

	uint8_t *chunk = (uint8_t *)malloc(65536);
	if (!chunk) {
		free(dice);
		return 0;
	}

	uint64_t rewritten = 0;
	off = 0;
	for (uint32_t i = 0; i < mhdr->ncmds && off + sizeof(struct load_command) <= mhdr->sizeofcmds; i++) {
		const struct load_command *lc = (const struct load_command *)(cmds + off);
		if (lc->cmdsize < sizeof(struct load_command) || off + lc->cmdsize > mhdr->sizeofcmds) break;
		if (lc->cmd == LC_SEGMENT_64 && lc->cmdsize >= sizeof(struct segment_command_64)) {
			const struct segment_command_64 *seg = (const struct segment_command_64 *)lc;
			const struct section_64 *sects = (const struct section_64 *)(seg + 1);
			uint32_t maxSects = (lc->cmdsize - (uint32_t)sizeof(*seg)) / (uint32_t)sizeof(*sects);
			for (uint32_t s = 0; s < seg->nsects && s < maxSects; s++) {
				uint32_t sflags = sects[s].flags;
				uint32_t stype = sflags & SECTION_TYPE;
				if (stype == S_ZEROFILL || stype == S_GB_ZEROFILL || stype == S_THREAD_LOCAL_ZEROFILL) continue;
				if ((sflags & (S_ATTR_PURE_INSTRUCTIONS | S_ATTR_SOME_INSTRUCTIONS)) == 0) continue;
				uint64_t sOff = sects[s].offset;
				uint64_t sLen = sects[s].size & ~3ULL;
				if (sLen == 0 || sOff + sLen > fileSize) continue;

				size_t dIdx = 0;
				while (dIdx < diceCount && (uint64_t)dice[dIdx].offset + dice[dIdx].length <= sOff) {
					dIdx++;
				}

				uint64_t cur = sOff;
				uint64_t end = sOff + sLen;
				while (cur < end) {
					size_t n = (size_t)MIN((uint64_t)65536, end - cur);
					n &= ~3u;
					if (n == 0 || !pread_all(rwFd, chunk, n, (off_t)cur)) break;
					BOOL dirty = NO;
					for (size_t p = 0; p < n; p += 4) {
						uint64_t fpos = cur + p;
						while (dIdx < diceCount && (uint64_t)dice[dIdx].offset + dice[dIdx].length <= fpos) {
							dIdx++;
						}
						if (dIdx < diceCount && fpos >= dice[dIdx].offset &&
						    fpos < (uint64_t)dice[dIdx].offset + dice[dIdx].length) {
							continue;
						}
						uint32_t insn = rd32le(chunk + p);
						uint32_t top = insn >> 28;
						if (top != 0xd && top != 0xf) continue;
						int chg = 0;
						uint32_t out = rewrite_pac_insn(insn, &chg);
						if (chg) {
							uint32_t leOut = OSSwapHostToLittleInt32(out);
							memcpy(chunk + p, &leOut, 4);
							rewritten++;
							dirty = YES;
						}
					}
					if (dirty) {
						(void)pwrite(rwFd, chunk, n, (off_t)cur);
					}
					cur += n;
				}
			}
		}
		off += lc->cmdsize;
	}

	free(chunk);
	free(dice);
	return rewritten;
}

/* ---- Mach-O thinning, FairPlay cryptid check & ad-hoc SHA-256 signing ---- */

static NSData *build_adhoc_superblob(int fd, uint64_t codeLimit, uint64_t execSegBase, uint64_t execSegLimit,
                                     BOOL isMainBinary, NSString *identStr, NSData *xmlEnts)
{
	const uint32_t pageShift = 12;
	const uint32_t pageSize = 1u << pageShift;
	uint32_t nCodeSlots = (uint32_t)((codeLimit + pageSize - 1) / pageSize);
	uint32_t nSpecialSlots = xmlEnts.length > 0 ? 5 : 0;

	const char *identC = (identStr.length > 0 ? identStr.UTF8String : "com.ios26hot.app");
	size_t identLen = strlen(identC) + 1;

	NSMutableData *entBlob = nil;
	uint8_t entHash[CC_SHA256_DIGEST_LENGTH];
	if (nSpecialSlots > 0) {
		uint32_t magic = OSSwapHostToBigInt32(0xfade7171u);
		uint32_t len   = OSSwapHostToBigInt32((uint32_t)(8 + xmlEnts.length));
		entBlob = [NSMutableData dataWithCapacity:8 + xmlEnts.length];
		[entBlob appendBytes:&magic length:4];
		[entBlob appendBytes:&len length:4];
		[entBlob appendData:xmlEnts];
		CC_SHA256(entBlob.bytes, (CC_LONG)entBlob.length, entHash);
	}

	uint32_t hdrLen = 88;
	uint32_t identOff = hdrLen;
	uint32_t hashOff = identOff + (uint32_t)identLen + nSpecialSlots * 32;
	uint32_t cdLen = hashOff + nCodeSlots * 32;

	NSMutableData *cd = [NSMutableData dataWithCapacity:cdLen];
	uint32_t w32[12];
	w32[0] = OSSwapHostToBigInt32(0xfade0c02u);
	w32[1] = OSSwapHostToBigInt32(cdLen);
	w32[2] = OSSwapHostToBigInt32(0x00020400u);
	w32[3] = OSSwapHostToBigInt32(0x00000002u); /* CS_ADHOC */
	w32[4] = OSSwapHostToBigInt32(hashOff);
	w32[5] = OSSwapHostToBigInt32(identOff);
	w32[6] = OSSwapHostToBigInt32(nSpecialSlots);
	w32[7] = OSSwapHostToBigInt32(nCodeSlots);
	w32[8] = OSSwapHostToBigInt32(codeLimit <= 0xffffffffULL ? (uint32_t)codeLimit : 0xffffffffu);
	[cd appendBytes:w32 length:9 * 4];

	uint8_t b4[4] = { 32, 2, 0, (uint8_t)pageShift };
	[cd appendBytes:b4 length:4];
	uint32_t zeros4[4] = { 0, 0, 0, 0 };
	[cd appendBytes:zeros4 length:16];

	uint64_t q64[4];
	q64[0] = OSSwapHostToBigInt64(codeLimit > 0xffffffffULL ? codeLimit : 0);
	q64[1] = OSSwapHostToBigInt64(execSegBase);
	q64[2] = OSSwapHostToBigInt64(execSegLimit);
	q64[3] = OSSwapHostToBigInt64(isMainBinary ? 1ULL : 0ULL);
	[cd appendBytes:q64 length:32];

	[cd appendBytes:identC length:identLen];
	if (nSpecialSlots > 0) {
		[cd appendBytes:entHash length:32];
		uint8_t z4[128] = { 0 };
		[cd appendBytes:z4 length:128];
	}

	uint8_t pageBuf[4096];
	for (uint64_t off = 0; off < codeLimit; off += pageSize) {
		size_t chunk = (size_t)MIN((uint64_t)pageSize, codeLimit - off);
		if (!pread_all(fd, pageBuf, chunk, (off_t)off)) return nil;
		uint8_t digest[CC_SHA256_DIGEST_LENGTH];
		CC_SHA256(pageBuf, (CC_LONG)chunk, digest);
		[cd appendBytes:digest length:32];
	}

	uint32_t blobCount = entBlob ? 2 : 1;
	uint32_t sbHdr = 12 + blobCount * 8;
	uint32_t sbLen = sbHdr + (uint32_t)cd.length + (uint32_t)(entBlob ? entBlob.length : 0);

	NSMutableData *sb = [NSMutableData dataWithCapacity:sbLen];
	uint32_t sbHead[3] = {
		OSSwapHostToBigInt32(0xfade0cc0u),
		OSSwapHostToBigInt32(sbLen),
		OSSwapHostToBigInt32(blobCount),
	};
	[sb appendBytes:sbHead length:12];
	uint32_t idx0[2] = { OSSwapHostToBigInt32(0), OSSwapHostToBigInt32(sbHdr) };
	[sb appendBytes:idx0 length:8];
	if (entBlob) {
		uint32_t idx1[2] = { OSSwapHostToBigInt32(5), OSSwapHostToBigInt32(sbHdr + (uint32_t)cd.length) };
		[sb appendBytes:idx1 length:8];
	}
	[sb appendData:cd];
	if (entBlob) [sb appendData:entBlob];
	return sb;
}

static NSData *merged_app_entitlements(NSData *existingXml, NSString *bundleId, NSArray **outGroups)
{
	NSString *bid = bundleId.length > 0 ? bundleId : @"com.ios26hot.app";
	NSMutableDictionary *dict = [NSMutableDictionary dictionary];
	if (existingXml.length > 0) {
		id parsed = [NSPropertyListSerialization propertyListWithData:existingXml
		                                                      options:NSPropertyListMutableContainersAndLeaves
		                                                       format:NULL
		                                                        error:nil];
		if ([parsed isKindOfClass:[NSDictionary class]]) {
			[dict addEntriesFromDictionary:(NSDictionary *)parsed];
		}
	}

	NSArray *groups = [dict[@"com.apple.security.application-groups"] isKindOfClass:[NSArray class]]
	                ? dict[@"com.apple.security.application-groups"] : @[];
	if (outGroups) *outGroups = groups;

	dict[@"application-identifier"] = [NSString stringWithFormat:@"0000000000.%@", bid];
	dict[@"com.apple.developer.team-identifier"] = @"0000000000";
	dict[@"get-task-allow"] = @YES;
	dict[@"com.apple.private.security.no-sandbox"] = @YES;

	NSMutableArray *kcGroups = [NSMutableArray array];
	if ([dict[@"keychain-access-groups"] isKindOfClass:[NSArray class]]) {
		for (id g in dict[@"keychain-access-groups"]) {
			if ([g isKindOfClass:[NSString class]] && ![g isEqualToString:@"*"] && ![kcGroups containsObject:g]) {
				[kcGroups addObject:g];
			}
		}
	}
	for (NSString *extra in @[
		[NSString stringWithFormat:@"0000000000.%@", bid],
		bid
	]) {
		if (![kcGroups containsObject:extra]) [kcGroups addObject:extra];
	}
	dict[@"keychain-access-groups"] = kcGroups;

	return [NSPropertyListSerialization dataWithPropertyList:dict
	                                                  format:NSPropertyListXMLFormat_v1_0
	                                                 options:0
	                                                   error:nil];
}

static BOOL prepare_macho_file(NSString *filePath, BOOL isMainExec, NSString *bundleId, NSArray **outGroups, NSString **errOut)
{
	const char *cpath = filePath.fileSystemRepresentation;
	struct stat st;
	if (lstat(cpath, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size < 64) {
		return YES;
	}

	int fd = open(cpath, O_RDONLY);
	if (fd < 0) return YES;
	uint32_t magic = 0;
	if (!pread_all(fd, &magic, 4, 0)) {
		close(fd);
		return YES;
	}

	/* Thin universal Mach-O (FAT_MAGIC / FAT_MAGIC_64) to arm64 or arm64e slice */
	if (magic == FAT_MAGIC || magic == FAT_CIGAM || magic == FAT_MAGIC_64 || magic == FAT_CIGAM_64) {
		BOOL is64 = (magic == FAT_MAGIC_64 || magic == FAT_CIGAM_64);
		struct fat_header fhdr;
		if (!pread_all(fd, &fhdr, sizeof(fhdr), 0)) { close(fd); return YES; }
		uint32_t narch = OSSwapBigToHostInt32(fhdr.nfat_arch);
		if (narch > 32) { close(fd); return YES; }

		uint64_t arm64Off = 0, arm64Size = 0;
		uint64_t arm64eOff = 0, arm64eSize = 0;
		for (uint32_t i = 0; i < narch; i++) {
			cpu_type_t ct = 0;
			cpu_subtype_t cs = 0;
			uint64_t sOff = 0, sSz = 0;
			if (!is64) {
				struct fat_arch fa;
				if (!pread_all(fd, &fa, sizeof(fa), (off_t)(sizeof(fhdr) + i * sizeof(fa)))) break;
				ct = (cpu_type_t)OSSwapBigToHostInt32((uint32_t)fa.cputype);
				cs = (cpu_subtype_t)(OSSwapBigToHostInt32((uint32_t)fa.cpusubtype) & ~CPU_SUBTYPE_MASK);
				sOff = OSSwapBigToHostInt32(fa.offset);
				sSz = OSSwapBigToHostInt32(fa.size);
			} else {
				struct fat_arch_64 fa64;
				if (!pread_all(fd, &fa64, sizeof(fa64), (off_t)(sizeof(fhdr) + i * sizeof(fa64)))) break;
				ct = (cpu_type_t)OSSwapBigToHostInt32((uint32_t)fa64.cputype);
				cs = (cpu_subtype_t)(OSSwapBigToHostInt32((uint32_t)fa64.cpusubtype) & ~CPU_SUBTYPE_MASK);
				sOff = OSSwapBigToHostInt64(fa64.offset);
				sSz = OSSwapBigToHostInt64(fa64.size);
			}
			if (ct == CPU_TYPE_ARM64) {
				if (cs == CPU_SUBTYPE_ARM64_ALL && arm64Size == 0) {
					arm64Off = sOff; arm64Size = sSz;
				} else if (cs == CPU_SUBTYPE_ARM64E && arm64eSize == 0) {
					arm64eOff = sOff; arm64eSize = sSz;
				}
			}
		}
		uint64_t chosenOff = arm64Size > 0 ? arm64Off : arm64eOff;
		uint64_t chosenSize = arm64Size > 0 ? arm64Size : arm64eSize;
		if (chosenSize == 0) {
			close(fd);
			if (isMainExec) {
				if (errOut) *errOut = @"Binary has no 64-bit ARM (arm64/arm64e) slice (legacy 32-bit iOS app).";
				return NO;
			}
			return YES;
		}
		NSString *tmpSlice = [filePath stringByAppendingString:@".arm64slice"];
		int outFd = open(tmpSlice.fileSystemRepresentation, O_CREAT | O_TRUNC | O_WRONLY, 0755);
		if (outFd < 0) { close(fd); return NO; }
		uint8_t buf[65536];
		uint64_t rem = chosenSize, cur = chosenOff;
		BOOL copyOk = YES;
		while (rem > 0) {
			size_t n = (size_t)MIN(rem, (uint64_t)sizeof(buf));
			if (!pread_all(fd, buf, n, (off_t)cur) || !write_all(outFd, buf, n)) { copyOk = NO; break; }
			cur += n; rem -= n;
		}
		close(outFd);
		close(fd);
		if (!copyOk || rename(tmpSlice.fileSystemRepresentation, cpath) != 0) {
			unlink(tmpSlice.fileSystemRepresentation);
			if (errOut) *errOut = @"Failed thinning universal binary";
			return NO;
		}
		chown(cpath, 0, 0);
		chmod(cpath, 0755);
		fd = open(cpath, O_RDONLY);
		if (fd < 0 || !pread_all(fd, &magic, 4, 0)) { if (fd >= 0) close(fd); return NO; }
		fstat(fd, &st);
	}

	if (magic != MH_MAGIC_64) {
		close(fd);
		return YES;
	}

	chown(cpath, 0, 0);
	chmod(cpath, 0755);

	struct mach_header_64 mhdr;
	if (!pread_all(fd, &mhdr, sizeof(mhdr), 0) || mhdr.sizeofcmds > (2u << 20)) {
		close(fd);
		return YES;
	}
	uint64_t fileSize = (uint64_t)st.st_size;
	uint8_t *cmds = (uint8_t *)malloc(mhdr.sizeofcmds);
	if (!cmds || !pread_all(fd, cmds, mhdr.sizeofcmds, sizeof(mhdr))) {
		free(cmds);
		close(fd);
		return YES;
	}

	uint64_t textFileOff = 0, textFileSize = 0;
	ssize_t linkeditCmdOff = -1, sigCmdOff = -1;
	uint32_t firstSectOff = 0xffffffffu;
	uint32_t cryptId = 0;
	BOOL cmdsModified = NO;

	size_t off = 0;
	for (uint32_t i = 0; i < mhdr.ncmds && off + sizeof(struct load_command) <= mhdr.sizeofcmds; i++) {
		struct load_command *lc = (struct load_command *)(cmds + off);
		if (lc->cmdsize < sizeof(struct load_command) || off + lc->cmdsize > mhdr.sizeofcmds) break;
		if (lc->cmd == LC_SEGMENT_64 && lc->cmdsize >= sizeof(struct segment_command_64)) {
			struct segment_command_64 *seg = (struct segment_command_64 *)lc;
			if (strncmp(seg->segname, "__TEXT", 16) == 0) {
				textFileOff = seg->fileoff;
				textFileSize = seg->filesize;
			} else if (strncmp(seg->segname, "__LINKEDIT", 16) == 0) {
				linkeditCmdOff = (ssize_t)off;
			} else if (strncmp(seg->segname, "__RESTRICT", 16) == 0) {
				memset(seg->segname, 0, 16);
				memcpy(seg->segname, "__HOTRSTRT", 10);
				cmdsModified = YES;
			}
			struct section_64 *sects = (struct section_64 *)(seg + 1);
			uint32_t maxSects = (lc->cmdsize - (uint32_t)sizeof(*seg)) / (uint32_t)sizeof(*sects);
			for (uint32_t s = 0; s < seg->nsects && s < maxSects; s++) {
				if (sects[s].size > 0 && sects[s].offset > 0 && sects[s].offset < firstSectOff) {
					firstSectOff = sects[s].offset;
				}
			}
		} else if (lc->cmd == LC_ENCRYPTION_INFO_64 && lc->cmdsize >= sizeof(struct encryption_info_command_64)) {
			struct encryption_info_command_64 *enc = (struct encryption_info_command_64 *)lc;
			if (enc->cryptid != 0) cryptId = enc->cryptid;
		} else if (lc->cmd == LC_ENCRYPTION_INFO && lc->cmdsize >= sizeof(struct encryption_info_command)) {
			struct encryption_info_command *enc = (struct encryption_info_command *)lc;
			if (enc->cryptid != 0) cryptId = enc->cryptid;
		} else if (lc->cmd == LC_CODE_SIGNATURE && lc->cmdsize >= sizeof(struct linkedit_data_command)) {
			sigCmdOff = (ssize_t)off;
		}
		off += lc->cmdsize;
	}

	if (cryptId != 0) {
		free(cmds);
		close(fd);
		if (errOut) {
			*errOut = @"Encrypted App Store binary (FairPlay cryptid=1). Tap 'Install Web App' in HotStore Discover or import an unencrypted (cryptid=0) .ipa.";
		}
		return NO;
	}

	BOOL hasValidCD = NO;
	NSData *existingXmlEnts = nil;
	if (sigCmdOff >= 0) {
		struct linkedit_data_command *sc = (struct linkedit_data_command *)(cmds + sigCmdOff);
		uint32_t sigOff = sc->dataoff;
		uint32_t sigSize = sc->datasize;
		if (sigSize >= 20 && (uint64_t)sigOff + sigSize <= fileSize && sigSize <= (16u << 20)) {
			uint8_t *sb = (uint8_t *)malloc(sigSize);
			if (sb && pread_all(fd, sb, sigSize, (off_t)sigOff)) {
				uint32_t sbMagic = OSSwapBigToHostInt32(*(uint32_t *)sb);
				uint32_t sbCount = OSSwapBigToHostInt32(*(uint32_t *)(sb + 8));
				if (sbMagic == 0xfade0cc0u && sbCount <= 32 && 12 + sbCount * 8 <= sigSize) {
					for (uint32_t b = 0; b < sbCount; b++) {
						uint32_t btype = OSSwapBigToHostInt32(*(uint32_t *)(sb + 12 + b * 8));
						uint32_t boff  = OSSwapBigToHostInt32(*(uint32_t *)(sb + 16 + b * 8));
						if (boff + 8 > sigSize) continue;
						uint32_t bmagic = OSSwapBigToHostInt32(*(uint32_t *)(sb + boff));
						uint32_t blen   = OSSwapBigToHostInt32(*(uint32_t *)(sb + boff + 4));
						if (boff + blen > sigSize || blen < 8) continue;
						if ((btype == 0 || (btype >= 0x1000 && btype < 0x1010)) && bmagic == 0xfade0c02u) {
							hasValidCD = YES;
						} else if (btype == 5 && bmagic == 0xfade7171u && blen > 8) {
							existingXmlEnts = [NSData dataWithBytes:(sb + boff + 8) length:(blen - 8)];
						}
					}
				}
			}
			free(sb);
		}
	}
	close(fd);

	int rwFd = open(cpath, O_RDWR);
	if (rwFd < 0) {
		free(cmds);
		return YES;
	}

	/* De-PAC arm64e slices in-place so ARMv8.3 PAC instructions run trap-free at full speed */
	uint64_t depacCount = 0;
	if ((mhdr.cpusubtype & ~CPU_SUBTYPE_MASK) == CPU_SUBTYPE_ARM64E) {
		depacCount = depac_macho_fd(rwFd, &mhdr, cmds, fileSize);
		if (depacCount > 0) {
			fprintf(stderr, "hotstored: de-PAC %s -> %llu instructions rewritten\n",
			    filePath.lastPathComponent.UTF8String, (unsigned long long)depacCount);
		}
	}
	if (cmdsModified) {
		(void)pwrite(rwFd, cmds, mhdr.sizeofcmds, sizeof(mhdr));
	}

	/* Non-main dylibs that already carry a valid CD and were not modified can keep their signature */
	if (!isMainExec && hasValidCD && depacCount == 0 && !cmdsModified) {
		close(rwFd);
		free(cmds);
		return YES;
	}

	if (linkeditCmdOff < 0) {
		close(rwFd);
		free(cmds);
		return YES;
	}

	uint64_t codeLimit = fileSize;
	BOOL addedSigCmd = NO;
	struct linkedit_data_command newSigCmd = { LC_CODE_SIGNATURE, sizeof(struct linkedit_data_command), 0, 0 };

	if (sigCmdOff >= 0) {
		struct linkedit_data_command *sc = (struct linkedit_data_command *)(cmds + sigCmdOff);
		if ((uint64_t)sc->dataoff + sc->datasize == fileSize || sc->datasize == 0 || !hasValidCD) {
			codeLimit = sc->dataoff;
		} else {
			close(rwFd);
			free(cmds);
			return YES;
		}
	} else {
		if (firstSectOff == 0xffffffffu) firstSectOff = 4096;
		if (sizeof(mhdr) + mhdr.sizeofcmds + sizeof(newSigCmd) > firstSectOff) {
			close(rwFd);
			free(cmds);
			return YES;
		}
		codeLimit = (fileSize + 15ULL) & ~15ULL;
		if (codeLimit > fileSize) {
			uint8_t pad[16] = { 0 };
			(void)pwrite(rwFd, pad, (size_t)(codeLimit - fileSize), (off_t)fileSize);
		}
		addedSigCmd = YES;
	}

	struct segment_command_64 *le = (struct segment_command_64 *)(cmds + linkeditCmdOff);
	if (codeLimit < le->fileoff) {
		close(rwFd);
		free(cmds);
		return YES;
	}

	NSData *xmlEnts = isMainExec ? merged_app_entitlements(existingXmlEnts, bundleId, outGroups) : existingXmlEnts;
	NSString *ident = isMainExec ? bundleId : [filePath.lastPathComponent stringByDeletingPathExtension];

	uint32_t nCodeSlots = (uint32_t)((codeLimit + 4095ULL) / 4096ULL);
	uint32_t nSpecial = xmlEnts.length > 0 ? 5 : 0;
	const char *identC = ident.length > 0 ? ident.UTF8String : "com.ios26hot.app";
	uint32_t cdLen = 88 + (uint32_t)strlen(identC) + 1 + (nSpecial + nCodeSlots) * 32;
	uint32_t entBlobLen = xmlEnts.length > 0 ? (uint32_t)(8 + xmlEnts.length) : 0;
	uint32_t blobCount = entBlobLen > 0 ? 2 : 1;
	uint32_t exactSigLen = 12 + blobCount * 8 + cdLen + entBlobLen;

	le->filesize = (codeLimit - le->fileoff) + exactSigLen;
	uint64_t minVm = (le->filesize + 0x3fffULL) & ~0x3fffULL;
	if (le->vmsize < minVm) le->vmsize = minVm;

	if (addedSigCmd) {
		newSigCmd.dataoff = (uint32_t)codeLimit;
		newSigCmd.datasize = exactSigLen;
		uint32_t oldCmdsLen = mhdr.sizeofcmds;
		mhdr.ncmds += 1;
		mhdr.sizeofcmds += sizeof(newSigCmd);
		(void)pwrite(rwFd, &mhdr, sizeof(mhdr), 0);
		(void)pwrite(rwFd, cmds, oldCmdsLen, sizeof(mhdr));
		(void)pwrite(rwFd, &newSigCmd, sizeof(newSigCmd), (off_t)(sizeof(mhdr) + oldCmdsLen));
	} else {
		struct linkedit_data_command *sc = (struct linkedit_data_command *)(cmds + sigCmdOff);
		sc->dataoff = (uint32_t)codeLimit;
		sc->datasize = exactSigLen;
		(void)pwrite(rwFd, cmds, mhdr.sizeofcmds, sizeof(mhdr));
	}

	NSData *sb = build_adhoc_superblob(rwFd, codeLimit, textFileOff, textFileSize,
	                                   mhdr.filetype == MH_EXECUTE, ident, xmlEnts);
	if (sb.length == exactSigLen) {
		(void)ftruncate(rwFd, (off_t)codeLimit);
		(void)pwrite(rwFd, sb.bytes, sb.length, (off_t)codeLimit);
	}
	close(rwFd);
	free(cmds);
	return YES;
}

/* ---- Icon PNG Generation & Conversion (ImageIO + CoreGraphics) ---- */

static NSData *encode_cgimage_png_square(CGImageRef srcImg, size_t pxSize)
{
	CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
	CGContextRef ctx = CGBitmapContextCreate(NULL, pxSize, pxSize, 8, pxSize * 4, cs,
	    kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big);
	CGColorSpaceRelease(cs);
	if (!ctx) return nil;

	CGContextSetInterpolationQuality(ctx, kCGInterpolationHigh);
	CGContextDrawImage(ctx, CGRectMake(0, 0, pxSize, pxSize), srcImg);
	CGImageRef scaled = CGBitmapContextCreateImage(ctx);
	CGContextRelease(ctx);
	if (!scaled) return nil;

	NSMutableData *png = [NSMutableData data];
	CGImageDestinationRef dst = CGImageDestinationCreateWithData((__bridge CFMutableDataRef)png, CFSTR("public.png"), 1, NULL);
	if (dst) {
		CGImageDestinationAddImage(dst, scaled, NULL);
		CGImageDestinationFinalize(dst);
		CFRelease(dst);
	}
	CGImageRelease(scaled);
	return png.length > 0 ? png : nil;
}

static NSData *generate_fallback_icon_png(NSString *title, size_t pxSize)
{
	CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
	CGContextRef ctx = CGBitmapContextCreate(NULL, pxSize, pxSize, 8, pxSize * 4, cs,
	    kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big);
	if (!ctx) { CGColorSpaceRelease(cs); return nil; }

	/* Deterministic vibrant gradient based on title hash */
	NSUInteger h = title.hash;
	CGFloat r1 = 0.12 + ((h & 0x3f) / 140.0);
	CGFloat g1 = 0.35 + (((h >> 6) & 0x3f) / 140.0);
	CGFloat b1 = 0.72 + (((h >> 12) & 0x1f) / 120.0);
	CGFloat comps[8] = { r1, g1, b1, 1.0, r1 * 0.55, g1 * 0.65, MIN(1.0, b1 * 1.15), 1.0 };
	CGGradientRef grad = CGGradientCreateWithColorComponents(cs, comps, NULL, 2);
	CGContextDrawLinearGradient(ctx, grad, CGPointMake(0, pxSize), CGPointMake(pxSize, 0), 0);
	CGGradientRelease(grad);
	CGColorSpaceRelease(cs);

	/* Subtle glass ring */
	CGContextSetRGBStrokeColor(ctx, 1.0, 1.0, 1.0, 0.38);
	CGContextSetLineWidth(ctx, pxSize * 0.045);
	CGContextStrokeEllipseInRect(ctx, CGRectMake(pxSize * 0.22, pxSize * 0.22, pxSize * 0.56, pxSize * 0.56));

	CGImageRef img = CGBitmapContextCreateImage(ctx);
	CGContextRelease(ctx);
	if (!img) return nil;
	NSData *png = encode_cgimage_png_square(img, pxSize);
	CGImageRelease(img);
	return png;
}

static void write_webapp_icons(NSString *appDir, NSString *iconURL, NSString *title)
{
	CGImageRef decoded = NULL;
	if (iconURL.length > 8 && ([iconURL hasPrefix:@"http://"] || [iconURL hasPrefix:@"https://"])) {
		dispatch_semaphore_t sem = dispatch_semaphore_create(0);
		__block NSData *rawData = nil;
		NSURLSessionConfiguration *cfg = [NSURLSessionConfiguration ephemeralSessionConfiguration];
		cfg.timeoutIntervalForRequest = 10.0;
		cfg.timeoutIntervalForResource = 12.0;
		NSURLSession *sess = [NSURLSession sessionWithConfiguration:cfg];
		[[sess dataTaskWithURL:[NSURL URLWithString:iconURL]
		     completionHandler:^(NSData *data, NSURLResponse *resp, NSError *err) {
			(void)resp;
			if (!err && data.length > 64) rawData = data;
			dispatch_semaphore_signal(sem);
		}] resume];
		dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 12 * NSEC_PER_SEC));
		[sess finishTasksAndInvalidate];

		if (rawData) {
			CGImageSourceRef src = CGImageSourceCreateWithData((__bridge CFDataRef)rawData, NULL);
			if (src) {
				decoded = CGImageSourceCreateImageAtIndex(src, 0, NULL);
				CFRelease(src);
			}
		}
	}

	NSData *png180 = decoded ? encode_cgimage_png_square(decoded, 180) : generate_fallback_icon_png(title, 180);
	NSData *png120 = decoded ? encode_cgimage_png_square(decoded, 120) : generate_fallback_icon_png(title, 120);
	if (decoded) CGImageRelease(decoded);

	if (png180) [png180 writeToFile:[appDir stringByAppendingPathComponent:@"AppIcon60x60@3x.png"] atomically:YES];
	if (png120) [png120 writeToFile:[appDir stringByAppendingPathComponent:@"AppIcon60x60@2x.png"] atomically:YES];
}

/* ---- Install Native IPA & WebApp Workers ---- */

static void install_ipa_worker(NSString *ipaPath, BOOL deleteAfter, NSString *hintName)
{
	@autoreleasepool {
		NSFileManager *fm = [NSFileManager defaultManager];
		NSString *stageDir = [NSString stringWithFormat:@"%s/job-%llu",
		    STAGE_ROOT, (unsigned long long)([[NSDate date] timeIntervalSince1970] * 1000.0)];
		[fm removeItemAtPath:stageDir error:nil];
		[fm createDirectoryAtPath:stageDir withIntermediateDirectories:YES attributes:nil error:nil];

		NSString *err = nil;
		set_job(@"extracting", 0.08, @"Extracting IPA archive...", hintName ?: @"", @"", @"");
		if (!extract_ipa_zip(ipaPath, stageDir, 0.08, 0.58, &err)) {
			[fm removeItemAtPath:stageDir error:nil];
			if (deleteAfter) [fm removeItemAtPath:ipaPath error:nil];
			set_job(@"error", 0.0, @"Extraction failed", hintName ?: @"", @"", err ?: @"Failed to unpack IPA");
			pthread_mutex_lock(&g_job_lock); g_busy = NO; pthread_mutex_unlock(&g_job_lock);
			return;
		}

		NSString *payloadDir = [stageDir stringByAppendingPathComponent:@"Payload"];
		NSArray<NSString *> *items = [fm contentsOfDirectoryAtPath:payloadDir error:nil];
		NSString *appFolder = nil;
		for (NSString *it in items) {
			if ([it.pathExtension.lowercaseString isEqualToString:@"app"]) {
				appFolder = it;
				break;
			}
		}
		if (!appFolder) {
			[fm removeItemAtPath:stageDir error:nil];
			if (deleteAfter) [fm removeItemAtPath:ipaPath error:nil];
			set_job(@"error", 0.0, @"Invalid IPA", hintName ?: @"", @"", @"No Payload/*.app bundle found inside archive");
			pthread_mutex_lock(&g_job_lock); g_busy = NO; pthread_mutex_unlock(&g_job_lock);
			return;
		}

		NSString *stagedApp = [payloadDir stringByAppendingPathComponent:appFolder];
		NSString *infoPath = [stagedApp stringByAppendingPathComponent:@"Info.plist"];
		NSMutableDictionary *info = [NSMutableDictionary dictionaryWithContentsOfFile:infoPath];
		NSString *bundleId = [info[@"CFBundleIdentifier"] isKindOfClass:[NSString class]] ? info[@"CFBundleIdentifier"] : @"";
		NSString *execName = [info[@"CFBundleExecutable"] isKindOfClass:[NSString class]] ? info[@"CFBundleExecutable"] : @"";
		NSString *dispName = [info[@"CFBundleDisplayName"] isKindOfClass:[NSString class]] ? info[@"CFBundleDisplayName"] :
		                     ([info[@"CFBundleName"] isKindOfClass:[NSString class]] ? info[@"CFBundleName"] :
		                     [appFolder stringByDeletingPathExtension]);
		NSString *version  = [info[@"CFBundleShortVersionString"] isKindOfClass:[NSString class]] ? info[@"CFBundleShortVersionString"] :
		                     ([info[@"CFBundleVersion"] isKindOfClass:[NSString class]] ? info[@"CFBundleVersion"] : @"1.0");

		if (bundleId.length == 0 || execName.length == 0) {
			[fm removeItemAtPath:stageDir error:nil];
			if (deleteAfter) [fm removeItemAtPath:ipaPath error:nil];
			set_job(@"error", 0.0, @"Invalid Info.plist", dispName, @"", @"Missing CFBundleIdentifier or CFBundleExecutable");
			pthread_mutex_lock(&g_job_lock); g_busy = NO; pthread_mutex_unlock(&g_job_lock);
			return;
		}

		/* Ensure MinimumOSVersion never exceeds 26.5.2 if an exotic beta plist has a weird string */
		if ([info[@"UIDeviceFamily"] isKindOfClass:[NSArray class]] && ![info[@"UIDeviceFamily"] containsObject:@1]) {
			NSMutableArray *fam = [info[@"UIDeviceFamily"] mutableCopy];
			[fam insertObject:@1 atIndex:0];
			info[@"UIDeviceFamily"] = fam;
			[info writeToFile:infoPath atomically:YES];
		}

		set_job(@"signing", 0.62, [NSString stringWithFormat:@"De-PAC & signing %@...", dispName], dispName, bundleId, @"");
		chown_chmod_recursive(stagedApp);

		NSString *mainExecPath = [stagedApp stringByAppendingPathComponent:execName];
		if (![fm fileExistsAtPath:mainExecPath]) {
			[fm removeItemAtPath:stageDir error:nil];
			if (deleteAfter) [fm removeItemAtPath:ipaPath error:nil];
			set_job(@"error", 0.0, @"Missing executable", dispName, bundleId,
			    [NSString stringWithFormat:@"Executable %@ missing from app bundle", execName]);
			pthread_mutex_lock(&g_job_lock); g_busy = NO; pthread_mutex_unlock(&g_job_lock);
			return;
		}

		NSArray *appGroups = @[];
		if (!prepare_macho_file(mainExecPath, YES, bundleId, &appGroups, &err)) {
			[fm removeItemAtPath:stageDir error:nil];
			if (deleteAfter) [fm removeItemAtPath:ipaPath error:nil];
			set_job(@"error", 0.0, @"Cannot install binary", dispName, bundleId, err ?: @"Incompatible binary");
			pthread_mutex_lock(&g_job_lock); g_busy = NO; pthread_mutex_unlock(&g_job_lock);
			return;
		}

		NSDirectoryEnumerator *en = [fm enumeratorAtPath:stagedApp];
		NSString *rel;
		while ((rel = [en nextObject]) != nil) {
			NSString *full = [stagedApp stringByAppendingPathComponent:rel];
			if ([full isEqualToString:mainExecPath]) continue;
			if (!prepare_macho_file(full, NO, bundleId, NULL, &err)) {
				[fm removeItemAtPath:stageDir error:nil];
				if (deleteAfter) [fm removeItemAtPath:ipaPath error:nil];
				set_job(@"error", 0.0, @"Framework verification failed", dispName, bundleId, err ?: @"Encrypted framework");
				pthread_mutex_lock(&g_job_lock); g_busy = NO; pthread_mutex_unlock(&g_job_lock);
				return;
			}
		}

		provision_app_containers(bundleId, appGroups);

		NSString *destDirName = appFolder;
		NSString *destApp = [@"/Applications" stringByAppendingPathComponent:destDirName];
		if ([fm fileExistsAtPath:destApp] &&
		    ![fm fileExistsAtPath:[destApp stringByAppendingPathComponent:@MARKER_FILE]] &&
		    ![bundleId isEqualToString:@"com.ios26hot.hotstore"] &&
		    ![bundleId isEqualToString:@"com.ios26hot.hotauth"]) {
			destDirName = [NSString stringWithFormat:@"%@.app", sanitize_slug(bundleId)];
			destApp = [@"/Applications" stringByAppendingPathComponent:destDirName];
		}

		NSDictionary *meta = @{
			@"name": dispName,
			@"bundleId": bundleId,
			@"execName": execName,
			@"version": version,
			@"kind": @"native",
			@"dirName": destDirName,
			@"installedAt": @((long long)[[NSDate date] timeIntervalSince1970]),
		};
		NSString *markerPath = [stagedApp stringByAppendingPathComponent:@MARKER_FILE];
		[meta writeToFile:markerPath atomically:YES];
		chown(markerPath.fileSystemRepresentation, 0, 0);
		chmod(markerPath.fileSystemRepresentation, 0644);

		set_job(@"installing", 0.85, [NSString stringWithFormat:@"Installing %@ to /Applications...", dispName], dispName, bundleId, @"");
		NSString *oldBackup = [destApp stringByAppendingString:@".old"];
		[fm removeItemAtPath:oldBackup error:nil];
		if ([fm fileExistsAtPath:destApp]) {
			[fm moveItemAtPath:destApp toPath:oldBackup error:nil];
		}
		NSError *mvErr = nil;
		if (![fm moveItemAtPath:stagedApp toPath:destApp error:&mvErr]) {
			if ([fm fileExistsAtPath:oldBackup]) [fm moveItemAtPath:oldBackup toPath:destApp error:nil];
			[fm removeItemAtPath:stageDir error:nil];
			if (deleteAfter) [fm removeItemAtPath:ipaPath error:nil];
			set_job(@"error", 0.0, @"Move to /Applications failed", dispName, bundleId, mvErr.localizedDescription ?: @"Move failed");
			pthread_mutex_lock(&g_job_lock); g_busy = NO; pthread_mutex_unlock(&g_job_lock);
			return;
		}
		[fm removeItemAtPath:oldBackup error:nil];
		[fm removeItemAtPath:stageDir error:nil];
		if (deleteAfter) [fm removeItemAtPath:ipaPath error:nil];

		set_job(@"installing", 0.92, @"Registering icon on Home Screen (lsreg)...", dispName, bundleId, @"");
		(void)run_lsreg_mobile_args(@[destApp]);
		(void)run_lsreg_rebuild();

		set_job(@"done", 1.0, [NSString stringWithFormat:@"Installed %@ (%@)", dispName, version], dispName, bundleId, @"");
		fprintf(stderr, "hotstored: installed native %s (%s) -> %s\n",
		    dispName.UTF8String, bundleId.UTF8String, destApp.UTF8String);
		pthread_mutex_lock(&g_job_lock); g_busy = NO; pthread_mutex_unlock(&g_job_lock);
	}
}

static void create_webapp_worker(NSString *dispName, NSString *startURL, NSString *uaMode, NSString *iconURL, NSString *origBid)
{
	@autoreleasepool {
		NSFileManager *fm = [NSFileManager defaultManager];
		NSString *stubPath = @"/Applications/HotStore.app/HotWebAppStub";
		if (![fm fileExistsAtPath:stubPath]) {
			stubPath = @"/var/local/bin/HotWebAppStub";
		}
		if (![fm fileExistsAtPath:stubPath]) {
			set_job(@"error", 0.0, @"WebApp stub missing", dispName, @"", @"HotWebAppStub binary not found");
			pthread_mutex_lock(&g_job_lock); g_busy = NO; pthread_mutex_unlock(&g_job_lock);
			return;
		}

		NSString *slugBase = origBid.length > 0 ? origBid : dispName;
		NSString *slug = [sanitize_slug(slugBase) lowercaseString];
		if (slug.length > 48) slug = [slug substringToIndex:48];
		NSString *bundleId = [NSString stringWithFormat:@"com.ios26hot.webapp.%@", slug];
		NSString *execName = [NSString stringWithFormat:@"HW_%@", slug];
		NSString *destDirName = [NSString stringWithFormat:@"HW_%@.app", slug];
		NSString *destApp = [@"/Applications" stringByAppendingPathComponent:destDirName];

		NSString *stageDir = [NSString stringWithFormat:@"%s/web-%llu",
		    STAGE_ROOT, (unsigned long long)([[NSDate date] timeIntervalSince1970] * 1000.0)];
		NSString *stagedApp = [stageDir stringByAppendingPathComponent:destDirName];
		[fm createDirectoryAtPath:stagedApp withIntermediateDirectories:YES attributes:nil error:nil];

		set_job(@"extracting", 0.25, [NSString stringWithFormat:@"Fetching %@ icon & packaging...", dispName], dispName, bundleId, @"");
		write_webapp_icons(stagedApp, iconURL, dispName);

		NSString *execDst = [stagedApp stringByAppendingPathComponent:execName];
		if (![fm copyItemAtPath:stubPath toPath:execDst error:nil]) {
			[fm removeItemAtPath:stageDir error:nil];
			set_job(@"error", 0.0, @"Failed copying WebApp binary", dispName, bundleId, @"Copy failed");
			pthread_mutex_lock(&g_job_lock); g_busy = NO; pthread_mutex_unlock(&g_job_lock);
			return;
		}

		NSDictionary *info = @{
			@"CFBundleDevelopmentRegion": @"en",
			@"CFBundleDisplayName": dispName,
			@"CFBundleExecutable": execName,
			@"CFBundleIdentifier": bundleId,
			@"CFBundleInfoDictionaryVersion": @"6.0",
			@"CFBundleName": dispName,
			@"CFBundlePackageType": @"APPL",
			@"CFBundleShortVersionString": @"1.0",
			@"CFBundleVersion": @"1",
			@"LSRequiresIPhoneOS": @YES,
			@"UIDeviceFamily": @[@1, @2],
			@"UILaunchScreen": @{},
			@"UIRequiredDeviceCapabilities": @[@"arm64"],
			@"UISupportedInterfaceOrientations": @[
				@"UIInterfaceOrientationPortrait",
				@"UIInterfaceOrientationLandscapeLeft",
				@"UIInterfaceOrientationLandscapeRight",
			],
			@"CFBundleIcons": @{
				@"CFBundlePrimaryIcon": @{
					@"CFBundleIconFiles": @[@"AppIcon60x60"],
					@"UIPrerenderedIcon": @NO,
				},
			},
			@"NSAppTransportSecurity": @{ @"NSAllowsArbitraryLoads": @YES },
			@"SWStartURL": startURL,
			@"SWUserAgentMode": ([uaMode.lowercaseString isEqualToString:@"desktop"] ? @"desktop" : @"mobile"),
		};
		[info writeToFile:[stagedApp stringByAppendingPathComponent:@"Info.plist"] atomically:YES];

		set_job(@"signing", 0.65, [NSString stringWithFormat:@"Signing %@...", dispName], dispName, bundleId, @"");
		chown_chmod_recursive(stagedApp);
		NSString *err = nil;
		if (!prepare_macho_file(execDst, YES, bundleId, NULL, &err)) {
			[fm removeItemAtPath:stageDir error:nil];
			set_job(@"error", 0.0, @"Signing failed", dispName, bundleId, err ?: @"Sign error");
			pthread_mutex_lock(&g_job_lock); g_busy = NO; pthread_mutex_unlock(&g_job_lock);
			return;
		}

		provision_app_containers(bundleId, @[]);

		NSDictionary *meta = @{
			@"name": dispName,
			@"bundleId": bundleId,
			@"origBundleId": origBid ?: @"",
			@"execName": execName,
			@"version": @"WebApp",
			@"kind": @"webapp",
			@"startURL": startURL,
			@"dirName": destDirName,
			@"installedAt": @((long long)[[NSDate date] timeIntervalSince1970]),
		};
		NSString *markerPath = [stagedApp stringByAppendingPathComponent:@MARKER_FILE];
		[meta writeToFile:markerPath atomically:YES];
		chown(markerPath.fileSystemRepresentation, 0, 0);
		chmod(markerPath.fileSystemRepresentation, 0644);

		[fm removeItemAtPath:destApp error:nil];
		if (![fm moveItemAtPath:stagedApp toPath:destApp error:nil]) {
			[fm removeItemAtPath:stageDir error:nil];
			set_job(@"error", 0.0, @"Move to /Applications failed", dispName, bundleId, @"Move error");
			pthread_mutex_lock(&g_job_lock); g_busy = NO; pthread_mutex_unlock(&g_job_lock);
			return;
		}
		[fm removeItemAtPath:stageDir error:nil];

		set_job(@"installing", 0.90, @"Adding icon to Home Screen...", dispName, bundleId, @"");
		(void)run_lsreg_mobile_args(@[destApp]);
		(void)run_lsreg_rebuild();

		set_job(@"done", 1.0, [NSString stringWithFormat:@"Installed %@ Web App", dispName], dispName, bundleId, @"");
		fprintf(stderr, "hotstored: installed webapp %s (%s -> %s) at %s\n",
		    dispName.UTF8String, bundleId.UTF8String, startURL.UTF8String, destApp.UTF8String);
		pthread_mutex_lock(&g_job_lock); g_busy = NO; pthread_mutex_unlock(&g_job_lock);
	}
}

/* ---- GPU Mode (/var/local/mtl2-clients) & Wi-Fi IP Helpers ---- */

static NSSet<NSString *> *read_mtl2_disabled_set(void)
{
	NSString *txt = [NSString stringWithContentsOfFile:@MTL2_CLIENTS_PATH encoding:NSUTF8StringEncoding error:nil];
	NSMutableSet<NSString *> *set = [NSMutableSet set];
	for (NSString *tok in [txt componentsSeparatedByCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]]) {
		if (tok.length > 1 && [tok hasPrefix:@"-"]) {
			[set addObject:[tok substringFromIndex:1]];
		}
	}
	return set;
}

static BOOL set_app_gpu_enabled(NSString *execName, BOOL enabled)
{
	if (execName.length == 0) return NO;
	NSString *txt = [NSString stringWithContentsOfFile:@MTL2_CLIENTS_PATH encoding:NSUTF8StringEncoding error:nil] ?: @"*\n";
	NSMutableArray<NSString *> *lines = [NSMutableArray array];
	NSString *negToken = [@"-" stringByAppendingString:execName];
	BOOL sawStar = NO;
	for (NSString *raw in [txt componentsSeparatedByCharactersInSet:[NSCharacterSet newlineCharacterSet]]) {
		NSString *t = [raw stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
		if (t.length == 0) continue;
		if ([t isEqualToString:@"*"]) sawStar = YES;
		if ([t isEqualToString:negToken]) continue;
		[lines addObject:t];
	}
	if (!sawStar) [lines insertObject:@"*" atIndex:0];
	if (!enabled) [lines addObject:negToken];
	NSString *out = [[lines componentsJoinedByString:@"\n"] stringByAppendingString:@"\n"];
	return [out writeToFile:@MTL2_CLIENTS_PATH atomically:YES encoding:NSUTF8StringEncoding error:nil];
}

static NSString *current_wifi_ipv4(void)
{
	struct ifaddrs *ifaddr = NULL;
	if (getifaddrs(&ifaddr) != 0) return nil;
	NSString *best = nil;
	for (struct ifaddrs *ifa = ifaddr; ifa != NULL; ifa = ifa->ifa_next) {
		if (!ifa->ifa_addr || ifa->ifa_addr->sa_family != AF_INET) continue;
		if ((ifa->ifa_flags & IFF_UP) == 0 || (ifa->ifa_flags & IFF_LOOPBACK) != 0) continue;
		char ip[INET_ADDRSTRLEN] = {0};
		struct sockaddr_in *sin = (struct sockaddr_in *)ifa->ifa_addr;
		if (!inet_ntop(AF_INET, &sin->sin_addr, ip, sizeof(ip))) continue;
		NSString *ipStr = [NSString stringWithUTF8String:ip];
		if (strcmp(ifa->ifa_name, "en0") == 0) {
			best = ipStr;
			break;
		}
		if (!best) best = ipStr;
	}
	freeifaddrs(ifaddr);
	return best;
}

/* ---- Toggleable Wi-Fi LAN .ipa Dropzone (Port 29172) ---- */

static void handle_lan_upload_client(int cfd)
{
	@autoreleasepool {
		int one = 1;
		setsockopt(cfd, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof(one));
		struct timeval tv = { .tv_sec = 30, .tv_usec = 0 };
		setsockopt(cfd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

		NSMutableData *hdrBuf = [NSMutableData dataWithCapacity:4096];
		uint8_t tmp[4096];
		ssize_t hdrEnd = -1;
		uint64_t contentLen = 0;

		while (hdrBuf.length < 32768) {
			ssize_t n = recv(cfd, tmp, sizeof(tmp), 0);
			if (n <= 0) break;
			[hdrBuf appendBytes:tmp length:(NSUInteger)n];
			const char *b = (const char *)hdrBuf.bytes;
			for (size_t i = 0; i + 3 < hdrBuf.length; i++) {
				if (b[i] == '\r' && b[i+1] == '\n' && b[i+2] == '\r' && b[i+3] == '\n') {
					hdrEnd = (ssize_t)(i + 4);
					NSString *hs = [[NSString alloc] initWithBytes:b length:i encoding:NSISOLatin1StringEncoding];
					for (NSString *line in [hs componentsSeparatedByString:@"\r\n"]) {
						if ([line.lowercaseString hasPrefix:@"content-length:"]) {
							contentLen = (uint64_t)[[line substringFromIndex:15] longLongValue];
						}
					}
					break;
				}
			}
			if (hdrEnd >= 0) break;
		}
		if (hdrEnd < 0) { close(cfd); return; }

		NSString *firstLine = [[[NSString alloc] initWithBytes:hdrBuf.bytes length:(NSUInteger)(hdrEnd - 4)
		                                              encoding:NSISOLatin1StringEncoding]
		                       componentsSeparatedByString:@"\r\n"].firstObject ?: @"";
		NSArray<NSString *> *parts = [firstLine componentsSeparatedByString:@" "];
		NSString *method = parts.count > 0 ? parts[0] : @"";
		NSString *uri    = parts.count > 1 ? parts[1] : @"";

		if ([method isEqualToString:@"GET"]) {
			static const char *html =
				"<!doctype html><html><head><meta charset='utf-8'>"
				"<meta name='viewport' content='width=device-width,initial-scale=1'>"
				"<title>HotStore Wi-Fi IPA Dropzone</title>"
				"<style>body{font-family:-apple-system,sans-serif;background:#0f1117;color:#f5f7ff;margin:0;padding:32px;text-align:center}"
				".card{max-width:520px;margin:24px auto;background:#1a1e29;border:2px dashed #3b82f6;border-radius:20px;padding:36px 24px}"
				"h1{font-size:22px;margin:0 0 8px}p{color:#9ca3af;font-size:14px;line-height:1.5}"
				"input[type=file]{margin:18px 0;color:#e5e7eb}#st{margin-top:16px;font-weight:600;color:#60a5fa}"
				"progress{width:100%;height:14px;display:none;margin-top:12px}</style></head><body>"
				"<div class='card'><h1>HotStore 2.0 Wi-Fi Uploader</h1>"
				"<p>Select or drop any unencrypted <code>.ipa</code> (<code>cryptid=0</code>) to stream &amp; install directly onto iOS 26.5.2.</p>"
				"<input type='file' id='f' accept='.ipa'><progress id='p' max='100' value='0'></progress><div id='st'>Ready</div></div>"
				"<script>const f=document.getElementById('f'),p=document.getElementById('p'),st=document.getElementById('st');"
				"f.onchange=()=>{const file=f.files[0];if(!file)return;p.style.display='block';st.textContent='Uploading '+file.name+'...';"
				"const x=new XMLHttpRequest();x.open('POST','/upload?name='+encodeURIComponent(file.name));"
				"x.upload.onprogress=e=>{if(e.lengthComputable)p.value=Math.round(e.loaded*100/e.total);};"
				"x.onload=()=>{st.textContent=x.status===200?'Uploaded! Installing on iPhone now — check HotStore.':'Error: '+x.responseText;};"
				"x.onerror=()=>{st.textContent='Upload network error';};x.send(file);};</script></body></html>";
			size_t hlen = strlen(html);
			char hdr[256];
			int n = snprintf(hdr, sizeof(hdr),
			    "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: %lu\r\nConnection: close\r\n\r\n",
			    (unsigned long)hlen);
			(void)write_all(cfd, hdr, (size_t)n);
			(void)write_all(cfd, html, hlen);
			close(cfd);
			return;
		}

		if ([method isEqualToString:@"POST"] && [uri hasPrefix:@"/upload"] && contentLen > 64 && contentLen <= (4ULL << 30)) {
			NSString *rawName = @"Uploaded.ipa";
			NSRange q = [uri rangeOfString:@"name="];
			if (q.location != NSNotFound) {
				NSString *enc = [uri substringFromIndex:q.location + 5];
				rawName = [enc stringByRemovingPercentEncoding] ?: @"Uploaded.ipa";
			}
			NSString *cleanName = sanitize_slug([rawName.lastPathComponent stringByDeletingPathExtension]);
			NSString *dstPath = [NSString stringWithFormat:@"%s/%@.ipa", LOCAL_IPA_DIR, cleanName];
			int outFd = open(dstPath.fileSystemRepresentation, O_CREAT | O_TRUNC | O_WRONLY, 0644);
			if (outFd < 0) {
				const char *msg = "Cannot open destination file";
				char hdr[160];
				int n = snprintf(hdr, sizeof(hdr), "HTTP/1.1 500 Error\r\nContent-Length: %lu\r\n\r\n%s", strlen(msg), msg);
				(void)write_all(cfd, hdr, (size_t)n);
				close(cfd);
				return;
			}
			uint64_t written = 0;
			size_t already = hdrBuf.length - (size_t)hdrEnd;
			if (already > 0) {
				size_t take = (size_t)MIN((uint64_t)already, contentLen);
				(void)write_all(outFd, (const uint8_t *)hdrBuf.bytes + hdrEnd, take);
				written += take;
			}
			uint8_t *ioBuf = (uint8_t *)malloc(65536);
			while (ioBuf && written < contentLen) {
				size_t want = (size_t)MIN((uint64_t)65536, contentLen - written);
				ssize_t got = recv(cfd, ioBuf, want, 0);
				if (got <= 0) break;
				if (!write_all(outFd, ioBuf, (size_t)got)) break;
				written += (uint64_t)got;
			}
			free(ioBuf);
			close(outFd);

			if (written == contentLen) {
				pthread_mutex_lock(&g_job_lock);
				if (!g_busy) {
					g_busy = YES;
					pthread_mutex_unlock(&g_job_lock);
					set_job(@"extracting", 0.05, @"Installing Wi-Fi uploaded IPA...", cleanName, @"", @"");
					dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
						install_ipa_worker(dstPath, NO, cleanName);
					});
				} else {
					pthread_mutex_unlock(&g_job_lock);
				}
				const char *okMsg = "OK";
				char hdr[128];
				int n = snprintf(hdr, sizeof(hdr), "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n%s", okMsg);
				(void)write_all(cfd, hdr, (size_t)n);
			} else {
				unlink(dstPath.fileSystemRepresentation);
				const char *errMsg = "Incomplete upload";
				char hdr[160];
				int n = snprintf(hdr, sizeof(hdr), "HTTP/1.1 400 Bad Request\r\nContent-Length: %lu\r\n\r\n%s", strlen(errMsg), errMsg);
				(void)write_all(cfd, hdr, (size_t)n);
			}
			close(cfd);
			return;
		}

		close(cfd);
	}
}

static BOOL set_lan_upload_enabled(BOOL enable)
{
	pthread_mutex_lock(&g_lan_lock);
	if (!enable) {
		if (g_lan_srv >= 0) {
			close(g_lan_srv);
			g_lan_srv = -1;
		}
		pthread_mutex_unlock(&g_lan_lock);
		return YES;
	}
	if (g_lan_srv >= 0) {
		pthread_mutex_unlock(&g_lan_lock);
		return YES;
	}
	int srv = socket(AF_INET, SOCK_STREAM, 0);
	if (srv < 0) {
		pthread_mutex_unlock(&g_lan_lock);
		return NO;
	}
	int one = 1;
	setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
	struct sockaddr_in addr;
	memset(&addr, 0, sizeof(addr));
	addr.sin_len = sizeof(addr);
	addr.sin_family = AF_INET;
	addr.sin_port = htons(HOTUPLOAD_PORT);
	addr.sin_addr.s_addr = htonl(INADDR_ANY);
	if (bind(srv, (struct sockaddr *)&addr, sizeof(addr)) != 0 || listen(srv, 8) != 0) {
		close(srv);
		pthread_mutex_unlock(&g_lan_lock);
		return NO;
	}
	g_lan_srv = srv;
	pthread_mutex_unlock(&g_lan_lock);

	dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
		for (;;) {
			pthread_mutex_lock(&g_lan_lock);
			int s = g_lan_srv;
			pthread_mutex_unlock(&g_lan_lock);
			if (s < 0) break;
			int cfd = accept(s, NULL, NULL);
			if (cfd < 0) {
				if (errno == EINTR) continue;
				break;
			}
			dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
				handle_lan_upload_client(cfd);
			});
		}
	});
	return YES;
}

/* ---- Scans & HTTP API ---- */

static NSArray *scan_installed_apps(void)
{
	NSFileManager *fm = [NSFileManager defaultManager];
	NSArray<NSString *> *apps = [fm contentsOfDirectoryAtPath:@"/Applications" error:nil];
	NSSet<NSString *> *gpuDisabled = read_mtl2_disabled_set();
	NSMutableArray *list = [NSMutableArray array];
	for (NSString *dir in apps) {
		if (![dir.pathExtension.lowercaseString isEqualToString:@"app"]) continue;
		NSString *full = [@"/Applications" stringByAppendingPathComponent:dir];
		NSString *marker = [full stringByAppendingPathComponent:@MARKER_FILE];
		if (![fm fileExistsAtPath:marker]) continue;
		NSDictionary *meta = [NSDictionary dictionaryWithContentsOfFile:marker];
		if (!meta) continue;
		NSString *execName = meta[@"execName"] ?: @"";
		if (execName.length == 0) {
			NSDictionary *info = [NSDictionary dictionaryWithContentsOfFile:[full stringByAppendingPathComponent:@"Info.plist"]];
			if ([info[@"CFBundleExecutable"] isKindOfClass:[NSString class]]) {
				execName = info[@"CFBundleExecutable"];
			}
		}
		BOOL gpuOn = (execName.length == 0) ? YES : ![gpuDisabled containsObject:execName];
		[list addObject:@{
			@"name": meta[@"name"] ?: [dir stringByDeletingPathExtension],
			@"bundleId": meta[@"bundleId"] ?: @"",
			@"origBundleId": meta[@"origBundleId"] ?: @"",
			@"execName": execName ?: @"",
			@"version": meta[@"version"] ?: @"1.0",
			@"kind": meta[@"kind"] ?: @"native",
			@"startURL": meta[@"startURL"] ?: @"",
			@"gpuEnabled": @(gpuOn),
			@"dirName": dir,
			@"path": full,
		}];
	}
	[list sortUsingComparator:^NSComparisonResult(NSDictionary *a, NSDictionary *b) {
		return [a[@"name"] localizedCaseInsensitiveCompare:b[@"name"]];
	}];
	return list;
}

static NSArray *scan_local_ipas(void)
{
	NSFileManager *fm = [NSFileManager defaultManager];
	NSArray<NSString *> *dirs = @[
		@"/private/var/mobile/Downloads",
		@"/private/var/mobile/Media/Downloads",
		@"/private/var/mobile/Documents",
		@LOCAL_IPA_DIR,
	];
	NSMutableArray *found = [NSMutableArray array];
	NSMutableSet *seen = [NSMutableSet set];
	for (NSString *d in dirs) {
		NSArray<NSString *> *files = [fm contentsOfDirectoryAtPath:d error:nil];
		for (NSString *f in files) {
			if (![f.pathExtension.lowercaseString isEqualToString:@"ipa"]) continue;
			if ([f hasPrefix:@".hotdl-"]) continue;
			NSString *full = [d stringByAppendingPathComponent:f];
			if ([seen containsObject:full]) continue;
			[seen addObject:full];
			NSDictionary *attr = [fm attributesOfItemAtPath:full error:nil];
			unsigned long long sz = [attr fileSize];
			if (sz < 64) continue;
			[found addObject:@{
				@"name": f,
				@"path": full,
				@"size": @(sz),
			}];
		}
	}
	return found;
}

static NSDictionary *build_status_dict(void)
{
	pthread_mutex_lock(&g_job_lock);
	BOOL busy = g_busy;
	NSDictionary *job = @{
		@"state": g_state ?: @"idle",
		@"progress": @(g_progress),
		@"step": g_step ?: @"",
		@"appName": g_app_name ?: @"",
		@"bundleId": g_bundle_id ?: @"",
		@"error": g_error ?: @"",
	};
	pthread_mutex_unlock(&g_job_lock);

	pthread_mutex_lock(&g_lan_lock);
	BOOL lanOn = (g_lan_srv >= 0);
	pthread_mutex_unlock(&g_lan_lock);
	NSString *wifiIP = current_wifi_ipv4();
	NSString *lanURL = (lanOn && wifiIP.length > 0)
	                 ? [NSString stringWithFormat:@"http://%@:%d", wifiIP, HOTUPLOAD_PORT]
	                 : @"";

	return @{
		@"ok": @YES,
		@"busy": @(busy),
		@"job": job,
		@"lanEnabled": @(lanOn),
		@"lanURL": lanURL,
		@"wifiIP": wifiIP ?: @"",
		@"installed": scan_installed_apps(),
		@"localIpas": scan_local_ipas(),
	};
}

static void send_json(int cfd, int status, NSDictionary *obj)
{
	NSData *body = [NSJSONSerialization dataWithJSONObject:obj options:0 error:nil] ?: [NSData data];
	const char *stText = (status == 200) ? "OK" : (status == 409 ? "Conflict" : "Bad Request");
	char hdr[256];
	int hlen = snprintf(hdr, sizeof(hdr),
	    "HTTP/1.1 %d %s\r\n"
	    "Content-Type: application/json; charset=utf-8\r\n"
	    "Content-Length: %lu\r\n"
	    "Connection: close\r\n\r\n",
	    status, stText, (unsigned long)body.length);
	(void)write_all(cfd, hdr, (size_t)hlen);
	if (body.length > 0) (void)write_all(cfd, body.bytes, body.length);
}

static void handle_client(int cfd)
{
	@autoreleasepool {
		int one = 1;
		setsockopt(cfd, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof(one));
		struct timeval tv = { .tv_sec = 5, .tv_usec = 0 };
		setsockopt(cfd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

		NSMutableData *req = [NSMutableData dataWithCapacity:4096];
		uint8_t buf[2048];
		ssize_t hdrEnd = -1;
		size_t contentLen = 0;

		while (req.length < 65536) {
			ssize_t n = recv(cfd, buf, sizeof(buf), 0);
			if (n <= 0) break;
			[req appendBytes:buf length:(NSUInteger)n];
			if (hdrEnd < 0) {
				const char *b = (const char *)req.bytes;
				for (size_t i = 0; i + 3 < req.length; i++) {
					if (b[i] == '\r' && b[i+1] == '\n' && b[i+2] == '\r' && b[i+3] == '\n') {
						hdrEnd = (ssize_t)(i + 4);
						NSString *hs = [[NSString alloc] initWithBytes:b length:i encoding:NSISOLatin1StringEncoding];
						for (NSString *line in [hs componentsSeparatedByString:@"\r\n"]) {
							if ([line.lowercaseString hasPrefix:@"content-length:"]) {
								contentLen = (size_t)[[line substringFromIndex:15] integerValue];
							}
						}
						break;
					}
				}
			}
			if (hdrEnd >= 0 && req.length >= (NSUInteger)hdrEnd + contentLen) break;
		}
		if (hdrEnd < 0) { close(cfd); return; }

		NSString *firstLine = [[[NSString alloc] initWithBytes:req.bytes length:(NSUInteger)(hdrEnd - 4)
		                                              encoding:NSISOLatin1StringEncoding]
		                       componentsSeparatedByString:@"\r\n"].firstObject ?: @"";
		NSArray<NSString *> *parts = [firstLine componentsSeparatedByString:@" "];
		NSString *method = parts.count > 0 ? parts[0] : @"";
		NSString *path   = parts.count > 1 ? parts[1] : @"";

		NSDictionary *json = nil;
		if (contentLen > 0 && req.length >= (NSUInteger)hdrEnd + contentLen) {
			NSData *body = [req subdataWithRange:NSMakeRange((NSUInteger)hdrEnd, contentLen)];
			id parsed = [NSJSONSerialization JSONObjectWithData:body options:0 error:nil];
			if ([parsed isKindOfClass:[NSDictionary class]]) json = parsed;
		}

		if ([method isEqualToString:@"GET"] && [path hasPrefix:@"/status"]) {
			send_json(cfd, 200, build_status_dict());
			close(cfd);
			return;
		}

		if ([method isEqualToString:@"GET"] && [path hasPrefix:@"/appfix-log"]) {
			NSData *d = [NSData dataWithContentsOfFile:@APPFIX_LOG_PATH];
			NSString *logStr = @"";
			if (d.length > 0) {
				NSUInteger maxLen = 16384;
				NSData *tail = d.length > maxLen ? [d subdataWithRange:NSMakeRange(d.length - maxLen, maxLen)] : d;
				logStr = [[NSString alloc] initWithData:tail encoding:NSUTF8StringEncoding]
				      ?: [[NSString alloc] initWithData:tail encoding:NSISOLatin1StringEncoding]
				      ?: @"";
			}
			send_json(cfd, 200, @{ @"ok": @YES, @"log": logStr });
			close(cfd);
			return;
		}

		if ([method isEqualToString:@"POST"] && [path isEqualToString:@"/clear-appfix-log"]) {
			[@"" writeToFile:@APPFIX_LOG_PATH atomically:YES encoding:NSUTF8StringEncoding error:nil];
			chmod(APPFIX_LOG_PATH, 0666);
			send_json(cfd, 200, @{ @"ok": @YES });
			close(cfd);
			return;
		}

		if ([method isEqualToString:@"POST"] && [path isEqualToString:@"/install-file"]) {
			NSString *ipaPath = [json[@"path"] isKindOfClass:[NSString class]] ? json[@"path"] : @"";
			BOOL delAfter = [json[@"deleteAfter"] respondsToSelector:@selector(boolValue)] ? [json[@"deleteAfter"] boolValue] : NO;
			NSString *disp = [json[@"displayName"] isKindOfClass:[NSString class]] ? json[@"displayName"] : @"";
			if (ipaPath.length == 0 || ![[NSFileManager defaultManager] fileExistsAtPath:ipaPath]) {
				send_json(cfd, 400, @{ @"ok": @NO, @"error": @"IPA file not found on disk" });
				close(cfd);
				return;
			}
			pthread_mutex_lock(&g_job_lock);
			if (g_busy) {
				pthread_mutex_unlock(&g_job_lock);
				send_json(cfd, 409, @{ @"ok": @NO, @"error": @"Another installation is already in progress" });
				close(cfd);
				return;
			}
			g_busy = YES;
			pthread_mutex_unlock(&g_job_lock);
			set_job(@"extracting", 0.05, @"Starting installation...", disp, @"", @"");
			dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
				install_ipa_worker(ipaPath, delAfter, disp);
			});
			send_json(cfd, 200, @{ @"ok": @YES });
			close(cfd);
			return;
		}

		if ([method isEqualToString:@"POST"] && [path isEqualToString:@"/create-webapp"]) {
			NSString *name = [json[@"name"] isKindOfClass:[NSString class]] ? json[@"name"] : @"";
			NSString *url  = [json[@"url"] isKindOfClass:[NSString class]] ? json[@"url"] : @"";
			NSString *ua   = [json[@"userAgent"] isKindOfClass:[NSString class]] ? json[@"userAgent"] : @"mobile";
			NSString *icon = [json[@"iconURL"] isKindOfClass:[NSString class]] ? json[@"iconURL"] : @"";
			NSString *obid = [json[@"origBundleId"] isKindOfClass:[NSString class]] ? json[@"origBundleId"] : @"";
			if (name.length == 0 || url.length < 8 || (![url hasPrefix:@"http://"] && ![url hasPrefix:@"https://"])) {
				send_json(cfd, 400, @{ @"ok": @NO, @"error": @"Valid app name and http(s) URL required" });
				close(cfd);
				return;
			}
			pthread_mutex_lock(&g_job_lock);
			if (g_busy) {
				pthread_mutex_unlock(&g_job_lock);
				send_json(cfd, 409, @{ @"ok": @NO, @"error": @"Another installation is already in progress" });
				close(cfd);
				return;
			}
			g_busy = YES;
			pthread_mutex_unlock(&g_job_lock);
			set_job(@"extracting", 0.10, [NSString stringWithFormat:@"Creating %@ Web App...", name], name, @"", @"");
			dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
				create_webapp_worker(name, url, ua, icon, obid);
			});
			send_json(cfd, 200, @{ @"ok": @YES });
			close(cfd);
			return;
		}

		if ([method isEqualToString:@"POST"] && [path isEqualToString:@"/gpu-mode"]) {
			NSString *execName = [json[@"execName"] isKindOfClass:[NSString class]] ? json[@"execName"] : @"";
			BOOL en = [json[@"gpuEnabled"] respondsToSelector:@selector(boolValue)] ? [json[@"gpuEnabled"] boolValue] : YES;
			BOOL ok = set_app_gpu_enabled(execName, en);
			send_json(cfd, ok ? 200 : 400, @{ @"ok": @(ok), @"installed": scan_installed_apps() });
			close(cfd);
			return;
		}

		if ([method isEqualToString:@"POST"] && [path isEqualToString:@"/lan-upload"]) {
			BOOL en = [json[@"enabled"] respondsToSelector:@selector(boolValue)] ? [json[@"enabled"] boolValue] : NO;
			BOOL ok = set_lan_upload_enabled(en);
			send_json(cfd, ok ? 200 : 400, build_status_dict());
			close(cfd);
			return;
		}

		if ([method isEqualToString:@"POST"] && [path isEqualToString:@"/uninstall"]) {
			NSString *dirName = [json[@"dirName"] isKindOfClass:[NSString class]] ? json[@"dirName"] : @"";
			BOOL delData = [json[@"deleteData"] respondsToSelector:@selector(boolValue)] ? [json[@"deleteData"] boolValue] : NO;
			if (dirName.length == 0 || [dirName containsString:@"/"] || [dirName containsString:@".."]) {
				send_json(cfd, 400, @{ @"ok": @NO, @"error": @"Invalid bundle directory" });
				close(cfd);
				return;
			}
			NSString *appPath = [@"/Applications" stringByAppendingPathComponent:dirName];
			NSString *marker = [appPath stringByAppendingPathComponent:@MARKER_FILE];
			NSFileManager *fm = [NSFileManager defaultManager];
			if (![fm fileExistsAtPath:marker]) {
				send_json(cfd, 400, @{ @"ok": @NO, @"error": @"Refusing to uninstall system app" });
				close(cfd);
				return;
			}
			NSDictionary *meta = [NSDictionary dictionaryWithContentsOfFile:marker];
			NSString *bid = [meta[@"bundleId"] isKindOfClass:[NSString class]] ? meta[@"bundleId"] : @"";
			[fm removeItemAtPath:appPath error:nil];
			if (delData && bid.length > 0) {
				NSString *dataDir = [@"/private/var/mobile/Containers/Data/Application" stringByAppendingPathComponent:sanitize_slug(bid)];
				[fm removeItemAtPath:dataDir error:nil];
			}
			(void)run_lsreg_rebuild();
			send_json(cfd, 200, @{ @"ok": @YES, @"installed": scan_installed_apps() });
			close(cfd);
			return;
		}

		if ([method isEqualToString:@"POST"] && [path isEqualToString:@"/rebuild-icons"]) {
			int rc = run_lsreg_rebuild();
			send_json(cfd, 200, @{ @"ok": @(rc == 0), @"rc": @(rc) });
			close(cfd);
			return;
		}

		if ([method isEqualToString:@"POST"] && [path isEqualToString:@"/open"]) {
			NSString *bid = [json[@"bundleId"] isKindOfClass:[NSString class]] ? json[@"bundleId"] : @"";
			if (bid.length == 0) {
				send_json(cfd, 400, @{ @"ok": @NO, @"error": @"Missing bundleId" });
				close(cfd);
				return;
			}
			dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INTERACTIVE, 0), ^{
				char *const argv[] = { (char *)"/var/local/bin/openapp", (char *)bid.UTF8String, NULL };
				(void)run_tool("/var/local/bin/openapp", argv);
			});
			send_json(cfd, 200, @{ @"ok": @YES });
			close(cfd);
			return;
		}

		send_json(cfd, 400, @{ @"ok": @NO, @"error": @"Unknown endpoint" });
		close(cfd);
	}
}

static int promote_staged_system_apps(void)
{
	int moved = 0;
	@autoreleasepool {
		NSFileManager *fm = [NSFileManager defaultManager];
		NSArray<NSString *> *staged = [fm contentsOfDirectoryAtPath:@STAGED_SYSTEM_APPS_DIR error:nil];
		if (staged.count == 0) return 0;

		mkdir(STAGED_PLACEHOLDERS_DIR, 0755);
		for (NSString *item in staged) {
			if (![item.pathExtension.lowercaseString isEqualToString:@"app"]) continue;
			NSString *src = [@STAGED_SYSTEM_APPS_DIR stringByAppendingPathComponent:item];
			NSString *dst = [@"/Applications" stringByAppendingPathComponent:item];
			NSString *phSrc = [@APP_PLACEHOLDERS_DIR stringByAppendingPathComponent:item];
			NSString *phDst = [@STAGED_PLACEHOLDERS_DIR stringByAppendingPathComponent:item];

			if ([fm fileExistsAtPath:phSrc]) {
				(void)rename(phSrc.fileSystemRepresentation, phDst.fileSystemRepresentation);
			}
			if (![fm fileExistsAtPath:dst]) {
				if (rename(src.fileSystemRepresentation, dst.fileSystemRepresentation) == 0) {
					chown(dst.fileSystemRepresentation, 0, 0);
					chmod(dst.fileSystemRepresentation, 0755);
					moved++;
				}
			}
		}
		if (moved > 0) {
			fprintf(stderr, "hotstored: promoted %d staged system apps into /Applications\n", moved);
		}
	}
	return moved;
}

static void ensure_first_boot_registration(int moved)
{
	@autoreleasepool {
		NSFileManager *fm = [NSFileManager defaultManager];
		NSArray<NSString *> *promoted = [fm contentsOfDirectoryAtPath:@STAGED_PLACEHOLDERS_DIR error:nil];
		struct stat st;
		long long sw_sz = 0, sa_sz = 0;
		if (stat("/Applications/HotStore.app/HotStore", &st) == 0) sw_sz = (long long)st.st_size;
		if (stat("/Applications/HotAuth.app/HotAuth", &st) == 0) sa_sz = (long long)st.st_size;

		NSString *want = [NSString stringWithFormat:@"v4:%lld:%lld:%lu\n",
		    sw_sz, sa_sz, (unsigned long)promoted.count];
		NSString *have = [NSString stringWithContentsOfFile:@REG_STAMP_PATH encoding:NSUTF8StringEncoding error:nil];
		if (moved == 0 && [have isEqualToString:want]) return;

		NSMutableArray<NSString *> *toReg = [NSMutableArray array];
		if (sw_sz > 0) [toReg addObject:@"/Applications/HotStore.app"];
		if (sa_sz > 0) [toReg addObject:@"/Applications/HotAuth.app"];
		for (NSString *item in promoted) {
			if (![item.pathExtension.lowercaseString isEqualToString:@"app"]) continue;
			NSString *appPath = [@"/Applications" stringByAppendingPathComponent:item];
			if ([fm fileExistsAtPath:appPath]) {
				[toReg addObject:appPath];
			}
		}

		for (int attempt = 0; attempt < 5; attempt++) {
			sleep(attempt == 0 ? 15 : 6);
			int rc_reb = run_lsreg_rebuild();
			int rc_reg = toReg.count > 0 ? run_lsreg_mobile_args(toReg) : 0;
			fprintf(stderr, "hotstored: auto-register (%lu apps, moved=%d) attempt %d -> reg=%d rebuild=%d\n",
			    (unsigned long)toReg.count, moved, attempt + 1, rc_reg, rc_reb);
			if (rc_reg == 0 && rc_reb == 0) {
				[want writeToFile:@REG_STAMP_PATH atomically:YES encoding:NSUTF8StringEncoding error:nil];
				break;
			}
		}
	}
}

int main(int argc, char **argv)
{
	(void)argc; (void)argv;
	signal(SIGPIPE, SIG_IGN);
	@autoreleasepool {
		mkdir("/private/var/local", 0777);
		chmod("/private/var/local", 0777);
		mkdir(LOCAL_IPA_DIR, 0777);
		chmod(LOCAL_IPA_DIR, 0777);
		mkdir(STAGE_ROOT, 0755);
		mkdir("/private/var/mobile/Downloads", 0777);
		chown("/private/var/mobile/Downloads", 501, 501);
		chmod("/private/var/mobile/Downloads", 0777);

		int moved = promote_staged_system_apps();

		dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
			ensure_first_boot_registration(moved);
		});

		int srv = socket(AF_INET, SOCK_STREAM, 0);
		if (srv < 0) {
			perror("hotstored: socket");
			return 1;
		}
		int one = 1;
		setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

		struct sockaddr_in addr;
		memset(&addr, 0, sizeof(addr));
		addr.sin_len = sizeof(addr);
		addr.sin_family = AF_INET;
		addr.sin_port = htons(HOTSTORED_PORT);
		addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

		if (bind(srv, (struct sockaddr *)&addr, sizeof(addr)) != 0 || listen(srv, 16) != 0) {
			perror("hotstored: bind/listen");
			close(srv);
			return 1;
		}
		fprintf(stderr, "hotstored v2: listening on 127.0.0.1:%d\n", HOTSTORED_PORT);

		for (;;) {
			int cfd = accept(srv, NULL, NULL);
			if (cfd < 0) {
				if (errno == EINTR) continue;
				usleep(100000);
				continue;
			}
			dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
				handle_client(cfd);
			});
		}
	}
}
