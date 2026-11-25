package com.music.spotui.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.local.LocalAlbums
import com.music.spotui.data.local.LocalCovers
import com.music.spotui.data.local.LocalMusic
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.di.LocalPlayer
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One album of the phone's own files: the files sharing the opened track's
 * effective album name. Reached from "Show album" on a local file — a local
 * file's album is this set, never the catalog album of the same name. The
 * album's cover is set here (LocalCovers, keyed by artist + album); the rows
 * are Local Files rows: tap plays, heart likes, + adds to a playlist, ⋮ edits.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun LocalAlbumScreen(navController: NavController, artist: String, album: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<List<LocalTrack>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LocalTrack?>(null) }
    var adding by remember { mutableStateOf<LocalTrack?>(null) }
    var coverTick by remember { mutableStateOf(0) }
    LaunchedEffect(artist, album) {
        tracks = LocalAlbums.tracksFor(LocalMusic.tracks(context), artist, album)
    }
    val albumCover = remember(coverTick, artist, album) { LocalCovers.albumCover(artist, album) }
    val pickCover = com.music.spotui.ui.components.rememberPlaylistCoverPicker { uri ->
        scope.launch {
            val path = withContext(Dispatchers.IO) { LocalCovers.setAlbumCover(context, artist, album, uri) }
            if (path != null) coverTick++
            else Toast.makeText(context, context.getString(R.string.local_cover_failed), Toast.LENGTH_SHORT).show()
        }
    }
    editing?.let { t ->
        EditLocalTrackDialog(t.title, t.artist, t.album, onDismiss = { editing = null },
            uri = t.uri.toString(), artUri = t.artUri.toString()) { title, artist2, album2 ->
            com.music.spotui.data.local.LocalMetadata.edit(t.uri.toString(), title, artist2, album2)
            editing = null
            scope.launch { tracks = LocalAlbums.tracksFor(LocalMusic.tracks(context, refresh = true), artist, album) }
        }
    }
    adding?.let { t -> AddLocalToPlaylistSheet(t, onDismiss = { adding = null }) }
    if (menu) com.music.spotui.ui.components.LookSheet(onDismissRequest = { menu = false }, containerColor = tone(0xFF282828)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            LocalAlbumSheetRow(Icons.Outlined.Image, stringResource(R.string.local_change_cover)) { menu = false; pickCover() }
            if (albumCover != null) LocalAlbumSheetRow(Icons.Outlined.HideImage, stringResource(R.string.local_remove_cover)) {
                menu = false
                LocalCovers.clearAlbumCover(artist, album)
                coverTick++
            }
        }
    }
    val list = tracks.orEmpty()
    Box(Modifier.fillMaxSize().background(AppBackground)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp, 8.dp, 8.dp, 4.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(44.dp).clip(CircleShape).pressable { navController.navigateUp() },
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.lbl_back), tint = AppText) }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(album, color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(artist.takeIf { it.isNotBlank() },
                            pluralStringResource(R.plurals.lbl_n_songs_s, list.size, list.size.toString()).takeIf { tracks != null })
                            .joinToString(" • "),
                        color = AppMuted, fontSize = 13.sp,
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(44.dp).clip(CircleShape).pressable { menu = true },
                ) { Icon(Icons.Default.MoreVert, stringResource(R.string.lbl_more), tint = AppText) }
            }
            if (tracks != null && list.isEmpty()) {
                Text(stringResource(R.string.local_album_empty), color = AppMuted, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
            } else if (tracks != null) {
                LazyColumn(contentPadding = PaddingValues(bottom = com.music.spotui.ui.components.bottomBarsSpace()), modifier = Modifier.fillMaxSize()) {
                    item(key = "cover") {
                        GlideImage(
                            model = albumCover?.let { java.io.File(it) } ?: list.firstOrNull()?.artUri,
                            contentDescription = null, contentScale = ContentScale.Crop,
                            loading = placeholder(R.drawable.placeholder), failure = placeholder(R.drawable.local_files_cover),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                                .height(180.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    }
                    item(key = "play") {
                        Row(Modifier.fillMaxWidth().padding(16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(52.dp).pressable(0.92f) {
                                    LocalPlayer.play(list, 0, context = album)
                                }.clip(CircleShape).background(AppAccent),
                            ) {
                                Icon(painterResource(R.drawable.play_svgrepo_com), stringResource(R.string.lbl_play), tint = Color.Black, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                    itemsIndexed(list, key = { _, t -> t.id }) { index, t ->
                        val np = SongPlayer.nowPlaying.value
                        val current = LocalPlayer.active.value && np.uri == "local:${t.id}"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .pressable(0.98f) { LocalPlayer.play(list, index, context = album) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            GlideImage(
                                model = t.artUri, contentDescription = null, contentScale = ContentScale.Crop,
                                loading = placeholder(R.drawable.placeholder), failure = placeholder(R.drawable.local_files_cover),
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                            )
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(t.title, color = if (current) AppAccent else AppText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (t.artist.isNotBlank()) Text(t.artist, color = AppMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val liked = com.music.spotui.data.local.LocalLikes.isLiked(t)
                            Icon(
                                if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                stringResource(R.string.lbl_liked_songs), tint = if (liked) AppAccent else AppText,
                                modifier = Modifier.size(24.dp).pressable(0.92f) { com.music.spotui.data.local.LocalLikes.set(t, !liked) })
                            Spacer(Modifier.width(16.dp))
                            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.lbl_add_to_playlist), tint = AppText,
                                modifier = Modifier.size(24.dp).pressable(0.92f) { adding = t })
                            Spacer(Modifier.width(16.dp))
                            Icon(Icons.Default.MoreVert, stringResource(R.string.local_edit_title_artist), tint = AppText,
                                modifier = Modifier.size(24.dp).pressable(0.92f) { editing = t })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalAlbumSheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Icon(icon, null, tint = AppMuted, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = AppText, fontSize = 16.sp)
    }
}
