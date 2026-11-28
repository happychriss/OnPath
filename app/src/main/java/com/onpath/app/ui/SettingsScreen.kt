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
import androidx.compose.material.icons.filled.Check
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
    /** Developer tools → „Tourvorschläge“ (round trips, on by default – the off-switch); null = hidden. */
    suggestTest: SuggestTestUi? = null,
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
    /** ← on a page (the app: [SettingsSlice.settingsBack] – the pairing window opened from outside closes); null = [onPage] up. */
    onUp: (() -> Unit)? = null,
) {
    // every page this phone shows (sub pages and aliases too – the on-the-way list and the rows inside pages use them)
    val available = SettingsPage.entries.filter {
        when (it) {
            SettingsPage.OFFLINE_MAPS -> offlineMaps != null
            SettingsPage.BACKUP -> backup != null
            SettingsPage.SYNC -> sync != null
            SettingsPage.BACKUP_SYNC -> backup != null
            SettingsPage.UPDATES -> update != null
            SettingsPage.DIAGNOSTICS -> diagnostics
            SettingsPage.PHONE -> phoneChecks != null
            else -> true
        }
    }
    // the page drawn: an alias shows the page that holds it ([SettingsPage.landing])
    val shown = page?.landing
    Surface(Modifier.fillMaxSize(), color = SettingsGround) {
        Column(Modifier.systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (page != null) onUp?.invoke() ?: onPage(page.up) else onBack() }, Modifier.size(56.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, str(Res.string.common_back))
                }
                Text(if (shown == SettingsPage.SYNC) pairWindowTitle(sync) else shown?.title ?: str(Res.string.common_settings), style = MaterialTheme.typography.titleLarge)
            }
            note?.let { NoticeLine(it, Modifier.padding(horizontal = 24.dp)) }
            if (page == null && onTheWay) CompositionLocalProvider(LocalSectionTitle provides false) {
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { LocalSettingsFirst.current?.invoke() // live sharing's rows ([LiveShareLayer]) – first on the way
                        // V1 (Chriss 2026-11-28 10:59, picture einstellungen-unterwegs): only what one changes on the way
                        OnTheWaySection(settings, onChange, activity, onPhoneSetup = { onPage(SettingsPage.PHONE) },
                            phoneChecks = phoneChecks, phone = phone)
                    }
                    val more = SettingsSummary.onTheWay(available)
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
                    // six topics in two groups (owner 2026-11-27): what the user sets, then the smaller "App" group;
                    // Navigation on top (no heading) while Phone setup – a row on it – has a step open (owner 2026-10-30)
                    SettingsSummary.groups(available, phoneChecks).forEachIndexed { gi, (kind, group) ->
                        val app = kind == TopicGroup.APP
                        // the browser's own card above "Your settings" (owner 2026-11-28, P1): status + the pairing window
                        val card = kind == TopicGroup.YOURS && sync != null
                        if (card) sync?.let { sy -> item { PairTopicCard(sy, top = if (gi > 0) 20.dp else 4.dp) { onPage(SettingsPage.SYNC) } } }
                        if (kind != TopicGroup.URGENT) item {
                            Column { SettingsGroupHeader(str(if (app) Res.string.settings_group_app else Res.string.settings_group_yours),
                                top = if (gi > 0 || card) 20.dp else 4.dp)
                                Spacer(Modifier.height(8.dp)) }
                        } else item { Spacer(Modifier.height(4.dp)) }
                        item {
                            SettingsCard {
                                run {
                                    group.forEachIndexed { i, t ->
                                        if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp))
                                        val s = when (t) {
                                            SettingsPage.PLAN -> SettingsSummary.plan(settings, activity)
                                            SettingsPage.MAP -> SettingsSummary.mapPage(settings, offlineMaps)
                                            SettingsPage.NAVIGATION -> SettingsSummary.navigationPage(settings, phoneChecks)
                                            SettingsPage.BACKUP -> backup?.let { SettingsSummary.backup(it, now) } ?: Summary("")
                                            SettingsPage.PRIVACY -> SettingsSummary.privacy
                                            else -> SettingsSummary.aboutPage(version, update, problems.takeIf { diagnostics }, diagnosticsSeenMs)
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
                    when (shown!!) {
                        // Plan (owner 2026-11-27): your pace, following marked trails, the vibration while planning
                        SettingsPage.PLAN, SettingsPage.PACE -> {
                            item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsGroupHeader(SettingsPage.PACE.title)
                                PaceSection(settings, onChange, activity) // PaceSettings.kt
                            } }
                            item { SettingsCard {
                                MarkedTrailsRow(settings, onChange, activity) // PaceSettings.kt
                                SwitchRow(str(Res.string.settings_map_vibrate), null, settings.planVibrate) { onChange(settings.copy(planVibrate = it)) }
                            } }
                        }
                        // Map: its switches, then Points of interest and Offline maps as rows to their own pages
                        SettingsPage.MAP -> {
                            item { MapSection(settings, onChange, themes) }
                            item {
                                SettingsCard {
                                    TopicRow(SettingsPage.POI, SettingsSummary.poi(settings.hiddenPoiKinds)) { onPage(SettingsPage.POI) }
                                    if (offlineMaps != null) {
                                        HorizontalDivider(Modifier.padding(start = 64.dp))
                                        TopicRow(SettingsPage.OFFLINE_MAPS, SettingsSummary.offlineMaps(offlineMaps)) { onPage(SettingsPage.OFFLINE_MAPS) }
                                    }
                                }
                            }
                        }
                        SettingsPage.POI -> item { PoiSection(settings, onChange, initiallyExpanded) }
                        SettingsPage.OFFLINE_MAPS -> if (offlineMaps != null) item { OfflineMapsSection(offlineMaps, offlineMapsCb) }
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
                        // the pairing window „Mit Browser verbinden“ (owner 2026-11-28, A-018): only the browser part
                        SettingsPage.SYNC -> if (sync != null) pairWindow(sync, syncCb, now)
                        // Backup (owner 2026-11-28: the browser has its own window; „Sicherung & Browser“ is an alias of it)
                        SettingsPage.BACKUP_SYNC, SettingsPage.BACKUP -> if (backup != null) item { BackupSection(backup, backupCb) }
                        SettingsPage.DIAGNOSTICS -> if (diagnostics) {
                            item { DiagnosticsSection(diagnosticsBusy, onExportDiagnostics, problems, onClearProblems, language.active, routingInfo, problemsOpen) }
                            if (devTools) item {
                                DeveloperTools(devToolsOpen, mapServer, mapCells, dataBusy, onDownloadRouting, onImportData, checks, mapPackage, dataDir,
                                    onTimeFormulas, testStyle, language, demo, suggestTest)
                            }
                        }
                        SettingsPage.PRIVACY -> item { PrivacySection() }
                        // About OnPath (owner 2026-11-27, slimmed): one card with logo, version (7 taps → developer tools) and the
                        // update state with its one button; then Help ↗, Diagnostics ›, Legal & credits ›
                        SettingsPage.ABOUT, SettingsPage.UPDATES -> {
                            item {
                                AboutSection(version, devTools, onUnlockDevTools) {
                                    if (update != null) UpdateSectionContent(update, version, onCheckUpdate, onInstallUpdate, compact = true)
                                }
                            }
                            item {
                                SettingsCard {
                                    HelpLinkRow()
                                    if (diagnostics) {
                                        HorizontalDivider(Modifier.padding(start = 64.dp))
                                        TopicRow(SettingsPage.DIAGNOSTICS, SettingsSummary.diagnostics(problems, diagnosticsSeenMs), small = true) {
                                            onPage(SettingsPage.DIAGNOSTICS)
                                        }
                                    }
                                    HorizontalDivider(Modifier.padding(start = 64.dp))
                                    TopicRow(SettingsPage.LEGAL, SettingsSummary.legal, small = true) { onPage(SettingsPage.LEGAL) }
                                }
                            }
                        }
                        // Legal & credits: the website's pages, the source, the credits and the licences
                        SettingsPage.LEGAL -> {
                            item { LegalLinksSection() }
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
        SettingsPage.PLAN -> Icons.Outlined.Route
        SettingsPage.BACKUP_SYNC -> Icons.Outlined.SaveAlt
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
        SettingsPage.LEGAL -> Icons.Outlined.Gavel
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
        // a topic that blocks tours (Phone setup, a row of Navigation): the red ✗-mark of the phone checks instead of its icon
        val phoneRow = t == SettingsPage.PHONE || t == SettingsPage.NAVIGATION
        val blocking = s.attention == Summary.Attention.PROBLEM && phoneRow
        // steps open (owner 2026-11-08): a small amber dot on the icon, the row on top with "N steps open"
        val open = s.attention == Summary.Attention.WARNING && phoneRow
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

/** Map: contours, hill shading, map direction (Plan and Navigate); the vibration while planning is on the Plan page. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MapSection(settings: AppSettings, onChange: (AppSettings) -> Unit, themes: List<String>) {
    SettingsCard {
        // value rows (owner 2026-11-06 23:05): the choice opens as a sheet ([ChoiceRow])
        if (themes.size > 1) ChoiceRow(str(Res.string.settings_map_style), null,
            themes.map { Choice(it, com.onpath.app.map.StyleLoader.label(it)) }, settings.mapTheme,
            onPick = { onChange(settings.copy(mapTheme = it)) })
        SwitchRow(str(Res.string.settings_map_contours), null, settings.contours) { onChange(settings.copy(contours = it)) }
        SwitchRow(str(Res.string.settings_map_hillshade), null, settings.hillshade) { onChange(settings.copy(hillshade = it)) }
        ChoiceRow(str(Res.string.settings_map_direction), null,
            listOf(Choice(true, str(Res.string.settings_map_travel)), Choice(false, str(Res.string.settings_map_north))),
            settings.navHeadingUp, onPick = { onChange(settings.copy(navHeadingUp = it)) })
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
        // „Akku sparen“ first (Chriss 2026-11-28, picture akku-varianten A, R-10): reachable during the tour via ⚙
        SettingsGroupHeader(str(Res.string.settings_group_battery))
        SettingsCard {
            BatterySaveSwitch(settings, onChange)
        }
        SettingsGroupHeader(str(Res.string.settings_group_offroute))
        SettingsCard {
            SwitchRow(str(Res.string.settings_nav_offroute), null, warning) {
                onChange(settings.copy(offRouteWarning = it))
            }
            ChoiceRow(str(Res.string.settings_nav_warn_after), null,
                AppSettings.OFF_ROUTE_CHOICES.map { Choice(it, str(Res.string.settings_nav_meters, it)) }, settings.offRouteM,
                onPick = { onChange(settings.copy(offRouteM = it)) }, enabled = warning, indent = true)
            SwitchRow(str(Res.string.settings_nav_vibrate_off), null,
                settings.offRouteVibrate && warning, enabled = warning, indent = true) {
                onChange(settings.copy(offRouteVibrate = it))
            }
        }
        // how the turns are told – either / or (owner 2026-11-01): off / vibration / voice; one test of the chosen kind
        SettingsGroupHeader(str(Res.string.settings_group_junctions))
        SettingsCard {
            JunctionGuideRow(settings, onChange)
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
    // variant B (2026-11-27): an outlined button on its own tile – it looked like half a row before
    Box(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) {
        androidx.compose.material3.OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 40.dp),
            contentPadding = PaddingValues(start = 14.dp, end = 18.dp)) {
            Icon(Icons.Filled.PlayArrow, null, tint = OnPathColors.Green, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, color = OnPathColors.Green)
        }
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
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = OnPathColors.Ink)
        }
        Text(str(Res.string.settings_nav_lock_sub), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.material3.Slider(
            value = settings.navHoldMs.toFloat(),
            onValueChange = { v -> val ms = hs.snap(v); if (ms != settings.navHoldMs) onChange(settings.copy(navHoldMs = ms)) },
            valueRange = 0f..hs.MAX_MS.toFloat(), steps = hs.SLIDER_STEPS,
            colors = settingsSliderColors(),
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
internal fun BikeScreenRow(settings: AppSettings, onChange: (AppSettings) -> Unit) {

    val options = listOf(
        Choice(com.onpath.app.data.BikeScreen.ALWAYS, str(Res.string.settings_nav_bike_screen_always), str(Res.string.settings_nav_bike_screen_always_sub)),
        Choice(com.onpath.app.data.BikeScreen.SMART, str(Res.string.settings_nav_bike_screen_smart), str(Res.string.settings_nav_bike_screen_smart_sub)),
        Choice(com.onpath.app.data.BikeScreen.PHONE, str(Res.string.settings_nav_bike_screen_phone), str(Res.string.settings_nav_bike_screen_phone_sub)))
    // the menu only the words: the chosen one's line already stands under the title (variant B, 2026-11-27)
    ChoiceRow(str(Res.string.settings_nav_bike_screen), options.first { it.value == settings.bikeScreen }.line, options,
        settings.bikeScreen, onPick = { onChange(settings.copy(bikeScreen = it)) }, menuLines = false)
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

/** False inside a topic page: the page's top bar already names the topic, the card does not repeat it. */
internal val LocalSectionTitle = compositionLocalOf { true }

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    // on a settings page (no section titles there) flat like the tiles of [SettingsCard] – variant B has no shadows
    val settingsPage = !LocalSectionTitle.current
    Surface(shape = OnPathShapes.Card, color = OnPathColors.CardPaper,
        modifier = if (settingsPage) Modifier else Modifier.warmShadow(OnPathShadow.LOW, OnPathShapes.Card)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            if (LocalSectionTitle.current) Text(title, style = MaterialTheme.typography.titleSmall, color = OnPathColors.Green,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            content()
        }
    }
}

/** A switch row; [indent] = depends on the switch above it, [enabled] false = greyed (38 %) and not tappable. */
@Composable
internal fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, indent: Boolean = false,
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
        SettingsSwitch(checked, enabled)
    }
}

/**
 * The settings' switch (variant B, 2026-11-27): the accent green with a ✓ in the thumb, Android's standard – the dark
 * Walnuss tracks were the heaviest things on the page.
 */
@Composable
internal fun SettingsSwitch(checked: Boolean, enabled: Boolean = true) =
    Switch(checked = checked, onCheckedChange = null, enabled = enabled,
        thumbContent = if (checked) { { Icon(Icons.Filled.Check, null, Modifier.size(androidx.compose.material3.SwitchDefaults.IconSize)) } } else null,
        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = OnPathColors.Green,
            checkedThumbColor = androidx.compose.ui.graphics.Color.White, checkedIconColor = OnPathColors.Green))

/** The settings' slider: the same green as the switches, a light sage for the rest of the track. */
@Composable
internal fun settingsSliderColors() = androidx.compose.material3.SliderDefaults.colors(thumbColor = OnPathColors.Green,
    activeTrackColor = OnPathColors.Green, activeTickColor = androidx.compose.ui.graphics.Color.White,
    inactiveTrackColor = SettingsGround, inactiveTickColor = OnPathColors.Green)

/** „Hinweise an Abzweigungen“ – a value row like every choice (owner 2026-11-27): Aus · Vibration · Stimme in its menu. */
@Composable
internal fun JunctionGuideRow(settings: AppSettings, onChange: (AppSettings) -> Unit) =
    ChoiceRow(str(Res.string.settings_nav_guide), null,
        listOf(Choice(com.onpath.app.data.JunctionGuide.OFF, str(Res.string.settings_nav_guide_off)),
            Choice(com.onpath.app.data.JunctionGuide.VIBRATION, str(Res.string.settings_nav_guide_vibration)),
            Choice(com.onpath.app.data.JunctionGuide.VOICE, str(Res.string.settings_nav_guide_voice))),
        settings.junctionGuide, onPick = { onChange(settings.copy(junctionGuide = it)) })
