package com.music.spotui.share

import android.content.Context
import app.myspoti.pass.ShareClient
import app.myspoti.pass.ShareInvite
import app.myspoti.pass.ShareServer
import com.music.spotui.data.local.LocalMusic
import com.music.spotui.data.local.LocalPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The same-Wi-Fi share's Android side: the wire itself is the pass module's
 * [ShareServer] / [ShareClient]; what lives here is everything that makes it a
 * playlist — the sender serves a [ShareExport.Packed] (the file share's own plan), the
 * receiver brings the manifest home, checks each song as it lands against the
 * manifest's size and hash, and hands the verified files to [ShareImport], the one
 * landing both transports share.
 */
object ShareLan {

    /** A hosting share: the packed playlist served until one receiver has it, or the wait runs out. */
    fun serve(
        packed: ShareExport.Packed,
        playlist: LocalPlaylist,
        onConnected: (name: String) -> Unit = {},
        onSending: (entry: String, sentInFile: Long, size: Long) -> Unit = { _, _, _ -> },
        onFileDone: (entry: String) -> Unit = {},
        onDone: (completed: Boolean) -> Unit = {},
    ): ShareServer {
        val entries = HashMap<String, ShareServer.Entry>()
        packed.manifest.tracks.forEach { t ->
            val e = packed.entry(t.entry) ?: return@forEach
            entries[t.entry] = ShareServer.Entry(t.size) { runCatching { e.open() }.getOrNull() }
        }
        packed.manifest.coverEntry?.let { coverName ->
            val coverFile = playlist.cover.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }
            if (coverFile != null) entries[coverName] = ShareServer.Entry(coverFile.length()) { FileInputStream(coverFile) }
        }
        return ShareServer(
            manifestJson = packed.manifest.encode(),
            entries = entries,
            playlistName = packed.manifest.name,
            onConnected = onConnected,
            onSending = onSending,
            onFileDone = onFileDone,
            onDone = onDone,
        ).start()
    }

    private fun deviceName(): String = android.os.Build.MODEL.orEmpty().ifBlank { "Android" }

    /** One receiving session: the connection, and the manifest it brought. */
    class Session private constructor(val client: ShareClient, val manifest: ShareManifest) {

        /** What the fetching brought home: a temp file per song that checked out, the cover, and the counts. */
        data class Fetched(val files: Map<String, File>, val cover: File?, val failed: Int, val cancelled: Boolean)

        /**
         * Asks for every song the plan says this phone lacks (and the cover), each
         * streamed to a temp file and checked against the manifest's size and SHA-256
         * before it counts — a song that does not check out is left out and counted,
         * never half-kept. Songs the phone already owns are not asked for at all:
         * that is what makes scanning a fresh code after an interrupted try a resume.
         */
        suspend fun fetchAll(
            context: Context,
            cancelled: AtomicBoolean,
            onProgress: (name: String, doneBytes: Long, totalBytes: Long) -> Unit,
        ): Fetched = withContext(Dispatchers.IO) {
            val decisions = SharePlan.decide(manifest, LocalMusic.tracks(context))
            val wanted = decisions.filterIsInstance<ShareDecision.Fetch>().map { it.track }
            val total = wanted.sumOf { it.size }
            val dir = File(context.cacheDir, "shared/received").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val files = HashMap<String, File>()
            var failed = 0
            var doneBytes = 0L
            for (track in wanted) {
                if (cancelled.get()) break
                onProgress(track.title, doneBytes, total)
                val tmp = File(dir, track.entry.replace('/', '_'))
                val ok = runCatching {
                    val written = FileOutputStream(tmp).use { out ->
                        client.fetch(track.entry, out) { inFile -> onProgress(track.title, doneBytes + inFile, total) }
                    }
                    check(written == track.size) { "song arrived short" }
                    check(ShareHashes.sha256(FileInputStream(tmp)) == track.sha256) { "song does not match its manifest" }
                }.isSuccess
                if (ok) { files[track.entry] = tmp; doneBytes += track.size } else { tmp.delete(); failed++ }
                onProgress(track.title, doneBytes, total)
            }
            var cover: File? = null
            if (!cancelled.get()) manifest.coverEntry?.let { coverEntry ->
                val tmp = File(dir, "cover.jpg")
                val ok = runCatching {
                    FileOutputStream(tmp).use { out -> client.fetch(coverEntry, out) }
                    check(tmp.length() > 0) { "empty cover" }
                }.isSuccess
                if (ok) cover = tmp else tmp.delete()
            }
            Fetched(files, cover, failed, cancelled.get())
        }

        /** Everything arrived: the sender can stop listening. */
        fun finish() = client.done()

        fun close() = client.close()

        companion object {
            /** Connects to the code's sender and brings its manifest home. Throws with the reason, in plain words. */
            suspend fun open(invite: ShareInvite): Session = withContext(Dispatchers.IO) {
                val client = ShareClient(invite, deviceName())
                val json = try {
                    client.connect()
                } catch (e: Exception) {
                    client.close()
                    throw e
                }
                val manifest = ShareManifest.decode(json)
                    ?: run { client.close(); throw IOException("the other phone sent a playlist this app cannot read") }
                Session(client, manifest)
            }
        }
    }
}
