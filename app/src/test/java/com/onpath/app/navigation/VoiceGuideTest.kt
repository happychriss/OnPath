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

    @Test fun bikeOneSentencePerJunction_earlyByTheSpeed() {
        val l = line(2000, mapOf(500 to JunctionCue.LEFT, 1500 to JunctionCue.RIGHT))
        val said = startless(ride(VoiceGuide(), l, bike = true, speed = 6.0))
        assertEquals(2, said.size)
        // 15 s × 6 m/s = 90 m before
        assertEquals(410, said[0].first)
        assertEquals(Turn.LEFT, (said[0].second as Phrase.Junction).turn)
        // faster: earlier, up to 200 m
        val fast = startless(ride(VoiceGuide(), l, bike = true, speed = 12.0))
        assertEquals(320, fast[0].first)
        val veryFast = startless(ride(VoiceGuide(), l, bike = true, speed = 20.0))
        assertEquals(300, veryFast[0].first)
    }

    @Test fun onFoot_40SecondsWithin40To80m() {
        val l = line(1000, mapOf(500 to JunctionCue.LEFT))
        val said = startless(ride(VoiceGuide(), l, bike = false, speed = 1.4))
        assertEquals(445, said.single().first) // 40 s × 1.4 = 56 m → the fix at 445
    }

    @Test fun onlyWhereWeVibrate_notWhereTheRouteFollowsTheWay() {
        // the street bends right, a smaller way goes on straight: CueRule says no – the voice says nothing
        val along = JunctionShape(30f, 20, 20, listOf(JunctionShape.Branch(-5f, 6)))
        val l = line(1000, mapOf(500 to JunctionCue.SLIGHT_RIGHT), along)
        assertEquals(emptyList<Pair<Int, Phrase>>(), startless(ride(VoiceGuide(), l, bike = true, speed = 6.0)))
    }

    @Test fun twoCloseTurnsInOneSentence() {
        val l = line(2000, mapOf(500 to JunctionCue.LEFT, 540 to JunctionCue.RIGHT))
        val j = startless(ride(VoiceGuide(), l, bike = true, speed = 6.0)).single().second as Phrase.Junction
        assertEquals(Turn.LEFT, j.turn)
        assertEquals(Turn.RIGHT, j.then)
    }

    @Test fun aLongQuietStretchAfterwardsIsSaid() {
        val l = line(5000, mapOf(500 to JunctionCue.RIGHT, 4000 to JunctionCue.LEFT))
        val said = startless(ride(VoiceGuide(), l, bike = true, speed = 6.0)).map { it.second as Phrase.Junction }
        assertEquals(3500.0, said[0].straightM!!, 1.0)
        assertNull("1000 m to the end: under 2 km on the bike", said[1].straightM)
    }

    @Test fun theKindOfWayWhenItChanges() {
        // off the road onto a track (bike classes: 22 road → 4 track)
        val offRoad = JunctionShape(-85f, 22, 4, listOf(JunctionShape.Branch(0f, 22)))
        val l = line(1000, mapOf(500 to JunctionCue.LEFT), offRoad)
        val j = startless(ride(VoiceGuide(), l, bike = true, speed = 6.0)).single().second as Phrase.Junction
        assertEquals(WayKind.TRACK, j.onto)
        // street to street: nothing about the way
        val streets = JunctionShape(-85f, 6, 6, listOf(JunctionShape.Branch(0f, 6)))
        val k = startless(ride(VoiceGuide(), line(1000, mapOf(500 to JunctionCue.LEFT), streets), bike = true, speed = 6.0)).single().second
        assertNull((k as Phrase.Junction).onto)
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
        assertEquals(Phrase.BackOnRoute, g.update(base.copy(nowMs = 40_000)))
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

    @Test fun noGpsOnceAfter30Seconds_quietWhilePaused() {
        val g = VoiceGuide()
        val l = line(3000, emptyMap())
        val base = VoiceGuide.Input(0, l, 1, 100.0, onRoute = true, speedMps = 5.0, bike = true)
        g.update(base)
        assertNull(g.update(base.copy(nowMs = 20_000, gpsLost = true)))
        assertEquals(Phrase.NoGps, g.update(base.copy(nowMs = 51_000, gpsLost = true)))
        assertNull(g.update(base.copy(nowMs = 90_000, gpsLost = true)))
        assertNull(g.update(base.copy(nowMs = 200_000, gpsLost = true, paused = true)))
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
        assertEquals(41, said.size)
    }
}
