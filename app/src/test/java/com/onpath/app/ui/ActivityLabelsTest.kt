package com.onpath.app.ui

import com.onpath.app.navigation.ActivityFit
import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityLabelsTest {
    private fun ui(a: Activity, running: Boolean) =
        PlanUi(3, null, false, null, canUndo = false, canRedo = false, activity = a, tourRunning = running)

    @Test fun primaryButtonNamesTheActivity() {
        // owner 2026-11-02 (#16): "Let's go · ‹destination›"; no named destination → the activity
        assertEquals("Let’s go · Hike", primaryLabel(ui(Activity.HIKE, false)))
        assertEquals("Carry on · Gravelbike", primaryLabel(ui(Activity.BIKE_TREKKING, true)))
    }

    /** "Let's go · Schloss Nordkirchen": the destination's own name; "Destination" (unnamed) or a loop → the activity. */
    @Test fun letsGoNamesTheDestination() {
        fun row(i: Int, role: com.onpath.app.routing.PointRole, name: String, loop: Boolean = false) =
            com.onpath.app.routing.PlanPointRow(i, role, name, null, false, loop)
        val start = com.onpath.app.routing.PointRole.START
        val goal = com.onpath.app.routing.PointRole.GOAL
        val named = listOf(row(0, start, "Start"), row(1, goal, "Schloss Nordkirchen"))
        assertEquals("Let’s go · Schloss Nordkirchen", primaryLabel(ui(Activity.HIKE, false).copy(points = named)))
        val unnamed = listOf(row(0, start, "Start"), row(1, goal, "Destination"))
        assertEquals("Let’s go · Hike", primaryLabel(ui(Activity.HIKE, false).copy(points = unnamed)))
        val loop = listOf(row(0, start, "Café", loop = true), row(1, com.onpath.app.routing.PointRole.STOP, "Stop 2"))
        assertEquals("Let’s go · Hike", primaryLabel(ui(Activity.HIKE, false).copy(points = loop)))
    }
    /** Problem 5: the track panel's button names the activity it navigates with. */
    @Test fun trackButtonNamesTheActivity() {
        assertEquals("Let’s go · Hike", trackFollowLabel(recording = false, Activity.HIKE))
        assertEquals("Follow this tour · MTB", trackFollowLabel(recording = true, Activity.MTB))
    }

    @Test fun rowStatusTexts() {
        val fits = ActivityFit(Activity.MTB, 3, emptyList(), 7_000.0, 2_280.0, 40.0, emptyList())
        assertEquals("✓ fits", activityRowStatus(ActivityRowState.Ready(fits)))
        val bad = fits.copy(problems = listOf("1.4 km not suited for gravel bikes", "steps 40 m"), extraM = 1_230.0)
        assertEquals("⚠ 1.4 km not suited for gravel bikes, steps 40 m · +1.2 km", activityRowStatus(ActivityRowState.Ready(bad)))
        assertEquals("✓ fits · −500 m", activityRowStatus(ActivityRowState.Ready(fits.copy(extraM = -500.0))))
        assertEquals("No route found for it", activityRowStatus(ActivityRowState.Failed("No route found for it")))
    }

    /** Clear bike names, each with one short line in the picker (owner); the enum names stay (stored). */
    @Test fun activityNamesAndPickerLines() {
        assertEquals(listOf("Bike", "Gravelbike", "E-bike", "MTB", "E-MTB", "Hike"), Activity.entries.map { it.label })
        assertEquals(listOf("Paved cycleways, quiet roads", "Gravel tracks welcome", "Bike with motor, 25 km/h",
            "Trails and singletrails", "MTB with motor", "T1 … T5"), Activity.entries.map { it.line })
        assertEquals(listOf("BIKE_TOURING", "BIKE_TREKKING", "EBIKE", "MTB", "EMTB", "HIKE"), Activity.entries.map { it.name })
    }

    /** "… not suited for <who>" in the activity switch sheet follows the names. */
    @Test fun activityCheckNamesTheActivityInThePlural() {
        assertEquals(listOf("bikes", "gravelbikes", "e-bikes", "MTBs", "E-MTBs", "hiking (T3)"),
            Activity.entries.map { com.onpath.app.navigation.ActivityCheck.who(it, 3) })
    }
}
