package com.music.spotui.data.api

import com.music.spotui.data.local.ImportedSong
import com.music.spotui.data.offline.OfflineDownloader

/**
 * A playlist file from a transfer service, read: the fallback for any playlist the
 * link imports cannot reach (a private Amazon Music playlist above all). Soundiiz,
 * TuneMyMusic and FreeYourMusic all export a playlist as CSV with a header row; the
 * dialects differ in the header's words and order, so the columns are found by name —
 * case, spacing and order aside — and a file whose header names no title column is not
 * a playlist export and is refused whole, never half-imported.
 *
 * What a row keeps: the title, the artist, the album, the duration, and the ISRC
 * exactly as the file states it (normalized as every ISRC in the app is) — an exported
 * ISRC is the recording's own, so it is never second-guessed. A track id in Amazon's
 * shape is kept as the row's ASIN, its identity at its source. The songs land in the
 * same imported-playlist path as a link's, catalog completion included.
 */
object PlaylistCsv {

    /** A file read: the playlist's name ("" when the file states none) and its songs. */
    data class Parsed(val name: String, val songs: List<ImportedSong>)

    private val TITLE = setOf("title", "track", "trackname", "tracktitle", "song", "songtitle", "name")
    private val ARTIST = setOf("artist", "artists", "artistname", "artistnames")
    private val ALBUM = setOf("album", "albumname")
    private val ISRC = setOf("isrc")
    private val DURATION = setOf("duration", "durationms", "length", "tracklength", "time")
    private val PLAYLIST = setOf("playlist", "playlistname")
    private val TRACK_ID = setOf("trackid", "amazonid")
    private val ASIN_ID = setOf("asin", "amazontrackid")
    private val URL = setOf("url", "link", "trackurl", "amazontracklink", "amazonlink")

    /** An ASIN as the files state one: ten letters/digits; a track's begins with B. */
    private val ASIN_SHAPE = Regex("[A-Z0-9]{10}")
    private val TRACK_ASIN_SHAPE = Regex("B[A-Z0-9]{9}")
    private val TRACK_ASIN = Regex("""trackAsin=([A-Za-z0-9]{10})""")

    /**
     * The file's whole text → its playlist, or null when it is not a playlist export
     * this reader knows. [fallbackName] names the playlist when the file does not (the
     * picked file's own name, usually).
     */
    fun parse(text: String, fallbackName: String = ""): Parsed? {
        val body = text.removePrefix("\uFEFF")
        if (body.isBlank()) return null
        val delimiter = sniffDelimiter(body)
        val records = tokenize(body, delimiter).filter { rec -> rec.any { it.isNotBlank() } }
        if (records.isEmpty()) return null

        val headerAt = records.indices.firstOrNull { i ->
            val cells = records[i].map(::normalizeHeader)
            TITLE.any { it in cells } && (ARTIST.any { it in cells } || ALBUM.any { it in cells } || ISRC.any { it in cells })
        }
        if (headerAt == null) return parseHeaderless(body, fallbackName)

        val header = records[headerAt].map(::normalizeHeader)
        fun column(aliases: Set<String>): Int = header.indexOfFirst { it in aliases }
        val title = column(TITLE)
        if (title < 0) return null
        val artist = column(ARTIST)
        val album = column(ALBUM)
        val isrc = column(ISRC)
        val duration = column(DURATION)
        val playlist = column(PLAYLIST)
        val trackId = column(TRACK_ID)
        val asinId = column(ASIN_ID)
        val url = column(URL)

        var name = ""
        val songs = ArrayList<ImportedSong>()
        for (rec in records.drop(headerAt + 1)) {
            fun cell(at: Int): String = if (at < 0) "" else rec.getOrNull(at)?.trim().orEmpty()
            val songTitle = cell(title)
            if (songTitle.isBlank()) continue
            if (name.isBlank()) name = cell(playlist)
            // The row's ASIN: stated in its own column (FreeYourMusic), or the track id
            // when it is ASIN-shaped (Soundiiz, for an Amazon playlist), or in the
            // song's own Amazon link. Another service's track id is none of these and
            // is not kept — it is not this row's identity anywhere here.
            val asin = cell(asinId).uppercase().takeIf { ASIN_SHAPE.matches(it) }
                ?: cell(trackId).uppercase().takeIf { TRACK_ASIN_SHAPE.matches(it) }
                ?: TRACK_ASIN.find(cell(url))?.groupValues?.get(1).orEmpty()
            songs += ImportedSong(
                key = "", spotifyTrackId = "",
                title = songTitle, artist = cell(artist), album = cell(album),
                durationMs = parseDuration(cell(duration)),
                coverUrl = "",
                isrc = OfflineDownloader.normalizeIsrc(cell(isrc)).orEmpty(),
                asin = asin,
            )
        }
        if (songs.isEmpty()) return null
        return Parsed(name.ifBlank { fallbackName }, songs)
    }

    /**
     * A plain text file with no header (a TuneMyMusic TXT): one song a line, written
     * "Artist - Title". A line that does not read that way is skipped; a file with no
     * such line at all is not a playlist.
     */
    private fun parseHeaderless(body: String, fallbackName: String): Parsed? {
        val songs = ArrayList<ImportedSong>()
        for (line in body.lineSequence()) {
            val text = line.trim()
            if (text.isBlank()) continue
            val at = listOf(text.indexOf(" - "), text.indexOf(" – "), text.indexOf(" — "))
                .filter { it > 0 }.minOrNull() ?: continue
            val artist = text.substring(0, at).trim()
            val title = text.substring(at + 3).trim()
            if (artist.isBlank() || title.isBlank()) continue
            songs += ImportedSong("", "", title, artist, "", 0L, "")
        }
        return if (songs.isEmpty()) null else Parsed(fallbackName, songs)
    }

    /** A header cell as its alias key: lowercased, nothing but letters and digits. */
    private fun normalizeHeader(cell: String): String =
        cell.trim().lowercase().filter { it.isLetterOrDigit() }

    /** The delimiter the file's first line separates its fields with: tab, semicolon or comma. */
    private fun sniffDelimiter(body: String): Char {
        val first = body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        var tabs = 0; var semis = 0; var commas = 0; var quoted = false
        for (c in first) {
            if (c == '"') quoted = !quoted
            if (!quoted) when (c) { '\t' -> tabs++; ';' -> semis++; ',' -> commas++ }
        }
        return when {
            tabs > 0 && tabs >= semis && tabs >= commas -> '\t'
            semis > commas -> ';'
            else -> ','
        }
    }

    /**
     * The file as records of fields: quoted fields keep their commas, line breaks and
     * doubled quotes; a quote only opens a field at its start. Malformed quoting never
     * throws — the worst it yields is a field the header check then refuses.
     */
    internal fun tokenize(text: String, delimiter: Char): List<List<String>> {
        val records = ArrayList<List<String>>()
        var record = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var fieldStart = true
        var i = 0
        fun endField() { record += field.toString(); field.clear(); fieldStart = true }
        fun endRecord() { endField(); records += record; record = ArrayList() }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted -> when (c) {
                    '"' -> if (text.getOrNull(i + 1) == '"') { field.append('"'); i++ } else quoted = false
                    else -> field.append(c)
                }
                c == '"' && fieldStart -> { quoted = true; fieldStart = false }
                c == delimiter -> endField()
                c == '\r' -> { if (text.getOrNull(i + 1) == '\n') i++; endRecord() }
                c == '\n' -> endRecord()
                else -> { field.append(c); fieldStart = false }
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) endRecord()
        return records
    }

    /**
     * A duration as the exports write it: "3:45" (or "1:02:30"), or a bare number —
     * seconds in Soundiiz and FreeYourMusic files, so a number under 20,000 reads as
     * seconds and a larger one as the milliseconds it must be. Unreadable: 0, unknown —
     * a duration is a matching hint, never worth refusing a song over.
     */
    internal fun parseDuration(raw: String): Long {
        val s = raw.trim()
        if (s.isBlank()) return 0L
        if (':' in s) {
            val parts = s.split(':').map { it.trim().toLongOrNull() ?: return 0L }
            return parts.fold(0L) { acc, part -> acc * 60 + part } * 1000
        }
        val number = s.toDoubleOrNull() ?: return 0L
        return if (number >= 20_000) number.toLong() else (number * 1000).toLong()
    }
}
