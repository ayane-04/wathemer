package com.wathemer.app.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View

/**
 * Frosted glass as a background drawable, for surfaces a GlassView child cannot serve. Static
 * by design: the wallpaper does not move, and the blur is a downscale/upscale, not a convolution.
 */
class FrostDrawable(
    private val view: View,
    /**
     * Pre-blurred wallpaper to sample; null is correct over a live glass surface, which already
     * supplies the blur. No InsetDrawable here: setBackground folds its padding in and it runs away.
     */
    private val small: Bitmap?,
    private var radius: Float,
    private var tint: Int,
    /** A 1px-ish rim, echoing the Fresnel highlight the GlassView panes draw. 0 disables it. */
    private val strokeWidth: Float = 0f,
    private val strokeColor: Int = 0,
    /** Fill the full bounds; the Calls tab discs' own ripple ignores their padding. */
    private val ignorePadding: Boolean = false,
) : Drawable() {

    private val loc = IntArray(2)
    private val clip = RectF()
    private val path = Path()
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = strokeColor
        this.strokeWidth = strokeWidth
    }

    fun setRadius(r: Float) {
        if (r != radius) {
            radius = r
            invalidateSelf()
        }
    }

    /** Selection is expressed as a brighter pill, so this changes at bind time. */
    fun setTintColor(c: Int) {
        if (c != tint) {
            tint = c
            tintPaint.color = c
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val dm = view.resources.displayMetrics

        // Matrix, not an integer src rect: the bitmap is not screen-sized and rounding jumped layouts.
        // Screen, not window, position: in a dialog getLocationInWindow sampled well below itself.
        if (small != null) {
            view.getLocationOnScreen(loc)
            matrix.reset()
            matrix.setScale(
                dm.widthPixels / small.width.toFloat(),
                dm.heightPixels / small.height.toFloat(),
            )
            matrix.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
        }

        // Inset by the view's padding: a chip's touch target is taller than its visible pill.
        if (ignorePadding) {
            clip.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        } else {
            clip.set(
                (b.left + view.paddingLeft).toFloat(),
                (b.top + view.paddingTop).toFloat(),
                (b.right - view.paddingRight).toFloat(),
                (b.bottom - view.paddingBottom).toFloat(),
            )
        }
        if (clip.width() <= 0f || clip.height() <= 0f) return
        val r = minOf(radius, clip.height() / 2f)
        path.reset()
        path.addRoundRect(clip, r, r, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(path)
        if (small != null) canvas.drawBitmap(small, matrix, paint)
        if (tint ushr 24 != 0) canvas.drawRoundRect(clip, r, r, tintPaint)
        canvas.restoreToCount(save)
        // Outside the clip, inset by half the stroke, so the rim is not sliced in half by the edge.
        if (strokeWidth > 0f && strokeColor ushr 24 != 0) {
            val h = strokeWidth / 2f
            clip.inset(h, h)
            canvas.drawRoundRect(clip, r - h, r - h, strokePaint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Blur strength via downscale. Do not cut it to fix blockiness; the mapping, not this, causes that. */
        private const val BLUR_SHRINK = 20

        /** Rebuild at 1/4 before use: one bilinear step from 1/20 to full size leaves visible facets. */
        private const val SMOOTH_FRACTION = 4

        /** Build the shared blurred copy by progressive halving; one big jump leaves blocky facets. */
        fun shrinkOf(source: Bitmap): Bitmap? = runCatching {
            var cur = source
            var w = source.width
            var h = source.height
            val floor = (source.width / BLUR_SHRINK).coerceAtLeast(1)
            while (w / 2 >= floor && h / 2 >= 1) {
                val next = Bitmap.createScaledBitmap(cur, w / 2, (h / 2).coerceAtLeast(1), true)
                if (cur !== source) cur.recycle()
                cur = next
                w = cur.width
                h = cur.height
            }
            val ceiling = (source.width / SMOOTH_FRACTION).coerceAtLeast(1)
            while (w * 2 <= ceiling) {
                val next = Bitmap.createScaledBitmap(cur, w * 2, h * 2, true)
                if (cur !== source) cur.recycle()
                cur = next
                w = cur.width
                h = cur.height
            }
            if (cur === source) Bitmap.createScaledBitmap(source, ceiling, source.height / SMOOTH_FRACTION, true)
            else cur
        }.getOrNull()
    }
}
