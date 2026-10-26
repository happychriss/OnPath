package com.onpath.app.rides

import com.onpath.strings.*

import com.onpath.app.data.Tour
import com.onpath.app.data.TourDb
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What [RideApply] reads and writes (TourDb in the app, a map in tests). */
interface RideStore {
    fun tour(id: Long): Tour?
    fun segments(id: Long): List<List<TrackPoint>>
    /** Rides + stats of [id] in one step; false when the tour is gone. Points are never touched. */
    fun setRides(id: Long, rec: RideRecord?, stats: TourStats): Boolean
    /** A new recorded tour (a part split off) with its own copy of the points; its id. */
    fun insertPart(from: Tour, name: String, segments: List<List<TrackPoint>>, stats: TourStats, createdMs: Long): Long
}

class TourDbRides(private val db: TourDb) : RideStore {
    override fun tour(id: Long) = db.getTour(id)
    override fun segments(id: Long) = db.loadSegments(id)
    override fun setRides(id: Long, rec: RideRecord?, stats: TourStats) =
        db.setRides(id, rec, stats.distanceM, stats.ascentM, stats.descentM, stats.durationS)
    override fun insertPart(from: Tour, name: String, segments: List<List<TrackPoint>>, stats: TourStats, createdMs: Long) =
        db.insertTour(TourKind.RECORDED, name, from.activity, from.sac, stats.distanceM, stats.ascentM, stats.descentM,
            stats.durationS, segments, createdMs = createdMs)
}

/**
 * Applies the choice of the end-of-tour summary (docs/design/vehicle-detection.md) – JVM tested (RideApplyTest).
 * 1. The app stores the user's choice on the still-recording tour ([choose]) and then stops the recording.
 * 2. The recording service, when it ends the tour, calls [finish] with the stats as recorded: the choice is applied
 *    (stats; "Split" adds the tours after the rides). Without a choice the rides are found and only marked
 *    ("Save as recorded") – e.g. a tour ended on arrival or saved after a crash; "Edit rides" can change it later.
 * The points stay exactly as recorded in every case.
 */
object RideApply {

    /** The summary's choice, stored before the recording stops ([finish] applies it). */
    fun choose(store: RideStore, id: Long, rides: List<Ride>, choice: RideChoice): Boolean {
        val t = store.tour(id) ?: return false
        val rec = RideRecord(rides, choice, TourStats(t.distanceM, t.ascentM, t.descentM, t.durationS))
        return store.setRides(id, rec, rec.recorded)
    }

    /**
     * The tour [id] has ended with [recorded] stats at [endMs]. Returns the ids of tours split off (empty otherwise).
     * Never throws: a failure is reported and the tour stays as recorded.
     */
    fun finish(store: RideStore, id: Long, recorded: TourStats, endMs: Long, activity: Activity, nameAt: (Long) -> String = ::dateName): List<Long> = try {
        val t = store.tour(id)
        if (t == null) emptyList() else {
            val segs = store.segments(id)
            val chosen = t.rides
            if (chosen == null) {
                val found = RideDetector.detect(segs, activity)
                if (found.isNotEmpty()) {
                    store.setRides(id, RideRecord(found, RideChoice.AS_RECORDED, recorded, endMs), recorded)
                    Problems.info("rides", "${found.size} ride(s) found in the ended tour – kept as recorded (Edit rides in My routes)")
                }
                emptyList()
            } else apply(store, t, segs, chosen.copy(recorded = recorded, endMs = endMs), nameAt)
        }
    } catch (e: Exception) {
        Problems.error("rides", "The choice for the ride could not be applied – the tour is saved as recorded", e)
        emptyList()
    }

    /** "Edit rides" on a saved tour: another reading of the same points. False when it has no rides. */
    fun edit(store: RideStore, id: Long, choice: RideChoice, nameAt: (Long) -> String = ::dateName): List<Long>? {
        val t = store.tour(id) ?: return null
        val rec = t.rides ?: return null
        return apply(store, t, store.segments(id), rec.copy(choice = choice), nameAt)
    }

    private fun apply(store: RideStore, t: Tour, segs: List<List<TrackPoint>>, rec: RideRecord, nameAt: (Long) -> String): List<Long> {
        val stats = RideView.stats(segs, rec)
        val parts = if (rec.choice == RideChoice.SPLIT) RideView.splitParts(segs, rec.rides) else emptyList()
        // the parts first: if one fails, the original still reads in full (nothing is lost)
        val ids = parts.map { p ->
            val start = p.flatten().firstNotNullOfOrNull { it.timeMs } ?: System.currentTimeMillis()
            store.insertPart(t, nameAt(start), p, RideView.partStats(p), start)
        }
        if (!store.setRides(t.id, rec, stats)) Problems.warn("rides", "Tour gone before its rides could be saved")
        return ids
    }

    /** Like a new recording: "Tour 22 Oct 2026, 16:58" (the app proposes a place name afterwards). */
    fun dateName(ms: Long): String =
        Txt.s(Res.string.nav_tour_date_name, SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms)))
}
