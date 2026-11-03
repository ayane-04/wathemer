# WaThemer

LSPosed module for theming WhatsApp and WhatsApp Business.

## Requirements

- Android 12+, arm64
- Root, with Vector 2.1+ or LSPosed 2.x (libxposed API 101)
- WhatsApp and/or WhatsApp Business in the module scope

## Features

- Colours: accent, background and text, plus per-element overrides
- Bubbles: sent and received colours, 56 styles
- Ticks: 89 styles, custom colours
- Wallpaper and per-chat wallpaper, with dim and blur
- Liquid Glass (needs a wallpaper)
- Fonts: bundled or your own
- iOS style icons
- Snowfall
- Themes: save, share, import
- Theme store

## Usage

Restart WhatsApp after any change. Restart WhatsApp is in the app's top menu.

## Theme store

To submit a theme, export it and send it to the [WaThemer Chat](https://t.me/wathemer_chat) group on Telegram.

## Updates

Auto update checks once a day, can be turned off.

## Screenshots

To be added.

## Building

```
./gradlew assembleRelease
```

Signing reads `WATHEMER_STORE_FILE`, `WATHEMER_STORE_PASSWORD`, `WATHEMER_KEY_ALIAS` and `WATHEMER_KEY_PASSWORD`
from Gradle properties. Without them the APK is unsigned.

Logs are tagged `WaThemer`.

## Comments

Comments are there so that if anyone wants to fork and build, they know what the code does.

## Credits

- [WaEnhancer](https://github.com/Dev4Mod/WaEnhancer): colour substitution approach and stock colour list
- [DexKit](https://github.com/LuckyPray/DexKit)
- [LSPosed](https://github.com/LSPosed/LSPosed) and the Xposed API
- [uCrop](https://github.com/Yalantis/uCrop)
- [Phosphor Icons](https://github.com/phosphor-icons/homepage) (MIT)
- Fonts: Arvo, Inter, Lato, Lora, Nunito, Poppins, Rubik (OFL 1.1)

Licence texts are in the APK at `res/raw/open_source_licences.txt`.

## Licence

GPL-3.0. See [LICENSE](LICENSE).
