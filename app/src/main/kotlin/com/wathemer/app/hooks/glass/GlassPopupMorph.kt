// A menu's glass drawn in the Activity's own window, so it can rise out of the button that opened the
// menu and sink back into it; the menu's window sits below its button and could never reach it.
package com.wathemer.app.hooks.glass

import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import com.wathemer.app.glass.GlassBubblePane
import com.wathemer.app.glass.GlassParams
import com.wathemer.app.glass.RectList
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference

internal object GlassPopupMorph {

    /** Set at install; off leaves every menu on the pane inside its own window. */
    var enabled = false

    private const val OPEN_MS = 250L

    /** A little quicker than the opening, and inside the window's own exit fade. */
    private const val CLOSE_MS = 200L

    /** The screen snapshot lands a frame or two after the show; past this the glass starts on the wallpaper copy. */
    private const val SNAP_WAIT_MS = 100L

    private const val IDLE = 0
    private const val OPEN = 1
    private const val HOLD = 2
    private const val CLOSE = 3

    /** One pane per Activity content, at its top, kept as a tag on it: a map entry would hold its own key alive. */
    private val paneTag = tagKey("wathemer-popup-morph-pane")

    private var phase = IDLE
    private var startMs = 0L
    private var waitSince = 0L
    private var contentRef: WeakReference<View>? = null
    private var popupRef: WeakReference<PopupWindow>? = null
    private var paneRef: WeakReference<GlassBubblePane>? = null

    // The button's box and the menu's, in screen px, and the rect between them that was last drawn.
    private val anchorRect = RectF()
    private var anchorRadius = 0f
    private var menuRadius = 0f
    private val menuRect = RectF()
    private var haveMenu = false
    private val current = RectF()
    private var currentRadius = 0f
    private val closeFrom = RectF()
    private var closeFromRadius = 0f
    private val rows = RectList()
    private val at = IntArray(2)

    /** The content's own alpha before the rows were tied to the glass; restored whatever way the morph ends. */
    private var contentAlpha0 = 1f

    /** Take a menu whose button is in the Activity's own window; false hands it back to the pane in the popup. */
    fun begin(content: View, radiusPx: Float, anchor: View?, pw: PopupWindow): Boolean {
        if (!enabled || anchor == null) return false
        if (anchor.width <= 0 || anchor.height <= 0) return false
        val decor = activityOf(anchor)?.window?.decorView ?: return false
        // A button inside a dialog's window sits above this pane; that menu keeps its own glass.
        if (anchor.rootView !== decor) return false
        val host = decor.findViewById<View>(android.R.id.content) as? FrameLayout ?: return false
        val pane = paneFor(host) ?: return false
        anchor.getLocationOnScreen(at)
        anchorRect.set(
            at[0].toFloat(), at[1].toFloat(),
            (at[0] + anchor.width).toFloat(), (at[1] + anchor.height).toFloat(),
        )
        anchorRadius = minOf(anchor.width, anchor.height) / 2f
        menuRadius = radiusPx
        contentRef = WeakReference(content)
        popupRef = WeakReference(pw)
        paneRef = WeakReference(pane)
        haveMenu = false
        phase = OPEN
        // The clock starts once the snapshot has landed, so the glass never samples a frame of itself.
        startMs = 0L
        waitSince = 0L
        // The rows arrive with the glass, not ahead of it: a layer for a quarter second, on a menu-sized view.
        contentAlpha0 = content.alpha
        content.alpha = 0f
        pane.postInvalidateOnAnimation()
        return true
    }

    private fun restoreRows() {
        contentRef?.get()?.let { if (it.alpha != contentAlpha0) it.alpha = contentAlpha0 }
    }

    /** The menu is going: the glass sinks back into its button while the window's own exit fades the rows. */
    fun onDismiss(pw: PopupWindow) {
        if (popupRef?.get() !== pw) return
        if (phase == OPEN || phase == HOLD) startClose()
        paneRef?.get()?.postInvalidateOnAnimation()
    }

    private fun startClose() {
        restoreRows()
        if (!haveMenu && phase == OPEN && startMs == 0L) {
            phase = IDLE
            return
        }
        closeFrom.set(current)
        closeFromRadius = currentRadius
        phase = CLOSE
        startMs = SystemClock.uptimeMillis()
    }

    private fun paneFor(host: FrameLayout): GlassBubblePane? {
        (host.getTag(paneTag) as? GlassBubblePane)?.let { if (it.parent === host) return it }
        val d = host.resources.displayMetrics.density
        val pane = GlassBubblePane(host.context).apply {
            // The popup pane's recipe: the snapshot is the composited screen, graded and lifted already.
            params = GlassParams(d).apply {
                blurRadius = host.dp(BLUR_DP)
                cornerRadius = host.dp(PANEL_ROW_RADIUS_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = host.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                tintColor = MENU_SCRIM
                saturation = 1f
                bloom = 0f
                transGamma = 1f
                detail = 0f
            }
            tint = { MENU_SCRIM }
            backdrop = { screenSnap ?: bubbleBackdrop(host) }
            placement = { if (screenSnap != null) screenSnapPlace else bubblePlacement(host) }
            dim = { 0f }
            rimColor = glassTint(BUBBLE_RIM_ALPHA)
            rimWidth = d
            collect = { out -> collect(out) }
        }
        // The top of the Activity's content: over everything this window draws, under every window above it.
        host.addView(
            pane,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        host.setTag(paneTag, pane)
        XposedBridge.log("[$TAG] popup morph pane inserted into ${host.context.javaClass.simpleName}")
        return pane
    }

    /** The menu's rect as it stands; false once its rows are gone. */
    private fun readMenu(): Boolean {
        val content = contentRef?.get() ?: return false
        if (!content.isAttachedToWindow) return false
        rows.clear()
        collectPopupRowRects(content, rows)
        if (rows.size == 0) return false
        menuRect.set(rows[0])
        haveMenu = true
        return true
    }

    private fun collect(out: RectList) {
        val pane = paneRef?.get() ?: return
        val now = SystemClock.uptimeMillis()
        when (phase) {
            IDLE -> return
            OPEN -> {
                if (startMs == 0L) {
                    if (screenSnapPending && (waitSince == 0L || now - waitSince < SNAP_WAIT_MS)) {
                        if (waitSince == 0L) waitSince = now
                        pane.postInvalidateOnAnimation()
                        return
                    }
                    startMs = now
                }
                if (!readMenu()) {
                    startClose()
                    if (phase == CLOSE) emit(out, pane)
                    return
                }
                val p = easeOut(((now - startMs).toFloat() / OPEN_MS).coerceIn(0f, 1f))
                lerpInto(anchorRect, anchorRadius, menuRect, menuRadius, p)
                emit(out, pane)
                contentRef?.get()?.alpha = contentAlpha0 * p
                if (p >= 1f) {
                    restoreRows()
                    phase = HOLD
                }
            }
            HOLD -> {
                val pw = popupRef?.get()
                if (pw == null || !pw.isShowing || !readMenu()) {
                    startClose()
                    if (phase == CLOSE) emit(out, pane)
                    return
                }
                current.set(menuRect)
                currentRadius = menuRadius
                emit(out, pane)
            }
            CLOSE -> {
                val p = easeIn(((now - startMs).toFloat() / CLOSE_MS).coerceIn(0f, 1f))
                if (p >= 1f) {
                    phase = IDLE
                    return
                }
                lerpInto(closeFrom, closeFromRadius, anchorRect, anchorRadius, p)
                emit(out, pane)
            }
        }
    }

    private fun lerpInto(a: RectF, ra: Float, b: RectF, rb: Float, p: Float) {
        current.set(
            a.left + (b.left - a.left) * p,
            a.top + (b.top - a.top) * p,
            a.right + (b.right - a.right) * p,
            a.bottom + (b.bottom - a.bottom) * p,
        )
        currentRadius = ra + (rb - ra) * p
    }

    private fun emit(out: RectList, pane: GlassBubblePane) {
        // Per frame and never through onChanged, which this pane does not wire; the painter's radii guard sees it.
        pane.params.cornerRadius = currentRadius
        out.add(current.left, current.top, current.right, current.bottom)
    }

    private fun easeOut(p: Float): Float {
        val u = 1f - p
        return 1f - u * u * u
    }

    private fun easeIn(p: Float): Float = p * p * p
}
