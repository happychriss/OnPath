package com.onpath.app.recording

import com.onpath.app.data.Geo
import com.onpath.app.gpx.TrackPoint

/**
 * Which GPS fixes become track points (pure, unit-tested; used by [RecordingService]).
 *
 * - Fixes worse than [MAX_ACCURACY_M] are dropped. 50 m keeps the track whole in gorges and under forest, where
 *   30–60 m accuracy is normal; the jitter rule below keeps such fixes from inflating the distance.
 * - An impossible speed since the previous point ([MAX_SPEED_MS]) is a GPS jump: dropped.
 * - Moving less than [minMoveM] (half the accuracy, 3–25 m) within 30 s is standing still: dropped, so a break
 *   with GPS drift adds no distance (R-05).
 */
object PointFilter {
    const val MAX_ACCURACY_M = 50f
    const val MAX_SPEED_MS = 35.0       // ~126 km/h: anything faster is a GPS jump
    const val STILL_WINDOW_S = 30.0
    /** A GPS gap up to this long is bridged (owner field test 2026-10-22: the drawn track broke under forest). */
    const val BRIDGE_MAX_MS = 5 * 60_000L
    /** … when the straight line across it is no faster than this (~54 km/h: a fast bike downhill, not a train). */
    const val BRIDGE_MAX_SPEED_MS = 15.0

    enum class Verdict { ACCEPT, BAD_ACCURACY, JUMP, STANDING_STILL }

    fun accuracyOk(hasAccuracy: Boolean, accuracyM: Float): Boolean = hasAccuracy && accuracyM <= MAX_ACCURACY_M

    /** Movement that counts as real at this accuracy. */
    fun minMoveM(accuracyM: Double): Double = (accuracyM / 2).coerceIn(3.0, 25.0)

    /**
     * Judges [p] after [prev]. [continuous] = no GPS gap between them (otherwise distance and speed are not
     * compared: a new segment starts). Returns the verdict and the distance to add when accepted.
     */
    fun judge(prev: TrackPoint?, p: TrackPoint, accuracyM: Double, continuous: Boolean): Pair<Verdict, Double> {
        if (prev == null || !continuous) return Verdict.ACCEPT to 0.0
        val d = Geo.distance(prev.lat, prev.lon, p.lat, p.lon)
        val dt = ((p.timeMs ?: 0) - (prev.timeMs ?: 0)) / 1000.0
        if (dt > 0 && d / dt > MAX_SPEED_MS) return Verdict.JUMP to 0.0
        if (d < minMoveM(accuracyM) && dt < STILL_WINDOW_S) return Verdict.STANDING_STILL to 0.0
        return Verdict.ACCEPT to d
    }

    /**
     * After a GPS gap: does [p] continue [prev]'s track piece? Yes for a short gap crossed at a plausible speed – the
     * line stays connected and the straight distance counts (the walker did cover it). A long gap or a fast one (bus,
     * train, a wrong fix) starts a new piece, as before.
     */
    fun bridgesGap(prev: TrackPoint, p: TrackPoint): Boolean {
        val dtMs = (p.timeMs ?: return false) - (prev.timeMs ?: return false)
        if (dtMs <= 0 || dtMs > BRIDGE_MAX_MS) return false
        val d = Geo.distance(prev.lat, prev.lon, p.lat, p.lon)
        return d / (dtMs / 1000.0) <= BRIDGE_MAX_SPEED_MS
    }
}
