package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.data.DataFiles
import com.onpath.app.data.Format
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.routing.Activity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ missing data

@Composable
fun MissingDataScreen(
    checks: List<DataFiles.Check>,
    dataDir: String,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text("OnPath", style = MaterialTheme.typography.headlineMedium, color = OnPathColors.Green, fontWeight = FontWeight.Bold)
            Text("Offline map data is missing", style = MaterialTheme.typography.titleLarge)
            Text("OnPath works fully offline. Copy the map package to the phone once, then everything runs without network.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 1.dp) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    checks.forEach { c ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (c.present) Icons.Filled.CheckCircle else Icons.Filled.Cancel, null,
                                tint = if (c.present) OnPathColors.Good else OnPathColors.Bad, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                                Text(c.hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(if (c.present) "found" else "missing", fontSize = 12.sp,
                                color = if (c.present) OnPathColors.Good else OnPathColors.Bad)
                        }
                    }
                }
            }
            Text("Connect the phone with USB debugging on and run on your computer:", style = MaterialTheme.typography.bodyMedium)
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF1E2421)) {
                Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        SelectionContainer {
                            Text(DataFiles.ADB_COMMAND, color = Color(0xFFD7F5DF), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        }
                    }
                    IconButton(onClick = { onCopy(DataFiles.ADB_COMMAND) }, Modifier.size(48.dp)) {
                        Icon(Icons.Filled.ContentCopy, "Copy command", tint = Color.White)
                    }
                }
            }
            Text("Target folder on the phone:\n$dataDir", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                Text("Check again", fontSize = 16.sp)
            }
            OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                Text("Continue without map data")
            }
        }
    }
}

// ------------------------------------------------------------------ activity picker

@Composable
fun ActivityDialog(
    current: Activity, sac: Int, overlayOn: Boolean,
    onSelect: (Activity) -> Unit, onSac: (Int) -> Unit, onOverlay: (Boolean) -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Activity") },
        text = {
            Column {
                Activity.entries.forEach { a ->
                    val sel = a == current
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onSelect(a) }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(a.icon, null, tint = if (sel) OnPathColors.Green else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(14.dp))
                        Text(a.label, fontSize = 16.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
                if (current == Activity.HIKE) {
                    Spacer(Modifier.height(12.dp))
                    Text("Max. difficulty (SAC)", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..5).forEach { level ->
                            FilterChip(selected = level == sac, onClick = { onSac(level) }, label = { Text("T$level") },
                                modifier = Modifier.heightIn(min = 44.dp))
                        }
                    }
                    Text(sacHint(sac), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Show marked routes", Modifier.weight(1f))
                    Switch(checked = overlayOn, onCheckedChange = onOverlay)
                }
            }
        },
    )
}

private fun sacHint(sac: Int) = when (sac) {
    1 -> "T1 – hiking: well-built paths"
    2 -> "T2 – mountain hiking: sure-footedness"
    3 -> "T3 – demanding mountain hiking"
    4 -> "T4 – alpine hiking, some scrambling"
    else -> "T5 – demanding alpine hiking"
}

// ------------------------------------------------------------------ my tours

@Composable
fun ToursScreen(
    tours: List<Tour>,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onOpen: (Tour) -> Unit,
    onExport: (Tour) -> Unit,
    onDelete: (Tour) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<Tour?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("My tours", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onImport, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.UploadFile, null); Spacer(Modifier.width(6.dp)); Text("Import GPX")
                }
            }
            if (tours.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No tours yet.\nPlan a route, record a tour or import a GPX file.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(tours, key = { it.id }) { t ->
                        TourCard(t, onOpen = { onOpen(t) }, onExport = { onExport(t) }, onDelete = { confirmDelete = t })
                    }
                }
            }
        }
    }
    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete tour?") },
            text = { Text("“${t.name}” will be deleted from this phone.") },
            confirmButton = { TextButton(onClick = { onDelete(t); confirmDelete = null }) { Text("Delete", color = OnPathColors.Bad) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TourCard(t: Tour, onOpen: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    val (icon, color) = when (t.kind) {
        TourKind.PLANNED -> Icons.Filled.Route to OnPathColors.Route
        TourKind.RECORDED -> Icons.Filled.FiberManualRecord to OnPathColors.Rec
        TourKind.IMPORTED -> Icons.Filled.FileOpen to OnPathColors.Gpx
    }
    Surface(onClick = onOpen, shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${t.kind.label} · " + SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(t.createdMs)),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${Format.distance(t.distanceM)} · ↑ ${Format.meters(t.ascentM)} · ${Format.duration(t.durationS)}", fontSize = 13.sp)
            }
            IconButton(onClick = onExport, Modifier.size(48.dp)) { Icon(Icons.Filled.Share, "Export GPX") }
            IconButton(onClick = onDelete, Modifier.size(48.dp)) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
fun NameDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun UnfinishedDialog(t: Tour, onContinue: () -> Unit, onSave: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Unfinished tour") },
        text = { Text("A recording was interrupted (${Format.distance(t.distanceM)}, ${Format.duration(t.durationS)}). Nothing was lost.") },
        confirmButton = { TextButton(onClick = onContinue) { Text("Continue tour") } },
        dismissButton = { TextButton(onClick = onSave) { Text("Save tour") } },
    )
}

@Composable
fun FinishedDialog(t: Tour, onExport: () -> Unit, onDelete: () -> Unit, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Tour saved") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t.name, fontWeight = FontWeight.SemiBold)
                StatsRow(t.distanceM, t.ascentM, t.descentM, t.durationS)
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = OnPathColors.Bad) }
                TextButton(onClick = onExport) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(4.dp)); Text("GPX") }
            }
        },
    )
}

@Composable
fun ConfirmStopDialog(onStop: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Navigation, null) },
        title = { Text("Stop recording?") },
        text = { Text("The tour is saved to My tours.") },
        confirmButton = { TextButton(onClick = onStop) { Text("Stop & save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep recording") } },
    )
}
