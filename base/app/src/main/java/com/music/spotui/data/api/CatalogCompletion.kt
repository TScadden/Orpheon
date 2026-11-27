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
 *  - any other hit must match on the normalized title AND the artist, and, when both
 *    durations are known, its duration must sit inside the downloads' own length
 *    tolerances ([OfflineDownloader.lengthMismatch]: no more than 5 s short, 15 s long);
 *  - everything looser is rejected and the field stays blank.
 *
 * The artist half of the bar compares PRIMARY artists (see [primaryCredit]): the head
 * of each credit, with bracketed or trailing featuring credits stripped, both sides
 * normalized the same way. It has to: a guest credit defeats the full-credit
 * comparisons even when the head artist is identical — [Match.artistFit] caps a
 * contained name at 0.9, so "The Kid LAROI [feat. Youngboy Never Broke Again]" never
 * reached full marks against a plain "The Kid LAROI", and a duet credit ("Old Sea
 * Brigade, Amanda Bergman") never equalled either of its two names. Those rows
 * stayed thin forever — no duration, no identity, unplayable — while single-artist
 * rows completed. The primary comparison widens the credit's SHAPE tolerance only:
 * a different head artist still refuses, and the title and duration bars are
 * exactly as strict as they were.
 *
 * One duration exception, narrowly fenced: a stored duration that NO strict
 * title-and-primary-artist candidate corroborates, while two or more of those
 * candidates agree with EACH OTHER on a different duration, counts as unfilled —
 * the agreeing hits outvote the row's own value, and the accepted hit's duration
 * replaces it. A lone contradicting candidate never outvotes a stated duration
 * (it is as likely a different recording as a wrong row), and a duration any
 * candidate corroborates is never overwritten.
 *
 * Only blanks are ever filled — a field a song already carries is never overwritten,
 * with the single fenced exception of an outvoted duration (below) — and
 * the list itself never changes: same songs, same order. A song the catalog cannot answer
 * for (or a lookup that fails) is left exactly as it was.
 *
 * The pass also attaches the two fields that decide whether a row can play at all. The
 * first is the hit's catalog key ([Candidate.catalogKey]): the app refuses to ask the
 * extensions about a song with no id at all (ExtensionAudio.playList), so an Amazon row
 * with no Spotify id plays under the key its completion attached. The second is the
 * hit's duration. The audio-source extensions resolve a song by the metadata they are
 * handed — title, artist, duration — and the resolver of the source this phone uses
 * (Melzo's, read in its own code) refuses a track whose duration is not positive before
 * it searches at all, and scores every candidate against that duration. An Amazon row
 * whose page stated no duration (the phone's rendering of the page shows none) was
 * therefore unanswerable forever: v28 attached its identity and its ISRC, and every
 * tap still ended "No source plays", because no pass anywhere filled the one field
 * the hook gates on. A duration the row lacks is filled from the accepted hit, under
 * the same strict bar as every other field, never looser.
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
        /** The hit's own catalog key ("deezer.3135556"): the identity an id-less row
         * completed from this hit plays under. "" when the hit's uri names no key. */
        val catalogKey: String = "",
    )

    /** Where candidates come from: the live catalog chain, or a test's stand-in. */
    fun interface Gatherer {
        suspend fun candidates(song: ImportedSong): List<Candidate>
    }

    /** How many thin songs one pass completes at most; the rest wait for the next pass. */
    private const val MAX_PER_PASS = 100
    /** Lookups in flight at once: the catalog is shared, polite infrastructure. */
    private const val IN_FLIGHT = 5

    /**
     * A song still missing any of the fields a completion can fill. A row with no
     * Spotify id is also thin while it carries no catalog key, however full its
     * other fields read: the key is its playable identity, and a row the page
     * itself furnished with a cover and an album (an Amazon row) is exactly the
     * one that would otherwise never be gathered for and never play. A row with
     * no duration is thin for the same reason: the audio hooks resolve by title,
     * artist and duration, and refuse a duration-less track outright (see the
     * class doc), so a row whose source stated no duration must be gathered for
     * until a completion fills it — v28 left such rows "complete" with an
     * identity and an ISRC and they still could not play.
     */
    fun thin(song: ImportedSong): Boolean =
        song.isrc.isBlank() || song.coverUrl.isBlank() || song.album.isBlank() ||
            song.durationMs <= 0 ||
            song.explicit == app.myspoti.api.Explicitness.UNKNOWN ||
            (song.spotifyTrackId.isBlank() && song.catalogKey.isBlank())

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
        if (!acceptedIgnoringDuration(song, c)) return false
        // The duration bar: a hit whose length contradicts a stated duration is
        // another recording (a longer cut, a live take) until the agreement rule
        // in [pick] says the row's own value is the wrong one. An ISRC-exact hit
        // is an identity, not a match, and is exempt: it stands on the ISRC alone.
        val wanted = Match.isrc(song.isrc)
        if (wanted != null && Match.isrc(c.isrc) == wanted) return true
        if (song.durationMs > 0 && c.durationMs > 0 &&
            OfflineDownloader.lengthMismatch(c.durationMs / 1000, song.durationMs) != null
        ) return false
        return true
    }

    /** [accepted] without the duration bar: the title and the artist alone. */
    private fun acceptedIgnoringDuration(song: ImportedSong, c: Candidate): Boolean {
        val wanted = Match.isrc(song.isrc)
        if (wanted != null && Match.isrc(c.isrc) == wanted) return true
        // A misread row is refused whatever the catalog offers. An ISRC-exact hit
        // above is an identity, not a match, and stands: a self-titled song whose
        // own source stated its ISRC is no misread.
        if (isMisread(song)) return false
        val title = Match.title(song.title)
        if (title.isEmpty() || title != Match.title(c.title)) return false
        if (!artistMatches(song.artist, c.artists)) return false
        return true
    }

    /**
     * The candidates that outvote a wrong stored duration: strict title-and-artist
     * hits (the duration bar aside) whose stated durations agree with EACH OTHER
     * inside the length tolerances while the stored value agrees with none of
     * them. Two or more independent hits stating the same different length are
     * the recording telling on the row; a lone contradictor proves nothing and
     * never outvotes. Null when there is no such agreement — including whenever
     * some candidate already corroborates the stored duration (then the ordinary
     * bar governs and the stated duration stands).
     */
    private fun agreeingCluster(song: ImportedSong, candidates: List<Candidate>): List<Candidate>? {
        if (song.durationMs <= 0) return null
        if (candidates.any { accepted(song, it) }) return null
        val relaxed = candidates.filter { acceptedIgnoringDuration(song, it) && it.durationMs > 0 }
        if (relaxed.size < 2) return null
        var best: List<Candidate>? = null
        for (c in relaxed) {
            val group = relaxed.filter {
                OfflineDownloader.lengthMismatch(it.durationMs / 1000, c.durationMs) == null
            }
            if (group.size >= 2 && (best == null || group.size > best!!.size)) best = group
        }
        return best
    }

    /** The full credit written the same, the primary artists equal, or the artist fit at full marks. */
    private fun artistMatches(credit: String, artists: List<String>): Boolean {
        val wanted = Match.name(credit)
        if (wanted.isEmpty() || artists.isEmpty()) return false
        if (artists.any { Match.name(it) == wanted }) return true
        // The primary comparison: the head of the song's credit against the head of
        // any of the hit's artist entries (a provider may state the whole credit as
        // one entry, or list the guest first — the head of an entry is still a head).
        val primary = Match.name(primaryCredit(credit))
        if (primary.isNotEmpty() && artists.any { Match.name(primaryCredit(it)) == primary }) return true
        return Match.artistFit(credit, artists) >= 1.0
    }

    private val FEATURING_BRACKETED = Regex("\\s*[\\[(]\\s*(feat\\.?|ft\\.?|featuring)\\b[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
    private val FEATURING_PLAIN = Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE)
    private val CREDIT_HEAD_SPLIT = Regex("\\s*(?:,|;|&|\\+)\\s*|\\s+and\\s+", RegexOption.IGNORE_CASE)

    /**
     * A credit's primary artist, as written: the featuring credits removed
     * ("The Kid LAROI [feat. Youngboy Never Broke Again]" → "The Kid LAROI",
     * "A feat. B" → "A"), then the head of what remains, split on the credit
     * separators (", ", " & ", " and ", …): "Old Sea Brigade, Amanda Bergman" →
     * "Old Sea Brigade". Both sides of a comparison go through this, so an act
     * whose name itself carries a separator ("Above & Beyond") still meets
     * itself: both sides reduce to the same head.
     */
    internal fun primaryCredit(credit: String): String {
        val noFeaturing = FEATURING_PLAIN.replace(FEATURING_BRACKETED.replace(credit, ""), "")
        return noFeaturing.split(CREDIT_HEAD_SPLIT).firstOrNull()?.trim().orEmpty()
    }

    /**
     * The candidate of [candidates] that completes [song]: an ISRC-exact one first, then
     * the strict-bar passer closest in duration (the given order breaks ties). When no
     * candidate passes and the stored duration is the only bar in the way, the
     * agreeing cluster (see [agreeingCluster]) may still complete the song — the
     * cluster's candidate closest to the cluster's own middle is the pick, and
     * [completion] replaces the outvoted duration with its length. Null when none
     * passes either way — the song keeps its blanks.
     */
    fun pick(song: ImportedSong, candidates: List<Candidate>): Candidate? {
        val ok = candidates.filter { accepted(song, it) }
        if (ok.isNotEmpty()) {
            val wanted = Match.isrc(song.isrc)
            if (wanted != null) ok.firstOrNull { Match.isrc(it.isrc) == wanted }?.let { return it }
            return ok.minByOrNull { c ->
                if (song.durationMs > 0 && c.durationMs > 0) abs(c.durationMs - song.durationMs) else Long.MAX_VALUE
            }
        }
        val cluster = agreeingCluster(song, candidates) ?: return null
        val sorted = cluster.map { it.durationMs }.sorted()
        val middle = sorted[sorted.size / 2]
        return cluster.minByOrNull { abs(it.durationMs - middle) }
    }

    /**
     * [song] completed from [candidates], or null when none may: [pick] applied,
     * then [completed] — except on the agreeing-cluster path, where the stored
     * duration the cluster outvoted counts as unfilled first, so the accepted
     * hit's duration replaces it instead of standing against it.
     */
    fun completion(song: ImportedSong, candidates: List<Candidate>): ImportedSong? {
        val picked = pick(song, candidates) ?: return null
        val cluster = agreeingCluster(song, candidates)
        val base = if (cluster != null && cluster.contains(picked)) song.copy(durationMs = 0) else song
        return completed(base, picked)
    }

    /** [song] with its blank ISRC / cover / album filled from [c]; present fields stand.
     * An unjudged song also takes the candidate's stated explicitness; a stated one stands.
     * A song with no catalog key takes the candidate's: its playable identity. A song
     * with no duration takes the candidate's: the length the audio hooks gate on (a
     * stated duration stands — the acceptance bar already checked the candidate
     * against it; the one path that replaces a stated duration runs through
     * [completion], which blanks an outvoted one before it gets here). */
    fun completed(song: ImportedSong, c: Candidate): ImportedSong = song.copy(
        isrc = song.isrc.ifBlank { c.isrc },
        coverUrl = song.coverUrl.ifBlank { c.coverUrl },
        album = song.album.ifBlank { c.album },
        durationMs = if (song.durationMs > 0) song.durationMs else c.durationMs,
        explicit = if (song.explicit == app.myspoti.api.Explicitness.UNKNOWN) c.explicit else song.explicit,
        catalogKey = song.catalogKey.ifBlank { c.catalogKey },
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
                            .let { completion(song, it) } ?: song
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
     *
     * The searches also run under the credit's PRIMARY artist (see [primaryCredit]):
     * a raw guest credit ("The Kid LAROI [feat. Youngboy Never Broke Again]") is a
     * query the services answer poorly or not at all — the brackets and the guest's
     * name are noise to them — so a row whose matcher would now accept the head
     * artist's hit must still be able to REACH that hit.
     */
    private suspend fun catalogCandidates(song: ImportedSong): List<Candidate> {
        val chain = CatalogRuntime.chain()
        val byUri = LinkedHashMap<String, Candidate>()
        fun add(hit: Hit) { byUri.putIfAbsent(hit.track.uri, hit.toCandidate()) }
        if (Match.isrc(song.isrc) != null) {
            runCatching { chain.resolve(song.title, song.artist, song.durationMs, song.isrc) }
                .getOrNull()?.let(::add)
        }
        val primary = primaryCredit(song.artist)
        val queries = listOf(
            "${song.artist} ${song.title}",
            "${song.artist} ${Match.stripped(song.title)}",
            "$primary ${song.title}",
            "$primary ${Match.stripped(song.title)}",
        ).distinct()
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
        catalogKey = CatalogRuntime.keyOf(track.uri).orEmpty(),
    )
}
