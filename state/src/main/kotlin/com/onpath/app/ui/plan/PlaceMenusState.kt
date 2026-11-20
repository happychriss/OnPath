package com.onpath.app.ui.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.debug.Problems
import com.onpath.app.domain.PlaceAddMenu
import com.onpath.app.domain.PlaceMenuMeasure
import com.onpath.app.domain.PointMenuTarget
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanEdits
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.WaypointInsertion
import com.onpath.app.search.Place
import com.onpath.app.ui.search.PlaceCardUi

/**
 * The offline place index as [PlaceMenusSlice.refreshPinnedKinds] asks it (the index itself stays in :app): the places of the
 * wanted kinds inside a box. Blocking – called on the IO dispatcher.
 */
fun interface PlaceLookup {
    fun placesIn(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, kinds: Set<String>): List<Place>
}

/**
 * What [PlaceMenusSlice] asks from the rest of the app (main thread): the app's facts, the route the plan shows, the
 * tour's one answer and the search's found place and picked place (the search's state stays in the search flow). Every
 * call is made at the point of the old code's sequence.
 */
interface PlaceMenusPorts {
    /** The map is in Plan mode. */
    fun planMode(): Boolean
    /** A tour is recording. */
    fun touring(): Boolean
    fun activity(): Activity
    fun sac(): Int
    /** The route the plan shows (its line, its length and time), null = none. */
    fun route(): PlannedRoute?
    /** The position while a tour is led to this plan and never reached it (a new point on the way becomes the start), else null. */
    fun wayThereFrom(): GeoPoint?
    /** Index of [place] in the plan, null = not a point (the search's same-spot rule). */
    fun routeIndexOf(place: Place): Int?
    /** The found place on the map (its pin). */
    var searchMark: Place?
    /** The picked search result (address, kind) behind the place card; null for a POI tapped on the map. */
    var searchPlace: Place?
}

/**
 * The menus of a held place, a route point and the found place's pin, the place card and the POI kinds of pinned points
 * (rebuild phase 2 piece 12, core feature C19; the add itself is [PlaceAddSlice]): which menu is open and what it offers –
 * a held place's add rows decided on the router's real paths ([PlaceMenuMeasure], owner 2026-11-17), the point menu's target
 * that follows its point, "Start here" of the point menu, the pin's removal – closing every map menu, what the card says, and
 * the kind of each pinned POI (owner 2026-11-10). The hold's own state ([PlaceAddSlice.holdAddMenu], `firstPointMenu`,
 * `holdMeasuring`) stays in [PlaceAddSlice], the search's pin and picked place in the search flow (host). Constructed with the
 * view model (no work).
 */
open class PlaceMenusState(
    private val plan: PlanDocument,
    private val planRun: PlanRunSlice,
    private val placeAdd: PlaceAddSlice,
    private val host: PlaceMenusPorts,
    /** The router a held place's rows are measured on (the same as the hold's). */
    private val router: HoldRouter,
) {
    private val waypoints: List<GeoPoint> get() = plan.points

    /** Search result shown in the place card (search, ui/search); map-held POIs use [poiMenu]. */
    var planPoi by mutableStateOf<com.onpath.app.poi.Poi?>(null)

    /** POI held on the map in Plan: its menu "Add to route · Open in Google Maps". */
    var poiMenu by mutableStateOf<com.onpath.app.poi.Poi?>(null)

    /** The route point whose point menu is open (hold on its marker, MapGestures): index when opened + position. */
    var pointMenuTarget by mutableStateOf<PointMenuTarget?>(null)
        private set

    /** Index of that point in the plan now, or null (no menu, or its point is gone). */
    val pointMenu: Int? get() = pointMenuTarget?.indexIn(waypoints)

    /** The router snapped points ([moves] old → new): an open point menu follows its point to the snapped position. */
    fun followSnapped(moves: Map<GeoPoint, GeoPoint>) { pointMenuTarget = pointMenuTarget?.afterSnap(moves) }

    /** The add rows decided for an open place menu while planning ([openPoiMenu]): for [poi] beside the plan [points]. */
    private class PoiRows(val poi: com.onpath.app.poi.Poi, val points: List<GeoPoint>, val menu: PlaceAddMenu.Menu, val lineSnapM: Double)
    private var poiMenuRows: PoiRows? = null

    /**
     * The add entries of a held place's menu ([PlaceAddMenu], owner 2026-11-07 / 2026-11-15 / 2026-11-17): on a tour or a
     * round trip with 2+ points „Einfügen“ and „Neues Ziel“, the likelier first by how clear the case is – measured on the
     * route still to go (during a tour from my position). While planning (owner 2026-11-17: „wenn ich da nicht einfügen
     * muss, dann braucht das auch im Menü nicht angezeigt zu werden“) only the matching row in a clear case, both in
     * between – the rows [openPoiMenu] decided on the paths ([PlaceMenuMeasure]); for a plan changed since (or a menu not
     * opened by it) the same rule on straight lines.
     */
    fun placeMenu(poi: com.onpath.app.poi.Poi): PlaceAddMenu.Menu {
        val p = GeoPoint(poi.lat, poi.lon)
        val touring = host.touring()
        if (!touring && !PlanEdits.isRoundTrip(waypoints) && waypoints.size >= 2) {
            poiMenuRows?.takeIf { it.poi == poi && it.points == waypoints.toList() }?.let { return it.menu }
            val d = PlaceAddMenu.detours(waypoints.toList(), p)!!
            return PlaceAddMenu.planningPlaceMenu(placeAdd.lineDpOf(p, poiMenuRows?.lineSnapM ?: 30.0), d)
        }
        return PlaceAddMenu.menu(waypoints.size, touring, placeAdd.zoneFor(p))
    }

    val mapOverlayOpen: Boolean get() =
        poiMenu != null || pointMenu != null || placeAdd.firstPointMenu != null || placeAdd.holdAddMenu != null || planPoi != null

    /** Closes every map menu; [why] goes into the gesture log (Diagnostics) when a point menu was open. */
    fun closeMapOverlays(why: String = "closed") {
        // The found place's pin stays when its menu closes (owner 2026-11-02: a tap elsewhere closes only the menu); it
        // goes when the place is added, with a new search, "Neu planen" or the menu's "Nadel entfernen" ([removePin]).
        closePointMenu(why)
        if (placeAdd.firstPointMenu != null) Problems.trace("plan", "First-point menu closed: $why")
        if (poiMenu != null) Problems.trace("plan", "POI menu closed: $why")
        if (placeAdd.holdAddMenu != null) Problems.trace("plan", "Hold menu closed: $why (nothing added)")
        placeAdd.cancelHoldMeasure(why)
        poiMenu = null; poiMenuRows = null; placeAdd.firstPointMenu = null; placeAdd.holdAddMenu = null; if (planPoi != null) closePlaceCard()
    }

    /** Closes the point menu (if open) and logs by what (core feature C1 diagnostics). */
    fun closePointMenu(why: String) {
        val t = pointMenuTarget ?: return
        pointMenuTarget = null
        Problems.trace("plan", "Point menu closed (point ${t.index + 1}): $why")
    }

    /**
     * Opens a held place's menu (a POI on the map, the found pin – a hold, its chip or a search pick; core feature C19).
     * While planning with 2+ points (no tour, no round trip) its add rows are decided first, exactly as a hold on the empty
     * map (owner 2026-11-17, [PlaceMenuMeasure]): on the line at once, else measured on the paths with the ring on the
     * place ([PlaceAddSlice.holdMeasuring], same time limit, fallback and cancel as the hold's measure) – the menu opens with
     * its rows, nothing jumps. [lineSnapM] = metres in [PlaceAddMenu.LINE_SNAP_DP] on screen (30 m when the caller has no map at hand).
     * A result for a plan no longer shown (the first route snapped its points, another activity or level, a tour started)
     * is never applied: the menu is decided again on the plan now shown – measured once more, after a second such change
     * opened with its rows on the straight lines – so it always opens; only leaving Plan drops it (the hold's rule, 62e7064c5).
     */
    fun openPoiMenu(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0) {
        closeMapOverlays("POI menu opened")
        decidePoiMenu(poi, lineSnapM, again = false)
    }

    /** [openPoiMenu]'s decision on the plan shown now; [again] = this is the second measure after the plan changed under the first. */
    private fun decidePoiMenu(poi: com.onpath.app.poi.Poi, lineSnapM: Double, again: Boolean) {
        val p = GeoPoint(poi.lat, poi.lon)
        val pts = waypoints.toList()
        if (pts.size < 2 || host.touring() || PlanEdits.isRoundTrip(waypoints) || !host.planMode()) { poiMenu = poi; return }
        val act = host.activity(); val level = host.sac()
        val measuring = PlaceMenuMeasure.start(placeAdd.holdPaths, pts, p, placeAdd.lineDpOf(p, lineSnapM), PlaceAddMenu.detours(pts, p)!!,
            { a, b, cancel -> router.routedLength(a, b, act, level, cancel) }) { r ->
            placeAdd.holdMeasuring = null
            if (!host.planMode()) {
                Problems.trace("plan", "Place menu: measured, but Plan was left – not opened (${r.how})")
                return@start
            }
            if (waypoints.toList() != pts || host.touring() || host.activity() != act || host.sac() != level) {
                if (!again) {
                    Problems.trace("plan", "Place menu: measured for a plan no longer shown – measured again on the plan now shown (${r.how})")
                    decidePoiMenu(poi, lineSnapM, again = true)
                } else {
                    Problems.trace("plan", "Place menu: measured again for a plan no longer shown – opened, its rows on the straight lines (${r.how})")
                    poiMenuRows = null
                    poiMenu = poi
                }
                return@start
            }
            Problems.trace("plan", "Place menu of a ${poi.kind}: ${r.action} → ${r.menu.main.joinToString(" / ")} (${r.how}, ${pts.size} points)")
            poiMenuRows = PoiRows(poi, pts, r.menu, lineSnapM)
            poiMenu = poi
        }
        if (measuring) placeAdd.holdMeasuring = p
    }

    /** The pin's name chip tapped (owner 2026-11-02): the same menu a hold on the pin opens. */
    fun openPinMenu() {
        val m = host.searchMark ?: return
        Problems.trace("plan", "Pin chip tapped: menu of \"${m.title()}\"")
        openPoiMenu(m.toPoi())
    }

    /**
     * The found place's pin goes (owner 2026-11-02): its menu's "Nadel entfernen" or "Neu planen" (`clearPlan`); adding
     * the place (`clearSearchMarkAt`) and a new search pick replace it on their own.
     */
    fun removePin(why: String) {
        if (host.searchMark == null) return
        Problems.trace("plan", "Pin removed: $why")
        host.searchMark = null; host.searchPlace = null
    }

    /**
     * Opens the menu of route point [index]; [distDp] = finger distance from the marker, [via] = what opened it
     * (both only for the gesture log in Diagnostics).
     */
    fun openPointMenu(index: Int, distDp: Int? = null, via: String = "hold") {
        closeMapOverlays("another menu opened ($via)")
        val p = waypoints.getOrNull(index)
        if (p == null) { Problems.trace("plan", "Point menu NOT opened: point ${index + 1} not in the plan of ${waypoints.size}"); return }
        pointMenuTarget = PointMenuTarget(index, p)
        Problems.trace("plan", "Point menu opened ($via): point ${index + 1} of ${waypoints.size}" +
            (distDp?.let { ", $it dp from the finger" } ?: ""))
    }

    /** POI menu → "Add to route": a pinned, named route point; when it already is a stop, its point menu opens. */
    /** The POI menu's add item, saying what it does ("Set as destination", "Add as stop 2", …) – same rule as [PlaceAddSlice.addPoint]. */
    /**
     * What the POI menu's add action says – the SAME decision [PlaceAddSlice.addPoint] then takes ([PlaceAddSlice.insertionFor]:
     * during a tour only the points ahead count, from the position), so the label never promises something else (field
     * 2026-11-07: the label was decided on the whole plan and read "Als Start setzen" while the tour ran).
     */
    fun poiAddLabel(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0): String {
        val ins = placeAdd.insertionFor(GeoPoint(poi.lat, poi.lon), lineSnapM)
        return com.onpath.app.search.AddPreview.label(WaypointInsertion.Decision(ins.index, ins.decision.kind), waypoints.size)
    }

    /** Point menu → "Start here" (drops earlier points) / round trip "Start & finish here" (rotates the loop). */
    fun makeStartFromMenu() {
        val index = menuAction("Start here") ?: return
        placeAdd.startAtPoint(index)
    }

    /**
     * A point menu action was tapped: closes the menu and returns the index of the point it was opened for in the
     * plan as it is NOW (the plan can change under an open menu – PointMenuTarget), or null when that point is gone.
     * Logged either way, so a "tap did nothing" report can be traced.
     */
    fun menuAction(what: String): Int? {
        val t = pointMenuTarget
        val i = t?.indexIn(waypoints)
        pointMenuTarget = null
        Problems.trace("plan", "Point menu: $what tapped – menu of point ${t?.index?.plus(1)}, now point ${i?.plus(1)}, " +
            "plan ${waypoints.size} points, route ${if (planRun.routing) "calculating" else "ready"}")
        return i
    }

    // ---- the place card (UI in ui/search; the card is keyed by planPoi so MapHost's tap rules – a map tap closes it – apply)

    /** Place shown in the card: the search result, or the tapped POI. */
    val cardPlace: Place?
        get() = planPoi?.let { poi -> host.searchPlace?.takeIf { it.id == poi.id } ?: Place.fromPoi(poi) }

    fun closePlaceCard() { planPoi = null; host.searchPlace = null }

    /** Card content incl. what "Add to route" will do (same WaypointInsertion as a map hold). */
    fun placeCardUi(place: Place): PlaceCardUi {
        val idx = host.routeIndexOf(place)
        val r = host.route()
        if (idx != null) return PlaceCardUi(place, idx, waypoints.size,
            com.onpath.app.search.AddPreview.existingRole(idx, waypoints.size))
        val pv = com.onpath.app.search.AddPreview.of(waypoints.toList(), GeoPoint(place.lat, place.lon), if (planRun.routing) null else r?.points,
            r?.takeIf { it.durationS > 0 }?.let { it.distanceM / it.durationS }, here = host.wayThereFrom())
        // during a tour the add goes among the points ahead ([PlaceAddSlice.insertionFor]) – say what really happens
        val ins = placeAdd.insertionFor(GeoPoint(place.lat, place.lon), 30.0)
        val k = ins.decision.kind
        val label = if (ins.inTour) com.onpath.app.search.AddPreview.label(WaypointInsertion.Decision(ins.index, k), waypoints.size)
            else pv.label
        return PlaceCardUi(place, null, waypoints.size, if (ins.inTour) "" else pv.subtitle, label,
            addIsStart = k == WaypointInsertion.Kind.START,
            addIsDestination = k == WaypointInsertion.Kind.DESTINATION && waypoints.size <= 1)
    }

    // ---- POI kinds of pinned points
    /**
     * POI kinds of pinned points (owner 2026-11-10, variant A: the POI symbol stays, a blue ring + badge around it):
     * [poiKindsKnown] from the tap that pinned it, [poiKindsFound] from the offline index for the others (a plan loaded
     * from My routes, a link, a backup) – [refreshPinnedKinds]. Not stored: looked up again after a restart.
     */
    private var poiKindsKnown by mutableStateOf<Map<GeoPoint, String>>(emptyMap())
    private var poiKindsFound by mutableStateOf<Map<GeoPoint, String>>(emptyMap())
    private val poiKindsLookedUp = HashSet<GeoPoint>()
    /** Kind by point index for the pinned points that sit on a POI the map draws (map/MapController poi markers). */
    fun pinnedPoiKinds(): Map<Int, String> =
        com.onpath.app.poi.PinnedPoiKinds.byIndex(waypoints.toList(), plan.pinnedIndices(), poiKindsKnown, poiKindsFound)

    fun rememberPoiKind(p: GeoPoint, kind: String?) {
        if (com.onpath.app.poi.PinnedPoiKinds.isPoiKind(kind)) poiKindsKnown = poiKindsKnown + (p to kind!!)
    }

    /** The pinned points whose POI kind is not known and was not looked up yet (each point is asked once). */
    fun pinnedKindsTodo(): List<GeoPoint> = plan.pinnedIndices().mapNotNull { waypoints.getOrNull(it) }
        .filter { it !in poiKindsKnown && it !in poiKindsLookedUp }.distinct()

    /** The POI kind under each of [todo] in [index] (blocking: the index reads its file). */
    fun findPinnedKinds(todo: List<GeoPoint>, index: PlaceLookup): Map<GeoPoint, String> {
        val pad = com.onpath.app.data.Geo.degLat(3 * com.onpath.app.poi.PinnedPoiKinds.SAME_SPOT_M)
        val kinds = com.onpath.app.poi.PinnedPoiKinds.indexKinds
        return todo.mapNotNull { p ->
            val padLon = com.onpath.app.data.Geo.degLon(3 * com.onpath.app.poi.PinnedPoiKinds.SAME_SPOT_M, p.lat)
            val near = index.placesIn(p.lat - pad, p.lon - padLon, p.lat + pad, p.lon + padLon, kinds)
            com.onpath.app.poi.PinnedPoiKinds.lookup(p, near)?.let { p to it }
        }.toMap()
    }

    /** A lookup of [todo] ended with [found]: those points are not asked again, the found kinds show. */
    fun pinnedKindsFound(todo: List<GeoPoint>, found: Map<GeoPoint, String>) {
        poiKindsLookedUp += todo
        if (found.isNotEmpty()) poiKindsFound = poiKindsFound + found
    }
}
