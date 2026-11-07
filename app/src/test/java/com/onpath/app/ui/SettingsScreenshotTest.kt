package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.cells.CellId
import com.onpath.app.data.AppSettings
import com.onpath.app.data.DataFiles
import com.onpath.app.debug.Problems
import com.onpath.app.poi.PoiGroup
import com.onpath.app.poi.PoiKinds
import com.onpath.strings.*
import org.junit.Rule
import org.junit.Test

/** Settings as a list of topics + one page per topic, Offline maps → Clean up / Clear all. */
class SettingsScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420)),
        showSystemUi = false,
    )

    private val zone = java.util.TimeZone.getDefault()
    @org.junit.Before fun utc() = java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
    @org.junit.After fun back() = java.util.TimeZone.setDefault(zone)

    private val now = 1_761_150_000_000L // 22 Oct 2025, 16:20 UTC
    private val areas = listOf(
        OfflineArea("10-543-361", "Meran", 41_400_000, "19 Oct 2026"),
        OfflineArea("10-544-361", "near Bozen", 52_100_000, "19 Oct 2026"),
        OfflineArea("10-543-362", "Ulten", 37_800_000, "12 Oct 2026", status = "removed after the tour"),
    )
    private val problems = listOf(
        Problems.Entry(Problems.Level.ERROR, "cells", "Map areas could not be joined into the map", null, now - 3_600_000, now - 3_600_000, 1),
        Problems.Entry(Problems.Level.WARN, "routing", "Routing data sizes not readable; map sizes only", null, now - 7_200_000, now - 600_000, 3),
    )

    @Composable
    private fun Screen(page: SettingsPage?, offline: OfflineMapsUi = OfflineMapsUi(true, areas, wifiOnly = false,
        waiting = WaitingDownloads(2, 0, 60L * 1_048_576)), devTools: Boolean = false, seenMs: Long = now,
        demo: DemoUi? = null, sync: SyncUi = SyncUi(paired = true, peer = "Chrome on Linux", lastOkMs = now - 60_000, sinceMs = now - 3 * 86_400_000L),
        phone: com.onpath.app.setup.PhoneState? = null, licencesOpen: Boolean = false, problemsOpen: Boolean = false,
        onTheWay: Boolean = false) = OnPathTheme {
        SettingsScreen(
            AppSettings(hiddenPoiKinds = PoiKinds.defaultHidden, offRouteM = 40), {},
            listOf(DataFiles.Check("base.pmtiles", true, "Base map"), DataFiles.Check("pois.geojson", false, "Points of interest")),
            "/storage/emulated/0/Android/data/com.onpath.app/files/onpath", "0.10.0 (build 1000)", {},
            page = page,
            initiallyExpanded = setOf(PoiGroup.FOOD),
            update = AppViewModel.UpdateState.UpToDate,
            diagnostics = true,
            problems = problems,
            mapCells = MapCellsDebug(listOf(Triple("10-543-361", "2026-10-19-s2", 41_400_000L)), emptyList(), "store generation 7", {}, {}),
            offlineMaps = offline,
            backup = BackupUi("Documents/OnPath", now - 3 * 3_600_000, 28, null, custom = false),
            mapPackage = DataFiles.PackageCheck("Südtirol", listOf(10.70, 46.30, 11.60, 46.85), "2026-10-18T00:00:00Z", emptyList()),
            devToolsOpen = devTools,
            problemsOpen = problemsOpen,
            devTools = devTools,
            diagnosticsSeenMs = seenMs,
            mapServer = "test · https://maps.example/v1 · key set (…28ec)",
            demo = demo,
            sync = sync,
            phoneChecks = phone?.let { com.onpath.app.setup.PhoneCheck.all(it) },
            licences = com.onpath.app.data.Licences.entries(listOf("org.maplibre.gl:android-sdk:13.7.1",
                "org.maplibre.gl:android-sdk-geojson:6.0.1", "androidx.compose.ui:ui:1.7.6", "androidx.core:core:1.15.0",
                "org.jetbrains.kotlin:kotlin-stdlib:2.2.10", "com.squareup.okhttp3:okhttp:4.12.0")),
            licencesOpen = licencesOpen,
            now = now,
            onTheWay = onTheWay,
        )
    }

    /** Navigate → ⚙ (owner 2026-11-07): the Navigation page, then Karte · POIs · Tempo · Offline-Karten, "Alle Einstellungen ›". */
    fun onTheWay() = paparazzi.snapshot { Screen(null, phone = phoneOk, onTheWay = true) }
    /** The same with the location off: "Handy einrichten" at the top of the Navigation page (red, "Fehlt: …"). */
    fun onTheWayPhoneMissing() = paparazzi.snapshot { Screen(null, phone = phoneOk.copy(locationOn = false), onTheWay = true) }

    fun list() = paparazzi.snapshot { Screen(null) }
    /** A serious problem not looked at yet: the Diagnostics line is red until the page is opened (owner). */
    @Test fun listNewProblem() = paparazzi.snapshot { Screen(null, seenMs = 0L) }
    // Phone setup in the list (owner 2026-10-30)
    private val phoneOk = com.onpath.app.setup.PhoneState(sdk = 35, manufacturer = "Google", location = com.onpath.app.setup.LocationGrant.PRECISE,
        vibrationFelt = com.onpath.app.setup.Felt.YES)
    /** A tour needs something (location off): Phone setup on top, red mark, "1 item needed for tours". */
    fun listPhoneNeeded() = paparazzi.snapshot { Screen(null, phone = phoneOk.copy(locationOn = false, notificationsOn = false)) }
    /** Only recommended items missing: Phone setup at its place in "App", amber "2 recommended". */
    fun listPhoneRecommended() = paparazzi.snapshot { Screen(null, phone = phoneOk.copy(notificationsOn = false, backgroundRestricted = true)) }
    /** All set up: at its place, grey "Everything set up". */
    @Test fun listPhoneOk() = paparazzi.snapshot { Screen(null, phone = phoneOk) }
    fun pace() = paparazzi.snapshot { Screen(SettingsPage.PACE) }
    @Test fun poi() = paparazzi.snapshot { Screen(SettingsPage.POI) }
    fun map() = paparazzi.snapshot { Screen(SettingsPage.MAP) }
    fun navigation() = paparazzi.snapshot { Screen(SettingsPage.NAVIGATION) }
    /** Location off (a tour needs it) + battery restricted: "Handy einrichten" at the top, red, "Fehlt: …" (owner 2026-11-06). */
    fun navigationPhoneMissing() = paparazzi.snapshot {
        Screen(SettingsPage.NAVIGATION, phone = phoneOk.copy(locationOn = false, backgroundRestricted = true))
    }
    /** A value row's choice as a bottom sheet (drawn in place – Paparazzi draws no sheet window): screen while cycling. */
    fun navigationChoiceSheet() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter)) {
                    val b = listOf(
                        Choice(com.onpath.app.data.BikeScreen.ALWAYS, str(Res.string.settings_nav_bike_screen_always),
                            str(Res.string.settings_nav_bike_screen_always_sub)),
                        Choice(com.onpath.app.data.BikeScreen.SMART, str(Res.string.settings_nav_bike_screen_smart),
                            str(Res.string.settings_nav_bike_screen_smart_sub)),
                        Choice(com.onpath.app.data.BikeScreen.PHONE, str(Res.string.settings_nav_bike_screen_phone),
                            str(Res.string.settings_nav_bike_screen_phone_sub)))
                    ChoiceSheet(str(Res.string.settings_nav_bike_screen), b, com.onpath.app.data.BikeScreen.SMART,
                        onPick = {}, onDismiss = {}, inline = true)
                }
            }
        }
    }
    fun offlineMaps() = paparazzi.snapshot { Screen(SettingsPage.OFFLINE_MAPS) }
    @Test fun backup() = paparazzi.snapshot { Screen(SettingsPage.BACKUP) }
    @Test fun privacy() = paparazzi.snapshot { Screen(SettingsPage.PRIVACY) }
    @Test fun updates() = paparazzi.snapshot { Screen(SettingsPage.UPDATES) }
    /** Folded: one line "2 entries, 1 error ▸" (owner 2026-11-02). */
    @Test fun diagnostics() = paparazzi.snapshot { Screen(SettingsPage.DIAGNOSTICS) }
    /** Unfolded after a tap: the entries and "Clear problem list". */
    @Test fun diagnosticsProblemsOpen() = paparazzi.snapshot { Screen(SettingsPage.DIAGNOSTICS, problemsOpen = true) }
    @Test fun diagnosticsDeveloperTools() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1024, screenHeight = 4200, xdpi = 428, ydpi = 428, density = Density.create(420)))
        paparazzi.snapshot { Screen(SettingsPage.DIAGNOSTICS, devTools = true) }
    }
    /** Developer tools → Demo location while a demo ride runs (speed, stand still, stop). */
    @Test fun diagnosticsDemoRunning() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1024, screenHeight = 4200, xdpi = 428, ydpi = 428, density = Density.create(420)))
        paparazzi.snapshot {
            Screen(SettingsPage.DIAGNOSTICS, devTools = true, demo = DemoUi(com.onpath.app.recording.DemoLocation.State(
                com.onpath.app.recording.DemoLocation.Kind.RIDE, "planned route", factor = 5, paused = false, alongM = 3400.0,
                lengthM = 12_600.0, ended = false), "planned route"))
        }
    }
    @Test fun about() = paparazzi.snapshot { Screen(SettingsPage.ABOUT) }
    /** About → Licences open (A-020): the ODbL line, MapLibre, BRouter (onpath1), glyphs, icons, the libraries. */
    fun aboutLicences() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1024, screenHeight = 5200, xdpi = 428, ydpi = 428, density = Density.create(420)))
        paparazzi.snapshot { Screen(SettingsPage.ABOUT, licencesOpen = true) }
    }
    /** Privacy (A-020) in full: device backup, IP line, update check, search, Google short link, address service. */
    fun privacyFull() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1024, screenHeight = 4600, xdpi = 428, ydpi = 428, density = Density.create(420)))
        paparazzi.snapshot { Screen(SettingsPage.PRIVACY) }
    }

    // Browser sync (plan sync with the web planner, A-007)
    /** Not paired: how to pair (the system camera opens the pairing link). */
    fun syncNotPaired() = paparazzi.snapshot { Screen(SettingsPage.SYNC, sync = SyncUi()) }
    /** A pairing link was opened: the check digits, Pair / Cancel. */
    fun syncAsk() = paparazzi.snapshot { Screen(SettingsPage.SYNC, sync = SyncUi(askCheck = "395 514")) }
    /** Paired: the browser's name, "Last synced: 1 min ago", Unpair – no Sync now (it syncs every minute by itself). */
    fun syncPaired() = paparazzi.snapshot { Screen(SettingsPage.SYNC) }
    /** Paired, the last pass found items from a newer app (amber line). */
    @Test fun syncNewerApp() = paparazzi.snapshot {
        Screen(SettingsPage.SYNC, sync = SyncUi(paired = true, peer = "Firefox on Windows", lastOkMs = now - 86_400_000,
            problem = com.onpath.app.plansync.PlanSync.Problem.NewerApp(2)))
    }
    /** Paired, offline (grey line), the last good pass 25 min ago. */
    fun syncOffline() = paparazzi.snapshot {
        Screen(SettingsPage.SYNC, sync = SyncUi(paired = true, peer = null, lastOkMs = now - 25 * 60_000,
            problem = com.onpath.app.plansync.PlanSync.Problem.Offline))
    }

    /** Unpaired in the browser (field bug 2026-10-31): the grey line, the steps + Open camera to pair again, Unpair. */
    fun syncUnpairedInBrowser() = paparazzi.snapshot {
        Screen(SettingsPage.SYNC, sync = SyncUi(paired = true, peer = null, lastOkMs = now - 60_000,
            browser = com.onpath.app.plansync.BrowserLink.UNPAIRED))
    }

    /** Offline maps → Clean up: what goes and what stays, before anything is removed. */
    @Test fun offlineMapsCleanUp() = paparazzi.snapshot {
        Screen(SettingsPage.OFFLINE_MAPS, OfflineMapsUi(true, areas, wifiOnly = false,
            cleanup = CleanupPreview(List(23) { CellId(500 + it, 361) }, 410L * 1_048_576,
                listOf("your current plan", "saved routes", "running tour", "where you are now"))))
    }

    /** Offline maps → Clear all: the second confirmation. */
    @Test fun offlineMapsClearAll() = paparazzi.snapshot {
        OnPathTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.padding(12.dp)) {
                    OfflineMapsSection(OfflineMapsUi(true, areas, wifiOnly = false), OfflineMapsCallbacks(), initialClearAll = true)
                }
            }
        }
    }

    /** During a tour "Clear all" is off with a short reason. */
    @Test fun offlineMapsDuringATour() = paparazzi.snapshot {
        Screen(SettingsPage.OFFLINE_MAPS, OfflineMapsUi(true, areas, wifiOnly = false,
            clearAllBlocked = "Not during a tour – the map stays as it is until you end it."))
    }

    /** After Clean up: a plain line on the page (no message box on the map), until the page is left. */
    @Test fun offlineMapsAfterCleanUp() = paparazzi.snapshot {
        Screen(SettingsPage.OFFLINE_MAPS, OfflineMapsUi(true, areas.take(2), wifiOnly = false,
            result = OfflineResult.removed(37, 450L * 1_048_576, afterTour = false)))
    }
}
