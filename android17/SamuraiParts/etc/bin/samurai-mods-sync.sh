#!/system/bin/sh
# Samurai Google Photos Sysconfig & Optional Cromite Synchronizer for RMX1931 (Android 17, SELinux Enforcing)
# - Ensures zero native DNS/NFQUEUE/hosts or APK bind-mounts exist in PID 1 or Zygote namespaces
# - Keeps Google Photos detached from Play Store split-APK overwrites while Pixel XL sysconfig is active
# - Installs optional open-source Cromite Browser only when requested by the user in SamuraiParts

export PATH=/system/bin:/system/xbin
umask 0022

OTA_DIR="/data/ota_package"
QUEUE_DIR="${OTA_DIR}/hw/queue"
MODS_PROP="${OTA_DIR}/hw/mods.prop"
STAGE_DIR="${OTA_DIR}/mods_stage"
MODS_ROOT="/data/system/samurai_mods"

mkdir -p "${QUEUE_DIR}" "${STAGE_DIR}" "${MODS_ROOT}"
chmod 0777 "${QUEUE_DIR}" "${STAGE_DIR}" 2>/dev/null || true
chown 0:0 "${MODS_ROOT}" 2>/dev/null || true
chmod 0700 "${MODS_ROOT}" 2>/dev/null || true
restorecon "${MODS_ROOT}" 2>/dev/null || true

# Clean up legacy /data/adb/samurai_mods state and restore strict /data/adb permissions
rm -rf /data/adb/samurai_mods 2>/dev/null || true
if [ -d /data/adb ]; then
    chown 0:0 /data/adb 2>/dev/null || true
    chmod 0700 /data/adb 2>/dev/null || true
    restorecon /data/adb 2>/dev/null || true
fi

# Unmount any legacy bind-mounts from older OTA versions so PID 1 and Zygote mount tables remain stock
for legacy_mp in \
    /system/etc/hosts \
    /product/app/Photos/Photos.apk \
    /system/product/app/Photos/Photos.apk; do
    if grep -Fq " ${legacy_mp} " /proc/1/mounts 2>/dev/null; then
        nsenter -t 1 -m -- /system/bin/umount -l "${legacy_mp}" 2>/dev/null || umount -l "${legacy_mp}" 2>/dev/null || true
    fi
    for zpid in $(pidof zygote64 zygote 2>/dev/null); do
        if grep -Fq " ${legacy_mp} " "/proc/${zpid}/mounts" 2>/dev/null; then
            nsenter -t "${zpid}" -m -- /system/bin/umount -l "${legacy_mp}" 2>/dev/null || true
        fi
    done
done
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

    [ -n "${v1}" ] && [ -n "${v1}" ] && [ "${v1}" != "null" ] && settings put global verifier_verify_adb_installs "${v1}" 2>/dev/null || true
    [ -n "${v2}" ] && [ "${v2}" != "null" ] && settings put global package_verifier_enable "${v2}" 2>/dev/null || true
    [ "${ok}" -eq 1 ]
}

has_photos_sysconfig() {
    [ -f /product/etc/sysconfig/pixel_2016_exclusive.xml ] || \
    [ -f /system/product/etc/sysconfig/pixel_2016_exclusive.xml ]
}

write_mods_status() {
    local tmp="${MODS_PROP}.tmp"
    local p_apk cr_apk
    p_apk="$(get_pkg_apk_path com.google.android.apps.photos || true)"
    cr_apk="$(get_pkg_apk_path org.cromite.cromite || true)"

    local st_photos="stock"
    if [ -n "${p_apk}" ] && has_photos_sysconfig; then
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

migrate_stale_client_caches_once() {
    local stamp="${MODS_ROOT}/.client_env_migrated_v1"
    [ -f "${stamp}" ] && return 0
    local prefs_dir="/data/user/0/com.phonepe.app/shared_prefs"
    if [ -d "${prefs_dir}" ]; then
        am force-stop com.phonepe.app >/dev/null 2>&1 || true
        rm -f "${prefs_dir}"/*GUARDIAN* \
              "${prefs_dir}"/Y29tLnBob25lcGU* \
              "${prefs_dir}"/b740dd50* 2>/dev/null || true
    fi
    touch "${stamp}" 2>/dev/null || true
    chmod 0600 "${stamp}" 2>/dev/null || true
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

    # 2. One-shot cleanup of stale pre-fix app environment caches after OTA upgrade
    migrate_stale_client_caches_once

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
