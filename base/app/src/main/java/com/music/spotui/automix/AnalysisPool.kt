/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The analysis workers: a small bounded pool (two by default) with two priorities.
 *
 * One worker (the shape through v31) serialised everything: a song's network fetch, its
 * decode and its CPU analysis ran back to back with nothing overlapping, and every later
 * song — the player's next pair, a DJ Mix pool, the Mix editor's junction — waited for the
 * whole chain. Two workers let one song's fetch+decode overlap another's analysis, which is
 * where the wall time actually goes. The pool stays small on purpose: each analysis is
 * itself parallel inside (the analyser splits its budget across the active songs), and the
 * v30 wedge was an unbounded WAIT, not a worker shortage — every song still carries its own
 * [AnalysisDeadline], so no task in this pool can hold a worker forever.
 *
 * [EDITOR] tasks dequeue before queued [PLAYER] tasks: a junction the user is staring at
 * never sits behind a background batch. Within a priority, tasks run in submission order.
 * A task already running is never preempted — priorities order the queue, nothing more.
 */
internal class AnalysisPool(threads: Int) {
    private val seq = AtomicLong()
    private val queue = PriorityBlockingQueue<Runnable>(64, compareBy<Runnable> { rank(it) }.thenBy { order(it) })
    private val exec = ThreadPoolExecutor(
        threads, threads, 0L, TimeUnit.MILLISECONDS, queue,
        { r -> Thread(r, "automix").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } },
    )

    private class Task(val priority: Int, val order: Long, val body: () -> Unit) : Runnable {
        override fun run() = body()
    }

    private fun rank(r: Runnable): Int = (r as? Task)?.priority ?: PLAYER
    private fun order(r: Runnable): Long = (r as? Task)?.order ?: 0L

    /** Runs [task] on a pool worker, [EDITOR] asks ahead of queued [PLAYER] asks. */
    fun submit(priority: Int, task: () -> Unit) {
        exec.execute(Task(priority, seq.getAndIncrement(), task))
    }

    companion object {
        /** The Mix editor's asks: the user is looking at the junction now. */
        const val EDITOR = 0

        /** The player's and DJ Mix's asks: background preparation. */
        const val PLAYER = 1
    }
}
