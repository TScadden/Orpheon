/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.djmix

import app.myspoti.automix.MixPlan
import app.myspoti.automix.MixPlanner
import app.myspoti.automix.TrackAnalysis
import kotlin.math.abs
import kotlin.math.min

/**
 * Sequencing a DJ Mix by real transitions. The score of one song following another
 * is the AutoMix planner's own judgement of that pair ([MixPlanner.plan] — the same
 * planner the local player mixes with): a beat-matched blend over bars scores
 * highest, a beat-aligned cut next, a filtered fade below that and a plain fade
 * last; the tempo stretch the blend needs and any harmonic clash the planner
 * measures ([MixPlanner.keyDistance]) pull the score down, and a longer blend
 * lifts it a little. The order is then greedy: the opener is the song with the
 * strongest single outgoing transition, and each next song is the remaining one
 * that flows best out of the song before it. Ties keep chart order, so the same
 * pool and analyses always build the same mix.
 *
 * Pure: analyses in, order out. Songs without an analysis are not scored at all —
 * they are appended after the sequenced core in their original (chart) order and
 * counted as [Result.appended], never silently dropped.
 */
object DjSequence {
    data class Result<T>(val ordered: List<T>, val sequenced: Int, val appended: Int)

    /** The planner's settings for judging a pair, the planner's own defaults. */
    private val SETTINGS = MixPlanner.Settings()

    /** How good the transition from [a] into [b] is, on the planner's judgement (higher flows better). */
    fun transitionScore(a: TrackAnalysis, b: TrackAnalysis): Double {
        val plan = MixPlanner.plan(a, b, SETTINGS)
        val style = when (plan.style) {
            MixPlan.Style.BLEND -> 1.0
            MixPlan.Style.CUT -> 0.55
            MixPlan.Style.FADE -> if (plan.filtered) 0.35 else 0.15
        }
        // The tempo change B is played at for the blend: 8 % costs 0.40.
        val stretch = min(0.40, abs(plan.speedB - 1.0) * 5.0)
        // The planner's harmonic distance: 0 (compatible), 1 (near), 2 (clash).
        val keys = MixPlanner.keyDistance(a, b) * 0.15
        // A longer synced blend is a smoother ride, worth a little on top.
        val length = if (plan.style == MixPlan.Style.BLEND) min(0.10, plan.beats / 160.0) else 0.0
        return (style - stretch - keys + length).coerceIn(0.0, 1.2)
    }

    /**
     * [items] in mix order: the ones [analysis] answers for, sequenced by
     * [transitionScore]; the ones it does not, appended after in their given
     * order. [Result.sequenced] and [Result.appended] count the two groups.
     */
    fun <T> order(items: List<T>, analysis: (T) -> TrackAnalysis?): Result<T> {
        val analysed = ArrayList<Pair<T, TrackAnalysis>>()
        val missing = ArrayList<T>()
        for (item in items) {
            val a = analysis(item)
            if (a != null) analysed += item to a else missing += item
        }
        val n = analysed.size
        if (n <= 1) return Result(analysed.map { it.first } + missing, n, missing.size)
        // Every pair's score, computed once: score[i][j] is analysed[i] into analysed[j].
        val score = Array(n) { i ->
            DoubleArray(n) { j -> if (i == j) 0.0 else transitionScore(analysed[i].second, analysed[j].second) }
        }
        // The opener: the strongest single outgoing transition in the pool.
        var opener = 0
        var openerBest = Double.NEGATIVE_INFINITY
        for (i in 0 until n) {
            var best = Double.NEGATIVE_INFINITY
            for (j in 0 until n) if (j != i) best = maxOf(best, score[i][j])
            if (best > openerBest + EPS) {
                openerBest = best
                opener = i
            }
        }
        val used = BooleanArray(n)
        val picked = ArrayList<Int>(n)
        var cur = opener
        used[cur] = true
        picked += cur
        while (picked.size < n) {
            var next = -1
            var nextScore = Double.NEGATIVE_INFINITY
            for (j in 0 until n) {
                if (used[j]) continue
                if (score[cur][j] > nextScore + EPS) {
                    nextScore = score[cur][j]
                    next = j
                }
            }
            cur = next
            used[cur] = true
            picked += cur
        }
        return Result(picked.map { analysed[it].first } + missing, n, missing.size)
    }

    /** Scores closer than this are a tie, settled by chart order. */
    private const val EPS = 1e-9
}
