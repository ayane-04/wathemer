// The emoji and sticker tray for non-glass users: its panel, its tab bar and its icons take the chosen colours.
// The tray repaints its own fill when it shows, so the panel colour is kept by a pre-draw check, not set once.
package com.wathemer.app.hooks

import android.app.Application
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import com.wathemer.app.hooks.dispatch.ViewThemeDispatcher
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XposedBridge

object TrayColors {

    private const val TAG = "WaThemer.TrayColors"

    private val xprefs: ModulePrefs.WtPrefs by lazy { ModulePrefs.open() }

    /** Unselected tabs sit at this alpha of the icon colour; the selected one is the colour itself. */
    private const val UNSELECTED_ALPHA = 0x99

    fun install(app: Application) {
        xprefs.reload()
        if (xprefs.getBoolean(Prefs.KEY_GLASS_ENABLED, false)) {
            XposedBridge.log("$TAG: Liquid Glass is on; tray colours stand down")
            HookLog.skip("install/TrayColors", "Liquid Glass owns the tray")
            return
        }
        val body = xprefs.getInt(Prefs.TRAY_BG, 0)
        val header = xprefs.getInt(Prefs.TRAY_HEADER_BG, 0)
        val icons = xprefs.getInt(Prefs.TRAY_ICON_TINT, 0)
        if ((body or header or icons) == 0) {
            HookLog.skip("install/TrayColors", "no tray tokens set")
            return
        }
        val res = app.resources
        val pkg = app.packageName

        if (body != 0) {
            // The tray view, the container WhatsApp parks it in, and the pager behind the pages.
            for (name in listOf("expressions_tray_view_id", "dynamic_expressions_tray_view_id", "expression_tray_container", "browser_content")) {
                val id = res.waId(name, pkg)
                if (id != 0) ViewThemeDispatcher.onId(id) { v -> keepColour(v, body, "TrayColors/body") }
            }
        }
        if (header != 0) {
            val id = res.waId("header_linear_layout", pkg)
            if (id != 0) ViewThemeDispatcher.onId(id) { v -> keepColour(v, header, "TrayColors/header") }
        }
        if (icons != 0) {
            val csl = ColorStateList.valueOf(icons)
            for (name in listOf("search_entry_icon", "close_button", "delete_symbol_tb")) {
                val id = res.waId(name, pkg)
                if (id != 0) ViewThemeDispatcher.onId(id) { v ->
                    (v as? ImageView)?.imageTintList = csl
                    HookLog.hit("TrayColors/icons")
                }
            }
            // The search button is a frame around an unnamed icon.
            val searchId = res.waId("search_button", pkg)
            if (searchId != 0) ViewThemeDispatcher.onId(searchId) { v ->
                val g = v as? ViewGroup ?: return@onId
                for (i in 0 until g.childCount) (g.getChildAt(i) as? ImageView)?.imageTintList = csl
                HookLog.hit("TrayColors/icons")
            }
            val tabTint = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(icons, (icons and 0x00FFFFFF) or (UNSELECTED_ALPHA shl 24)),
            )
            for (name in listOf("emojis", "rewrite", "gifs", "stickers")) {
                val id = res.waId(name, pkg)
                if (id != 0) ViewThemeDispatcher.onId(id) { v -> tintTab(v, tabTint) }
            }
        }
        XposedBridge.log("$TAG: armed body=%08x header=%08x icons=%08x".format(body, header, icons))
    }

    /** Paint now and on every frame the view is attached, since WhatsApp repaints the fill as the tray shows. */
    private fun keepColour(v: View, color: Int, ledger: String) {
        val apply = {
            val bg = v.background
            if (!(bg is ColorDrawable && bg.color == color)) {
                v.setBackgroundColor(color)
                HookLog.hit(ledger)
            }
        }
        apply()
        val vto = v.viewTreeObserver
        val pre = ViewTreeObserver.OnPreDrawListener { apply(); true }
        vto.addOnPreDrawListener(pre)
        // The observer dies with the detach, so the listener goes with it and the next attach registers afresh.
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                runCatching { vto.removeOnPreDrawListener(pre) }
                view.removeOnAttachStateChangeListener(this)
            }
        })
    }

    /** The tabs are Material buttons from WhatsApp's own copy of the library, reached by name. */
    private fun tintTab(v: View, tint: ColorStateList) {
        runCatching {
            v.javaClass.getMethod("setIconTint", ColorStateList::class.java).invoke(v, tint)
            HookLog.hit("TrayColors/tabs")
        }.onFailure { XposedBridge.log("$TAG: tab tint failed on ${v.javaClass.name}: $it") }
    }
}
