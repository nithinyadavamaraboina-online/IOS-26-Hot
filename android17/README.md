# Android 17 (`CP3A.260905.010`) Clean Pixel OS + SamuraiParts + Repo OTA for Realme X2 Pro (`RMX1931` / `samurai`)

Pure stock **Google Pixel OS** experience on **Official Android 17 (`CinnamonBun`, API 37.1)** with full **Google Mobile Services (Play Store, GMS, Pixel Launcher, Google Phone, Google Messages, Chrome, Gboard)**, **Tap-to-Wake & Zero-Latency Tap-to-Close**, **90 Hz Samsung AMOLED + SkiaVk Material 3 Expressive blur**, **Goodix G3 Optical In-Display Fingerprint (UDFPS)**, **50W SuperVOOC Smart Charge**, **Dual TFA9894 Stereo EQ + 165 Hz Clear Speaker**, **Linux `4.14.356` eBPF / Connectivity / Keymaster / ION** adaptations, and a built-in **GitHub Repo OTA Updater (`SamuraiOTAUpdater`)**.

---

## 1. Device-Specific Custom Architecture (`RMX1931` / `samurai`)

- **`samurai_hw_daemon` (`/system/bin/samurai_hw_daemon`)**:
  Static `aarch64` C11 hardware & input daemon managing:
  - **Tap-to-Wake (`DT2W`)** & off-screen gestures (`Draw V` Torch, `Draw O` Camera, `||` `<` `>` Music) via Synaptics `S3706` `touchpanel_kpd` (`/proc/touchpanel/double_tap_enable` & `double_tap_enable_indep`, preserving Goodix FOD bits `14-15`).
  - **Zero-Latency Tap-to-Close (Double-Tap to Sleep)**: Passive observation of `touchpanel` + virtual `/dev/uinput` `KEY_SLEEP` (`142`) across **Status Bar**, **Lockscreen**, and **Pixel Launcher Home Screen** — sleeps in `<0.5 ms` while keeping Goodix UDFPS fingerprint unlock active on next wake.
  - **Hardware Sysfs Control & Live Telemetry**: Samsung `SOFEF03F-M` One-Pulse DC Dimming, 1000-nit HBM, DCI-P3/sRGB `SEED`, Snapdragon 855+ (`2.96 GHz` Prime / `675 MHz` Adreno 640) profiles, 50W SuperVOOC Smart Charge cutoff (`80/85/90/100%` with `3%` hysteresis), USB OTG switch, and Awinic `AW8697` X-axis LRA haptics gain.
- **`SamuraiParts.apk` (`com.realme.samurai.parts`)**:
  Privileged Material 3 Expressive device hub integrated directly into **Settings → System → Realme X2 Pro hardware** plus **6 Quick Settings Tiles** (`DC Dimming`, `Sunlight HBM`, `Refresh Rate`, `Performance`, `Charge Limit`, `USB OTG`), per-app smart `60 Hz` video / `90 Hz` game switching, floating FPS/Wattage HUD pill, Dual `TFA9894` 5-band `DynamicsProcessing` stereo soundstage, and `165 Hz` Clear Speaker water/dust ejector.
- **Goodix G3 Optical UDFPS (`patch_odm_a17.py` + RROs)**:
  Installs `goodixfp.{mdt,b00..b07}` directly into `/odm/vendor/firmware/` (`0 ms` load, eliminating the `2.50s` retry stall) and starts `fps_hal` (`@2.1`) + `vendor.fps_hal.samurai` (`@2.3`) on `post-fs-data` ahead of `SystemServer` `AuthService`.

---

## 2. Offline Build & 9-Gate Verification (Touches Zero Connected Devices)

Run inside WSL2 (`wsl -u root`):

```bash
bash android17/scripts/build_rom_zip.sh
bash android17/scripts/verify_images.sh
```

Outputs (written to gitignored `work/a17/out/` + repo manifest `ota/rmx1931.json`):
- `work/a17/out/RMX1931-Android17-PixelOS-OTA.zip` — TWRP flashable & OTA-ready ROM package
- `work/a17/out/SamuraiOTAUpdater.apk` — Privileged Pixel OS System Update app (`com.realme.ota`)
- `work/a17/out/SamuraiParts.apk` — Privileged RMX1931 Hardware, Gestures & Audio Hub (`com.realme.samurai.parts`)
- `work/a17/out/samurai_hw_daemon` — Static `aarch64` hardware & gesture daemon
- `work/a17/out/BUILD-MANIFEST.json` & `work/a17/out/SHA256SUMS.txt`
- `ota/rmx1931.json` — Lightweight JSON OTA manifest tracked in git (no binary bloat in git)

---

## 3. Publishing an OTA Update to the Repo

Whenever you rebuild here and want devices running this ROM to receive an over-the-air update:

```bash
# Build + run all 9 verification gates + upload ZIP to GitHub Releases + commit & push ota/rmx1931.json:
bash android17/scripts/publish_ota.sh --build

# Or if work/a17/out/RMX1931-Android17-PixelOS-OTA.zip is already built & verified:
OTA_CHANGELOG="Updated security patch & hardware tuning" bash android17/scripts/publish_ota.sh
```
