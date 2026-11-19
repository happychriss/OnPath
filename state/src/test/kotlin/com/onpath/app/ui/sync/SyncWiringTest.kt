package com.onpath.app.ui.sync

import com.onpath.app.testkit.sourceFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the flows of [SyncedRoutingSlice] and [SyncDriver] cannot see: the view model's own wiring of them (rebuild phase 2
 * piece 15). Both are tested against fakes; these pins say the app's real adapters hand the sync, the stores and the view model
 * exactly what the old code did, member by member, and that the forwarders the screens and the other slices call are still
 * there. Each block is cut out of the file by its own anchors (a pin in another block does not count).
 */
class SyncWiringTest {
    private val vm = sourceFile("ui/AppViewModel.kt").readText()

    private fun between(start: String, end: String): String {
        assertTrue("anchor found: $start", vm.contains(start))
        val from = vm.substringAfter(start)
        assertTrue("end anchor found after it: $end", from.contains(end))
        return from.substringBefore(end)
    }

    private val routingHost get() = between("object : com.onpath.app.ui.sync.SyncedRoutingHost {", "}, viewModelScope, Dispatchers.IO)")
    private val driverEngine get() = between("engine = object : com.onpath.app.ui.sync.SyncEngine {", "        host = object")
    private val driverHost get() = between("host = object : com.onpath.app.ui.sync.SyncHost {", "        recording = recState")

    /** The routing slice is given the plan, the route slice and the one tours store (the same TourDb calls as Save and Routes). */
    @Test fun theRoutingSliceGetsTheSameStoreAsSaveAndRoutesAndRunsOnTheViewModelScopeAndIo() {
        assertTrue(vm.contains("SyncedRoutingSlice(plan, planRoute, toursStore,"))
        assertTrue(vm.contains("}, viewModelScope, Dispatchers.IO)"))
    }

    @Test fun theRoutingHostHandsEachCallToTheMemberTheOldBodyUsed() {
        for (line in listOf(
            "override fun opened(uuid: String, points: List<GeoPoint>) = sync.opened(uuid, points)",
            "override fun refreshRoutingTiles(tiles: List<String>, why: String) = this@AppViewModel.refreshRoutingTiles(tiles, why)",
            "override fun refreshTours() = this@AppViewModel.routesFlow.refreshTours()",
            "override fun setSaved(name: String?, id: Long, points: List<GeoPoint>?) = this@AppViewModel.tourFollow.setSaved(name, id, points)",
            "override fun tourChanged(id: Long) = Backups.tourChanged(getApplication(), id)",
        )) assertTrue("routing host: $line", routingHost.contains(line))
        assertEquals("nothing else is handed over", 5, Regex("override fun ").findAll(routingHost).count())
    }

    @Test fun theForwardersTheScreensAndTheOtherSlicesCallStayAndTheArrivedDataRoutesWhatWaited() {
        for (line in listOf(
            "private fun syncMarkSeen(t: Tour) = syncDriver.markSeen(t.uuid)",
        )) assertTrue("forwarder: $line", vm.contains(line))
        // review routing M5: the routes that waited for routing data are routed after the batch of arriving parts (once)
        val arrived = between("private fun routingDataArrived", "\n    }\n")
        assertTrue(arrived.contains("syncedRouting.routeWaiting()"))
        assertTrue("after the gate: inside the batch's lambda, before the recalc of the open plan",
            arrived.indexOf("syncedRouting.routeWaiting()") in arrived.indexOf("gated(") until arrived.indexOf("recalc()"))
    }

    /** The sync object is given the same recording check as before; the driver's engine port is one call each. */
    @Test fun theDriversEnginePortIsOneCallOfThePlanSyncEach() {
        for (line in listOf(
            "override val state get() = sync.state",
            "override val marks get() = sync.files.marks.state",
            "override fun loadMarks() { sync.files.marks.all() }",
            "override fun markSeen(uuid: String) { sync.files.marks.remove(uuid) }",
            "override fun start() { sync.start() }",
            "override fun quiet() = sync.quiet()",
            "override fun busy() = sync.busy()",
            "override fun request(why: String, manual: Boolean) { sync.request(why, manual) }",
            "override fun poll(why: String) = sync.poll(why)",
        )) assertTrue("engine: $line", driverEngine.contains(line))
        assertEquals(9, Regex("override ").findAll(driverEngine).count())
        assertTrue(vm.contains("recording = { recState.value != null }, onReport = { r -> syncDriver.onReport(r) })"))
        assertTrue(vm.contains("recording = recState.map { it != null }, isRecording = { recState.value != null },"))
        assertTrue(vm.contains("scope = viewModelScope, io = Dispatchers.IO, clock = { android.os.SystemClock.elapsedRealtime() })"))
    }

    /**
     * The driver's host: what a pass's report does in the app. `toursChanged` is the order the old code had – the list, the open
     * plan follows its saved route, the plans that came are routed (SyncedRoutingFlowTest drives the same three through the real
     * driver; this pins that the app's host is that).
     */
    @Test fun theDriversHostSaysItInTheSamePlacesAndRunsTheThreeStepsInOrder() {
        assertTrue(driverHost.contains("override fun ended() { notices.sayInfo(Txt.s(Res.string.sync_ended), NoticePlace.SETTINGS) }"))
        assertTrue(driverHost.contains("override fun placesChanged() { keptPlaces.savedPlaces = null }"))
        assertTrue(driverHost.contains("override fun toursChanged(toRoute: List<Long>) { routesFlow.refreshTours(); syncedRouting.followSyncedSavedPlan(); syncedRouting.routeSyncedPlans(toRoute) }"))
        assertTrue(driverHost.contains("override fun newerData(tiles: List<String>) { refreshRoutingTiles(tiles, \"a plan from the browser was routed with newer data\") }"))
        assertTrue(driverHost.contains("SyncPoll.routesScreen(routes.open, settingsSlice.showSettings, settingsSlice.settingsPage == SettingsPage.SYNC)"))
        assertTrue(driverHost.contains("SyncPoll.shown(map.appOnScreen, routes.open, routes.tab == RoutesTab.PLANNED, settingsSlice.showSettings,"))
        assertTrue("the driver starts once, in its own init", vm.contains("init { syncDriver.start() }"))
    }

    /** The slice file still carries the thread of the routing: the background dispatcher, then the interactive stores' io. */
    @Test fun theBrowserPlansAreRoutedOnTheBackgroundRoutingThreadAndNeverTheUsersOwn() {
        val slice = sourceFile("ui/sync/SyncedRoutingSlice.kt").readText()
        val routing = slice.substringAfter("fun routeSyncedPlans(").substringBefore("\n    }\n")
        assertTrue(routing.contains("withContext(planRoute.threads.background)"))
        assertTrue("not the plan's own routing thread", !routing.contains("threads.routing"))
    }
}
