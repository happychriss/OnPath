package com.onpath.app.testkit

import com.onpath.app.data.Geo
import com.onpath.app.data.TourKind
import com.onpath.app.domain.tour.ShortRecording
import com.onpath.app.navigation.NavKind
import com.onpath.app.navigation.NavPrompt
import com.onpath.app.navigation.NavSession
import com.onpath.app.navigation.NavSessionStore
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.navigation.Trace.walk
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.LegCache
import com.onpath.app.routing.Planner
import com.onpath.app.routing.RouteCancel
import com.onpath.app.domain.SplitRequests
import com.onpath.app.ui.tour.TourController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The JVM test kit for state holders (backlog "Code audit 2026-11-12" (b)) proved on logic that exists today – no
 * view model, no Android: the tour's lifecycle ([TourController]) over a recording that writes real rows
 * ([FakeRecording] + [JdbcTours]); the service's re-routing against the app's session file with the order of events
 * decided by the test ([FakeNavService] + [ManualExecutor] + [FakeRoutingEngine]); and the view model's pattern for a
 * route run – routed on the routing thread, applied on the main thread only while it is the newest ([SplitRequests]).
 * The slices cut out of AppViewModel later are tested the same way.
 */
class TestKitTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val problems = ProblemsRule()

    @get:Rule val composeState = OwnComposeSnapshot()

    private var now = 1_793_000_000_000L
    private val clock = { now.also { now += 1000 } }

    // ---- tour lifecycle: TourController + FakeRecording + JdbcTours ----

    @Test fun aTourIsWrittenPointByPointAndSavedAtTheEnd() {
        val tours = JdbcTours(); val rec = FakeRecording(tours, clock); val tour = TourController(rec)
        tour.start("HIKE", 2)
        val id = rec.state.value!!.tourId
        assertEquals("active", tours.getTour(id)!!.state)
        line(0.0 to 0.0, 300.0 to 0.0).forEach { rec.fix(it) }
        assertEquals("every fix is a row at once", 31, tours.loadSegments(id).single().size)
        tour.pause(true); rec.fix(pt(400.0, 0.0)); tour.pause(false) // nothing recorded while paused
        line(300.0 to 0.0, 300.0 to 200.0).forEach { rec.fix(it) }
        assertEquals(listOf(31, 21), tours.loadSegments(id).map { it.size })
        assertEquals(500.0, rec.state.value!!.distanceM, 5.0)

        tour.end(delete = false); tour.stop()
        assertNull("no tour runs", rec.state.value)
        val saved = tours.getTour(rec.finished!!)!!
        assertEquals("done", saved.state); assertEquals(500.0, saved.distanceM, 5.0)
        // what the view model does with the finished tour: its points decide short / saved (ShortRecording)
        val short = ShortRecording.bins(saved.distanceM, tours.loadSegments(saved.id), rides = false)
        assertEquals(TourController.Finished.SAVED, tour.finishedOutcome(short))
        assertEquals(listOf("start HIKE 2", "pause", "resume", "stop"), rec.commands)
        assertTrue(problems.none())
    }

    @Test fun aStartByMistakeIsAShortRecording() {
        val tours = JdbcTours(); val rec = FakeRecording(tours, clock); val tour = TourController(rec)
        tour.start("HIKE", 2)
        repeat(5) { rec.fix(pt(it * 2.0, 0.0)) } // 8 m on the spot
        tour.end(delete = false); tour.stop()
        val t = tours.getTour(rec.finished!!)!!
        assertEquals(TourController.Finished.SHORT, tour.finishedOutcome(ShortRecording.bins(t.distanceM, tours.loadSegments(t.id), false)))
    }

    @Test fun aProcessKilledMidTourLeavesTheTourToContinue() {
        val file = File(tmp.root, "onpath.db").path
        val tours = JdbcTours(file); val rec = FakeRecording(tours, clock)
        TourController(rec).start("BIKE_TOURING", 3)
        line(0.0 to 0.0, 200.0 to 0.0).forEach { rec.fix(it) }
        val id = rec.state.value!!.tourId
        rec.kill(); tours.close()

        // the restart: a new process on the same database file
        val tours2 = JdbcTours(file); val rec2 = FakeRecording(tours2, clock); val tour2 = TourController(rec2)
        val active = tours2.findActiveRecording()
        assertEquals("the tour is still marked as recording", id, active!!.id)
        assertEquals("every point written before the kill is there", 21, tours2.loadSegments(id).single().size)
        tour2.showUnfinished(active, tours2.loadSegments(id).map { s -> s.map { GeoPoint(it.lat, it.lon) } })
        tour2.start("HIKE", 2) // held: no second tour beside the interrupted one
        assertFalse(rec2.running); assertEquals(1, tours2.listTours().size)
        assertTrue(problems.traced("tour").any { it.contains("Tour start held") })
        tour2.continueTour(active)
        assertNull(tour2.unfinished)
        line(200.0 to 0.0, 200.0 to 100.0).forEach { rec2.fix(it) }
        assertEquals("the same tour, a new piece after the break", listOf(21, 11), tours2.loadSegments(id).map { it.size })
        assertEquals(id, rec2.state.value!!.tourId)
    }

    @Test fun aPointThatCannotBeWrittenIsSaidAndTheTourGoesOn() {
        val tours = JdbcTours(); val rec = FakeRecording(tours, clock)
        rec.start("HIKE", 2)
        rec.fix(pt(0.0, 0.0))
        tours.failWrites = 1 // the disk is full for one write
        rec.fix(pt(10.0, 0.0))
        assertTrue(rec.state.value!!.saveFailed)
        rec.fix(pt(20.0, 0.0))
        assertFalse("the next point is stored again", rec.state.value!!.saveFailed)
        assertEquals("the one that waited went in with it, in order (PointQueue)", listOf(pt(0.0, 0.0), pt(10.0, 0.0), pt(20.0, 0.0)),
            tours.loadSegments(rec.state.value!!.tourId).single().map { GeoPoint(it.lat, it.lon) })
        assertEquals("the screen still draws all three", 3, rec.state.value!!.track.single().size)
        assertTrue("and the failure is in the problem log", problems.said("db").any { it.contains("database write failed (point)") })
        assertEquals("the user told once", 1, rec.saveFailNotices)
    }

    /**
     * [FakeRecording] mirrors the service's wiring of its core pieces; these are the lines it copies – a change there
     * fails here, and the fake is changed with it.
     */
    @Test fun theFakeRecordingCopiesTheServicesLines() {
        val svc = sourceFile("recording/RecordingService.kt").readText()
        for (line in listOf(
            "const val GAP_MS = ${FakeRecording.GAP_MS / 1000}_000L",
            "val wasLost = lastGoodFixElapsed > 0 && nowEl - lastGoodFixElapsed > GAP_MS",
            "val routeChecked = GuidanceGate.offRouteCheck(moveWatch.moving(nowEl), arrived)",
            "val bridged = wasLost && prev != null && PointFilter.bridgesGap(prev, p)",
            "PointFilter.judge(prev, p, loc.accuracy.toDouble(), continuous = !wasLost || bridged)",
            "if (verdict == PointFilter.Verdict.JUMP) return",
            "tourRun.count(moved, newPiece = wasLost && prev != null && !bridged)",
            "pointQueue.add(PointQueue.Pending(tourId, seq++, seg, p, loc.accuracy))",
            "if (now - lastStats >= ${FakeRecording.STATS_MS / 1000}_000) {",
            "if (gpsLost()) navigator?.onGpsLost()",
            "private fun gpsLost() = SystemClock.elapsedRealtime() - lastGoodFixElapsed > RestWatch.lostAfterMs(restGps.resting)",
            "val (nextSeq, lastSeg) = db.recordingCursor(existing.id)",
            "tourRun = TourRun.resumed(existing.distanceM, existing.ascentM, existing.descentM,",
            "if (intent == null) gpsWatchdog.arm(SystemClock.elapsedRealtime()) else gpsWatchdog.disarm()",
            "if (!p) { seg++; last = null; moveWatch.start(SystemClock.elapsedRealtime()); gpsWatchdog.resumed(SystemClock.elapsedRealtime()) }",
            // the two clocks: the fix's own time on the point, the wall clock for the tour's start and the pause's
            "val p = TrackPoint(loc.latitude, loc.longitude, seaLevelHeight(loc, nowEl), loc.time)",
            "val created = System.currentTimeMillis()",
            "pausedSinceMs = if (p) System.currentTimeMillis() else null",
            // the battery saver said once per tour
            "if (!LocationSaverMode.warnDuringTour(pm.isPowerSaveMode, mode, saverWarned)) return",
            "saverWarned = true",
            "Problems.warn(\"recording\", \"Battery saver came on during the tour and stops GPS with the screen off – user told\")",
            // the arrival on every fix, after the off-route check
            "try { if (routeChecked) checkOffRoute(p, loc.accuracy.toDouble(), nowEl) else navigator?.onGpsLost() }",
            "try { checkArrived(p.toGeo(), loc.accuracy.toDouble()) }",
            "if (!GuidanceGate.arrivalCheck(arrived, paused, planQuiet())) return",
            "val remaining = (len - (st.shownAlongM ?: st.progressM)).coerceAtLeast(0.0)",
            "if (!arrivalGate.onFix(pos, st.line.last(), remaining, accuracyM, openStops = nav.session.openStops)) return",
            // no precise location at a start: told, nothing started (the row untouched)
            "if (!tracker.hasFinePermission()) {",
            "\"${FakeRecording.NO_PERMISSION}\")",
            // a failed write: logged, told once per failure; the points wait in one queue and go in when the storage is back
            "private val pointQueue = PointQueue()",
            "writePoints(withNewPoint = true)",
            "val late = if (withNewPoint) waited - 1 else waited",
            "if (ok && saveFailed) { saveFailed = false; nm().cancel(SAVE_FAIL_ID) }",
            "val ok = safeDb(\"point\") { pointQueue.flush { db.appendPoint(it.tourId, it.seq, it.seg, it.point, it.accuracyM) }?.let { throw it } }",
            "if (ok && late > 0) Problems.info(\"recording\", \"\$late track point(s) written late – none lost\")",
            "if (pointQueue.dropped > 0 && pointQueue.dropped % 1000 == 1) {",
            "Problems.error(\"recording\", \"Storage failed for hours: \${pointQueue.dropped} oldest unwritten point(s) given up\")",
            "Problems.error(\"db\", \"Recording: database write failed (\$what)\", e)",
            "if (!saveFailed) { saveFailed = true; notifySaveFailed(); vibrate() }",
            "if (pointQueue.size > 0) writePoints() // storage back while standing still: the waiting points go in",
            "if (pointQueue.size > 0) writePoints() // the last waiting points, if the storage is back",
        )) assertTrue("RecordingService no longer has: $line", svc.contains(line))
        // the warning line without a motion sensor (NoGpsShow.line, sensorStill = null)
        val still = sourceFile("recording/StillSense.kt").readText().replace(Regex("\\s+"), " ")
        assertTrue(still.contains("sensorStill == true -> Line.NONE shortGap -> Line.QUIET sensorStill == false -> Line.WARN " +
            "!moveWatchMoving -> Line.QUIET else -> Line.WARN"))
        // a continued tour's next number: the same statement as TourDb's
        assertTrue(sourceFile("data/TourDb.kt").readText().contains("\"SELECT MAX(seq), MAX(seg) FROM points WHERE tour_id=?\""))
    }

    @Test fun theRealTablesAreThere() {
        val tours = JdbcTours()
        assertEquals(setOf("tours", "points", "tour_plan"), tours.tables().toSet())
        val id = tours.insertTour(TourKind.PLANNED, "Waalweg", "HIKE", 2, waypoints = listOf(com.onpath.app.gpx.GpxWaypoint(46.67, 11.13, "Algund")))
        assertEquals("Algund", tours.getTour(id)!!.waypoints.single().name)
        tours.setDeleted(id, 5); assertTrue(tours.listTours().isEmpty()); assertEquals(listOf(id), tours.listDeleted().map { it.id })
    }

    // ---- the service's re-routing against the app's session file, the order decided here ----

    private val sessionFile get() = File(tmp.root, NavSession.FILE)

    private fun planSession(token: Long, vararg corners: Pair<Double, Double>) = NavSession.start(NavKind.PLAN, "Plan $token", Activity.HIKE, 3,
        line(*corners), corners.map { pt(it.first, it.second) }).copy(token = token, reached = true)

    /** The app follows a plan, the service walks it and leaves it past the corner: a way back is being routed. */
    private fun offRoute(engine: FakeRoutingEngine): Pair<NavSessionStore, FakeNavService> {
        val app = NavSessionStore(sessionFile)
        app.save(planSession(1, 0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 1000.0))
        val service = FakeNavService(sessionFile, engine)
        service.followChanged()
        walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0).forEach { service.fix(it) }
        service.main.runAll()
        assertEquals("one re-route waits on the routing thread", 1, service.routing.size)
        assertTrue(engine.calls.isEmpty())
        return app to service
    }

    @Test fun aReRouteIsTakenWrittenAndSurvivesARestart() {
        val engine = FakeRoutingEngine()
        val (_, service) = offRoute(engine)
        service.routing.runAll(); service.main.runAll()
        assertTrue("the way back is asked", service.navigator!!.prompt is NavPrompt.Question)
        assertTrue(engine.calls.isNotEmpty())
        // taken 150 m further on: the way back is routed again from there and taken when found (Navigator.take)
        service.take(); service.main.runAll()
        assertEquals(NavPrompt.Searching, service.navigator!!.prompt)
        service.settle()
        assertTrue(service.navigator!!.session.rerouted)
        val after = service.restarted()
        assertTrue("the taken way is followed after a restart", after.navigator!!.session.rerouted)
        assertEquals(1L, after.navigator!!.session.token)
        assertEquals(0, service.dropped)
    }

    @Test fun aReRouteOfTheOldPlanLandingAfterTheAppsEditIsDropped() {
        val engine = FakeRoutingEngine()
        val (app, service) = offRoute(engine)
        service.routing.runAll(); service.main.runAll()         // the question for the old line is open
        service.take(); service.main.runAll()                    // "Take new route": its way is being routed …
        // … meanwhile the plan is edited in the app: a new session in the file, its FOLLOW_CHANGED still on the way
        val edited = planSession(2, 0.0 to 0.0, 1000.0 to 0.0, 2000.0 to 0.0)
        app.save(edited)
        service.settle()                                         // the old line's way back arrives first
        assertEquals(1, service.dropped)
        assertEquals("the app's plan is on the phone, not the old line's way back", 2L, NavSessionStore(sessionFile).load()!!.token)
        assertFalse(NavSessionStore(sessionFile).load()!!.rerouted)
        assertEquals("and the service follows it", 2L, service.navigator!!.session.token)
        service.followChanged(); service.settle()                // the late FOLLOW_CHANGED changes nothing
        assertEquals(2L, service.navigator!!.session.token)
        assertFalse(NavSessionStore(sessionFile).load()!!.rerouted)
    }

    @Test fun guidanceEndedInTheAppWhileAReRouteRunsStaysEnded() {
        val engine = FakeRoutingEngine()
        val (app, service) = offRoute(engine)
        service.routing.runAll(); service.main.runAll()
        service.take(); service.main.runAll()                    // the way back is being routed …
        app.save(null)                                           // … clearFollow in the app: the file is gone
        service.settle()
        assertEquals(1, service.dropped)
        assertFalse("the removed route does not come back", sessionFile.exists())
        assertNull(service.navigator)
    }

    // ---- a route run of the view model's kind: routed off the main thread, applied only while it is the newest ----

    /**
     * The followAhead pattern (AppViewModel) with its real parts; the state is two fields. Plain hand-overs between
     * the two executors.
     */
    private class RouteRuns(val engine: FakeRoutingEngine, val main: ManualExecutor, val routing: ManualExecutor) {
        val requests = SplitRequests()
        val legs = LegCache()
        var plan: List<GeoPoint> = emptyList()
        var line: List<GeoPoint>? = null
        var cancel: RouteCancel? = null

        fun edit(points: List<GeoPoint>) = main.execute {
            plan = points
            val gen = requests.next(points)
            cancel?.cancel()
            val c = RouteCancel().also { cancel = it }
            routing.execute {
                val r = runCatching { Planner(engine, c, legs = legs).plan(points, Activity.HIKE, 2) }
                main.execute {
                    if (!c.isCancelled && requests.isCurrent(gen, plan)) line = r.getOrNull()?.takeIf { it.error == null }?.legs?.flatMap { it.points }
                }
            }
        }
    }

    @Test fun onlyTheNewestRouteRunIsApplied() {
        val engine = FakeRoutingEngine()
        val runs = RouteRuns(engine, ManualExecutor("main"), ManualExecutor("route"))
        val a = pt(0.0, 0.0); val b = pt(500.0, 0.0); val c = pt(500.0, 500.0)
        runs.edit(listOf(a, b))
        runs.main.runAll()                                       // the first run reaches the routing thread …
        runs.edit(listOf(a, b, c))                               // … a second edit comes before it routed
        runs.main.runAll()
        assertEquals(2, runs.routing.size)
        runs.routing.runNext(); runs.main.runAll()               // the old run ends: cancelled, nothing applied
        assertNull("the older run is not shown", runs.line)
        runs.routing.runAll(); runs.main.runAll()
        assertNotNull(runs.line)
        assertEquals("the line goes through all three points", 1000.0, Geo.length(runs.line!!), 20.0)
        assertTrue(runs.line!!.any { Geo.distance(it, c) < 1.0 })
        // the same plan again: its legs come from the cache, the engine is not asked
        val asked = engine.calls.size
        runs.edit(listOf(a, b, c)); runs.main.runAll(); runs.routing.runAll(); runs.main.runAll()
        assertEquals(asked, engine.calls.size)
        assertTrue(problems.none())
    }

    @Test fun aRouteThatFailsLeavesTheLineAsItWasAndTheEngineCanBeHeld() {
        val engine = FakeRoutingEngine()
        val a = pt(0.0, 0.0); val b = pt(500.0, 0.0)
        engine.fail = { call -> "No route found near this point".takeIf { call.waypoints.any { Geo.distance(it, b) < 1.0 } } }
        assertNotNull(Planner(engine).plan(listOf(a, b), Activity.HIKE, 2).error)
        // hold: a request waits inside the engine on its own thread until the test lets it go
        engine.fail = { null }; engine.hold()
        val t = Thread { engine.route(listOf(a, b), Activity.HIKE, 2) }.apply { start() }
        assertTrue(engine.awaitHeld()); assertTrue(t.isAlive)
        engine.release(); t.join(10_000)
        assertFalse(t.isAlive)
    }
}
