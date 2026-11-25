package com.music.spotui.data.local

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Songs of this phone the user liked, kept on this phone only. A device file has no
 * account id, so the account library can never hold it: before this store, liking one
 * was dropped on the floor (SongPlayer.setLiked logged "like not sent, the local song
 * has no Spotify id" and returned) and Liked Songs — read from the account alone —
 * never showed it. The rows are [LocalItem]s, the same shape local playlists keep, so
 * Liked Songs can list and play them like any other local list.
 */
object LocalLikes {
    private const val VERSION = 1
    private lateinit var file: File
    private val lock = Any()

    /** The liked files, newest first. Compose state: screens reading it redraw on a like. */
    val items: SnapshotStateList<LocalItem> = mutableStateListOf()

    @Volatile private var loaded = false

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "local_likes.json")
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

    fun isLiked(track: LocalTrack): Boolean {
        ensureLoaded()
        return items.any { it.uri == track.uri.toString() }
    }

    fun isLikedUri(uri: String): Boolean {
        ensureLoaded()
        return items.any { it.uri == uri }
    }

    /** Likes or unlikes [track] on this phone. A like already there keeps its place. */
    fun set(track: LocalTrack, on: Boolean) {
        val uri = track.uri.toString()
        update {
            val at = items.indexOfFirst { it.uri == uri }
            if (on && at < 0) items.add(0, LocalItem.of(LocalMetadata.apply(track)))
            if (!on && at >= 0) items.removeAt(at)
        }
    }

    /** A title / artist / album edit (see [LocalMetadata]) reaches the liked rows of that file too. */
    fun applyMetadata(uri: String, title: String, artist: String, album: String = "") = update {
        val at = items.indexOfFirst { it.uri == uri }
        if (at >= 0) items[at] = items[at].copy(
            title = title, artist = artist,
            album = if (album.isNotBlank()) album else items[at].album,
        )
    }

    private fun update(change: () -> Unit) {
        synchronized(lock) {
            ensureLoaded()
            change()
            if (::file.isInitialized) {
                runCatching { com.music.spotui.data.writeTextSafely(file, write()) }
                    .onFailure { app.myspoti.host.android.Logbook.log("local likes not saved: ${it.message}") }
            }
        }
    }

    // ── JSON (the LocalPlaylists shape: one occurrence of a file, keyed by a fresh id) ──

    internal fun encode(list: List<LocalItem>): String {
        val a = JSONArray()
        list.forEach { i ->
            a.put(JSONObject().put("key", i.key).put("uri", i.uri).put("title", i.title)
                .put("artist", i.artist).put("album", i.album).put("duration", i.durationMs).put("art", i.artUri))
        }
        return JSONObject().put("version", VERSION).put("items", a).toString()
    }

    internal fun decode(text: String): List<LocalItem> {
        val o = JSONObject(text)
        if (o.optInt("version") != VERSION) return emptyList()
        val a = o.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull {
            runCatching {
                val i = a.getJSONObject(it)
                LocalItem(i.getString("key"), i.getString("uri"), i.optString("title"),
                    i.optString("artist"), i.optString("album"), i.optLong("duration"), i.optString("art"))
            }.getOrNull()
        }
    }

    internal fun write(): String = encode(items)

    internal fun read(text: String) {
        val decoded = runCatching { decode(text) }.getOrDefault(emptyList())
        items.clear()
        items.addAll(decoded)
    }
}
