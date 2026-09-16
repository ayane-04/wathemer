// The conversation's bubble glass as the list's own background, so whatever the list's render node does to its content, the overscroll stretch above all, carries the glass with the text.
package com.wathemer.app.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver

/** Every visible bubble's glass, painted behind the rows from inside the list's own display list; in-row glass freezes on scroll and a pane beside the list misses the stretch. */
class GlassBubbleBackground(private val host: View) : Drawable() {

    var params: GlassParams = GlassParams(host.resources.displayMetrics.density)
    var tint: () -> Int = { 0 }
    var backdrop: () -> Bitmap? = { null }
    var placement: () -> Matrix? = { null }
    var dim: () -> Float = { 0f }
    var rimColor: Int = 0
    var rimWidth: Float = 0f

    /** Screen y where bubbles start dissolving as they rise, and the ramp's length. 0 disables. */
    var fadeLineScreenY: Float = 0f
    var fadeLen: Float = 0f

    /** Flattened-corner radius for grouped continuations, px; 0 turns merging off and the flags are ignored. */
    var flatRadiusPx: Float = 0f
    private val radiiBuf = FloatArray(4)

    /** Fills the list with every visible bubble's rect, in screen pixels. */
    var collect: ((RectList) -> Unit)? = null

    /** Fired when the host resizes, the cheapest signal that the backdrop mapping went stale. */
    var onGeometryChanged: (() -> Unit)? = null

    private val painter = BubbleGlassPainter(host.resources.displayMetrics.density)
    private val shown = RectList()
    private val pending = RectList()
    private val at = IntArray(2)
    private val local = RectF()
    private var lastBitmap: Bitmap? = null
    private var lastTint = 0
    private var loggedFirst = false
    private var loggedThrow = false
    private var loggedCollectThrow = false

    /** The host re-records on every scroll frame by itself; this asks for one more when a swipe moved a rect or the backdrop changed, which alone would not. */
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        // Guarded: collect and backdrop walk live WhatsApp views, and an escape here takes the traversal down.
        try {
            val c = collect
            if (c != null) {
                pending.clear()
                c(pending)
                val bmp = backdrop()
                val t = tint()
                if (!pending.contentEquals(shown) || bmp !== lastBitmap || t != lastTint) {
                    shown.copyFrom(pending)
                    lastBitmap = bmp
                    lastTint = t
                    invalidateSelf()
                }
            }
        } catch (t: Throwable) {
            if (!loggedCollectThrow) {
                loggedCollectThrow = true
                Log.w("WaThemer.Bubble", "background pre-draw collect threw, frame skipped", t)
            }
        }
        true
    }

    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.viewTreeObserver.addOnPreDrawListener(preDraw)
        }

        override fun onViewDetachedFromWindow(v: View) {
            // The observer belongs to the window; a listener left behind would keep this drawable's host alive.
            v.viewTreeObserver.removeOnPreDrawListener(preDraw)
        }
    }

    /** Becomes the host's background and follows its window. */
    fun install() {
        host.background = this
        host.addOnAttachStateChangeListener(attach)
        if (host.isAttachedToWindow) host.viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    /** True while this is still what the host draws behind its rows. */
    val active: Boolean
        get() = host.background === this && host.isAttachedToWindow

    /** True when [v] is the list this draws behind; one conversation's background must not stand in for another's. */
    fun serves(v: View): Boolean = host === v

    /** Stops a superseded instance collecting: both listeners go, the drawable itself the host has already let go of. */
    fun release() {
        host.removeOnAttachStateChangeListener(attach)
        host.viewTreeObserver.removeOnPreDrawListener(preDraw)
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        onGeometryChanged?.invoke()
    }

    override fun draw(canvas: Canvas) {
        if (!params.enabled || shown.size == 0) return
        val bmp: Bitmap?
        val place: Matrix?
        val t: Int
        val d: Float
        val rec: WallpaperRecord?
        // Guarded like painter.paint below: these lambdas walk live WhatsApp views on the draw path.
        try {
            bmp = backdrop()
            place = if (bmp != null) placement() else null
            t = tint()
            d = dim()
            // The sharp source for the rim, once per draw; the record is the window's own.
            rec = if (params.detail > 0f) GlassView.wallpaperRecordFor?.invoke(host) else null
        } catch (e: Throwable) {
            if (!loggedThrow) {
                loggedThrow = true
                Log.w("WaThemer.Bubble", "background backdrop lambdas threw, frame skipped", e)
            }
            return
        }
        if (!loggedFirst) {
            loggedFirst = true
            Log.i(
                "WaThemer.Bubble",
                "background drawing ${shown.size} bubbles, size=${bounds.width()}x${bounds.height()} " +
                    "backdrop=${bmp?.width}x${bmp?.height} place=${place != null}",
            )
        }
        host.getLocationOnScreen(at)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        for (i in 0 until shown.size) {
            val s = shown[i]
            local.set(s.left - at[0], s.top - at[1], s.right - at[0], s.bottom - at[1])
            // A fling reports rows still outside the list; shading them is pure cost.
            if (local.bottom <= 0f || local.top >= h || local.right <= 0f || local.left >= w) continue
            var radii: FloatArray? = null
            val flag = shown.flagAt(i)
            if (flatRadiusPx > 0f && (flag and GlassBubblePane.FLAG_EXT) != 0) {
                val rr = params.cornerRadius
                radiiBuf[0] = if (flag and GlassBubblePane.FLAG_OUTGOING == 0) flatRadiusPx else rr
                radiiBuf[1] = if (flag and GlassBubblePane.FLAG_OUTGOING != 0) flatRadiusPx else rr
                radiiBuf[2] = rr
                radiiBuf[3] = rr
                radii = radiiBuf
            }
            // Guarded: an escape here takes WhatsApp's whole draw traversal down.
            runCatching {
                painter.paint(
                    canvas, local, params, t, bmp, place,
                    screenX = s.left, screenY = s.top,
                    dim = d, rimColor = rimColor, rimWidth = rimWidth,
                    fadeLineScreenY = fadeLineScreenY, fadeLen = fadeLen,
                    radii = radii,
                    sharp = rec?.src, sharpPlace = rec?.srcPlacement,
                    // The copy folded its dim, so the sharp tap takes the same; an unfolded copy dims both later in the program.
                    sharpDim = if (rec != null && rec.bubbleDimFolded) rec.dimAlpha / 255f else 0f,
                    mask = shown.refAt(i) as? MaskRef,
                )
            }.onFailure {
                if (!loggedThrow) {
                    loggedThrow = true
                    Log.w("WaThemer.Bubble", "background painter threw, bubble skipped", it)
                }
            }
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
