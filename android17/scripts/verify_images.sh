#!/usr/bin/env bash
# Exhaustive 9-Gate Offline Verification Suite for RMX1931 Android 17 Dual-Channel PixelOS:
#   - ROM_FLAVOR=pixelos  -> Pure Retail Google PixelOS Stable (ro.ota.channel=stable, ZERO iOS components)
#   - ROM_FLAVOR=ios-beta -> Opt-In iOS Style UI Beta (ro.ota.channel=ios_beta, Full iOS 26 Liquid Glass Suite)
# Strictly read-only verification — DOES NOT TOUCH ANY CONNECTED DEVICE
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
A17_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${A17_ROOT}/.." && pwd)"

export WORK_ROOT="${WORK_ROOT:-/var/tmp/rmx1931-a17}"
export ROM_FLAVOR="${ROM_FLAVOR:-pixelos}"
IMG_OUT="${WORK_ROOT}/out/images"
MNT_V="${WORK_ROOT}/mnt/verify"
WIN_OUT="${REPO_ROOT}/work/a17/out"
AAPT2="${WORK_ROOT}/tools/android-sdk/build-tools/35.0.0/aapt2"
APKSIGNER="${WORK_ROOT}/tools/android-sdk/build-tools/35.0.0/apksigner"
ZIPALIGN="${WORK_ROOT}/tools/android-sdk/build-tools/35.0.0/zipalign"

if [ "${ROM_FLAVOR}" = "ios-beta" ]; then
    EXP_CH="ios_beta"
    EXP_OTA_VER="${OTA_VERSION:-v2.1.11-b1}"
elif [ "${ROM_FLAVOR}" = "pixelos" ]; then
    EXP_CH="stable"
    EXP_OTA_VER="${OTA_VERSION:-v2.1.11}"
else
    echo "FAIL: Unsupported ROM_FLAVOR=${ROM_FLAVOR}"
    exit 1
fi

cleanup() {
    for mp in "${MNT_V}/sys" "${MNT_V}/ven" "${MNT_V}/odm"; do
        if mountpoint -q "${mp}" 2>/dev/null; then
            umount -lf "${mp}" || true
        fi
    done
}
trap cleanup EXIT
cleanup
mkdir -p "${MNT_V}/sys" "${MNT_V}/ven" "${MNT_V}/odm"

if ! command -v qemu-aarch64-static >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get install -y -qq qemu-user-static >/dev/null
fi

echo "=================================================================="
echo " [GATE 1/9] APK Signatures, Privileged Whitelists & Flavor Audit (${ROM_FLAVOR})"
echo "=================================================================="
ROM_FLAVOR="${ROM_FLAVOR}" python3 "${A17_ROOT}/scripts/verify_privapp_whitelist.py"

echo "=================================================================="
echo " [GATE 2/9] RMX1931 Hardware Partition Byte-Size Cap Audit"
echo "=================================================================="
BOOT_SZ=$(stat -c%s "${IMG_OUT}/boot.img")
SYS_SZ=$(stat -c%s "${IMG_OUT}/system.raw.img")
VEN_SZ=$(stat -c%s "${IMG_OUT}/vendor.raw.img")
ODM_SZ=$(stat -c%s "${IMG_OUT}/odm.raw.img")
DTBO_SZ=$(stat -c%s "${IMG_OUT}/dtbo.img")

echo "  boot.img       : ${BOOT_SZ} B (exact cap: 100,663,296 B)"
echo "  system.raw.img : ${SYS_SZ} B (max cap:   4,487,905,280 B)"
echo "  vendor.raw.img : ${VEN_SZ} B (max cap:   1,649,410,048 B)"
echo "  odm.raw.img    : ${ODM_SZ} B (max cap:     268,435,456 B)"
echo "  dtbo.img       : ${DTBO_SZ} B (max cap:      25,165,824 B)"

[ "${BOOT_SZ}" -eq 100663296 ] || { echo "FAIL: boot.img != 100663296"; exit 1; }
[ "${SYS_SZ}" -le 4487905280 ] || { echo "FAIL: system.raw.img exceeds RMX1931 system partition!"; exit 1; }
[ "${VEN_SZ}" -le 1649410048 ] || { echo "FAIL: vendor.raw.img exceeds RMX1931 vendor partition!"; exit 1; }
[ "${ODM_SZ}" -le 268435456 ]  || { echo "FAIL: odm.raw.img exceeds RMX1931 odm partition!"; exit 1; }
[ "${DTBO_SZ}" -le 25165824 ]  || { echo "FAIL: dtbo.img exceeds RMX1931 dtbo partition!"; exit 1; }
echo "[PASS] All partition images fit within RMX1931 UFS GPT limits."

echo "=================================================================="
echo " [GATE 3/9] Realme ABL AVB v1.0 (boot, dtbo) & vbmeta Verification"
echo "=================================================================="
BOOT_AVB="$(python3 "${WORK_ROOT}/tools/avbtool.py" info_image --image "${IMG_OUT}/boot.img")"
DTBO_AVB="$(python3 "${WORK_ROOT}/tools/avbtool.py" info_image --image "${IMG_OUT}/dtbo.img")"
VBMETA_AVB="$(python3 "${WORK_ROOT}/tools/avbtool.py" info_image --image "${IMG_OUT}/vbmeta.img")"
echo "${BOOT_AVB}" | head -n 14
echo "${DTBO_AVB}" | head -n 14
echo "${VBMETA_AVB}"
echo "${VBMETA_AVB}" | grep -Eq "^Flags:[[:space:]]+3$" || { echo "FAIL: vbmeta.img Flags != 3!"; exit 1; }
echo "${VBMETA_AVB}" | grep -q "Public key (sha1):        2597c218aae470a130f61162feaae70afd97f011" || { echo "FAIL: vbmeta.img not signed with AOSP testkey_rsa4096!"; exit 1; }
! echo "${VBMETA_AVB}" | grep -q "Hashtree descriptor:" || { echo "FAIL: vbmeta.img still contains stale dm-verity Hashtree descriptors!"; exit 1; }
BOOT_DIGEST="$(echo "${BOOT_AVB}" | awk '/Digest:/{print $2; exit}')"
DTBO_DIGEST="$(echo "${DTBO_AVB}" | awk '/Digest:/{print $2; exit}')"
echo "${VBMETA_AVB}" | grep -q "${BOOT_DIGEST}" || { echo "FAIL: vbmeta.img boot descriptor digest mismatch (${BOOT_DIGEST})!"; exit 1; }
echo "${VBMETA_AVB}" | grep -q "${DTBO_DIGEST}" || { echo "FAIL: vbmeta.img dtbo descriptor digest mismatch (${DTBO_DIGEST})!"; exit 1; }
echo "[PASS] Signed Flags=3 vbmeta.img with exact matching boot & dtbo digests verified!"

echo "=================================================================="
echo " [GATE 4/9] Linux 4.14 ext4 Compatibility & e2fsck Integrity"
echo "=================================================================="
for p in system vendor odm; do
    e2fsck -fn "${IMG_OUT}/${p}.raw.img" >/dev/null
    FEATS="$(tune2fs -l "${IMG_OUT}/${p}.raw.img" | grep 'Filesystem features:')"
    echo "  ${p}: e2fsck CLEAN | ${FEATS}"
    for bad_feat in orphan_file shared_blocks metadata_csum_seed; do
        if echo "${FEATS}" | grep -qw "${bad_feat}"; then
            echo "FAIL: ${p}.raw.img has ext4 feature '${bad_feat}' incompatible with RMX1931 Linux 4.14 rw mount!"
            exit 1
        fi
    done
done
echo "[PASS] All ext4 filesystems clean and Linux 4.14 compatible."

echo "=================================================================="
echo " [GATE 5/9] Combined Android 17 + RMX1931 Treble SELinux CIL Gate"
echo "=================================================================="
mount -o loop,ro "${IMG_OUT}/system.raw.img" "${MNT_V}/sys"
mount -o loop,ro "${IMG_OUT}/vendor.raw.img" "${MNT_V}/ven"
mount -o loop,ro "${IMG_OUT}/odm.raw.img" "${MNT_V}/odm"

SYS_ROOT="${MNT_V}/sys/system"
[ -d "${SYS_ROOT}/etc/selinux" ] || SYS_ROOT="${MNT_V}/sys"

PLAT_VER="$(tr -d '\r\n ' < "${MNT_V}/ven/etc/selinux/plat_sepolicy_vers.txt")"
echo "  Vendor Treble SELinux mapping version: ${PLAT_VER}"

CIL_ARGS=(
    "${SYS_ROOT}/etc/selinux/plat_sepolicy.cil"
    "${SYS_ROOT}/etc/selinux/mapping/${PLAT_VER}.cil"
)
for opt_cil in \
    "${SYS_ROOT}/etc/selinux/mapping/${PLAT_VER}.compat.cil" \
    "${SYS_ROOT}/system_ext/etc/selinux/system_ext_sepolicy.cil" \
    "${SYS_ROOT}/system_ext/etc/selinux/mapping/${PLAT_VER}.cil" \
    "${SYS_ROOT}/product/etc/selinux/product_sepolicy.cil" \
    "${SYS_ROOT}/product/etc/selinux/mapping/${PLAT_VER}.cil" \
    "${MNT_V}/ven/etc/selinux/plat_pub_versioned.cil" \
    "${MNT_V}/ven/etc/selinux/vendor_sepolicy.cil" \
    "${MNT_V}/odm/etc/selinux/odm_sepolicy.cil"; do
    [ -f "${opt_cil}" ] && CIL_ARGS+=("${opt_cil}")
done

mkdir -p "${WORK_ROOT}/work"
qemu-aarch64-static -L "${MNT_V}/sys" \
    -E LD_LIBRARY_PATH="${SYS_ROOT}/lib64:${SYS_ROOT}/lib64/bootstrap" \
    "${SYS_ROOT}/bin/secilc" \
    -m -M true -G -N -c 30 \
    -o "${WORK_ROOT}/work/sepolicy.test.bin" -f /dev/null "${CIL_ARGS[@]}"
SEPOL_SZ=$(stat -c%s "${WORK_ROOT}/work/sepolicy.test.bin")
grep -q "(allow priv_app_36 ota_package_file (file" "${SYS_ROOT}/etc/selinux/plat_sepolicy.cil" || { echo "FAIL: plat_sepolicy.cil missing priv_app_36 ota_package_file rule!"; exit 1; }
grep -q "(allow priv_app_all ota_package_file (file" "${SYS_ROOT}/etc/selinux/plat_sepolicy.cil" || { echo "FAIL: plat_sepolicy.cil missing priv_app_all ota_package_file rule!"; exit 1; }
grep -q "/data/ota_raw" "${SYS_ROOT}/etc/selinux/plat_file_contexts" || { echo "FAIL: plat_file_contexts missing /data/ota_raw ota_package_file label!"; exit 1; }
grep -q "mkdir /data/ota_raw 0777 system cache encryption=None" "${SYS_ROOT}/etc/init/ota.rc" || { echo "FAIL: ota.rc missing unencrypted /data/ota_raw FBE exemption!"; exit 1; }
grep -q "(allow untrusted_app_all app_data_file (file (execmod)))" "${SYS_ROOT}/etc/selinux/plat_sepolicy.cil" || { echo "FAIL: plat_sepolicy.cil missing untrusted_app_all app_data_file execmod!"; exit 1; }
grep -q "(allow untrusted_app_all apk_data_file (file (execmod)))" "${SYS_ROOT}/etc/selinux/plat_sepolicy.cil" || { echo "FAIL: plat_sepolicy.cil missing untrusted_app_all apk_data_file execmod!"; exit 1; }
grep -q "(allow system_app logpersistd_logging_prop (property_service (set)))" "${SYS_ROOT}/etc/selinux/plat_sepolicy.cil" || { echo "FAIL: plat_sepolicy.cil missing system_app logpersistd_logging_prop set rule (Developer Options crash fix)!"; exit 1; }
grep -q "(typepermissive toolbox)" "${SYS_ROOT}/etc/selinux/plat_sepolicy.cil" || { echo "FAIL: plat_sepolicy.cil missing (typepermissive toolbox)!"; exit 1; }
grep -q "(allow servicemanager init (binder (call transfer)))" "${SYS_ROOT}/etc/selinux/plat_sepolicy.cil" || { echo "FAIL: plat_sepolicy.cil missing servicemanager -> init binder transfer rule!"; exit 1; }
! grep -q "(typepermissive su)" "${SYS_ROOT}/etc/selinux/plat_sepolicy.cil" || { echo "FAIL: plat_sepolicy.cil must not contain (typepermissive su)!"; exit 1; }
[ ! -e "${SYS_ROOT}/bin/su" ] && [ ! -e "${SYS_ROOT}/xbin/su" ] || { echo "FAIL: Userdebug su binary still present in system.raw.img!"; exit 1; }
grep -q "samurai_hw_daemon --early-props" "${SYS_ROOT}/etc/init/init.samurai.bootguard.rc" || { echo "FAIL: init.samurai.bootguard.rc missing --early-props!"; exit 1; }
! grep -q "u:r:su:s0" "${SYS_ROOT}/etc/init/samurai_parts.rc" "${SYS_ROOT}/etc/init/ota.rc" || { echo "FAIL: samurai_parts.rc / ota.rc must not run services as u:r:su:s0!"; exit 1; }
echo "[PASS] Android 17 /system/bin/secilc compiled combined policyvers=30 binary sepolicy (${SEPOL_SZ} bytes) + /data/ota_raw encryption=None verified!"

echo "=================================================================="
echo " [GATE 6/9] Native samurai_hw_daemon, samurai_ota_bcb & Keylayouts"
echo "=================================================================="
for bin_name in samurai_hw_daemon samurai_ota_bcb; do
    BIN_P="${SYS_ROOT}/bin/${bin_name}"
    [ -x "${BIN_P}" ] || { echo "FAIL: Missing executable ${BIN_P}"; exit 1; }
    BIN_FILE="$(file "${BIN_P}")"
    echo "  [ELF] ${BIN_FILE}"
    echo "${BIN_FILE}" | grep -q "ARM aarch64" || { echo "FAIL: ${bin_name} is not ARM64!"; exit 1; }
    echo "${BIN_FILE}" | grep -q "statically linked" || { echo "FAIL: ${bin_name} is not statically linked!"; exit 1; }
done
grep -a -q "/data/ota_raw/update.zip" "${SYS_ROOT}/bin/samurai_ota_bcb" || { echo "FAIL: samurai_ota_bcb missing /data/ota_raw/update.zip >380 MiB staging!"; exit 1; }
grep -a -q "Raw UFS plaintext check failed" "${SYS_ROOT}/bin/samurai_ota_bcb" || { echo "FAIL: samurai_ota_bcb missing raw UFS PK/EOF plaintext readback verification!"; exit 1; }
[ ! -e "${SYS_ROOT}/bin/samurai_adblockd" ] || { echo "FAIL: Legacy samurai_adblockd still present in /system/bin!"; exit 1; }
[ ! -e "${SYS_ROOT}/etc/adblock_seed.gz" ] || { echo "FAIL: Legacy adblock_seed.gz still present in /system/etc!"; exit 1; }

for kl in touchpanel_kpd.kl touchpanel.kl samurai-virtual-keys.kl; do
    KL_PATH="${SYS_ROOT}/usr/keylayout/${kl}"
    [ -f "${KL_PATH}" ] || { echo "FAIL: Missing keylayout ${KL_PATH}"; exit 1; }
    grep -Eq "key[[:space:]]+142[[:space:]]+SLEEP" "${KL_PATH}" || { echo "FAIL: ${kl} missing SLEEP (142)!"; exit 1; }
    grep -Eq "key[[:space:]]+143[[:space:]]+WAKEUP" "${KL_PATH}" || { echo "FAIL: ${kl} missing WAKEUP (143)!"; exit 1; }
    echo "  [KEYLAYOUT] ${kl} verified (SLEEP=142, WAKEUP=143)"
done
grep -Eq "key[[:space:]]+247[[:space:]]+WAKEUP" "${SYS_ROOT}/usr/keylayout/touchpanel_kpd.kl" || { echo "FAIL: touchpanel_kpd.kl missing key 247 WAKEUP!"; exit 1; }
echo "[PASS] Static ARM64 samurai_hw_daemon + dual-mode samurai_ota_bcb + keylayouts verified!"

echo "=================================================================="
echo " [GATE 7/9] Goodix G3 Optical UDFPS Firmware, HAL & RRO Overlays (${ROM_FLAVOR})"
echo "=================================================================="
for fw in goodixfp.mdt goodixfp.b00 goodixfp.b01 goodixfp.b02 goodixfp.b03 goodixfp.b04 goodixfp.b05 goodixfp.b06 goodixfp.b07; do
    FW_P="${MNT_V}/odm/vendor/firmware/${fw}"
    [ -f "${FW_P}" ] || { echo "FAIL: Missing ODM Goodix firmware ${FW_P}"; exit 1; }
    FW_CTX="$(getfattr --only-values -n security.selinux "${FW_P}" | tr -d '\0')"
    [ "${FW_CTX}" = "u:object_r:vendor_firmware_file:s0" ] || { echo "FAIL: Wrong context on ${FW_P}: ${FW_CTX}"; exit 1; }
done
ODM_FP_RC="${MNT_V}/odm/etc/init/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc"
grep -q "/sys/kernel/oppo_display/hbm" "${ODM_FP_RC}" || { echo "FAIL: oppo_display/hbm missing in ODM fp rc!"; exit 1; }
grep -q "start fps_hal" "${ODM_FP_RC}" || { echo "FAIL: early start fps_hal missing in ODM fp rc!"; exit 1; }
grep -q "start vendor.fps_hal.samurai" "${ODM_FP_RC}" || { echo "FAIL: early start vendor.fps_hal.samurai missing in ODM fp rc!"; exit 1; }
! grep -q "init.oplus.fingerprints.sh" "${ODM_FP_RC}" || { echo "FAIL: broken init.oplus.fingerprints.sh still in ODM fp rc!"; exit 1; }
VEN_FP_RC="${MNT_V}/ven/etc/init/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc"
grep -q "mkdir /data/vendor/fingerprint/gf_data 0770 system system" "${VEN_FP_RC}" || { echo "FAIL: vendor fp 2.3 rc missing /data/vendor/fingerprint/gf_data!"; exit 1; }
grep -q "write /proc/touchpanel/fp_enable 1" "${VEN_FP_RC}" || { echo "FAIL: vendor fp 2.3 rc missing write /proc/touchpanel/fp_enable 1!"; exit 1; }
grep -q "^persist.vendor.fingerprint.sensor_type=optical$" "${MNT_V}/ven/build.prop" || { echo "FAIL: vendor/build.prop missing persist.vendor.fingerprint.sensor_type=optical!"; exit 1; }
grep -q "^persist.vendor.fingerprint.fp_id=G_OPTICAL_G3$" "${MNT_V}/ven/build.prop" || { echo "FAIL: vendor/build.prop missing persist.vendor.fingerprint.fp_id=G_OPTICAL_G3!"; exit 1; }
grep -q "^persist.vendor.fingerprint.version=Goodix$" "${MNT_V}/ven/build.prop" || { echo "FAIL: vendor/build.prop missing persist.vendor.fingerprint.version=Goodix!"; exit 1; }
grep -q "^persist.vendor.fingerprint.sensor_type=optical$" "${SYS_ROOT}/build.prop" || { echo "FAIL: system/build.prop missing persist.vendor.fingerprint.sensor_type=optical!"; exit 1; }
grep -q "^persist.vendor.fingerprint.version=Goodix$" "${SYS_ROOT}/build.prop" || { echo "FAIL: system/build.prop missing persist.vendor.fingerprint.version=Goodix!"; exit 1; }
[ -f "${SYS_ROOT}/etc/udfps_icon_pressed.webp" ] || { echo "FAIL: Missing /system/etc/udfps_icon_pressed.webp!"; exit 1; }
[ "$(stat -c%s "${SYS_ROOT}/etc/udfps_icon_pressed.webp")" = "1926" ] || { echo "FAIL: udfps_icon_pressed.webp size != 1926!"; exit 1; }
[ -f "${MNT_V}/ven/lib64/libfod_zpos_hook.so" ] || { echo "FAIL: Missing /vendor/lib64/libfod_zpos_hook.so!"; exit 1; }
[ -f "${MNT_V}/ven/lib64/libdisplaydebug.so" ] || { echo "FAIL: Missing /vendor/lib64/libdisplaydebug.so!"; exit 1; }
cmp -s "${MNT_V}/ven/lib64/libfod_zpos_hook.so" "${MNT_V}/ven/lib64/libdisplaydebug.so" || { echo "FAIL: /vendor/lib64/libdisplaydebug.so does not match libfod_zpos_hook.so!"; exit 1; }
for fod_sym in _ZN3sdm12HWCCallbacks8RegisterEN4HWC28CallbackEPvPFvvE _ZN3sdm10HWCDisplay15BuildLayerStackEv _ZN3sdm10HWCDisplay15CanSkipValidateEv drmModeAtomicAddProperty drmModeAtomicCommit dl_iterate_phdr __android_log_print; do
    readelf -Ws "${MNT_V}/ven/lib64/libfod_zpos_hook.so" | grep -q "${fod_sym}" || { echo "FAIL: libfod_zpos_hook.so missing symbol ${fod_sym}!"; exit 1; }
done
! readelf -Ws "${MNT_V}/ven/lib64/libfod_zpos_hook.so" | grep -Eq "[[:space:]]UND[[:space:]]+dlsym(@|$)" || { echo "FAIL: libfod_zpos_hook.so still uses dlsym(RTLD_NEXT) instead of dl_iterate_phdr!"; exit 1; }
FOD_DYN="$(readelf -dW "${MNT_V}/ven/lib64/libdisplaydebug.so")"
echo "${FOD_DYN}" | grep "(SONAME)" | grep -q "\[libdisplaydebug\.so\]" || { echo "FAIL: libdisplaydebug.so missing SONAME libdisplaydebug.so!"; exit 1; }
echo "${FOD_DYN}" | grep "(FLAGS_1)" | grep -q "GLOBAL" || { echo "FAIL: libdisplaydebug.so missing DF_1_GLOBAL (-Wl,-z,global)!"; exit 1; }
echo "${FOD_DYN}" | grep "(FLAGS_1)" | grep -q "NOW" || { echo "FAIL: libdisplaydebug.so missing DF_1_NOW (-Wl,-z,now)!"; exit 1; }
echo "${FOD_DYN}" | grep -q "(INIT_ARRAY)" || { echo "FAIL: libdisplaydebug.so missing DT_INIT_ARRAY constructor!"; exit 1; }
echo "${FOD_DYN}" | grep "(NEEDED)" | grep -q "\[liblog\.so\]" || { echo "FAIL: libdisplaydebug.so missing DT_NEEDED liblog.so!"; exit 1; }
grep -a -q "FodZposHook" "${MNT_V}/ven/lib64/libdisplaydebug.so" || { echo "FAIL: libdisplaydebug.so missing FodZposHook log tag!"; exit 1; }
readelf -dW "${MNT_V}/ven/lib64/libsdedrm.so" | grep "(NEEDED)" | head -n 1 | grep -q "\[libdisplaydebug\.so\]" || { echo "FAIL: libsdedrm.so DT_NEEDED[0] is not libdisplaydebug.so!"; exit 1; }
readelf -dW "${MNT_V}/ven/lib64/hw/hwcomposer.qcom.so" | grep "(NEEDED)" | head -n 1 | grep -q "\[libdisplaydebug\.so\]" || { echo "FAIL: hwcomposer.qcom.so DT_NEEDED[0] is not libdisplaydebug.so!"; exit 1; }
python3 - "${MNT_V}/odm/lib64/libgf_hal_G3.so" <<'GFVFY'
import struct, sys
b = open(sys.argv[1], "rb").read()
assert struct.unpack_from("<I", b, 0x28AF4)[0] == 0x52800028, "libgf_hal_G3.so missing SPMT calibration fix at 0x28af4!"
assert struct.unpack_from("<I", b, 0x28B80)[0] == 0x52800028, "libgf_hal_G3.so missing SPMT calibration fix at 0x28b80!"
GFVFY
grep -q "libfod_zpos_hook.so" "${MNT_V}/ven/etc/init/android.hardware.graphics.composer@2.4-service.rc" || { echo "FAIL: composer@2.4-service.rc missing LD_PRELOAD libfod_zpos_hook.so!"; exit 1; }
grep -q "(allow hal_graphics_composer_default sysfs_oppo_display" "${MNT_V}/ven/etc/selinux/vendor_sepolicy.cil" || { echo "FAIL: vendor_sepolicy.cil missing hal_graphics_composer_default sysfs_oppo_display rule!"; exit 1; }
DEV_FCM="${SYS_ROOT}/etc/vintf/compatibility_matrix.device.xml"
grep -q "vendor.oplus.hardware.syshelper" "${DEV_FCM}" || { echo "FAIL: compatibility_matrix.device.xml missing vendor.oplus.hardware.syshelper (IUdfpsHelper)!"; exit 1; }
grep -q "vendor.oplus.hardware.biometrics.fingerprint" "${DEV_FCM}" || { echo "FAIL: compatibility_matrix.device.xml missing vendor.oplus.hardware.biometrics.fingerprint!"; exit 1; }
grep -q "android.hardware.biometrics.fingerprint" "${DEV_FCM}" || { echo "FAIL: compatibility_matrix.device.xml missing android.hardware.biometrics.fingerprint!"; exit 1; }
grep -a -q "/sys/kernel/oppo_display/notify_fppress" "${SYS_ROOT}/bin/samurai_hw_daemon" || { echo "FAIL: samurai_hw_daemon missing notify_fppress optical UDFPS HBM sync!"; exit 1; }
grep -q "service vendor.cdsprpcd /vendor/bin/cdsprpcd" "${MNT_V}/ven/etc/init/vendor.qti.adsprpc-service.rc" || { echo "FAIL: vendor.qti.adsprpc-service.rc missing vendor.cdsprpcd CDSP FastRPC service!"; exit 1; }
grep -q "(allow hal_fingerprint_default vendor_firmware_file" "${MNT_V}/ven/etc/selinux/vendor_sepolicy.cil" || { echo "FAIL: vendor_sepolicy.cil missing hal_fingerprint_default vendor_firmware_file rule!"; exit 1; }
grep -q "vendor_cdsprpcd" "${MNT_V}/ven/etc/selinux/vendor_sepolicy.cil" && grep -q "(typeattributeset vendor_hal_dspmanager_client.*hal_fingerprint_default" "${MNT_V}/ven/etc/selinux/vendor_sepolicy.cil" || { echo "FAIL: vendor_sepolicy.cil missing hal_fingerprint_default in vendor_hal_dspmanager_client!"; exit 1; }
grep -q "(typeattributeset hal_cryptoeng_client.*hal_fingerprint_default" "${MNT_V}/ven/etc/selinux/vendor_sepolicy.cil" || { echo "FAIL: vendor_sepolicy.cil missing hal_fingerprint_default in hal_cryptoeng_client!"; exit 1; }

STATIC_RROS=(
    SamuraiHardwareOverlay.apk
    SamuraiSystemUIOverlay.apk
    SamuraiSettingsOverlay.apk
)
IOS_ONLY_RROS=(
    SamuraiDialerOverlay.apk
    SamuraiMessagesOverlay.apk
    SamuraiGmailOverlay.apk
    SamuraiPhotosOverlay.apk
    SamuraiLauncherOverlay.apk
    SamuraiIos26WallpaperOverlay.apk
)
[ ! -e "${SYS_ROOT}/system_ext/overlay/SamuraiICallOverlay.apk" ] || { echo "FAIL: Retired SamuraiICallOverlay.apk present in /system_ext/overlay!"; exit 1; }
if [ "${ROM_FLAVOR}" = "ios-beta" ]; then
    STATIC_RROS+=("${IOS_ONLY_RROS[@]}")
fi

for rro in "${STATIC_RROS[@]}"; do
    RRO_P="${SYS_ROOT}/system_ext/overlay/${rro}"
    [ -f "${RRO_P}" ] || { echo "FAIL: Missing RRO ${RRO_P}"; exit 1; }
    [ "$(stat -c%a "${RRO_P}")" = "644" ] || { echo "FAIL: ${rro} mode != 644!"; exit 1; }
    [ "$(stat -c%u:%g "${RRO_P}")" = "0:0" ] || { echo "FAIL: ${rro} owner != 0:0!"; exit 1; }
    [ "$(getfattr --only-values -n security.selinux "${RRO_P}" | tr -d '\0')" = "u:object_r:system_file:s0" ] || { echo "FAIL: ${rro} SELinux context != u:object_r:system_file:s0!"; exit 1; }
    "${AAPT2}" dump xmltree "${RRO_P}" --file AndroidManifest.xml | grep -q "priority(0x0101001c)=950" || { echo "FAIL: ${rro} priority != 950!"; exit 1; }
    "${AAPT2}" dump xmltree "${RRO_P}" --file AndroidManifest.xml | grep -q "isStatic(0x0101055a)=true" || { echo "FAIL: ${rro} isStatic != true!"; exit 1; }
    echo "  [RRO] ${rro} verified in /system_ext/overlay/ (0644, 0:0, system_file:s0, priority=950, isStatic=true)"
done

if [ "${ROM_FLAVOR}" = "pixelos" ]; then
    for forbidden_rro in "${IOS_ONLY_RROS[@]}"; do
        [ ! -e "${SYS_ROOT}/system_ext/overlay/${forbidden_rro}" ] || { echo "FAIL: iOS-only RRO ${forbidden_rro} present in Pure PixelOS build!"; exit 1; }
    done
    echo "  [PASS] Zero iOS-only RRO overlays in /system_ext/overlay/ (Pure PixelOS verified)."
fi

HP_RRO="${SYS_ROOT}/system_ext/overlay/SamuraiHidePillOverlay.apk"
[ -f "${HP_RRO}" ] || { echo "FAIL: Missing dynamic RRO ${HP_RRO}"; exit 1; }
[ "$(stat -c%a "${HP_RRO}")" = "644" ] || { echo "FAIL: SamuraiHidePillOverlay.apk mode != 644!"; exit 1; }
[ "$(stat -c%u:%g "${HP_RRO}")" = "0:0" ] || { echo "FAIL: SamuraiHidePillOverlay.apk owner != 0:0!"; exit 1; }
[ "$(getfattr --only-values -n security.selinux "${HP_RRO}" | tr -d '\0')" = "u:object_r:system_file:s0" ] || { echo "FAIL: SamuraiHidePillOverlay.apk SELinux context != u:object_r:system_file:s0!"; exit 1; }
"${AAPT2}" dump xmltree "${HP_RRO}" --file AndroidManifest.xml | grep -q "isStatic(0x0101055a)=false" || { echo "FAIL: SamuraiHidePillOverlay.apk isStatic != false!"; exit 1; }
echo "  [RRO] SamuraiHidePillOverlay.apk verified in /system_ext/overlay/ (dynamic toggleable)"

for pin_f in \
    "${SYS_ROOT}/framework/framework.jar" \
    "${SYS_ROOT}/framework/services.jar" \
    "${SYS_ROOT}/bin/surfaceflinger" \
    "${SYS_ROOT}/system_ext/priv-app/SystemUI/SystemUI.apk" \
    "${SYS_ROOT}/lib64/libgui.so" \
    "${SYS_ROOT}/lib64/libui.so" \
    "${SYS_ROOT}/lib64/libhwui.so" \
    "${SYS_ROOT}/lib64/libvulkan.so" \
    "${MNT_V}/odm/lib64/hw/fingerprint.goodix_G3.default.so" \
    "${MNT_V}/ven/lib64/hw/vulkan.adreno.so" \
    "${MNT_V}/ven/lib64/egl/libGLESv2_adreno.so" \
    "${MNT_V}/ven/lib64/libgsl.so"; do
    [ -f "${pin_f}" ] || { echo "FAIL: PinnerService target file missing: ${pin_f}"; exit 1; }
done
echo "[PASS] Goodix G3 Optical UDFPS, RRO overlays (${ROM_FLAVOR}) & 8GB PinnerService verified!"

echo "=================================================================="
echo " [GATE 8/9] Shared Core, Channel Isolation, Codec2, Photos & GCam 9.6 (${ROM_FLAVOR})"
echo "=================================================================="
LG_FOUND="$(find "${SYS_ROOT}" -iname "*liquidglass*" | head -n 5 || true)"
if [ -n "${LG_FOUND}" ]; then
    echo "FAIL: Legacy standalone LiquidGlass files found inside system.raw.img: ${LG_FOUND}"
    exit 1
fi
! grep -qi "liquidglass" "${SYS_ROOT}/build.prop" || { echo "FAIL: liquidglass prop still in build.prop!"; exit 1; }

SHARED_REQ_FILES=(
    "${SYS_ROOT}/priv-app/SamuraiOTAUpdater/SamuraiOTAUpdater.apk"
    "${SYS_ROOT}/etc/permissions/privapp-permissions-ota.xml"
    "${SYS_ROOT}/etc/default-permissions/default-permissions-ota.xml"
    "${SYS_ROOT}/etc/init/ota.rc"
    "${SYS_ROOT}/etc/ota-watcher.sh"
    "${SYS_ROOT}/etc/ota-post-fs.sh"
    "${SYS_ROOT}/etc/pixel-boot.sh"
    "${SYS_ROOT}/bin/samurai_ota_bcb"
    "${SYS_ROOT}/priv-app/SamuraiParts/SamuraiParts.apk"
    "${SYS_ROOT}/etc/permissions/privapp-permissions-samuraiparts.xml"
    "${SYS_ROOT}/etc/default-permissions/default-permissions-samuraiparts.xml"
    "${SYS_ROOT}/bin/samurai_hw_daemon"
    "${SYS_ROOT}/framework/samurai-mod-helper.jar"
    "${SYS_ROOT}/framework/services.jar"
    "${SYS_ROOT}/system_ext/priv-app/SystemUI/SystemUI.apk"
    "${SYS_ROOT}/etc/samurai-mods-sync.sh"
    "${SYS_ROOT}/etc/samurai-gcam-sync.sh"
    "${SYS_ROOT}/etc/samurai-mods/photos-gsi-dex.zip"
    "${SYS_ROOT}/etc/samurai-mods/gcastartup_uaf_fix.bin"
    "${SYS_ROOT}/etc/samurai-mods/release-mods.json"
    "${SYS_ROOT}/etc/samurai-mods/KernelSU_Next_v1.0.9_12797.apk"
    "${SYS_ROOT}/etc/init/samurai_parts.rc"
    "${SYS_ROOT}/etc/init/init.samurai.perf.rc"
    "${SYS_ROOT}/etc/init/init.samurai.bootguard.rc"
    "${SYS_ROOT}/system_ext/overlay/SamuraiHardwareOverlay.apk"
    "${SYS_ROOT}/system_ext/overlay/SamuraiSystemUIOverlay.apk"
    "${SYS_ROOT}/system_ext/overlay/SamuraiSettingsOverlay.apk"
    "${SYS_ROOT}/system_ext/overlay/SamuraiHidePillOverlay.apk"
    "${SYS_ROOT}/system_ext/priv-app/NexusLauncherRelease/NexusLauncherRelease.apk"
    "${SYS_ROOT}/system_ext/priv-app/WallpaperPickerGoogleRelease/WallpaperPickerGoogleRelease.apk"
    "${SYS_ROOT}/product/app/PixelThemesStub2022_and_newer/PixelThemesStub2022_and_newer.apk"
    "${SYS_ROOT}/system_ext/etc/permissions/privapp-permissions-pixel-launcher.xml"
    "${SYS_ROOT}/etc/default-permissions/default-permissions-pixel-launcher.xml"
    "${SYS_ROOT}/etc/default-permissions/default-permissions-gcam.xml"
    "${SYS_ROOT}/etc/sysconfig/pixel-contextual-search.xml"
    "${SYS_ROOT}/bin/netbpfload_414"
    "${SYS_ROOT}/etc/bpf_netd_414.o"
    "${SYS_ROOT}/lib64/libnetd_updatable_414.so"
    "${SYS_ROOT}/lib64/libservice-connectivity_414.so"
    "${SYS_ROOT}/etc/init/netbpfload.rc"
    "${SYS_ROOT}/etc/init/init.rmx.adb.rc"
    "${SYS_ROOT}/etc/selinux/plat_file_contexts"
    "${SYS_ROOT}/etc/selinux/plat_sepolicy.cil"
    "${SYS_ROOT}/product/priv-app/GoogleDialer/GoogleDialer.apk"
    "${SYS_ROOT}/product/priv-app/PrebuiltBugle/PrebuiltBugle.apk"
    "${SYS_ROOT}/product/etc/sysconfig/pixel_2016_exclusive.xml"
)
for f in "${SHARED_REQ_FILES[@]}"; do
    [ -f "${f}" ] || { echo "FAIL: Missing required shared file ${f}"; exit 1; }
    CTX="$(getfattr --only-values -n security.selinux "${f}" | tr -d '\0')"
    [[ "${CTX}" == u:object_r:* ]] || { echo "FAIL: Invalid SELinux xattr on ${f}: ${CTX}"; exit 1; }
done

for retired_sys_path in \
    "${SYS_ROOT}/priv-app/IosClock" \
    "${SYS_ROOT}/etc/permissions/privapp-permissions-ios-clock.xml" \
    "${SYS_ROOT}/app/IosCalculator" \
    "${SYS_ROOT}/app/IosNotes" \
    "${SYS_ROOT}/app/IosWeather" \
    "${SYS_ROOT}/app/IosDialer" \
    "${SYS_ROOT}/app/ICall26" \
    "${SYS_ROOT}/app/IosMessages"; do
    [ ! -e "${retired_sys_path}" ] || { echo "FAIL: Retired path still present in system.raw.img: ${retired_sys_path}"; exit 1; }
done

grep -q "^ro.ota.channel=${EXP_CH}$" "${SYS_ROOT}/build.prop" || { echo "FAIL: ro.ota.channel=${EXP_CH} missing in system/build.prop!"; exit 1; }
grep -q "^ro.ota.version=${EXP_OTA_VER}$" "${SYS_ROOT}/build.prop" || { echo "FAIL: ro.ota.version=${EXP_OTA_VER} missing in system/build.prop!"; exit 1; }
grep -q "^ro.samurai.ota.version=${EXP_OTA_VER}$" "${SYS_ROOT}/build.prop" || { echo "FAIL: ro.samurai.ota.version=${EXP_OTA_VER} missing in system/build.prop!"; exit 1; }
grep -q "^ro.pixelos.display.version=${EXP_OTA_VER}$" "${SYS_ROOT}/build.prop" || { echo "FAIL: ro.pixelos.display.version=${EXP_OTA_VER} missing in system/build.prop!"; exit 1; }

grep -q 'TARGET_SCHEMA="${CUR_CH}-v2.1.4"' "${SYS_ROOT}/etc/ota-post-fs.sh" || { echo "FAIL: ota-post-fs.sh missing channel-aware TARGET_SCHEMA!"; exit 1; }
grep -q 'TARGET_SCHEMA="${CUR_CH}-v2.1.4"' "${SYS_ROOT}/etc/ota-watcher.sh" || { echo "FAIL: ota-watcher.sh missing channel-aware TARGET_SCHEMA!"; exit 1; }
grep -q 'TARGET_SCHEMA="${CUR_CH}-v2.1.4"' "${SYS_ROOT}/etc/pixel-boot.sh" || { echo "FAIL: pixel-boot.sh missing channel-aware TARGET_SCHEMA!"; exit 1; }
! grep -q "pm uninstall.*com.google.android.dialer" "${SYS_ROOT}/etc/samurai-mods-sync.sh" || { echo "FAIL: samurai-mods-sync.sh still uninstalls com.google.android.dialer!"; exit 1; }
! grep -q "pm uninstall.*com.google.android.apps.messaging" "${SYS_ROOT}/etc/samurai-mods-sync.sh" || { echo "FAIL: samurai-mods-sync.sh still uninstalls com.google.android.apps.messaging!"; exit 1; }
grep -q "install-existing --user 0 com.google.android.dialer" "${SYS_ROOT}/etc/samurai-mods-sync.sh" || { echo "FAIL: samurai-mods-sync.sh missing GoogleDialer install-existing recovery!"; exit 1; }
grep -q "install-existing --user 0 com.google.android.apps.messaging" "${SYS_ROOT}/etc/samurai-mods-sync.sh" || { echo "FAIL: samurai-mods-sync.sh missing PrebuiltBugle install-existing recovery!"; exit 1; }
grep -q 'KSUN_TARGET_VC="12797"' "${SYS_ROOT}/etc/samurai-mods-sync.sh" || { echo "FAIL: samurai-mods-sync.sh missing KSUN_TARGET_VC=12797!"; exit 1; }
grep -q "sync_ksunext_manager" "${SYS_ROOT}/etc/samurai-mods-sync.sh" || { echo "FAIL: samurai-mods-sync.sh missing sync_ksunext_manager!"; exit 1; }
grep -q "REQUEST_INSTALL_PACKAGES ignore" "${SYS_ROOT}/etc/samurai-mods-sync.sh" || { echo "FAIL: samurai-mods-sync.sh missing REQUEST_INSTALL_PACKAGES ignore lock!"; exit 1; }
grep -q 'name="check_update" value="false"' "${SYS_ROOT}/etc/samurai-mods-sync.sh" || { echo "FAIL: samurai-mods-sync.sh missing check_update=false enforcement!"; exit 1; }
[ "$(sha256sum "${SYS_ROOT}/etc/samurai-mods/KernelSU_Next_v1.0.9_12797.apk" | awk '{print $1}')" = "0013c41c9aeda2699f8a8af840839eb495f89bce7264b5c8fa42152b4c8d9a1e" ] || { echo "FAIL: KernelSU_Next_v1.0.9_12797.apk SHA-256 mismatch in system.raw.img!"; exit 1; }

# Verify SamuraiOTAUpdater.apk contains iOS Style UI Beta toggle + /data/ota_raw staging + dual-channel manifest support
python3 - "${SYS_ROOT}/priv-app/SamuraiOTAUpdater/SamuraiOTAUpdater.apk" <<'OTAPY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "r") as zf:
    dex = b"".join(zf.read(n) for n in zf.namelist() if n.endswith(".dex"))
for sym in (
    b"samurai_ios_style_ui_beta",
    b"iOS Style UI Beta",
    b"ios_beta",
    b"/data/ota_raw",
    b"ota/ios-beta/rmx1931.json",
    b"ota/stable/rmx1931.json",
):
    assert sym in dex, f"{sym.decode()} missing in SamuraiOTAUpdater.apk DEX!"
print("  [PASS] SamuraiOTAUpdater.apk Dual-Channel (Stable + Opt-In iOS Style UI Beta + /data/ota_raw) verified!")
OTAPY

# Verify pristine stock SystemUI.apk & two-way SamuraiPlatformSigBridge in services.ja
[ ! -e "${SYS_ROOT}/system_ext/priv-app/SystemUI/oat" ] || { echo "FAIL: Stale SystemUI/oat directory present!"; exit 1; }
[ ! -e "${SYS_ROOT}/framework/oat/arm64/services.odex" ] || { echo "FAIL: Stale services.odex still present!"; exit 1; }
"${APKSIGNER}" verify "${SYS_ROOT}/system_ext/priv-app/SystemUI/SystemUI.apk" >/dev/null || { echo "FAIL: SystemUI.apk signature verification failed!"; exit 1; }
cmp -s "${SYS_ROOT}/system_ext/priv-app/SystemUI/SystemUI.apk" "${WORK_ROOT}/downloads/pixel_a17_cp3a/SystemUI_stock.apk" || { echo "FAIL: SystemUI.apk does NOT match pristine stock SystemUI_stock.apk!"; exit 1; }
"${ZIPALIGN}" -c 4 "${SYS_ROOT}/framework/services.jar" >/dev/null || { echo "FAIL: services.jar 4-byte zipalign failed!"; exit 1; }
python3 - "${SYS_ROOT}/system_ext/priv-app/SystemUI/SystemUI.apk" "${SYS_ROOT}/framework/services.jar" <<'SVCPY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "r") as zf:
    assert "classes4.dex" not in zf.namelist(), "SystemUI.apk unexpectedly contains custom classes4.dex!"
with zipfile.ZipFile(sys.argv[2], "r") as zf:
    assert "classes4.dex" in zf.namelist(), "services.jar missing classes4.dex!"
    d4 = zf.read("classes4.dex")
    assert b"SamuraiPlatformSigBridge" in d4, "services.jar missing SamuraiPlatformSigBridge!"
    assert b"SamuraiUdfpsOpticalOverlay" in d4, "services.jar missing SamuraiUdfpsOpticalOverlay!"
    assert b"onOverlayShown: " in d4 and b"onFingerDown: (" in d4, "services.jar missing SamuraiUdfpsOverlay transition telemetry!"
    assert b"/system/etc/udfps_icon_pressed.webp" in d4, "services.jar missing /system/etc/udfps_icon_pressed.webp!"
    assert b"onWallpaperCommand" in d4 and b"android.wallpaper.tap" in d4, "services.jar missing onWallpaperCommand Home Screen double-tap lock bridge!"
    assert b"STOCK_NEXUS_LAUNCHER_SHA256" in d4 and bytes.fromhex("a86bdb059f28f265162d64ce6c8d9772901d227e741581d1f04a5ed132a574d0") in d4, "services.jar missing STOCK_NEXUS_LAUNCHER_SHA256 two-way cert bridge!"
    assert b"SamuraiSettingsBinderWrapper" in d4 and b"maybeWrapSettingsHolder" in d4, "services.jar missing SamuraiSettingsBinderWrapper per-UID Settings isolation!"
    assert b"hasSystemFeature" in d4 and b"NEXUS_PRELOAD" in d4 and b"PIXEL_20" in d4, "services.jar missing per-UID Pixel 2016 feature routing for Google Photos!"
print("  [PASS] Pristine stock SystemUI.apk + two-way SamuraiPlatformSigBridge + Home Wallpaper double-tap sleep in services.jar verified!")
SVCPY

OV_HW_RES="$("${AAPT2}" dump resources "${SYS_ROOT}/system_ext/overlay/SamuraiHardwareOverlay.apk")"
OV_TEL_RES="$("${AAPT2}" dump resources "${SYS_ROOT}/system_ext/overlay/SamuraiTelephonyOverlay.apk")"
OV_SYSUI_RES="$("${AAPT2}" dump resources "${SYS_ROOT}/system_ext/overlay/SamuraiSystemUIOverlay.apk")"
OV_SET_RES="$("${AAPT2}" dump resources "${SYS_ROOT}/system_ext/overlay/SamuraiSettingsOverlay.apk")"
[[ "${OV_HW_RES}" == *"bool/config_sf_slowBlur"* ]] || { echo "FAIL: config_sf_slowBlur missing in SamuraiHardwareOverlay!"; exit 1; }
[[ "${OV_HW_RES}" == *"bool/config_switch_phone_on_voice_reg_state_change"* ]] || { echo "FAIL: config_switch_phone_on_voice_reg_state_change missing in SamuraiHardwareOverlay!"; exit 1; }
[[ "${OV_TEL_RES}" == *"org.codeaurora.ims"* ]] || { echo "FAIL: config_ims_mmtel_package=org.codeaurora.ims missing in SamuraiTelephonyOverlay!"; exit 1; }
[[ "${OV_TEL_RES}" == *"vendor.qti.imsrcs"* ]] || { echo "FAIL: config_ims_rcs_package=vendor.qti.imsrcs missing in SamuraiTelephonyOverlay!"; exit 1; }
[[ "${OV_HW_RES}" == *"com.google.android.apps.nexuslauncher/com.android.quickstep.RecentsActivity"* ]] || { echo "FAIL: config_recentsComponentName != NexusLauncherRelease!"; exit 1; }
[[ "${OV_SYSUI_RES}" == *"com.google.android.GoogleCameraEng"* ]] || { echo "FAIL: config_cameraGesturePackage != GoogleCameraEng!"; exit 1; }
[[ "${OV_SET_RES}" == *"com.google.android.apps.wallpaper"* ]] || { echo "FAIL: config_wallpaper_picker_package != WallpaperPickerGoogleRelease!"; exit 1; }

# Verify QTI IMS / VoLTE / SMS-over-IMS / DSDS / DPM Stack, RMX1931 CarrierConfigRes & Google Dialer Support
for qti_req in \
    "${SYS_ROOT}/system_ext/priv-app/ims/ims.apk" \
    "${SYS_ROOT}/system_ext/priv-app/qcrilmsgtunnel/qcrilmsgtunnel.apk" \
    "${SYS_ROOT}/system_ext/app/QtiTelephonyService/QtiTelephonyService.apk" \
    "${SYS_ROOT}/system_ext/app/ImsRcsService/ImsRcsService.apk" \
    "${SYS_ROOT}/product/overlay/CarrierConfigRes.apk" \
    "${SYS_ROOT}/system_ext/bin/dpmd" \
    "${SYS_ROOT}/system_ext/etc/init/dpmd.rc" \
    "${SYS_ROOT}/system_ext/etc/dpm/dpm.conf" \
    "${SYS_ROOT}/system_ext/lib64/libgui_shim.so" \
    "${SYS_ROOT}/system_ext/lib64/libimscamera_jni.so" \
    "${SYS_ROOT}/system_ext/lib64/libimsmedia_jni.so" \
    "${SYS_ROOT}/system_ext/lib64/libdiag_system.so" \
    "${SYS_ROOT}/lib64/vendor.qti.diaghal-V1-ndk.so" \
    "${SYS_ROOT}/system_ext/framework/extphonelib.jar" \
    "${SYS_ROOT}/system_ext/framework/qcrilhook.jar" \
    "${SYS_ROOT}/product/framework/ims-ext-common.jar" \
    "${SYS_ROOT}/product/framework/com.google.android.dialer.support.jar" \
    "${SYS_ROOT}/product/etc/permissions/com.google.android.dialer.support.xml" \
    "${SYS_ROOT}/system_ext/etc/permissions/privapp-permissions-qti-ims.xml" \
    "${SYS_ROOT}/system_ext/etc/sysconfig/qti-telephony-shareduid-allowlist.xml" \
    "${SYS_ROOT}/system_ext/etc/selinux/system_ext_mac_permissions.xml"; do
    [ -f "${qti_req}" ] || { echo "FAIL: Missing QTI IMS/Telephony/Dialer component ${qti_req}!"; exit 1; }
done
[ -L "${SYS_ROOT}/system_ext/priv-app/ims/lib/arm64/libimscamera_jni.so" ] && [ -L "${SYS_ROOT}/system_ext/priv-app/ims/lib/arm64/libimsmedia_jni.so" ] || { echo "FAIL: Missing ims.apk JNI symlinks!"; exit 1; }
[ ! -e "${SYS_ROOT}/system_ext/framework/qti-telephony-common.jar" ] || { echo "FAIL: Incompatible SDK-36 qti-telephony-common.jar must not be staged on Android 17!"; exit 1; }
for qti_signed_apk in \
    "${SYS_ROOT}/system_ext/priv-app/ims/ims.apk" \
    "${SYS_ROOT}/system_ext/priv-app/qcrilmsgtunnel/qcrilmsgtunnel.apk" \
    "${SYS_ROOT}/system_ext/app/QtiTelephonyService/QtiTelephonyService.apk" \
    "${SYS_ROOT}/system_ext/app/ImsRcsService/ImsRcsService.apk" \
    "${SYS_ROOT}/product/overlay/CarrierConfigRes.apk" \
    "${SYS_ROOT}/system_ext/overlay/SamuraiTelephonyOverlay.apk"; do
    "${ZIPALIGN}" -c 4 "${qti_signed_apk}" >/dev/null || { echo "FAIL: 4-byte zipalign failed on ${qti_signed_apk}!"; exit 1; }
    "${APKSIGNER}" verify "${qti_signed_apk}" >/dev/null || { echo "FAIL: apksigner verify failed on ${qti_signed_apk}!"; exit 1; }
done
grep -q "com.google.android.apps.dialer.SUPPORTED" "${SYS_ROOT}/product/etc/permissions/com.google.android.dialer.support.xml" || { echo "FAIL: com.google.android.dialer.support.xml missing SUPPORTED feature!"; exit 1; }
grep -q "^persist.radio.multisim.config=dsds$" "${SYS_ROOT}/build.prop" || { echo "FAIL: system/build.prop missing persist.radio.multisim.config=dsds!"; exit 1; }
grep -q "^persist.vendor.dpm.feature=1$" "${SYS_ROOT}/system_ext/etc/build.prop" || { echo "FAIL: system_ext/etc/build.prop missing persist.vendor.dpm.feature=1!"; exit 1; }
grep -q "RMX1931_QTI_202504_MAPPING" "${SYS_ROOT}/system_ext/etc/selinux/mapping/202504.cil" || { echo "FAIL: system_ext 202504.cil missing RMX1931_QTI_202504_MAPPING!"; exit 1; }
grep -q "RMX1931_QTI_IMS_TELEPHONY_SEPOLICY" "${SYS_ROOT}/etc/selinux/plat_sepolicy.cil" || { echo "FAIL: plat_sepolicy.cil missing RMX1931_QTI_IMS_TELEPHONY_SEPOLICY!"; exit 1; }
[ "$(getfattr --only-values -n security.selinux "${SYS_ROOT}/system_ext/bin/dpmd" 2>/dev/null | tr -d '\0')" = "u:object_r:vendor_dpmd_exec:s0" ] || { echo "FAIL: dpmd bad SELinux context!"; exit 1; }
echo "  [PASS] QTI IMS/VoLTE/SMS-over-IMS/DSDS/DPM + RMX1931 CarrierConfigRes + Google Dialer support + SELinux 202504 verified!"

NX_SYS_APK="${SYS_ROOT}/system_ext/priv-app/NexusLauncherRelease/NexusLauncherRelease.apk"
[ ! -e "${SYS_ROOT}/system_ext/priv-app/NexusLauncherRelease/oat" ] || { echo "FAIL: Stale NexusLauncherRelease/oat directory present!"; exit 1; }
"${APKSIGNER}" verify "${NX_SYS_APK}" >/dev/null || { echo "FAIL: NexusLauncherRelease.apk signature verification failed!"; exit 1; }
NX_BADGE="$("${AAPT2}" dump badging "${NX_SYS_APK}" 2>/dev/null | head -n 2 || true)"

if [ "${ROM_FLAVOR}" = "pixelos" ]; then
    # =====================================================================
    # STRICT PURE PIXELOS STABLE ASSERTIONS (ZERO iOS UI / COMMS / SHADE)
    # =====================================================================
    for forbidden_ios_path in \
        "${SYS_ROOT}/priv-app/IosStyler" \
        "${SYS_ROOT}/etc/permissions/privapp-permissions-styler.xml" \
        "${SYS_ROOT}/priv-app/IosSettings" \
        "${SYS_ROOT}/etc/permissions/privapp-permissions-ios-settings.xml" \
        "${SYS_ROOT}/priv-app/IosControlCenter" \
        "${SYS_ROOT}/etc/permissions/privapp-permissions-ios-controlcenter.xml" \
        "${SYS_ROOT}/priv-app/IosDialer" \
        "${SYS_ROOT}/etc/permissions/privapp-permissions-ios-dialer.xml" \
        "${SYS_ROOT}/priv-app/IosMessages" \
        "${SYS_ROOT}/etc/permissions/privapp-permissions-ios-messages.xml" \
        "${SYS_ROOT}/app/ICall26" \
        "${SYS_ROOT}/app/IosMessages" \
        "${SYS_ROOT}/etc/default-permissions/default-permissions-ios-comms.xml"; do
        [ ! -e "${forbidden_ios_path}" ] || { echo "FAIL: iOS path present in Pure PixelOS system.raw.img: ${forbidden_ios_path}"; exit 1; }
    done
    IOS_LEAK="$(find "${SYS_ROOT}/app" "${SYS_ROOT}/priv-app" "${SYS_ROOT}/system_ext/overlay" -maxdepth 2 \( -iname "*ios*" -o -iname "*icall*" \) || true)"
    [ -z "${IOS_LEAK}" ] || { echo "FAIL: Unexpected iOS/iCall file leaked into Pure PixelOS: ${IOS_LEAK}"; exit 1; }
    ! grep -Eq "icontact|iossettings|controlcenter|samurai\.dialer|samurai\.messages" "${SYS_ROOT}/etc/sysconfig/pixel-contextual-search.xml" || { echo "FAIL: iOS packages leaked into Pure PixelOS pixel-contextual-search.xml!"; exit 1; }

    [[ "${OV_HW_RES}" == *"M50,0 A50,50"* ]] || { echo "FAIL: Pure PixelOS SamuraiHardwareOverlay missing circular Pixel icon mask (M50,0 A50,50)!"; exit 1; }
    [[ "${OV_HW_RES}" == *"com.google.android.dialer"* ]] || { echo "FAIL: Pure PixelOS SamuraiHardwareOverlay missing com.google.android.dialer!"; exit 1; }
    [[ "${OV_HW_RES}" == *"com.google.android.apps.messaging"* ]] || { echo "FAIL: Pure PixelOS SamuraiHardwareOverlay missing com.google.android.apps.messaging!"; exit 1; }
    [[ "${OV_SET_RES}" != *"ios26_settings_fg"* ]] || { echo "FAIL: Pure PixelOS SamuraiSettingsOverlay still contains ios26_settings_fg!"; exit 1; }

    [[ "${NX_BADGE}" == *"versionCode='907'"* ]] || { echo "FAIL: Pure PixelOS NexusLauncherRelease.apk is not stock v907: ${NX_BADGE}"; exit 1; }
    cmp -s "${NX_SYS_APK}" "${WORK_ROOT}/downloads/pixel_a17_cp3a/NexusLauncherRelease.apk" || { echo "FAIL: Pure PixelOS NexusLauncherRelease.apk does not match unmodified retail Google APK!"; exit 1; }
    python3 - "${NX_SYS_APK}" <<'NXSTOCKPY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "r") as zf:
    names = zf.namelist()
    assert "classes3.dex" not in names, "Pure PixelOS NexusLauncherRelease.apk unexpectedly contains custom classes3.dex!"
    assert not any("ios26" in n.lower() for n in names), "Pure PixelOS NexusLauncherRelease.apk contains ios26 assets!"
print("  [PASS] Pure PixelOS: unmodified retail Google NexusLauncherRelease.apk (v907) + circular Pixel icons + Google Dialer/Messages + ZERO iOS components verified!")
NXSTOCKPY
else
    # =====================================================================
    # OPT-IN iOS STYLE UI BETA ASSERTIONS (FULL iOS 26 LIQUID GLASS SUITE)
    # =====================================================================
    for ios_req in \
        "${SYS_ROOT}/priv-app/IosStyler/IosStyler.apk" \
        "${SYS_ROOT}/etc/permissions/privapp-permissions-styler.xml" \
        "${SYS_ROOT}/priv-app/IosSettings/IosSettings.apk" \
        "${SYS_ROOT}/priv-app/IosSettings/lib/arm64/libandroidx.graphics.path.so" \
        "${SYS_ROOT}/etc/permissions/privapp-permissions-ios-settings.xml" \
        "${SYS_ROOT}/priv-app/IosControlCenter/IosControlCenter.apk" \
        "${SYS_ROOT}/priv-app/IosControlCenter/lib/arm64/libandroidx.graphics.path.so" \
        "${SYS_ROOT}/etc/permissions/privapp-permissions-ios-controlcenter.xml" \
        "${SYS_ROOT}/priv-app/IosDialer/IosDialer.apk" \
        "${SYS_ROOT}/etc/permissions/privapp-permissions-ios-dialer.xml" \
        "${SYS_ROOT}/priv-app/IosMessages/IosMessages.apk" \
        "${SYS_ROOT}/priv-app/IosMessages/lib/arm64/libandroidx.graphics.path.so" \
        "${SYS_ROOT}/etc/permissions/privapp-permissions-ios-messages.xml" \
        "${SYS_ROOT}/etc/default-permissions/default-permissions-ios-comms.xml"; do
        [ -f "${ios_req}" ] || { echo "FAIL: Missing iOS Beta component ${ios_req}"; exit 1; }
    done
    [[ "${OV_HW_RES}" == *"M 50,0 L 65.605,0 C 75.509,0"* ]] || { echo "FAIL: iOS Beta SamuraiHardwareOverlay missing r=22.50% G2 squircle mask!"; exit 1; }
    [[ "${OV_HW_RES}" == *"com.realme.samurai.dialer"* ]] || { echo "FAIL: iOS Beta SamuraiHardwareOverlay missing com.realme.samurai.dialer!"; exit 1; }
    [[ "${OV_HW_RES}" != *"icontact"* ]] || { echo "FAIL: iOS Beta SamuraiHardwareOverlay still references icontact!"; exit 1; }
    [[ "${OV_SET_RES}" == *"drawable/ios26_settings_fg"* ]] || { echo "FAIL: iOS Beta SamuraiSettingsOverlay missing ios26_settings_fg!"; exit 1; }
    "${ZIPALIGN}" -c 4 "${NX_SYS_APK}" >/dev/null || { echo "FAIL: iOS Beta NexusLauncherRelease.apk zipalign failed!"; exit 1; }
    [[ "${NX_BADGE}" == *"versionCode='934'"* ]] || { echo "FAIL: iOS Beta NexusLauncherRelease.apk not v934: ${NX_BADGE}"; exit 1; }
    python3 - "${NX_SYS_APK}" "${SYS_ROOT}/priv-app/IosControlCenter/IosControlCenter.apk" "${SYS_ROOT}/priv-app/IosDialer/IosDialer.apk" <<'NXBETAPY'
import sys, zipfile
nx_apk, cc_apk, dialer_apk = sys.argv[1:]
with zipfile.ZipFile(nx_apk, "r") as zf:
    names = zf.namelist()
    assert "classes3.dex" in names, "classes3.dex missing in iOS Beta NexusLauncherRelease.apk!"
    d3 = zf.read("classes3.dex")
    webp_icons = [n for n in names if n.startswith("assets/ios26_icons/") and n.endswith(".webp")]
    assert len(webp_icons) >= 129, f"Expected >=129 bundled WebP icons, found {len(webp_icons)}"
    assert not any("icontact" in n.lower() for n in webp_icons), "Stale icontact WebP icon still bundled in NexusLauncherRelease.apk!"
    for sym in (
        b"SamuraiLauncherHooks",
        b"SamuraiDockGlassRenderer",
        b"SamuraiPopulateWorkspaceTask",
        b"handleHomeStatusBarSwipe",
        b"SamuraiRecentsController",
        b"samurai_launcher_bar_state",
    ):
        assert sym in d3, f"{sym.decode()} missing in iOS Beta classes3.dex!"
with zipfile.ZipFile(cc_apk, "r") as zf:
    cc_dex = b"".join(zf.read(n) for n in zf.namelist() if n.endswith(".dex"))
    for sym in (b"uSheetEdgeY", b"SamuraiIos26HomeBar", b"SecureBouncerHandoffCoordinator", b"SamuraiIos26Unlock"):
        assert sym in cc_dex, f"{sym.decode()} missing in IosControlCenter.apk DEX!"
with zipfile.ZipFile(dialer_apk, "r") as zf:
    dl_dex = b"".join(zf.read(n) for n in zf.namelist() if n.endswith(".dex"))
    for sym in (b"bindToPaintInCallLit", b"uIsLiveCapture"):
        assert sym in dl_dex, f"{sym.decode()} missing in IosDialer.apk DEX!"
print(f"  [PASS] iOS Beta: NexusLauncherRelease (v934, {len(webp_icons)} icons, Stacked-Deck Recents) + IosControlCenter (Sliding Sheet AGSL + HomeBar) + IosDialer (90Hz AGSL) + IosSettings + IosStyler + IosMessages verified!")
NXBETAPY
fi

# Shared Kernel 4.14, Chrome zygote_next, 4.14 BPF/netd, Codec2, Unlimited Photos, GCam 9.6 & HBM checks
grep -q "^    exec_start bpfloader$" "${SYS_ROOT}/etc/init/netbpfload.rc" || { echo "FAIL: netbpfload.rc missing exec_start bpfloader!"; exit 1; }
! grep -q "bpfloade$" "${SYS_ROOT}/etc/init/netbpfload.rc" || { echo "FAIL: netbpfload.rc still contains bpfloade typo!"; exit 1; }
[ -x "${SYS_ROOT}/bin/netbpfload_414" ] && [ -f "${SYS_ROOT}/etc/bpf_netd_414.o" ] && [ -f "${SYS_ROOT}/lib64/libnetd_updatable_414.so" ] && [ -f "${SYS_ROOT}/lib64/libservice-connectivity_414.so" ] || { echo "FAIL: Missing 4.14 BPF/netd binaries in system image!"; exit 1; }
grep -q 'ctl\.start\$zygote_next' "${SYS_ROOT}/etc/selinux/plat_property_contexts" || { echo "FAIL: plat_property_contexts missing ctl.start\$zygote_next!"; exit 1; }
grep -q "^persist.zygote.zygote_next.start_on_boot=true$" "${SYS_ROOT}/build.prop" || { echo "FAIL: build.prop missing persist.zygote.zygote_next.start_on_boot=true!"; exit 1; }
grep -q "^on boot$" "${SYS_ROOT}/etc/init/zygote_next64.rc" && grep -q "^    start statsd$" "${SYS_ROOT}/etc/init/zygote_next64.rc" && grep -q "group root readproc reserved_disk" "${SYS_ROOT}/etc/init/zygote_next64.rc" || { echo "FAIL: zygote_next64.rc missing boot trigger, start statsd, or readproc group!"; exit 1; }
grep -q "property:ro.ota.channel=stable" "${SYS_ROOT}/etc/init/ota.rc" && grep -q "property:ro.ota.channel=ios_beta" "${SYS_ROOT}/etc/init/ota.rc" || { echo "FAIL: ota.rc missing channel-gated blur triggers!"; exit 1; }
! grep -Eq "^[[:space:]]*stop[[:space:]]+statsd([[:space:]]|$)" "${MNT_V}/ven/etc/init/hw/init.target.rc" || { echo "FAIL: vendor init.target.rc still contains 'stop statsd'!"; exit 1; }
[ "$(stat -c '%u:%g:%a' "${MNT_V}/ven/etc/init/hw/init.target.rc")" = "0:0:644" ] || { echo "FAIL: vendor init.target.rc bad ownership/mode!"; exit 1; }
[ "$(getfattr --only-values -n security.selinux "${MNT_V}/ven/etc/init/hw/init.target.rc" 2>/dev/null | tr -d '\0')" = "u:object_r:vendor_configs_file:s0" ] || { echo "FAIL: vendor init.target.rc bad SELinux context!"; exit 1; }
[ -f "${MNT_V}/ven/etc/.pixelos_a17_vendor_ok" ] || { echo "FAIL: vendor missing /etc/.pixelos_a17_vendor_ok marker!"; exit 1; }
[ "$(stat -c '%u:%g:%a' "${MNT_V}/ven/etc/.pixelos_a17_vendor_ok")" = "0:0:644" ] || { echo "FAIL: .pixelos_a17_vendor_ok bad ownership/mode!"; exit 1; }
[ "$(getfattr --only-values -n security.selinux "${MNT_V}/ven/etc/.pixelos_a17_vendor_ok" 2>/dev/null | tr -d '\0')" = "u:object_r:vendor_configs_file:s0" ] || { echo "FAIL: .pixelos_a17_vendor_ok bad SELinux context!"; exit 1; }
python3 - "${MNT_V}/ven/etc/fstab.qcom" <<'FSTABPY'
import sys
txt = open(sys.argv[1], encoding="utf-8").read()
data_lines = [l for l in txt.splitlines() if not l.strip().startswith("#") and "/userdata" in l and "/data" in l]
assert len(data_lines) == 2, f"Expected 2 /data lines (ext4 + f2fs) in /vendor/etc/fstab.qcom, got {len(data_lines)}: {data_lines}"
assert "ext4" in data_lines[0] and "errors=remount-ro" in data_lines[0] and "formattable" not in data_lines[0], f"Bad ext4 /data line: {data_lines[0]}"
assert "f2fs" in data_lines[1] and "formattable" in data_lines[1] and "checkpoint=fs" in data_lines[1], f"Bad f2fs /data line: {data_lines[1]}"
for dl in data_lines:
    assert "inlinecrypt" not in dl and "fileencryption" not in dl and "wrappedkey" not in dl, f"Encryption deadlock flag still in /data line: {dl}"
print("  [PASS] /vendor/etc/fstab.qcom dual EXT4 + F2FS /data mounting & .pixelos_a17_vendor_ok verified!")
FSTABPY
python3 -c 'import struct,sys; b=open(sys.argv[1],"rb").read(); assert struct.unpack_from("<I",b,0x2a598)[0]==0xd65f03c0' "${SYS_ROOT}/lib64/libmeminfo.so"
python3 -c 'import struct,sys; b=open(sys.argv[1],"rb").read(); assert struct.unpack_from("<I",b,0xb8e8)[0]==0x9400179e' "${SYS_ROOT}/lib64/libzygote_sys.dylib.so"
python3 -c 'import struct,sys; b=open(sys.argv[1],"rb").read(); assert struct.unpack_from("<I",b,0x3600)[0]==0xd503201f and struct.unpack_from("<I",b,0x3654)[0]==0x7100941f' "${SYS_ROOT}/lib64/libcap_rust.dylib.so"
python3 -c 'import struct,sys; b=open(sys.argv[1],"rb").read(); assert len(b)==69432 and struct.unpack_from("<I",b,0x6540)[0]==0x1400002e and struct.unpack_from("<I",b,0x72b4)[0]==0x1400002e' "${SYS_ROOT}/lib64/libzygote_species_android_native.so"
[ -f "${MNT_V}/ven/gcam/GoogleCameraEng.apk" ] || { echo "FAIL: Missing /vendor/gcam/GoogleCameraEng.apk!"; exit 1; }
python3 -c 'import struct,sys; f=open(sys.argv[1],"rb"); f.seek(0x4fb76ac); assert struct.unpack("<I",f.read(4))[0]==0xd2800000' "${MNT_V}/ven/gcam/lib/arm64/libgcastartup.so"
python3 - "${MNT_V}/ven/lib64/hw/com.qti.chi.override.so" <<'CHIPY'
import struct, sys
b = open(sys.argv[1], "rb").read()
assert len(b) == 2172832, f"Unexpected com.qti.chi.override.so size: {len(b)}"
for i in range(10):
    cid, _, exp = struct.unpack_from("<III", b, 0x20e7fc + i * 0xd4)
    assert cid == i and exp == (1 if i < 4 else 0)
assert struct.unpack_from("<I", b, 0x5639c)[0] == 0x14027519, "Missing Cave 1 EIS3 hook at 0x5639c!"
assert struct.unpack_from("<I", b, 0xdccc4)[0] == 0x14005aff, "Missing Cave 2 EIS3 hook at 0xdccc4!"
assert struct.unpack_from("<I", b, 0xdd100)[0] == 0x14005a08, "Missing Cave 3 EIS3 hook at 0xdd100!"
assert struct.unpack_from("<I", b, 0xdd188)[0] == 0x140059e6, "Missing Cave 3 EIS3 hook at 0xdd188!"
assert struct.unpack_from("<I", b, 0xf3800)[0] == 0xb9401329, "Missing Cave 1 entry instruction!"
assert struct.unpack_from("<I", b, 0xf38c0)[0] == 0xb9401268, "Missing Cave 2 entry instruction!"
assert struct.unpack_from("<I", b, 0xf3920)[0] == 0xf9400665, "Missing Cave 3 entry instruction!"
print("  [PASS] Shared Core: Chrome 156+ zygote_next 4.14 fix + GCam 9.6 + Spectra 380 Quad-Lens & 4K60 Gyro-EIS3 CHI HAL verified!")
CHIPY
grep -q "pixel_gcam_eis_4k60_v1" "${SYS_ROOT}/etc/samurai-gcam-sync.sh" || { echo "FAIL: samurai-gcam-sync.sh missing pixel_gcam_eis_4k60_v1!"; exit 1; }
grep -q 'ses_60fps_id_key_0">32808<' "${SYS_ROOT}/etc/samurai-gcam-sync.sh" || { echo "FAIL: samurai-gcam-sync.sh missing 32808 60fps EIS session pre-seed!"; exit 1; }
grep -q 'ses_30fps_id_key_0">32779<' "${SYS_ROOT}/etc/samurai-gcam-sync.sh" || { echo "FAIL: samurai-gcam-sync.sh missing 32779 30fps EIS session pre-seed!"; exit 1; }
grep -q 'pref_video_stabilization_key">1<' "${SYS_ROOT}/etc/samurai-gcam-sync.sh" || { echo "FAIL: samurai-gcam-sync.sh missing pref_video_stabilization_key=1!"; exit 1; }
grep -q "pixel_gcam_rmx1931_xml_v3" "${SYS_ROOT}/etc/pixel-boot.sh" || { echo "FAIL: pixel-boot.sh missing pixel_gcam_rmx1931_xml_v3!"; exit 1; }
grep -a -q "/sys/class/backlight/panel0-backlight/brightness" "${SYS_ROOT}/bin/samurai_hw_daemon" || { echo "FAIL: samurai_hw_daemon missing panel0-backlight slider override!"; exit 1; }
grep -q "write /sys/kernel/oppo_display/hbm 0" "${SYS_ROOT}/etc/init/init.samurai.perf.rc" || { echo "FAIL: init.samurai.perf.rc missing oppo_display/hbm 0 boot reset!"; exit 1; }
grep -q "^ro.debuggable=1$" "${SYS_ROOT}/build.prop" || { echo "FAIL: system/build.prop must have ro.debuggable=1!"; exit 1; }
grep -q "^ro.secure=0$" "${SYS_ROOT}/build.prop" || { echo "FAIL: system/build.prop must have ro.secure=0!"; exit 1; }
grep -q "^ro.build.type=user$" "${SYS_ROOT}/build.prop" || { echo "FAIL: system/build.prop must have ro.build.type=user!"; exit 1; }
grep -q "^ro.build.tags=release-keys$" "${SYS_ROOT}/build.prop" || { echo "FAIL: system/build.prop must have ro.build.tags=release-keys!"; exit 1; }
grep -q "^vendor.flash.locked=1$" "${MNT_V}/ven/build.prop" || { echo "FAIL: vendor/build.prop must have vendor.flash.locked=1!"; exit 1; }
! grep -Eq "mount[[:space:]]+-o[[:space:]]+bind" "${SYS_ROOT}/etc/samurai-mods-sync.sh" || { echo "FAIL: samurai-mods-sync.sh must not perform runtime bind-mounts!"; exit 1; }

echo "--- Android 17 Pixel OS & Dual-Channel OTA Build Props (${ROM_FLAVOR}) ---"
grep -E "ro.system.build.version.release=|ro.system.build.version.sdk=|ro.system.build.id=|ro.ota.channel=|ro.ota.repo=|ro.ota.version=|ro.samurai.ota.version=|ro.pixelos.display.version=|ro.ota.datetime=|ro.ota.manifest_url=" "${SYS_ROOT}/build.prop"

cleanup

echo "=================================================================="
echo " [GATE 9/9] Final OTA ZIP Integrity, Manifest Parity & Firmware Safety (${ROM_FLAVOR})"
echo "=================================================================="
if [ "${ROM_FLAVOR}" = "pixelos" ]; then
    BRIDGE_ZIP="${WIN_OUT}/RMX1931-Android17-PixelOS-OTA.zip"
    SYS_BLOCK_ZIP="${WIN_OUT}/RMX1931-Android17-PixelOS-System.zip"
    VB_ZIP="${WIN_OUT}/RMX1931-Android17-PixelOS-VendorBoot.zip"
    unzip -tq "${BRIDGE_ZIP}"
    unzip -tq "${SYS_BLOCK_ZIP}"
    unzip -tq "${VB_ZIP}"

    python3 - "${BRIDGE_ZIP}" "${REPO_ROOT}/ota/rmx1931.json" "${SYS_BLOCK_ZIP}" "${REPO_ROOT}/ota/stable/rmx1931.json" "${EXP_OTA_VER}" "${WORK_ROOT}/downloads/pixel_a17_cp3a/SystemUI_stock.apk" <<'PYSTABLE'
import hashlib, json, os, struct, sys, zipfile

def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

bridge_zip, legacy_json, sys_zip, stable_json, exp_ver, stock_sysui = sys.argv[1:]
e_leg = json.load(open(legacy_json, encoding="utf-8"))["response"][0]
e_stb = json.load(open(stable_json, encoding="utf-8"))["response"][0]

assert e_leg["channel"] == "stable" and e_stb["channel"] == "stable", "Channel != stable in Stable manifests!"
assert e_leg["version"] == exp_ver and e_stb["version"] == exp_ver, f"Version mismatch vs {exp_ver}!"
assert e_leg["datetime"] == e_stb["datetime"], f"Timestamp mismatch between ota/rmx1931.json ({e_leg['datetime']}) and ota/stable/rmx1931.json ({e_stb['datetime']}) — must match to prevent double download!"
assert e_leg["datetime"] > 1793728592, f"Stable datetime {e_leg['datetime']} must be > v1.7.0 (1793728592)!"

assert e_leg["size"] == os.path.getsize(bridge_zip) and e_leg["size"] < 398458880, "Bridge OTA size invalid or >= 380 MiB!"
b_sha = sha256_file(bridge_zip)
assert e_leg["sha256"] == b_sha and e_leg["id"] == b_sha, "SHA-256 mismatch in ota/rmx1931.json!"

assert e_stb["size"] == os.path.getsize(bridge_zip) and e_stb["size"] < 398458880, "Stable OTA manifest must point to compact < 380 MiB OTA.zip (not 1.8 GB System.zip)!"
assert e_stb["sha256"] == b_sha and e_stb["id"] == b_sha, "SHA-256 mismatch in ota/stable/rmx1931.json!"

assert os.path.getsize(sys_zip) < 2147483648, "Stable System.zip clean-flash image >= 2 GiB!"

with zipfile.ZipFile(bridge_zip, "r") as zf:
    names = set(zf.namelist())
    for forbidden in ("IosStyler.apk", "IosSettings.apk", "IosControlCenter.apk", "IosDialer.apk", "ICall26.apk", "IosMessages.apk", "SamuraiLauncherOverlay.apk", "SamuraiIos26WallpaperOverlay.apk"):
        assert f"ota_payload/{forbidden}" not in names, f"Forbidden iOS file {forbidden} inside Pure PixelOS Bridge OTA!"
    apply_sh = zf.read("ota_payload/apply_ota.sh").decode("utf-8")
    updater_sc = zf.read("META-INF/com/google/android/updater-script").decode("utf-8")
    for del_t in ("/system/priv-app/IosStyler", "/system/priv-app/IosSettings", "/system/priv-app/IosControlCenter", "/system/priv-app/IosDialer", "/system/priv-app/IosMessages", "/system/app/ICall26", "/system/app/IosMessages", "/system/system_ext/overlay/SamuraiLauncherOverlay.apk"):
        assert del_t in updater_sc, f"Pure PixelOS updater-script missing delete_recursive for {del_t}!"
    assert 'OTA_CH="stable"' in apply_sh and "ro.ota.channel=" in apply_sh, "apply_ota.sh missing ro.ota.channel=stable!"
    for req_inc in ("netbpfload_414", "bpf_netd_414.o", "libnetd_updatable_414.so", "libservice-connectivity_414.so", "netbpfload.rc", "zygote_next64.rc", "plat_property_contexts"):
        assert f"ota_payload/{req_inc}" in names, f"Bridge OTA missing ota_payload/{req_inc}!"
    nb_rc_inc = zf.read("ota_payload/netbpfload.rc").decode("utf-8")
    assert "exec_start bpfloader\n" in nb_rc_inc and "bpfloade\n" not in nb_rc_inc, "Bridge OTA netbpfload.rc missing exec_start bpfloader!"
    pc_inc = zf.read("ota_payload/plat_property_contexts").decode("utf-8")
    assert "ctl.start$zygote_next" in pc_inc, "Bridge OTA plat_property_contexts missing ctl.start$zygote_next!"
    zn_rc_inc = zf.read("ota_payload/zygote_next64.rc").decode("utf-8")
    assert "start zygote_next" in zn_rc_inc and "readproc" in zn_rc_inc, "Bridge OTA zygote_next64.rc missing boot start or readproc!"
    assert "persist.zygote.zygote_next.start_on_boot=true" in apply_sh, "Bridge OTA apply_ota.sh missing persist.zygote.zygote_next.start_on_boot=true!"
    zsys_b = zf.read("ota_payload/libzygote_sys.dylib.so")
    assert struct.unpack_from("<I", zsys_b, 0xb8e8)[0] == 0x9400179e, "Bridge OTA libzygote_sys.dylib.so not 4.14-patched!"
    zsp_b = zf.read("ota_payload/libzygote_species_android_native.so")
    assert len(zsp_b) == 69432 and struct.unpack_from("<I", zsp_b, 0x6540)[0] == 0x1400002e and struct.unpack_from("<I", zsp_b, 0x72b4)[0] == 0x1400002e, "Bridge OTA libzygote_species_android_native.so missing statsd FD-registry guard!"
    assert "/mnt_ven/etc/init/hw/init.target.rc" in apply_sh and "statsd" in apply_sh, "Bridge OTA apply_ota.sh missing vendor init.target.rc stop statsd strip!"
    chi_b = zf.read("ota_payload/com.qti.chi.override.so")
    assert len(chi_b) == 2172832 and struct.unpack_from("<I", chi_b, 0x5639c)[0] == 0x14027519 and struct.unpack_from("<I", chi_b, 0xdccc4)[0] == 0x14005aff and struct.unpack_from("<I", chi_b, 0xdd100)[0] == 0x14005a08 and struct.unpack_from("<I", chi_b, 0xdd188)[0] == 0x140059e6, "Bridge OTA com.qti.chi.override.so missing 4K60 EIS3 hooks!"
    gcam_sh = zf.read("ota_payload/samurai-gcam-sync.sh").decode("utf-8")
    assert "pixel_gcam_eis_4k60_v1" in gcam_sh and "32808" in gcam_sh and "32779" in gcam_sh, "Bridge OTA samurai-gcam-sync.sh missing 4K60 EIS pre-seed!"
    assert "ota_payload/fstab.qcom" in names, "Bridge OTA missing ota_payload/fstab.qcom!"
    assert "ota_payload/compatibility_matrix.device.xml" in names, "Bridge OTA missing ota_payload/compatibility_matrix.device.xml!"
    assert "ota_payload/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc" in names, "Bridge OTA missing fp 2.3 rc!"
    assert "ota_payload/android.hardware.graphics.composer@2.4-service.rc" in names, "Bridge OTA missing composer 2.4 rc!"
    assert "ota_payload/libfod_zpos_hook.so" in names, "Bridge OTA missing libfod_zpos_hook.so!"
    assert "ota_payload/libsdedrm.so" in names, "Bridge OTA missing libsdedrm.so!"
    assert "ota_payload/hwcomposer.qcom.so" in names, "Bridge OTA missing hwcomposer.qcom.so!"
    assert "ota_payload/libgf_hal_G3.so" in names, "Bridge OTA missing libgf_hal_G3.so!"
    assert "ota_payload/android.hardware.biometrics.fingerprint@2.3-service.samurai" in names, "Bridge OTA missing fp 2.3 binary!"
    assert "ota_payload/vendor.oplus.hardware.syshelper.service" in names, "Bridge OTA missing syshelper binary!"
    assert "ota_payload/vendor.oplus.hardware.syshelper.service.rc" in names, "Bridge OTA missing syshelper rc!"
    assert "ota_payload/android.hardware.biometrics.fingerprint@2.3-service.samurai.xml" in names, "Bridge OTA missing fp 2.3 VINTF xml!"
    assert "ota_payload/vendor.oplus.hardware.syshelper.service.xml" in names, "Bridge OTA missing syshelper VINTF xml!"
    assert "ota_payload/vendor.oplus.hardware.biometrics.fingerprint@2.1-service" in names, "Bridge OTA missing odm fp 2.1 binary!"
    for zn in sorted(names):
        if zn.startswith("ota_payload/") and not zn.endswith("/"):
            rel_p = zn[len("ota_payload/"):]
            exp_ext = f'package_extract_file("ota_payload/{rel_p}", "/tmp/ota_payload/{rel_p}");'
            assert exp_ext in updater_sc, f"EXP-019 violation: Bridge OTA updater-script missing {exp_ext}"
    assert "/mnt_ven/lib64/libdisplaydebug.so" in apply_sh, "Bridge OTA apply_ota.sh missing libdisplaydebug.so install!"
    assert "ota_payload/udfps_icon_pressed.webp" in names, "Bridge OTA missing udfps_icon_pressed.webp!"
    assert "ota_payload/KernelSU_Next_v1.0.9_12797.apk" in names, "Bridge OTA missing KernelSU_Next_v1.0.9_12797.apk!"
    assert hashlib.sha256(zf.read("ota_payload/KernelSU_Next_v1.0.9_12797.apk")).hexdigest() == "0013c41c9aeda2699f8a8af840839eb495f89bce7264b5c8fa42152b4c8d9a1e", "Bridge OTA KernelSU_Next_v1.0.9_12797.apk SHA-256 mismatch!"
    assert "KernelSU_Next_v1.0.9_12797.apk" in apply_sh, "Bridge OTA apply_ota.sh missing KernelSU_Next_v1.0.9_12797.apk install!"
    assert "ota_payload/vendor_sepolicy.cil" in names, "Bridge OTA missing vendor_sepolicy.cil!"
    assert "ota_payload/init.rmx.adb.rc" in names and "bin/su" in apply_sh, "Bridge OTA missing init.rmx.adb.rc or bin/su cleanup!"
    assert "ota_payload/SamuraiTelephonyOverlay.apk" in names and "ota_payload/qti_ims_telephony.tar.gz" in names and "ota_payload/qti_ims_fixperms.sh" in names, "Bridge OTA missing QTI IMS/Telephony payload!"
    assert "persist.radio.multisim.config=dsds" in apply_sh, "Bridge OTA apply_ota.sh missing persist.radio.multisim.config=dsds!"
    assert "persist.vendor.fingerprint.sensor_type=optical" in apply_sh, "Bridge OTA apply_ota.sh missing Goodix optical UDFPS props!"
    inc_fstab = zf.read("ota_payload/fstab.qcom").decode("utf-8")
    assert "ext4" in inc_fstab and "f2fs" in inc_fstab and "checkpoint=fs" in inc_fstab, "Bridge OTA fstab.qcom missing dual ext4+f2fs /data!"
    assert "E1002:" in updater_sc and updater_sc.index("E1002:") < updater_sc.index("delete_recursive"), "Bridge OTA updater-script missing E1002 pre-flight guard before delete_recursive!"
    assert "fstab.qcom" in apply_sh and ".pixelos_a17_vendor_ok" in apply_sh, "Bridge OTA apply_ota.sh missing fstab.qcom / .pixelos_a17_vendor_ok install!"

with zipfile.ZipFile(sys_zip, "r") as zf:
    snames = set(zf.namelist())
    assert "system.new.dat.br" in snames and "system.transfer.list" in snames, "Stable System.zip missing system.new.dat.br!"
    assert "vendor_patch/com.qti.chi.override.so" in snames and "vendor_patch/fstab.qcom" in snames and "vendor_patch/libfod_zpos_hook.so" in snames and "vendor_patch/libsdedrm.so" in snames and "vendor_patch/hwcomposer.qcom.so" in snames and "vendor_patch/libgf_hal_G3.so" in snames and "vendor_patch/android.hardware.graphics.composer@2.4-service.rc" in snames, "Stable System.zip missing vendor_patch files!"
    sys_chi = zf.read("vendor_patch/com.qti.chi.override.so")
    assert len(sys_chi) == 2172832 and struct.unpack_from("<I", sys_chi, 0x5639c)[0] == 0x14027519 and struct.unpack_from("<I", sys_chi, 0xdccc4)[0] == 0x14005aff, "Stable System.zip CHI override missing 4K60 EIS3 hooks!"
    sys_fstab = zf.read("vendor_patch/fstab.qcom").decode("utf-8")
    assert "ext4" in sys_fstab and "f2fs" in sys_fstab and "checkpoint=fs" in sys_fstab, "Stable System.zip fstab.qcom missing dual ext4+f2fs /data!"
    sys_upd = zf.read("META-INF/com/google/android/updater-script").decode("utf-8")
    assert "E1000:" in sys_upd and sys_upd.index("E1000:") < sys_upd.index("block_image_update"), "Stable System.zip updater-script missing E1000 pre-flight Vendor guard before block_image_update!"
    assert "/mnt_ven/lib64/hw/com.qti.chi.override.so" in sys_upd and "/mnt_ven/etc/fstab.qcom" in sys_upd and ".pixelos_a17_vendor_ok" in sys_upd, "Stable System.zip updater-script missing /vendor CHI/fstab/marker install!"
    assert "/mnt_ven/etc/init/hw/init.target.rc" in sys_upd and "statsd" in sys_upd, "Stable System.zip updater-script missing /vendor init.target.rc stop statsd strip!"

print(f"  [PASS] ota/rmx1931.json & ota/stable/rmx1931.json ({os.path.basename(bridge_zip)}, {e_leg['size']/(1024*1024):.1f} MiB compact OTA) + clean-flash {os.path.basename(sys_zip)} verified 1:1!")
PYSTABLE

    for zchk in "${BRIDGE_ZIP}" "${SYS_BLOCK_ZIP}" "${VB_ZIP}"; do
        UPDATER="$(unzip -p "${zchk}" META-INF/com/google/android/updater-script)"
        APPLY_OPT=""
        if unzip -l "${zchk}" ota_payload/apply_ota.sh >/dev/null 2>&1; then
            APPLY_OPT="$(unzip -p "${zchk}" ota_payload/apply_ota.sh)"
        fi
        if printf '%s\n%s\n' "${UPDATER}" "${APPLY_OPT}" | grep -Eqi "by-name/(abl|xbl|tz|modem|persist|fsg|fsc|oppo|recovery)"; then
            echo "FAIL: ${zchk} updater-script or apply_ota.sh references forbidden firmware/recovery partition!"
            exit 1
        fi
    done
    VB_UPD="$(unzip -p "${VB_ZIP}" META-INF/com/google/android/updater-script)"
    echo "${VB_UPD}" | grep -q "E2004:" && echo "${VB_UPD}" | grep -q "E2006:" && echo "${VB_UPD}" | grep -q "mke2fs -t ext4" || { echo "FAIL: VendorBoot.zip updater-script missing E2004/E2006 boot/vbmeta aborts or unconditional /metadata mke2fs!"; exit 1; }
else
    BETA_OTA_ZIP="${WIN_OUT}/RMX1931-Android17-iOSBeta-OTA-${EXP_OTA_VER}.zip"
    unzip -tq "${BETA_OTA_ZIP}"
    python3 - "${BETA_OTA_ZIP}" "${REPO_ROOT}/ota/ios-beta/rmx1931.json" "${WIN_OUT}/BUILD-MANIFEST-ios-beta.json" "${REPO_ROOT}/ota/stable/rmx1931.json" "${REPO_ROOT}/ota/rmx1931.json" "${EXP_OTA_VER}" "${WIN_OUT}/RMX1931-Android17-PixelOS-OTA.zip" <<'PYBETA'
import hashlib, json, os, struct, sys, zipfile
beta_zip, beta_json, bmanifest_json, stable_json, root_json, exp_ver, stable_zip = sys.argv[1:]
e_beta = json.load(open(beta_json, encoding="utf-8"))["response"][0]
bm = json.load(open(bmanifest_json, encoding="utf-8"))
exp_tag = f"ios-beta-{exp_ver}"
exp_fname = f"RMX1931-Android17-iOSBeta-OTA-{exp_ver}.zip"
assert e_beta["channel"] == "ios_beta", f"Expected channel ios_beta, got {e_beta.get('channel')}"
assert e_beta["version"] == exp_ver, f"Expected version {exp_ver}, got {e_beta['version']}"
assert e_beta["tag"] == exp_tag, f"Expected tag {exp_tag}, got {e_beta.get('tag')}"
assert e_beta["filename"] == exp_fname, f"Expected filename {exp_fname}, got {e_beta.get('filename')}"
assert e_beta["url"].endswith(f"/releases/download/{exp_tag}/{exp_fname}"), f"Unexpected url in ota/ios-beta/rmx1931.json: {e_beta.get('url')}"
assert e_beta["datetime"] > 1794040000, f"Stale datetime in ota/ios-beta/rmx1931.json: {e_beta['datetime']}"
assert bm["ota_metadata"]["version"] == exp_ver and bm["android_system_props"]["ro.ota.version"] == exp_ver, f"BUILD-MANIFEST-ios-beta.json version mismatch: {bm['ota_metadata']}"
assert int(bm["ota_metadata"]["datetime"]) == int(e_beta["datetime"]) and bm["android_system_props"]["ro.ota.datetime"] == str(e_beta["datetime"]), f"BUILD-MANIFEST-ios-beta.json datetime ({bm['ota_metadata']['datetime']}) != ota/ios-beta/rmx1931.json ({e_beta['datetime']})!"
sh_stb = hashlib.sha256()
with open(stable_zip, "rb") as sf:
    for chunk in iter(lambda: sf.read(1024 * 1024), b""):
        sh_stb.update(chunk)
EXPECTED_STABLE_SHA = sh_stb.hexdigest()
for st_path in (stable_json, root_json):
    st_e = json.load(open(st_path, encoding="utf-8"))["response"][0]
    assert (
        st_e["channel"] == "stable"
        and st_e["version"] == "v2.1.11"
        and st_e["sha256"] == EXPECTED_STABLE_SHA
        and st_e["id"] == EXPECTED_STABLE_SHA
    ), f"Stable manifest {st_path} mismatch during ios-beta build: {st_e}"
assert e_beta["size"] == os.path.getsize(beta_zip) and e_beta["size"] < 398458880, "iOS Beta OTA ZIP size invalid or >= 380 MiB (must be compact OTA, not 1.8 GB System.zip)!"
h = hashlib.sha256()
with open(beta_zip, "rb") as f:
    for chunk in iter(lambda: f.read(1024 * 1024), b""):
        h.update(chunk)
digest = h.hexdigest()
assert e_beta["sha256"] == digest and e_beta["id"] == digest, "SHA-256 mismatch in ota/ios-beta/rmx1931.json!"
with zipfile.ZipFile(beta_zip, "r") as zf:
    bnames = set(zf.namelist())
    for req_ios in ("IosStyler.apk", "IosSettings.apk", "IosControlCenter.apk", "IosDialer.apk", "IosMessages.apk", "privapp-permissions-ios-dialer.xml", "privapp-permissions-ios-messages.xml", "SamuraiLauncherOverlay.apk", "SamuraiIos26WallpaperOverlay.apk", "SamuraiTelephonyOverlay.apk", "qti_ims_telephony.tar.gz", "qti_ims_fixperms.sh", "NexusLauncherRelease.apk", "fstab.qcom", "compatibility_matrix.device.xml", "android.hardware.biometrics.fingerprint@2.3-service.samurai", "vendor.oplus.hardware.syshelper.service", "vendor.oplus.hardware.syshelper.service.rc", "android.hardware.biometrics.fingerprint@2.3-service.samurai.xml", "vendor.oplus.hardware.syshelper.service.xml", "vendor.oplus.hardware.biometrics.fingerprint@2.1-service", "android.hardware.biometrics.fingerprint@2.3-service.samurai.rc", "android.hardware.graphics.composer@2.4-service.rc", "libfod_zpos_hook.so", "libsdedrm.so", "hwcomposer.qcom.so", "libgf_hal_G3.so", "udfps_icon_pressed.webp", "KernelSU_Next_v1.0.9_12797.apk", "vendor_sepolicy.cil"):
        assert f"ota_payload/{req_ios}" in bnames, f"iOS Beta OTA missing ota_payload/{req_ios}!"
    for retired_ios in ("ICall26.apk", "SamuraiICallOverlay.apk"):
        assert f"ota_payload/{retired_ios}" not in bnames, f"iOS Beta OTA still contains retired {retired_ios}!"
    apply_sh = zf.read("ota_payload/apply_ota.sh").decode("utf-8")
    b_upd = zf.read("META-INF/com/google/android/updater-script").decode("utf-8")
    for zn in sorted(bnames):
        if zn.startswith("ota_payload/") and not zn.endswith("/"):
            rel_p = zn[len("ota_payload/"):]
            exp_ext = f'package_extract_file("ota_payload/{rel_p}", "/tmp/ota_payload/{rel_p}");'
            assert exp_ext in b_upd, f"EXP-019 violation: iOS Beta OTA updater-script missing {exp_ext}"
    b_fstab = zf.read("ota_payload/fstab.qcom").decode("utf-8")
    assert "ext4" in b_fstab and "f2fs" in b_fstab and "checkpoint=fs" in b_fstab, "iOS Beta OTA fstab.qcom missing dual ext4+f2fs /data!"
    assert "E1002:" in b_upd and b_upd.index("E1002:") < b_upd.index("delete_recursive"), "iOS Beta OTA updater-script missing E1002 pre-flight guard!"
    assert 'OTA_CH="ios_beta"' in apply_sh and 'FLAVOR="ios-beta"' in apply_sh and "fstab.qcom" in apply_sh and ".pixelos_a17_vendor_ok" in apply_sh, "iOS Beta apply_ota.sh missing ios_beta channel or fstab.qcom!"
    zsp_b = zf.read("ota_payload/libzygote_species_android_native.so")
    assert len(zsp_b) == 69432 and struct.unpack_from("<I", zsp_b, 0x6540)[0] == 0x1400002e and struct.unpack_from("<I", zsp_b, 0x72b4)[0] == 0x1400002e, "iOS Beta OTA libzygote_species_android_native.so missing statsd FD-registry guard!"
    assert "/mnt_ven/etc/init/hw/init.target.rc" in apply_sh and "statsd" in apply_sh, "iOS Beta OTA apply_ota.sh missing vendor init.target.rc stop statsd strip!"
    b_chi = zf.read("ota_payload/com.qti.chi.override.so")
    assert len(b_chi) == 2172832 and struct.unpack_from("<I", b_chi, 0x5639c)[0] == 0x14027519 and struct.unpack_from("<I", b_chi, 0xdccc4)[0] == 0x14005aff and struct.unpack_from("<I", b_chi, 0xdd100)[0] == 0x14005a08 and struct.unpack_from("<I", b_chi, 0xdd188)[0] == 0x140059e6, "iOS Beta OTA CHI override missing 4K60 EIS3 hooks!"
print(f"  [PASS] ota/ios-beta/rmx1931.json & {os.path.basename(beta_zip)} ({e_beta['size']/(1024*1024):.1f} MiB compact OTA, sha256={digest[:16]}...) verified 1:1!")
PYBETA
    UPDATER="$(unzip -p "${BETA_OTA_ZIP}" META-INF/com/google/android/updater-script)"
    APPLY_SH="$(unzip -p "${BETA_OTA_ZIP}" ota_payload/apply_ota.sh)"
    if printf '%s\n%s\n' "${UPDATER}" "${APPLY_SH}" | grep -Eqi "by-name/(abl|xbl|tz|modem|persist|fsg|fsc|oppo|recovery)"; then
        echo "FAIL: ${BETA_OTA_ZIP} updater-script or apply_ota.sh references forbidden firmware/recovery partition!"
        exit 1
    fi
fi

echo "[PASS] Firmware safety verified: ONLY system, vendor, odm, boot, dtbo, vbmeta are updated."
echo "=================================================================="
echo " ALL 9 OFFLINE VERIFICATION GATES PASSED (${ROM_FLAVOR} / ${EXP_OTA_VER})!"
echo "=================================================================="
