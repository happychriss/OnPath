package com.onpath.app.ui.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import com.onpath.app.domain.HoldMeasure
import com.onpath.app.domain.PlaceAddMenu
import com.onpath.app.domain.TakenPin
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.navigation.TourSplit
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanEdits
import com.onpath.app.routing.RouteCancel
import com.onpath.app.routing.WaypointInsertion
import com.onpath.app.ui.WholeRoute

/**
 * The router [PlaceAddSlice] measures a hold beside the plan on (the engine, its leg cache and the Planner stay in :app).
 * Blocking – called on the hold's own thread ([HoldPaths]).
 */
fun interface HoldRouter {
    /**
     * The routed length [a] → [b] for [act] / [level] in metres: from the leg cache, else one leg by the planner (then in the
     * cache for the run after the decision). Throws when the leg has no route (a straight leg: no routing data, no way near the
     * spot, an island) or [cancel] fired.
     */
    fun routedLength(a: GeoPoint, b: GeoPoint, act: Activity, level: Int, cancel: RouteCancel): Double
}

/**
 * What [PlaceAddSlice] asks from the rest of the app (main thread): the map's menus and the place card ([PlaceMenusSlice],
 * piece 12; the view model's adapters call it), the tour (what a new point means during a tour in progress – pieces 16–19), the route
 * the plan shows and the search's found place. Every call is made at the point of the old code's sequence.
 */
interface PlaceAddPorts {
    // ---- map menus and the place card ([PlaceMenusSlice], piece 12)
    /** Closes every map menu ([why] into the log) – the hold / place menu's own state is the slice's, the rest the view model's. */
    fun closeMapOverlays(why: String = "closed")
    /** The point menu of route point [index] opens ([via] = what opened it, for the gesture log). */
    fun openPointMenu(index: Int, via: String)
    fun closePlaceCard()

    // ---- the app's facts
    /** The map is in Plan mode. */
    fun planMode(): Boolean
    /** The map's plan can be edited (Plan mode, no shown track). */
    fun canEditMap(): Boolean
    fun activity(): Activity
    fun sac(): Int
    /** The route line of the plan now, null = none. */
    fun routeLine(): List<GeoPoint>?

    // ---- the tour (pieces 16-19)
    /** A tour is recording. */
    fun touring(): Boolean
    /** Current position (the map's GPS), else the tour's last recorded point; null without either. */
    fun myPosition(): GeoPoint?
    /** The indices of the plan points a tour has passed ([plan] = the points asked about); empty without a tour. */
    fun tourPassed(plan: List<GeoPoint>): Set<Int>
    /** The followed line, null = none. */
    fun followLine(): List<GeoPoint>?
    /** The position while a tour is led to this plan and never reached it (a new point on the way becomes the start), else null. */
    fun wayThereFrom(): GeoPoint?
    /** The map's GPS fix, null without one. */
    fun gpsPoint(): GeoPoint?

    // ---- search (the found place, the names)
    /** The found place on the map (its pin). */
    fun searchMark(): com.onpath.app.search.Place?
    /** The found place's pin at ([lat], [lon]) goes into the plan: the pin goes; returns that place (null = no pin there). */
    fun takePin(lat: Double, lon: Double): com.onpath.app.search.Place?
    fun isCandidate(poi: com.onpath.app.poi.Poi): Boolean
    fun rememberPlaceName(p: GeoPoint, name: String)
    fun rememberPoiKind(p: GeoPoint, kind: String?)
    /** Index of the plan point at ([lat], [lon]) – a POI, place or search mark that became a route point keeps its exact coordinate – or -1. */
    fun stopIndexAt(lat: Double, lon: Double): Int
    /** Index of [place] in the plan, null = not a point. */
    fun routeIndexOf(place: com.onpath.app.search.Place): Int?
}

/** What a place card's "add" does with the place ([PlaceAddSlice.addPlace]). */
enum class AddRole { SMART, START, DESTINATION }

/**
 * Adding points to the plan (rebuild phase 2 piece 11, core feature C2: the hold on the map every tour starts with): where a
 * new point goes ([insertionFor]), the add entries of a place / a held spot ("Einfügen", "Neues Ziel", "Start here", "Go here"
 * from my location), the hold on the map with its two-row menu and the measuring of a hold beside the plan on the router's
 * real paths, the places added from the menu / the card, and the first-point menu. Every add is ONE [PlanEditSlice.change]
 * and writes [PlanEditSlice.pinTaken] after it. The decisions are rules in :core (WaypointInsertion, PlaceAddMenu,
 * HoldPaths, TakenPin, WholeRoute). The POI / point / pin menus, the place card and the kept places are [PlaceMenusSlice] and
 * [KeptPlacesSlice] (piece 12); this slice reaches them behind [PlaceAddPorts]. Constructed with the view model (no work).
 * Coroutine-free (for the planner): the measure sits behind the port [HoldMeasure] ([holdPaths]; the app's [PlaceAddSlice] hands in core's `HoldPaths`, the planner its worker's).
 */
open class PlaceAddState(
    private val plan: PlanDocument,
    private val planEdit: PlanEditState,
    private val planRun: PlanRunSlice,
    private val host: PlaceAddPorts,
    private val router: HoldRouter,
    /** The measure of a hold beside the plan ([measureHold]) and of a held place ([PlaceMenuMeasure], [PlaceMenusSlice.openPoiMenu]). */
    val holdPaths: HoldMeasure,
) {
    private val waypoints: List<GeoPoint> get() = plan.points

    /** The view model ends: a running measure stops ([HoldPaths.cancel]). */
    fun close() { holdPaths.cancel() }

    /** A hold on the empty map in a doubtful spot (owner 2026-11-15): the spot, its two rows and the hold's snap distance. */
    class HoldAdd(val p: GeoPoint, val menu: PlaceAddMenu.Menu, val lineSnapM: Double)

    /** The open „Einfügen“ / „Neues Ziel“ menu of a hold on the empty map; null = none ([holdOnMap]). The map's overlay closes it too. */
    var holdAddMenu by mutableStateOf<HoldAdd?>(null)

    /** First hold on an empty plan: the held spot, its menu "Start here · Go here (from my location)" is open. */
    var firstPointMenu by mutableStateOf<GeoPoint?>(null)

    /**
     * A hold beside the plan being measured on the paths ([measureHold]) or a held place's rows being decided ([PlaceMenusSlice.openPoiMenu],
     * which sets it until it moves): the spot, a ring turns on it; null = none.
     */
    var holdMeasuring by mutableStateOf<GeoPoint?>(null)

    /**
     * Adds a held point where it most likely belongs (WaypointInsertion, F-PLAN-13), silently: the hold's vibration and
     * the new line confirm it; a wrong guess is fixed with Undo or by dragging ≡ in the Points list.
     * @param lineSnapM "on the line" distance, ~24 dp in metres at the current zoom.
     */
    fun addPoint(p: GeoPoint, lineSnapM: Double = 30.0, onPoi: Boolean = false) {
        if (onPoi) plan.pin(p)
        val ins = insertionFor(p, lineSnapM)
        if (ins.decision.kind == WaypointInsertion.Kind.ON_THE_WAY)
            Problems.trace("plan", "New point on the way there (led to a route never reached): a stop before ①, the tour " +
                "is led via it (a round trip's loop stays, owner 2026-11-03)")
        planEdit.change { add(ins.index.coerceIn(0, size), p) }
    }

    /**
     * Where a new point [p] goes ([addPoint]): [points] and [line] = what the decision was made on, [index] = the place in
     * the plan. [inTour]: a tour passed plan points – only the points ahead count, from the position.
     */
    class Insertion(val points: List<GeoPoint>, val line: List<GeoPoint>?, val decision: WaypointInsertion.Decision,
                    val index: Int, val inTour: Boolean)

    fun insertionFor(p: GeoPoint, lineSnapM: Double, stopOnly: Boolean = false): Insertion {
        val before = waypoints.toList()
        // during a tour a new point goes only among the points ahead, never behind the user (TourSplit)
        val passed = host.tourPassed(before)
        val pos = host.myPosition()
        if (passed.isNotEmpty() && pos != null) {
            val ahead = listOf(pos) + TourSplit.ahead(before, passed)
            // the followed line fits these points only once it runs from the position (else best fit by geometry)
            val line = host.followLine()?.takeIf { it.isNotEmpty() && Geo.distance(it.first(), pos) < 50.0 }
            val d = WaypointInsertion.decide(ahead, p, line, lineSnapM, stopOnly = stopOnly)
            return Insertion(ahead, line, d, TourSplit.insertIndex(passed, d.index), inTour = true)
        }
        // While a reroute runs, the shown line belongs to older waypoints: use best-fit only.
        val line = if (planRun.routing) null else host.routeLine()
        val d = WaypointInsertion.decide(before, p, line, lineSnapM, host.wayThereFrom(), stopOnly = stopOnly)
        return Insertion(before, line, d, d.index, inTour = false)
    }

    /**
     * How far [p] is from the drawn line on screen, dp ([lineSnapM] = metres in [PlaceAddMenu.LINE_SNAP_DP] there): the
     * shown route, while a reroute runs (its line belongs to older points) the straight legs.
     */
    fun lineDpOf(p: GeoPoint, lineSnapM: Double): Double {
        val mPerDp = lineSnapM / PlaceAddMenu.LINE_SNAP_DP
        val line = (if (planRun.routing) null else host.routeLine())?.takeIf { it.size >= 2 } ?: waypoints.toList()
        return Geo.distanceToLine(p, line) / mPerDp
    }

    /**
     * How clear the case of a new point [p] is ([PlaceAddMenu.zone]), measured on the route still to go – during a tour from
     * my position; null with fewer than 2 points. The one measure of a held place's menu and of a hold on the map.
     */
    fun zoneFor(p: GeoPoint): PlaceAddMenu.Zone? {
        if (waypoints.size < 2) return null
        val touring = host.touring()
        val ins = insertionFor(p, 30.0)
        val pos = host.myPosition()
        // during a tour the route to go starts here (TourSplit's ahead list already does; else put my position first)
        val ahead = if (ins.inTour || !touring || pos == null) ins.points else listOf(pos) + ins.points
        return PlaceAddMenu.zone(ahead, p, loop = PlanEdits.isRoundTrip(waypoints))
    }

    /** Place menu → „Einfügen“: where it fits best on the route ahead, never behind the goal (the goal stays). */
    fun addPoiAsStop(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0) =
        addPoiWith(poi, "as a stop") { p -> insertAsStop(p, lineSnapM) }

    /**
     * Place menu → „Neues Ziel“ (owner 2026-11-14): while planning (no tour) the route goes on to the place and the old
     * goal stays as the last stop – planning forward; during a tour the place replaces the old goal (no detour back to a
     * goal one changed one's mind about – flow map N3, owner 2026-11-07). [PlaceAddMenu.keepsOldGoal]; Undo either way.
     */
    fun poiAsNewDestination(poi: com.onpath.app.poi.Poi) = addPoiWith(poi, "as the new destination") { p -> asNewDestination(p) }

    /** „Einfügen“ of a place or a held spot: the stop-only insertion on the route ahead (the goal stays). */
    private fun insertAsStop(p: GeoPoint, lineSnapM: Double) {
        val ins = insertionFor(p, lineSnapM, stopOnly = true)
        planEdit.change { add(ins.index.coerceIn(0, size), p) }
    }

    /** „Neues Ziel“ of a place or a held spot: appended while planning, replaces the goal on a tour. */
    private fun asNewDestination(p: GeoPoint) {
        val keep = PlaceAddMenu.keepsOldGoal(touring = host.touring())
        planEdit.change { if (isEmpty() || keep) add(p) else set(lastIndex, p) }
    }

    /**
     * A hold on the empty map in Plan ([MapGestures.Action.AddPoint], core feature C1), owner 2026-11-15 / 11-17. While
     * planning [PlaceAddMenu.planningHold] decides (owner 2026-11-17 21:40: „die kürzere gewinnt; nur fast gleich →
     * fragen“): on the line → the smart insertion, else the shorter of appending / inserting at once, within 20 % of each
     * other → the small menu „Einfügen“ / „Neues Ziel“, the cheaper first;
     * on a tour or a round trip the zone does ([PlaceAddMenu.asksOnHold]). Back / a tap elsewhere adds nothing.
     * Not on the line, the detours are measured on the real paths first ([measureHold], owner 2026-11-17).
     */
    fun holdOnMap(p: GeoPoint, lineSnapM: Double) {
        host.closeMapOverlays("hold on the map (new point)")
        decideHold(p, lineSnapM, HoldTry.FIRST)
    }

    /**
     * Which decision of one hold this is ([umbau]'s finding 2026-11-20, owner: never lose the user's input silently): the
     * first measure, the one again after the plan changed under it (the first route's snap), then the straight lines.
     */
    private enum class HoldTry { FIRST, AGAIN, STRAIGHT }

    /** [holdOnMap]'s decision on the plan shown now; [tryNo] says whether the paths may still be measured. */
    private fun decideHold(p: GeoPoint, lineSnapM: Double, tryNo: HoldTry) {
        val n = waypoints.size
        val touring = host.touring()
        if (n < 2) {
            Problems.trace("plan", "New point by a hold: plan $n → ${n + 1}")
            addPoint(p, lineSnapM)
            return
        }
        val menu = if (touring || PlanEdits.isRoundTrip(waypoints)) {
            // on a tour / a round trip: the zone on the route ahead decides (a loop is never dropped by one hold)
            val zone = zoneFor(p)
            if (!PlaceAddMenu.asksOnHold(zone)) {
                Problems.trace("plan", "New point by a hold: plan $n → ${n + 1} (clear case $zone: set at once)")
                addPoint(p, lineSnapM)
                return
            }
            Problems.trace("plan", "Hold on the map in doubt (zone $zone, $n points)")
            PlaceAddMenu.menu(n, touring, zone)
        } else {
            // planning: „die kürzere gewinnt; nur fast gleich → fragen“ – on the line at once, else on the real paths
            val lineDp = lineDpOf(p, lineSnapM)
            val d = PlaceAddMenu.detours(waypoints.toList(), p)!!
            if (PlaceAddMenu.planningHold(lineDp, d) == PlaceAddMenu.HoldAction.ON_LINE) {
                Problems.trace("plan", "New point by a hold: on the line, set at once (line ${lineDp.toInt()} dp, $n points)")
                addPoint(p, lineSnapM); return
            }
            if (tryNo == HoldTry.STRAIGHT) applyPlanningHold(p, lineSnapM, lineDp, d, "line ${lineDp.toInt()} dp, straight lines" +
                ", insert +${d.insertM.toInt()} m, append ${d.appendM.toInt()} m, $n points")
            else measureHold(p, lineSnapM, lineDp, d, tryNo)
            return
        }
        holdAddMenu = HoldAdd(p, menu, lineSnapM)
        Problems.trace("plan", "Hold on the map: asks ${menu.main.joinToString(" / ")}")
    }

    /** Stops a running measure of a hold ([measureHold]): its result is never applied. */
    fun cancelHoldMeasure(why: String) {
        if (holdPaths.cancel()) Problems.trace("plan", "Hold beside the plan: measure on the paths stopped ($why)")
        holdMeasuring = null
    }

    /**
     * A hold beside the plan while planning, not on the line (owner 2026-11-17 „Always real paths“, F-PLAN-15): appending
     * and inserting are measured on the router's paths off the main thread ([HoldPaths], the legs from the leg cache and into
     * it – the plan run after the decision finds them); a ring turns on the spot meanwhile. Then [PlaceAddMenu.planningHold]
     * decides on those numbers. A timeout or a failure keeps the straight lines [straight]; a newer hold, Undo, an edit
     * or leaving Plan stops it. A result for a plan no longer shown (the first route snapped its points, another activity
     * or level, a tour started) is never applied: the hold is decided again on the plan now shown – measured once more,
     * after a second such change on the straight lines – so it always ends in a point or the menu ([umbau] 2026-11-20).
     */
    private fun measureHold(p: GeoPoint, lineSnapM: Double, lineDp: Double, straight: PlaceAddMenu.Detours, tryNo: HoldTry) {
        val pts = waypoints.toList()
        val act = host.activity(); val level = host.sac()
        holdMeasuring = p
        holdPaths.start(pts, p, straight, { a, b, cancel -> router.routedLength(a, b, act, level, cancel) }) { o ->
            holdMeasuring = null
            if (!host.planMode()) {
                Problems.trace("plan", "Hold beside the plan: measured, but Plan was left – nothing added (${o.describe()})")
                return@start
            }
            if (waypoints.toList() != pts || host.touring() || host.activity() != act || host.sac() != level) {
                if (tryNo == HoldTry.FIRST) {
                    Problems.trace("plan", "Hold beside the plan: measured for a plan no longer shown – measured again on the plan now shown (${o.describe()})")
                    decideHold(p, lineSnapM, HoldTry.AGAIN)
                } else {
                    Problems.trace("plan", "Hold beside the plan: measured again for a plan no longer shown – the straight lines on the plan now shown decide (${o.describe()})")
                    decideHold(p, lineSnapM, HoldTry.STRAIGHT)
                }
                return@start
            }
            val d = o.detours
            val why = "line ${lineDp.toInt()} dp, ${o.describe()}" +
                (if (o.onPaths) "; straight +${straight.insertM.toInt()} / ${straight.appendM.toInt()} m" else
                    ", insert +${d.insertM.toInt()} m" + (d.earlyInsertM?.let { " (earlier legs +${it.toInt()} m)" } ?: "") +
                        ", append ${d.appendM.toInt()} m") + ", ${pts.size} points"
            applyPlanningHold(p, lineSnapM, lineDp, d, why)
        }
    }

    /** [PlaceAddMenu.planningHold] on the detours [d] (measured or straight): a point at once or the small menu. */
    private fun applyPlanningHold(p: GeoPoint, lineSnapM: Double, lineDp: Double, d: PlaceAddMenu.Detours, why: String) {
        when (PlaceAddMenu.planningHold(lineDp, d)) {
            PlaceAddMenu.HoldAction.ON_LINE -> addPoint(p, lineSnapM) // not reached: on the line is set before measuring
            PlaceAddMenu.HoldAction.NEW_GOAL -> {
                Problems.trace("plan", "New point by a hold: appending clearly shorter, the new goal at once ($why)")
                asNewDestination(p)
            }
            PlaceAddMenu.HoldAction.STOP -> {
                Problems.trace("plan", "New point by a hold: inserting clearly shorter, a stop at once ($why)")
                insertAsStop(p, lineSnapM)
            }
            PlaceAddMenu.HoldAction.ASK -> {
                Problems.trace("plan", "Hold on the map in doubt ($why)")
                val menu = PlaceAddMenu.planningMenu(d)
                holdAddMenu = HoldAdd(p, menu, lineSnapM)
                Problems.trace("plan", "Hold on the map: asks ${menu.main.joinToString(" / ")}")
            }
        }
    }

    /** The hold menu's „Einfügen“. */
    fun holdAsStop() {
        val a = holdAddMenu ?: return
        holdAddMenu = null
        Problems.trace("plan", "Hold menu: Einfügen (plan ${waypoints.size} → ${waypoints.size + 1})")
        insertAsStop(a.p, a.lineSnapM)
    }

    /** The hold menu's „Neues Ziel“. */
    fun holdAsNewDestination() {
        val a = holdAddMenu ?: return
        holdAddMenu = null
        Problems.trace("plan", "Hold menu: Neues Ziel (${if (host.touring()) "replaces the goal on the tour" else "appended"})")
        asNewDestination(a.p)
    }

    /** The bookkeeping of a place taken from its menu (name, pin, candidate fit), then [edit] with its point. */
    private fun addPoiWith(poi: com.onpath.app.poi.Poi, how: String, edit: (GeoPoint) -> Unit) {
        val candidate = host.isCandidate(poi)
        host.closeMapOverlays()
        val idx = host.stopIndexAt(poi.lat, poi.lon)
        if (idx >= 0) { host.openPointMenu(idx, via = "POI that is a stop"); return }
        val p = GeoPoint(poi.lat, poi.lon)
        host.rememberPlaceName(p, com.onpath.app.search.Place.fromPoi(poi).title())
        host.rememberPoiKind(p, poi.kind)
        val pin = host.takePin(poi.lat, poi.lon)
        plan.pin(p)
        val before = waypoints.size
        if (candidate) fittingPlaceAdd { edit(p) } else edit(p)
        planEdit.pinTaken = pin?.let { TakenPin(it, p) }
        Problems.trace("plan", "Place menu: \"${poi.name ?: poi.kind}\" $how (plan $before → ${waypoints.size} points)")
    }

    /**
     * The candidate marker's dashed preview line (owner 2026-10-31): from the search result / shared place to where
     * adding it would join the route – the same decision as [addPoint] (WaypointInsertion.joinPoint). null = no line:
     * no candidate, not in Plan, no route yet or one being computed, or the place already is a route point.
     */
    fun candidateJoin(): Pair<GeoPoint, GeoPoint>? {
        val m = host.searchMark() ?: return null
        if (!host.canEditMap() || waypoints.size < 2) return null
        if (host.stopIndexAt(m.lat, m.lon) >= 0) return null
        val p = GeoPoint(m.lat, m.lon)
        val ins = insertionFor(p, 30.0)
        return WaypointInsertion.joinPoint(ins.points, p, ins.line, ins.decision)?.let { p to it }
    }

    fun openFirstPointMenu(p: GeoPoint) {
        host.closeMapOverlays("first-point menu opened")
        if (waypoints.isEmpty()) { firstPointMenu = p; Problems.trace("plan", "First-point menu opened") }
    }

    /** First-point menu → "Start here": the held spot is the start; later holds add points as usual. */
    fun startHere(p: GeoPoint) { firstPointMenu = null; addPoint(p) }

    /** First-point menu → "Go here": start = current position, the held spot = destination. */
    fun routeFromMyLocation(p: GeoPoint) {
        firstPointMenu = null
        val loc = host.gpsPoint() ?: return // the menu only offers this with a position
        if (waypoints.isEmpty()) planEdit.change { add(loc); add(p) } else addPoint(p)
    }

    /** POI menu on an empty plan → "Go here": start = position, the POI (pinned, named) = destination. */
    fun poiFromMyLocation(poi: com.onpath.app.poi.Poi) {
        host.closeMapOverlays()
        val loc = host.gpsPoint() ?: return // the menu only offers this with a position
        if (waypoints.isNotEmpty()) { addPoiPoint(poi); return }
        val p = GeoPoint(poi.lat, poi.lon)
        host.rememberPlaceName(p, com.onpath.app.search.Place.fromPoi(poi).title())
        host.rememberPoiKind(p, poi.kind)
        val pin = host.takePin(poi.lat, poi.lon)
        plan.pin(p)
        planEdit.change { add(loc); add(p) }
        planEdit.pinTaken = pin?.let { TakenPin(it, p) }
        Problems.trace("plan", "Go here from my location: \"${poi.name ?: poi.kind}\" (plan 0 → ${waypoints.size} points)")
    }

    fun addPoiPoint(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0) {
        // the candidate marker's menu (a search result / shared place, maybe far off): the new route is fitted
        val candidate = host.isCandidate(poi)
        host.closeMapOverlays()
        val idx = host.stopIndexAt(poi.lat, poi.lon)
        if (idx >= 0) { host.openPointMenu(idx, via = "POI that is a stop"); return }
        val p = GeoPoint(poi.lat, poi.lon)
        host.rememberPlaceName(p, com.onpath.app.search.Place.fromPoi(poi).title())
        host.rememberPoiKind(p, poi.kind) // the symbol at once, not only after the 5 m index lookup ([umbau]'s finding 2026-11-19)
        val pin = host.takePin(poi.lat, poi.lon)
        val before = waypoints.size
        if (candidate) fittingPlaceAdd { addPoint(p, lineSnapM, onPoi = true) } else addPoint(p, lineSnapM, onPoi = true)
        planEdit.pinTaken = pin?.let { TakenPin(it, p) }
        Problems.trace("plan", "POI menu: \"${poi.name ?: poi.kind}\" added to the route (plan $before → ${waypoints.size} points)")
    }

    /**
     * "Start here" on planned point [index] – the map's point menu and the search card's "As start" (owner:
     * the same word does the same, [PlanEdits.startHere]); one Undo step brings the plan back.
     */
    fun startAtPoint(index: Int) {
        val w = waypoints.toList()
        val next = PlanEdits.startHere(w, index)
        if (next != w) planEdit.set(next)
    }

    /**
     * [block] adds a place from the place card or the candidate marker's menu: the new route is fitted once computed
     * ([WholeRoute.fitAfterPlace], the same fit as the first route).
     */
    private inline fun fittingPlaceAdd(block: () -> Unit) {
        val before = waypoints.toList()
        block()
        if (WholeRoute.fitAfterPlace(before, waypoints.toList())) planRun.routeEvent(RouteRun.Event.FitNext)
    }

    /** Adds the place as a pinned stop (passed exactly), named after the place. Moves it when already planned. */
    fun addPlace(place: com.onpath.app.search.Place, role: AddRole) = fittingPlaceAdd {
        val p = GeoPoint(place.lat, place.lon)
        val name = place.title()
        val idx = host.routeIndexOf(place)
        host.closePlaceCard()
        host.rememberPlaceName(p, name)
        host.rememberPoiKind(p, place.poi?.kind ?: place.kind)
        // taken: the candidate marker goes (the numbered route point shows the place now)
        val pin = host.takePin(place.lat, place.lon)
        if (idx != null) {
            // "As start" = the point menu's "Start here" (owner); "As destination" moves it to the end
            when (role) { AddRole.START -> startAtPoint(idx); AddRole.DESTINATION -> planEdit.makeDestination(idx); AddRole.SMART -> Unit }
            return@fittingPlaceAdd
        }
        plan.pin(p)
        when (role) {
            // No timed notices (owner rule): the named marker and the new line show what happened.
            AddRole.SMART -> addPoint(p, onPoi = true)
            AddRole.START -> planEdit.change { add(0, p) }
            AddRole.DESTINATION -> {
                val loc = host.gpsPoint()
                if (waypoints.isEmpty() && loc != null) planEdit.change { add(loc); add(p) }
                else planEdit.change { add(p) }
            }
        }
        planEdit.pinTaken = pin?.let { TakenPin(it, p) }
        Problems.trace("plan", "Place card: \"$name\" added (${role.name.lowercase()}, plan now ${waypoints.size} points)")
    }
}
