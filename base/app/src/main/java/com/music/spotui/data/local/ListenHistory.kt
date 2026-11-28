package com.music.spotui.data.local

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Every listen on this phone, whatever the source (Spotify, downloads, local files), for the
 * in-app recap. Stays on the phone; nothing is reported to Spotify or anyone else.
 * A play counts the moment a song starts (v34, Tyson's rule — it used to count only after
 * 30 s, and only once the next song began): [beginListen] writes the record at the start,
 * and the time it plays for is written into that same record as it accrues ([updateOpenListen],
 * [endListen]), so the recap's minutes stay honest and there is exactly one record per play.
 * Records written by older versions (closed at the next song, 30 s or more) read the same.
 */
object ListenHistory {
    enum class Source { SPOTIFY, DOWNLOAD, LOCAL }

    data class Listen(
        val atMs: Long,
        val key: String,
        val title: String,
        val artist: String,
        val album: String,
        val cover: String,
        val playedMs: Long,
        val source: Source,
        /** Each credited artist on its own, for the top artists; just the line's first one for older listens. */
        val artists: List<String> = listOf(artist.substringBefore(",").trim()),
    )

    /**
     * The artists of a song credited as [line] ("A, B") with [ids] artist ids: split only when the
     * names and the ids agree in number, so a name with a comma in it ("Tyler, The Creator", one id)
     * stays whole.
     */
    fun artistsOf(line: String, ids: List<String>): List<String> {
        val names = line.split(", ").map { it.trim() }.filter { it.isNotEmpty() }
        return if (ids.size > 1 && names.size == ids.size) names else listOf(line.trim()).filter { it.isNotEmpty() }
    }

    private var file: File? = null
    private val lock = Any()

    fun init(context: Context) {
        file = File(context.filesDir, "listen_history.jsonl")
    }

    private fun lineOf(listen: Listen): String = JSONObject()
        .put("at", listen.atMs).put("key", listen.key).put("title", listen.title).put("artist", listen.artist)
        .put("album", listen.album).put("cover", listen.cover).put("ms", listen.playedMs).put("src", listen.source.name)
        .put("artists", org.json.JSONArray(listen.artists))
        .toString()

    /*
     * The record of the play now open (begun, not yet ended): its line is the file's last one,
     * so its played time is written back into that line in place. [openOffset] is the byte
     * offset of the line, [openAtMs]/[openKey] say whose it is; both are forgotten whenever
     * the file is put aside or taken back (park/claim/clear), where offsets stop meaning
     * anything. The in-memory copy in [loaded] is updated alongside either way.
     */
    private var openAtMs = -1L
    private var openKey: String? = null
    private var openOffset = -1L

    private fun forgetOpen() { openAtMs = -1L; openKey = null; openOffset = -1L }

    /**
     * A play of [listen] starts now: its record is written at once (its [Listen.playedMs] is
     * the caller's start value, 0). False when there is nothing to write to or no title: the
     * play is then not counted, and the updates that follow it are no-ops.
     */
    fun beginListen(listen: Listen): Boolean {
        if (listen.title.isBlank()) return false
        val f = file ?: return false
        synchronized(lock) {
            runCatching {
                openOffset = if (f.exists()) f.length() else 0L
                f.appendText(lineOf(listen) + "\n")
                openAtMs = listen.atMs; openKey = listen.key
                loaded?.let { loaded = it + listen }
            }.onFailure { forgetOpen(); return false }
        }
        return true
    }

    /** The open play has played [playedMs] so far: its record (in memory and on disk) says so. */
    fun updateOpenListen(playedMs: Long) {
        synchronized(lock) {
            val at = openAtMs; val key = openKey ?: return@synchronized
            loaded?.let { list ->
                val i = list.indexOfLast { it.atMs == at && it.key == key }
                if (i >= 0 && list[i].playedMs != playedMs) loaded = list.toMutableList().also { it[i] = list[i].copy(playedMs = playedMs) }
            }
            val f = file ?: return@synchronized
            val offset = openOffset
            if (offset < 0 || !f.exists() || f.length() <= offset) return@synchronized
            runCatching {
                java.io.RandomAccessFile(f, "rw").use { raf ->
                    raf.seek(offset)
                    // The open play's line is the file's last: read it as UTF-8 bytes
                    // (RandomAccessFile.readLine would mangle a non-ASCII title).
                    val raw = ByteArray((raf.length() - offset).toInt().coerceAtLeast(0))
                    raf.readFully(raw)
                    val head = String(raw, Charsets.UTF_8).substringBefore('\n')
                    if (head.isBlank()) return@runCatching
                    val o = JSONObject(head)
                    // The line there is no longer the open play's (the file moved under it):
                    // leave the disk alone rather than write over another record.
                    if (o.getLong("at") != at || o.getString("key") != key) { openOffset = -1L; return@runCatching }
                    val current = Listen(at, key, o.getString("title"), o.getString("artist"), o.optString("album"),
                        o.optString("cover"), o.getLong("ms"), Source.valueOf(o.optString("src", "SPOTIFY")),
                        o.optJSONArray("artists")?.let { a -> List(a.length()) { a.getString(it) } }
                            ?: listOf(o.getString("artist").substringBefore(",").trim()))
                    val bytes = (lineOf(current.copy(playedMs = playedMs)) + "\n").toByteArray(Charsets.UTF_8)
                    raf.seek(offset)
                    raf.write(bytes)
                    raf.setLength(offset + bytes.size)
                }
            }
        }
    }

    /** The open play is over: [playedMs] is its final played time. */
    fun endListen(playedMs: Long) {
        updateOpenListen(playedMs)
        synchronized(lock) { forgetOpen() }
    }

    /** The history as last read, kept while the file only grows by [beginListen]: not read again for every screen. */
    private var loaded: List<Listen>? = null

    fun all(): List<Listen> = synchronized(lock) {
        loaded?.let { return it }
        val f = file?.takeIf { it.exists() } ?: return emptyList()
        val list = f.readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                val artist = o.optString("artist")
                val artists = o.optJSONArray("artists")?.let { a -> List(a.length()) { a.getString(it) } }?.filter { it.isNotBlank() }
                Listen(o.getLong("at"), o.getString("key"), o.getString("title"), artist, o.optString("album"),
                    o.optString("cover"), o.getLong("ms"), Source.valueOf(o.optString("src", "SPOTIFY")),
                    artists?.ifEmpty { null } ?: listOf(artist.substringBefore(",").trim()))
            }.getOrNull()
        }
        loaded = list
        list
    }

    fun clear() = synchronized(lock) { loaded = null; forgetOpen(); file?.delete(); owner()?.delete() }

    /*
     * The history belongs to the account it was heard on, as the downloads do (OfflineAccounts): on an
     * account switch it is put aside under that account's key (`listen-history/<key>.jsonl`) and the
     * next account's comes back. A history from before has no owner and goes to the first account read.
     */
    private fun owner(): File? = file?.let { File(it.parentFile, "listen_history.owner") }
    private fun parked(key: String): File? = file?.let { File(it.parentFile, "listen-history/$key.jsonl") }
    private fun ownerKey(): String? = owner()?.takeIf { it.isFile }?.let { runCatching { it.readText().trim() }.getOrNull() }?.ifBlank { null }

    /** [from] is put after what [to] already holds (a copy left by an earlier move that went wrong), then removed. */
    private fun moveInto(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (!to.exists() && from.renameTo(to)) return
        to.appendText(from.readText())
        from.delete()
    }

    /** Leaving the account [key] (its stamp wins): its history goes aside, the next account starts with none. */
    fun park(key: String?) = synchronized(lock) {
        val f = file ?: return@synchronized
        val owner = ownerKey() ?: key ?: "unclaimed-${System.currentTimeMillis()}"
        runCatching { if (f.exists()) moveInto(f, parked(owner)!!) }
        owner()?.delete()
        loaded = null
        forgetOpen()
    }

    /** The account [key] is signed in: its own history is the one shown; one with no owner yet becomes its own. */
    fun claim(key: String) = synchronized(lock) {
        val f = file ?: return@synchronized
        val owner = ownerKey()
        if (owner == key) return@synchronized
        runCatching {
            if (owner != null && f.exists()) moveInto(f, parked(owner)!!)
            parked(key)?.takeIf { it.exists() }?.let { moveInto(it, f) }
            owner()?.writeText(key)
        }
        loaded = null
        forgetOpen()
    }

    /** The account [key] is removed from the phone: its history put aside goes with it. */
    fun forget(key: String) = synchronized(lock) { parked(key)?.delete(); Unit }

    data class Ranked(val name: String, val subtitle: String, val cover: String, val count: Int, val minutes: Long)

    data class Recap(
        val listens: Int,
        val minutes: Long,
        val bySource: Map<Source, Long>,
        val topTracks: List<Ranked>,
        val topArtists: List<Ranked>,
        val topAlbums: List<Ranked>,
    )

    /** One calendar week of listening: its first day (local midnight, epoch ms) and the minutes played in it. */
    data class Week(val startMs: Long, val minutes: Long)

    /** Minutes per week over the last [weeks] calendar weeks, oldest first, the current week last. */
    fun weekly(weeks: Int = 8, nowMs: Long = System.currentTimeMillis()): List<Week> = weekly(all(), weeks, nowMs)

    /**
     * Groups [listens] by calendar week (weeks start on the locale's first day, in [zone]); the
     * current, unfinished week is last. Weeks without listens count 0.
     */
    fun weekly(
        listens: List<Listen>,
        weeks: Int,
        nowMs: Long,
        zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
        firstDayOfWeek: Int = java.util.Calendar.getInstance().firstDayOfWeek,
    ): List<Week> {
        val cal = java.util.Calendar.getInstance(zone).apply {
            this.firstDayOfWeek = firstDayOfWeek
            timeInMillis = nowMs
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            while (get(java.util.Calendar.DAY_OF_WEEK) != firstDayOfWeek) add(java.util.Calendar.DAY_OF_MONTH, -1)
        }
        // Week starts, newest first; calendar arithmetic keeps daylight-saving weeks right.
        val starts = (0 until weeks).map { i ->
            if (i > 0) cal.add(java.util.Calendar.WEEK_OF_YEAR, -1)
            cal.timeInMillis
        }.reversed()
        val ends = starts.drop(1) + Long.MAX_VALUE
        return starts.indices.map { i ->
            val ms = listens.filter { it.atMs >= starts[i] && it.atMs < ends[i] && it.atMs <= nowMs }.sumOf { it.playedMs }
            Week(starts[i], ms / 60_000)
        }
    }

    /** Every credited artist of a listen counts it, not only the first one on the line. */
    internal fun rankArtists(list: List<Listen>): List<Ranked> = list
        .flatMap { l -> l.artists.filter { it.isNotBlank() }.map { it to l } }
        .groupBy({ it.first }, { it.second })
        .map { (name, l) -> Ranked(name, "", l.first().cover, l.size, l.sumOf { it.playedMs } / 60_000) }
        .sortedWith(compareByDescending<Ranked> { it.count }.thenByDescending { it.minutes })
        .take(10)

    /** Totals and top lists for listens since [sinceMs]. */
    fun recap(sinceMs: Long): Recap {
        val list = all().filter { it.atMs >= sinceMs }
        fun rank(group: (Listen) -> String, subtitle: (Listen) -> String) = list.groupBy(group)
            .filterKeys { it.isNotBlank() }
            .map { (name, l) -> Ranked(name, subtitle(l.first()), l.first().cover, l.size, l.sumOf { it.playedMs } / 60_000) }
            .sortedWith(compareByDescending<Ranked> { it.count }.thenByDescending { it.minutes })
            .take(10)
        return Recap(
            listens = list.size,
            minutes = list.sumOf { it.playedMs } / 60_000,
            bySource = list.groupBy { it.source }.mapValues { (_, l) -> l.sumOf { it.playedMs } / 60_000 },
            topTracks = rank({ it.title + "\u0000" + it.artist }, { it.artist }).map { it.copy(name = it.name.substringBefore('\u0000')) },
            topArtists = rankArtists(list),
            topAlbums = rank({ it.album }, { it.artist }),
        )
    }
}
