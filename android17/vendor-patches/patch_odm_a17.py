#!/usr/bin/env python3
"""
RMX1931 (samurai) ODM Partition Optimization for Android 17 (API 37.1):
1. Installs Goodix G3 Optical UDFPS firmware (goodixfp.{mdt,b00..b07}) directly into
   /odm/vendor/firmware/ (u:object_r:vendor_firmware_file:s0) so fingerprint.goodix_G3.default.so
   loads firmware on attempt #1 in 0 ms instead of stalling 2.50s across missing search paths.
2. Patches /odm/etc/init/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc:
   - Removes broken /odm/bin/init.oplus.fingerprints.sh service (file does not exist).
   - Fixes /sys/kernel/oplus_display/hbm typo -> /sys/kernel/oppo_display/{hbm,dimlayer_hbm,
     dimlayer_bl_en,notify_fppress,aod_light_mode_set}.
   - Starts fps_hal (@2.1) and vendor.fps_hal.samurai (@2.3) immediately at the end of
     on post-fs-data (after /data/vendor/fingerprint exists), registering @2.3 seconds BEFORE
     SystemServer AuthService queries IBiometricsFingerprint.
"""
import glob
import os
import shutil
import subprocess
import sys


def set_selinux_xattr(path: str, ctx: str) -> None:
    subprocess.run(["setfattr", "-h", "-n", "security.selinux", "-v", ctx, path], check=True)


def get_selinux_xattr(path: str, default: str = "u:object_r:vendor_configs_file:s0") -> str:
    res = subprocess.run(
        ["getfattr", "-h", "--only-values", "-n", "security.selinux", path],
        capture_output=True,
        text=True,
    )
    if res.returncode == 0 and res.stdout.strip():
        return res.stdout.strip().rstrip("\x00")
    return default


def patch_odm_mount(odm_mnt: str, vendor_fw_dir: str) -> None:
    # 1. Copy goodixfp.* firmware into /odm/vendor/firmware/ with vendor_firmware_file SELinux label
    odm_fw_dir = os.path.join(odm_mnt, "vendor", "firmware")
    os.makedirs(odm_fw_dir, mode=0o755, exist_ok=True)
    os.chmod(odm_fw_dir, 0o755)
    os.chown(odm_fw_dir, 0, 0)
    set_selinux_xattr(os.path.join(odm_mnt, "vendor"), "u:object_r:vendor_file:s0")
    set_selinux_xattr(odm_fw_dir, "u:object_r:vendor_firmware_file:s0")

    copied = 0
    for src in sorted(glob.glob(os.path.join(vendor_fw_dir, "goodixfp.*"))):
        fn = os.path.basename(src)
        dst = os.path.join(odm_fw_dir, fn)
        shutil.copyfile(src, dst)
        os.chmod(dst, 0o644)
        os.chown(dst, 0, 0)
        set_selinux_xattr(dst, "u:object_r:vendor_firmware_file:s0")
        copied += 1
    print(f"[odm-patch] Installed {copied} Goodix G3 optical UDFPS firmware blobs into /odm/vendor/firmware/")

    # 2. Rewrite vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc for zero-race startup
    fp_rc = os.path.join(
        odm_mnt, "etc", "init", "vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc"
    )
    if os.path.isfile(fp_rc):
        ctx = get_selinux_xattr(fp_rc, "u:object_r:vendor_configs_file:s0")
        clean_rc = """service fps_hal /odm/bin/hw/vendor.oplus.hardware.biometrics.fingerprint@2.1-service
    class late_start
    user system
    group system input uhid drmrpc wakelock
    capabilities BLOCK_SUSPEND
    setenv ADSP_LIBRARY_PATH "/vendor/lib/rfsa/adsp;/odm/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/vendor/dsp"
    setenv DSP_LIBRARY_PATH "/vendor/lib/rfsa/adsp;/odm/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/vendor/dsp"
    priority -20

on boot
    chown system system /dev/goodix_fp
    chmod 0666 /dev/goodix_fp
    chown system system /dev/qseecom
    chmod 0660 /dev/qseecom
    chown system system /dev/adsprpc-smd
    chmod 0666 /dev/adsprpc-smd
    chown system system /sys/kernel/oppo_display/hbm
    chmod 0666 /sys/kernel/oppo_display/hbm
    chown system system /sys/kernel/oppo_display/dimlayer_hbm
    chmod 0666 /sys/kernel/oppo_display/dimlayer_hbm
    chown system system /sys/kernel/oppo_display/dimlayer_bl_en
    chmod 0666 /sys/kernel/oppo_display/dimlayer_bl_en
    chown system system /sys/kernel/oppo_display/notify_fppress
    chmod 0666 /sys/kernel/oppo_display/notify_fppress
    chown system system /sys/kernel/oppo_display/aod_light_mode_set
    chmod 0666 /sys/kernel/oppo_display/aod_light_mode_set

on post-fs-data
    mkdir /data/vendor/fingerprint 0770 system system
    mkdir /data/vendor/fingerprint/gf_data 0770 system system
    mkdir /data/vendor_de/0/fpdata 0770 system system
    mkdir /mnt/vendor/persist/fingerprint 0770 system system
    chown system system /sys/kernel/oppo_display/hbm
    chmod 0666 /sys/kernel/oppo_display/hbm
    chown system system /sys/kernel/oppo_display/dimlayer_hbm
    chmod 0666 /sys/kernel/oppo_display/dimlayer_hbm
    chown system system /sys/kernel/oppo_display/notify_fppress
    chmod 0666 /sys/kernel/oppo_display/notify_fppress
    start vendor.cdsprpcd
    start fps_hal
    start vendor.fps_hal.samurai

on property:sys.boot_completed=1
    setprop vendor.oplus.biometrics.fingerprint.boot_completed 1
"""
        with open(fp_rc, "w", encoding="utf-8", newline="\n") as f:
            f.write(clean_rc)
        os.chmod(fp_rc, 0o644)
        os.chown(fp_rc, 0, 0)
        set_selinux_xattr(fp_rc, ctx)
        print("[odm-patch] Patched vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc (oppo_display + early post-fs-data start)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: patch_odm_a17.py <mounted_odm_dir> <vendor_firmware_dir>", file=sys.stderr)
        sys.exit(1)
    patch_odm_mount(sys.argv[1], sys.argv[2])
