# Messages (`IosMessages.apk` — `com.realme.samurai.messages`)

Android 17 (`SDK 35`) iOS 26 Liquid Glass SMS/MMS client for Realme X2 Pro (`RMX1931`) PixelOS `v1.6.0`.

- **Package**: `com.realme.samurai.messages`
- **Label**: `Messages`
- **Version**: `1.6.0-ios26` (`versionCode = 17060`)
- **System Sync**: Live `Settings.Global` observer synced with `iOS Styler` (`samurai_liquid_glass_mode`, `samurai_ios26_refraction_pct`, `samurai_ios26_frost_pct`).

## Build

Run in WSL so `.gradle/` and `build/` stay in `/var/tmp/rmx1931-a17/work/ios-messages-build` and `android17/ios-messages/` stays 100% clean:

```bash
bash android17/ios-messages/build_ios_messages.sh
```

Signed release output: `work/a17/ios-messages/IosMessages.apk`.

## Open-Source Attribution

Adapted from [`abtin-f/liquid-messages`](https://github.com/abtin-f/liquid-messages) `v1.8.2` under the [MIT License](LICENSE) (`Copyright (c) 2026 abtin-f`).
Bundled Inter and Vazirmatn fonts are licensed under the SIL Open Font License 1.1 (`licenses/`).
