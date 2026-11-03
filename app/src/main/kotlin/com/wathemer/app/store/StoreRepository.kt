// The catalogue: shown from the cache at once, refreshed from the site, accepted only when signed, current and well formed.
package com.wathemer.app.store

import android.content.Context
import android.util.Log
import java.io.File

object StoreRepository {

    private const val TAG = "WaThemer.Store"

    /** Pages caches the list this long, so checking sooner would only fetch the same bytes. */
    const val FRESH_MS = 10L * 60 * 1000

    const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

    private const val MAX_CATALOGUE = StoreIndex.MAX_BYTES + StoreSignature.MAX_HEADER + 1

    class Loaded(val index: StoreIndex, val checkedAt: Long)

    sealed interface Refreshed {
        class Ok(val loaded: Loaded) : Refreshed
        data object NewerSchema : Refreshed
        data object NotAvailable : Refreshed
        data class Busy(val retryAfterSeconds: Long?) : Refreshed
        data class Unreachable(val reason: String) : Refreshed
        data class Unverified(val reason: String) : Refreshed
    }

    private sealed interface Verdict {
        class Accepted(val index: StoreIndex, val keyId: String) : Verdict
        data object Newer : Verdict
        data class Rejected(val reason: String) : Verdict
    }

    private sealed interface Attempt {
        class Done(val result: Refreshed) : Attempt
        class Retry(val reason: String) : Attempt
    }

    /** The last catalogue that passed, held again to what the phone remembers; null when there is none. */
    fun cached(context: Context, target: StoreTarget): Loaded? {
        val cached = StoreFiles.readIndex(context, target.storeId) ?: return null
        val verdict = verify(cached.blob, target, StoreFiles.readState(context))
        if (verdict is Verdict.Accepted) return Loaded(verdict.index, cached.checkedAt)
        StoreFiles.dropIndex(context, target.storeId)
        return null
    }

    /** A clock moved backwards counts as stale. */
    fun isFresh(loaded: Loaded, now: Long): Boolean = now >= loaded.checkedAt && now - loaded.checkedAt < FRESH_MS

    fun refresh(context: Context, target: StoreTarget, http: StoreHttp, now: Long): Refreshed {
        val address = target.site + StoreConfig.CATALOGUE
        val first = attempt(context, target, http, address, StoreFiles.readIndex(context, target.storeId), now)
        if (first is Attempt.Done) return first.result
        Log.w(TAG, "catalogue refused: ${(first as Attempt.Retry).reason}; asking past the cache")
        // A CDN edge can serve an older copy for a while; one request past its cache settles which is current.
        return when (val second = attempt(context, target, http, "$address?t=$now", null, now)) {
            is Attempt.Done -> second.result
            is Attempt.Retry -> Refreshed.Unverified(second.reason)
        }
    }

    /** [asset]'s verified bytes on disk, fetched once and kept under its checksum; null when they cannot be had. */
    fun siteFile(context: Context, target: StoreTarget, http: StoreHttp, asset: StoreAsset): File? {
        val file = StoreFiles.imageFile(context, asset.sha256)
        if (file.isFile) return file
        val got = http.site(target.site + asset.path, MAX_IMAGE_BYTES) as? StoreHttp.Got.Body ?: return null
        if (sha256Hex(got.bytes) != asset.sha256) return null
        return file.takeIf { StoreFiles.writeAtomically(it, got.bytes) }
    }

    private fun attempt(
        context: Context,
        target: StoreTarget,
        http: StoreHttp,
        address: String,
        cached: StoreFiles.CachedIndex?,
        now: Long,
    ): Attempt {
        val state = StoreFiles.readState(context)
        return when (val got = http.site(address, MAX_CATALOGUE, cached?.etag)) {
            is StoreHttp.Got.Body -> when (val v = verify(got.bytes, target, state)) {
                is Verdict.Accepted -> {
                    accept(context, target, v.index)
                    if (!StoreFiles.writeIndex(context, target.storeId, got.bytes, got.etag, now)) Log.w(TAG, "catalogue not cached")
                    Log.i(TAG, "catalogue ${target.storeId} serial ${v.index.serial}: ${v.index.themes.size} theme(s), key ${v.keyId}")
                    Attempt.Done(Refreshed.Ok(Loaded(v.index, now)))
                }
                Verdict.Newer -> Attempt.Done(Refreshed.NewerSchema)
                is Verdict.Rejected -> Attempt.Retry(v.reason)
            }
            StoreHttp.Got.NotModified -> {
                val v = cached?.let { verify(it.blob, target, state) }
                if (v is Verdict.Accepted) {
                    accept(context, target, v.index)
                    StoreFiles.touchIndex(context, target.storeId, now)
                    Attempt.Done(Refreshed.Ok(Loaded(v.index, now)))
                } else {
                    Attempt.Retry("the cached list no longer passes")
                }
            }
            StoreHttp.Got.Missing -> Attempt.Done(Refreshed.NotAvailable)
            is StoreHttp.Got.Busy -> Attempt.Done(Refreshed.Busy(got.retryAfterSeconds))
            is StoreHttp.Got.Failed -> Attempt.Done(Refreshed.Unreachable(got.reason))
        }
    }

    /** Remembered before anything is shown: a later replay of an older list must find the serial already raised. */
    private fun accept(context: Context, target: StoreTarget, index: StoreIndex) {
        if (!StoreFiles.remember(context, target.storeId, index.serial, index.revokeKeys.intersect(target.keys.keys))) {
            Log.w(TAG, "store state not saved")
        }
        if (StoreFiles.readSummary(context, target.storeId)?.serial != index.serial) StoreFiles.writeSummary(context, index)
    }

    private fun verify(blob: ByteArray, target: StoreTarget, state: StoreFiles.State): Verdict {
        val opened = when (val o = StoreSignature.open(blob, target.keys, state.revoked)) {
            is StoreSignature.Opened.Ok -> o
            is StoreSignature.Opened.Refused -> return Verdict.Rejected(o.reason)
        }
        val index = when (val p = StoreIndex.parse(opened.payload, target)) {
            is StoreIndex.Parsed.Ok -> p.index
            StoreIndex.Parsed.NewerSchema -> return Verdict.Newer
            is StoreIndex.Parsed.Refused -> return Verdict.Rejected(p.reason)
        }
        return when {
            // An older serial is an old list replayed: it would bring back removed themes and hide updates.
            index.serial < (state.serials[target.storeId] ?: 0) -> Verdict.Rejected("older than a list this phone has seen")
            opened.keyId in index.revokeKeys -> Verdict.Rejected("it revokes the key that signed it")
            else -> Verdict.Accepted(index, opened.keyId)
        }
    }
}
