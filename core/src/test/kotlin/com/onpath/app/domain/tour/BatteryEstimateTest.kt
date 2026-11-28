package com.onpath.app.domain.tour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Akku sparen (Chriss 2026-11-28 10:38, „A und die Frage“; item 2026-11-28-battery-critical): the use per hour from the
 * battery's 1 % steps, the level at the goal from the time still to go; asked once per tour when the goal is reached with
 * less than 10 % – only after 30 min and a drop of 3 %, never while charging. Rather not ask than ask wrongly.
 */
class BatteryEstimateTest {
    private val min = 60_000L

    /** A tour from 0 ms: the level [start], then one step down every [everyMin] minutes, [steps] times. */
    private fun walk(start: Int, everyMin: Long, steps: Int, firstAfterMin: Long = everyMin): BatteryEstimate {
        val e = BatteryEstimate()
        e.level(0L, start, charging = false)
        for (i in 0 until steps) e.level((firstAfterMin + i * everyMin) * min, start - 1 - i, charging = false)
        return e
    }

    @Test fun usePerHourComesFromTheStepsNotFromTheStart() {
        // 1 % every 6 min = 10 %/h; the first step after 2 min (the start sat just above a step) must not count
        val e = walk(60, everyMin = 6, steps = 6, firstAfterMin = 2)
        assertEquals(10.0, e.usePerHour()!!, 0.01)
    }

    @Test fun noEstimateBeforeThirtyMinutesOrThreePercent() {
        assertNull("3 % but only 18 min", walk(60, everyMin = 6, steps = 3).usePerHour())
        val slow = walk(60, everyMin = 20, steps = 2) // 40 min, 2 % only
        assertNull("40 min but only 2 %", slow.usePerHour())
        assertTrue(walk(60, everyMin = 10, steps = 3).usePerHour() != null) // 30 min, 3 %
    }

    @Test fun levelAtTheGoal() {
        val e = walk(40, everyMin = 6, steps = 6) // 10 %/h, now 34 %
        assertEquals(34 - 25.0, e.levelAtGoal(toGoS = 2.5 * 3600)!!, 0.01)
    }

    @Test fun asksOnceWhenTheGoalIsReachedWithLessThanTenPercent() {
        val e = walk(40, everyMin = 6, steps = 6) // 34 %, 10 %/h
        assertTrue("9 % at the goal", BatteryEstimate.asks(e, toGoS = 2.5 * 3600, saving = false, askedThisTour = false))
        assertFalse("asked already", BatteryEstimate.asks(e, toGoS = 2.5 * 3600, saving = false, askedThisTour = true))
        assertFalse("already saving", BatteryEstimate.asks(e, toGoS = 2.5 * 3600, saving = true, askedThisTour = false))
        assertFalse("14 % at the goal: enough", BatteryEstimate.asks(e, toGoS = 2.0 * 3600, saving = false, askedThisTour = false))
        assertFalse("no time to go known", BatteryEstimate.asks(e, toGoS = null, saving = false, askedThisTour = false))
    }

    @Test fun chargingStartsOver() {
        val e = walk(40, everyMin = 6, steps = 6)
        e.level(40 * min, 34, charging = true)
        assertNull("charging: no estimate", e.usePerHour())
        e.level(41 * min, 35, charging = false)
        assertNull("after charging the count starts again", e.usePerHour())
    }

    @Test fun aLevelGoingUpWithoutChargingStartsOverToo() {
        val e = walk(40, everyMin = 6, steps = 6)
        e.level(40 * min, 36, charging = false) // a jump up (calibration, a power bank the phone did not report)
        assertNull(e.usePerHour())
    }

    @Test fun theUseIsTheRateBetweenTheFirstAndTheLastStep() {
        val e = BatteryEstimate()
        e.level(0, 80, false)
        e.level(5 * min, 79, false)   // first step
        e.level(20 * min, 78, false)
        e.level(50 * min, 77, false)  // last step: 2 % in 45 min after the first step …
        e.level(55 * min, 77, false)  // … a reading without a step changes nothing
        // 30 min since the start and 3 % since the start: 2 % / 0.75 h
        assertEquals(2.0 / 0.75, e.usePerHour()!!, 0.01)
    }
}
