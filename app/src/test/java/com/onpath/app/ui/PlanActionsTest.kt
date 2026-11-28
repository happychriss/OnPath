package com.onpath.app.ui

import com.onpath.app.testkit.sourceFile
import com.onpath.app.domain.RouteRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-10-31 (0.13.5): the plan sheet's ⋯ menu is gone. The sheet has a row of three small icons with a label
 * under each – ✕ Close plan (was "Clear route"; asks "Discard changes?" only with unsaved changes), ⇅ Reverse, ↻ Round
 * trip. Save is ☰ → Save route (under My routes whenever there is a plan; while unsaved also the "Nicht gespeichert ›" sign beside the mode label); Save as copy, Export GPX
 * and Share plan are in My routes → ⋮; Time details = a tap on the time.
 */
class PlanActionsTest {
    private fun src(f: String) = sourceFile(f).readText()
    private val vm = src("ui/AppViewModel.kt")
    private val panel = src("ui/PlanPanel.kt")

    /** Owner 2026-11-06: ✕ asks nothing any more (also during a tour) – the plan goes under Zuletzt, one Undo brings it back. */
    @Test fun closePlanAsksNothing() {
        assertTrue(src("ui/MapScreen.kt").contains("onNewPlan = if (vm.plan.points.isNotEmpty()) vm.planEdit::clearPlan else null"))
        assertTrue(src("ui/MapScreen.kt").contains("onClear = vm.planEdit::clearPlan")) // piece 21: the ✕ calls the edit slice's clearPlan itself, the forwarder `requestClear` is gone
        // the plan is kept under Zuletzt first, before it leaves the map: PlanEditFlowTest.clearKeepsThePlanForZuletztFirstThenEmptiesInOneUndoStepAndNeverAsks
        // what is unsaved (decides what goes under Zuletzt) = RouteRulesTest.isUnsaved…
        assertFalse(src("ui/RouteDialogs.kt").contains("DiscardPlanDialog"))
    }

    @Test fun theSheetHasTheActionRowAndNoMenu() {
        for (gone in listOf("SheetOverflow", "SheetMenuItems", "MoreHoriz", "SaveButton", "plan_clear_route"))
            assertFalse("$gone gone from the sheet", panel.contains(gone))
        // no Save in the sheet (owner 2026-11-05: ☰ → Save route; ✕ offers Save for an unsaved plan)
        assertFalse(Regex("onSave\\w*").containsMatchIn(panel))
        val row = panel.substringAfter("private fun PlanHead(").substringBefore("\n}\n")
        for (item in listOf("Res.string.plan_close_plan", "Res.string.plan_reverse", "Res.string.plan_round_trip"))
            assertTrue(item, row.contains(item))
        val action = panel.substringAfter("private fun PlanAction(").substringBefore("\n}\n")
        assertTrue("≥ 48 dp", action.contains("heightIn(min = 48.dp)"))
        assertTrue("a label beside the icon, never icon-only", action.contains("Text(label"))
        assertFalse("Reverse / Round trip not twice", src("ui/PointsSection.kt").contains("onReverse"))
    }

    /** Owner 2026-11-01: "Meine Routen" first, Save route under it. */
    @Test fun myRoutesIsTheTopItemSaveUnderIt() {
        assertTrue(RouteRules.saveInMenu(2))
        assertFalse(RouteRules.saveInMenu(1))
        val items = src("ui/PlanTopBar.kt").substringAfter("internal fun PlanMenuItems(").substringBefore("\n}\n")
        val order = listOf("plan_menu_my_routes", "menu.onSaveRoute", "menu.onPairBrowser", "common_settings")
            .map { items.indexOf(it) }
        // owner 2026-11-02 (#15): "Aufzeichnung starten" is gone from ☰ – the green start button does it
        assertFalse("no Start recording in ☰", items.contains("plan_menu_start_recording") || items.contains("onStartRecording"))
        assertTrue("☰: My routes · Save route · Pair with browser · Settings $order",
            order.all { it >= 0 } && order == order.sorted())
        // owner 2026-11-06 (Zuletzt): no sign and no dot for an unsaved plan – nothing is lost
        assertFalse("no dot for unsaved", PlanMenu(onSaveRoute = {}).dotWarn)
        assertFalse(File("src/main/java/com/onpath/app/ui/PlanTopBar.kt").readText().contains("UnsavedSign"))
    }

    /**
     * Owner's card 2026-11-28 09:45 („Punkte + gleiche Rechnung“, F-PLAN-26): no „Ganz neu berechnen“ in the app's ☰ – the
     * full recalculation stays inside (PlanEditState.recalcFromScratch, the planner's own row); Neu planen, Nur aufzeichnen
     * keep their order.
     */
    @Test fun noRecalcRowInTheAppMenu() {
        val items = src("ui/PlanTopBar.kt").substringAfter("internal fun PlanMenuItems(").substringBefore("\n}\n")
        assertFalse(items.contains("plan_menu_recalc"))
        assertFalse(src("ui/MapScreen.kt").contains("recalcRow"))
        val order = listOf("menu.onNewPlan", "menu.onRecordWithoutPlan", "HorizontalDivider()\n    menu.onPairBrowser").map { items.indexOf(it) }
        assertTrue("Neu planen, Nur aufzeichnen $order", order.all { it >= 0 } && order == order.sorted())
    }

}
