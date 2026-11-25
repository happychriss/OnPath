package com.onpath.app.ui.tour

import com.onpath.app.data.Geo
import com.onpath.app.data.TourKind
import com.onpath.app.data.TourSchema
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.recording.InterruptedLog
import com.onpath.app.routing.GeoPoint
import com.onpath.app.testkit.FakeRecording
import com.onpath.app.testkit.JdbcTours
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The app updated in the middle of a tour – nothing lost, nothing doubled (hard cases,
 * docs/team/items/2026-11-24-hard-case-test-coverage.md, "Phone reboot / app update in a tour"). For EVERY tour database
 * version that can still be on a phone (1 … 9, and a version 3 that has `rides` but no `acc`; 10 = no upgrade, the control):
 * the database file as that version had it (its tables and columns), a tour marked "active" with two pieces of points, the
 * upgrade (the steps of `TourDb.onUpgrade` / `onOpen`, with the REAL [TourSchema] statements – `TourDb` is an Android
 * SQLiteOpenHelper and cannot run here, the same mirror as TourSchemaTest), a new process on the file: the start-up check
 * finds the tour (Continue / Save is offered), name, activity, distance, ascent, descent, time and every point are as they
 * were, the next point is numbered on without a hole or a double ([JdbcTours.recordingCursor]), Continue records more points
 * on top (a new piece), and the end saves it.
 *
 * Not here: the upgrade running inside the real SQLiteOpenHelper (androidTest `TourDbMigrationTest`), TourInterruptedReceiver.
 */
@RunWith(Parameterized::class)
class SchemaUpgradeFlowTest(private val version: Int, private val quirk: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "schema {0} {1}")
        fun versions(): List<Array<Any>> = (1..TourSchema.VERSION).map { arrayOf<Any>(it, "") } + listOf(arrayOf<Any>(3, "rides-but-no-acc"))

        private const val NAME = "Waalweg am Morgen"
        private const val ASCENT = 85.5
        private const val DESCENT = 40.25
        private const val DURATION = 612.0
        private const val BASE_MS = 1_793_000_000_000L
    }

    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val problems = ProblemsRule()
    @get:Rule val composeState = OwnComposeSnapshot()

    private val opened = ArrayList<JdbcTours>()
    @After fun close() = opened.forEach { it.close() }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
    private fun Connection.columns(table: String): List<String> =
        createStatement().use { s -> s.executeQuery("PRAGMA table_info($table)").use { r -> buildList { while (r.next()) add(r.getString("name")) } } }

    /** The tour database as [version] had it (columns by the version that added them; see the `TourSchema.VERSION` doc). */
    private fun createOld(c: Connection, version: Int) {
        val rides = version >= 4 || quirk == "rides-but-no-acc"
        val acc = version >= 3 && quirk != "rides-but-no-acc"
        val tourCols = buildList {
            add("id INTEGER PRIMARY KEY AUTOINCREMENT"); add("kind TEXT NOT NULL"); add("name TEXT NOT NULL"); add("created INTEGER NOT NULL")
            add("activity TEXT"); add("sac INTEGER NOT NULL DEFAULT 3"); add("distance REAL NOT NULL DEFAULT 0"); add("ascent REAL NOT NULL DEFAULT 0")
            add("descent REAL NOT NULL DEFAULT 0"); add("duration REAL NOT NULL DEFAULT 0"); add("state TEXT NOT NULL DEFAULT 'done'"); add("waypoints TEXT")
            if (version >= 2) { add("uuid TEXT"); add("pinned TEXT"); add("deleted_at INTEGER") }
            if (rides) add("rides TEXT")
            if (version >= 5) add("draft INTEGER NOT NULL DEFAULT 0")
            if (version >= 7) add("difficulty TEXT")
            if (version >= 8) add("favourite INTEGER NOT NULL DEFAULT 0")
            if (version >= 9) add("days TEXT")
            if (version >= 10) add("anchors TEXT")
        }
        c.exec("CREATE TABLE tours(${tourCols.joinToString(", ")})")
        if (version >= 2) c.exec("CREATE INDEX tours_uuid ON tours(uuid)")
        c.exec("CREATE TABLE points(tour_id INTEGER NOT NULL, seq INTEGER NOT NULL, seg INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, ele REAL, time INTEGER" +
            (if (acc) ", acc REAL" else "") + ")")
        c.exec("CREATE INDEX points_tour ON points(tour_id, seq)")
        if (version >= 6) c.exec(if (version >= 10) TourSchema.CREATE_PLAN else TourSchema.CREATE_PLAN.replace(", anchors TEXT)", ")"))
    }

    /** `TourDb.onUpgrade` + `onOpen` (the same order and skips) with the real [TourSchema] statements. */
    private fun upgrade(c: Connection, old: Int) {
        if (old < TourSchema.VERSION) {
            if (old < 2) {
                for (col in listOf("uuid TEXT", "pinned TEXT", "deleted_at INTEGER")) c.exec("ALTER TABLE tours ADD COLUMN $col")
                c.exec("CREATE INDEX IF NOT EXISTS tours_uuid ON tours(uuid)")
            }
            TourSchema.tablesFrom(old).forEach { c.exec(it) }
            TourSchema.stepsFrom(old).forEach { if (it.column !in c.columns(it.table)) c.exec(it.sql) }
        }
        // onOpen (also assignMissingUuids, which onUpgrade runs too)
        val ids = c.createStatement().use { s -> s.executeQuery("SELECT id FROM tours WHERE uuid IS NULL OR uuid=''").use { r -> buildList { while (r.next()) add(r.getLong(1)) } } }
        ids.forEach { c.exec("UPDATE tours SET uuid='${java.util.UUID.randomUUID()}' WHERE id=$it") }
        c.exec(TourSchema.CREATE_PLAN)
        TourSchema.stepsFrom(9).forEach { if (it.column !in c.columns(it.table)) c.exec(it.sql) }
    }

    private var now = BASE_MS + 3_600_000L
    private var x = 0.0

    /** The points the first process wrote: piece 0 and piece 1 (a pause between), every 5 s, 7 m apart. */
    private val written = ArrayList<List<TrackPoint>>()
    /** The points recorded after the update. */
    private val newPoints = ArrayList<TrackPoint>()

    private fun writeOldTour(c: Connection, first: Int, second: Int) {
        var t = BASE_MS
        for (n in listOf(first, second)) {
            written += List(n) { val g = pt(x, 0.0); x += 7.0; t += 5000; TrackPoint(g.lat, g.lon, null, t) }
            t += 60_000
        }
        val metres = written.sumOf { s -> Geo.length(s.map { it.toGeo() }) }
        c.prepareStatement("INSERT INTO tours(kind, name, created, activity, sac, distance, ascent, descent, duration, state) VALUES (?,?,?,?,?,?,?,?,?,'active')").use {
            it.setString(1, TourKind.RECORDED.name); it.setString(2, NAME); it.setLong(3, BASE_MS); it.setString(4, "BIKE"); it.setInt(5, 2)
            it.setDouble(6, metres); it.setDouble(7, ASCENT); it.setDouble(8, DESCENT); it.setDouble(9, DURATION); it.executeUpdate()
        }
        var seq = 0
        written.forEachIndexed { seg, pts -> pts.forEach { p ->
            c.prepareStatement("INSERT INTO points(tour_id, seq, seg, lat, lon, ele, time) VALUES (1,?,?,?,?,NULL,?)").use {
                it.setInt(1, seq++); it.setInt(2, seg); it.setDouble(3, p.lat); it.setDouble(4, p.lon); it.setLong(5, p.timeMs!!); it.executeUpdate()
            }
        } }
    }

    private fun second(rec: FakeRecording, fix: Boolean) {
        now += 1000
        rec.tick()
        if (fix) { val g = pt(x, 0.0); rec.fix(g); newPoints += TrackPoint(g.lat, g.lon, null, now); x += 7.0 }
    }
    private fun walk(rec: FakeRecording, n: Int) = repeat(n) { repeat(4) { second(rec, false) }; second(rec, true) }

    private fun rows(tours: JdbcTours, id: Long): List<Triple<Int, Int, TrackPoint>> =
        tours.db.prepareStatement("SELECT seq, seg, lat, lon, time FROM points WHERE tour_id=? ORDER BY rowid").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { r -> buildList { while (r.next()) add(Triple(r.getInt(1), r.getInt(2), TrackPoint(r.getDouble(3), r.getDouble(4), null, r.getLong(5)))) } }
        }

    /** Guards the builder above: version 10 as built here IS [TourSchema.CREATE] (same columns), so the older ones can be trusted. */
    @Test fun theBuilderOfTheOldDatabasesMatchesTheCurrentSchema() {
        if (version != TourSchema.VERSION) return
        val a = DriverManager.getConnection("jdbc:sqlite::memory:"); val b = DriverManager.getConnection("jdbc:sqlite::memory:")
        createOld(a, TourSchema.VERSION); TourSchema.CREATE.forEach { b.exec(it) }
        for (t in listOf("tours", "points", "tour_plan")) assertEquals(t, b.columns(t), a.columns(t))
        a.close(); b.close()
    }

    @Test fun updatedMidTour_continueIsOffered_everythingIntact_numberedOn_continuedOnTop_saved() {
        val path = File(tmp.root, "onpath.db").path
        val old = DriverManager.getConnection("jdbc:sqlite:$path")
        createOld(old, version)
        writeOldTour(old, first = 40, second = 20)
        upgrade(old, version)
        old.close()

        // the new process (the app after the update)
        val tours = JdbcTours(path).also { opened += it }
        val rec = FakeRecording(tours, { now })
        val tour = TourController(rec)
        val savedDistance = written.sumOf { s -> Geo.length(s.map { it.toGeo() }) }

        val active = tours.findActiveRecording()
        assertNotNull("Continue / Save is offered: the tour is still marked as recording", active)
        assertEquals(NAME, active!!.name)
        assertEquals("BIKE", active.activity); assertEquals(2, active.sac)
        assertEquals(TourKind.RECORDED, active.kind)
        assertEquals(savedDistance, active.distanceM, 0.001); assertEquals(ASCENT, active.ascentM, 0.0); assertEquals(DESCENT, active.descentM, 0.0)
        assertEquals(DURATION, active.durationS, 0.0)
        assertFalse("every tour has its backup id after the upgrade", active.uuid.isBlank())
        assertFalse("not a plan kept under Zuletzt", active.draft); assertFalse(active.favourite); assertNull(active.deletedMs)
        assertEquals(listOf(40, 20), tours.loadSegments(active.id).map { it.size })
        assertEquals("the next point: no hole, no double", 60 to 1, tours.recordingCursor(active.id))
        assertNull("no service came back by itself", rec.state.value)
        tour.showUnfinished(active, tours.loadSegments(active.id).map { s -> s.map { GeoPoint(it.lat, it.lon) } })
        assertFalse("no new tour beside it while it is asked", tour.canStartNew)

        InterruptedLog.chose(active.name, InterruptedLog.Choice.CONTINUE) {}
        tour.continueTour(active)
        assertEquals("the same tour records again", active.id, rec.state.value!!.tourId)
        val resumed = tours.getTour(active.id)!!
        assertEquals("the continued tour's stats are what they were", savedDistance, resumed.distanceM, 0.5)
        assertEquals(ASCENT, resumed.ascentM, 0.0); assertEquals(DESCENT, resumed.descentM, 0.0)
        assertTrue("the time is not less", resumed.durationS >= DURATION)

        now += 120_000
        walk(rec, 30)
        tour.end(delete = false); tour.stop()
        assertEquals(active.id, rec.finished)

        val rows = rows(tours, active.id)
        assertEquals("every point once: 60 old + 30 new", 90, rows.size)
        assertEquals("numbered 0–89 in the order written", (0 until 90).toList(), rows.map { it.first })
        assertEquals(written.flatten() + newPoints, rows.map { it.third })
        assertEquals("the continuing is a new piece", List(40) { 0 } + List(20) { 1 } + List(30) { 2 }, rows.map { it.second })
        val done = tours.getTour(active.id)!!
        assertEquals("done", done.state)
        assertEquals(NAME, done.name); assertEquals("BIKE", done.activity); assertEquals(active.uuid, done.uuid)
        assertEquals("one tour", 1, tours.listTours().size)
        assertNull(tours.findActiveRecording())
        val walked = tours.loadSegments(active.id).sumOf { s -> Geo.length(s.map { it.toGeo() }) }
        assertEquals("the distance of all the points", walked, done.distanceM, 0.5)
        assertTrue(problems.none("recording"))
        assertTrue(problems.none("db"))
    }
}
