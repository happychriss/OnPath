package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue

/**
 * What the voice says, as language-free phrases ([VoiceText] turns them into a sentence of the app's language). Adding a
 * language never touches these: all words are in `strings_voice.xml`.
 */
sealed interface Phrase {
    /**
     * The tour starts with a route: [distanceM] to go, [durationS] (null = not known yet), [destination] name.
     * [carryOn]: the plan was changed during the tour and the new line is ridden – "Weiter geht's" (owner 2026-11-06).
     */
    data class Start(val distanceM: Double, val durationS: Double?, val destination: String?, val carryOn: Boolean = false) : Phrase
    /**
     * A junction [inM] ahead (under [VoiceGuide.SOON_M]: "now"); [onto] = the kind of way it turns onto when that
     * changes; [then] = a second turn right after it (said together); [straightM] = the quiet stretch after it.
     */
    data class Junction(
        val inM: Double, val turn: Turn, val exit: Int = 0, val onto: WayKind? = null,
        val then: Turn? = null, val thenExit: Int = 0, val straightM: Double? = null,
    ) : Phrase
    data object OffRoute : Phrase
    data object BackOnRoute : Phrase
    /**
     * A new route was taken; [next] = its first junction when it is close; [turnRound] = it starts behind the user:
     * "Neue Route. Bitte umdrehen." (owner 2026-11-01 – the U sign on the map, "Umdrehen" on the turn card).
     */
    data class NewRoute(val next: Junction?, val turnRound: Boolean = false) : Phrase
    /** Stop [number] (1-based, as in the plan) reached; then on to [nextName] [nextM] away (either may be null). */
    data class StopReached(val name: String?, val number: Int, val nextName: String?, val nextM: Double?,
                           /** The route goes back the way it came at the stop ([Reversals]): "Bitte umdrehen." */
                           val turnRound: Boolean = false) : Phrase
    data object Arrived : Phrase
    data object NoGps : Phrase
    data object NoDestination : Phrase
}

enum class Turn { LEFT, SLIGHT_LEFT, SHARP_LEFT, KEEP_LEFT, RIGHT, SLIGHT_RIGHT, SHARP_RIGHT, KEEP_RIGHT, U_TURN, ROUNDABOUT;
    companion object {
        fun of(c: JunctionCue): Turn = when (c) {
            JunctionCue.LEFT -> LEFT
            JunctionCue.SLIGHT_LEFT -> SLIGHT_LEFT
            JunctionCue.SHARP_LEFT -> SHARP_LEFT
            JunctionCue.KEEP_LEFT -> KEEP_LEFT
            JunctionCue.RIGHT -> RIGHT
            JunctionCue.SLIGHT_RIGHT -> SLIGHT_RIGHT
            JunctionCue.SHARP_RIGHT -> SHARP_RIGHT
            JunctionCue.KEEP_RIGHT -> KEEP_RIGHT
            JunctionCue.U_TURN_LEFT, JunctionCue.U_TURN_RIGHT -> U_TURN
            JunctionCue.ROUNDABOUT -> ROUNDABOUT
        }
    }
}

/**
 * The kind of way a turn goes onto (owner 2026-11-01, Hilden: every park path was a "Feldweg"): from the way type of the
 * route ([com.onpath.app.routing.WayAttrs.wayType]) – TRACK only on a real track, CYCLEWAY a bike way, WAY a footway (on
 * the bike also a path), PATH a path on foot, ROAD a street; from the router's classes only when the line has no way types.
 */
enum class WayKind { PATH, TRACK, ROAD, CYCLEWAY, WAY }

/**
 * When the voice speaks during a tour (owner 2026-10-31, `docs/design/voice-guidance.md`): few, clear sentences. Pure,
 * app only, unit-tested (`VoiceGuideTest`); the recording service feeds it every fix ([update]) and app events
 * ([event]) and speaks what comes back.
 *
 * - Junctions only where the phone vibrates ([CueRule]), one sentence each, said early – timed by the current speed
 *   (bike in two stages – [BIKE_EARLY_S] s ahead with the distance, [BIKE_FINAL_S] s ahead "Gleich …" – see [nextJunction]; on foot [HIKE_LEAD_S] s within [HIKE_MIN_M]…[HIKE_MAX_M]);
 *   the vibration at the junction is the "now".
 * - Two close turns in one sentence (the merge distance of [JunctionCues]); the kind of way when it changes; a quiet
 *   stretch after it ≥ [BIKE_STRAIGHT_M] / [HIKE_STRAIGHT_M] is said ("then 3 km straight on").
 * - At least [GAP_MS] between sentences; off route / arrival / no destination never wait. A sentence that had to wait
 *   is dropped after [STALE_MS]; a junction is simply said at the next fix (with its distance then).
 * - Quiet while paused; "No GPS" once after [GPS_LOST_MS] without a fix while moving (owner 2026-11-01: a short gap is no
 *   news, standing still in a shop never), only after the start, at most every [NO_GPS_EVERY_MS].
 * - State sentences have a minimum interval (field 2026-11-01): "off the route" every [OFF_ROUTE_EVERY_MS], "back on the
 *   route" only after [BACK_ON_AFTER_MS] (15 s) of the question that was said, "arrived" every [ARRIVED_EVERY_MS].
 * - The tour's voice memory ([Memory]) is kept with the tour, not with the service: a recording service recreated
 *   mid-tour (sticky restart after the process died) continues with [continueTour] and never says "Los geht's" again
 *   (Pixel 2026-11-01: 3–4× in the first 3 km).
 */
class VoiceGuide {
    data class Input(
        val nowMs: Long,
        val line: List<GeoPoint>,
        val version: Int,
        /** Progress on [line] (null = not matched). */
        val alongM: Double?,
        /** On the line (no question open, matched). */
        val onRoute: Boolean,
        val speedMps: Double?,
        val bike: Boolean,
        /** The off-route question is open. */
        val offRoute: Boolean = false,
        /** A new route (re-route) is followed. */
        val rerouted: Boolean = false,
        val paused: Boolean = false,
        val gpsLost: Boolean = false,
        /** The plan's stops on the original line and which were visited ([NavSession]). */
        val stops: List<NavStop> = emptyList(),
        val visited: Set<Int> = emptySet(),
        /** The map's number of [stops]'s first stop ([NavSession.firstNumber]): "Stopp 7", not "Stopp 2". */
        val firstNumber: Int = 1,
        /** Stops left out on purpose ([NavSession.skipped]): in [visited], never "reached". */
        val skipped: Set<Int> = emptySet(),
        /** To go for the start sentence (time model); null = not known yet. */
        val toGoS: Double? = null,
        /** Standing still ([com.onpath.app.recording.MoveWatch], owner 2026-11-01): no "No GPS", no "off the route". */
        val quiet: Boolean = false,
        /** The route taken starts behind the user ([TurnRound], the U sign): the new-route sentence says to turn round. */
        val turnRound: Boolean = false,
    )

    private var version = Int.MIN_VALUE
    private var size = -1
    private var bike = false
    private var alongs = DoubleArray(0)
    private var turns = emptyList<Turn>()
    private var exits = IntArray(0)
    private var onto = arrayOfNulls<WayKind>(0)
    private var needed = BooleanArray(0)
    private val said = HashSet<Int>()
    /** Bike: junctions whose early sentence ("In 200 Metern links.") was said. */
    private val early = HashSet<Int>()
    private var lineLength = 0.0
    /** Where the line goes back the way it came (metres along it, [Reversals]). */
    private var reversals = DoubleArray(0)

    /** What must outlive the service instance during one tour (times in [Input.nowMs] units, null = never). */
    data class Memory(
        val started: Boolean = false,
        val offRouteSaidMs: Long? = null,
        val noGpsSaidMs: Long? = null,
        val arrivedSaidMs: Long? = null,
    )

    private var started = false
    private var offRouteSaidMs: Long? = null
    private var noGpsSaidMs: Long? = null
    private var arrivedSaidMs: Long? = null
    /** The open off-route question was said (else its closing says nothing). */
    private var offRouteSpoken = false
    private var lastSpokenMs = Long.MIN_VALUE / 2
    private var lastOffRoute = false
    private var lastRerouted = false
    private var lastVisited: Set<Int>? = null
    private var lastStops: List<NavStop>? = null
    private var gpsLostSince: Long? = null
    private var gpsSaid = false
    /** GPS good again and walking since (after "No GPS" was said); null = not yet / standing. */
    private var gpsGoodSince: Long? = null
    private val waiting = ArrayDeque<Pair<Phrase, Long>>()

    /** The tour's voice memory, to keep with the tour ([continueTour] after a restart). */
    fun memory(): Memory = Memory(started, offRouteSaidMs, noGpsSaidMs, arrivedSaidMs)

    /** The same tour goes on in a new service instance: what was said stays said (the start sentence is no news). */
    fun continueTour(m: Memory) {
        started = m.started; offRouteSaidMs = m.offRouteSaidMs; noGpsSaidMs = m.noGpsSaidMs; arrivedSaidMs = m.arrivedSaidMs
        lastVisited = null
    }

    /** The app chose a new line (plan edited): an open off-route question of the old line is gone – no "back on". */
    fun lineFromApp() { lastOffRoute = false; offRouteSpoken = false; if (started) carryOnPending = true }

    /** The plan was changed during the tour: "Weiter geht's …" once the new line is on (owner 2026-11-06). */
    private var carryOnPending = false

    /** A new tour: everything is said afresh. */
    fun newTour() {
        version = Int.MIN_VALUE; size = -1; said.clear(); early.clear(); lineLength = 0.0
        started = false; offRouteSaidMs = null; noGpsSaidMs = null; arrivedSaidMs = null; offRouteSpoken = false
        carryOnPending = false
        lastSpokenMs = Long.MIN_VALUE / 2; lastOffRoute = false; lastRerouted = false; lastVisited = null
        gpsLostSince = null; gpsSaid = false; gpsGoodSince = null; waiting.clear()
    }

    /** The phrase to say now for this fix, or null. */
    fun update(i: Input): Phrase? {
        if (i.paused) { gpsLostSince = null; return null }
        val newLine = load(i.line, i.version, i.bike)
        // the off-route question: said once when it opens (at most every OFF_ROUTE_EVERY_MS – jumping fixes open and
        // close it again and again); closed by riding back → "back on the route" when it was said a while ago
        if (i.offRoute && !lastOffRoute) {
            lastOffRoute = true
            offRouteSpoken = !i.quiet && since(offRouteSaidMs, i.nowMs) >= OFF_ROUTE_EVERY_MS
            if (offRouteSpoken) { offRouteSaidMs = i.nowMs; waiting.clear(); return speak(Phrase.OffRoute, i.nowMs) }
        }
        if (!i.offRoute && lastOffRoute) {
            lastOffRoute = false
            if (offRouteSpoken && !(newLine && i.rerouted) && since(offRouteSaidMs, i.nowMs) >= BACK_ON_AFTER_MS) {
                queue(Phrase.BackOnRoute, i.nowMs)
            }
            offRouteSpoken = false
        }
        // before the start sentence a new line is no news – the start sentence tells its length (field 2026-11-01:
        // "Neue Route." came before "Los geht's.")
        // every new route is said, not just the first one away from the plan (owner 2026-11-01, Rosellen replay: the
        // second and third re-route came without a word – "keine Nachricht, bitte wenden")
        if (newLine && i.rerouted && started) {
            queue(if (i.turnRound) Phrase.NewRoute(null, turnRound = true) else Phrase.NewRoute(nextJunction(i, closeOnly = true)), i.nowMs)
        }
        lastRerouted = i.rerouted
        // GPS: said once; again only after the GPS was good for [GPS_REARM_MS] while walking (owner 2026-11-01: "einmal und
        // dann erst wieder, wenn es einige Zeit gehabt hat und wir wieder gelaufen sind")
        if (i.gpsLost) {
            gpsGoodSince = null
            val since = gpsLostSince ?: i.nowMs.also { gpsLostSince = it }
            if (!gpsSaid && started && !i.quiet && i.nowMs - since >= GPS_LOST_MS && since(noGpsSaidMs, i.nowMs) >= NO_GPS_EVERY_MS) {
                gpsSaid = true; noGpsSaidMs = i.nowMs; queue(Phrase.NoGps, i.nowMs)
            }
        } else {
            gpsLostSince = null
            if (gpsSaid) {
                if (i.quiet) gpsGoodSince = null
                else if (i.nowMs - (gpsGoodSince ?: i.nowMs.also { gpsGoodSince = it }) >= GPS_REARM_MS) { gpsSaid = false; gpsGoodSince = null }
            }
        }
        // start
        if (!started && i.line.size >= 2 && i.alongM != null && i.onRoute) {
            started = true
            val dest = i.stops.lastOrNull()?.name
            queue(Phrase.Start((lineLength - i.alongM).coerceAtLeast(0.0), i.toGoS, dest), i.nowMs)
        } else if (carryOnPending && started && i.line.size >= 2 && i.alongM != null && i.onRoute) {
            // the changed plan's line is ridden: its length and destination, like the start sentence
            carryOnPending = false
            queue(Phrase.Start((lineLength - i.alongM).coerceAtLeast(0.0), i.toGoS, i.stops.lastOrNull()?.name, carryOn = true), i.nowMs)
        }
        // stops reached (not the start, not the destination – the arrival is its own event)
        // another list of stops (a plan edited during the tour, the plan followed again after deleting a point): its
        // visited stops are no news (owner 2026-11-01, Rosellen replay: "Stopp 2 erreicht" again 3 km after it)
        val prev = lastVisited?.takeIf { i.stops == lastStops }
        lastStops = i.stops
        lastVisited = i.visited
        if (prev != null) {
            for (k in i.visited.sorted()) {
                if (k in prev || k == 0 || k >= i.stops.size - 1 || k in i.skipped) continue
                val next = (k + 1 until i.stops.size).firstOrNull { it !in i.visited }?.let { i.stops[it] }
                val nextM = if (next != null && !i.rerouted && i.alongM != null) (next.alongM - i.alongM).takeIf { it > 0 } else null
                val back = !i.rerouted && reversals.any { kotlin.math.abs(it - i.stops[k].alongM) < Reversals.SAME_M }
                queue(Phrase.StopReached(i.stops[k].name, k + i.firstNumber, next?.name, nextM, back), i.nowMs)
            }
        }
        // a sentence that waited for the gap
        while (waiting.isNotEmpty() && i.nowMs - waiting.first().second > STALE_MS) waiting.removeFirst()
        val since = i.nowMs - lastSpokenMs
        // the pause between sentences – except for a junction that is just ahead now (owner 2026-11-01, Rosellen: the
        // "left" 80 m after a sharp right fell into the pause and was dropped as too late)
        if (since < URGENT_GAP_MS) return null
        val gapOk = since >= GAP_MS
        if (gapOk) waiting.removeFirstOrNull()?.let { return speak(it.first, i.nowMs) }
        // junctions
        if (!i.onRoute || i.alongM == null) return null
        val j = nextJunction(i, closeOnly = false, finalOnly = !gapOk) ?: return null
        return speak(j, i.nowMs)
    }

    /** Back from Plan (owner 2026-11-03): sentences that waited are dropped – nothing from the time in Plan is said late. */
    fun dropWaiting() { waiting.clear() }

    /** An app event (arrival, nothing ahead after an edit): said at once, never waits; "arrived" at most every [ARRIVED_EVERY_MS]. */
    fun event(p: Phrase, nowMs: Long): Phrase? {
        if (p == Phrase.Arrived) {
            if (since(arrivedSaidMs, nowMs) < ARRIVED_EVERY_MS) return null
            arrivedSaidMs = nowMs
        }
        waiting.clear(); return speak(p, nowMs)
    }

    /** Time since [last]; "long ago" when never, or when the clock went back (a reboot restarts the elapsed clock). */
    private fun since(last: Long?, nowMs: Long): Long = if (last == null || last > nowMs) Long.MAX_VALUE else nowMs - last

    /**
     * The junction to announce now ([closeOnly]: the first one ahead if within twice the lead, for "New route";
     * [finalOnly]: only one that is just ahead, during the pause between sentences).
     *
     * Bike in two stages (owner 2026-11-01, Rosellen replay: "100 Meter – war das wirklich die empfohlene Entfernung?";
     * Organic Maps / OsmAnd): early, ~[BIKE_EARLY_S] ahead at the speed ridden (≥ [BIKE_EARLY_MIN_M]): "In 200 Metern
     * links." – then ~[BIKE_FINAL_S] ahead ([BIKE_FINAL_MIN_M]…[BIKE_FINAL_MAX_M]) without a number: "Gleich links."
     * (with "danach"). A junction that comes into view too late for the early stage gets only the second.
     * On foot one stage: "Gleich links." ~10 m before.
     */
    private fun nextJunction(i: Input, closeOnly: Boolean, finalOnly: Boolean = false): Phrase.Junction? {
        val along = i.alongM ?: return null
        val speed = i.speedMps?.takeIf { it.isFinite() && it >= 0 } ?: if (bike) SpeedWindow.FALLBACK_MPS else JunctionCues.WALK_FALLBACK_MPS
        val lead = if (bike) (speed * BIKE_FINAL_S).coerceIn(BIKE_FINAL_MIN_M, BIKE_FINAL_MAX_M)
            else (speed * HIKE_LEAD_S).coerceIn(HIKE_MIN_M, HIKE_MAX_M)
        val k = alongs.indices.firstOrNull { needed[it] && it !in said && alongs[it] >= along - JunctionCues.PASSED_M } ?: return null
        val d = alongs[k] - along
        if (bike && !finalOnly && d > lead && k !in early) {
            // the early sentence with its distance – only when there is time for both
            val earlyLead = (speed * BIKE_EARLY_S).coerceIn(BIKE_EARLY_MIN_M, BIKE_EARLY_MAX_M)
            val reach = if (closeOnly) 2 * earlyLead else earlyLead
            if (d <= reach && d - lead >= speed * BIKE_EARLY_GAP_S) {
                early += k
                return Phrase.Junction(d, turns[k], exits[k], onto[k])
            }
            if (!closeOnly) return null
        }
        if (d > (if (closeOnly && !bike) 2 * lead else lead)) return null
        said += k
        if (d < (if (bike) TOO_LATE_M else HIKE_TOO_LATE_M)) return null // the vibration says it now
        // "danach": the next turn within [BIKE_THEN_M] on the bike (concept: never time for its own two sentences)
        val merge = if (bike) maxOf(JunctionCues.bikeMergeM(speed), BIKE_THEN_M) else JunctionCues.hikeMergeM(speed)
        val j = alongs.indices.firstOrNull { it > k && needed[it] }?.takeIf { alongs[it] - alongs[k] < merge }
        if (j != null) said += j
        val last = j ?: k
        val after = alongs.indices.firstOrNull { it > last && needed[it] }?.let { alongs[it] } ?: lineLength
        val straight = (after - alongs[last]).takeIf { it >= (if (bike) BIKE_STRAIGHT_M else HIKE_STRAIGHT_M) }
        // the bike's second stage has no number ("Gleich links."); on foot it is close anyway
        val inM = if (bike) 0.0 else d.coerceAtLeast(0.0)
        return Phrase.Junction(inM, turns[k], exits[k], onto[k], j?.let { turns[it] }, j?.let { exits[it] } ?: 0, straight)
    }

    private fun queue(p: Phrase, nowMs: Long) { if (waiting.size < 3) waiting.addLast(p to nowMs) }

    private fun speak(p: Phrase, nowMs: Long): Phrase { lastSpokenMs = nowMs; return p }

    /** True when a new line was loaded (not the first one). */
    private fun load(given: List<GeoPoint>, v: Int, isBike: Boolean): Boolean {
        if (v == version && given.size == size && isBike == bike) return false
        val first = version == Int.MIN_VALUE
        version = v; size = given.size; bike = isBike
        val line = JogRule.approach(given, hike = !isBike)   // a corner drawn as a curve: its whole turn
        val cum = if (line.size >= 2) Geo.cumulative(line) else DoubleArray(line.size)
        lineLength = cum.lastOrNull() ?: 0.0
        val idx = line.indices.filter { line[it].turn != null }
        alongs = DoubleArray(idx.size) { cum[idx[it]] }
        turns = idx.map { Turn.of(line[it].turn!!) }
        exits = IntArray(idx.size) { line[idx[it]].exit }
        onto = Array(idx.size) { ontoAt(line, cum, idx[it], isBike) }
        needed = JogRule.needed(line, cum, idx, hike = !isBike)
        reversals = if (line.size >= 3) Reversals.of(line, cum).map { cum[it] }.toDoubleArray() else DoubleArray(0)
        said.clear(); early.clear()
        return !first
    }

    companion object {
        /** Bike, early sentence with the distance: this many seconds ahead at the speed ridden … */
        const val BIKE_EARLY_S = 25.0
        const val BIKE_EARLY_MIN_M = 100.0
        const val BIKE_EARLY_MAX_M = 400.0
        /** … and only with at least this many seconds of riding left before the second one. */
        const val BIKE_EARLY_GAP_S = 6.0
        /** Bike, "Gleich links." without a number: this many seconds ahead (Organic Maps 35–150 m). */
        const val BIKE_FINAL_S = 8.0
        const val BIKE_FINAL_MIN_M = 35.0
        const val BIKE_FINAL_MAX_M = 150.0
        /** Bike: a turn this close after the one said is part of its sentence ("Gleich links, danach rechts."). */
        const val BIKE_THEN_M = 100.0
        /** A junction just ahead may be said this soon after the last sentence (else [GAP_MS]). */
        const val URGENT_GAP_MS = 3_000L
        /**
         * On foot a short "Gleich links." about 10 m before the junction, no distance (owner 2026-11-01 on the replay:
         * "50 Meter kann man zu Fuß gar nicht abschätzen – 10 Meter reichen"; Organic Maps: 15–25 m).
         */
        const val HIKE_LEAD_S = 8.0
        const val HIKE_MIN_M = 8.0
        const val HIKE_MAX_M = 15.0
        /** On foot closer than this nothing is said any more. */
        const val HIKE_TOO_LATE_M = 3.0
        /** Closer than this the sentence says "now". */
        const val SOON_M = 30.0
        /** Closer than this nothing is said any more – the vibration comes. */
        const val TOO_LATE_M = 12.0
        const val BIKE_STRAIGHT_M = 2_000.0
        const val HIKE_STRAIGHT_M = 1_000.0
        const val GAP_MS = 10_000L
        const val STALE_MS = 30_000L
        /** Owner 2026-11-01: "2–3 minutes without GPS while on the way – then say it; otherwise it nags". */
        const val GPS_LOST_MS = 150_000L
        const val NO_GPS_EVERY_MS = 10 * 60_000L
        /** "No GPS" is said again only after the GPS was good this long while walking. */
        const val GPS_REARM_MS = 2 * 60_000L
        const val OFF_ROUTE_EVERY_MS = 2 * 60_000L
        /** "Wieder auf der Route." when back this long after "Abseits der Route." (was 60 s – owner 2026-11-01, Rosellen
         *  replay: back on the route 25 s after it, nothing said – "da wäre eine schöne Aussage"); quick flapping stays quiet. */
        const val BACK_ON_AFTER_MS = 15_000L
        const val ARRIVED_EVERY_MS = 5 * 60_000L

        /** The way type is read this far before / after the junction: a crossing or a short link piece is not "the way". */
        const val WAY_SAMPLE_M = 20.0

        /**
         * The kind of way the route turns onto at line point [i] – only when it is another kind than the way it comes on.
         * From the line's way types [WAY_SAMPLE_M] before and after the junction (Hilden: the crossing into the park is
         * not "the way", the park path behind it is); without way types from the router's classes ([wayKind]).
         */
        fun ontoAt(line: List<GeoPoint>, cum: DoubleArray, i: Int, bike: Boolean): WayKind? {
            val inType = wayTypeAt(line, cum, cum[i] - WAY_SAMPLE_M)
            val outType = wayTypeAt(line, cum, cum[i] + WAY_SAMPLE_M)
            if (inType == null && outType == null) return wayKind(line[i], bike)
            val out = outType?.let { kindOfType(it, bike) } ?: return null
            return out.takeIf { it != inType?.let { t -> kindOfType(t, bike) } }
        }

        /** The way type of the span at [d] metres along the line (clamped); null = not known. */
        private fun wayTypeAt(line: List<GeoPoint>, cum: DoubleArray, d: Double): String? {
            if (line.size < 2) return null
            var j = java.util.Arrays.binarySearch(cum, d.coerceIn(0.0, cum.last())).let { if (it >= 0) it else -it - 2 }
            j = j.coerceIn(0, line.size - 2)
            return line[j].way?.wayType
        }

        /** BRouterWayStats.wayType → the word said. Steps, ferries and the unknown: nothing. */
        fun kindOfType(type: String, bike: Boolean): WayKind? = when (type) {
            "track" -> WayKind.TRACK
            "cycleway" -> WayKind.CYCLEWAY
            "path" -> if (bike) WayKind.WAY else WayKind.PATH
            "footway" -> WayKind.WAY
            "quiet_road", "road", "busy_road" -> WayKind.ROAD
            else -> null
        }

        /** From the router's classes (a line without way types): the kind of way turned onto, when it changes. */
        fun wayKind(p: GeoPoint, bike: Boolean): WayKind? {
            val s = p.shape ?: return null
            val out = kindOf(s.outPrio, bike)
            return out?.takeIf { it != kindOf(s.inPrio, bike) }
        }

        private fun kindOf(prio: Int, bike: Boolean): WayKind? =
            if (bike) {
                // class ≤ 4 is track, path, footway, crossing, pedestrian alike – "Weg", never "Feldweg" (Hilden)
                when (CueRule.bikeKind(prio)) { 1 -> WayKind.WAY; 3 -> WayKind.ROAD; else -> null }
            } else {
                val c = CueRule.hikeClass(prio)
                when { c == 1 -> WayKind.PATH; c == 2 -> WayKind.TRACK; c >= 18 -> WayKind.ROAD; else -> null }
            }
    }
}
