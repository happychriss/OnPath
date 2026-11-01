package com.onpath.app.navigation

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import com.onpath.app.routing.JunctionShape
import com.onpath.app.routing.JunctionShape.Branch
import com.onpath.app.routing.TurnDetector
import com.onpath.app.routing.TurnKind
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** The screen shows the junctions the voice names (owner 2026-11-01, Hilden). Lines from legs, a point every metre. */
class ScreenTurnsTest {
    private fun line(vararg legs: Triple<Double, Int, Pair<JunctionCue, Float>?>, hints: Boolean = true): List<GeoPoint> {
        val out = arrayListOf(GeoPoint(51.1673, 6.9274))
        var lat = out[0].lat; var lon = out[0].lon
        for ((deg, m, hint) in legs) {
            if (hint != null && hints) {
                val (cue, a) = hint
                out[out.size - 1] = out.last().copy(turn = cue, turnAngle = a,
                    shape = JunctionShape(a, 6, 6, listOf(Branch(if (a > 0) -90f else 90f, 6))))
            }
            val r = Math.toRadians(deg)
            repeat(m) {
                lat += cos(r) / 111_320.0; lon += sin(r) / (111_320.0 * cos(Math.toRadians(lat)))
                out += GeoPoint(lat, lon)
            }
        }
        return out
    }

    @Test fun theCrossingStepIsNotShown() {
        val l = line(Triple(163.0, 80, null), Triple(224.0, 8, JunctionCue.RIGHT to 61f), Triple(150.0, 80, JunctionCue.LEFT to -74f))
        assertEquals(emptyList<Any>(), ScreenTurns.of(l, hike = false))
    }

    @Test fun twoRightsInAParkAreTwoRightsNotAHairpin() {
        // Hilden park: right, 28 m on another right (46°) – the geometry merged them into "hairpin right"
        val l = line(Triple(0.0, 80, null), Triple(92.0, 28, JunctionCue.RIGHT to 92f), Triple(138.0, 80, JunctionCue.SLIGHT_RIGHT to 46f))
        assertEquals(listOf(TurnKind.RIGHT, TurnKind.SLIGHT_RIGHT), ScreenTurns.of(l, hike = false).map { it.kind })
        val geometry = TurnDetector.turns(line(Triple(0.0, 80, null), Triple(92.0, 28, null), Triple(138.0, 80, null)))
        assertEquals("before: one sharp / hairpin turn", 1, geometry.size)
    }

    @Test fun aTrackWithoutHintsKeepsTheGeometry() {
        val l = line(Triple(0.0, 80, null), Triple(90.0, 80, JunctionCue.RIGHT to 90f), hints = false)
        assertEquals(TurnDetector.turns(l), ScreenTurns.of(l, hike = false))
        assertEquals(TurnKind.RIGHT, ScreenTurns.of(l, hike = false).single().kind)
    }

    @Test fun roundaboutsAreLeftToTheRoundaboutCard() {
        val l = line(Triple(0.0, 80, null), Triple(90.0, 80, JunctionCue.ROUNDABOUT to 90f), Triple(0.0, 80, JunctionCue.LEFT to -90f))
        assertEquals(listOf(TurnKind.LEFT), ScreenTurns.of(l, hike = false).map { it.kind })
    }
}
