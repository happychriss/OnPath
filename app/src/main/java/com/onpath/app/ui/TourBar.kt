package com.onpath.app.ui

import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.data.Format

/**
 * Plan mode while a tour records: read-only status "● Tour running · 6.4 km · 1:23:45" plus the rare
 * "End tour" text button. The primary "Continue navigation" is the plan sheet's filled button.
 */
/**
 * The tour card in Plan mode while a tour records (owner + UX review): the three answers to "what now with my
 * tour?" together in one row – Continue navigation (filled, most used), Pause / Resume (tonal), End tour (outlined
 * red, asks first). Continue also resumes a paused tour (back to navigating = walking again).
 */
@Composable
fun TourRunningBar(
    distanceM: Double, elapsedS: Long, paused: Boolean, onEnd: () -> Unit, modifier: Modifier = Modifier,
    onPause: (Boolean) -> Unit = {}, onContinue: () -> Unit = {},
) {
    Surface(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 2.dp) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            TourControls(distanceM, elapsedS, paused, onPause, onContinue, onEnd)
        }
    }
}

/** Recording state dot: orange while recording, grey while paused. */
@Composable
fun RecDot(paused: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).background(if (paused) OnPathColors.Grey else OnPathColors.Rec, CircleShape))
}

/**
 * "End tour" (owner 2026-10-22): always Cancel · Delete · Save. Save stops and keeps the tour; Delete (red) asks once
 * more, then the tour goes to the recycle bin (30 days) – for a short walk nobody wants to keep.
 * [cancelLabel]: "Cancel" from the End button, "Keep going" on arrival.
 */
@Composable
fun EndTourDialog(
    onSave: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit,
    title: String = "End this tour?", cancelLabel: String = "Cancel",
) {
    var askDelete by remember { mutableStateOf(false) }
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            icon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete this tour?") },
            text = { Text("Recording stops and the tour is not kept. You can still get it back from the recycle bin " +
                "for 30 days (My routes → ⋮ → Recycle bin).") },
            confirmButton = { TextButton(onClick = { askDelete = false; onDelete() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Flag, null) },
        title = { Text(title) },
        text = { Text("Save keeps the tour in My routes. Delete throws it away.") },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(cancelLabel) }
                TextButton(onClick = { askDelete = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            }
        },
    )
}

/** Shown once in Navigate when the destination is reached: the same three choices. */
@Composable
fun ArrivalDialog(onSave: () -> Unit, onDelete: () -> Unit, onKeepGoing: () -> Unit) =
    EndTourDialog(onSave, onDelete, onKeepGoing, title = "You have arrived – end tour?", cancelLabel = "Keep going")


/**
 * Status line + the three tour buttons in one row: Continue (filled; also resumes), Pause / Resume (tonal), End
 * (outlined red, asks first). Used at the top of the plan sheet during a tour and in the stand-alone tour card.
 */
@Composable
fun TourControls(
    distanceM: Double, elapsedS: Long, paused: Boolean,
    onPause: (Boolean) -> Unit, onContinue: () -> Unit, onEnd: () -> Unit,
) {
    val status = (if (paused) "Tour paused" else "Tour running") + " · ${Format.distance(distanceM)} · ${Format.clock(elapsedS)}"
    Row(Modifier.semantics { contentDescription = status }, verticalAlignment = Alignment.CenterVertically) {
        RecDot(paused)
        Spacer(Modifier.width(10.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { if (paused) onPause(false); onContinue() }, modifier = Modifier.weight(1.3f).heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(Icons.Filled.Navigation, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text("Continue", maxLines = 1)
        }
        FilledTonalButton(onClick = { onPause(!paused) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(if (paused) "Resume" else "Pause", maxLines = 1)
        }
        OutlinedButton(onClick = onEnd, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)) {
            Icon(Icons.Filled.Stop, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp))
            Text("End", color = MaterialTheme.colorScheme.error, maxLines = 1)
        }
    }
}


/**
 * A plan far longer than a day (owner 2026-10-22: a point in another country by mistake). Asked before anything is
 * routed or loaded. Cancel takes the last change back.
 */
/**
 * Owner decision 9 (2026-10-22, RouteBack): the goal of a start → goal plan was deleted during a tour. Stays until
 * answered (no outside tap / back dismiss – never self-dismissing).
 */
@Composable
fun RouteBackDialog(onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Filled.Flag, null) },
        title = { Text("Route back to the start?") },
        text = { Text("The goal is deleted. Yes: the route leads from here back to the start. No: the tour keeps " +
            "recording without a route. Undo brings the goal back either way.") },
        confirmButton = { TextButton(onClick = onYes) { Text("Yes") } },
        dismissButton = { TextButton(onClick = onNo) { Text("No") } },
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    )
}

@Composable
fun LongRouteDialog(seconds: Double, activity: com.onpath.app.routing.Activity, onPlan: () -> Unit, onCancel: () -> Unit) {
    val how = if (activity == com.onpath.app.routing.Activity.HIKE) "on foot" else "by bike"
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Filled.Flag, null) },
        title = { Text("This route is very long") },
        text = { Text("It would take ${com.onpath.app.routing.LongRoute.describe(seconds)} $how. Calculating it and " +
            "loading the maps along it takes a long time. Plan it anyway?") },
        confirmButton = { TextButton(onClick = onPlan) { Text("Plan anyway") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
