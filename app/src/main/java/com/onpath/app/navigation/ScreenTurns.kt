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

    fun of(line: List<GeoPoint>, hike: Boolean): List<Turn> {
        if (line.size < 2) return emptyList()
        if (line.none { it.turn != null }) return TurnDetector.turns(line)
        val cum = Geo.cumulative(line)
        val idx = line.indices.filter { line[it].turn != null }
        val needed = JogRule.needed(line, cum, idx, hike)
        return idx.indices.filter { needed[it] && line[idx[it]].turn != JunctionCue.ROUNDABOUT }.map { k ->
            val i = idx[k]
            val p = line[i]
            val before = near(line, cum, i, -ARM_M); val after = near(line, cum, i, ARM_M)
            Turn(i, p, TurnDetector.bearing(before, p), TurnDetector.bearing(p, after), JogRule.angleOf(p).toDouble(), cum[i])
        }
    }

    /** The line vertex about [dM] metres from vertex [i] (the end at most). */
    private fun near(line: List<GeoPoint>, cum: DoubleArray, i: Int, dM: Double): GeoPoint {
        var j = i
        if (dM < 0) { while (j > 0 && cum[i] - cum[j] < -dM) j-- } else { while (j < line.size - 1 && cum[j] - cum[i] < dM) j++ }
        return line[j]
    }
}
