package com.onpath.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.onpath.strings.*
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The one modal while the user waits for the map of their area: plain words, time left, Cancel – and each phase its own
 * progress (owner 2026-11-18, [PercentStallTest]): preparing and "almost ready" with a running bar and no number, the
 * download with the real bytes in %. EN, DE, DE at the largest font.
 */
abstract class MapLoadOverlayShots(private val locale: Locale, fontScale: Float) {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = fontScale))

    private val before = Locale.getDefault()
    @Before fun lang() = Locale.setDefault(locale)
    @After fun back() = Locale.setDefault(before)

    protected val mb = 1_048_576L
    protected val preparing get() = MapLoadUi(MapLoadUi.Kind.PREPARING, 0, 260, regions = 3, bytes = 27 * mb)
    protected val loading get() = MapLoadUi(MapLoadUi.Kind.LOADING, 62, 25, regions = 1, bytes = 9 * mb)
    protected val finishing get() = MapLoadUi(MapLoadUi.Kind.FINISHING, 100, null, regions = 1, bytes = 9 * mb)
    /** Two of three areas are there, the third is still prepared on the server: the count says how far. */
    protected val counted get() = MapLoadUi(MapLoadUi.Kind.PREPARING, 66, 120, regions = 3, bytes = 27 * mb, regionsDone = 2)
    protected val offline get() = MapLoadUi(MapLoadUi.Kind.OFFLINE, 47, null, regions = 2, bytes = 18 * mb)
    protected val failed get() = MapLoadUi(MapLoadUi.Kind.FAILED, 12, null, Txt.s(Res.string.maps_load_failed_retry), retryable = true)

    /** Preparing (the bar runs, the server's time left) and the download (real bytes, %). */
    @Test fun loading() = shot(preparing, loading)

    /** Everything downloaded, joined into the map: "almost ready", the bar runs – no 98 % that stands. */
    @Test fun finishing() = shot(finishing, counted)

    @Test fun offlineAndFailed() = shot(offline, failed)

    /** "Maps only on Wi-Fi": the modal offers "Load now over mobile data" once; setting unchanged. */
    @Test fun waitingForWifi() {
        val wifi = MapLoadUi(MapLoadUi.Kind.WIFI, 0, null, regions = 2, bytes = 17 * mb)
        assertTrue(wifi.paused)
        assertTrue(wifi.line == MapLoadUi.WIFI_TEXT)
        shot(wifi)
    }

    protected val routingStatus get() = com.onpath.app.data.RoutingDataLoader.Status(listOf("E10_N45.rd5:E11_N46"),
        com.onpath.app.data.RoutingDataLoader.Phase.LOADING, 3 * mb, 10 * mb, 2.5 * mb)

    /** Fresh install: map + routing data in one modal; only routing data missing. */
    @Test fun withRoutingData() = shot(
        RoutingLoads.combine(MapLoadUi(MapLoadUi.Kind.LOADING, 40, 30, regions = 4, bytes = 30 * mb), routingStatus)!!,
        RoutingLoads.combine(null, routingStatus)!!)

    protected fun shot(vararg uis: MapLoadUi) = paparazzi.snapshot {
        OnPathTheme {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                for (ui in uis) MapLoadPanel(ui, MapLoadCallbacks())
            }
        }
    }
}

class MapLoadOverlayTest : MapLoadOverlayShots(Locale.US, 1f) {
    @Test fun texts() {
        assertTrue(preparing.title == "Preparing the map for your area" && preparing.line == "About 4 minutes left")
        assertTrue(loading.title == "Loading the map for your area" && loading.line == "Less than a minute left")
        assertTrue(finishing.title == "The map for your area is almost ready" && finishing.line == null)
        assertTrue(counted.totals, counted.totals == "2 of 3 map areas · about 27 MB")
        assertTrue(offline.paused)
        assertFalse(loading.paused)
        // a number only where bytes flow (or wait): never while preparing / finishing
        assertTrue(loading.hasPercent && offline.hasPercent)
        assertFalse(preparing.hasPercent || finishing.hasPercent || counted.hasPercent)
        val both = RoutingLoads.combine(MapLoadUi(MapLoadUi.Kind.LOADING, 40, 30, regions = 4, bytes = 30 * mb), routingStatus)!!
        val only = RoutingLoads.combine(null, routingStatus)!!
        assertTrue(both.totals, both.totals == "4 map areas · about 40 MB")
        assertTrue(only.title == "Loading the map for your area" && only.totals == "about 10 MB")
    }
}

class MapLoadOverlayGermanTest : MapLoadOverlayShots(Locale.GERMANY, 1f)
class MapLoadOverlayLargeFontGermanTest : MapLoadOverlayShots(Locale.GERMANY, 2f)
