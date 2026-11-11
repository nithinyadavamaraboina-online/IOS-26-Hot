#!/usr/bin/env bash
# Dual-Channel Cumulative / Bridge Reset OTA Package Builder (< 380 MiB /cache safe limit)
# - Default (ROM_FLAVOR=pixelos):
#     Builds RMX1931-Android17-PixelOS-OTA.zip + ota/rmx1931.json (Pure PixelOS v2.1.0 Stable Bridge Reset
#     for existing v1.0-v1.7.0 / v2.0.0 installs so they transition wirelessly to 100% Pure Google PixelOS
#     and receive the upgraded SamuraiOTAUpdater + /data/ota_raw atomic block-OTA engine).
# - Optional (ROM_FLAVOR=ios-beta):
#     Builds cumulative < 380 MiB iOS Style UI Beta hotfix OTA if needed.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
A17_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${A17_ROOT}/.." && pwd)"

export WORK_ROOT="${WORK_ROOT:-/var/tmp/rmx1931-a17}"
export ROM_FLAVOR="${ROM_FLAVOR:-pixelos}"
DL_DIR="${WORK_ROOT}/downloads"
APKS_DIR="${WORK_ROOT}/out/apks"
BIN_DIR="${WORK_ROOT}/out/bin"
IMG_OUT="${WORK_ROOT}/out/images"
WIN_OUT="${REPO_ROOT}/work/a17/out"
INC_STAGE="${WORK_ROOT}/work/ota_incremental"

export OTA_GITHUB_REPO="${OTA_GITHUB_REPO:-nithinyadavamaraboina-online/PixelOS-Android17-RMX1931}"
if [ -z "${OTA_BUILD_DATETIME:-}" ] && [ -f "${WORK_ROOT}/out/BUILD-MANIFEST.json" ]; then
    OTA_BUILD_DATETIME="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["ota_metadata"]["datetime"])' "${WORK_ROOT}/out/BUILD-MANIFEST.json" 2>/dev/null || date -u +%s)"
fi
export OTA_BUILD_DATETIME="${OTA_BUILD_DATETIME:-$(date -u +%s)}"

OTA_FULL_CUMULATIVE="${OTA_FULL_CUMULATIVE:-0}"
for arg in "$@"; do
    case "${arg}" in
        --full-cumulative|--with-gcam) OTA_FULL_CUMULATIVE=1 ;;
    esac
done

if [ "${ROM_FLAVOR}" = "ios-beta" ]; then
    OTA_CHANNEL="ios_beta"
    OTA_VERSION="${OTA_VERSION:-v2.1.8-b1}"
    OTA_RELEASE_TAG="${OTA_RELEASE_TAG:-ios-beta-${OTA_VERSION}}"
    ROM_FILENAME="${ROM_FILENAME:-RMX1931-Android17-iOSBeta-OTA-${OTA_VERSION}.zip}"
    MANIFEST_OUT="${MANIFEST_OUT:-${REPO_ROOT}/ota/ios-beta/rmx1931.json}"
    OTA_MANIFEST_PROP_URL="https://raw.githubusercontent.com/${OTA_GITHUB_REPO}/main/ota/ios-beta/rmx1931.json"
elif [ "${ROM_FLAVOR}" = "pixelos" ]; then
    OTA_CHANNEL="stable"
    OTA_VERSION="${OTA_VERSION:-v2.1.8}"
    OTA_RELEASE_TAG="${OTA_RELEASE_TAG:-pixelos-stable-${OTA_VERSION}}"
    ROM_FILENAME="${ROM_FILENAME:-RMX1931-Android17-PixelOS-OTA.zip}"
    MANIFEST_OUT="${MANIFEST_OUT:-${REPO_ROOT}/ota/rmx1931.json}"
    OTA_MANIFEST_PROP_URL="https://raw.githubusercontent.com/${OTA_GITHUB_REPO}/main/ota/stable/rmx1931.json"
else
    echo "ERROR: Unsupported ROM_FLAVOR='${ROM_FLAVOR}' (expected 'pixelos' or 'ios-beta')" >&2
    exit 1
fi
export OTA_VERSION OTA_RELEASE_TAG

mkdir -p "${WIN_OUT}" "$(dirname "${MANIFEST_OUT}")"

# 1. Verify EDIFY update-binary supports package_extract_file
UPD_BIN="${WORK_ROOT}/work/stage/donor/META-INF/com/google/android/update-binary"
if [ ! -f "${UPD_BIN}" ]; then
    mkdir -p "${WORK_ROOT}/work/stage/donor"
    DONOR_ZIP="${DL_DIR}/samurai-donor-a16.zip"
    [ -f "${DONOR_ZIP}" ] || DONOR_ZIP="/var/tmp/rmx1931-a17/downloads/samurai-donor-a16.zip"
    unzip -q -o "${DONOR_ZIP}" "META-INF/*" -d "${WORK_ROOT}/work/stage/donor"
fi
grep -a -q "package_extract_file" "${UPD_BIN}" || { echo "ERROR: update-binary missing package_extract_file"; exit 1; }

# 2. Extract patched binaries/configs directly from built system.raw.img, vendor.raw.img & odm.raw.img for 1:1 parity
SYS_MNT="${WORK_ROOT}/mnt/inc_sys"
VEN_MNT="${WORK_ROOT}/mnt/inc_ven"
ODM_MNT="${WORK_ROOT}/mnt/inc_odm"
cleanup_inc_mounts() {
    for mp in "${SYS_MNT}" "${VEN_MNT}" "${ODM_MNT}"; do
        if [ -d "${mp}" ] && mountpoint -q "${mp}" 2>/dev/null; then
            umount -lf "${mp}" || true
        fi
    done
}
trap cleanup_inc_mounts EXIT
cleanup_inc_mounts
mkdir -p "${SYS_MNT}" "${VEN_MNT}" "${ODM_MNT}"
mount -o loop,ro "${IMG_OUT}/system.raw.img" "${SYS_MNT}"
mount -o loop,ro "${IMG_OUT}/vendor.raw.img" "${VEN_MNT}"
mount -o loop,ro "${IMG_OUT}/odm.raw.img" "${ODM_MNT}"
SYS_SUB="${SYS_MNT}/system"
[ -d "${SYS_SUB}/lib64" ] || SYS_SUB="${SYS_MNT}"
rm -rf "${INC_STAGE}"
mkdir -p "${INC_STAGE}/META-INF/com/google/android" "${INC_STAGE}/ota_payload"
cp -f "${SYS_SUB}/bin/netbpfload_414" "${INC_STAGE}/ota_payload/netbpfload_414"
cp -f "${SYS_SUB}/etc/bpf_netd_414.o" "${INC_STAGE}/ota_payload/bpf_netd_414.o"
cp -f "${SYS_SUB}/lib64/libnetd_updatable_414.so" "${INC_STAGE}/ota_payload/libnetd_updatable_414.so"
cp -f "${SYS_SUB}/lib64/libservice-connectivity_414.so" "${INC_STAGE}/ota_payload/libservice-connectivity_414.so"
cp -f "${SYS_SUB}/etc/init/netbpfload.rc" "${INC_STAGE}/ota_payload/netbpfload.rc"
cp -f "${SYS_SUB}/etc/init/zygote_next64.rc" "${INC_STAGE}/ota_payload/zygote_next64.rc"
cp -f "${SYS_SUB}/lib64/libmeminfo.so" "${INC_STAGE}/ota_payload/libmeminfo.so"
cp -f "${SYS_SUB}/lib64/libzygote_sys.dylib.so" "${INC_STAGE}/ota_payload/libzygote_sys.dylib.so"
cp -f "${SYS_SUB}/lib64/libcap_rust.dylib.so" "${INC_STAGE}/ota_payload/libcap_rust.dylib.so"
cp -f "${SYS_SUB}/lib64/libzygote_species_android_native.so" "${INC_STAGE}/ota_payload/libzygote_species_android_native.so"
cp -f "${SYS_SUB}/etc/selinux/plat_sepolicy.cil" "${INC_STAGE}/ota_payload/plat_sepolicy.cil"
cp -f "${SYS_SUB}/etc/selinux/plat_file_contexts" "${INC_STAGE}/ota_payload/plat_file_contexts"
cp -f "${SYS_SUB}/etc/selinux/plat_property_contexts" "${INC_STAGE}/ota_payload/plat_property_contexts"
cp -f "${SYS_SUB}/etc/vintf/compatibility_matrix.device.xml" "${INC_STAGE}/ota_payload/compatibility_matrix.device.xml"
cp -f "${SYS_SUB}/framework/samurai-mod-helper.jar" "${INC_STAGE}/ota_payload/samurai-mod-helper.jar"
cp -f "${SYS_SUB}/framework/services.jar" "${INC_STAGE}/ota_payload/services.jar"
cp -f "${SYS_SUB}/etc/samurai-mods-sync.sh" "${INC_STAGE}/ota_payload/samurai-mods-sync.sh"
cp -f "${SYS_SUB}/etc/samurai-gcam-sync.sh" "${INC_STAGE}/ota_payload/samurai-gcam-sync.sh"
cp -f "${SYS_SUB}/etc/sysconfig/pixel-contextual-search.xml" "${INC_STAGE}/ota_payload/pixel-contextual-search.xml"
if [ "${OTA_FULL_CUMULATIVE}" = "1" ]; then
    cp -f "${SYS_SUB}/etc/samurai-mods/photos-gsi-dex.zip" "${INC_STAGE}/ota_payload/photos-gsi-dex.zip"
fi
cp -f "${SYS_SUB}/etc/samurai-mods/release-mods.json" "${INC_STAGE}/ota_payload/release-mods.json"
cp -f "${SYS_SUB}/etc/udfps_icon_pressed.webp" "${INC_STAGE}/ota_payload/udfps_icon_pressed.webp"
cp -f "${VEN_MNT}/lib64/hw/com.qti.chi.override.so" "${INC_STAGE}/ota_payload/com.qti.chi.override.so"
cp -f "${VEN_MNT}/lib64/libfod_zpos_hook.so" "${INC_STAGE}/ota_payload/libfod_zpos_hook.so"
cp -f "${VEN_MNT}/etc/init/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc" "${INC_STAGE}/ota_payload/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc"
cp -f "${VEN_MNT}/etc/init/android.hardware.graphics.composer@2.4-service.rc" "${INC_STAGE}/ota_payload/android.hardware.graphics.composer@2.4-service.rc"
cp -f "${VEN_MNT}/etc/init/vendor.qti.adsprpc-service.rc" "${INC_STAGE}/ota_payload/vendor.qti.adsprpc-service.rc"
cp -f "${ODM_MNT}/etc/init/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc" "${INC_STAGE}/ota_payload/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc"
cp -f "${ODM_MNT}/lib64/libgf_hal_G3.so" "${INC_STAGE}/ota_payload/libgf_hal_G3.so"
cp -f "${VEN_MNT}/etc/selinux/vendor_sepolicy.cil" "${INC_STAGE}/ota_payload/vendor_sepolicy.cil"
cp -f "${VEN_MNT}/etc/selinux/vendor_property_contexts" "${INC_STAGE}/ota_payload/vendor_property_contexts"
for vcfg in fstab.qcom media_codecs.xml media_codecs_c2.xml media_codecs_performance_c2.xml media_codecs_performance.xml powerhint.json; do
    cp -f "${VEN_MNT}/etc/${vcfg}" "${INC_STAGE}/ota_payload/${vcfg}"
done
python3 - "${SCRIPT_DIR}" "${SYS_SUB}" "${INC_STAGE}/ota_payload/qti_ims_telephony.tar.gz" "${INC_STAGE}/ota_payload/qti_ims_fixperms.sh" <<'QTIPACKPY'
import os, stat, sys, tarfile
script_dir, sys_sub, out_tgz, out_sh = sys.argv[1:]
sys.path.insert(0, script_dir)
import qti_ims_telephony_a17 as qti

rels = [
    "etc/sysconfig/qti_whitelist.xml",
    "system_ext/etc/sysconfig/qti-telephony-shareduid-allowlist.xml",
    "lib64/vendor.qti.diaghal-V1-ndk.so",
    "product/etc/permissions/com.google.android.dialer.support.xml",
    "product/framework/com.google.android.dialer.support.jar",
    "system_ext/bin/dpmd",
    "system_ext/etc/build.prop",
    "system_ext/etc/dpm/dpm.conf",
    "system_ext/etc/init/dpmd.rc",
    "system_ext/etc/permissions/privapp-permissions-qti-ims.xml",
    "system_ext/etc/selinux/mapping/202504.cil",
    "system_ext/etc/selinux/system_ext_file_contexts",
    "system_ext/etc/selinux/system_ext_mac_permissions.xml",
    "system_ext/etc/selinux/system_ext_property_contexts",
    "system_ext/etc/selinux/system_ext_seapp_contexts",
    "system_ext/etc/selinux/system_ext_service_contexts",
    "system_ext/etc/sysconfig/qti_whitelist_system_ext.xml",
    "system_ext/lib64/libgui_shim.so",
    "system_ext/priv-app/ims/lib/arm64/libimscamera_jni.so",
    "system_ext/priv-app/ims/lib/arm64/libimsmedia_jni.so",
]
for _, tgt_rel, _ in qti.QTI_APKS_TO_RESIGN:
    rels.append(tgt_rel)
for j in qti.QTI_SYSTEM_EXT_JARS:
    rels.append(f"system_ext/framework/{j}")
for j in qti.QTI_PRODUCT_JARS:
    rels.append(f"product/framework/{j}")
for x in qti.QTI_SYSTEM_EXT_PERM_XMLS:
    rels.append(f"system_ext/etc/permissions/{x}")
for x in qti.QTI_PRODUCT_PERM_XMLS:
    rels.append(f"product/etc/permissions/{x}")
for so in qti.QTI_SYSTEM_EXT_LIB64_DONOR:
    rels.append(f"system_ext/lib64/{so}")

dirs_seen = set()
ordered_dirs = []
for r in sorted(set(rels)):
    full = os.path.join(sys_sub, r)
    if not os.path.lexists(full):
        raise SystemExit(f"ERROR: Missing QTI/Dialer file in system.raw.img: {full}")
    parts = r.split("/")[:-1]
    cur = ""
    for p in parts:
        cur = f"{cur}/{p}" if cur else p
        if cur not in dirs_seen:
            dirs_seen.add(cur)
            ordered_dirs.append(cur)

sh_lines = ["#!/sbin/sh", "set -e", 'SYS="$1"']
with tarfile.open(out_tgz, "w:gz", format=tarfile.GNU_FORMAT) as tf:
    for d in ordered_dirs:
        dfull = os.path.join(sys_sub, d)
        st = os.lstat(dfull)
        ctx = os.getxattr(dfull, "security.selinux", follow_symlinks=False).rstrip(b"\x00").decode("ascii")
        sh_lines.append(f'mkdir -p "$SYS/{d}" && chown {st.st_uid}:{st.st_gid} "$SYS/{d}" && chmod {stat.S_IMODE(st.st_mode):04o} "$SYS/{d}" && chcon {ctx} "$SYS/{d}"')
    for r in sorted(set(rels)):
        full = os.path.join(sys_sub, r)
        st = os.lstat(full)
        ctx = os.getxattr(full, "security.selinux", follow_symlinks=False).rstrip(b"\x00").decode("ascii")
        tf.add(full, arcname=r, recursive=False)
        if stat.S_ISLNK(st.st_mode):
            sh_lines.append(f'chown -h {st.st_uid}:{st.st_gid} "$SYS/{r}" && chcon -h {ctx} "$SYS/{r}"')
        else:
            sh_lines.append(f'chown {st.st_uid}:{st.st_gid} "$SYS/{r}" && chmod {stat.S_IMODE(st.st_mode):04o} "$SYS/{r}" && chcon {ctx} "$SYS/{r}"')

with open(out_sh, "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(sh_lines) + "\n")
os.chmod(out_sh, 0o755)
print(f"[ota-inc] Packed {len(set(rels))} QTI IMS/VoLTE/SMS/DSDS & Dialer files -> {out_tgz} ({os.path.getsize(out_tgz)/(1024*1024):.2f} MiB)")
QTIPACKPY
umount -lf "${SYS_MNT}"
umount -lf "${VEN_MNT}"
umount -lf "${ODM_MNT}"

# 3. Populate flavor-appropriate OTA payload
STOCK_SYSUI_APK="${DL_DIR}/pixel_a17_cp3a/SystemUI_stock.apk"
[ -f "${STOCK_SYSUI_APK}" ] || STOCK_SYSUI_APK="/var/tmp/rmx1931-a17/downloads/pixel_a17_cp3a/SystemUI_stock.apk"
[ -f "${STOCK_SYSUI_APK}" ] || { echo "ERROR: Missing pristine stock SystemUI_stock.apk"; exit 1; }

cp -f "${UPD_BIN}" "${INC_STAGE}/META-INF/com/google/android/update-binary"
cp -f "${APKS_DIR}/SamuraiHardwareOverlay.apk" "${INC_STAGE}/ota_payload/"
cp -f "${APKS_DIR}/SamuraiTelephonyOverlay.apk" "${INC_STAGE}/ota_payload/"
cp -f "${APKS_DIR}/SamuraiSystemUIOverlay.apk" "${INC_STAGE}/ota_payload/"
cp -f "${APKS_DIR}/SamuraiSettingsOverlay.apk" "${INC_STAGE}/ota_payload/"
cp -f "${APKS_DIR}/SamuraiHidePillOverlay.apk" "${INC_STAGE}/ota_payload/"
cp -f "${APKS_DIR}/SamuraiParts.apk" "${INC_STAGE}/ota_payload/"
cp -f "${APKS_DIR}/SamuraiOTAUpdater.apk" "${INC_STAGE}/ota_payload/"
cp -f "${APKS_DIR}/NexusLauncherRelease.apk" "${INC_STAGE}/ota_payload/"
cp -f "${STOCK_SYSUI_APK}" "${INC_STAGE}/ota_payload/SystemUI.apk"

if [ "${ROM_FLAVOR}" = "ios-beta" ]; then
    cp -f "${APKS_DIR}/SamuraiDialerOverlay.apk" "${INC_STAGE}/ota_payload/"
    cp -f "${APKS_DIR}/SamuraiMessagesOverlay.apk" "${INC_STAGE}/ota_payload/"
    cp -f "${APKS_DIR}/SamuraiGmailOverlay.apk" "${INC_STAGE}/ota_payload/"
    cp -f "${APKS_DIR}/SamuraiPhotosOverlay.apk" "${INC_STAGE}/ota_payload/"
    cp -f "${APKS_DIR}/SamuraiLauncherOverlay.apk" "${INC_STAGE}/ota_payload/"
    cp -f "${APKS_DIR}/SamuraiIos26WallpaperOverlay.apk" "${INC_STAGE}/ota_payload/"
    cp -f "${APKS_DIR}/IosStyler.apk" "${INC_STAGE}/ota_payload/"
    cp -f "${APKS_DIR}/IosSettings.apk" "${INC_STAGE}/ota_payload/"
    cp -f "${APKS_DIR}/IosControlCenter.apk" "${INC_STAGE}/ota_payload/"
    cp -f "${APKS_DIR}/IosDialer.apk" "${INC_STAGE}/ota_payload/"
    cp -f "${APKS_DIR}/IosMessages.apk" "${INC_STAGE}/ota_payload/"
    cp -f "${A17_ROOT}/ios-styler/privapp-permissions-styler.xml" "${INC_STAGE}/ota_payload/"
    cp -f "${A17_ROOT}/ios-settings/privapp-permissions-ios-settings.xml" "${INC_STAGE}/ota_payload/"
    cp -f "${A17_ROOT}/ios-controlcenter/privapp-permissions-ios-controlcenter.xml" "${INC_STAGE}/ota_payload/"
    cp -f "${A17_ROOT}/ios-dialer/privapp-permissions-ios-dialer.xml" "${INC_STAGE}/ota_payload/"
    cp -f "${A17_ROOT}/ios-messages/privapp-permissions-ios-messages.xml" "${INC_STAGE}/ota_payload/"
    cp -f "${A17_ROOT}/SamuraiParts/etc/default-permissions/default-permissions-ios-comms.xml" "${INC_STAGE}/ota_payload/"
fi

if [ "${OTA_FULL_CUMULATIVE}" = "1" ]; then
    PIXEL_DL="${DL_DIR}/pixel_a17_cp3a"
    [ -d "${PIXEL_DL}" ] || PIXEL_DL="/var/tmp/rmx1931-a17/downloads/pixel_a17_cp3a"
    GCAM_APK="${DL_DIR}/gcam/MGC_9.6.080_V53_ENG.apk"
    [ -f "${GCAM_APK}" ] || GCAM_APK="/var/tmp/rmx1931-a17/downloads/gcam/MGC_9.6.080_V53_ENG.apk"
    cp -f "${PIXEL_DL}/WallpaperPickerGoogleRelease.apk" "${INC_STAGE}/ota_payload/"
    cp -f "${PIXEL_DL}/PixelThemesStub2022_and_newer.apk" "${INC_STAGE}/ota_payload/"
    cp -f "${GCAM_APK}" "${INC_STAGE}/ota_payload/GoogleCameraEng.apk"
fi

python3 -c 'import sys; open(sys.argv[1], "wb").write(b"\x00\x00\x80\xd2")' "${INC_STAGE}/ota_payload/gcastartup_uaf_fix.bin"
[ "$(stat -c%s "${INC_STAGE}/ota_payload/gcastartup_uaf_fix.bin")" = "4" ] || { echo "ERROR: invalid gcastartup_uaf_fix.bin"; exit 1; }
cp -f "${A17_ROOT}/SamuraiParts/etc/permissions/privapp-permissions-samuraiparts.xml" "${INC_STAGE}/ota_payload/"
cp -f "${A17_ROOT}/SamuraiParts/etc/default-permissions/default-permissions-samuraiparts.xml" "${INC_STAGE}/ota_payload/"
cp -f "${A17_ROOT}/SamuraiOTAUpdater/etc/permissions/privapp-permissions-ota.xml" "${INC_STAGE}/ota_payload/"
cp -f "${A17_ROOT}/SamuraiOTAUpdater/etc/default-permissions/default-permissions-ota.xml" "${INC_STAGE}/ota_payload/"
cp -f "${A17_ROOT}/SamuraiParts/etc/permissions/privapp-permissions-pixel-launcher.xml" "${INC_STAGE}/ota_payload/"
cp -f "${A17_ROOT}/SamuraiParts/etc/default-permissions/default-permissions-pixel-launcher.xml" "${INC_STAGE}/ota_payload/"
cp -f "${A17_ROOT}/SamuraiParts/etc/default-permissions/default-permissions-gcam.xml" "${INC_STAGE}/ota_payload/"
cp -f "${A17_ROOT}/SamuraiParts/etc/sysconfig/pixel_2016_exclusive.xml" "${INC_STAGE}/ota_payload/"
cp -f "${BIN_DIR}/samurai_hw_daemon" "${INC_STAGE}/ota_payload/"
cp -f "${BIN_DIR}/samurai_ota_bcb" "${INC_STAGE}/ota_payload/"
cp -f "${BIN_DIR}/bpfloader_wrap" "${INC_STAGE}/ota_payload/"
cp -f "${A17_ROOT}/SamuraiOTAUpdater/etc/bin/pixel-boot.sh" "${INC_STAGE}/ota_payload/pixel-boot.sh"
cp -f "${A17_ROOT}/SamuraiOTAUpdater/etc/bin/ota-watcher.sh" "${INC_STAGE}/ota_payload/ota-watcher.sh"
cp -f "${A17_ROOT}/SamuraiOTAUpdater/etc/bin/ota-post-fs.sh" "${INC_STAGE}/ota_payload/ota-post-fs.sh"
cp -f "${A17_ROOT}/SamuraiOTAUpdater/etc/init/ota.rc" "${INC_STAGE}/ota_payload/ota.rc"
cp -f "${A17_ROOT}/SamuraiParts/etc/init/samurai_parts.rc" "${INC_STAGE}/ota_payload/samurai_parts.rc"
cp -f "${A17_ROOT}/SamuraiParts/etc/init/init.samurai.perf.rc" "${INC_STAGE}/ota_payload/init.samurai.perf.rc"
cp -f "${A17_ROOT}/SamuraiParts/etc/init/init.samurai.bootguard.rc" "${INC_STAGE}/ota_payload/init.samurai.bootguard.rc"

cat > "${INC_STAGE}/ota_payload/apply_ota.sh" <<EOF
#!/sbin/sh
set -ex

OTA_VER="${OTA_VERSION}"
OTA_TS="${OTA_BUILD_DATETIME}"
OTA_CH="${OTA_CHANNEL}"
OTA_REPO="${OTA_GITHUB_REPO}"
OTA_URL="${OTA_MANIFEST_PROP_URL}"
FLAVOR="${ROM_FLAVOR}"

export PATH="/sbin:/system/bin:/vendor/bin:\$PATH"

for mp in /system_root /system /vendor /mnt_sys /mnt_ven; do
    umount "\$mp" 2>/dev/null || umount -l "\$mp" 2>/dev/null || true
done

SYS_DEV=/dev/block/by-name/system
[ -b "\$SYS_DEV" ] || SYS_DEV=/dev/block/bootdevice/by-name/system
VEN_DEV=/dev/block/by-name/vendor
[ -b "\$VEN_DEV" ] || VEN_DEV=/dev/block/bootdevice/by-name/vendor
MISC_DEV=/dev/block/by-name/misc
[ -b "\$MISC_DEV" ] || MISC_DEV=/dev/block/bootdevice/by-name/misc

# Expand system & vendor ext4 filesystems to full UFS GPT partition capacity before mounting rw
e2fsck -fy "\$SYS_DEV" >/dev/null 2>&1 || true
resize2fs "\$SYS_DEV" >/dev/null 2>&1 || true
e2fsck -fy "\$VEN_DEV" >/dev/null 2>&1 || true
resize2fs "\$VEN_DEV" >/dev/null 2>&1 || true

mkdir -p /mnt_sys /mnt_ven
mount -t ext4 -o rw "\$SYS_DEV" /mnt_sys
mount -t ext4 -o rw "\$VEN_DEV" /mnt_ven
df -h /mnt_sys /mnt_ven || true

SYS=/mnt_sys/system
[ -d "\$SYS/system_ext/overlay" ] || SYS=/mnt_sys

grep -q "^ro.system.build.version.release=17" "\$SYS/build.prop" || {
    echo "ERROR: Base system is not Android 17!" >&2
    umount /mnt_sys /mnt_ven 2>/dev/null || true
    exit 1
}

# 0. Prune Redundant Bloat, Stale Stock oat/, Legacy /system/app Comms & Retired v1.0/v1.1 Native AdBlocker
rm -rf "\$SYS/product/app/Camera2" "\$SYS/product/app/Calendar" "\$SYS/app/BasicDreams" "\$SYS/app/PartnerBookmarksProvider" \
       "\$SYS/app/IosDialer" "\$SYS/app/ICall26" "\$SYS/app/IosMessages"
rm -rf "\$SYS/priv-app/IosClock" "\$SYS/etc/permissions/privapp-permissions-ios-clock.xml" "\$SYS/app/IosCalculator" "\$SYS/app/IosNotes" "\$SYS/app/IosWeather"
rm -rf "\$SYS/product/app/Photos/oat" "\$SYS/product/app/YouTube/oat" "\$SYS/product/app/YouTubeMusicPrebuilt/oat"
rm -f "\$SYS/bin/samurai_adblockd" "\$SYS/etc/adblock_seed.gz" "\$SYS/system_ext/overlay/SamuraiICallOverlay.apk"

if [ "\$FLAVOR" = "pixelos" ]; then
    # Purge all iOS UI & Comms apps, permission whitelists & iOS-only RRO overlays for 100% Pure Google PixelOS
    rm -rf "\$SYS/priv-app/IosStyler" "\$SYS/priv-app/IosSettings" "\$SYS/priv-app/IosControlCenter" \
           "\$SYS/priv-app/IosDialer" "\$SYS/priv-app/IosMessages" \
           "\$SYS/app/ICall26" "\$SYS/app/IosMessages"
    rm -f "\$SYS/etc/permissions/privapp-permissions-styler.xml" \
          "\$SYS/etc/permissions/privapp-permissions-ios-settings.xml" \
          "\$SYS/etc/permissions/privapp-permissions-ios-controlcenter.xml" \
          "\$SYS/etc/permissions/privapp-permissions-ios-dialer.xml" \
          "\$SYS/etc/permissions/privapp-permissions-ios-messages.xml" \
          "\$SYS/etc/default-permissions/default-permissions-ios-comms.xml"
    rm -f "\$SYS/system_ext/overlay/SamuraiDialerOverlay.apk" \
          "\$SYS/system_ext/overlay/SamuraiICallOverlay.apk" \
          "\$SYS/system_ext/overlay/SamuraiMessagesOverlay.apk" \
          "\$SYS/system_ext/overlay/SamuraiGmailOverlay.apk" \
          "\$SYS/system_ext/overlay/SamuraiPhotosOverlay.apk" \
          "\$SYS/system_ext/overlay/SamuraiLauncherOverlay.apk" \
          "\$SYS/system_ext/overlay/SamuraiIos26WallpaperOverlay.apk"
fi

# 1. Install Shared High-Priority Static & Dynamic RRO Overlays
for rro in \
    SamuraiHardwareOverlay.apk \
    SamuraiTelephonyOverlay.apk \
    SamuraiSystemUIOverlay.apk \
    SamuraiSettingsOverlay.apk \
    SamuraiHidePillOverlay.apk; do
    cp -f "/tmp/ota_payload/\$rro" "\$SYS/system_ext/overlay/\$rro"
    chown 0:0 "\$SYS/system_ext/overlay/\$rro"
    chmod 0644 "\$SYS/system_ext/overlay/\$rro"
    chcon u:object_r:system_file:s0 "\$SYS/system_ext/overlay/\$rro"
done

# 1b. Install QTI IMS / VoLTE / SMS-over-IMS / DSDS / DPM Stack, RMX1931 CarrierConfigRes & Google Dialer Support
if [ -f /tmp/ota_payload/qti_ims_telephony.tar.gz ]; then
    rm -rf "\$SYS/system_ext/priv-app/ims/oat" \
           "\$SYS/system_ext/priv-app/qcrilmsgtunnel/oat" \
           "\$SYS/system_ext/priv-app/dpmserviceapp/oat" \
           "\$SYS/system_ext/app/QtiTelephonyService/oat" \
           "\$SYS/system_ext/app/ImsRcsService/oat" \
           "\$SYS/system_ext/framework/oat" \
           "\$SYS/product/framework/oat" \
           "\$SYS/system_ext/framework/qti-telephony-common.jar" 2>/dev/null || true
    tar -xzf /tmp/ota_payload/qti_ims_telephony.tar.gz -C "\$SYS"
    chmod 0755 /tmp/ota_payload/qti_ims_fixperms.sh
    /sbin/sh /tmp/ota_payload/qti_ims_fixperms.sh "\$SYS"
fi

if [ "\$FLAVOR" = "ios-beta" ]; then
    for rro in \
        SamuraiDialerOverlay.apk \
        SamuraiMessagesOverlay.apk \
        SamuraiGmailOverlay.apk \
        SamuraiPhotosOverlay.apk \
        SamuraiLauncherOverlay.apk \
        SamuraiIos26WallpaperOverlay.apk; do
        cp -f "/tmp/ota_payload/\$rro" "\$SYS/system_ext/overlay/\$rro"
        chown 0:0 "\$SYS/system_ext/overlay/\$rro"
        chmod 0644 "\$SYS/system_ext/overlay/\$rro"
        chcon u:object_r:system_file:s0 "\$SYS/system_ext/overlay/\$rro"
    done
fi

# 2. Install Patched services.jar, Pristine Stock SystemUI.apk, NexusLauncherRelease.apk, SamuraiParts & SamuraiOTAUpdater
rm -rf "\$SYS/system_ext/priv-app/SystemUI/oat"
rm -f "\$SYS/framework/oat/arm64/services.art" "\$SYS/framework/oat/arm64/services.odex" "\$SYS/framework/oat/arm64/services.vdex"
mkdir -p "\$SYS/priv-app/SamuraiParts" "\$SYS/priv-app/SamuraiOTAUpdater" "\$SYS/system_ext/priv-app/SystemUI" \
         "\$SYS/etc/permissions" "\$SYS/system_ext/etc/permissions" "\$SYS/etc/default-permissions" \
         "\$SYS/etc/sysconfig" "\$SYS/product/etc/sysconfig" "\$SYS/etc/samurai-mods"
for pdir in "\$SYS/priv-app/SamuraiParts" "\$SYS/priv-app/SamuraiOTAUpdater" "\$SYS/system_ext/priv-app/SystemUI" "\$SYS/product/etc/sysconfig" "\$SYS/etc/samurai-mods"; do
    chown 0:0 "\$pdir"
    chmod 0755 "\$pdir"
    chcon u:object_r:system_file:s0 "\$pdir"
done

if [ -f /tmp/ota_payload/NexusLauncherRelease.apk ]; then
    rm -rf "\$SYS/system_ext/priv-app/Launcher3QuickStep" \
           "\$SYS/system_ext/priv-app/ThemePicker" \
           "\$SYS/system_ext/etc/permissions/com.android.launcher3.xml" \
           "\$SYS/system_ext/priv-app/NexusLauncherRelease/oat"
    mkdir -p "\$SYS/system_ext/priv-app/NexusLauncherRelease"
    chown 0:0 "\$SYS/system_ext/priv-app/NexusLauncherRelease"
    chmod 0755 "\$SYS/system_ext/priv-app/NexusLauncherRelease"
    chcon u:object_r:system_file:s0 "\$SYS/system_ext/priv-app/NexusLauncherRelease"
    cp -f /tmp/ota_payload/NexusLauncherRelease.apk "\$SYS/system_ext/priv-app/NexusLauncherRelease/NexusLauncherRelease.apk"
    chown 0:0 "\$SYS/system_ext/priv-app/NexusLauncherRelease/NexusLauncherRelease.apk"
    chmod 0644 "\$SYS/system_ext/priv-app/NexusLauncherRelease/NexusLauncherRelease.apk"
    chcon u:object_r:system_file:s0 "\$SYS/system_ext/priv-app/NexusLauncherRelease/NexusLauncherRelease.apk"
    touch "\$SYS/system_ext/priv-app/NexusLauncherRelease/NexusLauncherRelease.apk" \
          "\$SYS/system_ext/priv-app/NexusLauncherRelease" 2>/dev/null || true
else
    [ -f "\$SYS/system_ext/priv-app/NexusLauncherRelease/NexusLauncherRelease.apk" ] || {
        echo "ERROR: Base system missing Retail Pixel Launcher — clean-flash System.zip first!" >&2
        umount /mnt_sys /mnt_ven 2>/dev/null || true
        exit 1
    }
fi

if [ -f /tmp/ota_payload/WallpaperPickerGoogleRelease.apk ]; then
    mkdir -p "\$SYS/system_ext/priv-app/WallpaperPickerGoogleRelease" \
             "\$SYS/product/app/PixelThemesStub2022_and_newer"
    for dirp in "\$SYS/system_ext/priv-app/WallpaperPickerGoogleRelease" "\$SYS/product/app/PixelThemesStub2022_and_newer"; do
        chown 0:0 "\$dirp"
        chmod 0755 "\$dirp"
        chcon u:object_r:system_file:s0 "\$dirp"
    done
    cp -f /tmp/ota_payload/WallpaperPickerGoogleRelease.apk "\$SYS/system_ext/priv-app/WallpaperPickerGoogleRelease/WallpaperPickerGoogleRelease.apk"
    cp -f /tmp/ota_payload/PixelThemesStub2022_and_newer.apk "\$SYS/product/app/PixelThemesStub2022_and_newer/PixelThemesStub2022_and_newer.apk"
    for lapk in \
        "\$SYS/system_ext/priv-app/WallpaperPickerGoogleRelease/WallpaperPickerGoogleRelease.apk" \
        "\$SYS/product/app/PixelThemesStub2022_and_newer/PixelThemesStub2022_and_newer.apk"; do
        chown 0:0 "\$lapk"
        chmod 0644 "\$lapk"
        chcon u:object_r:system_file:s0 "\$lapk"
    done
fi

cp -f /tmp/ota_payload/SamuraiParts.apk "\$SYS/priv-app/SamuraiParts/SamuraiParts.apk"
cp -f /tmp/ota_payload/SamuraiOTAUpdater.apk "\$SYS/priv-app/SamuraiOTAUpdater/SamuraiOTAUpdater.apk"
cp -f /tmp/ota_payload/SystemUI.apk "\$SYS/system_ext/priv-app/SystemUI/SystemUI.apk"
cp -f /tmp/ota_payload/services.jar "\$SYS/framework/services.jar"
touch "\$SYS/system_ext/priv-app/SystemUI/SystemUI.apk" "\$SYS/system_ext/priv-app/SystemUI" "\$SYS/framework/services.jar" 2>/dev/null || true

cp -f /tmp/ota_payload/privapp-permissions-samuraiparts.xml "\$SYS/etc/permissions/privapp-permissions-samuraiparts.xml"
cp -f /tmp/ota_payload/default-permissions-samuraiparts.xml "\$SYS/etc/default-permissions/default-permissions-samuraiparts.xml"
cp -f /tmp/ota_payload/privapp-permissions-ota.xml "\$SYS/etc/permissions/privapp-permissions-ota.xml"
cp -f /tmp/ota_payload/default-permissions-ota.xml "\$SYS/etc/default-permissions/default-permissions-ota.xml"
cp -f /tmp/ota_payload/privapp-permissions-pixel-launcher.xml "\$SYS/system_ext/etc/permissions/privapp-permissions-pixel-launcher.xml"
cp -f /tmp/ota_payload/default-permissions-pixel-launcher.xml "\$SYS/etc/default-permissions/default-permissions-pixel-launcher.xml"
cp -f /tmp/ota_payload/default-permissions-gcam.xml "\$SYS/etc/default-permissions/default-permissions-gcam.xml"
cp -f /tmp/ota_payload/pixel-contextual-search.xml "\$SYS/etc/sysconfig/pixel-contextual-search.xml"
cp -f /tmp/ota_payload/pixel_2016_exclusive.xml "\$SYS/product/etc/sysconfig/pixel_2016_exclusive.xml"
cp -f /tmp/ota_payload/samurai-mod-helper.jar "\$SYS/framework/samurai-mod-helper.jar"
cp -f /tmp/ota_payload/udfps_icon_pressed.webp "\$SYS/etc/udfps_icon_pressed.webp"
cp -f /tmp/ota_payload/gcastartup_uaf_fix.bin "\$SYS/etc/samurai-mods/gcastartup_uaf_fix.bin"
cp -f /tmp/ota_payload/release-mods.json "\$SYS/etc/samurai-mods/release-mods.json"

if [ "\$FLAVOR" = "ios-beta" ]; then
    rm -rf "\$SYS/app/ICall26" "\$SYS/app/IosDialer" "\$SYS/app/IosMessages" \
           "\$SYS/priv-app/IosDialer" "\$SYS/priv-app/IosMessages" \
           "\$SYS/priv-app/IosSettings" "\$SYS/priv-app/IosControlCenter" "\$SYS/priv-app/IosStyler/oat"
    mkdir -p "\$SYS/priv-app/IosStyler" "\$SYS/priv-app/IosSettings/lib/arm64" "\$SYS/priv-app/IosControlCenter/lib/arm64" \
             "\$SYS/priv-app/IosDialer" "\$SYS/priv-app/IosMessages/lib/arm64"
    for pdir in "\$SYS/priv-app/IosStyler" "\$SYS/priv-app/IosSettings" "\$SYS/priv-app/IosSettings/lib" "\$SYS/priv-app/IosSettings/lib/arm64" \
                "\$SYS/priv-app/IosControlCenter" "\$SYS/priv-app/IosControlCenter/lib" "\$SYS/priv-app/IosControlCenter/lib/arm64" \
                "\$SYS/priv-app/IosDialer" "\$SYS/priv-app/IosMessages" "\$SYS/priv-app/IosMessages/lib" "\$SYS/priv-app/IosMessages/lib/arm64"; do
        chown 0:0 "\$pdir"
        chmod 0755 "\$pdir"
        chcon u:object_r:system_file:s0 "\$pdir"
    done
    cp -f /tmp/ota_payload/IosStyler.apk "\$SYS/priv-app/IosStyler/IosStyler.apk"
    cp -f /tmp/ota_payload/IosSettings.apk "\$SYS/priv-app/IosSettings/IosSettings.apk"
    if unzip -l "\$SYS/priv-app/IosSettings/IosSettings.apk" 2>/dev/null | grep -q 'lib/arm64-v8a/'; then
        unzip -o -q "\$SYS/priv-app/IosSettings/IosSettings.apk" 'lib/arm64-v8a/*' -d "\$SYS/priv-app/IosSettings/"
        mv -f "\$SYS/priv-app/IosSettings/lib/arm64-v8a/"*.so "\$SYS/priv-app/IosSettings/lib/arm64/"
        rmdir "\$SYS/priv-app/IosSettings/lib/arm64-v8a"
        for isso in "\$SYS/priv-app/IosSettings/lib/arm64/"*.so; do
            chown 0:0 "\$isso"; chmod 0644 "\$isso"; chcon u:object_r:system_file:s0 "\$isso"
        done
    fi
    cp -f /tmp/ota_payload/IosControlCenter.apk "\$SYS/priv-app/IosControlCenter/IosControlCenter.apk"
    if unzip -l "\$SYS/priv-app/IosControlCenter/IosControlCenter.apk" 2>/dev/null | grep -q 'lib/arm64-v8a/'; then
        unzip -o -q "\$SYS/priv-app/IosControlCenter/IosControlCenter.apk" 'lib/arm64-v8a/*' -d "\$SYS/priv-app/IosControlCenter/"
        mv -f "\$SYS/priv-app/IosControlCenter/lib/arm64-v8a/"*.so "\$SYS/priv-app/IosControlCenter/lib/arm64/"
        rmdir "\$SYS/priv-app/IosControlCenter/lib/arm64-v8a"
        for iccso in "\$SYS/priv-app/IosControlCenter/lib/arm64/"*.so; do
            chown 0:0 "\$iccso"; chmod 0644 "\$iccso"; chcon u:object_r:system_file:s0 "\$iccso"
        done
    fi
    cp -f /tmp/ota_payload/IosDialer.apk "\$SYS/priv-app/IosDialer/IosDialer.apk"
    cp -f /tmp/ota_payload/IosMessages.apk "\$SYS/priv-app/IosMessages/IosMessages.apk"
    if unzip -l "\$SYS/priv-app/IosMessages/IosMessages.apk" 2>/dev/null | grep -q 'lib/arm64-v8a/'; then
        unzip -o -q "\$SYS/priv-app/IosMessages/IosMessages.apk" 'lib/arm64-v8a/*' -d "\$SYS/priv-app/IosMessages/"
        mv -f "\$SYS/priv-app/IosMessages/lib/arm64-v8a/"*.so "\$SYS/priv-app/IosMessages/lib/arm64/"
        rmdir "\$SYS/priv-app/IosMessages/lib/arm64-v8a"
        for imso in "\$SYS/priv-app/IosMessages/lib/arm64/"*.so; do
            chown 0:0 "\$imso"; chmod 0644 "\$imso"; chcon u:object_r:system_file:s0 "\$imso"
        done
    fi
    cp -f /tmp/ota_payload/privapp-permissions-styler.xml "\$SYS/etc/permissions/privapp-permissions-styler.xml"
    cp -f /tmp/ota_payload/privapp-permissions-ios-settings.xml "\$SYS/etc/permissions/privapp-permissions-ios-settings.xml"
    cp -f /tmp/ota_payload/privapp-permissions-ios-controlcenter.xml "\$SYS/etc/permissions/privapp-permissions-ios-controlcenter.xml"
    cp -f /tmp/ota_payload/privapp-permissions-ios-dialer.xml "\$SYS/etc/permissions/privapp-permissions-ios-dialer.xml"
    cp -f /tmp/ota_payload/privapp-permissions-ios-messages.xml "\$SYS/etc/permissions/privapp-permissions-ios-messages.xml"
    cp -f /tmp/ota_payload/default-permissions-ios-comms.xml "\$SYS/etc/default-permissions/default-permissions-ios-comms.xml"
    for ios_f in \
        "\$SYS/priv-app/IosStyler/IosStyler.apk" \
        "\$SYS/priv-app/IosSettings/IosSettings.apk" \
        "\$SYS/priv-app/IosControlCenter/IosControlCenter.apk" \
        "\$SYS/priv-app/IosDialer/IosDialer.apk" \
        "\$SYS/priv-app/IosMessages/IosMessages.apk" \
        "\$SYS/etc/permissions/privapp-permissions-styler.xml" \
        "\$SYS/etc/permissions/privapp-permissions-ios-settings.xml" \
        "\$SYS/etc/permissions/privapp-permissions-ios-controlcenter.xml" \
        "\$SYS/etc/permissions/privapp-permissions-ios-dialer.xml" \
        "\$SYS/etc/permissions/privapp-permissions-ios-messages.xml" \
        "\$SYS/etc/default-permissions/default-permissions-ios-comms.xml"; do
        chown 0:0 "\$ios_f"; chmod 0644 "\$ios_f"; chcon u:object_r:system_file:s0 "\$ios_f"
    done
    touch "\$SYS/priv-app/IosDialer/IosDialer.apk" "\$SYS/priv-app/IosDialer" \
          "\$SYS/priv-app/IosMessages/IosMessages.apk" "\$SYS/priv-app/IosMessages" 2>/dev/null || true
fi

if [ -f /tmp/ota_payload/photos-gsi-dex.zip ]; then
    cp -f /tmp/ota_payload/photos-gsi-dex.zip "\$SYS/etc/samurai-mods/photos-gsi-dex.zip"
    chown 0:0 "\$SYS/etc/samurai-mods/photos-gsi-dex.zip"
    chmod 0644 "\$SYS/etc/samurai-mods/photos-gsi-dex.zip"
    chcon u:object_r:system_file:s0 "\$SYS/etc/samurai-mods/photos-gsi-dex.zip"
else
    [ -f "\$SYS/etc/samurai-mods/photos-gsi-dex.zip" ] || {
        echo "ERROR: Base system missing photos-gsi-dex.zip — clean-flash System.zip first!" >&2
        umount /mnt_sys /mnt_ven 2>/dev/null || true
        exit 1
    }
fi

for apk in \
    "\$SYS/priv-app/SamuraiParts/SamuraiParts.apk" \
    "\$SYS/priv-app/SamuraiOTAUpdater/SamuraiOTAUpdater.apk" \
    "\$SYS/system_ext/priv-app/SystemUI/SystemUI.apk" \
    "\$SYS/framework/services.jar" \
    "\$SYS/etc/permissions/privapp-permissions-samuraiparts.xml" \
    "\$SYS/etc/default-permissions/default-permissions-samuraiparts.xml" \
    "\$SYS/etc/permissions/privapp-permissions-ota.xml" \
    "\$SYS/etc/default-permissions/default-permissions-ota.xml" \
    "\$SYS/system_ext/etc/permissions/privapp-permissions-pixel-launcher.xml" \
    "\$SYS/etc/default-permissions/default-permissions-pixel-launcher.xml" \
    "\$SYS/etc/default-permissions/default-permissions-gcam.xml" \
    "\$SYS/etc/sysconfig/pixel-contextual-search.xml" \
    "\$SYS/product/etc/sysconfig/pixel_2016_exclusive.xml" \
    "\$SYS/framework/samurai-mod-helper.jar" \
    "\$SYS/etc/udfps_icon_pressed.webp" \
    "\$SYS/etc/samurai-mods/gcastartup_uaf_fix.bin" \
    "\$SYS/etc/samurai-mods/release-mods.json"; do
    chown 0:0 "\$apk"
    chmod 0644 "\$apk"
    chcon u:object_r:system_file:s0 "\$apk"
done

# 2a. Install or Preserve Pixel Google Camera 9.6 (BSG V53) in /vendor/gcam & Update 4-Camera CHI Override HAL
mkdir -p "\$SYS/product/app/GoogleCameraEng"
chown 0:0 "\$SYS/product/app/GoogleCameraEng"
chmod 0755 "\$SYS/product/app/GoogleCameraEng"
chcon u:object_r:system_file:s0 "\$SYS/product/app/GoogleCameraEng"

if [ -f /tmp/ota_payload/GoogleCameraEng.apk ]; then
    rm -rf "\$SYS/product/app/GoogleCameraEng" /mnt_ven/gcam
    mkdir -p "\$SYS/product/app/GoogleCameraEng" /mnt_ven/gcam/lib/arm64
    chown 0:0 "\$SYS/product/app/GoogleCameraEng"
    chmod 0755 "\$SYS/product/app/GoogleCameraEng"
    chcon u:object_r:system_file:s0 "\$SYS/product/app/GoogleCameraEng"

    mv -f /tmp/ota_payload/GoogleCameraEng.apk /mnt_ven/gcam/GoogleCameraEng.apk
    unzip -o -q /mnt_ven/gcam/GoogleCameraEng.apk 'lib/arm64-v8a/*' -d /mnt_ven/gcam/
    mv -f /mnt_ven/gcam/lib/arm64-v8a/*.so /mnt_ven/gcam/lib/arm64/
    rmdir /mnt_ven/gcam/lib/arm64-v8a

    dd if=/tmp/ota_payload/gcastartup_uaf_fix.bin of=/mnt_ven/gcam/lib/arm64/libgcastartup.so bs=4 seek=20897195 count=1 conv=notrunc
    dd if=/mnt_ven/gcam/lib/arm64/libgcastartup.so bs=4 skip=20897195 count=1 2>/dev/null | cmp -s - /tmp/ota_payload/gcastartup_uaf_fix.bin

    for gdir in /mnt_ven/gcam /mnt_ven/gcam/lib /mnt_ven/gcam/lib/arm64; do
        chown 0:0 "\$gdir"; chmod 0755 "\$gdir"; chcon u:object_r:system_file:s0 "\$gdir"
    done
    chown 0:0 /mnt_ven/gcam/GoogleCameraEng.apk
    chmod 0644 /mnt_ven/gcam/GoogleCameraEng.apk
    chcon u:object_r:system_file:s0 /mnt_ven/gcam/GoogleCameraEng.apk
    for gso in /mnt_ven/gcam/lib/arm64/*.so; do
        chown 0:0 "\$gso"; chmod 0644 "\$gso"; chcon u:object_r:system_file:s0 "\$gso"
    done
else
    [ -f /mnt_ven/gcam/GoogleCameraEng.apk ] && [ -f /mnt_ven/gcam/lib/arm64/libgcastartup.so ] || {
        echo "ERROR: Base vendor missing /vendor/gcam — clean-flash VendorBoot.zip first!" >&2
        umount /mnt_sys /mnt_ven 2>/dev/null || true
        exit 1
    }
    dd if=/tmp/ota_payload/gcastartup_uaf_fix.bin of=/mnt_ven/gcam/lib/arm64/libgcastartup.so bs=4 seek=20897195 count=1 conv=notrunc
    dd if=/mnt_ven/gcam/lib/arm64/libgcastartup.so bs=4 skip=20897195 count=1 2>/dev/null | cmp -s - /tmp/ota_payload/gcastartup_uaf_fix.bin
fi

cp -f /tmp/ota_payload/com.qti.chi.override.so /mnt_ven/lib64/hw/com.qti.chi.override.so
chown 0:0 /mnt_ven/lib64/hw/com.qti.chi.override.so
chmod 0644 /mnt_ven/lib64/hw/com.qti.chi.override.so
chcon u:object_r:vendor_file:s0 /mnt_ven/lib64/hw/com.qti.chi.override.so

if [ -f /tmp/ota_payload/libfod_zpos_hook.so ]; then
    cp -f /tmp/ota_payload/libfod_zpos_hook.so /mnt_ven/lib64/libfod_zpos_hook.so
    chown 0:0 /mnt_ven/lib64/libfod_zpos_hook.so
    chmod 0644 /mnt_ven/lib64/libfod_zpos_hook.so
    chcon u:object_r:vendor_file:s0 /mnt_ven/lib64/libfod_zpos_hook.so
    cp -f /tmp/ota_payload/libfod_zpos_hook.so /mnt_ven/lib64/libdisplaydebug.so
    chown 0:0 /mnt_ven/lib64/libdisplaydebug.so
    chmod 0644 /mnt_ven/lib64/libdisplaydebug.so
    chcon u:object_r:vendor_file:s0 /mnt_ven/lib64/libdisplaydebug.so
fi

for vcfg in fstab.qcom media_codecs.xml media_codecs_c2.xml media_codecs_performance_c2.xml media_codecs_performance.xml powerhint.json; do
    cp -f "/tmp/ota_payload/\$vcfg" "/mnt_ven/etc/\$vcfg"
    chown 0:0 "/mnt_ven/etc/\$vcfg"
    chmod 0644 "/mnt_ven/etc/\$vcfg"
    chcon u:object_r:vendor_configs_file:s0 "/mnt_ven/etc/\$vcfg"
done
if [ -f /tmp/ota_payload/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc ]; then
    cp -f /tmp/ota_payload/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc /mnt_ven/etc/init/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc
    chown 0:0 /mnt_ven/etc/init/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc
    chmod 0644 /mnt_ven/etc/init/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc
    chcon u:object_r:vendor_configs_file:s0 /mnt_ven/etc/init/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc
fi
if [ -f /tmp/ota_payload/android.hardware.graphics.composer@2.4-service.rc ]; then
    cp -f /tmp/ota_payload/android.hardware.graphics.composer@2.4-service.rc /mnt_ven/etc/init/android.hardware.graphics.composer@2.4-service.rc
    chown 0:0 /mnt_ven/etc/init/android.hardware.graphics.composer@2.4-service.rc
    chmod 0644 /mnt_ven/etc/init/android.hardware.graphics.composer@2.4-service.rc
    chcon u:object_r:vendor_configs_file:s0 /mnt_ven/etc/init/android.hardware.graphics.composer@2.4-service.rc
fi
if [ -f /tmp/ota_payload/vendor.qti.adsprpc-service.rc ]; then
    cp -f /tmp/ota_payload/vendor.qti.adsprpc-service.rc /mnt_ven/etc/init/vendor.qti.adsprpc-service.rc
    chown 0:0 /mnt_ven/etc/init/vendor.qti.adsprpc-service.rc
    chmod 0644 /mnt_ven/etc/init/vendor.qti.adsprpc-service.rc
    chcon u:object_r:vendor_configs_file:s0 /mnt_ven/etc/init/vendor.qti.adsprpc-service.rc
fi
ODM_DEV=/dev/block/by-name/odm
[ -b "\$ODM_DEV" ] || ODM_DEV=/dev/block/bootdevice/by-name/odm
if [ -b "\$ODM_DEV" ] && [ -f /tmp/ota_payload/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc ]; then
    umount /odm /mnt_odm 2>/dev/null || umount -l /odm /mnt_odm 2>/dev/null || true
    e2fsck -fy "\$ODM_DEV" >/dev/null 2>&1 || true
    resize2fs "\$ODM_DEV" >/dev/null 2>&1 || true
    mkdir -p /mnt_odm
    if mount -t ext4 -o rw "\$ODM_DEV" /mnt_odm 2>/dev/null; then
        cp -f /tmp/ota_payload/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc /mnt_odm/etc/init/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc
        chown 0:0 /mnt_odm/etc/init/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc
        chmod 0644 /mnt_odm/etc/init/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc
        chcon u:object_r:vendor_configs_file:s0 /mnt_odm/etc/init/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc
        if [ -f /tmp/ota_payload/libgf_hal_G3.so ]; then
            cp -f /tmp/ota_payload/libgf_hal_G3.so /mnt_odm/lib64/libgf_hal_G3.so
            chown 0:0 /mnt_odm/lib64/libgf_hal_G3.so
            chmod 0644 /mnt_odm/lib64/libgf_hal_G3.so
            chcon u:object_r:vendor_file:s0 /mnt_odm/lib64/libgf_hal_G3.so
        fi
        sync
        umount /mnt_odm 2>/dev/null || true
    fi
fi
if [ -f /tmp/ota_payload/vendor_sepolicy.cil ]; then
    cp -f /tmp/ota_payload/vendor_sepolicy.cil /mnt_ven/etc/selinux/vendor_sepolicy.cil
    chown 0:0 /mnt_ven/etc/selinux/vendor_sepolicy.cil
    chmod 0644 /mnt_ven/etc/selinux/vendor_sepolicy.cil
    chcon u:object_r:vendor_configs_file:s0 /mnt_ven/etc/selinux/vendor_sepolicy.cil
fi
if [ -f /tmp/ota_payload/vendor_property_contexts ]; then
    cp -f /tmp/ota_payload/vendor_property_contexts /mnt_ven/etc/selinux/vendor_property_contexts
    chown 0:0 /mnt_ven/etc/selinux/vendor_property_contexts
    chmod 0644 /mnt_ven/etc/selinux/vendor_property_contexts
    chcon u:object_r:vendor_configs_file:s0 /mnt_ven/etc/selinux/vendor_property_contexts
fi
printf 'RMX1931_PIXELOS_A17_VENDOR=1\n' > /mnt_ven/etc/.pixelos_a17_vendor_ok
chown 0:0 /mnt_ven/etc/.pixelos_a17_vendor_ok
chmod 0644 /mnt_ven/etc/.pixelos_a17_vendor_ok
chcon u:object_r:vendor_configs_file:s0 /mnt_ven/etc/.pixelos_a17_vendor_ok

for sphal_lib in \
    /mnt_ven/lib64/libOpenCL_adreno.so \
    /mnt_ven/lib/libOpenCL_adreno.so \
    /mnt_ven/lib64/libkernelmanager.so \
    /mnt_ven/lib/libkernelmanager.so \
    /mnt_ven/lib64/libarcsoft_scbokeh_video.so \
    /mnt_ven/lib/libarcsoft_scbokeh_video.so; do
    if [ -f "\$sphal_lib" ]; then
        chown 0:0 "\$sphal_lib"
        chmod 0644 "\$sphal_lib"
        chcon u:object_r:same_process_hal_file:s0 "\$sphal_lib"
    fi
done
VFCTX="/mnt_ven/etc/selinux/vendor_file_contexts"
if [ -f "\$VFCTX" ] && ! grep -q "libOpenCL_adreno" "\$VFCTX"; then
    cat >> "\$VFCTX" <<'VFCTXEOF'

/vendor/lib(64)?/libOpenCL_adreno\.so   u:object_r:same_process_hal_file:s0
/vendor/lib(64)?/libkernelmanager\.so   u:object_r:same_process_hal_file:s0
/vendor/lib(64)?/libarcsoft_scbokeh_video\.so   u:object_r:same_process_hal_file:s0
VFCTXEOF
    chown 0:0 "\$VFCTX"
    chmod 0644 "\$VFCTX"
    chcon u:object_r:file_contexts_file:s0 "\$VFCTX"
fi
rm -f /mnt_ven/etc/selinux/precompiled_sepolicy /mnt_ven/etc/selinux/precompiled_sepolicy.plat_sepolicy_and_mapping.sha256 2>/dev/null || true
if [ -f /mnt_ven/etc/init/hw/init.target.rc ]; then
    sed -i '/^[[:space:]]*stop[[:space:]][[:space:]]*statsd[[:space:]]*$/d' /mnt_ven/etc/init/hw/init.target.rc
    chown 0:0 /mnt_ven/etc/init/hw/init.target.rc
    chmod 0644 /mnt_ven/etc/init/hw/init.target.rc
    chcon u:object_r:vendor_configs_file:s0 /mnt_ven/etc/init/hw/init.target.rc
fi

for binf in samurai_hw_daemon samurai_ota_bcb; do
    cp -f "/tmp/ota_payload/\$binf" "\$SYS/bin/\$binf"
    chown 0:0 "\$SYS/bin/\$binf"
    chmod 0755 "\$SYS/bin/\$binf"
    chcon u:object_r:system_file:s0 "\$SYS/bin/\$binf"
done

if [ ! -f "\$SYS/bin/bpfloader.real" ] && [ -f "\$SYS/bin/bpfloader" ]; then
    mv "\$SYS/bin/bpfloader" "\$SYS/bin/bpfloader.real"
    chown 0:2000 "\$SYS/bin/bpfloader.real"
    chmod 0755 "\$SYS/bin/bpfloader.real"
    chcon u:object_r:bpfloader_platform_exec:s0 "\$SYS/bin/bpfloader.real"
fi
cp -f /tmp/ota_payload/bpfloader_wrap "\$SYS/bin/bpfloader"
chown 0:2000 "\$SYS/bin/bpfloader"
chmod 0755 "\$SYS/bin/bpfloader"
chcon u:object_r:bpfloader_platform_exec:s0 "\$SYS/bin/bpfloader"

cp -f /tmp/ota_payload/netbpfload_414 "\$SYS/bin/netbpfload_414"
chown 0:0 "\$SYS/bin/netbpfload_414"
chmod 0755 "\$SYS/bin/netbpfload_414"
chcon u:object_r:bpfloader_exec:s0 "\$SYS/bin/netbpfload_414"

cp -f /tmp/ota_payload/bpf_netd_414.o "\$SYS/etc/bpf_netd_414.o"
chown 1000:1000 "\$SYS/etc/bpf_netd_414.o"
chmod 0644 "\$SYS/etc/bpf_netd_414.o"
chcon u:object_r:system_file:s0 "\$SYS/etc/bpf_netd_414.o"

cp -f /tmp/ota_payload/libnetd_updatable_414.so "\$SYS/lib64/libnetd_updatable_414.so"
chown 1000:1000 "\$SYS/lib64/libnetd_updatable_414.so"
chmod 0644 "\$SYS/lib64/libnetd_updatable_414.so"
chcon u:object_r:system_lib_file:s0 "\$SYS/lib64/libnetd_updatable_414.so"

for syslib in libservice-connectivity_414.so libmeminfo.so libzygote_sys.dylib.so libcap_rust.dylib.so libzygote_species_android_native.so; do
    cp -f "/tmp/ota_payload/\$syslib" "\$SYS/lib64/\$syslib"
    chown 0:0 "\$SYS/lib64/\$syslib"
    chmod 0644 "\$SYS/lib64/\$syslib"
    chcon u:object_r:system_lib_file:s0 "\$SYS/lib64/\$syslib"
done

mkdir -p "\$SYS/etc/bpf.disabled_515"
chown 0:0 "\$SYS/etc/bpf.disabled_515"
chmod 0755 "\$SYS/etc/bpf.disabled_515"
chcon u:object_r:system_file:s0 "\$SYS/etc/bpf.disabled_515"
for bpf515 in dmabuf wakeupsources lock_contention memevents fuseMedia.bpf bpfRingbufProg.bpf; do
    if [ -e "\$SYS/etc/bpf/\$bpf515" ]; then
        rm -rf "\$SYS/etc/bpf.disabled_515/\$bpf515"
        mv "\$SYS/etc/bpf/\$bpf515" "\$SYS/etc/bpf.disabled_515/\$bpf515"
    fi
done
if [ -f "\$SYS/etc/init/mmd.rc" ]; then
    sed -i '/^[[:space:]]*critical[[:space:]]*$/d' "\$SYS/etc/init/mmd.rc"
    chown 0:0 "\$SYS/etc/init/mmd.rc"
    chmod 0644 "\$SYS/etc/init/mmd.rc"
    chcon u:object_r:system_file:s0 "\$SYS/etc/init/mmd.rc"
fi

for shf in pixel-boot.sh ota-watcher.sh ota-post-fs.sh samurai-mods-sync.sh samurai-gcam-sync.sh; do
    cp -f "/tmp/ota_payload/\$shf" "\$SYS/etc/\$shf"
    chown 0:0 "\$SYS/etc/\$shf"
    chmod 0755 "\$SYS/etc/\$shf"
    chcon u:object_r:system_file:s0 "\$SYS/etc/\$shf"
done

for rcf in netbpfload.rc zygote_next64.rc ota.rc samurai_parts.rc init.samurai.perf.rc init.samurai.bootguard.rc; do
    cp -f "/tmp/ota_payload/\$rcf" "\$SYS/etc/init/\$rcf"
    chown 0:0 "\$SYS/etc/init/\$rcf"
    chmod 0644 "\$SYS/etc/init/\$rcf"
    chcon u:object_r:system_file:s0 "\$SYS/etc/init/\$rcf"
done

# 2b. Install Updated Platform SELinux CIL, File Contexts & Property Contexts (/data/ota_raw + ctl.start\$zygote_next)
cp -f /tmp/ota_payload/plat_sepolicy.cil "\$SYS/etc/selinux/plat_sepolicy.cil"
chown 0:0 "\$SYS/etc/selinux/plat_sepolicy.cil"
chmod 0644 "\$SYS/etc/selinux/plat_sepolicy.cil"
chcon u:object_r:sepolicy_file:s0 "\$SYS/etc/selinux/plat_sepolicy.cil"

cp -f /tmp/ota_payload/plat_file_contexts "\$SYS/etc/selinux/plat_file_contexts"
chown 0:0 "\$SYS/etc/selinux/plat_file_contexts"
chmod 0644 "\$SYS/etc/selinux/plat_file_contexts"
chcon u:object_r:file_contexts_file:s0 "\$SYS/etc/selinux/plat_file_contexts"

cp -f /tmp/ota_payload/plat_property_contexts "\$SYS/etc/selinux/plat_property_contexts"
chown 0:0 "\$SYS/etc/selinux/plat_property_contexts"
chmod 0644 "\$SYS/etc/selinux/plat_property_contexts"
chcon u:object_r:property_contexts_file:s0 "\$SYS/etc/selinux/plat_property_contexts"

mkdir -p "\$SYS/etc/vintf"
chown 0:0 "\$SYS/etc/vintf"
chmod 0755 "\$SYS/etc/vintf"
chcon u:object_r:system_file:s0 "\$SYS/etc/vintf"
cp -f /tmp/ota_payload/compatibility_matrix.device.xml "\$SYS/etc/vintf/compatibility_matrix.device.xml"
chown 0:0 "\$SYS/etc/vintf/compatibility_matrix.device.xml"
chmod 0644 "\$SYS/etc/vintf/compatibility_matrix.device.xml"
chcon u:object_r:system_file:s0 "\$SYS/etc/vintf/compatibility_matrix.device.xml"

# 3. Inject Variable RobotoFlex & Google Sans Font Aliases in /system/etc/fonts.xml
F_XML="\$SYS/etc/fonts.xml"
if [ -f "\$F_XML" ] && ! grep -q 'name="roboto-flex-medium"' "\$F_XML"; then
    sed -i 's|</familyset>|  <alias name="roboto-flex-medium" to="roboto-flex" weight="500"/>\n  <alias name="roboto-flex-bold" to="roboto-flex" weight="700"/>\n  <alias name="google-sans" to="roboto-flex"/>\n  <alias name="google-sans-medium" to="roboto-flex" weight="500"/>\n  <alias name="google-sans-bold" to="roboto-flex" weight="700"/>\n  <alias name="google-sans-text" to="roboto-flex"/>\n  <alias name="google-sans-text-medium" to="roboto-flex" weight="500"/>\n  <alias name="google-sans-text-bold" to="roboto-flex" weight="700"/>\n</familyset>|' "\$F_XML"
    chown 0:0 "\$F_XML"
    chmod 0644 "\$F_XML"
    chcon u:object_r:system_file:s0 "\$F_XML"
fi

# 4. Stamp New OTA Channel/Version/Timestamp & Official PixelOS 17.0 Adreno 640 90Hz Props in /system/build.prop
BLUR_DISABLED="1"
[ "\$FLAVOR" = "ios-beta" ] && BLUR_DISABLED="0"
SPROP="\$SYS/build.prop"
sed -i '/^ro\.debuggable=/d; /^ro\.secure=/d; /^ro\.adb\.secure=/d; /^ro\.build\.type=/d; /^ro\.build\.tags=/d; /^ro\.build\.flavor=/d; /^ro\.build\.description=/d; /^ro\.build\.fingerprint=/d; /^ro\.system\.build\.fingerprint=/d; /^ro\.ota\.channel=/d; /^ro\.ota\.version=/d; /^ro\.samurai\.ota\.version=/d; /^ro\.pixelos\.display\.version=/d; /^ro\.ota\.datetime=/d; /^ro\.ota\.repo=/d; /^ro\.ota\.manifest_url=/d; /^persist\.zygote\.zygote_next\.start_on_boot=/d; /^ro\.sf\.lcd_density=/d; /^ro\.surface_flinger\.supports_background_blur=/d; /^ro\.surface_flinger\.blur_disabled_by_default=/d; /^persist\.sys\.sf\.disable_blurs=/d; /^ro\.sf\.blurs_are_expensive=/d; /^debug\.sf\.disable_client_composition_cache=/d; /^debug\.renderengine\.backend=/d; /^debug\.hwui\.renderer=/d; /^debug\.sf\.auto_latch_unsignaled=/d; /^debug\.sf\.latch_unsignaled=/d; /^debug\.sf\.enable_gl_backpressure=/d; /^debug\.sf\.enable_hwc_vds=/d; /^debug\.sf\.predict_hwc_composition_strategy=/d; /^media\.stagefright\.thumbnail\.prefer_hw_codecs=/d; /^dalvik\.vm\.systemuicompilerfilter=/d; /^ro\.surface_flinger\.use_content_detection_for_refresh_rate=/d; /^ro\.surface_flinger\.set_idle_timer_ms=/d; /^ro\.surface_flinger\.set_touch_timer_ms=/d; /^dalvik\.vm\.dex2oat-threads=/d; /^dalvik\.vm\.background-dex2oat-threads=/d; /^dalvik\.vm\.background-dex2oat-cpu-set=/d; /^ro\.build\.product=/d; /^ro\.product\.brand=/d; /^vendor\.camera\.aux\.packagelist=/d; /^persist\.vendor\.camera\.privapp\.list=/d; /^persist\.vendor\.fingerprint\./d; /^persist\.vendor\.qfp=/d; /^vendor\.flash\.locked=/d; /^debug\.sf\.early_/d; /^debug\.sf\.high_fps_/d; /^persist\.radio\.multisim\.config=/d; /^ro\.telephony\.default_network=/d; /^persist\.vendor\.radio\.apm_sim_not_pwdn=/d; /^persist\.vendor\.radio\.sib16_support=/d; /^persist\.vendor\.radio\.custom_ecc=/d; /^persist\.vendor\.radio\.rat_on=/d; /^persist\.dbg\.volte_avail_ovr=/d; /^persist\.dbg\.vt_avail_ovr=/d; /^persist\.dbg\.wfc_avail_ovr=/d' "\$SPROP"
cat >> "\$SPROP" <<SPROPEOF
ro.debuggable=0
ro.adb.secure=0
ro.secure=1
ro.build.type=user
ro.build.tags=release-keys
ro.build.flavor=caiman-user
ro.build.description=caiman-user 17 CP3A.260905.010 16176746 release-keys
ro.build.fingerprint=google/caiman/caiman:17/CP3A.260905.010/16176746:user/release-keys
ro.system.build.fingerprint=google/caiman/caiman:17/CP3A.260905.010/16176746:user/release-keys
ro.build.product=RMX1931
ro.product.brand=realme
persist.radio.multisim.config=dsds
ro.telephony.default_network=22,22
persist.vendor.radio.apm_sim_not_pwdn=1
persist.vendor.radio.sib16_support=1
persist.vendor.radio.custom_ecc=1
persist.vendor.radio.rat_on=combine
persist.dbg.volte_avail_ovr=1
persist.dbg.vt_avail_ovr=1
persist.dbg.wfc_avail_ovr=1
vendor.camera.aux.packagelist=org.codeaurora.snapcam,com.android.camera,com.android.camera2,com.google.android.GoogleCamera,com.google.android.GoogleCameraEng,app.grapheneos.camera
persist.vendor.camera.privapp.list=org.codeaurora.snapcam,com.android.camera,com.android.camera2,com.google.android.GoogleCamera,com.google.android.GoogleCameraEng,app.grapheneos.camera
persist.vendor.fingerprint.sensor_type=optical
persist.vendor.fingerprint.optical.support=1
persist.vendor.fingerprint.optical.iconlocation=244
persist.vendor.fingerprint.optical.iconsize=190
persist.vendor.fingerprint.fp_id=G_OPTICAL_G3
persist.vendor.fingerprint.version=Goodix
persist.vendor.qfp=true
vendor.flash.locked=1
persist.zygote.zygote_next.start_on_boot=true
ro.sf.lcd_density=420
ro.surface_flinger.supports_background_blur=1
ro.surface_flinger.blur_disabled_by_default=true
persist.sys.sf.disable_blurs=\${BLUR_DISABLED}
ro.sf.blurs_are_expensive=1
debug.sf.disable_client_composition_cache=0
debug.sf.auto_latch_unsignaled=0
debug.sf.latch_unsignaled=1
debug.sf.enable_gl_backpressure=0
debug.sf.enable_hwc_vds=1
debug.sf.predict_hwc_composition_strategy=0
media.stagefright.thumbnail.prefer_hw_codecs=true
dalvik.vm.systemuicompilerfilter=speed
ro.surface_flinger.use_content_detection_for_refresh_rate=false
ro.surface_flinger.set_idle_timer_ms=0
ro.surface_flinger.set_touch_timer_ms=4000
dalvik.vm.dex2oat-threads=4
dalvik.vm.background-dex2oat-threads=3
dalvik.vm.background-dex2oat-cpu-set=0,1,2,3,4
ro.ota.channel=\${OTA_CH}
ro.ota.repo=\${OTA_REPO}
ro.ota.manifest_url=\${OTA_URL}
ro.ota.version=\${OTA_VER}
ro.samurai.ota.version=\${OTA_VER}
ro.pixelos.display.version=\${OTA_VER}
ro.ota.datetime=\${OTA_TS}
SPROPEOF
chown 0:0 "\$SPROP"
chmod 0600 "\$SPROP"
chcon u:object_r:system_file:s0 "\$SPROP"

PPROP="\$SYS/product/etc/build.prop"
if [ -f "\$PPROP" ]; then
    sed -i '/^ro\.product\.build\.type=/d; /^ro\.product\.build\.tags=/d; /^ro\.product\.build\.fingerprint=/d; /^ro\.build\.flavor=/d; /^ro\.build\.description=/d' "\$PPROP"
    cat >> "\$PPROP" <<'PPROPEOF'
ro.product.build.type=user
ro.product.build.tags=release-keys
ro.product.build.fingerprint=google/caiman/caiman:17/CP3A.260905.010/16176746:user/release-keys
ro.build.flavor=caiman-user
ro.build.description=caiman-user 17 CP3A.260905.010 16176746 release-keys
PPROPEOF
    chown 0:0 "\$PPROP"
    chmod 0600 "\$PPROP"
    chcon u:object_r:system_file:s0 "\$PPROP"
fi

# 5. Update /vendor/build.prop
VPROP="/mnt_ven/build.prop"
sed -i '/^ro\.build\.product=/d; /^ro\.product\.brand=/d; /^vendor\.camera\.aux\.packagelist=/d; /^persist\.vendor\.camera\.privapp\.list=/d; /^persist\.vendor\.fingerprint\./d; /^persist\.vendor\.qfp=/d; /^vendor\.flash\.locked=/d; /^ro\.sf\.lcd_density=/d; /^ro\.surface_flinger\.supports_background_blur=/d; /^ro\.surface_flinger\.blur_disabled_by_default=/d; /^ro\.sf\.blurs_are_expensive=/d; /^debug\.sf\.disable_client_composition_cache=/d; /^debug\.renderengine\.backend=/d; /^debug\.hwui\.renderer=/d; /^debug\.sf\.auto_latch_unsignaled=/d; /^debug\.sf\.latch_unsignaled=/d; /^debug\.sf\.enable_gl_backpressure=/d; /^debug\.sf\.enable_hwc_vds=/d; /^debug\.sf\.predict_hwc_composition_strategy=/d; /^media\.stagefright\.thumbnail\.prefer_hw_codecs=/d; /^ro\.surface_flinger\.has_wide_color_display=/d; /^persist\.sys\.sf\.color_saturation=/d; /^ro\.surface_flinger\.use_content_detection_for_refresh_rate=/d; /^ro\.surface_flinger\.set_touch_timer_ms=/d; /^ro\.surface_flinger\.set_idle_timer_ms=/d' "\$VPROP"
cat >> "\$VPROP" <<'VPROPEOF'

# RMX1931 Pixel OS 4-Camera AUX Unlock, Goodix G3 Optical UDFPS, Adreno 640 HWC/Codec2 Smoothness, Locked 90Hz & 420dpi
ro.build.product=RMX1931
ro.product.brand=realme
vendor.camera.aux.packagelist=org.codeaurora.snapcam,com.android.camera,com.android.camera2,com.google.android.GoogleCamera,com.google.android.GoogleCameraEng,app.grapheneos.camera
persist.vendor.camera.privapp.list=org.codeaurora.snapcam,com.android.camera,com.android.camera2,com.google.android.GoogleCamera,com.google.android.GoogleCameraEng,app.grapheneos.camera
persist.vendor.fingerprint.sensor_type=optical
persist.vendor.fingerprint.optical.support=1
persist.vendor.fingerprint.optical.iconlocation=244
persist.vendor.fingerprint.optical.iconsize=190
persist.vendor.fingerprint.fp_id=G_OPTICAL_G3
persist.vendor.fingerprint.version=Goodix
persist.vendor.qfp=true
vendor.flash.locked=1
ro.sf.lcd_density=420
ro.surface_flinger.supports_background_blur=1
ro.surface_flinger.blur_disabled_by_default=true
ro.sf.blurs_are_expensive=1
debug.sf.disable_client_composition_cache=0
debug.sf.auto_latch_unsignaled=0
debug.sf.latch_unsignaled=1
debug.sf.enable_gl_backpressure=0
debug.sf.enable_hwc_vds=1
debug.sf.predict_hwc_composition_strategy=0
media.stagefright.thumbnail.prefer_hw_codecs=true
ro.surface_flinger.has_wide_color_display=true
persist.sys.sf.color_saturation=1.10
ro.surface_flinger.use_content_detection_for_refresh_rate=false
ro.surface_flinger.set_touch_timer_ms=4000
ro.surface_flinger.set_idle_timer_ms=0
VPROPEOF
chown 0:0 "\$VPROP"
chmod 0600 "\$VPROP"
chcon u:object_r:vendor_file:s0 "\$VPROP"

sync
umount /mnt_sys
umount /mnt_ven

# 6. Clear RRO/Package/Launcher Icon Caches if /data is decrypted (ota-post-fs.sh handles FBE cross-channel migration!)
if [ -d /data/system/users ] || [ -d /data/data ]; then
    rm -rf /data/resource-cache/* /data/dalvik-cache/* /data/system/package_cache/* \
           /data/user_de/0/com.android.phone/files/carrierconfig-*.xml \
           /data/data/com.android.phone/files/carrierconfig-*.xml \
           /data/data/com.google.android.apps.nexuslauncher/databases/app_icons.db* \
           /data/data/com.google.android.apps.nexuslauncher/cache/* \
           /data/data/com.google.android.apps.nexuslauncher/code_cache/* \
           /data/user/0/com.google.android.apps.nexuslauncher/databases/app_icons.db* \
           /data/user/0/com.google.android.apps.nexuslauncher/cache/* \
           /data/user_de/0/com.google.android.apps.nexuslauncher/databases/app_icons.db* \
           /data/user_de/0/com.google.android.apps.nexuslauncher/cache/* \
           /data/adb/samurai_mods /data/ota_package/adblock /data/ota_package/hw/adblock.prop 2>/dev/null || true
    if [ -d /data/adb ]; then
        chown 0:0 /data/adb 2>/dev/null || true
        chmod 0700 /data/adb 2>/dev/null || true
    fi
    rm -f /data/ota_package/install.ready /data/ota_package/apply.status 2>/dev/null || true
    sync
fi

rm -rf /tmp/ota_payload
dd if=/dev/zero of="\$MISC_DEV" bs=4096 count=1 conv=fsync 2>/dev/null || dd if=/dev/zero of="\$MISC_DEV" bs=4096 count=1 2>/dev/null || true
sync
EOF
chmod 0755 "${INC_STAGE}/ota_payload/apply_ota.sh"
sed -i 's/\r$//' "${INC_STAGE}/ota_payload/apply_ota.sh" "${INC_STAGE}/ota_payload/qti_ims_fixperms.sh" "${INC_STAGE}/ota_payload/pixel-boot.sh" "${INC_STAGE}/ota_payload/ota-watcher.sh" "${INC_STAGE}/ota_payload/ota-post-fs.sh" "${INC_STAGE}/ota_payload/samurai-mods-sync.sh" "${INC_STAGE}/ota_payload/samurai-gcam-sync.sh" "${INC_STAGE}/ota_payload/netbpfload.rc" "${INC_STAGE}/ota_payload/zygote_next64.rc" "${INC_STAGE}/ota_payload/ota.rc" "${INC_STAGE}/ota_payload/samurai_parts.rc" "${INC_STAGE}/ota_payload/init.samurai.perf.rc" "${INC_STAGE}/ota_payload/init.samurai.bootguard.rc"

cat > "${INC_STAGE}/META-INF/com/google/android/updater-script" <<EOF
assert(getprop("ro.product.device") == "samurai" || getprop("ro.build.product") == "samurai" ||
       getprop("ro.product.device") == "RMX1931" || getprop("ro.build.product") == "RMX1931" ||
       getprop("ro.product.device") == "RMX1931L1" || getprop("ro.build.product") == "RMX1931L1" ||
       abort("E3004: This package is for device: samurai,RMX1931,RMX1931L1; this device is " + getprop("ro.product.device") + "."););
assert(qcom.verify_trustzone("TZ.XF.5.2-00209","TZ.XF.5.2-398866") == "1" ||
       abort("E3005: Firmware incompatible — Realme UI 2.0 (Android 11) firmware required."););
ui_print("================================================");
ui_print("  Android 17 Pixel OS ${OTA_VERSION} (${OTA_CHANNEL})");
ui_print("  Device: Realme X2 Pro (RMX1931 / samurai)     ");
ui_print("================================================");
ui_print("Verifying existing PixelOS Android 17 installation...");
run_program("/sbin/sh", "-c", "SYS_DEV=/dev/block/by-name/system; [ -b \"\$SYS_DEV\" ] || SYS_DEV=/dev/block/bootdevice/by-name/system; VEN_DEV=/dev/block/by-name/vendor; [ -b \"\$VEN_DEV\" ] || VEN_DEV=/dev/block/bootdevice/by-name/vendor; umount /system_root /system /vendor /mnt_schk /mnt_vchk 2>/dev/null || umount -l /system_root /system /vendor /mnt_schk /mnt_vchk 2>/dev/null || true; mkdir -p /mnt_schk /mnt_vchk; ok=1; if mount -t ext4 -o ro \"\$SYS_DEV\" /mnt_schk 2>/dev/null || mount -t ext4 \"\$SYS_DEV\" /mnt_schk 2>/dev/null; then S=/mnt_schk/system; [ -f \"\$S/build.prop\" ] || S=/mnt_schk; if grep -q '^ro.system.build.version.release=17' \"\$S/build.prop\" 2>/dev/null && [ -f \"\$S/priv-app/SamuraiParts/SamuraiParts.apk\" ]; then if mount -t ext4 -o ro \"\$VEN_DEV\" /mnt_vchk 2>/dev/null || mount -t ext4 \"\$VEN_DEV\" /mnt_vchk 2>/dev/null; then if [ -f /mnt_vchk/etc/.pixelos_a17_vendor_ok ] || grep -q 'RMX1931_A17_SEPOLICY' /mnt_vchk/etc/selinux/vendor_sepolicy.cil 2>/dev/null; then ok=0; fi; umount /mnt_vchk 2>/dev/null || true; fi; fi; umount /mnt_schk 2>/dev/null || true; fi; rmdir /mnt_schk /mnt_vchk 2>/dev/null || true; exit \$ok") == 0 ||
  abort("E1002: OTA.zip updates existing PixelOS Android 17 installs ONLY! For a first-time Clean Flash, flash VendorBoot.zip [1/2] + System.zip [2/2].");
show_progress(0.200000, 1);
delete_recursive("/system/priv-app/IosClock", "/system/etc/permissions/privapp-permissions-ios-clock.xml", "/system/app/IosCalculator", "/system/app/IosNotes", "/system/app/IosWeather");
EOF

if [ "${ROM_FLAVOR}" = "pixelos" ]; then
    cat >> "${INC_STAGE}/META-INF/com/google/android/updater-script" <<'EOF'
delete_recursive("/system/priv-app/IosStyler", "/system/priv-app/IosSettings", "/system/priv-app/IosControlCenter", "/system/priv-app/IosDialer", "/system/priv-app/IosMessages", "/system/app/ICall26", "/system/app/IosDialer", "/system/app/IosMessages", "/system/etc/permissions/privapp-permissions-styler.xml", "/system/etc/permissions/privapp-permissions-ios-settings.xml", "/system/etc/permissions/privapp-permissions-ios-controlcenter.xml", "/system/etc/permissions/privapp-permissions-ios-dialer.xml", "/system/etc/permissions/privapp-permissions-ios-messages.xml", "/system/etc/default-permissions/default-permissions-ios-comms.xml", "/system/system_ext/overlay/SamuraiDialerOverlay.apk", "/system/system_ext/overlay/SamuraiICallOverlay.apk", "/system/system_ext/overlay/SamuraiMessagesOverlay.apk", "/system/system_ext/overlay/SamuraiGmailOverlay.apk", "/system/system_ext/overlay/SamuraiPhotosOverlay.apk", "/system/system_ext/overlay/SamuraiLauncherOverlay.apk", "/system/system_ext/overlay/SamuraiIos26WallpaperOverlay.apk");
EOF
fi

cat >> "${INC_STAGE}/META-INF/com/google/android/updater-script" <<'EOF'
run_program("/sbin/sh", "-c", "rm -rf /tmp/ota_payload && mkdir -p /tmp/ota_payload");
package_extract_file("ota_payload/SamuraiHardwareOverlay.apk", "/tmp/ota_payload/SamuraiHardwareOverlay.apk");
package_extract_file("ota_payload/SamuraiTelephonyOverlay.apk", "/tmp/ota_payload/SamuraiTelephonyOverlay.apk");
package_extract_file("ota_payload/SamuraiSystemUIOverlay.apk", "/tmp/ota_payload/SamuraiSystemUIOverlay.apk");
package_extract_file("ota_payload/qti_ims_telephony.tar.gz", "/tmp/ota_payload/qti_ims_telephony.tar.gz");
package_extract_file("ota_payload/qti_ims_fixperms.sh", "/tmp/ota_payload/qti_ims_fixperms.sh");
package_extract_file("ota_payload/SamuraiSettingsOverlay.apk", "/tmp/ota_payload/SamuraiSettingsOverlay.apk");
package_extract_file("ota_payload/SamuraiHidePillOverlay.apk", "/tmp/ota_payload/SamuraiHidePillOverlay.apk");
package_extract_file("ota_payload/SamuraiParts.apk", "/tmp/ota_payload/SamuraiParts.apk");
package_extract_file("ota_payload/SamuraiOTAUpdater.apk", "/tmp/ota_payload/SamuraiOTAUpdater.apk");
package_extract_file("ota_payload/NexusLauncherRelease.apk", "/tmp/ota_payload/NexusLauncherRelease.apk");
package_extract_file("ota_payload/SystemUI.apk", "/tmp/ota_payload/SystemUI.apk");
package_extract_file("ota_payload/services.jar", "/tmp/ota_payload/services.jar");
package_extract_file("ota_payload/gcastartup_uaf_fix.bin", "/tmp/ota_payload/gcastartup_uaf_fix.bin");
package_extract_file("ota_payload/com.qti.chi.override.so", "/tmp/ota_payload/com.qti.chi.override.so");
package_extract_file("ota_payload/fstab.qcom", "/tmp/ota_payload/fstab.qcom");
package_extract_file("ota_payload/media_codecs.xml", "/tmp/ota_payload/media_codecs.xml");
package_extract_file("ota_payload/media_codecs_c2.xml", "/tmp/ota_payload/media_codecs_c2.xml");
package_extract_file("ota_payload/media_codecs_performance_c2.xml", "/tmp/ota_payload/media_codecs_performance_c2.xml");
package_extract_file("ota_payload/media_codecs_performance.xml", "/tmp/ota_payload/media_codecs_performance.xml");
package_extract_file("ota_payload/powerhint.json", "/tmp/ota_payload/powerhint.json");
package_extract_file("ota_payload/privapp-permissions-samuraiparts.xml", "/tmp/ota_payload/privapp-permissions-samuraiparts.xml");
package_extract_file("ota_payload/default-permissions-samuraiparts.xml", "/tmp/ota_payload/default-permissions-samuraiparts.xml");
package_extract_file("ota_payload/privapp-permissions-ota.xml", "/tmp/ota_payload/privapp-permissions-ota.xml");
package_extract_file("ota_payload/default-permissions-ota.xml", "/tmp/ota_payload/default-permissions-ota.xml");
package_extract_file("ota_payload/privapp-permissions-pixel-launcher.xml", "/tmp/ota_payload/privapp-permissions-pixel-launcher.xml");
package_extract_file("ota_payload/default-permissions-pixel-launcher.xml", "/tmp/ota_payload/default-permissions-pixel-launcher.xml");
package_extract_file("ota_payload/default-permissions-gcam.xml", "/tmp/ota_payload/default-permissions-gcam.xml");
package_extract_file("ota_payload/pixel-contextual-search.xml", "/tmp/ota_payload/pixel-contextual-search.xml");
package_extract_file("ota_payload/samurai_hw_daemon", "/tmp/ota_payload/samurai_hw_daemon");
package_extract_file("ota_payload/samurai_ota_bcb", "/tmp/ota_payload/samurai_ota_bcb");
package_extract_file("ota_payload/samurai-mod-helper.jar", "/tmp/ota_payload/samurai-mod-helper.jar");
package_extract_file("ota_payload/udfps_icon_pressed.webp", "/tmp/ota_payload/udfps_icon_pressed.webp");
package_extract_file("ota_payload/libfod_zpos_hook.so", "/tmp/ota_payload/libfod_zpos_hook.so");
package_extract_file("ota_payload/samurai-mods-sync.sh", "/tmp/ota_payload/samurai-mods-sync.sh");
package_extract_file("ota_payload/samurai-gcam-sync.sh", "/tmp/ota_payload/samurai-gcam-sync.sh");
package_extract_file("ota_payload/release-mods.json", "/tmp/ota_payload/release-mods.json");
package_extract_file("ota_payload/bpfloader_wrap", "/tmp/ota_payload/bpfloader_wrap");
package_extract_file("ota_payload/netbpfload_414", "/tmp/ota_payload/netbpfload_414");
package_extract_file("ota_payload/bpf_netd_414.o", "/tmp/ota_payload/bpf_netd_414.o");
package_extract_file("ota_payload/libnetd_updatable_414.so", "/tmp/ota_payload/libnetd_updatable_414.so");
package_extract_file("ota_payload/libservice-connectivity_414.so", "/tmp/ota_payload/libservice-connectivity_414.so");
package_extract_file("ota_payload/libmeminfo.so", "/tmp/ota_payload/libmeminfo.so");
package_extract_file("ota_payload/libzygote_sys.dylib.so", "/tmp/ota_payload/libzygote_sys.dylib.so");
package_extract_file("ota_payload/libcap_rust.dylib.so", "/tmp/ota_payload/libcap_rust.dylib.so");
package_extract_file("ota_payload/libzygote_species_android_native.so", "/tmp/ota_payload/libzygote_species_android_native.so");
package_extract_file("ota_payload/pixel-boot.sh", "/tmp/ota_payload/pixel-boot.sh");
package_extract_file("ota_payload/ota-watcher.sh", "/tmp/ota_payload/ota-watcher.sh");
package_extract_file("ota_payload/ota-post-fs.sh", "/tmp/ota_payload/ota-post-fs.sh");
package_extract_file("ota_payload/netbpfload.rc", "/tmp/ota_payload/netbpfload.rc");
package_extract_file("ota_payload/zygote_next64.rc", "/tmp/ota_payload/zygote_next64.rc");
package_extract_file("ota_payload/ota.rc", "/tmp/ota_payload/ota.rc");
package_extract_file("ota_payload/samurai_parts.rc", "/tmp/ota_payload/samurai_parts.rc");
package_extract_file("ota_payload/init.samurai.perf.rc", "/tmp/ota_payload/init.samurai.perf.rc");
package_extract_file("ota_payload/init.samurai.bootguard.rc", "/tmp/ota_payload/init.samurai.bootguard.rc");
package_extract_file("ota_payload/plat_sepolicy.cil", "/tmp/ota_payload/plat_sepolicy.cil");
package_extract_file("ota_payload/plat_file_contexts", "/tmp/ota_payload/plat_file_contexts");
package_extract_file("ota_payload/plat_property_contexts", "/tmp/ota_payload/plat_property_contexts");
package_extract_file("ota_payload/compatibility_matrix.device.xml", "/tmp/ota_payload/compatibility_matrix.device.xml");
package_extract_file("ota_payload/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc", "/tmp/ota_payload/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc");
package_extract_file("ota_payload/android.hardware.graphics.composer@2.4-service.rc", "/tmp/ota_payload/android.hardware.graphics.composer@2.4-service.rc");
package_extract_file("ota_payload/vendor.qti.adsprpc-service.rc", "/tmp/ota_payload/vendor.qti.adsprpc-service.rc");
package_extract_file("ota_payload/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc", "/tmp/ota_payload/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc");
package_extract_file("ota_payload/libgf_hal_G3.so", "/tmp/ota_payload/libgf_hal_G3.so");
package_extract_file("ota_payload/vendor_sepolicy.cil", "/tmp/ota_payload/vendor_sepolicy.cil");
package_extract_file("ota_payload/vendor_property_contexts", "/tmp/ota_payload/vendor_property_contexts");
package_extract_file("ota_payload/apply_ota.sh", "/tmp/ota_payload/apply_ota.sh");
EOF

if [ "${ROM_FLAVOR}" = "ios-beta" ]; then
    cat >> "${INC_STAGE}/META-INF/com/google/android/updater-script" <<'EOF'
package_extract_file("ota_payload/SamuraiDialerOverlay.apk", "/tmp/ota_payload/SamuraiDialerOverlay.apk");
package_extract_file("ota_payload/SamuraiMessagesOverlay.apk", "/tmp/ota_payload/SamuraiMessagesOverlay.apk");
package_extract_file("ota_payload/SamuraiGmailOverlay.apk", "/tmp/ota_payload/SamuraiGmailOverlay.apk");
package_extract_file("ota_payload/SamuraiPhotosOverlay.apk", "/tmp/ota_payload/SamuraiPhotosOverlay.apk");
package_extract_file("ota_payload/SamuraiLauncherOverlay.apk", "/tmp/ota_payload/SamuraiLauncherOverlay.apk");
package_extract_file("ota_payload/SamuraiIos26WallpaperOverlay.apk", "/tmp/ota_payload/SamuraiIos26WallpaperOverlay.apk");
package_extract_file("ota_payload/IosStyler.apk", "/tmp/ota_payload/IosStyler.apk");
package_extract_file("ota_payload/IosSettings.apk", "/tmp/ota_payload/IosSettings.apk");
package_extract_file("ota_payload/IosControlCenter.apk", "/tmp/ota_payload/IosControlCenter.apk");
package_extract_file("ota_payload/IosDialer.apk", "/tmp/ota_payload/IosDialer.apk");
package_extract_file("ota_payload/IosMessages.apk", "/tmp/ota_payload/IosMessages.apk");
package_extract_file("ota_payload/privapp-permissions-styler.xml", "/tmp/ota_payload/privapp-permissions-styler.xml");
package_extract_file("ota_payload/privapp-permissions-ios-settings.xml", "/tmp/ota_payload/privapp-permissions-ios-settings.xml");
package_extract_file("ota_payload/privapp-permissions-ios-controlcenter.xml", "/tmp/ota_payload/privapp-permissions-ios-controlcenter.xml");
package_extract_file("ota_payload/privapp-permissions-ios-dialer.xml", "/tmp/ota_payload/privapp-permissions-ios-dialer.xml");
package_extract_file("ota_payload/privapp-permissions-ios-messages.xml", "/tmp/ota_payload/privapp-permissions-ios-messages.xml");
package_extract_file("ota_payload/default-permissions-ios-comms.xml", "/tmp/ota_payload/default-permissions-ios-comms.xml");
EOF
fi

if [ "${OTA_FULL_CUMULATIVE}" = "1" ]; then
    cat >> "${INC_STAGE}/META-INF/com/google/android/updater-script" <<'EOF'
package_extract_file("ota_payload/WallpaperPickerGoogleRelease.apk", "/tmp/ota_payload/WallpaperPickerGoogleRelease.apk");
package_extract_file("ota_payload/PixelThemesStub2022_and_newer.apk", "/tmp/ota_payload/PixelThemesStub2022_and_newer.apk");
package_extract_file("ota_payload/GoogleCameraEng.apk", "/tmp/ota_payload/GoogleCameraEng.apk");
package_extract_file("ota_payload/photos-gsi-dex.zip", "/tmp/ota_payload/photos-gsi-dex.zip");
EOF
fi

cat >> "${INC_STAGE}/META-INF/com/google/android/updater-script" <<'EOF'
ui_print("Applying Android 17 Pixel OS Wireless OTA Update...");
show_progress(0.700000, 12);
run_program("/sbin/sh", "-c", "chmod 0755 /tmp/ota_payload/apply_ota.sh && /sbin/sh /tmp/ota_payload/apply_ota.sh") == 0 ||
  abort("E1001: Failed to apply cumulative Android 17 Pixel OS OTA.");
run_program("/sbin/sh", "-c", "if [ -d /data/system ]; then rm -rf /data/resource-cache/* /data/system/package_cache/* /data/data/com.google.android.apps.nexuslauncher/databases/app_icons.db* /data/user_de/0/com.google.android.apps.nexuslauncher/databases/app_icons.db* 2>/dev/null || true; fi");
set_progress(1.000000);
ui_print("OTA update complete! Rebooting to Android 17...");
EOF
sed -i 's/\r$//' "${INC_STAGE}/META-INF/com/google/android/updater-script"

# 4. Sync latest built APKs/binaries/frameworks/manifest to WIN_OUT, Package Zip & Update Manifest
rm -f "${WIN_OUT}/ICall26.apk" "${WIN_OUT}/ICall26.apk.idsig" \
      "${WIN_OUT}/SamuraiICallOverlay.apk" "${WIN_OUT}/SamuraiICallOverlay.apk.idsig" \
      "${WIN_OUT}/IosClock.apk" "${WIN_OUT}/IosClock.apk.idsig" \
      "${WIN_OUT}/IosCalculator.apk" "${WIN_OUT}/IosCalculator.apk.idsig" \
      "${WIN_OUT}/IosNotes.apk" "${WIN_OUT}/IosNotes.apk.idsig" \
      "${WIN_OUT}/IosWeather.apk" "${WIN_OUT}/IosWeather.apk.idsig" \
      "${WIN_OUT}/SystemUI.apk" "${WIN_OUT}/SystemUI.apk.idsig"
if [ "${ROM_FLAVOR}" = "pixelos" ]; then
    rm -f "${WIN_OUT}"/Ios*.apk "${WIN_OUT}"/Ios*.apk.idsig \
          "${WIN_OUT}/ICall26.apk" "${WIN_OUT}/ICall26.apk.idsig" \
          "${WIN_OUT}/SamuraiDialerOverlay.apk" "${WIN_OUT}/SamuraiDialerOverlay.apk.idsig" \
          "${WIN_OUT}/SamuraiICallOverlay.apk" "${WIN_OUT}/SamuraiICallOverlay.apk.idsig" \
          "${WIN_OUT}/SamuraiMessagesOverlay.apk" "${WIN_OUT}/SamuraiMessagesOverlay.apk.idsig" \
          "${WIN_OUT}/SamuraiGmailOverlay.apk" "${WIN_OUT}/SamuraiGmailOverlay.apk.idsig" \
          "${WIN_OUT}/SamuraiPhotosOverlay.apk" "${WIN_OUT}/SamuraiPhotosOverlay.apk.idsig" \
          "${WIN_OUT}/SamuraiLauncherOverlay.apk" "${WIN_OUT}/SamuraiLauncherOverlay.apk.idsig" \
          "${WIN_OUT}/SamuraiIos26WallpaperOverlay.apk" "${WIN_OUT}/SamuraiIos26WallpaperOverlay.apk.idsig"
    cp -f "${APKS_DIR}/"*.apk "${WIN_OUT}/"
    cp -f "${BIN_DIR}/"* "${WIN_OUT}/"
    cp -f "${WORK_ROOT}/out/framework/services.jar" "${WIN_OUT}/services.jar"
    [ -f "${WORK_ROOT}/out/BUILD-MANIFEST.json" ] && cp -f "${WORK_ROOT}/out/BUILD-MANIFEST.json" "${WIN_OUT}/BUILD-MANIFEST.json"
fi

FINAL_ZIP="${WIN_OUT}/${ROM_FILENAME}"
rm -f "${FINAL_ZIP}"
(cd "${INC_STAGE}" && zip -q -r -9 "${FINAL_ZIP}" .)
unzip -t "${FINAL_ZIP}" >/dev/null

ZIP_SIZE=$(stat -c%s "${FINAL_ZIP}")
[ "${ZIP_SIZE}" -lt 398458880 ] || { echo "ERROR: Bridge/Incremental OTA ZIP (${ZIP_SIZE} B) exceeds 380 MiB /cache safe staging limit!"; exit 1; }
ZIP_SHA256="$(sha256sum "${FINAL_ZIP}" | awk '{print $1}')"

if [ "${ROM_FLAVOR}" = "ios-beta" ]; then
    [ -f "${WORK_ROOT}/out/BUILD-MANIFEST.json" ] && cp -f "${WORK_ROOT}/out/BUILD-MANIFEST.json" "${WIN_OUT}/BUILD-MANIFEST-ios-beta.json"
    BETA_SUM_TARGETS=("${ROM_FILENAME}")
    [ -f "${WIN_OUT}/BUILD-MANIFEST-ios-beta.json" ] && BETA_SUM_TARGETS+=("BUILD-MANIFEST-ios-beta.json")
    if [ -f "${WIN_OUT}/RMX1931-Android17-iOSBeta-System-${OTA_VERSION}.zip" ]; then
        BETA_SUM_TARGETS+=("RMX1931-Android17-iOSBeta-System-${OTA_VERSION}.zip")
    fi
    (cd "${WIN_OUT}" && sha256sum "${BETA_SUM_TARGETS[@]}" > SHA256SUMS-ios-beta.txt)
else
    SUM_TARGETS=(
        "${ROM_FILENAME}"
        "services.jar"
        "NexusLauncherRelease.apk"
        "SamuraiOTAUpdater.apk"
        "SamuraiParts.apk"
        "SamuraiHardwareOverlay.apk"
        "SamuraiTelephonyOverlay.apk"
        "SamuraiSystemUIOverlay.apk"
        "SamuraiSettingsOverlay.apk"
        "SamuraiHidePillOverlay.apk"
        "samurai_hw_daemon"
        "samurai_ota_bcb"
        "BUILD-MANIFEST.json"
    )
    if [ -f "${WIN_OUT}/RMX1931-Android17-PixelOS-VendorBoot.zip" ]; then
        SUM_TARGETS+=("RMX1931-Android17-PixelOS-VendorBoot.zip")
    fi
    if [ -f "${WIN_OUT}/RMX1931-Android17-PixelOS-System.zip" ]; then
        SUM_TARGETS+=("RMX1931-Android17-PixelOS-System.zip")
    fi
    (cd "${WIN_OUT}" && sha256sum "${SUM_TARGETS[@]}" > SHA256SUMS.txt)
fi
OTA_DL_URL="https://github.com/${OTA_GITHUB_REPO}/releases/download/${OTA_RELEASE_TAG}/${ROM_FILENAME}"

python3 - "${MANIFEST_OUT}" "${OTA_BUILD_DATETIME}" "${ROM_FILENAME}" "${ZIP_SHA256}" "${ZIP_SIZE}" "${OTA_DL_URL}" "${OTA_VERSION}" "${OTA_RELEASE_TAG}" "${OTA_CHANNEL}" <<'PYEOF'
import json, os, shutil, sys
out_path, dt, fname, sha, size, url, ver, tag, channel = sys.argv[1:]
if channel == "ios_beta":
    changelog = (
        f"What's New in Pixel OS {ver} (iOS Style UI Beta):\n"
        "• Fixed Dual SIM: Both SIM cards now work together reliably for calls, SMS, and mobile data.\n"
        "• Fixed Calls, SMS & OTPs: Sending text messages, receiving bank/login OTPs, and making HD voice calls (including on Jio 4G/5G) now work properly.\n"
        "• Fixed Phone App Warning: Removed the \"This device isn't supported\" warning banner when opening the Phone app after a restart.\n"
        "• Fixed PhonePe & Banking Apps: Stopped PhonePe and other UPI/banking apps from randomly closing or crashing when you open or switch back to them.\n"
        "• Fixed Double-Tap to Lock: Double-tapping empty space on the Home Screen now locks the phone right away.\n"
        "• Improved Fingerprint & Lock Screen Unlock: Faster single-swipe lock screen PIN entry and smoother in-display fingerprint unlock."
    )
else:
    changelog = (
        f"What's New in Pixel OS {ver}:\n"
        "• Fixed Dual SIM: Both SIM cards now work together reliably for calls, SMS, and mobile data.\n"
        "• Fixed Calls, SMS & OTPs: Sending text messages, receiving bank/login OTPs, and making HD voice calls (including on Jio 4G/5G) now work properly.\n"
        "• Fixed Phone App Warning: Removed the \"This device isn't supported\" warning banner when opening the Phone app after a restart.\n"
        "• Fixed PhonePe & Banking Apps: Stopped PhonePe and other UPI/banking apps from randomly closing or crashing when you open or switch back to them.\n"
        "• Fixed Double-Tap to Lock: Double-tapping empty space on the Home Screen now locks the phone right away.\n"
        "• Improved Fingerprint Unlock: Smoother and more reliable in-display fingerprint recognition."
    )

manifest = {
    "response": [
        {
            "datetime": int(dt),
            "filename": fname,
            "id": sha,
            "sha256": sha,
            "romtype": "PIXELOS",
            "channel": channel,
            "size": int(size),
            "url": url,
            "version": ver,
            "tag": tag,
            "device": "RMX1931",
            "changelog": changelog,
        }
    ]
}
with open(out_path, "w", encoding="utf-8", newline="\n") as f:
    json.dump(manifest, f, indent=2)
    f.write("\n")
if channel == "stable":
    stable_sub = os.path.join(os.path.dirname(out_path), "stable", "rmx1931.json")
    os.makedirs(os.path.dirname(stable_sub), exist_ok=True)
    shutil.copyfile(out_path, stable_sub)
    print(f"[ota-inc] Mirrored compact Stable OTA manifest -> {stable_sub}")
print(f"[ota-inc] Built {fname} ({int(size)/(1024*1024):.2f} MiB, channel={channel}, sha256={sha})")
PYEOF
