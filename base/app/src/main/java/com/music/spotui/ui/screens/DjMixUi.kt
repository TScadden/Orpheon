/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.music.spotui.djmix.DjFailure
import com.music.spotui.djmix.DjMixSession
import com.music.spotui.djmix.DjMixState
import com.music.spotui.djmix.DjPool
import com.music.spotui.ui.navigation.localPlaylistRoute
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone

private val Green: Color get() = com.music.spotui.ui.theme.AppAccent
private val SheetColor: Color get() = tone(0xFF282828)

/**
 * "DJ Mix": pick a category, and the app builds a new local playlist of its most
 * popular songs this week, sequenced by the AutoMix planner's transition scores.
 * The build is shown as it goes — chart, analysis (N of M), sequencing — and can
 * be cancelled at any point; nothing is created until the mix is finished. The
 * counts on the done step are the build's own (see DjSummary): sequenced songs,
 * and any whose audio could not be analysed, placed at the end in chart order.
 */
@Composable
fun DjMixDialog(navController: NavController, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session = remember { DjMixSession(scope) }
    LaunchedEffect(session) { session.start() }
    val state by session.state.collectAsState()
    fun close() {
        session.cancel()
        onDismiss()
    }
    val building = state is DjMixState.FetchingPool || state is DjMixState.Analyzing || state is DjMixState.Sequencing
    AlertDialog(
        onDismissRequest = { close() },
        containerColor = SheetColor,
        title = { Text(stringResource(R.string.djmix_title), color = AppText) },
        text = {
            when (val s = state) {
                DjMixState.LoadingCategories -> DjMixProgress(stringResource(R.string.djmix_loading_categories), null)
                is DjMixState.Categories -> Column(
                    Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text(stringResource(R.string.djmix_pick), color = AppMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    for (category in s.categories) {
                        Column(
                            Modifier.fillMaxWidth().clickable { session.pick(category) }.padding(vertical = 11.dp),
                        ) {
                            Text(category.label, color = AppText, fontSize = 16.sp)
                            if (category.cleanOnly) {
                                Text(stringResource(R.string.djmix_clean_detail), color = AppMuted, fontSize = 12.sp)
                            }
                        }
                    }
                }
                is DjMixState.FetchingPool -> DjMixProgress(stringResource(R.string.djmix_fetching, s.category.label), null)
                is DjMixState.Analyzing -> DjMixProgress(
                    stringResource(R.string.djmix_analyzing, s.done, s.total),
                    if (s.total > 0) s.done.toFloat() / s.total else null,
                )
                is DjMixState.Sequencing -> DjMixProgress(stringResource(R.string.djmix_sequencing), null)
                is DjMixState.Done -> Column {
                    Text(s.name, color = AppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (s.summary.appended == 0) stringResource(R.string.djmix_done_all, s.summary.sequenced)
                        else stringResource(R.string.djmix_done_appended, s.summary.sequenced, s.summary.appended),
                        color = AppMuted, fontSize = 13.sp,
                    )
                    if (s.summary.appended > 0) when (s.refusal) {
                        com.music.spotui.automix.AutoMix.Reason.MobileData -> {
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.djmix_done_refused_mobile_data), color = AppMuted, fontSize = 13.sp)
                        }
                        com.music.spotui.automix.AutoMix.Reason.NotAnalyzed -> {
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.djmix_done_refused_failed), color = AppMuted, fontSize = 13.sp)
                        }
                        else -> Unit
                    }
                }
                is DjMixState.Failed -> Text(djFailureText(s.failure), color = AppText, fontSize = 14.sp)
            }
        },
        confirmButton = {
            (state as? DjMixState.Done)?.let { done ->
                TextButton(onClick = {
                    val id = done.playlistId
                    close()
                    navController.navigate(localPlaylistRoute(id))
                }) { Text(stringResource(R.string.djmix_open), color = Green) }
            }
        },
        dismissButton = {
            TextButton(onClick = { close() }) {
                Text(stringResource(if (building) R.string.lbl_cancel else R.string.lbl_close), color = AppText)
            }
        },
    )
}

/** One build step: a spinner (or the analysis bar) and what it is doing. */
@Composable
private fun DjMixProgress(label: String, fraction: Float?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (fraction == null) {
                CircularProgressIndicator(color = Green, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(14.dp))
            }
            Text(label, color = AppMuted, fontSize = 14.sp)
        }
        if (fraction != null) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                color = Green,
                trackColor = tone(0xFF3A3A3A),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A stopped build, said plainly (the Clean Rap count names its rule, not a guess). */
@Composable
private fun djFailureText(failure: DjFailure): String = when (failure) {
    DjFailure.CategoriesUnavailable -> stringResource(R.string.djmix_categories_failed)
    DjFailure.ChartUnavailable -> stringResource(R.string.djmix_chart_failed)
    is DjFailure.TooFew -> if (failure.category.cleanOnly) {
        stringResource(R.string.djmix_too_few_clean, failure.found, DjPool.MIN_SONGS)
    } else {
        stringResource(R.string.djmix_too_few, failure.found, failure.category.label, DjPool.MIN_SONGS)
    }
}
