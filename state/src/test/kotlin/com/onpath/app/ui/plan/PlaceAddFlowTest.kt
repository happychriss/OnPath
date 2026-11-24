package com.onpath.app.ui.plan

import com.onpath.app.data.Geo
import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.PlaceAddMenu
import com.onpath.app.domain.PlanStatus
import com.onpath.app.domain.TakenPin
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.domain.plan.RunCause
import com.onpath.app.navigation.TourSplit
import com.onpath.app.poi.Poi
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanEdits
import com.onpath.app.routing.RouteCancel
import com.onpath.app.routing.RoutingException
import com.onpath.app.search.Place
import com.onpath.app.testkit.ManualExecutor
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * Adding points to the plan (rebuild phase 2 piece 11, core feature C2: the hold on the map every tour starts with) as flows
 * of the REAL [PlaceAddSlice] with the real [PlanEditSlice] it edits through, [PlanDocument] (its store re-read after the
 * flow), [PlanRunSlice], [RunCause] and the real [com.onpath.app.domain.HoldPaths]. Fakes only at the edges: the host (the
 * map's menus, the place card, the tour, the route the map shows, the search's pin), the router (scripted lengths; its
 * thread is a [ManualExecutor] the test runs when it decides) and the clock (the measure's 1.5 s limit runs on a virtual-time
 * dispatcher). The host records each call IN ORDER with the plan as stored AT THAT MOMENT, the edit's host does the same for
 * the plan's edit sequence together with [PlanEditSlice.pinTaken] – so "an add is one change and the pin is written after it" is
 * read from the log. The tour's own code (tourPassed, wayThereFrom, myPosition, the followed line) is a scripted answer here;
 * values read on one branch only count their reads ([App.reads]).
 *
 * Replaces the source pins on the view model's add functions (WholeRouteTest.placeAddedFromTheCardOrTheCandidateIsFitted,
 * PlanEditsTest.asStartIsStartHere, FarDestinationTest.cancelOfARunShowsThePinOfTheAddItTookBack, FoundPlacePinTest's
 * "adding the place takes the pin"). What has no behaviour test here: the view model's own adapters (PlaceAddAdaptersTest reads
 * them), the POI / point / pin menus and the place card (PlaceMenusFlowTest, piece 12), `tourPassed` itself (tour code).
 */
class PlaceAddFlowTest {
    @get:Rule val composeState = OwnComposeSnapshot()
    @get:Rule val problems = ProblemsRule()

    private val a = GeoPoint(46.60, 11.10)
    private val b = GeoPoint(46.62, 11.10)
    private val c = GeoPoint(46.64, 11.10)
    private fun at(from: GeoPoint, eastM: Double, northM: Double) =
        GeoPoint(from.lat + Geo.degLat(northM), from.lon + Geo.degLon(eastM, from.lat))
    /** 1.5 km east of the goal: appending is the shorter on straight lines, not by much on the paths (see [App.lengths]). */
    private val beside = at(c, 1500.0, 0.0)
    private val far = Place("photon:1", "Mechernich", "place", 50.5947, 6.6517, sub = "town")
    private val hut = Poi("n1", "hut", "Hütte", 46.63, 11.11)
    private val farAt = GeoPoint(far.lat, far.lon)
    private val hutAt = GeoPoint(hut.lat, hut.lon)
    private val letters = HashMap<GeoPoint, String>().apply { put(a, "a"); put(b, "b"); put(c, "c"); put(beside, "S"); put(farAt, "M"); put(hutAt, "H") }
    private fun ids(l: List<GeoPoint>?) = l?.joinToString("") { letters[it] ?: "?" } ?: "-"

    /** Runs a launched block inline (like Main.immediate), holds the measure's time limit until [advance] reaches it. */
    @OptIn(InternalCoroutinesApi::class)
    private class VirtualMain : CoroutineDispatcher(), Delay {
        var now = 0L
        private class Timer(val at: Long, val seq: Int, val block: Runnable) { var live = true }
        private val timers = ArrayList<Timer>()
        private var seq = 0
        val pending: Int get() = timers.count { it.live }
        override fun isDispatchNeeded(context: CoroutineContext) = false
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            val t = Timer(now + timeMillis, seq++, Runnable { continuation.cancel() }) // not used by the measure
            timers += t
        }
        // withTimeoutOrNull asks for exactly this
        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
            val t = Timer(now + timeMillis, seq++, block)
            timers += t
            return DisposableHandle { t.live = false }
        }
        fun advance(ms: Long) {
            val to = now + ms
            while (true) {
                val next = timers.filter { it.live }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
                if (next.at > to) break
                next.live = false; now = next.at; next.block.run()
            }
            now = to
        }
    }

    private class MemoryPlanStore : PlanStore {
        override var points: List<GeoPoint> = emptyList()
        override var pinned: Set<GeoPoint> = emptySet()
        override var savedName: String? = null
        override var savedId: Long = -1L
        override var savedPoints: List<GeoPoint>? = null
    }

    private inner class App(initial: List<GeoPoint> = emptyList()) {
        val store = MemoryPlanStore().also { it.points = initial }
        val plan = PlanDocument(store)
        val causes = RunCause()
        val main = VirtualMain()
        val worker = ManualExecutor("hold")
        /** What the hosts were asked, in order; the lines carry the plan as stored at that moment. */
        val log = ArrayList<String>()
        lateinit var slice: PlaceAddSlice
        lateinit var edit: PlanEditSlice
        lateinit var run: PlanRunSlice

        // the app's facts the slice reads through its host
        var planMode = true
        var touring = false
        var activity = Activity.HIKE
        var sac = 2
        var routeLine: List<GeoPoint>? = null
        var position: GeoPoint? = null
        var passed: Set<Int> = emptySet()
        var followLine: List<GeoPoint>? = null
        var wayThere: GeoPoint? = null
        var gps: GeoPoint? = null
        var mark: Place? = null
        var canEdit = true
        var candidate = false
        var cardClosed = 0
        var names = ArrayList<Pair<GeoPoint, String>>()
        var kinds = ArrayList<Pair<GeoPoint, String?>>()
        var pinOnMap: Place? = null
        var pointMenus = ArrayList<Pair<Int, String>>()
        /** What the fake router answers: its lengths by (from, to); default the straight distance. */
        var lengths: (GeoPoint, GeoPoint) -> Double = { x, y -> Geo.distance(x, y) }
        val routed = ArrayList<String>()
        var routerThrows: String? = null
        /** Reads of values the slice may read on one branch only. */
        val reads = HashMap<String, Int>()
        private fun read(what: String) { reads[what] = (reads[what] ?: 0) + 1 }

        fun st() = "[stored=${ids(store.points).ifEmpty { "-" }} pinTaken=${edit.pinTaken?.at?.let { letters[it] ?: "?" } ?: "-"}]"

        val router = HoldRouter { x, y, act, level, cancel ->
            routed += "${ids(listOf(x))}→${ids(listOf(y))} ${act.name} T$level"
            routerThrows?.let { throw RoutingException(it) }
            if (cancel.isCancelled) throw RoutingException("stopped", kind = RoutingException.Kind.CANCELLED)
            lengths(x, y)
        }

        val host = object : PlaceAddHost {
            override fun closeMapOverlays(why: String) { log += "closeMapOverlays($why) ${st()}" }
            override fun openPointMenu(index: Int, via: String) { pointMenus += index to via; log += "openPointMenu($index, $via) ${st()}" }
            override fun closePlaceCard() { cardClosed++; log += "closePlaceCard ${st()}" }
            override fun planMode() = planMode
            override fun canEditMap() = canEdit
            override fun activity() = activity
            override fun sac() = sac
            override fun routeLine(): List<GeoPoint>? { read("routeLine"); return routeLine }
            override fun touring() = touring
            override fun myPosition(): GeoPoint? { read("myPosition"); return position }
            override fun tourPassed(plan: List<GeoPoint>): Set<Int> { log += "tourPassed(${ids(plan)})"; return passed }
            override fun followLine(): List<GeoPoint>? { read("followLine"); return followLine }
            override fun wayThereFrom(): GeoPoint? { read("wayThereFrom"); return wayThere }
            override fun gpsPoint(): GeoPoint? { read("gps"); return gps }
            override fun searchMark(): Place? = mark
            override fun takePin(lat: Double, lon: Double): Place? {
                log += "takePin ${st()}"
                return pinOnMap?.takeIf { Geo.distance(GeoPoint(it.lat, it.lon), GeoPoint(lat, lon)) < 1.0 }
                    .also { if (it != null) pinOnMap = null }
            }
            override fun isCandidate(poi: Poi): Boolean { log += "isCandidate"; return candidate }
            override fun rememberPlaceName(p: GeoPoint, name: String) { names += p to name; log += "name($name) ${st()}" }
            override fun rememberPoiKind(p: GeoPoint, kind: String?) { kinds += p to kind; log += "kind($kind) ${st()}" }
            override fun stopIndexAt(lat: Double, lon: Double): Int =
                plan.points.indexOfFirst { Math.abs(it.lat - lat) < 1e-7 && Math.abs(it.lon - lon) < 1e-7 }
            override fun routeIndexOf(place: Place): Int? = stopIndexAt(place.lat, place.lon).takeIf { it >= 0 }
        }

        /** The edit's host: the app's answers; Cancel-of-a-hold, the menus and the guard go to the REAL slice as the view model's adapter does. */
        val editHost = object : PlanEditHost {
            override fun cancelHoldMeasure(why: String) { log += "edit.holdOff($why) ${st()}"; slice.cancelHoldMeasure(why) }
            override fun stopFollowingWhenMapTurns() = Unit
            override fun closePointMenu(why: String) = Unit
            override fun clearFirstPointMenu() { slice.firstPointMenu = null }
            override fun clearHoldAddMenu() { slice.holdAddMenu = null }
            override fun clearHighlight() = Unit
            override fun removePin(why: String) = Unit
            override fun menuAction(what: String): Int? = null
            override fun setRoutingSpot(spot: GeoPoint?) = Unit
            override fun loadMapsForNewPoints(before: List<GeoPoint>) = Unit
            override fun keepAsDraft(why: String) = Unit
            override fun routeLine(): List<GeoPoint>? = routeLine
            override fun recalc() { log += "edit.recalc ${st()}" }
            override fun restoreActivity(c: PlanStatus.Cause.ActivityChange) = Unit
            override fun planChanged() = Unit
            override fun myPosition(): GeoPoint? = position
            override fun touring() = touring
            override fun following() = followLine != null
            override fun followIsTrack() = false
            override fun followsLine(line: List<GeoPoint>) = followLine == line
            override fun followFromPlan() = false
            override fun splitOf(before: List<GeoPoint>, after: List<GeoPoint>): TourSplit.Outcome? = null
            override fun sayNoDestination() = Unit
            override fun oneLeftGoesFromHere(before: List<GeoPoint>, mayEmpty: Boolean) = Unit
            override fun endGuidanceIfPlanEmpty() = Unit
            override fun clearFollow() = Unit
            override fun guidanceEndedByEmptyPlan() = Unit
            override fun stopName(p: GeoPoint): String? = null
            override suspend fun pointNames(): PointNames = PointNames { null }
            override var searchMark: Place?
                get() = mark
                set(v) { mark = v; log += "searchMark=${v?.name}" }
        }

        init {
            run = PlanRunSlice(causes, object : PlanRunHost {
                override fun points() = plan.points.toList()
                override fun clearRoute() = Unit
                override fun noCorridor() = Unit
                override fun loadDeferredMaps(before: List<GeoPoint>) = Unit
                override fun recalc() { log += "run.recalc" }
                override fun cancelRun(why: NotRouted) { edit.cancelRun(why) }
            })
            edit = PlanEditSlice(plan, run, causes, editHost, CoroutineScope(Job() + main), main)
            slice = PlaceAddSlice(plan, edit, run, host, router, worker.asCoroutineDispatcher(), CoroutineScope(Job() + main))
        }

        fun stored() = ids(store.points)
        fun restarted() = ids(PlanDocument(store).points)
        /** The router's thread works through what waits; its answers reach the main thread inline. */
        fun measure() = worker.runAll()
        fun trace() = problems.traced("plan")
        /** Fit asked for the next route (the first route / a place added). */
        val fit: Boolean get() = run.routeRun.fitNext
        /** A finished run: no fit pending, as before the add under test. */
        fun settle() {
            causes.start()
            run.routeEvent(RouteRun.Event.Recalc(listOf(a), Activity.HIKE, planMode = true, newPlan = false))
            run.routeEvent(RouteRun.Event.Unrouted(NotRouted.STOPPED))
            log.clear()
        }
        /** The straight legs of a 3-point plan a b c are 2.2 km; [append] / [insert] script what the paths say for a spot beside it. */
        fun scriptPaths(spot: GeoPoint, append: Double, insert: Double) {
            lengths = { x, y -> when {
                x == c && y == spot -> append
                x == spot || y == spot -> (2220.0 + insert) / 2
                else -> 2220.0
            } }
        }
    }

    // ---------------- C2: a hold on the empty map ----------------

    @Test fun theFirstTwoHoldsAddAtOnceAndEachIsOneStoredStepAfterTheMenusClosed() {
        val app = App()
        app.slice.holdOnMap(a, 30.0)
        assertEquals("a", app.stored())
        app.slice.holdOnMap(b, 30.0)
        assertEquals("ab", app.stored())
        assertEquals("ab", app.restarted()) // survives the process
        assertEquals(listOf("New point by a hold: plan 0 → 1", "New point by a hold: plan 1 → 2"), app.trace())
        // the menus closed first, then the one change; no measure on fewer than 2 points
        assertEquals("closeMapOverlays(hold on the map (new point)) [stored=- pinTaken=-]", app.log.first())
        assertEquals(0, app.worker.size)
        assertEquals(0, app.routed.size)
        assertNull(app.slice.holdMeasuring)
        assertNull(app.slice.holdAddMenu)
        // one Undo step per hold
        app.edit.undoStep()
        assertEquals("a", app.stored())
    }

    @Test fun aHoldOnTheLineIsInsertedAtOnceWithoutMeasuring() {
        val app = App(listOf(a, c))
        app.routeLine = listOf(a, c)
        app.slice.holdOnMap(b, 30.0)
        assertEquals("abc", app.stored())
        assertEquals(0, app.routed.size)
        assertEquals(0, app.worker.size)
        assertNull(app.slice.holdMeasuring)
        assertTrue(app.trace().last(), app.trace().last().startsWith("New point by a hold: on the line, set at once (line "))
    }

    @Test fun aHoldBesideThePlanIsMeasuredOnThePathsAndTheShorterWinsAtOnce() {
        // appending clearly shorter on the paths → the new goal at once
        val goal = App(listOf(a, b, c))
        goal.scriptPaths(beside, append = 300.0, insert = 900.0)
        goal.slice.holdOnMap(beside, 30.0)
        // while the router works: a ring turns on the spot, nothing is added, nothing asked yet
        assertEquals(beside, goal.slice.holdMeasuring)
        assertEquals("abc", goal.stored())
        assertEquals(1, goal.worker.size)
        assertNull(goal.slice.holdAddMenu)
        assertEquals(0, goal.routed.size)
        goal.measure()
        assertNull(goal.slice.holdMeasuring)
        assertEquals("abcS", goal.stored())
        assertEquals("abcS", goal.restarted())
        assertTrue(goal.trace().last(), goal.trace().last().startsWith("New point by a hold: appending clearly shorter, the new goal at once (line "))
        // the router was asked for the plan's activity and level, appending (goal → spot) first
        assertEquals("c→S HIKE T2", goal.routed.first())

        // inserting clearly shorter → a stop at once, before the goal
        val stop = App(listOf(a, b, c))
        stop.scriptPaths(beside, append = 900.0, insert = 300.0)
        stop.slice.holdOnMap(beside, 30.0)
        stop.measure()
        assertEquals("abSc", stop.stored())
        assertTrue(stop.trace().last(), stop.trace().last().startsWith("New point by a hold: inserting clearly shorter, a stop at once (line "))

        // nearly the same → the small menu, the cheaper first; nothing added
        val ask = App(listOf(a, b, c))
        ask.scriptPaths(beside, append = 500.0, insert = 550.0)
        ask.slice.holdOnMap(beside, 30.0)
        ask.measure()
        assertEquals("abc", ask.stored())
        val menu = ask.slice.holdAddMenu!!
        assertEquals(beside, menu.p)
        assertEquals(30.0, menu.lineSnapM, 0.0)
        assertEquals(listOf(PlaceAddMenu.Item.NEW_DESTINATION, PlaceAddMenu.Item.ADD_STOP), menu.menu.main)
        assertNull(ask.slice.holdMeasuring)
        assertEquals("Hold on the map: asks NEW_DESTINATION / ADD_STOP", ask.trace().last())
    }

    @Test fun aTimeoutDecidesOnTheStraightLinesAndTheRouterNeverRunsAfterwards() {
        val app = App(listOf(a, b, c))
        app.scriptPaths(beside, append = 900.0, insert = 300.0) // the paths would say: a stop – the straight lines say: the new goal
        app.slice.holdOnMap(beside, 30.0)
        app.main.advance(1_499)
        assertEquals(beside, app.slice.holdMeasuring)
        assertEquals("abc", app.stored())
        app.main.advance(1)
        assertNull(app.slice.holdMeasuring)
        assertEquals("abcS", app.stored())
        assertTrue(app.trace().last(), app.trace().last().contains("paths failed: timeout after 1500 ms ("))
        app.measure() // the router's thread gets to it late: the measure was given up
        assertEquals(emptyList<String>(), app.routed)
        assertEquals("abcS", app.stored())
    }

    @Test fun aRouterFailureKeepsTheStraightLinesToo() {
        val app = App(listOf(a, b, c))
        app.routerThrows = "no routing data"
        app.slice.holdOnMap(beside, 30.0)
        app.measure()
        assertNull(app.slice.holdMeasuring)
        assertEquals("abcS", app.stored())
        assertTrue(app.trace().last(), app.trace().last().contains("paths failed: "))
    }

    /**
     * [umbau]'s finding 2026-11-20 (owner: never lose the user's input silently): a hold while the first route still snaps
     * the plan was measured on the plan before the snap and its result dropped – nothing added, no word. A measure for a
     * plan no longer shown is measured again on the plan now shown and ends in a point or the menu; only leaving Plan drops it.
     */
    @Test fun aMeasureForAPlanChangedMeanwhileIsMeasuredAgainOnThePlanNowShown() {
        val snappedB = at(b, 8.0, 0.0)
        letters[snappedB] = "B"
        val changes = listOf<Triple<String, App.() -> Unit, String>>(
            Triple("the first route snapped a point", { plan.moveSnapped(listOf(a, b, c), listOf(a, snappedB, c)) }, "aBcS"),
            Triple("another activity", { activity = Activity.EBIKE }, "abcS"),
            Triple("another level", { sac = 3 }, "abcS"),
        )
        for ((why, change, added) in changes) {
            val app = App(listOf(a, b, c))
            app.scriptPaths(beside, append = 300.0, insert = 900.0)
            app.slice.holdOnMap(beside, 30.0)
            app.change()
            app.worker.runNext() // the first measure finds its plan gone: measured again, the ring stays on the spot
            assertEquals("$why: the ring stays while measuring again", beside, app.slice.holdMeasuring)
            assertTrue("$why: said so: ${app.trace()}", app.trace().last().startsWith(
                "Hold beside the plan: measured for a plan no longer shown – measured again on the plan now shown (paths: insert"))
            app.measure()
            assertEquals("$why: the point is added on the plan now shown", added, app.stored())
            assertNull("$why: the ring is off", app.slice.holdMeasuring)
            assertTrue("$why: ${app.trace().last()}",
                app.trace().last().startsWith("New point by a hold: appending clearly shorter, the new goal at once (line "))
            // the second measure asked the router for the plan now shown
            assertEquals("$why: ${app.routed}", "c→S ${app.activity.name} T${app.sac}", app.routed.last { it.startsWith("c→S") })
        }
        // a tour started meanwhile: the tour's zone decides at once (no second measure) – here the menu asks
        val tour = App(listOf(a, b, c))
        tour.scriptPaths(beside, append = 300.0, insert = 900.0)
        tour.slice.holdOnMap(beside, 30.0)
        tour.touring = true
        tour.measure()
        assertEquals(0, tour.worker.size)
        assertNull(tour.slice.holdMeasuring)
        assertEquals(beside, tour.slice.holdAddMenu!!.p)
        assertEquals("abc", tour.stored())
    }

    /** Leaving Plan while the measure ran: nothing is added (the user went elsewhere), and the trace says so. */
    @Test fun aMeasureAfterLeavingPlanAddsNothing() {
        val app = App(listOf(a, b, c))
        app.scriptPaths(beside, append = 300.0, insert = 900.0)
        app.slice.holdOnMap(beside, 30.0)
        app.planMode = false
        app.measure()
        assertEquals("abc", app.stored())
        assertNull(app.slice.holdMeasuring)
        assertNull(app.slice.holdAddMenu)
        assertEquals(0, app.worker.size)
        assertTrue(app.trace().last(),
            app.trace().last().startsWith("Hold beside the plan: measured, but Plan was left – nothing added (paths: insert"))
    }

    /** The plan changes again while it is measured again: the straight lines on the plan now shown decide – still a point. */
    @Test fun aSecondRaceStillEndsInAPointOnTheStraightLines() {
        val app = App(listOf(a, b, c))
        app.scriptPaths(beside, append = 900.0, insert = 300.0) // the paths would say: a stop – the straight lines say: the new goal
        app.slice.holdOnMap(beside, 30.0)
        app.activity = Activity.EBIKE
        app.worker.runNext() // the first measure: its plan is gone, measured again
        assertEquals(beside, app.slice.holdMeasuring)
        app.sac = 3
        app.measure()
        assertEquals("abcS", app.stored())
        assertNull(app.slice.holdMeasuring)
        assertEquals(0, app.worker.size)
        assertTrue(app.trace().last(),
            app.trace().last().startsWith("New point by a hold: appending clearly shorter, the new goal at once (line "))
        assertTrue(app.trace().toString(), app.trace().any {
            it.startsWith("Hold beside the plan: measured again for a plan no longer shown – the straight lines on the plan now shown decide")
        })

        // the second measure times out: its own 1.5 s limit gives the straight lines too
        val slow = App(listOf(a, b, c))
        slow.scriptPaths(beside, append = 900.0, insert = 300.0)
        slow.slice.holdOnMap(beside, 30.0)
        slow.activity = Activity.EBIKE
        slow.worker.runNext()
        assertEquals(beside, slow.slice.holdMeasuring)
        slow.main.advance(com.onpath.app.domain.HoldPaths.TIMEOUT_MS)
        assertEquals("abcS", slow.stored())
        assertNull(slow.slice.holdMeasuring)
        assertTrue(slow.trace().last(), slow.trace().last().contains("paths failed: timeout after 1500 ms ("))
    }

    @Test fun aNewerHoldStopsTheOlderMeasureAndAnEditStopsIt() {
        val app = App(listOf(a, b, c))
        app.scriptPaths(beside, append = 300.0, insert = 900.0)
        val other = at(c, 0.0, 1500.0)
        app.slice.holdOnMap(beside, 30.0)
        app.slice.holdOnMap(other, 30.0)
        assertEquals(other, app.slice.holdMeasuring)
        app.measure()
        assertNull(app.slice.holdMeasuring)
        // only the newer hold is decided (the scripted paths ask about it): its menu, nothing added, one decision traced
        assertEquals(other, app.slice.holdAddMenu!!.p)
        assertEquals(listOf(a, b, c), app.store.points)
        assertEquals(1, app.trace().count { it.startsWith("Hold on the map in doubt") })

        // an edit (through change) stops a running measure at its first line: the ring goes, nothing is applied
        val edit = App(listOf(a, b))
        edit.edit.change { add(c) } // one step to undo
        edit.scriptPaths(beside, append = 300.0, insert = 900.0)
        edit.slice.holdOnMap(beside, 30.0)
        edit.edit.undoStep()
        assertNull(edit.slice.holdMeasuring)
        edit.measure()
        assertEquals("ab", edit.stored())
        assertTrue(edit.trace().any { it.startsWith("Hold beside the plan: measure on the paths stopped (Undo)") })
        assertNull(edit.slice.holdAddMenu)

        // the view model's end stops a running measure: its result is never applied
        val ending = App(listOf(a, b, c))
        ending.scriptPaths(beside, append = 300.0, insert = 900.0)
        ending.slice.holdOnMap(beside, 30.0)
        ending.slice.close()
        ending.measure()
        assertEquals("abc", ending.stored())
        assertNull(ending.slice.holdAddMenu)
        assertEquals(emptyList<String>(), ending.routed)
    }

    // ---------------- the hold menu ----------------

    @Test fun theHoldMenuInsertsAStopOrAppendsTheNewGoalAndClosesItself() {
        val stop = App(listOf(a, b, c))
        stop.scriptPaths(beside, append = 500.0, insert = 550.0)
        stop.slice.holdOnMap(beside, 30.0); stop.measure()
        stop.slice.holdAsStop()
        assertNull(stop.slice.holdAddMenu)
        assertEquals(listOf(a, b, beside, c), stop.store.points) // never behind the goal
        assertEquals("Hold menu: Einfügen (plan 3 → 4)", stop.trace().last())
        stop.slice.holdAsStop() // no menu any more: nothing
        assertEquals(4, stop.store.points.size)

        val goal = App(listOf(a, b, c))
        goal.scriptPaths(beside, append = 500.0, insert = 550.0)
        goal.slice.holdOnMap(beside, 30.0); goal.measure()
        goal.slice.holdAsNewDestination()
        assertNull(goal.slice.holdAddMenu)
        assertEquals("abcS", goal.stored())
        assertEquals("Hold menu: Neues Ziel (appended)", goal.trace().last())
        goal.slice.holdAsNewDestination()
        assertEquals("abcS", goal.stored())
    }

    @Test fun duringATourANewGoalReplacesTheOldOneAndPlanningKeepsIt() {
        val app = App(listOf(a, b, c))
        app.slice.holdAddMenu = PlaceAddState.HoldAdd(beside, PlaceAddMenu.Menu(listOf(PlaceAddMenu.Item.NEW_DESTINATION)), 30.0)
        app.touring = true
        app.slice.holdAsNewDestination()
        assertEquals("abS", app.stored())
        assertEquals("Hold menu: Neues Ziel (replaces the goal on the tour)", app.trace().last())
        // an empty plan just gets it
        val empty = App()
        empty.touring = true
        empty.slice.holdAddMenu = PlaceAddState.HoldAdd(beside, PlaceAddMenu.Menu(listOf(PlaceAddMenu.Item.NEW_DESTINATION)), 30.0)
        empty.slice.holdAsNewDestination()
        assertEquals("S", empty.stored())
    }

    // ---------------- a tour, a round trip ----------------

    @Test fun aHoldOnATourOrARoundTripIsDecidedByTheZoneNotMeasured() {
        // a clear case on a tour (right beside a point): set at once
        val clear = App(listOf(a, b, c))
        clear.touring = true
        clear.slice.holdOnMap(at(b, 30.0, 0.0), 30.0)
        assertEquals(4, clear.store.points.size)
        assertEquals(0, clear.worker.size)
        assertEquals("New point by a hold: plan 3 → 4 (clear case STOP: set at once)", clear.trace().last())
        // a round trip is never measured either: its zone decides
        val loop = App(listOf(a, b, c, a))
        loop.slice.holdOnMap(beside, 30.0)
        assertEquals(0, loop.worker.size)
        assertNull(loop.slice.holdMeasuring)
        assertEquals(0, loop.routed.size)
        assertEquals("New point by a hold: plan 4 → 5 (clear case STOP: set at once)", loop.trace().last())
        // in doubt (the goal could be this): the menu asks, the likelier first, nothing added
        val ask = App(listOf(a, b, c))
        ask.touring = true
        ask.slice.holdOnMap(beside, 30.0)
        assertEquals("abc", ask.stored())
        assertEquals(0, ask.worker.size)
        assertEquals(beside, ask.slice.holdAddMenu!!.p)
        assertEquals(listOf(PlaceAddMenu.Item.NEW_DESTINATION, PlaceAddMenu.Item.ADD_STOP), ask.slice.holdAddMenu!!.menu.main)
        assertEquals(listOf("Hold on the map in doubt (zone GOAL_FIRST, 3 points)", "Hold on the map: asks NEW_DESTINATION / ADD_STOP"), ask.trace().takeLast(2))
    }

    @Test fun aPlaceAddedDuringATourGoesAmongThePointsAheadNeverBehindMe() {
        // the tour passed a and b and stands between b and c; a point beside a goes between a and b when planning
        val nearA = at(a, -50.0, -100.0)
        val planning = App(listOf(a, b, c))
        planning.routeLine = listOf(a, b, c)
        planning.slice.addPoint(nearA)
        assertEquals(listOf(a, nearA, b, c), planning.store.points)

        val tour = App(listOf(a, b, c))
        tour.touring = true
        tour.passed = setOf(0, 1)
        tour.position = at(b, 0.0, 700.0)
        tour.routeLine = listOf(a, b, c)
        tour.slice.addPoint(nearA)
        assertTrue("never before a passed point: ${tour.store.points.indexOf(nearA)}", tour.store.points.indexOf(nearA) >= 2)
        assertEquals(listOf(a, b), tour.store.points.take(2))
        assertEquals(4, tour.store.points.size)
        assertTrue("the passed points were asked for the plan before the add: ${tour.log}", "tourPassed(abc)" in tour.log)
        // the position and the followed line are read on the tour's branch, the plan's route line and the way there are not
        assertNull(tour.reads["routeLine"])
        assertNull(tour.reads["wayThereFrom"])
        assertTrue((tour.reads["myPosition"] ?: 0) >= 1)
        assertEquals(1, tour.reads["followLine"])
        assertEquals(1, planning.reads["wayThereFrom"])
        assertEquals(1, planning.reads["routeLine"])
        assertNull(planning.reads["followLine"])
        // "Einfügen" on a tour is the stop-only insertion among the points ahead: the goal stays last
        val stop = App(listOf(a, b, c))
        stop.touring = true; stop.passed = setOf(0, 1); stop.position = at(b, 0.0, 700.0)
        stop.slice.holdAddMenu = PlaceAddState.HoldAdd(nearA, PlaceAddMenu.Menu(listOf(PlaceAddMenu.Item.ADD_STOP)), 30.0)
        stop.slice.holdAsStop()
        assertEquals(c, stop.store.points.last())
        assertTrue(stop.store.points.indexOf(nearA) >= 2)
    }

    @Test fun theWayThereFromReachesTheDecisionSoAPointOnTheWayThereBecomesTheStart() {
        val app = App(listOf(a, b))
        val me = at(a, 0.0, -500.0)
        app.wayThere = me
        app.slice.addPoint(at(me, 0.0, 100.0)) // between me and a, on the way there: the new start (WaypointInsertion rule 4)
        assertEquals(3, app.store.points.size)
        assertEquals(at(me, 0.0, 100.0), app.store.points.first())
        assertTrue(app.trace().any { it.startsWith("New point on the way there") })
    }

    // ---------------- the first-point menu ----------------

    @Test fun theFirstPointMenuOpensOnAnEmptyPlanOnlyAndItsActionsAddTheStart() {
        val empty = App()
        empty.slice.openFirstPointMenu(a)
        assertEquals(a, empty.slice.firstPointMenu)
        assertEquals(listOf("closeMapOverlays(first-point menu opened) [stored=- pinTaken=-]"), empty.log)
        assertEquals("First-point menu opened", empty.trace().last())
        empty.slice.startHere(a)
        assertNull(empty.slice.firstPointMenu)
        assertEquals("a", empty.stored())

        val planned = App(listOf(a, b))
        planned.slice.openFirstPointMenu(c)
        assertNull(planned.slice.firstPointMenu)
        assertEquals(1, planned.log.size) // the overlays closed, nothing opened
    }

    @Test fun goHereStartsAtMyLocationAndNeedsAPosition() {
        val app = App()
        app.gps = a
        app.slice.firstPointMenu = b
        app.slice.routeFromMyLocation(c)
        assertNull(app.slice.firstPointMenu)
        assertEquals("ac", app.stored())
        assertEquals("ac", app.restarted())
        // without a fix the menu closes and nothing is added
        val none = App()
        none.slice.firstPointMenu = b
        none.slice.routeFromMyLocation(c)
        assertNull(none.slice.firstPointMenu)
        assertEquals(0, none.store.points.size)
        // a plan that is not empty any more: just a point
        val planned = App(listOf(a, b))
        planned.gps = a
        planned.slice.routeFromMyLocation(c)
        assertEquals(listOf(a, b, c), planned.store.points)
    }

    // ---------------- places: the card, the menu ----------------

    @Test fun aPlaceFromTheCardIsAStopPinnedNamedAndTheFoundPinGoesIntoThePlan() {
        val app = App(listOf(a, b))
        app.settle()
        app.pinOnMap = far
        app.slice.addPlace(far, AddRole.SMART)
        assertEquals("abM", app.stored())
        assertEquals("abM", app.restarted())
        assertTrue(farAt in app.plan.pinned)
        assertEquals(listOf(farAt to "Mechernich"), app.names)
        assertEquals(listOf(farAt to "place"), app.kinds)
        assertEquals(1, app.cardClosed)
        assertNull("the pin goes into the plan", app.pinOnMap)
        assertEquals(TakenPin(far, farAt), app.edit.pinTaken)
        // the order: the card closes, the name and kind are kept, the pin taken – then the ONE change, and only after it the pin
        // is remembered (change() forgets an older one: the recalc at the end of the edit still saw none)
        assertEquals(listOf(
            "closePlaceCard [stored=ab pinTaken=-]",
            "name(Mechernich) [stored=ab pinTaken=-]",
            "kind(place) [stored=ab pinTaken=-]",
            "takePin [stored=ab pinTaken=-]",
            "tourPassed(ab)",
            "edit.holdOff(plan changed) [stored=ab pinTaken=-]",
            "edit.recalc [stored=abM pinTaken=-]",
        ), app.log.filter { !it.startsWith("run.") })
        assertEquals("Place card: \"Mechernich\" added (smart, plan now 3 points)", app.trace().last())
        // a far place fits the new route once it is computed
        assertTrue(app.fit)
    }

    @Test fun aPlaceTheUserTakesBackWithCancelComesBackAsItsPin() {
        val app = App(listOf(a, b))
        app.pinOnMap = far
        app.slice.addPlace(far, AddRole.DESTINATION)
        assertEquals("abM", app.stored())
        assertNull(app.mark)
        app.causes.start() // the run the add started (its cause: the point edit)
        app.edit.cancelRun(NotRouted.STOPPED)
        assertEquals("ab", app.stored())
        assertEquals(far, app.mark)
        assertEquals("Cancel took back the add of \"Mechernich\": its pin is shown again", app.trace().last())
    }

    @Test fun thePlaceCardsRolesStartDestinationAndAnAlreadyPlannedPlace() {
        // as start: the first point, one change
        val start = App(listOf(a, b))
        start.settle()
        start.slice.addPlace(far, AddRole.START)
        assertEquals("Mab", start.stored())
        assertTrue("every role fits the new route", start.fit)
        assertTrue("the place is pinned for every role (the add by the card pins it itself)", farAt in start.plan.pinned)
        // as destination with a position on an empty plan: from my location
        val empty = App()
        empty.gps = a
        empty.slice.addPlace(far, AddRole.DESTINATION)
        assertEquals("aM", empty.stored())
        // without a position, or on a plan with points: just the end
        val noFix = App()
        noFix.slice.addPlace(far, AddRole.DESTINATION)
        assertEquals("M", noFix.stored())
        val planned = App(listOf(a, b))
        planned.gps = c
        planned.settle()
        planned.slice.addPlace(far, AddRole.DESTINATION)
        assertEquals("abM", planned.stored())
        assertTrue("every role fits the new route", planned.fit)
        // the position is read only for the destination role
        val smart = App(listOf(a, b))
        smart.slice.addPlace(far, AddRole.SMART)
        assertNull(smart.reads["gps"])
        assertEquals(1, planned.reads["gps"])
        // a place that already is a point: "As start" is Start here (drops what came before), "As destination" moves it to the
        // end, the smart add changes nothing; no pin remembered, no second point
        val inPlan = Place("photon:2", "Berg", "peak", c.lat, c.lon)
        val st = App(listOf(a, b, c)); st.slice.addPlace(inPlan, AddRole.START)
        assertEquals("cab", st.stored())
        assertEquals("the same word does the same as the point menu's Start here", PlanEdits.startHere(listOf(a, b, c), 2), st.store.points)
        val de = App(listOf(a, c, b)); de.slice.addPlace(inPlan, AddRole.DESTINATION)
        assertEquals("abc", de.stored())
        val sm = App(listOf(a, b, c)); sm.slice.addPlace(inPlan, AddRole.SMART)
        assertEquals("abc", sm.stored())
        assertFalse(sm.plan.canUndo)
        assertNull(sm.edit.pinTaken)
        // the card closes and the name is kept for a planned place too
        assertEquals(1, sm.cardClosed)
        assertEquals(1, sm.names.size)
    }

    @Test fun aPlaceMenuThatIsAlreadyAStopOpensItsPointMenuAndAddsNothing() {
        for (add in listOf<(App) -> Unit>({ it.slice.addPoiPoint(hut) }, { it.slice.addPoiAsStop(hut) }, { it.slice.poiAsNewDestination(hut) })) {
            val app = App(listOf(a, hutAt, c))
            add(app)
            assertEquals("aHc", app.stored())
            assertEquals(listOf(1 to "POI that is a stop"), app.pointMenus)
            assertFalse(app.plan.canUndo)
            assertTrue(app.names.isEmpty())
            // the found place's mark is asked BEFORE the menus close (closing them would end what the answer depends on)
            assertEquals(listOf("isCandidate", "closeMapOverlays(closed) [stored=aHc pinTaken=-]"), app.log.take(2))
        }
    }

    @Test fun aPlaceMenuAddsTheHutNamedKindedPinnedAndTheOwnersMenuRows() {
        // „Einfügen“: where it fits best on the route ahead, never behind the goal
        val stop = App(listOf(a, b, c))
        stop.slice.addPoiAsStop(hut)
        assertEquals(listOf(a, b, hutAt, c), stop.store.points)
        assertTrue(hutAt in stop.plan.pinned)
        assertEquals(listOf(hutAt to "Hütte"), stop.names)
        assertEquals(listOf(hutAt to "hut"), stop.kinds)
        assertEquals("Place menu: \"Hütte\" as a stop (plan 3 → 4 points)", stop.trace().last())
        // „Neues Ziel“ while planning: the old goal stays as the last stop – appended; on a tour it replaces the goal
        val goal = App(listOf(a, b, c))
        goal.pinOnMap = Place("photon:9", "Hütte", "hut", hut.lat, hut.lon)
        goal.slice.poiAsNewDestination(hut)
        assertEquals(listOf(a, b, c, hutAt), goal.store.points)
        assertNull("the found place's pin went into the plan", goal.pinOnMap)
        assertEquals(hutAt, goal.edit.pinTaken?.at)
        assertEquals("Place menu: \"Hütte\" as the new destination (plan 3 → 4 points)", goal.trace().last())
        val tour = App(listOf(a, b, c))
        tour.touring = true
        tour.slice.poiAsNewDestination(hut)
        assertEquals(listOf(a, b, hutAt), tour.store.points)
        // the order of one add: overlays closed, name, kind, pin taken, then the edit, then the pin remembered
        assertEquals(listOf("isCandidate", "closeMapOverlays(closed) [stored=abc pinTaken=-]", "name(Hütte) [stored=abc pinTaken=-]",
            "kind(hut) [stored=abc pinTaken=-]", "takePin [stored=abc pinTaken=-]"), goal.log.take(5))
        assertEquals("edit.recalc [stored=abcH pinTaken=-]", goal.log.last { it.startsWith("edit.") })
    }

    @Test fun aFoundPlacePinGoesIntoThePlanWithItsMenuAndTheCandidateIsFitted() {
        val app = App(listOf(a, b))
        app.settle()
        app.candidate = true
        app.pinOnMap = Place("photon:9", "Hütte", "hut", hut.lat, hut.lon)
        app.slice.addPoiPoint(hut)
        assertEquals(3, app.store.points.size)
        assertEquals(hutAt, app.edit.pinTaken?.at)
        assertTrue("a POI added through the menu is pinned (addPoint onPoi)", hutAt in app.plan.pinned)
        assertNull("the found place's pin went into the plan", app.pinOnMap)
        assertEquals("POI menu: \"Hütte\" added to the route (plan 2 → 3 points)", app.trace().last())
        assertTrue("the found place's add fits the new route", app.fit)
        // a plain POI of the map (not the found place) does not move the map
        val plain = App(listOf(a, b))
        plain.settle()
        plain.slice.addPoiPoint(hut)
        assertFalse(plain.fit)
        assertNull(plain.edit.pinTaken)
        assertEquals(listOf(hutAt to "Hütte"), plain.names)
        assertTrue("the plain add pins it too", hutAt in plain.plan.pinned)
        // the POI keeps its kind at once (was a gap since 2026-11-10: the symbol came only later by the 5 m index lookup)
        assertEquals("the POI's kind is kept with the point", listOf(hutAt to hut.kind), plain.kinds)
    }

    /** While a reroute runs the shown line belongs to older points: best fit only, and the hold's line distance is on the straight legs. */
    @Test fun whileARerouteRunsTheShownLineIsNotUsedForTheInsertionOrTheHoldsLineDistance() {
        val line = listOf(a, b, beside, c) // the shown route passes the spot: on the line it is a stop before the goal
        val idle = App(listOf(a, b, c))
        idle.routeLine = line
        idle.slice.addPoint(beside)
        assertEquals("abSc", idle.stored())
        val busy = App(listOf(a, b, c))
        busy.routeLine = line
        busy.run.routeEvent(RouteRun.Event.Recalc(listOf(a, b, c), Activity.HIKE, planMode = true, newPlan = false))
        assertTrue(busy.run.routing)
        busy.slice.addPoint(beside)
        assertEquals("best fit by geometry: the new goal", "abcS", busy.stored())
        // the hold: on the shown line it is set at once; while routing it is measured (straight legs are far away)
        val holdIdle = App(listOf(a, b, c))
        holdIdle.routeLine = line
        holdIdle.slice.holdOnMap(beside, 30.0)
        assertEquals("abSc", holdIdle.stored())
        assertEquals(0, holdIdle.worker.size)
        val holdBusy = App(listOf(a, b, c))
        holdBusy.routeLine = line
        holdBusy.run.routeEvent(RouteRun.Event.Recalc(listOf(a, b, c), Activity.HIKE, planMode = true, newPlan = false))
        holdBusy.slice.holdOnMap(beside, 30.0)
        assertEquals("abc", holdBusy.stored())
        assertEquals(beside, holdBusy.slice.holdMeasuring)
    }

    @Test fun goHereFromMyLocationOnAnEmptyPlanStartsAtMeAndNeedsAFix() {
        val app = App()
        app.gps = a
        app.pinOnMap = Place("photon:9", "Hütte", "hut", hut.lat, hut.lon)
        app.slice.poiFromMyLocation(hut)
        assertEquals("aH", app.stored())
        assertTrue(hutAt in app.plan.pinned)
        assertEquals(hutAt, app.edit.pinTaken?.at)
        assertEquals(listOf(hutAt to "hut"), app.kinds)
        assertEquals("Go here from my location: \"Hütte\" (plan 0 → 2 points)", app.trace().last())
        // no fix: the menu closed, nothing else
        val none = App()
        none.slice.poiFromMyLocation(hut)
        assertEquals(0, none.store.points.size)
        assertEquals(listOf("closeMapOverlays(closed) [stored=- pinTaken=-]"), none.log)
        // a plan with points: the plain add
        val planned = App(listOf(a, b))
        planned.gps = c
        planned.slice.poiFromMyLocation(hut)
        assertEquals(3, planned.store.points.size)
        assertEquals("POI menu: \"Hütte\" added to the route (plan 2 → 3 points)", planned.trace().last())
    }

    // ---------------- the candidate's dashed line ----------------

    @Test fun theCandidatesJoinLineHasItsGuards() {
        val app = App(listOf(a, c))
        app.routeLine = listOf(a, c)
        val mark = Place("photon:9", "Hütte", "hut", hut.lat, hut.lon)
        // no found place → none
        assertNull(app.slice.candidateJoin())
        app.mark = mark
        val join = app.slice.candidateJoin()
        assertNotNull(join)
        assertEquals(hutAt, join!!.first)
        // not in Plan / a shown track → none; fewer than two points → none; already a route point → none
        app.canEdit = false
        assertNull(app.slice.candidateJoin())
        app.canEdit = true
        assertNotNull(app.slice.candidateJoin())
        assertNull(App(listOf(a)).also { it.mark = mark }.slice.candidateJoin())
        assertNull(App(listOf(a, hutAt, c)).also { it.mark = mark }.slice.candidateJoin())
    }
}
