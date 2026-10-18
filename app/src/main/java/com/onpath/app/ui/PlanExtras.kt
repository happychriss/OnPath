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
import androidx.compose.material3.MaterialTheme
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
    LaunchedEffect(notice.id) { delay(if (notice.action != null) 4000 else 2500); onDismiss() }
    Snackbar(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        action = notice.action?.let { a ->
            { TextButton(onClick = { onAction(a) }) { Text(notice.actionLabel ?: "Change", color = MaterialTheme.colorScheme.inversePrimary) } }
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
