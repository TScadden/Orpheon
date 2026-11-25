package com.music.spotui.data.local

/**
 * Albums of the phone's own files. A local file has no catalog album behind it —
 * its album is the files on this phone that share its effective album name (the
 * user's override first, the file's own tag otherwise). "Show album" on a local
 * file opens that set, never a catalog match on the name.
 */
object LocalAlbums {
    /**
     * [all] narrowed to one album: the effective album name alone decides, case-
     * and space-insensitively. The artist does NOT narrow — one album the user
     * named is one album, even when its files carry different artist strings
     * (a featuring credit, a differently-cased name). Tyson's pick, Nov 25 2026.
     */
    fun tracksFor(all: List<LocalTrack>, album: String): List<LocalTrack> =
        all.filter { it.album.trim().equals(album.trim(), ignoreCase = true) }

    /**
     * The artist a local album page's header can name: the one artist string
     * every track shares, or null when they differ (the header then reads
     * "Various artists") or there are no tracks.
     */
    fun sharedArtist(tracks: List<LocalTrack>): String? {
        if (tracks.isEmpty()) return null
        val distinct = tracks.map { it.artist }.distinct()
        return if (distinct.size == 1) distinct.single() else null
    }

    /** Whether a list row is a phone file (its play address is one), not a catalog song. */
    fun isLocalRow(url: String, spotifyTrackId: String): Boolean =
        url.startsWith("local:") && spotifyTrackId.isBlank()

    /** Where the player's "go to album" leads for the song that plays. */
    enum class AlbumTap { LOCAL, LOCAL_NO_ALBUM, CATALOG }

    /**
     * The player's "go to album" for the song that plays, decided from the queue's
     * row for it ([url] and [spotifyTrackId] — the same pair the songs' menus
     * judge by, see [isLocalRow]) and the player's own address for it
     * ([nowPlayingUri]).
     *
     * A phone file played from a playlist or Liked Songs carries the queue
     * address "local:content://media/external/audio/media/<id>": a phone file
     * all the same, and its album is the phone's own page, never a catalog match
     * on the name. A file played straight from the files or an album page may
     * have no queue row to judge by: its own address is then the bare
     * "local:<MediaStore id>" form, an id above zero.
     *
     * Downloads and radio keep the catalog route: a download's row carries its
     * Spotify id (so [isLocalRow] refuses it) and its own address's id is below
     * zero; a radio's rows are the catalog's own songs, no "local:" address.
     */
    fun albumTap(url: String, spotifyTrackId: String, nowPlayingUri: String, albumName: String): AlbumTap {
        val bareId = nowPlayingUri.removePrefix("local:").toLongOrNull()
        val local = isLocalRow(url, spotifyTrackId) ||
            (nowPlayingUri.startsWith("local:") && bareId != null && bareId > 0)
        return when {
            !local -> AlbumTap.CATALOG
            albumName.isBlank() -> AlbumTap.LOCAL_NO_ALBUM
            else -> AlbumTap.LOCAL
        }
    }
}
