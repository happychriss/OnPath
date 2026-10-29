package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.SnapshotHandler
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanPointRow
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.PointRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.sin

/**
 * Opening / pulling the plan sheet must not stutter (Pixel 8, 120 Hz), also with the points list open. Frames run on the JVM (Paparazzi, no images kept); the heavy parts count their compositions (RecomposeProbe).
 * Moving the sheet must not recompose the points list or the elevation profile.
 */
class SheetSmoothnessTest {
    private object NoImages : SnapshotHandler {
        override fun newFrameHandler(snapshot: app.cash.paparazzi.Snapshot, frameCount: Int, fps: Int) =
            java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
                arrayOf(SnapshotHandler.FrameHandler::class.java)) { _, _, _ -> null } as SnapshotHandler.FrameHandler
        override fun close() = Unit
    }

    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, snapshotHandler = NoImages)

    @Before fun on() { RecomposeProbe.reset(); RecomposeProbe.enabled = true }
    @After fun off() { RecomposeProbe.enabled = false }

    private val pts = (0..600).map { i -> GeoPoint(46.67 + i * 0.0002, 11.16 + i * 0.00015, 350.0 + 600 * sin(i / 90.0) + (i % 7)) }
    private val route = PlannedRoute(listOf(PlannedLeg(pts, false)), 12_480.0, 842.0, 310.0, 4.6 * 3600, null)
    private val rows = (0 until 8).map { i ->
        PlanPointRow(i, if (i == 0) PointRole.START else if (i == 7) PointRole.GOAL else PointRole.STOP, "Point $i",
            if (i == 0) null else 1500.0, approx = false, loop = false)
    }

    private fun ui(size: PlanSize) = PlanUi(8, route, false, null, canUndo = true, canRedo = false, activity = Activity.HIKE,
        size = size, points = rows)

    private fun counts() = "points=${RecomposeProbe.count("points")} profile=${RecomposeProbe.count("profile")} " +
        "profile draws=${RecomposeProbe.count("profile-draw")}"

    /** The finger drags the sheet through 60 heights (profile step → points step and back), then lets go. */
    @Test fun draggingThroughSixtyHeightsRecomposesNothingHeavy() {
        var frames = 0
        var heights = emptyList<Float>()
        var settledAt = -1
        paparazzi.gif("drag", 0, 1500, 60) {
            var size by remember { mutableStateOf(PlanSize.COMPACT) }
            val state = rememberStepSheetState(3, size.step)
            LaunchedEffect(Unit) {
                withFrameNanos { }
                withFrameNanos { }
                heights = state.heights()
                val from = heights[1]; val to = heights[2]
                for (k in 0 until 60) {
                    withFrameNanos {
                        state.dragging = true
                        // up to the points and half-way back
                        val t = if (k < 40) k / 39f else 1f - (k - 40) / 40f
                        state.dragHeight = from + (to - from) * t
                        frames++
                    }
                }
                withFrameNanos { state.dragging = false; state.settleTick++ }
                settledAt = state.step
            }
            OnPathTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        PlanPanel(ui(size), PlanCallbacks(onResize = { size = it }), sheetState = state)
                    }
                }
            }
        }
        println("SMOOTH drag: frames=$frames heights=$heights ${counts()}")
        assertEquals(60, frames)
        assertTrue("three different step heights: $heights", heights[0] < heights[1] && heights[1] < heights[2])
        assertEquals("the points list composed once for 60 sheet heights", 1, RecomposeProbe.count("points"))
        assertEquals("the profile composed once for 60 sheet heights", 1, RecomposeProbe.count("profile"))
        assertEquals(1, settledAt)
    }

    /** Handle tap: profile step → points step; the spring runs ~40 frames. At most the one switch recomposes. */
    @Test fun openingThePointsRecomposesAtMostOnce() {
        var frames = 0
        paparazzi.gif("open", 0, 1000, 60) {
            var size by remember { mutableStateOf(PlanSize.COMPACT) }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                size = PlanSize.FULL
                while (true) withFrameNanos { frames++ }
            }
            OnPathTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        PlanPanel(ui(size), PlanCallbacks(onResize = { size = it }))
                    }
                }
            }
        }
        println("SMOOTH open: frames=$frames ${counts()}")
        assertTrue("points composed ${RecomposeProbe.count("points")}×", RecomposeProbe.count("points") <= 2)
        assertEquals("profile composed once", 1, RecomposeProbe.count("profile"))
    }
}
