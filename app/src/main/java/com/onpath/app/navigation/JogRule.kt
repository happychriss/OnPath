package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import kotlin.math.abs

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
        val l = approach(line, hike)
        val cum = if (l.size >= 2) Geo.cumulative(l) else DoubleArray(l.size)
        return needed(l, cum, l.indices.filter { l[it].turn != null }, hike)
    }

    /** Bike / on foot: the heading one comes on is measured over this far before a junction ([approach]). */
    const val APPROACH_BIKE_M = 30.0
    const val APPROACH_HIKE_M = 20.0
    /** A turn grown by [approach] to at least this is a plain turn (BRouter: 45° … 135°), above [SHARP_DEG] sharp. */
    const val TURN_DEG = 45.0
    const val SHARP_DEG = 135.0

    /**
     * The radius rule, first half (owner 2026-11-01, replay of tour 63 at Ellerstraße: no word at a corner of ~115°):
     * BRouter measures a junction's angle against the last few metres one comes on. Where a corner is drawn as a curve
     * of short pieces, the junction sits at its end and BRouter sees only the last 43° – "slight left, clearly the
     * straightest way", no cue. Here the heading one comes on is measured over [APPROACH_HIKE_M] (bike
     * [APPROACH_BIKE_M]) before the junction, never across the hint before it, and the junction's angles (the route's
     * and the other ways', all relative to that heading) turned by the difference – only when that makes the route's
     * turn larger the same way (a turn is never made smaller or quiet here; close opposite turns: [needed]). A turn grown
     * past [TURN_DEG] becomes "left" / "right" (past [SHARP_DEG] "sharp"). Returns [line] with the hints corrected.
     */
    fun approach(line: List<GeoPoint>, hike: Boolean): List<GeoPoint> {
        if (line.size < 3 || line.none { it.turn != null }) return line
        val cum = Geo.cumulative(line)
        val reach = if (hike) APPROACH_HIKE_M else APPROACH_BIKE_M
        val out = line.toMutableList()
        var prevHint = Double.NEGATIVE_INFINITY
        for (i in line.indices) {
            val p = line[i]
            val cue = p.turn ?: continue
            val c = cum[i]
            val gap = c - prevHint
            prevHint = c
            if (i == 0 || cue == JunctionCue.ROUNDABOUT || cue == JunctionCue.U_TURN_LEFT || cue == JunctionCue.U_TURN_RIGHT) continue
            val arm = minOf(reach, gap * 0.9)
            if (arm < 5.0 || cum[i] - cum[i - 1] >= arm) continue   // the last piece is the approach itself
            val a = angleOf(p).toDouble()
            if (a == 0.0) continue
            val delta = Geo.angleDiff(Geo.bearing(at(line, cum, c - arm), p), Geo.bearing(line[i - 1], p))
            val grown = a + delta
            if (Math.signum(grown) != Math.signum(a) || abs(grown) <= abs(a) + 1.0) continue
            val g = grown.coerceIn(-SHARP_DEG - 20.0, SHARP_DEG + 20.0).toFloat()
            val left = g < 0
            val newCue = when {
                abs(g) > SHARP_DEG -> if (left) JunctionCue.SHARP_LEFT else JunctionCue.SHARP_RIGHT
                abs(g) >= TURN_DEG -> if (left) JunctionCue.LEFT else JunctionCue.RIGHT
                else -> cue
            }
            val d = (g - a).toFloat()
            out[i] = p.copy(turn = newCue, turnAngle = g, shape = p.shape?.let { s ->
                s.copy(angle = g, others = s.others.map { it.copy(angle = wrap(it.angle + d)) })
            })
        }
        return out
    }

    private fun wrap(a: Float): Float = (((a + 540f) % 360f) - 180f)

    /** [idx] = the line indices of the hints, [cum] = the line's cumulative metres. */
    fun needed(line: List<GeoPoint>, cum: DoubleArray, idx: List<Int>, hike: Boolean): BooleanArray {
        val out = BooleanArray(idx.size) { CueRule.needed(line[idx[it]].turn!!, line[idx[it]].shape, hike) }
        val reach = if (hike) HIKE_M else BIKE_M
        var k = 0
        while (k + 1 < idx.size) {
            val a = line[idx[k]]; val b = line[idx[k + 1]]
            val gap = cum[idx[k + 1]] - cum[idx[k]]
            val opposite = angleOf(a) * angleOf(b) < 0
            if (gap >= reach || !opposite || a.turn == JunctionCue.ROUNDABOUT || b.turn == JunctionCue.ROUNDABOUT) { k++; continue }
            // headings before / after the step, not reaching over the neighbouring hints
            val first = cum[idx[k]]; val last = cum[idx[k + 1]]
            val armIn = if (k > 0) minOf(ARM_M, (first - cum[idx[k - 1]]) * 0.9) else ARM_M
            val armOut = if (k + 2 < idx.size) minOf(ARM_M, (cum[idx[k + 2]] - last) * 0.9) else ARM_M
            val net = Geo.angleDiff(Geo.bearing(at(line, cum, first - armIn), at(line, cum, first)),
                Geo.bearing(at(line, cum, last), at(line, cum, last + armOut)))
            val any = out[k] || out[k + 1]
            out[k] = false; out[k + 1] = false
            if (abs(net) > STRAIGHT_DEG && any) out[if (Math.signum(angleOf(a).toDouble()) == Math.signum(net)) k else k + 1] = true
            k += 2
        }
        return out
    }

    /**
     * The junction's angle (> 0 right): [GeoPoint.turnAngle], else the shape's angle, else a typical one for the cue.
     * A line read back from the navigation session file has turnAngle 0 except at roundabouts (NavSession stores the
     * angle in the shape) – field 2026-11-01: the jog rule never fired on the phone and the turn card said "Leicht links"
     * for every turn.
     */
    fun angleOf(p: GeoPoint): Float {
        if (p.turnAngle != 0f) return p.turnAngle
        p.shape?.angle?.takeIf { it != 0f }?.let { return it }
        return when (p.turn) {
            JunctionCue.LEFT -> -90f; JunctionCue.RIGHT -> 90f
            JunctionCue.SLIGHT_LEFT -> -35f; JunctionCue.SLIGHT_RIGHT -> 35f
            JunctionCue.SHARP_LEFT -> -135f; JunctionCue.SHARP_RIGHT -> 135f
            JunctionCue.KEEP_LEFT -> -15f; JunctionCue.KEEP_RIGHT -> 15f
            JunctionCue.U_TURN_LEFT -> -180f; JunctionCue.U_TURN_RIGHT -> 180f
            JunctionCue.ROUNDABOUT, null -> 0f
        }
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

}
