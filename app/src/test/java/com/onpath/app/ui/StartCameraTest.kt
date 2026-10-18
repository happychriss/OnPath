package com.onpath.app.ui

import com.onpath.app.ui.StartCamera.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class StartCameraTest {
    private fun d(ms: Long, known: Boolean = true, plan: Boolean = true, moved: Boolean = false, age: Long? = null) =
        StartCamera.decide(ms, known, plan, moved, age)

    @Test fun freshFixCentresAtOnce() = assertEquals(Step.CENTRE, d(300, age = 2))
    @Test fun oldLastKnownWaitsForAFreshFixFirst() {
        assertEquals(Step.WAIT, d(1_000, age = 3_600))
        assertEquals(Step.CENTRE, d(StartCamera.FRESH_WAIT_MS, age = 3_600))
    }
    @Test fun noPositionKeepsWaitingThenGivesUp() {
        assertEquals(Step.WAIT, d(10_000))
        assertEquals(Step.GIVE_UP, d(StartCamera.ANY_WAIT_MS))
    }
    @Test fun lateFirstFixStillCentres() = assertEquals(Step.CENTRE, d(20_000, age = 1))
    @Test fun userPannedNeverJumps() = assertEquals(Step.GIVE_UP, d(500, moved = true, age = 1))
    @Test fun navigateOrTourRunningDoesNothing() = assertEquals(Step.GIVE_UP, d(500, plan = false, age = 1))
    @Test fun waitsUntilTheStartModeIsKnown() = assertEquals(Step.WAIT, d(500, known = false, plan = false, age = 1))
}
