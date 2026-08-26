// The conversation screen's chrome: the header capsule and band, the compose pill, and the list
// padding that lets rows scroll behind both. Geometry is re-derived per layout, never stored.
package com.wathemer.app.hooks.glass

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.FrameLayout
import com.wathemer.app.glass.GlassView
import com.wathemer.app.hooks.HookLog
import com.wathemer.app.hooks.waId
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal var convHolderRef: WeakReference<ViewGroup>? = null

internal var convCoordRef: WeakReference<ViewGroup>? = null

internal var convFooterRef: WeakReference<ViewGroup>? = null

internal val convToolbarTag = tagKey("wathemer-conv-toolbar")

internal val convFooterTag = tagKey("wathemer-conv-footer")

private val convFloatTag = tagKey("wathemer-conv-float")

private val convPanes = WeakHashMap<View, GlassView>()

private val convRect = Rect()

/** WhatsApp packs the toolbar targets edge to edge; trimming the diameter is what puts air between the pills. */
private const val CONV_PILL_TRIM_DP = 4f

/** Deliberately more than fits: both clamps engage and the capsule fills the header band, no slivers peek around it. */
private const val CONV_CAPSULE_GROW_DP = 8f

/** Resting clearance from the chrome; clipToPadding = false still lets rows scroll into the band. */
private const val CONV_CHROME_GAP_DP = 6f

/** Negative bottom margin grows the coordinator to y=0; translationZ is not optional, messages would paint over the toolbar. */
internal fun floatConvToolbar(holder: ViewGroup) {
    if (holder.getTag(convFloatTag) != null) return
    holder.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
        override fun onLayoutChange(
            v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or_: Int, ob: Int,
        ) {
            val h = b - t
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            if (h <= 0 || lp.bottomMargin < 0) return          // already floated
            v.removeOnLayoutChangeListener(this)
            v.setTag(convFloatTag, true)
            lp.bottomMargin = -h
            v.layoutParams = lp
            if (v.translationZ < 1f) v.translationZ = 1f
            XposedBridge.log("[$TAG] conversation toolbar floated (h=$h, bottomMargin=-$h)")
            runCatching { syncConvToolbar() }
        }
    })
    holder.requestLayout()
}

/** RelativeLayout here, so the negative margin goes on the list host; the footer draws later and needs no lift. */
internal fun floatConvFooter(footer: ViewGroup) {
    if (footer.getTag(convFloatTag) != null) return
    val parent = footer.parent as? ViewGroup ?: return
    // Looked up in the listener: at attach every child still has height 0 and an early return would skip the float.
    footer.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
        override fun onLayoutChange(
            v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or_: Int, ob: Int,
        ) {
            val h = b - t
            if (h <= 0) return
            val listHost = convListHost(parent) ?: return
            val lp = listHost.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            if (lp.bottomMargin < 0) return                    // already floated
            v.removeOnLayoutChangeListener(this)
            v.setTag(convFloatTag, true)
            val wasAtBottom = !listHost.canScrollVertically(1)
            lp.bottomMargin = -h
            listHost.layoutParams = lp
            XposedBridge.log(
                "[$TAG] conversation footer floated (h=$h, list host bottomMargin=-$h)",
            )
            runCatching { padConvList(listHost, h) }
            // Only correct an offset we caused, and only when the list was pinned; from onPreDraw, see repinToBottom.
            if (wasAtBottom) {
                (0 until listHost.childCount)
                    .mapNotNull { listHost.getChildAt(it) as? AbsListView }
                    .firstOrNull()
                    ?.let { repinToBottom(it) }
            }
            runCatching { syncConvFooter() }
        }
    })
    footer.requestLayout()
}

/** The row dissolve runs from the list's top edge to the pill's bottom plus this; bigger is gentler. */
private const val CONV_ROW_FADE_DP = 56f

private var convRowFadeLen = 0f

private var convBandRef: WeakReference<GlassView>? = null

/** The list's own parent: a plain FrameLayout, and the fade pane's host. */
private var convListHostRef: WeakReference<ViewGroup>? = null

private fun convPillAlpha(): Int =
    if (convBandRef?.get()?.parent != null) {
        (TINT_ALPHA - convBandAlpha()).coerceAtLeast(0)
    } else {
        TINT_ALPHA
    }

/** Full-width band fading below the toolbar pill. Wallpaper only: a backdrop would flicker as messages scroll under it. */
internal fun syncConvBand(holder: ViewGroup) {
    if (holder.height <= 0) return
    val under = wallpaperUnderlay(holder)
    // No wallpaper means nothing to transmit, and a tint-only band would just be a grey wash.
    if (under.isEmpty()) return
    val res = holder.resources
    val abrId = res.waId("action_bar_root", holder.context.packageName)
    if (abrId == 0) return
    val abr = holder.rootView?.findViewById<View>(abrId) as? FrameLayout ?: return

    // Solid to the pill's bottom, not the holder's; only the capsule pane knows where the pill ends.
    val at = IntArray(2)
    val abrAt = IntArray(2)
    holder.getLocationOnScreen(at)
    abr.getLocationOnScreen(abrAt)
    val pillBottom = convPanes.values
        .filter { it.parent === holder }
        .mapNotNull { it.layoutParams as? FrameLayout.LayoutParams }
        .maxOfOrNull { it.topMargin + it.height }
        ?: holder.height
    val solid = (at[1] - abrAt[1]) + pillBottom
    if (solid <= 0) return
    // One pane only: two abutting panes cannot be seamless, each blur kernel is clipped to its own capture.
    // Hard stop on the pill's bottom edge; the SDF is expanded past it so the cut has no rim of its own.
    val total = solid

    var band = convBandRef?.get()
    if (band == null || band.parent !== abr) {
        band = GlassView(abr.context).apply {
            underlay = under
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = holder.dp(BLUR_DP)
                refractionEnabled = true
                // With the SDF expanded there is no bevel to size; a 1px nominal one keeps depth and displacement at zero.
                bevelFraction = 0f
                bevelThickness = 1f
                depthRatio = DEPTH_RATIO
                maxDisplacePx = holder.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                cornerRadius = 0f
                // Full-bleed panel; a rim here would outline the screen.
                rimEnabled = false
                edgeExpandLeft = 8f
                edgeExpandTop = 8f
                edgeExpandRight = 8f
                edgeExpandBottom = 8f
            }
        }
        // Above the wallpaper and its dim; if they are not abr's children the band hides behind them, hence the log.
        var insertAt = 0
        for (i in 0 until abr.childCount) {
            val c = abr.getChildAt(i)
            if (c === under.firstOrNull() || (under.size > 1 && c === under[1])) insertAt = i + 1
        }
        if (insertAt == 0) {
            XposedBridge.log(
                "[$TAG] WARN: wallpaper views are not children of action_bar_root; the band " +
                    "will be behind them and invisible",
            )
        }
        abr.addView(
            band, insertAt,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, total).apply {
                gravity = Gravity.TOP
            },
        )
        convBandRef = WeakReference(band)
        XposedBridge.log(
            "[$TAG] conversation band inserted at index $insertAt " +
                "(solid=${solid}px tint=${convBandAlpha()})",
        )
    }
    band.params.tintColor = glassTint(convBandAlpha())
    val lp = band.layoutParams as? FrameLayout.LayoutParams ?: return
    if (lp.height != total) {
        lp.height = total
        band.layoutParams = lp
        XposedBridge.log("[$TAG] conversation band resized to ${total}px")
    }
    convRowFadeLen = (solid - (convListHostRef?.get()?.let { h ->
        val a = IntArray(2); h.getLocationOnScreen(a); a[1]
    } ?: 0)) + holder.dp(CONV_ROW_FADE_DP)
    syncRowFade()
}

/** The framework's own fading edge; the ramp is also handed to GlassBubblePane so bubble glass fades with the text. */
private fun syncRowFade() {
    val list = convListRef?.get() ?: return
    val len = convRowFadeLen
    if (len <= 0f) return
    if (!list.isVerticalFadingEdgeEnabled) {
        list.isVerticalFadingEdgeEnabled = true
        XposedBridge.log("[$TAG] row fade armed (${len.toInt()}px from the list's top edge)")
    }
    if (list.verticalFadingEdgeLength != len.toInt()) list.setFadingEdgeLength(len.toInt())
    val at = IntArray(2)
    list.getLocationOnScreen(at)
    convBubblePaneRef?.get()?.let { pane ->
        pane.fadeLineScreenY = at[1].toFloat()
        pane.fadeLen = len
    }
}

internal val pillPadTag = tagKey("wathemer-pill-pad")

private val mentionPaneTag = tagKey("wathemer-mention-pane")

private val mentionPreDrawTag = tagKey("wathemer-mention-predraw")

/** The mention list blurs the conversation behind it; its own painted fill goes. */
internal fun syncMentionPane(host: FrameLayout) {
    var pane = host.getTag(mentionPaneTag) as? GlassView
    if (pane == null || pane.parent !== host) {
        pane = GlassView(host.context).apply {
            backdrop = convCoordRef?.get()
            underlay = wallpaperUnderlay(host)
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = host.dp(BLUR_DP)
                cornerRadius = host.dp(16f)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = host.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                tintColor = glassTint(convPillAlpha())
            }
        }
        host.addView(
            pane, 0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        host.setTag(mentionPaneTag, pane)
        HookLog.hit("pane/mention")
    } else if (pane.backdrop == null) {
        pane.backdrop = convCoordRef?.get()
    }
    // WhatsApp repaints the picker's fill per data pass with no layout to catch, so pre-draw it away.
    if (host.getTag(mentionPreDrawTag) == null) {
        host.setTag(mentionPreDrawTag, true)
        host.viewTreeObserver.addOnPreDrawListener {
            for (i in 0 until host.childCount) {
                val c = host.getChildAt(i)
                if (c !is GlassView && c.background != null) c.background = null
            }
            true
        }
    }
}

/** Zero every header pane but the one taking the capsule now, or two pills stack. */
private fun collapseHeaderPanes(holder: ViewGroup, keep: View) {
    for ((owner, g) in convPanes.entries) {
        if (g.parent !== holder || owner === keep) continue
        val glp = g.layoutParams ?: continue
        if (glp.width != 0 || glp.height != 0) {
            glp.width = 0
            glp.height = 0
            g.layoutParams = glp
        }
    }
}

/** One capsule spanning the toolbar; every rect is re-derived, never stored, custom_view resizes when the menu inflates. */
internal fun syncConvToolbar() {
    val holder = convHolderRef?.get() ?: return
    if (holder.width <= 0 || holder.height <= 0) return
    val toolbar = (0 until holder.childCount)
        .mapNotNull { holder.getChildAt(it) as? ViewGroup }
        .firstOrNull { it !is GlassView && it.javaClass.name.contains("Toolbar") } ?: return

    // In search the holder swaps in its own bar; the capsule and the band's bottom edge follow it.
    val barId = holder.resources.waId("search_view_toolbar", holder.context.packageName)
    val searchBar = (if (barId != 0) holder.findViewById<View>(barId) else null)
        ?.takeIf { it.isShown && it.width > 0 && it.height > 0 }
    if (searchBar != null) {
        collapseHeaderPanes(holder, searchBar)
        val r = Rect(0, 0, searchBar.width, searchBar.height)
        if (runCatching { holder.offsetDescendantRectToMyCoords(searchBar, r) }.isSuccess) {
            clearBg(searchBar, "conversation search bar")
            pill(holder, searchBar, r.left, r.top, r.right, r.bottom)
        }
        return
    }
    // In selection the bar lives in action_bar_root over this band; leave the capsule or that bar loses its pill.
    if (!toolbar.isShown) return
    collapseHeaderPanes(holder, toolbar)

    // WhatsApp's own bar fill has to go, or the pills sit on a slab instead of on the wallpaper.
    clearBg(toolbar, "conversation toolbar")
    // The 1dp line is WDSToolbar.onDraw's, handled by ensureToolbarDividerHook; cleared per-id, never blanket.
    if (toolbar.foreground != null) {
        logOnce("conversation toolbar foreground cleared: ${toolbar.foreground?.javaClass?.name}")
        toolbar.foreground = null
    }

    val d = holder.resources.displayMetrics.density
    val inset = (CONV_PILL_INSET_DP * d).toInt()

    // Found structurally: the ActionMenuView has no id at all.
    var custom: ViewGroup? = null
    var actions: ViewGroup? = null
    for (i in 0 until toolbar.childCount) {
        val c = toolbar.getChildAt(i) as? ViewGroup ?: continue
        if (c.javaClass.name.contains("ActionMenuView")) actions = c
        else if (c.childCount > 0) custom = c
    }

    val backId = holder.resources.waId("whatsapp_toolbar_home", holder.context.packageName)
    val back = if (backId != 0) custom?.findViewById<View>(backId) else null

    // The band comes from the Back button, not the bar: the elements do not share a height.
    var refTop = -1
    var refBottom = -1
    if (back != null && back.width > 0) {
        rectInHolder(holder, back)
        refTop = convRect.top
        refBottom = convRect.bottom
    } else if (actions != null && actions.childCount > 0) {
        val a = actions.getChildAt(0)
        if (a != null && a.height > 0) {
            rectInHolder(holder, a)
            refTop = convRect.top
            refBottom = convRect.bottom
        }
    }
    if (refBottom <= refTop) return
    val trim = (CONV_PILL_TRIM_DP * d).toInt()
    // The holder clamp matters: clipChildren=true silently cuts an oversize capsule's rounded ends.
    val grow = (CONV_CAPSULE_GROW_DP * d).toInt()
    val pt = (refTop + trim / 2 - grow).coerceAtLeast(0)
    val pb = (refBottom - trim / 2 + grow).coerceAtMost(holder.height)
    val band = (pb - pt).coerceAtLeast(1)

    // ── One capsule ───────────────────────────────────────────────────────────────
    // WhatsApp's own layout spaces the controls inside it, so none of the per-pill centring defects can recur.
    val l = inset
    val r = holder.width - inset
    if (r - l > band) pill(holder, toolbar, l, pt, r, pb)
}

/** [convRect] = [child]'s bounds mapped into [holder]'s coordinates. */
private fun rectInHolder(holder: ViewGroup, child: View) {
    convRect.set(0, 0, child.width, child.height)
    runCatching { holder.offsetDescendantRectToMyCoords(child, convRect) }
}

/** Padding parks the messages clear of the chrome; clipToPadding = false lets them scroll behind it. */
private fun padConvList(listHost: ViewGroup, footerHeight: Int) {
    val list = (0 until listHost.childCount)
        .map { listHost.getChildAt(it) }
        .firstOrNull { it is AbsListView } as? AbsListView ?: return
    convListRef = WeakReference(list)
    convListHostRef = WeakReference(listHost)
    ensureRowHook()
    ensureBubblePane(listHost, list)
    // After the bubble pane, deliberately: both insert at index 0, so this one ends up beneath it.
    ensureMsgSelectPane(listHost, list)
    val d = list.resources.displayMetrics.density
    val gap = (CONV_CHROME_GAP_DP * d).toInt()
    val holder = convHolderRef?.get()
    val top = (holder?.height ?: 0) + gap
    val bottom = footerHeight + gap
    if (list.paddingTop == top && list.paddingBottom == bottom) return
    val wasAtBottom = !list.canScrollVertically(1)
    list.clipToPadding = false
    list.setPadding(list.paddingLeft, top, list.paddingRight, bottom)
    XposedBridge.log("[$TAG] conversation list padded (top=$top bottom=$bottom)")
    if (wasAtBottom) repinToBottom(list)
}

/** The conversation's own AbsListView: the row hooks' identity test and the collectors' row source. */
internal var convListRef: WeakReference<ViewGroup>? = null

/** Found structurally, child 0 is a zero-height clipper; never derive from an index you did not set. */
private fun convListHost(parent: ViewGroup): ViewGroup? {
    for (i in 0 until parent.childCount) {
        val c = parent.getChildAt(i) as? ViewGroup ?: continue
        if (c.height <= 0) continue
        if (containsList(c, 0)) return c
    }
    return null
}

private fun containsList(v: View, depth: Int): Boolean {
    if (v is AbsListView ||
        v is ViewGroup && v.javaClass.name.contains("ConversationListView")
    ) {
        return true
    }
    if (depth >= 3 || v !is ViewGroup) return false
    for (i in 0 until v.childCount) {
        if (containsList(v.getChildAt(i) ?: continue, depth + 1)) return true
    }
    return false
}

/** Keyed by the element, not by index: the ActionMenuView's children change when the menu inflates. */
internal fun pill(holder: ViewGroup, owner: View, l: Int, t: Int, r: Int, b: Int) {
    if (r <= l || b <= t) return
    var glass = convPanes[owner]
    if (glass == null || glass.parent !== holder) {
        glass = GlassView(holder.context).apply {
            backdrop = convCoordRef?.get()
            underlay = wallpaperUnderlay(holder)
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = holder.dp(BLUR_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = holder.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                tintColor = glassTint(convPillAlpha())
            }
        }
        holder.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
        convPanes[owner] = glass
        HookLog.hit("pane/convCapsule")
    } else if (glass.backdrop == null) {
        // The coordinator may have attached after the pill did.
        glass.backdrop = convCoordRef?.get()
    }
    // Re-asserted: the band may arrive after the pill, and the split depends on whether it exists.
    glass.params.tintColor = glassTint(convPillAlpha())
    val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
    if (lp.width != r - l || lp.height != b - t || lp.leftMargin != l || lp.topMargin != t) {
        lp.width = r - l
        lp.height = b - t
        lp.leftMargin = l
        lp.topMargin = t
        lp.gravity = Gravity.TOP or Gravity.START
        glass.layoutParams = lp
        glass.params.cornerRadius = (b - t) / 2f
        // One log line per distinct rect, so an off-centre pill can be checked against numbers.
        val name = runCatching { owner.resources.getResourceEntryName(owner.id) }
            .getOrNull() ?: owner.javaClass.simpleName
        XposedBridge.log(
            "[$TAG] pill $name owner=${owner.left},${owner.top}-${owner.right},${owner.bottom} " +
                "pad(${owner.paddingLeft},${owner.paddingRight}) -> pane $l,$t-$r,$b",
        )
    }
}

/** Sized to input_layout so the send button keeps its own circle; backdrop is a sibling, so the capture cannot recurse. */
internal fun syncConvFooter() {
    val footer = convFooterRef?.get() ?: return
    if (footer.width <= 0 || footer.height <= 0) return
    val res = footer.resources
    val pkg = footer.context.packageName
    val inputId = res.waId("input_layout", pkg)
    val sendId = res.waId("conversation_entry_action_button", pkg)
    // ── No compose row where you cannot compose ────────────────────────────────────
    // These replace the compose row while input_layout stays VISIBLE; collapse by size, syncPaneVisibility owns visibility.
    for (n in listOf(
        "read_only_chat_info_container",
        "composer_blocker",
        "voice_note_draft_layout_v2",
    )) {
        val id = res.waId(n, pkg)
        if (id == 0) continue
        val v = footer.rootView?.findViewById<View>(id) ?: continue
        if (v.isShown) {
            collapseConvPanes(footer)
            // The recorder's stock card was neutralised with the shell; give it back, sized to the layout, the footer is taller.
            if (n == "voice_note_draft_layout_v2" && v.width > 0 && v.height > 0) {
                convRect.set(0, 0, v.width, v.height)
                runCatching { footer.offsetDescendantRectToMyCoords(v, convRect) }
                composePane(
                    footer, v,
                    convRect.left, convRect.top, convRect.right, convRect.bottom,
                    (footer.parent as? ViewGroup)?.let { p -> convListHost(p) },
                )
            }
            return
        }
    }

    val input = if (inputId != 0) footer.findViewById<View>(inputId) else null
    val send = if (sendId != 0) footer.findViewById<View>(sendId) else null
    // Same lookup as floatConvFooter, and for the same reason: index 0 is a zero-height clipper.
    val listHost = (footer.parent as? ViewGroup)?.let { convListHost(it) }

    // An ancestor going gone leaves both owners VISIBLE at their last size, so neither is presence.
    if (goneAbove(input, footer) && goneAbove(send, footer)) {
        collapseConvPanes(footer)
        return
    }

    // Only owners this pass will NOT re-size. Sweeping the live pair too collapsed a pane that
    // composePane sized again below it, which is two layout passes per frame rather than a rebuild.
    if (convPanes.isNotEmpty()) {
        // parent === footer only: the header capsule shares this map and belongs to syncConvToolbar.
        val gone = ArrayList<View>()
        for ((owner, glass) in convPanes) {
            if (glass.parent !== footer) continue
            if (owner === input || owner === send) continue
            // Departed, not merely unmeasured: a live owner reads zero-size on animation frames.
            if (!owner.isAttachedToWindow || owner.parent == null) {
                gone.add(owner)
                continue
            }
            // Still attached, so a layout is coming and a zeroed size will actually apply.
            val lp = glass.layoutParams
            if (lp != null && (lp.width != 0 || lp.height != 0)) {
                lp.width = 0
                lp.height = 0
                glass.layoutParams = lp
            }
        }
        for (o in gone) {
            convPanes.remove(o)?.let { g -> (g.parent as? ViewGroup)?.removeView(g) }
        }
    }

    if (input != null && input.width > 0) {
        clearBg(input, "input_layout")
        // The mic disc's own screen margin; the pill's left edge mirrors it or the corner clips.
        var sideFloor = 0
        if (send != null && send.width > 0) {
            val sr = Rect(0, 0, send.width, send.height)
            if (runCatching { footer.offsetDescendantRectToMyCoords(send, sr) }.isSuccess) {
                sideFloor = (footer.width - sr.right).coerceAtLeast(0)
            }
        }
        convRect.set(0, 0, input.width, input.height)
        runCatching { footer.offsetDescendantRectToMyCoords(input, convRect) }
        // Grown so the icons can breathe, clamped because the footer is a ClippingLayout and shaves the overflow.
        val pad = footer.dp(COMPOSE_PILL_PAD_DP).toInt()
        composePane(
            footer, input,
            (convRect.left - pad).coerceAtLeast(sideFloor),
            (convRect.top - pad).coerceAtLeast(0),
            (convRect.right + pad).coerceAtMost(footer.width),
            (convRect.bottom + pad).coerceAtMost(footer.height),
            listHost,
        )
    }
    if (send != null && send.width > 0) {
        convRect.set(0, 0, send.width, send.height)
        runCatching { footer.offsetDescendantRectToMyCoords(send, convRect) }
        composePane(footer, send, convRect.left, convRect.top, convRect.right, convRect.bottom, listHost)
    }
}

/** GONE between an owner and the footer is the composer removed; INVISIBLE there is the open animation. */
private fun goneAbove(v: View?, stop: View): Boolean {
    var p: View? = v ?: return true
    var hops = 0
    while (p != null && hops < 8) {
        if (p.visibility == View.GONE) return true
        if (p === stop) return false
        p = p.parent as? View
        hops++
    }
    return false
}

/** Zero-size, never hide: syncPaneVisibility is the only visibility writer. Scoped by parent, the map also holds the header capsule. */
private fun collapseConvPanes(footer: ViewGroup) {
    for (glass in convPanes.values) {
        if (glass.parent !== footer) continue
        val lp = glass.layoutParams ?: continue
        if (lp.width != 0 || lp.height != 0) {
            lp.width = 0
            lp.height = 0
            glass.layoutParams = lp
        }
    }
}

private fun composePane(
    footer: ViewGroup,
    owner: View,
    l: Int,
    t: Int,
    r: Int,
    b: Int,
    backdropView: ViewGroup?,
) {
    if (r <= l || b <= t) return
    var glass = convPanes[owner]
    if (glass == null || glass.parent !== footer) {
        glass = GlassView(footer.context).apply {
            backdrop = backdropView
            underlay = wallpaperUnderlay(footer)
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = footer.dp(BLUR_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = footer.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                tintColor = glassTint(TINT_ALPHA)
            }
        }
        // Index 0, so WhatsApp's own icons and the text field keep painting on top of it.
        footer.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
        convPanes[owner] = glass
        HookLog.hit("pane/convCompose")
    } else if (glass.backdrop == null && backdropView != null) {
        glass.backdrop = backdropView
    }
    val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
    if (lp.width != r - l || lp.height != b - t || lp.leftMargin != l || lp.topMargin != t) {
        lp.width = r - l
        lp.height = b - t
        lp.leftMargin = l
        lp.topMargin = t
        lp.gravity = Gravity.TOP or Gravity.START
        glass.layoutParams = lp
        // Capped: height/2 on a two-row compose box sweeps the corners across the quote's thumbnail.
        glass.params.cornerRadius = minOf((b - t) / 2f, footer.dp(COMPOSE_MAX_RADIUS_DP))
    }
}

/** How far the compose pill is grown past `input_layout` so its icons are not flush. */
private const val COMPOSE_PILL_PAD_DP = 5f

/** Ceiling on the compose pill's corner radius: half of the single-row height (136px). */
private const val COMPOSE_MAX_RADIUS_DP = 24f
