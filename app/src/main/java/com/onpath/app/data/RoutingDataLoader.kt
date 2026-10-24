package com.onpath.app.data

import com.onpath.app.cells.CellId
import com.onpath.app.cells.NetworkStatus
import com.onpath.app.debug.Problems
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.Rd5Parts
import java.io.File

/**
 * Which BRouter routing data a place needs, as 1° parts of the 5° rd5 files (owner decision "D", 2026-10-21: ~10 MB per
 * part instead of ~200 MB per file; docs/design/map-data-size.md §6). Keys are [Rd5Parts.Part.key]
 * ("E10_N45.rd5:E11_N46"). Pure, unit-tested.
 */
object RoutingTiles {
    /** Size of a part when its tile's index has not been read yet (parts are 1–19 MB, median 8). */
    const val ESTIMATED_BYTES = 10L * 1_048_576

    /** Parts within [marginM] of each point (a point set, the position, a search result) – not the line between. */
    fun forPoints(points: List<GeoPoint>, marginM: Double = Rd5Parts.POINT_MARGIN_M): List<String> =
        points.flatMap { Rd5Parts.forPoint(it, marginM) }.distinct().map { it.key }

    /** Parts in a corridor (~10 km) around a line: a computed route, or the straight legs between planned points. */
    fun forLine(points: List<GeoPoint>, marginM: Double = Rd5Parts.CORRIDOR_MARGIN_M): List<String> =
        Rd5Parts.forCorridor(points, marginM).map { it.key }

    /** Parts under these map regions: routing data comes with the map. */
    fun forCells(cells: Collection<CellId>): List<String> = cells.flatMap { c ->
        val b = c.bbox // west, south, east, north
        Rd5Parts.forBox(b[1], b[0], b[3], b[2])
    }.distinct().map { it.key }

    /** Of [names], those not on the phone ([exists]). */
    fun missing(names: Collection<String>, exists: (String) -> Boolean): List<String> = names.filter { !exists(it) }.distinct()

    /**
     * Routing parts no longer needed when the regions [deleted] go and [kept] stay: parts under a deleted region and
     * under no kept one (parts loaded for a point or a route outside every region stay).
     */
    fun unneeded(deleted: Collection<CellId>, kept: Collection<CellId>): List<String> = forCells(deleted) - forCells(kept).toSet()

    /**
     * Routing bytes per region for Settings → Offline maps: each part on the phone counted once, for the first region
     * (in [cells] order) that lies on it – shared parts are not counted twice (approximate on purpose).
     */
    fun bytesPerCell(cells: List<CellId>, sizeOnPhone: (String) -> Long): Map<CellId, Long> {
        val seen = HashSet<String>()
        return cells.associateWith { c -> forCells(listOf(c)).filter { seen.add(it) }.sumOf(sizeOnPhone) }
    }
}

/**
 * Plain notice on the plan while routing data is missing (never a file name or path – those go to Problems). Pure,
 * unit-tested.
 */
object RoutingDataNotice {
    const val LOADING = "Map loading – straight line for now"
    const val NOT_YET = "Map loads when online – straight line for now"

    fun text(loading: Boolean): String = if (loading) LOADING else NOT_YET

    fun isNotice(s: String?): Boolean = s == LOADING || s == NOT_YET

    /**
     * The plan's notice: [missing] = a tile under the route is not on the phone → the plain notice (loading or not);
     * a router message about missing data (any wording, may hold a path) → the plain notice too; else [error] as is.
     */
    fun forRoute(error: String?, missing: Boolean, loading: Boolean): String? = when {
        missing -> text(loading)
        error != null && (error.contains("No routing data") || error.contains(".rd5")) -> text(loading)
        else -> error
    }
}

/**
 * Loads missing routing data from brouter.de by itself (owner 2026-10-21): when a map area is loaded or a route is
 * planned where it is missing. On mobile data too, unless "Maps only on Wi-Fi" is set. The unit is a 1° part
 * ([RoutingTiles] keys, [Rd5PartStore]: HTTP range, resume, atomic rewrite, no mixed versions); a plain 5° file name
 * ("E10_N45.rd5") still works for an old caller and means the whole file. Failures go to Problems and are tried again
 * when the network changes ([retry]) or the next time the part is needed. One 5° file at a time; the real sizes are read
 * from the server's index before the first part loads, so the modal can say "about X MB". The wanted parts of one 5° file
 * load together and are written into its rd5 once.
 *
 * [launch] runs a block in the background (tests: at once); [onInstalled] gets the keys that arrived (the plan is
 * computed again), [onChange] every status change (progress, offline …). Thread-safe.
 */
class RoutingDataLoader(
    private val segmentsDir: File,
    private val network: NetworkStatus,
    private val wifiOnly: () -> Boolean,
    private val launch: (() -> Unit) -> Unit,
    private val onInstalled: (List<String>) -> Unit = {},
    private val onChange: () -> Unit = {},
    /** The phone's routing data by part (one per folder; also used for the 35-day refresh and region deletes). */
    val store: Rd5PartStore = Rd5PartStore(segmentsDir),
    /** Loads one batch: one key, or several parts of the same 5° file (one rd5 rewrite for all, [nextBatch]). */
    private val download: (names: List<String>, dir: File, onProgress: (Long, Long) -> Unit, cancelled: () -> Boolean) -> Unit =
        { n, d, p, c -> fetch(store, n, d, p, c) },
    /** Size of [name] on the server (network; null = unknown). Asked once per key before loading. */
    private val remoteSize: (String) -> Long? = { n -> Rd5Parts.Part.parse(n)?.let { store.remoteSize(it) } },
) {
    enum class Phase { LOADING, OFFLINE, WIFI, FAILED }

    /** What is loading: [names] still missing, bytes over all of them (unknown sizes estimated). */
    data class Status(val names: List<String>, val phase: Phase, val doneBytes: Long, val totalBytes: Long, val bytesPerSecond: Double?) {
        val percent: Int get() = if (totalBytes <= 0) 0 else (doneBytes * 100 / totalBytes).toInt().coerceIn(0, 99)
        val secondsLeft: Int? get() = bytesPerSecond?.takeIf { it > 0 }?.let { ((totalBytes - doneBytes) / it).toInt() }
    }

    private val lock = Any()
    private val wanted = LinkedHashSet<String>()
    /** Of [wanted]: asked for only by the plan's route corridor ([ensure] with corridor = true). */
    private val corridorOnly = HashSet<String>()
    private val sizes = HashMap<String, Long>()
    private val done = HashMap<String, Long>()
    private var phase: Phase? = null
    private var running = false
    /** Settings → "Download routing data" (an explicit tap) loads on mobile data even with "Maps only on Wi-Fi". */
    @Volatile private var wifiOverride = false
    private var failedAt = 0L
    @Volatile private var cancelled = false
    private var rateAt = 0L
    private var rateBytes = 0L
    private var rate: Double? = null

    /** [name] is on the phone: a part key (in the local rd5), or a whole 5° file name. */
    fun exists(name: String): Boolean = Rd5Parts.Part.parse(name)?.let { store.has(it) }
        ?: File(segmentsDir, name).let { it.isFile && it.length() > 0 }

    /** Missing tiles among [names]. */
    fun missing(names: Collection<String>): List<String> = RoutingTiles.missing(names, ::exists)

    /** Null = nothing wanted (all there, or stopped with Cancel). */
    val status: Status? get() = synchronized(lock) {
        val p = phase ?: return null
        val open = wanted.toList()
        if (open.isEmpty()) return null
        Status(open, p, open.sumOf { done[it] ?: partSize(it) }, open.sumOf { sizeOf(it) }, rate)
    }

    val loading: Boolean get() = status?.phase == Phase.LOADING

    /** Bytes already here of a broken download (resumed, so they count as done). */
    private fun partSize(name: String): Long = Rd5Parts.Part.parse(name)?.let { store.stagedBytes(it) }
        ?: File(segmentsDir, ".$name.part").let { if (it.isFile) it.length() else 0L }

    /** Size for the modal: told by the download, else the server's index (read before loading), else an estimate. */
    private fun sizeOf(name: String): Long = sizes[name] ?: Rd5Parts.Part.parse(name)?.let { store.knownSize(it) }
        ?: RoutingTiles.ESTIMATED_BYTES

    /** Background: real sizes of the wanted keys from the server's index (one small request per 5° file). */
    private fun readSizes() {
        val unknown = synchronized(lock) { wanted.filter { it !in sizes } }
        if (unknown.isEmpty()) return
        for (n in unknown) {
            val size = try { remoteSize(n) } catch (e: Exception) {
                Problems.info("routing", "Size of routing data $n not known yet (${e.message}); estimated", e); null
            } ?: continue
            synchronized(lock) { sizes[n] = size }
        }
        onChange()
    }

    /** Loads the missing tiles of [names] (already loading ones are not asked twice). Returns the missing ones. */
    fun ensure(names: Collection<String>, ignoreWifiOnly: Boolean = false, corridor: Boolean = false): List<String> {
        if (ignoreWifiOnly) wifiOverride = true
        val miss = missing(names)
        if (miss.isEmpty()) return miss
        val start = synchronized(lock) {
            // [corridor]: wanted only for the plan's route corridor – dropped when the plan changes ([keepCorridor]);
            // asked for by a point / region as well: kept
            if (corridor) miss.filter { it !in wanted }.forEach { corridorOnly += it } else corridorOnly -= miss.toSet()
            val added = miss.filter { wanted.add(it) }
            if (added.isNotEmpty()) Problems.info("routing", "Routing data missing for ${added.joinToString()}; loading it")
            !running || phase != Phase.LOADING
        }
        if (start) start()
        return miss
    }

    /**
     * The plan's route corridor changed (field bug 2026-10-22): parts wanted only for an older corridor and not in
     * [keep] are dropped – a running download of one stops (its partial file stays for resuming). Returns them.
     */
    fun keepCorridor(keep: Collection<String>): List<String> {
        val k = keep.toSet()
        val drop = synchronized(lock) {
            val d = corridorOnly.filter { it !in k && it in wanted }
            corridorOnly.removeAll { it !in k }
            wanted.removeAll(d.toSet()); d.forEach { done.remove(it) }
            if (wanted.isEmpty() && !running) phase = null
            d
        }
        if (drop.isNotEmpty()) {
            Problems.trace("routing", "Dropped ${drop.size} corridor routing parts – plan changed: ${drop.joinToString()}")
            onChange()
        }
        return drop
    }

    /**
     * "Load now over mobile data" while "Maps only on Wi-Fi" holds the download (owner Q6 2026-10-23): the tiles wanted
     * now load once over mobile data; the setting stays (the override ends when the queue is empty or cancelled).
     */
    fun loadOnMobileOnce() {
        wifiOverride = true
        retry(force = true)
    }

    /** Network back / "Try again": continue the wanted tiles. */
    fun retry(force: Boolean = false, now: Long = System.currentTimeMillis()) {
        // a failure (server, storage) is tried again on a network change at most every RETRY_AFTER_FAIL_MS –
        // a flapping connection must not hammer the server; "Try again" ([force]) at once
        val go = synchronized(lock) {
            wanted.isNotEmpty() && !running && (force || phase != Phase.FAILED || now - failedAt >= RETRY_AFTER_FAIL_MS)
        }
        if (go) start()
    }

    companion object {
        const val RETRY_AFTER_FAIL_MS = 60_000L

        /** Default download: part keys through the part store (all at once), a whole file name as before. */
        fun fetch(store: Rd5PartStore, names: List<String>, dir: File, progress: (Long, Long) -> Unit, cancelled: () -> Boolean) {
            val parts = names.mapNotNull { Rd5Parts.Part.parse(it) }
            if (parts.isNotEmpty()) store.download(parts, progress, cancelled)
            names.filter { Rd5Parts.Part.parse(it) == null }.forEach { SegmentDownloader.download(it, dir, progress, cancelled) }
        }
    }

    /** Cancel: stop the download (the partial file stays for resuming); asked again when a tile is needed again. */
    fun cancel() {
        synchronized(lock) {
            if (wanted.isEmpty()) return
            cancelled = true; wanted.clear(); corridorOnly.clear(); phase = null; wifiOverride = false
        }
        Problems.info("routing", "Routing data download stopped by the user (partial file kept)")
        onChange()
    }

    private fun start() {
        val ph = when {
            !network.connected() -> Phase.OFFLINE
            wifiOnly() && !wifiOverride && !network.unmetered() -> Phase.WIFI
            else -> Phase.LOADING
        }
        val go = synchronized(lock) {
            phase = ph
            if (ph == Phase.LOADING) cancelled = false
            if (ph == Phase.LOADING && !running) { running = true; true } else false
        }
        onChange()
        if (go) launch { runQueue() }
    }

    /**
     * The next download: the first wanted key and, when it is a part, every other wanted part of the same 5° file –
     * the local rd5 is rewritten once for all of them instead of once per part (perf report 2026-10-22: 20 parts one
     * by one wrote 1.82 GB for 157 MB downloaded).
     */
    private fun nextBatch(): List<String>? = synchronized(lock) {
        if (cancelled) return null
        val first = wanted.firstOrNull() ?: return null
        val tile = Rd5Parts.Part.parse(first)?.tile ?: return listOf(first)
        wanted.filter { Rd5Parts.Part.parse(it)?.tile == tile }
    }

    private fun runQueue() {
        val got = mutableListOf<String>()
        try {
            readSizes()
            while (true) {
                val all = nextBatch() ?: break
                val here = all.filter { exists(it) }
                if (here.isNotEmpty()) { synchronized(lock) { wanted.removeAll(here.toSet()) }; got += here }
                val batch = all - here.toSet()
                if (batch.isEmpty()) continue
                val name = batch.joinToString()
                try {
                    synchronized(lock) { rateAt = System.currentTimeMillis(); rateBytes = batch.sumOf { partSize(it) }; rate = null }
                    val why = if (synchronized(lock) { batch.all { it in corridorOnly } }) "corridor" else "point"
                    val t0 = System.currentTimeMillis()
                    Problems.trace("routing", "Routing download start: $name ($why), ${batch.sumOf { sizeOf(it) } / 1_048_576} MB" +
                        (if (network.unmetered()) "" else " on mobile data"))
                    // dropped meanwhile ([keepCorridor]) or Cancel: stops at once (the partial file stays)
                    download(batch, segmentsDir, { d, t -> progress(batch, d, t) }, { cancelled || synchronized(lock) { batch.any { it !in wanted } } })
                    synchronized(lock) { wanted.removeAll(batch.toSet()); batch.forEach { done.remove(it); corridorOnly.remove(it) } }
                    Problems.info("routing", "Routing data $name installed in ${segmentsDir.absolutePath}")
                    Problems.trace("routing", "Routing download finished: $name ($why) in ${System.currentTimeMillis() - t0} ms")
                    got += batch
                    onChange()
                } catch (e: SegmentDownloader.Cancelled) {
                    break // expected: the user stopped it (cancel() already cleared the queue)
                } catch (e: Rd5PartStore.Cancelled) {
                    break // expected: the user stopped it (cancel() already cleared the queue)
                } catch (e: Exception) {
                    val offline = !network.connected()
                    synchronized(lock) { failedAt = System.currentTimeMillis(); if (wanted.isNotEmpty()) phase = if (offline) Phase.OFFLINE else Phase.FAILED }
                    if (offline) Problems.info("routing", "Routing data $name: download paused, offline (partial file kept)", e)
                    else Problems.warn("routing", "Routing data $name could not be downloaded (partial file kept; tried again later)", e)
                    break
                }
            }
        } finally {
            // asked again while a cancelled run was ending: go on
            val again = synchronized(lock) {
                running = false
                if (wanted.isEmpty()) { phase = null; wifiOverride = false }
                wanted.isNotEmpty() && phase == Phase.LOADING && !cancelled
            }
            onChange()
            if (got.isNotEmpty()) onInstalled(got)
            if (again) start()
        }
    }

    /** [d] of [t] bytes over the whole [batch]: counted on its first key (the others 0) – the sum is what the modal shows. */
    private fun progress(batch: List<String>, d: Long, t: Long) {
        synchronized(lock) {
            batch.forEachIndexed { i, n -> done[n] = if (i == 0) d else 0L }
            if (t > 0 && batch.size == 1) sizes[batch[0]] = t
            val now = System.currentTimeMillis()
            val dt = (now - rateAt) / 1000.0
            if (dt >= 2 && d > rateBytes) rate = (d - rateBytes) / dt
        }
        onChange()
    }
}
