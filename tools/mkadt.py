#!/usr/bin/env python3
"""Build the minimal Apple Device Tree that the SWEET xnu needs to boot on flame.

Only what the early boot path actually reads (see ios-sweet's NOTES.md); every
MMIO address comes from the flame (Pixel 4, SM8150) live FDT. The kernel's
built-in drivers still match the Redmi names (xiaomi,sweet, qcom,kryo470,
sweet,display), so those stay next to the flame ones.
Usage:  mkadt.py OUT.adt [--dump] [--qemu] [--highmem] [--rmx1931]
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
# --rmx1931 (8 GB LPDDR4X, DRAM top 0x2_80000000): 5.45 GiB managed XNU RAM + 512 MiB
# extra WC GPU pool at 0x2_5d000000..0x2_7d000000 (1,456 MiB total WC GPU pool).
HIGH_MEM_SIZE_RMX1931 = 0x15cc00000
GPOOL_HI_RMX1931 = (0x25d000000, 0x20000000)
MIRROR_PA_RMX1931 = 0x27e000000
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

# The iOS userland is the iPhone X (iPhone10,3 / D22AP) IPSW. MobileGestalt
# derives product type, device class, home-button-less UI, artwork etc. from
# the root identity and /product of the real D22AP device tree. That tree is
# an Apple artifact (kept out of git): extract it from the IPSW with
#   tools/remotezip.py URL DeviceTree.d22ap.im4p DIR
#   ipsw img4 im4p extract DIR/Firmware/all_flash/DeviceTree.d22ap.im4p -o d22ap.adt
D22AP_ADT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '../research/ipsw-x/d22ap.adt')


def _clean_adt_subtree(src_node, ph):
    name_bytes = dict((k, v) for k, f, v in src_node.props).get('name', b'\0')
    name = name_bytes.rstrip(b'\0').decode('latin1')
    props = [ph()] + [
        (k, v) for k, f, v in src_node.props
        if not f and k not in ('name', 'AAPL,phandle')
    ]
    children = [_clean_adt_subtree(ch, ph) for ch in src_node.children]
    return node(name, props, children)


def d22ap_product(ph, path=D22AP_ADT):
    """/product subtree of the real D22AP tree (including camera, haptics,
    audio, facetime, maps), without the syscfg placeholders iBoot would fill in
    (flag bit set) and with fresh AAPL,phandles."""
    if not os.path.exists(path):
        print(f'warning: {path} missing, no /product node', file=sys.stderr)
        return []
    root, _ = adt.parse(open(path, 'rb').read())
    for c in root.children:
        if dict((k, v) for k, f, v in c.props).get('name') == b'product\0':
            return [_clean_adt_subtree(c, ph)]
    raise SystemExit(f'{path}: no /product node')

u32 = lambda *v: struct.pack('<%dI' % len(v), *v)
u64 = lambda *v: struct.pack('<%dQ' % len(v), *v)
cstr = lambda s: s.encode() + b'\0'


PANIC_LOG_PA = 0x1fd000000
PANIC_LOG_PA_RMX1931 = 0x27d000000
PANIC_LOG_SIZE = 0x80000


def node(name, props=(), children=()):
    n = adt.Node()
    n.props = [('name', 0, cstr(name))] + [(k, 0, v) for k, v in props]
    n.children = list(children)
    return n


def build(seed=None, qemu=False, highmem=False, rmx1931=False):
    """qemu=True: same tree, but with QEMU virt's GIC addresses and CNTFRQ (tools/qemu-sweet.py)."""
    if rmx1931 and highmem:
        phys_base, mem_size = HIGH_PHYS_BASE, HIGH_MEM_SIZE_RMX1931
    elif highmem:
        phys_base, mem_size = HIGH_PHYS_BASE, HIGH_MEM_SIZE
    else:
        phys_base, mem_size = PHYS_BASE, MEM_SIZE
    panic_pa = PANIC_LOG_PA_RMX1931 if (rmx1931 and highmem) else PANIC_LOG_PA
    gicd, gicr, timebase = (QEMU_GICD, QEMU_GICR, QEMU_TIMEBASE) if qemu else (GICD, GICR, TIMEBASE)
    seed = seed if seed is not None else os.urandom(64)
    phandle = iter(range(1, 1000))
    ph = lambda: ('AAPL,phandle', u32(next(phandle)))

    chosen_extra = []
    if rmx1931 and highmem:
        chosen_extra = [
            ('sweet-gpool-hi', u64(*GPOOL_HI_RMX1931)),
            ('sweet-mirror-pa', u64(MIRROR_PA_RMX1931)),
        ]

    chosen = node('chosen', [
        ph(),
        ('dram-base', u64(phys_base)),
        ('dram-size', u64(mem_size)),
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
        # SpringBoard's FBSDisplayMode "infxinf@0x" invalid. The display is
        # presented as the iPhone X's 1125x2436 @3x.
        ('display-scale', u32(3)),
        # XNU derives IOPlatformUUID (gethostuuid) from these on arm; without
        # them CFPreferences cannot use kCFPreferencesCurrentHost (no ByHost
        # files), so e.g. DataMigration never records LastSystemVersion and
        # migrates on every boot. Synthetic ECID (not the phone's); chip-id
        # of the A11 that the iPhone X userland expects.
        ('unique-chip-id', struct.pack('<Q', SWEET_ECID)),
        ('chip-id', u32(0x8015)),
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
    ] + chosen_extra, [node('memory-map', [ph()]),
        # Local boot policy (kern_csr.c): SIP relaxed like `csrutil disable`
        # (lp-sip0: untrusted kexts, unrestricted FS, task_for_pid, kernel
        # debugger, dtrace, nvram), lp-sip1: unauthenticated (unsealed) root.
        # The iOS shared cache is in no trust cache, so dyld can only map it
        # with CSR_ALLOW_UNRESTRICTED_FS (vm_unix.c shared region checks).
        node('asmb', [ph(), ('lp-sip0', u64(0x6f)), ('lp-sip1', u32(1))])])

    # AMP on SM8150/SM8150-AC: cpu0-3 Cortex-A55 ('E'), cpu4-6 Cortex-A76 + cpu7 Prime ('P').
    # QEMU retains its original single-CPU CLUSTER_TYPE_SMP tree.
    cpu_nodes = []
    for core in range(1 if qemu else 8):
        is_p = (not qemu) and (core >= 4)
        prime_hz = 2956800000 if rmx1931 else 2841600000
        cpu_nodes.append(node(f'cpu{core}', [
            ph(),
            ('device_type', cstr('cpu')),
            ('compatible', cstr('qcom,kryo470') + cstr('qcom,kryo485') + cstr('ARM,v8')),
            ('reg', u32(core << 8)),  # actual MPIDR Aff1:Aff0, NOT logical ID
            ('cpu-id', u32(core)),   # descriptive: XNU assigns IDs by DT order
            ('cluster-id', u32(1 if is_p else 0)),
            ('cluster-type', u32(0) if qemu else cstr('P' if is_p else 'E')),
            ('die-cluster-id', u32(1 if is_p else 0)),
            ('cluster-core-id', u32(core)),
            ('l2-cache-id', u32(core)),  # per-core L2 nodes in the live FDT
            ('l3-cache-id', u32(0)),     # shared L3
            ('state', cstr('running' if core == 0 else 'stopped')),
            ('timebase-frequency', u32(timebase)),
            ('clock-frequency', u32(prime_hz if core == 7 else 2419200000 if core >= 4 else 1785600000)),
            ('bus-frequency', u32(100000000)),
            ('memory-frequency', u32(2133000000 if rmx1931 else 1866000000)),
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
            ('canvas-height', u32(2436)),
        ])])

    fbcon = node('sweet-fbcon', [
        ph(),
        ('reg', u64(FB, FB_SIZE)),
        ('width', u32(FB_W)),
        ('height', u32(FB_H)),
        ('stride', u32(FB_STRIDE)),
    ])

    # iBoot's panic region (pe_init.c check_for_panic_log): RAM above XNU's range
    # and below the console mirror.
    pram = node('pram', [ph(), ('reg', struct.pack('<2Q', panic_pa, PANIC_LOG_SIZE))])

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
    # (IONameMatch = 'backlight', calibration constants from D22AP /backlight).
    backlight = node('backlight', [
        ph(),
        ('compatible', cstr('backlight')),
        ('device_type', cstr('backlight')),
        ('LminProduct', u32(131072)),
        ('LmidProduct', u32(10158080)),
        ('LmaxProduct', u32(44173121)),
        ('DBVMin', u32(139)),
        ('DBVMax', u32(2047)),
        ('panel-gamma', u32(2200)),
        ('max-nit-value', u32(750)),
        ('aab-constraint-lmax', u32(675)),
        ('user-accessible-max-nits', u32(625)),
    ])

    product = d22ap_product(ph)

    # Identity of the iPhone X whose userland runs here: compatible[0] is
    # hw.model/hw.target (D22AP), model is hw.machine/hw.product
    # (iPhone10,3). 'xiaomi,sweet' stays for SweetPlatformExpert matching.
    return node('device-tree', [
        ph(),
        ('compatible', cstr('D22AP') + cstr('iPhone10,3') + cstr('xiaomi,sweet') + cstr('google,flame') + cstr('realme,rmx1931') + cstr('qcom,sm8150') + cstr('AppleARM')),
        ('model', cstr('iPhone10,3')),
        ('target-type', cstr('D22')),
        ('target-sub-type', cstr('D22AP')),
        ('manufacturer', cstr('Apple Inc.')),
        ('#address-cells', u32(2)),
        ('#size-cells', u32(2)),
    ], [chosen, defaults, cpus, arm_io, fbcon, buttons, backlight] + product + ([] if qemu else [ufs, pram]))


if __name__ == '__main__':
    root = build(qemu='--qemu' in sys.argv, highmem='--highmem' in sys.argv, rmx1931='--rmx1931' in sys.argv)
    blob = adt.serialize(root)
    open(sys.argv[1], 'wb').write(blob)
    if '--dump' in sys.argv:
        adt.dump(adt.parse(blob)[0])
    print(f'{sys.argv[1]}: {len(blob)} bytes', file=sys.stderr)
