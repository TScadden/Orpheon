package com.music.spotui.data.local

import android.content.Context
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Title / artist / album the user gave a phone file inside the app. The file's own tags are
 * never rewritten (MediaStore is read-only for another app's files, and the user's
 * files stay as they are); the names live here, keyed by the file's uri, and are
 * applied wherever the file is shown: the Local Files list (through [LocalMusic]),
 * local playlists and Liked Songs (their stored rows are updated in step, see [edit]).
 *
 * An override is named by its title: a blank title is no override at all. The artist and
 * the album ride along with it — a blank album in an override means the file's own album
 * shows, so emptying the album field takes the album override away on its own.
 */
object LocalMetadata {
    private const val VERSION = 1
    private lateinit var file: File
    private val lock = Any()

    /** File uri → (title, artist, album) as the user typed them. */
    private val overrides = HashMap<String, Triple<String, String, String>>()

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

    fun overrideFor(uri: String): Triple<String, String, String>? {
        ensureLoaded()
        return overrides[uri]
    }

    /** [track] with the user's title / artist / album for its file, when they set them. */
    fun apply(track: LocalTrack): LocalTrack {
        val (title, artist, album) = overrideFor(track.uri.toString()) ?: return track
        return track.copy(title = title, artist = artist, album = if (album.isNotBlank()) album else track.album)
    }

    /** [item] with the user's title / artist / album for its file, when they set them. */
    fun apply(item: LocalItem): LocalItem {
        val (title, artist, album) = overrideFor(item.uri) ?: return item
        return item.copy(title = title, artist = artist, album = if (album.isNotBlank()) album else item.album)
    }

    /**
     * Sets the title / artist / album of the file at [uri] everywhere the app shows it: the
     * override itself (the Local Files list reads it through [LocalMusic]), and the
     * stored rows of that file in local playlists, playlist additions and Liked Songs.
     */
    fun edit(uri: String, title: String, artist: String, album: String = "") {
        val t = title.trim()
        val a = artist.trim()
        val al = album.trim()
        synchronized(lock) {
            ensureLoaded()
            if (t.isBlank()) overrides.remove(uri) else overrides[uri] = Triple(t, a, al)
            if (::file.isInitialized) {
                runCatching { com.music.spotui.data.writeTextSafely(file, write()) }
                    .onFailure { app.myspoti.host.android.Logbook.log("local metadata not saved: ${it.message}") }
            }
        }
        if (t.isNotBlank()) {
            LocalPlaylists.applyMetadata(uri, t, a, al)
            LocalLikes.applyMetadata(uri, t, a, al)
        }
    }

    // ── JSON ──

    internal fun encode(map: Map<String, Triple<String, String, String>>): String {
        val names = JSONObject()
        map.forEach { (uri, v) -> names.put(uri, JSONObject().put("title", v.first).put("artist", v.second).put("album", v.third)) }
        return JSONObject().put("version", VERSION).put("names", names).toString()
    }

    internal fun decode(text: String): Map<String, Triple<String, String, String>> {
        val o = JSONObject(text)
        if (o.optInt("version") != VERSION) return emptyMap()
        val names = o.optJSONObject("names") ?: return emptyMap()
        val out = HashMap<String, Triple<String, String, String>>()
        names.keys().forEach { uri ->
            val v = names.optJSONObject(uri) ?: return@forEach
            val title = v.optString("title")
            // Files written before the album field have none: their album is no override.
            if (title.isNotBlank()) out[uri] = Triple(title, v.optString("artist"), v.optString("album"))
        }
        return out
    }

    internal fun write(): String = encode(overrides)

    internal fun read(text: String) {
        overrides.clear()
        overrides.putAll(runCatching { decode(text) }.getOrDefault(emptyMap()))
    }
}
