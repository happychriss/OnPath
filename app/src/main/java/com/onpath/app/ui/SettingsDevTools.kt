package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.SegmentedButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.data.AppSettings
import com.onpath.app.data.DataFiles
import com.onpath.app.poi.PoiGroup
import com.onpath.app.poi.PoiKinds
import com.onpath.strings.*

// Settings → About → Diagnostics → Developer tools (English, like the whole page); split out of SettingsScreen.kt.

/** Developer tools → app language: the phone's, or fixed to English / Deutsch; kept across restarts. */
data class LanguageUi(
    val choice: com.onpath.app.data.LanguageChoice = com.onpath.app.data.LanguageChoice.SYSTEM,
    val active: String = "English (phone language)",
    val onChoose: (com.onpath.app.data.LanguageChoice) -> Unit = {},
)

@Composable
private fun LanguageRow(l: LanguageUi) {
    Text("App language", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val all = com.onpath.app.data.LanguageChoice.entries
    androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        all.forEachIndexed { i, c ->
            SegmentedButton(
                selected = c == l.choice, onClick = { if (c != l.choice) l.onChoose(c) },
                shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, all.size),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(c.label, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/**
 * Diagnostics → "Developer tools" (closed at first): map server, routing data by hand, the map areas list, the map
 * data import and the data folder / file checks / map package.
 */
@Composable
internal fun DeveloperTools(
    initiallyOpen: Boolean, mapServer: String, mapCells: MapCellsDebug?, dataBusy: String?, onDownloadRouting: () -> Unit, onImportData: () -> Unit,
    checks: List<DataFiles.Check>, mapPackage: DataFiles.PackageCheck?, dataDir: String,
    onTimeFormulas: (() -> Unit)? = null,
    testStyle: TestStyleUi? = null,
    language: LanguageUi = LanguageUi(),
    demo: DemoUi? = null,
    suggestTest: SuggestTestUi? = null,
) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Surface(shape = OnPathShapes.Card, color = OnPathColors.CardPaper, modifier = Modifier.warmShadow(OnPathShadow.LOW, OnPathShapes.Card)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { open = !open }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Developer tools", style = MaterialTheme.typography.titleSmall, color = OnPathColors.Green, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Close" else "Open")
            }
            if (open) Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Map server: $mapServer", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // by hand: the 1° routing parts at the map centre (they normally load with the map)
                OutlinedButton(onClick = onDownloadRouting, enabled = dataBusy == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Download routing data (map centre)") }
                if (dataBusy != null) Text(dataBusy, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                mapCells?.let { MapCellsDebugList(it) }
                // the formulas behind the plan sheet's time
                OutlinedButton(onClick = { onTimeFormulas?.invoke() }, enabled = onTimeFormulas != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Time estimate of the route (formulas)") }
                if (onTimeFormulas == null) Text("Plan a route first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                testStyle?.let { TestStyleRow(it) }
                demo?.let { DemoRow(it) }
                // the round-trip suggestions (docs/design/tour-suggestions.md §7 stage 4): on by default since 2026-11-18, an off-switch as the planner's ?debug one
                suggestTest?.let { t -> SwitchRow(str(Res.string.suggest_ui_test), null, t.on, onChange = t.onChange) }
                LanguageRow(language)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Map data", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text("Import a map package or a map style file (.zip, .pmtiles, .geojson, .rd5, onpath-*.json) from this phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // routing data is part of the map (owner): no own button here, see above
                DataActions(routingMissing = false, busy = dataBusy, onImport = onImportData, onDownloadRouting = {})
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Data folder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(dataDir, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                checks.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (c.present) Icons.Filled.CheckCircle else Icons.Filled.RemoveCircleOutline, null,
                            tint = if (c.present) OnPathColors.Good else OnPathColors.Bad, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("${c.name} – ${c.hint}" + if (c.present) "" else " (missing)", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (mapPackage != null) {
                    Text("Map package: ${mapPackage.region} · ${mapPackage.area}" +
                        (mapPackage.osmDate?.let { " · OSM ${it.take(10)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    if (!mapPackage.ok) Text("From another package (map has gaps there): ${mapPackage.mismatched.joinToString()}. " +
                        "Import every part of the map package again.", style = MaterialTheme.typography.bodySmall, color = OnPathColors.Bad)
                }
            }
        }
    }
}

/** Developer tools → „Tourvorschläge“: the round-trip suggestions on / off ([com.onpath.app.ui.suggest.SuggestSlice.on]). */
data class SuggestTestUi(val on: Boolean, val onChange: (Boolean) -> Unit)

/**
 * Developer tools: the map test style (StyleLoader.DEV_STYLE, pushed by tools/dev/push_style.sh). [status] null = no
 * test style file (only the reload action shows).
 */
data class TestStyleUi(val status: String?, val onRemove: () -> Unit, val onReload: () -> Unit)

@Composable
private fun TestStyleRow(t: TestStyleUi) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text("Map style", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    if (t.status != null) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(t.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = t.onRemove, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
    } else Text("No test style (tools/dev/push_style.sh puts one in ${com.onpath.app.map.StyleLoader.DEV_STYLE}).",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = t.onReload, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Reload map style") }
}

/**
 * Developer tools → Demo location (recording/DemoLocation): simulated GPS fixes for screenshots. [state] null = off;
 * [rideLabel] = what a ride would follow ("planned route", a shown track), null = nothing to ride.
 */
data class DemoUi(
    val state: com.onpath.app.recording.DemoLocation.State?,
    val rideLabel: String?,
    val onRide: () -> Unit = {},
    val onPlace: () -> Unit = {},
    val onFactor: (Int) -> Unit = {},
    val onPause: (Boolean) -> Unit = {},
    val onStop: () -> Unit = {},
    /** Recorded tours to ride again at their recorded times (label, start) – debugging a field tour. */
    val recorded: List<Pair<String, () -> Unit>> = emptyList(),
)

@Composable
private fun DemoRow(d: DemoUi) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text("Demo location", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    val st = d.state
    if (st == null) {
        Text("Simulated GPS for screenshots: real GPS is ignored while it runs, the map shows DEMO, tours are named " +
            "\"${com.onpath.app.recording.DemoLocation.TOUR_PREFIX}…\". Ends with Stop or an app restart.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = d.onRide, enabled = d.rideLabel != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Demo: ride the ${d.rideLabel ?: "planned route"}")
        }
        if (d.rideLabel == null) Text("Plan a route or open a track first.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = d.onPlace, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Demo: stand at the map centre") }
        if (d.recorded.isNotEmpty()) Text("Ride a recorded tour again (its own times; plan and start navigation first):",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for ((label, start) in d.recorded) {
            OutlinedButton(onClick = start, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Demo: $label") }
        }
        return
    }
    val where = when (st.kind) {
        com.onpath.app.recording.DemoLocation.Kind.RIDE -> "riding the ${st.label}: " +
            "${"%.1f".format(java.util.Locale.ROOT, st.alongM / 1000)} of ${"%.1f".format(java.util.Locale.ROOT, st.lengthM / 1000)} km" +
            when { st.ended -> " – at the end"; st.paused -> " – standing"; else -> " at ${st.factor}×" }
        com.onpath.app.recording.DemoLocation.Kind.PLACE -> "standing at the ${st.label}"
    }
    Text("DEMO on – $where", style = MaterialTheme.typography.bodySmall, color = OnPathColors.Warn)
    if (st.kind == com.onpath.app.recording.DemoLocation.Kind.RIDE) {
        val all = com.onpath.app.recording.DemoLocation.FACTORS
        androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            all.forEachIndexed { i, f ->
                SegmentedButton(selected = f == st.factor, onClick = { d.onFactor(f) },
                    shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, all.size),
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("$f×", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (!st.ended) OutlinedButton(onClick = { d.onPause(!st.paused) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (st.paused) "Go on" else "Stand still")
        }
    }
    OutlinedButton(onClick = d.onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Stop demo – real GPS again") }
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
    Text("Map areas (debug)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    Text("Map files: ${d.store}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (d.installed.isEmpty()) Text("No areas installed.", style = MaterialTheme.typography.bodySmall)
    d.installed.forEach { (id, version, bytes) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$id · $version · ${"%.1f".format(java.util.Locale.ROOT, bytes / 1_048_576.0)} MB", style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            TextButton(onClick = { d.onDelete(id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
        }
    }
    if (d.installed.isNotEmpty()) Text("Total ${"%.1f".format(java.util.Locale.ROOT, d.installed.sumOf { it.third } / 1_048_576.0)} MB",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    d.active.forEach { (id, s) -> Text("$id: $s", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    OutlinedButton(onClick = d.onRequestAtCentre, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("Request map area at map centre")
    }
}
