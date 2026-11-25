package com.music.spotui.wear

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import app.myspoti.host.android.Logbook
import com.google.android.gms.wearable.Wearable
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

/** What the phone plays, as the watch's Phone mode shows it. Pure data: built on the phone, read on the watch. */
data class PhonePlayingSnapshot(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** The sleeve's http(s) address when the watch can fetch it itself; "" otherwise. */
    val coverUrl: String = "",
) {
    val hasSong: Boolean get() = title.isNotBlank()
    /** What identifies a change worth pushing (the position is left out: the watch predicts it). */
    val key: String get() = "$title|$artist|$album|$playing|$durationMs|$coverUrl"
}

/** The now-playing payload as JSON: [PhonePlayingSnapshot]'s fields, plus the sleeve itself when it is not on the web. */
object PhonePlayingJson {
    /** [artJpegBase64]: the sleeve as a small JPEG, Base64 — only for sleeves with no http(s) address (a phone file's art). */
    fun encode(s: PhonePlayingSnapshot, artJpegBase64: String? = null): String = buildJsonObject {
        put("title", s.title); put("artist", s.artist); put("album", s.album)
        put("playing", s.playing); put("positionMs", s.positionMs); put("durationMs", s.durationMs)
        if (s.coverUrl.startsWith("http")) put("cover", s.coverUrl)
        if (artJpegBase64 != null) put("art", artJpegBase64)
    }.toString()

    /** The watch reads the same shape with its own parser; this one keeps the pair honest in tests. */
    fun decode(text: String): Pair<PhonePlayingSnapshot, String?> {
        val o = Json.parseToJsonElement(text).jsonObject
        fun str(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
        fun num(k: String) = str(k).toLongOrNull() ?: 0L
        return PhonePlayingSnapshot(
            title = str("title"), artist = str("artist"), album = str("album"),
            playing = str("playing") == "true", positionMs = num("positionMs"), durationMs = num("durationMs"),
            coverUrl = str("cover"),
        ) to o["art"]?.jsonPrimitive?.content
    }
}

/** A command the watch sends the phone's player, parsed from the control path's body. Pure. */
object PhoneControl {
    enum class Command { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, SEEK, UNKNOWN }

    data class Parsed(val command: Command, val seekMs: Long = 0L)

    fun parse(body: String): Parsed = when (val b = body.trim()) {
        "play" -> Parsed(Command.PLAY)
        "pause" -> Parsed(Command.PAUSE)
        "toggle" -> Parsed(Command.TOGGLE)
        "next" -> Parsed(Command.NEXT)
        "previous" -> Parsed(Command.PREVIOUS)
        else -> if (b.startsWith("seek:")) {
            b.removePrefix("seek:").toLongOrNull()?.let { Parsed(Command.SEEK, it) } ?: Parsed(Command.UNKNOWN)
        } else Parsed(Command.UNKNOWN)
    }

    /** Runs a parsed command on the phone's player (the same entry points the media session uses). */
    fun execute(parsed: Parsed) {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                when (parsed.command) {
                    Command.PLAY -> SongPlayer.play(by = "watch")
                    Command.PAUSE -> SongPlayer.pause(by = "watch")
                    Command.TOGGLE -> if (SongPlayer.isPlaying()) SongPlayer.pause(by = "watch") else SongPlayer.play(by = "watch")
                    Command.NEXT -> SongPlayer.next(by = "watch")
                    Command.PREVIOUS -> SongPlayer.previous(by = "watch")
                    Command.SEEK -> SongPlayer.seekTo(parsed.seekMs)
                    Command.UNKNOWN -> Unit
                }
            }.onFailure { Logbook.log("watch: control not run (${it.message})") }
        }
    }
}

/**
 * The phone's side of Phone mode: what plays here, for the watch to mirror — answered when the
 * watch asks ([WatchAnswers]) and pushed to the connected watch whenever the song or the
 * play state changes (plus a slow heartbeat while playing, to re-anchor the position the
 * watch predicts between pushes).
 */
object PhoneNowPlaying {
    private const val HEARTBEAT_MS = 30_000L
    private const val ART_PX = 120
    private const val ART_MAX_BYTES = 40_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    @Volatile private var lastKey = ""
    @Volatile private var lastPushAt = 0L
    @Volatile private var artFor = ""
    @Volatile private var artCache: String? = null

    /** The song as the phone's own screens state it, the player's clock for the position. */
    fun snapshot(): PhonePlayingSnapshot {
        val st = Graph.currentSongState
        val current = runCatching { SongPlayer.engineQueue.value.current }.getOrNull()
        return PhonePlayingSnapshot(
            title = st.title.value.ifBlank { current?.title.orEmpty() },
            artist = st.singer.value.ifBlank { current?.singer.orEmpty() },
            album = st.album.value.ifBlank { current?.album.orEmpty() },
            playing = runCatching { SongPlayer.isPlaying() }.getOrDefault(st.playingState.value),
            positionMs = runCatching { SongPlayer.getCurrentPosition() }.getOrDefault(0L).coerceAtLeast(0L),
            durationMs = runCatching { SongPlayer.getDuration() }.getOrDefault(0L).takeIf { it > 0 }
                ?: (current?.durationMs?.toLong() ?: 0L),
            coverUrl = st.coverUri.value.ifBlank { current?.coverUri.orEmpty() },
        )
    }

    /** The full answer for the watch: the snapshot, with the sleeve attached when it is not on the web. */
    suspend fun payload(context: Context): String {
        val s = snapshot()
        val art = if (s.coverUrl.isNotBlank() && !s.coverUrl.startsWith("http")) artBase64(context, s.coverUrl) else null
        return PhonePlayingJson.encode(s, art)
    }

    /** Watches the playing song from the app's start and pushes its changes to the connected watch. */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            val st = Graph.currentSongState
            androidx.compose.runtime.snapshotFlow {
                listOf(st.title.value, st.singer.value, st.album.value, st.coverUri.value, st.playingState.value, SongPlayer.nowPlaying.value.uri)
            }.distinctUntilChanged().collectLatest { push(app) }
        }
        scope.launch {
            while (true) {
                delay(HEARTBEAT_MS)
                val s = runCatching { snapshot() }.getOrNull() ?: continue
                if (s.playing && s.hasSong) runCatching { push(app, force = true) }
            }
        }
    }

    private suspend fun push(context: Context, force: Boolean = false) {
        val s = snapshot()
        val now = System.currentTimeMillis()
        if (!force && s.key == lastKey && now - lastPushAt < HEARTBEAT_MS) return
        lastKey = s.key; lastPushAt = now
        val text = payload(context)
        val nodes = runCatching { Wearable.getNodeClient(context).connectedNodes.await() }.getOrDefault(emptyList())
        val client = Wearable.getMessageClient(context)
        nodes.forEach { node ->
            runCatching { client.sendMessage(node.id, WatchAnswers.NOW_PLAYING, text.encodeToByteArray()).await() }
                .onFailure { Logbook.log("watch: now playing not pushed (${it.message})") }
        }
    }

    /** A phone-local sleeve (a file's art) shrunk for the watch, Base64; cached per sleeve. */
    private fun artBase64(context: Context, cover: String): String? {
        if (cover == artFor) return artCache
        val encoded = runCatching {
            val bitmap = com.bumptech.glide.Glide.with(context).asBitmap().load(cover).submit(ART_PX, ART_PX).get()
            var bytes = jpeg(bitmap, 80)
            if (bytes.size > ART_MAX_BYTES) bytes = jpeg(Bitmap.createScaledBitmap(bitmap, 72, 72, true), 60)
            if (bytes.size > ART_MAX_BYTES) null else Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrNull()
        artFor = cover; artCache = encoded
        return encoded
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
