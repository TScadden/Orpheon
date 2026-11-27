package com.music.spotui.data.api

import com.music.spotui.data.catalog.CatalogRuntime
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.local.ImportedPlaylists
import com.music.spotui.data.local.ImportedSong
import com.music.spotui.extensions.AudioRoute
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The playability seam, tested where playback actually decides (the v27 miss
 * was a completion-only test: the rows completed, and still could not play).
 *
 * The tap path, traced: a playlist page's row tap calls
 * SongPlayer.playThroughExtensions with the model's spotifyTrackId, and
 * ExtensionAudio.playList refuses a blank one before any extension is asked —
 * the refusal is the "No source plays" notice. What an extension is then asked
 * with is a TrackInfo addressed by that id (Extensions.trackInfo: the catalog
 * uri of a catalog key, else "spotify:track:<id>"), carrying the model's ISRC.
 * So a row is resolvable exactly when its play id (ImportedSong.playId — the
 * id Api.importedSongOf models it under) is one the extensions can be asked
 * by: a Spotify id, or a catalog key. These tests build that request from an
 * Amazon-shaped row and a Spotify-shaped row, side by side, through the same
 * production gates (AudioRoute.asks, AudioRoute.songsFor, CatalogRuntime).
 *
 * v29 adds the second half of the contract, the half v28's tests could not
 * see because every fixture row carried durationMs 0: being ASKED is not
 * being ANSWERED. The extensions resolve by the ask's metadata — Melzo's
 * resolver (its own main.js) refuses a track whose durationMs is not
 * positive before it searches, and scores candidates against that duration —
 * and an Amazon row whose page stated no duration kept 0 through every pass,
 * because the completion never filled durations and never counted a missing
 * one as thin. The hook side of that refusal is pinned against the real
 * runtime in the extensions module (AudioHookContractTest); the tests below
 * pin the app side: the completion fills the duration under its strict bar,
 * counts a duration-less row as thin however complete its other fields read,
 * and the write-back persists the filled duration.
 */
class ImportedPlayabilityTest {

    @Before fun clean() { ImportedPlaylists.playlists.clear() }
    @After fun cleanUp() { ImportedPlaylists.playlists.clear() }

    /** An Amazon row as the reader produces it: an ASIN, no Spotify id, no ISRC. */
    private fun amazonRow() = ImportedSong(
        key = "", spotifyTrackId = "", title = "BLEED", artist = "The Kid LAROI",
        album = "", durationMs = 0L, coverUrl = "", asin = "B0HYPE0001",
    )

    /** A Spotify row as the merged import reading produces it. */
    private fun spotifyRow() = ImportedSong(
        key = "", spotifyTrackId = "4uLU6hMCjMI75M1A2tKUQC", title = "BLEED", artist = "The Kid LAROI",
        album = "THE FIRST TIME", durationMs = 0L, coverUrl = "https://img/bleed", isrc = "USRC12345678",
    )

    /** The catalog's answer for the Amazon row, on the strict bar. */
    private val bleedHit = CatalogCompletion.Candidate(
        title = "BLEED", artists = listOf("The Kid LAROI"), album = "THE FIRST TIME",
        coverUrl = "https://img/bleed", durationMs = 0L, isrc = "USRC12345678",
        catalogKey = "deezer.3135556",
    )

    /** The row as Api.importedSongOf models it: under its play id, with its ISRC. */
    private fun modelOf(s: ImportedSong) = SongsModel(
        id = 1, title = s.title, album = s.album, singer = s.artist, coverUri = s.coverUrl,
        url = "", spotifyTrackId = s.playId, isrc = s.isrc,
    )

    @Test fun anAmazonRowCompletesIntoTheSameResolvableRequestASpotifyRowMakes() = runBlocking {
        val completed = CatalogCompletion.complete(
            listOf(amazonRow()), CatalogCompletion.Gatherer { listOf(bleedHit) },
        ).single()
        // The completion attached the identity, not just the facts.
        assertEquals("deezer.3135556", completed.catalogKey)
        assertEquals("USRC12345678", completed.isrc)

        for (row in listOf(spotifyRow(), completed)) {
            // The gate SongPlayer.playSong applies before the extensions are
            // asked at all — the one a blank id fails.
            assertTrue("asks(${row.playId})", AudioRoute.asks(row.playId, hasAudioSource = true))
            // The lookup ExtensionAudio.route performs: the model is found in
            // the page's list by the very id it will be asked under.
            val model = modelOf(row)
            val list = AudioRoute.songsFor(row.playId, listOf(model)) { null }
            assertEquals(listOf(model), list)
        }
        // The address the extension request carries (Extensions.trackInfo's own
        // derivation): a catalog key names its catalog track uri — the same
        // request a catalog song from a search makes — while a Spotify id names
        // no catalog uri and takes the "spotify:track:<id>" form.
        assertEquals("deezer:track:3135556", CatalogRuntime.uriOfKey(completed.playId))
        assertNull(CatalogRuntime.uriOfKey(spotifyRow().playId))
    }

    @Test fun anAmazonRowNoCompletionMatchedStaysUnresolvable() {
        // The honest remainder: a row the catalog could not match carries no
        // identity, and the play gate refuses it exactly as it refused Tyson's
        // rows — it is not dressed up as playable.
        val row = amazonRow()
        assertEquals("", row.playId)
        assertFalse(AudioRoute.asks(row.playId, hasAudioSource = true))
    }

    @Test fun theStoredRowsCarryTheIdentityAcrossARestart() = runBlocking {
        val completed = CatalogCompletion.complete(
            listOf(amazonRow()), CatalogCompletion.Gatherer { listOf(bleedHit) },
        )
        val p = ImportedPlaylists.create(
            "Hype", "https://music.amazon.com/user-playlists/B0PLAAAA1111", completed, origin = "amazon",
        )
        val text = ImportedPlaylists.write()
        ImportedPlaylists.playlists.clear()
        ImportedPlaylists.read(text)
        val again = ImportedPlaylists.playlists.getValue(p.id)
        assertEquals("deezer.3135556", again.items.single().catalogKey)
        assertEquals("deezer.3135556", again.items.single().playId)
        assertTrue(AudioRoute.asks(again.items.single().playId, hasAudioSource = true))
    }

    @Test fun aDurationlessRowIsThinHoweverCompleteItsOtherFieldsRead() {
        // Tyson's v28 rows exactly: identity, ISRC, cover and album all attached
        // by the earlier pass — and no duration, because the page stated none.
        val row = amazonRow().copy(
            isrc = "USRC12345678", catalogKey = "deezer.3135556",
            coverUrl = "https://img/bleed", album = "THE FIRST TIME",
            explicit = app.myspoti.api.Explicitness.CLEAN,
        )
        assertEquals(0L, row.durationMs)
        assertTrue(CatalogCompletion.thin(row))
        assertFalse(CatalogCompletion.thin(row.copy(durationMs = 207_000L)))
    }

    @Test fun theCompletionFillsAMissingDurationAndTheWriteBackPersistsIt() = runBlocking {
        val stored = amazonRow().copy(
            isrc = "USRC12345678", catalogKey = "deezer.3135556",
            coverUrl = "https://img/bleed", album = "THE FIRST TIME",
        )
        val p = ImportedPlaylists.create(
            "Hype", "https://music.amazon.com/user-playlists/B0PLAAAA1111", listOf(stored), origin = "amazon",
        )
        // The catalog's answer for the row, carrying the recording's own length.
        val pass = CatalogCompletion.complete(
            ImportedPlaylists.get(p.id)!!.items,
            CatalogCompletion.Gatherer { listOf(bleedHit.copy(durationMs = 207_000L)) },
        )
        assertEquals(207_000L, pass.single().durationMs)

        ImportedPlaylists.complete(p.id, pass.associateBy { it.key })
        val after = ImportedPlaylists.get(p.id)!!.items.single()
        assertEquals(207_000L, after.durationMs)
        // The model the tap builds from the healed row (Api.importedSongOf's own
        // fields) now carries the duration the hook gates on.
        val model = SongsModel(
            id = 1, title = after.title, album = after.album, singer = after.artist,
            coverUri = after.coverUrl, url = "", spotifyTrackId = after.playId,
            durationMs = after.durationMs.toInt(), isrc = after.isrc,
        )
        assertTrue(model.durationMs > 0)
        // And it survives a restart: the store's own round trip.
        val text = ImportedPlaylists.write()
        ImportedPlaylists.playlists.clear()
        ImportedPlaylists.read(text)
        assertEquals(207_000L, ImportedPlaylists.get(p.id)!!.items.single().durationMs)
    }

    @Test fun aStatedDurationIsNeverOverwrittenByACompletion() = runBlocking {
        // Blanks only: a row whose source stated a duration keeps it, even when
        // the accepted hit (inside the length window) states a different one.
        val row = amazonRow().copy(durationMs = 205_000L)
        val done = CatalogCompletion.complete(
            listOf(row), CatalogCompletion.Gatherer { listOf(bleedHit.copy(durationMs = 207_000L)) },
        ).single()
        assertEquals(205_000L, done.durationMs)
    }

    @Test fun theCompletionWriteBackFillsAStoredPlaylistsIdentityBlanksOnly() = runBlocking {
        // Tyson's stored copy, as v27 left it: clean rows, no identity on any.
        val p = ImportedPlaylists.create(
            "Hype", "https://music.amazon.com/user-playlists/B0PLAAAA1111",
            listOf(amazonRow(), amazonRow().copy(title = "TOO MUCH", asin = "B0HYPE0002")),
            origin = "amazon",
        )
        val stored = ImportedPlaylists.get(p.id)!!.items
        assertTrue(stored.all { it.playId.isBlank() })

        // The on-open completion pass, driven with a stand-in catalog, and its
        // write-back by row key (ImportedPlaylists.complete).
        val pass = CatalogCompletion.complete(stored, CatalogCompletion.Gatherer { song ->
            listOf(bleedHit.copy(title = song.title, catalogKey = "deezer.${song.asin.takeLast(4)}"))
        })
        val done = ImportedPlaylists.complete(p.id, pass.associateBy { it.key })
        assertEquals(stored.map { it.key }.toSet(), done)

        val after = ImportedPlaylists.get(p.id)!!.items
        assertEquals("deezer.0001", after[0].playId)
        assertEquals("deezer.0002", after[1].playId)
        assertEquals("USRC12345678", after[0].isrc)
        assertTrue(after.all { AudioRoute.asks(it.playId, hasAudioSource = true) })

        // A second pass has nothing left to fill, and an identity a row already
        // carries is never overwritten by a different offer.
        val again = ImportedPlaylists.complete(
            p.id, after.associateBy({ it.key }, { it.copy(catalogKey = "deezer.9999999", isrc = "XXRC99999999") }),
        )
        assertTrue(again.isEmpty())
        assertEquals("deezer.0001", ImportedPlaylists.get(p.id)!!.items[0].catalogKey)
        assertEquals("USRC12345678", ImportedPlaylists.get(p.id)!!.items[0].isrc)
    }
}
