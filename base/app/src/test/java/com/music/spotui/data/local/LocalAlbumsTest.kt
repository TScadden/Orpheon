package com.music.spotui.data.local

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // The player's "go to album" (LocalAlbums.albumTap): a phone file reaches
    // its own album page however it was started; downloads and catalog songs
    // keep the catalog route. Tyson's report, Nov 25 2026: "Not Fair" played
    // from his playlist (album "Laroi unreleased") landed on a catalog album.
    @Test fun albumTapSendsAPlaylistFilesRowToItsLocalAlbum() {
        // The queue row of a file played from a local playlist or Liked Songs:
        // a "local:content://…" play address and no Spotify id behind it.
        assertEquals(
            LocalAlbums.AlbumTap.LOCAL,
            LocalAlbums.albumTap(
                url = "local:content://media/external/audio/media/123",
                spotifyTrackId = "",
                nowPlayingUri = "local:content://media/external/audio/media/123",
                albumName = "Laroi unreleased",
            ),
        )
    }

    @Test fun albumTapKeepsTheBareIdFormLocal() {
        // A file played straight from the files or an album page: no queue row
        // to judge by, the player's own address is the bare MediaStore id form.
        assertEquals(
            LocalAlbums.AlbumTap.LOCAL,
            LocalAlbums.albumTap(url = "", spotifyTrackId = "", nowPlayingUri = "local:123", albumName = "Laroi unreleased"),
        )
        assertEquals(
            LocalAlbums.AlbumTap.LOCAL,
            LocalAlbums.albumTap(
                url = "local:content://media/external/audio/media/123",
                spotifyTrackId = "", nowPlayingUri = "local:123", albumName = "Laroi unreleased",
            ),
        )
    }

    @Test fun albumTapKeepsACatalogSongOnTheCatalogRoute() {
        assertEquals(
            LocalAlbums.AlbumTap.CATALOG,
            LocalAlbums.albumTap(
                url = "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
                spotifyTrackId = "4uLU6hMCjMI75M1A2tKUQC",
                nowPlayingUri = "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
                albumName = "F*CK LOVE 3+: OVER YOU",
            ),
        )
    }

    @Test fun albumTapKeepsADownloadOnTheCatalogRoute() {
        // A download wraps as a local track too, but its row carries its
        // Spotify id and its own address's id is below zero: catalog route.
        assertEquals(
            LocalAlbums.AlbumTap.CATALOG,
            LocalAlbums.albumTap(
                url = "local:file:///data/user/0/app.orpheon.beta.clean/files/offline/song.mp3",
                spotifyTrackId = "4uLU6hMCjMI75M1A2tKUQC",
                nowPlayingUri = "local:-12345",
                albumName = "F*CK LOVE 3+: OVER YOU",
            ),
        )
        assertEquals(
            LocalAlbums.AlbumTap.CATALOG,
            LocalAlbums.albumTap(url = "", spotifyTrackId = "", nowPlayingUri = "local:-12345", albumName = "Anything"),
        )
    }

    @Test fun albumTapNamesNoAlbumForAFileWithoutOne() {
        assertEquals(
            LocalAlbums.AlbumTap.LOCAL_NO_ALBUM,
            LocalAlbums.albumTap(
                url = "local:content://media/external/audio/media/123",
                spotifyTrackId = "",
                nowPlayingUri = "local:content://media/external/audio/media/123",
                albumName = "",
            ),
        )
        assertEquals(
            LocalAlbums.AlbumTap.LOCAL_NO_ALBUM,
            LocalAlbums.albumTap(url = "", spotifyTrackId = "", nowPlayingUri = "local:123", albumName = ""),
        )
    }

    // The shared classifier (LocalAlbums.isLocalSong) both songs' menus judge
    // by: every address form a phone file takes in the lists is local, and a
    // row carrying a catalog id never is. Tyson's report, Nov 25 2026: the
    // menu of his local "Unfaithful" (album "The Kid Laroi Unreleased") opened
    // the catalog album "F*CK LOVE 3+: OVER YOU" — its row failed the menus'
    // old weak test and fell into the catalog route.
    @Test fun isLocalSongCoversEveryPhoneFileForm() {
        // The play queue's address for a file (album pages, playlists, Liked Songs).
        assertTrue(LocalAlbums.isLocalSong("local:content://media/external/audio/media/123", ""))
        // The bare MediaStore id form.
        assertTrue(LocalAlbums.isLocalSong("local:123", ""))
        // A file liked into Liked Songs.
        assertTrue(LocalAlbums.isLocalSong("spotify:local:123", ""))
        // A local playlist's own address for a file.
        assertTrue(LocalAlbums.isLocalSong("content://media/external/audio/media/123", ""))
        // No queue row to judge by: the player's own address is the bare id form.
        assertTrue(LocalAlbums.isLocalSong("", "", nowPlayingUri = "local:123"))
        assertTrue(LocalAlbums.isLocalSong("local:content://media/external/audio/media/123", "", nowPlayingUri = "local:123"))
    }

    @Test fun isLocalSongKeepsCatalogRowsAndDownloadsOut() {
        // A catalog song.
        assertFalse(LocalAlbums.isLocalSong("spotify:track:4uLU6hMCjMI75M1A2tKUQC", "4uLU6hMCjMI75M1A2tKUQC"))
        // A catalog song played through an extension stream.
        assertFalse(LocalAlbums.isLocalSong("myspoti-ext://track/4uLU6hMCjMI75M1A2tKUQC", "4uLU6hMCjMI75M1A2tKUQC"))
        // A download: a local-looking address, but the row carries its Spotify id,
        // and its own address's id is below zero.
        assertFalse(LocalAlbums.isLocalSong("local:file:///data/user/0/app.orpheon.beta.clean/files/offline/song.mp3", "4uLU6hMCjMI75M1A2tKUQC"))
        assertFalse(LocalAlbums.isLocalSong("", "", nowPlayingUri = "local:-12345"))
        // The local forms stop being local the moment a catalog id rides along.
        assertFalse(LocalAlbums.isLocalSong("content://media/external/audio/media/123", "4uLU6hMCjMI75M1A2tKUQC"))
        assertFalse(LocalAlbums.isLocalSong("spotify:local:123", "4uLU6hMCjMI75M1A2tKUQC"))
        // Nothing at all.
        assertFalse(LocalAlbums.isLocalSong("", ""))
    }

    @Test fun albumTapFollowsTheSharedClassifierForTheMenuForms() {
        // The two forms the menus used to miss reach the local album now.
        assertEquals(
            LocalAlbums.AlbumTap.LOCAL,
            LocalAlbums.albumTap(url = "spotify:local:123", spotifyTrackId = "", nowPlayingUri = "", albumName = "The Kid Laroi Unreleased"),
        )
        assertEquals(
            LocalAlbums.AlbumTap.LOCAL,
            LocalAlbums.albumTap(url = "content://media/external/audio/media/123", spotifyTrackId = "", nowPlayingUri = "", albumName = "The Kid Laroi Unreleased"),
        )
    }

    @Test fun sharedArtistNamesTheOneArtistEveryTrackShares() {
        val all = listOf(track(1, "The Kid Laroi", "X"), track(2, "The Kid Laroi", "X"))
        assertEquals("The Kid Laroi", LocalAlbums.sharedArtist(all))
        // All-blank artists share the blank: the header names nobody, not Various.
        assertEquals("", LocalAlbums.sharedArtist(listOf(track(3, "", "X"))))
        assertNull(LocalAlbums.sharedArtist(emptyList()))
    }
}
