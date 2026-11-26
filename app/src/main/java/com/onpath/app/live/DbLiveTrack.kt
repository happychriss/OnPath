package com.onpath.app.live

import com.onpath.app.data.FollowProgress
import com.onpath.app.navigation.NavState
import com.onpath.app.recording.RecState
import kotlin.math.roundToInt

/** A stored track point as the live sharing reads it. */
class LivePointRow(val seq: Int, val seg: Int, val lat: Double, val lon: Double, val ele: Double?, val timeMs: Long?, val accM: Double?)

/**
 * The shared tour's row: when it began, what it is recorded as, and whether it is [over] – ended by the user (the
 * recorder marks it done before its state goes away) or closed by a newer tour; a tour that was only interrupted
 * ("Continue tour" still possible) is not over.
 */
class LiveTourInfo(val createdMs: Long, val activity: String?, val over: Boolean = false)

/**
 * The tour database as the live sharing reads it – **reads only** (docs/design/live-tracking.md §7.1). The app's is
 * [com.onpath.app.data.TourDb] (queries on WAL reader connections: they never wait for the recorder's writes and the
 * recorder never waits for them); tests take the JDBC tables.
 */
interface LivePoints {
    /** The points of [tourId] with a number above [seq], in the order of their numbers, at most [max]. */
    fun after(tourId: Long, seq: Int, max: Int): List<LivePointRow>
    /** The newest point of [tourId]; null = none yet. */
    fun last(tourId: Long): LivePointRow?
    /** The tour's row; null = there is none (deleted, or split into parts at its end). */
    fun tour(tourId: Long): LiveTourInfo?
}

/**
 * The recording as the uploader sees it ([LiveTrack]): the points from the database, the status from the recording
 * service's published [RecState] – a value the service hands out anyway. Nothing here writes, nothing waits for the
 * recorder, and the recorder knows nothing of it.
 *
 * The point number is the recorder's own `seq`: it counts up per tour, survives a restart
 * ([com.onpath.app.data.TourDb.recordingCursor]) and the points are written in its order (the recorder's queue flushes
 * in order, a point that could not be written waits in front of the later ones) – so "above the cursor, by number"
 * never skips a point that arrives late.
 *
 * @param now wall clock (ms) – pauses and point times are wall / GPS time
 */
class DbLiveTrack(
    private val points: LivePoints,
    private val state: () -> RecState?,
    private val batteryPct: () -> Int?,
    private val now: () -> Long,
) : LiveTrack {

    override fun pointsAfter(tourId: Long, seq: Int, max: Int): List<LiveTrackPoint> {
        var lastTime = 0L
        var lastSeg = 0
        return points.after(tourId, seq, max).map { r ->
            // a point without a time (never from the recorder, but the column allows it) takes the one before
            lastTime = r.timeMs ?: lastTime
            lastSeg = maxOf(lastSeg, r.seg) // a segment number never goes back
            LiveTrackPoint(r.seq, LivePoint(lastSeg, r.lat, r.lon, r.ele?.takeIf { it.isFinite() }?.roundToInt(), lastTime))
        }
    }

    override fun head(tourId: Long): LiveHead? {
        val s = state()?.takeIf { it.tourId == tourId } ?: return null
        val last = points.last(tourId)
        val tour = points.tour(tourId)
        val t = now()
        val lastTime = last?.timeMs
        val still = when {
            s.paused -> s.pausedSinceMs?.let { ((t - it) / 1000).coerceAtLeast(0) } ?: 0L
            // recording, but no point kept for a while (the point filter drops fixes while standing): standing still
            !s.gpsLost && lastTime != null && t - lastTime >= STANDING_MS -> (t - lastTime) / 1000
            else -> null
        }
        val nav = s.nav
        val toGo = nav?.shownAlongM?.let { along -> nav.line.takeIf { it.size >= 2 }?.let { FollowProgress(it).at(along, null)?.distanceM } }
        val arrival = if (toGo != null) s.time?.arrivalMs else null
        val planned = PlannedArrival.next(plannedNow, tourId, nav?.takeIf { it.line.size >= 2 }?.let(::versionOf), arrival)
        plannedNow = planned
        return LiveHead(
            state = if (s.paused) LiveState.PAUSED else LiveState.RECORDING,
            fix = last?.let {
                LiveFix(it.lat, it.lon, it.ele?.takeIf { e -> e.isFinite() }?.roundToInt(), it.timeMs ?: 0L,
                    it.accM?.takeIf { a -> a.isFinite() }?.roundToInt())
            },
            gpsLost = s.gpsLost,
            arrived = s.arrived,
            offRouteM = if (s.offRouteAlert) s.offRouteM?.takeIf { it.isFinite() }?.roundToInt() else null,
            distanceM = whole(s.distanceM), ascentM = whole(s.ascentM),
            movingS = whole(s.time?.movingS ?: s.elapsedS.toDouble()),
            startedMs = tour?.createdMs ?: 0L,
            toGoM = toGo?.let(::whole),
            arrivalMs = arrival,
            plannedArrivalMs = planned?.arrivalMs?.takeIf { arrival != null },
            stillS = still?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
            batteryPct = batteryPct()?.coerceIn(0, 100),
            activity = nav?.session?.activity?.name ?: tour?.activity.orEmpty(),
        )
    }

    /** The arrival as planned for the line followed now ([PlannedArrival]). */
    @Volatile private var plannedNow: PlannedArrival? = null

    /** True when the tour [tourId] will not go on: its row says so, or is gone. Throws when the database cannot be read. */
    fun over(tourId: Long): Boolean = points.tour(tourId)?.over ?: true

    override fun route(tourId: Long): LiveRoute? {
        val nav = state()?.takeIf { it.tourId == tourId }?.nav ?: return null
        if (nav.line.size < 2) return null
        return LiveRoute(versionOf(nav), nav.line)
    }

    private fun whole(v: Double): Int = if (v.isFinite()) v.roundToInt().coerceAtLeast(0) else 0

    companion object {
        /** No kept point for this long while recording with GPS = standing still (the head's `stillS`, the slow interval). */
        const val STANDING_MS = 120_000L

        /**
         * A number that changes whenever the followed line does: the navigator's own version (a re-route) and the
         * line's ends and length (another plan, whose navigator starts counting again). Never [LiveShare.NO_ROUTE].
         */
        fun versionOf(nav: NavState): Int {
            var h = nav.version
            h = 31 * h + nav.line.size
            for (p in listOf(nav.line.first(), nav.line[nav.line.size / 2], nav.line.last())) {
                h = 31 * h + p.lat.hashCode()
                h = 31 * h + p.lon.hashCode()
            }
            return if (h == LiveShare.NO_ROUTE) LiveShare.NO_ROUTE - 1 else h
        }
    }
}

/**
 * The arrival as planned (owner 2026-11-26, the viewer's „Etwas später als geplant“): the first arrival estimate for a
 * tour's followed line; a new line (re-plan, [version]) or another tour plans anew. Pure.
 */
data class PlannedArrival(val tourId: Long, val version: Int, val arrivalMs: Long) {
    companion object {
        fun next(before: PlannedArrival?, tourId: Long, version: Int?, estimateMs: Long?): PlannedArrival? = when {
            version == null -> null
            before != null && before.tourId == tourId && before.version == version -> before
            estimateMs == null -> null
            else -> PlannedArrival(tourId, version, estimateMs)
        }
    }
}
