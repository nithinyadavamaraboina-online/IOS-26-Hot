#!/system/bin/sh
# Samurai Google Photos Unlimited Backup & Optional Cromite Synchronizer for RMX1931 (Android 17, SELinux Enforcing)
# - Ensures zero native DNS/NFQUEUE/hosts interception is active for full-speed 90Hz Chrome & YouTube
# - Replaces stock Google Photos offline via /system/etc/samurai-mods/photos-gsi-dex.zip
#   (Unlimited Original Quality Pixel XL backup) and detaches from Play Store split-APK overwrites
# - Installs optional open-source Cromite Browser only when requested by the user in SamuraiParts

export PATH=/system/bin:/system/xbin
umask 0022

OTA_DIR="/data/ota_package"
QUEUE_DIR="${OTA_DIR}/hw/queue"
MODS_PROP="${OTA_DIR}/hw/mods.prop"
STAGE_DIR="${OTA_DIR}/mods_stage"
MODS_ROOT="/data/adb/samurai_mods"
PHOTOS_GSI_DEX="/system/etc/samurai-mods/photos-gsi-dex.zip"

mkdir -p "${QUEUE_DIR}" "${STAGE_DIR}" "${MODS_ROOT}"
chmod 0777 "${QUEUE_DIR}" "${STAGE_DIR}" 2>/dev/null || true
chmod 0755 "/data/adb" "${MODS_ROOT}" 2>/dev/null || true

# Clean up any legacy v1.0/v1.1 AdBlocker hosts bind-mount or state if present
if grep -Fq " /system/etc/hosts " /proc/1/mounts 2>/dev/null; then
    nsenter -t 1 -m -- /system/bin/umount -l /system/etc/hosts 2>/dev/null || umount -l /system/etc/hosts 2>/dev/null || true
fi
rm -rf "${OTA_DIR}/adblock" "${OTA_DIR}/hw/adblock.prop" "${QUEUE_DIR}"/adblock_*.req 2>/dev/null || true

run_helper() {
    CLASSPATH=/system/framework/samurai-mod-helper.jar \
        /system/bin/app_process64 /system/bin com.realme.samurai.helper.SamuraiModHelper "$@"
}

get_pkg_apk_path() {
    local pkg="$1"
    local out
    out="$(pm path "${pkg}" 2>/dev/null | grep -m1 '^package:')" || return 1
    out="${out#package:}"
    [ -n "${out}" ] && [ -f "${out}" ] || return 1
    printf '%s\n' "${out}"
}

get_pkg_version() {
    local pkg="$1"
    local ver
    ver="$(dumpsys package "${pkg}" 2>/dev/null | grep -m1 'versionName=')" || return 1
    ver="${ver#*versionName=}"
    ver="$(printf '%s' "${ver}" | tr -d '\r\n ')"
    [ -n "${ver}" ] || return 1
    printf '%s\n' "${ver}"
}

is_path_mounted() {
    local target="$1"
    grep -Fq " ${target} " /proc/1/mounts 2>/dev/null
}

bind_mount_global() {
    local src_apk="$1"
    local dst_apk="$2"
    local pkg="$3"
    local is_sys="$4"

    if [ "${is_sys}" = "1" ]; then
        chown 0:0 "${src_apk}" 2>/dev/null || true
        chmod 0644 "${src_apk}" 2>/dev/null || true
        chcon u:object_r:system_file:s0 "${src_apk}" 2>/dev/null || true
    else
        chown 1000:1000 "${src_apk}" 2>/dev/null || true
        chmod 0644 "${src_apk}" 2>/dev/null || true
        chcon u:object_r:apk_data_file:s0 "${src_apk}" 2>/dev/null || true
    fi

    nsenter -t 1 -m -- /system/bin/mount -o bind "${src_apk}" "${dst_apk}" || return 1
    for zpid in $(pidof zygote64 zygote 2>/dev/null); do
        if ! grep -Fq " ${dst_apk} " "/proc/${zpid}/mounts" 2>/dev/null; then
            nsenter -t "${zpid}" -m -- /system/bin/mount -o bind "${src_apk}" "${dst_apk}" 2>/dev/null || true
        fi
    done
    am force-stop "${pkg}" >/dev/null 2>&1 || true
    return 0
}

pm_install_stock_apk() {
    local pkg="$1"
    local apk="$2"
    local sz
    sz="$(stat -c '%s' "${apk}" 2>/dev/null)" || return 1

    local v1 v2
    v1="$(settings get global verifier_verify_adb_installs 2>/dev/null)"
    v2="$(settings get global package_verifier_enable 2>/dev/null)"
    settings put global verifier_verify_adb_installs 0 2>/dev/null || true
    settings put global package_verifier_enable 0 2>/dev/null || true

    chown 1000:1000 "${apk}" 2>/dev/null || true
    chmod 0644 "${apk}" 2>/dev/null || true
    chcon u:object_r:apk_data_file:s0 "${apk}" 2>/dev/null || true

    local ok=0
    for attempt in 1 2; do
        local ses
        ses="$(pm install-create --user 0 -i com.android.vending -r -d -S "${sz}" 2>&1)" || true
        ses="${ses#*[}"
        ses="${ses%]*}"
        if [ -n "${ses}" ] && pm install-write -S "${sz}" "${ses}" base.apk "${apk}" >/dev/null 2>&1; then
            local c_out
            if c_out="$(pm install-commit "${ses}" 2>&1)"; then
                ok=1
                break
            fi
            if echo "${c_out}" | grep -Eq "DOWNGRADE|INCOMPATIBLE|DUPLICATE"; then
                pm uninstall-system-updates "${pkg}" >/dev/null 2>&1 || pm uninstall --user 0 "${pkg}" >/dev/null 2>&1 || true
                continue
            fi
        fi
        break
    done

    [ -n "${v1}" ] && [ "${v1}" != "null" ] && settings put global verifier_verify_adb_installs "${v1}" 2>/dev/null || true
    [ -n "${v2}" ] && [ "${v2}" != "null" ] && settings put global package_verifier_enable "${v2}" 2>/dev/null || true
    [ "${ok}" -eq 1 ]
}

ensure_saved_bundle_mounted() {
    local pkg="$1"
    local pkg_dir="${MODS_ROOT}/${pkg}"
    [ -f "${pkg_dir}/patched.apk" ] && [ -f "${pkg_dir}/version.txt" ] || return 1

    local want_ver cur_ver cur_apk
    want_ver="$(tr -d '\r\n ' < "${pkg_dir}/version.txt" 2>/dev/null)"
    cur_apk="$(get_pkg_apk_path "${pkg}" || true)"
    [ -n "${cur_apk}" ] || return 1

    if is_path_mounted "${cur_apk}"; then
        return 0
    fi

    cur_ver="$(get_pkg_version "${pkg}" || true)"
    if [ "${cur_ver}" != "${want_ver}" ] || [[ "${cur_apk}" != /data/app/* ]]; then
        if [ -f "${pkg_dir}/stock_libs.apk" ]; then
            pm_install_stock_apk "${pkg}" "${pkg_dir}/stock_libs.apk" || return 1
            cur_apk="$(get_pkg_apk_path "${pkg}" || true)"
        else
            return 1
        fi
    fi

    [[ "${cur_apk}" == /data/app/* ]] || return 1
    local base_dir="${cur_apk%/base.apk}"
    if [ ! -d "${base_dir}/lib/arm64" ] || [ -z "$(ls -A "${base_dir}/lib/arm64" 2>/dev/null)" ]; then
        if [ -f "${pkg_dir}/stock_libs.apk" ]; then
            mkdir -p "${base_dir}/lib/arm64"
            run_helper extract-zip "${pkg_dir}/stock_libs.apk" "${base_dir}/lib/arm64" "lib/arm64-v8a/" "1" >/dev/null 2>&1 || true
            chown -R 1000:1000 "${base_dir}/lib" 2>/dev/null || true
            chmod -R 0755 "${base_dir}/lib" 2>/dev/null || true
            chcon -R u:object_r:apk_data_file:s0 "${base_dir}/lib" 2>/dev/null || true
        fi
    fi

    bind_mount_global "${pkg_dir}/patched.apk" "${cur_apk}" "${pkg}" "0"
}

ensure_offline_photos_unlimited() {
    local pkg="com.google.android.apps.photos"
    local cur_apk
    cur_apk="$(get_pkg_apk_path "${pkg}" || true)"
    [ -n "${cur_apk}" ] || return 1

    if is_path_mounted "${cur_apk}"; then
        return 0
    fi

    local pkg_dir="${MODS_ROOT}/${pkg}"
    mkdir -p "${pkg_dir}"

    if [[ "${cur_apk}" == /data/app/* ]]; then
        [ -f "${pkg_dir}/patched.apk" ] && return 1
        pm uninstall-system-updates "${pkg}" >/dev/null 2>&1 || true
        cur_apk="$(get_pkg_apk_path "${pkg}" || true)"
        [ -n "${cur_apk}" ] || return 1
        [[ "${cur_apk}" == /data/app/* ]] && return 1
    fi
    [ -f "${PHOTOS_GSI_DEX}" ] || return 1
    local merged_apk="${pkg_dir}/patched_gsi.apk"
    local stock_sz
    stock_sz="$(stat -c '%s' "${cur_apk}" 2>/dev/null)"
    local prev_sz=""
    [ -f "${pkg_dir}/gsi_stock.sz" ] && prev_sz="$(cat "${pkg_dir}/gsi_stock.sz" 2>/dev/null)"

    local need_compile=0
    if [ ! -s "${merged_apk}" ] || [ "${stock_sz}" != "${prev_sz}" ]; then
        if run_helper merge-dex "${cur_apk}" "${PHOTOS_GSI_DEX}" "${merged_apk}" >/dev/null 2>&1; then
            printf '%s\n' "${stock_sz}" > "${pkg_dir}/gsi_stock.sz"
            rm -f /data/dalvik-cache/arm64/*Photos* 2>/dev/null || true
            need_compile=1
        else
            return 1
        fi
    fi

    if bind_mount_global "${merged_apk}" "${cur_apk}" "${pkg}" "1"; then
        if [ "${cur_apk}" = "/product/app/Photos/Photos.apk" ] && [ -f "/system/product/app/Photos/Photos.apk" ] && ! is_path_mounted "/system/product/app/Photos/Photos.apk"; then
            bind_mount_global "${merged_apk}" "/system/product/app/Photos/Photos.apk" "${pkg}" "1" || true
        fi
        if [ "${need_compile}" -eq 1 ]; then
            nice -n 19 cmd package compile -m speed-profile -f "${pkg}" >/dev/null 2>&1 || true
        fi
        run_helper detach-playstore "${pkg}" >/dev/null 2>&1 || true
        return 0
    fi
    return 1
}

write_mods_status() {
    local tmp="${MODS_PROP}.tmp"
    local p_apk cr_apk
    p_apk="$(get_pkg_apk_path com.google.android.apps.photos || true)"
    cr_apk="$(get_pkg_apk_path org.cromite.cromite || true)"

    local st_photos="stock"
    if [ -n "${p_apk}" ] && is_path_mounted "${p_apk}"; then
        st_photos="unlimited_active ($(get_pkg_version com.google.android.apps.photos || echo active))"
    fi

    local st_cr="not_installed"
    [ -n "${cr_apk}" ] && st_cr="installed ($(get_pkg_version org.cromite.cromite || echo ready))"

    {
        printf 'mod_photos=%s\n' "${st_photos}"
        printf 'mod_cromite=%s\n' "${st_cr}"
    } > "${tmp}"
    chmod 0644 "${tmp}" 2>/dev/null || true
    mv -f "${tmp}" "${MODS_PROP}"
}

sync_all_mods() {
    # 0. Keep retired experimental & third-party dialer apps purged if shadows remain
    for retired_pkg in \
        com.mayoh.contact.icontact \
        com.realme.samurai.clock \
        com.realme.samurai.calculator \
        com.realme.samurai.notes \
        com.realme.samurai.weather; do
        if pm path "${retired_pkg}" >/dev/null 2>&1; then
            pm uninstall "${retired_pkg}" >/dev/null 2>&1 || pm uninstall --user 0 "${retired_pkg}" >/dev/null 2>&1 || true
        fi
    done

    local cur_ch
    cur_ch="$(getprop ro.ota.channel 2>/dev/null)"
    if [ "${cur_ch}" = "ios_beta" ]; then
        pm disable-user --user 0 com.google.android.dialer >/dev/null 2>&1 || true
        pm disable-user --user 0 com.google.android.apps.messaging >/dev/null 2>&1 || true
    else
        cmd package install-existing --user 0 com.google.android.dialer >/dev/null 2>&1 || true
        cmd package install-existing --user 0 com.google.android.apps.messaging >/dev/null 2>&1 || true
        pm enable --user 0 com.google.android.dialer >/dev/null 2>&1 || true
        pm enable --user 0 com.google.android.apps.messaging >/dev/null 2>&1 || true
    fi

    # 1. Install optional open-source Cromite Browser if user requested it
    if [ -s "${STAGE_DIR}/samurai-cromite-arm64.apk" ]; then
        pm_install_stock_apk "org.cromite.cromite" "${STAGE_DIR}/samurai-cromite-arm64.apk" && \
            rm -f "${STAGE_DIR}/samurai-cromite-arm64.apk"
    fi

    # 2. Ensure Google Photos Pixel XL Unlimited Original Quality backup is active offline
    ensure_saved_bundle_mounted "com.google.android.apps.photos" || ensure_offline_photos_unlimited || true

    # 3. Keep Google Photos backup detached from Play Store split-APK overwrites
    run_helper detach-playstore com.google.android.apps.photos >/dev/null 2>&1 || true

    write_mods_status
}

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 1; done
until pm path android >/dev/null 2>&1; do sleep 1; done
sleep 2

sync_all_mods
LAST_PKG_STAMP="$(stat -c '%s:%Y' /data/system/packages.list 2>/dev/null || true)"

tick=0
while true; do
    for req in "${QUEUE_DIR}"/mods_*.req; do
        [ -f "${req}" ] || continue
        while IFS='=' read -r k _; do
            case "${k}" in
                MODS_SYNC_NOW)
                    sync_all_mods
                    LAST_PKG_STAMP="$(stat -c '%s:%Y' /data/system/packages.list 2>/dev/null || true)"
                    ;;
            esac
        done < "${req}"
        rm -f "${req}"
    done

    tick=$((tick + 1))
    if [ "${tick}" -ge 6 ]; then
        tick=0
        if [ -s "${STAGE_DIR}/samurai-cromite-arm64.apk" ]; then
            sync_all_mods
            LAST_PKG_STAMP="$(stat -c '%s:%Y' /data/system/packages.list 2>/dev/null || true)"
        else
            CUR_PKG_STAMP="$(stat -c '%s:%Y' /data/system/packages.list 2>/dev/null || true)"
            if [ "${CUR_PKG_STAMP}" != "${LAST_PKG_STAMP}" ]; then
                LAST_PKG_STAMP="${CUR_PKG_STAMP}"
                write_mods_status
            fi
        fi
    fi
    sleep 5
done
