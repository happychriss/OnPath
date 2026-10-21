package com.onpath.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The one modal while the user waits for the map of their area (owner 2026-10-21): plain words, %, time left, Cancel. */
class MapLoadOverlayTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private val preparing = MapLoadUi(MapLoadUi.Kind.PREPARING, 34, 260, regions = 3, bytes = 27L * 1_048_576)
    private val loading = MapLoadUi(MapLoadUi.Kind.LOADING, 91, 25, regions = 1, bytes = 9L * 1_048_576)
    private val offline = MapLoadUi(MapLoadUi.Kind.OFFLINE, 47, null, regions = 2, bytes = 18L * 1_048_576)
    private val failed = MapLoadUi(MapLoadUi.Kind.FAILED, 12, null, "Something went wrong while preparing it. Please try again.", retryable = true)

    @Test fun texts() {
        assertTrue(preparing.title == "Preparing the map for your area" && preparing.line == "About 4 minutes left")
        assertTrue(loading.title == "Loading the map for your area" && loading.line == "Less than a minute left")
        assertTrue(offline.paused)
        assertFalse(loading.paused)
    }

    @Test fun loading() = shot(preparing, loading)
    @Test fun offlineAndFailed() = shot(offline, failed)

    /** Fresh install (field test 2026-10-21): map + routing data in one modal; only routing data missing. */
    @Test fun withRoutingData() {
        val mb = 1_048_576L
        val status = com.onpath.app.data.RoutingDataLoader.Status(listOf("E10_N45.rd5:E11_N46"),
            com.onpath.app.data.RoutingDataLoader.Phase.LOADING, 3 * mb, 10 * mb, 2.5 * mb)
        val both = RoutingLoads.combine(MapLoadUi(MapLoadUi.Kind.LOADING, 40, 30, regions = 4, bytes = 30 * mb), status)!!
        val only = RoutingLoads.combine(null, status)!!
        assertTrue(both.totals, both.totals == "4 regions · about 40 MB")
        assertTrue(only.title == "Loading the map for your area" && only.totals == "about 10 MB")
        shot(both, only)
    }

    private fun shot(vararg uis: MapLoadUi) = paparazzi.snapshot {
        OnPathTheme {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                for (ui in uis) MapLoadPanel(ui, MapLoadCallbacks())
            }
        }
    }

    /** Newer map of an area the user has: soft veil + pill (seconds), or the small hint while it is built (minutes). */
    @Test fun updateVeilAndHint() = paparazzi.snapshot {
        OnPathTheme {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFE8E4DA))) {
                MapUpdateLayer(MapUpdateUi.Loading(0.6f), Modifier.fillMaxSize())
                MapPreparingHint(MapUpdateUi.Preparing(6), Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 76.dp))
            }
        }
    }
}
