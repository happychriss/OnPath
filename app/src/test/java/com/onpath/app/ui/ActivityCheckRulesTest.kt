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

    /** Large German font broke the header as "Wechs / eln zu": the grey note goes under the title when both don't fit. */
    @Test fun theHeaderNoteGoesUnderTheTitleWhenBothDoNotFit() {
        // dp at a 392 dp wide sheet (360 dp inside): normal font fits side by side, large font does not
        assertTrue(!ActivityCheckRules.noteStacked(360f, 90f, 140f, 12f))
        assertTrue(ActivityCheckRules.noteStacked(360f, 140f, 230f, 12f))
    }

    /**
     * Field bug 2026-11-07, tour 207 (owner: "erst dann, wenn ich wirklich eine Routenart ausgewählt habe"): the sheet
     * opened with every row being routed, 15 s each. Rows start plain and choosable; a tap routes that row alone.
     */
    @Test fun onlyTheChosenRowIsRoutedAndANewTapLeavesTheEarlierOne() {
        val idle = listOf(Activity.BIKE_TOURING, Activity.MTB, Activity.HIKE).map { ActivityRow(it, 1, ActivityRowState.Idle) }
        val ui = ActivityCheckUi(Activity.BIKE_TREKKING, 1, idle)
        assertTrue("plain rows can be chosen", ui.rows.all { it.choosable })
        assertEquals("no pre-check text", "", activityRowStatus(ActivityRowState.Idle))
        val mtb = ActivityCheckRules.checking(ui, Activity.MTB, 1)
        assertEquals(listOf(ActivityRowState.Idle, ActivityRowState.Checking, ActivityRowState.Idle), mtb.rows.map { it.state })
        assertTrue("a row being routed is not chosen twice", !mtb.rows[1].choosable)
        val failed = mtb.copy(rows = mtb.rows.map { if (it.activity == Activity.MTB) it.copy(state = ActivityRowState.Failed("none")) else it })
        val hike = ActivityCheckRules.checking(failed, Activity.HIKE, 5)
        assertEquals(listOf(ActivityRowState.Idle, ActivityRowState.Failed("none"), ActivityRowState.Checking), hike.rows.map { it.state })
        assertEquals("the T chip's level", 5, hike.rows.last().sac)
        // a tap while Hike routes: Hike is plain again (its request is cancelled), only Bike spins
        val bike = ActivityCheckRules.checking(hike, Activity.BIKE_TOURING, 1)
        assertEquals(listOf(ActivityRowState.Checking, ActivityRowState.Failed("none"), ActivityRowState.Idle), bike.rows.map { it.state })
    }

}
