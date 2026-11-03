// Store pictures: fetched once by checksum, decoded off the main thread at the size they are shown, two at a time.
package com.wathemer.app.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

object StoreImages {

    private const val MAX_CACHE_BYTES = 16 * 1024 * 1024

    private val http by lazy { StoreConfig.http() }

    // Counted in bytes: by entries, sixteen full screenshots would count the same as sixteen tiles.
    private val cache = object : LruCache<String, Bitmap>(minOf(Runtime.getRuntime().maxMemory() / 16, MAX_CACHE_BYTES.toLong()).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    // A semaphore, not limitedParallelism: that is still experimental in the coroutines this app resolves.
    private val decoding = Semaphore(2)

    fun cached(asset: StoreAsset, width: Int): Bitmap? = cache.get(key(asset, width))

    /** [asset] at about [width] x [height] pixels, or null when it cannot be fetched or read. */
    suspend fun load(context: Context, asset: StoreAsset, width: Int, height: Int): Bitmap? {
        cache.get(key(asset, width))?.let { return it }
        val app = context.applicationContext
        return decoding.withPermit {
            withContext(Dispatchers.IO) {
                cache.get(key(asset, width)) ?: runCatching {
                    StoreRepository.siteFile(app, StoreConfig.TARGET, http, asset)?.let { decode(it.path, width, height) }
                }.getOrNull()?.also { cache.put(key(asset, width), it) }
            }
        }
    }

    private fun key(asset: StoreAsset, width: Int): String = "${asset.sha256}/$width"

    /** The largest power-of-two step down that still covers the size it is shown at. */
    private fun decode(path: String, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Bounded, so a slot measured at zero cannot double the step forever.
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        var sample = 1
        while (sample < 64 && bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
