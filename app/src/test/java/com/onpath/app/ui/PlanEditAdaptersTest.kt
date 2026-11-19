package com.onpath.app.ui

import com.onpath.app.testkit.sourceFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The view model's side of the plan-editing slice (rebuild phase 2 piece 10): the slice's flows are PlanEditFlowTest (:state,
 * with a recording host); what a flow cannot see is what the view model answers when the slice calls its host and how the
 * old names forward. Every host member is pinned by its exact line, scoped to the `planEdit` construction (another slice's
 * host holds similar lines: the map's has `followMe`, the route run's has `recalc`), and every forwarder by its exact line.
 */
class PlanEditAdaptersTest {
    private val vm = sourceFile("ui/AppViewModel.kt").readText()
    private val host = vm.substringAfter("val planEdit: com.onpath.app.ui.plan.PlanEditSlice = com.onpath.app.ui.plan.PlanEditSlice(")
        .substringBefore("scope = viewModelScope, io = Dispatchers.IO,")

    private fun hostHas(line: String) = assertTrue("the planEdit host must have: $line", host.contains(line))

    @Test fun theHostAnswersFromTheViewModelsOwnMembers() {
        assertTrue("the planEdit block was found", host.length > 1500 && host.contains("object : com.onpath.app.ui.plan.PlanEditHost"))
        listOf(
            "override fun cancelHoldMeasure(why: String) = placeAdd.cancelHoldMeasure(why)",
            "override fun stopFollowingWhenMapTurns() { if (tourSession.mode == AppMode.PLAN && settingsSlice.settings.navHeadingUp && map.followMe) map.followMe = false }",
            "override fun closePointMenu(why: String) = placeMenus.closePointMenu(why)",
            "override fun clearFirstPointMenu() { placeAdd.firstPointMenu = null }",
            "override fun clearHoldAddMenu() { placeAdd.holdAddMenu = null }",
            "override fun clearHighlight() { highlight = null }",
            "override fun removePin(why: String) = placeMenus.removePin(why)",
            "override fun menuAction(what: String): Int? = placeMenus.menuAction(what)",
            "override fun setRoutingSpot(spot: GeoPoint?) { routingSpot = spot }",
            "override fun loadMapsForNewPoints(before: List<GeoPoint>) = cellsForNewPoints(before, plan.points)",
            "override fun keepAsDraft(why: String) = save.keepAsDraft(why)",
            "override fun routeLine(): List<GeoPoint>? = route?.points",
            "override fun recalc() = this@AppViewModel.recalc()",
            "override fun restoreActivity(c: PlanStatus.Cause.ActivityChange) = this@AppViewModel.restoreActivity(c)",
            "override fun planChanged() = this@AppViewModel.planRoute.planChanged()",
            "override fun myPosition(): GeoPoint? = this@AppViewModel.myPosition()",
            "override fun touring(): Boolean = recState.value != null",
            "override fun following(): Boolean = tourFollow.followLine != null",
            "override fun followIsTrack(): Boolean = this@AppViewModel.tourFollow.followIsTrack",
            "override fun followsLine(line: List<GeoPoint>): Boolean = tourFollow.followLine == line",
            "override fun followFromPlan(): Boolean = this@AppViewModel.tourFollow.followFromPlan",
            "override fun splitOf(before: List<GeoPoint>, after: List<GeoPoint>): TourSplit.Outcome? = tourEdits.splitOf(before, after)",
            "override fun sayNoDestination() = sayEvent(RecordingService.SAY_NO_DESTINATION)",
            "override fun oneLeftGoesFromHere(before: List<GeoPoint>, mayEmpty: Boolean) = tourEdits.oneLeftGoesFromHere(before, mayEmpty)",
            "override fun endGuidanceIfPlanEmpty() = tourEdits.endGuidanceIfPlanEmpty()",
            "override fun clearFollow() = this@AppViewModel.tourFollow.clearFollow()",
            "override fun guidanceEndedByEmptyPlan() { this@AppViewModel.tourFollow.guidanceEndedByEmptyPlan = true }",
            "override fun stopName(p: GeoPoint): String? = this@AppViewModel.searchFlow.stopName(p)",
            "val index = search.offlineIndex()",
            "com.onpath.app.search.RouteNamer.label(p, com.onpath.app.search.RouteNamer.placesNear(index, listOf(p)))",
            "get() = this@AppViewModel.searchFlow.searchMark",
            "set(v) { this@AppViewModel.searchFlow.searchMark = v }",
        ).forEach(::hostHas)
    }

    @Test fun theSlicesCollaboratorsAreTheViewModelsOwnAndRunOnItsScope() {
        assertTrue(host.contains("scope = viewModelScope") || vm.contains("scope = viewModelScope, io = Dispatchers.IO,\n    )"))
        val head = vm.substringAfter("val planEdit: com.onpath.app.ui.plan.PlanEditSlice = com.onpath.app.ui.plan.PlanEditSlice(").substringBefore("host = object")
        assertEquals("\n        plan = plan, planRun = planRun, runCauses = planRoute.runCauses,\n        ", head)
        // Cancel of the route run reaches the slice's take-back
        assertTrue(vm.contains("            override fun cancelRun(why: NotRouted) = planEdit.cancelRun(why)\n"))
        // the slice is built before the one that takes `change` (a bound reference needs no slice, but the order was chosen on purpose)
        assertTrue(vm.indexOf("private val planEdit:") < vm.indexOf("internal val multiDay ="))
    }

    @Test fun theOldNamesForwardToTheSlice() {
        listOf(
            "fun planPointRows(): List<com.onpath.app.routing.PlanPointRow> = planEdit.planPointRows(route)",
        ).forEach { assertTrue("forwarder: $it", vm.contains("    $it\n")) }
        // pinTaken is the add slice's to write (piece 11) and the edit slice's to keep: the view model no longer holds it
        assertTrue("no pinTaken forwarder", !vm.contains("pinTaken"))
    }

    @Test fun theEditingFunctionsAreNotInTheViewModelAnyMore() {
        for (gone in listOf("fun reverseRoute() {", "fun roundTrip() {", "fun clearPlan() {", "private fun step(", "private fun cancelRun(",
                "fun movePointInList(from: Int, to: Int) {", "fun deletePointFromList(index: Int) {", "private var namesJob"))
            assertTrue("$gone moved to the slice", !vm.contains(gone))
    }
}
