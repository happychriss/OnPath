package com.onpath.app.ui.tour

import com.onpath.app.testkit.sourceFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the flows of the tour slices cannot see: the view model's own wiring of them (rebuild phase 2 piece 16b on; the later
 * tour pieces add their slices here). The slices are tested against fakes; these pins say the app's real adapters hand the
 * Android stores, the location tracker and the view model exactly what the old code did, member by member, and that the
 * slice sits where its readers need it. Style of `RoutesWiringTest`.
 */
class TourWiringTest {
    private val vm = sourceFile("ui/AppViewModel.kt").readText()
    private val slice = sourceFile("ui/tour/ActivityCheckSlice.kt").readText()

    private fun count(hay: String, needle: String): Int {
        var n = 0; var i = hay.indexOf(needle)
        while (i >= 0) { n++; i = hay.indexOf(needle, i + needle.length) }
        return n
    }

    /** From [start] (found once) to the next [end] (found after it). */
    private fun between(start: String, end: String): String {
        assertEquals("anchor found exactly once: $start", 1, count(vm, start))
        val a = vm.indexOf(start)
        val b = vm.indexOf(end, a + start.length)
        assertTrue("end anchor found after the start: ${end.trim()}", b > a)
        return vm.substring(a, b)
    }

    // ---------------- piece 16b: the activity check ----------------

    private val check get() = between("private val activityCheckSlice = com.onpath.app.ui.tour.ActivityCheckSlice(", "\n    )\n")
    private val rowsAdapter get() = between("rows = object : com.onpath.app.ui.tour.TourRows {", "        },")
    private val fixesAdapter get() = between("fixes = object : com.onpath.app.ui.tour.Fixes {", "        },")
    private val hostAdapter get() = between("host = object : com.onpath.app.ui.tour.ActivityCheckHost {", "        },")

    /** The slice gets the view model's ONE client, FollowStore, plan route and the scope: nothing else may reach the service. */
    @Test fun theSliceGetsTheViewModelsOwnObjects() {
        for (line in listOf(
            "planRoute = planRoute, recording = recording, follow = followStore,",
            "scope = viewModelScope, io = Dispatchers.IO, cpu = Dispatchers.Default,",
        )) assertTrue("the constructor says: $line", check.contains(line))
        assertEquals("one recording client in the view model", 1, count(vm, "ServiceRecordingClient(app)"))
        assertTrue(vm.contains("private val recording: RecordingClient = ServiceRecordingClient(app)"))
        assertEquals("one FollowStore in the view model", 1, count(vm, "FollowStore(app.filesDir)"))
    }

    /** The one tour-row write the old code made: TourDb's `updateActivity`, every argument by name. */
    @Test fun theRowsAdapterWritesTheActivityOfTheTourWithNamedArguments() {
        assertTrue(rowsAdapter, rowsAdapter.contains(
            "override fun updateActivity(id: Long, activity: String, sac: Int) = db.updateActivity(tourId = id, activity = activity, sac = sac)"))
    }

    /** The fix: its altitude only when it has one; the first fix is waited for on the tracker's flow, as the old body did. */
    @Test fun theFixesAdapterReadsTheTrackersLocationAsTheOldBodyDid() {
        for (line in listOf(
            "private fun point(loc: android.location.Location) = GeoPoint(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else null)",
            "override fun now(): GeoPoint? = tracker.location.value?.let { point(it) }",
            "override suspend fun first(): GeoPoint = point(tracker.location.filterNotNull().first())",
        )) assertTrue("the adapter says: $line", fixesAdapter.contains(line))
    }

    @Test fun theHostHandsEachCallToTheViewModelMemberTheOldBodyUsed() {
        for (line in listOf(
            "override val mode: AppMode get() = this@AppViewModel.mode",
            "override fun enterNavigate() = enter(AppMode.NAVIGATE)",
            "override val followFromPlan: Boolean get() = this@AppViewModel.followFromPlan",
            "override val planPoints: Int get() = waypoints.size",
            "override fun routeEvent(e: RouteRun.Event) { this@AppViewModel.routeEvent(e) }",
            "override fun rememberSac(level: Int) { prefs.sac = level }",
        )) assertTrue("the host says: $line", hostAdapter.contains(line))
    }

    /** The screens and tests call these names: each is a one-line forward; the sheet's state is read from the slice. */
    @Test fun theOldNamesForwardToTheSlice() {
        for (line in listOf(
            "val activityCheck: ActivityCheckUi? get() = activityCheckSlice.activityCheck",
            "fun openActivityCheck() = activityCheckSlice.openActivityCheck()",
            "fun chooseHikeLevel(level: Int) = activityCheckSlice.chooseHikeLevel(level)",
            "fun chooseActivity(a: Activity) = activityCheckSlice.chooseActivity(a)",
            "fun closeActivityCheck() = activityCheckSlice.closeActivityCheck()",
        )) assertEquals("the view model says: $line", 1, count(vm, line))
    }

    /** What the old block's follow state kept in the view model (piece 18a moves it): it must not have been taken along. */
    @Test fun theFollowStateOfTheOldBlockStaysInTheViewModel() {
        for (line in listOf(
            "private var navActivitySeen: Activity? = null",
            "private var planBaseline: List<GeoPoint>? = null",
            "private fun planFollowed(planLine: List<GeoPoint>?): Boolean =",
            "private fun syncNavActivity(s: NavSession?) {",
        )) assertEquals("still in the view model: $line", 1, count(vm, line))
        for (name in listOf("navActivitySeen", "planBaseline", "planFollowed")) assertFalse(
            "the slice does not touch the follow state: $name", slice.contains(name))
    }

    /** Nothing runs at construction and no collector is involved: the slice has no `init`, no `collect`, no launch outside a user action. */
    @Test fun theSliceReadsAndStartsNothingAtConstruction() {
        assertFalse(slice.contains("init {"))
        assertFalse(slice.contains(".collect"))
        assertFalse(slice.contains("snapshotFlow"))
        // every launch is inside startCheck / a choice (a user action), never in a property initialiser
        assertEquals(4, count(slice, "scope.launchSafe("))
        assertEquals(1, count(slice.substringAfter("private fun startCheck(").substringBefore("\n    }\n"), "scope.launchSafe("))
    }

    /** Declared where its readers are (no work at construction, so any place after its constructor arguments); the init blocks never touch it. */
    @Test fun theSliceIsDeclaredAfterWhatItIsGiven() {
        val at = vm.indexOf("private val activityCheckSlice = ")
        for (dep in listOf("private val planRoute = ", "private val followStore = FollowStore(", "private val recording: RecordingClient", "val tracker = LocationTracker(",
            "val prefs = Prefs(", "private val db = TourDb.get(")) {
            assertEquals("declared once: $dep", 1, count(vm, dep))
            assertTrue("$dep must be declared before the slice", vm.indexOf(dep) < at)
        }
        // no `init {` block of the view model names the slice (its first reader is a screen)
        var from = 0
        while (true) {
            val i = vm.indexOf("\n    init {", from)
            if (i < 0) break
            val body = vm.substring(i).substringBefore("\n    }\n")
            assertFalse("an init block reads the activity check", body.contains("activityCheck"))
            from = i + 5
        }
    }
}
