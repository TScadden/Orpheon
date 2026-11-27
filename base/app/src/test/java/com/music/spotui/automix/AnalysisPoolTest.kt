/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The analysis pool: two workers, editor priority. These are also the fixture timings behind
 * the speed report — the same six songs through one worker (the shape through v31) and
 * through two.
 */
class AnalysisPoolTest {
    @Test fun twoSongsRunAtOnceOnTwoWorkersAndOneAloneOnOne() {
        for ((workers, expected) in listOf(2 to 2, 1 to 1)) {
            val pool = AnalysisPool(workers)
            val inside = AtomicInteger()
            val highWater = AtomicInteger()
            val release = CountDownLatch(1)
            val done = CountDownLatch(4)
            repeat(4) {
                pool.submit(AnalysisPool.PLAYER) {
                    val n = inside.incrementAndGet()
                    highWater.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                    release.await(5, TimeUnit.SECONDS)
                    inside.decrementAndGet()
                    done.countDown()
                }
            }
            val until = System.currentTimeMillis() + 5_000
            while (inside.get() < expected && System.currentTimeMillis() < until) Thread.sleep(5)
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals("workers=$workers", expected, highWater.get())
        }
    }

    @Test fun anEditorTaskJumpsQueuedPlayerTasks() {
        val pool = AnalysisPool(1)
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(3)
        // Occupy the only worker, queue a background song behind it, then ask as the editor:
        // the editor's song runs before the queued background song, never behind it.
        pool.submit(AnalysisPool.PLAYER) {
            started.countDown()
            gate.await(5, TimeUnit.SECONDS)
            order += "first"
            done.countDown()
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        pool.submit(AnalysisPool.PLAYER) { order += "player"; done.countDown() }
        pool.submit(AnalysisPool.EDITOR) { order += "editor"; done.countDown() }
        Thread.sleep(200)
        gate.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("first", "editor", "player"), order.toList())
    }

    @Test fun tasksWithinOnePriorityRunInSubmissionOrder() {
        val pool = AnalysisPool(1)
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<Int>())
        val done = CountDownLatch(4)
        pool.submit(AnalysisPool.PLAYER) {
            started.countDown()
            gate.await(5, TimeUnit.SECONDS)
            done.countDown()
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        repeat(3) { i -> pool.submit(AnalysisPool.PLAYER) { order += i; done.countDown() } }
        gate.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(0, 1, 2), order.toList())
    }

    @Test fun thePairCostsAboutOneSongNotTwo() {
        // Six 150 ms songs: ~900 ms through one worker, ~450 ms through two. The bounds are
        // loose (CI machines are shared); the ordering between the pools is the assertion.
        fun run(workers: Int): Long {
            val pool = AnalysisPool(workers)
            val done = CountDownLatch(6)
            val t0 = System.nanoTime()
            repeat(6) { pool.submit(AnalysisPool.PLAYER) { Thread.sleep(150); done.countDown() } }
            assertTrue(done.await(30, TimeUnit.SECONDS))
            return (System.nanoTime() - t0) / 1_000_000
        }
        val serial = run(1)
        val parallel = run(2)
        println("AnalysisPool fixture: 6×150 ms — 1 worker ${serial} ms, 2 workers ${parallel} ms")
        assertTrue("serial ${serial} ms", serial >= 800)
        assertTrue("parallel ${parallel} ms should beat serial ${serial} ms", parallel < serial * 3 / 4)
    }
}
