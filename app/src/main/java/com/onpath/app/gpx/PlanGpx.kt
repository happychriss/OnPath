package com.onpath.app.gpx

import com.onpath.app.data.Geo
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint

/** A GPX file to share: its file name and its text. */
data class GpxFile(val fileName: String, val text: String)

/**
 * A plan as GPX – the one export of a planned route, shared by the app (My routes → Export GPX) and the web planner
 * (Export GPX); pure, no Android (SharedCoreRuleTest). Written by [GpxWriter]:
 * - the routed line as one track (most apps prefer `<trk>`), its sport as `<trk><type>` ([SportType], Komoot's string);
 * - the route points as `<wpt>` in plan order (start, stops, goal – a round trip ends with its start again), with their
 *   names: other apps show them, and a re-import rebuilds the plan from them ([stopsOf]);
 * - the file name "<name> - plan.gpx" (the same in every language, owner: a plan and its ride never overwrite each other).
 */
object PlanGpx {
    /** A stop farther than this from the line's start / end is not its start / goal. */
    const val END_M = 30.0
    /** A stop farther than this from the line is not one of its route points (a skipped stop stays within it). */
    const val ON_LINE_M = 100.0
    /** More route points than this: waypoints of something else (turn hints, POIs), not a plan. */
    const val MAX_STOPS = 200

    /** The file name of a plan named [name]: "Hilden - plan.gpx". */
    fun fileName(name: String): String = GpxWriter.fileName(name, GpxWriter.kindSuffix(planned = true, recorded = false))

    /** The plan's route points as `<wpt>`, [name] = the known name of point i (pinned POI, search) or null. */
    fun stops(points: List<GeoPoint>, name: (Int) -> String?): List<GpxWaypoint> =
        points.mapIndexed { i, p -> GpxWaypoint(p.lat, p.lon, name(i)) }

    /** The plan GPX: [line] = the routed line (segments), [stops] = its route points ([stops]), the activity's sport. */
    fun export(name: String, line: List<List<TrackPoint>>, stops: List<GpxWaypoint>, activity: Activity?, sac: Int,
               timeMs: Long = System.currentTimeMillis()): GpxFile =
        GpxFile(fileName(name), GpxWriter.write(name, line, stops, timeMs, type = activity?.let { SportType.typeOf(it, sac) }))

    /** [export] for a stored activity name (Tour.activity); unknown / missing = no `<type>`. */
    fun export(name: String, line: List<List<TrackPoint>>, stops: List<GpxWaypoint>, activityName: String?, sac: Int,
               timeMs: Long = System.currentTimeMillis()): GpxFile =
        export(name, line, stops, activityName?.let { n -> Activity.entries.firstOrNull { it.name == n } }, sac, timeMs)

    /**
     * Re-import: the plan's route points when the file's [waypoints] are the stops of a plan along [line] (as [export]
     * writes them) – at least two, the first at the line's start and the last at its end ([END_M]), all near the line
     * ([ON_LINE_M]); else null (a track with POIs or turn hints: the plan is fitted to the line instead, TrackToPlan).
     */
    fun stopsOf(line: List<GeoPoint>, waypoints: List<GpxWaypoint>): List<GpxWaypoint>? {
        if (line.size < 2 || waypoints.size < 2 || waypoints.size > MAX_STOPS) return null
        val first = waypoints.first(); val last = waypoints.last()
        if (Geo.distance(GeoPoint(first.lat, first.lon), line.first()) > END_M) return null
        if (Geo.distance(GeoPoint(last.lat, last.lon), line.last()) > END_M) return null
        if (waypoints.any { Geo.distanceToLine(it.lat, it.lon, line) > ON_LINE_M }) return null
        return waypoints
    }
}
