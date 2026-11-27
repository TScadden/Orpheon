package com.music.spotui.ui.screens

import com.music.spotui.ui.theme.AppDestructive
import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.data.api.LibraryEdits
import com.music.spotui.data.api.LibraryFolders
import com.music.spotui.data.api.Response
import com.music.spotui.data.api.SpotifySync
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.api.PlaylistImport
import com.music.spotui.data.api.PlaylistLink
import com.music.spotui.data.api.PlaylistLinks
import com.music.spotui.data.local.ImportedPlaylists
import com.music.spotui.data.local.LocalItem
import com.music.spotui.data.local.LocalPlaylists
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.di.Graph
import com.music.spotui.di.LocalPlayer
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.SwipeToRemove
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.components.SkeletonKind
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.navigation.importedPlaylistRoute
import com.music.spotui.ui.navigation.localPlaylistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.components.ProfileAvatar
import com.music.spotui.ui.theme.AppBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Green: Color get() = com.music.spotui.ui.theme.AppAccent
private val SheetColor: androidx.compose.ui.graphics.Color get() = tone(0xFF282828)

/** A name prompt (new playlist / folder, rename). */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SheetColor,
        title = { Text(title, color = AppText) },
        text = {
            TextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = tone(0xFF3A3A3A), unfocusedContainerColor = tone(0xFF3A3A3A),
                    focusedTextColor = AppText, unfocusedTextColor = AppText, cursorColor = Green,
                    focusedIndicatorColor = Green, unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(confirm, color = Green) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.lbl_cancel), color = AppText) }
        },
    )
}

/**
 * "Import playlist": paste a public playlist link, read its songs, name the list, keep it on
 * this phone. Spotify links are read from Spotify's public pages (no account); an Amazon
 * Music link is read from Amazon's web player rendered on this phone, in a page the user
 * watches — a public link reads signed out, and when Amazon gates the playlist behind a
 * sign-in (a private one is invisible signed out) the user signs in on Amazon's own page
 * first (see AmazonPlaylistReader). A playlist file from a transfer service (Soundiiz,
 * TuneMyMusic, FreeYourMusic) can be picked instead.
 */
@Composable
fun ImportPlaylistDialog(navController: NavController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var fetched by remember { mutableStateOf<PlaylistImport.Imported?>(null) }
    /** Where [fetched] came from, kept with the playlist: the link, or "" for a file. */
    var fetchedSource by remember { mutableStateOf("") }
    /** Which source [fetched] came by — "spotify", "amazon" or "csv" — kept with the playlist as its origin. */
    var fetchedOrigin by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var amazon by remember { mutableStateOf<com.music.spotui.data.api.AmazonReadSession?>(null) }
    /** The fetched read's journal on screen (the Read details affordance), when asked for. */
    var showReadDetails by remember { mutableStateOf(false) }
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = tone(0xFF3A3A3A), unfocusedContainerColor = tone(0xFF3A3A3A),
        focusedTextColor = AppText, unfocusedTextColor = AppText, cursorColor = Green,
        focusedIndicatorColor = Green, unfocusedIndicatorColor = Color.Transparent,
    )
    // Whatever was read, from whichever source: complete its thin rows from the app's
    // own catalog before it is shown or kept (CatalogCompletion), then offer the name.
    fun complete(read: PlaylistImport.Imported, source: String, origin: String) {
        scope.launch {
            val done = runCatching {
                read.copy(songs = com.music.spotui.data.api.CatalogCompletion.completeFromCatalog(read.songs))
            }.getOrDefault(read)
            fetched = done
            fetchedSource = source
            fetchedOrigin = origin
            name = done.name.ifBlank { context.getString(R.string.import_default_name) }
            busy = false
        }
    }
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            busy = true; error = null
            scope.launch {
                val parsed = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = context.contentResolver.openInputStream(uri)
                            ?.bufferedReader(Charsets.UTF_8)?.readText()
                        text?.let { com.music.spotui.data.api.PlaylistCsv.parse(it, pickedFileName(context, uri)) }
                    }.getOrNull()
                }
                if (parsed == null) {
                    error = context.getString(R.string.import_file_bad)
                    busy = false
                } else complete(PlaylistImport.Imported(parsed.name, parsed.songs, parsed.songs.size), "", "csv")
            }
        }
    }
    fun fetch() {
        when (val parsed = PlaylistLinks.parse(link)) {
            is PlaylistLink.Spotify -> {
                busy = true; error = null
                scope.launch {
                    // A reading Spotify's API refused part of arrives thin (no
                    // covers, albums or ISRCs): complete() fills it from the catalog.
                    runCatching { PlaylistImport.fetchSpotify(parsed.id) }
                        .onSuccess { complete(it, link.trim(), "spotify") }
                        .onFailure { error = context.getString(R.string.import_failed); busy = false }
                }
            }
            is PlaylistLink.Amazon -> {
                busy = true; error = null
                amazon = com.music.spotui.data.api.AmazonReadSession(context, parsed.url)
            }
            PlaylistLink.Unknown -> error = context.getString(R.string.import_bad_link)
        }
    }
    amazon?.let { session ->
        AmazonImportDialog(
            session = session,
            onImported = { imported -> amazon = null; complete(imported, session.url, "amazon") },
            // No error text here: the Amazon dialog itself showed the read's failure
            // readout (what the page actually was) before it was closed.
            onFailed = { amazon = null; busy = false },
            onCancel = { amazon = null; busy = false },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SheetColor,
        title = { Text(stringResource(R.string.import_title), color = AppText) },
        text = {
            Column {
                val got = fetched
                if (got == null) {
                    TextField(
                        value = link, onValueChange = { link = it; error = null }, singleLine = true,
                        label = { Text(stringResource(R.string.import_link_hint), color = AppMuted) },
                        colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        enabled = !busy,
                        onClick = { pickFile.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel")) },
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text(stringResource(R.string.import_file_button), color = Green, fontSize = 14.sp) }
                } else {
                    TextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        label = { Text(stringResource(R.string.lbl_playlist), color = AppMuted) },
                        colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                    )
                    // What the read may honestly say about its own size. A page read
                    // whose source states its total on the page (Amazon) and never
                    // stated it has no "of M" to print: the haul may be only part of
                    // the list, and the line says exactly that — never "N of N",
                    // which dresses a window up as the whole playlist.
                    if (got.totalStated && got.declaredTotal == null) {
                        Text(stringResource(R.string.import_total_unknown, got.songs.size), color = AppMuted, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 10.dp))
                    } else {
                        val total = got.declaredTotal ?: got.total
                        Text(stringResource(R.string.import_count, got.songs.size, total), color = AppMuted, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 10.dp))
                        // A read that gathered fewer songs than its source declares says
                        // so plainly: a partial import is never dressed as a complete one.
                        if (total > got.songs.size) {
                            Text(stringResource(R.string.import_shortfall, got.songs.size, total), color = AppMuted, fontSize = 13.sp,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    // The read's journal, when it kept one (an Amazon read): every
                    // pass's scrollers, movement and verdict, copyable — the
                    // diagnosis that travels with a short haul.
                    got.readDetails?.let {
                        TextButton(
                            onClick = { showReadDetails = true },
                            modifier = Modifier.padding(top = 2.dp),
                        ) { Text(stringResource(R.string.import_read_details), color = Green, fontSize = 13.sp) }
                    }
                }
                if (busy) Text(stringResource(R.string.loading_playlists), color = AppMuted, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp))
                error?.let { Text(it, color = AppDestructive, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
            }
        },
        confirmButton = {
            val got = fetched
            if (got == null) TextButton(enabled = link.isNotBlank() && !busy, onClick = { fetch() }) {
                Text(stringResource(R.string.import_fetch), color = Green)
            } else TextButton(enabled = name.isNotBlank(), onClick = {
                val p = ImportedPlaylists.create(name, fetchedSource, got.songs, fetchedOrigin)
                onDismiss()
                navController.navigate(importedPlaylistRoute(p.id))
            }) { Text(stringResource(R.string.import_button), color = Green) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.lbl_cancel), color = AppText) }
        },
    )
    // The fetched read's journal, on request: the same copyable readout the
    // failure path shows, kept for a completed read too (see AmazonReadout).
    if (showReadDetails) {
        val details = fetched?.readDetails
        AlertDialog(
            onDismissRequest = { showReadDetails = false },
            containerColor = SheetColor,
            title = { Text(stringResource(R.string.import_read_details), color = AppText) },
            text = {
                if (details != null) {
                    SelectionContainer {
                        Text(
                            details,
                            color = AppText, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showReadDetails = false }) {
                    Text(stringResource(R.string.lbl_close), color = Green)
                }
            },
        )
    }
}

/** A picked file's display name without its extension: a playlist file's own name for its list. */
private fun pickedFileName(context: android.content.Context, uri: android.net.Uri): String {
    var display: String? = null
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) display = c.getString(0)
        }
    }
    val raw = display ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    return raw.substringBeforeLast('.', raw)
}

/**
 * An Amazon link being read: the web player at full size from the start, with the
 * read's live status above it, so the user watches the same page the read reads.
 * When Amazon answers with a wall a tap can clear — a bot check, or a sign-in gate
 * (a playlist that is not public is invisible signed out) — the status says so and
 * Continue resumes the read after the user clears it on Amazon's own page. A read
 * that still fails ends here, showing its readout (what the page actually was),
 * with the CSV route as a secondary line — not a guessed-at cause.
 */
@Composable
private fun AmazonImportDialog(
    session: com.music.spotui.data.api.AmazonReadSession,
    onImported: (PlaylistImport.Imported) -> Unit,
    onFailed: () -> Unit,
    onCancel: () -> Unit,
) {
    androidx.compose.runtime.DisposableEffect(session) { onDispose { session.destroy() } }
    androidx.compose.runtime.LaunchedEffect(session) { session.run() }
    androidx.compose.runtime.LaunchedEffect(session.phase) {
        if (session.phase == com.music.spotui.data.api.AmazonReadSession.Phase.DONE) {
            session.imported?.let(onImported)
        }
    }
    val failed = session.phase == com.music.spotui.data.api.AmazonReadSession.Phase.FAILED
    val needsUser = session.phase == com.music.spotui.data.api.AmazonReadSession.Phase.NEEDS_USER
    val needsSignIn = needsUser && session.stage == com.music.spotui.data.api.AmazonStage.NEEDS_SIGN_IN
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = SheetColor,
        title = {
            Text(
                stringResource(
                    when {
                        failed -> R.string.import_amazon_failed_title
                        needsSignIn -> R.string.import_amazon_signin_title
                        needsUser -> R.string.import_amazon_check_title
                        else -> R.string.import_title
                    },
                ),
                color = AppText,
            )
        },
        text = {
            if (failed) {
                Column {
                    session.failureReadout?.let { readout ->
                        SelectionContainer {
                            Text(
                                readout.text(),
                                color = AppText, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.import_amazon_csv_hint),
                        color = AppMuted, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            } else {
                Column {
                    Text(amazonStatusText(session), color = AppMuted, fontSize = 14.sp)
                    Box(
                        Modifier.fillMaxWidth().padding(top = 12.dp)
                            .height(420.dp)
                            .clipToBounds(),
                    ) {
                        androidx.compose.ui.viewinterop.AndroidView(
                            factory = { session.webView },
                            modifier = Modifier.fillMaxWidth().height(440.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                failed -> TextButton(onClick = onFailed) {
                    Text(stringResource(R.string.lbl_close), color = Green)
                }
                needsUser -> TextButton(onClick = { session.userContinue() }) {
                    Text(stringResource(R.string.import_amazon_continue), color = Green)
                }
            }
        },
        dismissButton = {
            if (!failed) TextButton(onClick = onCancel) { Text(stringResource(R.string.lbl_cancel), color = AppText) }
        },
    )
}

/** The status line over the reading page, from the read's own stage and counts. */
@Composable
private fun amazonStatusText(session: com.music.spotui.data.api.AmazonReadSession): String =
    when (session.stage) {
        com.music.spotui.data.api.AmazonStage.LOADING_PAGE ->
            stringResource(R.string.import_amazon_status_loading)
        com.music.spotui.data.api.AmazonStage.WAITING_FOR_LIST ->
            stringResource(R.string.import_amazon_status_waiting)
        com.music.spotui.data.api.AmazonStage.FOUND_SONGS ->
            stringResource(R.string.import_amazon_status_found, session.foundCount)
        com.music.spotui.data.api.AmazonStage.COLLECTING ->
            // An unstated total is not a denominator: "9 of 0" implied a whole
            // the read never verified. Until the page states one, the count
            // stands alone.
            if (session.declaredTotal > 0) {
                stringResource(R.string.import_amazon_status_collecting, session.foundCount, session.declaredTotal)
            } else {
                stringResource(R.string.import_amazon_status_found, session.foundCount)
            }
        com.music.spotui.data.api.AmazonStage.NEEDS_CAPTCHA ->
            stringResource(R.string.import_amazon_check_body)
        com.music.spotui.data.api.AmazonStage.NEEDS_SIGN_IN ->
            stringResource(R.string.import_amazon_signin_body)
        com.music.spotui.data.api.AmazonStage.DONE ->
            stringResource(R.string.import_amazon_status_found, session.foundCount)
        com.music.spotui.data.api.AmazonStage.FAILED -> ""
    }

@Composable
private fun SheetRow(icon: ImageVector, label: String, detail: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Icon(icon, null, tint = AppMuted, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, color = AppText, fontSize = 16.sp)
            if (detail != null) Text(detail, color = AppMuted, fontSize = 12.sp)
        }
    }
}

/** The Library's "+": a Spotify playlist, a Spotify folder, or a playlist on this phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryCreateSheet(navController: NavController, onDismiss: () -> Unit, onLibraryChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<String?>(null) } // "playlist" | "folder" | "local"
    // A .scadifyplaylist file someone shared: picked here, imported through the share flow.
    var shareFile by remember { mutableStateOf<android.net.Uri?>(null) }
    val pickShareFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) shareFile = uri }
    shareFile?.let { uri ->
        com.music.spotui.share.ShareImportFlow(
            uri = uri,
            onDismiss = { shareFile = null },
            onImported = { id -> shareFile = null; onDismiss(); navController.navigate(localPlaylistRoute(id)) },
        )
    }
    // A playlist shared by QR code on this Wi-Fi: scanned here, received through the share flow.
    var shareReceive by remember { mutableStateOf(false) }
    if (shareReceive) {
        com.music.spotui.share.ShareReceiveFlow(
            onDismiss = { shareReceive = false },
            onImported = { id -> shareReceive = false; onDismiss(); navController.navigate(localPlaylistRoute(id)) },
        )
    }
    when (asking) {
        "playlist" -> NameDialog(stringResource(R.string.lbl_playlist_create), context.getString(R.string.my_playlist),
            stringResource(R.string.lbl_create), onDismiss = onDismiss) { name ->
            // The row shows in the library at once; SpotifySync takes it back if Spotify refuses.
            SpotifySync.createPlaylistWithTrack(context, name, "")
            onDismiss()
        }
        "folder" -> NameDialog(stringResource(R.string.lbl_folder), context.getString(R.string.lbl_folder_default),
            stringResource(R.string.lbl_create), onDismiss = onDismiss) { name ->
            LibraryFolders.create(context, name)
            onDismiss()
        }
        "local" -> NameDialog(stringResource(R.string.local_playlist_new), context.getString(R.string.my_playlist),
            stringResource(R.string.lbl_create), onDismiss = onDismiss) { name ->
            val p = LocalPlaylists.create(name)
            onDismiss()
            navController.navigate(localPlaylistRoute(p.id))
        }
        "blend" -> BlendSheet(navController, onDismiss)
        "import" -> ImportPlaylistDialog(navController, onDismiss)
        "djmix" -> DjMixDialog(navController, onDismiss)
        else -> com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, containerColor = SheetColor) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                SheetRow(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.lbl_playlist)) { asking = "playlist" }
                SheetRow(Icons.Outlined.Group, stringResource(R.string.blend_title), stringResource(R.string.blend_detail)) { asking = "blend" }
                SheetRow(Icons.Outlined.CreateNewFolder, stringResource(R.string.lbl_folder)) { asking = "folder" }
                SheetRow(Icons.Outlined.PhoneAndroid, stringResource(R.string.local_playlist_new), stringResource(R.string.local_add_note)) { asking = "local" }
                SheetRow(Icons.Outlined.Link, stringResource(R.string.import_playlist), stringResource(R.string.import_playlist_detail)) { asking = "import" }
                SheetRow(Icons.Rounded.GraphicEq, stringResource(R.string.djmix_title), stringResource(R.string.djmix_detail)) { asking = "djmix" }
                SheetRow(Icons.Outlined.FolderOpen, stringResource(R.string.share_import_file), stringResource(R.string.share_import_file_detail)) {
                    if (com.music.spotui.share.ShareImport.supported) {
                        pickShareFile.launch(arrayOf(com.music.spotui.share.ShareZip.MIME, "application/zip", "application/x-zip-compressed"))
                    } else {
                        Toast.makeText(context, context.getString(R.string.share_needs_newer_android), Toast.LENGTH_LONG).show()
                    }
                }
                SheetRow(Icons.Outlined.QrCode2, stringResource(R.string.share_receive), stringResource(R.string.share_receive_detail)) {
                    if (com.music.spotui.share.ShareImport.supported) {
                        shareReceive = true
                    } else {
                        Toast.makeText(context, context.getString(R.string.share_receive_needs_newer_android), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}

/**
 * "Mix à plusieurs" (Spotify Blend): an invitation link to send, or one received to join with.
 * The Blend playlist only exists on Spotify once the friend has joined; then it is in the
 * library like any playlist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlendSheet(navController: NavController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val blends = app.myspoti.host.android.MyspotiRuntime.api.blends
    var joining by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    fun say(res: Int) = Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
    if (joining) NameDialog(stringResource(R.string.blend_join_title), "", stringResource(R.string.blend_join_button),
        onDismiss = { joining = false }) { link ->
        joining = false
        busy = true
        scope.launch {
            val opened = runCatching {
                val invitation = blends.invitation(link)
                when (invitation.state) {
                    app.myspoti.api.BlendInvitationState.JOINABLE, app.myspoti.api.BlendInvitationState.UNKNOWN -> blends.join(link)
                    app.myspoti.api.BlendInvitationState.JOINED -> invitation.playlist
                    app.myspoti.api.BlendInvitationState.WAITING -> { say(R.string.blend_own_invite); null }
                    app.myspoti.api.BlendInvitationState.FULL -> { say(R.string.blend_full); null }
                    app.myspoti.api.BlendInvitationState.DELETED -> { say(R.string.blend_deleted); null }
                }
            }.onFailure {
                say(if (it is IllegalArgumentException) R.string.blend_bad_link else R.string.blend_failed)
            }.getOrNull()
            busy = false
            if (opened != null) { onDismiss(); navController.navigate(playlistRoute(opened.substringAfterLast(':'))) }
        }
    }
    com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, containerColor = SheetColor) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(stringResource(R.string.blend_title), color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 8.dp))
            if (busy) Text(stringResource(R.string.blend_working), color = AppMuted, fontSize = 14.sp, modifier = Modifier.padding(20.dp, 12.dp))
            else {
                SheetRow(Icons.Outlined.Share, stringResource(R.string.blend_invite)) {
                    busy = true
                    scope.launch {
                        val link = runCatching { blends.invite().link }.getOrNull()
                        busy = false
                        if (link == null) return@launch say(R.string.blend_failed)
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, context.getString(R.string.blend_invite_message, link))
                        context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        onDismiss()
                    }
                }
                SheetRow(Icons.Outlined.Link, stringResource(R.string.blend_join)) { joining = true }
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String?, navController: NavController, onMenu: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp, 8.dp, 8.dp, 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(44.dp).clip(CircleShape).pressable { navController.navigateUp() },
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.lbl_back), tint = AppText) }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = AppMuted, fontSize = 13.sp)
        }
        if (onMenu != null) Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(44.dp).clip(CircleShape).pressable { onMenu() },
        ) { Icon(Icons.Default.MoreVert, stringResource(R.string.lbl_more), tint = AppText) }
    }
}

/** A Spotify library folder: its folders and playlists, opened level by level. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(navController: NavController, uri: String, initialName: String) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initialName) }
    // The folder as Spotify gave it; [entries] is that with the changes Spotify has not caught up with.
    var server by remember { mutableStateOf<List<LibraryEntry>?>(null) }
    val edits by com.music.spotui.data.api.LibraryEdits.version.collectAsState()
    val entries = remember(server, edits) { server?.let { com.music.spotui.data.api.LibraryEdits.applyTo(uri, it) } }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var newPlaylist by remember { mutableStateOf(false) }
    var deletingFolder by remember { mutableStateOf(false) }
    var generation by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(uri, generation) {
        withContext(Dispatchers.IO) {
            Graph.repository.provideLibraryFolder(uri).collect { r -> if (r is Response.Success) server = r.data else if (r is Response.Error && server == null) server = emptyList() }
        }
        refreshing = false
    }
    // A change just written: the folder is read again once Spotify's list has had time to catch up.
    LaunchedEffect(uri) { com.music.spotui.data.api.LibraryEdits.reloads.collect { generation++ } }
    if (menu) com.music.spotui.ui.components.LookSheet(onDismissRequest = { menu = false }, containerColor = SheetColor) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            SheetRow(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.lbl_new_playlist)) { menu = false; newPlaylist = true }
            SheetRow(Icons.Outlined.Edit, stringResource(R.string.lbl_folder_rename)) { menu = false; renaming = true }
            SheetRow(Icons.Outlined.Delete, stringResource(R.string.folder_delete)) { menu = false; deletingFolder = true }
        }
    }
    if (deletingFolder) androidx.compose.material3.AlertDialog(
        onDismissRequest = { deletingFolder = false },
        containerColor = SheetColor,
        text = { Text(stringResource(R.string.folder_delete_confirm, name), color = AppText) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                deletingFolder = false
                // The folder is gone from the library at once; a refusal brings it back.
                LibraryFolders.delete(context, uri)
                navController.navigateUp()
            }) { Text(stringResource(R.string.lbl_delete), color = AppDestructive) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { deletingFolder = false }) { Text(stringResource(R.string.action_cancel_label), color = AppText) } },
    )
    // A Spotify playlist created straight inside this folder.
    if (newPlaylist) NameDialog(stringResource(R.string.lbl_playlist_create), context.getString(R.string.my_playlist),
        stringResource(R.string.lbl_create), onDismiss = { newPlaylist = false }) { playlistName ->
        newPlaylist = false
        SpotifySync.createPlaylistWithTrack(context, playlistName, "", folderUri = uri)
    }
    if (renaming) NameDialog(stringResource(R.string.lbl_folder_rename), name, stringResource(R.string.local_rename),
        onDismiss = { renaming = false }) { newName ->
        renaming = false
        val before = name
        name = newName
        LibraryFolders.rename(context, uri, newName) { ok -> if (!ok) name = before }
    }
    Column(Modifier.fillMaxSize().background(AppBackground)) {
        val n = entries?.size
        PageHeader(name, n?.let { pluralStringResource(R.plurals.lbl_n_playlists, it, it.toString()) }, navController) { menu = true }
        when (val list = entries) {
            null -> Loader(SkeletonKind.Header)
            else -> {
                com.music.spotui.data.local.LibraryOrder.version
                val arranged = com.music.spotui.data.local.LibraryOrder.run { arrange(list, sort, custom(uri), 0) }
                androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = refreshing, onRefresh = { refreshing = true; generation++ }, modifier = Modifier.fillMaxSize(),
                ) {
                    SumUpLibraryScreen(PaddingValues(0.dp), arranged, emptyList(), navController, reorderLevel = uri, onChanged = { generation++ })
                }
            }
        }
    }
}

/**
 * "Edit title / artist" for a phone file: three fields (the album too), saved with
 * [onSave]. The file itself is never changed — the app keeps the names (LocalMetadata)
 * and shows them for the file everywhere. The album starts from the file's effective
 * album; emptying it leaves the file's own album in place (no album override).
 *
 * With [uri] (the file's own address) the dialog also carries the file's cover: a
 * picked picture is copied in at once (LocalCovers), not on Save — a cover is a
 * picture, not a name, and the preview should show it the moment it is chosen.
 * [artUri] is the art the file shows now, for that preview.
 */
@Composable
fun EditLocalTrackDialog(title: String, artist: String, album: String, onDismiss: () -> Unit, uri: String? = null, artUri: String? = null, onSave: (String, String, String) -> Unit) {
    var newTitle by remember { mutableStateOf(title) }
    var newArtist by remember { mutableStateOf(artist) }
    var newAlbum by remember { mutableStateOf(album) }
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = tone(0xFF3A3A3A), unfocusedContainerColor = tone(0xFF3A3A3A),
        focusedTextColor = AppText, unfocusedTextColor = AppText, cursorColor = Green,
        focusedIndicatorColor = Green, unfocusedIndicatorColor = Color.Transparent,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SheetColor,
        title = { Text(stringResource(R.string.local_edit_title_artist), color = AppText) },
        text = {
            Column {
                if (uri != null) LocalCoverEditor(uri, artUri)
                TextField(value = newTitle, onValueChange = { newTitle = it }, singleLine = true,
                    label = { Text(stringResource(R.string.local_edit_song_title), color = AppMuted) },
                    colors = fieldColors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                TextField(value = newArtist, onValueChange = { newArtist = it }, singleLine = true,
                    label = { Text(stringResource(R.string.local_edit_song_artist), color = AppMuted) },
                    colors = fieldColors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                TextField(value = newAlbum, onValueChange = { newAlbum = it }, singleLine = true,
                    label = { Text(stringResource(R.string.local_edit_song_album), color = AppMuted) },
                    colors = fieldColors, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.local_edit_song_note), color = AppMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = newTitle.isNotBlank(), onClick = { onSave(newTitle, newArtist, newAlbum) }) {
                Text(stringResource(R.string.playlist_save), color = Green)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.lbl_cancel), color = AppText) }
        },
    )
}

/**
 * The cover half of the local edit dialog: the art the file shows now, a Change cover
 * (the system photo picker; the picture is copied into the app's files at once) and,
 * while the file has a cover of its own, a Remove cover. Shared by the Local Files
 * edit and the stored-row edit — both are the same file underneath.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun LocalCoverEditor(uri: String, artUri: String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    val own = remember(tick, uri) { com.music.spotui.data.local.LocalCovers.songCover(uri) }
    val pickCover = com.music.spotui.ui.components.rememberPlaylistCoverPicker { picked ->
        scope.launch {
            val saved = withContext(Dispatchers.IO) { com.music.spotui.data.local.LocalCovers.setSongCover(context, uri, picked) }
            if (saved == null) Toast.makeText(context, context.getString(R.string.local_cover_failed), Toast.LENGTH_SHORT).show()
            else tick++
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        GlideImage(
            model = own?.let { java.io.File(it) } ?: artUri.orEmpty(), contentDescription = null, contentScale = ContentScale.Crop,
            loading = placeholder(R.drawable.placeholder), failure = placeholder(R.drawable.local_files_cover),
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)),
        )
        Column(Modifier.padding(start = 8.dp)) {
            TextButton(onClick = pickCover) { Text(stringResource(R.string.local_change_cover), color = Green) }
            if (own != null) TextButton(onClick = {
                com.music.spotui.data.local.LocalCovers.clearSongCover(uri)
                tick++
            }) { Text(stringResource(R.string.local_remove_cover), color = AppText) }
        }
    }
}

/** [EditLocalTrackDialog] for a stored local row: the edit lands through LocalMetadata. */
@Composable
fun EditLocalItemDialog(item: LocalItem, onDismiss: () -> Unit) {
    EditLocalTrackDialog(item.title, item.artist, item.album, onDismiss,
        uri = item.uri, artUri = com.music.spotui.data.local.LocalCovers.effectiveArt(item)) { title, artist, album ->
        com.music.spotui.data.local.LocalMetadata.edit(item.uri, title, artist, album)
        com.music.spotui.data.local.LocalMusic.invalidate()
        onDismiss()
    }
}

@OptIn(ExperimentalGlideComposeApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LocalItemRow(
    item: LocalItem,
    current: Boolean,
    onClick: () -> Unit,
    onMenu: (() -> Unit)? = null,
    /** A long press of its own (multi-select); without one the row only clicks. */
    onLongClick: (() -> Unit)? = null,
    /** Non-null while the list is selecting: whether this row is chosen. */
    selected: Boolean? = null,
) {
    val gestures = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.pressable(0.97f, onClick = onClick)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .then(if (selected == true) Modifier.background(Green.copy(alpha = 0.14f)) else Modifier)
            .then(gestures)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(Modifier.size(48.dp)) {
            GlideImage(
                model = com.music.spotui.data.local.LocalCovers.effectiveArt(item), contentDescription = null, contentScale = ContentScale.Crop,
                loading = placeholder(R.drawable.placeholder), failure = placeholder(R.drawable.local_files_cover),
                modifier = Modifier.matchParentSize().clip(RoundedCornerShape(4.dp)),
            )
            if (selected != null) Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) Green else AppText.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.BottomEnd).size(20.dp).background(AppBackground, CircleShape),
            )
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(item.title, color = if (current) Green else AppText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.artist.isNotBlank()) Text(item.artist, color = AppMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onMenu != null) Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).clip(CircleShape).pressable(0.92f, onClick = onMenu),
        ) { Icon(Icons.Default.MoreVert, stringResource(R.string.lbl_more), tint = AppMuted, modifier = Modifier.size(20.dp)) }
    }
}

/**
 * Phone files in a list ([items]), played by the local player. A row leaves the list by
 * swiping it away or from its ⋯ menu, which also edits the file's title / artist.
 *
 * With [selection] non-null the list is in (or can enter) multi-select: a long press
 * calls [onEnterSelection], a tap calls [onToggle] instead of playing, the swipe is off
 * (a swipe and a selection would fight), and the ⋯ menu stays for the single-row actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
fun androidx.compose.foundation.lazy.LazyListScope.localItems(
    items: List<LocalItem>,
    context: String,
    onRemove: (LocalItem) -> Unit,
    selection: Set<String>? = null,
    onToggle: (LocalItem) -> Unit = {},
    onEnterSelection: (LocalItem) -> Unit = {},
    /** "Show album" for a row, where the screen can navigate (the row's album page). */
    onShowAlbum: ((LocalItem) -> Unit)? = null,
    /** The play context URI handed to the player (a local playlist's own, so its saved mixes are found). */
    contextUri: String = "",
    /** The Mix editor's junction under each row but the last (the index of the row above it); null: no junctions. */
    onMixJunction: ((Int) -> Unit)? = null,
    /** Whether the junction under a row carries a saved mix (its marker). */
    mixEdited: (Int) -> Boolean = { false },
) {
    itemsIndexed(items, key = { _, i -> i.key }) { index, item ->
        val np = SongPlayer.nowPlaying.value
        val track = item.toTrack()
        var menu by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf(false) }
        val selecting = selection != null
        val row = @Composable {
            LocalItemRow(item, LocalPlayer.active.value && np.uri == "local:${track.id}",
                onClick = {
                    if (selecting) onToggle(item)
                    else LocalPlayer.play(items.map { it.toTrack() }, index, context = context, contextUri = contextUri)
                },
                onMenu = { menu = true },
                onLongClick = if (selecting) ({ if (selection.isEmpty()) onEnterSelection(item) else onToggle(item) }) else null,
                selected = selection?.let { item.key in it })
        }
        val rowBlock = @Composable { if (selecting) row() else SwipeToRemove(item.key, onRemove = { onRemove(item) }) { row() } }
        if (onMixJunction != null && !selecting && index < items.lastIndex) {
            Column {
                rowBlock()
                com.music.spotui.ui.components.MixJunctionStrip(edited = mixEdited(index), immersive = false) { onMixJunction(index) }
            }
        } else rowBlock()
        if (menu) com.music.spotui.ui.components.LookSheet(onDismissRequest = { menu = false }, containerColor = SheetColor) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                SheetRow(Icons.Outlined.Edit, stringResource(R.string.local_edit_title_artist)) { menu = false; editing = true }
                if (onShowAlbum != null) SheetRow(Icons.Default.Album, stringResource(R.string.lbl_open_album)) { menu = false; onShowAlbum(item) }
                SheetRow(Icons.Outlined.RemoveCircleOutline, stringResource(R.string.local_remove_from_playlist)) { menu = false; onRemove(item) }
            }
        }
        if (editing) EditLocalItemDialog(item) { editing = false }
    }
}

/**
 * The bar a Classic multi-select carries: how many are chosen, Select all, the actions
 * (add to a playlist, and where it applies take the chosen out), and the way out.
 */
@Composable
internal fun ClassicSelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onAdd: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp)).background(tone(0xFF282828))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).clip(CircleShape).pressable(0.92f, onClick = onCancel),
        ) { Icon(Icons.Default.Close, stringResource(R.string.select_cancel), tint = AppText, modifier = Modifier.size(22.dp)) }
        Text(stringResource(R.string.select_selected_count, count), color = AppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp))
        TextButton(onClick = onSelectAll) { Text(stringResource(R.string.select_all), color = Green, fontSize = 14.sp) }
        Spacer(Modifier.weight(1f))
        if (onRemove != null) TextButton(onClick = onRemove) {
            Text(stringResource(R.string.local_remove_selected), color = AppText, fontSize = 14.sp)
        }
        if (onAdd != null) Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.height(40.dp).pressable(0.96f, onClick = onAdd)
                .clip(RoundedCornerShape(50)).background(Green).padding(horizontal = 16.dp),
        ) { Text(stringResource(R.string.lbl_add_to_playlist), color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
    }
}

/** A playlist kept on this phone. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun LocalPlaylistScreen(navController: NavController, id: String) {
    val playlist = LocalPlaylists.playlists[id]
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var addingSelected by remember { mutableStateOf(false) }
    val pickCover = com.music.spotui.ui.components.rememberPlaylistCoverPicker { uri ->
        scope.launch {
            val path = withContext(Dispatchers.IO) { com.music.spotui.data.local.PlaylistCovers.save(context, uri) }
            if (path != null) LocalPlaylists.setCover(id, path)
            else Toast.makeText(context, context.getString(R.string.local_cover_failed), Toast.LENGTH_SHORT).show()
        }
    }
    if (playlist == null) {
        LaunchedEffect(Unit) { navController.navigateUp() }
        return
    }
    val selecting = selection.isNotEmpty()
    androidx.activity.compose.BackHandler(enabled = selecting) { selection = emptySet() }
    if (addingSelected) AddLocalTracksToPlaylistSheet(
        tracks = playlist.items.filter { it.key in selection }.map { it.toTrack() },
        onDismiss = { addingSelected = false; selection = emptySet() },
    )
    if (menu) com.music.spotui.ui.components.LookSheet(onDismissRequest = { menu = false }, containerColor = SheetColor) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            SheetRow(Icons.Outlined.Edit, stringResource(R.string.local_rename)) { menu = false; renaming = true }
            SheetRow(Icons.Outlined.Image, stringResource(R.string.local_change_cover)) { menu = false; pickCover() }
            if (playlist.cover.isNotBlank()) SheetRow(Icons.Outlined.HideImage, stringResource(R.string.local_remove_cover)) {
                menu = false
                LocalPlaylists.clearCover(id)
            }
            SheetRow(Icons.Outlined.Share, stringResource(R.string.share_playlist)) { menu = false; sharing = true }
            SheetRow(Icons.Outlined.Delete, stringResource(R.string.local_delete_playlist)) {
                menu = false
                navController.navigateUp()
                LocalPlaylists.delete(id)
            }
        }
    }
    if (renaming) NameDialog(stringResource(R.string.local_rename), playlist.name, stringResource(R.string.local_rename),
        onDismiss = { renaming = false }) { renaming = false; LocalPlaylists.rename(id, it) }
    if (sharing) com.music.spotui.share.SharePlaylistSheet(playlist) { sharing = false }
    Box(Modifier.fillMaxSize().background(AppBackground)) {
        Column(Modifier.fillMaxSize()) {
            PageHeader(playlist.name, stringResource(R.string.local_playlist_subtitle) + " • " +
                pluralStringResource(R.plurals.lbl_n_songs_s, playlist.items.size, playlist.items.size.toString()),
                navController) { menu = true }
            if (playlist.items.isEmpty()) Text(stringResource(R.string.local_playlist_empty), color = AppMuted, fontSize = 14.sp,
                modifier = Modifier.padding(20.dp))
            else LazyColumn(contentPadding = PaddingValues(bottom = com.music.spotui.ui.components.bottomBarsSpace()), modifier = Modifier.fillMaxSize()) {
                if (playlist.cover.isNotBlank()) item(key = "cover") {
                    GlideImage(
                        model = java.io.File(playlist.cover), contentDescription = null, contentScale = ContentScale.Crop,
                        loading = placeholder(R.drawable.local_files_cover), failure = placeholder(R.drawable.local_files_cover),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                            .height(180.dp).clip(RoundedCornerShape(8.dp)),
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(52.dp).pressable(0.92f) {
                                LocalPlayer.play(playlist.items.map { it.toTrack() }, 0, context = playlist.name,
                                    contextUri = com.music.spotui.data.local.MixEdits.localContextUri(id))
                            }.clip(CircleShape).background(Green),
                        ) {
                            Icon(painterResource(R.drawable.play_svgrepo_com), stringResource(R.string.lbl_play), tint = Color.Black, modifier = Modifier.size(24.dp))
                        }
                    }
                }
                localItems(
                    playlist.items, playlist.name,
                    onRemove = { LocalPlaylists.remove(id, it.key) },
                    selection = selection,
                    onToggle = { selection = com.music.spotui.ui.components.MultiSelect.toggle(selection, it.key) },
                    onEnterSelection = { selection = setOf(it.key) },
                    onShowAlbum = { item ->
                        if (item.album.isNotBlank()) navController.navigate(com.music.spotui.ui.navigation.localAlbumRoute(item.artist, item.album))
                        else Toast.makeText(context, context.getString(R.string.local_no_album), Toast.LENGTH_SHORT).show()
                    },
                    contextUri = com.music.spotui.data.local.MixEdits.localContextUri(id),
                    mixEdited = { index ->
                        com.music.spotui.data.local.MixEdits.has("local:$id",
                            com.music.spotui.data.local.MixEdits.trackIdentity(android.net.Uri.parse(playlist.items[index].uri)),
                            com.music.spotui.data.local.MixEdits.trackIdentity(android.net.Uri.parse(playlist.items[index + 1].uri)))
                    },
                    onMixJunction = { index ->
                        navController.navigate(com.music.spotui.ui.navigation.mixEditorRoute("local:$id",
                            com.music.spotui.data.local.MixEdits.trackIdentity(android.net.Uri.parse(playlist.items[index].uri)),
                            com.music.spotui.data.local.MixEdits.trackIdentity(android.net.Uri.parse(playlist.items[index + 1].uri))))
                    },
                )
            }
        }
        if (selecting) ClassicSelectionBar(
            count = selection.size,
            onSelectAll = { selection = com.music.spotui.ui.components.MultiSelect.selectAll(selection, playlist.items.map { it.key }) },
            onAdd = { addingSelected = true },
            onRemove = {
                LocalPlaylists.removeAll(id, selection)
                selection = emptySet()
            },
            onCancel = { selection = emptySet() },
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = com.music.spotui.ui.components.bottomBarsSpace()),
        )
    }
}

/**
 * "Add to playlist" for a phone file: a playlist on this phone (or a new one), or one of
 * the user's Spotify playlists, where it is shown under "Added on this device" (Spotify
 * cannot hold phone files, so it stays on this phone).
 */
@Composable
fun AddLocalToPlaylistSheet(track: LocalTrack, onDismiss: () -> Unit) =
    AddLocalToPlaylistSheet(listOf(track), single = true, onDismiss = onDismiss)

/** [AddLocalToPlaylistSheet] for a multi-select's songs at once. */
@Composable
fun AddLocalTracksToPlaylistSheet(tracks: List<LocalTrack>, onDismiss: () -> Unit) =
    AddLocalToPlaylistSheet(tracks, single = false, onDismiss = onDismiss)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
private fun AddLocalToPlaylistSheet(tracks: List<LocalTrack>, single: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var spotifyLists by remember { mutableStateOf(com.music.spotui.data.api.EditablePlaylists.cachedWithCovers()) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        spotifyLists = com.music.spotui.data.api.EditablePlaylists.loadWithCovers()
    }
    // What the add did is always said: in, already there (the store refuses a song twice),
    // or a mix of the two for a bulk add.
    fun added(name: String, result: com.music.spotui.data.local.AddResult) {
        val message = when {
            result.added == 0 -> context.getString(R.string.local_already_in_playlist)
            result.skipped == 0 && single -> context.getString(R.string.local_added_to, name)
            result.skipped == 0 -> context.getString(R.string.local_added_count_to, result.added, name)
            else -> context.getString(R.string.local_added_count_skipped, result.added, name, result.skipped)
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        onDismiss()
    }
    if (creating) {
        NameDialog(stringResource(R.string.local_playlist_new), context.getString(R.string.my_playlist),
            stringResource(R.string.lbl_create), onDismiss = { creating = false }) { name ->
            val p = LocalPlaylists.create(name)
            added(p.name, LocalPlaylists.addAll(p.id, tracks))
        }
        return
    }
    com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, containerColor = SheetColor) {
        LazyColumn(Modifier.navigationBarsPadding().heightIn(max = 560.dp)) {
            item {
                Text(stringResource(R.string.lbl_add_to_playlist), color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(20.dp, 4.dp, 20.dp, 4.dp))
                Text(stringResource(R.string.local_add_note), color = AppMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 8.dp))
                SheetRow(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.lbl_new_playlist),
                    stringResource(R.string.local_playlist_subtitle)) { creating = true }
            }
            items(LocalPlaylists.list(), key = { "l:" + it.id }) { p ->
                PickRow(
                    name = p.name, detail = stringResource(R.string.local_playlist_subtitle),
                    cover = p.cover.takeIf { it.isNotBlank() }?.let { java.io.File(it) },
                ) {
                    added(p.name, LocalPlaylists.addAll(p.id, tracks))
                }
            }
            // The imported playlists too: a file added there is a row of the file's own
            // names (no catalog identity), playing by title and artist like an Amazon
            // import's rows.
            items(com.music.spotui.data.local.ImportedPlaylists.list(), key = { "i:" + it.id }) { p ->
                PickRow(
                    name = p.name, detail = stringResource(
                        com.music.spotui.data.api.importedOriginLabelRes(com.music.spotui.data.api.importedOrigin(p)),
                    ),
                    cover = p.cover.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
                        ?: p.items.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl,
                ) {
                    added(p.name, com.music.spotui.data.local.ImportedPlaylists.addAll(p.id, tracks.map {
                        com.music.spotui.data.local.ImportedPlaylists.rowOf(it)
                    }))
                }
            }
            when (val lists = spotifyLists) {
                null -> item { Text(stringResource(R.string.loading_playlists), color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(20.dp, 12.dp)) }
                else -> items(lists, key = { "s:" + it.id }) { p ->
                    PickRow(name = p.name, detail = stringResource(R.string.local_added_section), cover = p.imageUrl) {
                        added(p.name, LocalPlaylists.addAllToSpotify(p.id, tracks))
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun PickRow(name: String, detail: String, cover: Any?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        if (cover == null) Image(painterResource(R.drawable.local_files_cover), null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
        else GlideImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop,
            loading = placeholder(R.drawable.placeholder), failure = placeholder(R.drawable.placeholder),
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.padding(start = 12.dp)) {
            Text(name, color = AppText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = AppMuted, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}


/**
 * Whether the row's ⋯ menu offers "Remove from Your Library": a playlist of someone else (its
 * owner known), an album, an artist or a show. The user's own playlists are deleted, not removed.
 */
internal fun removable(entry: LibraryEntry, me: String? = com.music.spotui.data.api.ProfileCache.id): Boolean {
    val uri = entry.uri
    if (entry.isFolder) return false
    if (uri.startsWith("spotify:playlist:")) return me != null && entry.ownerUri.isNotBlank() && entry.ownerUri != "spotify:user:$me"
    return uri.startsWith("spotify:album:") || uri.startsWith("spotify:artist:") || uri.startsWith("spotify:show:")
}

/**
 * A library row's ⋯ menu: pin it at the top, move a playlist or folder into another folder
 * (or back to the top level), delete a folder, remove what is not the user's own ([removable]). Each shows in the list at once ([LibraryEdits]),
 * which reads itself again when Spotify has had time to catch up; [onChanged] is kept for callers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryItemSheet(entry: LibraryEntry, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    var moving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    // The change shows in the list at once and is written in the app's own scope (the sheet is
    // closed by then); a refusal puts the list back and says so.
    fun act(edit: LibraryEdits.Edit, block: suspend () -> Unit) {
        LibraryEdits.run(context, edit, write = { block(); LibraryFolders.changed() })
        onDismiss()
    }
    val api = app.myspoti.host.android.MyspotiRuntime.api
    com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, containerColor = SheetColor) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(entry.name, color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 8.dp))
            if (!moving) {
                // A saved playlist or album: all its songs into the queue (read to the end first).
                if (entry.uri.startsWith("spotify:playlist:") || entry.uri.startsWith("spotify:album:")) {
                    SheetRow(Icons.AutoMirrored.Outlined.PlaylistPlay, stringResource(R.string.lbl_play_next)) {
                        com.music.spotui.di.CollectionQueue.queue(entry.uri, next = true); onDismiss()
                    }
                    SheetRow(Icons.AutoMirrored.Outlined.QueueMusic, stringResource(R.string.lbl_queue_add)) {
                        com.music.spotui.di.CollectionQueue.queue(entry.uri, next = false); onDismiss()
                    }
                }
                SheetRow(Icons.Outlined.PushPin, stringResource(if (entry.pinned) R.string.library_unpin else R.string.library_pin)) {
                    act(LibraryEdits.Pin(entry.uri, !entry.pinned)) { if (entry.pinned) api.library.unpin(entry.uri) else api.library.pin(entry.uri) }
                }
                if (entry.isPlaylist || entry.isFolder) SheetRow(Icons.Outlined.DriveFileMove, stringResource(R.string.library_move_to_folder)) { moving = true }
                // A saved playlist or album: all its songs into another playlist of yours.
                if (entry.uri.startsWith("spotify:playlist:") || entry.uri.startsWith("spotify:album:"))
                    SheetRow(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.lbl_add_to_playlist)) { merging = true }
                if (entry.isFolder) SheetRow(Icons.Outlined.Delete, stringResource(R.string.folder_delete)) { deleting = true }
                // Someone else's playlist, an album, an artist or a show: out of the library from here,
                // without opening it and relying on its heart (reported: a playlist listed with an
                // empty heart could not be taken out).
                if (removable(entry)) SheetRow(Icons.Outlined.RemoveCircleOutline, stringResource(R.string.whats_new_remove_library)) {
                    com.music.spotui.di.SongPlayer.setSaved(entry.uri, false); onDismiss()
                }
            } else {
                val folders by androidx.compose.runtime.produceState<List<app.myspoti.api.LibraryItem>?>(null) {
                    value = runCatching { api.library.items(folderUri = null, limit = 200).items.filter { it.kind == app.myspoti.api.MediaKind.FOLDER && it.uri != entry.uri } }
                        .getOrDefault(emptyList())
                }
                SheetRow(Icons.Outlined.Home, stringResource(R.string.library_top_level)) { act(LibraryEdits.Move(entry, null)) { api.library.moveToFolder(entry.uri, null) } }
                folders?.forEach { f ->
                    SheetRow(Icons.Outlined.Folder, f.name) { act(LibraryEdits.Move(entry, f.uri)) { api.library.moveToFolder(entry.uri, f.uri) } }
                }
            }
        }
    }
    if (merging) com.music.spotui.ui.components.AddCollectionToPlaylistSheet(entry.uri, entry.name) { merging = false; onDismiss() }
    if (deleting) androidx.compose.material3.AlertDialog(
        onDismissRequest = { deleting = false },
        containerColor = SheetColor,
        text = { Text(stringResource(R.string.folder_delete_confirm, entry.name), color = AppText) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { deleting = false; act(LibraryEdits.Remove(entry.uri)) { api.library.deleteFolder(entry.uri) } }) {
                Text(stringResource(R.string.lbl_delete), color = AppDestructive)
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel_label), color = AppText) } },
    )
}
