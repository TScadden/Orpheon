package com.music.spotui.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalCoversTest {

    @Test fun theSongCoverWinsOverEverything() {
        assertEquals("song.jpg", LocalCovers.chooseArt("song.jpg", "content://art/1", ownArtExists = true, albumCover = "album.jpg"))
        assertEquals("song.jpg", LocalCovers.chooseArt("song.jpg", "content://art/1", ownArtExists = false, albumCover = "album.jpg"))
    }

    @Test fun theFilesOwnArtShowsWhenItExists() {
        assertEquals("content://art/1", LocalCovers.chooseArt(null, "content://art/1", ownArtExists = true, albumCover = "album.jpg"))
    }

    @Test fun theAlbumCoverStandsInWhenTheFileHasNoArt() {
        // Tyson's rule: a file with no image of its own takes its album's cover.
        assertEquals("album.jpg", LocalCovers.chooseArt(null, "content://art/1", ownArtExists = false, albumCover = "album.jpg"))
    }

    @Test fun withNoCoversAtAllTheFilesOwnAddressStands() {
        // Its failure is answered by the screens' placeholder, as before covers existed.
        assertEquals("content://art/1", LocalCovers.chooseArt(null, "content://art/1", ownArtExists = false, albumCover = null))
    }

    @Test fun albumKeysTellArtistsApartAndIgnoreCaseAndSpaces() {
        assertEquals(LocalCovers.albumKey("The Kid LAROI", "A Place To Go"), LocalCovers.albumKey(" the kid laroi ", "a place to go"))
        // The same album name under another artist is another album.
        assertEquals(false, LocalCovers.albumKey("Artist A", "Greatest Hits") == LocalCovers.albumKey("Artist B", "Greatest Hits"))
    }

    @Test fun coversSurviveARestartAsText() {
        val songs = mapOf("content://media/1" to "/files/track_covers/a.jpg")
        val albums = mapOf(LocalCovers.albumKey("Artist", "Album") to "/files/album_covers/b.jpg")
        val (s, a) = LocalCovers.decode(LocalCovers.encode(songs, albums))
        assertEquals(songs, s)
        assertEquals(albums, a)
        // Another version's file: no covers, no crash.
        assertEquals(emptyMap<String, String>() to emptyMap<String, String>(), LocalCovers.decode("""{"version":99,"songs":{}}"""))
        assertEquals(emptyMap<String, String>() to emptyMap<String, String>(), LocalCovers.decode("""{"version":1}"""))
    }
}
