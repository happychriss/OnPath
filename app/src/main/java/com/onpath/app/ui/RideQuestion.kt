package com.onpath.app.ui

import com.onpath.strings.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.onpath.app.data.Format
import com.onpath.app.rides.RideChoice
import com.onpath.app.rides.RideView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The question when a tour with a ride (bus / car / lift) is saved (owner 2026-11-21, sketch "zwei Knöpfe"): an amber
 * box saying where the ride was, the tour's map with the ride dashed, and two big buttons – **without the ride**
 * ([RideView.Summary.withoutRide]: a ride at the end is cut off, one in the middle does not count) and **with the ride**
 * (as recorded). Cancel and Delete tour below. Nothing recorded is lost either way ([com.onpath.app.rides.RideApply]).
 * "Edit rides" of a saved tour keeps [RideSummaryDialog]. [onDelete] null = no Delete (not used by the two callers today).
 */
@Composable
fun RideQuestionDialog(
    summary: RideView.Summary, onSave: (RideChoice) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val shownAt = remember { android.os.SystemClock.uptimeMillis() }
    val without = guardedTap("Save without the ride", shownAt) { onSave(summary.withoutRide) }
    val with = guardedTap("Save with the ride", shownAt) { onSave(summary.withRide) }
    val cancel = guardedTap("Cancel (ride question)", shownAt, onDismiss)
    val delete = onDelete?.let { guardedTap("Tour löschen (ride question)", shownAt, it) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RideWarnBox(summary, zone)
                RideMap(summary.questionLines, Modifier.fillMaxWidth().height(120.dp))
                Button(onClick = without.onClick, interactionSource = without.source,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OnPathColors.ActionGo)) {
                    Text(str(Res.string.nav_ride_q_without), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }
                OutlinedButton(onClick = with.onClick, interactionSource = with.source,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Text(str(Res.string.nav_ride_q_with), color = OnPathColors.ActionGo, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                }
                // side by side; at a large font the second goes under the first instead of breaking inside a word
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.Center) {
                    TextButton(onClick = cancel.onClick, interactionSource = cancel.source, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(str(Res.string.common_cancel), color = OnPathColors.ActionGo, softWrap = false)
                    }
                    if (delete != null) TextButton(onClick = delete.onClick, interactionSource = delete.source, modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(str(Res.string.nav_delete_tour), softWrap = false)
                    }
                }
            }
        }
    }
}

/** The amber box: title by situation, under it the ride (kind · times · km) or, with several, their count + total km. */
@Composable
private fun RideWarnBox(summary: RideView.Summary, zone: ZoneId) {
    val fmt = remember(zone) { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }
    fun t(ms: Long?) = ms?.let { fmt.format(Instant.ofEpochMilli(it)) } ?: "–"
    val rideRows = summary.rows.filter { it.kind == RideView.RowKind.VEHICLE || it.kind == RideView.RowKind.LIFT }
    val line = if (rideRows.size == 1) rideRows[0].let { "${str(it.kind.res)} · ${t(it.startMs)}–${t(it.endMs)} · ${Format.distance(it.distanceM)}" }
    else str(Res.string.nav_ride_q_several, rideRows.size.coerceAtLeast(summary.rides.size), Format.distance(rideRows.sumOf { it.distanceM }))
    Row(Modifier.fillMaxWidth().background(OnPathColors.WarnContainer, OnPathShapes.Small)
        .border(1.dp, OnPathColors.Warn, OnPathShapes.Small).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, null, tint = OnPathColors.Warn, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(str(if (summary.rideAtEnd) Res.string.nav_ride_q_end else Res.string.nav_ride_q_along),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = OnPathColors.OnWarnContainer)
            Text(line, style = MaterialTheme.typography.bodyMedium, color = OnPathColors.OnWarnContainer)
        }
    }
}
