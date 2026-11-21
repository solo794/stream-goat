package io.github.sherazi5110.cloudstream.cast

import android.os.SystemClock
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
import java.net.SocketTimeoutException

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
    /** What the connection attempt is doing now ("Trying through this phone (1/3)…"). */
    var step by mutableStateOf("")
        private set
    /** Why attempts failed, one line each: shown under the error so it can be read off a screenshot. */
    val log = mutableStateListOf<String>()

    /** True while the TV plays through LG's own app launch (StreamBox on the TV), not DLNA: no position or seeking then. */
    var lgMode by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var poll: Job? = null
    private var job: Job? = null
    private var attempt = 0

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

    private data class Plan(val label: String, val viaPhone: Boolean, val mime: String, val meta: Dlna.Meta, val chunked: Boolean = false)

    private suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching { block() } }

    private fun describe(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "got no answer from the TV (timeout)"
        is java.net.ConnectException -> "could not connect to the TV"
        else -> e.message ?: e.toString()
    }

    /**
     * Sends [link] to [target], starting at [startMs]. TVs differ a lot in what they accept, so it tries in turn:
     * through this phone (which adds the headers a TV cannot send and hands the TV plain http), through the
     * phone again with a bare request, and, when the source needs no headers, straight from the source.
     */
    fun start(target: Dlna.Device, link: ExtractorLink, name: String, startMs: Long, isLive: Boolean) {
        if (connecting) return
        if (!link.url.startsWith("http", ignoreCase = true)) { error = "Only online sources can be cast (not downloaded files)."; return }
        connecting = true
        error = null
        log.clear()
        step = "Connecting to ${target.name}…"
        val mine = ++attempt
        job = scope.launch {
            var failure: String? = null
            var viaLg = false
            try {
                if (target.maker.contains("LG", ignoreCase = true)) {
                    val lgFailure = connectLg(target, link, name, startMs, isLive)
                    if (lgFailure == null) viaLg = true else log += "LG remote service: $lgFailure"
                }
                if (!viaLg) failure = connect(target, link, name, isLive)
            } finally {
                if (attempt == mine) { connecting = false; step = "" }
            }
            if (attempt != mine) return@launch
            if (failure != null) {
                error = "${target.name} did not play the stream. $failure"
                StreamProxy.stopLan()
                return@launch
            }
            lgMode = viaLg
            val v = if (viaLg) runCatching { Lg.volume() }.getOrNull() else io { Dlna.volume(target) }.getOrNull()
            device = target
            title = name
            live = isLive || viaLg
            volume = v
            state = "PLAYING"
            positionMs = startMs
            durationMs = 0
            CastService.start(App.context)
            if (!viaLg) watch(target, if (isLive) 0 else startMs)
        }
    }

    /** Opens StreamBox on an LG TV with the stream address. Null when it worked, otherwise why not. */
    private suspend fun connectLg(target: Dlna.Device, link: ExtractorLink, name: String, startMs: Long, isLive: Boolean): String? {
        val me = io { Dlna.localAddressFor(target.host) }.getOrNull() ?: return "this device has no local network address"
        return try {
            step = "Connecting to ${target.name}'s remote service…"
            Lg.connect(target.host) { step = "Accept the prompt shown on the TV…" }
            step = "Opening StreamBox on the TV…"
            val headers = headersOf(link)
            // The TV loads the stream itself, from the source, so the phone (and its data) is not involved.
            // A source that wants special headers may refuse the TV; StreamBox then retries through this phone.
            val viaPhone = StreamProxy.wrapLan(link.url, headers, me)
            Lg.launch(link.url, viaPhone.takeIf { headers.isNotEmpty() }, name, startMs, isLive)
            null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            Lg.close()
            val m = e.message.orEmpty()
            when {
                e is kotlinx.coroutines.TimeoutCancellationException -> "the TV did not answer"
                m.contains("not found", true) || m.contains("invalid", true) || m.contains("Cannot", true) ->
                    "StreamBox is not installed on the TV (install StreamBox 1.3.0 from the TV app package) — $m"
                else -> describe(e)
            }
        }
    }

    /** Gives up on a connection attempt that is still running (the panel was closed). */
    fun cancel() {
        if (!connecting) return
        attempt++
        job?.cancel()
        connecting = false
        step = ""
        StreamProxy.stopLan()
    }

    /** Null when the TV is playing; otherwise why it was not. */
    private suspend fun connect(target: Dlna.Device, link: ExtractorLink, name: String, isLive: Boolean): String? {
        val headers = headersOf(link)
        // What the source really is, not what its address suggests (many have no extension).
        step = "Checking the source…"
        val probe = io { StreamProxy.probe(link.url, headers) }.getOrNull()
        val served = probe?.contentType.orEmpty()
        val hls = link.isM3u8 || "mpegurl" in served
        val mime = when {
            hls -> "application/vnd.apple.mpegurl"
            served.startsWith("video/") -> served
            else -> Dlna.mimeOf(link.url, link.isM3u8, link.type.name == "DASH")
        }
        // No length means a live stream: it cannot be sought, and a TV wants to be told so.
        val live = isLive || (!hls && probe?.length == null)
        val plans = buildList {
            add(Plan("through this phone", true, mime, Dlna.Meta.FULL))
            add(Plan(if (live) "through this phone, chunked, other format" else "through this phone, bare request", true,
                Dlna.altMime(mime), if (live) Dlna.Meta.FULL else Dlna.Meta.NONE, chunked = live))
            if (headers.isEmpty()) add(Plan("straight from the source", false, mime, Dlna.Meta.FULL))
        }
        val began = SystemClock.elapsedRealtime()
        for ((i, plan) in plans.withIndex()) {
            if (i > 0 && SystemClock.elapsedRealtime() - began > 70_000) break
            step = "Trying ${plan.label} (${i + 1}/${plans.size})…"
            val url = if (plan.viaPhone) {
                val me = io { Dlna.localAddressFor(target.host) }.getOrNull()
                    ?: return "This device has no local network address (is Wi-Fi on?)"
                StreamProxy.wrapLan(link.url, headers, me, plan.mime, plan.chunked)
            } else link.url
            val before = StreamProxy.lanRequests()
            io { Dlna.resetTransport(target) }
            val setError = io { Dlna.setUri(target, url, name, plan.mime, live, plan.meta) }.exceptionOrNull()
            if (setError != null) {
                log += "${plan.label}: SetAVTransportURI ${describe(setError)}"
                // A refusal will not change by waiting. A TV that is only slow may still have taken the URL.
                if (setError !is SocketTimeoutException) continue
            }
            io { Dlna.play(target) }.exceptionOrNull()?.let { log += "${plan.label}: Play ${describe(it)}" }
            val why = waitPlaying(target, before, plan.viaPhone) ?: return null
            log += "${plan.label}: $why"
        }
        return "Details below."
    }

    /** Waits for the TV to start playing; null when it did, otherwise what it was doing instead. */
    private suspend fun waitPlaying(target: Dlna.Device, before: Int, viaPhone: Boolean): String? {
        var deadline = SystemClock.elapsedRealtime() + 15_000
        var extended = false
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(800)
            val s = io { Dlna.status(target) }.getOrNull()
            if (s != null) {
                last = s.state
                if (s.state == "PLAYING" || s.state == "PAUSED_PLAYBACK") return null
            }
            // The TV is fetching the stream from this phone: give it longer to start.
            if (!extended && viaPhone && StreamProxy.lanRequests() > before) { extended = true; deadline += 12_000 }
        }
        val hits = StreamProxy.lanRequests() - before
        val seen = when {
            !viaPhone -> ""
            hits == 0 -> "; the TV never asked this phone for the stream"
            else -> "; the TV asked this phone $hits time(s): ${StreamProxy.lanLog().takeLast(4).joinToString(", ")}"
        }
        return "TV state ${last.ifBlank { "unknown (no answer)" }}$seen"
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
        if (lgMode) {
            val play = state != "PLAYING"
            state = if (play) "PLAYING" else "PAUSED_PLAYBACK"
            scope.launch { runCatching { if (play) Lg.play() else Lg.pause() }.onFailure { App.toast("TV: ${it.message}") } }
            return
        }
        if (state == "PLAYING") { state = "PAUSED_PLAYBACK"; act { Dlna.pause(it) } }
        else { state = "TRANSITIONING"; act { Dlna.play(it) } }
    }

    fun seekTo(ms: Long) {
        if (lgMode) { App.toast("Seek with the TV remote"); return }
        val target = ms.coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        positionMs = target
        act { Dlna.seek(it, target) }
    }

    fun seekBy(ms: Long) = seekTo(positionMs + ms)

    fun changeVolume(step: Int) {
        val v = ((volume ?: return) + step).coerceIn(0, 100)
        volume = v
        if (lgMode) { scope.launch { runCatching { Lg.setVolume(v) } }; return }
        act { Dlna.setVolume(it, v) }
    }

    /** Ends the session; with [tellTv] the TV stops playing too. */
    fun end(tellTv: Boolean = true) {
        val d = device
        val wasLg = lgMode
        lgMode = false
        poll?.cancel()
        poll = null
        device = null
        connecting = false
        state = ""
        CastService.stop(App.context)
        scope.launch {
            if (wasLg) { if (tellTv) runCatching { Lg.stopApp() }; Lg.close() }
            else if (d != null && tellTv) withContext(Dispatchers.IO) { runCatching { Dlna.stop(d) } }
            // The TV may still be reading the last bytes: close the proxy a moment later.
            delay(1500)
            if (device == null) StreamProxy.stopLan()
        }
    }
}
