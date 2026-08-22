# WaThemer

An LSPosed module for theming WhatsApp: colours, chat bubbles and shapes, wallpaper, fonts, icons,
system bars, and a Liquid Glass mode. Everything is off until you turn it on.

Needs Android 12 or newer, arm64, and LSPosed with WhatsApp in the module's scope. Changes apply
when WhatsApp restarts, and there is a button for that at the bottom of the settings app.

## Screenshots

To be added.

## Building

```
./gradlew assembleRelease
```

Signing reads `WATHEMER_STORE_FILE`, `WATHEMER_STORE_PASSWORD`, `WATHEMER_KEY_ALIAS` and
`WATHEMER_KEY_PASSWORD` from Gradle properties; without them you get an unsigned APK. R8 is off,
because the keep rules for all the reflection are not written.

## Comments

The comments are the documentation. Each is one line and says why the code is the way it is, which
is usually that the obvious alternative was tried and broke something:

```kotlin
// getLocationOnScreen, never InWindow: those agree only within one window (the frost sampling bug).
// Never add ConstraintLayout to canStack: an unconstrained index-0 child breaks the Broadcast page's + FAB.
```

Take the ones phrased as warnings literally. WhatsApp's class and field names are obfuscated and
move every release, so lookups are structural, and one that fails switches its feature off and logs
a line instead of crashing WhatsApp. Grep the log for `WaThemer`.

## Credits

- [WaEnhancer](https://github.com/Dev4Mod/WaEnhancer), for the colour-substitution approach and its
  list of stock WhatsApp colours
- [DexKit](https://github.com/LuckyPray/DexKit), for resolving obfuscated classes by structure
- [LSPosed](https://github.com/LSPosed/LSPosed) and the Xposed API
- [uCrop](https://github.com/Yalantis/uCrop), for the wallpaper crop screen
- [Phosphor Icons](https://github.com/phosphor-icons/homepage) (MIT), for most of the vector icons
- Arvo, Inter, Lato, Lora, Nunito, Poppins and Rubik, all under OFL 1.1

Full licence texts ship in the APK at `res/raw/open_source_licences.txt`.

## Licence

GPL-3.0. See [LICENSE](LICENSE).
