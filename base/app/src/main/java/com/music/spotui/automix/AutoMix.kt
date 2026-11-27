/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.myspoti.automix.Analyzer
import app.myspoti.automix.BeatThis
import app.myspoti.automix.MixPlan
import app.myspoti.automix.MixPlanner
import app.myspoti.automix.TrackAnalysis
import app.myspoti.automix.DurationBounds
import com.music.spotui.di.Graph
import com.music.spotui.extensions.ExtensionAudio
import java.io.File
import java.security.MessageDigest

/**
 * AutoMix: DJ-style transitions between the local player's songs (extension streams, downloads,
 * the phone's files). Spotify songs play in the Web Player, whose audio the app never has.
 *
 * Each song is analysed once in the background (see [Analyzer]) and the result kept in the cache;
 * [planFor] gives the transition between two analysed songs. LocalPlayer plays it (see its AutoMix part).
 */
object AutoMix {
    private const val PREFS = "myspoti_prefs"
    private const val KEY = "automix_on"
    private const val KEY_DURATION_MIN = "automix_duration_min_seconds"
    private const val KEY_DURATION_MAX = "automix_duration_max_seconds"
    private const val KEY_PERFORMANCE_PRIORITY = "automix_performance_priority"

    private fun loadDurationPair(): Pair<Int, Int> {
        val p = Graph.app.getSharedPreferences(PREFS, 0)
        val rawMin = runCatching { p.getInt(KEY_DURATION_MIN, 0) }.getOrDefault(-1)
        val rawMax = runCatching { p.getInt(KEY_DURATION_MAX, 0) }.getOrDefault(-1)
        val pair = normalizeDurationPair(rawMin, rawMax)
        if (pair.first != rawMin || pair.second != rawMax) {
            p.edit().putInt(KEY_DURATION_MIN, pair.first).putInt(KEY_DURATION_MAX, pair.second).apply()
        }
        return pair
    }
    private val initialDurationPair = loadDurationPair()

    /** The setting, Compose state (off by default). */
    var on by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean(KEY, false))
        private set

    fun set(value: Boolean) {
        on = value
        if (!value) cancelRequests()
        Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY, value).apply()
        // The Spotify songs' fade follows: a short one with AutoMix on (see TransitionPref).
        com.music.spotui.data.preferences.applyCrossfade(com.music.spotui.data.preferences.CrossfadePref.seconds)
        journal("AutoMix ${if (value) "on" else "off"}")
    }

    /**
     * What Next does (see LocalPlayer.mixOnSkip): 0 cuts, 1 a quick mix from the next downbeat (B from
     * its start), 2 the real mix (A out on a downbeat or phrase within 4 s, B in at its best entry,
     * 4 bars with the bass swap). The real mix by default; "Mix on Next" turned off before stays a cut.
     */
    var nextStyle by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).let { p ->
        if (p.contains("automix_next_style")) p.getInt("automix_next_style", 2).coerceIn(0, 2)
        else if (!p.getBoolean("automix_on_next", true)) 0 else 2
    })
        private set
    fun changeNextStyle(value: Int) { nextStyle = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putInt("automix_next_style", value).apply() }
    /** Next mixes at all. */
    val mixOnNext: Boolean get() = nextStyle != 0

    /** No mix between two songs that follow each other on their album. On by default. */
    var albumOrder by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_album_order", true))
        private set
    fun changeAlbumOrder(value: Boolean) { albumOrder = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_album_order", value).apply(); com.music.spotui.di.LocalPlayer.autoMixSettingsChanged() }

    /** The mix shown in the player: its stretch on the seek bar, the cover and its glow pulsing with it. On by default. */
    var showInPlayer by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_show", true))
        private set
    /** Mix length: 0 short, 1 as the music says, 2 long. */
    var mixLength by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getInt("automix_length", 1).coerceIn(0, 2))
        private set
    fun changeMixLength(value: Int) { mixLength = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putInt("automix_length", value).apply(); com.music.spotui.di.LocalPlayer.autoMixSettingsChanged() }

    /** Optional hard bounds on overlap seconds; minimum 0 is automatic and maximum 0 is unlimited. */
    var minDurationSeconds by mutableStateOf(initialDurationPair.first)
        private set
    var maxDurationSeconds by mutableStateOf(initialDurationPair.second)
        private set

    /** Updates the pair atomically so a temporary min > max can never reach the planner. */
    fun changeDurationBounds(minSeconds: Int, maxSeconds: Int): Boolean {
        if (normalizeDurationPair(minSeconds, maxSeconds) != (minSeconds to maxSeconds)) return false
        minDurationSeconds = minSeconds
        maxDurationSeconds = maxSeconds
        Graph.app.getSharedPreferences(PREFS, 0).edit()
            .putInt(KEY_DURATION_MIN, minSeconds).putInt(KEY_DURATION_MAX, maxSeconds).apply()
        com.music.spotui.di.LocalPlayer.autoMixSettingsChanged()
        return true
    }

    /** Same planner and signal path, with one analysis thread per song and no third-song prefetch. */
    var performancePriority by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean(KEY_PERFORMANCE_PRIORITY, false))
        private set
    fun changePerformancePriority(value: Boolean) {
        performancePriority = value
        Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_PERFORMANCE_PRIORITY, value).apply()
        com.music.spotui.di.LocalPlayer.autoMixSettingsChanged()
    }

    /** A DJ session may leave at an earlier musical section; independent of overlap length. */
    var sessionDj by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_session_dj", true))
        private set
    fun changeSessionDj(value: Boolean) { sessionDj = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_session_dj", value).apply(); com.music.spotui.di.LocalPlayer.autoMixSettingsChanged() }

    /** How far the next song's tempo may be bent to match: 0 strict (4 %), 1 normal (8 %), 2 wide (12 %). */
    var tempoRange by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getInt("automix_tempo", 1).coerceIn(0, 2))
        private set
    fun changeTempoRange(value: Int) { tempoRange = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putInt("automix_tempo", value).apply(); com.music.spotui.di.LocalPlayer.autoMixSettingsChanged() }

    /** The DJ EQ: the bass swap and the filters (each player's DynamicsProcessing); off, only the volumes move. */
    var djEq by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_eq", true))
        private set
    fun changeDjEq(value: Boolean) { djEq = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_eq", value).apply(); com.music.spotui.di.LocalPlayer.autoMixEqChanged() }

    /** How much the player moves with the mix: 0 not at all (the stretch on the bar stays), 1 subtle, 2 strong. */
    var pulseLevel by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getInt("automix_pulse", 2).coerceIn(0, 2))
        private set
    fun changePulseLevel(value: Int) { pulseLevel = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putInt("automix_pulse", value).apply() }

    /**
     * The transition style: 0 beat blend (beat-matched where both grids allow, else a cut on the beat
     * or a fade), 1 always a cut on A's downbeat (a fade when A has no grid), 2 always a plain fade.
     */
    var style by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getInt("automix_style", 0).coerceIn(0, 2))
        private set
    fun changeStyle(value: Int) { style = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putInt("automix_style", value).apply(); com.music.spotui.di.LocalPlayer.autoMixSettingsChanged() }

    /** Harmonic mixing: the songs' keys weigh strongly in the choice of the mix; off, they are ignored. On by default. */
    var harmonic by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_harmonic", true))
        private set
    fun changeHarmonic(value: Boolean) { harmonic = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_harmonic", value).apply(); com.music.spotui.di.LocalPlayer.autoMixSettingsChanged() }

    /** Podcast episodes and audiobooks mixed like songs; off (the default), they start and end whole. */
    var mixPodcasts by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_podcasts", false))
        private set
    fun changeMixPodcasts(value: Boolean) { mixPodcasts = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_podcasts", value).apply(); com.music.spotui.di.LocalPlayer.autoMixSettingsChanged() }

    /** Extension streams analysed on mobile data too (each is read a second time: about the song's size again). On by default: off, songs heard on mobile data are not mixed. */
    var analyzeOnMobileData by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_mobile_data", true))
        private set
    fun changeAnalyzeOnMobileData(value: Boolean) {
        analyzeOnMobileData = value
        Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_mobile_data", value).apply()
        // The songs refused on mobile data are asked again.
        if (value) synchronized(memory) { refused.values.removeAll { it == Reason.MobileData } }
        com.music.spotui.di.LocalPlayer.autoMixSettingsChanged()
    }

    /**
     * The planner's settings from the ones above. Mix length in bars: short 4–8, balanced 8–16 (as
     * the music says), long 12–32 (up to 90 seconds); the lower end is a preference (a shorter mix only
     * when no longer one fits the songs), the upper one a limit. A plain fade lasts 4, 8 or 14 s.
     */
    private fun configuredDurationBounds() = DurationBounds(
        minSeconds = minDurationSeconds.toDouble(),
        maxSeconds = if (maxDurationSeconds == 0) Double.POSITIVE_INFINITY else maxDurationSeconds.toDouble(),
    )

    fun settings(playbackRate: Double = com.music.spotui.di.LocalPlayer.rate().toDouble()) = MixPlanner.Settings(
        maxStretch = doubleArrayOf(0.04, 0.12, 0.16)[tempoRange],
        maxSec = if (mixLength == 2) 90.0 else 48.0,
        lengthBias = mixLength - 1,
        style = arrayOf(MixPlan.Style.BLEND, MixPlan.Style.CUT, MixPlan.Style.FADE)[style],
        minBars = intArrayOf(4, 8, 12)[mixLength],
        maxBars = intArrayOf(8, 16, 32)[mixLength],
        keyWeight = if (harmonic) 2.0 else 0.0,
        smoothSec = doubleArrayOf(4.0, 8.0, 14.0)[mixLength],
        filterSec = doubleArrayOf(6.0, 10.0, 14.0)[mixLength],
        djMode = sessionDj,
        durationBounds = configuredDurationBounds().forPlaybackRate(playbackRate),
    )

    /** The data-plan transitions, in place of the planner; only for the beat blend style. On unless the listener turned them off. */
    var segue by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_segue", true))
        private set
    fun changeSegue(value: Boolean) { segue = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_segue", value).apply(); com.music.spotui.di.LocalPlayer.autoMixSettingsChanged() }
    val segueActive: Boolean get() = segue && style == 0

    /** The Orpheon planner (on by default), else the compact one it replaces, kept as a way back. */
    var orpheon by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_orpheon", true))
        private set
    fun changeOrpheon(value: Boolean) { orpheon = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_orpheon", value).apply(); com.music.spotui.di.LocalPlayer.autoMixSettingsChanged() }

    /** The [app.myspoti.automix.segue.MixIdea]s the Orpheon planner plays with, as their bits: each is one switch of the Mix Lab. */
    var ideas by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getInt("automix_ideas",
        if (app.myspoti.engine.BuildConfig.APPLICATION_ID == "app.myspoti.automixlab") app.myspoti.automix.segue.MixIdea.DEFAULT else 0))
        private set
    fun hasIdea(idea: app.myspoti.automix.segue.MixIdea) = ideas and idea.bit != 0
    fun changeIdea(idea: app.myspoti.automix.segue.MixIdea, on: Boolean) {
        ideas = if (on) ideas or idea.bit else ideas and idea.bit.inv()
        Graph.app.getSharedPreferences(PREFS, 0).edit().putInt("automix_ideas", ideas).apply()
        com.music.spotui.di.LocalPlayer.autoMixSettingsChanged()
    }

    /** The tempo gap the Orpheon planner may close when the listener asks for the narrowest range. */
    private const val ORPHEON_NARROW_TEMPO = 0.06

    /** The policy's decision for two analysed songs. The listener's settings may only tighten its limits. */
    fun decide(a: TrackAnalysis, b: TrackAnalysis, earliestExit: Double, sameAlbum: Boolean, listener: Float,
               genreA: app.myspoti.automix.segue.GenreFamily? = null, genreB: app.myspoti.automix.segue.GenreFamily? = null): app.myspoti.automix.segue.SegueDecision {
        val s = settings()
        val d = app.myspoti.automix.segue.SeguePolicy.decide(app.myspoti.automix.segue.SegueRequest(a, b, (earliestExit * 1e6).toLong(),
            listenerRate = (listener * app.myspoti.automix.segue.SegueFormat.RATE_NOMINAL).toLong(),
            // The Orpheon planner ramps both songs and keeps its own tempo limits (see OrpheonTuning): the
            // listener's range, made for one song held at a fixed speed, only narrows it at its smallest.
            maxRatePpm = if (orpheon && tempoRange > 0) null else ((if (orpheon) ORPHEON_NARROW_TEMPO else s.maxStretch) * 1e6).toLong(),
            maxBars = s.maxBars, eq = djEq, harmonic = harmonic, sameAlbum = sameAlbum,
            durationBounds = configuredDurationBounds(), genreA = genreA, genreB = genreB,
            minBars = s.minBars, fadeSec = doubleArrayOf(6.0, 10.0, 14.0)[mixLength],
            tuning = app.myspoti.automix.segue.SegueTuning(planner = if (orpheon) app.myspoti.automix.segue.SegueTuning.PLANNER_ORPHEON else app.myspoti.automix.segue.SegueTuning.PLANNER_COMPACT,
                ideas = if (orpheon) ideas else 0)))
        val plan = d.plan ?: return d
        if (app.myspoti.automix.segue.SegueDeck.playable(plan)) return d
        return app.myspoti.automix.segue.SegueDecision(app.myspoti.automix.segue.SegueLevel.NORMAL, null,
            d.attempts + app.myspoti.automix.segue.SegueAttempt(d.level, app.myspoti.automix.segue.SegueCause.INVALID_PLAN, "the engine cannot play it"))
    }

    // ── Why a song is not mixed (for the screens) ──

    /** Why the next transition is not an AutoMix one. */
    enum class Reason {
        /** AutoMix is off. */
        Off,
        /** A Spotify song: it plays in the Web Player, whose audio the app never has. */
        SpotifyStream,
        /** The songs play on a Cast device. */
        Cast,
        /** A podcast episode or an audiobook, with "Mix podcasts" off. */
        Podcast,
        /** Two songs that follow each other on their album, with "Keep albums whole" on. */
        Album,
        /** A stream not analysed on mobile data ("Analyze on mobile data" off, or the data saver). */
        MobileData,
        /** Not analysed in time while saving energy (the phone's saver, the app's, or a low battery). */
        Frugal,
        /** Not analysed in time, or the analysis failed: the songs change with a plain fade. */
        NotAnalyzed,
    }

    /** The detail of a [Reason.NotAnalyzed] refusal: what actually happened to the song. */
    enum class RefusalDetail {
        /** Its stream stopped arriving and the stall bound settled it. */
        Stalled,

        /** It kept arriving but its own work ran past the hard bound. */
        TooLong,

        /** No source could fetch a playable stream for it. */
        NoStream,

        /** The fetch or the analysis itself failed (the journal holds the exception). */
        SourceError,
    }

    /** The reason LocalPlayer found for the next pair (null: it is mixed, or still being analysed). */
    internal val pairReason = mutableStateOf<Reason?>(null)

    /**
     * Why the song playing does not mix into the next one, null when it does (or will, once
     * analysed). Compose state: a screen reading it redraws when it changes.
     */
    fun whyNot(): Reason? = when {
        !on -> Reason.Off
        com.music.spotui.cast.CastPlayback.device.value != null || com.music.spotui.cast.CastPlayback.active -> Reason.Cast
        !com.music.spotui.di.LocalPlayer.active.value -> Reason.SpotifyStream
        else -> pairReason.value
    }

    /** Why [uri] has no analysis and will get none for now (null: ready, or under way). */
    fun missing(uri: Uri): Reason? = synchronized(memory) { refused[requestId(uri)] }

    /** Why [uri]'s refusal happened, in detail (null: not refused, or no detail recorded). */
    fun missingDetail(uri: Uri): RefusalDetail? = synchronized(memory) { refusalDetail[requestId(uri)] }

    /**
     * How far [uri]'s analysis has got, 0..1 — from the decoder's and the analyser's own
     * counts, never from elapsed time. Null while the song's total is genuinely unknown
     * (a stream that states neither a duration nor a size): the screens stay indeterminate
     * rather than show a made-up number. A song with an analysis, or one that has settled
     * refused, reads 1.
     */
    fun progressOf(uri: Uri): Float? {
        val id = requestId(uri)
        val state = synchronized(progressStates) { progressStates[id] }
            ?: return if (analysis(uri) != null) 1f else null
        val settled = analysis(uri) != null || synchronized(memory) { refused.containsKey(id) }
        return AnalysisProgress.song(state.decode, state.analyze, settled)
    }

    /** Forgets a song's refusal and its retry backoff: an explicit retry starts it fresh. */
    fun forgetRefusal(uri: Uri) {
        val id = requestId(uri)
        synchronized(memory) { refused -= id; refusalDetail -= id; retry.forgiven(key(uri)) }
    }

    fun changeShowInPlayer(value: Boolean) { showInPlayer = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_show", value).apply() }

    // ── Journal (last lines, for the command line and the stats sheet) ──

    private val lines = ArrayDeque<String>()
    /** Immutable event snapshot for Compose readers; updated only when a journal event arrives. */
    var journalSnapshot by mutableStateOf<List<String>>(emptyList())
        private set
    fun journal(line: String) {
        app.myspoti.host.android.Logbook.log("automix: $line")
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > 60) lines.removeFirst()
            journalSnapshot = lines.toList()
        }
    }
    fun journalLines(): List<String> = synchronized(lines) { lines.toList() }

    // ── Analysis ──

    /**
     * The analysis workers (see [AnalysisPool]): two, with editor priority. Through v31 one
     * worker ran every song's fetch, decode and analysis back to back, so a song's wall time
     * was its own chain plus every song queued ahead of it; two workers overlap one song's
     * fetch+decode with another's analysis. The wedge protection was never the worker count —
     * every song still carries its own [AnalysisDeadline].
     */
    private val pool = AnalysisPool(2)

    /** Analyses running right now: the analyser's thread budget is split between them. */
    private val activeAnalyses = java.util.concurrent.atomic.AtomicInteger()

    /** One song's live fractions, written by its decode and its analysis as they run. */
    private class SongProgress {
        @Volatile var decode: Float? = null
        @Volatile var analyze: Float = 0f
    }

    /** Live per-song progress for the screens' percentages; bounded, eldest dropped. */
    private val progressStates = object : LinkedHashMap<String, SongProgress>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SongProgress>?): Boolean = size > 32
    }

    private fun progressState(id: String): SongProgress =
        synchronized(progressStates) { progressStates.getOrPut(id) { SongProgress() } }

    /** The decoder's own position, recorded as the song's decode fraction (duration first, else bytes against the stream's size). */
    private fun noteDecodeProgress(id: String, bytes: Long, totalBytes: Long, decodedSec: Double, durationSec: Double) {
        val frac = when {
            durationSec > 0 -> (decodedSec / durationSec).toFloat()
            totalBytes > 0 -> (bytes.toDouble() / totalBytes).toFloat()
            else -> return
        }
        val state = progressState(id)
        val clamped = frac.coerceIn(0f, 1f)
        val current = state.decode
        if (current == null || clamped > current) state.decode = clamped
    }
    private val memory = object : LinkedHashMap<String, TrackAnalysis>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackAnalysis>?) = size > 24
    }
    private val asked = HashSet<String>()
    private val retry = AnalysisRetry()
    private val fileVersions = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val streamKeys = ArrayDeque<Pair<app.myspoti.extensions.AudioStream, String>>()
    private val baseKeys = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var cacheEpoch = 0L
    /** Songs without an analysis for now, and why (shown through [missing]). */
    private val refused = HashMap<String, Reason>()

    /** Why each [Reason.NotAnalyzed] refusal happened, guarded by the same lock as [refused]. */
    private val refusalDetail = HashMap<String, RefusalDetail>()
    @Volatile private var wanted: Set<String> = emptySet()
    /** Waveform peaks by analysis key (the Mix editor's drawing data), kept with the analyses. */
    private val peaksMemory = object : LinkedHashMap<String, FloatArray>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 24
    }
    /** The Mix editor's asks under way, so a second ask of the same song adds no second decode. */
    private val editorAsked = HashSet<String>()

    /** The analysis of [uri] if it is ready (memory or cache), else null. */
    fun analysis(uri: Uri): TrackAnalysis? {
        val key = key(uri)
        synchronized(memory) { memory[key]?.let { return it } }
        return null
    }

    /**
     * Asks for the analysis of [uris] (the song playing and the next ones), dropping the asks for
     * any other song; [ready] runs on the worker when one is done.
     */
    fun request(uris: List<Uri>, ready: () -> Unit) {
        // The song playing and the next one; the one after only while charging (skipped or
        // replaced before it plays, it was often analysed for nothing: about 10 J a song).
        // Cached answers for B/C are loaded before A's expensive analysis.
        val lookahead = AnalysisCache.lookahead(charging = charging(), frugal = frugal())
        val songs = uris.distinctBy(::requestId).take(if (performancePriority) minOf(lookahead, 2) else lookahead)
        wanted = songs.map(::requestId).toSet()
        val epoch = cacheEpoch
        val jobs = songs.filter { uri ->
            val id = requestId(uri)
            synchronized(memory) { retry.allowed(key(uri), android.os.SystemClock.elapsedRealtime()) && asked.add(id) }
        }
        // One task per song on the pool: a cached song answers as soon as a worker is free,
        // and one song's slow fetch no longer holds the songs behind it — the next song's
        // fetch+decode overlaps this one's analysis on the other worker.
        for (uri in jobs) {
            pool.submit(AnalysisPool.PLAYER) {
                val id = requestId(uri)
                try {
                    if (epoch != cacheEpoch || id !in wanted) return@submit
                    refreshFileVersion(uri)
                    prepareStreamKey(uri)
                    val k = key(uri)
                    if (synchronized(memory) { memory.containsKey(k) }) return@submit
                    val cached = if (uri.scheme != ExtensionAudio.SCHEME || ExtensionAudio.streamOf(uri.lastPathSegment.orEmpty()) != null) load(k) else null
                    if (cached != null) {
                        synchronized(memory) { memory[k] = cached; refused -= id; refusalDetail -= id }
                        ready()
                        return@submit
                    }
                    waitForThePage()
                    if (epoch != cacheEpoch || id !in wanted) return@submit
                    val result = analyse(uri, epoch)
                    if (result != null && epoch == cacheEpoch && id in wanted && result.first == key(uri)) {
                        synchronized(memory) { memory[result.first] = result.second; refused -= id; refusalDetail -= id; retry.succeeded(result.first) }
                        ready()
                    }
                } finally { synchronized(memory) { asked -= id } }
            }
        }
    }

    /**
     * How long one song's own work may go without progress before the song counts as stalled
     * (see [AnalysisDeadline]). It must clear the slowest legitimate gap in a song's own work:
     * an extension's resolve is budgeted 15 s by its hook (plus grace), during which no byte
     * has yet arrived to count as progress.
     */
    private const val SONG_STALL_MS = 25_000L

    /** The hard bound on one song's own work, however steadily it progresses (see [AnalysisDeadline]). */
    private const val SONG_LIMIT_MS = 240_000L

    /**
     * The Mix editor's ask: the analysis AND the waveform peaks of each of [uris], whatever the
     * player is doing — these songs are not the player's `wanted`, and this ask neither drops nor
     * is dropped by the player's own. [settled] runs on the worker once per song, analysed or
     * failed: a song that cannot be analysed settles (its refusal readable through [missing])
     * within its deadline, never as an endless wait.
     */
    fun requestEditor(uris: List<Uri>, settled: () -> Unit) {
        val songs = uris.distinctBy(::requestId)
        if (songs.isEmpty()) return
        val fresh = synchronized(memory) { songs.filter { editorAsked.add(requestId(it)) } }
        if (fresh.isEmpty()) return
        val epoch = cacheEpoch
        // One task per song, at editor priority: the pair is analysed side by side, ahead of
        // any queued background asks, and every song settles its callback — analysed, refused,
        // or dropped by a cache clear alike (the old single runnable skipped the remaining
        // settles when a cache clear landed mid-pair).
        for (uri in fresh) {
            pool.submit(AnalysisPool.EDITOR) {
                try {
                    if (epoch == cacheEpoch) {
                        waitForThePage()
                        if (epoch == cacheEpoch) runCatching { editorSong(uri, epoch) }
                    }
                } finally {
                    synchronized(memory) { editorAsked -= requestId(uri) }
                    settled()
                }
            }
        }
    }

    /** One editor song: the full analysis (which now also captures peaks), or peaks alone when the analysis is already kept. */
    private fun editorSong(uri: Uri, epoch: Long) {
        val id = requestId(uri)
        refreshFileVersion(uri)
        prepareStreamKey(uri)
        val k = key(uri)
        var haveAnalysis = synchronized(memory) { memory.containsKey(k) }
        if (!haveAnalysis && (uri.scheme != ExtensionAudio.SCHEME || ExtensionAudio.streamOf(uri.lastPathSegment.orEmpty()) != null)) {
            load(k)?.let { synchronized(memory) { memory[k] = it; refused -= id; refusalDetail -= id }; haveAnalysis = true }
        }
        if (haveAnalysis) {
            if (synchronized(memory) { peaksMemory.containsKey(k) } || loadPeaks(k) != null) return
            // The analysis predates waveform peaks: one decode for the peaks alone.
            decodePeaks(uri, epoch)
            return
        }
        val result = analyse(uri, epoch, owned = false)
        if (result != null && epoch == cacheEpoch) {
            synchronized(memory) { memory[result.first] = result.second; refused -= id; refusalDetail -= id; retry.succeeded(result.first) }
        }
    }

    /**
     * The peaks alone (a song analysed before peaks were kept); the analysis is untouched.
     * The fast path ([TrackDecoder.decodePeaks]) gathers the envelope at the audio's own
     * rate — no resampled signals, no second pass — under the same watchdog as an analysis.
     */
    private fun decodePeaks(uri: Uri, epoch: Long) {
        var cacheKey = key(uri)
        val id = requestId(uri)
        val work = java.util.concurrent.atomic.AtomicLong()
        val deadline = AnalysisDeadline(SONG_LIMIT_MS, SONG_STALL_MS, progress = { work.get() })
        val cancelled = deadline.cancelled { epoch != cacheEpoch }
        try {
            if (uri.scheme == ExtensionAudio.SCHEME) {
                if (ExtensionAudio.resolve(uri, ahead = true) == null) return
                prepareStreamKey(uri)
                cacheKey = key(uri)
            }
            if (cancelled()) return
            val peaks = TrackDecoder.decodePeaks(uri, cancelled) { bytes, total, sec, dur ->
                work.set(bytes)
                noteDecodeProgress(id, bytes, total, sec, dur)
            }
            savePeaks(cacheKey, peaks)
            journal("peaks captured for ${label(uri)}")
        } catch (e: Exception) {
            if (!cancelled() && e !is InterruptedException) journal("peaks deferred for ${label(uri)}: ${e.javaClass.simpleName}")
        }
    }

    /** The waveform peaks of [uri]'s song, if captured (memory; [requestEditor] loads them). */
    fun peaks(uri: Uri): FloatArray? = synchronized(memory) { peaksMemory[key(uri)] }

    private fun loadPeaks(key: String): FloatArray? = runCatching {
        File(dir(), "$key.peaks").takeIf { it.exists() }?.let { f ->
            f.setLastModified(System.currentTimeMillis())
            WaveformPeaks.decode(f.readBytes())?.also { synchronized(memory) { peaksMemory[key] = it } }
        }
    }.getOrNull()

    private fun savePeaks(key: String, peaks: FloatArray) {
        synchronized(memory) { peaksMemory[key] = peaks }
        runCatching {
            val tmp = File(dir(), "$key.peaks.tmp")
            tmp.writeBytes(WaveformPeaks.encode(peaks))
            tmp.renameTo(File(dir(), "$key.peaks"))
        }
    }

    @Volatile private var pageWaited = false

    /**
     * A cold start: the player's page loads first. A mix is planned long before it is needed, and two
     * songs decoded at once with it took the page from about 3 s to about 20 s on the test phone.
     */
    private fun waitForThePage() {
        if (pageWaited) return
        val end = android.os.SystemClock.elapsedRealtime() + PAGE_WAIT_MS
        while (android.os.SystemClock.elapsedRealtime() < end && !runCatching { app.myspoti.host.android.MyspotiRuntime.api.ready.value }.getOrDefault(false)) Thread.sleep(250)
        pageWaited = true
    }

    private const val PAGE_WAIT_MS = 25_000L

    /**
     * One song's analysis, bounded by [AnalysisDeadline] as a progress watchdog on the song's
     * OWN work: the clock starts here, when the song's task starts — never while it waits in
     * the pool behind other songs — and it is fed by the work itself (bytes decoded, analysis
     * frames completed). A stream that stops arriving settles the song as refused
     * ([Reason.NotAnalyzed], detail [RefusalDetail.Stalled]) instead of holding a worker
     * forever; a song that keeps arriving, however slowly, is analysed even past the old flat
     * 45 s, up to the hard bound. [owned]: the player's ask, cancelled when the song leaves
     * [wanted]; the Mix editor's asks (owned = false) are cancelled only by a cache clear.
     */
    private fun analyse(uri: Uri, epoch: Long, owned: Boolean = true): Pair<String, TrackAnalysis>? {
        val id = requestId(uri)
        val work = java.util.concurrent.atomic.AtomicLong()
        val deadline = AnalysisDeadline(SONG_LIMIT_MS, SONG_STALL_MS, progress = { work.get() })
        val cancelled = deadline.cancelled { epoch != cacheEpoch || (owned && id !in wanted) }
        var cacheKey = key(uri)
        val state = progressState(id)
        state.decode = null; state.analyze = 0f
        // The deadline itself ended this song: record it as refused, with the detail of which
        // bound fired, so whoever waits on it (DJ Mix's batches, the Mix editor) sees it settle
        // instead of spinning — and can say why.
        fun settleExpired(): Boolean {
            if (!deadline.expired || epoch != cacheEpoch || (owned && id !in wanted)) return false
            synchronized(memory) {
                retry.failed(cacheKey, android.os.SystemClock.elapsedRealtime()); refused[id] = Reason.NotAnalyzed
                refusalDetail[id] = if (deadline.stalled) RefusalDetail.Stalled else RefusalDetail.TooLong
            }
            journal(
                if (deadline.stalled) "analysis stalled for ${label(uri)}: no progress for ${SONG_STALL_MS / 1000} s"
                else "analysis timed out for ${label(uri)} after ${SONG_LIMIT_MS / 1000} s of its own work",
            )
            return true
        }
        val active = activeAnalyses.incrementAndGet()
        try {
            if (uri.scheme == ExtensionAudio.SCHEME) {
                val saver = com.music.spotui.data.DataSaver.active
                if (com.music.spotui.data.DataSaver.metered && (saver || !analyzeOnMobileData)) {
                    synchronized(memory) { refused[id] = Reason.MobileData }
                    return null
                }
                if (ExtensionAudio.resolve(uri, ahead = true) == null) {
                    synchronized(memory) {
                        retry.failed(cacheKey, android.os.SystemClock.elapsedRealtime()); refused[id] = Reason.NotAnalyzed
                        refusalDetail[id] = RefusalDetail.NoStream
                    }
                    journal("no stream for ${label(uri)}")
                    return null
                }
                prepareStreamKey(uri)
                cacheKey = key(uri)
                // A source had not answered at the cache pass: its old analysis may now be usable.
                load(cacheKey)?.let { return cacheKey to it }
            }
            if (cancelled()) { settleExpired(); return null }
            val t0 = System.currentTimeMillis()
            val pcm = TrackDecoder.decode(uri, cancelled) { bytes, total, sec, dur ->
                work.set(bytes)
                noteDecodeProgress(id, bytes, total, sec, dur)
            }
            val t1 = System.currentTimeMillis()
            // On battery 3: measured on the S24, 5 made the same song's analysis slower (7.6 s against 5.5 s),
            // the extra cores being small ones and the next song's decode running meanwhile. The budget
            // is also split between the songs analysing at once, so two at a time cost about one.
            val base = if (performancePriority) 1 else if (frugal()) 2 else if (charging()) THREADS else minOf(THREADS, 3)
            val threads = ((base + active - 1) / active).coerceAtLeast(1)
            var lastStep = 0f
            val a = Analyzer.analyze(pcm.mid, pcm.side, model(), cancelled, threads, pcm.length) { f ->
                state.analyze = maxOf(state.analyze, f)
                if (f - lastStep >= 0.05f) { lastStep = f; work.incrementAndGet() }
            }
            if (cancelled() || cacheKey != key(uri)) { settleExpired(); return null }
            save(cacheKey, a)
            // The editor's waveform, from this same decode (one pass over the signals, no second read).
            savePeaks(cacheKey, WaveformPeaks.compute(pcm.mid, pcm.side, pcm.length))
            journal(String.format("analysed %s: %.1f BPM%s, %d bars, key %d, decode %d ms (bytes %d ms, %.1f MB, convert %d ms, %d restarts), analysis %d ms on %d threads", label(uri), a.bpm,
                if (a.steady) " steady" else (if (a.tail != null) " (tail grid)" else " (no grid)"), a.bars, a.key, t1 - t0, pcm.readMs, pcm.bytes / 1e6, pcm.convertMs, pcm.restarts,
                System.currentTimeMillis() - t1, threads) +
                (if (a.note.isNotEmpty()) " | grids refused: ${a.note}" else ""))
            return cacheKey to a
        } catch (e: Exception) {
            if (settleExpired()) return null
            // MediaExtractor can wrap the data source's cancellation IOException.
            if (cancelled() || e is InterruptedException) return null
            synchronized(memory) {
                retry.failed(cacheKey, android.os.SystemClock.elapsedRealtime()); refused[id] = Reason.NotAnalyzed
                refusalDetail[id] = RefusalDetail.SourceError
            }
            journal("analysis deferred for ${label(uri)}: ${e.javaClass.simpleName} ${e.message?.take(120)}")
            return null
        } catch (e: OutOfMemoryError) {
            // The song's signals are this thread's own (dropped with this frame): the analysis
            // is given up, the app goes on. Reported: this error, uncaught here, closed the app.
            synchronized(memory) {
                retry.failed(cacheKey, android.os.SystemClock.elapsedRealtime()); refused[id] = Reason.NotAnalyzed
                refusalDetail[id] = RefusalDetail.SourceError
            }
            journal("analysis given up for ${label(uri)}: out of memory (heap ${Runtime.getRuntime().maxMemory() / 1_000_000} MB)")
            return null
        } finally {
            activeAnalyses.decrementAndGet()
        }
    }

    private fun charging(): Boolean =
        runCatching { Graph.app.getSystemService(android.os.BatteryManager::class.java).isCharging }.getOrDefault(false)

    /**
     * Saving energy: the app's own energy saver or its data saver applying, the phone's battery
     * saver, or under 20 % and unplugged.
     */
    internal fun frugal(): Boolean = app.myspoti.power.EnergySettings.state.value.manualSaver || com.music.spotui.data.DataSaver.active || runCatching {
        val power = Graph.app.getSystemService(android.os.PowerManager::class.java)
        val battery = Graph.app.getSystemService(android.os.BatteryManager::class.java)
        power.isPowerSaveMode || (!battery.isCharging && battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0 until 20)
    }.getOrDefault(false)

    /** Cores for one analysis, plugged in: all but two (the player and the screen keep theirs); three on battery, two when saving it. */
    private val THREADS = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

    @Volatile private var beatThis: BeatThis? = null
    @Volatile private var modelFailed = false
    private val modelLock = Any()

    /**
     * Beat This, loaded once (null if it cannot run here: the onsets then decide alone). The
     * load is locked: two songs can now analyse at once, and the model must not load twice.
     */
    private fun model(): BeatThis? {
        if (beatThis != null || modelFailed) return beatThis
        return synchronized(modelLock) {
            if (beatThis != null || modelFailed) return@synchronized beatThis
            try {
                BeatThis(Graph.app.assets.open("automix/beat-this-small0-int8.onnx").use { it.readBytes() }, THREADS).also { beatThis = it }
            } catch (e: Throwable) {
                modelFailed = true
                journal("Beat This unavailable: ${e.javaClass.simpleName} ${e.message?.take(120)}")
                null
            }
        }
    }

    // ── Plans ──

    fun planFor(a: TrackAnalysis, b: TrackAnalysis): MixPlan? = MixPlanner.planBounded(a, b, settings())
    fun planAfter(a: TrackAnalysis, b: TrackAnalysis, earliestExit: Double): MixPlan? = MixPlanner.planAfter(a, b, earliestExit, settings())

    // ── Cache ──

    /**
     * In the app's files, not its cache: Android empties a cache when storage runs low, and every
     * song was then decoded and analysed again. Bounded instead ([AnalysisCache.MAX_BYTES], the
     * least recently used go first); the old cache folder moves here once.
     */
    private fun dir(): File {
        val d = File(Graph.app.filesDir, "automix4")
        if (!d.exists()) {
            val old = File(Graph.app.cacheDir, "automix4")
            if (!(old.isDirectory && old.renameTo(d))) d.mkdirs()
        }
        return d
    }

    private fun load(key: String): TrackAnalysis? = runCatching {
        File(dir(), key).takeIf { it.exists() }?.let { f ->
            // Used now: the last to go when the cache is trimmed.
            f.setLastModified(System.currentTimeMillis())
            f.inputStream().buffered().use { TrackAnalysis.read(it) }
        }
    }.getOrNull()

    private fun save(key: String, a: TrackAnalysis) {
        runCatching {
            val f = File(dir(), key)
            val tmp = File(dir(), "$key.tmp")
            tmp.outputStream().buffered().use { a.write(it) }
            tmp.renameTo(f)
            dir().listFiles()?.let { files ->
                AnalysisCache.toTrim(files.map { AnalysisCache.Entry(it.name, it.length(), it.lastModified()) })
                    .forEach { File(dir(), it).delete() }
            }
        }
    }

    private fun requestId(uri: Uri): String =
        if (uri.scheme == ExtensionAudio.SCHEME) "ext:" + uri.lastPathSegment.orEmpty() else uri.toString()

    /** No file I/O or large manifest hashing on the player/UI thread. */
    fun key(uri: Uri): String {
        val id = requestId(uri)
        if (uri.scheme == ExtensionAudio.SCHEME) {
            val stream = ExtensionAudio.streamOf(uri.lastPathSegment.orEmpty())
            if (stream != null) return synchronized(streamKeys) {
                streamKeys.firstOrNull { it.first === stream }?.second ?: "$id:pending:${System.identityHashCode(stream)}"
            }
        }
        return fileVersions[id] ?: baseKeys.getOrPut(id) { digest(id) }
    }

    private fun prepareStreamKey(uri: Uri) {
        if (uri.scheme != ExtensionAudio.SCHEME) return
        val stream = ExtensionAudio.streamOf(uri.lastPathSegment.orEmpty()) ?: return
        synchronized(streamKeys) { if (streamKeys.any { it.first === stream }) return }
        // Signed query strings expire; provider, recording path and audio metadata do not.
        val k = digest(AnalysisIdentity.stream(requestId(uri), stream.providerId, stream.durationMs, stream.codec, stream.source, stream.url, stream.manifest))
        synchronized(streamKeys) {
            streamKeys.addLast(stream to k)
            while (streamKeys.size > 24) streamKeys.removeFirst()
        }
    }

    private fun digest(id: String): String = MessageDigest.getInstance("SHA-1").digest(id.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(24)

    /** Only the worker stats files; replacing an existing file invalidates its analysis. */
    private fun refreshFileVersion(uri: Uri) {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return)
            fileVersions[requestId(uri)] = digest(AnalysisIdentity.file(uri.toString(), file.length(), file.lastModified()))
        } else if (uri.scheme == "content") {
            runCatching {
                Graph.app.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.SIZE, android.provider.MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) fileVersions[requestId(uri)] = digest(AnalysisIdentity.file(uri.toString(), cursor.getLong(0), cursor.getLong(1)))
                }
            }
        }
    }

    fun cancelRequests() { wanted = emptySet() }

    fun sourcesChanged() {
        synchronized(memory) { retry.clear(); refused.clear(); refusalDetail.clear() }
    }

    private fun label(uri: Uri) = uri.lastPathSegment?.takeLast(48).orEmpty()

    /** Off the main thread: every song analysed is dropped (they are analysed again when played). */
    fun clearCache() {
        cacheEpoch++
        synchronized(memory) { memory.clear(); peaksMemory.clear(); retry.clear(); refused.clear(); refusalDetail.clear() }
        synchronized(progressStates) { progressStates.clear() }
        fileVersions.clear()
        dir().listFiles()?.forEach { it.delete() }
    }

    /** The space the analyses take on disk, in bytes (off the main thread). */
    fun cacheBytes(): Long = dir().listFiles()?.sumOf { it.length() } ?: 0L
}

/** Invalid persisted pairs recover together to Auto; max=0 means no configured maximum. */
internal fun normalizeDurationPair(minSeconds: Int, maxSeconds: Int): Pair<Int, Int> =
    if (minSeconds !in 0..90 || maxSeconds !in 0..90 || (maxSeconds != 0 && minSeconds > maxSeconds)) 0 to 0
    else minSeconds to maxSeconds
