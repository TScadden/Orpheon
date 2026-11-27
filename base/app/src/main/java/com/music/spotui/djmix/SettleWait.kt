/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.djmix

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Waits until every asked song has settled — analysed OR refused — or the deadline passes,
 * reporting the settled count as it grows. A failure settles exactly like a success: the
 * progress bar moves either way, and the caller reads back what actually arrived. (Before,
 * progress was published per finished batch, so one wedged batch froze the count at its start
 * for the batch's whole deadline — DJ Mix sat at "0 of 30" with no error and no movement.)
 */
internal object SettleWait {
    /** How many of [total] settled when the wait ended (all of them, or the deadline's count). */
    suspend fun await(
        total: Int,
        settledCount: () -> Int,
        deadlineMs: Long,
        pollMs: Long,
        now: () -> Long = System::currentTimeMillis,
        sleep: suspend (Long) -> Unit = { delay(it) },
        onProgress: (Int) -> Unit = {},
    ): Int {
        val deadline = now() + deadlineMs
        var last = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = settledCount().coerceIn(0, total)
            if (count != last) { last = count; onProgress(count) }
            if (count >= total || now() >= deadline) return count
            sleep(pollMs)
        }
    }
}
