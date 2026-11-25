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
}
