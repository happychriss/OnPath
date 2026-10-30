package com.onpath.app.navigation

import com.onpath.strings.*

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue

/**
 * When to vibrate before a junction of the followed line. Pure, unit-tested; the recording service
 * calls [update] on every GPS fix and plays the returned cue.
 *
 * - A junction = a route point with [GeoPoint.turn] (BRouter's turn instructions – real junctions only).
 * - Cue once, when the junction is [aheadM] or less ahead (hike 40 m); never after it was passed.
 * - Switchbacks: a junction within [MERGE_M] after the previous junction gets no cue of its own (a chain of hairpins
 *   is felt as one cue – at its first junction).
 * - Nothing while off the route ([update] with onRoute = false) – the off-route warning speaks then.
 * - A new line (re-route, edited plan: [version]) starts over; junctions behind the position are skipped.
 *
 * On the bike ([bike], owner 2026-10-30 – in a town the old rules vibrated ~40 times in 50 min):
 * - only where one could go wrong ([CueRule]: clear turns, forks with a similar way, roundabouts – not where the route
 *   simply goes on along the street or the clearly straightest way);
 * - [LEAD_S] before the junction at the current speed, [MIN_LEAD_M]…[MAX_LEAD_M];
 * - close turns are one cue: a junction within [MERGE_S] riding time (at least [MERGE_M]) after the previous junction
 *   worth a cue gets none – a chain of close turns is felt once, before its first turn, with that turn's pattern (the
 *   map still wakes for each of them, ui/SmartScreen).
 * The lock screen still names every junction ([nextAheadText]).
 */
class JunctionCues(var aheadM: Double = HIKE_AHEAD_M) {
    /** The bike's rules (see the class comment); false = on foot, unchanged. */
    var bike: Boolean = false
    private var version = Int.MIN_VALUE
    private var size = -1
    private var alongs = DoubleArray(0)
    private var cues = emptyList<JunctionCue>()
    private var exits = IntArray(0)
    private var angles = FloatArray(0)
    /** Bike: worth a cue ([CueRule]); and where the previous junction worth a cue is (−∞ = none). */
    private var needed = BooleanArray(0)
    private var prevNeeded = DoubleArray(0)
    private var next = 0

    /**
     * The cue to play now, or null. [alongM] = progress on [line] (null = not matched); [speedMps] = the speed to plan
     * with on the bike ([SpeedWindow]; null = unknown, about 22 km/h).
     */
    fun update(line: List<GeoPoint>, version: Int, alongM: Double?, onRoute: Boolean, speedMps: Double? = null): JunctionCue? {
        load(line, version)
        if (alongM == null) return null
        // passed junctions never cue (also the ones passed while off route)
        while (next < alongs.size && alongs[next] < alongM - PASSED_M) next++
        if (!onRoute || next >= alongs.size) return null
        val speed = speedMps?.takeIf { it.isFinite() && it >= 0 } ?: SpeedWindow.FALLBACK_MPS
        val d = alongs[next] - alongM
        if (d > (if (bike) bikeLeadM(speed) else aheadM)) return null
        val i = next++
        val at = alongs[i]; val cue = cues[i]
        if (!bike) {
            val prev = if (i > 0) alongs[i - 1] else Double.NEGATIVE_INFINITY
            return if (at - prev < MERGE_M) null else cue // switchback chain: felt once, at its first junction
        }
        if (!needed[i]) return null
        // close turns: felt once, before the first of them
        return if (at - prevNeeded[i] < bikeMergeM(speed)) null else cue
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
        if (v == version && line.size == size) return
        version = v; size = line.size
        val cum = if (line.size >= 2) Geo.cumulative(line) else DoubleArray(line.size)
        val idx = line.indices.filter { line[it].turn != null }
        alongs = DoubleArray(idx.size) { cum[idx[it]] }
        cues = idx.map { line[it].turn!! }
        exits = IntArray(idx.size) { line[idx[it]].exit }
        angles = FloatArray(idx.size) { line[idx[it]].turnAngle }
        needed = BooleanArray(idx.size) { CueRule.needed(cues[it], line[idx[it]].shape) }
        var prev = Double.NEGATIVE_INFINITY
        prevNeeded = DoubleArray(idx.size) { k -> prev.also { if (needed[k]) prev = alongs[k] } }
        next = 0
    }

    companion object {
        const val HIKE_AHEAD_M = 40.0
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
     * [junction] within [JUNCTION_TITLE_M] becomes the title; [clock] formats the arrival (epoch ms → "15:40").
     */
    fun of(status: String, junction: Pair<String, Double>?, toGoM: Double?, toGoS: Double?, arrivalMs: Long?,
           distance: (Double) -> String, duration: (Double) -> String, clock: (Long) -> String): Text? {
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
            if (arrivalMs != null) add(Txt.s(Res.string.nav_notif_arrival, clock(arrivalMs)))
            if (!distanceInTitle) add(Txt.s(Res.string.nav_notif_left, distance(toGoM)))
        }
        return Text(title, parts.joinToString(" · "))
    }

    /** "120 m" not "117 m": steps of 10 m (50 m above 500 m), so the notification changes calmly. */
    fun roundDist(m: Double): Double = if (m < 500) Math.round(m / 10.0) * 10.0 else Math.round(m / 50.0) * 50.0

    const val JUNCTION_TITLE_M = 500.0
}
