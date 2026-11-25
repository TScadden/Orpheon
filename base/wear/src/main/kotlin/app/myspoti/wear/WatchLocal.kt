package app.myspoti.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import app.myspoti.extensions.AudioStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A list played on the watch from its own extensions ([WatchExtensions]): each song's audio is
 * asked as it comes (the next one while the current one plays) and played by a plain player,
 * straight from the network. A song no extension has is passed; after a few in a row the list stops.
 */
object WatchLocal {
    private const val TAG = "Orpheon"
    private const val MAX_MISSES = 5

    class Now(val name: String, val artists: String, val playing: Boolean, val source: String, val cover: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: ExoPlayer? = null
    private var tracks: List<WatchTrack> = emptyList()
    private var index = 0
    private var source = ""
    private var ahead: Deferred<Pair<AudioStream, String>?>? = null
    private var aheadIndex = -1
    private lateinit var app: Context

    private val _now = MutableStateFlow<Now?>(null)
    /** The song played from an extension; null when the watch plays from the account (or nothing). */
    val now: StateFlow<Now?> = _now
    val active: Boolean get() = _now.value != null

    /** Plays [list] (the list [uri]) from [start], whose audio [first] was already found, from [positionMs]. */
    fun play(context: Context, list: List<WatchTrack>, start: Int, first: Pair<AudioStream, String>, uri: String = "", positionMs: Long = 0L) {
        app = context.applicationContext
        tracks = list; index = start
        load(first, positionMs)
    }

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(app).build().also { p ->
        // Music, as media: the attributes are what route the sound to a Bluetooth car kit paired
        // to the watch (and give the player audio focus like any media app).
        p.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        p.setHandleAudioBecomingNoisy(true)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) move(1) }
            override fun onIsPlayingChanged(isPlaying: Boolean) { publish() }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { Log.w(TAG, "watch player: ${error.message}"); move(1) }
        })
        player = p
    }

    // ── The media session a car kit or headset drives ──────────────────────────────────────

    private var session: android.media.session.MediaSession? = null

    /**
     * The watch's media session, published while its own player plays: the system's Bluetooth
     * stack reads the active session for the car kit paired to the watch — its metadata is what
     * the car shows, its callback is what the car's play / pause / skip buttons land on.
     */
    private fun ensureSession(): android.media.session.MediaSession = session ?: android.media.session.MediaSession(app, "Scadify").also { s ->
        s.setCallback(object : android.media.session.MediaSession.Callback() {
            override fun onPlay() = resume()
            override fun onPause() = pause()
            override fun onSkipToNext() = next()
            override fun onSkipToPrevious() = previous()
            override fun onSeekTo(pos: Long) = seekTo(pos)
            override fun onStop() = stop()
        })
        @Suppress("DEPRECATION")
        s.setFlags(android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session = s
    }

    /** The session's token, for the playback notification's media style. */
    fun sessionToken(): android.media.session.MediaSession.Token? = session?.sessionToken

    /** What plays, as the session states it (called wherever the screen's own state is published). */
    private fun publishSession(playing: Boolean) {
        if (!::app.isInitialized) return
        val t = tracks.getOrNull(index) ?: return
        val s = ensureSession()
        val p = player
        s.setMetadata(android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, t.name)
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, t.artists.joinToString(", "))
            .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, t.album)
            .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, p?.duration?.takeIf { it > 0 } ?: t.durationMs)
            .build())
        s.setPlaybackState(android.media.session.PlaybackState.Builder()
            .setActions(android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE
                or android.media.session.PlaybackState.ACTION_PLAY_PAUSE or android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
                or android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS or android.media.session.PlaybackState.ACTION_SEEK_TO
                or android.media.session.PlaybackState.ACTION_STOP)
            .setState(
                if (playing) android.media.session.PlaybackState.STATE_PLAYING else android.media.session.PlaybackState.STATE_PAUSED,
                p?.currentPosition ?: 0L, if (playing) 1f else 0f,
            )
            .build())
        s.isActive = true
    }

    private fun load(found: Pair<AudioStream, String>, positionMs: Long = 0L) {
        val (stream, name) = found
        source = stream.qualityLabel()?.let { "$name · $it" } ?: name
        val p = ensurePlayer()
        p.setMediaSource(sourceOf(stream), positionMs)
        p.prepare(); p.play()
        publish(playing = true)
        WatchPlaybackService.follow(app, true)
        // The next song's audio, asked while this one plays.
        aheadIndex = index + 1
        ahead = tracks.getOrNull(aheadIndex)?.let { t -> scope.async(Dispatchers.IO) { WatchExtensions.audio(t, ahead = true) } }
    }

    private fun sourceOf(s: AudioStream): MediaSource {
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(s.headers).setAllowCrossProtocolRedirects(true)
        val data = DefaultDataSource.Factory(app, http)
        return if (s.manifest != null) {
            // A DASH manifest the extension gave whole: read from a data: URI.
            DashMediaSource.Factory(data).createMediaSource(
                MediaItem.Builder().setUri(Uri.parse("data:application/dash+xml;base64," + s.manifest)).setMimeType(MimeTypes.APPLICATION_MPD).build())
        } else ProgressiveMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(s.url))
    }

    /** To the song [by] places away whose audio an extension has (passing those none has). */
    private fun move(by: Int) {
        scope.launch {
            var misses = 0
            var i = index + by
            while (i in tracks.indices && misses < MAX_MISSES) {
                // A phone file is not asked of the extensions: it is a miss by definition (Phone mode plays it).
                val found = if (tracks[i].phoneOnly) null
                    else if (i == aheadIndex) ahead?.await() else WatchExtensions.audio(tracks[i])
                if (found != null) { index = i; load(found); return@launch }
                misses++; i += if (by < 0) -1 else 1
            }
            stop()
        }
    }

    private fun publish(playing: Boolean = player?.isPlaying == true) {
        val t = tracks.getOrNull(index) ?: return
        _now.value = Now(t.name, t.artists.joinToString(", "), playing, source, t.cover)
        publishSession(playing)
    }

    fun toggle() { player?.let { if (it.playWhenReady) it.pause() else it.play() }; publish() }
    fun pause() { player?.pause(); publish(false) }
    fun resume() { player?.play(); publish() }
    fun seekTo(ms: Long) { player?.seekTo(ms) }
    fun next() = move(1)
    fun previous() { val p = player; if (p != null && p.currentPosition > 3_000) p.seekTo(0) else move(-1) }

    fun stop() {
        player?.release(); player = null
        ahead?.cancel(); ahead = null
        session?.isActive = false
        if (_now.value != null) {
            _now.value = null
            if (::app.isInitialized) WatchPlaybackService.follow(app, false)
        }
    }

    /** Where the song is now, for the screen's ring; null with nothing loaded. */
    fun position(): WatchPosition? {
        val p = player ?: return null
        val t = tracks.getOrNull(index) ?: return null
        return WatchPosition(p.currentPosition, p.duration.takeIf { it > 0 } ?: t.durationMs, p.playbackState == Player.STATE_BUFFERING)
    }

    /** The songs after the current one, in order. */
    fun queued(): List<WatchTrack> = tracks.drop(index + 1)

}

/** Where the watch's song is: position, length, and whether it waits for data. */
data class WatchPosition(val positionMs: Long, val durationMs: Long, val buffering: Boolean)
