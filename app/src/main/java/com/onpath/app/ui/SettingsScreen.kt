package com.onpath.app.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

/** Settings page (☰ → Settings). Every change is applied and saved at once. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    checks: List<DataFiles.Check>,
    dataDir: String,
    version: String,
    onBack: () -> Unit,
    initiallyExpanded: Set<PoiGroup> = emptySet(),
    /** Available map themes (StyleLoader.themes); the chooser is shown when there is more than one. */
    themes: List<String> = listOf("classic"),
    onImportData: () -> Unit = {},
    /** Opens Android's app settings (battery usage). */
    onAppSettings: () -> Unit = {},
    /** Diagnostics → "Download routing data" (normally it comes with the map by itself – owner 2026-10-22). */
    onDownloadRouting: () -> Unit = {},
    dataBusy: String? = null,
    /** null = updates handled elsewhere (installed from Google Play) – section hidden. */
    update: AppViewModel.UpdateState? = null,
    onCheckUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
    /** Pace learned from earlier tours, one entry per activity with data (time estimate). */
    learnedPace: List<LearnedPace> = emptyList(),
    onResetPace: (com.onpath.app.routing.Activity) -> Unit = {},
    /** Installed map package (package.json) and files from another package – shown in About. */
    mapPackage: DataFiles.PackageCheck? = null,
    /** Diagnostics export (debug): switch + export button; null = section hidden. */
    diagnosticsOn: Boolean? = null,
    onDiagnostics: (Boolean) -> Unit = {},
    onExportDiagnostics: () -> Unit = {},
    /** Problem log (debug/Problems), most recent first. */
    problems: List<com.onpath.app.debug.Problems.Entry> = emptyList(),
    onClearProblems: () -> Unit = {},
    /** Diagnostics → "Map cells (debug)"; null = hidden. */
    mapCells: MapCellsDebug? = null,
    /** Diagnostics → "Reset maps (like a fresh install)"; null = hidden (no map downloads in this build). */
    onResetMaps: (() -> Unit)? = null,
    /** Settings → "Offline maps" (downloaded map areas); null = section hidden. */
    offlineMaps: OfflineMapsUi? = null,
    offlineMapsCb: OfflineMapsCallbacks = OfflineMapsCallbacks(),
    /** Settings → "Backup" (tours and routes as GPX in Documents/OnPath); null = section hidden. */
    backup: BackupUi? = null,
    backupCb: BackupCallbacks = BackupCallbacks(),
) {
    val expanded = remember { mutableStateMapOf<PoiGroup, Boolean>().apply { initiallyExpanded.forEach { put(it, true) } } }
    val hidden = settings.hiddenPoiKinds
    fun setHidden(h: Set<String>) = onChange(settings.copy(hiddenPoiKinds = h))

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }
            LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Section("Default activity") {
                        Text("OnPath starts with this activity. The activity button in the route sheet only changes the current route.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.onpath.app.routing.Activity.entries.forEach { a ->
                                FilterChip(selected = settings.defaultActivity == a, onClick = { onChange(settings.copy(defaultActivity = a)) },
                                    label = { Text(a.label) }, modifier = Modifier.heightIn(min = 44.dp))
                            }
                        }
                    }
                }
                item {
                    Section("Hiking difficulty (SAC scale)") {
                        Text("Hiking routes stay on paths up to this level.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        SacScale.levels.forEach { l ->
                            val sel = settings.defaultSac == l.level
                            // One touch target and one screen-reader item per level (the radio only shows the state).
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(selected = sel, role = Role.RadioButton) { onChange(settings.copy(defaultSac = l.level)) }
                                .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
                                androidx.compose.material3.RadioButton(selected = sel, onClick = null,
                                    modifier = Modifier.padding(12.dp))
                                Column(Modifier.weight(1f).padding(top = 10.dp, end = 8.dp)) {
                                    Text("T${l.level} · ${l.name}", fontSize = 15.sp,
                                        fontWeight = if (sel) androidx.compose.ui.text.font.FontWeight.SemiBold else null)
                                    Text(l.text, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                item { PaceSection(settings, onChange, learnedPace, onResetPace) } // time estimate (PaceSettings.kt)
                item {
                    Section("Points of interest") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { setHidden(emptySet()) }) { Text("Show all") }
                            TextButton(onClick = { setHidden(PoiKinds.all.map { it.key }.toSet()) }) { Text("Hide all") }
                            TextButton(onClick = { setHidden(PoiKinds.defaultHidden) }) { Text("Defaults") }
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
                item {
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
                        SwitchRow("Hillshade (relief)", null, settings.hillshade) { onChange(settings.copy(hillshade = it)) }
                    }
                }
                item {
                    Section("Navigation") {
                        SwitchRow("Off-route warning", "Vibration and notification when you leave the followed route",
                            settings.offRouteWarning) { onChange(settings.copy(offRouteWarning = it)) }
                        SwitchRow("Vibrate when off route", "Felt in the pocket, also with the screen off",
                            settings.offRouteVibrate && settings.offRouteWarning, enabled = settings.offRouteWarning) {
                            onChange(settings.copy(offRouteVibrate = it))
                        }
                        SwitchRow("Vibrate at junctions", "Before a turn of the followed route: 2× left, 3× right",
                            settings.junctionVibrate) { onChange(settings.copy(junctionVibrate = it)) }
                        SwitchRow("Vibrate when planning", "When you hold the map: 1× (map, line, place), 2× on a route point",
                            settings.planVibrate) { onChange(settings.copy(planVibrate = it)) }
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Warn after", fontSize = 14.sp)
                            AppSettings.OFF_ROUTE_CHOICES.forEach { m ->
                                FilterChip(selected = settings.offRouteM == m, enabled = settings.offRouteWarning,
                                    onClick = { onChange(settings.copy(offRouteM = m)) }, label = { Text("$m m") },
                                    modifier = Modifier.heightIn(min = 44.dp))
                            }
                        }
                        SwitchRow("Keep screen on while navigating", "Only while following a route; off in your pocket", settings.keepScreenOn) {
                            onChange(settings.copy(keepScreenOn = it))
                        }
                        // Was a pop-up on the first recording (owner: remove it) – now a quiet hint here.
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Recording with the screen off works best with battery usage set to “Unrestricted”.",
                                fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f))
                            androidx.compose.material3.TextButton(onClick = onAppSettings,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("App settings") }
                        }
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            val bg = androidx.core.content.ContextCompat.checkSelfPermission(ctx,
                                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (bg) "A tour continues by itself after a restart or update (location: Allow all the time)."
                                    else "After a restart or update you tap Continue. To continue by itself: location → “Allow all the time”.",
                                    fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f))
                                if (!bg) androidx.compose.material3.TextButton(onClick = onAppSettings,
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text("App settings") }
                            }
                        }
                        // Navigate orientation (navigation agent): heading-up default, north-up option.
                        Text("Map direction (Plan and Navigate)", fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(true to "Walking direction", false to "North up").forEach { (up, label) ->
                                FilterChip(selected = settings.navHeadingUp == up, onClick = { onChange(settings.copy(navHeadingUp = up)) },
                                    label = { Text(label) }, modifier = Modifier.heightIn(min = 44.dp))
                            }
                        }
                    }
                }
                if (backup != null) item { BackupSection(backup, backupCb) }
                if (offlineMaps != null) item { OfflineMapsSection(offlineMaps, offlineMapsCb) }
                item {
                    Section("Map data") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Import a map package or a map style file (.zip, .pmtiles, .geojson, .rd5, onpath-*.json) from this phone.",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // routing data is part of the map (owner 2026-10-22): no own button here, see Diagnostics
                            DataActions(routingMissing = false, busy = dataBusy, onImport = onImportData, onDownloadRouting = {})
                        }
                    }
                }
                if (update != null) item {
                    Section("Updates") { UpdateSectionContent(update, version, onCheckUpdate, onInstallUpdate) }
                }
                if (diagnosticsOn != null) item {
                    Section("Diagnostics") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Diagnostics export", modifier = Modifier.weight(1f))
                                androidx.compose.material3.Switch(checked = diagnosticsOn, onCheckedChange = onDiagnostics)
                            }
                            Text("Creates a file with the app version, map files, the loaded map style, what the map shows " +
                                "right now and the app log – to send to the developer when something looks wrong. Leave the map at the " +
                                "problem spot before you export. " +
                                "Nothing is sent by itself.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (diagnosticsOn) androidx.compose.material3.OutlinedButton(onClick = onExportDiagnostics,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Export diagnostics") }
                            Text("Map server: ${com.onpath.app.data.MapServer.describe()}", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            mapCells?.let { MapCellsDebugList(it) }
                            // by hand: the 1° routing parts at the map centre (they normally load with the map)
                            androidx.compose.material3.OutlinedButton(onClick = onDownloadRouting, enabled = dataBusy == null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Download routing data (map centre)") }
                            if (dataBusy != null) Text(dataBusy, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            onResetMaps?.let { reset ->
                                var ask by remember { mutableStateOf(false) }
                                androidx.compose.material3.OutlinedButton(onClick = { ask = true },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Reset maps (like a fresh install)") }
                                if (ask) androidx.compose.material3.AlertDialog(onDismissRequest = { ask = false },
                                    title = { Text("Reset maps?") },
                                    text = { Text("All downloaded map areas are deleted. The map for your position and for the points you " +
                                        "set loads again, as on a new install. Tours and routes are kept.") },
                                    confirmButton = { TextButton(onClick = { ask = false; reset() }) { Text("Reset") } },
                                    dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancel") } })
                            }
                            ProblemList(problems, onClearProblems)
                        }
                    }
                }
                item { AboutSection(checks, mapPackage, dataDir, version) }
            }
        }
    }
}

/** Data of the "Map cells (debug)" block: installed cells, cells in progress, two actions (end-to-end test only). */
data class MapCellsDebug(
    /** id, version, bytes */
    val installed: List<Triple<String, String, Long>>,
    /** id → state line (CellState.label) for cells not simply installed */
    val active: List<Pair<String, String>>,
    /** "store g000007" or "no cells – data folder" */
    val store: String,
    val onRequestAtCentre: () -> Unit,
    val onDelete: (String) -> Unit,
)

@Composable
private fun MapCellsDebugList(d: MapCellsDebug) {
    Text("Map cells (debug)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    Text("Map files: ${d.store}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (d.installed.isEmpty()) Text("No cells installed.", fontSize = 12.sp)
    d.installed.forEach { (id, version, bytes) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$id · $version · ${"%.1f".format(java.util.Locale.ROOT, bytes / 1_048_576.0)} MB", fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = { d.onDelete(id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
        }
    }
    if (d.installed.isNotEmpty()) Text("Total ${"%.1f".format(java.util.Locale.ROOT, d.installed.sumOf { it.third } / 1_048_576.0)} MB",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    d.active.forEach { (id, s) -> Text("$id: $s", fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
    androidx.compose.material3.OutlinedButton(onClick = d.onRequestAtCentre, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("Request cell at map centre")
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
    if (problems.isNotEmpty()) androidx.compose.material3.TextButton(onClick = onClear,
        modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear problem list") }
}

@Composable
private fun AboutSection(checks: List<DataFiles.Check>, mapPackage: DataFiles.PackageCheck?, dataDir: String, version: String) {
    Section("About") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("OnPath $version", fontWeight = FontWeight.SemiBold)
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
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ATTRIBUTIONS.forEach { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("Licence: Apache-2.0", fontSize = 12.sp)
        }
    }
}

private val ATTRIBUTIONS = listOf(
    "Map data © OpenStreetMap contributors (ODbL)",
    "Vector tile schema © OpenMapTiles",
    "Terrain: Terrarium tiles (Mapzen / AWS Terrain Tiles; SRTM, Copernicus and others)",
    "Routing: BRouter (MIT licence)",
    "POI icons: Maki and Temaki (CC0)",
    "Map renderer: MapLibre Native (BSD licence)",
)

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = OnPathColors.Green,
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
