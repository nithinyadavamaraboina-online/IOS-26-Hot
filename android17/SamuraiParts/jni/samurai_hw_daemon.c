/*
 * samurai_hw_daemon.c
 *
 * Native ARM64 Hardware, Gesture, Tap-to-Wake & Tap-to-Close Daemon
 * for Realme X2 Pro (RMX1931 / samurai) on Android 17 Pixel OS.
 *
 * Responsibilities:
 *  1. Tap-to-Wake (DT2W) & Off-Screen Gestures via Synaptics S3706 "touchpanel_kpd"
 *     (/proc/touchpanel/double_tap_enable & double_tap_enable_indep, preserving FOD bits 14-15).
 *  2. Zero-latency (<0.5ms) Tap-to-Close (Double-Tap to Sleep) via passive observation of
 *     "touchpanel" + virtual /dev/uinput KEY_SLEEP (142), preserving Goodix Optical UDFPS unlock.
 *  3. Validated sysfs hardware bridge for SamuraiParts.apk (/data/ota_package/hw/queue/<id>.req):
 *     - Samsung SOFEF03F-M 90Hz AMOLED: DC Dimming, 1000-nit HBM, DCI-P3/sRGB SEED, CABC, UDFPS reset
 *     - Synaptics S3706 Touch: Game Touch Mode, Edge Grip Rejection, Landscape Rotation Hint
 *     - Snapdragon 855+ (SM8150-AC): Balanced / 2.96GHz+675MHz Gaming / Efficiency profiles
 *     - 50W SuperVOOC Battery: Smart Charge Limit (80%/85%/90%/100% w/ 3% hysteresis), Cool-Down, OTG
 *     - Awinic AW8697 LRA Haptics: Hex gain control (/sys/class/leds/vibrator/gain)
 *  4. Atomic telemetry snapshot (/data/ota_package/hw/telemetry.prop) for Settings UI & HUD.
 */

#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define HW_DIR              "/data/ota_package/hw"
#define QUEUE_DIR           "/data/ota_package/hw/queue"
#define STATE_PROP_PATH     "/data/ota_package/hw/state.prop"
#define TELEMETRY_PATH      "/data/ota_package/hw/telemetry.prop"
#define TELEMETRY_TMP_PATH  "/data/ota_package/hw/.telemetry.tmp"
#define PANEL_BL_PATH       "/sys/class/backlight/panel0-backlight/brightness"
#define OPPO_HBM_PATH       "/sys/kernel/oppo_display/hbm"
#define OPPO_FPPRESS_PATH   "/sys/kernel/oppo_display/notify_fppress"
#define OPPO_DIMLAYER_PATH  "/sys/kernel/oppo_display/dimlayer_hbm"
#define OPPO_FP_ENABLE_PATH "/proc/touchpanel/fp_enable"

/* Oppo/Realme Synaptics S3706 touchpanel_kpd gesture keycodes */
#define OPPO_KEY_GESTURE_START       246
#define OPPO_KEY_DOUBLE_TAP          247  /* bit 1 */
#define OPPO_KEY_UP_VEE              248  /* bit 2 */
#define OPPO_KEY_DOWN_VEE            249  /* bit 3: Draw V -> Torch */
#define OPPO_KEY_LEFT_VEE            250  /* bit 4: Draw > -> Next track */
#define OPPO_KEY_RIGHT_VEE           251  /* bit 5: Draw < -> Prev track */
#define OPPO_KEY_CIRCLE              252  /* bit 6: Draw O -> Camera */
#define OPPO_KEY_DOUBLE_SWIPE        253  /* bit 7: Draw || -> Play/Pause */
#define OPPO_KEY_M                   258  /* bit 12 */
#define OPPO_KEY_W                   259  /* bit 13 */
#define OPPO_FOD_PRESERVE_MASK       0xC000 /* bits 14-15: FingerprintDown/Up */

typedef struct {
    int dt2w_enabled;          /* Default 1: Tap-to-Wake */
    int dt2s_statusbar;        /* Default 1: Double-tap Status Bar & Lockscreen to Close */
    int dt2s_home;             /* Default 1: Double-tap Pixel Launcher Home to Close */
    int gesture_torch_v;       /* Default 1: Draw V when screen off -> Torch */
    int gesture_camera_o;      /* Default 0: Draw O when screen off -> Camera */
    int gesture_music;         /* Default 0: Draw ||, <, > when screen off -> Media */
    int dc_dimming;            /* Default 0: /sys/kernel/oppo_display/dimlayer_bl_en */
    int hbm_mode;              /* Default 0: /sys/kernel/oppo_display/hbm */
    int seed_mode;             /* Default 2: DCI-P3 / Vivid (0=Off, 1=sRGB, 2=DCI-P3) */
    int cabc_mode;             /* Default 1: Low CABC */
    int tp_game_mode;          /* Default 0: /proc/touchpanel/game_switch_enable */
    int tp_edge_limit;         /* Default 1: /proc/touchpanel/oplus_tp_limit_enable */
    int tp_direction;          /* Default 0: 0=Portrait, 1=Landscape90, 2=Landscape270 */
    int perf_profile;          /* Default 0: 0=Balanced, 1=Gaming 855+, 2=Efficiency */
    int chg_limit_pct;         /* Default 100: 80, 85, 90, or 100 */
    int cool_down_level;       /* Default 0: SuperVOOC thermal cool_down */
    int otg_enabled;           /* Default 0: /sys/class/power_supply/usb/otg_switch */
    int vib_gain;              /* Default 128 (0x80): AW8697 LRA gain 32..128 */
    /* Transient runtime hints from SamuraiMonitorService (not persisted) */
    int lockscreen_active;
    int home_idle_active;
    int screen_interactive;
    int torch_on;
    int chg_paused_by_limit;
    int64_t telemetry_until_ms;
} samurai_state_t;

static samurai_state_t g_state = {
    .dt2w_enabled = 1,
    .dt2s_statusbar = 1,
    .dt2s_home = 1,
    .gesture_torch_v = 1,
    .gesture_camera_o = 0,
    .gesture_music = 0,
    .dc_dimming = 0,
    .hbm_mode = 0,
    .seed_mode = 2,
    .cabc_mode = 1,
    .tp_game_mode = 0,
    .tp_edge_limit = 1,
    .tp_direction = 0,
    .perf_profile = 0,
    .chg_limit_pct = 100,
    .cool_down_level = 0,
    .otg_enabled = 0,
    .vib_gain = 128,
    .lockscreen_active = 0,
    .home_idle_active = 0,
    .screen_interactive = 1,
    .torch_on = 0,
    .chg_paused_by_limit = 0,
    .telemetry_until_ms = 0,
};

static int g_uinput_fd = -1;
static int64_t g_last_sleep_ms = 0;
static int64_t g_last_wake_ms = 0;
static int g_fod_hbm_active = 0;
static int g_fod_session_active = 0;
static int64_t g_otg_idle_since_ms = 0;
static long long g_hbm_anchor_bl = -1;
static int64_t g_hbm_armed_ms = 0;

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000LL + (int64_t)(ts.tv_nsec / 1000000LL);
}

static bool write_str_file(const char *path, const char *val) {
    int fd = open(path, O_WRONLY | O_TRUNC | O_CLOEXEC);
    if (fd < 0) {
        return false;
    }
    size_t len = strlen(val);
    ssize_t n = write(fd, val, len);
    close(fd);
    return n == (ssize_t)len;
}

static bool write_int_file(const char *path, int val) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%d", val);
    return write_str_file(path, buf);
}

static bool read_str_file(const char *path, char *out, size_t out_sz) {
    if (out_sz == 0) return false;
    out[0] = '\0';
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    ssize_t n = read(fd, out, out_sz - 1);
    close(fd);
    if (n <= 0) return false;
    out[n] = '\0';
    while (n > 0 && (out[n - 1] == '\n' || out[n - 1] == '\r' || out[n - 1] == ' ')) {
        out[--n] = '\0';
    }
    return true;
}

static long long read_ll_file(const char *path, long long fallback) {
    char buf[64];
    if (!read_str_file(path, buf, sizeof(buf))) return fallback;
    char *end = NULL;
    long long v = strtoll(buf, &end, 10);
    return (end != buf) ? v : fallback;
}

static void set_system_prop_async(const char *key, const char *val) {
    pid_t pid = fork();
    if (pid == 0) {
        int devnull = open("/dev/null", O_RDWR);
        if (devnull >= 0) {
            dup2(devnull, STDOUT_FILENO);
            dup2(devnull, STDERR_FILENO);
            if (devnull > 2) close(devnull);
        }
        execl("/system/bin/setprop", "setprop", key, val, (char *)NULL);
        _exit(127);
    } else if (pid > 0) {
        int status = 0;
        waitpid(pid, &status, 0);
    }
}

/* ---------------- Virtual /dev/uinput Setup & Key Emission ---------------- */

static int setup_uinput_device(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        fd = open("/dev/input/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    }
    if (fd < 0) return -1;

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    const int keys[] = {
        KEY_SLEEP,        /* 142: Tap-to-Close (preserves UDFPS unlock!) */
        KEY_WAKEUP,       /* 143: Tap-to-Wake */
        KEY_NEXTSONG,     /* 163: Off-screen > */
        KEY_PLAYPAUSE,    /* 164: Off-screen || */
        KEY_PREVIOUSSONG, /* 165: Off-screen < */
        KEY_CAMERA,       /* 212: Off-screen O */
    };
    for (size_t i = 0; i < sizeof(keys) / sizeof(keys[0]); i++) {
        ioctl(fd, UI_SET_KEYBIT, keys[i]);
    }

    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    usetup.id.bustype = BUS_VIRTUAL;
    usetup.id.vendor = 0x22d9;
    usetup.id.product = 0x1931;
    usetup.id.version = 1;
    snprintf(usetup.name, UINPUT_MAX_NAME_SIZE, "samurai-virtual-keys");

    if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0) {
        struct uinput_user_dev udev;
        memset(&udev, 0, sizeof(udev));
        snprintf(udev.name, UINPUT_MAX_NAME_SIZE, "samurai-virtual-keys");
        udev.id.bustype = BUS_VIRTUAL;
        udev.id.vendor = 0x22d9;
        udev.id.product = 0x1931;
        udev.id.version = 1;
        if (write(fd, &udev, sizeof(udev)) != (ssize_t)sizeof(udev)) {
            close(fd);
            return -1;
        }
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static void emit_uinput_event(int fd, uint16_t type, uint16_t code, int32_t val) {
    if (fd < 0) return;
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = val;
    ssize_t unused = write(fd, &ev, sizeof(ev));
    (void)unused;
}

static void emit_key(int keycode) {
    if (g_uinput_fd < 0) return;
    emit_uinput_event(g_uinput_fd, EV_KEY, (uint16_t)keycode, 1);
    emit_uinput_event(g_uinput_fd, EV_SYN, SYN_REPORT, 0);
    usleep(12000);
    emit_uinput_event(g_uinput_fd, EV_KEY, (uint16_t)keycode, 0);
    emit_uinput_event(g_uinput_fd, EV_SYN, SYN_REPORT, 0);
}

static void trigger_sleep_now(void) {
    int64_t t = now_ms();
    if (t - g_last_sleep_ms < 400) return;
    g_last_sleep_ms = t;
    g_state.screen_interactive = 0;
    emit_key(KEY_SLEEP);
}

static void trigger_wake_now(void) {
    int64_t t = now_ms();
    /* Guard against accidental bounce immediately after Tap-to-Close */
    if (t - g_last_sleep_ms < 450) return;
    if (t - g_last_wake_ms < 350) return;
    g_last_wake_ms = t;
    g_state.screen_interactive = 1;
    emit_key(KEY_WAKEUP);
}

static void pulse_haptic_ms(int duration_ms) {
    write_int_file("/sys/class/leds/vibrator/duration", duration_ms);
    write_int_file("/sys/class/leds/vibrator/activate", 1);
}

/*
 * Scrubs any stale "bootonce-bootloader" / "boot-fastboot" / "boot-bootloader" BCB command
 * from /dev/block/by-name/misc AFTER Android 17 finishes booting cleanly, preventing normal
 * shutdown/restart cycles from being trapped back into Fastboot, while strictly preserving
 * "boot-recovery" used by unattended TWRP OTA updates.
 */
static void scrub_stale_fastboot_bcb(void) {
    const char *candidates[] = {
        "/dev/block/bootdevice/by-name/misc",
        "/dev/block/by-name/misc",
    };
    for (size_t i = 0; i < sizeof(candidates) / sizeof(candidates[0]); i++) {
        int fd = open(candidates[i], O_RDWR | O_CLOEXEC);
        if (fd < 0) continue;
        char bcb[64];
        memset(bcb, 0, sizeof(bcb));
        if (pread(fd, bcb, sizeof(bcb) - 1, 0) > 0) {
            if (strncmp(bcb, "bootonce-bootloader", 19) == 0 ||
                strncmp(bcb, "boot-bootloader", 15) == 0 ||
                strncmp(bcb, "boot-fastboot", 13) == 0) {
                char zeros[64];
                memset(zeros, 0, sizeof(zeros));
                if (pwrite(fd, zeros, sizeof(zeros), 0) == (ssize_t)sizeof(zeros)) {
                    fsync(fd);
                }
            }
        }
        close(fd);
        break;
    }
}

/*
 * Early-init and post-boot Bionic property synchronization (/dev/__properties__/).
 * Ensures production user-build and verified-boot read-only properties are consistent
 * before Zygote starts, and updates ro.adb.secure once adbd has initialized.
 */
static int g_adb_prop_synced = 0;

static int patch_prop_file_value(const char *prop_file, const char *prop_name, const char *new_val) {
    int fd = open(prop_file, O_RDWR | O_CLOEXEC);
    if (fd < 0) return 0;
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < 128) {
        close(fd);
        return 0;
    }
    size_t sz = (size_t)st.st_size;
    uint8_t *map = (uint8_t *)mmap(NULL, sz, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (map == MAP_FAILED) {
        close(fd);
        return 0;
    }
    int matched = 0;
    bool modified = false;
    size_t name_len = strlen(prop_name) + 1;
    size_t val_len = strlen(new_val);
    if (val_len < 92) {
        for (size_t off = 96; off + name_len <= sz; off += 4) {
            if (memcmp(map + off, prop_name, name_len) == 0) {
                uint32_t *serial_ptr = (uint32_t *)(void *)(map + off - 96);
                char *val_ptr = (char *)(void *)(map + off - 92);
                uint32_t old_serial = __atomic_load_n(serial_ptr, __ATOMIC_RELAXED);
                uint32_t old_len = old_serial >> 24;
                if (old_len < 92 && (old_serial & 1U) == 0 && strnlen(val_ptr, 92) == (size_t)old_len) {
                    matched++;
                    if ((size_t)old_len != val_len || memcmp(val_ptr, new_val, val_len + 1) != 0) {
                        memset(val_ptr, 0, 92);
                        memcpy(val_ptr, new_val, val_len);
                        uint32_t new_serial = ((uint32_t)val_len << 24) | ((old_serial + 2U) & 0x00fffffeU);
                        __atomic_store_n(serial_ptr, new_serial, __ATOMIC_RELEASE);
                        modified = true;
                    }
                }
            }
        }
    }
    if (modified) {
        msync(map, sz, MS_SYNC);
    }
    munmap(map, sz);
    close(fd);
    return matched;
}

static void ensure_prop_value(const char *preferred_file, const char *prop_name, const char *new_val) {
    if (preferred_file && patch_prop_file_value(preferred_file, prop_name, new_val) > 0) {
        return;
    }
    DIR *dir = opendir("/dev/__properties__");
    if (dir) {
        struct dirent *de;
        while ((de = readdir(dir)) != NULL) {
            if (de->d_name[0] == '.') continue;
            if (strcmp(de->d_name, "property_info") == 0 || strcmp(de->d_name, "properties_serial") == 0) {
                continue;
            }
            char full[320];
            snprintf(full, sizeof(full), "/dev/__properties__/%s", de->d_name);
            struct stat st;
            if (stat(full, &st) == 0 && S_ISREG(st.st_mode)) {
                if (patch_prop_file_value(full, prop_name, new_val) > 0) {
                    closedir(dir);
                    return;
                }
            }
        }
        closedir(dir);
    }
    set_system_prop_async(prop_name, new_val);
}

static bool is_adbd_process_active(void) {
    DIR *dir = opendir("/proc");
    if (!dir) return false;
    struct dirent *de;
    bool found = false;
    while ((de = readdir(dir)) != NULL) {
        if (!isdigit((unsigned char)de->d_name[0])) continue;
        char comm_path[320];
        char comm[32];
        snprintf(comm_path, sizeof(comm_path), "/proc/%s/comm", de->d_name);
        if (read_str_file(comm_path, comm, sizeof(comm)) && strcmp(comm, "adbd") == 0) {
            found = true;
            break;
        }
    }
    closedir(dir);
    return found;
}

static void apply_early_boot_props(void) {
    ensure_prop_value("/dev/__properties__/u:object_r:userdebug_or_eng_prop:s0", "ro.debuggable", "0");
    ensure_prop_value("/dev/__properties__/u:object_r:userdebug_or_eng_prop:s0", "ro.secure", "1");
    ensure_prop_value("/dev/__properties__/u:object_r:bootloader_prop:s0", "ro.boot.verifiedbootstate", "green");
    ensure_prop_value("/dev/__properties__/u:object_r:bootloader_prop:s0", "ro.boot.veritymode", "enforcing");
    ensure_prop_value("/dev/__properties__/u:object_r:bootloader_prop:s0", "ro.boot.flash.locked", "1");
    ensure_prop_value("/dev/__properties__/u:object_r:bootloader_prop:s0", "ro.boot.vbmeta.device_state", "locked");
    ensure_prop_value("/dev/__properties__/u:object_r:vendor_default_prop:s0", "vendor.flash.locked", "1");
    ensure_prop_value("/dev/__properties__/u:object_r:build_prop:s0", "ro.build.type", "user");
    ensure_prop_value("/dev/__properties__/u:object_r:build_prop:s0", "ro.build.tags", "release-keys");
}

static void maybe_sync_adb_prop(bool force_now) {
    if (!g_adb_prop_synced && (force_now || is_adbd_process_active())) {
        ensure_prop_value("/dev/__properties__/u:object_r:adb_prop:s0", "ro.adb.secure", "1");
        g_adb_prop_synced = 1;
    }
}

static void apply_post_boot_security_props(void) {
    apply_early_boot_props();
    maybe_sync_adb_prop(true);
}

static void toggle_rear_torch(void) {
    g_state.torch_on = !g_state.torch_on;
    if (g_state.torch_on) {
        write_int_file("/sys/class/leds/led:switch_0/brightness", 0);
        write_int_file("/sys/class/leds/led:torch_0/brightness", 140);
        write_int_file("/sys/class/leds/led:torch_1/brightness", 140);
        write_int_file("/sys/class/leds/led:switch_0/brightness", 1);
        write_int_file("/sys/class/leds/flashlight/brightness", 140);
    } else {
        write_int_file("/sys/class/leds/led:torch_0/brightness", 0);
        write_int_file("/sys/class/leds/led:torch_1/brightness", 0);
        write_int_file("/sys/class/leds/led:switch_0/brightness", 0);
        write_int_file("/sys/class/leds/flashlight/brightness", 0);
    }
    pulse_haptic_ms(22);
}

/* ---------------- Hardware Node Application ---------------- */

static void write_telemetry_snapshot(void);

static void apply_touch_gestures(void) {
    char cur_buf[64];
    int mask = 0;
    if (read_str_file("/proc/touchpanel/double_tap_enable_indep", cur_buf, sizeof(cur_buf))) {
        mask = (int)strtol(cur_buf, NULL, 16) & OPPO_FOD_PRESERVE_MASK;
    }

    if (g_state.dt2w_enabled) {
        mask |= (1 << (OPPO_KEY_DOUBLE_TAP - OPPO_KEY_GESTURE_START));
    }
    if (g_state.gesture_torch_v) {
        mask |= (1 << (OPPO_KEY_DOWN_VEE - OPPO_KEY_GESTURE_START));
    }
    if (g_state.gesture_camera_o) {
        mask |= (1 << (OPPO_KEY_CIRCLE - OPPO_KEY_GESTURE_START));
    }
    if (g_state.gesture_music) {
        mask |= (1 << (OPPO_KEY_DOUBLE_SWIPE - OPPO_KEY_GESTURE_START));
        mask |= (1 << (OPPO_KEY_LEFT_VEE - OPPO_KEY_GESTURE_START));
        mask |= (1 << (OPPO_KEY_RIGHT_VEE - OPPO_KEY_GESTURE_START));
    }

    int any_gesture = (mask != 0) ? 1 : 0;
    write_int_file("/proc/touchpanel/double_tap_enable", any_gesture);
    write_int_file("/proc/touchpanel/double_tap_enable_indep", mask);
}

static void disable_hbm_and_restore_backlight(void) {
    g_state.hbm_mode = 0;
    g_hbm_anchor_bl = -1;
    write_int_file(OPPO_HBM_PATH, 0);
    /* Re-apply current DRM/SDM backlight level immediately after exiting HBM */
    long long cur_bl = read_ll_file(PANEL_BL_PATH, -1);
    if (cur_bl > 0) {
        write_int_file(PANEL_BL_PATH, (int)cur_bl);
    }
}

/*
 * Synchronizes Synaptics S3706 FOD touch reporting (/proc/touchpanel/fp_enable) with
 * Goodix G3 (GF9500) optical UDFPS enrollment & unlock events
 * (/sys/kernel/oppo_display/{dimlayer_hbm,notify_fppress}).
 *
 * CRITICAL: Never write 1 to /sys/kernel/oppo_display/hbm during FOD!
 * In the RMX1931 kernel (oppo_onscreenfingerprint.c), oppo_get_panel_brightness_to_alpha()
 * immediately returns 0 if oppo_get_oppo_hbm_mode() != 0, which zeroes out the hardware
 * SDE dim layer alpha so the surrounding screen fails to dim and the Goodix G3 sensor
 * saturates. Instead, sde_crtc.c + sde_connector_update_hbm drive FOD HBM and the SDE
 * dim layer automatically via dimlayer_hbm + FOD_PRESSED_LAYER_ZORDER (0x20000000u).
 */
static void sync_fod_illumination(void) {
    long long fppress = read_ll_file(OPPO_FPPRESS_PATH, 0);
    long long dimlayer = read_ll_file(OPPO_DIMLAYER_PATH, 0);
    int prev_session = g_fod_session_active;
    g_fod_session_active = (dimlayer > 0 || fppress > 0) ? 1 : 0;
    g_fod_hbm_active = (fppress > 0) ? 1 : 0;

    if (g_fod_session_active) {
        if (read_ll_file(OPPO_FP_ENABLE_PATH, 0) != 1) {
            write_int_file(OPPO_FP_ENABLE_PATH, 1);
        }
        /* Ensure manual /sys/kernel/oppo_display/hbm is 0 on FOD session start */
        if (!prev_session) {
            write_int_file(OPPO_HBM_PATH, 0);
        }
    } else if (prev_session && g_state.hbm_mode) {
        /* Restore user-toggled Sunlight HBM after FOD session completes */
        write_int_file(OPPO_HBM_PATH, 1);
    }
}

static void check_backlight_slider_override(int64_t t) {
    if (!g_state.hbm_mode) return;
    long long cur_bl = read_ll_file(PANEL_BL_PATH, -1);
    if (cur_bl <= 0) {
        disable_hbm_and_restore_backlight();
        write_telemetry_snapshot();
        return;
    }
    if (g_hbm_anchor_bl < 0) {
        g_hbm_anchor_bl = cur_bl;
        return;
    }
    /* Whenever the user drags the Quick Settings / Settings brightness slider,
     * composer@2.4 updates panel0-backlight/brightness -> immediately release HBM
     * so manual brightness control responds in real time! */
    if ((t - g_hbm_armed_ms) >= 350 && llabs(cur_bl - g_hbm_anchor_bl) >= 6) {
        disable_hbm_and_restore_backlight();
        write_telemetry_snapshot();
    }
}

static void apply_display_state(void) {
    write_int_file("/sys/kernel/oppo_display/dimlayer_bl_en", g_state.dc_dimming ? 1 : 0);
    write_int_file(OPPO_HBM_PATH, g_state.hbm_mode ? 1 : 0);
    write_int_file("/sys/kernel/oppo_display/seed", g_state.seed_mode);
    write_int_file("/sys/kernel/oppo_display/cabc", g_state.cabc_mode);
}

static void apply_touch_tuning(void) {
    write_int_file("/proc/touchpanel/game_switch_enable", g_state.tp_game_mode ? 1 : 0);
    write_int_file("/proc/touchpanel/oplus_tp_limit_enable", g_state.tp_edge_limit ? 1 : 0);
    write_int_file("/proc/touchpanel/oplus_tp_direction", g_state.tp_direction);
}

static void apply_perf_profile(void) {
    if (g_state.perf_profile == 1) {
        /* Snapdragon 855+ Gaming Mode: Full 2.96 GHz Prime + 675 MHz Adreno 640 */
        write_int_file("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq", 1785600);
        write_int_file("/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq", 2419200);
        write_int_file("/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq", 2956800);
        write_int_file("/sys/devices/system/cpu/cpufreq/policy7/schedutil/down_rate_limit_us", 40000);
        write_int_file("/sys/class/kgsl/kgsl-3d0/throttling", 0);
        set_system_prop_async("perf_profile", "1");
    } else if (g_state.perf_profile == 2) {
        /* Efficiency / Cool Battery Mode */
        write_int_file("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq", 1785600);
        write_int_file("/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq", 1939200);
        write_int_file("/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq", 2035200);
        write_int_file("/sys/devices/system/cpu/cpufreq/policy7/schedutil/down_rate_limit_us", 5000);
        write_int_file("/sys/class/kgsl/kgsl-3d0/throttling", 1);
        set_system_prop_async("perf_profile", "0");
    } else {
        /* Balanced SM8150-AC Default (20ms down_rate_limit keeps 90Hz VSync steady) */
        write_int_file("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq", 1785600);
        write_int_file("/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq", 2419200);
        write_int_file("/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq", 2956800);
        write_int_file("/sys/devices/system/cpu/cpufreq/policy4/schedutil/down_rate_limit_us", 20000);
        write_int_file("/sys/devices/system/cpu/cpufreq/policy7/schedutil/down_rate_limit_us", 20000);
        write_int_file("/sys/class/kgsl/kgsl-3d0/throttling", 1);
        set_system_prop_async("perf_profile", "0");
    }
}

static void apply_battery_and_otg(void) {
    static int s_applied_cool_down = 0;
    static int s_applied_otg = 0;

    if (g_state.cool_down_level != s_applied_cool_down) {
        write_int_file("/sys/class/power_supply/battery/cool_down", g_state.cool_down_level);
        s_applied_cool_down = g_state.cool_down_level;
    }

    if (g_state.otg_enabled) {
        long long otg_on = read_ll_file("/sys/class/power_supply/usb/otg_online", -1);
        if (otg_on < 0) {
            otg_on = read_ll_file("/sys/class/oplus_chg/usb/otg_online", -1);
        }
        if (otg_on == 0) {
            if (g_otg_idle_since_ms == 0) {
                g_otg_idle_since_ms = now_ms();
            } else if (now_ms() - g_otg_idle_since_ms >= 600000LL) {
                g_state.otg_enabled = 0;
                g_otg_idle_since_ms = 0;
            }
        } else {
            g_otg_idle_since_ms = 0;
        }
    } else {
        g_otg_idle_since_ms = 0;
    }

    int target_otg = g_state.otg_enabled ? 1 : 0;
    if (target_otg != s_applied_otg) {
        write_int_file("/sys/class/power_supply/usb/otg_switch", target_otg);
        write_int_file("/sys/class/oplus_chg/usb/otg_switch", target_otg);
        s_applied_otg = target_otg;
    }

    long long cap = read_ll_file("/sys/class/power_supply/battery/capacity", 50);
    if (g_state.chg_limit_pct >= 100) {
        if (g_state.chg_paused_by_limit) {
            write_int_file("/sys/class/power_supply/battery/mmi_charging_enable", 1);
            g_state.chg_paused_by_limit = 0;
        }
    } else {
        if (cap >= g_state.chg_limit_pct && !g_state.chg_paused_by_limit) {
            write_int_file("/sys/class/power_supply/battery/mmi_charging_enable", 0);
            g_state.chg_paused_by_limit = 1;
        } else if (cap <= (g_state.chg_limit_pct - 3) && g_state.chg_paused_by_limit) {
            write_int_file("/sys/class/power_supply/battery/mmi_charging_enable", 1);
            g_state.chg_paused_by_limit = 0;
        }
    }
}

static void apply_vibrator_gain(void) {
    int g = g_state.vib_gain;
    if (g < 32) g = 32;
    if (g > 128) g = 128;
    char hex_buf[16];
    snprintf(hex_buf, sizeof(hex_buf), "0x%02x", g);
    write_str_file("/sys/class/leds/vibrator/gain", hex_buf);
}

static void apply_all_hardware_state(void) {
    apply_touch_gestures();
    apply_display_state();
    apply_touch_tuning();
    apply_perf_profile();
    apply_battery_and_otg();
    apply_vibrator_gain();
}

/* ---------------- Persistent State & Telemetry ---------------- */

static void save_state_prop(void) {
    char tmp_path[128];
    snprintf(tmp_path, sizeof(tmp_path), "%s.tmp", STATE_PROP_PATH);
    FILE *fp = fopen(tmp_path, "w");
    if (!fp) return;
    fprintf(fp,
        "dt2w_enabled=%d\n"
        "dt2s_statusbar=%d\n"
        "dt2s_home=%d\n"
        "gesture_torch_v=%d\n"
        "gesture_camera_o=%d\n"
        "gesture_music=%d\n"
        "dc_dimming=%d\n"
        "hbm_mode=%d\n"
        "seed_mode=%d\n"
        "cabc_mode=%d\n"
        "tp_game_mode=%d\n"
        "tp_edge_limit=%d\n"
        "perf_profile=%d\n"
        "chg_limit_pct=%d\n"
        "cool_down_level=%d\n"
        "otg_enabled=%d\n"
        "vib_gain=%d\n",
        g_state.dt2w_enabled,
        g_state.dt2s_statusbar,
        g_state.dt2s_home,
        g_state.gesture_torch_v,
        g_state.gesture_camera_o,
        g_state.gesture_music,
        g_state.dc_dimming,
        0, /* Never persist hbm_mode=1 across reboots */
        g_state.seed_mode,
        g_state.cabc_mode,
        g_state.tp_game_mode,
        g_state.tp_edge_limit,
        g_state.perf_profile,
        g_state.chg_limit_pct,
        g_state.cool_down_level,
        g_state.otg_enabled,
        g_state.vib_gain
    );
    fclose(fp);
    chmod(tmp_path, 0666);
    rename(tmp_path, STATE_PROP_PATH);
}

static void load_state_prop(void) {
    FILE *fp = fopen(STATE_PROP_PATH, "r");
    if (!fp) {
        save_state_prop();
        return;
    }
    char line[128];
    while (fgets(line, sizeof(line), fp)) {
        char *eq = strchr(line, '=');
        if (!eq) continue;
        *eq = '\0';
        const char *k = line;
        int v = atoi(eq + 1);
        if (strcmp(k, "dt2w_enabled") == 0) g_state.dt2w_enabled = (v != 0);
        else if (strcmp(k, "dt2s_statusbar") == 0) g_state.dt2s_statusbar = (v != 0);
        else if (strcmp(k, "dt2s_home") == 0) g_state.dt2s_home = (v != 0);
        else if (strcmp(k, "gesture_torch_v") == 0) g_state.gesture_torch_v = (v != 0);
        else if (strcmp(k, "gesture_camera_o") == 0) g_state.gesture_camera_o = (v != 0);
        else if (strcmp(k, "gesture_music") == 0) g_state.gesture_music = (v != 0);
        else if (strcmp(k, "dc_dimming") == 0) g_state.dc_dimming = (v != 0);
        else if (strcmp(k, "hbm_mode") == 0) g_state.hbm_mode = 0; /* Always start unlocked */
        else if (strcmp(k, "seed_mode") == 0 && v >= 0 && v <= 2) g_state.seed_mode = v;
        else if (strcmp(k, "cabc_mode") == 0 && v >= 0 && v <= 3) g_state.cabc_mode = v;
        else if (strcmp(k, "tp_game_mode") == 0) g_state.tp_game_mode = (v != 0);
        else if (strcmp(k, "tp_edge_limit") == 0) g_state.tp_edge_limit = (v != 0);
        else if (strcmp(k, "perf_profile") == 0 && v >= 0 && v <= 2) g_state.perf_profile = v;
        else if (strcmp(k, "chg_limit_pct") == 0 && v >= 70 && v <= 100) g_state.chg_limit_pct = v;
        else if (strcmp(k, "cool_down_level") == 0 && v >= 0 && v <= 6) g_state.cool_down_level = v;
        else if (strcmp(k, "otg_enabled") == 0) g_state.otg_enabled = (v != 0);
        else if (strcmp(k, "vib_gain") == 0 && v >= 32 && v <= 128) g_state.vib_gain = v;
    }
    fclose(fp);
}

static void write_telemetry_snapshot(void) {
    long long cap = read_ll_file("/sys/class/power_supply/battery/capacity", -1);
    long long cur_ua = read_ll_file("/sys/class/power_supply/battery/current_now", 0);
    long long volt_uv = read_ll_file("/sys/class/power_supply/battery/voltage_now", 0);
    long long batt_temp = read_ll_file("/sys/class/power_supply/battery/temp", 300);
    long long vooc_ing = read_ll_file("/sys/class/power_supply/battery/voocchg_ing", 0);
    long long cycles = read_ll_file("/sys/class/power_supply/battery/cycle_count", 0);
    long long mmi_chg = read_ll_file("/sys/class/power_supply/battery/mmi_charging_enable", 1);

    long long cpu0_khz = read_ll_file("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", 0);
    long long cpu4_khz = read_ll_file("/sys/devices/system/cpu/cpu4/cpufreq/scaling_cur_freq", 0);
    long long cpu7_khz = read_ll_file("/sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq", 0);
    long long gpu_hz = read_ll_file("/sys/class/kgsl/kgsl-3d0/gpuclk", 0);
    long long skin_mc = read_ll_file("/sys/class/thermal/thermal_zone30/temp", 32000);

    char fps_str[64] = "90.0";
    if (read_str_file("/sys/class/drm/sde-crtc-0/measured_fps", fps_str, sizeof(fps_str))) {
        /* Format often "fps: 90.0 duration:..." -> extract numeric token */
        char *p = fps_str;
        while (*p && !isdigit((unsigned char)*p)) p++;
        if (*p) {
            char clean_fps[24];
            size_t i = 0;
            while (p[i] && (isdigit((unsigned char)p[i]) || p[i] == '.') && i < sizeof(clean_fps) - 1) {
                clean_fps[i] = p[i];
                i++;
            }
            clean_fps[i] = '\0';
            snprintf(fps_str, sizeof(fps_str), "%s", clean_fps);
        }
    }

    char gpu_busy_str[64] = "0";
    if (read_str_file("/sys/class/kgsl/kgsl-3d0/gpubusy", gpu_busy_str, sizeof(gpu_busy_str))) {
        unsigned long long busy = 0, total = 0;
        if (sscanf(gpu_busy_str, "%llu %llu", &busy, &total) == 2 && total > 0) {
            snprintf(gpu_busy_str, sizeof(gpu_busy_str), "%llu", (busy * 100ULL) / total);
        } else {
            snprintf(gpu_busy_str, sizeof(gpu_busy_str), "0");
        }
    }

    FILE *fp = fopen(TELEMETRY_TMP_PATH, "w");
    if (!fp) return;
    fprintf(fp,
        "timestamp_ms=%lld\n"
        "batt_capacity=%lld\n"
        "batt_current_ua=%lld\n"
        "batt_volt_uv=%lld\n"
        "batt_temp_dc=%lld\n"
        "vooc_active=%lld\n"
        "cycle_count=%lld\n"
        "chg_enabled=%lld\n"
        "cpu0_mhz=%lld\n"
        "cpu4_mhz=%lld\n"
        "cpu7_mhz=%lld\n"
        "gpu_mhz=%lld\n"
        "gpu_busy_pct=%s\n"
        "measured_fps=%s\n"
        "skin_temp_mc=%lld\n"
        "dc_dimming=%d\n"
        "hbm_mode=%d\n"
        "otg_enabled=%d\n"
        "perf_profile=%d\n",
        (long long)now_ms(),
        cap,
        cur_ua,
        volt_uv,
        batt_temp,
        vooc_ing,
        cycles,
        mmi_chg,
        cpu0_khz / 1000LL,
        cpu4_khz / 1000LL,
        cpu7_khz / 1000LL,
        gpu_hz / 1000000LL,
        gpu_busy_str,
        fps_str,
        skin_mc,
        g_state.dc_dimming,
        g_state.hbm_mode,
        g_state.otg_enabled,
        g_state.perf_profile
    );
    fclose(fp);
    chmod(TELEMETRY_TMP_PATH, 0666);
    rename(TELEMETRY_TMP_PATH, TELEMETRY_PATH);
}

/* ---------------- Command Queue Handler ---------------- */

static void handle_command_kv(const char *key, const char *val_str) {
    int val = atoi(val_str);
    bool persist = false;

    if (strcmp(key, "SLEEP") == 0 && val == 1) {
        trigger_sleep_now();
    } else if (strcmp(key, "WAKE") == 0 && val == 1) {
        trigger_wake_now();
    } else if (strcmp(key, "DT2W") == 0) {
        g_state.dt2w_enabled = (val != 0);
        apply_touch_gestures();
        persist = true;
    } else if (strcmp(key, "DT2S") == 0) {
        g_state.dt2s_statusbar = (val != 0);
        persist = true;
    } else if (strcmp(key, "DT2S_HOME") == 0) {
        g_state.dt2s_home = (val != 0);
        persist = true;
    } else if (strcmp(key, "GESTURE_TORCH_V") == 0) {
        g_state.gesture_torch_v = (val != 0);
        apply_touch_gestures();
        persist = true;
    } else if (strcmp(key, "GESTURE_CAMERA_O") == 0) {
        g_state.gesture_camera_o = (val != 0);
        apply_touch_gestures();
        persist = true;
    } else if (strcmp(key, "GESTURE_MUSIC") == 0) {
        g_state.gesture_music = (val != 0);
        apply_touch_gestures();
        persist = true;
    } else if (strcmp(key, "DC_DIM") == 0) {
        g_state.dc_dimming = (val != 0);
        write_int_file("/sys/kernel/oppo_display/dimlayer_bl_en", g_state.dc_dimming);
        persist = true;
    } else if (strcmp(key, "HBM") == 0) {
        if (val != 0) {
            g_state.hbm_mode = 1;
            g_hbm_anchor_bl = read_ll_file(PANEL_BL_PATH, -1);
            g_hbm_armed_ms = now_ms();
            write_int_file(OPPO_HBM_PATH, 1);
        } else {
            disable_hbm_and_restore_backlight();
        }
        write_telemetry_snapshot();
    } else if (strcmp(key, "SEED") == 0 && val >= 0 && val <= 2) {
        g_state.seed_mode = val;
        write_int_file("/sys/kernel/oppo_display/seed", g_state.seed_mode);
        persist = true;
    } else if (strcmp(key, "CABC") == 0 && val >= 0 && val <= 3) {
        g_state.cabc_mode = val;
        write_int_file("/sys/kernel/oppo_display/cabc", g_state.cabc_mode);
        persist = true;
    } else if (strcmp(key, "UDFPS_RESET") == 0) {
        write_int_file("/sys/kernel/oppo_display/notify_fppress", 0);
        write_int_file("/sys/kernel/oppo_display/dimlayer_hbm", 0);
    } else if (strcmp(key, "TP_GAME") == 0) {
        g_state.tp_game_mode = (val != 0);
        write_int_file("/proc/touchpanel/game_switch_enable", g_state.tp_game_mode);
        persist = true;
    } else if (strcmp(key, "TP_LIMIT") == 0) {
        g_state.tp_edge_limit = (val != 0);
        write_int_file("/proc/touchpanel/oplus_tp_limit_enable", g_state.tp_edge_limit);
        persist = true;
    } else if (strcmp(key, "TP_DIR") == 0 && val >= 0 && val <= 2) {
        g_state.tp_direction = val;
        write_int_file("/proc/touchpanel/oplus_tp_direction", g_state.tp_direction);
    } else if (strcmp(key, "PERF") == 0 && val >= 0 && val <= 2) {
        g_state.perf_profile = val;
        apply_perf_profile();
        persist = true;
    } else if (strcmp(key, "CHG_LIMIT") == 0 && val >= 70 && val <= 100) {
        g_state.chg_limit_pct = val;
        apply_battery_and_otg();
        persist = true;
    } else if (strcmp(key, "COOL_DOWN") == 0 && val >= 0 && val <= 6) {
        g_state.cool_down_level = val;
        write_int_file("/sys/class/power_supply/battery/cool_down", g_state.cool_down_level);
        persist = true;
    } else if (strcmp(key, "OTG") == 0) {
        g_state.otg_enabled = (val != 0);
        g_otg_idle_since_ms = 0;
        write_int_file("/sys/class/power_supply/usb/otg_switch", g_state.otg_enabled);
        write_int_file("/sys/class/oplus_chg/usb/otg_switch", g_state.otg_enabled);
        persist = true;
    } else if (strcmp(key, "VIB_GAIN") == 0 && val >= 32 && val <= 128) {
        g_state.vib_gain = val;
        apply_vibrator_gain();
        pulse_haptic_ms(18);
        persist = true;
    } else if (strcmp(key, "LOCKSCREEN") == 0) {
        g_state.lockscreen_active = (val != 0);
    } else if (strcmp(key, "HOME_IDLE") == 0) {
        g_state.home_idle_active = (val != 0);
    } else if (strcmp(key, "INTERACTIVE") == 0) {
        g_state.screen_interactive = (val != 0);
        if (!g_state.screen_interactive) {
            g_fod_hbm_active = 0;
            g_fod_session_active = 0;
            if (g_state.hbm_mode) {
                disable_hbm_and_restore_backlight();
                write_telemetry_snapshot();
            } else {
                write_int_file(OPPO_HBM_PATH, 0);
            }
            write_int_file(OPPO_FPPRESS_PATH, 0);
            write_int_file(OPPO_DIMLAYER_PATH, 0);
            apply_touch_gestures();
        }
    } else if (strcmp(key, "BOOT_HEALTHY") == 0 && val == 1) {
        scrub_stale_fastboot_bcb();
        apply_post_boot_security_props();
    } else if (strcmp(key, "TELEMETRY") == 0) {
        g_state.telemetry_until_ms = now_ms() + (int64_t)(val > 0 ? val : 15) * 1000LL;
        write_telemetry_snapshot();
    }

    if (persist) {
        save_state_prop();
    }
}

static void drain_command_queue(void) {
    DIR *dir = opendir(QUEUE_DIR);
    if (!dir) return;
    struct dirent *de;
    while ((de = readdir(dir)) != NULL) {
        if (de->d_name[0] == '.') continue;
        if (strncmp(de->d_name, "adblock_", 8) == 0 || strncmp(de->d_name, "mods_", 5) == 0) continue;
        size_t len = strlen(de->d_name);
        if (len < 8 || strncmp(de->d_name, "cmd_", 4) != 0 || strcmp(de->d_name + len - 4, ".req") != 0) continue;

        char full[256];
        snprintf(full, sizeof(full), "%s/%s", QUEUE_DIR, de->d_name);
        FILE *fp = fopen(full, "r");
        if (fp) {
            char line[160];
            while (fgets(line, sizeof(line), fp)) {
                size_t l = strlen(line);
                while (l > 0 && (line[l - 1] == '\n' || line[l - 1] == '\r' || line[l - 1] == ' ')) {
                    line[--l] = '\0';
                }
                char *eq = strchr(line, '=');
                if (eq) {
                    *eq = '\0';
                    handle_command_kv(line, eq + 1);
                }
            }
            fclose(fp);
        }
        unlink(full);
    }
    closedir(dir);
}

/* ---------------- Input Discovery & Tap-to-Close State Machine ---------------- */

typedef struct {
    int cur_slot;
    bool slot0_down;
    bool slot0_need_anchor;
    bool multi_finger;
    bool moved_too_far;
    int cur_x;
    int cur_y;
    int start_x;
    int start_y;
    int64_t down_ms;

    bool has_prev_tap;
    int prev_tap_x;
    int prev_tap_y;
    int64_t prev_tap_up_ms;
} mt_tap_tracker_t;

static void find_synaptics_inputs(int *out_tp_fd, int *out_kpd_fd) {
    *out_tp_fd = -1;
    *out_kpd_fd = -1;
    for (int i = 0; i < 32; i++) {
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        char name[128] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0) {
            if (strcmp(name, "touchpanel") == 0 && *out_tp_fd < 0) {
                *out_tp_fd = fd;
                continue;
            }
            if (strcmp(name, "touchpanel_kpd") == 0 && *out_kpd_fd < 0) {
                *out_kpd_fd = fd;
                continue;
            }
        }
        close(fd);
    }
}

static void handle_kpd_events(int kpd_fd) {
    struct input_event ev;
    while (read(kpd_fd, &ev, sizeof(ev)) == (ssize_t)sizeof(ev)) {
        if (ev.type != EV_KEY || ev.value != 1) continue;

        switch (ev.code) {
            case KEY_WAKEUP:
            case OPPO_KEY_DOUBLE_TAP:
                if (g_state.dt2w_enabled) {
                    trigger_wake_now();
                }
                break;
            case OPPO_KEY_DOWN_VEE:
                if (g_state.gesture_torch_v) {
                    toggle_rear_torch();
                }
                break;
            case OPPO_KEY_CIRCLE:
                if (g_state.gesture_camera_o) {
                    trigger_wake_now();
                    usleep(60000);
                    emit_key(KEY_CAMERA);
                }
                break;
            case OPPO_KEY_DOUBLE_SWIPE:
                if (g_state.gesture_music) {
                    pulse_haptic_ms(16);
                    emit_key(KEY_PLAYPAUSE);
                }
                break;
            case OPPO_KEY_LEFT_VEE:
                if (g_state.gesture_music) {
                    pulse_haptic_ms(16);
                    emit_key(KEY_NEXTSONG);
                }
                break;
            case OPPO_KEY_RIGHT_VEE:
                if (g_state.gesture_music) {
                    pulse_haptic_ms(16);
                    emit_key(KEY_PREVIOUSSONG);
                }
                break;
            default:
                break;
        }
    }
}

static bool is_in_udfps_exclusion_zone(int x, int y) {
    /* RMX1931 Goodix Optical UDFPS center = (540, 2156), exclude generous r=200px */
    int dx = x - 540;
    int dy = y - 2156;
    return (dx * dx + dy * dy) <= (200 * 200);
}

static void evaluate_double_tap_to_close(int x, int y, int prev_x, int prev_y) {
    int64_t t = now_ms();
    if (t - g_last_wake_ms < 500) return;

    /* 1. Status Bar Double-Tap to Close (portrait & landscape top edge, skipped in gaming mode) */
    if (g_state.dt2s_statusbar && !g_state.tp_game_mode) {
        if (g_state.tp_direction == 0 && y >= 0 && y <= 92 && prev_y >= 0 && prev_y <= 92) {
            trigger_sleep_now();
            return;
        }
        if (g_state.tp_direction == 1 && x >= 0 && x <= 92 && prev_x >= 0 && prev_x <= 92) {
            trigger_sleep_now();
            return;
        }
        if (g_state.tp_direction == 2 && x >= 988 && x <= 1080 && prev_x >= 988 && prev_x <= 1080) {
            trigger_sleep_now();
            return;
        }
    }

    /* 2. Lockscreen Empty-Area Double-Tap to Close */
    if (g_state.dt2s_statusbar && g_state.lockscreen_active) {
        if (y >= 210 && y <= 1880 && x >= 70 && x <= 1010 && !is_in_udfps_exclusion_zone(x, y)) {
            trigger_sleep_now();
            return;
        }
    }

    /* Note: Home Screen Double-Tap to Lock (dt2s_home) is handled in system_server
     * WindowManagerService Session.sendWallpaperCommand("android.wallpaper.tap") so that
     * only genuine taps on empty launcher wallpaper lock the screen (never typing/scrolling). */
}

static void handle_touchpanel_events(int tp_fd, mt_tap_tracker_t *tr) {
    struct input_event ev;
    while (read(tp_fd, &ev, sizeof(ev)) == (ssize_t)sizeof(ev)) {
        if (ev.type == EV_ABS) {
            if (ev.code == ABS_MT_SLOT) {
                tr->cur_slot = ev.value;
                if (tr->cur_slot > 0) {
                    tr->multi_finger = true;
                }
            } else if (ev.code == ABS_MT_TRACKING_ID) {
                if (tr->cur_slot > 0 && ev.value >= 0) {
                    tr->multi_finger = true;
                } else if (tr->cur_slot == 0) {
                    if (ev.value >= 0) {
                        tr->slot0_down = true;
                        tr->slot0_need_anchor = true;
                        tr->multi_finger = false;
                        tr->moved_too_far = false;
                        tr->down_ms = now_ms();
                    } else if (tr->slot0_down) {
                        /* Finger lifted on slot 0 */
                        tr->slot0_down = false;
                        int64_t up_ms = now_ms();
                        int64_t hold_ms = up_ms - tr->down_ms;

                        if (!tr->multi_finger && !tr->moved_too_far && hold_ms >= 10 && hold_ms <= 180) {
                            if (tr->has_prev_tap) {
                                int64_t gap_ms = tr->down_ms - tr->prev_tap_up_ms;
                                int dx = abs(tr->start_x - tr->prev_tap_x);
                                int dy = abs(tr->start_y - tr->prev_tap_y);
                                if (gap_ms >= 30 && gap_ms <= 320 && dx <= 96 && dy <= 96) {
                                    tr->has_prev_tap = false;
                                    evaluate_double_tap_to_close(
                                        tr->start_x, tr->start_y,
                                        tr->prev_tap_x, tr->prev_tap_y
                                    );
                                    continue;
                                }
                            }
                            tr->has_prev_tap = true;
                            tr->prev_tap_x = tr->start_x;
                            tr->prev_tap_y = tr->start_y;
                            tr->prev_tap_up_ms = up_ms;
                        } else {
                            tr->has_prev_tap = false;
                        }
                    }
                }
            } else if (ev.code == ABS_MT_POSITION_X && tr->cur_slot == 0) {
                tr->cur_x = ev.value;
            } else if (ev.code == ABS_MT_POSITION_Y && tr->cur_slot == 0) {
                tr->cur_y = ev.value;
            }
        } else if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
            if (tr->slot0_down) {
                if (tr->slot0_need_anchor) {
                    tr->start_x = tr->cur_x;
                    tr->start_y = tr->cur_y;
                    tr->slot0_need_anchor = false;
                } else {
                    if (abs(tr->cur_x - tr->start_x) > 38 || abs(tr->cur_y - tr->start_y) > 38) {
                        tr->moved_too_far = true;
                    }
                }
            }
        }
    }
}

int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], "--early-props") == 0) {
        apply_early_boot_props();
        return 0;
    }

    apply_early_boot_props();
    maybe_sync_adb_prop(false);

    mkdir("/data/ota_package", 0777);
    chmod("/data/ota_package", 0777);
    mkdir(HW_DIR, 0777);
    chmod(HW_DIR, 0777);
    mkdir(QUEUE_DIR, 0777);
    chmod(QUEUE_DIR, 0777);

    load_state_prop();
    g_uinput_fd = setup_uinput_device();
    apply_all_hardware_state();
    write_telemetry_snapshot();

    int inotify_fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (inotify_fd >= 0) {
        inotify_add_watch(inotify_fd, QUEUE_DIR, IN_MOVED_TO | IN_CLOSE_WRITE | IN_CREATE);
    }

    int tp_fd = -1;
    int kpd_fd = -1;
    find_synaptics_inputs(&tp_fd, &kpd_fd);

    mt_tap_tracker_t tracker;
    memset(&tracker, 0, sizeof(tracker));

    int64_t last_periodic_ms = now_ms();
    drain_command_queue();
    {
        char bc_buf[64] = {0};
        FILE *fp_bc = popen("/system/bin/getprop sys.boot_completed", "r");
        if (fp_bc) {
            if (fgets(bc_buf, sizeof(bc_buf), fp_bc) && bc_buf[0] == '1') {
                apply_post_boot_security_props();
            }
            pclose(fp_bc);
        }
    }

    for (;;) {
        if (tp_fd < 0 || kpd_fd < 0) {
            int new_tp = -1, new_kpd = -1;
            find_synaptics_inputs(&new_tp, &new_kpd);
            if (tp_fd < 0 && new_tp >= 0) tp_fd = new_tp;
            else if (new_tp >= 0) close(new_tp);
            if (kpd_fd < 0 && new_kpd >= 0) kpd_fd = new_kpd;
            else if (new_kpd >= 0) close(new_kpd);
        }

        sync_fod_illumination();

        int timeout_ms = 400;
        int64_t t = now_ms();
        if (g_fod_session_active || g_fod_hbm_active) {
            timeout_ms = 12;
        } else if (g_state.hbm_mode && timeout_ms > 120) {
            timeout_ms = 120;
        }

        struct pollfd pfds[3];
        int nfds = 0;
        int idx_ino = -1, idx_tp = -1, idx_kpd = -1;

        if (inotify_fd >= 0) {
            idx_ino = nfds;
            pfds[nfds].fd = inotify_fd;
            pfds[nfds].events = POLLIN;
            pfds[nfds].revents = 0;
            nfds++;
        }
        if (tp_fd >= 0) {
            idx_tp = nfds;
            pfds[nfds].fd = tp_fd;
            pfds[nfds].events = POLLIN;
            pfds[nfds].revents = 0;
            nfds++;
        }
        if (kpd_fd >= 0) {
            idx_kpd = nfds;
            pfds[nfds].fd = kpd_fd;
            pfds[nfds].events = POLLIN;
            pfds[nfds].revents = 0;
            nfds++;
        }

        int pr = poll(pfds, (nfds_t)nfds, timeout_ms);
        t = now_ms();
        sync_fod_illumination();

        if (pr > 0) {
            if (idx_ino >= 0 && (pfds[idx_ino].revents & POLLIN)) {
                char ibuf[512];
                while (read(inotify_fd, ibuf, sizeof(ibuf)) > 0) {}
                drain_command_queue();
            }
            if (idx_kpd >= 0) {
                if (pfds[idx_kpd].revents & (POLLERR | POLLHUP | POLLNVAL)) {
                    close(kpd_fd);
                    kpd_fd = -1;
                } else if (pfds[idx_kpd].revents & POLLIN) {
                    handle_kpd_events(kpd_fd);
                }
            }
            if (idx_tp >= 0) {
                if (pfds[idx_tp].revents & (POLLERR | POLLHUP | POLLNVAL)) {
                    close(tp_fd);
                    tp_fd = -1;
                } else if (pfds[idx_tp].revents & POLLIN) {
                    handle_touchpanel_events(tp_fd, &tracker);
                }
            }
        }

        if (g_state.hbm_mode && !g_fod_hbm_active) {
            check_backlight_slider_override(t);
        }

        if (t - last_periodic_ms >= 2800) {
            last_periodic_ms = t;
            if (!g_state.hbm_mode && !g_fod_hbm_active && !g_fod_session_active && read_ll_file(OPPO_HBM_PATH, 0) != 0) {
                disable_hbm_and_restore_backlight();
            }
            if (!g_adb_prop_synced) {
                maybe_sync_adb_prop(false);
            }
            apply_battery_and_otg();
            if (g_state.telemetry_until_ms > t) {
                write_telemetry_snapshot();
            }
        }
    }

    return 0;
}
