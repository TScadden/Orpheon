package com.music.spotui.share

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.myspoti.engine.R
import app.myspoti.pass.ShareInvite
import app.myspoti.pass.ShareServer
import com.music.spotui.data.local.LocalPlaylist
import com.music.spotui.pass.PassQr
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppDestructive
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private val SheetColor get() = tone(0xFF282828)
private val Green get() = AppAccent

/** The share/receive screens keep the phone awake: a transfer is minutes, and a sleeping screen ends it. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

// ------------------------------------------------------------------ sending

private enum class QrPhase { PREPARING, WAITING, CONNECTED, SENDING, SENT, ENDED, EXPIRED, EMPTY, NO_NETWORK }

/**
 * This phone hosts a playlist: a QR code the other phone scans, then the songs cross
 * the room. One code, one receiver; closed, it stops listening. What the receiver got
 * stays theirs — going away mid-way is an ending the sender hears about, and a fresh
 * code resumes where it stopped (their phone keeps what already arrived).
 */
@Composable
fun ShareQrSheet(playlist: LocalPlaylist, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var phase by remember { mutableStateOf(QrPhase.PREPARING) }
    var inviteText by remember { mutableStateOf<String?>(null) }
    var peerName by remember { mutableStateOf("") }
    var sendingText by remember { mutableStateOf("") }
    var filesDone by remember { mutableStateOf(0) }
    var filesTotal by remember { mutableStateOf(0) }
    val serverRef = remember { mutableStateOf<ShareServer?>(null) }
    KeepScreenOn()
    DisposableEffect(Unit) { onDispose { serverRef.value?.stop() } }
    LaunchedEffect(playlist.id) {
        val packed = runCatching { ShareExport.pack(context, playlist) }.getOrNull()
        if (packed == null || packed.manifest.tracks.isEmpty()) {
            if (phase == QrPhase.PREPARING) phase = QrPhase.EMPTY
            return@LaunchedEffect
        }
        if (packed.skipped > 0) Toast.makeText(
            context, context.getString(R.string.share_skipped, packed.skipped), Toast.LENGTH_LONG,
        ).show()
        filesTotal = packed.manifest.tracks.size
        val titles = packed.manifest.tracks.associate { it.entry to it.title }
        val server = runCatching {
            ShareLan.serve(
                packed, playlist,
                onConnected = { name -> peerName = name; phase = QrPhase.CONNECTED },
                onSending = { entry, sent, size ->
                    sendingText = "${titles[entry] ?: entry} · ${Formatter.formatShortFileSize(context, sent)} / ${Formatter.formatShortFileSize(context, size)}"
                    phase = QrPhase.SENDING
                },
                onFileDone = { filesDone++ },
                onDone = { completed ->
                    phase = when {
                        completed -> QrPhase.SENT
                        phase == QrPhase.WAITING || phase == QrPhase.PREPARING -> QrPhase.EXPIRED
                        else -> QrPhase.ENDED
                    }
                },
            )
        }.getOrNull()
        if (server == null) {
            if (phase == QrPhase.PREPARING) phase = QrPhase.NO_NETWORK
        } else {
            serverRef.value = server
            inviteText = server.invite.toText()
            if (phase == QrPhase.PREPARING) phase = QrPhase.WAITING
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 420.dp).padding(24.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1C1C1E)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.share_qr_title), color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center)
            Text(playlist.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            when (phase) {
                QrPhase.PREPARING -> Text(stringResource(R.string.share_preparing), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, textAlign = TextAlign.Center)
                QrPhase.WAITING -> {
                    inviteText?.let { PassQr(it) }
                    Text(stringResource(R.string.share_qr_waiting), color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.share_qr_hint), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, textAlign = TextAlign.Center)
                }
                QrPhase.CONNECTED -> Text(stringResource(R.string.share_qr_connected, peerName.ifBlank { "…" }), color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center)
                QrPhase.SENDING -> {
                    Text(stringResource(R.string.share_import_progress, filesDone, filesTotal), color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center)
                    if (sendingText.isNotBlank()) Text(sendingText, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, textAlign = TextAlign.Center)
                }
                QrPhase.SENT -> Text(stringResource(R.string.share_qr_sent), color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center)
                QrPhase.ENDED -> Text(stringResource(R.string.share_qr_ended), color = Color(0xFFFFB74D), fontSize = 14.sp, textAlign = TextAlign.Center)
                QrPhase.EXPIRED -> Text(stringResource(R.string.share_qr_expired), color = Color(0xFFFFB74D), fontSize = 14.sp, textAlign = TextAlign.Center)
                QrPhase.EMPTY -> Text(stringResource(R.string.share_qr_empty), color = Color(0xFFFF8A80), fontSize = 14.sp, textAlign = TextAlign.Center)
                QrPhase.NO_NETWORK -> Text(stringResource(R.string.pass_no_network), color = Color(0xFFFF8A80), fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (phase == QrPhase.SENT) android.R.string.ok else android.R.string.cancel))
            }
        }
    }
}

// ------------------------------------------------------------------ receiving

/**
 * The receiving door with a camera in it: the scanner opens, the code read must be a
 * share code, then the session itself takes over. A phone too old for the import is
 * told so before the camera even opens.
 */
@Composable
fun ShareReceiveFlow(onDismiss: () -> Unit, onImported: (String) -> Unit) {
    val context = LocalContext.current
    var invite by remember { mutableStateOf<ShareInvite?>(null) }
    LaunchedEffect(Unit) {
        if (!ShareImport.supported) {
            Toast.makeText(context, context.getString(R.string.share_receive_needs_newer_android), Toast.LENGTH_LONG).show()
            onDismiss()
            return@LaunchedEffect
        }
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context).startScan()
            .addOnSuccessListener { code ->
                val i = ShareInvite.parse(code.rawValue.orEmpty())
                if (i == null) {
                    Toast.makeText(context, R.string.share_not_invite, Toast.LENGTH_LONG).show()
                    onDismiss()
                } else invite = i
            }
            .addOnCanceledListener { onDismiss() }
            .addOnFailureListener {
                Toast.makeText(context, R.string.pass_scan_unavailable, Toast.LENGTH_LONG).show()
                onDismiss()
            }
    }
    invite?.let { ShareLanSession(it, onDismiss = onDismiss, onImported = onImported) }
}

private enum class LanPhase { CONNECTING, CONFIRM, RECEIVING, IMPORTING, RESULT, ERROR }

/**
 * One receiving session, the same from every door (the scanner, a code a camera app
 * opened): connect, read the manifest, say what it is and what it costs, and only on
 * the user's yes bring the songs home — each checked against the manifest as it lands.
 */
@Composable
fun ShareLanSession(invite: ShareInvite, onDismiss: () -> Unit, onImported: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(LanPhase.CONNECTING) }
    var session by remember { mutableStateOf<ShareLan.Session?>(null) }
    var preview by remember { mutableStateOf<ShareImport.Preview?>(null) }
    var error by remember { mutableStateOf("") }
    var progressName by remember { mutableStateOf("") }
    var progressText by remember { mutableStateOf("") }
    var importProgress by remember { mutableStateOf(0 to 0) }
    var result by remember { mutableStateOf<ShareImport.Result?>(null) }
    val cancelFlag = remember { AtomicBoolean(false) }
    KeepScreenOn()
    DisposableEffect(Unit) { onDispose { session?.close() } }
    LaunchedEffect(invite) {
        runCatching {
            val s = ShareLan.Session.open(invite)
            session = s
            preview = ShareImport.previewManifest(context, s.manifest)
            phase = LanPhase.CONFIRM
        }.onFailure {
            error = it.message ?: it.javaClass.simpleName
            phase = LanPhase.ERROR
        }
    }
    fun cancelReceive() {
        cancelFlag.set(true)
        session?.close()
        onDismiss()
    }
    val offer = preview
    when (phase) {
        LanPhase.CONNECTING -> AlertDialog(
            onDismissRequest = onDismiss, containerColor = SheetColor,
            title = { Text(stringResource(R.string.share_import_title), color = AppText) },
            text = { Text(stringResource(R.string.loading_playlists), color = AppMuted, fontSize = 14.sp) },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lbl_cancel), color = AppText) } },
        )
        LanPhase.ERROR -> AlertDialog(
            onDismissRequest = onDismiss, containerColor = SheetColor,
            title = { Text(stringResource(R.string.share_receive_failed), color = AppText) },
            text = { Text(error, color = AppText, fontSize = 15.sp) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok), color = Green) } },
        )
        LanPhase.CONFIRM -> if (offer != null) AlertDialog(
            onDismissRequest = { session?.close(); onDismiss() }, containerColor = SheetColor,
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
                    val s = session ?: return@TextButton
                    phase = LanPhase.RECEIVING
                    scope.launch {
                        val fetched = runCatching {
                            s.fetchAll(context, cancelFlag) { name, doneB, totalB ->
                                progressName = name
                                progressText = context.getString(
                                    R.string.share_receive_progress,
                                    Formatter.formatShortFileSize(context, doneB),
                                    Formatter.formatShortFileSize(context, totalB),
                                )
                            }
                        }.getOrNull()
                        if (fetched == null || fetched.cancelled || cancelFlag.get()) {
                            s.close(); onDismiss(); return@launch
                        }
                        s.finish()
                        phase = LanPhase.IMPORTING
                        val r = ShareImport.importFiles(context, s.manifest, fetched.files, fetched.cover, cancelFlag) { d, t, _ ->
                            importProgress = d to t
                        }
                        if (r.cancelled) onDismiss() else { result = r; phase = LanPhase.RESULT }
                    }
                }) { Text(stringResource(R.string.import_button), color = Green) }
            },
            dismissButton = {
                TextButton(onClick = { session?.close(); onDismiss() }) { Text(stringResource(R.string.lbl_cancel), color = AppText) }
            },
        )
        LanPhase.RECEIVING -> AlertDialog(
            onDismissRequest = { cancelReceive() }, containerColor = SheetColor,
            title = { Text(stringResource(R.string.share_receiving, offer?.manifest?.name ?: invite.name), color = AppText) },
            text = {
                Column {
                    if (progressName.isNotBlank()) Text(progressName, color = AppText, fontSize = 15.sp)
                    if (progressText.isNotBlank()) Text(progressText, color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { cancelReceive() }) { Text(stringResource(R.string.lbl_cancel), color = AppText) } },
        )
        LanPhase.IMPORTING -> AlertDialog(
            onDismissRequest = { cancelFlag.set(true) }, containerColor = SheetColor,
            title = { Text(stringResource(R.string.share_importing, offer?.manifest?.name.orEmpty()), color = AppText) },
            text = { Text(stringResource(R.string.share_import_progress, importProgress.first, importProgress.second), color = AppText, fontSize = 15.sp) },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { cancelFlag.set(true) }) { Text(stringResource(R.string.lbl_cancel), color = AppText) } },
        )
        LanPhase.RESULT -> result?.let { done -> AlertDialog(
            onDismissRequest = onDismiss, containerColor = SheetColor,
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
        ) }
    }
}

// ------------------------------------------------------------------ links

/**
 * The share codes opened as links (`orpheon://share?…`, see [ShareInvite]): a phone's
 * camera scans the sending phone's code and the app opens on the question, without
 * going through the Library. A link only ever asks: nothing is received before the
 * user's yes, a link that is not a whole invitation for the local network is refused,
 * and a phone not signed in is told to sign in first.
 */
object ShareLinks {
    /** The invitation waiting for the user's answer, shown by [ShareLinkHost]. */
    val pending = MutableStateFlow<ShareInvite?>(null)

    /** A link of the share's own host (well formed or not): this app's to answer, never the pass's. */
    fun isShareLink(link: String): Boolean {
        val l = link.trim()
        if (l.length < ShareInvite.LINK.length) return false
        if (!l.regionMatches(0, ShareInvite.LINK, 0, ShareInvite.LINK.length, ignoreCase = true)) return false
        val next = l.getOrNull(ShareInvite.LINK.length)
        return next == null || next == '?' || next == '/' || next == '#'
    }

    /** Takes [link] when it is a share link (true), and puts it up for the question when it can be. */
    fun offer(context: android.content.Context, link: String, signedIn: Boolean): Boolean {
        if (!isShareLink(link)) return false
        val i = ShareInvite.parse(link)
        when {
            i == null -> Toast.makeText(context, R.string.share_not_invite, Toast.LENGTH_LONG).show()
            !signedIn -> Toast.makeText(context, R.string.pass_need_sign_in, Toast.LENGTH_LONG).show()
            else -> pending.value = i
        }
        return true
    }
}

/** Above every screen of a signed-in app: the question of a share link that was opened, then the session it accepts. */
@Composable
fun ShareLinkHost() {
    val i by ShareLinks.pending.collectAsState()
    var accepted by remember { mutableStateOf<ShareInvite?>(null) }
    i?.let { invite ->
        key(invite) {
            AlertDialog(
                onDismissRequest = { ShareLinks.pending.compareAndSet(invite, null) },
                containerColor = SheetColor,
                title = { Text(stringResource(R.string.share_link_title), color = AppText) },
                text = {
                    Text(
                        stringResource(R.string.share_link_text, invite.name.ifBlank { invite.host }, "${invite.host}:${invite.port}"),
                        color = AppText, fontSize = 15.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        ShareLinks.pending.compareAndSet(invite, null)
                        accepted = invite
                    }) { Text(stringResource(R.string.share_receive_action), color = Green) }
                },
                dismissButton = {
                    TextButton(onClick = { ShareLinks.pending.compareAndSet(invite, null) }) {
                        Text(stringResource(R.string.lbl_cancel), color = AppText)
                    }
                },
            )
        }
    }
    accepted?.let { invite ->
        key(invite) {
            ShareLanSession(
                invite,
                onDismiss = { accepted = null },
                onImported = { accepted = null },
            )
        }
    }
}
