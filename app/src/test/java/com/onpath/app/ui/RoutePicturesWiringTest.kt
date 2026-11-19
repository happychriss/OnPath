package com.onpath.app.ui

import com.onpath.app.testkit.sourceFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 piece 6: the route pictures and the demo rides moved from AppViewModel to plain classes in :app. What these
 * helpers decide is covered by ThumbRules / OverviewRules / RouteThumbsTest / OverviewPicturesTest / DemoPathTest /
 * NavSimStartTest / DemoRulesTest (:state); the picture work itself by RouteThumbE2ETest / OverviewSwipeE2ETest. This pins
 * the wiring: what the view model hands in and forwards, and the stored places / guards that moved verbatim.
 */
class RoutePicturesWiringTest {
    private val vm = sourceFile("ui/AppViewModel.kt").readText()
    private val pics = sourceFile("ui/RoutePictures.kt").readText()
    private val demo = sourceFile("ui/DemoRides.kt").readText()
    private fun has(src: String, what: String, regex: Boolean = false) =
        assertTrue("not found: $what", if (regex) Regex(what).containsMatchIn(src) else src.contains(what))

    @Test fun theViewModelBuildsThePicturesLazilyWithItsScopeIoStoreAndRoutes() {
        has(vm, """val pictures: RoutePictures by lazy \{\s+RoutePictures\(getApplication\(\), viewModelScope, Dispatchers\.IO, db, routes, object : RoutePicturesHost""", true)
        // (piece 21: the one-line forwards `thumbs`, `thumbVersion`, `kickThumbs` … are gone, the screens read `vm.pictures.x`)
        has(vm, "pictures.overviewShot(t, segments, tint, widthPx, heightPx, paddingPx, under)")
        has(vm, "pictures.routeSnapshot(segments, tint, widthPx, heightPx, paddingPx, under)")
    }

    @Test fun theViewModelBuildsTheDemoRidesLazilyAndForwardsEveryMember() {
        has(vm, """val demo: DemoRides by lazy \{\s+DemoRides\(viewModelScope, Dispatchers\.IO, db, object : DemoRidesHost""", true)
        // (piece 21: the forwards of the members are gone, the screens read `vm.demo.x`)
        has(vm, "if (com.onpath.app.recording.DemoLocation.endWithTour()) demo.navSimLine = null")
    }

    @Test fun theStoredFoldersAndTheBusyRuleAreAsTheyWere() {
        has(pics, "java.io.File(app.filesDir, \"thumbs\")")
        has(pics, "java.io.File(app.filesDir, \"overviews\")")
        // never while navigating or recording: one rule per maker (row pictures, overview pictures)
        val busy = "busy = { host.mode == AppMode.NAVIGATE || host.recState.value != null }"
        assertTrue("busy rule once per maker", pics.split(busy).size - 1 == 2)
        val thumbBlock = pics.substringAfter("private val thumbMaker by lazy").substringBefore("private val overviewMaker")
        val overviewBlock = pics.substringAfter("private val overviewMaker by lazy").substringBefore("suspend fun overviewShot")
        has(thumbBlock, busy); has(overviewBlock, busy)
        has(pics, "scope.launchSafe(\"routes\", \"Map picture shown\", Dispatchers.Main) { thumbVersion++ }")
        has(pics, "overviewPictures.setSize(widthPx, heightPx, paddingPx, done = t.kind == TourKind.RECORDED)") // owner 2026-11-15
        has(pics, "val store = t.uuid.isNotEmpty() && t.id >= 0") // the plan on the map is never stored
        has(pics, "Map pictures not started")
        has(pics, "Overview pictures not started")
    }

    @Test fun theDemoRidesKeepTheirGuards() {
        has(demo, "if (path == null) { host.sayInfo(\"This line is too short for a demo ride.\", NoticePlace.SETTINGS); return }")
        has(demo, "navSimLine = null // a recorded tour is ridden as it was")
        has(demo, "if (navSimLine == null || line == null || line.size < 2 || line == navSimLine) return")
        has(demo, "DemoRules.recentRecordedTours(host.tours, com.onpath.app.recording.DemoLocation.TOUR_PREFIX)")
    }

    private fun block(start: String): String {
        val a = vm.indexOf(start); assertTrue("not found: $start", a >= 0)
        return vm.substring(a, vm.indexOf("        })\n", a))
    }

    @Test fun thePicturesHostAdapterForwardsEachMemberToItsOwn() {
        val b = block("object : RoutePicturesHost {")
        for (l in listOf(
            "override val mode: AppMode get() = this@AppViewModel.tourSession.mode",
            "override val recState: StateFlow<RecState?> get() = this@AppViewModel.recState",
            "override val settings: AppSettings get() = this@AppViewModel.settingsSlice.settings",
            "override val dataFiles: DataFiles get() = this@AppViewModel.dataFiles",
            "override val devToolsShown: Boolean get() = this@AppViewModel.settingsSlice.devToolsShown",
            "override fun detailedAreas() = this@AppViewModel.mapData.detailedAreas()",
            "override fun packageAreas() = this@AppViewModel.mapData.packageAreas()",
            "override suspend fun followedPlanLine(t: Tour) = this@AppViewModel.followedPlanLine(t)")) has(b, l)
    }

    @Test fun theDemoHostAdapterForwardsEachMemberToItsOwn() {
        val b = block("object : DemoRidesHost {")
        for (l in listOf(
            "override val route: PlannedRoute? get() = this@AppViewModel.route",
            "override val shown: ShownTrack? get() = this@AppViewModel.routesFlow.shown",
            "override val activity: Activity get() = this@AppViewModel.planRoute.activity",
            "override val recState: StateFlow<RecState?> get() = this@AppViewModel.recState",
            "override val tours: List<Tour> get() = this@AppViewModel.routes.tours",
            "override fun planFactors() = this@AppViewModel.planRoute.planFactors()",
            "override fun sayInfo(text: String, place: NoticePlace) = this@AppViewModel.notices.sayInfo(text, place)",
            "override fun sayError(text: String, place: NoticePlace) = this@AppViewModel.notices.sayError(text, place)",
            "override fun initialCamera() = this@AppViewModel.initialCamera()")) has(b, l)
    }
}
