#!/system/bin/sh
# Post-fs-data Dual-Channel OTA Cache & RRO Synchronizer for RMX1931 (Android 17 FBE)
# Runs AFTER /data FBE decryption and BEFORE system_server starts so updated RRO overlays,
# privileged APKs, and channel switches (Clean PixelOS Stable <-> iOS Style UI Beta) take
# effect cleanly on the very first boot!
export PATH=/system/bin:/system/xbin
umask 0022

OTA_DIR="/data/ota_package"
RAW_DIR="/data/ota_raw"
STAMP_FILE="${OTA_DIR}/.applied_ota_version"
CH_STAMP_FILE="${OTA_DIR}/.applied_ota_channel"

CUR_VER="$(/system/bin/getprop ro.ota.version)"
CUR_CH="$(/system/bin/getprop ro.ota.channel)"
[ -n "${CUR_CH}" ] || CUR_CH="stable"

PREV_VER=""
[ -f "${STAMP_FILE}" ] && PREV_VER="$(tr -d '\r\n' < "${STAMP_FILE}" 2>/dev/null)"

PREV_CH=""
if [ -f "${CH_STAMP_FILE}" ]; then
    PREV_CH="$(tr -d '\r\n' < "${CH_STAMP_FILE}" 2>/dev/null)"
elif [ "${CUR_CH}" = "stable" ] && { \
    [ -f /data/local/tmp/pixel_v14_ios26_dock_migrated ] || \
    [ -f /data/local/tmp/pixel_v15_ios26_concentric_migrated ] || \
    [ -f /data/local/tmp/pixel_v165_ios26_icons_migrated ] || \
    [ -f /data/local/tmp/pixel_v161_ios_comms_role_migrated ] || \
    [ -d /data/user_de/0/com.realme.samurai.styler ] || \
    [ -d /data/data/com.realme.samurai.styler ] || \
    [ -f /data/user_de/0/com.google.android.apps.nexuslauncher/shared_prefs/samurai_ios26_prefs.xml ] || \
    [ -f /data/data/com.google.android.apps.nexuslauncher/shared_prefs/samurai_ios26_prefs.xml ]; \
}; then
    PREV_CH="ios_legacy"
else
    PREV_CH="${CUR_CH}"
fi

mkdir -p "${OTA_DIR}"
chmod 0777 "${OTA_DIR}" 2>/dev/null || true
rm -f "${OTA_DIR}/install.ready" "${OTA_DIR}/apply.status" \
      "${RAW_DIR}/install.ready" "${RAW_DIR}/apply.status" 2>/dev/null || true

# Ensure Samsung SOFEF03F-M HBM overdrive never stays locked across reboots (blocks MIPI DCS brightness slider)
echo 0 > /sys/kernel/oppo_display/hbm 2>/dev/null || true
if [ -f "${OTA_DIR}/hw/state.prop" ]; then
    sed -i 's/^hbm_mode=1$/hbm_mode=0/' "${OTA_DIR}/hw/state.prop" 2>/dev/null || true
fi
if [ -f "${OTA_DIR}/hw/telemetry.prop" ]; then
    sed -i 's/^hbm_mode=1$/hbm_mode=0/' "${OTA_DIR}/hw/telemetry.prop" 2>/dev/null || true
fi
PARTS_PREFS="/data/user_de/0/com.realme.samurai.parts/shared_prefs/samurai_parts_prefs.xml"
if [ -f "${PARTS_PREFS}" ]; then
    sed -i 's/<boolean name="hbm_mode" value="true" \/>/<boolean name="hbm_mode" value="false" \/>/' "${PARTS_PREFS}" 2>/dev/null || true
    sed -i '/name="temp_refresh_override"/d' "${PARTS_PREFS}" 2>/dev/null || true
fi

# Enforce TFLite OpenGL fallback UAF guard (0x4fb76ac -> mov x0, #0) on /data/app GoogleCameraEng before Zygote starts
UAF_FIX="/system/etc/samurai-mods/gcastartup_uaf_fix.bin"
if [ -f "${UAF_FIX}" ]; then
    for gcam_so in /data/app/*/*GoogleCameraEng*/lib/arm64/libgcastartup.so /data/app/*GoogleCameraEng*/lib/arm64/libgcastartup.so; do
        [ -f "${gcam_so}" ] || continue
        if ! dd if="${gcam_so}" bs=4 skip=20897195 count=1 2>/dev/null | cmp -s - "${UAF_FIX}"; then
            dd if="${UAF_FIX}" of="${gcam_so}" bs=4 seek=20897195 count=1 conv=notrunc 2>/dev/null || true
            chown 1000:1000 "${gcam_so}" 2>/dev/null || true
            chmod 0755 "${gcam_so}" 2>/dev/null || true
            chcon u:object_r:apk_data_file:s0 "${gcam_so}" 2>/dev/null || true
        fi
    done
fi

# Purge ONLY retired experimental / legacy third-party dialer packages (NEVER delete Google Dialer, Google Messages, or IosDialer!)
for retired_pkg in \
    com.mayoh.contact.icontact \
    com.realme.samurai.clock \
    com.realme.samurai.calculator \
    com.realme.samurai.notes \
    com.realme.samurai.weather; do
    rm -rf /data/app/*/*"${retired_pkg}"* /data/app/*"${retired_pkg}"* 2>/dev/null || true
done
rm -rf /data/data/com.mayoh.contact.icontact \
       /data/user/0/com.mayoh.contact.icontact \
       /data/user_de/0/com.mayoh.contact.icontact 2>/dev/null || true

# Cross-channel UI switch ONLY (Stable PixelOS <-> iOS Style UI Beta):
# Reset Pixel Launcher workspace DB so Stable boots with authentic Google Pixel Home + Dock (Phone, Messages,
# Chrome, Camera + App Drawer) and iOS Beta boots with clean SpringBoard pages + Liquid Glass Dock.
# Same-channel updates NEVER touch launcher*.db*!
if [ "${CUR_CH}" != "${PREV_CH}" ]; then
    for nx_dir in \
        /data/data/com.google.android.apps.nexuslauncher \
        /data/user/0/com.google.android.apps.nexuslauncher \
        /data/user_de/0/com.google.android.apps.nexuslauncher; do
        rm -f "${nx_dir}/databases/launcher"*.db* \
              "${nx_dir}/databases/grid_migrations.db"* \
              "${nx_dir}/shared_prefs/samurai_ios26_prefs.xml" \
              "${nx_dir}/shared_prefs/com.android.launcher3.prefs.xml" 2>/dev/null || true
    done
    rm -f /data/local/tmp/pixel_channel_roles_synced_stable \
          /data/local/tmp/pixel_channel_roles_synced_ios_beta \
          /data/local/tmp/pixel_v161_ios_comms_role_migrated \
          /data/local/tmp/pixel_v165_ios_settings_initialized 2>/dev/null || true
fi

if { [ -n "${CUR_VER}" ] && [ "${CUR_VER}" != "${PREV_VER}" ]; } || [ "${CUR_CH}" != "${PREV_CH}" ]; then
    rm -rf /data/resource-cache/* /data/system/package_cache/* 2>/dev/null || true
    rm -f /data/dalvik-cache/arm64/*NexusLauncher* \
          /data/dalvik-cache/arm64/*Samurai* \
          /data/dalvik-cache/arm64/*Ios* \
          /data/dalvik-cache/arm64/*ICall* \
          /data/dalvik-cache/arm64/*services.jar* 2>/dev/null || true
    rm -f /data/data/com.google.android.apps.nexuslauncher/databases/app_icons.db* \
          /data/user/0/com.google.android.apps.nexuslauncher/databases/app_icons.db* \
          /data/user_de/0/com.google.android.apps.nexuslauncher/databases/app_icons.db* 2>/dev/null || true
    rm -rf /data/data/com.google.android.apps.nexuslauncher/cache/* \
           /data/user/0/com.google.android.apps.nexuslauncher/cache/* \
           /data/user_de/0/com.google.android.apps.nexuslauncher/cache/* 2>/dev/null || true
    rm -f /data/local/tmp/pixel_ui_polish_initialized /data/local/tmp/samurai_adblock_dns_initialized \
          /data/local/tmp/pixel_v13_ios26_icons_migrated /data/local/tmp/pixel_v14_ios26_dock_migrated \
          /data/local/tmp/pixel_v15_ios26_concentric_migrated \
          /data/local/tmp/pixel_v200_ios26_full_os_icons_migrated /data/local/tmp/pixel_v200_ios26_full_os_initialized \
          /data/local/tmp/pixel_v161_ios_comms_role_migrated \
          /data/local/tmp/pixel_v165_ios26_icons_migrated 2>/dev/null || true
    rm -f "${OTA_DIR}/update.zip" "${OTA_DIR}/update.sha256" "${OTA_DIR}/update.zip.part" \
          "${RAW_DIR}/update.zip" "${RAW_DIR}/update.sha256" "${RAW_DIR}/update.zip.part" "${RAW_DIR}/.armed" \
          "/data/preloads/ota_raw/update.zip" 2>/dev/null || true
    [ -n "${CUR_VER}" ] && printf '%s\n' "${CUR_VER}" > "${STAMP_FILE}"
    printf '%s\n' "${CUR_CH}" > "${CH_STAMP_FILE}"
    chmod 0644 "${STAMP_FILE}" "${CH_STAMP_FILE}" 2>/dev/null || true
fi

TARGET_SCHEMA="${CUR_CH}-v2.1.4"
if [ "$(/system/bin/getprop persist.sys.samurai.icon_schema)" != "${TARGET_SCHEMA}" ]; then
    rm -rf /data/resource-cache/* /data/system/package_cache/* 2>/dev/null || true
    rm -f /data/data/com.google.android.apps.nexuslauncher/databases/app_icons.db* \
          /data/user/0/com.google.android.apps.nexuslauncher/databases/app_icons.db* \
          /data/user_de/0/com.google.android.apps.nexuslauncher/databases/app_icons.db* 2>/dev/null || true
    rm -rf /data/data/com.google.android.apps.nexuslauncher/cache/* \
           /data/user/0/com.google.android.apps.nexuslauncher/cache/* \
           /data/user_de/0/com.google.android.apps.nexuslauncher/cache/* 2>/dev/null || true
    /system/bin/setprop persist.sys.samurai.icon_schema "${TARGET_SCHEMA}" 2>/dev/null || true
fi

restorecon -RF "${OTA_DIR}" 2>/dev/null || true
[ -d "${RAW_DIR}" ] && restorecon -RF "${RAW_DIR}" 2>/dev/null || true
rm -rf "${OTA_DIR}/adblock" "${OTA_DIR}/hw/adblock.prop" 2>/dev/null || true
exit 0
