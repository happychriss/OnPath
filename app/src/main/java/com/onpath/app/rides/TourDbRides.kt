package com.onpath.app.rides

import com.onpath.app.data.Tour
import com.onpath.app.data.TourDb
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.TrackPoint

class TourDbRides(private val db: TourDb) : RideStore {
    override fun tour(id: Long) = db.getTour(id)
    override fun segments(id: Long) = db.loadSegments(id)
    override fun setRides(id: Long, rec: RideRecord?, stats: TourStats) =
        db.setRides(id, rec, stats.distanceM, stats.ascentM, stats.descentM, stats.durationS)
    override fun insertPart(from: Tour, name: String, segments: List<List<TrackPoint>>, stats: TourStats, createdMs: Long) =
        db.insertTour(TourKind.RECORDED, name, from.activity, from.sac, stats.distanceM, stats.ascentM, stats.descentM,
            stats.durationS, segments, createdMs = createdMs)
}
