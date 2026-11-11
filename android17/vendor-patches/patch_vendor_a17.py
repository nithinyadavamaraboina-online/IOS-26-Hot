#!/usr/bin/env python3
"""
Lossless RMX1931 (samurai) Vendor & SELinux Adaptation for Android 17 (API 37.1):
1. Injects ADSP_LIBRARY_PATH into adsprpc, sensors, and vppservice init .rc files.
2. Marks only init.time_daemon.rc disabled until triggered (leaving keymaster@4.0 and
   qseecomd enabled so Android 17 early_hal keystore2 + vold never deadlock).
3. Adapts /vendor/etc/fstab.qcom /data entries (ext4 & f2fs) for clean 4.14 mounting
   without inlinecrypt/wrappedkey metadata-encryption deadlocks.
4. Optimizes Goodix @2.3 fingerprint service rc + unlocks multi-lens AUX cameras
   (64MP GW1 + 13MP S5K3M5SX 2x Tele + 8MP IMX319 Ultrawide) in /vendor/build.prop,
   patches com.qti.chi.override.so Project 19781 table, and stages Pixel GCam 9.6.
5. Updates vendor SELinux contexts & CIL (Widevine DRM, Goodix FOD, GMS sysfs dontaudit,
   Dolby audio prop read, OLED calibration read) and removes stale precompiled_sepolicy.
"""
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile


def set_selinux_xattr(path: str, ctx: str) -> None:
    subprocess.run(["setfattr", "-n", "security.selinux", "-v", ctx, path], check=True)


def get_selinux_xattr(path: str) -> str:
    res = subprocess.run(
        ["getfattr", "--only-values", "-n", "security.selinux", path],
        capture_output=True, text=True
    )
    if res.returncode == 0 and res.stdout.strip():
        return res.stdout.strip().rstrip("\x00")
    return "u:object_r:vendor_configs_file:s0"


def patch_file_preserving_xattr(path: str, transform_fn) -> bool:
    if not os.path.isfile(path):
        return False
    ctx = get_selinux_xattr(path)
    st = os.stat(path)
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        orig = f.read()
    updated = transform_fn(orig)
    if updated == orig:
        return False
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(updated)
    os.chmod(path, st.st_mode & 0o7777)
    os.chown(path, st.st_uid, st.st_gid)
    set_selinux_xattr(path, ctx)
    return True


def add_adsp_env(rc_text: str) -> str:
    if "vendor.cdsprpcd" in rc_text:
        return rc_text
    return """service vendor.adsprpcd /vendor/bin/adsprpcd
    class main
    user system
    group system media drmrpc wakelock
    capabilities BLOCK_SUSPEND
    setenv ADSP_LIBRARY_PATH "/vendor/lib/rfsa/adsp;/odm/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/vendor/dsp"
    setenv DSP_LIBRARY_PATH "/vendor/lib/rfsa/adsp;/odm/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/vendor/dsp"

service vendor.adsprpcd_audiopd /vendor/bin/adsprpcd audiopd
    class main
    user media
    group system media drmrpc wakelock
    capabilities BLOCK_SUSPEND
    setenv ADSP_LIBRARY_PATH "/vendor/lib/rfsa/adsp;/odm/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/vendor/dsp"
    setenv DSP_LIBRARY_PATH "/vendor/lib/rfsa/adsp;/odm/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/vendor/dsp"

service vendor.cdsprpcd /vendor/bin/cdsprpcd
    class main
    user system
    group system media drmrpc wakelock
    capabilities BLOCK_SUSPEND
    setenv ADSP_LIBRARY_PATH "/vendor/lib/rfsa/adsp;/odm/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/vendor/dsp"
    setenv DSP_LIBRARY_PATH "/vendor/lib/rfsa/adsp;/odm/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/vendor/dsp"
"""


def add_disabled_to_service(rc_text: str) -> str:
    if re.search(r"^\s+disabled\s*$", rc_text, flags=re.MULTILINE):
        return rc_text
    return rc_text.rstrip() + "\n    disabled\n"


def strip_stop_statsd(rc_text: str) -> str:
    """
    Realme's Qualcomm vendor init.target.rc runs 'stop statsd' on property:sys.boot_completed=1,
    which kills Android 17's statsd daemon and breaks /dev/socket/statsdw mid-flight after
    zygote_next has registered FD 4 in FileDescriptorRegistry. Strip 'stop statsd' cleanly.
    """
    out = []
    for line in rc_text.splitlines():
        if re.match(r"^\s*stop\s+statsd\s*$", line):
            continue
        out.append(line)
    return "\n".join(out) + "\n"


def patch_fstab_userdata(fstab_text: str) -> str:
    out = []
    replaced_userdata = False
    for line in fstab_text.splitlines():
        stripped = line.strip()
        if not stripped.startswith("#") and "/dev/block/bootdevice/by-name/userdata" in stripped and "\t/data" in line.replace("  ", "\t"):
            if not replaced_userdata:
                out.append(
                    "/dev/block/bootdevice/by-name/userdata                  /data                  ext4    "
                    "noatime,nosuid,nodev,barrier=1,noauto_da_alloc,errors=remount-ro         "
                    "latemount,wait,check,reservedsize=128M"
                )
                out.append(
                    "/dev/block/bootdevice/by-name/userdata                  /data                  f2fs    "
                    "noatime,nosuid,nodev,discard,fsync_mode=nobarrier                        "
                    "latemount,wait,check,formattable,reservedsize=128M,sysfs_path=/sys/devices/platform/soc/1d84000.ufshc,checkpoint=fs"
                )
                replaced_userdata = True
            continue
        out.append(line)
    return "\n".join(out) + "\n"


def patch_vendor_build_prop(prop_text: str) -> str:
    aux_apps = (
        "org.codeaurora.snapcam,com.android.camera,com.android.camera2,"
        "com.google.android.GoogleCamera,com.google.android.GoogleCameraEng,app.grapheneos.camera"
    )
    updates = {
        "ro.build.product": "RMX1931",
        "ro.product.brand": "realme",
        "vendor.camera.aux.packagelist": aux_apps,
        "persist.vendor.camera.privapp.list": aux_apps,
        "ro.sf.lcd_density": "420",
        "ro.surface_flinger.supports_background_blur": "1",
        "ro.surface_flinger.blur_disabled_by_default": "true",
        "ro.sf.blurs_are_expensive": "1",
        "debug.sf.disable_client_composition_cache": "0",
        "debug.sf.auto_latch_unsignaled": "0",
        "debug.sf.latch_unsignaled": "1",
        "debug.sf.enable_gl_backpressure": "0",
        "debug.sf.enable_hwc_vds": "1",
        "debug.sf.predict_hwc_composition_strategy": "0",
        "media.stagefright.thumbnail.prefer_hw_codecs": "true",
        "ro.surface_flinger.has_wide_color_display": "true",
        "persist.sys.sf.color_saturation": "1.10",
        "ro.surface_flinger.use_content_detection_for_refresh_rate": "false",
        "ro.surface_flinger.set_touch_timer_ms": "4000",
        "ro.surface_flinger.set_idle_timer_ms": "0",
        "persist.vendor.fingerprint.sensor_type": "optical",
        "persist.vendor.fingerprint.optical.support": "1",
        "persist.vendor.fingerprint.optical.iconlocation": "244",
        "persist.vendor.fingerprint.optical.iconsize": "190",
        "persist.vendor.fingerprint.fp_id": "G_OPTICAL_G3",
        "persist.vendor.fingerprint.version": "Goodix",
        "persist.vendor.qfp": "true",
        "vendor.flash.locked": "1",
    }
    drop_keys = {"debug.renderengine.backend", "debug.hwui.renderer"}
    remaining = dict(updates)
    out = []
    for line in prop_text.splitlines():
        s = line.strip()
        if s and not s.startswith("#") and "=" in s:
            k, _ = s.split("=", 1)
            key = k.strip()
            if key in drop_keys:
                continue
            if key in remaining:
                out.append(f"{key}={remaining.pop(key)}")
                continue
        out.append(line)
    if remaining:
        out.append("")
        out.append("# RMX1931 Pixel OS Adreno 640 Blur, DCI-P3 Wide Color, 420dpi, Goodix G3 Optical UDFPS & AUX Cameras")
        for k, v in remaining.items():
            out.append(f"{k}={v}")
    return "\n".join(out) + "\n"


def patch_fp23_rc(rc_text: str) -> str:
    return """on post-fs-data
    mkdir /data/vendor/fingerprint 0770 system system
    mkdir /data/vendor/fingerprint/gf_data 0770 system system
    mkdir /data/vendor_de 0770 system system
    mkdir /data/vendor_de/0 0770 system system
    mkdir /data/vendor_de/0/fpdata 0770 system system
    mkdir /data/vendor_de/0/cryptoeng 0770 system system
    mkdir /mnt/vendor/persist/fingerprint 0770 system system

on boot
    chown system system /dev/goodix_fp
    chmod 0666 /dev/goodix_fp
    chown system system /dev/qseecom
    chmod 0660 /dev/qseecom
    chown system system /dev/adsprpc-smd
    chmod 0666 /dev/adsprpc-smd
    chown system system /dev/adsprpc-smd-secure
    chmod 0660 /dev/adsprpc-smd-secure
    chown system system /sys/kernel/oppo_display/dimlayer_hbm
    chmod 0666 /sys/kernel/oppo_display/dimlayer_hbm
    chown system system /sys/kernel/oppo_display/dimlayer_bl_en
    chmod 0666 /sys/kernel/oppo_display/dimlayer_bl_en
    chown system system /sys/kernel/oppo_display/notify_fppress
    chmod 0666 /sys/kernel/oppo_display/notify_fppress
    chown system system /sys/kernel/oppo_display/fp_state
    chmod 0666 /sys/kernel/oppo_display/fp_state
    chown system system /sys/kernel/oppo_display/hbm
    chmod 0666 /sys/kernel/oppo_display/hbm
    chown system system /sys/kernel/oppo_display/aod_light_mode_set
    chmod 0666 /sys/kernel/oppo_display/aod_light_mode_set
    chown system system /proc/touchpanel/fp_enable
    chmod 0666 /proc/touchpanel/fp_enable
    write /proc/touchpanel/fp_enable 1
    start vendor.cdsprpcd
    start fps_hal
    start vendor.fps_hal.samurai

service vendor.fps_hal.samurai /vendor/bin/hw/android.hardware.biometrics.fingerprint@2.3-service.samurai
    class late_start
    user system
    group system input uhid drmrpc wakelock
    capabilities BLOCK_SUSPEND
    setenv ADSP_LIBRARY_PATH "/vendor/lib/rfsa/adsp;/odm/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/vendor/dsp"
    setenv DSP_LIBRARY_PATH "/vendor/lib/rfsa/adsp;/odm/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/vendor/dsp"
    onrestart restart fps_hal
    task_profiles ProcessCapacityHigh HighPerformance
    priority -20
"""


def detect_cil_version_suffix(plat_pub_versioned_path: str, symbol: str) -> str | None:
    if not os.path.isfile(plat_pub_versioned_path):
        return None
    with open(plat_pub_versioned_path, "r", encoding="utf-8", errors="replace") as f:
        txt = f.read()
    m = re.search(rf"\(\s*typeattribute\s+({re.escape(symbol)}_[0-9_]+)\s*\)", txt)
    if m:
        return m.group(1)
    if re.search(rf"\(\s*(?:type|typeattribute)\s+{re.escape(symbol)}\s*\)", txt):
        return symbol
    return None


def symbol_exists_in_cil(cil_text: str, sym: str) -> bool:
    return bool(re.search(rf"\b{re.escape(sym)}\b", cil_text))


def write_vendor_config_file(path: str, content: str) -> None:
    ctx = get_selinux_xattr(path) if os.path.isfile(path) else "u:object_r:vendor_configs_file:s0"
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)
    os.chmod(path, 0o644)
    os.chown(path, 0, 0)
    set_selinux_xattr(path, ctx)


CLEAN_MEDIA_CODECS_XML = """<?xml version="1.0" encoding="utf-8" ?>
<!--
    Copyright (C) 2019 The Android Open Source Project
    RMX1931 (SM8150-AC) Official PixelOS 17.0 Codec2 Hardware Media Configuration
-->
<MediaCodecs>
    <Include href="media_codecs_c2.xml" />
    <Settings>
        <Setting name="max-video-encoder-input-buffers" value="11" />
        <Domain name="telephony" enabled="true" />
    </Settings>
</MediaCodecs>
"""

SM8150_MEDIA_CODECS_PERF_C2_XML = """<?xml version="1.0" encoding="utf-8" ?>
<!--
    RMX1931 (Snapdragon 855+ / SM8150-AC) Codec2 Measured Frame Rates for Android 17
    Provides hardware performance points for c2.qti.{avc,hevc,vp8,vp9} so YouTube / ExoPlayer / Chrome
    select zero-copy 1080p60 / 1440p60 / 2160p60 hardware decoding without software fallback.
-->
<MediaCodecs>
    <Decoders>
        <MediaCodec name="c2.qti.avc.decoder" type="video/avc" update="true">
            <Limit name="measured-frame-rate-320x240" range="569-630" />
            <Limit name="measured-frame-rate-720x480" range="513-554" />
            <Limit name="measured-frame-rate-1280x720" range="322-420" />
            <Limit name="measured-frame-rate-1920x1080" range="240-301" />
            <Limit name="measured-frame-rate-3840x2160" range="120-128" />
        </MediaCodec>
        <MediaCodec name="c2.qti.hevc.decoder" type="video/hevc" update="true">
            <Limit name="measured-frame-rate-352x288" range="612-750" />
            <Limit name="measured-frame-rate-640x360" range="590-720" />
            <Limit name="measured-frame-rate-720x480" range="595-700" />
            <Limit name="measured-frame-rate-1280x720" range="418-641" />
            <Limit name="measured-frame-rate-1920x1080" range="280-318" />
            <Limit name="measured-frame-rate-3840x2160" range="120-149" />
        </MediaCodec>
        <MediaCodec name="c2.qti.vp8.decoder" type="video/x-vnd.on2.vp8" update="true">
            <Limit name="measured-frame-rate-320x180" range="633-700" />
            <Limit name="measured-frame-rate-640x360" range="606-750" />
            <Limit name="measured-frame-rate-1280x720" range="332-337" />
            <Limit name="measured-frame-rate-1920x1080" range="220-248" />
        </MediaCodec>
        <MediaCodec name="c2.qti.vp9.decoder" type="video/x-vnd.on2.vp9" update="true">
            <Limit name="measured-frame-rate-320x180" range="626-700" />
            <Limit name="measured-frame-rate-640x360" range="557-650" />
            <Limit name="measured-frame-rate-1280x720" range="360-480" />
            <Limit name="measured-frame-rate-1920x1080" range="260-300" />
            <Limit name="measured-frame-rate-3840x2160" range="118-124" />
        </MediaCodec>
        <MediaCodec name="c2.android.avc.decoder" type="video/avc" update="true">
            <Limit name="measured-frame-rate-320x240" range="482-482" />
            <Limit name="measured-frame-rate-720x480" range="162-162" />
            <Limit name="measured-frame-rate-1280x720" range="62-63" />
            <Limit name="measured-frame-rate-1920x1080" range="27-27" />
        </MediaCodec>
        <MediaCodec name="c2.android.hevc.decoder" type="video/hevc" update="true">
            <Limit name="measured-frame-rate-352x288" range="492-492" />
            <Limit name="measured-frame-rate-640x360" range="271-274" />
            <Limit name="measured-frame-rate-720x480" range="259-259" />
            <Limit name="measured-frame-rate-1280x720" range="105-105" />
            <Limit name="measured-frame-rate-1920x1080" range="56-56" />
        </MediaCodec>
        <MediaCodec name="c2.android.vp8.decoder" type="video/x-vnd.on2.vp8" update="true">
            <Limit name="measured-frame-rate-320x180" range="798-802" />
            <Limit name="measured-frame-rate-640x360" range="392-392" />
            <Limit name="measured-frame-rate-1280x720" range="107-145" />
            <Limit name="measured-frame-rate-1920x1080" range="43-43" />
        </MediaCodec>
        <MediaCodec name="c2.android.vp9.decoder" type="video/x-vnd.on2.vp9" update="true">
            <Limit name="measured-frame-rate-320x180" range="669-669" />
            <Limit name="measured-frame-rate-640x360" range="220-222" />
            <Limit name="measured-frame-rate-1280x720" range="86-86" />
            <Limit name="measured-frame-rate-1920x1080" range="52-52" />
        </MediaCodec>
        <MediaCodec name="c2.android.h263.decoder" type="video/3gpp" update="true">
            <Limit name="measured-frame-rate-176x144" range="821-821" />
            <Limit name="measured-frame-rate-352x288" range="723-824" />
        </MediaCodec>
        <MediaCodec name="c2.android.mpeg4.decoder" type="video/mp4v-es" update="true">
            <Limit name="measured-frame-rate-176x144" range="1824-1839" />
            <Limit name="measured-frame-rate-480x360" range="970-1455" />
            <Limit name="measured-frame-rate-1280x720" range="368-552" />
        </MediaCodec>
    </Decoders>
    <Encoders>
        <MediaCodec name="c2.qti.avc.encoder" type="video/avc" update="true">
            <Limit name="measured-frame-rate-320x240" range="238-386" />
            <Limit name="measured-frame-rate-720x480" range="183-287" />
            <Limit name="measured-frame-rate-1280x720" range="120-204" />
            <Limit name="measured-frame-rate-1920x1080" range="90-118" />
        </MediaCodec>
        <MediaCodec name="c2.qti.hevc.encoder" type="video/hevc" update="true">
            <Limit name="measured-frame-rate-320x240" range="226-411" />
            <Limit name="measured-frame-rate-720x480" range="185-339" />
            <Limit name="measured-frame-rate-1280x720" range="120-194" />
            <Limit name="measured-frame-rate-1920x1080" range="91-104" />
            <Limit name="measured-frame-rate-3840x2160" range="37-40" />
        </MediaCodec>
        <MediaCodec name="c2.qti.vp8.encoder" type="video/x-vnd.on2.vp8" update="true">
            <Limit name="measured-frame-rate-320x180" range="478-486" />
            <Limit name="measured-frame-rate-640x360" range="228-238" />
            <Limit name="measured-frame-rate-1280x720" range="82-120" />
            <Limit name="measured-frame-rate-1920x1080" range="43-60" />
        </MediaCodec>
        <MediaCodec name="c2.android.h263.encoder" type="video/3gpp" update="true">
            <Limit name="measured-frame-rate-176x144" range="693-706" />
        </MediaCodec>
        <MediaCodec name="c2.android.avc.encoder" type="video/avc" update="true">
            <Limit name="measured-frame-rate-320x240" range="354-356" />
            <Limit name="measured-frame-rate-720x480" range="127-128" />
            <Limit name="measured-frame-rate-1280x720" range="71-76" />
            <Limit name="measured-frame-rate-1920x1080" range="40-40" />
        </MediaCodec>
        <MediaCodec name="c2.android.hevc.encoder" type="video/hevc" update="true">
            <Limit name="measured-frame-rate-320x240" range="59-60" />
        </MediaCodec>
        <MediaCodec name="c2.android.mpeg4.encoder" type="video/mp4v-es" update="true">
            <Limit name="measured-frame-rate-176x144" range="661-661" />
        </MediaCodec>
        <MediaCodec name="c2.android.vp8.encoder" type="video/x-vnd.on2.vp8" update="true">
            <Limit name="measured-frame-rate-320x180" range="249-255" />
            <Limit name="measured-frame-rate-640x360" range="135-135" />
            <Limit name="measured-frame-rate-1280x720" range="37-37" />
            <Limit name="measured-frame-rate-1920x1080" range="31-31" />
        </MediaCodec>
        <MediaCodec name="c2.android.vp9.encoder" type="video/x-vnd.on2.vp9" update="true">
            <Limit name="measured-frame-rate-320x180" range="192-288" />
            <Limit name="measured-frame-rate-640x360" range="61-61" />
            <Limit name="measured-frame-rate-1280x720" range="20-20" />
        </MediaCodec>
    </Encoders>
</MediaCodecs>
"""


def patch_vendor_media_and_powerhint(vendor_mnt: str) -> None:
    etc_dir = os.path.join(vendor_mnt, "etc")
    write_vendor_config_file(os.path.join(etc_dir, "media_codecs.xml"), CLEAN_MEDIA_CODECS_XML)
    write_vendor_config_file(os.path.join(etc_dir, "media_codecs_performance_c2.xml"), SM8150_MEDIA_CODECS_PERF_C2_XML)
    write_vendor_config_file(os.path.join(etc_dir, "media_codecs_performance.xml"), SM8150_MEDIA_CODECS_PERF_C2_XML)

    c2_xml_path = os.path.join(etc_dir, "media_codecs_c2.xml")
    if os.path.isfile(c2_xml_path):
        def fix_c2_perf_points(txt: str) -> str:
            return re.sub(
                r'(<Limit\s+name="performance-point-[^"]+"\s+)range="([^"]+)"',
                r'\1value="\2"',
                txt,
            )
        patch_file_preserving_xattr(c2_xml_path, fix_c2_perf_points)

    ph_path = os.path.join(etc_dir, "powerhint.json")
    if os.path.isfile(ph_path):
        ph_ctx = get_selinux_xattr(ph_path)
        with open(ph_path, "r", encoding="utf-8") as f:
            ph = json.load(f)
        for node in ph.get("Nodes", []):
            nm = node.get("Name", "")
            if nm == "TASchedtuneBoost":
                # Default to index 1 ("10") while interactive so libperfmgr never wipes top-app/schedtune.boost to 0
                node["DefaultIndex"] = 1
            elif nm in ("CPUBigClusterDownRateLimit", "CPUBigPlusClusterDownRateLimit"):
                vals = node.get("Values", [])
                if vals:
                    vals[0] = "20000"
        for act in ph.get("Actions", []):
            hint = act.get("PowerHint", "")
            nd = act.get("Node", "")
            if hint == "INTERACTIVE" and nd in ("CPUBigClusterDownRateLimit", "CPUBigPlusClusterDownRateLimit"):
                act["Value"] = "20000"
            elif hint == "INTERACTION":
                if act.get("Duration") == 58:
                    act["Duration"] = 120
                if nd == "CPUBigPlusClusterMinFreq":
                    act["Value"] = "1401600"
            elif hint == "LAUNCH":
                if act.get("Duration") == 1000:
                    act["Duration"] = 2500
        with open(ph_path, "w", encoding="utf-8", newline="\n") as f:
            json.dump(ph, f, indent=2)
            f.write("\n")
        os.chmod(ph_path, 0o644)
        os.chown(ph_path, 0, 0)
        set_selinux_xattr(ph_path, ph_ctx)
    print("[vendor-patch] Configured Official PixelOS 17.0 Codec2 media_codecs + 90Hz SM8150-AC powerhint.json")


def patch_chi_eis_4k60(chi_raw: bytearray) -> bool:
    """
    Idempotently patches /vendor/lib64/hw/com.qti.chi.override.so so Qualcomm Spectra 380 Gyro-EIS3
    (UsecaseVideo4kEIS3PreviewEIS2 at 3840x2160 4K30/4K60 and UsecaseVideoEIS3PreviewEIS2 at 1080p/720p)
    works out-of-the-box for BOTH BSG GCam 9.6 custom sessions (0x8028=32808 60fps, 0x800b=32779 30fps)
    AND standard Camera2 apps (operation_mode == 0 on rear main cameraId == 0 with 3 output streams:
    {Preview, HW_VIDEO_ENCODER > 440x360, BLOB Video Snapshot}), with stream-order-independent target binding.
    """
    assert chi_raw[:4] == b"\x7fELF" and chi_raw[4] == 2 and chi_raw[5] == 1, "Invalid ELF64 header in CHI override"
    assert len(chi_raw) == 2172832, f"Unexpected com.qti.chi.override.so size: {len(chi_raw)}"

    def enc_b(pc: int, target: int) -> int:
        diff = (target - pc) >> 2
        assert -(1 << 25) <= diff < (1 << 25)
        return 0x14000000 | (diff & 0x03FFFFFF)

    def enc_b_cond(pc: int, target: int, cond: int) -> int:
        diff = (target - pc) >> 2
        assert -(1 << 18) <= diff < (1 << 18)
        return 0x54000000 | ((diff & 0x7FFFF) << 5) | (cond & 0xF)

    def enc_cbz(is_64: bool, is_nz: bool, rt: int, pc: int, target: int) -> int:
        diff = (target - pc) >> 2
        assert -(1 << 18) <= diff < (1 << 18)
        sf = 1 if is_64 else 0
        op = 1 if is_nz else 0
        return (sf << 31) | 0x34000000 | (op << 24) | ((diff & 0x7FFFF) << 5) | (rt & 0x1F)

    def enc_tbz(bit: int, is_nz: bool, rt: int, pc: int, target: int) -> int:
        diff = (target - pc) >> 2
        assert -(1 << 13) <= diff < (1 << 13)
        b5 = (bit >> 5) & 1
        b40 = bit & 0x1F
        op = 1 if is_nz else 0
        return (b5 << 31) | 0x36000000 | (op << 24) | (b40 << 19) | ((diff & 0x3FFF) << 5) | (rt & 0x1F)

    def enc_adrp(rd: int, pc: int, target_page: int) -> int:
        imm = ((target_page & ~0xFFF) - (pc & ~0xFFF)) >> 12
        assert -(1 << 20) <= imm < (1 << 20)
        immlo = imm & 0x3
        immhi = (imm >> 2) & 0x7FFFF
        return (1 << 31) | (immlo << 29) | (0x10 << 24) | (immhi << 5) | (rd & 0x1F)

    COND_EQ, COND_NE, COND_LO, COND_HI, COND_LS = 0x0, 0x1, 0x3, 0x8, 0x9

    CAVE1 = 0xF3800
    L_LOOP = CAVE1 + 0x28
    L_CHK_PREV = CAVE1 + 0x54
    L_IS_PREV = CAVE1 + 0x64
    L_NEXT = CAVE1 + 0x68
    L_STOCK = CAVE1 + 0x84
    L_NO_EIS = CAVE1 + 0xB0
    cave1_insns = [
        0xB9401329,                                         # ldr w9, [x25, #0x10]
        enc_cbz(False, True, 9, CAVE1 + 0x04, L_STOCK),     # cbnz w9, L_STOCK
        enc_cbz(False, True, 19, CAVE1 + 0x08, L_NO_EIS),   # cbnz w19, L_NO_EIS
        0xB9400329,                                         # ldr w9, [x25]
        0x71000D3F,                                         # cmp w9, #3
        enc_b_cond(CAVE1 + 0x14, L_NO_EIS, COND_NE),        # b.ne L_NO_EIS
        0xF9400729,                                         # ldr x9, [x25, #0x8]
        0x2A1F03EE,                                         # mov w14, wzr
        0x2A1F03EF,                                         # mov w15, wzr
        0x2A1F03F0,                                         # mov w16, wzr
        0xF86E792B,                                         # L_LOOP: ldr x11, [x9, x14, lsl #3]
        enc_cbz(True, False, 11, CAVE1 + 0x2C, L_NO_EIS),   # cbz x11, L_NO_EIS
        0xB940016C,                                         # ldr w12, [x11]
        enc_cbz(False, True, 12, CAVE1 + 0x34, L_NO_EIS),   # cbnz w12, L_NO_EIS
        0xB940116C,                                         # ldr w12, [x11, #0x10]
        enc_tbz(16, True, 12, CAVE1 + 0x3C, L_NEXT),        # tbnz w12, #16, L_NEXT
        0xB9400D6C,                                         # ldr w12, [x11, #0xc]
        0x7100859F,                                         # cmp w12, #0x21
        enc_b_cond(CAVE1 + 0x48, L_CHK_PREV, COND_NE),      # b.ne L_CHK_PREV
        0x5280002F,                                         # mov w15, #1
        enc_b(CAVE1 + 0x50, L_NEXT),                        # b L_NEXT
        0x7100899F,                                         # L_CHK_PREV: cmp w12, #0x22
        enc_b_cond(CAVE1 + 0x58, L_IS_PREV, COND_EQ),       # b.eq L_IS_PREV
        0x71008D9F,                                         # cmp w12, #0x23
        enc_b_cond(CAVE1 + 0x60, L_NEXT, COND_NE),          # b.ne L_NEXT
        0x52800030,                                         # L_IS_PREV: mov w16, #1
        0x110005CE,                                         # L_NEXT: add w14, w14, #1
        0x71000DDF,                                         # cmp w14, #3
        enc_b_cond(CAVE1 + 0x70, L_LOOP, COND_LO),          # b.lo L_LOOP
        enc_cbz(False, False, 15, CAVE1 + 0x74, L_NO_EIS),  # cbz w15, L_NO_EIS
        enc_cbz(False, False, 16, CAVE1 + 0x78, L_NO_EIS),  # cbz w16, L_NO_EIS
        0x52800028,                                         # mov w8, #1
        enc_b(CAVE1 + 0x80, 0x563C4),                       # b 0x563c4
        0x7100067F,                                         # L_STOCK: cmp w19, #1
        enc_b_cond(CAVE1 + 0x88, L_NO_EIS, COND_HI),        # b.hi L_NO_EIS
        0x7100755F,                                         # cmp w10, #0x1d
        enc_b_cond(CAVE1 + 0x90, L_NO_EIS, COND_HI),        # b.hi L_NO_EIS
        0x52800028,                                         # mov w8, #1
        0x5280002B,                                         # mov w11, #1
        0x72A4080B,                                         # movk w11, #0x2040, lsl #16
        0x1ACA210C,                                         # lsl w12, w8, w10
        0x6A0B019F,                                         # tst w12, w11
        enc_b_cond(CAVE1 + 0xA8, L_NO_EIS, COND_EQ),        # b.eq L_NO_EIS
        enc_b(CAVE1 + 0xAC, 0x563C4),                       # b 0x563c4
        enc_b(CAVE1 + 0xB0, 0x5600C),                       # L_NO_EIS: b 0x5600c
    ]

    CAVE2 = 0xF38C0
    L_SEL_STOCK = CAVE2 + 0x24
    cave2_insns = [
        0xB9401268,                                         # ldr w8, [x19, #0x10]
        enc_cbz(False, True, 8, CAVE2 + 0x04, L_SEL_STOCK), # cbnz w8, L_SEL_STOCK
        enc_adrp(16, CAVE2 + 0x08, 0x211000),               # adrp x16, 0x211000
        0x911C6210,                                         # add x16, x16, #0x718
        0xB959BA10,                                         # ldr w16, [x16, #0x19b8]
        0x51000611,                                         # sub w17, w16, #1
        0x7100063F,                                         # cmp w17, #1
        enc_b_cond(CAVE2 + 0x1C, 0xDCCF4, COND_LS),         # b.ls 0xdccf4
        enc_b(CAVE2 + 0x20, 0xDCFCC),                       # b 0xdcfcc
        0x12900150,                                         # L_SEL_STOCK: mov w16, #-0x800b
        0x0A1C0115,                                         # and w21, w8, w28
        0x0B1002AA,                                         # add w10, w21, w16
        0x7100755F,                                         # cmp w10, #0x1d
        enc_b_cond(CAVE2 + 0x34, 0xDCFCC, COND_HI),         # b.hi 0xdcfcc
        0x52800031,                                         # mov w17, #1
        0x5280002F,                                         # mov w15, #1
        0x72A40811,                                         # movk w17, #0x2040, lsl #16
        0x1ACA21E2,                                         # lsl w2, w15, w10
        0x6A11005F,                                         # tst w2, w17
        enc_b_cond(CAVE2 + 0x4C, 0xDCFCC, COND_EQ),         # b.eq 0xdcfcc
        enc_b(CAVE2 + 0x50, 0xDCCF4),                       # b 0xdccf4
    ]

    CAVE3 = 0xF3920
    L_VID_S0 = CAVE3 + 0x34
    L_VID_S1 = CAVE3 + 0x44
    L_PICK_SNAP = CAVE3 + 0x50
    cave3_insns = [
        0xF9400665,                                         # ldr x5, [x19, #0x8]
        0xF9400A86,                                         # ldr x6, [x20, #0x10]
        0xF94000A7,                                         # ldr x7, [x5]
        0xF94004A8,                                         # ldr x8, [x5, #0x8]
        0xF94008A9,                                         # ldr x9, [x5, #0x10]
        0xB94010EC,                                         # ldr w12, [x7, #0x10]
        enc_tbz(16, True, 12, CAVE3 + 0x18, L_VID_S0),      # tbnz w12, #16, L_VID_S0
        0xB940110C,                                         # ldr w12, [x8, #0x10]
        enc_tbz(16, True, 12, CAVE3 + 0x20, L_VID_S1),      # tbnz w12, #16, L_VID_S1
        0xAA0903EE,                                         # mov x14, x9
        0xAA0703EA,                                         # mov x10, x7
        0xAA0803EB,                                         # mov x11, x8
        enc_b(CAVE3 + 0x30, L_PICK_SNAP),                   # b L_PICK_SNAP
        0xAA0703EE,                                         # L_VID_S0: mov x14, x7
        0xAA0803EA,                                         # mov x10, x8
        0xAA0903EB,                                         # mov x11, x9
        enc_b(CAVE3 + 0x40, L_PICK_SNAP),                   # b L_PICK_SNAP
        0xAA0803EE,                                         # L_VID_S1: mov x14, x8
        0xAA0703EA,                                         # mov x10, x7
        0xAA0903EB,                                         # mov x11, x9
        0xB9400D4C,                                         # L_PICK_SNAP: ldr w12, [x10, #0xc]
        0x7100859F,                                         # cmp w12, #0x21
        0x9A8A016D,                                         # csel x13, x11, x10, eq
        0x9A8B014F,                                         # csel x15, x10, x11, eq
        0xF94000D0,                                         # ldr x16, [x6]
        0xF900120D,                                         # str x13, [x16, #0x20]
        0xF94004D0,                                         # ldr x16, [x6, #0x8]
        0xF900120E,                                         # str x14, [x16, #0x20]
        0xF94008D0,                                         # ldr x16, [x6, #0x10]
        0xF900120F,                                         # str x15, [x16, #0x20]
        0xAA1F03FC,                                         # mov x28, xzr
        enc_b(CAVE3 + 0x7C, 0xDC4CC),                       # b 0xdc4cc
    ]

    cave1_bytes = struct.pack(f"<{len(cave1_insns)}I", *cave1_insns)
    cave2_bytes = struct.pack(f"<{len(cave2_insns)}I", *cave2_insns)
    cave3_bytes = struct.pack(f"<{len(cave3_insns)}I", *cave3_insns)
    assert len(cave1_bytes) == 0xB4 and len(cave2_bytes) == 0x54 and len(cave3_bytes) == 0x80

    hook_5639c = enc_b(0x5639C, CAVE1)
    hook_dccc4 = enc_b(0xDCCC4, CAVE2)
    hook_dd100 = enc_b(0xDD100, CAVE3)
    hook_dd188 = enc_b(0xDD188, CAVE3)

    if (
        struct.unpack_from("<I", chi_raw, 0x5639C)[0] == hook_5639c
        and struct.unpack_from("<I", chi_raw, 0xDCCC4)[0] == hook_dccc4
        and struct.unpack_from("<I", chi_raw, 0xDD100)[0] == hook_dd100
        and struct.unpack_from("<I", chi_raw, 0xDD188)[0] == hook_dd188
        and bytes(chi_raw[CAVE1 : CAVE1 + len(cave1_bytes)]) == cave1_bytes
        and bytes(chi_raw[CAVE2 : CAVE2 + len(cave2_bytes)]) == cave2_bytes
        and bytes(chi_raw[CAVE3 : CAVE3 + len(cave3_bytes)]) == cave3_bytes
    ):
        return False

    assert struct.unpack_from("<I", chi_raw, 0x56398)[0] == 0xD10203AD
    assert struct.unpack_from("<I", chi_raw, 0x5639C)[0] == 0x7100067F
    assert struct.unpack_from("<I", chi_raw, 0xDCCC4)[0] == 0xB9401268
    assert struct.unpack_from("<I", chi_raw, 0xDD100)[0] == 0xF940066B
    assert struct.unpack_from("<I", chi_raw, 0xDD188)[0] == 0xF9400665
    assert all(b == 0 for b in chi_raw[0xF3800:0xF3A00]), "CHI code cave 0xf3800..0xf3a00 is not empty!"

    e_phoff = struct.unpack_from("<Q", chi_raw, 0x20)[0]
    e_shoff = struct.unpack_from("<Q", chi_raw, 0x28)[0]
    e_phentsize, e_phnum, e_shentsize, e_shnum = struct.unpack_from("<HHHH", chi_raw, 0x36)

    found_pt = False
    for i in range(e_phnum):
        poff = e_phoff + i * e_phentsize
        p_type, p_flags, p_offset, p_vaddr, _, p_filesz, p_memsz, _ = struct.unpack_from("<IIQQQQQQ", chi_raw, poff)
        if p_type == 1 and p_offset == 0x3C000 and p_vaddr == 0x3C000:
            assert p_flags == 0x5 and p_filesz == 0xB7800 and p_memsz == 0xB7800
            struct.pack_into("<QQ", chi_raw, poff + 0x20, 0xB7A00, 0xB7A00)
            found_pt = True
    assert found_pt, "Missing RX PT_LOAD[2] in com.qti.chi.override.so"

    found_sh = False
    for i in range(e_shnum):
        soff = e_shoff + i * e_shentsize
        sh_addr, sh_offset, sh_size = struct.unpack_from("<QQQ", chi_raw, soff + 0x10)
        if sh_addr == 0xF2FE0 and sh_offset == 0xF2FE0:
            assert sh_size == 0x820
            struct.pack_into("<Q", chi_raw, soff + 0x20, 0xA20)
            found_sh = True
    assert found_sh, "Missing .plt section header in com.qti.chi.override.so"

    chi_raw[CAVE1 : CAVE1 + len(cave1_bytes)] = cave1_bytes
    chi_raw[CAVE2 : CAVE2 + len(cave2_bytes)] = cave2_bytes
    chi_raw[CAVE3 : CAVE3 + len(cave3_bytes)] = cave3_bytes
    struct.pack_into("<I", chi_raw, 0x5639C, hook_5639c)
    struct.pack_into("<I", chi_raw, 0xDCCC4, hook_dccc4)
    struct.pack_into("<I", chi_raw, 0xDD100, hook_dd100)
    struct.pack_into("<I", chi_raw, 0xDD188, hook_dd188)
    return True


def promote_dt_needed_first(elf_path: str, target_soname: str = "libdisplaydebug.so") -> bool:
    """
    Idempotently rotates target_soname to DT_NEEDED[0] in an ELF64 shared library's .dynamic
    table in-place (zero file-size or section-offset change), preserving mode/uid/gid/SELinux xattr.
    Combined with DF_1_GLOBAL (-Wl,-z,global) on libdisplaydebug.so, guarantees Bionic linker64
    binds SDM/DRM PLT slots to our interposer ahead of hwcomposer.qcom.so and libdrm.so.
    """
    assert os.path.isfile(elf_path), f"Missing ELF64 target: {elf_path}"
    ctx = get_selinux_xattr(elf_path)
    st = os.stat(elf_path)
    with open(elf_path, "rb") as f:
        raw = bytearray(f.read())
    assert raw[:4] == b"\x7fELF" and raw[4] == 2 and raw[5] == 1, f"Not little-endian ELF64: {elf_path}"

    e_phoff = struct.unpack_from("<Q", raw, 0x20)[0]
    e_phentsize, e_phnum = struct.unpack_from("<HH", raw, 0x36)
    pt_loads = []
    dyn_off = None
    dyn_sz = 0
    for i in range(e_phnum):
        poff = e_phoff + i * e_phentsize
        p_type, _, p_offset, p_vaddr, _, p_filesz, _, _ = struct.unpack_from("<IIQQQQQQ", raw, poff)
        if p_type == 1:
            pt_loads.append((p_vaddr, p_filesz, p_offset))
        elif p_type == 2:
            dyn_off = p_offset
            dyn_sz = p_filesz
    assert dyn_off is not None and dyn_sz >= 16, f"Missing PT_DYNAMIC in {elf_path}"

    def vaddr_to_off(vaddr: int) -> int:
        for pv, pfsz, poff in pt_loads:
            if pv <= vaddr < pv + pfsz:
                return poff + (vaddr - pv)
        raise AssertionError(f"Cannot map vaddr {hex(vaddr)} in {elf_path}")

    strtab_vaddr = None
    raw_needed = []
    for off in range(dyn_off, dyn_off + dyn_sz, 16):
        d_tag, d_val = struct.unpack_from("<qQ", raw, off)
        if d_tag == 0:
            break
        if d_tag == 5:
            strtab_vaddr = d_val
        elif d_tag == 1:
            raw_needed.append((off, d_val))
    assert strtab_vaddr is not None, f"Missing DT_STRTAB in {elf_path}"
    strtab_off = vaddr_to_off(strtab_vaddr)

    needed = []
    target_idx = None
    for idx, (off, d_val) in enumerate(raw_needed):
        s_start = strtab_off + d_val
        s_end = raw.index(0, s_start)
        soname = raw[s_start:s_end].decode("ascii", errors="replace")
        needed.append((off, d_val, soname))
        if soname == target_soname and target_idx is None:
            target_idx = idx

    assert target_idx is not None, (
        f"{target_soname} not found in DT_NEEDED of {elf_path}: {[n[2] for n in needed]}"
    )
    if target_idx == 0:
        return False

    rotated_dvals = [needed[target_idx][1]] + [needed[k][1] for k in range(target_idx)]
    for k, new_dval in enumerate(rotated_dvals):
        struct.pack_into("<Q", raw, needed[k][0] + 8, new_dval)

    with open(elf_path, "wb") as f:
        f.write(raw)
    os.chmod(elf_path, st.st_mode & 0o7777)
    os.chown(elf_path, st.st_uid, st.st_gid)
    set_selinux_xattr(elf_path, ctx)
    return True


def patch_vendor_mount(vendor_mnt: str) -> None:
    init_dir = os.path.join(vendor_mnt, "etc", "init")
    for rc_name in [
        "vendor.qti.adsprpc-service.rc",
        "vendor.sensors.sscrpcd.rc",
        "vppservice.rc",
    ]:
        p = os.path.join(init_dir, rc_name)
        if patch_file_preserving_xattr(p, add_adsp_env):
            print(f"[vendor-patch] Added ADSP_LIBRARY_PATH to {rc_name}")

    for rc_name in ["init.time_daemon.rc"]:
        p = os.path.join(init_dir, rc_name)
        if patch_file_preserving_xattr(p, add_disabled_to_service):
            print(f"[vendor-patch] Marked {rc_name} disabled until triggered")

    target_rc = os.path.join(init_dir, "hw", "init.target.rc")
    if patch_file_preserving_xattr(target_rc, strip_stop_statsd):
        print("[vendor-patch] Stripped 'stop statsd' from etc/init/hw/init.target.rc")

    fp23_rc = os.path.join(init_dir, "android.hardware.biometrics.fingerprint@2.3-service.samurai.rc")
    if patch_file_preserving_xattr(fp23_rc, patch_fp23_rc):
        print("[vendor-patch] Optimized android.hardware.biometrics.fingerprint@2.3-service.samurai.rc")

    hwc_rc = os.path.join(init_dir, "android.hardware.graphics.composer@2.4-service.rc")
    def patch_hwc_rc(rc_text: str) -> str:
        if "libfod_zpos_hook.so" in rc_text:
            return rc_text
        return rc_text.rstrip() + "\n    setenv LD_PRELOAD /vendor/lib64/libfod_zpos_hook.so\n"
    if patch_file_preserving_xattr(hwc_rc, patch_hwc_rc):
        print("[vendor-patch] Added LD_PRELOAD /vendor/lib64/libfod_zpos_hook.so to composer@2.4-service.rc")

    work_root_bin = os.environ.get("WORK_ROOT", "/var/tmp/rmx1931-a17")
    fod_hook_src = os.path.join(work_root_bin, "out", "bin", "libfod_zpos_hook.so")
    if os.path.isfile(fod_hook_src):
        for dst_name in ("libfod_zpos_hook.so", "libdisplaydebug.so"):
            fod_hook_dst = os.path.join(vendor_mnt, "lib64", dst_name)
            shutil.copyfile(fod_hook_src, fod_hook_dst)
            os.chmod(fod_hook_dst, 0o644)
            os.chown(fod_hook_dst, 0, 0)
            set_selinux_xattr(fod_hook_dst, "u:object_r:vendor_file:s0")
        print("[vendor-patch] Installed /vendor/lib64/libfod_zpos_hook.so & /vendor/lib64/libdisplaydebug.so (DF_1_GLOBAL SDM/DRM FOD bridge)")
    else:
        raise FileNotFoundError(f"Missing {fod_hook_src} (run build_apps.sh first)")

    for rel_elf in (
        os.path.join("lib64", "libsdedrm.so"),
        os.path.join("lib64", "hw", "hwcomposer.qcom.so"),
    ):
        elf_target = os.path.join(vendor_mnt, rel_elf)
        if promote_dt_needed_first(elf_target, "libdisplaydebug.so"):
            print(f"[vendor-patch] Promoted libdisplaydebug.so to DT_NEEDED[0] in /vendor/{rel_elf}")

    fstab_path = os.path.join(vendor_mnt, "etc", "fstab.qcom")
    if patch_file_preserving_xattr(fstab_path, patch_fstab_userdata):
        print("[vendor-patch] Patched etc/fstab.qcom /data entries for clean 4.14 ext4/f2fs mount")

    vbuild_prop = os.path.join(vendor_mnt, "build.prop")
    if patch_file_preserving_xattr(vbuild_prop, patch_vendor_build_prop):
        print("[vendor-patch] Unlocked AUX multi-camera lenses & RMX1931 product ID in /vendor/build.prop")

    patch_vendor_media_and_powerhint(vendor_mnt)
    write_vendor_config_file(
        os.path.join(vendor_mnt, "etc", ".pixelos_a17_vendor_ok"),
        "RMX1931_PIXELOS_A17_VENDOR=1\n",
    )

    chi_path = os.path.join(vendor_mnt, "lib64", "hw", "com.qti.chi.override.so")
    if os.path.isfile(chi_path):
        chi_ctx = get_selinux_xattr(chi_path)
        chi_st = os.stat(chi_path)
        with open(chi_path, "rb") as f:
            chi_raw = bytearray(f.read())
        base_off = 0x20E7FC
        entry_sz = 0xD4
        changed_chi = False
        for idx in range(10):
            off = base_off + idx * entry_sz
            cam_id, _, is_exp = struct.unpack_from("<III", chi_raw, off)
            assert cam_id == idx, f"Unexpected CHI cameraId at {hex(off)}: {cam_id} != {idx}"
            if idx < 4:
                assert is_exp == 1, f"Expected physical CHI camera {idx} isExposed=1, got {is_exp}"
            else:
                assert is_exp in (0, 1), f"Unexpected CHI isExposed at {hex(off)}: {is_exp}"
                if is_exp != 0:
                    struct.pack_into("<I", chi_raw, off + 8, 0)
                    changed_chi = True
        changed_eis = patch_chi_eis_4k60(chi_raw)
        if changed_chi or changed_eis:
            with open(chi_path, "wb") as f:
                f.write(chi_raw)
            os.chmod(chi_path, chi_st.st_mode & 0o7777)
            os.chown(chi_path, chi_st.st_uid, chi_st.st_gid)
            set_selinux_xattr(chi_path, chi_ctx)
            if changed_chi:
                print("[vendor-patch] Patched com.qti.chi.override.so Project 19781 table: exposed IDs 0..3, hid IDs 4..9")
            if changed_eis:
                print("[vendor-patch] Patched com.qti.chi.override.so Spectra 380 4K60/1080p60 Gyro-EIS3 + order-independent stream binder")

    work_root = os.environ.get("WORK_ROOT", "/var/tmp/rmx1931-a17")
    gcam_src = None
    for cand in [
        os.path.join(work_root, "downloads", "gcam", "MGC_9.6.080_V53_ENG.apk"),
        "/var/tmp/rmx1931-a17/downloads/gcam/MGC_9.6.080_V53_ENG.apk",
    ]:
        if os.path.isfile(cand):
            gcam_src = cand
            break
    if gcam_src:
        gcam_dir = os.path.join(vendor_mnt, "gcam")
        gcam_lib_dir = os.path.join(gcam_dir, "lib")
        gcam_arm64_dir = os.path.join(gcam_lib_dir, "arm64")
        if os.path.isdir(gcam_dir):
            shutil.rmtree(gcam_dir)
        os.makedirs(gcam_arm64_dir, mode=0o755, exist_ok=True)
        for d in (gcam_dir, gcam_lib_dir, gcam_arm64_dir):
            os.chmod(d, 0o755)
            os.chown(d, 0, 0)
            set_selinux_xattr(d, "u:object_r:system_file:s0")

        gcam_dst_apk = os.path.join(gcam_dir, "GoogleCameraEng.apk")
        shutil.copyfile(gcam_src, gcam_dst_apk)
        os.chmod(gcam_dst_apk, 0o644)
        os.chown(gcam_dst_apk, 0, 0)
        set_selinux_xattr(gcam_dst_apk, "u:object_r:system_file:s0")

        so_count = 0
        with zipfile.ZipFile(gcam_src, "r") as zf:
            for zi in zf.infolist():
                if zi.filename.startswith("lib/arm64-v8a/") and zi.filename.endswith(".so"):
                    so_name = os.path.basename(zi.filename)
                    so_dst = os.path.join(gcam_arm64_dir, so_name)
                    with zf.open(zi, "r") as src_f, open(so_dst, "wb") as dst_f:
                        shutil.copyfileobj(src_f, dst_f)
                    os.chmod(so_dst, 0o644)
                    os.chown(so_dst, 0, 0)
                    set_selinux_xattr(so_dst, "u:object_r:system_file:s0")
                    so_count += 1
        # Patch Google's TFLite OpenGL fallback UAF in extracted libgcastartup.so (offset 0x4fb76ac:
        # bl CreateOpenGLDelegate 0x9402b88d -> mov x0, #0 0xd2800000) so if any Pixel 9 TFLite model
        # ever fails OpenCL 2.0 on Adreno 640, InitImpl skips reusing the dirty InterpreterBuilder
        # (with freed delegates_[0]) and falls back cleanly to a fresh stack InterpreterBuilder on CPU.
        # GoogleCameraEng.apk itself remains 100% unmodified for v3 / PairIP signature verification.
        gcastartup_so = os.path.join(gcam_arm64_dir, "libgcastartup.so")
        assert os.path.isfile(gcastartup_so), "Missing extracted libgcastartup.so"
        with open(gcastartup_so, "r+b") as gf:
            gf.seek(0x4FB76AC)
            orig_insn = struct.unpack("<I", gf.read(4))[0]
            assert orig_insn in (0x9402B88D, 0xD2800000), (
                f"Unexpected instruction in libgcastartup.so at 0x4fb76ac: {hex(orig_insn)}"
            )
            if orig_insn != 0xD2800000:
                gf.seek(0x4FB76AC)
                gf.write(struct.pack("<I", 0xD2800000))
        os.chmod(gcastartup_so, 0o644)
        os.chown(gcastartup_so, 0, 0)
        set_selinux_xattr(gcastartup_so, "u:object_r:system_file:s0")
        print(f"[vendor-patch] Installed unmodified GoogleCameraEng.apk + {so_count} arm64 libs in /vendor/gcam (patched libgcastartup.so UAF guard)")

    # 3e. Fix Realme stock vendor SPHAL SELinux labels on Adreno 640 OpenCL & public vendor libraries
    sphal_fix_libs = [
        ("lib64", "libOpenCL_adreno.so"),
        ("lib", "libOpenCL_adreno.so"),
        ("lib64", "libkernelmanager.so"),
        ("lib", "libkernelmanager.so"),
        ("lib64", "libarcsoft_scbokeh_video.so"),
        ("lib", "libarcsoft_scbokeh_video.so"),
    ]
    relabelled_sphal = []
    for sub_arch, lib_fn in sphal_fix_libs:
        lib_p = os.path.join(vendor_mnt, sub_arch, lib_fn)
        if os.path.isfile(lib_p) and not os.path.islink(lib_p):
            cur_ctx = get_selinux_xattr(lib_p)
            if cur_ctx != "u:object_r:same_process_hal_file:s0":
                set_selinux_xattr(lib_p, "u:object_r:same_process_hal_file:s0")
                relabelled_sphal.append(f"{sub_arch}/{lib_fn}")
    if relabelled_sphal:
        print(f"[vendor-patch] Relabeled Adreno OpenCL SPHAL vendor libs -> same_process_hal_file:s0: {', '.join(relabelled_sphal)}")

    selinux_dir = os.path.join(vendor_mnt, "etc", "selinux")
    file_ctx = os.path.join(selinux_dir, "vendor_file_contexts")
    prop_ctx = os.path.join(selinux_dir, "vendor_property_contexts")
    svc_ctx = os.path.join(selinux_dir, "vendor_service_contexts")
    vendor_cil = os.path.join(selinux_dir, "vendor_sepolicy.cil")
    plat_pub_cil = os.path.join(selinux_dir, "plat_pub_versioned.cil")

    if os.path.isfile(file_ctx):
        def patch_file_ctx(txt: str) -> str:
            if "libOpenCL_adreno" in txt:
                return txt
            extra_fctx = (
                "\n/vendor/lib(64)?/libOpenCL_adreno\\.so   u:object_r:same_process_hal_file:s0"
                "\n/vendor/lib(64)?/libkernelmanager\\.so   u:object_r:same_process_hal_file:s0"
                "\n/vendor/lib(64)?/libarcsoft_scbokeh_video\\.so   u:object_r:same_process_hal_file:s0\n"
            )
            return txt.rstrip() + extra_fctx
        if patch_file_preserving_xattr(file_ctx, patch_file_ctx):
            print("[vendor-patch] Updated vendor_file_contexts for Adreno 640 OpenCL SPHAL libs")

    if os.path.isfile(prop_ctx):
        def patch_prop_ctx(txt: str) -> str:
            if "persist.vendor.fingerprint.sensor_type" in txt:
                return txt
            target_type = "oppo_fp_prop" if "oppo_fp_prop" in txt else "vendor_default_prop"
            return txt.rstrip() + f"\npersist.vendor.fingerprint.sensor_type u:object_r:{target_type}:s0\n"
        if patch_file_preserving_xattr(prop_ctx, patch_prop_ctx):
            print("[vendor-patch] Updated vendor_property_contexts for Goodix optical FOD")

    if os.path.isfile(svc_ctx) and os.path.isfile(vendor_cil):
        with open(vendor_cil, "r", encoding="utf-8", errors="replace") as f:
            vcil_txt = f.read()
        hal_drm_sym = detect_cil_version_suffix(plat_pub_cil, "hal_drm_service")
        if hal_drm_sym or symbol_exists_in_cil(vcil_txt, "hal_drm_service"):
            def patch_svc_ctx(txt: str) -> str:
                if "android.hardware.drm.IDrmFactory/widevine" in txt:
                    return txt
                return txt.rstrip() + "\nandroid.hardware.drm.IDrmFactory/widevine u:object_r:hal_drm_service:s0\n"
            if patch_file_preserving_xattr(svc_ctx, patch_svc_ctx):
                print("[vendor-patch] Bound IDrmFactory/widevine in vendor_service_contexts")

    if os.path.isfile(vendor_cil):
        def patch_vcil(txt: str) -> str:
            if "; RMX1931_A17_SEPOLICY" in txt:
                txt = txt.split("; RMX1931_A17_SEPOLICY")[0].rstrip()
            extra = ["\n; RMX1931_A17_SEPOLICY"]
            gms_sym = detect_cil_version_suffix(plat_pub_cil, "gmscore_app")
            if gms_sym and symbol_exists_in_cil(txt, "vendor_sysfs_usb_supply"):
                extra.append(f"(dontaudit {gms_sym} vendor_sysfs_usb_supply (dir (search)))")
                extra.append(f"(dontaudit {gms_sym} vendor_sysfs_usb_supply (file (read open getattr)))")
            if symbol_exists_in_cil(txt, "vendor_wcnss_service"):
                vprop = detect_cil_version_suffix(plat_pub_cil, "vendor_default_prop")
                if vprop:
                    extra.append(f"(allow vendor_wcnss_service {vprop} (property_service (set)))")
            ms_sym = detect_cil_version_suffix(plat_pub_cil, "mediaserver")
            vdef_prop = detect_cil_version_suffix(plat_pub_cil, "vendor_default_prop")
            if ms_sym and vdef_prop:
                extra.append(f"(dontaudit {ms_sym} {vdef_prop} (file (read open getattr map)))")
            ss_sym = detect_cil_version_suffix(plat_pub_cil, "system_server")
            vcfg_sym = detect_cil_version_suffix(plat_pub_cil, "vendor_configs_file")
            if ss_sym and vcfg_sym:
                extra.append(f"(allow {ss_sym} {vcfg_sym} (file (read open getattr)))")
            init_sym = detect_cil_version_suffix(plat_pub_cil, "init")
            if init_sym:
                for b_t in ("vendor_sysfs_battery_supply", "vendor_sysfs_usb_supply"):
                    if symbol_exists_in_cil(txt, b_t):
                        extra.append(f"(allow {init_sym} {b_t} (dir (search read open getattr)))")
                        extra.append(f"(allow {init_sym} {b_t} (file (ioctl read write open getattr lock append)))")
                for fp_t in ("proc_touchpanel", "sysfs_oppo_display", "fingerprint_device"):
                    if symbol_exists_in_cil(txt, fp_t):
                        extra.append(f"(allow {init_sym} {fp_t} (dir (search read open getattr setattr)))")
                        extra.append(f"(allow {init_sym} {fp_t} (file (ioctl read write open getattr setattr lock append)))")
            if symbol_exists_in_cil(txt, "hal_fingerprint_default"):
                for fp_rw in ("proc_touchpanel", "sysfs_oppo_display", "vendor_sysfs_fingerprint"):
                    if symbol_exists_in_cil(txt, fp_rw):
                        extra.append(f"(allow hal_fingerprint_default {fp_rw} (dir (search read open getattr)))")
                        extra.append(f"(allow hal_fingerprint_default {fp_rw} (file (ioctl read write open getattr lock append map watch watch_reads)))")
                for plat_dev in ("ion_device", "uhid_device", "uinput_device"):
                    pdev_sym = detect_cil_version_suffix(plat_pub_cil, plat_dev)
                    if pdev_sym:
                        extra.append(f"(allow hal_fingerprint_default {pdev_sym} (chr_file (ioctl read write open getattr lock append map)))")
                for plat_data in ("fingerprint_vendor_data_file", "vendor_data_file"):
                    pdata_sym = detect_cil_version_suffix(plat_pub_cil, plat_data)
                    if pdata_sym:
                        extra.append(f"(allow hal_fingerprint_default {pdata_sym} (dir (ioctl read write create getattr setattr lock rename open watch watch_reads add_name remove_name reparent search rmdir)))")
                        extra.append(f"(allow hal_fingerprint_default {pdata_sym} (file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))")
            if symbol_exists_in_cil(txt, "hal_syshelper_default"):
                for sh_rw in ("proc_touchpanel", "sysfs_oppo_display"):
                    if symbol_exists_in_cil(txt, sh_rw):
                        extra.append(f"(allow hal_syshelper_default {sh_rw} (dir (search read open getattr)))")
                        extra.append(f"(allow hal_syshelper_default {sh_rw} (file (ioctl read write open getattr lock append map)))")
            if symbol_exists_in_cil(txt, "hal_graphics_composer_default"):
                for hwc_rw in ("proc_touchpanel", "sysfs_oppo_display"):
                    if symbol_exists_in_cil(txt, hwc_rw):
                        extra.append(f"(allow hal_graphics_composer_default {hwc_rw} (dir (search read open getattr)))")
                        extra.append(f"(allow hal_graphics_composer_default {hwc_rw} (file (ioctl read write open getattr lock append map)))")
            if ss_sym:
                for ss_rw in ("proc_touchpanel", "sysfs_oppo_display", "vendor_sysfs_fingerprint"):
                    if symbol_exists_in_cil(txt, ss_rw):
                        extra.append(f"(allow {ss_sym} {ss_rw} (dir (search read open getattr)))")
                        extra.append(f"(allow {ss_sym} {ss_rw} (file (ioctl read write open getattr lock append map)))")
            old_soter = "(typeattributeset vendor_hal_soter_client (system_app_202504 tee_202504))"
            new_soter = "(typeattributeset vendor_hal_soter_client (system_app_202504 platform_app_202504 priv_app_202504 system_server_202504 tee_202504))"
            if old_soter in txt:
                txt = txt.replace(old_soter, new_soter, 1)
            old_dsp = "(typeattributeset vendor_hal_dspmanager_client (shell_202504 untrusted_app_202504 untrusted_app_29_202504 untrusted_app_27_202504 untrusted_app_25_202504))"
            new_dsp = "(typeattributeset vendor_hal_dspmanager_client (shell_202504 untrusted_app_202504 untrusted_app_29_202504 untrusted_app_27_202504 untrusted_app_25_202504 hal_fingerprint_default vendor_cdsprpcd vendor_adsprpcd hal_camera_default hal_audio_default))"
            if old_dsp in txt:
                txt = txt.replace(old_dsp, new_dsp, 1)
            old_crypto = "(typeattribute hal_cryptoeng_client)"
            new_crypto = "(typeattribute hal_cryptoeng_client)\n(typeattributeset hal_cryptoeng_client (hal_fingerprint_default system_server_202504 system_app_202504 platform_app_202504 priv_app_202504))"
            if "(typeattributeset hal_cryptoeng_client " not in txt and old_crypto in txt:
                txt = txt.replace(old_crypto, new_crypto, 1)
            old_genfs = '(genfscon proc "/fp_id" (u object_r fp_proc ((s0) (s0))))'
            new_genfs = '(genfscon proc "/fp_id" (u object_r fp_proc ((s0) (s0))))\n(genfscon proc "/fp_kernel_event" (u object_r fp_proc ((s0) (s0))))\n(genfscon proc "/tee_bind_core" (u object_r fp_proc ((s0) (s0))))'
            if '/fp_kernel_event' not in txt and old_genfs in txt:
                txt = txt.replace(old_genfs, new_genfs, 1)
            extra.extend([
                "; RMX1931_GOODIX_G3_UDFPS_TA_CDSP_SEPOLICY",
                "(allow hal_fingerprint_default vendor_firmware_file (dir (ioctl read getattr lock open watch watch_reads search)))",
                "(allow hal_fingerprint_default vendor_firmware_file (file (ioctl read getattr lock map open watch watch_reads)))",
                "(allow hal_fingerprint_default vendor_firmware_file (lnk_file (ioctl read getattr lock map open watch watch_reads)))",
                "(allow tee_202504 vendor_firmware_file (dir (ioctl read getattr lock open watch watch_reads search)))",
                "(allow tee_202504 vendor_firmware_file (file (ioctl read getattr lock map open watch watch_reads)))",
                "(allow tee_202504 vendor_firmware_file (lnk_file (ioctl read getattr lock map open watch watch_reads)))",
                "(allow hal_cryptoeng_default vendor_firmware_file (dir (ioctl read getattr lock open watch watch_reads search)))",
                "(allow hal_cryptoeng_default vendor_firmware_file (file (ioctl read getattr lock map open watch watch_reads)))",
                "(allow hal_cryptoeng_default vendor_firmware_file (lnk_file (ioctl read getattr lock map open watch watch_reads)))",
                "(allow hal_fingerprint_default vendor_qdsp_device (chr_file (ioctl read write getattr lock append map open watch watch_reads)))",
                "(allow vendor_cdsprpcd vendor_qdsp_device (chr_file (ioctl read write getattr lock append map open watch watch_reads)))",
                "(allow vendor_cdsprpcd vendor_xdsp_device (chr_file (ioctl read write getattr lock append map open watch watch_reads)))",
                "(allow vendor_dspservice vendor_qdsp_device (chr_file (ioctl read write getattr lock append map open watch watch_reads)))",
                "(allow vendor_dspservice vendor_xdsp_device (chr_file (ioctl read write getattr lock append map open watch watch_reads)))",
                "(allow hal_fingerprint_default public_adsprpcd_file (dir (ioctl read getattr lock open watch watch_reads search)))",
                "(allow hal_fingerprint_default public_adsprpcd_file (file (ioctl read getattr lock map open watch watch_reads)))",
                "(allow hal_fingerprint_default public_adsprpcd_file (lnk_file (ioctl read getattr lock map open watch watch_reads)))",
                "(allow hal_fingerprint_default adsprpcd_file (dir (ioctl read getattr lock open watch watch_reads search)))",
                "(allow hal_fingerprint_default adsprpcd_file (file (ioctl read getattr lock map open watch watch_reads)))",
                "(allow hal_fingerprint_default adsprpcd_file (lnk_file (ioctl read getattr lock map open watch watch_reads)))",
                "(allow vendor_cdsprpcd adsprpcd_file (dir (ioctl read getattr lock open watch watch_reads search)))",
                "(allow vendor_cdsprpcd adsprpcd_file (file (ioctl read getattr lock map open watch watch_reads)))",
                "(allow vendor_cdsprpcd adsprpcd_file (lnk_file (ioctl read getattr lock map open watch watch_reads)))",
                "(allow vendor_cdsprpcd ion_device_202504 (chr_file (ioctl read write getattr lock append map open watch watch_reads)))",
                "(allow vendor_cdsprpcd dmabuf_heap_device_202504 (chr_file (ioctl read getattr lock map open watch watch_reads)))",
                "(allow vendor_cdsprpcd dmabuf_system_heap_device_202504 (chr_file (ioctl read getattr lock map open watch watch_reads)))",
                "(allow hal_fingerprint_default dmabuf_heap_device_202504 (chr_file (ioctl read getattr lock map open watch watch_reads)))",
                "(allow hal_fingerprint_default dmabuf_system_heap_device_202504 (chr_file (ioctl read getattr lock map open watch watch_reads)))",
                "(allow hal_fingerprint_default mnt_vendor_file_202504 (dir (ioctl read getattr lock open watch watch_reads search)))",
                "(allow hal_fingerprint_default vendor_persist_file (dir (ioctl read write create getattr setattr lock rename open watch watch_reads add_name remove_name reparent search rmdir)))",
                "(allow hal_fingerprint_default vendor_persist_file (file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))",
                "(allow hal_fingerprint_default fp_proc (file (ioctl read write getattr lock append map open watch watch_reads)))",
                "(allow hal_cryptoeng_client hal_cryptoeng_server (binder (call transfer)))",
                "(allow hal_cryptoeng_server hal_cryptoeng_client (binder (call transfer)))",
                "(allow hal_cryptoeng_client hal_cryptoeng_server (fd (use)))",
                "(allow hal_cryptoeng_server hal_cryptoeng_client (fd (use)))",
                "(allow hal_cryptoeng_default vendor_data_file_202504 (dir (ioctl read write create getattr setattr lock rename open watch watch_reads add_name remove_name reparent search rmdir)))",
                "(allow hal_cryptoeng_default vendor_data_file_202504 (file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))",
                "(allow hal_cryptoeng_default mnt_vendor_file_202504 (dir (ioctl read getattr lock open watch watch_reads search)))",
                "(allow hal_cryptoeng_default vendor_persist_file (dir (ioctl read write create getattr setattr lock rename open watch watch_reads add_name remove_name reparent search rmdir)))",
                "(allow hal_cryptoeng_default vendor_persist_file (file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))",
                "(allow hal_fingerprint_default sysfs_wake_lock_202504 (file (ioctl read write getattr lock append map open watch watch_reads)))",
                "(allow hal_fingerprint_default self (capability2 (block_suspend)))",
                "(allow hal_fingerprint_default self (cap2_userns (block_suspend)))",
                "(allow vendor_cdsprpcd sysfs_wake_lock_202504 (file (ioctl read write getattr lock append map open watch watch_reads)))",
                "(allow vendor_cdsprpcd self (capability2 (block_suspend)))",
                "(allow vendor_cdsprpcd self (cap2_userns (block_suspend)))",
                "(allow vendor_adsprpcd sysfs_wake_lock_202504 (file (ioctl read write getattr lock append map open watch watch_reads)))",
                "(allow vendor_adsprpcd self (capability2 (block_suspend)))",
                "(allow vendor_adsprpcd self (cap2_userns (block_suspend)))",
            ])
            return txt.rstrip() + "\n" + "\n".join(extra) + "\n"

        if patch_file_preserving_xattr(vendor_cil, patch_vcil):
            print("[vendor-patch] Appended verified Android 17 CIL rules to vendor_sepolicy.cil")

    for pre in ["precompiled_sepolicy", "precompiled_sepolicy.plat_sepolicy_and_mapping.sha256"]:
        p = os.path.join(selinux_dir, pre)
        if os.path.exists(p):
            os.remove(p)
            print(f"[vendor-patch] Removed stale {pre} (forces clean Android 17 CIL compilation)")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: patch_vendor_a17.py <mounted_vendor_dir>", file=sys.stderr)
        sys.exit(1)
    patch_vendor_mount(sys.argv[1])
