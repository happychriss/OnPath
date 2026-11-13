package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.LongRouteCancelCamera.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-13: "Abbrechen" at the very-long-route question takes the map back to what was planned before – the
 * start in Hilden after Paris was taken as the destination; with two or more points the whole plan; during a tour my
 * position.
 */
class LongRouteCancelCameraTest {
    private val hilden = GeoPoint(51.169, 6.939)
    private val benrath = GeoPoint(51.162, 6.876)
    private val line = listOf(hilden, GeoPoint(51.166, 6.91), benrath)

    @Test fun onePointLeftTheMapGoesBackToIt() =
        assertEquals(Target.Fit(listOf(hilden)), LongRouteCancelCamera.after(listOf(hilden), null, touring = false, hasPosition = true))

    @Test fun twoOrMorePointsTheWholePlan() {
        assertEquals(Target.Fit(line + listOf(hilden, benrath)),
            LongRouteCancelCamera.after(listOf(hilden, benrath), line, touring = false, hasPosition = true))
        // no route line yet: the points
        assertEquals(Target.Fit(listOf(hilden, benrath)), LongRouteCancelCamera.after(listOf(hilden, benrath), null, false, false))
    }

    @Test fun duringATourMyPosition() {
        assertEquals(Target.Me, LongRouteCancelCamera.after(listOf(hilden, benrath), line, touring = true, hasPosition = true))
        assertEquals(Target.Me, LongRouteCancelCamera.after(emptyList(), null, touring = true, hasPosition = true))
        // no position known yet: the plan, as without a tour
        assertEquals(Target.Fit(listOf(hilden)), LongRouteCancelCamera.after(listOf(hilden), null, touring = true, hasPosition = false))
    }

    @Test fun noPointTheMapStays() = assertEquals(Target.Stay, LongRouteCancelCamera.after(emptyList(), null, false, true))

    @Test fun onlyTheLongRouteCancelMovesTheMap() {
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        val answer = vm.substringAfter("fun answerLongRoute(").substringBefore("\n    }\n")
        assertTrue(answer, answer.contains("LongRouteCancelCamera.after(waypoints.toList(), route?.points"))
        // the shared cancel (slow plan, Stop) leaves the camera alone
        val cancel = vm.substringAfter("private fun cancelRun(").substringBefore("\n    }\n")
        assertTrue(cancel, !cancel.contains("LongRouteCancelCamera"))
    }
}
