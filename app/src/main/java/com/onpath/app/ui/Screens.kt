package com.onpath.app.ui

import com.onpath.app.ui.routes.RouteRowActions
import com.onpath.app.ui.routes.RouteSort
import com.onpath.app.ui.routes.RouteSortChips
import kotlinx.coroutines.launch

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.draw.alpha
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
import com.onpath.app.domain.plan.LivePlan
import com.onpath.app.domain.plan.LivePlanRows
import com.onpath.app.domain.plan.RecentRow
import com.onpath.app.domain.tour.TrackLine

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
            Surface(shape = OnPathShapes.Card, color = OnPathColors.CardPaper, modifier = Modifier.warmShadow(OnPathShadow.LOW, OnPathShapes.Card)) {
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
            Surface(shape = OnPathShapes.Small, color = Color(0xFF1E2421)) {
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
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(56.dp), shape = OnPathShapes.Pill) {
                Text(str(Res.string.routes_missing_check_again), style = MaterialTheme.typography.bodyLarge)
            }
            OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(56.dp), shape = OnPathShapes.Pill) {
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
            shape = OnPathShapes.Pill) {
            Text(str(Res.string.routes_data_import), style = MaterialTheme.typography.bodyLarge)
        }
        if (routingMissing) {
            OutlinedButton(onClick = onDownloadRouting, enabled = busy == null,
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = OnPathShapes.Pill) {
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
 * ([Activity.line]); ONE tap chooses and closes – no confirm. Hike has its T1–T5 chips (SAC level) right under its row,
 * and under them the chosen level's name and description ("T2 · Bergwandern: …", owner 2026-11-08, father's feedback:
 * on a small phone the chips showed only "T1 … T5", and a tap on a T closed at once without saying what it means). A
 * chip = Hike with that level at once (the route is planned again); the picker stays open so its description can be
 * read, and "Fertig" closes it. The Hike row itself keeps the last level. Both are remembered across sessions.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ActivityDialog(
    current: Activity,
    onSelect: (Activity) -> Unit, onDismiss: () -> Unit,
    sac: Int = com.onpath.app.data.LastActivity.DEFAULT_SAC,
    onSac: (Int) -> Unit = {},
) {
    // a T chip chose Hike + a level and the picker stayed open for its description: the way out says "Done"
    var levelPicked by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        // one tap on an activity chooses (owner 2026-11-05): no Done button, only a way out without a change
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(str(if (levelPicked) Res.string.common_done else Res.string.common_cancel)) } },
        title = { Text(str(Res.string.routes_activity_title)) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Activity.entries.forEach { a ->
                    val sel = a == current
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(OnPathShapes.Small)
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
                    if (a == Activity.HIKE) SacChips(if (sel) sac else 0) { level -> onSelect(Activity.HIKE); onSac(level); levelPicked = true }
                }
            }
        },
    )
}

/**
 * Hike's T1–T5 under its row: one tap = Hike with that level; [selected] 0 = none marked. Under the chips the selected
 * level's line "T2 · Bergwandern: …" ([SacScale.line], owner 2026-11-08) – always there while a level is chosen, so a
 * "T2" never stands without its word, on every phone width and font size (it wraps, never cut).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SacChips(selected: Int, onPick: (Int) -> Unit) {
    // a T tapped on a small phone / large font: the picker scrolls so its line is in view (not on the first showing)
    val lineInView = androidx.compose.runtime.remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    var shown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(selected) }
    androidx.compose.runtime.LaunchedEffect(selected) {
        if (selected != shown && SacScale.lineLevel(selected) != null) lineInView.bringIntoView()
        shown = selected
    }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
        SacChipRow(selected, onPick)
        SacScale.lineLevel(selected)?.let { l ->
            Text(SacScale.line(l), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp).bringIntoViewRequester(lineInView))
        }
    }
}

@Composable
private fun SacChipRow(selected: Int, onPick: (Int) -> Unit) {
    // one row of five equal chips (never a lone T5 on a second line). Not Material's FilterChip: its fixed 8 dp label
    // padding cut the number on a 360 dp phone at font 1.3 – only "T" was left (owner 2026-11-08, father's S10); the
    // same chip look (outlined, the chosen one filled) with the whole width for "T2"
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SacScale.levels.forEach { l ->
            val desc = str(Res.string.routes_sac_button_desc, l.level, l.name)
            val sel = l.level == selected
            androidx.compose.material3.Surface(
                selected = sel, onClick = { onPick(l.level) }, shape = OnPathShapes.Small,
                color = if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor = if (sel) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                border = if (sel) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = desc },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("T${l.level}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
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

    /** The level whose description stands under the chips: the selected one; none while no level is chosen (0). */
    fun lineLevel(selected: Int): Int? = selected.takeIf { it in 1..5 }

    /** "T2 · Bergwandern: Durchgehender Weg, …" – the level's number, name and SAC description. */
    fun line(level: Int): String = of(level).let { Txt.s(Res.string.routes_sac_line, it.level, it.name, it.text) }
}

// ------------------------------------------------------------------ manage routes

/**
 * My routes (☰ → My routes): two tabs ([RoutesTab]) – Planned (planned routes + imported GPX files, what syncs to the
 * browser; Import GPX lives here) and Done (recorded tours) – each a list, newest first: type icon, name, date,
 * distance. Per item ⋮ Planen + Los geht's (planned routes, owner 2026-11-07) / Rename / Save as copy + Share plan (planned routes) /
 * Export GPX / Delete (asks first), tap = the large overview (Abbrechen · Los geht's · Planen). A route from the browser not opened yet
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
    /** The plan on the map (owner 2026-11-06 23:00): the one row under Zuletzt, "In Bearbeitung"; null = empty map. */
    live: LivePlan? = null,
    /**
     * The tour under Zuletzt while the map is empty (owner 2026-11-12: exactly one plan there – the one worked on last,
     * a draft or a saved route; RoutesState.recentId); -1 = the newest draft, if any.
     */
    recentId: Long = -1L,
    /** Its row tapped: back to the plan on the map, nothing reloaded. */
    onLiveOpen: () -> Unit = {},
    /** Its Speichern: the same as ☰ Plan speichern. */
    onLiveSave: () -> Unit = {},
    /** "Plan · <today>" on its row (screenshots pass a fixed day). */
    liveNowMs: Long = System.currentTimeMillis(),
    /** A row's line for its small preview and the overview (owner 2026-11-07); empty = the kind's sign. */
    loadLine: suspend (Tour) -> List<List<com.onpath.app.routing.GeoPoint>> = { emptyList() },
    /** The overview's map picture (MapLibre snapshot); null = the drawn shape only (screenshots). */
    snapshot: OverviewSnapshot? = null,
    /** A tour records: the overview's primary is "Folgen" (follows at once, the recording goes on – owner 22:05). */
    touring: Boolean = false,
    /** The overview open at the start (screenshots). */
    initialOverview: Tour? = null,
    /**
     * "Los geht's" on a planned route (owner 2026-11-07: ⋮, the overview, a Zuletzt row's ⋮): loaded as the plan and the
     * tour started by the plan sheet's own start path (AppViewModel.goFromRoutes). Not offered during a tour.
     */
    onGo: (Tour) -> Unit = {},
    /**
     * A recorded tour that followed a plan (owner 2026-11-07): "Planen" / "Los geht's" load that plan (its last version) –
     * AppViewModel.planFollowedPlan / goFollowedPlan. Not during a tour.
     */
    onPlanFollowed: (Tour) -> Unit = {},
    onGoFollowed: (Tour) -> Unit = {},
    /** The line of the plan a recorded tour followed, drawn thin under its track in the overview; empty = none. */
    loadPlanLine: suspend (Tour) -> List<com.onpath.app.routing.GeoPoint> = { emptyList() },
    /** The order of the saved routes / done tours (owner 2026-11-12, [RouteSort]); one for both tabs, kept by the app. */
    initialSort: RouteSort.Choice = RouteSort.DEFAULT,
    onSort: (RouteSort.Choice) -> Unit = {},
    /**
     * Pull to refresh on the Planned tab (owner 2026-11-13: a tour saved in the browser shows now, not after the next
     * poll): a sync pass at once (AppViewModel.pullSync); null = not paired, no gesture.
     */
    onPull: (() -> Unit)? = null,
    /** The pulled pass runs: the pull spinner shows until it ends. */
    pulling: Boolean = false,
    /**
     * The rows' map pictures (owner 2026-11-13, L1: made in the background, read here only – [RouteThumbSource]); null =
     * none, every row draws its line on paper. [thumbVersion] moves on when a picture was made.
     */
    thumbs: com.onpath.app.ui.routes.RouteThumbSource? = null,
    thumbVersion: Int = 0,
    /** ☆ on a row (owner 2026-11-13, favourites C): starred or not. */
    onFavourite: (Tour, Boolean) -> Unit = { _, _ -> },
    /** The "☆ Favoriten" pill on at the start (the app keeps it while it runs; screenshots). */
    initialFavouritesOnly: Boolean = false,
    onFavouritesOnly: (Boolean) -> Unit = {},
) {
    var tab by remember { mutableStateOf(initialTab) }
    var sort by remember { mutableStateOf(initialSort) }
    var favOnly by remember { mutableStateOf(initialFavouritesOnly) }
    // a row tap opens the large overview first (owner 2026-11-07 00:30) – "Planen" / "Folgen" there opens it as before
    var overview by remember { mutableStateOf(initialOverview) }
    val line: @Composable (Tour) -> List<List<com.onpath.app.routing.GeoPoint>>? = { t -> rememberRouteLine(t, loadLine) }
    // the search box (owner 2026-11-06): kept across the tabs, filters the list of the tab shown
    var query by remember { mutableStateOf(initialQuery) }
    val shown = remember(tours, tab, query) { RoutesTab.search(RoutesTab.filter(tours, tab), query) }
    // the list at its top stays there when a row comes before its first (ListTop, E2E 2026-11-07)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val listFirstKey = remember { arrayOfNulls<Any>(1) }
    var rename by remember { mutableStateOf(initialRename) }
    Box(Modifier.fillMaxSize()) {
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
            if (tours.isNotEmpty()) {
                RoutesSearchField(query, { query = it }, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                // the sort chips (owner 2026-11-12, variant C): a new order starts at the top of the list
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                RouteSortChips(sort, { sort = it; onSort(it); scope.launch { listState.scrollToItem(0) } },
                    Modifier.padding(bottom = 8.dp),
                    // favourites C (owner 2026-11-13): "☆ Favoriten" first – on, both tabs show only the starred ones
                    leading = { com.onpath.app.ui.routes.FavouriteChip(favOnly) {
                        favOnly = it; onFavouritesOnly(it); scope.launch { listState.scrollToItem(0) }
                    } })
            }
            if (busy == Busy.EXPORT) Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically) { BusyContent(Busy.EXPORT.text) }
            note?.let { NoticeLine(it, Modifier.padding(horizontal = 20.dp)) }
            // "Zuletzt" = exactly one row (owner 2026-11-12, [LivePlanRows]): the plan on the map ("In Bearbeitung"), else
            // the draft or saved route worked on last – a saved one with the "Gespeichert" tag, and under Gespeichert too
            val tabbed = remember(tours, tab) { RoutesTab.filter(tours, tab) }
            val recent = if (tab == RoutesTab.PLANNED && !favOnly) LivePlanRows.of(live, tabbed.filter { it.draft }, tabbed.filter { !it.draft }, recentId)
                else RecentRow.NONE
            val liveName = live?.name ?: str(Res.string.routes_live_name)
            // the row goes through the search like every other
            val liveTour = if (recent.live && live != null) Tour(LivePlanRows.LIVE_ID, TourKind.PLANNED, liveName,
                liveNowMs, null, 0, live.distanceM, live.ascentM, 0.0, live.durationS, "done", emptyList())
                .takeIf { RoutesTab.search(listOf(it), query).isNotEmpty() } else null
            val recentTour = if (recent.live) null else shown.firstOrNull { it.id == recent.tourId }
            val hasRecent = liveTour != null || recentTour != null
            // the chosen order (RouteSort) for the saved routes / done tours; a draft shows only as the Zuletzt row
            val saved = remember(shown, sort, favOnly) {
                RouteSort.sort(com.onpath.app.ui.routes.Favourites.shown(shown.filter { !it.draft }, favOnly), sort)
            }
            PullToSync(if (tab == RoutesTab.PLANNED) onPull else null, pulling) {
            if (saved.isEmpty() && !hasRecent) {
                // a list of one so the empty Planned tab can be pulled too
                LazyColumn(Modifier.fillMaxSize()) { item {
                    Box(Modifier.fillParentMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(if (query.isNotBlank()) str(Res.string.routes_search_none, query.trim())
                            else if (favOnly) str(Res.string.routes_fav_none)
                            else str(if (tab == RoutesTab.PLANNED) Res.string.routes_empty_planned else Res.string.routes_empty_done),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                } }
            } else {
                // Gespeichert / Gemacht (owner 2026-11-07 09:40, variant A): tile + name + facts + ›, every action in ⋮ –
                // the same card for a saved route under Zuletzt ([tag] "Gespeichert"), where the ✓ mark is left to Gespeichert
                val savedCard: @Composable (Tour, RouteTag?, Boolean) -> Unit = { t, tag, justSaved ->
                    val row = RouteRowActions.of(RouteRowActions.Section.SAVED, t.kind, recording = t.id == recordingId,
                        hasRides = !t.rides?.rides.isNullOrEmpty(), hasDistance = TrackLine.hasDistance(t.distanceM),
                        waypoints = t.waypoints.size, touring = touring, hasPlan = t.hasPlan)
                    fun has(a: RouteRowActions.Action) = a in row.menu
                    TourCard(t, tag = tag, row = row, onOpen = { overview = t }, justSaved = justSaved, line = line(t),
                        recording = t.id == recordingId, thumb = com.onpath.app.ui.routes.rememberThumb(t, thumbs, thumbVersion),
                        onFavourite = { onFavourite(t, it) },
                        onRename = { rename = t }, onExport = { onExport(t) },
                        onCopy = if (has(RouteRowActions.Action.COPY)) ({ onCopy(t) }) else null,
                        onShare = if (has(RouteRowActions.Action.SHARE)) ({ onShare(t) }) else null,
                        // straight to the recycle bin, no question (owner 2026-11-06: the bin is the net)
                        onDelete = if (has(RouteRowActions.Action.DELETE)) ({ onDelete(t) }) else null,
                        onEditRides = if (has(RouteRowActions.Action.EDIT_RIDES)) ({ onEditRides(t) }) else null,
                        onPlanFrom = if (has(RouteRowActions.Action.PLAN_FROM)) ({ onPlanFrom(t) }) else null,
                        // a recorded tour's Planen / Los geht's load the plan it followed (owner 2026-11-07)
                        onPlan = if (has(RouteRowActions.Action.PLAN)) ({ if (t.kind == TourKind.RECORDED) onPlanFollowed(t) else onOpen(t) }) else null,
                        onGo = if (has(RouteRowActions.Action.GO)) ({ if (t.kind == TourKind.RECORDED) onGoFollowed(t) else onGo(t) }) else null)
                }
                // a route saved / synced / read fresh lands on top: a LazyColumn would keep the old first row at the top
                // and the new one above the screen (E2E 2026-11-07) – at the top, the list shows it; scrolled, it stays
                val firstKey: Any? = if (hasRecent) "h-recent" else saved.firstOrNull()?.id
                val scrollTop = remember(firstKey) {
                    val before = listFirstKey[0]
                    listFirstKey[0] = firstKey
                    // the position of the last layout, before this list is measured
                    androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
                        com.onpath.app.ui.routes.ListTop.scrollTo(before, firstKey,
                            com.onpath.app.ui.routes.ListTop.atTop(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset))
                    }
                }
                androidx.compose.runtime.LaunchedEffect(firstKey) { scrollTop?.let { listState.scrollToItem(it) } }
                LazyColumn(state = listState, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (hasRecent) {
                        item(key = "h-recent") { SectionHeader(str(Res.string.routes_section_recent)) }
                        liveTour?.let { lt -> item(key = "live") {
                            // the plan on the map: a tap goes back to it; Speichern while it can be saved; a saved route
                            // as it is carries "Gespeichert" beside "In Bearbeitung" (owner 2026-11-12)
                            val row = RouteRowActions.of(RouteRowActions.Section.LIVE, lt.kind,
                                canSaveLive = live?.canSave == true && live.unsaved)
                            TourCard(lt, tag = RouteTag.LIVE, tag2 = if (recent.saved) RouteTag.SAVED else null, row = row,
                                onOpen = onLiveOpen, onRename = {}, onExport = {}, onDelete = null,
                                menu = false, footer = if (RouteRowActions.Action.SAVE in row.inline) ({ DraftActions(onLiveSave, null) }) else null,
                                line = line(lt))
                        } }
                        recentTour?.let { t -> item(key = "recent-${t.id}") {
                            if (t.draft) {
                                // Zuletzt (owner 2026-11-07 09:40): Speichern · Löschen on the row for a fast clean-up – not again in ⋮
                                // and "Planen" first (owner 2026-11-07: "es muss klar sein, dass man diese Routen bearbeiten kann"),
                                // "Los geht's" in ⋮
                                val row = RouteRowActions.of(RouteRowActions.Section.DRAFT, t.kind, waypoints = t.waypoints.size, touring = touring)
                                TourCard(t, tag = null, row = row, onOpen = { overview = t }, onRename = { rename = t }, onExport = { onExport(t) },
                                    onDelete = null, footer = { DraftActions({ onSaveDraft(t) }, { onDeleteDraft(t) },
                                        onPlan = if (RouteRowActions.Action.PLAN in row.inline) ({ onOpen(t) }) else null) },
                                    line = line(t), onGo = if (RouteRowActions.Action.GO in row.menu) ({ onGo(t) }) else null,
                                    thumb = com.onpath.app.ui.routes.rememberThumb(t, thumbs, thumbVersion))
                            } else savedCard(t, RouteTag.SAVED, false)
                        } }
                        if (saved.isNotEmpty()) item(key = "h-saved") { SectionHeader(str(Res.string.routes_section_saved)) }
                    }
                    items(saved, key = { it.id }) { t -> savedCard(t, RouteTag.of(t, marks), t.id == justSavedId) }
                }
            }
            }
        }
    }
    // drawn in place over the list, not in a dialog window (a full-screen Dialog got no navigation-bar inset: its
    // buttons lay under the gesture bar on the emulator 2026-11-07, as EndTourDialog's once did); Back closes it
    overview?.let { t ->
        androidx.activity.compose.BackHandler { overview = null }
        val segs = rememberRouteLine(t, loadLine)
        // a recorded tour that followed a plan (owner 2026-11-07): the same foot, both load the plan it followed, and that
        // plan's line drawn thin under the track
        val followed = t.kind == TourKind.RECORDED && t.hasPlan && !touring && t.id != recordingId
        var planLine by remember(t.id) { mutableStateOf<List<com.onpath.app.routing.GeoPoint>?>(null) }
        if (t.kind == TourKind.RECORDED && t.hasPlan) androidx.compose.runtime.LaunchedEffect(t.id) { planLine = loadPlanLine(t) }
        // a planned route (no tour): Abbrechen · Los geht's · Planen (owner 2026-11-07)
        RouteOverview(t, segs, touring, snapshot,
            onPrimary = { overview = null; if (followed) onPlanFollowed(t) else onOpen(t) }, onCancel = { overview = null },
            onGo = when {
                followed -> ({ overview = null; onGoFollowed(t) })
                t.kind == TourKind.PLANNED && !touring && t.waypoints.size >= 2 -> ({ overview = null; onGo(t) })
                else -> null
            },
            planLine = planLine)
    }
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

/**
 * Pull to refresh around Meine Touren's list (owner 2026-11-13): with [onPull] the Material pull gesture and its
 * spinner while [pulling]; without (not paired, the Done tab) the list as it is.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PullToSync(onPull: (() -> Unit)?, pulling: Boolean, content: @Composable () -> Unit) {
    if (onPull == null) { Box(Modifier.fillMaxSize()) { content() }; return }
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing = pulling, onRefresh = onPull,
        modifier = Modifier.fillMaxSize()) { content() }
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
        shape = OnPathShapes.Pill,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search))
}



/**
 * A My routes row – L1 (owner 2026-11-13, after the map sketches): a square map tile left (the route on the offline map,
 * [thumb]; without a picture the line on paper, without a line the kind's sign), then the name (+ tags), the activity
 * ("Wandern · T2" with its sign, the app's own labels) and the facts; at the right ⋮ over the star ([onFavourite]; filled
 * gold = favourite). A tap on the row opens the large overview. The date line went (the tab says the kind, the sort chips
 * how new); no › any more – the tile shows what a tap opens.
 */
@Composable
private fun TourCard(
    t: Tour, tag: RouteTag?, row: RouteRowActions.Row, onOpen: (() -> Unit)?, onRename: () -> Unit, onExport: () -> Unit,
    /** A second tag after [tag] (the plan on the map that is a saved route as it is: "Gespeichert"). */
    tag2: RouteTag? = null,
    /** ⋮ → Löschen; null = not in ⋮ (a draft deletes on its row, the tour being recorded never). */
    onDelete: (() -> Unit)?,
    onEditRides: (() -> Unit)? = null, onCopy: (() -> Unit)? = null, onShare: (() -> Unit)? = null,
    onPlanFrom: (() -> Unit)? = null,
    /** Under the card's text, over its full width (a draft's Speichern · Löschen). */
    footer: (@Composable () -> Unit)? = null,
    /** "✓ Gerade gespeichert" under the numbers. */
    justSaved: Boolean = false,
    /** The ⋮ menu (rename, export …); off for the plan on the map – it is no stored route. */
    menu: Boolean = true,
    /** Its line for the tile when there is no map picture (owner 2026-11-07); null / too short = the kind's sign. */
    line: List<List<com.onpath.app.routing.GeoPoint>>? = null,
    /** The tour being recorded right now: "Wird gerade aufgezeichnet" under the numbers. */
    recording: Boolean = false,
    /** ⋮ → "Planen" (blue) / "Los geht's" (green) on top (owner 2026-11-07); null = not in ⋮. */
    onPlan: (() -> Unit)? = null, onGo: (() -> Unit)? = null,
    /** The tile's map picture ([com.onpath.app.ui.routes.RouteThumbSource]); null = the line on paper. */
    thumb: androidx.compose.ui.graphics.ImageBitmap? = null,
    /** The star under ⋮ (owner 2026-11-13); null = none (Zuletzt's draft, the plan on the map). */
    onFavourite: ((Boolean) -> Unit)? = null,
) {
    val (icon, color) = when (t.kind) {
        TourKind.PLANNED -> Icons.Filled.Route to OnPathColors.Route
        TourKind.RECORDED -> Icons.Filled.FiberManualRecord to OnPathColors.Rec
        TourKind.IMPORTED -> Icons.Filled.FileOpen to OnPathColors.Gpx
    }
    // a plan under Zuletzt: the route sign pale – only a draft (owner 2026-11-06)
    val pale = DraftLook.of(t.draft)
    // SKETCH round trip (owner 2026-11-13): B says it to TalkBack too
    val rt = com.onpath.app.ui.routes.RoundTripSketch
    val openLabel = str(Res.string.routes_open_row, t.name) +
        (if (rt.isLoop(t.id) && rt.mode == com.onpath.app.ui.routes.RoundTripSketch.Mode.B) ", " + str(Res.string.plan_round_trip) else "")
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(onClick = { onOpen?.invoke() }, enabled = onOpen != null, shape = OnPathShapes.Card, color = OnPathColors.CardPaper,
        modifier = Modifier.fillMaxWidth().warmShadow(OnPathShadow.LOW, OnPathShapes.Card)
            .semantics { onClick(label = openLabel) { onOpen?.invoke(); onOpen != null } }) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                // the map tile: decorative (the row says what it opens); smaller at a large font – the words need the room
                val tile = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= LARGE_FONT_TILE_SCALE) MAP_TILE_LARGE_FONT else MAP_TILE
                Box(Modifier.size(tile).clip(OnPathShapes.Small).background(OnPathColors.PaperDim), contentAlignment = Alignment.Center) {
                    if (thumb != null) androidx.compose.foundation.Image(thumb, contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize()
                            .then(if (t.draft) Modifier.alpha(0.6f) else Modifier))
                    else RoutePreviewTile(line, RouteShape.tint(t.kind), pale = t.draft, tile = tile - 8.dp, placeholder = {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(color.copy(alpha = pale.circle)), contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = t.kind.label, tint = color.copy(alpha = pale.icon))
                        }
                    })
                    // SKETCH round trip B: ↻ on the tile's corner – a paper ring round an ink-blue disc, as the ⤢ badge was
                    if (rt.isLoop(t.id) && rt.mode == com.onpath.app.ui.routes.RoundTripSketch.Mode.B)
                        Box(Modifier.align(Alignment.BottomEnd).padding(3.dp).size(ENLARGE_BADGE).clip(CircleShape)
                            .background(OnPathColors.Paper).padding(2.dp).clip(CircleShape).background(OnPathColors.ActionEdit),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Loop, null, tint = OnPathColors.Paper, modifier = Modifier.size(ENLARGE_ICON))
                        }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    // from the browser and not opened yet (owner 2026-10-31): a "New" / "Changed" tag after the name, until opened
                    RouteTitle(t.name, tag, tag2)
                    ActivityLine(t)
                    Text(instrument(RouteRowActions.facts(Format.distance(t.distanceM), Format.meters(t.ascentM), Format.duration(t.durationS)),
                        MaterialTheme.typography.bodySmall, muted), style = MaterialTheme.typography.bodySmall)
                    if (recording) Text(str(Res.string.routes_recording_now), style = MaterialTheme.typography.bodySmall, color = OnPathColors.Rec)
                    if (justSaved) Text(str(Res.string.routes_just_saved), style = MaterialTheme.typography.labelLarge,
                        color = OnPathColors.ActionEdit, modifier = Modifier.padding(top = 2.dp))
                }
                // ⋮ over the star (the approved L1 picture); each 48 dp
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (menu) TourItemMenu(t, onRename, onExport, onDelete, onEditRides, onCopy, onShare, onPlanFrom, onPlan, onGo)
                    else Spacer(Modifier.width(8.dp))
                    onFavourite?.let { com.onpath.app.ui.routes.FavouriteStar(t.favourite, t.name, it) }
                }
            }
            // the card's whole width from its tile's edge (Planen · Speichern · Löschen need it in German; at a large font they stack)
            footer?.let { Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) { it() } }
        }
    }
}

/** The L1 row's map tile (owner 2026-11-13: 84 dp, the sketch's square; [com.onpath.app.map.ThumbRules.TILE_DP]). */
private val MAP_TILE = com.onpath.app.map.ThumbRules.TILE_DP.dp
/** From this font scale the tile is [MAP_TILE_LARGE_FONT] (the picture scaled down whole). */
private const val LARGE_FONT_TILE_SCALE = 1.5f
private val MAP_TILE_LARGE_FONT = 64.dp

/** "Wandern · T2" / "Fahrrad" / "Gravel" … with the activity's sign (the app's chip labels); nothing when unknown. */
@Composable
private fun ActivityLine(t: Tour) {
    val a = t.activity?.let { n -> Activity.entries.firstOrNull { it.name == n } } ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = if (a == Activity.HIKE && t.sac in 1..5) str(Res.string.activity_chip_with_level, a.chip, t.sac) else a.chip
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(a.icon, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        // SKETCH round trip A: "· ↻ Rundtour" at the end of the line
        if (com.onpath.app.ui.routes.RoundTripSketch.isLoop(t.id) && com.onpath.app.ui.routes.RoundTripSketch.mode == com.onpath.app.ui.routes.RoundTripSketch.Mode.A) {
            Text(" · ", style = MaterialTheme.typography.bodySmall, color = muted)
            Icon(Icons.Outlined.Loop, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(3.dp))
            Text(str(Res.string.plan_round_trip), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
        }
    }
}

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
private fun DraftActions(onSave: () -> Unit, onDelete: (() -> Unit)?, onPlan: (() -> Unit)? = null) {
    if (onPlan != null) { DraftActionsWithPlan(onPlan, onSave, onDelete); return }
    val save = @Composable { m: Modifier ->
        androidx.compose.material3.FilledTonalButton(onSave, modifier = m.heightIn(min = 48.dp),
            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = OnPathColors.EditTonal, contentColor = OnPathColors.OnEditTonal)) {
            Icon(Icons.Outlined.Save, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(str(Res.string.common_save), maxLines = 1) }
    }
    val delete = @Composable { m: Modifier ->
        if (onDelete != null) TextButton(onDelete, modifier = m.heightIn(min = 48.dp),
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

/**
 * A Zuletzt row's buttons with "Planen" (owner 2026-11-07: "es muss klar sein, dass man diese Routen bearbeiten kann"):
 * "Planen" tonal blue first (the one filled button), then Speichern (blue text) and Löschen (red text); one under the other,
 * full width, at a large font (M3: never a word broken into letters).
 */
@Composable
private fun DraftActionsWithPlan(onPlan: () -> Unit, onSave: () -> Unit, onDelete: (() -> Unit)?) {
    val plan = @Composable { m: Modifier ->
        androidx.compose.material3.FilledTonalButton(onPlan, modifier = m.heightIn(min = 48.dp),
            contentPadding = PaddingValues(start = 14.dp, end = 18.dp),
            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = OnPathColors.EditTonal, contentColor = OnPathColors.OnEditTonal)) {
            Icon(Icons.Filled.Route, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(str(Res.string.routes_overview_plan), maxLines = 1) }
    }
    val save = @Composable { m: Modifier ->
        TextButton(onSave, modifier = m.heightIn(min = 48.dp), colors = ActionButtons.editText(), contentPadding = PaddingValues(horizontal = 10.dp)) {
            Icon(Icons.Outlined.Save, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(str(Res.string.common_save), maxLines = 1) }
    }
    val delete = @Composable { m: Modifier ->
        if (onDelete != null) TextButton(onDelete, modifier = m.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 10.dp),
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Icon(Icons.Outlined.DeleteOutline, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(str(Res.string.common_delete), maxLines = 1) }
    }
    if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= DraftLook.STACK_FONT_SCALE)
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            plan(Modifier.fillMaxWidth()); save(Modifier.fillMaxWidth()); delete(Modifier.fillMaxWidth())
        }
    else Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        plan(Modifier); save(Modifier); delete(Modifier)
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
 * ⋮ per item in My routes: Planen + Los geht's (planned, not during a tour), Rename, Save as copy (planned), Export GPX,
 * Share plan (planned), Delete (asks first; not for the tour being recorded).
 */
@Composable
private fun TourItemMenu(t: Tour, onRename: () -> Unit, onExport: () -> Unit, onDelete: (() -> Unit)?, onEditRides: (() -> Unit)? = null,
                         onCopy: (() -> Unit)? = null, onShare: (() -> Unit)? = null, onPlanFrom: (() -> Unit)? = null,
                         onPlan: (() -> Unit)? = null, onGo: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, Modifier.size(48.dp)) {
            Icon(Icons.Filled.MoreVert, str(Res.string.routes_options_desc, t.name), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TourMenuItems(t, onRename, onExport, onDelete, onEditRides, onCopy, onShare, onPlanFrom, onPlan, onGo) { open = false }
        }
    }
}

/** The items of a My routes ⋮ (also drawn on their own by the screenshot tests). */
@Composable
internal fun TourMenuItems(t: Tour, onRename: () -> Unit, onExport: () -> Unit, onDelete: (() -> Unit)?, onEditRides: (() -> Unit)?,
                           onCopy: (() -> Unit)?, onShare: (() -> Unit)?, onPlanFrom: (() -> Unit)? = null,
                           onPlan: (() -> Unit)? = null, onGo: (() -> Unit)? = null, close: () -> Unit) {
    fun on(a: () -> Unit): () -> Unit = { close(); a() }
    // edit or follow (owner 2026-11-07): the plan sheet's words and colours – "Planen" blue, "Los geht's" green – on top
    if (onPlan != null) DropdownMenuItem(text = { Text(str(Res.string.routes_overview_plan), color = OnPathColors.ActionEdit, fontWeight = FontWeight.SemiBold) },
        leadingIcon = { Icon(Icons.Filled.Route, null, tint = OnPathColors.ActionEdit) },
        onClick = on(onPlan), modifier = Modifier.heightIn(min = 48.dp))
    if (onGo != null) DropdownMenuItem(text = { Text(str(Res.string.plan_bar_go), color = OnPathColors.ActionGo, fontWeight = FontWeight.SemiBold) },
        leadingIcon = { Icon(Icons.Filled.PlayArrow, null, tint = OnPathColors.ActionGo) },
        onClick = on(onGo), modifier = Modifier.heightIn(min = 48.dp))
    if (onPlan != null || onGo != null) androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
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
