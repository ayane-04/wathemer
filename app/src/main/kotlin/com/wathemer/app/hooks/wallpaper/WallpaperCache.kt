// Process-lifetime bitmap cache keyed on path alone; blur is a RenderEffect, never baked in, so keep it out of the key.
// Stays synchronized: a config-change replay and a tab swap can both fire inject paths.
package com.wathemer.app.hooks.wallpaper

import android.graphics.Bitmap

object WallpaperCache {

    private var bitmap: Bitmap? = null
    private var key: String? = null

    /** Cached bitmap for [path], else null; [blurRadius] is deliberately ignored, see the header. */
    @Synchronized
    @Suppress("UNUSED_PARAMETER")
    fun get(path: String, blurRadius: Int): Bitmap? {
        val current = bitmap
        return if (key == path && current != null && !current.isRecycled) current else null
    }

    /** Replaces the cache. Never recycle the old bitmap: live BitmapDrawables still hold it and recycling crashes WA; GC frees it. */
    @Synchronized
    fun put(path: String, blurRadius: Int, newBitmap: Bitmap) {
        bitmap = newBitmap
        key = path
    }
}
