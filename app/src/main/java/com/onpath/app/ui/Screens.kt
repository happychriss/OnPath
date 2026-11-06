package com.onpath.app.ui

import androidx.compose.ui.semantics.heading
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Save
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
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
            Surface(shape = RoundedCornerShape(16.dp), color = OnPathColors.Paper, shadowElevation = 1.dp) {
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
 * Activity picker (the activity button on the map, owner 2026-11-05): each row = name + one short line
 * ([Activity.line]); ONE tap chooses and closes – no confirm. Hike has its T1–T5 chips (SAC level) right under its row:
 * a chip = Hike with that level, closes; the Hike row itself keeps the last level. Both are remembered across sessions.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ActivityDialog(
    current: Activity,
    onSelect: (Activity) -> Unit, onDismiss: () -> Unit,
    sac: Int = com.onpath.app.data.LastActivity.DEFAULT_SAC,
    onSac: (Int) -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // one tap chooses (owner 2026-11-05): no Done button, only a way out without a change
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_cancel)) } },
        title = { Text(str(Res.string.routes_activity_title)) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Activity.entries.forEach { a ->
                    val sel = a == current
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onSelect(a); onDismiss() }.padding(horizontal = 12.dp),
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
                    if (a == Activity.HIKE) SacChips(if (sel) sac else 0) { level -> onSelect(Activity.HIKE); onSac(level); onDismiss() }
                }
            }
        },
    )
}

/** Hike's T1–T5 under its row: one tap = Hike with that level, closes; [selected] 0 = none marked. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SacChips(selected: Int, onPick: (Int) -> Unit) {
    // one row of five equal chips (never a lone T5 on a second line)
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SacScale.levels.forEach { l ->
            val desc = str(Res.string.routes_sac_button_desc, l.level, l.name)
            androidx.compose.material3.FilterChip(
                selected = l.level == selected, onClick = { onPick(l.level) },
                label = { Text("T${l.level}", style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                modifier = Modifier.weight(1f).semantics { contentDescription = desc },
            )
        }
    }
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
 * distance. Per item ⋮ Rename / Save as copy + Share plan (planned routes) / Export GPX / Delete (asks first), tap = open. A route from the browser not opened yet
 * has a tag after its name: "New" / "Changed" ([marks] → [RouteTag]). ⋮ Export all takes both tabs.
 * During a tour a planned route loads nothing: a tap shows the box "End the running tour first, then this route can be
 * loaded." with OK under the tabs (HINWEIS, ScreenMessageHost – AppViewModel.requestOpen; owner 2026-10-31).
 */
@Composable
fun RoutesScreen(
    tours: List<Tour>,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onOpen: (Tour) -> Unit,
    onExport: (Tour) -> Unit,
    onDelete: (Tour) -> Unit,
    /** The tour being recorded right now: it cannot be deleted (that would lose the track). */
    recordingId: Long = -1L,
    onRename: (Tour, String) -> Unit = { _, _ -> },
    /** ⋮ → Save as copy (planned routes): Save's name dialog with "<name> (2)" (owner 2026-10-31, from the plan sheet's ⋯). */
    onCopy: (Tour) -> Unit = {},
    /** ⋮ → Share plan (planned routes): the route as a plan.onpath.earth link (A-005). */
    onShare: (Tour) -> Unit = {},
    initialConfirmDelete: Tour? = null,
    initialRename: Tour? = null,
    /** ⋮ → Export all (one ZIP of GPX files). */
    onExportAll: () -> Unit = {},
    /** ⋮ → Recycle bin. */
    onRecycleBin: () -> Unit = {},
    /** ⋮ per recorded tour with rides (bus / car / lift) → Edit rides. */
    onEditRides: (Tour) -> Unit = {},
    /**
     * ⋮ per done tour / track → "Als Plan übernehmen" (owner 2026-11-06, F1: during a tour a tap only asks "Dieser Tour
     * folgen?" – making a plan of it lives here).
     */
    onPlanFrom: (Tour) -> Unit = {},
    /** Import / Export all running: a spinner on the Import button / a line under the title. */
    busy: Busy? = null,
    /** The result of something done here (restored, export / import failed …): a line under the title. */
    note: Notice? = null,
    /** The tab shown first (the app keeps the last one chosen while it runs). */
    initialTab: RoutesTab = RoutesTab.PLANNED,
    onTab: (RoutesTab) -> Unit = {},
    /** Routes from the browser not opened yet (tour uuid → mark): a "New" / "Changed" tag after the name (plansync/SyncMarks). */
    marks: Map<String, SyncMark> = emptyMap(),
    /** The search box's text at the start (screenshots). */
    initialQuery: String = "",
    /** Zuletzt → Speichern: the plan becomes saved (owner 2026-11-06). */
    onSaveDraft: (Tour) -> Unit = {},
    /** Zuletzt → Löschen: to the recycle bin, no question. */
    onDeleteDraft: (Tour) -> Unit = {},
    /** The plan just saved from Zuletzt: "✓ Gerade gespeichert" on it. */
    justSavedId: Long = -1L,
    /** All plans under Zuletzt shown at the start (screenshots); else the newest [RECENT_SHOWN]. */
    initialAllRecent: Boolean = false,
) {
    var confirmDelete by remember { mutableStateOf(initialConfirmDelete) }
    var tab by remember { mutableStateOf(initialTab) }
    // the search box (owner 2026-11-06): kept across the tabs, filters the list of the tab shown
    var query by remember { mutableStateOf(initialQuery) }
    val shown = remember(tours, tab, query) { RoutesTab.search(RoutesTab.filter(tours, tab), query) }
    var rename by remember { mutableStateOf(initialRename) }
    var allRecent by remember { mutableStateOf(initialAllRecent) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.systemBarsPadding()) {
            // the title never breaks inside a word: at a large font Import GPX becomes its icon (TopBarLayout)
            val importLabel = str(Res.string.routes_import_gpx)
            val importingLabel = str(Res.string.routes_importing)
            TopBarRow(
                back = { IconButton(onClick = onBack, Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, str(Res.string.common_back)) } },
                title = { ellipsize ->
                    Text(str(Res.string.routes_title), style = MaterialTheme.typography.titleLarge,
                        maxLines = if (ellipsize) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                },
                // Import GPX lives in Planned (an import lands there); a running import keeps its spinner on either tab
                action = if (tab == RoutesTab.PLANNED || busy == Busy.IMPORT) ({ iconOnly ->
                    if (iconOnly) IconButton(onClick = { if (busy == null) onImport() }, Modifier.size(48.dp)) {
                        if (busy == Busy.IMPORT) CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = importingLabel }, strokeWidth = 2.dp)
                        else Icon(Icons.Filled.UploadFile, importLabel)
                    } else TextButton(onClick = { if (busy == null) onImport() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        if (busy == Busy.IMPORT) BusyContent(importingLabel)
                        else { Icon(Icons.Filled.UploadFile, null); Spacer(Modifier.width(6.dp)); Text(importLabel) }
                    }
                }) else null,
                menu = { RoutesMenu(onExportAll, onRecycleBin, canExport = tours.isNotEmpty() && busy == null) },
            )
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
            if (tours.isNotEmpty()) RoutesSearchField(query, { query = it }, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            if (busy == Busy.EXPORT) Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically) { BusyContent(Busy.EXPORT.text) }
            note?.let { NoticeLine(it, Modifier.padding(horizontal = 20.dp)) }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(if (query.isNotBlank()) str(Res.string.routes_search_none, query.trim())
                        else str(if (tab == RoutesTab.PLANNED) Res.string.routes_empty_planned else Res.string.routes_empty_done),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                // Planned (owner 2026-11-06, "Zuletzt"): the plans kept by themselves on top – the newest three, the rest
                // on demand – then the saved ones; a draft is saved / deleted right on its card
                val drafts = shown.filter { it.draft }
                val saved = shown.filter { !it.draft }
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (drafts.isNotEmpty()) {
                        item(key = "h-recent") { SectionHeader(str(Res.string.routes_section_recent)) }
                        items(if (allRecent) drafts else drafts.take(RECENT_SHOWN), key = { it.id }) { t ->
                            TourCard(t, tag = null, onOpen = { onOpen(t) }, onRename = { rename = t }, onExport = { onExport(t) },
                                onDelete = { onDeleteDraft(t) }, footer = { DraftActions({ onSaveDraft(t) }, { onDeleteDraft(t) }) })
                        }
                        if (!allRecent && drafts.size > RECENT_SHOWN) item(key = "all-recent") {
                            TextButton({ allRecent = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(str(Res.string.routes_recent_all, drafts.size))
                            }
                        }
                        if (saved.isNotEmpty()) item(key = "h-saved") { SectionHeader(str(Res.string.routes_section_saved)) }
                    }
                    items(saved, key = { it.id }) { t ->
                        TourCard(t, tag = RouteTag.of(t, marks), onOpen = { onOpen(t) }, justSaved = t.id == justSavedId,
                            onRename = { rename = t }, onExport = { onExport(t) },
                            onCopy = if (t.kind == TourKind.PLANNED) ({ onCopy(t) }) else null,
                            onShare = if (t.kind == TourKind.PLANNED && t.waypoints.size >= 2) ({ onShare(t) }) else null,
                            onDelete = if (t.id == recordingId) null else ({ confirmDelete = t }),
                            onEditRides = if (t.rides?.rides.isNullOrEmpty() || t.id == recordingId) null else ({ onEditRides(t) }),
                            onPlanFrom = if (t.kind == TourKind.PLANNED || t.distanceM <= 0.0 || t.id == recordingId) null else ({ onPlanFrom(t) }))
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

/** Meine Touren's search box: one line, a magnifier, ✕ clears (owner 2026-11-06). */
@Composable
private fun RoutesSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value = query, onValueChange = onQuery, singleLine = true, modifier = modifier.fillMaxWidth(),
        placeholder = { Text(str(Res.string.routes_search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = if (query.isNotEmpty()) ({
            IconButton(onClick = { onQuery("") }, Modifier.size(48.dp)) { Icon(Icons.Filled.Close, str(Res.string.search_clear_query)) }
        }) else null,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search))
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


@Composable
private fun TourCard(
    t: Tour, tag: RouteTag?, onOpen: (() -> Unit)?, onRename: () -> Unit, onExport: () -> Unit, onDelete: (() -> Unit)?,
    onEditRides: (() -> Unit)? = null, onCopy: (() -> Unit)? = null, onShare: (() -> Unit)? = null,
    onPlanFrom: (() -> Unit)? = null,
    /** Under the card's text, over its full width (a draft's Speichern · Löschen). */
    footer: (@Composable () -> Unit)? = null,
    /** "✓ Gerade gespeichert" under the numbers. */
    justSaved: Boolean = false,
) {
    val (icon, color) = when (t.kind) {
        TourKind.PLANNED -> Icons.Filled.Route to OnPathColors.Route
        TourKind.RECORDED -> Icons.Filled.FiberManualRecord to OnPathColors.Rec
        TourKind.IMPORTED -> Icons.Filled.FileOpen to OnPathColors.Gpx
    }
    // a plan under Zuletzt: the route sign pale – only a draft (owner 2026-11-06), the words the same ("Plan · date")
    val pale = DraftLook.of(t.draft)
    Surface(onClick = { onOpen?.invoke() }, enabled = onOpen != null, shape = RoundedCornerShape(16.dp), color = OnPathColors.Paper,
        shadowElevation = 1.dp, modifier = Modifier.heightIn(min = 72.dp)) {
        Column {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(color.copy(alpha = pale.circle)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = t.kind.label, tint = color.copy(alpha = pale.icon))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // from the browser and not opened yet (owner 2026-10-31): a "New" / "Changed" tag after the name, until opened
                RouteTitle(t.name, tag)
                Text("${t.kind.label} · " + SimpleDateFormat(str(Res.string.common_date), Locale.getDefault()).format(Date(t.createdMs)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${Format.distance(t.distanceM)} · ↑ ${Format.meters(t.ascentM)} · ${Format.duration(t.durationS)}", style = MaterialTheme.typography.bodySmall)
                if (onDelete == null) Text(str(Res.string.routes_recording_now), style = MaterialTheme.typography.bodySmall, color = OnPathColors.Rec)
                if (justSaved) Text(str(Res.string.routes_just_saved), style = MaterialTheme.typography.labelLarge,
                    color = OnPathColors.ActionEdit, modifier = Modifier.padding(top = 2.dp))
            }
            TourItemMenu(t, onRename, onExport, onDelete, onEditRides, onCopy, onShare, onPlanFrom)
        }
        // a large font: the buttons get the card's whole width (they stack, DraftActions)
        val wide = androidx.compose.ui.platform.LocalDensity.current.fontScale >= DraftLook.STACK_FONT_SCALE
        footer?.let { Box(Modifier.fillMaxWidth().padding(start = if (wide) 14.dp else 70.dp, end = 12.dp, bottom = 8.dp)) { it() } }
        }
    }
}

/** How many plans Zuletzt shows before "Alle N anzeigen ›". */
const val RECENT_SHOWN = 3

/** A draft's route sign: pale (owner 2026-11-06 "damit man sieht, dass es nur ein Draft ist"); a saved one as always. */
data class DraftLook(val icon: Float, val circle: Float) {
    companion object {
        /** From this font scale a draft's Speichern · Löschen stack, full width. */
        const val STACK_FONT_SCALE = 1.3f
        fun of(draft: Boolean) = if (draft) DraftLook(icon = 0.35f, circle = 0.05f) else DraftLook(icon = 1f, circle = 0.12f)
    }
}

/** "ZULETZT" / "GESPEICHERT" over the parts of My routes → Planned. */
@Composable
private fun SectionHeader(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 6.dp).semantics { heading() })

/** A draft's own buttons: blue "💾 Speichern" (plan colour, the ☰ Save sign) and red "🗑 Löschen" (recycle bin, no question). */
@Composable
private fun DraftActions(onSave: () -> Unit, onDelete: () -> Unit) {
    val save = @Composable { m: Modifier ->
        androidx.compose.material3.FilledTonalButton(onSave, modifier = m.heightIn(min = 48.dp),
            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = OnPathColors.PlanTint, contentColor = OnPathColors.ActionEdit)) {
            Icon(Icons.Outlined.Save, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(str(Res.string.common_save), maxLines = 1) }
    }
    val delete = @Composable { m: Modifier ->
        TextButton(onDelete, modifier = m.heightIn(min = 48.dp),
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Icon(Icons.Outlined.DeleteOutline, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(str(Res.string.common_delete), maxLines = 1) }
    }
    // a large font: one under the other, full width (M3: never a word broken into letters)
    if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= DraftLook.STACK_FONT_SCALE)
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            save(Modifier.fillMaxWidth()); delete(Modifier.fillMaxWidth())
        }
    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        save(Modifier); delete(Modifier)
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

/**
 * ⋮ per item in My routes: Rename, Save as copy (planned), Export GPX, Share plan (planned), Delete (asks first; not
 * for the tour being recorded).
 */
@Composable
private fun TourItemMenu(t: Tour, onRename: () -> Unit, onExport: () -> Unit, onDelete: (() -> Unit)?, onEditRides: (() -> Unit)? = null,
                         onCopy: (() -> Unit)? = null, onShare: (() -> Unit)? = null, onPlanFrom: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, Modifier.size(48.dp)) {
            Icon(Icons.Filled.MoreVert, str(Res.string.routes_options_desc, t.name), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TourMenuItems(t, onRename, onExport, onDelete, onEditRides, onCopy, onShare, onPlanFrom) { open = false }
        }
    }
}

/** The items of a My routes ⋮ (also drawn on their own by the screenshot tests). */
@Composable
internal fun TourMenuItems(t: Tour, onRename: () -> Unit, onExport: () -> Unit, onDelete: (() -> Unit)?, onEditRides: (() -> Unit)?,
                           onCopy: (() -> Unit)?, onShare: (() -> Unit)?, onPlanFrom: (() -> Unit)? = null, close: () -> Unit) {
    fun on(a: () -> Unit): () -> Unit = { close(); a() }
    // a done tour / track as a plan to edit (owner 2026-11-06, F1): planning = blue
    if (onPlanFrom != null) DropdownMenuItem(text = { Text(str(Res.string.plan_from_track), color = OnPathColors.ActionEdit) },
        leadingIcon = { Icon(Icons.Filled.Route, null, tint = OnPathColors.ActionEdit) },
        onClick = on(onPlanFrom), modifier = Modifier.heightIn(min = 48.dp))
    DropdownMenuItem(text = { Text(str(Res.string.common_rename)) }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
        onClick = on(onRename), modifier = Modifier.heightIn(min = 48.dp))
    if (onCopy != null) DropdownMenuItem(text = { Text(str(Res.string.plan_save_as_copy)) },
        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = on(onCopy), modifier = Modifier.heightIn(min = 48.dp))
    DropdownMenuItem(text = { Text(str(Res.string.common_export_gpx)) }, leadingIcon = { Icon(Icons.Filled.Share, null) },
        onClick = on(onExport), modifier = Modifier.heightIn(min = 48.dp))
    if (onShare != null) DropdownMenuItem(text = { Text(str(Res.string.plan_share_plan)) },
        leadingIcon = { Icon(Icons.Outlined.Link, null) }, onClick = on(onShare), modifier = Modifier.heightIn(min = 48.dp))
    if (onEditRides != null) DropdownMenuItem(text = { Text(str(Res.string.routes_edit_rides)) },
        leadingIcon = { Icon(rideIcon(t.rides?.rides.orEmpty()), null) },
        onClick = on(onEditRides), modifier = Modifier.heightIn(min = 48.dp))
    if (onDelete != null) DropdownMenuItem(text = { Text(str(Res.string.common_delete), color = OnPathColors.Bad) },
        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = OnPathColors.Bad) },
        onClick = on(onDelete), modifier = Modifier.heightIn(min = 48.dp))
}

// ------------------------------------------------------------------ dialogs

@Composable
fun NameDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit, confirmLabel: String = str(Res.string.common_save)) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text(str(Res.string.routes_name_label)) }) },
        confirmButton = { TextButton(onClick = { onDone(text) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_cancel)) } },
    )
}

/**
 * A recording was interrupted (app or phone restarted without the service): Delete · Continue · Save, like End tour.
 * Delete asks once more, then the tour goes to the recycle bin. Stays until answered.
 */
@Composable
fun UnfinishedDialog(t: Tour, onContinue: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(str(Res.string.routes_unfinished_title)) },
        text = { DialogText(str(Res.string.routes_unfinished_text, Format.distance(t.distanceM), Format.duration(t.durationS))) },
        confirmButton = { DeleteApartButtons(onDelete, str(Res.string.routes_unfinished_continue), onContinue,
            str(Res.string.common_save), onSave) },
    )
}

/**
 * "Tour gespeichert" after "Tour speichern" (owner 2026-11-06, onpath.private#35: the box is clearer than a line in the
 * plan sheet – slim this time): the name, the numbers, moving time / breaks / planned vs. needed, "In Meine Touren ›"
 * and ONE button OK. No delete (the end sheet just offered it), no export (My routes ⋮).
 */
@Composable
fun SavedTourDialog(t: Tour, times: com.onpath.app.time.TourTimes?, onOpen: () -> Unit, onOk: () -> Unit) {
    val neutral = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = OnPathColors.Ink)
    AlertDialog(
        onDismissRequest = onOk,
        icon = { Icon(Icons.Filled.CheckCircle, null, tint = OnPathColors.ActionGo) },
        title = { Text(str(Res.string.routes_track_saved)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t.name, fontWeight = FontWeight.SemiBold)
                StatsRow(t.distanceM, t.ascentM, t.descentM, t.durationS)
                times?.let { TourTimesText(it) } // moving time, breaks, planned vs. needed (PaceSettings.kt)
            }
        },
        // M3: one row ("In Meine Touren ›" · OK); a large font that does not fit: equal full-width buttons, never ragged
        confirmButton = { SavedTourActions(str(Res.string.routes_saved_open), onOpen, str(Res.string.common_ok), onOk, neutral) },
    )
}

@Composable
private fun SavedTourActions(open: String, onOpen: () -> Unit, ok: String, onOk: () -> Unit,
                             neutral: androidx.compose.material3.ButtonColors) {
    val tm = androidx.compose.ui.text.rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // each text button: its word + 12 dp padding each side; 8 dp between
        val need = with(density) { listOf(open, ok).sumOf { tm.measure(it, style).size.width.toDp().value.toDouble() + 24 } }.toFloat() + 8f
        if (DecisionLayout.oneRow(need, maxWidth.value)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOpen, colors = neutral, modifier = Modifier.heightIn(min = 48.dp)) { Text(open, maxLines = 1, softWrap = false) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onOk, colors = neutral, modifier = Modifier.heightIn(min = 48.dp)) { Text(ok, maxLines = 1, softWrap = false) }
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedButton(onClick = onOk, colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = OnPathColors.Ink),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(ok) }
            TextButton(onClick = onOpen, colors = neutral, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(open) }
        }
    }
}
