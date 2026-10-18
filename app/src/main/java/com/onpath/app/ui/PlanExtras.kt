package com.onpath.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.data.Format
import kotlinx.coroutines.delay

/** "Added as stop 2 of 4 · Make it the destination" – sits just above the plan panel. */
@Composable
fun PlanNoticeBar(notice: PlanNotice, onAction: (NoticeAction) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(notice.id) { delay(if (notice.action != null) 6000 else 2500); onDismiss() }
    Snackbar(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        action = notice.action?.let { a ->
            { TextButton(onClick = { onAction(a) }) { Text(notice.actionLabel ?: "Change", color = Color(0xFFB9F6CA)) } }
        },
    ) { Text(notice.text) }
}

/** Clear with two or more points: one question, the plan can still be brought back with Undo. */
@Composable
fun ConfirmClearDialog(pointCount: Int, onClear: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.DeleteSweep, null) },
        title = { Text("Clear this route?") },
        text = { Text("All $pointCount points and the route line are removed. Undo brings them back.") },
        confirmButton = { TextButton(onClick = onClear) { Text("Clear") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Long-press on a marker without moving, or tap on a number chip. */
@Composable
fun PointMenuDialog(
    index: Int, count: Int,
    onRemove: () -> Unit, onMakeStart: () -> Unit, onMakeDestination: () -> Unit, onDismiss: () -> Unit,
) {
    val name = when { index == 0 -> "Start"; index == count - 1 && count > 1 -> "Destination"; else -> "Stop ${index + 1} of $count" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column {
                MenuRow(Icons.Filled.Delete, "Remove point") { onRemove(); onDismiss() }
                if (index > 0) MenuRow(Icons.Filled.TripOrigin, "Make it the start") { onMakeStart(); onDismiss() }
                if (index < count - 1) MenuRow(Icons.Filled.Flag, "Make it the destination") { onMakeDestination(); onDismiss() }
                Text("Tip: long-press a marker on the map and drag it to move it.", fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Icon(icon, null); Spacer(Modifier.width(12.dp)); Text(label, Modifier.weight(1f), fontSize = 16.sp)
    }
}

/** Top strip while following a route: what is left (F-NAV-01). */
@Composable
fun RemainingStrip(distanceM: Double, ascentM: Double, timeS: Double, offM: Double?, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 4.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Navigation, null, tint = OnPathColors.Route, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("${Format.distance(distanceM)} to go", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("  ·  ↑ ${Format.meters(ascentM)}  ·  ${Format.duration(timeS)}", fontSize = 14.sp)
            if (offM != null && offM > 150) Text("  ·  ${Format.distance(offM)} off", fontSize = 13.sp, color = OnPathColors.Bad)
        }
    }
}
