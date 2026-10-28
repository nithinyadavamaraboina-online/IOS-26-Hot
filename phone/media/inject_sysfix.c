/*
 * SPDX-License-Identifier: APSL-2.0
 *
 * libhot_sysfix.dylib: non-intrusive boot & UI responsiveness fixes for
 * containermanagerd and SpringBoard on RMX1931 / SWEET (iOS 16 & iOS 26).
 * Adapted from HackiOS userland/src/inject.c & userland/src/inject_sb.c
 * (https://github.com/oosexgang/hackios-xnu-kernel).
 *
 * Loaded via kern.exec_inject (0200-rmx1931-hackios-peripherals.patch):
 *   - containermanagerd / containermanagerd_system:
 *       * iOS 16 & iOS 26+:
 *           +[MCMUserIdentityCache personasAreSupported] -> NO
 *           Eliminates the 20 x 1 s usermanagerd persona lookup retry stall
 *           and bypasses strict XPC PersonaKernelID propagation checks on
 *           single-volume HFS+ (where containermanagerd proxies system/bundle
 *           containers to containermanagerd_system).
 *       * iOS 26+ (Darwin >= 25):
 *           -[MCMUserIdentity init] creates _defaultUserIdentity with
 *           personaType = 2 (unspecific, isNoSpecificPersona == YES), which
 *           causes legacy/system lookups (mobileactivationd) and container
 *           cache queries (lsd) to fail with MCMError 76 / 115 when
 *           personasAreSupported == NO. In addition, both containermanagerd
 *           (--user-container-mode=fixed, defaultUser=mobile/501) and
 *           containermanagerd_system (--user-container-mode=none, defaultUser=root/0)
 *           have userContainerMode != 2, so userIdentityForClient:error: assigns
 *           defaultUser while userIdentityForPersonalPersonaWithPOSIXUser: uses
 *           client.euid, rejecting cross-UID clients (mobileactivationd uid 0 ->
 *           containermanagerd, lsd uid 501 -> containermanagerd_system) with
 *           MCMError 76. Keep lock_userIdentityForUnspecificPersona as
 *           personaType = 2 while returning defaultUser's personaType = 0
 *           (Personal) identity from defaultUserIdentity,
 *           lock_userIdentityForPersonalPersona, and
 *           userIdentityForPersonalPersonaWithPOSIXUser:.
 *   - backboardd (iOS 26+):
 *       -[CBBacklightNode getBacklightCalibration:] fallback for 625-nit
 *       AppleARMBacklight so _DisplayCreateWithCapabilitiesAndID does not call
 *       __DisplaySliderToLogicalBrightnessInternal before copyMarketingTable
 *       allocates the slider luminance table.
 *   - SpringBoard:
 *       +[XBApplicationSnapshotManifestImpl _outputFormatForSnapshot:] -> JPEG / PNG
 *       Avoids multi-second CPU ASTC (.atx) texture compression when apps go to
 *       background, preventing 10 s scene-update watchdog terminations.
 */
#include <CoreFoundation/CoreFoundation.h>
#include <arpa/inet.h>
#include <dirent.h>
#include <dispatch/dispatch.h>
#include <fcntl.h>
#include <mach-o/dyld.h>
#include <netinet/in.h>
#include <notify.h>
#include <objc/message.h>
#include <objc/runtime.h>
#include <os/lock.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/sysctl.h>
#include <sys/time.h>
#include <unistd.h>

static void
say(const char *format, ...)
{
	char line[256];
	va_list ap;
	int n;

	va_start(ap, format);
	n = vsnprintf(line, sizeof(line), format, ap);
	va_end(ap);
	if (n < 0) {
		return;
	}
	if (n > (int)sizeof(line) - 1) {
		n = (int)sizeof(line) - 1;
	}
	(void)write(2, line, (size_t)n);
}

static int mcm_log_fd = -1;

static void
mcm_logf(const char *format, ...)
{
	struct stat st;
	char buf[384];
	va_list ap;
	int n;

	if (mcm_log_fd < 0) {
		return;
	}
	if (fstat(mcm_log_fd, &st) == 0 && st.st_size < 65536) {
		va_start(ap, format);
		n = vsnprintf(buf, sizeof(buf), format, ap);
		va_end(ap);
		if (n > 0) {
			if (n > (int)sizeof(buf) - 1) {
				n = (int)sizeof(buf) - 1;
			}
			(void)write(mcm_log_fd, buf, (size_t)n);
		}
	}
}

static int
darwin_major(void)
{
	char rel[32];
	size_t len = sizeof(rel) - 1;

	memset(rel, 0, sizeof(rel));
	if (sysctlbyname("kern.osrelease", rel, &len, NULL, 0) != 0) {
		return 0;
	}
	return atoi(rel);
}

/* ---- containermanagerd: +[MCMUserIdentityCache personasAreSupported] ---- */

static BOOL
personas_are_supported(__unused id self, __unused SEL selector)
{
	return NO;
}

/* ---- containermanagerd (iOS 26+): Personal vs Unspecific identity split ---- */

static Class cls_user_identity;
static IMP orig_default_identity;
static IMP orig_persona_id;
static IMP orig_mcm_error_init;
static IMP orig_mcm_error_init_cat;
static SEL sel_default_identity;
static SEL sel_alloc;
static SEL sel_release;
static SEL sel_posix_user;
static SEL sel_home_dir_url;
static SEL sel_kernel_persona_id;
static SEL sel_init_posix;
static SEL sel_utf8_string;
static SEL sel_path_str;
static os_unfair_lock personal_identity_lock = OS_UNFAIR_LOCK_INIT;
static const char personal_identity_key;

static id
cm26_default_identity(id self, __unused SEL selector)
{
	id raw, personal, posix_user, home_url, obj;
	uint32_t kpid;

	if (self == NULL || orig_default_identity == NULL) {
		return NULL;
	}
	raw = ((id (*)(id, SEL))orig_default_identity)(self, sel_default_identity);
	if (raw == NULL || cls_user_identity == NULL) {
		return raw;
	}
	personal = objc_getAssociatedObject(raw, &personal_identity_key);
	if (personal != NULL) {
		return personal;
	}

	os_unfair_lock_lock(&personal_identity_lock);
	personal = objc_getAssociatedObject(raw, &personal_identity_key);
	if (personal == NULL) {
		posix_user = ((id (*)(id, SEL))objc_msgSend)(raw, sel_posix_user);
		home_url = ((id (*)(id, SEL))objc_msgSend)(raw, sel_home_dir_url);
		kpid = ((uint32_t (*)(id, SEL))objc_msgSend)(raw, sel_kernel_persona_id);
		if (posix_user != NULL && home_url != NULL) {
			obj = ((id (*)(id, SEL))objc_msgSend)((id)cls_user_identity, sel_alloc);
			if (obj != NULL) {
				personal = ((id (*)(id, SEL, id, id, id, uint64_t, uint32_t))objc_msgSend)(
				    obj, sel_init_posix, posix_user, home_url, NULL, 0ULL, kpid);
				if (personal != NULL) {
					objc_setAssociatedObject(raw, &personal_identity_key,
					    personal, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
					((void (*)(id, SEL))objc_msgSend)(personal, sel_release);
				}
			}
		}
	}
	os_unfair_lock_unlock(&personal_identity_lock);

	return personal != NULL ? personal : raw;
}

static id
cm26_lock_personal(id self, __unused SEL selector)
{
	return cm26_default_identity(self, sel_default_identity);
}

static id
cm26_personal_with_posix_user(id self, __unused SEL selector, __unused id posix_user)
{
	return cm26_default_identity(self, sel_default_identity);
}

static id
cm26_lock_unspecific(id self, __unused SEL selector)
{
	if (self == NULL || orig_default_identity == NULL) {
		return NULL;
	}
	return ((id (*)(id, SEL))orig_default_identity)(self, sel_default_identity);
}

static id
cm26_persona_id(id self, SEL selector, uint32_t persona_id)
{
	id res = ((id (*)(id, SEL, uint32_t))orig_persona_id)(self, selector, persona_id);

	if (res != NULL) {
		return res;
	}
	return cm26_default_identity(self, sel_default_identity);
}

static const char *
nsstr_cstr(id obj)
{
	if (obj == NULL || sel_utf8_string == NULL) {
		return "-";
	}
	if (class_getInstanceMethod(object_getClass(obj), sel_utf8_string) != NULL) {
		const char *s = ((const char *(*)(id, SEL))objc_msgSend)(obj, sel_utf8_string);
		return s != NULL ? s : "-";
	}
	if (sel_path_str != NULL &&
	    class_getInstanceMethod(object_getClass(obj), sel_path_str) != NULL) {
		id p = ((id (*)(id, SEL))objc_msgSend)(obj, sel_path_str);
		if (p != NULL && class_getInstanceMethod(object_getClass(p), sel_utf8_string) != NULL) {
			const char *s = ((const char *(*)(id, SEL))objc_msgSend)(p, sel_utf8_string);
			return s != NULL ? s : "-";
		}
	}
	return "?";
}

static id
cm26_error_init(id self, SEL selector, uint64_t type, uint64_t category,
    id path, int posix_errno)
{
	id res = ((id (*)(id, SEL, uint64_t, uint64_t, id, int))orig_mcm_error_init)(
	    self, selector, type, category, path, posix_errno);

	if (res != NULL && type > 1) {
		mcm_logf("%s[%d]: MCMError type=%llu cat=%llu errno=%d path=%s\n",
		    getprogname(), (int)getpid(),
		    (unsigned long long)type, (unsigned long long)category,
		    posix_errno, nsstr_cstr(path));
	}
	return res;
}

static id
cm26_error_init_cat(id self, SEL selector, uint64_t type, uint64_t category)
{
	id res = ((id (*)(id, SEL, uint64_t, uint64_t))orig_mcm_error_init_cat)(
	    self, selector, type, category);

	if (res != NULL && type > 1) {
		mcm_logf("%s[%d]: MCMError type=%llu cat=%llu\n",
		    getprogname(), (int)getpid(),
		    (unsigned long long)type, (unsigned long long)category);
	}
	return res;
}

/*
 * iOS 26+ HFS+ APFS-fsctl & POSIX.1e extended-ACL bypasses:
 *   - -[MCMFileHandle registerAppContainerForProtectionWithError:] calls APFS
 *     container-protection fsctl and only accepts 0 or EEXIST (not ENOTSUP),
 *     causing -[MCMContainerFactory _createContainerForContainerIdentity:error:]
 *     to roll back and delete every newly created container on HFS+.
 *   - -[MCMFileHandle checkAppContainerProtection:error:] fails with ENOTSUP
 *     (MCMError 144 / 161) and discards queried containers on HFS+.
 *   - -[MCMFileManager enableFastDiskUsageForURL:error:] calls APFSIOC_DIR_STATS_OP
 *     (0xc1104a71), failing with ENOTSUP on HFS+.
 *   - System (class 12) and SystemGroup (class 13) containers call
 *     -[MCMFileManager setTopLevelSystemContainerACLAtURL:error:] (acl_set_link_np)
 *     which fails with ENOTSUP on HFS+ and rolls back every staged system/group
 *     container. In addition, MCMContainerPath sets schemaPOSIXMode = 0000 and
 *     schemaPOSIXOwner = nobody (-2) when systemContainerMode == 1, relying on
 *     inherited APFS ACLs for multi-UID access (lsd uid=501, mobileactivationd
 *     uid=0, installd uid=33). Bypass the unsupported POSIX.1e ACL setters and
 *     assign 0777 + container owner on System/SystemGroup paths so HFS+ UNIX DAC
 *     matches Apple's inherited system-container ACL semantics under Seatbelt.
 */
static IMP orig_root_posix_mode;
static IMP orig_data_posix_mode;
static IMP orig_schema_posix_mode;
static IMP orig_subdir_posix_owner;
static IMP orig_create_dir;
static SEL sel_container_class;

#ifndef DT_DIR
#define DT_DIR 4
#endif

static void
chmod_sys_dirs_recursive(const char *path, int depth)
{
	DIR *dir;
	struct dirent *de;
	char sub[1024];
	int n;

	if (path == NULL || path[0] != '/' || depth > 4) {
		return;
	}
	(void)chmod(path, 0777);
	if (depth == 4) {
		return;
	}
	dir = opendir(path);
	if (dir == NULL) {
		return;
	}
	while ((de = readdir(dir)) != NULL) {
		if (de->d_type != DT_DIR) {
			continue;
		}
		if (strcmp(de->d_name, ".") == 0 ||
		    strcmp(de->d_name, "..") == 0 ||
		    strcmp(de->d_name, "DiagnosticReports") == 0 ||
		    strcmp(de->d_name, "MemoryLimitViolations") == 0) {
			continue;
		}
		n = snprintf(sub, sizeof(sub), "%s/%s", path, de->d_name);
		if (n > 0 && n < (int)sizeof(sub)) {
			chmod_sys_dirs_recursive(sub, depth + 1);
		}
	}
	(void)closedir(dir);
}

static void
repair_existing_sys_containers(void)
{
	static const char *const roots[] = {
		"/private/var/containers/Data/System",
		"/private/var/containers/Shared/SystemGroup",
	};
	DIR *dir;
	struct dirent *de;
	char sub[1024];
	size_t i;
	int n;

	for (i = 0; i < sizeof(roots) / sizeof(roots[0]); i++) {
		dir = opendir(roots[i]);
		if (dir == NULL) {
			continue;
		}
		while ((de = readdir(dir)) != NULL) {
			if (de->d_type != DT_DIR ||
			    strcmp(de->d_name, ".") == 0 ||
			    strcmp(de->d_name, "..") == 0) {
				continue;
			}
			n = snprintf(sub, sizeof(sub), "%s/%s", roots[i], de->d_name);
			if (n > 0 && n < (int)sizeof(sub)) {
				chmod_sys_dirs_recursive(sub, 0);
			}
		}
		(void)closedir(dir);
	}
}

static BOOL
cm26_register_protection(__unused id self, __unused SEL selector, __unused id *out_error)
{
	return YES;
}

static BOOL
cm26_check_protection(__unused id self, __unused SEL selector,
    BOOL *out_protected, __unused id *out_error)
{
	if (out_protected != NULL) {
		*out_protected = YES;
	}
	return YES;
}

static BOOL
cm26_enable_fast_disk_usage(__unused id self, __unused SEL selector,
    __unused id url, __unused id *out_error)
{
	return YES;
}

static BOOL
cm26_set_top_level_sys_acl(__unused id self, __unused SEL selector,
    id url, __unused id *out_error)
{
	const char *p = nsstr_cstr(url);

	if (p != NULL && p[0] == '/') {
		chmod_sys_dirs_recursive(p, 0);
	}
	return YES;
}

static BOOL
cm26_std_sys_acl(__unused id self, __unused SEL selector,
    id url, __unused id *out_error)
{
	const char *p = nsstr_cstr(url);

	if (p != NULL && p[0] == '/') {
		chmod_sys_dirs_recursive(p, 0);
	}
	return YES;
}

static BOOL
cm26_url_acl_noop(__unused id self, __unused SEL selector,
    __unused id url, __unused id *out_error)
{
	return YES;
}

static BOOL
cm26_standardize_acl_noop(__unused id self, __unused SEL selector,
    id url, BOOL is_sys, __unused id *out_error)
{
	const char *p;

	if (is_sys) {
		p = nsstr_cstr(url);
		if (p != NULL && p[0] == '/') {
			chmod_sys_dirs_recursive(p, 0);
		}
	}
	return YES;
}

static BOOL
cm26_create_dir(id self, SEL selector, id url, BOOL with_inter, uint16_t mode,
    id owner, int dp_class, void *fs_node, id *out_error)
{
	BOOL ok;
	const char *p;

	ok = ((BOOL (*)(id, SEL, id, BOOL, uint16_t, id, int, void *, id *))orig_create_dir)(
	    self, selector, url, with_inter, mode, owner, dp_class, fs_node, out_error);
	if (ok && (mode & 0777) == 0777) {
		p = nsstr_cstr(url);
		if (p != NULL && p[0] == '/') {
			(void)chmod(p, 0777);
		}
	}
	return ok;
}

static BOOL
cm26_fix_acl_on_fd(__unused id self, __unused SEL selector,
    __unused int fd, __unused void *rem_fsec, __unused void *dd_fsec,
    __unused const char *dd_text, __unused const char *path,
    __unused id *out_error)
{
	return YES;
}

static bool
cm26_is_system_or_group_path(id self)
{
	uint64_t ccls;

	if (self == NULL || sel_container_class == NULL) {
		return false;
	}
	ccls = ((uint64_t (*)(id, SEL))objc_msgSend)(self, sel_container_class);
	return (ccls & ~1ULL) == 12ULL;
}

static uint16_t
cm26_root_posix_mode(id self, SEL selector)
{
	if (cm26_is_system_or_group_path(self)) {
		return 0777;
	}
	return orig_root_posix_mode != NULL ?
	    ((uint16_t (*)(id, SEL))orig_root_posix_mode)(self, selector) : 0755;
}

static uint16_t
cm26_data_posix_mode(id self, SEL selector)
{
	if (cm26_is_system_or_group_path(self)) {
		return 0777;
	}
	return orig_data_posix_mode != NULL ?
	    ((uint16_t (*)(id, SEL))orig_data_posix_mode)(self, selector) : 0755;
}

static uint16_t
cm26_schema_posix_mode(id self, SEL selector)
{
	if (cm26_is_system_or_group_path(self)) {
		return 0777;
	}
	return orig_schema_posix_mode != NULL ?
	    ((uint16_t (*)(id, SEL))orig_schema_posix_mode)(self, selector) : 0755;
}

static id
cm26_subdir_posix_owner(id self, SEL selector, uint64_t container_class, id user)
{
	if ((container_class & ~1ULL) == 12ULL && user != NULL) {
		return user;
	}
	return orig_subdir_posix_owner != NULL ?
	    ((id (*)(id, SEL, uint64_t, id))orig_subdir_posix_owner)(
	        self, selector, container_class, user) : user;
}

static bool cm_hooked;

static void
cm_image_added(__unused const struct mach_header *header, __unused intptr_t slide)
{
	Class cls, fh_cls, fm_cls, cp_cls, err_cls;
	Method m;
	int dmaj;

	if (cm_hooked || (cls = objc_getClass("MCMUserIdentityCache")) == NULL) {
		return;
	}
	cm_hooked = true;
	dmaj = darwin_major();

	m = class_getClassMethod(cls, sel_registerName("personasAreSupported"));
	if (m != NULL) {
		method_setImplementation(m, (IMP)personas_are_supported);
		say("hot: %s: +[MCMUserIdentityCache personasAreSupported] -> NO\n",
		    getprogname());
	}
	if (dmaj < 25) {
		return;
	}
	if (mcm_log_fd < 0) {
		mcm_log_fd = open("/private/var/local/mcm.log",
		    O_WRONLY | O_APPEND | O_CLOEXEC);
	}

	cls_user_identity = objc_getClass("MCMUserIdentity");
	sel_default_identity = sel_registerName("defaultUserIdentity");
	sel_alloc = sel_registerName("alloc");
	sel_release = sel_registerName("release");
	sel_posix_user = sel_registerName("posixUser");
	sel_home_dir_url = sel_registerName("homeDirectoryURL");
	sel_kernel_persona_id = sel_registerName("kernelPersonaID");
	sel_init_posix = sel_registerName(
	    "initWithPOSIXUser:homeDirectoryURL:personaUniqueString:personaType:kernelPersonaID:");
	sel_utf8_string = sel_registerName("UTF8String");
	sel_path_str = sel_registerName("path");
	sel_container_class = sel_registerName("containerClass");

	m = class_getInstanceMethod(cls, sel_default_identity);
	if (m != NULL) {
		orig_default_identity = method_setImplementation(m, (IMP)cm26_default_identity);
	}
	m = class_getInstanceMethod(cls, sel_registerName("lock_userIdentityForPersonalPersona"));
	if (m != NULL) {
		(void)method_setImplementation(m, (IMP)cm26_lock_personal);
	}
	m = class_getInstanceMethod(cls,
	    sel_registerName("userIdentityForPersonalPersonaWithPOSIXUser:"));
	if (m != NULL) {
		(void)method_setImplementation(m, (IMP)cm26_personal_with_posix_user);
	}
	m = class_getInstanceMethod(cls, sel_registerName("lock_userIdentityForUnspecificPersona"));
	if (m != NULL) {
		(void)method_setImplementation(m, (IMP)cm26_lock_unspecific);
	}
	m = class_getInstanceMethod(cls, sel_registerName("userIdentityWithPersonaID:"));
	if (m != NULL) {
		orig_persona_id = method_setImplementation(m, (IMP)cm26_persona_id);
	}

	fh_cls = objc_getClass("MCMFileHandle");
	if (fh_cls != NULL) {
		m = class_getInstanceMethod(fh_cls,
		    sel_registerName("registerAppContainerForProtectionWithError:"));
		if (m != NULL) {
			(void)method_setImplementation(m, (IMP)cm26_register_protection);
		}
		m = class_getInstanceMethod(fh_cls,
		    sel_registerName("checkAppContainerProtection:error:"));
		if (m != NULL) {
			(void)method_setImplementation(m, (IMP)cm26_check_protection);
		}
	}
	fm_cls = objc_getClass("MCMFileManager");
	if (fm_cls != NULL) {
		m = class_getInstanceMethod(fm_cls,
		    sel_registerName("enableFastDiskUsageForURL:error:"));
		if (m != NULL) {
			(void)method_setImplementation(m, (IMP)cm26_enable_fast_disk_usage);
		}
		m = class_getInstanceMethod(fm_cls,
		    sel_registerName("setTopLevelSystemContainerACLAtURL:error:"));
		if (m != NULL) {
			(void)method_setImplementation(m, (IMP)cm26_set_top_level_sys_acl);
		}
		m = class_getInstanceMethod(fm_cls,
		    sel_registerName("standardizeACLsForSystemContainerAtURL:error:"));
		if (m != NULL) {
			(void)method_setImplementation(m, (IMP)cm26_std_sys_acl);
		}
		m = class_getInstanceMethod(fm_cls,
		    sel_registerName("standardizeACLsAtURL:isSystemContainer:error:"));
		if (m != NULL) {
			(void)method_setImplementation(m, (IMP)cm26_standardize_acl_noop);
		}
		m = class_getInstanceMethod(fm_cls,
		    sel_registerName("standardizeAllSystemContainerACLsAtURL:error:"));
		if (m != NULL) {
			(void)method_setImplementation(m, (IMP)cm26_std_sys_acl);
		}
		m = class_getInstanceMethod(fm_cls,
		    sel_registerName("stripACLFromURL:error:"));
		if (m != NULL) {
			(void)method_setImplementation(m, (IMP)cm26_url_acl_noop);
		}
		m = class_getInstanceMethod(fm_cls,
		    sel_registerName("_fixACLOnFD:removeACLFilesec:denyDeleteFilesec:denyDeleteText:path:error:"));
		if (m != NULL) {
			(void)method_setImplementation(m, (IMP)cm26_fix_acl_on_fd);
		}
		m = class_getInstanceMethod(fm_cls,
		    sel_registerName("createDirectoryAtURL:withIntermediateDirectories:mode:owner:dataProtectionClass:fsNode:error:"));
		if (m != NULL) {
			orig_create_dir = method_setImplementation(m, (IMP)cm26_create_dir);
		}
	}
	if (geteuid() == 0) {
		repair_existing_sys_containers();
	}
	cp_cls = objc_getClass("MCMContainerPath");
	if (cp_cls != NULL) {
		m = class_getInstanceMethod(cp_cls, sel_registerName("rootPOSIXMode"));
		if (m != NULL) {
			orig_root_posix_mode = method_setImplementation(m,
			    (IMP)cm26_root_posix_mode);
		}
		m = class_getInstanceMethod(cp_cls, sel_registerName("dataPOSIXMode"));
		if (m != NULL) {
			orig_data_posix_mode = method_setImplementation(m,
			    (IMP)cm26_data_posix_mode);
		}
		m = class_getInstanceMethod(cp_cls, sel_registerName("schemaPOSIXMode"));
		if (m != NULL) {
			orig_schema_posix_mode = method_setImplementation(m,
			    (IMP)cm26_schema_posix_mode);
		}
		m = class_getClassMethod(cp_cls,
		    sel_registerName("_posixOwnerForContainerSubdirectories:user:"));
		if (m != NULL) {
			orig_subdir_posix_owner = method_setImplementation(m,
			    (IMP)cm26_subdir_posix_owner);
		}
	}

	err_cls = objc_getClass("MCMError");
	if (err_cls != NULL) {
		m = class_getInstanceMethod(err_cls,
		    sel_registerName("initWithErrorType:category:path:POSIXerrno:"));
		if (m != NULL) {
			orig_mcm_error_init = method_setImplementation(m, (IMP)cm26_error_init);
		}
		m = class_getInstanceMethod(err_cls,
		    sel_registerName("initWithErrorType:category:"));
		if (m != NULL) {
			orig_mcm_error_init_cat = method_setImplementation(m,
			    (IMP)cm26_error_init_cat);
		}
	}
	mcm_logf("hot: %s[%d]: hooked MCMUserIdentityCache + HFS+ protection + ACLs (darwin=%d uid=%u)\n",
	    getprogname(), (int)getpid(), dmaj, (unsigned)geteuid());
	say("hot: %s: MCMUserIdentityCache + HFS+ protection & ACL hooks installed\n",
	    getprogname());
}

/* ---- SpringBoard & backboardd: selective CABackdropLayer live capture bypass ----
 *
 * Disables ONLY live per-frame CABackdropLayer framebuffer readback/blur in
 * backboardd and SpringBoard while keeping 100% of pre-baked IconServices 3D
 * Liquid Glass app icons, translucent pill chrome, specular borders, and spring
 * animations (NOT Accessibility "Reduce Transparency").
 * Touch /private/var/local/live-glass (or /var/local/live-glass) to re-enable
 * live dynamic refraction within 5 s without restarting or reflashing.
 */
static IMP orig_cabd_is_enabled;
static IMP orig_cabd_set_enabled;
static IMP orig_cabd_init;
static SEL sel_cabd_set_enabled;
static bool cabd_hooked;

static bool
hot_live_glass_enabled(void)
{
	static int cached = -1;
	static time_t last_chk = 0;
	struct timeval tv;

	(void)gettimeofday(&tv, NULL);
	if (cached < 0 || (tv.tv_sec - last_chk) >= 5) {
		last_chk = tv.tv_sec;
		cached = (access("/private/var/local/live-glass", F_OK) == 0 ||
		    access("/var/local/live-glass", F_OK) == 0) ? 1 : 0;
	}
	return cached != 0;
}

static BOOL
hot_cabd_is_enabled(id self, SEL selector)
{
	if (hot_live_glass_enabled() && orig_cabd_is_enabled != NULL) {
		return ((BOOL (*)(id, SEL))orig_cabd_is_enabled)(self, selector);
	}
	return NO;
}

static void
hot_cabd_set_enabled(id self, SEL selector, BOOL enabled)
{
	if (orig_cabd_set_enabled == NULL) {
		return;
	}
	((void (*)(id, SEL, BOOL))orig_cabd_set_enabled)(self, selector,
	    hot_live_glass_enabled() ? enabled : NO);
}

static id
hot_cabd_init(id self, SEL selector)
{
	id res = orig_cabd_init != NULL ?
	    ((id (*)(id, SEL))orig_cabd_init)(self, selector) : self;
	if (res != NULL && !hot_live_glass_enabled() &&
	    orig_cabd_set_enabled != NULL && sel_cabd_set_enabled != NULL) {
		((void (*)(id, SEL, BOOL))orig_cabd_set_enabled)(res,
		    sel_cabd_set_enabled, NO);
	}
	return res;
}

static void
hot_try_hook_backdrop_layer(const char *who)
{
	Class cls;
	Method m;

	if (cabd_hooked || (cls = objc_getClass("CABackdropLayer")) == NULL) {
		return;
	}
	cabd_hooked = true;
	sel_cabd_set_enabled = sel_registerName("setEnabled:");
	m = class_getInstanceMethod(cls, sel_registerName("isEnabled"));
	if (m != NULL) {
		orig_cabd_is_enabled = method_setImplementation(m, (IMP)hot_cabd_is_enabled);
	}
	m = class_getInstanceMethod(cls, sel_cabd_set_enabled);
	if (m != NULL) {
		orig_cabd_set_enabled = method_setImplementation(m, (IMP)hot_cabd_set_enabled);
	}
	m = class_getInstanceMethod(cls, sel_registerName("init"));
	if (m != NULL) {
		orig_cabd_init = method_setImplementation(m, (IMP)hot_cabd_init);
	}
	say("hot: %s: CABackdropLayer live capture bypass active (/var/local/live-glass re-enables)\n",
	    who);
}

/* ---- SpringBoard: snapshot format & iOS 26+ CloudConfiguration bypass ---- */

#define SNAPSHOT_FORMAT_PNG  0L
#define SNAPSHOT_FORMAT_JPEG 3L

static long
snapshot_output_format(__unused id self, __unused SEL selector, id snapshot)
{
	SEL opaque = sel_registerName("isImageOpaque");

	if (snapshot != NULL &&
	    class_getInstanceMethod(object_getClass(snapshot), opaque) != NULL &&
	    ((BOOL (*)(id, SEL))objc_msgSend)(snapshot, opaque)) {
		return SNAPSHOT_FORMAT_JPEG;
	}
	return SNAPSHOT_FORMAT_PNG;
}

static BOOL
sb26_return_no(__unused id self, __unused SEL selector)
{
	return NO;
}

static bool sb_xb_hooked;
static bool sb_mc_hooked;
static bool sb_rc_hooked;

static void
sb_image_added(__unused const struct mach_header *header, __unused intptr_t slide)
{
	Class cls;
	Method method;

	hot_try_hook_backdrop_layer("SpringBoard");

	if (!sb_xb_hooked &&
	    (cls = objc_getClass("XBApplicationSnapshotManifestImpl")) != NULL) {
		sb_xb_hooked = true;
		method = class_getClassMethod(cls,
		    sel_registerName("_outputFormatForSnapshot:"));
		if (method != NULL) {
			method_setImplementation(method, (IMP)snapshot_output_format);
			say("hot: SpringBoard: +[XBApplicationSnapshotManifestImpl _outputFormatForSnapshot:] -> JPEG/PNG\n");
		}
	}
	if (!sb_mc_hooked && darwin_major() >= 25 &&
	    (cls = objc_getClass("MCProfileConnection")) != NULL) {
		sb_mc_hooked = true;
		method = class_getInstanceMethod(cls,
		    sel_registerName("shouldShowSetupAssistant"));
		if (method != NULL) {
			method_setImplementation(method, (IMP)sb26_return_no);
		}
		method = class_getInstanceMethod(cls,
		    sel_registerName("shouldShowCloudConfigurationUI"));
		if (method != NULL) {
			method_setImplementation(method, (IMP)sb26_return_no);
		}
		say("hot: SpringBoard: -[MCProfileConnection shouldShowSetupAssistant/CloudConfigurationUI] -> NO\n");
	}
	if (!sb_rc_hooked &&
	    (cls = objc_getClass("SBRingerControl")) != NULL) {
		sb_rc_hooked = true;
		method = class_getInstanceMethod(cls,
		    sel_registerName("isRingerMuted"));
		if (method != NULL && method_getNumberOfArguments(method) == 2) {
			const char *enc = method_getTypeEncoding(method);
			if (enc != NULL && (enc[0] == 'B' || enc[0] == 'c')) {
				method_setImplementation(method, (IMP)sb26_return_no);
				say("hot: SpringBoard: -[SBRingerControl isRingerMuted] -> NO\n");
			}
		}
	}
}

static int sb_ringer_token = -1;

static void *
sb_ringer_unmute_thread(__unused void *arg)
{
	int tok = -1;

	sleep(2);
	if (notify_register_check("com.apple.springboard.ringerstate", &tok) == 0) {
		sb_ringer_token = tok;
		(void)notify_set_state(tok, 1);
	}
	(void)notify_post("com.apple.springboard.ringerstate");
	say("hot: SpringBoard: com.apple.springboard.ringerstate -> 1 (+2s)\n");

	sleep(8);
	if (sb_ringer_token >= 0) {
		(void)notify_set_state(sb_ringer_token, 1);
	}
	(void)notify_post("com.apple.springboard.ringerstate");
	say("hot: SpringBoard: com.apple.springboard.ringerstate -> 1 (+10s)\n");
	return NULL;
}

static void
sb_start_ringer_unmute(void)
{
	pthread_t thr;

	if (pthread_create(&thr, NULL, sb_ringer_unmute_thread, NULL) == 0) {
		(void)pthread_detach(thr);
	}
}

/* ---- backboardd (iOS 26+): CBBacklightNode + BKButtonHIDEventProcessor ringer state ---- */

static IMP orig_get_backlight_calib;
static IMP orig_bb_button_init;
static bool bb_cb_hooked;
static bool bb_ringer_hooked;

static BOOL
bb26_get_backlight_calib(id self, SEL selector, float *out)
{
	if (orig_get_backlight_calib != NULL &&
	    ((BOOL (*)(id, SEL, float *))orig_get_backlight_calib)(self, selector, out)) {
		return YES;
	}
	if (out == NULL) {
		return NO;
	}
	/* Matches AppleARMBacklight (625 nits max, 155 nits mid, 2 nits min, 100 mA max) */
	out[0] = 100.0f;	/* I_max */
	out[1] = 25.0f;		/* I_mid */
	out[2] = 100.0f;	/* Ihw_max */
	out[3] = 625.0f;	/* Lmax_product */
	out[4] = 155.0f;	/* Lmid_product */
	out[5] = 2.0f;		/* Lmin_product */
	out[6] = 625.0f;	/* Lmax_panel */
	out[7] = 155.0f;	/* Lmid_panel */
	out[8] = 2.0f;		/* Lmin_panel */
	out[9] = 625.0f;	/* milliAmps2NitsScaleFactor */
	return YES;
}

static uint64_t
bb26_ringer_state(__unused id self, __unused SEL selector)
{
	return 1ULL;
}

static id
bb26_button_init_with_context(id self, SEL selector, id ctx)
{
	id res = orig_bb_button_init != NULL ?
	    ((id (*)(id, SEL, id))orig_bb_button_init)(self, selector, ctx) : self;
	if (res != NULL) {
		SEL set_sel = sel_registerName("setRingerState:");
		if (class_getInstanceMethod(object_getClass(res), set_sel) != NULL) {
			((void (*)(id, SEL, uint64_t))objc_msgSend)(res, set_sel, 1ULL);
		}
	}
	return res;
}

static void
bb_image_added(__unused const struct mach_header *header, __unused intptr_t slide)
{
	Class cls;
	Method method;

	hot_try_hook_backdrop_layer("backboardd");

	if (!bb_ringer_hooked &&
	    (cls = objc_getClass("BKButtonHIDEventProcessor")) != NULL) {
		bb_ringer_hooked = true;
		method = class_getInstanceMethod(cls, sel_registerName("ringerState"));
		if (method != NULL) {
			(void)method_setImplementation(method, (IMP)bb26_ringer_state);
		}
		method = class_getInstanceMethod(cls, sel_registerName("initWithContext:"));
		if (method != NULL) {
			orig_bb_button_init = method_setImplementation(method,
			    (IMP)bb26_button_init_with_context);
		}
		say("hot: backboardd: -[BKButtonHIDEventProcessor ringerState] -> 1 (unmuted)\n");
	}
	if (!bb_cb_hooked && (cls = objc_getClass("CBBacklightNode")) != NULL) {
		bb_cb_hooked = true;
		method = class_getInstanceMethod(cls, sel_registerName("getBacklightCalibration:"));
		if (method != NULL) {
			orig_get_backlight_calib = method_setImplementation(method,
			    (IMP)bb26_get_backlight_calib);
			say("hot: backboardd: -[CBBacklightNode getBacklightCalibration:] -> 625-nit fallback\n");
		}
	}
}

/* ---- akd & cdpd (iOS 26+): HotAuth non-SEP Apple ID & iCloud compatibility ---- */

extern void *objc_autoreleasePoolPush(void);
extern void objc_autoreleasePoolPop(void *pool);

static void auth_logf(const char *format, ...) __attribute__((format(printf, 1, 2)));
static void
auth_logf(const char *format, ...)
{
	struct stat st;
	char msg[512];
	char line[640];
	va_list ap;
	int fd, n;

	va_start(ap, format);
	(void)vsnprintf(msg, sizeof(msg), format, ap);
	va_end(ap);

	n = snprintf(line, sizeof(line), "%s[%d]: %s\n",
	    getprogname() != NULL ? getprogname() : "?", (int)getpid(), msg);
	if (n <= 0) {
		return;
	}
	if (n > (int)sizeof(line) - 1) {
		n = (int)sizeof(line) - 1;
	}
	fd = open("/private/var/local/hotauth.log",
	    O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0666);
	if (fd >= 0) {
		if (fstat(fd, &st) == 0 && st.st_size > 65536) {
			(void)ftruncate(fd, 0);
		}
		(void)write(fd, line, (size_t)n);
		(void)close(fd);
	}
}

static BOOL
ak_return_no(__unused id self, __unused SEL selector)
{
	return NO;
}

static void
hook_bool_method_no(Class cls, const char *cls_name, const char *sel_name)
{
	SEL sel;
	Method m;
	const char *enc;

	if (cls == NULL) {
		return;
	}
	sel = sel_registerName(sel_name);
	m = class_getInstanceMethod(cls, sel);
	if (m != NULL && method_getNumberOfArguments(m) == 2) {
		enc = method_getTypeEncoding(m);
		if (enc != NULL && (enc[0] == 'B' || enc[0] == 'c')) {
			(void)method_setImplementation(m, (IMP)ak_return_no);
			auth_logf("hooked -[%s %s] -> NO", cls_name, sel_name);
		}
	}
	m = class_getClassMethod(cls, sel);
	if (m != NULL && method_getNumberOfArguments(m) == 2) {
		enc = method_getTypeEncoding(m);
		if (enc != NULL && (enc[0] == 'B' || enc[0] == 'c')) {
			(void)method_setImplementation(m, (IMP)ak_return_no);
			auth_logf("hooked +[%s %s] -> NO", cls_name, sel_name);
		}
	}
}

static bool
json_extract_string(const char *json, const char *key, char *out, size_t cap)
{
	char pat[64];
	const char *p;
	size_t o = 0;

	if (json == NULL || out == NULL || cap == 0) {
		return false;
	}
	out[0] = '\0';
	(void)snprintf(pat, sizeof(pat), "\"%s\"", key);
	p = strstr(json, pat);
	if (p == NULL) {
		return false;
	}
	p += strlen(pat);
	while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n' || *p == ':') {
		p++;
	}
	if (*p != '"') {
		return false;
	}
	p++;
	while (*p != '\0' && *p != '"' && o + 1 < cap) {
		if (*p == '\\' && p[1] != '\0') {
			p++;
		}
		out[o++] = *p++;
	}
	out[o] = '\0';
	return o > 0;
}

static bool
json_extract_u64(const char *json, const char *key, uint64_t *out_val)
{
	char pat[64];
	const char *p;
	char *endp = NULL;

	if (json == NULL || out_val == NULL) {
		return false;
	}
	(void)snprintf(pat, sizeof(pat), "\"%s\"", key);
	p = strstr(json, pat);
	if (p == NULL) {
		return false;
	}
	p += strlen(pat);
	while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n' || *p == ':' || *p == '"') {
		p++;
	}
	if (*p < '0' || *p > '9') {
		return false;
	}
	*out_val = strtoull(p, &endp, 10);
	return endp != p;
}

static bool
hotauthd_query_anisette(const char *op, char *body_out, size_t body_cap)
{
	struct sockaddr_in sin;
	struct timeval tv;
	char req[256];
	char resp[4096];
	const char *hdr_end;
	ssize_t rd;
	size_t total = 0;
	int fd, n;

	if (body_out != NULL && body_cap > 0) {
		body_out[0] = '\0';
	}
	fd = socket(AF_INET, SOCK_STREAM, 0);
	if (fd < 0) {
		return false;
	}
	tv.tv_sec = 10;
	tv.tv_usec = 0;
	(void)setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
	(void)setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

	memset(&sin, 0, sizeof(sin));
	sin.sin_family = AF_INET;
	sin.sin_port = htons(29171);
	sin.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	if (connect(fd, (struct sockaddr *)&sin, sizeof(sin)) != 0) {
		(void)close(fd);
		return false;
	}
	n = snprintf(req, sizeof(req),
	    "GET /v1/anisette?op=%s HTTP/1.1\r\n"
	    "Host: 127.0.0.1:29171\r\n"
	    "Connection: close\r\n\r\n",
	    op != NULL ? op : "fetch");
	if (n > 0) {
		(void)send(fd, req, (size_t)n, 0);
	}
	while (total + 1 < sizeof(resp)) {
		rd = recv(fd, resp + total, sizeof(resp) - 1 - total, 0);
		if (rd <= 0) {
			break;
		}
		total += (size_t)rd;
	}
	(void)close(fd);
	resp[total] = '\0';

	hdr_end = strstr(resp, "\r\n\r\n");
	if (hdr_end == NULL) {
		return false;
	}
	hdr_end += 4;
	if (body_out != NULL && body_cap > 0) {
		(void)strlcpy(body_out, hdr_end, body_cap);
	}
	return true;
}

static id
make_ak_error(int64_t code, const char *desc)
{
	Class err_cls = objc_getClass("NSError");
	CFStringRef cf_dom, cf_desc;
	CFDictionaryRef user_info = NULL;
	const void *keys[1];
	const void *vals[1];
	id err_obj = NULL;

	if (err_cls == NULL) {
		return NULL;
	}
	cf_dom = CFSTR("AKAnisetteError");
	cf_desc = CFStringCreateWithCString(kCFAllocatorDefault,
	    desc != NULL ? desc : "HotAuth Anisette bridge unavailable",
	    kCFStringEncodingUTF8);
	if (cf_desc != NULL) {
		keys[0] = CFSTR("NSLocalizedDescription");
		vals[0] = cf_desc;
		user_info = CFDictionaryCreate(kCFAllocatorDefault, keys, vals, 1,
		    &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
	}
	err_obj = ((id (*)(id, SEL, id, int64_t, id))objc_msgSend)(
	    (id)err_cls,
	    sel_registerName("errorWithDomain:code:userInfo:"),
	    (id)cf_dom, code, (id)user_info);
	if (user_info != NULL) {
		CFRelease(user_info);
	}
	if (cf_desc != NULL) {
		CFRelease(cf_desc);
	}
	return err_obj;
}

static void
ak_fetch_anisette_common(const char *op, void (^completion)(id, id))
{
	if (completion == NULL) {
		return;
	}
	dispatch_async(dispatch_get_global_queue(0, 0), ^{
		void *pool = objc_autoreleasePoolPush();
		char body[2048];
		char mid[256];
		char otp[256];
		char err_msg[384];
		uint64_t rinfo = 17106176ULL;

		if (!hotauthd_query_anisette(op, body, sizeof(body))) {
			auth_logf("akd %s: hotauthd unreachable on 127.0.0.1:29171", op);
			completion(NULL, make_ak_error(-8004,
			    "hotauthd is not reachable on 127.0.0.1:29171"));
			objc_autoreleasePoolPop(pool);
			return;
		}
		if (strstr(body, "\"ok\":true") != NULL &&
		    json_extract_string(body, "machineID", mid, sizeof(mid)) &&
		    json_extract_string(body, "oneTimePassword", otp, sizeof(otp))) {
			Class cls_ad = objc_getClass("AKAnisetteData");
			id ad = NULL;

			(void)json_extract_u64(body, "routingInfo", &rinfo);
			if (cls_ad != NULL) {
				ad = ((id (*)(id, SEL))objc_msgSend)(
				    ((id (*)(id, SEL))objc_msgSend)((id)cls_ad, sel_registerName("alloc")),
				    sel_registerName("init"));
			}
			if (ad != NULL) {
				CFStringRef cf_mid = CFStringCreateWithCString(kCFAllocatorDefault,
				    mid, kCFStringEncodingUTF8);
				CFStringRef cf_otp = CFStringCreateWithCString(kCFAllocatorDefault,
				    otp, kCFStringEncodingUTF8);
				SEL sel_rinfo = sel_registerName("setRoutingInfo:");
				Method m_rinfo = class_getInstanceMethod(cls_ad, sel_rinfo);

				if (cf_mid != NULL) {
					((void (*)(id, SEL, id))objc_msgSend)(ad,
					    sel_registerName("setMachineID:"), (id)cf_mid);
					CFRelease(cf_mid);
				}
				if (cf_otp != NULL) {
					((void (*)(id, SEL, id))objc_msgSend)(ad,
					    sel_registerName("setOneTimePassword:"), (id)cf_otp);
					CFRelease(cf_otp);
				}
				if (m_rinfo != NULL) {
					char arg_t[16];
					memset(arg_t, 0, sizeof(arg_t));
					method_getArgumentType(m_rinfo, 2, arg_t, sizeof(arg_t) - 1);
					if (arg_t[0] == 'Q' || arg_t[0] == 'q' ||
					    arg_t[0] == 'I' || arg_t[0] == 'i') {
						((void (*)(id, SEL, uint64_t))objc_msgSend)(
						    ad, sel_rinfo, rinfo);
					}
				}
				ad = ((id (*)(id, SEL))objc_msgSend)(ad, sel_registerName("autorelease"));
				auth_logf("akd %s: supplied AKAnisetteData (mid_len=%zu otp_len=%zu rinfo=%llu)",
				    op, strlen(mid), strlen(otp), (unsigned long long)rinfo);
				completion(ad, NULL);
				objc_autoreleasePoolPop(pool);
				return;
			}
		}

		if (!json_extract_string(body, "error", err_msg, sizeof(err_msg))) {
			(void)strlcpy(err_msg,
			    "Open HotAuth -> Settings Sign-In to configure Anisette, or use iCloud Sync (App Password).",
			    sizeof(err_msg));
		}
		auth_logf("akd %s failed: %s", op, err_msg);
		completion(NULL, make_ak_error(-8004, err_msg));
		objc_autoreleasePoolPop(pool);
	});
}

static void
ak_bool_anisette_common(const char *op, void (^completion)(BOOL, id))
{
	if (completion == NULL) {
		return;
	}
	dispatch_async(dispatch_get_global_queue(0, 0), ^{
		void *pool = objc_autoreleasePoolPush();
		char body[2048];
		char err_msg[384];

		if (hotauthd_query_anisette(op, body, sizeof(body)) &&
		    strstr(body, "\"ok\":true") != NULL) {
			auth_logf("akd %s: OK", op);
			completion(YES, NULL);
			objc_autoreleasePoolPop(pool);
			return;
		}
		if (!json_extract_string(body, "error", err_msg, sizeof(err_msg))) {
			(void)strlcpy(err_msg, "Anisette provisioning not configured in HotAuth",
			    sizeof(err_msg));
		}
		auth_logf("akd %s: %s", op, err_msg);
		completion(NO, make_ak_error(-8004, err_msg));
		objc_autoreleasePoolPop(pool);
	});
}

static void
ak_fetch_anisette(__unused id self, __unused SEL selector, __unused BOOL prov,
    void (^completion)(id, id))
{
	ak_fetch_anisette_common(prov ? "fetch_prov" : "fetch", completion);
}

static void
ak_legacy_anisette(__unused id self, __unused SEL selector, __unused id dsid,
    void (^completion)(id, id))
{
	ak_fetch_anisette_common("legacy", completion);
}

static void
ak_provision_anisette(__unused id self, __unused SEL selector,
    void (^completion)(BOOL, id))
{
	ak_bool_anisette_common("provision", completion);
}

static void
ak_sync_anisette(__unused id self, __unused SEL selector, __unused id sim_data,
    void (^completion)(BOOL, id))
{
	ak_bool_anisette_common("sync", completion);
}

static void
ak_erase_anisette(__unused id self, __unused SEL selector,
    void (^completion)(BOOL, id))
{
	ak_bool_anisette_common("erase", completion);
}

static bool akd_baa_hooked;
static bool akd_anisette_hooked;

static void
akd_image_added(__unused const struct mach_header *header, __unused intptr_t slide)
{
	static const char *const baa_classes[] = {
		"AKStrongDeviceIdentitySigner",
		"_AKStrongDeviceIdentitySigner",
		"AKDeviceIdentityWrapper",
		"AKDevice",
	};
	Class cls;
	Method m;
	size_t i;

	if (!akd_baa_hooked && objc_getClass("AKStrongDeviceIdentitySigner") != NULL) {
		akd_baa_hooked = true;
		for (i = 0; i < sizeof(baa_classes) / sizeof(baa_classes[0]); i++) {
			cls = objc_getClass(baa_classes[i]);
			hook_bool_method_no(cls, baa_classes[i], "isStrongDeviceIdentitySupported");
			hook_bool_method_no(cls, baa_classes[i], "isPhysicalDeviceBAAEnabled");
		}
	}

	if (!akd_anisette_hooked &&
	    (cls = objc_getClass("AKNativeAnisetteService")) != NULL) {
		akd_anisette_hooked = true;
		m = class_getInstanceMethod(cls,
		    sel_registerName("fetchAnisetteDataAndProvisionIfNecessary:withCompletion:"));
		if (m != NULL) {
			(void)method_setImplementation(m, (IMP)ak_fetch_anisette);
		}
		m = class_getInstanceMethod(cls,
		    sel_registerName("legacyAnisetteDataForDSID:withCompletion:"));
		if (m != NULL) {
			(void)method_setImplementation(m, (IMP)ak_legacy_anisette);
		}
		m = class_getInstanceMethod(cls,
		    sel_registerName("provisionAnisetteWithCompletion:"));
		if (m != NULL) {
			(void)method_setImplementation(m, (IMP)ak_provision_anisette);
		}
		m = class_getInstanceMethod(cls,
		    sel_registerName("syncAnisetteWithSIMData:completion:"));
		if (m != NULL) {
			(void)method_setImplementation(m, (IMP)ak_sync_anisette);
		}
		m = class_getInstanceMethod(cls,
		    sel_registerName("eraseAnisetteWithCompletion:"));
		if (m != NULL) {
			(void)method_setImplementation(m, (IMP)ak_erase_anisette);
		}
		auth_logf("hooked AKNativeAnisetteService -> hotauthd (127.0.0.1:29171)");
	}
}

static void
cdp_set_kc_sync_enabled(__unused id self, __unused SEL selector, BOOL enabled,
    void (^completion)(BOOL, id))
{
	auth_logf("cdpd setUserVisibleKeychainSyncEnabled:%d -> local non-SEP success",
	    (int)enabled);
	if (completion != NULL) {
		completion(YES, NULL);
	}
}

static BOOL
cdp_should_perform_repair(__unused id self, __unused SEL selector,
    __unused BOOL force_fetch, id *out_error)
{
	if (out_error != NULL) {
		*out_error = NULL;
	}
	return NO;
}

static bool cdp_kc_hooked;
static bool cdp_sm_hooked;

static void
cdp_image_added(__unused const struct mach_header *header, __unused intptr_t slide)
{
	Class cls;
	Method m;

	if (!cdp_kc_hooked && (cls = objc_getClass("CDPDKeychainSync")) != NULL) {
		cdp_kc_hooked = true;
		hook_bool_method_no(cls, "CDPDKeychainSync", "isUserVisibleKeychainSyncEnabled");
		m = class_getInstanceMethod(cls,
		    sel_registerName("setUserVisibleKeychainSyncEnabled:withCompletion:"));
		if (m != NULL && method_getNumberOfArguments(m) == 4) {
			(void)method_setImplementation(m, (IMP)cdp_set_kc_sync_enabled);
			auth_logf("hooked -[CDPDKeychainSync setUserVisibleKeychainSyncEnabled:withCompletion:]");
		}
		m = class_getClassMethod(cls,
		    sel_registerName("setUserVisibleKeychainSyncEnabled:withCompletion:"));
		if (m != NULL && method_getNumberOfArguments(m) == 4) {
			(void)method_setImplementation(m, (IMP)cdp_set_kc_sync_enabled);
			auth_logf("hooked +[CDPDKeychainSync setUserVisibleKeychainSyncEnabled:withCompletion:]");
		}
	}
	if (!cdp_sm_hooked && (cls = objc_getClass("CDPDStateMachine")) != NULL) {
		cdp_sm_hooked = true;
		m = class_getInstanceMethod(cls,
		    sel_registerName("shouldPerformRepairWithOptionForceFetch:error:"));
		if (m != NULL && method_getNumberOfArguments(m) == 4) {
			const char *enc = method_getTypeEncoding(m);
			if (enc != NULL && (enc[0] == 'B' || enc[0] == 'c')) {
				(void)method_setImplementation(m, (IMP)cdp_should_perform_repair);
				auth_logf("hooked -[CDPDStateMachine shouldPerformRepairWithOptionForceFetch:error:] -> NO");
			}
		}
	}
}

__attribute__((constructor))
static void
hot_sysfix_init(void)
{
	const char *name = getprogname();

	if (name == NULL) {
		return;
	}
	if (strcmp(name, "containermanagerd") == 0 ||
	    strcmp(name, "containermanagerd_system") == 0) {
		_dyld_register_func_for_add_image(cm_image_added);
		cm_image_added(NULL, 0);
	} else if (strcmp(name, "backboardd") == 0) {
		/*
		 * Disable 10-bit deep framebuffer (b3a8) and Apple framebuffer
		 * compression BEFORE backboardd's main() opens IOMFB / QuartzCore
		 * (2.6 s before HotMetal2.bundle loads on Boot #1), ensuring a
		 * plain 8-bit BGRA swapchain and full 60 Hz gpu_direct compositing.
		 */
		(void)setenv("CA_DISABLE_FRAMEBUFFER_COMPRESSION", "1", 1);
		CFPreferencesSetAppValue(CFSTR("CAEnableFramebufferCompression"),
		    kCFBooleanFalse, kCFPreferencesCurrentApplication);
		CFPreferencesSetAppValue(CFSTR("CAEnableIOSurfaceCompression"),
		    kCFBooleanFalse, kCFPreferencesCurrentApplication);
		CFPreferencesSetAppValue(CFSTR("CAEnableDeepFramebuffer"),
		    kCFBooleanFalse, kCFPreferencesCurrentApplication);
		if (darwin_major() >= 25) {
			_dyld_register_func_for_add_image(bb_image_added);
			bb_image_added(NULL, 0);
		}
	} else if (strcmp(name, "SpringBoard") == 0) {
		_dyld_register_func_for_add_image(sb_image_added);
		sb_image_added(NULL, 0);
		sb_start_ringer_unmute();
	} else if (strcmp(name, "akd") == 0) {
		_dyld_register_func_for_add_image(akd_image_added);
		akd_image_added(NULL, 0);
	} else if (strcmp(name, "cdpd") == 0) {
		_dyld_register_func_for_add_image(cdp_image_added);
		cdp_image_added(NULL, 0);
	}
}
