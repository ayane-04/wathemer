// The active tab's pill, module-owned so it can flow between tabs instead of blinking across.
// Driven by the indicators' layout boxes, never their transforms: Material scales and fades them.
package com.wathemer.app.hooks.glass

import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.wathemer.app.glass.GlassBubblePane
import com.wathemer.app.glass.GlassParams
import com.wathemer.app.glass.RectList
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.sqrt

internal object GlassNavDroplet {

    /** Set at install; off leaves the indicator on its frost stamp. */
    var enabled = false

    // One spring per edge, the leading one stiffer: the drop leaves at once and its tail catches up, which is
    // the stretch itself rather than a width scaled by speed. Damping just under one keeps a hint of rebound.
    private const val LEAD_STIFFNESS = 1100f

    private const val TRAIL_STIFFNESS = 360f

    private const val DAMPING = 0.82f

    /** How much of its stretch the drop gives back in height, and the floor; a thinning drop reads as liquid. */
    private const val SQUEEZE = 0.12f

    private const val SQUEEZE_FLOOR = 0.9f

    /** Alpha 1, not 0: a fully transparent drawable is still the ripple's mask, and a null one has no size. */
    private const val MASK_ARGB = 0x01FFFFFF

    private val indicators = ArrayList<WeakReference<View>>()
    private var paneRef: WeakReference<GlassBubblePane>? = null
    private val at = IntArray(2)

    // The two edges, each with its own velocity; NaN until the first frame places them.
    private var edgeL = Float.NaN
    private var edgeR = 0f
    private var velL = 0f
    private var velR = 0f
    private var lastMs = 0L

    /** One indicator, as the dispatcher meets it. Idempotent: the menu view rebuilds these on every refresh. */
    fun arm(indicator: View) {
        if (!enabled) return
        var seen = false
        var i = 0
        while (i < indicators.size) {
            val v = indicators[i].get()
            if (v == null) {
                indicators.removeAt(i)
                continue
            }
            if (v === indicator) seen = true
            i++
        }
        if (!seen) indicators.add(WeakReference(indicator))
        // Forced, because the menu view re-applies its own indicator drawable whenever it refreshes.
        forceBg(indicator, ColorDrawable(MASK_ARGB), "active tab pill")
        ensurePane(indicator)
    }

    /** The bar the indicators live in; its own reference first, then a walk for the layouts that differ. */
    private fun navOf(indicator: View): ViewGroup? {
        navBarRef?.get()?.let { nav -> if (isAncestorOf(nav, indicator)) return nav as? ViewGroup }
        var p = indicator.parent as? ViewGroup
        var hops = 0
        while (p != null && hops < 6) {
            val n = p.javaClass.name
            if (p is FrameLayout && (n.contains("NavigationBar") || n.contains("BottomBar"))) return p
            p = p.parent as? ViewGroup
            hops++
        }
        return null
    }

    private fun ensurePane(indicator: View) {
        val existing = paneRef?.get()
        val nav = navOf(indicator) ?: return
        if (existing != null && existing.parent === nav) return
        if (nav !is FrameLayout) {
            logOnce("nav droplet skipped: ${nav.javaClass.simpleName} does not stack children")
            return
        }
        val d = nav.resources.displayMetrics.density
        val pane = GlassBubblePane(nav.context)
        pane.params = GlassParams(d).apply {
            // Nothing to transmit: the nav's own pane behind supplies the blur, this is the lit pill over it.
            refractionEnabled = false
            bevelFraction = BEVEL_FRACTION
            depthRatio = DEPTH_RATIO
            fresnelStrength = 0.5f
            // A stadium whatever the size; the painter clamps this to half the smaller side.
            cornerRadius = 999f
            detail = 0f
            tintColor = glassTint(CHIP_ALPHA_SELECTED)
        }
        pane.tint = { glassTint(CHIP_ALPHA_SELECTED) }
        pane.backdrop = { null }
        pane.placement = { null }
        pane.dim = { 0f }
        pane.rimColor = glassTint(CHIP_RIM_ALPHA)
        pane.rimWidth = d
        // The hard line is the pill's edge: this pane transmits nothing, so the light pass leaves only the ring.
        pane.forceRim = true
        pane.collect = { out -> collectDroplet(out) }
        // Index 0, so it draws under the icons and labels; sized from the bar's own box, never MATCH_PARENT.
        nav.addView(pane, 0, FrameLayout.LayoutParams(0, 0))
        paneRef = WeakReference(pane)
        sizePane(nav, pane)
        nav.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sizePane(nav, pane) }
        XposedBridge.log("[$TAG] nav droplet inserted (${nav.javaClass.simpleName})")
    }

    private fun sizePane(nav: ViewGroup, pane: GlassBubblePane) {
        if (nav.width <= 0 || nav.height <= 0) return
        val lp = pane.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width == nav.width && lp.height == nav.height) return
        lp.width = nav.width
        lp.height = nav.height
        pane.layoutParams = lp
    }

    /** The selected tab's indicator; alpha decides while Material is mid-swap and neither reads as selected. */
    private fun selectedIndicator(): View? {
        var best: View? = null
        var bestAlpha = -1f
        var i = 0
        while (i < indicators.size) {
            val v = indicators[i].get()
            if (v == null) {
                indicators.removeAt(i)
                continue
            }
            if (v.isShown && v.width > 0 && v.height > 0) {
                if (v.isSelected) return v
                if (v.alpha > bestAlpha) {
                    bestAlpha = v.alpha
                    best = v
                }
            }
            i++
        }
        return best
    }

    /** One rect, its edges sprung; the pane redraws only while they move, so a settled drop is free. */
    private fun collectDroplet(out: RectList) {
        val target = selectedIndicator() ?: return
        // The container's screen position plus the indicator's layout box: its own position carries Material's scale.
        val container = target.parent as? View ?: return
        container.getLocationOnScreen(at)
        val w = target.width
        val h = target.height
        if (w <= 0 || h <= 0) return
        val top = (at[1] + target.top).toFloat()
        val tl = (at[0] + target.left).toFloat()
        val tr = tl + w

        val now = SystemClock.uptimeMillis()
        if (edgeL.isNaN()) {
            edgeL = tl
            edgeR = tr
            velL = 0f
            velR = 0f
            lastMs = now
        }
        // Clamped: a frame lost to a stall must not fling the drop across the bar.
        val dt = (now - lastMs).coerceIn(1L, 32L) / 1000f
        lastMs = now
        // One direction for the whole pill: per-edge tests swap the springs over during the settle.
        val rightLeads = tl + tr > edgeL + edgeR
        val kL = if (rightLeads) TRAIL_STIFFNESS else LEAD_STIFFNESS
        val kR = if (rightLeads) LEAD_STIFFNESS else TRAIL_STIFFNESS
        velL += (-kL * (edgeL - tl) - 2f * DAMPING * sqrt(kL) * velL) * dt
        velR += (-kR * (edgeR - tr) - 2f * DAMPING * sqrt(kR) * velR) * dt
        edgeL += velL * dt
        edgeR += velR * dt
        // Landed: snap, so the rect stops changing and the pane stops asking for frames.
        if (abs(tl - edgeL) < 0.5f && abs(velL) < 1f) {
            edgeL = tl
            velL = 0f
        }
        if (abs(tr - edgeR) < 0.5f && abs(velR) < 1f) {
            edgeR = tr
            velR = 0f
        }
        // Never narrower than the tab it is on: the two edges crossing would invert the rect.
        if (edgeR - edgeL < w * 0.5f) {
            val mid = (edgeL + edgeR) * 0.5f
            edgeL = mid - w * 0.25f
            edgeR = mid + w * 0.25f
        }
        // Thinner while it is stretched, about its own centre line; at rest the factor is exactly 1.
        val squeeze = (1f - SQUEEZE * ((edgeR - edgeL) / w - 1f)).coerceIn(SQUEEZE_FLOOR, 1f)
        val half = h * squeeze * 0.5f
        val cy = top + h * 0.5f
        out.add(edgeL, cy - half, edgeR, cy + half)
    }
}
