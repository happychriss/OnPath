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
        demo: DemoUi? = null) = OnPathTheme {
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
            devTools = devTools,
            diagnosticsSeenMs = seenMs,
            mapServer = "test · https://maps.example/v1 · key set (…28ec)",
            demo = demo,
            now = now,
        )
    }

    @Test fun list() = paparazzi.snapshot { Screen(null) }
    /** A serious problem not looked at yet: the Diagnostics line is red until the page is opened (owner). */
    @Test fun listNewProblem() = paparazzi.snapshot { Screen(null, seenMs = 0L) }
    @Test fun pace() = paparazzi.snapshot { Screen(SettingsPage.PACE) }
    @Test fun poi() = paparazzi.snapshot { Screen(SettingsPage.POI) }
    @Test fun map() = paparazzi.snapshot { Screen(SettingsPage.MAP) }
    @Test fun navigation() = paparazzi.snapshot { Screen(SettingsPage.NAVIGATION) }
    @Test fun offlineMaps() = paparazzi.snapshot { Screen(SettingsPage.OFFLINE_MAPS) }
    @Test fun backup() = paparazzi.snapshot { Screen(SettingsPage.BACKUP) }
    @Test fun privacy() = paparazzi.snapshot { Screen(SettingsPage.PRIVACY) }
    @Test fun updates() = paparazzi.snapshot { Screen(SettingsPage.UPDATES) }
    @Test fun diagnostics() = paparazzi.snapshot { Screen(SettingsPage.DIAGNOSTICS) }
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
