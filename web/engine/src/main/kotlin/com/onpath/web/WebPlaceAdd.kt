package com.onpath.web

import com.onpath.app.data.Geo
import com.onpath.app.domain.HoldMeasure
import com.onpath.app.domain.PlaceAddMenu
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.search.Place
import com.onpath.app.ui.plan.AddRole
import com.onpath.app.ui.plan.PlaceAddPorts
import com.onpath.app.ui.plan.PlaceAddState
import com.onpath.app.ui.plan.PlaceMenusPorts
import com.onpath.app.ui.plan.PlaceMenusState

/**
 * The app's PlaceAddState + PlaceMenusState (umbau's `XState` cut, docs/design/web-ready-status.md §6) for one question of
 * the page – a hold on the map, a place menu's rows, an add from a menu or the place card – on the plan the page shows now
 * ([points], the routed line [route]): the shared code decides, the page commits the plan it hands back (one Undo step in
 * the engine's history, as every edit). A fresh chain per question ([WebPlanChain] on its own scratch history), so a
 * decision never touches the plan's history and the page's plan stays the one truth.
 *
 * The hold's measure on the paths is the [HoldMeasure] port: here the routing worker measures at once
 * ([Engine.measureOrStraight] – with the planner's cut: a candidate leg over 30 km straight → the straight lines decide)
 * and answers in the same call. [activity] null = the straight lines only (the page's second look after the plan changed
 * twice while it waited, PlaceAddState's HoldTry.STRAIGHT on the page's side).
 */
class WebPlaceAdd(points: List<GeoPoint>, route: PlannedRoute?, private val activity: Activity?, private val sac: Int) {
    private val chain = WebPlanChain(points).also { it.line = route?.points }

    /** The page's measure: at once, in this worker; a cancelled one never answers (nothing runs after start returns). */
    private val measure = object : HoldMeasure {
        override val running: Boolean = false
        override fun start(points: List<GeoPoint>, s: GeoPoint, straight: PlaceAddMenu.Detours, router: HoldMeasure.Router,
                           done: (HoldMeasure.Outcome) -> Unit) {
            val m = if (activity == null) Engine.Measured(straight, false, 0, "no activity")
                else Engine.measureOrStraight(points, s, straight, activity, sac)
            done(HoldMeasure.Outcome(m.detours, m.onPaths, m.ms, m.failure))
        }
        override fun cancel(): Boolean = false
    }

    private val indexOfSpot: (Double, Double) -> Int = { lat, lon ->
        val p = GeoPoint(lat, lon)
        chain.plan.points.indexOfFirst { Geo.distance(it, p) < 1.0 }
    }

    val add = PlaceAddState(chain.plan, chain.edit, chain.planRun, object : PlaceAddPorts {
        override fun closeMapOverlays(why: String) = Unit // the page's menus
        override fun openPointMenu(index: Int, via: String) { pointMenu = index }
        override fun closePlaceCard() = Unit
        override fun planMode() = true // the planner is always Plan
        override fun canEditMap() = true
        override fun activity(): Activity = activity ?: Activity.HIKE
        override fun sac() = sac
        override fun routeLine(): List<GeoPoint>? = route?.points
        // no tour, no GPS track to follow
        override fun touring() = false
        override fun myPosition(): GeoPoint? = null
        override fun tourPassed(plan: List<GeoPoint>): Set<Int> = emptySet()
        override fun followLine(): List<GeoPoint>? = null
        override fun wayThereFrom(): GeoPoint? = null
        override fun gpsPoint(): GeoPoint? = null
        // names, pins and kinds are the page's (its meta follows the points by value)
        override fun searchMark(): Place? = null
        override fun takePin(lat: Double, lon: Double): Place? = null
        override fun isCandidate(poi: com.onpath.app.poi.Poi) = false
        override fun rememberPlaceName(p: GeoPoint, name: String) = Unit
        override fun rememberPoiKind(p: GeoPoint, kind: String?) = Unit
        override fun stopIndexAt(lat: Double, lon: Double): Int = indexOfSpot(lat, lon)
        override fun routeIndexOf(place: Place): Int? = indexOfSpot(place.lat, place.lon).takeIf { it >= 0 }
    }, { _, _, _, _, _ -> throw IllegalStateException("the planner measures through its HoldMeasure") }, measure)

    val menus = PlaceMenusState(chain.plan, chain.planRun, add, object : PlaceMenusPorts {
        override fun planMode() = true
        override fun touring() = false
        override fun activity(): Activity = activity ?: Activity.HIKE
        override fun sac() = sac
        override fun route(): PlannedRoute? = route
        override fun wayThereFrom(): GeoPoint? = null
        override fun routeIndexOf(place: Place): Int? = indexOfSpot(place.lat, place.lon).takeIf { it >= 0 }
        override var searchMark: Place? = null
        override var searchPlace: Place? = null
    }, { _, _, _, _, _ -> throw IllegalStateException("the planner measures through its HoldMeasure") })

    /** A point menu the shared code opened instead of an add (the place is a stop already): its index. */
    var pointMenu: Int? = null
        private set

    /** The plan now (after an add: one change more than the page's). */
    val points: List<GeoPoint> get() = chain.plan.points.toList()

    /** Runs [block] (a shared decision) and returns what it traced under "plan", for the page's diagnostics („why“). */
    fun traced(block: () -> Unit): String {
        val lines = ArrayList<String>()
        val before = com.onpath.app.debug.Problems.tap
        com.onpath.app.debug.Problems.tap = { area, message -> if (area == "plan") lines += message; before?.invoke(area, message) }
        try { block() } finally { com.onpath.app.debug.Problems.tap = before }
        return lines.joinToString(" · ")
    }

    companion object {
        fun poi(lat: Double, lon: Double, title: String, kind: String) =
            com.onpath.app.poi.Poi(id = "web", kind = kind.ifEmpty { "place" }, name = title.ifEmpty { null }, lat = lat, lon = lon)

        fun place(lat: Double, lon: Double, title: String, kind: String) =
            Place(id = "web", name = title.ifEmpty { null }, kind = kind.ifEmpty { "place" }, lat = lat, lon = lon)

        fun role(name: String): AddRole = when (name) { "START" -> AddRole.START; "DESTINATION" -> AddRole.DESTINATION; else -> AddRole.SMART }
    }
}
