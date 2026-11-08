#!/usr/bin/env bash
# Setup WSL2 build toolchain & image utilities for RMX1931 Android 17 Liquid Glass ROM
set -euo pipefail

WORK_ROOT="/var/tmp/rmx1931-a17"
TOOLS_DIR="${WORK_ROOT}/tools"
mkdir -p "${TOOLS_DIR}" "${WORK_ROOT}/downloads" "${WORK_ROOT}/mnt" "${WORK_ROOT}/work" "${WORK_ROOT}/out"

echo "[setup_env] Installing Ubuntu noble packaging & filesystem tools..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq \
    openjdk-17-jdk-headless \
    curl wget aria2 unzip zip p7zip-full \
    brotli lz4 gzip xz-utils cpio \
    e2fsprogs erofs-utils attr acl \
    android-sdk-libsparse-utils \
    python3 python3-pip git bc file

# Android command-line tools (aapt2, d8, zipalign, apksigner, android.jar)
ANDROID_SDK="${TOOLS_DIR}/android-sdk"
if [ ! -x "${ANDROID_SDK}/build-tools/35.0.0/aapt2" ]; then
    echo "[setup_env] Installing Android SDK build-tools 35.0.0 & platform 35..."
    mkdir -p "${ANDROID_SDK}/cmdline-tools"
    CMDLINE_ZIP="${WORK_ROOT}/downloads/commandlinetools-linux-11076708_latest.zip"
    if [ ! -f "${CMDLINE_ZIP}" ]; then
        curl -fL --retry 3 -o "${CMDLINE_ZIP}" \
            "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    fi
    rm -rf "${ANDROID_SDK}/cmdline-tools/latest" "${ANDROID_SDK}/cmdline-tools/cmdline-tools"
    unzip -q "${CMDLINE_ZIP}" -d "${ANDROID_SDK}/cmdline-tools"
    mv "${ANDROID_SDK}/cmdline-tools/cmdline-tools" "${ANDROID_SDK}/cmdline-tools/latest"
    export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
    yes | "${ANDROID_SDK}/cmdline-tools/latest/bin/sdkmanager" --sdk_root="${ANDROID_SDK}" --licenses >/dev/null || true
    "${ANDROID_SDK}/cmdline-tools/latest/bin/sdkmanager" --sdk_root="${ANDROID_SDK}" \
        "build-tools;35.0.0" "platforms;android-35" >/dev/null
fi

# Standalone Kotlin compiler (kotlinc 2.0.21) for fast zero-daemon APK compilation
KOTLIN_DIR="${TOOLS_DIR}/kotlinc"
if [ ! -x "${KOTLIN_DIR}/bin/kotlinc" ]; then
    echo "[setup_env] Installing Kotlin compiler 2.0.21..."
    KOTLIN_ZIP="${WORK_ROOT}/downloads/kotlin-compiler-2.0.21.zip"
    if [ ! -f "${KOTLIN_ZIP}" ]; then
        curl -fL --retry 3 -o "${KOTLIN_ZIP}" \
            "https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip"
    fi
    rm -rf "${KOTLIN_DIR}"
    unzip -q "${KOTLIN_ZIP}" -d "${TOOLS_DIR}"
fi

# sdat2img & img2sdat utilities for TWRP .new.dat.br OTA conversion
SDAT_DIR="${TOOLS_DIR}/sdat"
mkdir -p "${SDAT_DIR}"
if [ ! -f "${SDAT_DIR}/sdat2img.py" ]; then
    curl -fsSL -o "${SDAT_DIR}/sdat2img.py" \
        "https://raw.githubusercontent.com/xpirt/sdat2img/master/sdat2img.py"
fi
if [ ! -d "${TOOLS_DIR}/img2sdat" ]; then
    git clone --depth 1 https://github.com/xpirt/img2sdat.git "${TOOLS_DIR}/img2sdat"
fi

# avbtool.py from AOSP for boot.img / vbmeta.img verification & hash footer signing
if [ ! -f "${TOOLS_DIR}/avbtool.py" ]; then
    if [ -f "/mnt/c/Users/paink/Documents/antigravity/goofy-davinci/downloads/avb/avbtool.py" ]; then
        cp "/mnt/c/Users/paink/Documents/antigravity/goofy-davinci/downloads/avb/avbtool.py" "${TOOLS_DIR}/avbtool.py"
    else
        curl -fsSL "https://android.googlesource.com/platform/external/avb/+/refs/heads/main/avbtool.py?format=TEXT" \
            | base64 -d > "${TOOLS_DIR}/avbtool.py.tmp" && mv "${TOOLS_DIR}/avbtool.py.tmp" "${TOOLS_DIR}/avbtool.py"
    fi
    chmod +x "${TOOLS_DIR}/avbtool.py"
fi

echo "[setup_env] Toolchain ready in ${TOOLS_DIR}"
