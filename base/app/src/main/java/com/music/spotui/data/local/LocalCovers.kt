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
 * playlist-cover machinery); this store keeps their paths — a song cover by the file's
 * uri, an album cover by the album's name alone (local albums group by name alone
 * since v9, so their covers key the same way). Covers stored before v9 sit under a
 * legacy artist + album key; lookups fall back to those (see [albumCover]) so a cover
 * set on an old artist-split album page is never orphaned.
 *
 * Wherever a file's art shows, the chain is: the song's own cover → the file's own
 * MediaStore art → the album's cover → the art the album lends (the first of its files
 * whose own art exists, [noteBorrowedArt]) → the file's art address as it was (whose
 * failure the screens already answer with a placeholder). Whether MediaStore has art
 * for a file is learned during the [LocalMusic] scan ([noteArtPresence]); before any
 * scan, a file's own art is assumed present, exactly as it behaved before covers
 * existed. Lent art is a display-time fallback only: it lives in memory, is relearned
 * by every scan, and is never written into this store.
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

    /** [albumKey] → the art the album lends its artless files (scan-learned, never saved). */
    private val borrowed = ConcurrentHashMap<String, String>()

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

    /** The key an album's cover is stored under: the album name alone, case/space-insensitive. */
    internal fun albumKey(album: String): String = album.trim().lowercase()

    /** The pre-v9 key: artist and album. Covers stored then are still found by fallback. */
    internal fun legacyAlbumKey(artist: String, album: String): String =
        artist.trim().lowercase() + "\n" + album.trim().lowercase()

    /** Stored legacy keys whose album part is [albumKey]'s value, sorted for a stable pick. */
    private fun legacyKeysFor(normalizedAlbum: String): List<String> =
        albumCovers.keys.filter { it.contains('\n') && it.substringAfterLast('\n') == normalizedAlbum }.sorted()

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

    /**
     * The album cover's path for [album], when the user set one. The name-only key
     * wins; otherwise the first (sorted) pre-v9 artist + album key for this album
     * answers, so a cover set before the grouping change still shows.
     */
    fun albumCover(album: String): String? {
        ensureLoaded()
        return synchronized(lock) {
            val key = albumKey(album)
            albumCovers[key] ?: legacyKeysFor(key).firstOrNull()?.let { albumCovers[it] }
        }
    }

    /**
     * Learns the art each album of [tracks] lends its files that have none: the first
     * file of the album (the library's order, the album page's own) whose own art
     * exists — the art the album page's header borrows. Relearned whole by every scan;
     * files with no album name lend and borrow nothing.
     */
    fun noteBorrowedArt(tracks: List<LocalTrack>) {
        borrowed.clear()
        borrowed.putAll(borrowedArt(tracks.map { Triple(it.album, it.artUri.toString(), ownArtExists(it.artUri.toString())) }))
    }

    /**
     * The art each album lends, from (album, own art address, own art exists) rows in
     * the library's order: the first art-bearing file of each album, by [albumKey].
     */
    internal fun borrowedArt(rows: List<Triple<String, String, Boolean>>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((album, art, exists) in rows) {
            if (!exists) continue
            val key = albumKey(album)
            if (key.isEmpty() || key in out) continue
            out[key] = art
        }
        return out
    }

    /** The art [album] lends its artless files (see [noteBorrowedArt]), or null when it has none to lend. */
    fun borrowedArt(album: String): String? = borrowed[albumKey(album)]

    /**
     * The art chain itself, on plain values: the song's cover wins; the file's own art
     * shows when it exists; the album's cover stands in when it does not; with no cover
     * set, the art the album lends stands in; the file's own address is the last resort
     * (its failure shows the placeholder).
     */
    internal fun chooseArt(songCover: String?, ownArt: String, ownArtExists: Boolean, albumCover: String?, borrowedArt: String? = null): String =
        songCover ?: if (ownArtExists) ownArt else albumCover ?: borrowedArt ?: ownArt

    /** The effective art address of the file at [uri] (display form: content:// or file://). */
    fun effectiveArt(uri: String, album: String, ownArt: String): String = chooseArt(
        songCover(uri)?.let { Uri.fromFile(File(it)).toString() },
        ownArt,
        ownArtExists(ownArt),
        albumCover(album)?.let { Uri.fromFile(File(it)).toString() },
        borrowedArt(album),
    )

    /** The effective art address of a stored local row. */
    fun effectiveArt(item: LocalItem): String = effectiveArt(item.uri, item.album, item.artUri)

    /** [track] with its effective art. */
    fun apply(track: LocalTrack): LocalTrack {
        val art = effectiveArt(track.uri.toString(), track.album, track.artUri.toString())
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

    /**
     * Copies [image] in as the cover of [album]; the new path, or null when unusable.
     * Stored under the name-only key; any pre-v9 artist + album entries for this
     * album are retired (their files deleted) so the new cover is the one cover.
     */
    fun setAlbumCover(context: Context, album: String, image: Uri): String? {
        val path = PlaylistCovers.saveTo(context, image, ALBUMS_FOLDER) ?: return null
        val key = albumKey(album)
        val retired = synchronized(lock) {
            ensureLoaded()
            val old = mutableListOf<String>()
            albumCovers.put(key, path)?.let { old += it }
            legacyKeysFor(key).forEach { albumCovers.remove(it)?.let { p -> old += p } }
            persist()
            old
        }
        retired.forEach { if (it != path) PlaylistCovers.delete(it) }
        LocalMusic.invalidate()
        return path
    }

    /** Takes [album]'s cover away — the name-only entry and any pre-v9 ones for it. */
    fun clearAlbumCover(album: String) {
        val key = albumKey(album)
        val removed = synchronized(lock) {
            ensureLoaded()
            val old = mutableListOf<String>()
            albumCovers.remove(key)?.let { old += it }
            legacyKeysFor(key).forEach { albumCovers.remove(it)?.let { p -> old += p } }
            if (old.isNotEmpty()) persist()
            old
        }
        if (removed.isNotEmpty()) {
            removed.forEach { PlaylistCovers.delete(it) }
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
