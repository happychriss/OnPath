package com.onpath.app.data

import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.Rd5Parts.Part
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** brouter.de as seen by [Rd5PartStore]: a file read by byte ranges. */
interface Rd5Remote {
    /** Remote file: total [length], [etag] / [lastModified] (0 = unknown) and its first 200 bytes. */
    class Head(val length: Long, val etag: String?, val lastModified: Long, val index: ByteArray)

    /** First bytes of [tile] (HTTP range 0–199). */
    fun head(tile: String): Head

    /**
     * Bytes [from] until [toExclusive] of [tile]. Throws [Changed] when the server's file is no longer [etag]
     * (If-Range: the server then sends the whole new file – we stop and start over).
     */
    fun open(tile: String, from: Long, toExclusive: Long, etag: String?): InputStream

    /** The file on the server changed while we were reading it. */
    class Changed(msg: String) : IOException(msg)
}

/** Up to [n] bytes (fewer only at the end of the stream); InputStream.readNBytes needs Android 13. */
internal fun readUpTo(inp: InputStream, n: Int): ByteArray {
    val b = ByteArray(n)
    var got = 0
    while (got < n) {
        val k = inp.read(b, got, n - got)
        if (k < 0) break
        got += k
    }
    return if (got == n) b else b.copyOf(got)
}

/** [Rd5Remote] over HTTPS (plain HttpURLConnection, like [SegmentDownloader]). brouter.de answers ranges (nginx). */
class HttpRd5Remote(private val baseUrl: String = SegmentDownloader.BASE_URL) : Rd5Remote {
    private fun conn(tile: String, range: String, etag: String?) =
        (URL(baseUrl + tile).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 30_000
            setRequestProperty("Range", "bytes=$range")
            if (etag != null) setRequestProperty("If-Range", etag)
        }

    override fun head(tile: String): Rd5Remote.Head {
        val c = conn(tile, "0-${Rd5Parts.INDEX_BYTES - 1}", null)
        try {
            val code = c.responseCode
            if (code == 404) throw IOException("brouter.de has no routing data $tile (404).")
            if (code != 206) throw IOException("Server answered $code for the index of $tile (range not supported?).")
            val total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: throw IOException("Server sent no file size for $tile.")
            val b = c.inputStream.use { readUpTo(it, Rd5Parts.INDEX_BYTES) }
            if (b.size != Rd5Parts.INDEX_BYTES) throw IOException("Index of $tile incomplete (${b.size} bytes).")
            return Rd5Remote.Head(total, c.getHeaderField("ETag"), c.lastModified, b)
        } finally {
            c.disconnect()
        }
    }

    override fun open(tile: String, from: Long, toExclusive: Long, etag: String?): InputStream {
        val c = conn(tile, "$from-${toExclusive - 1}", etag)
        val code = try { c.responseCode } catch (e: IOException) { c.disconnect(); throw e }
        if (code == 200) { c.disconnect(); throw Rd5Remote.Changed("$tile changed on the server.") }
        if (code != 206) { c.disconnect(); throw IOException("Server answered $code for $tile.") }
        val gotEtag = c.getHeaderField("ETag")
        val start = c.getHeaderField("Content-Range")?.removePrefix("bytes ")?.substringBefore('-')?.toLongOrNull()
        if ((etag != null && gotEtag != null && gotEtag != etag) || start != from) {
            c.disconnect(); throw Rd5Remote.Changed("$tile changed on the server (or wrong range).")
        }
        return object : java.io.FilterInputStream(c.inputStream) {
            override fun close() { try { super.close() } finally { c.disconnect() } }
        }
    }
}

/**
 * BRouter routing data on the phone by 1° part (owner decision "D", docs/design/map-data-size.md §6): fetches only the
 * needed 1° × 1° parts of a 5° rd5 from brouter.de (HTTP range, ~5–19 MB each instead of ~200 MB) and keeps one local
 * rd5 per tile holding the parts we have (others empty). Next to it `<tile>.parts.json`: parts fetched, remote
 * ETag / Last-Modified / length, remote index + tail (sizes of missing parts known offline), fetch time.
 *
 * Rules: parts download into `.rd5parts/` and resume after a broken connection; the rd5 is rewritten atomically
 * (temp + rename) when parts are added; parts of different remote versions are never mixed – when the server's file
 * changed, the parts already here are fetched again together with the new ones; a file without a parts list (a whole
 * rd5 downloaded or pushed earlier) counts as complete. The rd5's modification time is the fetch time of its version
 * (the 35-day refresh rule reads it). Writers ([download], [refreshIfNewer], [remove]) and network reads are
 * synchronized – one writer per file; [has] / [missing] read without waiting (the rd5 is renamed into place before its
 * parts list, and a list that does not match the file is not trusted).
 */
class Rd5PartStore(
    private val dir: File,
    private val remote: Rd5Remote = HttpRd5Remote(),
    private val now: () -> Long = System::currentTimeMillis,
    /** Tests: called after an install / removal put the new rd5 in place, before its parts list is written. */
    private val afterRd5Written: () -> Unit = {},
) {
    /** What the server has for a tile: head + tail. */
    class RemoteTile(val head: Rd5Remote.Head, val tail: ByteArray) {
        val index = Rd5Parts.Index(head.index)
    }

    /** The local file's parts list. */
    data class State(
        val parts: Set<Int>, val etag: String?, val lastModified: Long, val fetchedAt: Long,
        val index: Rd5Parts.Index?, val tail: ByteArray?, val fileLength: Long, val legacy: Boolean,
    )

    private val remoteCache = HashMap<String, Pair<Long, RemoteTile>>()

    /**
     * Tiles being rewritten → their state before (array of one, null = no file). Between renaming the new rd5 into
     * place and writing its parts list, readers get this consistent old state instead of a "list does not match"
     * (field 2026-10-21/22: 12 false warnings, one per added part) or a new partial file without list (= "complete").
     */
    private val rewriting = java.util.concurrent.ConcurrentHashMap<String, Array<State?>>()

    fun rd5(tile: String) = File(dir, tile)
    private fun sidecar(tile: String) = File(dir, tile.removeSuffix(".rd5") + ".parts.json")
    private fun staging() = File(dir, ".rd5parts")

    /** [stateOnDisk] for the files as they were (length + modification time of the rd5 and its parts list). */
    private class Cached(val key: List<Long>, val state: State?, @Volatile var index: Rd5Parts.Index? = null)

    /**
     * Tile → its last read state. [has] is asked per part after every route (perf report 2026-10-22: each call read and
     * parsed the parts list); four file stats instead. Our own writes drop the entry; a file changed from outside
     * (adb push) has another length / time.
     */
    private val stateCache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    private fun fileKey(tile: String): List<Long> {
        val f = rd5(tile); val s = sidecar(tile)
        return listOf(f.length(), f.lastModified(), s.length(), s.lastModified())
    }

    private fun cached(tile: String): Cached {
        val key = fileKey(tile)
        stateCache[tile]?.let { if (it.key == key) return it }
        return Cached(key, stateOnDisk(tile)).also { stateCache[tile] = it }
    }

    /** Local state of [tile]; null = no file. A list that does not match the file (crash between the two) is ignored. */
    fun state(tile: String): State? {
        rewriting[tile]?.let { return it[0] }
        return cached(tile).state
    }

    /** The local rd5's own index (cached with the state). */
    private fun localIndex(tile: String): Rd5Parts.Index? {
        if (rewriting.containsKey(tile)) return Rd5Parts.readIndex(rd5(tile))
        val c = cached(tile)
        return c.index ?: Rd5Parts.readIndex(rd5(tile)).also { c.index = it }
    }

    private fun stateOnDisk(tile: String): State? {
        val f = rd5(tile)
        if (!f.isFile || f.length() < Rd5Parts.INDEX_BYTES) return null
        val s = sidecar(tile)
        if (s.isFile) {
            try {
                val j = JSONObject(s.readText())
                if (j.getLong("fileLength") == f.length()) {
                    val a = j.getJSONArray("parts")
                    return State(
                        (0 until a.length()).map { a.getInt(it) }.toSet(), j.optString("etag").ifEmpty { null },
                        j.optLong("lastModified"), j.getLong("fetchedAt"),
                        Rd5Parts.Index(Base64.getDecoder().decode(j.getString("index"))),
                        Base64.getDecoder().decode(j.getString("tail")), f.length(), legacy = false,
                    )
                }
                com.onpath.app.debug.Problems.warn("routing", "Parts list of $tile does not match the file; parts are checked again")
            } catch (e: Exception) {
                com.onpath.app.debug.Problems.warn("routing", "Parts list of $tile unreadable; parts are checked again", e)
            }
            // stale list: the file's own index says which parts have data; version unknown → refetched when added to
            val idx = Rd5Parts.readIndex(f) ?: return null
            return State(idx.withData, null, 0, f.lastModified(), null, null, f.length(), legacy = false)
        }
        // a whole rd5 (old download, adb push): complete
        return State((0 until Rd5Parts.PARTS).toSet(), null, 0, f.lastModified(), null, null, f.length(), legacy = true)
    }

    /** The phone has [part] (with data, or known to be empty on the server). */
    fun has(part: Part): Boolean = state(part.tile)?.parts?.contains(part.index) == true

    fun missing(parts: Collection<Part>): List<Part> = parts.distinct().filter { !has(it) }

    /** Size of [part] when known without the network (from a tile's stored remote index). */
    fun knownSize(part: Part): Long? = state(part.tile)?.index?.size(part.index)

    /** Size of [part] on the phone (0 = not here or empty). */
    fun sizeOnPhone(part: Part): Long =
        if (has(part)) localIndex(part.tile)?.size(part.index)?.coerceAtLeast(0L) ?: 0L else 0L

    /** Bytes of [part] already downloaded by a broken download (any version; resumed when it is the current one). */
    fun stagedBytes(part: Part): Long =
        staging().listFiles { f -> f.name.startsWith(part.tile.removeSuffix(".rd5") + "-") && f.name.endsWith("-${part.index}.part") }
            ?.sumOf { it.length() } ?: 0L

    /** Size of [part] on the server (network once per tile, then cached). */
    @Synchronized fun remoteSize(part: Part): Long = knownSize(part) ?: remote(part.tile).index.size(part.index)

    /** Remote head + tail of [tile] (cached a few minutes: sizing and downloading right after each other). */
    @Synchronized fun remote(tile: String, fresh: Boolean = false): RemoteTile {
        val t = now()
        if (!fresh) remoteCache[tile]?.let { (at, r) -> if (t - at < REMOTE_CACHE_MS) return r }
        val head = remote.head(tile)
        val idx = Rd5Parts.Index(head.index)
        idx.check(head.length)
        val tail = if (head.length > idx.dataEnd) remote.open(tile, idx.dataEnd, head.length, head.etag).use { readUpTo(it, (head.length - idx.dataEnd).toInt().coerceAtMost(4096)) } else ByteArray(0)
        return RemoteTile(head, tail).also { remoteCache[tile] = t to it }
    }

    /** Local file and the server's are the same version (same tail: creation time and part checksums). */
    private fun sameVersion(st: State, r: RemoteTile, tile: String): Boolean {
        val localTail = st.tail ?: rd5(tile).let { f -> Rd5Parts.readIndex(f)?.let { Rd5Parts.readTail(f, it) } } ?: return false
        if (localTail.size != r.tail.size || localTail.size < 12) return localTail.isEmpty() && r.tail.isEmpty() && st.etag != null && st.etag == r.head.etag
        // bytes 8..11 = the index checksum, which differs for a file with fewer parts
        return localTail.copyOfRange(0, 8).contentEquals(r.tail.copyOfRange(0, 8)) &&
            localTail.copyOfRange(12, localTail.size).contentEquals(r.tail.copyOfRange(12, r.tail.size))
    }

    /** What getting [parts] costs: parts to fetch (incl. refetch on a version change) and their bytes still to load. */
    data class Plan(val tile: String, val fetch: Set<Int>, val keep: Set<Int>, val record: Set<Int>, val bytes: Long, val remote: RemoteTile)

    /** Network: asks the server for the index of each tile. Already complete tiles are left out. */
    @Synchronized fun plan(parts: Collection<Part>, fresh: Boolean = false): List<Plan> =
        parts.groupBy { it.tile }.mapNotNull { (tile, ps) ->
            val st = state(tile)
            val wanted = ps.map { it.index }.toSet()
            if (st != null && st.parts.containsAll(wanted) && !fresh) return@mapNotNull null
            planTile(tile, wanted, st, remote(tile, fresh))
        }

    private fun planTile(tile: String, wanted: Set<Int>, st: State?, r: RemoteTile): Plan {
        val same = st != null && sameVersion(st, r, tile)
        val keep = if (same) st!!.parts else emptySet()
        val record = wanted + (st?.parts ?: emptySet())
        val fetch = (record - keep).filter { r.index.size(it) > 0 }.toSet()
        val bytes = fetch.sumOf { r.index.size(it) - staged(tile, r, it).length().coerceAtMost(r.index.size(it)) }
        return Plan(tile, fetch, keep, record, bytes, r)
    }

    /** Bytes still to load for [parts] (network for the index; 0 = all here). For "about X MB" before loading. */
    fun bytesToLoad(parts: Collection<Part>): Long = plan(parts).sumOf { it.bytes }

    private fun staged(tile: String, r: RemoteTile, i: Int): File {
        val version = Rd5Parts.creationTime(r.tail) ?: r.head.lastModified
        return File(staging(), "${tile.removeSuffix(".rd5")}-$version-$i.part")
    }

    /** The user stopped the download (Cancel); staged parts stay for resuming. */
    class Cancelled : Exception("Routing data download stopped.")

    /**
     * Gets [parts] onto the phone (see the class rules). [onProgress] (bytesDone, bytesTotal) over all parts to load.
     * Returns the parts that were missing and are here now. Throws with a plain message; staged parts stay.
     */
    @Synchronized fun download(parts: Collection<Part>, onProgress: (Long, Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }): List<Part> {
        val miss = missing(parts)
        var plans = plan(parts)
        if (plans.isEmpty()) return emptyList()
        for (attempt in 1..2) {
            try {
                val total = plans.sumOf { it.bytes }
                var done = 0L
                for (p in plans) install(p, { d -> onProgress(done + d, total) }, cancelled).also { done += p.bytes }
                onProgress(total, total)
                return miss
            } catch (e: Rd5Remote.Changed) {
                if (attempt == 2) throw IOException("Routing data changed on the server while loading – try again.", e)
                com.onpath.app.debug.Problems.info("routing", "Routing data changed on the server during the download; starting over", e)
                plans = plan(parts, fresh = true).also { remoteCache.clear() }
            }
        }
        return miss
    }

    /**
     * Newer routing data for [tile]: when the server's file is another version than the local one, fetches the local
     * parts again (only those – a parts file stays small). Returns true when a new version was installed.
     */
    @Synchronized fun refreshIfNewer(tile: String, onProgress: (Long, Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }): Boolean {
        val st = state(tile) ?: return false
        val r = remote(tile, fresh = true)
        if (sameVersion(st, r, tile)) return false
        val p = planTile(tile, st.parts, st, r)
        install(p, { d -> onProgress(d, p.bytes) }, cancelled)
        return true
    }

    private fun install(p: Plan, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
        val r = p.remote
        val dest = rd5(p.tile)
        val finalSize = Rd5Parts.INDEX_BYTES + p.record.sumOf { r.index.size(it) } + r.tail.size
        if (dir.apply { mkdirs() }.usableSpace in 1 until p.bytes + finalSize + SPACE_MARGIN) {
            throw IOException("Not enough free storage for routing data (${(p.bytes + finalSize) / 1_048_576} MB).")
        }
        // drop staged parts of other versions of this tile
        staging().mkdirs()
        val mine = p.fetch.map { staged(p.tile, r, it).name }.toSet()
        staging().listFiles { f -> f.name.startsWith(p.tile.removeSuffix(".rd5") + "-") && f.name !in mine }?.forEach { it.delete() }
        var done = 0L
        for (i in p.fetch.sorted()) {
            val f = staged(p.tile, r, i)
            val size = r.index.size(i)
            if (f.length() > size) f.delete()
            val have = f.length()
            if (have < size) {
                remote.open(p.tile, r.index.start(i) + have, r.index.end(i), r.head.etag).use { inp ->
                    java.io.FileOutputStream(f, true).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var got = have
                        var reported = 0L
                        while (got < size) {
                            if (cancelled()) throw Cancelled()
                            val n = inp.read(buf, 0, minOf(buf.size.toLong(), size - got).toInt())
                            if (n < 0) break
                            out.write(buf, 0, n); got += n
                            if (got - reported > 500_000) { onBytes(done + got - have); reported = got }
                        }
                        out.fd.sync()
                    }
                }
                if (f.length() != size) throw IOException("Routing data download incomplete – it continues where it stopped.")
            }
            done += size - have
            onBytes(done)
        }
        val local = Rd5Parts.readIndex(dest)
        val sources = HashMap<Int, Rd5Parts.PartSource>()
        for (i in p.keep) if (r.index.size(i) > 0) sources[i] = Rd5Parts.fromFile(dest, local ?: throw IOException("${p.tile} vanished"), i)
        for (i in p.fetch) sources[i] = Rd5Parts.fromPartFile(staged(p.tile, r, i))
        // the state before the rewrite: read now – after Rd5Parts.write the old list no longer matches the new file
        val before = stateOnDisk(p.tile)
        val fetchedAt = before?.takeIf { p.keep.isNotEmpty() }?.fetchedAt ?: now()
        rewriting[p.tile] = arrayOf(before)
        try {
            Rd5Parts.write(dest, r.index, r.tail, sources)
            afterRd5Written()
            dest.setLastModified(fetchedAt)
            writeSidecar(p.tile, p.record, r, fetchedAt, dest.length())
        } finally { stateCache.remove(p.tile); rewriting.remove(p.tile) }
        for (i in p.fetch) staged(p.tile, r, i).delete()
        File(dir, ".${p.tile}.part").delete() // a half whole-file download from before parts (up to ~200 MB)
    }

    /**
     * Removes [parts] from the phone (a map region deleted and no other region needs them): the rd5 is rewritten
     * without them (atomic), or deleted with its parts list when nothing is left. Works offline. Returns bytes freed.
     */
    @Synchronized fun remove(parts: Collection<Part>): Long {
        var freed = 0L
        for ((tile, ps) in parts.groupBy { it.tile }) {
            val st = state(tile) ?: continue
            val drop = ps.map { it.index }.toSet()
            if (st.parts.intersect(drop).isEmpty()) continue
            val f = rd5(tile)
            val local = Rd5Parts.readIndex(f) ?: continue
            try { local.check(f.length()) } catch (e: IOException) {
                com.onpath.app.debug.Problems.warn("routing", "$tile is not a valid rd5; its parts are not removed", e); continue
            }
            val before = f.length()
            val left = st.parts - drop
            if (left.none { local.size(it) > 0 }) {
                f.delete(); sidecar(tile).delete(); stateCache.remove(tile); freed += before; continue
            }
            val tail = Rd5Parts.readTail(f, local)
            rewriting[tile] = arrayOf(st)
            try {
                Rd5Parts.write(f, local, tail, left.filter { local.size(it) > 0 }.associateWith { Rd5Parts.fromFile(f, local, it) })
                afterRd5Written()
                f.setLastModified(st.fetchedAt)
                // sizes of parts not here: the stored remote index (a whole file: its own index)
                val index = st.index?.raw ?: local.raw
                val r = RemoteTile(Rd5Remote.Head(0, st.etag, st.lastModified, index), st.tail ?: tail)
                writeSidecar(tile, left, r, st.fetchedAt, f.length())
            } finally { stateCache.remove(tile); rewriting.remove(tile) }
            freed += before - f.length()
        }
        return freed
    }

    private fun writeSidecar(tile: String, parts: Set<Int>, r: RemoteTile, fetchedAt: Long, fileLength: Long) {
        val j = JSONObject()
            .put("parts", JSONArray(parts.sorted()))
            .put("etag", r.head.etag ?: "")
            .put("lastModified", r.head.lastModified)
            .put("remoteLength", r.head.length)
            .put("fetchedAt", fetchedAt)
            .put("fileLength", fileLength)
            .put("index", Base64.getEncoder().encodeToString(r.head.index))
            .put("tail", Base64.getEncoder().encodeToString(r.tail))
        val s = sidecar(tile)
        val tmp = File(dir, ".${s.name}.tmp")
        tmp.writeText(j.toString())
        if (!tmp.renameTo(s)) throw IOException("could not save the parts list of $tile")
    }

    companion object {
        private const val REMOTE_CACHE_MS = 10 * 60_000L
        private const val SPACE_MARGIN = 20L * 1_048_576
    }
}
