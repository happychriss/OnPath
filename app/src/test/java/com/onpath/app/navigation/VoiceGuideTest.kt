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
        // 8 s × 6 m/s = 48 m before, no number ("Gleich links.")
        assertEquals(455, said[1].first)
        assertEquals(0.0, (said[1].second as Phrase.Junction).inM, 0.0)
        // faster: earlier
        val fast = startless(ride(VoiceGuide(), l, bike = true, speed = 12.0))
        assertEquals(listOf(200, 405), fast.take(2).map { it.first })
        val veryFast = startless(ride(VoiceGuide(), l, bike = true, speed = 20.0))
        // (the early one waits for the 10 s after the start sentence: 200 m at 72 km/h)
        assertEquals(listOf(200, 350), veryFast.take(2).map { it.first })
    }

    /** A junction that comes into view too late for the early sentence gets only "Gleich …". */
    @Test fun bikeAJunctionRightAfterAnotherGetsOnlyTheSecondStage() {
        val l = line(2000, mapOf(500 to JunctionCue.LEFT, 590 to JunctionCue.RIGHT))
        val j = startless(ride(VoiceGuide(), l, bike = true, speed = 6.0)).map { it.second as Phrase.Junction }
        assertEquals(listOf(Turn.LEFT, Turn.LEFT), j.map { it.turn })
        assertEquals(Turn.RIGHT, j[1].then)   // 90 m on: "Gleich links, danach rechts."
    }

    /** Rosellen replay: the second turn 80 m after the first must not be lost in the pause between sentences. */
    @Test fun bikeAJunctionJustAheadIsSaidDuringThePause() {
        val l = line(2000, mapOf(500 to JunctionCue.SHARP_RIGHT, 620 to JunctionCue.LEFT))
        val said = startless(ride(VoiceGuide(), l, bike = true, speed = 6.0)).map { it.second as Phrase.Junction }
        assertTrue("$said", said.any { it.turn == Turn.LEFT || it.then == Turn.LEFT })
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
        val j = startless(ride(VoiceGuide(), l, bike = true, speed = 6.0)).last().second as Phrase.Junction
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
}
