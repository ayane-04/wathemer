// Bitmap decode utility, with a power-of-2 inSampleSize.
// Blur never lands here: it is a RenderEffect on the ImageView and not in WallpaperCache's key, so a blur change hits the cache.
package com.wathemer.app.hooks.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object BitmapDecoder {

    /** Decodes at the smallest power-of-2 sample that keeps both dimensions at least the target; null on failure. */
    fun decodeScaled(file: File, targetW: Int, targetH: Int): Bitmap? {
        if (!file.canRead()) return null
        val path = file.absolutePath

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while ((bounds.outWidth / (sample * 2)) >= targetW && (bounds.outHeight / (sample * 2)) >= targetH) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
    }
}
