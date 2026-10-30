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

    // ---- leaving the way one is on for a smaller one (owner 2026-10-30: on foot first, then the bike) ----
    // Bike kinds of way (CueRule.bikeKind): roads (unclassified and up, 20–30) > streets and bike ways (residential,
    // service, cycleway, designated, paved track: touring / trekking 6, mtb.brf 14 / 16 / 6) > tracks and paths (≤ 4).

    @Test fun bikeLeavingTheStreetForATrackVibratesEvenStraightOn() {
        // the street bends 50° right, the route goes straight on onto a field track
        assertTrue(cue(JunctionCue.SLIGHT_LEFT, shape(-3f, 6, 4, 50f to 6)))
        // the same off an unclassified road
        assertTrue(cue(JunctionCue.SLIGHT_RIGHT, shape(2f, 20, 4, -45f to 20)))
        // Hilden → Schloss Benrath (MTB) 5913 m: 20° left onto a track, the cycleway goes on 82° right
        assertTrue(cue(JunctionCue.SLIGHT_LEFT, shape(-20f, 6, 4, 82f to 6)))
    }

    @Test fun bikeCyclewayToPathStraightOnVibrates() {
        // touring / trekking: cycleway 6 → path 4, the cycleway bends 55° left
        assertTrue(cue(JunctionCue.SLIGHT_RIGHT, shape(4f, 6, 4, -55f to 6)))
        // mtb.brf: cycleway 6 → path 2
        assertTrue(cue(JunctionCue.SLIGHT_RIGHT, shape(4f, 6, 2, -55f to 6)))
    }

    @Test fun bikeStreetGoesOnStraightTrackBranchesOffIsQuiet() {
        // the street goes on straight, a track branches off: no change
        assertFalse(cue(JunctionCue.SLIGHT_LEFT, shape(-2f, 6, 6, 60f to 4)))
        assertFalse(cue(JunctionCue.SLIGHT_RIGHT, shape(5f, 20, 20, -40f to 4, 95f to 6)))
        // the street ends in two tracks: no bigger way goes on – the plain rule (clearly the straighter one: quiet)
        assertFalse(cue(JunctionCue.SLIGHT_LEFT, shape(-5f, 6, 4, 60f to 4)))
    }

    @Test fun bikeKindsOfWay() {
        // off the main road straight on into a side street while the main road bends: a smaller kind – vibrate
        assertTrue(cue(JunctionCue.SLIGHT_LEFT, shape(3f, 22, 6, -50f to 22)))
        // residential → service road (mtb.brf 16 → 14) straight on: both streets – the plain rule
        assertFalse(cue(JunctionCue.SLIGHT_LEFT, shape(-3f, 16, 14, 50f to 16)))
        // track → path (mtb.brf 4 → 2) straight on: touring / trekking cannot tell them apart (both 4) – one kind
        assertFalse(cue(JunctionCue.SLIGHT_LEFT, shape(-3f, 4, 2, 50f to 4)))
        // primary → tertiary straight on: both roads
        assertFalse(cue(JunctionCue.SLIGHT_LEFT, shape(-3f, 26, 22, 50f to 26)))
        assertEquals(listOf(0, 1, 1, 2, 2, 2, 3, 3, 3), listOf(0, 2, 4, 6, 14, 16, 20, 22, 30).map { CueRule.bikeKind(it) })
    }

    // ---- on foot (owner 2026-10-30): the same rule with hiking-mountain.brf's classes ----
    // 2 gravel / grass path, 3 path, 4 path grade2, 6 paved path, 8 track, 10 paved track, 12 / 14 service, 16 steps,
    // 18 residential, 24 secondary. "Meran" / "Algund" cases = hints of the routed hikes in tools/routing/jvm-test/JunctionsTest.kt.

    private fun hike(c: JunctionCue, s: JunctionShape?) = CueRule.needed(c, s, hike = true)

    @Test fun hikeTJunctionOnAMountainPathVibrates() {
        // off the path to the left, the path goes on straight
        assertTrue(hike(JunctionCue.LEFT, shape(-88f, 3, 3, 2f to 3)))
        // the end of a path at a crossing path: left or right
        assertTrue(hike(JunctionCue.LEFT, shape(-90f, 3, 3, 90f to 3)))
        // Meran 2000 → Ifinger 1245 m: 70° left, the other path 11° right
        assertTrue(hike(JunctionCue.LEFT, shape(-70f, 3, 3, 11f to 3)))
        // Algund → Vellau 199 m: off the track onto a path, the track goes on straight
        assertTrue(hike(JunctionCue.RIGHT, shape(73f, 8, 3, -128f to 3, 1f to 8)))
    }

    @Test fun hikeForkOfTwoPathsVibratesWhateverTheirSurface() {
        assertTrue(hike(JunctionCue.KEEP_LEFT, shape(-20f, 3, 3, 30f to 3)))
        // one of them tagged gravel (class 2): still two paths – the raw classes would call it "along the path"
        assertTrue(hike(JunctionCue.KEEP_LEFT, shape(-20f, 3, 3, 30f to 2)))
        assertFalse(CueRule.needed(JunctionCue.KEEP_LEFT, shape(-20f, 3, 3, 30f to 2)))
        assertTrue(hike(JunctionCue.KEEP_RIGHT, shape(25f, 4, 2, -20f to 6)))
        // Meran 2000 → Ifinger 862 m: 39° right, the other path 7° left
        assertTrue(hike(JunctionCue.SLIGHT_RIGHT, shape(39f, 3, 3, -7f to 3)))
    }

    @Test fun hikeAlongTheTrailIsQuiet() {
        // the path goes on (12°), a path branches off at 70°: clearly the straightest way
        assertFalse(hike(JunctionCue.SLIGHT_RIGHT, shape(12f, 3, 3, -70f to 2)))
        // a forest track bends 40°, a path goes straight on: along the track
        assertFalse(hike(JunctionCue.SLIGHT_RIGHT, shape(40f, 8, 8, -3f to 3)))
        // paved and unpaved stretches of the same track are one class
        assertFalse(hike(JunctionCue.SLIGHT_LEFT, shape(-50f, 10, 8, 5f to 3)))
        // Meran Obermais 42 m / 173 m: the service road bends 48° / 41°, a path branches off
        assertFalse(hike(JunctionCue.SLIGHT_RIGHT, shape(48f, 14, 14, -103f to 3)))
        assertFalse(hike(JunctionCue.SLIGHT_LEFT, shape(-41f, 12, 12, 49f to 3)))
        // a right-angle bend of the track with a path straight on still vibrates
        assertTrue(hike(JunctionCue.LEFT, shape(-90f, 8, 8, 2f to 3)))
    }

    @Test fun hikeLeavingTheTrackForAPathVibratesEvenStraightOn() {
        // the track bends 50° right, the route goes straight on into a path: the classic missed turn on foot
        assertTrue(hike(JunctionCue.SLIGHT_LEFT, shape(-5f, 8, 3, 50f to 8)))
        // Algund → Vellau 700 m: off the residential street into a service road, the street goes on
        assertTrue(hike(JunctionCue.SLIGHT_LEFT, shape(-18f, 18, 14, 42f to 18)))
        // the track ends in two paths: no bigger way goes on – the plain rule (clearly the straighter one: quiet)
        assertFalse(hike(JunctionCue.SLIGHT_LEFT, shape(-5f, 8, 3, 60f to 3)))
    }

    @Test fun hikeClasses() {
        assertEquals(listOf(0, 1, 1, 1, 1, 2, 2, 3, 3, 4, 18, 24), listOf(0, 2, 3, 4, 6, 8, 10, 12, 14, 16, 18, 24).map { CueRule.hikeClass(it) })
        // roundabouts / U-turns / no shape as on the bike
        assertTrue(hike(JunctionCue.ROUNDABOUT, shape(-147f, 18, 18)))
        assertFalse(hike(JunctionCue.SLIGHT_LEFT, null))
        assertFalse(hike(JunctionCue.LEFT, shape(-90f, 3, 3)))
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
