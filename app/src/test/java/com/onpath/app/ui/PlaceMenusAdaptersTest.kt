package com.onpath.app.ui

import com.onpath.app.testkit.sourceFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The view model's side of the menu and kept-places slices (rebuild phase 2 piece 12): the slices' flows are
 * PlaceMenusFlowTest (:state, a recording host, a scripted router, a recording store); what a flow cannot see is what the view
 * model answers when a slice calls its host, how the old names forward and the lines that stayed in the view model and now go
 * to a slice. Every host member is pinned by its exact line, scoped to the `placeMenus` / `keptPlaces` construction (the other
 * slices' hosts hold similar lines: `planMode`, `touring`, `isCandidate`), every forwarder by its exact line; each start AND
 * end anchor must be found.
 */
class PlaceMenusAdaptersTest {
    private val vm = sourceFile("ui/AppViewModel.kt").readText()
    private val menusHead = "    val placeMenus: com.onpath.app.ui.plan.PlaceMenusSlice = com.onpath.app.ui.plan.PlaceMenusSlice("
    private val keptHead = "    val keptPlaces: com.onpath.app.ui.plan.KeptPlacesSlice = com.onpath.app.ui.plan.KeptPlacesSlice("
    private val keptDoc = "    /**\n     * The kept places: Zuhause + favourites ([KeptPlacesSlice]"
    private val keptEnd = "\n    )\n    /** The point the last edit added or moved: the map's spinner" // the forwarder `routeRun` that stood here went in piece 21
    private val menusBlock = vm.substringAfter(menusHead).substringBefore(keptDoc)
    private val keptBlock = vm.substringAfter(keptHead).substringBefore(keptEnd)

    private fun menusHas(line: String) = assertTrue("the placeMenus block must have: $line", menusBlock.contains(line))
    private fun keptHas(line: String) = assertTrue("the keptPlaces block must have: $line", keptBlock.contains(line))

    @Test fun theAnchorsAreFoundAndTheSlicesAreBuiltWhereTheirReadersNeedThem() {
        assertTrue(vm.contains(menusHead) && vm.contains(keptHead) && vm.contains(keptDoc) && vm.substringAfter(keptHead).contains(keptEnd))
        assertTrue("the placeMenus block", menusBlock.length > 1000 && menusBlock.contains("object : com.onpath.app.ui.plan.PlaceMenusHost"))
        assertTrue("the keptPlaces block", keptBlock.length > 600 && keptBlock.contains("object : com.onpath.app.ui.plan.PlacesStore"))
        // the menus after the add slice they read, both before the init blocks and before the slices that follow; the kept places after
        assertTrue(vm.indexOf("    val placeAdd: PlaceAddSlice = PlaceAddSlice(") < vm.indexOf(menusHead))
        assertTrue(vm.indexOf(menusHead) < vm.indexOf(keptHead))
        assertTrue(vm.indexOf(keptHead) < vm.indexOf("internal val multiDay ="))
        assertTrue(vm.indexOf(keptHead) < vm.indexOf("\n    init {\n        recalc()"))
    }

    @Test fun theMenusHostAnswersFromTheViewModelsOwnMembers() {
        listOf(
            "override fun planMode(): Boolean = tourSession.mode == AppMode.PLAN",
            "override fun touring(): Boolean = recState.value != null",
            "override fun activity(): Activity = this@AppViewModel.planRoute.activity",
            "override fun sac(): Int = this@AppViewModel.planRoute.sac",
            "override fun route(): PlannedRoute? = this@AppViewModel.route",
            "override fun wayThereFrom(): GeoPoint? = tourEdits.wayThereFrom()",
            "override fun routeIndexOf(place: com.onpath.app.search.Place): Int? = this@AppViewModel.routeIndexOf(place)",
            "override var searchMark: com.onpath.app.search.Place?\n                get() = this@AppViewModel.searchFlow.searchMark\n                set(v) { this@AppViewModel.searchFlow.searchMark = v }",
            "override var searchPlace: com.onpath.app.search.Place?\n                get() = this@AppViewModel.searchFlow.searchPlace\n                set(v) { this@AppViewModel.searchFlow.searchPlace = v }",
            "override suspend fun offlineIndex(): com.onpath.app.ui.plan.PlaceLookup {\n                val index = search.offlineIndex()\n",
            "return com.onpath.app.ui.plan.PlaceLookup { minLat, minLon, maxLat, maxLon, kinds -> index.placesIn(minLat, minLon, maxLat, maxLon, kinds) }",
            // the collaborators: the plan, the run, the add slice, the hold's router (the same one), the IO thread, the main scope
            "        plan = plan, planRun = planRun, placeAdd = placeAdd,\n",
            "        router = holdRouter, io = Dispatchers.IO, scope = viewModelScope,\n",
        ).forEach(::menusHas)
    }

    @Test fun theStoreOfTheKeptPlacesPassesTheOldArgumentsOn() {
        listOf(
            "override fun saved(): com.onpath.app.search.SavedPlaces = searchStore.saved()",
            "searchStore.update(change = change)",
            "override fun backupChanged() = com.onpath.app.backup.Backups.placesChanged(getApplication())",
            "override fun syncChanged() = sync.changed(\"kept places\")",
            "override fun searchMark(): com.onpath.app.search.Place? = this@AppViewModel.searchFlow.searchMark",
            "override fun isCandidate(poi: com.onpath.app.poi.Poi): Boolean = this@AppViewModel.searchFlow.isCandidate(poi)",
        ).forEach(::keptHas)
    }

    @Test fun theOldNamesForwardToTheSlices() {
        listOf(
            "fun googleFromMenu(): GeoPoint? = placeMenus.menuAction(\"Open in Google Maps\")?.let { plan.points[it] }",
        ).forEach { assertTrue("forwarder: $it", vm.contains("    $it\n")) }
    }

    @Test fun theOtherSlicesAndTheViewModelsOwnLinesGoToTheSlices() {
        // the add slice's host calls the menus directly (they lived here before piece 12)
        val add = vm.substringAfter("    val placeAdd: PlaceAddSlice = PlaceAddSlice(").substringBefore("\n    /**\n     * The menus of a held place / a route point")
        listOf(
            "override fun closeMapOverlays(why: String) = placeMenus.closeMapOverlays(why)",
            "override fun openPointMenu(index: Int, via: String) = placeMenus.openPointMenu(index, via = via)",
            "override fun closePlaceCard() = placeMenus.closePlaceCard()",
            "override fun rememberPoiKind(p: GeoPoint, kind: String?) = placeMenus.rememberPoiKind(p, kind)",
        ).forEach { assertTrue("the placeAdd host must have: $it", add.contains(it)) }
        // the edit slice's host too
        val edit = vm.substringAfter("val planEdit: com.onpath.app.ui.plan.PlanEditSlice = com.onpath.app.ui.plan.PlanEditSlice(")
            .substringBefore("scope = viewModelScope, io = Dispatchers.IO,")
        listOf(
            "override fun closePointMenu(why: String) = placeMenus.closePointMenu(why)",
            "override fun removePin(why: String) = placeMenus.removePin(why)",
            "override fun menuAction(what: String): Int? = placeMenus.menuAction(what)",
        ).forEach { assertTrue("the planEdit host must have: $it", edit.contains(it)) }
        // the restore and the browser sync drop the cache of the kept places through their hosts
        assertTrue(vm.contains("            override var savedPlaces: com.onpath.app.search.SavedPlaces?\n                get() = keptPlaces.savedPlaces\n                set(v) { keptPlaces.savedPlaces = v }"))
        assertTrue(vm.contains("            override fun placesChanged() { keptPlaces.savedPlaces = null }"))
        // a snapped route: the open point menu follows its point; entering Navigate closes the menus (through the forwarders)
        // (the snap is in `recalc`, which moved to PlanRecalcSlice, piece 9b: through its host)
        assertTrue(vm.contains("override fun menuFollowsSnap(moves: Map<GeoPoint, GeoPoint>) = placeMenus.followSnapped(moves)"))
        assertTrue(com.onpath.app.testkit.sourceFile("ui/plan/PlanRecalcState.kt").readText().contains("        host.menuFollowsSnap(moves)\n"))
        // (piece 19: `enter` is TourSession's; its menus line is the host's `closeMenusForNavigate` – the same seven statements in the
        // same order, `followMe = true` right after it in the slice; TourSessionFlowTest shows each menu closed on entering Navigate)
        assertTrue(vm.contains("override fun closeMenusForNavigate() { placeMenus.planPoi = null; placeMenus.poiMenu = null; placeMenus.closePointMenu(\"mode change to Navigate\"); placeAdd.firstPointMenu = null; placeAdd.holdAddMenu = null; placeAdd.cancelHoldMeasure(\"mode change to Navigate\"); highlight = null }"))
        assertTrue(com.onpath.app.testkit.sourceFile("ui/tour/TourSession.kt").readText().contains("if (m == AppMode.NAVIGATE) { host.closeMenusForNavigate(); map.followMe = true }"))
        // the search flow's host reaches the menus through the view model's forwarders
        assertTrue(vm.contains("            override val mapOverlayOpen: Boolean get() = this@AppViewModel.placeMenus.mapOverlayOpen\n"))
        assertTrue(vm.contains("            override fun openPoiMenu(poi: com.onpath.app.poi.Poi) = this@AppViewModel.placeMenus.openPoiMenu(poi)\n"))
    }
}
