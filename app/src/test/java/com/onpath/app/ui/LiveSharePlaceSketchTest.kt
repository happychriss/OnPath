package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.onpath.app.recording.GpsStatus
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.math.sin

/**
 * SKETCH (owner 2026-11-24): three places for "Standort teilen" in Navigate – A the map column's top button, B the status
 * card's right edge, C a pill under the card. 15 pictures: free tour DE off / sharing, planned tour DE sharing / large font,
 * planned EN. Not for main; the sketch hook is [LocalSharePlaceSketch].
 */
class LiveSharePlaceSketchTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428,
            density = com.android.resources.Density.create(420)),
        showSystemUi = false,
    )
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    private val pts = (0..120).map { GeoPoint(46.67 + it * 0.0004, 11.16 + it * 0.0003, 350.0 + 600 * sin(it / 38.0) + (it % 7)) }
    private val planned = NavUi(toGo = ToGo(7_420.0, 512.0, 2.9 * 3600, 12.0, descentM = 310.0, profile = pts),
        recordedM = 5_320.0, elapsedS = 5025, activity = Activity.HIKE, arriveAt = "16:40")
    private val free = NavUi(recordedM = 2_150.0, elapsedS = 1820, paused = false)

    private fun shot(ui: NavUi, place: SharePlace, state: SharePlaceState, font: Float = 1f) = paparazzi.snapshot {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, font), LocalSharePlaceSketch provides SharePlaceSketch(place, state)) {
            OnPathTheme {
                Box(Modifier.fillMaxSize()) {
                    MapPlaceholder(Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                        NavigateOverlay(ui, footer = {
                            MapFooter("500 m" to 64.dp) {
                                MapControls(CompassSpec(ui.headingUp, 0f) {}, showEye = true, onPeek = {}, gps = GpsStatus.GOOD,
                                    following = true, onLocate = {}, onFitRoute = {}, onSettings = {})
                            }
                        }, onSlideToPlan = {})
                    }
                }
            }
        }
    }

    private fun en(block: () -> Unit) { Locale.setDefault(Locale.US); block() }

    @Test fun aFreeOff() = shot(free, SharePlace.A_COLUMN, SharePlaceState.OFF)
    @Test fun aFreeLive() = shot(free, SharePlace.A_COLUMN, SharePlaceState.LIVE)
    @Test fun aPlannedLive() = shot(planned, SharePlace.A_COLUMN, SharePlaceState.LIVE)
    @Test fun aPlannedLiveLarge() = shot(planned, SharePlace.A_COLUMN, SharePlaceState.LIVE, 2f)
    @Test fun aPlannedLiveEn() = en { shot(planned, SharePlace.A_COLUMN, SharePlaceState.LIVE) }

    @Test fun bFreeOff() = shot(free, SharePlace.B_CARD, SharePlaceState.OFF)
    @Test fun bFreeLive() = shot(free, SharePlace.B_CARD, SharePlaceState.LIVE)
    @Test fun bPlannedLive() = shot(planned, SharePlace.B_CARD, SharePlaceState.LIVE)
    @Test fun bPlannedLiveLarge() = shot(planned, SharePlace.B_CARD, SharePlaceState.LIVE, 2f)
    @Test fun bPlannedLiveEn() = en { shot(planned, SharePlace.B_CARD, SharePlaceState.LIVE) }

    @Test fun cFreeOff() = shot(free, SharePlace.C_PILL, SharePlaceState.OFF)
    @Test fun cFreeLive() = shot(free, SharePlace.C_PILL, SharePlaceState.LIVE)
    @Test fun cPlannedLive() = shot(planned, SharePlace.C_PILL, SharePlaceState.LIVE)
    @Test fun cPlannedLiveLarge() = shot(planned, SharePlace.C_PILL, SharePlaceState.LIVE, 2f)
    @Test fun cPlannedLiveEn() = en { shot(planned, SharePlace.C_PILL, SharePlaceState.LIVE) }
}
