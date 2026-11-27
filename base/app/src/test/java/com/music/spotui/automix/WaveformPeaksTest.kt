/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Mix editor's waveform data: the format is pinned (count, normalization, round-trip). */
class WaveformPeaksTest {
    @Test fun computeNormalizesToTheLoudestBucket() {
        val n = 22_050
        val mid = FloatArray(n)
        val side = FloatArray(n)
        // The first half quiet, one loud hit in the second half.
        for (i in 0 until n / 2) mid[i] = 0.1f
        mid[(n * 0.75).toInt()] = 0.9f
        val peaks = WaveformPeaks.compute(mid, side, n)
        assertEquals(WaveformPeaks.BUCKETS, peaks.size)
        assertEquals(1.0f, peaks.max(), 1e-6f)
        assertTrue(peaks.all { it in 0f..1f })
        // The quiet half reads at its share of the loudest bucket (0.1 / 0.9), not amplified away.
        assertEquals(0.1f / 0.9f, peaks[10], 0.02f)
    }

    @Test fun silenceIsAllZerosAndShortSignalsAreSafe() {
        val peaks = WaveformPeaks.compute(FloatArray(100), FloatArray(100), 100)
        assertEquals(WaveformPeaks.BUCKETS, peaks.size)
        assertTrue(peaks.all { it == 0f })
        assertEquals(WaveformPeaks.BUCKETS, WaveformPeaks.compute(FloatArray(0), FloatArray(0), 0).size)
        // A length past the arrays' end is clamped, not an error.
        assertEquals(WaveformPeaks.BUCKETS, WaveformPeaks.compute(FloatArray(10) { 0.5f }, FloatArray(10), 999).size)
    }

    @Test fun encodeDecodeRoundTripsAndRejectsStrangers() {
        val peaks = FloatArray(WaveformPeaks.BUCKETS) { (it % 17) / 16f }
        val bytes = WaveformPeaks.encode(peaks)
        assertEquals(3 + WaveformPeaks.BUCKETS, bytes.size)
        val back = WaveformPeaks.decode(bytes)!!
        assertEquals(peaks.size, back.size)
        for (i in peaks.indices) assertEquals(peaks[i], back[i], 1f / 255 + 1e-6f)
        // Another version, a truncated file, and a stranger's bytes are all refused.
        val otherVersion = bytes.copyOf().also { it[0] = 9 }
        assertNull(WaveformPeaks.decode(otherVersion))
        assertNull(WaveformPeaks.decode(bytes.copyOf(bytes.size - 1)))
        assertNull(WaveformPeaks.decode(ByteArray(0)))
        assertNull(WaveformPeaks.decode("not peaks at all, just text".toByteArray()))
    }
}
