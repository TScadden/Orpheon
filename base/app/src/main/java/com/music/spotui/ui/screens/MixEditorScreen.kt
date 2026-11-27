/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.ui.screens

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.automix.MixPlan
import app.myspoti.automix.MixPlanner
import app.myspoti.automix.TrackAnalysis
import app.myspoti.engine.R
import com.music.spotui.automix.AutoMix
import com.music.spotui.data.local.LocalPlaylists
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.data.local.MixEdit
import com.music.spotui.data.local.MixEdits
import com.music.spotui.di.Graph
import com.music.spotui.di.LocalPlayer
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.immersive.Immersive
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/** The two tracks of one junction, resolved under the very identities they play under. */
internal object MixEditorTracks {
    fun resolve(playlistKey: String, idA: String, idB: String): Pair<LocalTrack, LocalTrack>? {
        val a = one(playlistKey, idA) ?: return null
        val b = one(playlistKey, idB) ?: return null
        return a to b
    }

    private fun one(playlistKey: String, identity: String): LocalTrack? = when {
        playlistKey.startsWith("local:") -> LocalPlaylists.playlists[playlistKey.removePrefix("local:")]
            ?.items?.firstOrNull { MixEdits.trackIdentity(Uri.parse(it.uri)) == identity }?.toTrack()
        playlistKey.startsWith("imported:") ->
            Graph.api.importedPlayableTrack(playlistKey.removePrefix("imported:"), identity)
        else -> null
    }
}

/**
 * The Mix editor: one junction of a playlist, placed by hand — the outgoing song's waveform
 * with the transition window over its tail and its fade curve, the incoming song's waveform
 * and entry, the window in bars, the style, the DJ EQ for this pair, and a preview that plays
 * only the transition through the real player. Saving writes a [MixEdit]; the player prefers
 * it for this pair in this playlist until "Reset to auto" deletes it.
 */
@Composable
fun MixEditorScreen(navController: NavController, playlistKey: String, idA: String, idB: String) {
    val pair = remember(playlistKey, idA, idB) { MixEditorTracks.resolve(playlistKey, idA, idB) }
    if (pair == null) {
        LaunchedEffect(Unit) { navController.navigateUp() }
        return
    }
    val (trackA, trackB) = pair
    val existing = remember(playlistKey, idA, idB) { MixEdits.get(playlistKey, idA, idB) }

    var settled by remember { mutableIntStateOf(0) }
    var polls by remember { mutableIntStateOf(0) }
    var gaveUp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { AutoMix.requestEditor(listOf(trackA.uri, trackB.uri)) { settled++ } }
    LaunchedEffect(Unit) { delay(120_000); gaveUp = true }

    // Reading polls re-reads the engine's answers as they land.
    val tick = polls
    val analysisA = AutoMix.analysis(trackA.uri)
    val analysisB = AutoMix.analysis(trackB.uri)
    val peaksA = AutoMix.peaks(trackA.uri)
    val peaksB = AutoMix.peaks(trackB.uri)
    val ready = analysisA != null && analysisB != null && peaksA != null && peaksB != null
    val failed = !ready && (settled >= 2 || gaveUp)
    LaunchedEffect(ready, failed) {
        if (!ready && !failed) while (true) { delay(300); polls++ }
    }

    Column(Modifier.fillMaxSize().background(Immersive.Page)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(50)).pressable(0.92f) { navController.navigateUp() },
            ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.lbl_back), tint = Immersive.Text, modifier = Modifier.size(22.dp)) }
            Text(stringResource(R.string.mix_editor_title), color = Immersive.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp))
        }
        when {
            failed -> MixEditorFailed(trackA, trackB, analysisA, analysisB)
            !ready -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = Immersive.Accent)
                Text(stringResource(R.string.mix_analyzing), color = Immersive.TextSecondary, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 16.dp))
            }
            else -> MixEditorBody(
                navController, playlistKey, idA, idB, trackA, trackB,
                analysisA!!, analysisB!!, peaksA!!, peaksB!!, existing,
            )
        }
    }
}

/** A song of the pair could not be analysed: said plainly, with the engine's own reason where it has one. */
@Composable
private fun MixEditorFailed(trackA: LocalTrack, trackB: LocalTrack, analysisA: TrackAnalysis?, analysisB: TrackAnalysis?) {
    val bad = if (analysisA == null) trackA else trackB
    val reason = AutoMix.missing(bad.uri)
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.mix_couldnt_title), color = Immersive.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            if (reason == AutoMix.Reason.MobileData) stringResource(R.string.mix_couldnt_mobile_data, bad.title)
            else stringResource(R.string.mix_couldnt_body, bad.title),
            color = Immersive.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun MixEditorBody(
    navController: NavController,
    playlistKey: String, idA: String, idB: String,
    trackA: LocalTrack, trackB: LocalTrack,
    ra: TrackAnalysis, rb: TrackAnalysis,
    peaksA: FloatArray, peaksB: FloatArray,
    existing: MixEdit?,
) {
    val settings = remember { AutoMix.settings() }
    val barSecA = ra.tail?.let { 4 * it.period } ?: if (ra.bpm > 0) 240.0 / ra.bpm else 2.0
    val autoPlan = remember(ra, rb) { MixPlanner.planBounded(ra, rb, AutoMix.settings()) }

    var style by remember { mutableStateOf(existing?.style ?: autoPlan?.style ?: MixPlan.Style.FADE) }
    var bars by remember {
        mutableIntStateOf(existing?.bars ?: when (autoPlan?.style) {
            MixPlan.Style.BLEND -> (autoPlan.beats / 4).coerceIn(4, 32)
            MixPlan.Style.FADE -> (autoPlan.length / barSecA).roundToInt().coerceIn(4, 32)
            else -> 8
        })
    }
    var offset by remember {
        mutableIntStateOf(existing?.endBarOffset ?: autoPlan?.let { ((ra.contentEnd - it.endA) / barSecA).roundToInt().coerceIn(0, 16) } ?: 0)
    }
    var eq by remember { mutableStateOf(existing?.eq ?: AutoMix.djEq) }

    val plan = remember(style, bars, offset, eq, ra, rb) {
        MixPlanner.planCustom(ra, rb, style, bars, offset, eq, ra.contentStart, settings)
    }
    // A style is offered when it can be honoured at a middling window; the chosen window's own
    // fit is the plan above (and the note under the controls when it does not fit).
    val feasible = remember(ra, rb) {
        MixPlan.Style.entries.associateWith { s ->
            MixPlanner.planCustom(ra, rb, s, 8, 0, eq, ra.contentStart, settings) != null
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Immersive.Gutter, vertical = 8.dp)) {
        SongHeader(trackA.title, trackA.artist, ra)
        Spacer(Modifier.height(8.dp))
        Waveform(peaksA, ra.duration, plan?.let { it.exitA to it.endA }, fadePlan = plan)
        Spacer(Modifier.height(14.dp))
        Waveform(peaksB, rb.duration, plan?.let { it.entryB to (it.entryB + it.length * it.speedB) }, fadePlan = null)
        Spacer(Modifier.height(8.dp))
        SongHeader(trackB.title, trackB.artist, rb)

        Spacer(Modifier.height(22.dp))
        // Style.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in MixPlan.Style.entries) {
                val label = stringResource(when (s) {
                    MixPlan.Style.BLEND -> R.string.mix_style_blend
                    MixPlan.Style.CUT -> R.string.mix_style_cut
                    MixPlan.Style.FADE -> R.string.mix_style_fade
                })
                val chosen = style == s
                val offered = feasible[s] == true
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (chosen) Immersive.Accent else Immersive.Raised)
                        .alpha(if (offered) 1f else 0.35f)
                        .pressable(0.95f, enabled = offered) { style = s }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text(label, color = if (chosen) Immersive.Page else Immersive.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        // The window's length in bars (a cut's length is its two beats).
        Stepper(
            label = stringResource(R.string.mix_bars, bars),
            enabled = style != MixPlan.Style.CUT,
            onMinus = { bars = (bars - 1).coerceAtLeast(4) },
            onPlus = { bars = (bars + 1).coerceAtMost(32) },
        )
        if (style == MixPlan.Style.CUT) Text(stringResource(R.string.mix_cut_note), color = Immersive.TextSecondary, fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
        // Where the window ends: the stepper steps how early it ends (0: at the song's end).
        Stepper(
            label = if (offset == 0) stringResource(R.string.mix_ends_at_end) else stringResource(R.string.mix_ends_early, offset),
            enabled = true,
            onMinus = { offset = (offset - 1).coerceAtLeast(0) },
            onPlus = { offset = (offset + 1).coerceAtMost(16) },
        )
        Spacer(Modifier.height(14.dp))
        // The DJ EQ for this pair.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.mix_eq_label), color = Immersive.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = eq, onCheckedChange = { eq = it })
        }
        if (plan == null) {
            Text(stringResource(R.string.mix_no_fit), color = Immersive.TextSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp))
        } else {
            Text(plan.note, color = Immersive.TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }

        Spacer(Modifier.height(24.dp))
        if (!AutoMix.on) {
            Text(stringResource(R.string.mix_automix_off), color = Immersive.TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Immersive.Raised)
                    .pressable(0.95f) { AutoMix.set(true); LocalPlayer.autoMixChanged(true) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) { Text(stringResource(R.string.mix_turn_on_automix), color = Immersive.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Immersive.Raised)
                    .alpha(if (plan != null && AutoMix.on) 1f else 0.4f)
                    .pressable(0.95f, enabled = plan != null && AutoMix.on) {
                        val p = plan ?: return@pressable
                        val startMs = (max(ra.contentStart, p.exitA - 2 * barSecA) * 1000).toLong()
                        LocalPlayer.previewTransition(trackA, trackB, p, startMs)
                    }
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            ) { Text(stringResource(R.string.mix_preview), color = Immersive.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Immersive.Accent)
                    .alpha(if (plan != null) 1f else 0.4f)
                    .pressable(0.95f, enabled = plan != null) {
                        MixEdits.set(playlistKey, idA, idB, MixEdit(style, bars, offset, eq))
                        navController.navigateUp()
                    }
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            ) { Text(stringResource(R.string.mix_save), color = Immersive.Page, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        }
        if (existing != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.mix_reset_auto), color = Immersive.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable(0.95f) {
                    MixEdits.clear(playlistKey, idA, idB)
                    navController.navigateUp()
                }.padding(6.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

/** A song's name, artist, and what the analysis says: BPM, Camelot key, duration. */
@Composable
private fun SongHeader(title: String, artist: String, a: TrackAnalysis) {
    val camelot = if (a.key >= 0) MixPlanner.camelot(a.key).let { (n, major) -> "$n${if (major) "B" else "A"}" } else "—"
    val bpm = if (a.steady || a.bpm == a.bpm.roundToInt().toDouble()) "${a.bpm.roundToInt()}" else String.format("%.1f", a.bpm)
    val duration = "${(a.duration / 60).toInt()}:${String.format("%02d", (a.duration % 60).toInt())}"
    Column {
        Text(title, color = Immersive.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (artist.isNotBlank()) Text(artist, color = Immersive.TextSecondary, fontSize = 13.sp, maxLines = 1)
        Text(stringResource(R.string.mix_header_stats, bpm, camelot, duration), color = Immersive.Accent, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
    }
}

/**
 * One song's waveform: its peaks, the transition [window] (seconds, of [durationSec])
 * highlighted, and — for the outgoing song — the plan's own fade curve over the window.
 */
@Composable
private fun Waveform(peaks: FloatArray, durationSec: Double, window: Pair<Double, Double>?, fadePlan: MixPlan?) {
    val base = Immersive.TextSecondary.copy(alpha = 0.55f)
    val accent = Immersive.Accent
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        if (durationSec <= 0 || peaks.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val mid = h / 2
        val winL = window?.let { (it.first / durationSec).toFloat() * w } ?: -1f
        val winR = window?.let { (it.second / durationSec).toFloat() * w } ?: -1f
        if (window != null) drawRect(accent.copy(alpha = 0.14f), topLeft = Offset(winL, 0f), size = Size(winR - winL, h))
        val barW = w / peaks.size
        for (i in peaks.indices) {
            val x = i * barW
            val inWin = window != null && x >= winL && x <= winR
            val amp = peaks[i] * (h / 2 - 2)
            drawLine(if (inWin) accent else base, Offset(x, mid - amp), Offset(x, mid + amp),
                strokeWidth = (barW * 0.7f).coerceAtLeast(1f))
        }
        if (fadePlan != null && window != null) {
            val path = Path()
            val steps = 32
            for (s in 0..steps) {
                val x01 = s / steps.toDouble()
                val gain = fadePlan.levels(x01).gainA
                val px = winL + (winR - winL) * (s / steps.toFloat())
                val py = (h * (1 - gain)).toFloat()
                if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, accent, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

/** A − label + row: the bars and placement steppers. Minus steps the value the label reads down. */
@Composable
private fun Stepper(label: String, enabled: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f)) {
        Text(label, color = Immersive.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        StepperButton(Icons.Rounded.Remove, enabled, onMinus)
        Spacer(Modifier.size(8.dp))
        StepperButton(Icons.Rounded.Add, enabled, onPlus)
    }
}

@Composable
private fun StepperButton(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(Immersive.Raised)
            .pressable(0.92f, enabled = enabled, onClick = onClick),
    ) { Icon(icon, contentDescription = null, tint = Immersive.Text, modifier = Modifier.size(18.dp)) }
}
