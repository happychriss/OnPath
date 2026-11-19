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

    private val check get() = between("val activityCheckSlice: com.onpath.app.ui.tour.ActivityCheckSlice = com.onpath.app.ui.tour.ActivityCheckSlice(", "\n    )\n")
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
            "override fun findActiveRecording() = db.findActiveRecording()",
            "override fun setTourPlan(id: Long, plan: com.onpath.app.data.TourPlan) = db.setTourPlan(tourId = id, plan = plan)",
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
            "override val mode: AppMode get() = this@AppViewModel.tourSession.mode",
            "override fun enterNavigate() = tourSession.enter(AppMode.NAVIGATE)",
            "override val followFromPlan: Boolean get() = this@AppViewModel.tourFollow.followFromPlan",
            "override val planPoints: Int get() = plan.points.size",
            "override fun routeEvent(e: RouteRun.Event) { this@AppViewModel.planRun.routeEvent(e) }",
            "override fun rememberSac(level: Int) { prefs.sac = level }",
        )) assertTrue("the host says: $line", hostAdapter.contains(line))
    }

    /** The follow state the check sheet once left to piece 18a is the follow slice's now; the check slice does not touch it. */
    @Test fun theFollowStateOfTheCheckSheetIsTheFollowSlicesNow() {
        for (name in listOf("navActivitySeen", "planBaseline", "planFollowed")) assertFalse(
            "the check slice does not touch the follow state: $name", slice.contains(name))
        assertEquals("the state itself is the slice's", 0, count(vm, "private var navActivitySeen: Activity? = null"))
        assertEquals(0, count(vm, "private var planBaseline: List<GeoPoint>? = null"))
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
        val at = vm.indexOf("val activityCheckSlice: com.onpath.app.ui.tour.ActivityCheckSlice = ")
        for (dep in listOf("val planRoute: com.onpath.app.ui.plan.PlanRouteSlice = ", "private val followStore = FollowStore(", "private val recording: RecordingClient", "val tracker = LocationTracker(",
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
    private val end get() = between("val tourEnd: com.onpath.app.ui.tour.TourEndSlice = com.onpath.app.ui.tour.TourEndSlice(", "\n    )\n")
    private val endHost get() = between("host = object : com.onpath.app.ui.tour.TourEndHost {", "        },\n")
    /** The view model's big `init`, whose collectors start the follow of the service: from its first line to its end. */
    private val bigInit get() = between("    init {\n        recalc()\n", "\n    }\n")

    /** The slice gets the view model's ONE client, the controller, the notice board and the stores; the threads by name. */
    @Test fun theEndSliceGetsTheViewModelsOwnObjects() {
        for (line in listOf(
            "tour = tour, routes = routes, recording = recording, tours = toursStore, rows = tourRows,",
            "say = notices, scope = viewModelScope, io = Dispatchers.IO, main = Dispatchers.Main,",
        )) assertTrue("the constructor says: $line", end.contains(line))
        assertTrue(vm.contains("val tour: com.onpath.app.ui.tour.TourController = com.onpath.app.ui.tour.TourController(com.onpath.app.ui.tour.tourRecorder(recording))"))
        assertEquals("the end, the session (piece 19) and the route run (piece 9b) name the main part, nothing else does", 3, count(vm, "main = Dispatchers.Main,"))
    }

    /** Each host call hands over to the member the old body used, with the old arguments (each on the thread the slice calls it on). */
    @Test fun theEndHostHandsEachCallToTheViewModelMemberTheOldBodyUsed() {
        for (line in listOf(
            "override val activity: Activity get() = this@AppViewModel.planRoute.activity",
            "override fun activityOrNull(name: String?): Activity? = this@AppViewModel.planRoute.activityOrNull(name)",
            "override fun tourTimes(t: Tour): com.onpath.app.time.TourTimes? = this@AppViewModel.planRoute.tourTimes(t)",
            "override fun nameRecordedTour(t: Tour) = this@AppViewModel.nameRecordedTour(t)",
            "override fun refreshTours() = this@AppViewModel.routesFlow.refreshTours()",
            "override fun clearFollow() = tourFollow.clearFollow()",
            "override fun enterMode(m: AppMode) = tourSession.enter(m)",
            "override fun endGuidanceFlag() { tourFollow.guidanceEndedByEmptyPlan = false }",
            "override fun kickThumbs() = this@AppViewModel.pictures.kickThumbs()",
            "override fun demoEndedWithTour() { if (com.onpath.app.recording.DemoLocation.endWithTour()) demo.navSimLine = null }",
            "override fun tourChanged(id: Long) = Backups.tourChanged(getApplication(), id)",
            "override fun clearInterruptedNote() = this@AppViewModel.clearInterruptedNote()",
            "override val planPoints: Int get() = plan.points.size",
            "override fun clearPlan() = this@AppViewModel.planEdit.clearPlan()",
            "get() = this@AppViewModel.planEdit.routeBackAsk",
            "set(v) { this@AppViewModel.planEdit.routeBackAsk = v }",
            "get() = this@AppViewModel.planEdit.planSize",
            "set(v) { this@AppViewModel.planEdit.planSize = v }",
            "override val shownTourId: Long? get() = routesFlow.shown?.tourId",
            "override fun reopenShownTour(t: Tour) = this@AppViewModel.openTour(t)",
        )) assertTrue("the host says: $line", endHost.contains(line))
        // the view model's own members behind it are the old ones
        assertTrue(vm.contains("private fun clearInterruptedNote() = com.onpath.app.recording.TourInterruptedReceiver.cancelNote(getApplication())"))
        assertEquals("Backups.tourChanged is told through the host only (no call of the slice names it)", 0, count(endSlice, "Backups"))
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
        // piece 19: the start-up work, the startFailed and the state collector are the session's now (`tourSession.start()` stands where they stood, right
        // before the one comment line of the end collector); the old order of the two collectors is the slice's (TourSessionWiringTest part below)
        assertTrue("right after the session's start (only the end collector's one comment line between)",
            before.substringBeforeLast("        // the tour's end").trimEnd().endsWith("tourSession.start()"))
        assertEquals("no collector of the service's state left in the big init", 0, count(before, ".collect"))
        assertEquals("no finished collector in the view model any more", 0, count(vm, "recording.finished"))
        assertEquals("the one collector of `finished` is the slice's", 1, count(endSlice, "recording.finished.collect"))
        val at = vm.indexOf("    init {\n        recalc()\n")
        for (dep in listOf("val tourEnd: com.onpath.app.ui.tour.TourEndSlice = ", "private val tourRows = ", "val tour: com.onpath.app.ui.tour.TourController = ", "val routes: com.onpath.app.ui.routes.RoutesState = ", "private val toursStore = ",
            "val notices: NoticeBoard = ", "private val recording: RecordingClient")) {
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

    // ---------------- piece 18a: what the tour follows ----------------

    private val followSlice = sourceFile("ui/tour/TourFollow.kt").readText()
    private val follow get() = between("val tourFollow: com.onpath.app.ui.tour.TourFollow = com.onpath.app.ui.tour.TourFollow(", "\n    )\n")
    private val followHost get() = between("host = object : com.onpath.app.ui.tour.TourFollowHost {", "        },\n")
    private val followPrefsAdapter get() = between("prefs = object : com.onpath.app.ui.tour.FollowPrefs {", "        },\n")
    private fun body(src: String, fn: String): String {
        assertEquals("anchor found exactly once: $fn", 1, count(src, fn))
        return src.substringAfter(fn).substringBefore("\n    }\n")
    }
    private fun flat(text: String) = text.replace(Regex("\\s+"), " ")

    /** The slice gets the view model's ONE client, FollowStore, plan, plan route, rows, the scope and THE ONE serial dispatcher. */
    @Test fun theFollowSliceGetsTheViewModelsOwnObjects() {
        for (line in listOf(
            "plan = plan, planRoute = planRoute, recording = recording, files = followStore,",
            "rows = tourRows,",
            "scope = viewModelScope, planCopyDispatcher = planCopyDispatcher,",
        )) assertTrue("the constructor says: $line", follow.contains(line))
        assertEquals("one FollowStore in the view model", 1, count(vm, "FollowStore(app.filesDir)"))
        assertEquals("the plan copy's thread is made ONCE: one serial dispatcher (two would break the order of the writes)", 1,
            count(vm, "Dispatchers.IO.limitedParallelism(1)"))
        assertEquals(1, count(vm, "private val planCopyDispatcher = Dispatchers.IO.limitedParallelism(1)"))
        assertEquals("the slice never makes a dispatcher", 0, count(followSlice, "limitedParallelism") + count(followSlice, "Dispatchers."))
        assertEquals("the one object is passed in and used for the copy's launch only", 1,
            count(followSlice, "scope.launchSafe(\"app\", \"tourPlanCopy\", planCopyDispatcher)"))
    }

    /** The prefs adapter hands `prefs.followIsTrack` through, both ways; each host call goes to the member the old body used. */
    @Test fun theFollowHostHandsEachCallToTheViewModelMemberTheOldBodyUsed() {
        assertTrue(flat(followPrefsAdapter).contains("override var followIsTrack: Boolean get() = prefs.followIsTrack set(v) { prefs.followIsTrack = v }"))
        for (line in listOf(
            "override val route: PlannedRoute? get() = this@AppViewModel.route",
            "override val shown: ShownTrack? get() = this@AppViewModel.routesFlow.shown",
            "override fun placeName(p: GeoPoint): String? = this@AppViewModel.searchFlow.stopName(p)",
            "override fun newRouteName(prefix: String) = this@AppViewModel.defaultName(prefix)",
            "override fun stopsAsSaved(pts: List<GeoPoint>) = this@AppViewModel.savedStops(pts)",
            "override fun savedTourUuid(id: Long): String? = db.getTour(id)?.uuid?.takeIf { it.isNotEmpty() }",
            "override fun invalidateSplits() = tourEdits.splitRequests.invalidate()",
            "override fun routeEvent(e: RouteRun.Event) { this@AppViewModel.planRun.routeEvent(e) }",
            "override fun tourChanged(id: Long) = Backups.tourChanged(getApplication(), id)",
        )) assertTrue("the host says: $line", followHost.contains(line))
        assertEquals("Backups is told through the host only", 0, count(followSlice, "Backups"))
        assertEquals("no Android class in the slice", 0, count(followSlice, "android.") + count(followSlice, "getApplication"))
    }

    /**
     * The old names are one-line forwards; the state is read from and written to the slice. `followLine` keeps its private
     * setter, `guidanceEndedByEmptyPlan` too (the plan-edit code of piece 18b writes them through the view model).
     */
    @Test fun theFollowNamesForwardToTheSlice() {
        val all = flat(vm)
        // the forwards only the moved plan-edit code used went with it (piece 18b): the slice calls the follow slice itself
        for (gone in listOf("private val followOriginal", "private fun recopyFollowedPlan", "private fun copyFollowedPlan", "private fun setFollow",
            "private fun followAgain", "tourFollow.followAgain(",
            // piece 19: the state collector and the start / continue code were their last readers
            "private fun planFollowed", "private fun planReplacesTrack", "private fun syncNavActivity", "private var navVersion", "private var navActivitySeen",
        )) assertEquals("gone from the view model: $gone", 0, count(vm, gone))
        // the state collector of piece 19 is the session's: it reads / writes the follow slice directly
        val session = sourceFile("ui/tour/TourSession.kt").readText()
        assertTrue(session.contains("val n = st?.nav ?: run { follow.navActivitySeen = null; syncRetries = 0; return@collect }"))
        assertTrue(session.contains("if (n.version != follow.navVersion) { follow.navVersion = n.version; if (followLine != n.line) follow.followLine = n.line }"))
        assertTrue(session.contains("follow.syncNavActivity(n.session)"))
        // what is left of the old block in the view model is nothing but forwards: no file write, no plan copy, no token
        assertEquals("the files are written by the slice only", 0, count(vm, "followStore.appFollows") + count(vm, "followStore.appEnds"))
        assertEquals("no plan copy in the view model", 0, count(vm, "planCopy {") + count(vm, "tourPlanCopy"))
        assertEquals("the token is the slice's", 0, count(vm, "newSessionToken"))
    }

    /**
     * The tour-id collector: `tourFollow.start()` is the only statement of the small `init` right after the slice, and nothing but
     * declarations (lazies, plain properties, forwarders) lies between that `init` and the big one – so the collector's first pass,
     * which runs inline on `Main.immediate`, meets the same world it met when it stood 100 lines higher (`ViewModelInitOrderTest`
     * forbids the higher place now: the slice reaches the plan route's ports). The slice is declared after everything it is given,
     * and the big init does not start it.
     */
    @Test fun theTourIdCollectorStartsInTheSmallInitRightAfterTheSlice() {
        assertEquals("started exactly once", 1, count(vm, "tourFollow.start()"))
        val declared = vm.indexOf("val tourFollow: com.onpath.app.ui.tour.TourFollow = com.onpath.app.ui.tour.TourFollow(")
        val init = "    init {\n        // the recording tour's id: a new tour takes the plan followed at its start, its end writes the last version\n        tourFollow.start()\n    }\n"
        assertEquals(1, count(vm, init))
        val initAt = vm.indexOf(init)
        assertTrue("the init stands after the slice's constructor call", initAt > declared)
        // piece 18b: the edits slice is declared between the follow slice and its init (the follow slice's host reaches the edits slice's
        // `splitRequests`, and ViewModelInitOrderTest follows that edge from the init). What lies between the old place and the new one
        // is that one declaration – no init, no launch, no collect, no start().
        val followEnd = vm.indexOf("\n    )\n", declared) + "\n    )\n".length
        val between0 = vm.substring(followEnd, initAt)
        assertTrue("only the edits slice's and (piece 19) the session's declarations lie between",
            between0.trim().startsWith("/**") && count(between0, "val tourEdits: com.onpath.app.ui.tour.TourEdits = ") == 1 &&
                count(between0, "val tourSession: com.onpath.app.ui.tour.TourSession = ") == 1)
        assertTrue("and it ends right before the init", between0.endsWith("\n    )\n\n"))
        for (bad in listOf("init {", "launch", ".collect", ".start(")) assertEquals("`$bad` between the follow slice and its init", 0, count(between0, bad))
        val bigAt = vm.indexOf("    init {\n        recalc()\n")
        val between = vm.substring(initAt + init.length, bigAt)
        assertEquals("no other init between the small init and the big one", 0, count(between, "\n    init {"))
        assertFalse("the big init does not start it", bigInit.contains("tourFollow.start()"))
        for (dep in listOf("val plan: com.onpath.app.ui.plan.PlanDocument = ", "val planRoute: com.onpath.app.ui.plan.PlanRouteSlice = ", "private val recording: RecordingClient", "private val followStore = FollowStore(",
            "private val tourRows = ", "private val db = TourDb.get(", "val prefs = Prefs(")) {
            assertEquals("declared once: $dep", 1, count(vm, dep))
            assertTrue("$dep must be declared before the slice", vm.indexOf(dep) < declared)
        }
        assertTrue("the end slice is declared after it", vm.indexOf("val tourEnd: com.onpath.app.ui.tour.TourEndSlice = ") > initAt)
        assertEquals("the old collector is gone from the view model", 0, count(vm, "recState.map { it?.tourId }"))
        // the follow state is reached through forwarder properties now: an init above the slice that read one would crash at
        // the app's start (the old plain fields gave their defaults) and ViewModelInitOrderTest does not follow property getters
        assertEquals("only the two known inits stand above the slice – a new one must not read the follow state (review 2026-11-19)",
            2, count(vm.substring(0, declared), "\n    init {"))
    }

    /** The slice does no work in its constructor and has no `init`; its two launches are the collector (in `start`) and the copy's thread. */
    @Test fun theFollowSliceReadsAndStartsNothingAtConstruction() {
        assertFalse(followSlice.contains("init {"))
        assertEquals("one collect, inside start()", 1, count(followSlice, ".collect"))
        assertEquals(1, count(body(followSlice, "fun start() {"), ".collect"))
        assertEquals("two launches: the copy's thread and the collector", 2, count(followSlice, "scope.launchSafe("))
        assertFalse(followSlice.contains("snapshotFlow"))
        assertFalse(followSlice.contains("GlobalScope"))
        // the reads at construction are the restore of rule 33, and nothing else reads the files or the prefs there
        assertEquals(3, count(followSlice, "files.load"))
        assertEquals(1, count(followSlice, "= prefs.followIsTrack"))
    }

    /**
     * Rule 1: the file is written in the same main turn as its command – a `launch` or a `withContext(` between a write of the
     * files and the command would let the service read the old route (the flows run on an inline main and cannot see a plain
     * launch). Also: the token is time-based and strictly increasing, as it was; `followAgain` has no `recState` guard (L7).
     */
    @Test fun noSuspensionBetweenTheFollowFilesAndTheirCommandAndTheTokenIsAsItWas() {
        for ((fn, write, command) in listOf(
            Triple("fun setFollow(", "files.appFollows(session, name, points)", "recording.followChanged(checkNow)"),
            Triple("fun followAgain(", "files.appFollows(session, session.name, session.active)", "recording.followChanged()"),
            Triple("fun clearFollow() {", "files.appEnds()", "recording.followChanged()"),
        )) {
            val b = body(followSlice, fn)
            val a = b.indexOf(write); val c = b.indexOf(command)
            assertEquals(1, count(b, write)); assertEquals(1, count(b, command))
            assertTrue("$fn: the write, then the command", a in 0 until c)
            val between = b.substring(b.lastIndexOf('\n', a) + 1, c)
            for (bad in listOf("launch", "withContext(", "delay(")) assertFalse("`$bad` between the file write and the command in $fn", between.contains(bad))
        }
        assertTrue(followSlice.contains("private fun newSessionToken(): Long = maxOf(System.currentTimeMillis(), lastSessionToken + 1).also { lastSessionToken = it }"))
        assertFalse("followAgain keeps its missing guard (L7)", body(followSlice, "fun followAgain(").contains("recState.value != null"))
        assertTrue(body(followSlice, "fun setFollow(").contains("if (recState.value != null) recording.followChanged(checkNow)"))
        assertTrue(body(followSlice, "fun clearFollow() {").contains("if (recState.value != null) recording.followChanged()"))
    }

    // ---------------- piece 18b: what a plan edit means for the tour ----------------

    private val editsSlice = sourceFile("ui/tour/TourEdits.kt").readText()
    /** The route run's coroutine (`recalc`, piece 9b): it cancels `followCancel` and asks the tour to follow the edited plan. */
    private val recalcSlice = sourceFile("ui/plan/PlanRecalcSlice.kt").readText()
    private val edits get() = between("val tourEdits: com.onpath.app.ui.tour.TourEdits = com.onpath.app.ui.tour.TourEdits(", "\n    )\n")
    private val editsHost get() = between("host = object : com.onpath.app.ui.tour.TourEditsHost {", "        },\n")

    /** The slice gets the view model's own plan, edit slice, route slice, follow slice, client, history, notice board, scope and routing thread. */
    @Test fun theEditsSliceGetsTheViewModelsOwnObjects() {
        for (line in listOf(
            "plan = plan, planEdit = planEdit, planRoute = planRoute, follow = tourFollow, recording = recording,",
            "position = com.onpath.app.ui.Position { myPosition() }, followHistory = followHistory,",
            "say = notices, scope = viewModelScope, routingDispatcher = planRoute.threads.routing,",
        )) assertTrue("the constructor says: $line", edits.contains(line))
        assertEquals("one follow history, kept in the view model (the state collector clears it)", 1, count(vm, "FollowHistory()"))
        assertEquals("the requests are the slice's now", 0, count(vm, "SplitRequests()"))
        assertEquals(1, count(editsSlice, "val splitRequests = SplitRequests()"))
        assertTrue("the routing thread is the plan route's", vm.contains("routingDispatcher = planRoute.threads.routing"))
        assertTrue("myPosition is the old body: the phone's fix, else the tour's last point", flat(vm).contains(
            "private fun myPosition(): GeoPoint? = tracker.location.value?.let { GeoPoint(it.latitude, it.longitude) } ?: recState.value?.track?.lastOrNull()?.lastOrNull()"))
    }

    /** Each host call goes to the view model member the old body used (the names differ from the view model's functions on purpose). */
    @Test fun theEditsHostHandsEachCallToTheViewModelMemberTheOldBodyUsed() {
        for (line in listOf(
            "override val route: PlannedRoute? get() = this@AppViewModel.route",
            "override fun replan() = recalc()",
            "override fun mapsForNewPoints(before: List<GeoPoint>, after: List<GeoPoint>) = cellsForNewPoints(before, after)",
            "override fun noPositionNote(note: PlanGps.Note, text: org.jetbrains.compose.resources.StringResource) = map.noPositionForEdit(note, text)",
            "override fun placeName(p: GeoPoint): String? = this@AppViewModel.searchFlow.stopName(p)",
            "override fun newRouteName(prefix: String) = this@AppViewModel.defaultName(prefix)",
            "override fun routeRunEvent(e: RouteRun.Event) { this@AppViewModel.planRun.routeEvent(e) }",
            "override fun closeShownTrack() = routesFlow.closeShown()",
            "override fun resumeNavigation() = tourSession.continueNavigation()",
        )) assertTrue("the host says: $line", editsHost.contains(line))
        assertEquals("no Android class in the slice", 0, count(editsSlice, "android.") + count(editsSlice, "getApplication") + count(editsSlice, "Backups"))
    }

    /**
     * What the other hosts and the view model say: the plan edit's, the place menus', My routes' hooks and `recalc` answer from the
     * slice; the screens' names forward (`tourPassed` keeps its default). `followCancel` is ONE field of the slice (volatile), written
     * by `recalc` too; the moved state is not in the view model any more.
     */
    @Test fun theViewModelAndItsHostsAnswerFromTheEditsSlice() {
        for (line in listOf(
            "override fun splitOf(before: List<GeoPoint>, after: List<GeoPoint>): TourSplit.Outcome? = tourEdits.splitOf(before, after)",
            "override fun oneLeftGoesFromHere(before: List<GeoPoint>, mayEmpty: Boolean) = tourEdits.oneLeftGoesFromHere(before, mayEmpty)",
            "override fun endGuidanceIfPlanEmpty() = tourEdits.endGuidanceIfPlanEmpty()",
            "override fun tourPassed(plan: List<GeoPoint>): Set<Int> = tourEdits.tourPassed(plan)",
            "override fun loadedOnTheWay(on: Boolean) { tourEdits.loadedOnTheWay = on }",
            "override fun followShownNow() = tourEdits.followShownNow()",
            "override fun invalidateSplits() = tourEdits.splitRequests.invalidate()",
            "unlessUnsaved(\"Follow the shown track during the tour\") { tourEdits.followShownNow() }",
        )) assertTrue("the view model says: $line", vm.contains(line))
        assertEquals("wayThereFrom is the slice's for both hosts", 2, count(vm, "override fun wayThereFrom(): GeoPoint? = tourEdits.wayThereFrom()"))
        assertEquals("the one field, volatile, is the slice's", 1, count(editsSlice, "@Volatile var followCancel: com.onpath.app.routing.RouteCancel? = null"))
        assertEquals("recalc (piece 9b: the slice) is the only code that touches it", 1, count(recalcSlice, "tourEdits.followCancel?.cancel(); tourEdits.followCancel = null // the tour's follow line of the old plan"))
        assertEquals("…and the view model no longer does", 0, count(vm, "followCancel?.cancel()"))
        assertEquals("the run asks the tour to follow the edited plan", 1, count(recalcSlice, "RouteRun.Effect.FollowEditedPlan -> tourEdits.followEditedPlan()"))
        assertEquals(0, count(vm, "\n        followCancel"))
        for (gone in listOf("pendingSplit", "splitBefore", "passedPoints", "passedTourId", "private var loadedOnTheWay", "private fun splitOf", "private fun followAhead",
            "private fun followEditedPlan", "private fun oneLeftGoesFromHere", "private fun endGuidanceIfPlanEmpty", "private fun wayThereFrom", "private fun followShownNow",
            "private fun prunePinned")) assertEquals("gone from the view model: $gone", 0, count(vm, gone))
    }

    /**
     * Nothing at construction, no collector; the one launch is `followAhead` on the view model's scope, its routing body on the routing
     * thread BY NAME (a body that slips to another thread would route on main), `Dispatchers` is never named; no suspension (launch /
     * withContext / delay) in the functions that decide what the tour follows before they call the follow slice.
     */
    @Test fun theEditsSliceReadsAndStartsNothingAtConstructionAndRoutesOnTheRoutingThread() {
        assertFalse(editsSlice.contains("init {"))
        assertFalse(editsSlice.contains(".collect"))
        assertFalse(editsSlice.contains("snapshotFlow") || editsSlice.contains("GlobalScope"))
        assertEquals(0, count(editsSlice, "Dispatchers."))
        assertEquals("the one launch", 1, count(editsSlice, "scope.launchSafe("))
        assertEquals(1, count(body(editsSlice, "private fun followAhead("), "scope.launchSafe(\"app\", \"followAhead\") {"))
        assertEquals("one hop, to the routing thread", 1, count(editsSlice, "withContext("))
        assertEquals(1, count(editsSlice, "withContext(routingDispatcher) {"))
        for (fn in listOf("fun answerRouteBack(", "fun endGuidanceIfPlanEmpty()", "fun oneLeftGoesFromHere(", "fun followEditedPlan()", "fun followShownNow()",
            "fun splitOf(", "fun tourPassed(")) {
            val b = body(editsSlice, fn)
            for (bad in listOf("launch", "withContext(", "delay(")) assertFalse("`$bad` inside $fn", b.contains(bad))
        }
        assertTrue("the position → ahead route is asked of the engine the plan's route slice makes, with the shared leg cache",
            editsSlice.contains("Planner(planRoute.routingEngine(), cancel, legs = planRoute.legCache)"))
    }

    /** Rule 1 stays the follow slice's: the edits slice never writes the follow files nor sends a command itself. */
    @Test fun theEditsSliceTellsTheServiceOnlyThroughTheFollowSlice() {
        assertEquals(0, count(editsSlice, "followChanged") + count(editsSlice, "files.") + count(editsSlice, "recording.send") + count(editsSlice, "recording.say"))
    }
    // ---------------- piece 19: the tour's session ----------------

    private val sessionSlice = sourceFile("ui/tour/TourSession.kt").readText()
    private val session get() = between("val tourSession: com.onpath.app.ui.tour.TourSession = com.onpath.app.ui.tour.TourSession(", "\n    )\n")
    private val sessionHost get() = between("host = object : com.onpath.app.ui.tour.TourSessionHost {", "        },\n")

    /** The slice gets the view model's own objects; the two waits are the slice's defaults (6 s, 1.5 s), the view model names none. */
    @Test fun theSessionGetsTheViewModelsOwnObjects() {
        for (line in listOf(
            "plan = plan, planEdit = planEdit, planRun = planRun, planRoute = planRoute, save = save, map = map, tour = tour,",
            "follow = tourFollow, recording = recording, rows = tourRows, tours = toursStore, followHistory = followHistory,",
            "say = notices, scope = viewModelScope, io = Dispatchers.IO, main = Dispatchers.Main,",
        )) assertTrue("the constructor says: $line", session.contains(line))
        assertFalse("the waits are the slice's defaults", session.contains("startTimeoutMs") || session.contains("stickyWaitMs"))
        assertTrue(sessionSlice.contains("private val startTimeoutMs: Long = 6_000L,"))
        assertTrue(sessionSlice.contains("private val stickyWaitMs: Long = 1_500L,"))
        assertEquals("one follow history, the edits slice's and the session's", 1, count(vm, "FollowHistory()"))
    }

    /** Each host call hands over to the member the old body used; the menus line is the old one, statement by statement, without `followMe`. */
    @Test fun theSessionHostHandsEachCallToTheViewModelMemberTheOldBodyUsed() {
        val host = flat(sessionHost)
        for (line in listOf(
            "override val errorCard: String? get() = this@AppViewModel.notices.errorCard",
            "override val route: PlannedRoute? get() = this@AppViewModel.route",
            "override var shown: ShownTrack? get() = this@AppViewModel.routesFlow.shown set(v) { this@AppViewModel.routesFlow.shown = v }",
            "override val headingUp: Boolean get() = this@AppViewModel.settingsSlice.settings.navHeadingUp",
            "override val hasGpsFix: Boolean get() = tracker.location.value != null",
            "override fun clearHints() { tapHoldHint = false; offlineInfo = false }",
            "override fun closeMenusForNavigate() { placeMenus.planPoi = null; placeMenus.poiMenu = null; placeMenus.closePointMenu(\"mode change to Navigate\"); placeAdd.firstPointMenu = null; placeAdd.holdAddMenu = null; placeAdd.cancelHoldMeasure(\"mode change to Navigate\"); highlight = null }",
            "override fun secureTourCorridor(line: List<GeoPoint>, last: GeoPoint?, activity: Activity) = mapData.secureTourCorridor(line, last, activity)",
        )) assertTrue("the host says: $line", host.contains(line))
        assertEquals("nothing of the Android world in the slice", 0, count(sessionSlice, "android.") + count(sessionSlice, "getApplication") + count(sessionSlice, "Backups"))
        assertEquals("the fix is the tracker's own value, not the tour's last point (`myPosition`)", 0, count(sessionHost, "myPosition"))
    }

    /** The old names are one-line forwards (same signatures and defaults); the state objects are the slice's. */
    @Test fun theSessionNamesForwardToTheSlice() {
        val all = flat(vm)
        for (line in listOf(
            "val mode: AppMode get() = tourSession.mode",
            "override fun resumeNavigation() = tourSession.continueNavigation()",
        )) assertEquals("the view model says: $line", 1, count(all, line))
        // what moved is gone from the view model (the forwards nobody uses any more went with it)
        for (gone in listOf("private var startJob", "START_TIMEOUT_MS", "private fun secureTourCorridor", "private fun planHasStraightLegs", "private fun checkNow",
            "private fun onArrived", "mutableStateOf(AppMode.PLAN)", "val straightAsk by", "recording.startFailed", "recording.state.collect", "recording.state.filterNotNull",
            "unfinishedPending")) assertEquals("gone from the view model: $gone", 0, count(vm, gone))
        assertEquals("one place sets the mode: the slice", 0, count(vm, " mode = "))
    }

    /**
     * The start-up work keeps its place (tour paper risks 1 and 2): `recalc()` is the first statement of the big `init` and the plan
     * is routed as "in Plan"; `tourSession.start()` stands after `refreshTours()` – where the left-over line check, the mode flip, the
     * start-up check and the two collectors stood – and right before the end collector's `tourEnd.start()`, the last statement. Nothing
     * else is launched in between, and everything the first pass of the `state` collector reads is declared above that `init`
     * (`ViewModelInitOrderTest` also walks what the `init` reaches). The slice is declared right after the follow slice, above the
     * edits slice and the small `init` (the edits host reaches it through `resumeNavigation`; the walk follows that edge) – what lies
     * between the follow slice and the small `init` is these two declarations and nothing else.
     */
    @Test fun theStartUpWorkStartsWhereItAlwaysWasInTheBigInitAfterRecalc() {
        assertEquals("started exactly once", 1, count(vm, "tourSession.start()"))
        val init = bigInit
        assertTrue("recalc() first", init.startsWith("    init {\n        recalc()\n"))
        val at = { t: String -> assertEquals("once in the big init: $t", 1, count(init, t)); init.indexOf(t) }
        assertTrue("refreshTours() before the session's start", at("        routesFlow.refreshTours()") < at("        tourSession.start()"))
        assertTrue("the session before the end collector", at("        tourSession.start()") < at("        tourEnd.start()"))
        val between = init.substring(at("        routesFlow.refreshTours()"), at("        tourSession.start()"))
        for (bad in listOf("launch", ".collect", ".start(", "mode", "enter(")) assertEquals("`$bad` between refreshTours() and the session's start", 0, count(between.substringAfter("\n"), bad))
        assertEquals("no collector and no launch left in the big init besides the slices'", 0, count(init, "launchSafe") + count(init, ".collect"))
        val declared = vm.indexOf("val tourSession: com.onpath.app.ui.tour.TourSession = ")
        assertEquals(1, count(vm, "val tourSession: com.onpath.app.ui.tour.TourSession = "))
        for (dep in listOf("val plan: com.onpath.app.ui.plan.PlanDocument = ", "val planEdit: ", "val planRun: com.onpath.app.ui.plan.PlanRunSlice = ", "val planRoute: com.onpath.app.ui.plan.PlanRouteSlice = ", "val save: com.onpath.app.ui.routes.SaveSlice = ", "val map: com.onpath.app.ui.MapViewSlice = ",
            "val tour: com.onpath.app.ui.tour.TourController = ", "val tourFollow: com.onpath.app.ui.tour.TourFollow = ", "private val recording: RecordingClient", "private val tourRows = ", "private val toursStore = ", "private val followHistory = ",
            "val notices: NoticeBoard = ", "val tracker = LocationTracker(", "val mapData: MapDataFlow by lazy")) {
            assertEquals("declared once: $dep", 1, count(vm, dep))
            assertTrue("$dep must be declared above the session", vm.indexOf(dep) < declared)
        }
        assertTrue("above the edits slice", declared < vm.indexOf("val tourEdits: com.onpath.app.ui.tour.TourEdits = "))
        assertTrue("above the big init", declared < vm.indexOf("    init {\n        recalc()\n"))
    }

    /**
     * Where the slice's launches run and what sits between a decision and its command. Nothing names a dispatcher object; the start-up
     * check runs on `io` by the launch's own context and returns to main by name; the hold (`unfinishedPending = true`) is set directly
     * before that launch and the mode flip directly before the hold (the flows' held io cannot show a swap of these: a launch dispatched
     * to a held executor runs after both either way – in the app io is a real thread, so a hold set AFTER the launch could be cleared
     * before it was set); the start's decision (`followPlanned`, `save.atTourStart`, `START`) is one main turn.
     */
    @Test fun theSessionRunsOnTheThreadsItIsGivenAndTheStartIsOneMainTurn() {
        assertFalse(sessionSlice.contains("init {"))
        assertEquals(0, count(sessionSlice, "Dispatchers."))
        assertEquals("the left-over check, the start-up check, startFailed, state, startJob", 5, count(sessionSlice, "scope.launchSafe("))
        assertEquals(1, count(sessionSlice, "scope.launchSafe(\"app\", \"start-up work\", io) {"))
        assertEquals(2, count(sessionSlice, ".collect"))
        assertEquals("two returns to main by name, one non-cancellable", 2, count(sessionSlice, "withContext(main)"))
        assertEquals(1, count(sessionSlice, "withContext(NonCancellable + main)"))
        assertEquals("the left-over check's read is on io by name", 1, count(sessionSlice, "withContext(io) {"))
        assertEquals("no GlobalScope, no own scope", 0, count(sessionSlice, "GlobalScope") + count(sessionSlice, "CoroutineScope("))
        val sl = flat(sessionSlice)
        assertTrue("the hold is set directly before the launch, the flip before it", sl.contains(
            "if (recording.state.value != null) mode = AppMode.NAVIGATE // no new tour until this check has decided (TourController.canStartNew); cleared whatever happens – a stuck // flag would refuse every tour for good tour.unfinishedPending = true scope.launchSafe(\"app\", \"start-up work\", io) {"))
        assertTrue("the hold is cleared in `finally`, non-cancellable", sl.contains("} finally { withContext(NonCancellable + main) { tour.unfinishedPending = false } }"))
        val startBody = body(sessionSlice, "fun startNavigation(")
        val decision = startBody.substringBefore("startJob?.cancel()")
        for (bad in listOf("launch", "withContext(", "delay(")) assertFalse("`$bad` between the decision and START", decision.contains(bad))
        assertTrue("follow, corridor, save, START in this order", flat(decision).contains("PlanStart.NAVIGATE_PLAN -> { followPlanned(); secureTourCorridor(); save.atTourStart() }") &&
            decision.indexOf("PlanStart.NAVIGATE_PLAN") < decision.indexOf("\n        startRecording()\n"))
        assertTrue("the wait is cancelled by the next start only", startBody.contains("startJob?.cancel()\n        startJob = scope.launchSafe(\"app\", \"startNavigation\") {"))
        assertEquals("startFailed does not cancel it (L4)", 0, count(body(sessionSlice, "fun start() {"), "startJob"))
        for (fn in listOf("fun enter(m: AppMode) {", "fun continueNavigation(force: Boolean = false) {")) {
            val b = body(sessionSlice, fn)
            for (bad in listOf("launch", "withContext(", "delay(")) assertFalse("`$bad` inside $fn", b.contains(bad))
        }
        assertTrue("the mode is set first", body(sessionSlice, "fun enter(m: AppMode) {").contains("val was = mode\n        mode = m\n        host.clearHints()"))
        assertTrue("the service is told last", body(sessionSlice, "fun enter(m: AppMode) {").trimEnd().endsWith("if (recState.value != null) recording.navigating(m == AppMode.NAVIGATE)"))
        assertTrue("the decision, then Navigate last", body(sessionSlice, "fun continueNavigation(force: Boolean = false) {").trimEnd().endsWith("enter(AppMode.NAVIGATE)"))
    }

    /** The slice reads and starts nothing at construction: the state is plain `mutableStateOf` (initial values as before: Plan, no zoom, no question). */
    @Test fun theSessionReadsAndStartsNothingAtConstruction() {
        assertEquals(1, count(sessionSlice, "var mode by mutableStateOf(AppMode.PLAN)"))
        assertEquals(1, count(sessionSlice, "var navStartZoom by mutableStateOf<Double?>(null)"))
        assertEquals(1, count(sessionSlice, "var straightAsk by mutableStateOf(false)"))
        assertFalse(sessionSlice.contains("snapshotFlow"))
        assertEquals("only start() collects", 2, count(body(sessionSlice, "fun start() {"), ".collect"))
    }
}
