package com.onpath.app.navigation

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import com.onpath.app.routing.JunctionShape
import com.onpath.app.routing.JunctionShape.Branch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    private fun line(vararg legs: Triple<Double, Int, JunctionCue?>, angles: List<Float> = emptyList(),
                     shapes: List<JunctionShape?> = emptyList()): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        var lat = lat0; var lon = lon0
        var hint = 0
        out += GeoPoint(lat, lon)
        legs.forEachIndexed { k, (deg, m, cue) ->
            if (cue != null) {
                val a = angles.getOrElse(hint) { 0f }
                // a T-junction shape: another street of the same class goes on – CueRule says "worth a cue"
                out[out.size - 1] = out.last().copy(turn = cue, turnAngle = a,
                    shape = shapes.getOrNull(hint) ?: JunctionShape(a, 6, 6, listOf(Branch(if (a > 0) -90f else 90f, 6))))
                hint++
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
        // the piece between the turns is a crossing (class 4): on the bike the step reaches 20 m
        val l = line(Triple(0.0, 60, null), Triple(90.0, 15, JunctionCue.RIGHT), Triple(0.0, 60, JunctionCue.LEFT),
            angles = listOf(90f, -90f), shapes = crossing(90f, -90f))
        assertEquals("bike: 15 m = one step, straight", listOf(false, false), needed(l))
        assertEquals("on foot: 15 m apart = two turns", listOf(true, true), needed(l, hike = true))
    }

    /** Right onto a crossing / footway piece (class 4), left off it onto a street (class 6). */
    private fun crossing(right: Float, left: Float) = listOf(
        JunctionShape(right, 6, 4, listOf(Branch(-90f, 6))), JunctionShape(left, 4, 6, listOf(Branch(90f, 6), Branch(0f, 4))))

    /**
     * Field bug (owner 2026-11-07, Hilden bike plan: "warum keine Ansage, ich bin auf einer anderen Straße"): right by
     * 82.5° from a quiet street (class 6) onto a road (class 24), 19 m along it, left by 88.2° onto a footway (class 4) –
     * heading 55° before and after. Riding 19 m along a street is two turns, not a step across the road: both said.
     */
    @Test fun anOffsetAlongAStreetIsTwoTurns() {
        val l = line(Triple(55.0, 60, null), Triple(137.5, 19, JunctionCue.RIGHT), Triple(55.0, 60, JunctionCue.LEFT),
            angles = listOf(82.5f, -88.2f),
            shapes = listOf(JunctionShape(79f, 6, 24, listOf(Branch(-103f, 24))),
                JunctionShape(-88f, 24, 4, listOf(Branch(1f, 24), Branch(84f, 4)))))
        assertEquals(listOf(true, true), needed(l))
        assertEquals("on foot as before: two turns", listOf(true, true), needed(l, hike = true))
    }

    /** The guard of the narrowed rule: an 18 m crossing piece (class ≤ 4) between the turns stays one straight step. */
    @Test fun aLongCrossingPieceIsStillAStep() {
        val l = line(Triple(0.0, 60, null), Triple(90.0, 18, JunctionCue.RIGHT), Triple(0.0, 60, JunctionCue.LEFT),
            angles = listOf(90f, -90f), shapes = crossing(90f, -90f))
        assertEquals(listOf(false, false), needed(l))
        // along a street (class 6) the same 18 m are two turns; under 12 m still a step
        assertEquals(listOf(true, true), needed(line(Triple(0.0, 60, null), Triple(90.0, 18, JunctionCue.RIGHT),
            Triple(0.0, 60, JunctionCue.LEFT), angles = listOf(90f, -90f))))
        assertEquals(listOf(false, false), needed(line(Triple(0.0, 60, null), Triple(90.0, 10, JunctionCue.RIGHT),
            Triple(0.0, 60, JunctionCue.LEFT), angles = listOf(90f, -90f))))
    }

    /** A line without junction shapes (an old session file, the web) keeps the 20 m step. */
    @Test fun withoutAShapeTheStepKeeps20m() {
        val l = line(Triple(0.0, 60, null), Triple(90.0, 18, JunctionCue.RIGHT), Triple(0.0, 60, JunctionCue.LEFT),
            angles = listOf(90f, -90f)).map { it.copy(shape = null) }
        assertEquals(listOf(false, false), needed(l))
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

    /**
     * Field 2026-11-01 (tour 65, Hilden): the line the recording service navigates is read back from the session file,
     * where turnAngle is 0 – the angle lives in the shape. The step at the start (right, 6.5 m on left, net 2°) must stay
     * quiet there too, and the screen must say "left" for a 86° left.
     */
    @Test fun aLineReadBackFromTheSessionFileKeepsItsAngles() {
        val l = line(Triple(13.0, 20, null), Triple(99.0, 7, JunctionCue.RIGHT), Triple(13.0, 60, JunctionCue.LEFT),
            angles = listOf(88f, -86f))
        fun roundTrip(pts: List<GeoPoint>) = NavSessionStore.fromJson(NavSessionStore.toJson(
            NavSession(NavKind.PLAN, "x", com.onpath.app.routing.Activity.HIKE, 2, pts)))!!.original
        val back = roundTrip(l)
        assertEquals("the file keeps no turnAngle", 0f, back.first { it.turn != null }.turnAngle)
        assertEquals(listOf(false, false), JogRule.needed(back, hike = true).toList())
        val left = line(Triple(0.0, 60, null), Triple(270.0, 60, JunctionCue.LEFT), angles = listOf(-86f))
        val leftBack = roundTrip(left)
        assertEquals(com.onpath.app.routing.TurnKind.LEFT, ScreenTurns.of(leftBack, hike = true).single().kind)
    }

    /**
     * Replay of tour 63 (owner 2026-11-01): off a street onto the footway along Ellerstraße, a corner of ~115° left drawn
     * as a curve of short pieces; BRouter's hint sits at its end with 43° ("slight left", the other way 138° back) – no
     * cue, the owner heard nothing. Measured over the 20 m one comes on, it is a left turn worth a cue.
     */
    @Test fun aCornerDrawnAsACurveIsItsWholeTurn() {
        val pts = listOf(51.168576 to 6.925829, 51.168689 to 6.926262, 51.168799 to 6.926681, 51.168837 to 6.926654,
            51.168871 to 6.926633, 51.168983 to 6.927063, 51.169030 to 6.927245, 51.169049 to 6.927291,
            51.169082 to 6.927318, 51.169126 to 6.927312, 51.169223 to 6.927144, 51.169344 to 6.926936,
            51.169495 to 6.926679, 51.169556 to 6.926573)
        val line = pts.mapIndexed { k, (la, lo) ->
            when (k) {
                2 -> GeoPoint(la, lo, turn = JunctionCue.LEFT, shape = JunctionShape(-91f, 6, 12, listOf(Branch(0f, 6), Branch(88f, 12))))
                4 -> GeoPoint(la, lo, turn = JunctionCue.RIGHT, shape = JunctionShape(89f, 12, 6, listOf(Branch(-1f, 12), Branch(-92f, 6))))
                9 -> GeoPoint(la, lo, turn = JunctionCue.SLIGHT_LEFT, shape = JunctionShape(-43f, 6, 6, listOf(Branch(138f, 6))))
                else -> GeoPoint(la, lo)
            }
        }
        assertEquals(false, CueRule.needed(JunctionCue.SLIGHT_LEFT, line[9].shape, hike = true))   // as BRouter sees it
        val fixed = JogRule.approach(line, hike = true)
        assertEquals(JunctionCue.LEFT, fixed[9].turn)
        assertTrue(fixed[9].turnAngle < -70f)
        assertTrue(JogRule.needed(line, hike = true).last())
        assertEquals(com.onpath.app.routing.TurnKind.LEFT, ScreenTurns.of(line, hike = true).last().kind)
    }

    /** A plain right-angle turn after a straight approach is left as it is. */
    @Test fun aStraightApproachChangesNothing() {
        val l = line(Triple(0.0, 60, null), Triple(270.0, 60, JunctionCue.LEFT), angles = listOf(-86f))
        assertEquals(l, JogRule.approach(l, hike = true))
    }

    /** A bend before the junction that makes the turn smaller never makes it quieter (only grown turns change). */
    @Test fun anApproachThatShrinksTheTurnChangesNothing() {
        val l = line(Triple(0.0, 30, null), Triple(300.0, 20, null), Triple(270.0, 60, JunctionCue.LEFT), angles = listOf(-30f))
        assertEquals(l, JogRule.approach(l, hike = true))
    }
}
