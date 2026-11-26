package app.myspoti.wear

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.android.horologist.audio.ui.VolumeUiState
import com.google.android.horologist.images.base.paintable.BitmapPaintable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * The watch's pages: what plays (its name, its artists, previous, play or pause, next, the
 * volume), then, a swipe away, the playlists as the phone lists them — a tap opens one, and
 * its Play or Shuffle button or a song's tap plays it on the watch from its own sources
 * ([WatchPlay]: the extensions, and pulls of the phone's own files) — and the mode switch.
 * Two modes ([WatchModes]): Phone, where the player page mirrors the phone's song and its
 * buttons drive the phone's player; Watch, where the watch plays on its own. (The library
 * is a Watch-mode page; Phone mode has none.)
 */
class WatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The Wi-Fi link to the phone, for when the Data Layer is not there.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { PhoneLink(applicationContext).refreshLink() } }
        setContent { WatchTheme { WatchApp() } }
    }
}

/** The pages fed from the players, the phone and the extensions ([WatchPager] draws them). */
@Composable
internal fun WatchApp() {
    val context = LocalContext.current
    val app = context.applicationContext
    val localNow by WatchLocal.now.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    val extensions by WatchExtensions.installed.collectAsState()
    val ui = rememberCoroutineScope()
    var syncNote by remember { mutableStateOf<String?>(null) }
    remember { WatchExtensions.init(app); WatchModes.init(app); PhonePlayingState.load(app); WearUpdatePref.init(app); WatchPulls.init(app) }
    // The on-watch songs (v21's pulls): their size for the mode page, refreshed when a
    // play starts a pull and when the page's Clear empties them.
    var pullsVersion by remember { mutableStateOf(0) }
    val pullsUsageMb = remember(pullsVersion) { (WatchPulls.usageBytes(app) / (1024 * 1024)).toInt() }
    val mode by WatchModes.mode.collectAsState()
    // The extensions' player gives no position on its own: read every second while it has a song.
    val local = localNow
    var localPosition by remember { mutableStateOf<WatchPosition?>(null) }
    LaunchedEffect(local != null) {
        while (local != null) { localPosition = WatchLocal.position(); delay(1_000) }
        localPosition = null
    }
    val watchPlaying = local?.let {
        val p = localPosition
        WatchPlaying(it.name, it.artists, it.playing, it.source, it.cover, p?.positionMs ?: 0L, p?.durationMs ?: 0L, SystemClock.elapsedRealtime(), p?.buffering == true)
    }
    // Phone mode: the phone's song, asked for on entering the mode and refreshed while in it
    // (its pushes land in the same state between asks).
    val phone by PhonePlayingState.state.collectAsState()
    var phoneError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mode) {
        if (mode != WatchMode.PHONE) return@LaunchedEffect
        // Phone mode is the phone's stage: the watch's own player falls silent.
        if (WatchLocal.active) WatchLocal.stop()
        while (true) {
            runCatching { PhoneLink(app).nowPlaying() }
                .onSuccess { phoneError = null }
                .onFailure { if (PhonePlayingState.state.value?.hasSong != true) phoneError = context.getString(R.string.wear_no_phone) }
            delay(10_000)
        }
    }
    fun phoneControl(command: String) {
        ui.launch {
            runCatching { PhoneLink(app).control(command) }
                .onSuccess { phoneError = null }
                .onFailure { phoneError = context.getString(R.string.wear_no_phone) }
        }
    }
    val phonePlaying = phone?.takeIf { it.hasSong }?.let {
        WatchPlaying(it.title, it.artist, it.playing, stringResource(R.string.wear_on_phone), it.coverUrl,
            it.positionMs, it.durationMs, PhonePlayingState.atElapsedMs, buffering = false)
    }
    val playing = if (mode == WatchMode.PHONE) phonePlaying else watchPlaying
    // The phone's sleeve itself, when it sent one (a phone file's art has no address to fetch).
    val phoneArt = remember(phone?.artJpeg) {
        phone?.artJpeg?.let { bytes -> runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull() }
    }
    val artwork = remember(mode, phoneArt, playing?.cover) {
        if (mode == WatchMode.PHONE && phoneArt != null) BitmapPaintable(phoneArt)
        else playing?.cover?.let { watchArtwork(context, it) }
    }
    var attempt by remember { mutableStateOf(0) }
    // null: being read; a failure: the phone is not there.
    val lists by produceState<Result<List<WatchList>>?>(null, attempt) { value = runCatching { PhoneLink(app).library() } }
    // The list opened from the library (v19: a tap opens it instead of playing it) and its
    // songs, read from the phone (the last ones read when it is not there).
    var openList by remember { mutableStateOf<WatchList?>(null) }
    var tracksAttempt by remember { mutableStateOf(0) }
    val openTracks by produceState<Result<List<WatchTrack>>?>(null, openList, tracksAttempt) {
        value = openList?.let { runCatching { PhoneLink(app).tracks(it.uri) } }
    }
    LaunchedEffect(mode) { if (mode != WatchMode.WATCH) openList = null }

    // The watch app's own updates (Scadify v16), the phone's way: a check when the app
    // opens (the checker itself skips when the last one is under 3 hours old), then every
    // 3 hours while it stays open; the mode page carries the switch and the manual check.
    val updateAuto by WearUpdatePref.on.collectAsState()
    var updatePrompt by remember { mutableStateOf<WearUpdateChecker.Release?>(null) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateIsError by remember { mutableStateOf(false) }
    var updateCheckedAt by remember { mutableLongStateOf(WearUpdateChecker.lastChecked(app)) }
    LaunchedEffect(Unit) {
        while (true) {
            if (WearUpdatePref.on.value) {
                runCatching { WearUpdateChecker.check(app) }.getOrNull()?.let { updatePrompt = it }
            }
            delay(WearUpdateChecker.PERIODIC_MS)
        }
    }
    fun checkUpdatesNow() {
        if (updateChecking) return
        updateChecking = true; updateMessage = null; updateIsError = false
        ui.launch {
            val outcome = runCatching { WearUpdateChecker.refresh(app) }.getOrElse { WearUpdateChecker.Outcome.Failed(false) }
            updateChecking = false
            updateCheckedAt = WearUpdateChecker.lastChecked(app)
            when (outcome) {
                is WearUpdateChecker.Outcome.Found -> {
                    updateMessage = context.getString(R.string.wear_update_available, outcome.release.version)
                    updatePrompt = outcome.release
                }
                WearUpdateChecker.Outcome.UpToDate -> updateMessage = context.getString(R.string.wear_update_uptodate)
                is WearUpdateChecker.Outcome.Failed -> {
                    updateIsError = true
                    updateMessage = context.getString(if (outcome.rateLimited) R.string.wear_update_rate_limited else R.string.wear_update_check_failed)
                }
            }
        }
    }
    val updaterState by WearUpdater.state.collectAsState()
    // Back from the "Install unknown apps" page with the switch on: carry on by itself.
    LaunchedEffect(updaterState, updatePrompt) {
        val r = updatePrompt ?: return@LaunchedEffect
        while (WearUpdater.state.value == WearUpdater.State.NeedsPermission) {
            if (WearUpdater.canInstall(app)) { ui.launch { WearUpdater.update(app, r) }; break }
            delay(1_000)
        }
    }
    fun installUpdate() {
        val r = updatePrompt ?: return
        when {
            updaterState == WearUpdater.State.NeedsPermission -> WearUpdater.openInstallSettings(context)
            updaterState is WearUpdater.State.Downloading || updaterState == WearUpdater.State.Installing -> Unit
            else -> ui.launch { WearUpdater.update(context, r) }
        }
    }
    fun dismissUpdate() {
        val r = updatePrompt ?: return
        WearUpdater.reset(); WearUpdateChecker.dismiss(app, r); updatePrompt = null
    }

    val prompt = updatePrompt
    if (prompt != null) {
        WatchUpdatePrompt(
            release = prompt,
            state = updaterState,
            onInstall = { installUpdate() },
            onLater = { dismissUpdate() },
        )
        return
    }

    WatchPager(
        playing = playing,
        error = if (mode == WatchMode.PHONE) phoneError else error,
        onPrevious = { if (mode == WatchMode.PHONE) phoneControl("previous") else WatchLocal.previous() },
        onToggle = {
            if (mode == WatchMode.PHONE) phoneControl(if (phone?.playing == true) "pause" else "play")
            else WatchLocal.toggle()
        },
        onNext = { if (mode == WatchMode.PHONE) phoneControl("next") else WatchLocal.next() },
        volume = rememberWatchVolume(),
        lists = lists,
        onOpen = { openList = it },
        onRetry = { attempt++ },
        openList = openList,
        openTracks = openTracks,
        onCloseList = { openList = null },
        onPlayList = { error = null; pullsVersion++; WatchPlay.play(context, it.uri) { e -> error = e } },
        onShuffleList = { error = null; pullsVersion++; WatchPlay.playShuffled(context, it.uri) { e -> error = e } },
        onPlayTrack = { i ->
            val opened = openList
            val songs = openTracks?.getOrNull()
            if (opened != null && songs != null) {
                error = null
                pullsVersion++
                WatchPlay.playTrack(context, opened.uri, songs, i) { e -> error = e }
            }
        },
        pullsUsage = stringResource(R.string.wear_pulls_usage, pullsUsageMb),
        onClearPulls = { WatchPulls.clear(app); pullsVersion++ },
        onRetryTracks = { tracksAttempt++ },
        extensionsNote = syncNote ?: stringResource(R.string.wear_extensions_count, extensions.size),
        onSyncExtensions = {
            syncNote = context.getString(R.string.wear_loading)
            ui.launch {
                syncNote = runCatching { WatchExtensions.sync(app) }
                    .fold({ n -> context.getString(R.string.wear_extensions_synced, n) }, { e -> context.getString(R.string.wear_failed, e.message ?: "?") })
            }
        },
        artwork = artwork,
        mode = mode,
        onModeChange = { WatchModes.set(it) },
        updateAuto = updateAuto,
        onUpdateAutoChange = { WearUpdatePref.set(app, it) },
        updateChecking = updateChecking,
        updateMessage = updateMessage,
        updateIsError = updateIsError,
        updateLastChecked = if (updateCheckedAt <= 0L) stringResource(R.string.wear_update_never)
            else stringResource(R.string.wear_update_last_checked,
                android.text.format.DateUtils.getRelativeTimeSpanString(updateCheckedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)),
        onCheckUpdates = { checkUpdatesNow() },
    )
}

/** The media volume: the crown sets it (its level shown a moment), the button opens the system's volume panel. */
@Composable
private fun rememberWatchVolume(): WatchVolumeUi {
    val context = LocalContext.current
    return remember(context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun read() = VolumeUiState(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        var level by mutableStateOf(read())
        val shown = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        WatchVolumeUi(
            state = { level },
            shown = shown,
            set = { v -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0); level = read(); shown.tryEmit(Unit) },
            open = { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI); level = read() },
        )
    }
}

/**
 * A list played on the watch: its songs (from the phone, or the last ones read) play
 * from the watch's own sources — the extensions for catalogue songs, and, since v21,
 * pulls from the phone for the phone's own files. With nothing playable, nothing
 * plays and the screen says why.
 */
object WatchPlay {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    /** The list in its own order, from its first song the watch can play. */
    fun play(context: android.content.Context, uri: String, failed: (String) -> Unit) =
        playOrdered(context, uri, shuffle = false, failed)

    /** The list in a fresh random order (v21's Shuffle), from its first playable song. */
    fun playShuffled(context: android.content.Context, uri: String, failed: (String) -> Unit) =
        playOrdered(context, uri, shuffle = true, failed)

    private fun playOrdered(context: android.content.Context, uri: String, shuffle: Boolean, failed: (String) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            WatchExtensions.init(app)
            val fetched = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { PhoneLink(app).tracks(uri) }.getOrNull().orEmpty()
            }
            val tracks = order(fetched, shuffle)
            val start = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { firstPlayable(app, tracks) }
            if (start != null) {
                WatchLocal.play(app, tracks, start.first, start.second, uri = uri, failed = failed)
            } else {
                WatchLocal.stop()
                failed(app.getString(
                    if (tracks.isNotEmpty() && tracks.all { it.phoneOnly }) R.string.wear_phone_only else R.string.wear_no_source))
            }
        }
    }

    /**
     * The first song of [tracks] the watch can play, with its audio resolved: a phone
     * file's pull is started, another song is asked of the extensions (the first few
     * tried). A phone-only song with no file address is passed over — the watch cannot
     * play it at all.
     */
    private suspend fun firstPlayable(app: android.content.Context, tracks: List<WatchTrack>): Pair<Int, WatchSource>? {
        var extensionTries = 0
        for (i in tracks.indices) {
            val t = tracks[i]
            when {
                t.pullable -> return i to WatchSource.Pulled(WatchPulls.open(app, t))
                t.phoneOnly -> Unit
                extensionTries < 5 -> {
                    extensionTries++
                    if (WatchExtensions.hasAudio()) {
                        WatchExtensions.audio(t)?.let { return i to WatchSource.Stream(it) }
                    }
                }
            }
        }
        return null
    }

    /** The order a list plays in: its own, or a fresh shuffle (v21). */
    internal fun order(tracks: List<WatchTrack>, shuffle: Boolean): List<WatchTrack> =
        if (shuffle) tracks.shuffled() else tracks

    /** A song tapped in an opened list: the list plays on the watch from that song. */
    fun playTrack(context: android.content.Context, uri: String, tracks: List<WatchTrack>, index: Int, failed: (String) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            WatchExtensions.init(app)
            val track = tracks.getOrNull(index) ?: return@launch
            if (track.pullable) {
                WatchLocal.play(app, tracks, index, WatchSource.Pulled(WatchPulls.open(app, track)), uri = uri, failed = failed)
                return@launch
            }
            if (track.phoneOnly) {
                failed(app.getString(R.string.wear_phone_only_one))
                return@launch
            }
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (WatchExtensions.hasAudio()) WatchExtensions.audio(track) else null
            }
            if (found != null) {
                WatchLocal.play(app, tracks, index, WatchSource.Stream(found), uri = uri, failed = failed)
            } else {
                WatchLocal.stop()
                failed(app.getString(R.string.wear_no_source))
            }
        }
    }
}
