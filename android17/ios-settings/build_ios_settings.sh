#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

BUILD_DIR="/var/tmp/rmx1931-a17/work/ios-settings-compose"
SDK_DIR="/var/tmp/rmx1931-a17/tools/android-sdk"
BUILD_TOOLS="$SDK_DIR/build-tools/35.0.0"
KEYSTORE="$REPO_ROOT/work/a17/keys/samurai-ota-release.jks"
OUT_APKS_DIR="$REPO_ROOT/work/a17/apks"
OUT_APK="$OUT_APKS_DIR/IosSettings.apk"

export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export PATH="$JAVA_HOME/bin:$BUILD_TOOLS:$PATH"

echo "=== [1/4] Syncing clean IosSettings Compose source tree to $BUILD_DIR ==="
mkdir -p "$BUILD_DIR" "$OUT_APKS_DIR"
rsync -a --delete \
    --exclude '.gradle' \
    --exclude 'build' \
    "$SCRIPT_DIR/" "$BUILD_DIR/"

sed -i 's/\r$//' "$BUILD_DIR/gradlew"
chmod +x "$BUILD_DIR/gradlew"

echo "=== [2/4] Building Release APK (SDK 35, com.realme.samurai.iossettings) ==="
cd "$BUILD_DIR"
GRADLE_CACHED="/root/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle"
if [[ -x "$GRADLE_CACHED" ]]; then
    "$GRADLE_CACHED" assembleRelease --no-daemon
else
    ./gradlew assembleRelease --no-daemon
fi

UNSIGNED_APK="$BUILD_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
if [[ ! -f "$UNSIGNED_APK" ]]; then
    UNSIGNED_APK="$BUILD_DIR/app/build/outputs/apk/release/app-release.apk"
fi
if [[ ! -f "$UNSIGNED_APK" ]]; then
    echo "ERROR: Expected release APK not found in $BUILD_DIR/app/build/outputs/apk/release/" >&2
    exit 1
fi

echo "=== [3/4] Zipaligning (4-byte page-aligned) & Signing IosSettings.apk ==="
ALIGNED_APK="$BUILD_DIR/app/build/outputs/apk/release/IosSettings-aligned.apk"
"$BUILD_TOOLS/zipalign" -f -p 4 "$UNSIGNED_APK" "$ALIGNED_APK"

"$BUILD_TOOLS/apksigner" sign \
    --min-sdk-version 24 \
    --ks "$KEYSTORE" \
    --ks-key-alias samuraiota \
    --ks-pass pass:samuraiota17 \
    --key-pass pass:samuraiota17 \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$OUT_APK" \
    "$ALIGNED_APK"

mkdir -p /var/tmp/rmx1931-a17/out/apks
cp -f "$OUT_APK" /var/tmp/rmx1931-a17/out/apks/IosSettings.apk

echo "=== [4/4] Verifying Signature, Alignment, Badging & Permissions ==="
"$BUILD_TOOLS/zipalign" -c 4 "$OUT_APK"
"$BUILD_TOOLS/apksigner" verify --verbose "$OUT_APK"
"$BUILD_TOOLS/aapt2" dump badging "$OUT_APK" | grep -E "^(package:|sdkVersion:|targetSdkVersion:|application-label:|application:|launchable-activity:)"
"$BUILD_TOOLS/aapt2" dump permissions "$OUT_APK"

echo "=== SUCCESS: $OUT_APK ($(stat -c%s "$OUT_APK") bytes) ==="
