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

/** An artist the user follows, as the Library's Artists list needs it: open it by [uri]. */
data class FollowedArtist(
    /** The artist's own uri: a catalog uri ("deezer:artist:123") or a "spotify:artist:…" uri. */
    val uri: String,
    val name: String,
    val image: String,
    val addedAt: Long,
)

/**
 * Artists followed on this phone, kept on this phone only — the sibling of
 * [CatalogLikes] for follows. The platform behind the app is the neutral base (no
 * account): its library keeps nothing and refuses every change, so an artist's
 * follow sent there came back as the "Could not update your library" toast, and a
 * catalog artist's page did not even offer the button (reported: "It won't let me
 * follow an artist"). The follow lives here instead, as a snapshot of the artist,
 * and the Library's Artists list merges it beside the account's list. The routing
 * switch is [CatalogLikes.localOnly], the same one the song likes use: if a real
 * account library is ever wired into the platform, follows go there again.
 */
object LocalFollows {
    private const val VERSION = 1
    private lateinit var file: File
    private val lock = Any()

    /** The followed artists, newest first. Compose state: screens reading it redraw on a follow. */
    val items: SnapshotStateList<FollowedArtist> = mutableStateListOf()

    @Volatile private var loaded = false

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "local_follows.json")
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

    fun isFollowed(uri: String): Boolean {
        if (uri.isBlank()) return false
        ensureLoaded()
        return items.any { it.uri == uri }
    }

    /** Follows or unfollows an artist on this phone. A follow already there keeps its snapshot and place. */
    fun set(artist: FollowedArtist, on: Boolean) {
        if (artist.uri.isBlank()) return
        update {
            val at = items.indexOfFirst { it.uri == artist.uri }
            if (on && at < 0) items.add(0, artist)
            if (!on && at >= 0) items.removeAt(at)
        }
    }

    /** Unfollows by uri alone (a caller that holds no snapshot). */
    fun remove(uri: String) {
        update {
            val at = items.indexOfFirst { it.uri == uri }
            if (at >= 0) items.removeAt(at)
        }
    }

    private fun update(change: () -> Unit) {
        synchronized(lock) {
            ensureLoaded()
            change()
            if (::file.isInitialized) {
                runCatching { com.music.spotui.data.writeTextSafely(file, write()) }
                    .onFailure { app.myspoti.host.android.Logbook.log("local follows not saved: ${it.message}") }
            }
        }
    }

    // ── JSON (the CatalogLikes shape) ──

    internal fun encode(list: List<FollowedArtist>): String {
        val a = JSONArray()
        list.forEach { i ->
            a.put(JSONObject().put("uri", i.uri).put("name", i.name)
                .put("image", i.image).put("added", i.addedAt))
        }
        return JSONObject().put("version", VERSION).put("items", a).toString()
    }

    internal fun decode(text: String): List<FollowedArtist> {
        val o = JSONObject(text)
        if (o.optInt("version") != VERSION) return emptyList()
        val a = o.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull {
            runCatching {
                val i = a.getJSONObject(it)
                FollowedArtist(i.getString("uri"), i.optString("name"), i.optString("image"), i.optLong("added"))
            }.getOrNull()
        }.filter { it.uri.isNotBlank() }
    }

    internal fun write(): String = encode(items)

    internal fun read(text: String) {
        val decoded = runCatching { decode(text) }.getOrDefault(emptyList())
        items.clear()
        items.addAll(decoded)
    }
}
