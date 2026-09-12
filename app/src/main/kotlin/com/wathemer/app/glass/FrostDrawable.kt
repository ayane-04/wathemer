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
import android.util.Log
import android.view.View
import java.lang.ref.WeakReference

/** Frosted glass as a background drawable for surfaces a GlassView child cannot serve; static by design, since the wallpaper does not move and its copy was blurred once when it was built. */
class FrostDrawable(
    view: View,
    /** The window's wallpaper record, resolved per draw through the view; null is right over a live glass surface, which supplies the blur. */
    private val source: ((View) -> WallpaperRecord?)?,
    private var radius: Float,
    private var tint: Int,
    /** A hairline rim echoing the Fresnel highlight the panes draw; zero disables it. */
    private val strokeWidth: Float = 0f,
    private val strokeColor: Int = 0,
    /** Fill the full bounds; the Calls tab discs' own ripple ignores their padding. */
    private val ignorePadding: Boolean = false,
    /** A pill's own recipe for the bubble program; null keeps the bitmap stamp. The painter writes into it, so never share one. */
    private val optics: GlassParams? = null,
) : Drawable() {

    // Weak: forcedBg holds this drawable, and a strong view here would pin its own map key.
    private val viewRef = WeakReference(view)

    private val loc = IntArray(2)
    private val clip = RectF()
    private val opticsClip = RectF()
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
        // The pill as glass: the program draws the tint and the rim, so nothing below runs for it.
        if (optics != null && small != null && rec.bubblePlacement != null && canvas.isHardwareAccelerated &&
            GlassShader.supported && drawOptics(canvas, dm.density, rec, small, r)
        ) {
            return
        }
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

    /** Guarded whole and falling through to the stamp: this runs inside the host's draw pass. */
    private fun drawOptics(canvas: Canvas, density: Float, rec: WallpaperRecord, small: Bitmap, r: Float): Boolean {
        return try {
            val p = optics ?: return false
            // Inset by the stroke, as the bubble drawable is: the painter draws a pixel past its rect and the parent clips at the bounds.
            val inset = strokeWidth.coerceAtLeast(1f)
            opticsClip.set(clip)
            opticsClip.inset(inset, inset)
            if (opticsClip.width() <= 0f || opticsClip.height() <= 0f) return false
            // The painter clamps the radius to the inset rect, so the pill stays a stadium.
            p.cornerRadius = r
            opticsPainter(density).paint(
                canvas, opticsClip, p, tint, small, rec.bubblePlacement,
                screenX = loc[0] + opticsClip.left, screenY = loc[1] + opticsClip.top,
                dim = rec.bubbleDim, rimColor = strokeColor, rimWidth = strokeWidth,
                sharp = rec.src, sharpPlace = rec.srcPlacement,
                sharpDim = if (rec.bubbleDimFolded) rec.dimAlpha / 255f else 0f,
            )
            true
        } catch (t: Throwable) {
            if (!loggedOpticsThrow) {
                loggedOpticsThrow = true
                Log.w("WaThemer.Frost", "optics threw, pill kept its stamp", t)
            }
            false
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** The copy's size: a quarter of the wallpaper, so the screen stretches it four times at most. */
        const val FROST_SHRINK = 4

        /** Three box passes of this radius at a quarter size: the fixed frost, used when the copies do not follow the slider. */
        const val FROST_RADIUS = 2
        private const val FROST_PASSES = 3

        /** Process-wide latch: a pill re-binds often, and a per-instance latch would log per bind. */
        private var loggedOpticsThrow = false

        /** One painter per density for the pills, apart from the bubbles': their params would churn each other's push guard. */
        private val opticsPainters = HashMap<Float, BubbleGlassPainter>()

        /** Main thread only, like every draw; built on the first pill that asks, so an off build never compiles it. */
        fun opticsPainter(density: Float): BubbleGlassPainter =
            opticsPainters.getOrPut(density) { BubbleGlassPainter(density) }

        /** Build the shared blurred copy: halve to a quarter, then a real blur at [radius]; a stretched shrink shows its grid. */
        fun shrinkOf(source: Bitmap, linear: Boolean = false, radius: Int = FROST_RADIUS): Bitmap? = runCatching {
            // Linear light when asked and the image is opaque; a transparent image keeps the display-space path.
            if (linear) BitmapBlur.shrinkBlurLinear(source, FROST_SHRINK, radius, FROST_PASSES)?.let { return@runCatching it }
            val small = BitmapBlur.halveTo(source, FROST_SHRINK)
            val blurred = BitmapBlur.boxBlur(small, radius, FROST_PASSES)
            if (small !== source) small.recycle()
            blurred
        }.onFailure { Log.w("WaThemer.Frost", "shrinkOf failed; stamps draw tint only", it) }.getOrNull()
    }
}
