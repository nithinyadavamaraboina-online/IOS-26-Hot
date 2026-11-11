/*
 * Realme X2 Pro (RMX1931 / samurai) SDM/DRM Optical UDFPS Display Bridge
 *
 * Pure freestanding Android Bionic LP64 (aarch64-linux-android) library for
 * Android 17 ROM rebuilds.
 *
 * Strictly promotes ONLY the 204x204 (200..208px) calibrated warm-amber lightspot
 * plane at sensor center (540, 2156) to FOD_PRESSED_LAYER_ZORDER (0x20000000ULL),
 * avoiding all 190x190 transparent SystemUI/Settings planes and keeping
 * /sys/kernel/oppo_display/notify_fppress synchronized across every tap without
 * background-thread clobbering or drm_modeset_lock_all deadlocks.
 */

/* Android Bionic LP64 (aarch64) standard fixed-width & POSIX ABI types */
typedef signed char        int8_t;
typedef short              int16_t;
typedef int                int32_t;
typedef long               int64_t;
typedef unsigned char      uint8_t;
typedef unsigned short     uint16_t;
typedef unsigned int       uint32_t;
typedef unsigned long      uint64_t;
typedef unsigned long      size_t;
typedef long               ssize_t;
typedef long               pthread_t;

#define NULL ((void *)0)

/* Android Bionic LP64 constants */
#define RTLD_NEXT          ((void *)(-1L))
#define O_RDONLY           00000000
#define O_WRONLY           00000001
#define O_CLOEXEC          02000000
#define CLOCK_MONOTONIC    1

struct timespec {
    long tv_sec;
    long tv_nsec;
};

/* Android Bionic LP64 pthread_mutex_t is 40 bytes (zero-initialized for PTHREAD_MUTEX_INITIALIZER) */
typedef struct {
    uint64_t __private[8];
} bionic_mutex_t;

/* External Bionic libc.so & libdl.so functions */
extern void *dlsym(void *handle, const char *symbol);
extern int open(const char *pathname, int flags, ...);
extern ssize_t read(int fd, void *buf, size_t count);
extern ssize_t write(int fd, const void *buf, size_t count);
extern int close(int fd);
extern int nanosleep(const struct timespec *req, struct timespec *rem);
extern int pthread_mutex_lock(bionic_mutex_t *mutex);
extern int pthread_mutex_unlock(bionic_mutex_t *mutex);
extern int pthread_create(pthread_t *thread, const void *attr, void *(*start_routine)(void *), void *arg);
extern int pthread_detach(pthread_t thread);

#define FOD_PRESSED_BIT           0x20000000ULL
#define DRM_MODE_OBJECT_PLANE     0xeeeeeeeeU
#define DRM_MODE_ATOMIC_TEST_ONLY 0x0100U
#define DRM_PROP_NAME_LEN         32
#define MAX_DRM_PLANES            32

#define OPPO_DIMLAYER_HBM_PATH    "/sys/kernel/oppo_display/dimlayer_hbm"
#define OPPO_NOTIFY_FPPRESS_PATH  "/sys/kernel/oppo_display/notify_fppress"
#define OPPO_FP_STATE_PATH        "/sys/kernel/oppo_display/fp_state"

/* Provide display::DebugHandler symbols for SDM display composer compatibility */
static void _samurai_dbg_noop(void) {}
static int _samurai_dbg_zero(void) { return 0; }

__attribute__((visibility("default")))
void *_ZTVN7display19DefaultDebugHandlerE[13] = {
    (void *)0,
    (void *)0,
    (void *)_samurai_dbg_noop,
    (void *)_samurai_dbg_noop,
    (void *)_samurai_dbg_noop,
    (void *)_samurai_dbg_noop,
    (void *)_samurai_dbg_noop,
    (void *)_samurai_dbg_noop,
    (void *)_samurai_dbg_noop,
    (void *)_samurai_dbg_zero,
    (void *)_samurai_dbg_zero,
    (void *)_samurai_dbg_noop,
    (void *)_samurai_dbg_noop,
};

__attribute__((visibility("default")))
void *_ZN7display23g_default_debug_handlerE = &_ZTVN7display19DefaultDebugHandlerE[2];

__attribute__((visibility("default")))
void *_ZN7display12DebugHandler14debug_handler_E = &_ZN7display23g_default_debug_handlerE;

__attribute__((visibility("default")))
uint64_t _ZN7display12DebugHandler9log_mask_E = 1ULL;

__attribute__((visibility("default")))
void _ZN7display12DebugHandler3SetEPS0_(void *handler) {
    if (handler != NULL) {
        _ZN7display12DebugHandler14debug_handler_E = handler;
    } else {
        _ZN7display12DebugHandler14debug_handler_E = &_ZN7display23g_default_debug_handlerE;
    }
}

typedef struct _drmModeAtomicReq drmModeAtomicReq;
typedef drmModeAtomicReq* drmModeAtomicReqPtr;

typedef struct _drmModePlaneRes {
    uint32_t count_planes;
    uint32_t *planes;
} drmModePlaneRes, *drmModePlaneResPtr;

typedef struct _drmModeObjectProperties {
    uint32_t count_props;
    uint32_t *props;
    uint64_t *prop_values;
} drmModeObjectProperties, *drmModeObjectPropertiesPtr;

typedef struct _drmModeProperty {
    uint32_t prop_id;
    uint32_t flags;
    char name[DRM_PROP_NAME_LEN];
    int count_values;
    uint64_t *values;
    int count_enums;
    void *enums;
    int count_blobs;
    uint32_t *blob_ids;
} drmModeProperty, *drmModePropertyPtr;

typedef int (*pfn_drmModeAtomicAddProperty)(drmModeAtomicReqPtr, uint32_t, uint32_t, uint64_t);
typedef int (*pfn_drmModeAtomicCommit)(int, drmModeAtomicReqPtr, uint32_t, void*);
typedef drmModePlaneResPtr (*pfn_drmModeGetPlaneResources)(int);
typedef void (*pfn_drmModeFreePlaneResources)(drmModePlaneResPtr);
typedef drmModeObjectPropertiesPtr (*pfn_drmModeObjectGetProperties)(int, uint32_t, uint32_t);
typedef void (*pfn_drmModeFreeObjectProperties)(drmModeObjectPropertiesPtr);
typedef drmModePropertyPtr (*pfn_drmModeGetProperty)(int, uint32_t);
typedef void (*pfn_drmModeFreeProperty)(drmModePropertyPtr);
typedef int32_t (*pfn_HWCCallbacks_Register)(void*, int32_t, void*, void (*)(void));
typedef int32_t (*pfn_HWCDisplay_BuildLayerStack)(void*);
typedef int32_t (*pfn_HWCDisplay_CanSkipValidate)(void*);
typedef void (*pfn_HWC2_Refresh)(void*, uint64_t);

typedef struct {
    uint32_t plane_id;
    uint32_t zpos_prop;
    uint32_t fb_id_prop;
    uint32_t crtc_id_prop;
    uint32_t crtc_x_prop;
    uint32_t crtc_y_prop;
    uint32_t crtc_w_prop;
    uint32_t crtc_h_prop;
    uint32_t src_w_prop;
    uint32_t src_h_prop;

    uint64_t base_zpos;
    uint64_t cur_fb_id;
    uint64_t cur_crtc_id;
    uint64_t prev_crtc_id;
    int32_t  cur_x;
    int32_t  cur_y;
    uint32_t cur_w;
    uint32_t cur_h;
    uint32_t cur_src_w;
    uint32_t cur_src_h;

    int zpos_pending_in_req;
    int touched_in_req;
    int fod_tagged;
} plane_track_t;

static bionic_mutex_t g_lock = { { 0, 0, 0, 0, 0, 0, 0, 0 } };
static plane_track_t g_planes[MAX_DRM_PLANES];
static int g_num_planes = 0;
static int g_planes_initialized = 0;
static uint32_t g_primary_crtc_id = 0;

static pfn_drmModeAtomicAddProperty real_drmModeAtomicAddProperty = NULL;
static pfn_drmModeAtomicCommit real_drmModeAtomicCommit = NULL;
static pfn_drmModeGetPlaneResources real_drmModeGetPlaneResources = NULL;
static pfn_drmModeFreePlaneResources real_drmModeFreePlaneResources = NULL;
static pfn_drmModeObjectGetProperties real_drmModeObjectGetProperties = NULL;
static pfn_drmModeFreeObjectProperties real_drmModeFreeObjectProperties = NULL;
static pfn_drmModeGetProperty real_drmModeGetProperty = NULL;
static pfn_drmModeFreeProperty real_drmModeFreeProperty = NULL;
static pfn_HWCCallbacks_Register real_HWCCallbacks_Register = NULL;
static pfn_HWCDisplay_BuildLayerStack real_HWCDisplay_BuildLayerStack = NULL;
static pfn_HWCDisplay_CanSkipValidate real_HWCDisplay_CanSkipValidate = NULL;

static void *g_refresh_cb_data = NULL;
static pfn_HWC2_Refresh g_refresh_cb_fn = NULL;
static int g_watcher_started = 0;
static volatile int g_fod_force_validate_frames = 0;
static volatile int g_lightspot_committed = 0;
static int g_prev_had_fod_layer = 0;

static int str_eq(const char *a, const char *b) {
    while (*a && *b) {
        if (*a != *b) {
            return 0;
        }
        a++;
        b++;
    }
    return (*a == '\0' && *b == '\0');
}

static void zero_mem(void *ptr, size_t n) {
    uint8_t *p = (uint8_t *)ptr;
    for (size_t i = 0; i < n; i++) {
        p[i] = 0;
    }
}

static void sleep_ms(int ms) {
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

static int read_sysfs_int(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return 0;
    }
    char buf[16];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) {
        return 0;
    }
    buf[n] = '\0';
    for (ssize_t i = 0; i < n; i++) {
        if (buf[i] >= '1' && buf[i] <= '9') {
            return 1;
        }
    }
    return 0;
}

static void write_sysfs_one(const char *path) {
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd >= 0) {
        ssize_t ignored = write(fd, "1", 1);
        (void)ignored;
        close(fd);
    }
}

static void write_sysfs_zero(const char *path) {
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd >= 0) {
        ssize_t ignored = write(fd, "0", 1);
        (void)ignored;
        close(fd);
    }
}

/*
 * Reads /sys/kernel/oppo_display/fp_state ("X,Y,touch_state\n" emitted by
 * oppo_display_get_fp_state in oppo_onscreenfingerprint.c).
 */
static int read_fp_touch_state(void) {
    int fd = open(OPPO_FP_STATE_PATH, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return 0;
    }
    char buf[32];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) {
        return 0;
    }
    buf[n] = '\0';
    int comma_count = 0;
    for (ssize_t i = 0; i < n; i++) {
        if (buf[i] == ',') {
            comma_count++;
            if (comma_count == 2) {
                for (ssize_t j = i + 1; j < n; j++) {
                    if (buf[j] >= '1' && buf[j] <= '9') {
                        return 1;
                    }
                    if (buf[j] == '0') {
                        return 0;
                    }
                }
                break;
            }
        }
    }
    return 0;
}

/*
 * Exact 4-rotation sensor coordinate matcher for the 204x204 pressed lightspot
 * centered at physical (540, 2156) on 1080x2400:
 *   0 deg   : (438, 2054) -> x in [400..480],   y in [2010..2100]
 *   90 deg  : (2054, 438) -> x in [2010..2100], y in [400..480]
 *   180 deg : (438, 142)  -> x in [400..480],   y in [100..190]
 *   270 deg : (142, 438)  -> x in [100..190],   y in [400..480]
 */
static int is_fod_sensor_xy(int32_t x, int32_t y) {
    if (x >= 400 && x <= 480 && y >= 2010 && y <= 2100) {
        return 1;
    }
    if (x >= 2010 && x <= 2100 && y >= 400 && y <= 480) {
        return 1;
    }
    if (x >= 400 && x <= 480 && y >= 100 && y <= 190) {
        return 1;
    }
    if (x >= 100 && x <= 190 && y >= 400 && y <= 480) {
        return 1;
    }
    return 0;
}

static int is_lightspot_204_plane(const plane_track_t *p) {
    int match_crtc = (p->cur_w >= 200U && p->cur_w <= 208U &&
                      p->cur_h >= 200U && p->cur_h <= 208U);
    int match_src  = (p->cur_src_w >= 200U && p->cur_src_w <= 208U &&
                      p->cur_src_h >= 200U && p->cur_src_h <= 208U);
    if (!match_crtc && !match_src) {
        return 0;
    }
    return is_fod_sensor_xy(p->cur_x, p->cur_y);
}

static int is_lightspot_204_float(float left, float top, float right, float bottom) {
    float w = right - left;
    float h = bottom - top;
    if (w < 200.0f || w > 208.0f || h < 200.0f || h > 208.0f) {
        return 0;
    }
    int32_t x = (int32_t)(left + 0.5f);
    int32_t y = (int32_t)(top + 0.5f);
    return is_fod_sensor_xy(x, y);
}

static void resolve_drm_symbols_locked(void) {
    if (!real_drmModeAtomicAddProperty) {
        real_drmModeAtomicAddProperty = (pfn_drmModeAtomicAddProperty)dlsym(RTLD_NEXT, "drmModeAtomicAddProperty");
    }
    if (!real_drmModeAtomicCommit) {
        real_drmModeAtomicCommit = (pfn_drmModeAtomicCommit)dlsym(RTLD_NEXT, "drmModeAtomicCommit");
    }
    if (!real_drmModeGetPlaneResources) {
        real_drmModeGetPlaneResources = (pfn_drmModeGetPlaneResources)dlsym(RTLD_NEXT, "drmModeGetPlaneResources");
    }
    if (!real_drmModeFreePlaneResources) {
        real_drmModeFreePlaneResources = (pfn_drmModeFreePlaneResources)dlsym(RTLD_NEXT, "drmModeFreePlaneResources");
    }
    if (!real_drmModeObjectGetProperties) {
        real_drmModeObjectGetProperties = (pfn_drmModeObjectGetProperties)dlsym(RTLD_NEXT, "drmModeObjectGetProperties");
    }
    if (!real_drmModeFreeObjectProperties) {
        real_drmModeFreeObjectProperties = (pfn_drmModeFreeObjectProperties)dlsym(RTLD_NEXT, "drmModeFreeObjectProperties");
    }
    if (!real_drmModeGetProperty) {
        real_drmModeGetProperty = (pfn_drmModeGetProperty)dlsym(RTLD_NEXT, "drmModeGetProperty");
    }
    if (!real_drmModeFreeProperty) {
        real_drmModeFreeProperty = (pfn_drmModeFreeProperty)dlsym(RTLD_NEXT, "drmModeFreeProperty");
    }
}

static void init_drm_planes_locked(int fd) {
    if (g_planes_initialized || fd < 0) {
        return;
    }
    resolve_drm_symbols_locked();
    if (!real_drmModeGetPlaneResources || !real_drmModeFreePlaneResources ||
        !real_drmModeObjectGetProperties || !real_drmModeFreeObjectProperties ||
        !real_drmModeGetProperty || !real_drmModeFreeProperty) {
        return;
    }

    drmModePlaneResPtr pres = real_drmModeGetPlaneResources(fd);
    if (!pres) {
        return;
    }

    g_num_planes = 0;
    for (uint32_t i = 0; i < pres->count_planes && g_num_planes < MAX_DRM_PLANES; i++) {
        uint32_t pid = pres->planes[i];
        drmModeObjectPropertiesPtr props = real_drmModeObjectGetProperties(fd, pid, DRM_MODE_OBJECT_PLANE);
        if (!props) {
            continue;
        }

        plane_track_t *p = &g_planes[g_num_planes];
        zero_mem(p, sizeof(*p));
        p->plane_id = pid;

        for (uint32_t j = 0; j < props->count_props; j++) {
            drmModePropertyPtr prop = real_drmModeGetProperty(fd, props->props[j]);
            if (!prop) {
                continue;
            }
            uint64_t val = props->prop_values[j];
            if (str_eq(prop->name, "zpos")) {
                p->zpos_prop = prop->prop_id;
                p->base_zpos = val & ~FOD_PRESSED_BIT;
            } else if (str_eq(prop->name, "FB_ID")) {
                p->fb_id_prop = prop->prop_id;
                p->cur_fb_id = val;
            } else if (str_eq(prop->name, "CRTC_ID")) {
                p->crtc_id_prop = prop->prop_id;
                p->cur_crtc_id = val;
                p->prev_crtc_id = val;
                if (val != 0 && g_primary_crtc_id == 0) {
                    g_primary_crtc_id = (uint32_t)val;
                }
            } else if (str_eq(prop->name, "CRTC_X")) {
                p->crtc_x_prop = prop->prop_id;
                p->cur_x = (int32_t)val;
            } else if (str_eq(prop->name, "CRTC_Y")) {
                p->crtc_y_prop = prop->prop_id;
                p->cur_y = (int32_t)val;
            } else if (str_eq(prop->name, "CRTC_W")) {
                p->crtc_w_prop = prop->prop_id;
                p->cur_w = (uint32_t)val;
            } else if (str_eq(prop->name, "CRTC_H")) {
                p->crtc_h_prop = prop->prop_id;
                p->cur_h = (uint32_t)val;
            } else if (str_eq(prop->name, "SRC_W")) {
                p->src_w_prop = prop->prop_id;
                p->cur_src_w = (uint32_t)(val >> 16);
            } else if (str_eq(prop->name, "SRC_H")) {
                p->src_h_prop = prop->prop_id;
                p->cur_src_h = (uint32_t)(val >> 16);
            }
            real_drmModeFreeProperty(prop);
        }
        real_drmModeFreeObjectProperties(props);
        if (p->zpos_prop != 0) {
            g_num_planes++;
        }
    }
    real_drmModeFreePlaneResources(pres);
    if (g_num_planes > 0) {
        g_planes_initialized = 1;
    }
}

static void trigger_hwc_refresh(void) {
    pfn_HWC2_Refresh fn = g_refresh_cb_fn;
    void *data = g_refresh_cb_data;
    if (fn != NULL && data != NULL) {
        fn(data, 0ULL);
    }
}

/*
 * Non-destructive HWC refresh helper on dimlayer_hbm / fp_state edge transitions.
 * CRITICAL: Never write "0" to notify_fppress here! Writing "0" while the 204x204
 * lightspot is active sets oppo_onscreenfp_status = 0 in the kernel, which causes
 * sde_crtc.c:5508 to set sde_pstate->is_skip = true and aborts GF_NET_EVENT_UI_READY.
 */
static void *fod_watcher_thread(void *arg) {
    (void)arg;
    int last_dimlayer = 0;
    int last_fp_touch = 0;
    int followup_refreshes = 0;

    for (;;) {
        int dimlayer = read_sysfs_int(OPPO_DIMLAYER_HBM_PATH);
        int fp_touch = read_fp_touch_state();

        if (dimlayer != last_dimlayer || fp_touch != last_fp_touch) {
            last_dimlayer = dimlayer;
            last_fp_touch = fp_touch;
            pthread_mutex_lock(&g_lock);
            g_fod_force_validate_frames = 6;
            pthread_mutex_unlock(&g_lock);
            followup_refreshes = 3;
            trigger_hwc_refresh();
        } else if (followup_refreshes > 0) {
            followup_refreshes--;
            trigger_hwc_refresh();
        }

        if (dimlayer || fp_touch || followup_refreshes > 0) {
            sleep_ms(16);
        } else {
            sleep_ms(150);
        }
    }
    return NULL;
}

static void ensure_watcher_started_locked(void) {
    if (!g_watcher_started) {
        g_watcher_started = 1;
        pthread_t tid = 0;
        if (pthread_create(&tid, NULL, fod_watcher_thread, NULL) == 0) {
            pthread_detach(tid);
        }
    }
}

__attribute__((visibility("default")))
int32_t _ZN3sdm12HWCCallbacks8RegisterEN4HWC28CallbackEPvPFvvE(
        void *this_ptr, int32_t descriptor, void *callback_data, void (*pointer)(void)) {
    pthread_mutex_lock(&g_lock);
    if (!real_HWCCallbacks_Register) {
        real_HWCCallbacks_Register = (pfn_HWCCallbacks_Register)dlsym(
                RTLD_NEXT, "_ZN3sdm12HWCCallbacks8RegisterEN4HWC28CallbackEPvPFvvE");
    }
    /* HWC2::Callback::Refresh == 2 */
    if (descriptor == 2 && pointer != NULL) {
        g_refresh_cb_data = callback_data;
        g_refresh_cb_fn = (pfn_HWC2_Refresh)pointer;
        ensure_watcher_started_locked();
    }
    pfn_HWCCallbacks_Register fn = real_HWCCallbacks_Register;
    pthread_mutex_unlock(&g_lock);

    if (fn) {
        return fn(this_ptr, descriptor, callback_data, pointer);
    }
    return 0;
}

/*
 * Intercepts sdm::HWCDisplay::CanSkipValidate() so SDM never skips Validate() while an
 * optical UDFPS session/lightspot is active or transitioning, ensuring BuildLayerStack()
 * and HWDevice::Validate() assign a dedicated hardware SDE overlay pipe to the 204x204 lightspot.
 */
__attribute__((visibility("default")))
int32_t _ZN3sdm10HWCDisplay15CanSkipValidateEv(void *this_ptr) {
    pthread_mutex_lock(&g_lock);
    if (!real_HWCDisplay_CanSkipValidate) {
        real_HWCDisplay_CanSkipValidate = (pfn_HWCDisplay_CanSkipValidate)dlsym(
                RTLD_NEXT, "_ZN3sdm10HWCDisplay15CanSkipValidateEv");
    }
    pfn_HWCDisplay_CanSkipValidate fn = real_HWCDisplay_CanSkipValidate;
    int force_validate = (g_fod_force_validate_frames > 0 || g_lightspot_committed != 0);
    if (g_fod_force_validate_frames > 0) {
        g_fod_force_validate_frames--;
    }
    pthread_mutex_unlock(&g_lock);

    if (force_validate || read_fp_touch_state() != 0 || read_sysfs_int(OPPO_DIMLAYER_HBM_PATH) != 0) {
        return 0;
    }
    if (fn) {
        return fn(this_ptr);
    }
    return 0;
}

/*
 * Intercepts sdm::HWCDisplay::BuildLayerStack() in hwcomposer.qcom.so.
 *
 * Marks ONLY the 204x204 (200..208px) warm-amber optical lightspot layer at sensor
 * coordinates with hwc_layer[0x82] = 1 and geometry_changes |= 0x080U so SDM allocates
 * a dedicated SDE hardware overlay plane instead of GPU-compositing it, while leaving
 * all 190x190 transparent SystemUI/Settings UI layers untouched.
 */
__attribute__((visibility("default")))
int32_t _ZN3sdm10HWCDisplay15BuildLayerStackEv(void *this_ptr) {
    pthread_mutex_lock(&g_lock);
    if (!real_HWCDisplay_BuildLayerStack) {
        real_HWCDisplay_BuildLayerStack = (pfn_HWCDisplay_BuildLayerStack)dlsym(
                RTLD_NEXT, "_ZN3sdm10HWCDisplay15BuildLayerStackEv");
    }
    pfn_HWCDisplay_BuildLayerStack fn = real_HWCDisplay_BuildLayerStack;
    pthread_mutex_unlock(&g_lock);

    int found_fod_layer = 0;

    if (this_ptr != NULL) {
        uint8_t *disp = (uint8_t *)this_ptr;
        uint8_t *end_node = disp + 0xB0;
        uint8_t *node = *(uint8_t **)(disp + 0xA8);
        int guard = 0;
        while (node != NULL && node != end_node && guard < 64) {
            guard++;
            uint8_t *hwc_layer = *(uint8_t **)(node + 0x20);
            if (hwc_layer != NULL) {
                uint8_t *sdm_layer = *(uint8_t **)hwc_layer;
                int is_fod_rect = 0;
                if (sdm_layer != NULL) {
                    float left   = *(float *)(sdm_layer + 0xB9C);
                    float top    = *(float *)(sdm_layer + 0xBA0);
                    float right  = *(float *)(sdm_layer + 0xBA4);
                    float bottom = *(float *)(sdm_layer + 0xBA8);
                    is_fod_rect = is_lightspot_204_float(left, top, right, bottom);
                }
                uint8_t prev_flag = hwc_layer[0x82];
                uint8_t new_flag = is_fod_rect ? 1 : 0;
                if (is_fod_rect) {
                    found_fod_layer = 1;
                }
                if (prev_flag != new_flag) {
                    hwc_layer[0x82] = new_flag;
                    *(uint32_t *)(hwc_layer + 0x90) |= 0x080U;
                } else if (new_flag) {
                    *(uint32_t *)(hwc_layer + 0x90) |= 0x080U;
                }
            }

            /* Standard libc++ std::__tree_next_iter traversal */
            uint8_t *right_child = *(uint8_t **)(node + 0x08);
            if (right_child != NULL) {
                node = right_child;
                int sub_guard = 0;
                while (*(uint8_t **)node != NULL && sub_guard < 64) {
                    node = *(uint8_t **)node;
                    sub_guard++;
                }
            } else {
                uint8_t *parent = *(uint8_t **)(node + 0x10);
                int sub_guard = 0;
                while (parent != NULL && node != *(uint8_t **)parent && sub_guard < 64) {
                    node = parent;
                    parent = *(uint8_t **)(node + 0x10);
                    sub_guard++;
                }
                node = parent;
            }
        }
    }

    pthread_mutex_lock(&g_lock);
    if (found_fod_layer || g_prev_had_fod_layer) {
        g_fod_force_validate_frames = 6;
    }
    g_prev_had_fod_layer = found_fod_layer;
    pthread_mutex_unlock(&g_lock);

    if (fn) {
        return fn(this_ptr);
    }
    return 0;
}

__attribute__((visibility("default")))
int drmModeAtomicAddProperty(
        drmModeAtomicReqPtr req, uint32_t object_id, uint32_t property_id, uint64_t value) {
    pthread_mutex_lock(&g_lock);
    resolve_drm_symbols_locked();
    pfn_drmModeAtomicAddProperty fn = real_drmModeAtomicAddProperty;

    if (g_planes_initialized && req != NULL) {
        for (int i = 0; i < g_num_planes; i++) {
            plane_track_t *p = &g_planes[i];
            if (p->plane_id != object_id) {
                continue;
            }
            p->touched_in_req = 1;
            if (property_id == p->zpos_prop) {
                p->base_zpos = value & ~FOD_PRESSED_BIT;
                p->zpos_pending_in_req = 1;
                pthread_mutex_unlock(&g_lock);
                /* Defer actual zpos insertion to drmModeAtomicCommit so it is emitted once */
                return 1;
            } else if (property_id == p->fb_id_prop) {
                p->cur_fb_id = value;
            } else if (property_id == p->crtc_id_prop) {
                p->prev_crtc_id = p->cur_crtc_id;
                p->cur_crtc_id = value;
                if (value != 0 && g_primary_crtc_id == 0) {
                    g_primary_crtc_id = (uint32_t)value;
                }
            } else if (property_id == p->crtc_x_prop) {
                p->cur_x = (int32_t)value;
            } else if (property_id == p->crtc_y_prop) {
                p->cur_y = (int32_t)value;
            } else if (property_id == p->crtc_w_prop) {
                p->cur_w = (uint32_t)value;
            } else if (property_id == p->crtc_h_prop) {
                p->cur_h = (uint32_t)value;
            } else if (property_id == p->src_w_prop) {
                p->cur_src_w = (uint32_t)(value >> 16);
            } else if (property_id == p->src_h_prop) {
                p->cur_src_h = (uint32_t)(value >> 16);
            }
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);

    if (fn) {
        return fn(req, object_id, property_id, value);
    }
    return -1;
}

__attribute__((visibility("default")))
int drmModeAtomicCommit(int fd, drmModeAtomicReqPtr req, uint32_t flags, void *user_data) {
    pthread_mutex_lock(&g_lock);
    resolve_drm_symbols_locked();
    if (!g_planes_initialized) {
        init_drm_planes_locked(fd);
        ensure_watcher_started_locked();
    }

    pfn_drmModeAtomicAddProperty add_fn = real_drmModeAtomicAddProperty;
    pfn_drmModeAtomicCommit commit_fn = real_drmModeAtomicCommit;

    int is_test_only = (flags & DRM_MODE_ATOMIC_TEST_ONLY) != 0;
    int ensure_fppress_on = 0;
    int notify_fppress_off = 0;

    if (g_planes_initialized && req != NULL && add_fn != NULL) {
        int any_plane_touched = 0;
        int primary_plane_touched = 0;
        for (int i = 0; i < g_num_planes; i++) {
            plane_track_t *p = &g_planes[i];
            if (p->touched_in_req) {
                any_plane_touched = 1;
                if (g_primary_crtc_id == 0 ||
                    p->cur_crtc_id == g_primary_crtc_id ||
                    p->prev_crtc_id == g_primary_crtc_id) {
                    primary_plane_touched = 1;
                }
            }
        }

        if (any_plane_touched && !primary_plane_touched) {
            /* Non-primary (virtual/writeback) display commit: flush deferred zpos untouched */
            for (int i = 0; i < g_num_planes; i++) {
                plane_track_t *p = &g_planes[i];
                if (p->zpos_pending_in_req) {
                    add_fn(req, p->plane_id, p->zpos_prop, p->base_zpos);
                }
                p->zpos_pending_in_req = 0;
                p->touched_in_req = 0;
                if (!is_test_only) {
                    p->prev_crtc_id = p->cur_crtc_id;
                }
            }
        } else if (primary_plane_touched) {
            /*
             * Strictly match ONLY the 204x204 (200..208px) warm-amber optical lightspot plane
             * bound to the primary DSI CRTC at sensor coordinates. Zero fallback matchers.
             */
            plane_track_t *candidate_204 = NULL;
            for (int i = 0; i < g_num_planes; i++) {
                plane_track_t *p = &g_planes[i];
                if (p->cur_fb_id == 0 || p->cur_crtc_id == 0) {
                    continue;
                }
                if (g_primary_crtc_id != 0 && p->cur_crtc_id != g_primary_crtc_id) {
                    continue;
                }
                if (is_lightspot_204_plane(p)) {
                    if (!candidate_204 || p->base_zpos >= candidate_204->base_zpos) {
                        candidate_204 = p;
                    }
                }
            }

            for (int i = 0; i < g_num_planes; i++) {
                plane_track_t *p = &g_planes[i];
                if (p == candidate_204) {
                    add_fn(req, p->plane_id, p->zpos_prop, p->base_zpos | FOD_PRESSED_BIT);
                    if (!is_test_only) {
                        p->fod_tagged = 1;
                    }
                } else if (p->zpos_pending_in_req || p->fod_tagged) {
                    add_fn(req, p->plane_id, p->zpos_prop, p->base_zpos);
                    if (!is_test_only) {
                        p->fod_tagged = 0;
                    }
                }
                p->zpos_pending_in_req = 0;
                p->touched_in_req = 0;
                if (!is_test_only) {
                    p->prev_crtc_id = p->cur_crtc_id;
                }
            }

            if (!is_test_only) {
                if (candidate_204 != NULL) {
                    ensure_fppress_on = 1;
                    g_lightspot_committed = 1;
                } else if (g_lightspot_committed) {
                    g_lightspot_committed = 0;
                    notify_fppress_off = 1;
                }
            }
        }
    }
    pthread_mutex_unlock(&g_lock);

    /*
     * Perform sysfs notifications strictly outside g_lock (and never on TEST_ONLY commits),
     * because oppo_display_notify_fp_press acquires drm_modeset_lock_all on 0<->1 edges
     * (and is a lockless 2-instruction no-op when oppo_onscreenfp_status already matches).
     */
    if (ensure_fppress_on) {
        if (!read_sysfs_int(OPPO_DIMLAYER_HBM_PATH)) {
            write_sysfs_one(OPPO_DIMLAYER_HBM_PATH);
        }
        write_sysfs_one(OPPO_NOTIFY_FPPRESS_PATH);
    }

    int ret = -1;
    if (commit_fn) {
        ret = commit_fn(fd, req, flags, user_data);
    }

    if (notify_fppress_off) {
        write_sysfs_zero(OPPO_NOTIFY_FPPRESS_PATH);
    }

    return ret;
}
