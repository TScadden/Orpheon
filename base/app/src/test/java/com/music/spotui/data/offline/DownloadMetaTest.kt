package com.music.spotui.data.offline

import com.music.spotui.catalog.Hit
import com.music.spotui.catalog.Track
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

    private fun deezerHit() = Hit(
        Track(
            id = "3135556", name = "A PLACE TO GO", artist = "The Kid LAROI",
            album = "A PLACE TO GO", albumId = "99", durationMs = 163_000L,
            coverUrl = "https://img/deezer.jpg",
        ),
        isrc = "AU-UM7-24-00551",
    )

    private fun spotifyHit() = Hit(
        Track(
            id = "4uLU6hMCjMI75M1A2tKUQC", name = "A PLACE TO GO", artist = "The Kid LAROI",
            album = "A PLACE TO GO", albumId = "5rFKwPqnBziMYLdnD5rIXG", durationMs = 163_400L,
            coverUrl = "https://img/spotify.jpg",
        ),
        isrc = "AUUM72400551",
    )

    @Test fun providerKeyGainsTheProvidersIsrcAndCanonicalNames() {
        val m = DownloadMeta.merge(base(), deezerHit(), null)
        // The recording's ISRC, normalized — the part the download match turns on.
        assertEquals("AUUM72400551", m.isrc)
        // No Spotify counterpart: the download is still asked for by the provider key.
        assertEquals("deezer.3135556", m.downloadId)
        assertEquals("A PLACE TO GO", m.title)
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
            Track(id = "3135556", name = "", artist = "", album = "", albumId = "", durationMs = 0L, coverUrl = null),
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
