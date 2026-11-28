package com.music.spotui.data.local

/**
 * When a play counts, decided from the player's state reports (SongPlayer's, from either
 * player). Tyson's rule (v34): a song counts as a play the moment it STARTS — a tap, an
 * auto-advance, a shuffle landing, a repeat from the top — and every kind of song counts
 * the same (a phone file, an imported song, a catalog/extension song). Exactly once per
 * start: a pause and a resume of the same uninterrupted song is not a new start, and a
 * song that is only cued up (shown, buffering, restored at launch) counts when it first
 * actually plays, not before — a tap no source could play never becomes the current song
 * and so never counts.
 *
 * The counter is pure: the caller feeds reports with its own clocks and the sink writes
 * the records ([ListenHistory] in the app). A play's record is begun at its start; the
 * time it plays for accrues here and is handed to the sink as it grows (on pause, every
 * [CHECKPOINT_MS] of growth, and at the end), so the recap's minutes stay honest with
 * exactly one record per play.
 *
 * Reports from a muted context (the Mix editor's transition preview, whose plays are
 * auditions, not listens) close any open play and never begin one.
 */
class PlayCounter(private val sink: Sink) {

    interface Sink {
        /** A play of [listen] (its playedMs is 0) starts now: write its record. */
        fun begin(listen: ListenHistory.Listen)
        /** The open play has played [playedMs] so far. */
        fun update(playedMs: Long)
        /** The open play is over: [playedMs] is its final played time. */
        fun end(playedMs: Long)
    }

    private var key: String? = null
    private var proto: ListenHistory.Listen? = null
    private var muted = false
    private var open = false
    private var playedMs = 0L
    private var writtenMs = 0L
    private var tickAt = -1L
    private var wasPlaying = false

    /**
     * One state report: the song under [key] is current, [proto] is its record-to-be (its
     * atMs/playedMs are set by the counter), [isPlaying] whether it plays, [restarted]
     * whether it just started again from the top under the same key (a repeat), [muted]
     * whether this report comes from a context that does not count.
     */
    fun report(
        key: String,
        proto: ListenHistory.Listen?,
        isPlaying: Boolean,
        restarted: Boolean = false,
        muted: Boolean = false,
        elapsedMs: Long,
        wallMs: Long,
    ) {
        if (open && wasPlaying && tickAt >= 0) playedMs += (elapsedMs - tickAt).coerceAtLeast(0)
        when {
            restarted && key == this.key && open && !this.muted -> {
                // The same song starts again from the top: the play that was open ends,
                // a new one begins (below, if it is playing).
                sink.end(playedMs)
                open = false; playedMs = 0; writtenMs = 0
                if (proto != null) this.proto = proto
            }
            key != this.key || muted != this.muted -> {
                if (open) sink.end(playedMs)
                this.key = key; this.proto = proto; this.muted = muted
                open = false; playedMs = 0; writtenMs = 0
            }
            else -> if (proto != null) this.proto = proto
        }
        if (!open && !this.muted && isPlaying) {
            this.proto?.let { p ->
                sink.begin(p.copy(atMs = wallMs, playedMs = 0))
                open = true; writtenMs = 0
            }
        }
        if (open) {
            if (!isPlaying && playedMs > writtenMs) { sink.update(playedMs); writtenMs = playedMs }
            else if (playedMs - writtenMs >= CHECKPOINT_MS) { sink.update(playedMs); writtenMs = playedMs }
        }
        tickAt = elapsedMs
        wasPlaying = isPlaying
    }

    /**
     * The player put the current song back at 0:00 to play it again (LocalPlayer's repeat,
     * signalled outside the state reports): the open play ends and a new one begins at once.
     * No open play (the song was only cued): nothing to restart.
     */
    fun restart(elapsedMs: Long, wallMs: Long) {
        if (!open) return
        if (wasPlaying && tickAt >= 0) playedMs += (elapsedMs - tickAt).coerceAtLeast(0)
        sink.end(playedMs)
        open = false; playedMs = 0; writtenMs = 0
        proto?.let { p ->
            sink.begin(p.copy(atMs = wallMs, playedMs = 0))
            open = true
        }
        tickAt = elapsedMs
    }

    /** The session is over (signed out, the player torn down): the open play ends here. */
    fun close(elapsedMs: Long) {
        if (open && wasPlaying && tickAt >= 0) playedMs += (elapsedMs - tickAt).coerceAtLeast(0)
        if (open) sink.end(playedMs)
        key = null; proto = null; muted = false
        open = false; playedMs = 0; writtenMs = 0
        tickAt = -1L; wasPlaying = false
    }

    companion object {
        /** Played time is written back at least this often while a play runs on. */
        const val CHECKPOINT_MS = 15_000L
    }
}
