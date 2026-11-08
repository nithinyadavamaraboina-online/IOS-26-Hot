#!/usr/bin/env bash
# Fetch verified upstream artifacts for RMX1931 Android 17 + GMS + Liquid Glass build
set -euo pipefail

WORK_ROOT="/var/tmp/rmx1931-a17"
DL_DIR="${WORK_ROOT}/downloads"
mkdir -p "${DL_DIR}"

GSI_URL="https://dl.google.com/developers/android/cinnamonbun/images/gsi/gsi_gms_arm64-exp-CP3A.260905.010-16176746-d55d64aa.zip"
GSI_ZIP="${DL_DIR}/gsi_gms_arm64-CP3A.260905.010.zip"

DONOR_URL="https://sourceforge.net/projects/cyborgbuilds/files/RMX1931/infinity-x/Project_Infinity-X-3.5-samurai-13.12.2025-VANILLA-UNOFFICIAL.zip/download"
DONOR_ZIP="${DL_DIR}/samurai-donor-a16.zip"

KERNEL_URL="https://github.com/nayem8854/kernel_realme_sm8150_openela/releases/download/20260712-141336/Hyper-RMX1931-12072026-1357-KSU.zip"
KERNEL_ZIP="${DL_DIR}/Hyper-RMX1931-12072026-1357-KSU.zip"

echo "[fetch_inputs] 1/4 Downloading Official Google Android 17 GMS GSI (CP3A.260905.010)..."
if [ ! -f "${GSI_ZIP}.done" ]; then
    aria2c -x 16 -s 16 -k 4M --continue=true --allow-overwrite=true \
        -d "${DL_DIR}" -o "gsi_gms_arm64-CP3A.260905.010.zip" "${GSI_URL}"
    unzip -tq "${GSI_ZIP}"
    touch "${GSI_ZIP}.done"
fi

echo "[fetch_inputs] 2/4 Downloading RMX1931 (samurai) donor ROM (vendor/odm/boot/dtbo/vbmeta)..."
if [ ! -f "${DONOR_ZIP}.done" ]; then
    aria2c -x 8 -s 8 -k 4M --continue=true --allow-overwrite=true --max-tries=5 \
        --user-agent="Wget/1.21.4" \
        -d "${DL_DIR}" -o "samurai-donor-a16.zip" "${DONOR_URL}"
    unzip -tq "${DONOR_ZIP}"
    touch "${DONOR_ZIP}.done"
fi

echo "[fetch_inputs] 3/4 Downloading RMX1931 2026 OpenELA 4.14 BPF Kernel..."
if [ ! -f "${KERNEL_ZIP}.done" ]; then
    curl -fL --retry 4 -o "${KERNEL_ZIP}" "${KERNEL_URL}"
    unzip -tq "${KERNEL_ZIP}"
    touch "${KERNEL_ZIP}.done"
fi

echo "[fetch_inputs] 4/4 Cloning shallow nayem8854 samurai lineage-24.0 device tree for reference overlays & configs..."
if [ ! -d "${DL_DIR}/device_realme_samurai_24/.git" ]; then
    rm -rf "${DL_DIR}/device_realme_samurai_24"
    git clone --depth 1 -b lineage-24.0 \
        https://github.com/nayem8854/android_device_realme_samurai.git \
        "${DL_DIR}/device_realme_samurai_24"
fi

echo "[fetch_inputs] All upstream artifacts fetched and verified:"
ls -lh "${DL_DIR}"
