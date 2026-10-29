package com.onpath.app.ui

import com.onpath.strings.*

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
import androidx.compose.material.icons.outlined.Warning
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
    onPause: (Boolean) -> Unit = {}, onContinue: () -> Unit = {}, activity: com.onpath.app.routing.Activity? = null,
) {
    Surface(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 2.dp) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            TourControls(distanceM, elapsedS, paused, onPause, onContinue, onEnd, activity)
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
    title: String = str(Res.string.nav_end_tour_title), cancelLabel: String = str(Res.string.common_cancel),
) {
    var askDelete by remember { mutableStateOf(false) }
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            icon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(str(Res.string.nav_delete_track_title)) },
            text = { DialogText(RECYCLE_BIN_NOTE) },
            confirmButton = { TextButton(onClick = { askDelete = false; onDelete() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(str(Res.string.common_delete)) } },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text(str(Res.string.common_cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Flag, null) },
        title = { Text(title) },
        // no body: the buttons say it (review 2026-10-24 B11)
        confirmButton = { TextButton(onClick = onSave) { Text(str(Res.string.common_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(cancelLabel) }
                TextButton(onClick = { askDelete = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(str(Res.string.common_delete)) }
            }
        },
    )
}

/** Body of every "Delete …?" dialog that moves to the recycle bin (review 2026-10-24 B11). */
internal val RECYCLE_BIN_NOTE: String get() = Txt.s(Res.string.nav_recycle_bin_note, com.onpath.app.backup.RecycleBin.DAYS)

/** Shown once in Navigate when the destination is reached: the same three choices. */
@Composable
fun ArrivalDialog(onSave: () -> Unit, onDelete: () -> Unit, onKeepGoing: () -> Unit) =
    EndTourDialog(onSave, onDelete, onKeepGoing, title = str(Res.string.nav_arrived_title), cancelLabel = str(Res.string.nav_keep_going))


/** "Continue · Gravel bike" (owner rule: the way back to Navigate names the activity; UX review 2026-10-23, problem 14). */
internal fun continueLabel(activity: com.onpath.app.routing.Activity?): String =
    if (activity == null) Txt.s(Res.string.nav_continue) else Txt.s(Res.string.nav_continue_as, activity.navName)

/**
 * Status line + the three tour buttons: Continue (filled, full width, names the activity; also resumes), then Pause /
 * Resume (tonal) and End (outlined red, asks first) side by side. Used at the top of the plan sheet during a tour and in
 * the stand-alone tour card.
 */
@Composable
fun TourControls(
    distanceM: Double, elapsedS: Long, paused: Boolean,
    onPause: (Boolean) -> Unit, onContinue: () -> Unit, onEnd: () -> Unit,
    activity: com.onpath.app.routing.Activity? = null,
) {
    val status = str(if (paused) Res.string.nav_tour_paused else Res.string.nav_tour_running, Format.distance(distanceM), Format.clock(elapsedS))
    Row(Modifier.semantics { contentDescription = status }, verticalAlignment = Alignment.CenterVertically) {
        RecDot(paused)
        Spacer(Modifier.width(10.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { if (paused) onPause(false); onContinue() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(Icons.Filled.Navigation, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
        Text(continueLabel(activity), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { onPause(!paused) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(str(if (paused) Res.string.common_resume else Res.string.nav_pause), maxLines = 1)
        }
        OutlinedButton(onClick = onEnd, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)) {
            Icon(Icons.Filled.Stop, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp))
            Text(str(Res.string.nav_end_tour), color = MaterialTheme.colorScheme.error, maxLines = 1)
        }
    }
}


/**
 * Owner decision 9 (2026-10-22, RouteBack): the goal of a start → goal plan was deleted during a tour. Stays until
 * answered (no outside tap / back dismiss – never self-dismissing). Verb buttons (review 2026-10-24 B11).
 */
@Composable
fun RouteBackDialog(onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Filled.Flag, null) },
        title = { Text(str(Res.string.nav_route_back_title)) },
        text = { DialogText(str(Res.string.nav_route_back_text)) },
        confirmButton = { TextButton(onClick = onYes) { Text(str(Res.string.nav_route_back)) } },
        dismissButton = { TextButton(onClick = onNo) { Text(str(Res.string.nav_just_record)) } },
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    )
}

/** "About 29 days on foot. Calculating and loading its maps takes long." (review 2026-10-24 B11). */
internal fun longRouteText(seconds: Double, activity: com.onpath.app.routing.Activity): String {
    val how = if (activity == com.onpath.app.routing.Activity.HIKE) Res.string.nav_long_route_foot else Res.string.nav_long_route_bike
    return Txt.s(how, com.onpath.app.routing.LongRoute.describe(seconds)).replaceFirstChar { it.uppercase() }
}

/**
 * A plan far longer than a day (owner 2026-10-22: a point in another country by mistake). Asked before anything is
 * routed or loaded. Cancel takes back the change that made it so long (AppViewModel.cancelRun). Stays until a button
 * is tapped (UX review 2026-10-23, problem 9): an outside tap or Back must not undo anything.
 */
@Composable
fun LongRouteDialog(seconds: Double, activity: com.onpath.app.routing.Activity, onPlan: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Outlined.Warning, null, tint = OnPathColors.Warn) },
        title = { Text(str(Res.string.nav_long_route_title)) },
        text = { DialogText(longRouteText(seconds, activity)) },
        confirmButton = { TextButton(onClick = onPlan) { Text(str(Res.string.nav_plan_anyway)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(str(Res.string.common_cancel)) } },
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    )
}
