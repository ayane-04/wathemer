// The wallpaper the glass transmits: shrunk copies shared per bitmap and dim, one record per wallpaper
// ImageView carrying that window's placement, and the PixelCopy snapshot popups use instead.
package com.wathemer.app.hooks.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.widget.ImageView
import com.wathemer.app.glass.BitmapBlur
import com.wathemer.app.glass.FrostDrawable
import com.wathemer.app.glass.WallpaperRecord
import de.robv.android.xposed.XposedBridge
import java.util.WeakHashMap

/** Bumped on every wallpaper geometry change; a record whose epoch lags re-derives its placements. */
@Volatile internal var wallpaperGeometryEpoch = 0

/** Every wallpaper-geometry change makes every placement stale, both consumers at once. */
internal fun markWallpaperGeometryDirty() {
    wallpaperGeometryEpoch++
}

/** On an Activity's decor: its wallpaper ImageView, set at inject; a decor holding its own descendant leaks nothing. */
internal val wallpaperImageTag = tagKey("wathemer-wallpaper-image")

/** On the ImageView: its dim sibling, so a per-draw read never walks the window. */
internal val wallpaperDimTag = tagKey("wathemer-wallpaper-dim")

/** On the ImageView: the WallpaperRecord built for its current bitmap and dim. */
internal val wallpaperRecordTag = tagKey("wathemer-wallpaper-record")

/** On the ImageView: its WallpaperLook; the glass reads only whether it is the global wallpaper. */
internal val wallpaperLookTag = tagKey("wathemer-wallpaper-look")

/** On a decor that carries no wallpaper: a cached miss, so a wallpaper-less window is never walked per draw. */
private val wallpaperMissTag = tagKey("wathemer-wallpaper-miss")

/** The panes' blur as a sigma in screen px, set at install; 0 keeps the copies at their fixed radius. */
@Volatile internal var frostSigmaScreen = 0f

/** The popup snapshot's box radius; derived beside the sigma, or the fixed one. */
@Volatile internal var snapRadius = POPUP_SNAP_RADIUS

/** The info header's reading: a menu's backdrop is blurred deeper than the wallpaper copy. */
private const val SNAP_BLUR_BOOST = 1.8f

/** At install: hand the copies the panes' blur, or 0 to keep them as they were. */
internal fun configureCopyBlur(sigmaScreen: Float) {
    frostSigmaScreen = sigmaScreen
    snapRadius = if (sigmaScreen > 0f) boxRadiusFor(sigmaScreen * SNAP_BLUR_BOOST / POPUP_SNAP_SHRINK) else POPUP_SNAP_RADIUS
}

/** Three box passes whose variance matches a Gaussian of [sigmaCopy] texels; floored where a stretch would show its grid. */
internal fun boxRadiusFor(sigmaCopy: Float): Int {
    val r = (Math.sqrt(4.0 * sigmaCopy * sigmaCopy + 1.0) - 1.0) / 2.0
    return Math.round(r).toInt().coerceIn(2, 24)
}

private val matrixVals = FloatArray(9)

/** Source px to screen px: the view's own matrix once laid out, else the centre-crop ratio it will apply. */
private fun cropScaleOf(img: ImageView, src: Bitmap): Float {
    if (img.width > 0) {
        img.imageMatrix.getValues(matrixVals)
        val s = matrixVals[Matrix.MSCALE_X]
        if (s > 0.01f) return s
    }
    val dm = img.resources.displayMetrics
    return maxOf(dm.widthPixels / src.width.toFloat(), dm.heightPixels / src.height.toFloat()).coerceAtLeast(0.01f)
}

/** The shrunk copies for one source bitmap at one dim; the pair is the identity, not the bitmap alone. */
private class Shrunk(val dimAlpha: Int, val bubble: Bitmap?, val bubbleFolded: Boolean, val selection: Bitmap?)

/** Shared per source bitmap, weakly. A value never references the source, so an entry dies with its bitmap. */
private val shrunkRegistry = WeakHashMap<Bitmap, ArrayList<Shrunk>>()

/** The owning Activity's decor, where the wallpaper lives; a popup or dialog root never holds it. */
internal fun wallpaperHostOf(v: View): View = activityOf(v)?.window?.decorView ?: v.rootView

/** The wallpaper ImageView behind [host]'s window, else home's; never a tree walk on a decor already known to have none. */
internal fun wallpaperImageOf(host: View): ImageView? {
    val decor = wallpaperHostOf(host)
    imageOn(decor)?.let { return it }
    // A window with no wallpaper of its own, such as a dialog raised from a non-Activity context, shows home's.
    val home = contentRef?.get()?.rootView ?: return null
    return if (home !== decor) imageOn(home) else null
}

private fun imageOn(decor: View): ImageView? {
    val tagged = decor.getTag(wallpaperImageTag) as? ImageView
    if (tagged != null && tagged.parent != null) return tagged
    if (decor.getTag(wallpaperMissTag) != null) return null
    // One walk per decor: an inject that predates the tag is repaired here, a real miss is remembered.
    val found = decor.findViewWithTag<View>("wt_wallpaper") as? ImageView
    if (found != null) decor.setTag(wallpaperImageTag, found) else decor.setTag(wallpaperMissTag, true)
    return found
}

/** Called at inject and at a swap: the tags the draw path reads, written off the draw path. */
internal fun noteWallpaperViews(decor: View, image: ImageView, dim: View?) {
    decor.setTag(wallpaperImageTag, image)
    decor.setTag(wallpaperMissTag, null)
    image.setTag(wallpaperDimTag, dim)
    image.setTag(wallpaperRecordTag, null)
}

private fun dimAlphaOf(img: ImageView): Int {
    val dim = img.getTag(wallpaperDimTag) as? View ?: return 0
    return dimAlpha255(dim)
}

/** The dim view's alpha, 0 to 255, read from its colour: the view itself stays opaque so it is not a layer. */
internal fun dimAlpha255(dim: View): Int {
    if (dim.visibility != View.VISIBLE) return 0
    val a = (dim.background as? ColorDrawable)?.alpha ?: 255
    return if (dim.alpha < 1f) (a * dim.alpha.coerceIn(0f, 1f)).toInt() else a
}

/** This window's record, rebuilt when the ImageView's bitmap or dim changed, placements refreshed when the geometry did. */
internal fun wallpaperRecordOf(host: View): WallpaperRecord? {
    val img = wallpaperImageOf(host) ?: return null
    val src = (img.drawable as? BitmapDrawable)?.bitmap ?: return null
    val dimAlpha = dimAlphaOf(img)
    var rec = img.getTag(wallpaperRecordTag) as? WallpaperRecord
    if (rec == null || rec.src !== src || rec.dimAlpha != dimAlpha) {
        rec = buildRecord(src, dimAlpha, cropScaleOf(img, src)) ?: return null
        img.setTag(wallpaperRecordTag, rec)
    }
    if (rec.placementEpoch != wallpaperGeometryEpoch && img.width > 0) {
        rec.bubble?.let { rec.bubblePlacement = wallpaperPlacement(img, src, it) }
        rec.selection?.let { rec.selectionPlacement = wallpaperPlacement(img, src, it) }
        rec.srcPlacement = wallpaperPlacement(img, src, src)
        rec.placementEpoch = wallpaperGeometryEpoch
        logOnce("wallpaper placement derived for ${src.width}x${src.height} at dim $dimAlpha: iv=${img.width}x${img.height}")
    }
    return rec
}

private fun buildRecord(src: Bitmap, dimAlpha: Int, cropScale: Float): WallpaperRecord? {
    val shared = shrunkRegistry[src]?.firstOrNull { it.dimAlpha == dimAlpha }
        ?: buildShrunk(src, dimAlpha, cropScale) ?: return null
    return WallpaperRecord(src, dimAlpha, shared.bubble, shared.bubbleFolded, shared.selection)
}

/** Deep and light copies; the dim is folded so refraction matches the screen. Guarded: a scale to the same size hands back the source. */
private fun buildShrunk(src: Bitmap, dimAlpha: Int, cropScale: Float): Shrunk? {
    // One copy texel is FROST_SHRINK source px, and each source px is cropScale screen px; the sigma is the panes' in screen px.
    val radius = if (frostSigmaScreen > 0f) {
        boxRadiusFor(frostSigmaScreen / (FrostDrawable.FROST_SHRINK * cropScale))
    } else {
        FrostDrawable.FROST_RADIUS
    }
    val bubble = FrostDrawable.shrinkOf(src, LINEAR_COPY, radius)
    // The selected row's copy stays legible: half size with one light pass, not the frost's three.
    val selection = runCatching {
        val small = BitmapBlur.halveTo(src, SELECT_SHRINK)
        val soft = BitmapBlur.boxBlur(small, SELECT_RADIUS, 1)
        if (small !== src) small.recycle()
        soft
    }.getOrNull()
    if (bubble == null && selection == null) return null
    var folded = false
    if (dimAlpha > 0) {
        if (bubble != null && bubble !== src && bubble.isMutable) {
            runCatching { Canvas(bubble).drawColor(dimAlpha shl 24, PorterDuff.Mode.SRC_OVER); folded = true }
        }
        if (selection != null && selection !== src && selection.isMutable) {
            runCatching { Canvas(selection).drawColor(dimAlpha shl 24, PorterDuff.Mode.SRC_OVER) }
        }
    }
    val shrunk = Shrunk(dimAlpha, bubble, folded, selection)
    // Never register a copy that is the source itself: the registry's value would pin its own weak key.
    if (bubble !== src && selection !== src) {
        val list = shrunkRegistry.getOrPut(src) { ArrayList(2) }
        if (list.size >= 2) list.removeAt(0)
        list.add(shrunk)
    }
    XposedBridge.log(
        "[$TAG] wallpaper copies built for ${src.width}x${src.height}: bubble=${bubble?.width}x${bubble?.height} " +
            "selection=${selection?.width}x${selection?.height} dim=$dimAlpha folded=$folded linear=$LINEAR_COPY " +
            "radius=$radius crop=$cropScale",
    )
    return shrunk
}

/** Shrunk to original to imageMatrix to screen; one definition so the first derive and every re-derive cannot drift. */
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

/** The deeply blurred wallpaper behind [host]'s window, for bubbles, pills and cards. */
internal fun bubbleBackdrop(host: View): Bitmap? = wallpaperRecordOf(host)?.bubble

internal fun bubblePlacement(host: View): Matrix? = wallpaperRecordOf(host)?.bubblePlacement

/** The dim a bubble still paints itself; 0 once it is inside the bitmap, which is the normal case. */
internal fun bubbleDimOf(host: View): Float = wallpaperRecordOf(host)?.bubbleDim ?: 0f

/** The lightly blurred copy for a selected row; deliberately not the bubble copy, which is frost by construction. */
internal fun selectionBackdrop(host: View): Bitmap? = wallpaperRecordOf(host)?.selection

internal fun selectionPlacement(host: View): Matrix? = wallpaperRecordOf(host)?.selectionPlacement

/** The selected row's copy: half size and one light box pass, so shapes stay legible under text. */
private const val SELECT_SHRINK = 2
private const val SELECT_RADIUS = 2

/** Popups sample the composited screen. A View.draw copy would miss every RenderNode effect. */
private const val POPUP_SNAP_COPY = 4

/** Halved once more to an eighth, then three box passes: the screen behind a menu, blurred deeper than the wallpaper copy. */
private const val POPUP_SNAP_SHRINK = 8
private const val POPUP_SNAP_RADIUS = 2

/** A real blur of the snapshot at an eighth of the screen; a stretched shrink shows its grid. */
private fun smoothBlur(src: Bitmap): Bitmap {
    val radius = snapRadius
    if (LINEAR_COPY) BitmapBlur.shrinkBlurLinear(src, POPUP_SNAP_SHRINK / POPUP_SNAP_COPY, radius, 3)?.let { return it }
    val small = BitmapBlur.halveTo(src, POPUP_SNAP_SHRINK / POPUP_SNAP_COPY)
    val soft = BitmapBlur.boxBlur(small, radius, 3)
    if (small !== src) small.recycle()
    return soft
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
                    val soft = runCatching { smoothBlur(dst) }.getOrDefault(dst)
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
