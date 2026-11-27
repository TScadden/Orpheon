/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import kotlin.math.abs
import kotlin.math.max

/**
 * A song's waveform peaks, gathered frame by frame at the audio's own rate while it decodes
 * — the fast path for a song whose analysis already exists and only its peaks are missing.
 *
 * The full analysis decode resamples the whole song to 22.05 kHz mid/side signals (two large
 * float arrays) and [WaveformPeaks.compute] then walks them again; for peaks alone none of
 * that is needed. Peaks are an envelope: the loudest frame in each time bucket. A frame's
 * envelope value here is max(|L|, |R|) — exactly |mid| + |side| for the decoder's
 * mid = (L + R) / 2, side = (L − R) / 2, so the numbers are the same peaks the full path
 * computes from its signals, taken at the native rate instead of after the box-average
 * resample (which can only lower a peak). Buckets are placed by the frame's own timestamp,
 * so no signal is ever materialised: memory is the bucket array alone.
 */
internal class PeakAccumulator(private val durationSec: Double, private val buckets: Int = WaveformPeaks.BUCKETS) {
    private val peaks = FloatArray(buckets)
    private var top = 0f

    /** The furthest timestamp handed in, seconds: how much of the song was actually decoded. */
    var coveredSec = 0.0
        private set

    /** One decoded frame: its channel values and its position in the song, seconds. */
    fun add(l: Float, r: Float, atSec: Double) {
        if (atSec > coveredSec) coveredSec = atSec
        if (durationSec <= 0.0 || buckets <= 0) return
        val b = (atSec / durationSec * buckets).toInt().coerceIn(0, buckets - 1)
        val v = max(abs(l), abs(r))
        if (v > peaks[b]) {
            peaks[b] = v
            if (v > top) top = v
        }
    }

    /** The peaks, normalised so the loudest bucket is 1 (all zero for silence). */
    fun result(): FloatArray {
        val out = peaks.copyOf()
        if (top > 0f) for (i in out.indices) out[i] = (out[i] / top).coerceIn(0f, 1f)
        return out
    }
}
