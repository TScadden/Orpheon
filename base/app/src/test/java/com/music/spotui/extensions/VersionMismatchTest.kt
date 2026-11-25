package com.music.spotui.extensions

import app.myspoti.extensions.AudioStream
import app.myspoti.extensions.TrackInfo
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playback length check (Scadify v12): the downloads' own tolerances and words, applied to
 * what an audio source announces. An answer announcing no length is taken as it comes.
 */
class VersionMismatchTest {
    private fun track(durationMs: Long) = TrackInfo(
        id = "4uLU6hMCjMI75M1A2tKUQC", uri = "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
        title = "Song", artist = "Singer", artists = listOf("Singer"), album = "Album", durationMs = durationMs,
    )

    private fun stream(durationMs: Long?) = AudioStream(url = "https://example.invalid/a", headers = emptyMap(), mimeType = null, durationMs = durationMs)

    @Test fun anAnswerAnnouncingNoLengthIsNoMismatch() {
        assertNull(versionMismatch(stream(null), track(200_000)))
    }

    @Test fun theSongsOwnLengthAndNearItFit() {
        assertNull(versionMismatch(stream(200_000), track(200_000)))
        assertNull(versionMismatch(stream(196_000), track(200_000)))
        assertNull(versionMismatch(stream(214_000), track(200_000)))
    }

    @Test fun aShorterAnswerIsAPreviewAndALongerOneAnotherVersion() {
        assertTrue(versionMismatch(stream(100_000), track(200_000))!!.contains("a preview"))
        assertTrue(versionMismatch(stream(300_000), track(200_000))!!.contains("another version"))
    }

    @Test fun aSongOfUnknownLengthRefusesNothing() {
        assertNull(versionMismatch(stream(100_000), track(0)))
    }
}
