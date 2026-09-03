package com.wathemer.app

import android.os.Handler
import android.os.Looper
import com.wathemer.app.hooks.ActionModeColors
import com.wathemer.app.hooks.BubbleAlbumClipping
import com.wathemer.app.hooks.BubbleColors
import com.wathemer.app.hooks.BubbleShapes
import com.wathemer.app.hooks.ChatHeaderColors
import com.wathemer.app.hooks.HookLog
import com.wathemer.app.hooks.ComposeBarColors
import com.wathemer.app.hooks.EffectsOverlays
import com.wathemer.app.hooks.FontSwap
import com.wathemer.app.hooks.HomeActivityHook
import com.wathemer.app.hooks.HostAppInit
import com.wathemer.app.hooks.ModulePrefs
import com.wathemer.app.hooks.QuoteAndLabelColors
import com.wathemer.app.hooks.SystemBars
import com.wathemer.app.hooks.ThemeHook
import com.wathemer.app.hooks.TickAndLinkColors
import com.wathemer.app.hooks.WaIds
import com.wathemer.app.hooks.dexkit.Deobfuscator
import com.wathemer.app.hooks.dispatch.ViewThemeDispatcher
import com.wathemer.app.hooks.glass.GlassHook
import com.wathemer.app.hooks.wallpaper.WallpaperImage
import de.robv.android.xposed.XposedBridge
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/** Modern entry point. Targets api 101 on purpose: the hooks below it still speak the legacy API, which 102 forbids. */
class ModernEntry : XposedModule() {

    companion object {
        const val TAG = "WaThemer"
        const val WHATSAPP_PKG = "com.whatsapp"
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != WHATSAPP_PKG) return
        val classLoader = param.defaultClassLoader
        // No version here: nothing has a Context yet; the app-create block below logs the real one.
        XposedBridge.log("[$TAG] Attached to ${param.packageName} (api ${runCatching { frameworkName }.getOrDefault("?")})")
        // First, unconditionally: every installer below reads its prefs through this.
        ModulePrefs.attach(this)
        // Must precede every HostAppInit.onCreate call; it is what those registrations attach to.
        try { HostAppInit.resolve(param.applicationInfo.className, classLoader) }
        catch (t: Throwable) { HookLog.fail("anchor/appCreate", t) }

        // Deliberately no master gate: every feature is opt-in on its own prefs and no-ops when unset.
        // FontSwap installs first so its font registers before WA inflates any text.
        try { FontSwap.install(); HookLog.arm("install/FontSwap") }
        catch (t: Throwable) { HookLog.fail("install/FontSwap", t) }
        // System bars: gated internally, no-op until enabled; mutually exclusive with the wallpaper feature.
        try { SystemBars.install(); HookLog.arm("install/SystemBars") }
        catch (t: Throwable) { HookLog.fail("install/SystemBars", t) }
        try { ThemeHook.install(classLoader); HookLog.arm("install/ThemeHook") }
        catch (t: Throwable) { HookLog.fail("install/ThemeHook", t) }
        try { HomeActivityHook.install(classLoader); HookLog.arm("install/HomeActivityHook") }
        catch (t: Throwable) { HookLog.fail("install/HomeActivityHook", t) }

        // Bubble theming needs an Application context (APK path for DexKit, Resources for ids); defer to app create.
        HostAppInit.onCreate(HostAppInit.ORDER_MAIN, "main") { app ->
            // Log the host build once; every diagnosis starts with it. A diagnostic must never crash the module.
            runCatching {
                val pi = app.packageManager.getPackageInfo(app.packageName, 0)
                XposedBridge.log("[$TAG] host ${app.packageName} ${pi.versionName} (${pi.longVersionCode})")
            }
            // Order here is not load-bearing; anything that must run after all installers must be posted, not placed last.
            try { WallpaperImage.install(classLoader); HookLog.arm("install/WallpaperImage") }
            catch (t: Throwable) { HookLog.fail("install/WallpaperImage", t) }
            try { EffectsOverlays.install(); HookLog.arm("install/EffectsOverlays") }
            catch (t: Throwable) { HookLog.fail("install/EffectsOverlays", t) }
            try { BubbleColors.install(app, classLoader); HookLog.arm("install/BubbleColors") }
            catch (t: Throwable) { HookLog.fail("install/BubbleColors", t) }
            try { BubbleShapes.install(app, classLoader); HookLog.arm("install/BubbleShapes") }
            catch (t: Throwable) { HookLog.fail("install/BubbleShapes", t) }
            try { BubbleAlbumClipping.install(app, classLoader); HookLog.arm("install/BubbleAlbumClipping") }
            catch (t: Throwable) { HookLog.fail("install/BubbleAlbumClipping", t) }
            try { ComposeBarColors.install(app); HookLog.arm("install/ComposeBarColors") }
            catch (t: Throwable) { HookLog.fail("install/ComposeBarColors", t) }
            try { ChatHeaderColors.install(app); HookLog.arm("install/ChatHeaderColors") }
            catch (t: Throwable) { HookLog.fail("install/ChatHeaderColors", t) }
            try { ActionModeColors.install(app); HookLog.arm("install/ActionModeColors") }
            catch (t: Throwable) { HookLog.fail("install/ActionModeColors", t) }
            try { QuoteAndLabelColors.install(app, classLoader); HookLog.arm("install/QuoteAndLabelColors") }
            catch (t: Throwable) { HookLog.fail("install/QuoteAndLabelColors", t) }
            try { TickAndLinkColors.install(app, classLoader); HookLog.arm("install/TickAndLinkColors") }
            catch (t: Throwable) { HookLog.fail("install/TickAndLinkColors", t) }
            // Liquid Glass. Gated on its own pref inside install(); off by default.
            try { GlassHook.install(app); HookLog.arm("install/GlassHook") }
            catch (t: Throwable) { HookLog.fail("install/GlassHook", t) }
            // WaIds anchor summary, first thing to check after a WhatsApp update.
            // Must stay posted: inline it runs before HomeActivityHook and misses its ~40 ids.
            Handler(Looper.getMainLooper()).post {
                runCatching { WaIds.logSummary() }
                // DexKit's native index would otherwise sit in memory for the process life; ensureBridge re-opens it.
                runCatching { Deobfuscator.closeBridge() }
                runCatching { HookLog.dump("install complete") }
            }
        }

        // Surfaces attach long after install, so the ledger is dumped again once the first screens
        // have had time to build. Scheduled here so a dead anchor cannot also silence the ledger.
        runCatching {
            val main = Handler(Looper.getMainLooper())
            for (delay in longArrayOf(10_000L, 45_000L)) {
                main.postDelayed({
                    runCatching { HookLog.dump("+${delay / 1000}s") }
                    runCatching { ViewThemeDispatcher.report() }
                }, delay)
            }
        }
    }
}
