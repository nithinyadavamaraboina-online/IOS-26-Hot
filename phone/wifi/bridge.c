/*
 * The kernel's Wi-Fi station over /dev/wlan (flamewlan.h; the kernel side is bsd/dev/flame_wlan.c, under en0
 * and wifid's Apple80211 requests): wifid's scans and joins come here as requests, en0's frames both ways.
 * In this mode iOS does the IP side itself on en0 (configd, DHCP), so ipnet.c's utun is not made. Without the
 * device (an older kernel, or boot-arg sweetwifid=0) wland keeps its own utun.
 */
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/sysctl.h>
#include "flamewlan.h"
#include "link.h"
#include "radio.h"
#include "bridge.h"

static int bfd = -1;
static int hello_sent, configd_checked;
static int scan_pending, join_pending;
static unsigned scans_at;
static uint64_t hello_ms, scan_deadline, join_deadline, last_bg_scan;
int bridge_joins;	/* wifid has asked for a network (or to leave): wland's own wlan.conf join stands down */

#define SC_PREFS	"/private/var/preferences/SystemConfiguration/preferences.plist"
#define SC_NETIF	"/private/var/preferences/SystemConfiguration/NetworkInterfaces.plist"

static void bridge_console(const char *msg)
{
	int cfd = open("/dev/console", O_WRONLY | O_NOCTTY);
	if (cfd >= 0) {
		(void)write(cfd, msg, strlen(msg));
		close(cfd);
	}
}

static int has_ascii(const uint8_t *buf, size_t len, const char *needle)
{
	size_t nlen = strlen(needle);
	if (!nlen || len < nlen)
		return 0;
	for (size_t i = 0; i + nlen <= len; i++) {
		if (!memcmp(buf + i, needle, nlen))
			return 1;
	}
	return 0;
}

/*
 * configd starts at boot (~13 s) before wland finishes booting the modem + WCN3990 (~22 s), so on the
 * first boot without Wi-Fi it writes a SystemConfiguration preferences.plist with no en0 NetworkService.
 * Once FW_HELLO has published en0 + RedwoodWLAN in IORegistry, check preferences.plist and, if en0 is
 * missing, move the stale pre-Wi-Fi plist aside and SIGTERM configd so launchd respawns it with en0 live.
 */
static void ensure_en0_configd(void)
{
	static uint8_t buf[65536];
	int has_en0 = 0;
	int fd = open(SC_PREFS, O_RDONLY);
	if (fd >= 0) {
		ssize_t n = read(fd, buf, sizeof(buf));
		close(fd);
		if (n > 0 && has_ascii(buf, (size_t)n, "en0"))
			has_en0 = 1;
	}
	if (has_en0) {
		printf("[%6llu] bridge: SystemConfiguration already has en0\n", (unsigned long long)link_ms());
		return;
	}
	if (fd >= 0) {
		(void)rename(SC_PREFS, SC_PREFS ".pre-wifi");
		(void)rename(SC_NETIF, SC_NETIF ".pre-wifi");
	}
	int mib[4] = { CTL_KERN, KERN_PROC, KERN_PROC_ALL, 0 };
	size_t len = 0;
	int killed = 0;
	if (sysctl(mib, 3, NULL, &len, NULL, 0) == 0 && len > 0) {
		len += len / 4;
		struct kinfo_proc *p = malloc(len);
		if (p && sysctl(mib, 3, p, &len, NULL, 0) == 0) {
			int count = (int)(len / sizeof(*p));
			for (int i = 0; i < count; i++) {
				if (!strcmp(p[i].kp_proc.p_comm, "configd") &&
				    kill(p[i].kp_proc.p_pid, SIGTERM) == 0)
					killed++;
			}
		}
		free(p);
	}
	printf("[%6llu] bridge: seeded en0 in SystemConfiguration (killed %d configd)\n",
	       (unsigned long long)link_ms(), killed);
	bridge_console("wland: seeded en0 in SystemConfiguration (restarted configd)\n");
}

static void bridge_send(uint16_t type, const void *data, size_t len)
{
	uint8_t m[FW_MSG_MAX];
	struct fw_hdr h = { type, (uint16_t)len };
	if (bfd < 0 || len > sizeof(m) - sizeof(h))
		return;
	memcpy(m, &h, sizeof(h));
	if (len)
		memcpy(m + sizeof(h), data, len);
	if (write(bfd, m, sizeof(h) + len) < 0 && type != FW_RX)
		printf("[%6llu] bridge: write type %u: %s\n", (unsigned long long)link_ms(), type, strerror(errno));
}

static void bridge_status(uint16_t type, int32_t status)
{
	bridge_send(type, &status, sizeof(status));
}

static void bridge_rx(const uint8_t *frame, uint32_t len)
{
	link_activity++;
	if (len <= FW_FRAME_MAX)
		bridge_send(FW_RX, frame, len);
}

static void bridge_link(int up)
{
	struct fw_link l;
	memset(&l, 0, sizeof(l));
	l.up = (uint8_t)(up != 0);
	l.ssid_len = (uint8_t)radio_ssid(l.ssid, l.bssid);
	printf("[%6llu] bridge: link %s '%.*s'\n", (unsigned long long)link_ms(), up ? "up" : "down", l.ssid_len, l.ssid);
	bridge_send(FW_LINK, &l, sizeof(l));
}

/* what the scans heard (in the last minute), then SCAN_DONE */
static void bridge_list(int32_t status)
{
	static struct radio_bss r;
	static struct fw_bss b;
	unsigned n = 0;
	bridge_send(FW_BSS_BEGIN, NULL, 0);
	for (unsigned i = 0; radio_heard(i, &r) == 0; i++) {
		if (r.age_ms > 120000)
			continue;			/* not heard in the last two minutes */
		memset(&b, 0, sizeof(b));
		memcpy(b.bssid, r.bssid, 6);
		memcpy(b.ssid, r.ssid, r.ssid_len);
		b.ssid_len = r.ssid_len;
		b.frequency = r.freq;
		b.rssi = r.rssi;
		b.beacon_interval = r.bintval;
		b.capability = r.capab;
		b.age_ms = r.age_ms;
		b.elements_len = r.ie_len;
		memcpy(b.elements, r.ie, r.ie_len);
		bridge_send(FW_BSS, &b, sizeof(b));
		n++;
	}
	bridge_status(FW_SCAN_DONE, status);
	printf("[%6llu] bridge: scan done, %u networks for wifid\n", (unsigned long long)link_ms(), n);
}

int bridge_open(void)
{
	bfd = open("/dev/wlan", O_RDWR | O_NONBLOCK);
	if (bfd < 0) {
		printf("bridge: no /dev/wlan (%s): wland's own utun\n", strerror(errno));
		return -1;
	}
	radio_rx_hook = bridge_rx;
	radio_link_hook = bridge_link;
	printf("bridge: /dev/wlan open: en0 and wifid\n");
	return 0;
}

static void on_join(const struct fw_join *j)
{
	char pass[65] = "";
	char ssid[33];
	uint8_t cur[32];
	unsigned cl = radio_ssid(cur, NULL);
	bridge_joins = 1;
	memcpy(ssid, j->ssid, j->ssid_len);
	ssid[j->ssid_len] = 0;
	if (radio_joined() && cl == j->ssid_len && !memcmp(cur, j->ssid, cl)) {
		printf("[%6llu] bridge: join '%s': joined already\n", (unsigned long long)link_ms(), ssid);
		bridge_link(1);
		bridge_status(FW_JOIN_DONE, 0);
		return;
	}
	if (j->key_kind == FW_KEY_PASSPHRASE && j->key_len >= 8 && j->key_len <= 63) {
		memcpy(pass, j->key, j->key_len);
		pass[j->key_len] = 0;
	} else if (j->key_kind == FW_KEY_PMK && j->key_len == 32) {
		for (int i = 0; i < 32; i++)
			snprintf(pass + 2 * i, 3, "%02x", j->key[i]);	/* radio_join takes the PMK as 64 hex digits */
	}
	if (radio_joined())
		radio_disconnect();
	int r = radio_join(ssid, pass);
	printf("[%6llu] bridge: join '%s' (%s) -> %d\n", (unsigned long long)link_ms(), ssid,
	       j->key_kind == FW_KEY_PMK ? "PMK" : j->key_kind == FW_KEY_PASSPHRASE ? "passphrase" : "open", r);
	if (r) {
		bridge_status(FW_JOIN_DONE, r);
		return;
	}
	join_pending = 1;
	join_deadline = link_ms() + 35000;
}

/* from wland's idle loop */
void bridge_poll(void)
{
	static uint8_t m[FW_MSG_MAX];
	if (bfd < 0)
		return;
	if (!hello_sent && radio_scans() > 0) {
		struct fw_hello h;
		memcpy(h.address, radio_mac, 6);
		bridge_send(FW_HELLO, &h, sizeof(h));
		hello_sent = 1;
		hello_ms = link_ms();
		configd_checked = 0;
		printf("[%6llu] bridge: hello: the kernel makes en0\n", (unsigned long long)hello_ms);
		bridge_console("wland: FW_HELLO sent -> en0 live\n");
	}
	if (hello_sent && !configd_checked && link_ms() - hello_ms >= 800) {
		configd_checked = 1;
		ensure_en0_configd();
	}
	for (int n = 0; n < 32; n++) {
		ssize_t r = read(bfd, m, sizeof(m));
		if (r < (ssize_t)sizeof(struct fw_hdr))
			break;
		link_activity++;
		struct fw_hdr h;
		memcpy(&h, m, sizeof(h));
		const uint8_t *p = m + sizeof(h);
		switch (h.type) {
		case FW_SCAN:
			if (scan_pending)
				break;			/* the scan under way answers it */
			if (radio_joined() && link_ms() - last_bg_scan < 15000) {
				bridge_list(0);		/* joined: a background scan at most every 15 s; what is known */
				break;
			}
			scan_pending = 1;
			scans_at = radio_scans();
			scan_deadline = link_ms() + (radio_joined() ? 25000 : 12000);
			if (radio_joined()) {
				last_bg_scan = link_ms();
				radio_scan_now();
			} else {
				radio_rescan(0);
			}
			break;
		case FW_JOIN:
			if (h.len >= sizeof(struct fw_join))
				on_join((const struct fw_join *)p);
			else
				bridge_status(FW_JOIN_DONE, EINVAL);
			break;
		case FW_LEAVE:
			bridge_joins = 1;
			printf("[%6llu] bridge: leave\n", (unsigned long long)link_ms());
			radio_disconnect();
			bridge_status(FW_LEFT, 0);
			break;
		case FW_TX:
			radio_tx_ether(p, h.len);
			break;
		default:
			break;
		}
	}
	uint64_t now = link_ms();
	if (scan_pending && (radio_scans() > scans_at || now > scan_deadline)) {
		scan_pending = 0;
		bridge_list(0);			/* (after the deadline: what is known so far) */
	}
	if (join_pending) {
		int r = radio_join_result();
		if (r != -1 || now > join_deadline) {
			join_pending = 0;
			bridge_status(FW_JOIN_DONE, r == -1 ? ETIMEDOUT : r);
		}
	}
}

int bridge_active(void)
{
	return bfd >= 0;
}
