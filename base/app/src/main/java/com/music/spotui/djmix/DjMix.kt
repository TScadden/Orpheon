/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.djmix

import app.myspoti.api.Explicitness
import app.myspoti.api.best
import app.myspoti.automix.TrackAnalysis
import app.myspoti.catalog.Hit
import app.myspoti.catalog.ItemUri

/**
 * DJ Mix: a new playlist of a category's most popular songs this week (the free
 * catalog's charts — Deezer's, public, no key, no account), ordered so each song
 * flows into the next (see [DjSequence]). Everything in this file is the pure part:
 * which categories the catalog offers, which chart songs form a category's pool,
 * what the playlist is called, and how the finished order and its honest counts
 * come out of the pool and the analyses. The Android glue (fetching, analysing,
 * storing) is [DjMixSession].
 */

/** A genre of the free catalog, as its own listing names it. */
data class DjGenre(val id: Int, val name: String)

/** One entry of the DJ Mix category picker. */
data class DjCategory(
    /** Stable key of the entry (the playlist name is built from [label], not this). */
    val key: String,
    val label: String,
    /** The catalog genre whose chart feeds this category. */
    val genreId: Int,
    /** Only songs KNOWN clean may enter the pool (the Clean Rap rule, see [DjPool]). */
    val cleanOnly: Boolean = false,
)

/**
 * The picker, derived from the genres the catalog actually lists: a curated set of
 * names is matched against the live listing, so a genre the service drops (or has
 * not got) simply has no entry, and the labels stay the app's own. Clean Rap is not
 * a chart of its own: it is the Rap chart read under the known-clean rule, offered
 * right after Rap whenever Rap itself is offered.
 */
object DjCategories {
    private data class Curated(val label: String, val names: Set<String>)

    private val CURATED = listOf(
        Curated("Top this week", setOf("all")),
        Curated("Pop", setOf("pop")),
        Curated("Rap", setOf("rap/hip hop", "rap", "hip hop", "hip-hop")),
        Curated("Folk", setOf("folk")),
        Curated("Rock", setOf("rock")),
        Curated("Dance", setOf("dance")),
        Curated("Electro", setOf("electro", "electronic")),
        Curated("R&B", setOf("r&b", "rnb", "rhythm and blues")),
        Curated("Alternative", setOf("alternative")),
        Curated("Country", setOf("country")),
        Curated("Jazz", setOf("jazz")),
        Curated("Metal", setOf("metal")),
        Curated("Reggae", setOf("reggae")),
        Curated("Reggaeton", setOf("reggaeton")),
        Curated("Latin", setOf("latin music", "latin")),
        Curated("Soul & Funk", setOf("soul & funk", "soul and funk")),
        Curated("Blues", setOf("blues")),
        Curated("Classical", setOf("classical")),
        Curated("Christian", setOf("christian", "christian & gospel", "gospel")),
        Curated("Kids", setOf("kids", "children")),
    )

    private fun slug(label: String) = label.lowercase()
        .replace("&", "and").replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun fromGenres(genres: List<DjGenre>): List<DjCategory> {
        val byName = genres.associateBy { it.name.trim().lowercase() }
        val out = ArrayList<DjCategory>()
        for (c in CURATED) {
            val genre = c.names.firstNotNullOfOrNull { byName[it] } ?: continue
            out += DjCategory(slug(c.label), c.label, genre.id)
            // Clean Rap rides on Rap's own chart, under the known-clean rule.
            if (c.label == "Rap") out += DjCategory("clean-rap", "Clean Rap", genre.id, cleanOnly = true)
        }
        return out
    }
}

/** One song of a DJ Mix pool: a playable catalog song, in chart order. */
data class DjSong(
    /** The id the player, the downloads and the extensions know it by ("deezer.3135556"). */
    val key: String,
    /** Its catalog uri ("deezer:track:3135556"). */
    val catalogUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverUri: String,
    val durationMs: Long,
    /** The recording's ISRC, when the chart states it (its listing usually does not). */
    val isrc: String,
    val explicitness: Explicitness,
)

/**
 * A category's pool from its chart: the playable songs, each once, most popular
 * first, at most [TARGET]. Under the Clean Rap rule ([DjCategory.cleanOnly]) only
 * songs whose explicitness is KNOWN clean enter — a stated non-explicit judgement
 * (the chart's own, or one CatalogCompletion filled under its strict bar). An
 * explicit song is out, and an unjudged (UNKNOWN) song is out too: the app never
 * promises a song is clean when it does not know.
 */
object DjPool {
    /** How many songs a mix is built from, at most. */
    const val TARGET = 30

    /** Fewer songs than this is not a mix: the build says so instead of padding. */
    const val MIN_SONGS = 5

    fun fromChart(hits: List<Hit>, cleanOnly: Boolean): List<DjSong> {
        val seen = HashSet<String>()
        val out = ArrayList<DjSong>()
        for (hit in hits) {
            val t = hit.track
            if (!t.playable) continue
            val item = runCatching { ItemUri.parse(t.uri) }.getOrNull() ?: continue
            if (item.kind != "track") continue
            val key = "${item.provider}.${item.id}"
            if (!seen.add(key)) continue
            if (cleanOnly && t.explicitness != Explicitness.CLEAN) continue
            out += DjSong(
                key = key,
                catalogUri = t.uri,
                title = t.name,
                artist = t.artists.joinToString(", ") { it.name },
                album = t.album?.name.orEmpty(),
                coverUri = t.album?.images?.best(300)?.url.orEmpty(),
                durationMs = t.durationMs,
                isrc = hit.isrc.orEmpty(),
                explicitness = t.explicitness,
            )
            if (out.size >= TARGET) break
        }
        return out
    }
}

/** The playlist's name: "DJ Mix — <Category>", kept distinct from any list already there. */
object DjNames {
    fun base(label: String) = "DJ Mix — $label"

    /**
     * [base] when free; with the date appended when a mix of this category is
     * already there ("DJ Mix — Pop · Nov 27"); numbered from there ("(2)", "(3)"…).
     * [existing] is every playlist name the new one must not repeat (compared
     * trimmed and case-insensitively, as the library shows them).
     */
    fun unique(existing: Collection<String>, label: String, dateLabel: String): String {
        val taken = existing.mapTo(HashSet()) { it.trim().lowercase() }
        val base = base(label)
        if (base.lowercase() !in taken) return base
        val dated = "$base · $dateLabel"
        if (dated.lowercase() !in taken) return dated
        var n = 2
        while ("$dated ($n)".lowercase() in taken) n++
        return "$dated ($n)"
    }
}

/** How a finished mix came out, stated as it is: what was sequenced, what was not. */
data class DjSummary(val total: Int, val sequenced: Int, val appended: Int)

/** A pool in its final playlist order, with its [DjSummary]. */
data class DjArrangement(val ordered: List<DjSong>, val summary: DjSummary)

object DjMixes {
    /**
     * The playlist's construction from the pool and the analyses that exist:
     * analysed songs sequenced for flow (see [DjSequence]), the rest appended
     * after them in chart order — never silently dropped, and counted in the
     * summary either way.
     */
    fun arrange(pool: List<DjSong>, analyses: Map<String, TrackAnalysis>): DjArrangement {
        val r = DjSequence.order(pool) { analyses[it.key] }
        return DjArrangement(r.ordered, DjSummary(pool.size, r.sequenced, r.appended))
    }
}
