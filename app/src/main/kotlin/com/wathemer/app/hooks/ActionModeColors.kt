// Long-press action-mode toolbar theming; Home and Conversation share action_mode_bar by design.
// The bar is ActionBarContextView, not WDSToolbar, so plain background sets and a child walk are safe.
package com.wathemer.app.hooks

import android.app.Application
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.wathemer.app.BuildConfig
import com.wathemer.app.hooks.dispatch.TextColorDispatcher
import com.wathemer.app.hooks.dispatch.ViewThemeDispatcher
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import java.util.WeakHashMap

private const val TAG = "WaThemer.ActionMode"

object ActionModeColors {

    private val xprefs: XSharedPreferences by lazy {
        XSharedPreferences(BuildConfig.APPLICATION_ID, Prefs.FILE).apply {
            makeWorldReadable()
            reload()
        }
    }

    fun install(app: Application) {
        xprefs.reload()
        val bg          = xprefs.getInt(Prefs.OVR_ACTION_MODE_BG, 0)
        val icons       = xprefs.getInt(Prefs.OVR_ACTION_MODE_ICONS, 0)
        val title       = xprefs.getInt(Prefs.OVR_ACTION_MODE_TITLE, 0)
        val closeRipple = xprefs.getInt(Prefs.OVR_ACTION_MODE_CLOSE_RIPPLE, 0)

        if (bg == 0 && icons == 0 && title == 0 && closeRipple == 0) {
            XposedBridge.log("$TAG: no action-mode tokens set; skipping")
            return
        }

        val pkg = app.packageName
        val res = app.resources

        // ── Bar bg + child walk for icons + close ripple ──
        val barId = res.waId("action_mode_bar", pkg)
        if (barId != 0 && (bg != 0 || icons != 0 || closeRipple != 0)) {
            val closeBtnId = res.waId("action_mode_close_button", pkg)
            ViewThemeDispatcher.onId(barId) { v ->
                if (bg != 0) v.background = ColorDrawable(bg)
                if (icons != 0 || closeRipple != 0) {
                    walkAndApply(v, icons, closeRipple, closeBtnId)
                    v.post { walkAndApply(v, icons, closeRipple, closeBtnId) }
                    // Attach fires once, but each later selection rebuilds the item row untinted, so watch layouts too.
                    watchActionModeBar(v, icons, closeRipple, closeBtnId)
                }
            }
            XposedBridge.log("$TAG: bar armed (id=0x${barId.toString(16)})")
        }

        // ── Title (count text) ──
        if (title != 0) {
            val id = res.waId("action_bar_title", pkg)
            if (id != 0) {
                TextColorDispatcher.mapColor(id, title)
                ViewThemeDispatcher.onId(id) { v -> (v as? TextView)?.setTextColor(title) }
                XposedBridge.log("$TAG: title armed (id=0x${id.toString(16)})")
            }
        }

        // No menu-text token: those labels only appear in the overflow popup this hook never reaches.
    }

    /** Last selection state we acted on, per window root, so the walk only runs on a change. */
    private val barState = WeakHashMap<View, Boolean>()

    /** setTag throws unless the key's top byte is >= 2, so force it rather than trust hashCode. */
    private val barWatchTag = ("wathemer-action-mode-watch".hashCode() and 0x00FFFFFF) or 0x7F000000

    /** One watcher per window; the walk runs only on the hidden-to-shown edge because layouts fire constantly. */
    private fun watchActionModeBar(bar: View, icons: Int, closeRipple: Int, closeBtnId: Int) {
        val root = bar.rootView as? ViewGroup ?: return
        if (root.getTag(barWatchTag) != null) return
        root.setTag(barWatchTag, true)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            runCatching {
                val active = bar.isShown && bar.height > 0
                if (barState[root] == active) return@runCatching
                barState[root] = active
                if (!active) return@runCatching
                // Twice: the item row can still be empty on the first layout; the walk is idempotent.
                walkAndApply(bar, icons, closeRipple, closeBtnId)
                bar.post { walkAndApply(bar, icons, closeRipple, closeBtnId) }
            }
        }
    }

    /** Tint icon views; for the close button swap the ripple's check-state colour, keeping the stock press tint. */
    private fun walkAndApply(view: View, icons: Int, closeRipple: Int, closeBtnId: Int) {
        if (icons != 0) {
            val tintList = ColorStateList.valueOf(icons)
            val filter = PorterDuffColorFilter(icons, PorterDuff.Mode.SRC_IN)
            when {
                view is ImageButton -> {
                    view.imageTintList = tintList
                    view.imageTintMode = PorterDuff.Mode.SRC_IN
                    view.drawable?.mutate()?.colorFilter = filter
                }
                view is ImageView -> {
                    view.imageTintList = tintList
                    view.imageTintMode = PorterDuff.Mode.SRC_IN
                    view.drawable?.mutate()?.colorFilter = filter
                }
                view.javaClass.simpleName.endsWith("ActionMenuItemView") -> {
                    // Has compound drawables for the icon glyph.
                    (view as? TextView)?.let { tv ->
                        tv.compoundDrawableTintList = tintList
                        tv.compoundDrawableTintMode = PorterDuff.Mode.SRC_IN
                        tv.compoundDrawables.forEach { it?.mutate()?.colorFilter = filter }
                    }
                }
            }
        }
        if (closeRipple != 0 && view.id == closeBtnId && closeBtnId != 0) {
            // Fresh stateful ripple: user's colour on state_checked, stock #33ffffff for the default press.
            val rippleCsl = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(),
                ),
                intArrayOf(closeRipple, 0x33FFFFFF.toInt()),
            )
            view.background = RippleDrawable(rippleCsl, null, null)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walkAndApply(view.getChildAt(i), icons, closeRipple, closeBtnId)
            }
        }
    }
}
