package com.onpath.app.ui.plan

import com.onpath.app.domain.AppMode
import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.RouteSpot
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.domain.plan.RunCause
import com.onpath.app.routing.Activity
import com.onpath.app.routing.AnchorSet
import com.onpath.app.routing.Anchors
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.LegCache
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.RouteCancel
import com.onpath.app.testkit.FakeRoutingEngine
import com.onpath.app.testkit.ProblemsRule
import com.onpath.app.ui.Background
import com.onpath.app.ui.PlanSize
import com.onpath.app.ui.Say
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * "Ganz neu berechnen" (docs/design/detour-stitch.md §2) in the planner's chain (setup as PlanRecalcNoTourTest, no tour, no map
 * data): [PlanEditState.recalcFromScratch] clears the anchors in one undoable step and asks for a run; the next run carries none,
 * one Undo brings them back.
 */
class PlanRecalcFromScratchTest {
    @get:Rule val problems = ProblemsRule()

    private val a = GeoPoint(46.600, 11.100)
    private val b = GeoPoint(46.610, 11.130)
    private val rome = GeoPoint(41.90, 12.50)

    private class MemoryPlanStore : PlanStore {
        override var points: List<GeoPoint> = emptyList()
        override var pinned: Set<GeoPoint> = emptySet()
        override var savedName: String? = null
        override var savedId: Long = -1L
        override var savedPoints: List<GeoPoint>? = null
        override var anchorsJson: String? = null
        override var savedAnchorsJson: String? = null
    }

    private val plan = PlanDocument(MemoryPlanStore())
    private val causes = RunCause()
    private val legs = LegCache()
    private val engine = FakeRoutingEngine()
    private val said = ArrayList<String>()
    private val did = ArrayList<String>()

    private val planRun = PlanRunSlice(causes, object : PlanRunHost {
        override fun points(): List<GeoPoint> = plan.points.toList()
        override fun clearRoute() { host.route = null; host.routeError = null }
        override fun noCorridor() = Unit
        override fun loadDeferredMaps(before: List<GeoPoint>) = Unit
        override fun recalc() { did += "run.recalc" }
        override fun cancelRun(why: NotRouted) = Unit
    })

    private val ports = object : PlanRecalcPorts {
        override val runCauses: RunCause get() = causes
        override val activity: Activity get() = Activity.HIKE
        override val sac: Int get() = 2
        override val legCache: LegCache get() = legs
        override fun planFactors(a: Activity) = com.onpath.app.time.Factors()
        override fun withModelTime(r: PlannedRoute, a: Activity, f: com.onpath.app.time.Factors): PlannedRoute = r
        override var activityBefore: PlannedRoute? = null
        override var spotsOpen: Boolean = false
        override var spotLine: List<GeoPoint>? = null
        override var spotsMemo: Pair<Triple<List<GeoPoint>, Activity, Int>, List<RouteSpot>>? = null
        override var routeChangedNotice: String? = null
        override var planSize: PlanSize = PlanSize.COLLAPSED
        override fun setSaved(name: String?, id: Long, points: List<GeoPoint>?) = Unit
        override fun fitFirstRoute(line: List<GeoPoint>, pts: List<GeoPoint>, extraMs: Long) = Unit
        override fun linkSnapped(routed: List<GeoPoint>, snapped: List<GeoPoint>) = Unit
    }

    private val host = object : PlanRecalcHost {
        override var route: PlannedRoute? = null
        override var routeError: String? = null
        override var highlight: GeoPoint? = null
        override var planReach: com.onpath.app.routing.PlanReach? = null
        override var planResult: com.onpath.app.routing.PlanResult? = null
        override val mode: AppMode get() = AppMode.PLAN
        override fun nameOfStop(p: GeoPoint): String? = null
        override val multiDayOverview: Boolean get() = false
        override fun menuFollowsSnap(moves: Map<GeoPoint, GeoPoint>) = Unit
        override fun checkRoutingDataFor(line: List<GeoPoint>) = Unit
    }

    private val background = object : Background {
        override fun <T> run(what: String, work: () -> T, always: () -> Unit, then: (T) -> Unit) {
            try { then(work()) } finally { always() }
        }
    }

    private val state = PlanRecalcState(plan, planRun, ports, PlanTourPort.None, MapDataPort.None,
        Say { text, _, _ -> said += text }, host, background, elapsedMs = { 0L })

    /** The edit's ports: every call answers "nothing" (no tour, no menus) and is logged by name. */
    private var touring = false
    private val editPorts = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PlanEditPorts::class.java)) { _, m, _ ->
        if (m.name != "touring") did += m.name // a question, not a follow-up
        when (m.returnType) {
            java.lang.Boolean.TYPE -> m.name == "touring" && touring
            java.lang.Integer.TYPE -> 0
            else -> null
        }
    } as PlanEditPorts
    private val edit = PlanEditState(plan, planRun, causes, editPorts)

    private fun g(lat: Double) = GeoPoint(lat, 11.0)
    private val x = g(46.605); private val y = g(46.607)
    private val anchors = mapOf(1 to Anchors(listOf(x), listOf(y)))

    private fun withAnchors(d: PlanDocument, pts: List<GeoPoint>) {
        d.arriving(pts, AnchorSet.of(anchors, Activity.HIKE, 2))
        d.edit { clear(); addAll(pts) }
        did.clear()
    }

    private fun nextRun(): PlanRecalcState.Run = state.begin()!!

    @Test fun recalcFromScratchClearsTheAnchorsRoutesAgainAndOneUndoBringsThemBack() {
        val c = GeoPoint(46.62, 11.16)
        withAnchors(plan, listOf(a, b, c))
        assertTrue(plan.hasDetours(Activity.HIKE, 2))
        assertEquals(anchors, nextRun().anchors)
        edit.recalcFromScratch(Activity.HIKE, 2)
        assertFalse(plan.hasDetours(Activity.HIKE, 2))
        assertEquals(listOf(a, b, c), plan.points.toList())
        assertTrue("a run was asked for", "recalc" in did && "planChanged" in did)
        assertEquals(emptyMap<Int, Anchors>(), nextRun().anchors)
        edit.undoStep()
        assertTrue(plan.hasDetours(Activity.HIKE, 2))
        assertEquals(listOf(a, b, c), plan.points.toList())
        assertEquals(anchors, nextRun().anchors)
        edit.redoStep()
        assertFalse(plan.hasDetours(Activity.HIKE, 2))
        assertEquals(emptyMap<Int, Anchors>(), nextRun().anchors)
    }

    @Test fun theClearAndItsUndoAreStored() {
        val store = MemoryPlanStore()
        val d = PlanDocument(store)
        withAnchors(d, listOf(a, b, rome))
        assertTrue(store.anchorsJson != null)
        assertTrue(d.clearDetours())
        assertEquals(null, store.anchorsJson)
        d.replace(d.undo()!!)
        assertTrue(store.anchorsJson != null)
        assertEquals(anchors, PlanDocument(store).anchorSet.anchors) // the next start of the app
    }

    @Test fun otherProfileOrNoAnchorsDoNothing() {
        withAnchors(plan, listOf(a, b, rome))
        edit.recalcFromScratch(Activity.HIKE, 3) // the anchors lie on T2
        assertTrue(plan.hasDetours(Activity.HIKE, 2))
        assertTrue(did.isEmpty())
        val bare = PlanDocument(MemoryPlanStore()).also { it.edit { addAll(listOf(a, b)) } }
        val steps = bare.canUndo
        PlanEditState(bare, planRun, causes, editPorts).recalcFromScratch(Activity.HIKE, 2)
        assertTrue("no run", did.isEmpty())
        assertEquals("no history step", steps, bare.canUndo)
        assertFalse(bare.clearDetours())
    }

    @Test fun aClearedSavedPlanIsUnsavedUntilSavedAgainAndItsUndoIsSavedAgain() {
        val store = MemoryPlanStore()
        val d = PlanDocument(store)
        val pts = listOf(a, b, rome)
        withAnchors(d, pts)
        d.setSaved("Tour", 5L, pts)
        assertFalse("saved with its detour points", d.unsaved)
        assertTrue(d.clearDetours())
        assertTrue("the saved row still holds the old detour points", d.unsaved)
        d.replace(d.undo()!!)
        assertFalse("the undo equals the saved ones", d.unsaved)
        assertTrue(d.clearDetours())
        assertTrue(d.unsaved)
        d.setSaved("Tour", 5L, pts)
        assertFalse("saved again without them", d.unsaved)
        d.replace(d.undo()!!)
        assertTrue("the old ones differ from the new save", d.unsaved)
    }

    @Test fun aSavedPlanWithoutDetourPointsIsUnsavedAsBefore() {
        val d = PlanDocument(MemoryPlanStore())
        d.edit { addAll(listOf(a, b)) }
        d.setSaved("Tour", 5L, listOf(a, b))
        assertFalse(d.unsaved)
        d.edit { add(rome) }
        assertTrue(d.unsaved)
        assertFalse("nothing to clear", d.clearDetours())
    }

    @Test fun notWhileATourRuns() {
        withAnchors(plan, listOf(a, b, rome))
        touring = true
        assertFalse("the row is hidden", edit.canRecalcFromScratch(Activity.HIKE, 2))
        assertFalse(edit.recalcFromScratch(Activity.HIKE, 2))
        assertTrue("anchors unchanged", plan.hasDetours(Activity.HIKE, 2))
        assertEquals(anchors, plan.anchorSet.anchors)
        assertTrue("nothing re-routes", did.none { it == "recalc" || it == "planChanged" })
        touring = false
        assertTrue(edit.canRecalcFromScratch(Activity.HIKE, 2))
        assertTrue(edit.recalcFromScratch(Activity.HIKE, 2))
        assertFalse(plan.hasDetours(Activity.HIKE, 2))
    }

    private val hikeProfile = com.onpath.app.routing.AnchorProfile.of(Activity.HIKE, 2)
    private fun snap(d: PlanDocument, routed: List<GeoPoint>) {
        // what PlanRecalcState.snapToRoute does for a saved plan: the free point moves a little, the saved link follows
        val snapped = routed.mapIndexed { i, p -> if (i == 2) GeoPoint(p.lat + 0.0001, p.lon) else p }
        d.moveSnapped(routed, snapped, hikeProfile)
        d.relinking { d.setSaved(d.linkName, d.linkId, snapped) }
    }

    @Test fun aSnapAfterAClearKeepsTheSavedPlanUnsaved() {
        val d = PlanDocument(MemoryPlanStore())
        val pts = listOf(a, b, rome)
        withAnchors(d, pts)
        d.setSaved("Tour", 5L, pts)
        assertTrue(d.clearDetours())
        assertTrue(d.unsaved)
        snap(d, pts)
        assertTrue("the cold run's snap is no save", d.unsaved)
    }

    @Test fun aNormalSnapOfASavedStitchedRouteIsNotUnsaved() {
        val d = PlanDocument(MemoryPlanStore())
        val pts = listOf(a, b, rome)
        withAnchors(d, pts)
        d.setSaved("Tour", 5L, pts)
        snap(d, pts)
        assertFalse("anchors matched the save before the snap: still saved", d.unsaved)
    }

    @Test fun aRenameOfAClearedSavedPlanKeepsItUnsaved() {
        val d = PlanDocument(MemoryPlanStore())
        val pts = listOf(a, b, rome)
        withAnchors(d, pts)
        d.setSaved("Tour", 5L, pts)
        assertTrue(d.clearDetours())
        d.relinking { d.setSaved("Neu", 5L, d.savedPoints) }
        assertEquals("Neu", d.linkName)
        assertTrue(d.unsaved)
    }

    @Test fun theSavedDetourPointsSurviveARestart() {
        val store = MemoryPlanStore()
        val d = PlanDocument(store)
        val pts = listOf(a, b, rome)
        withAnchors(d, pts)
        d.setSaved("Tour", 5L, pts)
        assertTrue(d.clearDetours())
        assertTrue(PlanDocument(store).unsaved)
        d.setSaved("Tour", 5L, pts)
        assertFalse(PlanDocument(store).unsaved)
        // an old install has no such key: the loaded detour points count as saved
        store.savedAnchorsJson = null
        assertFalse(PlanDocument(store).unsaved)
    }
}
