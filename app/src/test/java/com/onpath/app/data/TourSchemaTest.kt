package com.onpath.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Tour database upgrades (sqlite-jdbc on the JVM): 2 → 4 and 3 → 4 add `points.acc` (3) and `tours.rides` (4); every
 * tour and point stays. Runs the same steps as [TourDb.onUpgrade] (skip a column already there).
 */
class TourSchemaTest {
    private fun db(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
    private fun Connection.columns(table: String): List<String> =
        createStatement().use { s -> s.executeQuery("PRAGMA table_info($table)").use { r -> buildList { while (r.next()) add(r.getString("name")) } } }
    private fun Connection.count(sql: String): Int = createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getInt(1) } }

    private fun Connection.upgrade(from: Int) =
        TourSchema.stepsFrom(from).forEach { if (it.column !in columns(it.table)) exec(it.sql) }

    /** Version 2 as shipped until 0.9.2; [acc] / [rides] = the columns a version 3 had. */
    private fun Connection.oldSchema(acc: Boolean = false, rides: Boolean = false) {
        exec("""CREATE TABLE tours(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, name TEXT NOT NULL,
            created INTEGER NOT NULL, activity TEXT, sac INTEGER NOT NULL DEFAULT 3, distance REAL NOT NULL DEFAULT 0,
            ascent REAL NOT NULL DEFAULT 0, descent REAL NOT NULL DEFAULT 0, duration REAL NOT NULL DEFAULT 0,
            state TEXT NOT NULL DEFAULT 'done', waypoints TEXT, uuid TEXT, pinned TEXT, deleted_at INTEGER${if (rides) ", rides TEXT" else ""})""")
        exec("""CREATE TABLE points(tour_id INTEGER NOT NULL, seq INTEGER NOT NULL, seg INTEGER NOT NULL,
            lat REAL NOT NULL, lon REAL NOT NULL, ele REAL, time INTEGER${if (acc) ", acc REAL" else ""})""")
        exec("INSERT INTO tours(kind, name, created, distance, uuid) VALUES ('RECORDED', 'Waalweg', 1, 5230.5, 'u1')")
        for (i in 0 until 50) exec("INSERT INTO points(tour_id, seq, seg, lat, lon, ele, time) VALUES (1, $i, 0, 46.6, 11.1, 600, ${1000L * i})")
    }

    private fun Connection.assertUpgraded() {
        assertTrue("rides" in columns("tours"))
        assertTrue("acc" in columns("points"))
        assertEquals(1, count("SELECT COUNT(*) FROM tours WHERE name='Waalweg' AND distance=5230.5 AND uuid='u1' AND rides IS NULL"))
        assertEquals(50, count("SELECT COUNT(*) FROM points WHERE tour_id=1 AND acc IS NULL"))
        exec("UPDATE tours SET rides='{}' WHERE id=1")
        exec("INSERT INTO points VALUES (1, 50, 0, 46.6, 11.1, 600, 50000, 12.5)")
        assertEquals(1, count("SELECT COUNT(*) FROM points WHERE acc=12.5"))
    }

    @Test fun upgradeFrom2KeepsEveryTourAndPoint() {
        db().use { c -> c.oldSchema(); c.upgrade(2); c.assertUpgraded() }
    }

    @Test fun upgradeFrom3KeepsEveryTourAndPoint() {
        db().use { c -> c.oldSchema(acc = true); c.upgrade(3); c.assertUpgraded() }
    }

    /** A test build of the ride branch wrote version 3 with `rides` but without `acc`. */
    @Test fun upgradeFromRideBranch3AddsAccuracy() {
        db().use { c -> c.oldSchema(rides = true); c.upgrade(3); c.assertUpgraded() }
    }

    @Test fun freshDatabaseHasTheColumns() {
        db().use { c ->
            TourSchema.CREATE.forEach { c.exec(it) }
            assertTrue("rides" in c.columns("tours"))
            assertTrue("acc" in c.columns("points"))
            assertEquals(4, TourSchema.VERSION)
            assertEquals(TourSchema.VERSION, TourDb.VERSION)
        }
    }
}
