package app.myspoti.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch player's buffer (Scadify v18): it streams over the watch's own link, and
 * the media3 defaults left it playing hand-to-mouth (a buffered position of 0 in the
 * media session), so stream dips cut the sound. The values must keep a deep bank of
 * audio ahead of the playhead and a healthy re-bank after a stall before resuming.
 */
class WatchBufferingTest {

    @Test fun banksThirtyToSixtySecondsAhead() {
        assertEquals(30_000, WatchBuffering.MIN_BUFFER_MS)
        assertEquals(60_000, WatchBuffering.MAX_BUFFER_MS)
        assertTrue(WatchBuffering.MAX_BUFFER_MS > WatchBuffering.MIN_BUFFER_MS)
    }

    @Test fun rebufferWaitsForAHealthyBank() {
        assertEquals(15_000, WatchBuffering.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
        assertTrue(WatchBuffering.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS >= 10_000)
        // Starting stays quick; only the resume after a stall waits for the bank.
        assertTrue(WatchBuffering.BUFFER_FOR_PLAYBACK_MS < WatchBuffering.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
    }
}
