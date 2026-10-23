package com.onpath.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.backup.RecycleBin
import com.onpath.app.data.Format
import com.onpath.app.data.Tour
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ recycle bin (My routes → ⋮ → Recycle bin)

/** Deleted tours and plans: Restore, or Delete now (asks first). Entries older than 30 days go at the next app start. */
@Composable
fun RecycleBinScreen(
    deleted: List<Tour>,
    onBack: () -> Unit,
    onRestore: (Tour) -> Unit,
    onDeleteForever: (Tour) -> Unit,
    nowMs: Long = System.currentTimeMillis(),
    initialConfirm: Tour? = null,
) {
    var confirm by remember { mutableStateOf(initialConfirm) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Recycle bin", style = MaterialTheme.typography.titleLarge)
            }
            Text("Deleted tracks and routes stay here for ${RecycleBin.DAYS} days, then they are removed.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            if (deleted.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("The recycle bin is empty.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            } else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(deleted, key = { it.id }) { t -> BinCard(t, nowMs, { onRestore(t) }, { confirm = t }) }
            }
        }
    }
    confirm?.let { t ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text("Delete “${t.name}” for good?") },
            text = { Text("It is removed from this phone and from the backup folder. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirm = null; onDeleteForever(t) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Delete", color = OnPathColors.Bad) } },
            dismissButton = { TextButton(onClick = { confirm = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BinCard(t: Tour, nowMs: Long, onRestore: () -> Unit, onDelete: () -> Unit) {
    val fmt = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, end = 4.dp, bottom = 4.dp)) {
            Text(t.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${t.kind.label} · ${fmt.format(Date(t.createdMs))} · ${Format.distance(t.distanceM)}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            t.deletedMs?.let { d ->
                val left = RecycleBin.daysLeft(d, nowMs)
                Text("Deleted ${fmt.format(Date(d))} · " + when (left) { 0 -> "removed at the next start"; 1 -> "1 day left"; else -> "$left days left" },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete now", color = OnPathColors.Bad) }
                TextButton(onClick = onRestore, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Restore, null); Spacer(Modifier.width(4.dp)); Text("Restore")
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Settings → Backup

/** What Settings → Backup shows. */
data class BackupUi(
    val folder: String,
    val lastMs: Long,
    val count: Int,
    val lastError: String?,
    /** A folder the user picked (not Documents/OnPath). */
    val custom: Boolean,
    /** Restore running: its progress line. */
    val restoring: String? = null,
    /** Android 8–9 without storage access: the "Allow" button. */
    val needsStorageAccess: Boolean = false,
)

data class BackupCallbacks(
    val onBackupNow: () -> Unit = {},
    val onChooseFolder: () -> Unit = {},
    val onUseDocuments: () -> Unit = {},
    val onRestore: () -> Unit = {},
    val onAllowStorage: () -> Unit = {},
)

@Composable
fun BackupSection(b: BackupUi, cb: BackupCallbacks) {
    Section("Backup") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Every recorded track and saved route is also saved as a GPX file – it stays when OnPath is removed and " +
                "can be restored on a new phone.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Folder: ${b.folder}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            val last = if (b.lastMs > 0) SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(b.lastMs)) else "not yet"
            Text("Last backup: $last · ${b.count} ${if (b.count == 1) "file" else "files"}", fontSize = 13.sp)
            b.lastError?.let { Text("Last backup failed – $it", fontSize = 13.sp, color = OnPathColors.Bad) }
            if (b.needsStorageAccess) OutlinedButton(onClick = cb.onAllowStorage, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Allow storage access")
            }
            OutlinedButton(onClick = cb.onBackupNow, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Back up all now") }
            OutlinedButton(onClick = cb.onChooseFolder, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Choose another folder (e.g. Drive, Nextcloud)")
            }
            if (b.custom) TextButton(onClick = cb.onUseDocuments, Modifier.heightIn(min = 48.dp)) { Text("Use Documents/OnPath again") }
            if (b.restoring != null) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(b.restoring, fontSize = 13.sp)
            } else OutlinedButton(onClick = cb.onRestore, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Restore tracks and routes") }
        }
    }
}

// ------------------------------------------------------------------ restore offer + progress

/** First start with no tours: offer the restore once (a reinstalled app may read the old folder only after the picker). */
@Composable
fun RestoreOfferDialog(onRestore: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Filled.Restore, null) },
        title = { Text("Earlier tracks and routes?") },
        text = { Text("If you used OnPath before, your tracks and routes may be in Documents/OnPath. Restore them? " +
            "Android then shows the folder – tap “Use this folder” and “Allow”.\n\nLater: Settings → Backup → Restore.") },
        confirmButton = { TextButton(onClick = onRestore, modifier = Modifier.heightIn(min = 48.dp)) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onNotNow, modifier = Modifier.heightIn(min = 48.dp)) { Text("Not now") } },
    )
}

/** Restore running: stays until done (then the result message, until OK). */
@Composable
fun RestoreProgressDialog(line: String) {
    AlertDialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
        title = { Text("Restoring tracks and routes") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp); Spacer(Modifier.width(12.dp)); Text(line)
            }
        },
        confirmButton = {},
    )
}
