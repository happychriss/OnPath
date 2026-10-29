package com.onpath.app.routing

import kotlin.math.roundToLong

/**
 * A plan as it travels between devices: in a QR code, in a shared link, later in the phone ⇄ browser sync
 * (docs/design/web-planner.md §8). The same code runs in the app and – compiled to
 * WebAssembly – in the web planner, so both sides always read and write exactly the same format.
 */
data class SharedPlan(
    /** The plan's points as the user set them (not the route line). A round trip ends where it started. */
    val points: List<GeoPoint>,
    val activity: Activity,
    /** SAC level 1–5 (only used for hiking, always carried). */
    val sac: Int = 2,
    /** Indices of points pinned to a POI or place (the route passes them exactly, Planner splits there). */
    val pinned: Set<Int> = emptySet(),
    /** Known names of points (POI, search result), by index. */
    val names: Map<Int, String> = emptyMap(),
    /** Name of the route, when it has one. */
    val title: String? = null,
)

/**
 * The plan code: compact, URL safe text for a [SharedPlan] (owner).
 * Pure Kotlin, no Android (SharedCoreRuleTest), no platform Base64 (it also runs in the browser).
 *
 * Link: `<web planner>/#p=<code>` – the plan sits after "#", so it never reaches a server.
 * The app accepts the whole link, `p=<code>` or the bare code ([parse]).
 *
 * Format version 1 (bytes, then Base64url without padding):
 * - version (1), activity (fixed table [ACTIVITIES], never the enum order), SAC (1–5)
 * - point count n (varint), then per point lat and lon in 1e-5° (~1 m) as zigzag varint deltas to the previous point
 * - pinned points: ceil(n / 8) bytes, bit i = point i
 * - optional fields until the end: tag (varint), length (varint), bytes. 1 = title (UTF-8),
 *   2 = point name (varint index + UTF-8). Unknown tags are skipped, so newer codes still open in older apps.
 *
 * Reading never throws: anything broken, too large or of a newer major version is null.
 */
object PlanCode {
    const val VERSION = 1
    const val MAX_POINTS = 500
    const val MAX_NAME = 100
    private const val MAX_TEXT = 16_000
    private const val SCALE = 100_000.0

    /** Stored activity codes. Append only; never reorder. */
    private val ACTIVITIES = listOf(
        Activity.BIKE_TOURING, Activity.BIKE_TREKKING, Activity.EBIKE, Activity.MTB, Activity.EMTB, Activity.HIKE,
    )

    private const val TAG_TITLE = 1
    private const val TAG_NAME = 2

    /** The code for [plan] (without "p="). Points beyond [MAX_POINTS] and over-long names are cut. */
    fun encode(plan: SharedPlan): String {
        val pts = plan.points.take(MAX_POINTS)
        val out = Bytes()
        out.byte(VERSION)
        out.byte(ACTIVITIES.indexOf(plan.activity).coerceAtLeast(0))
        out.byte(plan.sac.coerceIn(1, 5))
        out.varint(pts.size.toLong())
        var lat = 0L
        var lon = 0L
        for (p in pts) {
            val la = (p.lat.coerceIn(-90.0, 90.0) * SCALE).roundToLong()
            val lo = (p.lon.coerceIn(-180.0, 180.0) * SCALE).roundToLong()
            out.zigzag(la - lat); out.zigzag(lo - lon)
            lat = la; lon = lo
        }
        val bits = ByteArray((pts.size + 7) / 8)
        for (i in plan.pinned) if (i in pts.indices) bits[i / 8] = (bits[i / 8].toInt() or (1 shl (i % 8))).toByte()
        bits.forEach { out.byte(it.toInt()) }
        plan.title?.let(::clean)?.let { out.field(TAG_TITLE, it.encodeToByteArray()) }
        for ((i, name) in plan.names.toSortedMap()) {
            val n = clean(name) ?: continue
            if (i !in pts.indices) continue
            val b = Bytes().apply { varint(i.toLong()) }
            out.field(TAG_NAME, b.toArray() + n.encodeToByteArray())
        }
        return Base64Url.encode(out.toArray())
    }

    /** The link to open [plan] in the web planner at [base] (e.g. "https://…/"). */
    fun link(base: String, plan: SharedPlan): String = base.substringBefore('#') + "#p=" + encode(plan)

    /**
     * The plan in [text]: a link with `#p=` (or `?p=` / `&p=`), `p=<code>` or the bare code; surrounding blanks are
     * ignored. Null when there is no readable plan in it.
     */
    fun parse(text: String): SharedPlan? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_TEXT) return null
        val marker = Regex("""(?:^|[#?&])p=([A-Za-z0-9_-]+)""").find(t)
        val code = marker?.groupValues?.get(1) ?: t.takeIf { it.all(Base64Url::isCodeChar) } ?: return null
        return decode(code)
    }

    /** The plan for a bare [code], or null. */
    fun decode(code: String): SharedPlan? {
        val bytes = Base64Url.decode(code) ?: return null
        return try {
            read(Reader(bytes))
        } catch (_: IndexOutOfBoundsException) {
            null // expected: cut-off or damaged code (typed by hand, half a QR code) – the caller says "not a plan"
        } catch (_: IllegalArgumentException) {
            null // expected: values out of range in a damaged code
        }
    }

    private fun read(r: Reader): SharedPlan? {
        if (r.byte() != VERSION) return null
        val activity = ACTIVITIES.getOrNull(r.byte()) ?: return null
        val sac = r.byte().takeIf { it in 1..5 } ?: return null
        val n = r.varint()
        if (n < 0 || n > MAX_POINTS) return null
        var lat = 0L
        var lon = 0L
        val points = ArrayList<GeoPoint>(n.toInt())
        repeat(n.toInt()) {
            lat += r.zigzag(); lon += r.zigzag()
            val p = GeoPoint(lat / SCALE, lon / SCALE)
            require(p.lat in -90.0..90.0 && p.lon in -180.0..180.0)
            points += p
        }
        val pinned = HashSet<Int>()
        for (b in 0 until (points.size + 7) / 8) {
            val v = r.byte()
            for (bit in 0 until 8) if (v and (1 shl bit) != 0 && b * 8 + bit < points.size) pinned += b * 8 + bit
        }
        var title: String? = null
        val names = HashMap<Int, String>()
        while (r.more()) {
            val tag = r.varint()
            val len = r.varint()
            require(len in 0..r.left().toLong())
            val f = Reader(r.take(len.toInt()))
            when (tag.toInt()) {
                TAG_TITLE -> title = clean(f.rest().decodeToString())
                TAG_NAME -> {
                    val i = f.varint()
                    val name = clean(f.rest().decodeToString())
                    if (i >= 0 && i < points.size && name != null) names[i.toInt()] = name
                }
                else -> Unit // a field from a newer app: skip it
            }
        }
        return SharedPlan(points, activity, sac, pinned, names, title)
    }

    private fun clean(s: String): String? =
        s.filter { it >= ' ' }.trim().take(MAX_NAME).trim().takeIf { it.isNotEmpty() }

    private class Bytes {
        private var buf = ByteArray(64)
        private var size = 0
        fun byte(v: Int) {
            if (size == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[size++] = v.toByte()
        }
        fun varint(v: Long) {
            var x = v
            while (x >= 0x80) { byte(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
            byte(x.toInt())
        }
        fun zigzag(v: Long) = varint((v shl 1) xor (v shr 63))
        fun field(tag: Int, data: ByteArray) {
            varint(tag.toLong()); varint(data.size.toLong()); data.forEach { byte(it.toInt()) }
        }
        fun toArray() = buf.copyOf(size)
    }

    private class Reader(private val b: ByteArray) {
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

    /** Base64url without padding (RFC 4648 §5), small and platform free. */
    private object Base64Url {
        private const val ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        fun isCodeChar(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'

        fun encode(b: ByteArray): String = buildString {
            var i = 0
            while (i < b.size) {
                val n = minOf(3, b.size - i)
                var v = 0
                for (k in 0 until 3) v = (v shl 8) or (if (k < n) b[i + k].toInt() and 0xff else 0)
                for (k in 0..n) append(ABC[(v shr (18 - 6 * k)) and 0x3f])
                i += 3
            }
        }

        fun decode(s: String): ByteArray? {
            if (s.length % 4 == 1) return null
            val out = ByteArray(s.length * 3 / 4)
            var o = 0
            var v = 0
            var bits = 0
            for (c in s) {
                val d = ABC.indexOf(c)
                if (d < 0) return null
                v = (v shl 6) or d; bits += 6
                if (bits >= 8) { bits -= 8; out[o++] = (v shr bits).toByte(); v = v and ((1 shl bits) - 1) }
            }
            return out.copyOf(o)
        }
    }
}
