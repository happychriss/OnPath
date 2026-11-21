package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import kotlin.math.abs
import kotlin.math.max

/**
 * The numbers of "Abseits der Route. Die Route liegt rechts, 40 Meter." (owner 2026-11-21): side and metres are said only
 * when they are certain – every doubt gives null and the plain "Bitte aufs Handy schauen." stays (a wrong side is worse
 * than none). Pure, time is passed in; unit-tested (`OffRouteSayTest`). Fed with every good fix ([onFix]), asked for the
 * numbers of the moment ([numbers]) when the sentence is said.
 *
 * Doubt is: the fix is old, the GPS lost, standing still, a poor fix ([MAX_ACC_M]), the route closer than the fix is
 * accurate ([MIN_M]), a heading from a bent path (a U-turn or corner just taken – [PATH_FACTOR]) or across a GPS gap
 * ([GAP_MS]), no GPS bearing or one that contradicts the heading ([AGREE_DEG] – the bearing is the only check of the last
 * metres: the heading is a chord of at least 12 m), a route point that is not the nearest one ([NEAREST_TOL_M] – the
 * navigator looks ahead of where the route was left, not behind), and one far along from there ([MAX_AHEAD_M] – on a
 * loop that is a later leg: following it would skip the part between).
 */
class OffRouteSay {
    data class Numbers(val headingDeg: Double, val routeM: Double, val bearingDeg: Double)

    private class Pt(val p: GeoPoint, val ms: Long)
    private val recent = ArrayList<Pt>()
    private var accuracyM: Double? = null
    private var gpsBearingDeg: Double? = null
    private var speedMps: Double? = null
    private var lastMs: Long? = null

    fun reset() { recent.clear(); accuracyM = null; gpsBearingDeg = null; speedMps = null; lastMs = null }

    /**
     * A good fix that arrived at [arrivedMs] (elapsed clock), [ageMs] old by then – every time here is when it was
     * measured, so a late fix counts as old and a backlog delivered at once cannot hide a gap.
     * [gpsBearingDeg] / [speedMps]: what the receiver says, null = none.
     */
    fun onFix(p: GeoPoint, accuracyM: Double?, gpsBearingDeg: Double?, speedMps: Double?, arrivedMs: Long, ageMs: Long = 0) {
        val fixMs = arrivedMs - ageMs.coerceAtLeast(0)
        // a gap in the fixes: the old points say nothing about the way walked now
        if (recent.isNotEmpty() && (fixMs < recent.last().ms || fixMs - recent.last().ms > GAP_MS)) recent.clear()
        recent += Pt(p, fixMs)
        if (recent.size > KEEP) recent.subList(0, recent.size - KEEP).clear()
        this.accuracyM = accuracyM; this.gpsBearingDeg = gpsBearingDeg; this.speedMps = speedMps; lastMs = fixMs
    }

    /** The numbers to say at [nowMs] for the navigation state [st]; null = say the plain sentence. */
    fun numbers(st: NavState, nowMs: Long, gpsLost: Boolean): Numbers? {
        if (gpsLost) return null
        val last = lastMs ?: return null
        if (nowMs < last || nowMs - last > MAX_AGE_MS) return null
        // only while really off the route, and the hint must be the nearest point (not the far side of a turning point)
        if (st.shownAlongM != null) return null
        val hint = st.toRoute ?: return null
        val off = st.offM ?: return null
        if (!hint.distanceM.isFinite() || !hint.bearingDeg.isFinite() || !off.isFinite()) return null
        if (abs(hint.distanceM - off) > NEAREST_TOL_M) return null
        if ((hint.alongM ?: return null) > MAX_AHEAD_M) return null
        val acc = accuracyM ?: return null
        if (acc > MAX_ACC_M || hint.distanceM < max(MIN_M, acc)) return null
        if ((speedMps ?: return null) < MOVING_MPS) return null
        val heading = heading() ?: return null
        val gps = gpsBearingDeg ?: return null
        if (!gps.isFinite() || abs(Geo.angleDiff(heading, gps)) > AGREE_DEG) return null
        return Numbers(heading, hint.distanceM, hint.bearingDeg)
    }

    /** Bearing from the newest point at least [GoalSide.HEADING_M] back to here – only when the path there is nearly straight. */
    private fun heading(): Double? {
        val here = recent.lastOrNull()?.p ?: return null
        val k = recent.indices.reversed().firstOrNull { Geo.distance(recent[it].p, here) >= GoalSide.HEADING_M } ?: return null
        val d = Geo.distance(recent[k].p, here)
        if (d > GoalSide.HEADING_MAX_M) return null
        if (Geo.length(recent.subList(k, recent.size).map { it.p }) > PATH_FACTOR * d) return null
        return Geo.bearing(recent[k].p, here)
    }

    companion object {
        const val MAX_ACC_M = 15.0
        const val MIN_M = 15.0
        const val AGREE_DEG = 30.0
        const val MAX_AGE_MS = 3_000L
        const val GAP_MS = 3_000L
        const val NEAREST_TOL_M = 15.0
        const val MAX_AHEAD_M = 300.0
        const val PATH_FACTOR = 1.2
        const val MOVING_MPS = 0.8
        private const val KEEP = 120
    }
}
