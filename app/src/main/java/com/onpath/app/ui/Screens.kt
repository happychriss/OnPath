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
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onpath.app.data.DataFiles
import com.onpath.app.data.Format
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.routing.Activity
import com.onpath.app.plansync.SyncMark
import com.onpath.strings.*
import org.jetbrains.compose.resources.StringResource
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
            Text(str(Res.string.routes_missing_title), style = MaterialTheme.typography.titleLarge)
            Text(str(Res.string.routes_missing_body),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 1.dp) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    checks.forEach { c ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (c.present) Icons.Filled.CheckCircle else Icons.Filled.Cancel, null,
                                tint = if (c.present) OnPathColors.Good else OnPathColors.Bad, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                                Text(c.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(if (c.present) str(Res.string.routes_missing_found) else str(Res.string.routes_missing_missing), style = MaterialTheme.typography.bodySmall,
                                color = if (c.present) OnPathColors.Good else OnPathColors.Bad)
                        }
                    }
                }
            }
            DataActions(
                routingMissing = checks.any { it.name.startsWith("segments") && !it.present },
                busy = busy, onImport = onImport, onDownloadRouting = onDownloadRouting,
            )
            Text(str(Res.string.routes_missing_adb), style = MaterialTheme.typography.bodyMedium)
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF1E2421)) {
                Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        SelectionContainer {
                            Text(DataFiles.ADB_COMMAND, color = Color(0xFFD7F5DF), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    IconButton(onClick = { onCopy(DataFiles.ADB_COMMAND) }, Modifier.size(48.dp)) {
                        Icon(Icons.Filled.ContentCopy, str(Res.string.routes_missing_copy_command), tint = Color.White)
                    }
                }
            }
            Text(str(Res.string.routes_missing_target, dataDir), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                Text(str(Res.string.routes_missing_check_again), style = MaterialTheme.typography.bodyLarge)
            }
            OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                Text(str(Res.string.routes_missing_continue))
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
            Text(str(Res.string.routes_data_import), style = MaterialTheme.typography.bodyLarge)
        }
        if (routingMissing) {
            OutlinedButton(onClick = onDownloadRouting, enabled = busy == null,
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                Text(str(Res.string.routes_data_download_paths))
            }
        }
        if (busy != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(busy, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ------------------------------------------------------------------ activity picker

/**
 * Activity picker (plan sheet chip): each row = name + one short line ([Activity.line]). A bike activity applies and
 * closes; Hike applies and stays open with the T1–T5 row (SAC level) and the selected level's description; a level
 * applies at once (the route is planned again) and closes. Both are remembered across sessions.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ActivityDialog(
    current: Activity,
    onSelect: (Activity) -> Unit, onDismiss: () -> Unit,
    sac: Int = com.onpath.app.data.LastActivity.DEFAULT_SAC,
    onSac: (Int) -> Unit = {},
) {
    val hike = current == Activity.HIKE
    AlertDialog(
        onDismissRequest = onDismiss,
        // picking a bike activity applies it and closes; with Hike the dialog stays for the T level
        confirmButton = { if (hike) TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_done)) } },
        title = { Text(str(Res.string.routes_activity_title)) },
        text = {
            Column {
                Activity.entries.forEach { a ->
                    val sel = a == current
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onSelect(a); if (a != Activity.HIKE) onDismiss() }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(a.icon, null, tint = if (sel) OnPathColors.Green else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(14.dp))
                        // name + one short explaining line ("Gravel bike" / "Gravel tracks welcome")
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(a.label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                            Text(a.line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (hike) {
                    val level = SacScale.of(sac)
                    Spacer(Modifier.height(12.dp))
                    Text(str(Res.string.routes_sac_title), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                    androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SacScale.levels.forEachIndexed { i, l ->
                            val desc = str(Res.string.routes_sac_button_desc, l.level, l.name)
                            SegmentedButton(
                                selected = l.level == level.level,
                                onClick = { onSac(l.level); onDismiss() },
                                shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, SacScale.levels.size),
                                icon = {}, // no check mark: five narrow segments
                                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = desc },
                            ) { Text("T${l.level}", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    Text(str(Res.string.routes_sac_line, level.level, level.name, level.text), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                }
            }
        },
    )
}

/**
 * SAC hiking scale (Swiss Alpine Club), short official descriptions. Chosen in the activity picker with Hike: the
 * routing avoids paths above the chosen level.
 */
internal object SacScale {
    /** [name] / [text] in the app's language (strings_routes.xml; German = the SAC's own German wording). */
    class Level(val level: Int, private val nameRes: StringResource, private val textRes: StringResource) {
        val name: String get() = Txt.s(nameRes)
        val text: String get() = Txt.s(textRes)
    }
    val levels = listOf(
        Level(1, Res.string.routes_sac_1_name, Res.string.routes_sac_1_text),
        Level(2, Res.string.routes_sac_2_name, Res.string.routes_sac_2_text),
        Level(3, Res.string.routes_sac_3_name, Res.string.routes_sac_3_text),
        Level(4, Res.string.routes_sac_4_name, Res.string.routes_sac_4_text),
        Level(5, Res.string.routes_sac_5_name, Res.string.routes_sac_5_text),
    )
    fun of(level: Int): Level = levels[level.coerceIn(1, 5) - 1]
}

// ------------------------------------------------------------------ manage routes

/**
 * My routes (☰ → My routes): two tabs ([RoutesTab]) – Planned (planned routes + imported GPX files, what syncs to the
 * browser; Import GPX lives here) and Done (recorded tours) – each a list, newest first: type icon, name, date,
 * distance. Per item ⋮ Rename / Export GPX / Delete (asks first), tap = open. A route from the browser not opened yet
 * has a bold title and "New from the browser" / "Changed in the browser" ([marks]). ⋮ Export all takes both tabs.
 * While [navigating], items that would replace the plan cannot be opened (they wait for the end of the tour).
 */
@Composable
fun RoutesScreen(
    tours: List<Tour>,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onOpen: (Tour) -> Unit,
    onExport: (Tour) -> Unit,
    onDelete: (Tour) -> Unit,
    navigating: Boolean = false,
    /** The tour being recorded right now: it cannot be deleted (that would lose the track). */
    recordingId: Long = -1L,
    onRename: (Tour, String) -> Unit = { _, _ -> },
    initialConfirmDelete: Tour? = null,
    initialRename: Tour? = null,
    /** ⋮ → Export all (one ZIP of GPX files). */
    onExportAll: () -> Unit = {},
    /** ⋮ → Recycle bin. */
    onRecycleBin: () -> Unit = {},
    /** ⋮ per recorded tour with rides (bus / car / lift) → Edit rides. */
    onEditRides: (Tour) -> Unit = {},
    /** Import / Export all running: a spinner on the Import button / a line under the title. */
    busy: Busy? = null,
    /** The result of something done here (restored, export / import failed …): a line under the title. */
    note: Notice? = null,
    /** The tab shown first (the app keeps the last one chosen while it runs). */
    initialTab: RoutesTab = RoutesTab.PLANNED,
    onTab: (RoutesTab) -> Unit = {},
    /** Routes from the browser not opened yet (tour uuid → mark): bold title + a grey line (plansync/SyncMarks). */
    marks: Map<String, SyncMark> = emptyMap(),
) {
    var confirmDelete by remember { mutableStateOf(initialConfirmDelete) }
    var tab by remember { mutableStateOf(initialTab) }
    val shown = remember(tours, tab) { RoutesTab.filter(tours, tab) }
    var rename by remember { mutableStateOf(initialRename) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, str(Res.string.common_back)) }
                Text(str(Res.string.routes_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                // Import GPX lives in Planned (an import lands there); a running import keeps its spinner on either tab
                if (tab == RoutesTab.PLANNED || busy == Busy.IMPORT) {
                    TextButton(onClick = { if (busy == null) onImport() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        if (busy == Busy.IMPORT) BusyContent(str(Res.string.routes_importing))
                        else { Icon(Icons.Filled.UploadFile, null); Spacer(Modifier.width(6.dp)); Text(str(Res.string.routes_import_gpx)) }
                    }
                }
                RoutesMenu(onExportAll, onRecycleBin, canExport = tours.isNotEmpty() && busy == null)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                RoutesTab.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = tab == t,
                        onClick = { tab = t; onTab(t) },
                        shape = SegmentedButtonDefaults.itemShape(i, RoutesTab.entries.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(str(if (t == RoutesTab.PLANNED) Res.string.routes_tab_planned else Res.string.routes_tab_done)) }
                }
            }
            if (busy == Busy.EXPORT) Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically) { BusyContent(Busy.EXPORT.text) }
            note?.let { NoticeLine(it, Modifier.padding(horizontal = 20.dp)) }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(str(if (tab == RoutesTab.PLANNED) Res.string.routes_empty_planned else Res.string.routes_empty_done),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shown, key = { it.id }) { t ->
                        val openable = RouteRules.canOpen(t.kind, t.waypoints.size, navigating)
                        TourCard(t, mark = marks[t.uuid].takeIf { t.uuid.isNotEmpty() }, onOpen = if (openable) ({ onOpen(t) }) else null,
                            onRename = { rename = t }, onExport = { onExport(t) },
                            onDelete = if (t.id == recordingId) null else ({ confirmDelete = t }),
                            onEditRides = if (t.rides?.rides.isNullOrEmpty() || t.id == recordingId) null else ({ onEditRides(t) }))
                    }
                }
            }
        }
    }
    confirmDelete?.let { t ->
        DeleteRouteDialog(t, onDelete = { confirmDelete = null; onDelete(t) }, onCancel = { confirmDelete = null })
    }
    rename?.let { t ->
        NameDialog(str(when (t.kind) {
            TourKind.PLANNED -> Res.string.routes_rename_planned
            TourKind.RECORDED -> Res.string.routes_rename_recorded
            TourKind.IMPORTED -> Res.string.routes_rename_imported
        }), t.name, confirmLabel = str(Res.string.common_rename),
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
        title = { Text(str(Res.string.routes_delete_title, t.name)) },
        text = { DialogText(RECYCLE_BIN_NOTE) },
        confirmButton = { TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_delete), color = OnPathColors.Bad) } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_cancel)) } },
    )
}

/** Loading would overwrite a plan that is not saved: stays until answered (tap outside / Back = cancel). */
@Composable
fun ReplacePlanDialog(onSaveFirst: () -> Unit, onReplace: () -> Unit, onCancel: () -> Unit, fromLink: Boolean = false) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Filled.Route, null) },
        title = { Text(str(Res.string.routes_replace_title)) },
        text = { DialogText(str(if (fromLink) Res.string.routes_replace_text_link else Res.string.routes_replace_text)) },
        confirmButton = { TextButton(onClick = onReplace, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_replace)) } },
        dismissButton = { TextButton(onClick = onSaveFirst, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.routes_save_first)) } },
    )
}

@Composable
private fun TourCard(
    t: Tour, mark: SyncMark?, onOpen: (() -> Unit)?, onRename: () -> Unit, onExport: () -> Unit, onDelete: (() -> Unit)?,
    onEditRides: (() -> Unit)? = null,
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
                // from the browser and not opened yet (owner 2026-10-30): bold title + a grey line, until opened
                Text(t.name, fontWeight = if (mark != null) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                mark?.let {
                    Text(str(if (it == SyncMark.NEW) Res.string.routes_mark_new else Res.string.routes_mark_changed),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${t.kind.label} · " + SimpleDateFormat(str(Res.string.common_date), Locale.getDefault()).format(Date(t.createdMs)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${Format.distance(t.distanceM)} · ↑ ${Format.meters(t.ascentM)} · ${Format.duration(t.durationS)}", style = MaterialTheme.typography.bodySmall)
                if (onDelete == null) Text(str(Res.string.routes_recording_now), style = MaterialTheme.typography.bodySmall, color = OnPathColors.Rec)
                else if (onOpen == null) Text(str(Res.string.routes_after_tour), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TourItemMenu(t, onRename, onExport, onDelete, onEditRides)
        }
    }
}

/** ⋮ in the My routes title bar: Export all (ZIP), Recycle bin. */
@Composable
private fun RoutesMenu(onExportAll: () -> Unit, onRecycleBin: () -> Unit, canExport: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, Modifier.size(48.dp)) { Icon(Icons.Filled.MoreVert, str(Res.string.common_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(str(Res.string.routes_export_all)) }, leadingIcon = { Icon(Icons.Filled.Share, null) }, enabled = canExport,
                onClick = { open = false; onExportAll() }, modifier = Modifier.heightIn(min = 48.dp))
            DropdownMenuItem(text = { Text(str(Res.string.common_recycle_bin)) }, leadingIcon = { Icon(Icons.Outlined.DeleteSweep, null) },
                onClick = { open = false; onRecycleBin() }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

/** ⋮ per item in My routes: Rename, Export GPX, Delete (asks first; not for the tour being recorded). */
@Composable
private fun TourItemMenu(t: Tour, onRename: () -> Unit, onExport: () -> Unit, onDelete: (() -> Unit)?, onEditRides: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, Modifier.size(48.dp)) {
            Icon(Icons.Filled.MoreVert, str(Res.string.routes_options_desc, t.name), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun run(a: () -> Unit): () -> Unit = { open = false; a() }
            DropdownMenuItem(text = { Text(str(Res.string.common_rename)) }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                onClick = run(onRename), modifier = Modifier.heightIn(min = 48.dp))
            DropdownMenuItem(text = { Text(str(Res.string.common_export_gpx)) }, leadingIcon = { Icon(Icons.Filled.Share, null) },
                onClick = run(onExport), modifier = Modifier.heightIn(min = 48.dp))
            if (onEditRides != null) DropdownMenuItem(text = { Text(str(Res.string.routes_edit_rides)) },
                leadingIcon = { Icon(rideIcon(t.rides?.rides.orEmpty()), null) },
                onClick = run(onEditRides), modifier = Modifier.heightIn(min = 48.dp))
            if (onDelete != null) DropdownMenuItem(text = { Text(str(Res.string.common_delete), color = OnPathColors.Bad) },
                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = OnPathColors.Bad) },
                onClick = run(onDelete), modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
fun NameDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit, confirmLabel: String = str(Res.string.common_save),
               /** A better name computed in the background (route names): replaces [initial] until the user types. */
               suggestion: String? = null) {
    var text by remember { mutableStateOf(suggestion ?: initial) }
    var typed by remember { mutableStateOf(false) }
    LaunchedEffect(suggestion) { if (suggestion != null && !typed) text = suggestion }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it; typed = true }, singleLine = true, label = { Text(str(Res.string.routes_name_label)) }) },
        confirmButton = { TextButton(onClick = { onDone(text) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_cancel)) } },
    )
}

/**
 * A recording was interrupted (app or phone restarted without the service): Continue · Delete · Save, like End tour.
 * Delete asks once more, then the tour goes to the recycle bin. Stays until answered.
 */
@Composable
fun UnfinishedDialog(t: Tour, onContinue: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    var askDelete by remember(t.id) { mutableStateOf(false) }
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            icon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(str(Res.string.routes_unfinished_delete_title)) },
            text = { DialogText(RECYCLE_BIN_NOTE) },
            confirmButton = { TextButton(onClick = { askDelete = false; onDelete() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(str(Res.string.common_delete)) } },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text(str(Res.string.common_cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(str(Res.string.routes_unfinished_title)) },
        text = { DialogText(str(Res.string.routes_unfinished_text, Format.distance(t.distanceM), Format.duration(t.durationS))) },
        confirmButton = { TextButton(onClick = onSave) { Text(str(Res.string.common_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onContinue) { Text(str(Res.string.routes_unfinished_continue)) }
                TextButton(onClick = { askDelete = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(str(Res.string.common_delete)) }
            }
        },
    )
}

/**
 * "Tour saved" after End tour; its Delete asks first ([DeleteRouteDialog], stays until answered) – Cancel goes
 * back to "Tour saved", nothing is lost by a slip of the finger. The plan has already left the map by then (Undo
 * brings it back), so there is no "Clear route from map" here.
 */
@Composable
fun FinishedTourDialogs(t: Tour, onExport: () -> Unit, onDelete: () -> Unit, onDone: () -> Unit, initialConfirm: Boolean = false,
                        times: com.onpath.app.time.TourTimes? = null) {
    var confirm by remember(t.id) { mutableStateOf(initialConfirm) }
    if (confirm) DeleteRouteDialog(t, onDelete = { confirm = false; onDelete() }, onCancel = { confirm = false })
    else FinishedDialog(t, onExport, onDelete = { confirm = true }, onDone, times)
}

@Composable
fun FinishedDialog(t: Tour, onExport: () -> Unit, onDelete: () -> Unit, onDone: () -> Unit,
                   times: com.onpath.app.time.TourTimes? = null) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(str(Res.string.routes_track_saved)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t.name, fontWeight = FontWeight.SemiBold)
                StatsRow(t.distanceM, t.ascentM, t.descentM, t.durationS)
                times?.let { TourTimesText(it) } // moving time, breaks, planned vs. needed (PaceSettings.kt)
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(str(Res.string.common_done)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text(str(Res.string.common_delete), color = OnPathColors.Bad) }
                TextButton(onClick = onExport) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(4.dp)); Text(str(Res.string.common_export_gpx)) }
            }
        },
    )
}
