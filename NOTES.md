# iOS on Pixel 4 (flame) - working notes

Port of [ios-sweet](https://github.com/dipa-studio/ios-sweet) (XNU xnu-8796 + iPhone X iOS 16.7.16 userland on
a Redmi Note 10 Pro, SM7150) to the Pixel 4 (`flame`, SM8150). Read `docs/ios-sweet/NOTES.md`
for the history of ios-sweet's kernel patches (0001-0081); this file records what differs on flame.

What works now is in [README.md](README.md). [HANDOFF.md](HANDOFF.md) is the engineering log, newest
first: every boot, what broke and why. Paths under `work/`, `logs/`, `backup/`, `dtb/`, `stock/`,
`downloads/`, `images/` and `research/` in either file are local and not in git (see the README).

## Test loop (no buttons, no screen)

1. Phone in fastboot (`adb reboot bootloader` from TWRP).
2. `fastboot boot shim/out/shim-boot.img` (shim alone = probe; with a payload = XNU).
3. The shim sets the PM8150 PON reason to bootloader, so a reset lands in fastboot.
4. `fastboot boot downloads/twrp/twrp-3.7.1_12-0-flame.img`, then
   `tools/flame-log-pull.sh shim logs/X.txt` (and `xnu` for the XNU ring).
- `boot_a` holds TWRP (flashed 2026-10-19) so any reset without the PON reason boots TWRP
  (adb root), not Android. `boot_b` is stock. Restore everything with `images/.../flash-all.sh`.
- macOS asks "Allow accessory to connect?" the first time a new USB identity appears; until
  then `ioreg -p IOUSB` shows the phone `!registered`.

## Google ABL boot image rules (found the hard way)

`fastboot boot` fails with "Error verifying the received boot.img: ..." unless:
- the kernel is **LZ4 (frame) compressed** (uncompressed -> `Unsupported`),
- the Image header has **text_offset 0x80000** (0 -> `Invalid Parameter`),
- the image has an **AVB hash footer** (unsigned, algorithm NONE, padded to 64 MiB),
  `avbtool add_hash_footer --partition_size 67108864 --partition_name boot`.
Header v2, base 0, kernel 0x8000, ramdisk 0x1000000, tags 0x100, dtb 0x1f00000, page 4096,
stock `dtb` (two SM8150 base DTBs; ABL picks v2 and applies dtbo idx 11).
ABL loads the kernel at 0x80080000, the DTB at 0x84500000, the ramdisk at 0x84700000
(TWRP's 30 MB ramdisk went to 0xc0000000). max-download-size 256 MiB.

## Realme X2 Pro (`RMX1931` / `samurai`) ABL & hardware differences vs `flame`

- `fastboot boot` is disabled in Realme's ABL (`unknown command`); use `tools/rmx1931-run.ps1`, which
  temporarily flashes `boot` (non-A/B, 96 MiB = `100663296` B; TWRP lives separately in `recovery` on `sde18`),
  waits for the PM8150 PON warm-reset back to Fastboot, and restores `backup/boot.img`.
- Realme ABL boot image format (`shim/build-rmx1931.sh`, `tools/mkbootimg_rmx1931.py`): **Header v1**, page 4096,
  kernel `0x8000`, ramdisk `0x1000000`, tags `0x100`, `os_version 0x1c000181`, **`gzip -9` ARM64 `Image`
  (`text_offset 0x80000`) + appended `stock/dtb`** (`Image.gz-dtb`), unsigned AVB v1.0 hash footer padded to
  `100663296` B.
- RAM: 8 GB (`<0x80000000 0x3bb00000>`, `<0xc0000000 0x1c0000000>`). Fixed TZ/PIL carve-outs extend to
  `0x9d000000` (`0x9b200000..0x9d000000` is `[no-map]` `pil_adsp_region`) and `ramoops` is at
  `0xb7e00000..0xb8200000` (`pmsg-ramoops-0` at `0xb7fc0000`, 2 MiB); `0xc0000000..0x280000000` (7 GiB) is
  unreserved HLOS DRAM.
- Display: Samsung `SOFEF03F-M` `1080x2400` 90 Hz DSC cmd-mode panel; `cont_splash_region` at **`0x9d000000`**
  (`0x2400000` = 36 MiB), stride 4320, `a8r8g8b8`, SDE v5.0 (`0xae00000`) INTF1.
- Touch: **Synaptics S3706** (RMI4, self-booting internal flash) on GENI I2C `/soc/i2c@c80000` (QUPv3 wrap2 SE0 =
  `qup17`, `GPIO 55/56`, addr `0x20`), `reset-gpio` `GPIO 54` (active-low), `irq-gpio` `GPIO 122` (active-low),
  `vdd_2v8` = `ldoa17` 3000 mV, `enable1v8_gpio` = `PM8150L` (SID 4) `GPIO5` (`0xc400`, PPID `0x4c4`, LV/MV output).
- UFS / GIC / Timer / Keys / Watchdog / USB / Wi-Fi: same as `flame` (`userdata` is GPT `sda16`, 109.76 GiB).

## Hardware facts (live FDT `dtb/live.dtb` + shim probe)

| | flame | (sweet) |
|---|---|---|
| CPU | 4x A55 (cpu0-3) + 3x A76 + 1x A76 prime, MPIDR 0x81000n00, MIDR 0x51df805e | 6+2 |
| RAM | <0x80000000 0x3cc00000>, <0xc0000000 0x140000000> (6 GB) | |
| GIC | GIC-600, GICD 0x17a00000, GICR 0x17a60000 stride 0x20000 | same |
| Timer | 19.2 MHz, vtimer INTID 19 | same |
| PSCI | 1.1 smc; CPU_ON, SYSTEM_RESET yes; RESET2, SYSTEM_SUSPEND no | |
| UFS | UFSHCI 3.0 @0x1d84000, LUN0 sda, userdata sda15 first 4K LBA 0x25e000, 0xc80ffb blocks | 2.1, same addr |
| Display | NT37280 cmd-mode DSC panel 1080x2280; ABL fb 0x9c400000 stride 4320 a8r8g8b8; CTL0, VIG0+VIG1 (540 each), LM0/1, DSC0/1, INTF1; **ABL enables INTF1 autorefresh** | video mode, 0x9c000000 1080x2400 |
| Touch | ST FTS (ftm5) on GENI SPI @0x890000 CS0, irq GPIO122, reset GPIO127, vdd L14A 1.8V, avdd L17A | Goodix I2C |
| Keys | power KPDPWR + vol- RESIN on PM8150 PON (SID0 0x800); vol+ PM8150 GPIO6 (SID0 0xc500), active low | vol+ PM6150L GPIO2 |
| PMIC arb | v5 @0xc440000, chnls 0xc600000, obs 0xe600000, cnfg 0xc40a000, EE 0, 512 APIDs | same |
| Reboot reason | PM8150 PON SOFT_RB_SPARE 0x88f bits [7:1] = 2 (bootloader) -> fastboot; Linux clears it to 0 before every reset; 0x10 (= WDT) at boot | IMEM ignored |
| Watchdog | APSS WDT 0x17c10000, 32765 Hz, ABL leaves it off; bite register 20 bits (~32 s max) | |
| USB | DWC3 @0xa600000, SNPS femto HS PHY 0x88e2000, QMP SS PHY 0x88e8000 | QUSB2 |
| ramoops | 0xa47ff000 (2 MB), alt 0xa49ff000 (TWRP /dev/access-ramoops) | |

DRAM partly survives resets (shim saw TWRP's kernel pointers at 0x100000000), but a marker
written to the alt ramoops region via /dev/access-ramoops did not survive reboot -> bootloader -> TWRP.

## Layout

- `shim/` flame shim (`build.sh`, Homebrew llvm + lld), `tools/` host tools, `phone/` code that runs in
  iOS on the phone (each directory has a `build.sh`; they use `work/userland/bin/ios-cc`)
- `xnu/patches/` ios-sweet 0001-0081 + flame patches from 0082
- `work/dxb/` blacktop darwin-xnu-build checkout; `work/dxb/xnu` is a git repo whose HEAD is
  "baseline + applied patches", new patches come from `git diff`
- Not for git: `backup/` (partition dumps, serials), `dtb/`, `stock/`, `downloads/` (TWRP,
  IPSW parts, AOSP tools), `work/`, `logs/`

## Flame patches

- 0082 IORPC.kernelContent: Xcode 16+ iig emits it; field added (kernel only) and set at the
  two user->kernel IOUserServer dispatch sites, as in newer XNU.
- 0083 flame basics: early console on the 0x9c400000 / 2280-row framebuffer (SWEET.h
  constants used by start.s, sweet_early.c, arm_vm_init.c), vol+ on PM8150 GPIO6, the Redmi
  touch driver off by default (sweettouch=1 forces it; it would vote L11C to 3.2 V), APSS
  watchdog keepalive in SweetPlatformExpert (flamewdt=0 off, flamereboot=N stop after N s).
- 0084 early framebuffer block mapped Device-GRE, not Normal-NC (no speculative reads into pil_slpi).
- 0085 ST FTS touch (ftm5) over GENI SPI; 0086 its HW clock gating + cpufreq for all 8 cores.
- 0087 fixed-rate vsync + fps stats. 0088 flame USB offsets.
- 0089 log timestamps, RAM mirror of the console ring, poke scripts; with 0090: USB through the apps SMMU
  the way ABL does it (own context bank, translation off) and the UDP command server.
- 0091 USB ARP fixes, TCP file server, `sync` command (0092-0093 included).
- 0094 /dev/pmem (physical memory and SCM calls for userland drivers), file server READ.
- 0095 the A640 core in the kernel, GPU present (frames scaled to fit by the GPU).
- 0096 SweetAccelerator (IOAcceleratorES for Metal, `sweetmtl=1`); 0097 its user client.
- 0098 panic logs into the persistent log ring. 0099 PM8150 RTC time of day (`sweetrtcoff`).
- 0100 clonefile copy fallback on HFS, tap/swipe commands (0101 included).
- 0102 RPMh ARC/VRM votes from userland (PMEM_RPMH). 0103 AMFI stand-in answers trust cache queries.
- 0104 planar IOSurfaces report summed bytes-per-row/element (blank Safari pages).
- 0105-0109 GPU: VA reuse, more page tables, perf vote (`sweetgpufreq`), recovery, async submit.
- 0110-0112 Apple continuous display corners (final: 0112, GPU scissored bands + CPU row masks).
- 0113 wait for a client's last fence before unmapping its memory.
- 0114 the AMFI policy answers dyld's policy query: @rpath frameworks of third-party apps load.
- 0115 v2 GPU user client (NativeAdreno ABI) for Turnip: GPU buffers from RAM XNU does not manage
  (no cacheable physmap alias), multi-IB submits, progress-based hang detection.
- 0116 `sweetmtl=2` selects SweetMetal2 (metal2vulkan + Turnip). 0117-0118 pool accounting, submit timing.
- 0119 present not waited for. 0120 per-process GPU stats, BCM (bus/DDR) votes over PMEM_RPMH.
- 0121 vsync notifications carry the refresh period (CA's frame deadlines were always past).
- 0122 DDR + memory NoC voted up at GPU start (`sweetddr`), GPU pool caps (`sweetgpucap`, `sweetgpureserve`).
- 0123 en0 + Apple80211 for wifid (ios-topaz 0900 + 0128, from HackiOS), the station over /dev/wlan to
  wland in userland.
- 0124 10-bit surfaces as BGRA only for WebContent (`sweetsurf10`); the joined network keeps its scan record.
- 0125 RMX1931 board: 1080x2400 @ 0x9d000000 framebuffer/GPU/HID, `sweet_fts` off by default, Synaptics S3706
  RMI4 touch over GENI I2C SE0 (`rmxtouch`), and `sweetprobe=1` read-only driver bring-up under `rd=md0`.
- 0126 RMX1931 touch: fall back to open-drain TLMM GPIO I2C (`GPIO 55/56` with SCL clock stretching) when SE0
  `GENI_IF_DISABLE_RO` (`0x64`) reports `FIFO_IF_DISABLE` (`rmxtouchgpio=1` forces it).
- 0127 RMX1931 GPU: set Adreno 640 `ZAP_REGION` to `0x99515000` (`pil_gpu_region` in `RMX1931` FDT).
- 0128 RMX1931 Metal/GPU/touch: ensure `/private/var/local` + `m2v-cache` (`0777`) and `/private/etc/dropbear` (`0700`) before `launchd`, unlock SM8150-AC `675 MHz` Adreno 640 OPP (`sweetgpufreq=6`), and 4 ms active touch polling.
- 0129 RMX1931 backlight/Wi-Fi/FPS/overlay: `AppleARMBacklight` on `/backlight` with tear-free Adreno 640 AMOLED dimming, direct `SwapEnd` GPU present (`sweetfb_direct=1`), `sta_joined_bss` + open-network `AUTH_TYPE`, and pre-`launchd` `/chosen/sweet-overlay` ustar unpacker.
- 0130 RMX1931 HackiOS peripherals & runtime fixes: AMFI `0x5a`/`0x60` + Sandbox op `4` container path + entitlement priv grants, `kern.exec_inject` (`/private/var/local/inject.conf`), `xpcproxy` `pidversion` & persona-less `posix_spawn`, read-only TI `BQ28Z610`/`BQ27541` (`0x55`, `GPIO 27/28`) + `PM8150B` battery telemetry (`SweetBatteryReport`), `/dev/hakios-audio` 48 kHz sink, `PM8150L` SID `5` `0xd300` `/dev/hakios-torch` (125 mA / 30 s cap), and Awinic `AW8697` (`0x5a`, `GPIO 98/99/116`) 170 Hz closed-loop `/dev/hakios-haptic`.
- 0131 RMX1931 video IOSurface fixes: `SweetSurface` planar & single-plane `ExtendedPixels` (`Right`/`Bottom`/`Left`/`Top`), 16 KiB writable codec macroblock slack pages (fixing `H264SW.videocodec` `SIGBUS` past plane end), and cross-process `IOSurface` `SetYCbCrMatrix` (`sel 5`) + `SetBulkAttachments` (`sel 27`) into shared client state.
- 0132 RMX1931 UI performance: AMP Edge scheduler (`__ARM_AMP__=1`, 4x A55 E-cluster + 4x A76/Prime P-cluster, `bit_first` Prime preference, `-mcpu=cortex-a76+crypto`), 1.612 GHz L3 + peak RPMh BCM votes, GICv3 SPI dispatch + Adreno `INTID 332` fence IRQ + SDE `INTF1` HW TE vsync & `FB0`/`FB1` double-buffering, 1,456 MB WC GPU pool (`sweet-gpool-hi`) + `fKept[32]` BO recycle cache + `firstPA`/`gpuImported`/`cpuDirty` zero-clean GPU present, `INTID 240` IRQ-driven 400 kHz touch + batched 120 Hz `IOHIDEvent`, UFS read cache/IRQ wait (`ufslogsecs=15`), and stripped `DEVELOPMENT` debug trackers (`iotracking`, `pgzalloc`, `proc_ref_debug`, `vnguard`, `iocount_trace`, `lock_stats`, `sched_hygiene_debug`, `iotrace`).
