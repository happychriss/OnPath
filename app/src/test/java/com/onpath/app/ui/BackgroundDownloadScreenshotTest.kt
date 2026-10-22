package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.routing.Activity
import org.junit.Rule
import org.junit.Test

/** Background downloads, visible and stoppable (owner 2026-10-22): Plan strip, Navigate chip, details, Settings line. */
class BackgroundDownloadScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420)),
        showSystemUi = false,
    )

    private val ui = BackgroundDownloadUi(regions = 3, routingParts = 1, bytes = 24L * 1_048_576, percent = 40)

    /** Plan: thin blue strip just below the search bar. */
    @Test fun planStrip() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                    PlanTopBar({}, PlanMenu(onUpdates = {}), Modifier.fillMaxWidth())
                    BackgroundDownloadStrip(ui, onClick = {}, modifier = Modifier.padding(top = 68.dp, start = 16.dp, end = 16.dp))
                }
            }
        }
    }

    /** Navigate: only a small "⬇ 40 %" chip under the status card. */
    @Test fun navigateChip() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                    NavigateOverlay(NavUi(toGo = ToGo(7_420.0, 512.0, 2.9 * 3600, 12.0, descentM = 310.0), recordedM = 5_320.0,
                        elapsedS = 5025, activity = Activity.HIKE), footer = {}, onSlideToPlan = {}, download = ui)
                }
            }
        }
    }

    /** Tap on the strip / chip: what loads, Cancel all. */
    @Test fun details() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                BackgroundDownloadDialog(ui, onCancelAll = {}, onClose = {})
            }
        }
    }

    /** Settings → Offline maps: "Waiting to download: 16 areas · about 380 MB" with Cancel all. */
    @Test fun settingsWaiting() = paparazzi.snapshot {
        OnPathTheme {
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                Column(Modifier.padding(12.dp)) {
                    OfflineMapsSection(OfflineMapsUi(true, listOf(OfflineArea("10-543-361", "Meran", 10_400_000, "19 Oct 2026")),
                        wifiOnly = false, loading = 16, waiting = WaitingDownloads(16, 2, 380L * 1_048_576)), OfflineMapsCallbacks())
                }
            }
        }
    }

    private val newer = BackgroundDownloadUi(regions = 3, routingParts = 0, bytes = 22L * 1_048_576, percent = 34, newer = 3, newerEtaMin = 4)

    /** A newer version of the areas the user has: the strip is the one indicator (no veil on the map, owner 2026-10-22). */
    @Test fun planStripNewerMap() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                    PlanTopBar({}, PlanMenu(onUpdates = {}), Modifier.fillMaxWidth())
                    BackgroundDownloadStrip(newer, onClick = {}, modifier = Modifier.padding(top = 68.dp, start = 16.dp, end = 16.dp))
                }
            }
        }
    }

    /** Its details: newer version, being prepared, about N min. */
    @Test fun detailsNewerMap() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                BackgroundDownloadDialog(newer, onCancelAll = {}, onClose = {})
            }
        }
    }
}
