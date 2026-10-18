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
 * clock was. Progress matching is a simple forward window on [NavLine] (like the service's off-route detector).
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
        val line = NavLine(plan)
        val times = RouteTimes.of(plan, activity)
        val trip = TripPace(prior)
        trip.setLine(times)
        // active clock: time inside segments (a new segment = pause / GPS gap: its time is not counted)
        data class Fix(val activeS: Double, val wallMs: Long, val p: GeoPoint, val along: Double?, val onRoute: Boolean)
        val fixes = ArrayList<Fix>()
        var active = 0.0
        var progress = 0.0
        var started = false
        for (seg in track) {
            var lastT: Long? = null
            for (tp in seg) {
                val t = tp.timeMs ?: continue
                lastT?.let { active += (t - it) / 1000.0 }
                lastT = t
                val p = GeoPoint(tp.lat, tp.lon)
                // forward window (out-and-back routes: the way back must not match the way out)
                val m = if (!started) line.matchEarliest(p) else line.match(p, progress - 5, progress + 600, preferAlongM = progress + 20)
                val on = m != null && m.distanceM <= 40
                if (on) { progress = m!!.alongM; started = true }
                else if (DEBUG && started && (fixes.lastOrNull()?.onRoute != false)) println("  $name lost at progress ${progress.toInt()} d ${m?.distanceM?.toInt()} t ${active.toInt()}")
                fixes += Fix(active, t, p, if (on) progress else null, on)
            }
        }
        val total = times.lengthM
        val cps = doubleArrayOf(0.25, 0.5, 0.75)
        val shownAt = DoubleArray(3) { Double.NaN }; val staticAt = DoubleArray(3) { Double.NaN }
        val movingAt = DoubleArray(3) { Double.NaN }; val rawAt = DoubleArray(3) { Double.NaN }
        val jumps = ArrayList<Double>()
        var lastArr: Long? = null; var lastJumpAlong = -1e9; var lastBreak = 0.0
        var cp = 0
        for (f in fixes) {
            val bBefore = trip.snapshot().breakS
            trip.onFix(f.activeS, f.wallMs, f.p.lat, f.p.lon, f.along, f.onRoute)
            val snap = trip.snapshot()
            if (DEBUG && snap.breakS - bBefore > 30) println("  $name break +${(snap.breakS - bBefore).toInt()} s at ${f.along?.toInt()} m on ${f.onRoute}, active ${f.activeS.toInt()}")
            val a = f.along ?: continue
            snap.arrivalMs?.let { arr ->
                // a break moves the arrival by its length – that is real, not a jump: start over after it
                if (snap.breakS != lastBreak) { lastBreak = snap.breakS; lastArr = null }
                if (a - lastJumpAlong >= 100 || lastArr == null) {
                    lastArr?.let { jumps += abs(arr - it) / 60_000.0 }
                    lastArr = arr; lastJumpAlong = a
                }
            }
            while (cp < 3 && a >= cps[cp] * total) {
                shownAt[cp] = snap.toGoS ?: Double.NaN
                staticAt[cp] = TimeEstimate.round(times.remainingFrom(a, prior))
                movingAt[cp] = trip.movingS()
                rawAt[cp] = trip.rawToGoS() ?: Double.NaN
                if (DEBUG) println("  $name @${cps[cp]}: moving ${trip.movingS().toInt()} observed ${trip.observedS().toInt()} f ${trip.factors()} raw ${rawAt[cp].toInt()} static ${staticAt[cp].toInt()}")
                cp++
            }
        }
        val endMoving = trip.movingS()
        // real remaining moving time from each checkpoint to the end of the line (the track's end)
        val err = DoubleArray(3) { i -> if (movingAt[i].isNaN()) Double.NaN else abs(shownAt[i] / (endMoving - movingAt[i]) - 1) }
        val stat = DoubleArray(3) { i -> if (movingAt[i].isNaN()) Double.NaN else abs(staticAt[i] / (endMoving - movingAt[i]) - 1) }
        val raw = DoubleArray(3) { i -> if (movingAt[i].isNaN()) Double.NaN else abs(rawAt[i] / (endMoving - movingAt[i]) - 1) }
        val sj = jumps.sorted()
        return Result(name, err, stat, raw, times.total(prior) / endMoving - 1, sj.lastOrNull() ?: 0.0,
            if (sj.isEmpty()) 0.0 else sj[(sj.size * 0.9).toInt().coerceAtMost(sj.size - 1)],
            endMoving, trip.snapshot().breakS, trip.factors())
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
