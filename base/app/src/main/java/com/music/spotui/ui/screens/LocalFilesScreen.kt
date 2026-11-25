package com.music.spotui.ui.screens

import com.music.spotui.ui.theme.headerTint
import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppAccent

import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.navigationBarsPadding
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.data.local.LocalMusic
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.di.LocalPlayer
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.theme.AppBackground

/** "Local Files": the device's own audio files, played by [LocalPlayer]. */
@OptIn(ExperimentalGlideComposeApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LocalFilesScreen(navController: NavController) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(LocalMusic.hasPermission(context)) }
    var askedOnce by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf<List<LocalTrack>?>(null) }
    var find by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok; askedOnce = true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = LocalMusic.hasPermission(context) }
    LaunchedEffect(granted) { if (granted) tracks = LocalMusic.tracks(context, refresh = true) }

    val np by SongPlayer.nowPlaying
    val playingLocal = LocalPlayer.active.value
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var adding by remember { mutableStateOf<LocalTrack?>(null) }
    adding?.let { t -> AddLocalToPlaylistSheet(t, onDismiss = { adding = null }) }
    // Multi-select (a long press starts it): the chosen tracks, by their MediaStore id.
    var selection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var addingSelected by remember { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()
    androidx.activity.compose.BackHandler(enabled = selecting) { selection = emptySet() }
    if (addingSelected) AddLocalTracksToPlaylistSheet(
        tracks = tracks.orEmpty().filter { it.id in selection },
        onDismiss = { addingSelected = false; selection = emptySet() },
    )
    var editing by remember { mutableStateOf<LocalTrack?>(null) }
    editing?.let { t ->
        EditLocalTrackDialog(t.title, t.artist, t.album, onDismiss = { editing = null }) { title, artist, album ->
            com.music.spotui.data.local.LocalMetadata.edit(t.uri.toString(), title, artist, album)
            editing = null
            scope.launch { tracks = LocalMusic.tracks(context, refresh = true) }
        }
    }
    var choosingFolders by remember { mutableStateOf(false) }
    if (choosingFolders) LocalFoldersSheet(
        onChanged = { scope.launch { tracks = LocalMusic.tracks(context) } },
        onDismiss = { choosingFolders = false },
    )

    Box(Modifier.fillMaxSize().background(AppBackground)) {
        when {
            !granted -> EmptyState(
                title = stringResource(R.string.lbl_local_files_empty),
                subtitle = stringResource(R.string.lbl_local_files_empty_note),
                button = stringResource(if (askedOnce) R.string.lbl_open_settings else R.string.lbl_allow),
            ) {
                if (askedOnce) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                else launcher.launch(LocalMusic.permission)
            }
            tracks?.isEmpty() == true -> EmptyState(
                title = stringResource(R.string.lbl_local_files_none),
                subtitle = stringResource(R.string.local_files_none_subtitle),
                button = null,
            ) {}
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(headerTint(Color(0xFF2E5C46), 0f), AppBackground)))
                            .statusBarsPadding().padding(horizontal = 16.dp),
                    ) {
                        Spacer(Modifier.height(56.dp))
                        Image(
                            painterResource(R.drawable.local_files_cover), null,
                            modifier = Modifier.align(Alignment.CenterHorizontally).size(200.dp).clip(RoundedCornerShape(6.dp)),
                        )
                        Text(stringResource(R.string.lbl_local_files), color = AppText, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 20.dp))
                        val n = tracks?.size ?: 0
                        Text(pluralStringResource(R.plurals.local_files_count, n, n), color = AppMuted, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = { choosingFolders = true },
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF7F7F7F)),
                            ) { Text(stringResource(R.string.local_folders_button), color = AppText, fontSize = 13.sp) }
                            Spacer(Modifier.weight(1f))
                            Icon(painterResource(R.drawable.ic_player_shuffle), stringResource(R.string.lbl_shuffle), tint = AppText,
                                modifier = Modifier.size(28.dp).pressable { tracks?.let { if (it.isNotEmpty()) LocalPlayer.play(it, it.indices.random(), shuffle = true) } })
                            Spacer(Modifier.width(20.dp))
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(56.dp)
                                    .pressable(0.92f) {
                                        when {
                                            playingLocal && SongPlayer.isPlaying() -> SongPlayer.pause()
                                            playingLocal -> SongPlayer.play()
                                            else -> tracks?.let { LocalPlayer.play(it, 0) }
                                        }
                                    }
                                    .clip(CircleShape).background(com.music.spotui.ui.theme.AppAccent),
                            ) {
                                com.music.spotui.ui.components.PlayPauseIcon(playingLocal && SongPlayer.isPlaying(),
                                    stringResource(R.string.lbl_play), tint = Color.Black, modifier = Modifier.size(26.dp))
                            }
                        }
                    }
                }
                item(key = "find") {
                    com.music.spotui.ui.components.FindInListField(find, { find = it }, stringResource(R.string.local_files_find_hint),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                val shown = tracks.orEmpty().filter { com.music.spotui.ui.components.matchesFind(find, it.title, it.artist, it.album) }
                if (shown.isEmpty() && find.isNotBlank()) item(key = "find-empty") {
                    com.music.spotui.ui.components.FindInListEmpty(stringResource(R.string.lbl_no_results, find.trim()))
                }
                itemsIndexed(shown, key = { _, t -> t.id }) { _, t ->
                    val current = playingLocal && np.uri == "local:${t.id}"
                    val chosen = t.id in selection
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        // The whole folder plays from this song, even when the list is filtered.
                        // While selecting, a tap chooses instead of playing, and a long press
                        // is what started the selection.
                        modifier = Modifier.fillMaxWidth()
                            .then(if (chosen) Modifier.background(com.music.spotui.ui.theme.AppAccent.copy(alpha = 0.14f)) else Modifier)
                            .combinedClickable(
                                onClick = {
                                    if (selecting) selection = com.music.spotui.ui.components.MultiSelect.toggle(selection, t.id)
                                    else tracks?.let { all -> LocalPlayer.play(all, all.indexOf(t).coerceAtLeast(0)) }
                                },
                                onLongClick = {
                                    selection = if (selecting) com.music.spotui.ui.components.MultiSelect.toggle(selection, t.id) else setOf(t.id)
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Box(Modifier.size(48.dp)) {
                            GlideImage(
                                model = t.artUri, contentDescription = null, contentScale = ContentScale.Crop,
                                loading = placeholder(R.drawable.placeholder), failure = placeholder(R.drawable.local_files_cover),
                                modifier = Modifier.matchParentSize().clip(RoundedCornerShape(4.dp)),
                            )
                            if (selecting) Icon(
                                if (chosen) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (chosen) com.music.spotui.ui.theme.AppAccent else AppText.copy(alpha = 0.55f),
                                modifier = Modifier.align(Alignment.BottomEnd).size(20.dp).background(AppBackground, CircleShape),
                            )
                        }
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(t.title, color = if (current) com.music.spotui.ui.theme.AppAccent else AppText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (t.artist.isNotBlank()) Text(t.artist, color = AppMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!selecting) {
                            // Liked on this phone (LocalLikes): the file lands in Liked Songs.
                            val liked = com.music.spotui.data.local.LocalLikes.isLiked(t)
                            Icon(
                                if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                stringResource(R.string.lbl_liked_songs), tint = if (liked) com.music.spotui.ui.theme.AppAccent else AppText,
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
                item { Spacer(Modifier.height(com.music.spotui.ui.components.bottomBarsSpace())) }
            }
        }
        if (selecting) ClassicSelectionBar(
            count = selection.size,
            onSelectAll = {
                val shown = tracks.orEmpty().filter { com.music.spotui.ui.components.matchesFind(find, it.title, it.artist, it.album) }
                selection = com.music.spotui.ui.components.MultiSelect.selectAll(selection, shown.map { it.id })
            },
            onAdd = { addingSelected = true },
            onRemove = null,
            onCancel = { selection = emptySet() },
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = com.music.spotui.ui.components.bottomBarsSpace()),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.statusBarsPadding().padding(12.dp).size(40.dp).clip(CircleShape)
                .background(AppBackground.copy(alpha = 0.4f)).pressable { navController.navigateUp() },
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.lbl_back), tint = AppText)
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String, button: String?, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
    ) {
        Image(painterResource(R.drawable.local_files_cover), null, modifier = Modifier.size(120.dp).clip(RoundedCornerShape(6.dp)))
        Text(title, color = AppText, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp))
        Text(subtitle, color = AppMuted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        if (button != null) Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(top = 24.dp).height(48.dp).pressable(0.96f, onClick = onClick)
                .clip(RoundedCornerShape(50)).background(AppText).padding(horizontal = 28.dp),
        ) {
            Text(button, color = com.music.spotui.ui.theme.AppBackground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}


/** The phone's music folders, each shown in Local Files or left out (WhatsApp audio…). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun LocalFoldersSheet(onChanged: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var folders by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { folders = LocalMusic.folders(context) }
    com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, containerColor = tone(0xFF282828)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.local_folders_title), color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp))
            Text(stringResource(R.string.local_folders_detail), color = AppMuted, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            LazyColumn {
                items(folders.size) { i ->
                    val (folder, count) = folders[i]
                    @Suppress("UNUSED_EXPRESSION") version
                    val shown = !com.music.spotui.data.local.LocalFolders.isExcluded(context, folder)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            val name = folder.trimEnd('/').substringAfterLast('/').ifBlank { folder.ifBlank { "/" } }
                            Text(name, color = AppText, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text("$folder · ${pluralStringResource(R.plurals.local_files_count, count, count)}", color = Color(0xFF9A9A9A),
                                fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        androidx.compose.material3.Switch(
                            checked = shown,
                            onCheckedChange = {
                                com.music.spotui.data.local.LocalFolders.set(context, folder, it)
                                version++
                                onChanged()
                            },
                            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = AppAccent),
                        )
                    }
                }
            }
        }
    }
}
