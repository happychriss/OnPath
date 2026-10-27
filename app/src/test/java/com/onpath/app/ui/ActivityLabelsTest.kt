package com.onpath.app.ui

import com.onpath.app.navigation.ActivityFit
import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityLabelsTest {
    private fun ui(a: Activity, running: Boolean) =
        PlanUi(3, null, false, null, canUndo = false, canRedo = false, activity = a, tourRunning = running)

    @Test fun primaryButtonNamesTheActivity() {
        assertEquals("Start navigation · Hike", primaryLabel(ui(Activity.HIKE, false)))
        assertEquals("Continue navigation · Gravelbike", primaryLabel(ui(Activity.BIKE_TREKKING, true)))
    }

    /** UX review 2026-10-23, problem 14: the tour's Continue names the activity, like the primary button did. */
    @Test fun tourContinueNamesTheActivity() {
        assertEquals("Continue · Gravelbike", continueLabel(Activity.BIKE_TREKKING))
        assertEquals("Continue · Hike", continueLabel(Activity.HIKE))
    }

    /** Problem 5: the track panel's button names the activity it navigates with. */
    @Test fun trackButtonNamesTheActivity() {
        assertEquals("Start navigation · Hike", trackFollowLabel(recording = false, Activity.HIKE))
        assertEquals("Navigate this track · MTB", trackFollowLabel(recording = true, Activity.MTB))
    }

    @Test fun rowStatusTexts() {
        val fits = ActivityFit(Activity.MTB, 3, emptyList(), 7_000.0, 2_280.0, 40.0, emptyList())
        assertEquals("✓ fits", activityRowStatus(ActivityRowState.Ready(fits)))
        val bad = fits.copy(problems = listOf("1.4 km not suited for gravel bikes", "steps 40 m"), extraM = 1_230.0)
        assertEquals("⚠ 1.4 km not suited for gravel bikes, steps 40 m · +1.2 km", activityRowStatus(ActivityRowState.Ready(bad)))
        assertEquals("✓ fits · −500 m", activityRowStatus(ActivityRowState.Ready(fits.copy(extraM = -500.0))))
        assertEquals("No route found for it", activityRowStatus(ActivityRowState.Failed("No route found for it")))
    }

    /** Owner 2026-10-26: clearer bike names, each with one short line in the picker; the enum names stay (stored). */
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
