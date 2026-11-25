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

    @Test fun albumKeysAreTheNameAloneIgnoringCaseAndSpaces() {
        assertEquals(LocalCovers.albumKey("A Place To Go"), LocalCovers.albumKey(" a place to go "))
        // The legacy key still carries the artist, so stored pre-v9 covers parse apart.
        assertEquals(false, LocalCovers.legacyAlbumKey("Artist A", "Greatest Hits") == LocalCovers.legacyAlbumKey("Artist B", "Greatest Hits"))
    }

    @Test fun aCoverSetBeforeV9IsFoundByAlbumNameAlone() {
        // Stored the pre-v9 way, under artist + album, on one of the old split pages.
        val legacy = mapOf(LocalCovers.legacyAlbumKey("The Kid LAROI", "Laroi Unreleased") to "/files/album_covers/old.jpg")
        LocalCovers.read(LocalCovers.encode(emptyMap(), legacy))
        assertEquals("/files/album_covers/old.jpg", LocalCovers.albumCover("Laroi unreleased"))
        assertEquals(null, LocalCovers.albumCover("Some Other Album"))
    }

    @Test fun theNameOnlyKeyWinsOverALegacyOne() {
        val stored = mapOf(
            LocalCovers.legacyAlbumKey("The Kid LAROI", "Laroi Unreleased") to "/files/album_covers/old.jpg",
            LocalCovers.albumKey("Laroi Unreleased") to "/files/album_covers/new.jpg",
        )
        LocalCovers.read(LocalCovers.encode(emptyMap(), stored))
        assertEquals("/files/album_covers/new.jpg", LocalCovers.albumCover("laroi unreleased"))
    }

    @Test fun coversSurviveARestartAsText() {
        val songs = mapOf("content://media/1" to "/files/track_covers/a.jpg")
        val albums = mapOf(LocalCovers.albumKey("Album") to "/files/album_covers/b.jpg")
        val (s, a) = LocalCovers.decode(LocalCovers.encode(songs, albums))
        assertEquals(songs, s)
        assertEquals(albums, a)
        // Another version's file: no covers, no crash.
        assertEquals(emptyMap<String, String>() to emptyMap<String, String>(), LocalCovers.decode("""{"version":99,"songs":{}}"""))
        assertEquals(emptyMap<String, String>() to emptyMap<String, String>(), LocalCovers.decode("""{"version":1}"""))
    }
}
