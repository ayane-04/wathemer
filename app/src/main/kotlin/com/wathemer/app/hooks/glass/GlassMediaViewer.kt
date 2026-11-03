// The media viewer's top strip and footer as live glass over the photo, the Reply pill frosted on it; switched off, the stock look stays.
package com.wathemer.app.hooks.glass

import android.content.res.Resources
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.wathemer.app.glass.FrostDrawable
import com.wathemer.app.glass.GlassView
import com.wathemer.app.hooks.HookLog
import com.wathemer.app.hooks.dispatch.ViewThemeDispatcher
import com.wathemer.app.hooks.waId
import com.wathemer.app.settings.prefs.GlassDefaults
import de.robv.android.xposed.XposedBridge
import kotlin.math.abs

/** Read at install with the other glass switches. */
internal var MEDIA_VIEWER = GlassDefaults.MEDIA_VIEWER

/** How far a strip reaches past the screen's edges, where its corners and side bevels are clipped away. */
private const val OVERHANG_DP = 24f

/** WhatsApp's own Reply pill colour, see-through, so the footer's glass shows under it. */
private const val REPLY_FROST = 0x99202C33.toInt()

private val topPaneTag = tagKey("wathemer-media-top")
private val footerPaneTag = tagKey("wathemer-media-footer")
private val replyFrostTag = tagKey("wathemer-media-reply-frost")
private val replyPadTag = tagKey("wathemer-media-pill-pad")
private val syncTag = tagKey("wathemer-media-sync")

private class MediaIds(
    val layout: Int,
    val container: Int,
    val toolbar: Int,
    val pager: Int,
    val background: Int,
    val footer: Int,
    val pill: Int,
)

internal fun installMediaViewerGlass(res: Resources, pkg: String) {
    val ids = MediaIds(
        layout = res.waId("media_view_layout", pkg),
        container = res.waId("toolbar_container", pkg),
        toolbar = res.waId("toolbar", pkg),
        pager = res.waId("pager_container", pkg),
        background = res.waId("background", pkg),
        footer = res.waId("footer", pkg),
        pill = res.waId("quick_reactions_reply_container", pkg),
    )
    if (ids.layout == 0 || ids.container == 0 || ids.toolbar == 0 || ids.pager == 0 || ids.footer == 0) {
        XposedBridge.log("[$TAG] media viewer ids missing; its glass stays off")
        HookLog.skip("glass/mediaViewer", "ids missing")
        return
    }
    ViewThemeDispatcher.onId(ids.container) { v ->
        if (insideId(v, ids.layout, 2)) {
            runCatching { (v as? ViewGroup)?.let { armTop(it, ids) } }.onFailure { HookLog.fail("glass/mediaViewer/top", it) }
        }
    }
    // A generic id: only a footer inside the viewer counts, a few levels down through the pager.
    ViewThemeDispatcher.onId(ids.footer) { v ->
        if (insideId(v, ids.layout, 8)) {
            runCatching { armFooter(v, ids) }.onFailure { HookLog.fail("glass/mediaViewer/footer", it) }
        }
    }
    if (ids.pill != 0) {
        ViewThemeDispatcher.onId(ids.pill) { v ->
            if (insideId(v, ids.layout, 14)) {
                runCatching { armPill(v) }.onFailure { HookLog.fail("glass/mediaViewer/pill", it) }
            }
        }
    }
    HookLog.arm("glass/mediaViewer")
}

/** The user's own glass, tint included, as on every other surface. */
private fun newPane(host: View): GlassView = GlassView(host.context).apply {
    params.apply {
        downsample = DOWNSAMPLE
        blurRadius = host.dp(BLUR_DP)
        refractionEnabled = true
        bevelFraction = BEVEL_FRACTION
        depthRatio = DEPTH_RATIO
        maxDisplacePx = host.dp(DISPLACE_DP)
        fresnelStrength = 0.4f
        tintColor = glassTintColor
    }
}

private fun armTop(container: ViewGroup, ids: MediaIds) {
    val layout = ancestorWithId(container, ids.layout) ?: return
    val pager = layout.findViewById<View>(ids.pager) ?: return
    val toolbar = container.findViewById<View>(ids.toolbar) ?: return
    var pane = container.getTag(topPaneTag) as? GlassView
    if (pane == null || pane.parent !== container) {
        pane = newPane(container)
        // Explicit size: the container wraps its children, a MATCH_PARENT pane would take the window.
        container.addView(pane, 0, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START))
        container.setTag(topPaneTag, pane)
        // The strip covers the status bar, which sits in the container's padding.
        container.clipToPadding = false
        XposedBridge.log("[$TAG] media viewer top glass inserted")
    }
    val p = pane
    // Resolved on every attach: a rebuilt viewer brings a new pager.
    p.backdrop = pager
    p.underlay = listOfNotNull(layout.findViewById(ids.background))
    p.sourceChanged = pagerPhotoProbe(pager)
    clearBg(container, "media toolbar_container")
    armSync(container) { syncTop(container, toolbar, p); true }
}

private fun syncTop(container: ViewGroup, toolbar: View, pane: GlassView) {
    if (toolbar.height <= 0) return
    val r = container.dp(OVERHANG_DP).toInt()
    val lp = pane.layoutParams as? FrameLayout.LayoutParams ?: return
    // From the toolbar, never from the container that wraps the pane, or each grows the other.
    val w = container.width + 2 * r
    val h = toolbar.bottom + r
    val left = -(container.paddingLeft + r)
    val top = -(container.paddingTop + r)
    if (lp.width != w || lp.height != h || lp.leftMargin != left || lp.topMargin != top) {
        lp.width = w
        lp.height = h
        lp.leftMargin = left
        lp.topMargin = top
        pane.layoutParams = lp
    }
    if (pane.params.cornerRadius != r.toFloat()) pane.params.cornerRadius = r.toFloat()
}

private fun armFooter(footer: View, ids: MediaIds) {
    val page = pageOf(footer, ids.pager) as? FrameLayout ?: run {
        logOnce("media viewer footer: its page is not a FrameLayout; footer stays stock")
        return
    }
    val layer = directChildOf(page, footer) ?: return
    val photo = photoOf(page, layer) ?: run {
        logOnce("media viewer footer: no photo beside it; footer stays stock")
        return
    }
    val layout = ancestorWithId(page, ids.layout) ?: return
    var pane = page.getTag(footerPaneTag) as? GlassView
    if (pane == null || pane.parent !== page) {
        pane = newPane(page).apply { skipOffscreen = true }
        // Just below the footer's layer, so the caption and the Reply bar draw and take touches above it.
        page.addView(pane, page.indexOfChild(layer), FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START))
        page.setTag(footerPaneTag, pane)
        XposedBridge.log("[$TAG] media viewer footer glass inserted")
    }
    val p = pane
    p.backdrop = photo
    p.underlay = listOfNotNull(layout.findViewById(ids.background))
    p.sourceChanged = photoProbe { photo }
    // One writer for its visibility: the footer's own state, through the sweep.
    bindPane(p, footer, "media footer")
    clearBg(footer, "media footer")
    armSync(page) { syncFooter(page, footer, p); true }
}

private fun syncFooter(page: ViewGroup, footer: View, pane: GlassView) {
    // The viewer is its own Activity, where no other driver runs the sweep, so a pane hidden at attach would stay hidden.
    syncPaneVisibility()
    if (footer.width <= 0 || footer.height <= 0 || !footer.isAttachedToWindow) return
    val box = Rect(0, 0, footer.width, footer.height)
    // Within the page only, never across the pager, whose paging moves pages without a layout pass.
    if (!page.offsetDescendantRect(footer, box)) return
    val r = page.dp(OVERHANG_DP).toInt()
    val lp = pane.layoutParams as? FrameLayout.LayoutParams ?: return
    val w = box.width() + 2 * r
    val h = box.height() + r
    val left = box.left - r
    val top = box.top
    if (lp.width != w || lp.height != h || lp.leftMargin != left || lp.topMargin != top) {
        lp.width = w
        lp.height = h
        lp.leftMargin = left
        lp.topMargin = top
        pane.layoutParams = lp
    }
    if (pane.params.cornerRadius != r.toFloat()) pane.params.cornerRadius = r.toFloat()
    // A sibling inherits nothing, so the fade and slide of the footer and its layer are copied by hand.
    var alpha = 1f
    var tx = 0f
    var ty = 0f
    var v: View? = footer
    while (v != null && v !== page) {
        alpha *= v.alpha
        tx += v.translationX
        ty += v.translationY
        v = v.parent as? View
    }
    if (pane.alpha != alpha) pane.alpha = alpha
    if (pane.translationX != tx) pane.translationX = tx
    if (pane.translationY != ty) pane.translationY = ty
}

private fun armPill(text: View) {
    // Recorded before the nine-patch goes: a padding re-resolve reads any undeclared inset off the background.
    if (text.getTag(replyPadTag) == null) {
        text.setTag(replyPadTag, intArrayOf(text.paddingLeft, text.paddingTop, text.paddingRight, text.paddingBottom))
    }
    // Frost, not a second pane: two panes over one photo blur alike and the pill disappears into the footer.
    val frost = text.getTag(replyFrostTag) as? FrostDrawable ?: FrostDrawable(
        text, null, text.height / 2f, REPLY_FROST,
        strokeWidth = text.dp(1f), strokeColor = Color.argb(CHIP_RIM_ALPHA, 255, 255, 255),
        ignorePadding = true,
    ).also { text.setTag(replyFrostTag, it) }
    forceBg(text, frost, "media reply pill")
    // WhatsApp's grey hint has no contrast on a see-through pill over a busy photo.
    (text as? TextView)?.setTextColor(Color.WHITE)
    armSync(text) { syncPill(text, frost) }
}

private fun syncPill(text: View, frost: FrostDrawable): Boolean {
    // An inset lost to a re-resolve gets the stock one back, and this frame is dropped rather than shown cramped.
    (text.getTag(replyPadTag) as? IntArray)?.let { pad ->
        if (text.paddingLeft != pad[0] || text.paddingTop != pad[1] || text.paddingRight != pad[2] || text.paddingBottom != pad[3]) {
            text.setPadding(pad[0], pad[1], pad[2], pad[3])
            return false
        }
    }
    if (text.height > 0) frost.setRadius(text.height / 2f)
    (text as? TextView)?.let { if (it.currentTextColor != Color.WHITE) it.setTextColor(Color.WHITE) }
    return true
}

/** One pre-draw driver per host, re-pointed on every arm; it leaves with the host's window. False cancels the frame. */
private class SyncHolder(var sync: () -> Boolean)

private fun armSync(host: View, sync: () -> Boolean) {
    (host.getTag(syncTag) as? SyncHolder)?.let {
        it.sync = sync
        return
    }
    val holder = SyncHolder(sync)
    host.setTag(syncTag, holder)
    val observer = host.viewTreeObserver
    observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (!host.isAttachedToWindow) {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                host.setTag(syncTag, null)
                return true
            }
            return runCatching { holder.sync() }.onFailure { logOnce("media viewer sync threw: $it") }.getOrDefault(true)
        }
    })
}

/** True when what a photo view shows has moved since the last call: its image matrix, its picture or its own transform. */
private fun photoProbe(photo: () -> View?): () -> Boolean {
    val matrix = FloatArray(9)
    val now = FloatArray(15)
    val last = FloatArray(15) { Float.NaN }
    var lastPicture = -1
    return probe@{
        val v = photo() ?: return@probe false
        val image = v as? ImageView
        if (image != null) image.imageMatrix.getValues(matrix) else matrix.fill(0f)
        matrix.copyInto(now)
        now[9] = v.scaleX
        now[10] = v.scaleY
        now[11] = v.translationX
        now[12] = v.translationY
        now[13] = v.scrollX.toFloat()
        now[14] = v.scrollY.toFloat()
        val picture = System.identityHashCode(image?.drawable)
        val changed = !now.contentEquals(last) || picture != lastPicture
        if (changed) {
            now.copyInto(last)
            lastPicture = picture
        }
        changed
    }
}

/** [photoProbe] on the page nearest the screen's left edge, which is the one the top strip shows. */
private fun pagerPhotoProbe(pagerContainer: View): () -> Boolean {
    val loc = IntArray(2)
    var current: View? = null
    val inner = photoProbe { current }
    return {
        current = null
        val pager = (pagerContainer as? ViewGroup)?.getChildAt(0) as? ViewGroup
        var best = Int.MAX_VALUE
        if (pager != null) {
            for (i in 0 until pager.childCount) {
                val page = pager.getChildAt(i) as? ViewGroup ?: continue
                page.getLocationOnScreen(loc)
                if (abs(loc[0]) < best) {
                    best = abs(loc[0])
                    current = photoOf(page, null)
                }
            }
        }
        inner()
    }
}

/** The page's photo or video view: an ImageView child first, else the first child that is not glass or [skip]. */
private fun photoOf(page: ViewGroup, skip: View?): View? {
    var fallback: View? = null
    for (i in 0 until page.childCount) {
        val c = page.getChildAt(i)
        if (c === skip || c is GlassView) continue
        if (c is ImageView) return c
        if (fallback == null) fallback = c
    }
    return fallback
}

/** The pager's page holding [v]: the ancestor whose parent's parent is the pager container. */
private fun pageOf(v: View, pagerId: Int): ViewGroup? {
    var p = v.parent as? View
    var hops = 0
    while (p != null && hops < 8) {
        val pager = p.parent as? View
        if ((pager?.parent as? View)?.id == pagerId) return p as? ViewGroup
        p = pager
        hops++
    }
    return null
}

private fun directChildOf(parent: ViewGroup, descendant: View): View? {
    var v: View? = descendant
    while (v != null) {
        val p = v.parent
        if (p === parent) return v
        v = p as? View
    }
    return null
}

private fun ancestorWithId(v: View, id: Int): ViewGroup? {
    var p = v.parent as? View
    var hops = 0
    while (p != null && hops < 12) {
        if (p.id == id) return p as? ViewGroup
        p = p.parent as? View
        hops++
    }
    return null
}
