// The search screen: the field's rise, the pill that flies from the home bar and back, the filter
// chips, and the cross-fade that defers WhatsApp's own hide of the home content.
package com.wathemer.app.hooks.glass

import android.content.res.ColorStateList
import android.graphics.Rect
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.wathemer.app.glass.GlassBubblePane
import com.wathemer.app.glass.GlassParams
import com.wathemer.app.glass.GlassView
import com.wathemer.app.glass.RectList
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

internal var searchBarRef: WeakReference<View>? = null

internal var searchInputId = 0

internal var searchResultListId = 0

internal var searchDividerId = 0

private var searchToolbarRef: WeakReference<View>? = null

private var searchPanelRef: WeakReference<View>? = null

private var searchLowerOffset = 0

private var searchRaised = false

private val searchWatcherTag = tagKey("wathemer-search-watcher")

private val searchLayoutTag = tagKey("wathemer-search-layout")

internal val homeBarLayoutTag = tagKey("wathemer-home-bar-layout")

/** The home screen's own search bar, in `id/content`'s coordinates. See the writer for why. */
@Volatile internal var homeBarTop = -1

@Volatile internal var homeBarHeight = 0

private var searchEntryPending = false

/** The field's last resting place, recorded continuously: by back-out the fragment is gone with nothing left to measure. */
private var searchFieldRestTop = -1

private var searchFieldRestH = 0

// ── The hand-off ───────────────────────────────────────────────────────────────────────
// The pill starts at the home bar's rect and glides home; animate the transform, never the layout, and no post(), one frame would escape.
private const val SEARCH_ANIM_MS = 380L

private const val SEARCH_MOVE_MS = 260L

/** Resting top as a fraction of the full display, not the keyboard-shrunk window, or the field would move with the keyboard. */
private const val SEARCH_FIELD_TOP_FRACTION = 0.29f

/** Breathing room between the status bar and the raised search field; see [setSearchRaised]. */
private const val SEARCH_RAISED_GAP_DP = 6f

/** Corner radius of a single search result's glass. */
private const val SEARCH_ROW_RADIUS_DP = 14f

/** Runs on every attach of search_fragment; everything is idempotent except the two tag-guarded registrations. */
internal fun layoutSearchScreen(fragment: View) {
    val root = fragment as? ViewGroup ?: return
    val input = root.findViewById<View>(searchInputId) ?: run {
        logOnce("search: no search_input under search_fragment; layout skipped")
        return
    }
    // The toolbar by structure, not by the generic toolbar id: the direct child holding the input can only be the right row.
    val toolbar = directChildContaining(root, input) ?: run {
        logOnce("search: search_input is not inside a child of search_fragment")
        return
    }
    searchToolbarRef = WeakReference(toolbar)

    // Re-derived on every attach: it moves with the status-bar inset and rotation.
    val loc = IntArray(2)
    root.getLocationOnScreen(loc)
    val screenH = root.resources.displayMetrics.heightPixels
    searchLowerOffset = ((screenH * SEARCH_FIELD_TOP_FRACTION).toInt() - loc[1]).coerceAtLeast(0)

    // The hairline travels down with the field and cuts across the panel; INVISIBLE, not GONE, so nothing shifts.
    searchDividerId.takeIf { it != 0 }
        ?.let { root.findViewById<View>(it) }
        ?.let { if (it.visibility != View.INVISIBLE) it.visibility = View.INVISIBLE }

    // Read the field rather than assume empty: a relaunch can restore straight into a queried search.
    val hasQuery = searchQueryLength(input) > 0
    searchRaised = !hasQuery          // force the first setSearchRaised to actually apply
    setSearchRaised(hasQuery, animate = false)
    // Only the empty-query entry gets the hand-off; a restored search had no home bar to travel from.
    searchEntryPending = !hasQuery

    if (input.getTag(searchWatcherTag) == null && input is TextView) {
        input.setTag(searchWatcherTag, true)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                setSearchRaised(visibleLength(s) > 0)
            }
        })
    }

    injectSearchPanel(toolbar)
    if (toolbar.getTag(searchLayoutTag) == null) {
        toolbar.setTag(searchLayoutTag, true)
        toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncSearchPanel()
            // After syncSearchPanel, never before: until the panel has a size there is nothing to scale from.
            if (searchEntryPending && toolbar.height > 0) {
                searchEntryPending = false
                runCatching { animateSearchEntry(toolbar, root) }
            }
        }
    }
    toolbar.post { syncSearchPanel() }

    // Driven from the list's own layout: it binds long after attach and re-binds on every keystroke.
    root.findViewById<View>(searchResultListId)?.let { list ->
        if (list.getTag(searchLayoutTag) == null) {
            list.setTag(searchLayoutTag, true)
            list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                runCatching { syncSearchFilters(root) }
            }
            // A scroll driver too: recycling re-binds opaque chips without any layout; pre-draw with an O(1) early-out on child 0's top and identity.
            (list as? ViewGroup)?.let { lv ->
                val observer = lv.viewTreeObserver
                observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    private var lastTop = Int.MIN_VALUE
                    private var lastFirst = 0
                    override fun onPreDraw(): Boolean {
                        // The observer outlives the fragment: self-remove on detach and clear the tag so the next search re-registers.
                        if (!lv.isAttachedToWindow) {
                            if (observer.isAlive) observer.removeOnPreDrawListener(this)
                            lv.setTag(searchLayoutTag, null)
                            return true
                        }
                        val first = lv.getChildAt(0)
                        val top = first?.top ?: Int.MIN_VALUE
                        val id = System.identityHashCode(first)
                        if (top != lastTop || id != lastFirst) {
                            lastTop = top
                            lastFirst = id
                            runCatching { syncSearchFilters(root) }
                        }
                        return true
                    }
                })
            }
        }
        list.post { runCatching { syncSearchFilters(root) } }
    }
}

// ── The filter chips ───────────────────────────────────────────────────────────────────
// A real Material ChipGroup: wrapping needs singleLine off plus UNSPECIFIED rewritten to AT_MOST, hooked on whichever class declares onMeasure.
private val wrapChipGroups = WeakHashMap<View, Boolean>()

private var chipWrapHooked = false

private fun ensureChipWrapHook(group: View) {
    if (chipWrapHooked) return
    var c: Class<*>? = group.javaClass
    while (c != null) {
        val m = runCatching {
            c.getDeclaredMethod(
                "onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
        }.getOrNull()
        if (m != null) {
            chipWrapHooked = true
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    if (synchronized(wrapChipGroups) { wrapChipGroups[v] } != true) return
                    val spec = param.args[0] as Int
                    if (View.MeasureSpec.getMode(spec) != View.MeasureSpec.UNSPECIFIED) return
                    var size = View.MeasureSpec.getSize(spec)
                    // Fallbacks in trust order: spec size, parent inner width, display; a zero would collapse every chip onto its own line.
                    if (size <= 0) {
                        val p = v.parent as? View
                        if (p != null) size = p.width - p.paddingLeft - p.paddingRight
                    }
                    if (size <= 0) size = v.resources.displayMetrics.widthPixels
                    param.args[0] =
                        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST)
                }
            })
            XposedBridge.log("[$TAG] chip wrap hook armed on ${c.name}.onMeasure")
            return
        }
        c = c.superclass
    }
    XposedBridge.log("[$TAG] chip wrap FAILED: no onMeasure declared above ${group.javaClass.name}")
}

/** Last ChipGroup [searchChipGroup] found, so the scan runs once per search screen. */
private var searchChipGroupRef: WeakReference<ViewGroup>? = null

/** Found by scan, not index (RecyclerView reorders children); re-scanned when the cached ref goes stale, and a miss is normal. */
private fun searchChipGroup(list: ViewGroup): ViewGroup? {
    searchChipGroupRef?.get()?.let { if (it.isAttachedToWindow) return it }
    for (i in 0 until list.childCount) {
        val g = findChipGroup(list.getChildAt(i), 0) ?: continue
        searchChipGroupRef = WeakReference(g)
        logOnce("search filter chips: ChipGroup found (${g.childCount} chips)")
        return g
    }
    return null
}

/** Wrap the filters onto as many rows as they need; a single line shows five of eleven, and a mode that never toggles cannot stick. */
private fun syncSearchFilters(root: ViewGroup) {
    val list = root.findViewById<ViewGroup>(searchResultListId) ?: return
    if (list.childCount == 0) return
    // A miss here is silent and permanent: a renamed ChipGroup or a deeper row stops frost and wrap with nothing in the log.
    val group = searchChipGroup(list) ?: return

    if (synchronized(wrapChipGroups) { wrapChipGroups[group] } != true) {
        ensureChipWrapHook(group)
        synchronized(wrapChipGroups) { wrapChipGroups[group] = true }
        // FlowLayout tests singleLine before wrapping, so the spec rewrite alone changes nothing; reflective, no Material dependency.
        runCatching { XposedHelpers.callMethod(group, "setSingleLine", false) }
            .onFailure { XposedBridge.log("[$TAG] setSingleLine failed: $it") }
        // The scrolling parent must be free to grow, or the extra rows are simply clipped.
        (group.parent as? View)?.let { p ->
            val lp = p.layoutParams
            if (lp != null && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                p.layoutParams = lp
            }
        }
        group.requestLayout()
        XposedBridge.log("[$TAG] search filters wrapped (${group.childCount} chips)")
    }
    // Re-run every layout, unguarded: recycled chips re-bind and need painting again each time.
    for (i in 0 until group.childCount) {
        runCatching { styleSearchChip(group.getChildAt(i) ?: return@runCatching) }
    }
}

/** Built once and reused: the chip setters compare by reference, and a fresh equal list would invalidate every chip each layout. */
private var chipCslFor = Int.MIN_VALUE

private var chipFillCsl: ColorStateList? = null

private var chipRimCsl: ColorStateList? = null

/** A Chip drops setBackground on the floor, so [frost] cannot work; setChipBackgroundColor and the stroke pair are the honoured API. */
private fun styleSearchChip(chip: View) {
    // No done-guard on purpose: a re-bind restores opaque colours while a done-tag would survive it.
    val key = glassTint(CHIP_ALPHA)
    if (chipFillCsl == null || chipCslFor != key) {
        chipCslFor = key
        chipFillCsl = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(glassTint(CHIP_ALPHA_SELECTED), glassTint(CHIP_ALPHA)),
        )
        chipRimCsl = ColorStateList.valueOf(glassTint(CHIP_RIM_ALPHA))
    }
    val ok = runCatching {
        XposedHelpers.callMethod(chip, "setChipBackgroundColor", chipFillCsl)
        XposedHelpers.callMethod(chip, "setChipStrokeColor", chipRimCsl)
        XposedHelpers.callMethod(chip, "setChipStrokeWidth", chip.dp(1f))
    }.isSuccess
    if (!ok) logOnce("search chip styling failed; Chip API renamed?")
}

/** Anchored on the class NAME, which R8 keeps; a rename kills this silently, and the missing one-time "found" log is the signal. */
private fun findChipGroup(v: View?, depth: Int): ViewGroup? {
    if (v == null || depth > 4) return null
    if (v is ViewGroup) {
        if (v.javaClass.name.endsWith("chip.ChipGroup")) return v
        for (i in 0 until v.childCount) findChipGroup(v.getChildAt(i), depth + 1)?.let { return it }
    }
    return null
}

/** Visible characters, not text.length: the tokenising field keeps invisible anchor characters, so isNotEmpty lies. */
private fun visibleLength(s: CharSequence?): Int {
    if (s.isNullOrEmpty()) return 0
    var n = 0
    for (c in s) {
        if (c.isWhitespace()) continue
        val type = Character.getType(c)
        if (type == Character.FORMAT.toInt() || type == Character.CONTROL.toInt()) continue
        n++
    }
    return n
}

private fun searchQueryLength(input: View): Int {
    val t = (input as? TextView)?.text
    return visibleLength(t)
}

/** The direct child of [parent] that has [descendant] somewhere beneath it, or null. */
private fun directChildContaining(parent: ViewGroup, descendant: View): View? {
    var v: View? = descendant
    while (v != null) {
        val p = v.parent
        if (p === parent) return v
        v = p as? View
    }
    return null
}

/** Raise or lower the field with a toolbar top margin; the LinearLayout reflows everything below for free. */
private fun setSearchRaised(raised: Boolean, animate: Boolean = true) {
    if (raised == searchRaised) return
    val toolbar = searchToolbarRef?.get() ?: return
    val lp = toolbar.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    searchRaised = raised
    // Raised means the status inset, not zero, which clipped the field; read live, cutouts and split-screen change it.
    val statusInset = runCatching {
        toolbar.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top
    }.getOrNull() ?: 0
    val want = if (raised) statusInset + toolbar.dp(SEARCH_RAISED_GAP_DP).toInt() else searchLowerOffset
    val from = lp.topMargin
    if (lp.topMargin != want) {
        lp.topMargin = want
        toolbar.layoutParams = lp
    }
    // Only when already on screen and actually moving; the first placement of a session is [animateSearchEntry]'s job.
    if (animate && toolbar.height > 0 && from != want) {
        glideSearchBy((from - want).toFloat(), SEARCH_MOVE_MS)
    }
    XposedBridge.log("[$TAG] search field ${if (raised) "raised" else "lowered to $want"}")
}

/** Move field and pill together and settle to zero; [syncSearchPanel] reads layout positions, blind to translation, so they never fight. */
private fun glideSearchBy(delta: Float, duration: Long) {
    val toolbar = searchToolbarRef?.get() ?: return
    val panel = searchPanelRef?.get()
    toolbar.translationY = delta
    panel?.translationY = delta
    toolbar.animate().translationY(0f).setDuration(duration)
        .setInterpolator(searchInterp).start()
    panel?.animate()?.translationY(0f)?.setDuration(duration)
        ?.setInterpolator(searchInterp)?.start()
}

/** The entry hand-off: centres matched, not tops (the bars differ in height), and it bails when the home bar was never seen. */
private fun animateSearchEntry(toolbar: View, root: ViewGroup) {
    if (homeBarTop < 0 || homeBarHeight <= 0 || toolbar.height <= 0) return
    val content = contentRef?.get() ?: return
    // Both sides layout-relative and blind to translation, so the delta is between resting positions, not animation frames.
    val tb = Rect(0, 0, toolbar.width, toolbar.height)
    if (!runCatching { content.offsetDescendantRectToMyCoords(toolbar, tb) }.isSuccess) return
    val delta = (homeBarTop + homeBarHeight / 2f) - (tb.top + tb.height() / 2f)
    if (abs(delta) < 1f) return
    // More than half the screen means a wrong reading; refuse with a log rather than slide in from off-screen.
    if (abs(delta) > root.resources.displayMetrics.heightPixels / 2f) {
        XposedBridge.log("[$TAG] search hand-off REFUSED: implausible delta=${delta.toInt()}px")
        return
    }

    val panel = searchPanelRef?.get()
    toolbar.translationY = delta
    panel?.let {
        it.translationY = delta
        if (it.height > 0) {
            it.scaleY = (homeBarHeight.toFloat() / it.height).coerceIn(0.4f, 1f)
        }
    }
    toolbar.animate().translationY(0f).setDuration(SEARCH_ANIM_MS)
        .setInterpolator(searchInterp).start()
    panel?.animate()?.translationY(0f)?.scaleY(1f)?.setDuration(SEARCH_ANIM_MS)
        ?.setInterpolator(searchInterp)?.start()

    // The filters rise in slightly late so the eye follows the pill first.
    root.findViewById<View>(searchResultListId)?.let { list ->
        list.alpha = 0f
        list.translationY = root.dp(20f)
        list.animate().alpha(1f).translationY(0f)
            .setStartDelay(70).setDuration(SEARCH_ANIM_MS)
            .setInterpolator(searchInterp).start()
    }
    XposedBridge.log("[$TAG] search hand-off from home bar (delta=${delta.toInt()}px)")
}

/** The return leg animates the INCOMING bar: the fragment dies in one frame, and an outgoing pill would glide away empty. */
private fun animateHomeBarReturn() {
    val bar = searchBarRef?.get() ?: return
    if (bar.height <= 0 || searchFieldRestH <= 0 || homeBarTop < 0 || homeBarHeight <= 0) return
    val delta = (searchFieldRestTop + searchFieldRestH / 2f) -
        (homeBarTop + homeBarHeight / 2f)
    if (abs(delta) < 1f) return
    if (abs(delta) > bar.resources.displayMetrics.heightPixels / 2f) {
        XposedBridge.log("[$TAG] return hand-off REFUSED: implausible delta=${delta.toInt()}px")
        return
    }
    bar.animate().cancel()
    bar.translationY = delta
    bar.animate().translationY(0f).setDuration(SEARCH_ANIM_MS)
        .setInterpolator(searchInterp).start()
    XposedBridge.log("[$TAG] return hand-off to home bar (delta=${delta.toInt()}px)")
}

/** The search field's panel, in id/content because the fragment cannot stack children; bound to the toolbar so it leaves with search. */
private fun injectSearchPanel(toolbar: View) {
    val content = contentRef?.get() ?: return
    if (!content.isAttachedToWindow) return
    var glass = searchPanelRef?.get()
    if (glass == null || glass.parent !== content) {
        glass = GlassView(content.context).apply {
            underlay = wallpaperUnderlay(content)
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = content.dp(BLUR_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = content.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                tintColor = glassTintColor
            }
        }
        // Index 0, behind the fragment, so WhatsApp's field and icons keep painting on top.
        content.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
        searchPanelRef = WeakReference(glass)
        XposedBridge.log("[$TAG] search panel inserted")
    }
    // bindPane on every attach, outside the creation branch: each search open brings a new toolbar, and a pane that outlives its anchor must re-bind.
    bindPane(glass, toolbar, "search panel") { animateHomeBarReturn() }

    // Reused across searches, so shed the last session's transform or a cancelled hand-off strands it at 0.6 scale.
    glass.animate().cancel()
    glass.translationY = 0f
    glass.scaleY = 1f
}

/** Track the field's rect. Called on every layout of the toolbar, so it follows the rise. */
private fun syncSearchPanel() {
    val content = contentRef?.get() ?: return
    val glass = searchPanelRef?.get() as? GlassView ?: return
    val toolbar = searchToolbarRef?.get() ?: return
    if (toolbar.width <= 0 || toolbar.height <= 0) return
    val rect = Rect(0, 0, toolbar.width, toolbar.height)
    if (!runCatching { content.offsetDescendantRectToMyCoords(toolbar, rect) }.isSuccess) return
    // The resting place for the return trip; layout coordinates stay truthful mid-glide.
    searchFieldRestTop = rect.top
    searchFieldRestH = rect.height()

    val padV = content.dp(6f).toInt()
    val l = rect.left
    val t = rect.top - padV
    val r = rect.right
    val b = rect.bottom + padV
    if (r <= l || b <= t) return

    val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
    if (lp.width != r - l || lp.height != b - t ||
        lp.leftMargin != l - content.paddingLeft || lp.topMargin != t - content.paddingTop
    ) {
        lp.width = r - l
        lp.height = b - t
        lp.leftMargin = l - content.paddingLeft
        lp.topMargin = t - content.paddingTop
        lp.gravity = Gravity.TOP or Gravity.START
        glass.layoutParams = lp
        glass.params.cornerRadius = (b - t) / 2f
    }
}

/** Pane in my_search_bar sized to the field (the field cannot stack); backdrop is id/list, refused per frame once WhatsApp seats the bar inside it. */
internal fun injectSearchFieldGlass(inner: View) {
    val bar = inner.parent as? FrameLayout ?: return
    if (bar.getTag(innerGlassTag) != null) return
    if (!bar.isAttachedToWindow) return
    bar.setTag(innerGlassTag, true)

    val glass = GlassView(bar.context).apply {
        backdrop = listRef?.get()
        underlay = wallpaperUnderlay(bar)
        params.apply {
            downsample = DOWNSAMPLE
            blurRadius = bar.dp(BLUR_DP)
            refractionEnabled = true
            bevelFraction = BEVEL_FRACTION
            depthRatio = DEPTH_RATIO
            maxDisplacePx = bar.dp(DISPLACE_DP)
            fresnelStrength = 0.4f
            tintColor = glassTint(TINT_ALPHA)
        }
    }
    bar.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
    val sync = Runnable {
        if (inner.width <= 0 || inner.height <= 0) return@Runnable
        // Resolve, don't remember: a backdrop bound once at construction can end up a detached list, frozen or blank.
        listRef?.get()?.let { l -> if (glass.backdrop !== l) glass.backdrop = l }
        val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return@Runnable
        if (lp.width != inner.width || lp.height != inner.height ||
            lp.leftMargin != inner.left || lp.topMargin != inner.top
        ) {
            lp.width = inner.width
            lp.height = inner.height
            lp.leftMargin = inner.left
            lp.topMargin = inner.top
            glass.layoutParams = lp
            glass.params.cornerRadius = inner.height / 2f
        }
    }
    inner.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync.run() }
    inner.post(sync)
    XposedBridge.log("[$TAG] search field pane added (backdrop=id/list)")
}

/** One pane behind the search results, stamping a rounded rect per result row. */
internal fun searchRowGlass(list: View) {
    if (list.getTag(searchRowGlassTag) != null) return
    // isAttachedToWindow is still false inside our own attach hook; tag first so a second attach cannot queue a pane.
    list.setTag(searchRowGlassTag, true)
    list.post { runCatching { insertSearchRowPane(list) }
        .onFailure { XposedBridge.log("[$TAG] insertSearchRowPane threw: $it") } }
}

private fun insertSearchRowPane(list: View) {
    // The list and its fragment holder are LinearLayouts, so neither hosts the pane; the same walk as panelGlass.
    var host = list.parent as? ViewGroup
    while (host != null && !canStack(host)) host = host.parent as? ViewGroup
    if (host == null) {
        logOnce("search rows: no stacking ancestor above result_list")
        return
    }
    // The host outlives the list, rebuilt per search open; re-point the surviving pane or panes accumulate, each pinning its dead list.
    (host.getTag(searchRowPaneTag) as? GlassBubblePane)?.let { pane ->
        if (pane.parent === host) {
            pane.collect = { out -> collectSearchRowRects(list, out) }
            XposedBridge.log("[$TAG] search row glass re-bound to new list")
            return
        }
    }
    val d = list.resources.displayMetrics.density
    val pane = GlassBubblePane(host.context).apply {
        params = GlassParams(d).apply {
            blurRadius = list.dp(BLUR_DP)
            cornerRadius = list.dp(SEARCH_ROW_RADIUS_DP)
            refractionEnabled = true
            bevelFraction = BEVEL_FRACTION
            depthRatio = DEPTH_RATIO
            maxDisplacePx = list.dp(DISPLACE_DP)
            fresnelStrength = 0.5f
            tintColor = glassTintColor
        }
        tint = { glassTintColor }
        // Through the list's Activity: the search fragment's own root holds no wallpaper, the same trap as the popup path.
        backdrop = { bubbleBackdrop(list) }
        placement = { bubblePlacement(list) }
        dim = { 0f }
        rimColor = glassTint(BUBBLE_RIM_ALPHA)
        rimWidth = d
        collect = { out -> collectSearchRowRects(list, out) }
        onGeometryChanged = { markWallpaperGeometryDirty() }
    }
    host.addView(
        pane, 0,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ),
    )
    host.setTag(searchRowPaneTag, pane)
    XposedBridge.log("[$TAG] search row glass inserted into ${host.javaClass.simpleName}")
}

/** One rect per result row, in screen px; skip the chip row by type, never by index, it recycles away. */
private fun collectSearchRowRects(list: View, out: RectList) {
    val group = list as? ViewGroup ?: return
    // One field read that ends the walk once the search closes and its list detaches.
    if (!group.isAttachedToWindow) return
    val inset = 1.5f * list.resources.displayMetrics.density
    for (i in 0 until group.childCount) {
        val row = group.getChildAt(i) as? ViewGroup ?: continue
        if (row is HorizontalScrollView) continue
        if (!row.isShown || row.height <= 0 || row.width <= 0) continue
        row.getLocationOnScreen(searchRowAt)
        out.add(
            searchRowAt[0].toFloat(),
            searchRowAt[1] + inset,
            (searchRowAt[0] + row.width).toFloat(),
            searchRowAt[1] + row.height - inset,
        )
    }
}

private val searchRowAt = IntArray(2)

private val searchRowGlassTag = tagKey("wathemer-search-row-glass")

private val searchRowPaneTag = tagKey("wathemer-search-row-pane")

private var pagerFadeHooked = false

private val fadeOnHide = WeakHashMap<View, Boolean>()

private val hideInProgress = WeakHashMap<View, Boolean>()

private val applyingHide = WeakHashMap<View, Boolean>()

/** The fade must be hook-driven: WhatsApp shows pager_holder without laying anything of ours out. */
internal fun ensurePagerFadeHook() {
    if (pagerFadeHooked) return
    pagerFadeHooked = true
    runCatching {
        XposedHelpers.findAndHookMethod(
            View::class.java, "setVisibility", Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    // First and cheapest: one map probe on a very hot path.
                    if (synchronized(fadeOnHide) { fadeOnHide[v] } != true) return
                    val now = v.visibility
                    param.extra.putInt("wtPrev", now)
                    val want = param.args[0] as Int
                    // A VISIBLE mid-fade must be caught here, before the edge test, which cannot see it while the deferred view still reads VISIBLE.
                    if (want == View.VISIBLE) {
                        cancelHideFade(v)
                        return
                    }
                    if (now != View.VISIBLE) return
                    // Our own re-entrant call from the end of the fade. Let it through.
                    if (synchronized(fadeOnHide) { applyingHide[v] } == true) return
                    param.result = null                    // defer the hide
                    startHideFade(v, want)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    if (synchronized(fadeOnHide) { fadeOnHide[v] } != true) return
                    if ((param.args[0] as Int) != View.VISIBLE) return
                    // Only a real INVISIBLE to VISIBLE edge (previous captured in the before-hook): search opens with a redundant VISIBLE that zeroed the alpha.
                    if (param.extra.getInt("wtPrev") == View.VISIBLE) return
                    v.animate().cancel()
                    v.alpha = 0f
                    v.animate().alpha(1f).setDuration(SEARCH_FADE_MS)
                        .setInterpolator(searchInterp).start()
                }
            },
        )
        XposedBridge.log("[$TAG] home cross-fade hook armed")
    }.onFailure { XposedBridge.log("[$TAG] cross-fade hook FAILED: $it") }
}

/** Defer the hide and dissolve: WhatsApp's own hide lands right after attach, no room to race; panes bound to [v] fade too, they inherit nothing. */
private fun startHideFade(v: View, want: Int) {
    if (synchronized(fadeOnHide) { hideInProgress[v] } == true) return
    synchronized(fadeOnHide) { hideInProgress[v] = true }
    val panes = paneBindings.filter { it.anchor.get() === v }.mapNotNull { it.pane.get() }
    for (p in panes) {
        p.animate().cancel()
        // Glass dissolves its material; only a non-glass pane falls back to an alpha fade.
        (p as? GlassView)?.materializeTo(0f, SEARCH_FADE_MS)
            ?: p.animate().alpha(0f).setDuration(SEARCH_FADE_MS)
                .setInterpolator(searchInterp).start()
    }
    v.animate().cancel()
    v.animate().alpha(0f).setDuration(SEARCH_FADE_MS).setInterpolator(searchInterp)
        .withEndAction {
            synchronized(fadeOnHide) {
                hideInProgress.remove(v)
                applyingHide[v] = true
            }
            runCatching { v.visibility = want }
            synchronized(fadeOnHide) { applyingHide.remove(v) }
            // The panes are deliberately not reset here, they would flash one frame at full alpha; [syncPaneVisibility] resets them.
            v.alpha = 1f
        }.start()
}

/** All four: cancel (drops the pending write), clear hideInProgress, restore anchor and pane alpha; visibility keeps its one writer. */
private fun cancelHideFade(v: View) {
    if (synchronized(fadeOnHide) { hideInProgress[v] } != true) return
    v.animate().cancel()
    v.alpha = 1f
    for (p in paneBindings.filter { it.anchor.get() === v }.mapNotNull { it.pane.get() }) {
        p.animate().cancel()
        p.alpha = 1f
        (p as? GlassView)?.materializeTo(1f, 0L)
    }
    synchronized(fadeOnHide) { hideInProgress.remove(v) }
    logOnce("hide fade retracted; a VISIBLE arrived while the hide was deferred")
}

// The FABs are deliberately never registered here: deferring their everyday hides for the fade feels sticky.
/** Fade this view out with the home screen whenever WhatsApp hides it. */
internal fun registerFadeOnHide(v: View?) {
    if (v == null) return
    synchronized(fadeOnHide) { fadeOnHide[v] = true }
    // No forceHasOverlappingRendering(false): measured, it changed nothing; the cost is drawing two screens, not the buffer.
}
