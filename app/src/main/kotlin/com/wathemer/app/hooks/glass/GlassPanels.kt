// Panels, popup menus, sheets and the reactions tray: surfaces that arrive in their own window or
// with no id to hang a pane on. Geometry comes from pre-draw, because layout positions are unsettled.
package com.wathemer.app.hooks.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.RelativeLayout
import com.wathemer.app.glass.FrostDrawable
import com.wathemer.app.glass.GlassBubblePane
import com.wathemer.app.glass.GlassParams
import com.wathemer.app.glass.GlassView
import com.wathemer.app.hooks.HookLog
import com.wathemer.app.hooks.WaeCompat
import com.wathemer.app.glass.RectList
import com.wathemer.app.hooks.waId
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap
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
        val under = wallpaperUnderlayOf(host)
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
                // Off for the same reason: the content captured here carries graded glass already.
                saturation = 1f
                bloom = 0f
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
        clearWindowShells(panel, host, what)
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

/** A dialog window's own fill is a shell behind the pane, and the pane's wider corners leave it showing. */
private fun clearWindowShells(panel: View, host: ViewGroup, what: String) {
    val act = activityOf(panel) ?: return
    val root = panel.rootView
    // Its own window only: an inline panel's root is the Activity's decor, which the wallpaper already clears.
    if (root === act.window?.decorView) return
    var v: View? = host
    while (v != null) {
        if (v.background != null) clearBg(v, "$what window shell ${v.javaClass.simpleName}")
        if (v === root) return
        v = v.parent as? View
    }
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

/** The button each drop-down was shown from; showAtLocation's first argument is a parent token, not a button. */
private val popupAnchors = WeakHashMap<PopupWindow, WeakReference<View>>()

/** Popups expose no id, so PopupWindow's show methods are hooked; a menu is identified by its row ids. */
internal fun ensurePopupGlass() {
    if (popupHooked) return
    popupHooked = true
    runCatching {
        var hooked = 0
        for (m in PopupWindow::class.java.declaredMethods) {
            if (m.name == "dismiss") {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val pw = param.thisObject as? PopupWindow ?: return
                        runCatching { GlassPopupMorph.onDismiss(pw) }
                        runCatching { hideLiveDropdownPane(pw) }
                    }
                })
                hooked++
                continue
            }
            if (m.name != "showAsDropDown" && m.name != "showAtLocation") continue
            // Bound here, outside the hook: only a drop-down's first argument is the button it hangs from.
            val dropDown = m.name == "showAsDropDown"
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                // Before the show: PopupWindow measures the content to place it, and WhatsApp reads that size for the tray's box.
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!WaeCompat.enabled) return
                    val pw = param.thisObject as? PopupWindow ?: return
                    runCatching { prepareWaeTray(pw.contentView ?: return) }
                        .onFailure { logOnce("prepareWaeTray threw: $it") }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val pw = param.thisObject as? PopupWindow ?: return
                    if (dropDown) (param.args.getOrNull(0) as? View)?.let { popupAnchors[pw] = WeakReference(it) }
                    // Posted, not immediate: at show time the content view is 0x0 with zero rows; one frame later it is real.
                    pw.contentView?.post {
                        runCatching { glassPopup(pw) }
                            .onFailure { logOnce("glassPopup threw: $it") }
                    }
                }
            })
            hooked++
        }
        XposedBridge.log("[$TAG] popup glass armed on $hooked PopupWindow methods")
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
    // The floating message menu keeps its fill on the list itself, a child the walk above never reaches.
    val dropdown = if (WaeCompat.enabled) dropdownMenuList(content) else null
    if (dropdown != null) {
        if (radius == null) radius = readCornerRadius(dropdown)
        clearBg(dropdown, "message selection dropdown")
        // A transparent fill still casts the list's own shadow, as a rectangle.
        dropdown.elevation = 0f
        HookLog.hit("compat/floatingMenu")
    }
    // The attachment panel keeps its fill and its shadow on a DESCENDANT, which the walk above cannot reach.
    val clipId = content.resources.waId("paper_clip_layout", content.context.packageName)
    val attachPanel = (if (clipId != 0) content.findViewById<View>(clipId) else null)?.also { clip ->
        if (radius == null) radius = readCornerRadius(clip)
        clearBg(clip, "paper_clip_layout")
        clip.elevation = 0f
    } != null
    // The floating menu's glass is drawn live in the Activity's window, as the card's is; only when that fails
    // does it fall back to the snapshot pane inside the popup, with the reactions pill's scrim either way.
    if (dropdown != null && liveDropdownPane(dropdown, pw, radius ?: content.dp(PANEL_ROW_RADIUS_DP))) {
        content.setTag(panelGlassTag, true)
    } else {
        // A message row is no button to rise from: the dropdown keeps its glass in its own window.
        popupRowGlass(
            content, radius, if (dropdown != null) null else popupAnchors[pw]?.get(), pw,
            if (dropdown != null) TRAY_SCRIM else MENU_SCRIM,
        )
    }
    // Menu rows on one sheet want seams; a tile grid does not, and its "rows" include the spacers.
    if (!attachPanel) popupRowDividers(dropdown ?: content)
    // Class and size in the log so a wrongly glassed popup names itself.
    XposedBridge.log(
        "[$TAG] popup menu glassed ($rows rows, ${content.javaClass.simpleName} " +
            "${content.width}x${content.height})"
    )
}

/** A menu floats over content, not the wallpaper, so a white tint over a white image leaves white text on white. */
internal const val MENU_SCRIM = 0xB80E1418.toInt()

/** GlassBubblePane, not panelGlass: cost stays flat as the menu grows, and one union rect avoids scalloped seams between items. */
private fun popupRowGlass(content: View, radiusPx: Float?, anchor: View?, pw: PopupWindow, scrim: Int = MENU_SCRIM) {
    if (content.getTag(panelGlassTag) != null) return
    if (!content.isAttachedToWindow) return
    // With a button to rise from, the Activity's pane draws this menu's glass and the popup carries none of its own.
    if (GlassPopupMorph.begin(content, radiusPx ?: content.dp(PANEL_ROW_RADIUS_DP), anchor, pw)) {
        content.setTag(panelGlassTag, true)
        requestScreenSnap(content)
        logOnce("popup menu glass rises from its button")
        return
    }
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
            tintColor = scrim
            // The snapshot is the composited screen, graded glass included; grading or lifting it again would double both.
            saturation = 1f
            bloom = 0f
            transGamma = 1f
            // No rim from the wallpaper over a screen snapshot: the two would not match.
            detail = 0f
        }
        tint = { scrim }
        // The screen if PixelCopy gave us one, else the wallpaper. Both are screen-space.
        backdrop = { screenSnap ?: bubbleBackdrop(content) }
        placement = { if (screenSnap != null) screenSnapPlace else bubblePlacement(content) }
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

/** WhatsApp's floating message menu list, by its unobfuscated class name; the id-less child that carries the fill. */
private fun dropdownMenuList(content: View): ViewGroup? {
    fun find(v: View, depth: Int): ViewGroup? {
        if (v is ViewGroup && v.javaClass.name.endsWith(".MessageSelectionDropDownRecyclerView")) return v
        if (depth >= 3 || v !is ViewGroup) return null
        for (i in 0 until v.childCount) find(v.getChildAt(i) ?: continue, depth + 1)?.let { return it }
        return null
    }
    return find(content, 0)
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

/** Seams are hairline foreground lines on one continuous glass: per-item rects scallop, and the row backgrounds were cleared. */
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
internal fun collectPopupRowRects(content: View, out: RectList) {
    // The floating message menu: the list's own box, not a union that would take in the tray beside it.
    val dropdown = if (WaeCompat.enabled) dropdownMenuList(content) else null
    if (dropdown != null) {
        if (dropdown.width <= 0 || dropdown.height <= 0 || dropdown.visibility != View.VISIBLE) return
        dropdown.getLocationOnScreen(popupContentAt)
        val l = popupContentAt[0].toFloat()
        val t = popupContentAt[1].toFloat()
        out.add(l, t, l + dropdown.width, t + dropdown.height)
        return
    }
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
        underlay = wallpaperUnderlayOf(sheet)
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
    // Forced, never assigned after a clear: the interceptor swaps a plain assignment back to the cleared transparent.
    val stamp = FrostDrawable(
        sheet, { host -> wallpaperRecordOf(host) },
        sheet.dp(CARD_RADIUS_DP), glassTintColor,
        strokeWidth = sheet.dp(1f), strokeColor = glassTint(CHIP_RIM_ALPHA),
        ignorePadding = true,
    )
    forceBg(sheet, stamp, name)
    logOnce("self sheet glassed: $name (${sheet.javaClass.simpleName})")
}

/** Tuned by eye: less blur than the panes, a scrim between the menu tint and the panel scrim. */
internal const val TRAY_BLUR_DP = 10f

internal const val TRAY_SCRIM = 0x590E1418

/** WaEnhancer rebuilds the tray in the popup's constructor; the column must stand before PopupWindow measures the content at show. */
private fun prepareWaeTray(content: View) {
    val res = content.resources
    val pkg = content.context.packageName
    val trayId = res.waId("reactions_tray_layout", pkg)
    val containerId = res.waId("reactions_tray_container", pkg)
    if (trayId == 0 || containerId == 0) return
    val tray = content.findViewById<ViewGroup>(trayId) ?: return
    val container = tray.findViewById<View>(containerId) ?: return
    flattenWaeTray(tray, container)
}

/** The tray pill's recipe, shared with the buttons stacked under it: live over the Activity's content, which is what sits behind a popup. */
internal fun newTrayPane(ctx: Context, content: ViewGroup, tint: Int): GlassView = GlassView(ctx).apply {
    backdrop = content
    underlay = wallpaperUnderlay(content)
    params.apply {
        downsample = DOWNSAMPLE
        blurRadius = content.dp(TRAY_BLUR_DP)
        refractionEnabled = true
        bevelFraction = BEVEL_FRACTION
        depthRatio = DEPTH_RATIO
        maxDisplacePx = content.dp(DISPLACE_DP)
        fresnelStrength = 0.5f
        tintColor = tint
        // The activity content it captures carries graded and lifted panes already.
        saturation = 1f
        bloom = 0f
        transGamma = 1f
    }
}

/** The list's end fade hidden and the plus halo's alpha pinned to zero; WhatsApp animates that alpha, so its setter is hooked too. */
private fun quietTrayDecorations(tray: ViewGroup, container: ViewGroup) {
    val res = tray.resources
    val fadeId = res.waId("reactions_tray_gradient_left_end", tray.context.packageName)
    if (fadeId != 0) container.findViewById<View>(fadeId)?.let { fade ->
        if (fade.visibility != View.INVISIBLE) fade.visibility = View.INVISIBLE
    }
    val row = container.parent as? ViewGroup ?: return
    for (i in 0 until row.childCount) {
        val c = row.getChildAt(i) ?: continue
        if (!c.javaClass.name.endsWith(".ReactionPlusView")) continue
        runCatching { XposedHelpers.callMethod(c, "setBackgroundAlpha", 0f) }
        ensurePlusHaloHook(c.javaClass)
    }
}

private var plusHaloHooked = false

/** The reveal animation writes the halo alpha every frame; forced to zero on the way in, once per process. */
private fun ensurePlusHaloHook(cls: Class<*>) {
    if (plusHaloHooked) return
    plusHaloHooked = true
    runCatching {
        XposedHelpers.findAndHookMethod(cls, "setBackgroundAlpha", Float::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.args[0] = 0f
            }
        })
    }.onFailure { logOnce("plus halo hook threw: $it") }
}

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
            tray, { host -> wallpaperRecordOf(host) }, tray.dp(999f), glassTintColor,
            strokeWidth = tray.dp(1f), strokeColor = glassTint(BUBBLE_RIM_ALPHA),
        )
        tray.background = d
        logOnce("reactions tray frosted (stamp fallback)")
    } else {
        tray.background = null
        // The pill spans the emoji row, wider than the container; the pane pokes past it to reach under the plus button.
        container.clipChildren = false
        // Both were drawn for an opaque tray: the fade square at the list's end and the halo disc behind the plus read as stains on glass.
        runCatching { quietTrayDecorations(tray, container) }
        // The dialogs' recipe: the activity's content is another window, which is exactly what is behind a popup.
        val glass = newTrayPane(tray.context, content, TRAY_SCRIM)
        container.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
        val box = IntArray(4)
        val sync = Runnable {
            if (tray.width <= 0 || tray.height <= 0) return@Runnable
            val row = container.parent as? ViewGroup ?: return@Runnable
            // The pane overflows the container to reach under the plus; the tray lets that through, rows a module adds do not.
            var up: ViewGroup? = row
            while (up != null && up !== tray) {
                if (up.clipChildren) up.clipChildren = false
                up = up.parent as? ViewGroup
            }
            // From the row's content, never the tray: the container wraps this pane, and anything stacked under the row would feed back.
            rowContentBox(row, container, glass, box)
            val w = box[2]
            val h = box[3]
            if (w <= 0 || h <= 0) return@Runnable
            val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return@Runnable
            val ml = box[0] - container.left
            val mt = box[1] - container.top
            if (lp.width != w || lp.height != h || lp.leftMargin != ml || lp.topMargin != mt) {
                lp.width = w
                lp.height = h
                lp.leftMargin = ml
                lp.topMargin = mt
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

/** The emoji row's box: the children's own extents, so the plus sits inside the pill; the height from views other than the pane, which the container wraps. */
private fun rowContentBox(row: ViewGroup, container: View, glass: View, out: IntArray) {
    var left = Int.MAX_VALUE
    var top = Int.MAX_VALUE
    var right = Int.MIN_VALUE
    var h = 0
    for (i in 0 until row.childCount) {
        val c = row.getChildAt(i) ?: continue
        if (c === glass || c.visibility == View.GONE) continue
        val m = c.layoutParams as? ViewGroup.MarginLayoutParams
        val mt = m?.topMargin ?: 0
        left = minOf(left, c.left)
        right = maxOf(right, c.right)
        top = minOf(top, c.top - mt)
        if (c !== container) h = maxOf(h, c.height + mt + (m?.bottomMargin ?: 0))
    }
    val w = if (right > left) right - left else 0
    if (container is ViewGroup) for (i in 0 until container.childCount) {
        val c = container.getChildAt(i) ?: continue
        if (c === glass || c.visibility == View.GONE) continue
        h = maxOf(h, c.height)
    }
    if (left == Int.MAX_VALUE) {
        left = 0
        top = 0
    }
    out[0] = left
    out[1] = top
    out[2] = w
    out[3] = h
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

private val selBottomTag = tagKey("wathemer-sel-bottom-menu")

private val selBottomPanes = WeakHashMap<View, WeakReference<GlassView>>()

/** The floating message menu's action card: a live pane beneath it in the entry holder, the compose pill's recipe with the menu's scrim. */
internal fun glassSelectionBottomMenu(card: View) {
    val parent = card.parent as? ViewGroup ?: return
    // MaterialCardView: a transparent fill, never null; its shadow and stroke would frame the glass.
    clearBg(card, "message_selection_bottom_menu")
    runCatching { XposedHelpers.callMethod(card, "setCardElevation", 0f) }
    runCatching { XposedHelpers.callMethod(card, "setStrokeWidth", 0) }
    // WhatsApp builds this holder in code and hands its children whichever params the host takes: a FrameLayout in the
    // reply screen, a RelativeLayout in the conversation. Both can seat a child at an absolute box; anything else cannot.
    val paneParams: ViewGroup.MarginLayoutParams? = when (parent) {
        is FrameLayout -> FrameLayout.LayoutParams(0, 0).apply { gravity = Gravity.TOP or Gravity.START }
        is RelativeLayout -> RelativeLayout.LayoutParams(0, 0).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
            addRule(RelativeLayout.ALIGN_PARENT_START)
        }
        else -> null
    }
    if (paneParams == null) {
        frostSelectionBottomMenu(card)
        return
    }
    var pane = selBottomPanes[card]?.get()
    if (pane == null || pane.parent !== parent) {
        pane = GlassView(parent.context).apply {
            backdrop = convListHost(parent)
            underlay = wallpaperUnderlay(parent)
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = parent.dp(BLUR_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = parent.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                // The reactions pill's scrim, so the tray, the list and this card read as one composition.
                tintColor = TRAY_SCRIM
            }
        }
        // Right beneath the card: over the list, under the card's own icons.
        parent.addView(pane, parent.indexOfChild(card).coerceAtLeast(0), paneParams)
        selBottomPanes[card] = WeakReference(pane)
        bindPane(pane, card, "selection bottom menu")
        HookLog.hit("compat/floatingMenuBar")
    }
    val glass = pane
    val sync = Runnable {
        if (card.width <= 0 || card.height <= 0) return@Runnable
        val lp = glass.layoutParams as? ViewGroup.MarginLayoutParams ?: return@Runnable
        if (lp.width != card.width || lp.height != card.height ||
            lp.leftMargin != card.left || lp.topMargin != card.top
        ) {
            lp.width = card.width
            lp.height = card.height
            lp.leftMargin = card.left
            lp.topMargin = card.top
            glass.layoutParams = lp
            glass.params.cornerRadius = runCatching { XposedHelpers.callMethod(card, "getRadius") as? Float }
                .getOrNull()?.takeIf { it > 0f } ?: card.dp(PANEL_ROW_RADIUS_DP)
        }
    }
    if (card.getTag(selBottomTag) == null) {
        card.setTag(selBottomTag, true)
        card.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync.run() }
        // The card slides and fades on its own animators; the pane follows per frame, each write on change only.
        card.viewTreeObserver.addOnPreDrawListener {
            val g = selBottomPanes[card]?.get()
            if (g != null && g.parent != null) {
                if (g.translationX != card.translationX) g.translationX = card.translationX
                if (g.translationY != card.translationY) g.translationY = card.translationY
                if (g.alpha != card.alpha) g.alpha = card.alpha
                // Nothing else sweeps pane bindings on this screen.
                syncPaneVisibility()
            }
            // WhatsApp hides the compose row under this card; the compose pill would otherwise show beside it.
            val shown = card.isShown && card.alpha > 0.01f && card.width > 0
            if (shown != convSelectionCardShown) {
                convSelectionCardShown = shown
                runCatching { syncConvFooter() }
            }
            true
        }
    }
    sync.run()
}

/** The card's fallback where the host is neither a FrameLayout nor a RelativeLayout: frosted like the snackbar. */
internal fun frostSelectionBottomMenu(card: View) {
    val apply = Runnable {
        runCatching {
            if (card.width <= 0 || card.height <= 0) return@runCatching
            // Material's own shadow and stroke would frame the glass; the frost carries its rim.
            runCatching { XposedHelpers.callMethod(card, "setCardElevation", 0f) }
            runCatching { XposedHelpers.callMethod(card, "setStrokeWidth", 0) }
            val radius = runCatching { XposedHelpers.callMethod(card, "getRadius") as? Float }.getOrNull()
                ?.takeIf { it > 0f } ?: card.dp(PANEL_ROW_RADIUS_DP)
            // The menu's scrim, not the wallpaper tint: the card floats over content, the snackbar's recipe.
            frost(
                card, tintOverride = MENU_SCRIM, allowSquare = true, ignorePadding = true,
                radiusOverride = radius, forceLabel = "message_selection_bottom_menu",
            )
            HookLog.hit("compat/floatingMenuBar")
        }.onFailure { logOnce("selection bottom menu frost threw: $it") }
    }
    // Posted: the card measures after attach, and frost needs a real size.
    card.post(apply)
    if (card.getTag(selBottomTag) == null) {
        card.setTag(selBottomTag, true)
        card.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply.run() }
    }
}

private val livePopupPaneTag = tagKey("wathemer-live-popup-pane")

/** The popup each live pane is showing for, so a dismiss can put the pane away before the next frame. */
private val livePopupPanes = WeakHashMap<PopupWindow, WeakReference<GlassView>>()

/** The floating menu's glass drawn live in the Activity's window: the chat blurred beneath and the wallpaper under that, as the card has. */
private fun liveDropdownPane(list: ViewGroup, pw: PopupWindow, radius: Float): Boolean {
    val row = popupAnchors[pw]?.get() ?: return false
    val decor = activityOf(row)?.window?.decorView ?: return false
    val host = decor.findViewById<View>(android.R.id.content) as? FrameLayout ?: return false
    var pane = host.getTag(livePopupPaneTag) as? GlassView
    if (pane == null || pane.parent !== host) {
        pane = GlassView(host.context).apply {
            underlay = wallpaperUnderlay(host)
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = host.dp(BLUR_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = host.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                tintColor = TRAY_SCRIM
            }
        }
        // The top of the Activity's content: over everything this window draws, under the popup's own rows.
        host.addView(pane, FrameLayout.LayoutParams(0, 0).apply { gravity = Gravity.TOP or Gravity.START })
        host.setTag(livePopupPaneTag, pane)
    }
    val glass = pane
    // The list host is the footer's sibling; without it the pane still shows the wallpaper it stands on.
    glass.backdrop = (convFooterRef?.get()?.parent as? ViewGroup)?.let { convListHost(it) }
    glass.params.cornerRadius = radius
    glass.alpha = 0f
    glass.visibility = View.VISIBLE
    livePopupPanes[pw] = WeakReference(glass)
    val hostAt = IntArray(2)
    val listAt = IntArray(2)
    // The list lives in another window and animates in there; the Activity's own pre-draw follows it, one frame at a time.
    host.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (!pw.isShowing || !list.isAttachedToWindow || livePopupPanes[pw]?.get() !== glass) {
                if (glass.visibility != View.GONE) glass.visibility = View.GONE
                host.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
            // Its own heartbeat: the popup's frames do not redraw this window, so the pane asks for the next one.
            glass.postInvalidateOnAnimation()
            if (list.visibility != View.VISIBLE || list.width <= 0 || list.height <= 0) {
                if (glass.alpha != 0f) glass.alpha = 0f
                return true
            }
            val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return true
            if (lp.width != list.width || lp.height != list.height) {
                lp.width = list.width
                lp.height = list.height
                glass.layoutParams = lp
            }
            host.getLocationOnScreen(hostAt)
            list.getLocationOnScreen(listAt)
            val x = (listAt[0] - hostAt[0]).toFloat()
            val y = (listAt[1] - hostAt[1]).toFloat()
            if (glass.translationX != x) glass.translationX = x
            if (glass.translationY != y) glass.translationY = y
            if (glass.alpha != list.alpha) glass.alpha = list.alpha
            return true
        }
    })
    logOnce("floating menu: live pane in ${host.context.javaClass.simpleName}")
    return true
}

/** Called from the popup's dismiss: the pane goes before the next frame instead of lingering until one is drawn. */
private fun hideLiveDropdownPane(pw: PopupWindow) {
    val glass = livePopupPanes.remove(pw)?.get() ?: return
    if (glass.visibility != View.GONE) glass.visibility = View.GONE
}
