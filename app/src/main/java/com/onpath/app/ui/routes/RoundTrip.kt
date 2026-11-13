package com.onpath.app.ui.routes

import com.onpath.app.data.Geo
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanEdits
import com.onpath.app.search.RouteNamer

/**
 * "↻ Rundtour" in a My routes row's activity line (owner 2026-11-13, variant A after the sketch). Pure.
 * - A **planned route** is a round trip when its points end at its start (or in a loop behind stops on the way) – the
 *   plan sheet's own rule ([PlanEdits.isRoundTrip], the ↻ pill): certain.
 * - A **recorded tour that followed a plan**: the same rule on that plan's points ([Tour.roundTrip], read with the list):
 *   certain.
 * - An **imported track** or a **recording without a plan** has no plan to ask – a GUESS from its line: it ends within
 *   [RouteNamer.LOOP_M] (200 m) of its start and went at least [RouteNamer.OUT_M] (500 m) away (the rule that names a
 *   track "Rundtour ab …"); a short test ride that ends where it began is none.
 */
object RoundTrip {
    /** Certain answers (planned, followed plan); null = only the line can tell ([guess]). */
    fun known(t: Tour): Boolean? = when {
        t.kind == TourKind.PLANNED -> PlanEdits.isRoundTrip(t.waypoints.map { GeoPoint(it.lat, it.lon) })
        else -> t.roundTrip
    }

    /** The guess from a line (see above); false without one. */
    fun guess(line: List<List<GeoPoint>>?): Boolean {
        val pts = line?.flatten().orEmpty()
        if (pts.size < 3) return false
        val start = pts.first()
        return Geo.distance(start, pts.last()) <= RouteNamer.LOOP_M && pts.any { Geo.distance(start, it) >= RouteNamer.OUT_M }
    }

    fun of(t: Tour, line: List<List<GeoPoint>>?): Boolean = known(t) ?: guess(line)
}
