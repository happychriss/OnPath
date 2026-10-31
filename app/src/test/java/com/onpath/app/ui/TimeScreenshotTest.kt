package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.data.AppSettings
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.time.EstimateState
import com.onpath.app.time.Factors
import com.onpath.app.time.PauseReason
import com.onpath.app.time.Pace
import com.onpath.app.time.TourTimes
import org.junit.Rule
import org.junit.Test
import kotlin.math.sin

/** Screenshots of the time-estimate UI (docs/plan-time-estimate.md §5): arrival clock, moving time, pace settings. */
class TimeScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420)),
        showSystemUi = false,
    )

    private val pts = (0..120).map { i -> GeoPoint(46.67 + i * 0.0004, 11.16 + i * 0.0003, 350.0 + 600 * sin(i / 38.0) + (i % 7)) }
    private val ui = NavUi(toGo = ToGo(7_420.0, 512.0, 2 * 3600 + 50 * 60.0, 12.0, descentM = 310.0, profile = pts),
        recordedM = 5_320.0, elapsedS = 5025, arriveAt = "16:40", movingS = 72 * 60.0, breakS = 11 * 60.0,
        paceNote = paceNote(Factors(down = 1.2, flat = 1.0, up = 0.8)), activity = Activity.HIKE)

    /**
     * Navigate: "to go" (22 sp) with the arrival clock (30 sp) on the right; the orange dot on the activity icon instead of
     * a recording line; opened: moving time / breaks and the pace in words; paused; a free recording.
     */
    @Test fun navigateArrival() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Column(Modifier.padding(top = 40.dp, start = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NavStatusCard(ui, onResume = {})
                    NavStatusCard(ui, onResume = {}, profileOpen = true) // opened: profile + learned pace
                    NavStatusCard(ui.copy(paused = true), onResume = {})
                    NavStatusCard(NavUi(recordedM = 2_150.0, elapsedS = 1820, movingS = 28 * 60.0, breakS = 0.0), onResume = {})
                }
            }
        }
    }

    /**
     * Navigate card states: the target named, "— not on route" instead of a stale time,
     * "estimate paused" greyed with the reason.
     */
    @Test fun navigateEstimateStates() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Column(Modifier.padding(top = 40.dp, start = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NavStatusCard(ui.copy(toGo = ui.toGo!!.copy(target = "Leiter Alm")), onResume = {})
                    NavStatusCard(ui, onResume = {}) // unnamed destination
                    NavStatusCard(ui.copy(toGo = ToGo(0.0, 0.0, 0.0, 320.0, state = EstimateState.NOT_ON_ROUTE, target = "Leiter Alm")), onResume = {})
                    NavStatusCard(ui.copy(toGo = ui.toGo!!.copy(target = "Leiter Alm", state = EstimateState.PAUSED,
                        pausedBecause = PauseReason.NO_GPS.text), waitingGps = true, fixAgeS = 250), onResume = {})
                    NavStatusCard(ui.copy(toGo = ui.toGo!!.copy(target = "Hirzer Hütte (Rifugio Punta Cervina)", state = EstimateState.PAUSED,
                        pausedBecause = PauseReason.NO_PROGRESS.text)), onResume = {})
                }
            }
        }
    }

    /**
     * The destination under the arrival clock (field A-019: "Gasthof H…"): two lines no wider than the clock – a short
     * name whole, word by word; a long one ends in "…" on the second line.
     */
    @Test fun navigateTargetNames() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Column(Modifier.padding(top = 40.dp, start = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NavStatusCard(ui.copy(arriveAt = "14:15", toGo = ui.toGo!!.copy(target = "Gasthof Hochmuth"),
                        routeNote = "New route · was 13:35"), onResume = {})
                    NavStatusCard(ui.copy(toGo = ui.toGo!!.copy(target = "Leiteralm")), onResume = {})
                    NavStatusCard(ui.copy(toGo = ui.toGo!!.copy(target = "Hirzer Hütte (Rifugio Punta Cervina)")), onResume = {})
                }
            }
        }
    }

    /** Navigate after a plan edit / re-route that moved the arrival > 5 min: the reason stays in the card. */
    @Test fun navigateNewRouteNote() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Column(Modifier.padding(top = 40.dp, start = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NavStatusCard(ui.copy(arriveAt = "12:45", routeNote = "New route · was 13:15"), onResume = {})
                    NavStatusCard(ui.copy(arriveAt = "12:45", routeNote = "New route · was 13:15"), onResume = {}, profileOpen = true)
                }
            }
        }
    }

    /** Settings → Your pace: five levels for hiking and cycling, each with what it means. */
    @Test fun settingsPace() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                PaceSection(AppSettings(hikePace = Pace.FIT, bikePace = Pace.EASY), {}, Activity.EBIKE)
            }
        }
    }

    /** After a tour: moving time, breaks, planned vs. needed. */
    @Test fun finishedWithTimes() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) { MapPlaceholder(Modifier.fillMaxSize()) }
            FinishedTourDialogs(Tour(2, TourKind.RECORDED, "Tour 18 Oct 2026, 09:14", 1_760_200_000_000, "HIKE", 3, 12_800.0, 840.0, 830.0,
                3.55 * 3600, "done", emptyList()), {}, {}, {}, times = TourTimes(172 * 60.0, 41 * 60.0, 190 * 60.0, 172 * 60.0))
        }
    }
}
