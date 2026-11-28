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
 * small sign beside the map that covers nothing – a ring only, filled by the map areas of the whole action counted
 * ([fraction], VISION 2026-11-27: facts, no percent; null = Android's calm ring), no names, no words; [count] („3 von 7
 * Kartenbereichen“) is for TalkBack. The pill looks small; the tap target is ≥ 48 dp.
 * Tap → the details ([BesideDetails]).
 */
@Composable
fun BesideSign(fraction: Float?, count: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (!WaitMotion.othersMove()) return // a wait window is up: it is the one thing that moves
    val cd = str(Res.string.maps_bg_chip_cd, count ?: "")
    Box(modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = str(Res.string.maps_bg_chip_click), onClick = onClick)
        .semantics { contentDescription = cd }, contentAlignment = Alignment.Center) {
        Surface(shape = OnPathShapes.Pill, color = OnPathColors.TagContainer, contentColor = OnPathColors.TagInk,
            modifier = Modifier.warmShadow(OnPathShadow.LOW, OnPathShapes.Pill)) {
            Row(Modifier.padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                // the ring a little taller than the text (grows with the phone's font size)
                val ring = with(LocalDensity.current) { MaterialTheme.typography.labelMedium.fontSize.toDp() } + 6.dp
                // counted areas fill the ring; nothing counted yet (only routing data, or not known): Android's calm ring
                if (fraction != null) CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(ring), color = OnPathColors.TagInk,
                    trackColor = OnPathColors.TagInk.copy(alpha = 0.18f), strokeWidth = 2.dp, gapSize = 0.dp)
                else CircularProgressIndicator(Modifier.size(ring), color = OnPathColors.TagInk, strokeWidth = 2.dp)
            }
        }
    }
}

/**
 * The details (a bottom sheet's content, docs/design/ui-style.md "the details of a state are a bottom sheet"): title, the
 * ONE bar by the areas counted with „x von y Kartenbereichen“ under it, what loads – one line each, no number of its own –, [notes]
 * (newer versions, "your maps stay"), Cancel all (red) · Close.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BesideDetails(title: String, fraction: Float?, count: String?, rows: List<BesideRow>, notes: List<String>, onCancelAll: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = OnPathColors.Ink, modifier = Modifier.padding(end = 8.dp))
        // the bar by the areas counted, and the count as words – „3 von 7 Kartenbereichen“ (facts, no percent – VISION)
        Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clip(CircleShape))
            else LinearProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().clip(CircleShape))
            count?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = OnPathColors.Ink) }
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

/** The counted map areas of a loading action as the sign and its details show them (facts – VISION 2026-11-27). */
object BesideCount {
    /** done / all, or null when none is counted yet (the ring then turns, calm). */
    fun fraction(done: Int, all: Int): Float? = if (all <= 0) null else (done.coerceIn(0, all).toFloat() / all)

    /** „3 von 7 Kartenbereichen“; null when none is counted. */
    fun line(done: Int, all: Int): String? = if (all <= 0) null else Txt.s(Res.string.maps_bg_areas_count, done.coerceIn(0, all), all)
}
