package com.music.spotui.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.music.spotui.data.local.LocalMetadata
import com.music.spotui.data.local.LocalPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Packing one of this phone's playlists into a .scadifyplaylist file: the manifest, the
 * cover, and every song's bytes, each hashed as it is read (the manifest's SHA-256 is
 * the receiver's proof the song arrived whole). A song whose file cannot be read is
 * left out and counted in [Built.skipped] — the sender hears about it, never silence.
 */
object ShareExport {
    data class Built(val file: File, val manifest: ShareManifest, val skipped: Int)

    /**
     * A playlist readied for sharing, before any transport touches it: the manifest and
     * every entry's bytes on call. The file share packs this into a zip; the same-Wi-Fi
     * share serves the very same entries off it — one plan, two ways out the door.
     */
    data class Packed(val manifest: ShareManifest, val entries: List<ShareZip.Entry>, val skipped: Int) {
        /** An entry's bytes by its manifest name (a song, or the cover), or null when it is not in the pack. */
        fun entry(name: String): ShareZip.Entry? = entries.firstOrNull { it.name == name }
    }

    suspend fun pack(context: Context, playlist: LocalPlaylist): Packed = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val tracks = ArrayList<ShareTrack>()
        val entries = ArrayList<ShareZip.Entry>()
        var total = 0L
        var skipped = 0
        playlist.items.forEachIndexed { index, item ->
            val named = LocalMetadata.apply(item)
            val uri = Uri.parse(item.uri)
            val stats = runCatching { resolver.openInputStream(uri)?.use { ShareHashes.stats(it) } }.getOrNull()
            if (stats == null) { skipped++; return@forEachIndexed }
            val fileName = displayName(context, uri) ?: fallbackName(named.title, named.artist, index)
            val mime = resolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
                ?: "audio/mpeg"
            val ext = fileName.substringAfterLast('.', "").ifBlank {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "mp3"
            }
            val entry = "files/%03d.%s".format(index, ext)
            tracks += ShareTrack(
                entry = entry, fileName = fileName, size = stats.size, sha256 = stats.sha256,
                mime = mime, durationMs = named.durationMs,
                title = named.title, artist = named.artist, album = named.album,
            )
            total += stats.size
            entries += ShareZip.Entry(entry, stored = true, size = stats.size, crc = stats.crc) {
                resolver.openInputStream(uri) ?: throw java.io.IOException("song gone: $fileName")
            }
        }
        val coverFile = playlist.cover.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }
        val manifest = ShareManifest(
            name = playlist.name, senderName = android.os.Build.MODEL.orEmpty().ifBlank { "Android" },
            createdAt = System.currentTimeMillis(), coverEntry = coverFile?.let { ShareZip.COVER_ENTRY },
            totalBytes = total, tracks = tracks,
        )
        val all = ArrayList<ShareZip.Entry>()
        all += ShareZip.Entry(ShareZip.MANIFEST_ENTRY, stored = false) {
            java.io.ByteArrayInputStream(manifest.encode().toByteArray(Charsets.UTF_8))
        }
        if (coverFile != null) all += ShareZip.Entry(ShareZip.COVER_ENTRY, stored = false) { FileInputStream(coverFile) }
        all += entries
        Packed(manifest, all, skipped)
    }

    suspend fun build(context: Context, playlist: LocalPlaylist): Built = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared/playlist").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val packed = pack(context, playlist)
        val out = File(dir, safeFileName(playlist.name) + ShareZip.EXTENSION)
        FileOutputStream(out).use { ShareZip.write(packed.entries, it) }
        Built(out, packed.manifest, packed.skipped)
    }

    /** Hands the built file to the system share sheet (Drive, Quick Share, …). */
    fun share(context: Context, built: Built, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", built.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = ShareZip.MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, chooserTitle).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /** The songs' total size before anything is packed, for the share sheet's size line (-1 when a size is unknown). */
    fun totalSize(context: Context, playlist: LocalPlaylist): Long {
        var total = 0L
        for (item in playlist.items) {
            val size = sizeOf(context, Uri.parse(item.uri)) ?: return -1L
            total += size
        }
        return total
    }

    private fun sizeOf(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    private fun fallbackName(title: String, artist: String, index: Int): String {
        val base = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { "Song ${index + 1}" }
        return safeFileName(base) + ".mp3"
    }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(60).ifBlank { "playlist" }
}
