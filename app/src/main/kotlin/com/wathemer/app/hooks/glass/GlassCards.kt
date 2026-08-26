// The cards: one rounded pane per page, sized to the list it wraps and clipped to the same rect.
// Every edge is re-derived each pass from the list's own padding box, never snapshotted.
package com.wathemer.app.hooks.glass

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.wathemer.app.glass.GlassView
import com.wathemer.app.hooks.WaIds
import com.wathemer.app.hooks.waId
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

private var listPanelRef: WeakReference<View>? = null

/** The card's host: conversations_coordinator_layout, inside the pager so the card pages; not id/content. */
internal var cardHostRef: WeakReference<ViewGroup>? = null

// ── The updates page ──────────────────────────────────────────────────────────────
// Its own refs: the page's shape differs from Chats, and the host measures as a FrameLayout in both tab states.
internal var updatesHostRef: WeakReference<ViewGroup>? = null

internal var updatesListRef: WeakReference<View>? = null

private var updatesPanelRef: WeakReference<View>? = null

private var updatesTitleRef: WeakReference<View>? = null

private val updatesTitleTag = tagKey("wathemer-updates-title")

/** The chat-list card, at index 0 of the page's own coordinator so it travels with the page; Chats-only, geometry in [syncListCard]. */
internal fun injectListPanel(parent: ViewGroup) {
    if (listPanelRef?.get()?.parent === parent) return
    if (!parent.isAttachedToWindow) return

    val glass = GlassView(parent.context).apply {
        underlay = wallpaperUnderlay(parent)
        params.apply {
            downsample = DOWNSAMPLE
            blurRadius = parent.dp(BLUR_DP)
            cornerRadius = parent.dp(CARD_RADIUS_DP)
            refractionEnabled = true
            bevelFraction = BEVEL_FRACTION
            depthRatio = DEPTH_RATIO
            maxDisplacePx = parent.dp(DISPLACE_DP)
            fresnelStrength = 0.5f
            tintColor = glassTintColor
        }
    }
    // Zero-sized until syncListCard measures; plain MarginLayoutParams keeps out a compile-time coordinatorlayout dependency.
    parent.addView(glass, 0, ViewGroup.MarginLayoutParams(0, 0))
    listPanelRef = WeakReference(glass)
    syncListCard()
    XposedBridge.log("[$TAG] list card inserted at index 0 of ${parent.javaClass.simpleName}")
}

private val cardRect = Rect()

private var loggedHostMismatch = false

/** Shared card recipe for four surfaces; Chats is NOT one of them, its twin lives in [injectListPanel] and gets left behind. */
internal fun newCardGlass(parent: ViewGroup): GlassView = GlassView(parent.context).apply {
    underlay = wallpaperUnderlay(parent)
    params.apply {
        downsample = DOWNSAMPLE
        blurRadius = parent.dp(BLUR_DP)
        cornerRadius = parent.dp(CARD_RADIUS_DP)
        refractionEnabled = true
        bevelFraction = BEVEL_FRACTION
        depthRatio = DEPTH_RATIO
        maxDisplacePx = parent.dp(DISPLACE_DP)
        fresnelStrength = 0.5f
        tintColor = glassTintColor
    }
}

/** Updates card and big title in the page's FrameLayout; it stacks, so the list is moved down by paddingTop in [syncUpdatesCard]. */
internal fun injectUpdatesCard(host: ViewGroup) {
    if (host.getTag(updatesTitleTag) != null) return
    if (!host.isAttachedToWindow) return
    host.setTag(updatesTitleTag, true)

    val glass = newCardGlass(host)
    host.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
    updatesPanelRef = WeakReference(glass)

    val ctx = host.context
    val tv = TextView(ctx).apply {
        text = navLabel(1) ?: "Updates"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
        typeface = Typeface.create(
            Typeface.DEFAULT, Typeface.BOLD,
        )
        setTextColor(primaryTextColor(ctx))
        includeFontPadding = false
        maxLines = 1
        setPadding(
            host.dp(16f).toInt(), host.dp(2f).toInt(),
            host.dp(16f).toInt(), host.dp(10f).toInt(),
        )
    }
    host.addView(
        tv,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
    )
    updatesTitleRef = WeakReference(tv)
    XposedBridge.log("[$TAG] updates card + title '${tv.text}' inserted")
}

/** Title clearance is header.height + 4dp to match the Chats title, and every clip edge is the list's own padding box. */
internal fun syncUpdatesCard() {
    val host = updatesHostRef?.get() ?: return
    val glass = updatesPanelRef?.get() as? GlassView ?: return
    val list = updatesListRef?.get() ?: return
    val title = updatesTitleRef?.get() ?: return
    val header = headerRef?.get() ?: return
    if (host.width <= 0 || host.height <= 0 || header.height <= 0) return
    val inset = bottomInset
    if (inset <= 0) return

    val d = host.resources.displayMetrics.density
    val side = (CARD_INSET_DP * d).toInt()
    val gap = (CARD_GAP_DP * d).toInt()

    // Captured before anything below mutates, and used by both mutation sites.
    val wasAtTop = !list.canScrollVertically(-1)

    val clearance = header.height + (4f * d).toInt()
    val tlp = title.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    if (tlp.topMargin != clearance) {
        tlp.topMargin = clearance
        title.layoutParams = tlp
        // The title is re-created on every visit, so this branch is what re-introduced the offset each time.
        if (wasAtTop) repinToTop(list)
        return              // re-enter once the title has been laid out at its new place
    }
    if (title.height <= 0) return

    val top = clearance + title.height + gap
    val bottom = host.height - inset - gap
    val right = host.width - side
    if (bottom - top < gap || right - side < gap) return

    // Padding first, because the clip below reads it. The pad is the content's breathing room inside the border, equal on all four sides.
    val pad = (CARD_CONTENT_PAD_DP * d).toInt()
    val padBottom = host.height - bottom + pad
    if (list.paddingLeft != side + pad || list.paddingTop != top + pad ||
        list.paddingRight != side + pad || list.paddingBottom != padBottom
    ) {
        list.setPadding(side + pad, top + pad, side + pad, padBottom)
        if (wasAtTop) repinToTop(list)
    }

    val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
    if (lp.width != right - side || lp.height != bottom - top ||
        lp.leftMargin != side || lp.topMargin != top
    ) {
        lp.width = right - side
        lp.height = bottom - top
        lp.leftMargin = side
        lp.topMargin = top
        glass.layoutParams = lp
        XposedBridge.log("[$TAG] updates card $side,$top-$right,$bottom")
    }

    // The pad comes back off so the clip lands on the border while the content sits inside it.
    val cl = list.paddingLeft - pad
    val ct = list.paddingTop - pad
    val cr = list.width - list.paddingRight + pad
    val cb = list.height - list.paddingBottom + pad
    val provider = list.outlineProvider as? CardOutline
    if (provider == null) {
        list.outlineProvider = CardOutline(cl, ct, cr, cb, CARD_RADIUS_DP * d)
        list.clipToOutline = true
        list.invalidateOutline()
    } else if (provider.set(cl, ct, cr, cb, CARD_RADIUS_DP * d)) {
        // Gated on a real move: an unconditional invalidateOutline from a pre-draw path schedules the next frame forever.
        list.invalidateOutline()
    }
}

/** Size the card and clip the list to the same rect; runs on every global layout, so every write is guarded to stay idempotent. */
internal fun syncListCard() {
    val host = cardHostRef?.get() ?: return
    val glass = listPanelRef?.get() as? GlassView ?: return
    val list = listRef?.get() ?: return
    if (host.width <= 0 || host.height <= 0) return
    if (list.width <= 0 || list.height <= 0) return
    // Archived inflates this coordinator too and has no floating nav, so the inset is taken only when the nav is in THIS window.
    // Asked of the window structurally, never of an Activity name or of install order.
    val navInWindow = navContainerIdPin != 0 &&
        host.rootView?.findViewById<View>(navContainerIdPin) != null
    val inset = if (navInWindow) bottomInset else 0
    if (navInWindow && inset <= 0) return          // nav is here but not floated yet

    val d = host.resources.displayMetrics.density
    val side = (CARD_INSET_DP * d).toInt()
    val gap = (CARD_GAP_DP * d).toInt()

    // The list's origin in its own page, so this never crosses the pager boundary that cut the avatars off.
    val r = cardRect
    r.set(0, 0, list.width, list.height)
    if (!runCatching { host.offsetDescendantRectToMyCoords(list, r) }.isSuccess) return
    val listTop = r.top

    // bottomInset is measured in id/content; the page shares its bounds, and this logs once if that ever stops holding.
    val content = contentRef?.get()
    if (content != null && content.height != host.height && !loggedHostMismatch) {
        loggedHostMismatch = true
        XposedBridge.log(
            "[$TAG] card host height ${host.height} != id/content ${content.height}; " +
                "the card's bottom edge assumes they match"
        )
    }

    // paddingTop already carries the content pad (syncListExtension owns it), so take it back off to land on the border.
    val pad = (CARD_CONTENT_PAD_DP * d).toInt()
    val top = listTop + list.paddingTop - pad
    val bottom = host.height - inset - gap
    val right = host.width - side
    if (bottom - top < gap || right - side < gap) return

    val lp = glass.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    if (lp.width != right - side || lp.height != bottom - top ||
        lp.leftMargin != side || lp.topMargin != top
    ) {
        lp.width = right - side
        lp.height = bottom - top
        lp.leftMargin = side
        lp.topMargin = top
        glass.layoutParams = lp
        XposedBridge.log("[$TAG] list card $side,$top-$right,$bottom (r=${(CARD_RADIUS_DP * d).toInt()})")
    }

    // Inset the rows to the card; without the horizontal padding the clip slices the avatars.
    val padBottom = host.height - bottom + pad
    if (list.paddingLeft != side + pad || list.paddingRight != side + pad || list.paddingBottom != padBottom) {
        list.setPadding(side + pad, list.paddingTop, side + pad, padBottom)
    }

    // ── The clip is the list's own padding box, and nothing else ──────────────────
    // Derived any other way it crosses the pager boundary, sticks (child offsets skip layout), and cut the avatars off.
    // The pad comes back off so the clip lands on the card's border while the content sits inside it.
    val cl = list.paddingLeft - pad
    val ct = list.paddingTop - pad
    val cr = list.width - list.paddingRight + pad
    val cb = list.height - list.paddingBottom + pad
    val provider = list.outlineProvider as? CardOutline
    if (provider == null) {
        list.outlineProvider = CardOutline(cl, ct, cr, cb, CARD_RADIUS_DP * d)
        list.clipToOutline = true
        list.invalidateOutline()
    } else if (provider.set(cl, ct, cr, cb, CARD_RADIUS_DP * d)) {
        // Gated on a real move: an unconditional invalidateOutline from a pre-draw path schedules the next frame forever.
        list.invalidateOutline()
    }
}

// ── Folder pages that reuse the chat-list layout ───────────────────────────────────
// Archived carries conversations_coordinator_layout but no header, nav or big title, so it takes its own card.
// Its own refs too, so cardHostRef never follows the user off the home screen.
private var folderHostRef: WeakReference<ViewGroup>? = null

private var folderPanelRef: WeakReference<GlassView>? = null

private val folderCardTag = tagKey("wathemer-folder-card")

internal fun injectFolderCard(host: ViewGroup) {
    if (host.getTag(folderCardTag) != null) return
    host.setTag(folderCardTag, true)
    val glass = newCardGlass(host)
    host.addView(glass, 0, ViewGroup.MarginLayoutParams(0, 0))
    folderHostRef = WeakReference(host)
    folderPanelRef = WeakReference(glass)
    // The home hub never fires here, so this card drives itself or it is sized once at attach and never again.
    host.viewTreeObserver.addOnGlobalLayoutListener { runCatching { syncFolderCard() } }
    XposedBridge.log("[$TAG] folder card inserted into ${host.javaClass.simpleName}")
}

/** One gap in from every side of the host. The list is resolved by id each pass, never remembered. */
private fun syncFolderCard() {
    val host = folderHostRef?.get() ?: return
    val glass = folderPanelRef?.get() ?: return
    if (host.width <= 0 || host.height <= 0) return
    runCatching { ensureFolderHeader(host, "archived") }
    val list = host.findViewById<View>(android.R.id.list) ?: return
    if (list.width <= 0 || list.height <= 0) return

    val d = host.resources.displayMetrics.density
    val side = (CARD_INSET_DP * d).toInt()
    val gap = (CARD_GAP_DP * d).toInt()
    val pad = (CARD_CONTENT_PAD_DP * d).toInt()

    // The list fills the host whatever it holds, so the card hugs the rows' ink, clamped to the host's band.
    inkUnion.setEmpty()
    (list as? ViewGroup)?.let { g ->
        for (i in 0 until g.childCount) collectInk(g.getChildAt(i), 1)
    }
    if (inkUnion.isEmpty) {
        if (glass.visibility != View.GONE) glass.visibility = View.GONE
        return
    }
    if (glass.visibility != View.VISIBLE) glass.visibility = View.VISIBLE
    host.getLocationOnScreen(hostAt)

    // Scrollable takes the viewport box so the clip can be the card; short content keeps the hug.
    val scrollable = list.canScrollVertically(1) || list.canScrollVertically(-1)
    val top =
        if (scrollable) gap else maxOf(gap, inkUnion.top - hostAt[1] - pad)
    var bottom =
        if (scrollable) host.height - gap
        else minOf(host.height - gap, inkUnion.bottom - hostAt[1] + pad)
    // The same three-quarters snap as the content cards: almost-full reads as cut off mid air.
    if (!scrollable && (bottom - top) * 4 >= (host.height - gap - top) * 3) {
        bottom = host.height - gap
    }
    val right = host.width - side
    if (bottom - top < gap || right - side < gap) return

    // Card edges in the list's own coordinates, then the content pad on top of each.
    val padL = (side - list.left).coerceAtLeast(0) + pad
    val padT = (top - list.top).coerceAtLeast(0) + pad
    val padR = (list.left + list.width - right).coerceAtLeast(0) + pad
    if (list.paddingLeft != padL || list.paddingTop != padT || list.paddingRight != padR) {
        // Before the mutation: growing paddingTop leaves the list scrolled by what was added.
        val wasAtTop = !list.canScrollVertically(-1)
        (list as? ViewGroup)?.clipToPadding = false
        list.setPadding(padL, padT, padR, list.paddingBottom)
        if (wasAtTop) repinToTop(list)
    }

    val lp = glass.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    if (lp.width != right - side || lp.height != bottom - top ||
        lp.leftMargin != side || lp.topMargin != top
    ) {
        lp.width = right - side
        lp.height = bottom - top
        lp.leftMargin = side
        lp.topMargin = top
        glass.layoutParams = lp
        XposedBridge.log("[$TAG] folder card $side,$top-$right,$bottom")
    }

    // The clip IS the card rect in the list's coordinates, all four edges.
    val cl = side - list.left
    val ct = top - list.top
    val cr = right - list.left
    val cb = bottom - list.top
    val provider = list.outlineProvider as? CardOutline
    if (provider == null) {
        list.outlineProvider = CardOutline(cl, ct, cr, cb, CARD_RADIUS_DP * d)
        list.clipToOutline = true
        list.invalidateOutline()
    } else if (provider.set(cl, ct, cr, cb, CARD_RADIUS_DP * d)) {
        list.invalidateOutline()
    }
}

// ── List pages whose own root cannot host a pane ───────────────────────────────────
// None of their roots stack, so android.R.id.content hosts: a later sibling of the wallpaper, its index 0 sits above it and below the page.
// A framework id, not a WhatsApp one, so it is looked up directly and never through waId.
private val contentCards = WeakHashMap<View, GlassView>()

private val contentCardTag = tagKey("wathemer-content-card")

private val contentCardRect = Rect()

private val inkUnion = Rect()

private val inkAt = IntArray(2)

internal val hostAt = IntArray(2)

/** Screen-px union of everything under [v] that actually paints; a group that only holds children is not ink.
 *  A transparent background is not ink either: the wallpaper protocol installs ColorDrawable(0) all over this tree. */
private fun collectInk(v: View, depth: Int) {
    // GONE, not "other than VISIBLE": an INVISIBLE view reserves its box and turning it on is no layout.
    if (v.visibility == View.GONE || v.width <= 0 || v.height <= 0) return
    val group = v as? ViewGroup
    if (group != null && depth < 6 && group.childCount > 0) {
        for (i in 0 until group.childCount) collectInk(group.getChildAt(i), depth + 1)
        if (!paintsSomething(v)) return
    }
    v.getLocationOnScreen(inkAt)
    inkUnion.union(inkAt[0], inkAt[1], inkAt[0] + v.width, inkAt[1] + v.height)
}

private fun paintsSomething(v: View): Boolean {
    val bg = v.background ?: return false
    if (bg is ColorDrawable && (bg.color ushr 24) == 0) return false
    return bg.alpha != 0
}

/** frameCard is for grids that own their geometry: the VIEW is inset by margins and the card wraps its frame, so nothing inside is ever mutated. */
internal fun injectContentCard(list: View, label: String, frameCard: Boolean = false) {
    if (list.getTag(contentCardTag) != null) return
    val host = list.rootView?.findViewById<ViewGroup>(android.R.id.content) ?: run {
        logOnce("$label: no android.R.id.content in this window, no card")
        return
    }
    if (!canStack(host)) {
        logOnce("$label: content is ${host.javaClass.simpleName}, which does not stack, no card")
        return
    }
    // A page painting its own full-size background buries a card at index 0; glass under an opaque page is work with no pixels.
    var anc: View? = list
    while (anc != null && anc !== host) {
        if (paintsSomething(anc) && anc.height >= host.height / 2 &&
            (anc.background !is ColorDrawable)
        ) {
            logOnce("$label paints its own background (${anc.javaClass.simpleName}), no card")
            return
        }
        anc = anc.parent as? View
    }
    list.setTag(contentCardTag, true)
    val glass = newCardGlass(host)
    host.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
    contentCards[list] = glass
    if (!frameCard) {
        (list as? ViewGroup)?.clipToPadding = false
        // A ListView's own divider reads as a seam over glass; transparent rather than null keeps the spacing.
        (list as? ListView)?.let { lv ->
            val h = lv.dividerHeight
            lv.divider = ColorDrawable(0)
            lv.dividerHeight = h
        }
    }
    val ref = WeakReference(list)
    host.viewTreeObserver.addOnGlobalLayoutListener {
        runCatching { ref.get()?.let { syncContentCard(it, label, frameCard) } }
    }
    if (frameCard) {
        // The appbar collapses because the grid REPORTS its scroll to it; cut the link and it cannot.
        list.isNestedScrollingEnabled = false
        runCatching { pinGalleryAppbar(host, label) }
        // Nothing in a pager page stacks, so the card follows the grid's live screen X from pre-draw: two reads at rest, a write on swipe.
        val glassRef = WeakReference(glass)
        val obs = host.viewTreeObserver
        val gAt = IntArray(2)
        val hAt = IntArray(2)
        obs.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val grid = ref.get()
                val gl = glassRef.get()
                if (grid == null || gl == null || gl.parent == null) {
                    (if (obs.isAlive) obs else host.viewTreeObserver)
                        .removeOnPreDrawListener(this)
                    return true
                }
                if (!grid.isAttachedToWindow || gl.visibility != View.VISIBLE) return true
                val fg = (gl.getTag(frameGapTag) as? Int) ?: 0
                grid.getLocationOnScreen(gAt)
                host.getLocationOnScreen(hAt)
                val tx = (gAt[0] - fg - (hAt[0] + gl.left)).toFloat()
                if (abs(tx - gl.translationX) > 0.5f) gl.translationX = tx
                return true
            }
        })
    }
    XposedBridge.log("[$TAG] $label card inserted into ${host.javaClass.simpleName}")
}

private val frameGapTag = tagKey("wathemer-frame-gap")

private val appbarPinTag = tagKey("wathemer-appbar-pin")

/** The grid's own spacing, so the card's breathing room matches what sits between two thumbnails. */
private fun gridItemGap(grid: View, d: Float): Int {
    val g = grid as? ViewGroup ?: return (3f * d).toInt()
    var best = -1
    val cap = (24f * d).toInt()
    for (i in 0 until g.childCount) {
        val a = g.getChildAt(i) ?: continue
        for (j in i + 1 until g.childCount) {
            val b = g.getChildAt(j) ?: continue
            if (a.top == b.top && b.left >= a.right) {
                val gp = b.left - a.right
                if (gp in 1..cap && (best < 0 || gp < best)) best = gp
            }
        }
    }
    return if (best > 0) best else (3f * d).toInt()
}

/** Stock scrolls the gallery header under the status bar; pinned so it stays readable. */
private fun pinGalleryAppbar(host: ViewGroup, label: String) {
    val appbarId = host.resources.waId("appbar", host.context.packageName)
    val appbar =
        (if (appbarId != 0) host.rootView?.findViewById<ViewGroup>(appbarId) else null) ?: return
    if (appbar.getTag(appbarPinTag) != null) return
    appbar.setTag(appbarPinTag, true)
    var flagged = 0
    for (i in 0 until appbar.childCount) {
        val c = appbar.getChildAt(i) ?: continue
        // Reflective: AppBarLayout.LayoutParams is Material, which this module does not link.
        if (runCatching { XposedHelpers.callMethod(c.layoutParams, "setScrollFlags", 0) }.isSuccess) {
            flagged++
        }
    }
    appbar.requestLayout()
    // The count tells R8's verdict apart from a working pin; nested-scroll-off is the real stop.
    logOnce("$label appbar pin: $flagged/${appbar.childCount} flags cleared")
}

private val pageCardWatchTag = tagKey("wathemer-page-card-watch")

/** Pages that load their list async miss the one post; watch layouts until it shows, bounded. */
internal fun watchForPageScroller(content: ViewGroup, label: String) {
    if (content.getTag(pageCardWatchTag) != null) return
    content.setTag(pageCardWatchTag, true)
    val obs = content.viewTreeObserver
    obs.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
        private var tries = 0
        override fun onGlobalLayout() {
            val done = runCatching {
                findPageScroller(content)?.let { injectContentCard(it, label); true } ?: false
            }.getOrDefault(false)
            tries++
            if (done || tries >= 40) {
                // Remove through the observer captured at registration; a dead one silently no-ops.
                (if (obs.isAlive) obs else content.viewTreeObserver)
                    .removeOnGlobalLayoutListener(this)
                if (!done) logOnce("$label: no scroller found after $tries layouts, no card")
            }
        }
    })
}

/** By name up the chain: WhatsApp's androidx copies live in another classloader, so `is` cannot see them. */
private fun isAppScroller(v: View): Boolean {
    var c: Class<*>? = v.javaClass
    while (c != null) {
        when (c.name) {
            "androidx.recyclerview.widget.RecyclerView",
            "androidx.core.widget.NestedScrollView" -> return true
        }
        c = c.superclass
    }
    return false
}

/** The page's main scroller: the first shown vertical scrolling container of real height. */
internal fun findPageScroller(content: ViewGroup): View? {
    val queue = ArrayDeque<View>()
    queue.add(content)
    var depth = 0
    while (queue.isNotEmpty() && depth < 400) {
        depth++
        val v = queue.removeFirst()
        if (v !== content && v.isShown && v.height > content.height / 2 && (
                v is ScrollView ||
                    v is AbsListView ||
                    isAppScroller(v)
                )
        ) {
            return v
        }
        (v as? ViewGroup)?.let { g -> for (i in 0 until g.childCount) queue.add(g.getChildAt(i)) }
    }
    return null
}

/** True when this row shows an icon, so its indent is earning the space it takes. */
private fun rowHasIcon(v: View, depth: Int): Boolean {
    if (v is ImageView && v.visibility == View.VISIBLE) return true
    if (depth >= 2 || v !is ViewGroup) return false
    for (i in 0 until v.childCount) {
        if (rowHasIcon(v.getChildAt(i) ?: continue, depth + 1)) return true
    }
    return false
}

/** Iconless settings rows still indent to the icon column, far right of their own heading. */
private fun alignIconlessRows(v: View, gutter: Int, want: Int, depth: Int) {
    val g = v as? ViewGroup ?: return
    for (i in 0 until g.childCount) {
        val row = g.getChildAt(i) ?: continue
        if (row is ViewGroup && row.paddingLeft >= gutter && !rowHasIcon(row, 0)) {
            row.setPadding(want, row.paddingTop, row.paddingRight, row.paddingBottom)
        } else if (depth < 3) {
            // Rows sit three deep here, and a two level walk found none of them.
            alignIconlessRows(row, gutter, want, depth + 1)
        }
    }
}

/** Wraps the list itself, so an empty page whose list is GONE gets no slab. */
private fun syncContentCard(list: View, label: String, frameCard: Boolean = false) {
    val glass = contentCards[list] ?: return
    val host = glass.parent as? ViewGroup ?: return
    if (host.width <= 0 || host.height <= 0) return
    // Before the empty early-out: the header band belongs to the window, not to the list's content.
    runCatching { ensureFolderHeader(host, label) }
    // The list is GONE on an empty Starred or Broadcast page; a card behind nothing is a blank slab.
    if (!list.isShown || list.width <= 0 || list.height <= 0) {
        if (glass.visibility != View.GONE) glass.visibility = View.GONE
        return
    }
    if (glass.visibility != View.VISIBLE) glass.visibility = View.VISIBLE

    // The frame card insets the VIEW, never its insides: headers and rows move together, so the sticky copy cannot drift.
    // The card then CONTAINS the frame by the grid's own item gap, so thumbnails breathe off its border.
    var frameGap = 0
    if (frameCard) {
        val dd = host.resources.displayMetrics.density
        frameGap = gridItemGap(list, dd)
        glass.setTag(frameGapTag, frameGap)
        val want = (CARD_INSET_DP * dd).toInt() + frameGap
        val mlp = list.layoutParams as? ViewGroup.MarginLayoutParams
        if (mlp != null && (mlp.leftMargin != want || mlp.rightMargin != want)) {
            mlp.leftMargin = want
            mlp.rightMargin = want
            list.layoutParams = mlp
            return    // re-syncs on the layout this causes, with the frame settled
        }
    }

    val r = contentCardRect
    r.set(0, 0, list.width, list.height)
    if (!runCatching { host.offsetDescendantRectToMyCoords(list, r) }.isSuccess) return
    // The mapping subtracts the view's OWN scroll, so a scrolled ScrollView lands the card off screen; child-scrolling lists keep scrollY 0.
    r.offset(list.scrollX, list.scrollY)

    val d = host.resources.displayMetrics.density
    val side = (CARD_INSET_DP * d).toInt()
    val gap = (CARD_GAP_DP * d).toInt()
    val pad = (CARD_CONTENT_PAD_DP * d).toInt()

    // A row is often a full-height container with a picture in the middle, so its box is not its content; union what the rows ink.
    // From the ROWS, not the list: the list fills its viewport, so starting there is a slab.
    inkUnion.setEmpty()
    (list as? ViewGroup)?.let { g ->
        for (i in 0 until g.childCount) collectInk(g.getChildAt(i), 1)
    }
    if (inkUnion.isEmpty) {                        // nothing laid out yet, or nothing to card
        if (glass.visibility != View.GONE) glass.visibility = View.GONE
        return
    }
    host.getLocationOnScreen(hostAt)
    val inkTop = inkUnion.top - hostAt[1]
    val inkBottom = inkUnion.bottom - hostAt[1]

    // A scrollable page takes the viewport box: an ink-tied top cannot be the clip, and ink chased through padding is a runaway recurrence.
    // Short content keeps the hug; nothing scrolls, so nothing can leak.
    val scrollable = list.canScrollVertically(1) || list.canScrollVertically(-1)
    // The frame card wraps AROUND the inset frame; the list card sits INSIDE the full-width box.
    val l = if (frameCard) r.left - frameGap else r.left + side
    val right = if (frameCard) r.right + frameGap else r.right - side
    val t = when {
        frameCard -> maxOf(gap, r.top - frameGap)
        scrollable -> r.top + gap
        else -> maxOf(r.top + gap, inkTop - pad)
    }
    val full = if (frameCard) r.bottom + frameGap else r.bottom - gap
    var bottom = when {
        frameCard && scrollable -> full
        frameCard -> inkBottom + frameGap
        scrollable -> full
        else -> minOf(full, inkBottom + pad)
    }
    // A bottom edge just short of the viewport reads as cut off mid air, so a hug filling three quarters of the box snaps to the full box.
    if (!scrollable && (bottom - t) * 4 >= (full - t) * 3) bottom = full
    // The gallery grid runs taller than the window, and a card ending off screen has no bottom edge.
    if (frameCard) bottom = minOf(bottom, host.height - gap)
    if (right - l < gap || bottom - t < gap) return

    // Horizontal padding puts the rows inside the card; none on the bottom, where the card ends at the rows and padding would chase itself.
    // Not on a frame-carded grid: the gallery lays its own edges, and the write doubled its sticky headers.
    if (!frameCard &&
        (list.paddingLeft != side + pad || list.paddingTop != gap + pad ||
            list.paddingRight != side + pad)
    ) {
        // Before the mutation: growing paddingTop leaves the list scrolled by what was added.
        val wasAtTop = !list.canScrollVertically(-1)
        list.setPadding(side + pad, gap + pad, side + pad, list.paddingBottom)
        if (wasAtTop) repinToTop(list)
    }
    if (!frameCard) runCatching {
        val den = list.resources.displayMetrics.density
        alignIconlessRows(list, (64f * den).toInt(), (24f * den).toInt(), 0)
    }

    // Margins measure from the host's padding box, which carries the status bar inset, so a margin of t alone lands a status bar too low.
    val ml = l - host.paddingLeft
    val mt = t - host.paddingTop
    val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
    if (lp.width != right - l || lp.height != bottom - t ||
        lp.leftMargin != ml || lp.topMargin != mt
    ) {
        lp.width = right - l
        lp.height = bottom - t
        lp.leftMargin = ml
        lp.topMargin = mt
        glass.layoutParams = lp
        XposedBridge.log("[$TAG] $label card $l,$t-$right,$bottom (margin $ml,$mt)")
    }

    // The frame card CONTAINS the frame, so the view's own bounds cannot out-draw it; no clip.
    if (frameCard) return

    // The clip IS the card rect in the list's coordinates, all four edges; derived any other way it drifts off the card.
    val cl = l - r.left
    val ct = t - r.top
    val cr = right - r.left
    val cb = bottom - r.top
    val provider = list.outlineProvider as? CardOutline
    if (provider == null) {
        list.outlineProvider = CardOutline(cl, ct, cr, cb, CARD_RADIUS_DP * d)
        list.clipToOutline = true
        list.invalidateOutline()
    } else if (provider.set(cl, ct, cr, cb, CARD_RADIUS_DP * d)) {
        list.invalidateOutline()
    }
}

// Its own pad: these blocks are dense ink to every edge, and the shared 8dp read cramped on sight.
private const val BLOCK_CONTENT_PAD_DP = 10f

private val blockCards = WeakHashMap<View, GlassView>()

private val blockCardTag = tagKey("wathemer-block-card")

/** A sibling block above a carded list, like the broadcast quota stats; same material, own pane. */
internal fun injectBlockCard(block: View, label: String) {
    if (block.getTag(blockCardTag) != null) return
    val host = block.rootView?.findViewById<ViewGroup>(android.R.id.content) ?: return
    if (!canStack(host)) {
        logOnce("$label: content is ${host.javaClass.simpleName}, which does not stack, no panel")
        return
    }
    block.setTag(blockCardTag, true)
    val glass = newCardGlass(host)
    host.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
    blockCards[block] = glass
    val ref = WeakReference(block)
    host.viewTreeObserver.addOnGlobalLayoutListener {
        runCatching { ref.get()?.let { syncBlockCard(it, label) } }
    }
    XposedBridge.log("[$TAG] $label panel inserted into ${host.javaClass.simpleName}")
}

/** Sides pinned to the list card's inset so the two edges align; top and bottom hug the block's ink. */
private fun syncBlockCard(block: View, label: String) {
    val glass = blockCards[block] ?: return
    val host = glass.parent as? ViewGroup ?: return
    if (host.width <= 0 || host.height <= 0) return
    if (!block.isShown || block.width <= 0 || block.height <= 0) {
        if (glass.visibility != View.GONE) glass.visibility = View.GONE
        return
    }
    if (glass.visibility != View.VISIBLE) glass.visibility = View.VISIBLE
    // From here, not from inject: attach is not layout, and the divider's height reads 0 there.
    // A rule reads as a seam over glass; INVISIBLE keeps the row's height.
    block.findViewById<View>(block.resources.waId("divider", "com.whatsapp"))?.let {
        if (it.height in 1..8 && it.visibility == View.VISIBLE) it.visibility = View.INVISIBLE
    }

    inkUnion.setEmpty()
    collectInk(block, 1)
    if (inkUnion.isEmpty) return
    host.getLocationOnScreen(hostAt)

    val d = host.resources.displayMetrics.density
    val side = (CARD_INSET_DP * d).toInt()
    val gap = (CARD_GAP_DP * d).toInt()
    val pad = (BLOCK_CONTENT_PAD_DP * d).toInt()

    // The pad wins over edge alignment: sides start at the card inset and move outward when the block's ink sits closer than the pad.
    val l = minOf(side, inkUnion.left - hostAt[0] - pad)
    val right = maxOf(host.width - side, inkUnion.right - hostAt[0] + pad)
    val t = maxOf(gap, inkUnion.top - hostAt[1] - pad)
    val bottom = inkUnion.bottom - hostAt[1] + pad
    if (right - l < gap || bottom - t < gap) return

    val ml = l - host.paddingLeft
    val mt = t - host.paddingTop
    val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
    if (lp.width != right - l || lp.height != bottom - t ||
        lp.leftMargin != ml || lp.topMargin != mt
    ) {
        lp.width = right - l
        lp.height = bottom - t
        lp.leftMargin = ml
        lp.topMargin = mt
        glass.layoutParams = lp
        XposedBridge.log("[$TAG] $label panel $l,$t-$right,$bottom")
    }
}

private val repinTag = tagKey("wathemer-repin")

/** How many frames repinToTop watches for a mis-anchor before giving up. */
private const val REPIN_FRAMES = 40

/** Repin after our own mutations, only if the list was at top just before; onPreDraw so the wrong position is never drawn, post() shows it. */
private fun repinToTop(list: View) {
    if (list.getTag(repinTag) != null) return          // already watching this list
    // Removed through the observer captured here: a detached view's getViewTreeObserver returns a floating one.
    val observer = list.viewTreeObserver
    val listener = object : ViewTreeObserver.OnPreDrawListener {
        private var frames = 0
        private var pinned = false

        override fun onPreDraw(): Boolean {
            frames++
            var skipDraw = false
            // Re-checked every frame: if nothing mis-anchored, scrolling would be the bug.
            if (list.canScrollVertically(-1)) {
                runCatching { list.scrollBy(0, -1_000_000) }   // RecyclerView clamps at its start
                    .onSuccess { pinned = true; skipDraw = true }
            }
            if (pinned || frames >= REPIN_FRAMES || !list.isAttachedToWindow) {
                // `isAlive` because a dead observer throws on removal rather than ignoring it.
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                else list.viewTreeObserver.removeOnPreDrawListener(this)
                list.setTag(repinTag, null)
            }
            // Cancelling the draw costs one frame and buys never showing the wrong position.
            return !skipDraw
        }
    }
    list.setTag(repinTag, listener)
    observer.addOnPreDrawListener(listener)
}

/** Pull the list up and hand the distance back as padding, so rows scroll under the chrome; the work is in [syncListExtension]. */
internal fun extendList(list: View) {
    if (list.getTag(doneTag) != null) return
    val parent = list.parent as? ViewGroup ?: return
    // android.R.id.list is generic; this guard fails closed, so a renamed class kills the extension silently.
    if (!WaIds.classIs(parent, "ConversationsContainer")) return
    // Archived inflates this container too; only the window that has header is home.
    if (headerIdPin != 0 && list.rootView?.findViewById<View>(headerIdPin) == null) return
    list.setTag(doneTag, true)
    listRef = WeakReference(list)
    (list as? ViewGroup)?.clipToPadding = false
    syncListCard()                  // the nav may already have floated
    list.requestLayout()
}

/** Re-derives from the invariant top + paddingTop on every layout; never snapshot the measurement. */
internal fun syncListExtension() {
    val v = listRef?.get() ?: return
    if (!v.isLaidOut) return
    val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    // The content pad is excluded here and added back below, so the recurrence is the same one that already converges.
    val pad = (CARD_CONTENT_PAD_DP * v.resources.displayMetrics.density).toInt()
    val natural = v.top + v.paddingTop - pad
    if (natural <= 0) return
    if (lp.topMargin == -natural && v.paddingTop == natural + pad) return
    // Before the mutation, or the answer is already spoilt by it.
    val wasAtTop = !v.canScrollVertically(-1)
    lp.topMargin = -natural
    v.layoutParams = lp
    v.setPadding(v.paddingLeft, natural + pad, v.paddingRight, v.paddingBottom)
    XposedBridge.log("[$TAG] list extended: topMargin=-$natural paddingTop=${natural + pad}")
    if (wasAtTop) repinToTop(v)
}

/** One per sequencing tab: the pager keeps neighbour pages realised, so shared refs would track the wrong list. */
internal class PageCard(val titleFallback: String, val navIndex: Int) {
    var host: WeakReference<ViewGroup>? = null
    var list: WeakReference<View>? = null
    var panel: WeakReference<GlassView>? = null
    var title: WeakReference<TextView>? = null
    /** WhatsApp's chrome clearance, captured once; -1 as the sentinel so a real paddingTop of 0 never reads as unknown. */
    var clearance = -1
}

internal val communityCard = PageCard("Communities", 2)

internal val callsCard = PageCard("Calls", 3)

internal val communityCardTag = tagKey("wathemer-community-card")

internal val callsCardTag = tagKey("wathemer-calls-card")

private val decorHookedClasses = Collections.synchronizedSet(HashSet<String>())

/** Silence the decorations' paint but never remove them, the same objects supply the item offsets; the hook skips only this list since decoration classes are shared. */
internal fun killDecorations(list: View, listId: Int) {
    val count = runCatching {
        XposedHelpers.callMethod(list, "getItemDecorationCount") as Int
    }.getOrElse {
        XposedBridge.log("[$TAG] decoration count unreadable: $it")
        return
    }
    if (count <= 0) return

    val decorations = findDecorationList(list, count)
    if (decorations == null) {
        XposedBridge.log("[$TAG] $count decorations present but the backing list was not found")
        return
    }
    logOnce("$count decorations: ${decorations.joinToString { it?.javaClass?.name ?: "null" }}")
    for (d in decorations) {
        val cls = d?.javaClass ?: continue
        if (!decorHookedClasses.add(cls.name)) continue
        var hooked = 0
        // Walk the whole hierarchy: the paint methods live on superclasses, not the leaf class.
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                val p = m.parameterTypes
                // Match by shape, not by name, so an obfuscated override is still caught.
                if (p.isEmpty() || p[0] != Canvas::class.java) continue
                XposedBridge.log("[$TAG]   paint candidate ${c.name}.${m.name}(${p.joinToString { it.simpleName }})")
                runCatching {
                    XposedBridge.hookMethod(
                        m,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                // Check every argument: the obfuscated signatures put the RecyclerView at different positions.
                                val hit = param.args.any { it is View && it.id == listId }
                                if (!hit) return
                                param.result = null   // skip the paint, keep the offsets
                            }
                        },
                    )
                    hooked++
                }
            }
            c = c.superclass
        }
        XposedBridge.log("[$TAG] decoration ${cls.name} (super ${cls.superclass?.name}): $hooked paint methods silenced")
    }
}

/** The decorations list found by size and non-View elements; R8 may have renamed mItemDecorations. */
private fun findDecorationList(list: View, count: Int): List<*>? {
    var c: Class<*>? = list.javaClass
    while (c != null) {
        for (f in c.declaredFields) {
            if (!List::class.java.isAssignableFrom(f.type)) continue
            val value = runCatching {
                f.isAccessible = true
                f.get(list) as? List<*>
            }.getOrNull() ?: continue
            if (value.size != count) continue
            if (value.any { it is View }) continue
            return value
        }
        c = c.superclass
    }
    return null
}

/** Pre-draw, not layout: syncPageCard settles over several passes, and moving the title never fires the list's layout listener. */
internal fun armPageCard(card: PageCard, v: View, tag: Int) {
    val host = v.parent as? ViewGroup ?: return
    (v as? ViewGroup)?.clipToPadding = false
    card.list = WeakReference(v)
    card.host = WeakReference(host)
    runCatching { injectPageCard(card, host) }
        .onFailure { XposedBridge.log("[$TAG] injectPageCard(${card.titleFallback}) threw: $it") }
    syncPageCard(card)
    if (v.getTag(tag) == null) {
        v.setTag(tag, true)
        v.viewTreeObserver.addOnPreDrawListener {
            runCatching { syncPageCard(card) }
            true
        }
    }
}

private fun injectPageCard(card: PageCard, host: ViewGroup) {
    if (card.panel?.get()?.parent === host) return
    if (!host.isAttachedToWindow) return
    val glass = newCardGlass(host)
    // Index 0 draws behind the list; syncPageCard sets the margins once the heights are known.
    host.addView(glass, 0, ViewGroup.MarginLayoutParams(0, 0))
    card.panel = WeakReference(glass)

    // The title's margins cancel its height in this sequencing host, and it must go before the match_parent list or it measures 0px forever.
    val ctx = host.context
    val tv = TextView(ctx).apply {
        text = navLabel(card.navIndex) ?: card.titleFallback
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
        typeface = Typeface.create(
            Typeface.DEFAULT, Typeface.BOLD,
        )
        setTextColor(primaryTextColor(ctx))
        includeFontPadding = false
        maxLines = 1
        setPadding(
            host.dp(16f).toInt(), host.dp(2f).toInt(),
            host.dp(16f).toInt(), host.dp(10f).toInt(),
        )
    }
    host.addView(
        tv, 1,
        ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
    )
    card.title = WeakReference(tv)
    XposedBridge.log("[$TAG] ${card.titleFallback} card + title '${tv.text}' inserted")
}

/** The host sequences, so the card's margins sum to zero height; clearance comes from the list's own paddingTop. */
private fun syncPageCard(card: PageCard) {
    val host = card.host?.get() ?: return
    val glass = card.panel?.get() ?: return
    val list = card.list?.get() ?: return
    if (host.width <= 0 || host.height <= 0) return
    if (list.width <= 0 || list.height <= 0) return
    val inset = bottomInset
    if (inset <= 0) return                         // nav not floated yet

    val d = host.resources.displayMetrics.density
    val side = (CARD_INSET_DP * d).toInt()
    val gap = (CARD_GAP_DP * d).toInt()
    // WhatsApp's chrome clearance, captured from the list's paddingTop on the first pass, before we overwrite it.
    if (card.clearance < 0) {
        card.clearance = list.paddingTop
        XposedBridge.log("[$TAG] ${card.titleFallback} clearance = ${card.clearance}")
    }
    // Same arithmetic as syncUpdatesCard, so the two tabs' cards line up.
    val title = card.title?.get()
    val tlp = title?.layoutParams as? ViewGroup.MarginLayoutParams
    if (title != null && tlp != null) {
        if (tlp.topMargin != card.clearance) {
            tlp.topMargin = card.clearance
            title.layoutParams = tlp
            return          // re-enter once it has been laid out at its new place
        }
        if (title.height <= 0) return
        // Cancel its own height so the host's sequencing is untouched.
        val want = -(card.clearance + title.height)
        if (tlp.bottomMargin != want) {
            tlp.bottomMargin = want
            title.layoutParams = tlp
            return
        }
    }

    val top = card.clearance + (title?.height ?: 0) + gap
    val bottom = host.height - inset - gap
    val right = host.width - side
    if (bottom - top < gap || right - side < gap) return

    // The pad is the content's breathing room inside the border, equal on all four sides.
    val pad = (CARD_CONTENT_PAD_DP * d).toInt()
    val wasAtTop = !list.canScrollVertically(-1)
    if (list.paddingLeft != side + pad || list.paddingTop != top + pad ||
        list.paddingRight != side + pad || list.paddingBottom != host.height - bottom + pad
    ) {
        list.setPadding(side + pad, top + pad, side + pad, host.height - bottom + pad)
        if (wasAtTop) repinToTop(list)
    }

    val lp = glass.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    val h = bottom - top
    if (lp.width != right - side || lp.height != h ||
        lp.leftMargin != side || lp.topMargin != top || lp.bottomMargin != -(top + h)
    ) {
        lp.width = right - side
        lp.height = h
        lp.leftMargin = side
        lp.topMargin = top
        lp.bottomMargin = -(top + h)               // net zero height in the LinearLayout
        glass.layoutParams = lp
        XposedBridge.log("[$TAG] ${card.titleFallback} card $side,$top-$right,$bottom")
    }

    // The pad comes back off so the clip lands on the border while the content sits inside it.
    val cl = list.paddingLeft - pad
    val ct = list.paddingTop - pad
    val cr = list.width - list.paddingRight + pad
    val cb = list.height - list.paddingBottom + pad
    val provider = list.outlineProvider as? CardOutline
    if (provider == null) {
        list.outlineProvider = CardOutline(cl, ct, cr, cb, CARD_RADIUS_DP * d)
        list.clipToOutline = true
        list.invalidateOutline()
    } else if (provider.set(cl, ct, cr, cb, CARD_RADIUS_DP * d)) {
        // Must stay gated on a real move: unconditional, these per-frame syncs keep an off-screen page invalidating every frame.
        list.invalidateOutline()
    }
}

internal var channelHostRef: WeakReference<ViewGroup>? = null

internal var channelListRef: WeakReference<View>? = null

private var channelPanelRef: WeakReference<GlassView>? = null

internal val channelCardTag = tagKey("wathemer-channel-card")

internal val channelListTag = tagKey("wathemer-channel-list")

internal fun injectChannelCard(host: ViewGroup) {
    if (channelPanelRef?.get()?.parent === host) return
    if (!host.isAttachedToWindow) return
    val glass = newCardGlass(host)
    // MarginLayoutParams, not FrameLayout's: the CoordinatorLayout host converts via generateLayoutParams.
    host.addView(glass, 0, ViewGroup.MarginLayoutParams(0, 0))
    channelPanelRef = WeakReference(glass)
    XposedBridge.log("[$TAG] channel card inserted into ${host.javaClass.simpleName}")
}

/** Pre-draw driven: the appbar collapse runs no layout pass, so every write below must be cheap and no-op when nothing has moved. */
internal fun syncChannelCard() {
    val host = channelHostRef?.get() ?: return
    val glass = channelPanelRef?.get() ?: return
    val list = channelListRef?.get() ?: return
    if (host.width <= 0 || host.height <= 0) return
    if (list.width <= 0 || list.height <= 0) return

    val d = host.resources.displayMetrics.density
    val side = (CARD_INSET_DP * d).toInt()
    val gap = (CARD_GAP_DP * d).toInt()

    // Top follows the list, not the host: on Explore the appbar is the list's sibling, and list.top cannot be moved by scrolling.
    val top = list.top + gap
    val bottom = host.height - gap
    val right = host.width - side
    if (bottom - top < gap || right - side < gap) return

    // Every padding edge must stay invariant under the collapse, or setPadding fires a requestLayout per frame from pre-draw.
    // The pad on top is the content's breathing room inside the border, equal on all four sides.
    val pad = (CARD_CONTENT_PAD_DP * d).toInt()
    val padL = (side - list.left).coerceAtLeast(0) + pad
    val padR = (list.left + list.width - right).coerceAtLeast(0) + pad
    if (list.paddingLeft != padL || list.paddingTop != gap + pad ||
        list.paddingRight != padR || list.paddingBottom != gap + pad
    ) {
        list.setPadding(padL, gap + pad, padR, gap + pad)
    }

    val lp = glass.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    if (lp.width != right - side || lp.height != bottom - top ||
        lp.leftMargin != side || lp.topMargin != top
    ) {
        lp.width = right - side
        lp.height = bottom - top
        lp.leftMargin = side
        lp.topMargin = top
        glass.layoutParams = lp
        // No log here: height tracks the collapse per frame, so any line in this branch prints per frame.
    }

    // Clip to the padding box intersected with the card's rect: the list can outgrow the card and leak under its bottom edge.
    // The pad comes back off first so the clip lands on the border while the content sits inside it.
    val cl = maxOf(list.paddingLeft - pad, side - list.left)
    val ct = list.paddingTop - pad
    val cr = minOf(list.width - list.paddingRight + pad, right - list.left)
    val cb = minOf(list.height - list.paddingBottom + pad, bottom - list.top)
    val provider = list.outlineProvider as? CardOutline
    if (provider == null) {
        list.outlineProvider = CardOutline(cl, ct, cr, cb, CARD_RADIUS_DP * d)
        list.clipToOutline = true
        list.invalidateOutline()
    } else if (provider.set(cl, ct, cr, cb, CARD_RADIUS_DP * d)) {
        // Must stay gated on a real move: unconditional, these per-frame syncs keep an off-screen page invalidating every frame.
        list.invalidateOutline()
    }
}

// ── Folder header: the chat screen's band and capsule, one pair per window ─────────
// One band only: two abutting panes cannot be seamless, each blur kernel is clipped to its own capture.
internal val folderBands = WeakHashMap<View, GlassView>()

private val folderCapsules = WeakHashMap<View, GlassView>()

/** Whether this window's whole toolbar already carries [ensureFolderHeader]'s capsule. */
internal fun folderCapsuleOwns(v: View): Boolean {
    if (actionBarRootId == 0) return false
    val abr = v.rootView?.findViewById<View>(actionBarRootId) ?: return false
    return folderCapsules[abr]?.parent != null
}

private val folderHeaderAt = IntArray(2)

private val folderHeaderAbrAt = IntArray(2)

/** Solid over the status bar and toolbar, then the chat screen's fade; a capsule under the toolbar's content. */
private fun ensureFolderHeader(anchor: View, label: String) {
    val root = anchor.rootView ?: return
    val content = root.findViewById<ViewGroup>(android.R.id.content) ?: return
    val under = wallpaperUnderlay(content)
    if (under.isEmpty()) return
    // Settings hides its toolbar behind a persistent search bar, so the shown bar decides the band's reach; a capsule needs a shown toolbar.
    val toolbar = (if (homeToolbarId != 0) root.findViewById<View>(homeToolbarId) else null)
        ?.takeIf { it.isShown && it.height > 0 }
    val searchBarId = content.resources.waId("wds_search_bar", content.context.packageName)
    val headerBar = toolbar
        ?: (if (searchBarId != 0) root.findViewById<View>(searchBarId) else null)
            ?.takeIf { it.isShown && it.height > 0 }
        ?: return
    val abrId = content.resources.waId("action_bar_root", content.context.packageName)
    val abr = (if (abrId != 0) root.findViewById<View>(abrId) else null) as? FrameLayout ?: return

    headerBar.getLocationOnScreen(folderHeaderAt)
    abr.getLocationOnScreen(folderHeaderAbrAt)
    val solid = folderHeaderAt[1] - folderHeaderAbrAt[1] + headerBar.height
    if (solid <= 0) return
    val total = solid

    var band = folderBands[abr]
    if (band == null || band.parent !== abr) {
        band = GlassView(abr.context).apply {
            underlay = under
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = abr.dp(BLUR_DP)
                refractionEnabled = true
                // With the SDF expanded there is no bevel to size; a 1px nominal one keeps depth and displacement at zero.
                bevelFraction = 0f
                bevelThickness = 1f
                depthRatio = DEPTH_RATIO
                maxDisplacePx = abr.dp(DISPLACE_DP)
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
        var insertAt = 0
        for (i in 0 until abr.childCount) {
            val c = abr.getChildAt(i)
            if (c === under.firstOrNull() || (under.size > 1 && c === under[1])) insertAt = i + 1
        }
        if (insertAt == 0) {
            XposedBridge.log("[$TAG] WARN: $label wallpaper views are not children of action_bar_root")
        }
        abr.addView(
            band, insertAt,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, total).apply {
                gravity = Gravity.TOP
            },
        )
        folderBands[abr] = band
        XposedBridge.log("[$TAG] $label band inserted (solid=${solid}px)")
    }
    band.params.tintColor = glassTint(convBandAlpha())
    (band.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
        if (lp.height != total) { lp.height = total; band.layoutParams = lp }
    }

    // No capsule without a shown toolbar; the settings search pill is its own surface.
    if (toolbar == null) return
    var cap = folderCapsules[abr]
    if (cap == null || cap.parent !== abr) {
        cap = GlassView(abr.context).apply {
            underlay = under
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = abr.dp(BLUR_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = abr.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
            }
        }
        // Directly above the band, still under every app view.
        abr.addView(cap, abr.indexOfChild(band) + 1, FrameLayout.LayoutParams(0, 0))
        folderCapsules[abr] = cap
        XposedBridge.log("[$TAG] $label capsule inserted")
    }
    // The remainder of the tint budget; band plus capsule sum to TINT_ALPHA exactly, including 0.
    cap.params.tintColor = glassTint((TINT_ALPHA - convBandAlpha()).coerceAtLeast(0))
    val inset = (CONV_PILL_INSET_DP * abr.resources.displayMetrics.density).toInt()
    val trim = (4 * abr.resources.displayMetrics.density).toInt()
    val l = folderHeaderAt[0] - folderHeaderAbrAt[0] + inset
    val t = folderHeaderAt[1] - folderHeaderAbrAt[1] + trim
    val w = toolbar.width - 2 * inset
    val h = toolbar.height - 2 * trim
    if (w < inset || h < trim) return
    if (cap.params.cornerRadius != h / 2f) cap.params.cornerRadius = h / 2f
    (cap.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
        if (lp.width != w || lp.height != h || lp.leftMargin != l || lp.topMargin != t) {
            lp.width = w
            lp.height = h
            lp.leftMargin = l
            lp.topMargin = t
            lp.gravity = Gravity.TOP or Gravity.START
            cap.layoutParams = lp
            XposedBridge.log("[$TAG] $label capsule $l,$t ${w}x$h")
        }
    }
}
