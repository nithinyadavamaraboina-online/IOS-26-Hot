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
- 0129 iOS 26 (`xnu-12377.121.6` in `xnu/patches-26/`): grade `arm64e` slices on `ARM64_V8`, pass `ptrauth_disabled=1`, unify `arm64`/`arm64e` shared region + `v3`/`v5` auth rebase `memcpy`, and unsigned `DYLD_CHAINED_PTR_ARM64E*` page-in fixups.
- 0130 iOS 26 (`xnu/patches-26/`): EL1 `sleh.c` user-mode emulator for `arm64e` PAC (`reta*`, `bra*`/`blra*`, `pac*`/`aut*`/`xpac*`, `pacga`, `ldra*`) and ARMv8.3/v8.4 ISA (`fjcvtzs`, `fcadd`/`fcmla`, `stlur*`/`ldapur*`, `FlagM`/`DIT`, `SHA3`).

- 0131 iOS 26 (`xnu/patches-26/`): stage pinned dxb TrustCache headers, expose internal kmem APIs, opt SWEET into the private CE API, suppress reserved-identifier noise, and fix unused CodeSignature / Apple core-type / stale sysreg_restore build diagnostics.

- 0132 iOS 26: use current interrupt-debug / VM region APIs in SWEET diagnostics, declare the existing console flag, and supply pinned dxb browser-engine entitlement constants for NECP and exec.

- 0133 iOS 26: supply `ml_device_is_prod_fused` for SWEET using XNU's conservative x86 fallback (TRUE), resolving the sole remaining kernel 0132 link error.

- iOS 26 patch 0134: match Firehose chunk-count configuration and ABI to libdispatch; prevent early-boot log copy from corrupting buffer header.

- 0135 iOS 26: use `ARM64_BOARD_CONFIG_SWEET` and declare `early_random` in `corecrypto_rand.c` to activate the existing allocator RNG fallback without a registered corecrypto provider.
- 0136 iOS 26: register/boot `SweetCPU` sequentially via `ml_processor_register` (`processor_start` is stubbed in `xnu-12377`), guard Qualcomm SPMI/WDT/mirror on `/sweet-ufs` with synthetic `IORTC` for QEMU, and default `unentitled_ios_sim_launch` to `true` on `SWEET`.
- 0137 iOS 26: Sandbox ops write their results (5 extension issue -> token `invalid`, 6 consume -> handle 0, 0x1c/0x43/0x4d -> zeros; ENOENT for op 5 failed every container lookup: lsd, installd, mobileactivationd), AMFI answers 0x5b like 0x5a, and a critical-exit panic flushes SWEETLOG first. Boot-args needed on iOS 26: `cs_integrity_skip=1` (de-PAC'd binaries no longer match their page hashes) and `fips_mode=0x44` (cc_fips_test integrity check).
- 0138 iOS 26: EL1 `locore.s` synchronous PAC fast-path (`pacga`, `reta*`, `bra*`, `blra*` in ~45 cycles without NEON spill or atomic cache-line bounce) + per-CPU `sleh.c` counters, pre-`launchd` `/chosen/sweet-overlay` (`0129`), `AppleARMBacklight` AMOLED dimming + Wi-Fi join (`0129`), planar/YCbCr video `IOSurface` (`0131`), cool Kryo 485 voltage-knee default OPPs (`1632/1804/2016 MHz`), SPMI bus lock + `PM8150L GPIO5` touch retry, and unconditional `sweet_files_start()`.
- 0139 iOS 26: Map the SM8150 `MPSS` carve-out (`0x8dc00000..0x97c00000`) `VM_WIMG_WCOMB` + `bcopy` in `/dev/pmem` so `wland` loads the 158 MiB modem firmware in `< 0.1 s` instead of `~96 s` over strongly-ordered device stores.
- 0140 iOS 26: Disable `launchd` Mach message filter enforcement (`IPC_PORT_SERVICE_PORT_NOFILTER`) on `SWEET` service ports when `launchd_domain_ExtensionKit` lacks Apple-internal filter tables.
- 0141 iOS 26: Normalize `IOMobileFramebuffer` `SwapEnd` (`selector 4`) `0x588`-byte records (`+0x48` layer shift in `QuartzCore` on iOS 26) to `SweetFramebuffer`'s `0x46c` layout and widen the selector log bitmap to 128 bits.
- 0142 iOS 26: Widen `IOSurfaceRootUserClient` create/lookup result structs to `0xa68` bytes (`0xf60` legacy compatibility), implement iOS 26 selectors `43..59` (`CreateWithMachPort`, `SetBulkAttachments2` 22-field 80B color/HDR metadata, `GetBulkAttachments`, `SetCompressedTileDataRegionMemoryUsedOfPlane`, client state/event notifications), and extend `sweet_surface_traps` through index 15.
- 0143 iOS 26: Drain queued Adreno GPU fences (`sweet_gpu_last_fence()`) before `NativeAdrenoMethodBoFree` / `SweetAcceleratorUserClient::free()` unmaps SMMU PTEs or recycles `gpool` pages, and guard `fLastFence` updates monotonically under `fLock`.
- 0144 iOS 26: Full-bleed `20:9` logical framebuffer (`1125x2499`, `fit_rect = [0, 0, 1080, 2400]` with `2 px` aspect snap), asynchronous 64-entry GPU fence `BoFree` reaper ring, and swapchain dcache clean elision (`826 us` -> `17 us`).
- 0145 iOS 26: `IO80211` `apple80211req` ABI (`5528 B` `SCAN_REQ`, `2272 B` `SCAN_RESULT`, `908 B` `+4 B` `ASSOCIATE`, `21 B` `CARD_CAPABILITIES`, `COUNTRY_CODE 51`) for `wifid`.
- 0146 iOS 26: `IO80211` split-scan channel filter + 6 GHz `skip_radio` immediate `SCAN_DONE`, 4s scan-cache coalescing, 44-byte `apple80211_link_changed`, `SENSING_ENABLE` (`365`), and 3-tier dynamic Kryo 485 OSM CPU DVFS governor (`flamecpufreq=3`).
- 0147 iOS 26: Map the SM8150 `pil_adsp_region` carve-out (`0x9b200000..0x9d000000`) `VM_WIMG_WCOMB` in `/dev/pmem` (`< 15 ms` ADSP load) and grant `PRIV_GLOBAL_PROC_INFO` to entitled/platform audio & system clients.
- 0148 iOS 26: Widen `SweetKeyStore` key wrap/unwrap (`10`/`11`) to `1024 B` + `IOMemoryDescriptor` + `scalarOutput[0]` keyclass preservation, handle bag/status selectors (`2`, `3`, `6`, `107`, `142`), and add `@userapps` vnode matching (`2048 B` `exec_inject_conf`) in `kern_exec.c`.
- 0149 iOS 26: UI frame-pacing & latency: unlock `675 MHz` Adreno 640 (`sweetgpufreq=6`) + `1785/2419/2841 MHz` active / `1382/1708/1900 MHz` idle Kryo 485 OSM DVFS (`perfpcl` 1-core boost rows, `3.0 s` boost + system GPU submit boost), single-flush contiguous `BoMap`, 8-slot swapchain + 4-slot present IB ring, and quiet runtime GPU/RPMh/HID console logs.
- 0150 iOS 26 (`Hot`): Rebrand `SweetAccelerator` Metal2 plugin (`hotmtl=2` / `sweetmtl=2` -> `HotMetal2.bundle` / `HotMTL2Device`) and `kern_exec.c` default/userapp injection (`HotStore.app`, `HotAuth.app`, `.hotstore_installed`, `libhot_media.dylib`, `libhot_sysfix.dylib`) while retaining legacy fallback names.
- 0151 iOS 26 (`Hot`): Enable `__ARM_AMP__` 2-cluster Edge scheduling (`cpu0..3` A55 = `PSET_AMP_E`, `cpu4..7` A76/Prime = `PSET_AMP_P`, UI/launch QoS `< UT` pinned to `P` with idle `P` assist for `UT` and `BG` isolated on `E`), ARMv8.2 LSE hardware atomics (`__ARM_FEATURE_ATOMICS`), `250 us` sleeping GPU fence waits, default `ufscache=1`/`ufswait=1`, and stripped `work_interval` ops `6..8`.
