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
        appendLine("last poll: $lastPoll")
    }.trimEnd()
}

object AmazonDom {

    /** One rendered playlist page, read: its name, how many songs it declares, its rows. */
    data class Page(val name: String, val declaredTotal: Int, val rows: List<ImportedSong>)

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
        val rows = linked + parseRowElements(html).filter { rowKey(it) !in seen }
        return Page(name(html), declaredTotal(html, rows.size), rows)
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
            val title = cleanText(titleAnchor.groupValues[2])
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

    private fun declaredTotal(html: String, fallback: Int): Int =
        SONG_COUNT.find(html.take(30_000))?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            ?.takeIf { it > 0 } ?: fallback

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

    /** Scroll-and-collect from a first reading: see [AmazonCollect]. */
    private suspend fun collect(first: AmazonDom.Page) {
        val collect = AmazonCollect()
        collect.add(first.rows)
        var total = first.declaredTotal
        foundCount = collect.songs.size
        declaredTotal = total
        stage = amazonPollStage(foundCount, total)
        var stalls = 0
        var passes = 0
        val deadline = System.currentTimeMillis() + COLLECT_CAP_MS
        while (passes < MAX_SCROLL_PASSES && collect.songs.size < total &&
            System.currentTimeMillis() < deadline
        ) {
            scrollToBottom()
            delay(SCROLL_SETTLE_MS)
            val html = serialize()
            if (html == null) { lastPoll = "page unreadable during collect"; break }
            val fresh = withContext(Dispatchers.Default) { AmazonDom.parse(html) }
            if (fresh.declaredTotal > total) total = fresh.declaredTotal
            if (collect.add(fresh.rows) == 0) { if (++stalls >= 2) break } else stalls = 0
            foundCount = collect.songs.size
            declaredTotal = total
            stage = amazonPollStage(foundCount, total)
            passes++
        }
        imported = PlaylistImport.Imported(
            first.name, collect.songs, maxOf(total, collect.songs.size),
        )
        stage = AmazonStage.DONE
        phase = Phase.DONE
    }

    /** Lands the read on failure, with the readout of what the page actually was. */
    private fun fail() {
        failureReadout = AmazonReadout(
            url = url,
            pageTitle = pageTitle.ifBlank { runCatching { webView.title }.getOrNull().orEmpty() },
            pageUrl = lastPageUrl.ifBlank { runCatching { webView.url }.getOrNull().orEmpty() },
            htmlBytes = lastHtmlBytes,
            rowsFound = foundCount,
            wall = lastWall,
            loadOutcome = loadOutcome,
            lastPoll = lastPoll,
        )
        stage = AmazonStage.FAILED
        phase = Phase.FAILED
    }

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
            lastPoll = "poll $polls: ${parsed.rows.size} rows"
            if (parsed.rows.isNotEmpty()) {
                foundCount = parsed.rows.size
                declaredTotal = parsed.declaredTotal
                stage = amazonPollStage(foundCount, declaredTotal)
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

    private suspend fun scrollToBottom() { runJs(js, SCROLL_JS, EVAL_TIMEOUT_MS) }

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
        const val SCROLL_SETTLE_MS = 1_100L
        const val MAX_SCROLL_PASSES = 60
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

        /** Scrolls the page and every scrollable element in it to the bottom. */
        const val SCROLL_JS = """
            (function(){
              window.scrollTo(0, document.documentElement.scrollHeight||0);
              var els=document.querySelectorAll('*');
              for(var i=0;i<els.length;i++){var e=els[i];
                if(e.scrollHeight>e.clientHeight+40) e.scrollTop=e.scrollHeight;}
              return 1;
            })()
        """
    }
}
