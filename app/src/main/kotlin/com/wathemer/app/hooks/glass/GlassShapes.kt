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
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import com.wathemer.app.glass.FrostDrawable

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

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        box.set(b)
        box.inset(insetX, insetY)
        if (box.width() <= 0f || box.height() <= 0f) return
        val r = minOf(radius, box.height() / 2f)

        val bmp = runCatching { sharp() }.getOrNull()
        val place = if (bmp != null) placement() else null
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

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Scrolling repositions rows without re-recording, freezing the sampled wallpaper; armed once, never removed, deliberately. */
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
