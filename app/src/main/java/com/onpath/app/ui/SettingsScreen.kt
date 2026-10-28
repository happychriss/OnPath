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
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.data.AppSettings
import com.onpath.app.data.DataFiles
import com.onpath.app.poi.PoiGroup
import com.onpath.app.poi.PoiKinds
import com.onpath.strings.*

val PoiGroup.label: String
    get() = when (this) {
        PoiGroup.FOOD -> Txt.s(Res.string.settings_poi_group_food)
        PoiGroup.HUT -> Txt.s(Res.string.settings_poi_group_hut)
        PoiGroup.WATER -> Txt.s(Res.string.settings_poi_group_water)
        PoiGroup.SIGHT -> Txt.s(Res.string.settings_poi_group_sight)
        PoiGroup.TRANSPORT -> Txt.s(Res.string.settings_poi_group_transport)
        PoiGroup.FACILITY -> Txt.s(Res.string.settings_poi_group_facility)
        PoiGroup.OTHER -> Txt.s(Res.string.settings_poi_group_other)
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
    /** The result of something done on the open page (restored, a failure …): a line at its top (owner 2026-10-27). */
    note: Notice? = null,
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
    /**
     * Developer tools shown in Diagnostics (owner C5 2026-10-24): hidden until the version line in About is tapped 7
     * times (remembered); debug builds always.
     */
    devTools: Boolean = true,
    onUnlockDevTools: () -> Unit = {},
    /** Last visit to Diagnostics (ms): a serious problem after it makes the list line red (owner C6). */
    diagnosticsSeenMs: Long = 0L,
    /** Developer tools: the map server line (MapServer.describe; fixed in screenshots). */
    mapServer: String = com.onpath.app.data.MapServer.describe(),
    /** Developer tools: the test style line + Reload map style (tools/dev/push_style.sh); null = hidden. */
    testStyle: TestStyleUi? = null,
    /** Diagnostics: the active language line; Developer tools: System / English / Deutsch (owner 2026-10-26). */
    language: LanguageUi = LanguageUi(),
    now: Long = System.currentTimeMillis(),
) {
    val topics = SettingsPage.entries.filter {
        when (it) {
            SettingsPage.OFFLINE_MAPS -> offlineMaps != null
            SettingsPage.BACKUP -> backup != null
            SettingsPage.UPDATES -> update != null
            SettingsPage.DIAGNOSTICS -> diagnostics
            else -> true
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (page != null) onPage(null) else onBack() }, Modifier.size(56.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, str(Res.string.common_back))
                }
                Text(page?.title ?: str(Res.string.common_settings), style = MaterialTheme.typography.titleLarge)
            }
            note?.let { NoticeLine(it, Modifier.padding(horizontal = 24.dp)) }
            if (page == null) {
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
                    // two groups (owner C7 2026-10-24): what the user sets, then the smaller "App" group
                    listOf(false, true).forEach { app ->
                        val group = topics.filter { it.app == app }
                        if (group.isEmpty()) return@forEach
                        item {
                            Text(str(if (app) Res.string.settings_group_app else Res.string.settings_group_yours), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = if (app) 20.dp else 4.dp, bottom = 6.dp))
                        }
                        item {
                            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 1.dp) {
                                Column {
                                    group.forEachIndexed { i, t ->
                                        if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp))
                                        val s = when (t) {
                                            SettingsPage.PACE -> SettingsSummary.pace(settings, activity)
                                            SettingsPage.POI -> SettingsSummary.poi(settings.hiddenPoiKinds)
                                            SettingsPage.MAP -> SettingsSummary.map(settings)
                                            SettingsPage.NAVIGATION -> SettingsSummary.navigation(settings)
                                            SettingsPage.OFFLINE_MAPS -> SettingsSummary.offlineMaps(offlineMaps!!)
                                            SettingsPage.BACKUP -> SettingsSummary.backup(backup!!, now)
                                            SettingsPage.UPDATES -> SettingsSummary.updates(update!!, version)
                                            SettingsPage.DIAGNOSTICS -> SettingsSummary.diagnostics(problems, diagnosticsSeenMs)
                                            SettingsPage.PRIVACY -> SettingsSummary.privacy
                                            SettingsPage.ABOUT -> SettingsSummary.about
                                        }
                                        TopicRow(t, s, small = app) { onPage(t) }
                                    }
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
                            Section(SettingsPage.UPDATES.title) { UpdateSectionContent(update, version, onCheckUpdate, onInstallUpdate) }
                        }
                        SettingsPage.DIAGNOSTICS -> if (diagnostics) {
                            item { DiagnosticsSection(diagnosticsBusy, onExportDiagnostics, problems, onClearProblems, language.active) }
                            if (devTools) item {
                                DeveloperTools(devToolsOpen, mapServer, mapCells, dataBusy, onDownloadRouting, onImportData, checks, mapPackage, dataDir,
                                    onTimeFormulas, testStyle, language)
                            }
                        }
                        SettingsPage.PRIVACY -> item { PrivacySection() }
                        SettingsPage.ABOUT -> item { AboutSection(version, devTools, onUnlockDevTools) }
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

/**
 * One topic of the list: icon, title, summary (red when it needs attention, accent for news), chevron. [small]: the
 * "App" group – lower rows, grey icon.
 */
@Composable
private fun TopicRow(t: SettingsPage, s: Summary, small: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = if (small) 56.dp else 64.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = if (small) 6.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(t.icon, null, tint = if (small) MaterialTheme.colorScheme.onSurfaceVariant else OnPathColors.Green,
            modifier = Modifier.size(if (small) 22.dp else 24.dp))
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge)
            Text(s.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
    Section(SettingsPage.POI.title) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { setHidden(emptySet()) }) { Text(str(Res.string.common_show_all)) }
            TextButton(onClick = { setHidden(PoiKinds.all.map { it.key }.toSet()) }) { Text(str(Res.string.settings_poi_hide_all)) }
            TextButton(onClick = { setHidden(PoiKinds.defaultHidden) }) { Text(str(Res.string.common_reset)) }
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
                    Text(g.label, style = MaterialTheme.typography.bodyLarge)
                    Text(str(Res.string.settings_poi_shown, on, kinds.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, str(if (open) Res.string.settings_cd_collapse else Res.string.settings_cd_expand))
                Spacer(Modifier.width(8.dp))
            }
            if (open) kinds.forEach { k ->
                val shown = k.key !in hidden
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = shown, role = Role.Checkbox) { setHidden(if (shown) hidden + k.key else hidden - k.key) }
                    .padding(start = 40.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = shown, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Text(k.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** Map: contours, hill shading, map direction (Plan and Navigate) and – for planning – the vibration on hold. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MapSection(settings: AppSettings, onChange: (AppSettings) -> Unit, themes: List<String>) {
    Section(SettingsPage.MAP.title) {
        if (themes.size > 1) {
            Text(str(Res.string.settings_map_style), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
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
        SwitchRow(str(Res.string.settings_map_contours), null, settings.contours) { onChange(settings.copy(contours = it)) }
        SwitchRow(str(Res.string.settings_map_hillshade), null, settings.hillshade) { onChange(settings.copy(hillshade = it)) }
        Text(str(Res.string.settings_map_direction), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true to str(Res.string.settings_map_travel), false to str(Res.string.settings_map_north)).forEach { (up, label) ->
                FilterChip(selected = settings.navHeadingUp == up, onClick = { onChange(settings.copy(navHeadingUp = up)) },
                    label = { Text(label) }, modifier = Modifier.heightIn(min = 44.dp))
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        Text(str(Res.string.settings_map_planning), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = OnPathColors.Green,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp))
        SwitchRow(str(Res.string.settings_map_vibrate), str(Res.string.settings_map_vibrate_sub),
            settings.planVibrate) { onChange(settings.copy(planVibrate = it)) }
    }
}

/** Navigation, in the owner's order: warning → distance → vibrations → screen → battery / location hints. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NavigationSection(settings: AppSettings, onChange: (AppSettings) -> Unit, onAppSettings: () -> Unit) {
    Section(SettingsPage.NAVIGATION.title) {
        SwitchRow(str(Res.string.settings_nav_offroute), str(Res.string.settings_nav_offroute_sub),
            settings.offRouteWarning) { onChange(settings.copy(offRouteWarning = it)) }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(str(Res.string.settings_nav_warn_after), style = MaterialTheme.typography.bodyMedium)
            AppSettings.OFF_ROUTE_CHOICES.forEach { m ->
                FilterChip(selected = settings.offRouteM == m, enabled = settings.offRouteWarning,
                    onClick = { onChange(settings.copy(offRouteM = m)) }, label = { Text(str(Res.string.settings_nav_meters, m)) },
                    modifier = Modifier.heightIn(min = 44.dp))
            }
        }
        SwitchRow(str(Res.string.settings_nav_vibrate_off), str(Res.string.settings_nav_vibrate_off_sub),
            settings.offRouteVibrate && settings.offRouteWarning, enabled = settings.offRouteWarning) {
            onChange(settings.copy(offRouteVibrate = it))
        }
        // three choices (owner 2026-10-28): off / always / only with the screen locked
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(str(Res.string.settings_nav_junction), style = MaterialTheme.typography.bodyLarge)
            Text(str(Res.string.settings_nav_junction_sub), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(com.onpath.app.data.JunctionVibration.OFF to Res.string.settings_nav_junction_off,
                    com.onpath.app.data.JunctionVibration.ALWAYS to Res.string.settings_nav_junction_always,
                    com.onpath.app.data.JunctionVibration.LOCKED to Res.string.settings_nav_junction_locked).forEach { (v, label) ->
                    FilterChip(selected = settings.junctionVibration == v, onClick = { onChange(settings.copy(junctionVibration = v)) },
                        label = { Text(str(label)) }, modifier = Modifier.heightIn(min = 44.dp))
                }
            }
        }
        SwitchRow(str(Res.string.settings_nav_on_route), str(Res.string.settings_nav_on_route_sub), settings.positionOnRoute) {
            onChange(settings.copy(positionOnRoute = it))
        }
        SwitchRow(str(Res.string.settings_nav_screen_on), str(Res.string.settings_nav_screen_on_sub), settings.keepScreenOn) {
            onChange(settings.copy(keepScreenOn = it))
        }
        // Was a pop-up on the first recording (owner: remove it) – now a quiet hint here.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(str(Res.string.settings_nav_battery_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onAppSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_app_settings)) }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val bg = androidx.core.content.ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            // the quiet hint instead of a question (owner C2 2026-10-24: never ask); nothing to say once it is set
            if (!bg) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(str(Res.string.settings_nav_location_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onAppSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_app_settings)) }
            }
        }
    }
}

/** Diagnostics: the problem report button (no switch – review 2026-10-24 B23), then the problem list. */
@Composable
private fun DiagnosticsSection(busy: Boolean, onExport: () -> Unit,
                               problems: List<com.onpath.app.debug.Problems.Entry>, onClearProblems: () -> Unit,
                               language: String = "English (phone language)") {
    Section("Diagnostics") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { if (!busy) onExport() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (busy) BusyContent(Busy.DIAGNOSTICS.text) else Text("Send problem report")
            }
            Text("Leave the map at the problem spot first.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("App language: $language", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ProblemList(problems, onClearProblems)
        }
    }
}

/** Developer tools → app language (owner 2026-10-26): the phone's, or fixed to English / Deutsch; kept across restarts. */
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
 * data import and the data folder / file checks / map package (moved from About and "Map data", owner 2026-10-22).
 */
@Composable
private fun DeveloperTools(
    initiallyOpen: Boolean, mapServer: String, mapCells: MapCellsDebug?, dataBusy: String?, onDownloadRouting: () -> Unit, onImportData: () -> Unit,
    checks: List<DataFiles.Check>, mapPackage: DataFiles.PackageCheck?, dataDir: String,
    onTimeFormulas: (() -> Unit)? = null,
    testStyle: TestStyleUi? = null,
    language: LanguageUi = LanguageUi(),
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
                Text("Map server: $mapServer", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // by hand: the 1° routing parts at the map centre (they normally load with the map)
                OutlinedButton(onClick = onDownloadRouting, enabled = dataBusy == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Download routing data (map centre)") }
                if (dataBusy != null) Text(dataBusy, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                mapCells?.let { MapCellsDebugList(it) }
                // the formulas behind the plan sheet's time (moved here from ⋯ → Time details, owner 2026-10-23)
                OutlinedButton(onClick = { onTimeFormulas?.invoke() }, enabled = onTimeFormulas != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Time estimate of the route (formulas)") }
                if (onTimeFormulas == null) Text("Plan a route first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                testStyle?.let { TestStyleRow(it) }
                LanguageRow(language)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Map data", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text("Import a map package or a map style file (.zip, .pmtiles, .geojson, .rd5, onpath-*.json) from this phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // routing data is part of the map (owner 2026-10-22): no own button here, see above
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

/** Problems the app noticed (errors it recovered from, map engine warnings, crashes) – nothing is hidden. */
@Composable
private fun ProblemList(problems: List<com.onpath.app.debug.Problems.Entry>, onClear: () -> Unit) {
    val serious = problems.count { it.level >= com.onpath.app.debug.Problems.Level.ERROR }
    Text(if (problems.isEmpty()) "No problems noticed." else "Problems noticed: ${problems.size}" +
        (if (serious > 0) " ($serious serious)" else ""), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    val fmt = remember { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()) }
    problems.take(15).forEach { p ->
        val color = when (p.level) {
            com.onpath.app.debug.Problems.Level.CRASH, com.onpath.app.debug.Problems.Level.ERROR -> OnPathColors.Bad
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text("${fmt.format(java.util.Date(p.last))} · ${p.level.name.lowercase()} · ${p.area}: ${p.message}" +
            (if (p.count > 1) " (× ${p.count})" else ""), style = MaterialTheme.typography.bodySmall, color = color)
    }
    if (problems.size > 15) Text("… and ${problems.size - 15} more in the export.", style = MaterialTheme.typography.bodySmall)
    if (problems.isNotEmpty()) TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear problem list") }
}

/**
 * Privacy (owner 2026-10-22): what leaves the phone, to whom, when – checked against the code's network calls
 * (CellApi, Rd5PartStore / SegmentDownloader, PhotonProvider, AppUpdater, ShortLinkResolver, link-outs). Keep in step.
 */
@Composable
private fun PrivacySection() {
    Section(SettingsPage.PRIVACY.title) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(str(Res.string.settings_privacy_lead), fontWeight = FontWeight.SemiBold)
            Text(str(Res.string.settings_privacy_tracks), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Text(str(Res.string.settings_privacy_what), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            PRIVACY_FLOWS.forEach { f ->
                Column {
                    Text(f.what, style = MaterialTheme.typography.bodyMedium)
                    Text("→ ${f.to} · ${f.whenText}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** One thing that leaves the phone: what, to whom, when. */
data class PrivacyFlow(val what: String, val to: String, val whenText: String)

/** In the app's language (strings_settings.xml); service names stay as they are. */
val PRIVACY_FLOWS: List<PrivacyFlow> get() = listOf(
    PrivacyFlow(Txt.s(Res.string.settings_privacy_maps_what), Txt.s(Res.string.settings_privacy_maps_to),
        Txt.s(Res.string.settings_privacy_when_maps)),
    PrivacyFlow(Txt.s(Res.string.settings_privacy_routing_what), "brouter.de", Txt.s(Res.string.settings_privacy_when_maps)),
    PrivacyFlow(Txt.s(Res.string.settings_privacy_search_what), Txt.s(Res.string.settings_privacy_search_to, "komoot"),
        Txt.s(Res.string.settings_privacy_search_when)),
    PrivacyFlow(Txt.s(Res.string.settings_privacy_update_what), "GitHub", Txt.s(Res.string.settings_privacy_update_when)),
    PrivacyFlow(Txt.s(Res.string.settings_privacy_link_what), "Google", Txt.s(Res.string.settings_privacy_link_when)),
    PrivacyFlow(Txt.s(Res.string.settings_privacy_tap_what), "Google Maps", Txt.s(Res.string.settings_privacy_tap_when)),
    PrivacyFlow(Txt.s(Res.string.settings_privacy_web_what), Txt.s(Res.string.settings_privacy_web_to),
        Txt.s(Res.string.settings_privacy_web_when)),
    PrivacyFlow(Txt.s(Res.string.settings_privacy_diag_what), Txt.s(Res.string.settings_privacy_diag_to),
        Txt.s(Res.string.settings_privacy_diag_when)),
)

/** About: version, licence and the full credits (the map's "© OpenStreetMap" opens this page). */
@Composable
private fun AboutSection(version: String, devTools: Boolean = true, onUnlockDevTools: () -> Unit = {}) {
    var taps by remember { mutableStateOf(0) }
    Section(SettingsPage.ABOUT.title) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // tapped 7 times: Developer tools in Diagnostics (owner C5 2026-10-24, the Android convention); remembered
            Text("OnPath $version", fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)
                    .clickable(enabled = !devTools, indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                        taps++
                        if (DevToolsUnlock.unlocked(taps)) onUnlockDevTools()
                    })
            if (devTools && taps > 0) Text(str(Res.string.settings_about_devtools_on), style = MaterialTheme.typography.bodySmall,
                color = OnPathColors.Green)
            Text(str(Res.string.settings_about_licence), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(str(Res.string.settings_about_credits), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            MAP_CREDITS.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Full credits (Settings → About); the map shows only "© OpenStreetMap" and opens these on a tap. */
val MAP_CREDITS: List<String> get() = listOf(
    Res.string.settings_credit_osm, Res.string.settings_credit_omt, Res.string.settings_credit_terrain,
    Res.string.settings_credit_ne, Res.string.settings_credit_brouter, Res.string.settings_credit_icons,
    Res.string.settings_credit_maplibre,
).map { Txt.s(it) }

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
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
