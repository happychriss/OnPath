package com.onpath.app.data

import com.onpath.app.cells.CellId
import com.onpath.app.navigation.JogRule
import com.onpath.app.navigation.NavLine
import com.onpath.app.recording.DemoPath
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.TurnDetector
import com.onpath.app.routing.WaypointInsertion
import com.onpath.app.routing.WaypointSnap
import com.onpath.app.sync.TrackCode
import com.onpath.app.sync.TrackPt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden values of the geo math before it became one copy in [Geo] (code audit 2026-11-07, package 2): the outputs of
 * every replaced function for fixed inputs (Hilden). The flat projections used 111 320 / 110 540 / 111 000 m per degree;
 * now they use the sphere of [Geo.distance] (6 371 km, 111 195 m per degree), so distances in a flat frame move by
 * at most ~0.6 % (latitude) / ~0.1 % (longitude). [REL] is that bound; everything discrete (indices, cells, parts, cue
 * flags) must be exactly the same.
 */
class GeoGoldenTest {
    private val REL = 0.0065
    private fun near(expected: Double, actual: Double, rel: Double = REL, abs: Double = 1e-9) =
        assertEquals(expected, actual, kotlin.math.max(abs, kotlin.math.abs(expected) * rel))

    // a walk through Hilden: Fabriciusstraße – Ellerstraße – Feldstraße (≈ 1 km, three bends)
    private val line = listOf(
        GeoPoint(51.16800, 6.93400), GeoPoint(51.16820, 6.93520), GeoPoint(51.16880, 6.93600),
        GeoPoint(51.16950, 6.93610), GeoPoint(51.17010, 6.93700), GeoPoint(51.17020, 6.93850),
        GeoPoint(51.17100, 6.93900), GeoPoint(51.17150, 6.94020), GeoPoint(51.17160, 6.94150),
    )
    private val off = listOf(
        GeoPoint(51.16830, 6.93500), GeoPoint(51.16900, 6.93650), GeoPoint(51.17000, 6.93800),
        GeoPoint(51.17120, 6.94000), GeoPoint(51.16700, 6.93300), GeoPoint(51.17300, 6.94300),
    )

    @Test fun pointToLineDistances() {
        val exp = doubleArrayOf(GOLD_DTL_0, GOLD_DTL_1, GOLD_DTL_2, GOLD_DTL_3, GOLD_DTL_4, GOLD_DTL_5)
        off.forEachIndexed { i, p -> near(exp[i], Geo.distanceToLine(p.lat, p.lon, line)) }
    }

    @Test fun waypointInsertion() {
        val seg = doubleArrayOf(GOLD_SEG_0, GOLD_SEG_1, GOLD_SEG_2, GOLD_SEG_3, GOLD_SEG_4, GOLD_SEG_5)
        off.forEachIndexed { i, p ->
            near(seg[i], Geo.distanceToSegment(p, line[i], line[i + 1]))
            val c = Geo.closestOnSegment(p, line[i], line[i + 1])
            near(GOLD_CLOSE_LAT[i], c.lat, 0.0, 2e-6); near(GOLD_CLOSE_LON[i], c.lon, 0.0, 2e-6)
            val (idx, d) = WaypointInsertion.nearestOnRoute(line, p)!!
            assertEquals(GOLD_NEAR_IDX[i], idx); near(GOLD_NEAR_D[i], d)
        }
    }

    @Test fun snapProjection() {
        off.forEachIndexed { i, p ->
            val q = WaypointSnap.project(p, line)
            near(GOLD_SNAP_LAT[i], q.lat, 0.0, 2e-6); near(GOLD_SNAP_LON[i], q.lon, 0.0, 2e-6)
            near(GOLD_SNAP_D[i], WaypointSnap.distToLine(p, line))
        }
    }

    @Test fun navLineMatchAndParts() {
        val nl = NavLine(line)
        off.forEachIndexed { i, p ->
            val m = nl.match(p)!!
            near(GOLD_MATCH_ALONG[i], m.alongM); near(GOLD_MATCH_D[i], m.distanceM); assertEquals(GOLD_MATCH_SEG[i], m.seg)
            assertEquals(GOLD_PARTS_N[i], nl.parts(p, 60.0).size)
        }
    }

    @Test fun bearingsAndDiffs() {
        for (i in 0 until line.size - 1) near(GOLD_BEARING[i], Geo.bearing(line[i], line[i + 1]), 0.0, 1e-6)
        near(-170.0, Geo.angleDiff(170.0, 0.0), 0.0); near(180.0, Geo.angleDiff(0.0, 180.0), 0.0)
        near(-10.0, Geo.angleDiff(5.0, 355.0), 0.0)
    }

    @Test fun jogRuleFlags() {
        val turns = line.mapIndexed { i, p ->
            when (i) { 2 -> p.copy(turn = JunctionCue.LEFT); 3 -> p.copy(turn = JunctionCue.RIGHT); 5 -> p.copy(turn = JunctionCue.LEFT); else -> p }
        }
        assertEquals(GOLD_JOG_HIKE, JogRule.needed(turns, hike = true).joinToString(","))
        assertEquals(GOLD_JOG_BIKE, JogRule.needed(turns, hike = false).joinToString(","))
    }

    @Test fun cellsPartsAndDemo() {
        assertEquals(GOLD_CELLS, CellId.alongLine(line, corridorM = 3_000.0).joinToString(",") { "${it.x}/${it.y}" })
        assertEquals(GOLD_PARTS, Rd5Parts.forPoint(line[0], 30_000.0).joinToString(",") { "${it.lon}/${it.lat}" })
        val demo = DemoPath.constant(line, 1.5)!!
        off.forEachIndexed { i, p -> near(GOLD_DEMO_T[i], demo.timeNearest(p.lat, p.lon), 0.003, 0.05) }
    }

    @Test fun trackSimplify() {
        val pts = line.map { TrackPt(it.lat, it.lon) }
        assertEquals(GOLD_SIMPLIFY_10, TrackCode.simplify(pts, 10.0).size)
        assertEquals(GOLD_SIMPLIFY_40, TrackCode.simplify(pts, 40.0).size)
    }

    @Test fun routeDataDiffers() {
        val shifted = line.map { GeoPoint(it.lat + 0.0002, it.lon) } // ~22 m north
        assertEquals(false, RoutingDataCheck.differs(line, shifted, 30.0))
        assertEquals(true, RoutingDataCheck.differs(line, shifted, 15.0))
    }

    companion object {
        // recorded from the code before the change (96cd7f0e) – see the class comment
        const val GOLD_DTL_0 = 14.25064646049568
        const val GOLD_DTL_1 = 32.77286220459456
        const val GOLD_DTL_2 = 18.321501299558307
        const val GOLD_DTL_3 = 19.99019733719191
        const val GOLD_DTL_4 = 130.73491946730255
        const val GOLD_DTL_5 = 186.84152159711547
        const val GOLD_SEG_0 = 14.344589043667687
        const val GOLD_SEG_1 = 41.39700403443239
        const val GOLD_SEG_2 = 143.82479570257567
        const val GOLD_SEG_3 = 242.56803936560098
        const val GOLD_SEG_4 = 443.90193686730845
        const val GOLD_SEG_5 = 357.0836416837085
        val GOLD_CLOSE_LAT = doubleArrayOf(51.16817546509624, 51.1688, 51.1695, 51.1701, 51.1701, 51.171)
        val GOLD_CLOSE_LON = doubleArrayOf(6.935052790577442, 6.936, 6.9361, 6.937, 6.937, 6.939)
        val GOLD_NEAR_IDX = intArrayOf(0, 2, 4, 6, 0, 7)
        val GOLD_NEAR_D = doubleArrayOf(14.344589043667687, 32.7747093889945, 18.449342578050334, 20.088148202436667, 131.39509111120276, 187.7469940124934)
        val GOLD_SNAP_LAT = doubleArrayOf(51.16817546509624, 51.169026267416704, 51.17016480358146, 51.171350293884814, 51.168, 51.1716)
        val GOLD_SNAP_LON = doubleArrayOf(6.935052790577442, 6.936032323916672, 6.9379720537218175, 6.9398407053235465, 6.934, 6.9415)
        val GOLD_SNAP_D = doubleArrayOf(14.328473486808905, 32.73787618311428, 18.428613489600878, 20.06556818859249, 131.24706086470232, 187.5369364876039)
        val GOLD_MATCH_ALONG = doubleArrayOf(75.90316119047117, 198.83678209355068, 411.4531656886805, 614.4893320526828, 0.0, 735.7491649840242)
        val GOLD_MATCH_D = doubleArrayOf(14.25064646049568, 32.77286220459456, 18.321501299558307, 19.99019733719191, 130.73491946730255, 186.84152159711545)
        val GOLD_MATCH_SEG = intArrayOf(0, 2, 4, 6, 0, 7)
        val GOLD_PARTS_N = intArrayOf(1, 1, 1, 1, 0, 0)
        val GOLD_BEARING = doubleArrayOf(75.11447930356883, 39.89675873998476, 5.118556184610384, 43.24404652060451, 83.93025718336679, 21.398889014411964, 56.393679952536615, 83.00504844278254)
        val GOLD_DEMO_T = doubleArrayOf(50.635254700577946, 132.53039087814446, 274.2840700674264, 409.57307330358213, 0.0, 490.4994433226828)
        const val GOLD_JOG_HIKE = "true,true,true"
        const val GOLD_JOG_BIKE = "true,true,true"
        const val GOLD_CELLS = "531/342,531/341"
        const val GOLD_PARTS = "6/50,6/51,7/50,7/51"
        const val GOLD_SIMPLIFY_10 = 9
        const val GOLD_SIMPLIFY_40 = 4
    }
}
