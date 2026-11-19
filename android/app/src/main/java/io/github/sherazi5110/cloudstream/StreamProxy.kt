package io.github.sherazi5110.cloudstream

import com.lagradost.cloudstream3.app
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import io.github.sherazi5110.cloudstream.cast.Dlna

/**
 * Loopback HTTP proxy for the player (Android port of the Linux app's StreamProxy).
 *
 * libVLC only sends a User-Agent and a Referer, but extensions often need Origin, cookies or
 * token headers on every request. The player opens `http://127.0.0.1:<port>/s/<id>/<name>`
 * instead; the proxy adds the extension's headers, passes Range through for seeking, and
 * rewrites HLS playlists so every variant playlist, segment and key goes through it too.
 *
 * Casting uses a second listener on the Wi-Fi address ([wrapLan]): a TV fetches the stream from the
 * phone, which adds the headers the TV cannot send. Only URLs registered here are served.
 */
object StreamProxy {
    private data class Target(val url: String, val headers: Map<String, String>, val lanMime: String? = null, val chunked: Boolean = false)

    /** What a source answers with: its type, and its length when it has one (a live stream has none). */
    data class Probe(val contentType: String, val length: Long?)

    private val targets = ConcurrentHashMap<String, Target>()
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "stream-proxy").apply { isDaemon = true } }

    private val skipResponse = setOf("transfer-encoding", "connection", "content-encoding", "content-length", "keep-alive")

    @Synchronized
    private fun start(): ServerSocket = server ?: ServerSocket(0, 64, InetAddress.getByName("127.0.0.1")).also { s ->
        server = s
        pool.execute {
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: continue
                pool.execute { socket.use { handle(it, lan = false) } }
            }
        }
    }

    // Casting: the same proxy, reachable from the local network while a cast session runs.
    @Volatile private var lanServer: ServerSocket? = null
    @Volatile private var lanHost: String = "127.0.0.1"
    private val lanCount = AtomicInteger()
    private val lanEvents = ConcurrentLinkedDeque<String>()

    /** How many requests TVs made to the local-network listener: tells "never connected" from "did not like it". */
    fun lanRequests(): Int = lanCount.get()

    /** The last few requests a TV made, and how they were answered. */
    fun lanLog(): List<String> = lanEvents.toList()

    private fun lanNote(text: String) {
        lanEvents.addLast(text)
        while (lanEvents.size > 8) lanEvents.pollFirst()
    }

    @Synchronized
    private fun startLan(): ServerSocket = lanServer?.takeIf { !it.isClosed } ?: ServerSocket(0, 64).also { s ->
        lanServer = s
        pool.execute {
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: continue
                pool.execute { socket.use { handle(it, lan = true) } }
            }
        }
    }

    /** Like [wrap], for a device on the local network: [host] is this phone's address on that network. */
    fun wrapLan(url: String, headers: Map<String, String>, host: String, mime: String? = null, chunked: Boolean = false): String {
        lanHost = host
        return link(url, headers, lan = true, mime = mime, chunked = chunked)
    }

    /** Asks the source for its headers only (blocks: call off the main thread). */
    fun probe(url: String, headers: Map<String, String>): Probe? = runCatching {
        val request = Request.Builder().url(url).headers(headers.toHeaders()).build()
        app.baseClient.newCall(request).execute().use { r ->
            Probe(r.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase(), r.header("Content-Length")?.toLongOrNull())
        }
    }.getOrNull()

    /** Closes the local-network listener (the cast session ended). */
    @Synchronized
    fun stopLan() {
        runCatching { lanServer?.close() }
        lanServer = null
        lanEvents.clear()
    }

    /** Returns a loopback URL that fetches [url] with [headers]. */
    fun wrap(url: String, headers: Map<String, String>): String = link(url, headers, lan = false)

    private fun link(url: String, headers: Map<String, String>, lan: Boolean, mime: String? = null, chunked: Boolean = false): String {
        val s = if (lan) startLan() else start()
        val id = id(url, headers, mime.orEmpty() + chunked)
        targets[id] = Target(url, headers, mime, chunked)
        // Keep the original file name/extension: the player uses it to guess the format.
        val name = runCatching { URI(url).path.substringAfterLast('/') }.getOrNull().orEmpty()
            .ifBlank { "stream" }.let { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "http://${if (lan) lanHost else "127.0.0.1"}:${s.localPort}/s/$id/$name"
    }

    private fun id(url: String, headers: Map<String, String>, extra: String = ""): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((url + "\n" + headers.toSortedMap() + extra).toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun handle(socket: Socket, lan: Boolean) {
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        val requestLine = readLine(input) ?: return
        val (method, path) = requestLine.split(" ").let { (it.getOrNull(0) ?: "GET") to (it.getOrNull(1) ?: "/") }
        if (lan) lanCount.incrementAndGet()
        var range: String? = null
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (line.startsWith("range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        val id = path.removePrefix("/s/").substringBefore('/')
        val target = targets[id] ?: run {
            if (lan) lanNote("$method → 404 (unknown link)")
            return respond(out, 404, emptyList(), null)
        }
        val headers = target.headers.toMutableMap()
        range?.let { headers["Range"] = it }
        val sent = AtomicLong()
        val t0 = System.currentTimeMillis()
        val request = Request.Builder().url(target.url).headers(headers.toHeaders())
            // A TV often asks HEAD first, which some hosts refuse: ask them GET and send back the headers only.
            .apply { if (method == "HEAD" && !lan) head() }
            .build()
        try {
            app.baseClient.newCall(request).execute().use { response ->
                val type = response.header("Content-Type").orEmpty()
                if (lan) lanNote("$method${if (range != null) " [$range]" else ""} → ${response.code} ${type.substringBefore(';').ifBlank { "no type" }}")
                val copied = response.headers.filter { (n, _) -> n.lowercase() !in skipResponse }.toMutableList()
                if (lan) {
                    // A TV needs a video type to agree to play; hosts sometimes send none, or a generic one.
                    val ct = type.lowercase()
                    val forced = target.lanMime
                    if (forced != null) {
                        copied.removeAll { it.first.equals("Content-Type", true) }
                        copied += "Content-Type" to forced
                    } else if (ct.isBlank() || "octet-stream" in ct || ct.startsWith("text/")) {
                        copied.removeAll { it.first.equals("Content-Type", true) }
                        copied += "Content-Type" to Dlna.mimeOf(target.url, false, false)
                    }
                    // A stream with no length (live TV) cannot be sought: saying it can makes some TVs
                    // wait for a length or ask for ranges for ever.
                    val seekable = response.header("Content-Length") != null || response.code == 206
                    copied.removeAll { it.first.equals("Accept-Ranges", true) }
                    if (seekable) copied += "Accept-Ranges" to "bytes"
                    copied += "transferMode.dlna.org" to "Streaming"
                    copied += "contentFeatures.dlna.org" to "DLNA.ORG_OP=${if (seekable) "01" else "00"};DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
                }
                if (method != "HEAD" && isPlaylist(target.url, type)) {
                    val text = response.body.string()
                    if (text.trimStart().startsWith("#EXTM3U")) {
                        val bytes = rewrite(text, response.request.url.toString(), target.headers, lan).toByteArray()
                        respond(out, response.code, listOf("Content-Type" to "application/vnd.apple.mpegurl"), bytes)
                    } else respond(out, response.code, copied, text.toByteArray())
                    return
                }
                val length = response.header("Content-Length")
                val chunked = lan && target.chunked && length == null && method != "HEAD"
                val head = buildString {
                    append("HTTP/1.1 ${response.code} ${response.message.ifBlank { "OK" }}\r\n")
                    copied.forEach { (n, v) -> append("$n: $v\r\n") }
                    length?.let { append("Content-Length: $it\r\n") }
                    if (chunked) append("Transfer-Encoding: chunked\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(head.toByteArray())
                if (method != "HEAD") response.body.byteStream().use { pump(it, out, chunked, sent) }
                out.flush()
                if (lan && method != "HEAD") lanNote("$method → sent ${sent.get() / 1024} KB in ${(System.currentTimeMillis() - t0) / 100 / 10.0} s, then it ended")
            }
        } catch (t: Throwable) {
            // The player closing a connection mid-stream (seek, stop) lands here too.
            if (lan) lanNote("$method → stopped after ${sent.get() / 1024} KB, ${(System.currentTimeMillis() - t0) / 100 / 10.0} s: ${t.javaClass.simpleName} ${t.message.orEmpty().take(40)}")
            runCatching { respond(out, 502, emptyList(), null) }
        }
    }

    /** Copies a body to the client as it arrives; [chunked] frames it with Transfer-Encoding: chunked. */
    private fun pump(src: java.io.InputStream, out: OutputStream, chunked: Boolean, sent: AtomicLong) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = src.read(buf)
            if (n < 0) break
            if (n == 0) continue
            if (chunked) out.write("${Integer.toHexString(n)}\r\n".toByteArray())
            out.write(buf, 0, n)
            if (chunked) out.write("\r\n".toByteArray())
            out.flush()
            sent.addAndGet(n.toLong())
        }
        if (chunked) { out.write("0\r\n\r\n".toByteArray()); out.flush() }
    }

    private fun respond(out: OutputStream, code: Int, headers: List<Pair<String, String>>, body: ByteArray?) {
        val head = buildString {
            append("HTTP/1.1 $code ${if (code in 200..299) "OK" else "Error"}\r\n")
            headers.forEach { (n, v) -> append("$n: $v\r\n") }
            append("Content-Length: ${body?.size ?: 0}\r\nConnection: close\r\n\r\n")
        }
        out.write(head.toByteArray())
        body?.let { out.write(it) }
        out.flush()
    }

    private fun isPlaylist(url: String, contentType: String): Boolean {
        val type = contentType.lowercase()
        return "mpegurl" in type || runCatching { URI(url).path.lowercase().endsWith(".m3u8") }.getOrDefault(false)
    }

    private val uriAttribute = Regex("URI=\"([^\"]+)\"")

    /** Points every URI in an HLS playlist (segments, variants, keys, maps) back at the proxy. */
    fun rewrite(playlist: String, baseUrl: String, headers: Map<String, String>, lan: Boolean = false): String {
        val base = URI(baseUrl)
        fun proxied(ref: String) = link(base.resolve(ref.trim().replace(" ", "%20")).toString(), headers, lan)
        return playlist.lineSequence().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> uriAttribute.replace(line) { m -> "URI=\"${proxied(m.groupValues[1])}\"" }
                else -> proxied(line)
            }
        }
    }
}
