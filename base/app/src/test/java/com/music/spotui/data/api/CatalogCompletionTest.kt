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

    @Test fun aFailingGathererLeavesTheSongAsItWas() = runBlocking {
        val out = CatalogCompletion.complete(listOf(song()), CatalogCompletion.Gatherer { throw RuntimeException("down") })
        assertEquals(song(), out[0])
    }
}
