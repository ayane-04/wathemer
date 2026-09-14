// WaEnhancer's own widgets on the glass, found by the tags and classes it gives them and dressed like the surfaces they sit on.
package com.wathemer.app.hooks.glass

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.wathemer.app.glass.GlassView
import com.wathemer.app.hooks.HookLog
import com.wathemer.app.hooks.dexkit.Deobfuscator
import com.wathemer.app.hooks.waId
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** The tag WaEnhancer puts on the container wrapping WhatsApp's filter row with its own Chats/Groups pill. */
internal const val WAE_FILTERS_TAG = "wae_filters"

private val waeFilterRowTag = tagKey("wathemer-wae-filter-row")

/** WaEnhancer's Chats/Groups pill loses its green fill and strokes for the chips' frost, the chosen half lifted. */
internal fun dressWaeFilterRow(container: ViewGroup) {
    if (container.getTag(waeFilterRowTag) != null) return
    container.setTag(waeFilterRowTag, true)
    val fix = Runnable { runCatching { dressWaeFilterRowNow(container) } }
    fix.run()
    // WaEnhancer rebuilds both halves' drawables on every tap; pre-draw catches that before the frame.
    container.viewTreeObserver.addOnPreDrawListener {
        if (container.isAttachedToWindow) fix.run()
        true
    }
    HookLog.hit("compat/filterGroups")
}

private fun dressWaeFilterRowNow(container: ViewGroup) {
    val pill = container.getChildAt(0) as? ViewGroup ?: return
    val halves = pill.getChildAt(0) as? ViewGroup ?: return
    if (pill.width <= 0 || pill.height <= 0) return
    // The pill itself: the chips' tint-only frost and hairline rim; the card beneath supplies the blur.
    frost(pill, ignorePadding = true)
    val r = pill.height / 2f
    val inset = pill.dp(1f).toInt()
    for (i in 0 until halves.childCount) {
        val half = halves.getChildAt(i) as? TextView ?: continue
        // WaEnhancer's half is a two-layer drawable, stroke under fill; a coloured fill marks the chosen one.
        val theirs = half.background as? LayerDrawable ?: continue
        if (theirs.numberOfLayers != 2) continue
        val chosen = (theirs.getDrawable(1) as? ShapeDrawable)?.paint?.color
            ?.let { Color.alpha(it) != 0 } ?: false
        // Rounded on the outer side only, as theirs is, so the two halves still read as one pill.
        val radii = if (i == 0) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        val fill = ShapeDrawable(RoundRectShape(radii, null, null)).apply {
            paint.color = if (chosen) glassTint(CHIP_ALPHA_SELECTED) else Color.TRANSPARENT
        }
        half.background = InsetDrawable(fill, inset)
    }
}

/** WaEnhancer's IGStatus strip above the chat list; by class, it carries no id. */
internal fun waeStatusStrip(listParent: ViewGroup): View? {
    for (i in 0 until listParent.childCount) {
        val c = listParent.getChildAt(i) ?: continue
        if (c.javaClass.name.startsWith("com.wmods.wppenhacer.views.IGStatusView")) return c
    }
    return null
}

/** Seated inside the card with the rows' inset and the card's gap, no fill of its own; writes on change only. */
internal fun seatWaeStatusStrip(strip: View, sideMargin: Int, topMargin: Int) {
    clearBg(strip, "wae status strip")
    val lp = strip.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    if (lp.leftMargin != sideMargin || lp.rightMargin != sideMargin || lp.topMargin != topMargin) {
        lp.leftMargin = sideMargin
        lp.rightMargin = sideMargin
        lp.topMargin = topMargin
        strip.layoutParams = lp
    }
    if (!HookLog.isHit("compat/igStatus")) HookLog.hit("compat/igStatus")
}

private val waeToolbarTextTag = tagKey("wathemer-wae-toolbar-text")

/** The bio keeps the name's colour at this alpha, a quieter second line rather than a second title. */
private const val WAE_BIO_ALPHA = 0xB3

/** WaEnhancer's name and bio: id-less TextViews in a bare LinearLayout it adds to the home toolbar. */
internal fun isWaeToolbarText(v: View, toolbarId: Int): Boolean {
    if (toolbarId == 0 || v !is TextView || v.id != View.NO_ID) return false
    val row = v.parent as? LinearLayout ?: return false
    if (row.id != View.NO_ID) return false
    return (row.parent as? View)?.id == toolbarId
}

/** The name like WhatsApp's own tab titles, the bio a quieter line under it; once per view, WaEnhancer never restyles them. */
internal fun dressWaeToolbarText(tv: TextView) {
    if (tv.getTag(waeToolbarTextTag) != null) return
    tv.setTag(waeToolbarTextTag, true)
    val row = tv.parent as? ViewGroup ?: return
    val bar = row.parent as? ViewGroup ?: return
    val base = toolbarTitleColor(bar) ?: primaryTextColor(tv.context)
    if (row.getChildAt(0) === tv) {
        tv.setTextColor(base)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, TOOLBAR_TITLE_SP)
        tv.setTypeface(tv.typeface, Typeface.BOLD)
    } else {
        tv.setTextColor((base and 0x00FFFFFF) or (WAE_BIO_ALPHA shl 24))
    }
    HookLog.hit("compat/toolbarText")
}

/** WhatsApp's title is the toolbar's own direct TextView, hidden or not; its colour is what the colour engine left it. */
private fun toolbarTitleColor(bar: ViewGroup): Int? {
    for (i in 0 until bar.childCount) {
        val c = bar.getChildAt(i) as? TextView ?: continue
        if (c.id == View.NO_ID) return c.currentTextColor
    }
    return null
}

/** A frame with the glass behind the button; the pane takes the button's measured size in the same pass, never a wrapping ancestor's. */
private class WaeButtonShell(ctx: Context) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val pane = getChildAt(0)
        val button = getChildAt(1)
        if (pane != null && button != null) {
            measureChildWithMargins(button, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = pane.layoutParams
            lp.width = button.measuredWidth
            lp.height = button.measuredHeight
            (pane as? GlassView)?.params?.cornerRadius = button.measuredHeight / 2f
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** Each button under the emoji row goes into a shell with a live pane of the emoji pill's recipe; shadow and caps go. */
internal fun dressWaeTrayButtons(tray: ViewGroup, container: View, tint: Int) {
    val gap = (WAE_TRAY_GAP_DP * tray.resources.displayMetrics.density).toInt()
    val content = activityOf(tray)?.findViewById<ViewGroup>(android.R.id.content)
    for (i in 0 until tray.childCount) {
        val c = tray.getChildAt(i) as? TextView ?: continue
        if (isAncestorOf(c, container)) continue
        // A Material button keeps its pill inside insets; zeroed, with the gap and the bottom inset kept as the shell's margins.
        val bottom = runCatching { XposedHelpers.callMethod(c, "getInsetBottom") as Int }.getOrNull()
        if (bottom != null) {
            runCatching {
                XposedHelpers.callMethod(c, "setInsetTop", 0)
                XposedHelpers.callMethod(c, "setInsetBottom", 0)
            }
        }
        val old = c.layoutParams as? ViewGroup.MarginLayoutParams
        tray.removeViewAt(i)
        val shell = WaeButtonShell(tray.context)
        val shellLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        shellLp.topMargin = maxOf(old?.topMargin ?: 0, gap)
        shellLp.bottomMargin = maxOf(old?.bottomMargin ?: 0, bottom ?: 0)
        tray.addView(shell, i, shellLp)
        // No Activity behind the popup, no backdrop: a placeholder keeps the child order and the button its own look.
        val pane: View = if (content != null) newTrayPane(tray.context, content, tint) else View(tray.context)
        shell.addView(pane, FrameLayout.LayoutParams(0, 0))
        shell.addView(
            c,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        if (content != null) clearBg(c, "wae tray button")
        // The theme's button shouts in caps and floats on a shadow; neither belongs on glass.
        c.setAllCaps(false)
        c.stateListAnimator = null
        c.elevation = 0f
        HookLog.hit("compat/trayButtons")
    }
}

/** The gap a stacked button keeps from the pill above it, the Material inset the copy button gives up. */
private const val WAE_TRAY_GAP_DP = 6f

/** Each WaEnhancer feature wraps the tray's children in a row and appends a button, so two of them leave a button beside the emojis; every button goes back under the row. */
internal fun flattenWaeTray(tray: ViewGroup, container: View) {
    val row = container.parent as? ViewGroup ?: return
    if (row === tray) return
    var holder: View = row
    while (holder.parent !== tray) holder = holder.parent as? View ?: return
    var group = holder as? ViewGroup
    var insertAt = tray.indexOfChild(holder) + 1
    val gap = (WAE_TRAY_GAP_DP * tray.resources.displayMetrics.density).toInt()
    while (group != null && group !== row) {
        var pathChild: View? = null
        val strays = ArrayList<View>()
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i) ?: continue
            if (c === row || (c is ViewGroup && isAncestorOf(c, container))) pathChild = c else strays.add(c)
        }
        for (c in strays) {
            group.removeView(c)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = gap
            tray.addView(c, insertAt++, lp)
            HookLog.hit("compat/trayStack")
        }
        group = pathChild as? ViewGroup
    }
    // Shelled here too, so the size WhatsApp measures already holds the panes.
    dressWaeTrayButtons(tray, container, TRAY_SCRIM)
}

/** WhatsApp sizes and seats the tray from a measure taken before attach; its two readers get a measure of the column instead. */
internal fun hookWaeTrayPlacement(app: Application, classLoader: ClassLoader) {
    val res = app.resources
    val pkg = app.packageName
    val trayId = res.waId("reactions_tray_layout", pkg)
    val containerId = res.waId("reactions_tray_container", pkg)
    if (trayId == 0 || containerId == 0) return
    if (!Deobfuscator.ensureBridge(app)) {
        logOnce("tray placement: no DexKit bridge")
        return
    }
    val cls = Deobfuscator.loadReactionsTrayClass(classLoader, trayId) ?: run {
        logOnce("tray placement: tray class unresolved")
        return
    }
    Deobfuscator.saveCache()
    val hook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val tray = param.thisObject as? ViewGroup ?: return
            val container = tray.findViewById<View>(containerId) ?: return
            if (container.parent === tray) return
            flattenWaeTray(tray, container)
            val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            tray.measure(spec, spec)
            if (!HookLog.isHit("compat/trayPlace")) HookLog.hit("compat/trayPlace")
        }
    }
    var hooked = 0
    for (m in cls.declaredMethods) {
        val p = m.parameterTypes
        // Both readers of the tray's measured size, the seat-and-animate call and the gap computation, found by signature.
        val seats = p.size == 4 && p[0] == Int::class.javaPrimitiveType && p[1] == Int::class.javaPrimitiveType &&
            p[2] == Boolean::class.javaPrimitiveType && p[3] == Long::class.javaPrimitiveType
        val gaps = p.size == 3 && p[0] == View::class.java && p[1] == View::class.java &&
            p[2] == Int::class.javaPrimitiveType && m.returnType == Int::class.javaPrimitiveType
        if (seats || gaps) {
            XposedBridge.hookMethod(m, hook)
            hooked++
        }
    }
    XposedBridge.log("[WaThemer.Glass] tray placement hooked on $hooked methods of ${cls.name}")
}
