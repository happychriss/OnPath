package com.onpath.app.cells

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * PMTiles v3 (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md): a reader that lists every tile and a
 * writer that merges tiles of several archives into one new archive (the local cell store, [CellStore]).
 * Pure Kotlin, no dependencies, JVM-testable. Directories are gzip-compressed, tile data is copied byte for byte.
 */
object PmTiles {

    const val HEADER_SIZE = 127
    /** The spec keeps header + root directory within the first 16 KiB (readers fetch that in one request). */
    const val ROOT_LIMIT = 16_384 - HEADER_SIZE

    const val COMPRESSION_NONE = 1
    const val COMPRESSION_GZIP = 2
    const val TYPE_MVT = 1

    class Header(
        val rootOffset: Long, val rootLength: Long,
        val metadataOffset: Long, val metadataLength: Long,
        val leafOffset: Long, val leafLength: Long,
        val dataOffset: Long, val dataLength: Long,
        val addressedTiles: Long, val tileEntries: Long, val tileContents: Long,
        val clustered: Boolean,
        val internalCompression: Int, val tileCompression: Int, val tileType: Int,
        val minZoom: Int, val maxZoom: Int,
        /** e7 degrees: minLon, minLat, maxLon, maxLat */
        val minLonE7: Int, val minLatE7: Int, val maxLonE7: Int, val maxLatE7: Int,
        val centerZoom: Int, val centerLonE7: Int, val centerLatE7: Int,
    ) {
        fun bytes(): ByteArray {
            val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            b.put("PMTiles".toByteArray(Charsets.US_ASCII)); b.put(3)
            for (v in longArrayOf(rootOffset, rootLength, metadataOffset, metadataLength, leafOffset, leafLength,
                dataOffset, dataLength, addressedTiles, tileEntries, tileContents)) b.putLong(v)
            b.put(if (clustered) 1 else 0); b.put(internalCompression.toByte()); b.put(tileCompression.toByte())
            b.put(tileType.toByte()); b.put(minZoom.toByte()); b.put(maxZoom.toByte())
            b.putInt(minLonE7); b.putInt(minLatE7); b.putInt(maxLonE7); b.putInt(maxLatE7)
            b.put(centerZoom.toByte()); b.putInt(centerLonE7); b.putInt(centerLatE7)
            return b.array()
        }

        companion object {
            fun parse(h: ByteArray): Header {
                if (h.size < HEADER_SIZE || String(h, 0, 7, Charsets.US_ASCII) != "PMTiles") throw IOException("not a PMTiles file")
                if (h[7].toInt() != 3) throw IOException("PMTiles version ${h[7]} not supported (3 expected)")
                val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
                b.position(8)
                val l = LongArray(11) { b.long }
                val u = { b.get().toInt() and 0xff }
                return Header(l[0], l[1], l[2], l[3], l[4], l[5], l[6], l[7], l[8], l[9], l[10],
                    u() == 1, u(), u(), u(), u(), u(), b.int, b.int, b.int, b.int, u(), b.int, b.int)
            }
        }
    }

    /** One directory entry; [runLength] 0 = pointer to a leaf directory. */
    data class Entry(val tileId: Long, val offset: Long, val length: Long, val runLength: Long)

    /** Opened archive. Not thread-safe; close it. */
    class Reader(val file: File) : Closeable {
        private val raf = RandomAccessFile(file, "r")
        val header: Header

        init {
            try {
                val h = ByteArray(HEADER_SIZE)
                raf.readFully(h)
                header = Header.parse(h)
                val len = raf.length()
                if (header.dataOffset + header.dataLength > len || header.rootOffset + header.rootLength > len)
                    throw IOException("${file.name} is truncated")
            } catch (e: Exception) {
                raf.close(); throw e
            }
        }

        fun read(offset: Long, length: Long): ByteArray {
            if (length < 0 || length > Int.MAX_VALUE) throw IOException("bad length $length")
            val b = ByteArray(length.toInt()); raf.seek(offset); raf.readFully(b); return b
        }

        /** Metadata JSON (decompressed), "{}" when empty. */
        fun metadata(): String = if (header.metadataLength == 0L) "{}"
            else String(decompress(read(header.metadataOffset, header.metadataLength), header.internalCompression), Charsets.UTF_8)

        /** Every tile entry (leaf directories resolved), in tile-id order. Offsets are relative to the data section. */
        fun entries(): List<Entry> {
            val out = ArrayList<Entry>()
            fun walk(off: Long, len: Long, depth: Int) {
                if (depth > 4) throw IOException("${file.name}: directories nested too deep")
                for (e in decodeDirectory(decompress(read(off, len), header.internalCompression))) {
                    if (e.runLength == 0L) walk(header.leafOffset + e.offset, e.length, depth + 1) else out += e
                }
            }
            walk(header.rootOffset, header.rootLength, 0)
            return out
        }

        /** Raw (still compressed as stored) bytes of a tile entry. */
        fun tileBytes(e: Entry): ByteArray = read(header.dataOffset + e.offset, e.length)

        /** Raw bytes of tile z/x/y, null when absent. */
        fun tile(z: Int, x: Int, y: Int): ByteArray? {
            val id = zxyToTileId(z, x, y)
            val e = entries().firstOrNull { id >= it.tileId && id < it.tileId + it.runLength } ?: return null
            return tileBytes(e)
        }

        /** Copies [length] bytes at data offset [offset] to [out]. */
        fun copyTo(offset: Long, length: Long, out: FileOutputStream) {
            raf.channel.transferTo(header.dataOffset + offset, length, out.channel).let { n ->
                var done = n
                while (done < length) {
                    val m = raf.channel.transferTo(header.dataOffset + offset + done, length - done, out.channel)
                    if (m <= 0) throw IOException("${file.name}: short read")
                    done += m
                }
            }
        }

        override fun close() = raf.close()
    }

    // ------------------------------------------------------------------------------------------- merge writer

    /**
     * One input of [merge]: an archive and which of its tiles to take. Inputs later in the list win when two give the
     * same tile (the store's ownership rules normally make them disjoint).
     */
    class Input(val file: File, val keep: (z: Int, x: Int, y: Int) -> Boolean)

    /** What [merge] wrote. */
    data class Result(val tiles: Long, val bytes: Long, val minZoom: Int, val maxZoom: Int,
                      /** Data bytes taken from each input (same order as the inputs). */
                      val bytesPerInput: List<Long>)

    class IncompatibleException(message: String) : IOException(message)

    /**
     * Writes [out] with the kept tiles of [inputs] (tile data copied, identical tiles of one input stored once,
     * clustered). Inputs whose tile type or compression differ from the first usable one throw
     * [IncompatibleException] (mixing PNG and WebP terrain, or format 1 and 2, would break the map). Returns null and
     * writes nothing when no tile is kept. [bounds] = [west, south, east, north] for the header (null = from tiles).
     * The caller writes to a temp path and renames (crash safety).
     */
    fun merge(inputs: List<Input>, out: File, bounds: DoubleArray? = null, metadataJson: String? = null): Result? {
        val readers = ArrayList<Reader>()
        try {
            for (i in inputs) readers += Reader(i.file)
            val first = readers.firstOrNull() ?: return null
            for (r in readers) {
                if (r.header.tileType != first.header.tileType || r.header.tileCompression != first.header.tileCompression)
                    throw IncompatibleException("${r.file.path}: tile type ${r.header.tileType}/compression ${r.header.tileCompression} " +
                        "differs from ${first.file.path} (${first.header.tileType}/${first.header.tileCompression})")
            }
            // tile id → (input, entry); later inputs override earlier ones
            val chosen = java.util.TreeMap<Long, Pair<Int, Entry>>()
            readers.forEachIndexed { idx, r ->
                val keep = inputs[idx].keep
                for (e in r.entries()) {
                    var id = e.tileId
                    val end = e.tileId + e.runLength
                    if (e.runLength > 1_000_000) throw IOException("${r.file.name}: run of ${e.runLength} tiles not supported")
                    while (id < end) {
                        val (z, x, y) = tileIdToZxy(id)
                        if (keep(z, x, y)) chosen[id] = idx to Entry(id, e.offset, e.length, 1)
                        id++
                    }
                }
            }
            if (chosen.isEmpty()) return null
            // layout of the data section: first use of an (input, source offset) gets the next offset
            val dataOffsetOf = HashMap<Pair<Int, Long>, Long>()
            val copies = ArrayList<Triple<Int, Long, Long>>() // input, source offset, length – in output order
            val outEntries = ArrayList<Entry>()
            var dataLen = 0L
            val perInput = LongArray(readers.size)
            var minZ = 99; var maxZ = 0
            var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for ((id, pair) in chosen) {
                val (idx, e) = pair
                val key = idx to e.offset
                val off = dataOffsetOf.getOrPut(key) {
                    val o = dataLen; dataLen += e.length; copies += Triple(idx, e.offset, e.length); perInput[idx] += e.length; o
                }
                val last = outEntries.lastOrNull()
                if (last != null && last.offset == off && last.length == e.length && last.tileId + last.runLength == id) {
                    outEntries[outEntries.size - 1] = last.copy(runLength = last.runLength + 1)
                } else outEntries += Entry(id, off, e.length, 1)
                val (z, x, y) = tileIdToZxy(id)
                minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
                val n = (1 shl z).toDouble()
                minX = minOf(minX, x / n); maxX = maxOf(maxX, (x + 1) / n); minY = minOf(minY, y / n); maxY = maxOf(maxY, (y + 1) / n)
            }
            val (root, leaves) = buildDirectories(outEntries)
            val meta = gzip((metadataJson ?: first.metadata()).toByteArray(Charsets.UTF_8))
            val b = bounds ?: doubleArrayOf(minX * 360 - 180, latOfY(maxY), maxX * 360 - 180, latOfY(minY))
            fun e7(d: Double) = Math.round(d * 1e7).toInt()
            val rootOff = HEADER_SIZE.toLong()
            val metaOff = rootOff + root.size
            val leafOff = metaOff + meta.size
            val dataOff = leafOff + leaves.size
            val header = Header(rootOff, root.size.toLong(), metaOff, meta.size.toLong(), leafOff, leaves.size.toLong(),
                dataOff, dataLen, chosen.size.toLong(), outEntries.size.toLong(), copies.size.toLong(), true,
                COMPRESSION_GZIP, first.header.tileCompression, first.header.tileType, minZ, maxZ,
                e7(b[0]), e7(b[1]), e7(b[2]), e7(b[3]), minZ, e7((b[0] + b[2]) / 2), e7((b[1] + b[3]) / 2))
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { fo ->
                fo.write(header.bytes()); fo.write(root); fo.write(meta); fo.write(leaves)
                for ((idx, srcOff, len) in copies) readers[idx].copyTo(srcOff, len, fo)
                fo.flush(); fo.fd.sync()
            }
            return Result(chosen.size.toLong(), out.length(), minZ, maxZ, perInput.toList())
        } finally {
            readers.forEach { r -> try { r.close() } catch (e: IOException) { /* expected: closing a read-only file */ } }
        }
    }

    /**
     * Writes an archive from tiles in memory ("z/x/y" → stored bytes): small archives for tests and fixtures. Identical
     * byte arrays (same instance) are stored once.
     */
    fun write(tiles: Map<Triple<Int, Int, Int>, ByteArray>, out: File, tileType: Int = TYPE_MVT,
              tileCompression: Int = COMPRESSION_GZIP, metadataJson: String = "{}") {
        val tmp = File(out.path + ".src")
        val data = ByteArrayOutputStream()
        val offsets = java.util.IdentityHashMap<ByteArray, Long>()
        val entries = tiles.entries.map { (k, v) -> zxyToTileId(k.first, k.second, k.third) to v }.sortedBy { it.first }.map { (id, v) ->
            val off = offsets.getOrPut(v) { data.size().toLong().also { data.write(v) } }
            Entry(id, off, v.size.toLong(), 1)
        }
        val (root, leaves) = buildDirectories(entries)
        val meta = gzip(metadataJson.toByteArray(Charsets.UTF_8))
        val zs = tiles.keys.map { it.first }
        val h = Header(HEADER_SIZE.toLong(), root.size.toLong(), HEADER_SIZE.toLong() + root.size, meta.size.toLong(),
            HEADER_SIZE.toLong() + root.size + meta.size, leaves.size.toLong(),
            HEADER_SIZE.toLong() + root.size + meta.size + leaves.size, data.size().toLong(),
            entries.size.toLong(), entries.size.toLong(), offsets.size.toLong(), true, COMPRESSION_GZIP, tileCompression, tileType,
            zs.minOrNull() ?: 0, zs.maxOrNull() ?: 0, -1800000000, -850511287, 1800000000, 850511287, 0, 0, 0)
        FileOutputStream(tmp).use { it.write(h.bytes()); it.write(root); it.write(meta); it.write(leaves); it.write(data.toByteArray()) }
        if (!tmp.renameTo(out)) { out.delete(); if (!tmp.renameTo(out)) throw IOException("could not write ${out.name}") }
    }

    /**
     * Checks that [file] is a readable archive: header, every directory, every tile inside the data section.
     * Returns the number of addressed tiles; throws with a plain message otherwise.
     */
    fun verify(file: File): Long = Reader(file).use { r ->
        var n = 0L
        for (e in r.entries()) {
            if (e.offset < 0 || e.length <= 0 || e.offset + e.length > r.header.dataLength)
                throw IOException("${file.name}: tile ${e.tileId} outside the data section")
            n += e.runLength
        }
        if (n != r.header.addressedTiles) throw IOException("${file.name}: $n tiles found, header says ${r.header.addressedTiles}")
        n
    }

    /** Root + leaf directories (gzip) so that the root fits [ROOT_LIMIT]. */
    fun buildDirectories(entries: List<Entry>): Pair<ByteArray, ByteArray> {
        val rootOnly = gzip(encodeDirectory(entries))
        if (rootOnly.size <= ROOT_LIMIT) return rootOnly to ByteArray(0)
        var leafSize = 4096
        while (true) {
            val leaves = ByteArrayOutputStream()
            val rootEntries = ArrayList<Entry>()
            for (chunk in entries.chunked(leafSize)) {
                val bytes = gzip(encodeDirectory(chunk))
                rootEntries += Entry(chunk.first().tileId, leaves.size().toLong(), bytes.size.toLong(), 0)
                leaves.write(bytes)
            }
            val root = gzip(encodeDirectory(rootEntries))
            if (root.size <= ROOT_LIMIT) return root to leaves.toByteArray()
            leafSize *= 2
        }
    }

    fun encodeDirectory(entries: List<Entry>): ByteArray {
        val o = ByteArrayOutputStream()
        writeVarint(o, entries.size.toLong())
        var last = 0L
        for (e in entries) { writeVarint(o, e.tileId - last); last = e.tileId }
        for (e in entries) writeVarint(o, e.runLength)
        for (e in entries) writeVarint(o, e.length)
        for ((i, e) in entries.withIndex()) {
            if (i > 0 && e.offset == entries[i - 1].offset + entries[i - 1].length) writeVarint(o, 0)
            else writeVarint(o, e.offset + 1)
        }
        return o.toByteArray()
    }

    fun decodeDirectory(b: ByteArray): List<Entry> {
        var pos = 0
        fun varint(): Long {
            var shift = 0; var v = 0L
            while (true) {
                if (pos >= b.size) throw IOException("directory truncated")
                val c = b[pos++].toInt() and 0xff
                v = v or ((c and 0x7f).toLong() shl shift)
                if (c < 0x80) return v
                shift += 7
                if (shift > 63) throw IOException("bad varint")
            }
        }
        val n = varint().toInt()
        if (n < 0 || n > b.size) throw IOException("bad directory size $n")
        val ids = LongArray(n); val runs = LongArray(n); val lens = LongArray(n); val offs = LongArray(n)
        var last = 0L
        for (i in 0 until n) { last += varint(); ids[i] = last }
        for (i in 0 until n) runs[i] = varint()
        for (i in 0 until n) lens[i] = varint()
        for (i in 0 until n) {
            val v = varint()
            offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
        }
        return List(n) { Entry(ids[it], offs[it], lens[it], runs[it]) }
    }

    private fun writeVarint(o: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) { o.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
        o.write(v.toInt())
    }

    fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    fun decompress(b: ByteArray, comp: Int): ByteArray = when (comp) {
        COMPRESSION_GZIP -> GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
        0, COMPRESSION_NONE -> b
        else -> throw IOException("compression $comp not supported")
    }

    // ------------------------------------------------------------------------------------------- tile ids

    /** First tile id of zoom [z]: sum of 4^k for k < z. */
    fun zoomStart(z: Int): Long { var acc = 0L; for (k in 0 until z) acc += 1L shl (2 * k); return acc }

    /** z/x/y → PMTiles tile id (Hilbert curve per zoom). */
    fun zxyToTileId(z: Int, x: Int, y: Int): Long {
        val n = 1L shl z
        var rx: Long; var ry: Long; var d = 0L
        var tx = x.toLong(); var ty = y.toLong()
        var s = n / 2
        while (s > 0) {
            rx = if ((tx and s) > 0) 1 else 0
            ry = if ((ty and s) > 0) 1 else 0
            d += s * s * ((3 * rx) xor ry)
            // rotate
            if (ry == 0L) {
                if (rx == 1L) { tx = s - 1 - tx; ty = s - 1 - ty }
                val t = tx; tx = ty; ty = t
            }
            s /= 2
        }
        return zoomStart(z) + d
    }

    /** PMTiles tile id → z, x, y. */
    fun tileIdToZxy(id: Long): Triple<Int, Int, Int> {
        var acc = 0L
        for (z in 0..31) {
            val num = 1L shl (2 * z)
            if (acc + num > id) {
                var t = id - acc
                val n = 1L shl z
                var x = 0L; var y = 0L
                var s = 1L
                while (s < n) {
                    val rx = 1L and (t / 2)
                    val ry = 1L and (t xor rx)
                    if (ry == 0L) {
                        if (rx == 1L) { x = s - 1 - x; y = s - 1 - y }
                        val tmp = x; x = y; y = tmp
                    }
                    x += s * rx; y += s * ry
                    t /= 4; s *= 2
                }
                return Triple(z, x.toInt(), y.toInt())
            }
            acc += num
        }
        throw IllegalArgumentException("tile id too large")
    }

    private fun latOfY(yFrac: Double) = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(Math.PI * (1 - 2 * yFrac))))
}
