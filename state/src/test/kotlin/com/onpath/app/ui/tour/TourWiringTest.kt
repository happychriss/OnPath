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
    private val rowsAdapter get() = between("private val tourRows = object : com.onpath.app.ui.tour.TourRows {", "\n    }\n")
    private val fixesAdapter get() = between("fixes = object : com.onpath.app.ui.tour.Fixes {", "        },")
    private val hostAdapter get() = between("host = object : com.onpath.app.ui.tour.ActivityCheckHost {", "        },")

    /** The slice gets the view model's ONE client, FollowStore, plan route and the scope: nothing else may reach the service. */
    @Test fun theSliceGetsTheViewModelsOwnObjects() {
        for (line in listOf(
            "planRoute = planRoute, recording = recording, follow = followStore,",
            "rows = tourRows,",
            "scope = viewModelScope, io = Dispatchers.IO, cpu = Dispatchers.Default,",
        )) assertTrue("the constructor says: $line", check.contains(line))
        assertEquals("one recording client in the view model", 1, count(vm, "ServiceRecordingClient(app)"))
        assertTrue(vm.contains("private val recording: RecordingClient = ServiceRecordingClient(app)"))
        assertEquals("one FollowStore in the view model", 1, count(vm, "FollowStore(app.filesDir)"))
    }

    /**
     * The tour-row writes of the tour pieces, TourDb's own functions with every argument by name (the SQL is pinned in
     * `TourDbMirrorTest`): the activity switch (16b), the interrupted tour's stats, the recycle bin and the rides' store (17).
     */
    @Test fun theRowsAdapterWritesTheToursRowsWithNamedArguments() {
        for (line in listOf(
            "override fun updateActivity(id: Long, activity: String, sac: Int) = db.updateActivity(tourId = id, activity = activity, sac = sac)",
            "db.updateStats(tourId = id, distance = distance, ascent = ascent, descent = descent, duration = duration, state = state)",
            "override fun setDeleted(id: Long, deletedMs: Long?) = db.setDeleted(id = id, deletedMs = deletedMs)",
            "override fun rides(): com.onpath.app.rides.RideStore = com.onpath.app.rides.TourDbRides(db)",
        )) assertTrue("the adapter says: $line", rowsAdapter.replace(Regex("\\s+"), " ").contains(line))
        assertEquals("the one adapter object", 1, count(vm, "object : com.onpath.app.ui.tour.TourRows"))
        assertEquals("the rides' store is made in the adapter only", 1, count(vm, "TourDbRides(db)"))
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
        // every launchSafe is inside startCheck / a choice (a user action), never in a property initialiser
        assertEquals(4, count(slice, "scope.launchSafe("))
        assertEquals(1, count(slice.substringAfter("private fun startCheck(").substringBefore("\n    }\n"), "scope.launchSafe("))
    }

    /**
     * Rule 1: no suspension between the proposal file and its command (the flows cannot see a plain `launch` or a
     * `withContext` of the inline test scope; this is what P4's noSuspensionBetween said). From the start of the write's
     * line to the command there is no `launch` and no `withContext(`.
     */
    @Test fun noSuspensionBetweenTheProposalFileAndItsCommand() {
        val body = slice.substringAfter("private fun applySwitch(").substringBefore("\n    }\n")
        val a = body.indexOf("follow.propose(")
        val b = body.indexOf("recording.proposeSwitch(")
        assertEquals(1, count(body, "follow.propose(")); assertEquals(1, count(body, "recording.proposeSwitch("))
        assertTrue("both anchors found, the file first", a >= 0 && b > a)
        val between = body.substring(body.lastIndexOf('\n', a) + 1, b)
        for (bad in listOf("launch", "withContext(")) assertFalse("`$bad` between the proposal write and its command", between.contains(bad))
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

    // ---------------- piece 17: the tour's end ----------------

    private val endSlice = sourceFile("ui/tour/TourEndSlice.kt").readText()
    private val end get() = between("private val tourEnd = com.onpath.app.ui.tour.TourEndSlice(", "\n    )\n")
    private val endHost get() = between("host = object : com.onpath.app.ui.tour.TourEndHost {", "        },\n")
    /** The view model's big `init`, whose collectors start the follow of the service: from its first line to its end. */
    private val bigInit get() = between("    init {\n        recalc()\n", "\n    }\n")

    /** The slice gets the view model's ONE client, the controller, the notice board and the stores; the threads by name. */
    @Test fun theEndSliceGetsTheViewModelsOwnObjects() {
        for (line in listOf(
            "tour = tour, routes = routes, recording = recording, tours = toursStore, rows = tourRows,",
            "say = notices, scope = viewModelScope, io = Dispatchers.IO, main = Dispatchers.Main,",
        )) assertTrue("the constructor says: $line", end.contains(line))
        assertTrue(vm.contains("val tour = com.onpath.app.ui.tour.TourController(com.onpath.app.ui.tour.tourRecorder(recording))"))
        assertEquals("one view model scope for the end, the main part is named", 1, count(vm, "main = Dispatchers.Main,"))
    }

    /** Each host call hands over to the member the old body used, with the old arguments (each on the thread the slice calls it on). */
    @Test fun theEndHostHandsEachCallToTheViewModelMemberTheOldBodyUsed() {
        for (line in listOf(
            "override val activity: Activity get() = this@AppViewModel.activity",
            "override fun activityOrNull(name: String?): Activity? = this@AppViewModel.activityOrNull(name)",
            "override fun tourTimes(t: Tour): com.onpath.app.time.TourTimes? = this@AppViewModel.tourTimes(t)",
            "override fun nameRecordedTour(t: Tour) = this@AppViewModel.nameRecordedTour(t)",
            "override fun refreshTours() = this@AppViewModel.refreshTours()",
            "override fun clearFollow() = this@AppViewModel.clearFollow()",
            "override fun enterMode(m: AppMode) = enter(m)",
            "override fun endGuidanceFlag() { guidanceEndedByEmptyPlan = false }",
            "override fun kickThumbs() = this@AppViewModel.kickThumbs()",
            "override fun demoEndedWithTour() { if (com.onpath.app.recording.DemoLocation.endWithTour()) demo.navSimLine = null }",
            "override fun tourChanged(id: Long) = Backups.tourChanged(getApplication(), id)",
            "override fun clearInterruptedNote() = this@AppViewModel.clearInterruptedNote()",
            "override val planPoints: Int get() = waypoints.size",
            "override fun clearPlan() = this@AppViewModel.clearPlan()",
            "get() = this@AppViewModel.routeBackAsk",
            "set(v) { this@AppViewModel.routeBackAsk = v }",
            "get() = this@AppViewModel.planSize",
            "set(v) { this@AppViewModel.planSize = v }",
            "override val shownTourId: Long? get() = shown?.tourId",
            "override fun reopenShownTour(t: Tour) = this@AppViewModel.openTour(t)",
        )) assertTrue("the host says: $line", endHost.contains(line))
        // the view model's own members behind it are the old ones
        assertTrue(vm.contains("private fun clearInterruptedNote() = com.onpath.app.recording.TourInterruptedReceiver.cancelNote(getApplication())"))
        assertTrue(vm.contains("private fun tourTimes(t: Tour): com.onpath.app.time.TourTimes? = planRoute.tourTimes(t)"))
        assertEquals("Backups.tourChanged is told through the host only (no call of the slice names it)", 0, count(endSlice, "Backups"))
    }

    /** The screens, `MainActivity` and the E2E tests call these names: each is a one-line forward. */
    @Test fun theEndNamesForwardToTheSlice() {
        for (line in listOf(
            "fun undoShortRecording() = tourEnd.undoShortRecording()",
            "fun closeShortRecording() = tourEnd.closeShortRecording()",
            "fun keepRecordingAfterArrival() = tourEnd.keepRecordingAfterArrival()",
            "fun endTour() = tourEnd.endTour()",
            "fun askEndTour(saveIfNoRides: Boolean = false) = tourEnd.askEndTour(saveIfNoRides)",
            "fun endTourWith(a: EndAsk.Rides, choice: com.onpath.app.rides.RideChoice) = tourEnd.endTourWith(a, choice)",
            "fun openEditRides(t: Tour) = tourEnd.openEditRides(t)",
            "fun applyEditRides(t: Tour, choice: com.onpath.app.rides.RideChoice) = tourEnd.applyEditRides(t, choice)",
            "fun endTourAndDelete() = tourEnd.endTourAndDelete()",
            "fun continueRecording(t: Tour) = tourEnd.continueRecording(t)",
            "fun askSaveUnfinished(t: Tour) = tourEnd.askSaveUnfinished(t)",
            "fun saveUnfinished(t: Tour, choice: com.onpath.app.rides.RideChoice? = null) = tourEnd.saveUnfinished(t, choice)",
            "fun deleteUnfinished(t: Tour) = tourEnd.deleteUnfinished(t)",
            "fun pauseRecording(pause: Boolean) = tourEnd.pauseRecording(pause)",
            "fun stopRecording() = tourEnd.stopRecording()",
            "get() = tourEnd.editRides",
            "set(v) { tourEnd.editRides = v }",
        )) assertEquals("the view model says: $line", 1, count(vm, line))
        // what stays a forward to the controller (state of the controller, nothing to move)
        for (line in listOf("val shortBinnedTour: Tour? get() = tour.shortBinned", "fun backToUnfinished() = tour.backToUnfinished()",
            "val unfinishedRides: Pair<Tour, com.onpath.app.rides.RideView.Summary>? get() = tour.unfinishedRides"))
            assertEquals("still the view model's: $line", 1, count(vm, line))
    }

    /**
     * The collector keeps its place (tour paper risk 1): `tourEnd.start()` is called inside the view model's big `init`, as the LAST
     * statement – right after the state collector, where the `finished` collector was launched; no other launch follows it. Its first
     * pass runs inline on `Main.immediate`, so everything the first pass reads (the slice, its host, the controller, the stores, the
     * board) is declared above that `init` ([ViewModelInitOrderTest] also walks everything the `init` reaches). The old collector is gone.
     */
    @Test fun theFinishedCollectorStartsWhereItAlwaysWasInTheBigInit() {
        assertEquals("started exactly once", 1, count(vm, "tourEnd.start()"))
        val init = bigInit
        assertTrue("inside the big init, as its last statement", init.trimEnd().endsWith("        tourEnd.start()"))
        val before = init.substringBefore("tourEnd.start()")
        assertTrue("right after the state collector (only its one comment line between)",
            before.substringBeforeLast("        // the tour's end").trimEnd().endsWith("syncNavActivity(n.session)\n            }\n        }"))
        assertEquals("the collectors before it, in their old order: start-up check, left-over line, startFailed, state",
            listOf("recording.startFailed.collect", "recording.state.collect"), Regex("recording\\.(startFailed|state)\\.collect").findAll(before).map { it.value }.toList())
        assertEquals("no finished collector in the view model any more", 0, count(vm, "recording.finished"))
        assertEquals("the one collector of `finished` is the slice's", 1, count(endSlice, "recording.finished.collect"))
        val at = vm.indexOf("    init {\n        recalc()\n")
        for (dep in listOf("private val tourEnd = ", "private val tourRows = ", "val tour = ", "private val routes = ", "private val toursStore = ",
            "val notices = ", "private val recording: RecordingClient")) {
            assertEquals("declared once: $dep", 1, count(vm, dep))
            assertTrue("$dep must be declared above the big init", vm.indexOf(dep) < at)
        }
        // the demo and the follow state are reached only when the end is handled (a lazy / a later property read through the host's functions)
        assertTrue("the slice is launched by `start()` only: no `init`, one collect", !endSlice.contains("init {") && count(endSlice, ".collect") == 1)
        assertEquals("the collect is inside start()", 1, count(endSlice.substringAfter("fun start() {").substringBefore("\n    }\n"), "recording.finished.collect"))
    }

    /**
     * Where the slice's own launches run: every launch on the scope (the view model's), the two interrupted-tour writes on `io` by
     * the launch's own context, the two returns to the main thread by name; nothing names a dispatcher object (so no body can slip
     * to another thread), and no suspension sits between a write and a command (the end sends no command after a hop: the ride
     * choice is written on `io` and `endTour()` is called after it, from the same coroutine).
     */
    @Test fun theEndSliceRunsOnTheThreadsItIsGiven() {
        assertEquals(0, count(endSlice, "Dispatchers."))
        assertEquals(9, count(endSlice, "scope.launchSafe("))
        assertEquals(2, count(endSlice, "scope.launchSafe(\"app\", \"saveUnfinished\", io)") + count(endSlice, "scope.launchSafe(\"app\", \"deleteUnfinished\", io)"))
        assertEquals(2, count(endSlice, "withContext(main)"))
        assertEquals("no GlobalScope, no own scope", 0, count(endSlice, "GlobalScope") + count(endSlice, "CoroutineScope("))
        val withTour = endSlice.substringAfter("fun endTourWith(").substringBefore("\n    }\n")
        assertTrue("the ride choice is stored on io, THEN the tour ends (rule 11)", withTour.indexOf("RideApply.choose(") in 0 until withTour.indexOf("endTour()"))
    }

    /**
     * Rules 10 / 32: the delete decision, the closing of the sheets and the question about the plan's route, and the command STOP
     * are ONE main turn – a plain `launch` or a `withContext(` between them would let the next tour's end read the old decision, or
     * send STOP before the question is closed. The flows run on an inline main and cannot see a plain launch; this is what P8's
     * order said as text. (Between the choice's write on io and `endTour()` the suspension is the rule: rule 11, above.)
     */
    @Test fun theDecisionTheClosingAndTheStopAreOneMainTurn() {
        for (fn in listOf("fun endTour() {", "fun endTourAndDelete() {", "fun stopRecording() {")) {
            assertEquals("anchor found exactly once: $fn", 1, count(endSlice, fn))
            val body = endSlice.substringAfter(fn).substringBefore("\n    }\n")
            for (bad in listOf("launch", "withContext(", "delay(")) assertFalse("`$bad` inside $fn", body.contains(bad))
        }
        assertTrue(endSlice.substringAfter("fun endTour() {").substringBefore("\n    }\n").contains("tour.end(delete = false)\n        stopRecording()"))
        assertTrue(endSlice.substringAfter("fun endTourAndDelete() {").substringBefore("\n    }\n").contains("tour.end(delete = true)\n        stopRecording()"))
        assertTrue(endSlice.substringAfter("fun stopRecording() {").substringBefore("\n    }\n").contains("tour.stop()"))
    }
}
