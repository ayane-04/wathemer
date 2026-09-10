// Process-lifetime bitmap cache keyed on path alone; blur is a RenderEffect, never baked in, so keep it out of the key.
// Synchronized and load-bearing: the prefetch thread fills it and the main thread reads it.
package com.wathemer.app.hooks.wallpaper

import android.graphics.Bitmap

object WallpaperCache {

    /** Chat wallpapers kept beside the pinned global; each is a full-screen bitmap, so the count stays small. */
    private const val CHAT_SLOTS = 2

    private var pinnedKey: String? = null
    private var pinned: Bitmap? = null

    /** Access order, so alternating between three chats evicts the one seen longest ago. */
    private val recent = LinkedHashMap<String, Bitmap>(4, 0.75f, true)

    /** Cached bitmap for [path], else null; [blurRadius] is deliberately ignored, see the header. */
    @Synchronized
    @Suppress("UNUSED_PARAMETER")
    fun get(path: String, blurRadius: Int): Bitmap? {
        if (pinnedKey == path) return pinned?.takeIf { !it.isRecycled }
        return recent[path]?.takeIf { !it.isRecycled }
    }

    /** Stores a bitmap; [pinned] marks the global wallpaper, which no eviction may drop. Never recycles: live drawables still draw the old one. */
    @Synchronized
    @Suppress("UNUSED_PARAMETER")
    fun put(path: String, blurRadius: Int, newBitmap: Bitmap, pinned: Boolean = false) {
        if (pinned) {
            pinnedKey = path
            this.pinned = newBitmap
            recent.remove(path)
            return
        }
        recent[path] = newBitmap
        while (recent.size > CHAT_SLOTS) {
            val eldest = recent.keys.iterator()
            eldest.next()
            eldest.remove()
        }
    }
}
