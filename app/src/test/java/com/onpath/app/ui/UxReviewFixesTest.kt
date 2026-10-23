package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wiring of the UX logic review fixes (docs/review-ux-2026-10-23.md, owner answers 2026-10-23) that live in Compose
 * screens / the ViewModel and so cannot be run on the JVM: the source is checked instead (like PlanStatusTest).
 */
class UxReviewFixesTest {
    private val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
    private val map = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()

    private fun body(src: String, start: String) = src.substringAfter(start).substringBefore("\n    }\n")

    /** Problem 3 (owner Q1): the activity in Plan during a tour opens the same check-and-switch sheet as Navigate. */
    @Test fun activityInPlanDuringATourOpensTheCheckSheet() {
        val plan = map.substringAfter("PlanMode(vm, rec,").substringBefore("navigate = {")
        assertTrue(plan, plan.contains("onActivity = { if (rec != null) vm.openActivityCheck() else showActivity = true }"))
        assertTrue(map.contains("ActivitySwitchSheet(it, vm::chooseActivity, vm::closeActivityCheck"))
        // a proposal is answered in Navigate (Take new route / Keep)
        val choose = body(vm, "fun chooseActivity(")
        assertTrue(choose, choose.contains("if (mode == AppMode.PLAN) enter(AppMode.NAVIGATE)"))
        assertFalse(choose, choose.contains("selectActivity(a)\n"))
    }

    /** Problem 10: on a first start the restore offer waits for the location answer and the loading modal. */
    @Test fun firstStartDialogsComeOneAfterTheOther() {
        assertTrue(RestoreOfferRule.show(offer = true, recording = false, locationAsking = false, loadModalOpen = false))
        assertFalse("location request open", RestoreOfferRule.show(true, recording = false, locationAsking = true, loadModalOpen = false))
        assertFalse("loading modal open", RestoreOfferRule.show(true, recording = false, locationAsking = false, loadModalOpen = true))
        assertFalse("during a tour", RestoreOfferRule.show(true, recording = true, locationAsking = false, loadModalOpen = false))
        assertFalse("nothing to offer", RestoreOfferRule.show(false, recording = false, locationAsking = false, loadModalOpen = false))
        val main = File("src/main/java/com/onpath/app/MainActivity.kt").readText()
        assertTrue(main.contains("RestoreOfferRule.show(vm.restoreOffer"))
        assertTrue(main.contains("vm.locationAsking = true; permissionLauncher.launch"))
    }

    /** Problem 11: the message bar (stays until OK) never covers the blue strip – it goes below it. */
    @Test fun messageBarGoesBelowTheStrip() {
        assertTrue(MessagePlacement.topDp(stripShown = false, stripHeightDp = 0) == MessagePlacement.MESSAGE_TOP_DP)
        val stripHeight = 96
        val top = MessagePlacement.topDp(stripShown = true, stripHeightDp = stripHeight)
        assertTrue("$top", top >= MessagePlacement.STRIP_TOP_DP + stripHeight)
        // not measured yet: as before
        assertTrue(MessagePlacement.topDp(stripShown = true, stripHeightDp = 0) == MessagePlacement.MESSAGE_TOP_DP)
        val main = File("src/main/java/com/onpath/app/MainActivity.kt").readText()
        assertTrue(main.contains("MessagePlacement.topDp(vm.backgroundStripShown"))
    }

    /** Owner text Q7: ⋯ → Time details keeps plain lines only; the formulas moved to Settings → Diagnostics. */
    @Test fun timeDetailsPlainLinesAndFormulasInDiagnostics() {
        val pts = (0..60).map { com.onpath.app.routing.GeoPoint(46.67 + it * 0.0004, 11.16 + it * 0.0003, 350.0 + it * 3) }
        val r = com.onpath.app.time.TimeBreakdown.of(pts, com.onpath.app.routing.Activity.HIKE, com.onpath.app.time.Factors(1.0, 1.0, 1.0))
        val lines = timeDetailsPlainLines(TimeDetailsUi(r, "Average", 1.0))
        assertTrue(lines.toString(), lines.map { it.first } == listOf("Moving time", "With breaks", "Pace level"))
        assertTrue(lines[2].second == "Average")
        val settings = File("src/main/java/com/onpath/app/ui/SettingsScreen.kt").readText()
        assertTrue(settings.substringAfter("private fun DeveloperTools(").contains("onTimeFormulas"))
        val main = File("src/main/java/com/onpath/app/MainActivity.kt").readText()
        assertTrue(main.contains("vm.openTimeDetails(technical = true)"))
    }
}
