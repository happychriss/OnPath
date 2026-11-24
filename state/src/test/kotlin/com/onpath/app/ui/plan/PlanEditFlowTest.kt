package com.onpath.app.ui.plan

import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.PlanStatus
import com.onpath.app.domain.TakenPin
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.domain.plan.RunCause
import com.onpath.app.navigation.TourSplit
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.search.Place
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import com.onpath.app.ui.PlanSize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * Editing the plan's points (rebuild phase 2 piece 10) as flows of the REAL [PlanEditSlice] with the real [PlanDocument]
 * (its store re-read after every flow), the real [PlanRunSlice] and [RunCause]: add, insert on the line, move, delete (list,
 * menu, search card), reverse, round trip, Undo / Redo one step each and stored at once, Clear (never asks, keeps the plan
 * for Zuletzt first), and during a tour: the empty plan ends guidance and never the recording, no point ahead asks "route
 * back", one point left is where I stand. Fakes only at the edges: the host (the map's menus, the tour, the route run's
 * coroutine). The host records each call IN ORDER together with the plan as stored AT THAT MOMENT and the slice's state, so
 * the order of effects is what the test reads, not what it says. The tour's own code (the view model's `splitOf`,
 * `oneLeftGoesFromHere`, `endGuidanceIfPlanEmpty`) is a recorded call with a scripted answer here.
 *
 * Replaces the source pins on the view model's `change` / `cancelRun` (WholeRouteTest.firstRouteIsFitted: the first route
 * asks the fit; FarDestinationTest.cancelOfARunShowsThePinOfTheAddItTookBack: Cancel brings the pin back, a newer edit forgets it).
 */
class PlanEditFlowTest {
    @get:Rule val composeState = OwnComposeSnapshot()
    @get:Rule val problems = ProblemsRule()

    private val a = GeoPoint(46.60, 11.10)
    private val b = GeoPoint(46.65, 11.12)
    private val c = GeoPoint(46.70, 11.15)
    private val d = GeoPoint(46.75, 11.18)
    private val letters = mapOf(a to "a", b to "b", c to "c", d to "d")
    private fun ids(l: List<GeoPoint>?) = l?.joinToString("") { letters[it] ?: "?" } ?: "-"
    private val far = Place("photon:1", "Mechernich", "place", 50.5947, 6.6517, sub = "town")

    private class MemoryPlanStore : PlanStore {
        override var points: List<GeoPoint> = emptyList()
        override var pinned: Set<GeoPoint> = emptySet()
        override var savedName: String? = null
        override var savedId: Long = -1L
        override var savedPoints: List<GeoPoint>? = null
    }

    private inner class App(initial: List<GeoPoint> = emptyList(), io: CoroutineDispatcher = Dispatchers.Unconfined) {
        val store = MemoryPlanStore().also { it.points = initial }
        val plan = PlanDocument(store)
        val causes = RunCause()
        /** What the host was asked, in order: the call, then the plan as stored and the slice's state at that moment. */
        val log = ArrayList<String>()
        lateinit var slice: PlanEditSlice
        lateinit var run: PlanRunSlice
        // the app's facts the slice reads through the host
        var touring = false
        var followLine: List<GeoPoint>? = null
        var followIsTrack = false
        var followFromPlan = false
        var position: GeoPoint? = null
        var split: TourSplit.Outcome? = null
        var menuIndex: Int? = null
        var route: List<GeoPoint>? = null
        var mark: Place? = null
        var spot: GeoPoint? = null
        var named: Map<GeoPoint, String> = emptyMap()
        var stopNames: Map<GeoPoint, String> = emptyMap()
        var indexFails = false
        var nameCalls = 0
        var tourReads = 0
        var guidanceEnded = false
        var restoredActivity: PlanStatus.Cause.ActivityChange? = null

        private fun st() = "[stored=${ids(store.points)} ask=${slice.routeBackAsk} undo=${plan.canUndo} redo=${plan.canRedo} " +
            "next=${causes.next::class.simpleName} edited=${run.routeRun.editPending} fit=${run.routeRun.fitNext}]"

        val host = object : PlanEditHost {
            override fun cancelHoldMeasure(why: String) { log += "hold($why) ${st()}" }
            override fun stopFollowingWhenMapTurns() { log += "stopFollow ${st()}" }
            override fun closePointMenu(why: String) { log += "closePointMenu($why) ${st()}" }
            override fun clearFirstPointMenu() { log += "firstMenuOff" }
            override fun clearHoldAddMenu() { log += "holdAddOff" }
            override fun clearHighlight() { log += "highlightOff" }
            override fun removePin(why: String) { log += "removePin($why) ${st()}" }
            override fun menuAction(what: String): Int? { log += "menuAction($what)"; return menuIndex }
            override fun setRoutingSpot(spot: GeoPoint?) { this@App.spot = spot; log += "spot=${letters[spot] ?: "-"} ${st()}" }
            override fun loadMapsForNewPoints(before: List<GeoPoint>) { log += "maps(before=${ids(before)}) ${st()}" }
            override fun keepAsDraft(why: String) { log += "draft($why) ${st()}" }
            // the route line goes with the plan: a read after the points went finds none
            override fun routeLine(): List<GeoPoint>? = route?.takeIf { store.points.isNotEmpty() }
            override fun recalc() { log += "recalc ${st()}" }
            override fun restoreActivity(c: PlanStatus.Cause.ActivityChange) { restoredActivity = c; log += "restoreActivity ${st()}" }
            override fun planChanged() { log += "planChanged ${st()}" }
            override fun myPosition(): GeoPoint? = position
            override fun touring(): Boolean { tourReads++; return touring }
            override fun following(): Boolean { tourReads++; return followLine != null }
            override fun followIsTrack(): Boolean { tourReads++; return followIsTrack }
            override fun followsLine(line: List<GeoPoint>): Boolean = followLine == line
            override fun followFromPlan(): Boolean = followFromPlan
            override fun splitOf(before: List<GeoPoint>, after: List<GeoPoint>): TourSplit.Outcome? {
                log += "splitOf(${ids(before)}→${ids(after)}) ${st()}"; return split
            }
            override fun sayNoDestination() { log += "say(noDestination) ${st()}" }
            override fun oneLeftGoesFromHere(before: List<GeoPoint>, mayEmpty: Boolean) { log += "oneLeft(before=${ids(before)} mayEmpty=$mayEmpty) ${st()}" }
            override fun endGuidanceIfPlanEmpty() { log += "endGuidanceIfEmpty ${st()}" }
            override fun clearFollow() { followLine = null; log += "clearFollow ${st()}" }
            override fun guidanceEndedByEmptyPlan() { guidanceEnded = true; log += "guidanceEnded" }
            override fun stopName(p: GeoPoint): String? = stopNames[p]
            override suspend fun pointNames(): PointNames {
                nameCalls++
                if (indexFails) error("no index")
                return PointNames { p -> named[p] }
            }
            override var searchMark: Place?
                get() = mark
                set(v) { mark = v; log += "searchMark=${v?.name}" }
        }

        init {
            run = PlanRunSlice(causes, object : PlanRunHost {
                override fun points() = plan.points.toList()
                override fun clearRoute() { log += "run.clearRoute" }
                override fun noCorridor() { log += "run.noCorridor" }
                override fun loadDeferredMaps(before: List<GeoPoint>) = Unit
                override fun recalc() { log += "run.recalc" }
                override fun cancelRun(why: NotRouted) { log += "run.cancelRun($why)"; slice.cancelRun(why) }
            })
            slice = PlanEditSlice(plan, run, causes, host, CoroutineScope(Job() + Dispatchers.Unconfined), io)
        }

        /** A fresh start of the app on what the store holds. */
        fun restarted() = App(store.points)

        fun points() = ids(plan.points)
        fun stored() = ids(store.points)
        fun add(p: GeoPoint) = slice.change { add(p) }
        /** The route run is through with the last edit (as after a finished run): no edit pending, no fit asked, the cause handed on. */
        fun settle() {
            causes.start()
            run.routeEvent(RouteRun.Event.Recalc(listOf(a), Activity.HIKE, planMode = true, newPlan = false))
            run.routeEvent(RouteRun.Event.Unrouted(NotRouted.STOPPED))
            log.clear()
        }
        /** What `recalc` does when a run starts for the plan now. */
        fun startRun() {
            causes.start()
            run.perform(run.routeEvent(RouteRun.Event.Recalc(plan.points.toList(), Activity.HIKE, planMode = true, newPlan = false)))
        }
    }

    // ---------------- C1 / C2: add, insert, move ----------------

    @Test fun theFirstTwoPointsAreStoredAtOnceAndTheFirstRouteAsksTheFit() {
        val app = App()
        app.add(a)
        assertEquals("a", app.stored())
        assertFalse(app.run.routeRun.fitNext) // one point: no route to fit
        app.settle()
        app.add(b)
        assertEquals("ab", app.stored())
        assertEquals("ab", ids(app.restarted().plan.points)) // survives the process
        assertTrue(app.run.routeRun.fitNext) // the first route is fitted once computed (WholeRoute.firstRoute 1 → 2)
        assertEquals(b, app.spot)
        // the order of one edit: menus / following first, the stored plan before the maps, the tour before the run
        assertEquals(listOf(
            "hold(plan changed) [stored=a ask=false undo=true redo=false next=Other edited=false fit=false]",
            "stopFollow [stored=a ask=false undo=true redo=false next=Other edited=false fit=false]",
            "spot=b [stored=ab ask=false undo=true redo=false next=Other edited=false fit=true]",
            "maps(before=a) [stored=ab ask=false undo=true redo=false next=Other edited=false fit=true]",
            "splitOf(a→ab) [stored=ab ask=false undo=true redo=false next=Other edited=true fit=true]",
            "oneLeft(before=a mayEmpty=true) [stored=ab ask=false undo=true redo=false next=Other edited=true fit=true]",
            "planChanged [stored=ab ask=false undo=true redo=false next=Other edited=true fit=true]",
            "endGuidanceIfEmpty [stored=ab ask=false undo=true redo=false next=Other edited=true fit=true]",
            "recalc [stored=ab ask=false undo=true redo=false next=PointEdit edited=true fit=true]",
        ), app.log)
    }

    @Test fun aLaterEditNeverAsksTheFitAgain() {
        val app = App(listOf(a, b))
        app.add(c)
        assertFalse(app.run.routeRun.fitNext)
        assertEquals("abc", app.stored())
    }

    @Test fun anEditForgetsThePinOfAnEarlierAddAndTheAddSetsItAfterwards() {
        val app = App(listOf(a))
        app.slice.pinTaken = TakenPin(far, b)
        app.add(b)
        assertNull(app.slice.pinTaken) // only the add itself can bring its pin back: it sets it after change()
        app.slice.pinTaken = TakenPin(far, b)
        assertEquals(b, app.slice.pinTaken?.at)
    }

    @Test fun movingARowIsOneStoredStepAndUndoBringsItBack() {
        val app = App(listOf(a, b, c))
        app.slice.movePointInList(0, 2)
        assertEquals("bca", app.stored())
        assertTrue(app.plan.canUndo)
        assertEquals(listOf("Points list: point 1 moved to 3 (plan 3 points)"), app.problemsPlan())
        // (a row dropped on itself never reaches here: PointsSection calls onMove only for to != row; calling it anyway
        // empties the plan today because PlanPoints.move hands back the live list – found in this piece, reported, not changed)
        app.slice.undoStep()
        assertEquals("abc", app.stored())
        assertFalse(app.plan.canUndo)
    }

    @Test fun aPointDraggedOnTheMapIsOneStoredStepAndUndoBringsItBack() {
        val app = App(listOf(a, b, c))
        app.slice.dragPreview = 1 to d
        assertEquals("the preview alone changes nothing", "abc", app.stored())
        app.slice.movePointTo(1, d)
        assertNull(app.slice.dragPreview)
        assertEquals("adc", app.stored())
        assertTrue(app.plan.canUndo)
        assertEquals(listOf("Point 2 dragged on the map to a new place (plan 3 points)"), app.problemsPlan())
        app.slice.undoStep()
        assertEquals("abc", app.stored())
    }

    @Test fun aDragLetGoOnTheSameSpotOrAStalePointChangesNothing() {
        val app = App(listOf(a, b, c))
        app.slice.movePointTo(1, b)
        app.slice.movePointTo(7, d)
        assertEquals("abc", app.stored())
        assertFalse(app.plan.canUndo)
        assertEquals(emptyList<String>(), app.log)
    }

    @Test fun aRowDroppedOnItselfOrAStaleRowChangesNothing() {
        // a stale row index (the plan shrank under the drag) must never empty the plan: PlanPoints.move hands back the plan itself
        val app = App(listOf(a, b, c))
        app.slice.movePointInList(1, 1)
        app.slice.movePointInList(7, 0)
        assertEquals("abc", app.stored())
        assertEquals("abc", app.points())
        assertFalse(app.plan.canUndo)
        assertEquals(emptyList<String>(), app.log)
        // during a tour the guidance is not ended by it either
        app.touring = true; app.followLine = listOf(a, c)
        app.slice.movePointInList(2, 2)
        assertEquals(emptyList<String>(), app.log)
        assertFalse(app.guidanceEnded)
    }

    @Test fun makeDestinationMovesARowToTheEndAndTheLastRowIsLeftAlone() {
        val app = App(listOf(a, b, c))
        app.slice.makeDestination(0)
        assertEquals("bca", app.stored())
        app.log.clear()
        app.slice.makeDestination(2) // already the last
        app.slice.makeDestination(5) // not a row
        assertEquals(emptyList<String>(), app.log)
        assertEquals("bca", app.stored())
    }

    // ---------------- C9: delete from the list, the menu, the search card ----------------

    @Test fun deletingFromTheListIsStoredAtOnceAndUndoBringsItBack() {
        val app = App(listOf(a, b, c))
        app.slice.deletePointFromList(1)
        assertEquals("ac", app.stored())
        assertEquals("ac", ids(app.restarted().plan.points))
        assertEquals(listOf("Points list: point 2 deleted (plan 3 → 2)"), app.problemsPlan())
        app.slice.undoStep()
        assertEquals("abc", app.stored())
        app.slice.redoStep()
        assertEquals("ac", app.stored())
        assertFalse(app.plan.canRedo)
        assertTrue(app.plan.canUndo)
    }

    @Test fun aRowThatIsNotThereDeletesNothingAndAsksNothing() {
        val app = App(listOf(a, b))
        app.slice.deletePointFromList(5)
        app.slice.removePoint(-1)
        assertEquals("ab", app.stored())
        assertFalse(app.plan.canUndo)
        assertEquals(emptyList<String>(), app.log)
    }

    @Test fun aRoundTripStaysClosedWhenItsStartIsDeleted() {
        val app = App(listOf(a, b, c, a))
        app.slice.deletePointFromList(0)
        assertEquals("bcb", app.stored())
        app.slice.undoStep()
        app.slice.removePoint(3) // the closing point is the loop's start: the same edit
        assertEquals("bcb", app.stored()) // the loop closes again at the next point
    }

    @Test fun theMenuDeleteClosesTheMenuFirstAndThenTheEditRuns() {
        val app = App(listOf(a, b, c))
        app.menuIndex = 1
        app.slice.deletePointFromMenu()
        assertEquals("ac", app.stored())
        assertEquals("menuAction(Delete)", app.log.first())
        assertEquals(1, app.log.count { it.startsWith("recalc") })
        assertEquals("Point 2 deleted: plan 3 → 2, route recalculation not needed", app.problemsPlan().single())
    }

    @Test fun theMenuDeleteWithoutAPointDoesNothing() {
        val app = App(listOf(a, b, c))
        app.menuIndex = null // the point is gone (the plan changed under the open menu)
        app.slice.deletePointFromMenu()
        assertEquals("abc", app.stored())
        assertEquals(listOf("menuAction(Delete)"), app.log)
        assertFalse(app.plan.canUndo)
    }

    @Test fun theMenuDeleteSaysWhenARouteIsCalculatedAndWhenAnotherMarkerStaysNear() {
        val bNear = GeoPoint(46.65005, 11.12) // 5 m from b: a marker that looks like "the point stayed"
        val app = App(listOf(a, b, bNear))
        app.startRun()
        assertTrue(app.run.routing)
        app.menuIndex = 2
        app.slice.deletePointFromMenu()
        assertTrue(app.problemsPlan().single(), Regex("Point 3 deleted: plan 3 → 2, route recalculation started, point 2 is only \\d m away").matches(app.problemsPlan().single()))
    }

    // ---------------- reverse, round trip ----------------

    @Test fun reverseIsOneStepAndNothingWhenThePlanReadsTheSameBackwards() {
        val app = App(listOf(a, b, c))
        assertTrue(app.slice.canReverse())
        app.slice.reverseRoute()
        assertEquals("cba", app.stored())
        assertEquals(listOf("Reverse: plan 3 points"), app.problemsPlan())
        app.slice.undoStep()
        assertEquals("abc", app.stored())

        val same = App(listOf(a, b, a)) // an out-and-back over one point
        assertFalse(same.slice.canReverse())
        same.slice.reverseRoute()
        assertEquals("aba", same.stored())
        assertFalse(same.plan.canUndo)
        assertEquals(emptyList<String>(), same.log)
        assertEquals(listOf("Reverse: nothing to reverse (plan 3 points, the same backwards)"), same.problemsPlan().takeLast(1))
    }

    @Test fun roundTripSwitchesOnAndOffInOneStepEach() {
        val app = App(listOf(a, b, c))
        assertTrue(app.slice.canRoundTrip())
        app.slice.roundTrip()
        assertEquals("abca", app.stored())
        assertEquals("Round trip on: plan 3 → 4 points", app.problemsPlan().last())
        app.slice.roundTrip()
        assertEquals("abc", app.stored())
        assertEquals("Round trip off: plan 4 → 3 points", app.problemsPlan().last())
        app.slice.undoStep()
        assertEquals("abca", app.stored())
        app.slice.undoStep()
        assertEquals("abc", app.stored())
        assertFalse(app.plan.canUndo)
    }

    @Test fun roundTripOnAPlanOfOnePointChangesNothing() {
        val app = App(listOf(a))
        assertFalse(app.slice.canRoundTrip())
        app.slice.roundTrip()
        assertEquals("a", app.stored())
        assertFalse(app.plan.canUndo)
        assertEquals(emptyList<String>(), app.log)
        assertEquals(listOf("Round trip: nothing to change (plan 1 points)"), app.problemsPlan())
    }

    // ---------------- Undo / Redo ----------------

    @Test fun undoAndRedoGoOneStepEachAndAreStoredAtOnce() {
        val app = App()
        app.add(a); app.add(b); app.add(c)
        app.settle()
        app.slice.undoStep()
        assertEquals("ab", app.stored())
        assertEquals("ab", ids(app.restarted().plan.points))
        assertTrue(app.plan.canRedo)
        // the tour is asked BEFORE the plan is replaced (it needs the plan before), the run after everything
        val tail = "ask=false undo=true redo=true"
        assertEquals(listOf(
            "spot=-",
            "splitOf(abc→ab) [stored=abc $tail next=Other edited=false fit=false]",
            "oneLeft(before=abc mayEmpty=false) [stored=ab $tail next=Other edited=false fit=false]",
            "closePointMenu(undo / redo) [stored=ab $tail next=Other edited=false fit=false]",
            "firstMenuOff", "holdAddOff",
            "hold(Undo) [stored=ab $tail next=Other edited=false fit=false]",
            "planChanged [stored=ab $tail next=Other edited=true fit=false]",
            "endGuidanceIfEmpty [stored=ab $tail next=Other edited=true fit=false]",
            "recalc [stored=ab $tail next=Undo edited=true fit=false]",
        ), app.log.map { if (it.startsWith("spot=")) "spot=-" else it })
        app.log.clear()
        app.slice.redoStep()
        assertEquals("abc", app.stored())
        assertTrue(app.log.last().contains("next=Redo"))
        assertFalse(app.plan.canRedo)
    }

    @Test fun undoOfADeleteShowsTheSpinnerAtThePointThatComesBack() {
        val app = App(listOf(a, b, c))
        app.slice.deletePointFromList(1)
        assertNull(app.spot) // a delete adds no point
        app.slice.undoStep()
        assertEquals(b, app.spot) // RoutingSpot.of(before, prev): the point the step brings back
        app.slice.redoStep()
        assertNull(app.spot)
    }

    @Test fun anEditForgetsPinnedMarksOfPointsNeitherPlannedNorReachableByUndo() {
        val app = App(listOf(a, b))
        app.plan.pin(d) // a mark of a point that is nowhere
        app.plan.pin(a)
        app.add(c)
        assertEquals(setOf(a), app.plan.pinned) // d went, the planned a stays
        assertEquals(setOf(a), app.store.pinned) // and it is stored
    }

    @Test fun aFollowedTrackThatIsThePlansOwnLineGoesWithTheClearedPlanEvenWhenNotFromThePlan() {
        val line = listOf(a, c)
        val app = App(listOf(a, b, c))
        app.route = line; app.followLine = line; app.followIsTrack = true; app.followFromPlan = false
        app.slice.clearPlan() // the line read BEFORE the plan went is the plan's own line
        assertNull(app.followLine)
        assertEquals(1, app.log.count { it.startsWith("clearFollow") })
    }

    @Test fun aSecondLookupWhileTheFirstIsStillRunningStartsNothing() {
        val held = ArrayList<Runnable>()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { held += block }
        }
        val app = App(listOf(a, b), io)
        app.named = mapOf(a to "Algund", b to "Meran")
        app.slice.refreshPointNames()
        assertEquals(1, app.nameCalls)
        app.slice.refreshPointNames() // nothing is named yet, but the first job is on its way
        assertEquals(1, app.nameCalls)
        assertEquals(emptyMap<GeoPoint, String>(), app.slice.nearbyNames)
        while (held.isNotEmpty()) held.removeAt(0).run()
        assertEquals(mapOf(a to "Algund", b to "Meran"), app.slice.nearbyNames)
    }

    @Test fun nothingToUndoOrRedoTouchesNothing() {
        val app = App(listOf(a, b))
        app.slice.undoStep()
        app.slice.redoStep()
        assertEquals("ab", app.stored())
        assertEquals(emptyList<String>(), app.log)
        assertEquals(listOf("Undo: nothing to undo", "Redo: nothing to redo"), app.problemsPlan())
        assertEquals(RunCause().next, app.causes.next) // no run was asked for
    }

    @Test fun theUsersOwnUndoForgetsAPinThatACancelWouldBringBack() {
        val app = App(listOf(a))
        app.add(b)
        app.slice.pinTaken = TakenPin(far, b)
        app.slice.undoStep()
        assertNull(app.slice.pinTaken)
    }

    // ---------------- Clear ----------------

    @Test fun clearKeepsThePlanForZuletztFirstThenEmptiesInOneUndoStepAndNeverAsks() {
        val app = App(listOf(a, b, c))
        app.slice.planSize = PlanSize.COLLAPSED
        app.slice.clearPlan()
        assertEquals("", app.stored())
        assertEquals("", ids(app.restarted().plan.points))
        val draft = app.log.first { it.startsWith("draft(") }
        assertEquals("draft(cleared) [stored=abc ask=false undo=false redo=false next=Other edited=false fit=false]", draft) // the plan was still there
        val order = app.log.map { it.substringBefore("(").substringBefore(" ") }
        assertTrue(order.indexOf("draft") < order.indexOf("hold"))
        assertTrue(order.indexOf("recalc") < order.indexOf("closePointMenu"))
        assertTrue(order.indexOf("closePointMenu") < order.indexOf("removePin"))
        assertTrue(app.log.any { it.startsWith("removePin(plan cleared) [stored= ") }) // the pin goes after the plan went, with this reason
        assertTrue(app.log.any { it.startsWith("closePointMenu(plan cleared) ") })
        assertEquals(listOf("firstMenuOff", "holdAddOff", "highlightOff"), app.log.filter { it in setOf("firstMenuOff", "holdAddOff", "highlightOff") })
        assertEquals(PlanSize.MINI, app.slice.planSize) // the empty sheet says how to start: not rolled up
        assertTrue(app.plan.canUndo)
        assertEquals(listOf("Clear route: plan 3 → 0 points"), app.problemsPlan())
        app.slice.undoStep()
        assertEquals("abc", app.stored()) // one step brings it back
    }

    @Test fun clearOnAnEmptyPlanRunsTheRouteOnceAndMakesNoUndoStep() {
        val app = App()
        app.slice.clearPlan()
        assertFalse(app.plan.canUndo)
        assertEquals(1, app.log.count { it.startsWith("recalc") })
        assertEquals(0, app.log.count { it.startsWith("hold(") })
        assertEquals(listOf("Clear route: plan 0 → 0 points"), app.problemsPlan())
    }

    @Test fun clearLeavesAnExpandedSheetAlone() {
        val app = App(listOf(a, b))
        app.slice.planSize = PlanSize.FULL
        app.slice.clearPlan()
        assertEquals(PlanSize.FULL, app.slice.planSize)
    }

    @Test fun withoutATourClearDropsTheFollowedCopyOfThePlanButNotAShownTrack() {
        val line = listOf(a, c)
        val app = App(listOf(a, b, c))
        app.route = line; app.followLine = line; app.followFromPlan = true
        app.slice.clearPlan()
        assertNull(app.followLine) // the blue line went with the plan
        assertFalse(app.guidanceEnded) // and it is no tour: nothing to end
        assertEquals(1, app.log.count { it.startsWith("clearFollow") })

        val track = App(listOf(a, b, c))
        track.route = line; track.followLine = listOf(d, c); track.followIsTrack = true
        track.slice.clearPlan()
        assertEquals(listOf(d, c), track.followLine) // a shown GPX track keeps its own line
        assertEquals(0, track.log.count { it.startsWith("clearFollow") })
    }

    /**
     * From ClearPlanDuringTourTest (class doc, word for word; the rules are in PlanGuidanceTest):
     * ✕ Neu planen during a tour (owner 2026-11-05: he saw "Navigate without a route" – is that what the app does?): every
     * guidance ends, the recording never stops; owner 2026-11-06: no question any more, the plan goes under Zuletzt, Undo
     * brings it back (E2E CoreFieldE2ETest.clearingThePlanDuringATourAsksNothing). The empty sheet ("Die Tour läuft weiter")
     * and Navigate's free tour line are in the screenshot frames `planEmptyDuringTour` / `navigateFreeTour`.
     * From PlanGuidanceTest.clearRouteDuringATourEndsTheGuidanceToo: Sheet ⋯ → Clear route is allowed during a tour (owner) and goes through the same plan change.
     * (the view model's path (clear → the empty-plan rule): E2E CoreFieldE2ETest.clearingThePlanDuringATourAsksNothing)
     * From TourEditTest: the recording goes on – clearPlan never stops the recording itself (its host has no way to stop one – PlanEditHost).
     */
    @Test fun clearDuringATourEndsTheGuidanceAndNeverTheRecording() {
        val app = App(listOf(a, b, c))
        app.touring = true; app.followLine = listOf(a, c); app.followIsTrack = true // even a followed track
        app.slice.clearPlan()
        assertEquals("", app.stored())
        assertTrue(app.guidanceEnded)
        assertNull(app.followLine)
        assertTrue(app.touring) // the recording goes on: only the host's follow is touched, nothing else exists to stop it
        val order = app.log.map { it.substringBefore("(").substringBefore(" ") }
        assertTrue(order.indexOf("recalc") < order.indexOf("clearFollow"))
        assertEquals("guidanceEnded", app.log.last())
        assertEquals(listOf("Clear route: plan 3 → 0 points (tour running)", "Plan cleared during the tour: route guidance ended, recording continues"), app.problemsPlan())
    }

    @Test fun clearDuringATourWithoutGuidanceEndsNothing() {
        val app = App(listOf(a, b))
        app.touring = true
        app.slice.clearPlan()
        assertFalse(app.guidanceEnded)
        assertEquals(0, app.log.count { it.startsWith("clearFollow") })
    }

    // ---------------- during a tour ----------------

    @Test fun deletingTheGoalDuringATourAsksRouteBackAfterSayingNoDestinationAndBeforeTheOneLeftRule() {
        val app = App(listOf(a, b))
        app.touring = true; app.followLine = listOf(a, b); app.position = d
        app.slice.deletePointFromList(1)
        assertTrue(app.slice.routeBackAsk)
        val say = app.log.indexOfFirst { it.startsWith("say(") }
        val one = app.log.indexOfFirst { it.startsWith("oneLeft(") }
        assertTrue(say in 0 until one)
        assertEquals("say(noDestination) [stored=a ask=true undo=true redo=false next=Other edited=true fit=false]", app.log[say])
        assertEquals(listOf("Edit during the tour left no point ahead: asking \"No destination ahead\""), app.problemsPlan().takeLast(1))
        assertEquals("a", app.stored()) // the plan is the start alone while the question is open
    }

    @Test fun theSplitSayingNothingAheadAsksWithoutReadingTheTourFurther() {
        val app = App(listOf(a, b, c))
        app.split = TourSplit.Outcome.NothingAhead
        app.position = d
        app.slice.deletePointFromList(2)
        assertTrue(app.slice.routeBackAsk)
        assertEquals(0, app.tourReads) // lazy: the rule behind the split is not asked
    }

    @Test fun noQuestionWithoutATour() {
        val app = App(listOf(a, b))
        app.position = d
        app.slice.deletePointFromList(1)
        assertFalse(app.slice.routeBackAsk)
        assertEquals(0, app.log.count { it.startsWith("say(") })
        assertEquals(3, app.tourReads) // touring is asked first: not recording ends the rule (all three reads happen: arguments)
    }

    @Test fun theOneLeftWhereIStandAsksNothingAndReadsNoTour() {
        val app = App(listOf(a, b))
        app.touring = true; app.followLine = listOf(a, b)
        app.position = a // standing on the point that is left
        app.split = TourSplit.Outcome.NothingAhead
        app.slice.deletePointFromList(1)
        assertFalse(app.slice.routeBackAsk)
        assertEquals(0, app.tourReads) // the one left is here: nothing to ask, nothing read
        assertEquals(0, app.log.count { it.startsWith("say(") })
    }

    @Test fun undoDuringATourAsksNothingAheadOnlyWhenThePlanLeftIsNotWhereIStand() {
        val app = App(listOf(a, b, c))
        app.slice.deletePointFromList(2)
        app.split = TourSplit.Outcome.NothingAhead
        app.position = d
        app.slice.undoStep()
        assertTrue(app.slice.routeBackAsk)
        // standing on the one point that is left: asks nothing
        val here = App(listOf(a, b))
        here.slice.deletePointFromList(1)
        here.split = TourSplit.Outcome.NothingAhead
        here.position = a
        here.slice.redoStep()
        assertFalse(here.slice.routeBackAsk)
    }

    @Test fun anUndoThatIsNotNothingAheadClosesAnOpenQuestion() {
        val app = App(listOf(a, b))
        app.slice.routeBackAsk = true // the open question
        app.slice.deletePointFromList(1)
        app.slice.undoStep()
        assertFalse(app.slice.routeBackAsk)
    }

    @Test fun anEmptyPlanEndsGuidanceThroughTheHookAfterTheStoredEmptyPlan() {
        val app = App(listOf(a))
        app.touring = true; app.followLine = listOf(a, b)
        app.slice.deletePointFromList(0)
        assertEquals("", app.stored())
        val hook = app.log.single { it.startsWith("endGuidanceIfEmpty") }
        assertTrue(hook, hook.contains("stored= ")) // the plan was stored empty when the tour was told
        assertTrue(hook, hook.contains("edited=true")) // and the route run knew of the edit
        assertTrue(app.log.indexOf(hook) < app.log.indexOfFirst { it.startsWith("recalc") })
        app.slice.undoStep() // Undo brings the point back
        assertEquals("a", app.stored())
    }

    // ---------------- Cancel of a run takes the edit back ----------------

    @Test fun cancelOfARunForAPointEditUndoesThatEditAfterTheRunIsStopped() {
        val app = App(listOf(a, b))
        app.add(c)
        app.startRun()
        app.log.clear()
        app.run.cancelRouting()
        assertEquals("ab", app.stored()) // the edit is taken back
        assertTrue(app.plan.canRedo)
        assertEquals("run.cancelRun(STOPPED)", app.log.first { it.startsWith("run.cancelRun") }) // from the run's effects
        val leave = app.log.indexOf("run.clearRoute")
        val undo = app.log.indexOfFirst { it.startsWith("splitOf(abc→ab)") }
        assertTrue("the run ends before the step", leave in 0 until undo)
        assertEquals("Cancel (STOPPED) of a run for PointEdit: undo", app.problemsPlan("routing").last())
    }

    @Test fun cancelOfARunForUndoBringsTheStepBackAndOtherCausesOnlyStop() {
        val app = App(listOf(a, b))
        app.add(c)
        app.slice.undoStep()
        app.startRun()
        app.run.cancelRouting()
        assertEquals("abc", app.stored())

        val other = App(listOf(a, b)) // a plan restored at the start
        other.startRun()
        other.log.clear()
        other.slice.pinTaken = TakenPin(far, b)
        other.run.cancelRouting()
        assertNull(other.slice.pinTaken) // taken once by this Cancel, whatever it did
        assertEquals("ab", other.stored())
        assertFalse(other.plan.canRedo)
        assertEquals(0, other.log.count { it.startsWith("splitOf") })
        assertEquals("Cancel (STOPPED) of a run for Other: keep_points", other.problemsPlan("routing").last())
    }

    @Test fun cancelOfAnActivityChangeRestoresTheActivityAndLeavesThePoints() {
        val app = App(listOf(a, b))
        val change = PlanStatus.Cause.ActivityChange(Activity.EBIKE, 2)
        app.causes.activity(change)
        app.startRun()
        app.slice.pinTaken = TakenPin(far, b)
        app.run.cancelRouting()
        assertEquals(change, app.restoredActivity)
        assertEquals("ab", app.stored())
        assertNull(app.slice.pinTaken) // taken once by this Cancel, whatever it did
    }

    @Test fun cancelBringsTheFoundPlacesPinBackWhenTheAddIsTakenBackAndNewerThingsWin() {
        val app = App(listOf(a))
        app.add(b)
        app.slice.pinTaken = TakenPin(far, b) // what the add of a found place sets after change()
        app.startRun()
        app.run.cancelRouting()
        assertEquals("a", app.stored())
        assertEquals(far, app.mark)
        assertNull(app.slice.pinTaken) // given out once
        assertEquals("Cancel took back the add of \"Mechernich\": its pin is shown again", app.problemsPlan("plan").last())

        // a newer search pick wins
        val newer = App(listOf(a))
        newer.add(b); newer.slice.pinTaken = TakenPin(far, b); newer.mark = Place("photon:2", "Euskirchen", "place", 50.66, 6.79)
        newer.startRun(); newer.run.cancelRouting()
        assertEquals("Euskirchen", newer.mark?.name)

        // a newer edit forgets it: the later add did not come from the place
        val edited = App(listOf(a))
        edited.add(b); edited.slice.pinTaken = TakenPin(far, b); edited.add(c)
        edited.startRun(); edited.run.cancelRouting()
        assertNull(edited.mark)

        // the point stays in the plan: nothing to show
        val stays = App(listOf(a))
        stays.add(b); stays.slice.pinTaken = TakenPin(far, b)
        stays.causes.other(); stays.startRun(); stays.run.cancelRouting()
        assertNull(stays.mark)
        assertEquals("ab", stays.stored())
    }

    // ---------------- the Points list: names ----------------

    private fun route(vararg pts: GeoPoint) = PlannedRoute(listOf(PlannedLeg(pts.toList(), false)), 5000.0, 300.0, 150.0, 3600.0, null)

    @Test fun unnamedPointsAreNamedOnceFromTheIndexAndTheOwnNameComesFirst() {
        val app = App(listOf(a, b, c))
        app.stopNames = mapOf(b to "Leiter Alm") // a pinned POI: needs no lookup
        app.named = mapOf(a to "Algund", c to "Meran")
        app.slice.refreshPointNames()
        assertEquals(1, app.nameCalls)
        assertEquals(mapOf(a to "Algund", c to "Meran"), app.slice.nearbyNames)
        val rows = app.slice.planPointRows(null)
        assertEquals(listOf("Algund", "Leiter Alm", "Meran"), rows.map { it.name })
        app.slice.refreshPointNames() // everything known: no second lookup
        assertEquals(1, app.nameCalls)
    }

    @Test fun aMissingIndexLeavesThePointsUnnamedAndSaysSo() {
        val app = App(listOf(a, b))
        app.indexFails = true
        app.slice.refreshPointNames()
        assertEquals(emptyMap<GeoPoint, String>(), app.slice.nearbyNames)
        assertEquals(listOf("No place index for point names; \"Point N\" shown"), problems.said("plan"))
        assertTrue(problems.none())
    }

    @Test fun theRowsCarryTheRoutesDistancesOnlyWhileNoRunIsOpen() {
        val app = App(listOf(a, b))
        assertEquals(2, app.slice.planPointRows(route(a, b)).size)
        assertFalse(app.slice.planPointRows(route(a, b)).last().approx) // measured along the route
        app.startRun()
        assertTrue(app.run.routing)
        assertTrue(app.slice.planPointRows(route(a, b)).last().approx) // the route is not used while it is calculated
    }

    @Test fun theRouteDetailsAndTheSheetSizeAreTheSlicesState() {
        val app = App()
        assertFalse(app.slice.routeDetailsOpen)
        assertEquals(PlanSize.MINI, app.slice.planSize)
        app.slice.routeDetailsOpen = true
        assertTrue(app.slice.routeDetailsOpen)
    }

    private fun App.problemsPlan(area: String = "plan") = problems.traced(area)
}
