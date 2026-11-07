package com.onpath.app.domain.tour

import com.onpath.app.data.TourPlan
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-11-07: a recorded tour keeps a frozen copy of the plan it followed – from Gemacht he gets back to it to ride
 * it again. The last version counts (replanned during the tour → the plan at the end); "Nur aufzeichnen" has none.
 */
class TourPlanCopyTest {
    private val stored = HashMap<Long, TourPlan>()
    private var writes = 0
    private var failing = false
    private val backups = ArrayList<Long>()
    private val copy = TourPlanCopy(
        write = { id, p -> writes++; if (failing) false else { stored[id] = p; true } },
        finalChanged = { backups += it },
    )

    private fun plan(vararg pts: Pair<Double, Double>, activity: String = "HIKE", sac: Int = 2) = TourPlan(
        name = "Waalweg", activity = activity, sac = sac,
        waypoints = pts.mapIndexed { i, (lat, lon) -> GpxWaypoint(lat, lon, if (i == 0) "Algund" else null) },
        pinned = setOf(1), line = pts.map { (lat, lon) -> TrackPoint(lat, lon, 400.0) }, distanceM = 4200.0)

    private val a = plan(46.67 to 11.13, 46.68 to 11.15)
    private val b = plan(46.67 to 11.13, 46.68 to 11.15, 46.69 to 11.17)

    @Test fun theTourStartedOnAPlanGetsItsCopy() {
        copy.followed(a)               // Los geht's: the plan is followed before the service has the tour
        assertTrue(stored.isEmpty())
        copy.tour(7L)                  // the recording started
        assertEquals(a, stored[7L])
    }

    @Test fun replannedDuringTheTourTheLastVersionCounts() {
        copy.followed(a); copy.tour(7L)
        copy.followed(b)               // a point added on the way
        copy.followed(b.copy(activity = "MTB", sac = 3)) // activity switched
        copy.tour(null)                // End tour
        assertEquals(b.copy(activity = "MTB", sac = 3), stored[7L])
    }

    @Test fun theSameVersionIsWrittenOnce() {
        copy.followed(a); copy.tour(7L)
        copy.followed(a); copy.followed(a)
        assertEquals(1, writes)
    }

    @Test fun nurAufzeichnenHasNoPlan() {
        copy.notFollowed()             // ☰ → Nur aufzeichnen clears what is followed
        copy.tour(8L)
        copy.tour(null)
        assertNull(stored[8L])
        assertEquals(0, writes)
    }

    @Test fun aTrackFollowedLaterKeepsThePlansLastVersion() {
        copy.followed(a); copy.tour(7L)
        copy.notFollowed()             // a GPX track followed / the plan cleared during the tour
        copy.tour(null)
        assertEquals(a, stored[7L])
    }

    @Test fun aPlanLoadedDuringAFreeRecordingIsCopied() {
        copy.tour(9L)                  // started as a free recording
        copy.followed(b)               // a saved route tapped in My routes during the tour
        assertEquals(b, stored[9L])
    }

    @Test fun theNextTourDoesNotInheritThePlanOfTheLastOne() {
        copy.followed(a); copy.tour(7L); copy.tour(null)
        copy.tour(8L)                  // started without following anything
        assertNull(stored[8L])
    }

    @Test fun aFailedWriteIsTriedAgainAndAtTheEnd() {
        failing = true
        copy.followed(a); copy.tour(7L)
        assertNull(stored[7L])
        failing = false
        copy.tour(null)                // the end writes the last version once more
        assertEquals(a, stored[7L])
        assertEquals("the backup file is written again with the plan", listOf(7L), backups)
    }

    @Test fun anEndAfterGoodWritesNeedsNoSecondBackup() {
        copy.followed(a); copy.tour(7L); copy.tour(null)
        assertTrue(backups.isEmpty())
    }

    @Test fun lessThanTwoPointsIsNoPlan() {
        copy.tour(7L)
        copy.followed(plan(46.67 to 11.13))
        assertNull(stored[7L])
    }

    /** The plan can be deleted or changed later: the copy is a value, nothing refers back to the planned route. */
    @Test fun theCopyRoundTripsAsAValue() {
        val j = b.copy(sourceUuid = "u-route", updatedMs = 5L).toJson()
        val back = TourPlan.fromJson(org.json.JSONObject(j.toString()))
        assertEquals(b.copy(sourceUuid = "u-route", updatedMs = 5L), back)
        assertEquals("Algund", back!!.waypoints[0].name)
        assertNull("a broken record is no plan, never a crash", TourPlan.fromJson(org.json.JSONObject("{\"waypoints\":\"x\"}")))
    }

    @Test fun theLineKeepsItsPointsCompactly() {
        val line = listOf(TrackPoint(46.123456789, 11.987654321, 512.345), TrackPoint(46.2, 11.3, null))
        val back = TourPlan.lineFromJson(TourPlan.lineToJson(line))
        assertEquals(46.123457, back[0].lat, 1e-9)
        assertEquals(11.987654, back[0].lon, 1e-9)
        assertEquals(512.3, back[0].ele!!, 1e-9)
        assertNull(back[1].ele)
    }
}
