/*
 * flame (Pixel 4, SM8150) boot shim.
 *
 * ABL enters here at EL1 with the MMU and caches off and x0 = DTB. With an
 * XNU payload in the ramdisk (tools/mkpayload.py) the shim boots it;
 * without one it runs the hardware probe: everything it learns goes into
 * a text log that is written to the scratch area at the end of userdata,
 * and the phone is then reset into fastboot, so a run can be read back
 * from TWRP without anyone watching the screen.
 */
#include <stdint.h>
#include "console.h"
#include "fdt.h"
#include "loader.h"
#include "ramlog.h"
#include "log.h"
#include "sde.h"
#include "showlog.h"
#include "diag.h"
#include "disptest.h"
#include "gpuprobe.h"
#include "smmuprobe.h"
#include "spmi.h"
#include "ufs.h"
#include "util.h"

#define IMEM_RESTART_REASON	0x146bf65cUL	/* qcom,msm-imem restart_reason@65c */
#define RESTART_BOOTLOADER	0x77665500U
#define PON_SOFT_RB_SPARE	0x88f		/* PM8150 (SID 0) PON, reason in [7:1] */
#define PON_REASON_BOOTLOADER	0x02
#define PON_PS_HOLD_RESET_CTL	0x85a		/* [3:0] type */
#define PON_PS_HOLD_RESET_CTL2	0x85b		/* [7] enable */
#define PON_RESET_EN		0x80
#define PON_HARD_RESET		0x7
#define WDT_BASE		0x17c10000UL	/* qcom,wdt@17c10000 */
#define WDT_HZ			32765

#define GICD_BASE		0x17a00000UL
#define GICR_BASE		0x17a60000UL

#define PSCI_VERSION		0x84000000UL
#define PSCI_CPU_ON		0xc4000003UL
#define PSCI_SYSTEM_RESET	0x84000009UL
#define PSCI_FEATURES		0x8400000aUL
#define PSCI_SYSTEM_SUSPEND	0xc400000eUL
#define PSCI_SYSTEM_RESET2	0xc4000012UL
#define SMCCC_VERSION		0x80000000UL

static int ufs_ok;
static volatile int in_exception;

#define PSTORE_PMSG_BASE	0xb7fc0000UL	/* RMX1931 ramoops pmsg-ramoops-0 (2 MiB) */
#define PSTORE_PMSG_CAP		(0x200000UL - 12UL)
#define PSTORE_MAGIC		0x43474244U	/* PERSISTENT_RAM_SIG */

static void pstore_save_log(void)
{
	volatile uint32_t *hdr = (volatile uint32_t *)PSTORE_PMSG_BASE;
	volatile uint8_t *dst = (volatile uint8_t *)(PSTORE_PMSG_BASE + 12UL);
	uint32_t n = log_len < PSTORE_PMSG_CAP ? log_len : PSTORE_PMSG_CAP;
	for (uint32_t i = 0; i < n; i++)
		dst[i] = (uint8_t)log_buf[i];
	hdr[0] = PSTORE_MAGIC;
	hdr[1] = n;
	hdr[2] = n;
	__asm__ volatile("dsb sy" ::: "memory");
}

/* Header line, then the text; zero-padded to whole blocks. */
static void save_log(const char *why)
{
	log_printf("--- saved (%s) at %lu ms\n", why, ticks_now() / 19200);
	pstore_save_log();
#ifdef SHIM_READONLY
	return;
#endif
	if (!ufs_ok)
		return;
	uint32_t blocks = (log_len + 4095) / 4096;
	if (blocks > UFS_SCRATCH_BLOCKS)
		blocks = UFS_SCRATCH_BLOCKS;
	for (uint32_t i = 0; i < blocks; i += 16) {
		uint32_t n = blocks - i < 16 ? blocks - i : 16;
		int rc = ufs_scratch_write(i, n, log_buf + i * 4096);
		if (rc) {
			log_printf("save: write rc %d\n", rc);
			ufs_ok = 0;
			return;
		}
	}
}

/* Next boot goes to fastboot: PMIC PON reason (what ABL reads) plus the IMEM cookie. */
static void set_reboot_bootloader(void)
{
	uint8_t v = 0xff, after = 0xff;
	int rc = spmi_read(0, PON_SOFT_RB_SPARE, &v);
	if (!rc)
		rc = spmi_write(0, PON_SOFT_RB_SPARE, (v & 1) | PON_REASON_BOOTLOADER << 1);
	spmi_read(0, PON_SOFT_RB_SPARE, &after);
	wr32(IMEM_RESTART_REASON, RESTART_BOOTLOADER);
	log_printf("reboot reason: pon spare %x -> %x (rc %d), imem %x\n", v, after, rc,
		   rd32(IMEM_RESTART_REASON));
}

/*
 * Make PS_HOLD resets (PSCI SYSTEM_RESET, and TZ after a watchdog bite)
 * PMIC hard resets, as Linux does on reboot (qcom,system-reset): ABL leaves
 * WARM_RESET, and state surviving warm resets once left TWRP hanging until
 * a long key press. SOFT_RB_SPARE survives a hard reset. Sequence from
 * qpnp-power-on: disable, wait out 10 sleep-clock cycles, set type, enable.
 */
static void set_hard_reset(void)
{
	uint8_t ctl = 0xff, ctl2 = 0xff, after = 0xff, after2 = 0xff;
	int rc = spmi_read(0, PON_PS_HOLD_RESET_CTL, &ctl);
	rc |= spmi_read(0, PON_PS_HOLD_RESET_CTL2, &ctl2);
	if (!rc) {
		rc |= spmi_write(0, PON_PS_HOLD_RESET_CTL2, ctl2 & ~PON_RESET_EN);
		delay_ms(1);
		rc |= spmi_write(0, PON_PS_HOLD_RESET_CTL, (ctl & ~0xf) | PON_HARD_RESET);
		rc |= spmi_write(0, PON_PS_HOLD_RESET_CTL2, ctl2 | PON_RESET_EN);
	}
	spmi_read(0, PON_PS_HOLD_RESET_CTL, &after);
	spmi_read(0, PON_PS_HOLD_RESET_CTL2, &after2);
	log_printf("ps_hold reset: ctl %x -> %x, ctl2 %x -> %x (rc %d)\n", ctl, after, ctl2, after2,
		   rc);
}

static void __attribute__((noreturn)) reset(void)
{
	__asm__ volatile("dsb sy" ::: "memory");
	smc_call(PSCI_SYSTEM_RESET, 0, 0, 0);
	wr32(0x0c264000UL, 0);	/* PS_HOLD low */
	for (;;)
		__asm__ volatile("wfe");
}

static void wdt_off(void)
{
	log_printf("wdt: en %x sts %x bark %x bite %x -> off\n", rd32(WDT_BASE + 0x08),
		   rd32(WDT_BASE + 0x0c), rd32(WDT_BASE + 0x10), rd32(WDT_BASE + 0x14));
	wr32(WDT_BASE + 0x08, 0);
}

static void wdt_arm(unsigned bite_s)
{
	log_printf("wdt: en %x sts %x bark %x bite %x\n", rd32(WDT_BASE + 0x08),
		   rd32(WDT_BASE + 0x0c), rd32(WDT_BASE + 0x10), rd32(WDT_BASE + 0x14));
	wr32(WDT_BASE + 0x08, 0);
	wr32(WDT_BASE + 0x10, (bite_s - 2) * WDT_HZ);	/* bark: nobody listens */
	wr32(WDT_BASE + 0x14, bite_s * WDT_HZ);
	wr32(WDT_BASE + 0x04, 1);
	wr32(WDT_BASE + 0x08, 1);
	log_printf("wdt: armed, bite in %u s (en %x)\n", bite_s, rd32(WDT_BASE + 0x08));
}

void exc_handler(uint64_t slot, uint64_t *regs)
{
	if (in_exception++)
		reset();
	log_printf("\n*** EXCEPTION slot %lu esr %lx elr %lx far %lx spsr %lx x0 %lx x1 %lx lr %lx\n",
		   slot, rsr("esr_el1"), rsr("elr_el1"), rsr("far_el1"), rsr("spsr_el1"),
		   regs[0], regs[1], regs[31]);
	save_log("exception");
	reset();
}

void secondary_main(uint64_t idx)
{
	(void)idx;
	for (;;)
		__asm__ volatile("wfe");
}

static void log_cpu(uintptr_t dtb)
{
	log_printf("cpu: el %lu midr %lx mpidr %lx\n", rsr("currentel") >> 2, rsr("midr_el1"),
		   rsr("mpidr_el1"));
	log_printf("cpu: mmfr0 %lx mmfr1 %lx mmfr2 %lx\n", rsr("id_aa64mmfr0_el1"),
		   rsr("id_aa64mmfr1_el1"), rsr("id_aa64mmfr2_el1"));
	log_printf("cpu: pfr0 %lx isar0 %lx isar1 %lx\n", rsr("id_aa64pfr0_el1"),
		   rsr("id_aa64isar0_el1"), rsr("id_aa64isar1_el1"));
	log_printf("cpu: sctlr %lx ctr %lx cntfrq %lu vbar %lx\n", rsr("sctlr_el1"),
		   rsr("ctr_el0"), rsr("cntfrq_el0"), rsr("vbar_el1"));
	uint64_t lo = 0, hi = 0;
	fdt_chosen_u64(dtb, "linux,initrd-start", &lo);
	fdt_chosen_u64(dtb, "linux,initrd-end", &hi);
	log_printf("boot: dtb %lx magic %x initrd %lx-%lx shim %p\n", (uint64_t)dtb,
		   dtb ? __builtin_bswap32(rd32(dtb)) : 0, lo, hi, (void *)log_cpu);
	log_printf("psci: version %lx smccc %lx\n", smc_call(PSCI_VERSION, 0, 0, 0),
		   smc_call(SMCCC_VERSION, 0, 0, 0));
	/* No pointer tables: the image runs unrelocated wherever ABL put it. */
	log_printf("psci: features cpu_on %lx reset %lx reset2 %lx suspend %lx\n",
		   smc_call(PSCI_FEATURES, PSCI_CPU_ON, 0, 0),
		   smc_call(PSCI_FEATURES, PSCI_SYSTEM_RESET, 0, 0),
		   smc_call(PSCI_FEATURES, PSCI_SYSTEM_RESET2, 0, 0),
		   smc_call(PSCI_FEATURES, PSCI_SYSTEM_SUSPEND, 0, 0));
}

static void log_pmic(void)
{
	static const uint16_t regs[] = { 0x808, 0x809, 0x80a, 0x80c, 0x810, 0x85a, 0x85b, 0x88c, 0x88f };
	log_printf("pon:");
	for (unsigned i = 0; i < sizeof(regs) / sizeof(regs[0]); i++) {
		uint8_t v = 0;
		int rc = spmi_read(0, regs[i], &v);
		log_printf(" %x=%x%s", regs[i], v, rc ? "!" : "");
	}
	log_printf("\n");
}

static void log_gic(void)
{
	log_printf("gic: ctlr %x typer %x iidr %x\n", rd32(GICD_BASE), rd32(GICD_BASE + 4),
		   rd32(GICD_BASE + 8));
	for (int i = 0; i < 8; i++) {
		uintptr_t r = GICR_BASE + 0x20000UL * i;
		log_printf("gicr%d: typer %x:%x waker %x\n", i, rd32(r + 0xc), rd32(r + 8),
			   rd32(r + 0x14));
	}
}

/* Plain RAM where XNU will live: is it there and writable with the MMU off? */
static void log_ram(void)
{
	static const uint64_t probe[] = { 0x100000000ULL, 0x180000000ULL, 0x1fff00000ULL, 0x27ff00000ULL };
	for (unsigned i = 0; i < sizeof(probe) / sizeof(probe[0]); i++) {
		volatile uint64_t *p = (volatile uint64_t *)(uintptr_t)probe[i];
		uint64_t old = p[0];
		p[0] = 0x524d583100000000ULL | i;
		uint64_t back = p[0];
		p[0] = old;
		log_printf("ram %lx: was %lx readback %lx\n", probe[i], old, back);
	}
}

#ifdef SHIM_READONLY
#define XNU_MIRROR_PA_8G	0x27e000000UL
#define XNU_MIRROR_PA_6G	0x1fe000000UL
#define XNU_RING_SIZE	(4u << 20)

static void ramlog_rescue_readonly(void)
{
	static const uint64_t mirrors[2] = { XNU_MIRROR_PA_8G, XNU_MIRROR_PA_6G };
	static const char want[8] = { 'S','W','E','E','T','M','I','R' };
	uint64_t mpa = 0;
	for (int k = 0; k < 2; k++) {
		volatile const char *mh = (volatile const char *)mirrors[k];
		int ok = 1;
		for (int i = 0; i < 8; i++)
			if (mh[i] != want[i])
				ok = 0;
		if (ok && *(volatile const uint32_t *)(mirrors[k] + 16) == XNU_RING_SIZE) {
			mpa = mirrors[k];
			break;
		}
	}
	if (!mpa)
		return;
	uint64_t total = *(volatile const uint64_t *)(mpa + 8);
	*(volatile char *)mpa = 0;
	uint32_t max_copy = LOG_SIZE > log_len + 8192 ? (LOG_SIZE - log_len - 8192) : 0;
	uint32_t n = total < max_copy ? (uint32_t)total : max_copy;
	uint64_t start = total - n;
	log_printf("\n=== RESCUED XNU CONSOLE (%u of %lu bytes from SWEETMIR) ===\n", n, total);
	volatile const char *ring = (volatile const char *)(mpa + 0x1000UL);
	for (uint32_t i = 0; i < n && log_len + 1 < LOG_SIZE; i++)
		log_buf[log_len++] = ring[(start + i) % XNU_RING_SIZE];
	log_printf("\n=== END RESCUED XNU CONSOLE ===\n");
}

static int read_keys(int spmi_ok)
{
	if (!spmi_ok)
		return 0;
	uint8_t pon = 0, g6 = 1, g7 = 1;
	spmi_read(0, 0x810, &pon);	/* bit0 = kpdpwr, bit1 = resin */
	spmi_read(0, 0xc508, &g6);	/* PM8150 GPIO6 = vol_up (active low) */
	spmi_read(0, 0xc608, &g7);	/* PM8150 GPIO7 = vol_down (active low) */
	int mask = 0;
	if (pon & 1)
		mask |= 1;		/* PWR */
	if (!(g6 & 1))
		mask |= 2;		/* VOL+ */
	if ((pon & 2) || !(g7 & 1))
		mask |= 4;		/* VOL- */
	return mask;
}

static void readonly_viewer(int spmi_ok)
{
	unsigned starts[64], pages = 0, pos = 0, len = log_len;
	while (pos < len && pages < 64) {
		starts[pages++] = pos;
		con_home();
		con_skip();
		con_skip();
		unsigned step = con_text(log_buf + pos, len - pos, 0);
		if (!step)
			break;
		pos += step;
	}
	if (!pages)
		starts[pages++] = 0;

	unsigned cur = 0;
	int prev_keys = read_keys(spmi_ok);
	uint64_t t0 = ticks_now(), hz = rsr("cntfrq_el0");
	if (!hz)
		hz = 19200000UL;
	const unsigned timeout_s = 25;
	unsigned last_rem = 999;
	int redraw = 1;

	for (;;) {
		uint64_t elapsed = (ticks_now() - t0) / hz;
		if (elapsed >= timeout_s)
			break;
		unsigned rem = timeout_s - (unsigned)elapsed;
		int keys = read_keys(spmi_ok);
		int edge = keys & ~prev_keys;
		prev_keys = keys;

		if (edge & 1) {
			log_printf("viewer: power pressed, exiting\n");
			break;
		}
		if (edge & 2) {
			cur = (cur + 1) % pages;
			t0 = ticks_now();
			redraw = 1;
		}
		if (edge & 4) {
			cur = (cur + pages - 1) % pages;
			t0 = ticks_now();
			redraw = 1;
		}
		if (rem != last_rem)
			redraw = 1;

		if (redraw) {
			last_rem = rem;
			redraw = 0;
			char hdr1[80], hdr2[80];
			unsigned n1 = fmt_snprintf(hdr1, sizeof(hdr1),
				"RMX1931 XNU SHIM PROBE (ZERO-WRITE)  p%u/%u  keys=%x",
				cur + 1, pages, keys);
			unsigned n2 = fmt_snprintf(hdr2, sizeof(hdr2),
				"Fastboot in %us | VOL+/VOL-: page | PWR: exit now", rem);
			con_clear(0xff102438);
			con_text(hdr1, n1, GREEN);
			con_text(hdr2, n2, YELLOW);
			unsigned end = (cur + 1 < pages) ? starts[cur + 1] : len;
			if (end > starts[cur])
				con_text(log_buf + starts[cur], end - starts[cur], WHITE);
			if (!sde.tear_en)
				sde_kick();
		}
		delay_ms(40);
	}
}
#endif

static void save_before_jump(void)
{
	save_log("jumping to xnu");
}

void shim_main(uintptr_t dtb)
{
	log_printf("RMX1931 SHIM probe v1 t=%lu\n", ticks_now());
	log_cpu(dtb);
	int spmi_ok = !spmi_init();
	if (spmi_ok) {
		log_pmic();
		set_reboot_bootloader();
#if !defined(SHIM_READONLY) && !defined(SHIM_WARM_RESET)
		set_hard_reset();
#endif
	}
	/* Probe runs reset themselves; only an XNU boot arms the watchdog. */
	wdt_off();
#ifdef SHIM_DIAG
	sde_probe();
	uintptr_t dfb = sde.pipe ? rd32(sde.pipe + 0x14) : 0;
	uint32_t dstride = sde.pipe ? rd32(sde.pipe + 0x24) & 0xffff : 0;
	if (!con_init(dfb, dstride)) {
		sde_autorefresh(1);
		diag_run(spmi_ok);
	}
	reset();
#endif
	ufs_ok = !ufs_init();
#ifdef SHIM_READONLY
	ramlog_rescue_readonly();
#else
	if (ufs_ok)
		ramlog_rescue();
#endif
#ifdef SHIM_SHOWLOG
	/* Read-only: the shim log on UFS is the previous run's, keep it. */
	sde_probe();
	uintptr_t sfb = sde.pipe ? rd32(sde.pipe + 0x14) : 0;
	uint32_t sstride = sde.pipe ? rd32(sde.pipe + 0x24) & 0xffff : 0;
	if (!con_init(sfb, sstride)) {
		sde_autorefresh(1);
		showlog_run(ufs_ok);
	}
	reset();
#endif
	save_log("ufs up");
#ifdef SHIM_SMMUPROBE
	wdt_arm(20);
	smmuprobe_run();
	save_log("smmuprobe done");
	reset();
#endif
#ifdef SHIM_GPUPROBE
	wdt_arm(20);	/* a hung GPU register access resets the phone */
	gpuprobe_run(save_log);
	save_log("gpuprobe done");
	reset();
#endif
#ifdef SHIM_DISPTEST
	sde_probe();
	save_log("sde probe");
	disptest_run(save_log);
	save_log("disptest done");
	reset();
#endif

	sde_probe();
	save_log("sde probe");
	sde_sample("before", 4, 20);
	uintptr_t fb = sde.pipe ? rd32(sde.pipe + 0x14) : FB_SPLASH_BASE;
	uint32_t stride = sde.pipe ? (rd32(sde.pipe + 0x24) & 0xffff) : (FB_W * 4);
	int con_ok = !con_init(fb, stride);
	if (!con_ok) {
		fb = FB_SPLASH_BASE;
		stride = FB_W * 4;
		con_ok = !con_init(fb, stride);
	}
	if (con_ok) {
		con_clear(0xff102438);
		con_str("RMX1931", "shim probe v1");
	}
	sde_autorefresh(1);
	if (!sde.tear_en)
		sde_kick();
	sde_sample("autorefresh", 4, 20);
	save_log("display");

	/* An XNU payload in the ramdisk means: boot it. */
	struct loader_fb lfb = { fb, stride, FB_W, FB_H, save_before_jump };
	if (loader_has_payload(dtb))
		wdt_arm(30);
	const char *why = loader_boot(dtb, &lfb);
	log_printf("loader: %s\n", why);

	log_gic();
	log_ram();
	save_log("probe complete");
#ifdef SHIM_READONLY
	if (con_ok)
		readonly_viewer(spmi_ok);
#endif
	log_printf("done, resetting\n");
	save_log("done");
	reset();
}
