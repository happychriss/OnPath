package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

class TurnDetectorTest {
    // Local metric grid around Bozen: x east, y north (metres).
    private val lat0 = 46.5
    private val lon0 = 11.35
    private fun p(x: Double, y: Double) = GeoPoint(lat0 + y / 111_195.0, lon0 + x / (111_195.0 * cos(Math.toRadians(lat0))))

    /** Points every [step] m along straight pieces between the corners. */
    private fun line(vararg corners: Pair<Double, Double>, step: Double = 5.0): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        for (k in 0 until corners.size - 1) {
            val (x1, y1) = corners[k]; val (x2, y2) = corners[k + 1]
            val len = Math.hypot(x2 - x1, y2 - y1)
            val n = maxOf(1, (len / step).toInt())
            for (s in 0 until n) out += p(x1 + (x2 - x1) * s / n, y1 + (y2 - y1) * s / n)
        }
        out += corners.last().let { p(it.first, it.second) }
        return out
    }

    @Test fun straightLineHasNoTurns() {
        assertTrue(TurnDetector.turns(line(0.0 to 0.0, 0.0 to 500.0)).isEmpty())
        assertTrue(TurnDetector.turns(line(0.0 to 0.0, 300.0 to 400.0, step = 2.0)).isEmpty())
    }

    @Test fun rightAngleTurnRight() {
        val t = TurnDetector.turns(line(0.0 to 0.0, 0.0 to 200.0, 200.0 to 200.0))
        assertEquals(1, t.size)
        val turn = t[0]
        assertEquals(90.0, turn.angle, 5.0)
        assertEquals(TurnKind.RIGHT, turn.kind)
        assertEquals(200.0, turn.distanceFromStart, 6.0)
        assertEquals(90.0, turn.bearingOut, 5.0)
        assertEquals(0.0, abs(TurnDetector.diff(0.0, turn.bearingIn)), 5.0)
    }

    @Test fun sparseVerticesLeftTurn() {
        // only the three corners, no dense vertices
        val t = TurnDetector.turns(listOf(p(0.0, 0.0), p(0.0, 300.0), p(-300.0, 300.0)))
        assertEquals(1, t.size)
        assertEquals(TurnKind.LEFT, t[0].kind)
        assertEquals(-90.0, t[0].angle, 3.0)
    }

    @Test fun slightBendIsSlight() {
        val t = TurnDetector.turns(line(0.0 to 0.0, 0.0 to 200.0, 100.0 to 300.0))
        assertEquals(1, t.size)
        assertEquals(TurnKind.SLIGHT_RIGHT, t[0].kind)
        // 20° is not a turn at all
        assertTrue(TurnDetector.turns(line(0.0 to 0.0, 0.0 to 200.0, 68.0 to 388.0)).isEmpty())
    }

    @Test fun zigzagNoiseIgnored() {
        // GPS-like track: north with ±2.5 m sideways zigzag every 4 m
        val pts = (0..150).map { i -> p(if (i % 2 == 0) 2.5 else -2.5, i * 4.0) }
        assertTrue(TurnDetector.turns(pts).isEmpty())
    }

    @Test fun zigzagNoiseAroundARealTurnGivesOneTurn() {
        val north = (0..50).map { i -> p(if (i % 2 == 0) 2.0 else -2.0, i * 4.0) }
        val east = (1..50).map { i -> p(i * 4.0, 200.0 + if (i % 2 == 0) 2.0 else -2.0) }
        val t = TurnDetector.turns(north + east)
        assertEquals(1, t.size)
        assertTrue(t[0].kind == TurnKind.RIGHT || t[0].kind == TurnKind.SHARP_RIGHT)
    }

    @Test fun closeTurnsMerge() {
        // left then right 15 m apart (a jog across a road) → one turn, not two
        val t = TurnDetector.turns(line(0.0 to 0.0, 0.0 to 200.0, -15.0 to 200.0, -15.0 to 400.0))
        assertEquals(1, t.size)
        // two turns 150 m apart stay separate
        val t2 = TurnDetector.turns(line(0.0 to 0.0, 0.0 to 200.0, 150.0 to 200.0, 150.0 to 400.0))
        assertEquals(2, t2.size)
        assertEquals(TurnKind.RIGHT, t2[0].kind)
        assertEquals(TurnKind.LEFT, t2[1].kind)
    }

    @Test fun hairpinIsUTurn() {
        val t = TurnDetector.turns(line(0.0 to 0.0, 0.0 to 200.0, 10.0 to 200.0, 10.0 to 0.0))
        assertEquals(1, t.size)
        assertEquals(TurnKind.U_TURN_RIGHT, t[0].kind)
        assertEquals(180.0, t[0].bearingOut, 10.0)
        val left = TurnDetector.turns(line(0.0 to 0.0, 0.0 to 200.0, -10.0 to 200.0, -10.0 to 0.0))
        assertEquals(1, left.size)
        assertEquals(TurnKind.U_TURN_LEFT, left[0].kind)
        // straight out-and-back on the same line
        val back = TurnDetector.turns(line(0.0 to 0.0, 0.0 to 200.0, 0.0 to 0.0))
        assertEquals(1, back.size)
        assertTrue(abs(back[0].angle) >= 160)
    }

    @Test fun tooShortLines() {
        assertTrue(TurnDetector.turns(emptyList()).isEmpty())
        assertTrue(TurnDetector.turns(listOf(p(0.0, 0.0), p(0.0, 10.0))).isEmpty())
        assertTrue(TurnDetector.turns(listOf(p(0.0, 0.0), p(0.0, 0.0), p(0.0, 0.0))).isEmpty())
    }

    @Test fun nextTurnAhead() {
        val turns = TurnDetector.turns(line(0.0 to 0.0, 0.0 to 200.0, 150.0 to 200.0, 150.0 to 1000.0, 400.0 to 1000.0))
        assertEquals(3, turns.size)
        // at the start: first turn 200 m ahead
        val (t1, d1) = TurnDetector.nextTurn(turns, 0.0)!!
        assertEquals(turns[0], t1); assertEquals(200.0, d1, 6.0)
        // just past the first turn → the second
        assertEquals(turns[1], TurnDetector.nextTurn(turns, turns[0].distanceFromStart + 6)!!.first)
        // standing on the turn (within 5 m) counts as passed
        assertEquals(turns[1], TurnDetector.nextTurn(turns, turns[0].distanceFromStart - 3)!!.first)
        // after the second turn the third is 800 m away: too far to show
        assertNull(TurnDetector.nextTurn(turns, turns[1].distanceFromStart + 10))
        // within 500 m it shows
        assertEquals(turns[2], TurnDetector.nextTurn(turns, turns[2].distanceFromStart - 400)!!.first)
        // after the last turn: nothing
        assertNull(TurnDetector.nextTurn(turns, turns[2].distanceFromStart + 10))
    }

    @Test fun diffWraps() {
        assertEquals(20.0, TurnDetector.diff(350.0, 10.0), 1e-9)
        assertEquals(-20.0, TurnDetector.diff(10.0, 350.0), 1e-9)
        assertEquals(180.0, TurnDetector.diff(0.0, 180.0), 1e-9)
    }
}
