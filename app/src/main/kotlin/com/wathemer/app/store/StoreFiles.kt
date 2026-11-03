// The store's files on the phone: verified copies in the cache, and the little it must remember for good.
package com.wathemer.app.store

import android.content.Context
import android.util.Log
import com.wathemer.app.settings.prefs.ThemeLibrary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object StoreFiles {

    private const val TAG = "WaThemer.Store"

    /** A part left this long is given up; the file it belonged to has likely moved on. */
    private const val PART_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val TEMP_MAX_AGE_MS = 60L * 60 * 1000

    private const val MAX_ETAG = 200

    /** What the phone keeps for good: the highest serial seen of each store, and every key a catalogue revoked. */
    data class State(val serials: Map<String, Int> = emptyMap(), val revoked: Set<String> = emptySet())

    /** The last catalogue that passed, and the ETag it came with; [checkedAt] is when the site last vouched for it. */
    class CachedIndex(val blob: ByteArray, val etag: String?, val checkedAt: Long)

    /** What Home and Saved themes show without opening the catalogue: how many themes, and the newest usable version of each. */
    data class Summary(val serial: Int, val themes: Int, val latest: Map<String, Int>)

    private val stateLock = Any()

    fun dir(context: Context): File = File(context.cacheDir, "store")

    fun imageFile(context: Context, sha256: String): File = File(File(dir(context), "img"), sha256)

    fun downloadPart(context: Context, sha256: String): File = File(File(dir(context), "dl"), "$sha256.part")

    private fun indexFile(context: Context, storeId: String): File =
        File(dir(context), "index-${storeId.replace('/', '-')}.bin")

    // filesDir, not the cache: forgetting the serial would let an old catalogue back in.
    private fun stateFile(context: Context): File = File(File(context.filesDir, "store"), "state.json")

    private fun summaryFile(context: Context, storeId: String): File =
        File(File(context.filesDir, "store"), "summary-${storeId.replace('/', '-')}.json")

    fun readSummary(context: Context, storeId: String): Summary? = runCatching {
        val root = JSONObject(summaryFile(context, storeId).readText())
        val latest = HashMap<String, Int>()
        root.optJSONObject("latest")?.let { o -> for (k in o.keys()) (o.opt(k) as? Int)?.let { latest[k] = it } }
        Summary(root.getInt("serial"), root.getInt("themes"), latest)
    }.getOrNull()

    fun writeSummary(context: Context, index: StoreIndex): Boolean {
        val latest = JSONObject()
        for (t in index.themes) if (t.usable) latest.put(t.id, t.version)
        val root = JSONObject().put("serial", index.serial).put("themes", index.themes.size).put("latest", latest)
        return writeAtomically(summaryFile(context, index.store), root.toString().toByteArray(Charsets.UTF_8))
    }

    fun readState(context: Context): State = runCatching {
        val root = JSONObject(stateFile(context).readText())
        val serials = HashMap<String, Int>()
        root.optJSONObject("serials")?.let { o -> for (k in o.keys()) (o.opt(k) as? Int)?.let { serials[k] = it } }
        val revoked = HashSet<String>()
        root.optJSONArray("revoked")?.let { a -> for (i in 0 until a.length()) (a.opt(i) as? String)?.let { revoked += it } }
        State(serials, revoked)
    }.getOrDefault(State())

    /** Raises the serial of [storeId] and adds [revoked]; never lowers or forgets anything. */
    fun remember(context: Context, storeId: String, serial: Int, revoked: Set<String>): Boolean = synchronized(stateLock) {
        val old = readState(context)
        val serials = old.serials + (storeId to maxOf(serial, old.serials[storeId] ?: 0))
        val keys = old.revoked + revoked
        if (serials == old.serials && keys == old.revoked) return true
        val root = JSONObject()
            .put("serials", JSONObject().apply { serials.forEach { (k, v) -> put(k, v) } })
            .put("revoked", JSONArray(keys.sorted()))
        writeAtomically(stateFile(context), root.toString().toByteArray(Charsets.UTF_8))
    }

    fun readIndex(context: Context, storeId: String): CachedIndex? = runCatching {
        val f = indexFile(context, storeId)
        if (!f.isFile || f.length() > StoreIndex.MAX_BYTES + StoreSignature.MAX_HEADER + MAX_ETAG + 2) return null
        val bytes = f.readBytes()
        val newline = bytes.indexOf('\n'.code.toByte()).takeIf { it in 0..MAX_ETAG } ?: return null
        val etag = String(bytes, 0, newline, Charsets.UTF_8).ifEmpty { null }
        CachedIndex(bytes.copyOfRange(newline + 1, bytes.size), etag, f.lastModified())
    }.getOrNull()

    /** One file for the body and its ETag, so the ETag can never vouch for bytes that failed; dated [now], when the site vouched. */
    fun writeIndex(context: Context, storeId: String, blob: ByteArray, etag: String?, now: Long): Boolean {
        val tag = etag?.takeIf { it.length <= MAX_ETAG && '\n' !in it && '\r' !in it }.orEmpty()
        val file = indexFile(context, storeId)
        if (!writeAtomically(file, tag.toByteArray(Charsets.UTF_8) + '\n'.code.toByte() + blob)) return false
        file.setLastModified(now)
        return true
    }

    /** The site answered 304: the copy stands, checked as of [now]. */
    fun touchIndex(context: Context, storeId: String, now: Long) {
        indexFile(context, storeId).setLastModified(now)
    }

    fun dropIndex(context: Context, storeId: String) {
        indexFile(context, storeId).delete()
    }

    /** Written under a name of its own and moved over [dest], so a reader finds the old bytes or the new, never half. */
    fun writeAtomically(dest: File, bytes: ByteArray): Boolean {
        val dir = dest.parentFile ?: return false
        dir.mkdirs()
        val temp = runCatching { File.createTempFile(dest.name, ".part", dir) }.getOrNull() ?: return false
        // File.renameTo will not replace an existing file everywhere; an atomic move does.
        val ok = runCatching {
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.isSuccess
        if (!ok) temp.delete()
        return ok
    }

    /** Housekeeping when the store opens: the library's leftovers, stale or unlisted downloads, images no theme names. */
    fun tidy(context: Context, index: StoreIndex, busy: Set<String>, now: Long) {
        val fixed = ThemeLibrary.recover(context)
        var dropped = 0
        fun drop(f: File) { if (f.delete()) dropped++ }
        // A temp file this old was left by a write that never finished; a younger one may still be in use.
        fun orphan(f: File) = f.name.endsWith(".part") && now - f.lastModified() > TEMP_MAX_AGE_MS
        val wanted = index.themes.mapTo(HashSet()) { it.fileSha256 }
        File(dir(context), "dl").listFiles()?.forEach { f ->
            val sha = f.name.removeSuffix(".part")
            if (sha in busy) return@forEach
            if (now - f.lastModified() > PART_MAX_AGE_MS || !f.name.endsWith(".part") || sha !in wanted) drop(f)
        }
        val named = HashSet<String>()
        index.themes.forEach { t ->
            named += t.thumb.sha256
            t.shots.forEach { named += it.sha256 }
            t.previewHeader?.let { named += it.sha256 }
            t.previewWallpaper?.let { named += it.sha256 }
        }
        File(dir(context), "img").listFiles()?.forEach { f ->
            if (f.name.endsWith(".part")) { if (orphan(f)) drop(f) } else if (f.name !in named) drop(f)
        }
        for (d in listOf(dir(context), stateFile(context).parentFile)) d?.listFiles()?.forEach { if (orphan(it)) drop(it) }
        if (fixed + dropped > 0) Log.i(TAG, "tidy: $fixed library file(s) recovered, $dropped cached file(s) dropped")
    }
}
