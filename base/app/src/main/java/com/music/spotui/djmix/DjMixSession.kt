/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.djmix

import app.myspoti.automix.TrackAnalysis
import app.myspoti.catalog.Deezer
import app.myspoti.catalog.HttpFetch
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.automix.AutoMix
import com.music.spotui.data.catalog.CatalogRuntime
import com.music.spotui.data.local.LocalPlaylists
import com.music.spotui.di.Graph
import com.music.spotui.extensions.ExtensionAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where a DJ Mix build stands; the dialog shows exactly this, nothing hidden. */
sealed interface DjMixState {
    data object LoadingCategories : DjMixState
    data class Categories(val categories: List<DjCategory>) : DjMixState
    data class FetchingPool(val category: DjCategory) : DjMixState
    data class Analyzing(val category: DjCategory, val done: Int, val total: Int) : DjMixState
    data class Sequencing(val category: DjCategory) : DjMixState
    data class Done(val playlistId: String, val name: String, val summary: DjSummary, val refusal: AutoMix.Reason? = null) : DjMixState
    data class Failed(val failure: DjFailure) : DjMixState
}

/** Why a build stopped, in terms the dialog can say plainly. */
sealed interface DjFailure {
    /** The catalog's genre listing could not be read (or names nothing the picker knows). */
    data object CategoriesUnavailable : DjFailure

    /** The category's chart could not be read. */
    data object ChartUnavailable : DjFailure

    /**
     * The pool came up short of a mix ([DjPool.MIN_SONGS]). Under the Clean Rap
     * rule [found] counts only songs KNOWN clean — never padded with unknowns.
     */
    data class TooFew(val category: DjCategory, val found: Int) : DjFailure
}

/**
 * One DJ Mix dialog's work: read the categories from the free catalog (Deezer's
 * public endpoints, no key, no account — the same catalogue the Home chart shelf
 * reads), then, for the picked category, read its chart, analyse the pool with
 * the app's own AutoMix engine (analyses are the engine's, cached by it and
 * reused), sequence by the planner's transition scores, and store the result as
 * a local playlist. Nothing in the path costs anything: no paid API, no key, no
 * subscription, no new account.
 *
 * Analysis goes through [AutoMix.request] in small batches (the engine analyses
 * a couple of songs ahead by design); a song whose audio cannot be analysed —
 * no source answers, the data saver refuses a stream, the analysis fails — is
 * not dropped: it is appended after the sequenced songs in chart order and
 * counted in the summary.
 */
class DjMixSession(private val scope: CoroutineScope) {
    val state = MutableStateFlow<DjMixState>(DjMixState.LoadingCategories)
    private var job: Job? = null

    private val deezer by lazy {
        Deezer(HttpFetch(MyspotiRuntime.apiHttp, CatalogRuntime.USER_AGENT, 0))
    }

    fun start() {
        job?.cancel()
        job = scope.launch {
            state.value = DjMixState.LoadingCategories
            val categories = runCatching { deezer.genres() }.getOrNull()
                ?.let { list -> DjCategories.fromGenres(list.map { DjGenre(it.id, it.name) }) }
            state.value = if (categories.isNullOrEmpty()) {
                DjMixState.Failed(DjFailure.CategoriesUnavailable)
            } else {
                DjMixState.Categories(categories)
            }
        }
    }

    fun pick(category: DjCategory) {
        job?.cancel()
        job = scope.launch { build(category) }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun build(category: DjCategory) {
        state.value = DjMixState.FetchingPool(category)
        val hits = runCatching {
            deezer.chartTracks(category.genreId, if (category.cleanOnly) CLEAN_CHART_LIMIT else CHART_LIMIT)
        }.getOrNull()
        if (hits == null) {
            state.value = DjMixState.Failed(DjFailure.ChartUnavailable)
            return
        }
        val pool = DjPool.fromChart(hits, category.cleanOnly)
        if (pool.size < DjPool.MIN_SONGS) {
            state.value = DjMixState.Failed(DjFailure.TooFew(category, pool.size))
            return
        }
        // The pool's songs as the app's own rows (catalog key, cover, explicitness),
        // the same rows a search shows and the pickers store.
        val modelByKey = hits.map { Graph.api.songOf(it.track) }.associateBy { it.spotifyTrackId }

        // ── Analysis, in the engine's own batches ──
        val analyses = HashMap<String, TrackAnalysis>()
        val askedTracks = ArrayList<com.music.spotui.data.local.LocalTrack>()
        state.value = DjMixState.Analyzing(category, 0, pool.size)
        val queue = ArrayDeque(pool)
        var done = 0
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val batch = ArrayList<DjSong>(ANALYSIS_BATCH)
            while (batch.size < ANALYSIS_BATCH && queue.isNotEmpty()) batch += queue.removeFirst()
            // Each song with the queue track the player itself would play it as:
            // its analysis is asked, and read back, under that track's address.
            val pairs = batch.mapNotNull { song -> modelByKey[song.key]?.let { song to ExtensionAudio.localTrack(it) } }
            askedTracks += pairs.map { it.second }
            if (pairs.isNotEmpty()) {
                AutoMix.request(pairs.map { it.second.uri }) {}
                // Settled = analysed or refused, counted per song as it lands, so the bar moves
                // with every song — a failed song moves it too. The engine settles every song
                // within its own deadline now; this batch deadline is only the backstop.
                val doneBefore = done
                SettleWait.await(
                    total = pairs.size,
                    settledCount = { pairs.count { (_, t) -> AutoMix.analysis(t.uri) != null || AutoMix.missing(t.uri) != null } },
                    deadlineMs = BATCH_DEADLINE_MS,
                    pollMs = POLL_MS,
                    onProgress = { state.value = DjMixState.Analyzing(category, doneBefore + it, pool.size) },
                )
                for ((song, t) in pairs) AutoMix.analysis(t.uri)?.let { analyses[song.key] = it }
            }
            done += batch.size
            state.value = DjMixState.Analyzing(category, done, pool.size)
        }

        // ── Sequencing by the planner's own transition scores ──
        state.value = DjMixState.Sequencing(category)
        val arranged = withContext(Dispatchers.Default) { DjMixes.arrange(pool, analyses) }

        // ── The playlist itself: a local playlist, stored like any other ──
        val name = DjNames.unique(
            LocalPlaylists.list().map { it.name }, category.label,
            SimpleDateFormat("MMM d", Locale.US).format(Date()),
        )
        // Created and filled as one step: a cancel landing between the two would
        // leave an empty playlist behind.
        val playlistId = withContext(Dispatchers.IO + NonCancellable) {
            val p = LocalPlaylists.create(name)
            LocalPlaylists.addAllSongs(p.id, arranged.ordered.mapNotNull { modelByKey[it.key] })
            p.id
        }
        // Why the unsequenced songs went unanalyzed, when one reason dominates them: the
        // Done screen names it (a batch that all waited on mobile data is not a mystery).
        val dominantRefusal = askedTracks
            .filter { AutoMix.analysis(it.uri) == null }
            .mapNotNull { AutoMix.missing(it.uri) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        state.value = DjMixState.Done(playlistId, name, arranged.summary, dominantRefusal)
    }

    private companion object {
        /** Songs asked of the chart: headroom over the pool's target for the unplayable and the duplicates. */
        const val CHART_LIMIT = 50

        /** The Clean Rap read asks wider: its pool keeps only the known-clean songs of the chart. */
        const val CLEAN_CHART_LIMIT = 100

        /** Songs per analysis ask: the engine's own lookahead is two (three while charging). */
        const val ANALYSIS_BATCH = 2

        /** How long one batch may take before its unanswered songs count as unanalysed. */
        const val BATCH_DEADLINE_MS = 120_000L
        const val POLL_MS = 250L
    }
}
