package io.github.sherazi5110.cloudstream.cast

import io.github.sherazi5110.cloudstream.App
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * LG's own remote protocol (SSAP over a websocket on the TV, port 3001; 3000 on old sets). The TV asks once on
 * screen to allow this phone; the key it hands back is kept, so later casts need no prompt.
 * Used to open the StreamBox app on the TV with the stream address, which the TV then plays itself.
 */
object Lg {
    const val APP_ID = "com.salem.streambox"

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    // A TV's certificate is self-signed, so it cannot be checked; this client talks only to the TV on the local network.
    private val client: OkHttpClient by lazy {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(6, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private val prefs get() = App.context.getSharedPreferences("lg_tv", 0)

    private var socket: WebSocket? = null
    private var host: String? = null
    private var counter = 0
    private val pending = HashMap<String, CompletableDeferred<JSONObject>>()
    private var registered: CompletableDeferred<String>? = null

    private val permissions = listOf(
        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CONTROL_AUDIO", "CONTROL_INPUT_MEDIA_PLAYBACK",
        "CONTROL_POWER", "READ_INSTALLED_APPS", "READ_RUNNING_APPS", "CONTROL_DISPLAY",
    )

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = runCatching { JSONObject(text) }.getOrNull() ?: return
            val id = m.optString("id")
            when (m.optString("type")) {
                "registered" -> {
                    val key = m.optJSONObject("payload")?.optString("client-key").orEmpty()
                    registered?.complete(key)
                }
                "error" -> {
                    val msg = m.optString("error", "error")
                    if (id.startsWith("register")) registered?.completeExceptionally(IllegalStateException(msg))
                    synchronized(pending) { pending.remove(id) }?.complete(m)
                }
                else -> synchronized(pending) { pending.remove(id) }?.takeIf { id != "register_0" }?.complete(m)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket === socket) socket = null
            registered?.completeExceptionally(t)
            synchronized(pending) { pending.values.forEach { it.completeExceptionally(t) }; pending.clear() }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket === socket) socket = null
        }
    }

    private fun open(h: String, port: Int, scheme: String): WebSocket {
        val deferred = CompletableDeferred<String>()
        registered = deferred
        val ws = client.newWebSocket(Request.Builder().url("$scheme://$h:$port").build(), listener)
        val manifest = JSONObject()
            .put("manifestVersion", 1)
            .put("permissions", org.json.JSONArray(permissions))
        val payload = JSONObject()
            .put("forcePairing", false)
            .put("pairingType", "PROMPT")
            .put("manifest", manifest)
        prefs.getString(h, null)?.let { payload.put("client-key", it) }
        ws.send(JSONObject().put("type", "register").put("id", "register_0").put("payload", payload).toString())
        socket = ws
        host = h
        return ws
    }

    /** Connects (asking the TV to allow this phone the first time). Throws with a readable reason. */
    suspend fun connect(h: String, onPrompt: () -> Unit) {
        if (socket != null && host == h) return
        close()
        var last: Throwable? = null
        for ((scheme, port) in listOf("wss" to 3001, "ws" to 3000)) {
            try {
                open(h, port, scheme)
                val d = registered!!
                // The TV shows a prompt for a first-time phone: give the person time to accept.
                val key = try { withTimeout(4_000) { d.await() } } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    onPrompt()
                    withTimeout(40_000) { d.await() }
                }
                if (key.isNotBlank()) prefs.edit().putString(h, key).apply()
                return
            } catch (e: Throwable) {
                last = e
                runCatching { socket?.cancel() }
                socket = null
                if (e is kotlinx.coroutines.TimeoutCancellationException) throw IllegalStateException("the TV was not answered (accept the prompt on the TV)")
            }
        }
        throw IllegalStateException(last?.message ?: "could not connect to the TV's remote service")
    }

    private suspend fun request(uri: String, payload: JSONObject = JSONObject(), timeoutMs: Long = 8_000): JSONObject {
        val ws = socket ?: throw IllegalStateException("not connected")
        val id = "r${++counter}"
        val d = CompletableDeferred<JSONObject>()
        synchronized(pending) { pending[id] = d }
        ws.send(JSONObject().put("type", "request").put("id", id).put("uri", uri).put("payload", payload).toString())
        val r = withTimeout(timeoutMs) { d.await() }
        if (r.optString("type") == "error") throw IllegalStateException(r.optString("error", "error"))
        return r
    }

    /** Opens the StreamBox app on the TV, handing it the stream address. */
    suspend fun launch(url: String, title: String, startMs: Long, live: Boolean, fallback: String? = null) {
        val params = JSONObject().put("castUrl", url).put("title", title).put("startMs", startMs).put("live", live)
        if (fallback != null) params.put("fallbackUrl", fallback)
        request("ssap://system.launcher/launch", JSONObject().put("id", APP_ID).put("params", params), 15_000)
    }

    suspend fun play() { request("ssap://media.controls/play") }
    suspend fun pause() { request("ssap://media.controls/pause") }
    suspend fun forward() { request("ssap://media.controls/fastForward") }
    suspend fun rewind() { request("ssap://media.controls/rewind") }
    suspend fun volume(): Int? = request("ssap://audio/getVolume").optJSONObject("payload")?.let {
        if (it.has("volumeStatus")) it.getJSONObject("volumeStatus").optInt("volume", -1) else it.optInt("volume", -1)
    }?.takeIf { it >= 0 }
    suspend fun setVolume(v: Int) { request("ssap://audio/setVolume", JSONObject().put("volume", v)) }
    suspend fun stopApp() { request("ssap://system.launcher/close", JSONObject().put("id", APP_ID)) }

    fun close() {
        runCatching { socket?.close(1000, null) }
        socket = null
        host = null
    }
}
