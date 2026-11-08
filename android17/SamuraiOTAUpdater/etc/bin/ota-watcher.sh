#!/system/bin/sh
# Unattended TWRP @/cache/recovery/block.map + misc BCB Stager for RMX1931 A-only Dual-Channel OTA Updates
export PATH=/system/bin:/system/xbin
umask 0022

OTA_DIR="/data/ota_package"
RAW_DIR="/data/ota_raw"

mkdir -p "${OTA_DIR}" "${OTA_DIR}/hw/queue"
chmod 0777 "${OTA_DIR}" "${OTA_DIR}/hw" "${OTA_DIR}/hw/queue" 2>/dev/null || true
restorecon -RF "${OTA_DIR}" 2>/dev/null || true
if [ -d "${RAW_DIR}" ]; then
    chmod 0777 "${RAW_DIR}" 2>/dev/null || true
    restorecon -RF "${RAW_DIR}" 2>/dev/null || true
fi

STAGE_DIRS="${RAW_DIR} ${OTA_DIR} /data/user_de/0/com.realme.ota/files/ota_stage /data/user/0/com.realme.ota/files/ota_stage"
for sdir in ${STAGE_DIRS}; do
    rm -f "${sdir}/install.ready" "${sdir}/apply.status" 2>/dev/null || true
done

# Clean any already-applied /cache/recovery/update.zip & block.map (+ /data/ota_raw/update.zip if block.map was armed)
if [ -x /system/bin/samurai_ota_bcb ]; then
    /system/bin/samurai_ota_bcb --clear-cache >/dev/null 2>&1 || true
fi

# Retired experimental & legacy third-party dialer cleanup
if pm path android >/dev/null 2>&1; then
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
fi

CUR_CH="$(/system/bin/getprop ro.ota.channel)"
[ -n "${CUR_CH}" ] || CUR_CH="stable"
TARGET_SCHEMA="${CUR_CH}-v2.1.4"
if [ "$(/system/bin/getprop persist.sys.samurai.icon_schema)" != "${TARGET_SCHEMA}" ]; then
    rm -f /data/data/com.google.android.apps.nexuslauncher/databases/app_icons.db* \
          /data/user/0/com.google.android.apps.nexuslauncher/databases/app_icons.db* \
          /data/user_de/0/com.google.android.apps.nexuslauncher/databases/app_icons.db* 2>/dev/null || true
    rm -rf /data/data/com.google.android.apps.nexuslauncher/cache/* \
           /data/user/0/com.google.android.apps.nexuslauncher/cache/* \
           /data/user_de/0/com.google.android.apps.nexuslauncher/cache/* 2>/dev/null || true
    /system/bin/setprop persist.sys.samurai.icon_schema "${TARGET_SCHEMA}" 2>/dev/null || true
    /system/bin/am force-stop com.google.android.apps.nexuslauncher >/dev/null 2>&1 || true
fi

A11Y_COMP="com.realme.samurai.controlcenter/com.realme.samurai.controlcenter.service.IosSystemOverlayAccessibilityService"
if [ "${CUR_CH}" = "ios_beta" ] && /system/bin/pm path com.realme.samurai.controlcenter >/dev/null 2>&1; then
    # Root-level Control Center privilege & overlay startup guarantee on iOS Style UI Beta
    /system/bin/cmd deviceidle whitelist +com.realme.samurai.controlcenter >/dev/null 2>&1 || true
    for ccperm in \
        android.permission.BLUETOOTH_CONNECT \
        android.permission.READ_PHONE_STATE \
        android.permission.POST_NOTIFICATIONS; do
        /system/bin/pm grant com.realme.samurai.controlcenter "${ccperm}" >/dev/null 2>&1 || true
    done
    /system/bin/appops set com.realme.samurai.controlcenter FINE_LOCATION ignore >/dev/null 2>&1 || true
    /system/bin/appops set com.realme.samurai.controlcenter COARSE_LOCATION ignore >/dev/null 2>&1 || true
    /system/bin/appops set com.realme.samurai.controlcenter SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
    /system/bin/appops set com.realme.samurai.controlcenter WRITE_SETTINGS allow >/dev/null 2>&1 || true
    /system/bin/appops set com.realme.samurai.controlcenter RUN_IN_BACKGROUND allow >/dev/null 2>&1 || true
    /system/bin/appops set com.realme.samurai.controlcenter RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true
    /system/bin/cmd notification allow_listener com.realme.samurai.controlcenter/com.realme.samurai.controlcenter.service.IosNotificationListenerService >/dev/null 2>&1 || true
    CUR_A11Y="$(/system/bin/settings get secure enabled_accessibility_services 2>/dev/null)"
    case "${CUR_A11Y}" in
        ""|null)
            /system/bin/settings put secure enabled_accessibility_services "${A11Y_COMP}" >/dev/null 2>&1 || true
            ;;
        *"${A11Y_COMP}"*)
            ;;
        *)
            /system/bin/settings put secure enabled_accessibility_services "${CUR_A11Y}:${A11Y_COMP}" >/dev/null 2>&1 || true
            ;;
    esac
    /system/bin/settings put secure accessibility_enabled 1 >/dev/null 2>&1 || true
    /system/bin/am startservice -a com.realme.samurai.controlcenter.ACTION_ENSURE_RUNNING com.realme.samurai.controlcenter/.service.ControlCenterOverlayService >/dev/null 2>&1 || true
    if /system/bin/pm path com.realme.samurai.dialer >/dev/null 2>&1; then
        /system/bin/cmd role add-role-holder --user 0 android.app.role.DIALER com.realme.samurai.dialer 0 >/dev/null 2>&1 || true
        /system/bin/cmd role add-role-holder --user 0 android.app.role.CALL_SCREENING com.realme.samurai.dialer 0 >/dev/null 2>&1 || true
    fi
    if /system/bin/pm path com.realme.samurai.messages >/dev/null 2>&1; then
        /system/bin/pm grant com.realme.samurai.messages android.permission.CALL_PHONE >/dev/null 2>&1 || true
        /system/bin/cmd role add-role-holder --user 0 android.app.role.SMS com.realme.samurai.messages 0 >/dev/null 2>&1 || true
    fi
else
    # Clean PixelOS Stable: restore/enable stock Google Phone & Google Messages for user 0 and strip stale iOS Accessibility lock
    /system/bin/cmd package install-existing --user 0 com.google.android.dialer >/dev/null 2>&1 || true
    /system/bin/cmd package install-existing --user 0 com.google.android.apps.messaging >/dev/null 2>&1 || true
    /system/bin/pm enable --user 0 com.google.android.dialer >/dev/null 2>&1 || true
    /system/bin/pm enable --user 0 com.google.android.apps.messaging >/dev/null 2>&1 || true
    CUR_A11Y="$(/system/bin/settings get secure enabled_accessibility_services 2>/dev/null)"
    case "${CUR_A11Y}" in
        *"com.realme.samurai.controlcenter"*)
            NEW_A11Y="$(printf '%s' "${CUR_A11Y}" | tr ':' '\n' | grep -v 'com.realme.samurai.controlcenter' | paste -sd: -)"
            if [ -n "${NEW_A11Y}" ]; then
                /system/bin/settings put secure enabled_accessibility_services "${NEW_A11Y}" >/dev/null 2>&1 || true
            else
                /system/bin/settings delete secure enabled_accessibility_services >/dev/null 2>&1 || true
                /system/bin/settings put secure accessibility_enabled 0 >/dev/null 2>&1 || true
            fi
            ;;
    esac
fi

while true; do
    ACTIVE_STAGE=""
    for sdir in ${STAGE_DIRS}; do
        if [ -f "${sdir}/install.ready" ]; then
            ACTIVE_STAGE="${sdir}"
            break
        fi
    done

    if [ -n "${ACTIVE_STAGE}" ]; then
        READY_FLAG="${ACTIVE_STAGE}/install.ready"
        STATUS_FILE="${ACTIVE_STAGE}/apply.status"
        SRC_ZIP="${ACTIVE_STAGE}/update.zip"
        SRC_SHA="${ACTIVE_STAGE}/update.sha256"
        rm -f "${READY_FLAG}" "${STATUS_FILE}"

        if [ ! -s "${SRC_ZIP}" ]; then
            echo "ERROR: Missing update.zip" > "${STATUS_FILE}"
            chmod 0666 "${STATUS_FILE}" 2>/dev/null || true
            restorecon "${STATUS_FILE}" 2>/dev/null || true
            continue
        fi

        if [ -s "${SRC_SHA}" ]; then
            EXPECTED="$(tr -d ' \t\r\n' < "${SRC_SHA}" | tr 'A-Z' 'a-z')"
            ACTUAL="$(/system/bin/sha256sum "${SRC_ZIP}" | awk '{print tolower($1)}')"
            if [ -n "${EXPECTED}" ] && [ "${EXPECTED}" != "${ACTUAL}" ]; then
                echo "ERROR: SHA-256 mismatch (${ACTUAL} != ${EXPECTED})" > "${STATUS_FILE}"
                chmod 0666 "${STATUS_FILE}" 2>/dev/null || true
                restorecon "${STATUS_FILE}" 2>/dev/null || true
                continue
            fi
        fi

        chmod 0644 "${SRC_ZIP}" 2>/dev/null || true

        # Stage update.zip + @/cache/recovery/block.map (using /cache for <=380 MiB or unencrypted /data/ota_raw for >380 MiB)
        # and arm /dev/block/by-name/misc (sda3) with boot-recovery + --update_package=@/cache/recovery/block.map
        BCB_OUT="$(/system/bin/samurai_ota_bcb "${SRC_ZIP}" 2>&1)"
        BCB_RC=$?
        if [ "${BCB_RC}" -ne 0 ]; then
            ERR_LINE="$(printf '%s' "${BCB_OUT}" | tr '\r\n' '  ')"
            echo "ERROR: Recovery BCB staging failed (${ERR_LINE})" > "${STATUS_FILE}"
            chmod 0666 "${STATUS_FILE}" 2>/dev/null || true
            restorecon "${STATUS_FILE}" 2>/dev/null || true
            continue
        fi

        echo "READY" > "${STATUS_FILE}"
        chmod 0666 "${STATUS_FILE}" 2>/dev/null || true
        restorecon "${STATUS_FILE}" 2>/dev/null || true
        sync
        sleep 1
        # IMPORTANT: Issue plain `reboot` (NEVER `reboot,recovery`) so /dev/block/by-name/misc BCB
        # ("boot-recovery" + "--update_package=@/cache/recovery/block.map") is preserved intact for ABL & TWRP!
        setprop sys.powerctl reboot
        /system/bin/reboot 2>/dev/null || true
    fi
    sleep 2
done
