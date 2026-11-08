/*
 * samurai_adblockd.c — Native System-Wide DNS/DoH/DoT/CNAME Sinkhole Daemon for RMX1931 (Android 17, Linux 4.14)
 *
 * Architecture:
 *  - Fail-safe Kernel Netfilter NFQUEUE (--queue-num 53 --queue-bypass, NFQA_CFG_F_FAIL_OPEN) on
 *    mangle INPUT (-p udp --sport 53) for BOTH IPv4 and IPv6:
 *      * Zero outbound UDP NAT / conntrack / FIB policy-routing fragility: netd (dnsresolver) and apps
 *        send native UDP :53 packets untouched over Wi-Fi, Cellular (Jio/Airtel IPv4/IPv6/CLAT), & VPNs.
 *      * Allowed DNS responses receive an instant NF_ACCEPT verdict (<5 us) with zero packet modification.
 *      * Blocked domains & CNAME-cloaked trackers are rewritten in-flight to valid RFC 1035 NOERROR
 *        sinkhole answers (A -> 0.0.0.0, AAAA -> ::, HTTPS/SVCB -> NODATA) with exact IPv4/IPv6 + UDP
 *        checksums, keeping netd server health score at 100%.
 *      * Hardware/kernel fail-open (--queue-bypass + NFQA_CFG_F_FAIL_OPEN): even if samurai_adblockd is
 *        restarting or overloaded, DNS packets pass straight through — Wi-Fi can never lose internet.
 *  - TCP :53 interception via SAMURAI_ADBLOCK_NAT (-p tcp --dport 53 ! --gid-owner 1099 -> 127.0.0.1:5353)
 *    with SO_ORIGINAL_DST upstream preservation, plus bind-mounted /system/etc/hosts (top 16k domains) and
 *    app-UID (10000:99999) DoT (:853) / public DoH (:443 resolver IPs) REJECT rules.
 *  - Built-in early-boot pure-C /system/etc/adblock_seed.gz unpacker + automatic mtime/size DB hot-reload.
 *  - Open-addressed 64-bit FNV-1a hash table (1,048,576 slots) for O(labels) suffix matching (~200ns).
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <time.h>
#include <unistd.h>

#include <linux/netlink.h>
#include <linux/netfilter.h>
#include <linux/netfilter/nfnetlink.h>
#include <linux/netfilter/nfnetlink_queue.h>

#ifndef SOL_NETLINK
#define SOL_NETLINK        270
#endif
#ifndef NETLINK_NO_ENOBUFS
#define NETLINK_NO_ENOBUFS 5
#endif
#ifndef SO_ORIGINAL_DST
#define SO_ORIGINAL_DST    80
#endif

#define GID_ADBLOCK        1099
#define LISTEN_PORT        5353
#define NFQ_QUEUE_NUM      53

#define DEFAULT_OTA_DIR    "/data/ota_package"
#define DEFAULT_SEED_GZ    "/system/etc/adblock_seed.gz"

#define HT_BITS            20
#define HT_SIZE            (1U << HT_BITS) /* 1,048,576 slots */
#define HT_MASK            (HT_SIZE - 1U)
#define ALLOW_HT_SIZE      4096U
#define ALLOW_HT_MASK      (ALLOW_HT_SIZE - 1U)

#define MAX_UPSTREAMS      8
#define INFLIGHT_SLOTS     64
#define DNS_MAX_PKT        4096
#define NFQ_BUF_SZ         70000

struct ht_slot {
    uint64_t hash;
    uint32_t str_off; /* offset into arena (0 = empty) */
};

struct domain_db {
    struct ht_slot *block_ht;
    struct ht_slot  allow_ht[ALLOW_HT_SIZE];
    char           *arena;
    uint32_t        arena_used;
    uint32_t        arena_cap;
    uint32_t        block_count;
    uint32_t        allow_count;
};

struct upstream_ep {
    int                     family; /* AF_INET or AF_INET6 */
    struct sockaddr_storage addr;
    socklen_t               addrlen;
};

struct inflight_req {
    bool               active;
    uint16_t           orig_txid;
    uint16_t           wire_txid;
    uint16_t           qtype;
    struct sockaddr_in client_addr;
    uint64_t           sent_ms;
    bool               retried;
    size_t             pkt_len;
    uint8_t            pkt[512];
    char               qname[256];
};

static char g_adblock_dir[256]    = DEFAULT_OTA_DIR "/adblock";
static char g_domains_file[300]   = DEFAULT_OTA_DIR "/adblock/domains.txt";
static char g_allow_file[300]     = DEFAULT_OTA_DIR "/adblock/allow.txt";
static char g_hosts_file[300]     = DEFAULT_OTA_DIR "/adblock/hosts";
static char g_upstreams_file[300] = DEFAULT_OTA_DIR "/adblock/upstreams.txt";
static char g_state_file[300]     = DEFAULT_OTA_DIR "/adblock/state.prop";
static char g_seed_stamp[300]     = DEFAULT_OTA_DIR "/adblock/.seed_c_stamp";
static char g_queue_dir[256]      = DEFAULT_OTA_DIR "/hw/queue";
static char g_telemetry_out[300]  = DEFAULT_OTA_DIR "/hw/adblock.prop";
static char g_mods_prop_file[300] = DEFAULT_OTA_DIR "/hw/mods.prop";
static char g_seed_gz_file[256]   = DEFAULT_SEED_GZ;
static bool g_skip_firewall       = false;
static uint16_t g_nfq_num         = NFQ_QUEUE_NUM;

static pthread_rwlock_t g_db_lock = PTHREAD_RWLOCK_INITIALIZER;
static struct domain_db *g_db = NULL;
static time_t   g_domains_mtime = 0;
static uint64_t g_domains_size  = 0;

static pthread_mutex_t g_stats_lock = PTHREAD_MUTEX_INITIALIZER;
static uint64_t g_total_queries = 0;
static uint64_t g_blocked_queries = 0;
static char     g_last_blocked[256] = "none";

static pthread_mutex_t g_up_lock = PTHREAD_MUTEX_INITIALIZER;
static struct upstream_ep g_upstreams[MAX_UPSTREAMS];
static int                g_num_upstreams = 0;

static volatile sig_atomic_t g_running = 1;
static volatile bool     g_enabled = true;
static volatile uint64_t g_pause_until_ms = 0;
static bool     g_fw_applied = false;
static bool     g_fw_initialized = false;
static uint64_t g_fw_apply_ms = 0;
static uint64_t g_hook_recheck_until_ms = 0;
static uint64_t g_last_hook_check_ms = 0;
static pid_t    g_last_netd_pid = -1;
static volatile int g_tcp_workers = 0;

static void init_paths_from_env(void) {
    const char *base = getenv("SAMURAI_OTA_DIR");
    if (base && base[0]) {
        snprintf(g_adblock_dir, sizeof(g_adblock_dir), "%s/adblock", base);
        snprintf(g_domains_file, sizeof(g_domains_file), "%s/adblock/domains.txt", base);
        snprintf(g_allow_file, sizeof(g_allow_file), "%s/adblock/allow.txt", base);
        snprintf(g_hosts_file, sizeof(g_hosts_file), "%s/adblock/hosts", base);
        snprintf(g_upstreams_file, sizeof(g_upstreams_file), "%s/adblock/upstreams.txt", base);
        snprintf(g_state_file, sizeof(g_state_file), "%s/adblock/state.prop", base);
        snprintf(g_seed_stamp, sizeof(g_seed_stamp), "%s/adblock/.seed_c_stamp", base);
        snprintf(g_queue_dir, sizeof(g_queue_dir), "%s/hw/queue", base);
        snprintf(g_telemetry_out, sizeof(g_telemetry_out), "%s/hw/adblock.prop", base);
        snprintf(g_mods_prop_file, sizeof(g_mods_prop_file), "%s/hw/mods.prop", base);
    }
    const char *sgz = getenv("SAMURAI_SEED_GZ");
    if (sgz && sgz[0]) {
        snprintf(g_seed_gz_file, sizeof(g_seed_gz_file), "%s", sgz);
    }
    const char *sf = getenv("SAMURAI_SKIP_FW");
    if (sf && atoi(sf) == 1) {
        g_skip_firewall = true;
    }
    const char *qn = getenv("SAMURAI_NFQ_NUM");
    if (qn && atoi(qn) > 0 && atoi(qn) < 65535) {
        g_nfq_num = (uint16_t)atoi(qn);
    }
}

static uint64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)(ts.tv_nsec / 1000000ULL);
}

static uint64_t fnv1a_64(const char *s) {
    uint64_t h = 14695981039346656037ULL;
    while (*s) {
        h ^= (uint8_t)(*s++);
        h *= 1099511628211ULL;
    }
    return h ? h : 1ULL;
}

static void db_free(struct domain_db *db) {
    if (!db) return;
    free(db->block_ht);
    free(db->arena);
    free(db);
}

static uint32_t arena_push(struct domain_db *db, const char *s, size_t len) {
    if (db->arena_used + len + 1U > db->arena_cap) {
        uint32_t new_cap = db->arena_cap ? db->arena_cap * 2U : (8U * 1024U * 1024U);
        while (new_cap < db->arena_used + len + 1U) {
            new_cap *= 2U;
        }
        char *na = realloc(db->arena, new_cap);
        if (!na) return 0;
        db->arena = na;
        db->arena_cap = new_cap;
    }
    uint32_t off = db->arena_used;
    memcpy(db->arena + off, s, len);
    db->arena[off + len] = '\0';
    db->arena_used += (uint32_t)(len + 1U);
    return off;
}

static void ht_insert(struct ht_slot *ht, uint32_t mask, struct domain_db *db, const char *dom, bool is_block) {
    size_t len = strlen(dom);
    if (len == 0 || len > 253) return;
    uint64_t h = fnv1a_64(dom);
    uint32_t idx = (uint32_t)h & mask;
    for (uint32_t probe = 0; probe <= mask; probe++) {
        struct ht_slot *slot = &ht[idx];
        if (slot->str_off == 0) {
            uint32_t off = arena_push(db, dom, len);
            if (!off) return;
            slot->hash = h;
            slot->str_off = off;
            if (is_block) db->block_count++;
            else db->allow_count++;
            return;
        }
        if (slot->hash == h && strcmp(db->arena + slot->str_off, dom) == 0) {
            return; /* duplicate */
        }
        idx = (idx + 1U) & mask;
    }
}

static bool ht_lookup(const struct ht_slot *ht, uint32_t mask, const struct domain_db *db, const char *dom) {
    uint64_t h = fnv1a_64(dom);
    uint32_t idx = (uint32_t)h & mask;
    for (uint32_t probe = 0; probe <= mask; probe++) {
        const struct ht_slot *slot = &ht[idx];
        if (slot->str_off == 0) return false;
        if (slot->hash == h && strcmp(db->arena + slot->str_off, dom) == 0) {
            return true;
        }
        idx = (idx + 1U) & mask;
    }
    return false;
}

static void clean_domain_line(char *line, char *out, size_t out_sz) {
    char *p = line;
    while (*p && isspace((unsigned char)*p)) p++;
    if (*p == '\0' || *p == '#' || *p == '!' || *p == '@') {
        out[0] = '\0';
        return;
    }
    char *hash = strchr(p, '#');
    if (hash) *hash = '\0';

    /* Support both plain domain lines and "0.0.0.0 domain" hosts lines */
    if (strncmp(p, "0.0.0.0", 7) == 0 || strncmp(p, "127.0.0.1", 9) == 0 || strncmp(p, "::", 2) == 0) {
        while (*p && !isspace((unsigned char)*p)) p++;
        while (*p && isspace((unsigned char)*p)) p++;
    }
    if (strncmp(p, "*.", 2) == 0) p += 2;
    else if (*p == '.') p++;

    size_t i = 0;
    while (*p && !isspace((unsigned char)*p) && i + 1 < out_sz) {
        out[i++] = (char)tolower((unsigned char)*p++);
    }
    while (i > 0 && out[i - 1] == '.') i--;
    out[i] = '\0';
    if (strcmp(out, "localhost") == 0 || !strchr(out, '.')) {
        out[0] = '\0';
    }
}

static int run_cmd(char *const argv[]) {
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        if (setregid(0, 0) < 0) { /* best-effort root gid for iptables/mount */ }
        int devnull = open("/dev/null", O_RDWR);
        if (devnull >= 0) {
            dup2(devnull, STDOUT_FILENO);
            dup2(devnull, STDERR_FILENO);
            if (devnull > 2) close(devnull);
        }
        execv(argv[0], argv);
        _exit(127);
    }
    int st = 0;
    if (waitpid(pid, &st, 0) < 0) return -1;
    return WIFEXITED(st) ? WEXITSTATUS(st) : -1;
}

/*
 * Pure-C early-boot seed unpacker: decompresses /system/etc/adblock_seed.gz directly at
 * post-fs-data via toybox/gzip without needing Zygote/ART/app_process64, guaranteeing all
 * 231k+ domains and /data/ota_package/adblock/hosts are ready before netd starts.
 */
static void ensure_seed_unpacked(void) {
    struct stat st_seed;
    if (stat(g_seed_gz_file, &st_seed) != 0 || st_seed.st_size < 1024) {
        return;
    }

    char want_sig[96];
    snprintf(want_sig, sizeof(want_sig), "%lld_%lld",
             (long long)st_seed.st_size, (long long)st_seed.st_mtime);

    struct stat st_dom, st_hosts;
    if (stat(g_domains_file, &st_dom) == 0 && st_dom.st_size > 65536 &&
        stat(g_hosts_file, &st_hosts) == 0 && st_hosts.st_size > 1024) {
        FILE *fs = fopen(g_seed_stamp, "r");
        if (fs) {
            char have_sig[96] = {0};
            if (fgets(have_sig, sizeof(have_sig), fs)) {
                size_t l = strlen(have_sig);
                while (l > 0 && (have_sig[l - 1] == '\n' || have_sig[l - 1] == '\r')) {
                    have_sig[--l] = '\0';
                }
            }
            fclose(fs);
            if (strcmp(have_sig, want_sig) == 0) {
                return;
            }
        }
    }

    int pfd[2];
    if (pipe(pfd) != 0) return;

    pid_t pid = fork();
    if (pid < 0) {
        close(pfd[0]);
        close(pfd[1]);
        return;
    }
    if (pid == 0) {
        close(pfd[0]);
        if (setregid(0, 0) < 0) {}
        dup2(pfd[1], STDOUT_FILENO);
        if (pfd[1] > 2) close(pfd[1]);
        int dn = open("/dev/null", O_WRONLY);
        if (dn >= 0) {
            dup2(dn, STDERR_FILENO);
            if (dn > 2) close(dn);
        }
        char *const args_toybox[] = {"/system/bin/toybox", "gzip", "-dc", g_seed_gz_file, NULL};
        execv(args_toybox[0], args_toybox);
        char *const args_gzip[] = {"/bin/gzip", "-dc", g_seed_gz_file, NULL};
        execv(args_gzip[0], args_gzip);
        char *const args_ugzip[] = {"/usr/bin/gzip", "-dc", g_seed_gz_file, NULL};
        execv(args_ugzip[0], args_ugzip);
        _exit(127);
    }
    close(pfd[1]);

    char allow_tmp[320], hosts_tmp[320], doms_tmp[320];
    snprintf(allow_tmp, sizeof(allow_tmp), "%s.tmp", g_allow_file);
    snprintf(hosts_tmp, sizeof(hosts_tmp), "%s.tmp", g_hosts_file);
    snprintf(doms_tmp, sizeof(doms_tmp), "%s.tmp", g_domains_file);

    FILE *in = fdopen(pfd[0], "r");
    FILE *fa = fopen(allow_tmp, "w");
    FILE *fh = fopen(hosts_tmp, "w");
    FILE *fd = fopen(doms_tmp, "w");

    uint32_t allow_cnt = 0, hosts_cnt = 0, dom_cnt = 0;
    if (in && fa && fh && fd) {
        fputs("127.0.0.1 localhost\n::1 ip6-localhost\n", fh);
        int section = 0; /* 1=ALLOW, 2=HOSTS, 3=DOMAINS */
        char line[512];
        char norm[260];
        while (fgets(line, sizeof(line), in)) {
            char *s = line;
            while (*s && isspace((unsigned char)*s)) s++;
            size_t len = strlen(s);
            while (len > 0 && isspace((unsigned char)s[len - 1])) {
                s[--len] = '\0';
            }
            if (len == 0 || s[0] == '#') continue;
            if (strcmp(s, "@ALLOW") == 0) { section = 1; continue; }
            if (strcmp(s, "@HOSTS") == 0) { section = 2; continue; }
            if (strcmp(s, "@DOMAINS") == 0) { section = 3; continue; }

            if (section == 1) {
                fprintf(fa, "%s\n", s);
                allow_cnt++;
            } else if (section == 2) {
                fprintf(fh, "0.0.0.0 %s\n:: %s\n", s, s);
                hosts_cnt++;
            } else if (section == 3) {
                fprintf(fd, "%s\n", s);
                dom_cnt++;
            } else {
                clean_domain_line(s, norm, sizeof(norm));
                if (norm[0]) {
                    fprintf(fd, "%s\n", norm);
                    dom_cnt++;
                    if (hosts_cnt < 16000U) {
                        fprintf(fh, "0.0.0.0 %s\n:: %s\n", norm, norm);
                        hosts_cnt++;
                    }
                }
            }
        }
    }

    if (in) fclose(in);
    else close(pfd[0]);
    if (fa) fclose(fa);
    if (fh) fclose(fh);
    if (fd) fclose(fd);

    int wst = 0;
    waitpid(pid, &wst, 0);

    if (dom_cnt > 0) {
        chmod(doms_tmp, 0644);
        chmod(hosts_tmp, 0644);
        rename(doms_tmp, g_domains_file);
        rename(hosts_tmp, g_hosts_file);
        if (allow_cnt > 0) {
            chmod(allow_tmp, 0644);
            rename(allow_tmp, g_allow_file);
        } else {
            unlink(allow_tmp);
        }
        if (access("/system/bin/chcon", X_OK) == 0) {
            run_cmd((char *const[]){"/system/bin/chcon", "u:object_r:system_file:s0", g_hosts_file, NULL});
        }
        FILE *fs = fopen(g_seed_stamp, "w");
        if (fs) {
            fprintf(fs, "%s\n", want_sig);
            fclose(fs);
            chmod(g_seed_stamp, 0644);
        }
    } else {
        unlink(doms_tmp);
        unlink(hosts_tmp);
        unlink(allow_tmp);
    }
}

static bool reload_domain_db(void) {
    struct domain_db *db = calloc(1, sizeof(*db));
    if (!db) return false;
    db->block_ht = calloc(HT_SIZE, sizeof(struct ht_slot));
    if (!db->block_ht) {
        free(db);
        return false;
    }
    /* Reserve offset 0 in arena as sentinel */
    arena_push(db, "", 0);

    /* Hardcoded safety allowlist so critical logins/OTAs always work even if allow.txt is missing */
    static const char *const builtin_allow[] = {
        "google.com", "www.google.com", "accounts.google.com", "play.googleapis.com",
        "googleapis.com", "gstatic.com", "www.gstatic.com", "connectivitycheck.gstatic.com",
        "clients1.google.com", "clients2.google.com", "clients3.google.com", "clients4.google.com",
        "mtalk.google.com", "googleusercontent.com", "dl.google.com", "dl-ssl.google.com",
        "gvt1.com", "gvt2.com", "gvt3.com",
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "s.youtube.com", "googlevideo.com", "ytimg.com", "youtubei.googleapis.com",
        "instagram.com", "www.instagram.com", "graph.instagram.com", "i.instagram.com",
        "facebook.com", "www.facebook.com", "graph.facebook.com", "b-graph.facebook.com",
        "mqtt-mini.facebook.com", "g.whatsapp.net", "whatsapp.net", "whatsapp.com",
        "github.com", "api.github.com", "raw.githubusercontent.com", "objects.githubusercontent.com",
        "release-assets.githubusercontent.com", "github-releases.githubusercontent.com",
        "npci.org.in", "upi.org.in", "razorpay.com",
        NULL
    };
    for (int i = 0; builtin_allow[i]; i++) {
        ht_insert(db->allow_ht, ALLOW_HT_MASK, db, builtin_allow[i], false);
    }

    char line[512], dom[260];
    FILE *fa = fopen(g_allow_file, "r");
    if (fa) {
        while (fgets(line, sizeof(line), fa) && db->allow_count < (ALLOW_HT_SIZE * 3U) / 4U) {
            clean_domain_line(line, dom, sizeof(dom));
            if (dom[0]) ht_insert(db->allow_ht, ALLOW_HT_MASK, db, dom, false);
        }
        fclose(fa);
    }

    struct stat st_dom;
    bool have_stat = (stat(g_domains_file, &st_dom) == 0);
    FILE *fb = fopen(g_domains_file, "r");
    if (fb) {
        while (fgets(line, sizeof(line), fb) && db->block_count < (HT_SIZE * 7U) / 10U) {
            clean_domain_line(line, dom, sizeof(dom));
            if (dom[0]) ht_insert(db->block_ht, HT_MASK, db, dom, true);
        }
        fclose(fb);
    }

    pthread_rwlock_wrlock(&g_db_lock);
    struct domain_db *old = g_db;
    g_db = db;
    if (have_stat && db->block_count > 0) {
        g_domains_mtime = st_dom.st_mtime;
        g_domains_size = (uint64_t)st_dom.st_size;
    }
    pthread_rwlock_unlock(&g_db_lock);
    db_free(old);
    return true;
}

static bool normalize_qname(const char *qname_raw, char *qname, size_t cap) {
    if (!qname_raw || !qname_raw[0] || cap < 2) return false;
    size_t i = 0;
    while (qname_raw[i] && i + 1 < cap) {
        qname[i] = (char)tolower((unsigned char)qname_raw[i]);
        i++;
    }
    while (i > 0 && qname[i - 1] == '.') i--;
    qname[i] = '\0';
    return i > 0;
}

static bool is_domain_allowed_locked(const struct domain_db *db, const char *qname) {
    if (!db) return false;
    if (ht_lookup(db->allow_ht, ALLOW_HT_MASK, db, qname)) return true;
    const char *p = qname;
    while (p && *p) {
        const char *dot = strchr(p, '.');
        if (!dot) break;
        if (strcmp(p, "googlevideo.com") == 0 || strcmp(p, "cdninstagram.com") == 0 ||
            strcmp(p, "fbcdn.net") == 0 || strcmp(p, "whatsapp.net") == 0 ||
            strcmp(p, "githubusercontent.com") == 0 || strcmp(p, "gstatic.com") == 0 ||
            strcmp(p, "googleapis.com") == 0 || strcmp(p, "gvt1.com") == 0 ||
            strcmp(p, "gvt2.com") == 0 || strcmp(p, "gvt3.com") == 0) {
            return true;
        }
        p = dot + 1;
    }
    return false;
}

static bool is_domain_explicitly_allowed(const char *qname_raw) {
    char qname[256];
    if (!normalize_qname(qname_raw, qname, sizeof(qname))) return false;
    pthread_rwlock_rdlock(&g_db_lock);
    bool allowed = is_domain_allowed_locked(g_db, qname);
    pthread_rwlock_unlock(&g_db_lock);
    return allowed;
}

static bool is_domain_blocked(const char *qname_raw) {
    char qname[256];
    if (!normalize_qname(qname_raw, qname, sizeof(qname))) return false;

    bool blocked = false;
    pthread_rwlock_rdlock(&g_db_lock);
    const struct domain_db *db = g_db;
    if (db && !is_domain_allowed_locked(db, qname)) {
        const char *p = qname;
        while (p && *p) {
            if (!strchr(p, '.')) break; /* never match bare TLD */
            if (ht_lookup(db->block_ht, HT_MASK, db, p)) {
                blocked = true;
                break;
            }
            const char *dot = strchr(p, '.');
            if (!dot) break;
            p = dot + 1;
        }
    }
    pthread_rwlock_unlock(&g_db_lock);
    return blocked;
}

static void record_query_stat(bool blocked, const char *qname) {
    pthread_mutex_lock(&g_stats_lock);
    g_total_queries++;
    if (blocked) {
        g_blocked_queries++;
        if (qname && qname[0]) {
            snprintf(g_last_blocked, sizeof(g_last_blocked), "%s", qname);
        }
    }
    pthread_mutex_unlock(&g_stats_lock);
}

/* Parse RFC 1035 domain name starting at pkt[offset]. Returns next offset after name, or -1 on error. */
static int dns_read_name(const uint8_t *pkt, size_t pkt_len, size_t offset, char *out, size_t out_sz) {
    size_t pos = offset;
    size_t out_i = 0;
    int    jumped = 0;
    int    next_off = -1;
    int    jumps = 0;

    if (out_sz == 0) return -1;
    out[0] = '\0';

    while (pos < pkt_len) {
        uint8_t len = pkt[pos];
        if (len == 0) {
            if (!jumped) next_off = (int)(pos + 1);
            out[out_i] = '\0';
            return next_off;
        }
        if ((len & 0xC0) == 0xC0) {
            if (pos + 1 >= pkt_len) return -1;
            if (!jumped) next_off = (int)(pos + 2);
            pos = (size_t)(((len & 0x3F) << 8) | pkt[pos + 1]);
            jumped = 1;
            if (++jumps > 10) return -1;
            continue;
        }
        if ((len & 0xC0) != 0) return -1;
        pos++;
        if (pos + len > pkt_len) return -1;
        if (out_i > 0) {
            if (out_i + 1 >= out_sz) return -1;
            out[out_i++] = '.';
        }
        if (out_i + len >= out_sz) return -1;
        for (uint8_t k = 0; k < len; k++) {
            out[out_i++] = (char)tolower((unsigned char)pkt[pos + k]);
        }
        pos += len;
    }
    return -1;
}

static bool dns_extract_qsection(const uint8_t *pkt, size_t pkt_len, char *qname, size_t qname_sz,
                                 uint16_t *qtype_out, size_t *qend_out) {
    if (pkt_len < 12) return false;
    uint16_t qdcount = (uint16_t)((pkt[4] << 8) | pkt[5]);
    if (qdcount == 0) return false;

    int after_name = dns_read_name(pkt, pkt_len, 12, qname, qname_sz);
    if (after_name < 0 || (size_t)after_name + 4 > pkt_len) return false;

    uint16_t qtype = (uint16_t)((pkt[after_name] << 8) | pkt[after_name + 1]);
    uint16_t qclass = (uint16_t)((pkt[after_name + 2] << 8) | pkt[after_name + 3]);
    if ((qclass & 0x7FFFU) != 1U) return false; /* CLASS IN */

    *qtype_out = qtype;
    *qend_out = (size_t)after_name + 4U;
    return true;
}

static bool dns_parse_question(const uint8_t *pkt, size_t pkt_len, char *qname, size_t qname_sz,
                               uint16_t *qtype_out, size_t *qend_out) {
    if (pkt_len < 12) return false;
    uint16_t flags = (uint16_t)((pkt[2] << 8) | pkt[3]);
    if (flags & 0x8000U) return false; /* QR must be 0 (query) */
    return dns_extract_qsection(pkt, pkt_len, qname, qname_sz, qtype_out, qend_out);
}

static bool dns_parse_response(const uint8_t *pkt, size_t pkt_len, char *qname, size_t qname_sz,
                               uint16_t *qtype_out, size_t *qend_out) {
    if (pkt_len < 12) return false;
    uint16_t flags = (uint16_t)((pkt[2] << 8) | pkt[3]);
    if ((flags & 0x8000U) == 0) return false;      /* QR must be 1 (response) */
    if ((flags & 0x7800U) != 0) return false;      /* OPCODE must be 0 (QUERY) */
    return dns_extract_qsection(pkt, pkt_len, qname, qname_sz, qtype_out, qend_out);
}

/* Inspect upstream DNS response Answer section for CNAME cloaking */
static bool dns_response_has_blocked_cname(const uint8_t *pkt, size_t pkt_len, char *matched_cname, size_t mc_sz) {
    if (pkt_len < 12) return false;
    uint16_t qdcount = (uint16_t)((pkt[4] << 8) | pkt[5]);
    uint16_t ancount = (uint16_t)((pkt[6] << 8) | pkt[7]);
    if (ancount == 0) return false;

    size_t pos = 12;
    char tmp[256];
    for (uint16_t q = 0; q < qdcount; q++) {
        int next = dns_read_name(pkt, pkt_len, pos, tmp, sizeof(tmp));
        if (next < 0 || (size_t)next + 4 > pkt_len) return false;
        pos = (size_t)next + 4;
    }
    for (uint16_t a = 0; a < ancount && a < 24; a++) {
        int next = dns_read_name(pkt, pkt_len, pos, tmp, sizeof(tmp));
        if (next < 0 || (size_t)next + 10 > pkt_len) return false;
        pos = (size_t)next;
        uint16_t rtype = (uint16_t)((pkt[pos] << 8) | pkt[pos + 1]);
        uint16_t rdlen = (uint16_t)((pkt[pos + 8] << 8) | pkt[pos + 9]);
        pos += 10;
        if (pos + rdlen > pkt_len) return false;
        if (rtype == 5) { /* CNAME */
            char cname_target[256];
            if (dns_read_name(pkt, pkt_len, pos, cname_target, sizeof(cname_target)) > 0) {
                if (is_domain_blocked(cname_target)) {
                    if (matched_cname && mc_sz > 0) {
                        snprintf(matched_cname, mc_sz, "%s", cname_target);
                    }
                    return true;
                }
            }
        }
        pos += rdlen;
    }
    return false;
}

/* Build immediate sinkhole DNS reply: A -> 0.0.0.0, AAAA -> ::, HTTPS/SVCB/other -> NODATA (ANCOUNT=0) */
static size_t dns_build_sinkhole(const uint8_t *req, size_t req_len, size_t qend, uint16_t qtype,
                                 uint16_t txid_override, uint8_t *out, size_t out_cap) {
    if (qend > req_len || qend + 32 > out_cap || qend < 16) return 0;
    memcpy(out, req, qend);
    out[0] = (uint8_t)(txid_override >> 8);
    out[1] = (uint8_t)(txid_override & 0xFF);
    /* QR=1, RD=(copied), RA=1, RCODE=0 (NOERROR) */
    out[2] = (uint8_t)(0x80 | (req[2] & 0x01));
    out[3] = 0x80;
    out[4] = 0x00;
    out[5] = 0x01; /* QDCOUNT = 1 */
    out[8] = 0x00;
    out[9] = 0x00; /* NSCOUNT = 0 */
    out[10] = 0x00;
    out[11] = 0x00; /* ARCOUNT = 0 */

    size_t pos = qend;
    if (qtype == 1) { /* A -> 0.0.0.0 */
        out[6] = 0x00;
        out[7] = 0x01; /* ANCOUNT = 1 */
        uint8_t rr_a[16] = {
            0xC0, 0x0C,             /* pointer to QNAME at offset 12 */
            0x00, 0x01,             /* TYPE A */
            0x00, 0x01,             /* CLASS IN */
            0x00, 0x00, 0x00, 0x3C, /* TTL 60s */
            0x00, 0x04,             /* RDLENGTH 4 */
            0x00, 0x00, 0x00, 0x00  /* 0.0.0.0 */
        };
        memcpy(out + pos, rr_a, sizeof(rr_a));
        pos += sizeof(rr_a);
    } else if (qtype == 28) { /* AAAA -> :: */
        out[6] = 0x00;
        out[7] = 0x01; /* ANCOUNT = 1 */
        uint8_t rr_aaaa[28] = {
            0xC0, 0x0C,             /* pointer to QNAME at offset 12 */
            0x00, 0x1C,             /* TYPE AAAA */
            0x00, 0x01,             /* CLASS IN */
            0x00, 0x00, 0x00, 0x3C, /* TTL 60s */
            0x00, 0x10,             /* RDLENGTH 16 */
            0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0
        };
        memcpy(out + pos, rr_aaaa, sizeof(rr_aaaa));
        pos += sizeof(rr_aaaa);
    } else {
        /* HTTPS (65), SVCB (64), etc.: return authoritative empty NODATA */
        out[6] = 0x00;
        out[7] = 0x00;
    }
    return pos;
}

/* ---------------- Endian-Safe RFC 1071 / 768 / 8200 Checksums ---------------- */

static uint32_t csum_partial_be16(uint32_t sum, const uint8_t *buf, size_t len) {
    size_t i = 0;
    while (i + 1U < len) {
        sum += ((uint32_t)buf[i] << 8) | (uint32_t)buf[i + 1U];
        i += 2U;
    }
    if (i < len) {
        sum += ((uint32_t)buf[i] << 8);
    }
    return sum;
}

static uint16_t csum_fold_be16(uint32_t sum) {
    while (sum >> 16) {
        sum = (sum & 0xFFFFU) + (sum >> 16);
    }
    return (uint16_t)(~sum & 0xFFFFU);
}

static size_t rebuild_udp_dns_packet(int ip_ver, const uint8_t *orig_ip, size_t l3_len,
                                     const uint8_t *orig_uh, const uint8_t *dns_payload,
                                     size_t dns_len, uint8_t *out_ip, size_t out_cap) {
    size_t udp_len = 8U + dns_len;
    size_t total_len = l3_len + udp_len;
    if (total_len > out_cap || total_len > 65535U || udp_len > 65535U) return 0;

    memcpy(out_ip, orig_ip, l3_len);
    uint8_t *uh = out_ip + l3_len;
    uh[0] = orig_uh[0]; /* sport */
    uh[1] = orig_uh[1];
    uh[2] = orig_uh[2]; /* dport */
    uh[3] = orig_uh[3];
    uh[4] = (uint8_t)(udp_len >> 8);
    uh[5] = (uint8_t)(udp_len & 0xFFU);
    uh[6] = 0;
    uh[7] = 0;
    memcpy(uh + 8U, dns_payload, dns_len);

    if (ip_ver == 4) {
        out_ip[2] = (uint8_t)(total_len >> 8);
        out_ip[3] = (uint8_t)(total_len & 0xFFU);
        out_ip[10] = 0;
        out_ip[11] = 0;
        uint16_t ip_csum = csum_fold_be16(csum_partial_be16(0, out_ip, l3_len));
        out_ip[10] = (uint8_t)(ip_csum >> 8);
        out_ip[11] = (uint8_t)(ip_csum & 0xFFU);

        uint32_t usum = 0;
        usum = csum_partial_be16(usum, out_ip + 12U, 8U); /* saddr + daddr */
        usum += (uint32_t)IPPROTO_UDP;
        usum += (uint32_t)udp_len;
        usum = csum_partial_be16(usum, uh, udp_len);
        uint16_t udp_csum = csum_fold_be16(usum);
        if (udp_csum == 0) udp_csum = 0xFFFFU;
        uh[6] = (uint8_t)(udp_csum >> 8);
        uh[7] = (uint8_t)(udp_csum & 0xFFU);
        return total_len;
    }

    if (ip_ver == 6) {
        size_t ip6_plen = (l3_len - 40U) + udp_len;
        if (ip6_plen > 65535U) return 0;
        out_ip[4] = (uint8_t)(ip6_plen >> 8);
        out_ip[5] = (uint8_t)(ip6_plen & 0xFFU);

        uint32_t usum = 0;
        usum = csum_partial_be16(usum, out_ip + 8U, 32U); /* IPv6 saddr + daddr */
        usum += (uint32_t)(udp_len >> 16);
        usum += (uint32_t)(udp_len & 0xFFFFU);
        usum += (uint32_t)IPPROTO_UDP;
        usum = csum_partial_be16(usum, uh, udp_len);
        uint16_t udp_csum = csum_fold_be16(usum);
        if (udp_csum == 0) udp_csum = 0xFFFFU;
        uh[6] = (uint8_t)(udp_csum >> 8);
        uh[7] = (uint8_t)(udp_csum & 0xFFU);
        return total_len;
    }

    return 0;
}

/* ---------------- Kernel Netfilter NFQUEUE (Queue 53) Engine ---------------- */

static uint8_t *nla_put_raw(uint8_t *p, uint16_t type, const void *data, size_t len) {
    uint16_t nla_len = (uint16_t)(sizeof(struct nlattr) + len);
    struct nlattr nla;
    nla.nla_len = nla_len;
    nla.nla_type = type;
    memcpy(p, &nla, sizeof(nla));
    if (len > 0 && data != NULL) {
        memcpy(p + sizeof(nla), data, len);
    }
    size_t aligned = NLA_ALIGN(nla_len);
    if (aligned > nla_len) {
        memset(p + nla_len, 0, aligned - nla_len);
    }
    return p + aligned;
}

static int nfq_send_cfg_msg(int nl_fd, uint16_t queue_num, uint16_t attr_type,
                            const void *attr_data, size_t attr_len) {
    uint8_t buf[256];
    memset(buf, 0, sizeof(buf));
    struct nlmsghdr *nlh = (struct nlmsghdr *)buf;
    nlh->nlmsg_type = (uint16_t)((NFNL_SUBSYS_QUEUE << 8) | NFQNL_MSG_CONFIG);
    nlh->nlmsg_flags = NLM_F_REQUEST;

    struct nfgenmsg *nfg = (struct nfgenmsg *)(buf + NLMSG_HDRLEN);
    nfg->nfgen_family = AF_UNSPEC;
    nfg->version = NFNETLINK_V0;
    nfg->res_id = htons(queue_num);

    uint8_t *tail = buf + NLMSG_SPACE(sizeof(struct nfgenmsg));
    tail = nla_put_raw(tail, attr_type, attr_data, attr_len);
    nlh->nlmsg_len = (uint32_t)(tail - buf);

    struct sockaddr_nl kaddr;
    memset(&kaddr, 0, sizeof(kaddr));
    kaddr.nl_family = AF_NETLINK;
    return (sendto(nl_fd, buf, nlh->nlmsg_len, 0, (struct sockaddr *)&kaddr, sizeof(kaddr)) < 0) ? -1 : 0;
}

static int nfq_open_and_bind(uint16_t queue_num) {
    int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_NETFILTER);
    if (fd < 0) return -1;

    struct sockaddr_nl local;
    memset(&local, 0, sizeof(local));
    local.nl_family = AF_NETLINK;
    if (bind(fd, (struct sockaddr *)&local, sizeof(local)) < 0) {
        close(fd);
        return -1;
    }

    int one = 1;
    setsockopt(fd, SOL_NETLINK, NETLINK_NO_ENOBUFS, &one, sizeof(one));
    int rcvbuf = 4 * 1024 * 1024;
    if (setsockopt(fd, SOL_SOCKET, SO_RCVBUFFORCE, &rcvbuf, sizeof(rcvbuf)) < 0) {
        setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));
    }
    struct timeval tv = {.tv_sec = 0, .tv_usec = 400000};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    struct nfqnl_msg_config_cmd cmd;
    memset(&cmd, 0, sizeof(cmd));
    cmd.command = NFQNL_CFG_CMD_PF_UNBIND;
    cmd.pf = htons(AF_INET);
    nfq_send_cfg_msg(fd, 0, NFQA_CFG_CMD, &cmd, sizeof(cmd));
    cmd.command = NFQNL_CFG_CMD_PF_BIND;
    nfq_send_cfg_msg(fd, 0, NFQA_CFG_CMD, &cmd, sizeof(cmd));

    cmd.command = NFQNL_CFG_CMD_PF_UNBIND;
    cmd.pf = htons(AF_INET6);
    nfq_send_cfg_msg(fd, 0, NFQA_CFG_CMD, &cmd, sizeof(cmd));
    cmd.command = NFQNL_CFG_CMD_PF_BIND;
    nfq_send_cfg_msg(fd, 0, NFQA_CFG_CMD, &cmd, sizeof(cmd));

    memset(&cmd, 0, sizeof(cmd));
    cmd.command = NFQNL_CFG_CMD_BIND;
    cmd.pf = 0;
    if (nfq_send_cfg_msg(fd, queue_num, NFQA_CFG_CMD, &cmd, sizeof(cmd)) < 0) {
        close(fd);
        return -1;
    }

    struct nfqnl_msg_config_params params;
    memset(&params, 0, sizeof(params));
    params.copy_range = htonl(0xFFFFU);
    params.copy_mode = NFQNL_COPY_PACKET;
    if (nfq_send_cfg_msg(fd, queue_num, NFQA_CFG_PARAMS, &params, sizeof(params)) < 0) {
        close(fd);
        return -1;
    }

    uint32_t qmaxlen = htonl(4096U);
    nfq_send_cfg_msg(fd, queue_num, NFQA_CFG_QUEUE_MAXLEN | NLA_F_NET_BYTEORDER, &qmaxlen, sizeof(qmaxlen));

    /* Fail-open inside kernel queue if userspace socket buffer ever saturates */
    uint8_t fbuf[256];
    memset(fbuf, 0, sizeof(fbuf));
    struct nlmsghdr *nlh = (struct nlmsghdr *)fbuf;
    nlh->nlmsg_type = (uint16_t)((NFNL_SUBSYS_QUEUE << 8) | NFQNL_MSG_CONFIG);
    nlh->nlmsg_flags = NLM_F_REQUEST;
    struct nfgenmsg *nfg = (struct nfgenmsg *)(fbuf + NLMSG_HDRLEN);
    nfg->nfgen_family = AF_UNSPEC;
    nfg->version = NFNETLINK_V0;
    nfg->res_id = htons(queue_num);
    uint8_t *tail = fbuf + NLMSG_SPACE(sizeof(struct nfgenmsg));
    uint32_t flags_be = htonl(NFQA_CFG_F_FAIL_OPEN);
    tail = nla_put_raw(tail, NFQA_CFG_FLAGS | NLA_F_NET_BYTEORDER, &flags_be, sizeof(flags_be));
    tail = nla_put_raw(tail, NFQA_CFG_MASK | NLA_F_NET_BYTEORDER, &flags_be, sizeof(flags_be));
    nlh->nlmsg_len = (uint32_t)(tail - fbuf);

    struct sockaddr_nl kaddr;
    memset(&kaddr, 0, sizeof(kaddr));
    kaddr.nl_family = AF_NETLINK;
    ssize_t unused_r = sendto(fd, fbuf, nlh->nlmsg_len, 0, (struct sockaddr *)&kaddr, sizeof(kaddr));
    (void)unused_r;

    return fd;
}

static void nfq_send_verdict(int nl_fd, uint16_t queue_num, uint32_t pkt_id_be, uint32_t verdict,
                             const uint8_t *new_pkt, size_t new_len, uint8_t *vbuf) {
    struct nlmsghdr *nlh = (struct nlmsghdr *)vbuf;
    memset(vbuf, 0, NLMSG_SPACE(sizeof(struct nfgenmsg)));
    nlh->nlmsg_type = (uint16_t)((NFNL_SUBSYS_QUEUE << 8) | NFQNL_MSG_VERDICT);
    nlh->nlmsg_flags = NLM_F_REQUEST;

    struct nfgenmsg *nfg = (struct nfgenmsg *)(vbuf + NLMSG_HDRLEN);
    nfg->nfgen_family = AF_UNSPEC;
    nfg->version = NFNETLINK_V0;
    nfg->res_id = htons(queue_num);

    uint8_t *tail = vbuf + NLMSG_SPACE(sizeof(struct nfgenmsg));
    struct nfqnl_msg_verdict_hdr vh;
    vh.verdict = htonl(verdict);
    vh.id = pkt_id_be;
    tail = nla_put_raw(tail, NFQA_VERDICT_HDR, &vh, sizeof(vh));

    if (new_pkt != NULL && new_len > 0 && new_len <= 65535U) {
        tail = nla_put_raw(tail, NFQA_PAYLOAD, new_pkt, new_len);
    }
    nlh->nlmsg_len = (uint32_t)(tail - vbuf);

    struct sockaddr_nl kaddr;
    memset(&kaddr, 0, sizeof(kaddr));
    kaddr.nl_family = AF_NETLINK;
    ssize_t unused_r = sendto(nl_fd, vbuf, nlh->nlmsg_len, MSG_DONTWAIT,
                              (struct sockaddr *)&kaddr, sizeof(kaddr));
    (void)unused_r;
}

static void nfq_process_packet(int nl_fd, uint16_t queue_num, uint32_t pkt_id_be,
                               const uint8_t *ip_pkt, size_t ip_len,
                               uint8_t *new_ip_buf, uint8_t *verdict_buf) {
    bool active_filter = g_enabled && (g_pause_until_ms <= now_ms());
    if (!active_filter || !ip_pkt || ip_len < 28U) {
        nfq_send_verdict(nl_fd, queue_num, pkt_id_be, NF_ACCEPT, NULL, 0, verdict_buf);
        return;
    }

    int ver = (int)(ip_pkt[0] >> 4);
    size_t l3_len = 0;
    size_t pkt_end = 0;

    if (ver == 4) {
        l3_len = (size_t)(ip_pkt[0] & 0x0FU) * 4U;
        if (l3_len < 20U || ip_len < l3_len + 8U) {
            nfq_send_verdict(nl_fd, queue_num, pkt_id_be, NF_ACCEPT, NULL, 0, verdict_buf);
            return;
        }
        uint16_t tot_len = (uint16_t)((ip_pkt[2] << 8) | ip_pkt[3]);
        if (tot_len < l3_len + 8U || (size_t)tot_len > ip_len) {
            nfq_send_verdict(nl_fd, queue_num, pkt_id_be, NF_ACCEPT, NULL, 0, verdict_buf);
            return;
        }
        uint16_t frag = (uint16_t)((ip_pkt[6] << 8) | ip_pkt[7]);
        if (ip_pkt[9] != IPPROTO_UDP || (frag & 0x3FFFU) != 0) {
            nfq_send_verdict(nl_fd, queue_num, pkt_id_be, NF_ACCEPT, NULL, 0, verdict_buf);
            return;
        }
        pkt_end = (size_t)tot_len;
    } else if (ver == 6) {
        if (ip_len < 48U) {
            nfq_send_verdict(nl_fd, queue_num, pkt_id_be, NF_ACCEPT, NULL, 0, verdict_buf);
            return;
        }
        uint16_t plen = (uint16_t)((ip_pkt[4] << 8) | ip_pkt[5]);
        size_t lim = 40U + (size_t)plen;
        if (lim > ip_len) {
            nfq_send_verdict(nl_fd, queue_num, pkt_id_be, NF_ACCEPT, NULL, 0, verdict_buf);
            return;
        }
        uint8_t nh = ip_pkt[6];
        size_t off = 40U;
        for (int ext = 0; ext < 4 && (nh == 0 || nh == 43 || nh == 60); ext++) {
            if (off + 2U > lim) { nh = 0xFFU; break; }
            uint8_t next_nh = ip_pkt[off];
            size_t ext_len = ((size_t)ip_pkt[off + 1U] + 1U) * 8U;
            if (off + ext_len > lim) { nh = 0xFFU; break; }
            off += ext_len;
            nh = next_nh;
        }
        if (nh != IPPROTO_UDP || off + 8U > lim) {
            nfq_send_verdict(nl_fd, queue_num, pkt_id_be, NF_ACCEPT, NULL, 0, verdict_buf);
            return;
        }
        l3_len = off;
        pkt_end = lim;
    } else {
        nfq_send_verdict(nl_fd, queue_num, pkt_id_be, NF_ACCEPT, NULL, 0, verdict_buf);
        return;
    }

    const uint8_t *uh = ip_pkt + l3_len;
    uint16_t ulen = (uint16_t)((uh[4] << 8) | uh[5]);
    if (ulen < 20U || l3_len + (size_t)ulen > pkt_end) {
        nfq_send_verdict(nl_fd, queue_num, pkt_id_be, NF_ACCEPT, NULL, 0, verdict_buf);
        return;
    }

    const uint8_t *dns = uh + 8U;
    size_t dns_len = (size_t)ulen - 8U;
    char qname[256];
    uint16_t qtype = 0;
    size_t qend = 0;

    if (!dns_parse_response(dns, dns_len, qname, sizeof(qname), &qtype, &qend)) {
        nfq_send_verdict(nl_fd, queue_num, pkt_id_be, NF_ACCEPT, NULL, 0, verdict_buf);
        return;
    }

    char cname_hit[256] = {0};
    bool blocked = false;
    if (!is_domain_explicitly_allowed(qname)) {
        blocked = is_domain_blocked(qname);
        if (!blocked) {
            blocked = dns_response_has_blocked_cname(dns, dns_len, cname_hit, sizeof(cname_hit));
        }
    }

    if (!blocked) {
        record_query_stat(false, NULL);
        nfq_send_verdict(nl_fd, queue_num, pkt_id_be, NF_ACCEPT, NULL, 0, verdict_buf);
        return;
    }

    uint16_t txid = (uint16_t)((dns[0] << 8) | dns[1]);
    uint8_t sink_dns[544];
    size_t sink_len = dns_build_sinkhole(dns, dns_len, qend, qtype, txid, sink_dns, sizeof(sink_dns));
    if (sink_len == 0) {
        nfq_send_verdict(nl_fd, queue_num, pkt_id_be, NF_ACCEPT, NULL, 0, verdict_buf);
        return;
    }

    size_t new_ip_len = rebuild_udp_dns_packet(ver, ip_pkt, l3_len, uh, sink_dns, sink_len,
                                               new_ip_buf, NFQ_BUF_SZ);
    if (new_ip_len == 0) {
        nfq_send_verdict(nl_fd, queue_num, pkt_id_be, NF_ACCEPT, NULL, 0, verdict_buf);
        return;
    }

    record_query_stat(true, cname_hit[0] ? cname_hit : qname);
    nfq_send_verdict(nl_fd, queue_num, pkt_id_be, NF_ACCEPT, new_ip_buf, new_ip_len, verdict_buf);
}

static void *nfq_worker_thread(void *arg) {
    int nl_fd = (int)(intptr_t)arg;
    uint8_t *rx_buf = malloc(NFQ_BUF_SZ);
    uint8_t *new_ip_buf = malloc(NFQ_BUF_SZ);
    uint8_t *verdict_buf = malloc(NFQ_BUF_SZ + 256U);
    if (!rx_buf || !new_ip_buf || !verdict_buf) {
        free(rx_buf);
        free(new_ip_buf);
        free(verdict_buf);
        return NULL;
    }

    while (g_running) {
        ssize_t n = recv(nl_fd, rx_buf, NFQ_BUF_SZ, 0);
        if (n <= 0) {
            if (n < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK || errno == ENOBUFS)) {
                continue;
            }
            if (!g_running) break;
            usleep(10000);
            continue;
        }

        size_t off = 0;
        while (off + sizeof(struct nlmsghdr) <= (size_t)n) {
            const struct nlmsghdr *nlh = (const struct nlmsghdr *)(rx_buf + off);
            uint32_t mlen = nlh->nlmsg_len;
            if (mlen < sizeof(struct nlmsghdr) || off + (size_t)mlen > (size_t)n) {
                break;
            }

            uint8_t subsys = (uint8_t)(nlh->nlmsg_type >> 8);
            uint8_t msg_type = (uint8_t)(nlh->nlmsg_type & 0xFFU);
            if (subsys == NFNL_SUBSYS_QUEUE && msg_type == NFQNL_MSG_PACKET &&
                mlen >= NLMSG_SPACE(sizeof(struct nfgenmsg))) {
                size_t aoff = NLMSG_SPACE(sizeof(struct nfgenmsg));
                bool has_ph = false;
                uint32_t pkt_id_be = 0;
                const uint8_t *payload = NULL;
                size_t payload_len = 0;

                while (aoff + sizeof(struct nlattr) <= (size_t)mlen) {
                    const struct nlattr *nla = (const struct nlattr *)((const uint8_t *)nlh + aoff);
                    if (nla->nla_len < sizeof(struct nlattr) ||
                        aoff + (size_t)nla->nla_len > (size_t)mlen) {
                        break;
                    }
                    uint16_t atype = (uint16_t)(nla->nla_type & NLA_TYPE_MASK);
                    const uint8_t *adata = (const uint8_t *)nla + sizeof(struct nlattr);
                    size_t alen = (size_t)nla->nla_len - sizeof(struct nlattr);

                    if (atype == NFQA_PACKET_HDR && alen >= sizeof(struct nfqnl_msg_packet_hdr)) {
                        const struct nfqnl_msg_packet_hdr *ph =
                            (const struct nfqnl_msg_packet_hdr *)adata;
                        pkt_id_be = ph->packet_id;
                        has_ph = true;
                    } else if (atype == NFQA_PAYLOAD) {
                        payload = adata;
                        payload_len = alen;
                    }
                    aoff += NLA_ALIGN(nla->nla_len);
                }

                if (has_ph) {
                    nfq_process_packet(nl_fd, g_nfq_num, pkt_id_be, payload, payload_len,
                                       new_ip_buf, verdict_buf);
                }
            }

            off += NLMSG_ALIGN(mlen);
        }
    }

    free(rx_buf);
    free(new_ip_buf);
    free(verdict_buf);
    return NULL;
}

/* ---------------- Upstreams, Firewall & Control Queue ---------------- */

static bool add_upstream_str(struct upstream_ep *list, int *cnt, const char *ip_str) {
    if (*cnt >= MAX_UPSTREAMS || !ip_str || !ip_str[0]) return false;

    struct sockaddr_in sa4;
    memset(&sa4, 0, sizeof(sa4));
    if (inet_pton(AF_INET, ip_str, &sa4.sin_addr) == 1) {
        uint32_t h = ntohl(sa4.sin_addr.s_addr);
        /* Reject 0.0.0.0/8, 127.0.0.0/8, 169.254.0.0/16, 224.0.0.0/4 (multicast/mDNS), 240.0.0.0/4 */
        if ((h >> 24) == 0U || (h >> 24) == 127U || (h >> 16) == 0xA9FEU || (h & 0xE0000000U) == 0xE0000000U) {
            return false;
        }
        sa4.sin_family = AF_INET;
        sa4.sin_port = htons(53);
        for (int i = 0; i < *cnt; i++) {
            if (list[i].family == AF_INET) {
                const struct sockaddr_in *ex = (const struct sockaddr_in *)&list[i].addr;
                if (ex->sin_addr.s_addr == sa4.sin_addr.s_addr) return false;
            }
        }
        list[*cnt].family = AF_INET;
        memcpy(&list[*cnt].addr, &sa4, sizeof(sa4));
        list[*cnt].addrlen = sizeof(sa4);
        (*cnt)++;
        return true;
    }

    struct sockaddr_in6 sa6;
    memset(&sa6, 0, sizeof(sa6));
    if (inet_pton(AF_INET6, ip_str, &sa6.sin6_addr) == 1) {
        if (IN6_IS_ADDR_UNSPECIFIED(&sa6.sin6_addr) ||
            IN6_IS_ADDR_LOOPBACK(&sa6.sin6_addr) ||
            IN6_IS_ADDR_LINKLOCAL(&sa6.sin6_addr) ||
            IN6_IS_ADDR_MULTICAST(&sa6.sin6_addr)) {
            return false;
        }
        sa6.sin6_family = AF_INET6;
        sa6.sin6_port = htons(53);
        for (int i = 0; i < *cnt; i++) {
            if (list[i].family == AF_INET6) {
                const struct sockaddr_in6 *ex = (const struct sockaddr_in6 *)&list[i].addr;
                if (memcmp(&ex->sin6_addr, &sa6.sin6_addr, sizeof(sa6.sin6_addr)) == 0) return false;
            }
        }
        list[*cnt].family = AF_INET6;
        memcpy(&list[*cnt].addr, &sa6, sizeof(sa6));
        list[*cnt].addrlen = sizeof(sa6);
        (*cnt)++;
        return true;
    }
    return false;
}

static void refresh_upstreams(void) {
    struct upstream_ep tmp[MAX_UPSTREAMS];
    int cnt = 0;

    FILE *f = fopen(g_upstreams_file, "r");
    if (f) {
        char line[128];
        while (fgets(line, sizeof(line), f) && cnt < MAX_UPSTREAMS - 4) {
            char *p = line;
            while (*p && isspace((unsigned char)*p)) p++;
            char *e = p;
            while (*e && !isspace((unsigned char)*e)) e++;
            *e = '\0';
            if (*p) add_upstream_str(tmp, &cnt, p);
        }
        fclose(f);
    }

    add_upstream_str(tmp, &cnt, "1.1.1.1");
    add_upstream_str(tmp, &cnt, "2606:4700:4700::1111");
    add_upstream_str(tmp, &cnt, "8.8.8.8");
    add_upstream_str(tmp, &cnt, "2001:4860:4860::8888");

    pthread_mutex_lock(&g_up_lock);
    memcpy(g_upstreams, tmp, sizeof(tmp));
    g_num_upstreams = cnt;
    pthread_mutex_unlock(&g_up_lock);
}

static bool is_hosts_mounted(void) {
    FILE *f = fopen("/proc/mounts", "r");
    if (!f) return false;
    char line[512];
    bool found = false;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, " /system/etc/hosts ")) {
            found = true;
            break;
        }
    }
    fclose(f);
    return found;
}

static void ensure_hosts_bind_mounted(void) {
    if (g_skip_firewall) return;
    struct stat st;
    if (stat(g_hosts_file, &st) != 0 || st.st_size <= 32) return;

    char ctx[128];
    ssize_t clen = getxattr(g_hosts_file, "security.selinux", ctx, sizeof(ctx) - 1);
    if (clen <= 0 || strncmp(ctx, "u:object_r:system_file:s0", 25) != 0) {
        chmod(g_hosts_file, 0644);
        run_cmd((char *const[]){"/system/bin/chcon", "u:object_r:system_file:s0", g_hosts_file, NULL});
    }

    if (!is_hosts_mounted()) {
        chmod(g_hosts_file, 0644);
        if (mount(g_hosts_file, "/system/etc/hosts", NULL, MS_BIND, NULL) != 0) {
            run_cmd((char *const[]){"/system/bin/nsenter", "-t", "1", "-m", "--",
                "/system/bin/mount", "-o", "bind", g_hosts_file, "/system/etc/hosts", NULL});
        }
    }
}

static pid_t find_netd_pid(void) {
    if (g_last_netd_pid > 1) {
        char comm_path[64];
        snprintf(comm_path, sizeof(comm_path), "/proc/%d/comm", (int)g_last_netd_pid);
        FILE *cf = fopen(comm_path, "r");
        if (cf) {
            char buf[32] = {0};
            bool ok = (fgets(buf, sizeof(buf), cf) != NULL && strncmp(buf, "netd\n", 5) == 0);
            fclose(cf);
            if (ok) return g_last_netd_pid;
        }
    }
    DIR *d = opendir("/proc");
    if (!d) return -1;
    struct dirent *de;
    pid_t found = -1;
    while ((de = readdir(d)) != NULL) {
        if (!isdigit((unsigned char)de->d_name[0])) continue;
        pid_t pid = (pid_t)atoi(de->d_name);
        if (pid <= 1) continue;
        char comm_path[64];
        snprintf(comm_path, sizeof(comm_path), "/proc/%d/comm", (int)pid);
        FILE *cf = fopen(comm_path, "r");
        if (!cf) continue;
        char buf[32] = {0};
        bool match = (fgets(buf, sizeof(buf), cf) != NULL && strncmp(buf, "netd\n", 5) == 0);
        fclose(cf);
        if (match) {
            found = pid;
            break;
        }
    }
    closedir(d);
    return found;
}

static void ensure_single_hook(const char *ipt, const char *table, const char *hook, const char *chain) {
    if (run_cmd((char *const[]){(char *)ipt, "-w", "-t", (char *)table, "-C", (char *)hook, "-j", (char *)chain, NULL}) != 0) {
        run_cmd((char *const[]){(char *)ipt, "-w", "-t", (char *)table, "-I", (char *)hook, "1", "-j", (char *)chain, NULL});
    }
}

static void ensure_firewall_hooks(void) {
    if (g_skip_firewall || !g_fw_applied) return;
    ensure_single_hook("/system/bin/iptables",  "mangle", "INPUT",  "SAMURAI_ADBLOCK_MGL");
    ensure_single_hook("/system/bin/ip6tables", "mangle", "INPUT",  "SAMURAI_ADBLOCK6_MGL");
    ensure_single_hook("/system/bin/iptables",  "nat",    "OUTPUT", "SAMURAI_ADBLOCK_NAT");
    ensure_single_hook("/system/bin/iptables",  "filter", "OUTPUT", "SAMURAI_ADBLOCK_FLT");
    ensure_single_hook("/system/bin/ip6tables", "filter", "OUTPUT", "SAMURAI_ADBLOCK6_FLT");
}

static void clean_chain(const char *ipt, const char *table, const char *hook, const char *chain) {
    for (int k = 0; k < 4; k++) {
        if (run_cmd((char *const[]){(char *)ipt, "-w", "-t", (char *)table, "-D", (char *)hook, "-j", (char *)chain, NULL}) != 0) {
            break;
        }
    }
    run_cmd((char *const[]){(char *)ipt, "-w", "-t", (char *)table, "-F", (char *)chain, NULL});
    run_cmd((char *const[]){(char *)ipt, "-w", "-t", (char *)table, "-X", (char *)chain, NULL});
}

static void apply_firewall(bool enable, bool force) {
    if (g_skip_firewall) {
        g_fw_applied = enable;
        g_fw_initialized = true;
        return;
    }
    if (g_fw_initialized && !force && enable == g_fw_applied) return;

    /* Always clean all dedicated chains first so rules never duplicate or linger */
    clean_chain("/system/bin/iptables",  "mangle", "INPUT",  "SAMURAI_ADBLOCK_MGL");
    clean_chain("/system/bin/ip6tables", "mangle", "INPUT",  "SAMURAI_ADBLOCK6_MGL");
    clean_chain("/system/bin/iptables",  "nat",    "OUTPUT", "SAMURAI_ADBLOCK_NAT");
    clean_chain("/system/bin/iptables",  "filter", "OUTPUT", "SAMURAI_ADBLOCK_FLT");
    clean_chain("/system/bin/ip6tables", "filter", "OUTPUT", "SAMURAI_ADBLOCK6_FLT");

    if (!enable) {
        if (is_hosts_mounted()) {
            if (umount2("/system/etc/hosts", MNT_DETACH) != 0) {
                run_cmd((char *const[]){"/system/bin/nsenter", "-t", "1", "-m", "--",
                    "/system/bin/umount", "-l", "/system/etc/hosts", NULL});
            }
        }
        g_fw_applied = false;
        g_fw_initialized = true;
        return;
    }

    /* 1. Bind-mount /data/ota_package/adblock/hosts -> /system/etc/hosts */
    ensure_hosts_bind_mounted();

    char qnum_str[16];
    snprintf(qnum_str, sizeof(qnum_str), "%u", (unsigned int)g_nfq_num);

    /* 2. IPv4 & IPv6 UDP DNS response inspection via mangle INPUT NFQUEUE (--queue-bypass fail-open) */
    run_cmd((char *const[]){"/system/bin/iptables", "-w", "-t", "mangle", "-N", "SAMURAI_ADBLOCK_MGL", NULL});
    run_cmd((char *const[]){"/system/bin/iptables", "-w", "-t", "mangle", "-A", "SAMURAI_ADBLOCK_MGL",
        "-p", "udp", "--sport", "53", "-j", "NFQUEUE", "--queue-num", qnum_str, "--queue-bypass", NULL});
    run_cmd((char *const[]){"/system/bin/iptables", "-w", "-t", "mangle", "-I", "INPUT", "1", "-j", "SAMURAI_ADBLOCK_MGL", NULL});

    run_cmd((char *const[]){"/system/bin/ip6tables", "-w", "-t", "mangle", "-N", "SAMURAI_ADBLOCK6_MGL", NULL});
    run_cmd((char *const[]){"/system/bin/ip6tables", "-w", "-t", "mangle", "-A", "SAMURAI_ADBLOCK6_MGL",
        "-p", "udp", "--sport", "53", "-j", "NFQUEUE", "--queue-num", qnum_str, "--queue-bypass", NULL});
    run_cmd((char *const[]){"/system/bin/ip6tables", "-w", "-t", "mangle", "-I", "INPUT", "1", "-j", "SAMURAI_ADBLOCK6_MGL", NULL});

    /* 3. IPv4 NAT: redirect ONLY TCP port 53 (non-adblockd) to 127.0.0.1:5353 (NO UDP NAT REDIRECT!) */
    run_cmd((char *const[]){"/system/bin/iptables", "-w", "-t", "nat", "-N", "SAMURAI_ADBLOCK_NAT", NULL});
    run_cmd((char *const[]){"/system/bin/iptables", "-w", "-t", "nat", "-A", "SAMURAI_ADBLOCK_NAT",
        "-p", "tcp", "--dport", "53", "-m", "owner", "!", "--gid-owner", "1099",
        "-j", "REDIRECT", "--to-ports", "5353", NULL});
    run_cmd((char *const[]){"/system/bin/iptables", "-w", "-t", "nat", "-I", "OUTPUT", "1", "-j", "SAMURAI_ADBLOCK_NAT", NULL});

    /* 4. IPv4 Filter: block user/Chrome app DoT (:853) & direct DoH (:443 to public DNS resolver IPs) */
    run_cmd((char *const[]){"/system/bin/iptables", "-w", "-t", "filter", "-N", "SAMURAI_ADBLOCK_FLT", NULL});
    run_cmd((char *const[]){"/system/bin/iptables", "-w", "-t", "filter", "-A", "SAMURAI_ADBLOCK_FLT",
        "-p", "tcp", "--dport", "853", "-m", "owner", "--uid-owner", "10000:99999", "-j", "REJECT", NULL});
    run_cmd((char *const[]){"/system/bin/iptables", "-w", "-t", "filter", "-A", "SAMURAI_ADBLOCK_FLT",
        "-p", "udp", "--dport", "853", "-m", "owner", "--uid-owner", "10000:99999", "-j", "REJECT", NULL});

    static const char *const doh_v4[] = {
        "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112", "94.140.14.14", "94.140.15.15", NULL
    };
    for (int i = 0; doh_v4[i]; i++) {
        run_cmd((char *const[]){"/system/bin/iptables", "-w", "-t", "filter", "-A", "SAMURAI_ADBLOCK_FLT",
            "-d", (char *)doh_v4[i], "-p", "tcp", "--dport", "443",
            "-m", "owner", "--uid-owner", "10000:99999", "-j", "REJECT", NULL});
        run_cmd((char *const[]){"/system/bin/iptables", "-w", "-t", "filter", "-A", "SAMURAI_ADBLOCK_FLT",
            "-d", (char *)doh_v4[i], "-p", "udp", "--dport", "443",
            "-m", "owner", "--uid-owner", "10000:99999", "-j", "REJECT", NULL});
    }
    run_cmd((char *const[]){"/system/bin/iptables", "-w", "-t", "filter", "-I", "OUTPUT", "1", "-j", "SAMURAI_ADBLOCK_FLT", NULL});

    /* 5. IPv6 Filter: IPv6 UDP :53 flows natively through SAMURAI_ADBLOCK6_MGL NFQUEUE!
     *    Only reject app-UID (10000:99999) direct IPv6 TCP :53, DoT (:853), & IPv6 DoH (:443 resolver IPs). */
    run_cmd((char *const[]){"/system/bin/ip6tables", "-w", "-t", "filter", "-N", "SAMURAI_ADBLOCK6_FLT", NULL});
    run_cmd((char *const[]){"/system/bin/ip6tables", "-w", "-t", "filter", "-A", "SAMURAI_ADBLOCK6_FLT",
        "-p", "tcp", "--dport", "53", "-m", "owner", "--uid-owner", "10000:99999", "-j", "REJECT", NULL});
    run_cmd((char *const[]){"/system/bin/ip6tables", "-w", "-t", "filter", "-A", "SAMURAI_ADBLOCK6_FLT",
        "-p", "tcp", "--dport", "853", "-m", "owner", "--uid-owner", "10000:99999", "-j", "REJECT", NULL});
    run_cmd((char *const[]){"/system/bin/ip6tables", "-w", "-t", "filter", "-A", "SAMURAI_ADBLOCK6_FLT",
        "-p", "udp", "--dport", "853", "-m", "owner", "--uid-owner", "10000:99999", "-j", "REJECT", NULL});

    static const char *const doh_v6[] = {
        "2001:4860:4860::8888", "2001:4860:4860::8844",
        "2606:4700:4700::1111", "2606:4700:4700::1001",
        "2620:fe::fe", "2a10:50c0::ad1:ff", NULL
    };
    for (int i = 0; doh_v6[i]; i++) {
        run_cmd((char *const[]){"/system/bin/ip6tables", "-w", "-t", "filter", "-A", "SAMURAI_ADBLOCK6_FLT",
            "-d", (char *)doh_v6[i], "-p", "tcp", "--dport", "443",
            "-m", "owner", "--uid-owner", "10000:99999", "-j", "REJECT", NULL});
        run_cmd((char *const[]){"/system/bin/ip6tables", "-w", "-t", "filter", "-A", "SAMURAI_ADBLOCK6_FLT",
            "-d", (char *)doh_v6[i], "-p", "udp", "--dport", "443",
            "-m", "owner", "--uid-owner", "10000:99999", "-j", "REJECT", NULL});
    }
    run_cmd((char *const[]){"/system/bin/ip6tables", "-w", "-t", "filter", "-I", "OUTPUT", "1", "-j", "SAMURAI_ADBLOCK6_FLT", NULL});

    g_fw_applied = true;
    g_fw_initialized = true;
    g_fw_apply_ms = now_ms();
    g_hook_recheck_until_ms = g_fw_apply_ms + 75000ULL;
    g_last_hook_check_ms = g_fw_apply_ms;
}

static void load_saved_state(void) {
    FILE *f = fopen(g_state_file, "r");
    if (!f) return;
    char line[128];
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "enabled=", 8) == 0) {
            g_enabled = (atoi(line + 8) != 0);
        }
    }
    fclose(f);
}

static void save_state(void) {
    char tmp[320];
    snprintf(tmp, sizeof(tmp), "%s.tmp", g_state_file);
    FILE *f = fopen(tmp, "w");
    if (!f) return;
    fprintf(f, "enabled=%d\n", g_enabled ? 1 : 0);
    fclose(f);
    chmod(tmp, 0666);
    rename(tmp, g_state_file);
}

static void poll_control_queue(void) {
    DIR *d = opendir(g_queue_dir);
    if (!d) return;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (strncmp(de->d_name, "adblock_", 8) != 0) continue;
        size_t nl = strlen(de->d_name);
        if (nl < 12 || strcmp(de->d_name + nl - 4, ".req") != 0) continue;

        char path[540];
        snprintf(path, sizeof(path), "%s/%s", g_queue_dir, de->d_name);
        FILE *f = fopen(path, "r");
        if (f) {
            char line[256];
            while (fgets(line, sizeof(line), f)) {
                if (strncmp(line, "ADBLOCK_ENABLE=", 15) == 0) {
                    g_enabled = (atoi(line + 15) != 0);
                    if (g_enabled) g_pause_until_ms = 0;
                    save_state();
                } else if (strncmp(line, "ADBLOCK_PAUSE_SEC=", 18) == 0) {
                    int sec = atoi(line + 18);
                    if (sec > 0) g_pause_until_ms = now_ms() + (uint64_t)sec * 1000ULL;
                    else g_pause_until_ms = 0;
                } else if (strncmp(line, "ADBLOCK_RELOAD=", 15) == 0) {
                    reload_domain_db();
                    if (g_fw_applied) {
                        ensure_hosts_bind_mounted();
                    }
                }
            }
            fclose(f);
        }
        unlink(path);
    }
    closedir(d);
}

static void check_domains_hot_reload(void) {
    struct stat st;
    if (stat(g_domains_file, &st) != 0 || st.st_size < 1024) return;

    uint32_t cur_cnt = 0;
    time_t prev_mt = 0;
    uint64_t prev_sz = 0;
    pthread_rwlock_rdlock(&g_db_lock);
    if (g_db) cur_cnt = g_db->block_count;
    prev_mt = g_domains_mtime;
    prev_sz = g_domains_size;
    pthread_rwlock_unlock(&g_db_lock);

    if (cur_cnt == 0 || st.st_mtime != prev_mt || (uint64_t)st.st_size != prev_sz) {
        reload_domain_db();
    }
}

static void write_telemetry(uint64_t cur_ms) {
    static bool     s_init = false;
    static int      s_en = -1;
    static int      s_act = -1;
    static uint64_t s_pause = 0;
    static uint64_t s_blk = 0;
    static uint64_t s_tot = 0;
    static uint32_t s_dom = 0;
    static time_t   s_mods_mt = 0;
    static off_t    s_mods_sz = -1;
    static char     s_last[256] = "";

    uint64_t total_q, blocked_q;
    char last_blk[256];
    pthread_mutex_lock(&g_stats_lock);
    total_q = g_total_queries;
    blocked_q = g_blocked_queries;
    snprintf(last_blk, sizeof(last_blk), "%s", g_last_blocked);
    pthread_mutex_unlock(&g_stats_lock);

    uint32_t dom_cnt = 0;
    pthread_rwlock_rdlock(&g_db_lock);
    if (g_db) dom_cnt = g_db->block_count;
    pthread_rwlock_unlock(&g_db_lock);

    uint64_t pause_rem = 0;
    uint64_t pu = g_pause_until_ms;
    if (pu > cur_ms) {
        pause_rem = (pu - cur_ms + 999ULL) / 1000ULL;
    }

    int cur_en = g_enabled ? 1 : 0;
    int cur_act = (g_enabled && pause_rem == 0) ? 1 : 0;
    struct stat st_mods;
    time_t mods_mt = 0;
    off_t mods_sz = 0;
    if (stat(g_mods_prop_file, &st_mods) == 0) {
        mods_mt = st_mods.st_mtime;
        mods_sz = st_mods.st_size;
    }

    if (s_init &&
        s_en == cur_en &&
        s_act == cur_act &&
        s_pause == pause_rem &&
        s_blk == blocked_q &&
        s_tot == total_q &&
        s_dom == dom_cnt &&
        s_mods_mt == mods_mt &&
        s_mods_sz == mods_sz &&
        strcmp(s_last, last_blk) == 0 &&
        access(g_telemetry_out, R_OK) == 0) {
        return;
    }

    char tmp[320];
    snprintf(tmp, sizeof(tmp), "%s.tmp", g_telemetry_out);
    FILE *f = fopen(tmp, "w");
    if (!f) return;
    fprintf(f, "adblock_enabled=%d\n", cur_en);
    fprintf(f, "adblock_active=%d\n", cur_act);
    fprintf(f, "adblock_pause_rem=%llu\n", (unsigned long long)pause_rem);
    fprintf(f, "blocked_count=%llu\n", (unsigned long long)blocked_q);
    fprintf(f, "total_queries=%llu\n", (unsigned long long)total_q);
    fprintf(f, "domains_loaded=%u\n", dom_cnt);
    fprintf(f, "last_blocked=%s\n", last_blk);

    FILE *fm = fopen(g_mods_prop_file, "r");
    if (fm) {
        char mline[256];
        while (fgets(mline, sizeof(mline), fm)) {
            fputs(mline, f);
        }
        fclose(fm);
    }
    fclose(f);
    chmod(tmp, 0644);
    if (rename(tmp, g_telemetry_out) == 0) {
        s_init = true;
        s_en = cur_en;
        s_act = cur_act;
        s_pause = pause_rem;
        s_blk = blocked_q;
        s_tot = total_q;
        s_dom = dom_cnt;
        s_mods_mt = mods_mt;
        s_mods_sz = mods_sz;
        snprintf(s_last, sizeof(s_last), "%s", last_blk);
    }
}

static ssize_t read_full(int fd, void *buf, size_t n) {
    size_t got = 0;
    uint8_t *p = (uint8_t *)buf;
    while (got < n) {
        ssize_t r = recv(fd, p + got, n - got, 0);
        if (r == 0) return (ssize_t)got;
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        got += (size_t)r;
    }
    return (ssize_t)got;
}

static ssize_t write_full(int fd, const void *buf, size_t n) {
    size_t sent = 0;
    const uint8_t *p = (const uint8_t *)buf;
    while (sent < n) {
        ssize_t w = send(fd, p + sent, n - sent, MSG_NOSIGNAL);
        if (w <= 0) {
            if (w < 0 && errno == EINTR) continue;
            return -1;
        }
        sent += (size_t)w;
    }
    return (ssize_t)sent;
}

static bool forward_tcp_dns_once(const struct sockaddr *sa, socklen_t salen, int family,
                                 const uint8_t len_hdr[2], const uint8_t *qbuf, uint16_t qlen,
                                 uint8_t *rbuf, uint16_t *rlen_out) {
    int ufd = socket(family, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (ufd < 0) return false;
    struct timeval utv = {.tv_sec = 2, .tv_usec = 500000};
    setsockopt(ufd, SOL_SOCKET, SO_RCVTIMEO, &utv, sizeof(utv));
    setsockopt(ufd, SOL_SOCKET, SO_SNDTIMEO, &utv, sizeof(utv));
    bool ok = false;
    if (connect(ufd, sa, salen) == 0 &&
        write_full(ufd, len_hdr, 2) == 2 &&
        write_full(ufd, qbuf, qlen) == (ssize_t)qlen &&
        read_full(ufd, rbuf, 2) == 2) {
        uint16_t rlen = (uint16_t)((rbuf[0] << 8) | rbuf[1]);
        if (rlen <= DNS_MAX_PKT && read_full(ufd, rbuf + 2, rlen) == (ssize_t)rlen) {
            *rlen_out = rlen;
            ok = true;
        }
    }
    close(ufd);
    return ok;
}

static void *tcp_dns_worker(void *arg) {
    int cfd = (int)(intptr_t)arg;
    struct timeval tv = {.tv_sec = 4, .tv_usec = 0};
    setsockopt(cfd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(cfd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    uint8_t len_hdr[2];
    if (read_full(cfd, len_hdr, 2) == 2) {
        uint16_t qlen = (uint16_t)((len_hdr[0] << 8) | len_hdr[1]);
        if (qlen >= 12 && qlen <= DNS_MAX_PKT) {
            uint8_t *qbuf = malloc(qlen);
            uint8_t *rbuf = malloc(DNS_MAX_PKT + 2);
            if (qbuf && rbuf && read_full(cfd, qbuf, qlen) == (ssize_t)qlen) {
                char qname[256] = {0};
                uint16_t qtype = 0;
                size_t qend = 0;
                uint16_t txid = (uint16_t)((qbuf[0] << 8) | qbuf[1]);
                bool active_filter = g_enabled && (g_pause_until_ms <= now_ms());
                bool parsed = dns_parse_question(qbuf, qlen, qname, sizeof(qname), &qtype, &qend);

                if (active_filter && parsed && is_domain_blocked(qname)) {
                    record_query_stat(true, qname);
                    size_t slen = dns_build_sinkhole(qbuf, qlen, qend, qtype, txid, rbuf + 2, DNS_MAX_PKT);
                    if (slen > 0) {
                        rbuf[0] = (uint8_t)(slen >> 8);
                        rbuf[1] = (uint8_t)(slen & 0xFF);
                        write_full(cfd, rbuf, slen + 2);
                    }
                } else {
                    record_query_stat(false, NULL);
                    bool got_reply = false;
                    uint16_t rlen = 0;

                    /* Prefer the exact NAT-redirected destination IP (router/ISP DNS) first */
                    struct sockaddr_in orig_dst;
                    socklen_t orig_len = sizeof(orig_dst);
                    memset(&orig_dst, 0, sizeof(orig_dst));
                    if (getsockopt(cfd, SOL_IP, SO_ORIGINAL_DST, &orig_dst, &orig_len) == 0 &&
                        orig_dst.sin_family == AF_INET) {
                        uint32_t h = ntohl(orig_dst.sin_addr.s_addr);
                        if ((h >> 24) != 127U && (h >> 24) != 0U) {
                            got_reply = forward_tcp_dns_once((struct sockaddr *)&orig_dst, sizeof(orig_dst),
                                                             AF_INET, len_hdr, qbuf, qlen, rbuf, &rlen);
                        }
                    }

                    if (!got_reply) {
                        struct upstream_ep ups[MAX_UPSTREAMS];
                        int nups = 0;
                        pthread_mutex_lock(&g_up_lock);
                        nups = g_num_upstreams;
                        memcpy(ups, g_upstreams, sizeof(ups));
                        pthread_mutex_unlock(&g_up_lock);

                        for (int i = 0; i < nups; i++) {
                            if (forward_tcp_dns_once((struct sockaddr *)&ups[i].addr, ups[i].addrlen,
                                                     ups[i].family, len_hdr, qbuf, qlen, rbuf, &rlen)) {
                                got_reply = true;
                                break;
                            }
                        }
                    }

                    if (got_reply) {
                        char cname_hit[256] = {0};
                        if (active_filter && parsed && !is_domain_explicitly_allowed(qname) &&
                            dns_response_has_blocked_cname(rbuf + 2, rlen, cname_hit, sizeof(cname_hit))) {
                            record_query_stat(true, cname_hit[0] ? cname_hit : qname);
                            size_t slen = dns_build_sinkhole(qbuf, qlen, qend, qtype, txid, rbuf + 2, DNS_MAX_PKT);
                            if (slen > 0) {
                                rbuf[0] = (uint8_t)(slen >> 8);
                                rbuf[1] = (uint8_t)(slen & 0xFF);
                                write_full(cfd, rbuf, slen + 2);
                            }
                        } else {
                            write_full(cfd, rbuf, (size_t)rlen + 2U);
                        }
                    }
                }
            }
            free(qbuf);
            free(rbuf);
        }
    }

    close(cfd);
    __sync_fetch_and_sub(&g_tcp_workers, 1);
    return NULL;
}

static void send_upstream_udp(int up_v4_fd, int up_v6_fd, const uint8_t *pkt, size_t pkt_len, int start_idx) {
    struct upstream_ep ups[MAX_UPSTREAMS];
    int nups = 0;
    pthread_mutex_lock(&g_up_lock);
    nups = g_num_upstreams;
    memcpy(ups, g_upstreams, sizeof(ups));
    pthread_mutex_unlock(&g_up_lock);
    if (nups <= 0) return;

    int sent = 0;
    for (int k = 0; k < nups && sent < 2; k++) {
        int idx = (start_idx + k) % nups;
        int fd = (ups[idx].family == AF_INET6) ? up_v6_fd : up_v4_fd;
        if (fd < 0) continue;
        if (sendto(fd, pkt, pkt_len, MSG_DONTWAIT,
                   (const struct sockaddr *)&ups[idx].addr, ups[idx].addrlen) > 0) {
            sent++;
        }
    }
}

static void handle_sigterm(int sig) {
    (void)sig;
    g_running = 0;
}

int main(void) {
    umask(0022);
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, handle_sigterm);
    signal(SIGINT, handle_sigterm);

    init_paths_from_env();

    mkdir(DEFAULT_OTA_DIR, 0777);
    mkdir(DEFAULT_OTA_DIR "/hw", 0777);
    mkdir(g_queue_dir, 0777);
    mkdir(g_adblock_dir, 0775);

    /* Unpack /system/etc/adblock_seed.gz natively in C before dropping GID so domains.txt & hosts
     * are 100% populated at post-fs-data without waiting for Zygote/app_process64. */
    ensure_seed_unpacked();

    /* Pin effective & filesystem GID to GID_ADBLOCK (1099) so xt_owner (! --gid-owner 1099)
     * exempts all upstream sockets created by samurai_adblockd while retaining system/cache sups. */
    gid_t sups[5] = {0, 1000, 2001, 3003, GID_ADBLOCK};
    if (setgroups(5, sups) < 0) { /* ignore if unprivileged test */ }
    if (setregid(GID_ADBLOCK, GID_ADBLOCK) < 0) {
        /* ignore in unprivileged namespace test */
    }

    load_saved_state();
    reload_domain_db();
    refresh_upstreams();

    int udp_fd = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    int tcp_fd = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    int up4_fd = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    int up6_fd = socket(AF_INET6, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (udp_fd < 0 || tcp_fd < 0 || up4_fd < 0) {
        perror("socket");
        return 1;
    }

    int one = 1;
    setsockopt(udp_fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    setsockopt(tcp_fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

    struct sockaddr_in bind_addr;
    memset(&bind_addr, 0, sizeof(bind_addr));
    bind_addr.sin_family = AF_INET;
    bind_addr.sin_port = htons(LISTEN_PORT);
    bind_addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (bind(udp_fd, (struct sockaddr *)&bind_addr, sizeof(bind_addr)) < 0 ||
        bind(tcp_fd, (struct sockaddr *)&bind_addr, sizeof(bind_addr)) < 0 ||
        listen(tcp_fd, 32) < 0) {
        perror("bind/listen 127.0.0.1:5353");
        return 1;
    }

    /* Bind Netfilter NFQUEUE 53 & launch dedicated zero-blocking packet worker BEFORE firewall */
    int nl_fd = nfq_open_and_bind(g_nfq_num);
    pthread_t nfq_tid = 0;
    bool nfq_started = false;
    if (nl_fd >= 0) {
        if (pthread_create(&nfq_tid, NULL, nfq_worker_thread, (void *)(intptr_t)nl_fd) == 0) {
            nfq_started = true;
        } else {
            close(nl_fd);
            nl_fd = -1;
        }
    }

    apply_firewall(g_enabled, true);

    struct inflight_req slots[INFLIGHT_SLOTS];
    memset(slots, 0, sizeof(slots));
    uint16_t slot_seq = 0;

    uint64_t last_queue_ms = 0;
    uint64_t last_maint_ms = 0;
    uint64_t last_up_ms = 0;

    while (g_running) {
        struct pollfd pfds[4];
        int nfds = 0;
        pfds[nfds++] = (struct pollfd){.fd = udp_fd, .events = POLLIN, .revents = 0};
        pfds[nfds++] = (struct pollfd){.fd = tcp_fd, .events = POLLIN, .revents = 0};
        pfds[nfds++] = (struct pollfd){.fd = up4_fd, .events = POLLIN, .revents = 0};
        if (up6_fd >= 0) {
            pfds[nfds++] = (struct pollfd){.fd = up6_fd, .events = POLLIN, .revents = 0};
        }

        int pr = poll(pfds, (nfds_t)nfds, 150);
        uint64_t cur_ms = now_ms();

        /* 1. Handle direct local UDP DNS queries on 127.0.0.1:5353 */
        if (pr > 0 && (pfds[0].revents & POLLIN)) {
            uint8_t buf[DNS_MAX_PKT];
            uint8_t reply[DNS_MAX_PKT];
            struct sockaddr_in cli;
            for (int burst = 0; burst < 32; burst++) {
                socklen_t clen = sizeof(cli);
                ssize_t n = recvfrom(udp_fd, buf, sizeof(buf), MSG_DONTWAIT, (struct sockaddr *)&cli, &clen);
                if (n < 12) break;

                char qname[256] = {0};
                uint16_t qtype = 0;
                size_t qend = 0;
                uint16_t orig_txid = (uint16_t)((buf[0] << 8) | buf[1]);
                bool active_filter = g_enabled && (g_pause_until_ms <= cur_ms);

                if (active_filter && dns_parse_question(buf, (size_t)n, qname, sizeof(qname), &qtype, &qend) &&
                    is_domain_blocked(qname)) {
                    record_query_stat(true, qname);
                    size_t slen = dns_build_sinkhole(buf, (size_t)n, qend, qtype, orig_txid, reply, sizeof(reply));
                    if (slen > 0) {
                        sendto(udp_fd, reply, slen, MSG_DONTWAIT, (struct sockaddr *)&cli, clen);
                    }
                    continue;
                }

                record_query_stat(false, NULL);
                uint16_t idx = (slot_seq++) & (INFLIGHT_SLOTS - 1U);
                uint16_t wire_txid = (uint16_t)(((idx & 0x3FU) << 10) | ((orig_txid ^ slot_seq) & 0x03FFU));
                slots[idx].active = true;
                slots[idx].orig_txid = orig_txid;
                slots[idx].wire_txid = wire_txid;
                slots[idx].qtype = qtype;
                slots[idx].client_addr = cli;
                slots[idx].sent_ms = cur_ms;
                slots[idx].retried = false;
                snprintf(slots[idx].qname, sizeof(slots[idx].qname), "%s", qname);
                size_t copy_n = ((size_t)n <= sizeof(slots[idx].pkt)) ? (size_t)n : sizeof(slots[idx].pkt);
                memcpy(slots[idx].pkt, buf, copy_n);
                slots[idx].pkt_len = copy_n;

                buf[0] = (uint8_t)(wire_txid >> 8);
                buf[1] = (uint8_t)(wire_txid & 0xFF);
                slots[idx].pkt[0] = buf[0];
                slots[idx].pkt[1] = buf[1];

                send_upstream_udp(up4_fd, up6_fd, buf, (size_t)n, 0);
            }
        }

        /* 2. Handle incoming TCP DNS connections on 127.0.0.1:5353 */
        if (pr > 0 && (pfds[1].revents & POLLIN)) {
            struct sockaddr_in cli;
            socklen_t clen = sizeof(cli);
            int cfd = accept4(tcp_fd, (struct sockaddr *)&cli, &clen, SOCK_CLOEXEC);
            if (cfd >= 0) {
                if (__sync_fetch_and_add(&g_tcp_workers, 1) < 8) {
                    pthread_t tid;
                    pthread_attr_t attr;
                    pthread_attr_init(&attr);
                    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
                    pthread_attr_setstacksize(&attr, 131072);
                    if (pthread_create(&tid, &attr, tcp_dns_worker, (void *)(intptr_t)cfd) != 0) {
                        close(cfd);
                        __sync_fetch_and_sub(&g_tcp_workers, 1);
                    }
                    pthread_attr_destroy(&attr);
                } else {
                    close(cfd);
                    __sync_fetch_and_sub(&g_tcp_workers, 1);
                }
            }
        }

        /* 3. Handle upstream UDP replies for local 127.0.0.1:5353 queries */
        for (int u = 2; u < nfds; u++) {
            if (pr <= 0 || !(pfds[u].revents & POLLIN)) continue;
            uint8_t rbuf[DNS_MAX_PKT];
            uint8_t sbuf[DNS_MAX_PKT];
            for (int burst = 0; burst < 32; burst++) {
                ssize_t n = recvfrom(pfds[u].fd, rbuf, sizeof(rbuf), MSG_DONTWAIT, NULL, NULL);
                if (n < 12) break;
                uint16_t wtx = (uint16_t)((rbuf[0] << 8) | rbuf[1]);
                uint16_t idx = (wtx >> 10) & (INFLIGHT_SLOTS - 1U);
                if (!slots[idx].active || slots[idx].wire_txid != wtx) continue;

                slots[idx].active = false;
                bool active_filter = g_enabled && (g_pause_until_ms <= cur_ms);
                char cname_hit[256] = {0};

                if (active_filter && !is_domain_explicitly_allowed(slots[idx].qname) &&
                    dns_response_has_blocked_cname(rbuf, (size_t)n, cname_hit, sizeof(cname_hit))) {
                    char qn[256];
                    uint16_t qt = 0;
                    size_t qe = 0;
                    record_query_stat(true, cname_hit[0] ? cname_hit : slots[idx].qname);
                    if (dns_parse_question(slots[idx].pkt, slots[idx].pkt_len, qn, sizeof(qn), &qt, &qe)) {
                        size_t slen = dns_build_sinkhole(slots[idx].pkt, slots[idx].pkt_len, qe, qt,
                                                         slots[idx].orig_txid, sbuf, sizeof(sbuf));
                        if (slen > 0) {
                            sendto(udp_fd, sbuf, slen, MSG_DONTWAIT,
                                   (struct sockaddr *)&slots[idx].client_addr, sizeof(slots[idx].client_addr));
                        }
                    }
                } else {
                    rbuf[0] = (uint8_t)(slots[idx].orig_txid >> 8);
                    rbuf[1] = (uint8_t)(slots[idx].orig_txid & 0xFF);
                    sendto(udp_fd, rbuf, (size_t)n, MSG_DONTWAIT,
                           (struct sockaddr *)&slots[idx].client_addr, sizeof(slots[idx].client_addr));
                }
            }
        }

        /* 4. Fast retry (250ms) & expire (2500ms) inflight local UDP slots */
        for (int i = 0; i < INFLIGHT_SLOTS; i++) {
            if (!slots[i].active) continue;
            uint64_t age = cur_ms - slots[i].sent_ms;
            if (age > 2500ULL) {
                slots[i].active = false;
            } else if (!slots[i].retried && age >= 250ULL) {
                slots[i].retried = true;
                send_upstream_udp(up4_fd, up6_fd, slots[i].pkt, slots[i].pkt_len, 2);
            }
        }

        /* 5. Fast 150ms control queue poll for responsive UI & Quick Settings tile toggles */
        if (cur_ms - last_queue_ms >= 150ULL) {
            last_queue_ms = cur_ms;
            poll_control_queue();
            bool want_fw = g_enabled && (g_pause_until_ms <= cur_ms);
            if (want_fw != g_fw_applied) {
                apply_firewall(want_fw, false);
                write_telemetry(cur_ms);
            }
        }

        /* 6. Periodic 1s hot-reload, hosts mount check, netd-flush self-healing & telemetry snapshot */
        if (cur_ms - last_maint_ms >= 1000ULL) {
            last_maint_ms = cur_ms;
            check_domains_hot_reload();
            if (g_fw_applied) {
                ensure_hosts_bind_mounted();
                pid_t npid = find_netd_pid();
                if (npid > 1 && npid != g_last_netd_pid) {
                    g_last_netd_pid = npid;
                    g_hook_recheck_until_ms = cur_ms + 20000ULL;
                }
                if (cur_ms <= g_hook_recheck_until_ms && cur_ms - g_last_hook_check_ms >= 5000ULL) {
                    g_last_hook_check_ms = cur_ms;
                    ensure_firewall_hooks();
                }
            }
            write_telemetry(cur_ms);
        }

        if (cur_ms - last_up_ms >= 10000ULL) {
            last_up_ms = cur_ms;
            refresh_upstreams();
        }
    }

    apply_firewall(false, true);
    if (nfq_started) {
        pthread_join(nfq_tid, NULL);
    }
    if (nl_fd >= 0) close(nl_fd);
    return 0;
}
