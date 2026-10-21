package com.onpath.app.data

import com.onpath.app.cells.CellId
import com.onpath.app.cells.NetworkStatus
import com.onpath.app.debug.Problems
import com.onpath.app.routing.GeoPoint
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Which BRouter routing data tiles (5°×5°, e.g. E10_N45.rd5) a place needs (field test 2026-10-21: a fresh phone never
 * got routing data by itself). Pure, unit-tested.
 */
object RoutingTiles {
    /** Size of a tile when brouter.de has not said it yet (Alps tiles are 150–250 MB). */
    const val ESTIMATED_BYTES = 200L * 1_048_576

    /** Tiles for these points; consecutive points are joined by sampling (a straight leg may cross a tile edge). */
    fun forPoints(points: List<GeoPoint>): List<String> {
        val out = LinkedHashSet<String>()
        points.forEachIndexed { i, p ->
            if (p.lat.isNaN() || p.lon.isNaN() || abs(p.lat) > 89.0 || abs(p.lon) > 180.0) return@forEachIndexed
            out += SegmentDownloader.tileName(p.lat, p.lon)
            val q = points.getOrNull(i + 1) ?: return@forEachIndexed
            val steps = ceil(max(abs(q.lat - p.lat), abs(q.lon - p.lon)) / 0.5).toInt()
            for (k in 1 until steps) {
                val f = k.toDouble() / steps
                out += SegmentDownloader.tileName(p.lat + (q.lat - p.lat) * f, p.lon + (q.lon - p.lon) * f)
            }
        }
        return out.toList()
    }

    /** Tiles under these map regions (each region's corners – a region may lie on a tile edge). */
    fun forCells(cells: Collection<CellId>): List<String> = cells.flatMap { c ->
        val b = c.bbox // west, south, east, north
        val e = 1e-9
        listOf(GeoPoint(b[1] + e, b[0] + e), GeoPoint(b[1] + e, b[2] - e), GeoPoint(b[3] - e, b[0] + e), GeoPoint(b[3] - e, b[2] - e))
            .map { SegmentDownloader.tileName(it.lat, it.lon) }
    }.distinct()

    /** Of [names], those not on the phone ([exists]: complete file present). */
    fun missing(names: Collection<String>, exists: (String) -> Boolean): List<String> = names.filter { !exists(it) }.distinct()
}

/**
 * Plain notice on the plan while routing data is missing (never a file name or path – those go to Problems). Pure,
 * unit-tested.
 */
object RoutingDataNotice {
    const val LOADING = "Routing data for this area is still loading – the route is shown as a straight line until then."
    const val NOT_YET = "No routing data for this area yet – it loads when you're online. The route is shown as a straight line."

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
 * Loads missing routing data tiles from brouter.de by itself (owner 2026-10-21): when a map area is loaded or a route is
 * planned where a tile is missing. On mobile data too, unless "Maps only on Wi-Fi" is set. Resumes broken downloads
 * (Range), checks the size, installs atomically ([SegmentDownloader]); failures go to Problems and are tried again
 * when the network changes ([retry]) or the next time the tile is needed. One tile at a time.
 *
 * [launch] runs a block in the background (tests: at once); [onInstalled] gets the tiles that arrived (the plan is
 * computed again), [onChange] every status change (progress, offline …). Thread-safe.
 */
class RoutingDataLoader(
    private val segmentsDir: File,
    private val network: NetworkStatus,
    private val wifiOnly: () -> Boolean,
    private val launch: (() -> Unit) -> Unit,
    private val onInstalled: (List<String>) -> Unit = {},
    private val onChange: () -> Unit = {},
    private val download: (name: String, dir: File, onProgress: (Long, Long) -> Unit, cancelled: () -> Boolean) -> Unit =
        { n, d, p, c -> SegmentDownloader.download(n, d, p, c) },
) {
    enum class Phase { LOADING, OFFLINE, WIFI, FAILED }

    /** What is loading: [names] still missing, bytes over all of them (unknown sizes estimated). */
    data class Status(val names: List<String>, val phase: Phase, val doneBytes: Long, val totalBytes: Long, val bytesPerSecond: Double?) {
        val percent: Int get() = if (totalBytes <= 0) 0 else (doneBytes * 100 / totalBytes).toInt().coerceIn(0, 99)
        val secondsLeft: Int? get() = bytesPerSecond?.takeIf { it > 0 }?.let { ((totalBytes - doneBytes) / it).toInt() }
    }

    private val lock = Any()
    private val wanted = LinkedHashSet<String>()
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

    fun exists(name: String): Boolean = File(segmentsDir, name).let { it.isFile && it.length() > 0 }

    /** Missing tiles among [names]. */
    fun missing(names: Collection<String>): List<String> = RoutingTiles.missing(names, ::exists)

    /** Null = nothing wanted (all there, or stopped with Cancel). */
    val status: Status? get() = synchronized(lock) {
        val p = phase ?: return null
        val open = wanted.toList()
        if (open.isEmpty()) return null
        Status(open, p, open.sumOf { done[it] ?: partSize(it) }, open.sumOf { sizes[it] ?: RoutingTiles.ESTIMATED_BYTES }, rate)
    }

    val loading: Boolean get() = status?.phase == Phase.LOADING

    private fun partSize(name: String): Long = File(segmentsDir, ".$name.part").let { if (it.isFile) it.length() else 0L }

    /** Loads the missing tiles of [names] (already loading ones are not asked twice). Returns the missing ones. */
    fun ensure(names: Collection<String>, ignoreWifiOnly: Boolean = false): List<String> {
        if (ignoreWifiOnly) wifiOverride = true
        val miss = missing(names)
        if (miss.isEmpty()) return miss
        val start = synchronized(lock) {
            val added = miss.filter { wanted.add(it) }
            if (added.isNotEmpty()) Problems.info("routing", "Routing data missing for ${added.joinToString()}; loading it")
            !running || phase != Phase.LOADING
        }
        if (start) start()
        return miss
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
    }

    /** Cancel: stop the download (the partial file stays for resuming); asked again when a tile is needed again. */
    fun cancel() {
        synchronized(lock) {
            if (wanted.isEmpty()) return
            cancelled = true; wanted.clear(); phase = null; wifiOverride = false
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

    private fun runQueue() {
        val got = mutableListOf<String>()
        try {
            while (true) {
                val name = synchronized(lock) {
                    if (cancelled) null else wanted.firstOrNull()
                } ?: break
                if (exists(name)) { synchronized(lock) { wanted.remove(name) }; got += name; continue }
                try {
                    synchronized(lock) { rateAt = System.currentTimeMillis(); rateBytes = partSize(name); rate = null }
                    download(name, segmentsDir, { d, t -> progress(name, d, t) }, { cancelled })
                    synchronized(lock) { wanted.remove(name); done.remove(name) }
                    Problems.info("routing", "Routing data $name installed in ${segmentsDir.absolutePath}")
                    got += name
                    onChange()
                } catch (e: SegmentDownloader.Cancelled) {
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

    private fun progress(name: String, d: Long, t: Long) {
        synchronized(lock) {
            done[name] = d
            if (t > 0) sizes[name] = t
            val now = System.currentTimeMillis()
            val dt = (now - rateAt) / 1000.0
            if (dt >= 2 && d > rateBytes) rate = (d - rateBytes) / dt
        }
        onChange()
    }
}
