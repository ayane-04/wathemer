// Wallpaper resolver: direct path, then cached copy in filesDir, then MediaStore; first hit wins, null means skip.
// If the direct path stops being readable the cached copy is served forever; delete files/wt_wallpaper.png to escape.
package com.wathemer.app.hooks.wallpaper

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.provider.MediaStore
import android.util.Log
import com.wathemer.app.settings.prefs.Prefs
import java.io.File
import java.io.FileOutputStream

object WallpaperResolver {

    private const val TAG = "WaThemer.WPResolver"
    private const val CACHE_FILENAME = "wt_wallpaper.png"

    /** Returns the wallpaper file, or null meaning skip painting this Activity. */
    fun resolve(context: Context, prefs: SharedPreferences): File? {
        val storedPath = prefs.getString(Prefs.KEY_WALLPAPER_PATH, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        // Strategy 1: direct File.
        val direct = File(storedPath)
        if (direct.canRead()) {
            cacheIfStale(direct, context)
            return direct
        }

        // Strategy 2: mtime-cached copy in WA's private filesDir.
        val cache = cacheFile(context)
        if (cache.canRead()) return cache

        // Strategy 3: MediaStore lookup + copy to private cache.
        return copyViaMediaStore(context, storedPath, cache)
    }

    private fun cacheFile(context: Context): File = File(context.filesDir, CACHE_FILENAME)

    /** Mtime-checked copy; no-op when the cache is at least as new and the same size. */
    private fun cacheIfStale(source: File, context: Context) {
        val cache = cacheFile(context)
        try {
            if (cache.exists() &&
                cache.lastModified() >= source.lastModified() &&
                cache.length() == source.length()
            ) {
                return
            }
            source.inputStream().use { input ->
                FileOutputStream(cache).use { out -> input.copyTo(out) }
            }
            cache.setLastModified(source.lastModified())
        } catch (t: Throwable) {
            Log.w(TAG, "cacheIfStale failed: ${t.message}")
        }
    }

    /** Cross-UID route: ContentResolver reads MediaStore-indexed files even when WA's UID cannot read the path. */
    private fun copyViaMediaStore(context: Context, absolutePath: String, dest: File): File? {
        val resolver: ContentResolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DATA} = ?"
        val args = arrayOf(absolutePath)
        return try {
            resolver.query(collection, projection, selection, args, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val id = cursor.getLong(0)
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                    .appendPath(id.toString()).build()
                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { out -> input.copyTo(out) }
                }
                dest
            }
        } catch (t: Throwable) {
            Log.w(TAG, "copyViaMediaStore failed for $absolutePath: ${t.message}")
            null
        }
    }
}
