package com.music.spotui.data.api

import android.annotation.SuppressLint
import android.content.Context
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
 * Reading a public Amazon Music playlist on the phone.
 *
 * Why a rendered page at all: there is no Spotify-style reading of an Amazon link. Its
 * API is closed and authorized-only, and the share page's own HTML is an empty app
 * shell — no track list, no guest token. But the web player, opened signed out, renders
 * a public playlist's whole track list into its page: title, artist, album and duration
 * as text, and each song's ASIN in its album link (`/albums/<album>?trackAsin=<asin>`).
 * So the link is loaded in an offscreen [WebView], the rendered page is read out of it,
 * and long lists are scrolled into view a screen at a time and collected.
 *
 * Everything Amazon-specific lives in this one file — the page queries in [AmazonDom],
 * the scripts below — because Amazon rebuilt its player's insides as recently as
 * October 2026 and will change them again: when this read breaks, this file is the fix.
 * Nothing here signs in, and nothing leaves the phone: the rows go to the same import
 * path a Spotify reading takes. Amazon states no ISRC, so the rows carry none; the
 * import completes them from the app's own catalog (CatalogCompletion).
 */
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

    /**
     * A rendered playlist page's HTML (shadow roots inlined — see the reader's
     * serializer) → its rows. A row is found by its track link: the anchor whose address
     * carries `trackAsin=`. A row can link its song twice (the cover and the title), so
     * anchors are grouped by ASIN and the one carrying text is the title. The artist and
     * album are the row's own links where it has them, else its next lines of text; the
     * duration is the row's m:ss; the cover is the last picture before the title. A row
     * with no title text is not a row and is skipped.
     */
    fun parse(html: String): Page {
        val byAsin = LinkedHashMap<String, MutableList<MatchResult>>()
        for (m in ANCHOR.findAll(html)) {
            val asin = TRACK_ASIN.find(m.groupValues[1])?.groupValues?.get(1) ?: continue
            byAsin.getOrPut(asin) { ArrayList() } += m
        }
        val groups = byAsin.entries.toList()
        val rows = groups.mapIndexedNotNull { i, (asin, anchors) ->
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
        return Page(name(html), declaredTotal(html, rows.size), rows)
    }

    /** The page is a bot check (or another wall) rather than a playlist. */
    fun looksBlocked(html: String): Boolean =
        listOf("captcha", "robot check", "enter the characters you see below", "type the characters you see below")
            .any { html.contains(it, ignoreCase = true) }

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
 * One Amazon link being read: owns the offscreen WebView that renders the playlist and
 * the read itself ([run]). The import dialog shows it at a token size while it works;
 * when the page turns out to be a bot check, [phase] becomes [Phase.NEEDS_USER] and the
 * dialog shows the page at a real size for the user to tap through, then [userContinue]
 * lets the read try once more.
 */
@SuppressLint("SetJavaScriptEnabled")
class AmazonReadSession(context: Context, val url: String) {

    enum class Phase { READING, NEEDS_USER, DONE, FAILED, CANCELLED }

    var phase by mutableStateOf(Phase.READING)
        private set

    /** The read playlist, once [phase] is [Phase.DONE]. */
    var imported: PlaylistImport.Imported? = null
        private set

    val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = false
        settings.blockNetworkImage = true
        settings.userAgentString = UA
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { loadGate?.complete(true) }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) loadGate?.complete(false)
            }
        }
    }

    private var loadGate: CompletableDeferred<Boolean>? = null
    private var continueGate: CompletableDeferred<Unit>? = null

    /** The user tapped through the check Amazon showed: try the read once more. */
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
            if (phase == Phase.READING || phase == Phase.NEEDS_USER) phase = Phase.FAILED
        }
    }

    private suspend fun read() {
        loadGate = CompletableDeferred()
        webView.loadUrl(url)
        // The first finish is the shell; the list renders after it. A finish that never
        // comes is not fatal either — the polling below is the judge.
        withTimeoutOrNull(LOAD_TIMEOUT_MS) { loadGate?.await() }

        var page = pollForRows(FIRST_RENDER_MS)
        if (page == null) {
            val html = serialize()
            if (html != null && AmazonDom.looksBlocked(html)) {
                // A bot check: the one wall a tap can clear. Show the page, wait for
                // the tap-through, then read once more before giving up.
                phase = Phase.NEEDS_USER
                val gate = CompletableDeferred<Unit>().also { continueGate = it }
                withTimeoutOrNull(USER_TIMEOUT_MS) { gate.await() }
                continueGate = null
                if (phase == Phase.CANCELLED) return
                phase = Phase.READING
                delay(2_000)
                page = pollForRows(SECOND_RENDER_MS)
            }
        }
        if (page == null || page.rows.isEmpty()) { phase = Phase.FAILED; return }

        // Scroll-and-collect: the page loads more of a long list as it is scrolled, and
        // what it declares is the target. Two full scrolls that add nothing end it.
        val collect = AmazonCollect()
        collect.add(page.rows)
        var total = page.declaredTotal
        var stalls = 0
        var passes = 0
        while (passes < MAX_SCROLL_PASSES && collect.songs.size < total) {
            scrollToBottom()
            delay(SCROLL_SETTLE_MS)
            val html = serialize() ?: break
            val fresh = withContext(Dispatchers.Default) { AmazonDom.parse(html) }
            if (fresh.declaredTotal > total) total = fresh.declaredTotal
            if (collect.add(fresh.rows) == 0) { if (++stalls >= 2) break } else stalls = 0
            passes++
        }
        imported = PlaylistImport.Imported(
            page.name, collect.songs, maxOf(total, collect.songs.size),
        )
        phase = Phase.DONE
    }

    /** Polls the rendering page until its rows appear or [withinMs] runs out. */
    private suspend fun pollForRows(withinMs: Long): AmazonDom.Page? {
        val deadline = System.currentTimeMillis() + withinMs
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_MS)
            val html = serialize() ?: continue
            val parsed = withContext(Dispatchers.Default) { AmazonDom.parse(html) }
            if (parsed.rows.isNotEmpty()) return parsed
            if (AmazonDom.looksBlocked(html)) return null
        }
        return null
    }

    private suspend fun scrollToBottom() { evaluate(SCROLL_JS) }

    /** The rendered page as HTML, its shadow roots inlined where their hosts stand. */
    private suspend fun serialize(): String? = evaluate(SERIALIZER_JS)

    private suspend fun evaluate(script: String): String? = suspendCancellableCoroutine { cont ->
        try {
            webView.evaluateJavascript(script) { raw ->
                if (cont.isActive) cont.resume(
                    raw?.let { runCatching { org.json.JSONTokener(it).nextValue() as? String }.getOrNull() },
                )
            }
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
        const val LOAD_TIMEOUT_MS = 30_000L
        const val FIRST_RENDER_MS = 25_000L
        const val SECOND_RENDER_MS = 20_000L
        const val USER_TIMEOUT_MS = 300_000L
        const val POLL_MS = 1_200L
        const val SCROLL_SETTLE_MS = 1_100L
        const val MAX_SCROLL_PASSES = 60

        /** The page flattened to HTML: a shadow root's children stand where it renders. */
        const val SERIALIZER_JS = """
            (function(){
              function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;');}
              function ser(node){
                if(node.nodeType===3) return esc(node.textContent);
                if(node.nodeType!==1) return '';
                var tag=node.tagName.toLowerCase();
                var out='<'+tag;
                for(var i=0;i<node.attributes.length;i++){var a=node.attributes[i];
                  out+=' '+a.name+'="'+String(a.value).replace(/&/g,'&amp;').replace(/"/g,'&quot;')+'"';}
                out+='>';
                var kids=(node.shadowRoot?node.shadowRoot.childNodes:node.childNodes);
                for(var j=0;j<kids.length;j++) out+=ser(kids[j]);
                return out+'</'+tag+'>';
              }
              return ser(document.documentElement);
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
