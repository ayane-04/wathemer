// Toolbars: the action-icon pane, the drilled-in screens' capsule and Back pill, the big titles, and
// the guard that hides a toolbar bleeding through a selection bar. The bar's own isShown IS the mode.
package com.wathemer.app.hooks.glass

import android.app.Application
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.wathemer.app.glass.GlassView
import com.wathemer.app.hooks.HookLog
import com.wathemer.app.hooks.WaIds
import com.wathemer.app.hooks.waId
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

internal var altToolbarRef: WeakReference<ViewGroup>? = null

private var altToolbarGlassRef: WeakReference<GlassView>? = null

/** The toolbar itself when the holder is not its wrapper; a single ref is safe, these screens never coexist. */
internal var altBarRef: WeakReference<ViewGroup>? = null

internal val altToolbarTag = tagKey("wathemer-alt-toolbar")

private val altRect = Rect()

/** One pane behind a drilled-in toolbar's actions, unioned from the action container only: Back is an ImageButton too and would stretch it full width. */
internal fun syncAltToolbarGlass() {
    val holder = altToolbarRef?.get() ?: return
    if (holder.width <= 0 || holder.height <= 0) return

    // The folder header's capsule already covers this whole bar, and it is the one that lines up with the card.
    if (folderCapsuleOwns(holder)) {
        // Gated on a real change: an unconditional write from this per-layout path costs a pass every frame.
        altToolbarGlassRef?.get()?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
        altLeadingGlassRef?.get()?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
        return
    }

    // Use the named toolbar when given: the two-deep scan only holds when the holder is the toolbar's wrapper.
    var actions: ViewGroup? = null
    val explicitBar = altBarRef?.get()
    if (explicitBar != null) {
        // ActionMenuView by class first: the title container is also a populated ViewGroup and would otherwise take the pane.
        for (j in 0 until explicitBar.childCount) {
            val c = explicitBar.getChildAt(j) as? ViewGroup ?: continue
            if (c.javaClass.name.contains("ActionMenuView")) actions = c
        }
        if (actions == null) {
            for (j in 0 until explicitBar.childCount) {
                val c = explicitBar.getChildAt(j) as? ViewGroup ?: continue
                if (c.childCount > 0 && c.isShown) actions = c
            }
        }
    } else {
        // ActionMenuView by class first, as the named-bar path does: a search bar is a populated shown group too.
        forEachActionCandidate(holder) { c ->
            if (c.javaClass.name.contains("ActionMenuView")) actions = c
        }
        if (actions == null) {
            forEachActionCandidate(holder) { c ->
                if (c.childCount > 0 && !holdsSearchView(c)) actions = c
            }
        }
    }
    val group = actions ?: return

    // Asymmetric on purpose: the trailing pane grows by ALT_PANE_PAD_DP and the Back pill does not, yet both must land on the card's inset.
    val bar = group.parent as? ViewGroup ?: return
    val padStart = holder.dp(CARD_INSET_DP).toInt()
    val padEnd = holder.dp(CARD_INSET_DP + ALT_PANE_PAD_DP).toInt()
    if (bar.paddingLeft != padStart || bar.paddingRight != padEnd) {
        bar.setPadding(padStart, bar.paddingTop, padEnd, bar.paddingBottom)
    }

    // Scale the glyphs only, layout bounds keep the touch targets; scoped to bar or the community photo would shrink too.
    for (j in 0 until bar.childCount) {
        val c = bar.getChildAt(j) ?: continue
        if (c is ViewGroup) {
            for (k in 0 until c.childCount) shrinkGlyph(c.getChildAt(k))
        } else {
            shrinkGlyph(c)
        }
    }

    var l = Int.MAX_VALUE; var t = Int.MAX_VALUE
    var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
    var n = 0
    for (i in 0 until group.childCount) {
        val a = group.getChildAt(i) ?: continue
        // isShown, not visibility: the bleed guard hides the Toolbar while its ActionMenuView stays VISIBLE inside it.
        if (a.width <= 0 || a.height <= 0 || !a.isShown) continue
        val rect = altRect
        rect.set(0, 0, a.width, a.height)
        if (!runCatching { holder.offsetDescendantRectToMyCoords(a, rect) }.isSuccess) continue
        l = minOf(l, rect.left); t = minOf(t, rect.top)
        r = maxOf(r, rect.right); b = maxOf(b, rect.bottom)
        n++
    }
    if (n == 0 || r <= l || b <= t) return
    val pad = holder.dp(ALT_PANE_PAD_DP).toInt()
    l = (l - pad).coerceAtLeast(0)
    r = (r + pad).coerceAtMost(holder.width)

    // Force the icons light: only some widget types pick up the toolbar's icon colour.
    for (i in 0 until group.childCount) {
        val a = group.getChildAt(i) ?: continue
        if (a.getTag(altIconTag) != null) continue
        a.setTag(altIconTag, true)
        val white = ColorStateList.valueOf(Color.WHITE)
        when {
            a is ImageView -> a.imageTintList = white
            // ActionMenuItemView draws its icon as a compound drawable; imageTintList never reaches it.
            a is TextView -> a.compoundDrawableTintList = white
            else -> XposedBridge.log(
                "[$TAG] alt toolbar action ${a.javaClass.simpleName} has no known icon slot"
            )
        }
    }

    // t/b, not the padded l/r: the Back pill matches this pane's thickness and vertical position.
    syncAltLeadingGlass(holder, bar, group, t, b)

    var glass = altToolbarGlassRef?.get()
    if (glass == null || glass.parent !== holder) {
        glass = GlassView(holder.context).apply {
            underlay = wallpaperUnderlay(holder)
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = holder.dp(BLUR_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = holder.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                tintColor = glassTintColor
            }
        }
        holder.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
        altToolbarGlassRef = WeakReference(glass)
        XposedBridge.log("[$TAG] alt toolbar pane inserted ($n actions)")
    }
    // The complement of the folder-capsule stand-down above, for a window that loses it again.
    if (glass.visibility != View.VISIBLE) glass.visibility = View.VISIBLE
    // A tripwire, not a repair: a canStack host may hand back non-FrameLayout params, and a bare return would hide the pane silently.
    val lp = glass.layoutParams as? FrameLayout.LayoutParams
        ?: run {
            logOnce("alt toolbar pane: host gave ${glass.layoutParams?.javaClass?.simpleName} " +
                "instead of FrameLayout.LayoutParams; pane cannot be sized")
            return
        }
    if (lp.width != r - l || lp.height != b - t ||
        lp.leftMargin != l - holder.paddingLeft || lp.topMargin != t - holder.paddingTop
    ) {
        lp.width = r - l
        lp.height = b - t
        lp.leftMargin = l - holder.paddingLeft
        lp.topMargin = t - holder.paddingTop
        lp.gravity = Gravity.TOP or Gravity.START
        glass.layoutParams = lp
        glass.params.cornerRadius = (b - t) / 2f
        XposedBridge.log("[$TAG] alt toolbar pane $l,$t-$r,$b")
    }
}

/** Every shown grandchild group of the holder: the two passes below differ only in what they accept. */
private inline fun forEachActionCandidate(holder: ViewGroup, body: (ViewGroup) -> Unit) {
    for (i in 0 until holder.childCount) {
        val tb = holder.getChildAt(i) as? ViewGroup ?: continue
        if (tb is GlassView) continue // our own panes are FrameLayouts, not toolbars
        for (j in 0 until tb.childCount) {
            val c = tb.getChildAt(j) as? ViewGroup ?: continue
            if (c.isShown) body(c)
        }
    }
}

/** A search bar is a surface, not a row of action icons, however populated and shown it is. */
private fun holdsSearchView(g: ViewGroup): Boolean {
    for (i in 0 until g.childCount) {
        if (g.getChildAt(i)?.javaClass?.name?.endsWith("SearchView") == true) return true
    }
    return false
}

/** Icons only: a TextView without a compound drawable is the title, and scaling that would be silent damage. */
private fun shrinkGlyph(v: View?) {
    if (v == null) return
    val isIcon = when (v) {
        is ImageView -> true
        is TextView -> v.compoundDrawables.any { it != null }
        else -> false
    }
    if (!isIcon) return
    if (v.scaleX == ALT_ICON_SCALE && v.scaleY == ALT_ICON_SCALE) return
    v.scaleX = ALT_ICON_SCALE
    v.scaleY = ALT_ICON_SCALE
}

private val altIconTag = tagKey("wathemer-alt-icon")

private var altLeadingGlassRef: WeakReference<GlassView>? = null

/** A round pill behind Back only, sized from the trailing pane's own numbers so the two always match. */
private fun syncAltLeadingGlass(
    holder: ViewGroup,
    bar: ViewGroup,
    actions: ViewGroup,
    refTop: Int,
    refBottom: Int,
) {
    // A few px under the trailing pane: a circle reads larger than a pill of equal height.
    val side = refBottom - refTop - BACK_PILL_TRIM_PX
    if (side <= 0) return

    // Leftmost ImageView inside bar, not holder: the community photo is an ImageView too, and child order is WhatsApp's.
    var backLeft = Int.MAX_VALUE
    var backRight = Int.MIN_VALUE
    var backView: View? = null
    for (j in 0 until bar.childCount) {
        val a = bar.getChildAt(j) ?: continue
        if (a === actions) continue
        if (a !is ImageView) continue
        if (a.width <= 0 || a.height <= 0 || a.visibility != View.VISIBLE) continue
        val rect = altRect
        rect.set(0, 0, a.width, a.height)
        if (!runCatching { holder.offsetDescendantRectToMyCoords(a, rect) }.isSuccess) continue
        if (rect.left < backLeft) {
            backLeft = rect.left
            backRight = rect.right
            backView = a
        }
    }
    if (backRight <= backLeft) return

    // Centred on Back, so the arrow sits mid-circle however wide its touch target is.
    val cx = (backLeft + backRight) / 2
    val l = (cx - side / 2).coerceAtLeast(0)
    val t = refTop + BACK_PILL_TRIM_PX / 2      // keep it centred on the trailing pane
    val r = l + side
    val b = t + side

    var glass = altLeadingGlassRef?.get()
    if (glass == null || glass.parent !== holder) {
        glass = GlassView(holder.context).apply {
            underlay = wallpaperUnderlay(holder)
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = holder.dp(BLUR_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = holder.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                tintColor = glassTintColor
            }
        }
        holder.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
        altLeadingGlassRef = WeakReference(glass)
        XposedBridge.log("[$TAG] alt toolbar back pill inserted (${side}px)")
    }
    if (glass.visibility != View.VISIBLE) glass.visibility = View.VISIBLE
    if (glass.pressSource !== backView) glass.pressSource = backView
    // Same tripwire as [syncAltToolbarGlass]. See the note there.
    val lp = glass.layoutParams as? FrameLayout.LayoutParams
        ?: run {
            logOnce("alt toolbar back pill: host gave ${glass.layoutParams?.javaClass?.simpleName} " +
                "instead of FrameLayout.LayoutParams; pane cannot be sized")
            return
        }
    if (lp.width != r - l || lp.height != b - t ||
        lp.leftMargin != l - holder.paddingLeft || lp.topMargin != t - holder.paddingTop
    ) {
        lp.width = r - l
        lp.height = b - t
        lp.leftMargin = l - holder.paddingLeft
        lp.topMargin = t - holder.paddingTop
        lp.gravity = Gravity.TOP or Gravity.START
        glass.layoutParams = lp
        glass.params.cornerRadius = side / 2f
        XposedBridge.log("[$TAG] alt toolbar back pill $l,$t-$r,$b")
    }
}

internal var actionsGlassRef: WeakReference<View>? = null

private val chipAnchors = mutableListOf<WeakReference<View>>()

/** Which toolbar an action icon belongs to. See the registration and [syncActionsGlass]. */
private val actionGroupTag = tagKey("wathemer-action-group")

internal const val GROUP_NORMAL = 0

internal const val GROUP_SELECTION = 1

internal const val GROUP_BOTH = 2

/** One pane over the union of the toolbar actions: per-button circles came out uneven, and ActionMenuView has no id to hang one on. */
internal fun registerAction(v: View) {
    if (chipAnchors.none { it.get() === v }) {
        chipAnchors.add(WeakReference(v))
        v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncActionsGlass() }
    }
    val content = contentRef?.get()
    val backdrop = pagerHolderRef?.get()
    val header = headerRef?.get()
    if (content != null && backdrop != null && header != null && content.isAttachedToWindow &&
        actionsGlassRef?.get()?.parent !== content
    ) {
        val glass = GlassView(content.context).apply {
            this.backdrop = backdrop
            this.underlay = wallpaperUnderlay(backdrop)
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = content.dp(BLUR_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = content.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                tintColor = glassTint(TINT_ALPHA)
            }
        }
        content.addView(glass, content.indexOfChild(header), FrameLayout.LayoutParams(0, 0))
        actionsGlassRef = WeakReference(glass)
        XposedBridge.log("[$TAG] actions pane inserted")
    }
    // Bound to the header, which outlives the short-lived items; outside the branch above so a reused pane is re-bound (see [injectSearchPanel]).
    actionsGlassRef?.get()?.let { g -> headerRef?.get()?.let { bindPane(g, it, "toolbar actions") } }
    v.post { syncActionsGlass() }
}

private fun syncActionsGlass() {
    val content = contentRef?.get() ?: return
    val glass = actionsGlassRef?.get() as? GlassView ?: return
    // Re-resolve the live header and re-bind here: attach never re-fires after recreation, and a stale headerRef hid the pill for good.
    val liveHeader = (if (homeHeaderId != 0) content.findViewById<View>(homeHeaderId) else null)
        ?: headerRef?.get()
    liveHeader?.let { header ->
        if (headerRef?.get() !== header) headerRef = WeakReference(header)
        val bound = paneBindings.firstOrNull { it.pane.get() === glass }?.anchor?.get()
        if (bound !== header) bindPane(glass, header, "toolbar actions")
    }
    // A shown search bar owns the header; its buttons stay isShown under it and the unions overlap.
    val searchOverlay = searchOverlayShown(content)
    var l = Int.MAX_VALUE
    var t = Int.MAX_VALUE
    var r = Int.MIN_VALUE
    var b = Int.MIN_VALUE
    var n = 0
    // Dead anchors are pruned in-walk (main thread only); selection mode wins when any of its icons is shown, else the union spans both bars.
    var selectionMode = false
    for (ref in chipAnchors) {
        val a = ref.get() ?: continue
        if (a.getTag(actionGroupTag) != GROUP_SELECTION) continue
        // Same-window check: a chat left in selection mode keeps a shown action bar in its own decor for a beat after returning home.
        if (a.rootView !== content.rootView) continue
        if (a.isShown && a.width > 0) { selectionMode = true; break }
    }
    val anchors = chipAnchors.iterator()
    while (anchors.hasNext()) {
        val a = anchors.next().get()
        if (a == null) { anchors.remove(); continue }
        // isAttachedToWindow is the real filter: removed items keep size and VISIBLE, and the union spanned another tab's button.
        if (!a.isAttachedToWindow) continue
        // Skip anchors from another window root: a left Activity's menu stays attached, and the pill wore the shape of the screen you just left.
        if (a.rootView !== content.rootView) continue
        // isShown, not visibility == VISIBLE: a hidden ancestor leaves the item itself still reporting VISIBLE.
        if (a.width <= 0 || a.height <= 0 || !a.isShown) continue
        if (searchOverlay) continue
        // Only the group that owns the toolbar, plus the overflow button which is in both.
        val group = a.getTag(actionGroupTag)
        if (group != GROUP_BOTH &&
            group != (if (selectionMode) GROUP_SELECTION else GROUP_NORMAL)
        ) continue
        whitenActionIcons(a)
        for (icon in iconTargets(a)) {
            val rect = Rect(0, 0, icon.width, icon.height)
            if (!runCatching { content.offsetDescendantRectToMyCoords(icon, rect) }.isSuccess) {
                // The contextual action bar is not under id/content, so the offset call throws; screen coordinates work whatever the hierarchy.
                val la = IntArray(2)
                val lc = IntArray(2)
                icon.getLocationOnScreen(la)
                content.getLocationOnScreen(lc)
                rect.set(
                    la[0] - lc[0], la[1] - lc[1],
                    la[0] - lc[0] + icon.width, la[1] - lc[1] + icon.height,
                )
            }
            l = minOf(l, rect.left)
            t = minOf(t, rect.top)
            r = maxOf(r, rect.right)
            b = maxOf(b, rect.bottom)
            n++
            }
    }
    // No anchor on screen: collapse by SIZE, never visibility, which belongs to [syncPaneVisibility] alone; a second writer would fight it.
    if (n == 0) {
        val lp = glass.layoutParams
        if (lp != null && (lp.width != 0 || lp.height != 0)) {
            lp.width = 0
            lp.height = 0
            glass.layoutParams = lp
        }
        return
    }
    // No vertical trim: the full touch-target height matches the horizontal gap; trimming read as uneven.
    if (r <= l || b <= t) return

    // Widen a lone action's union to a square so the half-height radius reads as a circle, not a lozenge.
    if (r - l < b - t) {
        val grow = (b - t) - (r - l)
        l -= grow / 2
        r = l + (b - t)
        if (l < 0) { r -= l; l = 0 }
        if (r > content.width) { l -= r - content.width; r = content.width }
    }

    val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
    if (lp.width != r - l || lp.height != b - t ||
        lp.leftMargin != l - content.paddingLeft || lp.topMargin != t - content.paddingTop
    ) {
        lp.width = r - l
        lp.height = b - t
        lp.leftMargin = l - content.paddingLeft
        lp.topMargin = t - content.paddingTop
        glass.layoutParams = lp
        glass.params.cornerRadius = (b - t) / 2f
    }
}

/** A search bar covering this header, whose own capsule is the one that should show. */
private fun searchOverlayShown(content: ViewGroup): Boolean {
    val res = content.resources
    val pkg = content.context.packageName
    for (n in listOf("search_view", "search_fragment")) {
        val id = res.waId(n, pkg)
        if (id == 0) continue
        val v = content.rootView?.findViewById<View>(id) ?: continue
        if (v.isShown && v.height > 0) return true
    }
    return false
}

private val actionIconsTag = tagKey("wathemer-action-icons")

/** Tints whatever is in the row: some icons carry generated ids that name-keyed tinting misses; yields to a user icon colour. */
internal fun whitenActionIcons(container: View) {
    if (toolbarIconsColored) return
    val group = container as? ViewGroup ?: return
    // Menu items arrive after the container attaches, so one pass would miss every icon but the last.
    if (group.getTag(actionIconsTag) == null) {
        group.setTag(actionIconsTag, true)
        group.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (!toolbarIconsColored) (v as? ViewGroup)?.let { whitenIconsIn(it, 0) }
        }
    }
    whitenIconsIn(group, 0)
}

/** Walks into action views: a chat header's call button is an id-less ImageButton inside one. */
private fun whitenIconsIn(group: ViewGroup, depth: Int) {
    val white = ColorStateList.valueOf(Color.WHITE)
    for (i in 0 until group.childCount) {
        val a = group.getChildAt(i) ?: continue
        when {
            // Tag the icon, never its container: one tagged before its items arrive is never revisited.
            a is ImageView -> if (a.getTag(altIconTag) == null) {
                a.setTag(altIconTag, true)
                a.imageTintList = white
            }
            // ActionMenuItemView draws its icon as a compound drawable, out of imageTintList's reach.
            a is TextView -> if (a.getTag(altIconTag) == null) {
                a.setTag(altIconTag, true)
                a.compoundDrawableTintList = white
            }
            a is ViewGroup && depth < 3 -> whitenIconsIn(a, depth + 1)
        }
    }
}

/** Icons found by what a child is, not by a size heuristic; falls back to the bar itself. */
private fun iconTargets(a: View): List<View> {
    if (a !is ViewGroup) return listOf(a)
    val out = ArrayList<View>(a.childCount)
    for (i in 0 until a.childCount) {
        val c = a.getChildAt(i)
        if (!c.isShown || c.width <= 0 || c.height <= 0) continue
        if (c.isClickable || c is ImageView ||
            c.javaClass.simpleName.endsWith("ActionMenuItemView")
        ) out.add(c)
    }
    return if (out.isEmpty()) listOf(a) else out
}

/** The bar that owns a menu item; the container knows its children, a hard-coded id list cannot (WAEnhancer adds five). */
internal fun menuHolder(v: View): View = (v.parent as? ViewGroup) ?: v

/** Specific group beats [GROUP_BOTH]: the overflow seed must not relabel a bar it shares. */
internal fun tagGroup(v: View, group: Int) {
    val cur = v.getTag(actionGroupTag) as? Int
    if (cur == null || (cur == GROUP_BOTH && group != GROUP_BOTH)) v.setTag(actionGroupTag, group)
}

// ── Toolbar bleed-through guard ────────────────────────────────────────────────────────
/** Toolbars we hid for a contextual action bar, each with the visibility to give back. */
private val hiddenUnderActionBar = WeakHashMap<View, Int>()

/** Last action-bar state we acted on, per window root, so the walk runs on change only. */
private val actionBarState = WeakHashMap<View, Boolean>()

private val actionBarWatchTag = tagKey("wathemer-action-bar-watch")

/** One layout watcher per window, driving [syncActionBarCover] whenever the mode flips. */
internal fun watchActionBar(bar: View, toolbarId: Int) {
    val root = bar.rootView as? ViewGroup ?: return
    if (root.getTag(actionBarWatchTag) != null) return
    root.setTag(actionBarWatchTag, true)
    root.viewTreeObserver.addOnGlobalLayoutListener {
        runCatching { syncActionBarCover(root, bar, toolbarId) }
    }
    syncActionBarCover(root, bar, toolbarId)
}

/** The bar's own isShown IS the mode. Hide others INVISIBLE (GONE jumps the layout); steady state is one isShown call. */
private fun syncActionBarCover(root: ViewGroup, bar: View, toolbarId: Int) {
    val active = bar.isShown && bar.height > 0
    if (actionBarState[root] == active) return
    actionBarState[root] = active
    if (active) {
        // Same family as the toolbar: glass owns the fill, and the band or the wallpaper behind is the material.
        if (bar.background != null) clearBg(bar, "action_mode_bar")
        // AppCompat creates the guard lazily, hence the second, delayed pass.
        val inset = runCatching {
            root.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top
        }.getOrNull() ?: 0
        runCatching { clearStatusGuard(root, inset) }
        root.postDelayed({ runCatching { clearStatusGuard(root, inset) } }, 250L)
    }
    if (active) {
        forEachWithId(root, toolbarId) { t ->
            if (t !== bar && t.visibility == View.VISIBLE && !containsView(t, bar)) {
                hiddenUnderActionBar[t] = t.visibility
                t.visibility = View.INVISIBLE
            }
        }
    } else {
        val each = hiddenUnderActionBar.entries.iterator()
        while (each.hasNext()) {
            val e = each.next()
            // Scoped to this window root: a blanket restore would un-hide another Activity's toolbar mid-selection.
            if (e.key.rootView !== root) continue
            e.key.visibility = e.value
            each.remove()
        }
    }
}

private fun forEachWithId(v: View, id: Int, action: (View) -> Unit) {
    if (v.id == id) action(v)
    if (v is ViewGroup) for (i in 0 until v.childCount) forEachWithId(v.getChildAt(i), id, action)
}

/** Drop the id-less status guard's background (assigned once, so it holds); the match is deliberately narrow, looser blanks real views. */
private fun clearStatusGuard(root: ViewGroup, inset: Int) {
    if (inset <= 0) return
    fun walk(v: View): Boolean {
        if (v.javaClass == View::class.java && v.id == View.NO_ID && v.tag == null) {
            val bg = v.background as? ColorDrawable
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            if (bg != null && Color.alpha(bg.color) == 255 &&
                loc[1] <= 0 && v.height in 1..inset
            ) {
                v.background = null
                XposedBridge.log(
                    "[$TAG] status guard cleared (h=${v.height} was #%08x)".format(bg.color)
                )
                return true
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) if (walk(v.getChildAt(i))) return true
        return false
    }
    walk(root.rootView)
}

internal val toolbarTitleTag = tagKey("wathemer-toolbar-title")

private val hidTitleTag = tagKey("wathemer-hid-title")

/** Hide the small toolbar title on tabs with a big one; the shared bar's title is id-less, so it is matched by text against nav labels. */
private val bigTitleTabs = listOf(1, 2, 3)   // Updates, Communities, Calls.

internal fun syncToolbarTitle() {
    // Resolved from the live tree and scoped through content: a stale toolbarRef left this pass running on a dead view.
    val toolbar = (
        contentRef?.get()?.let { c ->
            if (homeToolbarId != 0) c.findViewById<View>(homeToolbarId) else null
        } ?: toolbarRef?.get()
        ) as? ViewGroup ?: return
    if (toolbarRef?.get() !== toolbar) toolbarRef = WeakReference(toolbar)
    val wanted = bigTitleTabs.mapNotNull { navLabel(it)?.toString() }
    for (i in 0 until toolbar.childCount) {
        val tv = toolbar.getChildAt(i) as? TextView ?: continue
        // Only restore titles we hid: WhatsApp keeps a "WhatsApp" TextView deliberately hidden and it must stay that way.
        val hide = tv.text?.toString() in wanted
        if (hide) {
            if (tv.visibility != View.GONE) {
                tv.visibility = View.GONE
                tv.setTag(hidTitleTag, true)
                XposedBridge.log("[$TAG] toolbar title (len=${tv.text?.length ?: 0}) -> GONE")
            }
        } else if (tv.getTag(hidTitleTag) != null && tv.visibility != View.VISIBLE) {
            tv.visibility = View.VISIBLE
            tv.setTag(hidTitleTag, null)
            XposedBridge.log("[$TAG] toolbar title (len=${tv.text?.length ?: 0}) -> restored")
        }
        // Absolute sp, not a multiplier, which would compound every layout; the typeface family survives a font swap.
        if (!hide) {
            val px = TOOLBAR_TITLE_SP * tv.resources.displayMetrics.scaledDensity
            if (abs(tv.textSize - px) > 0.5f) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, TOOLBAR_TITLE_SP)
                tv.setTypeface(tv.typeface, Typeface.BOLD)
                XposedBridge.log("[$TAG] toolbar title (len=${tv.text?.length ?: 0}) -> ${TOOLBAR_TITLE_SP}sp bold")
            }
        }
    }
}

/** The big Chats title, child 0 of the vertical container so everything reflows; text read off the nav because string names are stripped. */
internal fun injectBigTitle(bar: View) {
    val direct = bar.parent as? ViewGroup ?: return
    // Two layouts: the bar beside the list in the container, or seated inside the list as its header row.
    val seated = !WaIds.classIs(direct, "ConversationsContainer")
    var list: View? = null
    if (seated) {
        var p: ViewParent? = bar.parent
        while (p != null) {
            if (p is View && p.id == android.R.id.list) { list = p; break }
            p = p.parent
        }
    }
    val container = if (seated) list?.parent as? ViewGroup else direct
    // my_search_bar is not home-only; same fails-closed guard as [extendList], but a third layout must not vanish in silence.
    if (container == null || !WaIds.classIs(container, "ConversationsContainer")) {
        logOnce(
            "big title declined: container=${container?.javaClass?.simpleName ?: "none"}" +
                " parent=${direct.javaClass.simpleName} seated=$seated"
        )
        return
    }
    if (container.getTag(titleTag) != null) return
    val barLp = bar.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    container.setTag(titleTag, true)

    // The chrome clearance moves onto the title: the bar's own margin, or the list's padding once the bar is a row.
    val headerClearance =
        if (seated) (list?.paddingTop ?: 0) + container.dp(4f).toInt() else barLp.topMargin
    val ctx = container.context
    val tv = TextView(ctx).apply {
        text = navLabel() ?: "Chats"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
        typeface = Typeface.create(
            Typeface.DEFAULT, Typeface.BOLD,
        )
        setTextColor(primaryTextColor(ctx))
        includeFontPadding = false
        maxLines = 1
        // 16dp lines up with the search field; the 4dp shift comes off the padding so the margin stays the header's clearance.
        setPadding(
            container.dp(16f).toInt(), container.dp(2f).toInt(),
            container.dp(16f).toInt(), container.dp(10f).toInt(),
        )
    }
    container.addView(
        tv, 0,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = headerClearance },
    )
    // A seated bar keeps its margin: it is the list's row inset, and the extension recurrence lands the rows under the title.
    if (!seated) {
        barLp.topMargin = container.dp(2f).toInt()
        bar.layoutParams = barLp
    }
    val where = if (seated) "bar seated in the list" else "bar beside the list"
    XposedBridge.log("[$TAG] big title '${tv.text}' inserted (clearance=$headerClearance, $where)")
}

private var loggedHomeSyncThrow = false

/** Glass between pager_holder and the header, so WhatsApp's toolbar contents keep painting on top. */
internal fun injectToolbarGlass(header: View, pagerHolderId: Int) {
    val parent = header.parent as? ViewGroup ?: return
    if (parent.getTag(doneTag) != null) return

    val backdrop = parent.findViewById<View>(pagerHolderId) ?: run {
        XposedBridge.log("[$TAG] pager_holder not a sibling of header; glass skipped")
        HookLog.skip("glass/toolbarPane", "pager_holder is not a sibling of header")
        return
    }
    parent.setTag(doneTag, true)
    contentRef = WeakReference(parent)
    pagerHolderRef = WeakReference(backdrop)
    // Armed as soon as the backdrop is known, never in the search path: that is a race with the event being intercepted.
    ensurePagerFadeHook()
    registerFadeOnHide(backdrop)
    parent.viewTreeObserver.addOnGlobalLayoutListener {
        // Guarded: this is the home window's permanent layout driver, and an escape would crash WA on every layout.
        try {
            // The sweep runs first and only from here: the search swap is the one event the anchors cannot report, being the thing removed.
            syncPaneVisibility()
            // Anchors alone cannot drive this: leaving selection mode lays none of them out, and the pane kept the old bar's width.
            syncActionsGlass()
            applyLifts()
            syncListExtension()     // before the card: the card's top is read off the list
            syncListCard()
            syncUpdatesCard()
            syncToolbarTitle()
        } catch (t: Throwable) {
            if (!loggedHomeSyncThrow) {
                loggedHomeSyncThrow = true
                XposedBridge.log("[$TAG] home layout sync threw (logged once): $t")
            }
        }
    }
    syncActionsGlass()
    injectNavGlass()
}

private var dividerHookInstalled = false

/** Suppress WDSToolbar's onDraw divider, scoped to toolbars in [forcedBg]; the rest of its onDraw draws nothing visible. */
internal fun ensureToolbarDividerHook(app: Application) {
    if (dividerHookInstalled) return
    dividerHookInstalled = true
    runCatching {
        val cls = XposedHelpers.findClass(
            "com.whatsapp.ui.wds.components.topbar.WDSToolbar", app.classLoader,
        )
        XposedHelpers.findAndHookMethod(
            cls, "onDraw", Canvas::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    // Only ours; a map probe keeps this hot path cheap.
                    if (synchronized(forcedBg) { forcedBg.containsKey(v) }) {
                        param.result = null
                        logOnce("WDSToolbar divider suppressed")
                    }
                }
            },
        )
        XposedBridge.log("[$TAG] toolbar divider hook armed on WDSToolbar.onDraw")
    }.onFailure { XposedBridge.log("[$TAG] toolbar divider hook failed: $it") }
}

/** Material's BadgeDrawable by TRAIT, never by its R8 name: one Drawable field and one helper holding a TextPaint. */
internal fun themeNavBadge(d: Drawable) {
    val cls = d.javaClass
    if (cls.superclass != Drawable::class.java) return
    val shapeField = cls.declaredFields.firstOrNull {
        Drawable::class.java.isAssignableFrom(it.type)
    } ?: return
    val helperField = cls.declaredFields.firstOrNull { f ->
        runCatching {
            f.type.declaredFields.any { it.type == TextPaint::class.java }
        }.getOrDefault(false)
    } ?: return
    shapeField.isAccessible = true
    helperField.isAccessible = true
    val shape = shapeField.get(d) as? Drawable ?: return
    // Tint, not a paint write: the shape re-derives its paints from state, and tint survives that.
    shape.setTintList(
        ColorStateList.valueOf(
            if (navUnreadBg != 0) navUnreadBg else glassTint(CHIP_ALPHA)
        )
    )
    val helper = helperField.get(d) ?: return
    val tp = helper.javaClass.declaredFields
        .first { it.type == TextPaint::class.java }
        .apply { isAccessible = true }
        .get(helper) as? TextPaint ?: return
    tp.color = if (navUnreadText != 0) navUnreadText else 0xFFFFFFFF.toInt()
    logOnce("nav count badge themed (${cls.name})")
}
