#!/usr/bin/env bash
# Standalone Clean-Room Build Script for iOS 26 Liquid Glass Notes (com.realme.samurai.notes)
# Outputs signed APK to: work/a17/ios-notes/IosNotes.apk and /var/tmp/rmx1931-a17/out/apks/IosNotes.apk
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
A17_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${A17_ROOT}/.." && pwd)"

WORK_ROOT="${WORK_ROOT:-/var/tmp/rmx1931-a17}"
TOOLS_DIR="${WORK_ROOT}/tools"
ANDROID_SDK="${TOOLS_DIR}/android-sdk"
BUILD_TOOLS="${ANDROID_SDK}/build-tools/35.0.0"
PLATFORM_JAR="${ANDROID_SDK}/platforms/android-35/android.jar"
GLASS_JAR="${WORK_ROOT}/out/framework/samurai-glass-core-classes.jar"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

AAPT2="${BUILD_TOOLS}/aapt2"
D8="${BUILD_TOOLS}/d8"
ZIPALIGN="${BUILD_TOOLS}/zipalign"
APKSIGNER="${BUILD_TOOLS}/apksigner"

OUT_DIR="${REPO_ROOT}/work/a17/ios-notes"
APKS_OUT="${WORK_ROOT}/out/apks"
BUILD_TMP="${WORK_ROOT}/work/ios-notes-build"
rm -rf "${BUILD_TMP}"
mkdir -p "${OUT_DIR}" "${APKS_OUT}" "${BUILD_TMP}/compiled_res" "${BUILD_TMP}/gen" "${BUILD_TMP}/classes" "${BUILD_TMP}/dex"

KEYSTORE="${REPO_ROOT}/work/a17/keys/samurai-ota-release.jks"
if [ ! -f "${KEYSTORE}" ]; then
    KEYSTORE="${BUILD_TMP}/notes-fallback.jks"
    "${JAVA_HOME}/bin/keytool" -genkeypair -noprompt \
        -keystore "${KEYSTORE}" \
        -storepass "samuraiota17" \
        -keypass "samuraiota17" \
        -alias "samuraiota" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=SamuraiPixelOTA, OU=Android17, O=RMX1931, L=IN, ST=IN, C=IN"
fi

echo "=== [1/4] Compiling iOS 26 Notes Resources & Manifest via aapt2 ==="
"${AAPT2}" compile --dir "${SCRIPT_DIR}/res" -o "${BUILD_TMP}/compiled_res/res.zip"
"${AAPT2}" link \
    -I "${PLATFORM_JAR}" \
    --manifest "${SCRIPT_DIR}/AndroidManifest.xml" \
    --java "${BUILD_TMP}/gen" \
    --no-resource-deduping \
    -o "${BUILD_TMP}/unsigned-unaligned.apk" \
    "${BUILD_TMP}/compiled_res/res.zip"

echo "=== [2/4] Compiling 90Hz AGSL Java Sources (javac -Xlint:all -Werror) ==="
mapfile -t src_files < <(find "${SCRIPT_DIR}/src" -name "*.java" | sort)
mapfile -t gen_files < <(find "${BUILD_TMP}/gen" -name "*.java" | sort)

"${JAVA_HOME}/bin/javac" \
    -Xlint:all -Werror \
    -source 17 -target 17 \
    -encoding UTF-8 \
    -classpath "${PLATFORM_JAR}:${GLASS_JAR}" \
    -d "${BUILD_TMP}/classes" \
    "${src_files[@]}" "${gen_files[@]}"

echo "=== [3/4] Dexing Bytecode + samurai-glass-core via d8 (--min-api 33) ==="
mapfile -t class_files < <(find "${BUILD_TMP}/classes" -name "*.class" | sort)
"${D8}" \
    --lib "${PLATFORM_JAR}" \
    --min-api 33 \
    --output "${BUILD_TMP}/dex" \
    "${class_files[@]}" "${GLASS_JAR}"

cp "${BUILD_TMP}/unsigned-unaligned.apk" "${BUILD_TMP}/with-dex.apk"
(cd "${BUILD_TMP}/dex" && zip -q -0 "${BUILD_TMP}/with-dex.apk" classes*.dex)

echo "=== [4/4] Zipaligning & Signing IosNotes.apk (v1 + v2 + v3) ==="
"${ZIPALIGN}" -f -p 4 "${BUILD_TMP}/with-dex.apk" "${BUILD_TMP}/aligned.apk"
"${APKSIGNER}" sign \
    --ks "${KEYSTORE}" \
    --ks-key-alias "samuraiota" \
    --ks-pass pass:samuraiota17 \
    --key-pass pass:samuraiota17 \
    --min-sdk-version 24 \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "${OUT_DIR}/IosNotes.apk" \
    "${BUILD_TMP}/aligned.apk"

cp -f "${OUT_DIR}/IosNotes.apk" "${APKS_OUT}/IosNotes.apk"

echo "=== Verifying IosNotes.apk Signature, Alignment & Badging ==="
"${ZIPALIGN}" -c 4 "${OUT_DIR}/IosNotes.apk"
"${APKSIGNER}" verify --verbose --min-sdk-version 21 "${OUT_DIR}/IosNotes.apk"
BADGE_OUT="$("${AAPT2}" dump badging "${OUT_DIR}/IosNotes.apk")"
printf '%s\n' "${BADGE_OUT}" | head -n 15
"${AAPT2}" dump permissions "${OUT_DIR}/IosNotes.apk"
ls -lh "${OUT_DIR}/IosNotes.apk" "${APKS_OUT}/IosNotes.apk"
