package com.onpath.app.routing

import com.onpath.app.data.AnchorsJson
import com.onpath.app.debug.Problems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DB row format of the anchors (docs/design/detour-stitch.md §3): profile and a hash of all the row's points travel
 * with the entries, so a row an older build edited (no anchors there) is not routed through a stale anchor.
 */
class AnchorsRowFormatTest {
    private fun g(lat: Double, lon: Double = 11.0) = GeoPoint(lat, lon)
    private val pts = listOf(g(46.1), g(46.3), g(46.5), g(46.7))
    private val anchors = mapOf(1 to Anchors(listOf(g(46.2)), listOf(g(46.4))), 2 to Anchors(after = listOf(g(46.6))))
    private val none = emptyMap<Int, Anchors>()
    private val json = AnchorsJson.writeRow(pts, anchors, "HIKE", 3)!!

    @Test fun roundTripAndShape() {
        assertEquals(anchors, AnchorsJson.readRow(json, pts, "HIKE", 3))
        assertTrue(json.startsWith("{\"activity\":\"HIKE\",\"sac\":3,\"h\":\""))
        assertNull(AnchorsJson.writeRow(pts, none, "HIKE", 3))
        assertNull(AnchorsJson.writeRow(pts, anchors, null, 3))
        assertEquals(none, AnchorsJson.readRow(null, pts, "HIKE", 3))
    }

    @Test fun anotherProfileReadsNone() {
        Problems.clear()
        assertEquals(none, AnchorsJson.readRow(json, pts, "HIKE", 2))
        assertEquals(none, AnchorsJson.readRow(json, pts, "MTB", 3))
        assertEquals(none, AnchorsJson.readRow(json, pts, null, 3))
        assertTrue(Problems.snapshot().any { it.area == "db" })
    }

    @Test fun aBikeRowIgnoresTheTLevel() {
        val bike = AnchorsJson.writeRow(pts, anchors, "MTB", 3)!!
        assertEquals(anchors, AnchorsJson.readRow(bike, pts, "MTB", 1))
        assertEquals(none, AnchorsJson.readRow(bike, pts, "HIKE", 0))
    }

    @Test fun anUnknownActivityComparesByName() {
        val odd = AnchorsJson.writeRow(pts, anchors, "SKI", 2)!!
        assertEquals(anchors, AnchorsJson.readRow(odd, pts, "SKI", 2))
        assertEquals(none, AnchorsJson.readRow(odd, pts, "HIKE", 2))
        assertEquals(none, AnchorsJson.readRow(odd, pts, "SKI", 3))
    }

    @Test fun aNeighbourChangedReadsNone() {
        // the anchored points 1 and 2 are untouched in each case; only another point changed
        assertEquals(none, AnchorsJson.readRow(json, listOf(pts[0], pts[1], pts[2]), "HIKE", 3)) // last deleted
        assertEquals(none, AnchorsJson.readRow(json, listOf(g(46.11), pts[1], pts[2], pts[3]), "HIKE", 3)) // moved
        assertEquals(none, AnchorsJson.readRow(json, pts + g(46.9), "HIKE", 3)) // added elsewhere
        assertEquals(none, AnchorsJson.readRow(json, listOf(pts[0], pts[1], g(46.55), pts[2], pts[3]), "HIKE", 3))
    }

    @Test fun theOldArrayIsStillRead() {
        assertEquals(anchors, AnchorsJson.readRow(AnchorsJson.write(pts, anchors), pts, "MTB", 0))
    }

    @Test fun garbageIsNoneAndReported() {
        for (bad in listOf("{not json", "{\"activity\":\"HIKE\"}", "{\"activity\":\"HIKE\",\"sac\":3,\"h\":\"x\"}", "[1,2")) {
            Problems.clear()
            assertEquals(bad, none, AnchorsJson.readRow(bad, pts, "HIKE", 3))
            assertNotNull(bad, Problems.snapshot().firstOrNull { it.area == "db" })
        }
    }

    @Test fun theHashIsStable() {
        // pinned: the planner and every app version must agree on it
        assertEquals(AnchorsJson.hash(pts), AnchorsJson.hash(pts.map { g(it.lat + 1e-8) }))
        assertEquals(16, AnchorsJson.hash(pts).length)
    }
}
