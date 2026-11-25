package com.music.spotui.ui.immersive.lyrics

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.data.entity.Lyrics
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.PlayPauseIcon
import com.music.spotui.ui.components.rememberFrameClock
import com.music.spotui.ui.look.LYRIC_EASING
import com.music.spotui.ui.look.SweptLine
import com.music.spotui.ui.look.rememberArtworkColors
import com.music.spotui.ui.look.rememberReduceMotion
import com.music.spotui.ui.look.toSwept
import kotlinx.coroutines.delay

/**
 * LyricGlow: the lyrics as a glow, after the Obsidian Music view Tyson sent. A black screen,
 * three lines only — the one being sung large in the middle, its neighbours small and dim —
 * and the sung line fills with a two-colour gradient that sweeps across it in time with the
 * singing (word by word where the source times the words, evenly across the line where it
 * only times the lines). The colours come from the cover's palette, as the reference's do.
 * Along the bottom edge a row of bars dances in the same colours under the song's name in
 * small caps; the bars are a drawing driven by the playback state, not a measurement of the
 * audio (the player's session is not one this view can honestly tap — decks swap under
 * AutoMix and crossfade, and the app holds no recording permission). A tap brings the
 * transport up; it fades again after a few seconds, as in the reference.
 */
@Composable
fun LyricGlowView(
    lyrics: Lyrics,
    trackKey: String,
    title: String,
    artist: String,
    fallbackAccent: Color,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val playing = Graph.currentSongState.playingState.value
    val wordSynced = remember(lyrics) { lyrics.lines.any { !it.words.isNullOrEmpty() } }
    val head = rememberLyricsPlayhead(trackKey, playing && lyrics.synced, if (wordSynced) LYRICS_WORD_FPS else LYRICS_LINE_FPS)
    val clock = head.clock
    val swept = remember(lyrics) {
        lyrics.lines.mapIndexed { i, l -> l.toSwept(lyrics.lines.getOrNull(i + 1)?.timeMs) }
    }
    val active by remember(lyrics) { derivedStateOf { glowActiveIndex(lyrics.lines, clock.longValue) } }
    val durationMs = SongPlayer.getDuration().coerceAtLeast(0L)

    // The glow's colours: the cover's palette, with the lyrics' own highlight (Spotify's
    // per-song colour, when the source gave one) among the candidates.
    val cover = Graph.currentSongState.coverUri.value
    val mesh = rememberArtworkColors(cover.takeIf { it.isNotBlank() })
    val raw = remember(mesh, lyrics.colors) {
        glowGradient(mesh.colors + listOfNotNull(lyrics.colors?.highlight?.let { Color(it) }), fallbackAccent)
    }
    val colorA by animateColorAsState(raw.first, tween(600), label = "glowA")
    val colorB by animateColorAsState(raw.second, tween(600), label = "glowB")

    // Chrome: shown on entry and on a tap, gone again a few seconds later.
    var chrome by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    LaunchedEffect(touched) {
        chrome = true
        delay(3_500)
        chrome = false
    }

    val frame = rememberFrameClock(playing, fps = 30)
    val energy by animateFloatAsState(if (playing) 1f else 0.12f, tween(500), label = "glowEnergy")

    Box(
        modifier
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { touched++ }
            .padding(contentPadding),
    ) {
        // A whisper of the glow colour behind the lines; the ground stays black.
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(colorA.copy(alpha = 0.13f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.42f),
                        radius = size.height * 0.55f,
                    ),
                )
            },
        )
        Column(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = active,
                transitionSpec = {
                    (fadeIn(tween(320)) + slideInVertically(tween(320, easing = LYRIC_EASING)) { it / 5 }) togetherWith
                        (fadeOut(tween(220)) + slideOutVertically(tween(220, easing = LYRIC_EASING)) { -it / 5 })
                },
                label = "glowWindow",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { index ->
                GlowWindow(lyrics, swept, index, clock, durationMs, colorA, colorB)
            }
            Text(
                text = "♪ ${title.uppercase()} — ${artist.uppercase()}",
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, textAlign = TextAlign.Center),
                color = colorA.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
            )
            // The transport's space is kept while it is faded, so the lines never shift.
            val chromeAlpha by animateFloatAsState(if (chrome) 1f else 0f, tween(if (chrome) 250 else 450), label = "glowChrome")
            Box(Modifier.fillMaxWidth().height(92.dp)) {
                if (chromeAlpha > 0.01f) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = chromeAlpha }) {
                        GlowTransport(clock, durationMs) { touched++ }
                    }
                }
            }
            Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                val heights = glowBarHeights(frame.value / 1_000_000, GLOW_BAR_COUNT, energy)
                val barW = 3.dp.toPx()
                val gap = 2.5.dp.toPx()
                var x = (size.width - (GLOW_BAR_COUNT * barW + (GLOW_BAR_COUNT - 1) * gap)) / 2f
                val brush = Brush.verticalGradient(
                    listOf(colorB.copy(alpha = 0.25f), colorA.copy(alpha = 0.9f)),
                    startY = size.height * 0.15f,
                    endY = size.height,
                )
                heights.forEach { h ->
                    val bh = (h * size.height).coerceAtLeast(2.dp.toPx())
                    drawRoundRect(brush, Offset(x, size.height - bh), Size(barW, bh), CornerRadius(barW / 2, barW / 2))
                    x += barW + gap
                }
            }
        }
    }
}

private const val GLOW_BAR_COUNT = 56

/** The three lines: the previous and next small and dim around the sung one, centred. */
@Composable
private fun GlowWindow(
    lyrics: Lyrics,
    swept: List<SweptLine>,
    active: Int,
    clock: MutableLongState,
    durationMs: Long,
    colorA: Color,
    colorB: Color,
) {
    val shown = if (active < 0) 0 else active
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlowSideLine(swept.getOrNull(shown - 1)?.text)
        Spacer(Modifier.height(30.dp))
        val current = swept.getOrNull(shown)
        if (current != null) {
            if (current.text.isBlank()) {
                Text(
                    "♪",
                    style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                    color = Color.White.copy(alpha = 0.5f),
                )
            } else {
                GlowLine(current, lyrics.lines.getOrNull(shown + 1)?.timeMs, durationMs, clock, colorA, colorB, Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(30.dp))
        GlowSideLine(swept.getOrNull(shown + 1)?.text)
    }
}

/** A neighbouring line: small, dim, centred. An instrumental gap reads as a note. */
@Composable
private fun GlowSideLine(text: String?) {
    Text(
        text = text?.ifBlank { "♪" } ?: "",
        style = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
        color = Color.White.copy(alpha = 0.38f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

private val GLOW_LINE_STYLE = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
private const val GLOW_DIM_ALPHA = 0.42f
private const val GLOW_BLOOM_RADIUS = 18f
private val GLOW_FEATHER: Dp = 26.dp

/**
 * The sung line: a dim copy of the whole line, the gradient copy clipped to the sweep's
 * edge over it, and — on screens that can blur — a blurred copy of the gradient beneath,
 * the glow the mode is named for. The sweep is read from the lyrics clock in the draw
 * phase, so a frame costs no recomposition (the same trick as the scrolling panel).
 */
@Composable
private fun GlowLine(
    line: SweptLine,
    nextStartMs: Long?,
    durationMs: Long,
    clock: MutableLongState,
    colorA: Color,
    colorB: Color,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val maxWidthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val layout = remember(line.text, maxWidthPx) {
            measurer.measure(line.text, GLOW_LINE_STYLE, constraints = Constraints(maxWidth = maxWidthPx))
        }
        val height = with(LocalDensity.current) { layout.size.height.toDp() }
        val bloom = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !rememberReduceMotion()
        Box(Modifier.fillMaxWidth().height(height)) {
            Spacer(Modifier.fillMaxSize().drawWithContent {
                drawText(layout, color = Color.White.copy(alpha = GLOW_DIM_ALPHA))
            })
            if (bloom) {
                Spacer(
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            renderEffect = BlurEffect(GLOW_BLOOM_RADIUS, GLOW_BLOOM_RADIUS, TileMode.Decal)
                            alpha = 0.55f
                        }
                        .drawWithContent { drawGlowText(layout, line, nextStartMs, durationMs, clock, colorA, colorB, feather = false) },
                )
            }
            Spacer(
                Modifier.fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent { drawGlowText(layout, line, nextStartMs, durationMs, clock, colorA, colorB, feather = true) },
            )
        }
    }
}

/**
 * The gradient copy of [layout], clipped row by row to the sweep's edge at the clock's
 * position. With [feather], the edge on the boundary row fades over a short run instead of
 * cutting (needs the caller's offscreen layer: the fade erases with DstIn).
 */
private fun DrawScope.drawGlowText(
    layout: TextLayoutResult,
    line: SweptLine,
    nextStartMs: Long?,
    durationMs: Long,
    clock: MutableLongState,
    colorA: Color,
    colorB: Color,
    feather: Boolean,
) {
    val revealed = glowRevealedChars(line, nextStartMs, durationMs, clock.longValue)
    if (revealed <= 0f || line.text.isEmpty()) return
    val brush = Brush.horizontalGradient(listOf(colorA, colorB), startX = 0f, endX = size.width)
    if (revealed >= line.text.length) {
        drawText(layout, brush = brush)
        return
    }
    val rtl = layout.getParagraphDirection(0) == ResolvedTextDirection.Rtl
    for (row in 0 until layout.lineCount) {
        val start = layout.getLineStart(row)
        if (revealed <= start) break
        val top = layout.getLineTop(row)
        val bottom = layout.getLineBottom(row)
        if (revealed >= layout.getLineEnd(row, visibleEnd = false)) {
            clipRect(left = 0f, top = top, right = size.width, bottom = bottom) { drawText(layout, brush = brush) }
            continue
        }
        val x = layout.glowBoundaryX(revealed, line.sweepSpans, row)
        val featherPx = if (feather) GLOW_FEATHER.toPx() else 0f
        if (rtl) {
            clipRect(left = (x - featherPx).coerceAtLeast(0f), top = top, right = size.width, bottom = bottom) { drawText(layout, brush = brush) }
        } else {
            clipRect(left = 0f, top = top, right = (x + featherPx).coerceAtMost(size.width), bottom = bottom) { drawText(layout, brush = brush) }
        }
        if (featherPx > 0f) {
            clipRect(top = top, bottom = bottom) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.White,
                        1f to Color.Transparent,
                        startX = x,
                        endX = if (rtl) x - featherPx else x + featherPx,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
        break
    }
}

/** The sweep's edge on [row], in pixels: eased across the word being sung, letter by letter otherwise. */
private fun TextLayoutResult.glowBoundaryX(chars: Float, spans: List<IntRange>, row: Int): Float {
    val lineStart = getLineStart(row)
    val lineEndVisible = getLineEnd(row, visibleEnd = true)
    val lineEndAll = getLineEnd(row, visibleEnd = false)
    val span = spans.firstOrNull { chars >= it.first && chars < it.last + 1 }
    if (span != null && span.first >= lineStart && span.last + 1 <= lineEndVisible) {
        val from = glowXOn(span.first, row)
        val to = glowXOn(span.last + 1, row)
        return from + (to - from) * (chars - span.first) / (span.last + 1 - span.first)
    }
    val index = chars.toInt().coerceIn(lineStart, lineEndAll)
    val here = glowXOn(index, row)
    val spills = index + 1 > lineEndAll || index + 1 > layoutInput.text.length - 1 || getLineForOffset(index + 1) != row
    val next = if (spills) {
        if (getParagraphDirection(lineStart) == ResolvedTextDirection.Rtl) getLineLeft(row) else getLineRight(row)
    } else {
        glowXOn(index + 1, row)
    }
    return here + (next - here) * (chars - index)
}

/** The x of character boundary [offset] on [row]; the row's own edges at its ends. */
private fun TextLayoutResult.glowXOn(offset: Int, row: Int): Float {
    val start = getLineStart(row)
    val endVisible = getLineEnd(row, visibleEnd = true)
    val rtl = getParagraphDirection(start.coerceIn(0, (layoutInput.text.length - 1).coerceAtLeast(0))) == ResolvedTextDirection.Rtl
    return when {
        offset <= start -> if (rtl) getLineRight(row) else getLineLeft(row)
        offset >= endVisible -> if (rtl) getLineLeft(row) else getLineRight(row)
        else -> getHorizontalPosition(offset, usePrimaryDirection = true)
    }
}

/** The minimal transport: a thin seek bar over previous / play-pause / next, wired to the player. */
@Composable
private fun GlowTransport(clock: MutableLongState, durationMs: Long, onTouch: () -> Unit) {
    val playing = Graph.currentSongState.playingState.value
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        GlowSeekBar(clock, durationMs, onTouch)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(52.dp).clickable { onTouch(); SongPlayer.previous() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.lbl_previous), tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Box(
                Modifier.size(60.dp).clickable { onTouch(); if (SongPlayer.isPlaying()) SongPlayer.pause() else SongPlayer.play() },
                contentAlignment = Alignment.Center,
            ) {
                PlayPauseIcon(
                    playing = playing,
                    contentDescription = stringResource(if (playing) R.string.lbl_pause else R.string.lbl_play),
                    tint = Color.White,
                    modifier = Modifier.size(38.dp),
                )
            }
            Box(
                Modifier.size(52.dp).clickable { onTouch(); SongPlayer.next() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.SkipNext, stringResource(R.string.lbl_next), tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
    }
}

/** A thin seek bar read off the lyrics clock: a tap seeks, a drag scrubs and seeks on release. */
@Composable
private fun GlowSeekBar(clock: MutableLongState, durationMs: Long, onTouch: () -> Unit) {
    var widthPx by remember { mutableStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val dragState = rememberDraggableState { delta -> dragValue = (dragValue + delta / widthPx).coerceIn(0f, 1f) }
    Canvas(
        Modifier.fillMaxWidth().height(22.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(durationMs) {
                detectTapGestures { off ->
                    onTouch()
                    if (durationMs > 0) SongPlayer.seekTo((durationMs * (off.x / widthPx).coerceIn(0f, 1f)).toLong())
                }
            }
            .draggable(
                dragState,
                Orientation.Horizontal,
                onDragStarted = {
                    dragging = true
                    dragValue = if (durationMs > 0) (clock.longValue.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                    onTouch()
                },
                onDragStopped = {
                    dragging = false
                    if (durationMs > 0) SongPlayer.seekTo((durationMs * dragValue).toLong())
                },
            ),
    ) {
        val value = if (dragging) dragValue else if (durationMs > 0) (clock.longValue.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val h = 3.dp.toPx()
        val y = size.height / 2f
        drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2, h / 2))
        drawRoundRect(Color.White, Offset(0f, y - h / 2), Size(size.width * value, h), CornerRadius(h / 2, h / 2))
        drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(size.width * value, y))
    }
}
