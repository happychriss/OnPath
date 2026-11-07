package com.onpath.app.navigation

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import com.onpath.app.routing.JunctionShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/design/voice-guidance.md: few, clear sentences – when the voice speaks and what (owner 2026-10-31). */
class VoiceGuideTest {
    /** A straight line north, a point every 10 m, junctions at the given metres (a clear T-junction each). */
    private fun line(lengthM: Int, junctions: Map<Int, JunctionCue>, shape: JunctionShape? = null): List<GeoPoint> =
        (0..lengthM step 10).map { m -> GeoPoint(46.0 + m * 0.0000089932, 11.0, turn = junctions[m], shape = junctions[m]?.let { shape }) }

    /** Ride / walk the line in 5 m steps at [speed]; one fix per second of travel; returns (metre, phrase). */
    private fun ride(g: VoiceGuide, l: List<GeoPoint>, bike: Boolean, speed: Double, from: Int = 0, to: Int = (l.size - 1) * 10,
                     input: (Int, VoiceGuide.Input) -> VoiceGuide.Input = { _, i -> i }): List<Pair<Int, Phrase>> {
        var t = 0L
        return (from..to step 5).mapNotNull { m ->
            t += (5_000 / speed).toLong()
            val i = VoiceGuide.Input(t, l, 1, m.toDouble(), onRoute = true, speedMps = speed, bike = bike)
            g.update(input(m, i))?.let { m to it }
        }
    }

    private val startless: (List<Pair<Int, Phrase>>) -> List<Pair<Int, Phrase>> = { it.filter { p -> p.second !is Phrase.Start } }

    @Test fun startSentenceOnceWithDistanceTimeAndDestination() {
        val l = line(3000, emptyMap())
        val stops = listOf(NavStop(l.first(), 0.0, "Meran"), NavStop(l.last(), 3000.0, "Lana"))
        val said = ride(VoiceGuide(), l, bike = true, speed = 5.0) { _, i -> i.copy(stops = stops, toGoS = 900.0) }
        // the line's length comes from the geometry (3000 m ± a few mm) – the speech rounds it anyway
        val start = said.map { it.second }.single() as Phrase.Start
        assertEquals(3000.0, start.distanceM, 0.5)
        assertEquals(Phrase.Start(start.distanceM, 900.0, "Lana"), start)
    }

    /**
     * Bike in two stages (owner 2026-11-01, Rosellen replay; Organic Maps / OsmAnd): "In 150 Metern links." ~25 s ahead
     * (100…400 m), then "Gleich links." ~8 s ahead (35…150 m) – both by the speed ridden.
     */
    @Test fun bikeTwoStagesByTheSpeed() {
        val l = line(2000, mapOf(500 to JunctionCue.LEFT, 1500 to JunctionCue.RIGHT))
        val said = startless(ride(VoiceGuide(), l, bike = true, speed = 6.0))
        assertEquals(4, said.size)
        // 25 s × 6 m/s = 150 m before, with the distance
        assertEquals(350, said[0].first)
        assertEquals(150.0, (said[0].second as Phrase.Junction).inM, 0.5)
        assertEquals(Turn.LEFT, (said[0].second as Phrase.Junction).turn)
        // the turn word heard 6 s × 6 m/s = 36 m before, + the speech start (0.5 s), "Gleich" (0.4 s) and half a fix
        // interval (0.5 s) ridden meanwhile = 44 m: said at 460 m, no number ("Gleich links.")
        assertEquals(460, said[1].first)
        assertEquals(0.0, (said[1].second as Phrase.Junction).inM, 0.0)
        // faster: earlier
        val fast = startless(ride(VoiceGuide(), l, bike = true, speed = 12.0))
        assertEquals(listOf(200, 415), fast.take(2).map { it.first })
        val veryFast = startless(ride(VoiceGuide(), l, bike = true, speed = 20.0))
        // (the early one waits for the 10 s after the start sentence: 200 m at 72 km/h; the voice plans with at most
        // 14 m/s – a GPS speed spike is no reason to say a turn far too early)
        assertEquals(listOf(200, 400), veryFast.take(2).map { it.first })
    }

    /** A junction that comes into view too late for the early sentence gets only "Gleich …". */
    @Test fun bikeAJunctionRightAfterAnotherGetsOnlyTheSecondStage() {
        val l = line(2000, mapOf(500 to JunctionCue.LEFT, 590 to JunctionCue.RIGHT))
        val j = startless(ride(VoiceGuide(), l, bike = true, speed = 6.0)).map { it.second as Phrase.Junction }
        // the right 90 m on: named with the left ("Gleich links, danach rechts.") and its own "now" once the left is
        // ridden (owner 2026-11-07, never swallowed) – no early sentence for it
        assertEquals(listOf(Turn.LEFT, Turn.LEFT, Turn.RIGHT), j.map { it.turn })
        assertEquals(Turn.RIGHT, j[1].then)
        assertEquals(0.0, j[2].inM, 0.0)
    }

    /**
     * Owner 2026-11-06 (demo ride Düsseldorf → Grimlinghausen): bear right, 70 m later left, 60 m later right. "Gleich rechts
     * halten, danach links" – and then "In 100 Metern rechts" while the left was still 55 m ahead: it sounded as if the
     * left was skipped. A turn is announced only once the one before it is ridden.
     */
    @Test fun aTurnIsAnnouncedOnlyAfterThePreviousOneIsRidden() {
        val l = line(3200, mapOf(2730 to JunctionCue.RIGHT, 2800 to JunctionCue.LEFT, 2860 to JunctionCue.RIGHT))
        val said = startless(ride(VoiceGuide(), l, bike = true, speed = 5.0))
        // the right turn at 2860 (the one at 2730 is said before 2730)
        val right = said.filter { (m, p) -> m > 2730 && p is Phrase.Junction && p.turn == Turn.RIGHT }
        assertTrue("$said", right.isNotEmpty())
        assertTrue("the right turn announced before the left was ridden: $said", right.all { it.first >= 2800 })
    }

    /** Rosellen replay: the second turn 80 m after the first must not be lost in the pause between sentences. */
    @Test fun bikeAJunctionJustAheadIsSaidDuringThePause() {
        val l = line(2000, mapOf(500 to JunctionCue.SHARP_RIGHT, 620 to JunctionCue.LEFT))
        val said = startless(ride(VoiceGuide(), l, bike = true, speed = 6.0)).map { it.second as Phrase.Junction }
        assertTrue("$said", said.any { it.turn == Turn.LEFT || it.then == Turn.LEFT })
    }

    /**
     * Field bug (owner 2026-11-06, Fragenregister donald-20261106-51: "hier biegen wir links ab, aber das Navi sagt
     * nichts"): a round trip ridden a second time – the same line from its start again – says its turns again.
     */
    @Test fun aRoundTripRiddenTwiceSaysItsTurnsAgain() {
        val l = line(2000, mapOf(500 to JunctionCue.LEFT, 1500 to JunctionCue.RIGHT))
        for (bike in listOf(false, true)) {
            val speed = if (bike) 6.0 else 1.4
            val g = VoiceGuide()
            val lap1 = startless(ride(g, l, bike = bike, speed = speed)).map { (it.second as Phrase.Junction).turn }
            val lap2 = startless(ride(g, l, bike = bike, speed = speed) { _, i -> i.copy(nowMs = i.nowMs + 10_000_000L) })
                .map { (it.second as Phrase.Junction).turn }
            assertTrue("bike=$bike lap 1: $lap1", lap1.containsAll(listOf(Turn.LEFT, Turn.RIGHT)))
            assertEquals("bike=$bike lap 2", lap1, lap2)
        }
    }

    /** Owner 2026-11-01: on foot "Gleich links." about 10 m before, no distance ("50 m kann man nicht abschätzen"). */
    @Test fun onFoot_shortlyBeforeWithoutADistance() {
        val l = line(1000, mapOf(500 to JunctionCue.LEFT))
        val said = startless(ride(VoiceGuide(), l, bike = false, speed = 1.4))
        assertEquals(490, said.single().first) // 8 s × 1.4 = 11 m → the fix at 490
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("Gleich links.", VoiceText.of(said.single().second))
        } finally { java.util.Locale.setDefault(saved) }
    }

    @Test fun onlyWhereWeVibrate_notWhereTheRouteFollowsTheWay() {
        // the street bends right, a smaller way goes on straight: CueRule says no – the voice says nothing
        val along = JunctionShape(30f, 20, 20, listOf(JunctionShape.Branch(-5f, 6)))
        val l = line(1000, mapOf(500 to JunctionCue.SLIGHT_RIGHT), along)
        assertEquals(emptyList<Pair<Int, Phrase>>(), startless(ride(VoiceGuide(), l, bike = true, speed = 6.0)))
    }

    @Test fun twoCloseTurnsInOneSentence() {
        val l = line(2000, mapOf(500 to JunctionCue.LEFT, 540 to JunctionCue.RIGHT))
        val j = startless(ride(VoiceGuide(), l, bike = true, speed = 6.0)).map { it.second as Phrase.Junction }.first { it.inM == 0.0 }
        assertEquals(Turn.LEFT, j.turn)
        assertEquals(Turn.RIGHT, j.then)
    }

    @Test fun aLongQuietStretchAfterwardsIsSaid() {
        val l = line(5000, mapOf(500 to JunctionCue.RIGHT, 4000 to JunctionCue.LEFT))
        val said = startless(ride(VoiceGuide(), l, bike = true, speed = 6.0)).map { it.second as Phrase.Junction }
        // with the second stage ("Gleich rechts, dann 3,5 km geradeaus")
        assertEquals(3500.0, said[1].straightM!!, 1.0)
        assertNull("1000 m to the end: under 2 km on the bike", said[3].straightM)
    }

    @Test fun theKindOfWayWhenItChanges() {
        // off the road onto a small way, no way types on the line (bike classes: 22 road → 4 track / path / footway alike):
        // "Weg" – never "Feldweg" from the class alone (owner 2026-11-01, Hilden: park paths and crossings were "Feldweg")
        val offRoad = JunctionShape(-85f, 22, 4, listOf(JunctionShape.Branch(0f, 22)))
        val l = line(1000, mapOf(500 to JunctionCue.LEFT), offRoad)
        val j = startless(ride(VoiceGuide(), l, bike = true, speed = 6.0)).last().second as Phrase.Junction
        assertEquals(WayKind.WAY, j.onto)
        // street to street: nothing about the way
        val streets = JunctionShape(-85f, 6, 6, listOf(JunctionShape.Branch(0f, 6)))
        val k = startless(ride(VoiceGuide(), line(1000, mapOf(500 to JunctionCue.LEFT), streets), bike = true, speed = 6.0)).last().second
        assertNull((k as Phrase.Junction).onto)
    }

    /** The way types of the line (BRouterWayStats) name the way – read 20 m after the junction, past a crossing. */
    @Test fun theKindOfWayFromTheWayTypes() {
        fun typed(before: String, after: String, crossingM: Int = 0) = line(1000, mapOf(500 to JunctionCue.LEFT),
            JunctionShape(-85f, 6, 4, listOf(JunctionShape.Branch(0f, 6)))).mapIndexed { i, p ->
            val m = i * 10
            val type = when { m < 500 -> before; m < 500 + crossingM -> "footway"; else -> after }
            p.copy(way = com.onpath.app.routing.WayAttrs(type, "asphalt"))
        }
        fun onto(l: List<GeoPoint>, bike: Boolean = true) =
            (startless(ride(VoiceGuide(), l, bike = bike, speed = 6.0)).last().second as Phrase.Junction).onto
        assertEquals(WayKind.TRACK, onto(typed("quiet_road", "track")))
        assertEquals(WayKind.WAY, onto(typed("quiet_road", "footway")))
        assertEquals(WayKind.CYCLEWAY, onto(typed("quiet_road", "cycleway")))
        assertEquals("a path on the bike is a Weg", WayKind.WAY, onto(typed("quiet_road", "path")))
        assertEquals("on foot a Pfad", WayKind.PATH, onto(typed("quiet_road", "path"), bike = false))
        // a 10 m crossing (footway) before the park's cycle path: the cycle path is the way turned onto
        assertEquals(WayKind.CYCLEWAY, onto(typed("quiet_road", "cycleway", crossingM = 10)))
        // the same kind on both sides: nothing about the way
        assertNull(onto(typed("footway", "footway")))
        assertNull("steps: no word", onto(typed("quiet_road", "steps")))
    }

    @Test fun neverTwoSentencesWithin10Seconds_aJunctionWaitsAndKeepsItsDistance() {
        // the start sentence comes at 0 m; a junction 60 m ahead would be due at once – it comes after the gap
        val l = line(1000, mapOf(60 to JunctionCue.LEFT, 800 to JunctionCue.RIGHT))
        val said = ride(VoiceGuide(), l, bike = false, speed = 1.4)
        assertTrue(said[0].second is Phrase.Start)
        val j = said[1]
        assertTrue("junction after ≥ 10 s of walking (≥ 14 m): ${j.first}", j.first >= 15)
        assertTrue((j.second as Phrase.Junction).inM < 50)
    }

    @Test fun tooLateIsLeftToTheVibration() {
        val g = VoiceGuide()
        val l = line(1000, mapOf(500 to JunctionCue.LEFT))
        // matched for the first time 8 m before the junction
        val i = VoiceGuide.Input(0, l, 1, 492.0, onRoute = true, speedMps = 6.0, bike = true)
        g.update(i) // start sentence
        assertNull(g.update(i.copy(nowMs = 20_000, alongM = 493.0)))
    }

    @Test fun offRouteOnce_thenBackOnTheRoute() {
        val g = VoiceGuide()
        val l = line(3000, emptyMap())
        val base = VoiceGuide.Input(0, l, 1, 100.0, onRoute = true, speedMps = 5.0, bike = true)
        assertTrue(g.update(base) is Phrase.Start)
        assertEquals(Phrase.OffRoute, g.update(base.copy(nowMs = 2_000, onRoute = false, offRoute = true)))
        assertNull(g.update(base.copy(nowMs = 20_000, onRoute = false, offRoute = true)))
        // "back on the route" only when the question was open for a while (a flapping fix says nothing twice)
        assertEquals(Phrase.BackOnRoute, g.update(base.copy(nowMs = 70_000)))
    }

    @Test fun aTakenNewRouteSaysSoWithItsFirstJunction() {
        val g = VoiceGuide()
        val old = line(3000, emptyMap())
        val new = line(3000, mapOf(150 to JunctionCue.RIGHT))
        val base = VoiceGuide.Input(0, old, 1, 100.0, onRoute = true, speedMps = 5.0, bike = true)
        g.update(base)
        g.update(base.copy(nowMs = 2_000, onRoute = false, offRoute = true))
        val p = g.update(VoiceGuide.Input(30_000, new, 2, 20.0, onRoute = true, speedMps = 5.0, bike = true, rerouted = true))
        assertTrue("$p", p is Phrase.NewRoute)
        assertEquals(Turn.RIGHT, (p as Phrase.NewRoute).next?.turn)
    }

    /** Pixel 2026-11-01: the line was re-routed 5 s after the tour start – "Neue Route." came before "Los geht's.". */
    @Test fun aNewRouteBeforeTheStartSentenceIsNotSaid() {
        val g = VoiceGuide()
        val first = line(3000, emptyMap())
        val second = line(2800, emptyMap())
        // tour started, position not on the line yet (no start sentence)
        assertNull(g.update(VoiceGuide.Input(0, first, 1, null, onRoute = false, speedMps = null, bike = true)))
        val p = g.update(VoiceGuide.Input(5_000, second, 2, 0.0, onRoute = true, speedMps = 5.0, bike = true, rerouted = true))
        assertTrue("$p", p is Phrase.Start)
        assertNull(g.update(VoiceGuide.Input(20_000, second, 2, 50.0, onRoute = true, speedMps = 5.0, bike = true, rerouted = true)))
    }

    /** Rosellen replay (owner 2026-11-01): after deleting a point the whole plan is followed again – its visited stops are no news. */
    @Test fun anotherListOfStopsSaysNoStopReached() {
        val g = VoiceGuide()
        val l = line(3000, emptyMap())
        val all = listOf(NavStop(l[0], 0.0), NavStop(l[50], 500.0), NavStop(l[100], 1000.0), NavStop(l[200], 2000.0), NavStop(l.last(), 3000.0))
        val ahead = listOf(NavStop(l[120], 0.0), NavStop(l[200], 800.0), NavStop(l.last(), 1800.0))
        val base = VoiceGuide.Input(0, l, 1, 1200.0, onRoute = true, speedMps = 5.0, bike = true, stops = ahead, visited = emptySet())
        g.update(base)
        val said = (1..5).mapNotNull { s -> g.update(base.copy(nowMs = s * 20_000L, stops = all, visited = setOf(0, 1, 2))) }
        assertTrue("$said", said.none { it is Phrase.StopReached })
    }

    /** Rosellen replay (owner 2026-11-01): following the points ahead after an edit – the map's numbers, not 1, 2, 3. */
    @Test fun stopNumbersAreTheMapsAfterAnEdit() {
        val g = VoiceGuide()
        val l = line(3000, emptyMap())
        val stops = listOf(NavStop(l[0], 0.0), NavStop(l[100], 1000.0), NavStop(l.last(), 3000.0))
        val base = VoiceGuide.Input(0, l, 1, 10.0, onRoute = true, speedMps = 5.0, bike = true, stops = stops, visited = setOf(0),
            firstNumber = 6)
        g.update(base)
        val p = g.update(base.copy(nowMs = 300_000, alongM = 1000.0, visited = setOf(0, 1)))
        assertEquals(7, (p as Phrase.StopReached).number)
    }

    /** A stop left out on purpose ("Neue Route ohne Stopp 7") is never "reached" (owner 2026-11-01). */
    @Test fun aSkippedStopIsNotReached() {
        val g = VoiceGuide()
        val l = line(3000, emptyMap())
        val stops = listOf(NavStop(l[0], 0.0), NavStop(l[100], 1000.0), NavStop(l.last(), 3000.0))
        val base = VoiceGuide.Input(0, l, 1, 10.0, onRoute = true, speedMps = 5.0, bike = true, stops = stops, visited = setOf(0))
        g.update(base)
        val said = (1..5).mapNotNull { s -> g.update(base.copy(nowMs = s * 20_000L, alongM = 600.0, visited = setOf(0, 1), skipped = setOf(1))) }
        assertTrue("$said", said.none { it is Phrase.StopReached })
    }

    @Test fun stopReachedWithTheNextStop_notTheStartNorTheDestination() {
        val g = VoiceGuide()
        val l = line(3000, emptyMap())
        val stops = listOf(NavStop(l[0], 0.0), NavStop(l[100], 1000.0, "Gasthof Hochmuth"), NavStop(l[200], 2000.0, "Algund"),
            NavStop(l.last(), 3000.0, "Lana"))
        val base = VoiceGuide.Input(0, l, 1, 10.0, onRoute = true, speedMps = 5.0, bike = true, stops = stops, visited = setOf(0))
        g.update(base)
        val p = g.update(base.copy(nowMs = 300_000, alongM = 1000.0, visited = setOf(0, 1)))
        assertEquals(Phrase.StopReached("Gasthof Hochmuth", 2, "Algund", 1000.0), p)
        assertNull("the destination is the arrival event", g.update(base.copy(nowMs = 900_000, alongM = 3000.0, visited = setOf(0, 1, 2, 3)))
            ?.takeIf { it is Phrase.StopReached && it.number == 4 })
    }

    /** Owner 2026-11-01: on the way, 2–3 min without GPS → said once; a short gap says nothing ("otherwise it nags"). */
    @Test fun noGpsOnceAfterTwoAndAHalfMinutes_quietWhilePaused() {
        val g = VoiceGuide()
        val l = line(3000, emptyMap())
        val base = VoiceGuide.Input(0, l, 1, 100.0, onRoute = true, speedMps = 5.0, bike = true)
        g.update(base)
        assertNull(g.update(base.copy(nowMs = 20_000, gpsLost = true)))
        assertNull("a minute is no news", g.update(base.copy(nowMs = 80_000, gpsLost = true)))
        assertEquals(Phrase.NoGps, g.update(base.copy(nowMs = 20_000 + VoiceGuide.GPS_LOST_MS + 1_000, gpsLost = true)))
        assertNull(g.update(base.copy(nowMs = 300_000, gpsLost = true)))
        assertNull(g.update(base.copy(nowMs = 600_000, gpsLost = true, paused = true)))
    }

    @Test fun eventsAreSaidAtOnce() {
        val g = VoiceGuide()
        val l = line(3000, emptyMap())
        g.update(VoiceGuide.Input(0, l, 1, 100.0, onRoute = true, speedMps = 5.0, bike = true))
        assertEquals(Phrase.Arrived, g.event(Phrase.Arrived, 1_000))
    }

    @Test fun budget_aRideWithManyJunctionsIsAboutOneSentenceEachPlusStart() {
        val turns = (1..40).associate { it * 1000 to if (it % 2 == 0) JunctionCue.LEFT else JunctionCue.RIGHT }
        val said = ride(VoiceGuide(), line(41_000, turns), bike = true, speed = 6.0)
        // the start + two stages per junction on the bike ("In 150 Metern rechts." · "Gleich rechts.")
        assertEquals(81, said.size)
    }

    /** Pixel 2026-11-01: "Los geht's" 3–4× in the first 3 km – the process died, the service came back with a fresh guide. */
    @Test fun theStartSentenceIsNotSaidAgainWhenTheServiceContinuesTheTour() {
        val l = line(3000, emptyMap())
        val before = VoiceGuide()
        assertTrue(before.update(VoiceGuide.Input(0, l, 1, 0.0, onRoute = true, speedMps = 5.0, bike = true)) is Phrase.Start)
        // sticky restart: same tour, same line, a new guide with the tour's voice memory
        val after = VoiceGuide().apply { continueTour(before.memory()) }
        val said = (1..120).mapNotNull { s ->
            after.update(VoiceGuide.Input(s * 1000L, l, 1, 10.0 + s * 5, onRoute = true, speedMps = 5.0, bike = true))
        }
        assertTrue("$said", said.none { it is Phrase.Start })
    }

    /** The process died before the route was ever reached: the restarted guide still says the start sentence once. */
    @Test fun aRestartBeforeTheStartSentenceStillSaysItOnce() {
        val l = line(3000, emptyMap())
        val before = VoiceGuide()
        assertNull(before.update(VoiceGuide.Input(0, l, 1, null, onRoute = false, speedMps = null, bike = true)))
        val after = VoiceGuide().apply { continueTour(before.memory()) }
        val said = (1..60).mapNotNull { s ->
            after.update(VoiceGuide.Input(s * 1000L, l, 1, 10.0 + s * 5, onRoute = true, speedMps = 5.0, bike = true))
        }
        assertEquals(1, said.count { it is Phrase.Start })
    }

    /** A second tour in the same service instance starts afresh (the guide was never reset between tours). */
    @Test fun aNewTourSaysItsStartSentence() {
        val l = line(3000, emptyMap())
        val g = VoiceGuide()
        assertTrue(g.update(VoiceGuide.Input(0, l, 1, 0.0, onRoute = true, speedMps = 5.0, bike = true)) is Phrase.Start)
        g.newTour()
        assertTrue(g.update(VoiceGuide.Input(60_000, l, 1, 0.0, onRoute = true, speedMps = 5.0, bike = true)) is Phrase.Start)
    }

    /**
     * Indoors before the start there is no "No GPS signal"; a poor fix that comes and goes says it once (owner 2026-11-01,
     * a restaurant: "einmal und dann erst wieder, wenn es einige Zeit gehabt hat und wir wieder gelaufen sind").
     */
    @Test fun noGpsOnlyAfterTheStart_onceWhileTheFixComesAndGoes() {
        val l = line(3000, emptyMap())
        val g = VoiceGuide()
        val off = VoiceGuide.Input(0, l, 1, null, onRoute = false, speedMps = null, bike = true, gpsLost = true)
        assertNull(g.update(off))
        assertNull("not before the start", g.update(off.copy(nowMs = 60_000)))
        val on = VoiceGuide.Input(70_000, l, 1, 100.0, onRoute = true, speedMps = 5.0, bike = true)
        assertTrue(g.update(on) is Phrase.Start)
        val said = mutableListOf<Pair<Long, Phrase>>()
        // lost 160 s, one good fix, lost 160 s … for 20 minutes
        var t = 80_000L
        while (t < 80_000L + 20 * 60_000L) {
            for (k in 0 until 160) g.update(on.copy(nowMs = t + k * 1000L, gpsLost = true))?.let { said += (t + k * 1000L) to it }
            g.update(on.copy(nowMs = t + 161_000L))?.let { said += (t + 161_000L) to it }
            t += 162_000L
        }
        val noGps = said.filter { it.second == Phrase.NoGps }.map { it.first }
        assertEquals("$noGps", 1, noGps.size)
    }

    /** Said again only after the GPS was good for 2 min while walking – and 10 min after the last time. */
    @Test fun noGpsAgainOnlyAfterGoodGpsWhileWalking() {
        val l = line(3000, emptyMap())
        val g = VoiceGuide()
        val on = VoiceGuide.Input(0, l, 1, 100.0, onRoute = true, speedMps = 1.4, bike = false)
        assertTrue(g.update(on) is Phrase.Start)
        fun lost(from: Long) = (0..170).mapNotNull { k -> g.update(on.copy(nowMs = from + k * 1000L, gpsLost = true)) }
        assertEquals(listOf(Phrase.NoGps), lost(20_000))
        // 3 min good GPS but standing (a restaurant table by the window): not re-armed
        (0..180).forEach { k -> g.update(on.copy(nowMs = 200_000L + k * 1000L, quiet = true)) }
        assertEquals(emptyList<Phrase>(), lost(800_000))
        // 3 min good GPS while walking: re-armed – said again (more than 10 min after the first)
        (0..180).forEach { k -> g.update(on.copy(nowMs = 1_000_000L + k * 1000L)) }
        assertEquals(listOf(Phrase.NoGps), lost(1_200_000))
    }

    /** Owner 2026-11-01: a new route that starts behind the user says so – "Neue Route. Bitte umdrehen." */
    @Test fun aNewRouteBehindSaysTurnRound() {
        val l = line(1000, mapOf(300 to JunctionCue.LEFT))
        val g = VoiceGuide()
        assertTrue(g.update(VoiceGuide.Input(0, l, 1, 0.0, onRoute = true, speedMps = 1.4, bike = false)) is Phrase.Start)
        val back = line(800, mapOf(40 to JunctionCue.RIGHT))
        val said = g.update(VoiceGuide.Input(20_000, back, 2, 0.0, onRoute = true, speedMps = 1.4, bike = false, rerouted = true, turnRound = true))
        assertEquals(Phrase.NewRoute(null, turnRound = true), said)
    }

    /** Rosellen replay (owner 2026-11-01): the first re-route came before the start; the next ones were never said. */
    @Test fun everyNewRouteIsSaid() {
        val g = VoiceGuide()
        val first = line(3000, emptyMap())
        assertNull(g.update(VoiceGuide.Input(0, first, 1, null, onRoute = false, speedMps = null, bike = true)))
        val second = line(2800, emptyMap())
        assertTrue(g.update(VoiceGuide.Input(5_000, second, 2, 0.0, onRoute = true, speedMps = 5.0, bike = true, rerouted = true)) is Phrase.Start)
        val third = line(2900, mapOf(40 to JunctionCue.RIGHT))
        val said = g.update(VoiceGuide.Input(60_000, third, 3, 0.0, onRoute = true, speedMps = 5.0, bike = true, rerouted = true, turnRound = true))
        assertEquals(Phrase.NewRoute(null, turnRound = true), said)
        val fourth = line(2700, emptyMap())
        assertTrue(g.update(VoiceGuide.Input(120_000, fourth, 4, 0.0, onRoute = true, speedMps = 5.0, bike = true, rerouted = true)) is Phrase.NewRoute)
    }

    /** Rosellen replay (owner 2026-11-01): back on the route 25 s after "Abseits der Route." – said. */
    @Test fun backOnTheRouteSoonAfterIsSaid() {
        val l = line(3000, emptyMap())
        val g = VoiceGuide()
        val base = VoiceGuide.Input(0, l, 1, 100.0, onRoute = true, speedMps = 5.0, bike = true)
        assertTrue(g.update(base) is Phrase.Start)
        assertEquals(Phrase.OffRoute, g.update(base.copy(nowMs = 30_000, onRoute = false, offRoute = true)))
        assertEquals(Phrase.BackOnRoute, g.update(base.copy(nowMs = 55_000, alongM = 400.0)))
    }

    /** Jumping fixes open and close the question again and again: "off the route" at most every 2 min, no "back on". */
    @Test fun aFlappingOffRouteQuestionIsSaidAtMostEvery2Minutes() {
        val l = line(3000, emptyMap())
        val g = VoiceGuide()
        val base = VoiceGuide.Input(0, l, 1, 100.0, onRoute = true, speedMps = 5.0, bike = true)
        assertTrue(g.update(base) is Phrase.Start)
        val said = mutableListOf<Phrase>()
        var t = 15_000L
        repeat(8) { // open 10 s, closed 10 s → 160 s
            g.update(base.copy(nowMs = t, onRoute = false, offRoute = true))?.let { said += it }
            g.update(base.copy(nowMs = t + 10_000))?.let { said += it }
            t += 20_000
        }
        assertEquals("$said", 2, said.count { it == Phrase.OffRoute })
        assertTrue("$said", said.none { it == Phrase.BackOnRoute })
    }

    /** "Ziel erreicht" once: a second arrival question after "Not yet" within 5 min says nothing. */
    @Test fun arrivedAtMostOnceIn5Minutes() {
        val g = VoiceGuide()
        assertEquals(Phrase.Arrived, g.event(Phrase.Arrived, 1_000))
        assertNull(g.event(Phrase.Arrived, 120_000))
        assertEquals(Phrase.Arrived, g.event(Phrase.Arrived, 400_000))
        // a restart keeps it
        val after = VoiceGuide().apply { continueTour(g.memory()) }
        assertNull(after.event(Phrase.Arrived, 420_000))
    }

    /** Owner 2026-11-01: in a restaurant, phone in the pocket – no "No GPS signal", no "off the route" while standing still. */
    @Test fun standingStillSaysNothing() {
        val l = line(3000, emptyMap())
        val g = VoiceGuide()
        val base = VoiceGuide.Input(0, l, 1, 100.0, onRoute = true, speedMps = 5.0, bike = true)
        assertTrue(g.update(base) is Phrase.Start)
        val sitting = (1..60).mapNotNull { k -> g.update(base.copy(nowMs = 20_000L + k * 10_000L, gpsLost = true, quiet = true)) } +
            listOfNotNull(g.update(base.copy(nowMs = 700_000, onRoute = false, offRoute = true, quiet = true)))
        assertTrue("$sitting", sitting.isEmpty())
        // walking again, GPS lost in a forest: said
        val walking = (1..160).mapNotNull { k -> g.update(base.copy(nowMs = 800_000L + k * 1_000L, gpsLost = true)) }
        assertEquals(listOf(Phrase.NoGps), walking)
    }

    /** Review recording L1: a plan edited while off route builds a new line – no false "back on the route". */
    @Test fun aNewLineFromTheAppIsNoBackOnRoute() {
        val l = line(3000, emptyMap())
        val g = VoiceGuide()
        val base = VoiceGuide.Input(0, l, 1, 100.0, onRoute = true, speedMps = 5.0, bike = true)
        g.update(base)
        assertEquals(Phrase.OffRoute, g.update(base.copy(nowMs = 20_000, onRoute = false, offRoute = true)))
        g.lineFromApp()
        val said = (1..20).mapNotNull { g.update(base.copy(nowMs = 100_000L + it * 1000, version = 2)) }
        assertTrue("$said", said.none { it == Phrase.BackOnRoute })
    }

    /** Owner 2026-11-06: the plan changed during the tour, the new line ridden – "Weiter geht's" with its length, once. */
    @Test fun aChangedPlanDuringTheTourSaysCarryOnOnce() {
        val l = line(3000, emptyMap())
        val g = VoiceGuide()
        val base = VoiceGuide.Input(0, l, 1, 100.0, onRoute = true, speedMps = 5.0, bike = true)
        val first = (0..5).mapNotNull { g.update(base.copy(nowMs = it * 1000L)) }
        assertTrue("$first", first.any { it is Phrase.Start && !it.carryOn })
        g.lineFromApp()
        val l2 = line(2000, emptyMap())
        val after = (1..30).mapNotNull { g.update(base.copy(nowMs = 60_000L + it * 1000L, line = l2, version = 2, alongM = 50.0 + it * 5)) }
        val carry = after.filterIsInstance<Phrase.Start>()
        assertEquals("$after", 1, carry.size)
        assertTrue(carry.single().carryOn)
        assertEquals(1950.0, carry.single().distanceM, 10.0)
    }

    /** Before the start sentence a line from the app is the start, not "Weiter geht's". */
    @Test fun aLineFromTheAppBeforeTheStartIsTheStart() {
        val l = line(3000, emptyMap())
        val g = VoiceGuide()
        g.lineFromApp()
        val said = (0..5).mapNotNull { g.update(VoiceGuide.Input(it * 1000L, l, 1, 100.0, onRoute = true, speedMps = 5.0, bike = true)) }
        assertEquals("$said", listOf(false), said.filterIsInstance<Phrase.Start>().map { it.carryOn })
    }

    /** Owner 2026-11-01: on the way, short GPS gaps (a bridge, a few trees) never say anything. */
    @Test fun shortGapsOnTheWaySayNothing() {
        val l = line(3000, emptyMap())
        val g = VoiceGuide()
        val base = VoiceGuide.Input(0, l, 1, 100.0, onRoute = true, speedMps = 5.0, bike = true)
        g.update(base)
        val said = mutableListOf<Phrase>()
        var t = 20_000L
        repeat(20) { // 90 s lost, a fix, again – half an hour of patchy GPS
            for (k in 0 until 90) g.update(base.copy(nowMs = t + k * 1000L, gpsLost = true))?.let { said += it }
            g.update(base.copy(nowMs = t + 91_000L))?.let { said += it }
            t += 92_000L
        }
        assertTrue("$said", said.none { it == Phrase.NoGps })
    }

    /**
     * Field bug (owner 2026-11-07, Hilden bike plan: "warum keine Ansage, ich bin auf einer anderen Straße"): right by
     * 82.5° from a quiet street (class 6) onto a road (class 24), 19 m along it, left by 88.2° onto a footway (class 4).
     * One sentence for both – and the "onto" belongs to the left: the right turn goes onto the road, not "auf den Weg".
     */
    @Test fun anOffsetAlongAStreetIsSaidAsTwoTurns() {
        val pts = ArrayList<GeoPoint>()
        var lat = 51.1690; var lon = 6.9250
        fun leg(deg: Double, m: Int, type: String) {
            val r = Math.toRadians(deg)
            repeat(m) {
                pts[pts.size - 1] = pts.last().copy(way = com.onpath.app.routing.WayAttrs(type, "asphalt"))
                lat += kotlin.math.cos(r) / 111_320.0
                lon += kotlin.math.sin(r) / (111_320.0 * kotlin.math.cos(Math.toRadians(lat)))
                pts += GeoPoint(lat, lon)
            }
        }
        fun hint(cue: JunctionCue, angle: Float, shape: JunctionShape) {
            pts[pts.size - 1] = pts.last().copy(turn = cue, turnAngle = angle, shape = shape)
        }
        pts += GeoPoint(lat, lon)
        leg(55.0, 400, "quiet_road")
        hint(JunctionCue.RIGHT, 82.5f, JunctionShape(79f, 6, 24, listOf(JunctionShape.Branch(-103f, 24))))
        leg(137.5, 19, "road")
        hint(JunctionCue.LEFT, -88.2f, JunctionShape(-88f, 24, 4, listOf(JunctionShape.Branch(1f, 24), JunctionShape.Branch(84f, 4))))
        leg(55.0, 300, "footway")
        val g = VoiceGuide()
        var t = 0L
        val said = (0..127).mapNotNull { k -> t += 1000; g.update(VoiceGuide.Input(t, pts, 1, k * 5.5, true, 5.5, true)) }
            .filterIsInstance<Phrase.Junction>()
        val now = said.first { it.inM == 0.0 }
        assertEquals(Turn.RIGHT, now.turn)
        // and the left, 19 m on, its own "now" once the right is ridden (owner 2026-11-07: never swallowed)
        assertTrue("$said", said.any { it.inM == 0.0 && it.turn == Turn.LEFT && it.onto == WayKind.WAY })
        assertEquals(Turn.LEFT, now.then)
        assertNull("the right turn goes onto the road – not \"auf den Weg\"", now.onto)
        assertEquals("the left goes onto the footway", WayKind.WAY, now.thenOnto)
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("Gleich rechts, danach gleich links auf den Weg.", VoiceText.of(now))
        } finally { java.util.Locale.setDefault(saved) }
        // the early sentence names the right only
        assertTrue("$said", said.any { it.inM > 0 && it.turn == Turn.RIGHT && it.onto == null })
    }

    /**
     * Possible field bug (owner 2026-11-07, 3rd demo run on the same line from 3 m: the right at 238 m never said): a
     * simulation restarted from the start of the same line – after runs that ended part-way, with a few unmatched fixes
     * between – says its turns again.
     */
    @Test fun aSimulationRestartedOnTheSameLineSaysItsTurnsAgain() {
        val l = line(800, mapOf(240 to JunctionCue.RIGHT, 530 to JunctionCue.LEFT))
        val g = VoiceGuide()
        var t = 0L
        val speed = 7.5
        fun run(from: Double, to: Double): List<Phrase.Junction> {
            val out = ArrayList<Phrase.Junction>()
            var m = from
            while (m <= to) {
                t += 1000
                g.update(VoiceGuide.Input(t, l, 1, m, onRoute = true, speedMps = speed, bike = true))?.let { if (it is Phrase.Junction) out += it }
                m += speed
            }
            // the demo ends: two fixes elsewhere (not matched)
            repeat(2) { t += 1000; g.update(VoiceGuide.Input(t, l, 1, null, onRoute = false, speedMps = 0.0, bike = true)) }
            return out
        }
        run(0.0, 105.0)   // run 1 ends before the right
        run(0.0, 50.0)    // run 2: from the start, 7 s
        val third = run(3.0, 800.0)
        assertTrue("$third", third.any { it.inM == 0.0 && it.turn == Turn.RIGHT })
        assertTrue("$third", third.any { it.inM == 0.0 && it.turn == Turn.LEFT })
        // a run that got past the right before the restart: said again too
        run(0.0, 300.0)
        val again = run(0.0, 800.0)
        assertTrue("$again", again.any { it.inM == 0.0 && it.turn == Turn.RIGHT })
    }

    // ---- a due "now" is never swallowed (owner field ride 2026-11-07, tour 217; docs/design/voice-guidance.md §9) ----

    private fun nows(said: List<Pair<Int, Phrase>>, turn: Turn) =
        said.filter { (_, p) -> p is Phrase.Junction && p.inM == 0.0 && p.turn == turn }.map { it.first }

    /**
     * Tour 217, 12:32:19 "Gleich links, danach gleich rechts." – the right 50 m on was never said again, and 12:29:58
     * "Gleich rechts, danach gleich leicht rechts." – the bear right 93 m on neither: a turn said as "danach" gets its
     * own "now" once the one before it is ridden – also one only 20 m on.
     */
    @Test fun aTurnSaidAsDanachGetsItsOwnNow() {
        for ((second, speed) in listOf(550 to 4.1, 590 to 6.0, 530 to 5.0)) {
            val l = line(2000, mapOf(500 to JunctionCue.LEFT, second to JunctionCue.RIGHT))
            val said = startless(ride(VoiceGuide(), l, bike = true, speed = speed))
            val first = said.firstOrNull { (_, p) -> p is Phrase.Junction && p.inM == 0.0 && p.turn == Turn.LEFT }?.second as? Phrase.Junction
                ?: throw AssertionError("right at $second: no now for the left – $said")
            assertEquals("right at $second named with the left", Turn.RIGHT, first.then)
            val right = nows(said, Turn.RIGHT)
            assertEquals("right at $second: its own now after the left is ridden – $said", 1, right.size)
            assertTrue("right at $second said after the left (${right[0]} m)", right[0] >= 500)
            assertTrue("right at $second said before reaching it (${right[0]} m)", right[0] < second)
        }
    }

    /**
     * Tour 217, 12:30:29 "Stopp 2 erreicht. Weiter nach …" came 8 s before a sharp left; "Gleich scharf links" waited the
     * 3 s pause (12:30:32) and cut the stop sentence: the turn goes first, the stop sentence after it – both said.
     */
    @Test fun aTurnDueGoesBeforeAWaitingSentence_andThatOneIsStillSaid() {
        val l = line(1200, mapOf(390 to JunctionCue.SHARP_LEFT))
        val stops = listOf(NavStop(l[0], 0.0), NavStop(l[35], 350.0, "Stopp"), NavStop(l.last(), 1200.0))
        val said = startless(ride(VoiceGuide(), l, bike = true, speed = 4.1) { m, i ->
            i.copy(stops = stops, visited = if (m >= 352) setOf(0, 1) else setOf(0)) })
        val turn = said.indexOfFirst { (_, p) -> p is Phrase.Junction && p.inM == 0.0 }
        val stop = said.indexOfFirst { (_, p) -> p is Phrase.StopReached }
        assertTrue("$said", turn >= 0 && stop >= 0)
        assertTrue("the turn's now ≥ 30 m before it (said at ${said[turn].first} m): $said", 390 - said[turn].first >= 30)
        assertTrue("the turn before the stop sentence: $said", turn < stop)
    }

    /** A turn due 2 s after "Neue Route." is said at once (the speaker cuts what is being said) – no 3 s pause. */
    @Test fun aTurnDueRightAfterAnotherSentenceIsNotHeld() {
        val g = VoiceGuide()
        g.update(VoiceGuide.Input(0, line(3000, emptyMap()), 1, 100.0, onRoute = true, speedMps = 5.0, bike = true))
        val l = line(1000, mapOf(50 to JunctionCue.LEFT))
        val base = VoiceGuide.Input(60_000, l, 2, 5.0, onRoute = true, speedMps = 5.0, bike = true, rerouted = true)
        assertEquals(Phrase.NewRoute(null), g.update(base))
        assertNull(g.update(base.copy(nowMs = 61_000, alongM = 10.0)))
        val p = g.update(base.copy(nowMs = 62_000, alongM = 15.0))
        assertTrue("$p", p is Phrase.Junction && p.inM == 0.0 && p.turn == Turn.LEFT)
    }

    /** A single fix jumping right up to the turn neither says it nor uses it up: the next fixes say it in time. */
    @Test fun aGpsJumpCloseToTheTurnDoesNotSwallowIt() {
        val l = line(1000, mapOf(500 to JunctionCue.LEFT))
        val said = startless(ride(VoiceGuide(), l, bike = true, speed = 6.0, from = 300) { m, i -> if (m == 455) i.copy(alongM = 496.0) else i })
        val left = nows(said, Turn.LEFT)
        assertEquals("$said", 1, left.size)
        assertTrue("$said", 500 - left[0] in 30..50)
    }

    /** No fix for 20 s (a tunnel), the first one again 11 m before the turn: still said – 3 m before it is passed. */
    @Test fun aFixGapEndingJustBeforeTheTurnStillSaysIt_butNotWhenItIsPassed() {
        val l = line(1000, mapOf(500 to JunctionCue.RIGHT))
        for ((resume, expect) in listOf(489 to true, 497 to false)) {
            val g = VoiceGuide()
            val base = VoiceGuide.Input(0, l, 1, 300.0, onRoute = true, speedMps = 4.0, bike = true)
            g.update(base)
            assertNull(g.update(base.copy(nowMs = 30_000, alongM = 380.0)))
            val p = g.update(base.copy(nowMs = 52_000, alongM = resume.toDouble()))
            assertEquals("resumed $resume m: $p", expect, p is Phrase.Junction && p.turn == Turn.RIGHT)
        }
    }

    /**
     * Tour 217, 12:31:06 "Gleich rechts auf die Straße." 37 m before the road, then 55 s waiting there: when riding on the
     * turn is said once more.
     */
    @Test fun aNowSaidBeforeAStopIsSaidAgainWhenRidingOn() {
        val l = line(1000, mapOf(500 to JunctionCue.RIGHT))
        val g = VoiceGuide()
        val base = VoiceGuide.Input(0, l, 1, 300.0, onRoute = true, speedMps = 4.0, bike = true)
        g.update(base)
        val first = (0..10).mapNotNull { s -> g.update(base.copy(nowMs = 20_000L + s * 1000, alongM = 455.0 + s * 1.0))?.let { s to it } }
        assertEquals("$first", 1, first.count { (_, p) -> p is Phrase.Junction && p.inM == 0.0 })
        // standing 466 m … 50 s
        val standing = (1..50).mapNotNull { s -> g.update(base.copy(nowMs = 31_000L + s * 1000, alongM = 466.0, speedMps = 0.0, quiet = s > 10)) }
        assertTrue("nothing while standing: $standing", standing.isEmpty())
        val on = (1..5).mapNotNull { s -> g.update(base.copy(nowMs = 81_000L + s * 1000, alongM = 466.0 + s * 3, speedMps = 3.0)) }
        assertEquals("said once more when riding on: $on", 1, on.count { it is Phrase.Junction && (it as Phrase.Junction).turn == Turn.RIGHT })
    }

    /** A GPS speed spike (40 m/s on one fix) made the "now" come 100 m early: it is said once more at the right time. */
    @Test fun aSpeedSpikeDoesNotUseUpTheNow() {
        val l = line(1000, mapOf(500 to JunctionCue.LEFT))
        val said = startless(ride(VoiceGuide(), l, bike = true, speed = 5.0, from = 300) { m, i -> if (m == 400) i.copy(speedMps = 40.0) else i })
        val left = nows(said, Turn.LEFT)
        assertTrue("a now 25–45 m before the turn: $said", left.any { 500 - it in 25..45 })
    }
}
