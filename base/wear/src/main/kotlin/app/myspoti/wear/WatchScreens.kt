@file:OptIn(com.google.android.horologist.annotations.ExperimentalHorologistApi::class)

package app.myspoti.wear

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.dynamicColorScheme
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.google.android.horologist.audio.ui.VolumeUiState
import com.google.android.horologist.audio.ui.material3.VolumeLevelIndicator
import com.google.android.horologist.audio.ui.material3.components.actions.VolumeButton
import com.google.android.horologist.audio.ui.material3.volumeRotaryBehavior
import com.google.android.horologist.images.base.paintable.Paintable
import com.google.android.horologist.images.coil.CoilPaintable
import com.google.android.horologist.media.model.MediaPositionPredictor
import com.google.android.horologist.media.ui.material3.components.animated.AnimatedMediaControlButtons
import com.google.android.horologist.media.ui.material3.components.animated.MarqueeTextMediaDisplay
import com.google.android.horologist.media.ui.material3.components.background.RadialBackground
import com.google.android.horologist.media.ui.material3.components.display.MessageMediaDisplay
import com.google.android.horologist.media.ui.material3.screens.player.PlayerScreen
import com.google.android.horologist.media.ui.state.model.TrackPositionUiModel
import coil.request.ImageRequest
import kotlinx.coroutines.flow.Flow

/**
 * The watch's screens, drawn from plain state (the singletons are read in [WatchApp]): a player
 * page (Horologist's media UI on Wear Material 3) and, a swipe to the left, the library page.
 */

/** What plays, from the extensions ([source] set) or from the account. [atElapsedMs]: [positionMs]'s time on SystemClock.elapsedRealtime. */
internal class WatchPlaying(
    val name: String, val artists: String, val playing: Boolean, val source: String?, val cover: String?,
    val positionMs: Long = 0L, val durationMs: Long = 0L, val atElapsedMs: Long = 0L, val buffering: Boolean = false,
)

/** The watch's Material 3 theme: the system's dynamic colours where the watch has them. */
@Composable
internal fun WatchTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = remember(context) { runCatching { dynamicColorScheme(context) }.getOrNull() ?: ColorScheme() }
    MaterialTheme(colorScheme = colors, content = content)
}

/** The volume as the player shows it: its level, the moments the level indicator shows, and its changes. */
internal class WatchVolumeUi(
    val state: () -> VolumeUiState,
    val shown: Flow<Unit>?,
    val set: (Int) -> Unit,
    val open: () -> Unit,
)

@Composable
internal fun WatchPager(
    playing: WatchPlaying?,
    error: String?,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    volume: WatchVolumeUi,
    lists: Result<List<WatchList>>?,
    onOpen: (WatchList) -> Unit,
    onRetry: () -> Unit,
    extensionsNote: String,
    onSyncExtensions: () -> Unit,
    openList: WatchList? = null,
    openTracks: Result<List<WatchTrack>>? = null,
    onCloseList: () -> Unit = {},
    onPlayList: (WatchList) -> Unit = {},
    onShuffleList: (WatchList) -> Unit = {},
    onPlayTrack: (Int) -> Unit = {},
    onRetryTracks: () -> Unit = {},
    pullsUsage: String = "",
    onClearPulls: () -> Unit = {},
    initialPage: Int = 0,
    artwork: Paintable? = rememberWatchArtwork(playing?.cover),
    mode: WatchMode = WatchMode.WATCH,
    onModeChange: (WatchMode) -> Unit = {},
    updateAuto: Boolean = true,
    onUpdateAutoChange: (Boolean) -> Unit = {},
    updateChecking: Boolean = false,
    updateMessage: String? = null,
    updateIsError: Boolean = false,
    updateLastChecked: String = "",
    onCheckUpdates: () -> Unit = {},
) {
    // The player first, then — in Watch mode — the library, and the mode switch last in both
    // modes, so the switch is the same swipe away whichever side is showing.
    val pager = rememberPagerState(initialPage = initialPage) { if (mode == WatchMode.WATCH) 3 else 2 }
    AppScaffold {
        HorizontalPagerScaffold(pagerState = pager) {
            HorizontalPager(state = pager) { page ->
                when {
                    page == 0 -> WatchPlayer(playing, error, artwork, onPrevious, onToggle, onNext, volume, active = pager.currentPage == 0)
                    mode == WatchMode.WATCH && page == 1 -> {
                        val opened = openList
                        if (opened != null) WatchPlaylist(opened, openTracks, onCloseList, onPlayList, onPlayTrack, onRetryTracks, onShuffleList, error)
                        else WatchLibrary(lists, onOpen, onRetry, extensionsNote, onSyncExtensions)
                    }
                    else -> WatchModePage(mode, onModeChange, updateAuto, onUpdateAutoChange, updateChecking, updateMessage, updateIsError, updateLastChecked, onCheckUpdates, pullsUsage, onClearPulls)
                }
            }
        }
    }
}

/** Where the music plays: the phone (this watch mirrors and drives it) or the watch itself. */
@Composable
internal fun WatchModePage(
    mode: WatchMode,
    onModeChange: (WatchMode) -> Unit,
    updateAuto: Boolean = true,
    onUpdateAutoChange: (Boolean) -> Unit = {},
    updateChecking: Boolean = false,
    updateMessage: String? = null,
    updateIsError: Boolean = false,
    updateLastChecked: String = "",
    onCheckUpdates: () -> Unit = {},
    pullsUsage: String = "",
    onClearPulls: () -> Unit = {},
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item {
                ListHeader(Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
                    Text(stringResource(R.string.wear_mode))
                }
            }
            item {
                Button(
                    onClick = { onModeChange(WatchMode.PHONE) },
                    colors = if (mode == WatchMode.PHONE) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                    secondaryLabel = { Text(stringResource(R.string.wear_mode_phone_about), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.wear_mode_phone), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            item {
                Button(
                    onClick = { onModeChange(WatchMode.WATCH) },
                    colors = if (mode == WatchMode.WATCH) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                    secondaryLabel = { Text(stringResource(R.string.wear_mode_watch_about), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.wear_mode_watch), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            // The watch app's own updates (Scadify v16): the automatic check's switch and
            // the manual check, under the mode switch — the last page is the settings page.
            item {
                ListHeader(Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
                    Text(stringResource(R.string.wear_updates))
                }
            }
            item {
                Button(
                    onClick = { onUpdateAutoChange(!updateAuto) },
                    colors = if (updateAuto) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                    secondaryLabel = { Text(stringResource(if (updateAuto) R.string.wear_update_auto_on else R.string.wear_update_auto_off), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.wear_update_auto), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            item {
                Button(
                    onClick = onCheckUpdates,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    secondaryLabel = { Text(if (updateChecking) stringResource(R.string.wear_loading) else updateLastChecked, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.wear_update_check), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            val message = updateMessage
            if (message != null) item {
                Text(
                    message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                    color = if (updateIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // The songs pulled from the phone (Scadify v21): how much of the watch they
            // take — capped at 250 MB, oldest-played evicted first — and their Clear.
            item {
                ListHeader(Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
                    Text(stringResource(R.string.wear_pulls_header))
                }
            }
            item {
                Button(
                    onClick = onClearPulls,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    secondaryLabel = { Text(pullsUsage, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.wear_pulls_clear), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/**
 * "New version" for the watch app, full screen (a watch's dialog): what the version is,
 * its notes, then Install — the download and the system installer's confirmation follow
 * from there — or Later, which dismisses this release until a manual check offers it
 * again. While [WearUpdater] works, its state is the status line; a version Android
 * refused over the installed app can only be put on by hand, so it gets no Install.
 */
@Composable
internal fun WatchUpdatePrompt(
    release: WearUpdateChecker.Release,
    state: WearUpdater.State,
    onInstall: () -> Unit,
    onLater: () -> Unit,
) {
    val columnState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val busy = state is WearUpdater.State.Downloading || state == WearUpdater.State.Installing
    val byHand = (state as? WearUpdater.State.Failed)?.onlyByHand == true
    val status = when (val s = state) {
        is WearUpdater.State.Downloading ->
            if (s.progress >= 0f) stringResource(R.string.wear_update_downloading, (s.progress * 100).toInt())
            else stringResource(R.string.wear_update_downloading_unknown)
        WearUpdater.State.Installing -> stringResource(R.string.wear_update_installing)
        WearUpdater.State.NeedsPermission -> stringResource(R.string.wear_update_needs_permission)
        is WearUpdater.State.Failed -> if (s.onlyByHand) s.message else stringResource(R.string.wear_failed, s.message)
        // The space sentence stands on its own: shown exactly as written.
        is WearUpdater.State.NoSpace -> s.message
        WearUpdater.State.Idle -> null
    }
    ScreenScaffold(scrollState = columnState) { padding ->
        TransformingLazyColumn(state = columnState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item {
                ListHeader(Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
                    Text(stringResource(R.string.wear_update_available, release.version))
                }
            }
            if (release.notes.isNotBlank()) item {
                Text(
                    release.notes, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (status != null) item {
                Text(
                    status, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                    color = if (state is WearUpdater.State.Failed || state is WearUpdater.State.NoSpace) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!byHand) item {
                Button(
                    onClick = onInstall,
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
                ) {
                    Text(stringResource(when (state) {
                        WearUpdater.State.NeedsPermission -> R.string.wear_update_allow
                        is WearUpdater.State.Failed -> R.string.wear_retry
                        is WearUpdater.State.NoSpace -> R.string.wear_retry
                        else -> R.string.wear_update_install
                    }), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!busy) item {
                Button(
                    onClick = onLater,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.wear_update_later), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
internal fun WatchPlayer(
    playing: WatchPlaying?,
    error: String?,
    artwork: Paintable?,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    volume: WatchVolumeUi,
    active: Boolean = true,
) {
    // The crown turns the volume on the player page, the level shown at the edge meanwhile.
    val focus = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) runCatching { focus.requestFocus() } }
    ScreenScaffold(scrollIndicator = { VolumeLevelIndicator(volumeUiState = volume.state, displayIndicatorEvents = volume.shown) }) {
        PlayerScreen(
            modifier = Modifier
                .rotaryScrollable(volumeRotaryBehavior(volume.state, volume.set), focus)
                .focusRequester(focus).focusable(),
            mediaDisplay = {
                if (playing == null) {
                    MessageMediaDisplay(message = stringResource(R.string.wear_nothing_playing))
                } else {
                    MarqueeTextMediaDisplay(title = playing.name, artist = playing.artists.takeIf { it.isNotBlank() })
                }
            },
            controlButtons = {
                AnimatedMediaControlButtons(
                    onPlayButtonClick = onToggle,
                    onPauseButtonClick = onToggle,
                    playPauseButtonEnabled = playing != null,
                    playing = playing?.playing == true,
                    onSeekToPreviousButtonClick = onPrevious,
                    seekToPreviousButtonEnabled = playing != null,
                    onSeekToNextButtonClick = onNext,
                    seekToNextButtonEnabled = playing != null,
                    trackPositionUiModel = playing.position(),
                )
            },
            buttons = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top, modifier = Modifier.fillMaxSize()) {
                    // A failure takes the volume button's room (the crown still sets the volume).
                    if (error != null) {
                        Text(
                            stringResource(R.string.wear_failed, error), maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth(0.66f),
                        )
                    } else {
                        playing?.source?.let {
                            Text(
                                it, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth(0.62f),
                            )
                        }
                        VolumeButton(onVolumeClick = volume.open, volumeUiState = volume.state())
                    }
                }
            },
            background = {
                RadialBackground(color = MaterialTheme.colorScheme.primaryContainer)
                WatchArtwork(artwork)
            },
        )
    }
}

/** The side the sleeve is asked at: the player's background never shows it larger than the watch's screen. */
private const val WATCH_ARTWORK_PX = 400

/**
 * The song's sleeve as a Coil request capped at the watch's screen size (Scadify v18):
 * a bare URL asks Coil for the picture at its own size — a sleeve of 1,000 px or more,
 * decoded whole on the watch — while the background shows it at under 400 px across,
 * dimmed. Display-side only: the same address, asked smaller.
 */
internal fun watchArtwork(context: Context, cover: String): Paintable = CoilPaintable(
    ImageRequest.Builder(context)
        .data(cover)
        .size(WATCH_ARTWORK_PX, WATCH_ARTWORK_PX)
        .build()
)

@Composable
internal fun rememberWatchArtwork(cover: String?): Paintable? {
    val context = LocalContext.current
    return remember(cover, context) { cover?.let { watchArtwork(context, it) } }
}

/**
 * The song's sleeve behind the player, dimmed and fading to black at the edge so the text and
 * buttons stay readable; nothing (the radial background) until it is loaded.
 */
@Composable
private fun WatchArtwork(artwork: Paintable?) {
    Crossfade(targetState = artwork, label = "artwork") { current ->
        val painter = current?.rememberPainter() ?: return@Crossfade
        if (painter.intrinsicSize == Size.Unspecified) return@Crossfade
        val background = MaterialTheme.colorScheme.background
        Image(
            painter = painter, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.55f,
            modifier = Modifier.fillMaxSize().drawWithCache {
                val scrim = Brush.radialGradient(0f to background.copy(alpha = 0.35f), 0.7f to background.copy(alpha = 0.55f), 1f to background)
                onDrawWithContent { drawRect(background); drawContent(); drawRect(scrim) }
            },
        )
    }
}

/** The song's place for the ring round play and pause: predicted from its last known position while it plays. */
private fun WatchPlaying?.position(): TrackPositionUiModel = when {
    this == null -> TrackPositionUiModel.Hidden
    durationMs <= 0L -> if (buffering) TrackPositionUiModel.Loading(shouldAnimate = true) else TrackPositionUiModel.Hidden
    else -> TrackPositionUiModel.Predictive(
        MediaPositionPredictor(atElapsedMs, durationMs, positionMs.coerceIn(0L, durationMs), if (playing) 1f else 0f),
        shouldAnimate = true, isLoading = buffering,
    )
}

@Composable
internal fun WatchLibrary(
    lists: Result<List<WatchList>>?,
    onOpen: (WatchList) -> Unit,
    onRetry: () -> Unit,
    extensionsNote: String,
    onSyncExtensions: () -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item {
                ListHeader(Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
                    Text(stringResource(R.string.wear_library))
                }
            }
            when {
                lists == null -> item {
                    Text(stringResource(R.string.wear_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                lists.isFailure -> {
                    item {
                        Text(
                            stringResource(R.string.wear_no_phone), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        )
                    }
                    item {
                        Button(
                            onClick = onRetry, colors = ButtonDefaults.filledTonalButtonColors(),
                            icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
                        ) { Text(stringResource(R.string.wear_retry)) }
                    }
                }
                else -> items(lists.getOrThrow(), key = { it.uri }) { list ->
                    Button(
                        onClick = { onOpen(list) }, colors = ButtonDefaults.filledTonalButtonColors(),
                        icon = { Icon(WatchIcons.QueueMusic, contentDescription = null) },
                        secondaryLabel = list.subtitle.takeIf { it.isNotBlank() }?.let { s -> { Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
                    ) { Text(list.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
            // The phone's extensions, copied to run here (an audio source plays lists with no phone).
            item {
                Button(
                    onClick = onSyncExtensions, colors = ButtonDefaults.filledVariantButtonColors(),
                    icon = { Icon(WatchIcons.Extension, contentDescription = null) },
                    secondaryLabel = { Text(extensionsNote, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.wear_extensions_sync), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/**
 * One list, opened from the library (v19: a tap on a list used to start it playing at
 * once; now the tap opens it, and playing is the Play button's job — the whole list in
 * its order — or a song's own tap, which plays the list from that song). The Shuffle
 * button (v21) plays the list in a fresh random order instead. Songs that are files of
 * the phone are marked as such; in Watch mode the watch pulls and plays them itself
 * (v21), in Phone mode the phone plays them there.
 */
@Composable
internal fun WatchPlaylist(
    list: WatchList,
    tracks: Result<List<WatchTrack>>?,
    onBack: () -> Unit,
    onPlayList: (WatchList) -> Unit,
    onPlayTrack: (Int) -> Unit,
    onRetry: () -> Unit,
    onShuffleList: (WatchList) -> Unit = {},
    error: String? = null,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item {
                ListHeader(Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
                    Text(list.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            item {
                Button(
                    onClick = onBack, colors = ButtonDefaults.filledVariantButtonColors(),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.wear_back), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            item {
                Button(
                    onClick = { onPlayList(list) }, colors = ButtonDefaults.buttonColors(),
                    secondaryLabel = list.subtitle.takeIf { it.isNotBlank() }?.let { s -> { Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.wear_play), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            item {
                Button(
                    onClick = { onShuffleList(list) }, colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.wear_shuffle), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            // A play that failed (a pull the phone never answered, a file gone) says so
            // here, on the page the tap happened on (v33): the same words also show on
            // the player page, which the tap never brings up.
            if (error != null) item {
                Text(
                    stringResource(R.string.wear_failed, error), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth(),
                )
            }
            when {
                tracks == null -> item {
                    Text(stringResource(R.string.wear_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                tracks.isFailure -> {
                    item {
                        Text(
                            stringResource(R.string.wear_no_phone), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        )
                    }
                    item {
                        Button(
                            onClick = onRetry, colors = ButtonDefaults.filledTonalButtonColors(),
                            icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
                        ) { Text(stringResource(R.string.wear_retry)) }
                    }
                }
                else -> {
                    val songs = tracks.getOrThrow()
                    items(songs.size) { i ->
                        val track = songs[i]
                        Button(
                            onClick = { onPlayTrack(i) }, colors = ButtonDefaults.filledTonalButtonColors(),
                            secondaryLabel = {
                                Text(
                                    if (track.phoneOnly) stringResource(R.string.wear_on_phone)
                                    else track.artists.joinToString(", "),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
                        ) { Text(track.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

