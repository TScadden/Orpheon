package com.music.spotui.data.local

import android.content.Context
import android.net.Uri
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

/** One occurrence of a phone file in a list, with its own [key] (rows are taken out by key). */
data class LocalItem(
    val key: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artUri: String,
) {
    fun toTrack() = LocalTrack(
        id = key.hashCode().toLong() and 0x7fffffffL,
        uri = Uri.parse(uri), title = title, artist = artist, album = album,
        durationMs = durationMs, artUri = Uri.parse(LocalCovers.effectiveArt(this)),
    )

    companion object {
        fun of(t: LocalTrack) = LocalItem(
            key = UUID.randomUUID().toString(), uri = t.uri.toString(), title = t.title,
            artist = t.artist, album = t.album, durationMs = t.durationMs, artUri = t.artUri.toString(),
        )
    }
}

/** A playlist kept on this phone only (Spotify cannot hold phone files). */
data class LocalPlaylist(
    val id: String,
    val name: String,
    val items: List<LocalItem>,
    val createdAt: Long,
    /** The user's own cover picture: a file in the app's covers folder, "" for the default tile. */
    val cover: String = "",
)

/** How a bulk add went: how many songs went in, and how many were already there. */
data class AddResult(val added: Int, val skipped: Int)

/**
 * Playlists of phone files, and phone files added to Spotify playlists ("Added on this
 * device"). Stored in the app's files; nothing is sent to Spotify, whose API does not
 * accept local files.
 */
object LocalPlaylists {
    private const val VERSION = 1
    private lateinit var file: File
    private val lock = Any()

    val playlists: SnapshotStateMap<String, LocalPlaylist> = mutableStateMapOf()
    /** Spotify playlist id → phone files added to it on this phone. */
    val additions: SnapshotStateMap<String, List<LocalItem>> = mutableStateMapOf()

    @Volatile private var loaded = false

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "local_playlists.json")
        // Read off the main thread; the screens show the lists as they land, and a change made
        // before then reads the file first ([ensureLoaded]) so it cannot overwrite it.
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

    /**
     * Blocks until the store's file has been read ([init] starts the read on its own
     * thread; a reader that cannot wait for the state to land — the watch's answers in a
     * cold process — calls this after init). The read is the lock's own work, so a caller
     * that arrives while it runs waits for that same read, never a second one. Call off
     * the main thread.
     */
    fun awaitLoaded() = ensureLoaded()

    /** Newest first. */
    fun list(): List<LocalPlaylist> = playlists.values.sortedByDescending { it.createdAt }

    fun create(name: String): LocalPlaylist {
        val p = LocalPlaylist(UUID.randomUUID().toString(), name.trim(), emptyList(), System.currentTimeMillis())
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

    /** Back to the default tile: the cover file goes with the field. */
    fun clearCover(id: String) = update {
        playlists[id]?.let { p ->
            PlaylistCovers.delete(p.cover)
            playlists[id] = p.copy(cover = "")
        }
    }

    /**
     * Adds [track] to the playlist. A file is in a playlist at most once — the rule is the
     * store's, so every screen obeys it: false when the file is already there (the caller
     * says so), true when it went in.
     */
    fun add(id: String, track: LocalTrack): Boolean {
        var added = false
        update {
            playlists[id]?.let { p ->
                if (p.items.none { it.uri == track.uri.toString() }) {
                    playlists[id] = p.copy(items = p.items + LocalItem.of(track))
                    added = true
                }
            }
        }
        return added
    }

    /** Adds every track of [tracks] the playlist does not hold yet (see [add]). */
    fun addAll(id: String, tracks: List<LocalTrack>): AddResult {
        var result = AddResult(0, 0)
        update {
            playlists[id]?.let { p ->
                val have = p.items.mapTo(HashSet()) { it.uri }
                val fresh = ArrayList<LocalItem>()
                var skipped = 0
                for (t in tracks) {
                    if (!have.add(t.uri.toString())) skipped++ else fresh += LocalItem.of(t)
                }
                if (fresh.isNotEmpty()) playlists[id] = p.copy(items = p.items + fresh)
                result = AddResult(fresh.size, skipped)
            }
        }
        return result
    }

    /**
     * Adds a catalog [song] to a local playlist (the "Add to playlist" pickers):
     * the row is the song's own stream address, which the local player resolves
     * through the extensions like any queued catalog song — or its downloaded
     * file, when it has one. False when the song is already there, and for a song
     * with no Spotify id (nothing to stream it by).
     */
    fun addSong(id: String, song: com.music.spotui.data.entity.SongsModel): Boolean {
        if (song.spotifyTrackId.isBlank()) return false
        return add(id, com.music.spotui.extensions.ExtensionAudio.localTrack(song))
    }

    /** [addSong] for several songs at once; a song with no Spotify id counts as skipped. */
    fun addAllSongs(id: String, songs: List<com.music.spotui.data.entity.SongsModel>): AddResult {
        val withIds = songs.filter { it.spotifyTrackId.isNotBlank() }
        val r = addAll(id, withIds.map { com.music.spotui.extensions.ExtensionAudio.localTrack(it) })
        return AddResult(r.added, r.skipped + (songs.size - withIds.size))
    }

    fun remove(id: String, key: String) =
        update { playlists[id]?.let { playlists[id] = it.copy(items = it.items.filterNot { i -> i.key == key }) } }

    /** Takes several rows out at once (their own keys, from a multi-select). */
    fun removeAll(id: String, keys: Set<String>) =
        update { playlists[id]?.let { playlists[id] = it.copy(items = it.items.filterNot { i -> i.key in keys }) } }

    /** [add] for a Spotify playlist's on-this-device rows: false when the file is already there. */
    fun addToSpotify(playlistId: String, track: LocalTrack): Boolean {
        var added = false
        update {
            val rows = additions[playlistId].orEmpty()
            if (rows.none { it.uri == track.uri.toString() }) {
                additions[playlistId] = rows + LocalItem.of(track)
                added = true
            }
        }
        return added
    }

    /** [addAll] for a Spotify playlist's on-this-device rows. */
    fun addAllToSpotify(playlistId: String, tracks: List<LocalTrack>): AddResult {
        var result = AddResult(0, 0)
        update {
            val rows = additions[playlistId].orEmpty()
            val have = rows.mapTo(HashSet()) { it.uri }
            val fresh = ArrayList<LocalItem>()
            var skipped = 0
            for (t in tracks) {
                if (!have.add(t.uri.toString())) skipped++ else fresh += LocalItem.of(t)
            }
            if (fresh.isNotEmpty()) additions[playlistId] = rows + fresh
            result = AddResult(fresh.size, skipped)
        }
        return result
    }

    fun removeFromSpotify(playlistId: String, key: String) = update {
        val left = additions[playlistId].orEmpty().filterNot { it.key == key }
        if (left.isEmpty()) additions.remove(playlistId) else additions[playlistId] = left
    }

    /** Several on-this-device rows of a Spotify playlist out at once (their own keys). */
    fun removeAllFromSpotify(playlistId: String, keys: Set<String>) = update {
        val left = additions[playlistId].orEmpty().filterNot { it.key in keys }
        if (left.isEmpty()) additions.remove(playlistId) else additions[playlistId] = left
    }

    /** A title / artist / album edit (see [LocalMetadata]) reaches every stored row of that file. */
    fun applyMetadata(uri: String, title: String, artist: String, album: String = "") = update {
        fun LocalItem.named() = copy(
            title = title, artist = artist,
            album = if (album.isNotBlank()) album else this.album,
        )
        playlists.forEach { (id, p) ->
            if (p.items.any { it.uri == uri }) playlists[id] = p.copy(items = p.items.map { if (it.uri == uri) it.named() else it })
        }
        additions.forEach { (id, list) ->
            if (list.any { it.uri == uri }) additions[id] = list.map { if (it.uri == uri) it.named() else it }
        }
    }

    private fun update(change: () -> Unit) {
        synchronized(lock) {
            ensureLoaded()
            change()
            if (::file.isInitialized) {
                runCatching { com.music.spotui.data.writeTextSafely(file, write()) }
                    .onFailure { app.myspoti.host.android.Logbook.log("local playlists not saved: ${it.message}") }
            }
        }
    }

    // ── JSON ──

    private fun itemJson(i: LocalItem) = JSONObject().put("key", i.key).put("uri", i.uri).put("title", i.title)
        .put("artist", i.artist).put("album", i.album).put("duration", i.durationMs).put("art", i.artUri)

    private fun itemOf(o: JSONObject) = LocalItem(o.getString("key"), o.getString("uri"), o.optString("title"),
        o.optString("artist"), o.optString("album"), o.optLong("duration"), o.optString("art"))

    private fun items(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { runCatching { itemOf(a!!.getJSONObject(it)) }.getOrNull() }

    internal fun write(): String {
        val lists = JSONArray()
        playlists.values.forEach { p ->
            lists.put(JSONObject().put("id", p.id).put("name", p.name).put("created", p.createdAt)
                .put("cover", p.cover)
                .put("items", JSONArray().apply { p.items.forEach { put(itemJson(it)) } }))
        }
        val adds = JSONObject()
        additions.forEach { (id, list) -> adds.put(id, JSONArray().apply { list.forEach { put(itemJson(it)) } }) }
        return JSONObject().put("version", VERSION).put("playlists", lists).put("additions", adds).toString()
    }

    internal fun read(text: String) {
        val o = JSONObject(text)
        if (o.optInt("version") != VERSION) return
        val lists = o.optJSONArray("playlists") ?: JSONArray()
        for (i in 0 until lists.length()) {
            val p = lists.getJSONObject(i)
            playlists[p.getString("id")] = LocalPlaylist(p.getString("id"), p.optString("name"), items(p.optJSONArray("items")), p.optLong("created"), p.optString("cover"))
        }
        val adds = o.optJSONObject("additions") ?: JSONObject()
        adds.keys().forEach { id -> items(adds.optJSONArray(id)).takeIf { it.isNotEmpty() }?.let { additions[id] = it } }
    }
}
