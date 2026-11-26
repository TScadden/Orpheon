package com.music.spotui.data.api

import com.music.spotui.data.local.ImportedSong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Amazon reading's testable parts: the rendered-page extraction (against a saved
 * page modeled on the real player's output), the scroll collection, and what the
 * import does with the rows. The WebView read itself — a live page rendering on a
 * phone — is not provable here and is not claimed to be.
 */
class AmazonPlaylistReaderTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/amazon/$name")!!.bufferedReader().readText()

    @Test fun aRenderedPageReadsAsItsPlaylist() {
        val page = AmazonDom.parse(fixture("playlist.html"))
        assertEquals("Evening Drive Mix", page.name)
        assertEquals(3, page.declaredTotal)
        assertEquals(3, page.rows.size)

        val first = page.rows[0]
        assertEquals("Midnight Run", first.title)
        assertEquals("The Wanderers", first.artist)
        assertEquals("Long Road Home", first.album)
        assertEquals(204_000L, first.durationMs)
        assertEquals("B0TRACK001", first.asin)
        assertEquals("https://m.media-amazon.com/images/I/51cover1.jpg", first.coverUrl)
        // An Amazon row has no Spotify id and no ISRC: it must never pretend to either.
        assertEquals("", first.spotifyTrackId)
        assertEquals("", first.isrc)

        // Entities are read, and a row whose artist and album are plain lines (no
        // links) still names both.
        val second = page.rows[1]
        assertEquals("Paper & Gold", second.title)
        assertEquals("June Cartel", second.artist)
        assertEquals("Static Bloom", second.album)
        assertEquals(245_000L, second.durationMs)
        assertEquals("B0TRACK002", second.asin)
        assertEquals("https://m.media-amazon.com/images/I/51cover2.jpg", second.coverUrl)

        // A row linked only by its title has no cover of its own to take.
        val third = page.rows[2]
        assertEquals("Slow Parade", third.title)
        assertEquals("Ana Beltrán", third.artist)
        assertEquals("Harbor Lights", third.album)
        assertEquals(177_000L, third.durationMs)
        assertEquals("", third.coverUrl)
    }

    @Test fun aPageWithNoTrackLinksReadsAsNoRows() {
        val page = AmazonDom.parse("<html><head><title>Amazon Music</title></head><body>Tuning in…</body></html>")
        assertTrue(page.rows.isEmpty())
        assertFalse(AmazonDom.looksBlocked("<html><body>Tuning in…</body></html>"))
    }

    @Test fun aBotCheckPageIsToldApart() {
        assertTrue(AmazonDom.looksBlocked("<html><body><h1>Robot Check</h1>Enter the characters you see below</body></html>"))
        assertTrue(AmazonDom.looksBlocked("<html><body><form action=\"/errors/validateCaptcha\">captcha</form></body></html>"))
    }

    private fun row(asin: String, title: String, artist: String, album: String = "", cover: String = "") =
        ImportedSong("", "", title, artist, album, 100_000L, cover, asin = asin)

    @Test fun scrollCollectionDedupesByAsinThenByName() {
        val collect = AmazonCollect()
        assertEquals(2, collect.add(listOf(
            row("B0TRACK001", "One", "Artist A"),
            row("B0TRACK002", "Two", "Artist B"),
        )))
        // The next scroll re-reads the first screen and adds one new song; the
        // re-read of "One" completes the album it had not rendered the first time.
        assertEquals(1, collect.add(listOf(
            row("B0TRACK001", "One", "Artist A", album = "Alb"),
            row("B0TRACK002", "Two", "Artist B"),
            row("B0TRACK003", "Three", "Artist C"),
        )))
        assertEquals(3, collect.songs.size)
        assertEquals("Alb", collect.songs[0].album)
        // A song with no ASIN is told apart by its title and artist alone.
        assertEquals(1, collect.add(listOf(row("", "Four", "Artist D"))))
        assertEquals(0, collect.add(listOf(row("", " four ", "ARTIST D"))))
        assertEquals(4, collect.songs.size)
    }

    @Test fun catalogCompletionFillsAnAmazonRowWithoutASpotifyId() = runBlocking {
        // The import's completion, driven with a stand-in catalog: an Amazon row (no
        // Spotify id, no ISRC) is completed on the strict title + artist + duration
        // bar, exactly like a thin Spotify row.
        val thin = ImportedSong("", "", "Midnight Run", "The Wanderers", "", 204_000L, "")
        val gather = CatalogCompletion.Gatherer { song ->
            listOf(CatalogCompletion.Candidate(
                title = song.title, artists = listOf(song.artist), album = "Long Road Home",
                coverUrl = "https://img/cover", durationMs = 205_000L, isrc = "USS1Z2500001",
            ))
        }
        val done = CatalogCompletion.complete(listOf(thin), gather).single()
        assertEquals("USS1Z2500001", done.isrc)
        assertEquals("https://img/cover", done.coverUrl)
        assertEquals("Long Road Home", done.album)
        assertEquals("", done.spotifyTrackId)
    }
}
