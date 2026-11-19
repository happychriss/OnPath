package com.onpath.app.ui.plan

import com.onpath.app.testkit.sourceFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the flows of [MultiDaySlice] ([MultiDaySliceTest]) cannot see: the app's own wiring of it (rebuild phase 2 piece 20).
 * The slice is tested against fakes; these pins say the app's real adapters hand the Android stores, the preferences and the view
 * model exactly what the old body did – member by member – that the slice sits where its readers need it, that it starts
 * where the view model's last `init` started it before, and that nothing of the app has crept back into the slice.
 */
class MultiDayWiringTest {
    private val vm = sourceFile("ui/AppViewModel.kt").readText()
    private val host = sourceFile("ui/plan/AppMultiDayHost.kt").readText()
    private val prefsAdapter = sourceFile("ui/plan/PrefsMultiDay.kt").readText()
    private val slice = sourceFile("ui/plan/MultiDaySlice.kt").readText()
    private val flow = sourceFile("ui/MultiDayFlow.kt").readText()

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

    private val construction get() = between("internal val multiDay = com.onpath.app.ui.plan.MultiDaySlice(", "    private val saveHost")
    private val store get() = between("private val toursStore = object : com.onpath.app.ui.routes.ToursStore {", "    /** Routes from the browser")

    /** The slice gets the view model's own objects: the plan, the activity holder, the one store, the prefs, the camera, the scope and the two dispatchers it used by name. */
    @Test fun theSliceGetsTheViewModelsOwnObjects() {
        assertTrue(construction, construction.replace(Regex("\\s+"), " ").contains(
            "MultiDaySlice(plan, planRoute, toursStore, com.onpath.app.ui.plan.PrefsMultiDay(prefs), mapCamera, " +
                "com.onpath.app.ui.plan.AppMultiDayHost(this) { mapData }, ::change, ::setSaved, ::rememberPlaceName, { nearbyNames }, " +
                "viewModelScope, Dispatchers.IO, Dispatchers.Default)"))
        assertEquals("one slice", 1, count(vm, "com.onpath.app.ui.plan.MultiDaySlice("))
        assertEquals("the screens and the E2E tests reach it as vm.multiDay", 1, count(vm, "internal val multiDay ="))
    }

    /** Declared after what it is built from (a property below it would be null at construction) and above the hosts that call it. */
    @Test fun theSliceIsDeclaredAfterWhatItIsBuiltFromAndBeforeTheHostsThatReachIt() {
        val at = vm.indexOf("internal val multiDay =")
        for (need in listOf("private val plan = com.onpath.app.ui.plan.PlanDocument(", "private val planRoute", "private val toursStore = object", "private val mapCamera = object", "val prefs = Prefs(app)"))
            assertTrue("$need above multiDay", vm.indexOf(need).let { it in 0 until at })
        for (user in listOf("private val saveHost = object", "private val routesHost = object"))
            assertTrue("$user below multiDay", vm.indexOf(user) > at)
        // `multiDay` is only read inside the hosts' members, at call time
        assertTrue(vm.contains("override fun days(points: List<GeoPoint>) = multiDay.daysFor(points)"))
        assertTrue(vm.contains("override fun keepWhole() = multiDay.keepWhole()"))
        assertTrue(vm.contains("override fun overwriteWhole(name: String, then: (() -> Unit)?) = multiDay.overwriteWhole(name, then)"))
        assertTrue(vm.contains("override fun opened(t: Tour, pts: List<GeoPoint>) = multiDay.opened(t, pts)"))
        assertTrue(vm.contains("override fun withMaps(t: Tour) = multiDay.withMaps(t)"))
        // `recalc` moved to PlanRecalcSlice (piece 9b): the branch asks the host, the host asks the slice
        assertTrue(sourceFile("ui/plan/PlanRecalcSlice.kt").readText().contains("if (host.multiDayOverview) mapData.corridorFollowsPlan(it.points, act) else mapData.routeComputed(it.points, post?.cells, dest, act)"))
        assertTrue(vm.contains("override val multiDayOverview: Boolean get() = multiDay.overview"))
    }

    /** Started where it always was: after the map data's start, in the last `init`. */
    @Test fun theSliceStartsInTheLastInitRightAfterTheMapData() {
        assertEquals(1, count(vm, "multiDay.start()"))
        assertTrue(vm.contains("mapData.start(); multiDay.start() // multi-day tours: kept map areas, a followed day's write-back, the overview's days"))
        val lastInit = vm.lastIndexOf("    init {")
        assertTrue("in the last init block", vm.indexOf("multiDay.start()") > lastInit)
        // after the tracker's cell collectors and the storage-ready work, before the fused-position collector (what lay between the old and this place: nothing)
        assertTrue(vm.indexOf("cells.generation.drop(1).collectLatest { delay(5_000); search.prepare() }") < vm.indexOf("multiDay.start()"))
        assertTrue(vm.indexOf("multiDay.start()") < vm.indexOf("tracker.setFusedWanted(ask)"))
    }

    /** The view model's members behind the host, one call each – the ones the slice's old body called as `vm.x`. */
    @Test fun theHostHandsEachCallToTheViewModelMemberTheOldBodyUsed() {
        val h = host.replace(Regex("\\s+"), " ")
        for (line in listOf(
            "override val mode: AppMode get() = vm.mode",
            "override val route: PlannedRoute? get() = vm.route",
            "override val touring: Boolean get() = vm.recState.value != null",
            "override val cells: MapCells get() = vm.cells",
            "override fun placeNameOf(p: GeoPoint): String? = vm.stopName(p)",
            "override fun reopenTour(t: Tour) = vm.openTour(t)",
            "override fun reloadTourList() = vm.refreshTours()",
            "override fun showSaveProblem(text: String) { vm.routesProblem = text }",
            "override fun tourChanged(id: Long) = Backups.tourChanged(vm.getApplication(), id)",
            "override fun syncChanged(why: String) = vm.sync.changed(why)",
            "val index = vm.search.offlineIndex()",
            "return EndNamer { p -> RouteNamer.label(p, RouteNamer.placesNear(index, listOf(p))) }",
            "mapData().tourMaps(line)?.let { TourMapAreas(it.all, it.missing, it.bytes) }",
            "mapData().loadTourMaps(want, line, wifiWait)",
        )) assertTrue("the host says: $line", h.contains(line))
    }

    /** The five preferences are the old keys: one property each, no key renamed, no value changed on the way. */
    @Test fun thePrefsAdapterPassesEachPropertyThroughToTheOldPref() {
        val p = prefsAdapter.replace(Regex("\\s+"), " ")
        for (name in listOf("planMultiDay", "planDayFollow", "multiDayMapsAsked", "multiDayMapCells", "multiDayWifiCells"))
            assertTrue("$name", p.contains("get() = prefs.$name set(v) { prefs.$name = v }"))
        assertEquals(5, count(p, "get() = prefs."))
    }

    /** The draft write is TourDb's insertTour with every argument by name and the draft flag ([TourDbMirrorTest] pins the SQL columns). */
    @Test fun theDraftInsertIsTheOldInsertTourCall() {
        val s = store.replace(Regex("\\s+"), " ")
        assertTrue(s, s.contains("override fun insertDraftPlanned(name: String, row: com.onpath.app.ui.routes.PlannedRow) = db.insertTour(kind = TourKind.PLANNED, name = name, " +
            "activity = row.activity, sac = row.sac, distance = row.distanceM, ascent = row.ascentM, descent = row.descentM, duration = row.durationS, " +
            "segments = row.segments, waypoints = row.stops, pinned = row.pins, difficulty = row.difficulty, draft = true, days = row.days)"))
    }

    /** The slice and its state know no Android, no view model, no database, no preferences class and no dispatcher of their own. */
    @Test fun nothingOfTheAppIsInTheSliceOrItsFlow() {
        for ((name, text) in listOf("MultiDaySlice" to slice, "MultiDayFlow" to flow)) {
            for (bad in listOf("AppViewModel", "viewModelScope", "android.", "TourDb", "Backups", "Dispatchers.", "com.onpath.app.data.Prefs", "ui.MapDataFlow", "search.RouteNamer", "getApplication"))
                assertFalse("$name must not mention $bad", text.contains(bad))
        }
        // the slice's launches keep their old names (the problem log says where a failure happened)
        for (what in listOf("multiDayWriteBack", "multiDayDays", "multiDayReload", "dayMaps", "saveWholeTour", "dayEndNames"))
            assertTrue(what, slice.contains("scope.launchSafe(\"app\", \"$what\")"))
    }

    /** The three screen models and the slice's nested types stay in the names the screens use. */
    @Test fun theScreensStillFindTheirTypes() {
        assertTrue(slice.contains("data class TourMapsAsk("))
        assertTrue(slice.contains("enum class TourMapsAnswer { WHOLE, DAY, LATER }"))
        val ui = sourceFile("ui/MultiDayUi.kt").readText()
        for (t in listOf("data class DayRowUi(", "data class DaysUi(", "data class DayHeadUi("))
            assertTrue(t, ui.contains(t))
        assertFalse("the Composable file keeps none of them", sourceFile("ui/MultiDaySheet.kt").readText().contains("data class DaysUi("))
    }
}
