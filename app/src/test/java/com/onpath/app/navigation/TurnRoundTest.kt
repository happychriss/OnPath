package com.onpath.app.navigation

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Turn round" on a way back: the new route starts against the direction one rides. */
class TurnRoundTest {
    private val m = 0.0000089932 // ≈ 1 m of latitude
    /** Riding north: 60 m in 10 m steps, ending at the origin. */
    private val northbound = (0..6).map { GeoPoint(46.0 + (it - 6) * 10 * m, 11.0) }

    private fun lineTo(dLat: Double, dLon: Double, n: Int = 10) =
        (0..n).map { GeoPoint(46.0 + it * 10 * m * dLat, 11.0 + it * 10 * m * dLon / 0.69) }

    @Test fun wayBackSouthIsATurnRound() = assertTrue(TurnRound.needed(northbound, lineTo(-1.0, 0.0)))
    @Test fun wayOnNorthIsNot() = assertFalse(TurnRound.needed(northbound, lineTo(1.0, 0.0)))
    @Test fun turningOffToTheSideIsNot() = assertFalse(TurnRound.needed(northbound, lineTo(0.0, 1.0)))
    @Test fun standingStillIsUnknown() = assertFalse(TurnRound.needed(List(10) { GeoPoint(46.0, 11.0) }, lineTo(-1.0, 0.0)))
    @Test fun tooShortALineIsUnknown() = assertFalse(TurnRound.needed(northbound, listOf(GeoPoint(46.0, 11.0), GeoPoint(46.0 - 5 * m, 11.0))))

    @Test fun questionSaysTurnRound() {
        val q = NavPrompt.Question(1, 300.0, 600.0, 0.0, lineTo(-1.0, 0.0), 900.0, 0.0, turnRound = true)
        assertEquals("Off route – turn round, new route found", NavText.title(q))
        assertEquals("Turn round – new route found", NavText.state(q))
        assertEquals("Off route – new route found", NavText.title(q.copy(turnRound = false)))
    }
}
