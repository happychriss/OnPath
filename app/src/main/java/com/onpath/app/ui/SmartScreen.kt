package com.onpath.app.ui

import com.onpath.app.data.AppSettings
import com.onpath.app.data.BikeScreen
import com.onpath.app.data.Geo
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import com.onpath.app.routing.Turn
import com.onpath.app.routing.TurnKind

/**
 * What the screen does while following a route in Navigate (owner 2026-10-30). Pure, unit-tested (`SmartScreenTest`).
 * Hike: unchanged – "keep screen on" with the pocket logic (ui/PocketScreen.kt), never the rest view. Bike activities:
 * Settings → Navigation → "Screen while cycling" ([BikeScreen]); the handlebar has no pocket, so no proximity sensor
 * (a hand reaching for the screen would turn it off).
 */
data class ScreenPolicy(
    /** Keep the screen on while following a route. */
    val keepOn: Boolean,
    /** Proximity sensor turns the screen off when covered (pocket). */
    val pocket: Boolean,
    /** The rest view may replace the map far from the next turn (SmartScreen decides when). */
    val rest: Boolean,
) {
    companion object {
        fun of(s: AppSettings, activity: Activity): ScreenPolicy =
            if (activity == Activity.HIKE) ScreenPolicy(keepOn = s.keepScreenOn, pocket = true, rest = false)
            else ScreenPolicy(keepOn = s.bikeScreen.keepsOn, pocket = false, rest = s.bikeScreen == BikeScreen.SMART)
    }
}

/**
 * What comes next on the followed line for the rest view: a turn ([kind]), a roundabout ([roundabout] = exit, 0 =
 * unknown), or the destination (both null). [atM] = metres from the start of the line.
 */
data class RestEvent(val atM: Double, val kind: TurnKind?, val roundabout: Int? = null, val angle: Float? = null,
                     internal val rank: Int = RANK_END) {
    val isDestination: Boolean get() = kind == null && roundabout == null

    companion object {
        internal const val RANK_CUE = 1
        internal const val RANK_TURN = 2
        internal const val RANK_ROUNDABOUT = 3
        internal const val RANK_END = 0
    }
}

/** The next [event] and the distance to it; [key] tells one event from the next (a new key = the last one is passed). */
data class RestTarget(val event: RestEvent, val distanceM: Double) {
    val key: Long get() = Math.round(event.atM * 10)
}

/**
 * The places the full map comes back for: every turn of the turn card ([com.onpath.app.routing.TurnDetector]), every
 * junction the bike vibrates for (BRouter's instructions without slight bends – [com.onpath.app.navigation.JunctionCues]
 * with skipSlight), every roundabout, and the destination. Events closer than [MERGE_M] are one place (the earlier
 * position, the clearer look: roundabout > drawn turn > junction cue).
 */
object RestEvents {
    const val MERGE_M = 30.0
    /** Passed this far: the next one counts (like TurnDetector.nextTurn). */
    const val PASSED_M = 5.0

    fun of(line: List<GeoPoint>, turns: List<Turn>): List<RestEvent> {
        if (line.size < 2) return emptyList()
        val cum = Geo.cumulative(line)
        val all = ArrayList<RestEvent>()
        turns.forEach { all += RestEvent(it.distanceFromStart, it.kind, rank = RestEvent.RANK_TURN) }
        for (i in line.indices) {
            val c = line[i].turn ?: continue
            when (c) {
                JunctionCue.ROUNDABOUT -> all += RestEvent(cum[i], TurnKind.of(line[i].turnAngle.toDouble()), roundabout = line[i].exit,
                    angle = line[i].turnAngle, rank = RestEvent.RANK_ROUNDABOUT)
                JunctionCue.SLIGHT_LEFT, JunctionCue.SLIGHT_RIGHT -> {} // the bike does not vibrate for them either
                else -> all += RestEvent(cum[i], kindOf(c), rank = RestEvent.RANK_CUE)
            }
        }
        all.sortBy { it.atM }
        val merged = ArrayList<RestEvent>()
        for (e in all) {
            val prev = merged.lastOrNull()
            if (prev != null && e.atM - prev.atM < MERGE_M) {
                if (e.rank > prev.rank) merged[merged.lastIndex] = e.copy(atM = prev.atM)
            } else merged += e
        }
        merged += RestEvent(cum.last(), null)
        return merged
    }

    /** The next event ahead of [alongM] (metres from the start); null = none (past the end) or not matched. */
    fun next(events: List<RestEvent>, alongM: Double?): RestTarget? {
        if (alongM == null) return null
        val e = events.firstOrNull { if (it.isDestination) it.atM > alongM else it.atM > alongM + PASSED_M } ?: return null
        return RestTarget(e, (e.atM - alongM).coerceAtLeast(0.0))
    }

    private fun kindOf(c: JunctionCue): TurnKind = when (c) {
        JunctionCue.LEFT -> TurnKind.LEFT
        JunctionCue.SHARP_LEFT -> TurnKind.SHARP_LEFT
        JunctionCue.KEEP_LEFT, JunctionCue.SLIGHT_LEFT -> TurnKind.SLIGHT_LEFT
        JunctionCue.U_TURN_LEFT -> TurnKind.U_TURN_LEFT
        JunctionCue.RIGHT -> TurnKind.RIGHT
        JunctionCue.SHARP_RIGHT -> TurnKind.SHARP_RIGHT
        JunctionCue.KEEP_RIGHT, JunctionCue.SLIGHT_RIGHT -> TurnKind.SLIGHT_RIGHT
        JunctionCue.U_TURN_RIGHT -> TurnKind.U_TURN_RIGHT
        JunctionCue.ROUNDABOUT -> TurnKind.RIGHT
    }
}

/**
 * When the rest view shows instead of the map (owner 2026-10-30). The full map comes back:
 * - [LEAD_S] before the next event at the current speed (the highest of the last [SPEED_WINDOW_MS]), but at least
 *   [MIN_LEAD_M] before it, and stays until [AFTER_MS] after it is passed;
 * - for [TAP_MS] after the last touch of the screen (a tap on the rest view, a pan, a button);
 * - while something needs the user ([In.attention]: off route, arrival question, GPS lost / waiting, a dialog, paused,
 *   not following the position) and [AFTER_MS] after it.
 * Everything else (far from the next turn, nothing to answer): the rest view. One instance per Navigate screen; clock =
 * any monotonic milliseconds (the app: uptime, like the touch time).
 */
class SmartScreen {
    companion object {
        const val LEAD_S = 50.0
        const val MIN_LEAD_M = 150.0
        const val AFTER_MS = 10_000L
        const val TAP_MS = 30_000L
        const val SPEED_WINDOW_MS = 10_000L
        /** Speed unknown (no fix with a speed yet): about 22 km/h. */
        const val FALLBACK_SPEED_MPS = 6.0
        /** Held for an event: let go only when it is this much farther than the largest lead it was held with. */
        const val RELEASE_SLACK_M = 100.0
    }

    /**
     * [on] = the rest view is allowed now (Smart, bike activity, a tour follows a line in Navigate); [targetKey] /
     * [targetM] = the next event ([RestTarget]); [touchedMs] = last finger-down on the screen (0 = never).
     */
    data class In(val on: Boolean, val attention: Boolean, val targetKey: Long?, val targetM: Double?, val touchedMs: Long = 0)

    private var heldKey: Long? = null
    private var heldLeadM = 0.0
    private var afterUntil = Long.MIN_VALUE
    private var attentionUntil = Long.MIN_VALUE
    private var tapUntil = Long.MIN_VALUE
    private val speeds = ArrayDeque<Pair<Long, Double>>()

    /** Every fix: its speed (m/s; null = the fix has none). */
    fun noteSpeed(mps: Double?, nowMs: Long) {
        if (mps != null && mps.isFinite() && mps >= 0) speeds.addLast(nowMs to mps)
        while (speeds.isNotEmpty() && speeds.first().first < nowMs - SPEED_WINDOW_MS) speeds.removeFirst()
    }

    /** Speed the lead is computed with: the highest of the last [SPEED_WINDOW_MS] (speeding up counts at once). */
    fun speed(nowMs: Long): Double =
        speeds.filter { it.first >= nowMs - SPEED_WINDOW_MS }.maxOfOrNull { it.second } ?: FALLBACK_SPEED_MPS

    /** How far before an event the map comes back now. */
    fun leadM(nowMs: Long): Double = maxOf(MIN_LEAD_M, speed(nowMs) * LEAD_S)

    /** True = show the rest view now. */
    fun rest(i: In, nowMs: Long): Boolean {
        if (!i.on) { reset(); return false }
        if (i.touchedMs > 0) tapUntil = maxOf(tapUntil, i.touchedMs + TAP_MS)
        if (i.attention) attentionUntil = nowMs + AFTER_MS
        val held = heldKey
        // the event the map came back for is passed (or the line changed): the map stays a little longer
        if (held != null && held != i.targetKey) { afterUntil = nowMs + AFTER_MS; heldKey = null }
        val lead = leadM(nowMs)
        if (i.targetKey != null && i.targetM != null) {
            if (heldKey == null) {
                if (i.targetM <= lead) { heldKey = i.targetKey; heldLeadM = lead }
            } else {
                heldLeadM = maxOf(heldLeadM, lead)
                // moved away from it again (turned round, a long stop before it): rest again
                if (i.targetM > heldLeadM + RELEASE_SLACK_M) heldKey = null
            }
        }
        val map = i.attention || heldKey != null || nowMs < afterUntil || nowMs < attentionUntil || nowMs < tapUntil
        return !map
    }

    /** When [rest] may change by time alone (a hold ends): ms from [nowMs]; 1 s when nothing is pending. */
    fun recheckInMs(nowMs: Long): Long =
        listOf(afterUntil, attentionUntil, tapUntil).filter { it > nowMs }.minOrNull()?.let { it - nowMs + 1 } ?: 1_000L

    private fun reset() {
        heldKey = null; heldLeadM = 0.0
        afterUntil = Long.MIN_VALUE; attentionUntil = Long.MIN_VALUE; tapUntil = Long.MIN_VALUE
    }
}
