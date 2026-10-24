package com.onpath.app.cells

import com.onpath.app.debug.Problems
import com.onpath.app.routing.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Why cells are requested (map-cells.md §8 `reason`, §9 triggers). */
enum class CellReason(val api: String) {
    /** T1 / T3: the user's point or own position – highest priority. */
    POINT("point"),
    /** Close neighbours of a point; T5: the next cell ahead on a tour. */
    NEAR("near"),
    /** T4: cells along a computed route. */
    CORRIDOR("corridor"),
    /** Newer version of an installed cell – checked after a route was computed there (owner 2026-10-21). */
    REFRESH("refresh"),
    /**
     * An installed cell built for an older style (content schema below [CellApi.CELL_SCHEMA], e.g. without small
     * streets): rebuilt and loaded at once, also on mobile data – the map is incomplete until then.
     */
    UPDATE("point"),
}

/** What the phone knows about one cell – the card and the Settings list show this. */
sealed interface CellState {
    /** Not on the phone, not asked for. */
    data object None : CellState
    /** Asked for, no answer from the server yet. */
    data object Requested : CellState
    data class Queued(val position: Int?, val etaS: Int?) : CellState
    data class Building(val step: String, val progress: Double, val etaS: Int?) : CellState
    /** Ready on the server, waiting for Wi-Fi (or the user's yes to mobile data, [MapCells.approveMobile]). */
    data class WaitingForWifi(val bytes: Long?) : CellState
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : CellState {
        val percent: Int get() = if (totalBytes <= 0) 0 else (doneBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
    }
    /** Joining the downloaded cell into the map. */
    data object Installing : CellState
    /** Downloaded during a tour ([MapCells.holdUpdates]): joined into the map after the tour / back in Plan. */
    data object Downloaded : CellState
    data class Ready(val version: String) : CellState
    /** No connection / server not reachable: retried by itself. [message] is plain language for the card. */
    data class Offline(val message: String) : CellState
    /** [retryable]: asking again later may work (server failed to build, damaged download); false: e.g. app too old. */
    data class Failed(val message: String, val retryable: Boolean = true) : CellState
}

/** Short plain-language line for a state ("building – base 40 %", "downloading 60 %") – debug list, card rows. */
fun CellState.label(): String = when (this) {
    CellState.None -> "not on the phone"
    CellState.Requested -> "waiting"
    is CellState.Queued -> "waiting" + (etaS?.let { " – about ${(it + 59) / 60} min" } ?: "")
    is CellState.Building -> "being prepared – ${(progress * 100).toInt()} %" + (etaS?.let { ", about ${(it + 59) / 60} min" } ?: "")
    is CellState.WaitingForWifi -> "waits for Wi-Fi" + (bytes?.let { " (${it / 1_048_576} MB)" } ?: "")
    is CellState.Downloading -> if (totalBytes <= 0) "downloading" else "downloading $percent %"
    CellState.Installing -> "almost ready"
    CellState.Downloaded -> "on the phone – shows after the tour"
    is CellState.Ready -> "ready ($version)"
    is CellState.Offline -> message
    is CellState.Failed -> message
}

/** Network as the download rules see it (Android: [AndroidNetworkStatus]). */
interface NetworkStatus {
    fun connected(): Boolean
    /** Wi-Fi / Ethernet / unmetered: downloads without asking. */
    fun unmetered(): Boolean
}

/**
 * The app side of map cells (docs/design/map-cells.md §9, §19): requests cells from the doorman, polls their status
 * while the app is in the foreground, downloads ready cells (`.part` + Range resume + sha256), joins them into the
 * [CellStore] and tells the map to reload ([generation]). Requests are kept in `cells/requests.json` and continue on
 * the next app start (no background polling, map-cells.md §8).
 *
 * Mobile data rule (owner 2026-10-21): everything loads on mobile data by default; Settings → "Maps only on Wi-Fi"
 * ([wifiOnly]) makes cells wait as [CellState.WaitingForWifi] (or until [approveMobile]). Newer versions of installed
 * cells are not downloaded while [holdUpdates] (a tour runs: no map switch under the user's feet).
 *
 * Thread model: one worker coroutine in [scope] does all network and disk work; public calls only change the queue
 * and wake it. Never throws to the caller; every failure becomes a plain [CellState] and a Problems entry.
 */
class MapCells(
    val store: CellStore,
    /** null when the build has no map server key: requests then fail with a plain reason. */
    private val api: CellApi?,
    private val network: NetworkStatus,
    private val scope: CoroutineScope,
    /** Version of this app ("0.8.0"), for the manifest's min_app. */
    private val appVersion: String,
    /** Seconds the server asks to wait → milliseconds to wait (tests shorten it). */
    private val pollDelayMs: (Int) -> Long = { it * 1000L },
    /** Polling while the app is not on screen and a tour runs (tests shorten it). */
    private val backgroundPollMs: Long = BACKGROUND_POLL_MS,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Generation churn (field bug 2026-10-22: 40 store generations for one long route): cells finished within this
     * window are joined into the map in ONE generation – while other cells are still being built or downloaded, the
     * worker waits up to this long after the last finished one (at most [maxBatchMs] after the first).
     */
    private val batchMs: Long = BATCH_MS,
    private val maxBatchMs: Long = MAX_BATCH_MS,
    /** A point request younger than this is never dropped (the trigger just asked; the card may not show it yet). */
    private val freshMs: Long = FRESH_MS,
) : CellSource {
    // ------------------------------------------------------------------------------------------- public state

    private val _states = MutableStateFlow<Map<CellId, CellState>>(emptyMap())
    /** State of every cell the app knows (installed, asked for, failed). Cells not in the map are [CellState.None]. */
    val states: StateFlow<Map<CellId, CellState>> = _states

    private val _installed = MutableStateFlow<List<CellStore.InstalledCell>>(emptyList())
    /** Installed cells (id, version, size). */
    val installed: StateFlow<List<CellStore.InstalledCell>> = _installed

    private val _generation = MutableStateFlow(0)
    /** Store generation; changes when the map files changed → reload the map style (and the search index). */
    val generation: StateFlow<Int> = _generation

    /** Settings → "Wi-Fi only" (F-OFF-03): no cell on mobile data, not even the own one. */
    @Volatile var wifiOnly: Boolean = false
        set(v) { field = v; wake() }

    /**
     * True while a tour runs (Navigate / recording service): cells still download, but are joined into the map only
     * when this goes false (after the tour / back in Plan) – the map never switches under the user's feet (owner).
     */
    @Volatile var holdUpdates: Boolean = false
        set(v) {
            val was = field; field = v
            if (was && !v) { scope.launch { work.withLock { joinHeld() } }; wake() }
        }

    /**
     * The recording service runs a tour: requests are sent and polled also while the app is not on screen (T5 in the
     * background, owner 2026-10-22) – at most every [BACKGROUND_POLL_MS].
     */
    @Volatile var tourInBackground: Boolean = false
        set(v) { field = v; if (v) wake() }

    /** MapLoadService runs (owner 2026-10-23): areas being loaded keep going with the screen off / app in the background. */
    @Volatile var loadInBackground: Boolean = false
        set(v) { field = v; if (v) wake() }

    /**
     * The user drags the map or the sheet: finished cells are not joined now (a generation switch reloads the style);
     * they are joined as soon as this goes false.
     */
    @Volatile var userBusy: Boolean = false
        set(v) { val was = field; field = v; if (was && !v) wake() }

    /**
     * What the app knows about the user right now, asked right before each download (owner 2026-10-22: never a stale or
     * unexpected download). Null (no screen, e.g. only the recording service runs): point requests stay valid.
     */
    interface Relevance {
        /** Cells the user waits for right now (the load modal / the open map card) – always valid. */
        fun waiting(): Set<CellId>
        /** A POINT / NEAR request for [c] still belongs to a planned point, the search mark, the position or a tour. */
        fun stillNear(c: CellId): Boolean
    }
    @Volatile var relevance: Relevance? = null

    /**
     * A screen's state is gone: [r] is removed only when it is still the current one. Two screens (each its own
     * ViewModel) can overlap at start (field 0.10.1) – the older one, cleared last, must not remove the newer one's.
     */
    @Synchronized fun releaseRelevance(r: Relevance) { if (relevance === r) relevance = null }

    fun state(id: CellId): CellState = _states.value[id] ?: CellState.None

    /** False when this build has no map server key: the UI says "Map downloads not available in this build". */
    override val downloadsAvailable: Boolean get() = api != null

    /** Folder the map, POI tap and search read from (the store), or null = the data folder as before cells. */
    fun mapDir(): File? = store.mapDir()

    // ------------------------------------------------------------------------------------------- cells for places

    /** Cell of a point (half-open owner rule). */
    fun cellFor(p: GeoPoint): CellId? = CellId.at(p)
    /** Own cell + close neighbours within [radiusM] (T1/T3, Q1). Own cell first. */
    override fun cellsAround(p: GeoPoint, radiusM: Double): List<CellId> = CellId.near(p, radiusM)
    fun cellsFor(south: Double, west: Double, north: Double, east: Double): List<CellId> = CellId.inBbox(south, west, north, east)
    /** Corridor cells of a route (T4, F-OFF-02). */
    override fun cellsAlong(line: List<GeoPoint>, corridorM: Double): List<CellId> = CellId.alongLine(line, corridorM)

    /** True when the detailed map covers [p]: an installed cell, or the region package in the data folder. */
    override fun hasDetailedMap(p: GeoPoint): Boolean {
        val c = CellId.at(p) ?: return false
        if (_installed.value.any { it.id == c }) return true
        return regionBounds()?.let { b -> p.lon >= b[0] && p.lat >= b[1] && p.lon <= b[2] && p.lat <= b[3] } ?: false
    }

    /**
     * Areas with the detailed map as [west, south, east, north] boxes: every installed cell and the region package
     * (the style hides the overview there from zoom 10, map/Overview.kt). Never throws.
     */
    fun detailedAreas(): List<DoubleArray> = try {
        _installed.value.map { it.id.bbox } + listOfNotNull(regionBounds())
    } catch (e: Exception) {
        Problems.warn("cells", "Detailed map areas unknown; the overview may show through at the edges", e); emptyList()
    }

    /** Of [cells]: those installed on the phone (after-routing check for newer versions). */
    override fun installedAmong(cells: List<CellId>): List<CellId> {
        val inst = _installed.value.map { it.id }.toSet()
        return cells.filter { it in inst }
    }

    /** Of [cells]: those neither installed nor already asked for (what a trigger should request). */
    override fun missing(cells: List<CellId>): List<CellId> {
        val inst = _installed.value.map { it.id }.toSet()
        return cells.filter { it !in inst && !queued(it) && !isFinished(it) }
    }

    // ------------------------------------------------------------------------------------------- actions

    /**
     * Which of [cells] the map server has built already (owner 2026-10-24: opening the app loads the map for here
     * silently when it is ready on the server, and asks only when it must be built first). Only reads the status –
     * starts no build. Null when it cannot tell (no key, offline, server error). Blocking: call off the main thread.
     */
    fun builtOnServer(cells: List<CellId>): Set<CellId>? {
        val a = api ?: return null
        if (cells.isEmpty()) return emptySet()
        if (!network.connected()) return null
        return try {
            val answer = cells.chunked(MAX_PER_CALL).map { a.status(it.map { c -> c.id }) }
            val ready = answer.flatMap { it.cells }.filter { it.state == "ready" }.map { it.id }.toSet()
            cells.filter { it.id in ready }.toSet()
        } catch (e: Exception) {
            Problems.info("cells", "Map server status not available; asking the user instead", e); null
        }
    }

    /**
     * Asks for [cells]. Already installed (except for [CellReason.REFRESH]) or already queued cells are skipped.
     * [ownPoint]: the mobile-data rule's "own cell" (loads on mobile data without asking). Returns the cells added.
     */
    override fun request(cells: List<CellId>, reason: CellReason, ownPoint: Boolean): List<CellId> {
        val inst = _installed.value.map { it.id }.toSet()
        val added = ArrayList<CellId>()
        synchronized(queue) {
            for (c in cells.distinct()) {
                if (c in queue) {
                    val p = queue[c]!!
                    // asked again (a point there, the corridor of the current plan): it belongs to what asked now – a
                    // corridor request asked for by a point is no longer dropped with the corridor
                    queue[c] = p.copy(ownPoint = p.ownPoint || ownPoint, confirmed = true,
                        reason = if (p.reason == CellReason.CORRIDOR && (reason == CellReason.POINT || reason == CellReason.NEAR)) reason else p.reason)
                    continue
                }
                if (c in inst && reason != CellReason.REFRESH && reason != CellReason.UPDATE) continue
                if (isFinished(c) && reason != CellReason.REFRESH && reason != CellReason.UPDATE) continue
                queue[c] = Pending(c, reason, ownPoint, now(), approvedMobile = false)
                added += c
            }
            saveQueue()
        }
        if (added.isNotEmpty()) {
            Problems.trace("cells", "Requested ${added.size} region(s) (${reason.name.lowercase()}): ${added.joinToString { it.id }}")
            // an UPDATE replaces an installed (outdated) cell: show it as loading, not as ready
            update { m -> added.forEach { if (m[it] !is CellState.Ready || reason == CellReason.UPDATE) m[it] = CellState.Requested } }
            sent.removeAll(added.toSet())
            wake()
        }
        return added
    }

    /** The user said yes to mobile data for these cells (the card's question). */
    fun approveMobile(cells: Collection<CellId>) {
        synchronized(queue) { cells.forEach { c -> queue[c]?.let { queue[c] = it.copy(approvedMobile = true) } }; saveQueue() }
        wake()
    }

    /** Cells waiting only for Wi-Fi / the user's yes, with their size (for "3 more cells, 85 MB – now or on Wi-Fi?"). */
    fun waitingForMobileApproval(): List<Pair<CellId, Long?>> =
        _states.value.mapNotNull { (c, s) -> (s as? CellState.WaitingForWifi)?.let { c to it.bytes } }

    /**
     * T4 belongs to the current plan (field bug 2026-10-22: 16 corridor regions of a long route planned once kept
     * downloading after every app start): [keep] = the corridor of the current plan's route, empty when the plan has no
     * route (cleared, long-route question, Cancel, not routed, routing failed). Corridor requests outside it are dropped
     * (a running download stops; installed cells stay); those inside belong to the plan again (after a restart they wait
     * for this). Returns the dropped cells.
     */
    override fun keepCorridor(keep: Collection<CellId>): List<CellId> {
        val k = keep.toSet()
        planCorridor = k
        val drop = ArrayList<CellId>()
        synchronized(queue) {
            for (p in queue.values.toList()) if (p.reason == CellReason.CORRIDOR) {
                if (p.id in k) { if (!p.confirmed) queue[p.id] = p.copy(confirmed = true) } else { queue.remove(p.id); drop += p.id }
            }
            saveQueue()
        }
        if (drop.isNotEmpty()) {
            update { m -> drop.forEach { c -> if (m[c] !is CellState.Ready) m.remove(c) } }
            Problems.trace("cells", "Dropped ${drop.size} corridor regions – plan changed: ${drop.joinToString { it.id }}")
        }
        if (k.isNotEmpty()) wake()
        return drop
    }

    /** What still waits to download (Settings → Offline maps): regions in the queue, bytes as far as the server told. */
    data class Waiting(val regions: Int, val knownBytes: Long, val unknownSize: Int)

    /** Size of [c] as the server told it, null = not known yet. */
    fun knownSize(c: CellId): Long? = sizes[c]

    fun waiting(): Waiting {
        val ids = synchronized(queue) { queue.keys.toList() }
        val s = ids.map { c -> sizes[c] ?: (state(c) as? CellState.WaitingForWifi)?.bytes }
        return Waiting(ids.size, s.filterNotNull().sum(), s.count { it == null })
    }

    /** Settings → Offline maps → "Cancel all": the whole queue goes (installed maps stay). Returns the dropped cells. */
    fun cancelAll(): List<CellId> {
        val all = synchronized(queue) { queue.keys.toList() }
        if (all.isEmpty()) return all
        cancel(all)
        Problems.trace("cells", "Dropped ${all.size} region request(s) – Cancel all in Settings: ${all.joinToString { it.id }}")
        return all
    }

    /** Drops cells from the queue (the user closed the card / does not want them). Installed cells stay. */
    fun cancel(cells: Collection<CellId>) {
        synchronized(queue) { cells.forEach { queue.remove(it) }; saveQueue() }
        update { m -> cells.forEach { c -> if (m[c] !is CellState.Ready) m.remove(c) } }
    }

    /**
     * Deletes installed cells (tiles, POIs, addresses) – Settings → Offline maps (one area, "Clean up", "Clear all").
     * Their requests go at once; the files leave the map through the normal store generation path on the worker
     * ([removeIfDue]): batched (several calls in one generation), never while the user drags the map, and never while
     * [holdUpdates] (a tour runs – the map does not switch under the user's feet; removed after the tour).
     */
    fun delete(cells: Collection<CellId>, why: String = "deleted in Settings") {
        if (cells.isEmpty()) return
        cancel(cells)
        synchronized(finished) { cells.forEach { finished.remove(it) } }
        synchronized(toRemove) { toRemove += cells }
        Problems.trace("cells", "Removing ${cells.size} region(s) – $why: ${cells.joinToString { it.id }}" +
            (if (holdUpdates) " (after the tour)" else ""))
        wake()
    }

    /**
     * Settings → Offline maps → "Clear all" (owner 2026-10-22): every request goes and every downloaded area is removed
     * (the world overview in the APK stays); areas load again when needed. Returns the removed areas.
     */
    fun clearAll(): List<CellId> {
        val all = _installed.value.map { it.id }
        cancel(synchronized(queue) { queue.keys.toList() } + _states.value.keys)
        delete(all, "Clear all in Settings")
        if (all.isEmpty()) Problems.trace("cells", "Clear all in Settings: no downloaded areas")
        return all
    }

    /** Installed areas waiting to be removed ([delete]) – Settings shows "removed after the tour". */
    fun removing(): Set<CellId> = synchronized(toRemove) { toRemove.toSet() }

    /** Connectivity changed (Wi-Fi came back …): check at once instead of waiting for the next round. */
    fun networkChanged() = wake()

    /** The app came to the foreground (true) or left it (false). Polling runs in the foreground, on a tour, or while MapLoadService runs. */
    fun setForeground(on: Boolean) {
        foreground = on
        // nobody drags a map that is not on screen: a gesture whose "camera idle" never came (app left while the camera
        // moved) must not hold back joins / removals until the app is opened again (phone check 2026-10-23)
        if (!on && userBusy) { userBusy = false; wake() }
        if (on) wake()
    }

    /** The style loaded generation [number] fine: the previous generation can go (R-16). */
    fun confirmLoaded(number: Int) = store.confirmLoaded(number)

    /** The style could not load generation [number]: go back to the previous one. True = reload the style. */
    fun rollback(number: Int): Boolean = store.rollback(number).also { if (it) refreshInstalled() }

    /** Remembers that the user planned / navigated in these cells (T6: refresh only cells used in 6 months). */
    override fun markUsed(cells: Collection<CellId>) {
        val inst = _installed.value.map { it.id }.toSet()
        val used = cells.filter { it in inst }
        if (used.isEmpty()) return
        synchronized(usage) { used.forEach { usage[it] = now() }; saveUsage() }
    }

    /** Installed cells of an older content schema than this app's style needs ([CellApi.CELL_SCHEMA]). */
    override fun outdated(): List<CellId> =
        _installed.value.filter { CellApi.schemaOf(it.version) < CellApi.CELL_SCHEMA }.map { it.id }

    /**
     * Installed cells due for a refresh (T6): used in the last 6 months and installed more than [ageDays] days ago.
     * The caller requests them with [CellReason.REFRESH] (they download only when newer).
     */
    override fun refreshDue(ageDays: Int): List<CellId> {
        val t = now()
        return _installed.value.filter { c ->
            val used = synchronized(usage) { usage[c.id] } ?: c.installedAt
            t - used < 183L * DAY && t - c.installedAt > ageDays * DAY
        }.map { it.id }
    }

    /**
     * Starts the worker: finishes downloads a crash interrupted, re-joins the region package if it changed, loads the
     * saved queue. Call once (app start); safe to call again.
     */
    fun start() {
        if (worker?.isActive == true) return
        loadQueue(); loadUsage()
        refreshInstalled()
        worker = scope.launch {
            work.withLock { recover() }
            loop()
        }
    }

    /** The region package in the data folder changed (import): join it again with the cells. */
    fun regionChanged() {
        scope.launch {
            work.withLock {
                try {
                    if (store.regionChanged()) publish(store.apply())
                } catch (e: Exception) { Problems.error("cells", "New map package could not be joined with the downloaded areas", e) }
            }
        }
    }

    // ------------------------------------------------------------------------------------------- internals

    /**
     * [confirmed] false: a CORRIDOR request restored at start – it waits (not sent, not downloaded) until the restored
     * plan's route says it still needs it ([keepCorridor]).
     */
    private data class Pending(val id: CellId, val reason: CellReason, val ownPoint: Boolean, val at: Long,
                               val approvedMobile: Boolean, val confirmed: Boolean = true)

    /** Corridor of the current plan's route as last told ([keepCorridor]); null = not told in this run. */
    @Volatile private var planCorridor: Set<CellId>? = null
    /** Sizes the server told (status answers, manifests) – "about X MB" in Settings, download traces. */
    private val sizes = java.util.concurrent.ConcurrentHashMap<CellId, Long>()
    /** Downloaded and verified, waiting to be joined with others in one generation ([joinIfDue]). */
    private val finished = LinkedHashMap<CellId, CellStore.Download>()
    /** Installed areas to remove in the next generation ([delete], [removeIfDue]). */
    private val toRemove = LinkedHashSet<CellId>()
    private var firstFinishedAt = 0L
    private var lastFinishedAt = 0L

    private fun isFinished(c: CellId) = synchronized(finished) { c in finished }

    private val queue = LinkedHashMap<CellId, Pending>()
    private val usage = HashMap<CellId, Long>()
    /** Cells whose request was sent in this run (a request is sent once per run, then only status is polled). */
    private val sent = java.util.Collections.synchronizedSet(HashSet<CellId>())
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private val work = Mutex()
    private var worker: Job? = null
    @Volatile private var foreground = true
    private var backoff = 0
    /** Cells whose download was damaged once in this run (second time → failed). */
    private val damaged = HashSet<CellId>()

    private val queueFile get() = File(store.root, "requests.json")
    private val usageFile get() = File(store.root, "usage.json")

    private fun wake() { wakeup.trySend(Unit) }

    private fun update(block: (MutableMap<CellId, CellState>) -> Unit) {
        synchronized(_states) { val m = _states.value.toMutableMap(); block(m); _states.value = m }
    }

    private fun set(c: CellId, s: CellState) = update { it[c] = s }

    /**
     * Installed list → generation → states, in this order: whoever sees a cell "ready" also sees the map generation that
     * contains it (CI flake 2026-10-21: Ready was visible while the generation still had the old number).
     */
    private fun refreshInstalled(gen: Int? = null) {
        val inst = store.installed()
        _installed.value = inst
        _generation.value = gen ?: store.current()?.number ?: 0
        update { m ->
            m.entries.removeAll { (c, s) -> s is CellState.Ready && inst.none { it.id == c } }
            inst.forEach { if (it.id !in queue || m[it.id] == null) m[it.id] = CellState.Ready(it.version) }
        }
    }

    private fun publish(gen: CellStore.Generation) {
        refreshInstalled(gen.number)
        _generation.value = gen.number
    }

    private fun recover() {
        try {
            store.cleanTemp()
            val cur = store.current()
            val pending = store.pendingDownloads()
            val toJoin = pending.filter { d -> cur?.cell(d.manifest.cell)?.version != d.manifest.version }
            pending.filter { it !in toJoin }.forEach { store.deleteDownloads(it.manifest.cell) }
            if (toJoin.isNotEmpty()) {
                publish(store.apply(add = toJoin))
                toJoin.forEach { store.deleteDownloads(it.manifest.cell); dequeue(it.manifest.cell) }
            } else if (store.regionChanged()) publish(store.apply())
        } catch (e: Exception) {
            Problems.error("cells", "Downloaded map areas could not be joined after a restart; they load again", e)
        }
    }

    /** Joins the cells downloaded during a tour ([CellState.Downloaded]) – called when [holdUpdates] goes false. */
    private fun joinHeld() {
        try {
            val cur = store.current()
            val pending = store.pendingDownloads()
            val toJoin = pending.filter { d -> cur?.cell(d.manifest.cell)?.version != d.manifest.version }
            pending.filter { it !in toJoin }.forEach { store.deleteDownloads(it.manifest.cell) }
            if (toJoin.isEmpty()) return
            publish(store.apply(add = toJoin))
            synchronized(finished) { toJoin.forEach { finished.remove(it.manifest.cell) } }
            toJoin.forEach { store.deleteDownloads(it.manifest.cell); set(it.manifest.cell, CellState.Ready(it.manifest.version)) }
            Problems.info("cells", "Map cells downloaded during the tour joined: ${toJoin.joinToString { it.manifest.cell.id }}")
        } catch (e: Exception) {
            Problems.error("cells", "Map areas downloaded during the tour could not be joined; they load again", e)
        }
    }

    private fun queued(c: CellId): Boolean = synchronized(queue) { c in queue }

    /** The user cancelled a cell while it downloaded ([cancel]). */
    private class Cancelled : RuntimeException("cancelled by the user")

    private fun dequeue(c: CellId) { synchronized(queue) { queue.remove(c); saveQueue() } }

    private suspend fun loop() {
        while (true) {
            val waitMs = try { work.withLock { step() } } catch (e: Exception) {
                Problems.error("cells", "Map download worker failed; retrying", e); 60_000L
            }
            withTimeoutOrNull(waitMs) { wakeup.receive() }
        }
    }

    /**
     * One round: drop requests no longer valid, join finished cells when due, send requests, poll status, download one
     * ready cell. Returns how long to wait before the next.
     */
    private fun step(): Long {
        prune()
        val removeWait = removeIfDue()
        val joinWait = joinIfDue()
        val w = stepQueue()
        return listOfNotNull(w, joinWait, removeWait).min()
    }

    /**
     * Applies the areas waiting in [toRemove] in one generation – not while [userBusy] (rechecked soon), not while
     * [holdUpdates] (the setter wakes the worker after the tour). Null = nothing to do now.
     */
    private fun removeIfDue(): Long? {
        val ids = synchronized(toRemove) { toRemove.toSet() }
        if (ids.isEmpty() || holdUpdates) return null
        if (userBusy) return BUSY_RECHECK_MS
        try {
            val gen = store.apply(remove = ids)
            ids.forEach { store.deleteDownloads(it) }
            synchronized(toRemove) { toRemove.removeAll(ids) }
            publish(gen)
            update { m -> ids.forEach { m.remove(it) } }
            Problems.trace("cells", "Removed ${ids.size} region(s) from the map in one update (generation ${gen.number}): " +
                ids.joinToString { it.id })
        } catch (e: Exception) {
            synchronized(toRemove) { toRemove.removeAll(ids) }
            Problems.error("cells", "Map areas could not be deleted", e)
        }
        return null
    }

    /**
     * Why [p] is no longer wanted (null = still wanted) – checked right before each round of sending and downloading
     * (owner 2026-10-22: never a stale or unexpected download).
     */
    private fun whyInvalid(p: Pending, rel: Relevance?, inst: Set<CellId>, t: Long): String? = when (p.reason) {
        CellReason.CORRIDOR -> if (planCorridor?.let { p.id !in it } == true) "no longer on the plan's route" else null
        CellReason.POINT, CellReason.NEAR ->
            if (rel != null && t - p.at >= freshMs && !rel.stillNear(p.id)) "no longer near a planned point, the search or the position" else null
        CellReason.REFRESH, CellReason.UPDATE -> if (p.id !in inst) "area no longer on the phone" else null
    }

    /** Drops requests no longer wanted ([whyInvalid]); the user's waited-for cells always stay. */
    private fun prune() {
        val pend = synchronized(queue) { queue.values.filter { it.confirmed } }
        if (pend.isEmpty()) return
        val rel = relevance
        val (waiting, near) = try {
            (rel?.waiting() ?: emptySet()) to rel
        } catch (e: Exception) {
            Problems.warn("cells", "Map request check failed; requests kept", e); return
        }
        val inst = _installed.value.map { it.id }.toSet()
        val t = now()
        val drop = pend.mapNotNull { p ->
            if (p.id in waiting) null else try { whyInvalid(p, near, inst, t)?.let { p to it } } catch (e: Exception) {
                Problems.warn("cells", "Map request check for ${p.id} failed; kept", e); null
            }
        }
        if (drop.isEmpty()) return
        synchronized(queue) { drop.forEach { queue.remove(it.first.id) }; saveQueue() }
        update { m -> drop.forEach { (p, _) -> if (m[p.id] !is CellState.Ready) m.remove(p.id) } }
        drop.groupBy { it.second }.forEach { (why, list) ->
            Problems.trace("cells", "Dropped ${list.size} region request(s) before download – $why: " +
                list.joinToString { "${it.first.id} (${it.first.reason.name.lowercase()})" })
        }
    }

    /**
     * Finished downloads are joined into the map together: when nothing more is about to download and either nothing is
     * still being built or [batchMs] passed since the last one finished (at most [maxBatchMs] after the first), never
     * while [userBusy]. Null = nothing waits to be joined; else ms until it should be checked again.
     */
    private fun joinIfDue(): Long? {
        val done = synchronized(finished) { finished.values.toList() }
        if (done.isEmpty()) return null
        if (holdUpdates) {
            // a tour started meanwhile: they stay on disk and are joined after the tour ([joinHeld])
            synchronized(finished) { finished.clear() }
            done.forEach { set(it.manifest.cell, CellState.Downloaded) }
            return null
        }
        val t = now()
        val pend = synchronized(queue) { queue.values.filter { it.confirmed } }
        val more = pend.any { p -> state(p.id) is CellState.Downloading && allowed(p) }
        val building = pend.any { p -> state(p.id).let { s -> s is CellState.Queued || s is CellState.Building || s == CellState.Requested } }
        val quiet = t - lastFinishedAt
        val due = t - firstFinishedAt >= maxBatchMs || (!more && (!building || quiet >= batchMs))
        if (!due) return if (more) 0L else (batchMs - quiet).coerceAtLeast(1L)
        if (userBusy) return BUSY_RECHECK_MS
        join(done)
        return null
    }

    private fun join(done: List<CellStore.Download>) {
        val ids = done.map { it.manifest.cell }
        try {
            ids.forEach { set(it, CellState.Installing) }
            val gen = store.apply(add = done)
            ids.forEach { store.deleteDownloads(it) }
            synchronized(finished) { ids.forEach { finished.remove(it) } }
            publish(gen)
            done.forEach { set(it.manifest.cell, CellState.Ready(it.manifest.version)) }
            Problems.trace("cells", "Joined ${ids.size} region(s) into the map in one update (generation ${gen.number}): ${ids.joinToString { it.id }}")
        } catch (e: Exception) {
            synchronized(finished) { ids.forEach { finished.remove(it) } }
            Problems.error("cells", "Map areas ${ids.joinToString { it.id }} could not be joined into the map", e)
            ids.forEach { set(it, CellState.Failed((e as? CellStore.StoreException)?.message ?: "The map could not be saved.")) }
        }
    }

    private fun stepQueue(): Long {
        val pending = synchronized(queue) { queue.values.filter { it.confirmed } }
        if (pending.isEmpty()) return IDLE_MS
        if (api == null) {
            pending.forEach { set(it.id, CellState.Failed("This app version cannot download maps. Please install the latest version.", retryable = false)) }
            return IDLE_MS
        }
        if (!network.connected()) {
            pending.forEach { set(it.id, CellState.Offline("No connection – the detailed map for this area will load when you are online.")) }
            return 60_000L
        }
        try {
            // 1. ready cells we may download: own point first
            val ready = pending.filter { state(it.id).let { s -> s is CellState.WaitingForWifi || s is CellState.Downloading || s == READY_ON_SERVER } }
            val inst = _installed.value.map { it.id }.toSet()
            val next = ready.filter { allowed(it) }.sortedWith(compareByDescending<Pending> { it.ownPoint }.thenBy { it.reason.ordinal }.thenBy { it.at }).firstOrNull()
            if (next != null) { downloadAndInstall(next); backoff = 0; return 0 }
            if (!foreground && !tourInBackground && !loadInBackground) return IDLE_MS
            // 2. send requests not sent in this run (grouped by reason), else poll status
            val unsent = pending.filter { it.id !in sent }
            val answer = if (unsent.isNotEmpty()) {
                val byReason = unsent.groupBy { it.reason }
                var last: CellApi.StatusAnswer? = null
                for ((reason, list) in byReason) for (chunk in list.chunked(MAX_PER_CALL)) {
                    last = merge(last, api.request(chunk.map { it.id.id }, reason.api))
                    sent += chunk.map { it.id }
                }
                last!!
            } else {
                pending.chunked(MAX_PER_CALL).map { api.status(it.map { p -> p.id.id }) }.reduce { a, b -> merge(a, b) }
            }
            backoff = 0
            apply(answer, pending)
            val anyReadyAllowed = pending.any { state(it.id) == READY_ON_SERVER && allowed(it) }
            if (anyReadyAllowed) return 0
            val stillBuilding = pending.any { state(it.id).let { s -> s is CellState.Queued || s is CellState.Building || s is CellState.Requested } }
            return if (!stillBuilding) IDLE_MS
                else if (foreground || loadInBackground) pollDelayMs(answer.pollS) else maxOf(pollDelayMs(answer.pollS), backgroundPollMs)
        } catch (e: CellApi.ApiException) {
            return failed(e, pending)
        }
    }

    private fun merge(a: CellApi.StatusAnswer?, b: CellApi.StatusAnswer) =
        if (a == null) b else CellApi.StatusAnswer(a.cells + b.cells, minOf(a.pollS, b.pollS))

    /** Server answer → states. */
    private fun apply(answer: CellApi.StatusAnswer, pending: List<Pending>) {
        val inst = _installed.value.associateBy { it.id }
        for (s in answer.cells) {
            val c = CellId.parse(s.id) ?: continue
            val p = pending.firstOrNull { it.id == c } ?: continue
            if (!queued(c)) continue // cancelled while the server answered (modal → Cancel): no state for it any more
            s.size?.let { sizes[c] = it }
            when (s.state) {
                "ready" -> {
                    val have = inst[c]?.version
                    if (have != null && (s.version == null || s.version == have) && s.updating) {
                        // a newer version is being built: keep asking (state stays "ready" on the phone)
                        set(c, CellState.Ready(have))
                    } else if (have != null && (s.version == null || s.version == have)) {
                        // nothing newer (refresh) – done
                        dequeue(c); set(c, CellState.Ready(have))
                    } else if (allowed(p)) set(c, READY_ON_SERVER)
                    else set(c, CellState.WaitingForWifi(s.size))
                }
                "queued" -> set(c, CellState.Queued(s.position, s.etaS))
                "building" -> set(c, CellState.Building(s.step ?: "starting", s.progress ?: 0.0, s.etaS))
                "failed" -> {
                    val msg = "This map area could not be prepared. Routes there are straight lines for now."
                    Problems.warn("cells", "Map cell $c failed on the server: ${s.reason}")
                    set(c, CellState.Failed(msg))
                    // dropped from the queue: the next trigger for this place asks again (the server retries up to 3×)
                    synchronized(queue) { queue.remove(c) }
                }
                else -> { sent -= c; set(c, CellState.Requested) } // "none": server forgot it → request again
            }
        }
        synchronized(queue) { saveQueue() }
    }

    /** Mobile data rule (owner 2026-10-21): mobile data by default; "Maps only on Wi-Fi" waits unless the user said yes. */
    private fun allowed(p: Pending): Boolean = network.unmetered() || !wifiOnly || p.approvedMobile

    private fun failed(e: CellApi.ApiException, pending: List<Pending>): Long {
        val delays = longArrayOf(60_000, 120_000, 300_000, 600_000)
        val wait = maxOf((e.retryAfterS ?: 0) * 1000L, delays[minOf(backoff, delays.size - 1)])
        backoff++
        when (e.failure) {
            CellApi.Failure.REFUSED -> {
                Problems.error("cells", "Map server refused the app key", e)
                pending.forEach { set(it.id, CellState.Failed(com.onpath.app.debug.UserText.of(e, "Map server refused the request – update the app."), retryable = false)) }
                return IDLE_MS
            }
            CellApi.Failure.OFFLINE, CellApi.Failure.UNREACHABLE, CellApi.Failure.SERVER, CellApi.Failure.BUSY -> {
                Problems.info("cells", "Map server not reachable (${e.failure}); retrying", e)
                pending.forEach { if (state(it.id) !is CellState.Downloading) set(it.id, CellState.Offline(com.onpath.app.debug.UserText.of(e, "Map server not reachable – trying again."))) }
            }
            else -> {
                Problems.warn("cells", "Map server answer not usable; retrying", e)
                pending.forEach { set(it.id, CellState.Offline(com.onpath.app.debug.UserText.of(e, "Map server not reachable – trying again."))) }
            }
        }
        return wait
    }

    private fun downloadAndInstall(p: Pending) {
        val api = api ?: return
        val c = p.id
        try {
            val m = api.manifest(c)
            if (m.cell != c) throw IllegalStateException("manifest is for ${m.cell}")
            if (m.format != CellApi.FORMAT) { set(c, CellState.Failed("This area is only available for another app version.", false)); dequeue(c); return }
            if (m.minApp != null && com.onpath.app.update.AppUpdater.isNewer(m.minApp, appVersion)) {
                set(c, CellState.Failed("Update OnPath to load this area (needs ${m.minApp}).", false)); dequeue(c); return
            }
            store.current()?.cell(c)?.takeIf { it.version == m.version }?.let { dequeue(c); set(c, CellState.Ready(it.version)); return }
            val free = store.root.apply { mkdirs() }.usableSpace
            val need = m.totalSize * 2 + CellStore.MARGIN // download + its tiles in the new store generation
            if (free in 0 until need) {
                set(c, CellState.Failed("Not enough space (needs ${need / 1_048_576} MB). Delete old areas in Settings → Offline maps."))
                Problems.warn("cells", "Not enough space for map cell $c (${need / 1_048_576} MB needed)")
                dequeue(c); return
            }
            sizes[c] = m.totalSize
            Problems.trace("cells", "Download start: $c (${p.reason.name.lowercase()}${if (p.ownPoint) ", own" else ""}), ${mbOf(m.totalSize)}" +
                (if (network.unmetered()) "" else " on mobile data"))
            val part = store.partDir(m)
            part.mkdirs()
            var before = 0L
            set(c, CellState.Downloading(0, m.totalSize))
            for (f in m.files) {
                val dest = File(part, f.name)
                if (!(dest.isFile && dest.length() == f.size && CellApi.sha256(dest) == f.sha256)) {
                    dest.delete()
                    val tmp = File(part, f.name + ".part")
                    val base = before
                    api.download(f, tmp) { done ->
                        if (!queued(c)) throw Cancelled() // modal → Cancel: stop the download now (the part file stays)
                        set(c, CellState.Downloading(base + done, m.totalSize))
                    }
                    if (!tmp.renameTo(dest)) throw java.io.IOException("could not save ${f.name}")
                }
                before += f.size
            }
            File(part, CellStore.MANIFEST).writeText(m.json)
            val complete = store.completeDir(m)
            complete.deleteRecursively()
            if (!part.renameTo(complete)) throw java.io.IOException("could not finish the download folder")
            if (holdUpdates) {
                // a tour runs: the download stays complete on disk and is joined after the tour ([joinHeld])
                dequeue(c); damaged -= c
                set(c, CellState.Downloaded)
                Problems.info("cells", "Map cell $c downloaded during a tour; shown after the tour")
                return
            }
            // joined with others finishing soon in one generation ([joinIfDue])
            dequeue(c); damaged -= c
            set(c, CellState.Installing)
            val t = now()
            synchronized(finished) { if (finished.isEmpty()) firstFinishedAt = t; finished[c] = CellStore.Download(m, complete) }
            lastFinishedAt = t
            Problems.trace("cells", "Download finished: $c (${p.reason.name.lowercase()}), ${mbOf(m.totalSize)}")
        } catch (e: Cancelled) {
            Problems.trace("cells", "Download stopped: $c (${p.reason.name.lowercase()}) – no longer wanted")
            update { it.remove(c) }
        } catch (e: CellApi.ChecksumException) {
            if (c in damaged) {
                Problems.warn("cells", "Map cell $c damaged twice while downloading", e)
                set(c, CellState.Failed("Download damaged – please try again later.")); dequeue(c)
            } else { damaged += c; Problems.info("cells", "Map cell $c download damaged; loading again", e); set(c, READY_ON_SERVER) }
        } catch (e: CellApi.ApiException) {
            if (e.failure == CellApi.Failure.NOT_READY) { sent -= c; set(c, CellState.Requested); return }
            set(c, CellState.Offline(com.onpath.app.debug.UserText.of(e, "Download stopped – continues when connected.")))
            Problems.info("cells", "Map cell $c download stopped (${e.failure})", e)
            throw e
        } catch (e: CellStore.StoreException) {
            Problems.error("cells", "Map cell $c could not be joined into the map", e)
            set(c, CellState.Failed(com.onpath.app.debug.UserText.of(e, "The map could not be saved."))); dequeue(c)
        } catch (e: Exception) {
            Problems.error("cells", "Map cell $c download failed", e)
            set(c, CellState.Failed("Download failed. Please try again later.")); dequeue(c)
        }
    }

    // ------------------------------------------------------------------------------------------- persistence

    private fun saveQueue() = writeAtomic(queueFile, JSONObject().put("cells", JSONArray(queue.values.map { p ->
        JSONObject().put("id", p.id.id).put("reason", p.reason.name).put("own", p.ownPoint).put("at", p.at).put("mobileOk", p.approvedMobile)
    })).toString())

    private fun loadQueue() = synchronized(queue) {
        try {
            if (!queueFile.isFile) return@synchronized
            val a = JSONObject(queueFile.readText()).optJSONArray("cells") ?: JSONArray()
            val t = now()
            val stale = ArrayList<CellId>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val c = CellId.parse(o.optString("id")) ?: continue
                val r = runCatching { CellReason.valueOf(o.optString("reason")) }.getOrNull() ?: CellReason.NEAR
                val at = o.optLong("at")
                // revalidated at start (field bug 2026-10-22): a point request from yesterday – nobody waits for it;
                // a corridor waits until the restored plan's route says it still needs it ([keepCorridor])
                if ((r == CellReason.POINT || r == CellReason.NEAR) && t - at > STALE_MS) { stale += c; continue }
                queue[c] = Pending(c, r, o.optBoolean("own"), at, o.optBoolean("mobileOk"), confirmed = r != CellReason.CORRIDOR)
            }
            if (stale.isNotEmpty()) {
                saveQueue()
                Problems.trace("cells", "Dropped ${stale.size} old point region request(s) at start: ${stale.joinToString { it.id }}")
            }
            val held = queue.values.count { !it.confirmed }
            if (held > 0) Problems.trace("cells", "$held corridor region request(s) wait for the restored plan's route")
            update { m -> queue.values.filter { it.confirmed }.forEach { m[it.id] = CellState.Requested } }
        } catch (e: Exception) { Problems.warn("cells", "Saved map requests unreadable; asked again when needed", e) }
    }

    private fun saveUsage() = writeAtomic(usageFile, JSONObject(usage.mapKeys { it.key.id } as Map<*, *>).toString())

    private fun loadUsage() = synchronized(usage) {
        try {
            if (!usageFile.isFile) return@synchronized
            val o = JSONObject(usageFile.readText())
            o.keys().forEach { k -> CellId.parse(k)?.let { usage[it] = o.getLong(k) } }
        } catch (e: Exception) { Problems.warn("cells", "Map area usage list unreadable; refresh dates restart", e) }
    }

    private fun mbOf(bytes: Long) = "%.1f MB".format(java.util.Locale.ROOT, bytes / 1_048_576.0)

    private fun writeAtomic(f: File, text: String) {
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, ".${f.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw java.io.IOException("rename failed") }
        } catch (e: Exception) { Problems.warn("cells", "${f.name} not saved; map requests may be asked again", e) }
    }

    private var cachedRegion: Pair<String, DoubleArray?>? = null

    /** [west, south, east, north] of the region package's base map, null without one. */
    private fun regionBounds(): DoubleArray? {
        val base = File(store.dataDir, "base.pmtiles")
        if (!base.isFile) return null
        val key = "${base.length()}:${base.lastModified()}"
        cachedRegion?.let { if (it.first == key) return it.second }
        val b = try { PmTiles.Reader(base).use { r -> r.header.let { h -> doubleArrayOf(h.minLonE7 / 1e7, h.minLatE7 / 1e7, h.maxLonE7 / 1e7, h.maxLatE7 / 1e7) } } }
            catch (e: Exception) { Problems.warn("cells", "Region map bounds unreadable", e); null }
        cachedRegion = key to b
        return b
    }

    companion object {
        /** Ready on the server, download allowed – internal marker state (shown as 0 % downloading). */
        private val READY_ON_SERVER = CellState.Downloading(0, 0)
        private const val IDLE_MS = 15 * 60_000L
        /** Polling the build status while the app is not on screen (tour in the background). */
        const val BACKGROUND_POLL_MS = 30_000L
        private const val MAX_PER_CALL = 40
        /** Finished cells wait this long for more before they are joined in one generation. */
        const val BATCH_MS = 10_000L
        const val MAX_BATCH_MS = 60_000L
        const val FRESH_MS = 5_000L
        private const val BUSY_RECHECK_MS = 1_000L
        /** A point / near request older than this is dropped at app start (nobody waits for it any more). */
        const val STALE_MS = 24L * 3600 * 1000
        private const val DAY = 24L * 3600 * 1000
    }
}
