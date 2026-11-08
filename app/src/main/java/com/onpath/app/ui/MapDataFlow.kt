package com.onpath.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.cells.label
import com.onpath.app.data.AppSettings
import com.onpath.app.data.DataFiles
import com.onpath.app.data.RoutingDataNotice
import com.onpath.app.data.TourDb
import com.onpath.app.debug.Problems
import com.onpath.app.recording.LocationTracker
import com.onpath.app.recording.RecState
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Map cells and routing data on the phone (docs/design/map-cells.md §9, §19) – out of AppViewModel (review Arch 3, owner
 * 2026-11-01), the same code: the triggers (a point, the position, a route, a tap, a search, a tour start), the map card,
 * the loading modal, the background strip, Settings → Offline maps and the routing data loader. What it reads and changes
 * of the plan and the screens comes through [Host] (the ViewModel's own properties – no copies). The ViewModel keeps the
 * plan's answer to arriving routing data (routingDataArrived, gated), the freshness check of the route's routing data,
 * the very-long-plan deferral of new points and the demo.
 */
class MapDataFlow(
    private val app: Application,
    private val db: TourDb,
    private val scope: CoroutineScope,
    private val cellsOf: () -> com.onpath.app.cells.MapCells,
    private val host: Host,
) {
    /** The plan and the screens as map cells / routing data see them (the ViewModel's own properties). */
    interface Host {
        val mode: AppMode
        val recState: StateFlow<RecState?>
        val waypoints: List<GeoPoint>
        val route: PlannedRoute?
        val tracker: LocationTracker
        val settings: AppSettings
        val missing: List<DataFiles.Check>
        val startModeKnown: Boolean
        val searchMark: com.onpath.app.search.Place?
        /** The plan's activity – the corridor's width ([com.onpath.app.routing.OfflineCorridor]). */
        val activity: com.onpath.app.routing.Activity
        var routeError: String?
        var dataBusy: String?
        fun initialCamera(): Triple<Double, Double, Double>
        /** Loading modal → Cancel opened by a search / shared place: its ring goes. */
        fun clearSearchRing()
        /** A point's name for the loading chip (route point name, else the Points list's nearby name). */
        fun placeName(at: GeoPoint): String?
        suspend fun offlineIndex(): com.onpath.app.search.PlaceIndex
        /** Routing data changed on the phone: the next route uses a new engine. */
        fun invalidateEngine()
        /** Main thread: routing data arrived – the plan follows (the ViewModel's routingDataArrived). */
        fun routingDataArrived(names: List<String>)
        fun say(text: String, kind: NoticeKind, place: NoticePlace)
    }

    private val cells get() = cellsOf()
    private val mode get() = host.mode
    private val recState get() = host.recState
    private val waypoints get() = host.waypoints
    private val route get() = host.route
    private val tracker get() = host.tracker
    private val settings get() = host.settings
    private val startModeKnown get() = host.startModeKnown
    private val searchMark get() = host.searchMark
    private var routeError: String?
        get() = host.routeError
        set(v) { host.routeError = v }
    private var dataBusy: String?
        get() = host.dataBusy
        set(v) { host.dataBusy = v }
    private fun sayInfo(text: String, place: NoticePlace) = host.say(text, NoticeKind.INFO, place)
    private fun sayError(text: String, place: NoticePlace) = host.say(text, NoticeKind.ERROR, place)

    /** The app is on screen (Android allows starting the map loading service only then). */
    private var appVisible = true

    /** The app is on screen again: cells load in the foreground; in Plan the next usable fix checks the own area (T3). */
    fun onReturn(inPlan: Boolean) {
        appVisible = true
        cells.setForeground(true)
        if (inPlan) t3Pending = true
    }

    /** The app left the screen (map areas on their way go on through MapLoadService). */
    fun onLeave() { appVisible = false; cells.setForeground(false) }

    /** Called once from the ViewModel's last init (every property of both is initialised by then). */
    fun start() {
        // map areas start loading → MapLoadService keeps it going with the screen off / app left (owner)
        scope.launch {
            var wasLoading = false
            cells.states.collect { st ->
                val loading = st.values.any(com.onpath.app.cells.MapLoadService::loading)
                if (loading && !wasLoading && appVisible) com.onpath.app.cells.MapLoadService.ensure(app)
                wasLoading = loading
            }
        }
        // map cells (map-cells.md §9): Wi-Fi setting, cells still loading from the last run on the card, T3 on fixes
        cells.wifiOnly = settings.mapsWifiOnly
        // asked right before each download (owner): does a point request still belong to the user?
        cells.relevance = object : com.onpath.app.cells.MapCells.Relevance {
            override fun waiting(): Set<com.onpath.app.cells.CellId> =
                loadModalFor + (cellJob?.takeIf { it.why == com.onpath.app.cells.CellWhy.TAPPED }?.cells ?: emptyList())
            override fun stillNear(c: com.onpath.app.cells.CellId): Boolean {
                if (recState.value != null || mode == AppMode.NAVIGATE) return true // on tour: the map ahead (T5)
                val pts = waypoints.toList() + listOfNotNull(searchMark?.let { GeoPoint(it.lat, it.lon) },
                    tracker.location.value?.let { GeoPoint(it.latitude, it.longitude) })
                return com.onpath.app.cells.CellTriggers.nearAny(c, pts)
            }
        }.also { ownRelevance = it }
        safeCells("start") {
            val inst = cells.installed.value.map { it.id }.toSet()
            val loading = cells.states.value.filter { (c, s) -> c !in inst && s != com.onpath.app.cells.CellState.None &&
                s !is com.onpath.app.cells.CellState.Ready && s !is com.onpath.app.cells.CellState.Failed }.keys.sorted()
            if (loading.isNotEmpty() && cells.downloadsAvailable) cellJob = com.onpath.app.cells.CellJob(loading, null, com.onpath.app.cells.CellWhy.PLANNED)
        }
        scope.launch { tracker.location.filterNotNull().collect { cellsForFix(it) } }
        // a tour runs (Navigate): newer map versions wait – the map never switches under the user's feet
        scope.launch { androidx.compose.runtime.snapshotFlow { mode }.collect { cells.holdUpdates = it == AppMode.NAVIGATE } }
    }

    /** The ViewModel is cleared: its listener and its relevance go (a newer screen may have set its own already). */
    fun stop() {
        com.onpath.app.data.AppRouting.unlisten(routingListener)
        ownRelevance?.let { cells.releaseRelevance(it) }
    }

    /** This flow's [com.onpath.app.cells.MapCells.relevance] – [stop] removes only its own (two screens can exist at start). */
    private var ownRelevance: com.onpath.app.cells.MapCells.Relevance? = null

    /** Settings → Offline maps → "Clean up": what would be removed, shown until Remove / Cancel; null = no question. */
    var cleanupPreview by mutableStateOf<CleanupPreview?>(null)
    /** Settings → Offline maps: routing bytes per region for (regions, routing data version) – see [offlineMapsUi]. */
    private var routingBytesCache: Pair<Pair<List<com.onpath.app.cells.CellId>, Int>, Map<com.onpath.app.cells.CellId, Long>>? = null

    /** The loading modal waits for the map / routing data of the user's area. */
    val loadModalOpen: Boolean get() = loadModalFor.isNotEmpty() || routingModal

    /** Routing data is loading now (the plan's notice says "loading" rather than "not yet"). */
    val routingLoading: Boolean get() = routingData.loading

    /**
     * Can the route [line] be ridden without a network ("Ohne Netz ✓ / ⟳ / ⚠" in the plan's pill, owner 2026-11-03,
     * [OfflineReady]): the map regions along it and the corridor's routing parts on the phone, loading, or missing.
     * Blocking (file checks) – call off the main thread. Null for no route or when it cannot tell (reported).
     */
    fun offlineReady(line: List<GeoPoint>, activity: com.onpath.app.routing.Activity?): OfflineReady.Result? {
        if (line.size < 2) return null
        return try {
            val m = cells
            val states = m.states.value
            val installed = m.installed.value.map { it.id }.toSet()
            val along = m.cellsAlong(line, OfflineReady.ROUTE_M)
            val map = along.associateWith { c -> OfflineReady.cell(states[c], c in installed || m.hasDetailedMap(c.center)) }
            val parts = com.onpath.app.data.RoutingTiles.forLine(line, activity)
            val st = routingData.status?.takeIf { it.phase == com.onpath.app.data.RoutingDataLoader.Phase.LOADING }
            val cellBytes = along.sumOf { c ->
                (states[c] as? com.onpath.app.cells.CellState.Downloading)?.let { (it.totalBytes - it.doneBytes).coerceAtLeast(0) } ?: 0L
            }
            val routingBytes = st?.let { (it.totalBytes - it.doneBytes).coerceAtLeast(0) } ?: 0L
            OfflineReady.of(line, map, parts, routingData::exists, st?.names?.toSet() ?: emptySet(), cellBytes + routingBytes)
        } catch (e: Exception) {
            Problems.warn("cells", "Offline state of the route unknown; the plan's label shows none", e)
            null
        }
    }

    /**
     * T4: the plan's route was computed for [activity] – its corridor's map regions (named after [dest] in the loading
     * chip; [corridor] from [postRoute], else computed here for [activity]).
     */
    fun routeComputed(line: List<GeoPoint>, corridor: List<com.onpath.app.cells.CellId>?, dest: GeoPoint?, activity: com.onpath.app.routing.Activity?) =
        safeCells("route") { showCellJob(cellTriggers.routeComputed(line, corridor, activity).tagged(LoadTag(LoadWhy.ROUTE, dest))) }

    /** A searched / shared place flown to in Plan: without a detailed map there its regions and routing data load in the modal. */
    fun placeSearched(at: GeoPoint, name: String) {
        safeCells("search") {
            showCellJob(cellTriggers.pointSet(at, com.onpath.app.cells.CellWhy.SEARCHED).tagged(LoadTag(LoadWhy.POINT, at, name)))
        }
        routingFor(listOf(at), wait = true)
    }

    /** The user drags the map (true) / the map came to rest (false): new map areas are not joined meanwhile. */
    fun userBusy(on: Boolean) = safeCells("gesture") { cells.userBusy = on }

    /**
     * A tour starts on the plan's route [line] (F-OFF-02 "started route", owner: "new route – even without signal"): map
     * areas and routing parts of its corridor ([com.onpath.app.routing.OfflineCorridor] for [activity]: 10 km either
     * side on foot, 20 km by bike) still missing – loading cancelled, server failed, waited – are asked for again,
     * quietly (background chip; Wi-Fi-only rule as for the plan). Geometry off the main thread.
     */
    fun secureTourCorridor(line: List<GeoPoint>, dest: GeoPoint?, activity: com.onpath.app.routing.Activity?) {
        scope.launch {
            val pre = withContext(Dispatchers.Default) {
                runCatching { cellTriggers.corridorOf(line, activity) to com.onpath.app.data.RoutingTiles.forLine(line, activity) }
            }
            val (corridor, keys) = pre.getOrElse {
                Problems.error("cells", "Corridor of the started tour unknown; nothing more loads for it", it); return@launch
            }
            safeCells("tour") {
                val asked = cellTriggers.tourStarted(line, corridor, activity)
                tagLoads(asked, LoadTag(LoadWhy.ROUTE, dest))
                if (asked.isNotEmpty()) Problems.info("cells", "Tour started: ${asked.size} map area(s) of its corridor were missing; loading them")
            }
            routingForLine(keys)
        }
    }

    /** The map style loaded from [dir]: when it is a cell store generation, older generations can go (R-16). */
    fun onMapStyleLoaded(dir: java.io.File?) {
        val gen = com.onpath.app.cells.CellStore.generationOf(dir) ?: return
        scope.launch(Dispatchers.IO) { cells.confirmLoaded(gen) }
    }

    /** The map could not load from [dir]: if that is a cell store generation, go back to the previous one. */
    fun rollbackMapCells(dir: java.io.File?): Boolean {
        val gen = com.onpath.app.cells.CellStore.generationOf(dir) ?: return false
        return cells.rollback(gen)
    }

    // ---------------- map cell triggers + card (map-cells.md §9: the map comes where you plan or where you are) ----

    private val cellTriggers by lazy { com.onpath.app.cells.CellTriggers(cells) }
    /** Cells the open map card is about (T1/T3/T4); null = no card. Closed only by the user (OK / Not now / ×). */
    var cellJob by mutableStateOf<com.onpath.app.cells.CellJob?>(null)
        private set
    /** The card's mobile-data question was answered "Only on Wi-Fi" (for this card). */
    private var mobileDeclined by mutableStateOf(false)
    /** T3 waits for a usable fix after app start / return in Plan. */
    private var t3Pending by mutableStateOf(true)

    /** "Detailed map for here?" – asked in a question box when the app opens where there is no detailed map. */
    data class HereAsk(val at: GeoPoint, val accuracyM: Float?, val own: com.onpath.app.cells.CellId, val regions: Int, val bytes: Long,
                       /** The server must build it first (a few minutes); false = status unknown. */
                       val mustBuild: Boolean = false)
    var hereAsk by mutableStateOf<HereAsk?>(null)
        private set
    /** Areas the user said "Not now" to (this app run): not asked again until a point is set there. */
    private val hereDeclined = HashSet<com.onpath.app.cells.CellId>()

    /**
     * The answer to [hereAsk]: Load = the map for here in the background (no big loading window – the user only opened
     * the app); Not now = this area is not asked again in this run (setting a point there loads it as always).
     */
    fun answerHereAsk(load: Boolean) {
        val a = hereAsk ?: return
        hereAsk = null
        Problems.trace("cells", "Map for here: " + if (load) "load" else "not now")
        if (!load) { hereDeclined += a.own; return }
        loadHere(a.at, a.accuracyM)
    }

    /** The map for here in the background (no loading window – the user only opened the app). */
    private fun loadHere(at: GeoPoint, accuracyM: Float?) {
        safeCells("position") { showCellJob(cellTriggers.appOpened(at, accuracyM)?.copy(own = null)?.tagged(LoadTag(LoadWhy.POINT))) }
        routingFor(listOf(at), wait = false)
    }
    /** The first-start card ("Allow location to get the detailed map …") was hidden with × (this app run). */
    private var firstStartHidden by mutableStateOf(false)
    /** "Map downloads not available in this build" was shown in this app run (once is enough). */
    private var notAvailableShown = false

    /** The card for the current cell states (MapScreen collects [com.onpath.app.cells.MapCells.states] / installed). */
    fun cellCardUi(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>,
                   installed: List<com.onpath.app.cells.CellStore.InstalledCell>): CellCardUi? =
        builtCard(states, installed)
            // the user waited in the modal for their area: when all is there it simply goes – no "done" message
            ?.takeIf { !(it is CellCardUi.Done && jobWaited) }
            ?: CellCards.firstStart(cellJob, cells.downloadsAvailable,
                noDetailedMap = installed.isEmpty() && host.missing.any { !it.name.startsWith("segments") },
                waitingForPosition = t3Pending && mode == AppMode.PLAN, hidden = firstStartHidden,
                permission = tracker.hasPermission())

    private fun builtCard(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>,
                          installed: List<com.onpath.app.cells.CellStore.InstalledCell>): CellCardUi? =
        CellCards.build(cellJob, states, installed.associate { it.id to it.totalBytes }, cells.downloadsAvailable,
            settings.mapsWifiOnly, mobileDeclined)

    private fun showCellJob(j: com.onpath.app.cells.CellJob?) {
        j ?: return
        if (j.cells.isEmpty()) return // only a route's margin: quiet (the background chip shows it), no card
        val waits = MapLoadWait.waits(j, viewWithoutDetail) // never the map for here (field bug 2026-11-08)
        routingForCells(j.cells, wait = waits) // routing data comes with the map
        if (!cells.downloadsAvailable) {
            if (notAvailableShown) return
            notAvailableShown = true; cellJob = j; return
        }
        val done = builtCard(cells.states.value, cells.installed.value) is CellCardUi.Done
        val open = cellJob
        cellJob = com.onpath.app.cells.CellTriggers.merge(open, j, done)
        if (open == null || done) { mobileDeclined = false; jobWaited = false }
        // the user waits for this map (a point / search / tap without detailed map, or a route while the visible map has
        // none – never the map for here, MapLoadWait): the modal shows (again), over all regions asked for so far
        if (waits) {
            jobWaited = true
            if (loadModalFor.isEmpty()) { loadProgress.clear(); loadHeld = 0; loadBySearch = false }
            if (j.why == com.onpath.app.cells.CellWhy.SEARCHED) loadBySearch = true
            loadModalFor = loadModalFor + j.cells
        }
    }

    // ---------------- the modal while the user waits for the map of their area ----------------

    /** Regions the modal waits for; empty = no modal (closed with Cancel / Close, or all there). */
    private var loadModalFor by mutableStateOf<Set<com.onpath.app.cells.CellId>>(emptySet())
    /** The open card's job was waited for in the modal: when it is all there it simply goes – no "Map ready" card. */
    private var jobWaited by mutableStateOf(false)
    private val loadProgress = mutableMapOf<com.onpath.app.cells.CellId, MapLoadProgress>()
    /** The loading window's time-based % (one per window, reset when it closes). */
    private val loadSmoother = LoadSmoother()
    /** Last % shown (never backwards). */
    private var loadHeld = 0
    /** A search / shared place opened (or joined) the modal: Cancel also removes its ring ([MapLoadCancel]). */
    private var loadBySearch = false
    /** A large part of the visible map has no detailed map (MapScreen, any zoom) – a route computed now is waited for. */
    var viewWithoutDetail = false

    /**
     * What the modal shows now ([now]: uptime ms; called about every second): in Plan, while some of the regions the user
     * waits for are not there yet. Null = no modal (and it is over when everything is there).
     */
    fun mapLoadUi(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>, now: Long): MapLoadUi? {
        if (mode != AppMode.PLAN) return null
        val focus = loadModalFor
        val map = if (focus.isEmpty() || !cells.downloadsAvailable) null else
            MapLoads.aggregate(focus, states, cells.installed.value.associate { it.id to it.totalBytes }, loadProgress, loadHeld, now)
                .also { if (it == null) loadModalFor = emptySet() else loadHeld = it.percent } // null: the map is there
        // routing data in the same modal: one % over both, closes when both are there
        val r = if (routingModal) routingData.status.also { if (it == null) routingModal = false } else null
        val raw = RoutingLoads.combine(map, r, combinedHeld)
        // the % follows the time, not the bytes (owner: quick routing data would race to 50 % and stand)
        val ui = when {
            raw == null -> { loadSmoother.reset(); null }
            raw.kind == MapLoadUi.Kind.LOADING || raw.kind == MapLoadUi.Kind.PREPARING ->
                raw.copy(percent = maxOf(combinedHeld, loadSmoother.next(now, raw.secondsLeft)))
            else -> raw.copy(percent = combinedHeld) // offline / Wi-Fi / failed: the bar stays where it was
        }
        combinedHeld = ui?.percent ?: 0
        return ui
    }

    /** Modal (failed) → Close: the modal goes; the card shows the failure with Retry. */
    fun hideLoadModal() { loadModalFor = emptySet(); routingModal = false }

    // ---------------- routing data (loaded by the app itself, also on a fresh install) ----------------

    /** Loads missing routing data tiles from brouter.de (mobile data too unless "Maps only on Wi-Fi"). */
    val routingData: com.onpath.app.data.RoutingDataLoader by lazy {
        com.onpath.app.data.AppRouting.listen(routingListener)
        com.onpath.app.data.AppRouting.get(app)
    }
    /** The shared loader's events (also parts the recording service loaded on a tour) → the UI. */
    private val routingListener = object : com.onpath.app.data.AppRouting.Listener {
        override fun installed(names: List<String>) { scope.launch(Dispatchers.Main) { host.routingDataArrived(names) } }
        override fun changed() { scope.launch(Dispatchers.Main) { routingDataChanged() } }
    }
    /** The loading modal waits for routing data too (point set, first start, search). */
    private var routingModal by mutableStateOf(false)
    /** Last % of the combined modal (never backwards). */
    private var combinedHeld = 0
    /** Settings → "Download routing data" tapped: its progress shows in the data line. */
    private var manualRouting: String? = null

    /** Routing data for [points] (a point, a position, a route): missing tiles load; [wait] = in the modal. */
    private fun routingFor(points: List<GeoPoint>, wait: Boolean) = safeRouting("trigger") {
        val miss = routingData.ensure(com.onpath.app.data.RoutingTiles.forPoints(points))
        if (wait && miss.isNotEmpty() && routingData.status != null && mode == AppMode.PLAN) routingModal = true
    }

    /** Routing data in the corridor of a route (the map's own T4 trigger covers the regions). Never in the modal. */
    fun routingForLine(keys: List<String>) = safeRouting("route") {
        routingData.keepCorridor(keys) // parts of an older route's corridor go
        routingData.ensure(keys, corridor = true)
    }

    /**
     * What a computed route needs for its corridor – pure geometry and file checks, computed off the main thread (several
     * corridor walks per long route would hitch the UI). Null parts = that check failed (reported); the route stays.
     */
    class PostRoute(val routingKeys: List<String>?, val tilesMissing: Boolean, val cells: List<com.onpath.app.cells.CellId>?)

    fun postRoute(r: com.onpath.app.routing.PlannedRoute, pts: List<GeoPoint>, activity: com.onpath.app.routing.Activity?): PostRoute {
        val line = if (r.points.size >= 2) r.points else pts
        val keys = try { com.onpath.app.data.RoutingTiles.forLine(line, activity) } catch (e: Exception) {
            Problems.error("routing", "Routing data corridor of the route unknown; nothing loads for it", e); null
        }
        val missing = try { routingData.missing(com.onpath.app.data.RoutingTiles.forPoints(pts, 0.0)).isNotEmpty() } catch (e: Exception) {
            Problems.warn("routing", "Routing data check failed; notice not adjusted", e); false
        }
        val corridor = try { cellTriggers.corridorOf(r.points, activity) } catch (e: Exception) {
            Problems.error("cells", "Map corridor of the route unknown; computed on the main thread", e); null
        }
        return PostRoute(keys, missing, corridor)
    }

    /**
     * The plan's route settled without a corridor ([line] null: cleared, long-route question, Cancel, not routed, routing
     * failed): corridor map regions and routing parts of older routes are dropped, nothing new loads. A computed route does this through T4 ([routingForLine], CellTriggers.routeComputed).
     */
    fun corridorFollowsPlan(line: List<GeoPoint>?, activity: com.onpath.app.routing.Activity? = null) {
        safeCells("corridor") { cellTriggers.planRoute(line, activity) }
        safeRouting("corridor") {
            routingData.keepCorridor(if (line == null || line.size < 2) emptyList() else com.onpath.app.data.RoutingTiles.forLine(line, activity))
        }
    }

    /** Routing data under these map regions (a region load is a trigger too). */
    private fun routingForCells(c: Collection<com.onpath.app.cells.CellId>, wait: Boolean) = safeRouting("regions") {
        val miss = routingData.ensure(com.onpath.app.data.RoutingTiles.forCells(c))
        if (wait && miss.isNotEmpty() && routingData.status != null && mode == AppMode.PLAN) routingModal = true
    }

    /** Routing data triggers never break planning. */
    private inline fun safeRouting(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { Problems.error("routing", "Routing data download ($what) failed; planning goes on", e) }
    }

    /** Main thread: progress / phase changed – the plan's notice follows (loading ↔ not yet). */
    private fun routingDataChanged() {
        val s = routingData.status
        if (RoutingDataNotice.isNotice(routeError)) routeError = RoutingDataNotice.text(s?.phase == com.onpath.app.data.RoutingDataLoader.Phase.LOADING)
        val name = manualRouting ?: return
        when {
            s == null || name !in s.names -> {
                manualRouting = null; dataBusy = null
                if (routingData.exists(name)) sayInfo(Txt.s(Res.string.msg_routing_data_ready), NoticePlace.SETTINGS)
                else sayError(Txt.s(Res.string.msg_routing_data_stopped), NoticePlace.SETTINGS)
            }
            s.phase == com.onpath.app.data.RoutingDataLoader.Phase.LOADING ->
                dataBusy = Txt.s(Res.string.msg_routing_data_progress, s.percent, mb(s.totalBytes))
            else -> {
                manualRouting = null; dataBusy = null
                sayError(Txt.s(if (s.phase == com.onpath.app.data.RoutingDataLoader.Phase.OFFLINE) Res.string.msg_routing_data_offline
                    else Res.string.msg_routing_data_stopped), NoticePlace.SETTINGS)
            }
        }
    }

    /**
     * Modal → Cancel (owner: "Cancel = stop", [MapLoadCancel]): the waited-for regions stop loading, no card
     * follows, a searched place's ring goes, points set stay, the camera stays.
     */
    fun cancelLoadModal() {
        val job = cellJob
        val o = MapLoadCancel.decide(loadModalFor, job, jobWaited, cells.installed.value.map { it.id }.toSet(), loadBySearch)
        Problems.trace("cells", "Map loading cancelled: ${o.stop.size} region(s) stopped" +
            (job?.let { ", job ${it.why.name.lowercase()}" } ?: "") + (if (o.clearSearchMark) ", search ring removed" else ""))
        if (o.stop.isNotEmpty()) safeCells("cancel") { cells.cancel(o.stop) }
        o.stop.forEach { loadProgress.remove(it) }
        loadModalFor = emptySet(); loadBySearch = false
        // routing data waited for in the same modal: one Cancel stops it too (the partial download stays for resuming)
        if (routingModal) { routingModal = false; safeRouting("cancel") { routingData.cancel() } }
        if (o.closeCard) { cellJob = null; mobileDeclined = false; jobWaited = false; firstStartHidden = true }
        if (o.clearSearchMark) host.clearSearchRing()
    }

    /** A tap on the world map where there is no detailed map: "Load the map for this area? N regions · ≈ X MB". */
    data class LoadAsk(val at: GeoPoint, val regions: Int, val bytes: Long)
    var loadAsk by mutableStateOf<LoadAsk?>(null)
        private set

    /** Short tap on the map (nothing else under the finger): asks before loading – never a silent download from a tap. */
    fun mapTapped(p: GeoPoint) {
        if (mode != AppMode.PLAN || !cells.downloadsAvailable) return
        safeCells("tap") {
            val want = cellTriggers.preview(p)
            if (want.isNotEmpty()) loadAsk = LoadAsk(p, want.size,
                MapLoads.estimate(want, cells.states.value, cells.installed.value.associate { it.id to it.totalBytes }))
        }
    }

    /** The tap question answered: Load → the regions load (modal), Not now → nothing. */
    fun answerLoadAsk(load: Boolean) {
        val a = loadAsk ?: return
        loadAsk = null
        if (load) safeCells("tap") { showCellJob(cellTriggers.pointSet(a.at, com.onpath.app.cells.CellWhy.TAPPED).tagged(LoadTag(LoadWhy.POINT, a.at))) }
    }

    /**
     * T1: the user set these points (hold → Start here / Go here, a stop, a moved point). A very long plan's points come
     * only after its question was answered (the ViewModel's cellsForNewPoints).
     */
    fun pointsSet(before: List<GeoPoint>, after: List<GeoPoint>) {
        val fresh = com.onpath.app.cells.CellTriggers.newPoints(before, after)
        if (fresh.isNotEmpty()) routingFor(fresh, wait = fresh.size <= 3)
        if (fresh.size > 3) return // a whole route loaded / restored: T4 covers its corridor
        for (p in fresh) safeCells("point") {
            val j = cellTriggers.pointSet(p)
            // a point set there answers "map for here?" and lifts an earlier "Not now" for those areas
            j?.cells?.let { c -> hereDeclined.removeAll(c.toSet()); if (hereAsk?.own in c) hereAsk = null }
            showCellJob(j.tagged(LoadTag(LoadWhy.POINT, p)))
        }
    }

    /**
     * Strip → "Load now over mobile data" (owner): the waiting cells of this card load now, once – the
     * "Maps only on Wi-Fi" setting stays.
     */
    fun cellCardMobileYes() {
        val job = cellJob ?: return
        val waiting = cells.waitingForMobileApproval().map { it.first }.filter { it in job.cells }
        Problems.trace("cells", "Load now over mobile data: ${waiting.size} waiting region(s) of the card")
        safeCells("mobile") { cells.approveMobile(waiting) }
    }

    /**
     * Loading modal → "Load now over mobile data" (owner): what the modal waits for (map regions and
     * routing data) loads once over mobile data; the setting stays.
     */
    fun loadModalOnMobile() {
        val waiting = cells.waitingForMobileApproval().map { it.first }.filter { it in loadModalFor }
        Problems.trace("cells", "Load now over mobile data: ${waiting.size} waiting region(s) of the loading modal" +
            if (routingModal) " + routing data" else "")
        if (waiting.isNotEmpty()) safeCells("mobile") { cells.approveMobile(waiting) }
        if (routingModal) safeRouting("mobile") { routingData.loadOnMobileOnce() }
    }

    /** Card → "Only on Wi-Fi": the question goes, the card says the areas wait for Wi-Fi. */
    fun cellCardWifiOnly() { mobileDeclined = true }

    /** Card → "Retry": ask again for the cells that failed. */
    fun cellCardRetry() {
        safeRouting("retry") { routingData.retry(force = true) }
        val job = cellJob ?: return
        val failed = job.cells.filter { cells.state(it) is com.onpath.app.cells.CellState.Failed }
        val own = failed.filter { it == job.own }
        failed.forEach { loadProgress.remove(it) } // the modal's bar for them starts again
        if (jobWaited) loadModalFor = loadModalFor + failed
        safeCells("retry") {
            if (own.isNotEmpty()) cells.request(own, com.onpath.app.cells.CellReason.POINT)
            val rest = failed - own.toSet()
            if (rest.isNotEmpty()) cells.request(rest, com.onpath.app.cells.CellReason.NEAR, ownPoint = false)
        }
    }

    /** Card → OK / Not now / ×: the card goes; cells still loading go on (Settings → Offline maps shows them). */
    fun closeCellCard() { if (cellJob == null) firstStartHidden = true; cellJob = null; mobileDeclined = false }

    /** Overlay → Cancel: stop loading the areas of this job (installed ones stay; setting a point there asks again). */
    fun cancelCellLoading() {
        val job = cellJob ?: return
        val inst = cells.installed.value.map { it.id }.toSet()
        safeCells("cancel") { cells.cancel(job.cells.filter { it !in inst || job.why == com.onpath.app.cells.CellWhy.UPDATE }) }
        closeCellCard()
    }

    /** Offline maps → "Clear all" is not offered while a tour runs (its map must not change under the user's feet). */
    val mapsLocked: Boolean get() = recState.value != null || mode == AppMode.NAVIGATE

    /**
     * Settings → Offline maps → "Clear all" (owner): every downloaded
     * map area and every waiting request goes, the world overview stays; the area of the position (T3) and the next
     * point set load again as on a new install. Not during a tour.
     */
    fun clearAllMaps() = removeAreas("clear all") {
        if (mapsLocked) { Problems.info("cells", "Clear all refused: a tour runs"); return@removeAreas }
        val bytes = offlineBytes(cells.installed.value.map { it.id })
        val all = cells.clearAll()
        if (all.isNotEmpty()) removeRoutingParts(all, emptyList())
        cleanupPreview = null
        closeCellCard(); t3Pending = true
        // the user confirmed it on the Offline maps page: the result is a plain line there, nothing on the map (owner)
        offlineResult = OfflineResult.removed(all.size, bytes, afterTour = false)
    }

    /**
     * Settings → Offline maps → "Clear all" / "Clean up" remove: a failure keeps a persistent message (the line on the
     * page is only for success).
     */
    private inline fun removeAreas(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) {
            Problems.error("cells", "Offline maps: $what failed", e)
            offlineResult = null
            sayError(Txt.s(Res.string.msg_maps_remove_failed), NoticePlace.SETTINGS)
        }
    }

    /** Bytes of [ids] on the phone (map + routing data), as Offline maps lists them. */
    private fun offlineBytes(ids: List<com.onpath.app.cells.CellId>): Long {
        val routing = routingBytesCache?.second.orEmpty()
        val set = ids.toSet()
        return cells.installed.value.filter { it.id in set }.sumOf { it.totalBytes + (routing[it.id] ?: 0L) }
    }

    /** Offline maps: what the last Clean up / Clear all removed ("37 areas removed · 450 MB freed"); until the page is left. */
    var offlineResult by mutableStateOf<String?>(null)

    /** Settings → Offline maps → "Clean up": what would go, shown before anything is removed ([confirmCleanup]). */
    fun prepareCleanup() {
        val installed = cells.installed.value
        val removing = cells.removing()
        val planLine = route?.points?.takeIf { it.size >= 2 } ?: waypoints.toList()
        val planActivity = host.activity
        val rec = recState.value
        val tour = rec?.track.orEmpty()
        val followed = rec?.nav?.line.orEmpty()
        val pos = tracker.location.value?.let { GeoPoint(it.latitude, it.longitude) }
        val routingBytes = routingBytesCache?.second.orEmpty()
        scope.launch {
            val preview = try {
                withContext(Dispatchers.IO) {
                    // each saved route keeps the corridor of its own activity (unknown / unreadable → the largest)
                    val savedWithActivity = db.listTours().filter { it.kind != com.onpath.app.data.TourKind.RECORDED }
                        .map { t -> db.loadSegments(t.id).flatten().map { GeoPoint(it.lat, it.lon) } to
                            com.onpath.app.debug.enumOrNull<com.onpath.app.routing.Activity>(t.activity, "cells") }
                        .filter { it.first.isNotEmpty() }
                    val saved = savedWithActivity.map { it.first }
                    // the running tour keeps the largest corridor (tourActivity unknown): robust, never less than it loaded
                    val inputs = com.onpath.app.cells.MapCleanup.Inputs(planLine, saved, tour, followed, pos,
                        planActivity = planActivity, savedActivities = savedWithActivity.map { it.second })
                    val sizes = installed.filter { it.id !in removing }.map { it.id to it.totalBytes + (routingBytes[it.id] ?: 0L) }
                    val plan = com.onpath.app.cells.MapCleanup.plan(sizes, inputs)
                    CleanupPreview(plan.remove, plan.removeBytes, buildList {
                        if (planLine.isNotEmpty()) add(Txt.s(Res.string.msg_cleanup_keeps_plan))
                        if (saved.isNotEmpty()) add(Txt.s(Res.string.msg_cleanup_keeps_saved))
                        if (rec != null) add(Txt.s(Res.string.msg_cleanup_keeps_tour))
                        if (pos != null) add(Txt.s(Res.string.msg_cleanup_keeps_position))
                    })
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Problems.error("cells", "Clean up could not work out which map areas are needed; nothing removed", e)
                sayError(Txt.s(Res.string.msg_cleanup_not_possible), NoticePlace.SETTINGS)
                return@launch
            }
            Problems.trace("cells", "Clean up preview: ${preview.remove.size} region(s) could go (${preview.bytes / 1_048_576} MB); keeps " +
                preview.keeps.joinToString().ifEmpty { "nothing in use" })
            cleanupPreview = preview
        }
    }

    /** "Clean up" → Remove: the areas of [cleanupPreview] go (after the tour if one runs); routing data they alone need too. */
    fun confirmCleanup() {
        val p = cleanupPreview ?: return
        cleanupPreview = null
        if (p.remove.isEmpty()) return
        removeAreas("clean up") {
            val kept = cells.installed.value.map { it.id }.filter { it !in p.remove }
            cells.delete(p.remove, "Clean up in Settings")
            removeRoutingParts(p.remove, kept)
            offlineResult = OfflineResult.removed(p.remove.size, p.bytes, afterTour = mapsLocked)
        }
    }

    /** Areas with the detailed map (installed cells, region package) for the style's overview cover. */
    fun detailedAreas(): List<DoubleArray> = cells.detailedAreas()

    /** Boxes of the installed region packages (z7–9, map-cells.md §20): the style's region view there. */
    fun packageAreas(): List<DoubleArray> = cells.packageAreas()

    /** True when a large part of the visible box has no detailed map ([OverviewHintRule], any zoom). */
    fun overviewOnly(south: Double, west: Double, north: Double, east: Double): Boolean = try {
        OverviewHintRule.show(OverviewHintRule.MIN_ZOOM,
            OverviewHintRule.samples(south, west, north, east).map { (lat, lon) -> cells.hasDetailedMap(GeoPoint(lat, lon)) })
    } catch (e: Exception) {
        Problems.warn("cells", "Detailed-map check failed; overview hint hidden", e); false
    }

    /** Every fix: T3 (first usable fix after start / return in Plan). */
    private fun cellsForFix(loc: android.location.Location) {
        val p = GeoPoint(loc.latitude, loc.longitude)
        val acc = if (loc.hasAccuracy()) loc.accuracy else null
        val onTour = recState.value != null || mode == AppMode.NAVIGATE
        if (t3Pending && startModeKnown && !onTour && (acc == null || acc <= com.onpath.app.cells.CellTriggers.T3_MAX_ACCURACY_M)) {
            t3Pending = false
            // owner (e.g. in a car): opening the app loads the map for here silently only when the server
            // has it ready (seconds); when it must be built first (minutes) the question box asks. No loading window.
            safeCells("position") {
                val want = cellTriggers.preview(p)
                val own = want.firstOrNull()
                if (own == null) { hereAsk = null; return@safeCells }
                if (!cells.downloadsAvailable || own in hereDeclined) return@safeCells
                scope.launch {
                    val built = withContext(Dispatchers.IO) { cells.builtOnServer(want) }
                    if (built != null && built.containsAll(want)) {
                        Problems.trace("cells", "Map for here ready on the server (${want.size} area(s)) – loading it quietly")
                        loadHere(p, acc)
                    } else {
                        hereAsk = HereAsk(p, acc, own, want.size,
                            MapLoads.estimate(want, cells.states.value, cells.installed.value.associate { it.id to it.totalBytes }),
                            mustBuild = built != null)
                        Problems.trace("cells", "Asking whether to load the map for here (${want.size} area(s), " +
                            (if (built == null) "server status unknown" else "${want.size - built.size} to be built") + ")")
                    }
                }
            }
        }
        // T5 (on tour, near the edge of the map) runs in the recording service – also with the screen off
    }

    /**
     * After a route was computed in Plan (owner: "at the moment I plan, I want a recent map"; nothing is updated while
     * the app is idle): newer versions of the installed map areas along it (quiet; the map is veiled for
     * the seconds a ready one loads). Newer routing data: the ViewModel's checkRoutingData.
     */
    fun freshenAlong(line: List<GeoPoint>, corridor: List<com.onpath.app.cells.CellId>?, activity: com.onpath.app.routing.Activity?) {
        safeCells("fresh") {
            val asked = cellTriggers.freshenAlong(line, corridor, activity)
            tagLoads(asked, LoadTag(LoadWhy.REFRESH))
            if (asked.isNotEmpty()) Problems.info("cells", "Checking ${asked.size} map area(s) along the route for a newer version")
        }
    }

    // ---------------- background downloads: visible and stoppable (owner) ----------------

    private val bgProgress = BackgroundProgress()
    /** Per map region of the strip: its time-based % while the server prepares it ([MapLoadProgress]). */
    private val bgCellProgress = HashMap<com.onpath.app.cells.CellId, MapLoadProgress>()
    /** The strip / chip was tapped: what loads in the background, with "Cancel all". */
    var downloadDetailsOpen by mutableStateOf(false)
    /** Plan: a message box, the quiet pill or the download chip is on screen (hints below the search bar move down). */
    var backgroundStripShown by mutableStateOf(false)
    /** Bottom of that box / pill / chip below the status bar in dp (measured); hints go below it. */
    var backgroundStripBottomDp by mutableStateOf(0)

    /**
     * What loads in the background now (map regions, routing data the user does not wait for in the modal); null =
     * nothing, or the loading modal is open. Called about every second by the strip / chip.
     */
    fun backgroundDownloadUi(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>): BackgroundDownloadUi? = try {
        val modal = loadModalFor.isNotEmpty() || routingModal
        // newer versions of installed areas: told in the strip / its details (no second indicator on the map)
        val installed = if (modal) emptySet() else cells.installed.value.map { it.id }.toSet()
        // the server's preparing time moves the % too (else it stands at 0 % for the minutes an area is prepared)
        val cellItems = if (modal) emptyList() else BackgroundProgress.cellItems(states, emptySet(), installed, bgCellProgress,
            android.os.SystemClock.elapsedRealtime(), cells::knownSize)
        val r = if (modal) null else routingData.status?.takeIf { it.phase == com.onpath.app.data.RoutingDataLoader.Phase.LOADING }
        val items = cellItems + listOfNotNull(r?.let { BackgroundProgress.Item("routing", it.doneBytes, it.totalBytes) })
        val newer = if (modal) 0 else states.count { (c, s) ->
            c in installed && BackgroundProgress.loading(s) && s != com.onpath.app.cells.CellState.Requested
        }
        val eta = (MapUpdates.of(states, installed) as? MapUpdateUi.Preparing)?.minutes
        val groups = if (modal) emptyList() else loadGroups(states, cellItems, installed, r)
        bgProgress.update(items, cellItems.size, r?.names?.size ?: 0, newer, eta, groups)
    } catch (e: Exception) {
        Problems.warn("cells", "Background download status unknown; strip hidden", e); null
    }

    // ---- names of what loads (owner: "Bad Waldsee · 34 %" chip; map areas only have grid ids) ----

    /** Why each map area was asked for, set where the app asks (point, search, route, newer map); main thread only. */
    private val loadTags = HashMap<com.onpath.app.cells.CellId, LoadTag>()
    /** Larger towns of the world overview (read once off the main thread; null = not read yet). */
    @Volatile private var overviewTowns: List<com.onpath.app.map.OverviewTowns.Town>? = null
    private var overviewTownsAsked = false
    /** Nearest town per area ("" = none near). */
    private val townOfCell = HashMap<com.onpath.app.cells.CellId, String>()

    /** Remembers why [cells] load; a more important reason (route > point > newer) is kept. */
    private fun tagLoads(cells: Collection<com.onpath.app.cells.CellId>, tag: LoadTag) {
        val t = tag.copy(sinceMs = android.os.SystemClock.uptimeMillis())
        for (c in cells) { val old = loadTags[c]; if (old == null || t.why.ordinal <= old.why.ordinal) loadTags[c] = t }
    }

    private fun com.onpath.app.cells.CellJob?.tagged(tag: LoadTag): com.onpath.app.cells.CellJob? = also { j -> j?.let { tagLoads(it.cells + it.margin, tag) } }

    /** The overview town nearest to [c]'s centre; null while the towns are read or none is near. */
    private fun townNear(c: com.onpath.app.cells.CellId): String? {
        val towns = overviewTowns
        if (towns == null) {
            if (!overviewTownsAsked) {
                overviewTownsAsked = true
                scope.launch(Dispatchers.IO) {
                    overviewTowns = try { com.onpath.app.map.OverviewTowns.load(app) } catch (e: Exception) {
                        Problems.warn("cells", "Town names of the world overview not readable; loading areas named \"map area\"", e); emptyList()
                    }
                }
            }
            return null
        }
        return townOfCell.getOrPut(c) { com.onpath.app.map.OverviewTowns.nearest(towns, c.center)?.name.orEmpty() }.ifEmpty { null }
    }

    /** What loads, by name: the reason the app knows ([loadTags]), else the nearest overview town. */
    private fun loadGroups(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>, items: List<BackgroundProgress.Item>,
                           installed: Set<com.onpath.app.cells.CellId>, paths: com.onpath.app.data.RoutingDataLoader.Status?): List<LoadGroup> {
        val bytes = items.associate { it.key to it.total }
        val now = android.os.SystemClock.uptimeMillis()
        // reasons of areas no longer loading go (a moment of grace: a fresh request may not show as loading yet)
        loadTags.entries.removeAll { (c, t) -> !(states[c]?.let(BackgroundProgress::loading) ?: false) && now - t.sinceMs > 10_000 }
        val job = cellJob?.takeIf { it.own != null }
        val touring = recState.value != null
        val entries = states.filter { (c, s) -> "cell:${c.id}" in bytes && BackgroundProgress.loading(s) }.map { (c, s) ->
            val tag = loadTags[c]
            val why = tag?.why ?: when { c in installed -> LoadWhy.REFRESH; touring -> LoadWhy.AHEAD; else -> null }
            val name = tag?.name ?: tag?.at?.let { host.placeName(it) }
            LoadEntry(why, name, townNear(c), waiting = job != null && c in job.cells, bytes = bytes["cell:${c.id}"] ?: 0L,
                phase = LoadNames.phaseOf(s))
        }
        return LoadNames.groups(entries + listOfNotNull(paths?.let { LoadEntry(null, paths = true, bytes = it.totalBytes) }))
    }

    /** Settings → Offline maps / the strip's details → "Cancel all": every waiting download goes; installed maps stay. */
    fun cancelAllDownloads() {
        safeCells("cancel all") { cells.cancelAll() }
        safeRouting("cancel all") { routingData.cancel() }
        loadModalFor = emptySet(); routingModal = false
        cellJob = null
        downloadDetailsOpen = false
    }

    /** Map cell triggers never break planning: a failure is logged, planning goes on. */
    private inline fun safeCells(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { Problems.error("cells", "Map download trigger ($what) failed; planning goes on", e) }
    }

    // ---------------- Settings → Offline maps ----------------

    /** Place names of installed cells ("Meran", "near Lana") from the offline index; filled by [loadAreaNames]. */
    var areaNames by mutableStateOf<Map<com.onpath.app.cells.CellId, String>>(emptyMap())
        private set

    /** Settings opened: name the installed areas (offline index, built once; coordinates until then / without). */
    fun loadAreaNames() {
        val todo = cells.installed.value.map { it.id }.filter { it !in areaNames }
        if (todo.isEmpty()) return
        scope.launch {
            val index = try { host.offlineIndex() } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { Problems.info("cells", "No place index for map area names; coordinates shown", e); return@launch }
            val names = withContext(Dispatchers.IO) {
                todo.associateWith { c ->
                    com.onpath.app.search.RouteNamer.label(c.center, com.onpath.app.search.RouteNamer.placesNear(index, listOf(c.center)))
                }.filterValues { it != null }.mapValues { it.value!! }
            }
            areaNames = areaNames + names
        }
    }

    fun offlineMapsUi(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>,
                      installed: List<com.onpath.app.cells.CellStore.InstalledCell>): OfflineMapsUi {
        val fmt = SimpleDateFormat(Txt.s(Res.string.msg_date_pattern_area), Locale.getDefault())
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val sorted = installed.sortedBy { areaNames[it.id] ?: it.id.id }
        // the map of a region includes its routing data (owner); a part shared by regions counts once.
        // Computed once per change of the regions or the routing data – not on every progress tick
        val key = sorted.map { it.id } to routingData.store.version
        val routingBytes = routingBytesCache?.takeIf { it.first == key }?.second ?: try {
            com.onpath.app.data.RoutingTiles.bytesPerCell(sorted.map { it.id }) { k ->
                com.onpath.app.routing.Rd5Parts.Part.parse(k)?.let { routingData.store.sizeOnPhone(it) } ?: 0L
            }.also { routingBytesCache = key to it }
        } catch (e: Exception) {
            Problems.warn("routing", "Routing data sizes for Offline maps not readable; map sizes only", e); emptyMap()
        }
        val removing = try { cells.removing() } catch (e: Exception) { Problems.warn("cells", "Areas being removed unknown", e); emptySet() }
        val areas = sorted.map { c ->
            val built = c.built?.take(10)?.let { b -> runCatching { iso.parse(b) }.getOrNull() }
            val st = states[c.id]
            OfflineArea(c.id.id, areaNames[c.id] ?: coordName(c.id), c.totalBytes + (routingBytes[c.id] ?: 0L),
                fmt.format(built ?: Date(c.installedAt)),
                if (c.id in removing) Txt.s(Res.string.msg_area_removed_after_tour) else st?.takeIf { it !is com.onpath.app.cells.CellState.Ready && it != com.onpath.app.cells.CellState.None }?.label())
        }
        val ids = installed.map { it.id }.toSet()
        val loading = states.count { (c, s) -> c !in ids && s !is com.onpath.app.cells.CellState.Failed && s != com.onpath.app.cells.CellState.None }
        // what still waits to download – visible and stoppable (owner)
        val w = try { cells.waiting() } catch (e: Exception) { Problems.warn("cells", "Waiting downloads unknown", e); null }
        val r = try { routingData.status } catch (e: Exception) { Problems.warn("routing", "Routing downloads unknown", e); null }
        val waiting = WaitingDownloads(w?.regions ?: 0, r?.names?.size ?: 0,
            (w?.knownBytes ?: 0L) + (w?.unknownSize ?: 0) * BackgroundProgress.ESTIMATE_BYTES + (r?.let { it.totalBytes - it.doneBytes } ?: 0L))
        return OfflineMapsUi(cells.downloadsAvailable, areas, settings.mapsWifiOnly, loading, waiting.takeIf { it.any },
            cleanup = cleanupPreview, result = offlineResult, clearAllBlocked = if (mapsLocked) Txt.s(Res.string.msg_clear_all_blocked) else null)
    }

    private fun coordName(c: com.onpath.app.cells.CellId): String = c.center.let {
        "%.2f° %s, %.2f° %s".format(Locale.ROOT, kotlin.math.abs(it.lat), if (it.lat >= 0) "N" else "S",
            kotlin.math.abs(it.lon), if (it.lon >= 0) "E" else "W")
    }

    /** Settings → "Update maps now": ask for newer versions of every installed area. */
    fun updateMapsNow() = safeCells("update") {
        val all = cells.installed.value.map { it.id }
        val asked = cells.request(all, com.onpath.app.cells.CellReason.REFRESH, ownPoint = false)
        // the page's own result line (like a removal), not a message
        offlineResult = if (asked.isEmpty()) Txt.s(Res.string.msg_maps_already_checking)
            else Txt.p(if (settings.mapsWifiOnly) Res.plurals.msg_maps_checking_wifi else Res.plurals.msg_maps_checking, asked.size, asked.size)
    }

    fun deleteMapArea(id: String) {
        val c = com.onpath.app.cells.CellId.parse(id) ?: return
        val kept = cells.installed.value.map { it.id }.filter { it != c }
        safeCells("delete") { cells.delete(listOf(c), "deleted in Settings: ${areaNames[c] ?: c.id}") }
        removeRoutingParts(listOf(c), kept)
    }

    /**
     * Routing parts under deleted regions that no remaining region needs go too (owner: routing data is part
     * of a region's map). Parts loaded for a point or a route outside every region stay. Background, never during a tour.
     */
    private fun removeRoutingParts(deleted: List<com.onpath.app.cells.CellId>, kept: List<com.onpath.app.cells.CellId>) {
        if (recState.value != null) return // the tour's router may need them; they go with the next delete
        scope.launch(Dispatchers.IO) {
            safeRouting("delete") {
                val parts = com.onpath.app.data.RoutingTiles.unneeded(deleted, kept).mapNotNull { com.onpath.app.routing.Rd5Parts.Part.parse(it) }
                val freed = routingData.store.remove(parts)
                if (freed > 0) {
                    Problems.info("routing", "Routing data of deleted map areas removed (${freed / 1_048_576} MB, ${parts.joinToString()})")
                    withContext(Dispatchers.Main) { host.invalidateEngine() }
                }
            }
        }
    }
    /**
     * Settings → Diagnostics → "Download routing data": the 1° parts at the map centre from brouter.de (normally they
     * come by themselves with the map – owner: no separate "routing data" for the user).
     */
    fun downloadRoutingData() {
        if (dataBusy != null) return
        val (lat, lon, _) = host.initialCamera()
        val keys = com.onpath.app.data.RoutingTiles.forPoints(listOf(GeoPoint(lat, lon)))
        val miss = try { routingData.missing(keys) } catch (e: Exception) {
            Problems.warn("routing", "Routing data check failed", e); keys
        }
        if (miss.isEmpty()) { sayInfo(Txt.s(Res.string.msg_routing_data_present), NoticePlace.SETTINGS); return }
        // the same loader as the automatic download (one writer per file); an explicit tap loads on mobile data too
        manualRouting = miss.first()
        dataBusy = Txt.s(Res.string.msg_routing_data_busy)
        safeRouting("manual") { routingData.ensure(miss, ignoreWifiOnly = true) }
    }
}
