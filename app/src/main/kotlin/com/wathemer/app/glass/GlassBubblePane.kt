package com.wathemer.app.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver

/** A growable, allocation-free rect list reused every frame; 20 RectF per frame is 1200 a second. */
class RectList {
    private val items = ArrayList<RectF>()
    private var flags = IntArray(8)
    var size = 0
        private set

    fun clear() { size = 0 }

    fun add(l: Float, t: Float, r: Float, b: Float, flag: Int = 0) {
        if (size == items.size) items.add(RectF())
        if (size >= flags.size) flags = flags.copyOf(flags.size * 2)
        flags[size] = flag
        items[size++].set(l, t, r, b)
    }

    operator fun get(i: Int): RectF = items[i]

    fun flagAt(i: Int): Int = flags[i]

    fun contentEquals(other: RectList): Boolean {
        if (size != other.size) return false
        for (i in 0 until size) {
            if (items[i] != other.items[i] || flags[i] != other.flags[i]) return false
        }
        return true
    }

    fun copyFrom(other: RectList) {
        clear()
        for (i in 0 until other.size) {
            val r = other[i]
            add(r.left, r.top, r.right, r.bottom, other.flags[i])
        }
    }
}

/**
 * All the chat bubbles' glass, one view behind the message list. ListView scrolling repositions
 * row RenderNodes without re-recording, so nothing inside a row may depend on its screen position.
 */
class GlassBubblePane(context: Context) : View(context) {

    companion object {
        /** Rect flags: a grouped continuation flattens its top corner on the sender's side. */
        const val FLAG_EXT = 1
        const val FLAG_OUTGOING = 2
    }

    var params: GlassParams = GlassParams(context.resources.displayMetrics.density)
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

    /** Fired on resize, the cheapest signal that the backdrop mapping went stale (rotation, fold). */
    var onGeometryChanged: (() -> Unit)? = null

    private val painter = BubbleGlassPainter(context.resources.displayMetrics.density)
    private val shown = RectList()
    private val pending = RectList()
    private val at = IntArray(2)
    private val local = RectF()
    private var lastBitmap: Bitmap? = null
    private var lastTint = 0
    private var loggedFirst = false
    private var loggedEmpty = false
    private var loggedThrow = false
    private var loggedCollectThrow = false

    init {
        // The framework skips onDraw for a background-less plain View; be explicit.
        setWillNotDraw(false)
    }

    /** Refresh in pre-draw: onDraw only runs when already dirty, and a frame late is 180px of fling. */
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
                    invalidate()
                }
            }
        } catch (t: Throwable) {
            if (!loggedCollectThrow) {
                loggedCollectThrow = true
                Log.w("WaThemer.Bubble", "pre-draw collect threw, frame skipped", t)
            }
        }
        true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) onGeometryChanged?.invoke()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // The observer belongs to the window; a listener left behind would keep this view alive.
        viewTreeObserver.removeOnPreDrawListener(preDraw)
    }

    override fun onDraw(canvas: Canvas) {
        if (!params.enabled || shown.size == 0) {
            // Log once: "never asked to draw" and "asked with nothing" look identical from outside.
            if (!loggedEmpty) {
                loggedEmpty = true
                Log.i(
                    "WaThemer.Bubble",
                    "pane drew with NOTHING: enabled=${params.enabled} rects=${shown.size}",
                )
            }
            return
        }
        val bmp: Bitmap?
        val place: Matrix?
        val t: Int
        val d: Float
        // Guarded like painter.paint below: these lambdas walk live WhatsApp views on the draw path.
        try {
            bmp = backdrop()
            place = if (bmp != null) placement() else null
            t = tint()
            d = dim()
        } catch (e: Throwable) {
            if (!loggedThrow) {
                loggedThrow = true
                Log.w("WaThemer.Bubble", "backdrop lambdas threw, frame skipped", e)
            }
            return
        }
        if (!loggedFirst) {
            loggedFirst = true
            Log.i(
                "WaThemer.Bubble",
                "pane drawing ${shown.size} bubbles, size=${width}x$height " +
                    "backdrop=${bmp?.width}x${bmp?.height} place=${place != null}",
            )
        }
        getLocationOnScreen(at)
        for (i in 0 until shown.size) {
            val s = shown[i]
            local.set(s.left - at[0], s.top - at[1], s.right - at[0], s.bottom - at[1])
            // A fling reports rows still outside this view; shading them is pure cost.
            if (local.bottom <= 0f || local.top >= height || local.right <= 0f || local.left >= width) {
                continue
            }
            var radii: FloatArray? = null
            val flag = shown.flagAt(i)
            if (flatRadiusPx > 0f && (flag and FLAG_EXT) != 0) {
                val rr = params.cornerRadius
                radiiBuf[0] = if (flag and FLAG_OUTGOING == 0) flatRadiusPx else rr
                radiiBuf[1] = if (flag and FLAG_OUTGOING != 0) flatRadiusPx else rr
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
                )
            }.onFailure {
                if (!loggedThrow) {
                    loggedThrow = true
                    Log.w("WaThemer.Bubble", "painter threw, bubble skipped", it)
                }
            }
        }
    }
}
