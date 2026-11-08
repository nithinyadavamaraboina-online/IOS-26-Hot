#!/usr/bin/env bash
# Offline raw partition image builder (boot, dtbo, vbmeta, vendor, odm, system) for RMX1931 Android 17.
# OTA ZIP & manifest packaging is handled exclusively by build_ota_incremental.sh and build_clean_flash_zips.sh
# so Pure PixelOS Stable (ota/rmx1931.json & ota/stable/rmx1931.json) and Opt-In iOS Beta remain strictly isolated.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
A17_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${A17_ROOT}/.." && pwd)"

export WORK_ROOT="${WORK_ROOT:-/var/tmp/rmx1931-a17}"
DL_DIR="${WORK_ROOT}/downloads"
TOOLS_DIR="${WORK_ROOT}/tools"
STAGE="${WORK_ROOT}/work/stage"
IMG_OUT="${WORK_ROOT}/out/images"
APKS_DIR="${WORK_ROOT}/out/apks"
BIN_DIR="${WORK_ROOT}/out/bin"
MNT_BASE="${WORK_ROOT}/mnt"

export OTA_GITHUB_REPO="${OTA_GITHUB_REPO:-nithinyadavamaraboina-online/PixelOS-Android17-RMX1931}"
export OTA_BUILD_DATETIME="${OTA_BUILD_DATETIME:-$(date -u +%s)}"
OTA_TAG_DATE="$(date -u -d "@${OTA_BUILD_DATETIME}" +%Y%m%d-%H%M)"
export OTA_RELEASE_TAG="${OTA_RELEASE_TAG:-a17-pixelos-${OTA_TAG_DATE}}"

cleanup_mounts() {
    for mp in "${MNT_BASE}"/*; do
        if [ -d "${mp}" ] && mountpoint -q "${mp}" 2>/dev/null; then
            umount -lf "${mp}" || true
        fi
    done
}
trap cleanup_mounts EXIT
cleanup_mounts

SYSTEM_IMG_ONLY=0
IMAGES_ONLY=0
SKIP_APP_BUILD="${SKIP_APP_BUILD:-0}"
for arg in "$@"; do
    case "${arg}" in
        --system-img-only) SYSTEM_IMG_ONLY=1 ;;
        --images-only) IMAGES_ONLY=1 ;;
        --skip-app-build) SKIP_APP_BUILD=1 ;;
    esac
done

if [ "${SYSTEM_IMG_ONLY}" -eq 1 ]; then
    for req_img in boot.img dtbo.img vbmeta.img vendor.raw.img odm.raw.img; do
        [ -f "${IMG_OUT}/${req_img}" ] || { echo "ERROR: --system-img-only requires existing ${IMG_OUT}/${req_img}"; exit 1; }
    done
    mkdir -p "${STAGE}/gsi" "${MNT_BASE}/gsi_ro" "${MNT_BASE}/sys_rw" "${WORK_ROOT}/out"
    if [ "${SKIP_APP_BUILD}" -eq 1 ]; then
        echo "=== [Step 0/2] Using Prebuilt Shared Core + Flavor APKs/Overlays (ROM_FLAVOR=${ROM_FLAVOR:-pixelos}) ==="
    else
        echo "=== [Step 0/2] Building Shared Core + Flavor APKs/Overlays (ROM_FLAVOR=${ROM_FLAVOR:-pixelos}) ==="
        bash "${SCRIPT_DIR}/build_apps.sh"
    fi
    python3 "${SCRIPT_DIR}/verify_privapp_whitelist.py"

    echo "=== [Step 1/2] Preparing Official Google Android 17 GMS GSI (CP3A.260905.010) ==="
    if [ ! -f "${STAGE}/gsi/system.gsi.raw" ]; then
        unzip -q -o "${DL_DIR}/gsi_gms_arm64-CP3A.260905.010.zip" "system.img" -d "${STAGE}/gsi"
        if file "${STAGE}/gsi/system.img" | grep -qi "sparse"; then
            simg2img "${STAGE}/gsi/system.img" "${STAGE}/gsi/system.gsi.raw"
            rm -f "${STAGE}/gsi/system.img"
        else
            mv "${STAGE}/gsi/system.img" "${STAGE}/gsi/system.gsi.raw"
        fi
    fi

    echo "=== [Step 2/2] Creating Linux-4.14-compatible ext4 system.raw.img (ROM_FLAVOR=${ROM_FLAVOR:-pixelos}) ==="
    rm -f "${IMG_OUT}/system.raw.img"
    cp -f "${STAGE}/gsi/system.gsi.raw" "${IMG_OUT}/system.raw.img"
    truncate -s 4260M "${IMG_OUT}/system.raw.img"
    e2fsck -fy "${IMG_OUT}/system.raw.img" >/dev/null || true
    resize2fs "${IMG_OUT}/system.raw.img" 4260M >/dev/null
    if tune2fs -l "${IMG_OUT}/system.raw.img" | grep -q "shared_blocks"; then
        e2fsck -E unshare_blocks -fy "${IMG_OUT}/system.raw.img" >/dev/null || true
    fi
    tune2fs -m 0 -L "/" "${IMG_OUT}/system.raw.img" >/dev/null
    mount -o loop,rw "${IMG_OUT}/system.raw.img" "${MNT_BASE}/sys_rw"
    GSI_USED_MB="$(df -m "${MNT_BASE}/sys_rw" | awk 'NR==2 {print $3}')"
    echo "[gsi] Unshared Android 17 Pixel/GMS GSI used space: ${GSI_USED_MB} MiB"
    if [ "${GSI_USED_MB}" -gt 3960 ]; then
        for opt_bloat in \
            "system/product/priv-app/AICore" \
            "system/product/priv-app/AndroidSystemIntelligence" \
            "system/product/app/SpeechServicesByGoogle" \
            "system/product/app/PixelThemesStub" \
            "system/product/app/WallpaperEffect"; do
            rm -rf "${MNT_BASE}/sys_rw/${opt_bloat}"
        done
    fi

    python3 "${A17_ROOT}/scripts/inject_system_a17.py" \
        "${MNT_BASE}/sys_rw" \
        "${A17_ROOT}" \
        "${APKS_DIR}" \
        "${WORK_ROOT}/out/BUILD-MANIFEST.json"

    echo "[system] Post-injection df -h:"
    df -h "${MNT_BASE}/sys_rw"
    sync
    umount "${MNT_BASE}/sys_rw"

    e2fsck -fy "${IMG_OUT}/system.raw.img" >/dev/null || true
    resize2fs -M "${IMG_OUT}/system.raw.img" >/dev/null
    e2fsck -fy "${IMG_OUT}/system.raw.img" >/dev/null || true
    echo "=== [DONE] Rebuilt ${IMG_OUT}/system.raw.img ($(stat -c%s "${IMG_OUT}/system.raw.img") bytes) for ROM_FLAVOR=${ROM_FLAVOR:-pixelos} ==="
    exit 0
fi

rm -rf "${STAGE}" "${IMG_OUT}"
mkdir -p "${STAGE}/donor" "${STAGE}/gsi" "${STAGE}/ota" "${IMG_OUT}" \
         "${MNT_BASE}/gsi_ro" "${MNT_BASE}/sys_rw" "${MNT_BASE}/vendor_rw" "${MNT_BASE}/odm_rw"

echo "=== [Step 0/7] Building samurai_hw_daemon, samurai_adblockd, SamuraiModHelper, SamuraiParts & Overlays ==="
bash "${SCRIPT_DIR}/build_apps.sh"
python3 "${SCRIPT_DIR}/verify_privapp_whitelist.py"

echo "=== [Step 1/7] Unpacking RMX1931 (samurai) donor partitions ==="
unzip -q -o "${DL_DIR}/samurai-donor-a16.zip" \
    "boot.img" "dtbo.img" "vbmeta.img" \
    "vendor.transfer.list" "vendor.new.dat.br" \
    "odm.transfer.list" "odm.new.dat.br" \
    "META-INF/*" \
    -d "${STAGE}/donor"

echo "[donor] Decompressing vendor.new.dat.br & odm.new.dat.br..."
brotli -d -f "${STAGE}/donor/vendor.new.dat.br" -o "${STAGE}/donor/vendor.new.dat"
python3 "${TOOLS_DIR}/sdat/sdat2img.py" \
    "${STAGE}/donor/vendor.transfer.list" \
    "${STAGE}/donor/vendor.new.dat" \
    "${IMG_OUT}/vendor.raw.img" >/dev/null
rm -f "${STAGE}/donor/vendor.new.dat" "${STAGE}/donor/vendor.new.dat.br"

brotli -d -f "${STAGE}/donor/odm.new.dat.br" -o "${STAGE}/donor/odm.new.dat"
python3 "${TOOLS_DIR}/sdat/sdat2img.py" \
    "${STAGE}/donor/odm.transfer.list" \
    "${STAGE}/donor/odm.new.dat" \
    "${IMG_OUT}/odm.raw.img" >/dev/null
rm -f "${STAGE}/donor/odm.new.dat" "${STAGE}/donor/odm.new.dat.br"

cp "${STAGE}/donor/dtbo.img" "${IMG_OUT}/dtbo.img"
cp "${STAGE}/donor/vbmeta.img" "${IMG_OUT}/vbmeta.img"

echo "=== [Step 2/7] Adapting RMX1931 vendor.img & odm.img for Android 17 (Goodix UDFPS, GCam 9.6, Quad-Lens HAL, CIL) ==="
truncate -s 1640M "${IMG_OUT}/vendor.raw.img"
e2fsck -fy "${IMG_OUT}/vendor.raw.img" >/dev/null || true
resize2fs "${IMG_OUT}/vendor.raw.img" 1600M >/dev/null
if tune2fs -l "${IMG_OUT}/vendor.raw.img" | grep -q "shared_blocks"; then
    e2fsck -E unshare_blocks -fy "${IMG_OUT}/vendor.raw.img" >/dev/null || true
fi

truncate -s 268435456 "${IMG_OUT}/odm.new.raw.img"
mke2fs -q -t ext4 -b 4096 -I 256 -N 2048 -m 0 \
    -O ^has_journal,^orphan_file,^metadata_csum_seed \
    -L "odm" "${IMG_OUT}/odm.new.raw.img"
mkdir -p "${MNT_BASE}/odm_ro"
mount -o loop,ro "${IMG_OUT}/odm.raw.img" "${MNT_BASE}/odm_ro"
mount -o loop,rw "${IMG_OUT}/odm.new.raw.img" "${MNT_BASE}/odm_rw"
rsync -aHAX "${MNT_BASE}/odm_ro/" "${MNT_BASE}/odm_rw/"
ODM_ROOT_CTX="$(getfattr --only-values -n security.selinux "${MNT_BASE}/odm_ro" 2>/dev/null | tr -d '\0' || echo 'u:object_r:vendor_file:s0')"
setfattr -n security.selinux -v "${ODM_ROOT_CTX:-u:object_r:vendor_file:s0}" "${MNT_BASE}/odm_rw"
umount "${MNT_BASE}/odm_ro"

mount -o loop,rw "${IMG_OUT}/vendor.raw.img" "${MNT_BASE}/vendor_rw"

python3 "${A17_ROOT}/vendor-patches/patch_vendor_a17.py" "${MNT_BASE}/vendor_rw"
python3 "${A17_ROOT}/vendor-patches/patch_odm_a17.py" "${MNT_BASE}/odm_rw" "${MNT_BASE}/vendor_rw/firmware"

sync
umount "${MNT_BASE}/odm_rw"
umount "${MNT_BASE}/vendor_rw"
mv -f "${IMG_OUT}/odm.new.raw.img" "${IMG_OUT}/odm.raw.img"

for vpart in vendor odm; do
    e2fsck -fy "${IMG_OUT}/${vpart}.raw.img" >/dev/null || true
    resize2fs -M "${IMG_OUT}/${vpart}.raw.img" >/dev/null
    e2fsck -fy "${IMG_OUT}/${vpart}.raw.img" >/dev/null || true
done

echo "=== [Step 3/7] Building RMX1931 Header v1 boot.img & matched dtbo.img (4.14.356 BPF kernel + AVB v1.0) ==="
python3 "${A17_ROOT}/vendor-patches/repack_boot_a17.py" \
    "${STAGE}/donor/boot.img" \
    "none" \
    "${TOOLS_DIR}/avbtool.py" \
    "${IMG_OUT}/boot.img"

if ! python3 "${TOOLS_DIR}/avbtool.py" info_image --image "${IMG_OUT}/dtbo.img" >/dev/null 2>&1; then
    python3 "${TOOLS_DIR}/avbtool.py" add_hash_footer \
        --image "${IMG_OUT}/dtbo.img" \
        --partition_name dtbo \
        --partition_size 25165824
fi

echo "[avb] Generating signed Flags=3 vbmeta.img (verity + verification disabled, matched boot + dtbo descriptors)..."
mkdir -p "${TOOLS_DIR}/keys"
if [ ! -f "${TOOLS_DIR}/keys/testkey_rsa4096.pem" ]; then
    curl -fsSL "https://android.googlesource.com/platform/external/avb/+/refs/heads/main/test/data/testkey_rsa4096.pem?format=TEXT" \
        | base64 -d > "${TOOLS_DIR}/keys/testkey_rsa4096.pem"
fi
rm -f "${IMG_OUT}/vbmeta.img"
python3 "${TOOLS_DIR}/avbtool.py" make_vbmeta_image \
    --flags 3 \
    --padding_size 4096 \
    --key "${TOOLS_DIR}/keys/testkey_rsa4096.pem" \
    --algorithm SHA256_RSA4096 \
    --include_descriptors_from_image "${IMG_OUT}/boot.img" \
    --include_descriptors_from_image "${IMG_OUT}/dtbo.img" \
    --prop com.android.build.boot.os_version:16 \
    --prop com.android.build.boot.security_patch:2025-12-01 \
    --prop com.android.build.system.os_version:16 \
    --prop com.android.build.vendor.os_version:16 \
    --prop com.android.build.odm.os_version:16 \
    --output "${IMG_OUT}/vbmeta.img"

echo "=== [Step 4/7] Unpacking Official Google Android 17 GMS GSI (CP3A.260905.010) ==="
unzip -q -o "${DL_DIR}/gsi_gms_arm64-CP3A.260905.010.zip" "system.img" -d "${STAGE}/gsi"
if file "${STAGE}/gsi/system.img" | grep -qi "sparse"; then
    echo "[gsi] Converting sparse system.img to raw..."
    simg2img "${STAGE}/gsi/system.img" "${STAGE}/gsi/system.gsi.raw"
    rm -f "${STAGE}/gsi/system.img"
else
    mv "${STAGE}/gsi/system.img" "${STAGE}/gsi/system.gsi.raw"
fi

echo "=== [Step 5/7] Creating Linux-4.14-compatible ext4 system.img (<= 4,487,905,280 B) & injecting Pixel OS ==="
rm -f "${IMG_OUT}/system.raw.img"
cp -f "${STAGE}/gsi/system.gsi.raw" "${IMG_OUT}/system.raw.img"
rm -f "${STAGE}/gsi/system.gsi.raw"
truncate -s 4260M "${IMG_OUT}/system.raw.img"
e2fsck -fy "${IMG_OUT}/system.raw.img" >/dev/null || true
resize2fs "${IMG_OUT}/system.raw.img" 4260M >/dev/null
if tune2fs -l "${IMG_OUT}/system.raw.img" | grep -q "shared_blocks"; then
    e2fsck -E unshare_blocks -fy "${IMG_OUT}/system.raw.img" >/dev/null || true
fi
tune2fs -m 0 -L "/" "${IMG_OUT}/system.raw.img" >/dev/null
mount -o loop,rw "${IMG_OUT}/system.raw.img" "${MNT_BASE}/sys_rw"
GSI_USED_MB="$(df -m "${MNT_BASE}/sys_rw" | awk 'NR==2 {print $3}')"
echo "[gsi] Unshared Android 17 Pixel/GMS GSI used space: ${GSI_USED_MB} MiB"
if [ "${GSI_USED_MB}" -gt 3960 ]; then
    echo "[gsi] Trimming oversized offline NPU model bloat to fit 4.18 GiB RMX1931 system partition..."
    for opt_bloat in \
        "system/product/priv-app/AICore" \
        "system/product/priv-app/AndroidSystemIntelligence" \
        "system/product/app/SpeechServicesByGoogle" \
        "system/product/app/PixelThemesStub" \
        "system/product/app/WallpaperEffect"; do
        if [ -d "${MNT_BASE}/sys_rw/${opt_bloat}" ]; then
            echo "  - Removing oversized background model dir: ${opt_bloat}"
            rm -rf "${MNT_BASE}/sys_rw/${opt_bloat}"
        fi
    done
fi

python3 "${A17_ROOT}/scripts/inject_system_a17.py" \
    "${MNT_BASE}/sys_rw" \
    "${A17_ROOT}" \
    "${APKS_DIR}" \
    "${WORK_ROOT}/out/BUILD-MANIFEST.json"

echo "[system] Post-injection df -h:"
df -h "${MNT_BASE}/sys_rw"
sync
umount "${MNT_BASE}/sys_rw"

e2fsck -fy "${IMG_OUT}/system.raw.img" >/dev/null || true
resize2fs -M "${IMG_OUT}/system.raw.img" >/dev/null
e2fsck -fy "${IMG_OUT}/system.raw.img" >/dev/null || true
echo "=== [DONE] Built all partition images (boot, dtbo, vbmeta, vendor, odm, system) for ROM_FLAVOR=${ROM_FLAVOR:-pixelos} ==="
ls -lh "${IMG_OUT}"
