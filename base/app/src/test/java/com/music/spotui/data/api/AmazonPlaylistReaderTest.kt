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

    @Test fun wallsAreClassifiedCaptchaSignInOrNeither() {
        assertEquals(
            AmazonWall.CAPTCHA,
            AmazonDom.wallOf("<html><body><h1>Robot Check</h1>Enter the characters you see below</body></html>"),
        )
        // A sign-in gate: Amazon's own sign-in address and its call to sign in, no rows.
        val gate = "<html><head><title>Amazon Music</title></head><body>" +
            "<p>Sign in to listen to this playlist</p>" +
            "<a href=\"https://www.amazon.com/ap/signin?openid.return_to=https://music.amazon.com/\">Sign in</a>" +
            "</body></html>"
        assertEquals(AmazonWall.SIGN_IN, AmazonDom.wallOf(gate))
        assertFalse(AmazonDom.looksBlocked(gate))
        // A bare shell and a real playlist page are neither wall.
        assertEquals(AmazonWall.NONE, AmazonDom.wallOf("<html><body>Tuning in…</body></html>"))
        assertEquals(AmazonWall.NONE, AmazonDom.wallOf(fixture("playlist.html")))
    }

    @Test fun rowElementsReadWhenNoLinkCarriesATrackAsin() {
        val page = AmazonDom.parse(fixture("playlist_rows.html"))
        assertEquals("Night Drive", page.name)
        // The third row never rendered an artist: not a song the import may claim.
        assertEquals(2, page.rows.size)

        val first = page.rows[0]
        assertEquals("Glass Horizon", first.title)
        assertEquals("Velvet Static", first.artist)
        assertEquals("Afterglow", first.album)
        assertEquals(221_000L, first.durationMs)
        assertEquals("B0FALL0001", first.asin)
        assertEquals("https://m.media-amazon.com/images/I/61fall1.jpg", first.coverUrl)

        val second = page.rows[1]
        assertEquals("Low Country", second.title)
        assertEquals("Mara Quinn", second.artist)
        assertEquals("Field Notes", second.album)
        assertEquals(178_000L, second.durationMs)
        assertEquals("", second.asin)
    }

    @Test fun runJsAnswersOrTimesOutButNeverHangs() = runBlocking {
        // A page that answers: its value comes back.
        val answering = JsEvaluator { _, callback -> callback("rows") }
        assertEquals("rows", runJs(answering, "script", 1_000))
        // A page that never answers (busy, navigated, a script that threw): the
        // deadline answers instead — null, in bounded time.
        val silent = JsEvaluator { _, _ -> }
        assertEquals(null, runJs(silent, "script", 150))
        // A page whose evaluation itself blows up reads as no answer too.
        val throwing = JsEvaluator { _, _ -> throw IllegalStateException("gone") }
        assertEquals(null, runJs(throwing, "script", 1_000))
    }

    private fun readout(
        wall: AmazonWall = AmazonWall.NONE, htmlBytes: Int? = 1,
    ) = AmazonReadout(
        url = "https://music.amazon.com/user-playlists/ABC", pageTitle = "Amazon Music",
        pageUrl = "https://music.amazon.com/user-playlists/ABC",
        htmlBytes = htmlBytes, rowsFound = 0, wall = wall,
        loadOutcome = "finished", lastPoll = "poll 20: 0 rows",
    )

    @Test fun theReadoutNamesWhatThePageWas() {
        val text = readout(wall = AmazonWall.SIGN_IN, htmlBytes = null).text()
        assertTrue(text.contains("page title: Amazon Music"))
        assertTrue(text.contains("html size: unreadable"))
        assertTrue(text.contains("rows found: 0"))
        assertTrue(text.contains("wall: sign-in"))
        assertTrue(text.contains("last poll: poll 20: 0 rows"))
        // The other walls name themselves too, and a read page gives its size.
        assertTrue(readout(wall = AmazonWall.NONE).text().contains("wall: neither"))
        assertTrue(readout(wall = AmazonWall.CAPTCHA).text().contains("wall: captcha"))
        assertTrue(readout(htmlBytes = 12_345).text().contains("html size: 12345 bytes"))
    }

    @Test fun statusStagesFollowTheReadsFacts() {
        assertEquals(AmazonStage.WAITING_FOR_LIST, amazonPollStage(0, 0))
        assertEquals(AmazonStage.COLLECTING, amazonPollStage(3, 10))
        assertEquals(AmazonStage.FOUND_SONGS, amazonPollStage(10, 10))
        assertEquals(AmazonStage.NEEDS_CAPTCHA, amazonWallStage(AmazonWall.CAPTCHA))
        assertEquals(AmazonStage.NEEDS_SIGN_IN, amazonWallStage(AmazonWall.SIGN_IN))
        assertEquals(AmazonStage.WAITING_FOR_LIST, amazonWallStage(AmazonWall.NONE))
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

    @Test fun anArtistCreditInTheTitleSlotIsReDerivedOrSkipped() {
        // The shape of Tyson's v26 import: the text-bearing track link carries the
        // artist credit and the title is a plain line of the row. The real title
        // must win; the row with no title line anywhere must not import at all.
        val page = AmazonDom.parse(fixture("playlist_garbled.html"))
        assertEquals("Hype Playlist", page.name)
        assertEquals(2, page.declaredTotal)
        assertEquals(1, page.rows.size)

        val row = page.rows.single()
        assertEquals("Too Much", row.title)
        assertEquals("The Kid LAROI, Jung Kook & Central Cee", row.artist)
        assertEquals("The First Time", row.album)
        assertEquals(227_000L, row.durationMs)
        assertEquals("B0TRACK101", row.asin)
        assertEquals("https://m.media-amazon.com/images/I/51too.jpg", row.coverUrl)
        assertFalse(page.rows.any { AmazonDom.sameName(it.title, it.artist) })
    }

    @Test fun aCorrectlyReadRowOfTheGarbledPageCompletesIntoAPlayableIdentity() = runBlocking {
        // Playability follows from the clean read: the row parsed off the garbled
        // page completes on the strict bar and comes out carrying its ISRC — the
        // identity playback matches sources by (with the clean title + artist as
        // the play query), exactly like a Spotify import's row.
        val row = AmazonDom.parse(fixture("playlist_garbled.html")).rows.single()
        val gather = CatalogCompletion.Gatherer { song ->
            listOf(CatalogCompletion.Candidate(
                title = song.title, artists = listOf(song.artist),
                album = "The First Time", coverUrl = "https://img/too-much",
                durationMs = 227_000L, isrc = "USRC12345678",
            ))
        }
        val done = CatalogCompletion.complete(listOf(row), gather).single()
        assertEquals("Too Much", done.title)
        assertEquals("USRC12345678", done.isrc)
        // The cover the page itself showed stands: completion fills blanks only.
        assertEquals("https://m.media-amazon.com/images/I/51too.jpg", done.coverUrl)
    }

    @Test fun collectionEndsAtTheDeclaredTotalOrTheTrueBottomNeverTheWindow() {
        // The declared total reached: over, however the passes looked.
        assertTrue(CollectEnd().offer(added = 3, atBottom = false, declaredTotal = 10, collected = 10))
        // New rows at the bottom: not over.
        assertFalse(CollectEnd().offer(added = 2, atBottom = true, declaredTotal = null, collected = 5))
        // No new rows away from the bottom: not over, however often it repeats.
        val mid = CollectEnd()
        repeat(5) { assertFalse(mid.offer(added = 0, atBottom = false, declaredTotal = null, collected = 5)) }
        // The true bottom repeating empty ends it — on the third empty rest, not before.
        val end = CollectEnd()
        assertFalse(end.offer(added = 0, atBottom = true, declaredTotal = null, collected = 5))
        assertFalse(end.offer(added = 0, atBottom = true, declaredTotal = null, collected = 5))
        assertTrue(end.offer(added = 0, atBottom = true, declaredTotal = null, collected = 5))
        // A fresh row at the bottom resets the run of empty rests.
        val reset = CollectEnd()
        assertFalse(reset.offer(added = 0, atBottom = true, declaredTotal = null, collected = 5))
        assertFalse(reset.offer(added = 1, atBottom = true, declaredTotal = null, collected = 6))
        assertFalse(reset.offer(added = 0, atBottom = true, declaredTotal = null, collected = 6))
        assertFalse(reset.offer(added = 0, atBottom = true, declaredTotal = null, collected = 6))
        assertTrue(reset.offer(added = 0, atBottom = true, declaredTotal = null, collected = 6))
        // No declared total: the count alone never ends it — the window's size is
        // not a total, and ending there capped a long playlist at its first screen.
        val undeclared = CollectEnd()
        assertFalse(undeclared.offer(added = 3, atBottom = false, declaredTotal = null, collected = 3))
    }

    @Test fun aPageDeclaresItsTotalOnlyWhenItStatesOne() {
        assertEquals(3, AmazonDom.declaredTotalOrNull(fixture("playlist.html")))
        assertEquals(2, AmazonDom.declaredTotalOrNull(fixture("playlist_garbled.html")))
        assertEquals(null, AmazonDom.declaredTotalOrNull(fixture("playlist_rows.html")))
        assertEquals(null, AmazonDom.declaredTotalOrNull("<html><body>Nothing stated</body></html>"))
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
