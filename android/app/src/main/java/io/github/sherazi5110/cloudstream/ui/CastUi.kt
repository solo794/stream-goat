package io.github.sherazi5110.cloudstream.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.cast.Cast
import io.github.sherazi5110.cloudstream.cast.Dlna
import io.github.sherazi5110.cloudstream.player.PlayerController

/**
 * Covers the player while casting. Before a TV is picked: the TVs found on the network (DLNA) and a shortcut
 * to the system's screen mirroring. Once one plays: a remote for it (pause, seek, volume, stop).
 */
@Composable
fun CastPanel(c: PlayerController, onClose: () -> Unit) {
    val context = LocalContext.current
    val device = Cast.device
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (Cast.device == null) Cast.scan() }
    LaunchedEffect(device, Cast.devices.size) { runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).verticalScroll(rememberScrollState())) {
            if (device == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Cast, null, tint = Color.White, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Cast to a TV", style = MaterialTheme.typography.headlineSmall)
                }
                Text(
                    when {
                        Cast.connecting -> Cast.step.ifBlank { "Connecting…" }
                        Cast.scanning -> "Looking for TVs on this Wi-Fi…"
                        Cast.devices.isEmpty() -> "No TV found. The TV and this device must be on the same Wi-Fi, and the TV switched on."
                        else -> "Pick a TV. It plays the stream itself; this device becomes the remote."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                if (Cast.scanning || Cast.connecting) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp), color = Palette.accent, trackColor = Color.White.copy(alpha = 0.15f))
                Cast.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFF8A80), modifier = Modifier.padding(bottom = 4.dp)) }
                if (Cast.log.isNotEmpty()) Text(Cast.log.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(bottom = 8.dp))

                Cast.devices.forEachIndexed { i, d ->
                    DeviceRow(d, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                        val link = c.current ?: c.ranked().firstOrNull()
                        if (link == null) App.toast("No source to cast yet")
                        else {
                            if (c.player.isPlaying) c.player.pause()
                            Cast.start(d, link, castTitle(c), c.time, c.isLive)
                        }
                    }
                }

                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Search again", Icons.Filled.Refresh, if (Cast.devices.isEmpty()) Modifier.focusRequester(first) else Modifier, primary = Cast.devices.isEmpty(), compact = true) { Cast.scan() }
                    // Mirroring (Miracast / Google Cast) is the system's own feature: open its screen.
                    PillButton("Screen mirroring", null, primary = false, compact = true) {
                        val opened = listOf("android.settings.CAST_SETTINGS", "android.settings.WIRELESS_SETTINGS").any { action ->
                            runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
                        }
                        if (!opened) App.toast("This device has no screen mirroring settings")
                    }
                    PillButton("Close", null, primary = false, compact = true) { Cast.cancel(); onClose() }
                }
            } else {
                Text("Casting to ${device.name}", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Cast.title, style = MaterialTheme.typography.titleMedium, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Text(
                    when (Cast.state) {
                        "PLAYING" -> "Playing"
                        "PAUSED_PLAYBACK" -> "Paused"
                        "STOPPED", "NO_MEDIA_PRESENT" -> "Stopped on the TV"
                        else -> "Loading on the TV…"
                    },
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))

                if (!Cast.live) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(clock(Cast.positionMs / 1000.0), style = MaterialTheme.typography.titleSmall)
                    var dragging by remember { mutableStateOf<Float?>(null) }
                    Slider(
                        value = dragging ?: if (Cast.durationMs > 0) (Cast.positionMs.toFloat() / Cast.durationMs).coerceIn(0f, 1f) else 0f,
                        onValueChange = { dragging = it },
                        onValueChangeFinished = { dragging?.let { if (Cast.durationMs > 0) Cast.seekTo((it * Cast.durationMs).toLong()) }; dragging = null },
                        enabled = Cast.durationMs > 0,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Palette.accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    Text(clock(Cast.durationMs / 1000.0), style = MaterialTheme.typography.titleSmall)
                } else Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!Cast.live) PillButton("10", Icons.Filled.Replay10, primary = false, compact = true) { Cast.seekBy(-10_000) }
                    PillButton(if (Cast.playing) "Pause" else "Play", if (Cast.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, Modifier.focusRequester(first)) { Cast.togglePause() }
                    if (!Cast.live) PillButton("10", Icons.Filled.Forward10, primary = false, compact = true) { Cast.seekBy(10_000) }
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cast.volume?.let { v ->
                        PillButton("Vol −", null, primary = false, compact = true) { Cast.changeVolume(-5) }
                        Text("$v", style = MaterialTheme.typography.titleSmall)
                        PillButton("Vol +", null, primary = false, compact = true) { Cast.changeVolume(5) }
                    }
                    PillButton("Stop casting", Icons.Filled.Stop, primary = false, compact = true) {
                        // Carry on here from where the TV got to.
                        val at = Cast.positionMs
                        Cast.end()
                        onClose()
                        if (!c.isLive && at > 0) c.seekTo(at)
                        c.player.play()
                    }
                }
                Cast.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }
}

private fun castTitle(c: PlayerController): String =
    if (c.playables.size > 1) "${c.response.name} · ${c.playable.label}" else c.response.name

@Composable
private fun DeviceRow(d: Dlna.Device, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp).card(RoundedCornerShape(10.dp), focusScale = 1.02f, onClick = onClick)
        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Cast, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(d.maker, d.host).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        }
    }
}
