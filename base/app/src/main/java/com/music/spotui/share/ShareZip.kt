package com.music.spotui.share

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The bundle file itself: a plain zip — manifest.json at the root, the songs under
 * files/, the cover beside them. Audio entries are STORED, never deflated: music files
 * do not compress, and a stored entry streams straight out again on the receiving side.
 */
object ShareZip {
    const val MANIFEST_ENTRY = "manifest.json"
    const val COVER_ENTRY = "cover.jpg"
    const val MIME = "application/vnd.scadify.playlist+zip"
    const val EXTENSION = ".scadifyplaylist"

    /** One entry to write: its bytes come from [open], afresh for every pass over them. */
    class Entry(
        val name: String,
        /** Stored entries need their size and CRC up front (the sender's hashing pass has both). */
        val stored: Boolean,
        val size: Long = -1L,
        val crc: Long = -1L,
        val open: () -> InputStream,
    )

    fun write(entries: List<Entry>, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            val buf = ByteArray(64 * 1024)
            for (e in entries) {
                val ze = ZipEntry(e.name)
                if (e.stored) {
                    ze.method = ZipEntry.STORED
                    ze.size = e.size
                    ze.crc = e.crc
                }
                zip.putNextEntry(ze)
                e.open().use { input -> while (true) { val n = input.read(buf); if (n < 0) break; zip.write(buf, 0, n) } }
                zip.closeEntry()
            }
        }
    }

    /** The manifest out of a bundle stream, or null when the stream is not one of our bundles. */
    fun readManifest(input: InputStream): ShareManifest? {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: return null
                if (e.name == MANIFEST_ENTRY) {
                    return ShareManifest.decode(zip.readBytes().toString(Charsets.UTF_8))
                }
                zip.closeEntry()
            }
        }
    }
}

/** A stream read once, counted three ways: its length, its CRC32 (for stored zip entries), its SHA-256 (the manifest's word that the bytes are the song). */
data class StreamStats(val size: Long, val crc: Long, val sha256: String)

object ShareHashes {
    fun stats(input: InputStream): StreamStats {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        val buf = ByteArray(64 * 1024)
        var size = 0L
        input.use { s ->
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                crc.update(buf, 0, n)
                size += n
            }
        }
        return StreamStats(size, crc.value, digest.digest().joinToString("") { "%02x".format(it) })
    }

    /** The SHA-256 of a stream, lowercase hex — the same spelling the manifest uses. */
    fun sha256(input: InputStream): String = stats(input).sha256
}
