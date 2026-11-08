#!/usr/bin/env python3
"""
Build patched Android 17 `/system/framework/services.jar` (`patch_services_a17.py`):
1. Decodes pristine stock `services_stock.jar` via `apktool.jar` (`-r`).
2. Injects two strictly-scoped hooks into:
   - `AppIdPermissionPolicy.shouldGrantPermissionBySignature`: grants platform signature permissions
     to read-only `/system` & `/system_ext` privileged system packages signed with the pinned
     `samurai-ota-release.jks` SHA-256 (`SamuraiPlatformSigBridge.shouldTrustSystemPrivSignature`).
   - `PackageManagerServiceUtils.verifySignatures`: allows seamless dirty-OTA transitions between
     stock CP3A platform cert and `samurai-ota-release.jks` on system packages (`SystemUI.apk`).
3. Compiles `SamuraiPlatformSigBridge.java` -> `classes4.dex` via `javac 17` + `d8`.
4. Repacks `services.jar` preserving exact ZIP entry structure and verifies DEX integrity via `dexdump`.
"""

import glob
import os
import shutil
import subprocess
import sys
import zipfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
A17_ROOT = os.path.dirname(SCRIPT_DIR)
WORK_ROOT = os.environ.get("WORK_ROOT", "/var/tmp/rmx1931-a17")

STOCK_JAR = os.environ.get(
    "STOCK_SERVICES_JAR",
    os.path.join(WORK_ROOT, "downloads/pixel_a17_cp3a/services_stock.jar"),
)
OUT_JAR = os.environ.get(
    "OUT_SERVICES_JAR",
    os.path.join(WORK_ROOT, "out/framework/services.jar"),
)
BUILD_DIR = os.path.join(WORK_ROOT, "work/apk-build/ServicesMod")

JAVA_HOME = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-17-openjdk-amd64")
JAVA = os.path.join(JAVA_HOME, "bin/java")
JAVAC = os.path.join(JAVA_HOME, "bin/javac")

APKTOOL_JAR = os.path.join(WORK_ROOT, "tools/apktool.jar")
ANDROID_SDK = os.path.join(WORK_ROOT, "tools/android-sdk")
BUILD_TOOLS = os.path.join(ANDROID_SDK, "build-tools/35.0.0")
PLATFORM_JAR = os.path.join(ANDROID_SDK, "platforms/android-35/android.jar")
D8 = os.path.join(BUILD_TOOLS, "d8")
DEXDUMP = os.path.join(BUILD_TOOLS, "dexdump")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign")


def run(cmd):
    res = subprocess.run(cmd, text=True, capture_output=True)
    if res.returncode != 0:
        sys.stderr.write(f"COMMAND FAILED ({res.returncode}): {' '.join(cmd)}\n")
        sys.stderr.write(res.stdout + "\n" + res.stderr + "\n")
        raise RuntimeError(f"Command failed: {cmd[0]}")
    return res.stdout


def find_smali(decoded_dir: str, rel_path: str) -> str:
    for sub in ("smali", "smali_classes2", "smali_classes3"):
        p = os.path.join(decoded_dir, sub, rel_path)
        if os.path.isfile(p):
            return p
    raise FileNotFoundError(f"Smali not found: {rel_path}")


def patch_app_id_permission_policy(decoded_dir: str) -> None:
    path = find_smali(
        decoded_dir,
        "com/android/server/permission/access/permission/AppIdPermissionPolicy.smali",
    )
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()

    m_start = src.find(
        ".method public final shouldGrantPermissionBySignature("
        "Lcom/android/server/permission/access/MutateStateScope;"
        "Lcom/android/server/pm/pkg/PackageState;"
        "Lcom/android/server/permission/access/permission/Permission;)Z"
    )
    m_end = src.find(".end method", m_start)
    if m_start < 0 or m_end < 0:
        raise RuntimeError("shouldGrantPermissionBySignature not found in AppIdPermissionPolicy")

    body = src[m_start:m_end]
    target = (
        "    invoke-virtual {v3, v0, v7}, Landroid/content/pm/SigningDetails;"
        "->checkCapability(Landroid/content/pm/SigningDetails;I)Z\n\n"
        "    move-result v0\n\n"
        "    if-eqz v0, :cond_2\n\n"
        "    goto :goto_1\n\n"
        "    :cond_2\n"
        "    return v5"
    )
    if body.count(target) != 1:
        raise RuntimeError("Expected exact checkCapability block in shouldGrantPermissionBySignature")

    replacement = (
        "    invoke-virtual {v3, v0, v7}, Landroid/content/pm/SigningDetails;"
        "->checkCapability(Landroid/content/pm/SigningDetails;I)Z\n\n"
        "    move-result v1\n\n"
        "    if-eqz v1, :cond_2\n\n"
        "    goto :goto_1\n\n"
        "    :cond_2\n"
        "    invoke-static {p2, v0}, Lcom/realme/samurai/pm/SamuraiPlatformSigBridge;"
        "->shouldTrustSystemPrivSignature("
        "Lcom/android/server/pm/pkg/PackageState;Landroid/content/pm/SigningDetails;)Z\n\n"
        "    move-result v1\n\n"
        "    if-eqz v1, :cond_samurai_deny\n\n"
        "    goto :goto_1\n\n"
        "    :cond_samurai_deny\n"
        "    return v5"
    )
    patched_body = body.replace(target, replacement, 1)
    with open(path, "w", encoding="utf-8") as f:
        f.write(src[:m_start] + patched_body + src[m_end:])


def patch_pms_utils(decoded_dir: str) -> None:
    path = find_smali(
        decoded_dir,
        "com/android/server/pm/PackageManagerServiceUtils.smali",
    )
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()

    m_start = src.find(
        ".method public static verifySignatures("
        "Lcom/android/server/pm/PackageSetting;"
        "Lcom/android/server/pm/SharedUserSetting;"
        "Lcom/android/server/pm/PackageSetting;"
        "Landroid/content/pm/SigningDetails;ZZZ)Z"
    )
    m_end = src.find(".end method", m_start)
    if m_start < 0 or m_end < 0:
        raise RuntimeError("verifySignatures not found in PackageManagerServiceUtils")

    body = src[m_start:m_end]
    target = "    .locals 7\n"
    if body.count(target) != 1:
        raise RuntimeError("Expected .locals 7 in verifySignatures")

    replacement = (
        "    .locals 7\n\n"
        "    invoke-static {p0, p1, p3}, Lcom/realme/samurai/pm/SamuraiPlatformSigBridge;"
        "->checkSystemSignatureTransition("
        "Lcom/android/server/pm/PackageSetting;"
        "Lcom/android/server/pm/SharedUserSetting;"
        "Landroid/content/pm/SigningDetails;)I\n\n"
        "    move-result v0\n\n"
        "    if-lez v0, :cond_samurai_normal\n\n"
        "    const/4 v0, 0x1\n\n"
        "    return v0\n\n"
        "    :cond_samurai_normal\n"
    )
    patched_body = body.replace(target, replacement, 1)
    with open(path, "w", encoding="utf-8") as f:
        f.write(src[:m_start] + patched_body + src[m_end:])


def patch_wm_session(decoded_dir: str) -> None:
    path = find_smali(
        decoded_dir,
        "com/android/server/wm/Session.smali",
    )
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()

    sig_tail = (
        "sendWallpaperCommand("
        "Landroid/os/IBinder;Ljava/lang/String;IIILandroid/os/Bundle;)V"
    )
    m_start = src.find(f".method public final {sig_tail}")
    if m_start < 0:
        m_start = src.find(f".method public {sig_tail}")
    m_end = src.find(".end method", m_start) if m_start >= 0 else -1
    if m_start < 0 or m_end < 0:
        raise RuntimeError("sendWallpaperCommand not found in com/android/server/wm/Session.smali")

    body = src[m_start:m_end]
    target = "    .locals 4\n"
    if body.count(target) != 1:
        raise RuntimeError("Expected .locals 4 in Session.sendWallpaperCommand")

    replacement = (
        "    .locals 4\n\n"
        "    iget-object v0, p0, Lcom/android/server/wm/Session;->mService:Lcom/android/server/wm/WindowManagerService;\n\n"
        "    iget-object v0, v0, Lcom/android/server/wm/WindowManagerService;->mContext:Landroid/content/Context;\n\n"
        "    invoke-static {v0, p1, p2, p3, p4}, Lcom/realme/samurai/pm/SamuraiPlatformSigBridge;"
        "->onWallpaperCommand(Landroid/content/Context;Landroid/os/IBinder;Ljava/lang/String;II)V\n"
    )
    patched_body = body.replace(target, replacement, 1)
    with open(path, "w", encoding="utf-8") as f:
        f.write(src[:m_start] + patched_body + src[m_end:])


def patch_fingerprint_aidl_sensor(decoded_dir: str) -> None:
    """
    Fix Android 17 CP3A HidlFingerprintSensorConfig bug where sensorType is left at 0 (TYPE_UNKNOWN)
    for HIDL 2.3 UDFPS sensors configured via config_udfps_sensor_props=[540,2156,95].
    Upgrades sensorType=0 -> 3 (TYPE_UDFPS_OPTICAL) and halControlsIllumination=true so Settings
    FingerprintEnrollEnrolling and SystemUI UdfpsController attach the optical UDFPS overlay.
    """
    path = find_smali(
        decoded_dir,
        "com/android/server/biometrics/sensors/fingerprint/aidl/Sensor.smali",
    )
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()

    target = (
        "    iget-byte v5, p0, Landroid/hardware/biometrics/fingerprint/SensorProps;->sensorType:B\n\n"
        "    iget-boolean v6, p0, Landroid/hardware/biometrics/fingerprint/SensorProps;->halControlsIllumination:Z"
    )
    if src.count(target) != 1:
        raise RuntimeError("Expected exact sensorType/halControlsIllumination block in fingerprint/aidl/Sensor.smali")

    replacement = (
        "    iget-byte v5, p0, Landroid/hardware/biometrics/fingerprint/SensorProps;->sensorType:B\n\n"
        "    iget-boolean v6, p0, Landroid/hardware/biometrics/fingerprint/SensorProps;->halControlsIllumination:Z\n\n"
        "    if-nez v5, :cond_samurai_fp\n\n"
        "    const/4 v5, 0x3\n\n"
        "    iput-byte v5, p0, Landroid/hardware/biometrics/fingerprint/SensorProps;->sensorType:B\n\n"
        "    const/4 v6, 0x1\n\n"
        "    iput-boolean v6, p0, Landroid/hardware/biometrics/fingerprint/SensorProps;->halControlsIllumination:Z\n\n"
        "    :cond_samurai_fp"
    )
    with open(path, "w", encoding="utf-8") as f:
        f.write(src.replace(target, replacement, 1))


def main() -> int:
    if not os.path.isfile(STOCK_JAR):
        raise FileNotFoundError(f"Missing pristine services_stock.jar at {STOCK_JAR}")

    if os.path.exists(BUILD_DIR):
        shutil.rmtree(BUILD_DIR)
    decoded_dir = os.path.join(BUILD_DIR, "decoded")
    rebuilt_jar = os.path.join(BUILD_DIR, "rebuilt-smali.jar")
    stubs_classes = os.path.join(BUILD_DIR, "stubs_classes")
    mod_classes = os.path.join(BUILD_DIR, "mod_classes")
    mod_dex = os.path.join(BUILD_DIR, "mod_dex")
    os.makedirs(stubs_classes, exist_ok=True)
    os.makedirs(mod_classes, exist_ok=True)
    os.makedirs(mod_dex, exist_ok=True)
    os.makedirs(os.path.dirname(OUT_JAR), exist_ok=True)

    print("[services-mod] 1/4 Decoding pristine services_stock.jar via apktool (-r)...")
    run([JAVA, "-jar", APKTOOL_JAR, "d", "-f", "-r", STOCK_JAR, "-o", decoded_dir])

    print("[services-mod] 2/4 Applying surgical signature bridge, wallpaper tap & optical UDFPS hooks...")
    patch_app_id_permission_policy(decoded_dir)
    patch_pms_utils(decoded_dir)
    patch_wm_session(decoded_dir)
    patch_fingerprint_aidl_sensor(decoded_dir)

    print("[services-mod] 3/4 Rebuilding smali & compiling SamuraiPlatformSigBridge.java...")
    run([JAVA, "-jar", APKTOOL_JAR, "b", decoded_dir, "-o", rebuilt_jar])

    stub_srcs = sorted(
        glob.glob(os.path.join(A17_ROOT, "services-mod/stubs/**/*.java"), recursive=True)
    )
    mod_srcs = sorted(
        glob.glob(os.path.join(A17_ROOT, "services-mod/src/**/*.java"), recursive=True)
    )
    run([
        JAVAC, "-source", "17", "-target", "17", "-encoding", "UTF-8",
        "-classpath", PLATFORM_JAR,
        "-d", stubs_classes,
        *stub_srcs,
    ])
    run([
        JAVAC, "-Xlint:all", "-Werror", "-source", "17", "-target", "17", "-encoding", "UTF-8",
        "-classpath", f"{stubs_classes}:{PLATFORM_JAR}",
        "-d", mod_classes,
        *mod_srcs,
    ])
    mod_cls_files = sorted(glob.glob(os.path.join(mod_classes, "**/*.class"), recursive=True))
    run([
        D8,
        "--lib", PLATFORM_JAR,
        "--classpath", stubs_classes,
        "--min-api", "33",
        "--output", mod_dex,
        *mod_cls_files,
    ])
    bridge_dex = os.path.join(mod_dex, "classes.dex")
    if not os.path.isfile(bridge_dex):
        raise RuntimeError("d8 did not emit classes.dex for SamuraiPlatformSigBridge")

    print("[services-mod] 4/4 Repacking services.jar with STORED uncompressed DEX alignment...")
    with zipfile.ZipFile(rebuilt_jar, "r") as zin:
        rebuilt_dex = {
            n: zin.read(n) for n in zin.namelist()
            if n.startswith("classes") and n.endswith(".dex")
        }
    next_idx = len(rebuilt_dex) + 1
    next_dex_name = f"classes{next_idx}.dex"
    with open(bridge_dex, "rb") as f:
        rebuilt_dex[next_dex_name] = f.read()

    tmp_out = OUT_JAR + ".tmp"
    with zipfile.ZipFile(STOCK_JAR, "r") as zstock, zipfile.ZipFile(tmp_out, "w") as zout:
        for info in zstock.infolist():
            if info.filename in rebuilt_dex:
                ni = zipfile.ZipInfo(info.filename, date_time=(2009, 1, 1, 0, 0, 0))
                ni.compress_type = zipfile.ZIP_STORED
                ni.external_attr = info.external_attr
                zout.writestr(ni, rebuilt_dex[info.filename])
            else:
                ni = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                ni.compress_type = info.compress_type
                ni.external_attr = info.external_attr
                zout.writestr(ni, zstock.read(info.filename))

        ni = zipfile.ZipInfo(next_dex_name, date_time=(2009, 1, 1, 0, 0, 0))
        ni.compress_type = zipfile.ZIP_STORED
        zout.writestr(ni, rebuilt_dex[next_dex_name])

    run([ZIPALIGN, "-f", "4", tmp_out, OUT_JAR])
    os.remove(tmp_out)
    run([ZIPALIGN, "-c", "4", OUT_JAR])

    # Verify bridge DEX header & class presence via dexdump
    dump_out = run([DEXDUMP, "-f", bridge_dex])
    if "SamuraiPlatformSigBridge" not in run([DEXDUMP, bridge_dex]):
        raise RuntimeError("SamuraiPlatformSigBridge missing from bridge DEX!")
    print(f"[OK] Patched services.jar built & verified: {OUT_JAR} ({os.path.getsize(OUT_JAR)} bytes, +{next_dex_name})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
