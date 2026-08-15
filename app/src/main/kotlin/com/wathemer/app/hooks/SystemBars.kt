// Status-bar theming: A15 kills the legacy colour setters, so we draw our own inset-height strip
// and set icon contrast via WindowInsetsController. No nav theming; the wallpaper wins the bars.
package com.wathemer.app.hooks

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import com.wathemer.app.BuildConfig
import com.wathemer.app.hooks.wallpaper.WallpaperImage
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object SystemBars {

    private const val TAG = "WaThemer.SystemBars"
    private const val LOGTAG = "WaThemerBars"
    private const val WHATSAPP_PKG = "com.whatsapp"
    private const val STATUS_TAG = "wt_statusbar"

    // Immersive viewers where a themed strip would look wrong; kept short on purpose.
    private val IMMERSIVE_DENYLIST = listOf(
        "mediaview", "mediaalbum", "voip", "statusplayback", "watchandbrowse", "cameraactivity",
    )

    private val xprefs: XSharedPreferences by lazy {
        XSharedPreferences(BuildConfig.APPLICATION_ID, Prefs.FILE).apply { makeWorldReadable(); reload() }
    }

    fun install() {
        // Hook unconditionally; the gates live in the callbacks so toggling applies on the next Activity create.
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "onPostCreate", Bundle::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val a = p.thisObject as? Activity ?: return
                    if (!shouldTheme(a)) return
                    runCatching { apply(a, paintStrips = true) }
                        .onFailure { Log.w(LOGTAG, "apply(onPostCreate ${a.javaClass.simpleName}) failed: ${it.message}") }
                }
            },
        )
        // Re-apply after WA's own onResume appearance writes; cheap.
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val a = p.thisObject as? Activity ?: return
                    if (!shouldTheme(a)) return
                    runCatching { apply(a, paintStrips = true) }.onFailure { /* silent */ }
                }
            },
        )
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "onWindowFocusChanged", Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (p.args.getOrNull(0) as? Boolean != true) return   // only on gaining focus
                    val a = p.thisObject as? Activity ?: return
                    if (!shouldTheme(a)) return
                    runCatching { applyIcons(a, resolveStatusColor()) }.onFailure { /* silent */ }
                }
            },
        )
        Log.i(LOGTAG, "installed (Activity onPostCreate/onResume/onWindowFocusChanged)")
        XposedBridge.log("[$TAG] installed")
    }

    /** Checks the pref, not just the active flag, which is still false on the first Activity (hook-order race). */
    private fun wallpaperOwnsBars(): Boolean {
        if (WallpaperImage.INSTANCE?.isWallpaperActive() == true) return true
        if (!xprefs.getBoolean(Prefs.KEY_WALLPAPER_ENABLED, false)) return false
        return !xprefs.getString(Prefs.KEY_WALLPAPER_PATH, null).isNullOrEmpty()
    }

    /** Remove a strip painted before the wallpaper existed; only views tagged [STATUS_TAG] are touched. */
    private fun removeStrips(a: Activity) {
        val parent = contentParent(a) ?: return
        parent.findViewWithTag<View?>(STATUS_TAG)?.let {
            parent.removeView(it)
            Log.i(LOGTAG, "removed $STATUS_TAG; wallpaper owns the bars")
        }
    }

    /** Gate: package match + master toggle + not immersive + wallpaper inactive. Reloads prefs. */
    private fun shouldTheme(a: Activity): Boolean {
        if (a.packageName != WHATSAPP_PKG) return false
        xprefs.reload()
        // Wallpaper check runs before the master toggle so a leftover strip is cleaned up either way.
        if (wallpaperOwnsBars()) { runCatching { removeStrips(a) }; return false }
        if (!xprefs.getBoolean(Prefs.KEY_SYSTEM_BARS_ENABLED, false)) return false
        val name = a.javaClass.simpleName.lowercase()
        if (IMMERSIVE_DENYLIST.any { name.contains(it) }) return false
        return true
    }

    private fun apply(a: Activity, paintStrips: Boolean) {
        val statusColor = resolveStatusColor()
        if (paintStrips) {
            runCatching { paintStatusStrip(a, statusColor) }.onFailure { Log.w(LOGTAG, "status strip: ${it.message}") }
        }
        applyIcons(a, statusColor)
    }

    // ── Colour resolution (0 override = cascade) ───────────────────────────
    // status -> explicit -> home toolbar bg -> accent (a visible "themed header" by default).
    private fun resolveStatusColor(): Int {
        val o = xprefs.getInt(Prefs.OVR_STATUS_BAR_BG, 0); if (o != 0) return o
        val tb = xprefs.getInt(Prefs.OVR_TOOLBAR_BG, 0); if (tb != 0) return tb
        return xprefs.getInt(Prefs.KEY_PRIMARY, Prefs.DEFAULT_PRIMARY)
    }

    // ── Status strip: our own inset-height View ────────────────────────────
    private fun paintStatusStrip(a: Activity, color: Int) {
        val parent = contentParent(a) ?: return
        val h = statusInsetHeight(a)
        if (h <= 0) return
        val existing = parent.findViewWithTag<View?>(STATUS_TAG)
        val strip = existing ?: View(a).apply {
            tag = STATUS_TAG
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h, Gravity.TOP)
        }
        (strip.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.height != h || it.gravity != Gravity.TOP) { it.height = h; it.gravity = Gravity.TOP; strip.layoutParams = it }
        }
        strip.setBackgroundColor(color)
        if (existing == null) parent.addView(strip)
        strip.bringToFront()   // draw over the (transparent, fitsSystemWindows-padded) status gap
    }

    // ── Icons (light/dark): WindowInsetsController, the live A15 API ───────
    private fun applyIcons(a: Activity, statusColor: Int) {
        if (!xprefs.getBoolean(Prefs.KEY_SYSTEM_BAR_AUTO_ICONS, true)) return
        val ctrl: WindowInsetsController = a.window.insetsController ?: return
        // LIGHT_STATUS_BARS means light bg, dark icons. Mask only that bit so the nav bar stays untouched.
        ctrl.setSystemBarsAppearance(
            if (luminance(statusColor) > 0.5f) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────
    /** The FrameLayout between decor and content: the same slot WallpaperImage injects into. */
    private fun contentParent(a: Activity): ViewGroup? {
        val content = a.window.decorView.findViewById<View>(android.R.id.content) ?: return null
        return content.parent as? ViewGroup
    }

    private fun statusInsetHeight(a: Activity): Int {
        a.window.decorView.rootWindowInsets?.let {
            val top = it.getInsets(WindowInsets.Type.statusBars()).top
            if (top > 0) return top
        }
        val id = a.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) a.resources.getDimensionPixelSize(id) else 0
    }

    /** Rec.709 relative luminance (matches Components.luminance); 0..1. */
    private fun luminance(argb: Int): Float {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}
