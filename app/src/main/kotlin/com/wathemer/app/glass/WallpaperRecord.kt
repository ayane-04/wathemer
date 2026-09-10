// What one window's wallpaper looks like to the glass and to the wallpaper hook; plain data, no view references.
package com.wathemer.app.glass

import android.graphics.Bitmap
import android.graphics.Matrix

/** One window's wallpaper choice: the chat entry's stamp, or 0 for the global wallpaper. */
class WallpaperLook(val stamp: Int, val dim: Int, val blur: Int) {
    val global: Boolean get() = stamp == 0

    override fun equals(other: Any?): Boolean =
        other is WallpaperLook && other.stamp == stamp && other.dim == dim && other.blur == blur

    override fun hashCode(): Int = (stamp * 31 + dim) * 31 + blur

    override fun toString(): String = "stamp=$stamp dim=$dim blur=$blur"
}

/** The pre-blurred copies one wallpaper ImageView hands the glass, plus that view's placement; holds the bitmap, never the view. */
class WallpaperRecord(
    val src: Bitmap,
    /** The dim the copies were built against, in alpha units 0..255. */
    val dimAlpha: Int,
    /** Deeply blurred copy for bubbles and pills; the dim is folded in when [bubbleDimFolded]. */
    val bubble: Bitmap?,
    val bubbleDimFolded: Boolean,
    /** Lightly blurred copy for the selected row; the dim is always folded in. */
    val selection: Bitmap?,
) {
    var bubblePlacement: Matrix? = null
    var selectionPlacement: Matrix? = null
    /** The geometry epoch both placements were derived at; a stale epoch means re-derive both. */
    var placementEpoch: Int = -1

    /** The dim a bubble must still paint itself: nothing once it is inside the bitmap. */
    val bubbleDim: Float get() = if (bubbleDimFolded) 0f else dimAlpha / 255f
}
