package com.onpath.app.navigation

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import com.onpath.app.routing.JunctionShape
import com.onpath.app.time.TimeEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Junction vibration: when, how often, which side. */
class JunctionCuesTest {
    /** A straight line north, a point every 10 m (1 m ≈ 0.000009°), junctions at the given metres. */
    private fun line(lengthM: Int, junctions: Map<Int, JunctionCue>): List<GeoPoint> =
        (0..lengthM step 10).map { m -> GeoPoint(46.0 + m * 0.0000089932, 11.0, turn = junctions[m]) }

    /** Walk the line in 5 m steps; returns (metres, cue) for every cue. */
    private fun walk(c: JunctionCues, l: List<GeoPoint>, from: Int = 0, to: Int = l.size * 10, onRoute: (Int) -> Boolean = { true }):
        List<Pair<Int, JunctionCue>> = (from..to step 5).mapNotNull { m -> c.update(l, 1, m.toDouble(), onRoute(m))?.let { m to it } }

    @Test fun oncePerJunctionBeforeIt() {
        val l = line(1000, mapOf(200 to JunctionCue.LEFT, 600 to JunctionCue.RIGHT))
        val cues = walk(JunctionCues(), l)
        assertEquals(listOf(JunctionCue.LEFT, JunctionCue.RIGHT), cues.map { it.second })
        // on foot, speed unknown: 25 s at 5 km/h = 35 m
        assertTrue("left cue 35 m before: ${cues[0].first}", cues[0].first == 165)
        assertTrue("right cue 35 m before: ${cues[1].first}", cues[1].first == 565)
    }

    // ---- on foot (owner 2026-10-30: the bike's smarter cues, timed for walking) ----

    // shapes with hiking-mountain.brf's classes: 3 path, 2 gravel path, 8 track
    private val trailT = JunctionShape(-88f, 3, 3, listOf(JunctionShape.Branch(2f, 3))) // left off the path, it goes on
    private val trailOn = JunctionShape(12f, 3, 3, listOf(JunctionShape.Branch(-70f, 2))) // the path goes on, one branches off
    private val trackBends = JunctionShape(40f, 8, 8, listOf(JunctionShape.Branch(-3f, 3))) // the track bends, a path straight on

    /** A trail: [turns] (metre → T-junction left), [quiet] (metre → the path / track simply goes on). */
    private fun trail(lengthM: Int, turns: List<Int> = emptyList(), quiet: List<Int> = emptyList()) =
        line(lengthM, turns.associateWith { JunctionCue.LEFT } + quiet.associateWith { JunctionCue.SLIGHT_RIGHT }).mapIndexed { i, p ->
            when (i * 10) { in turns -> p.copy(shape = trailT); in quiet -> p.copy(shape = if (i % 2 == 0) trailOn else trackBends); else -> p }
        }

    private fun hike(l: List<GeoPoint>, speed: Double?) = ride(JunctionCues(), l, speed)

    @Test fun hikeCueComesTwentyFiveSecondsBefore() {
        val l = trail(1000, turns = listOf(500))
        assertEquals(475, hike(l, 1.0).single().first) // 3.6 km/h → 25 m
        assertEquals(460, hike(l, 1.6).single().first) // 5.8 km/h → 40 m (as before)
        assertEquals(440, hike(l, 3.0).single().first) // a jump of the GPS speed: at most 60 m
        assertEquals(475, hike(l, 0.4).single().first) // a steep climb: at least 25 m
        assertEquals(465, hike(l, null).single().first) // speed unknown: 5 km/h → 35 m
        assertEquals(30.0, JunctionCues.hikeLeadM(1.2), 0.01)
    }

    @Test fun hikeOnlyWhereOneCouldGoWrong() {
        // along a trail: the path goes on while another branches off, a track bends while a path goes straight on – quiet;
        // the T-junction where the route leaves the path vibrates
        val l = trail(1500, quiet = listOf(200, 450, 700), turns = listOf(1000))
        assertEquals(listOf(JunctionCue.LEFT), hike(l, 1.3).map { it.second })
        // the quiet ones are still on the lock screen
        assertEquals("Slight right", JunctionCues().nextAheadText(l, 1, 100.0)!!.first)
    }

    @Test fun hikeCloseJunctionsAreOneCue() {
        // two turns 60 m apart: at 5 km/h (1.4 m/s · 30 s = 42 m → 50 m) each its own cue; at 8 km/h (2.2 m/s → 66 m) one
        val l = trail(1000, turns = listOf(300, 360, 700))
        assertEquals(3, hike(l, 1.4).size)
        assertEquals(2, hike(l, 2.2).size)
        assertEquals(60.0, JunctionCues.hikeMergeM(2.0), 0.01)
        assertEquals(JunctionCues.MERGE_M, JunctionCues.hikeMergeM(1.0), 0.01)
        // a quiet junction between them does not split the chain
        val l2 = trail(1000, turns = listOf(300, 350), quiet = listOf(330))
        assertEquals(1, hike(l2, 2.0).size)
    }

    @Test fun hikeAndBikeReadTheSameLineDifferently() {
        // a fork of two paths 20° / 30°, one tagged gravel: the bike's classes would call it "along the path"; on foot a fork
        val fork = JunctionShape(-20f, 3, 3, listOf(JunctionShape.Branch(30f, 2)))
        val l = line(600, mapOf(300 to JunctionCue.KEEP_LEFT)).map { if (it.turn != null) it.copy(shape = fork) else it }
        assertEquals(1, ride(JunctionCues(), l, 1.4).size)
        assertEquals(0, ride(JunctionCues.bike(), l, 5.5).size)
        // switching one instance between them (another tour) re-reads the line
        val c = JunctionCues.bike()
        assertNull(c.update(l, 1, 280.0, true, 5.5))
        c.bike = false
        assertEquals(JunctionCue.KEEP_LEFT, c.update(l, 1, 280.0, true, 1.4))
    }

    // ---- on the bike (owner 2026-10-30) ----

    private val crossingStraightOn = JunctionShape(3f, 6, 6, listOf(JunctionShape.Branch(-88f, 6), JunctionShape.Branch(92f, 6)))
    private val tLeft = JunctionShape(-90f, 6, 6, listOf(JunctionShape.Branch(90f, 6)))
    private val tRight = JunctionShape(90f, 6, 6, listOf(JunctionShape.Branch(-90f, 6)))

    /** A line with bike junctions: left turns at [lefts], right turns at [rights] (T-junctions), quiet crossings at [quiet]. */
    private fun bikeLine(lengthM: Int, lefts: List<Int> = emptyList(), rights: List<Int> = emptyList(), quiet: List<Int> = emptyList()) =
        line(lengthM, lefts.associateWith { JunctionCue.LEFT } + rights.associateWith { JunctionCue.RIGHT } +
            quiet.associateWith { JunctionCue.KEEP_LEFT }).mapIndexed { i, p ->
            when (i * 10) { in lefts -> p.copy(shape = tLeft); in rights -> p.copy(shape = tRight); in quiet -> p.copy(shape = crossingStraightOn); else -> p }
        }

    private fun ride(c: JunctionCues, l: List<GeoPoint>, speed: Double?) =
        (0..l.size * 10 step 5).mapNotNull { m -> c.update(l, 1, m.toDouble(), true, speed)?.let { m to it } }

    @Test fun bikeCueComesEightSecondsBefore() {
        val l = bikeLine(1000, lefts = listOf(500))
        // 20 km/h = 5.56 m/s → 44 m before
        assertTrue(ride(JunctionCues.bike(), l, 5.56).single().first in 450..460)
        // 36 km/h → 80 m
        assertTrue(ride(JunctionCues.bike(), l, 10.0).single().first in 415..425)
        // fast downhill: at most 100 m
        assertTrue(ride(JunctionCues.bike(), l, 16.0).single().first in 400..405)
        // pushing the bike / standing at the lights: at least 30 m
        assertTrue(ride(JunctionCues.bike(), l, 1.0).single().first in 470..475)
        // no speed yet: about 22 km/h
        assertTrue(ride(JunctionCues.bike(), l, null).single().first in 450..455)
        assertEquals(44.0, JunctionCues.bikeLeadM(5.5), 0.01)
    }

    @Test fun bikeCloseTurnsAreOneCueBeforeTheFirst() {
        // left at 300, right 60 m later: at 20 km/h that is 11 s – one cue, the first turn's pattern
        val l = bikeLine(1000, lefts = listOf(300), rights = listOf(360, 700))
        assertEquals(listOf(JunctionCue.LEFT, JunctionCue.RIGHT), ride(JunctionCues.bike(), l, 5.5).map { it.second })
        // slowly (14 km/h) the two are 15 s apart: each gets its own cue
        assertEquals(listOf(JunctionCue.LEFT, JunctionCue.RIGHT, JunctionCue.RIGHT), ride(JunctionCues.bike(), l, 3.9).map { it.second })
        // the 50 m rule holds whatever the speed (a chain of hairpins)
        val hairpins = bikeLine(1000, rights = listOf(300), lefts = listOf(340))
        assertEquals(listOf(JunctionCue.RIGHT), ride(JunctionCues.bike(), hairpins, 1.0).map { it.second })
        assertEquals(66.0, JunctionCues.bikeMergeM(5.5), 0.01)
        assertEquals(JunctionCues.MERGE_M, JunctionCues.bikeMergeM(2.0), 0.01)
    }

    @Test fun bikeOnlyWhereOneCouldGoWrong() {
        // straight on through two crossings, then a T-junction left: one cue
        val l = bikeLine(1000, quiet = listOf(200, 400), lefts = listOf(700))
        assertEquals(listOf(JunctionCue.LEFT), ride(JunctionCues.bike(), l, 5.5).map { it.second })
        // a quiet crossing right before a turn does not swallow the turn's cue
        val l2 = bikeLine(1000, quiet = listOf(470), lefts = listOf(500))
        assertEquals(listOf(JunctionCue.LEFT), ride(JunctionCues.bike(), l2, 5.5).map { it.second })
        // the lock screen still names the quiet junction
        assertEquals("Keep left", JunctionCues.bike().nextAheadText(l, 1, 100.0)!!.first)
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

    // roundabouts with their exit: router row → point → session file → lock screen / hint
    @Test fun roundaboutExitFromTheRouter() {
        val pts = MutableList(5) { GeoPoint(46.0 + it * 0.001, 11.0) }
        JunctionCue.apply(pts, arrayOf(doubleArrayOf(1.0, 13.0, 30.0, 2.0), doubleArrayOf(3.0, 5.0, 90.0, 0.0)))
        assertEquals(JunctionCue.ROUNDABOUT, pts[1].turn); assertEquals(2, pts[1].exit)
        assertEquals(0, pts[3].exit)
        // an old three-value row (no exit) still works
        JunctionCue.apply(pts, arrayOf(doubleArrayOf(2.0, 13.0, 30.0)))
        assertEquals(JunctionCue.ROUNDABOUT, pts[2].turn); assertEquals(0, pts[2].exit)
    }

    @Test fun roundaboutText() {
        assertEquals("Roundabout · exit 2", JunctionCue.ROUNDABOUT.text(2))
        assertEquals("Roundabout", JunctionCue.ROUNDABOUT.text(0))
        assertEquals("Left", JunctionCue.LEFT.text(3))
    }

    @Test fun roundaboutAheadAndLockScreenText() {
        val l = line(600, mapOf(100 to JunctionCue.LEFT, 300 to JunctionCue.ROUNDABOUT)).toMutableList()
        val i = l.indexOfFirst { it.turn == JunctionCue.ROUNDABOUT }
        l[i] = l[i].copy(exit = 3)
        val c = JunctionCues()
        assertEquals(3 to 250.0, c.roundaboutAhead(l, 1, 50.0)!!.let { it.first to Math.round(it.second / 10) * 10.0 })
        assertNull("passed", c.roundaboutAhead(l, 1, 320.0))
        assertEquals("Left", c.nextAheadText(l, 1, 50.0)!!.first)
        assertEquals("Roundabout · exit 3", c.nextAheadText(l, 1, 150.0)!!.first)
    }

    @Test fun sessionFileKeepsTheRoundaboutExit() {
        val l = line(500, mapOf(200 to JunctionCue.ROUNDABOUT)).map { if (it.turn != null) it.copy(exit = 2) else it }
        val back = NavSessionStore.fromJson(NavSessionStore.toJson(NavSession(NavKind.PLAN, "Test",
            com.onpath.app.routing.Activity.BIKE_TOURING, 3, l)))!!
        assertEquals(listOf(2), back.original.filter { it.turn != null }.map { it.exit })
    }

    @Test fun sessionFileKeepsTheJunctions() {
        val l = line(500, mapOf(100 to JunctionCue.LEFT, 400 to JunctionCue.KEEP_RIGHT))
        val s = NavSession(NavKind.PLAN, "Test", com.onpath.app.routing.Activity.HIKE, 3, l, current = l.drop(5))
        val back = NavSessionStore.fromJson(NavSessionStore.toJson(s))!!
        assertEquals(l.map { it.turn }, back.original.map { it.turn })
        assertEquals(l.drop(5).map { it.turn }, back.current!!.map { it.turn })
    }

    /** Arrival at 15:41 local time today – shown as "15:40" (5-minute rounding, [TimeEstimate.clock]). */
    private fun localMs(h: Int, m: Int) = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun lockScreenText() {
        fun t(status: String, j: Pair<JunctionCue, Double>?) = NavNotification.of(status, j?.let { it.first.text to it.second }, 4230.0, 5100.0,
            localMs(15, 41),
            distance = { if (it < 1000) "${it.toInt()} m" else "%.1f km".format(java.util.Locale.ROOT, it / 1000) },
            duration = { "1:25 h" })!!
        assertEquals("Left in 120 m", t("Recording", JunctionCue.LEFT to 117.0).title)
        assertEquals("4.2 km to go", t("Recording", null).title)
        assertEquals("distance only once", "1:25 h · arrival 15:40", t("Recording", null).text)
        assertEquals("1:25 h to go · arrival 15:40 · 4.2 km left", t("Recording", JunctionCue.LEFT to 117.0).text)
        assertEquals("1:25 h to go · arrival 15:40 · 4.2 km left", t("Off route", null).text)
        assertEquals("4.2 km to go", t("Recording", JunctionCue.LEFT to 800.0).title)
        assertEquals("Off route", t("Off route", JunctionCue.LEFT to 50.0).title)
        assertNull("not on the route: plain notification", NavNotification.of("Recording", null, null, null, null, { "" }, { "" }))
    }

    /** Owner 2026-10-30: the lock screen showed "10:22" while the app said "10:20" – one rule (5 minutes) for both. */
    @Test fun lockScreenArrivalSameAsApp() {
        for ((h, m, shown) in listOf(Triple(10, 22, "10:20"), Triple(10, 23, "10:25"), Triple(9, 3, "9:05"), Triple(23, 58, "0:00"))) {
            val ms = localMs(h, m)
            assertEquals(shown, TimeEstimate.clock(ms))
            val text = NavNotification.of("Recording", null, 4230.0, 5100.0, ms, { "4.2 km" }, { "1:25 h" })!!.text
            assertEquals("lock screen = app ($h:$m)", "1:25 h · arrival ${TimeEstimate.clock(ms)}", text)
        }
    }

    /** A line without the junctions' ways (routed before, an old session): slight bends skipped, on the bike and on foot. */
    @Test fun slightBendsSkippedWithoutShape() {
        val l = line(800, mapOf(100 to JunctionCue.SLIGHT_LEFT, 300 to JunctionCue.LEFT, 500 to JunctionCue.KEEP_RIGHT, 700 to JunctionCue.SLIGHT_RIGHT))
        fun walk(c: JunctionCues) = (0..800 step 5).mapNotNull { m -> c.update(l, 1, m.toDouble(), true) }
        assertEquals(listOf(JunctionCue.LEFT, JunctionCue.KEEP_RIGHT), walk(JunctionCues.bike()))
        assertEquals(listOf(JunctionCue.LEFT, JunctionCue.KEEP_RIGHT), walk(JunctionCues()))
    }

    @Test fun sessionFileKeepsTheJunctionShapes() {
        val l = line(500, mapOf(100 to JunctionCue.LEFT, 400 to JunctionCue.KEEP_RIGHT))
            .map { if (it.turn == JunctionCue.LEFT) it.copy(shape = tLeft) else it }
        val s = NavSession(NavKind.PLAN, "Test", com.onpath.app.routing.Activity.BIKE_TOURING, 3, l, current = l.drop(5))
        val back = NavSessionStore.fromJson(NavSessionStore.toJson(s))!!
        assertEquals(l.map { it.shape }, back.original.map { it.shape })
        assertEquals(l.drop(5).map { it.shape }, back.current!!.map { it.shape })
        // a file of an older app (no shapes): junctions kept, no shape
        val old = NavSessionStore.toJson(s).apply { remove("originalShapes"); remove("currentShapes") }
        assertEquals(listOf(null, null), NavSessionStore.fromJson(old)!!.original.filter { it.turn != null }.map { it.shape })
    }
}
