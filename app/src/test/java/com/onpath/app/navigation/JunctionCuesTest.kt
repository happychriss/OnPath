package com.onpath.app.navigation

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Junction vibration (owner 2026-10-22): when, how often, which side. */
class JunctionCuesTest {
    /** A straight line north, a point every 10 m (1 m ≈ 0.000009°), junctions at the given metres. */
    private fun line(lengthM: Int, junctions: Map<Int, JunctionCue>): List<GeoPoint> =
        (0..lengthM step 10).map { m -> GeoPoint(46.0 + m * 0.0000089932, 11.0, turn = junctions[m]) }

    /** Walk the line in 5 m steps; returns (metres, cue) for every cue. */
    private fun walk(c: JunctionCues, l: List<GeoPoint>, from: Int = 0, to: Int = l.size * 10, onRoute: (Int) -> Boolean = { true }):
        List<Pair<Int, JunctionCue>> = (from..to step 5).mapNotNull { m -> c.update(l, 1, m.toDouble(), onRoute(m))?.let { m to it } }

    @Test fun oncePerJunctionAboutFortyMetresBefore() {
        val l = line(1000, mapOf(200 to JunctionCue.LEFT, 600 to JunctionCue.RIGHT))
        val cues = walk(JunctionCues(), l)
        assertEquals(listOf(JunctionCue.LEFT, JunctionCue.RIGHT), cues.map { it.second })
        assertTrue("left cue 40 m before: ${cues[0].first}", cues[0].first in 155..165)
        assertTrue("right cue 40 m before: ${cues[1].first}", cues[1].first in 555..565)
    }

    @Test fun bikesGetTheCueEarlier() {
        val l = line(1000, mapOf(500 to JunctionCue.LEFT))
        val cues = walk(JunctionCues(JunctionCues.BIKE_AHEAD_M), l)
        assertTrue(cues.single().first in 415..425)
    }

    @Test fun switchbacksAreFeltAsOne() {
        val l = line(1000, mapOf(300 to JunctionCue.LEFT, 330 to JunctionCue.RIGHT, 360 to JunctionCue.LEFT, 500 to JunctionCue.RIGHT))
        assertEquals(listOf(JunctionCue.LEFT, JunctionCue.RIGHT), walk(JunctionCues(), l).map { it.second })
    }

    @Test fun nothingOffRouteAndNeverLate() {
        val l = line(1000, mapOf(200 to JunctionCue.LEFT, 600 to JunctionCue.RIGHT))
        // off the route around the first junction, back on afterwards: no late cue for it
        val cues = walk(JunctionCues(), l) { m -> m !in 100..260 }
        assertEquals(listOf(JunctionCue.RIGHT), cues.map { it.second })
        // starting after a junction: it is skipped
        assertEquals(listOf(JunctionCue.RIGHT), walk(JunctionCues(), l, from = 300).map { it.second })
        // not matched to the line: nothing
        assertNull(JunctionCues().update(l, 1, null, true))
    }

    @Test fun aNewLineStartsOver() {
        val l = line(1000, mapOf(200 to JunctionCue.LEFT))
        val c = JunctionCues()
        walk(c, l)
        val l2 = line(1000, mapOf(200 to JunctionCue.SHARP_RIGHT))
        assertEquals(JunctionCue.SHARP_RIGHT, (100..200 step 5).firstNotNullOf { c.update(l2, 2, it.toDouble(), true) })
    }

    @Test fun nextAheadForTheLockScreen() {
        val l = line(2000, mapOf(300 to JunctionCue.LEFT, 1800 to JunctionCue.RIGHT))
        val c = JunctionCues()
        val (cue, d) = c.nextAhead(l, 1, 180.0)!!
        assertEquals(JunctionCue.LEFT, cue); assertEquals(120.0, d, 1.0)
        assertNull("beyond 1 km", c.nextAhead(l, 1, 400.0))
    }

    @Test fun patternsLeftOneRightTwo() {
        assertEquals(1, JunctionCues.pattern(JunctionCue.Side.LEFT).size / 2)
        assertEquals(2, JunctionCues.pattern(JunctionCue.Side.RIGHT).size / 2)
    }

    @Test fun junctionsOfALineAreCounted() {
        val l = listOf(GeoPoint(46.0, 11.0), GeoPoint(46.001, 11.0, turn = JunctionCue.LEFT), GeoPoint(46.002, 11.0))
        assertEquals(1, JunctionCues.count(l))
        assertEquals(0, JunctionCues.count(l.map { it.copy(turn = null) }))
    }

    @Test fun brouterCommands() {
        assertEquals(JunctionCue.LEFT, JunctionCue.ofBRouter(2))
        assertEquals(JunctionCue.SHARP_RIGHT, JunctionCue.ofBRouter(7))
        assertEquals(JunctionCue.KEEP_LEFT, JunctionCue.ofBRouter(8))
        assertNull("straight on", JunctionCue.ofBRouter(1))
        assertNull("end", JunctionCue.ofBRouter(100))
        val pts = MutableList(5) { GeoPoint(46.0 + it * 0.001, 11.0) }
        JunctionCue.apply(pts, arrayOf(doubleArrayOf(2.0, 5.0, 90.0), doubleArrayOf(9.0, 2.0, -90.0), doubleArrayOf(3.0, 1.0, 0.0)))
        assertEquals(listOf(null, null, JunctionCue.RIGHT, null, null), pts.map { it.turn })
    }

    @Test fun sessionFileKeepsTheJunctions() {
        val l = line(500, mapOf(100 to JunctionCue.LEFT, 400 to JunctionCue.KEEP_RIGHT))
        val s = NavSession(NavKind.PLAN, "Test", com.onpath.app.routing.Activity.HIKE, 3, l, current = l.drop(5))
        val back = NavSessionStore.fromJson(NavSessionStore.toJson(s))!!
        assertEquals(l.map { it.turn }, back.original.map { it.turn })
        assertEquals(l.drop(5).map { it.turn }, back.current!!.map { it.turn })
    }

    @Test fun lockScreenText() {
        fun t(status: String, j: Pair<JunctionCue, Double>?) = NavNotification.of(status, j, 4230.0, 5100.0, 0L,
            distance = { if (it < 1000) "${it.toInt()} m" else "%.1f km".format(java.util.Locale.ROOT, it / 1000) },
            duration = { "1:25 h" }, clock = { "15:40" })!!
        assertEquals("Left in 120 m", t("Recording", JunctionCue.LEFT to 117.0).title)
        assertEquals("4.2 km to go", t("Recording", null).title)
        assertEquals("distance only once", "1:25 h · arrival 15:40", t("Recording", null).text)
        assertEquals("1:25 h to go · arrival 15:40 · 4.2 km left", t("Recording", JunctionCue.LEFT to 117.0).text)
        assertEquals("1:25 h to go · arrival 15:40 · 4.2 km left", t("Off route", null).text)
        assertEquals("4.2 km to go", t("Recording", JunctionCue.LEFT to 800.0).title)
        assertEquals("Off route", t("Off route", JunctionCue.LEFT to 50.0).title)
        assertNull("not on the route: plain notification", NavNotification.of("Recording", null, null, null, null, { "" }, { "" }, { "" }))
    }
}
