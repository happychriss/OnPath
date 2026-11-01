package com.onpath.app.navigation

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import com.onpath.app.routing.JunctionShape
import com.onpath.app.routing.JunctionShape.Branch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos

/**
 * Close opposite turns are one sideways step (owner 2026-11-01, Hilden demo tour). Lines are drawn from legs (heading °,
 * metres), a point every metre; a hint sits where a leg starts. The Hilden case: along Poststraße, 61° right onto
 * Benrather Straße, 8 m later 75° left onto the pedestrian crossing into the park = straight on across the road.
 */
class JogRuleTest {
    private val lat0 = 51.1673
    private val lon0 = 6.9274

    /** [legs] = (heading in degrees, metres, the hint at the leg's start or null). */
    private fun line(vararg legs: Triple<Double, Int, JunctionCue?>, angles: List<Float> = emptyList()): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        var lat = lat0; var lon = lon0
        var hint = 0
        out += GeoPoint(lat, lon)
        legs.forEachIndexed { k, (deg, m, cue) ->
            if (cue != null) {
                val a = angles.getOrElse(hint++) { 0f }
                // a T-junction shape: another street of the same class goes on – CueRule says "worth a cue"
                out[out.size - 1] = out.last().copy(turn = cue, turnAngle = a,
                    shape = JunctionShape(a, 6, 6, listOf(Branch(if (a > 0) -90f else 90f, 6))))
            }
            val r = Math.toRadians(deg)
            repeat(m) {
                lat += cos(r) / 111_320.0
                lon += Math.sin(r) / (111_320.0 * cos(Math.toRadians(lat)))
                out += GeoPoint(lat, lon)
            }
        }
        return out
    }

    private fun needed(l: List<GeoPoint>, hike: Boolean = false) = JogRule.needed(l, hike).toList()

    @Test fun crossingTheRoadOnAStepIsStraightOn() {
        // Poststraße heading 163°, right onto Benrather Straße (224°), 8 m on, left onto the crossing (150°) into the park
        val l = line(Triple(163.0, 60, null), Triple(224.0, 8, JunctionCue.RIGHT), Triple(150.0, 60, JunctionCue.LEFT),
            angles = listOf(61f, -74f))
        assertEquals(listOf(false, false), needed(l))
    }

    @Test fun anOffsetThatEndsInATurnIsOneCue() {
        // right 10 m along the street, then left – but the new way runs off 90° to the left of the old heading
        val l = line(Triple(0.0, 60, null), Triple(90.0, 10, JunctionCue.RIGHT), Triple(270.0, 60, JunctionCue.SHARP_LEFT),
            angles = listOf(90f, -180f + 1f))
        assertEquals("only the left (the step's way)", listOf(false, true), needed(l))
    }

    @Test fun turnsFartherApartStayTwoCues() {
        val l = line(Triple(0.0, 60, null), Triple(90.0, 30, JunctionCue.RIGHT), Triple(0.0, 60, JunctionCue.LEFT),
            angles = listOf(90f, -90f))
        assertEquals(listOf(true, true), needed(l))
    }

    @Test fun onFootTheStepIsShorter() {
        val l = line(Triple(0.0, 60, null), Triple(90.0, 15, JunctionCue.RIGHT), Triple(0.0, 60, JunctionCue.LEFT),
            angles = listOf(90f, -90f))
        assertEquals("bike: 15 m = one step, straight", listOf(false, false), needed(l))
        assertEquals("on foot: 15 m apart = two turns", listOf(true, true), needed(l, hike = true))
    }

    @Test fun twoTurnsTheSameWayAreLeftToTheMerge() {
        val l = line(Triple(0.0, 60, null), Triple(90.0, 10, JunctionCue.RIGHT), Triple(180.0, 60, JunctionCue.RIGHT),
            angles = listOf(90f, 90f))
        assertEquals(listOf(true, true), needed(l))
    }

    @Test fun aRoundaboutIsNeverPartOfAStep() {
        val l = line(Triple(0.0, 60, null), Triple(90.0, 10, JunctionCue.ROUNDABOUT), Triple(0.0, 60, JunctionCue.LEFT),
            angles = listOf(90f, -90f))
        assertEquals(true, needed(l)[0])
    }

    @Test fun theBendBeforeTheStepDoesNotCount() {
        // Hilden: Poststraße bends 46° right 18 m before Benrather Straße – the step is measured after that bend
        val l = line(Triple(117.0, 60, null), Triple(163.0, 18, JunctionCue.SLIGHT_RIGHT), Triple(224.0, 8, JunctionCue.RIGHT),
            Triple(150.0, 60, JunctionCue.LEFT), angles = listOf(46f, 61f, -74f))
        assertArrayEquals(booleanArrayOf(true, false, false), JogRule.needed(l, false))
    }

    @Test fun vibrationAndVoiceFollowTheRule() {
        val l = line(Triple(163.0, 200, null), Triple(224.0, 8, JunctionCue.RIGHT), Triple(150.0, 200, JunctionCue.LEFT),
            angles = listOf(61f, -74f))
        val cues = JunctionCues.bike()
        val felt = (0..400 step 2).mapNotNull { m -> cues.update(l, 1, m.toDouble(), true, 5.5) }
        assertEquals("no vibration at the crossing", emptyList<JunctionCue>(), felt)
        val g = VoiceGuide()
        var t = 0L
        val said = (0..400 step 5).mapNotNull { m -> t += 1000; g.update(VoiceGuide.Input(t, l, 1, m.toDouble(), true, 5.5, true)) }
        assertEquals("only the start sentence", 1, said.size)
    }
}
