#!/usr/bin/env python3
"""Build the minimal Apple Device Tree that the SWEET xnu needs to boot on flame / RMX1931.

Only what the early boot path actually reads (see ios-sweet's NOTES.md); every
MMIO address comes from the SM8150 live FDT. Defaults to iPhone 11 Pro
(iPhone12,3 / D421AP, A13 Bionic Cebu 0x8030) for iOS 26; pass --d22ap for
legacy iPhone X (iPhone10,3 / D22AP, A11 0x8015) iOS 16 builds.
Usage:  mkadt.py OUT.adt [--dump] [--qemu] [--highmem] [--d22ap] [--canvas-height N] [--baseband]
"""
import hashlib, os, struct, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import adt  # noqa: E402

# Kernel-managed RAM: above every Qualcomm/OPPO XPU carve-out (RMX1931 carve-outs end at 0xbbb00000)
PHYS_BASE = 0xc0000000
MEM_SIZE = 0x3d000000
# --highmem (phone only): above 4G. The flame live FDT memory node is
# <0x80000000 0x3cc00000>, <0xc0000000 0x140000000>; every fixed
# reserved-memory carve-out ends below 0xac800000 and the dynamic pools are
# limited below 4G. ABL puts the ramdisk (our payload) at 0x84700000 or, if
# big, 0xc0000000, so XNU takes 0x1_00000000 up (same size as on the Redmi).
HIGH_PHYS_BASE = 0x100000000
HIGH_MEM_SIZE = 0xfcc00000
# Actual physical LPDDR4X address range for XNU's gDramBase/gDramSize
# (is_dram_addr() in pmap_map_bd_with_options / wimg_to_pte and pmap_first_pnum).
# Must cover all physical RAM regions mapped as Write-Combine (0x9d000000 fbcon,
# <4G USB bounce buffers, 0x1fd000000 /pram, 0x1fe000000 console mirror) while
# keeping SM8150 MMIO (<0x80000000) outside is_dram_addr().
DRAM_BASE, DRAM_SIZE = 0x80000000, 0x200000000
QEMU_DRAM_BASE, QEMU_DRAM_SIZE = 0x40000000, 0x1c0000000
# /arm-io children are relative to this; it must not be 0 (serial_init bails)
SOC_BASE = 0x01000000

GICD, GICD_SIZE = 0x17a00000, 0x10000
GICR, GICR_SIZE = 0x17a60000, 0x100000
# cont_splash_region on RMX1931 (Realme X2 Pro, Samsung SOFEF03F-M 1080x2400 cmd-mode panel)
FB, FB_SIZE = 0x9d000000, 0x2400000
SWEET_ECID = 0x5357454554000001  # 'SWEET' + 1, synthetic
FB_W, FB_H, FB_STRIDE = 1080, 2400, 1080 * 4
# Internal UFS host (ufshc@1d84000 "ufs_mem"); QEMU virt has none
UFS, UFS_SIZE = 0x01d84000, 0x3000
TIMEBASE = 19200000
VTIMER_INTID = 19
# QEMU virt (-M virt,gic-version=3 -cpu cortex-a76): GICD/GICR and the generic timer's CNTFRQ
QEMU_GICD, QEMU_GICR, QEMU_TIMEBASE = 0x08000000, 0x080a0000, 62500000
QEMU_VTIMER_INTID = 27

BOOT_MANIFEST_HASH = hashlib.sha384(b'sweet boot manifest').digest()
SIDP_MANIFEST_HASH = hashlib.sha384(b'sweet sidp manifest').digest()

# MobileGestalt derives product type, device class, home-button-less UI, artwork
# etc. from the root identity and /product of the real Apple device tree. Those
# trees are Apple artifacts (kept out of git): extract from the IPSW with
#   tools/remotezip.py URL DeviceTree.d421ap.im4p research/ipsw-26
#   tools/adt.py unwrap research/ipsw-26/Firmware/all_flash/DeviceTree.d421ap.im4p research/ipsw-26/d421ap.adt
D421AP_ADT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '../research/ipsw-26/d421ap.adt')
D22AP_ADT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '../research/ipsw-x/d22ap.adt')

u32 = lambda *v: struct.pack('<%dI' % len(v), *v)
u64 = lambda *v: struct.pack('<%dQ' % len(v), *v)
cstr = lambda s: s.encode() + b'\0'


def _clean_adt_subtree(src_node, ph):
    name_bytes = dict((k, v) for k, f, v in src_node.props).get('name', b'\0')
    name = name_bytes.rstrip(b'\0').decode('latin1')
    props = [ph()] + [
        (k, v) for k, f, v in src_node.props
        if not f and k not in ('name', 'AAPL,phandle')
    ]
    children = [_clean_adt_subtree(ch, ph) for ch in src_node.children]
    return node(name, props, children)


def synthetic_d421ap_product(ph):
    """Minimal fallback /product subtree for D421AP when research/ipsw-26/d421ap.adt is absent (e.g. CI)."""
    props = [
        ph(),
        ('product-name', cstr('iPhone 11 Pro')),
        ('product-description', cstr('iPhone 11 Pro')),
        ('unique-model', cstr('D421AP')),
        ('sub-product-type', cstr('iPhone12,3')),
        ('fdr-product-type', cstr('iPhone12,3')),
        ('compatible-device-fallback', cstr('iPhone11,2')),
        ('artwork-device-idiom', cstr('phone')),
        ('artwork-scale-factor', u32(3)),
        ('artwork-device-subtype', u32(2436)),
        ('artwork-display-gamut', cstr('P3')),
        ('oled-display', u32(1)),
        ('thin-bezel', u32(1)),
        ('display-corner-radius', u64(0x100000027)),
        ('app-macho-architecture', cstr('arm64e')),
        ('partition-style', cstr('iOS')),
        ('graphics-featureset-class', cstr('APPLE6')),
        ('graphics-featureset-fallbacks', cstr('APPLE5:APPLE4:APPLE3:APPLE3v1:APPLE2:APPLE1:GLES2,0')),
        ('device-perf-memory-class', u32(3)),
        ('has-exclaves', u32(0)),
        ('exclaves-enabled', u32(0)),
    ]
    children = [
        node('camera', [ph(), ('flash', u32(1))]),
        node('haptics', [ph(), ('closed-loop', u32(1))]),
        node('audio', [ph()]),
    ]
    return [node('product', props, children)]


def adt_product(ph, path, d22ap=False):
    """/product subtree of the real D421AP/D22AP tree (including camera, haptics,
    audio, facetime, maps), without the syscfg placeholders iBoot would fill in
    (flag bit set) and with fresh AAPL,phandles."""
    if not os.path.exists(path):
        print(f'warning: {path} missing, {"no /product node" if d22ap else "using synthetic D421AP /product"}',
              file=sys.stderr)
        return [] if d22ap else synthetic_d421ap_product(ph)
    root, _ = adt.parse(open(path, 'rb').read())
    for c in root.children:
        if dict((k, v) for k, f, v in c.props).get('name') == b'product\0':
            return [_clean_adt_subtree(c, ph)]
    raise SystemExit(f'{path}: no /product node')


def d22ap_product(ph, path=D22AP_ADT):
    return adt_product(ph, path, d22ap=True)


def d421ap_product(ph, path=D421AP_ADT):
    return adt_product(ph, path, d22ap=False)


PANIC_LOG_PA = 0x1fd000000
PANIC_LOG_SIZE = 0x80000


def node(name, props=(), children=()):
    n = adt.Node()
    n.props = [('name', 0, cstr(name))] + [(k, 0, v) for k, v in props]
    n.children = list(children)
    return n


def build(seed=None, qemu=False, highmem=False, d22ap=False, canvas_height=2499, baseband=False):
    """qemu=True: same tree, but with QEMU virt's GIC addresses and CNTFRQ (tools/qemu-sweet.py)."""
    phys_base, mem_size = (HIGH_PHYS_BASE, HIGH_MEM_SIZE) if highmem else (PHYS_BASE, MEM_SIZE)
    dram_base, dram_size = (QEMU_DRAM_BASE, QEMU_DRAM_SIZE) if qemu else (DRAM_BASE, DRAM_SIZE)
    gicd, gicr, timebase = (QEMU_GICD, QEMU_GICR, QEMU_TIMEBASE) if qemu else (GICD, GICR, TIMEBASE)
    # XNU 12377 requires four SHA-512 blocks from PE_get_random_seed;
    # the legacy iOS 16 target requests one block.
    seed_size = 64 if d22ap else 256
    seed = seed if seed is not None else os.urandom(seed_size)
    if len(seed) != seed_size:
        raise ValueError(f'{"D22AP" if d22ap else "D421AP"} requires {seed_size} random-seed bytes, got {len(seed)}')
    cl4_entropy = b''.join(hashlib.sha512(seed + bytes([i])).digest() for i in range(3))  # 192 bytes
    phandle = iter(range(1, 1000))
    ph = lambda: ('AAPL,phandle', u32(next(phandle)))

    chip_id = 0x8015 if d22ap else 0x8030  # A11 Skye (D22AP) vs A13 Cebu (D421AP)
    chosen_props = [
        ph(),
        ('dram-base', u64(dram_base)),
        ('dram-size', u64(dram_size)),
        ('sweet-phys-base', u64(phys_base)),
        ('sweet-mem-size', u64(mem_size)),
        ('random-seed', seed),
        ('debug-enabled', u32(1)),
        ('firmware-version', cstr('flame-shim')),
        ('system-firmware-version', cstr('flame-shim')),
        # iBoot's SHA-384 of the boot manifest; /sbin/mount -P 1 exits unless
        # it exists (bind mounts /private/preboot/<hex>/usr/standalone/firmware,
        # tolerating a missing directory). Fixed stand-in value.
        ('boot-manifest-hash', BOOT_MANIFEST_HASH),
        # libimg4 (cryptexd) aborts "failed to get crypto algorithm" without
        # it; real A11+ device trees say sha2-384.
        ('crypto-hash-method', cstr('sha2-384')),
        # MobileGestalt's screen class: display-scale (u32, default 0) comes
        # from /chosen, the pixel size from IOMobileFramebuffer. Scale 0 makes
        # SpringBoard's FBSDisplayMode "infxinf@0x" invalid. Both D22AP (iPhone X)
        # and D421AP (iPhone 11 Pro) use a 1125x2436 @3x Super Retina canvas.
        ('display-scale', u32(3)),
        # XNU derives IOPlatformUUID (gethostuuid) from these on arm; without
        # them CFPreferences cannot use kCFPreferencesCurrentHost (no ByHost
        # files), so e.g. DataMigration never records LastSystemVersion and
        # migrates on every boot. Synthetic ECID (not the phone's).
        ('unique-chip-id', struct.pack('<Q', SWEET_ECID)),
        ('chip-id', u32(chip_id)),
        # Security status as on a dev-fused, undemoted board (tuple 0,0,1,1):
        # MobileGestalt reads these from /chosen, and mobileactivationd's
        # DeviceType then takes its native "Hactivation" branch (state
        # Activated without an activation record; no SEP, no baseband here).
        ('certificate-production-status', u32(0)),
        ('effective-production-status-ap', u32(0)),
        ('certificate-security-mode', u32(1)),
        ('effective-security-mode-sep', u32(1)),
        # MobileGestalt's WiFi/Bluetooth/Ethernet addresses: without them each
        # WifiAddress answer waits 5 s for an IONetworkController (sdio, wlan,
        # marconi-wifi) that never comes, and the old-style UniqueDeviceID needs
        # it: installd then takes 5 s per bundle, LaunchServices never finishes
        # registering extensions, and PosterBoard (no poster providers) asserts:
        # black wallpaper. Locally administered addresses, not the phone's.
        ('mac-address-wifi0', bytes([0x02, 0x50, 0x49, 0x58, 0x45, 0x4c])),
        ('mac-address-bluetooth0', bytes([0x02, 0x50, 0x49, 0x58, 0x45, 0x4d])),
        ('mac-address-ethernet0', bytes([0x02, 0x50, 0x49, 0x58, 0x45, 0x4e])),
        # Panic region size (with /pram below): XNU's SavePanicInfo spins forever
        # without one, so panics printed no backtrace.
        ('embedded-panic-log-size', u32(PANIC_LOG_SIZE)),
    ]
    if not d22ap:
        # iOS 26 / xnu-12377 / D421AP properties present in DeviceTree.d421ap.im4p:
        # 192-byte corecrypto CL4 DRBG entropy, code-signing policy, SiDP manifest
        # hash, and complete AP/SEP Image4 security status.
        chosen_props += [
            ('cl4-entropy', cl4_entropy),
            ('cs-system-policy', cstr('default')),
            ('boot-type', cstr('local')),
            ('darwinos-security-environment', u32(0)),
            ('darwinos-userspace-reboot', u32(0)),
            ('repair-environment', u32(0)),
            ('unrestricted-subsystem-root', u32(0)),
            ('sidp-rom-manifest-hash', SIDP_MANIFEST_HASH),
            ('effective-security-mode-ap', u32(1)),
            ('effective-production-status-sep', u32(0)),
            ('kernel-ctrr-to-be-enabled', u32(1)),
            ('image4-supported', b''),
            ('display-corner-radius', u32(39)),
        ]

    chosen = node('chosen', chosen_props, [
        node('memory-map', [ph()]),
        # Local boot policy (kern_csr.c): SIP relaxed like `csrutil disable`
        # (lp-sip0: untrusted kexts, unrestricted FS, task_for_pid, kernel
        # debugger, dtrace, nvram), lp-sip1: unauthenticated (unsealed) root.
        # The iOS shared cache is in no trust cache, so dyld can only map it
        # with CSR_ALLOW_UNRESTRICTED_FS (vm_unix.c shared region checks).
        node('asmb', [ph(), ('lp-sip0', u64(0x6f)), ('lp-sip1', u32(1))])])

    # AMP on hardware: cpu0-3 = Cortex-A55 ('E', cluster 0), cpu4-6 = Cortex-A76
    # + cpu7 = Cortex-A76 Prime ('P', cluster 1) for XNU's Edge scheduler (0151).
    # QEMU retains its original single-CPU tree ('E', cluster 0): its MPIDRs differ.
    cpu_nodes = []
    for core in range(1 if qemu else 8):
        is_p = (not qemu) and (core >= 4)
        cluster_id = 1 if is_p else 0
        cluster_type = ord('P') if is_p else ord('E')
        cluster_core_id = (core - 4) if is_p else core
        cpu_nodes.append(node(f'cpu{core}', [
            ph(),
            ('device_type', cstr('cpu')),
            ('compatible', cstr('qcom,kryo470') + cstr('qcom,kryo485') + cstr('ARM,v8')),
            ('reg', u32(core << 8)),  # actual MPIDR Aff1:Aff0, NOT logical ID
            ('cpu-id', u32(core)),   # descriptive: XNU assigns IDs by DT order
            ('cluster-id', u32(cluster_id)),
            ('cluster-type', u32(cluster_type)),
            ('die-cluster-id', u32(cluster_id)),
            ('cluster-core-id', u32(cluster_core_id)),
            ('l2-cache-id', u32(core)),  # per-core L2 nodes in the live FDT
            ('l3-cache-id', u32(0)),     # shared L3
            ('state', cstr('running' if core == 0 else 'stopped')),
            ('timebase-frequency', u32(timebase)),
            ('clock-frequency', u32(2841600000 if core == 7 else 2419200000 if core >= 4 else 1785600000)),
            ('bus-frequency', u32(100000000)),
            ('memory-frequency', u32(1866000000)),
            ('peripheral-frequency', u32(timebase)),
            ('fixed-frequency', u32(timebase)),
        ]))
    cpus = node('cpus', [ph(), ('#address-cells', u32(1)),
                         ('#size-cells', u32(0))], cpu_nodes)

    gic = node('gic', [
        ph(),
        ('compatible', cstr('arm,gic-v3')),
        ('interrupt-controller', b''),
        ('virtual-timer-intid', u32(QEMU_VTIMER_INTID if qemu else VTIMER_INTID)),
        ('reg', u64(gicd - SOC_BASE, GICD_SIZE, gicr - SOC_BASE, GICR_SIZE)),
    ])
    arm_io = node('arm-io', [
        ph(),
        ('device_type', cstr('sweet-io')),
        ('compatible', cstr('sweet-io') + cstr('arm-io')),
        ('#address-cells', u32(2)),
        ('#size-cells', u32(2)),
        ('ranges', u64(0, SOC_BASE, 0x100000000 - SOC_BASE)),
    ], [gic,
        # No hardware: watchdogd checks IODeviceTree:/arm-io/wdt for no-pmu and
        # otherwise crashes trying to set the PMU boot stage (customer builds
        # ignore the -wdt_no_pmu boot-arg).
        node('wdt', [ph(), ('no-pmu', u32(1))]),
        # Main display: IOMobileFramebuffer.framework counts /arm-io children
        # with this device_type and reads the canvas size from the node
        # (the presented iPhone X size, see SweetFramebuffer.cpp);
        # the kernel's AppleCLCD matches the compatible string.
        # launchd starts wifid only when the device tree has a "wlan" node
        # (com.apple.wifid _LimitLoadToDeviceTree = wlan, marconi-wifi); the
        # kernel's en0 + Apple80211 (bsd/dev/flame_wlan.c) answer it (as ios-topaz)
        node('wlan', [
            ph(),
            ('device_type', cstr('wlan')),
            ('compatible', cstr('flame,wcn3990')),
        ]),
        node('disp0', [
            ph(),
            ('device_type', cstr('display-subsystem')),
            ('compatible', cstr('sweet,display')),
            ('canvas-width', u32(1125)),
            ('canvas-height', u32(canvas_height)),
        ])])

    fbcon = node('sweet-fbcon', [
        ph(),
        ('reg', u64(FB, FB_SIZE)),
        ('width', u32(FB_W)),
        ('height', u32(FB_H)),
        ('stride', u32(FB_STRIDE)),
    ])

    # iBoot's panic region (pe_init.c check_for_panic_log): RAM above XNU's range
    # (which ends at 0x1fcc00000) and below the console mirror at 0x1fe000000.
    pram = node('pram', [ph(), ('reg', struct.pack('<2Q', PANIC_LOG_PA, PANIC_LOG_SIZE))])

    # bsd/dev/sweet_ufs.c: the UFS host ABL leaves running (absolute physical reg).
    ufs = node('sweet-ufs', [
        ph(),
        ('compatible', cstr('qcom,ufshc')),
        ('reg', u64(UFS, UFS_SIZE)),
    ])

    # pmap_compute_max_asids requires the node even when no overrides are set.
    # An absent pmap-max-asids property selects XNU's own MAX_ASIDS default.
    # No effaceable storage: keybagd then reads /private/var/keybags/systembag.kb
    # as a plain plist instead of decrypting it with the effaceable key.
    defaults = node('defaults', [ph(), ('no-effaceable-storage', u32(1))])

    # MobileGestalt HomeButtonType = IODeviceTree:/buttons home-button-type
    # (D22AP: 2, no Home button; absent = 0 = Home-button device).
    buttons = node('buttons', [
        ph(),
        ('button-names', cstr('volup') + cstr('voldown') + cstr('hold')),
        ('home-button-type', u32(2)),
        ('opposed-power-vol-buttons', u32(1)),
    ])

    # CoreBrightness / BacklightServices match AppleARMBacklight on IODeviceTree:/backlight
    # (IONameMatch = 'backlight', max 625 nits matching SweetFramebuffer.cpp AppleARMBacklight).
    # CoreBrightness Display.m requires Q16.16 fixed-point user-accessible-max-nits /
    # digital-dimming-min-nits and a 101-entry Q16.16 backlight-marketing-table (0..100%).
    marketing_table = u32(*(
        int(round((2.0 + 623.0 * ((i / 100.0) ** 2.2)) * 65536))
        for i in range(101)
    ))
    backlight = node('backlight', [
        ph(),
        ('compatible', cstr('backlight')),
        ('device_type', cstr('backlight')),
        ('backlight-id', u32(0xffffffff)),
        ('backlight-update-policy', u32(4)),
        ('display-backlight-calibration', u32(0)),
        ('use-AAB-architecture', u32(1)),
        ('use-Nits2DACTable', u32(1)),
        ('LminProduct', u32(2 * 65536)),
        ('LmidProduct', u32(155 * 65536)),
        ('LmaxProduct', u32(625 * 65536)),
        ('digital-dimming-min-nits', u32(2 * 65536)),
        ('user-accessible-max-nits', u32(625 * 65536)),
        ('backlight-marketing-table', marketing_table),
        ('DBVMin', u32(139)),
        ('DBVMax', u32(2047)),
        ('panel-gamma', u32(2200)),
        ('max-nit-value', u32(625)),
        ('aab-constraint-lmax', u32(625)),
    ])

    baseband_nodes = [node('baseband', [
        ph(),
        ('device_type', cstr('baseband')),
        ('compatible', cstr('baseband,i19')),
        ('class', u32(3)),
    ])] if baseband else []

    product = d22ap_product(ph) if d22ap else d421ap_product(ph)

    target_type, target_sub, model = ('D22', 'D22AP', 'iPhone10,3') if d22ap else ('D421', 'D421AP', 'iPhone12,3')

    # Identity of the iPhone whose userland runs here: compatible[0] is
    # hw.model/hw.target (D421AP or D22AP), model is hw.machine/hw.product
    # (iPhone12,3 or iPhone10,3). 'xiaomi,sweet' stays for SweetPlatformExpert matching.
    return node('device-tree', [
        ph(),
        ('compatible', cstr(target_sub) + cstr(model) + cstr('xiaomi,sweet') + cstr('google,flame') + cstr('realme,rmx1931') + cstr('qcom,sm8150') + cstr('AppleARM')),
        ('model', cstr(model)),
        ('target-type', cstr(target_type)),
        ('target-sub-type', cstr(target_sub)),
        ('manufacturer', cstr('Apple Inc.')),
        ('#address-cells', u32(2)),
        ('#size-cells', u32(2)),
    ], [chosen, defaults, cpus, arm_io, fbcon, buttons, backlight] + baseband_nodes + product + ([] if qemu else [ufs, pram]))


if __name__ == '__main__':
    canvas_h = 2499
    for idx, arg in enumerate(sys.argv[1:], 1):
        if arg == '--canvas-height' and idx + 1 < len(sys.argv):
            canvas_h = int(sys.argv[idx + 1])
        elif arg.startswith('--canvas-height='):
            canvas_h = int(arg.split('=', 1)[1])
    root = build(
        qemu='--qemu' in sys.argv,
        highmem='--highmem' in sys.argv,
        d22ap='--d22ap' in sys.argv,
        canvas_height=canvas_h,
        baseband='--baseband' in sys.argv,
    )
    blob = adt.serialize(root)
    open(sys.argv[1], 'wb').write(blob)
    if '--dump' in sys.argv:
        adt.dump(adt.parse(blob)[0])
    print(f'{sys.argv[1]}: {len(blob)} bytes', file=sys.stderr)
