package com.music.spotui.ui.components

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.data.api.EditablePlaylist
import com.music.spotui.data.api.EditablePlaylists
import com.music.spotui.data.api.SpotifySync
import com.music.spotui.data.entity.SongsModel

/**
 * "Add to another playlist": one tap copies the track into a playlist the user picks.
 *
 * Add-only on purpose — the "Saved in" sheet stays the place where the track is toggled in
 * and out of several lists; this one is the short path asked for by the testers, with a
 * search field because a library folds into dozens of rows. The playlist the track is
 * already shown in is filtered out: adding it there would only make a duplicate entry.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun AddToPlaylistSheet(
    song: SongsModel,
    context: Context,
    onDismiss: () -> Unit,
    excludePlaylistId: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The last list at once, read again behind it.
    var playlists by remember { mutableStateOf(EditablePlaylists.cachedWithCovers()) }
    // This phone's own playlists (imported, then local): offered alongside the
    // account's — with no account they are all the playlists there are.
    var phoneLists by remember { mutableStateOf(EditablePlaylists.phonePlaylists()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        // Folders flattened: adding to a playlist does not need the folder tree.
        playlists = EditablePlaylists.loadWithCovers()
        phoneLists = EditablePlaylists.phonePlaylists()
    }
    // Filtered in one pass; accent-insensitive like the other "find" fields of the app.
    val shown = remember(playlists, phoneLists, query) {
        (playlists.orEmpty() + phoneLists).filter { it.id != excludePlaylistId && matchesFind(query, it.name) }
    }

    fun say(text: String) = Handler(Looper.getMainLooper()).post {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    fun addTo(playlist: EditablePlaylist) {
        onDismiss()
        when (playlist.source) {
            com.music.spotui.data.api.PlaylistSource.IMPORTED -> {
                // The phone's own store: the outcome is known at once, and said honestly.
                val added = com.music.spotui.data.local.ImportedPlaylists.add(
                    playlist.id, com.music.spotui.data.local.ImportedPlaylists.rowOf(song))
                say(context.getString(if (added) R.string.local_added_to else R.string.local_already_in_playlist, playlist.name))
            }
            com.music.spotui.data.api.PlaylistSource.LOCAL -> {
                val added = com.music.spotui.data.local.LocalPlaylists.addSong(playlist.id, song)
                say(context.getString(
                    if (added) R.string.local_added_to
                    else if (song.spotifyTrackId.isBlank()) R.string.playlist_change_refused
                    else R.string.local_already_in_playlist, playlist.name))
            }
            com.music.spotui.data.api.PlaylistSource.ACCOUNT -> SpotifySync.addTrackToPlaylist(context, playlist.id, song.spotifyTrackId) { ok ->
                // The write runs on the main dispatcher already; posted anyway so the toast
                // survives the sheet's teardown.
                say(if (ok) context.getString(R.string.local_added_to, playlist.name)
                    else context.getString(R.string.playlist_change_refused))
            }
        }
    }

    com.music.spotui.ui.components.LookSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = tone(0xFF1A1A1A),
    ) {
        Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
            Text(
                stringResource(R.string.context_menu_add_to_another_playlist),
                color = AppText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(20.dp, 4.dp, 20.dp, 12.dp),
            )
            FindInListField(
                query = query,
                onQuery = { query = it },
                hint = stringResource(R.string.add_to_playlist_search_hint),
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            val list = playlists
            when {
                // Rows first: the phone's own playlists are there at once, account or not.
                shown.isNotEmpty() -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp).padding(top = 4.dp)) {
                    items(shown.size) { i ->
                        val pl = shown[i]
                        PickRow(pl) { addTo(pl) }
                    }
                }
                list == null -> Text(
                    stringResource(R.string.loading_playlists),
                    color = androidx.compose.ui.graphics.Color.Gray, fontSize = 14.sp,
                    modifier = Modifier.padding(20.dp, 12.dp),
                )
                else -> Text(
                    stringResource(if (list.isEmpty() && phoneLists.isEmpty()) R.string.add_to_playlist_empty else R.string.add_to_playlist_no_match, query.trim()),
                    color = androidx.compose.ui.graphics.Color.Gray, fontSize = 14.sp,
                    modifier = Modifier.padding(20.dp, 12.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun PickRow(playlist: EditablePlaylist, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        GlideImage(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
            model = playlist.imageUrl,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(playlist.name, color = AppText, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                pluralStringResource(R.plurals.lbl_n_songs, playlist.trackCount, playlist.trackCount),
                color = androidx.compose.ui.graphics.Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.Add,
            contentDescription = null,
            tint = AppMuted,
            modifier = Modifier.size(22.dp),
        )
    }
}
