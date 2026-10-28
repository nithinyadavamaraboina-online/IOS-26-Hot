#!/usr/bin/env python3
"""Apply the SWEET bring-up changes to an iOS root made by tools/mkiosroot.sh.

  sudo sweet-root-tweaks.py MOUNTED_ROOT

MOUNTED_ROOT is the HFS+ image mounted read-write (Linux: run
`fsck.hfsplus -fy` on the image, then `mount -t hfsplus -o loop,force`).
Run as root (it writes root/mobile owned files). Idempotent. This is what
turned the plain mkiosroot.sh output into the images that reach the home
screen (image versions v8-v14 in NOTES.md):

  launchd.plist   _PanicOnCrash -> _PanicOnCrasX (a crashing daemon must not
                  panic the system); CommCenter jobs removed (no baseband: it
                  crash-loops and Setup blocks on its CoreTelephony XPC)
  containers      /private/var/containers/{Bundle,Data/System,Shared/SystemGroup,Temp}
                  (on iOS they live on the data volume)
  migration       LastSystemVersion so DataMigration does not run for an hour
                  on every boot
  audio           VirtualAudio HAL plug-in disabled (mediaserverd crash)
  MediaToolbox    StartRecorderServer=false (camera filter prewarm calls a
                  NULL OpenGL function without a GPU -> mediaserverd crash loop)
  SpringBoard     BKNoWatchdogs (one slow CPU path, no scene-create watchdog
                  kills), no cloud-configuration UI
  Setup           skipped: SetupDone, SetupFinishedAllSteps, SetupVersion 11,
                  mini-buddy flags off
  activation      mobileactivationd data ark seeded; the actual Activated state
                  comes from its Hactivation branch, selected by the /chosen
                  security-status properties in tools/mkadt.py
"""
import glob, os, plistlib, shutil, sys

DEFAULT_BUILD, DEFAULT_VERSION = '20H392', '16.7.16'
MOBILE = 501


def detect_system_version(root):
    for rel in ('System/Library/CoreServices/SystemVersion.plist',
                'private/preboot/Cryptexes/OS/System/Library/CoreServices/SystemVersion.plist'):
        p = os.path.join(root, rel)
        if os.path.exists(p):
            sv = load(p)
            return sv.get('ProductBuildVersion', DEFAULT_BUILD), sv.get('ProductVersion', DEFAULT_VERSION)
    return DEFAULT_BUILD, DEFAULT_VERSION


def load(path):
    with open(path, 'rb') as f:
        return plistlib.load(f)


def save(path, data, uid, gid, mode, fmt=plistlib.FMT_BINARY):
    with open(path, 'wb') as f:
        plistlib.dump(data, f, fmt=fmt)
    os.chown(path, uid, gid)
    os.chmod(path, mode)


def merge_pref(root, name, values):
    """Merge keys into /var/mobile/Library/Preferences/NAME.plist (mobile, 0600)."""
    path = os.path.join(root, 'private/var/mobile/Library/Preferences', name + '.plist')
    data = load(path) if os.path.exists(path) else {}
    data.update(values)
    save(path, data, MOBILE, MOBILE, 0o600)
    print('pref', name, sorted(values))


SKIP_LABELS = {
    'com.apple.CommCenter',
    'com.apple.aned',
    'com.apple.aneuserd',
    'com.apple.modelcatalogd',
    'com.apple.generativeexperiencesd',
    'com.apple.security.cryptexd',
    'com.apple.ManagedSettingsAgent',
    'com.apple.deviceaccessd',
    'com.apple.passd',
    'com.apple.icloud.findmydeviced',
    'com.apple.findmy.findmylocated',
    'com.apple.findmy.findmybeaconingd',
    'com.apple.driverkitd',
    'com.apple.webinspectord',
    'com.apple.PerfPowerServices',
    'com.apple.PerfPowerServicesExtended',
    'com.apple.PerfPowerTelemetryClientRegistrationService',
    'com.apple.perfpowermetricd',
    'com.apple.audioaccessoryd',
    'com.apple.cloudpaird',
    'com.apple.nfcd',
    'com.apple.seld',
    'com.apple.softposreaderd',
    'com.apple.wifip2pd',
    'com.apple.biomed',
    'com.apple.biomesyncd',
    'com.apple.biometrickitd.pearl',
    'com.apple.nanotimekitcompaniond',
    'com.apple.nanoregistryd',
    'com.apple.nanoregistrylaunchd',
    'com.apple.nanoprefsyncd',
    'com.apple.nanoprefsyncd.2',
    'com.apple.nanobackupd',
    'com.apple.cameracaptured',
    'com.apple.familycircled',
    'com.apple.factory.NFQRCoded',
    'com.apple.watchdogd',
    'com.apple.callintelligenced',
    'com.apple.localspeechrecognition',
    'com.apple.siriinferenced',
    'com.apple.sirittsd',
}

SKIP_PLISTS = {
    'com.apple.security.cryptexd.plist',
    'com.apple.ManagedSettingsAgent.plist',
    'com.apple.deviceaccessd.plist',
    'com.apple.passd.plist',
    'com.apple.icloud.findmydeviced.plist',
    'com.apple.findmy.findmylocated.plist',
    'com.apple.findmy.findmybeaconingd.plist',
    'com.apple.driverkitd.plist',
    'com.apple.webinspectord.plist',
    'com.apple.PerfPowerServices.plist',
    'com.apple.PerfPowerServicesExtended.plist',
    'com.apple.PerfPowerTelemetryClientRegistrationService.plist',
    'com.apple.perfpowermetricd.plist',
    'com.apple.audioaccessoryd.plist',
    'com.apple.cloudpaird.plist',
    'com.apple.nfcd.plist',
    'com.apple.seld.plist',
    'com.apple.softposreaderd.plist',
    'com.apple.wifip2pd.plist',
    'com.apple.biomed.plist',
    'com.apple.biomesyncd.plist',
    'com.apple.biometrickitd.pearl.plist',
    'com.apple.nanotimekitcompaniond.plist',
    'com.apple.nanoregistryd.plist',
    'com.apple.nanoregistrylaunchd.plist',
    'com.apple.nanoprefsyncd.plist',
    'com.apple.nanobackupd.plist',
    'com.apple.cameracaptured.plist',
    'com.apple.familycircled.plist',
    'com.apple.factory.NFQRCoded.plist',
    'com.apple.watchdogd.plist',
    'com.apple.callintelligenced.plist',
    'com.apple.localspeechrecognition.plist',
    'com.apple.siriinferenced.plist',
    'com.apple.sirittsd.plist',
}

SKIP_PREFIXES = (
    '/System/Library/NanoLaunchDaemons/',
)

IDLE_XPC_KEY = '/System/Library/LaunchDaemons/org.iosonpixel.idlexpc.plist'
IDLE_XPC_MACH_SERVICES = (
    'com.apple.BTAudioHALPlugin.xpc',
    'com.apple.DeviceAccess.xpc',
    'com.apple.bluetooth.xpc',
)


def ensure_idle_xpc_daemon(jobs):
    """Claim absent Bluetooth/DeviceAccess XPC MachServices via hot-idle-xpc to prevent lookup storms."""
    claimed = {
        svc
        for k, j in jobs.items()
        if k != IDLE_XPC_KEY and isinstance(j, dict) and isinstance(j.get('MachServices'), dict)
        for svc in j['MachServices']
    }
    mach_svcs = {svc: True for svc in IDLE_XPC_MACH_SERVICES if svc not in claimed}
    if mach_svcs:
        jobs[IDLE_XPC_KEY] = {
            'KeepAlive': False,
            'Label': 'org.iosonpixel.idlexpc',
            'MachServices': mach_svcs,
            'ProcessType': 'Background',
            'ProgramArguments': ['/var/local/bin/hot-idle-xpc'],
            'RunAtLoad': False,
            'UserName': 'root',
        }
    return sorted(mach_svcs)


def clean_launchd_plist(plist):
    """Rename _PanicOnCrash -> _PanicOnCrasX and strip hardware-absent/crash-looping LaunchDaemons."""
    jobs = plist['LaunchDaemons']
    renamed = 0
    for job in jobs.values():
        if '_PanicOnCrash' in job:
            job['_PanicOnCrasX'] = job.pop('_PanicOnCrash')
            renamed += 1
    bb_job = jobs.get('/System/Library/LaunchDaemons/com.apple.backboardd.plist')
    if isinstance(bb_job, dict):
        bb_env = dict(bb_job.get('EnvironmentVariables') or {})
        bb_env['CA_DISABLE_FRAMEBUFFER_COMPRESSION'] = '1'
        bb_job['EnvironmentVariables'] = bb_env
    removed = [
        k for k, j in jobs.items()
        if j.get('Label') in SKIP_LABELS
        or os.path.basename(k) in SKIP_PLISTS
        or k.startswith(SKIP_PREFIXES)
    ]
    for k in removed:
        del jobs[k]
    return renamed, removed


def launchd(root):
    path = os.path.join(root, 'System/Library/xpc/launchd.plist')
    st = os.stat(path)
    plist = load(path)
    renamed, removed = clean_launchd_plist(plist)
    if os.path.isfile(os.path.join(root, 'private/var/local/bin/hot-idle-xpc')):
        ensure_idle_xpc_daemon(plist['LaunchDaemons'])
    save(path, plist, st.st_uid, st.st_gid, st.st_mode & 0o7777)
    print(f'launchd.plist: {renamed} _PanicOnCrash renamed, {len(removed)} unsupported hw/ANE/Nano jobs removed')


def containers(root):
    base = os.path.join(root, 'private/var/containers')
    for sub, uid in (('Bundle', 33), ('Data/System', 0), ('Shared/SystemGroup', 0), ('Temp', 0)):
        d = os.path.join(base, sub)
        if not os.path.isdir(d):
            os.makedirs(d)
            os.chown(d, uid, uid)
            os.chmod(d, 0o755)
            print('mkdir', d)
    for rel, mode in (('private/var/local', 0o777), ('private/var/local/m2v-cache', 0o777), ('private/etc/dropbear', 0o700)):
        d = os.path.join(root, rel)
        os.makedirs(d, exist_ok=True)
        os.chmod(d, mode)
    for rel, uid, gid, mode in (
        ('private/var/Keychains', 64, 64, 0o775),
        ('private/var/Keychains/Analytics', 64, 64, 0o775),
        ('private/var/mobile/Library/Keychains', MOBILE, 64, 0o775),
        ('private/var/protected', 0, 0, 0o755),
        ('private/var/protected/sfanalytics', 64, 64, 0o775),
    ):
        d = os.path.join(root, rel)
        os.makedirs(d, exist_ok=True)
        os.chown(d, uid, gid)
        os.chmod(d, mode)
    kc_link = os.path.join(root, 'private/var/keychains')
    if not os.path.lexists(kc_link):
        os.symlink('Keychains', kc_link)


def find_container(root, kind, identifier):
    for meta in glob.glob(os.path.join(root, 'private/var/containers', kind, '*',
                                       '.com.apple.mobile_container_manager.metadata.plist')):
        if load(meta).get('MCMMetadataIdentifier') == identifier:
            return os.path.dirname(meta)
    return None


def activation(root, build, version):
    c = find_container(root, 'Data/System', 'com.apple.mobileactivationd')
    if c is None:
        print('warning: no mobileactivationd system container in the image; skipped data ark')
        return
    d = os.path.join(c, 'Library/internal')
    os.makedirs(d, exist_ok=True)
    os.chown(d, MOBILE, MOBILE)
    os.chmod(d, 0o755)
    save(os.path.join(d, 'data_ark.plist'), {
        '-ActivationState': 'Activated', '-BrickState': False,
        '-BuildVersion': build, '-ProductVersion': version,
        '-DataMigrationCompleted': True, '-PreSydroDataMigrationCompleted': True,
    }, MOBILE, MOBILE, 0o600)
    print('data ark', d, build, version)


def audio(root):
    src = os.path.join(root, 'Library/Audio/Plug-Ins/HAL/VirtualAudio.plugin')
    dst_dir = os.path.join(root, 'Library/Audio/Plug-Ins/HAL.disabled')
    if os.path.exists(src):
        os.makedirs(dst_dir, exist_ok=True)
        shutil.move(src, dst_dir)
        print('VirtualAudio.plugin disabled')
    for conf in glob.glob(os.path.join(root, 'System/Library/Frameworks/MediaToolbox.framework/*/SubServerConfiguration.plist')):
        st = os.stat(conf)
        data = load(conf)
        data['StartRecorderServer'] = False
        save(conf, data, st.st_uid, st.st_gid, st.st_mode & 0o7777, plistlib.FMT_XML)
        print('StartRecorderServer off', os.path.relpath(conf, root))


JETSAM_LIMIT_KEYS = (
    'ActiveHardMemoryLimit',
    'InactiveHardMemoryLimit',
    'ActiveSoftMemoryLimit',
    'InactiveSoftMemoryLimit',
)


def patch_jetsam_limits(obj, min_mb=64):
    """Raise tight 16K-page D421 daemon/XPC memory limits (< min_mb) for 8 GB RAM + 4K pages."""
    raised = 0
    if isinstance(obj, dict):
        for k, v in list(obj.items()):
            if k in JETSAM_LIMIT_KEYS and isinstance(v, int) and not isinstance(v, bool) and 0 < v < min_mb:
                obj[k] = min_mb
                raised += 1
            elif isinstance(v, (dict, list)):
                raised += patch_jetsam_limits(v, min_mb)
    elif isinstance(obj, list):
        for item in obj:
            if isinstance(item, (dict, list)):
                raised += patch_jetsam_limits(item, min_mb)
    return raised


def jetsam(root, min_mb=64):
    for path in sorted(glob.glob(os.path.join(root, 'System/Library/LaunchDaemons/com.apple.jetsamproperties.*.plist'))):
        st = os.stat(path)
        data = load(path)
        n = patch_jetsam_limits(data, min_mb=min_mb)
        if n > 0:
            save(path, data, st.st_uid, st.st_gid, st.st_mode & 0o7777)
            print(f'jetsam {os.path.basename(path)}: raised {n} limits to >= {min_mb} MB')


def main():
    if len(sys.argv) != 2 or not os.path.isdir(os.path.join(sys.argv[1], 'System/Library/xpc')):
        sys.exit(__doc__)
    root = sys.argv[1]
    build, version = detect_system_version(root)
    major = int(version.split('.')[0]) if version and version.split('.')[0].isdigit() else 16
    setup_ver = 15 if major >= 26 else 11
    print(f'target root: iOS {version} ({build}), SetupVersion={setup_ver}')
    launchd(root)
    jetsam(root)
    containers(root)
    activation(root, build, version)
    audio(root)
    merge_pref(root, 'com.apple.migration', {
        'LastSystemVersion': build,
        'DMUserDataDisposition': {'SyntheticDidUpgrade': False, 'buildVersion': build}})
    merge_pref(root, 'com.apple.springboard', {
        'BKNoWatchdogs': True,
        'FBLaunchWatchdogExceptions': {'com.apple.purplebuddy': 100.0},
        'MCForceShowCloudConfigurationUI': False})
    merge_pref(root, 'com.apple.purplebuddy', {
        'ForceNoBuddy': True,
        'SetupDone': True, 'SetupFinishedAllSteps': True, 'SetupVersion': setup_ver,
        'LastSystemVersion': build,
        'daemonizeNeedsToRun': False})
    merge_pref(root, 'com.apple.purplebuddy.notbackedup', {
        k: False for k in ('ShouldRunDiagnosticsMiniBuddy', 'ShouldRunHSA2MiniBuddy',
                           'ShouldRunKeychainSyncMiniBuddy', 'ShouldRunMesaMiniBuddy',
                           'ShouldRunPaymentMiniBuddy', 'ShouldRunStandardMiniBuddy',
                           'SilentICDPUpgrade')})
    merge_pref(root, 'com.apple.managedconfiguration.notbackedup', {
        'LockdownActivationIndicatesCloudConfigurationAvailable': False})
    merge_pref(root, 'com.apple.backboardd', {
        'CAEnableDeepFramebuffer': False,
        'CAEnableFramebufferCompression': False,
        'CAEnableIOSurfaceCompression': False})


if __name__ == '__main__':
    main()
