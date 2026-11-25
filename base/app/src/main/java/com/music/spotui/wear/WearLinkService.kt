package com.music.spotui.wear

import android.content.Context
import app.myspoti.host.android.Logbook
import app.myspoti.host.android.MyspotiRuntime
import app.myspoti.pass.PassCrypto
import app.myspoti.pass.PassExtension
import app.myspoti.pass.PassInvite
import app.myspoti.pass.PassLink
import app.myspoti.pass.PassPayload
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.music.spotui.data.local.CatalogLikes
import com.music.spotui.data.local.ImportedPlaylists
import com.music.spotui.data.local.LocalLikes
import com.music.spotui.data.local.LocalPlaylists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * What the watch's app asks the phone's: the account's session for the watch's own player (a
 * watch has no browser to sign in with), the playlists to pick from, a playlist's songs (for the
 * watch's own extensions to find their audio) and the extensions themselves. Asked over the
 * Wearable Data Layer ([WearLinkService]) or, when that link is down, straight over the Wi-Fi
 * ([WatchLan]).
 *
 * Scadify runs with no music account, so the library and track answers are served from what
 * lives on this phone alone ([WatchLocalAnswers]: local playlists, imported playlists, the
 * on-device likes), merged around the account's lists whenever one is signed in — the signed-in
 * answers are exactly what they were. Two further paths are the watch's Phone mode: what plays
 * on this phone ([PhoneNowPlaying]) and the commands that drive this phone's player
 * ([PhoneControl]).
 */
object WatchAnswers {
    const val SESSION = "/orpheon/session"
    const val LIBRARY = "/orpheon/library"
    const val TRACKS = "/orpheon/tracks"
    const val EXTENSIONS = "/orpheon/extensions"
    const val NOW_PLAYING = "/scadify/nowplaying"
    const val CONTROL = "/scadify/control"
    private const val MAX_LISTS = 60
    private const val MAX_TRACKS = 500
    /** A command (a skip, above all) lands on the player at once; its answer a breath later reads true. */
    private const val CONTROL_SETTLE_MS = 300L

    private fun platform() = MyspotiRuntime.api

    suspend fun answer(context: Context, path: String, body: String): String = when (path) {
        SESSION -> link(context)
        LIBRARY -> library(context)
        TRACKS -> tracks(context, body)
        EXTENSIONS -> extensions(context)
        NOW_PLAYING -> PhoneNowPlaying.payload(context)
        CONTROL -> {
            PhoneControl.execute(PhoneControl.parse(body))
            delay(CONTROL_SETTLE_MS)
            PhoneNowPlaying.payload(context)
        }
        else -> error("unknown question")
    }

    /** How the watch reaches this phone over the Wi-Fi when the Data Layer is down. No account token goes to the watch. */
    private fun link(context: Context): String {
        val lan = WatchLan.ensure(context)
        return buildJsonObject {
            if (lan != null) {
                put("lanSecret", lan.secret); put("lanPort", lan.port); put("lanId", lan.id)
                PassInvite.localAddress()?.let { put("lanHost", it) }
            }
        }.toString()
    }

    private suspend fun library(context: Context): String {
        val out = ArrayList<JSONObject>()
        out.add(JSONObject().put("uri", WatchLocalAnswers.LIKED_URI).put("name", context.getString(app.myspoti.engine.R.string.lbl_liked_songs)).put("subtitle", ""))
        // The account's lists when one is signed in (the neutral platform answers none, and a
        // platform that cannot answer must not take the phone's own lists down with it).
        runCatching { platform().playlists.mine() }.getOrDefault(emptyList())
            .filter { it.name.isNotBlank() }
            .forEach { p -> out.add(JSONObject().put("uri", p.uri).put("name", p.name).put("subtitle", p.owner.orEmpty())) }
        runCatching {
            LocalPlaylists.init(context)
            WatchLocalAnswers.libraryEntries(LocalPlaylists.list(), ImportedPlaylists.list())
        }.getOrDefault(emptyList()).forEach { out.add(it) }
        return JSONArray().apply { out.take(MAX_LISTS).forEach { put(it) } }.toString()
    }

    /** The songs of the list [uri] (Liked Songs: `spotify:collection:tracks`), at most [MAX_TRACKS]. */
    private suspend fun tracks(context: Context, uri: String): String {
        val out = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        fun add(o: JSONObject) { if (out.size < MAX_TRACKS && seen.add(WatchLocalAnswers.dedupeKey(o))) out.add(o) }
        when {
            uri.startsWith(WatchLocalAnswers.LOCAL_PREFIX) -> {
                LocalPlaylists.init(context)
                val p = LocalPlaylists.playlists[uri.removePrefix(WatchLocalAnswers.LOCAL_PREFIX)]
                    ?: error("no such list on this phone")
                WatchLocalAnswers.localTracks(p).forEach(::add)
            }
            uri.startsWith(WatchLocalAnswers.IMPORTED_PREFIX) -> {
                val p = ImportedPlaylists.get(uri.removePrefix(WatchLocalAnswers.IMPORTED_PREFIX))
                    ?: error("no such list on this phone")
                WatchLocalAnswers.importedTracks(p).forEach(::add)
            }
            uri == WatchLocalAnswers.LIKED_URI -> {
                runCatching { platformTracks(uri) }.getOrDefault(emptyList()).forEach { add(trackJson(it)) }
                runCatching { WatchLocalAnswers.likedTracks(CatalogLikes.items.toList(), LocalLikes.items.toList()) }
                    .getOrDefault(emptyList()).forEach(::add)
            }
            else -> platformTracks(uri).forEach { add(trackJson(it)) }
        }
        return JSONArray().apply { out.forEach { put(it) } }.toString()
    }

    /** A list's songs from the account's platform, paged as the platform gives them. */
    private suspend fun platformTracks(uri: String): List<app.myspoti.api.Track> {
        val s = platform()
        val out = ArrayList<app.myspoti.api.Track>()
        var offset: Int? = 0
        while (offset != null && out.size < MAX_TRACKS) {
            val page = if (uri == WatchLocalAnswers.LIKED_URI) s.library.likedTracks(offset, 50).let { p -> p.items.map { it.track } to p.nextOffset }
                else s.playlists.tracks(uri, offset, 100).let { p -> p.items to p.nextOffset }
            out.addAll(page.first.filter { it.playable })
            offset = page.second
        }
        return out
    }

    /** An account track in the shared answer shape (see [WatchLocalAnswers]). */
    private fun trackJson(t: app.myspoti.api.Track): JSONObject = WatchLocalAnswers.track(
        uri = t.uri, name = t.name, artists = t.artists.map { it.name }, album = t.album?.name.orEmpty(),
        durationMs = t.durationMs, cover = t.album?.images?.firstOrNull()?.url.orEmpty(), isrc = "", phoneOnly = false)

    /** The extensions installed on the phone, as the pass carries them (packages, grants, state, store, settings). */
    private fun extensions(context: Context): String =
        PassPayload.JSON.encodeToString(ListSerializer(PassExtension.serializer()), com.music.spotui.pass.PassAndroid.extensions(context))
}

/** The Data Layer side of [WatchAnswers]: small answers by message, the extensions (large) through a channel. */
class WearLinkService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onRequest(nodeId: String, path: String, data: ByteArray): Task<ByteArray>? {
        if (path !in setOf(WatchAnswers.SESSION, WatchAnswers.LIBRARY, WatchAnswers.TRACKS, WatchAnswers.NOW_PLAYING, WatchAnswers.CONTROL)) return null
        val out = TaskCompletionSource<ByteArray>()
        scope.launch {
            val answer = runCatching { withTimeout(ANSWER_MS) { WatchAnswers.answer(applicationContext, path, data.decodeToString()) } }
                .getOrElse { Logbook.log("watch: $path not answered (${it.message})"); "error: ${it.message ?: it.javaClass.simpleName}" }
            out.setResult(answer.encodeToByteArray())
        }
        return out.task
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WatchAnswers.EXTENSIONS) return
        scope.launch {
            val client = Wearable.getChannelClient(applicationContext)
            runCatching {
                val text = runCatching { WatchAnswers.answer(applicationContext, channel.path, "") }.getOrElse { "error: ${it.message}" }
                client.getOutputStream(channel).await().use { it.write(text.encodeToByteArray()) }
            }.onFailure { Logbook.log("watch: extensions not sent (${it.message})") }
            runCatching { client.close(channel).await() }
        }
    }

    private companion object {
        const val ANSWER_MS = 25_000L
    }
}

/**
 * The phone answering its watch straight over the Wi-Fi ([PassLink], sealed with a secret the
 * watch got over the Data Layer), for when that link is down: Bluetooth off or out of range, the
 * phone's Google services unreachable. Runs while this app's process does, once a watch has asked
 * for the session; announced on the network (NSD, `_orpheon._tcp`) so the watch finds the phone
 * again when its address changed.
 */
object WatchLan {
    class Link(val secret: String, val port: Int, val id: String)

    private const val PREFS = "pass_watch_link"
    private var server: PassLink.Server? = null
    private var registered: android.net.nsd.NsdManager.RegistrationListener? = null

    /** Starts the server when a watch was ever linked (the app's start). */
    fun startIfLinked(context: Context) {
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("secret")) Thread { runCatching { ensure(context) } }.start()
    }

    /** The link's secret, port and id, the server started; null when it could not start. */
    @Synchronized fun ensure(context: Context): Link? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val secret = prefs.getString("secret", null) ?: b64.encodeToString(PassCrypto.randomBytes(PassLink.SECRET_BYTES)).also { prefs.edit().putString("secret", it).apply() }
        val id = prefs.getString("id", null) ?: b64.encodeToString(PassCrypto.randomBytes(6)).also { prefs.edit().putString("id", it).apply() }
        val running = server ?: run {
            val app = context.applicationContext
            val raw = Base64.getUrlDecoder().decode(secret)
            // The same port as before when free: the watch keeps the last one it was told.
            val s = runCatching { PassLink.Server(raw, prefs.getInt("port", 0), answer = answerer(app)) }.getOrElse { PassLink.Server(raw, 0, answer = answerer(app)) }.start()
            prefs.edit().putInt("port", s.port).apply()
            server = s
            announce(app, s.port, id)
            Logbook.log("watch: Wi-Fi link open on port ${s.port}")
            s
        }
        Link(secret, running.port, id)
    }.onFailure { Logbook.log("watch: Wi-Fi link not open (${it.message})") }.getOrNull()

    private fun answerer(context: Context): (String, String) -> String = { path, body ->
        runBlocking { withTimeout(25_000) { WatchAnswers.answer(context, path, body) } }
    }

    private fun announce(context: Context, port: Int, id: String) = runCatching {
        val nsd = context.getSystemService(android.net.nsd.NsdManager::class.java) ?: return@runCatching
        val info = android.net.nsd.NsdServiceInfo().apply { serviceName = "Orpheon-$id"; serviceType = "_orpheon._tcp"; setPort(port) }
        val listener = object : android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: android.net.nsd.NsdServiceInfo) {}
            override fun onRegistrationFailed(i: android.net.nsd.NsdServiceInfo, code: Int) { Logbook.log("watch: not announced on the network ($code)") }
            override fun onServiceUnregistered(i: android.net.nsd.NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: android.net.nsd.NsdServiceInfo, code: Int) {}
        }
        nsd.registerService(info, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener)
        registered = listener
    }
}
