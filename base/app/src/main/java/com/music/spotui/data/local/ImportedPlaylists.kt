package com.music.spotui.data.local

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * One song of an imported playlist: a snapshot of a catalog song (its Spotify track id,
 * the names and the cover as the source listed them), not a phone file — so it is not a
 * [LocalItem]. It plays through the audio-source extensions like any catalog song.
 * [key]: this row's own id (rows are taken out by key).
 *
 * A song whose source is not Spotify (an Amazon Music link, a playlist file) has no
 * Spotify track id: [spotifyTrackId] is "" and the row is identified by its own key and,
 * where the source stated one, its [asin]. Such a row plays under the catalog identity
 * the import's completion attached to it ([catalogKey]) — the app asks the extensions
 * about a song under an id (a Spotify id, or a catalog key like "deezer.3135556") and
 * refuses a row carrying neither, however clean its title and artist read. It never
 * plays by a Spotify id it does not have. The extensions themselves then resolve the
 * song from the ask's metadata — title, artist, duration — so the row's [durationMs]
 * is a playability field too, not display data (see [playId]).
 */
data class ImportedSong(
    val key: String,
    val spotifyTrackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String,
    /** The recording's ISRC, when the source's read carried it (the Spotify API does, the
     * embed page does not): playback and downloads match sources by it, no lookup needed.
     * "" when unknown — playlists stored before it was kept read as "". */
    val isrc: String = "",
    /** The song's Amazon ASIN, when the source stated one (an Amazon page's track link,
     * a playlist file's track id): the row's identity at its own source, and the store's
     * way of telling two id-less rows of one song apart. "" when unknown. */
    val asin: String = "",
    /** What the source states about the song's explicitness (Spotify's reads state it,
     * the catalog completion can add it): UNKNOWN when nothing states it — playlists
     * stored before it was kept read as UNKNOWN, and UNKNOWN is never badged or hidden. */
    val explicit: app.myspoti.api.Explicitness = app.myspoti.api.Explicitness.UNKNOWN,
    /** The song's identity in the app's own catalog ("deezer.3135556" — the key
     * [com.music.spotui.data.catalog.CatalogRuntime.keyOf] makes of a catalog track),
     * attached by the catalog completion under its strict bar when the row's own
     * source gave it no Spotify id. It is the identity the row plays under (see
     * [playId]): a row of a catalog song in a search plays under exactly such a key.
     * "" when no completion has matched the row — playlists stored before it was
     * kept read as "". */
    val catalogKey: String = "",
) {

    /** How this song is told apart inside one playlist: its Spotify id, else its ASIN,
     * else nothing — a song with neither cannot be told apart from another. */
    val identity: String get() = spotifyTrackId.ifBlank { asin }

    /**
     * The id this song is played under: its Spotify id when it has one, else the
     * catalog key its completion attached. Blank: the row carries no identity the
     * app will ask the extensions by at all (ExtensionAudio.playList refuses a
     * blank one outright, which is where an Amazon row with a clean title and no
     * identity used to die). An identity alone is not playability, though: the
     * extensions resolve the song by the metadata the ask carries — title, artist
     * and duration — and a row whose duration was never filled is refused by the
     * hooks themselves (see CatalogCompletion's doc), which is why the completion
     * fills a missing duration under the same bar as the identity.
     */
    val playId: String get() = spotifyTrackId.ifBlank { catalogKey }
}

/** A playlist brought in from a public link (see PlaylistImport), kept on this phone only. */
data class ImportedPlaylist(
    val id: String,
    val name: String,
    val sourceUrl: String,
    val items: List<ImportedSong>,
    val createdAt: Long,
    /** The user's own cover picture: a file in the app's covers folder, "" for the songs' own art. */
    val cover: String = "",
    /**
     * Where the import came by — "spotify", "amazon" or "csv" — stated by the import
     * itself, so every surface reads the same "Imported from …" truth (the Library
     * list once hardcoded Spotify while an Amazon import's own page said Amazon).
     * "" in playlists stored before it was kept: those are read from [sourceUrl].
     */
    val origin: String = "",
)

/**
 * Playlists imported from public playlist links, stored in the app's files like the local
 * playlists. Spotify cannot hold them for an account that is not signed in, and the songs
 * are catalog songs, not files: their snapshots are all the page needs to list and play them.
 */
object ImportedPlaylists {
    private const val VERSION = 1
    private lateinit var file: File
    private val lock = Any()

    val playlists: SnapshotStateMap<String, ImportedPlaylist> = mutableStateMapOf()

    @Volatile private var loaded = false

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "imported_playlists.json")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { ensureLoaded() }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded || !::file.isInitialized) return
            loaded = true
            runCatching { read(file.readText()) }
        }
    }

    /** Newest first. */
    fun list(): List<ImportedPlaylist> {
        ensureLoaded()
        return playlists.values.sortedByDescending { it.createdAt }
    }

    fun get(id: String): ImportedPlaylist? {
        ensureLoaded()
        return playlists[id]
    }

    /**
     * Keeps an imported list: every song gets its own row key here. A song is in a playlist
     * at most once (the store's rule, as in [LocalPlaylists]): a source list that names the
     * same track twice imports it once, first listing first; a song with no identity at its
     * source (no Spotify id, no ASIN) cannot be told apart from another, so it is always kept.
     *
     * Re-importing a link the phone already holds ([sourceUrl] equal, non-blank) REPLACES
     * that playlist's songs in place — same id, same cover, same place in the list — rather
     * than adding a second copy: re-importing is how a broken import is repaired, and a
     * duplicate is nobody's repair. A file import (no link) always makes a new playlist.
     */
    fun create(name: String, sourceUrl: String, songs: List<ImportedSong>, origin: String = ""): ImportedPlaylist {
        ensureLoaded()
        val seen = HashSet<String>()
        val once = songs.filter { it.identity.isBlank() || seen.add(it.identity) }
        val rows = once.map { it.copy(key = UUID.randomUUID().toString()) }
        val held = if (sourceUrl.isBlank()) null
            else playlists.values.firstOrNull { it.sourceUrl == sourceUrl }
        val p = if (held == null) ImportedPlaylist(
            UUID.randomUUID().toString(), name.trim(), sourceUrl, rows, System.currentTimeMillis(),
            origin = origin,
        ) else held.copy(
            name = name.trim().ifBlank { held.name }, items = rows,
            origin = origin.ifBlank { held.origin },
        )
        update { playlists[p.id] = p }
        return p
    }

    fun rename(id: String, name: String) = update { playlists[id]?.let { playlists[id] = it.copy(name = name.trim()) } }

    fun delete(id: String) = update {
        playlists.remove(id)?.let { PlaylistCovers.delete(it.cover) }
    }

    /** Points the playlist at its own cover picture (a file in the covers folder). */
    fun setCover(id: String, path: String) = update {
        playlists[id]?.let { p ->
            if (p.cover != path) PlaylistCovers.delete(p.cover)
            playlists[id] = p.copy(cover = path)
        }
    }

    /** Back to the songs' own art: the cover file goes with the field. */
    fun clearCover(id: String) = update {
        playlists[id]?.let { p ->
            PlaylistCovers.delete(p.cover)
            playlists[id] = p.copy(cover = "")
        }
    }

    /** Takes one row out (by its own key, so a repeated song loses only the tapped copy). */
    fun remove(id: String, key: String) =
        update { playlists[id]?.let { playlists[id] = it.copy(items = it.items.filterNot { s -> s.key == key }) } }

    /** A picked song as an imported row: the model's own facts (its Spotify id, ISRC,
     * cover, album, duration and stated explicitness), no row key yet — [add] assigns one. */
    fun rowOf(song: com.music.spotui.data.entity.SongsModel): ImportedSong = ImportedSong(
        key = "", spotifyTrackId = song.spotifyTrackId, title = song.title, artist = song.singer,
        album = song.album, durationMs = song.durationMs.toLong(), coverUrl = song.coverUri,
        isrc = song.isrc, explicit = song.explicitStatus,
    )

    /** A phone file as an imported row: no catalog identity, so the row plays by its
     * title and artist, like an Amazon import's rows. */
    fun rowOf(track: LocalTrack): ImportedSong = ImportedSong(
        key = "", spotifyTrackId = "", title = track.title, artist = track.artist,
        album = track.album, durationMs = track.durationMs, coverUrl = track.artUri.toString(),
    )

    /**
     * Adds [song] to the playlist, appended last with a fresh row key (the "Add to
     * playlist" pickers' path — the store had no add at all before v23, so a user
     * whose playlists are all imported could add a song to none of them). The
     * store's no-duplicates rule decides by identity (Spotify id, else ASIN — see
     * [create]): a song already in under the same identity is not added again, and
     * false says so, so the caller can say "Already in …" instead of a silent
     * success. A song with no identity cannot be told apart from another, so it is
     * always added. False also when there is no such playlist.
     */
    fun add(id: String, song: ImportedSong): Boolean {
        var added = false
        update {
            playlists[id]?.let { p ->
                if (song.identity.isBlank() || p.items.none { it.identity == song.identity }) {
                    playlists[id] = p.copy(items = p.items + song.copy(key = UUID.randomUUID().toString()))
                    added = true
                }
            }
        }
        return added
    }

    /** Adds every song of [songs] the playlist does not hold yet (see [add]). */
    fun addAll(id: String, songs: List<ImportedSong>): AddResult {
        var result = AddResult(0, 0)
        update {
            playlists[id]?.let { p ->
                val have = p.items.mapTo(HashSet()) { it.identity }
                val fresh = ArrayList<ImportedSong>()
                var skipped = 0
                for (s in songs) {
                    if (s.identity.isBlank() || have.add(s.identity)) fresh += s.copy(key = UUID.randomUUID().toString())
                    else skipped++
                }
                if (fresh.isNotEmpty()) playlists[id] = p.copy(items = p.items + fresh)
                result = AddResult(fresh.size, skipped)
            }
        }
        return result
    }

    /**
     * Repairs a stored playlist's songs from a fresh reading of its source, [freshByTrackId]
     * (Spotify track id → the fresh reading's song of it): the ISRC, the cover and the album
     * name are filled wherever the stored song lacks them — a field the stored song already
     * carries is never overwritten, a song the reading does not know is left as it is, and
     * the list itself never changes (no song added, taken out or moved). For playlists
     * imported before imports kept the full reading — the embed page's rows carry no ISRC,
     * no cover and no album (see Api's repair on the playlist page). Returns the Spotify
     * track ids of the songs this pass completed in any field: the songs whose stored row
     * was part of the broken import, ISRC or art or album alike.
     */
    fun repair(id: String, freshByTrackId: Map<String, ImportedSong>): Set<String> {
        val repaired = LinkedHashSet<String>()
        update {
            playlists[id]?.let { p ->
                var changed = false
                val items = p.items.map { s ->
                    val fresh = freshByTrackId[s.spotifyTrackId]?.takeIf { s.spotifyTrackId.isNotBlank() } ?: return@map s
                    val isrc = if (s.isrc.isBlank()) fresh.isrc else s.isrc
                    val cover = if (s.coverUrl.isBlank()) fresh.coverUrl else s.coverUrl
                    val album = if (s.album.isBlank()) fresh.album else s.album
                    // A duration the stored row lacks (an embed-only reading states
                    // none) takes the fresh reading's: the audio hooks refuse a
                    // duration-less track, so a repaired row must not stay one.
                    val duration = if (s.durationMs > 0) s.durationMs else fresh.durationMs
                    // An unjudged song takes the reading's judgement; a stated one stands.
                    val explicit = if (s.explicit == app.myspoti.api.Explicitness.UNKNOWN) fresh.explicit else s.explicit
                    if (isrc == s.isrc && cover == s.coverUrl && album == s.album && explicit == s.explicit && duration == s.durationMs) s
                    else {
                        changed = true
                        repaired += s.spotifyTrackId
                        s.copy(isrc = isrc, coverUrl = cover, album = album, explicit = explicit, durationMs = duration)
                    }
                }
                if (changed) playlists[id] = p.copy(items = items)
            }
        }
        return repaired
    }

    /**
     * Completes a stored playlist's songs from a catalog completion pass,
     * [completedByKey] (row key → the pass's row of it): the ISRC, the cover, the
     * album name, the duration, the explicitness and the catalog key are filled
     * wherever the stored song lacks them — a field the stored song already
     * carries is never overwritten, a song the pass does not know is left as it
     * is, and the list itself never changes. [repair]'s sibling for the playlists
     * that repair cannot reach: a Spotify repair re-reads by Spotify track id,
     * but an Amazon or file import's rows have none — they are completed from
     * the catalog instead, matched by their own row keys, and the catalog key
     * the pass attaches is the identity those rows play under (see
     * [ImportedSong.playId]); the duration it fills is what the audio hooks
     * resolve by, and a row that never received one stayed unplayable however
     * complete its other fields read. Returns the row keys of the songs this
     * pass completed in any field.
     */
    fun complete(id: String, completedByKey: Map<String, ImportedSong>): Set<String> {
        val done = LinkedHashSet<String>()
        update {
            playlists[id]?.let { p ->
                var changed = false
                val items = p.items.map { s ->
                    val fresh = completedByKey[s.key] ?: return@map s
                    val isrc = if (s.isrc.isBlank()) fresh.isrc else s.isrc
                    val cover = if (s.coverUrl.isBlank()) fresh.coverUrl else s.coverUrl
                    val album = if (s.album.isBlank()) fresh.album else s.album
                    val duration = if (s.durationMs > 0) s.durationMs else fresh.durationMs
                    val explicit = if (s.explicit == app.myspoti.api.Explicitness.UNKNOWN) fresh.explicit else s.explicit
                    val catalogKey = if (s.catalogKey.isBlank()) fresh.catalogKey else s.catalogKey
                    if (isrc == s.isrc && cover == s.coverUrl && album == s.album &&
                        explicit == s.explicit && catalogKey == s.catalogKey && duration == s.durationMs
                    ) s
                    else {
                        changed = true
                        done += s.key
                        s.copy(isrc = isrc, coverUrl = cover, album = album, explicit = explicit, catalogKey = catalogKey, durationMs = duration)
                    }
                }
                if (changed) playlists[id] = p.copy(items = items)
            }
        }
        return done
    }

    private fun update(change: () -> Unit) {
        synchronized(lock) {
            ensureLoaded()
            change()
            if (::file.isInitialized) {
                runCatching { com.music.spotui.data.writeTextSafely(file, write()) }
                    .onFailure { app.myspoti.host.android.Logbook.log("imported playlists not saved: ${it.message}") }
            }
        }
    }

    // ── JSON (the LocalPlaylists shape) ──

    private fun songJson(s: ImportedSong) = JSONObject().put("key", s.key).put("track", s.spotifyTrackId)
        .put("title", s.title).put("artist", s.artist).put("album", s.album).put("duration", s.durationMs).put("cover", s.coverUrl)
        .put("isrc", s.isrc).put("asin", s.asin).put("explicit", s.explicit.name).put("catalog", s.catalogKey)

    private fun songOf(o: JSONObject) = ImportedSong(o.getString("key"), o.getString("track"), o.optString("title"),
        o.optString("artist"), o.optString("album"), o.optLong("duration"), o.optString("cover"), o.optString("isrc"),
        o.optString("asin"),
        // Absent in files written before it was kept: unjudged, never a guess.
        runCatching { app.myspoti.api.Explicitness.valueOf(o.optString("explicit")) }
            .getOrDefault(app.myspoti.api.Explicitness.UNKNOWN),
        // Absent in files written before it was kept: no catalog identity yet.
        o.optString("catalog"))

    internal fun write(): String {
        val lists = JSONArray()
        playlists.values.forEach { p ->
            lists.put(JSONObject().put("id", p.id).put("name", p.name).put("source", p.sourceUrl).put("created", p.createdAt)
                .put("cover", p.cover).put("origin", p.origin)
                .put("items", JSONArray().apply { p.items.forEach { put(songJson(it)) } }))
        }
        return JSONObject().put("version", VERSION).put("playlists", lists).toString()
    }

    internal fun read(text: String) {
        val o = JSONObject(text)
        if (o.optInt("version") != VERSION) return
        val lists = o.optJSONArray("playlists") ?: return
        for (i in 0 until lists.length()) {
            val p = lists.getJSONObject(i)
            val a = p.optJSONArray("items")
            val songs = (0 until (a?.length() ?: 0)).mapNotNull { runCatching { songOf(a!!.getJSONObject(it)) }.getOrNull() }
            playlists[p.getString("id")] = ImportedPlaylist(p.getString("id"), p.optString("name"), p.optString("source"), songs, p.optLong("created"), p.optString("cover"), p.optString("origin"))
        }
    }
}
