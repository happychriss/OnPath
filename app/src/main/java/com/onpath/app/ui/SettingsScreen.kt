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
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Warn after", fontSize = 14.sp)
                            AppSettings.OFF_ROUTE_CHOICES.forEach { m ->
                                FilterChip(selected = settings.offRouteM == m, enabled = settings.offRouteWarning,
                                    onClick = { onChange(settings.copy(offRouteM = m)) }, label = { Text("$m m") },
                                    modifier = Modifier.heightIn(min = 44.dp))
                            }
                        }
                        SwitchRow("Keep screen on while navigating", "Only while following a route", settings.keepScreenOn) {
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
                        // Navigate orientation (navigation agent): heading-up default, north-up option.
                        Text("Map direction while navigating", fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(true to "Walking direction", false to "North up").forEach { (up, label) ->
                                FilterChip(selected = settings.navHeadingUp == up, onClick = { onChange(settings.copy(navHeadingUp = up)) },
                                    label = { Text(label) }, modifier = Modifier.heightIn(min = 44.dp))
                            }
                        }
                    }
                }
                item {
                    Section("Map data") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Import a map package or a map style file (.zip, .pmtiles, .geojson, .rd5, onpath-*.json) from this phone.",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            DataActions(
                                routingMissing = checks.any { it.name.startsWith("segments") && !it.present },
                                busy = dataBusy, onImport = onImportData, onDownloadRouting = onDownloadRouting,
                            )
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
                        }
                    }
                }
                item { AboutSection(checks, mapPackage, dataDir, version) }
            }
        }
    }
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
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
