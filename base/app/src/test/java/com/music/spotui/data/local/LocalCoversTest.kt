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

    @Test fun lentArtStandsInOnlyBelowASetAlbumCover() {
        // The chain with lending (v12): song cover → own art → set album cover → lent art → own address.
        assertEquals("song.jpg", LocalCovers.chooseArt("song.jpg", "content://art/1", ownArtExists = false, albumCover = "album.jpg", borrowedArt = "content://art/2"))
        assertEquals("content://art/1", LocalCovers.chooseArt(null, "content://art/1", ownArtExists = true, albumCover = "album.jpg", borrowedArt = "content://art/2"))
        assertEquals("album.jpg", LocalCovers.chooseArt(null, "content://art/1", ownArtExists = false, albumCover = "album.jpg", borrowedArt = "content://art/2"))
        assertEquals("content://art/2", LocalCovers.chooseArt(null, "content://art/1", ownArtExists = false, albumCover = null, borrowedArt = "content://art/2"))
        assertEquals("content://art/1", LocalCovers.chooseArt(null, "content://art/1", ownArtExists = false, albumCover = null, borrowedArt = null))
    }

    @Test fun aFallbackStandsInOnlyWhereTheChainOtherwiseEndsAtTheOwnAddress() {
        // The album-page fallback (v24): below everything the chain already had.
        assertEquals("page.jpg", LocalCovers.chooseArt(null, "content://art/1", ownArtExists = false, albumCover = null, borrowedArt = null, fallback = "page.jpg"))
        // A file with art of its own keeps it; a set song cover, album cover and lent
        // art all still win over the fallback.
        assertEquals("content://art/1", LocalCovers.chooseArt(null, "content://art/1", ownArtExists = true, albumCover = null, borrowedArt = null, fallback = "page.jpg"))
        assertEquals("song.jpg", LocalCovers.chooseArt("song.jpg", "content://art/1", ownArtExists = false, albumCover = null, borrowedArt = null, fallback = "page.jpg"))
        assertEquals("album.jpg", LocalCovers.chooseArt(null, "content://art/1", ownArtExists = false, albumCover = "album.jpg", borrowedArt = null, fallback = "page.jpg"))
        assertEquals("content://art/2", LocalCovers.chooseArt(null, "content://art/1", ownArtExists = false, albumCover = null, borrowedArt = "content://art/2", fallback = "page.jpg"))
        // No fallback (every other surface): the chain is exactly as before.
        assertEquals("content://art/1", LocalCovers.chooseArt(null, "content://art/1", ownArtExists = false, albumCover = null, borrowedArt = null, fallback = null))
    }

    @Test fun anAlbumLendsTheArtOfItsFirstFileThatHasAny() {
        val rows = listOf(
            Triple("Laroi Unreleased", "content://art/1", false),
            Triple("Laroi Unreleased", "content://art/2", true),
            Triple("Laroi Unreleased", "content://art/3", true),
            Triple("Other Album", "content://art/9", false),
        )
        val lent = LocalCovers.borrowedArt(rows)
        // The artless first file lends nothing; the second file's art is the album's.
        assertEquals("content://art/2", lent[LocalCovers.albumKey("Laroi Unreleased")])
        // An album whose files all lack art lends nothing.
        assertEquals(null, lent[LocalCovers.albumKey("Other Album")])
    }

    @Test fun lendingIgnoresCaseAndSpacesAndSkipsNamelessAlbums() {
        val rows = listOf(
            Triple("", "content://art/1", true),
            Triple("  ", "content://art/2", true),
            Triple("Greatest Hits", "content://art/3", true),
            Triple("greatest hits ", "content://art/4", true),
        )
        val lent = LocalCovers.borrowedArt(rows)
        assertEquals(mapOf(LocalCovers.albumKey("Greatest Hits") to "content://art/3"), lent)
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
