/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Package D's bound: a song's analysis settles or fails within its deadline — a source that
 * never delivers (or trickles forever) cancels the decode at the limit and is recorded as
 * expired, so the caller settles it as failed instead of waiting on it forever.
 */
class AnalysisDeadlineTest {
    @Test fun aNeverDeliveringSourceIsCancelledAtTheDeadlineAndCountsExpired() {
        var clock = 0L
        val deadline = AnalysisDeadline(limitMs = 45_000, now = { clock })
        val cancelled = deadline.cancelled { false }
        // The source delivers nothing; the decode polls its cancellation as time passes.
        clock = 44_999
        assertFalse(cancelled())
        assertFalse(deadline.expired)
        clock = 45_000
        assertTrue("past the limit the decode is cancelled", cancelled())
        assertTrue("and the end counts as the deadline's, not the caller's", deadline.expired)
    }

    @Test fun theCallersOwnCancellationIsNotBlamedOnTheDeadline() {
        var clock = 0L
        val deadline = AnalysisDeadline(limitMs = 45_000, now = { clock })
        var user = false
        val cancelled = deadline.cancelled { user }
        clock = 1_000
        user = true
        assertTrue(cancelled())
        assertFalse("cancelled by the caller in time: not expired, nothing recorded as failed", deadline.expired)
    }

    @Test fun beforeTheLimitNothingIsCancelled() {
        var clock = 0L
        val deadline = AnalysisDeadline(limitMs = 45_000, now = { clock })
        val cancelled = deadline.cancelled { false }
        repeat(100) { clock += 100; assertFalse(cancelled()) }
        assertFalse(deadline.expired)
    }

    @Test fun aProgressingSourceSurvivesPastTheOldFlatDeadline() {
        // The flat 45 s refused a big song on a slow connection while it was still
        // delivering. The watchdog only asks that the work keeps moving: two minutes of
        // steady progress — far past the old flat deadline — never expires.
        var t = 0L
        var work = 0L
        val deadline = AnalysisDeadline(limitMs = 240_000, stallMs = 25_000, progress = { work }, now = { t })
        val cancelled = deadline.cancelled { false }
        while (t < 120_000) { t += 5_000; work += 1; assertFalse(cancelled()) }
        assertFalse(deadline.expired)
    }

    @Test fun aStalledSourceExpiresAtTheStallBound() {
        var t = 0L
        var work = 0L
        val deadline = AnalysisDeadline(limitMs = 240_000, stallMs = 25_000, progress = { work }, now = { t })
        val cancelled = deadline.cancelled { false }
        // Progress for a while, then the stream stops arriving: the stall bound, not the
        // hard one, is what settles it — and says so.
        repeat(4) { t += 5_000; work += 1; assertFalse(cancelled()) }
        t += 24_999
        assertFalse(cancelled())
        t += 1
        assertTrue(cancelled())
        assertTrue(deadline.expired)
        assertTrue(deadline.stalled)
    }

    @Test fun theHardBoundFiresEvenWhileProgressContinues() {
        var t = 0L
        var work = 0L
        val deadline = AnalysisDeadline(limitMs = 60_000, stallMs = 25_000, progress = { work }, now = { t })
        val cancelled = deadline.cancelled { false }
        while (t < 55_000) { t += 5_000; work += 1; assertFalse(cancelled()) }
        t += 5_000
        assertTrue(cancelled())
        assertTrue(deadline.expired)
        assertFalse(deadline.stalled)
    }
}
