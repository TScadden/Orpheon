package com.music.spotui.ui.immersive.lyrics

import androidx.compose.ui.graphics.Color
import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.LyricWord
import com.music.spotui.ui.look.SweptLine
import com.music.spotui.ui.look.toSwept
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricGlowModelTest {

    private fun lines(vararg starts: Long) = starts.map { LyricLine(it, "line at $it") }

    // ─── The active line ───

    @Test fun activeIndexBeforeTheFirstLineIsMinusOne() {
        assertEquals(-1, glowActiveIndex(lines(1_000, 5_000), 0))
        assertEquals(-1, glowActiveIndex(lines(1_000, 5_000), 999))
        assertEquals(-1, glowActiveIndex(emptyList(), 5_000))
    }

    @Test fun activeIndexIsTheLastLineStarted() {
        val ls = lines(1_000, 5_000, 9_000)
        assertEquals(0, glowActiveIndex(ls, 1_000))
        assertEquals(0, glowActiveIndex(ls, 4_999))
        assertEquals(1, glowActiveIndex(ls, 5_000))
        assertEquals(2, glowActiveIndex(ls, 60_000))
    }

    // ─── Line-timed progress: an even sweep over the line's span ───

    private fun plainLine(start: Long) = SweptLine(timeMs = start, text = "1234567890")

    @Test fun lineProgressCrossesEvenlyUntilTheNextLine() {
        val line = plainLine(1_000)
        assertEquals(0f, glowProgress(line, 5_000, 60_000, 500), 0.001f)
        assertEquals(0f, glowProgress(line, 5_000, 60_000, 1_000), 0.001f)
        assertEquals(0.5f, glowProgress(line, 5_000, 60_000, 3_000), 0.001f)
        assertEquals(1f, glowProgress(line, 5_000, 60_000, 5_000), 0.001f)
        assertEquals(1f, glowProgress(line, 5_000, 60_000, 9_000), 0.001f)
    }

    @Test fun lastLineProgressRunsToTheTracksEnd() {
        val line = plainLine(10_000)
        assertEquals(0.5f, glowProgress(line, null, 20_000, 15_000), 0.001f)
        assertEquals(1f, glowProgress(line, null, 20_000, 25_000), 0.001f)
    }

    @Test fun lastLineWithNoKnownEndFallsBackToAShortSpan() {
        val line = plainLine(10_000)
        assertEquals(0f, glowProgress(line, null, 0, 10_000), 0.001f)
        assertEquals(0.5f, glowProgress(line, null, 0, 12_500), 0.001f)
        assertEquals(1f, glowProgress(line, null, 0, 20_000), 0.001f)
    }

    @Test fun aLinesOwnStatedEndBeatsTheNextLinesStart() {
        val line = SweptLine(timeMs = 1_000, text = "1234567890", sungUntilMs = 3_000)
        assertEquals(0.5f, glowProgress(line, 9_000, 60_000, 2_000), 0.001f)
        assertEquals(1f, glowProgress(line, 9_000, 60_000, 3_000), 0.001f)
    }

    @Test fun lineRevealedCharsScaleWithProgress() {
        val line = plainLine(1_000)
        assertEquals(5f, glowRevealedChars(line, 5_000, 60_000, 3_000), 0.001f)
        assertEquals(0f, glowRevealedChars(line, 5_000, 60_000, 1_000), 0.001f)
        assertEquals(10f, glowRevealedChars(line, 5_000, 60_000, 5_000), 0.001f)
    }

    // ─── Word-timed progress: the sweep follows the words ───

    private fun wordLine() = LyricLine(
        timeMs = 1_000,
        text = "Hello world",
        words = listOf(LyricWord(1_000, 2_000, "Hello"), LyricWord(2_000, 3_000, "world")),
    ).toSwept(4_000)

    @Test fun wordProgressFollowsEachWord() {
        val line = wordLine()
        assertEquals(0f, glowProgress(line, 4_000, 60_000, 500), 0.001f)
        // Halfway through "Hello": 2.5 of 11 characters.
        assertEquals(2.5f / 11f, glowProgress(line, 4_000, 60_000, 1_500), 0.001f)
        // Halfway through "world": 6 + 2.5 characters.
        assertEquals(8.5f / 11f, glowProgress(line, 4_000, 60_000, 2_500), 0.001f)
        assertEquals(1f, glowProgress(line, 4_000, 60_000, 3_000), 0.001f)
    }

    @Test fun wordRevealedCharsMatchTheWords() {
        val line = wordLine()
        assertEquals(2.5f, glowRevealedChars(line, 4_000, 60_000, 1_500), 0.001f)
        assertEquals(11f, glowRevealedChars(line, 4_000, 60_000, 4_000), 0.001f)
    }

    // ─── The gradient ───

    @Test fun gradientPicksTheFurthestHueForTheSecondStop() {
        val purple = Color(0xFF7C3AED)
        val gold = Color(0xFFF5C518)
        val (first, second) = glowGradient(listOf(purple, gold), Color.Magenta)
        assertEquals(purple, first)
        assertEquals(gold, second)
    }

    @Test fun gradientWithOneHueLightensTowardWhite() {
        val red = Color(0xFFD42B1E)
        val (first, second) = glowGradient(listOf(red), Color.Magenta)
        assertEquals(red, first)
        assertTrue("the second stop is lighter than the first", second.green > first.green && second.blue > first.blue)
    }

    @Test fun gradientFallsBackAndLiftsDarkColours() {
        val nearBlack = Color(0xFF050510)
        val (first, _) = glowGradient(emptyList(), nearBlack)
        assertTrue("a near-black fallback is lifted until it reads", first.red > nearBlack.red && first.blue > nearBlack.blue)
        val (darkFirst, _) = glowGradient(listOf(Color(0xFF0A0A2E)), Color.Magenta)
        assertTrue("a too-dark palette colour is lifted too", darkFirst.blue > Color(0xFF0A0A2E).blue)
    }

    @Test fun gradientIgnoresTransparentCandidates() {
        val purple = Color(0xFF7C3AED)
        val (first, _) = glowGradient(listOf(Color.Transparent, purple), Color.Magenta)
        assertEquals(purple, first)
    }

    // ─── The bars ───

    @Test fun barsAreDeterministicBoundedAndCounted() {
        val a = glowBarHeights(12_345, 40, 1f)
        val b = glowBarHeights(12_345, 40, 1f)
        assertEquals(a, b)
        assertEquals(40, a.size)
        assertTrue(a.all { it in 0f..1f })
        assertEquals(emptyList<Float>(), glowBarHeights(0, 0, 1f))
    }

    @Test fun barsRestNearlyFlatAtNoEnergyAndDanceAtFull() {
        val rest = glowBarHeights(12_345, 40, 0f)
        assertTrue(rest.all { it == rest.first() })
        val dancing = glowBarHeights(12_345, 40, 1f)
        assertTrue(dancing.max() > rest.max())
        assertTrue("the bars vary along the row", dancing.distinct().size > 10)
    }

    @Test fun barsMoveWithTime() {
        val a = glowBarHeights(1_000, 40, 1f)
        val b = glowBarHeights(1_400, 40, 1f)
        assertTrue(a != b)
    }
}
