/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The percentage the Mix editor shows: built only from the work's own counts. An unknown
 * total stays unknown (null) — the screen falls back to its spinner rather than inventing
 * a number.
 */
class AnalysisProgressTest {
    @Test fun aSettledSongIsDone() {
        assertEquals(1f, AnalysisProgress.song(null, 0f, settled = true)!!, 0.001f)
        assertEquals(1f, AnalysisProgress.song(0.2f, 0.1f, settled = true)!!, 0.001f)
    }

    @Test fun theSongIsTheMeanOfItsTwoHalves() {
        assertEquals(0.25f, AnalysisProgress.song(0.5f, 0f, settled = false)!!, 0.001f)
        assertEquals(0.75f, AnalysisProgress.song(1f, 0.5f, settled = false)!!, 0.001f)
        assertEquals(1f, AnalysisProgress.song(1f, 1f, settled = false)!!, 0.001f)
    }

    @Test fun anUnknownTotalStaysUnknownUntilAnalysisStarts() {
        assertNull(AnalysisProgress.song(null, 0f, settled = false))
        // Once the analyser is running, the decode is necessarily finished.
        assertEquals(0.75f, AnalysisProgress.song(null, 0.5f, settled = false)!!, 0.001f)
    }

    @Test fun fractionsAreClamped() {
        assertEquals(1f, AnalysisProgress.song(1.4f, 1.2f, settled = false)!!, 0.001f)
        assertEquals(0f, AnalysisProgress.song(-0.5f, -1f, settled = false)!!, 0.001f)
    }

    @Test fun thePairIsTheMeanAndUnknownIfEitherSongIs() {
        assertEquals(0.5f, AnalysisProgress.pair(0.25f, 0.75f)!!, 0.001f)
        assertNull(AnalysisProgress.pair(null, 0.5f))
        assertNull(AnalysisProgress.pair(0.5f, null))
        assertNull(AnalysisProgress.pair(null, null))
    }
}
