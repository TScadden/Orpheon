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
        // The empty-shell reload is recorded, so a failure after one says so.
        assertTrue(text.contains("reloaded once: no"))
        assertTrue(readout().copy(reloaded = true).text().contains("reloaded once: yes"))
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
        // Playability follows from the clean read plus the identity the completion
        // attaches: the row parsed off the garbled page completes on the strict
        // bar and comes out carrying its ISRC and its catalog key — the key is
        // the id it plays under (ImportedSong.playId), the same kind of identity
        // a Spotify import's row carries. (An ISRC alone never was one: the
        // extensions are asked by id, and this row has no Spotify id.)
        val row = AmazonDom.parse(fixture("playlist_garbled.html")).rows.single()
        val gather = CatalogCompletion.Gatherer { song ->
            listOf(CatalogCompletion.Candidate(
                title = song.title, artists = listOf(song.artist),
                album = "The First Time", coverUrl = "https://img/too-much",
                durationMs = 227_000L, isrc = "USRC12345678", catalogKey = "deezer.3135556",
            ))
        }
        val done = CatalogCompletion.complete(listOf(row), gather).single()
        assertEquals("Too Much", done.title)
        assertEquals("USRC12345678", done.isrc)
        assertEquals("deezer.3135556", done.catalogKey)
        assertEquals("deezer.3135556", done.playId)
        // The cover the page itself showed stands: completion fills blanks only.
        assertEquals("https://m.media-amazon.com/images/I/51too.jpg", done.coverUrl)
    }

    @Test fun collectionEndsOnlyAtTheTrueBottomNeverTheDeclaredTotalNorTheWindow() {
        // The declared total reached mid-list: NOT over (v29). The total is
        // advisory — it sizes the summary's "of M" and stops nothing — because
        // a misread total ended real collections: an incidental 10 read as an
        // 84-song playlist's total stopped the read the moment 10 were held.
        assertFalse(CollectEnd().offer(added = 3, atBottom = false, declaredTotal = 10, collected = 10))
        assertFalse(CollectEnd().offer(added = 0, atBottom = false, declaredTotal = 84, collected = 84))
        // New rows at the bottom: not over.
        assertFalse(CollectEnd().offer(added = 2, atBottom = true, declaredTotal = null, collected = 5))
        // No new rows away from the bottom: not over, however often it repeats.
        val mid = CollectEnd()
        repeat(5) { assertFalse(mid.offer(added = 0, atBottom = false, declaredTotal = null, collected = 5)) }
        // The true bottom repeating empty ends it — but only once the bottom has
        // also stayed quiet for the whole lazy-load window. A chunk still loading
        // shows the same empty bottom for a few passes, and ending there concluded
        // collections inside the gap; the clock is driven by hand here.
        var t = 0L
        val end = CollectEnd(now = { t })
        assertFalse(end.offer(added = 0, atBottom = true, declaredTotal = null, collected = 5))
        t += 1_100; assertFalse(end.offer(added = 0, atBottom = true, declaredTotal = null, collected = 5))
        t += 1_100; assertFalse(end.offer(added = 0, atBottom = true, declaredTotal = null, collected = 5))
        // Three empty rests but only 2.2 s of quiet: not over.
        t += 4_000; assertTrue(end.offer(added = 0, atBottom = true, declaredTotal = null, collected = 5))
        // A fresh row at the bottom resets the empty rests and the quiet alike.
        var t2 = 0L
        val reset = CollectEnd(now = { t2 })
        assertFalse(reset.offer(added = 0, atBottom = true, declaredTotal = null, collected = 5))
        t2 += 7_000; assertFalse(reset.offer(added = 1, atBottom = true, declaredTotal = null, collected = 6))
        assertFalse(reset.offer(added = 0, atBottom = true, declaredTotal = null, collected = 6))
        t2 += 1_100; assertFalse(reset.offer(added = 0, atBottom = true, declaredTotal = null, collected = 6))
        t2 += 5_000; assertTrue(reset.offer(added = 0, atBottom = true, declaredTotal = null, collected = 6))
        // No declared total: the count alone never ends it — the window's size is
        // not a total, and ending there capped a long playlist at its first screen.
        val undeclared = CollectEnd()
        assertFalse(undeclared.offer(added = 3, atBottom = false, declaredTotal = null, collected = 3))
    }

    @Test fun anIncidentalCountElsewhereOnThePageCannotCapCollection() {
        // The shape of the 84-song import that stopped at 10: the page's chrome
        // states another list's "10 songs" ahead of the playlist's own header,
        // and further counts trail the rows. Only the header's own count — the
        // last one stated before the first row link — is the playlist's size.
        val html = """
            <html><head><title>Amazon Music: Hype</title></head><body>
            <nav><div class="rail-card">Road Trip Mix · 10 songs</div></nav>
            <header class="playlist-header"><h1>Hype</h1><div class="meta">84 SONGS • 4 HOURS 16 MINUTES</div></header>
            <div class="tracklist">
              <div class="row"><a href="/albums/B0ALBUM001?trackAsin=B0TRACK001">One</a></div>
            </div>
            <footer>Your other mix · 3 songs</footer>
            </body></html>
        """.trimIndent()
        assertEquals(84, AmazonDom.declaredTotalOrNull(html))
    }

    @Test fun aVirtualizedListOf84RowsIsCollectedWholeAcrossWindowsGapsAndReplacement() {
        // A modelled virtualized list: 84 rows, ~10 rendered per window, each step
        // REPLACING the window (earlier rows unmount as later ones mount), and
        // every chunk arriving late — the first read after a step still shows the
        // previous window, and stepping onto the last window reads as the bottom
        // while its rows are still loading. The collector must end with all 84
        // and must not conclude inside a lazy gap.
        val total = 84
        val window = 10
        val maxStart = total - window
        fun rows(from: Int): List<ImportedSong> =
            (from until minOf(from + window, total)).map { i ->
                row("B0TRK%05d".format(i), "Song $i", "Artist ${i % 7}")
            }
        var clock = 0L
        val collect = AmazonCollect()
        val end = CollectEnd(now = { clock })
        var held = 0
        fun offered(added: Int, atBottom: Boolean): Boolean {
            assertTrue("the collected set only grows", collect.songs.size >= held)
            held = collect.songs.size
            return end.offer(added, atBottom, total, collect.songs.size)
        }
        var start = 0
        var done = offered(collect.add(rows(0)), atBottom = false)
        assertFalse(done)
        var guard = 0
        while (!done && guard++ < 300) {
            val target = minOf(start + window, maxStart)
            // The gap read: the step was taken, the new window has not rendered —
            // the old window reads again, and an unloaded end reads as the bottom.
            clock += 1_100
            done = offered(collect.add(rows(start)), atBottom = target >= maxStart)
            assertFalse(
                "collection concluded inside a lazy gap at ${collect.songs.size} of $total songs",
                done && collect.songs.size < total,
            )
            if (done) break
            // The chunk lands: the new window replaces the old one.
            start = target
            clock += 1_100
            done = offered(collect.add(rows(start)), atBottom = start + window >= total)
        }
        assertTrue(done)
        assertEquals(total, collect.songs.size)
        assertEquals((0 until total).map { "Song $it" }, collect.songs.map { it.title })
    }

    @Test fun aShortReadStatesItsShortfallAndAWholeOneDoesNot() {
        val short = ReadSummary.of(found = 10, declared = 84)
        assertTrue(short.isShort)
        assertEquals(74, short.shortBy)
        assertFalse(ReadSummary.of(found = 84, declared = 84).isShort)
        // No total declared: the count alone is the story, never a shortfall.
        assertFalse(ReadSummary.of(found = 10, declared = null).isShort)
        assertEquals(0, ReadSummary.of(found = 10, declared = null).shortBy)
        // A page that understates itself never makes the read "short".
        assertFalse(ReadSummary.of(found = 90, declared = 84).isShort)
    }

    @Test fun theHeaderBlockIsNeverReadAsTheFirstSong() {
        // The shape of Tyson's v28 import: the playlist header presents itself
        // as a row-shaped block (a "track" test id, a labelled title slot)
        // whose title is the count line and whose artist is the playlist's top
        // credit. It imported as a junk first song titled "84 SONGS", ahead of
        // the real first track. The header is furniture, never a song — in the
        // row-element reading that produced it and at the parse's final gate.
        val html = """
            <html><head><title>Amazon Music: Hype Playlist</title>
            <meta property="og:title" content="Hype Playlist on Amazon Music"></head><body>
            <div data-testid="track-header" class="header-block" data-asin="B0HEADER01">
              <span class="t-title">84 SONGS</span>
              <a href="/artists/B0ARTIST01">The Kid LAROI</a>
            </div>
            <div class="tracklist">
              <a href="/albums/B0ALBUM001?trackAsin=B0TRACK001">SORRY</a>
              <a href="/artists/B0ARTIST01">The Kid LAROI</a>
              <span>THE FIRST TIME</span><span>3:24</span>
              <a href="/albums/B0ALBUM002?trackAsin=B0TRACK002">BLEED</a>
              <a href="/artists/B0ARTIST01">The Kid LAROI</a>
              <span>THE FIRST TIME</span><span>2:57</span>
            </div>
            </body></html>
        """.trimIndent()
        val page = AmazonDom.parse(html)
        assertEquals(listOf("SORRY", "BLEED"), page.rows.map { it.title })
        assertFalse(page.rows.any { AmazonDom.isCountLine(it.title) })
        assertEquals("B0TRACK001", page.rows[0].asin)
    }

    @Test fun theTenOfTenLieCannotStand() {
        // The shape of Tyson's v29-morning re-import: a rail card's "10 songs"
        // stands ahead of the first row link, and the playlist's own header —
        // its name and its "84 SONGS" — renders after that link (the player's
        // hero block links the first track early). The old region rule read
        // only what stood before the first link and declared 10, so the
        // confirm step said "10 of 10 songs read" over a short read and no
        // shortfall showed. The header's count, anchored on the playlist's own
        // name, outvotes the rail's: the summary's M is 84, and a 10-song read
        // of it states its shortfall plainly.
        val html = """
            <html><head><title>Amazon Music: Hype Playlist</title>
            <meta property="og:title" content="Hype Playlist on Amazon Music"></head><body>
            <nav><div class="rail-card">Road Trip Mix · 10 songs</div></nav>
            <div class="hero"><a href="/albums/B0ALBUM001?trackAsin=B0TRACK001">SORRY</a></div>
            <header class="playlist-header"><h1>Hype Playlist</h1><div class="meta">84 SONGS • 4 HOURS 16 MINUTES</div></header>
            <div class="tracklist">
              <a href="/albums/B0ALBUM002?trackAsin=B0TRACK002">BLEED</a>
            </div>
            </body></html>
        """.trimIndent()
        assertEquals(84, AmazonDom.declaredTotalOrNull(html))
        val summary = ReadSummary.of(found = 10, declared = AmazonDom.declaredTotalOrNull(html))
        assertTrue(summary.isShort)
        assertEquals(74, summary.shortBy)
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
