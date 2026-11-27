/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

/**
 * One song's analysis bound, measured on the song's OWN work — the clock starts when the
 * caller starts the song (never while it waits in a queue behind other songs), and it is a
 * progress watchdog, not a flat timer:
 *
 * - **Stall bound** ([stallMs]): the song counts as timed out when its [progress] has not
 *   advanced for this long. This is the bound that kills the v30 wedge — a stream that
 *   stalls, or trickles so slowly that the decoder is never handed another chunk: no bytes
 *   delivered, no frames analysed, no progress. A source that keeps delivering, however
 *   slowly, is NOT timed out by this bound: a big song on a slow connection is slow, not
 *   unanalyzable.
 * - **Hard bound** ([limitMs]): however steadily a song progresses, its own work may not
 *   run past this. A source advancing just often enough to dodge the stall bound forever
 *   still settles here, so a wedge stays impossible.
 *
 * [progress] is any monotonic work counter the caller advances as the song's work advances
 * (bytes handed to the decoder, analysis frames completed). [expired] says the bound (not
 * the caller) ended the song; [stalled] says which bound — the stall bound firing means the
 * work stopped moving, the hard bound firing means it moved but ran too long.
 *
 * With no [progress] signal the counter never advances, so the stall bound is the whole
 * rule and [stallMs] defaults to [limitMs]: the original fixed-deadline behaviour.
 */
internal class AnalysisDeadline(
    private val limitMs: Long,
    private val stallMs: Long = limitMs,
    private val progress: () -> Long = { 0L },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val start = now()
    private var lastProgress = progress()
    private var lastAdvance = start

    /** True once a bound itself (not the caller's own cancellation) ended the wait. */
    @Volatile var expired: Boolean = false
        private set

    /** True when the stall bound fired (no progress); false when the hard bound fired. */
    @Volatile var stalled: Boolean = false
        private set

    /** The decoder's cancellation: the caller's own reason, or a bound passing. */
    fun cancelled(user: () -> Boolean): () -> Boolean = {
        val t = now()
        val p = progress()
        if (p != lastProgress) { lastProgress = p; lastAdvance = t }
        val hard = t - start >= limitMs
        val stall = t - lastAdvance >= stallMs
        if (hard || stall) {
            if (!expired) { expired = true; stalled = stall && !hard }
            true
        } else user()
    }
}
