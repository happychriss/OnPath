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

    private fun Connection.upgrade(from: Int) {
        TourSchema.stepsFrom(from).forEach { if (it.column !in columns(it.table)) exec(it.sql) }
        TourSchema.tablesFrom(from).forEach { exec(it) }
    }

    private fun Connection.tables(): List<String> =
        createStatement().use { s -> s.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { r -> buildList { while (r.next()) add(r.getString(1)) } } }

    /** [TourDb.setTourPlan]: the same statement and arguments. */
    private fun Connection.setPlan(tourId: Long, p: TourPlan) = prepareStatement(TourSchema.PLAN_UPSERT).use { st ->
        TourSchema.planArgs(tourId, p).forEachIndexed { i, v -> st.setObject(i + 1, v) }
        st.executeUpdate()
    }
    private fun Connection.planWaypoints(tourId: Long): String? = createStatement().use { s ->
        s.executeQuery("SELECT waypoints FROM tour_plan WHERE tour_id=$tourId").use { r -> if (r.next()) r.getString(1) else null }
    }
    private val plan = TourPlan("Waalweg", "HIKE", 2, listOf(com.onpath.app.gpx.GpxWaypoint(46.67, 11.13, "Algund"),
        com.onpath.app.gpx.GpxWaypoint(46.68, 11.15, null)), setOf(1),
        listOf(com.onpath.app.gpx.TrackPoint(46.67, 11.13, 400.0), com.onpath.app.gpx.TrackPoint(46.68, 11.15, 410.0)), 4200.0)

    /** Version 2; [acc] / [rides] = the columns a version 3 had. */
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
        // 4 → 5 (Zuletzt): every existing tour stays saved
        assertTrue("draft" in columns("tours"))
        assertEquals(1, count("SELECT COUNT(*) FROM tours WHERE name='Waalweg' AND draft=0"))
        assertTrue("acc" in columns("points"))
        assertEquals(1, count("SELECT COUNT(*) FROM tours WHERE name='Waalweg' AND distance=5230.5 AND uuid='u1' AND rides IS NULL"))
        assertEquals(50, count("SELECT COUNT(*) FROM points WHERE tour_id=1 AND acc IS NULL"))
        // 5 → 6: the table of the followed plans; the old tour has none
        assertTrue("tour_plan" in tables())
        assertEquals(0, count("SELECT ${TourSchema.HAS_PLAN} FROM tours WHERE id=1"))
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

    /** A version 4 database (rides + acc) gets the draft column; its tours stay saved. */
    @Test fun upgradeFrom4AddsDraft() {
        db().use { c -> c.oldSchema(acc = true, rides = true); c.upgrade(4); c.assertUpgraded() }
    }

    /** A version 5 database (Zuletzt) gets the plan copies' table; every tour and point stays. */
    @Test fun upgradeFrom5AddsTheFollowedPlans() {
        db().use { c ->
            c.oldSchema(acc = true, rides = true); c.exec(TourSchema.ADD_DRAFT); c.upgrade(5); c.assertUpgraded()
            c.upgrade(5) // twice (a half-done upgrade): nothing breaks
        }
    }

    /**
     * Owner 2026-11-07: the copy is a copy – the planned route deleted (or changed) later, the tour still has the plan it
     * followed; a newer version replaces the older (the last version counts); the tour removed for good takes it along.
     */
    @Test fun theFollowedPlanIsACopyThatOutlivesThePlannedRoute() {
        db().use { c ->
            TourSchema.CREATE.forEach { c.exec(it) }
            c.exec("INSERT INTO tours(id, kind, name, created) VALUES (1, 'PLANNED', 'Waalweg', 1)")
            c.exec("INSERT INTO tours(id, kind, name, created) VALUES (2, 'RECORDED', 'Tour', 2)")
            c.setPlan(2, plan)
            c.setPlan(2, plan.copy(waypoints = plan.waypoints + com.onpath.app.gpx.GpxWaypoint(46.69, 11.17, "Meran")))
            assertEquals(1, c.count("SELECT COUNT(*) FROM tour_plan WHERE tour_id=2"))
            assertTrue("the last version", c.planWaypoints(2)!!.contains("Meran"))
            assertEquals(1, c.count("SELECT ${TourSchema.HAS_PLAN} FROM tours WHERE id=2"))
            assertEquals(0, c.count("SELECT ${TourSchema.HAS_PLAN} FROM tours WHERE id=1"))
            // the planned route deleted for good (TourDb.deleteTour)
            c.exec("DELETE FROM points WHERE tour_id=1"); c.exec("DELETE FROM tours WHERE id=1")
            c.prepareStatement(TourSchema.PLAN_DELETE).use { it.setLong(1, 1); it.executeUpdate() }
            assertTrue(c.planWaypoints(2)!!.contains("Algund"))
            // the tour removed for good: its copy goes with it
            c.exec("DELETE FROM tours WHERE id=2")
            c.prepareStatement(TourSchema.PLAN_DELETE).use { it.setLong(1, 2); it.executeUpdate() }
            assertEquals(0, c.count("SELECT COUNT(*) FROM tour_plan"))
        }
    }

    @Test fun freshDatabaseHasTheColumns() {
        db().use { c ->
            TourSchema.CREATE.forEach { c.exec(it) }
            assertTrue("rides" in c.columns("tours"))
            assertTrue("acc" in c.columns("points"))
            assertTrue("draft" in c.columns("tours"))
            assertTrue("tour_plan" in c.tables())
            assertEquals(6, TourSchema.VERSION)
            assertEquals(TourSchema.VERSION, TourDb.VERSION)
        }
    }

    /**
     * Review recording M7: a corrupt database replaced by an empty one mid-tour – the tour's row is put back under the
     * SAME id (the service keeps writing points with it), points already written for it are replaced.
     */
    @Test fun aRecordingIsPutBackUnderItsId() {
        db().use { c ->
            TourSchema.CREATE.forEach { c.exec(it) }
            // the fresh database: points of tour 42 arrived, no tour row
            for (i in 0 until 3) c.exec("INSERT INTO points(tour_id, seq, seg, lat, lon) VALUES (42, $i, 0, 46.6, 11.1)")
            assertEquals(0, c.count("SELECT COUNT(*) FROM tours WHERE id=42"))
            // TourDb.rescueRecording: delete its points, insert the row with the id, all points from memory
            c.exec("DELETE FROM points WHERE tour_id=42")
            c.exec("INSERT INTO tours(id, kind, name, created, uuid, pinned, waypoints, sac, state) VALUES (42, 'RECORDED', 'Tour', 1, 'u', '[]', '[]', 2, 'active')")
            for (i in 0 until 10) c.exec("INSERT INTO points(tour_id, seq, seg, lat, lon) VALUES (42, $i, ${i / 5}, 46.6, 11.1)")
            assertEquals(10, c.count("SELECT COUNT(*) FROM points p JOIN tours t ON t.id = p.tour_id WHERE t.id=42"))
            // a later new tour does not take the id
            c.exec("INSERT INTO tours(kind, name, created) VALUES ('PLANNED', 'Next', 2)")
            assertEquals(1, c.count("SELECT COUNT(*) FROM tours WHERE id > 42"))
        }
    }

    /** The service checks the row with every stats save and on the end of the tour. */
    @Test fun theServiceRescuesWhenTheRowIsGone() {
        val svc = java.io.File("src/main/java/com/onpath/app/recording/RecordingService.kt").readText()
        assertTrue(svc.contains("gone = db.updateStats(") && svc.contains("db.rescueRecording(tourId"))
        assertTrue(svc.substringAfter("private fun tick()").substringBefore("\n    }\n").contains("saveStats(\"active\")"))
        assertTrue(svc.substringAfter("private fun finish()").substringBefore("\n    }\n").contains("saveStats(\"done\")"))
    }
}
