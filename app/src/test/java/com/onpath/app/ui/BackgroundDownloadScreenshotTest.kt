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
import com.onpath.strings.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Form B, "ich kann weitermachen" (owner 2026-11-19 „einfach nur Prozent, muss aber passen für die gesamte Aktion“):
 * the small sign beside the map – a ring and ONE percent over the whole action (Plan, Navigate, Settings → Offline maps),
 * its details sheet (one bar, one line per load with where it stands, the finished ones "fertig"). EN, DE, DE at the
 * largest font: the subclasses below.
 */
abstract class BackgroundDownloadScreenshotTest {

    @get:Rule
    open val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420)),
        showSystemUi = false,
    )

    private val mb = 1_048_576L
    // getters: the names are read in the test (after @Before set the language), not when the class is made
    /** A multi-day tour's maps: stage 1 there, stage 2 loads, stage 3 is prepared on the server – one action, 34 %. */
    private val ui: BackgroundDownloadUi get() {
        val p = BackgroundProgress(com.onpath.app.domain.ActionPercent())
        fun g(stages: List<Pair<String, LoadPhase>>) = LoadNames.groups(stages.map { (n, ph) -> LoadEntry(LoadWhy.ROUTE, n, bytes = 9 * mb, phase = ph) })
        fun item(k: String, f: Double) = com.onpath.app.domain.ActionPercent.Item(k, 9 * mb, f)
        p.update(listOf(item("a", 0.0), item("b", 0.0), item("c", 0.0)), 3, 0, groups = g(listOf("Meran" to LoadPhase.LOADING,
            "Brixen" to LoadPhase.PREPARING, "Klausen" to LoadPhase.PREPARING)))
        return p.update(listOf(item("b", 0.02), item("c", 0.0)), 2, 0, groups = g(listOf("Brixen" to LoadPhase.LOADING,
            "Klausen" to LoadPhase.PREPARING)))!!
    }

    /** Plan: the small light blue sign under ☰ – the ring and „34 %“, nothing else. */
    @Test fun planChip() = planWith(ui)

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

    /** Tap on the sign: the ONE bar, one line per load (fertig · lädt · wird vorbereitet), Cancel all (red) · Close. */
    @Test fun details() = sheet(ui)

    /** The action's number is the same in the sign, its details and the notification; finished loads stay as "fertig". */
    @Test fun oneNumberOverTheAction() {
        val u = ui
        org.junit.Assert.assertEquals(34, u.percent)
        org.junit.Assert.assertEquals(listOf(Txt.s(Res.string.maps_phase_done), LoadPhase.LOADING.text, LoadPhase.PREPARING.text), u.rows.map { it.state })
    }

    /** The bottom sheet as ModalBottomSheet draws it (white, 28 dp top corners, drag handle, scrim) – Paparazzi has no popups. */
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    private fun sheet(u: BackgroundDownloadUi) = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f)))
                androidx.compose.material3.Surface(Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = OnPathColors.Paper) {
                    Column {
                        androidx.compose.material3.BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                        BackgroundDownloadDetailsContent(u, onCancelAll = {}, onClose = {})
                    }
                }
            }
        }
    }

    /** Settings → Offline maps: the same sign (34 %) and "Waiting to download: 16 areas · about 380 MB" with Cancel all. */
    @Test fun settingsWaiting() = paparazzi.snapshot {
        OnPathTheme {
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                Column(Modifier.padding(12.dp)) {
                    OfflineMapsSection(OfflineMapsUi(true, listOf(OfflineArea("10-543-361", "Meran", 10_400_000, "19 Oct 2026")),
                        wifiOnly = false, loading = 16, waiting = WaitingDownloads(16, 2, 380L * 1_048_576), action = 34), OfflineMapsCallbacks())
                }
            }
        }
    }

    private val newer: BackgroundDownloadUi get() {
        val groups = LoadNames.groups(listOf(
            LoadEntry(LoadWhy.REFRESH, town = "Meran", bytes = 8 * mb, phase = LoadPhase.PREPARING),
            LoadEntry(LoadWhy.REFRESH, town = "Meran", bytes = 7 * mb, phase = LoadPhase.PREPARING),
            LoadEntry(LoadWhy.REFRESH, town = "Bozen", bytes = 7 * mb),
        ))
        return BackgroundProgress(com.onpath.app.domain.ActionPercent()).update(
            listOf(com.onpath.app.domain.ActionPercent.Item("a", 22 * mb, 0.34)), 3, 0, newer = 3, newerEtaMin = 4, groups = groups)!!
    }

    /** Its details: newer version, being prepared, about N min. */
    @Test fun detailsNewerMap() = sheet(newer)
}

/** The same in German. */
class BackgroundDownloadGermanScreenshotTest : BackgroundDownloadScreenshotTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}

/** The phone's largest font (2.0), German: the sign still a small pill, the sheet's lines and buttons wrap, never cut. */
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
