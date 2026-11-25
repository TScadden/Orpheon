package com.music.spotui.data.offline

import app.myspoti.api.AlbumRef
import app.myspoti.api.ArtistRef
import app.myspoti.api.Image
import app.myspoti.api.Track
import app.myspoti.catalog.Hit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadMetaTest {

    /** A request keyed by a Deezer provider key, as a catalog like stores it. */
    private fun base() = DownloadMeta(
        downloadId = "deezer.3135556", isrc = null,
        title = "A PLACE TO GO", artist = "The Kid LAROI",
        album = "", albumId = "", durationMs = 0L, coverUrl = "https://img/base.jpg",
    )

    private fun track(uri: String, name: String, artist: String, album: String, albumUri: String, durationMs: Long, cover: String?) = Track(
        uri = uri, name = name, durationMs = durationMs,
        artists = listOf(ArtistRef("$uri:artist", artist)),
        album = AlbumRef(albumUri, album, listOfNotNull(cover?.let { Image(it, 640, 640) })),
    )

    private fun deezerHit() = Hit(
        track("deezer:track:3135556", "A PLACE TO GO", "The Kid LAROI", "A PLACE TO GO", "deezer:album:99", 163_000L, "https://img/deezer.jpg"),
        isrc = "AU-UM7-24-00551",
    )

    private fun spotifyHit() = Hit(
        track("spotify:track:4uLU6hMCjMI75M1A2tKUQC", "A PLACE TO GO", "The Kid LAROI", "A PLACE TO GO", "spotify:album:5rFKwPqnBziMYLdnD5rIXG", 163_400L, "https://img/spotify.jpg"),
        isrc = "AUUM72400551",
    )

    @Test fun providerKeyGainsTheProvidersIsrcAndCanonicalNames() {
        val m = DownloadMeta.merge(base(), deezerHit(), null)
        // The recording's ISRC, normalized — the part the download match turns on.
        assertEquals("AUUM72400551", m.isrc)
        // No Spotify counterpart: the download is still asked for by the provider key.
        assertEquals("deezer.3135556", m.downloadId)
        assertEquals("A PLACE TO GO", m.title)
        assertEquals("The Kid LAROI", m.artist)
        assertEquals("A PLACE TO GO", m.album)
        assertEquals(163_000L, m.durationMs)
        assertEquals("https://img/deezer.jpg", m.coverUrl)
        // The provider's own album id is not a Spotify album id and never lands here.
        assertEquals("", m.albumId)
    }

    @Test fun spotifyCounterpartSuppliesTheDownloadIdAndAlbum() {
        val m = DownloadMeta.merge(base(), deezerHit(), spotifyHit())
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", m.downloadId)
        assertEquals("5rFKwPqnBziMYLdnD5rIXG", m.albumId)
        assertEquals("AUUM72400551", m.isrc)
        assertEquals(163_400L, m.durationMs)
        assertEquals("https://img/spotify.jpg", m.coverUrl)
    }

    @Test fun aSpotifyHitNeedsNoSeparateCounterpart() {
        val m = DownloadMeta.merge(base(), spotifyHit(), null)
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", m.downloadId)
        assertEquals("5rFKwPqnBziMYLdnD5rIXG", m.albumId)
        assertEquals("AUUM72400551", m.isrc)
    }

    @Test fun blanksInTheHitKeepTheRequestsOwnValues() {
        val sparse = Hit(
            Track(uri = "deezer:track:3135556", name = "", durationMs = 0L, artists = emptyList(), album = null),
            isrc = null,
        )
        val m = DownloadMeta.merge(base(), sparse, null)
        assertEquals("A PLACE TO GO", m.title)
        assertEquals("The Kid LAROI", m.artist)
        assertEquals("https://img/base.jpg", m.coverUrl)
        assertEquals(0L, m.durationMs)
        assertNull(m.isrc)
    }

    @Test fun anUnusableIsrcInTheHitIsDropped() {
        val upc = Hit(deezerHit().track, isrc = "0602557362806") // a UPC, not an ISRC
        assertNull(DownloadMeta.merge(base(), upc, null).isrc)
    }
}
