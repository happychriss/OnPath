package com.onpath.app.domain

import com.onpath.strings.*
import com.onpath.app.recording.GpsStatus
import kotlin.math.max
import kotlin.math.min

/**
 * Map gesture rules of Plan mode (owner spec).
 * **Hold only** – a quick tap never changes anything, nothing is ever dragged.
 *
 * | hold ≥ 0.3 s on…                         | vibration | result |
 * | --- | --- | --- |
 * | empty map, plan still empty              | 1×        | first-point menu: Als Start · Als Ziel (from my location) |
 * | empty map / route line                   | 1×        | add point (smart insertion), no message |
 * | POI that is not a route point            | 1×        | POI menu ([PinMenuItems]) |
 * | route point (incl. a POI that is a stop) | 2×        | point menu ([pointMenu]) |
 *
 * The vibration comes at the moment the hold is recognised (ring full), never after the edit ([PlanHaptics]) – menu
 * buttons, set / delete and the Points list do not vibrate.
 *
 * The marker wins (within [MARKER_RADIUS_DP], nearest marker first), then a POI, then the line / map – no question in
 * between (owner: setting a point is setting a point, deleting is deleting; a wrong guess is undone by holding again).
 * A quick tap on a POI always shows its details – also with a menu open and on a POI that is a route point (owner
 * 2026-11-15). With a menu open a tap elsewhere closes it; a hold on another marker / POI opens that one's menu, a hold on
 * the empty map only closes the menu (no point is added while dismissing). A long press counts as a hold.
 * Navigate (or a shown track): nothing is added or moved, a POI tap opens read-only details.
 */
object MapGestures {
    /**
     * Screen radius around a marker that counts as "on the marker", at any zoom – generous, a finger is ~1 cm and holds
     * meant "on the point" land well beyond the drawn marker.
     */
    const val MARKER_RADIUS_DP = 48f

    /** Diagnostics only: a hold this close to a point but outside [MARKER_RADIUS_DP] is logged as a near miss. */
    const val NEAR_RADIUS_DP = 80f

    /** MapLibre's long click is reported as [HOLD] too. */
    enum class Press { TAP, HOLD }

    sealed interface Action {
        data class PointMenu(val index: Int) : Action
        data object PoiMenu : Action
        data object AddPoint : Action
        /** First hold on an empty plan: „Als Start“ · „Als Ziel“. */
        data object FirstPointMenu : Action
        data object CloseMenu : Action
        data object PoiDetails : Action
        data object None : Action
    }

    /** Vibration when a hold is recognised (ring full): 1× (map, line, POI), 2× (route point) or nothing (PlanHaptics). */
    enum class Buzz { NONE, ONE, TWO }

    /** Entries of the point menu, in display order. */
    enum class PointAction { MAKE_START, LOOP_START, KEEP, GOOGLE, DELETE }

    /** The group of a point menu's row (owner 2026-11-26): Route (Als Start) → Ort (Merken · Google) → Entfernen (Löschen). */
    fun pointGroup(a: PointAction): MenuGroups.Group = when (a) {
        PointAction.MAKE_START, PointAction.LOOP_START -> MenuGroups.Group.ROUTE
        PointAction.KEEP, PointAction.GOOGLE -> MenuGroups.Group.PLACE
        PointAction.DELETE -> MenuGroups.Group.REMOVE
    }

    /** Whether row [i] of the point menu [rows] gets a thin line above it (it starts a new group). */
    fun pointLineBefore(rows: List<PointAction>, i: Int): Boolean = MenuGroups.lineBefore(rows.map(::pointGroup), i)

    /**
     * A quick tap on the open map in Plan sets nothing (hold-only, owner) – it shows the calm line "Gedrückt halten, um
     * hier einen Punkt zu setzen." in the plan sheet (owner 2026-11-03, novice fix). Not on a POI / route point / the
     * pin (their own behaviour), not with a menu open (the tap closes it) and not on an empty plan (its hint says it).
     */
    fun tapShowsHoldHint(editable: Boolean, press: Press, action: Action, poi: Boolean, marker: Int, planEmpty: Boolean): Boolean =
        editable && press == Press.TAP && action == Action.None && !poi && marker < 0 && !planEmpty

    /**
     * @param marker index of the marker under the finger (see [nearestMarker]) or -1
     * @param poi a POI is under the finger
     */
    fun decide(editable: Boolean, press: Press, menuOpen: Boolean, marker: Int, poi: Boolean, planEmpty: Boolean): Action {
        // read-only (Navigate, a shown track): a POI opens its details on a tap AND a hold – a hold did nothing there while
        // it opens the POI menu in Plan (owner's Pixel 2026-11-27 on a paused tour: "tippen, nichts passiert")
        if (!editable) return if (poi) Action.PoiDetails else Action.None
        // a quick tap on a POI / hut shows its details in Plan too (UX review 5) – read-only, the plan never changes. Owner
        // 2026-11-15 (hike): a tap on a POI works everywhere – with a menu open (the details take its place, no second
        // tap) and on a POI that is the start, a stop or the goal (its marker sits on it; holding it still opens the
        // point menu)
        if (press == Press.TAP) return if (poi) Action.PoiDetails else if (menuOpen) Action.CloseMenu else Action.None
        return when {
            marker >= 0 -> Action.PointMenu(marker)
            poi -> Action.PoiMenu
            menuOpen -> Action.CloseMenu
            planEmpty -> Action.FirstPointMenu
            else -> Action.AddPoint
        }
    }

    /**
     * Why „Als Ziel“ (from my position) in the first-point menu is disabled, or null when a usable position exists.
     * A stale fix (status SEARCHING) is not used: the route would start in the wrong place.
     */
    fun fromMeBlocked(status: GpsStatus, hasFix: Boolean): String? = when (status) {
        GpsStatus.NO_PERMISSION -> Txt.s(Res.string.plan_no_location_permission)
        GpsStatus.OFF -> Txt.s(Res.string.plan_location_off)
        GpsStatus.SEARCHING -> Txt.s(Res.string.plan_searching_gps)
        GpsStatus.WEAK, GpsStatus.GOOD -> if (hasFix) null else Txt.s(Res.string.plan_searching_gps)
    }

    /**
     * Whether the hold menus on an empty plan (first-point menu, POI menu) offer „Als Start“: not during a tour – the
     * tour already runs from here, a held spot is where to go („Als Ziel“), never a new start (field 2026-11-07).
     */
    fun offersStartHere(touring: Boolean): Boolean = !touring

    /** How the armed hold feels (decided at finger-down, same targets as [decide]); [Buzz.NONE] = no ring either. */
    fun buzz(editable: Boolean, menuOpen: Boolean, marker: Int, poi: Boolean): Buzz = when {
        !editable -> Buzz.NONE
        marker >= 0 -> Buzz.TWO
        poi -> Buzz.ONE
        menuOpen -> Buzz.NONE
        else -> Buzz.ONE
    }

    /**
     * Whether [buzz] depends on a POI under the finger: only with no route point held and a menu open (POI → ONE, empty
     * map → NONE); every other press feels the same either way. The POI look-up is a query of the drawn map that waits
     * for MapLibre's render thread – done at every finger-down it held up the first frames of each pan (20–40 ms on the
     * emulator, smooth map 2026-11-08), so finger-down asks only when it matters.
     */
    fun buzzNeedsPoi(editable: Boolean, menuOpen: Boolean, marker: Int): Boolean = editable && marker < 0 && menuOpen

    /**
     * A hold that vibrated is never lost (owner 2026-11-26, Pixel: "it vibrates, but nothing happened – never lose a
     * symbol or anything after the vibration"). The ring is full and buzzes after 300 ms (MapHost's HOLD_MS) by our own
     * clock, but MapLibre reports the press only later: as a long click at the phone's long-press timeout (400 ms and
     * more), or as a click when the finger lifts inside the touch slop. A finger that slides past the slop between the
     * buzz and that moment (often while lifting) makes MapLibre's detector drop the press and pan the map: no click, no
     * long click, nothing. One per press: [down] → [armed] (the buzz) → MapLibre's [mapEvent] or the slide ([leftHold]);
     * whichever comes first plays the hold, the other is dropped.
     */
    class ArmedHold {
        private var armed = false
        private var mapSaid = false
        private var playedByApp = false

        /** A finger went down: a new press. */
        fun down() { armed = false; mapSaid = false; playedByApp = false }

        /** The hold's ring is full: it vibrated, the hold is promised. */
        fun armed() { armed = true }

        /** MapLibre reports a click / long click: true = act on it; false = the app already played this press. */
        fun mapEvent(): Boolean {
            if (playedByApp) return false
            mapSaid = true
            return true
        }

        /** The finger slid past the touch slop: true = play the hold now (it vibrated, MapLibre will not report it). */
        fun leftHold(): Boolean {
            if (!armed || mapSaid || playedByApp) return false
            playedByApp = true
            return true
        }
    }

    /**
     * Sensible options for route point [index] of [count] (owner 2026-11-26: groups Route → Ort → Entfernen). Middle points
     * of a normal route: „Als Start“ (drops the points before – „Löscht die N Punkte davor“). Round trip: „Als Start“
     * (rotates the loop – „Gleiche Rundtour, startet hier“) on every loop point except ①; with stops on the way before the
     * loop ([loopStart] > 0, owner 2026-11-03) those and ① get the normal route's „Als Start“. A named place / POI
     * ([isPlace]): „Merken“ ([keep] – the host can keep places; the planner passes false until it draws the row) and „In
     * Google Maps öffnen“. „Löschen“ always. Start and Ziel of a normal route get no route row (owner: „Umkehren braucht
     * nicht ins Menü“ – it is in the plan sheet).
     */
    fun pointMenu(index: Int, count: Int, isPlace: Boolean, roundTrip: Boolean = false, loopStart: Int = 0,
                  keep: Boolean = false): List<PointAction> = buildList {
        val middle = index in 1 until count - 1
        if (roundTrip) { if (middle) add(if (index > loopStart) PointAction.LOOP_START else PointAction.MAKE_START) }
        else if (middle) add(PointAction.MAKE_START)
        if (isPlace && keep) add(PointAction.KEEP)
        if (isPlace) add(PointAction.GOOGLE)
        add(PointAction.DELETE)
    }

    /**
     * The route point a press is aimed at (core feature: hold a point → its menu → Delete).
     * [points] is the PLAN's own list (never a copy kept with the drawing – it can lag behind),
     * [toScreen] its current screen position (null = projection not ready), finger = where the press ended.
     * A hold falls back to [downMarker], the point under the finger at finger-down: the map may move or turn while
     * the finger rests (following the position, compass). Returns the index or -1.
     */
    fun <P> markerUnderFinger(points: List<P>, toScreen: (P) -> Pair<Float, Float>?, fingerX: Float, fingerY: Float,
                              press: Press, downMarker: Int, radiusPx: Float): Int {
        val xs = FloatArray(points.size); val ys = FloatArray(points.size)
        points.forEachIndexed { i, p -> val s = toScreen(p); xs[i] = s?.first ?: Float.NaN; ys[i] = s?.second ?: Float.NaN }
        val hit = nearestMarker(xs, ys, fingerX, fingerY, radiusPx)
        if (hit >= 0) return hit
        return if (press == Press.HOLD && downMarker in points.indices) downMarker else -1
    }

    /**
     * Index of the marker nearest to the tap ([x],[y]) within [radiusPx], or -1. Works in screen pixels, so
     * the touch target stays the same size at every zoom; when markers overlap (zoomed out) the nearest wins,
     * on a tie the later one (drawn on top).
     */
    fun nearestMarker(markersX: FloatArray, markersY: FloatArray, x: Float, y: Float, radiusPx: Float): Int {
        var best = -1
        var bestD = radiusPx * radiusPx
        for (i in markersX.indices) {
            val dx = markersX[i] - x
            val dy = markersY[i] - y
            val d = dx * dx + dy * dy
            if (d <= bestD) { bestD = d; best = i }
        }
        return best
    }

    /**
     * Top-left of an overlay of size [w]×[h] anchored at ([ax],[ay]): centred above the anchor with [gap],
     * below it when there is no room above, always kept inside [left]..[right] × [top]..[bottom].
     */
    fun placeOverlay(
        ax: Float, ay: Float, w: Int, h: Int,
        left: Int, top: Int, right: Int, bottom: Int, gap: Int,
        /** Where "below" starts when the anchor has a body under it (the found place's pin: under its name chip). */
        belowAy: Float = ay,
        /**
         * The found place's pin (owner 2026-11-02): when neither fits inside [top]..[bottom], the menu may reach over the
         * top bar ([looseTop]) or the sheet ([looseBottom]) rather than cover the pin; null = the plain clamp.
         */
        looseTop: Int? = null, looseBottom: Int? = null,
    ): Pair<Int, Int> {
        val x = (ax - w / 2f).toInt().coerceIn(left, max(left, right - w))
        val above = (ay - gap - h).toInt()
        val below = (belowAy + gap).toInt()
        val y = when {
            above >= top -> above
            below + h <= bottom -> below
            looseTop != null && above >= looseTop -> above
            looseBottom != null && below + h <= looseBottom -> below
            else -> min(max(top, above), max(top, bottom - h))
        }
        return x to y
    }

    /**
     * A menu BESIDE a held spot (the hold menu „Als Stopp“ / „Als Ziel“, owner 2026-11-15): it never covers the spot
     * (its ring shows where the point goes). Right of the spot when it fits left of the map's control column
     * ([rightColumn] px wide at the right edge), else left of the spot, else right over the column, else above / below
     * ([placeOverlay]). Vertically centred on the spot, kept inside [top]..[bottom]. Pure.
     */
    fun placeBeside(
        ax: Float, ay: Float, w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int, gap: Int, rightColumn: Int,
    ): Pair<Int, Int> {
        val y = (ay - h / 2f).toInt().coerceIn(top, max(top, bottom - h))
        val toRight = (ax + gap).toInt()
        val toLeft = (ax - gap - w).toInt()
        return when {
            toRight + w <= right - rightColumn -> toRight to y
            toLeft >= left -> toLeft to y
            toRight + w <= right -> toRight to y
            else -> placeOverlay(ax, ay, w, h, left, top, right, bottom, gap)
        }
    }

    /** True while the anchor is on the visible map area (else the overlay closes). */
    fun anchorVisible(ax: Float, ay: Float, width: Int, height: Int): Boolean =
        ax >= 0 && ay >= 0 && ax <= width && ay <= height
}
