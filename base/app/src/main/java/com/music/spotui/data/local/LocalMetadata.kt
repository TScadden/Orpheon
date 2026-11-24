package com.music.spotui.data.local

import android.content.Context
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Title / artist the user gave a phone file inside the app. The file's own tags are
 * never rewritten (MediaStore is read-only for another app's files, and the user's
 * files stay as they are); the names live here, keyed by the file's uri, and are
 * applied wherever the file is shown: the Local Files list (through [LocalMusic]),
 * local playlists and Liked Songs (their stored rows are updated in step, see [edit]).
 */
object LocalMetadata {
    private const val VERSION = 1
    private lateinit var file: File
    private val lock = Any()

    /** File uri → (title, artist) as the user typed them. */
    private val overrides = HashMap<String, Pair<String, String>>()

    @Volatile private var loaded = false

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "local_metadata.json")
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

    fun overrideFor(uri: String): Pair<String, String>? {
        ensureLoaded()
        return overrides[uri]
    }

    /** [track] with the user's title / artist for its file, when they set one. */
    fun apply(track: LocalTrack): LocalTrack {
        val (title, artist) = overrideFor(track.uri.toString()) ?: return track
        return track.copy(title = title, artist = artist)
    }

    /** [item] with the user's title / artist for its file, when they set one. */
    fun apply(item: LocalItem): LocalItem {
        val (title, artist) = overrideFor(item.uri) ?: return item
        return item.copy(title = title, artist = artist)
    }

    /**
     * Sets the title / artist of the file at [uri] everywhere the app shows it: the
     * override itself (the Local Files list reads it through [LocalMusic]), and the
     * stored rows of that file in local playlists, playlist additions and Liked Songs.
     */
    fun edit(uri: String, title: String, artist: String) {
        val t = title.trim()
        val a = artist.trim()
        synchronized(lock) {
            ensureLoaded()
            if (t.isBlank()) overrides.remove(uri) else overrides[uri] = t to a
            if (::file.isInitialized) {
                runCatching { com.music.spotui.data.writeTextSafely(file, write()) }
                    .onFailure { app.myspoti.host.android.Logbook.log("local metadata not saved: ${it.message}") }
            }
        }
        if (t.isNotBlank()) {
            LocalPlaylists.applyMetadata(uri, t, a)
            LocalLikes.applyMetadata(uri, t, a)
        }
    }

    // ── JSON ──

    internal fun encode(map: Map<String, Pair<String, String>>): String {
        val names = JSONObject()
        map.forEach { (uri, v) -> names.put(uri, JSONObject().put("title", v.first).put("artist", v.second)) }
        return JSONObject().put("version", VERSION).put("names", names).toString()
    }

    internal fun decode(text: String): Map<String, Pair<String, String>> {
        val o = JSONObject(text)
        if (o.optInt("version") != VERSION) return emptyMap()
        val names = o.optJSONObject("names") ?: return emptyMap()
        val out = HashMap<String, Pair<String, String>>()
        names.keys().forEach { uri ->
            val v = names.optJSONObject(uri) ?: return@forEach
            val title = v.optString("title")
            if (title.isNotBlank()) out[uri] = title to v.optString("artist")
        }
        return out
    }

    internal fun write(): String = encode(overrides)

    internal fun read(text: String) {
        overrides.clear()
        overrides.putAll(runCatching { decode(text) }.getOrDefault(emptyMap()))
    }
}
