#!/system/bin/sh
# Dual-Channel PixelOS Android 17 (Stable Pure PixelOS + Opt-In iOS Style UI Beta):
# 90Hz AMOLED, Gesture Nav, Tap-to-Wake, GCam 9.6, Channel Roles & SamuraiParts init
CUR_CH="$(getprop ro.ota.channel 2>/dev/null)"
[ -n "${CUR_CH}" ] || CUR_CH="stable"

# Initialize iOS Style UI Beta Global setting if unset so UI toggle matches installed channel on fresh installs
CUR_BETA_TOGGLE="$(settings get global samurai_ios_style_ui_beta 2>/dev/null)"
case "${CUR_BETA_TOGGLE}" in
    0|1) ;;
    *)
        if [ "${CUR_CH}" = "ios_beta" ]; then
            settings put global samurai_ios_style_ui_beta 1 2>/dev/null || true
        else
            settings put global samurai_ios_style_ui_beta 0 2>/dev/null || true
        fi
        ;;
esac

PARTS_PREFS="/data/user_de/0/com.realme.samurai.parts/shared_prefs/samurai_parts_prefs.xml"
SAVED_REF_MODE="0"
if [ -f "${PARTS_PREFS}" ]; then
    if grep -q 'name="refresh_mode" value="2"' "${PARTS_PREFS}" 2>/dev/null; then
        SAVED_REF_MODE="2"
    elif grep -q 'name="refresh_mode" value="1"' "${PARTS_PREFS}" 2>/dev/null; then
        SAVED_REF_MODE="1"
    fi
fi

# Always enforce the user's saved refresh rate on boot so a killed temp video override can NEVER strand the panel at 60Hz
case "${SAVED_REF_MODE}" in
    2)
        settings put system peak_refresh_rate 60.0 2>/dev/null || true
        settings put system min_refresh_rate 60.0 2>/dev/null || true
        ;;
    1)
        settings put system peak_refresh_rate 90.0 2>/dev/null || true
        settings put system min_refresh_rate 60.0 2>/dev/null || true
        ;;
    *)
        settings put system peak_refresh_rate 90.0 2>/dev/null || true
        settings put system min_refresh_rate 90.0 2>/dev/null || true
        ;;
esac

if [ ! -f /data/local/tmp/pixel_90hz_initialized ]; then
    settings put secure double_tap_to_wake 1 2>/dev/null || true
    settings put secure doze_double_tap_gesture 1 2>/dev/null || true
    settings put secure doze_tap_screen_gesture 1 2>/dev/null || true
    touch /data/local/tmp/pixel_90hz_initialized 2>/dev/null || true
fi

# Ensure SurfaceFlinger & HWC composer reside in /dev/stune/rt for zero-missed-frame 90Hz composition
if [ -d /dev/stune/rt ]; then
    for rpid in $(pidof surfaceflinger android.hardware.graphics.composer@2.3-service vendor.qti.hardware.display.composer-service 2>/dev/null); do
        echo "${rpid}" > /dev/stune/rt/cgroup.procs 2>/dev/null || true
    done
fi

if [ ! -f /data/local/tmp/pixel_ui_polish_initialized ]; then
    wm density reset 2>/dev/null || true
    settings put global disable_window_blurs 1 2>/dev/null || true
    cmd overlay enable-exclusive com.android.internal.systemui.navbar.gestural 2>/dev/null || true
    cmd role add-role-holder --user 0 android.app.role.HOME com.google.android.apps.nexuslauncher 0 2>/dev/null || true
    cmd package set-home-activity --user 0 com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity 2>/dev/null || true
    touch /data/local/tmp/pixel_ui_polish_initialized 2>/dev/null || true
fi

if [ ! -f /data/local/tmp/pixel_brightness_calibrated_v1 ]; then
    echo 0 > /sys/kernel/oppo_display/hbm 2>/dev/null || true
    settings put secure reduce_bright_colors_activated 0 2>/dev/null || true
    CUR_BRT="$(settings get system screen_brightness 2>/dev/null)"
    case "${CUR_BRT}" in
        ""|null|[0-9]|1[0-9])
            settings put system screen_brightness 98 2>/dev/null || true
            settings put system screen_brightness_float 0.382 2>/dev/null || true
            ;;
    esac
    touch /data/local/tmp/pixel_brightness_calibrated_v1 2>/dev/null || true
fi

# 8GB LPDDR4X multitasking tuning (64 cached apps, disable phantom process killer)
device_config put activity_manager max_cached_processes 64 2>/dev/null || true
device_config set_sync_disabled_for_tests persistent 2>/dev/null || true
settings put global settings_enable_monitor_phantom_procs false 2>/dev/null || true

# Disable Android 17 AdServices/Topics/FLEDGE background telemetry
device_config put adservices global_kill_switch true 2>/dev/null || true
device_config put adservices adid_kill_switch true 2>/dev/null || true
device_config put adservices topics_kill_switch true 2>/dev/null || true
device_config put adservices fledge_custom_audience_service_kill_switch true 2>/dev/null || true
device_config put adservices measurement_kill_switch true 2>/dev/null || true

# v1.2 Chrome & YouTube Smoothness Migration (restore Private DNS & disable heavy window blurs by default)
if [ ! -f /data/local/tmp/pixel_v12_perf_migrated ]; then
    CUR_PDNS="$(settings get global private_dns_mode 2>/dev/null)"
    if [ "${CUR_PDNS}" = "off" ]; then
        settings put global private_dns_mode opportunistic 2>/dev/null || true
    fi
    settings put global disable_window_blurs 1 2>/dev/null || true
    rm -f /data/local/tmp/samurai_adblock_dns_initialized 2>/dev/null || true
    rm -rf /data/ota_package/adblock /data/ota_package/hw/adblock.prop 2>/dev/null || true
    touch /data/local/tmp/pixel_v12_perf_migrated 2>/dev/null || true
fi

# Retired experimental & third-party dialer cleanup (shared safety cleanup)
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

TARGET_SCHEMA="${CUR_CH}-v2.1.4"
if [ "$(getprop persist.sys.samurai.icon_schema 2>/dev/null)" != "${TARGET_SCHEMA}" ]; then
    rm -f /data/data/com.google.android.apps.nexuslauncher/databases/app_icons.db* \
          /data/user/0/com.google.android.apps.nexuslauncher/databases/app_icons.db* \
          /data/user_de/0/com.google.android.apps.nexuslauncher/databases/app_icons.db* 2>/dev/null || true
    rm -rf /data/data/com.google.android.apps.nexuslauncher/cache/* \
           /data/user/0/com.google.android.apps.nexuslauncher/cache/* \
           /data/user_de/0/com.google.android.apps.nexuslauncher/cache/* 2>/dev/null || true
    setprop persist.sys.samurai.icon_schema "${TARGET_SCHEMA}" 2>/dev/null || true
    am force-stop com.google.android.apps.nexuslauncher 2>/dev/null || true
fi

if [ "${CUR_CH}" = "ios_beta" ]; then
    # =========================================================================
    # CHANNEL B: iOS Style UI Beta (Liquid Glass Dock, iOS Shade/CC/Lock, IosDialer, IosMessages, IosSettings)
    # =========================================================================
    if [ ! -f /data/local/tmp/pixel_channel_roles_synced_ios_beta ]; then
        rm -f /data/local/tmp/pixel_channel_roles_synced_stable 2>/dev/null || true
        touch /data/local/tmp/pixel_channel_roles_synced_ios_beta 2>/dev/null || true
    fi

    # Always hide stock Android's 72dpx4dp SystemUI gesture handle on ios_beta;
    # SamuraiIos26HomeBar renders the 138dpx5dp iOS Home Indicator in apps & Recents.
    cmd overlay enable --user 0 com.realme.samurai.overlay.hidepill 2>/dev/null || true
    settings put global samurai_launcher_bar_state 0 2>/dev/null || true

    # One-shot v2.1.5 migration: enable iOS Home Indicator by default without overwriting future user toggles.
    # Persists durable marker in Settings.Global (samurai_v215_homebar_migrated=1) so /data/local/tmp cleanup
    # never clobbers a user's later preference toggle; SamuraiParts syncs /data/ota_package/hw/glass.prop.
    if [ "$(settings get global samurai_v215_homebar_migrated 2>/dev/null)" != "1" ] && \
       [ ! -f /data/local/tmp/pixel_v215_ios_homebar_migrated ]; then
        settings put global samurai_gesture_pill_enabled 1 2>/dev/null || true
        settings put global samurai_ios26_gesture_pill 1 2>/dev/null || true
        settings put secure samurai_gesture_pill_enabled 1 2>/dev/null || true
        settings put secure samurai_ios26_gesture_pill 1 2>/dev/null || true
        if [ -f /data/ota_package/hw/glass.prop ]; then
            if grep -q '^gesture_pill=' /data/ota_package/hw/glass.prop 2>/dev/null; then
                sed -i 's/^gesture_pill=.*/gesture_pill=1/' /data/ota_package/hw/glass.prop 2>/dev/null || true
            else
                echo 'gesture_pill=1' >> /data/ota_package/hw/glass.prop 2>/dev/null || true
            fi
        fi
        settings put global samurai_v215_homebar_migrated 1 2>/dev/null || true
        touch /data/local/tmp/pixel_v215_ios_homebar_migrated 2>/dev/null || true
    elif [ ! -f /data/local/tmp/pixel_v215_ios_homebar_migrated ]; then
        touch /data/local/tmp/pixel_v215_ios_homebar_migrated 2>/dev/null || true
    fi

    # v1.6.4 Liquid Glass Calibrated Defaults: 20% Refraction, 20% Glass Blur / Frost, 70% Wallpaper Brightness (Depth)
    if [ ! -f /data/local/tmp/pixel_v164_glass_defaults_initialized ]; then
        settings put global samurai_ios26_refraction_pct 20 2>/dev/null || true
        settings put secure samurai_ios26_refraction_pct 20 2>/dev/null || true
        settings put global samurai_ios26_frost_pct 20 2>/dev/null || true
        settings put secure samurai_ios26_frost_pct 20 2>/dev/null || true
        settings put global samurai_ios26_wp_brightness_pct 70 2>/dev/null || true
        settings put secure samurai_ios26_wp_brightness_pct 70 2>/dev/null || true
        for styler_pref in \
            /data/user_de/0/com.realme.samurai.styler/shared_prefs/samurai_ios_styler_prefs.xml \
            /data/data/com.realme.samurai.styler/shared_prefs/samurai_ios_styler_prefs.xml; do
            if [ -f "${styler_pref}" ]; then
                sed -i -e '/samurai_ios26_refraction_pct/d' \
                       -e '/samurai_ios26_frost_pct/d' \
                       -e '/samurai_ios26_wp_brightness_pct/d' "${styler_pref}" 2>/dev/null || true
            fi
        done
        if [ -f /data/ota_package/hw/glass.prop ]; then
            sed -i -e 's/^refraction_pct=.*/refraction_pct=20/' \
                   -e 's/^frost_pct=.*/frost_pct=20/' \
                   -e 's/^wp_brightness_pct=.*/wp_brightness_pct=70/' /data/ota_package/hw/glass.prop 2>/dev/null || true
        fi
        am force-stop com.realme.samurai.styler 2>/dev/null || true
        touch /data/local/tmp/pixel_v164_glass_defaults_initialized 2>/dev/null || true
    fi

    if [ ! -f /data/local/tmp/pixel_v165_ios_settings_initialized ]; then
        for stale_key in \
            samurai_ios26_full_os_mode \
            samurai_ios26_cc_enabled \
            samurai_ios26_lockscreen_enabled \
            samurai_ios26_dynamic_island_enabled \
            samurai_ios26_stacked_recents_enabled \
            samurai_ios26_ls_clock_style; do
            settings delete global "${stale_key}" >/dev/null 2>&1 || true
            settings delete secure "${stale_key}" >/dev/null 2>&1 || true
        done
        if pm path com.realme.samurai.iossettings >/dev/null 2>&1; then
            for sperm in \
                android.permission.ACCESS_FINE_LOCATION \
                android.permission.ACCESS_COARSE_LOCATION \
                android.permission.BLUETOOTH_CONNECT \
                android.permission.BLUETOOTH_SCAN \
                android.permission.READ_PHONE_STATE \
                android.permission.GET_ACCOUNTS \
                android.permission.POST_NOTIFICATIONS; do
                pm grant com.realme.samurai.iossettings "${sperm}" 2>/dev/null || true
            done
            appops set com.realme.samurai.iossettings WRITE_SETTINGS allow 2>/dev/null || true
            appops set com.realme.samurai.iossettings RUN_IN_BACKGROUND allow 2>/dev/null || true
            appops set com.realme.samurai.iossettings RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
            touch /data/local/tmp/pixel_v165_ios_settings_initialized 2>/dev/null || true
        fi
    fi

    if [ ! -f /data/local/tmp/pixel_v175_ios_comms_role_migrated ]; then
        DIALER_OK=0
        MSG_OK=0
        if pm path com.realme.samurai.dialer >/dev/null 2>&1; then
            for dperm in \
                android.permission.CALL_PHONE \
                android.permission.ANSWER_PHONE_CALLS \
                android.permission.READ_CALL_LOG \
                android.permission.WRITE_CALL_LOG \
                android.permission.READ_CONTACTS \
                android.permission.WRITE_CONTACTS \
                android.permission.READ_PHONE_STATE \
                android.permission.READ_PHONE_NUMBERS \
                android.permission.BLUETOOTH_CONNECT \
                android.permission.POST_NOTIFICATIONS; do
                pm grant com.realme.samurai.dialer "${dperm}" 2>/dev/null || true
            done
            appops set com.realme.samurai.dialer SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
            appops set com.realme.samurai.dialer USE_FULL_SCREEN_INTENT allow 2>/dev/null || true
            appops set com.realme.samurai.dialer WRITE_SETTINGS allow 2>/dev/null || true
            appops set com.realme.samurai.dialer RUN_IN_BACKGROUND allow 2>/dev/null || true
            appops set com.realme.samurai.dialer RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
            cmd role add-role-holder --user 0 android.app.role.DIALER com.realme.samurai.dialer 0 2>/dev/null && DIALER_OK=1 || true
            cmd role add-role-holder --user 0 android.app.role.CALL_SCREENING com.realme.samurai.dialer 0 2>/dev/null || true
            pm disable-user --user 0 com.google.android.dialer >/dev/null 2>&1 || true
        fi
        if pm path com.realme.samurai.messages >/dev/null 2>&1; then
            for mperm in \
                android.permission.SEND_SMS \
                android.permission.RECEIVE_SMS \
                android.permission.READ_SMS \
                android.permission.RECEIVE_MMS \
                android.permission.RECEIVE_WAP_PUSH \
                android.permission.READ_CONTACTS \
                android.permission.CALL_PHONE \
                android.permission.READ_PHONE_STATE \
                android.permission.READ_PHONE_NUMBERS \
                android.permission.ACCESS_FINE_LOCATION \
                android.permission.ACCESS_COARSE_LOCATION \
                android.permission.POST_NOTIFICATIONS; do
                pm grant com.realme.samurai.messages "${mperm}" 2>/dev/null || true
            done
            appops set com.realme.samurai.messages SCHEDULE_EXACT_ALARM allow 2>/dev/null || true
            appops set com.realme.samurai.messages RUN_IN_BACKGROUND allow 2>/dev/null || true
            appops set com.realme.samurai.messages RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
            cmd role add-role-holder --user 0 android.app.role.SMS com.realme.samurai.messages 0 2>/dev/null && MSG_OK=1 || true
            pm disable-user --user 0 com.google.android.apps.messaging >/dev/null 2>&1 || true
        fi
        if [ "${DIALER_OK}" = "1" ] && [ "${MSG_OK}" = "1" ]; then
            touch /data/local/tmp/pixel_v175_ios_comms_role_migrated 2>/dev/null || true
        fi
    fi

    cmd deviceidle whitelist +com.realme.samurai.dialer >/dev/null 2>&1 || true
    cmd deviceidle whitelist +com.realme.samurai.messages >/dev/null 2>&1 || true
    cmd deviceidle whitelist +com.realme.samurai.iossettings >/dev/null 2>&1 || true
    cmd deviceidle whitelist +com.realme.samurai.controlcenter >/dev/null 2>&1 || true
    if pm path com.realme.samurai.controlcenter >/dev/null 2>&1; then
        for ccperm in \
            android.permission.BLUETOOTH_CONNECT \
            android.permission.READ_PHONE_STATE \
            android.permission.POST_NOTIFICATIONS; do
            pm grant com.realme.samurai.controlcenter "${ccperm}" 2>/dev/null || true
        done
        appops set com.realme.samurai.controlcenter FINE_LOCATION ignore 2>/dev/null || true
        appops set com.realme.samurai.controlcenter COARSE_LOCATION ignore 2>/dev/null || true
        appops set com.realme.samurai.controlcenter SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
        appops set com.realme.samurai.controlcenter WRITE_SETTINGS allow 2>/dev/null || true
        appops set com.realme.samurai.controlcenter RUN_IN_BACKGROUND allow 2>/dev/null || true
        appops set com.realme.samurai.controlcenter RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
        cmd notification allow_listener com.realme.samurai.controlcenter/com.realme.samurai.controlcenter.service.IosNotificationListenerService >/dev/null 2>&1 || true
        A11Y_COMP="com.realme.samurai.controlcenter/com.realme.samurai.controlcenter.service.IosSystemOverlayAccessibilityService"
        CUR_A11Y="$(settings get secure enabled_accessibility_services 2>/dev/null)"
        case "${CUR_A11Y}" in
            ""|null)
                settings put secure enabled_accessibility_services "${A11Y_COMP}" 2>/dev/null || true
                ;;
            *"${A11Y_COMP}"*)
                ;;
            *)
                settings put secure enabled_accessibility_services "${CUR_A11Y}:${A11Y_COMP}" 2>/dev/null || true
                ;;
        esac
        settings put secure accessibility_enabled 1 2>/dev/null || true
        am startservice com.realme.samurai.controlcenter/.service.ControlCenterOverlayService >/dev/null 2>&1 || true

        CUR_IOS_LS="$(settings get global samurai_ios26_lockscreen 2>/dev/null)"
        if [ "${CUR_IOS_LS}" != "0" ] && dumpsys lock_settings 2>/dev/null | grep -q "CredentialType: NONE"; then
            if [ "$(locksettings get-disabled 2>/dev/null)" = "false" ]; then
                locksettings set-disabled true 2>/dev/null || true
                wm dismiss-keyguard 2>/dev/null || true
                touch /data/local/tmp/samurai_ios_ls_suppressed_swipe 2>/dev/null || true
            fi
        elif [ "${CUR_IOS_LS}" = "0" ] && [ -f /data/local/tmp/samurai_ios_ls_suppressed_swipe ]; then
            locksettings set-disabled false 2>/dev/null || true
            rm -f /data/local/tmp/samurai_ios_ls_suppressed_swipe 2>/dev/null || true
        fi
    fi
    appops set com.realme.samurai.iossettings WRITE_SETTINGS allow 2>/dev/null || true
    appops set com.realme.samurai.iossettings RUN_IN_BACKGROUND allow 2>/dev/null || true
    appops set com.realme.samurai.iossettings RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
else
    # =========================================================================
    # CHANNEL A: Clean Pure PixelOS Stable (Stock Pixel Launcher, Stock SystemUI, Google Phone & Messages)
    # =========================================================================
    if [ -f /data/local/tmp/samurai_ios_ls_suppressed_swipe ]; then
        locksettings set-disabled false 2>/dev/null || true
        rm -f /data/local/tmp/samurai_ios_ls_suppressed_swipe 2>/dev/null || true
    fi
    if [ ! -f /data/local/tmp/pixel_channel_roles_synced_stable ]; then
        settings put global samurai_gesture_pill_enabled 1 2>/dev/null || true
        settings delete secure samurai_ios26_default_wp_v153 2>/dev/null || true
        cmd overlay disable --user 0 com.realme.samurai.overlay.hidepill 2>/dev/null || true
        cmd package install-existing --user 0 com.google.android.dialer >/dev/null 2>&1 || true
        cmd package install-existing --user 0 com.google.android.apps.messaging >/dev/null 2>&1 || true
        pm enable --user 0 com.google.android.dialer >/dev/null 2>&1 || true
        pm enable --user 0 com.google.android.apps.messaging >/dev/null 2>&1 || true
        if pm path com.google.android.dialer >/dev/null 2>&1; then
            cmd role add-role-holder --user 0 android.app.role.DIALER com.google.android.dialer 0 2>/dev/null || true
            cmd role add-role-holder --user 0 android.app.role.CALL_SCREENING com.google.android.dialer 0 2>/dev/null || true
        fi
        if pm path com.google.android.apps.messaging >/dev/null 2>&1; then
            cmd role add-role-holder --user 0 android.app.role.SMS com.google.android.apps.messaging 0 2>/dev/null || true
        fi
        rm -f /data/local/tmp/pixel_channel_roles_synced_ios_beta 2>/dev/null || true
        touch /data/local/tmp/pixel_channel_roles_synced_stable 2>/dev/null || true
    fi

    CUR_PILL="$(settings get global samurai_gesture_pill_enabled 2>/dev/null)"
    case "${CUR_PILL}" in
        0)
            cmd overlay enable --user 0 com.realme.samurai.overlay.hidepill 2>/dev/null || true
            ;;
        *)
            settings put global samurai_gesture_pill_enabled 1 2>/dev/null || true
            cmd overlay disable --user 0 com.realme.samurai.overlay.hidepill 2>/dev/null || true
            ;;
    esac
fi

# Pixel Google Camera 9.6 (BSG V53) zero-popup permissions, Double-Tap Power gesture & RMX1931 60fps EIS XML profile
if pm path com.google.android.GoogleCameraEng >/dev/null 2>&1; then
    if [ ! -f /data/local/tmp/pixel_gcam_rmx1931_v2 ]; then
        if [ -f /data/local/tmp/pixel_gcam_rmx1931_v1 ]; then
            pm clear com.google.android.GoogleCameraEng >/dev/null 2>&1 || true
            rm -f /data/local/tmp/pixel_gcam_rmx1931_v1 2>/dev/null || true
        fi
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
            pm grant com.google.android.GoogleCameraEng "${perm}" 2>/dev/null || true
        done
        appops set com.google.android.GoogleCameraEng MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
        appops set com.google.android.GoogleCameraEng WRITE_SETTINGS allow 2>/dev/null || true
        settings put secure camera_double_tap_power_gesture_disabled 0 2>/dev/null || true
        cmd role add-role-holder --user 0 android.app.role.CAMERA com.google.android.GoogleCameraEng 0 2>/dev/null || true
        touch /data/local/tmp/pixel_gcam_rmx1931_v2 2>/dev/null || true
    fi
    if [ -d /sdcard/Download ] && { [ ! -f /sdcard/Download/MGC_CONFIG/9.6.080/RMX1931-PixelOS-Pro.xml ] || [ ! -f /data/local/tmp/pixel_gcam_rmx1931_xml_v3 ]; }; then
        mkdir -p /sdcard/Download/MGC_CONFIG/9.6.080 /sdcard/MGC_CONFIG/9.6.080 2>/dev/null || true
        cat > /sdcard/Download/MGC_CONFIG/9.6.080/RMX1931-PixelOS-Pro.xml <<'GCAMEOF'
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
GCAMEOF
        cp -f /sdcard/Download/MGC_CONFIG/9.6.080/RMX1931-PixelOS-Pro.xml /sdcard/MGC_CONFIG/9.6.080/RMX1931-PixelOS-Pro.xml 2>/dev/null || true
        touch /data/local/tmp/pixel_gcam_rmx1931_xml_v3 2>/dev/null || true
    fi
fi

cmd deviceidle whitelist +com.realme.ota >/dev/null 2>&1 || true
cmd deviceidle whitelist +com.realme.samurai.parts >/dev/null 2>&1 || true
appops set com.realme.ota RUN_IN_BACKGROUND allow 2>/dev/null || true
appops set com.realme.ota RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
appops set com.realme.samurai.parts RUN_IN_BACKGROUND allow 2>/dev/null || true
appops set com.realme.samurai.parts RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
appops set com.realme.samurai.parts SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
appops set com.realme.samurai.parts GET_USAGE_STATS allow 2>/dev/null || true
appops set com.realme.samurai.parts WRITE_SETTINGS allow 2>/dev/null || true
am startservice com.realme.samurai.parts/.SamuraiMonitorService >/dev/null 2>&1 || true
