package com.onpath.app.time

import com.onpath.app.gpx.GpxParser
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.NavLine
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Replay harness (docs/plan-time-estimate.md §6): feeds a recorded track (GPX with times) as fixes into the same
 * [TripPace] the recording service uses, along the planned line (GPX with DEM elevation), and measures how far off the
 * shown "to go" was at 25 / 50 / 75 % of the line against the real remaining moving time, and how calm the arrival
 * clock was. Progress comes from the service's own pipeline ([NavReplay]: navigator → off-route detector → TripPace),
 * so the replay shows what the phone shows; checkpoints count up to the last progress reached (a tour may end early).
 *
 * Fixture folder: src/test/resources/tracks/<name>/ with plan.gpx, track.gpx and info.txt (activity=HIKE, pace=NORMAL,
 * note=…). Owner tours: export the plan (Manage routes → Export GPX) and the recorded tour, trim ~300 m at both ends
 * of the track for privacy, add a folder.
 */
object TripReplay {
    var DEBUG = System.getenv("REPLAY_DEBUG") != null

    data class Result(
        val name: String,
        /** |shown to go − real remaining moving time| / real remaining, at 25 / 50 / 75 % (NaN = not reached). */
        val errors: DoubleArray,
        /** Same for the plain model without learning (the plan sheet's number from there). */
        val staticErrors: DoubleArray,
        /** Same for the learned estimate before the calm display (unrounded). */
        val rawErrors: DoubleArray,
        /** Plan time (prior, no learning) vs. real moving time of the whole line. */
        val planError: Double,
        /** Largest change of the shown arrival clock between two updates ≥ 100 m apart (min). */
        val maxJumpMin: Double,
        val p90JumpMin: Double,
        val movingS: Double,
        val breakS: Double,
        val learned: Factors,
    ) {
        fun line(): String = "%-34s plan %+4.0f%% | to go err 25/50/75: %s | raw %s | static %s | jump p90 %.1f max %.1f min | moving %s breaks %s | learned %s"
            .format(Locale.US, name, planError * 100, errors.joinToString("/") { pct(it) }, rawErrors.joinToString("/") { pct(it) }, staticErrors.joinToString("/") { pct(it) },
                p90JumpMin, maxJumpMin, TimeEstimate.hm(movingS), TimeEstimate.hm(breakS),
                "d %.2f f %.2f u %.2f".format(Locale.US, learned.down, learned.flat, learned.up))
        private fun pct(x: Double) = if (x.isNaN()) "  –" else "%3.0f%%".format(Locale.US, x * 100)
    }

    fun replay(name: String, plan: List<GeoPoint>, track: List<List<TrackPoint>>, activity: Activity, prior: Factors): Result {
        // the service's pipeline: Navigator (progress matching, off route) -> TripPace
        val r = NavReplay(plan, activity, prior).run(track)
        val cps = r.checkpoints(prior)
        fun err(sel: (NavReplay.Checkpoint) -> Double) = DoubleArray(3) { i -> cps[i]?.let { abs(sel(it) / it.realS - 1) } ?: Double.NaN }
        val sj = r.arrivalJumps().sorted()
        val planError = r.planError(prior)
        if (DEBUG) println(r.timeline())
        return Result(name, err { it.shownS }, err { it.staticS }, err { it.rawS }, planError, sj.lastOrNull() ?: 0.0,
            if (sj.isEmpty()) 0.0 else sj[(sj.size * 0.9).toInt().coerceAtMost(sj.size - 1)],
            r.trip.movingS(), r.trip.snapshot().breakS, r.trip.factors())
    }

    /** All fixture folders (plan.gpx + track.gpx + info.txt). */
    fun fixtures(): List<File> {
        val dir = listOf(File("src/test/resources/tracks"), File("app/src/test/resources/tracks")).firstOrNull { it.isDirectory }
            ?: return emptyList()
        return dir.listFiles { f -> f.isDirectory && File(f, "plan.gpx").isFile && File(f, "track.gpx").isFile }?.sortedBy { it.name }.orEmpty()
    }

    fun replay(folder: File): Result {
        val info = File(folder, "info.txt").takeIf { it.isFile }?.readLines().orEmpty()
            .filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val activity = runCatching { Activity.valueOf(info["activity"]!!) }.getOrDefault(Activity.HIKE)
        val pace = Pace.of(info["pace"])
        val plan = GpxParser.parse(File(folder, "plan.gpx").readBytes()).allSegments.flatten().map { it.toGeo() }
        val track = GpxParser.parse(File(folder, "track.gpx").readBytes()).allSegments
        return replay(folder.name, plan, track, activity, Factors.ONE * pace.factor)
    }

    // ---- synthetic tracks (until the owner's tours are in the fixture folder) ----

    /** A synthetic walker: true time per ~50 m = model × personal factor per group × fatigue × correlated noise. */
    data class Walker(val f: Factors, val fatigue: Double, val noise: Double, val seed: Long,
                      /** Breaks: (fraction of the line, minutes). */
                      val breaks: List<Pair<Double, Double>> = emptyList(),
                      val fixEveryS: Int = 10, val gpsNoiseM: Double = 4.0)

    fun walk(plan: List<GeoPoint>, activity: Activity, w: Walker, startMs: Long = 1_760_000_000_000L): List<TrackPoint> {
        val line = NavLine(plan)
        val times = RouteTimes.of(plan, activity)
        val rnd = Random(w.seed)
        val n = max(1, (times.lengthM / 50).toInt())
        val step = times.lengthM / n
        val segS = DoubleArray(n)
        var z = 0.0
        for (k in 0 until n) {
            z = 0.8 * z + 0.6 * rnd.nextGaussian()
            val p = times.between(k * step, (k + 1) * step)
            segS[k] = (0 until 3).sumOf { p[it] * w.f[it] } * (1 + w.fatigue * k / n) * exp(w.noise * z)
        }
        val out = ArrayList<TrackPoint>()
        var t = 0.0
        val pendingBreaks = w.breaks.sortedBy { it.first }.toMutableList()
        fun fix(along: Double, atS: Double) {
            val p = line.pointAt(along)
            val dLat = rnd.nextGaussian() * w.gpsNoiseM / 111_000.0
            val dLon = rnd.nextGaussian() * w.gpsNoiseM / 76_500.0
            out += TrackPoint(p.lat + dLat, p.lon + dLon, null, startMs + (atS * 1000).toLong())
        }
        var nextFix = 0.0
        for (k in 0 until n) {
            val a0 = k * step
            while (nextFix <= t + segS[k]) {
                fix(a0 + step * ((nextFix - t) / segS[k]).coerceIn(0.0, 1.0), nextFix)
                nextFix += w.fixEveryS
            }
            t += segS[k]
            if (pendingBreaks.isNotEmpty() && (k + 1) * step >= pendingBreaks.first().first * times.lengthM) {
                val minutes = pendingBreaks.removeAt(0).second
                val end = t + minutes * 60
                // standing: the recorder keeps a point every 30 s
                nextFix = max(nextFix, t)
                while (nextFix < end) { fix((k + 1) * step, nextFix); nextFix += 30.0 }
                t = end
                nextFix = t
            }
        }
        fix(times.lengthM, t)
        return out
    }

    /** Minimal GPX 1.1 writer for fixtures (times optional). */
    fun gpx(name: String, segs: List<List<TrackPoint>>, withTime: Boolean): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"OnPath TripReplay\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("<metadata><name>").append(name).append("</name></metadata>\n<trk>")
        for (s in segs) {
            sb.append("<trkseg>\n")
            for (p in s) {
                sb.append("<trkpt lat=\"%.6f\" lon=\"%.6f\">".format(Locale.US, p.lat, p.lon))
                p.ele?.let { sb.append("<ele>%.2f</ele>".format(Locale.US, it)) }
                if (withTime && p.timeMs != null) sb.append("<time>").append(java.time.Instant.ofEpochMilli(p.timeMs).toString()).append("</time>")
                sb.append("</trkpt>\n")
            }
            sb.append("</trkseg>")
        }
        sb.append("</trk>\n</gpx>\n")
        return sb.toString()
    }
}
