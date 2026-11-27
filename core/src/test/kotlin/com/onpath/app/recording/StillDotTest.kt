package com.onpath.app.recording

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Owner's Pixel 2026-11-27 23:32 (Navigate, indoors, phone on the table): the dot wandered with every GPS fix. */
class StillDotTest {
    private val last = GeoPoint(51.1692447372274, 6.92546263224504)

    @Test fun lyingStillTheDotStaysOnTheLastTrackPoint() {
        // the real fixes of tour 24: 33 m at 49 m, 38 m at 47 m, 47 m at 47 m
        assertEquals(last, StillDot.hold(last, 51.1693647853983, 6.92590769386294, 49.4, sensorStill = true))
        assertEquals(last, StillDot.hold(last, 51.1691751927047, 6.92545369745193, 46.7, sensorStill = true))
        assertEquals(last, StillDot.hold(last, 51.1691831427485, 6.92587754032961, 46.7, sensorStill = true))
    }

    @Test fun aRealMoveOrNoSensorWordShowsTheFix() {
        assertNull("300 m off: a real move", StillDot.hold(last, 51.1692447, 6.9297, 40.0, sensorStill = true))
        assertNull("moving by the sensor", StillDot.hold(last, 51.1693647853983, 6.92590769386294, 49.4, sensorStill = false))
        assertNull("no track point yet", StillDot.hold(null, 51.1693647853983, 6.92590769386294, 49.4, sensorStill = true))
        assertNull("no accuracy", StillDot.hold(last, 51.1693647853983, 6.92590769386294, null, sensorStill = true))
    }
}
