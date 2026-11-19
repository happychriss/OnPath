package com.onpath.app.testkit

import com.onpath.app.data.MultiDay
import com.onpath.app.data.Tour
import com.onpath.app.data.TourJson
import com.onpath.app.data.TourKind
import com.onpath.app.data.TourSchema
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import java.sql.Connection
import java.sql.DriverManager

/**
 * The tour database on the JVM (sqlite-jdbc, like TourSchemaTest): the app's real tables ([TourSchema.CREATE]) and its
 * real JSON columns ([TourJson.waypointsToJson] …) in memory or in a file, with the handful of statements a recording
 * and My routes need – the same SQL text as `data/TourDb` (which is an Android SQLiteOpenHelper and cannot run here).
 * For slices of the view model / the service that take a small store interface: this is the store behind their fake,
 * so a test sees real rows, real ordering and a real "disk full" ([failWrites]).
 *
 * Not a copy of TourDb's behaviour beyond these statements – what TourDb does on top (backups, corrupt-file handling)
 * stays covered by the emulator tests.
 */
class JdbcTours(path: String = ":memory:") : AutoCloseable {
    val db: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    /** The next writes throw (a full or broken database), counted down per write. */
    var failWrites = 0

    init { if (tables().isEmpty()) TourSchema.CREATE.forEach { exec(it) } }

    fun exec(sql: String, vararg args: Any?): Int = db.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, v -> st.setObject(i + 1, v) }
        st.execute(); st.updateCount
    }

    private fun write() { if (failWrites > 0) { failWrites--; throw java.sql.SQLException("database or disk is full") } }

    fun tables(): List<String> = db.createStatement().use { s ->
        s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { r -> buildList { while (r.next()) add(r.getString(1)) } }
    }

    /**
     * [TourDb.insertTour]: the tour row and its points in one transaction; returns the id. The columns written are
     * TourDb's (TourDbMirrorTest compares the two lists): what a saved plan carries – stats, pins, difficulty, days
     * ([days] null leaves the column NULL, as TourDb does).
     */
    fun insertTour(kind: TourKind, name: String, activity: String?, sac: Int, segments: List<List<TrackPoint>> = emptyList(),
                   waypoints: List<GpxWaypoint> = emptyList(), state: String = "done", distance: Double = 0.0,
                   uuid: String = java.util.UUID.randomUUID().toString(), createdMs: Long = System.currentTimeMillis(), draft: Boolean = false,
                   ascent: Double = 0.0, descent: Double = 0.0, duration: Double = 0.0, pinned: Set<Int> = emptySet(),
                   difficulty: String? = null, favourite: Boolean = false, days: MultiDay? = null): Long {
        write()
        db.autoCommit = false
        try {
            exec("INSERT INTO tours(kind, name, created, uuid, pinned, activity, sac, distance, ascent, descent, duration, state, waypoints, draft, " +
                "difficulty, favourite, days) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", kind.name, name, createdMs, uuid, TourJson.pinnedToJson(pinned),
                activity, sac, distance, ascent, descent, duration, state, TourJson.waypointsToJson(waypoints), if (draft) 1 else 0, difficulty,
                if (favourite) 1 else 0, days?.toJson())
            val id = db.createStatement().use { s -> s.executeQuery("SELECT last_insert_rowid()").use { r -> r.next(); r.getLong(1) } }
            var seq = 0
            segments.forEachIndexed { seg, pts -> pts.forEach { point(id, seq++, seg, it, null) } }
            db.commit()
            return id
        } catch (e: Exception) { db.rollback(); throw e } finally { db.autoCommit = true }
    }

    /**
     * [TourDb.replacePlanned]: a saved planned route overwritten in place, in one transaction – stats, via points, pins,
     * difficulty, line; name and date stay. False when the route is gone, in the recycle bin or not a planned one (nothing
     * changed). [days] null keeps the stored days, [clearDays] removes them – as TourDb.
     */
    fun replacePlanned(id: Long, activity: String?, sac: Int, distance: Double, ascent: Double, descent: Double, duration: Double,
                       segments: List<List<TrackPoint>>, waypoints: List<GpxWaypoint>, pinned: Set<Int> = emptySet(),
                       difficulty: String? = null, days: MultiDay? = null, clearDays: Boolean = false): Boolean {
        write()
        db.autoCommit = false
        try {
            val args = mutableListOf<Any?>(activity, sac, distance, ascent, descent, duration, TourJson.waypointsToJson(waypoints),
                TourJson.pinnedToJson(pinned), difficulty)
            if (days != null) args += days.toJson()
            args += id; args += TourKind.PLANNED.name
            val n = exec("UPDATE tours SET activity=?, sac=?, distance=?, ascent=?, descent=?, duration=?, waypoints=?, pinned=?, difficulty=?" +
                (if (days != null) ", days=?" else if (clearDays) ", days=NULL" else "") +
                " WHERE id=? AND kind=? AND deleted_at IS NULL", *args.toTypedArray())
            if (n == 0) { db.rollback(); return false }
            exec("DELETE FROM points WHERE tour_id=?", id)
            var seq = 0
            segments.forEachIndexed { seg, pts -> pts.forEach { point(id, seq++, seg, it, null) } }
            db.commit()
            return true
        } catch (e: Exception) { db.rollback(); throw e } finally { db.autoCommit = true }
    }

    /** [TourDb.setFavourite]: false when the tour is gone. */
    fun setFavourite(id: Long, favourite: Boolean): Boolean { write(); return exec("UPDATE tours SET favourite=? WHERE id=?", if (favourite) 1 else 0, id) > 0 }

    private fun point(id: Long, seq: Int, seg: Int, p: TrackPoint, acc: Float?) =
        exec("INSERT INTO points(tour_id, seq, seg, lat, lon, ele, time, acc) VALUES (?,?,?,?,?,?,?,?)", id, seq, seg, p.lat, p.lon, p.ele, p.timeMs,
            acc?.takeIf { it.isFinite() }?.toDouble())

    /** [TourDb.appendPoint]: throws when the write fails (a silent failure would lose the track unnoticed). */
    fun appendPoint(tourId: Long, seq: Int, seg: Int, p: TrackPoint, accuracyM: Float? = null) { write(); point(tourId, seq, seg, p, accuracyM) }

    /** [TourDb.updateStats]: the rows changed (0 = the tour row is gone). */
    fun updateStats(tourId: Long, distance: Double, ascent: Double, descent: Double, duration: Double, state: String): Int {
        write()
        return exec("UPDATE tours SET distance=?, ascent=?, descent=?, duration=?, state=? WHERE id=?", distance, ascent, descent, duration, state, tourId)
    }

    fun setDeleted(id: Long, deletedMs: Long?): Boolean { write(); return exec("UPDATE tours SET deleted_at=? WHERE id=?", deletedMs, id) == 1 }

    /** [TourDb.setDraft]: a plan under Zuletzt ↔ a saved route. */
    fun setDraft(id: Long, draft: Boolean): Boolean { write(); return exec("UPDATE tours SET draft=? WHERE id=?", if (draft) 1 else 0, id) > 0 }

    /** [TourDb.rename]. */
    fun rename(id: Long, name: String) { write(); exec("UPDATE tours SET name=? WHERE id=?", name, id) }

    /** [TourDb.deleteTour]: the row, its points and its plan copy – gone for good. */
    fun deleteTour(id: Long) {
        write()
        exec("DELETE FROM points WHERE tour_id=?", id); exec("DELETE FROM tours WHERE id=?", id); exec(TourSchema.PLAN_DELETE, id)
    }

    /** [TourDb.finishOtherActive]. */
    fun finishOtherActive(exceptId: Long): Int =
        exec("UPDATE tours SET state='done' WHERE kind=? AND state='active' AND id<>?", TourKind.RECORDED.name, exceptId)

    private fun query(sql: String, vararg args: Any?): List<Tour> = db.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, v -> st.setObject(i + 1, v) }
        st.executeQuery().use { r ->
            buildList {
                while (r.next()) add(Tour(
                    id = r.getLong("id"), kind = TourKind.valueOf(r.getString("kind")), name = r.getString("name"),
                    createdMs = r.getLong("created"), activity = r.getString("activity"), sac = r.getInt("sac"),
                    distanceM = r.getDouble("distance"), ascentM = r.getDouble("ascent"), descentM = r.getDouble("descent"),
                    durationS = r.getDouble("duration"), state = r.getString("state") ?: "done",
                    waypoints = TourJson.jsonToWaypoints(r.getString("waypoints")), uuid = r.getString("uuid") ?: "",
                    pinned = TourJson.jsonToPinned(r.getString("pinned")),
                    deletedMs = r.getLong("deleted_at").takeIf { !r.wasNull() },
                    draft = r.getInt("draft") == 1, hasPlan = r.getInt("has_plan") == 1,
                    difficulty = r.getString("difficulty"), favourite = r.getInt("favourite") == 1, days = MultiDay.fromJson(r.getString("days")),
                ))
            }
        }
    }

    /** [TourDb.listTours]: My routes – everything not in the recycle bin, newest first. */
    fun listTours(): List<Tour> = query("SELECT *, ${TourSchema.HAS_PLAN} FROM tours WHERE deleted_at IS NULL ORDER BY created DESC")
    fun listDeleted(): List<Tour> = query("SELECT *, ${TourSchema.HAS_PLAN} FROM tours WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun getTour(id: Long): Tour? = query("SELECT *, ${TourSchema.HAS_PLAN} FROM tours WHERE id=?", id).firstOrNull()

    /** [TourDb.findActiveRecording]: the tour still marked as recording (a restart mid-tour). */
    fun findActiveRecording(): Tour? = query("SELECT *, ${TourSchema.HAS_PLAN} FROM tours WHERE kind=? AND state='active' AND deleted_at IS NULL " +
        "ORDER BY created DESC LIMIT 1", TourKind.RECORDED.name).firstOrNull()

    /** [TourDb.loadSegments]. */
    fun loadSegments(id: Long): List<List<TrackPoint>> = db.prepareStatement("SELECT seg, lat, lon, ele, time FROM points WHERE tour_id=? ORDER BY seq").use { st ->
        st.setLong(1, id)
        st.executeQuery().use { r ->
            val out = ArrayList<MutableList<TrackPoint>>()
            var last = Int.MIN_VALUE
            while (r.next()) {
                val seg = r.getInt(1)
                if (seg != last) { out += ArrayList<TrackPoint>(); last = seg }
                val ele = r.getDouble(4).takeIf { !r.wasNull() }
                val time = r.getLong(5).takeIf { !r.wasNull() }
                out.last() += TrackPoint(r.getDouble(2), r.getDouble(3), ele, time)
            }
            out
        }
    }

    override fun close() = db.close()
}
