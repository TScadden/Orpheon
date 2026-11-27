/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.djmix

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DJ Mix's batch wait: progress counts settled songs — analysed OR failed — as they land,
 * and a song that never settles ends the wait at the deadline with the rest counted.
 */
class SettleWaitTest {
    @Test fun progressCountsEachSettledSongAsItLands() = runBlocking {
        var clock = 0L
        // Three songs settle at 500 / 750 / 1000 ms of the wait — the middle one by failing:
        // the wait's predicate counts refusals exactly like analyses (the session's does).
        val settledAt = listOf(500L, 750L, 1_000L)
        val progress = mutableListOf<Int>()
        val count = SettleWait.await(
            total = 3,
            settledCount = { settledAt.count { clock >= it } },
            deadlineMs = 10_000,
            pollMs = 250,
            now = { clock },
            sleep = { clock += it },
            onProgress = { progress += it },
        )
        assertEquals(3, count)
        assertEquals(listOf(0, 1, 2, 3), progress)
    }

    @Test fun oneHangingSongEndsAtTheDeadlineWithTheGoodOneCounted() = runBlocking {
        var clock = 0L
        val progress = mutableListOf<Int>()
        val count = SettleWait.await(
            total = 2,
            settledCount = { 1 },
            deadlineMs = 2_000,
            pollMs = 250,
            now = { clock },
            sleep = { clock += it },
            onProgress = { progress += it },
        )
        assertEquals("the good song settled; the hanging one is counted failed at the deadline", 1, count)
        assertTrue("the wait ended at its deadline, not after it", clock >= 2_000)
        assertEquals(listOf(1), progress)
    }

    @Test fun anAlreadySettledBatchReturnsAtOnce() = runBlocking {
        var clock = 0L
        val count = SettleWait.await(
            total = 2,
            settledCount = { 2 },
            deadlineMs = 2_000,
            pollMs = 250,
            now = { clock },
            sleep = { clock += it },
        )
        assertEquals(2, count)
        assertEquals(0L, clock)
    }
}
