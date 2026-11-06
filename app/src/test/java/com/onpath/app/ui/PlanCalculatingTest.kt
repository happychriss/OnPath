package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-06 (Pixel): on Reverse / Round trip the plan sheet went down and up – while the route was calculated the
 * profile, the difficulty and the lines vanished and a bar took room of its own. Now nothing appears or disappears
 * because of the calculation (the slow case with text + Cancel excepted); screenshots planRecalculating(Before).
 */
class PlanCalculatingTest {
    private val panel = File("src/main/java/com/onpath/app/ui/PlanPanel.kt").readText()

    @Test fun theBarRunsInTheHairlineUnlessSlow() {
        assertTrue(PlanCalculating.barInHairline(routing = true, slow = false))
        assertFalse(PlanCalculating.barInHairline(routing = true, slow = true))
        assertFalse(PlanCalculating.barInHairline(routing = false, slow = false))
    }

    @Test fun whatShowsDoesNotDependOnTheCalculation() {
        for (has in listOf(true, false)) assertTrue(PlanCalculating.shows(has, true) == PlanCalculating.shows(has, false))
    }

    @Test fun nothingInTheSheetIsHiddenWhileCalculating() {
        assertFalse("a line hidden while routing", panel.contains("takeIf { !ui.routing }"))
        assertFalse("the profile hidden while routing", panel.contains("if (route != null && !ui.routing)"))
        assertFalse("the difficulty hidden while routing", panel.contains("!oneLine && !ui.routing"))
        assertFalse("a bar with room of its own", panel.contains("else if (ui.routing) RoutingBar("))
        assertTrue(panel.contains("PlanCalculating.barInHairline(ui.routing, ui.routingSlow)"))
    }
}
