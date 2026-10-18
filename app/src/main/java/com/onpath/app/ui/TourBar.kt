package com.onpath.app.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.data.Format

/** Plan mode while a tour records: "Tour running · 6.4 km", End tour, Continue navigation. */
@Composable
fun TourRunningBar(distanceM: Double, paused: Boolean, onContinue: () -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(if (paused) OnPathColors.Grey else OnPathColors.Rec, CircleShape))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(if (paused) "Tour paused" else "Tour running", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(Format.distance(distanceM), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            TextButton(onClick = onEnd, modifier = Modifier.heightIn(min = 48.dp)) { Text("End tour", color = OnPathColors.Bad) }
            Button(onClick = onContinue, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Filled.Navigation, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("Continue navigation", fontSize = 14.sp, maxLines = 1)
            }
        }
    }
}

/** "End tour" in Plan mode: one question, then stop & save (summary follows). */
@Composable
fun EndTourDialog(onEnd: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Flag, null) },
        title = { Text("End this tour?") },
        text = { Text("Recording stops and the tour is saved to My tours.") },
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
        text = { Text("End & save stops recording and saves the tour to My tours.") },
        confirmButton = { TextButton(onClick = onEnd) { Text("End & save") } },
        dismissButton = { TextButton(onClick = onKeepGoing) { Text("Keep going") } },
    )
}
