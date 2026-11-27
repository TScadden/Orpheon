package com.music.spotui.data.api

import com.music.spotui.data.local.ImportedSong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog fallback that completes imported songs Spotify's reading left thin
 * (CatalogCompletion): the strict bar (a wrong cover is worse than none), the ISRC-exact
 * preference, blanks-only filling, and the pass that leaves complete songs alone.
 */
class CatalogCompletionTest {

    private fun song(
        title: String = "Blue Sky",
        artist: String = "The Testers",
        album: String = "",
        durationMs: Long = 200_000,
        coverUrl: String = "",
        isrc: String = "",
    ) = ImportedSong("k", "tid", title, artist, album, durationMs, coverUrl, isrc)

    private fun hit(
        title: String = "Blue Sky",
        artists: List<String> = listOf("The Testers"),
        album: String = "Sky Album",
        coverUrl: String = "https://img/cover.jpg",
        durationMs: Long = 200_000,
        isrc: String = "USRC10000001",
    ) = CatalogCompletion.Candidate(title, artists, album, coverUrl, durationMs, isrc)

    @Test fun aThinSongIsCompletedFromTheCatalog() = runBlocking {
        val out = CatalogCompletion.complete(listOf(song()), CatalogCompletion.Gatherer { listOf(hit()) })
        assertEquals("https://img/cover.jpg", out[0].coverUrl)
        assertEquals("Sky Album", out[0].album)
        assertEquals("USRC10000001", out[0].isrc)
    }

    @Test fun anIsrcExactHitBeatsASearchHit() {
        // The search hit is closer in duration and listed first; the ISRC one still wins.
        val s = song(isrc = "USRC10000001")
        val search = hit(coverUrl = "https://img/search.jpg", durationMs = 200_000, isrc = "USRC19999999")
        val exact = hit(coverUrl = "https://img/exact.jpg", durationMs = 204_000)
        assertEquals("https://img/exact.jpg", CatalogCompletion.pick(s, listOf(search, exact))?.coverUrl)
    }

    @Test fun anIsrcExactHitIsAcceptedOnTheIsrcAlone() {
        val s = song(isrc = "USRC10000001")
        val odd = hit(title = "Blue Sky (2009 Remaster)", durationMs = 231_000)
        assertTrue(CatalogCompletion.accepted(s, odd))
    }

    @Test fun aLooseTitleOrArtistMatchIsRejected() = runBlocking {
        val wrongTitle = hit(title = "Blue Skies Forever")
        val wrongArtist = hit(artists = listOf("Somebody Else"))
        assertFalse(CatalogCompletion.accepted(song(), wrongTitle))
        assertFalse(CatalogCompletion.accepted(song(), wrongArtist))
        val out = CatalogCompletion.complete(listOf(song()), CatalogCompletion.Gatherer { listOf(wrongTitle, wrongArtist) })
        assertEquals("", out[0].coverUrl)
        assertEquals("", out[0].album)
        assertEquals("", out[0].isrc)
    }

    @Test fun aDurationMismatchedCandidateIsRejected() {
        assertNull(CatalogCompletion.pick(song(), listOf(hit(durationMs = 260_000))))
        // Inside the downloads' own tolerances (no more than 15 s long) it is accepted.
        assertTrue(CatalogCompletion.accepted(song(), hit(durationMs = 210_000)))
    }

    @Test fun presentFieldsAreNeverOverwritten() = runBlocking {
        val kept = song(coverUrl = "https://img/mine.jpg")
        val out = CatalogCompletion.complete(listOf(kept), CatalogCompletion.Gatherer { listOf(hit()) })
        assertEquals("https://img/mine.jpg", out[0].coverUrl)
        assertEquals("Sky Album", out[0].album)
        assertEquals("USRC10000001", out[0].isrc)
    }

    @Test fun completeSongsAreNotGatheredFor() = runBlocking {
        var asked = 0
        // Complete now includes a stated explicitness (v23): a song thin only in
        // that is gathered for, like any other blank.
        val full = song(album = "A", coverUrl = "https://img/c.jpg", isrc = "USRC10000001")
            .copy(explicit = app.myspoti.api.Explicitness.CLEAN)
        val out = CatalogCompletion.complete(listOf(full), CatalogCompletion.Gatherer { asked++; listOf(hit()) })
        assertEquals(0, asked)
        assertEquals(full, out[0])
    }

    @Test fun aSongThinOnlyInExplicitGetsItsMarkFromTheCatalog() = runBlocking {
        val unjudged = song(album = "A", coverUrl = "https://img/c.jpg", isrc = "USRC10000001")
        assertTrue(CatalogCompletion.thin(unjudged))
        val marked = hit().copy(explicit = app.myspoti.api.Explicitness.EXPLICIT)
        val out = CatalogCompletion.complete(listOf(unjudged), CatalogCompletion.Gatherer { listOf(marked) })
        assertEquals(app.myspoti.api.Explicitness.EXPLICIT, out[0].explicit)
        // Nothing else about the song moved.
        assertEquals("https://img/c.jpg", out[0].coverUrl)
        assertEquals("A", out[0].album)
    }

    @Test fun aStatedExplicitnessIsNeverOverwritten() {
        val judged = song().copy(explicit = app.myspoti.api.Explicitness.CLEAN)
        val loud = hit().copy(explicit = app.myspoti.api.Explicitness.EXPLICIT)
        assertEquals(app.myspoti.api.Explicitness.CLEAN, CatalogCompletion.completed(judged, loud).explicit)
        // And an unjudged song takes the hit's judgement, either way it falls.
        assertEquals(app.myspoti.api.Explicitness.EXPLICIT, CatalogCompletion.completed(song(), loud).explicit)
        val quiet = hit().copy(explicit = app.myspoti.api.Explicitness.CLEAN)
        assertEquals(app.myspoti.api.Explicitness.CLEAN, CatalogCompletion.completed(song(), quiet).explicit)
    }

    @Test fun aMisreadRowIsRefusedCompletion() = runBlocking {
        // A row whose title is its artist said again (an Amazon row read with the
        // artist line as its title) names no recording: even a candidate matching
        // its garbled strings word for word may not complete it — that could only
        // attach a stranger's song. It is not even gathered for.
        val garbled = song(title = "The Testers", artist = "The Testers")
        assertTrue(CatalogCompletion.isMisread(garbled))
        assertFalse(CatalogCompletion.isMisread(song()))
        val twin = hit(title = "The Testers", artists = listOf("The Testers"))
        assertFalse(CatalogCompletion.accepted(garbled, twin))
        var asked = 0
        val out = CatalogCompletion.complete(listOf(garbled), CatalogCompletion.Gatherer { asked++; listOf(twin) })
        assertEquals(0, asked)
        assertEquals(garbled, out[0])
        // But an ISRC the row's own source stated is an identity, not a match: a
        // genuinely self-titled song carrying one still completes by it.
        val selfTitled = song(title = "The Testers", artist = "The Testers", isrc = "USRC10000001")
        assertTrue(CatalogCompletion.accepted(selfTitled, hit(title = "Anything", artists = listOf("Anyone"))))
    }

    @Test fun completionAttachesTheCatalogKeyAnIdlessRowPlaysUnder() = runBlocking {
        // An Amazon-shaped row: no Spotify id. The completion's hit carries its
        // catalog key, and the completed row plays under it (playId) — the
        // identity the extensions are asked by, which an ISRC alone never was.
        val idless = ImportedSong("k", "", "Blue Sky", "The Testers", "", 200_000, "")
        val keyed = hit().copy(catalogKey = "deezer.3135556")
        val out = CatalogCompletion.complete(listOf(idless), CatalogCompletion.Gatherer { listOf(keyed) })
        assertEquals("deezer.3135556", out[0].catalogKey)
        assertEquals("deezer.3135556", out[0].playId)
        // A candidate that fails the strict bar attaches nothing, key included.
        val loose = hit(title = "Blue Skies Forever").copy(catalogKey = "deezer.9999999")
        val refused = CatalogCompletion.complete(listOf(idless), CatalogCompletion.Gatherer { listOf(loose) })
        assertEquals("", refused[0].catalogKey)
        assertEquals("", refused[0].playId)
        // A key the row already carries stands against a different offer.
        val held = idless.copy(catalogKey = "deezer.111")
        val kept = CatalogCompletion.complete(listOf(held), CatalogCompletion.Gatherer { listOf(keyed) })
        assertEquals("deezer.111", kept[0].catalogKey)
    }

    @Test fun anIdlessRowWithEveryOtherFieldIsStillThinUntilItHasAKey() {
        // An Amazon row the page itself furnished with album, cover and (through
        // an earlier pass) an ISRC and a judgement is still thin while it has no
        // catalog key: the key is its playable identity, so the pass must still
        // gather for it. With a key — or with a Spotify id — it is left alone.
        val full = ImportedSong("k", "", "Blue Sky", "The Testers", "Sky Album", 200_000, "https://img/c.jpg", "USRC10000001")
            .copy(explicit = app.myspoti.api.Explicitness.CLEAN)
        assertTrue(CatalogCompletion.thin(full))
        assertFalse(CatalogCompletion.thin(full.copy(catalogKey = "deezer.3135556")))
        assertFalse(CatalogCompletion.thin(full.copy(spotifyTrackId = "tid")))
    }

    @Test fun aFailingGathererLeavesTheSongAsItWas() = runBlocking {
        val out = CatalogCompletion.complete(listOf(song()), CatalogCompletion.Gatherer { throw RuntimeException("down") })
        assertEquals(song(), out[0])
    }

    @Test fun primaryCreditTakesTheHeadOfACredit() {
        assertEquals("The Kid LAROI", CatalogCompletion.primaryCredit("The Kid LAROI [feat. Youngboy Never Broke Again]"))
        assertEquals("The Kid LAROI", CatalogCompletion.primaryCredit("The Kid LAROI (feat. Youngboy Never Broke Again)"))
        assertEquals("The Kid LAROI", CatalogCompletion.primaryCredit("The Kid LAROI feat. Youngboy Never Broke Again"))
        assertEquals("Old Sea Brigade", CatalogCompletion.primaryCredit("Old Sea Brigade, Amanda Bergman"))
        assertEquals("The Testers", CatalogCompletion.primaryCredit("The Testers"))
    }

    @Test fun aFeaturingCreditCompletesOnItsPrimaryArtist() = runBlocking {
        // The shape of Tyson's unplayable "CALL ME INSTEAD": an Amazon row (no
        // Spotify id, no duration — the phone's page states none) whose artist
        // credit carries a bracketed guest. The catalog's hit names the head
        // artist and the guest as its two artists. The full-credit comparisons
        // could never join them — Match.artistFit caps a contained name at 0.9,
        // short of full marks — so the row stayed thin and unplayable while
        // single-artist rows completed. The primary comparison joins them, and
        // the row completes whole: duration, ISRC, and the key it plays under.
        val row = ImportedSong("k", "", "CALL ME INSTEAD", "The Kid LAROI [feat. Youngboy Never Broke Again]", "", 0L, "")
        val hit = CatalogCompletion.Candidate(
            title = "CALL ME INSTEAD", artists = listOf("The Kid LAROI", "YoungBoy Never Broke Again"),
            album = "THE FIRST TIME", coverUrl = "https://img/call.jpg", durationMs = 222_000L,
            isrc = "USRC12300001", catalogKey = "deezer.222",
        )
        assertTrue(CatalogCompletion.accepted(row, hit))
        val out = CatalogCompletion.complete(listOf(row), CatalogCompletion.Gatherer { listOf(hit) }).single()
        assertEquals(222_000L, out.durationMs)
        assertEquals("USRC12300001", out.isrc)
        assertEquals("deezer.222", out.catalogKey)
        assertEquals("deezer.222", out.playId)
        // The same hit with the whole credit stated as one entry completes too.
        val combined = hit.copy(artists = listOf("The Kid LAROI, YoungBoy Never Broke Again"))
        assertTrue(CatalogCompletion.accepted(row, combined))
    }

    @Test fun aDuetCreditCompletesOnItsPrimaryArtist() = runBlocking {
        // "Rest Assured" (Sad playlist): a duet credit against a catalog entry
        // naming both singers. Neither name alone equals the full credit, and
        // the fit caps at 0.9 — the row never matched under the old bar either.
        // The heads agree, and that is identity enough with the title exact.
        val row = ImportedSong("k", "", "Rest Assured", "Old Sea Brigade, Amanda Bergman", "", 0L, "")
        val hit = CatalogCompletion.Candidate(
            title = "Rest Assured", artists = listOf("Old Sea Brigade", "Amanda Bergman"),
            album = "Ode to a Friend", coverUrl = "https://img/rest.jpg", durationMs = 231_000L,
            isrc = "USRC12300002", catalogKey = "deezer.333",
        )
        assertTrue(CatalogCompletion.accepted(row, hit))
        val out = CatalogCompletion.complete(listOf(row), CatalogCompletion.Gatherer { listOf(hit) }).single()
        assertEquals(231_000L, out.durationMs)
        assertEquals("USRC12300002", out.isrc)
        assertEquals("deezer.333", out.playId)
    }

    @Test fun aCommaTripleCreditCompletesOnItsPrimaryArtist() = runBlocking {
        // Tyson's "TOO MUCH" row (Nov 27, on v30): the same song PLAYS from
        // Search — the catalog row carries the catalog's own duration
        // natively — but refused from the imported Hype Playlist, because
        // completion never landed for its comma-triple credit and the
        // duration was never attached (the hook refuses duration-less tracks
        // before it searches). The head of the triple is the identity.
        assertEquals("The Kid LAROI", CatalogCompletion.primaryCredit("The Kid LAROI, Jung Kook & Central Cee"))
        val row = ImportedSong("k", "", "TOO MUCH", "The Kid LAROI, Jung Kook & Central Cee", "", 0L, "")
        val hit = CatalogCompletion.Candidate(
            title = "TOO MUCH", artists = listOf("The Kid LAROI", "Jung Kook", "Central Cee"),
            album = "THE FIRST TIME", coverUrl = "https://img/toomuch.jpg", durationMs = 143_000L,
            isrc = "USRC12300003", catalogKey = "deezer.444",
        )
        assertTrue(CatalogCompletion.accepted(row, hit))
        val out = CatalogCompletion.complete(listOf(row), CatalogCompletion.Gatherer { listOf(hit) }).single()
        assertEquals(143_000L, out.durationMs)
        assertEquals("USRC12300003", out.isrc)
        assertEquals("deezer.444", out.playId)
        // The same hit credited as one combined string completes it too.
        val combined = hit.copy(artists = listOf("The Kid LAROI, Jung Kook & Central Cee"))
        assertTrue(CatalogCompletion.accepted(row, combined))
    }

    @Test fun aDifferentPrimaryArtistStillRefuses() {
        // The guest alone is not the head: a hit credited only to the featured
        // artist is another recording of the title, however close the rest —
        // the primary widening is shape tolerance, never identity tolerance.
        val row = ImportedSong("k", "", "CALL ME INSTEAD", "The Kid LAROI [feat. Youngboy Never Broke Again]", "", 0L, "")
        val guestOnly = CatalogCompletion.Candidate(
            title = "CALL ME INSTEAD", artists = listOf("YoungBoy Never Broke Again"),
            album = "AI YoungBoy", coverUrl = "https://img/x.jpg", durationMs = 222_000L, isrc = "",
        )
        assertFalse(CatalogCompletion.accepted(row, guestOnly))
        // And a different head act altogether, in the same duet shape.
        val stranger = guestOnly.copy(artists = listOf("Old Sea Brigade, Amanda Bergman"))
        assertFalse(CatalogCompletion.accepted(row, stranger))
    }

    @Test fun anOutvotedDurationIsReplacedByTheAgreeingHits() = runBlocking {
        // The stored row states 90 s (a wrong value — a preview's length, a
        // garbled read); the catalog's providers agree the recording is 200 s.
        // No candidate corroborates the row's value and two agree with each
        // other against it: the duration counts as unfilled and the accepted
        // hit's replaces it, everything else filling as usual.
        val wrong = song(durationMs = 90_000)
        val a = hit(durationMs = 200_000, coverUrl = "https://img/a.jpg")
        val b = hit(durationMs = 200_000, coverUrl = "https://img/b.jpg")
        val out = CatalogCompletion.complete(listOf(wrong), CatalogCompletion.Gatherer { listOf(a, b) }).single()
        assertEquals(200_000L, out.durationMs)
        assertEquals("https://img/a.jpg", out.coverUrl)
        assertEquals("USRC10000001", out.isrc)
    }

    @Test fun aCorroboratedDurationStandsAgainstAnAgreeingPair() = runBlocking {
        // The row states 200 s and one hit corroborates it; two others agree
        // with each other on 260 s (a longer cut of the same title). The
        // corroborated value governs: the ordinary bar picks the corroborating
        // hit, and the stated duration is never replaced.
        val stated = song(durationMs = 200_000)
        val real = hit(durationMs = 205_000, coverUrl = "https://img/real.jpg")
        val long1 = hit(durationMs = 260_000, coverUrl = "https://img/l1.jpg")
        val long2 = hit(durationMs = 261_000, coverUrl = "https://img/l2.jpg")
        val out = CatalogCompletion.complete(listOf(stated), CatalogCompletion.Gatherer { listOf(long1, real, long2) }).single()
        assertEquals("https://img/real.jpg", out.coverUrl)
        assertEquals(200_000L, out.durationMs)
    }

    @Test fun aLoneContradictingCandidateNeverOutvotesAStatedDuration() = runBlocking {
        // One hit, 60 s longer than the row states: as likely another recording
        // as a wrong row. It refuses, and the song is left exactly as it was.
        val stated = song(durationMs = 200_000)
        val out = CatalogCompletion.complete(listOf(stated), CatalogCompletion.Gatherer { listOf(hit(durationMs = 260_000)) }).single()
        assertEquals(stated, out)
    }
}
