package com.onpath.app.domain

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint

/**
 * What the menu of a held place (POI, found pin, shared place) offers to add it (owner 2026-11-07 11:20 / 11:25 / 11:30,
 * 2026-11-15, words and order 2026-11-26). Pure.
 *
 * The words (owner 2026-11-26, `docs/team/items/2026-11-26-context-menus-wording.md` – replaces the words of 2026-11-15
 * „Einfügen“ / „Neues Ziel“ that were „never reworded“): three words for points, **Start · Stopp · Ziel**, every route
 * action reads „Als …“.
 * - No point yet: „Als Start“ · „Als Ziel“ (↳ Start: dein Standort) – during a tour only „Als Ziel“ (the tour already
 *   runs from here).
 * - One point (or no route ahead): „Als Ziel“ (one entry).
 * - Two or more: „Als Stopp“ (the best place on the route ahead, the goal stays) and „Als Ziel“ (the route goes on there,
 *   the old goal stays as the last stop while planning; during a tour it replaces the old goal – owner 2026-11-14
 *   [keepsOldGoal]; Undo brings the plan before back). On a tour or a round trip both, the likelier one ([zone]) tinted.
 *   While PLANNING (owner 2026-11-17): only the matching row in a clear case, both in between – the rule of a hold on the
 *   empty map ([planningRows]). One line each, only the label – where it goes shows on the map; no stop numbers.
 *
 * [Menu.main] keeps the decision's order (the likelier first, [Menu.emphasised]); the menu SHOWS [Menu.rows]: the fixed
 * order Start → Stopp → Ziel (owner 2026-11-26: no more „cheaper first“, the likelier row is only shown by the tint).
 */
object PlaceAddMenu {

    enum class Item { START_HERE, GO_HERE, SET_DESTINATION, ADD_STOP, NEW_DESTINATION }

    /**
     * "Als neues Ziel" (owner 2026-11-14: „Beim Planen anhängen, unterwegs ersetzen“): while planning the old goal stays as
     * the last stop (one plans forward, step by step); during a tour the new place replaces it (no detour back).
     */
    fun keepsOldGoal(touring: Boolean): Boolean = !touring

    /** How clear the case is: only stop · both, stop first · both, goal first · only goal. */
    enum class Zone { STOP, STOP_FIRST, GOAL_FIRST, GOAL }

    /**
     * [main] = the add rows in the decision's order – with 2+ points both add rows, the likelier first ([emphasised]).
     * What the menu shows is [rows].
     */
    data class Menu(val main: List<Item>) {
        /** The likelier row (shown tinted): the first of the two add rows; null with one point or none. */
        val emphasised: Item? get() = main.firstOrNull()?.takeIf { main.size == 2 && it in ADD_ITEMS }

        /** The rows as the menu shows them: always Start → Stopp → Ziel (owner 2026-11-26), whatever is likelier. */
        val rows: List<Item> get() = main.sortedBy { SHOWN_ORDER.indexOf(it) }
    }

    private val ADD_ITEMS = setOf(Item.ADD_STOP, Item.NEW_DESTINATION)

    /** Start → Stopp → Ziel: the order every menu shows its route rows in (owner 2026-11-26). */
    private val SHOWN_ORDER = listOf(Item.START_HERE, Item.ADD_STOP, Item.SET_DESTINATION, Item.GO_HERE, Item.NEW_DESTINATION)

    // Thresholds on the detour d = (route ahead with the place as its best stop) − (route ahead), straight lines. They are
    // conservative: a case near a border is DOUBTFUL (both entries) rather than one entry that is wrong.

    /** A clear stop: d at most this share of the route ahead … */
    const val CLEAR_STOP_SHARE = 0.2

    /** … or at most this many metres (a short rest: a place a few steps aside is still a stop). */
    const val CLEAR_STOP_M = 100.0

    /** Doubtful, but "Als neues Ziel" first: d more than this share of the route ahead. */
    const val GOAL_FIRST_SHARE = 0.5

    /** A clear goal: d more than this share of the route ahead (the detour more than doubles the rest) … */
    const val CLEAR_GOAL_SHARE = 1.0

    /** … and more than this many metres (a few hundred metres to go never make a place nearby "only a goal"). */
    const val CLEAR_GOAL_M = 300.0

    /** "Past the goal": farther from the last leg's start than the goal, within this angle of the leg → only goal … */
    const val PAST_GOAL_DEG = 30.0

    /** … within this wider angle → both, goal first. */
    const val PAST_GOAL_DOUBT_DEG = 60.0

    /**
     * A hold on the empty map with 2+ points (owner 2026-11-15, planning on his Pixel: „ich werde überhaupt nie gefragt“ –
     * „bei eindeutigen Sachen auch nicht schlecht“): only a doubtful case ([Zone.STOP_FIRST], [Zone.GOAL_FIRST]) asks with
     * this menu's two rows; a clear one, or no route ahead, is set at once where it fits best (WaypointInsertion).
     */
    fun asksOnHold(zone: Zone?): Boolean = zone == Zone.STOP_FIRST || zone == Zone.GOAL_FIRST

    /**
     * What a hold on the empty map does while planning ([planningHold]): [ON_LINE] = the smart insertion into that leg,
     * [STOP] = „Als Stopp“ (stop-only, never behind the goal), [NEW_GOAL] = „Als Ziel“ (appended), [ASK] = the menu.
     */
    enum class HoldAction { ON_LINE, STOP, NEW_GOAL, ASK }

    /** "On the line": this close to the planned line on screen (the hold's smart insertion uses the same ~24 dp). */
    const val LINE_SNAP_DP = 24.0

    /**
     * „Fast gleich“ (owner 2026-11-17 21:40, replacing the band of 2026-11-16 and way 3's cone of the same day: „die echte
     * Routendifferenz … die kürzere gewinnt; nur fast gleich → fragen“): the longer of appending and inserting at most this
     * many times the shorter → the app asks; else the shorter is taken at once.
     */
    const val SIMILAR = 1.2

    /** The costs of a new point while planning, metres – straight lines ([detours]) or on the paths ([pathDetours]). */
    data class Detours(
        /** The cheapest insertion detour over the legs: d(a,s) + d(s,b) − d(a,b) (as [zone]). */
        val insertM: Double,
        /** What appending costs: d(goal, s). */
        val appendM: Double,
        /**
         * The cheapest insertion detour over the legs before the last one; null with one leg. For the trace only – since
         * 2026-11-17 21:40 it decides nothing ([planningHold]).
         */
        val earlyInsertM: Double? = null,
    )

    /** [Detours] of a spot [s] for a plan of 2+ [points] on straight lines; null with fewer. */
    fun detours(points: List<GeoPoint>, s: GeoPoint): Detours? {
        if (points.size < 2) return null
        val legs = points.zipWithNext().map { (a, b) -> Geo.distance(a, s) + Geo.distance(s, b) - Geo.distance(a, b) }
        return Detours(legs.min(), Geo.distance(points.last(), s), legs.dropLast(1).minOrNull())
    }

    /**
     * „Always real paths“ (owner 2026-11-17, field case Hilden Feldstraße: straight lines said insert +68 m, append +63 m
     * – asked; on the paths inserting is ≈ +120 m, from point 12 there is no direct way, it goes past the goal; appending
     * ≈ 60 m): this many legs with the smallest straight-line detour are measured on the paths, the last leg always on top
     * ([pathCandidates]) – it caps the router's work.
     */
    const val PATH_CANDIDATES = 3

    /**
     * The legs (index of the leg's start point) [pathDetours] measures for a spot [s] in a plan of 2+ [points]: the last leg
     * first (it decides most holds), then the [PATH_CANDIDATES] legs with the smallest straight-line detour; empty with
     * fewer than 2 points.
     */
    fun pathCandidates(points: List<GeoPoint>, s: GeoPoint): List<Int> {
        if (points.size < 2) return emptyList()
        val last = points.size - 2
        val byDetour = (0..last).sortedBy { i ->
            Geo.distance(points[i], s) + Geo.distance(s, points[i + 1]) - Geo.distance(points[i], points[i + 1])
        }.take(PATH_CANDIDATES)
        return listOf(last) + byDetour.filter { it != last }
    }

    /**
     * [Detours] on the real paths (owner 2026-11-17 „Always real paths“): [length] (a, b) = the routed length a → b in
     * metres for the plan's activity; it throws when there is no route (the caller then keeps the straight lines,
     * [detours]). Appending = length(goal, s); inserting into a candidate leg a → b ([pathCandidates]) = length(a, s) +
     * length(s, b) − length(a, b), never below 0; [Detours.earlyInsertM] = the smallest among the candidates before the last
     * leg (null when none is measured). [straight] = the straight-line numbers (kept for the callers; the paths replace them).
     */
    fun pathDetours(points: List<GeoPoint>, s: GeoPoint, straight: Detours, length: (GeoPoint, GeoPoint) -> Double): Detours {
        require(points.size >= 2) { "a plan needs two points, got ${points.size}" }
        val goal = points.last()
        val append = length(goal, s)
        val last = points.size - 2
        var insert = Double.MAX_VALUE
        var early: Double? = null
        for (i in pathCandidates(points, s)) {
            val a = points[i]; val b = points[i + 1]
            val d = maxOf(0.0, length(a, s) + length(s, b) - length(a, b))
            insert = minOf(insert, d)
            if (i < last) early = minOf(early ?: d, d)
        }
        return Detours(insert, append, early)
    }

    /**
     * A hold on the empty map with 2+ points while PLANNING – no tour, no round trip (those keep [asksOnHold] on the zone).
     * The owner's rule (2026-11-17 21:40, replacing way 3 and the band of the same day: „die echte Routendifferenz … die
     * kürzere gewinnt; nur fast gleich → fragen“): on the line ([lineDp] ≤ [snapDp]) → [HoldAction.ON_LINE]; else the
     * longer of appending and inserting at most [SIMILAR] × the shorter → [HoldAction.ASK]; else the shorter at once:
     * appending → [HoldAction.NEW_GOAL], inserting → [HoldAction.STOP]. [lineDp] null = no line yet. The app gives [d] on
     * the paths ([pathDetours]) when its router measured them in time, else the straight lines ([detours]) – the rule is
     * the same.
     */
    fun planningHold(lineDp: Double?, d: Detours, snapDp: Double = LINE_SNAP_DP): HoldAction {
        if (lineDp != null && lineDp <= snapDp) return HoldAction.ON_LINE
        val lo = minOf(d.insertM, d.appendM); val hi = maxOf(d.insertM, d.appendM)
        return when {
            hi <= lo * SIMILAR -> HoldAction.ASK
            d.appendM < d.insertM -> HoldAction.NEW_GOAL
            else -> HoldAction.STOP
        }
    }

    /**
     * Both add rows while planning, the cheaper first in [Menu.main] and tinted (a hold that asks, a held place in doubt –
     * [planningRows]), decided on the numbers the decision took (the paths when measured); shown in [Menu.rows]' fixed order.
     */
    fun planningMenu(d: Detours): Menu =
        Menu(if (d.insertM < d.appendM) listOf(Item.ADD_STOP, Item.NEW_DESTINATION) else listOf(Item.NEW_DESTINATION, Item.ADD_STOP))

    /**
     * The add rows of a held PLACE's menu (POI, found pin) while planning – owner 2026-11-17, replacing „both rows always“
     * of 2026-11-15: „wenn ich da nicht einfügen muss, dann braucht das auch im Menü nicht angezeigt zu werden.“ Decided by
     * the same [planningHold] (and the same numbers) a hold on the empty map takes: [HoldAction.ON_LINE] / [HoldAction.STOP]
     * → only „Als Stopp“, [HoldAction.NEW_GOAL] → only „Als Ziel“, [HoldAction.ASK] → both, the cheaper one tinted
     * ([planningMenu]). One row alone is not tinted ([Menu.emphasised]: nothing to choose between).
     */
    fun planningRows(action: HoldAction, d: Detours): Menu = when (action) {
        HoldAction.ON_LINE, HoldAction.STOP -> Menu(listOf(Item.ADD_STOP))
        HoldAction.NEW_GOAL -> Menu(listOf(Item.NEW_DESTINATION))
        HoldAction.ASK -> planningMenu(d)
    }

    /** [planningRows] for a place [lineDp] from the drawn line (a place on the line → „Als Stopp“ only) with the numbers [d]. */
    fun planningPlaceMenu(lineDp: Double?, d: Detours, snapDp: Double = LINE_SNAP_DP): Menu =
        planningRows(planningHold(lineDp, d, snapDp), d)

    fun menu(points: Int, touring: Boolean, zone: Zone?): Menu = when {
        points <= 0 -> Menu(if (touring) listOf(Item.GO_HERE) else listOf(Item.START_HERE, Item.GO_HERE))
        points == 1 || zone == null -> Menu(listOf(Item.SET_DESTINATION))
        zone == Zone.STOP || zone == Zone.STOP_FIRST -> Menu(listOf(Item.ADD_STOP, Item.NEW_DESTINATION))
        else -> Menu(listOf(Item.NEW_DESTINATION, Item.ADD_STOP))
    }

    /**
     * [ahead] = the route still to go as points (during a tour: my position, then the points ahead; else the whole plan),
     * [p] = the held place, [loop] = a round trip (its goal is its start: nothing lies "past" it, and the loop is never
     * dropped by one tap – at most "goal first"). Null when there is no route ahead (fewer than 2 points).
     */
    fun zone(ahead: List<GeoPoint>, p: GeoPoint, loop: Boolean = false): Zone? {
        if (ahead.size < 2) return null
        var length = 0.0
        var detour = Double.MAX_VALUE
        for (i in 0 until ahead.size - 1) {
            val a = ahead[i]; val b = ahead[i + 1]
            val leg = Geo.distance(a, b)
            length += leg
            detour = minOf(detour, Geo.distance(a, p) + Geo.distance(p, b) - leg)
        }
        // "past the goal" is measured on the last leg, so a route that goes out and comes back (the place on the way out)
        // is not taken for one that ends short of it
        val legStart = ahead[ahead.size - 2]; val goal = ahead.last()
        val past = !loop && Geo.distance(goal, legStart) > 0.0 && Geo.distance(legStart, p) > Geo.distance(legStart, goal)
        val angle = if (past) kotlin.math.abs(Geo.angleDiff(Geo.bearing(legStart, goal), Geo.bearing(legStart, p))) else 180.0
        val zone = when {
            angle <= PAST_GOAL_DEG -> Zone.GOAL
            detour > maxOf(CLEAR_GOAL_SHARE * length, CLEAR_GOAL_M) -> Zone.GOAL
            angle <= PAST_GOAL_DOUBT_DEG || detour > maxOf(GOAL_FIRST_SHARE * length, CLEAR_STOP_M) -> Zone.GOAL_FIRST
            detour <= maxOf(CLEAR_STOP_SHARE * length, CLEAR_STOP_M) -> Zone.STOP
            else -> Zone.STOP_FIRST
        }
        return if (loop && zone == Zone.GOAL) Zone.GOAL_FIRST else zone
    }
}
