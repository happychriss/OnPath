package com.onpath.app.navigation

import com.onpath.strings.*

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import com.onpath.app.time.TimeEstimate

/**
 * When to vibrate before a junction of the followed line. Pure, unit-tested; the recording service
 * calls [update] on every GPS fix and plays the returned cue. Overview of all cues: docs/design/navigation-cues.md.
 *
 * - A junction = a route point with [GeoPoint.turn] (BRouter's turn instructions – real junctions only).
 * - Only where one could go wrong ([CueRule]: clear turns, forks with a similar way, roundabouts, U-turns – not where
 *   the route simply goes on along the street / trail or the clearly straightest way). Owner 2026-10-30: the bike first
 *   (in a town the old rules vibrated ~40 times in 50 min), then the hike the same way ([bike] = false: [CueRule] with
 *   the hiking classes).
 * - Cue once, a time before the junction at the current speed: bike [LEAD_S] ([MIN_LEAD_M]…[MAX_LEAD_M]), on foot
 *   [HIKE_LEAD_S] ([HIKE_MIN_LEAD_M]…[HIKE_MAX_LEAD_M]); never after it was passed.
 * - Close junctions are one cue: a junction within [MERGE_S] of riding (on foot [HIKE_MERGE_S] of walking) – at least
 *   [MERGE_M] – after the previous junction worth a cue gets none. A chain of close turns / switchbacks is felt once,
 *   before its first turn, with that turn's pattern (the map still wakes for each of them, ui/SmartScreen).
 * - Nothing while off the route ([update] with onRoute = false) – the off-route warning speaks then.
 * - A new line (re-route, edited plan: [version]) starts over; junctions behind the position are skipped.
 * The lock screen still names every junction ([nextAheadText]).
 */
class JunctionCues {
    /** The bike's timing and classes; false = on foot. */
    var bike: Boolean = false
    private var version = Int.MIN_VALUE
    private var size = -1
    private var loadedBike = false
    private var alongs = DoubleArray(0)
    private var cues = emptyList<JunctionCue>()
    private var exits = IntArray(0)
    private var angles = FloatArray(0)
    /** Worth a cue ([CueRule]); and where the previous junction worth a cue is (−∞ = none). */
    private var needed = BooleanArray(0)
    private var prevNeeded = DoubleArray(0)
    private var next = 0

    /**
     * The cue to play now, or null. [alongM] = progress on [line] (null = not matched); [speedMps] = the speed to plan
     * with ([SpeedWindow]; null = unknown: about 22 km/h on the bike, 5 km/h on foot).
     */
    fun update(line: List<GeoPoint>, version: Int, alongM: Double?, onRoute: Boolean, speedMps: Double? = null): JunctionCue? {
        load(line, version)
        if (alongM == null) return null
        // passed junctions never cue (also the ones passed while off route)
        while (next < alongs.size && alongs[next] < alongM - PASSED_M) next++
        if (!onRoute || next >= alongs.size) return null
        val speed = speedMps?.takeIf { it.isFinite() && it >= 0 } ?: if (bike) SpeedWindow.FALLBACK_MPS else WALK_FALLBACK_MPS
        val d = alongs[next] - alongM
        if (d > (if (bike) bikeLeadM(speed) else hikeLeadM(speed))) return null
        val i = next++
        if (!needed[i]) return null
        // close turns: felt once, before the first of them
        val merge = if (bike) bikeMergeM(speed) else hikeMergeM(speed)
        return if (alongs[i] - prevNeeded[i] < merge) null else cues[i]
    }

    /** Next junction ahead of [alongM] within [maxM] and its distance (lock screen "Left in 120 m"). */
    fun nextAhead(line: List<GeoPoint>, version: Int, alongM: Double?, maxM: Double = 1_000.0): Pair<JunctionCue, Double>? {
        load(line, version)
        if (alongM == null) return null
        for (i in alongs.indices) {
            val d = alongs[i] - alongM
            if (d < -PASSED_M) continue
            return if (d <= maxM) cues[i] to d.coerceAtLeast(0.0) else null
        }
        return null
    }

    /** Like [nextAhead], as the lock screen says it: "Roundabout · exit 2" / "Left" and the distance. */
    fun nextAheadText(line: List<GeoPoint>, version: Int, alongM: Double?, maxM: Double = 1_000.0): Pair<String, Double>? {
        load(line, version)
        if (alongM == null) return null
        for (i in alongs.indices) {
            val d = alongs[i] - alongM
            if (d < -PASSED_M) continue
            return if (d <= maxM) cues[i].text(exits[i]) to d.coerceAtLeast(0.0) else null
        }
        return null
    }

    /**
     * Next roundabout ahead within [maxM]: its exit (0 = unknown) and distance – the on-screen hint shows a roundabout
     * sign instead of the plain arrow.
     */
    fun roundaboutAhead(line: List<GeoPoint>, version: Int, alongM: Double?, maxM: Double = 500.0): Pair<Int, Double>? =
        roundabout(line, version, alongM, maxM)?.let { it.exit to it.distanceM }

    /** The next roundabout ahead within [maxM] with exit, angle and distance (the drawn roundabout sign). */
    fun roundabout(line: List<GeoPoint>, version: Int, alongM: Double?, maxM: Double = 500.0): Roundabout? {
        load(line, version)
        if (alongM == null) return null
        for (i in alongs.indices) {
            val d = alongs[i] - alongM
            if (d < -PASSED_M) continue
            if (d > maxM) return null
            if (cues[i] == JunctionCue.ROUNDABOUT) return Roundabout(exits[i], angles[i], d.coerceAtLeast(0.0))
        }
        return null
    }

    /** A roundabout ahead: [exit] to take (0 = unknown), [angle] of the whole turn (> 0 right), [distanceM] to it. */
    data class Roundabout(val exit: Int, val angle: Float, val distanceM: Double)

    private fun load(line: List<GeoPoint>, v: Int) {
        if (v == version && line.size == size && bike == loadedBike) return
        version = v; size = line.size; loadedBike = bike
        val cum = if (line.size >= 2) Geo.cumulative(line) else DoubleArray(line.size)
        val idx = line.indices.filter { line[it].turn != null }
        alongs = DoubleArray(idx.size) { cum[idx[it]] }
        cues = idx.map { line[it].turn!! }
        exits = IntArray(idx.size) { line[idx[it]].exit }
        angles = FloatArray(idx.size) { line[idx[it]].turnAngle }
        needed = BooleanArray(idx.size) { CueRule.needed(cues[it], line[idx[it]].shape, hike = !bike) }
        var prev = Double.NEGATIVE_INFINITY
        prevNeeded = DoubleArray(idx.size) { k -> prev.also { if (needed[k]) prev = alongs[k] } }
        next = 0
    }

    companion object {
        /** Close junctions: at least this far apart to get a cue each (a chain of hairpins is one cue). */
        const val MERGE_M = 50.0
        const val PASSED_M = 5.0

        /** Bike: the cue comes this many seconds before the junction at the current speed … */
        const val LEAD_S = 8.0
        /** … but at least this far before it (pushing the bike, a stop at the lights) … */
        const val MIN_LEAD_M = 30.0
        /** … and at most this far (fast downhill: not so early that the turn before it is mixed up). */
        const val MAX_LEAD_M = 100.0
        /** Bike: a junction this many seconds of riding after the previous one is part of its cue (close turns). */
        const val MERGE_S = 12.0

        fun bikeLeadM(speedMps: Double): Double = (speedMps * LEAD_S).coerceIn(MIN_LEAD_M, MAX_LEAD_M)
        fun bikeMergeM(speedMps: Double): Double = maxOf(MERGE_M, speedMps * MERGE_S)

        /** On foot: the cue comes this many seconds before the junction (was a fixed 40 m) … */
        const val HIKE_LEAD_S = 25.0
        /** … at least this far before it (a steep climb) … */
        const val HIKE_MIN_LEAD_M = 25.0
        /** … at most this far (a jump of the GPS speed; not mixed up with the junction before it). */
        const val HIKE_MAX_LEAD_M = 60.0
        /** On foot: a junction this many seconds of walking after the previous one is part of its cue. */
        const val HIKE_MERGE_S = 30.0
        /** On foot, speed unknown (no fix with a speed yet): 5 km/h. */
        const val WALK_FALLBACK_MPS = 1.4

        fun hikeLeadM(speedMps: Double): Double = (speedMps * HIKE_LEAD_S).coerceIn(HIKE_MIN_LEAD_M, HIKE_MAX_LEAD_M)
        fun hikeMergeM(speedMps: Double): Double = maxOf(MERGE_M, speedMps * HIKE_MERGE_S)

        /** The bike's junction cues ([bike] rules). */
        fun bike(): JunctionCues = JunctionCues().apply { bike = true }

        /** Junctions on [line] – traced once per followed line ("0" = a GPX track / straight line: nothing to cue). */
        fun count(line: List<GeoPoint>): Int = line.count { it.turn != null }

        /**
         * Vibration timings (off, on, off, on …): 1 pulse = left, 2 pulses = right (owner). Pulses of a
         * quarter second, so one is felt through a jacket on the bike, the gap clearly apart.
         */
        fun pattern(side: JunctionCue.Side): LongArray = when (side) {
            JunctionCue.Side.LEFT -> longArrayOf(0, PULSE_MS)
            JunctionCue.Side.RIGHT -> longArrayOf(0, PULSE_MS, GAP_MS, PULSE_MS)
        }
        const val PULSE_MS = 250L
        const val GAP_MS = 220L
    }
}

/**
 * The recording notification while a route is followed – readable on the lock screen: next
 * junction, time to go, arrival, distance to go. Pure, unit-tested.
 */
object NavNotification {
    data class Text(val title: String, val text: String)

    /**
     * [status]: "Recording" / "Paused" / "Off route" / "Searching GPS…" ("Recording" is not repeated);
     * [junction] within [JUNCTION_TITLE_M] becomes the title; the arrival is written by [TimeEstimate.clock] – the
     * same 5-minute rounding as in the app (owner: "10:20" on both, never "10:22" on the lock screen).
     */
    fun of(status: String, junction: Pair<String, Double>?, toGoM: Double?, toGoS: Double?, arrivalMs: Long?,
           distance: (Double) -> String, duration: (Double) -> String): Text? {
        if (toGoM == null) return null
        // the plain status (same text as RecordingService.notifStatus, so it works in every language)
        val recording = status == Txt.s(Res.string.nav_status_recording)
        val distanceInTitle = recording && !(junction != null && junction.second <= JUNCTION_TITLE_M)
        val title = when {
            !recording -> status
            !distanceInTitle -> Txt.s(Res.string.nav_turn_in, junction!!.first, distance(roundDist(junction.second)))
            else -> Txt.s(Res.string.nav_notif_to_go, distance(toGoM))
        }
        // The distance is said once: in the title ("8.2 km to go") or, when the title is a status / junction, here.
        val parts = buildList {
            if (toGoS != null) add(if (distanceInTitle) duration(toGoS) else Txt.s(Res.string.nav_notif_to_go, duration(toGoS)))
            if (arrivalMs != null) add(Txt.s(Res.string.nav_notif_arrival, TimeEstimate.clock(arrivalMs)))
            if (!distanceInTitle) add(Txt.s(Res.string.nav_notif_left, distance(toGoM)))
        }
        return Text(title, parts.joinToString(" · "))
    }

    /** "120 m" not "117 m": steps of 10 m (50 m above 500 m), so the notification changes calmly. */
    fun roundDist(m: Double): Double = if (m < 500) Math.round(m / 10.0) * 10.0 else Math.round(m / 50.0) * 50.0

    const val JUNCTION_TITLE_M = 500.0
}
