package io.github.sherazi5110.cloudstream.cast

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.utils.ExtractorLink
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.StreamProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The cast session: one TV (a DLNA renderer) playing what the player screen sent it.
 * State is Compose state, read by the cast panel; every network call runs off the main thread.
 */
object Cast {
    val devices = mutableStateListOf<Dlna.Device>()
    var scanning by mutableStateOf(false)
        private set
    /** The TV being cast to, or null. */
    var device by mutableStateOf<Dlna.Device?>(null)
        private set
    /** True from picking a TV until it accepted the stream (or refused it). */
    var connecting by mutableStateOf(false)
        private set
    var title by mutableStateOf("")
        private set
    /** PLAYING, PAUSED_PLAYBACK, TRANSITIONING, STOPPED… as the TV reports it. */
    var state by mutableStateOf("")
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var volume by mutableStateOf<Int?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var live by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var poll: Job? = null

    val playing: Boolean get() = state == "PLAYING" || state == "TRANSITIONING"

    /** Looks for TVs on the network (a few seconds). */
    fun scan() {
        if (scanning) return
        scanning = true
        error = null
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { Dlna.discover() }.getOrDefault(emptyList()) }
            devices.clear()
            devices.addAll(found.sortedBy { it.name.lowercase() })
            scanning = false
        }
    }

    /** The headers a source needs, the way the player builds them. */
    private fun headersOf(link: ExtractorLink): Map<String, String> = buildMap {
        putAll(link.headers)
        if (link.referer.isNotBlank() && keys.none { it.equals("Referer", true) }) put("Referer", link.referer)
    }

    /**
     * Sends [link] to [target], starting at [startMs]. A source that needs headers (most do) goes through the
     * phone's proxy, since a TV sends none of them; a plain URL is fetched by the TV directly.
     */
    fun start(target: Dlna.Device, link: ExtractorLink, name: String, startMs: Long, isLive: Boolean) {
        if (connecting) return
        if (!link.url.startsWith("http", ignoreCase = true)) { error = "Only online sources can be cast (not downloaded files)."; return }
        connecting = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val headers = headersOf(link)
                    val url = if (headers.isEmpty()) link.url else {
                        val me = Dlna.localAddressFor(target.host) ?: throw IllegalStateException("This device has no local network address (is Wi-Fi on?)")
                        StreamProxy.wrapLan(link.url, headers, me)
                    }
                    val mime = Dlna.mimeOf(link.url, link.isM3u8, link.type.name == "DASH")
                    Dlna.setUri(target, url, name, mime)
                    Dlna.play(target)
                    runCatching { Dlna.volume(target) }.getOrNull()
                }
            }
            connecting = false
            result.onFailure { e ->
                error = "${target.name} refused the stream: ${e.message ?: e.toString()}"
                StreamProxy.stopLan()
            }.onSuccess { v ->
                device = target
                title = name
                live = isLive
                volume = v
                state = "TRANSITIONING"
                positionMs = startMs
                durationMs = 0
                CastService.start(App.context)
                watch(target, if (isLive) 0 else startMs)
            }
        }
    }

    /** Follows the TV's state once a second; jumps to [resumeMs] as soon as it plays. */
    private fun watch(target: Dlna.Device, resumeMs: Long) {
        poll?.cancel()
        poll = scope.launch {
            var pendingSeek = resumeMs.takeIf { it > 5_000 }
            var failures = 0
            while (device === target) {
                delay(1000)
                val status = withContext(Dispatchers.IO) { runCatching { Dlna.status(target) }.getOrNull() }
                if (device !== target) break
                if (status == null) {
                    if (++failures >= 6) { error = "Lost the connection to ${target.name}"; end(tellTv = false); break }
                    continue
                }
                failures = 0
                state = status.state
                if (status.durationMs > 0) durationMs = status.durationMs
                if (status.state == "PLAYING") {
                    val seekTo = pendingSeek
                    if (seekTo != null) {
                        pendingSeek = null
                        withContext(Dispatchers.IO) { runCatching { Dlna.seek(target, seekTo) } }
                    } else positionMs = status.positionMs
                } else if (status.state == "PAUSED_PLAYBACK") positionMs = status.positionMs
            }
        }
    }

    private fun act(block: (Dlna.Device) -> Unit) {
        val d = device ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { block(d) } }
                .onFailure { App.toast("${d.name}: ${it.message ?: it.toString()}") }
        }
    }

    fun togglePause() {
        if (state == "PLAYING") { state = "PAUSED_PLAYBACK"; act { Dlna.pause(it) } }
        else { state = "TRANSITIONING"; act { Dlna.play(it) } }
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        positionMs = target
        act { Dlna.seek(it, target) }
    }

    fun seekBy(ms: Long) = seekTo(positionMs + ms)

    fun changeVolume(step: Int) {
        val v = ((volume ?: return) + step).coerceIn(0, 100)
        volume = v
        act { Dlna.setVolume(it, v) }
    }

    /** Ends the session; with [tellTv] the TV stops playing too. */
    fun end(tellTv: Boolean = true) {
        val d = device
        poll?.cancel()
        poll = null
        device = null
        connecting = false
        state = ""
        CastService.stop(App.context)
        scope.launch {
            if (d != null && tellTv) withContext(Dispatchers.IO) { runCatching { Dlna.stop(d) } }
            // The TV may still be reading the last bytes: close the proxy a moment later.
            delay(1500)
            if (device == null) StreamProxy.stopLan()
        }
    }
}
