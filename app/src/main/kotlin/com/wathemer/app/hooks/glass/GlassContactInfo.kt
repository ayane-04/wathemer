// Contact, group, business and newsletter info: the collapsing header's covering pane and the
// section cards. One pane stamps every card, because a background per card goes stale under scroll.
package com.wathemer.app.hooks.glass

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.wathemer.app.glass.GlassBubblePane
import com.wathemer.app.glass.GlassParams
import com.wathemer.app.glass.GlassView
import com.wathemer.app.glass.RectList
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

private var infoListRef: WeakReference<ViewGroup>? = null

private val infoHeaderTag = tagKey("wathemer-info-header")

internal var infoCollapsingPhotoId = 0

internal var infoPictureId = 0

internal var infoPhotoOverlayId = 0

internal var infoTitleId = 0

internal var infoSubtitleId = 0

internal var infoGroupTitleId = 0

internal var infoGroupSubtitleId = 0

private val infoGhostAt = IntArray(2)

private val infoGhostTag = tagKey("wathemer-info-ghost")

/** Clear the header's black band (picture + photo_overlay); re-asserted every layout, one clear is undone by the first scroll. */
internal fun infoHeaderGlass(header: View) {
    val holder = header as? ViewGroup ?: return
    // The header id is shared with the home screen; the collapsing photo marks this one as contact info.
    if (infoCollapsingPhotoId == 0 || holder.findViewById<View>(infoCollapsingPhotoId) == null) return
    val hideBand = Runnable {
        for (id in intArrayOf(infoPictureId, infoPhotoOverlayId)) {
            if (id == 0) continue
            val v = holder.findViewById<View>(id) ?: continue
            if (v.visibility != View.INVISIBLE) v.visibility = View.INVISIBLE
        }
    }
    hideBand.run()
    val sync = Runnable {
        hideBand.run()
        runCatching { syncInfoFade(holder) }
    }
    sync.run()
    if (holder.getTag(infoHeaderTag) == null) {
        holder.setTag(infoHeaderTag, true)
        holder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync.run() }
        logOnce("info header band cleared")
    }
}

private var infoTopPaneRef: WeakReference<GlassView>? = null

private val infoTopAt = IntArray(2)

/** Expanded height per header, weakly keyed: the two info screens share the id at different heights, one shared max dims both. */
private val infoHeaderMaxH = WeakHashMap<View, Int>()

/** Stronger than a reading surface: this panel exists to bury what slides under it. */
private const val INFO_FADE_BLUR_BOOST = 1.8f

/** Collapse-driven blur band. One GlassView because abutting panes cannot seam; its expanded height is learned from layout. */
private fun syncInfoFade(header: ViewGroup) {
    val h = header.height
    if (h <= 0) return
    val maxH = maxOf(infoHeaderMaxH[header] ?: 0, h)
    infoHeaderMaxH[header] = maxH
    val at = IntArray(2)
    header.getLocationOnScreen(at)

    // How far through the collapse we are: 0 at rest, 1 once the bar has finished shrinking.
    val progress = ((maxH - h).toFloat() /
        (maxH - header.dp(56f)).coerceAtLeast(1f)).coerceIn(0f, 1f)
    syncInfoTopPane(header, progress)

    // Fade the names out across the panel's bottom edge; alpha is only written while they cross it, so WhatsApp's crossfade survives.
    run {
        val lineY = (at[1] + h).toFloat()
        for (id in intArrayOf(infoTitleId, infoSubtitleId, infoGroupTitleId, infoGroupSubtitleId)) {
            if (id == 0) continue
            val v = header.rootView?.findViewById<View>(id) ?: continue
            if (v.height <= 0) continue
            v.getLocationOnScreen(infoGhostAt)
            val w = ((infoGhostAt[1] + v.height - lineY) / v.height).coerceIn(0f, 1f)
            if (w < 1f) {
                v.setTag(infoGhostTag, true)
                if (v.alpha != w) v.alpha = w
            } else if (v.getTag(infoGhostTag) != null) {
                v.setTag(infoGhostTag, null)
                v.alpha = 1f
            }
        }
    }
}

/** The covering pane at index 0 of header: over the list's content, under the bar's children, and header is the only host that stacks. */
private fun syncInfoTopPane(header: ViewGroup, progress: Float) {
    if (header.width <= 0 || header.height <= 0) return
    // Runs up over the status bar so it is the only pane in the region; two panes cannot seam at a join.
    header.getLocationOnScreen(infoTopAt)
    val lift = infoTopAt[1]
    // Unclip all the way up: the clip that matters sits several ancestors above header, and stopping short reads as an ignored margin.
    var anc: ViewGroup? = header
    var hops = 0
    while (anc != null && hops < 12) {
        if (anc.clipChildren) {
            anc.clipChildren = false
            logOnce("info pane: unclipped ${anc.javaClass.simpleName}")
        }
        if (anc.clipToPadding) anc.clipToPadding = false
        anc = anc.parent as? ViewGroup
        hops++
    }
    var pane = infoTopPaneRef?.get()
    if (pane == null || pane.parent !== header) {
        pane = GlassView(header.context).apply {
            backdrop = infoListRef?.get()
            underlay = wallpaperUnderlay(header)
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = header.dp(BLUR_DP * INFO_FADE_BLUR_BOOST)
                refractionEnabled = true
                bevelFraction = 0f
                bevelThickness = 1f
                depthRatio = DEPTH_RATIO
                maxDisplacePx = header.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                cornerRadius = 0f
                // Full-bleed header panel, same rule as the bands.
                rimEnabled = false
                tintColor = glassTintColor
            }
            setMaterialize(0f)
        }
        header.addView(
            pane, 0,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0),
        )
        infoTopPaneRef = WeakReference(pane)
        XposedBridge.log("[$TAG] info top pane inserted (lift=$lift)")
    }
    // Re-point the backdrop every sync, not only when null: after a revisit it is stale, not null, and a stale backdrop blurs nothing.
    infoListRef?.get()?.let { list -> if (pane.backdrop !== list) pane.backdrop = list }
    val want = header.height + lift
    val lp = pane.layoutParams as? FrameLayout.LayoutParams
    if (lp != null && (lp.height != want || lp.topMargin != -lift)) {
        lp.height = want
        lp.topMargin = -lift
        pane.layoutParams = lp
    }
    // The scroll scrubs the material itself, not an alpha over it: the glass thickens as the bar collapses.
    pane.setMaterialize(progress)
}

private val infoCardGlassTag = tagKey("wathemer-info-card-glass")

private val infoCardStackTag = tagKey("wathemer-info-card-stack")

private val infoCardClipTag = tagKey("wathemer-info-card-clip")

private val infoCardAt = IntArray(2)

internal var infoHeaderPlaceholderId = 0

internal var infoParticipantsId = 0

internal var infoMemberSheetId = 0

private var infoTailSeed = Float.NaN

private var infoTailSeedBottom = 0f

private var infoTailSeedL = 0f

private var infoTailSeedR = 0f

/** Half the gap between two stacked cards, applied at the top and bottom of each. */
private const val INFO_CARD_GAP_DP = 5f

private const val INFO_CARD_RADIUS_DP = 22f

/** One pane draws every section card, a background per card goes stale under scroll; hosted via a canStack walk, trailing list items unioned into their own card by collectInfoCardRects. */
internal fun infoCardGlass(card: View) {
    val stack = card.parent as? ViewGroup ?: return
    // The list, not the stack: the trailing action rows are separate list items that only attach on scroll.
    val list = stack.parent as? ViewGroup ?: return
    // Held for the header pill, which blurs this list: the cards pass under that bar, not the wallpaper.
    infoListRef = WeakReference(list)
    // Tag the stack before any early return, on every attach: an untagged rebuilt stack unions into one page-wide slab.
    stack.setTag(infoCardStackTag, true)
    if (list.getTag(infoCardGlassTag) != null) return
    // Tag before the post: isAttachedToWindow is still false in our attach callback, and an untagged second attach queues a second pane.
    list.setTag(infoCardGlassTag, true)
    list.post {
        runCatching { insertInfoCardPane(list) }
            .onFailure { XposedBridge.log("[$TAG] insertInfoCardPane threw: $it") }
    }
}

private fun insertInfoCardPane(stack: ViewGroup) {
    var host = stack.parent as? ViewGroup
    while (host != null && !canStack(host)) host = host.parent as? ViewGroup
    if (host == null) {
        logOnce("info cards: no stacking ancestor above the card stack")
        return
    }
    val d = stack.resources.displayMetrics.density
    val pane = GlassBubblePane(host.context).apply {
        params = GlassParams(d).apply {
            blurRadius = stack.dp(BLUR_DP)
            cornerRadius = stack.dp(INFO_CARD_RADIUS_DP)
            refractionEnabled = true
            bevelFraction = BEVEL_FRACTION
            depthRatio = DEPTH_RATIO
            maxDisplacePx = stack.dp(DISPLACE_DP)
            fresnelStrength = 0.5f
            tintColor = glassTintColor
        }
        tint = { glassTintColor }
        // Through this window's Activity; home's decor is the wrong screen here.
        backdrop = { bubbleBackdrop(stack) }
        placement = { bubblePlacement(stack) }
        dim = { 0f }
        rimColor = glassTint(BUBBLE_RIM_ALPHA)
        rimWidth = d
        collect = { out -> collectInfoCardRects(stack, out) }
        onGeometryChanged = { markWallpaperGeometryDirty() }
    }
    host.addView(
        pane, 0,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ),
    )
    XposedBridge.log("[$TAG] info card glass inserted into ${host.javaClass.simpleName}")
}

private val memberPaneTag = tagKey("wathemer-member-pane")

/** Inserted between the page and the sheet, the only layer that works; the sheet is resolved by id every frame so no reference goes stale. */
internal fun memberSheetPane(sheet: View) {
    val container = sheet.parent as? ViewGroup ?: return
    val host = container.parent as? ViewGroup ?: return
    if (!canStack(host)) {
        logOnce("member pane: ${host.javaClass.simpleName} does not stack")
        return
    }
    if (host.getTag(memberPaneTag) != null) return
    host.setTag(memberPaneTag, true)
    host.post {
        runCatching {
            val d = host.resources.displayMetrics.density
            val pane = GlassBubblePane(host.context).apply {
                params = GlassParams(d).apply {
                    blurRadius = host.dp(BLUR_DP)
                    cornerRadius = host.dp(INFO_CARD_RADIUS_DP)
                    refractionEnabled = true
                    bevelFraction = BEVEL_FRACTION
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = host.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                    tintColor = glassTintColor
                }
                tint = { glassTintColor }
                // Through the sheet's own Activity, never home's decor. See [insertInfoCardPane].
                backdrop = { bubbleBackdrop(sheet) }
                placement = { bubblePlacement(sheet) }
                dim = { 0f }
                rimColor = glassTint(BUBBLE_RIM_ALPHA)
                rimWidth = d
                collect = { out -> collectMemberSheetRect(host, out) }
                onGeometryChanged = { markWallpaperGeometryDirty() }
            }
            val at = host.indexOfChild(container).coerceAtLeast(0)
            host.addView(
                pane, at,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            XposedBridge.log("[$TAG] member pane inserted at $at of ${host.javaClass.simpleName}")
        }.onFailure { XposedBridge.log("[$TAG] member pane insert threw: $it") }
    }
}

private fun collectMemberSheetRect(host: ViewGroup, out: RectList) {
    if (!host.hasWindowFocus()) return
    if (infoMemberSheetId == 0) return
    val sheet = host.rootView?.findViewById<View>(infoMemberSheetId) ?: return
    if (!sheet.isShown || sheet.height <= 0 || sheet.width <= 0) return
    val insetX = host.dp(CARD_INSET_DP)
    val insetY = host.dp(INFO_CARD_GAP_DP)
    sheet.getLocationOnScreen(infoCardAt)
    out.add(
        infoCardAt[0] + insetX,
        infoCardAt[1] + insetY,
        infoCardAt[0] + sheet.width - insetX,
        infoCardAt[1] + sheet.height - insetY,
    )
}

/** One rect per section card: header_placeholder merges into the card below, trailing action rows union into one; nothing is keyed on which cards exist. */
private fun collectInfoCardRects(list: ViewGroup, out: RectList) {
    // Draw nothing without window focus: the contact popup is a second window, and lit glass burns straight through its scrim.
    if (!list.hasWindowFocus()) return
    val insetX = list.dp(CARD_INSET_DP)
    val insetY = list.dp(INFO_CARD_GAP_DP)
    var tailL = 0f
    var tailR = 0f
    var tailTop = Float.NaN
    var tailBottom = Float.NaN
    for (i in 0 until list.childCount) {
        val child = list.getChildAt(i) ?: continue
        if (!child.isShown || child.height <= 0 || child.width <= 0) continue
        if (child.getTag(infoCardStackTag) != null) {
            collectStackCards(child as? ViewGroup ?: continue, out, insetX, insetY)
            // Take the participants seed this frame, not the next: a frame late trails the fling.
            if (!infoTailSeed.isNaN()) {
                tailTop = infoTailSeed
                tailBottom = infoTailSeedBottom
                tailL = infoTailSeedL
                tailR = infoTailSeedR
                infoTailSeed = Float.NaN
            }
            continue
        }
        child.getLocationOnScreen(infoCardAt)
        if (tailTop.isNaN()) {
            tailTop = infoCardAt[1] + insetY
            tailL = infoCardAt[0] + insetX
            tailR = infoCardAt[0] + child.width - insetX
        }
        tailBottom = (infoCardAt[1] + child.height - insetY)
    }
    if (!tailTop.isNaN() && tailBottom > tailTop) out.add(tailL, tailTop, tailR, tailBottom)
}

/** A block whose visible content is nothing taller than a hairline is a divider; a card there is a floating sliver. */
private fun isHairlineOnly(v: View): Boolean {
    val g = v as? ViewGroup ?: return false
    val lim = g.dp(2f)
    for (i in 0 until g.childCount) {
        val c = g.getChildAt(i) ?: continue
        if (!c.isShown || c.width <= 0 || c.height <= 0) continue
        if (c.height > lim) return false
    }
    return true
}

private fun collectStackCards(stack: ViewGroup, out: RectList, insetX: Float, insetY: Float) {
    var mergedTop = Float.NaN
    for (i in 0 until stack.childCount) {
        val card = stack.getChildAt(i) ?: continue
        if (!card.isShown || card.height <= 0 || card.width <= 0) continue
        // An id-less container is a wrapper, not a card: group info nests cards a level deeper, so descend through it.
        if (card.id == View.NO_ID && card is ViewGroup && card.childCount > 0) {
            collectStackCards(card, out, insetX, insetY)
            continue
        }
        // The shortcuts gap holds only a hairline, and a card inset into it reads as a floating sliver.
        if (isHairlineOnly(card)) continue
        card.getLocationOnScreen(infoCardAt)
        // The participants block reads as one panel: from this card down, everything joins the tail union.
        if (infoParticipantsId != 0 && card.id == infoParticipantsId) {
            infoTailSeed = infoCardAt[1] + insetY
            infoTailSeedL = infoCardAt[0] + insetX
            infoTailSeedR = infoCardAt[0] + card.width - insetX
            // Bound the tail by its visible children, not the stack, which is the whole page; an oversize tail shows through the contact popup.
            var bottom = infoCardAt[1] + card.height
            for (j in i + 1 until stack.childCount) {
                val rest = stack.getChildAt(j) ?: continue
                if (!rest.isShown || rest.height <= 0 || rest.width <= 0) continue
                rest.getLocationOnScreen(infoCardAt)
                val b = infoCardAt[1] + rest.height
                if (b > bottom) bottom = b
            }
            infoTailSeedBottom = bottom - insetY
            return
        }
        if (infoHeaderPlaceholderId != 0 && card.id == infoHeaderPlaceholderId) {
            // Hold its top for the next card, so the avatar and its name share one surface.
            mergedTop = infoCardAt[1] + insetY
            continue
        }
        val top = if (mergedTop.isNaN()) infoCardAt[1] + insetY else mergedTop
        mergedTop = Float.NaN
        roundToPane(card, insetX, insetY)
        out.add(
            infoCardAt[0] + insetX,
            top,
            infoCardAt[0] + card.width - insetX,
            infoCardAt[1] + card.height - insetY,
        )
    }
}

/** Clip the card to the pane's rect so edge-to-edge content like the media strip cannot overhang it. */
private fun roundToPane(card: View, insetX: Float, insetY: Float) {
    val want = card.getTag(infoCardClipTag) as? PaneRound
    if (want == null) {
        val p = PaneRound(insetX, insetY, card.dp(INFO_CARD_RADIUS_DP))
        card.setTag(infoCardClipTag, p)
        card.outlineProvider = p
        card.clipToOutline = true
        card.invalidateOutline()
        return
    }
    // Runs every frame; the invalidate must stay gated on a size change or it self-sustains a redraw loop.
    if (want.needsRebuild(card.width, card.height)) card.invalidateOutline()
}
