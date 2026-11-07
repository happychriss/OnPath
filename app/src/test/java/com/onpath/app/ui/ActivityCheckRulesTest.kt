package com.onpath.app.ui

import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flow map K1 (owner 2026-11-06): during a tour the activity sheet offers Hike's T1–T5 like the plan's activity dialog –
 * before, Hike could only be taken with the remembered level.
 */
class ActivityCheckRulesTest {
    private val fits = ActivityRowState.Ready(com.onpath.app.navigation.ActivityFit(Activity.HIKE, 2, emptyList(), 5_000.0, 3600.0, 0.0, emptyList()))
    private fun rows(vararg a: Activity) = a.map { ActivityRow(it, if (it == Activity.HIKE) 2 else 2, fits) }

    @Test fun aChipChecksTheHikeRowAgainForThatLevel() {
        val ui = ActivityCheckUi(Activity.BIKE_TOURING, 2, rows(Activity.BIKE_TREKKING, Activity.HIKE))
        val after = ActivityCheckRules.withHikeLevel(ui, 4)
        val hike = after.rows.single { it.activity == Activity.HIKE }
        assertEquals(4, hike.sac)
        assertEquals(ActivityRowState.Checking, hike.state)
        assertEquals("the other rows stay", fits, after.rows.single { it.activity == Activity.BIKE_TREKKING }.state)
        assertEquals("the chips show the row's level", 4, ActivityCheckRules.chipLevel(after))
    }

    @Test fun hikingAlreadyAnotherLevelAddsAHikeRowFirst() {
        val ui = ActivityCheckUi(Activity.HIKE, 2, rows(Activity.BIKE_TOURING, Activity.MTB))
        assertEquals("the current level is marked", 2, ActivityCheckRules.chipLevel(ui))
        val after = ActivityCheckRules.withHikeLevel(ui, 3)
        assertEquals(Activity.HIKE, after.rows.first().activity)
        assertEquals(3, after.rows.first().sac)
        val back = ActivityCheckRules.withHikeLevel(after, 2)
        assertTrue("the same level as now: nothing to switch to", back.rows.none { it.activity == Activity.HIKE })
        assertEquals(2, ActivityCheckRules.chipLevel(back))
    }

}
