package com.onpath.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** Tour database 2 → 3 (vehicle detection): the new column comes, every tour and point stays (sqlite-jdbc on the JVM). */
class TourSchemaTest {
    private fun db(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
    private fun Connection.columns(table: String): List<String> =
        createStatement().use { s -> s.executeQuery("PRAGMA table_info($table)").use { r -> buildList { while (r.next()) add(r.getString("name")) } } }
    private fun Connection.count(sql: String): Int = createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getInt(1) } }

    @Test fun upgradeFrom2KeepsEveryTourAndPoint() {
        db().use { c ->
            // version 2 as shipped until 0.9.2
            c.exec("""CREATE TABLE tours(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, name TEXT NOT NULL,
                created INTEGER NOT NULL, activity TEXT, sac INTEGER NOT NULL DEFAULT 3, distance REAL NOT NULL DEFAULT 0,
                ascent REAL NOT NULL DEFAULT 0, descent REAL NOT NULL DEFAULT 0, duration REAL NOT NULL DEFAULT 0,
                state TEXT NOT NULL DEFAULT 'done', waypoints TEXT, uuid TEXT, pinned TEXT, deleted_at INTEGER)""")
            c.exec("""CREATE TABLE points(tour_id INTEGER NOT NULL, seq INTEGER NOT NULL, seg INTEGER NOT NULL,
                lat REAL NOT NULL, lon REAL NOT NULL, ele REAL, time INTEGER)""")
            c.exec("INSERT INTO tours(kind, name, created, distance, uuid) VALUES ('RECORDED', 'Waalweg', 1, 5230.5, 'u1')")
            for (i in 0 until 50) c.exec("INSERT INTO points VALUES (1, $i, 0, 46.6, 11.1, 600, ${1000L * i})")

            c.exec(TourSchema.ADD_RIDES)

            assertTrue("rides" in c.columns("tours"))
            assertEquals(1, c.count("SELECT COUNT(*) FROM tours WHERE name='Waalweg' AND distance=5230.5 AND uuid='u1' AND rides IS NULL"))
            assertEquals(50, c.count("SELECT COUNT(*) FROM points WHERE tour_id=1"))
            c.exec("UPDATE tours SET rides='{}' WHERE id=1")
        }
    }

    @Test fun freshDatabaseHasTheColumn() {
        db().use { c ->
            TourSchema.CREATE.forEach { c.exec(it) }
            assertTrue("rides" in c.columns("tours"))
            assertEquals(3, TourSchema.VERSION)
        }
    }
}
