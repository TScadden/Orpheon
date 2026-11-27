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
import org.junit.Assert.assertNotNull
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

    // ── v31: the completed row must ask the hook exactly as the catalog row does ──

    private val hookHost = object : app.myspoti.extensions.Host {
        val store = app.myspoti.extensions.MemoryStorage()
        override fun storage(extensionId: String) = store
        override fun setting(extensionId: String, key: String): String? = null
        override fun log(extensionId: String, message: String) {}
        override fun http(
            extensionId: String, request: app.myspoti.extensions.HttpRequest,
        ): app.myspoti.extensions.HttpResponse =
            app.myspoti.extensions.HttpResponse(200, emptyMap(), "{}")
    }

    /** Melzo's resolver reduced to its decision points (as AudioHookContractTest
     *  pins it in the extensions module), its search already done: the official
     *  recording of TOO MUCH is 143 s. It refuses a duration-less track before
     *  searching, and keeps a candidate only within −5 s / +15 s of the stated
     *  duration. It never reads the track's id, uri or ISRC. */
    private val melzoLikeTooMuch = """
        myspoti.provide('audio-source', function (track) {
          if (!track || !track.title || !(track.durationMs > 0)) return null;
          var names = [track.artist || ''];
          if (Array.isArray(track.artists)) track.artists.forEach(function (a) { names.push(a || ''); });
          if (!names.some(function (n) { return String(n).trim().length > 0; })) return null;
          var diff = 143 * 1000 - track.durationMs;
          if (diff < -5000 || diff > 15000) return null;
          return { url: 'https://example.org/too-much.m4a', headers: {}, mimeType: 'audio/mp4',
                   durationMs: track.durationMs, codec: 'aac', expiresInMs: 300000 };
        });
    """.trimIndent()

    private fun askHook(track: app.myspoti.extensions.TrackInfo): app.myspoti.extensions.AudioStream? {
        val m = app.myspoti.extensions.Manifest(
            "io.test.melzolike", "Melzo-like", "1", author = "Test",
            provides = listOf("audio-source"),
            permissions = listOf(app.myspoti.extensions.PermissionRequest("audio.source")),
        )
        return app.myspoti.extensions.ExtensionRuntime(
            m, melzoLikeTooMuch, hookHost, m.asks().map { it.key }.toSet(),
        ).use { rt ->
            rt.start()
            val raw = rt.call(
                app.myspoti.extensions.Hook.AUDIO_SOURCE,
                app.myspoti.extensions.AudioQuery.toJson(track, ahead = false), 8_000,
            )
            raw?.let { runCatching { app.myspoti.extensions.AudioStream.parse(it) }.getOrNull() }
        }
    }

    /** Extensions.trackInfo's own derivation, replicated as PlayDebugTest
     *  replicates it: the artist string split on ", ", the head as the artist,
     *  the catalog uri of the track's key. */
    private fun trackInfoOf(
        trackId: String, title: String, artist: String, album: String,
        durationMs: Long, cover: String?, isrc: String?,
    ): app.myspoti.extensions.TrackInfo {
        val artists = artist.split(", ").map { it.trim() }.filter { it.isNotEmpty() }
        return app.myspoti.extensions.TrackInfo(
            id = trackId,
            uri = if (trackId.isBlank()) "" else CatalogRuntime.uriOfKey(trackId) ?: "spotify:track:$trackId",
            title = title, artist = artists.firstOrNull().orEmpty(), artists = artists,
            album = album, durationMs = durationMs,
            isrc = isrc?.takeIf { it.isNotBlank() },
            cover = cover?.takeIf { it.startsWith("http") },
        )
    }

    @Test fun aCompletedCommaTripleRowAsksTheHookExactlyAsItsCatalogRowDoes() = runBlocking {
        // Tyson's on-device pair (Nov 27, v30): TOO MUCH plays from Search and
        // refuses from the imported Hype Playlist. The two asks differ only in
        // what the rows carry: the catalog row states its duration natively;
        // the playlist row's Amazon credit is a comma triple completion never
        // landed for, so its duration was never attached.
        val stored = ImportedSong(
            key = "", spotifyTrackId = "", title = "TOO MUCH",
            artist = "The Kid LAROI, Jung Kook & Central Cee",
            album = "", durationMs = 0L, coverUrl = "", asin = "B0HYPE0009",
        )
        val hit = CatalogCompletion.Candidate(
            title = "TOO MUCH", artists = listOf("The Kid LAROI", "Jung Kook", "Central Cee"),
            album = "THE FIRST TIME", coverUrl = "https://img/toomuch",
            durationMs = 143_000L, isrc = "USRC12300003", catalogKey = "deezer.4444",
        )
        // Before completion, the playlist row's ask is refused — the on-device state.
        assertNull(
            askHook(trackInfoOf(stored.playId, stored.title, stored.artist, stored.album, stored.durationMs, stored.coverUrl, stored.isrc)),
        )

        val completed = CatalogCompletion.complete(
            listOf(stored), CatalogCompletion.Gatherer { listOf(hit) },
        ).single()

        // The playlist row's ask, built from the completed row the production way.
        val playlistAsk = trackInfoOf(
            completed.playId, completed.title, completed.artist, completed.album,
            completed.durationMs, completed.coverUrl, completed.isrc,
        )
        // The same song's catalog row, as Search plays it: the catalog's main
        // artist and the duration the catalog states natively.
        val catalogAsk = trackInfoOf(
            "deezer.4444", "TOO MUCH", "The Kid LAROI", "THE FIRST TIME",
            143_000L, "https://img/toomuch", "USRC12300003",
        )

        // The fields the hook judges are equivalent: same title, same primary
        // artist, same duration; the playlist ask names every artist the
        // catalog ask does.
        assertEquals(catalogAsk.title, playlistAsk.title)
        assertEquals(catalogAsk.artist, playlistAsk.artist)
        assertEquals(catalogAsk.durationMs, playlistAsk.durationMs)
        assertTrue(playlistAsk.artists.containsAll(catalogAsk.artists))

        // And the hook answers BOTH — the playlist row now plays as the
        // search row does.
        assertNotNull(askHook(catalogAsk))
        val stream = askHook(playlistAsk)
        assertNotNull(stream)
        assertEquals("https://example.org/too-much.m4a", stream!!.url)
    }
}
