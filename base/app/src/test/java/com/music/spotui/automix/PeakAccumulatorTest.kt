/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The fast peaks path: frames fed by their own timestamps must land on the same peaks the
 * full decode computes from its resampled signals — per sample, |mid| + |side| is exactly
 * the louder channel's amplitude, so the two paths agree bucket for bucket.
 */
class PeakAccumulatorTest {
    @Test fun theFastPathPeaksMatchTheFullPathOnASyntheticSong() {
        val rate = 44_100
        val seconds = 6
        val n = rate * seconds
        val left = FloatArray(n) {
            (0.6 * sin(2 * PI * 220.0 * it / rate) * (0.5 + 0.5 * sin(2 * PI * it.toDouble() / n))).toFloat()
        }
        val right = FloatArray(n) { (0.4 * sin(2 * PI * 330.0 * it / rate + 0.7)).toFloat() }
        val buckets = 64
        val acc = PeakAccumulator(seconds.toDouble(), buckets)
        for (i in 0 until n) acc.add(left[i], right[i], i.toDouble() / rate)
        val fast = acc.result()
        val mid = FloatArray(n) { (left[it] + right[it]) / 2f }
        val side = FloatArray(n) { (left[it] - right[it]) / 2f }
        val full = WaveformPeaks.compute(mid, side, n, buckets)
        assertEquals(buckets, fast.size)
        for (b in 0 until buckets) assertEquals("bucket $b", full[b], fast[b], 0.01f)
        assertEquals(1f, fast.max(), 0.001f)
    }

    @Test fun silenceIsAllZeroAndCoverageIsTracked() {
        val acc = PeakAccumulator(10.0, 16)
        for (i in 0 until 100) acc.add(0f, 0f, i * 0.05)
        assertTrue(acc.result().all { it == 0f })
        assertEquals(99 * 0.05, acc.coveredSec, 0.001)
    }

    @Test fun aFrameAtTheSongsEndLandsInTheLastBucket() {
        val acc = PeakAccumulator(4.0, 8)
        acc.add(0.25f, 0.25f, 0.0)
        acc.add(1f, 1f, 4.0)
        val peaks = acc.result()
        assertEquals(1f, peaks[7], 0.001f)
        assertEquals(0.25f, peaks[0], 0.001f)
    }

    @Test fun aMonoFrameCountsForBothChannels() {
        val acc = PeakAccumulator(2.0, 4)
        acc.add(-0.8f, -0.8f, 0.25)
        assertEquals(1f, acc.result()[0], 0.001f)
    }
}
