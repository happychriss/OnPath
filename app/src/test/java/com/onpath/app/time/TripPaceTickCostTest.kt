package com.onpath.app.time

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field 2026-11-07 (Pixel, tour 221): "Main thread blocked 695 ms" sampled in `TripPace.tick` from the recording's
 * one-second tick. The tick must cost the same on a 10 000-point line after hours as on a short one: no loop over the
 * line or the track ([RouteTimes] keeps cumulative sums, the fix history is bounded to [TripPace.STALE_S]).
 */
class TripPaceTickCostTest {

    /** ~100 km line of 10 000 points with ups and downs. */
    private val line = (0 until 10_000).map { i ->
        GeoPoint(51.0 + i * 0.00009, 6.9, 100.0 + 40 * kotlin.math.sin(i / 50.0))
    }

    /** Average nanoseconds per call of [block] over [n] calls (after a warm-up). */
    private fun nsPer(n: Int, block: (Int) -> Unit): Double {
        repeat(n) { block(it) } // warm-up (JIT)
        val t0 = System.nanoTime()
        repeat(n) { block(it) }
        return (System.nanoTime() - t0).toDouble() / n
    }

    @Test fun `a tick on a 10 000 point line after a long tour stays cheap`() {
        val times = RouteTimes.of(line, Activity.HIKE)
        val trip = TripPace()
        trip.setLine(times, 0.0)
        // four hours of fixes along the line: history, breaks and learned sums as after a long day
        var s = 0.0
        var along = 0.0
        val wall0 = 1_800_000_000_000L
        repeat(4 * 3600) {
            s += 1.0; along += 1.2
            trip.onFix(s, wall0 + (s * 1000).toLong(), 51.0 + along / 111_000, 6.9, along, onRoute = true)
        }
        val ns = nsPer(20_000) { trip.tick(s + it, wall0 + ((s + it) * 1000).toLong()) }
        // a JVM tick is a few µs; 0.2 ms leaves room for a slow CI runner and still catches a loop over 10 000 points
        assertTrue("tick took ${ns / 1000} µs", ns < 200_000)
    }

    @Test fun `with the plan cleared during the tour the tick is cheap too`() {
        val trip = TripPace()
        trip.setLine(RouteTimes.of(line, Activity.HIKE), 0.0)
        trip.onFix(10.0, 1_800_000_010_000L, 51.0, 6.9, 0.0, onRoute = true)
        trip.setLine(null, null) // 13:40:21 "Clear route: plan 5 → 0 points (tour running)"
        val ns = nsPer(20_000) { trip.tick(11.0 + it, 1_800_000_011_000L + it * 1000L) }
        assertTrue("tick took ${ns / 1000} µs", ns < 200_000)
    }

    @Test fun `tick cost does not grow with the line length`() {
        fun cost(points: List<GeoPoint>): Double {
            val trip = TripPace()
            trip.setLine(RouteTimes.of(points, Activity.HIKE), 0.0)
            trip.onFix(10.0, 1_800_000_010_000L, 51.0, 6.9, 5.0, onRoute = true)
            return nsPer(50_000) { trip.tick(11.0 + it, 1_800_000_011_000L + it * 1000L) }
        }
        val short = cost(line.take(100))
        val long = cost(line)
        // O(1): the 100 times longer line may not cost 100 times more (generous: 10× for timer noise)
        assertTrue("short ${short / 1000} µs, long ${long / 1000} µs", long < 10 * short + 5_000)
    }
}
