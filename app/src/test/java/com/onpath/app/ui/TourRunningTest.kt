package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The empty plan during a tour says "● Tour läuft noch" + "Neues Ziel festlegen ›" (owner 2026-11-12, field: he deleted
 * all points during a tour and missed that it still recorded).
 */
class TourRunningTest {
    @Test fun `only while a tour runs and the plan is empty`() {
        assertTrue(TourStillRunning.shows(pointCount = 0, touring = true))
        assertFalse("with points the sheet shows the plan", TourStillRunning.shows(pointCount = 1, touring = true))
        assertFalse(TourStillRunning.shows(pointCount = 3, touring = true))
        assertFalse("without a tour the start guide asks", TourStillRunning.shows(pointCount = 0, touring = false))
        assertFalse(TourStillRunning.shows(pointCount = 2, touring = false))
    }

    @Test fun `the start guide and the tour line never show together`() {
        for (n in 0..3) for (t in listOf(true, false))
            assertFalse("n=$n touring=$t", EmptyGuide.shows(n, t) && TourStillRunning.shows(n, t))
    }

    @Test fun `the sheet uses the tour title and the new-destination row, the old line is gone, nothing ticks`() {
        val panel = File("src/main/java/com/onpath/app/ui/PlanPanel.kt").readText()
        assertFalse(panel.contains("plan_hint_tour_goes_on"))
        assertTrue(panel.contains("TourStillRunningTitle(") && panel.contains("TourNewTargetRow(cb.onSearch)"))
        val sheet = File("src/main/java/com/onpath/app/ui/TourStillRunning.kt").readText()
        assertFalse("no running time in the title", sheet.contains("elapsed") || sheet.contains("Format.duration"))
    }

    @Test fun `the texts in both languages`() {
        val de = File("../strings/src/commonMain/composeResources/values-de/strings_plan.xml").readText()
        val en = File("../strings/src/commonMain/composeResources/values/strings_plan.xml").readText()
        assertTrue(de.contains(">Tour läuft noch<") && de.contains(">Neues Ziel festlegen<") && de.contains("→ Hierhin<"))
        assertTrue(en.contains(">Tour still running<") && en.contains(">Set a new destination<") && en.contains("→ Go here<"))
    }
}
