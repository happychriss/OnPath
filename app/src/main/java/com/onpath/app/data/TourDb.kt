package com.onpath.app.data

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import org.json.JSONArray
import org.json.JSONObject

enum class TourKind(val label: String) { PLANNED("Planned route"), RECORDED("Recorded track"), IMPORTED("Imported GPX") }

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
)

/**
 * All user data in one SQLite database with write-ahead logging.
 * Recording writes every accepted point immediately (R-07).
 */
class TourDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "onpath.db", null, 1, KeepCorruptFile()) {

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
        db.execSQL(
            """CREATE TABLE tours(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL, name TEXT NOT NULL, created INTEGER NOT NULL,
                activity TEXT, sac INTEGER NOT NULL DEFAULT 3,
                distance REAL NOT NULL DEFAULT 0, ascent REAL NOT NULL DEFAULT 0,
                descent REAL NOT NULL DEFAULT 0, duration REAL NOT NULL DEFAULT 0,
                state TEXT NOT NULL DEFAULT 'done', waypoints TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE points(
                tour_id INTEGER NOT NULL, seq INTEGER NOT NULL, seg INTEGER NOT NULL,
                lat REAL NOT NULL, lon REAL NOT NULL, ele REAL, time INTEGER)"""
        )
        db.execSQL("CREATE INDEX points_tour ON points(tour_id, seq)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---- tours ----

    fun insertTour(
        kind: TourKind, name: String, activity: String?, sac: Int,
        distance: Double, ascent: Double, descent: Double, duration: Double,
        segments: List<List<TrackPoint>>, waypoints: List<GpxWaypoint> = emptyList(),
        state: String = "done",
    ): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("tours", null, ContentValues().apply {
                put("kind", kind.name); put("name", name); put("created", System.currentTimeMillis())
                put("activity", activity); put("sac", sac); put("distance", distance); put("ascent", ascent)
                put("descent", descent); put("duration", duration); put("state", state)
                put("waypoints", waypointsToJson(waypoints))
            })
            var seq = 0
            segments.forEachIndexed { segIdx, seg -> seg.forEach { insertPoint(db, id, seq++, segIdx, it) } }
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    fun listTours(): List<Tour> = readableDatabase.rawQuery(
        "SELECT * FROM tours ORDER BY created DESC", null
    ).use { c -> buildList { while (c.moveToNext()) add(readTour(c)) } }

    fun getTour(id: Long): Tour? = readableDatabase.rawQuery(
        "SELECT * FROM tours WHERE id=?", arrayOf(id.toString())
    ).use { c -> if (c.moveToFirst()) readTour(c) else null }

    fun findActiveRecording(): Tour? = readableDatabase.rawQuery(
        "SELECT * FROM tours WHERE kind=? AND state='active' ORDER BY created DESC LIMIT 1",
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
        segments: List<List<TrackPoint>>, waypoints: List<GpxWaypoint>,
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val n = db.update("tours", ContentValues().apply {
                put("activity", activity); put("sac", sac); put("distance", distance); put("ascent", ascent)
                put("descent", descent); put("duration", duration); put("waypoints", waypointsToJson(waypoints))
            }, "id=? AND kind=?", arrayOf(id.toString(), TourKind.PLANNED.name))
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

    // ---- recording ----

    fun appendPoint(tourId: Long, seq: Int, seg: Int, p: TrackPoint) = insertPoint(writableDatabase, tourId, seq, seg, p)

    /** The activity switched during a recording (Navigate → activity check). */
    fun updateActivity(tourId: Long, activity: String, sac: Int) {
        writableDatabase.update("tours", ContentValues().apply { put("activity", activity); put("sac", sac) },
            "id=?", arrayOf(tourId.toString()))
    }

    fun updateStats(tourId: Long, distance: Double, ascent: Double, descent: Double, duration: Double, state: String) {
        writableDatabase.update("tours", ContentValues().apply {
            put("distance", distance); put("ascent", ascent); put("descent", descent)
            put("duration", duration); put("state", state)
        }, "id=?", arrayOf(tourId.toString()))
    }

    /** Returns (next seq, last seg) so a recording can continue after a restart. */
    fun recordingCursor(tourId: Long): Pair<Int, Int> = readableDatabase.rawQuery(
        "SELECT MAX(seq), MAX(seg) FROM points WHERE tour_id=?", arrayOf(tourId.toString())
    ).use { c ->
        if (c.moveToFirst() && !c.isNull(0)) (c.getInt(0) + 1) to c.getInt(1) else 0 to 0
    }

    /** Throws on failure (disk full, corrupt DB): a silent `insert()` returning -1 would lose the track unnoticed. */
    private fun insertPoint(db: SQLiteDatabase, id: Long, seq: Int, seg: Int, p: TrackPoint) {
        db.insertOrThrow("points", null, ContentValues().apply {
            put("tour_id", id); put("seq", seq); put("seg", seg); put("lat", p.lat); put("lon", p.lon)
            if (p.ele != null) put("ele", p.ele) else putNull("ele")
            if (p.timeMs != null) put("time", p.timeMs) else putNull("time")
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
        )
    }

    companion object {
        @Volatile private var instance: TourDb? = null
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
