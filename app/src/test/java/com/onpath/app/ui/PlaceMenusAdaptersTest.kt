package com.onpath.app.ui

import com.onpath.app.testkit.sourceFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val menusHead = "    private val placeMenus: com.onpath.app.ui.plan.PlaceMenusSlice = com.onpath.app.ui.plan.PlaceMenusSlice("
    private val keptHead = "    private val keptPlaces: com.onpath.app.ui.plan.KeptPlacesSlice = com.onpath.app.ui.plan.KeptPlacesSlice("
    private val keptDoc = "    /**\n     * The kept places: Zuhause + favourites ([KeptPlacesSlice]"
    private val keptEnd = "\n    private val routeRun get() = planRun.routeRun"
    private val menusBlock = vm.substringAfter(menusHead).substringBefore(keptDoc)
    private val keptBlock = vm.substringAfter(keptHead).substringBefore(keptEnd)

    private fun menusHas(line: String) = assertTrue("the placeMenus block must have: $line", menusBlock.contains(line))
    private fun keptHas(line: String) = assertTrue("the keptPlaces block must have: $line", keptBlock.contains(line))

    @Test fun theAnchorsAreFoundAndTheSlicesAreBuiltWhereTheirReadersNeedThem() {
        assertTrue(vm.contains(menusHead) && vm.contains(keptHead) && vm.contains(keptDoc) && vm.substringAfter(keptHead).contains(keptEnd))
        assertTrue("the placeMenus block", menusBlock.length > 1000 && menusBlock.contains("object : com.onpath.app.ui.plan.PlaceMenusHost"))
        assertTrue("the keptPlaces block", keptBlock.length > 600 && keptBlock.contains("object : com.onpath.app.ui.plan.PlacesStore"))
        // the menus after the add slice they read, both before the init blocks and before the slices that follow; the kept places after
        assertTrue(vm.indexOf("    private val placeAdd = PlaceAddSlice(") < vm.indexOf(menusHead))
        assertTrue(vm.indexOf(menusHead) < vm.indexOf(keptHead))
        assertTrue(vm.indexOf(keptHead) < vm.indexOf("internal val multiDay ="))
        assertTrue(vm.indexOf(keptHead) < vm.indexOf("\n    init {\n        recalc()"))
    }

    @Test fun theMenusHostAnswersFromTheViewModelsOwnMembers() {
        listOf(
            "override fun planMode(): Boolean = mode == AppMode.PLAN",
            "override fun touring(): Boolean = recState.value != null",
            "override fun activity(): Activity = this@AppViewModel.activity",
            "override fun sac(): Int = this@AppViewModel.sac",
            "override fun route(): PlannedRoute? = this@AppViewModel.route",
            "override fun wayThereFrom(): GeoPoint? = tourEdits.wayThereFrom()",
            "override fun routeIndexOf(place: com.onpath.app.search.Place): Int? = this@AppViewModel.routeIndexOf(place)",
            "override var searchMark: com.onpath.app.search.Place?\n                get() = this@AppViewModel.searchMark\n                set(v) { this@AppViewModel.searchMark = v }",
            "override var searchPlace: com.onpath.app.search.Place?\n                get() = this@AppViewModel.searchPlace\n                set(v) { this@AppViewModel.searchPlace = v }",
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
            "override fun searchMark(): com.onpath.app.search.Place? = this@AppViewModel.searchMark",
            "override fun isCandidate(poi: com.onpath.app.poi.Poi): Boolean = this@AppViewModel.isCandidate(poi)",
        ).forEach(::keptHas)
    }

    @Test fun theOldNamesForwardToTheSlices() {
        listOf(
            "fun pinnedPoiKinds(): Map<Int, String> = placeMenus.pinnedPoiKinds()",
            "fun refreshPinnedKinds() = placeMenus.refreshPinnedKinds()",
            "fun placeMenu(poi: com.onpath.app.poi.Poi): PlaceAddMenu.Menu = placeMenus.placeMenu(poi)",
            "val mapOverlayOpen: Boolean get() = placeMenus.mapOverlayOpen",
            "fun closeMapOverlays(why: String = \"closed\") = placeMenus.closeMapOverlays(why)",
            "fun closePointMenu(why: String) = placeMenus.closePointMenu(why)",
            "fun openPoiMenu(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0) = placeMenus.openPoiMenu(poi, lineSnapM)",
            "fun openPinMenu() = placeMenus.openPinMenu()",
            "fun removePin(why: String) = placeMenus.removePin(why)",
            "fun openPointMenu(index: Int, distDp: Int? = null, via: String = \"hold\") = placeMenus.openPointMenu(index, distDp, via)",
            "fun poiAddLabel(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0): String = placeMenus.poiAddLabel(poi, lineSnapM)",
            "fun makeStartFromMenu() = placeMenus.makeStartFromMenu()",
            "fun closePlaceCard() = placeMenus.closePlaceCard()",
            "fun placeCardUi(place: com.onpath.app.search.Place): com.onpath.app.ui.search.PlaceCardUi = placeMenus.placeCardUi(place)",
            "val cardPlace: com.onpath.app.search.Place? get() = placeMenus.cardPlace",
            "val pointMenuTarget: PointMenuTarget? get() = placeMenus.pointMenuTarget",
            "val pointMenu: Int? get() = placeMenus.pointMenu",
            "fun isFavouritePlace(poi: com.onpath.app.poi.Poi): Boolean = keptPlaces.isFavouritePlace(poi)",
            "fun isHomePlace(poi: com.onpath.app.poi.Poi): Boolean = keptPlaces.isHomePlace(poi)",
            "fun toggleFavouritePlace(poi: com.onpath.app.poi.Poi) = keptPlaces.toggleFavouritePlace(poi)",
            "fun toggleHomePlace(poi: com.onpath.app.poi.Poi) = keptPlaces.toggleHomePlace(poi)",
            "fun googleFromMenu(): GeoPoint? = placeMenus.menuAction(\"Open in Google Maps\")?.let { waypoints[it] }",
        ).forEach { assertTrue("forwarder: $it", vm.contains("    $it\n")) }
        // the two state holders the screens and the E2E tests set: read and written through the slice
        assertTrue(vm.contains("    var planPoi: com.onpath.app.poi.Poi?\n        get() = placeMenus.planPoi\n        set(v) { placeMenus.planPoi = v }\n"))
        assertTrue(vm.contains("    var poiMenu: com.onpath.app.poi.Poi?\n        get() = placeMenus.poiMenu\n        set(v) { placeMenus.poiMenu = v }\n"))
    }

    @Test fun theOtherSlicesAndTheViewModelsOwnLinesGoToTheSlices() {
        // the add slice's host calls the menus directly (they lived here before piece 12)
        val add = vm.substringAfter("    private val placeAdd = PlaceAddSlice(").substringBefore("\n    /**\n     * The menus of a held place / a route point")
        listOf(
            "override fun closeMapOverlays(why: String) = placeMenus.closeMapOverlays(why)",
            "override fun openPointMenu(index: Int, via: String) = placeMenus.openPointMenu(index, via = via)",
            "override fun closePlaceCard() = placeMenus.closePlaceCard()",
            "override fun rememberPoiKind(p: GeoPoint, kind: String?) = placeMenus.rememberPoiKind(p, kind)",
        ).forEach { assertTrue("the placeAdd host must have: $it", add.contains(it)) }
        // the edit slice's host too
        val edit = vm.substringAfter("private val planEdit: com.onpath.app.ui.plan.PlanEditSlice = com.onpath.app.ui.plan.PlanEditSlice(")
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
        assertTrue(vm.contains("        placeMenus.followSnapped(moves)\n"))
        assertTrue(vm.contains("if (m == AppMode.NAVIGATE) { planPoi = null; poiMenu = null; closePointMenu(\"mode change to Navigate\"); firstPointMenu = null; holdAddMenu = null; cancelHoldMeasure(\"mode change to Navigate\"); highlight = null; followMe = true }"))
        // the search flow's host reaches the menus through the view model's forwarders
        assertTrue(vm.contains("            override val mapOverlayOpen: Boolean get() = this@AppViewModel.mapOverlayOpen\n"))
        assertTrue(vm.contains("            override fun openPoiMenu(poi: com.onpath.app.poi.Poi) = this@AppViewModel.openPoiMenu(poi)\n"))
    }

    @Test fun theMovedBodiesAreNotInTheViewModelAnyMore() {
        for (gone in listOf("private class PoiRows(", "private var poiMenuRows", "private var poiKindsKnown", "private var poiKindsFound", "private val poiKindsLookedUp",
                "private var poiKindsJob", "private fun rememberPoiKind(", "private fun menuAction(", "private fun saved()", "private fun placeOf(", "private fun keepPlaces(",
                "var savedPlaces by mutableStateOf", "var pointMenuTarget by mutableStateOf", "var planPoi by mutableStateOf", "var poiMenu by mutableStateOf",
                "PlaceMenuMeasure.start(", "PinnedPoiKinds.byIndex(", "Backups.placesChanged(getApplication())\n        sync.changed(",
                "searchStore.update(change = change) } catch", "AddPreview.of(", "AddPreview.existingRole(", "Point menu opened (", "Point menu closed (point"))
            assertFalse("$gone moved to the slice", vm.contains(gone))
        // the place card's helper that stays: the search's same-spot rule (the slice asks it through its host)
        assertEquals(1, Regex("private fun routeIndexOf\\(").findAll(vm).count())
    }
}
