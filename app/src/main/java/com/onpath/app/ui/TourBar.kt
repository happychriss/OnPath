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

/** "End tour" in Plan mode: one question, then stop & save (summary follows). */
@Composable
fun EndTourDialog(onEnd: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Flag, null) },
        title = { Text("End this tour?") },
        text = { Text("Recording stops and the tour is saved to My routes.") },
        confirmButton = { TextButton(onClick = onEnd) { Text("End & save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shown once in Navigate when the destination is reached. */
@Composable
fun ArrivalDialog(onEnd: () -> Unit, onKeepGoing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepGoing,
        icon = { Icon(Icons.Filled.Flag, null, tint = OnPathColors.Good) },
        title = { Text("You have arrived – end tour?") },
        text = { Text("End & save stops recording and saves the tour to My routes.") },
        confirmButton = { TextButton(onClick = onEnd) { Text("End & save") } },
        dismissButton = { TextButton(onClick = onKeepGoing) { Text("Keep going") } },
    )
}


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
