package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import com.onpath.app.routing.Turn
import com.onpath.app.routing.TurnDetector

/**
 * The turns the Navigate screen shows (turn card, arrow on the map, the rest view's wake-up) – the same junctions the
 * phone vibrates for and the voice names (owner 2026-11-01, Hilden: the card said "right", then "slight left" at a
 * pedestrian crossing the voice rightly kept quiet about, and "hairpin right" for two park junctions 28 m apart).
 * Pure, app only, unit-tested (`ScreenTurnsTest`).
 *
 * - A routed line (BRouter's hints): every hint worth a cue ([JogRule.needed] = [CueRule] + close opposite turns as one
 *   step), with the router's own angle at that junction. Roundabouts are left to the roundabout card (JunctionCues).
 * - A line without hints (a GPX track, a straight line): the geometry ([TurnDetector.turns]), as before.
 */
object ScreenTurns {
    /** Heading before / after a junction is measured over this far (for the card's bearings only). */
    private const val ARM_M = 15.0

    fun of(given: List<GeoPoint>, hike: Boolean): List<Turn> {
        if (given.size < 2) return emptyList()
        if (given.none { it.turn != null }) return TurnDetector.turns(given)
        val line = JogRule.approach(given, hike)   // a corner drawn as a curve: its whole turn
        val cum = Geo.cumulative(line)
        val idx = line.indices.filter { line[it].turn != null }
        val needed = JogRule.needed(line, cum, idx, hike)
        val hints = idx.indices.filter { needed[it] && line[idx[it]].turn != JunctionCue.ROUNDABOUT }.map { k ->
            val i = idx[k]
            val p = line[i]
            val before = near(line, cum, i, -ARM_M); val after = near(line, cum, i, ARM_M)
            Turn(i, p, TurnDetector.bearing(before, p), TurnDetector.bearing(p, after), JogRule.angleOf(p).toDouble(), cum[i])
        }
        // where the route goes back the way it came (no junction, so no hint): "Umdrehen" (owner 2026-11-01)
        val back = Reversals.of(line, cum).filter { r -> hints.none { kotlin.math.abs(it.distanceFromStart - cum[r]) < Reversals.SAME_M } }
            .map { i ->
                val p = line[i]
                Turn(i, p, TurnDetector.bearing(near(line, cum, i, -ARM_M), p), TurnDetector.bearing(p, near(line, cum, i, ARM_M)),
                    -180.0, cum[i], turnRound = true)
            }
        return (hints + back).sortedBy { it.distanceFromStart }
    }

    /** The line vertex about [dM] metres from vertex [i] (the end at most). */
    private fun near(line: List<GeoPoint>, cum: DoubleArray, i: Int, dM: Double): GeoPoint {
        var j = i
        if (dM < 0) { while (j > 0 && cum[i] - cum[j] < -dM) j-- } else { while (j < line.size - 1 && cum[j] - cum[i] < dM) j++ }
        return line[j]
    }
}

/**
 * Where a routed line goes back the way it came – an out-and-back to a plan point at the end of a dead end (owner
 * 2026-11-01, replay of tour 63, Stopp 6 in Fabriciusstraße: "da hätten wir wenden müssen" – no junction, so BRouter
 * gives no hint and nothing said "turn round"). A vertex where the heading over [ARM_M] before and after it differs by
 * at least [MIN_DEG]; a run of such vertices is one (the sharpest). Pure, app only (ScreenTurns, VoiceGuide).
 */
object Reversals {
    const val ARM_M = 10.0
    const val MIN_DEG = 150.0
    /** Candidates this close are one reversal; a hint this close names it already. */
    const val SAME_M = 15.0

    /** Vertex indices of [line] ([cum] = its cumulative metres) where it turns back. */
    fun of(line: List<GeoPoint>, cum: DoubleArray): List<Int> {
        val out = ArrayList<Int>()
        var first = -1; var last = -1
        // a run of candidates is one reversal: its middle (the arms see it from both sides)
        fun close() { if (first >= 0) out += (first..last).minBy { kotlin.math.abs(cum[it] - (cum[first] + cum[last]) / 2) } }
        for (i in 1 until line.size - 1) {
            if (cum[i] < ARM_M || cum.last() - cum[i] < ARM_M) continue
            val t = line[i].turn
            if (t == JunctionCue.U_TURN_LEFT || t == JunctionCue.U_TURN_RIGHT) continue
            val a = kotlin.math.abs(diff(TurnDetector.bearing(back(line, cum, i), line[i]), TurnDetector.bearing(line[i], ahead(line, cum, i))))
            if (a < MIN_DEG) continue
            if (first >= 0 && cum[i] - cum[last] < SAME_M) { last = i; continue }
            close()
            first = i; last = i
        }
        close()
        return out
    }

    private fun back(line: List<GeoPoint>, cum: DoubleArray, i: Int): GeoPoint {
        var j = i; while (j > 0 && cum[i] - cum[j] < ARM_M) j--; return line[j]
    }
    private fun ahead(line: List<GeoPoint>, cum: DoubleArray, i: Int): GeoPoint {
        var j = i; while (j < line.size - 1 && cum[j] - cum[i] < ARM_M) j++; return line[j]
    }
    private fun diff(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0
}
