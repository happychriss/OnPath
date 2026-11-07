package com.onpath.app.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onpath.app.gpx.TrackPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Schema upgrades: 3 = `points.acc` (GPS accuracy per point), 4 = `tours.rides`, 5 = `tours.draft` (Zuletzt, 2026-11-06).
 * An existing version-2, -3 or -4 database keeps every tour and point; old points read back as unknown accuracy, new
 * ones store it, the tour has no rides and is no draft (a saved route stays saved), and a draft can be marked.
 */
@RunWith(AndroidJUnit4::class)
class TourDbMigrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-test.db"

    @After fun tearDown() { ctx.deleteDatabase(name) }

    /** A version-2 database ([version] 3: with `points.acc`; 4: with `tours.rides` too). */
    private fun writeOld(version: Int) {
        ctx.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(name), null).use { db ->
            db.execSQL("""CREATE TABLE tours(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, name TEXT NOT NULL,
                created INTEGER NOT NULL, activity TEXT, sac INTEGER NOT NULL DEFAULT 3, distance REAL NOT NULL DEFAULT 0,
                ascent REAL NOT NULL DEFAULT 0, descent REAL NOT NULL DEFAULT 0, duration REAL NOT NULL DEFAULT 0,
                state TEXT NOT NULL DEFAULT 'done', waypoints TEXT, uuid TEXT, pinned TEXT, deleted_at INTEGER${if (version >= 4) ", rides TEXT" else ""})""")
            db.execSQL("""CREATE TABLE points(tour_id INTEGER NOT NULL, seq INTEGER NOT NULL, seg INTEGER NOT NULL,
                lat REAL NOT NULL, lon REAL NOT NULL, ele REAL, time INTEGER${if (version >= 3) ", acc REAL" else ""})""")
            db.execSQL("INSERT INTO tours(id, kind, name, created, state, uuid) VALUES (1, 'RECORDED', 'Old', 0, 'active', 'u1')")
            db.execSQL("INSERT INTO tours(id, kind, name, created, state, uuid) VALUES (2, 'PLANNED', 'Saved route', 1, 'done', 'u2')")
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
            assertEquals(TourSchema.VERSION, tdb.readableDatabase.version)
            assertEquals(5, TourSchema.VERSION) // a new schema version gets its own case here
            val t = tdb.getTour(1)!!
            assertEquals("u1", t.uuid)
            assertNull(t.rides)
            assertTrue(tdb.setRides(1, null, 1.0, 2.0, 3.0, 4.0))
            // 5: tours.draft – an old saved route is no draft (never under Zuletzt, never pruned) and a draft can be marked
            val saved = tdb.listTours().single { it.id == 2L }
            assertEquals("Saved route", saved.name)
            assertFalse("an old saved route stays saved", saved.draft)
            assertTrue(tdb.setDraft(2, true))
            assertTrue(tdb.getTour(2)!!.draft)
            assertEquals(0, tdb.pruneDrafts(25))
            assertEquals("a new draft row inserts on the upgraded schema", true,
                tdb.getTour(tdb.insertTour(TourKind.PLANNED, "Draft", "HIKE", 2, 1.0, 0.0, 0.0, 1.0, emptyList(), draft = true))!!.draft)
        } finally { tdb.close() }
    }

    @Test fun version2DatabaseGetsAccuracyAndRidesAndKeepsPoints() { writeOld(2); checkUpgraded() }

    @Test fun version3DatabaseGetsRidesAndKeepsPoints() { writeOld(3); checkUpgraded() }

    @Test fun version4DatabaseGetsDraftsAndKeepsToursAndPoints() { writeOld(4); checkUpgraded() }
}
