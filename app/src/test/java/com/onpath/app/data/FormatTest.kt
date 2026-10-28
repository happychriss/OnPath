package com.onpath.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun shortTimeToGoOverAnHour() = assertEquals("2:05 h", Format.durationShort(2 * 3600.0 + 5 * 60 + 30))
    @Test fun shortTimeToGoUnderAnHour() = assertEquals("25 min", Format.durationShort(25 * 60.0))
    @Test fun shortTimeToGoLongRide() = assertEquals("10:25 h", Format.durationShort(10 * 3600.0 + 25 * 60))
}
