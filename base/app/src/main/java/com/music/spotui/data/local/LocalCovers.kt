package com.music.spotui.data.local

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Cover pictures the user gave phone files and their albums inside the app: a song's
 * own cover, and an album's cover shared by the album's files. The pictures are copied
 * into the app's files (track_covers/ and album_covers/, through [PlaylistCovers], the
 * playlist-cover machinery); this store keeps their paths, keyed like [LocalMetadata] —
 * a song cover by the file's uri, an album cover by artist + album (an album's name
 * alone collides across artists).
 *
 * Wherever a file's art shows, the chain is: the song's own cover → the file's own
 * MediaStore art → the album's cover → the file's art address as it was (whose failure
 * the screens already answer with a placeholder). Whether MediaStore has art for a
 * file is learned during the [LocalMusic] scan ([noteArtPresence]); before any scan,
 * a file's own art is assumed present, exactly as it behaved before covers existed.
 */
object LocalCovers {
    private const val VERSION = 1
    private const val SONGS_FOLDER = "track_covers"
    private const val ALBUMS_FOLDER = "album_covers"

    private lateinit var file: File
    private val lock = Any()

    /** File uri → the song cover's path. */
    private val songCovers = HashMap<String, String>()

    /** [albumKey] → the album cover's path. */
    private val albumCovers = HashMap<String, String>()

    @Volatile private var loaded = false

    /** MediaStore album-art address → whether art actually came back for it (scan-learned). */
    private val artPresence = ConcurrentHashMap<String, Boolean>()

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "local_covers.json")
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

    /** The key an album's cover is stored under: artist and album, case/space-insensitive. */
    internal fun albumKey(artist: String, album: String): String =
        artist.trim().lowercase() + "\n" + album.trim().lowercase()

    /** Records whether MediaStore has art at [artUri] (the [LocalMusic] scan learns this). */
    fun noteArtPresence(artUri: String, exists: Boolean) {
        artPresence[artUri] = exists
    }

    /** Whether the file's own art is there; unknown reads as present (the pre-cover behaviour). */
    fun ownArtExists(artUri: String): Boolean = artPresence[artUri] ?: true

    /** The song cover's path for the file at [uri], when the user set one. */
    fun songCover(uri: String): String? {
        ensureLoaded()
        return synchronized(lock) { songCovers[uri] }
    }

    /** The album cover's path for [artist]'s [album], when the user set one. */
    fun albumCover(artist: String, album: String): String? {
        ensureLoaded()
        return synchronized(lock) { albumCovers[albumKey(artist, album)] }
    }

    /**
     * The art chain itself, on plain values: the song's cover wins; the file's own art
     * shows when it exists; the album's cover stands in when it does not; the file's
     * own address is the last resort (its failure shows the placeholder).
     */
    internal fun chooseArt(songCover: String?, ownArt: String, ownArtExists: Boolean, albumCover: String?): String =
        songCover ?: if (ownArtExists) ownArt else albumCover ?: ownArt

    /** The effective art address of the file at [uri] (display form: content:// or file://). */
    fun effectiveArt(uri: String, artist: String, album: String, ownArt: String): String = chooseArt(
        songCover(uri)?.let { Uri.fromFile(File(it)).toString() },
        ownArt,
        ownArtExists(ownArt),
        albumCover(artist, album)?.let { Uri.fromFile(File(it)).toString() },
    )

    /** The effective art address of a stored local row. */
    fun effectiveArt(item: LocalItem): String = effectiveArt(item.uri, item.artist, item.album, item.artUri)

    /** [track] with its effective art. */
    fun apply(track: LocalTrack): LocalTrack {
        val art = effectiveArt(track.uri.toString(), track.artist, track.album, track.artUri.toString())
        return if (art == track.artUri.toString()) track else track.copy(artUri = Uri.parse(art))
    }

    /** Copies [image] in as the cover of the file at [uri]; the new path, or null when unusable. */
    fun setSongCover(context: Context, uri: String, image: Uri): String? {
        val path = PlaylistCovers.saveTo(context, image, SONGS_FOLDER) ?: return null
        val old = synchronized(lock) { ensureLoaded(); songCovers.put(uri, path).also { persist() } }
        if (old != null && old != path) PlaylistCovers.delete(old)
        LocalMusic.invalidate()
        return path
    }

    /** Takes the file at [uri]'s own cover away (its art falls back down the chain). */
    fun clearSongCover(uri: String) {
        val old = synchronized(lock) { ensureLoaded(); songCovers.remove(uri).also { if (it != null) persist() } }
        if (old != null) {
            PlaylistCovers.delete(old)
            LocalMusic.invalidate()
        }
    }

    /** Copies [image] in as the cover of [artist]'s [album]; the new path, or null when unusable. */
    fun setAlbumCover(context: Context, artist: String, album: String, image: Uri): String? {
        val path = PlaylistCovers.saveTo(context, image, ALBUMS_FOLDER) ?: return null
        val key = albumKey(artist, album)
        val old = synchronized(lock) { ensureLoaded(); albumCovers.put(key, path).also { persist() } }
        if (old != null && old != path) PlaylistCovers.delete(old)
        LocalMusic.invalidate()
        return path
    }

    /** Takes [artist]'s [album]'s cover away. */
    fun clearAlbumCover(artist: String, album: String) {
        val old = synchronized(lock) { ensureLoaded(); albumCovers.remove(albumKey(artist, album)).also { if (it != null) persist() } }
        if (old != null) {
            PlaylistCovers.delete(old)
            LocalMusic.invalidate()
        }
    }

    private fun persist() {
        if (::file.isInitialized) {
            runCatching { com.music.spotui.data.writeTextSafely(file, write()) }
                .onFailure { app.myspoti.host.android.Logbook.log("local covers not saved: ${it.message}") }
        }
    }

    // ── JSON ──

    internal fun encode(songs: Map<String, String>, albums: Map<String, String>): String {
        val s = JSONObject()
        songs.forEach { (uri, path) -> s.put(uri, path) }
        val a = JSONObject()
        albums.forEach { (key, path) -> a.put(key, path) }
        return JSONObject().put("version", VERSION).put("songs", s).put("albums", a).toString()
    }

    internal fun decode(text: String): Pair<Map<String, String>, Map<String, String>> {
        val o = JSONObject(text)
        if (o.optInt("version") != VERSION) return emptyMap<String, String>() to emptyMap()
        fun read(map: JSONObject?): Map<String, String> {
            if (map == null) return emptyMap()
            val out = HashMap<String, String>()
            map.keys().forEach { k -> map.optString(k).takeIf { it.isNotBlank() }?.let { out[k] = it } }
            return out
        }
        return read(o.optJSONObject("songs")) to read(o.optJSONObject("albums"))
    }

    internal fun write(): String = encode(songCovers, albumCovers)

    internal fun read(text: String) {
        val (songs, albums) = runCatching { decode(text) }.getOrDefault(emptyMap<String, String>() to emptyMap())
        songCovers.clear()
        songCovers.putAll(songs)
        albumCovers.clear()
        albumCovers.putAll(albums)
    }
}
