package com.onpath.app.ui.plan

import com.onpath.app.data.Geo
import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.PlaceAddMenu
import com.onpath.app.domain.PlanStatus
import com.onpath.app.domain.PointMenuTarget
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.domain.plan.RunCause
import com.onpath.app.navigation.TourSplit
import com.onpath.app.poi.PinnedPoiKinds
import com.onpath.app.poi.Poi
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.RouteCancel
import com.onpath.app.routing.RoutingException
import com.onpath.app.search.Place
import com.onpath.app.search.SavedPlaces
import com.onpath.app.testkit.ManualExecutor
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import com.onpath.app.ui.search.PlaceCardUi
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.plan_add_as_stop
import com.onpath.strings.search_add_fits_best
import com.onpath.strings.search_add_on_route
import com.onpath.strings.search_card_new_destination
import com.onpath.strings.search_card_set_destination
import com.onpath.strings.search_card_set_start
import com.onpath.strings.search_is_stop
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * The menus of a held place, a route point and the found place's pin, the place card, the POI kinds of pinned points and the
 * kept places (rebuild phase 2 piece 12, core feature C19) as flows of the REAL [PlaceMenusSlice] and [KeptPlacesSlice] with
 * the real [PlaceAddSlice] (the hold's state, its measure on the router's paths), [PlanEditSlice], [PlanDocument],
 * [PlanRunSlice] and [com.onpath.app.domain.HoldPaths]. Fakes only at the edges: the hosts (the tour's answers, the route the
 * map shows, the search's pin and picked place), the router (scripted lengths on a held thread, the 1.5 s limit on a virtual
 * clock), the offline place index and the kept places' store. The hosts the view model implements by calls into the slices
 * ([PlaceAddHost.closeMapOverlays], [PlanEditHost.closePointMenu] …) route to the real slice here, as the view model's adapters
 * do (PlaceMenusAdaptersTest pins those). The store records each call with the stored places at that moment, so "written, then
 * backed up, then sent to the browser" is read from the log; values read on one branch only count their reads.
 *
 * Replaces the source pins on the view model's menu functions (FoundPlacePinTest's "closing a menu keeps the pin" and "Nadel
 * entfernen removes it", PlanEditsTest's "Start here of the point menu is the add's edit"). What has no behaviour test here:
 * the view model's own adapters (PlaceMenusAdaptersTest reads them), the screens' menus, the search flow's pin and picked place
 * themselves (the host's), `wayThereFrom` and `tourPassed` (tour code) – scripted answers here, and the stored FORMAT of the
 * kept places (`SearchStore`, SavedPlacesTest).
 */
class PlaceMenusFlowTest {
    @get:Rule val composeState = OwnComposeSnapshot()
    @get:Rule val problems = ProblemsRule()

    private val a = GeoPoint(46.60, 11.10)
    private val b = GeoPoint(46.62, 11.10)
    private val c = GeoPoint(46.64, 11.10)
    private fun at(from: GeoPoint, eastM: Double, northM: Double) =
        GeoPoint(from.lat + Geo.degLat(northM), from.lon + Geo.degLon(eastM, from.lat))
    /** 1.5 km east of the goal: appending is the shorter on straight lines, a stop the shorter on the scripted paths. */
    private val beside = at(c, 1500.0, 0.0)
    private val cafe = Poi("n2", "cafe", "Café Ost", beside.lat, beside.lon)
    private val hut = Poi("n1", "hut", "Hütte", b.lat, b.lon)
    private val letters = HashMap<GeoPoint, String>().apply { put(a, "a"); put(b, "b"); put(c, "c"); put(beside, "S") }
    private fun ids(l: List<GeoPoint>?) = l?.joinToString("") { letters[it] ?: "?" } ?: "-"

    @OptIn(InternalCoroutinesApi::class)
    private class VirtualMain : CoroutineDispatcher(), Delay {
        var now = 0L
        private class Timer(val at: Long, val seq: Int, val block: Runnable) { var live = true }
        private val timers = ArrayList<Timer>()
        private var seq = 0
        override fun isDispatchNeeded(context: CoroutineContext) = false
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            timers += Timer(now + timeMillis, seq++, Runnable { continuation.cancel() })
        }
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

    private fun place(id: String, name: String?, kind: String = "hut", at: GeoPoint = b, address: String? = null) =
        Place(id, name, kind, at.lat, at.lon, address = address)

    private inner class App(initial: List<GeoPoint> = emptyList()) {
        val store = MemoryPlanStore().also { it.points = initial }
        val plan = PlanDocument(store)
        val causes = RunCause()
        val main = VirtualMain()
        val worker = ManualExecutor("hold")
        val io = ManualExecutor("io")
        /** What the hosts and the store were asked, in order, with the state at that moment. */
        val log = ArrayList<String>()
        lateinit var add: PlaceAddSlice
        lateinit var edit: PlanEditSlice
        lateinit var run: PlanRunSlice
        lateinit var menus: PlaceMenusSlice
        lateinit var kept: KeptPlacesSlice

        var planMode = true
        var touring = false
        var activity = Activity.HIKE
        var sac = 2
        var routeLine: List<GeoPoint>? = null
        var route: PlannedRoute? = null
        var position: GeoPoint? = null
        var passed: Set<Int> = emptySet()
        var wayThere: GeoPoint? = null
        var mark: Place? = null
        var picked: Place? = null
        var candidateIds = HashSet<String>()
        val reads = HashMap<String, Int>()
        private fun read(what: String) { reads[what] = (reads[what] ?: 0) + 1 }
        val routed = ArrayList<String>()
        var lengths: (GeoPoint, GeoPoint) -> Double = { x, y -> Geo.distance(x, y) }

        // the kept places' store
        var stored = SavedPlaces()
        var storeFails: Exception? = null
        private fun saved(s: SavedPlaces) = "home=${s.home?.id ?: "-"} favs=${s.favourites.joinToString(",") { it.id }.ifEmpty { "-" }}"

        // the index of the pinned kinds
        var indexThrows: Exception? = null
        var indexPlaces: List<Place> = emptyList()
        val indexAsked = ArrayList<String>()

        val router = HoldRouter { x, y, act, level, cancel ->
            routed += "${ids(listOf(x))}→${ids(listOf(y))} ${act.name} T$level"
            if (cancel.isCancelled) throw RoutingException("stopped", kind = RoutingException.Kind.CANCELLED)
            lengths(x, y)
        }

        val addHost = object : PlaceAddHost {
            override fun closeMapOverlays(why: String) { log += "closeMapOverlays($why)"; menus.closeMapOverlays(why) }
            override fun openPointMenu(index: Int, via: String) { log += "openPointMenu($index, $via)"; menus.openPointMenu(index, via = via) }
            override fun closePlaceCard() { log += "closePlaceCard"; menus.closePlaceCard() }
            override fun planMode() = planMode
            override fun canEditMap() = true
            override fun activity() = activity
            override fun sac() = sac
            override fun routeLine(): List<GeoPoint>? = routeLine
            override fun touring() = touring
            override fun myPosition(): GeoPoint? { read("myPosition"); return position }
            override fun tourPassed(plan: List<GeoPoint>): Set<Int> = passed
            override fun followLine(): List<GeoPoint>? = null
            override fun wayThereFrom(): GeoPoint? = wayThere
            override fun gpsPoint(): GeoPoint? = null
            override fun searchMark(): Place? = mark
            override fun takePin(lat: Double, lon: Double): Place? = null
            override fun isCandidate(poi: Poi): Boolean = poi.id in candidateIds
            override fun rememberPlaceName(p: GeoPoint, name: String) = Unit
            override fun rememberPoiKind(p: GeoPoint, kind: String?) = menus.rememberPoiKind(p, kind)
            override fun stopIndexAt(lat: Double, lon: Double): Int =
                plan.points.indexOfFirst { Math.abs(it.lat - lat) < 1e-7 && Math.abs(it.lon - lon) < 1e-7 }
            override fun routeIndexOf(place: Place): Int? = stopIndexAt(place.lat, place.lon).takeIf { it >= 0 }
        }

        val editHost = object : PlanEditHost {
            override fun cancelHoldMeasure(why: String) { add.cancelHoldMeasure(why) }
            override fun stopFollowingWhenMapTurns() = Unit
            override fun closePointMenu(why: String) { log += "edit.closePointMenu($why)"; menus.closePointMenu(why) }
            override fun clearFirstPointMenu() { add.firstPointMenu = null }
            override fun clearHoldAddMenu() { add.holdAddMenu = null }
            override fun clearHighlight() = Unit
            override fun removePin(why: String) { log += "edit.removePin($why)"; menus.removePin(why) }
            override fun menuAction(what: String): Int? = menus.menuAction(what)
            override fun setRoutingSpot(spot: GeoPoint?) = Unit
            override fun loadMapsForNewPoints(before: List<GeoPoint>) = Unit
            override fun keepAsDraft(why: String) = Unit
            override fun routeLine(): List<GeoPoint>? = routeLine
            override fun recalc() = Unit
            override fun restoreActivity(c: PlanStatus.Cause.ActivityChange) = Unit
            override fun planChanged() = Unit
            override fun myPosition(): GeoPoint? = position
            override fun touring() = touring
            override fun following() = false
            override fun followIsTrack() = false
            override fun followsLine(line: List<GeoPoint>) = false
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
                set(v) { mark = v }
        }

        val menusHost = object : PlaceMenusHost {
            override fun planMode() = planMode
            override fun touring() = touring
            override fun activity() = activity
            override fun sac() = sac
            override fun route(): PlannedRoute? { read("route"); return route }
            override fun wayThereFrom(): GeoPoint? { read("wayThereFrom"); return wayThere }
            override fun routeIndexOf(place: Place): Int? = addHost.routeIndexOf(place)
            override var searchMark: Place?
                get() = mark
                set(v) { mark = v; log += "searchMark=${v?.name}" }
            override var searchPlace: Place?
                get() = picked
                set(v) { picked = v; log += "searchPlace=${v?.name}" }
            override suspend fun offlineIndex(): PlaceLookup {
                log += "offlineIndex"
                indexThrows?.let { throw it }
                return PlaceLookup { minLat, minLon, maxLat, maxLon, kinds ->
                    indexAsked += "${"%.5f".format(minLat)},${"%.5f".format(minLon)}..${"%.5f".format(maxLat)},${"%.5f".format(maxLon)} kinds=${kinds.size}"
                    indexPlaces
                }
            }
        }

        val placesStore = object : PlacesStore {
            override fun saved(): SavedPlaces { log += "store.saved ${saved(stored)}"; return stored }
            override fun update(change: (SavedPlaces) -> SavedPlaces): SavedPlaces {
                storeFails?.let { log += "store.update fails"; throw it }
                val next = change(stored)
                stored = next
                log += "store.update → ${saved(stored)}"
                return next
            }
            override fun backupChanged() { log += "backup ${saved(stored)}" }
            override fun syncChanged() { log += "sync ${saved(stored)}" }
        }

        val keptHost = object : KeptPlacesHost {
            override fun searchMark(): Place? { read("searchMark"); return mark }
            override fun isCandidate(poi: Poi): Boolean { read("isCandidate"); return poi.id in candidateIds }
        }

        init {
            run = PlanRunSlice(causes, object : PlanRunHost {
                override fun points() = plan.points.toList()
                override fun clearRoute() = Unit
                override fun noCorridor() = Unit
                override fun loadDeferredMaps(before: List<GeoPoint>) = Unit
                override fun recalc() = Unit
                override fun cancelRun(why: NotRouted) { edit.cancelRun(why) }
            })
            edit = PlanEditSlice(plan, run, causes, editHost, CoroutineScope(Job() + main), main)
            add = PlaceAddSlice(plan, edit, run, addHost, router, worker.asCoroutineDispatcher(), CoroutineScope(Job() + main))
            menus = PlaceMenusSlice(plan, run, add, menusHost, router, io.asCoroutineDispatcher(), CoroutineScope(Job() + main))
            kept = KeptPlacesSlice(placesStore, keptHost)
        }

        fun stored() = ids(store.points)
        fun trace() = problems.traced("plan")
        fun measure() = worker.runAll()
        /** The route run is calculating (the shown line belongs to older points). */
        fun routing() {
            causes.start()
            run.routeEvent(RouteRun.Event.Recalc(listOf(a, b), Activity.HIKE, planMode = true, newPlan = false))
            check(run.routing) { "the run is calculating" }
        }
        /** The straight legs of a 3-point plan a b c are 2.2 km; what the paths say for a spot beside it. */
        fun scriptPaths(spot: GeoPoint, append: Double, insert: Double) {
            lengths = { x, y -> when {
                x == c && y == spot -> append
                x == spot || y == spot -> (2220.0 + insert) / 2
                else -> 2220.0
            } }
        }
        fun routeOf(points: List<GeoPoint>, durationS: Double = 3600.0) =
            PlannedRoute(listOf(PlannedLeg(points, false)), 4440.0, 0.0, 0.0, durationS, null)
    }

    // ---------------- a held place's menu ----------------

    @Test fun aPlaceMenuOnASmallPlanOpensAtOnceAfterTheOtherMenusClosed() {
        val app = App(listOf(a))
        app.menus.openPointMenu(0)
        app.add.holdAddMenu = PlaceAddState.HoldAdd(b, PlaceAddMenu.menu(2, false, null), 30.0)
        app.add.firstPointMenu = c
        val traced = app.trace().size
        app.menus.openPoiMenu(hut)
        assertEquals(hut, app.menus.poiMenu)
        assertNull(app.menus.pointMenuTarget)
        assertNull(app.add.holdAddMenu)
        assertNull(app.add.firstPointMenu)
        assertNull(app.add.holdMeasuring)
        assertEquals(0, app.worker.size)
        assertEquals(emptyList<String>(), app.routed)
        // each open menu said why it closed, the point menu first
        assertEquals(listOf(
            "Point menu closed (point 1): POI menu opened",
            "First-point menu closed: POI menu opened",
            "Hold menu closed: POI menu opened (nothing added)",
        ), app.trace().drop(traced))
        assertEquals(listOf(PlaceAddMenu.Item.SET_DESTINATION), app.menus.placeMenu(hut).main)
    }

    @Test fun aPlaceBesideThePlanHasItsRowsDecidedOnThePathsBeforeTheMenuOpensAndNothingJumps() {
        val app = App(listOf(a, b, c))
        app.activity = Activity.BIKE_TOURING; app.sac = 3
        app.scriptPaths(beside, append = 900.0, insert = 300.0) // the paths say: a stop – the straight lines say: the new goal
        app.menus.openPoiMenu(cafe, 30.0)
        // while the router works: the ring turns on the place, the menu is not open, the plan is as it was
        assertEquals(beside, app.add.holdMeasuring)
        assertNull(app.menus.poiMenu)
        assertEquals(1, app.worker.size)
        assertEquals("abc", app.stored())
        app.measure()
        assertNull(app.add.holdMeasuring)
        assertEquals(cafe, app.menus.poiMenu)
        assertEquals(listOf(PlaceAddMenu.Item.ADD_STOP), app.menus.placeMenu(cafe).main)
        // the router was asked for the plan's activity and level, appending (goal → place) first
        assertEquals("c→S BIKE_TOURING T3", app.routed.first())
        assertTrue(app.trace().last(), app.trace().last().startsWith("Place menu of a cafe: ") && app.trace().last().contains("ADD_STOP") &&
            app.trace().last().endsWith(", 3 points)"))
        // the same place with another id, or the plan changed since: the rows are decided again on straight lines (the new goal)
        assertEquals(listOf(PlaceAddMenu.Item.NEW_DESTINATION), app.menus.placeMenu(cafe.copy(id = "n9")).main)
        app.edit.change { removeAt(0) }
        assertEquals(listOf(PlaceAddMenu.Item.NEW_DESTINATION), app.menus.placeMenu(cafe).main)
    }

    @Test fun aPlaceOnTheLineOpensAtOnceWithoutTheRouter() {
        val app = App(listOf(a, c))
        app.routeLine = listOf(a, c)
        app.menus.openPoiMenu(hut, 30.0)
        assertEquals(hut, app.menus.poiMenu)
        assertNull(app.add.holdMeasuring)
        assertEquals(0, app.worker.size)
        assertEquals(emptyList<String>(), app.routed)
        assertEquals(listOf(PlaceAddMenu.Item.ADD_STOP), app.menus.placeMenu(hut).main)
        assertTrue(app.trace().last(), app.trace().last().startsWith("Place menu of a hut: ") && app.trace().last().contains("on the line ("))
    }

    @Test fun aSlowMeasureDecidesOnTheStraightLines() {
        val app = App(listOf(a, b, c))
        app.scriptPaths(beside, append = 900.0, insert = 300.0)
        app.menus.openPoiMenu(cafe, 30.0)
        app.main.advance(1_499)
        assertNull(app.menus.poiMenu)
        assertEquals(beside, app.add.holdMeasuring)
        app.main.advance(1)
        assertEquals(cafe, app.menus.poiMenu)
        assertNull(app.add.holdMeasuring)
        assertEquals(listOf(PlaceAddMenu.Item.NEW_DESTINATION), app.menus.placeMenu(cafe).main)
        assertTrue(app.trace().last(), app.trace().last().contains("paths failed: timeout after 1500 ms"))
    }

    /**
     * The hold's rule for the place menu too (owner: never lose the user's input silently; the hold's fix 62e7064c5): a
     * measure for a plan no longer shown is not applied – the menu is decided again on the plan now shown and opens.
     */
    @Test fun aMeasureForAPlanChangedMeanwhileIsMeasuredAgainAndTheMenuOpens() {
        val changes = mapOf<String, (App) -> Unit>(
            "the plan changed" to { x -> x.plan.edit { add(c) } },
            "another activity" to { x -> x.activity = Activity.BIKE_TOURING },
            "another T level" to { x -> x.sac = 4 },
        )
        for ((why, change) in changes) {
            val app = App(listOf(a, b, c))
            app.scriptPaths(beside, append = 900.0, insert = 300.0)
            app.menus.openPoiMenu(cafe, 30.0)
            change(app)
            app.worker.runNext() // the first measure finds its plan gone: measured again, the ring stays on the place
            assertNull(why, app.menus.poiMenu)
            assertEquals("$why: the ring stays while measuring again", beside, app.add.holdMeasuring)
            assertTrue("$why: ${app.trace()}", app.trace().last().startsWith(
                "Place menu: measured for a plan no longer shown – measured again on the plan now shown ("))
            app.measure()
            assertEquals(why, cafe, app.menus.poiMenu)
            assertNull(why, app.add.holdMeasuring)
            assertEquals(why, listOf(PlaceAddMenu.Item.ADD_STOP), app.menus.placeMenu(cafe).main)
            assertTrue("$why: ${app.trace().last()}", app.trace().last().startsWith("Place menu of a cafe: "))
            // the second measure asked the router for the plan now shown
            assertEquals("$why: ${app.routed}", "c→S ${app.activity.name} T${app.sac}", app.routed.last { it.startsWith("c→S") })
        }
        // a tour started meanwhile: the tour's zone decides at once (no second measure)
        val tour = App(listOf(a, b, c))
        tour.scriptPaths(beside, append = 900.0, insert = 300.0)
        tour.menus.openPoiMenu(cafe, 30.0)
        tour.touring = true
        tour.measure()
        assertEquals(cafe, tour.menus.poiMenu)
        assertNull(tour.add.holdMeasuring)
        assertEquals(0, tour.worker.size)
    }

    /** The plan changes again while it is measured again: the menu opens with its rows on the straight lines. */
    @Test fun aSecondRaceStillOpensTheMenuOnTheStraightLines() {
        val app = App(listOf(a, b, c))
        app.scriptPaths(beside, append = 900.0, insert = 300.0) // the paths would say: a stop – the straight lines say: the new goal
        app.menus.openPoiMenu(cafe, 30.0)
        app.activity = Activity.BIKE_TOURING
        app.worker.runNext()
        assertEquals(beside, app.add.holdMeasuring)
        app.sac = 4
        app.measure()
        assertEquals(cafe, app.menus.poiMenu)
        assertNull(app.add.holdMeasuring)
        assertEquals(0, app.worker.size)
        assertEquals(listOf(PlaceAddMenu.Item.NEW_DESTINATION), app.menus.placeMenu(cafe).main)
        assertTrue(app.trace().last(), app.trace().last().startsWith(
            "Place menu: measured again for a plan no longer shown – opened, its rows on the straight lines ("))
    }

    /** Leaving Plan while the measure ran: the menu does not open (the user went elsewhere), and the trace says so. */
    @Test fun aMeasureAfterLeavingPlanOpensNoMenu() {
        val app = App(listOf(a, b, c))
        app.scriptPaths(beside, append = 900.0, insert = 300.0)
        app.menus.openPoiMenu(cafe, 30.0)
        app.planMode = false
        app.measure()
        assertNull(app.menus.poiMenu)
        assertNull(app.add.holdMeasuring)
        assertEquals(0, app.worker.size)
        assertTrue(app.trace().last(), app.trace().last().startsWith("Place menu: measured, but Plan was left – not opened ("))
    }

    @Test fun onATourOrARoundTripOrOutsidePlanThePlaceMenuOpensAtOnceOnTheZone() {
        // on a tour: a place past the goal asks the new goal first, a place on the way the stop first; nothing is measured
        val tour = App(listOf(a, b, c)); tour.touring = true
        tour.menus.openPoiMenu(cafe, 30.0)
        assertEquals(cafe, tour.menus.poiMenu)
        assertEquals(0, tour.worker.size)
        assertEquals(listOf(PlaceAddMenu.Item.NEW_DESTINATION, PlaceAddMenu.Item.ADD_STOP), tour.menus.placeMenu(cafe).main)
        assertEquals(listOf(PlaceAddMenu.Item.ADD_STOP, PlaceAddMenu.Item.NEW_DESTINATION), tour.menus.placeMenu(hut.copy(lon = b.lon + 0.00001)).main)
        val loop = App(listOf(a, b, c, a))
        loop.menus.openPoiMenu(hut, 30.0)
        assertEquals(hut, loop.menus.poiMenu)
        assertEquals(0, loop.worker.size)
        assertEquals(emptyList<String>(), loop.routed)
        val other = App(listOf(a, b, c)); other.planMode = false
        other.menus.openPoiMenu(cafe, 30.0)
        assertEquals(cafe, other.menus.poiMenu)
        assertEquals(0, other.worker.size)
        // the activity and level are not read for these (only a measure needs them) – nothing was routed
        assertEquals(emptyList<String>(), other.routed)
    }

    @Test fun closingTheMenusStopsARunningMeasureAndForgetsItsRowsButKeepsThePin() {
        val app = App(listOf(a, b, c))
        app.scriptPaths(beside, append = 900.0, insert = 300.0)
        app.mark = place("photon:5", "Pin", "cafe", beside)
        app.menus.openPoiMenu(cafe, 30.0)
        assertEquals(beside, app.add.holdMeasuring)
        app.menus.closeMapOverlays("tap elsewhere")
        assertNull(app.add.holdMeasuring)
        assertEquals("Hold beside the plan: measure on the paths stopped (tap elsewhere)", app.trace().last())
        app.measure()
        assertNull(app.menus.poiMenu) // the stopped measure never opens it
        // an open menu with measured rows: closing forgets them (the next question is the straight lines')
        val open = App(listOf(a, b, c))
        open.scriptPaths(beside, append = 900.0, insert = 300.0)
        open.menus.openPoiMenu(cafe, 30.0); open.measure()
        assertEquals(listOf(PlaceAddMenu.Item.ADD_STOP), open.menus.placeMenu(cafe).main)
        open.mark = place("photon:5", "Pin", "cafe", beside)
        open.menus.closeMapOverlays("tap elsewhere")
        assertNull(open.menus.poiMenu)
        assertEquals(listOf(PlaceAddMenu.Item.NEW_DESTINATION), open.menus.placeMenu(cafe).main)
        assertEquals("POI menu closed: tap elsewhere", open.trace().last())
        // the found place's pin and the card's picked place stay: only "Nadel entfernen" / adding / a new search take them
        assertEquals("Pin", open.mark?.name)
        open.picked = place("photon:5", "Pin", "cafe", beside)
        open.menus.closeMapOverlays("again")
        assertEquals("Pin", open.mark?.name)
        assertEquals("the picked place stays while no card is open", "Pin", open.picked?.name)
        assertFalse(open.log.any { it.startsWith("searchPlace=") })
    }

    @Test fun closingTheMenusClosesAnOpenPlaceCardAndSaysWhichMenusWereOpen() {
        val app = App(listOf(a, b, c))
        app.menus.planPoi = hut
        app.picked = place("n1", "Hütte")
        app.add.holdAddMenu = PlaceAddState.HoldAdd(beside, PlaceAddMenu.menu(3, false, PlaceAddMenu.Zone.GOAL), 30.0)
        assertTrue(app.menus.mapOverlayOpen)
        app.menus.closeMapOverlays("a tap")
        assertNull(app.menus.planPoi)
        assertNull(app.picked)
        assertEquals(listOf("searchPlace=null"), app.log.filter { it.startsWith("searchPlace=") })
        assertEquals("Hold menu closed: a tap (nothing added)", app.trace().last())
        assertFalse(app.menus.mapOverlayOpen)
    }

    @Test fun aMapMenuIsOpenWhenAnyOfTheFiveStateHoldersIsSet() {
        val app = App(listOf(a, b, c))
        assertFalse(app.menus.mapOverlayOpen)
        app.menus.poiMenu = hut; assertTrue(app.menus.mapOverlayOpen); app.menus.poiMenu = null
        app.menus.openPointMenu(1); assertTrue(app.menus.mapOverlayOpen); app.menus.closePointMenu("test")
        app.add.firstPointMenu = a; assertTrue(app.menus.mapOverlayOpen); app.add.firstPointMenu = null
        app.add.holdAddMenu = PlaceAddState.HoldAdd(beside, PlaceAddMenu.menu(3, false, null), 30.0); assertTrue(app.menus.mapOverlayOpen); app.add.holdAddMenu = null
        app.menus.planPoi = hut; assertTrue(app.menus.mapOverlayOpen); app.menus.planPoi = null
        assertFalse(app.menus.mapOverlayOpen)
    }

    // ---------------- a route point's menu ----------------

    @Test fun thePointMenuOpensOnTheHeldPointAndLogsWhyItDidNotOpen() {
        val app = App(listOf(a, b, c))
        app.menus.poiMenu = hut
        app.menus.openPointMenu(1, distDp = 7, via = "hold")
        assertEquals(PointMenuTarget(1, b), app.menus.pointMenuTarget)
        assertEquals(1, app.menus.pointMenu)
        assertNull(app.menus.poiMenu) // another menu closed first
        assertEquals(listOf("POI menu closed: another menu opened (hold)",
            "Point menu opened (hold): point 2 of 3, 7 dp from the finger"), app.trace())
        // a point that is not in the plan: not opened, the other menu closed all the same
        app.menus.openPointMenu(5, via = "POI that is a stop")
        assertNull(app.menus.pointMenuTarget)
        assertEquals("Point menu NOT opened: point 6 not in the plan of 3", app.trace().last())
        assertEquals("Point menu closed (point 2): another menu opened (POI that is a stop)", app.trace()[2])
    }

    @Test fun anOpenPointMenuStaysOnItsPointWhenThePlanChangesAndFollowsItWhenTheRouterSnapsIt() {
        val app = App(listOf(a, b, c))
        app.menus.openPointMenu(1)
        app.plan.edit { removeAt(0) } // the plan changed under the open menu: b is point 1 now
        assertEquals(0, app.menus.pointMenu)
        // the router snapped b: the menu follows its point, else it would lose it
        val b2 = at(b, 3.0, 4.0)
        app.plan.moveSnapped(listOf(b, c), listOf(b2, c))
        app.menus.followSnapped(mapOf(b to b2))
        assertEquals(PointMenuTarget(1, b2), app.menus.pointMenuTarget)
        assertEquals(0, app.menus.pointMenu)
        // another point snapped: nothing follows
        app.menus.followSnapped(mapOf(c to a))
        assertEquals(PointMenuTarget(1, b2), app.menus.pointMenuTarget)
    }

    @Test fun aPointMenuActionClosesTheMenuAndAnswersWithThePointNowAndTheLogSaysWhat() {
        val app = App(listOf(a, b, c))
        app.menus.openPointMenu(2)
        app.plan.edit { removeAt(0) }
        assertEquals(1, app.menus.menuAction("Delete"))
        assertNull(app.menus.pointMenuTarget)
        assertEquals("Point menu: Delete tapped – menu of point 3, now point 2, plan 2 points, route ready", app.trace().last())
        // no menu open (a second tap): null, said all the same; while a route is calculating the log says so
        app.routing()
        assertNull(app.menus.menuAction("Delete"))
        assertEquals("Point menu: Delete tapped – menu of point null, now point null, plan 2 points, route calculating", app.trace().last())
        // the point is gone: null
        val gone = App(listOf(a, b, c))
        gone.menus.openPointMenu(1)
        gone.plan.edit { clear(); add(a); add(c) }
        assertNull(gone.menus.menuAction("Open in Google Maps"))
        assertNull(gone.menus.pointMenuTarget)
    }

    @Test fun startHereOfThePointMenuDropsTheEarlierPointsInOneUndoStepAndDoesNothingWithoutAMenu() {
        val app = App(listOf(a, b, c))
        app.menus.openPointMenu(1)
        app.menus.makeStartFromMenu()
        assertEquals("bc", app.stored())
        assertEquals("bc", ids(PlanDocument(app.store).points))
        assertNull(app.menus.pointMenuTarget)
        assertEquals("Point menu: Start here tapped – menu of point 2, now point 2, plan 3 points, route ready", app.trace().last())
        app.edit.undoStep()
        assertEquals("abc", app.stored())
        // the point is gone (plan changed under the menu) or no menu: nothing changes
        app.menus.makeStartFromMenu()
        assertEquals("abc", app.stored())
        val gone = App(listOf(a, b, c))
        gone.menus.openPointMenu(1)
        gone.plan.edit { clear(); add(a); add(c) }
        gone.menus.makeStartFromMenu()
        assertEquals("ac", gone.stored())
        // the start itself: nothing to drop, no history step
        val first = App(listOf(a, b, c))
        first.menus.openPointMenu(0)
        first.menus.makeStartFromMenu()
        assertEquals("abc", first.stored())
        assertFalse(first.plan.canUndo)
    }

    // ---------------- the found place's pin ----------------

    @Test fun theChipOpensTheMenuOfThePinAndRemovingThePinTakesTheMarkAndThePickedPlace() {
        val app = App(listOf(a))
        app.menus.openPinMenu()
        assertNull(app.menus.poiMenu) // no pin, nothing to open
        assertEquals(emptyList<String>(), app.trace())
        val pin = place("photon:5", "Fabriciusstraße", "street", beside, address = "Fabriciusstraße 1, Meran")
        app.mark = pin
        app.picked = pin
        app.menus.openPinMenu()
        assertEquals(pin.toPoi(), app.menus.poiMenu)
        assertEquals("Pin chip tapped: menu of \"Fabriciusstraße\"", app.trace().first())
        app.menus.removePin("Nadel entfernen")
        assertNull(app.mark)
        assertNull(app.picked)
        assertEquals("Pin removed: Nadel entfernen", app.trace().last())
        assertEquals(listOf("searchMark=null", "searchPlace=null"), app.log.filter { it.startsWith("search") })
        // no pin: nothing said, the picked place stays (it is the card's, not the pin's)
        val before = app.trace().size
        app.picked = pin
        app.menus.removePin("again")
        assertEquals(before, app.trace().size)
        assertEquals(pin, app.picked)
    }

    @Test fun clearingThePlanTakesThePinThroughTheEditsHostAfterTheMenusClosed() {
        val app = App(listOf(a, b, c))
        app.mark = place("photon:5", "Pin", "cafe", beside)
        app.menus.openPointMenu(1)
        app.edit.clearPlan()
        assertNull(app.mark)
        assertNull(app.menus.pointMenuTarget)
        assertTrue(app.log.toString(), app.log.indexOf("edit.closePointMenu(plan cleared)") < app.log.indexOf("edit.removePin(plan cleared)"))
    }

    // ---------------- the label and the card ----------------

    @Test fun theAddLabelSaysWhatTheAddDoesAlsoDuringATour() {
        val newGoal = Txt.s(Res.string.search_card_new_destination)
        val stop = Txt.s(Res.string.plan_add_as_stop)
        assertNotEquals(newGoal, stop)
        // 2 km south of the start, 6.7 km from the goal: on the whole plan it fits best between a and b
        val south = cafe.copy(lat = a.lat - 0.02, lon = a.lon)
        val plain = App(listOf(a, b, c))
        assertEquals(stop, plain.menus.poiAddLabel(south, 30.0))
        assertEquals(newGoal, plain.menus.poiAddLabel(cafe, 30.0))
        // during a tour only the points ahead count, from the position (field 2026-11-07: the label was decided on the whole
        // plan and promised something else than the add did): the same spot is the new goal then
        val tour = App(listOf(a, b, c)); tour.touring = true; tour.passed = setOf(0, 1); tour.position = at(b, 0.0, 300.0)
        assertEquals(newGoal, tour.menus.poiAddLabel(south, 30.0))
        assertEquals(newGoal, tour.menus.poiAddLabel(cafe, 30.0))
        // the label's decision is the add's: the same place added as a stop goes where the label said
        assertEquals(stop, plain.menus.poiAddLabel(hut.copy(lat = a.lat + 0.01, lon = a.lon + 0.002), 30.0))
        // an empty plan: the first point is the start; one point: the destination
        assertEquals(Txt.s(Res.string.search_card_set_start), App().menus.poiAddLabel(hut))
        assertEquals(Txt.s(Res.string.search_card_set_destination), App(listOf(a)).menus.poiAddLabel(hut))
    }

    @Test fun thePlaceCardOfAPlannedPlaceSaysItsRoleAndReadsNoWayThereAndAPlaceNotPlannedSaysWhatAddingDoes() {
        val app = App(listOf(a, b, c))
        app.route = app.routeOf(listOf(a, b, c))
        val planned = app.menus.placeCardUi(place("n1", "Hütte"))
        assertEquals(PlaceCardUi(place("n1", "Hütte"), 1, 3, Txt.s(Res.string.search_is_stop, 2)), planned)
        assertEquals(1, app.reads["route"]) // read first (as before), the way there only for a place not planned
        assertNull(app.reads["wayThereFrom"])
        val card = app.menus.placeCardUi(place("n2", "Café Ost", "cafe", beside))
        assertNull(card.routeIndex)
        assertEquals(3, card.waypointCount)
        assertEquals(Txt.s(Res.string.search_card_new_destination), card.addLabel)
        assertFalse(card.addIsStart); assertFalse(card.addIsDestination)
        assertTrue(card.subtitle, card.subtitle.isNotEmpty() && card.subtitle.contains("min")) // „about +X m, +Y min“: the route's pace is known
        assertEquals(1, app.reads["wayThereFrom"])
        // a place 5 m beside the shown line is on the route; while a route calculates the line belongs to older points: it fits best
        val nearB = at(b, 5.0, 0.0)
        assertEquals(Txt.s(Res.string.search_add_on_route), app.menus.placeCardUi(place("n3", "Quelle", "spring", nearB)).subtitle)
        val busy = App(listOf(a, b, c)); busy.route = busy.routeOf(listOf(a, b, c)); busy.routing()
        assertTrue(busy.menus.placeCardUi(place("n3", "Quelle", "spring", nearB)).subtitle.startsWith(Txt.s(Res.string.search_add_fits_best)))
    }

    @Test fun thePlaceCardOfTheFirstAndSecondPointSaysStartAndDestination() {
        val empty = App()
        val first = empty.menus.placeCardUi(place("n1", "Hütte"))
        assertEquals(Txt.s(Res.string.search_card_set_start), first.addLabel)
        assertTrue(first.addIsStart); assertFalse(first.addIsDestination)
        assertEquals("", first.subtitle)
        val one = App(listOf(a))
        val second = one.menus.placeCardUi(place("n2", "Café Ost", "cafe", beside))
        assertEquals(Txt.s(Res.string.search_card_set_destination), second.addLabel)
        assertTrue(second.addIsDestination); assertFalse(second.addIsStart)
    }

    @Test fun duringATourTheCardSaysWhatTheAddReallyDoesAmongThePointsAheadWithoutASubtitle() {
        val tour = App(listOf(a, b, c)); tour.touring = true; tour.passed = setOf(0, 1); tour.position = at(b, 0.0, 300.0)
        tour.route = tour.routeOf(listOf(a, b, c))
        val card = tour.menus.placeCardUi(place("n2", "Café Ost", "cafe", beside))
        assertEquals("", card.subtitle)
        assertEquals(Txt.s(Res.string.search_card_new_destination), card.addLabel)
        // the same place without a tour has the pace in its subtitle
        val plan = App(listOf(a, b, c)); plan.route = plan.routeOf(listOf(a, b, c))
        assertNotEquals("", plan.menus.placeCardUi(place("n2", "Café Ost", "cafe", beside)).subtitle)
    }

    @Test fun theCardShowsThePickedPlaceOrThePoiAndClosingItForgetsBoth() {
        val app = App(listOf(a))
        assertNull(app.menus.cardPlace)
        app.menus.planPoi = hut
        assertEquals(Place.fromPoi(hut), app.menus.cardPlace)
        val picked = place("n1", "Hütte Süd", "hut", b, address = "Weg 3, Meran") // the search result behind the same POI: its address shows
        app.picked = picked
        assertEquals(picked, app.menus.cardPlace)
        app.picked = place("n7", "Anderer Ort") // another id: the POI itself
        assertEquals(Place.fromPoi(hut), app.menus.cardPlace)
        app.menus.closePlaceCard()
        assertNull(app.menus.planPoi)
        assertNull(app.picked)
        assertNull(app.menus.cardPlace)
    }

    // ---------------- POI kinds of pinned points ----------------

    @Test fun theKindOfAPinnedPointComesFromTheTapOnlyForPoiKindsAndOnlyForPinnedPoints() {
        val app = App(listOf(a, b, c))
        app.plan.pin(a); app.plan.pin(b)
        app.menus.rememberPoiKind(a, "hut")
        app.menus.rememberPoiKind(b, "street") // a search-only kind: no symbol
        app.menus.rememberPoiKind(c, "cafe") // known, but c is not pinned
        app.menus.rememberPoiKind(beside, null)
        assertEquals(mapOf(0 to "hut"), app.menus.pinnedPoiKinds())
    }

    @Test fun theIndexIsAskedOnceForEachPinnedPointWithoutAKnownKindOffTheMainThread() {
        val app = App(listOf(a, b, c))
        app.plan.pin(a); app.plan.pin(b)
        app.menus.rememberPoiKind(b, "hut") // known: not looked up
        app.indexPlaces = listOf(
            place("x1", "Café nah", "cafe", at(a, 1.0, 1.0)),
            place("x2", "Café weit", "cafe", at(a, 0.0, 20.0)), // not the same spot
            place("x3", "Bank", "street", at(a, 0.0, 1.0)), // not a POI kind
        )
        app.menus.refreshPinnedKinds()
        assertEquals(listOf("offlineIndex"), app.log)
        // the lookup waits for the IO thread
        assertEquals(emptyMap<Int, String>(), app.menus.pinnedPoiKinds().filterKeys { it == 0 })
        assertEquals(emptyList<String>(), app.indexAsked)
        app.menus.refreshPinnedKinds() // a lookup is running: no second one
        assertEquals(listOf("offlineIndex"), app.log)
        app.io.runAll()
        assertEquals(1, app.indexAsked.size)
        assertTrue(app.indexAsked.first(), app.indexAsked.first().endsWith("kinds=${PinnedPoiKinds.indexKinds.size}"))
        assertEquals(mapOf(0 to "cafe", 1 to "hut"), app.menus.pinnedPoiKinds())
        // once per point: nothing asked again; a point pinned later is
        app.menus.refreshPinnedKinds()
        assertEquals(listOf("offlineIndex"), app.log)
        app.plan.pin(c)
        app.indexPlaces = listOf(place("x4", "Gipfelhütte", "hut", at(c, 2.0, 0.0)))
        app.menus.refreshPinnedKinds(); app.io.runAll()
        assertEquals(2, app.indexAsked.size)
        assertEquals(mapOf(0 to "cafe", 1 to "hut", 2 to "hut"), app.menus.pinnedPoiKinds())
    }

    @Test fun aPointWithNothingNearIsNotAskedAgainButNoIndexIsTriedAgainNextTime() {
        val app = App(listOf(a, b, c))
        app.plan.pin(a)
        app.indexThrows = IllegalStateException("no index")
        app.menus.refreshPinnedKinds()
        assertEquals(emptyMap<Int, String>(), app.menus.pinnedPoiKinds())
        assertEquals(listOf("No place index for pinned POI symbols; plain points shown"), problems.said("plan"))
        assertEquals(0, app.io.size)
        // the index comes: the point is looked up now (it was not marked as looked up), nothing found – and not asked again
        app.indexThrows = null
        app.menus.refreshPinnedKinds(); app.io.runAll()
        assertEquals(1, app.indexAsked.size)
        assertEquals(emptyMap<Int, String>(), app.menus.pinnedPoiKinds())
        app.menus.refreshPinnedKinds(); app.io.runAll()
        assertEquals(1, app.indexAsked.size)
        assertEquals(2, app.log.count { it == "offlineIndex" }) // the third call found nothing to ask
    }

    // ---------------- kept places ----------------

    @Test fun aFavouriteIsKeptStoredBackedUpAndSentInThatOrderAndToggledOffAgain() {
        val app = App(listOf(a))
        assertFalse(app.kept.isFavouritePlace(hut))
        assertFalse(app.kept.isHomePlace(hut))
        assertEquals(listOf("store.saved home=- favs=-"), app.log) // read once, then from the cache
        app.log.clear()
        app.kept.toggleFavouritePlace(hut)
        assertEquals(listOf("store.update → home=- favs=n1", "backup home=- favs=n1", "sync home=- favs=n1"), app.log)
        assertEquals(listOf(Place.fromPoi(hut)), app.stored.favourites)
        assertEquals(listOf(Place.fromPoi(hut)), app.kept.savedPlaces?.favourites)
        assertTrue(app.kept.isFavouritePlace(hut))
        assertEquals("Place menu: \"Hütte\" kept as a favourite", problems.traced("search").last())
        app.log.clear()
        app.kept.toggleFavouritePlace(hut)
        assertEquals(listOf("store.update → home=- favs=-", "backup home=- favs=-", "sync home=- favs=-"), app.log)
        assertEquals(emptyList<Place>(), app.stored.favourites)
        assertEquals("Place menu: \"Hütte\" no longer a favourite", problems.traced("search").last())
    }

    @Test fun theNewestFavouriteComesFirstAndHomeIsReplacedOrRemovedWithoutTouchingTheFavourites() {
        val app = App(listOf(a))
        val cafeFav = cafe
        app.kept.toggleFavouritePlace(hut)
        app.kept.toggleFavouritePlace(cafeFav)
        assertEquals(listOf("n2", "n1"), app.stored.favourites.map { it.id })
        app.log.clear()
        app.kept.toggleHomePlace(hut)
        assertEquals("n1", app.stored.home?.id)
        assertEquals(listOf("n2", "n1"), app.stored.favourites.map { it.id })
        assertEquals("Place menu: \"Hütte\" set as home", problems.traced("search").last())
        assertTrue(app.kept.isHomePlace(hut)); assertFalse(app.kept.isHomePlace(cafeFav))
        app.kept.toggleHomePlace(cafeFav) // another place becomes home: the old home is replaced
        assertEquals("n2", app.stored.home?.id)
        assertEquals(listOf("n2", "n1"), app.stored.favourites.map { it.id })
        app.kept.toggleHomePlace(cafeFav) // the place that is home: removed
        assertNull(app.stored.home)
        assertEquals("Place menu: \"Café Ost\" no longer home", problems.traced("search").last())
        assertEquals(3, app.log.count { it.startsWith("backup") })
        assertEquals(3, app.log.count { it.startsWith("sync") })
    }

    @Test fun theFoundPlacesPinIsKeptWithItsAddressAndIsAskedAboutOnlyWhenThereIsOne() {
        val app = App(listOf(a))
        app.kept.toggleFavouritePlace(hut)
        assertNull(app.reads["isCandidate"]) // no pin: the candidate is not asked
        val pin = place("photon:7", "Hütte", "hut", b, address = "Weg 1, Meran")
        app.mark = pin; app.candidateIds += "n1"
        app.kept.toggleHomePlace(hut)
        assertEquals(pin, app.stored.home) // the pin's own place (address), not the POI's
        assertEquals(Place.fromPoi(hut), app.stored.favourites.single())
        assertEquals(1, app.reads["isCandidate"])
        // the pin (id photon:7) and the kept POI (id n1) are the same place: the same name within 100 m (SavedPlaces.same)
        assertTrue(app.kept.isFavouritePlace(hut))
    }

    @Test fun aToggleOnAStaleCacheDoesNotUndoWhatTheBrowserSyncBroughtAndTheStoreIsRead() {
        val app = App(listOf(a))
        assertFalse(app.kept.isFavouritePlace(hut)) // the cache says: not a favourite
        app.stored = SavedPlaces(favourites = listOf(Place.fromPoi(hut))) // the sync brought it meanwhile
        app.log.clear()
        app.kept.toggleFavouritePlace(hut) // intended: ON – already so in the store: unchanged, not toggled off
        assertEquals(listOf(Place.fromPoi(hut)), app.stored.favourites)
        assertEquals(listOf("store.update → home=- favs=n1", "backup home=- favs=n1", "sync home=- favs=n1"), app.log)
        assertEquals("Place menu: \"Hütte\" kept as a favourite", problems.traced("search").last())
        // the cache is dropped (a restored backup / a sync did): the next question reads the store again
        app.kept.savedPlaces = null
        app.log.clear()
        assertTrue(app.kept.isFavouritePlace(hut))
        assertEquals(listOf("store.saved home=- favs=n1"), app.log)
    }

    /** The planner's rows say the state they want (laptop 2026-11-20): asked twice it stays, no toggle back; home set / replaced / removed. */
    @Test fun thePlaceSettersTakeTheTargetStateNotAToggle() {
        val app = App(listOf(a))
        val p = Place.fromPoi(hut)
        app.kept.setFavourite(p, true); app.kept.setFavourite(p, true)
        assertEquals("on, asked twice: still on", listOf(p), app.stored.favourites)
        app.kept.setFavourite(p, false); app.kept.setFavourite(p, false)
        assertTrue(app.stored.favourites.isEmpty())
        app.kept.setHome(p)
        assertTrue(app.stored.isHome(p)); assertTrue(app.kept.isHomePlace(hut))
        assertEquals("Place menu: \"Hütte\" set as home", problems.traced("search").last())
        app.kept.setHome(null)
        assertFalse(app.stored.isHome(p))
        assertEquals("Place menu: home removed", problems.traced("search").last())
    }

    @Test fun aStoreThatCannotWriteReportsAndTellsNobodyAndKeepsWhatWasShown() {
        val app = App(listOf(a))
        assertFalse(app.kept.isFavouritePlace(hut))
        val shown = app.kept.savedPlaces
        app.storeFails = java.io.IOException("kept places not written")
        app.log.clear()
        app.kept.toggleFavouritePlace(hut)
        assertEquals(listOf("store.update fails"), app.log) // no backup, no sync
        assertEquals(shown, app.kept.savedPlaces)
        assertEquals(listOf("Favourite places / Zuhause could not be saved"), problems.said("search", com.onpath.app.debug.Problems.Level.ERROR))
        // latent (kept as it was, reported): the gesture log still says „kept as a favourite“ although nothing was saved
        assertEquals("Place menu: \"Hütte\" kept as a favourite", problems.traced("search").last())
        assertEquals(emptyList<Place>(), app.stored.favourites)
    }
}
