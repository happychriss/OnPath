package com.onpath.web

import com.onpath.app.data.Format
import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.GpxParser
import com.onpath.app.gpx.SportType
import com.onpath.app.routing.Activity
import com.onpath.app.routing.TrackToPlan
import com.onpath.app.time.TimeEstimate
import com.onpath.strings.*

/**
 * GPX import like the app's My routes → Import GPX (AppViewModel.importGpx, docs/design/web-planner.md §15): the app's
 * tolerant [GpxParser], Komoot's `<trk><type>` → activity and T level ([SportType.importChoice]), distance / climb /
 * time like the track panel (file time, else the one time model), then the app's two ways to use a track: keep it
 * shown, or "Plan a route from it" ([TrackToPlan.fit] with our router and the current activity).
 */
object WebImport {
    /** [bytes] = the file; [fileName] its name (the GPX name wins). JSON for the page's track panel, or {"error": …}. */
    fun read(bytes: ByteArray, fileName: String, activity: String, sac: Int): String = try {
        val data = GpxParser.parse(bytes)
        val segs = data.allSegments
        val current = Activity.valueOf(activity)
        // touring = false: the web never records a tour
        val choice = SportType.importChoice(data.type, current, sac, touring = false)
        val pick = choice.pick
        val name = data.name ?: fileName.ifBlank { null }?.removeSuffix(".gpx")?.removeSuffix(".GPX") ?: Txt.s(Res.string.msg_name_import)
        val geo = segs.map { s -> s.map { it.toGeo() } }
        val dist = geo.sumOf { Geo.length(it) }
        var up = 0.0; var down = 0.0
        segs.forEach { s -> val (u, d) = Geo.climb(s.map { it.ele }); up += u; down += d }
        // the file's own time, else the one time model with the pace setting (WebPace – the page's choice)
        val dur = TimeEstimate.importedSeconds(segs, pick.activity, WebPace.factors(pick.activity))
        data.type?.let { t ->
            val to = if (SportType.activityOf(t) == null) "unknown, kept ${pick.activity.name}"
                else pick.activity.name + if (pick.activity == Activity.HIKE) " T${pick.sac}" else ""
            Problems.info("gpx", "GPX import “$name”: type “${t.take(40)}” → $to")
        }
        if (data.warnings.isNotEmpty()) Problems.info("gpx", "GPX import “$name”: ${data.pointCount} points, " +
            "${data.waypoints.size} waypoints; " + data.warnings.joinToString("; "))
        buildString {
            append("{\"name\":").append(Engine.str(name))
            append(",\"activity\":").append(Engine.str(pick.activity.name)).append(",\"sac\":").append(pick.sac)
            append(",\"switch\":").append(choice.switch)
            append(",\"note\":").append(choice.note?.let { Engine.str(it) } ?: "null")
            append(",\"skippedNote\":").append(importedNote(data.warnings)?.let { Engine.str(it) } ?: "null")
            append(",\"distanceM\":").append(Engine.num(dist, 1)).append(",\"ascentM\":").append(Engine.num(up, 1))
            append(",\"descentM\":").append(Engine.num(down, 1)).append(",\"durationS\":").append(Engine.num(dur, 0))
            // the track panel's lines (ui/Panels.kt TrackPanel)
            append(",\"texts\":{\"distance\":").append(Engine.str(Format.distance(dist)))
            append(",\"duration\":").append(Engine.str(Format.duration(dur)))
            append(",\"climb\":").append(Engine.str("↑ ${Format.meters(up)}  ↓ ${Format.meters(down)}"))
            append(",\"plan\":").append(Engine.str(Txt.s(Res.string.plan_from_track)))
            append(",\"close\":").append(Engine.str(Txt.s(Res.string.plan_close_track))).append('}')
            append(",\"segments\":").append(geo.joinToString(",", "[", "]") { s -> buildString { with(Engine) { pointsJson(s) } } })
            append(",\"waypoints\":").append(data.waypoints.joinToString(",", "[", "]") { w ->
                "{\"lat\":" + Engine.num(w.lat, 6) + ",\"lon\":" + Engine.num(w.lon, 6) + ",\"name\":" + (w.name?.let { Engine.str(it) } ?: "null") + "}"
            })
            with(Engine) { profileJson(geo.flatten()) }
            append('}')
        }
    } catch (e: Exception) {
        Problems.error("gpx", "GPX import failed", e)
        // GpxException: our own plain reason ("No track, route or waypoint found in this file.")
        val own = if (e is com.onpath.app.gpx.GpxException) e.message.orEmpty() else ""
        "{\"error\":" + Engine.str(if (own.isNotEmpty()) Txt.s(Res.string.msg_gpx_import_failed_reason, own)
            else Txt.s(Res.string.msg_gpx_import_failed)) + "}"
    }

    /** Mirror of AppViewModel.importedNote (web/parity.json): one grey line when the parser skipped something. */
    internal fun importedNote(warnings: List<String>): String? = if (warnings.isEmpty()) null else Txt.s(Res.string.msg_gpx_parts_skipped)

    /**
     * Shown track → a plannable route with few route points (AppViewModel.planFromShown): the routing data along
     * the track is already in place (the page fetched its parts). `{"points": [[lat, lon], …]}` or {"error": …}.
     */
    fun planFromTrack(track: String, activity: String, sac: Int): String = try {
        val t = Engine.parsePoints(track)
        val act = Activity.valueOf(activity)
        val t0 = System.currentTimeMillis()
        val r = TrackToPlan.fit(t, Engine.engine(), act, sac)
        if (r.points.size < 2) "{\"error\":" + Engine.str(Txt.s(Res.string.plan_from_track_failed)) + "}" else {
            Problems.trace("plan", "Track → plan: ${t.size} track points → ${r.points.size} route points, follows " +
                "${(r.follows * 100).toInt()} %, ${r.calls} routing calls, ${r.failedLegs} legs without a route, " +
                "${System.currentTimeMillis() - t0} ms")
            "{\"points\":" + r.points.joinToString(",", "[", "]") { "[" + Engine.num(it.lat, 6) + "," + Engine.num(it.lon, 6) + "]" } +
                ",\"follows\":" + Engine.num(r.follows, 3) + ",\"calls\":" + r.calls + ",\"failedLegs\":" + r.failedLegs + "}"
        }
    } catch (e: Exception) {
        Problems.error("routing", "Track could not be turned into a plan", e)
        "{\"error\":" + Engine.str(Txt.s(Res.string.plan_from_track_failed)) + "}"
    }
}
