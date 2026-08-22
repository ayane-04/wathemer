package com.wathemer.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.wathemer.app.hooks.ActionModeColors
import com.wathemer.app.hooks.BubbleAlbumClipping
import com.wathemer.app.hooks.BubbleColors
import com.wathemer.app.hooks.BubbleShapes
import com.wathemer.app.hooks.ChatHeaderColors
import com.wathemer.app.hooks.ComposeBarColors
import com.wathemer.app.hooks.EffectsOverlays
import com.wathemer.app.hooks.FontSwap
import com.wathemer.app.hooks.HomeActivityHook
import com.wathemer.app.hooks.QuoteAndLabelColors
import com.wathemer.app.hooks.SystemBars
import com.wathemer.app.hooks.ThemeHook
import com.wathemer.app.hooks.TickAndLinkColors
import com.wathemer.app.hooks.WaIds
import com.wathemer.app.hooks.dexkit.Deobfuscator
import com.wathemer.app.hooks.glass.GlassHook
import com.wathemer.app.hooks.wallpaper.WallpaperImage
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** Xposed entry point. Hooks install in handleLoadPackage so ThemeHook catches early-init color resolution. */
class XposedEntry : IXposedHookLoadPackage {

    companion object {
        const val TAG = "WaThemer"
        const val WHATSAPP_PKG = "com.whatsapp"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != WHATSAPP_PKG) return
        // No version here: handleLoadPackage has no Context; the onCreate hook below logs the real one.
        XposedBridge.log("[$TAG] Attached to ${lpparam.packageName}")

        // Deliberately no master gate: every feature is opt-in on its own prefs and no-ops when unset.
        // FontSwap installs first so its font registers before WA inflates any text.
        runCatching { FontSwap.install() }
            .onFailure { XposedBridge.log("[$TAG] FontSwap.install threw: $it") }
        // System bars: gated internally, no-op until enabled; mutually exclusive with the wallpaper feature.
        runCatching { SystemBars.install() }
            .onFailure { XposedBridge.log("[$TAG] SystemBars.install threw: $it") }
        ThemeHook.install(lpparam.classLoader)            // Layer 1: global substitution
        HomeActivityHook.install(lpparam.classLoader)     // Layer 2: per-element overrides + WDSBadge

        // Bubble theming needs an Application context (APK path for DexKit, Resources for ids); defer to onCreate.
        XposedHelpers.findAndHookMethod(
            Application::class.java, "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Application ?: return
                    if (app.packageName != WHATSAPP_PKG) return
                    // Log the host build once; every diagnosis starts with it. A diagnostic must never crash the module.
                    runCatching {
                        val pi = app.packageManager.getPackageInfo(app.packageName, 0)
                        XposedBridge.log("[$TAG] host ${app.packageName} ${pi.versionName} (${pi.longVersionCode})")
                    }
                    // Order here is not load-bearing; anything that must run after all installers must be posted, not placed last.
                    runCatching { WallpaperImage.install(lpparam.classLoader) }
                        .onFailure { XposedBridge.log("[$TAG] WallpaperImage.install threw: $it") }
                    runCatching { EffectsOverlays.install() }
                        .onFailure { XposedBridge.log("[$TAG] EffectsOverlays.install threw: $it") }
                    runCatching { BubbleColors.install(app, lpparam.classLoader) }
                        .onFailure { XposedBridge.log("[$TAG] BubbleColors.install threw: $it") }
                    runCatching { BubbleShapes.install(app, lpparam.classLoader) }
                        .onFailure { XposedBridge.log("[$TAG] BubbleShapes.install threw: $it") }
                    runCatching { BubbleAlbumClipping.install(app, lpparam.classLoader) }
                        .onFailure { XposedBridge.log("[$TAG] BubbleAlbumClipping.install threw: $it") }
                    runCatching { ComposeBarColors.install(app) }
                        .onFailure { XposedBridge.log("[$TAG] ComposeBarColors.install threw: $it") }
                    runCatching { ChatHeaderColors.install(app) }
                        .onFailure { XposedBridge.log("[$TAG] ChatHeaderColors.install threw: $it") }
                    runCatching { ActionModeColors.install(app) }
                        .onFailure { XposedBridge.log("[$TAG] ActionModeColors.install threw: $it") }
                    runCatching { QuoteAndLabelColors.install(app, lpparam.classLoader) }
                        .onFailure { XposedBridge.log("[$TAG] QuoteAndLabelColors.install threw: $it") }
                    runCatching { TickAndLinkColors.install(app, lpparam.classLoader) }
                        .onFailure { XposedBridge.log("[$TAG] TickAndLinkColors.install threw: $it") }
                    // Liquid Glass. Gated on its own pref inside install(); off by default.
                    runCatching { GlassHook.install(app) }
                        .onFailure { XposedBridge.log("[$TAG] GlassHook.install threw: $it") }
                    // WaIds anchor summary, first thing to check after a WhatsApp update.
                    // Must stay posted: inline it runs before HomeActivityHook and misses its ~40 ids.
                    Handler(Looper.getMainLooper()).post {
                        runCatching { WaIds.logSummary() }
                        // DexKit's native index would otherwise sit in memory for the process life; ensureBridge re-opens it.
                        runCatching { Deobfuscator.closeBridge() }
                    }
                }
            },
        )
    }
}
