package com.onpath.app.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onpath.app.gpx.TrackPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Schema 2 → 3 (field test 2026-10-22): the points table gets `acc` (GPS accuracy per point). An existing version-2
 * database keeps every point; old points read back as unknown accuracy, new ones store it.
 */
@RunWith(AndroidJUnit4::class)
class TourDbMigrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-test.db"

    @After fun tearDown() { ctx.deleteDatabase(name) }

    @Test fun version2DatabaseGetsAccuracyColumnAndKeepsPoints() {
        ctx.deleteDatabase(name)
        // a version-2 database as 0.9.2 wrote it
        SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(name), null).use { db ->
            db.execSQL("""CREATE TABLE tours(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, name TEXT NOT NULL,
                created INTEGER NOT NULL, activity TEXT, sac INTEGER NOT NULL DEFAULT 3, distance REAL NOT NULL DEFAULT 0,
                ascent REAL NOT NULL DEFAULT 0, descent REAL NOT NULL DEFAULT 0, duration REAL NOT NULL DEFAULT 0,
                state TEXT NOT NULL DEFAULT 'done', waypoints TEXT, uuid TEXT, pinned TEXT, deleted_at INTEGER)""")
            db.execSQL("""CREATE TABLE points(tour_id INTEGER NOT NULL, seq INTEGER NOT NULL, seg INTEGER NOT NULL,
                lat REAL NOT NULL, lon REAL NOT NULL, ele REAL, time INTEGER)""")
            db.execSQL("INSERT INTO tours(id, kind, name, created, state, uuid) VALUES (1, 'RECORDED', 'Old', 0, 'active', 'u1')")
            db.execSQL("INSERT INTO points VALUES (1, 0, 0, 46.70, 11.22, 1500, 1000)")
            db.version = 2
        }
        val tdb = TourDb(ctx, name)
        try {
            tdb.appendPoint(1, 1, 0, TrackPoint(46.701, 11.221, 1501.0, 2000), 12.5f)
            tdb.appendPoint(1, 2, 0, TrackPoint(46.702, 11.222, 1502.0, 3000))
            val acc = tdb.pointAccuracies(1)
            assertEquals(3, acc.size)
            assertNull(acc[0])
            assertEquals(12.5, acc[1]!!, 0.01)
            assertNull(acc[2])
            assertEquals(3, tdb.readableDatabase.version)
        } finally { tdb.close() }
    }
}
