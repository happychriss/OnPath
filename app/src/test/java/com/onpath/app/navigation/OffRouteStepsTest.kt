package com.onpath.app.navigation

import com.onpath.app.domain.tour.SmartScreen
import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaving the route is two steps, each on the screen (owner 2026-11-07): the state → what Navigate shows. Step 1 = the
 * amber off-route card (ANZEIGE, held while the state holds), step 2 = the question box (FRAGE).
 */
class OffRouteStepsTest {
    private val q = NavPrompt.Question(1, 120.0, 600.0, 10.0, emptyList(), 100.0, 300.0, offM = 120.0)

    @Test fun onTheRouteOrJustLeavingNothing() {
        // LEAVING is not a step yet (GPS drift, a parallel way): no prompt, nothing shown
        assertFalse(OffRouteSteps.card(null))
        assertFalse(OffRouteSteps.question(null))
    }

    @Test fun confirmedBothStepsShowAtOnce() {
        // the way back is searched: the card (step 1) and the box with Keep original (step 2)
        assertTrue(OffRouteSteps.card(NavPrompt.Searching))
        assertTrue(OffRouteSteps.question(NavPrompt.Searching))
        assertFalse("the box says 'searching' – not the card too", OffRouteSteps.cardState(NavPrompt.Searching))
    }

    @Test fun theQuestionOpenTheCardStays() {
        assertTrue(OffRouteSteps.card(q))
        assertTrue(OffRouteSteps.question(q))
        assertFalse(OffRouteSteps.cardState(q))
    }

    @Test fun noWayOrKeptOnlyTheCardWithItsState() {
        for (p in listOf(NavPrompt.NoWay(Rerouter.Reason.NO_WAY), NavPrompt.Kept)) {
            assertTrue(OffRouteSteps.card(p))
            assertFalse(OffRouteSteps.question(p))
            assertTrue(OffRouteSteps.cardState(p))
        }
    }

    @Test fun notOffTheRouteNoCard() {
        val switch = q.copy(switch = ActivitySwitch(Activity.HIKE, 2, NavSession.start(NavKind.PLAN, "x", Activity.HIKE, 2,
            Trace.line(0.0 to 0.0, 500.0 to 0.0), listOf(Trace.pt(0.0, 0.0), Trace.pt(500.0, 0.0))), 600.0))
        for (p in listOf(NavPrompt.FarStart(1, 30_000.0, 90.0), NavPrompt.LeadFailed(2), NavPrompt.MidStart(3, 500.0), switch)) {
            assertFalse("$p", OffRouteSteps.card(p))
            assertFalse("$p", OffRouteSteps.question(p))
        }
    }

    /** The bike's rest view never covers the steps: an off-route prompt is something that needs the user. */
    @Test fun theRestViewGivesWayToTheOffRouteCard() {
        val gate = SmartScreen()
        fun rest(prompt: NavPrompt?) = gate.rest(SmartScreen.In(on = true, attention = SmartScreen.needsUser(paused = false,
            gpsLost = false, hadGpsFix = true, provisional = false, offRouteAlert = prompt != null, prompt = prompt != null,
            notOnRoute = false, noFix = false, notFollowing = false, dialog = false), targetKey = 1L, targetM = 5_000.0), 1_000_000L)
        assertTrue("far from a turn, nothing open: the rest view", rest(null))
        assertFalse(rest(NavPrompt.Searching))
        assertEquals(false, rest(q))
    }
}
