package com.music.spotui.share

import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.data.local.LocalPlaylist
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppDestructive
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private val SheetColor get() = tone(0xFF282828)
private val Green get() = AppAccent

/**
 * A playlist file that arrived from outside (shared to the app, or opened with it):
 * held here until the import host above every screen has shown its confirmation.
 */
object ShareInbox {
    val pending = MutableStateFlow<Uri?>(null)

    fun offer(uri: Uri) {
        pending.value = uri
    }
}

/** Above every screen of a signed-in app: the confirmation of a playlist file that was opened or shared in. */
@Composable
fun ShareImportHost() {
    val uri by ShareInbox.pending.collectAsState()
    uri?.let { u ->
        androidx.compose.runtime.key(u) {
            ShareImportFlow(
                uri = u,
                onDismiss = { ShareInbox.pending.compareAndSet(u, null) },
                onImported = { ShareInbox.pending.compareAndSet(u, null) },
            )
        }
    }
}

@Composable
private fun ShareRow(icon: ImageVector, label: String, detail: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Icon(icon, null, tint = AppMuted, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, color = if (enabled) AppText else AppMuted, fontSize = 16.sp)
            if (detail != null) Text(detail, color = AppMuted, fontSize = 12.sp)
        }
    }
}

/**
 * "Share" on a playlist of this phone: what it costs (the file's size, said plainly
 * before anything is packed) and the one way out phase 1 has — as a file. The QR /
 * same-Wi-Fi choice joins this sheet in phase 2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePlaylistSheet(playlist: LocalPlaylist, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var building by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(playlist.id, playlist.items.size) {
        size = withContext(Dispatchers.IO) { ShareExport.totalSize(context, playlist).takeIf { it >= 0 } }
    }
    com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, containerColor = SheetColor) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                stringResource(R.string.share_playlist), color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(20.dp, 4.dp, 20.dp, 8.dp),
            )
            if (!ShareImport.supported) {
                Text(
                    stringResource(R.string.share_needs_newer_android), color = AppMuted, fontSize = 14.sp,
                    modifier = Modifier.padding(20.dp, 4.dp),
                )
            } else {
                Text(
                    size?.let { stringResource(R.string.share_size_note, Formatter.formatShortFileSize(context, it)) }
                        ?: stringResource(R.string.share_size_unknown),
                    color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(20.dp, 4.dp, 20.dp, 4.dp),
                )
                ShareRow(
                    Icons.Outlined.Share, stringResource(R.string.share_as_file),
                    if (building) stringResource(R.string.share_preparing) else stringResource(R.string.share_as_file_detail),
                    enabled = !building,
                ) {
                    building = true
                    scope.launch {
                        val built = runCatching { ShareExport.build(context, playlist) }.getOrNull()
                        building = false
                        if (built == null) {
                            Toast.makeText(context, context.getString(R.string.share_failed), Toast.LENGTH_SHORT).show()
                        } else {
                            if (built.skipped > 0) Toast.makeText(
                                context, context.getString(R.string.share_skipped, built.skipped), Toast.LENGTH_LONG,
                            ).show()
                            ShareExport.share(context, built, playlist.name)
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

/**
 * The receiving flow, the same for every door (the Library's file picker, a file opened
 * with the app, a file shared to it): read the bundle, say what it is and what it costs,
 * and only then — on the user's yes — write its songs onto this phone.
 */
@Composable
fun ShareImportFlow(uri: Uri, onDismiss: () -> Unit, onImported: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<ShareImport.Preview?>(null) }
    var bad by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(Triple(0, 0, "")) }
    var result by remember { mutableStateOf<ShareImport.Result?>(null) }
    val cancelFlag = remember { AtomicBoolean(false) }
    LaunchedEffect(uri) {
        val p = ShareImport.preview(context, uri)
        if (p == null) bad = true else preview = p
    }
    val done = result
    val offer = preview
    when {
        done != null -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = SheetColor,
            title = { Text(stringResource(R.string.share_import_done), color = AppText) },
            text = {
                Column {
                    Text(stringResource(R.string.share_imported, done.imported, done.total), color = AppText, fontSize = 15.sp)
                    if (done.failed > 0) Text(
                        stringResource(R.string.share_import_failed_some, done.failed),
                        color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { done.playlistId?.let(onImported) ?: onDismiss() }) {
                    Text(stringResource(android.R.string.ok), color = Green)
                }
            },
        )
        importing -> AlertDialog(
            onDismissRequest = { cancelFlag.set(true) },
            containerColor = SheetColor,
            title = { Text(stringResource(R.string.share_importing, offer?.manifest?.name.orEmpty()), color = AppText) },
            text = {
                Column {
                    Text(stringResource(R.string.share_import_progress, progress.first, progress.second), color = AppText, fontSize = 15.sp)
                    if (progress.third.isNotBlank()) Text(progress.third, color = AppMuted, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { cancelFlag.set(true) }) { Text(stringResource(R.string.lbl_cancel), color = AppText) }
            },
        )
        bad -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = SheetColor,
            title = { Text(stringResource(R.string.share_import_title), color = AppText) },
            text = { Text(stringResource(R.string.share_import_bad), color = AppText, fontSize = 15.sp) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok), color = Green) }
            },
        )
        offer != null -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = SheetColor,
            title = { Text(stringResource(R.string.share_import_title), color = AppText) },
            text = {
                Column {
                    Text(offer.manifest.name, color = AppText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    if (offer.manifest.senderName.isNotBlank()) Text(
                        stringResource(R.string.share_import_from, offer.manifest.senderName),
                        color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        stringResource(R.string.share_import_meta, offer.manifest.tracks.size,
                            Formatter.formatShortFileSize(context, offer.manifest.totalBytes)),
                        color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                    if (offer.ownedCount > 0) Text(
                        stringResource(R.string.share_import_owned, offer.ownedCount),
                        color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                    if (!offer.spaceOk) Text(
                        stringResource(R.string.share_import_space, Formatter.formatShortFileSize(context, offer.manifest.totalBytes)),
                        color = AppDestructive, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = offer.spaceOk, onClick = {
                    importing = true
                    scope.launch {
                        val r = ShareImport.import(context, uri, offer.manifest, cancelFlag) { d, t, name ->
                            progress = Triple(d, t, name)
                        }
                        importing = false
                        if (r.cancelled) onDismiss() else result = r
                    }
                }) { Text(stringResource(R.string.import_button), color = Green) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.lbl_cancel), color = AppText) }
            },
        )
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = SheetColor,
            title = { Text(stringResource(R.string.share_import_title), color = AppText) },
            text = { Text(stringResource(R.string.loading_playlists), color = AppMuted, fontSize = 14.sp) },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.lbl_cancel), color = AppText) }
            },
        )
    }
}
