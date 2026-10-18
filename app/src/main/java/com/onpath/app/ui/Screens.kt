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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    onImport: () -> Unit = {},
    onDownloadRouting: () -> Unit = {},
    busy: String? = null,
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
            DataActions(
                routingMissing = checks.any { it.name.startsWith("segments") && !it.present },
                busy = busy, onImport = onImport, onDownloadRouting = onDownloadRouting,
            )
            Text("Or, from a computer with USB debugging on:", style = MaterialTheme.typography.bodyMedium)
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

/**
 * Getting map data onto the phone without a computer: import the onpath-data zip (or single files /
 * style files) from Downloads, and download routing data directly from brouter.de.
 */
@Composable
fun DataActions(routingMissing: Boolean, busy: String?, onImport: () -> Unit, onDownloadRouting: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onImport, enabled = busy == null, modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)) {
            Text("Import map data (.zip) from this phone", fontSize = 16.sp)
        }
        if (routingMissing) {
            OutlinedButton(onClick = onDownloadRouting, enabled = busy == null,
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                Text("Download routing data (≈ 200 MB, Wi-Fi)")
            }
        }
        if (busy != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(busy, fontSize = 14.sp)
            }
        }
    }
}

// ------------------------------------------------------------------ activity picker

@Composable
fun ActivityDialog(
    current: Activity, sac: Int,
    onSelect: (Activity) -> Unit, onSac: (Int) -> Unit, onDismiss: () -> Unit,
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

// ------------------------------------------------------------------ manage routes

/** Manage routes (⋮ → Manage routes) or the same list for ⋮ → Load route ([PICK]: tap = load, no other actions). */
enum class RoutesMode { MANAGE, PICK }

/**
 * One list of planned routes, recorded tours and imported GPX files (newest first): type icon, name, date,
 * distance. MANAGE: Import GPX, per item ⋮ Rename / Export (share) / Delete (asks first), tap = open. PICK: tap = load.
 * While [navigating], items that would replace the plan cannot be opened (Load waits for the end of the tour).
 */
@Composable
fun RoutesScreen(
    tours: List<Tour>,
    mode: RoutesMode,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onOpen: (Tour) -> Unit,
    onExport: (Tour) -> Unit,
    onDelete: (Tour) -> Unit,
    navigating: Boolean = false,
    onRename: (Tour, String) -> Unit = { _, _ -> },
    initialConfirmDelete: Tour? = null,
    initialRename: Tour? = null,
) {
    var confirmDelete by remember { mutableStateOf(initialConfirmDelete) }
    var rename by remember { mutableStateOf(initialRename) }
    val pick = mode == RoutesMode.PICK
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(if (pick) "Load route" else "Manage routes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (!pick) TextButton(onClick = onImport, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.UploadFile, null); Spacer(Modifier.width(6.dp)); Text("Import GPX")
                }
            }
            if (pick) Text("Tap a route to load it.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            if (tours.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No routes yet.\nSave a planned route, record a tour or import a GPX file.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(tours, key = { it.id }) { t ->
                        val openable = RouteRules.canOpen(t.kind, t.waypoints.size, navigating)
                        TourCard(t, pick = pick, onOpen = if (openable) ({ onOpen(t) }) else null,
                            onRename = { rename = t }, onExport = { onExport(t) }, onDelete = { confirmDelete = t })
                    }
                }
            }
        }
    }
    confirmDelete?.let { t ->
        DeleteRouteDialog(t, onDelete = { confirmDelete = null; onDelete(t) }, onCancel = { confirmDelete = null })
    }
    rename?.let { t ->
        NameDialog("Rename ${t.kind.label.lowercase()}", t.name, confirmLabel = "Rename",
            onDone = { rename = null; onRename(t, it) }, onDismiss = { rename = null })
    }
}

/** Stays until answered: a tap outside does nothing, Back = Cancel. Deletes only on "Delete". */
@Composable
fun DeleteRouteDialog(t: Tour, onDelete: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Filled.Delete, null) },
        title = { Text("Delete “${t.name}”?") },
        text = { Text("This ${t.kind.label.lowercase()} is removed from this phone. This cannot be undone.") },
        confirmButton = { TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete", color = OnPathColors.Bad) } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

/** Loading would overwrite a plan that is not saved: stays until answered (tap outside / Back = cancel). */
@Composable
fun ReplacePlanDialog(onSaveFirst: () -> Unit, onReplace: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Filled.Route, null) },
        title = { Text("Replace current plan?") },
        text = { Text("Your current route is not saved. Save it first, or replace it with the route you picked.") },
        confirmButton = { TextButton(onClick = onReplace, modifier = Modifier.heightIn(min = 48.dp)) { Text("Replace") } },
        dismissButton = { TextButton(onClick = onSaveFirst, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save first") } },
    )
}

@Composable
private fun TourCard(
    t: Tour, pick: Boolean, onOpen: (() -> Unit)?, onRename: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit,
) {
    val (icon, color) = when (t.kind) {
        TourKind.PLANNED -> Icons.Filled.Route to OnPathColors.Route
        TourKind.RECORDED -> Icons.Filled.FiberManualRecord to OnPathColors.Rec
        TourKind.IMPORTED -> Icons.Filled.FileOpen to OnPathColors.Gpx
    }
    Surface(onClick = { onOpen?.invoke() }, enabled = onOpen != null, shape = RoundedCornerShape(16.dp), color = Color.White,
        shadowElevation = 1.dp, modifier = Modifier.heightIn(min = 72.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = t.kind.label, tint = color)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${t.kind.label} · " + SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(t.createdMs)),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${Format.distance(t.distanceM)} · ↑ ${Format.meters(t.ascentM)} · ${Format.duration(t.durationS)}", fontSize = 13.sp)
                if (onOpen == null) Text("Can be loaded after the tour", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!pick) TourItemMenu(t, onRename, onExport, onDelete)
        }
    }
}

/** ⋮ per item in Manage routes: Rename, Export GPX, Delete (asks first). */
@Composable
private fun TourItemMenu(t: Tour, onRename: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, Modifier.size(48.dp)) {
            Icon(Icons.Filled.MoreVert, "Options for “${t.name}”", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun run(a: () -> Unit): () -> Unit = { open = false; a() }
            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                onClick = run(onRename), modifier = Modifier.heightIn(min = 48.dp))
            DropdownMenuItem(text = { Text("Export GPX") }, leadingIcon = { Icon(Icons.Filled.Share, null) },
                onClick = run(onExport), modifier = Modifier.heightIn(min = 48.dp))
            DropdownMenuItem(text = { Text("Delete", color = OnPathColors.Bad) },
                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = OnPathColors.Bad) },
                onClick = run(onDelete), modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
fun NameDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit, confirmLabel: String = "Save") {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onDone(text) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
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

/**
 * "Tour saved" after End tour; its Delete asks first ([DeleteRouteDialog], stays until answered) – Cancel goes
 * back to "Tour saved", nothing is lost by a slip of the finger.
 */
@Composable
fun FinishedTourDialogs(t: Tour, onExport: () -> Unit, onDelete: () -> Unit, onDone: () -> Unit, initialConfirm: Boolean = false) {
    var confirm by remember(t.id) { mutableStateOf(initialConfirm) }
    if (confirm) DeleteRouteDialog(t, onDelete = { confirm = false; onDelete() }, onCancel = { confirm = false })
    else FinishedDialog(t, onExport, onDelete = { confirm = true }, onDone)
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
