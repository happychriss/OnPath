package com.onpath.app.backup

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.GpxData
import com.onpath.app.gpx.GpxParser
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.GpxWriter
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.rides.RideRecord
import com.onpath.app.rides.RideView
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Everything needed to put a tour or saved plan back into the database exactly as it was (owner 2026-10-21, tour &
 * plan backup). Written into the backup GPX as `<metadata><extensions><onpath:backup>` – other apps ignore it and
 * still read the track. [uuid] identifies the entry across reinstalls and phones (dedupe on restore).
 */
data class BackupMeta(
    val uuid: String,
    val kind: TourKind,
    val name: String,
    val createdMs: Long,
    val activity: String?,
    val sac: Int,
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val durationS: Double,
    /** Stops of a plan / waypoints of an imported GPX, with their names (null = no name). */
    val waypoints: List<GpxWaypoint>,
    /** Indices of [waypoints] that are pinned POIs (routed exactly). */
    val pinned: Set<Int> = emptySet(),
    /** Time of the first / last track point (recorded tours), null when unknown. */
    val startMs: Long? = null,
    val endMs: Long? = null,
    /** In the recycle bin since (file then lies in `Deleted/`); null = normal. */
    val deletedMs: Long? = null,
    /** When this backup was written (newest wins when the same uuid is found twice). */
    val savedMs: Long = 0L,
    /** Recorded tour: rides found and the choice (rides/RideRecord); the file's track follows the choice. */
    val rides: RideRecord? = null,
) {
    /** "tour" (recorded), "plan" (saved planned route) or "imported" – readable in the file. */
    val type: String get() = typeOf(kind)

    companion object {
        fun typeOf(k: TourKind) = when (k) { TourKind.RECORDED -> "tour"; TourKind.PLANNED -> "plan"; TourKind.IMPORTED -> "imported" }
        fun kindOf(type: String): TourKind? = when (type) { "tour" -> TourKind.RECORDED; "plan" -> TourKind.PLANNED; "imported" -> TourKind.IMPORTED; else -> null }

        fun of(t: Tour, segments: List<List<TrackPoint>>, savedMs: Long = System.currentTimeMillis()): BackupMeta {
            val times = segments.flatten().mapNotNull { it.timeMs }
            return BackupMeta(t.uuid, t.kind, t.name, t.createdMs, t.activity, t.sac, t.distanceM, t.ascentM, t.descentM,
                t.durationS, t.waypoints, t.pinned, times.minOrNull(), times.maxOrNull(), t.deletedMs, savedMs, t.rides)
        }
    }
}

/** A backup file read back: its record and its track. */
data class BackupEntry(val meta: BackupMeta, val segments: List<List<TrackPoint>>)

/** GPX 1.1 with the OnPath backup record – and back. */
object BackupCodec {
    const val NS = "https://github.com/happychriss/OnPath/gpx-backup/1"
    const val VERSION = 1
    private val recordRe = Regex("<onpath:backup\\b[^>]*>(.*?)</onpath:backup\\s*>", RegexOption.DOT_MATCHES_ALL)

    /**
     * [segments] = the points as stored. With rides ([BackupMeta.rides]) the track follows the user's choice
     * ([RideView.layout]): a ride not counted is its own segment marked `<onpath:ride kind="…"/>`, and points dropped
     * by "Save only until" / "Split" are not in the track but in `<onpath:dropped>` beside the record – other apps show
     * the tour as chosen, a restore gets every recorded point back.
     */
    fun encode(meta: BackupMeta, segments: List<List<TrackPoint>>): String {
        val json = toJson(meta).toString()
        val layout = RideView.layout(segments, meta.rides)
        val dropped = if (layout.dropped.isEmpty()) "" else buildString {
            append("<onpath:dropped>")
            for (seg in layout.dropped) {
                append("<onpath:seg>")
                for (p in seg) {
                    append("<onpath:p lat=\"").append(p.lat).append("\" lon=\"").append(p.lon).append('"')
                    p.ele?.let { append(" ele=\"").append(it).append('"') }
                    p.timeMs?.let { append(" t=\"").append(it).append('"') }
                    append("/>")
                }
                append("</onpath:seg>")
            }
            append("</onpath:dropped>")
        }
        return GpxWriter.write(
            meta.name, layout.trk.map { it.first }, meta.waypoints, timeMs = meta.savedMs.takeIf { it > 0 } ?: meta.createdMs,
            extensions = "<onpath:backup version=\"$VERSION\">${GpxWriter.esc(json)}</onpath:backup>$dropped",
            extensionNs = "xmlns:onpath=\"$NS\"",
            segmentExtensions = layout.trk.map { (_, kind) -> kind?.let { "<onpath:ride kind=\"${it.name}\"/>" } }
                .takeIf { l -> l.any { it != null } },
        )
    }

    private val droppedRe = Regex("<onpath:dropped>(.*?)</onpath:dropped>", RegexOption.DOT_MATCHES_ALL)
    private val droppedSegRe = Regex("<onpath:seg>(.*?)</onpath:seg>", RegexOption.DOT_MATCHES_ALL)
    private val droppedPtRe = Regex("<onpath:p\\b([^>]*?)/>")
    private fun attr(attrs: String, name: String) = Regex("\\b$name=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)

    /** The dropped points of a backup file (empty when none). */
    fun droppedSegments(text: String): List<List<TrackPoint>> {
        val block = droppedRe.find(text)?.groupValues?.get(1) ?: return emptyList()
        return droppedSegRe.findAll(block).map { s ->
            droppedPtRe.findAll(s.groupValues[1]).mapNotNull { m ->
                val a = m.groupValues[1]
                val lat = attr(a, "lat")?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = attr(a, "lon")?.toDoubleOrNull() ?: return@mapNotNull null
                TrackPoint(lat, lon, attr(a, "ele")?.toDoubleOrNull(), attr(a, "t")?.toLongOrNull())
            }.toList()
        }.filter { it.isNotEmpty() }.toList()
    }

    /** True when [text] carries an OnPath backup record (other GPX files in the folder are left alone). */
    fun hasRecord(text: String) = recordRe.containsMatchIn(text)

    /** Reads a backup file; null when it is no OnPath backup or the record is unreadable (reported). */
    fun decode(text: String, fileName: String = "?"): BackupEntry? {
        val m = recordRe.find(text) ?: return null
        val meta = try { fromJson(JSONObject(unescape(m.groupValues[1]))) } catch (e: Exception) {
            Problems.warn("backup", "Backup file $fileName: OnPath record unreadable – skipped", e); return null
        } ?: return null
        val gpx: GpxData? = try { GpxParser.parse(text) } catch (e: Exception) {
            Problems.warn("backup", "Backup file $fileName: no track readable – restored without line", e); null
        }
        // the dropped tail follows the track: together every recorded point, in order
        return BackupEntry(meta, (gpx?.allSegments ?: emptyList()) + droppedSegments(text))
    }

    fun toJson(m: BackupMeta): JSONObject = JSONObject().apply {
        put("uuid", m.uuid); put("type", m.type); put("name", m.name); put("created", m.createdMs)
        m.activity?.let { put("activity", it) }
        put("sac", m.sac); put("distance", m.distanceM); put("ascent", m.ascentM); put("descent", m.descentM)
        put("duration", m.durationS)
        put("waypoints", JSONArray().apply {
            m.waypoints.forEach { w ->
                put(JSONObject().apply {
                    put("lat", w.lat); put("lon", w.lon); w.name?.let { put("name", it) }; w.ele?.let { put("ele", it) }
                })
            }
        })
        put("pinned", JSONArray().apply { m.pinned.sorted().forEach { put(it) } })
        m.startMs?.let { put("start", it) }
        m.endMs?.let { put("end", it) }
        m.deletedMs?.let { put("deleted", it) }
        put("saved", m.savedMs)
        m.rides?.let { put("rides", it.toJson()) }
    }

    fun fromJson(o: JSONObject): BackupMeta? {
        val uuid = o.optString("uuid", "").takeIf { it.isNotBlank() } ?: return null
        val kind = BackupMeta.kindOf(o.optString("type", "")) ?: run {
            Problems.warn("backup", "Backup record with unknown type '${o.optString("type")}' – skipped"); return null
        }
        val wa = o.optJSONArray("waypoints") ?: JSONArray()
        val wpts = (0 until wa.length()).map { i ->
            val w = wa.getJSONObject(i)
            GpxWaypoint(w.getDouble("lat"), w.getDouble("lon"), if (w.has("name")) w.getString("name") else null,
                if (w.has("ele")) w.getDouble("ele") else null)
        }
        val pa = o.optJSONArray("pinned") ?: JSONArray()
        return BackupMeta(
            uuid = uuid, kind = kind, name = o.optString("name", "").ifBlank { "Tour" }, createdMs = o.optLong("created", 0L),
            activity = if (o.has("activity")) o.getString("activity") else null, sac = o.optInt("sac", 3),
            distanceM = o.optDouble("distance", 0.0), ascentM = o.optDouble("ascent", 0.0),
            descentM = o.optDouble("descent", 0.0), durationS = o.optDouble("duration", 0.0),
            waypoints = wpts, pinned = (0 until pa.length()).map { pa.getInt(it) }.toSet(),
            startMs = if (o.has("start")) o.getLong("start") else null, endMs = if (o.has("end")) o.getLong("end") else null,
            deletedMs = if (o.has("deleted")) o.getLong("deleted") else null, savedMs = o.optLong("saved", 0L),
            rides = o.optJSONObject("rides")?.let { r ->
                try { RideRecord.fromJson(r) } catch (e: Exception) {
                    Problems.warn("backup", "Backup record: rides unreadable – the tour is restored as recorded", e); null
                }
            },
        )
    }

    private fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}

/** Readable, safe file names: `2026-10-21 Algund → Meran.gpx`. */
object BackupNames {
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    /** Characters no file system on a phone, PC or cloud drive accepts in a name. */
    private val bad = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    const val MAX_NAME = 80

    fun sanitize(name: String): String =
        name.replace(Regex("\\s+"), " ").replace(bad, "_").trim().trim('.', ' ').take(MAX_NAME).trim().ifBlank { "Tour" }

    fun base(createdMs: Long, name: String, zone: ZoneId = ZoneId.systemDefault()): String =
        dateFmt.format(Instant.ofEpochMilli(createdMs).atZone(zone)) + " " + sanitize(name)

    /** `base.gpx`, or `base (2).gpx`, `base (3).gpx` … when another entry already uses the name (any case). */
    fun unique(base: String, taken: Collection<String>): String {
        val used = taken.map { it.lowercase() }.toSet()
        if ("$base.gpx".lowercase() !in used) return "$base.gpx"
        var n = 2
        while ("$base ($n).gpx".lowercase() in used) n++
        return "$base ($n).gpx"
    }

    fun fileName(meta: BackupMeta, taken: Collection<String>, zone: ZoneId = ZoneId.systemDefault()) =
        unique(base(meta.createdMs, meta.name, zone), taken)
}
