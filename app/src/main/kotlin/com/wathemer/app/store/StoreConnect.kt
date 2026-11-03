// The store's own connections: a host's addresses raced, TLS checked against the host, plain HTTP/1.1.
package com.wathemer.app.store

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** One answer: status, headers, and a body that ends where the response does; [close] drops the connection. */
class StoreResponse internal constructor(
    val code: Int,
    private val headers: Map<String, String>,
    val body: InputStream,
    private val socket: Socket,
) : Closeable {
    fun header(name: String): String? = headers[name.lowercase()]

    /** -1 when the server sent no usable length. */
    val contentLength: Long get() = header("content-length")?.toLongOrNull()?.takeIf { it >= 0 } ?: -1L

    override fun close() {
        runCatching { socket.close() }
    }
}

/** A GET on whichever of a host's addresses answers first; the platform tries them in turn, a full timeout each. */
class StoreConnector(
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val dial: (Socket, InetSocketAddress, Int) -> Unit = { s, to, timeout -> s.connect(to, timeout) },
    private val tls: () -> SSLSocketFactory = { SSLSocketFactory.getDefault() as SSLSocketFactory },
    private val staggerMs: Long = STAGGER_MS,
) {

    /** [headers] go out as given, Host and Connection are added here; [track] gets every socket, for a cancel. */
    fun get(url: URL, headers: List<Pair<String, String>>, track: (Closeable) -> Unit = {}): StoreResponse {
        val https = when (url.protocol) {
            "https" -> true
            "http" -> false
            else -> throw IOException("unsupported scheme ${url.protocol}")
        }
        val host = url.host
        val target = url.file.ifEmpty { "/" }
        // Nothing reaches the request that could end a line early and start another.
        if (!token(host) || !token(target) || headers.any { (k, v) -> !token(k) || !value(v) }) {
            throw IOException("a bad request")
        }
        val port = if (url.port != -1) url.port else url.defaultPort
        val raw = race(resolve(host).ifEmpty { throw UnknownHostException(host) }, port, track)
        try {
            raw.soTimeout = readTimeoutMs
            val socket = if (https) secure(raw, host, port).also(track) else raw
            val request = buildString {
                append("GET ").append(target).append(" HTTP/1.1\r\n")
                append("Host: ").append(host)
                if (url.port != -1 && url.port != url.defaultPort) append(':').append(url.port)
                append("\r\n")
                for ((k, v) in headers) append(k).append(": ").append(v).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            return read(socket)
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    }

    /** The first address that answers; the next starts after [staggerMs], or at once when one fails. */
    private fun race(addresses: List<InetAddress>, port: Int, track: (Closeable) -> Unit): Socket {
        val results = LinkedBlockingQueue<Pair<Socket, Throwable?>>()
        val sockets = ArrayList<Socket>()
        var started = 0
        var finished = 0
        var last: Throwable?
        var winner: Socket? = null
        fun start() {
            val s = Socket()
            val to = InetSocketAddress(addresses[started++], port)
            sockets += s
            // Tracked before the dial, so a cancel also closes a connect that is still waiting.
            track(s)
            POOL.execute {
                val error = runCatching { dial(s, to, connectTimeoutMs) }.exceptionOrNull()
                if (error != null) runCatching { s.close() }
                results.put(s to error)
            }
        }
        try {
            start()
            while (true) {
                val r = if (started < addresses.size) results.poll(staggerMs, TimeUnit.MILLISECONDS) else results.take()
                if (r == null) {
                    start()
                    continue
                }
                finished++
                val (s, error) = r
                if (error == null) {
                    winner = s
                    return s
                }
                last = error
                if (started < addresses.size) start()
                else if (finished == started) throw (last as? IOException) ?: ConnectException("no address answered")
            }
        } finally {
            for (s in sockets) if (s !== winner) runCatching { s.close() }
        }
    }

    private fun secure(raw: Socket, host: String, port: Int): SSLSocket {
        val ssl = tls().createSocket(raw, host, port, true) as SSLSocket
        ssl.soTimeout = readTimeoutMs
        // The platform checks the certificate against the host only when HTTPS identification is asked for.
        ssl.sslParameters = ssl.sslParameters.apply {
            runCatching { SNIHostName(host) }.getOrNull()?.let { serverNames = listOf(it) }
            endpointIdentificationAlgorithm = "HTTPS"
        }
        ssl.startHandshake()
        return ssl
    }

    private fun read(socket: Socket): StoreResponse {
        val input = BufferedInputStream(socket.getInputStream(), BUFFER)
        val status = line(input) ?: throw IOException("no response")
        val parts = status.split(' ', limit = 3)
        if (parts.size < 2 || !parts[0].startsWith("HTTP/1.")) throw IOException("not HTTP/1.x")
        val code = parts[1].toIntOrNull()?.takeIf { it in 100..599 } ?: throw IOException("a bad status")
        val headers = HashMap<String, String>()
        var count = 0
        while (true) {
            val l = line(input) ?: throw IOException("headers cut short")
            if (l.isEmpty()) break
            if (++count > MAX_HEADERS) throw IOException("too many headers")
            val colon = l.indexOf(':')
            if (colon <= 0) continue
            val name = l.substring(0, colon).trim().lowercase()
            val v = l.substring(colon + 1).trim()
            val had = headers.putIfAbsent(name, v)
            // A length, range or next address given twice two ways has no right answer.
            if (had != null && had != v && name in STRICT) throw IOException("conflicting $name")
        }
        val length = headers["content-length"]
        val body: InputStream = when {
            code in 100..199 || code == 204 || code == 304 -> ByteArrayInputStream(ByteArray(0))
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> Chunked(input)
            length != null -> Bounded(input, length.toLongOrNull()?.takeIf { it >= 0 } ?: throw IOException("a bad length"))
            else -> input
        }
        return StoreResponse(code, headers, body, socket)
    }

    /** Exactly [left] bytes, then the end; a connection that closes sooner is an error, not a short file. */
    private class Bounded(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = super.read()
            if (b < 0) throw IOException("the body ended early")
            left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            if (len == 0) return 0
            val n = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw IOException("the body ended early")
            left -= n
            return n
        }
    }

    /** RFC 9112 chunked coding. */
    private class Chunked(private val input: InputStream) : InputStream() {
        private var left = 0L
        private var done = false

        private fun next(): Boolean {
            if (done) return false
            if (left > 0) return true
            val size = line(input)?.substringBefore(';')?.trim()?.toLongOrNull(16)
                ?.takeIf { it >= 0 } ?: throw IOException("a bad chunk size")
            if (size == 0L) {
                while (true) {
                    val t = line(input) ?: throw IOException("trailers cut short")
                    if (t.isEmpty()) break
                }
                done = true
                return false
            }
            left = size
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!next()) return -1
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw IOException("the body ended early")
            left -= n
            if (left == 0L && line(input) != "") throw IOException("a chunk not closed by CRLF")
            return n
        }
    }

    companion object {
        /** RFC 8305's connection attempt delay: short next to a dead address, long enough for a near one. */
        const val STAGGER_MS = 250L
        private const val BUFFER = 16 * 1024
        private const val MAX_LINE = 16 * 1024
        private const val MAX_HEADERS = 200
        private val STRICT = setOf("content-length", "transfer-encoding", "content-range", "location")

        // Daemon threads: a dial left waiting on a dead address must never hold anything up.
        private val POOL = Executors.newCachedThreadPool { r -> Thread(r, "wathemer-store-dial").apply { isDaemon = true } }

        private fun token(s: String): Boolean = s.isNotEmpty() && s.all { it in '!'..'~' }

        private fun value(s: String): Boolean = s.all { it in ' '..'~' }

        /** One line without its CRLF; null at a clean end of stream; past [MAX_LINE] is an error. */
        private fun line(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else throw IOException("a line cut short")
                if (c == '\n'.code) return sb.toString().removeSuffix("\r")
                if (sb.length >= MAX_LINE) throw IOException("a line too long")
                sb.append(c.toChar())
            }
        }
    }
}
