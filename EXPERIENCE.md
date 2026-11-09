# Project Experience Memory (`EXPERIENCE.md`)

Persistent root-cause lessons and architectural invariants distilled by **Harness²** (`Propose -> Probe -> Compose`). Read before modifying code in this workspace.

---

## `EXP-001` — Lock Screen Multi-Hour Stuck State & Hidden Background Churn
- **Symptom / Failure Mode:** Device ran smoothly for several hours on `ios_beta`, then became stuck on the Lock Screen (bottom swipe-up failed to lift the curtain or accumulated notifications trapped upward drags, compounded by continuous background Binder/Compose work while unlocked or screen-off).
- **Root Cause:**
  1. Quickstep's SPY `InputMonitor` raced `SamuraiIos26LockScreen` (`ControlCenterOverlayService.kt`) on bottom-edge upward gestures.
  2. Expanded Lock Screen notifications in `IosLockScreen.kt` used an eager `Column + verticalScroll` without resetting across screen-off/re-lock sessions (`lockSessionEpoch`).
  3. `ControlCenterOverlayService.syncControllerPanelVisibility()` was missing, leaving `ControlCenterController.isPanelVisible = true` while unlocked or screen-off and causing ~20 synchronous Binder IPCs plus hidden `IosLockScreenRoot` recompositions on every system broadcast.
- **Guardrail / Invariant Added:**
  - Keep `TopStatusGestureMonitor` bottom pilfering active when locked, render expanded Lock Screen notifications in a bounded `LazyColumn` (`notifLazyListState`) that resets on `lockSessionEpoch`, call `syncControllerPanelVisibility()` on every visibility/screen state transition, and return from `IosLockScreenRoot` before reading `controller.state` when `!isLockScreenVisible && curtainDismissProgress <= 0.001f`.

## `EXP-002` — RMX1931 Goodix G3 (`[540, 2156, 95]`) Single-Press Optical UDFPS Aperture
- **Symptom / Failure Mode:** Full-screen `SamuraiIos26LockScreen` overlay intercepted Press #1 on the in-display optical fingerprint sensor and attenuated 1000-nit HBM sensor light with opaque Compose wallpaper pixels, while `dp`-based sensor coordinates drifted on `RMX1931` (`420dpi`, `density = 2.625`).
- **Root Cause:** `UdfpsSensorGeometry` was previously approximated in `dp` instead of physical `1080x2400` panel pixels (`config_udfps_sensor_props = [540, 2156, 95]`), and `SamuraiIos26LockScreen` had a full-frame touchable region covering `UdfpsControllerOverlay`.
- **Guardrail / Invariant Added:**
  - Always resolve UDFPS geometry in physical viewport pixels via `UdfpsSensorGeometry.resolve()` (`540, 2156, 95` on `1080x2400`).
  - Carve a circular `ViewTreeObserver.OnComputeInternalInsetsListener` (`TOUCHABLE_INSETS_REGION = 3`) touch aperture over `(cx, cy, r)` on `SamuraiIos26LockScreen` whenever secure keyguard + enrolled UDFPS is active (`bottomMode = 4` leaves `< 28dp` stationary holds unpilfered for `0ms` Press-#1 scan and pilfers `>= 28dp` upward drags into Lock Screen swipe-to-unlock).
  - Punch a `BlendMode.Clear` (`CompositingStrategy.Offscreen`) circle under the thumb while `isUdfpsFingerDown == true`, and pad lower Lock Screen notification controls strictly above `cy - r`.

## `EXP-003` — Android 17 `Session.sendWallpaperCommand` Smali Modifier Variance
- **Symptom / Failure Mode:** `build_apps.sh` failed in `android17/scripts/patch_services_a17.py` (`patch_wm_session`) with `RuntimeError: sendWallpaperCommand not found in com/android/server/wm/Session.smali`.
- **Root Cause:** On CP3A `services_stock.jar`, `javac` omits `ACC_FINAL` on methods of `final class Session`, emitting `.method public sendWallpaperCommand(...)` rather than `.method public final sendWallpaperCommand(...)`.
- **Guardrail / Invariant Added:** `patch_services_a17.py:patch_wm_session` must check both `.method public final sendWallpaperCommand(` and `.method public sendWallpaperCommand(`.

## `EXP-004` — Dual-Channel Stable (`pixelos`) vs Opt-In Beta (`ios-beta`) Isolation
- **Symptom / Failure Mode:** Accidental overwrite of Pure Retail Google PixelOS Stable manifests (`ota/stable/rmx1931.json`, `ota/rmx1931.json`) when packaging or publishing an Opt-In iOS Style UI Beta update.
- **Root Cause:** Shared scripts default to `ROM_FLAVOR=pixelos` or `--channel both` when invoked without explicit channel flags.
- **Guardrail / Invariant Added:** Always pass `--flavor ios-beta` / `ROM_FLAVOR=ios-beta` during iOS Beta builds, pass `--channel ios-beta` to `publish_ota.sh`, and verify `git diff ota/rmx1931.json ota/stable/rmx1931.json` is empty before and after publishing.

## `EXP-005` — Refreshing `vendor.raw.img` Whenever `patch_vendor_a17.py` Changes
- **Symptom / Failure Mode:** `verify_images.sh` `[GATE 7/9]` failed with `FAIL: vendor fp 2.3 rc missing /data/vendor/fingerprint/gf_data!` when `build_rom_zip.sh` was run with `--system-img-only`.
- **Root Cause:** `--system-img-only` reuses the existing `/var/tmp/rmx1931-a17/out/images/vendor.raw.img` without re-applying `android17/vendor-patches/patch_vendor_a17.py`.
- **Guardrail / Invariant Added:** Whenever `patch_vendor_a17.py` has newer Goodix UDFPS / VINTF / HAL patches than the cached `vendor.raw.img`, refresh `vendor.raw.img` from donor + `patch_vendor_a17.py` before running `build_ota_incremental.sh` and `verify_images.sh`.

## `EXP-006` — Keeping `ViewTreeObserver` UDFPS Touch Aperture Synced Across GPU-Only Curtain Springs
- **Symptom / Failure Mode:** After an aborted Lock Screen upward swipe (`< 0.20f` progress), `animateLockCurtainBackToZero()` settled `lockCurtainProgressState` to `0f` via GPU layer translation/clip without triggering `ViewRootImpl.performTraversals()`, leaving `SamuraiIos26LockScreen` stuck at `TOUCHABLE_INSETS_FRAME` (`0`) instead of reopening the `[540, 2156, 95]` `TOUCHABLE_INSETS_REGION` aperture.
- **Root Cause:** `ViewTreeObserver.OnComputeInternalInsetsListener` only runs during layout traversals, and `syncSystemKeyguardSuppression()` mutated `isSecureKeyguardState` / `hasEnrolledUdfpsState` before `syncLockScreenVisibility()` compared `prevSecure != secure`.
- **Guardrail / Invariant Added:** Track `lastCarvedUdfpsAperture` inside `installTouchAperture`'s callback and call `syncLockUdfpsApertureIfChanged()` on every curtain spring frame/settlement, sheet progress change, keyguard suppression update, and visibility transition.

## `EXP-007` — Non-Telecom VoIP `AudioManager.MODE_IN_COMMUNICATION` Occlusion Continuity
- **Symptom / Failure Mode:** Answering a non-Telecom VoIP call (WhatsApp, Telegram, Signal, Discord, Meet, Zoom) in Swipe Lock mode switched audio mode from `MODE_RINGTONE` (`1`) to `MODE_IN_COMMUNICATION` (`3`) (or `MODE_COMMUNICATION_REDIRECT` = `5`), causing `lockSafetyWatchdog` to re-occlude the active call UI within `600ms`.
- **Root Cause:** Non-Telecom apps lack `MODIFY_PHONE_STATE` and cannot enter `MODE_IN_CALL`; checking only `MODE_RINGTONE` and `MODE_IN_CALL` missed answered VoIP sessions, and recapturing `swipeLockInitialComp` when `iosSwipeLockEngaged` was already `true` erased `newlyLaunchedAfterScreenOff` if Power was pressed while ringing.
- **Guardrail / Invariant Added:** Treat `MODE_RINGTONE` / `MODE_IN_CALL` as unconditionally critical, treat `MODE_IN_COMMUNICATION` / `MODE_COMMUNICATION_REDIRECT` as critical whenever `(isSystemLocked || newlyLaunchedAfterScreenOff)`, and never overwrite `swipeLockInitialComp` on `ACTION_SCREEN_OFF` while `iosSwipeLockEngaged` is already `true`.

## `EXP-008` — RMX1931 Goodix G3 Optical UDFPS Registration & Illumination on Android 17 GSI
- **Symptom / Failure Mode:** Fingerprint enrollment and authentication failed across both `pixelos` and `ios-beta` ("Fingerprint not able to register").
- **Root Cause:**
  1. Android 17 GSI shipped an empty `/system/etc/vintf/compatibility_matrix.device.xml`, so `servicemanager` rejected `vendor.oplus.hardware.syshelper.IUdfpsHelper/default` and `android.hardware.biometrics.fingerprint@2.3-service.samurai` blocked forever waiting for `IUdfpsHelper/default` before calling `registerAsService()`.
  2. Missing `persist.vendor.fingerprint.sensor_type=optical` (`G_OPTICAL_G3`, `iconlocation=244`, `iconsize=190`) and `/proc/touchpanel/fp_enable` + `/data/vendor/fingerprint/gf_data` permissions.
  3. Stock Google Pixel `surfaceflinger` lacks LineageOS `FOD_PRESSED_LAYER_ZORDER` bits (`0x41000005`), so `dimlayer_hbm=1` + `notify_fppress=1` alone did not trigger 1000-nit panel HBM (`dsi_display_hbm_on()`).
- **Guardrail / Invariant Added:**
  - Install `SamuraiParts/etc/vintf/compatibility_matrix.device.xml` (extracted from RMX1931 donor system) into `/system/etc/vintf/compatibility_matrix.device.xml`.
  - Stamp `persist.vendor.fingerprint.sensor_type=optical` (`G_OPTICAL_G3`) in `/vendor/build.prop` and `/system/build.prop`, and initialize `/data/vendor/fingerprint/gf_data` + `/proc/touchpanel/fp_enable` in `android.hardware.biometrics.fingerprint@2.3-service.samurai.rc`.
  - Poll `dimlayer_hbm` and `notify_fppress` at 83Hz (`12ms`) in `samurai_hw_daemon.c` (`sync_fod_illumination()`) to write `1` to `/sys/kernel/oppo_display/hbm` on finger press and restore HBM/backlight on release.

## `EXP-009` — Home Screen Double-Tap to Lock (`dt2s_home`) False Positives
- **Symptom / Failure Mode:** Enabling double-tap to lock on Home Screen caused random screen locks while typing on the keyboard, scrolling widgets, or pulling down the notification shade.
- **Root Cause:** `samurai_hw_daemon.c` previously triggered `KEY_SLEEP` on any two raw touchpanel taps within `x in [60..1020], y in [240..2040]` whenever `home_idle_active == 1` (polled every `450..1800ms`).
- **Guardrail / Invariant Added:** Never trigger `dt2s_home` from raw `/dev/input/event*` coordinates; instead, handle `"android.wallpaper.tap"` inside `SamuraiPlatformSigBridge.onWallpaperCommand` (hooked in `com/android/server/wm/Session.smali -> sendWallpaperCommand`) so only genuine double-taps on empty Home Screen wallpaper call `PowerManager.goToSleep()`, while `samurai_hw_daemon.c` handles Status Bar (`y <= 92`) and Lock Screen double-tap to sleep.

## `EXP-010` — Android 17 Developer Options Force-Close (`logpersistd_logging_prop`)
- **Symptom / Failure Mode:** Clicking `Settings -> System -> Developer options` immediately force-closed `com.android.settings`.
- **Root Cause:** `AbstractLogpersistPreferenceController` in `com.android.settings` (`system_app`) calls `SystemProperties.set("persist.logd.logpersistd", ...)` on entry, which threw a fatal `RuntimeException` when `plat_sepolicy.cil` lacked `property_service (set)` on `logpersistd_logging_prop`.
- **Guardrail / Invariant Added:** Grant `(allow system_app logpersistd_logging_prop (property_service (set)))` (along with `logd_prop`, `debug_prop`, and `log_tag_prop`) in `plat_sepolicy.cil` and verify it in Gate 5 of `verify_images.sh`.

## `EXP-011` — Android 17 `SceneContainer` Single-Swipe Primary Bouncer (`Overlays.Bouncer`) Handoff
- **Symptom / Failure Mode:** With PIN / Pattern / Password + Goodix UDFPS enrolled on `ios_beta`, swiping up on `SamuraiIos26LockScreen` briefly flashed stock Android's Lock Screen instead of opening the PIN/Pattern/Password pad directly, required a second swipe, and snapped `SamuraiIos26LockScreen` back on top after `2.5s` while the user tried to unlock.
- **Root Cause:**
  1. `IosKeyguardDismissActivity` called `KeyguardManager.requestDismissKeyguard(activity, callback)`, which on Android 17 (`SceneContainerFlag = true`) routes into `DeviceEntryInteractor.attemptDeviceEntry(skipShowingAlternateBouncer = false)` -> `AlternateBouncerView` (fingerprint prompt over stock Android's Lock Screen) whenever UDFPS is enrolled (`canShowAlternateBouncer == true`).
  2. `IosKeyguardDismissActivity.onStop()` fired as soon as SystemUI's `NotificationShade` took focus over the activity, setting `isDismissActivityAlive = false`; `ControlCenterOverlayService.lockSafetyWatchdog` then cleared `bouncerPassthrough = false` after `2500ms`, snapping `SamuraiIos26LockScreen` right back over the bouncer.
  3. `IosLockScreen.kt` passed `outsideBackdropAlpha = 1f` on secure keyguard while `CcLiquidGlassEngine.kt` rendered raw unblurred `crystalWp` in the exterior region below the lifting curtain and hid `lockHostView = View.GONE` before `Overlays.Bouncer` finished composing.
- **Guardrail / Invariant Added:**
  - Never use `IosKeyguardDismissActivity` (`Activity.onStop`-bound) for normal Lock Screen swipe-up unlock; use `SecureBouncerHandoffCoordinator` (`ControlCenterOverlayService.kt` + `SecureBouncerHandoffCoordinator.kt`).
  - At `t = 0ms` of swipe-up release (`animateLockCurtainAndUnlock`), release focus/touch (`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE` via `shouldShow = false`) while keeping `shouldRenderVisual = true` (`holdingBouncerMask`, frosted `outsideBackdropAlpha = 1f` backdrop in `CcLiquidGlassEngine.kt`).
  - Poll `lockHostView?.hasWindowFocus()` (`<= 96ms` max) before injecting `KEYCODE_DPAD_CENTER` (`23`), strictly chain `IWindowManager.dismissKeyguard(callbackStub, "SamuraiIos26Unlock")` `+24ms` *after* `KEYCODE_DPAD_CENTER` (so frame hitches never invert their arrival in SystemUI), trigger idempotent `notifyCurtainSettled()` at `progress >= 0.965f` (with `max(115ms from start, 95ms post-confirm)` compose floor before the `85ms` alpha crossfade), keep `bouncerPassthrough = true` on `onDismissSucceeded()` if `WMS` still transiently reports `km.isKeyguardLocked == true` (`<= 500ms`), reject delayed `ACTION_USER_PRESENT` broadcasts when `km.isKeyguardLocked == true`, and guard idle recovery with `60_000ms` + touch/IME activity + sequence-checked `onScreenOff()`.

## `EXP-012` — Android 17 `HidlFingerprintSensorConfig` `sensorType=3` (`TYPE_UDFPS_OPTICAL`), CDSP FastRPC (`vendor.cdsprpcd`), & `IosSettings` Full Developer Options Launcher
- **Symptom / Failure Mode:**
  1. Even with `IUdfpsHelper/default` and `compatibility_matrix.device.xml` present, Goodix G3 optical UDFPS (`GF9500`) registration/enrollment still failed on device across both `pixelos` and `ios-beta`.
  2. On `ios-beta`, `IosSettings -> Developer Options` only exposed a curated 5-item iOS-styled sheet (USB Debugging, Wireless Debugging, Window/Transition/Animator animation scales), leaving users unable to access or verify the full 80+ toggle native Android 17 `DevelopmentSettingsDashboardActivity`.
- **Root Cause:**
  1. **Android 17 `HidlFingerprintSensorConfig` Framework Bug (`sensorType = 0`):** In Android 17 (`CP3A.260905.010`), `com/android/server/biometrics/sensors/fingerprint/hidl/HidlFingerprintSensorConfig.smali` parses `config_udfps_sensor_props = [540, 2156, 95]` and sets `isUdfps = true`, but never updates `this.sensorType` when `this.modality == 2`, leaving `sensorType = 0` (`TYPE_UNKNOWN`) instead of `3` (`TYPE_UDFPS_OPTICAL`). Consequently, `FingerprintSensorPropertiesInternal.isAnyUdfpsType()` (which checks `sensorType == 2 || sensorType == 3`) and `SystemUI` `UdfpsController` (`isOptical() -> sensorType == 3`) both returned `false`, so SystemUI never attached `UdfpsControllerOverlay` or dispatched `onPointerDown` -> `HidlToAidlSessionAdapter.onPointerDown` -> `UdfpsHelper.onFingerDown` -> `IBiometricsFingerprint@2.3::onFingerDown`.
  2. **SELinux Denial on `/odm/vendor/firmware/goodixfp.*` (`vendor_firmware_file`):** `hal_fingerprint_default`, `tee_202504`, and `hal_cryptoeng_default` only had `allow` rules for `firmware_file`, not `vendor_firmware_file`, blocking `qseecomd` / `libgf_hal_G3.so` from loading the Goodix G3 TrustZone TA (`goodixfp.mdt`).
  3. **Missing CDSP FastRPC Daemon (`vendor.cdsprpcd`) & `vendor_hal_dspmanager_client`:** `libgf_hal_G3.so` loads `libhvx_proxy_stub.so` (`libcdsprpc.so`) to execute `libproxy_skel.so` on the CDSP (`_dom=cdsp`), which requires `service vendor.cdsprpcd /vendor/bin/cdsprpcd`, `ADSP_LIBRARY_PATH` / `DSP_LIBRARY_PATH` environment variables on `fps_hal`, `vendor_hal_dspmanager_client` membership on `hal_fingerprint_default` and `vendor_cdsprpcd`, and `write`/`ioctl` access to `/dev/adsprpc-smd` (`vendor_qdsp_device`).
  4. **Missing `hal_cryptoeng_client` & `/mnt/vendor/persist/fingerprint` Permissions:** `hal_fingerprint_default` lacked `hal_cryptoeng_client` and read/write permissions on `vendor_persist_file` (`mnt_vendor_file_202504`).
- **Guardrail / Invariant Added:**
  - Patch `com/android/server/biometrics/sensors/fingerprint/aidl/Sensor.smali` (`getFingerprintSensorPropertiesInternal`) in `patch_services_a17.py` (`patch_fingerprint_aidl_sensor`) so `sensorType == 0` is upgraded to `0x3` (`TYPE_UDFPS_OPTICAL`) and `halControlsIllumination` is set to `0x1` (`true`).
  - Start `vendor.cdsprpcd` in `/vendor/etc/init/vendor.qti.adsprpc-service.rc`, set `ADSP_LIBRARY_PATH` and `DSP_LIBRARY_PATH` in both `/vendor/etc/init/android.hardware.biometrics.fingerprint@2.3-service.samurai.rc` and `/odm/etc/init/vendor.oplus.hardware.biometrics.fingerprint@2.1-service.rc`, and grant `vendor_firmware_file`, `vendor_hal_dspmanager_client`, `hal_cryptoeng_client`, `vendor_qdsp_device`, and `vendor_persist_file` permissions in `/vendor/etc/selinux/vendor_sepolicy.cil` (verified in Gate 7 of `verify_images.sh`).
  - Include a prominent **"System Developer Dashboard -> Open Full Android Developer Options"** launcher item at the top of `DeveloperOptionsScreen` in `IosSettings.apk` (`SystemSettingsController.openNativeDeveloperOptions()`) that launches `com.android.settings`'s `DevelopmentSettingsDashboardActivity`.

## `EXP-013` — Project Infinity-X 4.1 (`lineage-24.0`) Optical UDFPS Hardware Pipeline (`FOD_PRESSED_LAYER_ZORDER` `0x20000000u` + `udfps_icon_pressed.webp` + `oppo_display/hbm = 0`)
- **Symptom / Failure Mode:**
  - Even after `sensorType=3`, CDSP FastRPC, and SELinux TA rules (`EXP-012`), optical fingerprint registration and unlock on Realme X2 Pro (`RMX1931` / `samurai`, Goodix G3 `GF9500`) still failed on real hardware because the sensor never received the calibrated `190x190` warm-amber lightspot or the kernel `GF_NET_EVENT_UI_READY` (`4`) netlink notification.
- **Root Cause:**
  1. **Writing `1` to `/sys/kernel/oppo_display/hbm` Zeroed the Kernel Dim-Layer Alpha (`alpha = 0`):** In the RMX1931 kernel (`drivers/gpu/drm/msm/oppo/oppo_onscreenfingerprint.c`), `oppo_get_panel_brightness_to_alpha()` starts with `if (oppo_get_oppo_hbm_mode()) return 0;`. When `samurai_hw_daemon.c` wrote `1` to `/sys/kernel/oppo_display/hbm` during FOD, the kernel computed `alpha = 0` (transparent dim layer), ruining sensor optical contrast.
  2. **Stock Google GSI `surfaceflinger` Lacks `FOD_PRESSED_LAYER_ZORDER` (`0x20000000u`):** In the RMX1931 kernel (`sde_plane.c` / `sde_crtc.c`), `sde_plane_atomic_set_property` only sets `PLANE_PROP_CUSTOM = 2` (`fod_val = 2`) when `PLANE_PROP_ZPOS` includes bit `0x20000000u` (`FOD_PRESSED_LAYER_ZORDER`), which then causes `sde_crtc_onscreenfinger_atomic_check` to insert the hardware SDE dim layer below the `190x190` FOD plane, enable panel FOD HBM (`oppo_ofp_set_fp_type`), and fire `msm_drm_notifier_call_chain(MSM_DRM_ONSCREENFINGERPRINT_EVENT, 1)` -> `gf_spi.c` -> `GF_NET_EVENT_UI_READY` -> `libgf_hal_G3.so`. Furthermore, `libsdedrm.so` (`sde_drm::AddProperty`) caches `PLANE_PROP_ZPOS` in `committed_prop_val_map_` when `geometry_changed == false`.
  3. **Stock Google GSI `SystemUI.apk` (`UdfpsControllerOverlay`) Draws a Transparent Touch View Without a `190x190` Lightspot Buffer:** Stock Pixel `SystemUI.apk` assumes HWC draws a hardwarelhbm circle and never renders the Oppo/Realme factory `190x190` calibrated warm-amber `udfps_icon_pressed.webp` (`RGB (255,208,188)` center -> `(255,255,230)` edge) at `(445, 2061)`.
- **Guardrail / Invariant Added:**
  - Never write `1` to `/sys/kernel/oppo_display/hbm` during FOD in `samurai_hw_daemon.c` (`sync_fod_illumination()`); actively force `/sys/kernel/oppo_display/hbm = 0` while `dimlayer_hbm > 0 || notify_fppress > 0` so `oppo_get_panel_brightness_to_alpha()` uses the calibrated `oppo_fp_alpha_lut_table` + `dimlayer_hbm`.
  - Preload `/vendor/lib64/libfod_zpos_hook.so` (`android17/vendor-patches/fod_zpos_hook.c`, built as a pure `-ffreestanding -nostdinc` Bionic LP64 shared library) into `android.hardware.graphics.composer@2.4-service.rc` to defer `zpos` in `drmModeAtomicAddProperty` and emit `base_zpos | 0x20000000ULL` on the `190x190` FOD plane in `drmModeAtomicCommit` when `notify_fppress > 0` (and clean `base_zpos` when `notify_fppress == 0`, plus `HWC2::Callback::Refresh` on `0 <-> 1` edges).
  - Bundle the factory `190x190` `udfps_icon_pressed.webp` at `/system/etc/udfps_icon_pressed.webp` and render it on a pre-warmed `190x190` `TYPE_SECURE_SYSTEM_OVERLAY` (`2015`, layer `33`) window at `(445, 2061)` via `SamuraiUdfpsOpticalOverlay.java` hooked in `services.jar` (`UdfpsHelper` + `HidlToAidlSessionAdapter` + `HidlToAidlCallbackConverter`).


## `EXP-014` — Goodix G3 Optical UDFPS Registration & Calibration (`libgf_hal_G3.so` SPMT Compatibility, `libdisplaydebug.so` SDM/DRM Bridge, & `/sys/kernel/oppo_display/fp_state`)
- **Symptom / Failure Mode:**
  - Even with `EXP-013` installed, fingerprint registration still failed to advance on physical RMX1931 hardware (`5531f272`):
    1. `getprop vendor.fingerprint.cali` reported `0` and `logcat` logged `[GF_HAL][SZ] spmt_mode:1, is_calibrated:0`, `[GF_HAL][SZ] Need to do SPMT first!`, and `[GF_HAL][Delmar] SZ_Init failed!` whenever `goodix.fingerprint.cali.bin` was missing on panels without factory SPMT data.
    2. `/proc/$(pidof android.hardware.graphics.composer@2.4-service)/maps` did not contain `libfod_zpos_hook.so`.
    3. Even when loaded into `composer@2.4-service`, `read_sysfs_int("/sys/kernel/oppo_display/notify_fppress")` always returned `0` because `notify_fppress` is write-only in the RMX1931 kernel (`oppo_onscreenfingerprint.c`), and `libsdmcore.so` GPU-composited (`Composition = 1` / `CLIENT`) the `190x190` `SamuraiUdfpsOpticalOverlay` into the `1080x2400` framebuffer target instead of allocating a dedicated SDE DRM plane.
- **Root Cause:**
  1. **Missing Factory SPMT Calibration (`SZAlgo::init` / `SZAlgo::getSpmtPassOrNot`):** In `/odm/lib64/libgf_hal_G3.so`, `SZAlgo::init` (`0x28A78`) checks `getSpmtPassOrNot` (`0x28AF4`) and returns `-1` if `spmt_mode == 1 && is_calibrated == 0`.
  2. **Composer Service `DT_NEEDED` Linkage (`libdisplaydebug.so`):** `hwcomposer.qcom.so`, `libsdmcore.so`, and `libsdedrm.so` link against `/vendor/lib64/libdisplaydebug.so` (a 14 KB stub exporting `display::DebugHandler` symbols) ahead of `libdrm.so`.
  3. **Write-Only `notify_fppress` vs Readable `fp_state` & SDM Layer Promotion:** In `oppo_onscreenfingerprint.c`, `notify_fppress` only defines `.store` (`oppo_display_notify_fp_press`), whereas `/sys/kernel/oppo_display/fp_state` defines `.show` (`oppo_display_get_fp_state`, returning `"%d,%d,%d\n"` where the third integer is `touch_state`). Furthermore, `libsdmcore.so` only allocates a dedicated SDE overlay plane for the `190x190` FOD layer when `sdm::Layer::flags` (`hwc_layer[0x82]`) has bit `0x1` (`is_fod_pressed_layer`) set during `sdm::HWCDisplay::BuildLayerStack()` and `CanSkipValidate()` returns `false`.
- **Guardrail / Invariant Added:**
  - Patch `/odm/lib64/libgf_hal_G3.so` in `patch_odm_a17.py` at file offsets `0x28AF4` and `0x28B80` (`mov w8, #1` / `0x52800028`) so `SZAlgo::init` and `SZAlgo::getSpmtPassOrNot` succeed (`vendor.fingerprint.cali=1`), verified by Gate 7 of `verify_images.sh`.
  - Build `fod_zpos_hook.c` with `-Wl,-soname,libdisplaydebug.so` exporting `display::DebugHandler` symbols (`_ZN7display12DebugHandler14debug_handler_E`, `_ZN7display12DebugHandler9log_mask_E` as 8-byte `uint64_t`, `_ZN7display12DebugHandler3SetEPS0_`, `_ZN7display23g_default_debug_handlerE`, `_ZTVN7display19DefaultDebugHandlerE`) and install it as both `/vendor/lib64/libdisplaydebug.so` and `/vendor/lib64/libfod_zpos_hook.so`.
  - In `fod_zpos_hook.c`, read `/sys/kernel/oppo_display/fp_state` (`read_fp_touch_state()`), intercept `_ZN3sdm10HWCDisplay15CanSkipValidateEv` and `_ZN3sdm10HWCDisplay15BuildLayerStackEv` to set `hwc_layer[0x82] = 1` on the `190x190` FOD layer, and tag `PLANE_PROP_ZPOS` with `0x20000000ULL` in `drmModeAtomicCommit`.
  - Keep `SamuraiUdfpsOpticalOverlay` `View.VISIBLE` strictly while `pressed == true` and `View.GONE` when unpressed, and write `0` to `/sys/kernel/oppo_display/hbm` in `samurai_hw_daemon.c` only once on FOD session entry (`if (!prev_session)`).
