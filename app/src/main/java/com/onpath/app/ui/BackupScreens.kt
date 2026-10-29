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
import com.onpath.app.backup.RecycleBin
import com.onpath.app.data.Format
import com.onpath.app.data.Tour
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.onpath.strings.*

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
                IconButton(onClick = onBack, Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, str(Res.string.common_back)) }
                Text(str(Res.string.common_recycle_bin), style = MaterialTheme.typography.titleLarge)
            }
            Text(str(Res.string.backup_bin_keep, RecycleBin.DAYS),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            if (deleted.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(str(Res.string.backup_bin_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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
            title = { Text(str(Res.string.backup_bin_delete_title, t.name)) },
            text = { DialogText(str(Res.string.backup_bin_delete_text)) },
            confirmButton = { TextButton(onClick = { confirm = null; onDeleteForever(t) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(str(Res.string.common_delete), color = OnPathColors.Bad) } },
            dismissButton = { TextButton(onClick = { confirm = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_cancel)) } },
        )
    }
}

@Composable
private fun BinCard(t: Tour, nowMs: Long, onRestore: () -> Unit, onDelete: () -> Unit) {
    val pattern = str(Res.string.backup_date)
    val fmt = remember(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, end = 4.dp, bottom = 4.dp)) {
            Text(t.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${t.kind.label} · ${fmt.format(Date(t.createdMs))} · ${Format.distance(t.distanceM)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            t.deletedMs?.let { d ->
                val left = RecycleBin.daysLeft(d, nowMs)
                Text(str(Res.string.backup_bin_deleted, fmt.format(Date(d)),
                    if (left == 0) str(Res.string.backup_bin_removed_next) else plural(Res.plurals.backup_bin_days_left, left, left)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.backup_bin_delete_now), color = OnPathColors.Bad) }
                TextButton(onClick = onRestore, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Restore, null); Spacer(Modifier.width(4.dp)); Text(str(Res.string.common_restore))
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
    /** "Back up all now" runs: a spinner on its button. */
    val backingUp: Boolean = false,
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
    Section(SettingsPage.BACKUP.title) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(str(Res.string.backup_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(str(Res.string.backup_folder, b.folder), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            val last = if (b.lastMs > 0) SimpleDateFormat(str(Res.string.backup_date_time), Locale.getDefault()).format(Date(b.lastMs))
                else str(Res.string.backup_not_yet)
            Text(plural(Res.plurals.backup_last, b.count, last, b.count), style = MaterialTheme.typography.bodySmall)
            b.lastError?.let { Text(str(Res.string.backup_last_failed, it), style = MaterialTheme.typography.bodySmall, color = OnPathColors.Bad) }
            if (b.needsStorageAccess) OutlinedButton(onClick = cb.onAllowStorage, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(str(Res.string.backup_allow_storage))
            }
            OutlinedButton(onClick = { if (!b.backingUp) cb.onBackupNow() }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (b.backingUp) BusyContent(Busy.BACKUP.text) else Text(str(Res.string.backup_now))
            }
            OutlinedButton(onClick = cb.onChooseFolder, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(str(Res.string.backup_choose_folder))
            }
            if (b.custom) TextButton(onClick = cb.onUseDocuments, Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.backup_use_documents)) }
            if (b.restoring != null) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(b.restoring, style = MaterialTheme.typography.bodySmall)
            } else OutlinedButton(onClick = cb.onRestore, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(str(Res.string.backup_restore_button)) }
        }
    }
}

// ------------------------------------------------------------------ restore offer + progress

/**
 * First start: the location request, "Restore your tours?" and the loading modal must not cover each other – the
 * restore offer waits for the location answer and for the loading modal to close (and never during a tour).
 */
object RestoreOfferRule {
    fun show(offer: Boolean, recording: Boolean, locationAsking: Boolean, loadModalOpen: Boolean): Boolean =
        offer && !recording && !locationAsking && !loadModalOpen
}

/** First start with no tours: offer the restore once (a reinstalled app may read the old folder only after the picker). */
@Composable
fun RestoreOfferDialog(onRestore: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Filled.Restore, null) },
        title = { Text(str(Res.string.backup_offer_title)) },
        text = { DialogText(str(Res.string.backup_offer_text)) },
        confirmButton = { TextButton(onClick = onRestore, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_restore)) } },
        dismissButton = { TextButton(onClick = onNotNow, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_not_now)) } },
    )
}

/** Restore running: stays until done (then the result message, until OK). */
@Composable
fun RestoreProgressDialog(line: String) {
    AlertDialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
        title = { Text(str(Res.string.backup_restoring_title)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp); Spacer(Modifier.width(12.dp)); Text(line)
            }
        },
        confirmButton = {},
    )
}
