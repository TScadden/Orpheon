/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

/**
 * One song's analysis bound: past [limitMs] the song counts as failed, never as still coming.
 *
 * The decoder's only exits used to be data or the caller's cancellation (the song leaving the
 * player's `wanted` set) — a stream that stalled, or trickled slowly enough to defeat every
 * per-read timeout, held the single analysis worker forever, and every later request queued
 * behind it (DJ Mix sat at "0 of 30": its batches were never even started). This deadline rides
 * inside the same `cancelled` the decoder already polls, so a stalled fetch or decode aborts
 * within a poll of the limit, and [expired] lets the caller record the song as refused
 * (settled) instead of dropping it silently.
 */
internal class AnalysisDeadline(
    private val limitMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val start = now()

    /** True once the limit itself (not the caller's own cancellation) ended the wait. */
    @Volatile var expired: Boolean = false
        private set

    /** The decoder's cancellation: the caller's own reason, or this deadline passing. */
    fun cancelled(user: () -> Boolean): () -> Boolean = {
        val past = now() - start >= limitMs
        if (past) expired = true
        past || user()
    }
}
