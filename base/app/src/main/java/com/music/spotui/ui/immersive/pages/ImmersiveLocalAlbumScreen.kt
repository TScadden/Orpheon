package com.music.spotui.ui.immersive.pages

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.music.spotui.data.local.LocalAlbums
import com.music.spotui.data.local.LocalCovers
import com.music.spotui.data.local.LocalItem
import com.music.spotui.data.local.LocalMusic
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.di.LocalPlayer
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.ArtDebugContent
import com.music.spotui.ui.immersive.components.ImmersiveCover
import com.music.spotui.ui.immersive.components.ImmersiveListRow
import com.music.spotui.ui.immersive.components.ImmersiveMessageState
import com.music.spotui.ui.immersive.components.ImmersivePlayShuffleButtons
import com.music.spotui.ui.immersive.shell.ImmersiveBarAction
import com.music.spotui.ui.immersive.sheets.ImmersiveSheet
import com.music.spotui.ui.immersive.sheets.ImmersiveSheetRow
import com.music.spotui.ui.immersive.Immersive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One album of the phone's own files, Immersive look: the files sharing the
 * opened track's effective album name (see the Classic page of the same name).
 * The album's cover is changed from the ⋯ menu (LocalCovers); a row's ⋯ edits
 * the file's names and its own cover, as in Local Files.
 */
@Composable
fun ImmersiveLocalAlbumScreen(navController: NavController, artist: String, album: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<List<LocalTrack>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var artDebug by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LocalItem?>(null) }
    var coverTick by remember { mutableStateOf(0) }
    LaunchedEffect(artist, album) {
        tracks = LocalAlbums.tracksFor(LocalMusic.tracks(context), album)
    }
    val albumCover = remember(coverTick, album) { LocalCovers.albumCover(album) }
    val pickCover = com.music.spotui.ui.components.rememberPlaylistCoverPicker { uri ->
        scope.launch {
            val path = withContext(Dispatchers.IO) { LocalCovers.setAlbumCover(context, album, uri) }
            if (path != null) coverTick++
            else Toast.makeText(context, context.getString(R.string.local_cover_failed), Toast.LENGTH_SHORT).show()
        }
    }
    if (menu) ImmersiveSheet(onDismiss = { menu = false }) {
        ImmersiveSheetRow(Icons.Rounded.Image, stringResource(R.string.local_change_cover), onClick = { menu = false; pickCover() })
        if (albumCover != null) ImmersiveSheetRow(Icons.Rounded.HideImage, stringResource(R.string.local_remove_cover), onClick = {
            menu = false
            LocalCovers.clearAlbumCover(album)
            coverTick++
        })
        // TEMPORARY DIAGNOSTIC (v25): remove with ArtDebug.kt.
        ImmersiveSheetRow(Icons.Rounded.BugReport, "Art debug", onClick = { menu = false; artDebug = true })
    }
    editing?.let { item ->
        com.music.spotui.ui.screens.EditLocalItemDialog(item) {
            editing = null
            scope.launch { tracks = LocalAlbums.tracksFor(LocalMusic.tracks(context, refresh = true), album) }
        }
    }
    val list = tracks.orEmpty()
    // The header's art, computed once: the rows fall back to it, so a track with no
    // art of its own shows the same cover the header shows (LocalCovers.effectiveArt).
    val headerArt = albumCover?.let { java.io.File(it).toURI().toString() } ?: LocalCovers.borrowedArt(album) ?: list.firstOrNull()?.artUri?.toString()
    // TEMPORARY DIAGNOSTIC (v25): remove with ArtDebug.kt.
    if (artDebug) ImmersiveSheet(onDismiss = { artDebug = false }, scrollable = false, maxHeightFraction = 0.92f) {
        ArtDebugContent(pageAlbum = album, tracks = list, headerArt = headerArt)
    }
    val np by SongPlayer.nowPlaying
    // Same header rule as the Classic page: an artist is named only when every
    // file shares that one artist string; mixed credits read Various artists.
    val sharedArtist = if (tracks != null) LocalAlbums.sharedArtist(list) else null
    val artistPart = when {
        tracks == null || list.isEmpty() -> null
        sharedArtist == null -> stringResource(R.string.local_various_artists)
        sharedArtist.isBlank() -> null
        else -> sharedArtist
    }
    ImmersivePage(
        title = album,
        subtitle = listOfNotNull(artistPart,
            pluralStringResource(R.plurals.lbl_n_songs_s, list.size, list.size.toString()).takeIf { tracks != null })
            .joinToString(" • ").takeIf { it.isNotBlank() },
        onBack = { navController.navigateUp() },
        actions = {
            ImmersiveBarAction(Icons.Rounded.MoreHoriz, stringResource(R.string.lbl_more), onClick = { menu = true })
        },
    ) {
        if (tracks != null && list.isEmpty()) {
            item(key = "empty") { ImmersiveMessageState(stringResource(R.string.local_album_empty)) }
            return@ImmersivePage
        }
        if (tracks == null) return@ImmersivePage
        item(key = "cover") {
            Box(Modifier.fillMaxWidth().padding(horizontal = Immersive.Gutter, vertical = 8.dp), contentAlignment = Alignment.Center) {
                ImmersiveCover(
                    headerArt,
                    Modifier.fillMaxWidth(0.62f).aspectRatio(1f),
                    Immersive.CoverLarge,
                )
            }
        }
        item(key = "buttons") {
            ImmersivePlayShuffleButtons(
                onPlay = {
                    val playingHere = LocalPlayer.active.value && LocalPlayer.contextName == album
                    when {
                        playingHere && SongPlayer.isPlaying() -> SongPlayer.pause()
                        playingHere -> SongPlayer.play()
                        else -> LocalPlayer.play(list, 0, context = album)
                    }
                },
                onShuffle = { LocalPlayer.play(list, list.indices.random(), shuffle = true, context = album) },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(list.size, key = { i -> list[i].id }) { index ->
            val t = list[index]
            val current = LocalPlayer.active.value && np.uri == "local:${t.id}"
            ImmersiveListRow(
                title = t.title,
                subtitle = t.artist.ifBlank { null },
                // Through the art chain, read under this page's album: a track with no
                // art of its own shows the album's set or borrowed cover — and, as the
                // last resort, the header's own art — not a placeholder.
                imageUrl = LocalCovers.effectiveArt(t.uri.toString(), t.artUri.toString(), album, headerArt),
                round = false,
                playing = current && SongPlayer.isPlaying(),
                onClick = { LocalPlayer.play(list, index, context = album) },
                onMore = { editing = LocalItem.of(t) },
            )
        }
    }
}
