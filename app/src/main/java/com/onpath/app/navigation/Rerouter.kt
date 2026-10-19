package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.RouteCancel
import com.onpath.app.routing.RouteLimits
import com.onpath.app.routing.RouteResult
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.routing.RoutingException
import com.onpath.app.routing.StraightLineEngine

/**
 * Finds a way back onto the original line ahead (docs/plan-auto-reroute.md §5, option C). Blocking – call it on a
 * background thread. At most [RerouteTargets.MAX_CANDIDATES] short engine requests ([PER_REQUEST_MS] each,
 * [TOTAL_MS] together); the candidate with the lowest "way back + rest of the original" time wins (ties → nearer).
 * Candidates whose way back would skip much of the route ahead (round trip end, out-and-back) are not taken.
 * The rest of the original line stays exactly as planned. Never throws.
 */
class Rerouter(private val engine: RoutingEngine, private val clock: () -> Long = System::currentTimeMillis) {

    data class Request(
        val position: GeoPoint,
        val original: NavLine,
        /** Progress on the original line when the user left it. */
        val progressM: Double,
        val stops: List<NavStop>,
        val visited: Set<Int>,
        val activity: Activity,
        val sac: Int,
        val perRequestMs: Long = PER_REQUEST_MS,
        val totalMs: Long = TOTAL_MS,
    )

    enum class Reason { NO_DATA, NO_WAY, TIMEOUT, CANCELLED, UNAVAILABLE }

    sealed interface Outcome { val tookMs: Long }

    data class Found(
        /** The new navigation line: way back + rest of the original. */
        val line: List<GeoPoint>,
        /** Length of the way back (start of the original rest on [line]). */
        val legM: Double,
        /** Where the original rest starts on the original line. */
        val resumeAlongM: Double,
        /** New remaining length minus the original remaining length from where the user is along it (< 0 = shorter). */
        val extraM: Double,
        /** Metres along the new line until it is back on the original for good. */
        val rejoinInM: Double,
        /** Distance from the position to where the new line starts (snapped onto a way). */
        val startOffM: Double,
        val targetStop: Int?,
        val skippedStops: Set<Int>,
        val tried: Int,
        override val tookMs: Long,
    ) : Outcome

    data class Failed(val reason: Reason, val detail: String, override val tookMs: Long) : Outcome

    fun reroute(req: Request, cancel: RouteCancel? = null): Outcome {
        val t0 = clock()
        if (engine is StraightLineEngine) return Failed(Reason.UNAVAILABLE, "Offline router not available", 0)
        if (!req.original.isUsable) return Failed(Reason.NO_WAY, "Nothing to rejoin", 0)
        val base = RerouteTargets.base(req.original, req.position, req.progressM)
        val skipped = RerouteTargets.passed(req.stops, base, req.visited)
        val targets = RerouteTargets.candidates(req.original, base, req.stops, req.visited)
        val deadline = t0 + req.totalMs
        // same time model as the plan sheet and "to go" (time/RouteTimes)
        val restTimes = com.onpath.app.time.RouteTimes.of(req.original.points, req.activity)
        var best: Pair<RejoinTarget, RouteResult>? = null
        var bestScore = Double.MAX_VALUE
        var nearest: Pair<RejoinTarget, RouteResult>? = null
        var firstError: String? = null
        var tried = 0
        for (t in targets) {
            if (cancel?.isCancelled == true) return Failed(Reason.CANCELLED, "Cancelled", clock() - t0)
            val left = deadline - clock()
            if (left < MIN_LEFT_MS) break
            tried++
            val r = try {
                engine.route(listOf(req.position, t.point), req.activity, req.sac,
                    RouteLimits(minOf(req.perRequestMs, left), cancel))
            } catch (e: Exception) {
                // expected: RoutingException = no way to this candidate; anything else is an engine bug
                if (e !is RoutingException) Problems.error("routing", "Re-route candidate request threw unexpectedly", e)
                val msg = e.message ?: "No route found"
                if (firstError == null) firstError = msg
                if (cancel?.isCancelled == true) return Failed(Reason.CANCELLED, msg, clock() - t0)
                // the position itself has no data / no way nearby: the other candidates fail the same way
                if (reasonOf(msg) == Reason.NO_DATA || msg.startsWith("Start point")) break
                continue
            }
            if (r.points.size < 2) continue
            if (nearest == null) nearest = t to r
            // A way back much shorter than the part of the route it replaces skips the plan (the end of a round trip
            // next to you, the way back of an out-and-back): not a rejoin.
            if (skipsRoute(r.distanceM, t.alongM - base)) continue
            val legS = com.onpath.app.time.RouteTimes.of(r.points, req.activity).total()
            val rest = restTimes.remainingFrom(t.alongM)
            val score = legS + rest
            if (score < bestScore - TIE_S) { bestScore = score; best = t to r }
        }
        val took = clock() - t0
        val (target, leg) = best ?: nearest ?: return Failed(firstError?.let { reasonOf(it) } ?: Reason.TIMEOUT,
            firstError ?: "No time left", took)
        val line = RerouteTargets.splice(leg.points, req.original, target.alongM)
        val legLen = Geo.length(leg.points)
        val newRemaining = legLen + (req.original.lengthM - target.alongM)
        val oldRemaining = req.original.lengthM - base
        return Found(
            line = line,
            legM = legLen,
            resumeAlongM = target.alongM,
            extraM = newRemaining - oldRemaining,
            rejoinInM = RerouteTargets.rejoinInM(leg.points, req.original, req.progressM - 300.0, target.alongM + 10.0),
            startOffM = Geo.distance(req.position, leg.points.first()),
            targetStop = target.stopIndex,
            skippedStops = skipped,
            tried = tried,
            tookMs = took,
        )
    }

    private fun skipsRoute(legM: Double, sectionM: Double) = legM + SKIP_SLACK_M < SKIP_RATIO * sectionM

    companion object {
        const val PER_REQUEST_MS = 8_000L
        const val TOTAL_MS = 12_000L
        private const val MIN_LEFT_MS = 300L
        /** Scores closer than this (seconds) are a tie: the nearer candidate (tried first) stays. */
        private const val TIE_S = 5.0
        /** A way back shorter than this share of the skipped route part (+ slack) is a skip, not a rejoin. */
        const val SKIP_RATIO = 0.8
        const val SKIP_SLACK_M = 100.0

        fun reasonOf(message: String): Reason = when {
            message.contains("No routing data") -> Reason.NO_DATA
            message.contains("cancelled") -> Reason.CANCELLED
            message.contains("busy") || message.contains("took too long") || message.contains("within") -> Reason.TIMEOUT
            message.contains("not available") || message.contains("could not be loaded") -> Reason.UNAVAILABLE
            else -> Reason.NO_WAY
        }
    }
}
