package com.wathemer.app.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View

/** WhatsApp's bubble background, replaced: reports (row, bounds) to feed the pane and paints only when no pane exists, because anything drawn from a row freezes the moment the list scrolls. */
class GlassBubbleDrawable(
    private val params: GlassParams,
    /** Pulled, not pushed: the tint resolves after WhatsApp asks for this drawable. */
    private val tint: () -> Int,
    private val density: Float,
    private val rimColor: Int,
    private val rimWidth: Float,
    /** The row's window wallpaper record: the bitmap, its screen matrix and the dim still to paint, read per draw. */
    private val source: (View) -> WallpaperRecord?,
    /** The row being drawn: the only live route to a screen position; callback and canvas matrix are dead. */
    private val rowProvider: () -> View?,
    /** Hands the row, its bubble's raw bounds and the grouping flag to `GlassHook`, which is what feeds the pane. */
    private val report: (View, Rect, Int) -> Unit,
    /** True once a pane is drawing the bubbles, in which case this draws nothing. */
    private val paneActive: () -> Boolean,
    /** GlassBubblePane.FLAG_* bits; fresh per factory call, so it is per-message state the pane cannot hold. */
    private val flag: Int = 0,
    /** Flattened-corner radius px for a grouped continuation; 0 turns merging off. */
    private val flatRadiusPx: Float = 0f,
) : Drawable() {

    companion object {
        /** Process-wide latch: the factory builds a fresh drawable per bind, so a per-instance latch logs per message. */
        private var loggedThrow = false

        /** Main thread only, like every draw; the pane already proves one painter serves every bubble. */
        private val painters = HashMap<Float, BubbleGlassPainter>()

        fun sharedPainter(density: Float): BubbleGlassPainter =
            painters.getOrPut(density) { BubbleGlassPainter(density) }

        /** A bubble's rect inside its row, both ends clamped and inset so overhanging grouped bubbles do not draw two rims through each other; a collapsed clamp restores only the raw bottom. */
        fun clamp(b: Rect, rowHeight: Int, insetX: Float, insetY: Float, out: RectF) {
            val top = maxOf(b.top, 0).toFloat() + insetY
            var bot = minOf(
                b.bottom.toFloat(),
                if (rowHeight > 0) rowHeight.toFloat() else Float.MAX_VALUE,
            ) - insetY
            if (bot - top < 4f) bot = b.bottom.toFloat()
            out.set(b.left.toFloat() + insetX, top, b.right.toFloat() - insetX, bot)
        }
    }

    private val clip = RectF()
    private val loc = IntArray(2)
    private val reported = Rect()
    private val radiiBuf = FloatArray(4)
    /** Shared per density and built on first paint: one painter per drawable was two shader compiles per bind. */
    private val painter: BubbleGlassPainter get() = sharedPainter(density)

    private val insetX = rimWidth
    private val insetY = 2f * density

    override fun draw(canvas: Canvas) {
        // Guarded whole: this runs inside WhatsApp's draw pass, and anything that escapes takes the traversal down.
        try {
            drawInner(canvas)
        } catch (t: Throwable) {
            if (!loggedThrow) {
                loggedThrow = true
                Log.w("WaThemer.Bubble", "draw threw, bubble skipped", t)
            }
        }
    }

    private fun drawInner(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return

        val row = rowProvider()
        // Report first, unconditionally: the only moment the bubble's rect inside its row is observable.
        if (row != null) {
            reported.set(b)
            report(row, reported, flag)
        }
        // A live pane already draws these bubbles; painting here too would double the material.
        // Gate on row != null as well: paneActive is process-wide, and Message info rows lost their glass.
        if (row != null && paneActive()) return

        clamp(b, row?.height ?: 0, insetX, insetY, clip)
        if (clip.width() <= 0f || clip.height() <= 0f) return

        // Resolve the backdrop only beside a row's true screen position, or it is built and never drawn.
        // getLocationOnScreen, never InWindow: those agree only within one window (the frost sampling bug).
        var rec: WallpaperRecord? = null
        var screenX = 0f
        var screenY = 0f
        if (row != null) {
            row.getLocationOnScreen(loc)
            screenX = loc[0] + clip.left
            screenY = loc[1] + clip.top
            rec = source(row)
        }
        val bmp: Bitmap? = rec?.bubble
        var radii: FloatArray? = null
        if (flatRadiusPx > 0f && (flag and GlassBubblePane.FLAG_EXT) != 0) {
            val rr = params.cornerRadius
            radiiBuf[0] = if (flag and GlassBubblePane.FLAG_OUTGOING == 0) flatRadiusPx else rr
            radiiBuf[1] = if (flag and GlassBubblePane.FLAG_OUTGOING != 0) flatRadiusPx else rr
            radiiBuf[2] = rr
            radiiBuf[3] = rr
            radii = radiiBuf
        }
        painter.paint(
            canvas, clip, params, tint(), bmp,
            if (bmp != null) rec.bubblePlacement else null,
            screenX = screenX, screenY = screenY,
            dim = rec?.bubbleDim ?: 0f, rimColor = rimColor, rimWidth = rimWidth,
            radii = radii,
            sharp = rec?.src, sharpPlace = rec?.srcPlacement,
            sharpDim = if (rec != null && rec.bubbleDimFolded) rec.dimAlpha / 255f else 0f,
        )
    }

    /** Deliberate no-op: BubbleColors' SRC_IN filter would flatten the glass into a solid colour. */
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    override fun setAlpha(alpha: Int) = Unit

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
