// Compose/input bar theming, five tokens plus the custom icon pack.
// SRC_IN on the existing background keeps WA's rounded pill; replacing the drawable loses the shape.
package com.wathemer.app.hooks

import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.wathemer.app.BuildConfig
import com.wathemer.app.hooks.dispatch.TextColorDispatcher
import com.wathemer.app.hooks.dispatch.ViewThemeDispatcher
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

private const val TAG = "WaThemer.ComposeBar"

object ComposeBarColors {

    private val xprefs: ModulePrefs.WtPrefs by lazy { ModulePrefs.open() }

    fun install(app: Application) {
        xprefs.reload()
        val barBg     = xprefs.getInt(Prefs.COMPOSE_BAR_BG, 0)
        val entryText = xprefs.getInt(Prefs.COMPOSE_ENTRY_TEXT, 0)
        val sendBg    = xprefs.getInt(Prefs.COMPOSE_SEND_BG, 0)
        val sendIcon  = xprefs.getInt(Prefs.COMPOSE_SEND_ICON, 0)
        val iconTint  = xprefs.getInt(Prefs.COMPOSE_ICON_TINT, 0)

        val customIcons = xprefs.getBoolean(Prefs.KEY_IOS_ICON_PACK, false)
        if (barBg == 0 && entryText == 0 && sendBg == 0 && sendIcon == 0 && iconTint == 0 && !customIcons) {
            XposedBridge.log("$TAG: no compose tokens set; skipping all hooks")
            HookLog.skip("install/ComposeBarColors", "no compose tokens set")
            return
        }

        val pkg = app.packageName
        val res = app.resources

        val inputLayoutId = if (barBg != 0) res.waId("input_layout", pkg) else 0
        val sendActionId  = if (sendBg != 0) res.waId("conversation_entry_action_button", pkg) else 0
        val sendContainerId = if (sendBg != 0) res.waId("send_container", pkg) else 0

        val barFilter  = if (barBg != 0) PorterDuffColorFilter(barBg, PorterDuff.Mode.SRC_IN) else null
        val sendFilter = if (sendBg != 0) PorterDuffColorFilter(sendBg, PorterDuff.Mode.SRC_IN) else null

        // ── Global setBackground hook: WA re-applies backgrounds on focus change, undoing our tint ──
        if (barFilter != null || sendFilter != null) {
            XposedHelpers.findAndHookMethod(
                View::class.java, "setBackground", Drawable::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val v = p.thisObject as? View ?: return
                        applyBgTint(v, inputLayoutId, barFilter, sendActionId, sendContainerId, sendFilter, res)
                    }
                },
            )
        }

        // ── Bar bg ── (attach-time + global setBackground hook above)
        if (barFilter != null && inputLayoutId != 0) {
            ViewThemeDispatcher.onId(inputLayoutId) { v ->
                if (isHomeActivitySearchBar(v, res)) return@onId
                val bg = v.background ?: return@onId
                bg.mutate()
                bg.colorFilter = barFilter
            }
            XposedBridge.log("$TAG: bar bg armed -> input_layout (id=0x${inputLayoutId.toString(16)})")
        }

        // ── Entry text (TextColorDispatcher catches WA's repaints) ──
        if (entryText != 0) {
            val entryId = res.waId("entry", pkg)
            if (entryId != 0) {
                TextColorDispatcher.mapColor(entryId, entryText)
                ViewThemeDispatcher.onId(entryId) { v ->
                    (v as? TextView)?.setTextColor(entryText)
                }
                XposedBridge.log("$TAG: entry text armed (id=0x${entryId.toString(16)})")
            }
        }

        // No entry-hint token: WA repaints the hint after attach, so it needs a hint-colour dispatcher.

        // ── Send button bg: two views, SRC_IN on the existing rounded drawable ──
        if (sendFilter != null) {
            for (id in listOf(sendActionId, sendContainerId)) {
                if (id == 0) continue
                ViewThemeDispatcher.onId(id) { v ->
                    val bg = v.background ?: return@onId
                    bg.mutate()
                    bg.colorFilter = sendFilter
                }
            }
            XposedBridge.log("$TAG: send bg armed (action=0x${sendActionId.toString(16)} container=0x${sendContainerId.toString(16)})")
        }

        // ── Mic/Send icon: the morphing icon inside the send button ──
        if (sendIcon != 0) {
            val csl = ColorStateList.valueOf(sendIcon)
            for (idName in SEND_ICON_IDS) {
                val id = res.waId(idName, pkg)
                if (id != 0) {
                    ViewThemeDispatcher.onId(id) { v ->
                        (v as? ImageView)?.imageTintList = csl
                    }
                    XposedBridge.log("$TAG: send icon armed -> $idName (id=0x${id.toString(16)})")
                }
            }
        }

        // ── Side-button icon tints (emoji/attach/camera/payment, not mic) ──
        if (iconTint != 0) {
            val csl = ColorStateList.valueOf(iconTint)
            for (idName in SIDE_ICON_IDS) {
                val id = res.waId(idName, pkg)
                if (id != 0) {
                    ViewThemeDispatcher.onId(id) { v ->
                        (v as? ImageView)?.imageTintList = csl
                    }
                    XposedBridge.log("$TAG: icon tint armed -> $idName (id=0x${id.toString(16)})")
                }
            }
        }

        // ── Custom entry-icon set: swaps input-bar glyphs for MBWA's iOS set; the tint tokens still apply ──
        if (customIcons) installCustomEntryIcons(app)
    }

    private var modRes: android.content.res.Resources? = null
    private val entryIconCache = HashMap<String, Drawable?>()

    // WA entry-button id -> MBWA glyph asset.
    private val ENTRY_ICON_MAP = listOf(
        "input_attach_button"       to "mb_ic_attach",
        "input_attach_button_start" to "mb_ic_attach",
        "camera_btn"                to "mb_ic_camera",
        "emoji_picker_btn"          to "mb_ic_emoji",
        "voice_note_btn"            to "mb_ic_mic",
        "payment_button"            to "mb_rupee",
        "send"                      to "mb_ic_send",
        "send_btn"                  to "mb_ic_send",
        "keyboard_btn"              to "mb_ic_keyboard",
        "voice_input_button"        to "mb_ic_mic",
    )

    private fun installCustomEntryIcons(app: Application) {
        val res = modRes ?: runCatching {
            app.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY).resources
        }.getOrNull()?.also { modRes = it }
        if (res == null) { XposedBridge.log("$TAG: custom entry icons; module res unavailable"); return }
        val waRes = app.resources; val pkg = app.packageName
        for ((idName, asset) in ENTRY_ICON_MAP) {
            val id = waRes.waId(idName, pkg)
            if (id == 0) continue
            val base = entryDrawable(res, asset) ?: continue
            ViewThemeDispatcher.onId(id) { v ->
                (v as? ImageView)?.setImageDrawable(base.constantState?.newDrawable()?.mutate() ?: base)
            }
        }
        XposedBridge.log("$TAG: custom entry icons armed")
    }

    private fun entryDrawable(res: android.content.res.Resources, name: String): Drawable? =
        entryIconCache.getOrPut(name) {
            val id = res.getIdentifier(name, "drawable", BuildConfig.APPLICATION_ID)
            if (id == 0) null else runCatching { res.getDrawable(id, null) }.getOrNull()
        }

    /** setBackground fan-out for every View; cheap because it only compares ids before doing anything. */
    private fun applyBgTint(
        v: View,
        inputLayoutId: Int, barFilter: PorterDuffColorFilter?,
        sendActionId: Int, sendContainerId: Int, sendFilter: PorterDuffColorFilter?,
        res: android.content.res.Resources,
    ) {
        val bg = v.background ?: return
        val id = v.id
        // mutate() before the filter: shared ConstantState would recolour every view sharing the drawable.
        when {
            barFilter != null && id == inputLayoutId && inputLayoutId != 0 -> {
                if (isHomeActivitySearchBar(v, res)) return
                bg.mutate()
                bg.colorFilter = barFilter
            }
            sendFilter != null && (id == sendActionId || id == sendContainerId) && id != 0 -> {
                bg.mutate()
                bg.colorFilter = sendFilter
            }
        }
    }

    /** input_layout is shared with the Home search bar; the search variant sits under search_input_layout. */
    private fun isHomeActivitySearchBar(v: View, res: android.content.res.Resources): Boolean {
        var node: View? = v.parent as? View
        var hops = 0
        while (node != null && hops < 6) {
            if (node.id != View.NO_ID && node.id != 0) {
                val name = runCatching { res.getResourceEntryName(node.id) }.getOrNull()
                if (name == "search_input_layout") return true
            }
            node = node.parent as? View
            hops++
        }
        return false
    }

    /** Side icons; mic and send live in [SEND_ICON_IDS]. */
    private val SIDE_ICON_IDS = arrayOf(
        "emoji_picker_btn",
        "input_attach_button",
        "input_attach_button_start",     // WDS-variant sibling
        "camera_btn",
        "payment_button",
    )

    /** Mic, send and dictation icons: the swap inside conversation_entry_action_button. */
    private val SEND_ICON_IDS = arrayOf(
        "voice_note_btn",        // mic icon (when entry is empty)
        "send",                  // send arrow (inflates when typing)
        "voice_input_button",    // dictation mic
        "voice_note_btn_slider", // long-press slide-to-cancel handle
    )
}
