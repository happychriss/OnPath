package com.onpath.app.data

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import com.onpath.app.debug.Problems
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.routes_kind_imported
import com.onpath.strings.routes_kind_planned
import com.onpath.strings.routes_kind_recorded
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import org.json.JSONArray
import org.json.JSONObject

enum class TourKind(private val labelRes: org.jetbrains.compose.resources.StringResource) {
    PLANNED(Res.string.routes_kind_planned), RECORDED(Res.string.routes_kind_recorded), IMPORTED(Res.string.routes_kind_imported);

    /** "Planned route" / "Geplante Route" (strings_routes.xml); the stored form is the enum name. */
    val label: String get() = Txt.s(labelRes)
}

data class Tour(
    val id: Long,
    val kind: TourKind,
    val name: String,
    val createdMs: Long,
    val activity: String?,
    val sac: Int,
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val durationS: Double,
    /** "done" or, for a recording in progress, "active". */
    val state: String,
    /** Planned: via points; imported: GPX waypoints. */
    val waypoints: List<GpxWaypoint>,
    /** Stable id across reinstalls and phones (backup file record, restore dedupe). */
    val uuid: String = "",
    /** Planned: indices of [waypoints] pinned on a POI. */
    val pinned: Set<Int> = emptySet(),
    /** In the recycle bin since (ms); null = in My routes. */
    val deletedMs: Long? = null,
    /** Recorded: bus / car / lift rides found and what the user chose (rides/); null = none, read as recorded. */
    val rides: com.onpath.app.rides.RideRecord? = null,
    /** Planned: kept automatically under "Zuletzt" when it left the map (owner 2026-11-06), not saved by the user. */
    val draft: Boolean = false,
    /** Recorded: a copy of the plan it followed is stored ([TourPlan], `tour_plan`) – Gemacht offers Planen / Los geht's. */
    val hasPlan: Boolean = false,
    /**
     * Planned: the difficulty badge's level when it was saved (`RouteSummary.Difficulty` name: EASY / MODERATE /
     * DIFFICULT – distance + ascent raised by the trail technique, which needs the way details only the plan has); null =
     * not known (saved before schema 7, imported, recorded, restored from a backup). A hike's T level is [sac]. Kept for
     * sorting by difficulty later (owner 2026-11-12: "erst ohne, später dazu").
     */
    val difficulty: String? = null,
)

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
        rides: com.onpath.app.rides.RideRecord? = null,
        /** A plan kept under "Zuletzt" (not saved by the user; no backup, no browser sync). */
        draft: Boolean = false,
        /** [Tour.difficulty]; null = not known. */
        difficulty: String? = null,
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
     * Keeps the newest [max] plans under "Zuletzt" (not in the recycle bin); older ones are removed for good – they were
     * never saved, the user saw them under Zuletzt. Returns how many went.
     */
    fun pruneDrafts(max: Int): Int {
        val old = readableDatabase.rawQuery("SELECT id FROM tours WHERE draft=1 AND deleted_at IS NULL ORDER BY created DESC",
            null).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }.drop(max)
        old.forEach { deleteTour(it) }
        return old.size
    }

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
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val n = db.update("tours", ContentValues().apply {
                put("activity", activity); put("sac", sac); put("distance", distance); put("ascent", ascent)
                put("descent", descent); put("duration", duration); put("waypoints", waypointsToJson(waypoints))
                put("pinned", pinnedToJson(pinned)); put("difficulty", difficulty)
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

    /** The plan tour [tourId] followed; null = none (old tour, "Nur aufzeichnen") or unreadable (reported). */
    fun getTourPlan(tourId: Long): TourPlan? = readableDatabase.rawQuery(
        "SELECT * FROM tour_plan WHERE tour_id=?", arrayOf(tourId.toString())
    ).use { c ->
        if (!c.moveToFirst()) return null
        fun s(n: String) = c.getColumnIndex(n).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getString(it) }
        fun d(n: String) = c.getColumnIndex(n).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getDouble(it) } ?: 0.0
        try {
            TourPlan(name = s("name"), activity = s("activity") ?: "HIKE", sac = d("sac").toInt(),
                waypoints = jsonToWaypoints(s("waypoints")), pinned = jsonToPinned(s("pinned")),
                line = TourPlan.lineFromJson(s("line")), distanceM = d("distance"), ascentM = d("ascent"),
                descentM = d("descent"), durationS = d("duration"), sourceUuid = s("source_uuid"),
                updatedMs = d("updated").toLong()).takeIf { it.usable }
        } catch (e: Exception) { Problems.warn("db", "The followed plan of tour $tourId is unreadable – the tour stays without it", e); null }
    }

    /**
     * The activity was switched during the tour on the route (the service took the proposal): the stored plan copy is
     * for that activity now. Rows changed (0 = the tour followed no plan).
     */
    fun setTourPlanActivity(tourId: Long, activity: String, sac: Int): Int = writableDatabase.update("tour_plan",
        ContentValues().apply { put("activity", activity); put("sac", sac); put("updated", System.currentTimeMillis()) },
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
    fun setRides(tourId: Long, rides: com.onpath.app.rides.RideRecord?, distance: Double, ascent: Double, descent: Double, duration: Double): Boolean =
        writableDatabase.update("tours", ContentValues().apply {
            if (rides == null) putNull("rides") else put("rides", rides.toJson().toString())
            put("distance", distance); put("ascent", ascent); put("descent", descent); put("duration", duration)
        }, "id=?", arrayOf(tourId.toString())) > 0

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

    private fun readTour(c: android.database.Cursor): Tour {
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
            rides = opt(c, "rides")?.let { com.onpath.app.rides.RideRecord.fromJson(c.getString(it)) },
            draft = opt(c, "draft")?.let { c.getInt(it) == 1 } ?: false,
            hasPlan = opt(c, "has_plan")?.let { c.getInt(it) == 1 } ?: false,
            difficulty = opt(c, "difficulty")?.let { c.getString(it) },
        )
    }

    /** Column index when the column exists and is not null. */
    private fun opt(c: android.database.Cursor, n: String): Int? = c.getColumnIndex(n).takeIf { it >= 0 && !c.isNull(it) }

    companion object {
        const val VERSION = TourSchema.VERSION
        @Volatile private var instance: TourDb? = null

        fun newUuid(): String = java.util.UUID.randomUUID().toString()

        fun pinnedToJson(p: Set<Int>): String = JSONArray().apply { p.sorted().forEach { put(it) } }.toString()

        fun jsonToPinned(json: String?): Set<Int> {
            if (json.isNullOrBlank()) return emptySet()
            return try { JSONArray(json).let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } } catch (e: Exception) {
                Problems.warn("db", "Stored pinned stops unreadable, dropped", e); emptySet()
            }
        }
        fun get(context: Context): TourDb =
            instance ?: synchronized(this) { instance ?: TourDb(context).also { instance = it } }

        fun waypointsToJson(list: List<GpxWaypoint>): String = JSONArray().apply {
            list.forEach { w ->
                put(JSONObject().apply {
                    put("lat", w.lat); put("lon", w.lon)
                    w.name?.let { put("name", it) }
                    w.ele?.let { put("ele", it) }
                })
            }
        }.toString()

        fun jsonToWaypoints(json: String?): List<GpxWaypoint> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val a = JSONArray(json)
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    GpxWaypoint(
                        o.getDouble("lat"), o.getDouble("lon"),
                        if (o.has("name")) o.getString("name") else null,
                        if (o.has("ele")) o.getDouble("ele") else null,
                    )
                }
            } catch (e: Exception) { Problems.warn("db", "Stored waypoint list unreadable, waypoints dropped", e); emptyList() }
        }
    }
}

/** The tour database's tables (kept apart so the migration is JVM tested: TourSchemaTest with sqlite-jdbc). */
object TourSchema {
    /**
     * 1, 2 = backup columns; 3 = `points.acc` (GPS accuracy); 4 = `tours.rides` (vehicle detection); 5 = `tours.draft`
     * (Zuletzt); 6 = the table `tour_plan` (the plan a recorded tour followed, [TourPlan]); 7 = `tours.difficulty`
     * ([Tour.difficulty]).
     */
    const val VERSION = 7

    val CREATE = listOf(
        """CREATE TABLE tours(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            kind TEXT NOT NULL, name TEXT NOT NULL, created INTEGER NOT NULL,
            activity TEXT, sac INTEGER NOT NULL DEFAULT 3,
            distance REAL NOT NULL DEFAULT 0, ascent REAL NOT NULL DEFAULT 0,
            descent REAL NOT NULL DEFAULT 0, duration REAL NOT NULL DEFAULT 0,
            state TEXT NOT NULL DEFAULT 'done', waypoints TEXT,
            uuid TEXT, pinned TEXT, deleted_at INTEGER, rides TEXT, draft INTEGER NOT NULL DEFAULT 0, difficulty TEXT)""",
        "CREATE INDEX tours_uuid ON tours(uuid)",
        """CREATE TABLE points(
            tour_id INTEGER NOT NULL, seq INTEGER NOT NULL, seg INTEGER NOT NULL,
            lat REAL NOT NULL, lon REAL NOT NULL, ele REAL, time INTEGER, acc REAL)""",
        "CREATE INDEX points_tour ON points(tour_id, seq)",
        CREATE_PLAN,
    )

    /** 2 → 3: GPS accuracy per recorded point (metres, null = unknown / imported). */
    const val ADD_ACC = "ALTER TABLE points ADD COLUMN acc REAL"

    /** 3 → 4: how to read the rides of a recorded tour (bus / car / lift); every point stays as it is. */
    const val ADD_RIDES = "ALTER TABLE tours ADD COLUMN rides TEXT"

    /** 4 → 5: plans kept under "Zuletzt" (owner 2026-11-06); every existing tour is saved (0). */
    const val ADD_DRAFT = "ALTER TABLE tours ADD COLUMN draft INTEGER NOT NULL DEFAULT 0"

    /** 6 → 7 (owner 2026-11-12): a planned route's difficulty at saving ([Tour.difficulty]); existing tours null. */
    const val ADD_DIFFICULTY = "ALTER TABLE tours ADD COLUMN difficulty TEXT"

    /**
     * 5 → 6 (owner 2026-11-07): the plan a recorded tour followed, one row per tour – a table of its own, because the line
     * can hold thousands of points and the tours list must stay light. A copy, not a reference: deleting or changing the
     * planned route never touches it; it goes with its tour ([PLAN_DELETE]).
     */
    const val CREATE_PLAN = """CREATE TABLE IF NOT EXISTS tour_plan(
            tour_id INTEGER PRIMARY KEY, name TEXT, activity TEXT NOT NULL, sac INTEGER NOT NULL,
            waypoints TEXT NOT NULL, pinned TEXT, line TEXT,
            distance REAL NOT NULL DEFAULT 0, ascent REAL NOT NULL DEFAULT 0, descent REAL NOT NULL DEFAULT 0,
            duration REAL NOT NULL DEFAULT 0, source_uuid TEXT, updated INTEGER NOT NULL)"""

    /** The newest version of a tour's plan replaces the one before. Arguments: [planArgs]. */
    const val PLAN_UPSERT = """INSERT OR REPLACE INTO tour_plan(tour_id, name, activity, sac, waypoints, pinned, line,
            distance, ascent, descent, duration, source_uuid, updated) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"""

    /** A tour removed for good: its plan copy goes too. Argument: the tour id. */
    const val PLAN_DELETE = "DELETE FROM tour_plan WHERE tour_id=?"

    /** `has_plan` (1 / 0) beside a tour row: My routes shows Planen / Los geht's on a recorded tour with a plan copy. */
    const val HAS_PLAN = "EXISTS(SELECT 1 FROM tour_plan p WHERE p.tour_id = tours.id) AS has_plan"

    fun planArgs(tourId: Long, p: TourPlan): Array<Any?> = arrayOf(tourId, p.name, p.activity, p.sac,
        TourDb.waypointsToJson(p.waypoints), TourDb.pinnedToJson(p.pinned), TourPlan.lineToJson(p.line),
        p.distanceM, p.ascentM, p.descentM, p.durationS, p.sourceUuid, p.updatedMs)

    /** Tables to add for an upgrade from [old] (each `IF NOT EXISTS`). */
    fun tablesFrom(old: Int): List<String> = buildList { if (old < 6) add(CREATE_PLAN) }

    class Step(val table: String, val column: String, val sql: String)

    /**
     * Columns to add for an upgrade from [old] (1 → 2 is done before in [TourDb.onUpgrade]), in order; the caller skips
     * a column already there. From 3 both are listed: some version-3 databases have `rides` but no `acc`, and without
     * `acc` every new point would fail to insert.
     */
    fun stepsFrom(old: Int): List<Step> = buildList {
        if (old < 4) add(Step("points", "acc", ADD_ACC))
        if (old < 4) add(Step("tours", "rides", ADD_RIDES))
        if (old < 5) add(Step("tours", "draft", ADD_DRAFT))
        if (old < 7) add(Step("tours", "difficulty", ADD_DIFFICULTY))
    }
}
