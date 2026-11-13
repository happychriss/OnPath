package com.onpath.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.plansync.DbTourStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Favourites (owner 2026-11-13) on the real database: the sync's store sets and clears the star, the recycle bin keeps
 * it; the line key of the map pictures follows the stored points (TourDb.lineKey).
 */
@RunWith(AndroidJUnit4::class)
class DbTourStoreStarTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "star-test.db"

    @After fun tearDown() { ctx.deleteDatabase(name) }

    @Test fun theSyncStoreStarsAndTheBinKeepsIt() {
        val db = TourDb(ctx, name)
        try {
            val store = DbTourStore(ctx, db) { com.onpath.app.time.Factors() }
            val line = listOf(TrackPoint(46.67, 11.16), TrackPoint(46.68, 11.17))
            val id = db.insertTour(TourKind.PLANNED, "Waalweg", "HIKE", 2, 1000.0, 10.0, 10.0, 600.0, listOf(line),
                listOf(GpxWaypoint(46.67, 11.16, null), GpxWaypoint(46.68, 11.17, null)))
            val t = db.getTour(id)!!
            assertFalse(t.favourite)
            store.setFavourite(t, true)
            assertTrue(db.getTour(id)!!.favourite)
            store.toBin(db.getTour(id)!!)
            assertTrue("the bin keeps the star", db.listDeleted().single { it.id == id }.favourite)
            store.fromBin(db.getTour(id)!!)
            store.setFavourite(db.getTour(id)!!, false)
            assertFalse(db.getTour(id)!!.favourite)
            // the map pictures' line key: the stored points, none for a tour without
            val k = db.lineKey(id)!!
            assertTrue(k.startsWith("2:"))
            db.replacePlanned(id, "HIKE", 2, 900.0, 0.0, 0.0, 500.0, listOf(line + TrackPoint(46.69, 11.18)), emptyList())
            assertTrue(db.lineKey(id)!!.startsWith("3:"))
            assertEquals(null, db.lineKey(9999))
            assertNull(db.lineKey(db.insertTour(TourKind.PLANNED, "Empty", "HIKE", 2, 0.0, 0.0, 0.0, 0.0, emptyList())))
        } finally { db.close() }
    }
}
