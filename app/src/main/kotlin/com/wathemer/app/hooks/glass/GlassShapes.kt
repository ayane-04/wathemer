// Small drawing and observation types the glass surfaces share; none of them touch glass state.
// Each is a plain framework subclass: an outline provider, a drawable, or a pre-draw position watch.
package com.wathemer.app.hooks.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import com.wathemer.app.glass.BubbleGlassPainter
import com.wathemer.app.glass.FrostDrawable
import com.wathemer.app.glass.GlassParams

/** Mutable and reused: the provider is asked for the outline on every invalidateOutline. */
internal class CardOutline(
    private var l: Int,
    private var t: Int,
    private var r: Int,
    private var b: Int,
    private var radius: Float,
) : ViewOutlineProvider() {
    /** True only when something moved; an unconditional invalidateOutline from a pre-draw path schedules the next frame forever. */
    fun set(l: Int, t: Int, r: Int, b: Int, radius: Float): Boolean {
        if (this.l == l && this.t == t && this.r == r && this.b == b && this.radius == radius) {
            return false
        }
        this.l = l; this.t = t; this.r = r; this.b = b; this.radius = radius
        return true
    }

    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(l, t, r, b, radius)
    }
}

internal class PaneRound(
    private val insetX: Float,
    private val insetY: Float,
    private val radius: Float,
) : ViewOutlineProvider() {
    private var builtW = -1
    private var builtH = -1

    /** True when the view's size has moved since the outline was last rebuilt for it. */
    fun needsRebuild(w: Int, h: Int): Boolean {
        if (w == builtW && h == builtH) return false
        builtW = w
        builtH = h
        return true
    }

    override fun getOutline(view: View, outline: Outline) {
        val l = insetX.toInt()
        val t = insetY.toInt()
        val r = view.width - l
        val b = view.height - t
        if (r <= l || b <= t) return
        outline.setRoundRect(l, t, r, b, radius)
    }
}

internal class InnerRound(private val radius: Float) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
}

/** A plain Drawable on purpose: an InsetDrawable reports its inset as padding and the row walks away from itself. */
internal class SelectionPane(
    private val host: View,
    private val insetX: Float,
    private val insetY: Float,
    private val radius: Float,
    private val fill: Int,
    private val lift: Int,
    private val rim: Int,
    private val rimWidth: Float,
    private val sharp: () -> Bitmap?,
    private val placement: () -> Matrix?,
) : Drawable() {
    private val box = RectF()
    private val path = Path()
    private val at = IntArray(2)
    private val m = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(
        Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG,
    )

    /** The row's own recipe for the bubble program, built on the first draw that can use it; the painter writes into it. */
    private var optics: GlassParams? = null

    /** The fill over the lift as one straight colour: the program takes a straight tint and multiplies it itself. */
    private val compositeTint: Int = run {
        val af = (fill ushr 24 and 0xFF) / 255f
        val al = (lift ushr 24 and 0xFF) / 255f
        val a = af + al * (1f - af)
        if (a <= 0f) {
            0
        } else {
            val wf = af / a
            val wl = al * (1f - af) / a
            fun ch(shift: Int) =
                ((fill shr shift and 0xFF) * wf + (lift shr shift and 0xFF) * wl + 0.5f).toInt().coerceIn(0, 255)
            ((a * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        box.set(b)
        box.inset(insetX, insetY)
        if (box.width() <= 0f || box.height() <= 0f) return
        val r = minOf(radius, box.height() / 2f)

        val bmp = runCatching { sharp() }.getOrNull()
        val place = if (bmp != null) placement() else null
        // The row as glass: the program draws the copy, the tint and the rim, so nothing below runs for it.
        if (ROW_OPTICS && bmp != null && place != null && canvas.isHardwareAccelerated &&
            drawOptics(canvas, bmp, place, r)
        ) {
            return
        }
        val save = canvas.save()
        path.reset()
        path.addRoundRect(box, r, r, Path.Direction.CW)
        canvas.clipPath(path)
        if (bmp != null && place != null) {
            host.getLocationOnScreen(at)
            m.set(place)
            m.postTranslate(-at[0].toFloat(), -at[1].toFloat())
            canvas.drawBitmap(bmp, m, bmpPaint)
        }
        paint.style = Paint.Style.FILL
        if (lift ushr 24 != 0) {
            paint.color = lift
            canvas.drawRoundRect(box, r, r, paint)
        }
        paint.color = fill
        canvas.drawRoundRect(box, r, r, paint)
        canvas.restoreToCount(save)

        if (rimWidth > 0f && rim ushr 24 != 0) {
            val h = rimWidth / 2f
            box.inset(h, h)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = rimWidth
            paint.color = rim
            canvas.drawRoundRect(box, r - h, r - h, paint)
        }
    }

    /** Guarded whole and falling through to the fills: this runs inside the row's draw pass. */
    private fun drawOptics(canvas: Canvas, bmp: Bitmap, place: Matrix, r: Float): Boolean {
        return try {
            val density = host.resources.displayMetrics.density
            val p = optics ?: GlassParams(density).apply {
                refractionEnabled = true
                bevelFraction = ROW_OPTICS_BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = DISPLACE_DP * density
                fresnelStrength = 0.5f
                // No fringe over a copy, as the bubbles and pills have it.
                dispersion = 0f
            }.also { optics = it }
            p.cornerRadius = r
            host.getLocationOnScreen(at)
            // No stroke and no fills: the program's edge is the rim, and the copy carries its dim already.
            selectionPainter(density).paint(
                canvas, box, p, compositeTint, bmp, place,
                screenX = at[0] + box.left, screenY = at[1] + box.top,
                dim = 0f, rimColor = 0, rimWidth = 0f,
            )
            true
        } catch (t: Throwable) {
            if (!loggedOpticsThrow) {
                loggedOpticsThrow = true
                Log.w("WaThemer.Select", "row optics threw, fills kept", t)
            }
            false
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** A row's band as a fraction of its smaller side, the pills' figure. */
        private const val ROW_OPTICS_BEVEL_FRACTION = 0.15f

        /** Process-wide latch: a fresh pane per selection would log per row. */
        private var loggedOpticsThrow = false

        /** Its own painter per density: this binds the selection copy, the pills the bubble copy, and a painter caches one. */
        private val painters = HashMap<Float, BubbleGlassPainter>()

        private fun selectionPainter(density: Float): BubbleGlassPainter =
            painters.getOrPut(density) { BubbleGlassPainter(density) }
    }
}

/** Scrolling repositions rows without re-recording, freezing the sampled wallpaper; the pre-draw listener detaches with its view, the attach watcher stays. */
internal class RowScrollWatch(private val host: View) :
    ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private val at = IntArray(2)
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private var observing = false

    fun arm() {
        host.addOnAttachStateChangeListener(this)
        if (host.isAttachedToWindow) attach()
    }

    private fun attach() {
        if (observing) return
        host.viewTreeObserver.addOnPreDrawListener(this)
        observing = true
    }

    override fun onViewAttachedToWindow(v: View) = attach()

    override fun onViewDetachedFromWindow(v: View) {
        if (!observing) return
        host.viewTreeObserver.removeOnPreDrawListener(this)
        observing = false
    }

    override fun onPreDraw(): Boolean {
        if (host.background !is SelectionPane) return true
        host.getLocationOnScreen(at)
        if (at[0] != lastX || at[1] != lastY) {
            lastX = at[0]
            lastY = at[1]
            host.invalidate()
        }
        return true
    }
}

/** Detaches with its view: one listener per recycled row, left registered, accumulates for the window's life. */
internal class FrostMoveWatch(private val host: View) :
    ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private val at = IntArray(2)
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private var observing = false

    fun arm() {
        host.addOnAttachStateChangeListener(this)
        if (host.isAttachedToWindow) attach()
    }

    private fun attach() {
        if (observing) return
        host.viewTreeObserver.addOnPreDrawListener(this)
        observing = true
    }

    override fun onViewAttachedToWindow(v: View) = attach()

    override fun onViewDetachedFromWindow(v: View) {
        if (!observing) return
        host.viewTreeObserver.removeOnPreDrawListener(this)
        observing = false
    }

    override fun onPreDraw(): Boolean {
        if (host.background !is FrostDrawable || !host.isShown || host.width <= 0) return true
        host.getLocationOnScreen(at)
        if (at[0] != lastX || at[1] != lastY) {
            lastX = at[0]
            lastY = at[1]
            host.invalidate()
        }
        return true
    }
}
