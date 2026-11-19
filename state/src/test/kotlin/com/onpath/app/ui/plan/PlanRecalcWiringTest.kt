package com.onpath.app.ui.plan

import com.onpath.app.testkit.sourceFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [PlanRecalcFlowTest] cannot see: the view model's own wiring of the route run's coroutine (rebuild phase 2 piece 9b). The slice
 * is tested against fakes; these pins say the real adapters hand `MapDataFlow`, the router, the clock and the view model's state exactly
 * what the old `recalc` body used, member by member, and that the slice sits where the init needs it. Style of `TourWiringTest`.
 */
class PlanRecalcWiringTest {
    private val vm = sourceFile("ui/AppViewModel.kt").readText()
    private val slice = sourceFile("ui/plan/PlanRecalcSlice.kt").readText()

    private fun count(hay: String, needle: String): Int {
        var n = 0; var i = hay.indexOf(needle)
        while (i >= 0) { n++; i = hay.indexOf(needle, i + needle.length) }
        return n
    }

    private fun between(start: String, end: String): String {
        assertEquals("anchor found exactly once: $start", 1, count(vm, start))
        val a = vm.indexOf(start)
        val b = vm.indexOf(end, a + start.length)
        assertTrue("end anchor found after the start: ${end.trim()}", b > a)
        return vm.substring(a, b)
    }

    private val ctor get() = between("private val planRecalc: com.onpath.app.ui.plan.PlanRecalcSlice = com.onpath.app.ui.plan.PlanRecalcSlice(", "\n    )\n")
    private val port get() = between("mapData = object : com.onpath.app.ui.plan.MapDataPort {", "        },\n")
    private val hostAdapter get() = between("host = object : com.onpath.app.ui.plan.PlanRecalcHost {", "        },\n")

    @Test fun theViewModelKeepsRecalcAsOneLineForwarderAndTheBigInitCallsItFirst() {
        assertEquals(1, count(vm, "    private fun recalc(): Unit = planRecalc.recalc()\n"))
        assertTrue("recalc() first", vm.contains("    init {\n        recalc()\n"))
        // the body is gone from the view model
        for (gone in listOf("private fun showProgress", "private fun namedStops", "private fun snapToRoute", "private fun aliasSnappedLegs", "private fun findReach",
            "private fun freshenAfterRoute", "private fun routingDataRouteChanged", "private inline fun <T> exceptions", "private class StandIn",
            "private var standIn", "private var routingDataBefore", "ROUTING_SLOW_MS", "WAIT_POLL_MS", "RouteRun.Event.Recalc(", "launchSafe(\"app\", \"recalc\")"))
            assertEquals("gone from the view model: $gone", 0, count(vm, gone))
    }

    @Test fun theSliceSitsAfterTheEditsSliceItReachesAndAboveEveryInit() {
        val at = vm.indexOf("private val planRecalc: com.onpath.app.ui.plan.PlanRecalcSlice")
        val edits = vm.indexOf("private val tourEdits: com.onpath.app.ui.tour.TourEdits = ")
        val firstInitAfterEdits = vm.indexOf("    init {\n", edits)
        val big = vm.indexOf("    init {\n        recalc()\n")
        assertTrue("after tourEdits (followCancel, followEditedPlan)", edits in 0 until at)
        assertTrue("above the small init that follows tourEdits", at < firstInitAfterEdits)
        assertTrue("above the big init", at < big)
        for (dep in listOf("private val plan = ", "val planRun = ", "private val planRoute = ", "private val planEdit: ", "private val tourFollow = ",
            "private val map = ", "private val links = ", "val recState: StateFlow<RecState?> = recording.state", "private val mapData by lazy"))
            assertTrue("declared above the slice: $dep", vm.indexOf(dep) in 0 until at)
    }

    @Test fun theConstructorHandsTheSliceItsNeighboursAndTheThreadsTheOldCodeUsed() {
        for (line in listOf(
            "plan = plan, planRun = planRun, planRoute = planRoute, planEdit = planEdit, tourEdits = tourEdits, tourFollow = tourFollow,",
            "map = map, links = links, recording = recording,",
            "say = notices,",
            "scope = viewModelScope, routingDispatcher = routingDispatcher, io = Dispatchers.IO, main = Dispatchers.Main, default = Dispatchers.Default,",
            "elapsedMs = { android.os.SystemClock.elapsedRealtime() },",
            "searchProgress = com.onpath.app.ui.plan.RouteSearchProgress { eng, t -> (eng as? com.onpath.app.routing.BRouterEngine)?.searchProgress(t) },",
        )) assertTrue("the constructor says: $line", ctor.contains(line))
    }

    @Test fun theMapDataPortHandsEachCallToMapDataFlowWithTheArgumentsTheOldBodyUsed() {
        for (line in listOf(
            "mapData.postRoute(r, pts, activity).let { com.onpath.app.ui.plan.MapDataPort.PostRoute(it.routingKeys, it.tilesMissing, it.cells) }",
            "override fun routingForLine(keys: List<String>) { mapData.routingForLine(keys) }",
            "override val routingLoading: Boolean get() = mapData.routingLoading",
            "override fun corridorFollowsPlan(line: List<GeoPoint>?, activity: Activity?) = mapData.corridorFollowsPlan(line, activity)",
            "mapData.routeComputed(line, corridor, dest, activity)",
            "mapData.freshenAlong(line, corridor, activity)",
        )) assertTrue("the port says: $line", port.contains(line))
        // the lazy `mapData` is touched only inside the port's members (when called), never while the slice is built
        val outside = ctor.replace(port, "").lines().filter { it.contains("mapData.") || it.contains("mapData)") }
        assertEquals("no `mapData` access outside the port object", emptyList<String>(), outside)
    }

    @Test fun theHostHandsEachCallToTheViewModelMemberTheOldBodyUsed() {
        for (line in listOf(
            "override val mode: AppMode get() = this@AppViewModel.mode",
            "override fun nameOfStop(p: GeoPoint): String? = this@AppViewModel.stopName(p)",
            "override val multiDayOverview: Boolean get() = multiDay.overview",
            "override fun menuFollowsSnap(moves: Map<GeoPoint, GeoPoint>) = placeMenus.followSnapped(moves)",
            "override fun checkRoutingDataFor(line: List<GeoPoint>) = this@AppViewModel.checkRoutingData(line)",
        )) assertTrue("the host says: $line", hostAdapter.contains(line))
        for (state in listOf("route: PlannedRoute?", "routeError: String?", "highlight: GeoPoint?", "planReach: com.onpath.app.routing.PlanReach?", "planResult: com.onpath.app.routing.PlanResult?")) {
            val name = state.substringBefore(":")
            assertTrue("$name reads and writes the view model's", hostAdapter.contains("override var $state\n                get() = this@AppViewModel.$name\n                set(v) { this@AppViewModel.$name = v }"))
        }
    }

    @Test fun otherPlacesCallTheForwarderOrTheSliceAndTheOneThatSetsTheNewerDataFlagUsesTheSlice() {
        assertTrue(vm.contains("planRecalc.routingDataBefore = route?.points; routeEvent(RouteRun.Event.NewerData); recalc()"))
        assertTrue(vm.contains("planRecalc.standIn = com.onpath.app.ui.plan.PlanRecalcSlice.StandIn(pts, act, level, preview)"))
        assertEquals("the three hosts of other slices and the tour's re-plan keep their forwarders", 3, count(vm, "override fun recalc() = this@AppViewModel.recalc()"))
        assertEquals(1, count(vm, "override fun replan() = recalc()"))
    }

    /** The rules for the slice itself (no work at construction, no Android, no view model scope). */
    @Test fun theSliceDoesNoWorkAtConstructionAndHasNoAndroid() {
        assertFalse("no init block", slice.contains("    init {"))
        assertFalse("no lazy", slice.contains(" by lazy"))
        val code = slice.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("/**") || it.trim().startsWith("//") }.joinToString("\n")
        assertEquals(0, count(code, "android.") + count(code, "viewModelScope") + count(code, "SystemClock") + count(code, "Dispatchers."))
        // every launch is the old kind and name
        assertEquals(1, count(slice, "routeJob = scope.launchSafe(\"app\", \"recalc\") {"))
        assertEquals(1, count(slice, "scope.launchSafe(\"app\", \"showProgress\", main) {"))
        assertEquals(1, count(slice, "scope.launchSafe(\"app\", \"aliasSnappedLegs\", io) {"))
        assertEquals("the head's first statement", 1, count(slice, "    fun recalc() {\n        runCauses.start()\n"))
    }

    /** A host member must not carry the name of a view-model function (`ViewModelInitOrderTest` follows every name an `init` can reach). */
    @Test fun noHostFunctionCarriesTheNameOfAViewModelFunction() {
        val host = slice.substringAfter("interface PlanRecalcHost {").substringBefore("\n}\n")
        val names = Regex("""\n    fun (\w+)\(""").findAll(host).map { it.groupValues[1] }.toList()
        assertTrue(names.toString(), names.size == 3)
        for (n in names) assertEquals("the view model has a function $n", 0, Regex("""\n    (private |internal )?fun $n\(""").findAll(vm).count())
    }
}
