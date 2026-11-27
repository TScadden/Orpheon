package app.myspoti.wear

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import app.myspoti.pass.PassLink
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64
import kotlin.coroutines.resume

/** A playlist (or Liked Songs) of the account, as the phone lists it for the watch. */
class WatchList(val uri: String, val name: String, val subtitle: String)

/**
 * The phone's app: asked over the Wearable Data Layer (the link between the user's own phone and
 * watch: Bluetooth, the Wi-Fi or Google's servers), else straight over the Wi-Fi ([PassLink], with
 * the secret the phone gave over the Data Layer; the phone found again by its NSD announce when
 * its address changed). The watch never gets the phone's accounts: only the Wi-Fi link's
 * secret, kept sealed on the watch ([WatchStore]).
 */
class PhoneLink(private val context: Context) {
    private val store = WatchStore(context)

    private suspend fun ask(path: String, body: String = ""): String {
        val viaLayer = runCatching { askLayer(path, body) }
        viaLayer.getOrNull()?.let { return it }
        val viaLan = runCatching { askLan(path, body) }
        viaLan.getOrNull()?.let { return it }
        Log.d(TAG, "phone not reached for $path: ${viaLayer.exceptionOrNull()?.message}; Wi-Fi: ${viaLan.exceptionOrNull()?.message}")
        throw viaLayer.exceptionOrNull() ?: IllegalStateException("no phone")
    }

    private suspend fun askLayer(path: String, body: String): String {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: throw IllegalStateException("no phone connected")
        val answer = withTimeout(ASK_MS) { Wearable.getMessageClient(context).sendRequest(node.id, path, body.encodeToByteArray()).await() }
        return answer.decodeToString().also { if (it.startsWith("error:")) throw IllegalStateException(it.removePrefix("error:").trim()) }
    }

    /** The phone over the Wi-Fi: at its last address, else where its announce says it is now. */
    private suspend fun askLan(path: String, body: String): String = withContext(Dispatchers.IO) {
        val link = store.secret(LINK)?.let { Json.parseToJsonElement(it).jsonObject } ?: throw IllegalStateException("no Wi-Fi link yet")
        val secret = Base64.getUrlDecoder().decode(link.text("secret"))
        val port = link.text("port").toIntOrNull() ?: 0
        fun at(host: String, p: Int) = PassLink.ask(host, p, secret, path, body).also { if (it.startsWith("error:")) throw IllegalStateException(it.removePrefix("error:").trim()) }
        val last = link.text("host")
        runCatching { if (last.isNotBlank() && port > 0) return@withContext at(last, port) }
        val found = find(link.text("id")) ?: throw IllegalStateException("the phone is not on this network")
        store.putSecret(LINK, JsonObject(link + mapOf("host" to kotlinx.serialization.json.JsonPrimitive(found.first), "port" to kotlinx.serialization.json.JsonPrimitive(found.second.toString()))).toString())
        at(found.first, found.second)
    }

    /** The phone's announce on this network (`Orpheon-<id>`): its address and port, or null within a few seconds. */
    private suspend fun find(id: String): Pair<String, Int>? {
        if (id.isBlank()) return null
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        return withTimeoutOrNull(FIND_MS) {
            suspendCancellableCoroutine { cont ->
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(info: NsdServiceInfo) {
                        if (info.serviceName != "Orpheon-$id") return
                        @Suppress("DEPRECATION")
                        nsd.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onServiceResolved(r: NsdServiceInfo) {
                                @Suppress("DEPRECATION") val host = r.host?.hostAddress
                                if (host != null && cont.isActive) cont.resume(host to r.port)
                            }
                            override fun onResolveFailed(r: NsdServiceInfo, code: Int) {}
                        })
                    }
                    override fun onDiscoveryStarted(type: String) {}
                    override fun onDiscoveryStopped(type: String) {}
                    override fun onServiceLost(info: NsdServiceInfo) {}
                    override fun onStartDiscoveryFailed(type: String, code: Int) { if (cont.isActive) cont.resume(null) }
                    override fun onStopDiscoveryFailed(type: String, code: Int) {}
                }
                nsd.discoverServices("_orpheon._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
                cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(listener) } }
            }
        }
    }

    /** The Wi-Fi link's secret and where the phone is, asked over the Data Layer and kept. */
    suspend fun refreshLink() = lock.withLock {
        val read = Json.parseToJsonElement(askLayer(LINK_PATH, "")).jsonObject
        if (read.text("lanSecret").isNotBlank()) store.putSecret(LINK, JsonObject(mapOf(
            "secret" to read["lanSecret"]!!, "port" to kotlinx.serialization.json.JsonPrimitive(read.text("lanPort")),
            "id" to (read["lanId"] ?: kotlinx.serialization.json.JsonPrimitive("")), "host" to (read["lanHost"] ?: kotlinx.serialization.json.JsonPrimitive("")),
        )).toString())
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()

    /** The playlists, from the phone; the last ones read when it is not there. */
    suspend fun library(): List<WatchList> {
        val text = runCatching { ask(LIBRARY).also { store.put("library", it) } }.getOrElse { e -> store.get("library") ?: throw e }
        return Json.parseToJsonElement(text).jsonArray.map { e -> e.jsonObject.let { WatchList(it.text("uri"), it.text("name"), it.text("subtitle")) } }
    }

    /** The songs of the list [uri], from the phone; the last ones read when it is not there. */
    suspend fun tracks(uri: String): List<WatchTrack> {
        val text = runCatching { ask(TRACKS, uri).also { store.put("tracks-$uri", it) } }.getOrElse { e -> store.get("tracks-$uri") ?: throw e }
        return Json.parseToJsonElement(text).jsonArray.map { e ->
            val o = e.jsonObject
            WatchTrack(o.text("uri"), o.text("name"), o["artists"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(), o.text("album"),
                o["durationMs"]?.jsonPrimitive?.long ?: 0L, o.text("cover").ifBlank { null },
                o.text("isrc"), o["phoneOnly"]?.jsonPrimitive?.content == "true",
                o.text("localUri"), o["size"]?.jsonPrimitive?.long ?: 0L, o["modified"]?.jsonPrimitive?.long ?: 0L)
        }
    }

    /**
     * One of the phone's files, pulled for the watch's own player (Scadify v21): a Data
     * Layer channel to the phone's app — the watch writes the file's address (the
     * `localUri` its track answer carried) as one line, the phone answers "ok <bytes>"
     * and the file itself, or "error gone" when the file is not one it exposes or no
     * longer opens. [registerCloser] is handed the channel's close as soon as the
     * channel is open, so a caller standing guard over the pull can force it shut.
     * Throws [PullFailed]: UNREACHABLE when no channel to the phone could be had at
     * all, GONE when the phone answered that the file is not there.
     */
    suspend fun pullFile(localUri: String, registerCloser: ((() -> Unit)?) -> Unit = {}): FilePull {
        val nodes = try {
            Wearable.getNodeClient(context).connectedNodes.await()
        } catch (e: Exception) {
            throw PullFailed(PullFailed.Kind.UNREACHABLE, e)
        }
        val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            ?: throw PullFailed(PullFailed.Kind.UNREACHABLE)
        val client = Wearable.getChannelClient(context)
        var closer: (() -> Unit)? = null
        try {
            // The whole handshake is bounded (v33): a channel the phone never answers —
            // its app asleep, the opening never delivered — left this waiting without
            // end, and the song simply never started. The status line is read in an
            // async of its own because a stream read does not hear cancellation: the
            // deadline leaves it behind and closes the channel, which ends the read.
            // A pull already crossing keeps its caller's outer guard (WatchPulls).
            return withTimeout(HANDSHAKE_MS) {
                val channel = try {
                    client.openChannel(node.id, FILE).await()
                } catch (e: Exception) {
                    throw PullFailed(PullFailed.Kind.UNREACHABLE, e)
                }
                val close: () -> Unit = { runCatching { client.close(channel) } }
                closer = close
                registerCloser(close)
                async(Dispatchers.IO) { readAnswer(client, channel, localUri, close) }.await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            closer?.invoke()
            throw PullFailed(PullFailed.Kind.UNREACHABLE, e)
        }
    }

    /** The pull's first exchange, on an IO thread: the file's address out, the phone's one-line answer in. */
    private suspend fun readAnswer(
        client: com.google.android.gms.wearable.ChannelClient,
        channel: com.google.android.gms.wearable.ChannelClient.Channel,
        localUri: String,
        closer: () -> Unit,
    ): FilePull {
        try {
            client.getOutputStream(channel).await().also { out ->
                out.write((localUri + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val input = client.getInputStream(channel).await()
            val status = readStatusLine(input)
            if (status.startsWith("ok")) {
                return FilePull(input, status.removePrefix("ok").trim().toLongOrNull() ?: -1L, closer)
            }
            closer()
            throw PullFailed(PullFailed.Kind.GONE)
        } catch (e: PullFailed) {
            throw e
        } catch (e: Exception) {
            closer()
            throw PullFailed(PullFailed.Kind.UNREACHABLE, e)
        }
    }

    /** The phone's one-line answer on a file channel, read byte by byte (the file follows it). */
    private fun readStatusLine(input: java.io.InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 256) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            bytes.write(b)
        }
        return bytes.toString(Charsets.UTF_8.name()).trim()
    }

    /** What plays on the phone, asked fresh; the answer is kept as Phone mode's state. */
    suspend fun nowPlaying(): PhonePlaying {
        val text = ask(NOW_PLAYING)
        return PhonePlayingJson.parse(text).also { store.put("nowplaying", text); PhonePlayingState.update(it) }
    }

    /** A command for the phone's player ("play", "pause", "next", "previous", "seek:<ms>"); the phone answers with what plays then. */
    suspend fun control(command: String): PhonePlaying {
        val text = ask(CONTROL, command)
        return PhonePlayingJson.parse(text).also { store.put("nowplaying", text); PhonePlayingState.update(it) }
    }

    /** The phone's extensions (packages and state, as the pass carries them): through a Data Layer channel, else the Wi-Fi. */
    suspend fun extensions(): String = runCatching {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        val node = nodes.firstOrNull { it.isNearby } ?: nodes.first()
        val client = Wearable.getChannelClient(context)
        val channel = client.openChannel(node.id, EXTENSIONS).await()
        try {
            withTimeout(EXTENSIONS_MS) { withContext(Dispatchers.IO) { client.getInputStream(channel).await().use { it.readBytes().decodeToString() } } }
        } finally { runCatching { client.close(channel).await() } }
    }.getOrElse { askLan(EXTENSIONS, "") }.also { if (it.startsWith("error:")) throw IllegalStateException(it.removePrefix("error:").trim()) }

    companion object {
        const val LINK_PATH = "/orpheon/session"
        const val LIBRARY = "/orpheon/library"
        const val TRACKS = "/orpheon/tracks"
        const val EXTENSIONS = "/orpheon/extensions"
        const val NOW_PLAYING = "/scadify/nowplaying"
        const val CONTROL = "/scadify/control"
        const val FILE = "/scadify/file"
        private const val TAG = "Orpheon"
        private const val LINK = "link"
        private const val ASK_MS = 20_000L
        private const val FIND_MS = 5_000L
        private const val EXTENSIONS_MS = 60_000L
        /** A file pull's handshake — the channel open and the phone's one-line answer — never waits longer. */
        private const val HANDSHAKE_MS = 30_000L
        private val lock = Mutex()
    }
}

/**
 * A song of a list, as the phone gives it for the watch's extensions. [isrc]: the recording's
 * fingerprint where the phone's store has it. [phoneOnly]: a file of the phone with no
 * catalogue identity, so the extensions cannot fetch it. A phone-only song that carries a
 * [localUri] — the file's address on the phone, with its [fileSize] and [fileModified]
 * (epoch seconds) where the phone could state them — is one the watch pulls and plays
 * itself (Scadify v21, [WatchPulls]); a phone-only song without one is an imported song
 * with no identity kept, which the watch cannot play at all.
 */
class WatchTrack(
    val uri: String, val name: String, val artists: List<String>, val album: String,
    val durationMs: Long, val cover: String?, val isrc: String = "", val phoneOnly: Boolean = false,
    val localUri: String = "", val fileSize: Long = 0L, val fileModified: Long = 0L,
) {
    /** A phone file the watch can pull and play in Watch mode. */
    val pullable: Boolean get() = phoneOnly && localUri.isNotBlank()
}

/**
 * A phone file on its way to the watch (Scadify v21): the file's bytes and their count
 * (-1 where the phone did not say). Closing releases the channel.
 */
class FilePull(
    val input: java.io.InputStream,
    val size: Long,
    private val closer: () -> Unit,
) : java.io.Closeable {
    @Volatile private var closed = false
    override fun close() {
        if (closed) return
        closed = true
        runCatching { input.close() }
        closer()
    }
}

/** A pull that could not be had: the phone could not be reached, or the file is not there. */
class PullFailed(val kind: Kind, cause: Throwable? = null) : Exception(kind.name, cause) {
    enum class Kind { UNREACHABLE, GONE }
}
