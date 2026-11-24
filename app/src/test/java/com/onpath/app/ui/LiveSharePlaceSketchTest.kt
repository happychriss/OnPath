package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonPinCircle
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ShareLocation
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

    private fun shot(ui: NavUi, place: SharePlace, state: SharePlaceState, font: Float = 1f,
                     icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.ShareLocation,
                     heightDp: Int? = null) = paparazzi.snapshot {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, font), LocalSharePlaceSketch provides SharePlaceSketch(place, state,
            if (place == SharePlace.A_COLUMN || place == SharePlace.E_BUBBLES_PILL) Icons.Outlined.Share else icon)) {
            OnPathTheme {
                Box(Modifier.fillMaxSize()) {
                    MapPlaceholder(Modifier.fillMaxSize())
                    Box((if (heightDp != null) Modifier.fillMaxWidth().height(heightDp.dp) else Modifier.fillMaxSize()).padding(top = 32.dp)) {
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

    private val D = SharePlace.D_TWO_BUBBLES
    @Test fun dFreeOff() = shot(free, D, SharePlaceState.OFF)
    @Test fun dFreeLive() = shot(free, D, SharePlaceState.LIVE)
    @Test fun dPlannedLive() = shot(planned, D, SharePlaceState.LIVE)
    @Test fun dPlannedWaiting() = shot(planned, D, SharePlaceState.WAITING)
    @Test fun dPlannedLiveLarge() = shot(planned, D, SharePlaceState.LIVE, 2f)
    @Test fun dPlannedLiveEn() = en { shot(planned, D, SharePlaceState.LIVE) }
    @Test fun dIconPerson() = shot(planned, D, SharePlaceState.LIVE, icon = Icons.Outlined.PersonPinCircle)
    @Test fun dIconWifi() = shot(planned, D, SharePlaceState.LIVE, icon = Icons.Outlined.WifiTethering)
    /** Small phone height (640 dp) at the largest font: both bubbles + gap against the card and the scale. */
    @Test fun dSmallLarge() = shot(planned, D, SharePlaceState.LIVE, 2f, heightDp = 640)
    @Test fun dSmallLargeFree() = shot(free, D, SharePlaceState.LIVE, 2f, heightDp = 640)

    private val E = SharePlace.E_BUBBLES_PILL
    @Test fun eFreeOff() = shot(free, E, SharePlaceState.OFF)
    @Test fun eFreeLive() = shot(free, E, SharePlaceState.LIVE)
    @Test fun ePlannedLive() = shot(planned, E, SharePlaceState.LIVE)
    @Test fun ePlannedWaiting() = shot(planned, E, SharePlaceState.WAITING)
    @Test fun ePlannedLiveLarge() = shot(planned, E, SharePlaceState.LIVE, 2f)
    @Test fun ePlannedWaitingLarge() = shot(planned, E, SharePlaceState.WAITING, 2f)
    @Test fun ePlannedLiveEn() = en { shot(planned, E, SharePlaceState.LIVE) }
    @Test fun eSmallLarge() = shot(planned, E, SharePlaceState.WAITING, 2f, heightDp = 640)
    @Test fun eSmallLargeFree() = shot(free, E, SharePlaceState.WAITING, 2f, heightDp = 640)
}
