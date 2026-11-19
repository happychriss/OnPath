package com.onpath.app.testkit

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.rides.RideRecord
import com.onpath.app.rides.RideStore
import com.onpath.app.rides.TourStats
import com.onpath.app.ui.tour.TourRows
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * The tour pieces' [TourRows] over the JDBC tables ([JdbcTours]): each write has the SQL of `TourDb` (pinned in
 * `TourDbMirrorTest`) and the ride store the arguments of `TourDbRides`. [onCall] sees every call by name (a flow checks the
 * thread there); a name in [failOn] makes ITS NEXT call throw once, as a full disk would (the name is then removed).
 */
class JdbcTourRows(val db: JdbcTours, private val onCall: (String) -> Unit = {}) : TourRows {
    val failOn = HashSet<String>()
    /** Called inside `setDeleted`, before the write (what the tour row and the screen held at that moment). */
    var beforeSetDeleted: (Long, Long?) -> Unit = { _, _ -> }

    private fun gate(name: String) {
        onCall(name)
        if (failOn.remove(name)) throw java.sql.SQLException("database or disk is full ($name)")
    }

    override fun updateActivity(id: Long, activity: String, sac: Int) { gate("updateActivity"); db.updateActivity(id, activity, sac) }

    override fun updateStats(id: Long, distance: Double, ascent: Double, descent: Double, duration: Double, state: String): Int {
        gate("updateStats"); return db.updateStats(id, distance, ascent, descent, duration, state)
    }

    override fun setDeleted(id: Long, deletedMs: Long?): Boolean {
        gate("setDeleted"); beforeSetDeleted(id, deletedMs); return db.setDeleted(id, deletedMs)
    }

    override fun rides(): RideStore = object : RideStore {
        override fun tour(id: Long): Tour? { gate("rides.tour"); return db.getTour(id) }
        override fun segments(id: Long): List<List<TrackPoint>> { gate("rides.segments"); return db.loadSegments(id) }
        override fun setRides(id: Long, rec: RideRecord?, stats: TourStats): Boolean {
            gate("rides.setRides"); return db.setRides(id, rec, stats.distanceM, stats.ascentM, stats.descentM, stats.durationS)
        }
        override fun insertPart(from: Tour, name: String, segments: List<List<TrackPoint>>, stats: TourStats, createdMs: Long): Long {
            gate("rides.insertPart")
            return db.insertTour(TourKind.RECORDED, name, from.activity, from.sac, segments, distance = stats.distanceM, ascent = stats.ascentM,
                descent = stats.descentM, duration = stats.durationS, createdMs = createdMs)
        }
    }
}

/** A [ManualExecutor] as the coroutine dispatcher a slice's `io` wants: what is dispatched to it waits until the test steps the executor. */
fun held(ex: ManualExecutor): CoroutineDispatcher = object : java.util.concurrent.AbstractExecutorService() {
    override fun execute(command: Runnable) = ex.execute(command)
    override fun shutdown() = Unit
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
    override fun isShutdown() = false
    override fun isTerminated() = false
    override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
}.asCoroutineDispatcher()
