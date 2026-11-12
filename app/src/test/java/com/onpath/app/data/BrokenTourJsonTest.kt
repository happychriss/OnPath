package com.onpath.app.data

import com.onpath.app.debug.Problems
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.rides.RideChoice
import com.onpath.app.rides.RideRecord
import com.onpath.app.rides.TourStats
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * Safety net for the JSON columns of the tour database and of a backup record, read whenever a tour is opened:
 * waypoints, pinned stops, the followed plan with its line, the rides. Garbage, a cut text, the wrong type or a
 * huge value never throw – the tour is shown without that part, and Problems says so.
 */
class BrokenTourJsonTest {
    @Before fun reset() { Problems.init(Files.createTempDirectory("tour-json").toFile()); Problems.clear() }

    private val garbage = listOf(
        "", " ", "\u0000ÿ{{", "[[46.5", "{\"a\":1}", "[1,2]", "[\"a\"]", "[null]", "[[46.5]]", "[[46.5,\"x\"]]", "[[46.5,11.2,\"x\"]]",
        "[{\"lat\":\"x\",\"lon\":1}]", "[{\"lon\":1}]", "[{\"lat\":1,\"lon\":2,\"ele\":\"e\"}]", "[1.5]", "5", "true",
    )

    @Test fun lineWaypointsAndPinnedNeverThrow() {
        for (s in garbage) {
            TourPlan.lineFromJson(s); TourDb.jsonToWaypoints(s); TourDb.jsonToPinned(s) // only: no throw
        }
        assertEquals(emptyList<TrackPoint>(), TourPlan.lineFromJson("[[46.5,11.2,\"x\"]]"))
        assertEquals(emptyList<GpxWaypoint>(), TourDb.jsonToWaypoints("[{\"lat\":\"x\",\"lon\":1}]"))
        assertEquals(emptySet<Int>(), TourDb.jsonToPinned("[\"a\"]"))
        assertTrue(Problems.snapshot().any { it.area == "db" && it.message.contains("unreadable") })
    }

    @Test fun hugeValuesStillLoad() {
        val line = buildString { append('['); repeat(200_000) { append("[46.5,11.2,1200.0],") }; append("[46.6,11.3]]") }
        assertEquals(200_001, TourPlan.lineFromJson(line).size)
        val pinned = buildString { append('['); repeat(100_000) { append("$it,") }; append("1]") }
        assertEquals(100_000, TourDb.jsonToPinned(pinned).size)
    }

    @Test fun aPlanRecordOfTheWrongTypeIsNoPlanOrAPlanWithoutThatPart() {
        for (s in listOf("{\"name\":5}", "{\"waypoints\":5}", "{\"waypoints\":[1]}", "{\"source\":[]}", "[1]", "\"x\"")) {
            val o = try { JSONObject(s) } catch (_: Exception) { continue } // expected: not an object at all – the column is unreadable before this
            assertNull(s, TourPlan.fromJson(o))
        }
        // two good points and the rest of the wrong type: the plan is kept without the broken parts
        val o = JSONObject("""{"waypoints":[{"lat":46.5,"lon":11.2},{"lat":46.6,"lon":11.3}],"line":"x","pinned":"y","sac":"x","distance":"d","updated":"u","activity":5}""")
        val plan = TourPlan.fromJson(o)!!
        assertEquals(2, plan.waypoints.size)
        assertTrue(plan.line.isEmpty()); assertTrue(plan.pinned.isEmpty())
        assertEquals(2, plan.sac); assertEquals(0.0, plan.distanceM, 0.0); assertEquals(0L, plan.updatedMs)
    }

    @Test fun ridesOfTheWrongTypeShowTheTourAsRecorded() {
        val bad = listOf(
            "\u0000garbage", "[1]", "{\"rides\":\"x\"}", "{\"rides\":[{\"from\":\"a\"}],\"choice\":\"NOPE\",\"recorded\":{}}",
            "{\"rides\":[],\"choice\":\"AS_RECORDED\",\"recorded\":{\"distance\":\"x\"}}",
            "{\"rides\":[{\"from\":1,\"to\":2,\"kind\":\"BOAT\"}],\"choice\":\"AS_RECORDED\",\"recorded\":{\"distance\":1,\"ascent\":1,\"descent\":1,\"duration\":1}}",
            "{\"rides\":[],\"choice\":\"AS_RECORDED\",\"recorded\":{\"distance\":1,\"ascent\":1,\"descent\":1,\"duration\":1},\"end\":\"x\"}",
        )
        for (s in bad) assertNull(s, RideRecord.fromJson(s))
        assertNull(RideRecord.fromJson(null)); assertNull(RideRecord.fromJson(""))
        val r = RideRecord(emptyList(), RideChoice.AS_RECORDED, TourStats(1.0, 2.0, 3.0, 4.0), 5L)
        assertNotNull(RideRecord.fromJson(r.toJson().toString()))
        assertTrue(Problems.snapshot().any { it.area == "rides" })
    }
}
