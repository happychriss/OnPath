package com.onpath.app.routing

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/**
 * BRouter routing data by 1° part (docs/design/map-data-size.md §6). A `.rd5` file covers 5° × 5° as 25 separate
 * 1° × 1° parts: the first 200 bytes are 25 big-endian longs, the low 48 bits = END offset of part i
 * (i = lon offset × 5 + lat offset; part 0 starts at 200; start = end of the previous part), the high 16 bits a version
 * (kept as read). Offsets inside a part are relative, so a part can be moved. A part of length 0 = "no data" (BRouter
 * finds no way there). After part 24 a tail (113 bytes today): creation time (long), index checksum (int, = crc ^ 2 for
 * the current format – this also tells BRouter the 1/32° grid, **so the tail is required**: without it BRouter reads
 * the parts with the old 1/80° grid and crashes), 25 part checksums (int), elevation type (byte).
 *
 * So the phone fetches the index + tail and only the parts it needs (HTTP range) and writes its own rd5 with the other
 * parts empty. Verified on the JVM (tools/routing/jvm-test/PartialTest.kt): routes inside present parts are identical
 * to the full file; a point in an empty part fails like "no way nearby" (BRouterEngine checks parts first, see
 * [hasData]). Pure (no Android) – JVM-tested.
 */
object Rd5Parts {
    const val INDEX_BYTES = 200
    const val PARTS = 25
    private const val OFFSET_MASK = 0xffffffffffffL

    /** Margin around a single point (first start, tapped point): a route starting there stays near it. */
    const val POINT_MARGIN_M = 2_000.0

    /** Corridor around a route or a straight leg (owner: "~10 km"). */
    const val CORRIDOR_MARGIN_M = 10_000.0

    /** One 1° × 1° part: [lon] / [lat] = its south-west corner in whole degrees. */
    data class Part(val lon: Int, val lat: Int) : Comparable<Part> {
        /** The rd5 file it lies in, e.g. "E10_N45.rd5". */
        val tile: String get() = tileName(lat + 0.5, lon + 0.5)

        /** Index 0..24 inside [tile]. */
        val index: Int get() = Math.floorMod(lon, 5) * 5 + Math.floorMod(lat, 5)

        /** Stable text form, e.g. "E10_N45.rd5:E11_N46" (what the loader queues and logs). */
        val key: String get() = "$tile:${ew(lon)}_${ns(lat)}"

        /** For people: "11°E 46°N". */
        val label: String get() = "${abs(lon)}°${if (lon < 0) "W" else "E"} ${abs(lat)}°${if (lat < 0) "S" else "N"}"

        override fun compareTo(other: Part): Int = compareValuesBy(this, other, { it.lon }, { it.lat })

        override fun toString(): String = key

        companion object {
            /** Part of [tile] at [index] (0..24). */
            fun of(tile: String, index: Int): Part {
                val (lon0, lat0) = tileOrigin(tile)
                return Part(lon0 + index / 5, lat0 + index % 5)
            }

            /** Inverse of [key]; null when it is not a part key (e.g. a whole tile name). */
            fun parse(key: String): Part? {
                val m = Regex("""^([EW]\d+_[NS]\d+\.rd5):([EW])(\d+)_([NS])(\d+)$""").matchEntire(key) ?: return null
                val lon = m.groupValues[3].toInt() * if (m.groupValues[2] == "W") -1 else 1
                val lat = m.groupValues[5].toInt() * if (m.groupValues[4] == "S") -1 else 1
                return Part(lon, lat).takeIf { it.tile == m.groupValues[1] }
            }
        }
    }

    private fun ew(lon: Int) = if (lon < 0) "W${-lon}" else "E$lon"
    private fun ns(lat: Int) = if (lat < 0) "S${-lat}" else "N$lat"

    /** 5° tile name as brouter.de names it: Bozen (46.5, 11.35) → "E10_N45.rd5". */
    fun tileName(lat: Double, lon: Double): String =
        "${ew((floor(lon / 5.0) * 5).toInt())}_${ns((floor(lat / 5.0) * 5).toInt())}.rd5"

    /** South-west corner (lon, lat) of a tile name. */
    fun tileOrigin(tile: String): Pair<Int, Int> {
        val m = Regex("""^([EW])(\d+)_([NS])(\d+)\.rd5$""").matchEntire(tile) ?: throw IllegalArgumentException("not a tile name: $tile")
        return (m.groupValues[2].toInt() * if (m.groupValues[1] == "W") -1 else 1) to
            (m.groupValues[4].toInt() * if (m.groupValues[3] == "S") -1 else 1)
    }

    private fun valid(lat: Double, lon: Double) = !lat.isNaN() && !lon.isNaN() && abs(lat) <= 89.0 && abs(lon) <= 180.0

    fun partOf(lat: Double, lon: Double): Part = Part(floor(lon).toInt().coerceIn(-180, 179), floor(lat).toInt())

    /** Parts under a box (degrees). */
    fun forBox(south: Double, west: Double, north: Double, east: Double): Set<Part> {
        if (!valid(south, west) || !valid(north, east)) return emptySet()
        // a box ending exactly on a degree line does not reach into the next part
        fun range(a: Double, b: Double): IntRange {
            val lo = floor(minOf(a, b)).toInt()
            return lo..maxOf(lo, floor(max(a, b) - 1e-9).toInt())
        }
        val out = sortedSetOf<Part>()
        for (lo in range(west, east)) for (la in range(south, north)) out += Part(lo.coerceIn(-180, 179), la)
        return out
    }

    /** Parts within [marginM] of a point. */
    fun forPoint(p: GeoPoint, marginM: Double = POINT_MARGIN_M): Set<Part> {
        if (!valid(p.lat, p.lon)) return emptySet()
        val dLat = marginM / 111_320.0
        val dLon = marginM / (111_320.0 * cos(Math.toRadians(p.lat)).coerceAtLeast(0.01))
        val out = sortedSetOf(partOf(p.lat, p.lon))
        out += forBox(p.lat - dLat, p.lon - dLon, p.lat + dLat, p.lon + dLon)
        return out
    }

    /**
     * Parts within [marginM] of a line (route, or the straight legs between planned points): every point plus samples
     * every ~1 km along each leg.
     */
    fun forCorridor(points: List<GeoPoint>, marginM: Double = CORRIDOR_MARGIN_M): Set<Part> {
        val out = sortedSetOf<Part>()
        val good = points.filter { valid(it.lat, it.lon) }
        good.forEachIndexed { i, p ->
            out += forPoint(p, marginM)
            val q = good.getOrNull(i + 1) ?: return@forEachIndexed
            val steps = ceil(max(abs(q.lat - p.lat), abs(q.lon - p.lon)) / 0.01).toInt()
            for (k in 1 until steps) {
                val f = k.toDouble() / steps
                out += forPoint(GeoPoint(p.lat + (q.lat - p.lat) * f, p.lon + (q.lon - p.lon) * f), marginM)
            }
        }
        return out
    }

    // ---- file format ----------------------------------------------------------------------------------------------

    /** The 200-byte index of an rd5. */
    class Index(raw: ByteArray) {
        val raw: ByteArray = raw.copyOf(INDEX_BYTES)
        private val longs = LongArray(PARTS).also { a ->
            require(raw.size >= INDEX_BYTES) { "rd5 index needs $INDEX_BYTES bytes, got ${raw.size}" }
            val b = ByteBuffer.wrap(raw, 0, INDEX_BYTES)
            for (i in 0 until PARTS) a[i] = b.long
        }

        fun end(i: Int): Long = longs[i] and OFFSET_MASK
        fun start(i: Int): Long = if (i == 0) INDEX_BYTES.toLong() else end(i - 1)
        fun size(i: Int): Long = end(i) - start(i)

        /** End of the last part = where the tail starts. */
        val dataEnd: Long get() = end(PARTS - 1)

        /** Parts that hold data. */
        val withData: Set<Int> get() = (0 until PARTS).filter { size(it) > 0 }.toSet()

        /** Same layout rules as BRouter: offsets never go back, part 0 starts after the index. */
        fun check(fileLength: Long? = null) {
            for (i in 0 until PARTS) if (end(i) < start(i)) throw java.io.IOException("rd5 index broken at part $i")
            if (fileLength != null && fileLength < dataEnd) throw java.io.IOException("rd5 shorter ($fileLength) than its index ($dataEnd)")
        }

        /** High 16 bits of entry [i] (version), kept as read. */
        fun high(i: Int): Long = longs[i] and OFFSET_MASK.inv()
    }

    /** BRouter's checksum (btools.util.Crc32 = CRC-32 without the final inversion), as a signed int. */
    fun crc(b: ByteArray, off: Int = 0, len: Int = b.size): Int {
        val c = CRC32(); c.update(b, off, len)
        return c.value.toInt().inv()
    }

    /** Index for a file that holds the parts in [present] (sizes from the remote [src]), others empty. */
    fun buildIndex(src: Index, present: Set<Int>): ByteArray {
        val b = ByteBuffer.allocate(INDEX_BYTES)
        var pos = INDEX_BYTES.toLong()
        for (i in 0 until PARTS) {
            if (i in present) pos += src.size(i)
            b.putLong(src.high(i) or pos)
        }
        return b.array()
    }

    /**
     * Tail for [newIndex] from the remote [srcTail] (its index checksum checked against [src]): the new index checksum
     * in the same format (plain or ^2), everything else as is. An empty tail stays empty (old files).
     */
    fun buildTail(src: Index, srcTail: ByteArray, newIndex: ByteArray): ByteArray {
        if (srcTail.isEmpty()) return srcTail
        require(srcTail.size >= 12) { "rd5 tail too short (${srcTail.size} bytes)" }
        val old = ByteBuffer.wrap(srcTail, 8, 4).int
        val srcCrc = crc(src.raw)
        val xor = when (old) {
            srcCrc -> 0
            srcCrc xor 2 -> 2
            else -> throw java.io.IOException("rd5 tail does not match its index (mixed versions?)")
        }
        val out = srcTail.copyOf()
        ByteBuffer.wrap(out, 8, 4).putInt(crc(newIndex) xor xor)
        return out
    }

    /** Creation time in the tail (BRouter's version stamp of the file), null without a tail. */
    fun creationTime(tail: ByteArray): Long? = if (tail.size >= 8) ByteBuffer.wrap(tail, 0, 8).long else null

    /** Where the data of one part comes from when a file is assembled. */
    fun interface PartSource {
        /** Writes exactly the part's bytes to [out]. */
        fun copyTo(out: FileOutputStream)
    }

    /** Part [i] read from an rd5 file with layout [index] (the local file, same remote version). */
    fun fromFile(file: File, index: Index, i: Int) = PartSource { out -> copyRange(file, index.start(i), index.size(i), out) }

    /** A whole file holding exactly one part's bytes (a downloaded part). */
    fun fromPartFile(file: File) = PartSource { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }

    private fun copyRange(file: File, start: Long, len: Long, out: FileOutputStream) {
        RandomAccessFile(file, "r").use { ra ->
            ra.seek(start)
            val buf = ByteArray(256 * 1024)
            var left = len
            while (left > 0) {
                val n = ra.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw java.io.IOException("${file.name} ended early")
                out.write(buf, 0, n); left -= n
            }
        }
    }

    /**
     * Writes an rd5 at [dest] holding [parts] (index → source) with the remote layout [src] / [srcTail]; atomic: a temp
     * file next to it, synced, sizes checked, then renamed over [dest] – never a broken file in place (BRouter may have
     * the old one open; it keeps reading the old inode).
     */
    fun write(dest: File, src: Index, srcTail: ByteArray, parts: Map<Int, PartSource>) {
        val present = parts.keys.filter { src.size(it) > 0 }.toSortedSet()
        val index = buildIndex(src, present)
        val tail = buildTail(src, srcTail, index)
        val tmp = File(dest.parentFile, ".${dest.name}.tmp")
        tmp.delete()
        try {
            FileOutputStream(tmp).use { out ->
                out.write(index)
                for (i in present) {
                    val before = out.channel.position()
                    parts.getValue(i).copyTo(out)
                    val got = out.channel.position() - before
                    if (got != src.size(i)) throw java.io.IOException("part $i of ${dest.name}: $got bytes instead of ${src.size(i)}")
                }
                out.write(tail)
                out.fd.sync()
            }
            val written = Index(index)
            if (tmp.length() != written.dataEnd + tail.size) throw java.io.IOException("${dest.name}: size check failed")
            if (!tmp.renameTo(dest)) throw java.io.IOException("could not install ${dest.name}")
        } finally {
            tmp.delete() // no-op after the rename
        }
    }

    /** Index of a local rd5, null when the file is missing or too short. */
    fun readIndex(file: File): Index? {
        if (!file.isFile || file.length() < INDEX_BYTES) return null
        val b = ByteArray(INDEX_BYTES)
        RandomAccessFile(file, "r").use { it.readFully(b) }
        return Index(b)
    }

    /** Tail of a local rd5 (bytes after the last part). */
    fun readTail(file: File, index: Index): ByteArray {
        val len = (file.length() - index.dataEnd).coerceIn(0, 4096).toInt()
        val b = ByteArray(len)
        RandomAccessFile(file, "r").use { it.seek(index.dataEnd); it.readFully(b) }
        return b
    }

    /**
     * The local file in [segmentsDir] holds data for the part at ([lat], [lon]): true / false; null when there is no
     * readable file at all. An empty part is either not downloaded or has no ways (sea) – both mean: no routing here.
     */
    fun hasData(segmentsDir: File, lat: Double, lon: Double): Boolean? = try {
        val part = partOf(lat, lon)
        readIndex(File(segmentsDir, part.tile))?.let { it.size(part.index) > 0 }
    } catch (e: java.io.IOException) {
        null // expected: unreadable file – BRouter's own error says more
    }
}
