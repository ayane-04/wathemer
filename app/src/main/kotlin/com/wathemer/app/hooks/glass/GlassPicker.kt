// The contact picker, the me tab's profile card, and the two search-bar components. One pane per
// corner radius: a GlassBubblePane stamps every rect it draws with the same one.
package com.wathemer.app.hooks.glass

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import com.wathemer.app.glass.GlassBubblePane
import com.wathemer.app.glass.GlassParams
import com.wathemer.app.glass.GlassView
import com.wathemer.app.glass.RectList
import com.wathemer.app.hooks.waId
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/** Half the gap between picker cards; without it adjacent groups share an edge and read as one shape. */
private const val PICKER_CARD_GAP_DP = 6f

private val pickerCardTag = tagKey("wathemer-picker-card")

private val pickerDiscTag = tagKey("wathemer-picker-disc")

private var pickerListRef: WeakReference<ListView>? = null

private val pickerAt = IntArray(2)

/** Two cards from one pane over one ListView; the toolbar pill is its own pane, one corner radius per pane. */
internal fun pickerGlass(root: ViewGroup) {
    // A MATCH_PARENT pane may only go in a parent whose own size does not depend on its children; content is that parent.
    val host = root.rootView?.findViewById<View>(android.R.id.content) as? FrameLayout
    if (host == null) {
        logOnce("contact picker: no full-size content host")
        return
    }
    val listHost = root.findViewById<View>(
        root.resources.waId("contact_list", root.context.packageName),
    ) as? ViewGroup
    val list = listHost?.let { h ->
        (0 until h.childCount).map { h.getChildAt(it) }
            .firstOrNull { it is ListView } as? ListView
    }
    if (list != null) {
        pickerListRef = WeakReference(list)
        list.clipToPadding = false
    }
    if (host.getTag(pickerCardTag) != null) return
    host.setTag(pickerCardTag, true)
    val d = host.resources.displayMetrics.density
    val toolbar = root.findViewById<View>(
        root.resources.waId("toolbar", root.context.packageName),
    )

    // Two panes: a GlassBubblePane carries one corner radius for everything it stamps.
    fun pane(radiusDp: Float, collect: (GlassBubblePane, RectList) -> Unit, what: String) {
        val g = GlassBubblePane(host.context)
        g.params = GlassParams(d).apply {
            blurRadius = d * BLUR_DP
            cornerRadius = d * radiusDp
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
        g.collect = { out -> collect(g, out) }
        g.onGeometryChanged = { markWallpaperGeometryDirty() }
        host.addView(
            g, 0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        XposedBridge.log("[$TAG] contact picker: $what inserted")
    }

    pane(CARD_RADIUS_DP, { _, out -> collectPickerCards(out) }, "cards pane")
    // The bar, not the toolbar in it: the two swap during search and the bar's own box does not move.
    val header = root.findViewById<View>(
        root.resources.waId("wds_search_bar", root.context.packageName),
    ) ?: toolbar
    if (header != null) {
        root.viewTreeObserver.addOnGlobalLayoutListener {
            runCatching { syncPickerBand(root, header) }
        }
        syncPickerBand(root, header)
    }
}

// Resolved once: waId is an uncached getIdentifier and this collect runs per pre-draw frame.
private var pickerIdsResolved = false

private var pickerActionIds = IntArray(0)

internal var pickerFooterId = 0

private var pickerSelectorId = 0

private var pickerPhotoId = 0

private var pickerNameId = 0

/** Kind, height and the label it was classified at; two row types share a height, so height alone let a recycled view keep the wrong kind. */
private class PickerRowKind(val kind: Int, val height: Int, val name: CharSequence?)

private val pickerRowKinds = WeakHashMap<View, PickerRowKind>()

/** The name view per row. Cached because the subtree walk is the cost; its text is a field read. */
private val pickerNameViews = WeakHashMap<View, TextView>()

private var loggedGlyph = false

private fun pickerNameOf(row: View): CharSequence? {
    if (pickerNameId == 0) return null
    val tv = pickerNameViews[row] ?: (row.findViewById<View>(pickerNameId) as? TextView)
        ?.also { pickerNameViews[row] = it } ?: return null
    return tv.text
}

/** Rows sort by contained id; the section header carries none, which is what makes the gap between the cards. */
private fun collectPickerCards(out: RectList) {
    val list = pickerListRef?.get() ?: return
    if (!list.isShown) return
    val pkg = list.context.packageName
    if (!pickerIdsResolved) {
        pickerIdsResolved = true
        // Their own ids: header_footer_row is the invite rows' layout and carries a contact_selector too.
        pickerActionIds = intArrayOf(
            list.resources.waId("menuitem_new_group_row", pkg),
            list.resources.waId("menuitem_new_contact_row", pkg),
            list.resources.waId("menuitem_new_communities_row", pkg),
            list.resources.waId("menuitem_new_broadcast", pkg),
        ).filter { it != 0 }.toIntArray()
        pickerFooterId = list.resources.waId("header_footer_row", pkg)
        pickerSelectorId = list.resources.waId("contact_selector", pkg)
        pickerPhotoId = list.resources.waId("contactpicker_row_photo", pkg)
        pickerNameId = list.resources.waId("contactpicker_row_name", pkg)
    }
    val ix = list.dp(CARD_INSET_DP)
    var aT = Float.MAX_VALUE
    var aB = -Float.MAX_VALUE
    var cT = Float.MAX_VALUE
    var cB = -Float.MAX_VALUE
    var fT = Float.MAX_VALUE
    var fB = -Float.MAX_VALUE
    for (i in 0 until list.childCount) {
        val row = list.getChildAt(i) ?: continue
        if (row.height <= 0 || row.visibility != View.VISIBLE) continue
        // Cached per row: the findViewById subtree walks are what this avoids.
        val cached = pickerRowKinds[row]
        val shown = pickerNameOf(row)
        val kind: Int
        if (cached != null && cached.height == row.height && cached.name == shown) {
            kind = cached.kind
        } else {
            val isA = pickerActionIds.any { row.findViewById<View>(it) != null }
            val isF = !isA && pickerFooterId != 0 && row.findViewById<View>(pickerFooterId) != null
            val isC = !isA && !isF && pickerSelectorId != 0 &&
                row.findViewById<View>(pickerSelectorId) != null
            kind = if (isA) 1 else if (isC) 2 else if (isF) 3 else 0
            pickerRowKinds[row] = PickerRowKind(kind, row.height, shown)
        }
        if (kind == 0) continue
        if (kind == 1) runCatching { glassActionDisc(row) }
        if (kind == 3) runCatching { frostPickerDiscs(row) }
        row.getLocationOnScreen(pickerAt)
        val t = pickerAt[1].toFloat()
        val b = (pickerAt[1] + row.height).toFloat()
        when (kind) {
            1 -> {
                if (t < aT) aT = t
                if (b > aB) aB = b
            }
            2 -> {
                if (t < cT) cT = t
                if (b > cB) cB = b
            }
            else -> {
                if (t < fT) fT = t
                if (b > fB) fB = b
            }
        }
    }
    val l = ix
    val r = list.width - ix
    if (r <= l) return
    val gap = list.dp(PICKER_CARD_GAP_DP)
    val radius = list.dp(CARD_RADIUS_DP)

    // One clip, the box the topmost card is clamped to; a partly scrolled row reaches the list's edge.
    val clipTop = gap.toInt()
    val provider = list.outlineProvider as? CardOutline
    if (provider == null) {
        list.outlineProvider = CardOutline(l.toInt(), clipTop, r.toInt(), list.height, radius)
        list.clipToOutline = true
        list.invalidateOutline()
    } else if (provider.set(l.toInt(), clipTop, r.toInt(), list.height, radius)) {
        // Gated on a real move: unconditional from this per-frame path it schedules frames forever.
        list.invalidateOutline()
    }

    // Clamped to the clip, not the list's edge: that is what puts air between the header and the card.
    list.getLocationOnScreen(pickerAt)
    val top = (pickerAt[1] + clipTop).toFloat()
    val bottom = (pickerAt[1] + list.height).toFloat()
    // Unrolled: a listOf of pairs boxes four floats per pre-draw frame on this path.
    fun emit(t0: Float, b0: Float) {
        if (b0 <= t0) return
        // Inset first, clamp second: the gap belongs between the groups.
        val t = (t0 + gap).coerceAtLeast(top)
        val b = (b0 - gap).coerceAtMost(bottom)
        if (b <= t) return
        out.add(pickerAt[0] + l, t, pickerAt[0] + r, b)
    }
    // Three groups, so the invite rows keep a card of their own instead of extending the contacts'.
    emit(aT, aB)
    emit(cT, cB)
    emit(fT, fB)
}

/**
 * The New rows' discs. WhatsApp bakes the accent circle and the white glyph into one bitmap, so
 * there is no fill to clear: the glyph is lifted out and the circle behind it becomes glass.
 */
private fun glassActionDisc(row: View) {
    if (row.getTag(pickerDiscTag) != null) return
    val icon = firstImageIn(row, 0) ?: return
    if (icon.width <= 0 || icon.height <= 0) return
    row.setTag(pickerDiscTag, true)
    runCatching {
        val glyph = glyphOf(icon.resources, icon.drawable)
        if (glyph !== icon.drawable) icon.setImageDrawable(glyph)
        // For an icon that was never a bitmap: a vector keeps its theme fill, and community's is dark.
        icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
        frost(icon, allowSquare = true, ignorePadding = true)
    }
}

/** The row's icon has no id of its own, so it is found by shape: the first ImageView in it. */
private fun firstImageIn(v: View, depth: Int): ImageView? {
    if (v is ImageView) return v
    if (depth >= 4 || v !is ViewGroup) return null
    for (i in 0 until v.childCount) {
        firstImageIn(v.getChildAt(i) ?: continue, depth + 1)?.let { return it }
    }
    return null
}

/** The glyph, white, whatever the icon is made of; the mask is read from the bitmap's own opacity, never assumed. */
private fun glyphOf(res: android.content.res.Resources, d: Drawable?): Drawable? {
    val src = (d as? BitmapDrawable)?.bitmap ?: return d
    return runCatching {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // Quantised to 5 bits a channel, so antialiasing does not split the fill into a thousand keys.
        val counts = HashMap<Int, Int>()
        var opaque = 0
        for (p in px) {
            if (p ushr 24 < 128) continue
            opaque++
            val q = p and 0xF8F8F8
            counts[q] = (counts[q] ?: 0) + 1
        }
        val discInBitmap = opaque * 2 > px.size
        val fill = counts.maxByOrNull { it.value }?.key ?: 0
        if (!loggedGlyph) {
            loggedGlyph = true
            XposedBridge.log(
                "[$TAG] action glyph " + w + "x" + h + " opaque=" + opaque + "/" + px.size +
                    " discInBitmap=" + discInBitmap + " fill=" + Integer.toHexString(fill),
            )
        }
        val fr = (fill shr 16) and 0xFF
        val fg = (fill shr 8) and 0xFF
        val fb = fill and 0xFF

        for (i in px.indices) {
            val p = px[i]
            val a = p ushr 24
            if (a == 0) {
                px[i] = 0
                continue
            }
            val m = if (discInBitmap) {
                val dr = (p shr 16) and 0xFF
                val dg = (p shr 8) and 0xFF
                val db = p and 0xFF
                // Saturating at 255 of summed channel distance: a white glyph and a dark one both clear it.
                val dist = kotlin.math.abs(dr - fr) + kotlin.math.abs(dg - fg) +
                    kotlin.math.abs(db - fb)
                if (dist >= 255) 255 else dist
            } else {
                a
            }
            px[i] = ((m * a / 255) shl 24) or 0xFFFFFF
        }
        BitmapDrawable(res, Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888))
    }.getOrDefault(d)
}

/** Scoped to the row: the same photo id is every contact's avatar, and frosting those would erase the photos. */
private fun frostPickerDiscs(row: View) {
    // The caller hands us classified rows only; the row tag makes repeat frames a single getTag.
    if (row.getTag(pickerDiscTag) != null) return
    if (pickerPhotoId == 0) return
    val photo = row.findViewById<View>(pickerPhotoId) ?: return
    if (photo.getTag(pickerDiscTag) != null) return
    if (photo.width <= 0 || photo.height <= 0) return
    photo.setTag(pickerDiscTag, true)
    row.setTag(pickerDiscTag, true)
    runCatching { frost(photo, allowSquare = true, ignorePadding = true) }
}

/** Band and pill stack, so they split TINT_ALPHA: the sum over the overlap must equal one surface. */
private fun pickerBandAlpha(): Int = (TINT_ALPHA / 2).coerceAtLeast(0)

private fun pickerPillAlpha(): Int = (TINT_ALPHA - pickerBandAlpha()).coerceAtLeast(0)

private var pickerBandRef: WeakReference<GlassView>? = null

/** Full-width band from the top edge to the pill's bottom; it lives in action_bar_root because id/content carries the status inset as padding and clips it. */
private fun syncPickerBand(root: ViewGroup, header: View) {
    if (header.height <= 0) return
    val under = wallpaperUnderlay(root)
    // No wallpaper means nothing to transmit, and a tint-only band is just a grey wash.
    if (under.isEmpty()) return
    val abrId = root.resources.waId("action_bar_root", root.context.packageName)
    if (abrId == 0) return
    val abr = root.rootView?.findViewById<View>(abrId) as? FrameLayout ?: return

    val at = IntArray(2)
    val abrAt = IntArray(2)
    header.getLocationOnScreen(at)
    abr.getLocationOnScreen(abrAt)
    val total = (at[1] - abrAt[1]) + header.height
    if (total <= 0) return

    var band = pickerBandRef?.get()
    if (band == null || band.parent !== abr) {
        band = GlassView(abr.context).apply {
            // Wallpaper only: a full-bleed surface tracking live content reads as a flicker.
            underlay = under
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = root.dp(BLUR_DP)
                refractionEnabled = true
                // With the SDF expanded there is no bevel to size; a 1px nominal one keeps displacement at zero.
                bevelFraction = 0f
                bevelThickness = 1f
                depthRatio = DEPTH_RATIO
                maxDisplacePx = root.dp(DISPLACE_DP)
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
        // Directly after the wallpaper and its dim, or the band hides behind them.
        var insertAt = 0
        for (i in 0 until abr.childCount) {
            val c = abr.getChildAt(i)
            if (c === under.firstOrNull() || (under.size > 1 && c === under[1])) insertAt = i + 1
        }
        if (insertAt == 0) {
            XposedBridge.log(
                "[$TAG] WARN: wallpaper views are not children of action_bar_root; the picker " +
                    "band will be behind them and invisible",
            )
        }
        abr.addView(
            band, insertAt,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, total).apply {
                gravity = Gravity.TOP
            },
        )
        pickerBandRef = WeakReference(band)
        XposedBridge.log("[$TAG] contact picker band inserted at " + insertAt + " (" + total + "px)")
    }
    band.params.tintColor = glassTint(pickerBandAlpha())
    val lp = band.layoutParams as? FrameLayout.LayoutParams ?: return
    if (lp.height != total) {
        lp.height = total
        band.layoutParams = lp
    }
}

private var meTabContainerRef: WeakReference<View>? = null

private val meTabAt = IntArray(2)

private val meTabHeaderAt = IntArray(2)

private var meTabHeaderRef: WeakReference<View>? = null

private val meTabPaneTag = tagKey("wathemer-me-tab-pane")

/** A stamped card on me_tab_container's box behind the profile block; the page scrolls, so a fixed panel or a full-bounds frost would not fit. */
internal fun ensureMeTabCard(container: View) {
    meTabContainerRef = WeakReference(container)
    val host = container.rootView?.findViewById<View>(android.R.id.content) as? FrameLayout ?: return
    if (host.getTag(meTabPaneTag) != null) return
    if (!host.isAttachedToWindow) return
    host.setTag(meTabPaneTag, true)
    val d = host.resources.displayMetrics.density
    val g = GlassBubblePane(host.context)
    g.params = GlassParams(d).apply {
        blurRadius = d * BLUR_DP
        cornerRadius = d * CARD_RADIUS_DP
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
    g.collect = { out -> collectMeTabCard(out) }
    g.onGeometryChanged = { markWallpaperGeometryDirty() }
    host.addView(
        g, 0,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ),
    )
    XposedBridge.log("[$TAG] me tab profile card inserted")
}

private fun collectMeTabCard(out: RectList) {
    val c = meTabContainerRef?.get() ?: return
    if (!c.isShown || c.width <= 0 || c.height <= 0) return
    val side = c.dp(CARD_INSET_DP)
    val gap = c.dp(CARD_GAP_DP)
    c.getLocationOnScreen(meTabAt)
    // Clamped under the header. The header lives INSIDE this page's scroller and its motion scene
    // pins it while the block collapses behind it, so an unclamped rect rides up over the capsule.
    var top = meTabAt[1] + gap
    // Re-resolved whenever the cache is dead or belongs to another window: this page is rebuilt on
    // re-entry, and a detached header reads isShown false, so the clamp silently stops running.
    var header = meTabHeaderRef?.get()
    if (header == null || !header.isAttachedToWindow || header.rootView !== c.rootView) {
        header = c.rootView?.findViewById(
            c.resources.waId("wds_search_bar", c.context.packageName),
        )
        meTabHeaderRef = header?.let { WeakReference(it) }
    }
    if (header != null && header.isShown && header.height > 0) {
        header.getLocationOnScreen(meTabHeaderAt)
        top = maxOf(top, (meTabHeaderAt[1] + header.height + gap).toFloat())
    }
    val bottom = (meTabAt[1] + c.height).toFloat()

    // The clip FIRST, never behind an early return: a skipped frame leaves the last value
    // standing. A later sibling than the header, so it paints over it once that fill is gone.
    val cl = side.toInt()
    val ct = (top - meTabAt[1]).toInt().coerceIn(0, c.height)
    val cr = c.width - side.toInt()
    val cb = c.height
    // Capped at half the height: a bigger radius yields an outline that cannot clip.
    val radius = minOf(c.dp(CARD_RADIUS_DP), (cb - ct) / 2f).coerceAtLeast(0f)
    val provider = c.outlineProvider as? CardOutline
    if (provider == null) {
        c.outlineProvider = CardOutline(cl, ct, cr, cb, radius)
        c.invalidateOutline()
    } else if (provider.set(cl, ct, cr, cb, radius)) {
        // Gated on a real move: unconditional from this per-frame path it schedules frames forever.
        c.invalidateOutline()
    }
    // Asserted every sync, never once: returning to a retained page cleared it and the block bled.
    if (!c.clipToOutline) c.clipToOutline = true
    // Collapsed far enough that nothing of the block is left below the header.
    if (bottom <= top) return
    out.add(meTabAt[0] + side, top, meTabAt[0] + c.width - side, bottom)
}

private val wdsBarPanes = WeakHashMap<ViewGroup, GlassView>()

private val wdsBackAt = IntArray(2)

private val wdsRect = Rect()

private val wdsOwnerAt = IntArray(2)

internal val wdsBarTag = tagKey("wathemer-wds-search-bar")

/** A band behind this header means the capsule takes the remainder of the tint, not all of it. */
private fun headerBandBehind(bar: View): Boolean {
    val abrId = bar.resources.waId("action_bar_root", bar.context.packageName)
    val abr = if (abrId != 0) bar.rootView?.findViewById<View>(abrId) else null
    if (abr != null && folderBands[abr]?.parent != null) return true
    return pickerBandRef?.get()?.parent != null
}

/** The search_view id covers two unlike bar families; acted on only where the field's parent can hold a pane, else the fill is left alone on purpose, because clearing it with no pane makes the bar invisible. */
internal fun syncSearchViewGlass(field: View) {
    val host = field.parent as? FrameLayout ?: return
    if (field.width <= 0 || field.height <= 0) return
    val res = field.resources
    val pkg = field.context.packageName

    var glass = wdsBarPanes[host]
    if (glass == null || glass.parent !== host) {
        val under = wallpaperUnderlay(host)
        if (under.isEmpty()) return
        glass = GlassView(host.context).apply {
            underlay = under
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = host.dp(BLUR_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = host.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
            }
        }
        host.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
        wdsBarPanes[host] = glass
        XposedBridge.log("[$TAG] search view capsule inserted")
    }
    // Only now, with a pane behind it, is the fill safe to take; two unlike families share this id.
    if (field.background != null) clearBg(field, "search_view")
    for (n in listOf("search_edit_frame", "search_plate", "submit_area", "search_view_toolbar")) {
        val id = res.waId(n, pkg)
        if (id == 0) continue
        field.findViewById<View>(id)?.let { if (it.background != null) clearBg(it, n) }
    }
    glass.params.tintColor =
        glassTint(if (headerBandBehind(host)) pickerPillAlpha() else TINT_ALPHA)

    // The host's box sideways, not the field's: WhatsApp leaves the field flush left with a wide end margin.
    val side = host.dp(CARD_INSET_DP).toInt()
    val w = host.width - 2 * side
    if (w <= 0) return
    // Down to the bar's trim, or the action pane's lower edge shows under a pane cut to the field.
    val h = maxOf(field.height, host.height - host.dp(BAR_PANE_TRIM_DP).toInt() - field.top)
    val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
    if (lp.width != w || lp.height != h ||
        lp.leftMargin != side || lp.topMargin != field.top
    ) {
        lp.width = w
        lp.height = h
        lp.leftMargin = side
        lp.topMargin = field.top
        lp.gravity = Gravity.TOP or Gravity.START
        glass.layoutParams = lp
        glass.params.cornerRadius = h / 2f
    }
}

/** Several ids reach this one component, so the capsule and its listener arm from here rather than at each id. */
internal fun armWdsSearchBar(bar: FrameLayout, why: String) {
    if (bar.getTag(wdsBarTag) == null) {
        bar.setTag(wdsBarTag, true)
        bar.viewTreeObserver.addOnGlobalLayoutListener { runCatching { syncWdsSearchBar(bar) } }
    }
    runCatching { syncWdsSearchBar(bar) }
        .onFailure { logOnce("wds search bar glass threw on " + why + ": " + it) }
}

/** One GlassView capsule per WDSSearchBar, keyed on the component; it swaps a toolbar and a search view, and a stamped rect's light pass would not match a real pill. */
internal fun syncWdsSearchBar(bar: FrameLayout) {
    if (bar.width <= 0 || bar.height <= 0) return
    val res = bar.resources
    val pkg = bar.context.packageName
    val fieldId = res.waId("wds_search_view", pkg)
    val toolbarId = res.waId("toolbar", pkg)
    val field = (if (fieldId != 0) bar.findViewById<View>(fieldId) else null)
        ?.takeIf { it.isShown && it.height > 0 }
    val toolbar = (if (toolbarId != 0) bar.findViewById<View>(toolbarId) else null)
        ?.takeIf { it.isShown && it.height > 0 }
    // The field wins: while it is up the toolbar is still a child and still measures.
    val owner = field ?: toolbar ?: return
    if (owner.parent !== bar) return
    // Clear both: the search field's fill is on backgroundHolder, a CHILD, so clearing the field
    // itself clears nothing and its rounded rect stays over the glass.
    clearBg(owner, "wds search bar")
    val holderId = res.waId("backgroundHolder", pkg)
    (if (holderId != 0) owner.findViewById<View>(holderId) else null)
        ?.let { clearBg(it, "wds search field fill") }

    // One capsule per header: where the folder header already carries the pill, clearing the fills is the whole job.
    if (folderCapsuleOwns(bar)) {
        // Gated on a real change, or this per-layout path writes every frame.
        wdsBarPanes[bar]?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
        return
    }

    var glass = wdsBarPanes[bar]
    if (glass == null || glass.parent !== bar) {
        val under = wallpaperUnderlay(bar)
        // The fills are already cleared, so a missing pane leaves an invisible bar; never let that be silent.
        if (under.isEmpty()) {
            logOnce("wds search bar: no underlay, so the fill is cleared with no pane behind it")
            return
        }
        glass = GlassView(bar.context).apply {
            underlay = under
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = bar.dp(BLUR_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = bar.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
            }
        }
        // Index 0 draws behind the bar's own controls.
        bar.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
        wdsBarPanes[bar] = glass
        val name = runCatching { res.getResourceEntryName(bar.id) }.getOrNull() ?: "?"
        XposedBridge.log("[$TAG] wds search capsule inserted on " + name)
    }
    if (glass.visibility != View.VISIBLE) glass.visibility = View.VISIBLE
    // A backdrop only if it reaches up here: sampled at our own position, a lower list reads black.
    val list = bar.rootView?.findViewById<View>(android.R.id.list)
    if (list != null) {
        list.getLocationOnScreen(wdsBackAt)
        owner.getLocationOnScreen(wdsOwnerAt)
        val covers = wdsBackAt[1] <= wdsOwnerAt[1]
        if (covers && glass.backdrop !== list) {
            runCatching { glass.backdrop = list }
                .onFailure { logOnce("wds search backdrop rejected: $it") }
        } else if (!covers && glass.backdrop != null) {
            glass.backdrop = null
        }
    }
    glass.params.tintColor =
        glassTint(if (headerBandBehind(bar)) pickerPillAlpha() else TINT_ALPHA)

    // The bar's own box in both states: one box cannot disagree with itself, so the swap is seamless.
    val side = bar.dp(CARD_INSET_DP).toInt()
    val box = wdsRect
    box.set(side, 0, bar.width - side, bar.height)
    if (box.width() <= 0 || box.height() <= 0) return
    val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
    if (lp.width != box.width() || lp.height != box.height() ||
        lp.leftMargin != box.left || lp.topMargin != box.top
    ) {
        lp.width = box.width()
        lp.height = box.height()
        lp.leftMargin = box.left
        lp.topMargin = box.top
        lp.gravity = Gravity.TOP or Gravity.START
        glass.layoutParams = lp
        glass.params.cornerRadius = box.height() / 2f
    }
}
