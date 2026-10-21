package com.onpath.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The modal "maps are being built / loaded" panel (owner 2026-10-21): clear, Cancel only, Wi-Fi / mobile question. */
class MapLoadOverlayTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private val rows = listOf(CellRow("Your point", "building map…", true), CellRow("Area to the east", "waiting", false))
    private val building = CellCardUi.Working("Preparing map for this area – about 6 min",
        "Getting the detailed map because you planned here.", rows, null, null, null, null)
    private val downloading = CellCardUi.Working("Downloading 1 of 2 · 14 MB", "Updating the map for the new map style (small streets).",
        listOf(CellRow("Your point", "ready ✓", true), CellRow("Area to the east", "38 %", false)), null, 0.4f, null, null)
    private val question = CellCardUi.Working("Detailed map ready to download", "Getting the detailed map because you planned here.",
        rows, null, null, MobileQuestion(2, 25L * 1_048_576), null)
    private val offline = CellCardUi.Offline("No internet – the detailed map loads when you are online", null)
    private val failed = CellCardUi.Failed("Could not prepare the map", "The map server did not answer.", retryable = true)

    @Test fun whenTheOverlayShows() {
        assertTrue(building.wantsOverlay())
        assertTrue(question.wantsOverlay())
        assertTrue(offline.wantsOverlay())
        assertTrue(failed.wantsOverlay())
        assertFalse(building.copy(wifiNote = "2 areas wait for Wi-Fi").wantsOverlay())
        assertFalse(CellCardUi.Done(2, 20L * 1_048_576).wantsOverlay())
    }

    @Test fun loading() = shot(building, downloading, question)
    @Test fun offlineAndFailed() = shot(offline, failed)

    private fun shot(vararg uis: CellCardUi) = paparazzi.snapshot {
        OnPathTheme {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                for (ui in uis) MapLoadPanel(ui, CellCardCallbacks())
            }
        }
    }
}
