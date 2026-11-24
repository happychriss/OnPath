package com.onpath.app.ui.tour

import com.onpath.app.data.Geo
import com.onpath.app.navigation.NavKind
import com.onpath.app.navigation.NavSession
import com.onpath.app.navigation.NavSessionStore
import com.onpath.app.navigation.Phrase
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.navigation.VoiceGuide
import com.onpath.app.navigation.offRoute
import com.onpath.app.recording.PointFilter
import com.onpath.app.recording.RecState
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.testkit.FakeNavService
import com.onpath.app.testkit.FakeRecording
import com.onpath.app.testkit.FakeRoutingEngine
import com.onpath.app.testkit.JdbcTours
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * One GPS jump next to Navigate (hard cases, docs/team/items/2026-11-24-hard-case-test-coverage.md, test 7): a plan
 * followed with a fix every second, then one fix ~2 km away (accuracy 12 m – a jump the accuracy filter lets through),
 * one second later the real position again. The jump lands beside the route, on the route further ahead, or on the goal;
 * on foot and on the bike (the bike also leaves the route by direction and speed). Then 2–3 jump fixes in a row.
 *
 * The service is [FakeRecording] with its real core pieces (PointFilter's jump rule: faster than 35 m/s since the last
 * point = dropped), its navigation [FakeNavService] (the real Navigator / OffRouteDetector, fed BEFORE the point is
 * judged, as in the service) and the arrival check (ArrivalGate on the navigator's state); the voice is the real
 * [VoiceGuide] fed as `RecordingService.voiceStep`; the app side is [TourController] (its arrival box).
 *
 * The rules promise: a jump never becomes a point (no spike in the track or the distance) while it is faster than 35 m/s
 * from the last point – 2 km for up to 57 s; the off-route alarm needs 15 s off the line and 25 m moved (a single jump
 * never confirms it).
 *
 * Found 2026-11-24 (the @Ignore'd tests): the navigation and the arrival get every fix BEFORE PointFilter judges it
 * (RecordingService.onFix: checkOffRoute / checkArrived, then PointFilter.judge) – a jump landing on the route ahead or on
 * the goal is taken as the position there. A jump beside the route is harmless.
 */
class JumpFixFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val problems = ProblemsRule()
    @get:Rule val composeState = OwnComposeSnapshot()

    private var now = 1_793_000_000_000L
    private val tours = JdbcTours()
    private val engine = FakeRoutingEngine()
    private val nav by lazy { FakeNavService(File(tmp.root, NavSession.FILE), engine) }
    private val rec by lazy { FakeRecording(tours, { now }, nav) }
    private val tour by lazy { TourController(rec) }
    private val voice = VoiceGuide()
    private val said = ArrayList<Phrase>()
    /** The state after every second. */
    private val seen = ArrayList<RecState>()
    private var activity = Activity.HIKE

    /** A plan [lengthM] due east, followed from its start. */
    private fun startWithPlan(a: Activity, lengthM: Double) {
        activity = a
        val plan = line(0.0 to 0.0, lengthM to 0.0)
        NavSessionStore(File(tmp.root, NavSession.FILE)).save(
            NavSession.start(NavKind.PLAN, "Waalweg", a, 3, plan, listOf(plan.first(), plan.last())).copy(token = 1))
        tour.start(a.name, 3)
        nav.settle()
    }

    /** One second: the service's tick, one fix at [p], its navigation and arrival, the voice; the app sees the state. */
    private fun second(p: GeoPoint, accuracyM: Float = 5f) {
        now += 1000
        rec.tick()
        rec.fix(p, accuracyM)
        nav.settle()
        voiceStep()
        val st = rec.state.value!!
        tour.onArrived(st.arrived) // TourSession's state collector
        seen += st
    }

    /** `RecordingService.voiceStep` with a voice on (as GpsLostFlowTest). */
    private fun voiceStep() {
        val st = nav.navigator?.state ?: return
        val s = st.session
        val r = rec.state.value ?: return
        voice.update(VoiceGuide.Input(nowMs = now, line = st.line, version = st.version, alongM = st.shownAlongM,
            onRoute = st.prompt?.offRoute != true && st.shownAlongM != null, speedMps = null, bike = s?.activity != Activity.HIKE,
            offRoute = st.prompt?.offRoute == true, rerouted = s?.rerouted == true, paused = r.paused, gpsLost = r.gpsLost,
            stops = s?.stops.orEmpty(), visited = s?.visited.orEmpty(), quiet = rec.standingStill()))?.let { said += it }
    }

    private val speed get() = if (activity == Activity.HIKE) 1.4 else 5.0

    /** [s] seconds along the plan from [fromX], a fix every second; returns where the walker is. */
    private fun go(fromX: Double, s: Int): Double {
        var x = fromX
        repeat(s) { x += speed; second(pt(x, 0.0)) }
        return x
    }

    /**
     * 300 m along the plan, then [jumps] fixes at [jumpTo] (one per second, the walker going on meanwhile), then on for a
     * minute. Asserts what must hold whatever the jump, and returns where the walker is at the end.
     */
    private fun tourWithJump(jumpTo: (Double) -> GeoPoint, jumps: Int = 1): Double {
        var x = go(0.0, (300 / speed).toInt())
        val before = rec.state.value!!
        val alongBefore = nav.navigator!!.state.shownAlongM!!
        assertEquals("following the plan", x, alongBefore, 15.0)
        val target = jumpTo(x)
        assertTrue("the jump is about 2 km", Geo.distance(target, pt(x, 0.0)) in 1_700.0..2_300.0)
        repeat(jumps) { x += speed; second(target, accuracyM = 12f) }
        assertTrue("the jump passes the accuracy filter", PointFilter.accuracyOk(true, 12f))
        val jumpStates = seen.takeLast(jumps)
        x = go(x, 60)

        val after = seen.takeLast(60)
        assertTrue("no off-route alarm", seen.none { it.offRouteAlert })
        assertNull("no off-route question", nav.navigator!!.prompt)
        assertTrue("no off-route sentence", said.none { it is Phrase.OffRoute })
        assertTrue("no re-route asked", engine.calls.isEmpty())
        assertTrue("no arrival", seen.none { it.arrived })
        assertFalse("no arrival box", tour.arrivalAsk)
        assertTrue("nothing said about arriving", said.none { it is Phrase.Arrived || it is Phrase.ArrivedSide })
        val track = rec.state.value!!.track
        assertEquals("one piece", 1, track.size)
        assertTrue("no point at the jump", track.flatten().none { Geo.distance(it, target) < 100.0 })
        assertTrue("no spike: no step longer than a few seconds' walk",
            track.single().zipWithNext().all { (a, b) -> Geo.distance(a, b) < 4 * speed + 5 })
        assertEquals("the jump adds nothing to the distance", before.distanceM, jumpStates.last().distanceM, 0.0)
        assertEquals("the distance is the way walked", x, rec.state.value!!.distanceM, 10.0)
        assertTrue("the navigation is back on the route within a few seconds", after.drop(5).all { it.nav!!.shownAlongM != null })
        assertEquals("and goes on at the real position", x, nav.navigator!!.state.shownAlongM!!, 15.0)
        assertTrue(problems.none("recording"))
        assertTrue(problems.none("navigation"))
        return x
    }

    private val north: (Double) -> GeoPoint = { x -> pt(x, 2_000.0) }

    @Test fun oneJumpBesideTheRoute_onFoot() { startWithPlan(Activity.HIKE, 3_000.0); tourWithJump(north) }

    @Test fun oneJumpBesideTheRoute_onTheBike() { startWithPlan(Activity.BIKE_TOURING, 5_000.0); tourWithJump(north) }

    /**
     * The jump lands on the route 2 km further on: the progress must not follow it there. Today it does: the detector
     * matches the jump in its window (progress − 300 m … + 2 km) and takes it as the progress (OffRouteDetector.onFix,
     * ON_ROUTE); the next real fix lies behind the new window – LEAVING ("to go" gone), confirmed off route after
     * 15 s + 25 m moved, the question and a re-route while walking on the route.
     */
    @Ignore("bug: a jump onto the route ahead moves the navigation's progress there (navigation gets the fix before PointFilter's jump rule) – the real fixes are off route: question + re-route ~22 s later")
    @Test fun oneJumpOntoTheRouteAhead_onFoot() { startWithPlan(Activity.HIKE, 4_000.0); tourWithJump({ x -> pt(x + 2_000.0, 0.0) }) }

    @Ignore("bug: a jump onto the route ahead moves the navigation's progress there (navigation gets the fix before PointFilter's jump rule) – the real fixes are off route: question + re-route ~22 s later")
    @Test fun oneJumpOntoTheRouteAhead_onTheBike() {
        startWithPlan(Activity.BIKE_TOURING, 5_000.0); tourWithJump({ x -> pt(x + 2_000.0, 0.0) })
    }

    /**
     * The jump lands on the goal: no "you have arrived". Today the jump fix moves the progress to the line's end (as
     * above) and the arrival check on the same fix finds it there – "been away", the progress in the last 50 m, at the
     * goal: arrived at once; guidance quiet from then on (no turns, no off-route), the arrival box asks to end the tour.
     */
    @Ignore("bug: a jump onto the goal is taken as the arrival (checkArrived gets the fix before PointFilter's jump rule) – arrival box, guidance quiet 2 km before the goal")
    @Test fun oneJumpOntoTheGoal_onFoot() { startWithPlan(Activity.HIKE, 2_300.0); tourWithJump({ pt(2_300.0, 0.0) }) }

    @Ignore("bug: a jump onto the goal is taken as the arrival (checkArrived gets the fix before PointFilter's jump rule) – arrival box, guidance quiet 2 km before the goal")
    @Test fun oneJumpOntoTheGoal_onTheBike() { startWithPlan(Activity.BIKE_TOURING, 2_300.0); tourWithJump({ pt(2_300.0, 0.0) }) }

    /** Three jump fixes in a row at the same wrong place (1 s apart): still faster than 35 m/s from the last point. */
    @Test fun threeJumpsInARowBesideTheRoute() { startWithPlan(Activity.HIKE, 3_000.0); tourWithJump(north, jumps = 3) }

    @Ignore("bug: a jump onto the goal is taken as the arrival (checkArrived gets the fix before PointFilter's jump rule) – arrival box, guidance quiet 2 km before the goal")
    @Test fun threeJumpsInARowOntoTheGoal() { startWithPlan(Activity.HIKE, 2_300.0); tourWithJump({ pt(2_300.0, 0.0) }, jumps = 3) }
}
