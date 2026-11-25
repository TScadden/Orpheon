/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.wear

import android.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phone mode's watch side: the phone's now-playing payload as the watch reads it (the phone
 * writes the same shape — its own tests round-trip its writer against its reader), and the
 * mode switch's kept value, where anything unreadable means the default, Phone mode.
 * Robolectric only for a working Base64.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneModeTest {

    @Test fun parsesThePhonesPayload() {
        val p = PhonePlayingJson.parse(
            """{"title":"Deep End","artist":"The Kid LAROI","album":"Laroi unreleased",""" +
                """"playing":true,"positionMs":42000,"durationMs":180000,"cover":"https://img/cover.jpg"}""",
        )
        assertTrue(p.hasSong)
        assertEquals("Deep End", p.title)
        assertEquals("The Kid LAROI", p.artist)
        assertEquals("Laroi unreleased", p.album)
        assertTrue(p.playing)
        assertEquals(42_000L, p.positionMs)
        assertEquals(180_000L, p.durationMs)
        assertEquals("https://img/cover.jpg", p.coverUrl)
        assertNull(p.artJpeg)
    }

    @Test fun parsesTheSleeveItselfWhenThePhoneSendsOne() {
        val jpeg = byteArrayOf(1, 2, 3, 4, 5)
        val art = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val p = PhonePlayingJson.parse(
            """{"title":"Not Fair","artist":"A","album":"","playing":false,"positionMs":0,"durationMs":1,"art":"$art"}""",
        )
        assertArrayEquals(jpeg, p.artJpeg)
        assertNull(p.coverUrl)
        assertFalse(p.playing)
    }

    @Test fun anEmptyPayloadIsNoSong() {
        val p = PhonePlayingJson.parse("""{"title":"","artist":"","album":"","playing":false,"positionMs":0,"durationMs":0}""")
        assertFalse(p.hasSong)
    }

    @Test fun modeParseDefaultsToPhone() {
        assertEquals(WatchMode.WATCH, WatchModes.parse("watch"))
        assertEquals(WatchMode.WATCH, WatchModes.parse(" WATCH "))
        assertEquals(WatchMode.PHONE, WatchModes.parse("phone"))
        assertEquals(WatchMode.PHONE, WatchModes.parse(null))
        assertEquals(WatchMode.PHONE, WatchModes.parse(""))
        assertEquals(WatchMode.PHONE, WatchModes.parse("junk"))
    }
}
