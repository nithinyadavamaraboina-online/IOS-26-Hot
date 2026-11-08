#!/system/bin/sh
# Samurai Pixel Google Camera 9.6 (BSG V53) /data/app Promoter & TFLite UAF Guard
# - BSG MGC_9.6.080_V53_ENG.apk requires a real /data/app/.../base.apk installation path:
#   1) Java com.android.app.Application.e()/f() scans /proc/self/maps for /data/app/<pkg>.../base.apk
#      before extracting/loading assets/QrQJbYT787QDr7Co (libdobby.so) & codec*.lck (AE/AWB/AF/GXP)
#   2) Native libdobby.so (0x39dda8 -> 0x39e0f4) calls kernel readlink("/proc/self/fd/%s") requiring
#      "base.apk" and verifies SHA256(META-INF/BSG.RSA[:365]) on the unmodified APK
# - Promotes unmodified /vendor/gcam/GoogleCameraEng.apk via PackageInstallerSession into /data/app/...
#   while enforcing the 4-byte TFLite OpenGL fallback UAF fix (0x4fb76ac -> mov x0, #0) on extracted
#   lib/arm64/libgcastartup.so.

export PATH=/system/bin:/system/xbin
umask 0022

PKG="com.google.android.GoogleCameraEng"
SRC_APK="/vendor/gcam/GoogleCameraEng.apk"
UAF_FIX="/system/etc/samurai-mods/gcastartup_uaf_fix.bin"
LOCK_DIR="/data/ota_package/gcam_sync.lock"
PROMOTED_STAMP="/data/local/tmp/pixel_gcam_bsg_v53_promoted"
EIS_PREF_STAMP="/data/local/tmp/pixel_gcam_eis_4k60_v1"

[ -f "${SRC_APK}" ] || exit 0

mkdir -p /data/ota_package 2>/dev/null || true
if ! mkdir "${LOCK_DIR}" 2>/dev/null; then
    old_pid=""
    [ -f "${LOCK_DIR}/pid" ] && old_pid="$(cat "${LOCK_DIR}/pid" 2>/dev/null)"
    if [ -n "${old_pid}" ] && [ -d "/proc/${old_pid}" ]; then
        exit 0
    fi
    rm -rf "${LOCK_DIR}" 2>/dev/null || true
    mkdir "${LOCK_DIR}" 2>/dev/null || exit 0
fi
printf '%s\n' "$$" > "${LOCK_DIR}/pid" 2>/dev/null || true
trap 'rm -rf "${LOCK_DIR}" 2>/dev/null || true' EXIT

get_gcam_apk() {
    local p
    p="$(pm path "${PKG}" 2>/dev/null | grep -m1 '^package:')" || return 1
    p="${p#package:}"
    [ -n "${p}" ] && [ -f "${p}" ] || return 1
    printf '%s\n' "${p}"
}

enforce_uaf_guard() {
    local apk_path="$1"
    case "${apk_path}" in
        /data/app/*/base.apk) ;;
        *) return 1 ;;
    esac
    local base_dir="${apk_path%/base.apk}"
    local so_dir="${base_dir}/lib/arm64"
    local gcam_so="${so_dir}/libgcastartup.so"

    if [ ! -f "${gcam_so}" ] && [ -d /vendor/gcam/lib/arm64 ]; then
        mkdir -p "${so_dir}" 2>/dev/null || true
        cp -f /vendor/gcam/lib/arm64/*.so "${so_dir}/" 2>/dev/null || true
        chown -R 1000:1000 "${base_dir}/lib" 2>/dev/null || true
        chmod -R 0755 "${base_dir}/lib" 2>/dev/null || true
        chcon -R u:object_r:apk_data_file:s0 "${base_dir}/lib" 2>/dev/null || true
    fi

    [ -f "${gcam_so}" ] && [ -f "${UAF_FIX}" ] || return 1
    if ! dd if="${gcam_so}" bs=4 skip=20897195 count=1 2>/dev/null | cmp -s - "${UAF_FIX}"; then
        dd if="${UAF_FIX}" of="${gcam_so}" bs=4 seek=20897195 count=1 conv=notrunc 2>/dev/null || return 1
        chown 1000:1000 "${gcam_so}" 2>/dev/null || true
        chmod 0755 "${gcam_so}" 2>/dev/null || true
        chcon u:object_r:apk_data_file:s0 "${gcam_so}" 2>/dev/null || true
        am force-stop "${PKG}" >/dev/null 2>&1 || true
    fi
    return 0
}

promote_to_data_app() {
    local sz
    sz="$(stat -c '%s' "${SRC_APK}" 2>/dev/null)" || return 1
    [ -n "${sz}" ] && [ "${sz}" -gt 100000000 ] || return 1

    local v1 v2
    v1="$(settings get global verifier_verify_adb_installs 2>/dev/null)"
    v2="$(settings get global package_verifier_enable 2>/dev/null)"
    settings put global verifier_verify_adb_installs 0 2>/dev/null || true
    settings put global package_verifier_enable 0 2>/dev/null || true

    local ses_out="" ses="" ok=0
    ses_out="$(pm install-create -i com.android.vending -r -d -S "${sz}" 2>&1)" || true
    ses="$(printf '%s' "${ses_out}" | sed -n 's/.*\[\([0-9][0-9]*\)\].*/\1/p')"
    if [ -z "${ses}" ]; then
        ses_out="$(pm install-create --user 0 -i com.android.vending -r -d -S "${sz}" 2>&1)" || true
        ses="$(printf '%s' "${ses_out}" | sed -n 's/.*\[\([0-9][0-9]*\)\].*/\1/p')"
    fi

    if [ -n "${ses}" ]; then
        if pm install-write -S "${sz}" "${ses}" base.apk - < "${SRC_APK}" >/dev/null 2>&1 || \
           pm install-write -S "${sz}" "${ses}" base.apk "${SRC_APK}" >/dev/null 2>&1; then
            if pm install-commit "${ses}" >/dev/null 2>&1; then
                ok=1
            else
                pm install-abandon "${ses}" >/dev/null 2>&1 || true
            fi
        else
            pm install-abandon "${ses}" >/dev/null 2>&1 || true
        fi
    fi

    [ -n "${v1}" ] && [ "${v1}" != "null" ] && settings put global verifier_verify_adb_installs "${v1}" 2>/dev/null || true
    [ -n "${v2}" ] && [ "${v2}" != "null" ] && settings put global package_verifier_enable "${v2}" 2>/dev/null || true
    [ "${ok}" -eq 1 ]
}

# 1. Wait for PackageManagerService readiness & ensure GoogleCameraEng lives in /data/app/.../base.apk
cur_apk=""
for _ in $(seq 1 90); do
    cur_apk="$(get_gcam_apk || true)"
    if [ -n "${cur_apk}" ]; then
        case "${cur_apk}" in
            /data/app/*/base.apk)
                break
                ;;
        esac
        if promote_to_data_app; then
            cur_apk="$(get_gcam_apk || true)"
            case "${cur_apk}" in
                /data/app/*/base.apk)
                    rm -f "${PROMOTED_STAMP}" 2>/dev/null || true
                    break
                    ;;
            esac
        fi
    fi
    sleep 1
done

case "${cur_apk}" in
    /data/app/*/base.apk) ;;
    *) exit 0 ;;
esac

# 2. Enforce TFLite OpenGL fallback UAF guard on /data/app/.../lib/arm64/libgcastartup.so
enforce_uaf_guard "${cur_apk}" || true

# 3. Once User 0 CE storage is unlocked, scrub any stale pre-promotion cache once & grant permissions/role
if [ -d "/data/user/0/${PKG}" ] && [ ! -f "${PROMOTED_STAMP}" ]; then
    am force-stop "${PKG}" >/dev/null 2>&1 || true
    rm -rf "/data/user/0/${PKG}/cache/"* \
           "/data/user/0/${PKG}/code_cache/"* \
           "/data/user_de/0/${PKG}/cache/"* \
           "/data/user_de/0/${PKG}/code_cache/"* 2>/dev/null || true

    for perm in \
        android.permission.CAMERA \
        android.permission.RECORD_AUDIO \
        android.permission.ACCESS_FINE_LOCATION \
        android.permission.ACCESS_COARSE_LOCATION \
        android.permission.READ_MEDIA_IMAGES \
        android.permission.READ_MEDIA_VIDEO \
        android.permission.READ_EXTERNAL_STORAGE \
        android.permission.WRITE_EXTERNAL_STORAGE \
        android.permission.POST_NOTIFICATIONS; do
        pm grant "${PKG}" "${perm}" 2>/dev/null || true
    done
    appops set "${PKG}" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
    appops set "${PKG}" WRITE_SETTINGS allow 2>/dev/null || true
    settings put secure camera_double_tap_power_gesture_disabled 0 2>/dev/null || true
    cmd role add-role-holder --user 0 android.app.role.CAMERA "${PKG}" 0 2>/dev/null || true

    if [ -f /system/framework/samurai-mod-helper.jar ]; then
        CLASSPATH=/system/framework/samurai-mod-helper.jar \
            /system/bin/app_process64 /system/bin com.realme.samurai.helper.SamuraiModHelper \
            detach-playstore "${PKG}" >/dev/null 2>&1 || true
    fi

    touch "${PROMOTED_STAMP}" 2>/dev/null || true
fi

# 4. Pre-seed out-of-the-box 4K 60fps & 1080p 60fps Spectra 380 Gyro-EIS3 preferences once User 0 CE storage is decrypted
PREF_DIR="/data/user/0/${PKG}/shared_prefs"
PREF_XML="${PREF_DIR}/${PKG}_preferences.xml"
if [ -d "/data/user/0/${PKG}" ] && { [ -d "/data/user/0/${PKG}/cache" ] || [ "$(getprop sys.user.0.ce_available)" = "true" ]; } && { [ ! -f "${PREF_XML}" ] || [ ! -f "${EIS_PREF_STAMP}" ]; }; then
    APP_UG="$(stat -c '%u:%g' "/data/user/0/${PKG}" 2>/dev/null)"
    APP_CTX="$(stat -c '%C' "/data/user/0/${PKG}" 2>/dev/null)"
    if [ -n "${APP_UG}" ] && [ -n "${APP_CTX}" ]; then
        am force-stop "${PKG}" >/dev/null 2>&1 || true
        mkdir -p "${PREF_DIR}" 2>/dev/null || true
        chown "${APP_UG}" "${PREF_DIR}" 2>/dev/null || true
        chmod 0771 "${PREF_DIR}" 2>/dev/null || true
        chcon "${APP_CTX}" "${PREF_DIR}" 2>/dev/null || true

        if [ ! -f "${PREF_XML}" ] || ! grep -q '<map>' "${PREF_XML}" 2>/dev/null || ! grep -q '</map>' "${PREF_XML}" 2>/dev/null; then
            cat > "${PREF_XML}" <<'GCAM_PREF_EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="pref_camera_hdrplus_option_available_key">1</string>
    <string name="hdr_net_key">1</string>
    <string name="pref_camera_raw_output_option_available_key">1</string>
    <string name="pref_video_stabilization_key">1</string>
    <string name="pref_video_fps_p2018_key">FPS_60</string>
    <string name="ses_60fps_id_key_0">32808</string>
    <string name="ses_60fps_id_key_2">32808</string>
    <string name="ses_60fps_id_key_3">32808</string>
    <string name="ses_30fps_id_key_0">32779</string>
    <string name="ses_30fps_id_key_2">32779</string>
    <string name="ses_30fps_id_key_3">32779</string>
    <string name="ses_autofps_id_key_0">32779</string>
    <string name="ses_autofps_id_key_2">32779</string>
    <string name="ses_autofps_id_key_3">32779</string>
</map>
GCAM_PREF_EOF
        else
            for kv in \
                "pref_camera_hdrplus_option_available_key:1" \
                "hdr_net_key:1" \
                "pref_camera_raw_output_option_available_key:1" \
                "pref_video_stabilization_key:1" \
                "pref_video_fps_p2018_key:FPS_60" \
                "ses_60fps_id_key_0:32808" \
                "ses_60fps_id_key_2:32808" \
                "ses_60fps_id_key_3:32808" \
                "ses_30fps_id_key_0:32779" \
                "ses_30fps_id_key_2:32779" \
                "ses_30fps_id_key_3:32779" \
                "ses_autofps_id_key_0:32779" \
                "ses_autofps_id_key_2:32779" \
                "ses_autofps_id_key_3:32779"; do
                k="${kv%%:*}"
                v="${kv#*:}"
                if grep -q "<string name=\"${k}\"></string>" "${PREF_XML}" 2>/dev/null; then
                    sed -i "s|<string name=\"${k}\"></string>|<string name=\"${k}\">${v}</string>|g" "${PREF_XML}" 2>/dev/null || true
                elif grep -q "<string name=\"${k}\"[[:space:]]*/>" "${PREF_XML}" 2>/dev/null; then
                    sed -i "s|<string name=\"${k}\"[[:space:]]*/>|<string name=\"${k}\">${v}</string>|g" "${PREF_XML}" 2>/dev/null || true
                elif ! grep -q "name=\"${k}\"" "${PREF_XML}" 2>/dev/null; then
                    sed -i "s|</map>|    <string name=\"${k}\">${v}</string>\n</map>|" "${PREF_XML}" 2>/dev/null || true
                fi
            done
        fi

        chown "${APP_UG}" "${PREF_XML}" 2>/dev/null || true
        chmod 0660 "${PREF_XML}" 2>/dev/null || true
        chcon "${APP_CTX}" "${PREF_XML}" 2>/dev/null || true
        if [ -s "${PREF_XML}" ] && grep -q 'ses_60fps_id_key_0' "${PREF_XML}" 2>/dev/null; then
            touch "${EIS_PREF_STAMP}" 2>/dev/null || true
        fi
    fi
fi

exit 0
