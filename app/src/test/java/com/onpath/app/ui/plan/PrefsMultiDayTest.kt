package com.onpath.app.ui.plan

import com.onpath.app.data.MapPrefs
import com.onpath.app.data.MultiDay
import com.onpath.app.data.Prefs
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.MultiDayFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * [PrefsMultiDay] uses the OLD preference keys of the multi-day state (an update must not lose a followed day): each property
 * writes its own raw key and reads it back, over the real [Prefs]; an old-style raw write restores the followed day.
 */
class PrefsMultiDayTest {
    private val dir = Files.createTempDirectory("prefsmultiday").toFile()
    private val sp = MapPrefs()
    private val adapter = PrefsMultiDay(Prefs(sp, dir))

    @Test fun everyPropertyWritesItsOwnRawKeyAndReadsItBack() {
        adapter.planMultiDay = "tour-json"; adapter.planDayFollow = "day-json"
        adapter.multiDayMapCells = setOf("10-1-1", "10-1-2")
        assertEquals("tour-json", sp.getString("planMultiDay", null))
        assertEquals("day-json", sp.getString("planDayFollow", null))
        assertEquals(setOf("10-1-1", "10-1-2"), sp.getStringSet("multiDayMapCells", null))
    }

    @Test fun aRawWriteOfTheOldKeysIsReadThroughTheAdapter() {
        sp.edit().putString("planMultiDay", "a").putString("planDayFollow", "b").putStringSet("multiDayMapCells", setOf("c1")).apply()
        assertEquals("a", adapter.planMultiDay); assertEquals("b", adapter.planDayFollow)
        assertEquals(setOf("c1"), adapter.multiDayMapCells)
    }

    @Test fun aFollowedDayKeptByTheOldCodeIsRestored() {
        Problems.init(dir); Problems.clear()
        val a = GeoPoint(46.0, 11.0); val b = GeoPoint(46.1, 11.0); val c = GeoPoint(46.2, 11.0)
        val tour = Tour(7, TourKind.PLANNED, "T", 1L, Activity.BIKE_TOURING.name, 2, 1.0, 0.0, 0.0, 0.0, "done", emptyList(), uuid = "u7", days = MultiDay(2))
        // what the old build wrote: the flow's own JSON, through the raw key
        val old = MapPrefs()
        val first = MultiDayFlow(PrefsMultiDay(Prefs(old, dir))) { 7L }
        first.opened(tour, listOf(a, b, c))
        val json = old.getString("planMultiDay", null)
        assertNotNull(json)
        sp.edit().putString("planMultiDay", json).apply()
        val restored = MultiDayFlow(adapter) { 7L }
        assertEquals(7L, restored.tour?.id)
        assertTrue(restored.overview)
        // the followed day's key: unreadable state under the old key is noticed as a lost day (the start then reloads the tour)
        sp.edit().putString("planDayFollow", "{\"tour\": broken").apply()
        assertTrue(MultiDayFlow(adapter) { 7L }.dayLost)
    }
}
