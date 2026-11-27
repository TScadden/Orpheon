/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppMuted

/**
 * The slim strip at the junction between two playlist songs: the way into the Mix editor for
 * that pair. A junction whose pair carries a saved plan says so — the accent label and dot —
 * so an edited transition is visible where it will play.
 */
@Composable
fun MixJunctionStrip(edited: Boolean, immersive: Boolean, onClick: () -> Unit) {
    val accent = if (immersive) Immersive.Accent else AppAccent
    val plain = if (immersive) Immersive.TextSecondary else AppMuted
    val color = if (edited) accent else plain
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .padding(start = if (immersive) Immersive.Gutter else 56.dp, end = 16.dp)
            .height(30.dp)
            .pressable(0.97f, onClick = onClick),
    ) {
        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Text(
            stringResource(if (edited) R.string.mix_junction_edited else R.string.mix_junction),
            color = color, fontSize = 12.sp,
            fontWeight = if (edited) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(start = 8.dp),
        )
        if (edited) Box(Modifier.padding(start = 6.dp).size(6.dp).clip(CircleShape).background(accent))
    }
}
