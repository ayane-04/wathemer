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
import java.lang.ref.WeakReference

/**
 * Frosted glass as a background drawable, for surfaces a GlassView child cannot serve. Static
 * by design: the wallpaper does not move, and the blur is a downscale/upscale, not a convolution.
 */
class FrostDrawable(
    view: View,
    /** The window's wallpaper record, resolved per draw through the view; null is right over a live glass surface, which supplies the blur. */
    private val source: ((View) -> WallpaperRecord?)?,
    private var radius: Float,
    private var tint: Int,
    /** A 1px-ish rim, echoing the Fresnel highlight the GlassView panes draw. 0 disables it. */
    private val strokeWidth: Float = 0f,
    private val strokeColor: Int = 0,
    /** Fill the full bounds; the Calls tab discs' own ripple ignores their padding. */
    private val ignorePadding: Boolean = false,
) : Drawable() {

    // Weak: forcedBg holds this drawable, and a strong view here would pin its own map key.
    private val viewRef = WeakReference(view)

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
        val view = viewRef.get() ?: return
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val dm = view.resources.displayMetrics

        // Read once per draw: a wallpaper swap changes the record, and the bitmap and its matrix must come from one record.
        val rec = source?.invoke(view)
        val small = rec?.bubble
        // Matrix, not an integer src rect: the bitmap is not screen-sized and rounding jumped layouts.
        // Screen, not window, position: in a dialog getLocationInWindow sampled well below itself.
        if (small != null) {
            view.getLocationOnScreen(loc)
            val place = rec.bubblePlacement
            if (place != null) {
                // The wallpaper view is CENTER_CROP, so only its own matrix lands the patch that sits behind this view.
                matrix.set(place)
            } else {
                matrix.setScale(
                    dm.widthPixels / small.width.toFloat(),
                    dm.heightPixels / small.height.toFloat(),
                )
            }
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
        /** The copy's size: a quarter of the wallpaper, so the screen stretches it four times at most. */
        private const val FROST_SHRINK = 4

        /** Three box passes of this radius at a quarter size: a Gaussian at the frost's strength. */
        private const val FROST_RADIUS = 2
        private const val FROST_PASSES = 3

        /** Build the shared blurred copy: halve to a quarter, then a real blur; a stretched shrink shows its grid. */
        fun shrinkOf(source: Bitmap): Bitmap? = runCatching {
            val small = BitmapBlur.halveTo(source, FROST_SHRINK)
            val blurred = BitmapBlur.boxBlur(small, FROST_RADIUS, FROST_PASSES)
            if (small !== source) small.recycle()
            blurred
        }.getOrNull()
    }
}
