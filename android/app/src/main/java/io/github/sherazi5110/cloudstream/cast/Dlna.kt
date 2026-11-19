package io.github.sherazi5110.cloudstream.cast

import android.content.Context
import android.net.wifi.WifiManager
import io.github.sherazi5110.cloudstream.App
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URL

/**
 * DLNA / UPnP AV sender: finds media renderers on the local network (smart TVs: LG webOS, Samsung, Sony,
 * Kodi, most set-top boxes) and tells one to play a URL. Plain sockets and HttpURLConnection, no library.
 *
 * Everything here blocks: call it from a background thread.
 */
object Dlna {
    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RENDERING = "urn:schemas-upnp-org:service:RenderingControl:1"

    data class Device(
        val name: String,
        /** The renderer's address, e.g. 192.168.1.20. */
        val host: String,
        val avTransport: String,
        val rendering: String?,
        val location: String,
        val maker: String = "",
    )

    data class Status(val state: String, val positionMs: Long, val durationMs: Long)

    /** This phone's IPv4 addresses on local networks (Wi-Fi, Ethernet, hotspot). */
    private fun localAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && it.isSiteLocalAddress }
    }.getOrDefault(emptyList())

    /** The address of this phone that [host] can reach (the route the system would use to get there). */
    fun localAddressFor(host: String): String? = runCatching {
        DatagramSocket().use { s ->
            s.connect(InetAddress.getByName(host), 9)
            s.localAddress.hostAddress
        }
    }.getOrNull()?.takeIf { it.isNotBlank() && it != "0.0.0.0" && !it.startsWith("::") }
        ?: localAddresses().firstOrNull()?.hostAddress

    /** SSDP search: every renderer that answers within [timeoutMs]. */
    fun discover(timeoutMs: Long = 3500): List<Device> {
        val wifi = App.context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wifi?.createMulticastLock("streamgoat-cast")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val locations = LinkedHashSet<String>()
        val sockets = ArrayList<DatagramSocket>()
        try {
            val found = localAddresses()
            val addresses: List<InetAddress?> = if (found.isEmpty()) listOf<InetAddress?>(null) else found
            for (a in addresses) {
                runCatching { DatagramSocket(InetSocketAddress(a, 0)).apply { soTimeout = 350 } }.getOrNull()?.let { sockets += it }
            }
            val group = InetAddress.getByName("239.255.255.250")
            val targets = listOf("urn:schemas-upnp-org:device:MediaRenderer:1", AV_TRANSPORT)
            repeat(2) {
                for (s in sockets) for (st in targets) {
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray()
                    runCatching { s.send(DatagramPacket(msg, msg.size, group, 1900)) }
                }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                for (s in sockets) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    val ok = runCatching { s.receive(packet); true }.getOrDefault(false)
                    if (!ok) continue
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    text.lineSequence().firstOrNull { it.startsWith("location:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()?.takeIf { it.startsWith("http") }?.let { locations += it }
                }
            }
        } finally {
            sockets.forEach { runCatching { it.close() } }
            runCatching { lock?.release() }
        }
        return locations.mapNotNull { runCatching { describe(it) }.getOrNull() }.distinctBy { it.avTransport }
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name[^>]*>(.*?)</$name>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(xml)?.groupValues?.get(1)?.trim()

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Reads a device description and keeps it when it has an AVTransport service (it can play a URL). */
    private fun describe(location: String): Device? {
        val xml = httpGet(location)
        val base = tag(xml, "URLBase")?.takeIf { it.startsWith("http") } ?: location
        var avUrl: String? = null
        var rcUrl: String? = null
        Regex("<service>(.*?)</service>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(xml).forEach { m ->
            val block = m.groupValues[1]
            val type = tag(block, "serviceType").orEmpty()
            val control = tag(block, "controlURL")?.let { unescape(it) } ?: return@forEach
            val url = runCatching { URL(URL(base), control).toString() }.getOrNull() ?: return@forEach
            if (avUrl == null && type.contains(":AVTransport:")) avUrl = url
            if (rcUrl == null && type.contains(":RenderingControl:")) rcUrl = url
        }
        val transport = avUrl ?: return null
        val host = URL(location).host
        val name = tag(xml, "friendlyName")?.let { unescape(it) }?.takeIf { it.isNotBlank() } ?: host
        val maker = tag(xml, "manufacturer")?.let { unescape(it) }.orEmpty()
        return Device(name, host, transport, rcUrl, location, maker)
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 3000
            c.readTimeout = 4000
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    private fun soap(control: String, service: String, action: String, args: List<Pair<String, String>>, timeoutMs: Int = 10_000): String {
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
            append("<s:Body><u:$action xmlns:u=\"$service\">")
            for ((k, v) in args) append("<$k>${escape(v)}</$k>")
            append("</u:$action></s:Body></s:Envelope>")
        }.toByteArray()
        val c = URL(control).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 4000
            c.readTimeout = timeoutMs
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("User-Agent", "Linux/4.9 UPnP/1.0 StreamGoat/1.0")
            c.setRequestProperty("Connection", "close")
            c.setRequestProperty("SOAPAction", "\"$service#$action\"")
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) {
                val why = tag(text, "errorDescription") ?: tag(text, "errorCode")?.let { "error $it" } ?: "HTTP $code"
                throw IOException("$action: $why")
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun avT(d: Device, action: String, timeoutMs: Int, args: List<Pair<String, String>>) =
        soap(d.avTransport, AV_TRANSPORT, action, listOf("InstanceID" to "0") + args, timeoutMs)

    private fun av(d: Device, action: String, vararg args: Pair<String, String>) = avT(d, action, 10_000, args.toList())

    /** The MIME type a renderer should expect for [url]. */
    fun mimeOf(url: String, hls: Boolean, dash: Boolean): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            hls || path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            dash || path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".ts") -> "video/mp2t"
            path.endsWith(".avi") -> "video/x-msvideo"
            else -> "video/mp4"
        }
    }

    /** How much of the DLNA description a renderer gets: some refuse (or hang on) the full one. */
    enum class Meta { FULL, MINIMAL, NONE }

    /** Another spelling of the format, for renderers that only know one of them. */
    fun altMime(mime: String): String = when (mime) {
        "application/vnd.apple.mpegurl" -> "application/x-mpegURL"
        "video/x-matroska" -> "video/x-mkv"
        "video/mp2t" -> "video/mpeg"
        else -> mime
    }

    private fun metadata(url: String, title: String, mime: String, live: Boolean, mode: Meta): String {
        if (mode == Meta.NONE) return ""
        val features = if (mode == Meta.MINIMAL) "*"
            else "DLNA.ORG_OP=${if (live) "00" else "01"};DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${escape(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:$features\">${escape(url)}</res></item></DIDL-Lite>"
    }

    /** Stops whatever the TV is playing, quickly: an idle renderer answers an error, or nothing. */
    fun stopQuick(d: Device) { runCatching { avT(d, "Stop", 2_500, emptyList()) } }

    /** Loads [url] on the renderer (not playing yet). A slow renderer gets 15 s to answer. */
    fun setUri(d: Device, url: String, title: String, mime: String, live: Boolean, mode: Meta) {
        avT(d, "SetAVTransportURI", 15_000, listOf("CurrentURI" to url, "CurrentURIMetaData" to metadata(url, title, mime, live, mode)))
    }

    fun play(d: Device) { avT(d, "Play", 12_000, listOf("Speed" to "1")) }
    fun pause(d: Device) { av(d, "Pause") }
    fun stop(d: Device) { av(d, "Stop") }

    fun seek(d: Device, ms: Long) {
        val s = (ms / 1000).coerceAtLeast(0)
        av(d, "Seek", "Unit" to "REL_TIME", "Target" to "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60))
    }

    /** "1:02:03" or "1:02:03.456" in milliseconds; 0 for NOT_IMPLEMENTED and the like. */
    private fun parseTime(text: String?): Long {
        val parts = text?.trim()?.substringBefore('.')?.split(':') ?: return 0
        if (parts.size != 3) return 0
        val (h, m, s) = parts.map { it.toLongOrNull() ?: return 0 }
        return ((h * 3600) + (m * 60) + s) * 1000
    }

    fun status(d: Device): Status {
        val state = tag(avT(d, "GetTransportInfo", 4_000, emptyList()), "CurrentTransportState").orEmpty()
        val position = avT(d, "GetPositionInfo", 4_000, emptyList())
        return Status(state, parseTime(tag(position, "RelTime")), parseTime(tag(position, "TrackDuration")))
    }

    /** 0–100, or null when the renderer has no volume control. */
    fun volume(d: Device): Int? {
        val control = d.rendering ?: return null
        val xml = soap(control, RENDERING, "GetVolume", listOf("InstanceID" to "0", "Channel" to "Master"))
        return tag(xml, "CurrentVolume")?.toIntOrNull()
    }

    fun setVolume(d: Device, volume: Int) {
        val control = d.rendering ?: return
        soap(control, RENDERING, "SetVolume", listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to volume.coerceIn(0, 100).toString()))
    }
}
