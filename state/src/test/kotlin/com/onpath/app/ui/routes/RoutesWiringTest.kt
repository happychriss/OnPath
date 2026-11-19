package com.onpath.app.ui.routes

import com.onpath.app.testkit.sourceFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the flows of [RoutesSlice] cannot see: the view model's own wiring of it (rebuild phase 2 piece 13). The slice is
 * tested against fakes; these pins say the app's real adapters hand the Android stores and the view model exactly what the
 * old code did – the arguments of the store, each host call, the forwarders the screens call, the order of declaration.
 */
class RoutesWiringTest {
    private val vm = sourceFile("ui/AppViewModel.kt").readText()

    private fun between(start: String, end: String): String {
        assertTrue("anchor found: $start", vm.contains(start))
        return vm.substringAfter(start).substringBefore(end)
    }

    private val store get() = between("private val toursStore = object : com.onpath.app.ui.routes.ToursStore {", "    private val saveHost")
    private val host get() = between("private val routesHost = object : com.onpath.app.ui.routes.RoutesHost {", "    val routesFlow")

    /** The one store object serves Save and Routes: its new calls read the same TourDb / the same times file as before. */
    @Test fun theToursStoreReadsThePlanPointsAndTheTimesFileAsTheViewModelDid() {
        assertTrue(store.contains("override fun planPoints(id: Long) = db.planPoints(id)"))
        // piece 14: the followed plan's copy and the tour list, one TourDb call each
        assertTrue(store.contains("override fun listTours() = db.listTours()"))
        assertTrue(store.contains("override fun getTourPlan(id: Long) = db.getTourPlan(id)"))
        assertTrue(store.contains("com.onpath.app.time.TourTimesStore(File(getApplication<Application>().filesDir, com.onpath.app.time.TourTimesStore.FILE)).all()"))
        assertTrue("Save's calls are still the same ones (SaveFlowTest, TourDbMirrorTest)", store.contains("override fun setFavourite(id: Long, favourite: Boolean) = db.setFavourite(id, favourite)"))
        assertTrue("Save and Routes share it", vm.contains("SaveSlice(plan, routes, recent, toursStore, saveHost,"))
        assertTrue(vm.contains("RoutesSlice(plan, routes, save, tour, planRoute, toursStore, mapCamera, routesHost,"))
    }

    @Test fun theHostHandsEachCallToTheViewModelMemberTheOldBodyUsed() {
        for (line in listOf(
            "override val touring: Boolean get() = recState.value != null",
            "override val recordingTourId: Long? get() = recState.value?.tourId",
            "override val mode: AppMode get() = this@AppViewModel.tourSession.mode",
            "override fun enterMode(m: AppMode) = tourSession.enter(m)",
            "override fun syncMarkSeen(t: Tour) = this@AppViewModel.syncMarkSeen(t)",
            "override fun rememberActivity(a: Activity) { prefs.activity = a }",
            "override fun rememberSac(level: Int) { prefs.sac = level }",
            "override fun loadedOnTheWay(on: Boolean) { tourEdits.loadedOnTheWay = on }",
            "override fun routeEvent(e: RouteRun.Event) { this@AppViewModel.planRun.routeEvent(e) }",
            "override fun change(block: MutableList<GeoPoint>.() -> Unit) = this@AppViewModel.planEdit.change(block)",
            "override fun rememberPlaceName(p: GeoPoint, name: String) = this@AppViewModel.searchFlow.rememberPlaceName(p, name)",
            "override fun saved(name: String?, id: Long, points: List<GeoPoint>?) = tourFollow.setSaved(name, id, points)",
            "override fun opened(t: Tour, pts: List<GeoPoint>) = multiDay.opened(t, pts)",
            "override fun syncRoutingCheck(t: Tour, pts: List<GeoPoint>) = this@AppViewModel.syncedRouting.syncRoutingCheck(t, pts)",
            "planRecalc.standIn = com.onpath.app.ui.plan.PlanRecalcSlice.StandIn(pts, act, level, preview)",
            "override fun withMaps(t: Tour) = multiDay.withMaps(t)",
            "override fun kickThumbs() = this@AppViewModel.pictures.kickThumbs()",
            "override fun clearHighlight() { highlight = null }",
            "override fun followShownNow() = tourEdits.followShownNow()",
            "override fun ensureRoutingData(parts: List<String>) = mapData.routingData.ensure(parts)",
            "override fun routingParts(track: List<GeoPoint>, act: Activity) = com.onpath.app.data.RoutingTiles.forLine(track, act)",
            "override fun tourChanged(id: Long) = Backups.tourChanged(getApplication(), id)",
            "override fun syncForget(uuid: String) { sync.files.marks.remove(uuid) }",
            "override fun syncChanged(why: String) = sync.changed(why)",
            "override fun say(text: String, kind: NoticeKind, place: NoticePlace) = notices.say(text, kind, place)",
            // piece 14 (Routes II): what the old bodies read of the view model, member by member
            "override val route: PlannedRoute? get() = this@AppViewModel.route",
            "override val routing: Boolean get() = this@AppViewModel.planRun.routing",
            "override val followIsTrack: Boolean get() = this@AppViewModel.tourFollow.followIsTrack",
            "override fun undoStep() = this@AppViewModel.planEdit.undoStep()",
            "override fun continueNavigation() = this@AppViewModel.tourSession.continueNavigation()",
            "override suspend fun share(name: String, gpx: () -> com.onpath.app.gpx.GpxFile) = this@AppViewModel.share(name, gpx)",
            "override fun stopName(p: GeoPoint) = this@AppViewModel.searchFlow.stopName(p)",
            "override fun holdCameraForSharedPlace() = this@AppViewModel.searchFlow.holdCameraForSharedPlace()",
            "get() = this@AppViewModel.map.followMe\n            set(v) { this@AppViewModel.map.followMe = v }",
            "get() = this@AppViewModel.map.freeLookAt\n            set(v) { this@AppViewModel.map.freeLookAt = v }",
        )) assertTrue("the host says: $line", host.contains(line))
    }

    /** The screens, the tests and the other slices' hosts call these names: each is a one-line forward to the slice. */
    @Test fun theOldNamesForwardToTheSlice() {
        for (line in listOf(
            "routesFlow.openTour(t, duringTour, trackThen, lineOf, planned)",
            // piece 14: Routes II
            "suspend fun followedPlanLine(t: Tour): List<GeoPoint> = routesGo.followedPlanLine(t)",
            "suspend fun tourReview(t: Tour): com.onpath.app.time.TourReview? = routesGo.tourReview(t)",
            // the plan-link host still hands the link's plan to the same function, after closing a shown track
            "override fun open(plan: com.onpath.app.routing.SharedPlan, via: String) { if (routesFlow.shown != null) routesFlow.closeShown(); routesGo.openSharedPlan(plan, via) }",
        )) assertTrue("forwarder: $line", vm.contains(line))
        // the slice's launches are the view model's scope and its two dispatchers, the elapsed clock the SystemClock
        assertTrue(vm.contains("viewModelScope, Dispatchers.IO, Dispatchers.Main, elapsedMs = { android.os.SystemClock.elapsedRealtime() })"))
        // Routes II: the same host and store, the view model's scope and io; the one-argument-each construction
        assertTrue(vm.contains("RoutesGoSlice(plan, routes, tour, planRoute, save, toursStore, links, routesFlow, mapCamera,\n        routesHost, viewModelScope, Dispatchers.IO)"))
    }

    /** Init order: the slice is built after what it takes (save, tour, planRoute, mapCamera, toursStore) and before any init that reads `shown`. */
    @Test fun theSliceIsDeclaredAfterItsNeighboursAndAboveTheInitBlocksThatReadIt() {
        fun at(s: String) = vm.indexOf(s).also { assertTrue("found: $s", it >= 0) }
        val flow = at("val routesFlow: com.onpath.app.ui.routes.RoutesSlice = com.onpath.app.ui.routes.RoutesSlice(")
        val go = at("val routesGo: com.onpath.app.ui.routes.RoutesGoSlice = com.onpath.app.ui.routes.RoutesGoSlice(")
        assertTrue("Routes II right after Routes I (it takes it), above the init blocks", flow < go && go < at("    init {\n        recalc()"))
        for (n in listOf("val links: com.onpath.app.ui.sync.PlanLinkFlow = com.onpath.app.ui.sync.PlanLinkFlow(", "private val routesHost = object : com.onpath.app.ui.routes.RoutesHost {"))
            assertTrue("$n above the Routes II slice", at(n) < go)
        for (n in listOf("private val toursStore = ", "val save: com.onpath.app.ui.routes.SaveSlice = com.onpath.app.ui.routes.SaveSlice(", "val tour: com.onpath.app.ui.tour.TourController = com.onpath.app.ui.tour.TourController(",
            "val planRoute: com.onpath.app.ui.plan.PlanRouteSlice = com.onpath.app.ui.plan.PlanRouteSlice(", "private val mapCamera = ", "val plan: com.onpath.app.ui.plan.PlanDocument = com.onpath.app.ui.plan.PlanDocument("))
            assertTrue("$n above the Routes slice", at(n) < flow)
        assertTrue("above the init that routes the saved plan and refreshes the list", flow < at("    init {\n        recalc()"))
    }

    /** The moved bodies do not exist twice: the view model holds none of the rules (one place: the slice). */
    @Test fun theViewModelKeepsNoCopyOfTheMovedBodies() {
        for (gone in listOf("TrackToPlan.fit(", "routes.toBin(", "StoredLine.preview(", "RideView.lines(", "SportType.openChoice(",
            "planFromTrackCancel", "msg_tour_delete_failed", "plan_from_track_failed", "plan_from_track_wait",
            // piece 14: Routes II lives in RoutesGoSlice only
            "RoutesGo.step(", "noteNewPlan(", "PlanLinks.load(", "PlanLinks.share(", "routes_followed_plan_missing",
            "TourReview.of(", "plan.usable", "goJob", "getTourPlan(t.id)", "RouteRules.menu(", "RouteRules.openAsk(", "routes.openAsk("))
            assertTrue("not in the view model: $gone", !vm.contains(gone))
    }
}
