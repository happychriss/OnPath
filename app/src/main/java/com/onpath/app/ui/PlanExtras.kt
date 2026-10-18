package com.onpath.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

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
