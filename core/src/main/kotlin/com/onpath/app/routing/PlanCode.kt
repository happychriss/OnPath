package com.onpath.app.routing

import com.onpath.app.data.Geo
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
    /**
     * Multi-day tour (docs/design/multi-day-tours.md §2, owner 2026-11-17): the number of days N; null = a normal plan.
     * The grey proposals are worked out each time ([PlanDays]), never stored.
     */
    val days: Int? = null,
    /** Limits per day of a multi-day tour (they propose N and flag a day above them); [DayLimits.NONE] = none. */
    val limits: DayLimits = DayLimits.NONE,
    /**
     * Night marks by point index – the third per-point attribute beside [pinned] and [names] ([PlanEdits.follow]):
     * "I end the day here" (Übernachtung, blue) or a hotel (green; the point sits at the hotel). Never on the start or
     * the last point.
     */
    val nights: Map<Int, NightMark> = emptyMap(),
    /** The tour's description (multi-day tours), free text with line breaks. */
    val description: String? = null,
    /**
     * Hidden via points by point index (docs/design/detour-stitch.md): the fourth per-point attribute. They travel in
     * tag 7 together with the points' exact 1e-6° values; an older reader skips both.
     */
    val anchors: Map<Int, Anchors> = emptyMap(),
)

/** Limits per day of a multi-day tour (owner 2026-11-17: "Natürlich bleiben die Grenzen pro Tag"); null = no limit. */
data class DayLimits(val maxTimeMin: Int? = null, val maxAscentM: Int? = null) {
    val isNone: Boolean get() = maxTimeMin == null && maxAscentM == null

    companion object {
        val NONE = DayLimits()
    }
}

/**
 * A night mark on a plan point (owner 2026-11-17, round 3 variant A): [hotel] null = Übernachtung (blue moon, the point
 * stays on the route), else Hotel (green bed: the point sits at the hotel, the route leads there).
 */
data class NightMark(val hotel: Hotel? = null) {
    val isHotel: Boolean get() = hotel != null

    companion object {
        val OVERNIGHT = NightMark()
    }
}

/** A booked hotel: its name and an address and / or a link (Google Maps, Booking) – the phone shows them. */
data class Hotel(val name: String? = null, val address: String? = null, val link: String? = null)

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
 * - multi-day tours (owner 2026-11-17, still version 1 – an older app reads a multi-day plan as a normal long plan, its
 *   hotels as named points): 3 = number of days (varint), 4 = limits per day (varint minutes, varint metres of ascent;
 *   0 = none), 5 = night mark (varint index, varint kind 1 = Übernachtung / 2 = hotel, then the hotel's own fields as
 *   tag, length, UTF-8: 1 = name, 2 = address, 3 = link; unknown ones skipped, an unknown kind reads as Übernachtung),
 *   6 = description (UTF-8, line breaks kept).
 * - detour anchors (docs/design/detour-stitch.md §3, §8.10; written only when the plan has one, an older app skips it):
 *   7 = first, for every point, its remainder to 1e-6° (lat, lon: zigzag varints, each −5…+5), so the points come back
 *   exactly; then per point with anchors: varint index, varint count before, varint count after, each anchor as zigzag
 *   deltas in 1e-6° (the first to the point's exact value, then to the previous anchor of that entry, before list
 *   first). The reader drops the whole tag when it is damaged (remainder out of range, cut off, over 500 anchors) and
 *   a single entry when its index is out of range, it has over 8 anchors on a side or one lies over 200 km from its point.
 *
 * Reading never throws: anything broken, too large or of a newer major version is null.
 */
object PlanCode {
    const val VERSION = 1
    const val MAX_POINTS = 500
    const val MAX_NAME = 100
    /** Most days of a multi-day tour. */
    const val MAX_DAYS = 99
    const val MAX_ADDRESS = 200
    /** A Booking link with its query is long; the rest of a link is cut. */
    const val MAX_LINK = 500
    const val MAX_DESCRIPTION = 2_000
    /** Limits above these are read as none (minutes: 24 h; metres). */
    private const val MAX_LIMIT_MIN = 24 * 60
    private const val MAX_LIMIT_ASCENT = 20_000
    private const val MAX_TEXT = 16_000
    private const val SCALE = 100_000.0
    private const val SCALE6 = 1_000_000.0
    /** Anchors: at most this many per side, per plan, and this far (m) from their point. */
    const val MAX_ANCHORS_SIDE = 8
    const val MAX_ANCHORS = 500
    private const val MAX_ANCHOR_DIST_M = 200_000.0

    /** Stored activity codes. Append only; never reorder. */
    private val ACTIVITIES = listOf(
        Activity.BIKE_TOURING, Activity.BIKE_TREKKING, Activity.EBIKE, Activity.MTB, Activity.EMTB, Activity.HIKE,
    )

    private const val TAG_TITLE = 1
    private const val TAG_NAME = 2
    private const val TAG_DAYS = 3
    private const val TAG_LIMITS = 4
    private const val TAG_NIGHT = 5
    private const val TAG_DESCRIPTION = 6
    /** The highest tag this reader knows; tests read as an older app with a lower one ([decode]). */
    private const val TAG_ANCHORS = 7
    private const val TAG_LAST = TAG_ANCHORS

    private const val NIGHT_OVERNIGHT = 1
    private const val NIGHT_HOTEL = 2
    private const val HOTEL_NAME = 1
    private const val HOTEL_ADDRESS = 2
    private const val HOTEL_LINK = 3

    /** The stored code of [a] – the same fixed table for the plan sync's track code (sync/TrackCode). */
    fun activityCode(a: Activity): Int = ACTIVITIES.indexOf(a).coerceAtLeast(0)

    /** The activity of a stored [code], or null for a code from a newer app. */
    fun activityOf(code: Int): Activity? = ACTIVITIES.getOrNull(code)

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
        plan.days?.let { out.field(TAG_DAYS, Bytes().apply { varint(it.coerceIn(1, MAX_DAYS).toLong()) }.toArray()) }
        if (!plan.limits.isNone) out.field(TAG_LIMITS, Bytes().apply {
            varint(limit(plan.limits.maxTimeMin, MAX_LIMIT_MIN).toLong()); varint(limit(plan.limits.maxAscentM, MAX_LIMIT_ASCENT).toLong())
        }.toArray())
        for ((i, mark) in plan.nights.toSortedMap()) {
            if (i <= 0 || i >= pts.lastIndex) continue // never on the start or the last point
            val b = Bytes().apply { varint(i.toLong()) }
            val h = mark.hotel
            if (h == null) b.varint(NIGHT_OVERNIGHT.toLong())
            else {
                b.varint(NIGHT_HOTEL.toLong())
                clean(h.name)?.let { b.field(HOTEL_NAME, it.encodeToByteArray()) }
                clean(h.address, MAX_ADDRESS)?.let { b.field(HOTEL_ADDRESS, it.encodeToByteArray()) }
                clean(h.link, MAX_LINK)?.let { b.field(HOTEL_LINK, it.encodeToByteArray()) }
            }
            out.field(TAG_NIGHT, b.toArray())
        }
        cleanText(plan.description)?.let { out.field(TAG_DESCRIPTION, it.encodeToByteArray()) }
        anchorsField(pts, plan.anchors)?.let { out.field(TAG_ANCHORS, it) }
        return Base64Url.encode(out.toArray())
    }

    /** Tag 7's payload, or null when there is nothing to write (no anchor survives the caps). */
    private fun anchorsField(pts: List<GeoPoint>, anchors: Map<Int, Anchors>): ByteArray? {
        val entries = ArrayList<Pair<Int, Anchors>>()
        var total = 0
        for ((i, a) in anchors.toSortedMap()) {
            if (i !in pts.indices || a.isEmpty || a.before.size > MAX_ANCHORS_SIDE || a.after.size > MAX_ANCHORS_SIDE) continue
            if (total + a.size > MAX_ANCHORS) break
            total += a.size; entries += i to a
        }
        if (entries.isEmpty()) return null
        val b = Bytes()
        for (p in pts) {
            val rl = micro(p.lat, 90.0) - (p.lat.coerceIn(-90.0, 90.0) * SCALE).roundToLong() * 10
            val ro = micro(p.lon, 180.0) - (p.lon.coerceIn(-180.0, 180.0) * SCALE).roundToLong() * 10
            if (rl !in -5L..5L || ro !in -5L..5L) return null
            b.zigzag(rl); b.zigzag(ro)
        }
        for ((i, a) in entries) {
            b.varint(i.toLong()); b.varint(a.before.size.toLong()); b.varint(a.after.size.toLong())
            var lat = micro(pts[i].lat, 90.0)
            var lon = micro(pts[i].lon, 180.0)
            for (q in a.before + a.after) {
                val la = micro(q.lat, 90.0); val lo = micro(q.lon, 180.0)
                b.zigzag(la - lat); b.zigzag(lo - lon)
                lat = la; lon = lo
            }
        }
        return b.toArray()
    }

    private fun micro(v: Double, max: Double): Long = (v.coerceIn(-max, max) * SCALE6).roundToLong()

    /**
     * Reads tag 7 over the points' 1e-5° values [la5] / [lo5]: the exact points and the anchors, or null when the whole
     * tag is to be ignored (damaged). A single bad entry is only dropped.
     */
    private fun readAnchors(f: Reader, la5: LongArray, lo5: LongArray): Pair<List<GeoPoint>, Map<Int, Anchors>>? {
        return try {
            val n = la5.size
            val la = LongArray(n); val lo = LongArray(n)
            for (i in 0 until n) {
                val rl = f.zigzag(); val ro = f.zigzag()
                if (rl !in -5L..5L || ro !in -5L..5L) return null
                la[i] = la5[i] * 10 + rl; lo[i] = lo5[i] * 10 + ro
            }
            val pts = List(n) { GeoPoint(la[it] / SCALE6, lo[it] / SCALE6) }
            val out = HashMap<Int, Anchors>()
            var total = 0L
            while (f.more()) {
                val i = f.varint(); val nb = f.varint(); val na = f.varint()
                if (nb < 0 || na < 0 || nb > f.left() || na > f.left() || (nb + na) * 2 > f.left()) return null // each anchor needs two bytes at least
                total += nb + na
                if (total > MAX_ANCHORS) return null
                var lat = if (i in 0 until n.toLong()) la[i.toInt()] else 0L
                var lon = if (i in 0 until n.toLong()) lo[i.toInt()] else 0L
                val list = ArrayList<GeoPoint>((nb + na).toInt())
                repeat((nb + na).toInt()) {
                    lat += f.zigzag(); lon += f.zigzag()
                    list += GeoPoint(lat / SCALE6, lon / SCALE6)
                }
                if (i !in 0 until n.toLong() || nb > MAX_ANCHORS_SIDE || na > MAX_ANCHORS_SIDE) continue
                val p = pts[i.toInt()]
                if (list.any { it.lat !in -90.0..90.0 || it.lon !in -180.0..180.0 || Geo.distance(p, it) > MAX_ANCHOR_DIST_M }) continue
                out[i.toInt()] = Anchors(list.take(nb.toInt()), list.drop(nb.toInt()))
            }
            pts to out
        } catch (_: IndexOutOfBoundsException) {
            null // expected: cut-off or damaged tag – the plan stays, without exact points and anchors
        } catch (_: IllegalArgumentException) {
            null // expected: damaged tag, same
        }
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

    /**
     * The plan for a bare [code], or null. [knownTags] = the highest optional field this reader knows (tests: 2 reads as
     * an app before multi-day tours did).
     */
    fun decode(code: String, knownTags: Int = TAG_LAST): SharedPlan? {
        val bytes = Base64Url.decode(code) ?: return null
        return try {
            read(Reader(bytes), knownTags)
        } catch (_: IndexOutOfBoundsException) {
            null // expected: cut-off or damaged code (typed by hand, half a QR code) – the caller says "not a plan"
        } catch (_: IllegalArgumentException) {
            null // expected: values out of range in a damaged code
        }
    }

    private fun read(r: Reader, knownTags: Int): SharedPlan? {
        if (r.byte() != VERSION) return null
        val activity = ACTIVITIES.getOrNull(r.byte()) ?: return null
        // T0 from a bike plan (field bug 2026-10-31): the level means nothing there – read it as the default T2
        val sac = r.byte().let { if (it == 0 && activity != Activity.HIKE) 2 else it }.takeIf { it in 1..5 } ?: return null
        val n = r.varint()
        if (n < 0 || n > MAX_POINTS) return null
        var lat = 0L
        var lon = 0L
        val points = ArrayList<GeoPoint>(n.toInt())
        val la5 = LongArray(n.toInt())
        val lo5 = LongArray(n.toInt())
        repeat(n.toInt()) {
            lat += r.zigzag(); lon += r.zigzag()
            la5[points.size] = lat; lo5[points.size] = lon
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
        var days: Int? = null
        var limits = DayLimits.NONE
        val nights = HashMap<Int, NightMark>()
        var description: String? = null
        var anchorBytes: ByteArray? = null
        while (r.more()) {
            val tag = r.varint()
            val len = r.varint()
            require(len in 0..r.left().toLong())
            val f = Reader(r.take(len.toInt()))
            when (if (tag > knownTags) -1 else tag.toInt()) {
                TAG_TITLE -> title = clean(f.rest().decodeToString())
                TAG_NAME -> {
                    val i = f.varint()
                    val name = clean(f.rest().decodeToString())
                    if (i >= 0 && i < points.size && name != null) names[i.toInt()] = name
                }
                TAG_DAYS -> days = f.varint().takeIf { it in 1..MAX_DAYS }?.toInt()
                TAG_LIMITS -> limits = DayLimits(limitOf(f.varint(), MAX_LIMIT_MIN), if (f.more()) limitOf(f.varint(), MAX_LIMIT_ASCENT) else null)
                TAG_NIGHT -> {
                    val i = f.varint()
                    val kind = if (f.more()) f.varint() else NIGHT_OVERNIGHT.toLong()
                    val mark = if (kind == NIGHT_HOTEL.toLong()) NightMark(readHotel(f)) else NightMark.OVERNIGHT
                    if (i > 0 && i < points.lastIndex) nights[i.toInt()] = mark
                }
                TAG_DESCRIPTION -> description = cleanText(f.rest().decodeToString())
                TAG_ANCHORS -> anchorBytes = f.rest()
                else -> Unit // a field from a newer app: skip it
            }
        }
        var exact: List<GeoPoint> = points
        var anchors: Map<Int, Anchors> = emptyMap()
        anchorBytes?.let { bytes ->
            readAnchors(Reader(bytes), la5, lo5)?.let { (p, a) -> exact = p; anchors = a }
        }
        return SharedPlan(exact, activity, sac, pinned, names, title, days, limits, nights, description, anchors)
    }

    private fun readHotel(f: Reader): Hotel {
        var name: String? = null; var address: String? = null; var link: String? = null
        while (f.more()) {
            val tag = f.varint()
            val len = f.varint()
            require(len in 0..f.left().toLong())
            val text = f.take(len.toInt()).decodeToString()
            when (tag.toInt()) {
                HOTEL_NAME -> name = clean(text)
                HOTEL_ADDRESS -> address = clean(text, MAX_ADDRESS)
                HOTEL_LINK -> link = clean(text, MAX_LINK)
                else -> Unit // a hotel field from a newer app: skip it
            }
        }
        return Hotel(name, address, link)
    }

    private fun limit(v: Int?, max: Int): Int = v?.takeIf { it in 1..max } ?: 0
    private fun limitOf(v: Long, max: Int): Int? = v.takeIf { it in 1..max.toLong() }?.toInt()

    private fun clean(s: String?, max: Int = MAX_NAME): String? =
        s?.filter { it >= ' ' }?.trim()?.take(max)?.trim()?.takeIf { it.isNotEmpty() }

    /** [clean] for a text with paragraphs: line breaks stay (at most two in a row), other control characters go. */
    private fun cleanText(s: String?): String? =
        s?.replace("\r\n", "\n")?.filter { it == '\n' || it >= ' ' }?.replace(Regex("\n{3,}"), "\n\n")?.trim()
            ?.take(MAX_DESCRIPTION)?.trim()?.takeIf { it.isNotEmpty() }

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
