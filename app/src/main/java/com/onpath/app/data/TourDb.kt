package com.onpath.app.data

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import com.onpath.app.debug.Problems
import com.onpath.app.routing.Anchors
import com.onpath.app.routing.GeoPoint
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import org.json.JSONArray
import org.json.JSONObject
import android.database.Cursor
import com.onpath.app.rides.RideRecord
import java.util.UUID
import java.util.Locale

/**
 * All user data in one SQLite database with write-ahead logging.
 * Recording writes every accepted point immediately (R-07).
 */
class TourDb internal constructor(context: Context, name: String = "onpath.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, VERSION, KeepCorruptFile()) {

    init { setWriteAheadLoggingEnabled(true) }

    /**
     * Android's default handler deletes a corrupt database – every recorded tour with it. This one moves the file
     * aside (`onpath.db.corrupt-<time>`) so the tours can still be rescued, and lets the app start with a fresh DB.
     */
    private class KeepCorruptFile : DatabaseErrorHandler {
        override fun onCorruption(db: SQLiteDatabase) {
            val path = db.path
            Problems.error("db", "Tour database corrupt – moved aside, starting with an empty one ($path)")
            try { if (db.isOpen) db.close() } catch (e: Exception) { Problems.warn("db", "Corrupt tour database could not be closed", e) }
            if (path.isNullOrEmpty() || path == ":memory:") return
            try {
                val stamp = System.currentTimeMillis()
                for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                    val f = File(path + suffix)
                    if (f.exists() && !f.renameTo(File("$path.corrupt-$stamp$suffix"))) {
                        Problems.error("db", "Corrupt tour database file ${f.name} could not be moved aside – deleted")
                        f.delete()
                    }
                }
            } catch (e: Exception) { Problems.error("db", "Corrupt tour database could not be moved aside", e) }
        }
    }

    /** An older test build over a newer database: keep the data, never throw at start. */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    override fun onCreate(db: SQLiteDatabase) {
        TourSchema.CREATE.forEach { db.execSQL(it) }
    }

    /**
     * 1 → 2 (backup): uuid (backup files, restore dedupe), pinned stops, recycle bin (deleted_at).
     * Every existing tour gets its uuid here. A column already there is fine.
     * 2 → 3: GPS accuracy per recorded point (`points.acc`, metres, null = unknown / imported), so a later analysis
     * can tell weak fixes from no fix.
     * 3 → 4 (vehicle detection): `tours.rides` (JSON, [com.onpath.app.rides.RideRecord]); points untouched.
     * 4 → 5 (owner 2026-11-06, "Zuletzt"): `tours.draft` – 1 = a plan kept automatically when it left the map (My routes →
     * Zuletzt), 0 = saved; every existing tour stays saved.
     * 5 → 6 (owner 2026-11-07): the table `tour_plan` – the frozen copy of the plan a recorded tour followed ([TourPlan]).
     * 6 → 7 (owner 2026-11-12): `tours.difficulty` – a planned route's difficulty level at saving ([Tour.difficulty]);
     * every existing tour reads null (not known).
     * 7 → 8 (owner 2026-11-13): `tours.favourite` – starred in My routes ([Tour.favourite]); every existing tour 0.
     * 8 → 9 (multi-day tours, stage 4): `tours.days` – a planned route's days ([Tour.days], JSON); every existing tour
     * null (a normal route).
     * 9 → 10 (detour stitch): `tours.anchors` and `tour_plan.anchors` – a plan's hidden via points; every existing row null.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            for (col in listOf("uuid TEXT", "pinned TEXT", "deleted_at INTEGER")) {
                try { db.execSQL("ALTER TABLE tours ADD COLUMN $col") } catch (e: Exception) {
                    Problems.info("db", "Column ${col.substringBefore(' ')} already there", e)
                }
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS tours_uuid ON tours(uuid)")
        }
        for (step in TourSchema.stepsFrom(oldVersion)) {
            val have = db.rawQuery("PRAGMA table_info(${step.table})", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
            }
            if (step.column in have) { Problems.info("db", "Column ${step.column} already there"); continue }
            try { db.execSQL(step.sql) } catch (e: Exception) { Problems.error("db", "Column ${step.column} could not be added", e) }
        }
        for (sql in TourSchema.tablesFrom(oldVersion)) {
            try { db.execSQL(sql) } catch (e: Exception) { Problems.error("db", "Table could not be added: ${sql.take(40)}", e) }
        }
        assignMissingUuids(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // an older build (after a downgrade) may have added tours without uuid
        if (!db.isReadOnly) try { assignMissingUuids(db) } catch (e: Exception) { Problems.warn("db", "Tour ids for the backup not assigned", e) }
        // the plan copies' table (schema 6) must exist before My routes reads `has_plan` – also when the upgrade could not add it
        if (!db.isReadOnly) try { db.execSQL(TourSchema.CREATE_PLAN) } catch (e: Exception) { Problems.error("db", "Table of the followed plans not there", e) }
    }

    private fun assignMissingUuids(db: SQLiteDatabase) {
        val ids = db.rawQuery("SELECT id FROM tours WHERE uuid IS NULL OR uuid=''", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
        ids.forEach { id -> db.update("tours", ContentValues().apply { put("uuid", newUuid()) }, "id=?", arrayOf(id.toString())) }
    }

    // ---- tours ----

    fun insertTour(
        kind: TourKind, name: String, activity: String?, sac: Int,
        distance: Double, ascent: Double, descent: Double, duration: Double,
        segments: List<List<TrackPoint>>, waypoints: List<GpxWaypoint> = emptyList(),
        state: String = "done",
        pinned: Set<Int> = emptySet(),
        /** Restore: the backup's id and date; otherwise new. */
        uuid: String = newUuid(),
        createdMs: Long = System.currentTimeMillis(),
        rides: RideRecord? = null,
        /** A plan kept under "Zuletzt" (not saved by the user; no backup, no browser sync). */
        draft: Boolean = false,
        /** [Tour.difficulty]; null = not known. */
        difficulty: String? = null,
        /** [Tour.favourite] (restore, a route from the browser). */
        favourite: Boolean = false,
        /** [Tour.days] of a multi-day tour; null = a normal route. */
        days: MultiDay? = null,
        /** [Tour.anchors] of a planned route (docs/design/detour-stitch.md §3); empty = none (the column stays NULL). */
        anchors: Map<Int, Anchors> = emptyMap(),
    ): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("tours", null, ContentValues().apply {
                put("kind", kind.name); put("name", name); put("created", createdMs)
                if (rides != null) put("rides", rides.toJson().toString())
                put("uuid", uuid); put("pinned", pinnedToJson(pinned))
                put("activity", activity); put("sac", sac); put("distance", distance); put("ascent", ascent)
                put("descent", descent); put("duration", duration); put("state", state)
                put("waypoints", waypointsToJson(waypoints))
                put("draft", if (draft) 1 else 0)
                put("difficulty", difficulty)
                put("favourite", if (favourite) 1 else 0)
                if (days != null) put("days", days.toJson())
                AnchorsJson.write(waypoints.map { GeoPoint(it.lat, it.lon) }, anchors)?.let { put("anchors", it) }
            })
            var seq = 0
            segments.forEachIndexed { segIdx, seg -> seg.forEach { insertPoint(db, id, seq++, segIdx, it) } }
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    /** My routes: everything not in the recycle bin. */
    fun listTours(): List<Tour> = readableDatabase.rawQuery(
        "SELECT *, ${TourSchema.HAS_PLAN} FROM tours WHERE deleted_at IS NULL ORDER BY created DESC", null
    ).use { c -> buildList { while (c.moveToNext()) add(readTour(c)) } }

    /** The recycle bin, most recently deleted first. */
    fun listDeleted(): List<Tour> = readableDatabase.rawQuery(
        "SELECT *, ${TourSchema.HAS_PLAN} FROM tours WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC", null
    ).use { c -> buildList { while (c.moveToNext()) add(readTour(c)) } }

    /** Number of tours including the recycle bin (restore offer on an empty database). */
    fun countAll(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM tours", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** uuids of every tour on the phone, recycle bin included (restore skips these). */
    fun allUuids(): Set<String> = readableDatabase.rawQuery("SELECT uuid FROM tours WHERE uuid IS NOT NULL", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }

    /** A plan under "Zuletzt" becomes saved ([draft] false) or the other way. False when the tour is gone. */
    fun setDraft(id: Long, draft: Boolean): Boolean = writableDatabase.update("tours", ContentValues().apply {
        put("draft", if (draft) 1 else 0)
    }, "id=?", arrayOf(id.toString())) > 0

    /**
     * Stars a tour in My routes or takes the star away ([Tour.favourite]); the recycle bin keeps it. False when the tour is
     * gone. The caller writes the backup file again (Backups.tourChanged) – the flag lives in its record.
     */
    fun setFavourite(id: Long, favourite: Boolean): Boolean = writableDatabase.update("tours", ContentValues().apply {
        put("favourite", if (favourite) 1 else 0)
    }, "id=?", arrayOf(id.toString())) > 0

    /** Moves a tour to the recycle bin ([deletedMs]) or back (null). False when the tour is gone. */
    fun setDeleted(id: Long, deletedMs: Long?): Boolean = writableDatabase.update("tours", ContentValues().apply {
        if (deletedMs == null) putNull("deleted_at") else put("deleted_at", deletedMs)
    }, "id=?", arrayOf(id.toString())) > 0

    /** Removes recycle-bin entries deleted before [cutoffMs] for good; returns their uuids (their files go too). */
    fun purgeDeleted(cutoffMs: Long): List<String> {
        val old = readableDatabase.rawQuery("SELECT id, uuid FROM tours WHERE deleted_at IS NOT NULL AND deleted_at < ?",
            arrayOf(cutoffMs.toString())).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to (c.getString(1) ?: "")) } }
        old.forEach { (id, _) -> deleteTour(id) }
        return old.map { it.second }.filter { it.isNotEmpty() }
    }

    fun getTour(id: Long): Tour? = readableDatabase.rawQuery(
        "SELECT *, ${TourSchema.HAS_PLAN} FROM tours WHERE id=?", arrayOf(id.toString())
    ).use { c -> if (c.moveToFirst()) readTour(c) else null }

    fun findActiveRecording(): Tour? = readableDatabase.rawQuery(
        "SELECT * FROM tours WHERE kind=? AND state='active' AND deleted_at IS NULL ORDER BY created DESC LIMIT 1",
        arrayOf(TourKind.RECORDED.name)
    ).use { c -> if (c.moveToFirst()) readTour(c) else null }

    /**
     * Only one recording can be active. When a new one starts while an older one is still marked active
     * (crash + restart race), the older one is closed as "done" so it stays visible in My routes instead of hidden.
     */
    fun finishOtherActive(exceptId: Long): Int = writableDatabase.update(
        "tours", ContentValues().apply { put("state", "done") },
        "kind=? AND state='active' AND id<>?", arrayOf(TourKind.RECORDED.name, exceptId.toString())
    )

    /**
     * Overwrites a saved planned route in place (⋮ Save after loading it): stats, via points and line; name and
     * date stay. Returns false when the route is gone (deleted meanwhile) – the caller saves a new one instead.
     */
    fun replacePlanned(
        id: Long, activity: String?, sac: Int,
        distance: Double, ascent: Double, descent: Double, duration: Double,
        segments: List<List<TrackPoint>>, waypoints: List<GpxWaypoint>, pinned: Set<Int> = emptySet(),
        /** [Tour.difficulty] of the new version; null = not known (the old level would describe the old line). */
        difficulty: String? = null,
        /**
         * [Tour.days] of the new version; null keeps the stored days (a synced multi-day plan routed afterwards, an
         * older caller) – never wiped by a write that does not know them. [clearDays]: the new version has none (a plan
         * from the browser that is no multi-day tour any more).
         */
        days: MultiDay? = null,
        clearDays: Boolean = false,
        /** [Tour.anchors] of the new version: written as they are, empty = NULL (unlike [days] there is no "keep"). */
        anchors: Map<Int, Anchors> = emptyMap(),
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val n = db.update("tours", ContentValues().apply {
                put("activity", activity); put("sac", sac); put("distance", distance); put("ascent", ascent)
                put("descent", descent); put("duration", duration); put("waypoints", waypointsToJson(waypoints))
                put("pinned", pinnedToJson(pinned)); put("difficulty", difficulty)
                if (days != null) put("days", days.toJson()) else if (clearDays) putNull("days")
                val a = AnchorsJson.write(waypoints.map { GeoPoint(it.lat, it.lon) }, anchors)
                if (a != null) put("anchors", a) else putNull("anchors")
            }, "id=? AND kind=? AND deleted_at IS NULL", arrayOf(id.toString(), TourKind.PLANNED.name))
            if (n == 0) return false
            db.delete("points", "tour_id=?", arrayOf(id.toString()))
            var seq = 0
            segments.forEachIndexed { segIdx, seg -> seg.forEach { insertPoint(db, id, seq++, segIdx, it) } }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Overwrites an imported track in place (plan sync: a newer version from the browser): line, waypoints, activity
     * and stats; name, date and uuid stay. False when the track is gone or not an imported one.
     */
    fun replaceImported(
        id: Long, activity: String?, sac: Int,
        distance: Double, ascent: Double, descent: Double, duration: Double,
        segments: List<List<TrackPoint>>, waypoints: List<GpxWaypoint>,
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val n = db.update("tours", ContentValues().apply {
                put("activity", activity); put("sac", sac); put("distance", distance); put("ascent", ascent)
                put("descent", descent); put("duration", duration); put("waypoints", waypointsToJson(waypoints))
            }, "id=? AND kind=?", arrayOf(id.toString(), TourKind.IMPORTED.name))
            if (n == 0) return false
            db.delete("points", "tour_id=?", arrayOf(id.toString()))
            var seq = 0
            segments.forEachIndexed { segIdx, seg -> seg.forEach { insertPoint(db, id, seq++, segIdx, it) } }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun rename(id: Long, name: String) {
        writableDatabase.update("tours", ContentValues().apply { put("name", name) }, "id=?", arrayOf(id.toString()))
    }

    fun deleteTour(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("points", "tour_id=?", arrayOf(id.toString()))
            db.delete("tours", "id=?", arrayOf(id.toString()))
            db.execSQL(TourSchema.PLAN_DELETE, arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * A short summary of tour [id]'s stored line ("count:sumLat:sumLon"; null = no points): My routes' map pictures are
     * made again when it changes (map/RouteThumbs). One query on the points index of that tour, no line loaded.
     */
    fun lineKey(id: Long): String? = readableDatabase.rawQuery(
        "SELECT COUNT(*), TOTAL(lat), TOTAL(lon) FROM points WHERE tour_id=?", arrayOf(id.toString())
    ).use { c ->
        if (!c.moveToFirst() || c.getLong(0) == 0L) null
        else "${c.getLong(0)}:" + "%.6f".format(Locale.ROOT, c.getDouble(1)) + ":" + "%.6f".format(Locale.ROOT, c.getDouble(2))
    }

    fun loadSegments(id: Long): List<List<TrackPoint>> {
        val out = mutableListOf<MutableList<TrackPoint>>()
        var lastSeg = Int.MIN_VALUE
        readableDatabase.rawQuery(
            "SELECT seg, lat, lon, ele, time FROM points WHERE tour_id=? ORDER BY seq", arrayOf(id.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val seg = c.getInt(0)
                if (seg != lastSeg) { out += mutableListOf<TrackPoint>(); lastSeg = seg }
                out.last() += TrackPoint(
                    c.getDouble(1), c.getDouble(2),
                    if (c.isNull(3)) null else c.getDouble(3),
                    if (c.isNull(4)) null else c.getLong(4),
                )
            }
        }
        return out
    }

    // ---- the plan a recorded tour followed (schema 6) ----

    /**
     * Stores [plan] as the plan tour [tourId] followed – the newest version replaces the one before (replanned during the
     * tour: the last version counts). Throws on failure (the caller reports it, like every recording write).
     */
    fun setTourPlan(tourId: Long, plan: TourPlan) {
        writableDatabase.execSQL(TourSchema.PLAN_UPSERT, TourSchema.planArgs(tourId, plan))
    }

    /** The points of the plan tour [tourId] followed (no line read); null = none. */
    fun planPoints(tourId: Long): List<GpxWaypoint>? = readableDatabase.rawQuery(
        "SELECT waypoints FROM tour_plan WHERE tour_id=?", arrayOf(tourId.toString())
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) jsonToWaypoints(c.getString(0)) else null }

    /** The plan tour [tourId] followed; null = none (old tour, "Nur aufzeichnen") or unreadable (reported). */
    fun getTourPlan(tourId: Long): TourPlan? = readableDatabase.rawQuery(
        "SELECT * FROM tour_plan WHERE tour_id=?", arrayOf(tourId.toString())
    ).use { c ->
        if (!c.moveToFirst()) return null
        fun s(n: String) = c.getColumnIndex(n).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getString(it) }
        fun d(n: String) = c.getColumnIndex(n).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getDouble(it) } ?: 0.0
        try {
            val waypoints = jsonToWaypoints(s("waypoints"))
            TourPlan(name = s("name"), activity = s("activity") ?: "HIKE", sac = d("sac").toInt(),
                waypoints = waypoints, pinned = jsonToPinned(s("pinned")),
                line = TourPlan.lineFromJson(s("line")), distanceM = d("distance"), ascentM = d("ascent"),
                descentM = d("descent"), durationS = d("duration"), sourceUuid = s("source_uuid"),
                updatedMs = d("updated").toLong(), anchors = AnchorsJson.read(s("anchors"), waypoints.map { GeoPoint(it.lat, it.lon) })).takeIf { it.usable }
        } catch (e: Exception) { Problems.warn("db", "The followed plan of tour $tourId is unreadable – the tour stays without it", e); null }
    }

    /**
     * The activity was switched during the tour on the route (the service took the proposal): the stored plan copy is
     * for that activity now. Rows changed (0 = the tour followed no plan).
     */
    fun setTourPlanActivity(tourId: Long, activity: String, sac: Int): Int = writableDatabase.update("tour_plan",
        ContentValues().apply { put("activity", activity); put("sac", sac); put("updated", System.currentTimeMillis()); putNull("anchors") },
        "tour_id=?", arrayOf(tourId.toString()))

    // ---- recording ----

    /** [accuracyM] = the fix's horizontal accuracy (stored for later analysis, not shown). */
    fun appendPoint(tourId: Long, seq: Int, seg: Int, p: TrackPoint, accuracyM: Float? = null) =
        insertPoint(writableDatabase, tourId, seq, seg, p, accuracyM)

    /** Stored accuracy per point of [tourId] in order (null = unknown) – diagnostics and tests. */
    fun pointAccuracies(tourId: Long): List<Double?> = readableDatabase.rawQuery(
        "SELECT acc FROM points WHERE tour_id=? ORDER BY seq", arrayOf(tourId.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(if (c.isNull(0)) null else c.getDouble(0)) } }

    /** The activity switched during a recording (Navigate → activity check). */
    fun updateActivity(tourId: Long, activity: String, sac: Int) {
        writableDatabase.update("tours", ContentValues().apply { put("activity", activity); put("sac", sac) },
            "id=?", arrayOf(tourId.toString()))
    }

    /** Returns the rows changed: 0 = the tour row is gone (a corrupt database was replaced mid-tour – [rescueRecording]). */
    fun updateStats(tourId: Long, distance: Double, ascent: Double, descent: Double, duration: Double, state: String): Int =
        writableDatabase.update("tours", ContentValues().apply {
            put("distance", distance); put("ascent", ascent); put("descent", descent)
            put("duration", duration); put("state", state)
        }, "id=?", arrayOf(tourId.toString()))

    /**
     * Review recording M7: the database was corrupt mid-tour, moved aside ([KeepCorruptFile]) and replaced by an empty
     * one – the running tour has no row there and its new points would end up nowhere. Puts the tour back under its id
     * with every point the service still holds ([segments]); points already written for it are replaced. One transaction.
     */
    fun rescueRecording(id: Long, name: String, activity: String?, sac: Int, createdMs: Long, segments: List<List<TrackPoint>>,
                        distance: Double, ascent: Double, descent: Double, duration: Double): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("points", "tour_id=?", arrayOf(id.toString()))
            db.insertOrThrow("tours", null, ContentValues().apply {
                put("id", id); put("kind", TourKind.RECORDED.name); put("name", name); put("created", createdMs)
                put("uuid", newUuid()); put("pinned", pinnedToJson(emptySet())); put("waypoints", waypointsToJson(emptyList()))
                put("activity", activity); put("sac", sac); put("distance", distance); put("ascent", ascent)
                put("descent", descent); put("duration", duration); put("state", "active")
            })
            var seq = 0
            segments.forEachIndexed { segIdx, seg -> seg.forEach { insertPoint(db, id, seq++, segIdx, it) } }
            db.setTransactionSuccessful()
            return seq
        } finally {
            db.endTransaction()
        }
    }

    /**
     * The rides of a tour and its stats under the chosen reading (rides/RideApply) in one step. The points are never
     * touched: [rides] only says how to read them. False when the tour is gone.
     */
    fun setRides(tourId: Long, rides: RideRecord?, distance: Double, ascent: Double, descent: Double, duration: Double): Boolean =
        writableDatabase.update("tours", ContentValues().apply {
            if (rides == null) putNull("rides") else put("rides", rides.toJson().toString())
            put("distance", distance); put("ascent", ascent); put("descent", descent); put("duration", duration)
        }, "id=?", arrayOf(tourId.toString())) > 0

    // ---- live sharing (docs/design/live-tracking.md §7): reads only, on the WAL's reader connections ----

    /** The points of [tourId] numbered above [seq], in the order of their numbers, at most [max]. */
    fun livePointsAfter(tourId: Long, seq: Int, max: Int): List<com.onpath.app.live.LivePointRow> = readableDatabase.rawQuery(
        com.onpath.app.live.LiveSql.POINTS_AFTER, arrayOf(tourId.toString(), seq.toString(), max.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(livePoint(c)) } }

    /** The newest point of [tourId]; null = none yet. */
    fun liveLastPoint(tourId: Long): com.onpath.app.live.LivePointRow? = readableDatabase.rawQuery(
        com.onpath.app.live.LiveSql.LAST_POINT, arrayOf(tourId.toString())
    ).use { c -> if (c.moveToFirst()) livePoint(c) else null }

    private fun livePoint(c: Cursor) = com.onpath.app.live.LivePointRow(
        c.getInt(0), c.getInt(1), c.getDouble(2), c.getDouble(3), if (c.isNull(4)) null else c.getDouble(4),
        if (c.isNull(5)) null else c.getLong(5), if (c.isNull(6)) null else c.getDouble(6))

    /** Returns (next seq, last seg) so a recording can continue after a restart. */
    fun recordingCursor(tourId: Long): Pair<Int, Int> = readableDatabase.rawQuery(
        "SELECT MAX(seq), MAX(seg) FROM points WHERE tour_id=?", arrayOf(tourId.toString())
    ).use { c ->
        if (c.moveToFirst() && !c.isNull(0)) (c.getInt(0) + 1) to c.getInt(1) else 0 to 0
    }

    /** Throws on failure (disk full, corrupt DB): a silent `insert()` returning -1 would lose the track unnoticed. */
    private fun insertPoint(db: SQLiteDatabase, id: Long, seq: Int, seg: Int, p: TrackPoint, accuracyM: Float? = null) {
        db.insertOrThrow("points", null, ContentValues().apply {
            put("tour_id", id); put("seq", seq); put("seg", seg); put("lat", p.lat); put("lon", p.lon)
            if (p.ele != null) put("ele", p.ele) else putNull("ele")
            if (p.timeMs != null) put("time", p.timeMs) else putNull("time")
            if (accuracyM != null && accuracyM.isFinite()) put("acc", accuracyM.toDouble())
        })
    }

    private fun readTour(c: Cursor): Tour {
        fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n))
        fun d(n: String) = c.getDouble(c.getColumnIndexOrThrow(n))
        return Tour(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            kind = runCatching { TourKind.valueOf(s("kind")) }.getOrElse {
                Problems.warn("db", "Tour with unknown kind, shown as imported", it); TourKind.IMPORTED
            },
            name = s("name"),
            createdMs = c.getLong(c.getColumnIndexOrThrow("created")),
            activity = s("activity"),
            sac = c.getInt(c.getColumnIndexOrThrow("sac")),
            distanceM = d("distance"), ascentM = d("ascent"), descentM = d("descent"), durationS = d("duration"),
            state = s("state") ?: "done",
            waypoints = jsonToWaypoints(s("waypoints")),
            uuid = opt(c, "uuid")?.let { c.getString(it) } ?: "",
            pinned = opt(c, "pinned")?.let { jsonToPinned(c.getString(it)) } ?: emptySet(),
            deletedMs = opt(c, "deleted_at")?.let { c.getLong(it) },
            rides = opt(c, "rides")?.let { RideRecord.fromJson(c.getString(it)) },
            draft = opt(c, "draft")?.let { c.getInt(it) == 1 } ?: false,
            hasPlan = opt(c, "has_plan")?.let { c.getInt(it) == 1 } ?: false,
            difficulty = opt(c, "difficulty")?.let { c.getString(it) },
            favourite = opt(c, "favourite")?.let { c.getInt(it) == 1 } ?: false,
            days = opt(c, "days")?.let { MultiDay.fromJson(c.getString(it)) },
            anchors = opt(c, "anchors")?.let { i -> AnchorsJson.read(c.getString(i), jsonToWaypoints(s("waypoints")).map { GeoPoint(it.lat, it.lon) }) } ?: emptyMap(),
        )
    }

    /** Column index when the column exists and is not null. */
    private fun opt(c: Cursor, n: String): Int? = c.getColumnIndex(n).takeIf { it >= 0 && !c.isNull(it) }

    companion object {
        const val VERSION = TourSchema.VERSION
        @Volatile private var instance: TourDb? = null

        fun newUuid(): String = UUID.randomUUID().toString()

        fun pinnedToJson(p: Set<Int>): String = TourJson.pinnedToJson(p)

        fun jsonToPinned(json: String?): Set<Int> = TourJson.jsonToPinned(json)

        fun get(context: Context): TourDb =
            instance ?: synchronized(this) { instance ?: TourDb(context).also { instance = it } }

        fun waypointsToJson(list: List<GpxWaypoint>): String = TourJson.waypointsToJson(list)

        fun jsonToWaypoints(json: String?): List<GpxWaypoint> = TourJson.jsonToWaypoints(json)
    }
}
