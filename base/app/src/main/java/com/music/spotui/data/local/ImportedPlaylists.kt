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
 * [key]: this row's own id (a playlist can hold the same song twice).
 */
data class ImportedSong(
    val key: String,
    val spotifyTrackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String,
)

/** A playlist brought in from a public link (see PlaylistImport), kept on this phone only. */
data class ImportedPlaylist(
    val id: String,
    val name: String,
    val sourceUrl: String,
    val items: List<ImportedSong>,
    val createdAt: Long,
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

    /** Keeps an imported list: every song gets its own row key here. */
    fun create(name: String, sourceUrl: String, songs: List<ImportedSong>): ImportedPlaylist {
        val p = ImportedPlaylist(
            UUID.randomUUID().toString(), name.trim(), sourceUrl,
            songs.map { it.copy(key = UUID.randomUUID().toString()) }, System.currentTimeMillis(),
        )
        update { playlists[p.id] = p }
        return p
    }

    fun rename(id: String, name: String) = update { playlists[id]?.let { playlists[id] = it.copy(name = name.trim()) } }
    fun delete(id: String) = update { playlists.remove(id) }

    /** Takes one row out (by its own key, so a repeated song loses only the tapped copy). */
    fun remove(id: String, key: String) =
        update { playlists[id]?.let { playlists[id] = it.copy(items = it.items.filterNot { s -> s.key == key }) } }

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

    private fun songOf(o: JSONObject) = ImportedSong(o.getString("key"), o.getString("track"), o.optString("title"),
        o.optString("artist"), o.optString("album"), o.optLong("duration"), o.optString("cover"))

    internal fun write(): String {
        val lists = JSONArray()
        playlists.values.forEach { p ->
            lists.put(JSONObject().put("id", p.id).put("name", p.name).put("source", p.sourceUrl).put("created", p.createdAt)
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
            playlists[p.getString("id")] = ImportedPlaylist(p.getString("id"), p.optString("name"), p.optString("source"), songs, p.optLong("created"))
        }
    }
}
