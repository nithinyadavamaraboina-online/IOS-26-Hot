/*
 * hotauthd.m — Local Apple Account & iCloud compatibility daemon ("microG for iOS")
 * Bound strictly to 127.0.0.1:29171 on RMX1931 / SWEET (iOS 26.5.2).
 *
 * Endpoints:
 *   GET  /v1/selfcheck         — Live subsystem health check (MobileActivation, 512B Keychain
 *                                wrap/unwrap on SweetKeyStore 0148, clock, gsa.apple.com +
 *                                icloud.com TLS, ACAccountStore, inject.conf, Anisette state)
 *   POST /v1/setup-icloud-dav  — Verifies Apple ID + App-Specific Password over HTTPS and
 *                                provisions native CardDAV, CalDAV, and IMAP/SMTP accounts
 *                                in accountsd for Contacts, Calendar, Mail, and Notes
 *   GET  /v1/config            — Returns current Anisette bridge config
 *   POST /v1/config            — Saves Anisette v1/v3 config (/private/var/local/hotauth/config.json, 0600)
 *   GET  /v1/anisette?op=...   — Supplies AKAnisetteData (machineID, oneTimePassword, routingInfo)
 *                                to akd (via libhot_sysfix.dylib) using user-configured v1 or v3 URL
 *   GET  /v1/logs              — Reads /private/var/local/hotauth.log
 *   POST /v1/clear-logs        — Truncates /private/var/local/hotauth.log
 */
#import <Foundation/Foundation.h>
#import <Security/Security.h>
#import <Accounts/Accounts.h>
#include <CommonCrypto/CommonDigest.h>
#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <objc/message.h>
#include <objc/runtime.h>
#include <pthread.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>

static NSString *const kAuthDir       = @"/private/var/local/hotauth";
static NSString *const kConfigPath    = @"/private/var/local/hotauth/config.json";
static NSString *const kADIStatePath  = @"/private/var/local/hotauth/adi_state.plist";
static NSString *const kAuthLogPath   = @"/private/var/local/hotauth.log";

static NSLock *gStateLock = nil;

static void authd_log(NSString *msg)
{
	NSString *line = [NSString stringWithFormat:@"[hotauthd %d] %@\n", (int)getpid(), msg];
	NSData *d = [line dataUsingEncoding:NSUTF8StringEncoding];
	int fd = open(kAuthLogPath.UTF8String, O_WRONLY | O_CREAT | O_APPEND, 0666);
	if (fd >= 0) {
		struct stat st;
		if (fstat(fd, &st) == 0 && st.st_size > 131072) {
			(void)ftruncate(fd, 0);
		}
		if (d.length > 0) {
			(void)write(fd, d.bytes, d.length);
		}
		close(fd);
	}
	fprintf(stderr, "%s", line.UTF8String);
}

static void ensure_auth_dir(void)
{
	(void)mkdir("/private/var/local", 0755);
	(void)mkdir(kAuthDir.UTF8String, 0700);
	(void)chmod(kAuthDir.UTF8String, 0700);
}

static void ensure_keychain_dirs(void)
{
	(void)mkdir("/private/var/Keychains", 0775);
	(void)chown("/private/var/Keychains", 64, 64);
	(void)chmod("/private/var/Keychains", 0775);

	(void)mkdir("/private/var/Keychains/Analytics", 0775);
	(void)chown("/private/var/Keychains/Analytics", 64, 64);
	(void)chmod("/private/var/Keychains/Analytics", 0775);

	struct stat kst;
	if (lstat("/private/var/keychains", &kst) == 0 && S_ISDIR(kst.st_mode)) {
		NSFileManager *fm = [NSFileManager defaultManager];
		NSArray<NSString *> *items = [fm contentsOfDirectoryAtPath:@"/private/var/keychains" error:nil];
		for (NSString *name in items) {
			NSString *src = [@"/private/var/keychains" stringByAppendingPathComponent:name];
			NSString *dst = [@"/private/var/Keychains" stringByAppendingPathComponent:name];
			if (![fm fileExistsAtPath:dst]) {
				[fm moveItemAtPath:src toPath:dst error:nil];
			} else {
				[fm removeItemAtPath:src error:nil];
			}
		}
		(void)rmdir("/private/var/keychains");
	}
	(void)symlink("Keychains", "/private/var/keychains");

	(void)mkdir("/private/var/mobile/Library", 0755);
	(void)mkdir("/private/var/mobile/Library/Keychains", 0775);
	(void)chown("/private/var/mobile/Library/Keychains", 501, 64);
	(void)chmod("/private/var/mobile/Library/Keychains", 0775);

	(void)mkdir("/private/var/protected", 0755);
	(void)chmod("/private/var/protected", 0755);
	(void)mkdir("/private/var/protected/sfanalytics", 0775);
	(void)chown("/private/var/protected/sfanalytics", 64, 64);
	(void)chmod("/private/var/protected/sfanalytics", 0775);
}

static NSString *json_escape(NSString *s)
{
	if (!s) return @"";
	NSMutableString *m = [NSMutableString stringWithCapacity:s.length + 8];
	for (NSUInteger i = 0; i < s.length; i++) {
		unichar c = [s characterAtIndex:i];
		if (c == '"') [m appendString:@"\\\""];
		else if (c == '\\') [m appendString:@"\\\\"];
		else if (c == '\n') [m appendString:@"\\n"];
		else if (c == '\r') [m appendString:@"\\r"];
		else if (c == '\t') [m appendString:@"\\t"];
		else if (c < 0x20) [m appendFormat:@"\\u%04x", (unsigned)c];
		else [m appendFormat:@"%C", c];
	}
	return m;
}

static NSDictionary *load_config_locked(void)
{
	NSData *d = [NSData dataWithContentsOfFile:kConfigPath];
	if (!d) return @{ @"enabled": @NO, @"mode": @"v3", @"url": @"" };
	id obj = [NSJSONSerialization JSONObjectWithData:d options:0 error:nil];
	if (![obj isKindOfClass:[NSDictionary class]]) {
		return @{ @"enabled": @NO, @"mode": @"v3", @"url": @"" };
	}
	return (NSDictionary *)obj;
}

static BOOL save_config_locked(NSDictionary *cfg)
{
	ensure_auth_dir();
	NSData *d = [NSJSONSerialization dataWithJSONObject:cfg options:NSJSONWritingPrettyPrinted error:nil];
	if (!d) return NO;
	BOOL ok = [d writeToFile:kConfigPath atomically:YES];
	if (ok) (void)chmod(kConfigPath.UTF8String, 0600);
	return ok;
}

static NSMutableDictionary *load_adi_state_locked(void)
{
	NSDictionary *d = [NSDictionary dictionaryWithContentsOfFile:kADIStatePath];
	if ([d isKindOfClass:[NSDictionary class]]) {
		return [d mutableCopy];
	}
	NSMutableDictionary *m = [NSMutableDictionary dictionary];
	uint8_t raw_uuid[16];
	arc4random_buf(raw_uuid, sizeof(raw_uuid));
	raw_uuid[6] = (raw_uuid[6] & 0x0F) | 0x40;
	raw_uuid[8] = (raw_uuid[8] & 0x3F) | 0x80;
	NSData *idData = [NSData dataWithBytes:raw_uuid length:sizeof(raw_uuid)];
	m[@"identifier"] = [idData base64EncodedStringWithOptions:0];

	NSMutableString *hexUuid = [NSMutableString stringWithCapacity:36];
	for (int i = 0; i < 16; i++) {
		if (i == 4 || i == 6 || i == 8 || i == 10) [hexUuid appendString:@"-"];
		[hexUuid appendFormat:@"%02X", raw_uuid[i]];
	}
	m[@"deviceUUID"] = hexUuid;

	unsigned char sha[CC_SHA256_DIGEST_LENGTH];
	NSData *uBytes = [hexUuid dataUsingEncoding:NSUTF8StringEncoding];
	CC_SHA256(uBytes.bytes, (CC_LONG)uBytes.length, sha);
	NSMutableString *luHex = [NSMutableString stringWithCapacity:64];
	for (int i = 0; i < CC_SHA256_DIGEST_LENGTH; i++) {
		[luHex appendFormat:@"%02X", sha[i]];
	}
	m[@"localUserUUID"] = luHex;

	ensure_auth_dir();
	[m writeToFile:kADIStatePath atomically:YES];
	(void)chmod(kADIStatePath.UTF8String, 0600);
	return m;
}

static void save_adi_state_locked(NSDictionary *st)
{
	ensure_auth_dir();
	[st writeToFile:kADIStatePath atomically:YES];
	(void)chmod(kADIStatePath.UTF8String, 0600);
}

/* ---- Subsystem Self-Check ---- */

static NSString *query_activation_state(void)
{
	void *h = dlopen("/System/Library/PrivateFrameworks/MobileActivation.framework/MobileActivation", RTLD_LAZY);
	if (h) {
		typedef id (*mae_fn_t)(id *err);
		mae_fn_t fn = (mae_fn_t)dlsym(h, "MAEGetActivationStateWithError");
		if (fn) {
			id err = nil;
			id st = fn(&err);
			if ([st isKindOfClass:[NSString class]] && [(NSString *)st length] > 0) {
				return (NSString *)st;
			}
		}
	}
	NSArray *recs = [[NSFileManager defaultManager] contentsOfDirectoryAtPath:@"/private/var/root/Library/Lockdown/activation_records" error:nil];
	if (recs.count > 0) return @"Activated (record)";
	NSArray *recs2 = [[NSFileManager defaultManager] contentsOfDirectoryAtPath:@"/private/var/containers/Data/System" error:nil];
	(void)recs2;
	return @"Unknown";
}

static BOOL run_keychain_roundtrip_512b_query(NSDictionary *baseQuery, NSData *payload,
    int *out_status, NSString **out_step, NSInteger *out_got_len)
{
	(void)SecItemDelete((__bridge CFDictionaryRef)baseQuery);

	NSMutableDictionary *addAttrs = [baseQuery mutableCopy];
	addAttrs[(__bridge id)kSecValueData] = payload;
	addAttrs[(__bridge id)kSecAttrAccessible] = (__bridge id)kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly;

	OSStatus st = SecItemAdd((__bridge CFDictionaryRef)addAttrs, NULL);
	if (st != errSecSuccess) {
		usleep(200000);
		(void)SecItemDelete((__bridge CFDictionaryRef)baseQuery);
		st = SecItemAdd((__bridge CFDictionaryRef)addAttrs, NULL);
	}
	if (st != errSecSuccess) {
		if (out_status) *out_status = (int)st;
		if (out_step) *out_step = @"add";
		if (out_got_len) *out_got_len = -1;
		return NO;
	}

	NSMutableDictionary *copyQuery = [baseQuery mutableCopy];
	copyQuery[(__bridge id)kSecReturnData] = (__bridge id)kCFBooleanTrue;
	copyQuery[(__bridge id)kSecMatchLimit] = (__bridge id)kSecMatchLimitOne;

	CFTypeRef result = NULL;
	st = SecItemCopyMatching((__bridge CFDictionaryRef)copyQuery, &result);
	if (st != errSecSuccess || !result) {
		if (result) {
			CFRelease(result);
			result = NULL;
		}
		usleep(250000);
		st = SecItemCopyMatching((__bridge CFDictionaryRef)copyQuery, &result);
	}
	(void)SecItemDelete((__bridge CFDictionaryRef)baseQuery);

	if (out_status) *out_status = (int)st;
	if (st != errSecSuccess || !result) {
		if (result) CFRelease(result);
		if (out_step) *out_step = (st != errSecSuccess) ? @"copy" : @"null_result";
		if (out_got_len) *out_got_len = -1;
		return NO;
	}
	id obj = (__bridge_transfer id)result;
	NSData *got = nil;
	if ([obj isKindOfClass:[NSData class]]) {
		got = (NSData *)obj;
	} else if ([obj isKindOfClass:[NSDictionary class]]) {
		id vd = ((NSDictionary *)obj)[(__bridge id)kSecValueData];
		if ([vd isKindOfClass:[NSData class]]) {
			got = (NSData *)vd;
		}
	}
	if (out_got_len) *out_got_len = got ? (NSInteger)got.length : -2;
	BOOL eq = (got != nil) && [got isEqualToData:payload];
	if (out_step) *out_step = eq ? @"ok" : (got ? @"mismatch" : NSStringFromClass([obj class]));
	return eq;
}

static BOOL run_keychain_roundtrip_512b(int *out_status, NSString **out_step, NSInteger *out_got_len)
{
	ensure_keychain_dirs();

	NSMutableData *payload = [NSMutableData dataWithLength:512];
	uint8_t *p = (uint8_t *)payload.mutableBytes;
	for (size_t i = 0; i < 512; i++) {
		p[i] = (uint8_t)((i * 131 + 0x5A) & 0xFF);
	}
	NSDictionary *defaultAgrpQuery = @{
		(__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
		(__bridge id)kSecAttrService: @"com.ios26hot.hotauth.selftest",
		(__bridge id)kSecAttrAccount: @"roundtrip512",
	};
	(void)SecItemDelete((__bridge CFDictionaryRef)defaultAgrpQuery);

	NSDictionary *explicitAgrpQuery = @{
		(__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
		(__bridge id)kSecAttrAccessGroup: @"com.ios26hot.hotauthd",
		(__bridge id)kSecAttrService: @"com.ios26hot.hotauth.selftest",
		(__bridge id)kSecAttrAccount: @"roundtrip512",
	};
	if (run_keychain_roundtrip_512b_query(explicitAgrpQuery, payload, out_status, out_step, out_got_len)) {
		return YES;
	}
	return run_keychain_roundtrip_512b_query(defaultAgrpQuery, payload, out_status, out_step, out_got_len);
}

static NSInteger probe_https_status(NSString *urlStr)
{
	NSURL *u = [NSURL URLWithString:urlStr];
	if (!u) return 0;
	NSURLSessionConfiguration *cfg = [NSURLSessionConfiguration ephemeralSessionConfiguration];
	cfg.timeoutIntervalForRequest = 6.0;
	cfg.timeoutIntervalForResource = 7.0;
	NSURLSession *sess = [NSURLSession sessionWithConfiguration:cfg];

	NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:u];
	req.HTTPMethod = @"GET";
	[req setValue:@"HotAuth/1.0" forHTTPHeaderField:@"User-Agent"];

	__block NSInteger code = 0;
	dispatch_semaphore_t sem = dispatch_semaphore_create(0);
	[[sess dataTaskWithRequest:req completionHandler:^(__unused NSData *data, NSURLResponse *resp, NSError *err) {
		if (!err && [resp isKindOfClass:[NSHTTPURLResponse class]]) {
			code = [(NSHTTPURLResponse *)resp statusCode];
		} else if (err) {
			code = -1;
		}
		dispatch_semaphore_signal(sem);
	}] resume];
	(void)dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(7.5 * NSEC_PER_SEC)));
	[sess finishTasksAndInvalidate];
	return code;
}

static NSDictionary *run_selfcheck(void)
{
	NSString *actState = query_activation_state();
	BOOL actOK = ([actState rangeOfString:@"Activated" options:NSCaseInsensitiveSearch].location != NSNotFound);

	int kcStatus = -1;
	NSString *kcStep = @"unrun";
	NSInteger kcGotLen = -1;
	BOOL kcOK = run_keychain_roundtrip_512b(&kcStatus, &kcStep, &kcGotLen);

	NSTimeInterval now = [[NSDate date] timeIntervalSince1970];
	BOOL clockOK = (now >= 1700000000.0);

	__block NSInteger gsaCode = 0;
	__block NSInteger icloudCode = 0;
	dispatch_group_t grp = dispatch_group_create();
	dispatch_group_async(grp, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
		gsaCode = probe_https_status(@"https://gsa.apple.com/grandslam/GsService2/lookup");
	});
	dispatch_group_async(grp, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
		icloudCode = probe_https_status(@"https://www.icloud.com");
	});
	(void)dispatch_group_wait(grp, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(8.0 * NSEC_PER_SEC)));
	BOOL tlsOK = (gsaCode >= 200 && gsaCode < 500) && (icloudCode >= 200 && icloudCode < 500);

	NSInteger acctCount = 0;
	NSMutableArray *acctNames = [NSMutableArray array];
	@try {
		ACAccountStore *store = [[ACAccountStore alloc] init];
		SEL allSel = sel_registerName("allAccounts");
		if ([store respondsToSelector:allSel]) {
			NSArray *all = ((NSArray *(*)(id, SEL))objc_msgSend)(store, allSel);
			if ([all isKindOfClass:[NSArray class]]) {
				acctCount = (NSInteger)all.count;
				for (ACAccount *a in all) {
					NSString *desc = [NSString stringWithFormat:@"%@ (%@)",
					    a.username.length ? a.username : @"account",
					    a.accountType.identifier ?: @"?"];
					[acctNames addObject:desc];
				}
			}
		}
	} @catch (__unused NSException *e) {}

	NSString *injText = [NSString stringWithContentsOfFile:@"/private/var/local/inject.conf" encoding:NSUTF8StringEncoding error:nil] ?: @"";
	BOOL sysfixOK = [[NSFileManager defaultManager] fileExistsAtPath:@"/usr/local/lib/libhot_sysfix.dylib"]
	             && ([injText rangeOfString:@"akd"].location != NSNotFound)
	             && ([injText rangeOfString:@"cdpd"].location != NSNotFound);
	BOOL appfixOK = [[NSFileManager defaultManager] fileExistsAtPath:@"/usr/local/lib/libhot_appfix.dylib"]
	             && ([injText rangeOfString:@"@userapps"].location != NSNotFound);

	[gStateLock lock];
	NSDictionary *cfg = load_config_locked();
	NSDictionary *adi = load_adi_state_locked();
	[gStateLock unlock];

	BOOL aniEnabled = [cfg[@"enabled"] boolValue];
	NSString *aniMode = [cfg[@"mode"] isKindOfClass:[NSString class]] ? cfg[@"mode"] : @"v3";
	NSString *aniURL = [cfg[@"url"] isKindOfClass:[NSString class]] ? cfg[@"url"] : @"";
	BOOL aniProvisioned = ([adi[@"adi_pb"] isKindOfClass:[NSString class]] && [(NSString *)adi[@"adi_pb"] length] > 16);

	return @{
		@"ok": @YES,
		@"activation_state": actState ?: @"Unknown",
		@"activation_ok": @(actOK),
		@"keychain_512b_ok": @(kcOK),
		@"keychain_status": @(kcStatus),
		@"keychain_step": kcStep ?: @"?",
		@"keychain_got_len": @(kcGotLen),
		@"clock_epoch": @((long long)now),
		@"clock_ok": @(clockOK),
		@"gsa_http": @(gsaCode),
		@"icloud_http": @(icloudCode),
		@"tls_ok": @(tlsOK),
		@"accounts_count": @(acctCount),
		@"accounts": acctNames,
		@"sysfix_akd_cdpd_ok": @(sysfixOK),
		@"appfix_userapps_ok": @(appfixOK),
		@"anisette_enabled": @(aniEnabled),
		@"anisette_mode": aniMode,
		@"anisette_url": aniURL,
		@"anisette_provisioned": @(aniProvisioned),
	};
}

/* ---- Native CardDAV / CalDAV / IMAP Account Provisioning ---- */

static NSInteger verify_icloud_app_password(NSString *email, NSString *password)
{
	NSURL *u = [NSURL URLWithString:@"https://contacts.icloud.com/"];
	NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:u];
	req.HTTPMethod = @"PROPFIND";
	[req setValue:@"0" forHTTPHeaderField:@"Depth"];
	[req setValue:@"application/xml; charset=utf-8" forHTTPHeaderField:@"Content-Type"];
	NSString *rawAuth = [NSString stringWithFormat:@"%@:%@", email, password];
	NSString *b64 = [[rawAuth dataUsingEncoding:NSUTF8StringEncoding] base64EncodedStringWithOptions:0];
	[req setValue:[NSString stringWithFormat:@"Basic %@", b64] forHTTPHeaderField:@"Authorization"];
	req.HTTPBody = [@"<?xml version=\"1.0\" encoding=\"utf-8\"?><D:propfind xmlns:D=\"DAV:\"><D:prop><D:current-user-principal/></D:prop></D:propfind>" dataUsingEncoding:NSUTF8StringEncoding];

	NSURLSessionConfiguration *cfg = [NSURLSessionConfiguration ephemeralSessionConfiguration];
	cfg.timeoutIntervalForRequest = 10.0;
	NSURLSession *sess = [NSURLSession sessionWithConfiguration:cfg];

	__block NSInteger status = 0;
	dispatch_semaphore_t sem = dispatch_semaphore_create(0);
	[[sess dataTaskWithRequest:req completionHandler:^(__unused NSData *data, NSURLResponse *resp, NSError *err) {
		if (!err && [resp isKindOfClass:[NSHTTPURLResponse class]]) {
			status = [(NSHTTPURLResponse *)resp statusCode];
		} else {
			status = -1;
		}
		dispatch_semaphore_signal(sem);
	}] resume];
	(void)dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(11.0 * NSEC_PER_SEC)));
	[sess finishTasksAndInvalidate];
	return status;
}

static BOOL save_account_sync(ACAccountStore *store, ACAccount *acct, NSString **outErr)
{
	__block BOOL saved = NO;
	__block NSError *saveErr = nil;
	dispatch_semaphore_t sem = dispatch_semaphore_create(0);

	SEL verSel = sel_registerName("saveVerifiedAccount:withCompletionHandler:");
	if ([store respondsToSelector:verSel]) {
		void (^blk)(BOOL, NSError *) = ^(BOOL success, NSError *error) {
			saved = success;
			saveErr = error;
			dispatch_semaphore_signal(sem);
		};
		((void (*)(id, SEL, id, id))objc_msgSend)(store, verSel, acct, blk);
	} else {
		[store saveAccount:acct withCompletionHandler:^(BOOL success, NSError *error) {
			saved = success;
			saveErr = error;
			dispatch_semaphore_signal(sem);
		}];
	}
	(void)dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(10.0 * NSEC_PER_SEC)));
	if (!saved && outErr) {
		*outErr = saveErr.localizedDescription ?: @"ACAccountStore save timed out or declined";
	}
	return saved;
}

static void set_acct_prop(ACAccount *acct, NSString *key, id val)
{
	SEL setProp = sel_registerName("setAccountProperty:forKey:");
	if ([acct respondsToSelector:setProp]) {
		((void (*)(id, SEL, id, id))objc_msgSend)(acct, setProp, val, key);
	}
}

static void enable_acct_dataclass(ACAccount *acct, NSString *dc)
{
	SEL setEn = sel_registerName("setEnabled:forDataclass:");
	if ([acct respondsToSelector:setEn]) {
		((void (*)(id, SEL, BOOL, id))objc_msgSend)(acct, setEn, YES, dc);
	}
	SEL setProv = sel_registerName("setProvisioned:forDataclass:");
	if ([acct respondsToSelector:setProv]) {
		((void (*)(id, SEL, BOOL, id))objc_msgSend)(acct, setProv, YES, dc);
	}
}

static NSDictionary *setup_icloud_dav(NSDictionary *req)
{
	NSString *email = [[req[@"email"] isKindOfClass:[NSString class]] ? req[@"email"] : @"" stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
	NSString *appPw = [[req[@"appPassword"] isKindOfClass:[NSString class]] ? req[@"appPassword"] : @"" stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
	appPw = [appPw stringByReplacingOccurrencesOfString:@" " withString:@""];

	if (email.length < 4 || [email rangeOfString:@"@"].location == NSNotFound || appPw.length < 8) {
		return @{ @"ok": @NO, @"error": @"Enter your Apple ID email and 16-character App-Specific Password." };
	}

	NSInteger httpCode = verify_icloud_app_password(email, appPw);
	authd_log([NSString stringWithFormat:@"setup-icloud-dav verify %@ -> HTTP %ld", email, (long)httpCode]);
	if (httpCode == 401 || httpCode == 403) {
		return @{
			@"ok": @NO,
			@"error": @"Apple rejected the email or App-Specific Password (HTTP 401). Generate a fresh App-Specific Password at account.apple.com -> Sign-In and Security -> App-Specific Passwords."
		};
	}
	if (httpCode <= 0) {
		return @{ @"ok": @NO, @"error": @"Could not reach contacts.icloud.com over HTTPS. Check Wi-Fi (en0)." };
	}

	ACAccountStore *store = [[ACAccountStore alloc] init];
	NSMutableArray *created = [NSMutableArray array];
	NSMutableArray *warnings = [NSMutableArray array];

	BOOL wantContacts = req[@"enableContacts"] ? [req[@"enableContacts"] boolValue] : YES;
	BOOL wantCalendar = req[@"enableCalendar"] ? [req[@"enableCalendar"] boolValue] : YES;
	BOOL wantMail     = req[@"enableMail"] ? [req[@"enableMail"] boolValue] : YES;

	/* 1. CardDAV (iCloud Contacts) */
	if (wantContacts) {
		ACAccountType *t = [store accountTypeWithAccountTypeIdentifier:@"com.apple.account.CardDAV"];
		if (t) {
			ACAccount *a = [[ACAccount alloc] initWithAccountType:t];
			a.username = email;
			a.accountDescription = [NSString stringWithFormat:@"iCloud Contacts (%@)", email];
			a.credential = [[ACAccountCredential alloc] initWithOAuth2Token:appPw refreshToken:nil expiryDate:nil];
			SEL initPass = sel_registerName("initWithPassword:");
			if ([ACAccountCredential instancesRespondToSelector:initPass]) {
				a.credential = ((id (*)(id, SEL, id))objc_msgSend)([ACAccountCredential alloc], initPass, appPw);
			}
			set_acct_prop(a, @"CardDAVHostName", @"contacts.icloud.com");
			set_acct_prop(a, @"CardDAVPort", @443);
			set_acct_prop(a, @"CardDAVUseSSL", @YES);
			set_acct_prop(a, @"Hostname", @"contacts.icloud.com");
			set_acct_prop(a, @"PortNumber", @443);
			set_acct_prop(a, @"SSLEnabled", @YES);
			enable_acct_dataclass(a, @"com.apple.Dataclass.Contacts");
			enable_acct_dataclass(a, @"com.apple.Dataclass.ContactsSearch");
			NSString *errStr = nil;
			if (save_account_sync(store, a, &errStr)) [created addObject:@"CardDAV (Contacts)"];
			else if (errStr) [warnings addObject:[NSString stringWithFormat:@"CardDAV: %@", errStr]];
		}
	}

	/* 2. CalDAV (iCloud Calendar & Reminders) */
	if (wantCalendar) {
		ACAccountType *t = [store accountTypeWithAccountTypeIdentifier:@"com.apple.account.CalDAV"];
		if (t) {
			ACAccount *a = [[ACAccount alloc] initWithAccountType:t];
			a.username = email;
			a.accountDescription = [NSString stringWithFormat:@"iCloud Calendar (%@)", email];
			SEL initPass = sel_registerName("initWithPassword:");
			if ([ACAccountCredential instancesRespondToSelector:initPass]) {
				a.credential = ((id (*)(id, SEL, id))objc_msgSend)([ACAccountCredential alloc], initPass, appPw);
			}
			set_acct_prop(a, @"CalDAVHostName", @"caldav.icloud.com");
			set_acct_prop(a, @"CalDAVPort", @443);
			set_acct_prop(a, @"CalDAVUseSSL", @YES);
			set_acct_prop(a, @"Hostname", @"caldav.icloud.com");
			set_acct_prop(a, @"PortNumber", @443);
			set_acct_prop(a, @"SSLEnabled", @YES);
			enable_acct_dataclass(a, @"com.apple.Dataclass.Calendars");
			enable_acct_dataclass(a, @"com.apple.Dataclass.Reminders");
			NSString *errStr = nil;
			if (save_account_sync(store, a, &errStr)) [created addObject:@"CalDAV (Calendar & Reminders)"];
			else if (errStr) [warnings addObject:[NSString stringWithFormat:@"CalDAV: %@", errStr]];
		}
	}

	/* 3. IMAP + SMTP (iCloud Mail & IMAP Notes) */
	if (wantMail) {
		ACAccountType *t = [store accountTypeWithAccountTypeIdentifier:@"com.apple.account.IMAP"];
		if (t) {
			ACAccount *a = [[ACAccount alloc] initWithAccountType:t];
			a.username = email;
			a.accountDescription = [NSString stringWithFormat:@"iCloud Mail (%@)", email];
			SEL initPass = sel_registerName("initWithPassword:");
			if ([ACAccountCredential instancesRespondToSelector:initPass]) {
				a.credential = ((id (*)(id, SEL, id))objc_msgSend)([ACAccountCredential alloc], initPass, appPw);
			}
			set_acct_prop(a, @"Hostname", @"imap.mail.me.com");
			set_acct_prop(a, @"PortNumber", @993);
			set_acct_prop(a, @"SSLEnabled", @YES);
			set_acct_prop(a, @"EmailAddresses", @[email]);
			set_acct_prop(a, @"FullUserName", email);
			set_acct_prop(a, @"SMTPHostname", @"smtp.mail.me.com");
			set_acct_prop(a, @"SMTPPortNumber", @587);
			set_acct_prop(a, @"SMTPSSLEnabled", @YES);
			enable_acct_dataclass(a, @"com.apple.Dataclass.Mail");
			enable_acct_dataclass(a, @"com.apple.Dataclass.Notes");
			NSString *errStr = nil;
			if (save_account_sync(store, a, &errStr)) [created addObject:@"IMAP (Mail & Notes)"];
			else if (errStr) [warnings addObject:[NSString stringWithFormat:@"IMAP: %@", errStr]];
		}
	}

	authd_log([NSString stringWithFormat:@"setup-icloud-dav provisioned=%@ warnings=%@", created, warnings]);
	if (created.count == 0) {
		NSString *w = warnings.count ? [warnings componentsJoinedByString:@"; "] : @"No matching ACAccountType identifiers in accountsd";
		return @{ @"ok": @NO, @"error": w };
	}
	return @{
		@"ok": @YES,
		@"verified_http": @(httpCode),
		@"provisioned": created,
		@"warnings": warnings,
	};
}

/* ---- Anisette v1 & v3 Client Bridge ---- */

static NSData *sync_http_request(NSURLRequest *req, NSHTTPURLResponse **outResp, NSError **outErr)
{
	NSURLSessionConfiguration *cfg = [NSURLSessionConfiguration ephemeralSessionConfiguration];
	cfg.timeoutIntervalForRequest = 10.0;
	NSURLSession *sess = [NSURLSession sessionWithConfiguration:cfg];

	__block NSData *retData = nil;
	__block NSHTTPURLResponse *retResp = nil;
	__block NSError *retErr = nil;
	dispatch_semaphore_t sem = dispatch_semaphore_create(0);
	[[sess dataTaskWithRequest:req completionHandler:^(NSData *data, NSURLResponse *resp, NSError *err) {
		retData = data;
		if ([resp isKindOfClass:[NSHTTPURLResponse class]]) retResp = (NSHTTPURLResponse *)resp;
		retErr = err;
		dispatch_semaphore_signal(sem);
	}] resume];
	(void)dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(11.0 * NSEC_PER_SEC)));
	[sess finishTasksAndInvalidate];
	if (outResp) *outResp = retResp;
	if (outErr) *outErr = retErr;
	return retData;
}

static NSString *iso8601_now(void)
{
	NSISO8601DateFormatter *fmt = [[NSISO8601DateFormatter alloc] init];
	return [fmt stringFromDate:[NSDate date]];
}

static NSDictionary *fetch_anisette_v1(NSString *baseURL)
{
	NSURL *u = [NSURL URLWithString:baseURL];
	if (!u) return @{ @"ok": @NO, @"error": @"Invalid Anisette v1 URL" };
	NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:u];
	req.HTTPMethod = @"GET";
	NSError *err = nil;
	NSData *d = sync_http_request(req, NULL, &err);
	if (!d) {
		return @{ @"ok": @NO, @"error": [NSString stringWithFormat:@"Anisette v1 request failed: %@", err.localizedDescription ?: @"timeout"] };
	}
	id obj = [NSJSONSerialization JSONObjectWithData:d options:0 error:nil];
	if (![obj isKindOfClass:[NSDictionary class]]) {
		return @{ @"ok": @NO, @"error": @"Anisette v1 endpoint did not return JSON" };
	}
	NSDictionary *j = (NSDictionary *)obj;
	NSString *otp = [j[@"X-Apple-I-MD"] isKindOfClass:[NSString class]] ? j[@"X-Apple-I-MD"] : @"";
	NSString *mid = [j[@"X-Apple-I-MD-M"] isKindOfClass:[NSString class]] ? j[@"X-Apple-I-MD-M"] : @"";
	id rRaw = j[@"X-Apple-I-MD-RINFO"];
	unsigned long long rinfo = [rRaw respondsToSelector:@selector(unsignedLongLongValue)] ? [rRaw unsignedLongLongValue] : 17106176ULL;
	if (otp.length == 0 || mid.length == 0) {
		return @{ @"ok": @NO, @"error": @"Anisette v1 response missing X-Apple-I-MD or X-Apple-I-MD-M" };
	}
	return @{
		@"ok": @YES,
		@"machineID": mid,
		@"oneTimePassword": otp,
		@"routingInfo": @(rinfo),
	};
}

static NSDictionary *gsa_plist_post(NSString *urlStr, NSDictionary *bodyDict, NSString *clientInfo, NSString *userAgent, NSDictionary *adiState)
{
	NSURL *u = [NSURL URLWithString:urlStr];
	if (!u) return nil;
	NSDictionary *payload = @{ @"Header": @{}, @"Request": bodyDict ?: @{} };
	NSData *xml = [NSPropertyListSerialization dataWithPropertyList:payload format:NSPropertyListXMLFormat_v1_0 options:0 error:nil];
	NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:u];
	req.HTTPMethod = @"POST";
	req.HTTPBody = xml;
	[req setValue:@"text/x-xml-plist" forHTTPHeaderField:@"Content-Type"];
	if (clientInfo.length) [req setValue:clientInfo forHTTPHeaderField:@"X-Mme-Client-Info"];
	if (userAgent.length) [req setValue:userAgent forHTTPHeaderField:@"User-Agent"];
	[req setValue:(adiState[@"deviceUUID"] ?: @"") forHTTPHeaderField:@"X-Mme-Device-Id"];
	[req setValue:(adiState[@"localUserUUID"] ?: @"") forHTTPHeaderField:@"X-Apple-I-MD-LU"];
	[req setValue:iso8601_now() forHTTPHeaderField:@"X-Apple-I-Client-Time"];
	[req setValue:[NSTimeZone localTimeZone].abbreviation ?: @"UTC" forHTTPHeaderField:@"X-Apple-I-TimeZone"];
	[req setValue:@"en_US" forHTTPHeaderField:@"X-Apple-Locale"];

	NSData *respData = sync_http_request(req, NULL, NULL);
	if (!respData) return nil;
	id plist = [NSPropertyListSerialization propertyListWithData:respData options:0 format:NULL error:nil];
	if ([plist isKindOfClass:[NSDictionary class]]) {
		NSDictionary *r = ((NSDictionary *)plist)[@"Response"];
		return [r isKindOfClass:[NSDictionary class]] ? r : (NSDictionary *)plist;
	}
	return nil;
}

static BOOL provision_anisette_v3(NSString *base, NSMutableDictionary *adiState, NSString *clientInfo, NSString *userAgent, NSString **outErr)
{
	/* 1. Fetch GrandSlam URL bag */
	NSMutableURLRequest *bagReq = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:@"https://gsa.apple.com/grandslam/GsService2/lookup"]];
	if (clientInfo.length) [bagReq setValue:clientInfo forHTTPHeaderField:@"X-Mme-Client-Info"];
	if (userAgent.length) [bagReq setValue:userAgent forHTTPHeaderField:@"User-Agent"];
	NSData *bagData = sync_http_request(bagReq, NULL, NULL);
	NSDictionary *bag = bagData ? [NSPropertyListSerialization propertyListWithData:bagData options:0 format:NULL error:nil] : nil;
	NSDictionary *urls = [bag isKindOfClass:[NSDictionary class]] ? bag[@"urls"] : nil;
	NSString *startURL = [urls isKindOfClass:[NSDictionary class]] ? urls[@"midStartProvisioning"] : nil;
	NSString *finishURL = [urls isKindOfClass:[NSDictionary class]] ? urls[@"midFinishProvisioning"] : nil;
	if (!startURL.length || !finishURL.length) {
		if (outErr) *outErr = @"Could not read midStartProvisioning / midFinishProvisioning from gsa.apple.com lookup";
		return NO;
	}

	NSString *wsStr = base;
	if ([wsStr hasPrefix:@"https://"]) wsStr = [@"wss://" stringByAppendingString:[wsStr substringFromIndex:8]];
	else if ([wsStr hasPrefix:@"http://"]) wsStr = [@"ws://" stringByAppendingString:[wsStr substringFromIndex:7]];
	if ([wsStr hasSuffix:@"/"]) wsStr = [wsStr substringToIndex:wsStr.length - 1];
	wsStr = [wsStr stringByAppendingString:@"/v3/provisioning_session"];

	NSURL *wsURL = [NSURL URLWithString:wsStr];
	if (!wsURL) {
		if (outErr) *outErr = @"Invalid Anisette v3 WebSocket URL";
		return NO;
	}

	NSURLSession *sess = [NSURLSession sessionWithConfiguration:[NSURLSessionConfiguration ephemeralSessionConfiguration]];
	NSURLSessionWebSocketTask *ws = [sess webSocketTaskWithURL:wsURL];
	[ws resume];

	BOOL done = NO;
	BOOL ok = NO;
	NSString *errMsg = @"Provisioning session closed unexpectedly";

	for (int step = 0; step < 10 && !done; step++) {
		__block NSURLSessionWebSocketMessage *rxMsg = nil;
		__block NSError *rxErr = nil;
		dispatch_semaphore_t sem = dispatch_semaphore_create(0);
		[ws receiveMessageWithCompletionHandler:^(NSURLSessionWebSocketMessage *message, NSError *error) {
			rxMsg = message;
			rxErr = error;
			dispatch_semaphore_signal(sem);
		}];
		if (dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(10.0 * NSEC_PER_SEC))) != 0 || rxErr || !rxMsg) {
			errMsg = rxErr.localizedDescription ?: @"WebSocket timeout talking to Anisette v3 server";
			break;
		}
		NSData *mData = rxMsg.type == NSURLSessionWebSocketMessageTypeString
			? [rxMsg.string dataUsingEncoding:NSUTF8StringEncoding]
			: rxMsg.data;
		NSDictionary *j = mData ? [NSJSONSerialization JSONObjectWithData:mData options:0 error:nil] : nil;
		if (![j isKindOfClass:[NSDictionary class]]) break;
		NSString *res = j[@"result"];
		NSDictionary *txDict = nil;

		if ([res isEqualToString:@"GiveIdentifier"]) {
			txDict = @{ @"identifier": adiState[@"identifier"] ?: @"" };
		} else if ([res isEqualToString:@"GiveStartProvisioningData"]) {
			NSDictionary *r1 = gsa_plist_post(startURL, @{}, clientInfo, userAgent, adiState);
			NSString *spim = [r1[@"spim"] isKindOfClass:[NSString class]] ? r1[@"spim"] : nil;
			if (!spim.length) {
				errMsg = @"gsa.apple.com midStartProvisioning did not return spim";
				break;
			}
			txDict = @{ @"spim": spim };
		} else if ([res isEqualToString:@"GiveEndProvisioningData"]) {
			NSString *cpim = [j[@"cpim"] isKindOfClass:[NSString class]] ? j[@"cpim"] : @"";
			NSDictionary *r2 = gsa_plist_post(finishURL, @{ @"cpim": cpim }, clientInfo, userAgent, adiState);
			NSString *ptm = [r2[@"ptm"] isKindOfClass:[NSString class]] ? r2[@"ptm"] : nil;
			NSString *tk  = [r2[@"tk"] isKindOfClass:[NSString class]] ? r2[@"tk"] : nil;
			id rinfoObj = r2[@"X-Apple-I-MD-RINFO"];
			if (!ptm.length || !tk.length) {
				errMsg = @"gsa.apple.com midFinishProvisioning did not return ptm/tk";
				break;
			}
			if (rinfoObj) {
				adiState[@"routingInfo"] = @([rinfoObj respondsToSelector:@selector(unsignedLongLongValue)]
					? [rinfoObj unsignedLongLongValue] : 17106176ULL);
			}
			txDict = @{ @"ptm": ptm, @"tk": tk };
		} else if ([res isEqualToString:@"ProvisioningSuccess"]) {
			NSString *adiPb = [j[@"adi_pb"] isKindOfClass:[NSString class]] ? j[@"adi_pb"] : @"";
			if (adiPb.length > 0) {
				adiState[@"adi_pb"] = adiPb;
				ok = YES;
			}
			done = YES;
			break;
		} else {
			errMsg = [NSString stringWithFormat:@"Anisette v3 step returned %@", res ?: @"error"];
			break;
		}

		if (txDict) {
			NSData *txData = [NSJSONSerialization dataWithJSONObject:txDict options:0 error:nil];
			NSString *txStr = [[NSString alloc] initWithData:txData encoding:NSUTF8StringEncoding];
			dispatch_semaphore_t ssem = dispatch_semaphore_create(0);
			__block NSError *txErr = nil;
			[ws sendMessage:[[NSURLSessionWebSocketMessage alloc] initWithString:txStr] completionHandler:^(NSError *error) {
				txErr = error;
				dispatch_semaphore_signal(ssem);
			}];
			(void)dispatch_semaphore_wait(ssem, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(8.0 * NSEC_PER_SEC)));
			if (txErr) {
				errMsg = txErr.localizedDescription;
				break;
			}
		}
	}

	[ws cancelWithCloseCode:NSURLSessionWebSocketCloseCodeNormalClosure reason:nil];
	[sess finishTasksAndInvalidate];
	if (!ok && outErr) *outErr = errMsg;
	return ok;
}

static NSDictionary *fetch_anisette_v3(NSString *baseURL, NSString *op)
{
	NSString *base = baseURL;
	while ([base hasSuffix:@"/"]) base = [base substringToIndex:base.length - 1];

	/* 1. Client info */
	NSURL *ciURL = [NSURL URLWithString:[base stringByAppendingString:@"/v3/client_info"]];
	if (!ciURL) return @{ @"ok": @NO, @"error": @"Invalid Anisette v3 URL" };
	NSData *ciData = sync_http_request([NSURLRequest requestWithURL:ciURL], NULL, NULL);
	NSDictionary *ci = ciData ? [NSJSONSerialization JSONObjectWithData:ciData options:0 error:nil] : nil;
	NSString *clientInfo = [ci isKindOfClass:[NSDictionary class]] ? (ci[@"client_info"] ?: @"") : @"";
	NSString *userAgent  = [ci isKindOfClass:[NSDictionary class]] ? (ci[@"user_agent"] ?: @"akd/1.0 CFNetwork/1496.0.7 Darwin/25.5.0") : @"akd/1.0 CFNetwork/1496.0.7 Darwin/25.5.0";

	[gStateLock lock];
	NSMutableDictionary *adiState = load_adi_state_locked();
	if ([op isEqualToString:@"erase"]) {
		[adiState removeObjectForKey:@"adi_pb"];
		save_adi_state_locked(adiState);
		[gStateLock unlock];
		return @{ @"ok": @YES };
	}
	BOOL hasAdi = ([adiState[@"adi_pb"] isKindOfClass:[NSString class]] && [(NSString *)adiState[@"adi_pb"] length] > 16);
	if (!hasAdi || [op isEqualToString:@"provision"]) {
		NSString *provErr = nil;
		BOOL pOK = provision_anisette_v3(base, adiState, clientInfo, userAgent, &provErr);
		if (!pOK) {
			[gStateLock unlock];
			return @{ @"ok": @NO, @"error": provErr ?: @"Anisette v3 provisioning failed" };
		}
		save_adi_state_locked(adiState);
		if ([op isEqualToString:@"provision"]) {
			[gStateLock unlock];
			return @{ @"ok": @YES };
		}
	}
	NSString *ident = adiState[@"identifier"] ?: @"";
	NSString *adiPb = adiState[@"adi_pb"] ?: @"";
	unsigned long long savedRinfo = [adiState[@"routingInfo"] respondsToSelector:@selector(unsignedLongLongValue)]
		? [adiState[@"routingInfo"] unsignedLongLongValue] : 17106176ULL;
	[gStateLock unlock];

	NSURL *ghURL = [NSURL URLWithString:[base stringByAppendingString:@"/v3/get_headers"]];
	NSMutableURLRequest *ghReq = [NSMutableURLRequest requestWithURL:ghURL];
	ghReq.HTTPMethod = @"POST";
	[ghReq setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
	ghReq.HTTPBody = [NSJSONSerialization dataWithJSONObject:@{ @"identifier": ident, @"adi_pb": adiPb } options:0 error:nil];

	NSData *ghData = sync_http_request(ghReq, NULL, NULL);
	NSDictionary *gh = ghData ? [NSJSONSerialization JSONObjectWithData:ghData options:0 error:nil] : nil;
	if (![gh isKindOfClass:[NSDictionary class]]) {
		return @{ @"ok": @NO, @"error": @"Anisette v3 /v3/get_headers failed" };
	}
	NSDictionary *hdrs = [gh[@"headers"] isKindOfClass:[NSDictionary class]] ? gh[@"headers"] : gh;
	NSString *otp = [hdrs[@"X-Apple-I-MD"] isKindOfClass:[NSString class]] ? hdrs[@"X-Apple-I-MD"] : @"";
	NSString *mid = [hdrs[@"X-Apple-I-MD-M"] isKindOfClass:[NSString class]] ? hdrs[@"X-Apple-I-MD-M"] : @"";
	id rRaw = hdrs[@"X-Apple-I-MD-RINFO"];
	unsigned long long rinfo = [rRaw respondsToSelector:@selector(unsignedLongLongValue)] ? [rRaw unsignedLongLongValue] : savedRinfo;
	if (otp.length == 0 || mid.length == 0) {
		return @{ @"ok": @NO, @"error": [NSString stringWithFormat:@"Anisette v3 get_headers returned: %@", gh[@"result"] ?: @"missing headers"] };
	}
	return @{
		@"ok": @YES,
		@"machineID": mid,
		@"oneTimePassword": otp,
		@"routingInfo": @(rinfo),
	};
}

static NSString *handle_anisette_op(NSString *op)
{
	[gStateLock lock];
	NSDictionary *cfg = load_config_locked();
	[gStateLock unlock];

	BOOL enabled = [cfg[@"enabled"] boolValue];
	NSString *mode = [cfg[@"mode"] isKindOfClass:[NSString class]] ? cfg[@"mode"] : @"v3";
	NSString *url = [[cfg[@"url"] isKindOfClass:[NSString class]] ? cfg[@"url"] : @"" stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];

	if (!enabled || url.length == 0) {
		return @"{\"ok\":false,\"code\":-8004,\"error\":\"Anisette bridge is disabled. Open HotAuth -> Settings Bridge to configure a v1/v3 URL, or use HotAuth -> iCloud Sync (App Password).\"}";
	}
	if ([op isEqualToString:@"sync"]) {
		return @"{\"ok\":true}";
	}

	NSDictionary *res = [[mode lowercaseString] isEqualToString:@"v1"]
		? fetch_anisette_v1(url)
		: fetch_anisette_v3(url, op);

	if ([res[@"ok"] boolValue]) {
		NSString *mid = [res[@"machineID"] isKindOfClass:[NSString class]] ? res[@"machineID"] : @"";
		NSString *otp = [res[@"oneTimePassword"] isKindOfClass:[NSString class]] ? res[@"oneTimePassword"] : @"";
		unsigned long long rinfo = [res[@"routingInfo"] respondsToSelector:@selector(unsignedLongLongValue)]
			? [res[@"routingInfo"] unsignedLongLongValue] : 17106176ULL;
		return [NSString stringWithFormat:@"{\"ok\":true,\"machineID\":\"%@\",\"oneTimePassword\":\"%@\",\"routingInfo\":%llu}",
		    json_escape(mid), json_escape(otp), rinfo];
	}
	NSString *err = [res[@"error"] isKindOfClass:[NSString class]] ? res[@"error"] : @"Anisette request failed";
	return [NSString stringWithFormat:@"{\"ok\":false,\"code\":-8004,\"error\":\"%@\"}", json_escape(err)];
}

/* ---- Minimal Loopback HTTP Server (127.0.0.1:29171) ---- */

static void send_http_json(int fd, int status, NSString *jsonBody)
{
	NSData *body = [jsonBody dataUsingEncoding:NSUTF8StringEncoding] ?: [NSData data];
	const char *statusText = (status == 200) ? "OK" : "Bad Request";
	char hdr[256];
	int hn = snprintf(hdr, sizeof(hdr),
	    "HTTP/1.1 %d %s\r\n"
	    "Content-Type: application/json; charset=utf-8\r\n"
	    "Content-Length: %zu\r\n"
	    "Connection: close\r\n\r\n",
	    status, statusText, (size_t)body.length);
	if (hn > 0) (void)send(fd, hdr, (size_t)hn, 0);
	if (body.length > 0) (void)send(fd, body.bytes, body.length, 0);
}

static void send_http_dict(int fd, int status, NSDictionary *dict)
{
	NSData *d = [NSJSONSerialization dataWithJSONObject:dict options:0 error:nil];
	NSString *s = d ? [[NSString alloc] initWithData:d encoding:NSUTF8StringEncoding] : @"{\"ok\":false}";
	send_http_json(fd, status, s);
}

static void handle_client(int fd)
{
	struct timeval tv = { .tv_sec = 10, .tv_usec = 0 };
	(void)setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
	(void)setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

	NSMutableData *buf = [NSMutableData data];
	uint8_t tmp[4096];
	NSInteger hdrEnd = NSNotFound;
	NSInteger contentLen = 0;

	while (buf.length < 65536) {
		ssize_t r = recv(fd, tmp, sizeof(tmp), 0);
		if (r <= 0) break;
		[buf appendBytes:tmp length:(NSUInteger)r];
		if (hdrEnd == NSNotFound) {
			const uint8_t *b = buf.bytes;
			for (NSUInteger i = 0; i + 3 < buf.length; i++) {
				if (b[i] == '\r' && b[i+1] == '\n' && b[i+2] == '\r' && b[i+3] == '\n') {
					hdrEnd = (NSInteger)(i + 4);
					NSString *hdrStr = [[NSString alloc] initWithBytes:b length:i encoding:NSISOLatin1StringEncoding];
					for (NSString *line in [hdrStr componentsSeparatedByString:@"\r\n"]) {
						if ([line.lowercaseString hasPrefix:@"content-length:"]) {
							contentLen = [[line substringFromIndex:15] integerValue];
						}
					}
					break;
				}
			}
		}
		if (hdrEnd != NSNotFound && (NSInteger)buf.length >= hdrEnd + contentLen) {
			break;
		}
	}
	if (hdrEnd == NSNotFound) {
		close(fd);
		return;
	}

	NSString *hdrStr = [[NSString alloc] initWithBytes:buf.bytes length:(NSUInteger)(hdrEnd - 4) encoding:NSUTF8StringEncoding];
	NSString *firstLine = [[hdrStr componentsSeparatedByString:@"\r\n"] firstObject] ?: @"";
	NSArray *parts = [firstLine componentsSeparatedByString:@" "];
	NSString *method = parts.count > 0 ? parts[0] : @"GET";
	NSString *fullPath = parts.count > 1 ? parts[1] : @"/";

	NSData *bodyData = [NSData data];
	if ((NSInteger)buf.length > hdrEnd) {
		NSUInteger avail = buf.length - (NSUInteger)hdrEnd;
		NSUInteger take = contentLen > 0 ? MIN(avail, (NSUInteger)contentLen) : avail;
		bodyData = [buf subdataWithRange:NSMakeRange((NSUInteger)hdrEnd, take)];
	}
	NSDictionary *jsonIn = bodyData.length > 0 ? [NSJSONSerialization JSONObjectWithData:bodyData options:0 error:nil] : nil;
	if (![jsonIn isKindOfClass:[NSDictionary class]]) jsonIn = @{};

	NSURLComponents *uc = [NSURLComponents componentsWithString:fullPath];
	NSString *path = uc.path ?: fullPath;

	if ([method isEqualToString:@"GET"] && [path isEqualToString:@"/v1/selfcheck"]) {
		send_http_dict(fd, 200, run_selfcheck());
	} else if ([method isEqualToString:@"POST"] && [path isEqualToString:@"/v1/setup-icloud-dav"]) {
		send_http_dict(fd, 200, setup_icloud_dav(jsonIn));
	} else if ([method isEqualToString:@"GET"] && [path isEqualToString:@"/v1/config"]) {
		[gStateLock lock];
		NSDictionary *cfg = load_config_locked();
		NSDictionary *adi = load_adi_state_locked();
		[gStateLock unlock];
		BOOL prov = ([adi[@"adi_pb"] isKindOfClass:[NSString class]] && [(NSString *)adi[@"adi_pb"] length] > 16);
		send_http_dict(fd, 200, @{
			@"ok": @YES,
			@"enabled": @([cfg[@"enabled"] boolValue]),
			@"mode": [cfg[@"mode"] isKindOfClass:[NSString class]] ? cfg[@"mode"] : @"v3",
			@"url": [cfg[@"url"] isKindOfClass:[NSString class]] ? cfg[@"url"] : @"",
			@"provisioned": @(prov),
		});
	} else if ([method isEqualToString:@"POST"] && [path isEqualToString:@"/v1/config"]) {
		BOOL enabled = [jsonIn[@"enabled"] boolValue];
		NSString *mode = [[jsonIn[@"mode"] isKindOfClass:[NSString class]] ? jsonIn[@"mode"] : @"v3" lowercaseString];
		if (![mode isEqualToString:@"v1"] && ![mode isEqualToString:@"v3"]) mode = @"v3";
		NSString *url = [[jsonIn[@"url"] isKindOfClass:[NSString class]] ? jsonIn[@"url"] : @"" stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
		BOOL eraseAdi = [jsonIn[@"eraseAdi"] boolValue];

		[gStateLock lock];
		BOOL ok = save_config_locked(@{ @"enabled": @(enabled), @"mode": mode, @"url": url });
		if (eraseAdi) {
			NSMutableDictionary *adi = load_adi_state_locked();
			[adi removeObjectForKey:@"adi_pb"];
			save_adi_state_locked(adi);
		}
		[gStateLock unlock];
		authd_log([NSString stringWithFormat:@"updated config enabled=%d mode=%@ url_len=%lu erase=%d",
		    enabled, mode, (unsigned long)url.length, eraseAdi]);
		send_http_dict(fd, 200, @{ @"ok": @(ok) });
	} else if ([path isEqualToString:@"/v1/anisette"]) {
		NSString *op = @"fetch";
		for (NSURLQueryItem *qi in uc.queryItems) {
			if ([qi.name isEqualToString:@"op"] && qi.value.length > 0) {
				op = qi.value;
			}
		}
		NSString *raw = handle_anisette_op(op);
		send_http_json(fd, 200, raw);
	} else if ([method isEqualToString:@"GET"] && [path isEqualToString:@"/v1/logs"]) {
		NSString *logText = [NSString stringWithContentsOfFile:kAuthLogPath encoding:NSUTF8StringEncoding error:nil] ?: @"";
		send_http_dict(fd, 200, @{ @"ok": @YES, @"log": logText });
	} else if ([method isEqualToString:@"POST"] && [path isEqualToString:@"/v1/clear-logs"]) {
		[@"" writeToFile:kAuthLogPath atomically:YES encoding:NSUTF8StringEncoding error:nil];
		send_http_dict(fd, 200, @{ @"ok": @YES });
	} else {
		send_http_dict(fd, 404, @{ @"ok": @NO, @"error": @"Not found" });
	}

	close(fd);
}

int main(int argc, char *argv[])
{
	(void)argc; (void)argv;
	signal(SIGPIPE, SIG_IGN);
	@autoreleasepool {
		gStateLock = [[NSLock alloc] init];
		ensure_auth_dir();
		ensure_keychain_dirs();

		int srv = socket(AF_INET, SOCK_STREAM, 0);
		if (srv < 0) {
			authd_log(@"FATAL: socket() failed");
			return 1;
		}
		int yes = 1;
		(void)setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));

		struct sockaddr_in addr;
		memset(&addr, 0, sizeof(addr));
		addr.sin_family = AF_INET;
		addr.sin_port = htons(29171);
		addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

		if (bind(srv, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
			authd_log([NSString stringWithFormat:@"FATAL: bind(127.0.0.1:29171) failed errno=%d", errno]);
			return 1;
		}
		if (listen(srv, 16) != 0) {
			authd_log(@"FATAL: listen() failed");
			return 1;
		}
		authd_log(@"listening on http://127.0.0.1:29171");

		for (;;) {
			int cfd = accept(srv, NULL, NULL);
			if (cfd < 0) {
				if (errno == EINTR) continue;
				usleep(100000);
				continue;
			}
			dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
				@autoreleasepool {
					handle_client(cfd);
				}
			});
		}
	}
	return 0;
}
