/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

/**
 * How far one song's analysis has got, as a fraction — built only from what the work itself
 * truthfully knows, never from elapsed time:
 *
 * - the decode half is the decoder's own position: decoded seconds against the song's
 *   duration, or bytes read against the stream's total size when no duration is known;
 * - the analysis half is the analyser's own frames: onset frames completed against the
 *   song's frame count;
 * - a song that has settled (analysed or refused) is done: 1.
 *
 * Each half is a real fraction of its own phase; the song's number is their mean. While the
 * decode's total is genuinely unknown (no duration, no stream size) and analysis has not
 * started, the song's fraction is null — unknown — and any combination containing it is
 * null too: the screens show an indeterminate spinner rather than a made-up percentage.
 * Once analysis is running the decode is necessarily complete, so an unknown decode total
 * then counts as a finished half.
 */
internal object AnalysisProgress {
    /** [decode]: the decode fraction, null while its total is unknown; [analyze]: 0..1. */
    fun song(decode: Float?, analyze: Float, settled: Boolean): Float? {
        if (settled) return 1f
        val a = analyze.coerceIn(0f, 1f)
        val d = when {
            decode != null -> decode.coerceIn(0f, 1f)
            a > 0f -> 1f
            else -> return null
        }
        return (d + a) / 2f
    }

    /** The pair's fraction: the mean of the two songs'; null while either song's is unknown. */
    fun pair(a: Float?, b: Float?): Float? {
        if (a == null || b == null) return null
        return (a.coerceIn(0f, 1f) + b.coerceIn(0f, 1f)) / 2f
    }
}
