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
}
