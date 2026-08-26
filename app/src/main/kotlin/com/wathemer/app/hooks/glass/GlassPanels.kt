// Panels, popup menus, sheets and the reactions tray: surfaces that arrive in their own window or
// with no id to hang a pane on. Geometry comes from pre-draw, because layout positions are unsettled.
package com.wathemer.app.hooks.glass

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import com.wathemer.app.glass.FrostDrawable
import com.wathemer.app.glass.GlassBubblePane
import com.wathemer.app.glass.GlassParams
import com.wathemer.app.glass.GlassView
import com.wathemer.app.hooks.HookLog
import com.wathemer.app.glass.RectList
import com.wathemer.app.hooks.waId
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import kotlin.math.abs

/** The radius is the optics ceiling (the lensing band clamps to it); a fraction so big surfaces are not starved. */
private const val PANEL_RADIUS_FRACTION = 0.22f

private const val PANEL_RADIUS_MAX_DP = 48f

/** Panels stack over content that already carries a pane, so their own blur composes with it. */
private const val PANEL_BLUR_SCALE = 0.7f

internal var menuRowId = 0

internal var menuTitleId = 0

/** The message long-press menu's rows carry neither of the two above. */
internal var menuSelRowId = 0

private val panelGlassTag = tagKey("wathemer-panel-glass")

private val panelSweepTag = tagKey("wathemer-panel-sweep")

/** The picker's sheet variant, tested the way the app tests it. A server flag picks which ships. */
internal fun isMediaPickerSheet(v: View): Boolean {
    var c: Class<*>? = activityOf(v)?.javaClass
    while (c != null) {
        if (c.name == "com.whatsapp.gallerypicker.ui.MediaPickerBottomSheetActivity") return true
        c = c.superclass
    }
    return false
}

/** The live content the panel refracts; the source must never be an ancestor of the pane, which recurses. */
internal fun backdropFor(panel: View, host: ViewGroup, underlay: List<View>): View? {
    val act = activityOf(panel)
    val content = act?.findViewById<View>(android.R.id.content)
    if (act != null && panel.rootView !== act.window?.decorView) return content
    // The biggest eligible sibling, not the first (a zero-size ViewStub), and never a wallpaper view (double blur).
    var best: View? = null
    var bestArea = 0
    for (i in 0 until host.childCount) {
        val c = host.getChildAt(i) ?: continue
        if (c === panel || isAncestorOf(c, panel)) break   // our own branch; stop before it
        // By name too: the conversation's own WDSWallpaper is in neither list, and identity alone picks it as source.
        if (c.javaClass.simpleName.contains("Wallpaper", ignoreCase = true)) continue
        if (underlay.any { it === c || isAncestorOf(c, it) }) continue
        val area = c.width * c.height
        if (area > bestArea) { bestArea = area; best = c }
    }
    return best
}

/** Inline-panel glass: walks up to a stacking ancestor and inserts at the panel's branch, just behind it. */
internal fun panelGlass(panel: View, what: String) {
    if (panel.getTag(panelGlassTag) == null) {
        if (!panel.isAttachedToWindow) return
        // Before clearBg below strips it.
        val own = readCornerRadius(panel)
        var child: View = panel
        var host = panel.parent as? ViewGroup
        while (host != null && !canStack(host)) {
            child = host
            host = host.parent as? ViewGroup
        }
        if (host == null) {
            logOnce("panel glass: no stacking ancestor above $what")
            return
        }
        val under = wallpaperUnderlay(host).ifEmpty { wallpaperUnderlayGlobal() }
        val back = runCatching { backdropFor(panel, host, under) }.getOrNull()
        val glass = GlassView(host.context).apply {
            // The live content, not just the wallpaper; without it the pane reads as a frosted sheet laid over the app.
            if (back != null) backdrop = back
            // The underlay fills where WhatsApp draws nothing; dialogs need the global form, their window has no wallpaper.
            underlay = under
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = host.dp(BLUR_DP * PANEL_BLUR_SCALE)
                cornerRadius = own ?: host.dp(CARD_RADIUS_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = host.dp(DISPLACE_DP)
                // Halved; the content underneath already carries a lit pane.
                fresnelStrength = 0.25f
                // Off; the pane underneath already lifted this content.
                transGamma = 1f
                // Half a pane's tint; the content underneath already carries one.
                tintColor = glassTint((TINT_ALPHA / 2).coerceAtLeast(0))
            }
        }
        // MarginLayoutParams: addView converts them, avoiding a compile-time coordinatorlayout dependency.
        host.addView(
            glass, host.indexOfChild(child).coerceAtLeast(0),
            ViewGroup.MarginLayoutParams(0, 0),
        )
        panel.setTag(panelGlassTag, glass)
        clearBg(panel, what)
        XposedBridge.log(
            "[$TAG] $what: backdrop=${back?.javaClass?.simpleName ?: "NONE"}" +
                " ownRadius=${own?.toInt() ?: -1}px"
        )
        // Pre-draw, not layout: layout-phase reads flicker the pane to full screen; pre-draw sees settled positions.
        if (panel.getTag(panelSweepTag) == null) {
            panel.setTag(panelSweepTag, true)
            panel.viewTreeObserver.addOnPreDrawListener {
                syncPanelGlass(panel)
                true
            }
        }
        XposedBridge.log("[$TAG] panel glass for $what in ${host.javaClass.simpleName}")
    }
    syncPanelGlass(panel)
}

// UI-thread scratch, reused like popupRowGlass's paneRect: this runs per pre-draw while any panel shows.
private val panelScratchRect = Rect()

private fun syncPanelGlass(panel: View) {
    val glass = panel.getTag(panelGlassTag) as? GlassView ?: return
    val host = glass.parent as? ViewGroup ?: return
    val want = if (paneShouldShow(panel)) View.VISIBLE else View.GONE
    if (glass.visibility != want) glass.visibility = want
    if (want != View.VISIBLE) return
    val r = panelScratchRect
    r.set(0, 0, panel.width, panel.height)
    if (!runCatching { host.offsetDescendantRectToMyCoords(panel, r) }.isSuccess) return
    val lp = glass.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    if (lp.width != r.width() || lp.height != r.height() ||
        lp.leftMargin != r.left || lp.topMargin != r.top
    ) {
        lp.width = r.width()
        lp.height = r.height()
        lp.leftMargin = r.left
        lp.topMargin = r.top
        glass.layoutParams = lp
    }
    // Radius scales with the surface: the lensing band clamps to it, and a fixed radius starves big dialogs to frost.
    // Deliberately overwrites a panel's own radius every frame; the own radius read at creation only seeds the first frame.
    val radius = (minOf(r.width(), r.height()) * PANEL_RADIUS_FRACTION)
        .coerceIn(glass.dp(16f), glass.dp(PANEL_RADIUS_MAX_DP))
    if (abs(glass.params.cornerRadius - radius) > 0.5f) {
        glass.params.cornerRadius = radius
    }
}

private var popupHooked = false

private val popupGlassTag = tagKey("wathemer-popup-glass")

/** Popups expose no id, so PopupWindow's show methods are hooked; a menu is identified by its row ids. */
internal fun ensurePopupGlass() {
    if (popupHooked) return
    popupHooked = true
    runCatching {
        var hooked = 0
        for (m in PopupWindow::class.java.declaredMethods) {
            if (m.name != "showAsDropDown" && m.name != "showAtLocation") continue
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pw = param.thisObject as? PopupWindow ?: return
                    // Posted, not immediate: at show time the content view is 0x0 with zero rows; one frame later it is real.
                    pw.contentView?.post {
                        runCatching { glassPopup(pw) }
                            .onFailure { logOnce("glassPopup threw: $it") }
                    }
                }
            })
            hooked++
        }
        XposedBridge.log("[$TAG] popup glass armed on $hooked PopupWindow show methods")
    }.onFailure { XposedBridge.log("[$TAG] popup glass FAILED: $it") }
}

private fun glassPopup(pw: PopupWindow) {
    val content = pw.contentView ?: return
    if (content.getTag(popupGlassTag) != null) return
    // A menu is rows carrying the menu-row id; one row is enough, the Calls Block menu has exactly one.
    // Both row shapes count: overflow rows carry content, call-menu buttons carry menu_title and no content.
    val rows = countDescendantsWithId(content, menuRowId, 0) +
        (if (menuTitleId != 0) countDescendantsWithId(content, menuTitleId, 0) else 0) +
        (if (menuSelRowId != 0) countDescendantsWithId(content, menuSelRowId, 0) else 0)
    if (rows < 1) return
    content.setTag(popupGlassTag, true)
    // The fill sits on wrapper views above the content: clear each one, reading the radius first or it is lost.
    var v: View? = content
    var hops = 0
    var radius: Float? = null
    while (v != null && hops < 4) {
        if (v.background != null) {
            if (radius == null) radius = readCornerRadius(v)
            clearBg(v, "popup menu")
        }
        v = v.parent as? View
        hops++
    }
    // The attachment panel keeps its fill and its shadow on a DESCENDANT, which the walk above cannot reach.
    val clipId = content.resources.waId("paper_clip_layout", content.context.packageName)
    val attachPanel = (if (clipId != 0) content.findViewById<View>(clipId) else null)?.also { clip ->
        if (radius == null) radius = readCornerRadius(clip)
        clearBg(clip, "paper_clip_layout")
        clip.elevation = 0f
    } != null
    popupRowGlass(content, radius)
    // Menu rows on one sheet want seams; a tile grid does not, and its "rows" include the spacers.
    if (!attachPanel) popupRowDividers(content)
    // Class and size in the log so a wrongly glassed popup names itself.
    XposedBridge.log(
        "[$TAG] popup menu glassed ($rows rows, ${content.javaClass.simpleName} " +
            "${content.width}x${content.height})"
    )
}

/** A menu floats over content, not the wallpaper, so a white tint over a white image leaves white text on white. */
private const val MENU_SCRIM = 0xB80E1418.toInt()

/** GlassBubblePane, not panelGlass: cost stays flat as the menu grows, and one union rect avoids scalloped seams between items. */
private fun popupRowGlass(content: View, radiusPx: Float?) {
    if (content.getTag(panelGlassTag) != null) return
    if (!content.isAttachedToWindow) return
    // The pane needs a stacking ancestor; a LinearLayout would sequence it in and consume a row.
    var host = content.parent as? ViewGroup
    while (host != null && !canStack(host)) host = host.parent as? ViewGroup
    if (host == null) {
        logOnce("popup row glass: no stacking ancestor")
        return
    }
    content.setTag(panelGlassTag, true)
    requestScreenSnap(content)
    val d = content.resources.displayMetrics.density
    val pane = GlassBubblePane(host.context).apply {
        params = GlassParams(d).apply {
            blurRadius = content.dp(BLUR_DP)
            cornerRadius = radiusPx ?: content.dp(PANEL_ROW_RADIUS_DP)
            refractionEnabled = true
            bevelFraction = BEVEL_FRACTION
            depthRatio = DEPTH_RATIO
            maxDisplacePx = content.dp(DISPLACE_DP)
            fresnelStrength = 0.5f
            tintColor = MENU_SCRIM
        }
        tint = { MENU_SCRIM }
        // The screen if PixelCopy gave us one, else the wallpaper. Both are screen-space.
        backdrop = { screenSnap ?: contentRef?.get()?.let { host -> bubbleBackdrop(host) } }
        placement = { if (screenSnap != null) screenSnapPlace else bubbleWpPlacement }
        dim = { 0f }
        rimColor = glassTint(BUBBLE_RIM_ALPHA)
        rimWidth = d
        collect = { out -> collectPopupRowRects(content, out) }
        onGeometryChanged = { markWallpaperGeometryDirty() }
    }
    // Sized to the content: a MATCH_PARENT child makes the WRAP_CONTENT window measure full screen and the WM shoves it to the top.
    host.addView(pane, 0, FrameLayout.LayoutParams(0, 0))
    val paneHost = host
    val paneRect = Rect()
    val syncPane = Runnable {
        if (content.width <= 0 || content.height <= 0) return@Runnable
        paneRect.set(0, 0, content.width, content.height)
        if (!runCatching { paneHost.offsetDescendantRectToMyCoords(content, paneRect) }.isSuccess) {
            return@Runnable
        }
        val lp = pane.layoutParams as? FrameLayout.LayoutParams ?: return@Runnable
        if (lp.width != paneRect.width() || lp.height != paneRect.height() ||
            lp.leftMargin != paneRect.left || lp.topMargin != paneRect.top
        ) {
            lp.width = paneRect.width()
            lp.height = paneRect.height()
            lp.leftMargin = paneRect.left
            lp.topMargin = paneRect.top
            pane.layoutParams = lp
        }
    }
    content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncPane.run() }
    syncPane.run()
    XposedBridge.log("[$TAG] popup row glass inserted into ${host.javaClass.simpleName}")
}

/** The view whose children are the menu rows; some menus wrap it, so descend through single-child groups. */
private fun menuRowHost(content: View): ViewGroup? {
    var group = content as? ViewGroup ?: return null
    var hops = 0
    while (group.childCount == 1 && hops < 4) {
        val only = group.getChildAt(0) as? ViewGroup ?: break
        group = only
        hops++
    }
    return group
}

/** Seams are 1px foreground lines on one continuous glass: per-item rects scallop, and the row backgrounds were cleared. */
private fun popupRowDividers(content: View) {
    val group = menuRowHost(content) ?: return
    val line = glassTint(POPUP_DIVIDER_ALPHA)
    val thickness = maxOf(1f, content.resources.displayMetrics.density * 0.5f)
    for (i in 0 until group.childCount - 1) {
        val row = group.getChildAt(i) ?: continue
        if (row.getTag(popupDividerTag) != null) continue
        row.setTag(popupDividerTag, true)
        row.foreground = object : Drawable() {
            private val p = Paint().apply { color = line }
            override fun draw(canvas: Canvas) {
                val b = bounds
                if (b.width() <= 0) return
                canvas.drawRect(
                    b.left.toFloat(), b.bottom - thickness,
                    b.right.toFloat(), b.bottom.toFloat(), p,
                )
            }
            override fun setAlpha(alpha: Int) { p.alpha = alpha }
            override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
    }
}

/** One union rect over the rows: per-item rects notch at the seams, and the content view runs taller than its rows. */
private fun collectPopupRowRects(content: View, out: RectList) {
    val group = menuRowHost(content) ?: return
    if (content.width <= 0) return
    content.getLocationOnScreen(popupContentAt)
    val left = popupContentAt[0].toFloat()
    val right = left + content.width
    var top = Float.MAX_VALUE
    var bottom = Float.MIN_VALUE
    for (i in 0 until group.childCount) {
        val row = group.getChildAt(i) ?: continue
        if (row.height <= 0 || row.visibility != View.VISIBLE) continue
        row.getLocationOnScreen(popupRowAt)
        top = minOf(top, popupRowAt[1].toFloat())
        bottom = maxOf(bottom, (popupRowAt[1] + row.height).toFloat())
    }
    if (bottom - top <= 0f) return
    out.add(left, top, right, bottom)
}

private val popupDividerTag = tagKey("wathemer-popup-divider")

private val popupContentAt = IntArray(2)

private val popupRowAt = IntArray(2)

private fun countDescendantsWithId(v: View, id: Int, depth: Int): Int {
    if (depth > 6) return 0
    var n = if (v.id == id) 1 else 0
    if (v is ViewGroup) for (i in 0 until v.childCount) {
        n += countDescendantsWithId(v.getChildAt(i) ?: continue, id, depth + 1)
    }
    return n
}

/** A live pane like the nav pill's; underlay only, nothing sits behind a sheet but the wallpaper. */
internal fun injectSheetGlass(v: View) {
    val sheet = v as? ViewGroup ?: return
    if (sheet.getTag(innerGlassTag) != null) return
    if (!sheet.isAttachedToWindow) return
    sheet.setTag(innerGlassTag, true)
    clearBg(sheet, "design_bottom_sheet")

    val glass = GlassView(sheet.context).apply {
        underlay = wallpaperUnderlayGlobal()
        params.apply {
            downsample = DOWNSAMPLE
            blurRadius = sheet.dp(BLUR_DP)
            // Follows the radius slider so sheets agree with every other surface.
            cornerRadius = sheet.dp(CARD_RADIUS_DP)
            refractionEnabled = true
            bevelFraction = BEVEL_FRACTION
            depthRatio = DEPTH_RATIO
            maxDisplacePx = sheet.dp(DISPLACE_DP)
            fresnelStrength = 0.5f
            tintColor = glassTintColor
        }
    }
    sheet.addView(glass, 0, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    XposedBridge.log("[$TAG] sheet pane added (${sheet.width}x${sheet.height})")
}

/** A stacking carrier takes the live pane; the rest wear the tray's stamp material, a pane child pushes a LinearLayout's rows out. */
internal fun glassSelfSheet(v: View, name: String) {
    val sheet = v as? ViewGroup ?: return
    if (sheet.getTag(innerGlassTag) != null) return
    // One material per sheet: skip when a glassed Material wrapper already owns this subtree.
    var anc: View? = sheet.parent as? View
    var depth = 0
    while (anc != null && depth < 6) {
        if (anc.getTag(innerGlassTag) != null) return
        anc = anc.parent as? View
        depth++
    }
    // The camera and media viewer stay stock on purpose.
    val cls = activityOf(sheet)?.javaClass?.name ?: ""
    if (listOf(".camera", "mediaview").any { cls.contains(it) }) return
    // Tint-only frost on the viewers list: that window's backdrop is the status media, not the wallpaper.
    if (cls.contains(".status.")) {
        val detailsId = sheet.resources.waId("status_details_container", sheet.context.packageName)
        val details = if (detailsId != 0) sheet.findViewById<View>(detailsId) else null
        if (details == null || details.getTag(innerGlassTag) != null) return
        details.setTag(innerGlassTag, true)
        details.background = FrostDrawable(
            details, null, details.dp(CARD_RADIUS_DP), glassTint(CHIP_ALPHA),
            strokeWidth = details.dp(1f), strokeColor = glassTint(CHIP_RIM_ALPHA),
            ignorePadding = true,
        )
        logOnce("self sheet frosted: status details")
        return
    }
    if (sheet is FrameLayout) {
        injectSheetGlass(sheet)
        logOnce("self sheet glassed live: $name")
        return
    }
    sheet.setTag(innerGlassTag, true)
    clearBg(sheet, name)
    sheet.background = FrostDrawable(
        sheet, bubbleBackdrop(contentRef?.get() ?: sheet),
        sheet.dp(CARD_RADIUS_DP), glassTintColor,
        strokeWidth = sheet.dp(1f), strokeColor = glassTint(CHIP_RIM_ALPHA),
        ignorePadding = true,
    )
    logOnce("self sheet glassed: $name (${sheet.javaClass.simpleName})")
}

/** Tuned by eye: less blur than the panes, a scrim between the menu tint and the panel scrim. */
private const val TRAY_BLUR_DP = 10f

private const val TRAY_SCRIM = 0x590E1418

/** The pill is a live pane INSIDE the tray, so it rides transform animations no external pane can follow. */
internal fun frostReactionsTray(v: View) {
    val tray = v as? ViewGroup ?: return
    if (tray.getTag(frostTag) != null) return
    tray.setTag(frostTag, true)
    val containerId = tray.resources.waId("reactions_tray_container", tray.context.packageName)
    val container =
        (if (containerId != 0) tray.findViewById<View>(containerId) else null) as? FrameLayout
    val content = activityOf(tray)?.findViewById<ViewGroup>(android.R.id.content)
    if (container == null || content == null) {
        // No stacking child or no activity behind: the stamp fallback, so the tray never shows stock.
        val d = FrostDrawable(
            tray, bubbleBackdrop(tray), tray.dp(999f), glassTintColor,
            strokeWidth = tray.dp(1f), strokeColor = glassTint(BUBBLE_RIM_ALPHA),
        )
        tray.background = d
        logOnce("reactions tray frosted (stamp fallback)")
    } else {
        tray.background = null
        // The pill spans the padding box, wider than the container; the pane pokes past it to reach under the plus button.
        container.clipChildren = false
        val glass = GlassView(tray.context).apply {
            // The dialogs' recipe: the activity's content is another window, which is exactly what is behind a popup.
            backdrop = content
            underlay = wallpaperUnderlay(content)
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = tray.dp(TRAY_BLUR_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = tray.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                tintColor = TRAY_SCRIM
            }
        }
        container.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
        val sync = Runnable {
            if (tray.width <= 0 || tray.height <= 0) return@Runnable
            val w = tray.width - tray.paddingLeft - tray.paddingRight
            val h = tray.height - tray.paddingTop - tray.paddingBottom
            if (w <= 0 || h <= 0) return@Runnable
            val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return@Runnable
            if (lp.width != w || lp.height != h) {
                lp.width = w
                lp.height = h
                lp.leftMargin = tray.paddingLeft - container.left
                lp.topMargin = tray.paddingTop - container.top
                glass.layoutParams = lp
                glass.params.cornerRadius = h / 2f
            }
        }
        tray.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync.run() }
        tray.post(sync)
        HookLog.hit("pane/reactionsTray")
    }
    // The stock pill sits inside shadow padding; the wrappers' own fills go too.
    var p: View? = tray.parent as? View
    var hops = 0
    while (p != null && hops < 3) {
        if (p.background != null) clearBg(p, "reactions tray wrapper")
        p = p.parent as? View
        hops++
    }
}

internal val slabSweepTag = tagKey("wathemer-slab-sweep")

/** Divider bands to re-assert: WhatsApp re-shows them after our callback, and the flip fires no layout event. */
private val slabRefs = mutableListOf<WeakReference<View>>()

/** Hide one divider band, keeping its height so nothing above or below moves. */
internal fun hideSlab(v: View, what: String) {
    if (slabRefs.none { it.get() === v }) slabRefs.add(WeakReference(v))
    if (v.visibility != View.VISIBLE) return
    v.visibility = View.INVISIBLE
    logOnce("slab divider hidden: $what")
}

/** Cheap enough to run every frame: a handful of visibility compares. */
internal fun reassertSlabs() {
    val it = slabRefs.iterator()
    while (it.hasNext()) {
        val v = it.next().get()
        if (v == null) { it.remove(); continue }
        if (v.visibility == View.VISIBLE) v.visibility = View.INVISIBLE
    }
}

/** Unnamed divider bands matched by a deliberately narrow shape rule; every condition rules out something real on this page. */
internal fun hideSlabsByShape(root: ViewGroup) {
    if (root.width <= 0) return
    val maxH = root.dp(12f).toInt()
    val minW = root.width - root.dp(8f).toInt()
    fun walk(v: View) {
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i) ?: continue)
            return
        }
        if (v.javaClass != View::class.java) return
        if (v.background == null) return
        if (v.height <= 0 || v.height > maxH) return
        if (v.width < minW) return
        if (v.visibility != View.VISIBLE) return
        v.visibility = View.INVISIBLE
        logOnce("slab divider hidden by shape (${v.width}x${v.height})")
    }
    walk(root)
}

/** Hide the id-less WDSDividers inside a glassed bar, matched by class and scoped to it; INVISIBLE, not GONE, so nothing shifts. */
internal fun hideWdsDividers(root: ViewGroup) {
    fun walk(v: View) {
        if (v.javaClass.name.contains("WDSDivider")) {
            if (v.visibility == View.VISIBLE) {
                v.visibility = View.INVISIBLE
                logOnce("WDSDivider hidden")
            }
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i) ?: continue)
    }
    walk(root)
}
