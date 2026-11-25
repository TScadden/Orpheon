package app.myspoti.wear

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What plays on the phone, as the phone sends it for Phone mode: the song, whether it plays,
 * where it is, and its sleeve — its web address when it has one, else the picture itself as a
 * small JPEG ([artJpeg]), for a phone file's art the watch cannot address.
 */
class PhonePlaying(
    val title: String, val artist: String, val album: String, val playing: Boolean,
    val positionMs: Long, val durationMs: Long, val coverUrl: String?, val artJpeg: ByteArray?,
) {
    val hasSong: Boolean get() = title.isNotBlank()
}

/** The phone's now-playing payload, read. The phone writes the same shape (its own PhonePlayingJson). Pure. */
object PhonePlayingJson {
    fun parse(text: String): PhonePlaying {
        val o = Json.parseToJsonElement(text).jsonObject
        fun str(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
        fun num(k: String) = str(k).toLongOrNull() ?: 0L
        return PhonePlaying(
            title = str("title"), artist = str("artist"), album = str("album"),
            playing = str("playing") == "true", positionMs = num("positionMs"), durationMs = num("durationMs"),
            coverUrl = str("cover").ifBlank { null },
            artJpeg = str("art").takeIf { it.isNotBlank() }?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() },
        )
    }
}

/**
 * Phone mode's state on the watch: the last now-playing the phone pushed or answered, kept
 * between runs (the raw payload in [WatchStore]) so the page opens on the song, not on a wait.
 * [atElapsedMs] is when the current state landed, on [SystemClock.elapsedRealtime]: the player
 * page predicts the position from it while the song plays.
 */
object PhonePlayingState {
    private val _state = MutableStateFlow<PhonePlaying?>(null)
    val state: StateFlow<PhonePlaying?> = _state

    @Volatile var atElapsedMs: Long = 0L
        private set

    fun update(playing: PhonePlaying) {
        atElapsedMs = SystemClock.elapsedRealtime()
        _state.value = playing
    }

    /** The kept payload, read once when the app opens. */
    fun load(context: Context) {
        if (_state.value != null) return
        runCatching { WatchStore(context.applicationContext).get("nowplaying") }
            .getOrNull()?.let { raw -> runCatching { update(PhonePlayingJson.parse(raw)) } }
    }
}

/** The phone's pushes land here (it sends whenever its song or play state changes). */
class PhoneNowPlayingService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PhoneLink.NOW_PLAYING) return
        val text = event.data.decodeToString()
        runCatching { PhonePlayingState.update(PhonePlayingJson.parse(text)) }
        runCatching { WatchStore(applicationContext).put("nowplaying", text) }
    }
}
