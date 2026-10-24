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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.data.AppSettings
import com.onpath.app.data.DataFiles
import com.onpath.app.poi.PoiGroup
import com.onpath.app.poi.PoiKinds

val PoiGroup.label: String
    get() = when (this) {
        PoiGroup.FOOD -> "Food & drink"
        PoiGroup.HUT -> "Huts & camping"
        PoiGroup.WATER -> "Water"
        PoiGroup.SIGHT -> "Sights & views"
        PoiGroup.TRANSPORT -> "Transport"
        PoiGroup.FACILITY -> "Facilities"
        PoiGroup.OTHER -> "Other"
    }

/**
 * Settings (☰ → Settings), owner 2026-10-22: a list of topics (icon, title, one line of the current values –
 * [SettingsSummary]); a topic opens its own page ([page]; back returns to the list). Every change is applied and saved
 * at once. [page] is hoisted (AppViewModel.settingsPage) so other places can open a topic directly (map credits → About).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    checks: List<DataFiles.Check>,
    dataDir: String,
    version: String,
    onBack: () -> Unit,
    /** The open topic; null = the list of topics. */
    page: SettingsPage? = null,
    onPage: (SettingsPage?) -> Unit = {},
    initiallyExpanded: Set<PoiGroup> = emptySet(),
    /** Available map themes (StyleLoader.themes); the chooser is shown when there is more than one. */
    themes: List<String> = listOf("classic"),
    onImportData: () -> Unit = {},
    /** Opens Android's app settings (battery usage). */
    onAppSettings: () -> Unit = {},
    /** Diagnostics → "Download routing data" (normally it comes with the map by itself – owner 2026-10-22). */
    onDownloadRouting: () -> Unit = {},
    /** Diagnostics → Developer tools → the planned route's time estimate with its formulas; null = no route (greyed). */
    onTimeFormulas: (() -> Unit)? = null,
    dataBusy: String? = null,
    /** null = updates handled elsewhere (installed from Google Play) – topic hidden. */
    update: AppViewModel.UpdateState? = null,
    onCheckUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
    /** Installed map package (package.json) and files from another package – Diagnostics → Developer tools. */
    mapPackage: DataFiles.PackageCheck? = null,
    /** Settings → Diagnostics shown (problem report button + problem list); false = topic hidden. */
    diagnostics: Boolean = false,
    /** The problem report is being created: a spinner on its button (review 2026-10-24 C9). */
    diagnosticsBusy: Boolean = false,
    onExportDiagnostics: () -> Unit = {},
    /** Problem log (debug/Problems), most recent first. */
    problems: List<com.onpath.app.debug.Problems.Entry> = emptyList(),
    onClearProblems: () -> Unit = {},
    /** Diagnostics → Developer tools → "Map areas (debug)"; null = hidden. */
    mapCells: MapCellsDebug? = null,
    /** Settings → "Offline maps" (downloaded map areas); null = topic hidden. */
    offlineMaps: OfflineMapsUi? = null,
    offlineMapsCb: OfflineMapsCallbacks = OfflineMapsCallbacks(),
    /** Settings → "Backup" (tours and routes as GPX in Documents/OnPath); null = topic hidden. */
    backup: BackupUi? = null,
    backupCb: BackupCallbacks = BackupCallbacks(),
    /** Current activity: "Your pace" explains the cycling levels for this bike. */
    activity: com.onpath.app.routing.Activity = com.onpath.app.routing.Activity.HIKE,
    /** Diagnostics → "Developer tools" open at first (screenshots). */
    devToolsOpen: Boolean = false,
    /** Developer tools: the map server line (MapServer.describe; fixed in screenshots). */
    mapServer: String = com.onpath.app.data.MapServer.describe(),
    now: Long = System.currentTimeMillis(),
) {
    val topics = buildList {
        add(SettingsPage.PACE); add(SettingsPage.POI); add(SettingsPage.MAP); add(SettingsPage.NAVIGATION)
        if (offlineMaps != null) add(SettingsPage.OFFLINE_MAPS)
        if (backup != null) add(SettingsPage.BACKUP)
        add(SettingsPage.PRIVACY)
        if (update != null) add(SettingsPage.UPDATES)
        if (diagnostics) add(SettingsPage.DIAGNOSTICS)
        add(SettingsPage.ABOUT)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (page != null) onPage(null) else onBack() }, Modifier.size(56.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
                Text(page?.title ?: "Settings", style = MaterialTheme.typography.titleLarge)
            }
            if (page == null) {
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
                    item {
                        Surface(shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 1.dp) {
                            Column {
                                topics.forEachIndexed { i, t ->
                                    if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp))
                                    val s = when (t) {
                                        SettingsPage.PACE -> SettingsSummary.pace(settings, activity)
                                        SettingsPage.POI -> SettingsSummary.poi(settings.hiddenPoiKinds)
                                        SettingsPage.MAP -> SettingsSummary.map(settings)
                                        SettingsPage.NAVIGATION -> SettingsSummary.navigation(settings)
                                        SettingsPage.OFFLINE_MAPS -> SettingsSummary.offlineMaps(offlineMaps!!)
                                        SettingsPage.BACKUP -> SettingsSummary.backup(backup!!, now)
                                        SettingsPage.UPDATES -> SettingsSummary.updates(update!!, version)
                                        SettingsPage.DIAGNOSTICS -> SettingsSummary.diagnostics(problems)
                                        SettingsPage.PRIVACY -> SettingsSummary.privacy
                                        SettingsPage.ABOUT -> SettingsSummary.about
                                    }
                                    TopicRow(t, s) { onPage(t) }
                                }
                            }
                        }
                    }
                }
            } else CompositionLocalProvider(LocalSectionTitle provides false) {
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (page) {
                        SettingsPage.PACE -> item { PaceSection(settings, onChange, activity) } // PaceSettings.kt
                        SettingsPage.POI -> item { PoiSection(settings, onChange, initiallyExpanded) }
                        SettingsPage.MAP -> item { MapSection(settings, onChange, themes) }
                        SettingsPage.NAVIGATION -> item { NavigationSection(settings, onChange, onAppSettings) }
                        SettingsPage.OFFLINE_MAPS -> if (offlineMaps != null) item { OfflineMapsSection(offlineMaps, offlineMapsCb) }
                        SettingsPage.BACKUP -> if (backup != null) item { BackupSection(backup, backupCb) }
                        SettingsPage.UPDATES -> if (update != null) item {
                            Section("Updates") { UpdateSectionContent(update, version, onCheckUpdate, onInstallUpdate) }
                        }
                        SettingsPage.DIAGNOSTICS -> if (diagnostics) {
                            item { DiagnosticsSection(diagnosticsBusy, onExportDiagnostics, problems, onClearProblems) }
                            item {
                                DeveloperTools(devToolsOpen, mapServer, mapCells, dataBusy, onDownloadRouting, onImportData, checks, mapPackage, dataDir,
                                    onTimeFormulas)
                            }
                        }
                        SettingsPage.PRIVACY -> item { PrivacySection() }
                        SettingsPage.ABOUT -> item { AboutSection(version) }
                    }
                }
            }
        }
    }
}

private val SettingsPage.icon: ImageVector
    get() = when (this) {
        SettingsPage.PACE -> Icons.Outlined.Timer
        SettingsPage.POI -> Icons.Outlined.Place
        SettingsPage.MAP -> Icons.Outlined.Map
        SettingsPage.NAVIGATION -> Icons.Outlined.Navigation
        SettingsPage.OFFLINE_MAPS -> Icons.Outlined.CloudDownload
        SettingsPage.BACKUP -> Icons.Outlined.SaveAlt
        SettingsPage.PRIVACY -> Icons.Outlined.Lock
        SettingsPage.UPDATES -> Icons.Outlined.SystemUpdate
        SettingsPage.DIAGNOSTICS -> Icons.Outlined.BugReport
        SettingsPage.ABOUT -> Icons.Outlined.Info
    }

/** One topic of the list: icon, title, summary (red when it needs attention, accent for news), chevron. */
@Composable
private fun TopicRow(t: SettingsPage, s: Summary, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(t.icon, null, tint = OnPathColors.Green, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, fontSize = 16.sp)
            Text(s.text, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                fontWeight = if (s.attention == Summary.Attention.NONE) FontWeight.Normal else FontWeight.SemiBold,
                color = when (s.attention) {
                    Summary.Attention.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                    Summary.Attention.ACCENT -> OnPathColors.Route
                    Summary.Attention.PROBLEM -> OnPathColors.Bad
                })
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PoiSection(settings: AppSettings, onChange: (AppSettings) -> Unit, initiallyExpanded: Set<PoiGroup>) {
    val expanded = remember { mutableStateMapOf<PoiGroup, Boolean>().apply { initiallyExpanded.forEach { put(it, true) } } }
    val hidden = settings.hiddenPoiKinds
    fun setHidden(h: Set<String>) = onChange(settings.copy(hiddenPoiKinds = h))
    Section("Points of interest") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { setHidden(emptySet()) }) { Text("Show all") }
            TextButton(onClick = { setHidden(PoiKinds.all.map { it.key }.toSet()) }) { Text("Hide all") }
            TextButton(onClick = { setHidden(PoiKinds.defaultHidden) }) { Text("Reset") }
        }
        PoiGroup.entries.forEach { g ->
            val kinds = PoiKinds.inGroup(g)
            if (kinds.isEmpty()) return@forEach
            val on = kinds.count { it.key !in hidden }
            val state = when (on) { 0 -> ToggleableState.Off; kinds.size -> ToggleableState.On; else -> ToggleableState.Indeterminate }
            val open = expanded[g] == true
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { expanded[g] = !open }.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TriStateCheckbox(state = state, onClick = {
                    val keys = kinds.map { it.key }
                    setHidden(if (state == ToggleableState.On) hidden + keys else hidden - keys.toSet())
                })
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(g.argb)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.label, fontSize = 16.sp)
                    Text("$on of ${kinds.size} shown", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Collapse" else "Expand")
                Spacer(Modifier.width(8.dp))
            }
            if (open) kinds.forEach { k ->
                val shown = k.key !in hidden
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = shown, role = Role.Checkbox) { setHidden(if (shown) hidden + k.key else hidden - k.key) }
                    .padding(start = 40.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = shown, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Text(k.en, fontSize = 15.sp)
                }
            }
        }
    }
}

/** Map: contours, hill shading, map direction (Plan and Navigate) and – for planning – the vibration on hold. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MapSection(settings: AppSettings, onChange: (AppSettings) -> Unit, themes: List<String>) {
    Section("Map") {
        if (themes.size > 1) {
            Text("Map style", fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            androidx.compose.foundation.layout.FlowRow(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                themes.forEach { t ->
                    FilterChip(selected = settings.mapTheme == t, onClick = { onChange(settings.copy(mapTheme = t)) },
                        label = { Text(com.onpath.app.map.StyleLoader.label(t)) },
                        modifier = Modifier.heightIn(min = 44.dp))
                }
            }
        }
        SwitchRow("Contour lines", null, settings.contours) { onChange(settings.copy(contours = it)) }
        SwitchRow("Hill shading", null, settings.hillshade) { onChange(settings.copy(hillshade = it)) }
        Text("Map direction (Plan and Navigate)", fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true to "Direction of travel", false to "North up").forEach { (up, label) ->
                FilterChip(selected = settings.navHeadingUp == up, onClick = { onChange(settings.copy(navHeadingUp = up)) },
                    label = { Text(label) }, modifier = Modifier.heightIn(min = 44.dp))
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        Text("Planning", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = OnPathColors.Green,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp))
        SwitchRow("Vibrate when planning", "Short buzz when you hold the map, two buzzes on a route point",
            settings.planVibrate) { onChange(settings.copy(planVibrate = it)) }
    }
}

/** Navigation, in the owner's order: warning → distance → vibrations → screen → battery / location hints. */
@Composable
private fun NavigationSection(settings: AppSettings, onChange: (AppSettings) -> Unit, onAppSettings: () -> Unit) {
    Section("Navigation") {
        SwitchRow("Off-route warning", "Vibration and notification when you leave the followed route",
            settings.offRouteWarning) { onChange(settings.copy(offRouteWarning = it)) }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Warn after", fontSize = 14.sp)
            AppSettings.OFF_ROUTE_CHOICES.forEach { m ->
                FilterChip(selected = settings.offRouteM == m, enabled = settings.offRouteWarning,
                    onClick = { onChange(settings.copy(offRouteM = m)) }, label = { Text("$m m") },
                    modifier = Modifier.heightIn(min = 44.dp))
            }
        }
        SwitchRow("Vibrate when off route", "Felt in the pocket, also with the screen off",
            settings.offRouteVibrate && settings.offRouteWarning, enabled = settings.offRouteWarning) {
            onChange(settings.copy(offRouteVibrate = it))
        }
        SwitchRow("Vibrate at junctions", "Before a turn of the followed route: 2× left, 3× right",
            settings.junctionVibrate) { onChange(settings.copy(junctionVibrate = it)) }
        SwitchRow("Keep screen on while navigating", "Only while following a route; off in your pocket", settings.keepScreenOn) {
            onChange(settings.copy(keepScreenOn = it))
        }
        // Was a pop-up on the first recording (owner: remove it) – now a quiet hint here.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Recording with the screen off works best with battery usage set to “Unrestricted”.",
                fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onAppSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("App settings") }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val bg = androidx.core.content.ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (bg) "A tour continues by itself after a restart or update (location: Allow all the time)."
                    else "After a phone restart or app update, tap Continue to go on recording. To make this automatic, set location to “Allow all the time”.",
                    fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                if (!bg) TextButton(onClick = onAppSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("App settings") }
            }
        }
    }
}

/** Diagnostics: the problem report button (no switch – review 2026-10-24 B23), then the problem list. */
@Composable
private fun DiagnosticsSection(busy: Boolean, onExport: () -> Unit,
                               problems: List<com.onpath.app.debug.Problems.Entry>, onClearProblems: () -> Unit) {
    Section("Diagnostics") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { if (!busy) onExport() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (busy) BusyContent(Busy.DIAGNOSTICS.text) else Text("Send problem report")
            }
            Text("Leave the map at the problem spot first.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ProblemList(problems, onClearProblems)
        }
    }
}

/**
 * Diagnostics → "Developer tools" (closed at first): map server, routing data by hand, the map areas list, the map
 * data import and the data folder / file checks / map package (moved from About and "Map data", owner 2026-10-22).
 */
@Composable
private fun DeveloperTools(
    initiallyOpen: Boolean, mapServer: String, mapCells: MapCellsDebug?, dataBusy: String?, onDownloadRouting: () -> Unit, onImportData: () -> Unit,
    checks: List<DataFiles.Check>, mapPackage: DataFiles.PackageCheck?, dataDir: String,
    onTimeFormulas: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { open = !open }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Developer tools", style = MaterialTheme.typography.titleSmall, color = OnPathColors.Green, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Close" else "Open")
            }
            if (open) Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Map server: $mapServer", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // by hand: the 1° routing parts at the map centre (they normally load with the map)
                OutlinedButton(onClick = onDownloadRouting, enabled = dataBusy == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Download routing data (map centre)") }
                if (dataBusy != null) Text(dataBusy, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                mapCells?.let { MapCellsDebugList(it) }
                // the formulas behind the plan sheet's time (moved here from ⋯ → Time details, owner 2026-10-23)
                OutlinedButton(onClick = { onTimeFormulas?.invoke() }, enabled = onTimeFormulas != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Time estimate of the route (formulas)") }
                if (onTimeFormulas == null) Text("Plan a route first.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Map data", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Import a map package or a map style file (.zip, .pmtiles, .geojson, .rd5, onpath-*.json) from this phone.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // routing data is part of the map (owner 2026-10-22): no own button here, see above
                DataActions(routingMissing = false, busy = dataBusy, onImport = onImportData, onDownloadRouting = {})
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Data folder", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(dataDir, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                checks.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (c.present) Icons.Filled.CheckCircle else Icons.Filled.RemoveCircleOutline, null,
                            tint = if (c.present) OnPathColors.Good else OnPathColors.Bad, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("${c.name} – ${c.hint}" + if (c.present) "" else " (missing)", fontSize = 13.sp)
                    }
                }
                if (mapPackage != null) {
                    Text("Map package: ${mapPackage.region} · ${mapPackage.area}" +
                        (mapPackage.osmDate?.let { " · OSM ${it.take(10)}" } ?: ""), fontSize = 13.sp)
                    if (!mapPackage.ok) Text("From another package (map has gaps there): ${mapPackage.mismatched.joinToString()}. " +
                        "Import every part of the map package again.", fontSize = 13.sp, color = OnPathColors.Bad)
                }
            }
        }
    }
}

/** Data of the "Map areas (debug)" block: installed areas, areas in progress, two actions (end-to-end test only). */
data class MapCellsDebug(
    /** id, version, bytes */
    val installed: List<Triple<String, String, Long>>,
    /** id → state line (CellState.label) for areas not simply installed */
    val active: List<Pair<String, String>>,
    /** "store g000007" or "no areas – data folder" */
    val store: String,
    val onRequestAtCentre: () -> Unit,
    val onDelete: (String) -> Unit,
)

@Composable
private fun MapCellsDebugList(d: MapCellsDebug) {
    Text("Map areas (debug)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    Text("Map files: ${d.store}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (d.installed.isEmpty()) Text("No areas installed.", fontSize = 12.sp)
    d.installed.forEach { (id, version, bytes) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$id · $version · ${"%.1f".format(java.util.Locale.ROOT, bytes / 1_048_576.0)} MB", fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            TextButton(onClick = { d.onDelete(id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
        }
    }
    if (d.installed.isNotEmpty()) Text("Total ${"%.1f".format(java.util.Locale.ROOT, d.installed.sumOf { it.third } / 1_048_576.0)} MB",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    d.active.forEach { (id, s) -> Text("$id: $s", fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
    OutlinedButton(onClick = d.onRequestAtCentre, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("Request map area at map centre")
    }
}

/** Problems the app noticed (errors it recovered from, map engine warnings, crashes) – nothing is hidden. */
@Composable
private fun ProblemList(problems: List<com.onpath.app.debug.Problems.Entry>, onClear: () -> Unit) {
    val serious = problems.count { it.level >= com.onpath.app.debug.Problems.Level.ERROR }
    Text(if (problems.isEmpty()) "No problems noticed." else "Problems noticed: ${problems.size}" +
        (if (serious > 0) " ($serious serious)" else ""), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    val fmt = remember { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()) }
    problems.take(15).forEach { p ->
        val color = when (p.level) {
            com.onpath.app.debug.Problems.Level.CRASH, com.onpath.app.debug.Problems.Level.ERROR -> OnPathColors.Bad
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text("${fmt.format(java.util.Date(p.last))} · ${p.level.name.lowercase()} · ${p.area}: ${p.message}" +
            (if (p.count > 1) " (× ${p.count})" else ""), fontSize = 12.sp, color = color)
    }
    if (problems.size > 15) Text("… and ${problems.size - 15} more in the export.", fontSize = 12.sp)
    if (problems.isNotEmpty()) TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear problem list") }
}

/**
 * Privacy (owner 2026-10-22): what leaves the phone, to whom, when – checked against the code's network calls
 * (CellApi, Rd5PartStore / SegmentDownloader, PhotonProvider, AppUpdater, ShortLinkResolver, link-outs). Keep in step.
 */
@Composable
private fun PrivacySection() {
    Section("Privacy") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Nothing is tracked. No account, no analytics, no ads.", fontWeight = FontWeight.SemiBold)
            Text("Your tracks and routes never leave the phone – only to your own backup folder.", fontSize = 14.sp)
            HorizontalDivider()
            Text("What leaves your phone", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            PRIVACY_FLOWS.forEach { f ->
                Column {
                    Text(f.what, fontSize = 14.sp)
                    Text("→ ${f.to} · ${f.whenText}", fontSize = 12.sp, lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** One thing that leaves the phone: what, to whom, when. */
data class PrivacyFlow(val what: String, val to: String, val whenText: String)

val PRIVACY_FLOWS = listOf(
    PrivacyFlow("Which map area you need (an area of about 30 × 40 km, not your position)", "OnPath map server",
        "when maps load"),
    PrivacyFlow("Which map area you need for route planning (an area of about 80 × 110 km)", "brouter.de", "when maps load"),
    PrivacyFlow("Your search text and a rough position (about 1 km)", "komoot (Germany)", "only for online search"),
    PrivacyFlow("A request for the newest app version (no personal data)", "GitHub",
        "update check, once a day and when you tap Check"),
    PrivacyFlow("A Google Maps link you share into OnPath", "Google", "only to find the place of a short link"),
    PrivacyFlow("The place you tapped", "Google Maps", "only when you tap “Open in Google Maps”"),
    PrivacyFlow("A place's website or Wikipedia page", "that website", "only when you tap the link"),
    PrivacyFlow("The diagnostics file", "whoever you send it to", "only when you share it"),
)

/** About: version, licence and the full credits (the map's "© OpenStreetMap" opens this page). */
@Composable
private fun AboutSection(version: String) {
    Section("About") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("OnPath $version", fontWeight = FontWeight.SemiBold)
            Text("Licence: Apache-2.0", fontSize = 13.sp)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Credits", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            MAP_CREDITS.forEach { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Full credits (Settings → About); the map shows only "© OpenStreetMap" and opens these on a tap. */
val MAP_CREDITS = listOf(
    "Map data © OpenStreetMap contributors (ODbL)",
    "Vector tile schema © OpenMapTiles",
    "Terrain: Terrarium tiles (Mapzen / AWS Terrain Tiles; SRTM, Copernicus and others)",
    "World overview: Natural Earth (public domain)",
    "Routing: BRouter (MIT)",
    "POI icons: Maki and Temaki (CC0)",
    "Map renderer: MapLibre Native (BSD licence)",
)

/** False inside a topic page: the page's top bar already names the topic, the card does not repeat it. */
internal val LocalSectionTitle = compositionLocalOf { true }

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            if (LocalSectionTitle.current) Text(title, style = MaterialTheme.typography.titleSmall, color = OnPathColors.Green,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
