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
}
