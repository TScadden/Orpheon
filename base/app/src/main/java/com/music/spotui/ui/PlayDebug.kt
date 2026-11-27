package com.music.spotui.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.extensions.PlayAttempt
import app.myspoti.extensions.TrackInfo
import com.music.spotui.data.local.ImportedSong
import com.music.spotui.extensions.ExtensionAudio
import com.music.spotui.extensions.Extensions
import com.music.spotui.ui.components.LookSheet
import com.music.spotui.ui.immersive.Immersive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * DIAGNOSTIC — Scadify v29 "Play debug".
 *
 * The receipt for an imported song that will not play: what the row stores, the
 * TrackInfo a tap builds from it, what each installed audio source answered the
 * last time the song was tapped (Extensions.attempts, recorded by the ask
 * itself), and the route the tap finally took (ExtensionAudio's journal). One
 * screenshot of this sheet is the whole diagnosis — the two v27/v28 theories
 * died for want of exactly these values on the phone that failed.
 *
 * This sheet stays in the build until Tyson says the import saga is closed;
 * it changes nothing about how any song is chosen or played.
 */

private fun playDebugValue(value: String?): String = when {
    value == null -> "null"
    value.isBlank() -> "(blank)"
    else -> value
}

/** An outcome key of an attempt's ask, in the words the sheet shows. */
internal fun playDebugOutcome(ask: PlayAttempt.Ask): String = buildString {
    append(
        when (ask.outcome) {
            "found" -> "claimed it"
            "not-found" -> "refused: not found"
            "skipped" -> "not asked"
            else -> ask.outcome
        },
    )
    ask.skippedReason?.let { append(" ($it)") }
    ask.detail?.let { append(" — $it") }
    if (ask.outcome != "skipped") append(" (${ask.ms} ms)")
}

internal fun buildPlayDebugReport(
    row: ImportedSong,
    info: TrackInfo,
    ask: ExtensionAudio.AskRecord?,
    attempt: PlayAttempt?,
    sources: List<Pair<String, Boolean>>,
    names: Map<String, String>,
): String = buildString {
    appendLine("PLAY DEBUG")
    appendLine("song: ${row.title} — ${row.artist}")
    appendLine()
    appendLine("STORED ROW")
    appendLine("spotifyTrackId: ${playDebugValue(row.spotifyTrackId)}")
    appendLine("catalogKey: ${playDebugValue(row.catalogKey)}")
    appendLine("isrc: ${playDebugValue(row.isrc)}")
    appendLine("asin: ${playDebugValue(row.asin)}")
    appendLine("durationMs: ${row.durationMs}")
    appendLine("playId: ${playDebugValue(row.playId)}")
    appendLine()
    appendLine("TRACKINFO THE TAP BUILDS")
    appendLine("id: ${playDebugValue(info.id)}")
    appendLine("uri: ${playDebugValue(info.uri)}")
    appendLine("title: ${playDebugValue(info.title)}")
    appendLine("artist: ${playDebugValue(info.artist)}")
    appendLine("album: ${playDebugValue(info.album)}")
    appendLine("durationMs: ${info.durationMs}")
    appendLine("isrc: ${playDebugValue(info.isrc)}")
    appendLine()
    appendLine("AUDIO SOURCES INSTALLED")
    if (sources.isEmpty()) appendLine("(none)")
    sources.forEach { (name, enabled) -> appendLine("$name: ${if (enabled) "on" else "off"}") }
    appendLine()
    appendLine("LAST TAP")
    if (ask == null) {
        appendLine("not tapped since the app started")
    } else {
        val at = SimpleDateFormat("h:mm a", Locale.US).format(Date(ask.at))
        appendLine("at: $at")
        appendLine("asked durationMs: ${ask.info.durationMs}")
        appendLine("route: ${ask.route}")
        ask.providerId?.let { appendLine("answered by: ${names[it] ?: it}") }
    }
    appendLine()
    appendLine("WHAT EACH SOURCE ANSWERED (that tap)")
    val asked = attempt?.asked.orEmpty()
    if (asked.isEmpty()) {
        appendLine("(no ask recorded)")
    } else {
        asked.forEach { a ->
            appendLine("${names[a.extension] ?: a.extension}: ${playDebugOutcome(a)}")
        }
        appendLine("chosen: ${attempt?.chosen?.let { names[it] ?: it } ?: "none"}")
        attempt?.fallback?.let { appendLine("fallback: $it") }
        attempt?.error?.let { appendLine("error: $it") }
    }
}.trimEnd()

@Composable
fun PlayDebugContent(row: ImportedSong, modifier: Modifier = Modifier) {
    val info = remember(row) {
        Extensions.trackInfo(row.playId, row.title, row.artist, row.album, row.durationMs, row.coverUrl, row.isrc)
    }
    val ask = remember(row.playId) { ExtensionAudio.lastAsk(row.playId) }
    val attempt = remember(row.playId) { Extensions.attempts.latest(row.playId) }
    val sources = remember { Extensions.audioSources().map { it.name to it.enabled } }
    val names = remember { Extensions.registry.value.extensions.associate { it.id to it.name } }
    val report = remember(row, info, ask, attempt, sources, names) {
        buildPlayDebugReport(row, info, ask, attempt, sources, names)
    }
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 720.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            "Play debug",
            color = Immersive.Text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(12.dp))
        SelectionContainer {
            Text(
                report,
                color = Immersive.Text,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** The Play debug sheet, shared by both looks' song menus (as the credits sheet is). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlayDebugSheet(row: ImportedSong, onDismiss: () -> Unit) {
    LookSheet(onDismissRequest = onDismiss, containerColor = com.music.spotui.ui.theme.tone(0xFF282828)) {
        PlayDebugContent(row)
    }
}
