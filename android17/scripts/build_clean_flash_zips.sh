#!/usr/bin/env bash
# Dual-Channel Block-Image Package Builder (< 2.0 GiB per asset for GitHub Releases):
#   - ROM_FLAVOR=pixelos (default):
#       1. RMX1931-Android17-PixelOS-VendorBoot.zip (Clean Flash [1/2]: vendor, odm, boot, dtbo, vbmeta)
#       2. RMX1931-Android17-PixelOS-System.zip     (Clean Flash [2/2] AND Full Atomic Stable System OTA)
#       + Writes ota/stable/rmx1931.json
#   - ROM_FLAVOR=ios-beta (or --system-only):
#       1. RMX1931-Android17-iOSBeta-System-${OTA_VERSION}.zip (Full Atomic iOS Style UI Beta System OTA)
#       + Writes ota/ios-beta/rmx1931.json
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
A17_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${A17_ROOT}/.." && pwd)"

export WORK_ROOT="${WORK_ROOT:-/var/tmp/rmx1931-a17}"
export ROM_FLAVOR="${ROM_FLAVOR:-pixelos}"
export OTA_GITHUB_REPO="${OTA_GITHUB_REPO:-nithinyadavamaraboina-online/PixelOS-Android17-RMX1931}"
export OTA_BUILD_DATETIME="${OTA_BUILD_DATETIME:-$(date -u +%s)}"

DL_DIR="${WORK_ROOT}/downloads"
TOOLS_DIR="${WORK_ROOT}/tools"
STAGE="${WORK_ROOT}/work/clean_flash_stage"
IMG_OUT="${WORK_ROOT}/out/images"
WIN_OUT="${REPO_ROOT}/work/a17/out"

SYSTEM_ONLY=0
REUSE_VENDORBOOT=0
WRITE_OTA_MANIFEST=0
for arg in "$@"; do
    case "${arg}" in
        --system-only) SYSTEM_ONLY=1 ;;
        --reuse-vendorboot) REUSE_VENDORBOOT=1 ;;
        --write-ota-manifest) WRITE_OTA_MANIFEST=1 ;;
    esac
done

if [ "${ROM_FLAVOR}" = "ios-beta" ]; then
    SYSTEM_ONLY=1
    OTA_VERSION="${OTA_VERSION:-v2.1.5-b4}"
    OTA_RELEASE_TAG="${OTA_RELEASE_TAG:-ios-beta-${OTA_VERSION}}"
    SYS_FILENAME="${SYS_FILENAME:-RMX1931-Android17-iOSBeta-System-${OTA_VERSION}.zip}"
    OTA_CHANNEL="ios_beta"
    MANIFEST_OUT="${REPO_ROOT}/ota/ios-beta/rmx1931.json"
elif [ "${ROM_FLAVOR}" = "pixelos" ]; then
    OTA_VERSION="${OTA_VERSION:-v2.1.5}"
    OTA_RELEASE_TAG="${OTA_RELEASE_TAG:-pixelos-stable-${OTA_VERSION}}"
    SYS_FILENAME="${SYS_FILENAME:-RMX1931-Android17-PixelOS-System.zip}"
    OTA_CHANNEL="stable"
    MANIFEST_OUT="${REPO_ROOT}/ota/stable/rmx1931.json"
else
    echo "ERROR: Unsupported ROM_FLAVOR='${ROM_FLAVOR}' (expected 'pixelos' or 'ios-beta')" >&2
    exit 1
fi
export OTA_VERSION OTA_RELEASE_TAG

sed -i 's/^import imp$/import importlib as imp/' "${TOOLS_DIR}/img2sdat/common.py"

rm -rf "${STAGE}"
mkdir -p "${STAGE}/vb/META-INF/com/google/android" \
         "${STAGE}/sys/META-INF/com/google/android" \
         "${WORK_ROOT}/work/stage/donor" \
         "${WIN_OUT}" \
         "$(dirname "${MANIFEST_OUT}")"

UPD_BIN="${WORK_ROOT}/work/stage/donor/META-INF/com/google/android/update-binary"
if [ ! -f "${UPD_BIN}" ]; then
    DONOR_ZIP="${DL_DIR}/samurai-donor-a16.zip"
    [ -f "${DONOR_ZIP}" ] || DONOR_ZIP="/var/tmp/rmx1931-a17/downloads/samurai-donor-a16.zip"
    unzip -q -o "${DONOR_ZIP}" "META-INF/*" -d "${WORK_ROOT}/work/stage/donor"
fi

VB_ZIP="${WIN_OUT}/RMX1931-Android17-PixelOS-VendorBoot.zip"
SYS_ZIP="${WIN_OUT}/${SYS_FILENAME}"

BUILD_VB=1
if [ "${SYSTEM_ONLY}" -eq 1 ]; then
    BUILD_VB=0
elif [ "${REUSE_VENDORBOOT}" -eq 1 ] && [ -f "${VB_ZIP}" ]; then
    BUILD_VB=0
    echo "[block-ota] Reusing existing ${VB_ZIP}"
fi

cp -f "${UPD_BIN}" "${STAGE}/sys/META-INF/com/google/android/update-binary"
if [ "${BUILD_VB}" -eq 1 ]; then
    cp -f "${UPD_BIN}" "${STAGE}/vb/META-INF/com/google/android/update-binary"
    PARTS_TO_CONVERT=(vendor odm system)
else
    PARTS_TO_CONVERT=(system)
fi

for part in "${PARTS_TO_CONVERT[@]}"; do
    (
        OUT_SUB="${STAGE}/vb"
        Q_LVL=7
        if [ "${part}" = "system" ]; then
            OUT_SUB="${STAGE}/sys"
            Q_LVL=6
        fi
        echo "[block-ota] Converting ${part}.raw.img -> ${part}.new.dat.br (q=${Q_LVL}, flavor=${ROM_FLAVOR})..."
        img2simg "${IMG_OUT}/${part}.raw.img" "${STAGE}/${part}.sparse.img"
        python3 "${TOOLS_DIR}/img2sdat/img2sdat.py" \
            "${STAGE}/${part}.sparse.img" \
            -o "${OUT_SUB}" \
            -v 4 \
            -p "${part}" >/dev/null
        rm -f "${STAGE}/${part}.sparse.img"
        brotli -q "${Q_LVL}" -f "${OUT_SUB}/${part}.new.dat" -o "${OUT_SUB}/${part}.new.dat.br"
        rm -f "${OUT_SUB}/${part}.new.dat"
        echo "[block-ota] Done ${part}.new.dat.br ($(du -h "${OUT_SUB}/${part}.new.dat.br" | awk '{print $1}'))"
    ) &
done
wait

if [ "${BUILD_VB}" -eq 1 ]; then
    cp -f "${IMG_OUT}/boot.img" "${STAGE}/vb/boot.img"
    cp -f "${IMG_OUT}/dtbo.img" "${STAGE}/vb/dtbo.img"
    cp -f "${IMG_OUT}/vbmeta.img" "${STAGE}/vb/vbmeta.img"

    cat > "${STAGE}/vb/META-INF/com/google/android/updater-script" <<'EOF'
assert(getprop("ro.product.device") == "samurai" || getprop("ro.build.product") == "samurai" ||
       getprop("ro.product.device") == "RMX1931" || getprop("ro.build.product") == "RMX1931" ||
       getprop("ro.product.device") == "RMX1931L1" || getprop("ro.build.product") == "RMX1931L1" ||
       abort("E3004: This package is for device: samurai,RMX1931,RMX1931L1; this device is " + getprop("ro.product.device") + "."););
assert(qcom.verify_trustzone("TZ.XF.5.2-00209","TZ.XF.5.2-398866") == "1" ||
       abort("E3005: Firmware incompatible — Realme UI 2.0 (Android 11) firmware required."););
ui_print("================================================");
ui_print("  Pixel OS Android 17 — Clean Flash [1/2]       ");
ui_print("  Device: Realme X2 Pro (RMX1931 / samurai)     ");
ui_print("  Vendor + Goodix UDFPS ODM + 4.14 BPF Kernel   ");
ui_print("================================================");
ui_print("Flashing RMX1931 Android 17 vendor image...");
show_progress(0.550000, 0);
block_image_update("/dev/block/by-name/vendor", package_extract_file("vendor.transfer.list"), "vendor.new.dat.br", "vendor.patch.dat") ||
  abort("E2001: Failed to update vendor image.");
ui_print("Flashing RMX1931 odm image (Goodix G3 UDFPS)...");
show_progress(0.250000, 0);
block_image_update("/dev/block/by-name/odm", package_extract_file("odm.transfer.list"), "odm.new.dat.br", "odm.patch.dat") ||
  abort("E2002: Failed to update odm image.");
ui_print("Flashing Linux 4.14.356 BPF boot, dtbo & Flags=3 vbmeta...");
show_progress(0.200000, 6);
package_extract_file("boot.img", "/dev/block/bootdevice/by-name/boot") ||
  package_extract_file("boot.img", "/dev/block/by-name/boot") ||
  abort("E2004: Failed to flash boot.img.");
package_extract_file("dtbo.img", "/dev/block/bootdevice/by-name/dtbo") ||
  package_extract_file("dtbo.img", "/dev/block/by-name/dtbo") ||
  abort("E2005: Failed to flash dtbo.img.");
package_extract_file("vbmeta.img", "/dev/block/bootdevice/by-name/vbmeta") ||
  package_extract_file("vbmeta.img", "/dev/block/by-name/vbmeta") ||
  abort("E2006: Failed to flash vbmeta.img.");
ui_print("Formatting /metadata ext4 & clearing BCB for clean Android 17 boot...");
run_program("/sbin/sh", "-c", "META_DEV=/dev/block/by-name/metadata; [ -b \"$META_DEV\" ] || META_DEV=/dev/block/bootdevice/by-name/metadata; MISC_DEV=/dev/block/by-name/misc; [ -b \"$MISC_DEV\" ] || MISC_DEV=/dev/block/bootdevice/by-name/misc; umount /metadata 2>/dev/null || umount -l /metadata 2>/dev/null || true; if [ -b \"$META_DEV\" ]; then mke2fs -t ext4 -b 4096 -O ^orphan_file,^metadata_csum_seed,metadata_csum -F \"$META_DEV\" >/dev/null 2>&1 || mke2fs -t ext4 -b 4096 -O metadata_csum -F \"$META_DEV\" >/dev/null 2>&1 || /system/bin/mke2fs -t ext4 -b 4096 -O metadata_csum -F \"$META_DEV\" >/dev/null 2>&1 || true; fi; if [ -b \"$MISC_DEV\" ]; then dd if=/dev/zero of=\"$MISC_DEV\" bs=4096 count=1 conv=fsync 2>/dev/null || dd if=/dev/zero of=\"$MISC_DEV\" bs=4096 count=1 2>/dev/null || true; fi");
set_progress(1.000000);
ui_print("Part [1/2] Vendor + Boot complete!");
ui_print("Next: flash System.zip [2/2], then Wipe -> Format Data -> type 'yes'.");
EOF
    sed -i 's/\r$//' "${STAGE}/vb/META-INF/com/google/android/updater-script"
fi

if [ "${ROM_FLAVOR}" = "ios-beta" ]; then
    BANNER_TITLE="  Pixel OS Android 17 — iOS Style UI Beta       "
    BANNER_SUB="  90Hz Liquid Glass + 4K60 Gyro-EIS3 + Comms    "
else
    BANNER_TITLE="  Pixel OS Android 17 — Pure Google PixelOS     "
    BANNER_SUB="  Retail Pixel Launcher + 4K60 EIS3 GCam + OTA  "
fi

mkdir -p "${STAGE}/sys/vendor_patch"
debugfs -R "cat /lib64/hw/com.qti.chi.override.so" "${IMG_OUT}/vendor.raw.img" > "${STAGE}/sys/vendor_patch/com.qti.chi.override.so"
debugfs -R "cat /etc/fstab.qcom" "${IMG_OUT}/vendor.raw.img" > "${STAGE}/sys/vendor_patch/fstab.qcom"
debugfs -R "cat /etc/init/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc" "${IMG_OUT}/vendor.raw.img" > "${STAGE}/sys/vendor_patch/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc"
debugfs -R "cat /etc/init/vendor.qti.adsprpc-service.rc" "${IMG_OUT}/vendor.raw.img" > "${STAGE}/sys/vendor_patch/vendor.qti.adsprpc-service.rc"
debugfs -R "cat /etc/init/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc" "${IMG_OUT}/odm.raw.img" > "${STAGE}/sys/vendor_patch/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc"
debugfs -R "cat /etc/selinux/vendor_sepolicy.cil" "${IMG_OUT}/vendor.raw.img" > "${STAGE}/sys/vendor_patch/vendor_sepolicy.cil"
debugfs -R "cat /etc/selinux/vendor_property_contexts" "${IMG_OUT}/vendor.raw.img" > "${STAGE}/sys/vendor_patch/vendor_property_contexts"
debugfs -R "cat /build.prop" "${IMG_OUT}/vendor.raw.img" > "${STAGE}/sys/vendor_patch/vendor_build.prop"
grep -Eq "userdata[[:space:]]+/data[[:space:]]+ext4" "${STAGE}/sys/vendor_patch/fstab.qcom" || { echo "ERROR: vendor fstab.qcom missing ext4 /data!"; exit 1; }
grep -Eq "userdata[[:space:]]+/data[[:space:]]+f2fs" "${STAGE}/sys/vendor_patch/fstab.qcom" || { echo "ERROR: vendor fstab.qcom missing f2fs /data!"; exit 1; }
grep -q "persist.vendor.fingerprint.sensor_type=optical" "${STAGE}/sys/vendor_patch/vendor_build.prop" || { echo "ERROR: vendor build.prop missing Goodix optical UDFPS props!"; exit 1; }
python3 - "${STAGE}/sys/vendor_patch/com.qti.chi.override.so" <<'CHIVFY'
import struct, sys
b = open(sys.argv[1], "rb").read()
assert len(b) == 2172832, f"Unexpected com.qti.chi.override.so size: {len(b)}"
assert struct.unpack_from("<I", b, 0x5639c)[0] == 0x14027519, "Missing Cave 1 EIS3 hook at 0x5639c!"
assert struct.unpack_from("<I", b, 0xdccc4)[0] == 0x14005aff, "Missing Cave 2 EIS3 hook at 0xdccc4!"
assert struct.unpack_from("<I", b, 0xdd100)[0] == 0x14005a08, "Missing Cave 3 EIS3 hook at 0xdd100!"
assert struct.unpack_from("<I", b, 0xdd188)[0] == 0x140059e6, "Missing Cave 3 EIS3 hook at 0xdd188!"
CHIVFY

cat > "${STAGE}/sys/META-INF/com/google/android/updater-script" <<EOF
assert(getprop("ro.product.device") == "samurai" || getprop("ro.build.product") == "samurai" ||
       getprop("ro.product.device") == "RMX1931" || getprop("ro.build.product") == "RMX1931" ||
       getprop("ro.product.device") == "RMX1931L1" || getprop("ro.build.product") == "RMX1931L1" ||
       abort("E3004: This package is for device: samurai,RMX1931,RMX1931L1; this device is " + getprop("ro.product.device") + "."););
assert(qcom.verify_trustzone("TZ.XF.5.2-00209","TZ.XF.5.2-398866") == "1" ||
       abort("E3005: Firmware incompatible — Realme UI 2.0 (Android 11) firmware required."););
ui_print("================================================");
ui_print("${BANNER_TITLE}");
ui_print("  Device: Realme X2 Pro (RMX1931) • ${OTA_VERSION}");
ui_print("${BANNER_SUB}");
ui_print("================================================");
ui_print("Verifying PixelOS Android 17 Vendor + Boot base [1/2]...");
run_program("/sbin/sh", "-c", "VEN_DEV=/dev/block/by-name/vendor; [ -b \"\$VEN_DEV\" ] || VEN_DEV=/dev/block/bootdevice/by-name/vendor; umount /vendor /mnt_vchk 2>/dev/null || umount -l /vendor /mnt_vchk 2>/dev/null || true; mkdir -p /mnt_vchk; ok=1; if mount -t ext4 -o ro \"\$VEN_DEV\" /mnt_vchk 2>/dev/null || mount -t ext4 \"\$VEN_DEV\" /mnt_vchk 2>/dev/null; then if [ -f /mnt_vchk/etc/.pixelos_a17_vendor_ok ] || grep -q 'RMX1931_A17_SEPOLICY' /mnt_vchk/etc/selinux/vendor_sepolicy.cil 2>/dev/null; then ok=0; fi; umount /mnt_vchk 2>/dev/null || true; fi; rmdir /mnt_vchk 2>/dev/null || true; exit \$ok") == 0 ||
  abort("E1000: Clean Flash Part [1/2] missing! Flash RMX1931-Android17-PixelOS-VendorBoot.zip FIRST, then flash System.zip [2/2].");
ui_print("Atomically updating Android 17 (CP3A.260905.010) system image...");
show_progress(0.920000, 0);
block_image_update("/dev/block/by-name/system", package_extract_file("system.transfer.list"), "system.new.dat.br", "system.patch.dat") ||
  abort("E1001: Failed to update system image.");
ui_print("Updating Spectra 380 4K60 Gyro-EIS3 HAL, Goodix UDFPS & dual EXT4/F2FS fstab...");
package_extract_file("vendor_patch/com.qti.chi.override.so", "/tmp/com.qti.chi.override.so");
package_extract_file("vendor_patch/fstab.qcom", "/tmp/fstab.qcom");
package_extract_file("vendor_patch/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc", "/tmp/fp23.rc");
package_extract_file("vendor_patch/vendor.qti.adsprpc-service.rc", "/tmp/adsprpc.rc");
package_extract_file("vendor_patch/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc", "/tmp/odm_fp21.rc");
package_extract_file("vendor_patch/vendor_sepolicy.cil", "/tmp/vendor_sepolicy.cil");
package_extract_file("vendor_patch/vendor_property_contexts", "/tmp/vendor_property_contexts");
package_extract_file("vendor_patch/vendor_build.prop", "/tmp/vendor_build.prop");
run_program("/sbin/sh", "-c", "VEN_DEV=/dev/block/by-name/vendor; [ -b \"\$VEN_DEV\" ] || VEN_DEV=/dev/block/bootdevice/by-name/vendor; ODM_DEV=/dev/block/by-name/odm; [ -b \"\$ODM_DEV\" ] || ODM_DEV=/dev/block/bootdevice/by-name/odm; umount /vendor /odm /mnt_ven /mnt_odm 2>/dev/null || umount -l /vendor /odm /mnt_ven /mnt_odm 2>/dev/null || true; e2fsck -fy \"\$VEN_DEV\" >/dev/null 2>&1 || true; resize2fs \"\$VEN_DEV\" >/dev/null 2>&1 || true; mkdir -p /mnt_ven && mount -t ext4 -o rw \"\$VEN_DEV\" /mnt_ven && cp -f /tmp/com.qti.chi.override.so /mnt_ven/lib64/hw/com.qti.chi.override.so && chown 0:0 /mnt_ven/lib64/hw/com.qti.chi.override.so && chmod 0644 /mnt_ven/lib64/hw/com.qti.chi.override.so && chcon u:object_r:vendor_file:s0 /mnt_ven/lib64/hw/com.qti.chi.override.so && cp -f /tmp/fstab.qcom /mnt_ven/etc/fstab.qcom && chown 0:0 /mnt_ven/etc/fstab.qcom && chmod 0644 /mnt_ven/etc/fstab.qcom && chcon u:object_r:vendor_configs_file:s0 /mnt_ven/etc/fstab.qcom && cp -f /tmp/fp23.rc /mnt_ven/etc/init/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc && chown 0:0 /mnt_ven/etc/init/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc && chmod 0644 /mnt_ven/etc/init/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc && chcon u:object_r:vendor_configs_file:s0 /mnt_ven/etc/init/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc && cp -f /tmp/adsprpc.rc /mnt_ven/etc/init/vendor.qti.adsprpc-service.rc && chown 0:0 /mnt_ven/etc/init/vendor.qti.adsprpc-service.rc && chmod 0644 /mnt_ven/etc/init/vendor.qti.adsprpc-service.rc && chcon u:object_r:vendor_configs_file:s0 /mnt_ven/etc/init/vendor.qti.adsprpc-service.rc && cp -f /tmp/vendor_sepolicy.cil /mnt_ven/etc/selinux/vendor_sepolicy.cil && chown 0:0 /mnt_ven/etc/selinux/vendor_sepolicy.cil && chmod 0644 /mnt_ven/etc/selinux/vendor_sepolicy.cil && chcon u:object_r:vendor_configs_file:s0 /mnt_ven/etc/selinux/vendor_sepolicy.cil && cp -f /tmp/vendor_property_contexts /mnt_ven/etc/selinux/vendor_property_contexts && chown 0:0 /mnt_ven/etc/selinux/vendor_property_contexts && chmod 0644 /mnt_ven/etc/selinux/vendor_property_contexts && chcon u:object_r:vendor_configs_file:s0 /mnt_ven/etc/selinux/vendor_property_contexts && cp -f /tmp/vendor_build.prop /mnt_ven/build.prop && chown 0:0 /mnt_ven/build.prop && chmod 0600 /mnt_ven/build.prop && chcon u:object_r:vendor_file:s0 /mnt_ven/build.prop && rm -f /mnt_ven/etc/selinux/precompiled_sepolicy /mnt_ven/etc/selinux/precompiled_sepolicy.plat_sepolicy_and_mapping.sha256 2>/dev/null || true && printf 'RMX1931_PIXELOS_A17_VENDOR=1\\n' > /mnt_ven/etc/.pixelos_a17_vendor_ok && chown 0:0 /mnt_ven/etc/.pixelos_a17_vendor_ok && chmod 0644 /mnt_ven/etc/.pixelos_a17_vendor_ok && chcon u:object_r:vendor_configs_file:s0 /mnt_ven/etc/.pixelos_a17_vendor_ok && if [ -f /mnt_ven/etc/init/hw/init.target.rc ]; then sed -i '/^[[:space:]]*stop[[:space:]][[:space:]]*statsd[[:space:]]*$/d' /mnt_ven/etc/init/hw/init.target.rc && chown 0:0 /mnt_ven/etc/init/hw/init.target.rc && chmod 0644 /mnt_ven/etc/init/hw/init.target.rc && chcon u:object_r:vendor_configs_file:s0 /mnt_ven/etc/init/hw/init.target.rc; fi && sync && umount /mnt_ven; rc=\$?; if [ -b \"\$ODM_DEV\" ] && [ -f /tmp/odm_fp21.rc ]; then e2fsck -fy \"\$ODM_DEV\" >/dev/null 2>&1 || true; resize2fs \"\$ODM_DEV\" >/dev/null 2>&1 || true; mkdir -p /mnt_odm; if mount -t ext4 -o rw \"\$ODM_DEV\" /mnt_odm 2>/dev/null; then cp -f /tmp/odm_fp21.rc /mnt_odm/etc/init/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc && chown 0:0 /mnt_odm/etc/init/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc && chmod 0644 /mnt_odm/etc/init/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc && chcon u:object_r:vendor_configs_file:s0 /mnt_odm/etc/init/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc && sync; umount /mnt_odm 2>/dev/null || true; fi; fi; rm -f /tmp/com.qti.chi.override.so /tmp/fstab.qcom /tmp/fp23.rc /tmp/adsprpc.rc /tmp/odm_fp21.rc /tmp/vendor_sepolicy.cil /tmp/vendor_property_contexts /tmp/vendor_build.prop; exit \$rc") == 0 ||
  abort("E2003: Failed to update vendor Spectra 380 Camera HAL, Goodix UDFPS, fstab.qcom & init.target.rc.");
delete_recursive("/data/cache");
delete_recursive("/data/dalvik-cache");
delete_recursive("/data/resource-cache");
delete_recursive("/data/system/package_cache");
run_program("/sbin/sh", "-c", "MISC_DEV=/dev/block/by-name/misc; [ -b \"\$MISC_DEV\" ] || MISC_DEV=/dev/block/bootdevice/by-name/misc; rm -f /data/local/tmp/pixel_ui_polish_initialized /data/local/tmp/samurai_adblock_dns_initialized 2>/dev/null || true; if [ -b \"\$MISC_DEV\" ]; then dd if=/dev/zero of=\"\$MISC_DEV\" bs=4096 count=1 conv=fsync 2>/dev/null || dd if=/dev/zero of=\"\$MISC_DEV\" bs=4096 count=1 2>/dev/null || true; fi");
set_progress(1.000000);
ui_print("Part [2/2] Android 17 Pixel OS System (${OTA_VERSION}) complete!");
ui_print("------------------------------------------------");
ui_print("First-time Clean Flash? Go to Wipe -> Format Data");
ui_print("-> type 'yes' (do NOT use Swipe to Factory Reset).");
ui_print("Both EXT4 and F2FS /data partitions are supported!");
ui_print("------------------------------------------------");
ui_print("Manage Stable vs iOS Style UI Beta in Settings -> System -> System update.");
EOF
sed -i 's/\r$//' "${STAGE}/sys/META-INF/com/google/android/updater-script"

LOCAL_VB_ZIP="${STAGE}/out_VendorBoot.zip"
LOCAL_SYS_ZIP="${STAGE}/out_${SYS_FILENAME}"
rm -f "${SYS_ZIP}" "${LOCAL_SYS_ZIP}"
if [ "${BUILD_VB}" -eq 1 ]; then
    rm -f "${VB_ZIP}" "${LOCAL_VB_ZIP}"
    (cd "${STAGE}/vb"  && zip -q -r -9 -n .br "${LOCAL_VB_ZIP}" .) &
fi
(cd "${STAGE}/sys" && zip -q -r -9 -n .br "${LOCAL_SYS_ZIP}" .) &
wait

if [ "${BUILD_VB}" -eq 1 ]; then
    unzip -tq "${LOCAL_VB_ZIP}"
    VB_SZ=$(stat -c%s "${LOCAL_VB_ZIP}")
    [ "${VB_SZ}" -lt 2147483648 ] || { echo "ERROR: VendorBoot.zip >= 2 GiB!"; exit 1; }
    cp -f "${LOCAL_VB_ZIP}" "${VB_ZIP}"
elif [ -f "${VB_ZIP}" ]; then
    VB_SZ=$(stat -c%s "${VB_ZIP}")
    [ "${VB_SZ}" -lt 2147483648 ] || { echo "ERROR: VendorBoot.zip >= 2 GiB!"; exit 1; }
fi

unzip -tq "${LOCAL_SYS_ZIP}"
SYS_SZ=$(stat -c%s "${LOCAL_SYS_ZIP}")
[ "${SYS_SZ}" -lt 2147483648 ] || { echo "ERROR: ${SYS_FILENAME} >= 2 GiB!"; exit 1; }
SYS_SHA256="$(sha256sum "${LOCAL_SYS_ZIP}" | awk '{print $1}')"
cp -f "${LOCAL_SYS_ZIP}" "${SYS_ZIP}"
[ "$(stat -c%s "${SYS_ZIP}")" -eq "${SYS_SZ}" ] || { echo "ERROR: Copied ${SYS_FILENAME} size mismatch!"; exit 1; }
OTA_DL_URL="https://github.com/${OTA_GITHUB_REPO}/releases/download/${OTA_RELEASE_TAG}/${SYS_FILENAME}"

if [ "${WRITE_OTA_MANIFEST}" -eq 1 ]; then
python3 - "${MANIFEST_OUT}" "${OTA_BUILD_DATETIME}" "${SYS_FILENAME}" "${SYS_SHA256}" "${SYS_SZ}" "${OTA_DL_URL}" "${OTA_VERSION}" "${OTA_RELEASE_TAG}" "${OTA_CHANNEL}" <<'PYEOF'
import json, sys
out_path, dt, fname, sha, size, url, ver, tag, channel = sys.argv[1:]
if channel == "ios_beta":
    changelog = (
        f"Pixel OS {ver} — Opt-In iOS Style UI Beta on Android 17 for Realme X2 Pro (RMX1931)\n"
        "• Lock Screen & Multi-Hour Touch Reliability Fix (IosControlCenter v1.7.6-ios26): fixed bottom Home-Bar swipe-up unlock being stolen by Quickstep's swipe-up SPY monitor, fixed accumulated/expanded Lock Screen notifications swallowing upward unlock drags, hardened WindowManager layout flag commit/recovery & bouncer/camera passthrough cleanup, and added automatic Lock Screen session reset on every wake\n"
        "• iOS 26 Home Indicator Bar & Stacked-Deck App Switcher: persistent SamuraiIos26HomeBar capsule overlay with auto-dimming, launcher/overlay double-pill coordination, curved 3D stacked-deck Recents cards, and 1:1 horizontal home-bar app scrub handoff\n"
        "• 1:1 Finger-Tracked Sliding Liquid Glass Sheets (Notification Center, Control Center & Lock Screen): GPU AGSL moving 28dp rounded convex meniscus rim, chromatic R/B dispersion, Fresnel specular highlight, and velocity-seeded critically damped spring settlement\n"
        "• 90Hz AGSL Liquid Glass Across Phone & Call Screen (IosDialer v1.7.6-ios26): spherical meniscus refraction on all 12 keypad orbs with neighbor-key caustic coupling, live-scrolled backdrop refraction through the 5-tab capsule & search bar, row (i) micro-orbs, and refractive lit/emerald/crimson In-Call lenses"
    )
else:
    changelog = (
        f"Pixel OS {ver} — Pure Retail Google PixelOS Stable for Realme X2 Pro (RMX1931)\n"
        "• Dual EXT4 + F2FS /data Support Out of the Box & Clean-Flash Bootloader-Loop Fix: restored native F2FS + EXT4 /data mounting in /vendor/etc/fstab.qcom (fixing reboot-to-bootloader on TWRP 3.7.0_12-0 when Format Data formats userdata as F2FS), added clean /metadata ext4 formatting & matched Flags=3 vbmeta.img in VendorBoot.zip, and added pre-flight install-order guards\n"
        "• Fixed Websites Not Opening in Chrome, Brave & Chromium Browsers: stripped Qualcomm vendor init.target.rc 'stop statsd' and hardened zygote_next's native species library (libzygote_species_android_native.so)\n"
        "• Out-of-the-Box 4K 60fps & 1080p 60fps Hardware Gyro-EIS3 Video Stabilization: patched Qualcomm Spectra 380 CHI HAL (UsecaseVideo4kEIS3PreviewEIS2 + stream-order-independent binder) & pre-seeded Pixel 9 GCam 9.6 custom EIS sessions (32808 60fps / 32779 30fps)\n"
        "• 100% Pure Google PixelOS Experience on Default Stable Channel + Opt-In 'iOS Style UI Beta' Toggle in Settings -> System -> System update"
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
print(f"[block-ota] Wrote {out_path} (channel={channel}, version={ver}, size={int(size)/(1024*1024):.1f} MiB, sha256={sha[:16]}...)")
PYEOF
fi

if [ "${ROM_FLAVOR}" = "ios-beta" ]; then
    [ -f "${WORK_ROOT}/out/BUILD-MANIFEST.json" ] && cp -f "${WORK_ROOT}/out/BUILD-MANIFEST.json" "${WIN_OUT}/BUILD-MANIFEST-ios-beta.json"
    (cd "${WIN_OUT}" && sha256sum "${SYS_FILENAME}" BUILD-MANIFEST-ios-beta.json > SHA256SUMS-ios-beta.txt)
fi

rm -rf "${STAGE}"
echo "[block-ota] Completed ${SYS_FILENAME} ($((SYS_SZ / 1048576)) MiB)"
