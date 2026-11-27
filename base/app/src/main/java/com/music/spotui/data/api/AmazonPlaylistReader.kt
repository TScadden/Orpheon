package com.music.spotui.data.api

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.spotui.data.local.ImportedSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Reading an Amazon Music playlist on the phone.
 *
 * Why a rendered page at all: there is no Spotify-style reading of an Amazon link. Its
 * API is closed and authorized-only, and the share page's own HTML is an empty app
 * shell — no track list, no guest token. But the web player renders a public playlist's
 * track list into its page: title, artist, album and duration as text, and each song's
 * ASIN in its album link (`/albums/<album>?trackAsin=<asin>`). So the link is loaded in
 * a WebView the import dialog shows at full size, the rendered page is read out of it,
 * and long lists are scrolled into view a screen at a time and collected.
 *
 * The page is visible from the start (v26) because the signed-out read can meet walls
 * only the user can clear: a bot check, or Amazon asking for a sign-in — a playlist
 * that is not public is invisible signed out by Amazon's design, and its owner signs
 * in on Amazon's own page in this same WebView (the app never sees the password), taps
 * Continue, and the read finishes as him. Every script the read runs has a deadline,
 * the read as a whole has one too, and a read that still fails says what the page
 * actually was (see [AmazonReadout]) instead of guessing.
 *
 * Everything Amazon-specific lives in this one file — the page queries in [AmazonDom],
 * the scripts below — because Amazon rebuilt its player's insides as recently as
 * October 2026 and will change them again: when this read breaks, this file is the fix.
 * Amazon states no ISRC, so the rows carry none; the import completes them from the
 * app's own catalog (CatalogCompletion).
 */

/** Which wall, if any, a rendered page shows instead of a playlist. */
enum class AmazonWall { NONE, CAPTCHA, SIGN_IN }

/**
 * What the import dialog's status line says, as a state of its own, so the mapping
 * from the read's facts to the line the user reads is testable without a phone.
 */
enum class AmazonStage {
    LOADING_PAGE, WAITING_FOR_LIST, FOUND_SONGS, COLLECTING, NEEDS_SIGN_IN, NEEDS_CAPTCHA, DONE, FAILED,
}

/** The status while songs are being gathered: still waiting, part-way, or all here. */
internal fun amazonPollStage(found: Int, total: Int): AmazonStage = when {
    found <= 0 -> AmazonStage.WAITING_FOR_LIST
    total > found -> AmazonStage.COLLECTING
    else -> AmazonStage.FOUND_SONGS
}

/** The status for a wall the user can tap through. */
internal fun amazonWallStage(wall: AmazonWall): AmazonStage = when (wall) {
    AmazonWall.CAPTCHA -> AmazonStage.NEEDS_CAPTCHA
    AmazonWall.SIGN_IN -> AmazonStage.NEEDS_SIGN_IN
    AmazonWall.NONE -> AmazonStage.WAITING_FOR_LIST
}

/**
 * One script run in the page, behind a seam: the read depends on this, not on the
 * WebView directly, so the deadline behaviour is testable without a phone. The
 * callback receives the script's string result, already decoded, or null.
 */
fun interface JsEvaluator {
    fun evaluate(script: String, callback: (String?) -> Unit)
}

/**
 * [JsEvaluator.evaluate] with a deadline: a page that never answers (busy, navigated
 * away, a script that threw) reads as null, never as a hang.
 */
suspend fun runJs(evaluator: JsEvaluator, script: String, timeoutMs: Long): String? =
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            try {
                evaluator.evaluate(script) { value ->
                    if (cont.isActive) cont.resume(value)
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

/**
 * The facts of one read, kept so a failure can say what the page actually was —
 * its title, how much of it could be read, how many rows it held, which wall it
 * showed — instead of the import guessing at a cause.
 */
data class AmazonReadout(
    val url: String,
    val pageTitle: String,
    val pageUrl: String,
    val htmlBytes: Int?,
    val rowsFound: Int,
    val wall: AmazonWall,
    val loadOutcome: String,
    val lastPoll: String,
    /** The first poll met an empty shell and the page was reloaded once (see the session). */
    val reloaded: Boolean = false,
    /**
     * The scroll collection's journal, one line per pass ([CollectPass.line]):
     * which scrollers were stepped, how each moved, and the bottom verdict it
     * was given. Kept for completed reads as much as failed ones — the confirm
     * step's Read details shows it — because a collection that stops early is
     * diagnosed from exactly this.
     */
    val journal: List<String> = emptyList(),
) {
    fun text(): String = buildString {
        appendLine("AMAZON READ")
        appendLine("url: $url")
        appendLine("page title: ${pageTitle.ifBlank { "(none)" }}")
        appendLine("page url: ${pageUrl.ifBlank { "(none)" }}")
        appendLine("html size: ${htmlBytes?.let { "$it bytes" } ?: "unreadable"}")
        appendLine("rows found: $rowsFound")
        appendLine(
            "wall: " + when (wall) {
                AmazonWall.CAPTCHA -> "captcha"
                AmazonWall.SIGN_IN -> "sign-in"
                AmazonWall.NONE -> "neither"
            },
        )
        appendLine("load: $loadOutcome")
        appendLine("reloaded once: ${if (reloaded) "yes" else "no"}")
        appendLine("last poll: $lastPoll")
        if (journal.isNotEmpty()) {
            appendLine("collect journal:")
            journal.forEach { appendLine("  $it") }
        }
    }.trimEnd()
}

object AmazonDom {

    /**
     * One rendered playlist page, read: its name, its rows, and how many songs it
     * declares — null when it states none. The total is never defaulted to the
     * rows at hand: a virtualized list shows a window, and the window's size
     * masquerading as the playlist's size is how a 9-song read of an 84-song
     * list came to print "9 of 9 songs read" (v29) — a completeness the read
     * never verified. Unknown stays unknown, and the summary says so.
     */
    data class Page(val name: String, val declaredTotal: Int?, val rows: List<ImportedSong>)

    private val TRACK_ASIN = Regex("""trackAsin=([A-Za-z0-9]{10})""")
    private val ANCHOR = Regex(
        """<a\b[^>]*?href\s*=\s*"([^"]*)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val IMAGE = Regex("""<img\b[^>]*?\bsrc\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val DURATION = Regex("""(?<!\d)(\d{1,3}):([0-5]\d)(?!\d)""")
    private val DURATION_LINE = Regex("""\d{1,3}:[0-5]\d""")
    private val TAG = Regex("""<[^>]+>""")
    private val SONG_COUNT = Regex("""(\d[\d,]*)\s+(?i:songs?)\b""")
    private val META_TAG = Regex("""<meta\b[^>]*\bog:title\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val CONTENT = Regex("""content\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val TITLE_TAG = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** How far around a row's title link its own cover and fields may sit. */
    private const val WINDOW = 6_000

    /** Words a row shows that are neither its artist nor its album. */
    private val NOT_A_FIELD = setOf(
        "play", "pause", "explicit", "e", "lyrics", "share", "more options", "add to library",
        "remove from library", "go to album", "go to artist", "add to playlist",
    )

    private val CAPTCHA_MARKERS = listOf(
        "captcha", "robot check", "enter the characters you see below", "type the characters you see below",
    )
    private val SIGN_IN_PHRASES = listOf(
        "sign in to listen", "sign in to continue", "sign in to see", "sign in to view", "sign-in required",
    )
    private val SIGN_IN_LINK = Regex("""href\s*=\s*"[^"]*(?:ap/signin|/signin)""", RegexOption.IGNORE_CASE)
    private val SIGN_IN_CTA = Regex(""">\s*sign in\s*<""", RegexOption.IGNORE_CASE)

    /** A line that states a song count ("84 songs", "84 SONGS", "1 song"): the
     * playlist header's own furniture, never a song's title. */
    private val COUNT_LINE = Regex("""^\d[\d,]*\s+songs?$""", RegexOption.IGNORE_CASE)

    /** Whether [title] is a count line — the header read as a row (see [parse]). */
    internal fun isCountLine(title: String): Boolean = COUNT_LINE.matches(title.trim())

    /**
     * A rendered playlist page's HTML (shadow roots inlined — see the reader's
     * serializer) → its rows, by two readings merged. The first is the player's
     * track links (see [parseAnchors]); the second reads the rendered row elements
     * themselves (see [parseRowElements]) for a player whose rows no longer link
     * their songs that way. A song the first reading found is never re-read by the
     * second: the link reading is the authority where it speaks.
     */
    fun parse(html: String): Page {
        val linked = parseAnchors(html)
        val seen = linked.mapTo(HashSet()) { rowKey(it) }
        val rows = (linked + parseRowElements(html).filter { rowKey(it) !in seen })
            // The last lines of defence, behind both readings' own checks: a row
            // whose title merely restates its artist is a misread of the page (the
            // artist line read as the title), never a song; and a row whose title
            // is a count line is the playlist's own header read as a row — it
            // imported as a junk first song titled "84 SONGS" on a phone, ahead
            // of the real first track, which lost its slot to it.
            .filterNot { sameName(it.title, it.artist) || isCountLine(it.title) }
        return Page(name(html), declaredTotalOrNull(html), rows)
    }

    /** A title and an artist that say the same thing (case and spacing aside). */
    internal fun sameName(a: String, b: String): Boolean {
        val x = a.trim().lowercase()
        return x.isNotEmpty() && x == b.trim().lowercase()
    }

    private fun rowKey(s: ImportedSong): String =
        if (s.asin.isNotBlank()) "asin:${s.asin}"
        else "name:${s.title.trim().lowercase()}|${s.artist.trim().lowercase()}"

    /**
     * The link reading: a row is found by its track link — the anchor whose address
     * carries `trackAsin=`. A row can link its song twice (the cover and the title),
     * so anchors are grouped by ASIN and the one carrying text is the title. The
     * artist and album are the row's own links where it has them, else its next
     * lines of text; the duration is the row's m:ss; the cover is the last picture
     * before the title. A row with no title text is not a row and is skipped.
     */
    private fun parseAnchors(html: String): List<ImportedSong> {
        val byAsin = LinkedHashMap<String, MutableList<MatchResult>>()
        for (m in ANCHOR.findAll(html)) {
            val asin = TRACK_ASIN.find(m.groupValues[1])?.groupValues?.get(1) ?: continue
            byAsin.getOrPut(asin) { ArrayList() } += m
        }
        val groups = byAsin.entries.toList()
        return groups.mapIndexedNotNull { i, (asin, anchors) ->
            val titleAnchor = anchors.firstOrNull { cleanText(it.groupValues[2]).isNotBlank() } ?: anchors.first()
            var title = cleanText(titleAnchor.groupValues[2])
            if (title.isBlank()) return@mapIndexedNotNull null
            val titleStart = titleAnchor.range.first
            val titleEnd = titleAnchor.range.last + 1
            val prevTitleEnd = if (i == 0) maxOf(0, titleStart - WINDOW) else groups[i - 1].value.first().range.last + 1
            val segEnd = if (i == groups.lastIndex) minOf(html.length, titleEnd + WINDOW)
                else groups[i + 1].value.first().range.first
            val segment = html.substring(titleEnd, segEnd)

            var artist = ""
            var album = ""
            for (m in ANCHOR.findAll(segment)) {
                val href = m.groupValues[1]
                if (artist.isBlank() && "/artists/" in href) artist = cleanText(m.groupValues[2])
                if (album.isBlank() && "/albums/" in href && "trackAsin" !in href) album = cleanText(m.groupValues[2])
                if (artist.isNotBlank() && album.isNotBlank()) break
            }
            val durationMs = DURATION.find(segment)?.let { (it.groupValues[1].toLong() * 60 + it.groupValues[2].toLong()) * 1000 } ?: 0L
            if (artist.isBlank() || album.isBlank()) {
                val lines = textLines(segment)
                    .filter {
                        it != title && it != artist && it.lowercase() !in NOT_A_FIELD &&
                            !it.matches(DURATION_LINE)
                    }
                var at = 0
                if (artist.isBlank()) artist = lines.getOrNull(at++).orEmpty()
                if (album.isBlank()) album = lines.getOrNull(at).orEmpty()
            }
            if (sameName(title, artist)) {
                // The text-bearing track link carried the artist credit, not the
                // title — the current player's rows link their album that way, the
                // credit sitting where a title was expected. The title is another
                // line of the row's own territory (from this row's first track link
                // to the next row's) that is neither the artist nor the album. No
                // such line, no song: the row is skipped rather than imported
                // under the artist's name.
                val region = html.substring(anchors.first().range.first, segEnd)
                title = textLines(region).firstOrNull {
                    it != artist && it != album && it.lowercase() !in NOT_A_FIELD &&
                        !it.matches(DURATION_LINE)
                } ?: return@mapIndexedNotNull null
            }
            // The header's count line is not this row's title either (the header
            // block can sit inside the first row's territory): never a song.
            if (isCountLine(title)) return@mapIndexedNotNull null
            val cover = IMAGE.findAll(html.substring(prevTitleEnd, titleStart)).lastOrNull()
                ?.groupValues?.get(1)?.takeIf { it.startsWith("http") && "transparent" !in it && "pixel" !in it }
                .orEmpty()
            ImportedSong(
                key = "", spotifyTrackId = "", title = title, artist = artist, album = album,
                durationMs = durationMs, coverUrl = cover, asin = asin,
            )
        }
    }

    private val ANY_TAG = Regex("""<([a-zA-Z][a-zA-Z0-9-]*)\b([^>]*)>""")
    private val ATTR = Regex("""([a-zA-Z][a-zA-Z0-9-]*)\s*=\s*"([^"]*)"""")
    private val ASIN_VALUE = Regex("""^[A-Z0-9]{10}$""")

    /** How far one row element's fields may run before they are another row's. */
    private const val ROW_WINDOW = 8_000

    /**
     * The row-element reading: the player's rendered rows themselves — an element
     * that names itself a track row (a `music-…track…` element, a `data-testid` or
     * class saying track/row, or any element carrying an ASIN attribute). Within
     * one row, the title, artist and album are the elements labelled for them (or
     * the row's own artist/album links); only a row with no labelled fields at all
     * has its plain lines read as artist then album. A row is only a song the
     * import may claim with a real title AND a real artist: anything thinner is
     * left for the catalog completion of a properly read row, never guessed here.
     */
    private fun parseRowElements(html: String): List<ImportedSong> {
        // (where the row's tag starts, where its content starts, its attributes)
        val starts = ArrayList<Triple<Int, Int, Map<String, String>>>()
        for (m in ANY_TAG.findAll(html)) {
            val tag = m.groupValues[1].lowercase()
            val attrs = ATTR.findAll(m.groupValues[2])
                .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            if (isRowElement(tag, attrs)) starts += Triple(m.range.first, m.range.last + 1, attrs)
        }
        return starts.mapIndexedNotNull { i, (_, from, attrs) ->
            val until = if (i == starts.lastIndex) minOf(html.length, from + ROW_WINDOW)
                else starts[i + 1].first
            val segment = html.substring(from, until)

            val asin = attrs.entries.firstOrNull { (k, v) -> "asin" in k && ASIN_VALUE.matches(v) }?.value
                ?: TRACK_ASIN.find(segment)?.groupValues?.get(1)
                ?: ""
            var title = labelledText(segment, "title")
            if (title.isBlank()) title = ANCHOR.find(segment)?.let { cleanText(it.groupValues[2]) }.orEmpty()
            var artist = ""
            var album = ""
            for (m in ANCHOR.findAll(segment)) {
                val href = m.groupValues[1]
                if (artist.isBlank() && "/artists/" in href) artist = cleanText(m.groupValues[2])
                if (album.isBlank() && "/albums/" in href && "trackAsin" !in href) album = cleanText(m.groupValues[2])
                if (artist.isNotBlank() && album.isNotBlank()) break
            }
            if (artist.isBlank()) artist = labelledText(segment, "artist")
            if (album.isBlank()) album = labelledText(segment, "album")
            if (artist.isBlank() && album.isBlank()) {
                val lines = textLines(segment)
                    .filter {
                        it != title && it.lowercase() !in NOT_A_FIELD && !it.matches(DURATION_LINE)
                    }
                artist = lines.getOrNull(0).orEmpty()
                album = lines.getOrNull(1).orEmpty()
            }
            if (sameName(title, artist)) {
                // As in the link reading: the slot read as the title held the artist
                // credit. The title is another line of the row itself, neither the
                // artist nor the album; no such line, no song.
                title = textLines(segment).firstOrNull {
                    it != artist && it != album && it.lowercase() !in NOT_A_FIELD &&
                        !it.matches(DURATION_LINE)
                } ?: return@mapIndexedNotNull null
            }
            // The playlist header presents itself as a row element on the current
            // player (its block carries the list's furniture): its "title" is the
            // count line and its "artist" the playlist's top credit. It is the
            // header, never the first song — reading it as one put a junk
            // "84 SONGS" row ahead of the real first track.
            if (isCountLine(title)) return@mapIndexedNotNull null
            if (title.isBlank() || artist.isBlank()) return@mapIndexedNotNull null
            val durationMs = DURATION.find(segment)?.let { (it.groupValues[1].toLong() * 60 + it.groupValues[2].toLong()) * 1000 } ?: 0L
            val cover = IMAGE.find(segment)?.groupValues?.get(1)
                ?.takeIf { it.startsWith("http") && "transparent" !in it && "pixel" !in it }
                .orEmpty()
            ImportedSong(
                key = "", spotifyTrackId = "", title = title, artist = artist, album = album,
                durationMs = durationMs, coverUrl = cover, asin = asin,
            )
        }
    }

    /** An element that presents itself as one track row (never a list or a header). */
    private fun isRowElement(tag: String, attrs: Map<String, String>): Boolean {
        val testid = attrs["data-testid"].orEmpty()
        val cls = attrs["class"].orEmpty()
        if ("tracklist" in testid.lowercase() || "tracklist" in cls.lowercase()) return false
        if ("list" in testid.lowercase() && "track" in testid.lowercase()) return false
        return tag.contains("track") ||
            testid.contains("track", ignoreCase = true) || testid.contains("row", ignoreCase = true) ||
            cls.contains("track-row", ignoreCase = true) || cls.contains("trackrow", ignoreCase = true) ||
            attrs.keys.any { "asin" in it }
    }

    /** The text of the first element whose class or test id names [marker] ("title", "artist", "album"). */
    private fun labelledText(segment: String, marker: String): String {
        val re = Regex(
            """<[a-zA-Z][a-zA-Z0-9-]*\b[^>]*?(?:class|data-testid)\s*=\s*"[^"]*${Regex.escape(marker)}[^"]*"[^>]*>(.*?)</[a-zA-Z][a-zA-Z0-9-]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return re.find(segment)?.let { cleanText(it.groupValues[1]) }.orEmpty()
    }

    /** Which wall a rendered page shows: a bot check, a sign-in gate, or neither. */
    fun wallOf(html: String): AmazonWall = when {
        CAPTCHA_MARKERS.any { html.contains(it, ignoreCase = true) } -> AmazonWall.CAPTCHA
        SIGN_IN_PHRASES.any { html.contains(it, ignoreCase = true) } ||
            SIGN_IN_LINK.containsMatchIn(html) || SIGN_IN_CTA.containsMatchIn(html) -> AmazonWall.SIGN_IN
        else -> AmazonWall.NONE
    }

    /** The page is a bot check (or another wall) rather than a playlist. */
    fun looksBlocked(html: String): Boolean = wallOf(html) == AmazonWall.CAPTCHA

    /** The first row link of the page: an address carrying a track ASIN. */
    private val TRACK_LINK = Regex("""href\s*=\s*"[^"]*trackAsin=""", RegexOption.IGNORE_CASE)

    /** How far from the playlist's own name its header's count may sit (the same block). */
    private const val NAME_WINDOW = 8_000

    /**
     * How many songs the page itself declares, when it declares any. The total is
     * ADVISORY only — it sizes the summary's "of M" and nothing else; collection
     * never ends on it (see [CollectEnd]) — but the summary must still be the
     * header's truth, so the count taken is the LARGEST one stated near the
     * playlist's own name: its header block carries the name and the count
     * together, and the header's 84 must outvote a rail card's incidental 10.
     *
     * The rule this replaces — the last count before the first row link — read
     * 10 for Tyson's 84-song playlist: the player's hero block links the first
     * track ahead of the header, so the only count before that link was a rail
     * card's, the header's own count sat below the link unread, and the confirm
     * step said "10 of 10 songs read" over a short read. When the page's name
     * cannot be found to anchor on, the old region rule stands as the fallback.
     * Null when no count is credible — a reader must not mistake the rows it
     * happened to see for the playlist's size: a virtualized list shows a
     * window, and the window's size is not a total.
     */
    internal fun declaredTotalOrNull(html: String): Int? {
        val counts = SONG_COUNT.findAll(html)
            .map { it.range.first to (it.groupValues[1].replace(",", "").toIntOrNull() ?: 0) }
            .filter { it.second > 0 }.toList()
        if (counts.isEmpty()) return null
        val pageName = name(html)
        if (pageName.isNotBlank()) {
            val nameAt = ArrayList<Int>()
            var from = html.indexOf(pageName, ignoreCase = true)
            while (from >= 0) {
                nameAt += from
                from = html.indexOf(pageName, from + 1, ignoreCase = true)
            }
            val near = counts.filter { (at, _) -> nameAt.any { abs(it - at) <= NAME_WINDOW } }
            if (near.isNotEmpty()) return near.maxOf { it.second }
        }
        val rowsAt = TRACK_LINK.find(html)?.range?.first
        val header = if (rowsAt != null) html.substring(0, rowsAt) else html.take(30_000)
        return SONG_COUNT.findAll(header).lastOrNull()?.groupValues?.get(1)
            ?.replace(",", "")?.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun name(html: String): String {
        val meta = META_TAG.find(html)?.value?.let { CONTENT.find(it)?.groupValues?.get(1) }
        val raw = meta ?: TITLE_TAG.find(html)?.groupValues?.get(1) ?: return ""
        var name = decodeEntities(raw).trim()
        for (suffix in listOf(" on Amazon Music", " | Amazon Music", " - Amazon Music", " : Amazon Music", " — Amazon Music"))
            if (name.endsWith(suffix, ignoreCase = true)) name = name.dropLast(suffix.length).trim()
        if (name.startsWith("Amazon Music", ignoreCase = true))
            name = name.drop("Amazon Music".length).trimStart(' ', ':', '-', '|').trim()
        return name
    }

    /** The visible text lines of an HTML fragment, in order, empties out. */
    private fun textLines(fragment: String): List<String> =
        fragment.replace(TAG, "\n").split('\n').map { cleanText(it) }.filter { it.isNotBlank() }

    /** An HTML fragment as its text: tags out, entities read, whitespace single-spaced. */
    internal fun cleanText(fragment: String): String =
        decodeEntities(fragment.replace(TAG, " ")).replace(Regex("\\s+"), " ").trim()

    internal fun decodeEntities(text: String): String {
        var out = text
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("&#x27;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
        out = Regex("""&#(\d+);""").replace(out) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: it.value }
        out = Regex("""&#x([0-9a-fA-F]+);""").replace(out) {
            it.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: it.value
        }
        return out.replace("&amp;", "&")
    }
}

/**
 * The songs of one playlist, collected over scrolls: the page only renders the rows it
 * has loaded, so each scroll's reading is added to what is already held. A song is told
 * apart by its ASIN, else by its normalized title and artist; a later sighting of a song
 * fills the fields an earlier one lacked (a row read half-rendered, completed when it
 * rendered fully).
 */
class AmazonCollect {
    private val byKey = LinkedHashMap<String, ImportedSong>()

    val songs: List<ImportedSong> get() = byKey.values.toList()

    /** Adds [rows]; returns how many were new. */
    fun add(rows: List<ImportedSong>): Int {
        var added = 0
        for (row in rows) {
            val key = keyOf(row)
            val held = byKey[key]
            if (held == null) { byKey[key] = row; added++ } else byKey[key] = complete(held, row)
        }
        return added
    }

    private fun keyOf(s: ImportedSong): String =
        if (s.asin.isNotBlank()) "asin:${s.asin}"
        else "name:${normalize(s.title)}|${normalize(s.artist)}"

    private fun normalize(s: String): String = s.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun complete(held: ImportedSong, seen: ImportedSong): ImportedSong = held.copy(
        title = held.title.ifBlank { seen.title },
        artist = held.artist.ifBlank { seen.artist },
        album = held.album.ifBlank { seen.album },
        coverUrl = held.coverUrl.ifBlank { seen.coverUrl },
        durationMs = if (held.durationMs > 0) held.durationMs else seen.durationMs,
    )
}

/**
 * When a scroll collection is over, as a decision of its own so it is testable
 * without a phone: the list's true bottom has repeated with no new rows across
 * [bottomStallsToEnd] stepped passes running AND a full [bottomQuietMs] of no
 * growth at the bottom. The time half matters as much as the count: a
 * virtualized player loads its next chunk asynchronously, so an apparent
 * bottom can sit empty for several passes while more rows are on their way —
 * ending on the pass count alone concluded collections inside that lazy-load
 * gap.
 *
 * The page's declared total is NOT a stop rule — it is advisory, sizing only
 * the summary's "of M". It was one until v29, and a misread total ended real
 * collections: the page's chrome stated an incidental 10 ahead of an 84-song
 * header, the reader took 10 as the total, and collection stopped the moment
 * 10 were held. A total can understate (a rail card's count, a stale header)
 * as easily as the window's size can masquerade as one, so the bottom rule —
 * with the session's own pass and time caps behind it — is the only end.
 * [now] is a seam: tests drive the clock.
 */
internal class CollectEnd(
    private val bottomStallsToEnd: Int = 3,
    private val bottomQuietMs: Long = 6_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var stalls = 0
    private var quietSince: Long? = null

    /**
     * Offers one collection pass: [added] rows were new, the list rested
     * [atBottom], the page declares [declaredTotal] songs (null when it states
     * none — carried for the caller's summary, never consulted here) and
     * [collected] are held. True when the collection is over.
     */
    fun offer(added: Int, atBottom: Boolean, declaredTotal: Int?, collected: Int): Boolean {
        if (atBottom && added == 0) {
            stalls++
            val since = quietSince ?: now().also { quietSince = it }
            if (stalls >= bottomStallsToEnd && now() - since >= bottomQuietMs) return true
        } else {
            stalls = 0
            quietSince = null
        }
        return false
    }
}

/**
 * How a finished read is stated, as a value of its own so the mapping is
 * testable without a phone: the songs the read holds, and how many the page
 * declared ([declared] null when it stated none). [totalKnown] is the line
 * between the two honest statements the confirm step may make: with a stated
 * total it prints "N of M songs read" (and the shortfall, through [shortBy],
 * when short); without one it must say the total is unknown and the haul may
 * be only part of the list — never "N of N", which dresses a window up as a
 * whole playlist (the v29 "9 of 9" over a 9-song window of an 84-song list).
 */
internal data class ReadSummary(val found: Int, val declared: Int?) {
    /** Songs the page declared that the read never gathered; 0 when whole or undeclared. */
    val shortBy: Int get() = if (declared == null) 0 else (declared - found).coerceAtLeast(0)
    val isShort: Boolean get() = shortBy > 0
    /** Whether the page stated its total at all: the confirm step's licence to print "of M". */
    val totalKnown: Boolean get() = declared != null

    companion object {
        fun of(found: Int, declared: Int?): ReadSummary =
            ReadSummary(found, declared?.takeIf { it > 0 })
    }
}

/** Which scroller of the page a [ScrollerReport] describes. */
internal enum class ScrollerKind {
    /** The scrollable ancestor holding the most row links: the list's own container. */
    ANCHOR,
    /** The page itself (document element / window). */
    DOCUMENT,
    /** The largest scrollable element of the page: a fallback, and a known decoy. */
    LARGEST,
}

/**
 * One scroller of the page across one stepped pass: where it rested before and
 * after the step, and its size as last measured. [moved] is the proof the
 * bottom verdict demands (see [BottomJudge]): a scroller whose top never
 * changed cannot be at a bottom it never travelled to.
 */
internal data class ScrollerReport(
    val kind: ScrollerKind,
    /** A short identity for the journal: the element's tag with its id or first class. */
    val who: String,
    val topBefore: Int,
    val topAfter: Int,
    val height: Int,
    val client: Int,
) {
    val moved: Boolean get() = topAfter > topBefore
    val atBottom: Boolean get() = height <= client || topAfter + client >= height - 4

    /** This scroller's line of a pass journal. */
    fun journalText(): String =
        "${kind.name.lowercase()} $who top $topBefore→$topAfter (height $height, client $client)" +
            (if (atBottom) " [at bottom]" else "")
}

/** One stepped scroll of the page: every scroller the step measured, the rows' own first. */
internal data class ScrollStep(val reports: List<ScrollerReport>) {
    val anchor: ScrollerReport? get() = reports.firstOrNull { it.kind == ScrollerKind.ANCHOR }
}

/**
 * Whether a stepped pass rested at the list's TRUE bottom, judged from the
 * pass's [ScrollStep] — a decision of its own so it is testable without a
 * phone. Two rules, learned from three reads that each ended at the first
 * window:
 *
 *  - The verdict comes from the scroller that contains the rows (the anchor's
 *    own scrollable ancestor), never from the page's largest scrollable
 *    element: the largest is as often a rail or a chrome panel, which can read
 *    "at bottom" from the first pass while the list's container never moves.
 *  - A scroller whose scrollTop never changed across the whole read cannot
 *    declare the bottom. The step script steps the document and the row
 *    container alike, so a container that cannot actually scroll (a wrapper
 *    whose overflow is visible, a decoy) is found out by its own stillness:
 *    the highest-priority scroller that HAS proven it can move gives the
 *    verdict, so a never-moved anchor yields to a travelled document rather
 *    than vetoing the read into its caps.
 */
internal class BottomJudge {
    private val provenMovers = mutableSetOf<ScrollerKind>()

    /** The credible bottom verdict for one stepped pass; false when the step is unknown. */
    fun atBottom(step: ScrollStep?): Boolean {
        if (step == null || step.reports.isEmpty()) return false
        step.reports.forEach { if (it.moved || it.topAfter > 0) provenMovers += it.kind }
        val judge = listOf(ScrollerKind.ANCHOR, ScrollerKind.DOCUMENT, ScrollerKind.LARGEST)
            .firstNotNullOfOrNull { kind ->
                step.reports.firstOrNull { it.kind == kind && kind in provenMovers }
            }
            ?: return false
        return judge.atBottom
    }
}

/**
 * One pass of a scroll collection, journaled: which scrollers were stepped and
 * how each answered, what the pass added, what the read then held, and the
 * bottom verdict it was given. The journal rides the read's readout (see
 * [AmazonReadout]) onto the confirm step's Read details, so a read that still
 * stops early on a real page carries its own diagnosis with it.
 */
internal data class CollectPass(
    val pass: Int,
    val reports: List<ScrollerReport>,
    val added: Int,
    val held: Int,
    val judgedBottom: Boolean,
) {
    fun line(): String = buildString {
        append("pass $pass: ")
        if (reports.isEmpty()) append("no scroller answered")
        else append(reports.joinToString(" · ") { it.journalText() })
        append(" · +$added songs, $held held · bottom judged: ${if (judgedBottom) "yes" else "no"}")
    }
}

/**
 * One Amazon link being read: owns the WebView that renders the playlist and the read
 * itself ([run]). The import dialog shows the page at full size from the start, with
 * the read's live status above it ([stage], [foundCount], [declaredTotal]): the user
 * watches the same page the read reads. When Amazon answers with a wall a tap can
 * clear — a bot check, or a sign-in gate (a playlist that is not public is invisible
 * signed out) — [phase] becomes [Phase.NEEDS_USER]; the user clears it on Amazon's own
 * page, in this same session, and [userContinue] lets the read try once more. Signing
 * in persists for the session: same WebView, cookies kept.
 */
@SuppressLint("SetJavaScriptEnabled")
class AmazonReadSession(context: Context, val url: String) {

    enum class Phase { READING, NEEDS_USER, DONE, FAILED, CANCELLED }

    var phase by mutableStateOf(Phase.READING)
        private set

    /** What the dialog's status line says right now. */
    var stage by mutableStateOf(AmazonStage.LOADING_PAGE)
        private set

    /** Songs read so far, and how many the page says the playlist holds. */
    var foundCount by mutableStateOf(0)
        private set
    var declaredTotal by mutableStateOf(0)
        private set

    /** The read playlist, once [phase] is [Phase.DONE]. */
    var imported: PlaylistImport.Imported? = null
        private set

    /** What the page actually was, once [phase] is [Phase.FAILED]. */
    var failureReadout by mutableStateOf<AmazonReadout?>(null)
        private set

    // The read's running facts, kept for the failure readout.
    private var pageTitle = ""
    private var lastPageUrl = ""
    private var lastHtml: String? = null
    private var lastHtmlBytes: Int? = null
    private var lastWall = AmazonWall.NONE
    private var loadOutcome = "not finished"
    private var lastPoll = "not started"
    private var serializeNote: String? = null
    /** The total the page itself declares, when it declares one; null while it states none. */
    private var lastDeclaredTotal: Int? = null
    /** The empty-shell reload: attempted at most once per read, and the readout says so. */
    private var reloaded = false
    /** Every collect pass, journaled as it happens: the read's own evidence (see [CollectPass]). */
    private val collectJournal = mutableListOf<CollectPass>()

    val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = UA
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null) lastPageUrl = url
                loadGate?.complete(true)
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    loadOutcome = "error ${error?.errorCode}"
                    loadGate?.complete(false)
                }
            }
        }
    }

    init {
        // A sign-in on Amazon's page must stick for the rest of the read.
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)
    }

    /** The page's scripts, run through the seam: every call carries a deadline. */
    private val js = JsEvaluator { script, callback ->
        try {
            webView.evaluateJavascript(script) { raw ->
                callback(raw?.let { runCatching { org.json.JSONTokener(it).nextValue() as? String }.getOrNull() })
            }
        } catch (e: Exception) {
            callback(null)
        }
    }

    private var loadGate: CompletableDeferred<Boolean>? = null
    private var continueGate: CompletableDeferred<Unit>? = null

    /** The user cleared what Amazon showed (a check, a sign-in): read once more. */
    fun userContinue() { continueGate?.complete(Unit) }

    /** The read is over, one way or another: the page goes away. */
    fun destroy() {
        runCatching { webView.stopLoading() }
        runCatching { webView.destroy() }
    }

    suspend fun run() {
        if (phase != Phase.READING) return
        try {
            withContext(Dispatchers.Main) { read() }
        } catch (e: CancellationException) {
            phase = Phase.CANCELLED
            throw e
        } catch (e: Exception) {
            if (phase == Phase.READING || phase == Phase.NEEDS_USER) fail()
        }
    }

    private suspend fun read() {
        // The whole read has a deadline of its own: whatever Amazon does, the dialog
        // lands somewhere — the playlist, a wall, or the failure readout.
        val finished = withTimeoutOrNull(TOTAL_CAP_MS) { readCapped() }
        if (finished == null && (phase == Phase.READING || phase == Phase.NEEDS_USER)) {
            lastPoll = "overall timeout (${TOTAL_CAP_MS / 1000}s)"
            fail()
        }
    }

    private suspend fun readCapped() {
        loadGate = CompletableDeferred()
        webView.loadUrl(url)
        // The first finish is the shell; the list renders after it. A finish that never
        // comes is not fatal either — the polling below is the judge.
        val loaded = withTimeoutOrNull(LOAD_TIMEOUT_MS) { loadGate?.await() }
        if (loadOutcome == "not finished") {
            loadOutcome = when (loaded) {
                true -> "finished"
                false -> "error"
                null -> "never finished"
            }
        }
        stage = AmazonStage.WAITING_FOR_LIST

        var page = pollForRows(FIRST_RENDER_MS)
        if (page == null && lastWall == AmazonWall.NONE && !reloaded && emptyShell()) {
            // The black-page case (seen on a phone: the import sat on an empty
            // black page area until it failed): Amazon served the bare app
            // shell and the list never rendered — no rows, no wall, almost no
            // page at all. One reload is the whole remedy attempted, once; the
            // readout records that it happened.
            reloaded = true
            lastPoll = "empty shell (no rows, no wall, ${lastHtmlBytes ?: 0} bytes read): reloading once"
            loadGate = CompletableDeferred()
            webView.reload()
            withTimeoutOrNull(LOAD_TIMEOUT_MS) { loadGate?.await() }
            if (phase == Phase.CANCELLED) return
            page = pollForRows(SECOND_RENDER_MS)
        }
        if (page == null && lastWall != AmazonWall.NONE) {
            // A wall a tap can clear: a bot check, or Amazon asking for a sign-in —
            // a playlist that is not public shows nothing until its owner signs in.
            // The page is already on screen at full size; wait for the tap-through,
            // then read once more before giving up.
            phase = Phase.NEEDS_USER
            stage = amazonWallStage(lastWall)
            val gate = CompletableDeferred<Unit>().also { continueGate = it }
            withTimeoutOrNull(USER_TIMEOUT_MS) { gate.await() }
            continueGate = null
            if (phase == Phase.CANCELLED) return
            phase = Phase.READING
            stage = AmazonStage.WAITING_FOR_LIST
            delay(2_000)
            page = pollForRows(SECOND_RENDER_MS)
        }
        if (page == null || page.rows.isEmpty()) { fail(); return }
        collect(page)
    }

    /**
     * Scroll-and-collect from a first reading: see [AmazonCollect]. The list is
     * VIRTUALIZED — it renders the rows near where it rests — so the scroll
     * advances a screen at a time and every resting place is read; a jump to
     * the bottom would skip the middle rows' rendering entirely, and they
     * would never be collected (a long playlist importing as a handful of
     * songs). [CollectEnd] decides when it is over: the true bottom repeating
     * empty through its quiet window — never the size of the window that
     * happened to be showing, and never the total the page declares (advisory
     * only: it sizes the summary, it stops nothing).
     */
    private suspend fun collect(first: AmazonDom.Page) {
        val collect = AmazonCollect()
        collect.add(first.rows)
        var declared = lastDeclaredTotal
        foundCount = collect.songs.size
        declaredTotal = declared ?: 0
        stage = amazonPollStage(foundCount, declared ?: Int.MAX_VALUE)
        val end = CollectEnd()
        val judge = BottomJudge()
        var passes = 0
        val deadline = System.currentTimeMillis() + COLLECT_CAP_MS
        var done = end.offer(first.rows.size, atBottom = false, declared, collect.songs.size)
        while (!done && passes < MAX_SCROLL_PASSES && System.currentTimeMillis() < deadline) {
            val step = scrollStep()
            delay(SCROLL_SETTLE_MS)
            val html = serialize()
            if (html == null) { lastPoll = "page unreadable during collect"; break }
            val fresh = withContext(Dispatchers.Default) { AmazonDom.parse(html) }
            AmazonDom.declaredTotalOrNull(html)?.let { d -> declared = maxOf(declared ?: 0, d) }
            val added = collect.add(fresh.rows)
            foundCount = collect.songs.size
            declaredTotal = declared ?: 0
            stage = amazonPollStage(foundCount, declared ?: Int.MAX_VALUE)
            // The bottom verdict is the judge's, not any single scroller's: the
            // rows' own container decides, and only once it has proven it moves
            // (see BottomJudge). Every pass is journaled either way.
            val judgedBottom = judge.atBottom(step)
            collectJournal += CollectPass(passes + 1, step?.reports.orEmpty(), added, collect.songs.size, judgedBottom)
            lastPoll = "collect pass ${passes + 1}: +$added songs, ${collect.songs.size} held"
            passes++
            done = end.offer(added, judgedBottom, declared, collect.songs.size)
        }
        // The read's own statement of itself: whole against a stated total, short
        // by a number the confirm step shows plainly — or, when the page never
        // stated a total, honestly unstated: the Imported carries the declared
        // count only when one was actually found (see ReadSummary.totalKnown),
        // so the confirm step can never print the haul as the whole list on a
        // total nobody verified.
        val summary = ReadSummary.of(collect.songs.size, declared)
        if (summary.isShort) {
            lastPoll = "collect ended short: ${summary.found} of ${summary.declared} songs read"
        } else if (!summary.totalKnown) {
            lastPoll = "collect ended: ${summary.found} songs read; the page stated no total"
        }
        imported = PlaylistImport.Imported(
            first.name, collect.songs, maxOf(summary.declared ?: 0, summary.found),
            declaredTotal = summary.declared,
            totalStated = true,
            readDetails = readout(collect.songs.size).text(),
        )
        stage = AmazonStage.DONE
        phase = Phase.DONE
    }

    /** The read's facts as a readout, for a failure's screen or a success's Read details. */
    private fun readout(rowsFound: Int): AmazonReadout = AmazonReadout(
        url = url,
        pageTitle = pageTitle.ifBlank { runCatching { webView.title }.getOrNull().orEmpty() },
        pageUrl = lastPageUrl.ifBlank { runCatching { webView.url }.getOrNull().orEmpty() },
        htmlBytes = lastHtmlBytes,
        rowsFound = rowsFound,
        wall = lastWall,
        loadOutcome = loadOutcome,
        lastPoll = lastPoll,
        reloaded = reloaded,
        journal = collectJournal.map { it.line() },
    )

    /** Lands the read on failure, with the readout of what the page actually was. */
    private fun fail() {
        failureReadout = readout(foundCount)
        stage = AmazonStage.FAILED
        phase = Phase.FAILED
    }

    /**
     * The page the first poll read was an empty shell: nothing of it could be
     * read at all, or what could be read is far smaller than any rendered
     * playlist page (those run to hundreds of kilobytes once the list renders;
     * the bare app shell Amazon sometimes answers with is a few).
     */
    private fun emptyShell(): Boolean = lastHtml == null || (lastHtmlBytes ?: 0) < SHELL_MAX_BYTES

    /** Polls the rendering page until its rows appear or [withinMs] runs out. */
    private suspend fun pollForRows(withinMs: Long): AmazonDom.Page? {
        val deadline = System.currentTimeMillis() + withinMs
        var polls = 0
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_MS)
            polls++
            val html = serialize()
            if (html == null) {
                lastPoll = "poll $polls: ${serializeNote ?: "page unreadable"}"
                continue
            }
            lastHtml = html
            val parsed = withContext(Dispatchers.Default) { AmazonDom.parse(html) }
            if (parsed.name.isNotBlank()) pageTitle = parsed.name
            // The largest credible total seen anywhere in the read is the
            // summary's: a later, fuller serialization must be able to outvote
            // an early page whose header had not rendered (or rendered a rail
            // card's count) — never the other way round.
            AmazonDom.declaredTotalOrNull(html)?.let { d -> lastDeclaredTotal = maxOf(lastDeclaredTotal ?: 0, d) }
            lastPoll = "poll $polls: ${parsed.rows.size} rows"
            if (parsed.rows.isNotEmpty()) {
                foundCount = parsed.rows.size
                declaredTotal = lastDeclaredTotal ?: 0
                stage = amazonPollStage(foundCount, lastDeclaredTotal ?: Int.MAX_VALUE)
                return parsed
            }
            // A bot check never turns into rows: stop early and let the user clear it.
            // A sign-in gate is judged on the whole window instead — a working page
            // can carry a sign-in link in its header while its rows are still coming.
            if (AmazonDom.wallOf(html) == AmazonWall.CAPTCHA) {
                lastWall = AmazonWall.CAPTCHA
                lastPoll = "poll $polls: captcha wall"
                return null
            }
        }
        lastWall = lastHtml?.let { AmazonDom.wallOf(it) } ?: AmazonWall.NONE
        return null
    }

    /** Advances the page's list by one screen and reports how every scroller answered. */
    private suspend fun scrollStep(): ScrollStep? {
        val raw = runJs(js, STEP_JS, EVAL_TIMEOUT_MS) ?: return null
        return runCatching {
            val arr = org.json.JSONObject(raw).getJSONArray("reports")
            val reports = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ScrollerReport(
                    kind = ScrollerKind.valueOf(o.getString("kind").uppercase()),
                    who = o.getString("who"),
                    topBefore = o.getInt("before"),
                    topAfter = o.getInt("after"),
                    height = o.getInt("height"),
                    client = o.getInt("client"),
                )
            }
            ScrollStep(reports)
        }.getOrNull()
    }

    /** The rendered page as HTML, its shadow roots inlined where their hosts stand. */
    private suspend fun serialize(): String? {
        val raw = runJs(js, SERIALIZER_JS, EVAL_TIMEOUT_MS)
        if (raw == null) {
            serializeNote = "page did not answer"
            return null
        }
        if (raw.startsWith(SERIALIZE_ERROR)) {
            serializeNote = "serialize error: ${raw.removePrefix(SERIALIZE_ERROR).take(80)}"
            return null
        }
        serializeNote = null
        lastHtmlBytes = raw.toByteArray(Charsets.UTF_8).size
        return raw
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
        const val LOAD_TIMEOUT_MS = 30_000L
        const val FIRST_RENDER_MS = 25_000L
        const val SECOND_RENDER_MS = 20_000L
        const val USER_TIMEOUT_MS = 300_000L
        const val TOTAL_CAP_MS = 540_000L
        const val COLLECT_CAP_MS = 120_000L
        const val EVAL_TIMEOUT_MS = 10_000L
        const val POLL_MS = 1_200L
        /** A first read smaller than this is the bare app shell, not a rendered page. */
        const val SHELL_MAX_BYTES = 20_000
        const val SCROLL_SETTLE_MS = 1_100L
        const val MAX_SCROLL_PASSES = 120
        const val SERIALIZE_ERROR = "__SERIALIZE_ERROR__:"

        /** The page flattened to HTML: a shadow root's children stand where it renders. */
        const val SERIALIZER_JS = """
            (function(){
              try {
                function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;');}
                function ser(node, depth){
                  if(node.nodeType===3) return esc(node.textContent);
                  if(node.nodeType!==1) return '';
                  if(depth>80) return '';
                  var tag=node.tagName.toLowerCase();
                  var out='<'+tag;
                  for(var i=0;i<node.attributes.length;i++){var a=node.attributes[i];
                    out+=' '+a.name+'="'+String(a.value).replace(/&/g,'&amp;').replace(/"/g,'&quot;')+'"';}
                  out+='>';
                  var kids=(node.shadowRoot?node.shadowRoot.childNodes:node.childNodes);
                  for(var j=0;j<kids.length;j++) out+=ser(kids[j], depth+1);
                  return out+'</'+tag+'>';
                }
                return ser(document.documentElement, 0);
              } catch (e) {
                return '__SERIALIZE_ERROR__:' + (e && e.message ? e.message : e);
              }
            })()
        """

        /**
         * Steps the page's list down by one screen — never a jump to the bottom:
         * a virtualized list renders only what it rests on, so a jump renders
         * the end and skips the middle — and reports EVERY scroller it measured
         * as JSON: {"reports":[{"kind":…,"who":…,"before":…,"after":…,
         * "height":…,"client":…}, …]} (see [ScrollerReport]).
         *
         * Which scroller holds the rows is decided by the rows themselves:
         * every track link's nearest scrollable ancestor (climbing through
         * shadow hosts — the player renders its list inside shadow roots, and a
         * stepper that only queries the light DOM never sees that container at
         * all) is tallied, and the ancestor holding the most links is the list's
         * container: the "anchor" scroller. It and the document are stepped
         * each pass, and each reports its top before and after — the movement
         * is the evidence [BottomJudge] judges by, because a bottom verdict
         * taken from the page's largest scrollable element ended real reads at
         * the first window: the largest is as often a rail or a chrome panel
         * that sits at its own bottom from the start. The largest is still
         * measured every pass (the journal shows it for what it is) and is
         * stepped only in its fallback role, when no row link has a scrollable
         * ancestor at all.
         */
        const val STEP_JS = """
            (function(){
              function deepAll(root, out){
                var els = root.querySelectorAll ? root.querySelectorAll('*') : [];
                for (var i = 0; i < els.length; i++) {
                  var e = els[i];
                  out.push(e);
                  if (e.shadowRoot) deepAll(e.shadowRoot, out);
                }
                return out;
              }
              function scrollable(e){ return e.scrollHeight > e.clientHeight + 40; }
              function nearestScroller(node){
                var n = node;
                while (n) {
                  if (n.nodeType === 1 && scrollable(n)) return n;
                  if (n.parentElement) { n = n.parentElement; continue; }
                  var r = n.getRootNode ? n.getRootNode() : null;
                  n = (r && r.host) ? r.host : null;
                }
                return null;
              }
              var doc = document.documentElement;
              function describe(e){
                var s = e.tagName ? e.tagName.toLowerCase() : '?';
                if (e.id) s += '#' + e.id;
                else if (typeof e.className === 'string' && e.className.trim()) s += '.' + e.className.trim().split(/\s+/)[0];
                return s;
              }
              function measure(e){
                var top = e === doc ? (window.scrollY || doc.scrollTop || 0) : (e.scrollTop || 0);
                return { top: Math.round(top), height: Math.round(e.scrollHeight || 0), client: Math.round(e.clientHeight || window.innerHeight || 0) };
              }
              function stepEl(e){
                var view = e.clientHeight || window.innerHeight || 600;
                var step = Math.max(200, Math.floor(view * 0.9));
                e.scrollTop = Math.min((e.scrollTop || 0) + step, e.scrollHeight || 0);
                if (e === doc) window.scrollTo(0, Math.min((window.scrollY || 0) + step, doc.scrollHeight || 0));
              }
              var all = deepAll(document, []);
              var tally = new Map();
              for (var i = 0; i < all.length; i++) {
                var h = all[i].getAttribute ? all[i].getAttribute('href') : null;
                if (h && h.indexOf('trackAsin=') >= 0) {
                  var sc = nearestScroller(all[i]);
                  if (sc) tally.set(sc, (tally.get(sc) || 0) + 1);
                }
              }
              var anchorScroller = null, bestCount = 0;
              tally.forEach(function(n, sc){ if (n > bestCount) { bestCount = n; anchorScroller = sc; } });
              var largest = null, bestH = 0;
              for (var j = 0; j < all.length; j++) {
                var e2 = all[j];
                if (scrollable(e2) && e2.scrollHeight > bestH) { bestH = e2.scrollHeight; largest = e2; }
              }
              var reports = [];
              function report(kind, e, doStep){
                if (!e) return;
                var before = measure(e);
                if (doStep) stepEl(e);
                var after = measure(e);
                reports.push({kind: kind, who: describe(e), before: before.top, after: after.top, height: after.height, client: after.client});
              }
              report('anchor', anchorScroller, true);
              if (doc !== anchorScroller) report('document', doc, true);
              if (largest && largest !== anchorScroller && largest !== doc) report('largest', largest, !anchorScroller);
              return JSON.stringify({reports: reports});
            })()
        """
    }
}
