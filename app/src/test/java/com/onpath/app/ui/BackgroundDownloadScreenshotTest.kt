package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.routing.Activity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Background downloads, visible and stoppable: the light blue chip with its progress ring (Plan, Navigate; spinning while
 * areas are still prepared), the details sheet, the Settings line. German + largest font: the subclasses below.
 */
open class BackgroundDownloadScreenshotTest {

    @get:Rule
    open val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420)),
        showSystemUi = false,
    )

    private val mb = 1_048_576L
    // getters: the names are read in the test (after @Before set the language), not when the class is made
    private val ui get() = BackgroundDownloadUi(regions = 3, routingParts = 1, bytes = 24L * mb, percent = 34,
        groups = LoadNames.groups(listOf(
            LoadEntry(LoadWhy.POINT, "Bad Waldsee", "Ravensburg", waiting = true, bytes = 9 * mb),
            LoadEntry(LoadWhy.ROUTE, "Gasthof Taser Alm", "Bozen", bytes = 7 * mb, phase = LoadPhase.PREPARING),
            LoadEntry(LoadWhy.ROUTE, "Gasthof Taser Alm", "Bozen", bytes = 6 * mb, phase = LoadPhase.WAITING),
            LoadEntry(null, paths = true, bytes = 2 * mb),
        )))
    private val longName get() = ui.copy(groups = LoadNames.groups(listOf(
        LoadEntry(LoadWhy.ROUTE, "Berggasthof Gampenalm am Hochplateau über Unsere Liebe Frau im Walde", bytes = 9 * mb))))

    /** Plan: small light blue chip under ☰ – ring at 34 %, "Bad Waldsee +1 · 34 %". */
    @Test fun planChip() = planWith(ui)

    /** Nothing has a percent yet (areas being prepared on the server): the ring spins, no "0 %". */
    @Test fun planChipPreparing() = planWith(ui.copy(percent = 0))

    /** A long name is cut with "…" at about half the width; the % always shows. */
    @Test fun planChipLongName() = planWith(longName)

    private fun planWith(u: BackgroundDownloadUi) = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                    PlanTopBar({}, PlanMenu(onUpdates = {}), Modifier.fillMaxWidth())
                    BackgroundDownloadChip(u, onClick = {},
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = MessagePlacement.CHIP_TOP_DP.dp, end = 12.dp))
                }
            }
        }
    }

    /** Navigate: the same small chip under the status card, with the name when it fits. */
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

    /** Tap on the chip: what loads, Cancel all (red) · Close – a bottom sheet over the map (drawn as the sheet looks). */
    @Test fun details() = sheet(ui)

    /** The bottom sheet as ModalBottomSheet draws it (white, 28 dp top corners, drag handle, scrim) – Paparazzi has no popups. */
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    private fun sheet(u: BackgroundDownloadUi) = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f)))
                androidx.compose.material3.Surface(Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = OnPathColors.CardWhite) {
                    Column {
                        androidx.compose.material3.BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                        BackgroundDownloadDetailsContent(u, onCancelAll = {}, onClose = {})
                    }
                }
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

    private val newer get() = BackgroundDownloadUi(regions = 3, routingParts = 0, bytes = 22L * mb, percent = 34, newer = 3, newerEtaMin = 4,
        groups = LoadNames.groups(listOf(
            LoadEntry(LoadWhy.REFRESH, town = "Meran", bytes = 8 * mb, phase = LoadPhase.PREPARING),
            LoadEntry(LoadWhy.REFRESH, town = "Meran", bytes = 7 * mb, phase = LoadPhase.PREPARING),
            LoadEntry(LoadWhy.REFRESH, town = "Bozen", bytes = 7 * mb),
        )))

    /** Its details: newer version, being prepared, about N min. */
    @Test fun detailsNewerMap() = sheet(newer)
}

/** The same in German. */
class BackgroundDownloadGermanScreenshotTest : BackgroundDownloadScreenshotTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}

/** The phone's largest font (2.0), German: the chip still a small pill (name cut, % whole), the sheet's buttons fit. */
class BackgroundDownloadLargeFontTest : BackgroundDownloadScreenshotTest() {
    @get:Rule
    override val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428,
            density = Density.create(420), fontScale = 2.0f),
        showSystemUi = false,
    )
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}
