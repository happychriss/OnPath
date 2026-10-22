package com.onpath.app.rides

import com.onpath.app.data.Geo
import com.onpath.app.gpx.TrackPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * How a recorded tour reads under a [RideChoice] – stats, drawing, export / backup layout, split parts. The stored
 * points are the input and are never changed (pure, JVM tested: RideViewTest).
 *
 * Under a choice every point is one of: KEPT (the tour), a ride not counted (VEHICLE / LIFT; the points between a
 * ride's `from` and `to`), or DROPPED (after the cut of "Save only until" / "Split"). A ride's `from` / `to` points stay
 * KEPT – that is where the user got on and off.
 */
object RideView {
    /** Ascent hysteresis of the recording (RecordingService): 5 m. */
    const val CLIMB_THRESHOLD_M = 5.0
    /** Less than this after the last ride (m): the ride is "at the end" – "Save only until" is preselected. */
    const val AT_END_M = 300.0

    enum class Part { KEPT, VEHICLE, LIFT, DROPPED }

    /** A line to draw: [points] in order, [part] = how. */
    data class Line(val points: List<TrackPoint>, val part: Part)

    /** Backup / export: track segments (with the ride they belong to, null = tour) + the dropped tail. */
    data class Layout(val trk: List<Pair<List<TrackPoint>, RideKind?>>, val dropped: List<List<TrackPoint>>)

    private class Flat(segments: List<List<TrackPoint>>) {
        val pts = segments.flatten()
        val seg = IntArray(pts.size).also { a -> var i = 0; segments.forEachIndexed { s, l -> repeat(l.size) { a[i++] = s } } }
        val size get() = pts.size
        fun d(i: Int, j: Int) = Geo.distance(pts[i].lat, pts[i].lon, pts[j].lat, pts[j].lon)
    }

    /** Rides that are valid for [n] points (an older record, a shorter track): the others are ignored. */
    fun validRides(rides: List<Ride>, n: Int): List<Ride> {
        var floor = 0
        return rides.sortedBy { it.from }.filter { r -> (r.from >= floor && r.to > r.from && r.to < n).also { if (it) floor = r.to } }
    }

    /** Index of the last KEPT point (later points are dropped), null = nothing dropped. */
    fun cutIndex(rides: List<Ride>, choice: RideChoice): Int? = when (choice) {
        RideChoice.UNTIL -> rides.lastOrNull()?.from
        RideChoice.SPLIT -> rides.firstOrNull()?.from
        else -> null
    }

    /** Rides that are in the kept part but not counted. */
    fun notCounted(rides: List<Ride>, choice: RideChoice): List<Ride> = when (choice) {
        RideChoice.NOT_COUNTED -> rides
        RideChoice.UNTIL -> rides.dropLast(1)
        else -> emptyList()
    }

    private fun labels(f: Flat, rides: List<Ride>, choice: RideChoice): Array<Part> {
        val out = Array(f.size) { Part.KEPT }
        for (r in notCounted(rides, choice)) for (i in r.from + 1 until r.to) out[i] = if (r.kind == RideKind.LIFT) Part.LIFT else Part.VEHICLE
        cutIndex(rides, choice)?.let { c -> for (i in c + 1 until f.size) out[i] = Part.DROPPED }
        return out
    }

    /** Pair (i, i+1) is not part of the tour's km / ascent / time. */
    private fun removedPairs(f: Flat, rides: List<Ride>, choice: RideChoice): BooleanArray {
        val out = BooleanArray(maxOf(0, f.size - 1))
        for (r in notCounted(rides, choice)) for (i in r.from until r.to) out[i] = true
        cutIndex(rides, choice)?.let { c -> for (i in c until out.size) out[i] = true }
        return out
    }

    // ------------------------------------------------------------------------------------------------ stats

    /**
     * The tour's stats under [choice]: the recorded stats minus what is not counted – the km between points of a ride
     * (as the recording counted them: within a track piece), its time (from getting on to getting off), its ascent;
     * for a cut everything from the cut to the end of the tour ([RideRecord.endMs]).
     */
    fun stats(segments: List<List<TrackPoint>>, rec: RideRecord, choice: RideChoice = rec.choice): TourStats {
        val f = Flat(segments)
        val rides = validRides(rec.rides, f.size)
        val removed = removedPairs(f, rides, choice)
        var dist = 0.0
        for (i in removed.indices) if (removed[i] && f.seg[i] == f.seg[i + 1]) dist += f.d(i, i + 1)
        var time = 0.0
        var up = 0.0; var down = 0.0
        fun climb(a: Int, b: Int) { val (u, d) = Geo.climb((a..b).map { f.pts[it].ele }, CLIMB_THRESHOLD_M); up += u; down += d }
        for (r in notCounted(rides, choice)) {
            val t0 = f.pts[r.from].timeMs; val t1 = f.pts[r.to].timeMs
            if (t0 != null && t1 != null && t1 > t0) time += (t1 - t0) / 1000.0
            climb(r.from, r.to)
        }
        cutIndex(rides, choice)?.let { c ->
            val t0 = f.pts[c].timeMs
            val last = (c until f.size).mapNotNull { f.pts[it].timeMs }.maxOrNull()
            val end = maxOf(last ?: Long.MIN_VALUE, rec.endMs ?: Long.MIN_VALUE)
            if (t0 != null && end > t0) time += (end - t0) / 1000.0
            climb(c, f.size - 1)
        }
        val r = rec.recorded
        return TourStats(
            (r.distanceM - dist).coerceAtLeast(0.0),
            (r.ascentM - up).coerceAtLeast(0.0),
            (r.descentM - down).coerceAtLeast(0.0),
            (r.durationS - time).coerceIn(0.0, r.durationS.coerceAtLeast(0.0)),
        )
    }

    /** Stats of a part of its own (a tour split off): km within track pieces, ascent, time while moving in pieces. */
    fun partStats(segments: List<List<TrackPoint>>): TourStats {
        var dist = 0.0; var time = 0.0
        for (s in segments) for (i in 1 until s.size) {
            dist += Geo.distance(s[i - 1].lat, s[i - 1].lon, s[i].lat, s[i].lon)
            val a = s[i - 1].timeMs; val b = s[i].timeMs
            if (a != null && b != null && b > a) time += (b - a) / 1000.0
        }
        val (up, down) = Geo.climb(segments.flatten().map { it.ele }, CLIMB_THRESHOLD_M)
        return TourStats(dist, up, down, time)
    }

    // ------------------------------------------------------------------------------------------------ drawing

    /**
     * Lines to draw under [choice]: the tour (KEPT, broken at track pieces and rides), each ride not counted from
     * getting on to getting off (also across a GPS gap), and – for previews – the dropped tail. Without a record: the
     * track as stored.
     */
    fun lines(segments: List<List<TrackPoint>>, rec: RideRecord?, choice: RideChoice? = rec?.choice): List<Line> {
        if (rec == null || choice == null) return segments.filter { it.isNotEmpty() }.map { Line(it, Part.KEPT) }
        val f = Flat(segments)
        val rides = validRides(rec.rides, f.size)
        val lab = labels(f, rides, choice)
        val removed = removedPairs(f, rides, choice)
        val out = ArrayList<Line>()
        // tour pieces
        var cur = ArrayList<TrackPoint>()
        for (i in 0 until f.size) {
            if (lab[i] != Part.KEPT) { if (cur.isNotEmpty()) out += Line(cur, Part.KEPT); cur = ArrayList(); continue }
            if (cur.isNotEmpty() && (f.seg[i] != f.seg[i - 1] || removed[i - 1] || lab[i - 1] != Part.KEPT)) { out += Line(cur, Part.KEPT); cur = ArrayList() }
            cur += f.pts[i]
        }
        if (cur.isNotEmpty()) out += Line(cur, Part.KEPT)
        for (r in notCounted(rides, choice)) out += Line(f.pts.subList(r.from, r.to + 1).toList(), if (r.kind == RideKind.LIFT) Part.LIFT else Part.VEHICLE)
        cutIndex(rides, choice)?.let { c -> if (c < f.size - 1) out += Line(f.pts.subList(c, f.size).toList(), Part.DROPPED) }
        return out
    }

    /** The tour as drawn (blue / purple line): KEPT pieces only. */
    fun keptSegments(segments: List<List<TrackPoint>>, rec: RideRecord?): List<List<TrackPoint>> =
        lines(segments, rec).filter { it.part == Part.KEPT }.map { it.points }

    // ------------------------------------------------------------------------------------------------ backup

    /**
     * Backup / export file: the tour as chosen, every point exactly once and in order – KEPT and ride points as track
     * segments (a ride not counted is its own segment, marked), the DROPPED tail apart (the backup keeps it in OnPath's
     * own record, so a restore gets every raw point back; an export leaves it out).
     */
    fun layout(segments: List<List<TrackPoint>>, rec: RideRecord?): Layout {
        if (rec == null) return Layout(segments.filter { it.isNotEmpty() }.map { it to null }, emptyList())
        val f = Flat(segments)
        val rides = validRides(rec.rides, f.size)
        val lab = labels(f, rides, rec.choice)
        val removed = removedPairs(f, rides, rec.choice)
        val trk = ArrayList<Pair<List<TrackPoint>, RideKind?>>()
        val dropped = ArrayList<List<TrackPoint>>()
        var cur = ArrayList<TrackPoint>()
        var curLab: Part? = null
        fun flush() {
            if (cur.isEmpty()) return
            when (curLab) {
                Part.DROPPED -> dropped += cur
                Part.VEHICLE -> trk += cur to RideKind.VEHICLE
                Part.LIFT -> trk += cur to RideKind.LIFT
                else -> trk += cur to null
            }
            cur = ArrayList()
        }
        for (i in 0 until f.size) {
            val newPiece = i == 0 || lab[i] != curLab || f.seg[i] != f.seg[i - 1] ||
                (lab[i] == Part.KEPT && removed[i - 1])
            if (newPiece) { flush(); curLab = lab[i] }
            cur += f.pts[i]
        }
        flush()
        return Layout(trk, dropped)
    }

    // ------------------------------------------------------------------------------------------------ split

    /**
     * "Split": the tours after each ride – points from getting off to the next ride (or the end), as stored pieces.
     * Parts with fewer than two points are left out. The first part stays the original tour (cut at the first ride).
     */
    fun splitParts(segments: List<List<TrackPoint>>, rides: List<Ride>): List<List<List<TrackPoint>>> {
        val f = Flat(segments)
        val rs = validRides(rides, f.size)
        return rs.mapIndexedNotNull { k, r ->
            val end = rs.getOrNull(k + 1)?.from ?: (f.size - 1)
            if (end - r.to < 1) return@mapIndexedNotNull null
            val segs = ArrayList<MutableList<TrackPoint>>()
            for (i in r.to..end) {
                if (i == r.to || f.seg[i] != f.seg[i - 1]) segs += ArrayList<TrackPoint>()
                segs.last() += f.pts[i]
            }
            segs
        }
    }

    // ------------------------------------------------------------------------------------------------ summary

    enum class RowKind(val label: String) { ON_FOOT("On foot"), BY_BIKE("By bike"), VEHICLE("Bus / car"), LIFT("Lift") }

    /** One line of the timeline: what, from – to, km, ascent, average speed (km/h; null when the time is unknown). */
    data class Row(val kind: RowKind, val startMs: Long?, val endMs: Long?, val distanceM: Double, val ascentM: Double, val speedKmh: Double?)

    data class Option(val choice: RideChoice, val title: String, val detail: String)

    /** Everything the end-of-tour summary shows: timeline, the choices (preselected one first in [preselected]), map lines. */
    data class Summary(
        val rides: List<Ride>,
        val rows: List<Row>,
        val options: List<Option>,
        val preselected: RideChoice,
        val lines: Map<RideChoice, List<Line>>,
    )

    fun summary(segments: List<List<TrackPoint>>, rides0: List<Ride>, onFoot: Boolean, zone: ZoneId = ZoneId.systemDefault(),
                allowSplit: Boolean = true): Summary? {
        val f = Flat(segments)
        val rides = validRides(rides0, f.size)
        if (rides.isEmpty()) return null
        val rows = ArrayList<Row>()
        fun row(kind: RowKind, a: Int, b: Int, acrossGaps: Boolean) {
            if (b <= a) return
            var d = 0.0
            for (i in a until b) if (acrossGaps || f.seg[i] == f.seg[i + 1]) d += f.d(i, i + 1)
            val (up, _) = Geo.climb((a..b).map { f.pts[it].ele }, CLIMB_THRESHOLD_M)
            val t0 = f.pts[a].timeMs; val t1 = f.pts[b].timeMs
            val speed = if (t0 != null && t1 != null && t1 > t0) d / ((t1 - t0) / 1000.0) * 3.6 else null
            rows += Row(kind, t0, t1, d, up, speed)
        }
        val own = if (onFoot) RowKind.ON_FOOT else RowKind.BY_BIKE
        var pos = 0
        for (r in rides) {
            row(own, pos, r.from, acrossGaps = false)
            row(if (r.kind == RideKind.LIFT) RowKind.LIFT else RowKind.VEHICLE, r.from, r.to, acrossGaps = true)
            pos = r.to
        }
        row(own, pos, f.size - 1, acrossGaps = false)

        val last = rides.last()
        val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        val untilTime = f.pts[last.from].timeMs?.let { fmt.format(Instant.ofEpochMilli(it)) }
        val parts = 1 + splitParts(segments, rides).size
        val options = buildList {
            if (last.from >= 1) add(Option(RideChoice.UNTIL,
                if (untilTime != null) "Save only until $untilTime" else "Save only until the ride",
                if (rides.size > 1) "The last ride and everything after it dropped; earlier rides not counted"
                else "The ride and everything after it dropped"))
            add(Option(RideChoice.NOT_COUNTED, "Save all, rides not counted", "Rides grey on the map, not in km, time or pace"))
            if (allowSplit && parts >= 2 && rides.first().from >= 1) add(Option(RideChoice.SPLIT,
                if (parts == 2) "Split into two tours" else "Split into $parts tours",
                if (parts == 2) "Before and after the ride" else "One tour between rides"))
            add(Option(RideChoice.AS_RECORDED, "Save as recorded", "Everything counts, the rides too"))
        }
        var after = 0.0
        for (i in last.to until f.size - 1) after += f.d(i, i + 1)
        val atEnd = last.kind == RideKind.VEHICLE && after < AT_END_M
        val pre = if (atEnd && options.any { it.choice == RideChoice.UNTIL }) RideChoice.UNTIL else RideChoice.NOT_COUNTED
        val dummy = TourStats(0.0, 0.0, 0.0, 0.0)
        val lines = options.associate { o -> o.choice to lines(segments, RideRecord(rides, o.choice, dummy), o.choice) }
        return Summary(rides, rows, options, pre, lines)
    }
}
