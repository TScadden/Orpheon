package com.music.spotui.data.preferences

import com.music.spotui.data.entity.SongsModel

/**
 * The explicit filter's judgements, kept pure so they can be tested without a phone.
 * A song is hidden from a list, or blocked when a queue reaches it, only when explicit
 * music is not allowed AND the song is KNOWN explicit — a source stated it (see
 * [SongsModel.knownExplicit]). A song no source has judged (UNKNOWN: most phone files,
 * every unreleased file) is never hidden and never blocked.
 */
object ExplicitFilter {

    /** [song] leaves lists and search while explicit music is off. */
    fun hides(song: SongsModel, allowed: Boolean): Boolean = !allowed && song.knownExplicit

    /** A queue reaching a song whose explicit flag is [explicit] skips it while off. */
    fun blocks(explicit: Boolean, allowed: Boolean): Boolean = !allowed && explicit

    /** [songs] as a list shows them: the known-explicit ones gone while off. */
    fun visible(songs: List<SongsModel>, allowed: Boolean): List<SongsModel> =
        if (allowed) songs else songs.filterNot { it.knownExplicit }

    /** How many of [songs] the filter keeps out while off (the page's hidden note). */
    fun hiddenCount(songs: List<SongsModel>, allowed: Boolean): Int =
        if (allowed) 0 else songs.count { it.knownExplicit }
}
