package com.music.spotui.ui

import app.myspoti.extensions.PlayAttempt
import app.myspoti.extensions.TrackInfo
import com.music.spotui.data.local.ImportedSong
import com.music.spotui.extensions.ExtensionAudio
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v29 diagnostic sheet: one screenshot of it is the diagnosis of a refused
 * tap, so the report must carry the stored row's exact fields, the TrackInfo
 * the tap builds from them, and each source's own answer, word for word.
 */
class PlayDebugTest {

    /** Tyson's v28 row: identity and ISRC attached, no duration — the refusal's shape. */
    private fun row() = ImportedSong(
        key = "row-1", spotifyTrackId = "", title = "TEAR ME APART", artist = "The Kid LAROI",
        album = "THE FIRST TIME", durationMs = 0L, coverUrl = "https://img/tear",
        isrc = "USRC12345678", asin = "B0HYPE0007", catalogKey = "deezer.3135556",
    )

    private fun infoOf(r: ImportedSong) = TrackInfo(
        id = r.playId, uri = "deezer:track:3135556", title = r.title, artist = "The Kid LAROI",
        artists = listOf("The Kid LAROI"), album = r.album, durationMs = r.durationMs, isrc = r.isrc,
    )

    @Test fun reportShowsTheStoredRowAndTheTrackInfoTheTapBuilds() {
        val r = row()
        val report = buildPlayDebugReport(
            row = r, info = infoOf(r), ask = null, attempt = null,
            sources = listOf("Melzo — Spotify Pro" to true), names = emptyMap(),
        )
        assertTrue(report.contains("spotifyTrackId: (blank)"))
        assertTrue(report.contains("catalogKey: deezer.3135556"))
        assertTrue(report.contains("isrc: USRC12345678"))
        assertTrue(report.contains("asin: B0HYPE0007"))
        assertTrue(report.contains("durationMs: 0"))
        assertTrue(report.contains("playId: deezer.3135556"))
        assertTrue(report.contains("uri: deezer:track:3135556"))
        assertTrue(report.contains("Melzo — Spotify Pro: on"))
        assertTrue(report.contains("not tapped since the app started"))
        assertTrue(report.contains("(no ask recorded)"))
    }

    @Test fun reportShowsTheLastTapsRouteAndEachSourcesOwnAnswer() {
        val r = row()
        val ask = ExtensionAudio.AskRecord(
            at = 1_700_000_000_000L, info = infoOf(r),
            route = "no extension had it — Spotify was offered the song",
        )
        val attempt = PlayAttempt(
            id = 1, at = ask.at, trackId = r.playId, title = r.title,
            asked = listOf(
                PlayAttempt.Ask("io.melzo", "not-found", 812),
                PlayAttempt.Ask("io.solid", "bug", 40, detail = "TypeError: x is not a function"),
                PlayAttempt.Ask("io.sc", "skipped", 0, skippedReason = "cooling down"),
            ),
            chosen = null, fallback = "Spotify",
        )
        val report = buildPlayDebugReport(
            row = r, info = infoOf(r), ask = ask, attempt = attempt,
            sources = listOf("Melzo — Spotify Pro" to true, "Solid Source" to true, "SoundCloud for Scadify" to true),
            names = mapOf("io.melzo" to "Melzo — Spotify Pro", "io.solid" to "Solid Source", "io.sc" to "SoundCloud for Scadify"),
        )
        assertTrue(report.contains("route: no extension had it — Spotify was offered the song"))
        assertTrue(report.contains("Melzo — Spotify Pro: refused: not found (812 ms)"))
        assertTrue(report.contains("Solid Source: bug — TypeError: x is not a function (40 ms)"))
        assertTrue(report.contains("SoundCloud for Scadify: not asked (cooling down)"))
        assertTrue(report.contains("chosen: none"))
        assertTrue(report.contains("fallback: Spotify"))
    }
}
