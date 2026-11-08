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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
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
 * Settings (☰ → Settings): a list of topics (icon, title, one line of the current values –
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
    /** Settings → Phone setup: the checks of this phone; null = topic hidden (screenshots of older pages). */
    phoneChecks: List<com.onpath.app.setup.Check>? = null,
    phone: com.onpath.app.setup.PhoneState = com.onpath.app.setup.PhoneState(sdk = 35),
    /** A row's "Set up" (or the vibration test): that step of the guide. */
    onPhoneFix: (com.onpath.app.setup.CheckId) -> Unit = {},
    /** Diagnostics → "Download routing data" (normally it comes with the map by itself). */
    onDownloadRouting: () -> Unit = {},
    /** Diagnostics → Developer tools → the planned route's time estimate with its formulas; null = no route (greyed). */
    onTimeFormulas: (() -> Unit)? = null,
    dataBusy: String? = null,
    /** The result of something done on the open page (restored, a failure …): a line at its top. */
    note: Notice? = null,
    /** null = updates handled elsewhere (installed from Google Play) – topic hidden. */
    update: AppViewModel.UpdateState? = null,
    onCheckUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
    /** Installed map package (package.json) and files from another package – Diagnostics → Developer tools. */
    mapPackage: DataFiles.PackageCheck? = null,
    /** Settings → Diagnostics shown (problem report button + problem list); false = topic hidden. */
    diagnostics: Boolean = false,
    /** The problem report is being created: a spinner on its button. */
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
    /** Settings → "Browser sync" (plan sync with the web planner); null = topic hidden. */
    sync: SyncUi? = null,
    syncCb: SyncCallbacks = SyncCallbacks(),
    /** Current activity: "Your pace" explains the cycling levels for this bike. */
    activity: com.onpath.app.routing.Activity = com.onpath.app.routing.Activity.HIKE,
    /** Diagnostics → "Developer tools" open at first (screenshots). */
    devToolsOpen: Boolean = false,
    /** Diagnostics: the problem list unfolded (screenshot tests); on the phone it always opens folded (owner 2026-11-02). */
    problemsOpen: Boolean = false,
    /**
     * Developer tools shown in Diagnostics (owner): hidden until the version line in About is tapped 7 times
     * (remembered; also in debug builds – testers get those). Hidden unless the caller says so.
     */
    devTools: Boolean = false,
    onUnlockDevTools: () -> Unit = {},
    /** Last visit to Diagnostics (ms): a serious problem after it makes the list line red (owner). */
    diagnosticsSeenMs: Long = 0L,
    /** Developer tools: the map server line (MapServer.describe; fixed in screenshots). */
    mapServer: String = com.onpath.app.data.MapServer.describe(),
    /** Developer tools: the test style line + Reload map style (tools/dev/push_style.sh); null = hidden. */
    testStyle: TestStyleUi? = null,
    /** Diagnostics: the active language line; Developer tools: System / English / Deutsch. */
    language: LanguageUi = LanguageUi(),
    /** Diagnostics: routing fingerprint + routing data dates (routing/RoutingFingerprint.describe); null = no line. */
    routingInfo: String? = null,
    /** Developer tools → Demo location (simulated GPS for screenshots); null = hidden. */
    demo: DemoUi? = null,
    /** About → Licences: the list (fixed in screenshots); null = read from the APK's assets when opened. */
    licences: List<com.onpath.app.data.Licences.Entry>? = null,
    /** About → Licences open from the start (screenshots). */
    licencesOpen: Boolean = false,
    now: Long = System.currentTimeMillis(),
    /**
     * Opened from Navigate's ⚙ (owner 2026-11-07): the list is the Navigation page itself, then only the topics for
     * the way ([SettingsSummary.onTheWay]) and "Alle Einstellungen ›" ([onAllSettings]); the tour is not touched.
     */
    onTheWay: Boolean = false,
    onAllSettings: () -> Unit = {},
) {
    val topics = SettingsPage.entries.filter {
        when (it) {
            SettingsPage.OFFLINE_MAPS -> offlineMaps != null
            SettingsPage.BACKUP -> backup != null
            SettingsPage.SYNC -> sync != null
            SettingsPage.UPDATES -> update != null
            SettingsPage.DIAGNOSTICS -> diagnostics
            SettingsPage.PHONE -> phoneChecks != null
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
            if (page == null && onTheWay) CompositionLocalProvider(LocalSectionTitle provides false) {
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        NavigationSection(settings, onChange, onPhoneSetup = { onPage(SettingsPage.PHONE) },
                            onTestVibration = { onPhoneFix(com.onpath.app.setup.CheckId.VIBRATION) },
                            onTestVoice = { onPhoneFix(com.onpath.app.setup.CheckId.VOICE) },
                            phoneChecks = phoneChecks, phone = phone)
                    }
                    val more = SettingsSummary.onTheWay(topics)
                    item { SettingsGroupHeader(str(Res.string.settings_group_on_the_way)) }
                    item {
                        SettingsCard {
                            more.forEachIndexed { i, t ->
                                if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp))
                                TopicRow(t, topicSummary(t, settings, activity, offlineMaps)) { onPage(t) }
                            }
                            if (more.isNotEmpty()) HorizontalDivider(Modifier.padding(start = 64.dp))
                            AllSettingsRow(onAllSettings)
                        }
                    }
                }
            } else if (page == null) {
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
                    // two groups (owner): what the user sets, then the smaller "App" group; Phone setup on top (no
                    // heading) while a tour needs something from the phone (owner 2026-10-30)
                    SettingsSummary.groups(topics, phoneChecks).forEachIndexed { gi, (kind, group) ->
                        val app = kind == TopicGroup.APP
                        if (kind != TopicGroup.URGENT) item {
                            Text(str(if (app) Res.string.settings_group_app else Res.string.settings_group_yours), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = if (gi > 0) 20.dp else 4.dp, bottom = 6.dp))
                        } else item { Spacer(Modifier.height(4.dp)) }
                        item {
                            Surface(shape = OnPathShapes.Card, color = OnPathColors.CardPaper, modifier = Modifier.warmShadow(OnPathShadow.LOW, OnPathShapes.Card)) {
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
                                            SettingsPage.SYNC -> SettingsSummary.sync(sync!!)
                                            SettingsPage.UPDATES -> SettingsSummary.updates(update!!, version)
                                            SettingsPage.DIAGNOSTICS -> SettingsSummary.diagnostics(problems, diagnosticsSeenMs)
                                            SettingsPage.PRIVACY -> SettingsSummary.privacy
                                            SettingsPage.PHONE -> SettingsSummary.phone(phoneChecks!!)
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
                        SettingsPage.NAVIGATION -> item {
                            NavigationSection(settings, onChange, onPhoneSetup = { onPage(SettingsPage.PHONE) },
                                onTestVibration = { onPhoneFix(com.onpath.app.setup.CheckId.VIBRATION) },
                                onTestVoice = { onPhoneFix(com.onpath.app.setup.CheckId.VOICE) },
                                phoneChecks = phoneChecks, phone = phone)
                        }
                        SettingsPage.PHONE -> if (phoneChecks != null) item {
                            com.onpath.app.ui.setup.PhoneSetupSection(phoneChecks, phone, onPhoneFix,
                                onTestVibration = { onPhoneFix(com.onpath.app.setup.CheckId.VIBRATION) })
                        }
                        SettingsPage.OFFLINE_MAPS -> if (offlineMaps != null) item { OfflineMapsSection(offlineMaps, offlineMapsCb) }
                        SettingsPage.BACKUP -> if (backup != null) item { BackupSection(backup, backupCb) }
                        SettingsPage.SYNC -> if (sync != null) item { SyncSection(sync, syncCb, now) }
                        SettingsPage.UPDATES -> if (update != null) item {
                            Section(SettingsPage.UPDATES.title) { UpdateSectionContent(update, version, onCheckUpdate, onInstallUpdate) }
                        }
                        SettingsPage.DIAGNOSTICS -> if (diagnostics) {
                            item { DiagnosticsSection(diagnosticsBusy, onExportDiagnostics, problems, onClearProblems, language.active, routingInfo, problemsOpen) }
                            if (devTools) item {
                                DeveloperTools(devToolsOpen, mapServer, mapCells, dataBusy, onDownloadRouting, onImportData, checks, mapPackage, dataDir,
                                    onTimeFormulas, testStyle, language, demo)
                            }
                        }
                        SettingsPage.PRIVACY -> item { PrivacySection() }
                        SettingsPage.ABOUT -> {
                            item { AboutSection(version, devTools, onUnlockDevTools) }
                            item { AboutLinksSection() }
                            item { CreditsSection() }
                            item { LicencesSection(licences, licencesOpen) }
                        }
                    }
                }
            }
        }
    }
}

/** The summary line of a topic on the on-the-way list ([SettingsSummary.ON_THE_WAY]). */
private fun topicSummary(t: SettingsPage, settings: AppSettings, activity: com.onpath.app.routing.Activity, offlineMaps: OfflineMapsUi?): Summary =
    when (t) {
        SettingsPage.PACE -> SettingsSummary.pace(settings, activity)
        SettingsPage.POI -> SettingsSummary.poi(settings.hiddenPoiKinds)
        SettingsPage.MAP -> SettingsSummary.map(settings)
        SettingsPage.OFFLINE_MAPS -> offlineMaps?.let { SettingsSummary.offlineMaps(it) } ?: SettingsSummary.navigation(settings)
        else -> SettingsSummary.navigation(settings)
    }

/** "Alle Einstellungen ›" at the end of the on-the-way list: the whole topic list. */
@Composable
private fun AllSettingsRow(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Settings, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(24.dp))
        Text(str(Res.string.settings_all), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
        SettingsPage.SYNC -> Icons.Outlined.Sync
        SettingsPage.PRIVACY -> Icons.Outlined.Lock
        SettingsPage.PHONE -> Icons.Outlined.PhoneAndroid
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
        // a topic that blocks tours (Phone setup): the red ✗-mark of the phone checks instead of its icon
        val blocking = s.attention == Summary.Attention.PROBLEM && t == SettingsPage.PHONE
        // steps open (owner 2026-11-08): a small amber dot on the phone icon, the row on top with "N steps open"
        val open = s.attention == Summary.Attention.WARNING && t == SettingsPage.PHONE
        Box {
            Icon(if (blocking) Icons.Outlined.ErrorOutline else t.icon, null,
                tint = when { blocking -> OnPathColors.Error; open -> OnPathColors.Green; small -> MaterialTheme.colorScheme.onSurfaceVariant; else -> OnPathColors.Green },
                modifier = Modifier.size(if (small && !open) 22.dp else 24.dp))
            if (open) Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(10.dp)
                .background(OnPathColors.Warn, CircleShape).border(1.5.dp, OnPathColors.CardPaper, CircleShape))
        }
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge)
            Text(s.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                fontWeight = if (s.attention == Summary.Attention.NONE) FontWeight.Normal else FontWeight.SemiBold,
                color = when (s.attention) {
                    Summary.Attention.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                    Summary.Attention.ACCENT -> OnPathColors.Route
                    Summary.Attention.WARNING -> OnPathColors.Warn
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
        // value rows (owner 2026-11-06 23:05): the choice opens as a sheet ([ChoiceRow])
        if (themes.size > 1) ChoiceRow(str(Res.string.settings_map_style), null,
            themes.map { Choice(it, com.onpath.app.map.StyleLoader.label(it)) }, settings.mapTheme,
            onPick = { onChange(settings.copy(mapTheme = it)) })
        SwitchRow(str(Res.string.settings_map_contours), null, settings.contours) { onChange(settings.copy(contours = it)) }
        SwitchRow(str(Res.string.settings_map_hillshade), null, settings.hillshade) { onChange(settings.copy(hillshade = it)) }
        ChoiceRow(str(Res.string.settings_map_direction), null,
            listOf(Choice(true, str(Res.string.settings_map_travel)), Choice(false, str(Res.string.settings_map_north))),
            settings.navHeadingUp, onPick = { onChange(settings.copy(navHeadingUp = it)) })
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        Text(str(Res.string.settings_map_planning), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = OnPathColors.Green,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp))
        SwitchRow(str(Res.string.settings_map_vibrate), str(Res.string.settings_map_vibrate_sub),
            settings.planVibrate) { onChange(settings.copy(planVibrate = it)) }
    }
}

/**
 * Navigation in groups of value rows (owner 2026-11-06 23:05, variant C – "Ich mag Wertezeilen am meisten"): Abseits
 * der Route (warning → indented distance + vibration, greyed while the warning is off) · Abzweigungen (cue kind + ONE
 * test of the chosen kind) · Bildschirm und Karte · Sperre während der Tour (the hold slider) · "Handy einrichten" –
 * at the top with ⚠ and what is missing while something is not set up ([SettingRows.phoneRow]), else the last row.
 */
@Composable
internal fun NavigationSection(settings: AppSettings, onChange: (AppSettings) -> Unit, onPhoneSetup: () -> Unit,
                               onTestVibration: () -> Unit, onTestVoice: () -> Unit = {},
                               phoneChecks: List<com.onpath.app.setup.Check>? = null,
                               phone: com.onpath.app.setup.PhoneState = com.onpath.app.setup.PhoneState(sdk = 35)) {
    val place = SettingRows.phoneRow(phoneChecks)
    val missing = SettingRows.missing(phoneChecks).map { com.onpath.app.ui.setup.checkTitle(it, phone, row = true) }
    val warning = settings.offRouteWarning
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (place != SettingRows.PhoneRow.LAST) SettingsCard { PhoneSetupRow(place, missing, onPhoneSetup) }
        SettingsGroupHeader(str(Res.string.settings_group_offroute))
        SettingsCard {
            SwitchRow(str(Res.string.settings_nav_offroute), str(Res.string.settings_nav_offroute_sub), warning) {
                onChange(settings.copy(offRouteWarning = it))
            }
            ChoiceRow(str(Res.string.settings_nav_warn_after), null,
                AppSettings.OFF_ROUTE_CHOICES.map { Choice(it, str(Res.string.settings_nav_meters, it)) }, settings.offRouteM,
                onPick = { onChange(settings.copy(offRouteM = it)) }, enabled = warning, indent = true)
            SwitchRow(str(Res.string.settings_nav_vibrate_off), str(Res.string.settings_nav_vibrate_off_sub),
                settings.offRouteVibrate && warning, enabled = warning, indent = true) {
                onChange(settings.copy(offRouteVibrate = it))
            }
        }
        // how the turns are told – either / or (owner 2026-11-01): off / vibration / voice; one test of the chosen kind
        SettingsGroupHeader(str(Res.string.settings_group_junctions))
        SettingsCard {
            ChoiceRow(str(Res.string.settings_nav_guide), str(Res.string.settings_nav_guide_sub),
                listOf(Choice(com.onpath.app.data.JunctionGuide.OFF, str(Res.string.settings_nav_guide_off)),
                    Choice(com.onpath.app.data.JunctionGuide.VIBRATION, str(Res.string.settings_nav_guide_vibration)),
                    Choice(com.onpath.app.data.JunctionGuide.VOICE, str(Res.string.settings_nav_guide_voice))),
                settings.junctionGuide, onPick = { onChange(settings.copy(junctionGuide = it)) })
            when (SettingRows.testFor(settings.junctionGuide)) {
                com.onpath.app.setup.CheckId.VIBRATION -> TestButton(str(Res.string.setup_nav_test_vibration), onTestVibration)
                com.onpath.app.setup.CheckId.VOICE -> TestButton(str(Res.string.settings_nav_voice_test), onTestVoice)
                else -> {}
            }
        }
        SettingsGroupHeader(str(Res.string.settings_group_screen))
        SettingsCard {
            SwitchRow(str(Res.string.settings_nav_screen_on), str(Res.string.settings_nav_screen_on_sub), settings.keepScreenOn) {
                onChange(settings.copy(keepScreenOn = it))
            }
            BikeScreenRow(settings, onChange)
            SwitchRow(str(Res.string.settings_nav_on_route), str(Res.string.settings_nav_on_route_sub), settings.positionOnRoute) {
                onChange(settings.copy(positionOnRoute = it))
            }
        }
        SettingsGroupHeader(str(Res.string.settings_group_lock))
        SettingsCard { HoldTimeRow(settings, onChange) }
        // what the phone must allow (location, notifications, vibration, battery): one row to Phone setup
        if (place == SettingRows.PhoneRow.LAST) SettingsCard { PhoneSetupRow(place, missing, onPhoneSetup) }
    }
}

/** The one test button of the junction cue ("Vibration testen" / "Stimme testen"), Walnuss like every neutral action. */
@Composable
private fun TestButton(text: String, onClick: () -> Unit) =
    TextButton(onClick = onClick, modifier = Modifier.padding(start = 6.dp).heightIn(min = 48.dp)) {
        Icon(Icons.Filled.PlayArrow, null, tint = OnPathColors.Walnut, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = OnPathColors.Walnut)
    }

/**
 * "Knöpfe während der Tour halten" (owner 2026-11-06 22:50, replaces the lock switch): a slider 0 · 0,5 · 1 · 1,5 · 2 s
 * ([com.onpath.app.data.HoldSetting]) – how long a button of the tour's bar must be held; "Aus" = a plain tap. The value
 * beside the title, one line under it.
 */
@Composable
private fun HoldTimeRow(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val hs = com.onpath.app.data.HoldSetting
    val value = hs.seconds(settings.navHoldMs, str(Res.string.settings_nav_lock_decimal).firstOrNull() ?: ',')
        ?.let { str(Res.string.settings_nav_lock_seconds, it) } ?: str(Res.string.settings_nav_lock_off)
    val title = str(Res.string.settings_nav_lock)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = OnPathColors.Walnut)
        }
        Text(str(Res.string.settings_nav_lock_sub), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.material3.Slider(
            value = settings.navHoldMs.toFloat(),
            onValueChange = { v -> val ms = hs.snap(v); if (ms != settings.navHoldMs) onChange(settings.copy(navHoldMs = ms)) },
            valueRange = 0f..hs.MAX_MS.toFloat(), steps = hs.SLIDER_STEPS,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .holdSliderSemantics(title, value))
    }
}

/** TalkBack reads the title and the chosen time on the slider ("Knöpfe während der Tour halten, 1 s"). */
private fun Modifier.holdSliderSemantics(title: String, value: String): Modifier =
    this.then(Modifier.semantics { contentDescription = title; stateDescription = value })

/**
 * "Screen while cycling: Always on / Smart / Like the phone" (owner 2026-10-30) as a value row: the chosen one explained
 * in the line under the title; in the sheet every option with its line.
 */
@Composable
private fun BikeScreenRow(settings: AppSettings, onChange: (AppSettings) -> Unit) {

    val options = listOf(
        Choice(com.onpath.app.data.BikeScreen.ALWAYS, str(Res.string.settings_nav_bike_screen_always), str(Res.string.settings_nav_bike_screen_always_sub)),
        Choice(com.onpath.app.data.BikeScreen.SMART, str(Res.string.settings_nav_bike_screen_smart), str(Res.string.settings_nav_bike_screen_smart_sub)),
        Choice(com.onpath.app.data.BikeScreen.PHONE, str(Res.string.settings_nav_bike_screen_phone), str(Res.string.settings_nav_bike_screen_phone_sub)))
    ChoiceRow(str(Res.string.settings_nav_bike_screen), options.first { it.value == settings.bikeScreen }.line, options,
        settings.bikeScreen, onPick = { onChange(settings.copy(bikeScreen = it)) })
}

/** Diagnostics: the problem report button (no switch), then the problem list. */
@Composable
private fun DiagnosticsSection(busy: Boolean, onExport: () -> Unit,
                               problems: List<com.onpath.app.debug.Problems.Entry>, onClearProblems: () -> Unit,
                               language: String = "English (phone language)", routing: String? = null,
                               problemsOpen: Boolean = false) {
    Section("Diagnostics") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { if (!busy) onExport() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (busy) BusyContent(Busy.DIAGNOSTICS.text) else Text("Send problem report")
            }
            Text("Leave the map at the problem spot first.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("App language: $language", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // the same fingerprint + data dates in the browser = the same routes (docs/design/routing-fingerprint.md)
            routing?.let { Text("Routing: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ProblemList(problems, onClearProblems, problemsOpen)
        }
    }
}

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
private fun DeveloperTools(
    initiallyOpen: Boolean, mapServer: String, mapCells: MapCellsDebug?, dataBusy: String?, onDownloadRouting: () -> Unit, onImportData: () -> Unit,
    checks: List<DataFiles.Check>, mapPackage: DataFiles.PackageCheck?, dataDir: String,
    onTimeFormulas: (() -> Unit)? = null,
    testStyle: TestStyleUi? = null,
    language: LanguageUi = LanguageUi(),
    demo: DemoUi? = null,
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

/**
 * Problems the app noticed (errors it recovered from, map engine warnings, crashes) – nothing is hidden, but the list
 * starts folded to one line, "127 entries, 3 errors ▸" (owner 2026-11-02: the developer tools under it are reached
 * without scrolling); a tap unfolds it. Not remembered: every visit opens folded. English like the whole page.
 */
@Composable
private fun ProblemList(problems: List<com.onpath.app.debug.Problems.Entry>, onClear: () -> Unit, initiallyOpen: Boolean = false) {
    val serious = problems.count { it.level >= com.onpath.app.debug.Problems.Level.ERROR }
    var open by remember { mutableStateOf(initiallyOpen) }
    if (problems.isEmpty()) {
        Text(ProblemFold.summary(0, 0, open), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) { open = !open },
        verticalAlignment = Alignment.CenterVertically) {
        Text(ProblemFold.summary(problems.size, serious, open), fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            color = if (serious > 0) OnPathColors.Bad else androidx.compose.ui.graphics.Color.Unspecified)
    }
    if (!open) return
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
    TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear problem list") }
}

/** The folded problem list's one line (pure): "127 entries, 3 errors ▸" – ▾ when open; errors = ERROR + CRASH. */
object ProblemFold {
    fun summary(total: Int, errors: Int, open: Boolean): String {
        if (total == 0) return "No problems noticed."
        val entries = if (total == 1) "1 entry" else "$total entries"
        val errs = when (errors) { 0 -> ""; 1 -> ", 1 error"; else -> ", $errors errors" }
        return entries + errs + if (open) " ▾" else " ▸"
    }
}

/**
 * Privacy: what leaves the phone, to whom, when – checked against the code's network calls
 * (CellApi, Rd5PartStore / SegmentDownloader, PhotonProvider, AppUpdater, ShortLinkResolver, AndroidPlaceGeocoder,
 * link-outs) and the backup rules (res/xml/backup_rules.xml, data_extraction_rules.xml). Says the same as the
 * website's privacy notice (onpath.earth/datenschutz: hosting, device backup, pairing). Keep in step.
 */
@Composable
private fun PrivacySection() {
    Section(SettingsPage.PRIVACY.title) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(str(Res.string.settings_privacy_lead), fontWeight = FontWeight.SemiBold)
            Text(str(Res.string.settings_privacy_tracks), style = MaterialTheme.typography.bodyMedium)
            Text(str(Res.string.settings_privacy_device_backup), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Text(str(Res.string.settings_privacy_what), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(str(Res.string.settings_privacy_ip), style = MaterialTheme.typography.bodySmall)
            PRIVACY_FLOWS.forEach { f ->
                Column {
                    Text(f.what, style = MaterialTheme.typography.bodyMedium)
                    Text("→ ${f.to} · ${f.whenText}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(str(Res.string.settings_privacy_google), style = MaterialTheme.typography.bodySmall)
            // plan sync (docs/design/web-sync.md §12 – the website's privacy notice says the same)
            HorizontalDivider()
            Text(str(Res.string.settings_privacy_sync_title), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(str(Res.string.settings_privacy_sync_text), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
        }
        PrivacyNoticeLink()
    }
}

/** One thing that leaves the phone: what, to whom, when. */
data class PrivacyFlow(val what: String, val to: String, val whenText: String)

/** In the app's language (strings_settings.xml); service names stay as they are. */
val PRIVACY_FLOWS: List<PrivacyFlow> get() = listOfNotNull(
    PrivacyFlow(Txt.s(Res.string.settings_privacy_maps_what), Txt.s(Res.string.settings_privacy_maps_to),
        Txt.s(Res.string.settings_privacy_when_maps)),
    PrivacyFlow(Txt.s(Res.string.settings_privacy_routing_what), "brouter.de", Txt.s(Res.string.settings_privacy_when_maps)),
    PrivacyFlow(Txt.s(Res.string.settings_privacy_search_what), Txt.s(Res.string.settings_privacy_search_to, "komoot"),
        Txt.s(Res.string.settings_privacy_search_when)),
    // update/AppUpdater – the Play build (A-003) has no own updater: no row there
    PrivacyFlow(Txt.s(Res.string.settings_privacy_update_what), "GitHub", Txt.s(Res.string.settings_privacy_update_when))
        .takeIf { com.onpath.app.BuildConfig.SELF_UPDATE },
    // search/ShortLinkResolver: redirects only, the Google page is never loaded (A-020)
    PrivacyFlow(Txt.s(Res.string.settings_privacy_link_what), "Google", Txt.s(Res.string.settings_privacy_link_when)),
    // search/AndroidPlaceGeocoder: last step for a shared place (A-020)
    PrivacyFlow(Txt.s(Res.string.settings_privacy_geocoder_what), Txt.s(Res.string.settings_privacy_geocoder_to),
        Txt.s(Res.string.settings_privacy_geocoder_when)),
    PrivacyFlow(Txt.s(Res.string.settings_privacy_tap_what), "Google Maps", Txt.s(Res.string.settings_privacy_tap_when)),
    PrivacyFlow(Txt.s(Res.string.settings_privacy_web_what), Txt.s(Res.string.settings_privacy_web_to),
        Txt.s(Res.string.settings_privacy_web_when)),
    PrivacyFlow(Txt.s(Res.string.settings_privacy_diag_what), Txt.s(Res.string.settings_privacy_diag_to),
        Txt.s(Res.string.settings_privacy_diag_when)),
    // plan sync with the web planner (plansync/HttpMailbox → plan.onpath.earth/sync/v1, only when paired)
    PrivacyFlow(Txt.s(Res.string.settings_privacy_sync_what), Txt.s(Res.string.settings_privacy_sync_to),
        Txt.s(Res.string.settings_privacy_sync_when)),
)

/**
 * About: logo, version, licence; then the links to the website (AboutLinks), the full credits (the map's
 * "© OpenStreetMap" opens this page) and the licences.
 */
@Composable
private fun AboutSection(version: String, devTools: Boolean = false, onUnlockDevTools: () -> Unit = {}) {
    var taps by remember { mutableStateOf(0) }
    Section(SettingsPage.ABOUT.title) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // big logo + name on top (owner 2026-11-07)
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                OnPathLogo(80.dp)
                Spacer(Modifier.height(12.dp))
                Text(Brand.NAME, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold,
                    letterSpacing = Brand.Tracking), color = OnPathColors.Walnut)
            }
            // tapped 7 times: Developer tools in Diagnostics (the Android convention); remembered
            Text("${Brand.NAME} $version", textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)
                    .clickable(enabled = !devTools, indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                        taps++
                        if (DevToolsUnlock.unlocked(taps)) onUnlockDevTools()
                    })
            if (devTools && taps > 0) Text(str(Res.string.settings_about_devtools_on), style = MaterialTheme.typography.bodySmall,
                color = OnPathColors.Green)
            Text(str(Res.string.settings_about_licence), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

/** About → the full credits, under the links. */
@Composable
private fun CreditsSection() {
    Section(str(Res.string.settings_about_credits)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(str(Res.string.settings_about_credits), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            MAP_CREDITS.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * About → Licences (A-020): every open-source part with version and licence, the texts from the APK's assets – all
 * offline (data/Licences). Closed: one row; open: the ODbL line, then one block per project.
 */
@Composable
private fun LicencesSection(given: List<com.onpath.app.data.Licences.Entry>?, initiallyOpen: Boolean) {
    var open by remember { mutableStateOf(initiallyOpen) }
    val assets = androidx.compose.ui.platform.LocalContext.current.assets
    Section(str(Res.string.settings_licences)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { open = !open }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(str(Res.string.settings_licences), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(str(Res.string.settings_licences_sum), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        if (open) {
            val entries = remember(given) { given ?: com.onpath.app.data.Licences.load(assets) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(str(Res.string.settings_licences_lead), style = MaterialTheme.typography.bodySmall)
                Text(str(Res.string.settings_licences_odbl), style = MaterialTheme.typography.bodySmall)
                entries.forEach { e ->
                    HorizontalDivider()
                    LicenceEntry(e) { asset -> com.onpath.app.data.Licences.text(assets, asset) }
                }
            }
        }
    }
}

@Composable
private fun LicenceEntry(e: com.onpath.app.data.Licences.Entry, read: (String) -> String?) {
    var shown by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(e.project.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        Text(e.project.licence.title, style = MaterialTheme.typography.bodySmall, color = OnPathColors.Green)
        Text(e.libraries.joinToString(", "), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        e.project.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (e.project.url.isNotEmpty()) Text(e.project.url, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        e.project.text?.let { asset ->
            TextButton(onClick = { shown = !shown }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(str(if (shown) Res.string.settings_licences_text_hide else Res.string.settings_licences_text))
            }
            if (shown) {
                val text = remember(asset) { read(asset) }
                text?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
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
    Surface(shape = OnPathShapes.Card, color = OnPathColors.CardPaper, modifier = Modifier.warmShadow(OnPathShadow.LOW, OnPathShapes.Card)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            if (LocalSectionTitle.current) Text(title, style = MaterialTheme.typography.titleSmall, color = OnPathColors.Green,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            content()
        }
    }
}

/** A switch row; [indent] = depends on the switch above it, [enabled] false = greyed (38 %) and not tappable. */
@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, indent: Boolean = false,
                      onChange: (Boolean) -> Unit) {
    val a = if (enabled) 1f else 0.38f
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(start = if (indent) 32.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = a))
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = a))
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
