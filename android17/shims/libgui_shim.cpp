/*
 * RMX1931 (SM8150) Android 17 (SDK 37 / CP3A.260905.010) libgui_shim.so
 *
 * Bridges QTI IMS Video Telephony (lib-imsvideocodec.so / libimscamera_jni.so)
 * and WFD callers compiled against Android 14-16 libgui.so symbols onto
 * Android 17's libgui.so AArch64 ABI:
 *
 * 1. android::Surface::Surface(const sp<IGraphicBufferProducer>&, bool)
 *    -> forwards to Android 17's 4-arg constructor:
 *       Surface(const sp<IGraphicBufferProducer>&, bool,
 *               const sp<IBinder>&, std::optional<IGraphicBufferProducer::SurfaceConfig>)
 *    CRITICAL AAPCS64 ABI NOTE:
 *    sizeof(std::optional<SurfaceConfig>) > 16 bytes (engaged flag at +0x28),
 *    so AArch64 passes it by indirect pointer in x4 (A17 libgui.so executes
 *    `ldrb w8, [x4, #0x28]`). Passing nullptr in x4 would SIGSEGV; x4 MUST
 *    point to a 64-byte zero-initialized stack buffer (representing std::nullopt).
 *
 * 2. SurfaceComposerClient::createVirtualDisplay / createDisplay / destroyDisplay
 * 3. SurfaceComposerClient::Transaction::apply(bool) -> apply(bool, bool)
 *
 * Freestanding (-ffreestanding -nostdinc -nostdlib) so zero NDK std::__ndk1
 * symbols ever leak into Android platform's std::__1 ABI.
 */

typedef unsigned char uint8_t;
typedef unsigned int uint32_t;
typedef unsigned long uint64_t;
typedef unsigned long size_t;

extern "C" {
void* malloc(size_t size);
void free(void* ptr);
void* memcpy(void* dst, const void* src, size_t n);
void* memset(void* dst, int c, size_t n);
size_t strlen(const char* s);
}

/* Android platform libc++ (std::__1::string) LP64 little-endian layout (24 bytes):
 *   Short mode (len <= 22): s[0] = (uint8_t)(len << 1), s[1..len] = chars, s[1+len] = '\0'
 *   Long  mode (len >= 23): l.cap = aligned_cap | 1ULL, l.size = len, l.data = heap_buf
 */
struct alignas(8) PlatformStdString {
    union {
        struct {
            uint64_t cap_or_flag;
            uint64_t size;
            char* data;
        } l;
        uint8_t s[24];
    };
};

static inline void platform_std_string_init(PlatformStdString* out, const char* cstr) {
    memset(out, 0, sizeof(*out));
    if (!cstr || !cstr[0]) {
        return;
    }
    size_t len = strlen(cstr);
    if (len <= 22) {
        out->s[0] = (uint8_t)(len << 1);
        memcpy(&out->s[1], cstr, len);
        out->s[1 + len] = '\0';
    } else {
        size_t cap = (len + 1 + 15ULL) & ~15ULL;
        char* buf = (char*)malloc(cap);
        if (buf) {
            memcpy(buf, cstr, len);
            buf[len] = '\0';
            out->l.cap_or_flag = (uint64_t)cap | 1ULL;
            out->l.size = (uint64_t)len;
            out->l.data = buf;
        }
    }
}

static inline void platform_std_string_destroy(PlatformStdString* str) {
    if ((str->s[0] & 1U) != 0 && str->l.data) {
        free(str->l.data);
        str->l.data = nullptr;
    }
}

extern "C" void samurai_invoke_a17_create_virtual_display(
    void* sret_sp_ibinder,
    const PlatformStdString* display_name,
    bool is_secure,
    const PlatformStdString* unique_id,
    float requested_refresh_rate);

extern "C" __attribute__((visibility("hidden"))) void samurai_create_display_helper(
    void* sret_sp_ibinder,
    const char* const* string8_ptr,
    bool is_secure,
    float requested_refresh_rate) {
    const char* raw = (string8_ptr && *string8_ptr) ? *string8_ptr : "";
    PlatformStdString display_name;
    PlatformStdString unique_id;
    platform_std_string_init(&display_name, raw);
    platform_std_string_init(&unique_id, "");
    samurai_invoke_a17_create_virtual_display(
        sret_sp_ibinder, &display_name, is_secure, &unique_id, requested_refresh_rate);
    platform_std_string_destroy(&display_name);
    platform_std_string_destroy(&unique_id);
}

__asm__(
    ".text\n"
    ".align 4\n"

    ".hidden samurai_invoke_a17_create_virtual_display\n"
    ".globl samurai_invoke_a17_create_virtual_display\n"
    ".type samurai_invoke_a17_create_virtual_display, @function\n"
    "samurai_invoke_a17_create_virtual_display:\n"
    "    mov x8, x0\n"
    "    mov x0, x1\n"
    "    and w1, w2, #1\n"
    "    mov w2, #1\n"
    "    mov w4, #0\n"
    "    mov w5, #0\n"
    "    b _ZN7android21SurfaceComposerClient20createVirtualDisplayERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEbbS9_jbf\n"
    ".size samurai_invoke_a17_create_virtual_display, .-samurai_invoke_a17_create_virtual_display\n"

    /* 1a. 2-arg Surface::Surface(const sp<IGraphicBufferProducer>&, bool) [C1 & C2] */
    ".globl _ZN7android7SurfaceC1ERKNS_2spINS_22IGraphicBufferProducerEEEb\n"
    ".type _ZN7android7SurfaceC1ERKNS_2spINS_22IGraphicBufferProducerEEEb, @function\n"
    ".globl _ZN7android7SurfaceC2ERKNS_2spINS_22IGraphicBufferProducerEEEb\n"
    ".type _ZN7android7SurfaceC2ERKNS_2spINS_22IGraphicBufferProducerEEEb, @function\n"
    "_ZN7android7SurfaceC1ERKNS_2spINS_22IGraphicBufferProducerEEEb:\n"
    "_ZN7android7SurfaceC2ERKNS_2spINS_22IGraphicBufferProducerEEEb:\n"
    "    sub sp, sp, #0x60\n"
    "    stp x29, x30, [sp]\n"
    "    mov x29, sp\n"
    "    stp xzr, xzr, [sp, #0x10]\n"
    "    stp xzr, xzr, [sp, #0x20]\n"
    "    stp xzr, xzr, [sp, #0x30]\n"
    "    stp xzr, xzr, [sp, #0x40]\n"
    "    stp xzr, xzr, [sp, #0x50]\n"
    "    add x3, sp, #0x10\n"
    "    add x4, sp, #0x20\n"
    "    bl _ZN7android7SurfaceC1ERKNS_2spINS_22IGraphicBufferProducerEEEbRKNS1_INS_7IBinderEEENSt3__18optionalINS2_13SurfaceConfigEEE\n"
    "    ldp x29, x30, [sp]\n"
    "    add sp, sp, #0x60\n"
    "    ret\n"
    ".size _ZN7android7SurfaceC1ERKNS_2spINS_22IGraphicBufferProducerEEEb, .-_ZN7android7SurfaceC1ERKNS_2spINS_22IGraphicBufferProducerEEEb\n"

    /* 1b. 3-arg Surface::Surface(const sp<IGraphicBufferProducer>&, bool, const sp<IBinder>&) */
    ".globl _ZN7android7SurfaceC1ERKNS_2spINS_22IGraphicBufferProducerEEEbRKNS1_INS_7IBinderEEE\n"
    ".type _ZN7android7SurfaceC1ERKNS_2spINS_22IGraphicBufferProducerEEEbRKNS1_INS_7IBinderEEE, @function\n"
    "_ZN7android7SurfaceC1ERKNS_2spINS_22IGraphicBufferProducerEEEbRKNS1_INS_7IBinderEEE:\n"
    "    sub sp, sp, #0x50\n"
    "    stp x29, x30, [sp]\n"
    "    mov x29, sp\n"
    "    stp xzr, xzr, [sp, #0x10]\n"
    "    stp xzr, xzr, [sp, #0x20]\n"
    "    stp xzr, xzr, [sp, #0x30]\n"
    "    stp xzr, xzr, [sp, #0x40]\n"
    "    add x4, sp, #0x10\n"
    "    bl _ZN7android7SurfaceC1ERKNS_2spINS_22IGraphicBufferProducerEEEbRKNS1_INS_7IBinderEEENSt3__18optionalINS2_13SurfaceConfigEEE\n"
    "    ldp x29, x30, [sp]\n"
    "    add sp, sp, #0x50\n"
    "    ret\n"
    ".size _ZN7android7SurfaceC1ERKNS_2spINS_22IGraphicBufferProducerEEEbRKNS1_INS_7IBinderEEE, .-_ZN7android7SurfaceC1ERKNS_2spINS_22IGraphicBufferProducerEEEbRKNS1_INS_7IBinderEEE\n"

    /* 2a. 4-arg Android 14/15 SurfaceComposerClient::createVirtualDisplay */
    ".globl _ZN7android21SurfaceComposerClient20createVirtualDisplayERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEbS9_f\n"
    ".type _ZN7android21SurfaceComposerClient20createVirtualDisplayERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEbS9_f, @function\n"
    "_ZN7android21SurfaceComposerClient20createVirtualDisplayERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEbS9_f:\n"
    "    mov x3, x2\n"
    "    mov w2, #1\n"
    "    mov w4, #0\n"
    "    mov w5, #0\n"
    "    b _ZN7android21SurfaceComposerClient20createVirtualDisplayERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEbbS9_jbf\n"
    ".size _ZN7android21SurfaceComposerClient20createVirtualDisplayERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEbS9_f, .-_ZN7android21SurfaceComposerClient20createVirtualDisplayERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEbS9_f\n"

    /* 2b. 5-arg Android 16 SurfaceComposerClient::createVirtualDisplay */
    ".globl _ZN7android21SurfaceComposerClient20createVirtualDisplayERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEbbS9_f\n"
    ".type _ZN7android21SurfaceComposerClient20createVirtualDisplayERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEbbS9_f, @function\n"
    "_ZN7android21SurfaceComposerClient20createVirtualDisplayERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEbbS9_f:\n"
    "    mov w4, #0\n"
    "    mov w5, #0\n"
    "    b _ZN7android21SurfaceComposerClient20createVirtualDisplayERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEbbS9_jbf\n"
    ".size _ZN7android21SurfaceComposerClient20createVirtualDisplayERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEbbS9_f, .-_ZN7android21SurfaceComposerClient20createVirtualDisplayERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEbbS9_f\n"

    /* 2c. Legacy String8 SurfaceComposerClient::createDisplay(const String8&, bool, float) */
    ".globl _ZN7android21SurfaceComposerClient13createDisplayERKNS_7String8Ebf\n"
    ".type _ZN7android21SurfaceComposerClient13createDisplayERKNS_7String8Ebf, @function\n"
    "_ZN7android21SurfaceComposerClient13createDisplayERKNS_7String8Ebf:\n"
    "    mov x2, x1\n"
    "    mov x1, x0\n"
    "    mov x0, x8\n"
    "    b samurai_create_display_helper\n"
    ".size _ZN7android21SurfaceComposerClient13createDisplayERKNS_7String8Ebf, .-_ZN7android21SurfaceComposerClient13createDisplayERKNS_7String8Ebf\n"

    /* 2d. SurfaceComposerClient::destroyDisplay(const sp<IBinder>&) */
    ".globl _ZN7android21SurfaceComposerClient14destroyDisplayERKNS_2spINS_7IBinderEEE\n"
    ".type _ZN7android21SurfaceComposerClient14destroyDisplayERKNS_2spINS_7IBinderEEE, @function\n"
    "_ZN7android21SurfaceComposerClient14destroyDisplayERKNS_2spINS_7IBinderEEE:\n"
    "    b _ZN7android21SurfaceComposerClient21destroyVirtualDisplayERKNS_2spINS_7IBinderEEE\n"
    ".size _ZN7android21SurfaceComposerClient14destroyDisplayERKNS_2spINS_7IBinderEEE, .-_ZN7android21SurfaceComposerClient14destroyDisplayERKNS_2spINS_7IBinderEEE\n"

    /* 3. SurfaceComposerClient::Transaction::apply(bool) */
    ".globl _ZN7android21SurfaceComposerClient11Transaction5applyEb\n"
    ".type _ZN7android21SurfaceComposerClient11Transaction5applyEb, @function\n"
    "_ZN7android21SurfaceComposerClient11Transaction5applyEb:\n"
    "    mov w2, #0\n"
    "    b _ZN7android21SurfaceComposerClient11Transaction5applyEbb\n"
    ".size _ZN7android21SurfaceComposerClient11Transaction5applyEb, .-_ZN7android21SurfaceComposerClient11Transaction5applyEb\n"
);
