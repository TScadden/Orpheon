/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone mode's wire shapes: the now-playing payload the phone sends the watch, and the
 * control commands the watch sends back. Both sides parse the same shapes; these tests keep
 * the phone's writer and reader in agreement, and the command words mapped to the commands
 * the phone's player is driven with.
 */
class PhoneNowPlayingTest {

    @Test fun payloadRoundTrips() {
        val s = PhonePlayingSnapshot(
            title = "Deep End", artist = "The Kid LAROI", album = "Laroi unreleased",
            playing = true, positionMs = 42_000L, durationMs = 180_000L,
            coverUrl = "https://img/cover.jpg",
        )
        val (back, art) = PhonePlayingJson.decode(PhonePlayingJson.encode(s, artJpegBase64 = "QUJD"))
        assertEquals(s, back)
        assertEquals("QUJD", art)
    }

    @Test fun phoneLocalSleeveIsNotSentAsAnAddress() {
        val s = PhonePlayingSnapshot(
            title = "Not Fair", artist = "The Kid LAROI", album = "Laroi unreleased",
            playing = false, positionMs = 0L, durationMs = 1L,
            coverUrl = "content://media/external/audio/albumart/7",
        )
        val (back, _) = PhonePlayingJson.decode(PhonePlayingJson.encode(s))
        assertEquals("", back.coverUrl)
        assertEquals("Not Fair", back.title)
        assertFalse(back.playing)
    }

    @Test fun emptyPayloadDecodesToNoSong() {
        val (back, art) = PhonePlayingJson.decode(PhonePlayingJson.encode(PhonePlayingSnapshot()))
        assertFalse(back.hasSong)
        assertNull(art)
        val (blank, _) = PhonePlayingJson.decode("{}")
        assertFalse(blank.hasSong)
        assertEquals(0L, blank.durationMs)
    }

    @Test fun snapshotKeyIgnoresPositionButNotState() {
        val a = PhonePlayingSnapshot(title = "T", artist = "A", playing = true, positionMs = 1_000L, durationMs = 9L)
        val b = a.copy(positionMs = 5_000L)
        assertEquals(a.key, b.key)
        assertTrue(a.key != a.copy(playing = false).key)
        assertTrue(a.key != a.copy(title = "U").key)
    }

    @Test fun controlCommandsParse() {
        assertEquals(PhoneControl.Command.PLAY, PhoneControl.parse("play").command)
        assertEquals(PhoneControl.Command.PAUSE, PhoneControl.parse("pause").command)
        assertEquals(PhoneControl.Command.TOGGLE, PhoneControl.parse("toggle").command)
        assertEquals(PhoneControl.Command.NEXT, PhoneControl.parse("next").command)
        assertEquals(PhoneControl.Command.PREVIOUS, PhoneControl.parse("previous").command)
        assertEquals(PhoneControl.Command.NEXT, PhoneControl.parse("  next  ").command)
    }

    @Test fun seekParsesItsPositionAndJunkIsUnknown() {
        val seek = PhoneControl.parse("seek:12345")
        assertEquals(PhoneControl.Command.SEEK, seek.command)
        assertEquals(12345L, seek.seekMs)
        assertEquals(PhoneControl.Command.UNKNOWN, PhoneControl.parse("seek:abc").command)
        assertEquals(PhoneControl.Command.UNKNOWN, PhoneControl.parse("shuffle").command)
        assertEquals(PhoneControl.Command.UNKNOWN, PhoneControl.parse("").command)
    }
}
