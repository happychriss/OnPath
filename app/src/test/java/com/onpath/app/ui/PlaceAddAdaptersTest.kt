package com.onpath.app.ui

import com.onpath.app.testkit.sourceFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The view model's side of the add slice (rebuild phase 2 piece 11): the slice's flows are PlaceAddFlowTest (:state, with a
 * recording host and a scripted router); what a flow cannot see is what the view model answers when the slice calls its host
 * and how the old names forward. Every host member is pinned by its exact line, scoped to the `placeAdd` construction (the other
 * slices' hosts hold similar lines: the map's has `canEditMap`, the edit's has `myPosition` and `touring`), every forwarder by
 * its exact line, and the places where the view model's own members (the plan edit's host, the view model's end) now go to the slice. The menus
 * that read this slice moved to PlaceMenusSlice (piece 12): their pins into this slice's members are re-pointed at that file here.
 */
class PlaceAddAdaptersTest {
    private val vm = sourceFile("ui/AppViewModel.kt").readText()
    private val head = "    val placeAdd: PlaceAddSlice = PlaceAddSlice("
    private val endAnchor = "\n    /**\n     * The menus of a held place / a route point"
    private val block = vm.substringAfter(head).substringBefore(endAnchor)

    private fun blockHas(line: String) = assertTrue("the placeAdd block must have: $line", block.contains(line))

    @Test fun theHostAnswersFromTheViewModelsOwnMembers() {
        assertTrue("the placeAdd block was found", vm.contains(head) && block.length > 2000 && block.contains("object : com.onpath.app.ui.plan.PlaceAddHost"))
        assertTrue("the block's end anchor was found", vm.substringAfter(head).contains(endAnchor))
        listOf(
            "override fun closeMapOverlays(why: String) = placeMenus.closeMapOverlays(why)",
            "override fun openPointMenu(index: Int, via: String) = placeMenus.openPointMenu(index, via = via)",
            "override fun closePlaceCard() = placeMenus.closePlaceCard()",
            "override fun planMode(): Boolean = tourSession.mode == AppMode.PLAN",
            "override fun canEditMap(): Boolean = this@AppViewModel.canEditMap",
            "override fun activity(): Activity = this@AppViewModel.planRoute.activity",
            "override fun sac(): Int = this@AppViewModel.planRoute.sac",
            "override fun routeLine(): List<GeoPoint>? = route?.points",
            "override fun touring(): Boolean = recState.value != null",
            "override fun myPosition(): GeoPoint? = this@AppViewModel.myPosition()",
            "override fun tourPassed(plan: List<GeoPoint>): Set<Int> = tourEdits.tourPassed(plan)",
            "override fun followLine(): List<GeoPoint>? = this@AppViewModel.tourFollow.followLine",
            "override fun wayThereFrom(): GeoPoint? = tourEdits.wayThereFrom()",
            "override fun gpsPoint(): GeoPoint? = tracker.location.value?.let { GeoPoint(it.latitude, it.longitude) }",
            "override fun searchMark(): com.onpath.app.search.Place? = this@AppViewModel.searchFlow.searchMark",
            "override fun takePin(lat: Double, lon: Double): com.onpath.app.search.Place? = this@AppViewModel.takePin(lat, lon)",
            "override fun isCandidate(poi: com.onpath.app.poi.Poi): Boolean = this@AppViewModel.searchFlow.isCandidate(poi)",
            "override fun rememberPlaceName(p: GeoPoint, name: String) = this@AppViewModel.searchFlow.rememberPlaceName(p, name)",
            "override fun rememberPoiKind(p: GeoPoint, kind: String?) = placeMenus.rememberPoiKind(p, kind)",
            "override fun stopIndexAt(lat: Double, lon: Double): Int = plan.points.indexOfFirst { sameSpot(it, lat, lon) }",
            "override fun routeIndexOf(place: com.onpath.app.search.Place): Int? = this@AppViewModel.routeIndexOf(place)",
        ).forEach(::blockHas)
    }

    @Test fun theSlicesCollaboratorsAreTheViewModelsOwnAndTheRouterIsTheEngineStayingHere() {
        // the router port is the Planner on the engine and the leg cache (stay in :app); the hold's own thread; the main scope
        blockHas("        plan = plan, planEdit = planEdit, planRun = planRun,\n")
        blockHas("        router = holdRouter,\n")
        assertTrue(vm.contains("    private val holdRouter = com.onpath.app.ui.plan.HoldRouter { a, b, act, level, cancel -> routedLength(a, b, act, level, cancel) }\n"))
        assertTrue("the hold's router is built before the slice", vm.indexOf("    private val holdRouter =") < vm.indexOf(head))
        blockHas("        holdWorker = planRoute.threads.hold, scope = viewModelScope,\n")
        val routed = vm.substringAfter("    private fun routedLength(").substringBefore("\n    }\n")
        assertTrue(routed, routed.contains("planRoute.legCache.lookup(a, b, act, level)?.let { return it.leg.distanceM }"))
        assertTrue(routed, routed.contains("val r = Planner(planRoute.routingEngine(), cancel, legs = planRoute.legCache).plan(listOf(a, b), act, level)"))
        assertTrue(routed, routed.contains("if (r.legs.any { it.straight }) throw com.onpath.app.routing.RoutingException(r.error ?: \"no route for the leg\")"))
        // built after the edit slice it edits through and before the slice that takes `change`
        assertTrue(vm.indexOf("private val planEdit:") < vm.indexOf(head))
        assertTrue(vm.indexOf(head) < vm.indexOf("internal val multiDay ="))
        // the view model's end stops a running measure
        assertTrue(vm.contains("        placeAdd.close()\n        planRoute.close() // the four routing threads\n"))
    }

    @Test fun theCardsRolesMapOneToOne() {
        // the forwarders of piece 11 are gone (piece 21: screens read `vm.placeAdd.x`); the one that converts stays
        assertTrue(vm.contains("    fun addPlace(place: com.onpath.app.search.Place, role: PlaceRole) = placeAdd.addPlace(place, when (role) {\n" +
            "        PlaceRole.SMART -> AddRole.SMART\n        PlaceRole.START -> AddRole.START\n        PlaceRole.DESTINATION -> AddRole.DESTINATION\n    })\n"))
    }

    @Test fun theMenusAreReadingTheSliceAndTheSliceTheirs() {
        // piece 12: the POI menu, the place menu, the label, the card and closing the menus moved to PlaceMenusSlice and read this
        // slice there (PlaceMenusAdaptersTest pins the view model's side); the anchors must be found
        val menus = sourceFile("ui/plan/PlaceMenusState.kt").readText()
        fun body(start: String): String { assertTrue("$start was found", menus.contains(start)); return menus.substringAfter(start).substringBefore("\n    }\n") }
        assertTrue(body("    fun openPoiMenu(").contains("decidePoiMenu(poi, lineSnapM, again = false)"))
        val poiMenu = body("    private fun decidePoiMenu(")
        assertTrue(poiMenu, poiMenu.contains("PlaceMenuMeasure.start(placeAdd.holdPaths, pts, p, placeAdd.lineDpOf(p, lineSnapM), PlaceAddMenu.detours(pts, p)!!,"))
        assertTrue(poiMenu, poiMenu.contains("{ a, b, cancel -> router.routedLength(a, b, act, level, cancel) }) { r ->"))
        val menu = body("    fun placeMenu(")
        assertTrue(menu, menu.contains("PlaceAddMenu.planningPlaceMenu(placeAdd.lineDpOf(p, poiMenuRows?.lineSnapM ?: 30.0), d)"))
        assertTrue(menu, menu.contains("return PlaceAddMenu.menu(waypoints.size, touring, placeAdd.zoneFor(p))"))
        assertTrue(body("    fun poiAddLabel(").contains("val ins = placeAdd.insertionFor(GeoPoint(poi.lat, poi.lon), lineSnapM)"))
        assertTrue(body("    fun placeCardUi(").contains("val ins = placeAdd.insertionFor(GeoPoint(place.lat, place.lon), 30.0)"))
        assertTrue(body("    fun makeStartFromMenu(").contains("placeAdd.startAtPoint(index)"))
        val close = body("    fun closeMapOverlays(")
        assertTrue(close, close.contains("placeAdd.cancelHoldMeasure(why)"))
        assertTrue(close, close.contains("poiMenu = null; poiMenuRows = null; placeAdd.firstPointMenu = null; placeAdd.holdAddMenu = null; if (planPoi != null) closePlaceCard()"))
    }
}
