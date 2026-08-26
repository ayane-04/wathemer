# WaThemer

An LSPosed module that lets you restyle WhatsApp: colours, chat bubbles, wallpaper, fonts, icons,
system bars, and a frosted glass look called Liquid Glass.

Nothing is switched on to begin with. With no settings saved, WhatsApp looks exactly as it would
without the module installed, so you can try one thing at a time and put it back if you don't like it.

## What you need

- Android 12 or newer
- An arm64 device
- LSPosed, with WhatsApp added to this module's scope
- Regular WhatsApp, not Business

## What you can change

**Colours.** Pick three colours (accent, background, text) and the whole app follows them. If you
want finer control, almost every part has its own override: chat list, search bar, tab bar, header,
floating button, bubbles, the compose bar, quoted replies, the selection toolbar, ticks, links and
the status bar. Leave an override alone and it keeps following your three main colours.

**Bubbles.** Separate colours for your messages and theirs, plus 56 bubble shapes. Most of them take
your colour; a few are fixed artwork.

**Wallpaper.** Use any picture as the background, with dim and blur if it turns out too busy behind
text.

**Liquid Glass.** A frosted, slightly refracting material on the bars, cards and buttons. It needs a
wallpaper to work, because what you see through the glass is the wallpaper.

**Extras.** A choice of bundled fonts or one of your own, an iOS-style icon set, and snowfall.

**Themes.** Save a look you like, send it to someone, or open one they sent you.

## Applying changes

WhatsApp reads your settings when it starts, so changes show up after it restarts. There is a
Restart WhatsApp button at the bottom of the settings app.

## Updates

The settings app can check this repository's releases for a newer build, at most once a day, and only
while you have the app open. WhatsApp itself never goes online. If there is a new version you can
download it from the Updates screen, and installing it hands the file to Android's own installer, so
nothing gets installed without you confirming it. You can turn the automatic check off.

## Screenshots

To be added.

## Building

```
./gradlew assembleRelease
```

Signing reads `WATHEMER_STORE_FILE`, `WATHEMER_STORE_PASSWORD`, `WATHEMER_KEY_ALIAS` and
`WATHEMER_KEY_PASSWORD` from Gradle properties. Without them you still get an APK, just an unsigned
one. R8 is switched off, because this code reflects into WhatsApp constantly and the keep rules for
all of it are not written.

## About the comments

There are a lot of comments in here, and that is on purpose.

WhatsApp's own class and field names are scrambled, and they move around with almost every release.
Very little of this code explains itself as a result. A line that looks pointless is usually holding
something up, and you can't tell which one by reading the code around it. So the comments say **why**
a thing is written the way it is, rather than what it does. Often the why is that the obvious
approach was tried first and quietly broke something else.

They are written for three people: me in six months, me if I put this down and come back to it later,
and you if you fork it. Without them, any of us would work the same things out again the slow way.

```kotlin
// getLocationOnScreen, never InWindow: those agree only within one window (the frost sampling bug).
// Never add ConstraintLayout to canStack: an unconstrained index-0 child breaks the Broadcast page's + FAB.
```

If a comment reads like a warning, treat it as one. It is phrased that way because something already
went wrong once.

One more thing worth knowing before you change anything: when a lookup into WhatsApp fails, the
feature that needed it switches itself off and writes a line to the log. It does not crash WhatsApp.
So if something stops working after a WhatsApp update, the log usually tells you which piece gave up.
Grep it for `WaThemer`.

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
