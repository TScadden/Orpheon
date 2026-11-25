package com.music.spotui.wear

import com.music.spotui.data.local.CatalogLike
import com.music.spotui.data.local.ImportedPlaylist
import com.music.spotui.data.local.LocalItem
import com.music.spotui.data.local.LocalPlaylist
import org.json.JSONArray
import org.json.JSONObject

/**
 * The watch's library and track answers for what lives on this phone alone (no music account):
 * the local playlists, the imported playlists and the on-device likes. Pure builders over the
 * stores' data, so the shapes are testable without a phone: [WatchAnswers] feeds them the
 * stores' contents and merges the account's lists (when one is signed in) around them.
 *
 * The track shape is the account answer's ({uri, name, artists, album, durationMs, cover})
 * plus two fields the watch needs to be honest about what it can do with a row:
 * - `isrc`: the recording's fingerprint where the store has it (imported songs do), so the
 *   watch's extensions match the exact recording instead of a same-named one.
 * - `phoneOnly`: the row is a file of this phone with no catalogue identity kept for it (no
 *   track id, no ISRC): the watch cannot reach the file and must not pretend it can fetch the
 *   song. Such rows are for display and for Phone mode, never for the watch's own player.
 */
object WatchLocalAnswers {
    const val LOCAL_PREFIX = "scadify:local:"
    const val IMPORTED_PREFIX = "scadify:imported:"
    const val LIKED_URI = "spotify:collection:tracks"

    /** The phone's own lists as library entries ({uri, name, subtitle}), locals before imports. */
    fun libraryEntries(local: List<LocalPlaylist>, imported: List<ImportedPlaylist>): List<JSONObject> =
        local.map { p ->
            JSONObject().put("uri", LOCAL_PREFIX + p.id).put("name", p.name).put("subtitle", "On this phone")
        } + imported.map { p ->
            JSONObject().put("uri", IMPORTED_PREFIX + p.id).put("name", p.name).put("subtitle", "Imported")
        }

    /** A local playlist's rows: phone files, every one of them phone-only (see the class doc). */
    fun localTracks(p: LocalPlaylist): List<JSONObject> = p.items.map { item ->
        track(uri = "", name = item.title, artists = listOf(item.artist), album = item.album,
            durationMs = item.durationMs, cover = "", isrc = "", phoneOnly = true)
    }

    /** An imported playlist's rows: catalogue songs, by their Spotify id and ISRC where kept. */
    fun importedTracks(p: ImportedPlaylist): List<JSONObject> = p.items.map { s ->
        val resolvable = s.spotifyTrackId.isNotBlank() || s.isrc.isNotBlank()
        track(
            uri = if (s.spotifyTrackId.isNotBlank()) "spotify:track:${s.spotifyTrackId}" else "",
            name = s.title, artists = listOf(s.artist), album = s.album,
            durationMs = s.durationMs, cover = s.coverUrl, isrc = s.isrc, phoneOnly = !resolvable,
        )
    }

    /**
     * The on-device part of Liked Songs: catalogue likes first (they resolve by their track id),
     * then the liked files (phone-only). The account's liked songs are merged around these by
     * the caller, deduped on [dedupeKey].
     */
    fun likedTracks(catalog: List<CatalogLike>, local: List<LocalItem>): List<JSONObject> =
        catalog.map { like ->
            track(
                uri = if (like.trackId.isNotBlank() && ':' !in like.trackId) "spotify:track:${like.trackId}" else like.trackId,
                name = like.title, artists = listOf(like.artist), album = like.album,
                durationMs = like.durationMs, cover = like.coverUri, isrc = "", phoneOnly = false,
            )
        } + local.map { item ->
            track(uri = "", name = item.title, artists = listOf(item.artist), album = item.album,
                durationMs = item.durationMs, cover = "", isrc = "", phoneOnly = true)
        }

    /** One track object, the account answer's shape plus `isrc` and `phoneOnly`. */
    fun track(
        uri: String, name: String, artists: List<String>, album: String,
        durationMs: Long, cover: String, isrc: String, phoneOnly: Boolean,
    ): JSONObject = JSONObject().apply {
        put("uri", uri); put("name", name); put("durationMs", durationMs)
        put("artists", JSONArray().apply { artists.forEach { put(it) } })
        put("album", album)
        if (cover.isNotBlank()) put("cover", cover)
        put("isrc", isrc); put("phoneOnly", phoneOnly)
    }

    /** How two rows of one answer are told apart when merging sources: by uri, else by name + artist. */
    fun dedupeKey(o: JSONObject): String {
        val uri = o.optString("uri")
        if (uri.isNotBlank()) return "u:$uri"
        val artists = o.optJSONArray("artists")
        val first = if (artists != null && artists.length() > 0) artists.optString(0) else ""
        return "n:${o.optString("name").lowercase()}|${first.lowercase()}"
    }
}
