/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A song's waveform as [BUCKETS] peak amplitudes (0..1, the loudest bucket 1), for drawing —
 * the Mix editor's two waveforms. Computed from the analysis decode's own signals (the mid and
 * side at 22.05 kHz the analyser already holds), in one extra pass over them: no second decode,
 * and the analysis itself is untouched. Stored beside the analysis under its key (see AutoMix).
 */
object WaveformPeaks {
    const val BUCKETS = 512
    private const val VERSION: Byte = 1

    /** Peaks of the decoded [mid]/[side] signals (their first [length] samples). Silence: all zero. */
    fun compute(mid: FloatArray, side: FloatArray, length: Int, buckets: Int = BUCKETS): FloatArray {
        val out = FloatArray(buckets)
        val n = minOf(length, mid.size, side.size)
        if (n <= 0 || buckets <= 0) return out
        var top = 0f
        for (bucket in 0 until buckets) {
            val from = bucket * n / buckets
            val to = maxOf(from + 1, (bucket + 1) * n / buckets)
            var peak = 0f
            for (i in from until minOf(to, n)) {
                // The louder channel's amplitude, from the mid/side pair: |mid| + |side| bounds it.
                val v = abs(mid[i]) + abs(side[i])
                if (v > peak) peak = v
            }
            out[bucket] = peak
            if (peak > top) top = peak
        }
        if (top > 0f) for (i in out.indices) out[i] = (out[i] / top).coerceIn(0f, 1f)
        return out
    }

    /** One byte a peak (0..255) behind a version byte and the bucket count: [BUCKETS] peaks take 515 bytes. */
    fun encode(peaks: FloatArray): ByteArray {
        val out = ByteArray(3 + peaks.size)
        out[0] = VERSION
        out[1] = (peaks.size shr 8).toByte()
        out[2] = (peaks.size and 0xff).toByte()
        for (i in peaks.indices) out[3 + i] = (peaks[i].coerceIn(0f, 1f) * 255).roundToInt().toByte()
        return out
    }

    /** The peaks [encode] wrote, or null for anything else (another version, a truncated file). */
    fun decode(bytes: ByteArray): FloatArray? {
        if (bytes.size < 4 || bytes[0] != VERSION) return null
        val count = ((bytes[1].toInt() and 0xff) shl 8) or (bytes[2].toInt() and 0xff)
        if (count !in 16..4096 || bytes.size != 3 + count) return null
        return FloatArray(count) { (bytes[3 + it].toInt() and 0xff) / 255f }
    }
}
