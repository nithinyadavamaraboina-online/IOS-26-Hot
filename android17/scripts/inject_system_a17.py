#!/usr/bin/env python3
"""
Injects Clean Pixel OS Hardware Integration, SamuraiParts (Tap-to-Wake, Tap-to-Close, 90Hz OLED,
50W SuperVOOC, Dual TFA9894 Audio & AW8697 Haptics), Repo-Driven System OTA Updater (SamuraiOTAUpdater),
and ALL hardware-proven Linux 4.14.356 kernel adaptations into the mounted Android 17 GMS GSI:
1. Purges any stale non-Pixel custom UI files if present, preserving 100% genuine Google Pixel Launcher,
   Google Setup Wizard, Play Store, GMS, Phone, Messages, Chrome, Gboard, and Material 3 Expressive SystemUI.
2. Installs SamuraiOTAUpdater.apk (/system/priv-app/SamuraiOTAUpdater) + TWRP OpenRecoveryScript stager.
3. Installs SamuraiParts.apk (/system/priv-app/SamuraiParts), static ARM64 /system/bin/samurai_hw_daemon,
   Synaptics S3706 & virtual uinput keylayouts (touchpanel_kpd.kl, touchpanel.kl, samurai-virtual-keys.kl),
   samurai_parts.rc, init.samurai.perf.rc, and init.samurai.bootguard.rc.
4. Installs all 5 high-priority (priority=950) static RRO overlays in /system/system_ext/overlay/:
   SamuraiHardwareOverlay.apk, SamuraiSystemUIOverlay.apk, SamuraiSettingsOverlay.apk,
   SamuraiDialerOverlay.apk, SamuraiMessagesOverlay.apk.
5. Extracts com.android.tethering.apex from /system/apex and builds the 4 hardware-verified 4.14 binaries:
   netbpfload_414, bpf_netd_414.o, libnetd_updatable_414.so, libservice-connectivity_414.so.
6. Quarantines 5.8+/5.15+ platform ringbuf/iter .bpf objects & strips 'critical' from mmd.rc.
7. Patches plat_file_contexts & plat_sepolicy.cil (/dev/ion, BPF mounton, ota_package_file rules for priv_app).
8. Configures 90Hz SurfaceFlinger VSync offsets, 8-core dex2oat, RescueParty bootguard, AUX cameras & OTA props.
"""
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import zipfile
from datetime import datetime, timezone

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)
from qti_ims_telephony_a17 import (
    QTI_SYSTEM_BUILD_PROPS,
    QTI_SYSTEM_EXT_BUILD_PROPS,
    inject_qti_ims_and_dialer_support,
)


def set_selinux(path: str, context: str) -> None:
    subprocess.run(
        ["setfattr", "-h", "-n", "security.selinux", "-v", context, path],
        check=True,
    )


def get_selinux(path: str, default: str = "u:object_r:system_file:s0") -> str:
    r = subprocess.run(
        ["getfattr", "-h", "--only-values", "-n", "security.selinux", path],
        capture_output=True,
    )
    if r.returncode == 0 and r.stdout:
        val = r.stdout.decode("utf-8", errors="ignore").strip("\x00\r\n ")
        if val:
            return val
    return default


def ensure_dir(path: str, mode: int = 0o755, ctx: str = "u:object_r:system_file:s0") -> None:
    if not os.path.exists(path):
        os.makedirs(path, mode=mode, exist_ok=True)
    os.chmod(path, mode)
    os.chown(path, 0, 0)
    set_selinux(path, ctx)


def install_file(
    src: str,
    dst: str,
    mode: int = 0o644,
    uid: int = 0,
    gid: int = 0,
    ctx: str = "u:object_r:system_file:s0",
) -> None:
    parent = os.path.dirname(dst)
    ensure_dir(parent, 0o755, "u:object_r:system_file:s0")
    shutil.copyfile(src, dst)
    os.chmod(dst, mode)
    os.chown(dst, uid, gid)
    set_selinux(dst, ctx)


def install_bytes(
    data: bytes,
    dst: str,
    mode: int = 0o644,
    uid: int = 0,
    gid: int = 0,
    ctx: str = "u:object_r:system_file:s0",
) -> None:
    parent = os.path.dirname(dst)
    ensure_dir(parent, 0o755, "u:object_r:system_file:s0")
    with open(dst, "wb") as f:
        f.write(data)
    os.chmod(dst, mode)
    os.chown(dst, uid, gid)
    set_selinux(dst, ctx)


def patch_build_prop(
    prop_path: str,
    updates: dict[str, str],
    remove_prefixes: tuple[str, ...] = (),
) -> None:
    if not os.path.isfile(prop_path):
        return
    orig_ctx = get_selinux(prop_path, "u:object_r:system_file:s0")
    with open(prop_path, "r", encoding="utf-8", errors="replace") as f:
        lines = f.read().splitlines()

    remaining = dict(updates)
    out = []
    for line in lines:
        stripped = line.strip()
        if stripped and not stripped.startswith("#") and "=" in stripped:
            k, _ = stripped.split("=", 1)
            key = k.strip()
            if remove_prefixes and any(key.startswith(pfx) for pfx in remove_prefixes):
                continue
            if key in remaining:
                out.append(f"{key}={remaining.pop(key)}")
                continue
        out.append(line)

    if remaining:
        out.append("")
        out.append("# RMX1931 Android 17 Pixel OS Hardware, Gestures & OTA Configuration")
        for k, v in remaining.items():
            out.append(f"{k}={v}")

    with open(prop_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    os.chmod(prop_path, 0o600)
    os.chown(prop_path, 0, 0)
    set_selinux(prop_path, orig_ctx)


def purge_any_legacy_custom_ui(sys_sub: str, is_ios_beta: bool = False) -> None:
    legacy_paths = [
        os.path.join(sys_sub, "priv-app", "LiquidGlassSystem"),
        os.path.join(sys_sub, "etc", "permissions", "privapp-permissions-liquidglass.xml"),
        os.path.join(sys_sub, "etc", "default-permissions", "default-permissions-liquidglass.xml"),
        os.path.join(sys_sub, "etc", "init", "liquidglass.rc"),
        os.path.join(sys_sub, "etc", "liquidglass-boot.sh"),
        os.path.join(sys_sub, "product", "overlay", "LiquidGlassFrameworkOverlay.apk"),
        os.path.join(sys_sub, "product", "overlay", "LiquidGlassSystemUIOverlay.apk"),
        os.path.join(sys_sub, "product", "overlay", "SamuraiHardwareOverlay.apk"),
        # Redundant 2014 AOSP stub bloat in raw GSI (Google equivalents + Pixel GCam 9.6 used instead)
        os.path.join(sys_sub, "product", "app", "Camera2"),
        os.path.join(sys_sub, "product", "app", "Calendar"),
        os.path.join(sys_sub, "app", "BasicDreams"),
        os.path.join(sys_sub, "app", "PartnerBookmarksProvider"),
        os.path.join(sys_sub, "app", "IosDialer"),
        os.path.join(sys_sub, "app", "ICall26"),
        os.path.join(sys_sub, "app", "IosMessages"),
        os.path.join(sys_sub, "system_ext", "overlay", "SamuraiICallOverlay.apk"),
        # Retired v2.0.0 rushed apps & permission whitelists
        os.path.join(sys_sub, "priv-app", "IosClock"),
        os.path.join(sys_sub, "etc", "permissions", "privapp-permissions-ios-clock.xml"),
        os.path.join(sys_sub, "app", "IosCalculator"),
        os.path.join(sys_sub, "app", "IosNotes"),
        os.path.join(sys_sub, "app", "IosWeather"),
        # Retired v1.0/v1.1 native NFQUEUE/hosts AdBlocker (disabled in v1.2 for zero-latency Chrome & YouTube)
        os.path.join(sys_sub, "bin", "samurai_adblockd"),
        os.path.join(sys_sub, "etc", "adblock_seed.gz"),
        # Strip userdebug GSI su binaries for retail user-build parity
        os.path.join(sys_sub, "bin", "su"),
        os.path.join(sys_sub, "xbin", "su"),
    ]
    if not is_ios_beta:
        legacy_paths.extend([
            os.path.join(sys_sub, "priv-app", "IosStyler"),
            os.path.join(sys_sub, "etc", "permissions", "privapp-permissions-styler.xml"),
            os.path.join(sys_sub, "priv-app", "IosSettings"),
            os.path.join(sys_sub, "etc", "permissions", "privapp-permissions-ios-settings.xml"),
            os.path.join(sys_sub, "priv-app", "IosControlCenter"),
            os.path.join(sys_sub, "etc", "permissions", "privapp-permissions-ios-controlcenter.xml"),
            os.path.join(sys_sub, "priv-app", "IosDialer"),
            os.path.join(sys_sub, "etc", "permissions", "privapp-permissions-ios-dialer.xml"),
            os.path.join(sys_sub, "priv-app", "IosMessages"),
            os.path.join(sys_sub, "etc", "permissions", "privapp-permissions-ios-messages.xml"),
            os.path.join(sys_sub, "etc", "default-permissions", "default-permissions-ios-comms.xml"),
            os.path.join(sys_sub, "system_ext", "overlay", "SamuraiDialerOverlay.apk"),
            os.path.join(sys_sub, "system_ext", "overlay", "SamuraiMessagesOverlay.apk"),
            os.path.join(sys_sub, "system_ext", "overlay", "SamuraiGmailOverlay.apk"),
            os.path.join(sys_sub, "system_ext", "overlay", "SamuraiPhotosOverlay.apk"),
            os.path.join(sys_sub, "system_ext", "overlay", "SamuraiLauncherOverlay.apk"),
            os.path.join(sys_sub, "system_ext", "overlay", "SamuraiIos26WallpaperOverlay.apk"),
        ])
    for p in legacy_paths:
        if os.path.isdir(p):
            shutil.rmtree(p)
        elif os.path.exists(p):
            os.remove(p)


def inject_pixel_font_aliases(sys_sub: str) -> None:
    fonts_xml = os.path.join(sys_sub, "etc", "fonts.xml")
    if not os.path.isfile(fonts_xml):
        return
    orig_ctx = get_selinux(fonts_xml, "u:object_r:system_file:s0")
    with open(fonts_xml, "r", encoding="utf-8") as f:
        txt = f.read()
    if 'name="roboto-flex-medium"' in txt:
        return
    alias_block = (
        '  <!-- Pixel OS Variable Typography Aliases (RobotoFlex) -->\n'
        '  <alias name="roboto-flex-medium" to="roboto-flex" weight="500"/>\n'
        '  <alias name="roboto-flex-bold" to="roboto-flex" weight="700"/>\n'
        '  <alias name="google-sans" to="roboto-flex"/>\n'
        '  <alias name="google-sans-medium" to="roboto-flex" weight="500"/>\n'
        '  <alias name="google-sans-bold" to="roboto-flex" weight="700"/>\n'
        '  <alias name="google-sans-text" to="roboto-flex"/>\n'
        '  <alias name="google-sans-text-medium" to="roboto-flex" weight="500"/>\n'
        '  <alias name="google-sans-text-bold" to="roboto-flex" weight="700"/>\n'
        '</familyset>'
    )
    if "</familyset>" not in txt:
        raise RuntimeError(f"Unexpected fonts.xml structure in {fonts_xml}")
    updated = txt.replace("</familyset>", alias_block, 1)
    with open(fonts_xml, "w", encoding="utf-8", newline="\n") as f:
        f.write(updated)
    os.chmod(fonts_xml, 0o644)
    os.chown(fonts_xml, 0, 0)
    set_selinux(fonts_xml, orig_ctx)


def build_and_inject_414_bpf_netd(sys_sub: str, repo_root: str) -> None:
    apex_dir = os.path.join(sys_sub, "apex")
    tethering_apex = None
    for fn in os.listdir(apex_dir):
        if "tethering" in fn and (fn.endswith(".apex") or fn.endswith(".capex")):
            tethering_apex = os.path.join(apex_dir, fn)
            break
    if not tethering_apex:
        raise RuntimeError(f"Could not find com.android.tethering apex in {apex_dir}")

    with tempfile.TemporaryDirectory(prefix="tethering_414_") as tmp:
        with zipfile.ZipFile(tethering_apex, "r") as zf:
            names = zf.namelist()
            if "original_apex" in names:
                orig_apex = os.path.join(tmp, "original.apex")
                with open(orig_apex, "wb") as f:
                    f.write(zf.read("original_apex"))
                with zipfile.ZipFile(orig_apex, "r") as zf2:
                    zf2.extract("apex_payload.img", tmp)
            else:
                zf.extract("apex_payload.img", tmp)

        payload_img = os.path.join(tmp, "apex_payload.img")
        mnt = os.path.join(tmp, "mnt")
        os.makedirs(mnt, exist_ok=True)
        subprocess.run(["mount", "-o", "loop,ro", payload_img, mnt], check=True)
        try:
            with open(os.path.join(mnt, "bin", "netbpfload"), "rb") as f:
                raw_nb = bytearray(f.read())
            with open(os.path.join(mnt, "etc", "bpf", "mainline", "netd.o"), "rb") as f:
                raw_netd_o = bytearray(f.read())
            with open(os.path.join(mnt, "lib64", "libnetd_updatable.so"), "rb") as f:
                raw_so = bytearray(f.read())
            with open(os.path.join(mnt, "lib64", "libservice-connectivity.so"), "rb") as f:
                raw_sc = bytearray(f.read())
        finally:
            subprocess.run(["umount", "-lf", mnt], check=False)

    # 1. Patch netbpfload -> netbpfload_414 (bit-for-bit identical to hardware-proven netbpfload_414)
    for off, expected, new_val, desc in [
        (0x98c4, 0x54002141, 0xd503201f, "allow bind-mounted /proc/self/exe st_dev"),
        (0xa528, 0xd0000869, 0xd0000873, "bypass 4.19/5.4/5.10/5.15 kver gates (adrp)"),
        (0xa52c, 0x7110491f, 0x52800029, "bypass 4.19/5.4/5.10/5.15 kver gates (mov w9, #1)"),
        (0xa530, 0x39447129, 0x14000030, "bypass 4.19/5.4/5.10/5.15 kver gates (branch)"),
        (0xb04c, 0x94000f0e, 0xd503201f, "skip access(uprobestatsbpfload, F_OK) SELinux abort"),
        (0xb050, 0x360002c0, 0x14000016, "jump straight to platform /system/bin/bpfloader"),
    ]:
        cur, = struct.unpack_from("<I", raw_nb, off)
        assert cur == expected, f"Unexpected netbpfload@{hex(off)} ({desc}): {hex(cur)} != {hex(expected)}"
        struct.pack_into("<I", raw_nb, off, new_val)

    # 2. Patch netd.o -> bpf_netd_414.o (max_api 65536 + BPF_ARSH -> BPF_RSH at 0xb4a0 & 0x1a100)
    for prog_name, off in [("ingress/stats$4_9_t", 0x1e5e0 + 32), ("egress/stats$4_9_t", 0x1eb80 + 32)]:
        max_api, = struct.unpack_from("<I", raw_netd_o, off)
        assert max_api == 3500, f"Unexpected max_api at {hex(off)} ({prog_name}): {max_api}"
        struct.pack_into("<I", raw_netd_o, off, 65536)

    for prog_name, arsh_off in [("ingress/stats$4_9_t", 0xb4a0), ("egress/stats$4_9_t", 0x1a100)]:
        op_and, r_and, _, imm_and = struct.unpack_from("<BBhi", raw_netd_o, arsh_off - 16)
        op_lsh, r_lsh, _, imm_lsh = struct.unpack_from("<BBhi", raw_netd_o, arsh_off - 8)
        op_rsh, r_rsh, _, imm_rsh = struct.unpack_from("<BBhi", raw_netd_o, arsh_off)
        assert (op_and, r_and, imm_and) == (0x57, 8, 1), f"Unexpected insn before {prog_name}"
        assert (op_lsh, r_lsh, imm_lsh) == (0x67, 8, 32), f"Unexpected lsh in {prog_name}"
        assert (op_rsh, r_rsh, imm_rsh) == (0xc7, 8, 32), f"Unexpected arsh in {prog_name}"
        raw_netd_o[arsh_off] = 0x77  # BPF_ALU64 | BPF_RSH | BPF_K

    # 3. Patch libnetd_updatable.so -> libnetd_updatable_414.so
    for off, expected, new_val, desc in [
        (0x91cc, 0x540008c8, 0x14000046, "bypass V+ < 4.19.0 check"),
        (0x92ec, 0x54001ce8, 0x140000e7, "bypass 25Q2+ < 5.4.0 check"),
        (0x9bac, 0xf0000068, 0x140003e5, "skip 5.4+/6.1+ spinlock & tcp sockopt self-tests"),
    ]:
        cur, = struct.unpack_from("<I", raw_so, off)
        assert cur == expected, f"Unexpected libnetd_updatable@{hex(off)} ({desc}): {hex(cur)}"
        struct.pack_into("<I", raw_so, off, new_val)

    # 4. Patch libservice-connectivity.so -> libservice-connectivity_414.so
    assert struct.unpack_from("<I", raw_sc, 0xf3bc)[0] == 0x910003e0
    assert struct.unpack_from("<I", raw_sc, 0xf3ec)[0] == 0x940015ad
    assert struct.unpack_from("<I", raw_sc, 0x120d8)[0] == 0xd10103a0
    assert struct.unpack_from("<I", raw_sc, 0x12108)[0] == 0x94000a66
    for idx, insn in enumerate([
        0x2a1f03e0,  # mov  w0, wz
        0x52810001,  # mov  w1, #0x800 (EFD_NONBLOCK)
        0x72a00101,  # movk w1, #0x8, lsl #16 (| EFD_CLOEXEC)
        0x9400175a,  # bl   0x15130 <eventfd@plt>
        0xb9001a60,  # str  w0, [x19, #0x18]
        0x17ffffed,  # b    0xf384
    ]):
        struct.pack_into("<I", raw_sc, 0xf3bc + idx * 4, insn)
    for idx, insn in enumerate([
        0xaa1f03f3,  # mov  x19, xz
        0x17fffff3,  # b    0x120a8
    ]):
        struct.pack_into("<I", raw_sc, 0x120d8 + idx * 4, insn)

    # 5. Patch /system/lib64/libmeminfo.so so BpfMapRO<uint64_t,uint64_t>::abortOnMismatch
    #    does not abort system_server when 5.4+ BTF /sys/fs/bpf/map_gpuMem_gpu_mem_total_map
    #    is absent on Qualcomm KGSL Linux 4.14 (allowing ReadProcessGpuUsageKb/ReadPerProcessGpuMem
    #    to execute their built-in `if (!map.isValid()) return false;` fallback).
    libmeminfo_path = os.path.join(sys_sub, "lib64", "libmeminfo.so")
    with open(libmeminfo_path, "rb") as f:
        raw_mi = bytearray(f.read())
    for off, expected, new_val, desc in [
        (0x258b4, 0x94001339, 0xd503201f, "ReadPerProcessGpuMem skip BpfMapRO::abortOnMismatch"),
        (0x2647c, 0x94001047, 0xd503201f, "ReadProcessGpuUsageKb skip BpfMapRO::abortOnMismatch"),
        (0x2a598, 0xd10543ff, 0xd65f03c0, "BpfMapRO<u64,u64>::abortOnMismatch -> ret"),
    ]:
        cur, = struct.unpack_from("<I", raw_mi, off)
        assert cur in (expected, new_val), f"Unexpected libmeminfo.so@{hex(off)} ({desc}): {hex(cur)}"
        struct.pack_into("<I", raw_mi, off, new_val)
    install_bytes(
        bytes(raw_mi),
        libmeminfo_path,
        mode=0o644, uid=0, gid=0, ctx="u:object_r:system_lib_file:s0",
    )

    install_bytes(
        bytes(raw_nb),
        os.path.join(sys_sub, "bin", "netbpfload_414"),
        mode=0o755, uid=0, gid=0, ctx="u:object_r:bpfloader_exec:s0",
    )
    install_bytes(
        bytes(raw_netd_o),
        os.path.join(sys_sub, "etc", "bpf_netd_414.o"),
        mode=0o644, uid=1000, gid=1000, ctx="u:object_r:system_file:s0",
    )
    install_bytes(
        bytes(raw_so),
        os.path.join(sys_sub, "lib64", "libnetd_updatable_414.so"),
        mode=0o644, uid=1000, gid=1000, ctx="u:object_r:system_lib_file:s0",
    )
    install_bytes(
        bytes(raw_sc),
        os.path.join(sys_sub, "lib64", "libservice-connectivity_414.so"),
        mode=0o644, uid=0, gid=0, ctx="u:object_r:system_lib_file:s0",
    )

    win_out = os.path.join(repo_root, "work", "a17", "out")
    if os.path.isdir(win_out):
        with open(os.path.join(win_out, "netbpfload_414"), "wb") as f:
            f.write(raw_nb)
        with open(os.path.join(win_out, "bpf_netd_414.o"), "wb") as f:
            f.write(raw_netd_o)
        with open(os.path.join(win_out, "libnetd_updatable_414.so"), "wb") as f:
            f.write(raw_so)
        with open(os.path.join(win_out, "libservice-connectivity_414.so"), "wb") as f:
            f.write(raw_sc)

    nb_rc_path = os.path.join(sys_sub, "etc", "init", "netbpfload.rc")
    first_line = "# 2026 3 37 1 0 # 26q3 sdk/api level 37.1 - Android 17 QPR1"
    if os.path.isfile(nb_rc_path):
        with open(nb_rc_path, "r", encoding="utf-8", errors="replace") as f:
            orig_first = f.readline().strip()
            if orig_first.startswith("# 20"):
                first_line = orig_first

    nb_rc_new = f"""{first_line}

on load-bpf-programs
    write /proc/sys/kernel/kptr_restrict 1
    mount none /system/bin/netbpfload_414 /apex/com.android.tethering/bin/netbpfload bind
    mount none /system/etc/bpf_netd_414.o /apex/com.android.tethering/etc/bpf/mainline/netd.o bind
    mount none /system/lib64/libnetd_updatable_414.so /apex/com.android.tethering/lib64/libnetd_updatable.so bind
    mount none /system/lib64/libservice-connectivity_414.so /apex/com.android.tethering/lib64/libservice-connectivity.so bind
    mount none /system/lib64/libservice-connectivity_414.so /apex/com.android.tethering@371899999/lib64/libservice-connectivity.so bind
    exec_start bpfloader
    wait_for_prop bpf.progs_loaded 1
    write /proc/sys/kernel/kptr_restrict 2
    exec_start netd1shot
    start netd

service bpfloader /system/bin/false
    disabled
    user root
    oneshot
    reboot_on_failure reboot,netbpfload-missing
    updatable
"""
    install_bytes(
        nb_rc_new.encode("utf-8"),
        nb_rc_path,
        mode=0o644, uid=0, gid=0, ctx="u:object_r:system_file:s0",
    )
    print("[system-inject] Built & injected 4.14 BPF/netd/connectivity suite")


def patch_zygote_next_for_kernel_414(sys_sub: str) -> None:
    """
    Patches Android 17's Rust zygote_next runtime libraries for Linux 4.14.356 & vendor compatibility
    so Chrome/Brave/WebView isolated native renderers (com.android.chrome_zygote_native,
    com.brave.browser_zygote_native & NativeOnlySandboxedProcessService0) spawn cleanly:
      1. libzygote_sys.dylib.so @ 0xb8e8: bl clone3@plt (0x940017b6, Linux 5.3+) -> bl fork@plt (0x9400179e),
         semantically identical because both Spawn and SpawnSubspecies pass {flags: 0, exit_signal: SIGCHLD}.
      2. libcap_rust.dylib.so @ 0x3600 & 0x3654: handle CAP_LAST_CAP=37 on Linux 4.14 when zygote-child
         queries/drops Linux 5.8+/5.9+ capabilities 38..40 (CAP_PERFMON, CAP_BPF, CAP_CHECKPOINT_RESTORE).
      3. libzygote_species_android_native.so @ 0x6540 & 0x72b4: skip telemetry-only stats_write calls
         (NativeZygoteStarted & NativeSubspeciesZygoteForked) to their Ok(()) continuations (0x1400002e)
         so zygote_next never opens or closes /dev/socket/statsdw behind FileDescriptorRegistry's back
         even if statsd is stopped or restarted at runtime.
    """
    zsys_path = os.path.join(sys_sub, "lib64", "libzygote_sys.dylib.so")
    with open(zsys_path, "rb") as f:
        raw_zsys = bytearray(f.read())
    cur_zsys, = struct.unpack_from("<I", raw_zsys, 0xb8e8)
    assert cur_zsys in (0x940017b6, 0x9400179e), (
        f"Unexpected libzygote_sys.dylib.so@0xb8e8: {hex(cur_zsys)}"
    )
    struct.pack_into("<I", raw_zsys, 0xb8e8, 0x9400179e)
    install_bytes(
        bytes(raw_zsys),
        zsys_path,
        mode=0o644, uid=0, gid=0, ctx="u:object_r:system_lib_file:s0",
    )

    cap_path = os.path.join(sys_sub, "lib64", "libcap_rust.dylib.so")
    with open(cap_path, "rb") as f:
        raw_cap = bytearray(f.read())
    for off, expected, new_val, desc in [
        (0x3600, 0x54000100, 0xd503201f, "cap_within_bound return Ok(false) on cap > CAP_LAST_CAP"),
        (0x3654, 0x7100a01f, 0x7100941f, "drop_bounding_set cap loop bound 40 -> 37 (Linux 4.14 CAP_LAST_CAP)"),
    ]:
        cur_cap, = struct.unpack_from("<I", raw_cap, off)
        assert cur_cap in (expected, new_val), (
            f"Unexpected libcap_rust.dylib.so@{hex(off)} ({desc}): {hex(cur_cap)}"
        )
        struct.pack_into("<I", raw_cap, off, new_val)
    install_bytes(
        bytes(raw_cap),
        cap_path,
        mode=0o644, uid=0, gid=0, ctx="u:object_r:system_lib_file:s0",
    )

    species_path = os.path.join(sys_sub, "lib64", "libzygote_species_android_native.so")
    with open(species_path, "rb") as f:
        raw_sp = bytearray(f.read())
    assert len(raw_sp) == 69432, f"Unexpected libzygote_species_android_native.so size: {len(raw_sp)}"
    for off, expected, new_val, cont_off, desc in [
        (0x6540, 0x94000754, 0x1400002e, 0x6550, "skip native_subspecies_zygote_forked::stats_write -> 0x65f8"),
        (0x72b4, 0x9400047b, 0x1400002e, 0x72c4, "skip native_zygote_started::stats_write -> 0x736c"),
    ]:
        cur_sp, = struct.unpack_from("<I", raw_sp, off)
        cont_sp, = struct.unpack_from("<I", raw_sp, cont_off)
        assert cont_sp == 0x54000540, f"Unexpected continuation branch at {hex(cont_off)}: {hex(cont_sp)}"
        assert cur_sp in (expected, new_val), (
            f"Unexpected libzygote_species_android_native.so@{hex(off)} ({desc}): {hex(cur_sp)}"
        )
        struct.pack_into("<I", raw_sp, off, new_val)
    install_bytes(
        bytes(raw_sp),
        species_path,
        mode=0o644, uid=0, gid=0, ctx="u:object_r:system_lib_file:s0",
    )

    zn_rc_path = os.path.join(sys_sub, "etc", "init", "zygote_next64.rc")
    zn_rc_new = """service zygote_next /system/bin/zygote_next --name zygote_next --species /system/lib64/libzygote_species_android_native.so --log-level INFO --arg-buf-padding xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
    user root
    group root readproc reserved_disk
    priority -20
    socket zygote_next seqpacket 660 root system
    task_profiles ProcessCapacityHigh MaxPerformance
    disabled
    onrestart setprop zygote.zygote_next.server_ready false

on boot
    start statsd
    start zygote_next

on property:sys.boot_completed=1
    start statsd
"""
    install_bytes(
        zn_rc_new.encode("utf-8"),
        zn_rc_path,
        mode=0o644, uid=0, gid=0, ctx="u:object_r:system_file:s0",
    )
    print("[system-inject] Patched Android 17 zygote_next (libzygote_sys + libcap_rust + libzygote_species + zygote_next64.rc) for Linux 4.14")


def quarantine_515_platform_bpf(sys_sub: str) -> None:
    bpf_dir = os.path.join(sys_sub, "etc", "bpf")
    disabled_dir = os.path.join(sys_sub, "etc", "bpf.disabled_515")
    os.makedirs(disabled_dir, mode=0o755, exist_ok=True)
    os.chown(disabled_dir, 0, 0)
    set_selinux(disabled_dir, "u:object_r:system_file:s0")

    moved = []
    for item in ["dmabuf", "wakeupsources", "lock_contention", "memevents", "fuseMedia.bpf", "bpfRingbufProg.bpf"]:
        src = os.path.join(bpf_dir, item)
        dst = os.path.join(disabled_dir, item)
        if os.path.exists(src):
            shutil.move(src, dst)
            moved.append(item)
    print(f"[system-inject] Quarantined 5.8+/5.15+ platform BPF objects: {moved}")


def patch_mmd_and_adb_rc(sys_sub: str) -> None:
    mmd_rc = os.path.join(sys_sub, "etc", "init", "mmd.rc")
    if os.path.isfile(mmd_rc):
        ctx = get_selinux(mmd_rc, "u:object_r:system_file:s0")
        with open(mmd_rc, "r", encoding="utf-8", errors="replace") as f:
            lines = [ln for ln in f.read().splitlines() if ln.strip() != "critical"]
        with open(mmd_rc, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(lines) + "\n")
        os.chmod(mmd_rc, 0o644)
        os.chown(mmd_rc, 0, 0)
        set_selinux(mmd_rc, ctx)

    adb_rc = (
        "on property:sys.usb.configfs=1 && property:sys.usb.config=none\n"
        "    setprop sys.usb.config adb\n"
    )
    install_bytes(
        adb_rc.encode("utf-8"),
        os.path.join(sys_sub, "etc", "init", "init.rmx.adb.rc"),
        mode=0o644, uid=0, gid=0, ctx="u:object_r:system_file:s0",
    )


ION_DOMAINS_RW = [
    "adbd", "base_typeattr_281", "bootanim", "bufferhubd", "cameraserver",
    "fingerprintd", "fwk_bufferhub", "gatekeeperd", "gpuservice", "hal_audio",
    "hal_authgraph_server", "hal_camera", "hal_cas", "hal_codec2_server",
    "hal_codec2_client", "base_typeattr_631", "hal_face", "hal_fingerprint",
    "hal_gatekeeper", "hal_graphics_allocator", "hal_graphics_composer",
    "hal_keymaster", "base_typeattr_681", "hal_neuralnetworks_server",
    "hal_omx_server", "halserverdomain", "mediaserver", "rs", "shell",
    "surfaceflinger", "system_server", "zygote",
]


def patch_platform_selinux(sys_sub: str) -> None:
    fc_path = os.path.join(sys_sub, "etc", "selinux", "plat_file_contexts")
    if os.path.isfile(fc_path):
        fc_ctx = get_selinux(fc_path, "u:object_r:file_contexts_file:s0")
        with open(fc_path, "r", encoding="utf-8", errors="replace") as f:
            fc_txt = f.read()
        fc_lines = [
            ln for ln in fc_txt.splitlines()
            if "samurai_adblockd" not in ln and "/data/ota_package/adblock/hosts" not in ln
        ]
        fc_txt_clean = "\n".join(fc_lines)
        extra_fc = []
        if not re.search(r"^/dev/ion\s", fc_txt_clean, flags=re.MULTILINE):
            extra_fc.append("/dev/ion\t\tu:object_r:ion_device:s0")
        if "/system/bin/netbpfload_414" not in fc_txt_clean:
            extra_fc.append("/system/bin/netbpfload_414\tu:object_r:bpfloader_exec:s0")
        if "bpfloader\\.real" not in fc_txt_clean:
            extra_fc.append("/system/bin/bpfloader\\.real\tu:object_r:bpfloader_platform_exec:s0")
        if "/system/bin/samurai_hw_daemon" not in fc_txt_clean:
            extra_fc.append("/system/bin/samurai_hw_daemon\tu:object_r:system_file:s0")
        if "/system/bin/samurai_ota_bcb" not in fc_txt_clean:
            extra_fc.append("/system/bin/samurai_ota_bcb\tu:object_r:system_file:s0")
        if "/data/ota_raw" not in fc_txt_clean:
            extra_fc.append("/data/ota_raw(/.*)?\tu:object_r:ota_package_file:s0")
        if "/data/preloads/ota_raw" not in fc_txt_clean:
            extra_fc.append("/data/preloads/ota_raw(/.*)?\tu:object_r:ota_package_file:s0")
        new_fc_txt = fc_txt_clean.rstrip() + ("\n" + "\n".join(extra_fc) if extra_fc else "") + "\n"
        if new_fc_txt != fc_txt:
            with open(fc_path, "w", encoding="utf-8", newline="\n") as f:
                f.write(new_fc_txt)
            os.chmod(fc_path, 0o644)
            os.chown(fc_path, 0, 0)
            set_selinux(fc_path, fc_ctx)

    pc_path = os.path.join(sys_sub, "etc", "selinux", "plat_property_contexts")
    if os.path.isfile(pc_path):
        pc_ctx = get_selinux(pc_path, "u:object_r:property_contexts_file:s0")
        with open(pc_path, "r", encoding="utf-8", errors="replace") as f:
            pc_txt = f.read()
        extra_pc = []
        for ctl_key in ("ctl.start$zygote_next", "ctl.stop$zygote_next", "ctl.restart$zygote_next"):
            if ctl_key not in pc_txt:
                extra_pc.append(f"{ctl_key}\tu:object_r:ctl_default_prop:s0")
        if extra_pc:
            new_pc_txt = pc_txt.rstrip() + "\n" + "\n".join(extra_pc) + "\n"
            with open(pc_path, "w", encoding="utf-8", newline="\n") as f:
                f.write(new_pc_txt)
            os.chmod(pc_path, 0o644)
            os.chown(pc_path, 0, 0)
            set_selinux(pc_path, pc_ctx)

    plat_cil = os.path.join(sys_sub, "etc", "selinux", "plat_sepolicy.cil")
    if os.path.isfile(plat_cil):
        cil_ctx = get_selinux(plat_cil, "u:object_r:sepolicy_file:s0")
        with open(plat_cil, "r", encoding="utf-8", errors="replace") as f:
            cil_txt = f.read()

        if "; RMX1931_PLAT_A17_414" in cil_txt:
            cil_txt = cil_txt.split("; RMX1931_PLAT_A17_414")[0].rstrip()

        rules = ["; RMX1931_PLAT_A17_414"]
        if re.search(r"\btype\s+gpuservice\b", cil_txt) and re.search(r"\btype\s+fs_bpf\b", cil_txt):
            rules.append("(allow gpuservice fs_bpf (file (getattr)))")
        for mnt_target in ["bpfloader_exec", "system_file", "system_lib_file"]:
            if re.search(rf"\b{mnt_target}\b", cil_txt):
                rules.append(f"(allow init {mnt_target} (file (mounton)))")
        if re.search(r"\bsystem_file\b", cil_txt):
            rules.append("(allow init system_file (dir (mounton)))")
        for p_dom in ["init", "toolbox", "bpfloader", "netd", "adbd"]:
            if re.search(rf"\btype\s+{p_dom}\b", cil_txt):
                rules.append(f"(typepermissive {p_dom})")
        if re.search(r"\bsystem_server\b", cil_txt) and re.search(r"\btype\s+init\b", cil_txt):
            rules.append("(allow system_server init (binder (call transfer)))")
            rules.append("(allow system_server init (fd (use)))")
            rules.append("(allow system_server init (fifo_file (ioctl read write getattr append)))")
        if re.search(r"\bservicemanager\b", cil_txt) and re.search(r"\btype\s+init\b", cil_txt):
            rules.append("(allow servicemanager init (binder (call transfer)))")
            rules.append("(allow init servicemanager (binder (call transfer)))")
        # Allow privileged SamuraiOTAUpdater, SamuraiParts & system_server (Android 17 priv_app AND targetSdk<=36 priv_app_36 / priv_app_all)
        # to read/write/create files in /data/ota_package(/hw) under SELinux Enforcing
        if re.search(r"\bota_package_file\b", cil_txt):
            for p_ota_dom in ["system_server", "priv_app_all", "priv_app", "priv_app_36", "platform_app", "platform_app_36", "system_app"]:
                if re.search(rf"\b{p_ota_dom}\b", cil_txt):
                    rules.append(f"(allow {p_ota_dom} ota_package_file (dir (ioctl read write create getattr setattr lock rename open watch watch_reads add_name remove_name reparent search rmdir)))")
                    rules.append(f"(allow {p_ota_dom} ota_package_file (file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))")
        # Allow Settings (system_app) Developer Options AbstractLogpersistPreferenceController to read/set logpersistd_logging_prop without crashing
        for dev_prop in ["logpersistd_logging_prop", "logd_prop", "debug_prop", "log_tag_prop"]:
            if re.search(rf"\b{dev_prop}\b", cil_txt):
                for app_dom in ["system_app", "platform_app", "priv_app", "priv_app_all"]:
                    if re.search(rf"\b{app_dom}\b", cil_txt):
                        rules.append(f"(allow {app_dom} {dev_prop} (property_service (set)))")
                        rules.append(f"(allow {app_dom} {dev_prop} (file (read getattr map open)))")
        # Allow Pixel Google Camera 9.6 (BSG V53, targetSdk=35 -> untrusted_app_34 / untrusted_app_all)
        # native libdobby.so (DobbyHook + RamPatcher) to restore RX protection after in-RAM code patching
        if re.search(r"\buntrusted_app_all\b", cil_txt):
            for execmod_t in ["app_data_file", "apk_data_file", "system_file", "system_lib_file", "same_process_hal_file"]:
                if re.search(rf"\b{execmod_t}\b", cil_txt):
                    rules.append(f"(allow untrusted_app_all {execmod_t} (file (execmod)))")
        if re.search(r"\btype\s+ion_device\b", cil_txt):
            if re.search(r"\bdumpstate\b", cil_txt):
                rules.append("(allow dumpstate ion_device (chr_file (ioctl read getattr lock map open watch watch_reads)))")
            for dom in ION_DOMAINS_RW:
                if re.search(rf"\b{re.escape(dom)}\b", cil_txt):
                    rules.append(
                        f"(allow {dom} ion_device (chr_file (ioctl read write getattr lock append map open watch watch_reads)))"
                    )

        cil_txt = cil_txt.rstrip() + "\n" + "\n".join(rules) + "\n"
        with open(plat_cil, "w", encoding="utf-8", newline="\n") as f:
            f.write(cil_txt)
        os.chmod(plat_cil, 0o644)
        os.chown(plat_cil, 0, 0)
        set_selinux(plat_cil, cil_ctx)
        print(f"[system-inject] Appended {len(rules) - 1} verified 4.14 ION/BPF/OTA/SamuraiParts CIL rules to plat_sepolicy.cil")


def scan_gapps(sys_root: str) -> list[dict]:
    found = []
    targets = [
        ("NexusLauncher", "Pixel Launcher"),
        ("WallpaperPickerGoogle", "Pixel Wallpaper & Style"),
        ("PixelThemesStub", "Pixel Themes"),
        ("Launcher3QuickStep", "Quickstep Launcher"),
        ("Phonesky", "Google Play Store"),
        ("PrebuiltGmsCore", "Google Play Services (GMS Core)"),
        ("GoogleServicesFramework", "Google Services Framework (GSF)"),
        ("SetupWizard", "Google Setup Wizard"),
        ("Velvet", "Google App / Assistant"),
        ("LatinIMEGoogle", "Gboard"),
        ("IosDialer", "iOS 26 Liquid Glass Phone & InCallService (IosDialer)"),
        ("IosMessages", "iOS 26 Liquid Glass Messages (IosMessages)"),
        ("IosSettings", "iOS 26 Liquid Glass Settings (IosSettings)"),
        ("GoogleDialer", "Google Phone"),
        ("PrebuiltBugle", "Google Messages"),
        ("GoogleContacts", "Google Contacts"),
        ("WebViewGoogle", "Android System WebView"),
        ("Chrome", "Google Chrome"),
        ("DeskClockGoogle", "Google Clock"),
        ("CalendarGoogle", "Google Calendar"),
        ("CalculatorGoogle", "Google Calculator"),
        ("Photos", "Google Photos (Unlimited Pixel XL Backup)"),
        ("FilesGoogle", "Files by Google"),
        ("GoogleCameraEng", "Pixel Google Camera 9.6 (BSG V53)"),
    ]
    for root, _, files in os.walk(sys_root):
        for fn in files:
            if not fn.endswith(".apk"):
                continue
            full = os.path.join(root, fn)
            rel = os.path.relpath(full, sys_root)
            for key, desc in targets:
                if key.lower() in full.lower():
                    found.append({
                        "component": desc,
                        "path": "/" + rel.replace("\\", "/"),
                        "size_mb": round(os.path.getsize(full) / (1024 * 1024), 2),
                    })
                    break
    for gcam_dl in [
        os.path.join(os.environ.get("WORK_ROOT", "/var/tmp/rmx1931-a17"), "downloads", "gcam", "MGC_9.6.080_V53_ENG.apk"),
        "/var/tmp/rmx1931-a17/downloads/gcam/MGC_9.6.080_V53_ENG.apk",
    ]:
        if os.path.isfile(gcam_dl) and not any(x["component"].startswith("Pixel Google Camera") for x in found):
            found.append({
                "component": "Pixel Google Camera 9.6 (BSG V53)",
                "path": "/product/app/GoogleCameraEng/GoogleCameraEng.apk",
                "size_mb": round(os.path.getsize(gcam_dl) / (1024 * 1024), 2),
            })
            break
    return found


def main() -> int:
    if len(sys.argv) < 5:
        print("Usage: inject_system_a17.py <sys_rw_root> <a17_repo_dir> <out_apks_dir> <manifest_json_out>")
        return 1

    sys_rw, a17_dir, apks_dir, manifest_out = sys.argv[1:5]
    out_bin_dir = os.path.join(os.path.dirname(apks_dir), "bin")
    repo_root = os.path.abspath(os.path.join(a17_dir, ".."))
    sys_sub = os.path.join(sys_rw, "system") if os.path.isdir(os.path.join(sys_rw, "system", "priv-app")) else sys_rw

    rom_flavor = os.environ.get("ROM_FLAVOR", "pixelos").strip().lower()
    if rom_flavor not in ("pixelos", "ios-beta"):
        raise RuntimeError(f"Unsupported ROM_FLAVOR={rom_flavor!r} (expected 'pixelos' or 'ios-beta')")
    is_ios_beta = (rom_flavor == "ios-beta")
    ota_channel = "ios_beta" if is_ios_beta else "stable"
    print(f"[system-inject] ROM_FLAVOR={rom_flavor} -> ro.ota.channel={ota_channel}")

    # 0. Ensure zero legacy non-Pixel custom UI files exist & inject RobotoFlex Pixel font aliases
    purge_any_legacy_custom_ui(sys_sub, is_ios_beta=is_ios_beta)
    inject_pixel_font_aliases(sys_sub)

    # 1. Inject Privileged SamuraiOTAUpdater.apk (Pixel OS System Update) + permissions + TWRP ORS engine
    ota_src = os.path.join(a17_dir, "SamuraiOTAUpdater")
    install_file(
        os.path.join(apks_dir, "SamuraiOTAUpdater.apk"),
        os.path.join(sys_sub, "priv-app", "SamuraiOTAUpdater", "SamuraiOTAUpdater.apk"),
        mode=0o644,
    )
    install_file(
        os.path.join(ota_src, "etc", "permissions", "privapp-permissions-ota.xml"),
        os.path.join(sys_sub, "etc", "permissions", "privapp-permissions-ota.xml"),
        mode=0o644,
    )
    install_file(
        os.path.join(ota_src, "etc", "default-permissions", "default-permissions-ota.xml"),
        os.path.join(sys_sub, "etc", "default-permissions", "default-permissions-ota.xml"),
        mode=0o644,
    )
    install_file(
        os.path.join(ota_src, "etc", "init", "ota.rc"),
        os.path.join(sys_sub, "etc", "init", "ota.rc"),
        mode=0o644,
    )
    install_file(
        os.path.join(ota_src, "etc", "bin", "ota-watcher.sh"),
        os.path.join(sys_sub, "etc", "ota-watcher.sh"),
        mode=0o755,
    )
    install_file(
        os.path.join(ota_src, "etc", "bin", "ota-post-fs.sh"),
        os.path.join(sys_sub, "etc", "ota-post-fs.sh"),
        mode=0o755,
    )
    install_file(
        os.path.join(ota_src, "etc", "bin", "pixel-boot.sh"),
        os.path.join(sys_sub, "etc", "pixel-boot.sh"),
        mode=0o755,
    )
    install_file(
        os.path.join(out_bin_dir, "samurai_ota_bcb"),
        os.path.join(sys_sub, "bin", "samurai_ota_bcb"),
        mode=0o755,
        uid=0,
        gid=0,
        ctx="u:object_r:system_file:s0",
    )

    # 1b. Inject Privileged SamuraiParts.apk + static ARM64 samurai_hw_daemon + Keylayouts + Init .rc
    parts_src = os.path.join(a17_dir, "SamuraiParts")
    install_file(
        os.path.join(apks_dir, "SamuraiParts.apk"),
        os.path.join(sys_sub, "priv-app", "SamuraiParts", "SamuraiParts.apk"),
        mode=0o644,
    )
    install_file(
        os.path.join(parts_src, "etc", "permissions", "privapp-permissions-samuraiparts.xml"),
        os.path.join(sys_sub, "etc", "permissions", "privapp-permissions-samuraiparts.xml"),
        mode=0o644,
    )
    install_file(
        os.path.join(parts_src, "etc", "default-permissions", "default-permissions-samuraiparts.xml"),
        os.path.join(sys_sub, "etc", "default-permissions", "default-permissions-samuraiparts.xml"),
        mode=0o644,
    )
    install_file(
        os.path.join(parts_src, "etc", "udfps_icon_pressed.webp"),
        os.path.join(sys_sub, "etc", "udfps_icon_pressed.webp"),
        mode=0o644,
        uid=0,
        gid=0,
        ctx="u:object_r:system_file:s0",
    )

    if is_ios_beta:
        styler_src = os.path.join(a17_dir, "ios-styler")
        install_file(
            os.path.join(apks_dir, "IosStyler.apk"),
            os.path.join(sys_sub, "priv-app", "IosStyler", "IosStyler.apk"),
            mode=0o644,
        )
        install_file(
            os.path.join(styler_src, "privapp-permissions-styler.xml"),
            os.path.join(sys_sub, "etc", "permissions", "privapp-permissions-styler.xml"),
            mode=0o644,
        )

        # 1b-0. Inject Privileged IosSettings.apk (+ arm64 JNI .so) + 0x10 Whitelist
        ios_settings_sys_dir = os.path.join(sys_sub, "priv-app", "IosSettings")
        if os.path.isdir(ios_settings_sys_dir):
            shutil.rmtree(ios_settings_sys_dir)

        ios_settings_apk_src = os.path.join(apks_dir, "IosSettings.apk")
        ios_settings_apk_dst = os.path.join(ios_settings_sys_dir, "IosSettings.apk")
        install_file(
            ios_settings_apk_src,
            ios_settings_apk_dst,
            mode=0o644,
        )
        ensure_dir(os.path.join(ios_settings_sys_dir, "lib"), 0o755, "u:object_r:system_file:s0")
        ensure_dir(os.path.join(ios_settings_sys_dir, "lib", "arm64"), 0o755, "u:object_r:system_file:s0")
        with zipfile.ZipFile(ios_settings_apk_src, "r") as zf:
            for zn in zf.namelist():
                if zn.startswith("lib/arm64-v8a/") and zn.endswith(".so"):
                    so_bn = os.path.basename(zn)
                    install_bytes(
                        zf.read(zn),
                        os.path.join(ios_settings_sys_dir, "lib", "arm64", so_bn),
                        mode=0o644,
                        uid=0,
                        gid=0,
                        ctx="u:object_r:system_file:s0",
                    )
        set_now = time.time()
        os.utime(ios_settings_apk_dst, (set_now, set_now))
        os.utime(ios_settings_sys_dir, (set_now, set_now))
        install_file(
            os.path.join(a17_dir, "ios-settings", "privapp-permissions-ios-settings.xml"),
            os.path.join(sys_sub, "etc", "permissions", "privapp-permissions-ios-settings.xml"),
            mode=0o644,
        )

        # 1b-0a. Inject Privileged IosControlCenter.apk (+ arm64 JNI .so) + 0x10 Whitelist
        ios_cc_sys_dir = os.path.join(sys_sub, "priv-app", "IosControlCenter")
        if os.path.isdir(ios_cc_sys_dir):
            shutil.rmtree(ios_cc_sys_dir)

        ios_cc_apk_src = os.path.join(apks_dir, "IosControlCenter.apk")
        ios_cc_apk_dst = os.path.join(ios_cc_sys_dir, "IosControlCenter.apk")
        install_file(
            ios_cc_apk_src,
            ios_cc_apk_dst,
            mode=0o644,
        )
        ensure_dir(os.path.join(ios_cc_sys_dir, "lib"), 0o755, "u:object_r:system_file:s0")
        ensure_dir(os.path.join(ios_cc_sys_dir, "lib", "arm64"), 0o755, "u:object_r:system_file:s0")
        with zipfile.ZipFile(ios_cc_apk_src, "r") as zf:
            for zn in zf.namelist():
                if zn.startswith("lib/arm64-v8a/") and zn.endswith(".so"):
                    so_bn = os.path.basename(zn)
                    install_bytes(
                        zf.read(zn),
                        os.path.join(ios_cc_sys_dir, "lib", "arm64", so_bn),
                        mode=0o644,
                        uid=0,
                        gid=0,
                        ctx="u:object_r:system_file:s0",
                    )
        cc_now = time.time()
        os.utime(ios_cc_apk_dst, (cc_now, cc_now))
        os.utime(ios_cc_sys_dir, (cc_now, cc_now))
        install_file(
            os.path.join(a17_dir, "ios-controlcenter", "privapp-permissions-ios-controlcenter.xml"),
            os.path.join(sys_sub, "etc", "permissions", "privapp-permissions-ios-controlcenter.xml"),
            mode=0o644,
        )

        # 1b-1. Inject Privileged Clean-Room iOS 26 Phone (IosDialer.apk) & Messages (IosMessages.apk) + 0x10 Whitelists
        for stale_comms_dir in [
            os.path.join(sys_sub, "app", "IosDialer"),
            os.path.join(sys_sub, "app", "ICall26"),
            os.path.join(sys_sub, "app", "IosMessages"),
            os.path.join(sys_sub, "priv-app", "IosDialer"),
            os.path.join(sys_sub, "priv-app", "IosMessages"),
        ]:
            if os.path.isdir(stale_comms_dir):
                shutil.rmtree(stale_comms_dir)

        ios_dialer_sys_dir = os.path.join(sys_sub, "priv-app", "IosDialer")
        ios_dialer_apk_src = os.path.join(apks_dir, "IosDialer.apk")
        ios_dialer_apk_dst = os.path.join(ios_dialer_sys_dir, "IosDialer.apk")
        install_file(
            ios_dialer_apk_src,
            ios_dialer_apk_dst,
            mode=0o644,
        )
        dialer_now = time.time()
        os.utime(ios_dialer_apk_dst, (dialer_now, dialer_now))
        os.utime(ios_dialer_sys_dir, (dialer_now, dialer_now))
        install_file(
            os.path.join(a17_dir, "ios-dialer", "privapp-permissions-ios-dialer.xml"),
            os.path.join(sys_sub, "etc", "permissions", "privapp-permissions-ios-dialer.xml"),
            mode=0o644,
        )

        ios_msg_sys_dir = os.path.join(sys_sub, "priv-app", "IosMessages")
        ios_msg_apk_src = os.path.join(apks_dir, "IosMessages.apk")
        ios_msg_apk_dst = os.path.join(ios_msg_sys_dir, "IosMessages.apk")
        install_file(
            ios_msg_apk_src,
            ios_msg_apk_dst,
            mode=0o644,
        )
        with zipfile.ZipFile(ios_msg_apk_src, "r") as zf:
            msg_sos = [zn for zn in zf.namelist() if zn.startswith("lib/arm64-v8a/") and zn.endswith(".so")]
            if msg_sos:
                ensure_dir(os.path.join(ios_msg_sys_dir, "lib"), 0o755, "u:object_r:system_file:s0")
                ensure_dir(os.path.join(ios_msg_sys_dir, "lib", "arm64"), 0o755, "u:object_r:system_file:s0")
                for zn in msg_sos:
                    so_bn = os.path.basename(zn)
                    install_bytes(
                        zf.read(zn),
                        os.path.join(ios_msg_sys_dir, "lib", "arm64", so_bn),
                        mode=0o644,
                        uid=0,
                        gid=0,
                        ctx="u:object_r:system_file:s0",
                    )
        msg_now = time.time()
        os.utime(ios_msg_apk_dst, (msg_now, msg_now))
        os.utime(ios_msg_sys_dir, (msg_now, msg_now))
        install_file(
            os.path.join(a17_dir, "ios-messages", "privapp-permissions-ios-messages.xml"),
            os.path.join(sys_sub, "etc", "permissions", "privapp-permissions-ios-messages.xml"),
            mode=0o644,
        )
        install_file(
            os.path.join(parts_src, "etc", "default-permissions", "default-permissions-ios-comms.xml"),
            os.path.join(sys_sub, "etc", "default-permissions", "default-permissions-ios-comms.xml"),
            mode=0o644,
        )
    install_file(
        os.path.join(out_bin_dir, "samurai_hw_daemon"),
        os.path.join(sys_sub, "bin", "samurai_hw_daemon"),
        mode=0o755,
        uid=0,
        gid=0,
        ctx="u:object_r:system_file:s0",
    )
    out_root = os.path.dirname(apks_dir)
    install_file(
        os.path.join(out_root, "framework", "samurai-mod-helper.jar"),
        os.path.join(sys_sub, "framework", "samurai-mod-helper.jar"),
        mode=0o644,
        uid=0,
        gid=0,
        ctx="u:object_r:system_file:s0",
    )
    # 1b-0b. Inject Patched Android 17 services.jar (Platform Signature Bridge) & Restore Pristine Stock SystemUI.apk
    for stale_svc_oat in [
        os.path.join(sys_sub, "framework", "oat", "arm64", "services.art"),
        os.path.join(sys_sub, "framework", "oat", "arm64", "services.odex"),
        os.path.join(sys_sub, "framework", "oat", "arm64", "services.vdex"),
    ]:
        if os.path.isfile(stale_svc_oat):
            os.remove(stale_svc_oat)
    svc_src = os.path.join(out_root, "framework", "services.jar")
    if not os.path.isfile(svc_src):
        svc_src = os.path.join(repo_root, "work", "a17", "apks", "services.jar")
    if not os.path.isfile(svc_src):
        raise FileNotFoundError(f"Missing patched services.jar: {svc_src}")
    svc_dst = os.path.join(sys_sub, "framework", "services.jar")
    install_file(
        svc_src,
        svc_dst,
        mode=0o644,
        uid=0,
        gid=0,
        ctx="u:object_r:system_file:s0",
    )
    work_root_sysui = os.environ.get("WORK_ROOT", "/var/tmp/rmx1931-a17")
    stock_sysui_src = None
    for cand in [
        os.path.join(work_root_sysui, "downloads", "pixel_a17_cp3a", "SystemUI_stock.apk"),
        "/var/tmp/rmx1931-a17/downloads/pixel_a17_cp3a/SystemUI_stock.apk",
    ]:
        if os.path.isfile(cand):
            stock_sysui_src = cand
            break
    if not stock_sysui_src:
        raise FileNotFoundError("Missing pristine stock SystemUI_stock.apk to restore into system.raw.img!")
    sysui_dst_dir = os.path.join(sys_sub, "system_ext", "priv-app", "SystemUI")
    if os.path.isdir(os.path.join(sysui_dst_dir, "oat")):
        shutil.rmtree(os.path.join(sysui_dst_dir, "oat"))
    sysui_dst_apk = os.path.join(sysui_dst_dir, "SystemUI.apk")
    install_file(
        stock_sysui_src,
        sysui_dst_apk,
        mode=0o644,
    )
    svc_now = time.time()
    os.utime(svc_dst, (svc_now, svc_now))
    os.utime(sysui_dst_apk, (svc_now, svc_now))
    os.utime(sysui_dst_dir, (svc_now, svc_now))
    install_file(
        os.path.join(parts_src, "etc", "bin", "samurai-mods-sync.sh"),
        os.path.join(sys_sub, "etc", "samurai-mods-sync.sh"),
        mode=0o755,
        uid=0,
        gid=0,
        ctx="u:object_r:system_file:s0",
    )
    install_file(
        os.path.join(parts_src, "etc", "bin", "samurai-gcam-sync.sh"),
        os.path.join(sys_sub, "etc", "samurai-gcam-sync.sh"),
        mode=0o755,
        uid=0,
        gid=0,
        ctx="u:object_r:system_file:s0",
    )
    for rc_fn in ["samurai_parts.rc", "init.samurai.perf.rc", "init.samurai.bootguard.rc"]:
        install_file(
            os.path.join(parts_src, "etc", "init", rc_fn),
            os.path.join(sys_sub, "etc", "init", rc_fn),
            mode=0o644,
        )
    for kl_fn in ["touchpanel_kpd.kl", "touchpanel.kl", "samurai-virtual-keys.kl"]:
        install_file(
            os.path.join(parts_src, "etc", "keylayout", kl_fn),
            os.path.join(sys_sub, "usr", "keylayout", kl_fn),
            mode=0o644,
        )
    vintf_dev_matrix_src = os.path.join(parts_src, "etc", "vintf", "compatibility_matrix.device.xml")
    if os.path.isfile(vintf_dev_matrix_src):
        install_file(
            vintf_dev_matrix_src,
            os.path.join(sys_sub, "etc", "vintf", "compatibility_matrix.device.xml"),
            mode=0o644,
            uid=0,
            gid=0,
            ctx="u:object_r:system_file:s0",
        )

    # 1b-2. Inject Offline Unlimited Google Photos DEX + Cloud App-Mods Manifest, & strip stale stock oat/
    work_root = os.environ.get("WORK_ROOT", "/var/tmp/rmx1931-a17")
    photos_dex_src = None
    for cand in [
        os.path.join(work_root, "photos-gsi-dex.zip"),
        "/var/tmp/rmx1931-a17-adblock/photos-gsi-dex.zip",
    ]:
        if os.path.isfile(cand):
            photos_dex_src = cand
            break
    if not photos_dex_src:
        raise RuntimeError("Missing photos-gsi-dex.zip for offline Unlimited Google Photos!")
    install_file(
        photos_dex_src,
        os.path.join(sys_sub, "etc", "samurai-mods", "photos-gsi-dex.zip"),
        mode=0o644,
        uid=0,
        gid=0,
        ctx="u:object_r:system_file:s0",
    )
    install_file(
        os.path.join(parts_src, "etc", "sysconfig", "pixel_2016_exclusive.xml"),
        os.path.join(sys_sub, "product", "etc", "sysconfig", "pixel_2016_exclusive.xml"),
        mode=0o644,
        uid=0,
        gid=0,
        ctx="u:object_r:system_file:s0",
    )
    install_bytes(
        b"\x00\x00\x80\xd2",
        os.path.join(sys_sub, "etc", "samurai-mods", "gcastartup_uaf_fix.bin"),
        mode=0o644,
        uid=0,
        gid=0,
        ctx="u:object_r:system_file:s0",
    )
    for stale_oat in [
        os.path.join(sys_sub, "product", "app", "Photos", "oat"),
        os.path.join(sys_sub, "product", "app", "YouTube", "oat"),
        os.path.join(sys_sub, "product", "app", "YouTubeMusicPrebuilt", "oat"),
    ]:
        if os.path.isdir(stale_oat):
            shutil.rmtree(stale_oat)

    # 1c. Replace AOSP Launcher3QuickStep & ThemePicker with Official Retail Google Pixel Launche
    #     (NexusLauncherRelease), Pixel Wallpaper & Style (WallpaperPickerGoogleRelease) & PixelThemesStub
    pixel_dl = os.path.join(work_root, "downloads", "pixel_a17_cp3a")
    if not os.path.isdir(pixel_dl):
        pixel_dl = "/var/tmp/rmx1931-a17/downloads/pixel_a17_cp3a"
    for legacy_p in [
        os.path.join(sys_sub, "system_ext", "priv-app", "Launcher3QuickStep"),
        os.path.join(sys_sub, "system_ext", "priv-app", "ThemePicker"),
        os.path.join(sys_sub, "system_ext", "etc", "permissions", "com.android.launcher3.xml"),
        os.path.join(sys_sub, "system_ext", "priv-app", "NexusLauncherRelease", "oat"),
    ]:
        if os.path.isdir(legacy_p):
            shutil.rmtree(legacy_p)
        elif os.path.isfile(legacy_p):
            os.remove(legacy_p)

    nx_dst_dir = os.path.join(sys_sub, "system_ext", "priv-app", "NexusLauncherRelease")
    nx_dst_apk = os.path.join(nx_dst_dir, "NexusLauncherRelease.apk")
    install_file(
        os.path.join(apks_dir, "NexusLauncherRelease.apk"),
        nx_dst_apk,
        mode=0o644,
    )
    now_ts = time.time()
    os.utime(nx_dst_apk, (now_ts, now_ts))
    os.utime(nx_dst_dir, (now_ts, now_ts))
    install_file(
        os.path.join(pixel_dl, "WallpaperPickerGoogleRelease.apk"),
        os.path.join(sys_sub, "system_ext", "priv-app", "WallpaperPickerGoogleRelease", "WallpaperPickerGoogleRelease.apk"),
        mode=0o644,
    )
    install_file(
        os.path.join(pixel_dl, "PixelThemesStub2022_and_newer.apk"),
        os.path.join(sys_sub, "product", "app", "PixelThemesStub2022_and_newer", "PixelThemesStub2022_and_newer.apk"),
        mode=0o644,
    )
    install_file(
        os.path.join(parts_src, "etc", "permissions", "privapp-permissions-pixel-launcher.xml"),
        os.path.join(sys_sub, "system_ext", "etc", "permissions", "privapp-permissions-pixel-launcher.xml"),
        mode=0o644,
    )
    install_file(
        os.path.join(parts_src, "etc", "default-permissions", "default-permissions-pixel-launcher.xml"),
        os.path.join(sys_sub, "etc", "default-permissions", "default-permissions-pixel-launcher.xml"),
        mode=0o644,
    )
    if is_ios_beta:
        install_file(
            os.path.join(parts_src, "etc", "sysconfig", "pixel-contextual-search.xml"),
            os.path.join(sys_sub, "etc", "sysconfig", "pixel-contextual-search.xml"),
            mode=0o644,
        )
    else:
        clean_sysconfig = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<config>\n'
            '    <feature name="com.google.android.feature.CONTEXTUAL_SEARCH" />\n'
            '    <allow-in-power-save package="com.realme.ota" />\n'
            '    <allow-in-data-usage-save package="com.realme.ota" />\n'
            '    <allow-in-power-save package="com.realme.samurai.parts" />\n'
            '    <allow-in-data-usage-save package="com.realme.samurai.parts" />\n'
            '</config>\n'
        )
        install_bytes(
            clean_sysconfig.encode("utf-8"),
            os.path.join(sys_sub, "etc", "sysconfig", "pixel-contextual-search.xml"),
            mode=0o644,
            uid=0,
            gid=0,
            ctx="u:object_r:system_file:s0",
        )

    # 1d. Create /system/product/app/GoogleCameraEng mountpoint (bound from /vendor/gcam on post-fs)
    #     + install default runtime permissions for Pixel Google Camera 9.6 (BSG V53)
    gcam_mp = os.path.join(sys_sub, "product", "app", "GoogleCameraEng")
    if os.path.isdir(gcam_mp):
        shutil.rmtree(gcam_mp)
    ensure_dir(gcam_mp, 0o755, "u:object_r:system_file:s0")
    install_file(
        os.path.join(parts_src, "etc", "default-permissions", "default-permissions-gcam.xml"),
        os.path.join(sys_sub, "etc", "default-permissions", "default-permissions-gcam.xml"),
        mode=0o644,
    )

    # 2. Inject Flavor-Appropriate High-Priority Static & Dynamic RRO Overlays into /system/system_ext/overlay/
    sysext_overlay = os.path.join(sys_sub, "system_ext", "overlay")
    rro_list = [
        "SamuraiHardwareOverlay.apk",
        "SamuraiTelephonyOverlay.apk",
        "SamuraiSystemUIOverlay.apk",
        "SamuraiSettingsOverlay.apk",
        "SamuraiHidePillOverlay.apk",
    ]
    if is_ios_beta:
        rro_list.extend([
            "SamuraiDialerOverlay.apk",
            "SamuraiMessagesOverlay.apk",
            "SamuraiGmailOverlay.apk",
            "SamuraiPhotosOverlay.apk",
            "SamuraiLauncherOverlay.apk",
            "SamuraiIos26WallpaperOverlay.apk",
        ])
    for rro_apk in rro_list:
        install_file(
            os.path.join(apks_dir, rro_apk),
            os.path.join(sysext_overlay, rro_apk),
            mode=0o644,
        )

    # 3. Extract & binary-patch 4.14 mainline tethering BPF + netd + connectivity suite + zygote_next + platform bpfloader_wrap
    build_and_inject_414_bpf_netd(sys_sub, repo_root)
    patch_zygote_next_for_kernel_414(sys_sub)
    orig_bpfloader = os.path.join(sys_sub, "bin", "bpfloader")
    real_bpfloader = os.path.join(sys_sub, "bin", "bpfloader.real")
    if not os.path.isfile(real_bpfloader) and os.path.isfile(orig_bpfloader):
        shutil.move(orig_bpfloader, real_bpfloader)
        os.chmod(real_bpfloader, 0o755)
        os.chown(real_bpfloader, 0, 2000)
        set_selinux(real_bpfloader, "u:object_r:bpfloader_platform_exec:s0")
    install_file(
        os.path.join(out_bin_dir, "bpfloader_wrap"),
        orig_bpfloader,
        mode=0o755,
        uid=0,
        gid=2000,
        ctx="u:object_r:bpfloader_platform_exec:s0",
    )

    # 4. Quarantine 5.8+/5.15+ platform BPF ringbuf/iter objects on 4.14
    quarantine_515_platform_bpf(sys_sub)

    # 5. Strip 'critical' from mmd.rc & install clean USB ADB init rc
    patch_mmd_and_adb_rc(sys_sub)

    # 6. Patch plat_file_contexts & plat_sepolicy.cil for /dev/ion + BPF + ota_package_file
    patch_platform_selinux(sys_sub)

    # 6b. Inject QTI IMS/VoLTE/ViLTE/VoWiFi/SMS/DSDS/DPM stack, RMX1931 CarrierConfigRes.apk & Google Dialer support
    inject_qti_ims_and_dialer_support(
        sys_sub=sys_sub,
        a17_dir=a17_dir,
        out_bin_dir=out_bin_dir,
        out_fwk_dir=os.path.join(out_root, "framework"),
        work_root=work_root,
    )

    # 7. Configure Clean Pixel OS, 90Hz SurfaceFlinger VSync, 8-core dex2oat, BootGuard & Repo OTA Props
    build_ts = int(float(os.environ.get("OTA_BUILD_DATETIME", str(int(time.time())))))
    dt_str = datetime.fromtimestamp(build_ts, tz=timezone.utc).strftime("%Y%m%d-%H%M")
    ota_repo = os.environ.get("OTA_GITHUB_REPO", "nithinyadavamaraboina-online/PixelOS-Android17-RMX1931")
    default_tag = f"ios-beta-{dt_str}" if is_ios_beta else f"pixelos-stable-{dt_str}"
    ota_release_tag = os.environ.get("OTA_RELEASE_TAG", default_tag)
    ota_version = os.environ.get("OTA_VERSION", "v2.1.9-b1" if is_ios_beta else "v2.1.9")
    if is_ios_beta:
        ota_manifest_url = f"https://raw.githubusercontent.com/{ota_repo}/main/ota/ios-beta/rmx1931.json"
    else:
        ota_manifest_url = f"https://raw.githubusercontent.com/{ota_repo}/main/ota/stable/rmx1931.json"

    mods_hashes_path = None
    for cand in [
        os.path.join(work_root, "mods_release", "mods-hashes.json"),
        "/var/tmp/rmx1931-a17-adblock/mods_release/mods-hashes.json",
    ]:
        if os.path.isfile(cand):
            mods_hashes_path = cand
            break
    if not mods_hashes_path:
        raise RuntimeError("Missing mods_release/mods-hashes.json for release-mods.json!")
    with open(mods_hashes_path, "r", encoding="utf-8") as f:
        raw_mods_meta = json.load(f)
    mods_meta = {k: v for k, v in raw_mods_meta.items() if k in ("cromite",)}
    for k, entry in mods_meta.items():
        if entry.get("direct_url"):
            entry["url"] = entry["direct_url"]
        else:
            asset_fn = entry.get("asset_name", entry["filename"])
            entry["url"] = f"https://github.com/{ota_repo}/releases/download/{ota_release_tag}/{asset_fn}"
    install_bytes(
        (json.dumps(mods_meta, indent=2) + "\n").encode("utf-8"),
        os.path.join(sys_sub, "etc", "samurai-mods", "release-mods.json"),
        mode=0o644,
        uid=0,
        gid=0,
        ctx="u:object_r:system_file:s0",
    )

    aux_cams = (
        "org.codeaurora.snapcam,com.android.camera,com.android.camera2,"
        "com.google.android.GoogleCamera,com.google.android.GoogleCameraEng,app.grapheneos.camera"
    )

    caiman_fp = "google/caiman/caiman:17/CP3A.260905.010/16176746:user/release-keys"
    caiman_desc = "caiman-user 17 CP3A.260905.010 16176746 release-keys"

    tuning_props = {
        "ro.debuggable": "1",
        "ro.adb.secure": "0",
        "ro.secure": "0",
        "ro.build.type": "user",
        "ro.build.tags": "release-keys",
        "ro.build.flavor": "caiman-user",
        "ro.build.description": caiman_desc,
        "ro.build.fingerprint": caiman_fp,
        "ro.system.build.fingerprint": caiman_fp,
        "persist.sys.usb.config": "mtp,adb",
        "persist.sys.disable_rescue": "true",
        "persist.zygote.zygote_next.start_on_boot": "true",
        "ro.surface_flinger.supports_background_blur": "1",
        "ro.surface_flinger.blur_disabled_by_default": "true",
        "persist.sys.sf.disable_blurs": "0" if is_ios_beta else "1",
        "ro.sf.blurs_are_expensive": "1",
        "debug.sf.disable_client_composition_cache": "0",
        "ro.sf.lcd_density": "420",
        "ro.surface_flinger.use_content_detection_for_refresh_rate": "false",
        "ro.surface_flinger.set_idle_timer_ms": "0",
        "ro.surface_flinger.set_touch_timer_ms": "4000",
        "ro.surface_flinger.set_display_power_timer_ms": "1000",
        "ro.surface_flinger.max_frame_buffer_acquired_buffers": "3",
        "debug.sf.auto_latch_unsignaled": "0",
        "debug.sf.latch_unsignaled": "1",
        "debug.sf.enable_gl_backpressure": "0",
        "debug.sf.enable_hwc_vds": "1",
        "debug.sf.predict_hwc_composition_strategy": "0",
        "media.stagefright.thumbnail.prefer_hw_codecs": "true",
        "dalvik.vm.systemuicompilerfilter": "speed",
        "dalvik.vm.dex2oat-threads": "4",
        "dalvik.vm.background-dex2oat-threads": "3",
        "dalvik.vm.background-dex2oat-cpu-set": "0,1,2,3,4",
        "dalvik.vm.boot-dex2oat-threads": "8",
        "dalvik.vm.image-dex2oat-threads": "8",
        "pm.dexopt.install": "speed-profile",
        "pm.dexopt.bg-dexopt": "speed-profile",
        "ro.config.media_vol_steps": "25",
        "ro.config.vc_call_vol_steps": "7",
        "vendor.camera.aux.packagelist": aux_cams,
        "persist.vendor.camera.privapp.list": aux_cams,
        "ro.build.product": "RMX1931",
        "ro.product.brand": "realme",
        "qcom.bluetooth.soc": "cherokee",
        "vendor.bluetooth.soc": "cherokee",
        "ro.bluetooth.library_name": "libbluetooth.so",
        "persist.bluetooth.bluetooth_audio_hal.disabled": "false",
        "persist.vendor.btstack.enable.splita2dp": "true",
        "persist.vendor.btstack.a2dp_offload_cap": "sbc-aptx-aptxtws-aptxhd-aac-ldac",
        "ro.audio.monitorRotation": "true",
        "ril.subscription.types": "NV,RUIM",
        "ro.telephony.default_network": "22,22",
        "telephony.lteOnCdmaDevice": "1",
        "persist.dbg.volte_avail_ovr": "1",
        "persist.dbg.vt_avail_ovr": "1",
        "persist.dbg.wfc_avail_ovr": "1",
        "persist.data.df.dev_name": "rmnet_usb0",
        "persist.sys.phh.fod.oppo": "true",
        "persist.sys.phh.mainkeys": "0",
        "persist.vendor.fingerprint.sensor_type": "optical",
        "persist.vendor.fingerprint.optical.support": "1",
        "persist.vendor.fingerprint.optical.iconlocation": "244",
        "persist.vendor.fingerprint.optical.iconsize": "190",
        "persist.vendor.fingerprint.fp_id": "G_OPTICAL_G3",
        "persist.vendor.fingerprint.version": "Goodix",
        "persist.vendor.qfp": "true",
        "vendor.flash.locked": "1",
        "ro.ota.device": "RMX1931",
        "ro.ota.channel": ota_channel,
        "ro.ota.repo": ota_repo,
        "ro.ota.manifest_url": ota_manifest_url,
        "ro.ota.version": ota_version,
        "ro.samurai.ota.version": ota_version,
        "ro.pixelos.display.version": ota_version,
        "ro.ota.datetime": str(build_ts),
        "ro.product.system.brand": "realme",
        "ro.product.system.manufacturer": "realme",
        "ro.product.system.model": "Realme X2 Pro",
        "ro.product.system.device": "RMX1931L1",
        "ro.product.system.name": "RMX1931",
        **QTI_SYSTEM_BUILD_PROPS,
    }
    patch_build_prop(
        os.path.join(sys_sub, "build.prop"),
        tuning_props,
        remove_prefixes=(
            "ro.liquidglass.",
            "persist.sys.liquidglass.",
            "debug.sf.early_",
            "debug.sf.high_fps_",
            "debug.renderengine.backend",
            "debug.hwui.renderer",
        ),
    )

    patch_build_prop(os.path.join(sys_sub, "product", "etc", "build.prop"), {
        "ro.product.product.brand": "realme",
        "ro.product.product.manufacturer": "realme",
        "ro.product.product.model": "Realme X2 Pro",
        "ro.product.product.device": "RMX1931L1",
        "ro.product.product.name": "RMX1931",
        "ro.product.Product_model": "Realme X2 Pro",
        "ro.product.build.type": "user",
        "ro.product.build.tags": "release-keys",
        "ro.product.build.fingerprint": caiman_fp,
        "ro.build.flavor": "caiman-user",
        "ro.build.description": caiman_desc,
        "ro.opa.eligible_device": "true",
        "ro.com.google.ime.theme_id": "5",
        "ro.lmk.kill_heaviest_task": "true",
        "ro.lmk.kill_timeout_ms": "15",
        "bluetooth.profile.a2dp.source.enabled": "true",
        "bluetooth.profile.avrcp.target.enabled": "true",
        "bluetooth.profile.gatt.enabled": "true",
        "bluetooth.profile.hfp.ag.enabled": "true",
        "bluetooth.profile.hid.device.enabled": "true",
        "bluetooth.profile.hid.host.enabled": "true",
        "bluetooth.profile.map.server.enabled": "true",
        "bluetooth.profile.opp.enabled": "true",
        "bluetooth.profile.pan.nap.enabled": "true",
        "bluetooth.profile.pan.panu.enabled": "true",
        "bluetooth.profile.pbap.server.enabled": "true",
    })

    patch_build_prop(os.path.join(sys_sub, "system_ext", "etc", "build.prop"), {
        "ro.product.system_ext.brand": "realme",
        "ro.product.system_ext.manufacturer": "realme",
        "ro.product.system_ext.model": "Realme X2 Pro",
        "ro.product.system_ext.device": "RMX1931L1",
        "ro.product.system_ext.name": "RMX1931",
        "ro.system_ext.build.type": "user",
        "ro.system_ext.build.tags": "release-keys",
        "ro.system_ext.build.fingerprint": caiman_fp,
        "persist.arm64.memtag.app.com.google.android.bluetooth": "off",
        **QTI_SYSTEM_EXT_BUILD_PROPS,
    })

    # 8. Audit Pixel GApps & write build manifest
    gapps = scan_gapps(sys_sub)
    sys_props = {}
    with open(os.path.join(sys_sub, "build.prop"), "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if "=" in line and not line.startswith("#"):
                k, v = line.strip().split("=", 1)
                if k in (
                    "ro.system.build.version.release",
                    "ro.system.build.version.sdk",
                    "ro.system.build.id",
                    "ro.build.version.security_patch",
                    "ro.system.build.fingerprint",
                    "ro.ota.channel",
                    "ro.ota.version",
                    "ro.samurai.ota.version",
                    "ro.pixelos.display.version",
                    "ro.ota.datetime",
                    "ro.ota.repo",
                    "ro.ota.manifest_url",
                ):
                    sys_props[k] = v

    injected_items = [
        "/system/framework/services.jar",
        "/system/system_ext/priv-app/SystemUI/SystemUI.apk",
        "/system/priv-app/SamuraiOTAUpdater/SamuraiOTAUpdater.apk",
        "/system/etc/permissions/privapp-permissions-ota.xml",
        "/system/etc/default-permissions/default-permissions-ota.xml",
        "/system/etc/init/ota.rc",
        "/system/etc/ota-watcher.sh",
        "/system/etc/ota-post-fs.sh",
        "/system/etc/pixel-boot.sh",
        "/system/bin/samurai_ota_bcb",
        "/system/priv-app/SamuraiParts/SamuraiParts.apk",
        "/system/etc/permissions/privapp-permissions-samuraiparts.xml",
        "/system/etc/default-permissions/default-permissions-samuraiparts.xml",
        "/system/etc/udfps_icon_pressed.webp",
        "/system/bin/samurai_hw_daemon",
        "/system/framework/samurai-mod-helper.jar",
        "/system/etc/samurai-mods-sync.sh",
        "/system/etc/samurai-gcam-sync.sh",
        "/system/etc/samurai-mods/photos-gsi-dex.zip",
        "/system/product/etc/sysconfig/pixel_2016_exclusive.xml",
        "/system/etc/samurai-mods/gcastartup_uaf_fix.bin",
        "/system/etc/samurai-mods/release-mods.json",
        "/system/etc/init/samurai_parts.rc",
        "/system/etc/init/init.samurai.perf.rc",
        "/system/etc/init/init.samurai.bootguard.rc",
        "/system/usr/keylayout/touchpanel_kpd.kl",
        "/system/usr/keylayout/touchpanel.kl",
        "/system/usr/keylayout/samurai-virtual-keys.kl",
        "/system/etc/vintf/compatibility_matrix.device.xml",
        "/system/system_ext/priv-app/NexusLauncherRelease/NexusLauncherRelease.apk",
        "/system/system_ext/priv-app/ims/ims.apk",
        "/system/system_ext/priv-app/qcrilmsgtunnel/qcrilmsgtunnel.apk",
        "/system/system_ext/priv-app/dpmserviceapp/dpmserviceapp.apk",
        "/system/system_ext/app/QtiTelephonyService/QtiTelephonyService.apk",
        "/system/system_ext/app/ImsRcsService/ImsRcsService.apk",
        "/system/system_ext/bin/dpmd",
        "/system/system_ext/lib64/libgui_shim.so",
        "/system/product/overlay/CarrierConfigRes.apk",
        "/system/product/framework/com.google.android.dialer.support.jar",
        "/system/product/etc/permissions/com.google.android.dialer.support.xml",
    ] + [f"/system/system_ext/overlay/{rro}" for rro in rro_list]
    if is_ios_beta:
        injected_items.extend([
            "/system/priv-app/IosStyler/IosStyler.apk",
            "/system/etc/permissions/privapp-permissions-styler.xml",
            "/system/priv-app/IosSettings/IosSettings.apk",
            "/system/etc/permissions/privapp-permissions-ios-settings.xml",
            "/system/priv-app/IosControlCenter/IosControlCenter.apk",
            "/system/etc/permissions/privapp-permissions-ios-controlcenter.xml",
            "/system/priv-app/IosDialer/IosDialer.apk",
            "/system/etc/permissions/privapp-permissions-ios-dialer.xml",
            "/system/priv-app/IosMessages/IosMessages.apk",
            "/system/etc/permissions/privapp-permissions-ios-messages.xml",
            "/system/etc/default-permissions/default-permissions-ios-comms.xml",
        ])

    flavor_desc = (
        f"Android 17 PixelOS {ota_version} — Opt-In iOS Style UI Beta (90Hz Liquid Glass Dock + iOS 26 Lock/Shade/CC + IosSettings + IosDialer + IosMessages + IosStyler)"
        if is_ios_beta
        else f"Android 17 PixelOS {ota_version} — Pure Retail Google PixelOS Stable (Stock Pixel Launcher + Stock SystemUI + Google Phone/Messages + GCam 9.6 + Opt-In iOS Beta Toggle)"
    )

    report = {
        "rom_flavor": flavor_desc,
        "flavor_id": rom_flavor,
        "android_system_props": sys_props,
        "ota_metadata": {
            "device": "RMX1931",
            "channel": ota_channel,
            "version": ota_version,
            "datetime": build_ts,
            "repo": ota_repo,
            "manifest_url": ota_manifest_url,
        },
        "gapps_components_found": gapps,
        "injected_pixel_os_and_ota": injected_items,
        "kernel_414_adaptations": [
            "/system/bin/netbpfload_414",
            "/system/etc/bpf_netd_414.o",
            "/system/lib64/libnetd_updatable_414.so",
            "/system/lib64/libservice-connectivity_414.so",
            "/system/lib64/libmeminfo.so (bpf_map_ro_abort_fallback)",
            "/system/lib64/libzygote_sys.dylib.so (zygote_next_clone3_to_fork)",
            "/system/lib64/libcap_rust.dylib.so (cap_rust_cap_last_cap_37)",
            "/system/lib64/libzygote_species_android_native.so (statsd_fd_registry_guard)",
            "/system/etc/init/netbpfload.rc",
            "/system/etc/init/init.rmx.adb.rc",
            "/system/etc/bpf.disabled_515",
            "/system/etc/selinux/plat_file_contexts (/dev/ion + netbpfload_414 + samurai_hw_daemon + adblock/hosts)",
            "/system/etc/selinux/plat_sepolicy.cil (ion_device + BPF mounton + ota_package_file)",
        ],
    }
    with open(manifest_out, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
    print(f"[system-inject] Verified {len(gapps)} Pixel/GMS packages & injected Clean Pixel OS + SamuraiParts + Repo OTA + 4.14 BPF suite.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
