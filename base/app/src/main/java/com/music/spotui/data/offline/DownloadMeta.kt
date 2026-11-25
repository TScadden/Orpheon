package com.music.spotui.data.offline

import com.music.spotui.catalog.Hit

/**
 * The metadata a download resolves against: the id the download-source extension is
 * asked for ([downloadId]), the recording's ISRC, and the canonical names, duration
 * and cover the saved track is labelled with.
 *
 * A song can be keyed by a catalog provider's id instead of a Spotify id (a catalog
 * song saved under [com.music.spotui.catalog.CatalogRuntime.keyOf], e.g.
 * "deezer.3135556"). Spotify has no such track, so a download of it used to reach the
 * source extension with a foreign id and no ISRC and come back "no matching
 * recording". [merge] completes the meta from the catalog chain's own record of the
 * track: the provider's ISRC and canonical names, cross-matched to the Spotify
 * record (whose id the extensions know best) when the chain carries one.
 */
data class DownloadMeta(
    /** The id the download source is asked for; the stored track stays keyed by the request's own id. */
    val downloadId: String,
    val isrc: String?,
    val title: String,
    val artist: String,
    val album: String,
    /** A Spotify album id, when one is known; a provider's own album id never lands here. */
    val albumId: String,
    val durationMs: Long,
    val coverUrl: String?,
) {
    companion object {
        /** The first of [values] that is not blank, or "". */
        private fun firstText(vararg values: String?): String =
            values.firstOrNull { !it.isNullOrBlank() } ?: ""

        /**
         * [base] (the request's own values, keyed by its provider key) completed by
         * [hit], the provider's record of the same track. [spotifyHit], when the
         * chain cross-matched the track to Spotify, wins the names and supplies the
         * download id: the source extensions resolve a Spotify id most reliably.
         * Without it the download keeps the provider key as its id and gains the
         * ISRC, which is the part the match actually turns on.
         */
        fun merge(base: DownloadMeta, hit: Hit, spotifyHit: Hit?): DownloadMeta {
            val spotify = spotifyHit ?: hit.takeIf { it.provider == "spotify" }
            val isrc = listOf(spotify?.isrc, hit.isrc)
                .firstNotNullOfOrNull { raw -> raw?.let(OfflineDownloader::normalizeIsrc) }
            return DownloadMeta(
                downloadId = spotify?.track?.id ?: base.downloadId,
                isrc = isrc ?: base.isrc,
                title = firstText(spotify?.track?.name, hit.track.name, base.title),
                artist = firstText(spotify?.track?.artist, hit.track.artist, base.artist),
                album = firstText(spotify?.track?.album, hit.track.album, base.album),
                albumId = firstText(if (spotify != null) spotify.track.albumId else null, base.albumId),
                durationMs = spotify?.track?.durationMs?.takeIf { it > 0 }
                    ?: hit.track.durationMs.takeIf { it > 0 } ?: base.durationMs,
                coverUrl = spotify?.track?.coverUrl ?: hit.track.coverUrl ?: base.coverUrl,
            )
        }
    }
}
