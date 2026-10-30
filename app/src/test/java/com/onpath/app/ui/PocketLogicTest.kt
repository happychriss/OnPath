package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When the proximity sensor turns the screen off while navigating (owner 2026-10-30): on foot at once, on the bike only
 * after 5 s covered (a hand reaching for the handlebar phone is ignored); 60 s covered = the phone sleeps as usual.
 */
class PocketLogicTest {
    private val on = PocketLogic.Out(lock = false, keepOn = true)
    private val off = PocketLogic.Out(lock = true, keepOn = true) // the lock held: covered = screen off
    private val asleep = PocketLogic.Out(lock = false, keepOn = false)

    // ---- on foot: unchanged ----

    @Test fun hikeHoldsTheLockAllTheTime() {
        val p = PocketLogic(armMs = 0)
        assertEquals(off, p.at(0))
        assertEquals(off, p.sensor(true, 1_000)) // covered: the system turns the screen off at once
        assertEquals(off, p.sensor(false, 2_000)) // uncovered: on again (the lock stays)
        assertNull(p.nextChangeInMs(2_000))
    }

    @Test fun hikeSleepsAfterAMinuteInThePocket() {
        val p = PocketLogic(armMs = 0)
        p.sensor(true, 10_000)
        assertEquals(60_000L, p.nextChangeInMs(10_000))
        assertEquals(off, p.at(69_999))
        assertEquals(asleep, p.at(70_000))
        assertNull("nothing more to time", p.nextChangeInMs(70_000))
        // taken out: keep-on and the lock again
        assertEquals(off, p.sensor(false, 200_000))
    }

    // ---- on the bike ----

    @Test fun bikeIgnoresAHandOnTheScreen() {
        val p = PocketLogic(PocketLogic.BIKE_ARM_MS)
        assertEquals(on, p.at(0))
        assertEquals(on, p.sensor(true, 1_000)) // a hand over the sensor
        assertEquals(4_000L, p.nextChangeInMs(2_000))
        assertEquals(on, p.at(5_999))
        assertEquals(on, p.sensor(false, 5_999)) // gone after 4.999 s: nothing happened
        assertNull(p.nextChangeInMs(6_000))
        // short covers one after the other do not add up
        p.sensor(true, 7_000); p.sensor(false, 10_000); p.sensor(true, 10_500)
        assertEquals(on, p.at(14_000))
    }

    @Test fun bikeInAPocketAfterFiveSeconds() {
        val p = PocketLogic(PocketLogic.BIKE_ARM_MS)
        p.sensor(true, 1_000)
        assertEquals(off, p.at(6_000)) // covered 5 s: screen off
        assertEquals(55_000L, p.nextChangeInMs(6_000))
        // out of the pocket: screen on, the sensor no longer counts at once
        assertEquals(on, p.sensor(false, 20_000))
        assertEquals(on, p.sensor(true, 21_000))
        assertEquals(off, p.at(26_000))
    }

    @Test fun bikeSleepsAfterAMinuteInThePocket() {
        val p = PocketLogic(PocketLogic.BIKE_ARM_MS)
        p.sensor(true, 0)
        assertEquals(off, p.at(59_999))
        assertEquals(asleep, p.at(60_000))
        // taken out: keep-on again, sensor waits for the next long cover
        assertEquals(on, p.sensor(false, 90_000))
    }

    @Test fun repeatedReportsKeepTheStartOfTheCover() {
        val p = PocketLogic(PocketLogic.BIKE_ARM_MS)
        p.sensor(true, 0); p.sensor(true, 3_000); p.sensor(true, 4_000)
        assertEquals(off, p.at(5_000))
    }
}
