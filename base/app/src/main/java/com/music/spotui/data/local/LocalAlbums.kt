package com.music.spotui.data.local

/**
 * Albums of the phone's own files. A local file has no catalog album behind it —
 * its album is the files on this phone that share its effective album name (the
 * user's override first, the file's own tag otherwise). "Show album" on a local
 * file opens that set, never a catalog match on the name.
 */
object LocalAlbums {
    /**
     * [all] narrowed to one album: the effective album name must match, and the
     * artist narrows too when the album was opened from a track that has one —
     * two artists can share an album name, and their files are not one album.
     */
    fun tracksFor(all: List<LocalTrack>, artist: String, album: String): List<LocalTrack> =
        all.filter { it.album == album && (artist.isBlank() || it.artist == artist) }

    /** Whether a list row is a phone file (its play address is one), not a catalog song. */
    fun isLocalRow(url: String, spotifyTrackId: String): Boolean =
        url.startsWith("local:") && spotifyTrackId.isBlank()
}
