package com.onpath.app.ui.progress

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.ui.OnPathColors
import com.onpath.app.ui.OnPathShadow
import com.onpath.app.ui.OnPathShapes

import com.onpath.app.ui.warmShadow
import com.onpath.strings.*

/**
 * Form B ("ich kann weitermachen", owner 2026-11-19 „einfach nur Prozent, muss aber passen für die gesamte Aktion“): one
 * small sign beside the map that covers nothing – a ring and ONE percent over the whole action (every load of it
 * together, [com.onpath.app.domain.ActionPercent]), no names, no words. The pill looks small; the tap target is ≥ 48 dp.
 * Tap → the details ([BesideDetails]).
 */
@Composable
fun BesideSign(percent: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cd = str(Res.string.maps_bg_chip_cd, "$percent %")
    Box(modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = str(Res.string.maps_bg_chip_click), onClick = onClick)
        .semantics { contentDescription = cd }, contentAlignment = Alignment.Center) {
        Surface(shape = OnPathShapes.Pill, color = OnPathColors.TagContainer, contentColor = OnPathColors.TagInk,
            modifier = Modifier.warmShadow(OnPathShadow.LOW, OnPathShapes.Pill)) {
            Row(Modifier.padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                // the ring a little taller than the text (grows with the phone's font size)
                val ring = with(LocalDensity.current) { MaterialTheme.typography.labelMedium.fontSize.toDp() } + 6.dp
                CircularProgressIndicator(progress = { percent / 100f }, modifier = Modifier.size(ring), color = OnPathColors.TagInk,
                    trackColor = OnPathColors.TagInk.copy(alpha = 0.18f), strokeWidth = 2.dp, gapSize = 0.dp)
                Spacer(Modifier.width(7.dp))
                Text("$percent %", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * The details (a bottom sheet's content, docs/design/ui-style.md "the details of a state are a bottom sheet"): title, the
 * ONE bar with the sign's percent over the whole action, what loads – one line each, no number of its own –, [notes]
 * (newer versions, "your maps stay"), Cancel all (red) · Close.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BesideDetails(title: String, percent: Int, rows: List<BesideRow>, notes: List<String>, onCancelAll: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = OnPathColors.Ink, modifier = Modifier.padding(end = 8.dp))
        Row(Modifier.padding(end = 8.dp).semantics(mergeDescendants = true) { contentDescription = "$percent %" },
            verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(progress = { percent / 100f }, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).clip(CircleShape))
            Text("$percent %", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = OnPathColors.Ink, softWrap = false, modifier = Modifier.padding(start = 12.dp))
        }
        if (rows.isNotEmpty()) Column(Modifier.padding(end = 8.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { r ->
                // name and state on one line; at a large font the state wraps under the name (never cut)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r.name, style = MaterialTheme.typography.bodyLarge, color = OnPathColors.Ink, modifier = Modifier.padding(end = 12.dp))
                    Text(r.state, style = MaterialTheme.typography.bodyMedium, color = OnPathColors.InkMuted, softWrap = false)
                }
            }
        }
        notes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = OnPathColors.InkMuted, modifier = Modifier.padding(end = 8.dp)) }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancelAll, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(str(Res.string.common_cancel_all), color = OnPathColors.Bad, fontWeight = FontWeight.SemiBold, softWrap = false)
            }
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_close), softWrap = false) }
        }
    }
}
