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

/** A catalog song the user liked, as the Liked Songs page needs it: play it by [url]. */
data class CatalogLike(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverUri: String,
    val durationMs: Long,
    /** What plays it (a catalog play address, or a Spotify play query); "" rebuilds one. */
    val url: String,
)

/**
 * Catalog songs liked on this phone, kept on this phone only — the sibling of [LocalLikes]
 * for songs that are not files. The platform behind the app is the neutral base (no
 * account): its library keeps nothing and refuses every change, so a catalog song's like
 * sent there came back as the "Could not update your library" toast and the star never
 * stayed lit (reported on a Melzo-played song). The like lives here instead, as a snapshot
 * of the song, and Liked Songs lists it beside the account's list and the liked files.
 * [localOnly] is the routing switch: if a real account library is ever wired into the
 * platform, likes go there again and this store is read no more.
 */
object CatalogLikes {
    private const val VERSION = 1
    private lateinit var file: File
    private val lock = Any()

    /** Likes of catalog songs live on this phone (see the class doc). */
    const val localOnly = true

    /** The liked catalog songs, newest first. Compose state: screens reading it redraw on a like. */
    val items: SnapshotStateList<CatalogLike> = mutableStateListOf()

    @Volatile private var loaded = false

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "catalog_likes.json")
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

    fun isLiked(trackId: String): Boolean {
        if (trackId.isBlank()) return false
        ensureLoaded()
        return items.any { it.trackId == trackId }
    }

    /** The stored snapshot of a liked song, or null when it is not liked here. */
    fun like(trackId: String): CatalogLike? {
        ensureLoaded()
        return items.firstOrNull { it.trackId == trackId }
    }

    /** Likes or unlikes a catalog song on this phone. A like already there keeps its place. */
    fun set(like: CatalogLike, on: Boolean) {
        if (like.trackId.isBlank()) return
        update {
            val at = items.indexOfFirst { it.trackId == like.trackId }
            if (on && at < 0) items.add(0, like)
            if (!on && at >= 0) items.removeAt(at)
        }
    }

    /** Unlikes by id alone (a caller that holds no snapshot). */
    fun remove(trackId: String) {
        update {
            val at = items.indexOfFirst { it.trackId == trackId }
            if (at >= 0) items.removeAt(at)
        }
    }

    private fun update(change: () -> Unit) {
        synchronized(lock) {
            ensureLoaded()
            change()
            if (::file.isInitialized) {
                runCatching { com.music.spotui.data.writeTextSafely(file, write()) }
                    .onFailure { app.myspoti.host.android.Logbook.log("catalog likes not saved: ${it.message}") }
            }
        }
    }

    // ── JSON (the LocalLikes shape) ──

    internal fun encode(list: List<CatalogLike>): String {
        val a = JSONArray()
        list.forEach { i ->
            a.put(JSONObject().put("track", i.trackId).put("title", i.title)
                .put("artist", i.artist).put("album", i.album).put("duration", i.durationMs)
                .put("cover", i.coverUri).put("url", i.url))
        }
        return JSONObject().put("version", VERSION).put("items", a).toString()
    }

    internal fun decode(text: String): List<CatalogLike> {
        val o = JSONObject(text)
        if (o.optInt("version") != VERSION) return emptyList()
        val a = o.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull {
            runCatching {
                val i = a.getJSONObject(it)
                CatalogLike(i.getString("track"), i.optString("title"),
                    i.optString("artist"), i.optString("album"), i.optString("cover"),
                    i.optLong("duration"), i.optString("url"))
            }.getOrNull()
        }.filter { it.trackId.isNotBlank() }
    }

    internal fun write(): String = encode(items)

    internal fun read(text: String) {
        val decoded = runCatching { decode(text) }.getOrDefault(emptyList())
        items.clear()
        items.addAll(decoded)
    }
}
