package com.music.spotui.data.api

import com.music.spotui.data.local.ImportedSong
import com.music.spotui.data.offline.OfflineDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What a pasted playlist link is, before anything is fetched. */
sealed interface PlaylistLink {
    data class Spotify(val id: String) : PlaylistLink
    /** Amazon Music: its API is closed and authorized-only, so a link can be told apart but not read. */
    object Amazon : PlaylistLink
    object Unknown : PlaylistLink
}

object PlaylistLinks {
    private val SPOTIFY_ID = Regex("[A-Za-z0-9]{16,32}")

    fun parse(raw: String): PlaylistLink {
        val s = raw.trim()
        if (s.isEmpty()) return PlaylistLink.Unknown
        if (s.contains("music.amazon", ignoreCase = true)) return PlaylistLink.Amazon
        if (s.startsWith("spotify:playlist:", ignoreCase = true)) {
            val id = s.removePrefix("spotify:playlist:").substringBefore('?').trim()
            return if (SPOTIFY_ID.matches(id)) PlaylistLink.Spotify(id) else PlaylistLink.Unknown
        }
        // open.spotify.com/[intl-xx/][embed/]playlist/<id>[?...]
        val host = s.substringAfter("://", s).substringBefore('/').lowercase()
        if (host == "open.spotify.com" || host == "spotify.com" || host == "play.spotify.com" || host.endsWith(".spotify.com")) {
            val seg = s.substringAfter("://", "").split('/').filter { it.isNotBlank() }
            val at = seg.indexOf("playlist")
            val id = if (at >= 0) seg.getOrNull(at + 1)?.substringBefore('?')?.substringBefore('#').orEmpty() else ""
            return if (SPOTIFY_ID.matches(id)) PlaylistLink.Spotify(id) else PlaylistLink.Unknown
        }
        return PlaylistLink.Unknown
    }
}

/** A Spotify embed page read: the playlist's name, its songs, how many it declares, and the page's anonymous token. */
data class SpotifyEmbedResult(
    val name: String,
    val tracks: List<ImportedSong>,
    val declaredTotal: Int,
    val accessToken: String?,
)

/** One page of the playlist-tracks answer the embed token can ask for. */
data class SpotifyApiPage(val tracks: List<ImportedSong>, val total: Int, val hasNext: Boolean)

/**
 * Reading a public Spotify playlist with no account: the embed page carries the track list
 * in its `__NEXT_DATA__` JSON, and the anonymous token on the same page can ask Spotify's
 * API for the rest when the list runs past the embed's own cap. Songs that do not parse are
 * counted in the declared total, never silently dropped (the import says "N of M").
 */
object SpotifyEmbed {
    private const val MARKER = "id=\"__NEXT_DATA__\""

    /** The whole embed page's HTML → its playlist, or null when the page is not one. */
    fun parse(html: String): SpotifyEmbedResult? {
        val at = html.indexOf(MARKER)
        if (at < 0) return null
        val start = html.indexOf('>', at) + 1
        val end = html.indexOf("</script>", start)
        if (start <= 0 || end < 0) return null
        val root = runCatching { JSONObject(html.substring(start, end)) }.getOrNull() ?: return null
        return parseData(root)
    }

    /** The `__NEXT_DATA__` JSON → the playlist. Pure: the parser's tests drive this. */
    fun parseData(root: JSONObject): SpotifyEmbedResult? {
        val state = root.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state") ?: return null
        val entity = state.optJSONObject("data")?.optJSONObject("entity") ?: return null
        val list = entity.optJSONArray("trackList") ?: return null
        val tracks = (0 until list.length()).mapNotNull { i -> runCatching { embedTrack(list.getJSONObject(i)) }.getOrNull() }
        val token = state.optJSONObject("settings")?.optJSONObject("session")?.optString("accessToken")?.takeIf { it.isNotBlank() }
        val declared = entity.optInt("trackCount", list.length()).takeIf { it > 0 } ?: list.length()
        return SpotifyEmbedResult(entity.optString("name"), tracks, maxOf(declared, list.length()), token)
    }

    private fun embedTrack(o: JSONObject): ImportedSong {
        val uri = o.getString("uri")
        require(uri.startsWith("spotify:track:"))
        // The embed's durations are milliseconds; a value in seconds (a short one) is read as such.
        val raw = o.optLong("duration")
        return ImportedSong(
            key = "", spotifyTrackId = uri.substringAfterLast(':'),
            title = o.getString("title"), artist = o.optString("subtitle"),
            album = "", durationMs = if (raw in 1..9_999) raw * 1000 else raw, coverUrl = "",
        )
    }

    /** One `GET /v1/playlists/<id>/tracks` answer → its songs (removed tracks skipped), its total, whether more pages follow. */
    fun parseApiPage(o: JSONObject): SpotifyApiPage {
        val items = o.optJSONArray("items")
        val tracks = (0 until (items?.length() ?: 0)).mapNotNull { i ->
            runCatching {
                val t = items!!.getJSONObject(i).getJSONObject("track")
                val album = t.optJSONObject("album")
                val images = album?.optJSONArray("images")
                var cover = ""
                var best = Int.MAX_VALUE
                for (j in 0 until (images?.length() ?: 0)) {
                    val im = images!!.getJSONObject(j)
                    val w = im.optInt("width", 640)
                    if (w in 200..400) { cover = im.optString("url"); best = 0; break }
                    if (w < best) { best = w; cover = im.optString("url") }
                }
                ImportedSong(
                    key = "", spotifyTrackId = t.getString("id"),
                    title = t.getString("name"),
                    artist = t.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") } }.orEmpty(),
                    album = album?.optString("name").orEmpty(),
                    durationMs = t.optLong("duration_ms"), coverUrl = cover,
                    // The API's track object carries the recording's ISRC (the embed page
                    // never does): kept normalized, as every other ISRC in the app is.
                    isrc = t.optJSONObject("external_ids")?.optString("isrc")?.let(OfflineDownloader::normalizeIsrc).orEmpty(),
                )
            }.getOrNull()
        }
        return SpotifyApiPage(tracks, o.optInt("total", tracks.size), o.optString("next").isNotBlank())
    }

    /** How a song is matched across the two readings: its Spotify id, or — when either
     * reading lacks one — its normalized title and artist. */
    private fun nameKey(s: ImportedSong): String =
        s.title.trim().lowercase().replace(Regex("\\s+"), " ") + "|" +
            s.artist.trim().lowercase().replace(Regex("\\s+"), " ")

    /** [base] (an embed row) completed by [rich] (the API's row of the same song): every
     * field the API's reading carries wins, the embed's stands where the API has none. */
    private fun overlay(base: ImportedSong, rich: ImportedSong) = ImportedSong(
        key = base.key,
        spotifyTrackId = rich.spotifyTrackId.ifBlank { base.spotifyTrackId },
        title = rich.title.ifBlank { base.title },
        artist = rich.artist.ifBlank { base.artist },
        album = rich.album.ifBlank { base.album },
        durationMs = rich.durationMs.takeIf { it > 0 } ?: base.durationMs,
        coverUrl = rich.coverUrl.ifBlank { base.coverUrl },
        isrc = rich.isrc.ifBlank { base.isrc },
    )

    /**
     * The two readings of one playlist, merged song by song: the embed's order stands
     * (it is the playlist's order), each embed row is completed by the API's row of the
     * same song, and songs only the API reached are appended in API order. A partial or
     * failed API read therefore completes the songs it did return instead of the fuller
     * reading winning wholesale — the old either/or lost every cover and album name
     * whenever the API's list came back shorter than the embed's.
     */
    fun mergeReads(embed: List<ImportedSong>, api: List<ImportedSong>): List<ImportedSong> {
        if (api.isEmpty()) return embed
        if (embed.isEmpty()) return api
        val byId = HashMap<String, Int>()
        val byName = HashMap<String, Int>()
        api.forEachIndexed { i, s ->
            if (s.spotifyTrackId.isNotBlank()) byId.putIfAbsent(s.spotifyTrackId, i)
            byName.putIfAbsent(nameKey(s), i)
        }
        val used = HashSet<Int>()
        val matchedIds = HashSet<String>()
        val merged = embed.map { e ->
            val at = if (e.spotifyTrackId.isNotBlank()) byId[e.spotifyTrackId] else byName[nameKey(e)]
            if (at == null) e else {
                used += at
                api[at].spotifyTrackId.takeIf { it.isNotBlank() }?.let { matchedIds += it }
                overlay(e, api[at])
            }
        }
        // An API row is appended only when no embed row took it — a song the API lists
        // twice completes the embed's row once and is not appended as a second copy
        // (the store keeps a song at most once per playlist anyway).
        return merged + api.filterIndexed { i, s -> i !in used && (s.spotifyTrackId.isBlank() || s.spotifyTrackId !in matchedIds) }
    }
}

/** Fetching a public playlist's songs. The result always knows how many the source declared. */
object PlaylistImport {
    data class Imported(val name: String, val songs: List<ImportedSong>, val total: Int)

    private val http by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    private fun get(url: String, token: String? = null): String {
        val req = Request.Builder().url(url).header("User-Agent", UA)
            .apply { if (token != null) header("Authorization", "Bearer $token") }.build()
        return http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            r.body.string()
        }
    }

    /**
     * The playlist [id]'s songs: the embed page first; when its anonymous token is there, the
     * API's pages too (the embed caps its own list), and the two readings are merged song by
     * song ([SpotifyEmbed.mergeReads]) — the embed's rows carry no album, cover or ISRC, the
     * API's carry all three, and neither reading is guaranteed complete.
     */
    suspend fun fetchSpotify(id: String): Imported = withContext(Dispatchers.IO) {
        val embed = SpotifyEmbed.parse(get("https://open.spotify.com/embed/playlist/$id"))
            ?: throw IOException("Spotify did not answer with a playlist")
        var songs = embed.tracks
        var total = embed.declaredTotal
        val token = embed.accessToken
        if (!token.isNullOrBlank()) runCatching {
            val all = ArrayList<ImportedSong>()
            var offset = 0
            var apiTotal = total
            while (true) {
                val page = SpotifyEmbed.parseApiPage(JSONObject(get("https://api.spotify.com/v1/playlists/$id/tracks?limit=100&offset=$offset", token)))
                apiTotal = page.total
                all += page.tracks
                if (!page.hasNext || all.size >= apiTotal || offset > 10_000) break
                offset += 100
            }
            songs = SpotifyEmbed.mergeReads(embed.tracks, all)
            total = maxOf(total, apiTotal)
        }
        Imported(embed.name, songs, maxOf(total, songs.size))
    }
}
