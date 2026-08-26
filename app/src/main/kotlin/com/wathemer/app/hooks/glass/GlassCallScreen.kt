// The audio call screen and the call info page. Video stands the card glass down: a Surface renders
// outside the view tree, so there is nothing for a capture to transmit.
package com.wathemer.app.hooks.glass

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.wathemer.app.glass.FrostDrawable
import com.wathemer.app.glass.GlassBubblePane
import com.wathemer.app.glass.GlassParams
import com.wathemer.app.glass.GlassView
import com.wathemer.app.glass.RectList
import com.wathemer.app.hooks.waId
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import kotlin.math.abs

/** Uniform gap between the call card's content and the pane border; WA's own audio card measures the same 24dp. */
private const val CALL_CARD_PAD_DP = 24f

private val callGlassTag = tagKey("wathemer-call-glass")

private val callBgStashTag = tagKey("wathemer-call-bg-stash")

private var callVideoLive = false

private val callAt = IntArray(2)

private var callCardRef: WeakReference<ViewGroup>? = null

private var callCardBgRef: WeakReference<View>? = null

private var callSymLogged = false

/** Wallpaper revealed and the video watcher armed; the card's pane rides its own registration. */
internal fun callScreenGlass(root: ViewGroup) {
    val pkg = root.context.packageName
    val res = root.resources
    // The wallpaper injector reaches every Activity; the glass only lies without a real backdrop under it.
    if (root.rootView?.findViewWithTag<View>("wt_wallpaper") == null) {
        logOnce("call screen: no wallpaper in this window; glass stands down")
        return
    }
    val bgId = res.waId("call_background", pkg)
    root.findViewById<View>(bgId)?.let {
        // INVISIBLE not GONE, the hideNativeWallpaper rule: the layout slot stays.
        if (it.visibility == View.VISIBLE) it.visibility = View.INVISIBLE
    }
    val surfaceId = res.waId("surface_view", pkg)
    val callScreenId = res.waId("call_screen", pkg)
    clearCallShells(root, callScreenId)

    // Layout-driven, never per frame: findViewById is a tree walk and calls upgrade to video mid-session.
    if (root.getTag(callGlassTag) == null) {
        root.setTag(callGlassTag, true)
        val refresh = refresh@{
            // Re-assert per layout: WA repaints its backdrop on connect, and the flip lands before the draw.
            root.findViewById<View>(bgId)?.let { if (it.visibility == View.VISIBLE) it.visibility = View.INVISIBLE }
            clearCallShells(root, callScreenId)
            val was = callVideoLive
            callVideoLive = surfaceId != 0 && root.findViewById<View>(surfaceId)?.isShown == true
            if (was == callVideoLive) return@refresh
            XposedBridge.log(
                "[$TAG] call video ${if (callVideoLive) "LIVE; card glass standing down" else "gone; card glass resumes"}",
            )
            if (callVideoLive) restoreCallCardBg()
        }
        refresh()
        root.viewTreeObserver.addOnGlobalLayoutListener { runCatching { refresh() } }
    }
}

/** The doodle image hid solid fills on the shells; every one of them sits over the injected wallpaper. */
private fun clearCallShells(root: ViewGroup, callScreenId: Int) {
    var v: View? = root
    var hops = 0
    while (v != null && hops < 6) {
        val bg = v.background
        if (bg != null) {
            logOnce("call shell fill cleared: ${v.javaClass.simpleName} (${bg.javaClass.simpleName})")
            v.background = null
        }
        if (v.id == android.R.id.content) break
        v = v.parent as? View
        hops++
    }
    if (callScreenId != 0) {
        root.findViewById<View>(callScreenId)?.let {
            val bg = it.background
            if (bg != null) {
                logOnce("call shell fill cleared: call_screen (${bg.javaClass.simpleName})")
                it.background = null
            }
        }
    }
}

/** Refreshes the refs on every card (re)build and inserts the pane once per host. */
internal fun callCardGlass(card: ViewGroup) {
    val pkg = card.context.packageName
    val res = card.resources
    val root = card.rootView ?: return
    if (root.findViewWithTag<View>("wt_wallpaper") == null) return
    callCardRef = WeakReference(card)
    callCardBgRef = WeakReference(card.findViewById(res.waId("background", pkg)))

    // Guard on the HOST: WA rebuilds the card per call type, and a tag on the dead instance would stack panes.
    val host = card.parent as? ViewGroup ?: return
    if (host.getTag(callGlassTag) != null) return
    host.setTag(callGlassTag, true)
    val d = card.resources.displayMetrics.density
    // The stock fill's own radius, read before the clear strips it; the card family default otherwise.
    val stockRadius = callCardBgRef?.get()?.let { readCornerRadius(it) }
    val g = GlassBubblePane(host.context)
    g.params = GlassParams(d).apply {
        blurRadius = d * BLUR_DP
        cornerRadius = stockRadius ?: (d * CARD_RADIUS_DP)
        refractionEnabled = true
        bevelFraction = BEVEL_FRACTION
        depthRatio = DEPTH_RATIO
        maxDisplacePx = d * DISPLACE_DP
        fresnelStrength = 0.5f
        tintColor = glassTintColor
    }
    g.tint = { glassTintColor }
    g.backdrop = { bubbleBackdrop(g) }
    g.placement = { bubbleWpPlacement }
    g.dim = { 0f }
    g.rimColor = glassTint(BUBBLE_RIM_ALPHA)
    g.rimWidth = d
    g.collect = { out -> collectCallCard(out) }
    g.onGeometryChanged = { markWallpaperGeometryDirty() }
    // Just under the card in its own parent: over the grid and header, never over the buttons.
    host.addView(
        g, host.indexOfChild(card).coerceAtLeast(0),
        ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ),
    )
    XposedBridge.log("[$TAG] call card pane inserted (stockRadius=${stockRadius?.toInt() ?: -1}px)")
}

/** The pane rect is the content union plus one equal pad on all four sides, symmetric by construction. */
private fun collectCallCard(out: RectList) {
    if (callVideoLive) return
    val card = callCardRef?.get() ?: return
    if (!card.isShown || card.width <= 0 || card.height <= 0) return
    val bg = callCardBgRef?.get()
    // Prevention, not correction: WA rebuilds the card per call type, so the clear rides the pre-draw collect.
    if (bg != null && bg.background != null) {
        bg.setTag(callBgStashTag, bg.background)
        bg.background = null
    }
    var l = Int.MAX_VALUE
    var t = Int.MAX_VALUE
    var r = Int.MIN_VALUE
    var b = Int.MIN_VALUE
    for (i in 0 until card.childCount) {
        val c = card.getChildAt(i) ?: continue
        // Bare Views are the card's own fill and click targets, not content; header_click spans the card.
        if (c === bg || c.javaClass == View::class.java) continue
        if (!c.isShown || c.width <= 0 || c.height <= 0) continue
        if (c.left < l) l = c.left
        if (c.top < t) t = c.top
        if (c.right > r) r = c.right
        if (c.bottom > b) b = c.bottom
    }
    if (r <= l || b <= t) return
    val pad = card.dp(CALL_CARD_PAD_DP)
    card.getLocationOnScreen(callAt)
    out.add(
        callAt[0] + l - pad,
        callAt[1] + t - pad,
        callAt[0] + r + pad,
        callAt[1] + b + pad,
    )
    if (!callSymLogged) {
        callSymLogged = true
        XposedBridge.log(
            "[$TAG] call card pane: union=[$l,$t,$r,$b] pad=${pad.toInt()}px card=${card.width}x${card.height}",
        )
    }
}

/** The More dialog: its own window, so the container wears the sheet stamp and the rows keep ripples only. */
internal fun callMoreMenuGlass(label: View) {
    val row = label.parent as? ViewGroup ?: return
    val container = row.parent as? ViewGroup ?: return
    if (container.getTag(callGlassTag) != null) return
    container.setTag(callGlassTag, true)
    val d = label.resources.displayMetrics.density
    var rows = 0
    for (i in 0 until container.childCount) {
        val r = container.getChildAt(i) as? ViewGroup ?: continue
        if (r.background == null) continue
        // Radius read before the fill goes; the ripple keeps the touch feedback the fill carried.
        val radius = readCornerRadius(r) ?: (14f * d)
        val mask = GradientDrawable().apply {
            setColor(-1)
            cornerRadius = radius
        }
        r.background = RippleDrawable(
            ColorStateList.valueOf(0x2EFFFFFF), null, mask,
        )
        rows++
        for (j in 0 until r.childCount) {
            val c = r.getChildAt(j)
            if (c is FrameLayout && c.background != null) {
                runCatching { tintWdsShape(c.background, glassTint(CHIP_ALPHA)) }
            }
        }
    }
    // The card is menu_card_frame above the rows (ancestry-traced); its own fill is the slab, nothing else is touched.
    val frameId = label.resources.waId("menu_card_frame", label.context.packageName)
    var frame: View? = container
    var hops = 0
    while (frame != null && frame.id != frameId && hops < 4) {
        frame = frame.parent as? View
        hops++
    }
    if (frame == null || frame.id != frameId) {
        logOnce("call more menu: menu_card_frame not found above the rows")
        return
    }
    val radius = readCornerRadius(frame) ?: (d * CARD_RADIUS_DP)
    // Straight assignment, never clearBg: its keep-clear re-assert kills the stamp.
    frame.background = FrostDrawable(
        frame, bubbleBackdrop(frame),
        radius, glassTintColor,
        strokeWidth = frame.dp(1f), strokeColor = glassTint(CHIP_RIM_ALPHA),
        ignorePadding = true,
    )
    XposedBridge.log("[$TAG] call more menu: menu_card_frame frosted, $rows rows cleared to ripple")
}

/** Video went live: the stamp stops, so the card must get its own fill back or it floats bare over the feed. */
private fun restoreCallCardBg() {
    val bg = callCardBgRef?.get() ?: return
    val stash = bg.getTag(callBgStashTag) as? Drawable ?: return
    if (bg.background == null) bg.background = stash
    bg.setTag(callBgStashTag, null)
}

internal var callHeaderHostRef: WeakReference<ViewGroup>? = null

private var callHeaderPanelRef: WeakReference<GlassView>? = null

internal val callHeaderTag = tagKey("wathemer-call-header")

internal val callLogDividerTag = tagKey("wathemer-call-log-divider")

internal fun injectCallHeaderPanel(host: ViewGroup) {
    if (callHeaderPanelRef?.get()?.parent === host) return
    if (!host.isAttachedToWindow) return
    val glass = newCardGlass(host)
    host.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
    callHeaderPanelRef = WeakReference(glass)
    XposedBridge.log("[$TAG] call header panel inserted")
}

/** The contact header's panel, sized to its collapsing toolbar; no clip, nothing scrolls under its edge. */
internal fun syncCallHeaderPanel() {
    val host = callHeaderHostRef?.get() ?: return
    val glass = callHeaderPanelRef?.get() ?: return
    if (host.width <= 0 || host.height <= 0) return

    val d = host.resources.displayMetrics.density
    val side = (CARD_INSET_DP * d).toInt()
    val gap = (CARD_GAP_DP * d).toInt()
    // Full height, side insets only: the header's own margins already supply the breathing room.
    val top = 0
    val bottom = host.height
    val right = host.width - side
    if (bottom - top < gap || right - side < gap) return

    val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
    if (lp.width != right - side || lp.height != bottom - top ||
        lp.leftMargin != side || lp.topMargin != top
    ) {
        lp.width = right - side
        lp.height = bottom - top
        lp.leftMargin = side
        lp.topMargin = top
        lp.gravity = Gravity.TOP or Gravity.START
        glass.layoutParams = lp
        XposedBridge.log("[$TAG] call header panel $side,$top-$right,$bottom")
    }
}

internal val callActionsTag = tagKey("wathemer-call-actions")

/** Frost the Calls tab's action discs, matched by shape since they carry no ids; scoped to this one row. */
internal fun frostCallActions(row: ViewGroup) {
    val min = row.dp(40f)
    fun walk(v: View) {
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i) ?: continue)
            return
        }
        if (v.background == null) return
        if (v.width < min || v.height < min) return
        if (abs(v.width - v.height) > v.width * 0.15f) return   // square only
        // ignorePadding: the ripple fills the whole disc, and an inset frost reads as a dot inside a hole.
        runCatching { frost(v, allowSquare = true, ignorePadding = true) }
    }
    walk(row)
}

internal var callBtnIds = IntArray(0)
