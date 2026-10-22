package com.onpath.app.rides

import com.onpath.app.data.Geo
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import kotlin.math.abs

/**
 * Finds bus / car / train rides and lifts in a recorded track – GPS only, no activity recognition (no Play Services).
 * Runs over the stored points at "End tour" (docs/design/vehicle-detection.md). Pure, JVM tested (RideDetectorTest).
 *
 * Vehicle (owner limits 2026-10-22, adjust after field tests): a stretch whose average speed over every minute is
 * above the limit – hike > 12 km/h for ≥ 60 s and ≥ 500 m, bike / MTB / e-bike > 50 km/h for ≥ 60 s and ≥ 1 km.
 * GPS gaps count (distance / time across the gap: a bus without GPS, a tunnel). Stops of a bus (≤ 3 min, ≤ 250 m)
 * join the pieces of one ride. No single outlier counts:
 * - a lone GPS jump (out and back) is removed before anything is measured;
 * - the stretch must get somewhere: start → end at least half the minimum distance, and not mostly back and forth
 *   (straight distance ≥ 30 % of the path) – GPS wandering in a gorge does neither;
 * - on foot a minute at > 12 km/h is a run, not a bus: the fastest 30 s must also be above 20 km/h;
 * - on a bike > 50 km/h downhill (≤ −3 %) is a fast descent, not a car – unless above 80 km/h.
 * Lift: > 1 m/s vertical for ≥ 60 s and ≥ 100 m, on a nearly straight line (≥ 90 %), at least 20 % steep. The design
 * said 2 m/s; the owner's Taser gondola (2026-10-22, no fix in the cabin) went 1500 → 907 m in 356 s = 1.67 m/s, and no
 * hiker climbs or descends 1 m/s for minutes. Downhill on a bike (where 1–2 m/s is a normal trail descent) it takes
 * > 2 m/s, even straighter (≥ 97 %) and steeper (≥ 35 %): a fast straight trail descent is not a lift.
 * Proposed start of a ride: the last point where the user still moved before it – the waiting at the stop (points
 * within 40 m of where the ride began) is cut off with the ride.
 */
object RideDetector {
    const val MIN_S = 60.0
    const val FOOT_LIMIT_MS = 12 / 3.6
    const val FOOT_MIN_M = 500.0
    const val FOOT_PEAK_MS = 20 / 3.6
    const val PEAK_WINDOW_S = 30.0
    const val BIKE_LIMIT_MS = 50 / 3.6
    const val BIKE_MIN_M = 1000.0
    const val BIKE_ANY_MS = 80 / 3.6
    const val BIKE_DOWNHILL_GRADE = -0.03
    const val MIN_STRAIGHTNESS = 0.3
    const val MERGE_GAP_S = 180.0
    const val MERGE_GAP_M = 250.0
    const val LIFT_VERTICAL_MS = 1.0
    const val LIFT_BIKE_DOWN_VERTICAL_MS = 2.0
    const val LIFT_MIN_VERTICAL_M = 100.0
    const val LIFT_STRAIGHTNESS = 0.9
    const val LIFT_MIN_GRADE = 0.2
    const val LIFT_BIKE_DOWN_STRAIGHTNESS = 0.97
    const val LIFT_BIKE_DOWN_GRADE = 0.35
    const val STOP_RADIUS_M = 40.0
    private const val SPIKE_MIN_M = 40.0
    /** Path for straightness: points at least this far apart in time (GPS jitter of 1 s fixes would lengthen it). */
    private const val SAMPLE_S = 15.0

    fun isFoot(a: Activity) = a == Activity.HIKE

    /** The rides in [segments] (as stored), in order and not overlapping. Empty when nothing was ridden. */
    fun detect(segments: List<List<TrackPoint>>, activity: Activity): List<Ride> {
        val flat = segments.flatten()
        val w = Work.of(flat)
        if (w.n < 3) return emptyList()
        val lifts = lifts(w, activity)
        // a lift is not also a bus: its pairs are left out before bus pieces are joined (Taser: gondola, 3 min, car)
        val vehicles = vehicles(w, activity, lifts).filter { v -> lifts.none { l -> v.first < l.second && l.first < v.second } }
        val runs = (lifts.map { Triple(it.first, it.second, RideKind.LIFT) } + vehicles.map { Triple(it.first, it.second, RideKind.VEHICLE) })
            .sortedBy { it.first }
        val out = ArrayList<Ride>()
        for ((s, e, kind) in runs) {
            val startFlat = w.idx[s]
            val endFlat = w.idx[e]
            val floor = out.lastOrNull()?.to ?: 0
            if (startFlat < floor) continue // overlaps the ride before (should not happen): keep the first
            var from = startFlat
            val p0 = flat[startFlat]
            while (from - 1 >= floor && Geo.distance(flat[from - 1].lat, flat[from - 1].lon, p0.lat, p0.lon) <= STOP_RADIUS_M) from--
            if (endFlat > from) out += Ride(from, endFlat, kind)
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------- vehicles

    private fun vehicles(w: Work, activity: Activity, lifts: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val foot = isFoot(activity)
        val limit = if (foot) FOOT_LIMIT_MS else BIKE_LIMIT_MS
        val minM = if (foot) FOOT_MIN_M else BIKE_MIN_M
        val fast = BooleanArray(w.n - 1)
        var j = 0
        for (i in 0 until w.n - 1) {
            if (j < i + 1) j = i + 1
            while (j < w.n - 1 && w.t[j] - w.t[i] < MIN_S) j++
            val dt = w.t[j] - w.t[i]
            if (dt < MIN_S) break
            if ((w.p[j] - w.p[i]) / dt > limit) for (k in i until j) fast[k] = true
        }
        for ((ls, le) in lifts) for (k in ls until le) fast[k] = false
        val runs = merge(runsOf(fast), w)
        return runs.mapNotNull { (s0, e0) ->
            var s = s0; var e = e0
            while (s < e && w.pairSpeed(s) < limit / 2) s++
            while (e > s && w.pairSpeed(e - 1) < limit / 2) e--
            val dur = w.t[e] - w.t[s]
            val dist = w.p[e] - w.p[s]
            if (dur < MIN_S || dist < minM || dist / dur <= limit) return@mapNotNull null
            val straight = w.straight(s, e)
            if (straight < minM / 2 || straight < MIN_STRAIGHTNESS * w.sampledPath(s, e)) return@mapNotNull null
            if (foot && w.peakSpeed(s, e, PEAK_WINDOW_S) <= FOOT_PEAK_MS) return@mapNotNull null
            if (!foot && dist / dur <= BIKE_ANY_MS) {
                val es = w.ele[s]; val ee = w.ele[e]
                if (!es.isNaN() && !ee.isNaN() && (ee - es) / dist < BIKE_DOWNHILL_GRADE) return@mapNotNull null
            }
            s to e
        }
    }

    /** Fast pairs → runs of points (start, end). */
    private fun runsOf(fast: BooleanArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var k = 0
        while (k < fast.size) {
            if (!fast[k]) { k++; continue }
            val a = k
            while (k < fast.size && fast[k]) k++
            out += a to k
        }
        return out
    }

    /** A bus stop or a red light between two fast pieces joins them. */
    private fun merge(runs: List<Pair<Int, Int>>, w: Work): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (r in runs) {
            val last = out.lastOrNull()
            if (last != null && w.t[r.first] - w.t[last.second] <= MERGE_GAP_S && w.p[r.first] - w.p[last.second] <= MERGE_GAP_M) {
                out[out.lastIndex] = last.first to r.second
            } else out += r
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------- lifts

    private fun lifts(w: Work, activity: Activity): List<Pair<Int, Int>> {
        val steep = BooleanArray(w.n - 1)
        var j = 0
        for (i in 0 until w.n - 1) {
            if (w.ele[i].isNaN()) continue
            if (j < i + 1) j = i + 1
            while (j < w.n - 1 && (w.t[j] - w.t[i] < MIN_S || w.ele[j].isNaN())) j++
            val dt = w.t[j] - w.t[i]
            if (dt < MIN_S || w.ele[j].isNaN()) break
            if (abs(w.ele[j] - w.ele[i]) / dt > LIFT_VERTICAL_MS) for (k in i until j) steep[k] = true
        }
        return runsOf(steep).mapNotNull { (s0, e0) ->
            var s = s0; var e = e0
            while (s < e && (w.ele[s].isNaN() || w.vertical(s) < LIFT_VERTICAL_MS / 2)) s++
            while (e > s && (w.ele[e].isNaN() || w.vertical(e - 1) < LIFT_VERTICAL_MS / 2)) e--
            val dur = w.t[e] - w.t[s]
            if (dur < MIN_S || w.ele[s].isNaN() || w.ele[e].isNaN()) return@mapNotNull null
            val dz = w.ele[e] - w.ele[s]
            if (abs(dz) < LIFT_MIN_VERTICAL_M || abs(dz) / dur <= LIFT_VERTICAL_MS) return@mapNotNull null
            val straight = w.straight(s, e)
            val ratio = if (w.sampledPath(s, e) > 0) straight / w.sampledPath(s, e) else 1.0
            val grade = if (straight > 1) abs(dz) / straight else Double.MAX_VALUE
            val bikeDown = !isFoot(activity) && dz < 0
            val minRatio = if (bikeDown) LIFT_BIKE_DOWN_STRAIGHTNESS else LIFT_STRAIGHTNESS
            val minGrade = if (bikeDown) LIFT_BIKE_DOWN_GRADE else LIFT_MIN_GRADE
            if (ratio < minRatio || grade < minGrade) return@mapNotNull null
            if (bikeDown && abs(dz) / dur <= LIFT_BIKE_DOWN_VERTICAL_MS) return@mapNotNull null
            s to e
        }
    }

    // ---------------------------------------------------------------------------------------------- work list

    /**
     * The points that can be judged: with a time, in time order, without lone GPS jumps. [idx] = index in the flat
     * point list, [t] seconds, [p] path so far (m), [ele] median-smoothed elevation (NaN = unknown).
     */
    private class Work(val idx: IntArray, val lat: DoubleArray, val lon: DoubleArray, val t: DoubleArray,
                       val p: DoubleArray, val ele: DoubleArray) {
        val n get() = idx.size

        fun d(a: Int, b: Int) = Geo.distance(lat[a], lon[a], lat[b], lon[b])
        fun pairSpeed(k: Int): Double = (p[k + 1] - p[k]) / (t[k + 1] - t[k])
        fun vertical(k: Int): Double =
            if (ele[k].isNaN() || ele[k + 1].isNaN()) 0.0 else abs(ele[k + 1] - ele[k]) / (t[k + 1] - t[k])
        fun straight(s: Int, e: Int) = d(s, e)

        /** Path from [s] to [e] over points at least [SAMPLE_S] apart (GPS jitter does not lengthen it). */
        fun sampledPath(s: Int, e: Int): Double {
            var sum = 0.0
            var last = s
            for (k in s + 1..e) if (k == e || t[k] - t[last] >= SAMPLE_S) { sum += d(last, k); last = k }
            return sum
        }

        /** Fastest average speed over [window] seconds inside [s]..[e]. */
        fun peakSpeed(s: Int, e: Int, window: Double): Double {
            var best = 0.0
            var j = s
            for (i in s until e) {
                if (j <= i) j = i + 1
                while (j < e && t[j] - t[i] < window) j++
                val dt = t[j] - t[i]
                if (dt < window) break
                best = maxOf(best, (p[j] - p[i]) / dt)
            }
            return best
        }

        companion object {
            fun of(flat: List<TrackPoint>): Work {
                // time order, with a time
                val timed = ArrayList<Int>()
                var lastT = Long.MIN_VALUE
                flat.forEachIndexed { i, q -> val tm = q.timeMs ?: return@forEachIndexed; if (tm > lastT) { timed += i; lastT = tm } }
                // lone jumps: far out and straight back
                val keep = ArrayList<Int>()
                for (k in timed.indices) {
                    if (k in 1 until timed.size - 1) {
                        val a = flat[timed[k - 1]]; val b = flat[timed[k]]; val c = flat[timed[k + 1]]
                        val ab = Geo.distance(a.lat, a.lon, b.lat, b.lon)
                        val bc = Geo.distance(b.lat, b.lon, c.lat, c.lon)
                        val ac = Geo.distance(a.lat, a.lon, c.lat, c.lon)
                        if (ab > SPIKE_MIN_M && bc > SPIKE_MIN_M && ac < 0.35 * (ab + bc)) continue
                    }
                    keep += timed[k]
                }
                val n = keep.size
                val lat = DoubleArray(n) { flat[keep[it]].lat }
                val lon = DoubleArray(n) { flat[keep[it]].lon }
                val t = DoubleArray(n) { flat[keep[it]].timeMs!! / 1000.0 }
                val p = DoubleArray(n)
                for (k in 1 until n) p[k] = p[k - 1] + Geo.distance(lat[k - 1], lon[k - 1], lat[k], lon[k])
                val raw = DoubleArray(n) { flat[keep[it]].ele ?: Double.NaN }
                // median of 5: one bad altitude fix does not make a lift
                val ele = DoubleArray(n) { k ->
                    if (raw[k].isNaN()) Double.NaN
                    else (maxOf(0, k - 2)..minOf(n - 1, k + 2)).map { raw[it] }.filter { !it.isNaN() }.sorted().let { it[it.size / 2] }
                }
                return Work(keep.toIntArray(), lat, lon, t, p, ele)
            }
        }
    }
}
