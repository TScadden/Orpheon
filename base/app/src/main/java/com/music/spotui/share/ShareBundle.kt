package com.music.spotui.share

import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.local.LocalFileMatch
import com.music.spotui.data.local.LocalTrack
import org.json.JSONArray
import org.json.JSONObject

/**
 * One song of a shared playlist: where its bytes sit in the bundle, and the names the
 * sender's app shows for it (their title / artist / album edits applied — the receiver's
 * library should look like the sender's, junk file tags and all overridden).
 */
data class ShareTrack(
    val entry: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val mime: String,
    val durationMs: Long,
    val title: String,
    val artist: String,
    val album: String,
)

/**
 * The bundle's table of contents (manifest.json at the zip's root). One format for every
 * transport: phase 1 carries it in a .scadifyplaylist file; the LAN/QR phase will serve
 * the same manifest over the local network.
 */
data class ShareManifest(
    val name: String,
    val senderName: String,
    val createdAt: Long,
    /** The zip entry holding the playlist's cover picture, or null when it has none. */
    val coverEntry: String?,
    val totalBytes: Long,
    val tracks: List<ShareTrack>,
) {
    fun encode(): String {
        val songs = JSONArray()
        tracks.forEach { t ->
            songs.put(
                JSONObject().put("kind", "file").put("entry", t.entry).put("fileName", t.fileName)
                    .put("size", t.size).put("sha256", t.sha256).put("mime", t.mime)
                    .put("durationMs", t.durationMs).put("title", t.title)
                    .put("artist", t.artist).put("album", t.album),
            )
        }
        return JSONObject()
            .put("version", VERSION).put("kind", KIND).put("name", name)
            .put("senderName", senderName).put("createdAt", createdAt)
            .put("cover", coverEntry ?: JSONObject.NULL).put("totalBytes", totalBytes)
            .put("tracks", songs)
            .toString()
    }

    companion object {
        const val VERSION = 1
        const val KIND = "scadify-playlist"

        /** A manifest out of its JSON, or null when the text is not one of ours. */
        fun decode(text: String): ShareManifest? = runCatching {
            val o = JSONObject(text)
            if (o.optInt("version") != VERSION || o.optString("kind") != KIND) return null
            val list = o.optJSONArray("tracks") ?: return null
            val tracks = (0 until list.length()).mapNotNull { i ->
                val t = list.optJSONObject(i) ?: return@mapNotNull null
                // Only file tracks travel in this version; anything else is left out and
                // the import's "N of M" count is taken against the manifest's own list.
                if (t.optString("kind") != "file") return@mapNotNull null
                ShareTrack(
                    entry = t.getString("entry"), fileName = t.optString("fileName"),
                    size = t.optLong("size"), sha256 = t.optString("sha256"),
                    mime = t.optString("mime"), durationMs = t.optLong("durationMs"),
                    title = t.optString("title"), artist = t.optString("artist"),
                    album = t.optString("album"),
                )
            }
            ShareManifest(
                name = o.optString("name"), senderName = o.optString("senderName"),
                createdAt = o.optLong("createdAt"),
                coverEntry = o.optString("cover").takeIf { it.isNotBlank() },
                totalBytes = o.optLong("totalBytes"), tracks = tracks,
            )
        }.getOrNull()
    }
}

/** What the receiver does with one song of a bundle. */
sealed interface ShareDecision {
    /** The receiver already owns this song: the playlist row binds to their copy. */
    data class Bind(val track: ShareTrack, val existing: LocalTrack) : ShareDecision

    /** The receiver needs the bytes out of the bundle. */
    data class Fetch(val track: ShareTrack) : ShareDecision
}

/**
 * The import plan, decided before a single byte moves: which of a bundle's songs the
 * receiver already has (by [LocalFileMatch]'s ear — same title and artist, length within
 * its tolerance) and which must be read out of the bundle. Pure, so its tests need no
 * device: the Android side only carries the plan out.
 */
object SharePlan {
    fun decide(manifest: ShareManifest, existing: List<LocalTrack>): List<ShareDecision> {
        val used = HashSet<String>()
        return manifest.tracks.map { track ->
            val found = LocalFileMatch.find(asSong(track), existing)
            // One of the receiver's files can only stand in for one row of the playlist:
            // a second lookalike row reads its own bytes instead of binding twice.
            if (found != null && used.add(found.uri.toString())) ShareDecision.Bind(track, found)
            else ShareDecision.Fetch(track)
        }
    }

    /** How many of the bundle's songs the receiver already owns. */
    fun ownedCount(manifest: ShareManifest, existing: List<LocalTrack>): Int =
        decide(manifest, existing).count { it is ShareDecision.Bind }

    private fun asSong(t: ShareTrack) = SongsModel(
        id = 0, title = t.title, album = t.album, singer = t.artist, coverUri = "", url = "",
        durationMs = t.durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
    )
}
