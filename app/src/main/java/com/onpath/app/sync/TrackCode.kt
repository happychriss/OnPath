package com.onpath.app.sync

import com.onpath.app.routing.Activity
import com.onpath.app.routing.PlanCode
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** A point of a synced track: degrees, elevation in metres (null = none). */
data class TrackPt(val lat: Double, val lon: Double, val ele: Double? = null)

/** A named point of a synced track (a GPX waypoint). */
data class TrackMark(val lat: Double, val lon: Double, val name: String? = null)

/** An imported GPX track as it is synced (owner 2026-10-29): the line, its activity and its waypoints. */
data class SyncTrack(
    val segments: List<List<TrackPt>>,
    val activity: Activity,
    /** SAC level 1–5 (only used for hiking, always carried). */
    val sac: Int = 2,
    val marks: List<TrackMark> = emptyList(),
)

/**
 * The track code: an imported track in compact bytes for a sync item (field 4 of [SyncFormat], kind
 * [SyncItem.TRACK]). Pure Kotlin, shared with the web planner like [PlanCode]; reading never throws.
 *
 * Format version 1:
 * - version (1), activity (PlanCode's fixed table), SAC (1–5), flags (bit 0 = elevation)
 * - segment count (varint), then per segment its point count (varint)
 * - per point lat and lon in 1e-5° (~1 m) as zigzag varint deltas to the previous point (across segments); with
 *   elevation also the elevation in whole metres as a zigzag delta
 * - optional fields until the end: tag (varint), length (varint), bytes. 1 = waypoint: zigzag lat, zigzag lon
 *   (absolute, 1e-5°), then the name (UTF-8, may be empty). Unknown tags are skipped.
 *
 * A track longer than the item allows is thinned ([fit]: Douglas–Peucker with a growing tolerance) – the other device
 * gets a slightly simpler line, the phone keeps its own.
 */
object TrackCode {
    const val VERSION = 1
    const val MAX_POINTS = 200_000
    const val MAX_SEGMENTS = 2_000
    const val MAX_MARKS = 300
    const val MAX_NAME = 100
    private const val SCALE = 100_000.0
    private const val TAG_MARK = 1
    /** Tolerances (metres) tried one after the other until a long track fits. */
    private val TOLERANCES = listOf(1.0, 2.0, 3.0, 5.0, 8.0, 12.0, 20.0, 30.0, 50.0, 80.0, 120.0, 200.0)

    fun encode(t: SyncTrack): ByteArray {
        val segs = t.segments.filter { it.isNotEmpty() }.take(MAX_SEGMENTS)
        val ele = segs.isNotEmpty() && segs.all { s -> s.all { it.ele != null } }
        val out = Out()
        out.byte(VERSION)
        out.byte(PlanCode.activityCode(t.activity))
        out.byte(t.sac.coerceIn(1, 5))
        out.byte(if (ele) 1 else 0)
        out.varint(segs.size.toLong())
        segs.forEach { out.varint(it.size.toLong()) }
        var lat = 0L; var lon = 0L; var h = 0L
        for (s in segs) for (p in s) {
            val la = (p.lat.coerceIn(-90.0, 90.0) * SCALE).roundToLong()
            val lo = (p.lon.coerceIn(-180.0, 180.0) * SCALE).roundToLong()
            out.zigzag(la - lat); out.zigzag(lo - lon); lat = la; lon = lo
            if (ele) { val e = p.ele!!.coerceIn(-1000.0, 10_000.0).roundToLong(); out.zigzag(e - h); h = e }
        }
        for (m in t.marks.take(MAX_MARKS)) {
            val f = Out()
            f.zigzag((m.lat.coerceIn(-90.0, 90.0) * SCALE).roundToLong())
            f.zigzag((m.lon.coerceIn(-180.0, 180.0) * SCALE).roundToLong())
            clean(m.name)?.let { f.bytes(it.encodeToByteArray()) }
            out.field(TAG_MARK, f.toArray())
        }
        return out.toArray()
    }

    fun decode(bytes: ByteArray): SyncTrack? = try {
        read(In(bytes))
    } catch (_: IndexOutOfBoundsException) {
        null // expected: cut-off bytes; the caller treats it as "no readable track"
    } catch (_: IllegalArgumentException) {
        null // expected: lengths or values out of range in damaged bytes
    }

    /** The track in an item's [SyncItem.track] text, or null. */
    fun of(text: String?): SyncTrack? = text?.let(B64::decode)?.let(::decode)

    /** Activity and SAC level of an item's track text without reading the points (cheap compare), or null. */
    fun header(text: String?): Pair<Activity, Int>? {
        val b = text?.let(B64::decode) ?: return null
        if (b.size < 4 || b[0].toInt() != VERSION) return null
        val a = PlanCode.activityOf(b[1].toInt() and 0xff) ?: return null
        return a to (b[2].toInt() and 0xff).coerceIn(1, 5)
    }

    /**
     * The item text for [t] within [maxBytes]: as it is when it fits, else thinned with a growing tolerance (and at last
     * every n-th point). Waypoints beyond what fits are dropped last. Null when the track has no points.
     */
    fun fit(t: SyncTrack, maxBytes: Int = SyncFormat.MAX_TRACK): String? {
        if (t.segments.none { it.isNotEmpty() }) return null
        encode(t).let { if (it.size <= maxBytes) return B64.encode(it) }
        for (tol in TOLERANCES) {
            val b = encode(t.copy(segments = t.segments.map { simplify(it, tol) }))
            if (b.size <= maxBytes) return B64.encode(b)
        }
        // still too long (a very long track): every n-th point of the most thinned line, then fewer waypoints
        var segs = t.segments.map { simplify(it, TOLERANCES.last()) }
        var marks = t.marks
        repeat(40) {
            val b = encode(SyncTrack(segs, t.activity, t.sac, marks))
            if (b.size <= maxBytes) return B64.encode(b)
            if (marks.size > 20) marks = marks.take(marks.size / 2)
            else segs = segs.map { s -> if (s.size <= 2) s else s.filterIndexed { i, _ -> i % 2 == 0 || i == s.size - 1 } }
        }
        return null
    }

    /** Douglas–Peucker on a local flat projection: keeps first, last and every point further than [tolM] off the line. */
    internal fun simplify(s: List<TrackPt>, tolM: Double): List<TrackPt> {
        if (s.size < 3) return s
        val k = cos(Math.toRadians(s[0].lat))
        val x = DoubleArray(s.size) { s[it].lon * 111_320.0 * k }
        val y = DoubleArray(s.size) { s[it].lat * 110_540.0 }
        val keep = BooleanArray(s.size).also { it[0] = true; it[s.size - 1] = true }
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to s.size - 1) }
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            var far = -1; var max = tolM
            for (i in a + 1 until b) {
                val d = offLine(x[i], y[i], x[a], y[a], x[b], y[b])
                if (d > max) { max = d; far = i }
            }
            if (far >= 0) { keep[far] = true; stack.add(a to far); stack.add(far to b) }
        }
        return s.filterIndexed { i, _ -> keep[i] }
    }

    private fun offLine(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val qx = ax + t * dx - px; val qy = ay + t * dy - py
        return sqrt(qx * qx + qy * qy)
    }

    private fun read(r: In): SyncTrack? {
        if (r.byte() != VERSION) return null
        val activity = PlanCode.activityOf(r.byte()) ?: return null
        val sac = r.byte().takeIf { it in 1..5 } ?: return null
        val ele = r.byte() and 1 != 0
        val n = r.varint()
        require(n in 0..MAX_SEGMENTS.toLong())
        val counts = List(n.toInt()) { r.varint().also { require(it in 0..MAX_POINTS.toLong()) }.toInt() }
        require(counts.sumOf { it.toLong() } <= MAX_POINTS)
        var lat = 0L; var lon = 0L; var h = 0L
        val segs = counts.map { c ->
            List(c) {
                lat += r.zigzag(); lon += r.zigzag()
                if (ele) h += r.zigzag()
                val p = TrackPt(lat / SCALE, lon / SCALE, if (ele) h.toDouble() else null)
                require(p.lat in -90.0..90.0 && p.lon in -180.0..180.0)
                p
            }
        }
        val marks = ArrayList<TrackMark>()
        while (r.more()) {
            val tag = r.varint()
            val len = r.varint()
            require(len in 0..r.left().toLong())
            val f = In(r.take(len.toInt()))
            if (tag == TAG_MARK.toLong() && marks.size < MAX_MARKS) {
                val m = TrackMark(f.zigzag() / SCALE, f.zigzag() / SCALE, clean(f.rest().decodeToString()))
                if (m.lat in -90.0..90.0 && m.lon in -180.0..180.0) marks += m
            } // else a field from a newer app: skipped
        }
        return SyncTrack(segs, activity, sac, marks)
    }

    private fun clean(s: String?): String? = s?.filter { it >= ' ' }?.trim()?.take(MAX_NAME)?.trim()?.takeIf { it.isNotEmpty() }

    private class Out {
        private var buf = ByteArray(256)
        private var size = 0
        fun byte(v: Int) { if (size == buf.size) buf = buf.copyOf(buf.size * 2); buf[size++] = v.toByte() }
        fun bytes(b: ByteArray) = b.forEach { byte(it.toInt()) }
        fun varint(v: Long) { var x = v; while (x >= 0x80 || x < 0) { byte(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; byte(x.toInt()) }
        fun zigzag(v: Long) = varint((v shl 1) xor (v shr 63))
        fun field(tag: Int, data: ByteArray) { varint(tag.toLong()); varint(data.size.toLong()); bytes(data) }
        fun toArray() = buf.copyOf(size)
    }

    private class In(private val b: ByteArray) {
        private var pos = 0
        fun more() = pos < b.size
        fun left() = b.size - pos
        fun byte(): Int = b[pos++].toInt() and 0xff
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val v = byte()
                result = result or ((v and 0x7f).toLong() shl shift)
                if (v and 0x80 == 0) return result
                shift += 7
                require(shift < 63)
            }
        }
        fun zigzag(): Long { val v = varint(); return (v ushr 1) xor -(v and 1) }
        fun take(n: Int): ByteArray { if (n > left()) throw IndexOutOfBoundsException(); return b.copyOfRange(pos, pos + n).also { pos += n } }
        fun rest(): ByteArray = take(left())
    }
}
