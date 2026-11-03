// Every request the store makes: its own hosts only, each redirect checked by hand, every byte capped.
package com.wathemer.app.store

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/** Which addresses a request may reach; the harness swaps in one for its local server. */
interface StorePolicy {
    /** The catalogue and the images: the store's own site, never redirected. */
    fun site(url: URL): Boolean

    /** A theme download's first hop, the store repository's releases. */
    fun release(url: URL): Boolean

    /** Each redirect a theme download meets. */
    fun redirect(url: URL): Boolean
}

/** GitHub as the app meets it. Hosts are matched whole, so a lookalike, a user-info trick or another port never passes. */
class GitHubPolicy(private val target: StoreTarget) : StorePolicy {

    private val siteUrl = URL(target.site)

    override fun site(url: URL): Boolean =
        plain(url) && url.host == siteUrl.host && url.path.startsWith(siteUrl.path) && tame(url)

    override fun release(url: URL): Boolean =
        plain(url) && url.host == "github.com" && url.toString().startsWith(target.releases) && tame(url)

    override fun redirect(url: URL): Boolean = plain(url) && ASSET_HOST.matches(url.host.lowercase())

    // The authority must be the bare host: no user info, no port.
    private fun plain(url: URL): Boolean = url.protocol == "https" && url.authority == url.host

    // The app's own paths never hold a dot segment or an escape, which another URL parser could resolve differently.
    private fun tame(url: URL): Boolean = "/." !in url.path && "%" !in url.path

    private companion object {
        // Labels, then githubusercontent.com: the apex, a trailing dot and a lookalike all fail.
        val ASSET_HOST = Regex("^([a-z0-9-]+\\.)+githubusercontent\\.com$")
    }
}

/** One download's handle: [cancel] works from any thread and leaves the part for a resume. */
class StoreJob {

    @Volatile
    var cancelled = false
        private set

    private val open = CopyOnWriteArrayList<Closeable>()

    // A coroutine cancel does not unblock a socket read on this stack; closing the socket does.
    fun cancel() {
        cancelled = true
        for (c in open) runCatching { c.close() }
    }

    /** Closed by [cancel]; one tracked after the cancel is closed at once. */
    internal fun track(c: Closeable) {
        open += c
        if (cancelled) runCatching { c.close() }
    }

    /** Forgets what a finished hop opened; the hop has closed it. */
    internal fun release() = open.clear()
}

class StoreHttp(
    private val policy: StorePolicy,
    private val userAgent: String,
    private val connector: StoreConnector = StoreConnector(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS),
) {

    sealed interface Got {
        class Body(val bytes: ByteArray, val etag: String?) : Got
        data object NotModified : Got
        data object Missing : Got
        data class Busy(val retryAfterSeconds: Long?) : Got
        data class Failed(val reason: String) : Got
    }

    sealed interface Downloaded {
        data class Done(val file: File) : Downloaded
        data object Missing : Downloaded
        data class Busy(val retryAfterSeconds: Long?) : Downloaded
        /** The connection ended early; the part keeps what arrived for the next try to resume. */
        data object Interrupted : Downloaded
        data object Cancelled : Downloaded
        data class Failed(val reason: String) : Downloaded
    }

    private sealed interface Hop {
        class Reached(val connection: StoreResponse, val code: Int) : Hop
        class Ended(val result: Downloaded) : Hop
    }

    private enum class Copy { WHOLE, SHORT, TOO_LONG }

    /** A catalogue or an image, from the site alone and at most [cap] bytes; [etag] lets the site answer 304. */
    fun site(address: String, cap: Int, etag: String? = null): Got {
        val url = runCatching { URL(address) }.getOrNull() ?: return Got.Failed("a bad address")
        if (!policy.site(url)) return Got.Failed("refused ${url.host}")
        var opened: StoreResponse? = null
        return try {
            val c = connect(url, 0L, etag, null).also { opened = it }
            when (val code = c.code) {
                HttpURLConnection.HTTP_OK -> readCapped(c, cap)?.let { Got.Body(it, c.header("ETag")) }
                    ?: Got.Failed("larger than the store allows")
                HttpURLConnection.HTTP_NOT_MODIFIED -> if (etag != null) Got.NotModified else Got.Failed("HTTP 304")
                404, 410 -> Got.Missing
                403, 429 -> Got.Busy(retryAfter(c))
                // Never followed: the site moving would mean its address changed under every build.
                else -> Got.Failed("HTTP $code")
            }
        } catch (e: Exception) {
            Got.Failed("unreachable: ${e.javaClass.simpleName}")
        } finally {
            opened?.close()
        }
    }

    /** A theme file into [part], resumed from whatever [part] holds; Done only once the whole file matches [sha256]. */
    fun download(address: String, size: Long, sha256: String, part: File, job: StoreJob, progress: (Long) -> Unit): Downloaded {
        part.parentFile?.mkdirs()
        var restarted = false
        // One fresh start at most: a file that fails from nothing twice will not pass a third time.
        fun restart(): Boolean {
            part.delete()
            if (restarted) return false
            restarted = true
            return true
        }
        while (true) {
            if (job.cancelled) return Downloaded.Cancelled
            var have = if (part.isFile) part.length() else 0L
            if (have > size) {
                part.delete()
                have = 0L
            }
            // The whole file is hashed from disk, never just the part received this time.
            if (have == size) {
                if (sha256Hex(part) == sha256) return Downloaded.Done(part)
                if (!restart()) return Downloaded.Failed("the file does not match the store's checksum")
                continue
            }
            val hop = reach(address, have, job)
            if (hop is Hop.Ended) return hop.result
            val c = (hop as Hop.Reached).connection
            try {
                val append = when (hop.code) {
                    200 -> false
                    206 -> if (c.header("Content-Range") == "bytes $have-${size - 1}/$size") {
                        true
                    } else {
                        if (!restart()) return Downloaded.Failed("the server sent the wrong part of the file")
                        continue
                    }
                    416 -> {
                        if (!restart()) return Downloaded.Failed("the server would not resume the file")
                        continue
                    }
                    404, 410 -> return Downloaded.Missing
                    403, 429 -> return Downloaded.Busy(retryAfter(c))
                    else -> return Downloaded.Failed("HTTP ${hop.code}")
                }
                when (copy(c, part, append, if (append) have else 0L, size, job, progress)) {
                    Copy.WHOLE -> Unit
                    Copy.SHORT -> return if (job.cancelled) Downloaded.Cancelled else Downloaded.Interrupted
                    Copy.TOO_LONG -> if (!restart()) return Downloaded.Failed("the file is larger than the store says")
                }
            } finally {
                job.release()
                c.close()
            }
        }
    }

    /** The release address, then each redirect by hand: the platform would follow any https host on its own. */
    private fun reach(address: String, from: Long, job: StoreJob): Hop {
        var url = runCatching { URL(address) }.getOrNull() ?: return Hop.Ended(Downloaded.Failed("a bad address"))
        if (!policy.release(url)) return Hop.Ended(Downloaded.Failed("refused ${url.host}"))
        var redirects = 0
        while (true) {
            val c = runCatching { connect(url, from, null, job) }.getOrElse {
                job.release()
                return Hop.Ended(if (job.cancelled) Downloaded.Cancelled else Downloaded.Failed("unreachable"))
            }
            if (job.cancelled) {
                c.close()
                job.release()
                return Hop.Ended(Downloaded.Cancelled)
            }
            val code = c.code
            if (code !in 300..399) return Hop.Reached(c, code)
            val location = c.header("Location")
            c.close()
            job.release()
            if (location == null || ++redirects > MAX_REDIRECTS) return Hop.Ended(Downloaded.Failed("redirected too often"))
            url = runCatching { URL(url, location) }.getOrNull() ?: return Hop.Ended(Downloaded.Failed("a bad redirect"))
            if (!policy.redirect(url)) return Hop.Ended(Downloaded.Failed("refused ${url.host}"))
        }
    }

    private fun copy(
        c: StoreResponse,
        part: File,
        append: Boolean,
        start: Long,
        size: Long,
        job: StoreJob,
        progress: (Long) -> Unit,
    ): Copy {
        var total = start
        try {
            c.body.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (!job.cancelled) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (total + n > size) return Copy.TOO_LONG
                        out.write(buf, 0, n)
                        total += n
                        progress(total)
                    }
                }
            }
        } catch (e: IOException) {
            return Copy.SHORT
        }
        return if (total == size) Copy.WHOLE else Copy.SHORT
    }

    /** Never followed and never cached; [job] gets every socket so its cancel can close them. */
    private fun connect(url: URL, from: Long, etag: String?, job: StoreJob?): StoreResponse {
        val headers = ArrayList<Pair<String, String>>()
        headers += "User-Agent" to userAgent
        // Uncompressed, so a file's length and byte ranges are its own, and the cap counts real bytes.
        headers += "Accept-Encoding" to "identity"
        if (from > 0) headers += "Range" to "bytes=$from-"
        if (etag != null) headers += "If-None-Match" to etag
        return connector.get(url, headers) { job?.track(it) }
    }

    private fun readCapped(c: StoreResponse, cap: Int): ByteArray? {
        if (c.contentLength > cap) return null
        c.body.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) return out.toByteArray()
                if (out.size() + n > cap) return null
                out.write(buf, 0, n)
            }
        }
    }

    private fun retryAfter(c: StoreResponse): Long? =
        c.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it in 0..86_400 }

    companion object {
        const val MAX_REDIRECTS = 3
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
    }
}
