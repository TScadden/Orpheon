package com.music.spotui.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.data.local.LocalCovers
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/*
 * TEMPORARY DIAGNOSTIC — Scadify v25 "Art debug".
 *
 * This file exists only to capture ground truth for the local-album row-art
 * problem. Remove this whole file and the two album-screen menu hooks that
 * reference ArtDebugContent when the diagnostic is no longer needed. It does
 * not change how any cover is selected or displayed outside this sheet.
 */

private fun artDebugValue(value: String?): String = when {
    value == null -> "null"
    value.isBlank() -> "(blank)"
    else -> value
}

private fun artDebugRowResult(track: LocalTrack, pageAlbum: String, headerArt: String?): String =
    LocalCovers.effectiveArt(track.uri.toString(), track.artUri.toString(), pageAlbum, headerArt)

internal fun buildArtDebugReport(
    pageAlbum: String,
    tracks: List<LocalTrack>,
    albumCoverPath: String?,
    borrowedArt: String?,
    headerArt: String?,
): String = buildString {
    appendLine("ART DEBUG")
    appendLine("pageAlbum: ${artDebugValue(pageAlbum)}")
    appendLine("albumCover(pageAlbum): ${artDebugValue(albumCoverPath)}")
    appendLine("borrowedArt(pageAlbum): ${artDebugValue(borrowedArt)}")
    appendLine("headerArt: ${artDebugValue(headerArt)}")
    appendLine()
    appendLine("TRACKS (first 6)")
    if (tracks.isEmpty()) {
        appendLine("tracks: none")
        return@buildString
    }
    tracks.take(6).forEachIndexed { index, track ->
        val cachedArt = track.artUri.toString()
        val rowResult = artDebugRowResult(track, pageAlbum, headerArt)
        appendLine("[${index + 1}] title: ${artDebugValue(track.title)}")
        appendLine("t.album: ${artDebugValue(track.album)}")
        appendLine("t.artUri: ${artDebugValue(cachedArt)}")
        appendLine("ownArtExists(t.artUri): ${LocalCovers.ownArtExists(cachedArt)}")
        appendLine("row effectiveArt: ${artDebugValue(rowResult)}")
        appendLine("rowResult vs headerArt: ${if (rowResult == headerArt) "SAME" else "DIFF"}")
        if (index < tracks.take(6).lastIndex) appendLine()
    }
}.trimEnd()

internal suspend fun probeArtAddress(context: Context, address: String?): String = withContext(Dispatchers.IO) {
    when {
        address.isNullOrBlank() -> "unprobed"
        address.startsWith("content://") -> runCatching {
            context.contentResolver.openInputStream(Uri.parse(address))?.use { input ->
                // Reading one byte proves the provider actually returns image data,
                // rather than merely handing back a stream object.
                if (input.read() == -1) "OK (stream opened; no bytes)" else "OK"
            } ?: "FAIL (openInputStream returned null)"
        }.getOrElse { error -> "FAIL (${error.javaClass.simpleName}: ${error.message ?: "no message"})" }
        address.startsWith("file://") -> runCatching {
            val path = Uri.parse(address).path
            if (path.isNullOrBlank()) "FAIL (file URI has no path)" else "File.exists() = ${File(path).exists()}"
        }.getOrElse { error -> "FAIL (${error.javaClass.simpleName}: ${error.message ?: "no message"})" }
        !address.contains("://") -> runCatching { "File.exists() = ${File(address).exists()}" }
            .getOrElse { error -> "FAIL (${error.javaClass.simpleName}: ${error.message ?: "no message"})" }
        else -> "unprobed"
    }
}

@Composable
fun ArtDebugContent(
    pageAlbum: String,
    tracks: List<LocalTrack>,
    headerArt: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val albumCoverPath = remember(pageAlbum) { LocalCovers.albumCover(pageAlbum) }
    val borrowedArt = remember(pageAlbum) { LocalCovers.borrowedArt(pageAlbum) }
    val firstTrack = tracks.firstOrNull()
    val firstCachedArt = firstTrack?.artUri?.toString()
    val firstRowArt = remember(firstTrack?.id, firstTrack?.uri, firstCachedArt, pageAlbum, headerArt) {
        firstTrack?.let { artDebugRowResult(it, pageAlbum, headerArt) }
    }
    val report = remember(pageAlbum, tracks, albumCoverPath, borrowedArt, headerArt) {
        buildArtDebugReport(pageAlbum, tracks, albumCoverPath, borrowedArt, headerArt)
    }
    val probeTargets = remember(headerArt, firstCachedArt, firstRowArt) {
        listOf(
            "headerArt" to headerArt,
            "firstTrack.t.artUri" to firstCachedArt,
            "firstTrack.rowEffectiveArt" to firstRowArt,
        )
    }
    var probeResults by remember(probeTargets) { mutableStateOf<Map<String, String>?>(null) }
    LaunchedEffect(probeTargets) {
        probeResults = null
        val distinctAddresses = probeTargets.mapNotNull { (_, address) -> address?.takeIf { it.isNotBlank() } }.distinct()
        probeResults = distinctAddresses.associateWith { address -> probeArtAddress(context, address) }
    }
    val probeText = buildString {
        appendLine()
        appendLine()
        appendLine("LIVE PROBES")
        if (probeResults == null) {
            appendLine("probing…")
        } else {
            probeTargets.forEach { (label, address) ->
                val result = address?.let { probeResults?.get(it) } ?: "unprobed"
                appendLine("$label [${artDebugValue(address)}]: $result")
            }
        }
    }.trimEnd()

    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 720.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            "Art debug",
            color = Immersive.Text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(12.dp))
        SelectionContainer {
            Text(
                report + probeText,
                color = Immersive.Text,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "TILES",
            color = Immersive.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ArtDebugTile("HEADER", headerArt)
            ArtDebugTile("CACHED", firstCachedArt)
            ArtDebugTile("ROW", firstRowArt)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ArtDebugTile(label: String, address: String?) {
    Column {
        ImmersiveCover(address, Modifier.size(64.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = Immersive.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}
