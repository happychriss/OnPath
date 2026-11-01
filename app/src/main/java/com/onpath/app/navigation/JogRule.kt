package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

/**
 * Close turns are one manoeuvre (owner 2026-11-01, Hilden demo tour: "keine einzige Angabe passte"). Pure, shared with
 * the web planner, unit-tested (`JogRuleTest`).
 *
 * BRouter gives a hint at every junction. Where the route steps sideways – onto a pedestrian crossing and off it again,
 * from the sidewalk onto the street, round an offset crossroads – that is two hints a few metres apart: "right" onto
 * Benrather Straße and 8 m later "left" onto the crossing into the park. A person says "straight on across the road".
 *
 * - Two hints in a row, closer than [BIKE_M] (on foot [HIKE_M]), turning opposite ways (one left, one right) are one
 *   sideways step. Its turn is the heading before the first hint to the heading after the second, each measured over
 *   [ARM_M] but never across the neighbouring hint (Hilden: the bend of Poststraße 18 m before the crossing).
 * - Within [STRAIGHT_DEG] of straight on: neither is worth a cue (no vibration, no voice).
 * - Else the step is one cue: the hint turning the step's way; the other is quiet (an offset crossroads, "left").
 * - A roundabout is never part of a step (its own cue). Two turns the same way stay as they are ([JunctionCues] merges
 *   close cues).
 *
 * [needed] = [CueRule.needed] per hint, then this rule – the one answer for the vibration ([JunctionCues]), the voice
 * ([VoiceGuide]) and the trace of a followed line.
 */
object JogRule {
    const val BIKE_M = 20.0
    const val HIKE_M = 12.0
    const val ARM_M = 15.0
    const val STRAIGHT_DEG = 30.0

    /** Per hint of [line] (points with a turn, in line order): worth a cue. [hike] = the on-foot rules. */
    fun needed(line: List<GeoPoint>, hike: Boolean): BooleanArray {
        val cum = if (line.size >= 2) Geo.cumulative(line) else DoubleArray(line.size)
        return needed(line, cum, line.indices.filter { line[it].turn != null }, hike)
    }

    /** [idx] = the line indices of the hints, [cum] = the line's cumulative metres. */
    fun needed(line: List<GeoPoint>, cum: DoubleArray, idx: List<Int>, hike: Boolean): BooleanArray {
        val out = BooleanArray(idx.size) { CueRule.needed(line[idx[it]].turn!!, line[idx[it]].shape, hike) }
        val reach = if (hike) HIKE_M else BIKE_M
        var k = 0
        while (k + 1 < idx.size) {
            val a = line[idx[k]]; val b = line[idx[k + 1]]
            val gap = cum[idx[k + 1]] - cum[idx[k]]
            val opposite = a.turnAngle * b.turnAngle < 0
            if (gap >= reach || !opposite || a.turn == JunctionCue.ROUNDABOUT || b.turn == JunctionCue.ROUNDABOUT) { k++; continue }
            // headings before / after the step, not reaching over the neighbouring hints
            val first = cum[idx[k]]; val last = cum[idx[k + 1]]
            val armIn = if (k > 0) minOf(ARM_M, (first - cum[idx[k - 1]]) * 0.9) else ARM_M
            val armOut = if (k + 2 < idx.size) minOf(ARM_M, (cum[idx[k + 2]] - last) * 0.9) else ARM_M
            val net = diff(bearing(at(line, cum, first - armIn), at(line, cum, first)),
                bearing(at(line, cum, last), at(line, cum, last + armOut)))
            val any = out[k] || out[k + 1]
            out[k] = false; out[k + 1] = false
            if (abs(net) > STRAIGHT_DEG && any) out[if (Math.signum(a.turnAngle.toDouble()) == Math.signum(net)) k else k + 1] = true
            k += 2
        }
        return out
    }

    /** The point [d] metres along the line (clamped to its ends). */
    private fun at(line: List<GeoPoint>, cum: DoubleArray, d: Double): GeoPoint {
        if (d <= 0) return line.first()
        if (d >= cum.last()) return line.last()
        var i = java.util.Arrays.binarySearch(cum, d).let { if (it >= 0) it else -it - 2 }.coerceIn(0, line.size - 2)
        while (i < line.size - 2 && cum[i + 1] <= d) i++
        val seg = cum[i + 1] - cum[i]
        val f = if (seg <= 0) 0.0 else (d - cum[i]) / seg
        val a = line[i]; val b = line[i + 1]
        return GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }

    private fun bearing(a: GeoPoint, b: GeoPoint): Double =
        Math.toDegrees(atan2((b.lon - a.lon) * cos(Math.toRadians(a.lat)), b.lat - a.lat))

    /** Signed change from heading [from] to [to] in −180..180 (> 0 = right). */
    private fun diff(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0
}
