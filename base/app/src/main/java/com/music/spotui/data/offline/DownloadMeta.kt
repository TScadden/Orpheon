package com.music.spotui.data.offline

import app.myspoti.catalog.Hit

/**
 * The metadata a download resolves against: the id the download-source extension is
 * asked for ([downloadId]), the recording's ISRC, and the canonical names, duration
 * and cover the saved track is labelled with.
 *
 * A song can be keyed by a catalog provider's id instead of a Spotify id (a catalog
 * song saved under [com.music.spotui.data.catalog.CatalogRuntime.keyOf], e.g.
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
    /** The track's explicitness as the sources state it; UNKNOWN when none does. */
    val explicitness: app.myspoti.api.Explicitness = app.myspoti.api.Explicitness.UNKNOWN,
) {
    companion object {
        /** The first of [values] that is not blank, or "". */
        private fun firstText(vararg values: String?): String =
            values.firstOrNull { !it.isNullOrBlank() } ?: ""

        /** The provider a hit's track belongs to ("deezer" of "deezer:track:…"). */
        private fun providerOf(hit: Hit): String = hit.track.uri.substringBefore(':')

        /** A hit's track id on its own provider ("3135556" of "deezer:track:3135556"). */
        private fun trackIdOf(hit: Hit): String = hit.track.uri.substringAfterLast(':')

        private fun artistsOf(hit: Hit): String = hit.track.artists.joinToString(", ") { it.name }

        private fun coverOf(hit: Hit): String? =
            hit.track.album?.images?.maxByOrNull { it.width ?: 0 }?.url

        /**
         * [base] (the request's own values, keyed by its provider key) completed by
         * [hit], the provider's record of the same track. [spotifyHit], when the
         * chain cross-matched the track to Spotify, wins the names and supplies the
         * download id: the source extensions resolve a Spotify id most reliably.
         * Without it the download keeps the provider key as its id and gains the
         * ISRC, which is the part the match actually turns on.
         */
        fun merge(base: DownloadMeta, hit: Hit, spotifyHit: Hit?): DownloadMeta {
            val spotify = spotifyHit ?: hit.takeIf { providerOf(it) == "spotify" }
            val isrc = listOf(spotify?.isrc, hit.isrc)
                .firstNotNullOfOrNull { raw -> raw?.let(OfflineDownloader::normalizeIsrc) }
            return DownloadMeta(
                downloadId = spotify?.let { trackIdOf(it) } ?: base.downloadId,
                isrc = isrc ?: base.isrc,
                title = firstText(spotify?.track?.name, hit.track.name, base.title),
                artist = firstText(spotify?.let { artistsOf(it) }, artistsOf(hit), base.artist),
                album = firstText(spotify?.track?.album?.name, hit.track.album?.name, base.album),
                albumId = firstText(spotify?.track?.album?.uri?.substringAfterLast(':'), base.albumId),
                durationMs = spotify?.track?.durationMs?.takeIf { it > 0 }
                    ?: hit.track.durationMs.takeIf { it > 0 } ?: base.durationMs,
                coverUrl = spotify?.let { coverOf(it) } ?: coverOf(hit) ?: base.coverUrl,
                explicitness = spotify?.track?.explicitness?.takeIf { it != app.myspoti.api.Explicitness.UNKNOWN }
                    ?: hit.track.explicitness.takeIf { it != app.myspoti.api.Explicitness.UNKNOWN }
                    ?: base.explicitness,
            )
        }
    }
}
