// The wallpaper the glass transmits: one shrunk bitmap, the matrix that maps it to the screen, and
// the PixelCopy snapshot popups use instead. The bitmap latches; the placement never does.
package com.wathemer.app.hooks.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.widget.ImageView
import com.wathemer.app.glass.FrostDrawable
import de.robv.android.xposed.XposedBridge

private var bubbleBlurred: Bitmap? = null

private var bubbleBlurredResolved = false

internal var bubbleDim = 0f

internal var bubbleDimFolded = false

internal var bubbleWpPlacement: Matrix? = null

/** Set when any wallpaper-transmitting surface resizes; costs a matrix rebuild, not a bitmap rebuild. */
@Volatile internal var bubbleWpDirty = true

/** Set only through [markWallpaperGeometryDirty], never alone; cleared only where the selection placement is written. */
@Volatile internal var selectionWpDirty = true

/** Every wallpaper-geometry change makes both cached placements stale. */
internal fun markWallpaperGeometryDirty() {
    bubbleWpDirty = true
    selectionWpDirty = true
}

/** Shrunk to original to imageMatrix to screen; one definition so the first resolve and re-derives cannot drift. */
private fun wallpaperPlacement(
    img: ImageView,
    src: Bitmap,
    shrunk: Bitmap,
): Matrix {
    val m = Matrix()
    m.setScale(src.width.toFloat() / shrunk.width, src.height.toFloat() / shrunk.height)
    m.postConcat(img.imageMatrix)
    val at = IntArray(2)
    img.getLocationOnScreen(at)
    m.postTranslate(at[0].toFloat(), at[1].toFloat())
    return m
}

/** Built once, resolved from the live view so the flag cannot latch before any wallpaper view exists. */
internal fun bubbleBackdrop(host: View): Bitmap? {
    // The bitmap latches, the placement does not: a latched matrix kept the old scale after rotation.
    if (bubbleBlurredResolved && !bubbleWpDirty) return bubbleBlurred
    if (bubbleBlurredResolved) {
        // Geometry only: re-derive the mapping from the live ImageView and keep the bitmap.
        val img = host.rootView?.findViewWithTag<View>("wt_wallpaper") as? ImageView
        val shrunk = bubbleBlurred
        val src = (img?.drawable as? BitmapDrawable)?.bitmap
        if (img != null && shrunk != null && src != null && img.width > 0) {
            bubbleWpPlacement = wallpaperPlacement(img, src, shrunk)
            bubbleWpDirty = false
            XposedBridge.log("[$TAG] bubble backdrop placement re-derived after a geometry change")
        }
        return bubbleBlurred
    }
    val root = host.rootView
    val img = root.findViewWithTag<View>("wt_wallpaper") as? ImageView ?: return null
    val src = (img.drawable as? BitmapDrawable)?.bitmap ?: return null
    bubbleDim = (root.findViewWithTag<View>("wt_wallpaper_dim")?.alpha ?: 0f).coerceIn(0f, 1f)
    bubbleBlurred = FrostDrawable.shrinkOf(src)
    bubbleBlurredResolved = bubbleBlurred != null
    // Fold the dim in so refraction matches the screen; guarded, the shrink can hand back WhatsApp's own bitmap.
    bubbleDimFolded = false
    bubbleBlurred?.let { shrunk ->
        if (bubbleDim > 0f && shrunk !== src && shrunk.isMutable) {
            runCatching {
                Canvas(shrunk).drawColor(
                    ((bubbleDim * 255f).toInt().coerceIn(0, 255) shl 24),
                    PorterDuff.Mode.SRC_OVER,
                )
                bubbleDimFolded = true
            }
        }
    }
    // See [wallpaperPlacement] for the three steps this matrix is built from.
    bubbleBlurred?.let { shrunk ->
        val m = wallpaperPlacement(img, src, shrunk)
        bubbleWpPlacement = m
        bubbleWpDirty = false
        XposedBridge.log(
            "[$TAG] bubble backdrop placement: iv=${img.width}x${img.height} " +
                "src=${src.width}x${src.height} shrunk=${shrunk.width}x${shrunk.height} m=$m",
        )
    }
    if (bubbleBlurredResolved) {
        XposedBridge.log(
            "[$TAG] bubble backdrop built ${bubbleBlurred?.width}x${bubbleBlurred?.height} " +
                "dim=$bubbleDim folded=$bubbleDimFolded",
        )
    }
    return bubbleBlurred
}

/* The selected row's own, barely-blurred copy of the wallpaper. See [ROW_SELECT_SHRINK]. */
private var selectionSharp: Bitmap? = null

private var selectionSharpResolved = false

internal var selectionWpPlacement: Matrix? = null

/** Deliberately lighter than bubbleBackdrop so the wallpaper's shapes stay readable; dim folded in with the same guard. */
internal fun selectionBackdrop(host: View): Bitmap? {
    if (selectionSharpResolved && !selectionWpDirty) return selectionSharp
    val img = host.rootView?.findViewWithTag<View>("wt_wallpaper")
        as? ImageView ?: return selectionSharp
    val src = (img.drawable as? BitmapDrawable)?.bitmap
        ?: return selectionSharp
    if (img.width <= 0) return selectionSharp
    if (!selectionSharpResolved) {
        val w = (src.width / ROW_SELECT_SHRINK).coerceAtLeast(1)
        val h = (src.height / ROW_SELECT_SHRINK).coerceAtLeast(1)
        val small = runCatching { Bitmap.createScaledBitmap(src, w, h, true) }
            .getOrNull() ?: return null
        val dim = (host.rootView?.findViewWithTag<View>("wt_wallpaper_dim")?.alpha ?: 0f)
            .coerceIn(0f, 1f)
        if (dim > 0f && small !== src && small.isMutable) {
            runCatching {
                Canvas(small).drawColor(
                    ((dim * 255f).toInt().coerceIn(0, 255) shl 24),
                    PorterDuff.Mode.SRC_OVER,
                )
            }
        }
        selectionSharp = small
        selectionSharpResolved = true
        logOnce("selection backdrop built ${small.width}x${small.height} dim=$dim")
    }
    selectionSharp?.let {
        selectionWpPlacement = wallpaperPlacement(img, src, it)
        // Cleared by the consumer that owns this placement, never by the bubble path.
        selectionWpDirty = false
    }
    return selectionSharp
}

// Copy at 1/4, halve to 1/30, double back to 1/2. Deeper than the wallpaper's 1/20 for sharp text.
// The shader samples NEAREST, so the rebuild size is the block size.
/** Popups sample the composited screen. A View.draw copy would miss every RenderNode effect. */
private const val POPUP_SNAP_COPY = 4

private const val POPUP_SNAP_BLUR = 30

private const val POPUP_SNAP_SMOOTH = 2

/** Progressive halve then double, like FrostDrawable.shrinkOf but sized to the screen. */
private fun smoothBlur(src: Bitmap, blurW: Int, outW: Int): Bitmap {
    var cur = src
    while (cur.width / 2 >= blurW && cur.height / 2 >= 1) {
        val next = Bitmap.createScaledBitmap(cur, cur.width / 2, (cur.height / 2).coerceAtLeast(1), true)
        if (cur !== src) cur.recycle()
        cur = next
    }
    while (cur.width * 2 <= outW) {
        val next = Bitmap.createScaledBitmap(cur, cur.width * 2, cur.height * 2, true)
        if (cur !== src) cur.recycle()
        cur = next
    }
    return cur
}

@Volatile internal var screenSnap: Bitmap? = null

internal val screenSnapPlace = Matrix()

@Volatile internal var screenSnapPending = false

private val snapLoc = IntArray(2)

/** Once per popup open. The listener is on the main thread, so no draw sees a half-swapped pair. */
internal fun requestScreenSnap(anchor: View) {
    if (screenSnapPending) return
    val win = activityOf(anchor)?.window ?: return
    val decor = win.decorView
    if (decor.width <= 0 || decor.height <= 0) return
    val w = (decor.width / POPUP_SNAP_COPY).coerceAtLeast(1)
    val h = (decor.height / POPUP_SNAP_COPY).coerceAtLeast(1)
    val dst = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
    screenSnapPending = true
    runCatching {
        PixelCopy.request(
            win, dst,
            { res ->
                screenSnapPending = false
                if (res == PixelCopy.SUCCESS) {
                    decor.getLocationOnScreen(snapLoc)
                    val soft = runCatching {
                        smoothBlur(
                            dst,
                            (decor.width / POPUP_SNAP_BLUR).coerceAtLeast(1),
                            (decor.width / POPUP_SNAP_SMOOTH).coerceAtLeast(1),
                        )
                    }.getOrDefault(dst)
                    // Bitmap to screen, off the output bitmap; the blur changed its size.
                    screenSnapPlace.setScale(
                        decor.width.toFloat() / soft.width,
                        decor.height.toFloat() / soft.height,
                    )
                    screenSnapPlace.postTranslate(snapLoc[0].toFloat(), snapLoc[1].toFloat())
                    screenSnap = soft
                } else {
                    logOnce("popup snapshot: PixelCopy returned $res, staying on the wallpaper")
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }.onFailure {
        screenSnapPending = false
        logOnce("popup snapshot threw, staying on the wallpaper: $it")
    }
}
