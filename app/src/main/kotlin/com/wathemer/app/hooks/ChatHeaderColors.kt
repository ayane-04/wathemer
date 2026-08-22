// Conversation header title/subtitle theming; bar bg and icons share the toolbar tokens via HomeActivityHook.
// Do not use WDSToolbar's set(Sub)TitleTextColor: gated on a private field, it can silently no-op.
package com.wathemer.app.hooks

import android.app.Application
import android.widget.TextView
import com.wathemer.app.BuildConfig
import com.wathemer.app.hooks.dispatch.TextColorDispatcher
import com.wathemer.app.hooks.dispatch.ViewThemeDispatcher
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge

private const val TAG = "WaThemer.ChatHeader"

object ChatHeaderColors {

    private val xprefs: XSharedPreferences by lazy {
        XSharedPreferences(BuildConfig.APPLICATION_ID, Prefs.FILE).apply {
            makeWorldReadable()
            reload()
        }
    }

    fun install(app: Application) {
        xprefs.reload()
        val title    = xprefs.getInt(Prefs.CHAT_HEADER_TITLE, 0)
        val subtitle = xprefs.getInt(Prefs.CHAT_HEADER_SUBTITLE, 0)

        if (title == 0 && subtitle == 0) {
            XposedBridge.log("$TAG: no header text tokens set; skipping")
            return
        }

        val pkg = app.packageName
        val res = app.resources

        if (title != 0) {
            val id = res.waId("conversation_contact_name", pkg)
            if (id != 0) {
                TextColorDispatcher.mapColor(id, title)
                ViewThemeDispatcher.onId(id) { v -> (v as? TextView)?.setTextColor(title) }
                XposedBridge.log("$TAG: title armed -> conversation_contact_name (id=0x${id.toString(16)})")
            }
        }

        if (subtitle != 0) {
            val id = res.waId("conversation_contact_status", pkg)
            if (id != 0) {
                TextColorDispatcher.mapColor(id, subtitle)
                ViewThemeDispatcher.onId(id) { v -> (v as? TextView)?.setTextColor(subtitle) }
                XposedBridge.log("$TAG: subtitle armed -> conversation_contact_status (id=0x${id.toString(16)})")
            }
        }
    }
}
