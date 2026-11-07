package com.onpath.app.debug

import com.onpath.app.map.ReliefAuto.Gesture
import com.onpath.app.map.ReliefAuto.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The problem report's "mapJank": janky share per gesture kind and relief state; the recent gestures for ReliefAuto. */
class MapJankTallyTest {
    @Test fun sumsPerKindAndReliefState() {
        val t = MapJankTally()
        t.add(Gesture(Kind.PAN, 100, 46, true))
        t.add(Gesture(Kind.PAN, 100, 50, true))
        t.add(Gesture(Kind.ZOOM, 80, 10, true))
        t.add(Gesture(Kind.PAN, 50, 4, false))
        val j = t.json()
        assertEquals(3, j.length())
        val pan = j.getJSONObject("pan, relief on")
        assertEquals(2, pan.getInt("gestures"))
        assertEquals(200, pan.getInt("frames"))
        assertEquals(96, pan.getInt("janky"))
        assertEquals(48, pan.getInt("jankyPercent"))
        assertEquals(13, j.getJSONObject("zoom, relief on").getInt("jankyPercent"))
        assertEquals(8, j.getJSONObject("pan, relief off").getInt("jankyPercent"))
    }

    @Test fun keepsTheRecentGesturesOldestFirst() {
        val t = MapJankTally()
        repeat(40) { t.add(Gesture(Kind.PAN, it, 0, true)) }
        val r = t.recent()
        assertEquals(30, r.size)
        assertEquals(10, r.first().frames)
        assertEquals(39, r.last().frames)
    }

    @Test fun emptyWithoutGestures() = assertEquals(0, MapJankTally().json().length())
}
