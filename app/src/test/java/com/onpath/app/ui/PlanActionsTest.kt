package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
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
    private val a = GeoPoint(46.680, 11.140)
    private val b = GeoPoint(46.700, 11.120)

    private fun src(f: String) = File("src/main/java/com/onpath/app/$f").readText()
    private val vm = src("ui/AppViewModel.kt")
    private val panel = src("ui/PlanPanel.kt")

    @Test fun closePlanAsksOnlyWithUnsavedChanges() {
        val body = vm.substringAfter("fun requestClear()").substringBefore("\n")
        // owner 2026-11-05: during a tour it asks too ("Plan leeren? Deine Tour läuft weiter …")
        assertTrue(body, body.contains("if (ClearAsk.asks(planUnsaved, recState.value != null, waypoints.size)) discardAsk = true else clearPlan()"))
        assertTrue(vm.contains("fun discardConfirmed() { discardAsk = false; clearPlan() }"))
        // unsaved = a new plan of two points, or a saved one changed since
        assertTrue(RouteRules.isUnsaved(listOf(a, b), null))
        assertFalse(RouteRules.isUnsaved(listOf(a, b), listOf(a, b)))
        val dialogs = src("ui/RouteDialogs.kt")
        assertTrue(dialogs.contains("if (vm.discardAsk) DiscardPlanDialog(onDiscard = vm::discardConfirmed"))
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
        // owner 2026-11-06 (#41 B): unsaved = the "Nicht gespeichert ›" sign beside the mode label, no green dot on ☰
        assertTrue(PlanMenu(onSaveRoute = {}, unsaved = true).showUnsaved)
        assertFalse("no dot for unsaved", PlanMenu(onSaveRoute = {}, unsaved = true).dotWarn)
    }

    @Test fun copyAndShareLiveInMyRoutes() {
        val screens = src("ui/Screens.kt")
        assertTrue(screens.contains("onCopy = if (t.kind == TourKind.PLANNED) ({ onCopy(t) }) else null"))
        assertTrue(screens.contains("Res.string.plan_save_as_copy"))
        assertTrue(screens.contains("Res.string.plan_share_plan"))
        val main = src("MainActivity.kt")
        assertTrue(main.contains("onShare = { vm.shareRoute(it) }"))
    }
}
