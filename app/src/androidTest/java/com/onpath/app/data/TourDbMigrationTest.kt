package com.onpath.app.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onpath.app.gpx.TrackPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Schema upgrades: 3 = `points.acc` (GPS accuracy per point), 4 = `tours.rides`.
 * An existing version-2 or version-3 database keeps every point; old points read back as unknown accuracy, new ones store
 * it, and the tour has no rides.
 */
@RunWith(AndroidJUnit4::class)
class TourDbMigrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-test.db"

    @After fun tearDown() { ctx.deleteDatabase(name) }

    /** A version-2 database ([version] 3: with `points.acc`). */
    private fun writeOld(version: Int) {
        ctx.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(name), null).use { db ->
            db.execSQL("""CREATE TABLE tours(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, name TEXT NOT NULL,
                created INTEGER NOT NULL, activity TEXT, sac INTEGER NOT NULL DEFAULT 3, distance REAL NOT NULL DEFAULT 0,
                ascent REAL NOT NULL DEFAULT 0, descent REAL NOT NULL DEFAULT 0, duration REAL NOT NULL DEFAULT 0,
                state TEXT NOT NULL DEFAULT 'done', waypoints TEXT, uuid TEXT, pinned TEXT, deleted_at INTEGER)""")
            db.execSQL("""CREATE TABLE points(tour_id INTEGER NOT NULL, seq INTEGER NOT NULL, seg INTEGER NOT NULL,
                lat REAL NOT NULL, lon REAL NOT NULL, ele REAL, time INTEGER${if (version >= 3) ", acc REAL" else ""})""")
            db.execSQL("INSERT INTO tours(id, kind, name, created, state, uuid) VALUES (1, 'RECORDED', 'Old', 0, 'active', 'u1')")
            db.execSQL("INSERT INTO points(tour_id, seq, seg, lat, lon, ele, time) VALUES (1, 0, 0, 46.70, 11.22, 1500, 1000)")
            db.version = version
        }
    }

    private fun checkUpgraded() {
        val tdb = TourDb(ctx, name)
        try {
            tdb.appendPoint(1, 1, 0, TrackPoint(46.701, 11.221, 1501.0, 2000), 12.5f)
            tdb.appendPoint(1, 2, 0, TrackPoint(46.702, 11.222, 1502.0, 3000))
            val acc = tdb.pointAccuracies(1)
            assertEquals(3, acc.size)
            assertNull(acc[0])
            assertEquals(12.5, acc[1]!!, 0.01)
            assertNull(acc[2])
            assertEquals(4, tdb.readableDatabase.version)
            val t = tdb.getTour(1)!!
            assertEquals("u1", t.uuid)
            assertNull(t.rides)
            assertTrue(tdb.setRides(1, null, 1.0, 2.0, 3.0, 4.0))
        } finally { tdb.close() }
    }

    @Test fun version2DatabaseGetsAccuracyAndRidesAndKeepsPoints() { writeOld(2); checkUpgraded() }

    @Test fun version3DatabaseGetsRidesAndKeepsPoints() { writeOld(3); checkUpgraded() }
}
