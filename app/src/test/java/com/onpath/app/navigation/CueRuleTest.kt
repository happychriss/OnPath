package com.onpath.app.navigation

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import com.onpath.app.routing.JunctionShape
import com.onpath.app.routing.JunctionShape.Branch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bike vibrates only where one could go wrong (owner 2026-10-30). Shapes as BRouter gives them: the route's angle at
 * the node, the road class it comes on / goes on (26 primary, 22 tertiary, 20 unclassified, 6 residential / cycleway,
 * 4 path / track), the other ways. The "Hilden" cases are hints of a routed town line Hilden → Haan / Schloss Benrath
 * (tools/routing/jvm-test/BikeCuesTest.kt), rounded.
 */
class CueRuleTest {
    private fun shape(angle: Float, inPrio: Int, outPrio: Int, vararg others: Pair<Float, Int>) =
        JunctionShape(angle, inPrio, outPrio, others.map { Branch(it.first, it.second) })

    private fun cue(c: JunctionCue, s: JunctionShape?) = CueRule.needed(c, s)

    @Test fun straightOnThroughACrossingIsQuiet() {
        // BRouter still names it (a slight bend of the street at the crossing): nothing to choose
        assertFalse(cue(JunctionCue.SLIGHT_RIGHT, shape(5f, 6, 6, -88f to 6, 92f to 6)))
        // Hilden 6898 m: "keep left" straight on, the other street 43° off
        assertFalse(cue(JunctionCue.KEEP_LEFT, shape(-4f, 6, 6, -43f to 6)))
        // the street bends 30° right, the only other way 67° left (Hilden 6772 m)
        assertFalse(cue(JunctionCue.SLIGHT_RIGHT, shape(30f, 6, 6, -67f to 6)))
    }

    @Test fun tJunctionVibrates() {
        assertTrue(cue(JunctionCue.LEFT, shape(-90f, 6, 6, 90f to 6)))
        assertTrue(cue(JunctionCue.RIGHT, shape(88f, 6, 6, -92f to 6, 0f to 4))) // and a footpath straight on
    }

    @Test fun forkWithSimilarWaysVibrates() {
        // a Y: 20° left, the other way 25° right
        assertTrue(cue(JunctionCue.KEEP_LEFT, shape(-20f, 6, 6, 25f to 6)))
        assertTrue(cue(JunctionCue.KEEP_RIGHT, shape(20f, 6, 6, -25f to 6)))
        // Hilden 572 m: 19° left, others 12° and 37° right
        assertTrue(cue(JunctionCue.SLIGHT_LEFT, shape(-19f, 6, 6, 37f to 6, 12f to 6)))
        // Hilden 4316 m: 27° left vs 56° right – not clearly the straighter one
        assertTrue(cue(JunctionCue.SLIGHT_LEFT, shape(-27f, 6, 6, 56f to 6)))
    }

    @Test fun turnWhereAnotherWayGoesStraightOnVibrates() {
        assertTrue(cue(JunctionCue.LEFT, shape(-85f, 6, 6, 1f to 6))) // Hilden 5151 m
        assertTrue(cue(JunctionCue.SLIGHT_RIGHT, shape(51f, 6, 6, -14f to 6, -125f to 6))) // Hilden 3665 m
    }

    @Test fun bendOfTheMainRoadWithASideStreetStraightOnIsQuiet() {
        // an unclassified road bends 74° left, a residential street goes straight on (Hilden 2994 m)
        assertFalse(cue(JunctionCue.LEFT, shape(-74f, 20, 20, 8f to 6)))
        assertFalse(cue(JunctionCue.SLIGHT_LEFT, shape(-45f, 22, 22, 3f to 6, 90f to 4)))
        // a right-angle turn of the main road: the side street straight on is too tempting – vibrate
        assertTrue(cue(JunctionCue.LEFT, shape(-90f, 22, 22, 3f to 6)))
        // a residential street bends, a footpath goes on (Hilden 1982 m)
        assertFalse(cue(JunctionCue.SLIGHT_RIGHT, shape(57f, 6, 6, -33f to 4)))
    }

    @Test fun leavingOrJoiningTheBiggerRoadVibrates() {
        // off the main road into a side street, the main road goes straight on (Hilden 2079 m)
        assertTrue(cue(JunctionCue.LEFT, shape(-72f, 22, 6, -2f to 22, 108f to 6)))
        // a side street joins a bigger road at 60°, the side street itself goes on straight: not "along the street"
        assertTrue(cue(JunctionCue.LEFT, shape(-60f, 6, 20, -4f to 6)))
    }

    @Test fun noOtherWayIsQuiet() {
        // the only other ways are a oneway against us / no access (left out when read): the road simply bends
        assertFalse(cue(JunctionCue.SLIGHT_RIGHT, shape(39f, 20, 20)))
        assertFalse(cue(JunctionCue.LEFT, shape(-90f, 6, 6)))
    }

    @Test fun roundaboutsAndUTurnsAlwaysVibrate() {
        assertTrue(cue(JunctionCue.ROUNDABOUT, shape(-147f, 6, 6)))
        assertTrue(cue(JunctionCue.U_TURN_LEFT, shape(-175f, 6, 6)))
        assertTrue(cue(JunctionCue.U_TURN_RIGHT, null))
    }

    @Test fun withoutShapeAsBefore() {
        assertTrue(cue(JunctionCue.LEFT, null))
        assertTrue(cue(JunctionCue.KEEP_RIGHT, null))
        assertFalse(cue(JunctionCue.SLIGHT_LEFT, null))
        assertFalse(cue(JunctionCue.SLIGHT_RIGHT, null))
    }

    // ---- reading BRouter's rows (btools.router.OnPathHints) ----

    @Test fun rowWithWaysBecomesAShape() {
        // {index, cmd, angle, exit, goodAngle, oldPrio, goodPrio, n, (angle, prio, wrongWay, cost) × n}
        val row = doubleArrayOf(2.0, 2.0, -95.0, 0.0, -89.4, 20.0, 6.0, 3.0,
            1.4, 20.0, 0.0, 1.0, // the main road straight on
            90.0, 6.0, 1.0, 1.0, // a oneway against us: left out
            45.0, 30.0, 0.0, 10_000.0) // no access: left out
        val pts = MutableList(5) { GeoPoint(46.0 + it * 0.001, 11.0) }
        JunctionCue.apply(pts, arrayOf(row))
        assertEquals(JunctionCue.LEFT, pts[2].turn)
        assertEquals(-95f, pts[2].turnAngle, 0.01f)
        assertEquals(JunctionShape(-89.4f, 20, 6, listOf(Branch(1.4f, 20))), pts[2].shape)
    }

    @Test fun oldRowsHaveNoShape() {
        assertNull(JunctionShape.ofRow(doubleArrayOf(2.0, 2.0, -95.0, 0.0)))
        assertNull("too short for its ways", JunctionShape.ofRow(doubleArrayOf(2.0, 2.0, -95.0, 0.0, -89.0, 6.0, 6.0, 2.0, 1.0, 6.0)))
        val pts = MutableList(3) { GeoPoint(46.0 + it * 0.001, 11.0) }
        JunctionCue.apply(pts, arrayOf(doubleArrayOf(1.0, 5.0, 90.0)))
        assertEquals(JunctionCue.RIGHT, pts[1].turn); assertNull(pts[1].shape)
    }

    @Test fun shapeTextRoundTrip() {
        val s = JunctionShape(-74f, 20, 20, listOf(Branch(8f, 6), Branch(-120f, 4)))
        assertEquals("-74,20,20;8,6;-120,4", s.encode())
        assertEquals(s, JunctionShape.decode(s.encode()))
        assertEquals(JunctionShape(39f, 20, 20, emptyList()), JunctionShape.decode("39,20,20"))
        assertNull(JunctionShape.decode("nonsense"))
        assertNull(JunctionShape.decode(""))
        assertNull(JunctionShape.decode(null))
    }
}
