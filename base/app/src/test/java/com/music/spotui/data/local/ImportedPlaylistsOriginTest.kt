package com.music.spotui.data.local

import com.music.spotui.data.api.importedOrigin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The imported playlist's origin (v27): stated by the import, kept on the playlist,
 * and read the same way by every surface. And re-importing a held link: it replaces
 * the held playlist's songs in place — that is how a broken import is repaired —
 * instead of adding a duplicate beside it.
 */
class ImportedPlaylistsOriginTest {

    private val amazonUrl = "https://music.amazon.com/user-playlists/B0PLAAAA1111"
    private val spotifyUrl = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"

    private fun song(title: String, asin: String) =
        ImportedSong("", "", title, "Artist", "Album", 100_000L, "", asin = asin)

    @Before fun clean() { ImportedPlaylists.playlists.clear() }

    @After fun cleanUp() { ImportedPlaylists.playlists.clear() }

    @Test fun theImportStatedOriginIsKeptAndSurvivesARestart() {
        val p = ImportedPlaylists.create("Hype Playlist", amazonUrl, listOf(song("Too Much", "B0TRACK101")), origin = "amazon")
        assertEquals("amazon", p.origin)
        assertEquals("amazon", importedOrigin(p))

        val text = ImportedPlaylists.write()
        ImportedPlaylists.playlists.clear()
        ImportedPlaylists.read(text)
        val again = ImportedPlaylists.playlists.getValue(p.id)
        assertEquals("amazon", again.origin)
        assertEquals("amazon", importedOrigin(again))
    }

    @Test fun aPlaylistStoredBeforeOriginsReadsItsOriginFromItsLink() {
        // No origin stated (as every playlist stored before v27): the link decides,
        // and a playlist with no link at all came by file.
        val amazon = ImportedPlaylists.create("Old Amazon", amazonUrl, listOf(song("One", "B0TRACK201")))
        assertEquals("amazon", importedOrigin(amazon))
        val spotify = ImportedPlaylists.create("Old Spotify", spotifyUrl, listOf(song("Two", "")))
        assertEquals("spotify", importedOrigin(spotify))
        val file = ImportedPlaylists.create("From A File", "", listOf(song("Three", "")))
        assertEquals("csv", importedOrigin(file))
    }

    @Test fun reimportingAHeldLinkReplacesItsSongsInPlace() {
        val broken = ImportedPlaylists.create(
            "Hype Playlist", amazonUrl,
            listOf(song("The Kid LAROI", "B0TRACK101"), song("The Kid LAROI", "B0TRACK102")),
            origin = "amazon",
        )
        ImportedPlaylists.setCover(broken.id, "/covers/hype.jpg")

        val fixed = ImportedPlaylists.create(
            "Hype Playlist", amazonUrl,
            listOf(song("Too Much", "B0TRACK101"), song("Love Again", "B0TRACK102"), song("Nights", "B0TRACK103")),
            origin = "amazon",
        )

        // The same playlist — same id, same cover, same place — with the fresh rows,
        // and no second copy anywhere.
        assertEquals(broken.id, fixed.id)
        assertEquals(broken.createdAt, fixed.createdAt)
        assertEquals("/covers/hype.jpg", fixed.cover)
        assertEquals(1, ImportedPlaylists.list().size)
        assertEquals(listOf("Too Much", "Love Again", "Nights"), fixed.items.map { it.title })
        assertTrue(fixed.items.all { it.key.isNotBlank() })
        assertTrue(fixed.items.none { it.key in broken.items.map { b -> b.key } })
    }

    @Test fun aFileImportNeverReplacesAnother() {
        // No link, nothing to match on: two file imports are two playlists.
        ImportedPlaylists.create("One", "", listOf(song("A", "")), origin = "csv")
        ImportedPlaylists.create("Two", "", listOf(song("B", "")), origin = "csv")
        assertEquals(2, ImportedPlaylists.list().size)
    }
}
