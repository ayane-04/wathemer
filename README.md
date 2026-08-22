# WaThemer

An LSPosed module that themes WhatsApp: colours, chat bubbles, wallpaper, fonts, icons, system bars,
and a Liquid Glass mode.

Nothing is applied until you switch it on. With no settings saved, WhatsApp renders exactly as it
does without the module.

## Requirements

- Android 12 or newer (`minSdk 31`)
- arm64 device
- LSPosed, with WhatsApp added to the module's scope
- WhatsApp (not Business)

Settings changes take effect when WhatsApp restarts. The settings app has a Restart WhatsApp button
at the bottom of every screen.

## What it does

**Colours.** Three global tokens (accent, background, text) substituted app-wide, plus per-element
overrides for the chat list, search bar, tab bar, header, FAB, bubbles, compose bar, quoted replies,
selection toolbar, ticks, links and the status bar. An override of 0 means "follow the global".

**Bubbles.** Per-side colours, and 56 bubble shapes drawn from nine-patch assets. The first 36 are
masks that take your bubble colour; the rest are fixed artwork.

**Wallpaper.** A picked image behind every screen, with dim and blur.

**Liquid Glass.** A refraction-and-blur material rendered in AGSL, applied to the chrome across the
app. It needs a wallpaper, because it transmits one.

**Extras.** Bundled fonts or your own font file, an iOS-style icon set, and a snowfall overlay.

**Themes.** Save your setup, share it as a `.wathemer` file, or apply one someone sent you.

## Screenshots

_To be added._

## Building

```
./gradlew assembleRelease
```

Signing credentials are read from Gradle properties (`WATHEMER_STORE_FILE`, `WATHEMER_STORE_PASSWORD`,
`WATHEMER_KEY_ALIAS`, `WATHEMER_KEY_PASSWORD`), normally in `~/.gradle/gradle.properties`. Without
them the build produces an unsigned APK rather than failing.

R8 is off. The module resolves a lot of WhatsApp internals reflectively and nobody has written the
keep rules for that yet.

## Working on the code

The comments are the documentation. If you want to continue this work, they are written for you.

Every comment is one line and says **why**, not what. The code already says what it does; the comment
says what will break if you change it, or which of two obvious approaches was the wrong one. Some
examples of the kind of thing they record:

```kotlin
// getLocationOnScreen, never InWindow: those agree only within one window (the frost sampling bug).
// Guard only the listener registration, never the paint: a repaint must be idempotent and must always run.
// Never add ConstraintLayout to canStack: an unconstrained index-0 child breaks the Broadcast page's + FAB.
```

A few things worth knowing before you edit:

- **A comment that reads like a warning is a warning.** Most of them exist because something
  specific went wrong. Lines that say "never", "do not" or "on purpose" are load-bearing.
- **WhatsApp's class and field names are obfuscated and change every release.** Nothing is hardcoded
  where it can be resolved structurally instead. When a lookup fails, the feature switches itself off
  and logs a line saying so, rather than crashing WhatsApp. Search the logs for `WaThemer`.
- **Resource ids are looked up by name at runtime** and every miss is logged once, so a WhatsApp
  rename shows up as a log line rather than as silent breakage.
- **Placement and rendering are separate.** `glass/` is the engine and knows nothing about WhatsApp.
  `hooks/glass/GlassHook.kt` decides where the glass goes and is the only file that knows about
  WhatsApp's layout.

Layout of the source:

```
app/src/main/kotlin/com/wathemer/app/
  glass/       the Liquid Glass engine: AGSL shaders, capture, drawables
  hooks/       everything that runs inside WhatsApp
    dexkit/    structural lookup of obfuscated classes and methods
    dispatch/  shared hooks, so features do not each install their own
    glass/     where glass surfaces go
    wallpaper/ wallpaper injection and the containers it has to clear
  settings/    the settings app (Compose)
  util/        small shared helpers
```

## Credits

- [WaEnhancer](https://github.com/Dev4Mod/WaEnhancer) - the colour-substitution approach, and its
  set of stock WhatsApp colour values, which this module's seed lists build on.
- [DexKit](https://github.com/LuckyPray/DexKit) - finds WhatsApp's obfuscated classes and methods by
  their structure instead of by name.
- [LSPosed](https://github.com/LSPosed/LSPosed) and the Xposed API - the framework this runs on.
- [uCrop](https://github.com/Yalantis/uCrop) - the wallpaper crop screen.
- [Phosphor Icons](https://github.com/phosphor-icons/homepage) (MIT) - most of the bundled vector
  icons.
- Bundled fonts, all under the SIL Open Font License 1.1: Arvo, Inter, Lato, Lora, Nunito, Poppins,
  Rubik.
- Jetpack Compose, Material Components, and FlatBuffers.

Full licence texts for the bundled artwork and fonts ship inside the APK at
`res/raw/open_source_licences.txt`. Anything added to `res/font/` or `res/drawable/` from a third
party must be added there in the same commit.

## Licence

GPL-3.0. See [LICENSE](LICENSE).

WaThemer is not affiliated with or endorsed by WhatsApp or Meta.
