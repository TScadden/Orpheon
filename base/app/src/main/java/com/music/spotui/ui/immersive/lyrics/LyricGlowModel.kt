package com.music.spotui.ui.immersive.lyrics

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.music.spotui.data.entity.LyricLine
import com.music.spotui.ui.look.SweptLine
import kotlin.math.abs
import kotlin.math.sin

/**
 * The arithmetic of the LyricGlow view (see LyricGlow.kt): which line is being sung, how far
 * the sweep has crossed it, the two colours it glows in, and the bars along the bottom edge.
 * Pure, so it is unit-tested without a screen.
 */

/** The line being sung at [positionMs]: the last one starting at or before it, -1 before the first. */
fun glowActiveIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var lo = 0
    var hi = lines.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].timeMs <= positionMs) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}

/** How long a last line with no known end is swept over. */
private const val FALLBACK_LINE_MS = 5_000L

/**
 * How far the sweep has crossed [line] at [positionMs], 0..1.
 *
 * Word-timed lines follow their words (see [SweptLine.revealedChars]: a held note crosses
 * slowly, a rattled-off one snaps). Line-timed lines have no words to follow, so the fill
 * crosses the line evenly over its span — until the next line starts, or the line's own
 * stated end, or the track's end for the last one.
 */
fun glowProgress(line: SweptLine, nextStartMs: Long?, durationMs: Long, positionMs: Long): Float {
    if (line.isWordSynced) {
        if (line.text.isEmpty()) return 0f
        return (line.revealedChars(positionMs) / line.text.length).coerceIn(0f, 1f)
    }
    val end = line.sungUntilMs ?: nextStartMs ?: if (durationMs > line.timeMs) durationMs else line.timeMs + FALLBACK_LINE_MS
    if (end <= line.timeMs) return if (positionMs >= line.timeMs) 1f else 0f
    return ((positionMs - line.timeMs).toFloat() / (end - line.timeMs)).coerceIn(0f, 1f)
}

/** The sweep's edge at [positionMs], as a fractional character index into [SweptLine.text]. */
fun glowRevealedChars(line: SweptLine, nextStartMs: Long?, durationMs: Long, positionMs: Long): Float =
    if (line.isWordSynced) {
        line.revealedChars(positionMs).coerceIn(0f, line.text.length.toFloat())
    } else {
        glowProgress(line, nextStartMs, durationMs, positionMs) * line.text.length
    }

/** A glow colour darker than this against the black ground would barely read. */
private const val MIN_GLOW_LUMINANCE = 0.10f

/** Two stops closer in hue than this read as one colour, not a sweep between two. */
private const val MIN_HUE_DISTANCE = 40f

/** [c] lightened toward white until it reads on black (the hue kept, the value raised). */
private fun liftForBlack(c: Color): Color {
    var out = c
    var t = 0f
    while (out.luminance() < MIN_GLOW_LUMINANCE && t < 0.6f) {
        t += 0.1f
        out = lerp(c, Color.White, t)
    }
    return out
}

/** [c]'s hue in degrees, 0..360; a grey has none, answered 0. */
private fun hueOf(c: Color): Float {
    val r = c.red
    val g = c.green
    val b = c.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    if (d <= 0.0001f) return 0f
    val h = when (max) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    return (h + 360f) % 360f
}

private fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

/**
 * The sweep's two colours, drawn from the cover's [colors] (its artwork palette, the
 * lyrics' own highlight among them when the source gives one): the first colour, then the
 * candidate furthest from it in hue — purple into gold, in the reference. A palette with
 * only one hue to give lightens its first colour toward white for the second stop instead
 * (the reference's red into white). No colours at all: [fallback], lightened the same way.
 * Every stop is lifted until it reads on black.
 */
fun glowGradient(colors: List<Color>, fallback: Color): Pair<Color, Color> {
    val usable = colors.filter { it.alpha > 0.5f }.map { liftForBlack(it) }
    val first = usable.firstOrNull() ?: liftForBlack(fallback)
    val firstHue = hueOf(first)
    val second = usable.drop(1)
        .maxByOrNull { hueDistance(hueOf(it), firstHue) }
        ?.takeIf { hueDistance(hueOf(it), firstHue) >= MIN_HUE_DISTANCE }
        ?: lerp(first, Color.White, 0.45f)
    return first to second
}

/** The bars' floor: at no energy they rest at this height, a near-flat line along the edge. */
private const val BAR_FLOOR = 0.06f

/**
 * The bottom visualizer's bar heights, 0..1, at [timeMs] — a drawing, not a measurement:
 * three slow waves over a per-bar character, scaled by [energy] (1 while playing, low
 * while paused, so the bars settle rather than stop dead). Deterministic in its inputs,
 * so a frame can be tested and the bars never jump between redraws.
 */
fun glowBarHeights(timeMs: Long, count: Int, energy: Float): List<Float> {
    if (count <= 0) return emptyList()
    val e = energy.coerceIn(0f, 1f)
    val t = timeMs / 1000.0
    return List(count) { i ->
        val character = 0.45f + 0.55f * (((i * 2654435761L) % 1000L) / 1000f)
        val wave = sin(t * 2.1 + i * 0.55) * 0.5 + sin(t * 3.7 + i * 1.31) * 0.3 + sin(t * 6.3 + i * 2.17) * 0.2
        (BAR_FLOOR + (0.5 + 0.5 * wave).toFloat() * character * e * (1f - BAR_FLOOR)).coerceIn(0f, 1f)
    }
}
