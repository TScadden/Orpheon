package app.myspoti.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The audio-source hook contract as the phone's real source implements it,
 * run through the production runtime: the app asks with [AudioQuery]'s JSON,
 * the answer is read with [AudioStream.parse] — the same two ends
 * Extensions.audio joins. The fake extension below is Melzo's resolver
 * reduced to its decision points, taken from its own main.js (the copy kept
 * with the project): `resolveTrack` returns nothing at all when the track
 * has no title, no artist name, or `!(track.durationMs > 0)`; its `score`
 * keeps a candidate only when `sameLength` holds — the candidate's length
 * within −5 s / +15 s of the track's stated duration. It never reads the
 * track's id, uri or ISRC to decide: it searches by title and artist and
 * judges by length.
 *
 * That is the contract two Scadify theories died against: an imported
 * Amazon row carrying a catalog key and an ISRC (v28's fix) still could not
 * play, because the row's stored duration was 0 — the one field this hook
 * gates on before anything else. These tests pin the gate so the app's side
 * (the completion filling a missing duration) is tested against the real
 * refusal, not against a guess about it.
 */
class AudioHookContractTest {

    private val host = object : Host {
        val store = MemoryStorage()
        override fun storage(extensionId: String) = store
        override fun setting(extensionId: String, key: String): String? = null
        override fun log(extensionId: String, message: String) {}
        override fun http(extensionId: String, request: HttpRequest): HttpResponse =
            HttpResponse(200, emptyMap(), "{}")
    }

    /** Melzo's resolver, its search already done: the official recording is 207 s. */
    private val melzoLike = """
        myspoti.provide('audio-source', function (track) {
          if (!track || !track.title || !(track.durationMs > 0)) return null;
          var names = [track.artist || ''];
          if (Array.isArray(track.artists)) track.artists.forEach(function (a) { names.push(a || ''); });
          if (!names.some(function (n) { return String(n).trim().length > 0; })) return null;
          var diff = 207 * 1000 - track.durationMs;
          if (diff < -5000 || diff > 15000) return null;
          return { url: 'https://example.org/tear-me-apart.m4a', headers: {}, mimeType: 'audio/mp4',
                   durationMs: track.durationMs, codec: 'aac', expiresInMs: 300000 };
        });
    """.trimIndent()

    private fun ask(track: TrackInfo): AudioStream? {
        val m = Manifest(
            "io.test.melzolike", "Melzo-like", "1", author = "Test",
            provides = listOf("audio-source"),
            permissions = listOf(PermissionRequest("audio.source")),
        )
        return ExtensionRuntime(m, melzoLike, host, m.asks().map { it.key }.toSet()).use { rt ->
            rt.start()
            val raw = rt.call(Hook.AUDIO_SOURCE, AudioQuery.toJson(track, ahead = false), 8_000)
            raw?.let { runCatching { AudioStream.parse(it) }.getOrNull() }
        }
    }

    /** The TrackInfo a tap builds for Tyson's row, as Extensions.trackInfo derives it. */
    private fun tearMeApart(durationMs: Long, id: String = "deezer.3135556", uri: String = "deezer:track:3135556") = TrackInfo(
        id = id, uri = uri, title = "TEAR ME APART", artist = "The Kid LAROI",
        artists = listOf("The Kid LAROI"), album = "THE FIRST TIME",
        durationMs = durationMs, isrc = "USRC12345678", cover = "https://img/tear",
    )

    @Test fun aDurationlessRowIsRefusedHoweverCompleteItsIdentity() {
        // The v28 state on the phone: catalog key attached, ISRC attached,
        // title and artist clean — and no duration, because the page's read
        // stated none and no pass filled it. The hook refuses before searching.
        assertNull(ask(tearMeApart(durationMs = 0)))
    }

    @Test fun theSameRowWithItsDurationFilledIsAnswered() {
        // The v29 completion fills the duration from the accepted catalog hit
        // (207 s, the recording's own): the same ask now answers.
        val stream = ask(tearMeApart(durationMs = 207_000))
        assertNotNull(stream)
        assertEquals("https://example.org/tear-me-apart.m4a", stream!!.url)
    }

    @Test fun aSpotifyAddressedRowAnswersByTheSameMetadataRule() {
        // The control: a Spotify import's row, addressed by its Spotify id —
        // it answers not because of the address (the hook never reads it) but
        // because the Spotify read carried a real duration all along.
        val stream = ask(
            tearMeApart(durationMs = 207_000, id = "4uLU6hMCjMI75M1A2tKUQC", uri = "spotify:track:4uLU6hMCjMI75M1A2tKUQC"),
        )
        assertNotNull(stream)
    }

    @Test fun aWrongDurationIsRefusedLikeNone() {
        // A duration far off the recording's length scores no candidate: the
        // hook's length window refuses it exactly as it refuses a missing one.
        assertNull(ask(tearMeApart(durationMs = 120_000)))
    }
}
