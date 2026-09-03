// Rounded outer corners for grouped album images, via a constructor hook on the album grid class.
// Foreground kills live in BubbleColors.installForegroundKill; do not add a competing hook here.
package com.wathemer.app.hooks

import android.app.Application
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

private const val TAG = "WaThemer.AlbumClip"
private const val ALBUM_CLASS = "com.whatsapp.conversationrow.album.ConversationRowImageAndVideoAlbumGridFrame"

object BubbleAlbumClipping {

    private val xprefs: ModulePrefs.WtPrefs by lazy { ModulePrefs.open() }

    fun install(app: Application, classLoader: ClassLoader) {
        xprefs.reload()
        // Gate on the bubble bg colour: no point clipping if the user has not themed bubbles.
        val active = xprefs.getInt(Prefs.BUBBLE_LEFT_BG, 0) != 0 ||
                     xprefs.getInt(Prefs.BUBBLE_RIGHT_BG, 0) != 0
        if (!active) {
            // Every pref-gated installer logs when it stands down, or the log cannot explain its absence.
            XposedBridge.log("$TAG: no bubble background colour set; album clipping not installed")
            HookLog.skip("install/BubbleAlbumClipping", "no bubble background colour set")
            return
        }

        val cls = runCatching { classLoader.loadClass(ALBUM_CLASS) }
            .onFailure { XposedBridge.log("$TAG: $ALBUM_CLASS not found: ${it.message}") }
            .getOrNull() ?: return

        val density = app.resources.displayMetrics.density
        val radiusPx = 12f * density

        val provider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                if (v.width <= 0 || v.height <= 0) return
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }

        XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val view = p.thisObject as? ViewGroup ?: return
                view.clipToOutline = true
                view.outlineProvider = provider
            }
        })
        XposedBridge.log("$TAG: hooked $ALBUM_CLASS ctors (radius=${radiusPx}px)")
    }
}
