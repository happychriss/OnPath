package com.onpath.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recorded heights above sea level (without the geoid correction ~45 m too high near Düsseldorf). */
class SeaLevelTest {
    private val here = SeaLevel.Offset(51.17, 6.75, 47.0)

    @Test fun ellipsoidHeightMinusTheGeoid() = assertEquals(38.0, SeaLevel.height(85.0, null, here, 51.171, 6.751, 60_000)!!, 0.01)
    @Test fun thePhonesSeaLevelHeightWins() = assertEquals(40.0, SeaLevel.height(85.0, 40.0, here, 51.17, 6.75, 60_000)!!, 0.01)
    @Test fun noHeightWhileSettling() = assertNull(SeaLevel.height(135.0, null, here, 51.17, 6.75, 5_000))
    @Test fun withoutOffsetAsGiven() = assertEquals(85.0, SeaLevel.height(85.0, null, null, 51.17, 6.75, 60_000)!!, 0.01)
    @Test fun farFromTheOffsetAsGiven() = assertEquals(85.0, SeaLevel.height(85.0, null, here, 51.40, 6.75, 60_000)!!, 0.01)
    @Test fun noHeightAtAll() = assertNull(SeaLevel.height(null, null, here, 51.17, 6.75, 60_000))
    @Test fun offsetRefreshedAfterMoving() {
        assertTrue(SeaLevel.needsOffset(null, 51.17, 6.75))
        assertFalse(SeaLevel.needsOffset(here, 51.18, 6.75))
        assertTrue(SeaLevel.needsOffset(here, 51.25, 6.75))
    }
}
