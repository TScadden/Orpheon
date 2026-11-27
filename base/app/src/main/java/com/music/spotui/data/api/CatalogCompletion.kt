package com.music.spotui.data.api

import app.myspoti.api.best
import app.myspoti.catalog.Hit
import app.myspoti.catalog.Match
import com.music.spotui.data.catalog.CatalogRuntime
import com.music.spotui.data.local.ImportedSong
import com.music.spotui.data.offline.OfflineDownloader
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs

/**
 * Completing imported songs the Spotify reading left thin, from the app's own accountless
 * catalog (the MetadataApi chain: Deezer, iTunes, MusicBrainz and the script providers, in
 * the user's order) instead of from Spotify.
 *
 * Why this exists: an import's rich half is Spotify's API read with the embed page's
 * anonymous token, and that read is refused intermittently (a 429 quota answer was seen
 * from a phone whose playlist then repaired to nothing: the merged read came back
 * embed-only, no covers, no albums, no ISRCs, and the repair had nothing to fill). The
 * catalog does not depend on Spotify at all, so a song the Spotify read left thin is
 * completed from it — at import time and again when the playlist repair re-reads.
 *
 * The bar is strict, because a wrong cover is worse than none:
 *  - a song carrying an ISRC is completed from the ISRC-exact hit when a provider has one
 *    (the chain's own resolve asks every provider by ISRC first; Deezer and MusicBrainz
 *    answer ISRC lookups), and such a hit is accepted on the ISRC alone;
 *  - any other hit must match on the normalized title AND the artist (the catalog's own
 *    [Match] normalization and artist fit at full marks), and, when both durations are
 *    known, its duration must sit inside the downloads' own length tolerances
 *    ([OfflineDownloader.lengthMismatch]: no more than 5 s short, 15 s long);
 *  - everything looser is rejected and the field stays blank.
 *
 * Only blanks are ever filled — a field a song already carries is never overwritten — and
 * the list itself never changes: same songs, same order. A song the catalog cannot answer
 * for (or a lookup that fails) is left exactly as it was.
 */
object CatalogCompletion {

    /** One catalog hit reduced to the fields a completion may take from it. */
    data class Candidate(
        val title: String,
        val artists: List<String>,
        val album: String,
        val coverUrl: String,
        val durationMs: Long,
        val isrc: String,
        /** The hit's stated explicitness (Deezer and iTunes state it): UNKNOWN when it does not. */
        val explicit: app.myspoti.api.Explicitness = app.myspoti.api.Explicitness.UNKNOWN,
    )

    /** Where candidates come from: the live catalog chain, or a test's stand-in. */
    fun interface Gatherer {
        suspend fun candidates(song: ImportedSong): List<Candidate>
    }

    /** How many thin songs one pass completes at most; the rest wait for the next pass. */
    private const val MAX_PER_PASS = 100
    /** Lookups in flight at once: the catalog is shared, polite infrastructure. */
    private const val IN_FLIGHT = 5

    /** A song still missing any of the fields a completion can fill. */
    fun thin(song: ImportedSong): Boolean =
        song.isrc.isBlank() || song.coverUrl.isBlank() || song.album.isBlank() ||
            song.explicit == app.myspoti.api.Explicitness.UNKNOWN

    /**
     * A song read wrong at its source: its title is its artist said again. An
     * Amazon row can arrive this way when the page's artist line is read as the
     * title; such a row names no recording, so nothing may complete it — matching
     * it could only attach a stranger's song, and its "title artist" play query
     * could only fail. It is left exactly as it was for the read to fix, or the
     * user to re-import.
     */
    fun isMisread(song: ImportedSong): Boolean {
        val title = Match.name(song.title)
        return title.isNotEmpty() && title == Match.name(song.artist)
    }

    /** Whether [c] may complete [song], under the strict bar in the class doc. */
    fun accepted(song: ImportedSong, c: Candidate): Boolean {
        val wanted = Match.isrc(song.isrc)
        if (wanted != null && Match.isrc(c.isrc) == wanted) return true
        // A misread row is refused whatever the catalog offers. An ISRC-exact hit
        // above is an identity, not a match, and stands: a self-titled song whose
        // own source stated its ISRC is no misread.
        if (isMisread(song)) return false
        val title = Match.title(song.title)
        if (title.isEmpty() || title != Match.title(c.title)) return false
        if (!artistMatches(song.artist, c.artists)) return false
        if (song.durationMs > 0 && c.durationMs > 0 &&
            OfflineDownloader.lengthMismatch(c.durationMs / 1000, song.durationMs) != null
        ) return false
        return true
    }

    /** The full credit written the same, or the artist fit at full marks. */
    private fun artistMatches(credit: String, artists: List<String>): Boolean {
        val wanted = Match.name(credit)
        if (wanted.isEmpty() || artists.isEmpty()) return false
        if (artists.any { Match.name(it) == wanted }) return true
        return Match.artistFit(credit, artists) >= 1.0
    }

    /**
     * The candidate of [candidates] that completes [song]: an ISRC-exact one first, then
     * the strict-bar passer closest in duration (the given order breaks ties). Null when
     * none passes — the song keeps its blanks.
     */
    fun pick(song: ImportedSong, candidates: List<Candidate>): Candidate? {
        val ok = candidates.filter { accepted(song, it) }
        if (ok.isEmpty()) return null
        val wanted = Match.isrc(song.isrc)
        if (wanted != null) ok.firstOrNull { Match.isrc(it.isrc) == wanted }?.let { return it }
        return ok.minByOrNull { c ->
            if (song.durationMs > 0 && c.durationMs > 0) abs(c.durationMs - song.durationMs) else Long.MAX_VALUE
        }
    }

    /** [song] with its blank ISRC / cover / album filled from [c]; present fields stand.
     * An unjudged song also takes the candidate's stated explicitness; a stated one stands. */
    fun completed(song: ImportedSong, c: Candidate): ImportedSong = song.copy(
        isrc = song.isrc.ifBlank { c.isrc },
        coverUrl = song.coverUrl.ifBlank { c.coverUrl },
        album = song.album.ifBlank { c.album },
        explicit = if (song.explicit == app.myspoti.api.Explicitness.UNKNOWN) c.explicit else song.explicit,
    )

    /**
     * Every thin song of [songs] completed from [gather]'s candidates, blanks only, order
     * kept. A song whose gathering fails keeps its blanks (the pass as a whole never fails).
     * A misread song (see [isMisread]) is never gathered for: it stays exactly as it was.
     */
    suspend fun complete(songs: List<ImportedSong>, gather: Gatherer): List<ImportedSong> = coroutineScope {
        val gate = Semaphore(IN_FLIGHT)
        var attempted = 0
        songs.map { song ->
            if (!thin(song) || isMisread(song) || attempted >= MAX_PER_PASS) async { song }
            else {
                attempted++
                async {
                    gate.withPermit {
                        runCatching { gather.candidates(song) }.getOrDefault(emptyList())
                            .let { pick(song, it) }?.let { completed(song, it) } ?: song
                    }
                }
            }
        }.map { it.await() }
    }

    /** [complete] against the live catalog chain. */
    suspend fun completeFromCatalog(songs: List<ImportedSong>): List<ImportedSong> =
        complete(songs, Gatherer { song -> catalogCandidates(song) })

    /**
     * The live candidates for [song]: the chain's ISRC-first resolve when the song carries
     * an ISRC, plus the chain's merged track search under the song's artist and title (and
     * under the title without its bracketed credits, for services that search every word).
     * The same recording offered twice is kept once.
     */
    private suspend fun catalogCandidates(song: ImportedSong): List<Candidate> {
        val chain = CatalogRuntime.chain()
        val byUri = LinkedHashMap<String, Candidate>()
        fun add(hit: Hit) { byUri.putIfAbsent(hit.track.uri, hit.toCandidate()) }
        if (Match.isrc(song.isrc) != null) {
            runCatching { chain.resolve(song.title, song.artist, song.durationMs, song.isrc) }
                .getOrNull()?.let(::add)
        }
        val queries = listOf("${song.artist} ${song.title}", "${song.artist} ${Match.stripped(song.title)}").distinct()
        for (query in queries) {
            runCatching { chain.searchHits(query, 0, 20) }.getOrNull()?.items?.forEach(::add)
        }
        return byUri.values.toList()
    }

    private fun Hit.toCandidate(): Candidate = Candidate(
        title = track.name,
        artists = track.artists.map { it.name },
        album = track.album?.name.orEmpty(),
        coverUrl = track.album?.images?.best(300)?.url.orEmpty(),
        durationMs = track.durationMs,
        isrc = isrc.orEmpty(),
        explicit = track.explicitness,
    )
}
