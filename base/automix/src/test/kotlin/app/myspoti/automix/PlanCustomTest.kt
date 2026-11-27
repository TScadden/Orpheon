/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Mix editor's plans ([MixPlanner.planCustom]): the listener's style, bar count and window
 * placement honoured exactly, or null — never a different transition under the edit's name.
 * Fixtures mirror MixPlannerPolicyTest's: 240 s at 120 BPM, a bar every 2 s, 120 bars.
 */
class PlanCustomTest {
    private fun track(bpm: Double = 120.0, grids: Boolean = true, duration: Double = 240.0): TrackAnalysis {
        val beat = 60.0 / bpm
        val barSec = 4 * beat
        val bars = (duration / barSec).toInt()
        val grid = if (grids) Grid(0.0, beat, (duration / beat).toInt(), 1.0, 0.0, 1.0) else null
        return TrackAnalysis(duration, 0.0, duration, -10.0, 0, 1.0, bpm,
            true, grid, grid, DoubleArray((duration / beat).toInt()) { it * beat },
            DoubleArray(bars) { it * barSec },
            FloatArray(bars) { -10f }, FloatArray(bars) { -16f }, FloatArray(bars) { -20f },
            FloatArray(bars), FloatArray(bars), 0, false,
            if (grids) 0.0 else Double.NaN, if (grids) 0.0 else Double.NaN)
    }

    @Test fun blendHonoursBarsPlacementAndEq() {
        val plan = MixPlanner.planCustom(track(), track(), MixPlan.Style.BLEND, bars = 8, endBarOffset = 0,
            eq = false, earliestExit = 0.0)!!
        assertEquals(MixPlan.Style.BLEND, plan.style)
        assertEquals(32, plan.beats)
        assertEquals(16.0, plan.length, 1e-9)
        // The window ends at the song's end: exit 224, end 240.
        assertEquals(224.0, plan.exitA, 1e-9)
        assertEquals(240.0, plan.endA, 1e-9)
        assertEquals(false, plan.userEq)
        // Four bars earlier: the whole window moves four bars up.
        val moved = MixPlanner.planCustom(track(), track(), MixPlan.Style.BLEND, bars = 8, endBarOffset = 4,
            eq = true, earliestExit = 0.0)!!
        assertEquals(216.0, moved.exitA, 1e-9)
        assertEquals(232.0, moved.endA, 1e-9)
        assertEquals(true, moved.userEq)
    }

    @Test fun blendRefusedWithoutGridsOrCommonTempo() {
        // No tail grid on A: no beat to blend on.
        assertNull(MixPlanner.planCustom(track(grids = false), track(), MixPlan.Style.BLEND, 8, 0, true, 0.0))
        // 150 against 120 BPM is a 20 % stretch, past the default 8 %.
        assertNull(MixPlanner.planCustom(track(), track(bpm = 150.0), MixPlan.Style.BLEND, 8, 0, true, 0.0))
        // A window that has already passed is not replayed late.
        assertNull(MixPlanner.planCustom(track(), track(), MixPlan.Style.BLEND, 8, 0, true, earliestExit = 230.0))
    }

    @Test fun cutLandsOnADownbeatByTheWindowEnd() {
        val plan = MixPlanner.planCustom(track(), track(), MixPlan.Style.CUT, bars = 8, endBarOffset = 0,
            eq = true, earliestExit = 0.0)!!
        assertEquals(MixPlan.Style.CUT, plan.style)
        assertEquals(1.0, plan.length, 1e-9)
        assertTrue(plan.endA <= 240.0 + 1e-9)
        assertEquals(0.0, plan.exitA % 2.0, 1e-9)
        // No tail grid: no downbeat to cut on.
        assertNull(MixPlanner.planCustom(track(grids = false), track(), MixPlan.Style.CUT, 8, 0, true, 0.0))
    }

    @Test fun fadeNeedsNoGridAndUsesBarsForItsLength() {
        val plan = MixPlanner.planCustom(track(grids = false), track(grids = false), MixPlan.Style.FADE,
            bars = 8, endBarOffset = 0, eq = false, earliestExit = 0.0)!!
        assertEquals(MixPlan.Style.FADE, plan.style)
        // 8 bars at 120 BPM (2 s a bar) ending at the song's end.
        assertEquals(16.0, plan.length, 1e-9)
        assertEquals(224.0, plan.exitA, 1e-9)
        assertEquals(false, plan.userEq)
    }

    @Test fun automaticPlansCarryNoUserEq() {
        assertNull(MixPlanner.plan(track(), track()).userEq)
        assertNull(MixPlanner.planBounded(track(), track(), MixPlanner.Settings())?.userEq)
    }
}
