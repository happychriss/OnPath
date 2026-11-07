package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-11-06 (Pixel): on Reverse / Round trip the plan sheet went down and up – while the route was calculated the
 * profile, the difficulty and the lines vanished and a bar took room of its own. Now nothing appears or disappears
 * because of the calculation (the slow case with text + Cancel excepted); screenshots planRecalculating(Before).
 */
class PlanCalculatingTest {

    @Test fun theBarRunsInTheHairlineUnlessSlow() {
        assertTrue(PlanCalculating.barInHairline(routing = true, slow = false))
        assertFalse(PlanCalculating.barInHairline(routing = true, slow = true))
        assertFalse(PlanCalculating.barInHairline(routing = false, slow = false))
    }

}
