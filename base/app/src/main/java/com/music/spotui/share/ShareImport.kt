package com.music.spotui.share

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.MediaStore
import com.music.spotui.data.local.LocalMetadata
import com.music.spotui.data.local.LocalMusic
import com.music.spotui.data.local.LocalPlaylists
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.data.local.PlaylistCovers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * The receiving half of a shared playlist. Songs the phone already owns are bound, not
 * copied (see [SharePlan]); the rest stream out of the bundle into MediaStore as real
 * files under Music/Scadify/, each checked against the manifest's size and SHA-256
 * before it counts. What arrived is reported as "N of M" — a song that fails is counted
 * and named in the count, never silently dropped.
 *
 * The MediaStore write path is deliberately thin: insert a pending row, stream the
 * bytes into it, publish it. It cannot be exercised off a device; everything around it
 * (manifest, plan, zip) is unit-tested.
 */
object ShareImport {
    /** Received songs land here, where the Local Files scan sees them as music. */
    const val RECEIVE_PATH = "Music/Scadify/"

    /** The file doors need MediaStore's scoped-storage insert: Android 10 (API 29) and up. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    data class Preview(val manifest: ShareManifest, val ownedCount: Int, val freeBytes: Long) {
        val spaceOk: Boolean get() = freeBytes >= manifest.totalBytes
    }

    data class Result(
        val playlistId: String?,
        val imported: Int,
        val total: Int,
        val failed: Int,
        val cancelled: Boolean,
    )

    /** A bundle's manifest plus the confirm screen's facts, or null when the file is not a bundle. */
    suspend fun preview(context: Context, uri: Uri): Preview? = withContext(Dispatchers.IO) {
        val manifest = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ShareZip.readManifest(it) }
        }.getOrNull() ?: return@withContext null
        previewManifest(context, manifest)
    }

    /** The confirm screen's facts for a manifest that arrived another way (the same-Wi-Fi share reads it off the wire). */
    suspend fun previewManifest(context: Context, manifest: ShareManifest): Preview = withContext(Dispatchers.IO) {
        val owned = SharePlan.ownedCount(manifest, LocalMusic.tracks(context))
        Preview(manifest, owned, StatFs(context.filesDir.path).availableBytes)
    }

    suspend fun import(
        context: Context,
        uri: Uri,
        manifest: ShareManifest,
        cancelled: AtomicBoolean,
        onProgress: (done: Int, total: Int, name: String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val decisions = SharePlan.decide(manifest, LocalMusic.tracks(context))
        val bound = HashMap<String, LocalTrack>()
        val toFetch = HashMap<String, ShareTrack>()
        decisions.forEach { d ->
            when (d) {
                is ShareDecision.Bind -> bound[d.track.entry] = d.existing
                is ShareDecision.Fetch -> toFetch[d.track.entry] = d.track
            }
        }
        val arrived = HashMap<String, LocalTrack>()
        val received = ArrayList<Uri>()
        var failed = 0
        var done = 0
        var coverTemp: File? = null
        val input = resolver.openInputStream(uri)
        if (input == null) return@withContext Result(null, 0, manifest.tracks.size, manifest.tracks.size, false)
        ZipInputStream(input.buffered()).use { zip ->
            while (!cancelled.get()) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == manifest.coverEntry -> {
                        val tmp = File(context.cacheDir, "shared-cover.jpg")
                        runCatching { tmp.outputStream().use { zip.copyTo(it) } }
                            .onSuccess { coverTemp = tmp }.onFailure { tmp.delete() }
                    }
                    toFetch.containsKey(entry.name) -> {
                        val track = toFetch.getValue(entry.name)
                        onProgress(done, toFetch.size, track.title)
                        val got = writeSong(context, track, zip, cancelled)
                        if (got != null) { arrived[track.entry] = got; received += got.uri } else failed++
                        done++
                        onProgress(done, toFetch.size, track.title)
                    }
                    else -> Unit // the manifest, or a song already owned: read past it.
                }
                zip.closeEntry()
            }
        }
        if (cancelled.get()) {
            discardUnreferenced(context, received)
            coverTemp?.delete()
            return@withContext Result(null, 0, manifest.tracks.size, failed, cancelled = true)
        }
        finishImport(context, manifest, bound, arrived, coverTemp)
    }

    /**
     * The same-Wi-Fi share's landing: every song the plan said to fetch already sits in a
     * temp file, checked against the manifest's size and hash by the transfer that
     * brought it. From here the road is the file share's own — bind what the phone owns,
     * write the rest into MediaStore, make the playlist.
     */
    suspend fun importFiles(
        context: Context,
        manifest: ShareManifest,
        files: Map<String, File>,
        coverFile: File?,
        cancelled: AtomicBoolean,
        onProgress: (done: Int, total: Int, name: String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val decisions = SharePlan.decide(manifest, LocalMusic.tracks(context))
        val byEntry = decisions.associateBy { d -> when (d) { is ShareDecision.Bind -> d.track.entry; is ShareDecision.Fetch -> d.track.entry } }
        val bound = HashMap<String, LocalTrack>()
        val toFetch = ArrayList<ShareTrack>()
        manifest.tracks.forEach { t ->
            when (val d = byEntry[t.entry]) {
                is ShareDecision.Bind -> bound[t.entry] = d.existing
                is ShareDecision.Fetch -> toFetch += d.track
                null -> Unit
            }
        }
        val arrived = HashMap<String, LocalTrack>()
        val received = ArrayList<Uri>()
        var failed = 0
        var done = 0
        for (track in toFetch) {
            if (cancelled.get()) break
            onProgress(done, toFetch.size, track.title)
            val file = files[track.entry]
            val got = file?.let { runCatching { it.inputStream().buffered().use { s -> writeSong(context, track, s, cancelled) } }.getOrNull() }
            if (got != null) { arrived[track.entry] = got; received += got.uri } else failed++
            done++
            onProgress(done, toFetch.size, track.title)
        }
        if (cancelled.get()) {
            discardUnreferenced(context, received)
            return@withContext Result(null, 0, manifest.tracks.size, failed, cancelled = true)
        }
        finishImport(context, manifest, bound, arrived, coverFile)
    }

    /** What every road ends with: the playlist, its songs in the bundle's order, its cover. */
    private fun finishImport(
        context: Context,
        manifest: ShareManifest,
        bound: Map<String, LocalTrack>,
        arrived: Map<String, LocalTrack>,
        coverTemp: File?,
    ): Result {
        val inOrder = manifest.tracks.mapNotNull { t -> bound[t.entry] ?: arrived[t.entry] }
        val playlist = LocalPlaylists.create(manifest.name.ifBlank { context.getString(app.myspoti.engine.R.string.import_default_name) })
        val add = LocalPlaylists.addAll(playlist.id, inOrder)
        coverTemp?.let { tmp ->
            val path = PlaylistCovers.save(context, Uri.fromFile(tmp))
            if (path != null) LocalPlaylists.setCover(playlist.id, path)
            tmp.delete()
        }
        LocalMusic.invalidate()
        return Result(playlist.id, add.added, manifest.tracks.size, manifest.tracks.size - add.added, cancelled = false)
    }

    /**
     * One song out of the bundle into MediaStore: a pending row, the bytes streamed in
     * while they are hashed, the manifest's size and hash as the gate, then published.
     * The stream is whatever carried the song — a zip entry, a temp file the network
     * transfer filled; the gate is the same. Null when anything does not check out —
     * the row goes with the failure.
     */
    private fun writeSong(context: Context, track: ShareTrack, source: java.io.InputStream, cancelled: AtomicBoolean): LocalTrack? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, track.fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, track.mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, RECEIVE_PATH)
            put(MediaStore.Audio.Media.TITLE, track.title)
            put(MediaStore.Audio.Media.ARTIST, track.artist)
            put(MediaStore.Audio.Media.ALBUM, track.album)
            if (track.durationMs > 0) put(MediaStore.Audio.Media.DURATION, track.durationMs)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), values)
        }.getOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        val ok = runCatching {
            resolver.openOutputStream(uri, "w")!!.use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled.get()) throw InterruptedException("import cancelled")
                    val n = source.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    written += n
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            check(written == track.size && sha == track.sha256) { "bundle song does not match its manifest" }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        }.isSuccess
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        // The sender's names are the song's names here too (the file's own tags may be junk).
        if (track.title.isNotBlank()) LocalMetadata.edit(uri.toString(), track.title, track.artist, track.album)
        return LocalTrack(
            id = runCatching { ContentUris.parseId(uri) }.getOrDefault(0L),
            uri = uri, title = track.title, artist = track.artist, album = track.album,
            durationMs = track.durationMs, artUri = albumArtUri(context, uri), folder = RECEIVE_PATH,
        )
    }

    private fun albumArtUri(context: Context, uri: Uri): Uri {
        val albumId = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.ALBUM_ID), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }.getOrNull() ?: 0L
        return ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
    }

    /**
     * A cancelled import takes its files back out — but only the ones no playlist holds:
     * a file another list points at stays, override and all.
     */
    private fun discardUnreferenced(context: Context, received: List<Uri>) {
        if (received.isEmpty()) return
        val held = HashSet<String>()
        LocalPlaylists.playlists.values.forEach { p -> p.items.forEach { held += it.uri } }
        LocalPlaylists.additions.values.forEach { list -> list.forEach { held += it.uri } }
        received.forEach { uri ->
            if (uri.toString() !in held) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                LocalMetadata.edit(uri.toString(), "", "", "")
            }
        }
    }
}
