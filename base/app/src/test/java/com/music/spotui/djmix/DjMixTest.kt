/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.djmix

import app.myspoti.api.AlbumRef
import app.myspoti.api.ArtistRef
import app.myspoti.api.Explicitness
import app.myspoti.api.Image
import app.myspoti.api.Track
import app.myspoti.automix.Analyzer
import app.myspoti.automix.Features
import app.myspoti.automix.TrackAnalysis
import app.myspoti.catalog.Hit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * DJ Mix's pure seams: the categories the catalog's genre listing yields, the
 * pool a chart forms (Clean Rap's known-clean rule above all), the sequencing
 * by the AutoMix planner's transition scores, the playlist's name, and the
 * finished mix's honest counts.
 */
class DjMixTest {

    // ── Categories ──

    /** Deezer's genre listing as it answers today (id to name). */
    private val deezerGenres = listOf(
        0 to "All", 132 to "Pop", 116 to "Rap/Hip Hop", 122 to "Reggaeton", 152 to "Rock",
        113 to "Dance", 165 to "R&B", 85 to "Alternative", 186 to "Christian", 106 to "Electro",
        466 to "Folk", 144 to "Reggae", 129 to "Jazz", 84 to "Country", 67 to "Salsa",
        65 to "Traditional Mexicano", 98 to "Classical", 173 to "Films/Games", 464 to "Metal",
        169 to "Soul & Funk", 2 to "African Music", 16 to "Asian Music", 153 to "Blues",
        75 to "Brazilian Music", 71 to "Cumbia", 81 to "Indian Music", 95 to "Kids", 197 to "Latin Music",
    ).map { (id, name) -> DjGenre(id, name) }

    @Test fun categoriesComeFromTheCatalogsOwnGenres() {
        val categories = DjCategories.fromGenres(deezerGenres)
        val labels = categories.map { it.label }
        // The picker's head, in order: the week's top, then Tyson's named categories,
        // Clean Rap riding right after Rap on Rap's own chart.
        assertEquals(listOf("Top this week", "Pop", "Rap", "Clean Rap", "Folk"), labels.take(5))
        assertTrue("Rock" in labels && "Jazz" in labels && "Metal" in labels && "Classical" in labels)
        val byLabel = categories.associateBy { it.label }
        assertEquals(0, byLabel.getValue("Top this week").genreId)
        assertEquals(132, byLabel.getValue("Pop").genreId)
        assertEquals(116, byLabel.getValue("Rap").genreId)
        assertEquals(466, byLabel.getValue("Folk").genreId)
        val cleanRap = byLabel.getValue("Clean Rap")
        assertEquals(116, cleanRap.genreId)
        assertTrue(cleanRap.cleanOnly)
        assertFalse(byLabel.getValue("Rap").cleanOnly)
        // Every entry is a distinct picker row.
        assertEquals(categories.size, categories.map { it.key }.distinct().size)
    }

    @Test fun categoriesSkipWhatTheCatalogDoesNotList() {
        val some = DjCategories.fromGenres(listOf(DjGenre(132, "Pop"), DjGenre(466, "Folk")))
        assertEquals(listOf("Pop", "Folk"), some.map { it.label })
        // No Rap in the listing: no Rap entry, and no Clean Rap either.
        assertTrue(DjCategories.fromGenres(listOf(DjGenre(132, "Pop"))).none { it.cleanOnly })
        assertTrue(DjCategories.fromGenres(emptyList()).isEmpty())
    }

    // ── Pool ──

    private fun hit(id: Long, title: String, explicitness: Explicitness, playable: Boolean = true, isrc: String? = null) = Hit(
        Track(
            uri = "deezer:track:$id",
            name = title,
            durationMs = 200_000,
            artists = listOf(ArtistRef("deezer:artist:7", "Band")),
            album = AlbumRef("deezer:album:9", "Album", listOf(Image("cover-big", 500, 500), Image("cover-small", 56, 56))),
            explicit = explicitness == Explicitness.EXPLICIT,
            explicitness = explicitness,
            playable = playable,
        ),
        isrc,
    )

    @Test fun poolKeepsThePlayableChartInOrder() {
        val hits = listOf(
            hit(5, "One", Explicitness.EXPLICIT, isrc = "ISRC1"),
            hit(6, "Two", Explicitness.CLEAN),
            hit(5, "One again", Explicitness.EXPLICIT),
            hit(7, "Gone", Explicitness.CLEAN, playable = false),
            hit(8, "Three", Explicitness.UNKNOWN),
        )
        val pool = DjPool.fromChart(hits, cleanOnly = false)
        // Chart order, each song once, the unplayable one out; explicitness is
        // carried as stated (no filtering outside the clean rule).
        assertEquals(listOf("One", "Two", "Three"), pool.map { it.title })
        assertEquals("deezer.5", pool[0].key)
        assertEquals("deezer:track:5", pool[0].catalogUri)
        assertEquals("ISRC1", pool[0].isrc)
        assertEquals("cover-big", pool[0].coverUri)
        assertEquals(Explicitness.EXPLICIT, pool[0].explicitness)
    }

    @Test fun poolCapsAtTheTarget() {
        val hits = (1..(DjPool.TARGET + 5)).map { hit(it.toLong(), "Song $it", Explicitness.CLEAN) }
        assertEquals(DjPool.TARGET, DjPool.fromChart(hits, cleanOnly = false).size)
    }

    @Test fun cleanPoolKeepsOnlySongsKnownClean() {
        val hits = listOf(
            hit(5, "Stated clean", Explicitness.CLEAN),
            hit(6, "Stated explicit", Explicitness.EXPLICIT),
            hit(7, "Never judged", Explicitness.UNKNOWN),
            hit(8, "Also clean", Explicitness.CLEAN),
        )
        val pool = DjPool.fromChart(hits, cleanOnly = true)
        // The honesty rule: an UNKNOWN song is refused, exactly like an explicit
        // one — the app never promises clean what it does not know is clean.
        assertEquals(listOf("Stated clean", "Also clean"), pool.map { it.title })
        assertTrue(pool.all { it.explicitness == Explicitness.CLEAN })
    }

    // ── Sequencing (fabricated analyses, as the automix tests fabricate them) ──

    /** A drum loop at [bpm]: kick on 1 and 3 (low), snare on 2 and 4 (noise), hat on eighths, a bass note per bar. */
    private fun loop(bpm: Double, seconds: Double, offset: Double = 0.5, seed: Long = 1): FloatArray {
        val sr = Features.RATE
        val x = FloatArray((seconds * sr).toInt())
        val rnd = java.util.Random(seed)
        val beat = 60 / bpm
        var k = 0
        while (offset + k * beat / 2 < seconds) {
            val t0 = offset + k * beat / 2
            val i0 = (t0 * sr).toInt()
            val inBar = k % 8
            for (i in 0 until (0.25 * sr).toInt()) {
                if (i0 + i >= x.size) break
                val t = i.toDouble() / sr
                var v = 0.0
                if (inBar == 0 || inBar == 4) v += 0.8 * sin(2 * PI * (50 + 80 * exp(-t * 30)) * t) * exp(-t * 12)
                if (inBar == 2 || inBar == 6) v += 0.4 * (rnd.nextDouble() * 2 - 1) * exp(-t * 25)
                v += 0.08 * (rnd.nextDouble() * 2 - 1) * exp(-t * 80)
                x[i0 + i] += v.toFloat()
            }
            k++
        }
        val notes = doubleArrayOf(65.4, 87.3, 98.0, 65.4)
        for (i in x.indices) {
            val t = i.toDouble() / sr - offset
            if (t < 0) continue
            val bar = (t / (4 * beat)).toInt()
            x[i] += (0.15 * sin(2 * PI * notes[bar % 4] * t)).toFloat()
        }
        return x
    }

    /** [a] with a clear [key] (the loops' own keys are too weak to count). */
    private fun withKey(a: TrackAnalysis, key: Int) = TrackAnalysis(
        a.duration, a.contentStart, a.contentEnd, a.loudnessDb, key, 1.0, a.bpm,
        a.steady, a.head, a.tail, a.beats, a.barStarts, a.barDb, a.barLowDb, a.barHighDb, a.barCentre, a.barNovelty,
        a.phraseOffset, a.stereo, a.headDown, a.tailDown, a.note, a.barVocalRisk,
    )

    @Test fun transitionScorePrefersThePairThatBlends() {
        val a = Analyzer.analyze(loop(124.0, 90.0, seed = 1), null)
        val near = Analyzer.analyze(loop(122.0, 90.0, offset = 0.3, seed = 2), null)
        val far = Analyzer.analyze(loop(100.0, 90.0, seed = 3), null)
        // 124 into 122 blends on the planner's judgement; 124 into 100 cannot be
        // stretched into a blend at all, and must score below it.
        assertTrue(
            "near ${DjSequence.transitionScore(a, near)} far ${DjSequence.transitionScore(a, far)}",
            DjSequence.transitionScore(a, near) > DjSequence.transitionScore(a, far),
        )
        // Same tempos, a clashing key against a compatible one: the clash costs.
        val aKeyed = withKey(a, 0)                       // C major
        val compatible = withKey(near, 7)                // G major: the planner's distance 0
        val clash = withKey(near, 6)                     // F# major: the planner's distance 2
        assertTrue(
            DjSequence.transitionScore(aKeyed, compatible) > DjSequence.transitionScore(aKeyed, clash),
        )
    }

    @Test fun greedyOrderKeepsTheFlowingPairTogetherAndTheOddSongLast() {
        val x = Analyzer.analyze(loop(124.0, 90.0, seed = 1), null)
        val y = Analyzer.analyze(loop(122.0, 90.0, offset = 0.3, seed = 2), null)
        val z = Analyzer.analyze(loop(90.0, 90.0, seed = 3), null)
        val analyses = mapOf("x" to x, "y" to y, "z" to z)
        // Chart order puts the odd song first: the sequence must not.
        val r = DjSequence.order(listOf("z", "x", "y")) { analyses[it] }
        assertEquals(3, r.sequenced)
        assertEquals(0, r.appended)
        assertEquals("z", r.ordered.last())
        assertEquals(1, abs(r.ordered.indexOf("x") - r.ordered.indexOf("y")))
    }

    @Test fun unanalysedSongsAreAppendedInChartOrder() {
        val x = Analyzer.analyze(loop(124.0, 90.0, seed = 1), null)
        val y = Analyzer.analyze(loop(122.0, 90.0, offset = 0.3, seed = 2), null)
        val analyses = mapOf("x" to x, "y" to y)
        val r = DjSequence.order(listOf("x", "u1", "y", "u2")) { analyses[it] }
        assertEquals(2, r.sequenced)
        assertEquals(2, r.appended)
        // The analysed two lead (in whichever order they flow), the unanalysed
        // keep their chart order behind them — counted, never dropped.
        assertEquals(setOf("x", "y"), r.ordered.take(2).toSet())
        assertEquals(listOf("u1", "u2"), r.ordered.drop(2))
    }

    // ── The finished mix: construction + counts ──

    private fun song(id: Long, title: String) = DjSong(
        key = "deezer.$id", catalogUri = "deezer:track:$id", title = title, artist = "Band",
        album = "Album", coverUri = "", durationMs = 200_000, isrc = "", explicitness = Explicitness.CLEAN,
    )

    @Test fun arrangeCountsWhatWasSequencedAndWhatWasAppended() {
        val a = Analyzer.analyze(loop(124.0, 90.0, seed = 1), null)
        val b = Analyzer.analyze(loop(122.0, 90.0, offset = 0.3, seed = 2), null)
        val pool = listOf(song(1, "One"), song(2, "Two"), song(3, "Three"))
        val arranged = DjMixes.arrange(pool, mapOf("deezer.1" to a, "deezer.3" to b))
        assertEquals(DjSummary(total = 3, sequenced = 2, appended = 1), arranged.summary)
        assertEquals(3, arranged.ordered.size)
        assertEquals("Two", arranged.ordered.last().title)
        assertEquals(setOf("One", "Three"), arranged.ordered.take(2).map { it.title }.toSet())
        // Nothing analysed at all: the whole pool stands, in chart order, counted as appended.
        val bare = DjMixes.arrange(pool, emptyMap())
        assertEquals(pool, bare.ordered)
        assertEquals(DjSummary(total = 3, sequenced = 0, appended = 3), bare.summary)
    }

    // ── Names ──

    @Test fun namesAreKeptDistinct() {
        assertEquals("DJ Mix — Pop", DjNames.unique(emptyList(), "Pop", "Nov 27"))
        assertEquals("DJ Mix — Pop", DjNames.unique(listOf("DJ Mix — Rap"), "Pop", "Nov 27"))
        // A mix of this category is already there: the new one carries the date.
        assertEquals("DJ Mix — Pop · Nov 27", DjNames.unique(listOf("DJ Mix — Pop"), "Pop", "Nov 27"))
        // And a third one the same day is numbered (the match is case-insensitive).
        assertEquals(
            "DJ Mix — Pop · Nov 27 (2)",
            DjNames.unique(listOf("dj mix — pop", "DJ Mix — Pop · Nov 27"), "Pop", "Nov 27"),
        )
        assertEquals(
            "DJ Mix — Pop · Nov 27 (3)",
            DjNames.unique(listOf("DJ Mix — Pop", "DJ Mix — Pop · Nov 27", "DJ Mix — Pop · Nov 27 (2)"), "Pop", "Nov 27"),
        )
    }
}
