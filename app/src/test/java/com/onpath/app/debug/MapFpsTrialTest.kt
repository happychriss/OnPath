package com.onpath.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapFpsTrialTest {
    @Test fun aNumberInRangeIsTheLimit() {
        assertEquals(60, MapFpsTrial.parse("60\n"))
        assertEquals(120, MapFpsTrial.parse(" 120 "))
    }

    @Test fun nothingUsableMeansNoLimit() {
        assertNull(MapFpsTrial.parse(null))
        assertNull(MapFpsTrial.parse(""))
        assertNull(MapFpsTrial.parse("fast"))
        assertNull(MapFpsTrial.parse("0"))
        assertNull(MapFpsTrial.parse("1000"))
    }
}
