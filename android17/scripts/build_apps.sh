#!/usr/bin/env bash
# Dual-Channel RMX1931 Android 17 App, Framework & Overlay Builder:
#   - ROM_FLAVOR=pixelos  (default): Pure Retail Google PixelOS (stock Google NexusLauncherRelease,
#     stock Google Dialer/Messages, 4 hardware/Pixel overlays, ZERO iOS apps/overlays).
#   - ROM_FLAVOR=ios-beta          : Opt-In iOS Style UI Beta (Shared Core + Liquid Glass Launcher,
#     IosControlCenter, IosSettings, IosStyler, IosDialer, IosMessages, and all 10 RRO overlays).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
A17_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
A17_DIR="${A17_ROOT}"
REPO_ROOT="$(cd "${A17_ROOT}/.." && pwd)"

ROM_FLAVOR="${ROM_FLAVOR:-pixelos}"
REBUILD_MESSAGES=0
while [ $# -gt 0 ]; do
    case "$1" in
        --flavor)
            ROM_FLAVOR="$2"
            shift 2
            ;;
        --flavor=*)
            ROM_FLAVOR="${1#*=}"
            shift
            ;;
        --rebuild-messages)
            REBUILD_MESSAGES=1
            shift
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 2
            ;;
    esac
done
case "${ROM_FLAVOR}" in
    pixelos|ios-beta) ;;
    *)
        echo "ERROR: Invalid ROM_FLAVOR='${ROM_FLAVOR}' (expected 'pixelos' or 'ios-beta')" >&2
        exit 2
        ;;
esac
export ROM_FLAVOR
echo "=== Building RMX1931 Android 17 Components [ROM_FLAVOR=${ROM_FLAVOR}] ==="

export WORK_ROOT="${WORK_ROOT:-/var/tmp/rmx1931-a17}"
TOOLS_DIR="${WORK_ROOT}/tools"
ANDROID_SDK="${TOOLS_DIR}/android-sdk"
BUILD_TOOLS="${ANDROID_SDK}/build-tools/35.0.0"
PLATFORM_JAR="${ANDROID_SDK}/platforms/android-35/android.jar"
KOTLINC="${TOOLS_DIR}/kotlinc/bin/kotlinc"
KOTLIN_STDLIB="${TOOLS_DIR}/kotlinc/lib/kotlin-stdlib.jar"
export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"

AAPT2="${BUILD_TOOLS}/aapt2"
D8="${BUILD_TOOLS}/d8"
ZIPALIGN="${BUILD_TOOLS}/zipalign"
APKSIGNER="${BUILD_TOOLS}/apksigner"

APK_BUILD="${WORK_ROOT}/work/apk-build-main"
OUT_APKS="${WORK_ROOT}/out/apks"
OUT_BIN="${WORK_ROOT}/out/bin"
OUT_FWK="${WORK_ROOT}/out/framework"
OUT_ETC="${WORK_ROOT}/out/etc"
WORK_APKS="${REPO_ROOT}/work/a17/apks"
rm -rf "${APK_BUILD}" "${OUT_APKS}" "${OUT_BIN}" "${OUT_FWK}"
mkdir -p "${APK_BUILD}" "${OUT_APKS}" "${OUT_BIN}" "${OUT_FWK}" "${OUT_ETC}" "${WORK_APKS}"

# Purge any stale v2.0.0 / third-party dialer APKs across output & work directories
for stale_dir in "${OUT_APKS}" "${WORK_APKS}" "${REPO_ROOT}/work/a17/out"; do
    if [ -d "${stale_dir}" ]; then
        rm -f \
            "${stale_dir}/ICall26.apk" "${stale_dir}/ICall26.apk.idsig" \
            "${stale_dir}/SamuraiICallOverlay.apk" "${stale_dir}/SamuraiICallOverlay.apk.idsig" \
            "${stale_dir}/IosClock.apk" "${stale_dir}/IosClock.apk.idsig" \
            "${stale_dir}/IosCalculator.apk" "${stale_dir}/IosCalculator.apk.idsig" \
            "${stale_dir}/IosNotes.apk" "${stale_dir}/IosNotes.apk.idsig" \
            "${stale_dir}/IosWeather.apk" "${stale_dir}/IosWeather.apk.idsig" \
            "${stale_dir}/SystemUI.apk" "${stale_dir}/SystemUI.apk.idsig"
    fi
done

# Persist release keystore in gitignored work/a17/keys/ so OTA APK signatures stay consistent across builds
KEYSTORE_DIR="${REPO_ROOT}/work/a17/keys"
mkdir -p "${KEYSTORE_DIR}"
KEYSTORE="${KEYSTORE_DIR}/samurai-ota-release.jks"
if [ ! -f "${KEYSTORE}" ]; then
    "${JAVA_HOME}/bin/keytool" -genkeypair -noprompt \
        -keystore "${KEYSTORE}" \
        -storepass "samuraiota17" \
        -keypass "samuraiota17" \
        -alias "samuraiota" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=SamuraiPixelOTA, OU=Android17, O=RMX1931, L=IN, ST=IN, C=IN"
fi

if ! command -v aarch64-linux-gnu-gcc >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq gcc-aarch64-linux-gnu libc6-dev-arm64-cross
fi

echo "=== [1/7] Compiling Static ARM64 samurai_hw_daemon, samurai_ota_bcb & 4.14 bpfloader_wrap ==="
aarch64-linux-gnu-gcc -O2 -Wall -Wextra -Werror -static \
    -o "${OUT_BIN}/samurai_hw_daemon" \
    "${A17_ROOT}/SamuraiParts/jni/samurai_hw_daemon.c"
aarch64-linux-gnu-strip "${OUT_BIN}/samurai_hw_daemon"
chmod 0755 "${OUT_BIN}/samurai_hw_daemon"

aarch64-linux-gnu-gcc -O2 -Wall -Wextra -Werror -static \
    -o "${OUT_BIN}/samurai_ota_bcb" \
    "${A17_ROOT}/SamuraiOTAUpdater/jni/samurai_ota_bcb.c"
aarch64-linux-gnu-strip "${OUT_BIN}/samurai_ota_bcb"
chmod 0755 "${OUT_BIN}/samurai_ota_bcb"

aarch64-linux-gnu-gcc -O2 -Wall -Wextra -Werror -nostdlib -static \
    -o "${OUT_BIN}/bpfloader_wrap" \
    "${A17_ROOT}/SamuraiParts/jni/bpfloader_wrap.c"
aarch64-linux-gnu-strip "${OUT_BIN}/bpfloader_wrap"
chmod 0755 "${OUT_BIN}/bpfloader_wrap"

BIONIC_STUBS="${APK_BUILD}/bionic_stubs"
mkdir -p "${BIONIC_STUBS}"
cat << 'EOF' > "${BIONIC_STUBS}/libc_stub.c"
void clock_gettime(void) {}
void nanosleep(void) {}
void open(void) {}
void read(void) {}
void write(void) {}
void close(void) {}
void pthread_mutex_lock(void) {}
void pthread_mutex_unlock(void) {}
void pthread_create(void) {}
void pthread_detach(void) {}
void* malloc(unsigned long n) { (void)n; return 0; }
void free(void* p) { (void)p; }
void* memcpy(void* d, const void* s, unsigned long n) { (void)s; (void)n; return d; }
void* memset(void* d, int c, unsigned long n) { (void)c; (void)n; return d; }
unsigned long strlen(const char* s) { (void)s; return 0; }
EOF
cat << 'EOF' > "${BIONIC_STUBS}/libdl_stub.c"
void dlsym(void) {}
EOF
cat << 'EOF' > "${BIONIC_STUBS}/libgui_stub.S"
.text
.globl _ZN7android7SurfaceC1ERKNS_2spINS_22IGraphicBufferProducerEEEbRKNS1_INS_7IBinderEEENSt3__18optionalINS2_13SurfaceConfigEEE
.type _ZN7android7SurfaceC1ERKNS_2spINS_22IGraphicBufferProducerEEEbRKNS1_INS_7IBinderEEENSt3__18optionalINS2_13SurfaceConfigEEE, @function
_ZN7android7SurfaceC1ERKNS_2spINS_22IGraphicBufferProducerEEEbRKNS1_INS_7IBinderEEENSt3__18optionalINS2_13SurfaceConfigEEE:
    ret
.globl _ZN7android21SurfaceComposerClient20createVirtualDisplayERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEbbS9_jbf
.type _ZN7android21SurfaceComposerClient20createVirtualDisplayERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEbbS9_jbf, @function
_ZN7android21SurfaceComposerClient20createVirtualDisplayERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEbbS9_jbf:
    ret
.globl _ZN7android21SurfaceComposerClient21destroyVirtualDisplayERKNS_2spINS_7IBinderEEE
.type _ZN7android21SurfaceComposerClient21destroyVirtualDisplayERKNS_2spINS_7IBinderEEE, @function
_ZN7android21SurfaceComposerClient21destroyVirtualDisplayERKNS_2spINS_7IBinderEEE:
    ret
.globl _ZN7android21SurfaceComposerClient11Transaction5applyEbb
.type _ZN7android21SurfaceComposerClient11Transaction5applyEbb, @function
_ZN7android21SurfaceComposerClient11Transaction5applyEbb:
    ret
EOF
clang --target=aarch64-linux-android30 -fuse-ld=lld -ffreestanding -nostdinc -fno-builtin -nostdlib -shared -fPIC \
    -Wl,-soname,libc.so -o "${BIONIC_STUBS}/libc.so" "${BIONIC_STUBS}/libc_stub.c"
clang --target=aarch64-linux-android30 -fuse-ld=lld -ffreestanding -nostdinc -fno-builtin -nostdlib -shared -fPIC \
    -Wl,-soname,libdl.so -o "${BIONIC_STUBS}/libdl.so" "${BIONIC_STUBS}/libdl_stub.c"
clang --target=aarch64-linux-android30 -fuse-ld=lld -ffreestanding -nostdinc -fno-builtin -nostdlib -shared -fPIC \
    -Wl,-soname,libgui.so -o "${BIONIC_STUBS}/libgui.so" "${BIONIC_STUBS}/libgui_stub.S"
clang --target=aarch64-linux-android30 -fuse-ld=lld -ffreestanding -nostdinc -fno-builtin -O2 -Wall -Wextra -Werror -nostdlib -shared -fPIC \
    -Wl,-soname,libdisplaydebug.so \
    -o "${OUT_BIN}/libfod_zpos_hook.so" \
    "${A17_ROOT}/vendor-patches/fod_zpos_hook.c" \
    -L"${BIONIC_STUBS}" -lc -ldl
llvm-strip "${OUT_BIN}/libfod_zpos_hook.so"
chmod 0644 "${OUT_BIN}/libfod_zpos_hook.so"
cp -f "${OUT_BIN}/libfod_zpos_hook.so" "${WORK_APKS}/libfod_zpos_hook.so"

clang++ --target=aarch64-linux-android30 -fuse-ld=lld -ffreestanding -nostdinc -fno-builtin -fno-exceptions -fno-rtti \
    -O2 -Wall -Wextra -Werror -nostdlib -shared -fPIC \
    -Wl,-soname,libgui_shim.so -Wl,--no-undefined \
    -o "${OUT_BIN}/libgui_shim.so" \
    "${A17_ROOT}/shims/libgui_shim.cpp" \
    -L"${BIONIC_STUBS}" -lgui -lc
llvm-strip "${OUT_BIN}/libgui_shim.so"
chmod 0644 "${OUT_BIN}/libgui_shim.so"
cp -f "${OUT_BIN}/libgui_shim.so" "${WORK_APKS}/libgui_shim.so"

cp -f "${A17_ROOT}/SamuraiParts/etc/udfps_icon_pressed.webp" "${OUT_ETC}/udfps_icon_pressed.webp"

echo "=== [2/7] Building samurai-mod-helper.jar, com.google.android.dialer.support.jar & Patched services.jar ==="
HELPER_TMP="${APK_BUILD}/SamuraiModHelper"
mkdir -p "${HELPER_TMP}/classes" "${HELPER_TMP}/dex"
"${JAVA_HOME}/bin/javac" -Xlint:all -Werror -source 17 -target 17 \
    -classpath "${PLATFORM_JAR}" \
    -d "${HELPER_TMP}/classes" \
    "${A17_ROOT}/SamuraiParts/helper/src/com/realme/samurai/helper/SamuraiModHelper.java"
mapfile -t helper_cls < <(find "${HELPER_TMP}/classes" -name "*.class" | sort)
"${D8}" --lib "${PLATFORM_JAR}" --min-api 33 --output "${HELPER_TMP}/dex" "${helper_cls[@]}"
(cd "${HELPER_TMP}/dex" && zip -q -0 "${HELPER_TMP}/samurai-mod-helper-unaligned.jar" classes*.dex)
"${ZIPALIGN}" -f 4 "${HELPER_TMP}/samurai-mod-helper-unaligned.jar" "${OUT_FWK}/samurai-mod-helper.jar"

DIALER_SUP_TMP="${APK_BUILD}/DialerSupport"
mkdir -p "${DIALER_SUP_TMP}/classes" "${DIALER_SUP_TMP}/dex"
"${JAVA_HOME}/bin/javac" -Xlint:all -Werror -source 17 -target 17 \
    -classpath "${PLATFORM_JAR}" \
    -d "${DIALER_SUP_TMP}/classes" \
    "${A17_ROOT}/shims/src/com/google/android/dialer/support/DialerSupport.java"
mapfile -t dialer_sup_cls < <(find "${DIALER_SUP_TMP}/classes" -name "*.class" | sort)
"${D8}" --lib "${PLATFORM_JAR}" --min-api 33 --output "${DIALER_SUP_TMP}/dex" "${dialer_sup_cls[@]}"
(cd "${DIALER_SUP_TMP}/dex" && zip -q -0 "${DIALER_SUP_TMP}/dialer-support-unaligned.jar" classes*.dex)
"${ZIPALIGN}" -f 4 "${DIALER_SUP_TMP}/dialer-support-unaligned.jar" "${OUT_FWK}/com.google.android.dialer.support.jar"
cp -f "${OUT_FWK}/com.google.android.dialer.support.jar" "${WORK_APKS}/com.google.android.dialer.support.jar"

python3 "${SCRIPT_DIR}/patch_services_a17.py"
cp -f "${OUT_FWK}/services.jar" "${WORK_APKS}/services.jar"

ls -lh "${OUT_BIN}/samurai_hw_daemon" "${OUT_BIN}/samurai_ota_bcb" "${OUT_BIN}/bpfloader_wrap" \
       "${OUT_BIN}/libgui_shim.so" "${OUT_FWK}/samurai-mod-helper.jar" \
       "${OUT_FWK}/com.google.android.dialer.support.jar" "${OUT_FWK}/services.jar"

build_kotlin_priv_apk() {
    local app_name="$1"
    local app_src="${A17_ROOT}/${app_name}"
    local app_tmp="${APK_BUILD}/${app_name}"
    mkdir -p "${app_tmp}/compiled_res" "${app_tmp}/gen" "${app_tmp}/classes" "${app_tmp}/dex"

    "${AAPT2}" compile --dir "${app_src}/res" -o "${app_tmp}/compiled_res/res.zip"
    "${AAPT2}" link \
        -I "${PLATFORM_JAR}" \
        --manifest "${app_src}/AndroidManifest.xml" \
        --java "${app_tmp}/gen" \
        -o "${app_tmp}/unsigned-unaligned.apk" \
        "${app_tmp}/compiled_res/res.zip"

    mapfile -t kt_files < <(find "${app_src}/src" -name "*.kt" | sort)
    mapfile -t java_files < <(find "${app_tmp}/gen" -name "*.java" | sort)

    "${KOTLINC}" \
        -classpath "${PLATFORM_JAR}" \
        -jvm-target 17 \
        -d "${app_tmp}/classes" \
        "${kt_files[@]}" "${java_files[@]}"

    "${JAVA_HOME}/bin/javac" \
        -source 17 -target 17 \
        -classpath "${PLATFORM_JAR}:${app_tmp}/classes" \
        -d "${app_tmp}/classes" \
        "${java_files[@]}"

    mapfile -t class_files < <(find "${app_tmp}/classes" -name "*.class" | sort)
    "${D8}" \
        --lib "${PLATFORM_JAR}" \
        --min-api 33 \
        --output "${app_tmp}/dex" \
        "${class_files[@]}" \
        "${KOTLIN_STDLIB}"

    cp "${app_tmp}/unsigned-unaligned.apk" "${app_tmp}/with-dex.apk"
    (cd "${app_tmp}/dex" && zip -q -0 "${app_tmp}/with-dex.apk" classes*.dex)

    "${ZIPALIGN}" -f -p 4 "${app_tmp}/with-dex.apk" "${app_tmp}/aligned.apk"
    "${APKSIGNER}" sign \
        --ks "${KEYSTORE}" \
        --ks-key-alias "samuraiota" \
        --ks-pass pass:samuraiota17 \
        --key-pass pass:samuraiota17 \
        --v1-signing-enabled true \
        --v2-signing-enabled true \
        --v3-signing-enabled true \
        --out "${OUT_APKS}/${app_name}.apk" \
        "${app_tmp}/aligned.apk"
}

echo "=== [3/7] Building Shared Privileged SamuraiOTAUpdater.apk & SamuraiParts.apk ==="
build_kotlin_priv_apk "SamuraiOTAUpdater"
build_kotlin_priv_apk "SamuraiParts"

build_rro_apk() {
    local rro_name="$1"
    local manifest_path="$2"
    local res_stage="$3"
    local rro_tmp="${APK_BUILD}/${rro_name}"
    mkdir -p "${rro_tmp}/compiled_res"
    "${AAPT2}" compile --dir "${res_stage}" -o "${rro_tmp}/compiled_res/res.zip"
    "${AAPT2}" link \
        -I "${PLATFORM_JAR}" \
        --manifest "${manifest_path}" \
        --no-resource-deduping \
        -o "${rro_tmp}/unsigned.apk" \
        "${rro_tmp}/compiled_res/res.zip"
    "${ZIPALIGN}" -f -p 4 "${rro_tmp}/unsigned.apk" "${rro_tmp}/aligned.apk"
    "${APKSIGNER}" sign \
        --ks "${KEYSTORE}" \
        --ks-key-alias "samuraiota" \
        --ks-pass pass:samuraiota17 \
        --key-pass pass:samuraiota17 \
        --v1-signing-enabled true \
        --v2-signing-enabled true \
        --v3-signing-enabled true \
        --out "${OUT_APKS}/${rro_name}.apk" \
        "${rro_tmp}/aligned.apk"
}

if [ "${ROM_FLAVOR}" = "pixelos" ]; then
    echo "=== [4/7] Skipping iOS Apps (ROM_FLAVOR=pixelos -> 100% Pure Retail Google PixelOS) ==="
    echo "=== [5/7] Building 4 Pure PixelOS RMX1931 Hardware Overlays ==="
    # 1. SamuraiHardwareOverlay (RMX1931 OLED/UDFPS/90Hz + circular Pixel icons + Google Dialer/Messages)
    HW_STAGE="${APK_BUILD}/SamuraiHardwareOverlay/res_stage"
    mkdir -p "${HW_STAGE}"
    cp -a "${A17_ROOT}/overlays/SamuraiHardwareOverlay/res/." "${HW_STAGE}/"
    cp -f "${A17_ROOT}/overlays-pixelos/SamuraiHardwareOverlay/res/values/config.xml" "${HW_STAGE}/values/config.xml"
    build_rro_apk "SamuraiHardwareOverlay" "${A17_ROOT}/overlays/SamuraiHardwareOverlay/AndroidManifest.xml" "${HW_STAGE}"

    # 2. SamuraiSystemUIOverlay (RMX1931 cutout, UDFPS geometry, 24dp window blur, Pixel GCam/Wallpaper bindings)
    SYSUI_STAGE="${APK_BUILD}/SamuraiSystemUIOverlay/res_stage"
    mkdir -p "${SYSUI_STAGE}"
    cp -a "${A17_ROOT}/overlays/SamuraiSystemUIOverlay/res/." "${SYSUI_STAGE}/"
    build_rro_apk "SamuraiSystemUIOverlay" "${A17_ROOT}/overlays/SamuraiSystemUIOverlay/AndroidManifest.xml" "${SYSUI_STAGE}"

    # 3. SamuraiSettingsOverlay (Pure Pixel Settings config — zero iOS icon overrides)
    SET_STAGE="${APK_BUILD}/SamuraiSettingsOverlay/res_stage"
    mkdir -p "${SET_STAGE}"
    cp -a "${A17_ROOT}/overlays-pixelos/SamuraiSettingsOverlay/res/." "${SET_STAGE}/"
    build_rro_apk "SamuraiSettingsOverlay" "${A17_ROOT}/overlays-pixelos/SamuraiSettingsOverlay/AndroidManifest.xml" "${SET_STAGE}"

    # 4. SamuraiHidePillOverlay (dynamic toggleable RRO, disabled by default on Pure PixelOS)
    HP_STAGE="${APK_BUILD}/SamuraiHidePillOverlay/res_stage"
    mkdir -p "${HP_STAGE}"
    cp -a "${A17_ROOT}/overlays/SamuraiHidePillOverlay/res/." "${HP_STAGE}/"
    build_rro_apk "SamuraiHidePillOverlay" "${A17_ROOT}/overlays/SamuraiHidePillOverlay/AndroidManifest.xml" "${HP_STAGE}"

    # 5. SamuraiTelephonyOverlay (Binds ImsResolver MMTEL->org.codeaurora.ims & RCS->vendor.qti.imsrcs)
    TEL_STAGE="${APK_BUILD}/SamuraiTelephonyOverlay/res_stage"
    mkdir -p "${TEL_STAGE}"
    cp -a "${A17_ROOT}/overlays/SamuraiTelephonyOverlay/res/." "${TEL_STAGE}/"
    build_rro_apk "SamuraiTelephonyOverlay" "${A17_ROOT}/overlays/SamuraiTelephonyOverlay/AndroidManifest.xml" "${TEL_STAGE}"

    echo "=== [6/7] Staging Unmodified Official Retail Google Pixel Launcher (NexusLauncherRelease.apk) ==="
    STOCK_NX="${WORK_ROOT}/downloads/pixel_a17_cp3a/NexusLauncherRelease.apk"
    [ -f "${STOCK_NX}" ] || STOCK_NX="/var/tmp/rmx1931-a17/downloads/pixel_a17_cp3a/NexusLauncherRelease.apk"
    cp -f "${STOCK_NX}" "${OUT_APKS}/NexusLauncherRelease.apk"
else
    echo "=== [4/7] Building Clean-Room Privileged IosStyler.apk, IosDialer.apk & IosMessages.apk (ROM_FLAVOR=ios-beta) ==="
    bash "${A17_ROOT}/glass-core/build_glass_core.sh"
    bash "${A17_ROOT}/ios-styler/build_ios_styler.sh"
    cp -f "${REPO_ROOT}/work/a17/ios-styler/IosStyler.apk" "${OUT_APKS}/IosStyler.apk"

    bash "${A17_ROOT}/ios-dialer/build_ios_dialer.sh"
    [ -f "${OUT_APKS}/IosDialer.apk" ] || cp -f "${REPO_ROOT}/work/a17/ios-dialer/IosDialer.apk" "${OUT_APKS}/IosDialer.apk"

    if [ ! -f "${REPO_ROOT}/work/a17/ios-messages/IosMessages.apk" ] || [ "${REBUILD_MESSAGES}" = "1" ]; then
        bash "${A17_ROOT}/ios-messages/build_ios_messages.sh"
    fi
    cp -f "${REPO_ROOT}/work/a17/ios-messages/IosMessages.apk" "${OUT_APKS}/IosMessages.apk"

    echo "=== [5/7] Building Clean-Room Privileged iOS Settings & Control Center (IosSettings.apk + IosControlCenter.apk) ==="
    if [[ -f "$A17_DIR/ios-settings/build_ios_settings.sh" ]]; then
        bash "$A17_DIR/ios-settings/build_ios_settings.sh"
        [ -f "${OUT_APKS}/IosSettings.apk" ] || cp -f "${REPO_ROOT}/work/a17/apks/IosSettings.apk" "${OUT_APKS}/IosSettings.apk"
    fi
    if [[ -f "$A17_DIR/ios-controlcenter/build_ios_controlcenter.sh" ]]; then
        bash "$A17_DIR/ios-controlcenter/build_ios_controlcenter.sh"
        [ -f "${OUT_APKS}/IosControlCenter.apk" ] || cp -f "${REPO_ROOT}/work/a17/apks/IosControlCenter.apk" "${OUT_APKS}/IosControlCenter.apk"
    fi

    echo "=== [6/7] Building All 10 RMX1931 + iOS 26 Static & Dynamic RRO Overlays ==="
    for RRO_NAME in \
        SamuraiHardwareOverlay \
        SamuraiSystemUIOverlay \
        SamuraiSettingsOverlay \
        SamuraiDialerOverlay \
        SamuraiMessagesOverlay \
        SamuraiGmailOverlay \
        SamuraiPhotosOverlay \
        SamuraiLauncherOverlay \
        SamuraiIos26WallpaperOverlay \
        SamuraiHidePillOverlay \
        SamuraiTelephonyOverlay; do
        RRO_SRC="${A17_ROOT}/overlays/${RRO_NAME}"
        RRO_STAGE="${APK_BUILD}/${RRO_NAME}/res_stage"
        mkdir -p "${RRO_STAGE}"
        if [ -d "${RRO_SRC}/res" ]; then
            cp -a "${RRO_SRC}/res/." "${RRO_STAGE}/"
        fi
        if [ "${RRO_NAME}" = "SamuraiIos26WallpaperOverlay" ]; then
            mkdir -p "${RRO_STAGE}/drawable-nodpi"
            cp -f "${REPO_ROOT}/work/a17/wallpapers/default_wallpaper.png" "${RRO_STAGE}/drawable-nodpi/default_wallpaper.png"
        fi
        build_rro_apk "${RRO_NAME}" "${RRO_SRC}/AndroidManifest.xml" "${RRO_STAGE}"
    done

    echo "=== [7/7] Building Patched Pixel Launcher (Liquid Glass Dock + iOS 26 Icons) ==="
    python3 "${SCRIPT_DIR}/patch_nexus_launcher.py"
fi

echo "=== Verifying Priv-App Whitelists & Flavor Purity [ROM_FLAVOR=${ROM_FLAVOR}] ==="
ROM_FLAVOR="${ROM_FLAVOR}" python3 "${SCRIPT_DIR}/verify_privapp_whitelist.py"

echo "=== RMX1931 Android 17 [${ROM_FLAVOR}] Binaries, Frameworks & APKs built cleanly: ==="
ls -lh "${OUT_BIN}" "${OUT_FWK}" "${OUT_APKS}"
