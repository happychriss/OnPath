package com.onpath.app.rides

import com.onpath.app.gpx.TrackPoint
import com.onpath.app.recording.PointFilter
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** A raw GPS fix as the phone delivers it (before the recording's filter). */
data class Fix(val lat: Double, val lon: Double, val ele: Double?, val t: Long, val acc: Double = 5.0)

/** What the recording stored (as RecordingService does it: PointFilter, gaps → new piece unless bridged). */
data class Recorded(val segments: List<List<TrackPoint>>, val distanceM: Double, val ascentM: Double, val descentM: Double, val durationS: Double) {
    val stats get() = TourStats(distanceM, ascentM, descentM, durationS)
    val points get() = segments.flatten()
}

/**
 * Builds real-like GPS traces second by second: walking, waiting at a stop, a bus with stops, a car on a pass road,
 * a cable car, a fast bike descent, GPS jumps and gaps. Positions in a local metric frame, noise from a fixed seed.
 */
class TraceBuilder(lat0: Double = 46.67, lon0: Double = 11.16, ele0: Double = 600.0,
                   t0: Long = Instant.parse("2026-10-22T08:00:00Z").toEpochMilli(), seed: Int = 7) {
    var lat = lat0; var lon = lon0; var ele = ele0; var t = t0
    val fixes = ArrayList<Fix>()
    private val rnd = Random(seed)
    /** Times worth asserting on (e.g. "arrived at the stop"). */
    val marks = LinkedHashMap<String, Long>()

    fun mark(name: String) = apply { marks[name] = t }

    private fun gauss() = rnd.nextDouble(-1.0, 1.0) + rnd.nextDouble(-1.0, 1.0) + rnd.nextDouble(-1.0, 1.0)

    private fun step(dx: Double, dy: Double, dz: Double) {
        lat += dy / 111_320.0
        lon += dx / (111_320.0 * cos(Math.toRadians(lat)))
        ele += dz
    }

    // GPS error drifts slowly (correlated from second to second, ~2.5 m), as real receivers do
    private var nx = 0.0; private var ny = 0.0; private var nz = 0.0

    /** A fix at the true position + drifting error; [offsetM] towards [offsetDeg] = a GPS jump on top. */
    fun fix(offsetM: Double = 0.0, offsetDeg: Double = 0.0, acc: Double = 5.0) {
        nx = 0.97 * nx + gauss() * 0.6; ny = 0.97 * ny + gauss() * 0.6; nz = 0.97 * nz + gauss() * 0.8
        val ox = nx + offsetM * sin(Math.toRadians(offsetDeg))
        val oy = ny + offsetM * cos(Math.toRadians(offsetDeg))
        fixes += Fix(lat + oy / 111_320.0, lon + ox / (111_320.0 * cos(Math.toRadians(lat))), ele + nz, t, acc)
    }

    /** Moves [seconds] at [kmh] towards [heading] (deg), climbing [vertMs]; a fix every second unless [noFix]. */
    fun move(seconds: Int, kmh: Double, heading: Double, vertMs: Double = 0.0, noFix: Boolean = false,
             headingAt: ((Int) -> Double)? = null) = apply {
        val v = kmh / 3.6
        repeat(seconds) { s ->
            val h = Math.toRadians(headingAt?.invoke(s) ?: heading)
            step(v * sin(h), v * cos(h), vertMs)
            t += 1000
            if (!noFix) fix()
        }
    }

    fun walk(minutes: Double, kmh: Double = 4.5, heading: Double = 60.0, vertMs: Double = 0.1) =
        move((minutes * 60).toInt(), kmh, heading, vertMs, headingAt = { s -> heading + 25 * sin(s / 90.0) })

    /** Standing (waiting at a stop): a fix every second with noise, no movement. */
    fun stand(seconds: Int) = apply { repeat(seconds) { t += 1000; fix() } }

    /** No fix at all (bus without GPS, tunnel): [distM] covered towards [heading]. */
    fun gap(seconds: Int, distM: Double, heading: Double, dEle: Double = 0.0) = apply {
        val h = Math.toRadians(heading)
        step(distM * sin(h), distM * cos(h), dEle)
        t += seconds * 1000L
        fix()
    }

    /** City bus: drives [driveS] at [kmh], stops [stopS] (doors open), [legs] times; slight bends. */
    fun bus(legs: Int, driveS: Int = 90, kmh: Double = 40.0, stopS: Int = 30, heading: Double = 120.0) = apply {
        repeat(legs) { leg ->
            move(driveS, kmh, heading, headingAt = { s -> heading + 20 * sin((leg * driveS + s) / 60.0) })
            stand(stopS)
        }
    }

    /** Car on a pass road: switchbacks (±[swing]° around [heading], turning every [legS] s), climbing [vertMs]. */
    fun passRoad(seconds: Int, kmh: Double, heading: Double, vertMs: Double, legS: Int = 50, swing: Double = 60.0) =
        move(seconds, kmh, heading, vertMs, headingAt = { s -> heading + if ((s / legS) % 2 == 0) swing else -swing })
}

/** Runs [fixes] through the recording's rules (RecordingService.onFix: accuracy, PointFilter, gap → piece / bridge). */
fun record(fixes: List<Fix>): Recorded {
    val segs = ArrayList<MutableList<TrackPoint>>()
    var last: TrackPoint? = null
    var lastFixT = 0L
    var dist = 0.0
    var eleRef: Double? = null
    var up = 0.0; var down = 0.0
    for (f in fixes) {
        if (!PointFilter.accuracyOk(true, f.acc.toFloat())) continue
        val wasLost = lastFixT > 0 && f.t - lastFixT > 30_000L
        lastFixT = f.t
        val p = TrackPoint(f.lat, f.lon, f.ele, f.t)
        val prev = last
        val bridged = wasLost && prev != null && PointFilter.bridgesGap(prev, p)
        val (v, moved) = PointFilter.judge(prev, p, f.acc, continuous = !wasLost || bridged)
        if (v != PointFilter.Verdict.ACCEPT) continue
        dist += moved
        if (segs.isEmpty() || (wasLost && prev != null && !bridged)) segs += ArrayList<TrackPoint>()
        p.ele?.let { e ->
            val r = eleRef
            if (r == null) eleRef = e
            else if (e - r >= 5) { up += e - r; eleRef = e }
            else if (r - e >= 5) { down += r - e; eleRef = e }
        }
        segs.last() += p
        last = p
    }
    val ts = segs.flatten().mapNotNull { it.timeMs }
    val dur = if (ts.isEmpty()) 0.0 else (fixes.last().t - fixes.first().t) / 1000.0
    return Recorded(segs, dist, up, down, dur)
}
