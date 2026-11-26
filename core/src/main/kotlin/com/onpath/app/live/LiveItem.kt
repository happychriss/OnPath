package com.onpath.app.live

import com.onpath.app.routing.GeoPoint
import com.onpath.app.sync.Aead
import com.onpath.app.sync.AesGcm
import com.onpath.app.sync.B64
import com.onpath.app.sync.TrackCode
import com.onpath.app.sync.TrackPt
import kotlin.math.roundToLong

/** What the shared tour does right now. */
enum class LiveState(val code: Int) {
    RECORDING(1), PAUSED(2), ENDED(3);

    companion object { fun of(code: Int): LiveState? = entries.firstOrNull { it.code == code } }
}

/** The phone's last GPS fix as the viewer gets it. */
data class LiveFix(val lat: Double, val lon: Double, val eleM: Int?, val timeMs: Long, val accuracyM: Int?)

/**
 * The head of a shared tour (docs/design/live-tracking.md §5): the small status the phone replaces with every upload.
 * Everything the viewer's status line and numbers need; all of it optional on the wire, so an older page reads a newer
 * head and the other way round.
 */
data class LiveHead(
    val state: LiveState,
    val fix: LiveFix? = null,
    val gpsLost: Boolean = false,
    /** The end of the followed plan was reached (the recording may go on). */
    val arrived: Boolean = false,
    /** Metres off the followed route while the app's own off-route alarm is on; null = on the route / nothing followed. */
    val offRouteM: Int? = null,
    val distanceM: Int = 0,
    val ascentM: Int = 0,
    val movingS: Int = 0,
    val startedMs: Long = 0,
    /** Only while following a plan. */
    val toGoM: Int? = null,
    val arrivalMs: Long? = null,
    /**
     * The arrival as planned (owner 2026-11-26: the viewer says „Etwas später als geplant“): the first estimate for the
     * line followed now – a new line (a re-plan) plans anew; null without a route.
     */
    val plannedArrivalMs: Long? = null,
    /** Seconds the pause or the standing still has lasted at this upload; null while moving. */
    val stillS: Int? = null,
    val batteryPct: Int? = null,
    /** The activity's name as the app stores it (e.g. "HIKE"); the page maps it to its word. */
    val activity: String = "",
    /** The sharer's display name; empty = none given. */
    val name: String = "",
    /** Counts the uploads of this tour (a head that goes back is an old one). */
    val counter: Int = 0,
    /** Seconds to the phone's next planned upload – the viewer page paces its questions by it ([LivePolicy.viewerNext]). */
    val nextS: Int = LivePolicy.MOVING_S,
    /**
     * How many track chunks count ("t0000" … below this number); null = not said. A chunk at or above it is a leftover
     * of an upload whose answer got lost and was cut differently the second time – the page ignores it.
     */
    val chunks: Int? = null,
)

/** A recorded point in a track chunk; [seg] counts the track's segments (a new one after a pause or GPS loss). */
data class LivePoint(val seg: Int, val lat: Double, val lon: Double, val eleM: Int?, val timeMs: Long)

/**
 * The live item and blob formats (docs/design/live-tracking.md §5). Pure Kotlin, shared by the app and the viewer page;
 * reading never throws.
 *
 * Every item starts with version (1) and kind (1): 1 head, 2 route, 3 track chunk.
 * - **Head:** then only fields until the end – tag (varint), length (varint), bytes. 1 state, 2 fix (zigzag lat, zigzag
 *   lon in 1e-5°, time ms, flags: bit 0 elevation, bit 1 accuracy, then those as zigzag m / varint m), 3 flags (bit 0
 *   GPS lost, bit 1 arrived), 4 off-route m, 5 distance m, 6 ascent m, 7 moving s, 8 started ms, 9 to go m, 10 arrival
 *   ms, 11 still for s, 12 battery %, 13 activity (UTF-8), 14 name (UTF-8), 15 counter, 16 next upload s, 17 chunks that count,
 *   18 planned arrival ms.
 * - **Route:** flags (bit 0 = elevation), point count, per point lat / lon in 1e-5° as zigzag deltas (with elevation
 *   also whole metres as a zigzag delta); then fields (none yet).
 * - **Chunk:** flags (bit 0 = elevation), point count, per point: segment as a delta (never back), lat / lon as zigzag
 *   deltas, elevation delta when flagged, time in ms as a zigzag delta – the first point against zero, so every chunk
 *   reads on its own; then fields (none yet).
 * Unknown tags are skipped (a newer app); one tag never means two things.
 *
 * Blob (what the server stores, version 1): version (1), nonce (12), AES-256-GCM(item) with the 16-byte tag. The
 * additional data binds the blob to its place: version byte + box id (16 bytes) + the item's name (ASCII) – the server
 * cannot move a blob to another box, swap the head for an old chunk or reorder chunks unnoticed.
 */
object LiveItem {
    const val VERSION = 1
    const val BLOB_VERSION = 1
    const val KIND_HEAD = 1
    const val KIND_ROUTE = 2
    const val KIND_CHUNK = 3

    /** The head's item name. */
    const val HEAD = "h"
    /** The followed route's item name. */
    const val ROUTE = "r"

    const val MAX_NAME = 60
    const val MAX_ACTIVITY = 32
    /** Most points of a route item: a longer line is thinned ([route]). */
    const val MAX_ROUTE_POINTS = 2000
    /**
     * Largest plain chunk or route (bytes). With the blob's 29 bytes around it and Base64url on the wire it stays
     * under the server's 16 KiB an item.
     */
    const val MAX_PLAIN = 12_000
    private const val MAX_READ_POINTS = 50_000
    private const val SCALE = 100_000.0
    private val TOLERANCES = listOf(2.0, 5.0, 10.0, 20.0, 40.0, 80.0, 150.0, 300.0)

    /** The item name of track chunk number [n] (0, 1, 2 …): "t0000", "t0001" … */
    fun chunkName(n: Int): String { require(n >= 0); return "t" + n.toString().padStart(4, '0') }

    /** The number of a chunk's item name, or null when [name] is no chunk. */
    fun chunkNumber(name: String): Int? =
        name.takeIf { it.length in 5..10 && it[0] == 't' && it.drop(1).all { c -> c in '0'..'9' } }?.drop(1)?.toIntOrNull()

    /** A name an item may have: the head, the route or a chunk. */
    fun isName(name: String) = name == HEAD || name == ROUTE || chunkNumber(name) != null

    // ---- head ----

    fun head(h: LiveHead): ByteArray {
        val out = LiveOut()
        out.byte(VERSION); out.byte(KIND_HEAD)
        out.number(1, h.state.code.toLong())
        h.fix?.let { f ->
            val o = LiveOut()
            o.zigzag(deg(f.lat, 90.0)); o.zigzag(deg(f.lon, 180.0)); o.varint(f.timeMs.coerceAtLeast(0))
            o.byte((if (f.eleM != null) 1 else 0) or (if (f.accuracyM != null) 2 else 0))
            f.eleM?.let { o.zigzag(it.coerceIn(-1000, 10_000).toLong()) }
            f.accuracyM?.let { o.varint(it.coerceIn(0, 100_000).toLong()) }
            out.field(2, o.toArray())
        }
        val flags = (if (h.gpsLost) 1 else 0) or (if (h.arrived) 2 else 0)
        if (flags != 0) out.number(3, flags.toLong())
        h.offRouteM?.let { out.number(4, it.toLong()) }
        out.number(5, h.distanceM.toLong()); out.number(6, h.ascentM.toLong()); out.number(7, h.movingS.toLong())
        out.number(8, h.startedMs)
        h.toGoM?.let { out.number(9, it.toLong()) }
        h.arrivalMs?.let { out.number(10, it) }
        h.stillS?.let { out.number(11, it.toLong()) }
        h.batteryPct?.let { out.number(12, it.coerceIn(0, 100).toLong()) }
        clean(h.activity, MAX_ACTIVITY)?.let { out.field(13, it.encodeToByteArray()) }
        clean(h.name, MAX_NAME)?.let { out.field(14, it.encodeToByteArray()) }
        out.number(15, h.counter.toLong())
        out.number(16, h.nextS.toLong())
        h.chunks?.let { out.number(17, it.toLong()) }
        h.plannedArrivalMs?.let { out.number(18, it) }
        return out.toArray()
    }

    fun readHead(bytes: ByteArray): LiveHead? = guarded {
        val r = LiveIn(bytes)
        if (r.byte() != VERSION || r.byte() != KIND_HEAD) return@guarded null
        var h = LiveHead(LiveState.RECORDING)
        var state: LiveState? = null
        while (r.more()) {
            val (tag, raw) = r.field()
            fun num() = LiveIn(raw).varint().also { require(it >= 0) }
            fun int() = num().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            when (tag) {
                1L -> state = LiveState.of(int())
                2L -> {
                    val f = LiveIn(raw)
                    val lat = f.zigzag() / SCALE; val lon = f.zigzag() / SCALE; val t = f.varint()
                    val fl = f.byte()
                    val ele = if (fl and 1 != 0) f.zigzag().toInt() else null
                    val acc = if (fl and 2 != 0) f.varint().coerceAtMost(100_000).toInt() else null
                    require(lat in -90.0..90.0 && lon in -180.0..180.0 && t >= 0)
                    h = h.copy(fix = LiveFix(lat, lon, ele, t, acc))
                }
                3L -> num().let { h = h.copy(gpsLost = it and 1 != 0L, arrived = it and 2 != 0L) }
                4L -> h = h.copy(offRouteM = int())
                5L -> h = h.copy(distanceM = int())
                6L -> h = h.copy(ascentM = int())
                7L -> h = h.copy(movingS = int())
                8L -> h = h.copy(startedMs = num())
                9L -> h = h.copy(toGoM = int())
                10L -> h = h.copy(arrivalMs = num())
                11L -> h = h.copy(stillS = int())
                12L -> h = h.copy(batteryPct = int().coerceAtMost(100))
                13L -> h = h.copy(activity = clean(raw.decodeToString(), MAX_ACTIVITY).orEmpty())
                14L -> h = h.copy(name = clean(raw.decodeToString(), MAX_NAME).orEmpty())
                15L -> h = h.copy(counter = int())
                16L -> h = h.copy(nextS = int())
                17L -> h = h.copy(chunks = int())
                18L -> h = h.copy(plannedArrivalMs = num())
                else -> Unit // a field from a newer app: skip it
            }
        }
        state?.let { h.copy(state = it) } // a state this reader does not know: no readable head
    }

    // ---- route ----

    /** The followed [line] as a route item; a long line is thinned until it has at most [MAX_ROUTE_POINTS] points and fits. */
    fun route(line: List<GeoPoint>): ByteArray {
        var pts = line.map { TrackPt(it.lat, it.lon, it.ele) }
        for (tol in TOLERANCES) {
            if (pts.size <= MAX_ROUTE_POINTS) break
            pts = TrackCode.simplify(pts, tol)
        }
        while (pts.size > MAX_ROUTE_POINTS) pts = pts.filterIndexed { i, _ -> i % 2 == 0 || i == pts.size - 1 }
        var b = routeBytes(pts)
        while (b.size > MAX_PLAIN && pts.size > 2) {
            pts = pts.filterIndexed { i, _ -> i % 2 == 0 || i == pts.size - 1 }
            b = routeBytes(pts)
        }
        return b
    }

    private fun routeBytes(pts: List<TrackPt>): ByteArray {
        val ele = pts.isNotEmpty() && pts.all { it.ele != null }
        val out = LiveOut()
        out.byte(VERSION); out.byte(KIND_ROUTE); out.byte(if (ele) 1 else 0)
        out.varint(pts.size.toLong())
        var lat = 0L; var lon = 0L; var h = 0L
        for (p in pts) {
            val la = deg(p.lat, 90.0); val lo = deg(p.lon, 180.0)
            out.zigzag(la - lat); out.zigzag(lo - lon); lat = la; lon = lo
            if (ele) { val e = p.ele!!.coerceIn(-1000.0, 10_000.0).roundToLong(); out.zigzag(e - h); h = e }
        }
        return out.toArray()
    }

    fun readRoute(bytes: ByteArray): List<GeoPoint>? = guarded {
        val r = LiveIn(bytes)
        if (r.byte() != VERSION || r.byte() != KIND_ROUTE) return@guarded null
        val ele = r.byte() and 1 != 0
        val n = r.varint()
        require(n in 0..minOf(MAX_READ_POINTS, r.left()).toLong())
        var lat = 0L; var lon = 0L; var h = 0L
        val out = ArrayList<GeoPoint>(n.toInt())
        repeat(n.toInt()) {
            lat += r.zigzag(); lon += r.zigzag()
            if (ele) h += r.zigzag()
            require(lat in -9_000_000L..9_000_000L && lon in -18_000_000L..18_000_000L)
            out.add(GeoPoint(lat / SCALE, lon / SCALE, if (ele) h.toDouble() else null))
        }
        skipFields(r)
        out
    }

    // ---- track chunk ----

    /** The [points] as one chunk item (the caller cuts them to size with [LiveChunker]). Elevation only when every point has one. */
    fun chunk(points: List<LivePoint>): ByteArray {
        val ele = points.isNotEmpty() && points.all { it.eleM != null }
        val out = LiveOut()
        out.byte(VERSION); out.byte(KIND_CHUNK); out.byte(if (ele) 1 else 0)
        out.varint(points.size.toLong())
        val w = PointWriter(out, ele)
        points.forEach(w::add)
        return out.toArray()
    }

    /** Writes points in the chunk's delta coding – also used by [LiveChunker] to measure as it goes. */
    internal class PointWriter(private val out: LiveOut, private val ele: Boolean) {
        private var seg = 0L; private var lat = 0L; private var lon = 0L; private var h = 0L; private var t = 0L
        fun add(p: LivePoint) {
            val s = maxOf(seg, p.seg.toLong())
            out.varint(s - seg); seg = s
            val la = deg(p.lat, 90.0); val lo = deg(p.lon, 180.0)
            out.zigzag(la - lat); out.zigzag(lo - lon); lat = la; lon = lo
            if (ele) { val e = p.eleM!!.coerceIn(-1000, 10_000).toLong(); out.zigzag(e - h); h = e }
            val time = p.timeMs.coerceAtLeast(0)
            out.zigzag(time - t); t = time
        }
    }

    fun readChunk(bytes: ByteArray): List<LivePoint>? = guarded {
        val r = LiveIn(bytes)
        if (r.byte() != VERSION || r.byte() != KIND_CHUNK) return@guarded null
        val ele = r.byte() and 1 != 0
        val n = r.varint()
        require(n in 0..minOf(MAX_READ_POINTS, r.left()).toLong())
        var seg = 0L; var lat = 0L; var lon = 0L; var h = 0L; var t = 0L
        val out = ArrayList<LivePoint>(n.toInt())
        repeat(n.toInt()) {
            seg += r.varint()
            lat += r.zigzag(); lon += r.zigzag()
            if (ele) h += r.zigzag()
            t += r.zigzag()
            require(seg in 0..Int.MAX_VALUE.toLong() && t >= 0)
            require(lat in -9_000_000L..9_000_000L && lon in -18_000_000L..18_000_000L)
            out.add(LivePoint(seg.toInt(), lat / SCALE, lon / SCALE, if (ele) h.toInt() else null, t))
        }
        skipFields(r)
        out
    }

    // ---- blob ----

    /** The blob for the item [name] ([HEAD], [ROUTE], [chunkName]) in the box of [keys]; [nonce] = 12 fresh random bytes (never reuse one). */
    fun seal(keys: LiveKeys, name: String, plain: ByteArray, nonce: ByteArray, aead: Aead = AesGcm): ByteArray {
        require(nonce.size == AesGcm.NONCE)
        require(isName(name)) { "bad live item name" }
        return byteArrayOf(BLOB_VERSION.toByte()) + nonce + aead.seal(keys.content, nonce, aad(keys, name), plain)
    }

    /** The plain item in a [blob] stored as [name], or null (wrong key, damaged, moved to another name or box, newer format). */
    fun open(keys: LiveKeys, name: String, blob: ByteArray, aead: Aead = AesGcm): ByteArray? {
        if (blob.size < 1 + AesGcm.NONCE + AesGcm.TAG || blob[0].toInt() != BLOB_VERSION || !isName(name)) return null
        return aead.open(keys.content, blob.copyOfRange(1, 1 + AesGcm.NONCE), aad(keys, name), blob.copyOfRange(1 + AesGcm.NONCE, blob.size))
    }

    /** Base64url for blobs on the wire (the server's JSON and its D1 rows). */
    fun toText(b: ByteArray) = B64.encode(b)
    fun fromText(s: String): ByteArray? = B64.decode(s)

    private fun aad(keys: LiveKeys, name: String) = byteArrayOf(BLOB_VERSION.toByte()) + keys.boxBytes + name.encodeToByteArray()

    private fun deg(v: Double, max: Double): Long = (v.coerceIn(-max, max) * SCALE).roundToLong()

    private fun skipFields(r: LiveIn) { while (r.more()) r.field() }

    private fun clean(s: String?, max: Int): String? =
        s?.filter { it >= ' ' }?.trim()?.take(max)?.trim()?.takeIf { it.isNotEmpty() }

    private inline fun <T> guarded(read: () -> T?): T? = try {
        read()
    } catch (_: IndexOutOfBoundsException) {
        null // expected: cut-off bytes; the caller treats it as "not readable"
    } catch (_: IllegalArgumentException) {
        null // expected: lengths or numbers out of range in damaged bytes
    }
}
