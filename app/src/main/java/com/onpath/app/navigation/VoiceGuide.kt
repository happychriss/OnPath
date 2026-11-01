package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue

/**
 * What the voice says, as language-free phrases ([VoiceText] turns them into a sentence of the app's language). Adding a
 * language never touches these: all words are in `strings_voice.xml`.
 */
sealed interface Phrase {
    /** The tour starts with a route: [distanceM] to go, [durationS] (null = not known yet), [destination] name. */
    data class Start(val distanceM: Double, val durationS: Double?, val destination: String?) : Phrase
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
    /** A new route was taken; [next] = its first junction when it is close. */
    data class NewRoute(val next: Junction?) : Phrase
    /** Stop [number] (1-based, as in the plan) reached; then on to [nextName] [nextM] away (either may be null). */
    data class StopReached(val name: String?, val number: Int, val nextName: String?, val nextM: Double?) : Phrase
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

/** The kind of way a turn goes onto, as far as the router's classes tell it. */
enum class WayKind { PATH, TRACK, ROAD }

/**
 * When the voice speaks during a tour (owner 2026-10-31, `docs/design/voice-guidance.md`): few, clear sentences. Pure,
 * app only, unit-tested (`VoiceGuideTest`); the recording service feeds it every fix ([update]) and app events
 * ([event]) and speaks what comes back.
 *
 * - Junctions only where the phone vibrates ([CueRule]), one sentence each, said early – timed by the current speed
 *   (bike [BIKE_LEAD_S] s within [BIKE_MIN_M]…[BIKE_MAX_M], on foot [HIKE_LEAD_S] s within [HIKE_MIN_M]…[HIKE_MAX_M]);
 *   the vibration at the junction is the "now".
 * - Two close turns in one sentence (the merge distance of [JunctionCues]); the kind of way when it changes; a quiet
 *   stretch after it ≥ [BIKE_STRAIGHT_M] / [HIKE_STRAIGHT_M] is said ("then 3 km straight on").
 * - At least [GAP_MS] between sentences; off route / arrival / no destination never wait. A sentence that had to wait
 *   is dropped after [STALE_MS]; a junction is simply said at the next fix (with its distance then).
 * - Quiet while paused; "No GPS" once after [GPS_LOST_MS].
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
        /** To go for the start sentence (time model); null = not known yet. */
        val toGoS: Double? = null,
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
    private var lineLength = 0.0

    private var started = false
    private var lastSpokenMs = Long.MIN_VALUE / 2
    private var lastOffRoute = false
    private var lastRerouted = false
    private var lastVisited: Set<Int>? = null
    private var gpsLostSince: Long? = null
    private var gpsSaid = false
    private val waiting = ArrayDeque<Pair<Phrase, Long>>()

    /** The phrase to say now for this fix, or null. */
    fun update(i: Input): Phrase? {
        if (i.paused) { gpsLostSince = null; return null }
        val newLine = load(i.line, i.version, i.bike)
        // the off-route question: said once when it opens; closed by riding back → "back on the route"
        if (i.offRoute && !lastOffRoute) { lastOffRoute = true; waiting.clear(); return speak(Phrase.OffRoute, i.nowMs) }
        if (!i.offRoute && lastOffRoute) {
            lastOffRoute = false
            if (!(newLine && i.rerouted)) queue(Phrase.BackOnRoute, i.nowMs)
        }
        // before the start sentence a new line is no news – the start sentence tells its length (field 2026-11-01:
        // "Neue Route." came before "Los geht's.")
        if (newLine && i.rerouted && !lastRerouted && started) queue(Phrase.NewRoute(nextJunction(i, closeOnly = true)), i.nowMs)
        lastRerouted = i.rerouted
        // GPS
        if (i.gpsLost) {
            val since = gpsLostSince ?: i.nowMs.also { gpsLostSince = it }
            if (!gpsSaid && i.nowMs - since >= GPS_LOST_MS) { gpsSaid = true; queue(Phrase.NoGps, i.nowMs) }
        } else { gpsLostSince = null; gpsSaid = false }
        // start
        if (!started && i.line.size >= 2 && i.alongM != null && i.onRoute) {
            started = true
            val dest = i.stops.lastOrNull()?.name
            queue(Phrase.Start((lineLength - i.alongM).coerceAtLeast(0.0), i.toGoS, dest), i.nowMs)
        }
        // stops reached (not the start, not the destination – the arrival is its own event)
        val prev = lastVisited
        lastVisited = i.visited
        if (prev != null) {
            for (k in i.visited.sorted()) {
                if (k in prev || k == 0 || k >= i.stops.size - 1) continue
                val next = (k + 1 until i.stops.size).firstOrNull { it !in i.visited }?.let { i.stops[it] }
                val nextM = if (next != null && !i.rerouted && i.alongM != null) (next.alongM - i.alongM).takeIf { it > 0 } else null
                queue(Phrase.StopReached(i.stops[k].name, k + 1, next?.name, nextM), i.nowMs)
            }
        }
        // a sentence that waited for the gap
        while (waiting.isNotEmpty() && i.nowMs - waiting.first().second > STALE_MS) waiting.removeFirst()
        if (i.nowMs - lastSpokenMs < GAP_MS) return null
        waiting.removeFirstOrNull()?.let { return speak(it.first, i.nowMs) }
        // junctions
        if (!i.onRoute || i.alongM == null) return null
        val j = nextJunction(i, closeOnly = false) ?: return null
        return speak(j, i.nowMs)
    }

    /** An app event (arrival, nothing ahead after an edit): said at once, never waits. */
    fun event(p: Phrase, nowMs: Long): Phrase { waiting.clear(); return speak(p, nowMs) }

    /** The junction to announce now ([closeOnly]: the first one ahead if within twice the lead, for "New route"). */
    private fun nextJunction(i: Input, closeOnly: Boolean): Phrase.Junction? {
        val along = i.alongM ?: return null
        val speed = i.speedMps?.takeIf { it.isFinite() && it >= 0 } ?: if (bike) SpeedWindow.FALLBACK_MPS else JunctionCues.WALK_FALLBACK_MPS
        val lead = if (bike) (speed * BIKE_LEAD_S).coerceIn(BIKE_MIN_M, BIKE_MAX_M) else (speed * HIKE_LEAD_S).coerceIn(HIKE_MIN_M, HIKE_MAX_M)
        val k = alongs.indices.firstOrNull { needed[it] && it !in said && alongs[it] >= along - JunctionCues.PASSED_M } ?: return null
        val d = alongs[k] - along
        if (d > (if (closeOnly) 2 * lead else lead)) return null
        said += k
        if (d < TOO_LATE_M) return null // the vibration says it now
        val merge = if (bike) JunctionCues.bikeMergeM(speed) else JunctionCues.hikeMergeM(speed)
        val j = alongs.indices.firstOrNull { it > k && needed[it] }?.takeIf { alongs[it] - alongs[k] < merge }
        if (j != null) said += j
        val last = j ?: k
        val after = alongs.indices.firstOrNull { it > last && needed[it] }?.let { alongs[it] } ?: lineLength
        val straight = (after - alongs[last]).takeIf { it >= (if (bike) BIKE_STRAIGHT_M else HIKE_STRAIGHT_M) }
        return Phrase.Junction(d.coerceAtLeast(0.0), turns[k], exits[k], onto[k], j?.let { turns[it] }, j?.let { exits[it] } ?: 0, straight)
    }

    private fun queue(p: Phrase, nowMs: Long) { if (waiting.size < 3) waiting.addLast(p to nowMs) }

    private fun speak(p: Phrase, nowMs: Long): Phrase { lastSpokenMs = nowMs; return p }

    /** True when a new line was loaded (not the first one). */
    private fun load(line: List<GeoPoint>, v: Int, isBike: Boolean): Boolean {
        if (v == version && line.size == size && isBike == bike) return false
        val first = version == Int.MIN_VALUE
        version = v; size = line.size; bike = isBike
        val cum = if (line.size >= 2) Geo.cumulative(line) else DoubleArray(line.size)
        lineLength = cum.lastOrNull() ?: 0.0
        val idx = line.indices.filter { line[it].turn != null }
        alongs = DoubleArray(idx.size) { cum[idx[it]] }
        turns = idx.map { Turn.of(line[it].turn!!) }
        exits = IntArray(idx.size) { line[idx[it]].exit }
        onto = Array(idx.size) { wayKind(line[idx[it]], isBike) }
        needed = BooleanArray(idx.size) { CueRule.needed(line[idx[it]].turn!!, line[idx[it]].shape, hike = !isBike) }
        said.clear()
        return !first
    }

    companion object {
        const val BIKE_LEAD_S = 15.0
        const val BIKE_MIN_M = 60.0
        const val BIKE_MAX_M = 200.0
        const val HIKE_LEAD_S = 40.0
        const val HIKE_MIN_M = 40.0
        const val HIKE_MAX_M = 80.0
        /** Closer than this the sentence says "now". */
        const val SOON_M = 30.0
        /** Closer than this nothing is said any more – the vibration comes. */
        const val TOO_LATE_M = 12.0
        const val BIKE_STRAIGHT_M = 2_000.0
        const val HIKE_STRAIGHT_M = 1_000.0
        const val GAP_MS = 10_000L
        const val STALE_MS = 30_000L
        const val GPS_LOST_MS = 30_000L

        /** The kind of way the route turns onto – only when it is another kind than the way it comes on. */
        fun wayKind(p: GeoPoint, bike: Boolean): WayKind? {
            val s = p.shape ?: return null
            val out = kindOf(s.outPrio, bike)
            return out?.takeIf { it != kindOf(s.inPrio, bike) }
        }

        private fun kindOf(prio: Int, bike: Boolean): WayKind? =
            if (bike) {
                when (CueRule.bikeKind(prio)) { 1 -> WayKind.TRACK; 3 -> WayKind.ROAD; else -> null }
            } else {
                val c = CueRule.hikeClass(prio)
                when { c == 1 -> WayKind.PATH; c == 2 -> WayKind.TRACK; c >= 18 -> WayKind.ROAD; else -> null }
            }
    }
}
