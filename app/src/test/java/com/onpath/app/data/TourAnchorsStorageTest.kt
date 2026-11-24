package com.onpath.app.data

import com.onpath.app.backup.BackupCodec
import com.onpath.app.backup.BackupMeta
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.plansync.RouteItems
import com.onpath.app.routing.Anchors
import com.onpath.app.routing.GeoPoint
import com.onpath.app.testkit.JdbcTours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plan's hidden via points (docs/design/detour-stitch.md §3) through the stores of schema 10: save, load, overwrite,
 * the plan copy of a recorded tour, the backup record and the sync item. The app only reads anchors; a damaged text reads as
 * none and is reported, never a crash and never a lost route.
 */
class TourAnchorsStorageTest {
    private val stops = listOf(GpxWaypoint(46.60, 11.10, "A"), GpxWaypoint(46.62, 11.14, null), GpxWaypoint(46.66, 11.20, "C"))
    private val line = listOf(listOf(TrackPoint(46.60, 11.10), TrackPoint(46.62, 11.14), TrackPoint(46.66, 11.20)))
    private val anchors = mapOf(1 to Anchors(listOf(GeoPoint(46.611, 11.121)), listOf(GeoPoint(46.631234, 11.151234))))

    private fun JdbcTours.planned(anchors: Map<Int, Anchors> = this@TourAnchorsStorageTest.anchors) =
        insertTour(TourKind.PLANNED, "Plan", "HIKE", 2, line, stops, anchors = anchors)

    @Test fun savedAnchorsComeBackWithTheRoute() {
        JdbcTours().use { t ->
            val id = t.planned()
            assertEquals(anchors, t.getTour(id)!!.anchors)
            // overwritten with other anchors, then with none: written as they are, nothing "kept"
            val other = mapOf(0 to Anchors(after = listOf(GeoPoint(46.601, 11.101))))
            assertTrue(t.replacePlanned(id, "HIKE", 2, 1.0, 0.0, 0.0, 0.0, line, stops, anchors = other))
            assertEquals(other, t.getTour(id)!!.anchors)
            assertTrue(t.replacePlanned(id, "HIKE", 2, 1.0, 0.0, 0.0, 0.0, line, stops))
            assertEquals(emptyMap<Int, Anchors>(), t.getTour(id)!!.anchors)
            assertEquals(1, t.exec("UPDATE tours SET name=name WHERE anchors IS NULL AND id=?", id))
        }
    }

    @Test fun aTourWithoutAnchorsKeepsTheColumnNull() {
        JdbcTours().use { t ->
            val id = t.planned(emptyMap())
            assertEquals(1, t.exec("UPDATE tours SET name=name WHERE anchors IS NULL AND id=?", id))
            assertEquals(emptyMap<Int, Anchors>(), t.getTour(id)!!.anchors)
        }
    }

    @Test fun theFollowedPlansCopyKeepsAnchors() {
        JdbcTours().use { t ->
            val tour = t.insertTour(TourKind.RECORDED, "Tour", "HIKE", 2)
            t.setTourPlan(tour, TourPlan("Plan", "HIKE", 2, stops, line = line[0], anchors = anchors))
            assertEquals(anchors, t.getTourPlan(tour)!!.anchors)
            t.setTourPlan(tour, TourPlan("Plan", "HIKE", 2, stops))
            assertEquals(emptyMap<Int, Anchors>(), t.getTourPlan(tour)!!.anchors)
        }
    }

    @Test fun backupAndSyncCarryThemOn() {
        JdbcTours().use { t ->
            val tour = t.getTour(t.planned())!!
            val meta = BackupCodec.fromJson(BackupCodec.toJson(BackupMeta.of(tour, line, 5L)))!!
            assertEquals(anchors, meta.anchors)
            // the restore stores them as they came
            val restored = t.insertTour(TourKind.PLANNED, meta.name, meta.activity, meta.sac, line, meta.waypoints, anchors = meta.anchors)
            assertEquals(anchors, t.getTour(restored)!!.anchors)
            // the sync item and back
            val code = RouteItems.planOf(t.getTour(restored)!!)!!
            val item = com.onpath.app.sync.SyncItem(id = "i", updatedAt = 1L, device = "d", kind = com.onpath.app.sync.SyncItem.PLAN, title = "T", plan = code)
            assertEquals(anchors, RouteItems.planContent(item)!!.anchors)
        }
    }

    /** An older build (no anchors) edits the row and leaves the column: the stored profile and the points hash catch it. */
    @Test fun aRowEditedByAnOlderBuildReadsAsNone() {
        JdbcTours().use { t ->
            val id = t.planned()
            val plan = t.insertTour(TourKind.RECORDED, "Tour", "HIKE", 2)
            t.setTourPlan(plan, TourPlan("Plan", "HIKE", 2, stops, anchors = anchors))
            // the T level changed
            t.exec("UPDATE tours SET sac=3 WHERE id=?", id)
            t.exec("UPDATE tour_plan SET sac=3 WHERE tour_id=?", plan)
            assertEquals(emptyMap<Int, Anchors>(), t.getTour(id)!!.anchors)
            assertEquals(emptyMap<Int, Anchors>(), t.getTourPlan(plan)!!.anchors)
            t.exec("UPDATE tours SET sac=2 WHERE id=?", id)
            assertEquals(anchors, t.getTour(id)!!.anchors)
            // a neighbour of the anchored point 1 (point 2) was moved
            t.exec("UPDATE tours SET waypoints=? WHERE id=?", TourJson.waypointsToJson(stops.take(2) + GpxWaypoint(46.67, 11.21, "C")), id)
            assertEquals(emptyMap<Int, Anchors>(), t.getTour(id)!!.anchors)
        }
    }

    @Test fun aBrokenAnchorsTextReadsAsNoneAndIsReported() {
        val dir = java.nio.file.Files.createTempDirectory("anch").toFile()
        Problems.init(dir); Problems.clear()
        JdbcTours().use { t ->
            val id = t.planned()
            val plan = t.insertTour(TourKind.RECORDED, "Tour", "HIKE", 2)
            t.setTourPlan(plan, TourPlan("Plan", "HIKE", 2, stops, anchors = anchors))
            for (bad in listOf("{not json", "[{\"i\":\"x\"}]", "[1,2", "\u0000", "{}")) {
                t.exec("UPDATE tours SET anchors=? WHERE id=?", bad, id)
                t.exec("UPDATE tour_plan SET anchors=? WHERE tour_id=?", bad, plan)
                val tour = t.getTour(id)!!
                assertEquals("the route itself loads", stops.size, tour.waypoints.size)
                assertEquals(emptyMap<Int, Anchors>(), tour.anchors)
                assertEquals(emptyMap<Int, Anchors>(), t.getTourPlan(plan)!!.anchors)
            }
            assertTrue(t.listTours().size == 2)
        }
        assertTrue(Problems.snapshot().any { it.area == "db" && it.message.contains("detour points") })
    }

    /** The plan copy gets another profile when the activity switches during the tour: its detour points lie on the old one (TourDb cannot run on the JVM: the text). */
    @Test fun switchingTheActivityOfThePlanCopyClearsItsAnchors() {
        val body = java.io.File("src/main/java/com/onpath/app/data/TourDb.kt").readText().substringAfter("fun setTourPlanActivity(").substringBefore("// ---- recording")
        assertTrue(body, body.contains("putNull(\"anchors\")") && body.contains("put(\"activity\", activity)"))
    }
}
