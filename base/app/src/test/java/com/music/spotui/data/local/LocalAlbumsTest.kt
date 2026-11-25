package com.music.spotui.data.local

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Local albums group by the album NAME alone (Tyson's pick, Nov 25 2026): one
 * album he named is one album, even when its files carry different artist
 * strings — a differently-cased name, a featuring credit. Different album
 * names stay different albums.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LocalAlbumsTest {

    private fun track(id: Long, artist: String, album: String) = LocalTrack(
        id = id, uri = Uri.parse("content://media/$id"), title = "Song $id", artist = artist,
        album = album, durationMs = 1_000L, artUri = Uri.EMPTY,
    )

    @Test fun tysonsLaroiUnreleasedIsOneAlbum() {
        // The exact split from his phone: three artist strings, one album name.
        val all = listOf(
            track(1, "The Kid LAROI", "Laroi unreleased"),
            track(2, "The Kid Laroi", "Laroi unreleased"),
            track(3, "The Kid Laroi", "Laroi unreleased"),
            track(4, "The Kid Laroi", "Laroi unreleased"),
            track(5, "The Kid Laroi", "Laroi unreleased"),
            track(6, "Laroi x Spooky Black", "Laroi unreleased"),
        )
        assertEquals(all, LocalAlbums.tracksFor(all, "Laroi unreleased"))
        // And the header can name no single artist for the merged page.
        assertNull(LocalAlbums.sharedArtist(all))
    }

    @Test fun theAlbumNameMatchIgnoresCaseAndSpaces() {
        val all = listOf(
            track(1, "Artist", "Greatest Hits"),
            track(2, "Artist", " greatest hits "),
            track(3, "Artist", "GREATEST HITS"),
        )
        assertEquals(all, LocalAlbums.tracksFor(all, "greatest hits"))
    }

    @Test fun differentAlbumNamesStaySeparate() {
        val mine = track(1, "Artist", "Album One")
        val other = track(2, "Artist", "Album Two")
        assertEquals(listOf(mine), LocalAlbums.tracksFor(listOf(mine, other), "Album One"))
    }

    @Test fun blankAlbumStillMatchesOnlyBlankAlbumFiles() {
        val blank = track(1, "Artist", "")
        val named = track(2, "Artist", "Album")
        assertEquals(listOf(blank), LocalAlbums.tracksFor(listOf(blank, named), ""))
    }

    @Test fun sharedArtistNamesTheOneArtistEveryTrackShares() {
        val all = listOf(track(1, "The Kid Laroi", "X"), track(2, "The Kid Laroi", "X"))
        assertEquals("The Kid Laroi", LocalAlbums.sharedArtist(all))
        // All-blank artists share the blank: the header names nobody, not Various.
        assertEquals("", LocalAlbums.sharedArtist(listOf(track(3, "", "X"))))
        assertNull(LocalAlbums.sharedArtist(emptyList()))
    }
}
