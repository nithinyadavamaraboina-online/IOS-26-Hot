#!/usr/bin/env python3
"""
Flavor-aware static gatekeeper for Android 17 ro.control_privapp_permissions=enforce:
- ROM_FLAVOR=pixelos:
  1. Asserts ZERO iOS apps (IosStyler, IosSettings, IosControlCenter, IosDialer, IosMessages) and ZERO
     iOS RRO overlays exist in OUT_APKS.
  2. Verifies NexusLauncherRelease.apk is unmodified retail Google Pixel Launcher (signed with Google's
     official CN=nexuslauncher SHA-256 a86bdb05..., versionCode=907, zero custom classes3.dex).
  3. Verifies 4-byte zipalign + signatures + 1:1 0x10 privapp-permissions parity on SamuraiOTAUpdater & SamuraiParts.
  4. Verifies patched services.jar (SamuraiPlatformSigBridge with STOCK_NEXUS_LAUNCHER_SHA256 + 4-byte zipalign).
- ROM_FLAVOR=ios-beta:
  1. Verifies all 18 APKs (NexusLauncher v17.7.0-ios26, 7 priv-apps, 10 RROs) + 1:1 0x10 parity.
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

WORK_ROOT = os.environ.get("WORK_ROOT", "/var/tmp/rmx1931-a17")
ROM_FLAVOR = os.environ.get("ROM_FLAVOR", "pixelos").strip().lower()
IS_IOS_BETA = (ROM_FLAVOR == "ios-beta")

BUILD_TOOLS = f"{WORK_ROOT}/tools/android-sdk/build-tools/35.0.0"
AAPT2 = f"{BUILD_TOOLS}/aapt2"
ZIPALIGN = f"{BUILD_TOOLS}/zipalign"
APKSIGNER = f"{BUILD_TOOLS}/apksigner"
OUT_APKS = f"{WORK_ROOT}/out/apks"
OUT_FWK = f"{WORK_ROOT}/out/framework"
FW_RES_STOCK = f"{WORK_ROOT}/downloads/pixel_a17_cp3a/framework-res_stock.apk"
SYSUI_STOCK = f"{WORK_ROOT}/downloads/pixel_a17_cp3a/SystemUI_stock.apk"
STOCK_NX_SHA256 = "a86bdb059f28f265162d64ce6c8d9772901d227e741581d1f04a5ed132a574d0"

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
A17_ROOT = os.path.dirname(SCRIPT_DIR)

RETIRED_APKS = {
    "ICall26.apk",
    "SamuraiICallOverlay.apk",
    "IosClock.apk",
    "IosCalculator.apk",
    "IosNotes.apk",
    "IosWeather.apk",
    "SystemUI.apk",
}

IOS_BETA_ONLY_APKS = {
    "IosStyler.apk",
    "IosSettings.apk",
    "IosControlCenter.apk",
    "IosDialer.apk",
    "IosMessages.apk",
    "SamuraiDialerOverlay.apk",
    "SamuraiMessagesOverlay.apk",
    "SamuraiGmailOverlay.apk",
    "SamuraiPhotosOverlay.apk",
    "SamuraiLauncherOverlay.apk",
    "SamuraiIos26WallpaperOverlay.apk",
}

PIXELOS_APKS = [
    "SamuraiOTAUpdater.apk",
    "SamuraiParts.apk",
    "SamuraiHardwareOverlay.apk",
    "SamuraiSystemUIOverlay.apk",
    "SamuraiSettingsOverlay.apk",
    "SamuraiHidePillOverlay.apk",
    "NexusLauncherRelease.apk",
]

IOS_BETA_APKS = [
    "SamuraiOTAUpdater.apk",
    "SamuraiParts.apk",
    "IosStyler.apk",
    "IosSettings.apk",
    "IosControlCenter.apk",
    "IosDialer.apk",
    "IosMessages.apk",
    "SamuraiHardwareOverlay.apk",
    "SamuraiSystemUIOverlay.apk",
    "SamuraiSettingsOverlay.apk",
    "SamuraiDialerOverlay.apk",
    "SamuraiMessagesOverlay.apk",
    "SamuraiGmailOverlay.apk",
    "SamuraiPhotosOverlay.apk",
    "SamuraiLauncherOverlay.apk",
    "SamuraiIos26WallpaperOverlay.apk",
    "SamuraiHidePillOverlay.apk",
    "NexusLauncherRelease.apk",
]

FALLBACK_PRIV_0X10 = {
    "android.permission.REBOOT",
    "android.permission.RECOVERY",
    "android.permission.ACCESS_CACHE_FILESYSTEM",
    "android.permission.DELETE_CACHE_FILES",
    "android.permission.WRITE_SECURE_SETTINGS",
    "android.permission.PACKAGE_USAGE_STATS",
    "android.permission.REAL_GET_TASKS",
    "android.permission.INTERACT_ACROSS_USERS",
    "android.permission.CHANGE_OVERLAY_PACKAGES",
    "android.permission.STATUS_BAR",
    "android.permission.TETHER_PRIVILEGED",
    "android.permission.BLUETOOTH_PRIVILEGED",
    "android.permission.MODIFY_PHONE_STATE",
    "android.permission.READ_PRIVILEGED_PHONE_STATE",
    "android.permission.SCHEDULE_EXACT_ALARM",
    "android.permission.TURN_SCREEN_ON",
    "android.permission.START_ACTIVITIES_FROM_BACKGROUND",
    "android.permission.SUBSTITUTE_NOTIFICATION_APP_NAME",
    "android.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE",
}

SHARED_PRIV_APPS = [
    (
        "SamuraiOTAUpdater.apk",
        "com.realme.ota",
        os.path.join(A17_ROOT, "SamuraiOTAUpdater", "etc", "permissions", "privapp-permissions-ota.xml"),
    ),
    (
        "SamuraiParts.apk",
        "com.realme.samurai.parts",
        os.path.join(A17_ROOT, "SamuraiParts", "etc", "permissions", "privapp-permissions-samuraiparts.xml"),
    ),
]

IOS_BETA_PRIV_APPS = [
    (
        "IosStyler.apk",
        "com.realme.samurai.styler",
        os.path.join(A17_ROOT, "ios-styler", "privapp-permissions-styler.xml"),
    ),
    (
        "IosSettings.apk",
        "com.realme.samurai.iossettings",
        os.path.join(A17_ROOT, "ios-settings", "privapp-permissions-ios-settings.xml"),
    ),
    (
        "IosControlCenter.apk",
        "com.realme.samurai.controlcenter",
        os.path.join(A17_ROOT, "ios-controlcenter", "privapp-permissions-ios-controlcenter.xml"),
    ),
    (
        "IosDialer.apk",
        "com.realme.samurai.dialer",
        os.path.join(A17_ROOT, "ios-dialer", "privapp-permissions-ios-dialer.xml"),
    ),
    (
        "IosMessages.apk",
        "com.realme.samurai.messages",
        os.path.join(A17_ROOT, "ios-messages", "privapp-permissions-ios-messages.xml"),
    ),
]


def load_framework_priv_permissions() -> set[str]:
    if not os.path.isfile(FW_RES_STOCK):
        return set(FALLBACK_PRIV_0X10)
    out = subprocess.check_output([AAPT2, "dump", "xmltree", FW_RES_STOCK, "--file", "AndroidManifest.xml"], text=True)
    priv_0x10 = set()
    cur_perm = None
    in_perm = False
    for line in out.splitlines():
        s = line.strip()
        if s.startswith("E: permission ("):
            in_perm = True
            cur_perm = None
        elif s.startswith("E: "):
            in_perm = False
            cur_perm = None
        elif in_perm and ":name(" in s:
            m = re.search(r'="([^"]+)"', s)
            if m:
                cur_perm = m.group(1)
        elif in_perm and ":protectionLevel(" in s:
            m = re.search(r"=(0x[0-9a-fA-F]+)", s)
            if m and cur_perm:
                val = int(m.group(1), 16)
                if val & 0x10:
                    priv_0x10.add(cur_perm)
    return priv_0x10 | FALLBACK_PRIV_0X10


def verify_privapp_parity(apk_name: str, pkg_name: str, xml_path: str, priv_0x10: set[str]) -> bool:
    apk_path = os.path.join(OUT_APKS, apk_name)
    if not os.path.isfile(xml_path):
        print(f"ERROR [{apk_name}]: Missing privapp-permissions XML at {xml_path}", file=sys.stderr)
        return False
    dump = subprocess.check_output([AAPT2, "dump", "permissions", apk_path], text=True)
    apk_perms = set()
    for line in dump.splitlines():
        m = re.search(r"uses-permission:\s*name='([^']+)'", line)
        if m:
            apk_perms.add(m.group(1))

    tree = ET.parse(xml_path)
    xml_perms = set()
    for elem in tree.findall(f".//privapp-permissions[@package='{pkg_name}']/permission"):
        xml_perms.add(elem.attrib["name"])

    required_priv = apk_perms & priv_0x10
    missing_in_xml = required_priv - xml_perms
    extra_in_xml = xml_perms - required_priv
    if missing_in_xml or extra_in_xml:
        print(
            f"ERROR [{apk_name}]: privapp-permissions 0x10 mismatch! missing_in_xml={sorted(missing_in_xml)} extra_in_xml={sorted(extra_in_xml)}",
            file=sys.stderr,
        )
        return False

    print(
        f"[OK] {apk_name} ({pkg_name}) privapp-permissions 1:1 parity verified "
        f"({len(xml_perms)} privileged 0x10, {len(apk_perms)} total permissions)"
    )
    return True


def main() -> int:
    if ROM_FLAVOR not in ("pixelos", "ios-beta"):
        print(f"ERROR: Unsupported ROM_FLAVOR='{ROM_FLAVOR}'", file=sys.stderr)
        return 1

    for fn in os.listdir(OUT_APKS):
        if "liquidglass" in fn.lower():
            print(f"ERROR: Stale LiquidGlass artifact found in {OUT_APKS}: {fn}", file=sys.stderr)
            return 1
        if fn in RETIRED_APKS:
            print(f"ERROR: Retired APK found in {OUT_APKS}: {fn}", file=sys.stderr)
            return 1
        if not IS_IOS_BETA and fn in IOS_BETA_ONLY_APKS:
            print(f"ERROR: iOS-Beta-only APK '{fn}' found in Pure PixelOS build output!", file=sys.stderr)
            return 1

    # Verify pristine stock SystemUI_stock.apk is clean and unmodified
    if not os.path.isfile(SYSUI_STOCK):
        print(f"ERROR: Missing pristine stock {SYSUI_STOCK}", file=sys.stderr)
        return 1
    sysui_sig = subprocess.run([APKSIGNER, "verify", "--verbose", SYSUI_STOCK], capture_output=True, text=True)
    if sysui_sig.returncode != 0:
        print(f"ERROR: apksigner verify failed for SystemUI_stock.apk:\n{sysui_sig.stderr}", file=sys.stderr)
        return 1
    with zipfile.ZipFile(SYSUI_STOCK, "r") as zf:
        if "classes4.dex" in zf.namelist():
            print("ERROR: SystemUI_stock.apk unexpectedly contains classes4.dex!", file=sys.stderr)
            return 1
        for dn in [n for n in zf.namelist() if n.endswith(".dex")]:
            if b"SamuraiSystemUiHooks" in zf.read(dn):
                print("ERROR: SystemUI_stock.apk contains custom SamuraiSystemUiHooks!", file=sys.stderr)
                return 1
    print("[OK] Pristine stock SystemUI_stock.apk (zero custom hooks, valid signature) verified!")

    priv_0x10 = load_framework_priv_permissions()
    expected_apks = IOS_BETA_APKS if IS_IOS_BETA else PIXELOS_APKS

    for name in expected_apks:
        p = os.path.join(OUT_APKS, name)
        if not os.path.isfile(p):
            print(f"ERROR: Missing expected APK {p}", file=sys.stderr)
            return 1
        za = subprocess.run([ZIPALIGN, "-c", "4", p], capture_output=True, text=True)
        if za.returncode != 0:
            print(f"ERROR: zipalign -c 4 failed for {name}:\n{za.stdout}\n{za.stderr}", file=sys.stderr)
            return 1
        res = subprocess.run([APKSIGNER, "verify", "--verbose", p], capture_output=True, text=True)
        if res.returncode != 0:
            print(f"ERROR: apksigner verify failed for {name}:\n{res.stderr}", file=sys.stderr)
            return 1
        print(f"[OK] zipalign -c 4 + Signature verified: {name}")

    priv_apps = SHARED_PRIV_APPS + (IOS_BETA_PRIV_APPS if IS_IOS_BETA else [])
    for apk_name, pkg_name, xml_path in priv_apps:
        if not verify_privapp_parity(apk_name, pkg_name, xml_path, priv_0x10):
            return 1

    # Verify patched services.jar (Dual-Channel Signature Bridge + 4-byte zipalign)
    services_jar = os.path.join(OUT_FWK, "services.jar")
    if not os.path.isfile(services_jar):
        services_jar = os.path.join(os.path.dirname(A17_ROOT), "work", "a17", "apks", "services.jar")
    if not os.path.isfile(services_jar):
        print(f"ERROR: Missing patched {services_jar}", file=sys.stderr)
        return 1
    with zipfile.ZipFile(services_jar, "r") as zf:
        if "classes4.dex" not in zf.namelist():
            print("ERROR: services.jar missing classes4.dex!", file=sys.stderr)
            return 1
        c4 = zf.read("classes4.dex")
        if b"SamuraiPlatformSigBridge" not in c4 or b"STOCK_NEXUS_LAUNCHER_SHA256" not in c4:
            print("ERROR: services.jar missing SamuraiPlatformSigBridge / STOCK_NEXUS_LAUNCHER_SHA256!", file=sys.stderr)
            return 1
    za_svc = subprocess.run([ZIPALIGN, "-c", "4", services_jar], capture_output=True, text=True)
    if za_svc.returncode != 0:
        print(f"ERROR: services.jar failed 4-byte zipalign check:\n{za_svc.stdout}\n{za_svc.stderr}", file=sys.stderr)
        return 1
    print("[OK] services.jar (classes4.dex + Dual-Channel SamuraiPlatformSigBridge + 4-byte zipalign) verified!")

    nx_apk = os.path.join(OUT_APKS, "NexusLauncherRelease.apk")
    nx_xml = os.path.join(A17_ROOT, "SamuraiParts", "etc", "permissions", "privapp-permissions-pixel-launcher.xml")
    nx_badge = subprocess.check_output([AAPT2, "dump", "badging", nx_apk], text=True)
    nx_dump = subprocess.check_output([AAPT2, "dump", "permissions", nx_apk], text=True)
    nx_apk_perms = {
        m.group(1) for line in nx_dump.splitlines() if (m := re.search(r"uses-permission:\s*name='([^']+)'", line))
    }
    nx_tree = ET.parse(nx_xml)
    nx_xml_perms = {
        e.attrib["name"]
        for e in nx_tree.findall(".//privapp-permissions[@package='com.google.android.apps.nexuslauncher']/permission")
    }
    missing_nx_priv = (nx_apk_perms & priv_0x10) - nx_xml_perms
    if missing_nx_priv:
        print(f"ERROR: NexusLauncherRelease.apk missing 0x10 whitelist entries: {sorted(missing_nx_priv)}", file=sys.stderr)
        return 1

    if not IS_IOS_BETA:
        if "versionCode='907'" not in nx_badge:
            print(f"ERROR: Pure PixelOS NexusLauncherRelease.apk is not unmodified retail v907:\n{nx_badge[:300]}", file=sys.stderr)
            return 1
        cert_out = subprocess.check_output([APKSIGNER, "verify", "--print-certs", nx_apk], text=True).lower()
        if STOCK_NX_SHA256 not in cert_out:
            print(f"ERROR: Pure PixelOS NexusLauncherRelease.apk is not signed with Google cert {STOCK_NX_SHA256}!", file=sys.stderr)
            return 1
        with zipfile.ZipFile(nx_apk, "r") as zf:
            if "classes3.dex" in zf.namelist():
                print("ERROR: Pure PixelOS NexusLauncherRelease.apk contains custom classes3.dex!", file=sys.stderr)
                return 1
        print(
            "[OK] Pure PixelOS verified: unmodified Google-signed NexusLauncherRelease.apk (v907), "
            "4 hardware RROs, ZERO iOS apps, ZERO iOS overlays!"
        )
        return 0

    # iOS Beta specific checks
    if "versionCode='934'" not in nx_badge or "versionName='17.7.0-ios26'" not in nx_badge:
        print(f"ERROR: NexusLauncherRelease.apk missing versionCode=934 / versionName=17.7.0-ios26:\n{nx_badge[:300]}", file=sys.stderr)
        return 1
    with zipfile.ZipFile(nx_apk, "r") as zf:
        for dn in [n for n in zf.namelist() if n.endswith(".dex")]:
            if b"SamuraiRecentsStackController" in zf.read(dn):
                print(f"ERROR: {dn} in NexusLauncherRelease.apk still contains SamuraiRecentsStackController!", file=sys.stderr)
                return 1

    styler_apk = os.path.join(OUT_APKS, "IosStyler.apk")
    styler_badge = subprocess.check_output([AAPT2, "dump", "badging", styler_apk], text=True)
    if "versionCode='20001'" not in styler_badge or "versionName='1.6.5-ios26'" not in styler_badge:
        print(f"ERROR: IosStyler.apk missing versionCode=20001 / versionName=1.6.5-ios26:\n{styler_badge[:300]}", file=sys.stderr)
        return 1

    cc_apk = os.path.join(OUT_APKS, "IosControlCenter.apk")
    cc_badge = subprocess.check_output([AAPT2, "dump", "badging", cc_apk], text=True)
    if "versionCode='20012'" not in cc_badge or "versionName='1.7.7-ios26'" not in cc_badge:
        print(f"ERROR: IosControlCenter.apk missing versionCode=20012 / versionName=1.7.7-ios26:\n{cc_badge[:300]}", file=sys.stderr)
        return 1

    for priv_comms_name, exp_pkg, exp_vc, exp_vn in [
        ("IosDialer.apk", "com.realme.samurai.dialer", "20042", "1.7.6-ios26"),
        ("IosMessages.apk", "com.realme.samurai.messages", "20040", "1.7.5-ios26"),
    ]:
        c_apk = os.path.join(OUT_APKS, priv_comms_name)
        c_badge = subprocess.check_output([AAPT2, "dump", "badging", c_apk], text=True)
        if f"name='{exp_pkg}'" not in c_badge:
            print(f"ERROR: {priv_comms_name} package mismatch:\n{c_badge[:300]}", file=sys.stderr)
            return 1
        if f"versionCode='{exp_vc}'" not in c_badge or f"versionName='{exp_vn}'" not in c_badge:
            print(f"ERROR: {priv_comms_name} missing versionCode={exp_vc} / versionName={exp_vn}:\n{c_badge[:300]}", file=sys.stderr)
            return 1
        if "liquidglass" in c_badge.lower():
            print(f"ERROR: {priv_comms_name} badging contains forbidden 'liquidglass' identifier!", file=sys.stderr)
            return 1
        with zipfile.ZipFile(c_apk, "r") as zf:
            dex_blob = b"".join(zf.read(dn) for dn in zf.namelist() if dn.endswith(".dex"))
        if b"liteapks" in dex_blob.lower():
            print(f"ERROR: {priv_comms_name} contains forbidden third-party adware string!", file=sys.stderr)
            return 1
        if priv_comms_name == "IosDialer.apk":
            for req_sym in (b"Lcom/android/incallui/InCallServiceImpl;", b"Lcom/realme/samurai/dialer/IosCallActivity;"):
                if req_sym not in dex_blob:
                    print(f"ERROR: IosDialer.apk missing required symbol {req_sym.decode()}!", file=sys.stderr)
                    return 1
            mf_tree = subprocess.check_output([AAPT2, "dump", "xmltree", c_apk, "--file", "AndroidManifest.xml"], text=True)
            if "android.intent.action.CALL_PRIVILEGED" in mf_tree or '"android.intent.action.CALL"' in mf_tree:
                print("ERROR: IosDialer.apk must not intercept ACTION_CALL / CALL_PRIVILEGED (TelecomShim owns CALL)!", file=sys.stderr)
                return 1
            if "com.android.dialer.DialtactsActivity" not in mf_tree or "com.android.incallui.InCallServiceImpl" not in mf_tree:
                print("ERROR: IosDialer.apk missing Telecom DialtactsActivity alias or InCallServiceImpl!", file=sys.stderr)
                return 1
        else:
            if b"placeCall" not in dex_blob or b"Audio call" not in dex_blob:
                print("ERROR: IosMessages.apk missing TelecomManager.placeCall / Audio call integration!", file=sys.stderr)
                return 1
            if b"FaceTime Unavailable" in dex_blob:
                print("ERROR: IosMessages.apk still contains dead 'FaceTime Unavailable' string!", file=sys.stderr)
                return 1
        print(f"[OK] {priv_comms_name} ({exp_pkg} v{exp_vn}) clean-room /system/priv-app verified!")

    if "android.permission.READ_WALLPAPER_INTERNAL" not in nx_apk_perms or "android.permission.READ_WALLPAPER_INTERNAL" not in nx_xml_perms:
        print("ERROR: NexusLauncherRelease.apk / privapp-permissions-pixel-launcher.xml missing READ_WALLPAPER_INTERNAL!", file=sys.stderr)
        return 1
    print(
        f"[OK] iOS Beta verified: NexusLauncherRelease.apk (v17.7.0-ios26, versionCode=934) + IosStyler.apk + "
        f"IosSettings.apk + IosControlCenter.apk + IosDialer.apk + IosMessages.apk + services.jar verified "
        f"({len(nx_xml_perms)} whitelisted launcher permissions)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
