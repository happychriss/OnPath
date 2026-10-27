package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue

/**
 * When to vibrate before a junction of the followed line (owner 2026-10-22). Pure, unit-tested; the recording service
 * calls [update] on every GPS fix and plays the returned cue.
 *
 * - A junction = a route point with [GeoPoint.turn] (BRouter's turn instructions – real junctions only).
 * - Cue once, when the junction is [aheadM] or less ahead (hike 40 m, bike 80 m); never after it was passed.
 * - Switchbacks: a junction within [MERGE_M] after the previous junction gets no cue of its own (a chain of hairpins
 *   is felt as one cue – at its first junction).
 * - Nothing while off the route ([update] with onRoute = false) – the off-route warning speaks then.
 * - A new line (re-route, edited plan: [version]) starts over; junctions behind the position are skipped.
 */
class JunctionCues(var aheadM: Double = HIKE_AHEAD_M) {
    private var version = Int.MIN_VALUE
    private var size = -1
    private var alongs = DoubleArray(0)
    private var cues = emptyList<JunctionCue>()
    private var next = 0

    /** The cue to play now, or null. [alongM] = progress on [line] (null = not matched). */
    fun update(line: List<GeoPoint>, version: Int, alongM: Double?, onRoute: Boolean): JunctionCue? {
        load(line, version)
        if (alongM == null) return null
        // passed junctions never cue (also the ones passed while off route)
        while (next < alongs.size && alongs[next] < alongM - PASSED_M) next++
        if (!onRoute || next >= alongs.size) return null
        val d = alongs[next] - alongM
        if (d > aheadM) return null
        val at = alongs[next]; val cue = cues[next]
        val prev = if (next > 0) alongs[next - 1] else Double.NEGATIVE_INFINITY
        next++
        return if (at - prev < MERGE_M) null else cue // switchback chain: felt once, at its first junction
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

    private fun load(line: List<GeoPoint>, v: Int) {
        if (v == version && line.size == size) return
        version = v; size = line.size
        val cum = if (line.size >= 2) Geo.cumulative(line) else DoubleArray(line.size)
        val idx = line.indices.filter { line[it].turn != null }
        alongs = DoubleArray(idx.size) { cum[idx[it]] }
        cues = idx.map { line[it].turn!! }
        next = 0
    }

    companion object {
        const val HIKE_AHEAD_M = 40.0
        const val BIKE_AHEAD_M = 80.0
        const val MERGE_M = 50.0
        const val PASSED_M = 5.0

        /** Vibration timings (off, on, off, on …): 2 short pulses = left, 3 = right. */
        fun pattern(side: JunctionCue.Side): LongArray = when (side) {
            JunctionCue.Side.LEFT -> longArrayOf(0, 110, 140, 110)
            JunctionCue.Side.RIGHT -> longArrayOf(0, 110, 140, 110, 140, 110)
        }
    }
}

/**
 * The recording notification while a route is followed – readable on the lock screen (owner 2026-10-22): next
 * junction, time to go, arrival, distance to go. Pure, unit-tested.
 */
object NavNotification {
    data class Text(val title: String, val text: String)

    /**
     * [status]: "Recording" / "Paused" / "Off route" / "Searching GPS…" ("Recording" is not repeated);
     * [junction] within [JUNCTION_TITLE_M] becomes the title; [clock] formats the arrival (epoch ms → "15:40").
     */
    fun of(status: String, junction: Pair<JunctionCue, Double>?, toGoM: Double?, toGoS: Double?, arrivalMs: Long?,
           distance: (Double) -> String, duration: (Double) -> String, clock: (Long) -> String): Text? {
        if (toGoM == null) return null
        val distanceInTitle = status == "Recording" && !(junction != null && junction.second <= JUNCTION_TITLE_M)
        val title = when {
            status != "Recording" -> status
            !distanceInTitle -> "${junction!!.first.text} in ${distance(roundDist(junction.second))}"
            else -> "${distance(toGoM)} to go"
        }
        // The distance is said once: in the title ("8.2 km to go") or, when the title is a status / junction, here.
        val parts = buildList {
            if (toGoS != null) add(if (distanceInTitle) duration(toGoS) else "${duration(toGoS)} to go")
            if (arrivalMs != null) add("arrival ${clock(arrivalMs)}")
            if (!distanceInTitle) add("${distance(toGoM)} left")
        }
        return Text(title, parts.joinToString(" · "))
    }

    /** "120 m" not "117 m": steps of 10 m (50 m above 500 m), so the notification changes calmly. */
    fun roundDist(m: Double): Double = if (m < 500) Math.round(m / 10.0) * 10.0 else Math.round(m / 50.0) * 50.0

    const val JUNCTION_TITLE_M = 500.0
}
