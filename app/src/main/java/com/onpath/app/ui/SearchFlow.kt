package com.onpath.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import com.onpath.app.routing.GeoPoint
import com.onpath.app.search.SharedPlaceLookup
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Search and shared places (UI in ui/search, logic in search/) – out of AppViewModel (review Arch 3, owner 2026-11-01),
 * the same code: the search view, a picked result's flight and its candidate marker ([searchMark]), the names of route
 * points added from a place, and a place shared from another app (lookup, "Place not clear", its menu once the map is
 * there). What it reads and changes of the plan and the screens comes through [Host] (the ViewModel's own properties –
 * no copies). Adding a place to the plan (addPlace, the place card) stays in the ViewModel: those are plan edits.
 */
class SearchFlow(
    private val scope: CoroutineScope,
    private val searchOf: () -> com.onpath.app.ui.search.SearchSession,
    private val storeOf: () -> com.onpath.app.search.SearchStore,
    private val host: Host,
) {
    /** The plan and the screens as search sees them (the ViewModel's own properties). */
    interface Host {
        val mode: AppMode
        val startModeKnown: Boolean
        val canEditMap: Boolean
        val mapOverlayOpen: Boolean
        val routesOpen: Boolean
        val showSettings: Boolean
        val loadModalOpen: Boolean
        val waypoints: List<GeoPoint>
        /** Points the plan's undo / redo can bring back (their names are kept). */
        fun historyPoints(): Set<GeoPoint>
        fun enter(m: AppMode)
        fun closeMapOverlays(why: String)
        /** My routes, Settings, the time details and the route details close: back to the map. */
        fun backToMap()
        /** A shown track (GPX file / tour) goes – it hides the plan sheet and blocks map holds. */
        fun closeShownTrack()
        /** An intended camera fly to [at]: following stops first (CLAUDE.md "Map camera"). */
        fun flyTo(at: GeoPoint, zoom: Double)
        /** Map regions and routing data for a searched place (the loading modal when there is no detailed map). */
        fun loadMapsFor(at: GeoPoint, name: String)
        fun stopFollowing()
        /** The start camera must not move the map away from a shared place. */
        fun holdStartCamera()
        fun openPoiMenu(poi: com.onpath.app.poi.Poi)
        fun say(text: String, kind: NoticeKind, place: NoticePlace)
    }

    private val search get() = searchOf()
    private val searchStore get() = storeOf()
    private val mode get() = host.mode
    private val startModeKnown get() = host.startModeKnown
    private val canEditMap get() = host.canEditMap
    private val mapOverlayOpen get() = host.mapOverlayOpen
    private val routesOpen get() = host.routesOpen
    private val showSettings get() = host.showSettings
    private val loadModalOpen get() = host.loadModalOpen
    private val waypoints get() = host.waypoints
    private fun enter(m: AppMode) = host.enter(m)
    private fun closeMapOverlays(why: String = "closed") = host.closeMapOverlays(why)
    private fun sayError(text: String, place: NoticePlace) = host.say(text, NoticeKind.ERROR, place)

    private fun sameSpot(p: GeoPoint, lat: Double, lon: Double) =
        kotlin.math.abs(p.lat - lat) < 1e-7 && kotlin.math.abs(p.lon - lon) < 1e-7

    var searchOpen by mutableStateOf(false)
    /** The picked search result (address, kind) behind planPoi; null for a POI tapped on the map. */
    var searchPlace by mutableStateOf<com.onpath.app.search.Place?>(null)
    /** Names of route points added from a place ("Leiter Alm"), by position – for stop lists. */
    private val placeNames = HashMap<GeoPoint, String>()
    private var namesLoaded = false

    fun openSearch() { closeMapOverlays("search opened"); searchOpen = true; search.onOpen() }
    fun closeSearch() { searchOpen = false }

    /**
     * Result tapped (owner): close the search, fly the map there and mark the place with a pin – no card. Holding the
     * pin opens the usual POI menu (Add to route / Start here … / Google Maps). The mark stays until the next search
     * pick or until the place is added to the route.
     */
    fun pickSearchResult(place: com.onpath.app.search.Place) {
        val p = search.resolve(place)
        search.remember(p)
        searchOpen = false
        closeMapOverlays()
        searchPlace = p
        searchMark = p
        flyToPlace(p)
    }

    /**
     * Flies to a searched / shared place at a zoom that fits what it is (a city needs the overview, a restaurant the
     * close-up); without a detailed map there its regions and routing data load in the modal (owner: a trigger).
     */
    fun flyToPlace(p: com.onpath.app.search.Place) {
        val zoom = when (p.sub) { "city" -> 12.0; "town" -> 13.0; "village", "suburb" -> 14.0; else -> 16.0 }
        host.flyTo(GeoPoint(p.lat, p.lon), zoom) // following stops first (CLAUDE.md "Map camera")
        if (mode == AppMode.PLAN) host.loadMapsFor(GeoPoint(p.lat, p.lon), p.title())
    }

    /** Search result marked on the map (pin); see [pickSearchResult]. */
    var searchMark by mutableStateOf<com.onpath.app.search.Place?>(null)

    /** [poi] is the candidate marker's place (its hold menu): a search result / shared place, not a POI of the map. */
    fun isCandidate(poi: com.onpath.app.poi.Poi): Boolean =
        searchMark?.let { sameSpot(GeoPoint(it.lat, it.lon), poi.lat, poi.lon) } == true

    fun clearSearchMarkAt(lat: Double, lon: Double) {
        val m = searchMark ?: return
        if (sameSpot(GeoPoint(m.lat, m.lon), lat, lon)) searchMark = null
    }

    /** Name of a route point added from a place, or null (UI: "Leiter Alm" instead of "Point 3"). */
    fun stopName(p: GeoPoint): String? { loadNames(); return placeNames[p] }

    private fun loadNames() { if (!namesLoaded) { namesLoaded = true; placeNames.putAll(searchStore.names()) } }

    fun rememberPlaceName(p: GeoPoint, name: String) {
        loadNames(); placeNames[p] = name
        val alive = HashSet(waypoints).apply { add(p); addAll(host.historyPoints()) }
        placeNames.keys.retainAll(alive)
        searchStore.saveNames(placeNames)
    }

    // ---------------- shared places (another app → OnPath; parsing in search/SharedPlace) ----------------
    private var sharedJob: Job? = null
    /** A shared place is being looked up (short link, address search): the map shows "Finding the shared place…". */
    var sharedLookup by mutableStateOf(false)
    /** Until then (elapsedRealtime) the start camera must not move the map away from a shared place. */
    var keepCameraUntil = 0L
        private set

    /**
     * A place shared from Google Maps, a `geo:` link … (MainActivity): back to the map in Plan (a running tour goes on
     * untouched), fly there and show the candidate marker like a search result – nothing is added to the route. Without a
     * coordinate (short link offline, address only) the address is searched offline, then online, with a note.
     */
    fun receiveSharedPlace(shared: com.onpath.app.search.SharedPlace) {
        host.backToMap(); searchOpen = false
        sharedUnsure = null
        closeMapOverlays()
        // A shown track hides the plan sheet and blocks map holds – the ring's menu must work.
        host.closeShownTrack()
        holdCameraForSharedPlace()
        sharedJob?.cancel()
        sharedLookup = true
        sharedJob = scope.launch {
          try {
            // Cold start: the start mode (Plan / Navigate) is decided after a database check – switch after that.
            if (!startModeKnown) androidx.compose.runtime.snapshotFlow { startModeKnown }.first { it }
            if (mode != AppMode.PLAN) enter(AppMode.PLAN)
            // coordinate in the link → offline index / Photon → the phone's Geocoder → "Place not clear":
            // search/SharedPlaceResolve + SharedPlaceLookup (unit-tested). The Google page is never loaded (A-020).
            val http = com.onpath.app.search.ShortLinkResolver.http(
                com.onpath.app.search.PhotonProvider.userAgent(com.onpath.app.BuildConfig.VERSION_NAME))
            val outcome = com.onpath.app.search.SharedPlaceResolve(http) { name, address, query ->
                try {
                    search.findShared(name, address, query)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    Problems.info("search", "Address search for a shared place failed", e); SharedPlaceLookup.Result.NotFound
                }
            }.resolve(shared)
            when (outcome) {
                is com.onpath.app.search.SharedOutcome.Show -> showSharedPlace(outcome.place)
                // hits, but none surely the place (a namesake in another town): no ring – the search, prefilled; stays until tapped
                is com.onpath.app.search.SharedOutcome.Unsure -> sharedUnsure = outcome.searchText
                // Stays until OK (owner rule: no message disappears by itself).
                com.onpath.app.search.SharedOutcome.NotFound -> sayError(Txt.s(Res.string.msg_shared_place_not_found), NoticePlace.MAP)
            }
          } finally { sharedLookup = false }
        }
    }

    /**
     * A shared place without a sure position (only namesakes elsewhere): the text to search for. While set, Plan shows
     * the box "Place not clear" with Search ([searchSharedUnsure]).
     */
    var sharedUnsure by mutableStateOf<String?>(null)
        private set

    /** The "Place not clear" box's button: the search opens with the shared name + town typed in. */
    fun searchSharedUnsure() {
        val text = sharedUnsure ?: return
        sharedUnsure = null
        openSearch()
        search.onQuery(text)
    }

    fun showSharedPlace(p: com.onpath.app.search.Place) {
        if (mode != AppMode.PLAN) enter(AppMode.PLAN)
        closeMapOverlays()
        holdCameraForSharedPlace()
        searchPlace = p
        searchMark = p
        // a place shared into OnPath counts as searched: it goes to "Recent" with its share sign (owner 2026-11-02)
        search.remember(p.copy(shared = true))
        host.stopFollowing()
        // Google Maps place → go there (owner): the ring's menu opens by itself once the map is there – on every share
        sharedMenuWaiting = sharedMenu.arrived(SharedPlaceMenu.key(p.lat, p.lon, p.name))
        // outside the downloaded map the ring still shows (on an empty map) – the overview zoom makes that clear
        flyToPlace(p)
    }

    private val sharedMenu = SharedPlaceMenu()
    /** The map moved by hand during the flight to a shared place: its menu no longer opens by itself. */
    fun mapMovedByHand() {
        if (sharedMenu.cancel()) { sharedMenuWaiting = false; Problems.trace("plan", "Shared place: menu not opened – map moved by hand") }
    }
    /** The shared place's menu waits for the camera to arrive ([openSharedMenuIfArrived], asked by MapScreen). */
    var sharedMenuWaiting by mutableStateOf(false)
        private set
    /** Bumped on every camera stop (MapHost): MapScreen then asks [openSharedMenuIfArrived]. */
    var cameraIdleTick by mutableStateOf(0)
        private set
    fun cameraIdle() { cameraIdleTick++ }

    /**
     * The camera stopped with a shared place's menu waiting: opens the ring's POI menu when the map is there – same
     * menu and rules as holding the ring ([SharedPlaceMenu]). [cameraLat] / [cameraLon] = camera centre.
     */
    fun openSharedMenuIfArrived(cameraLat: Double?, cameraLon: Double?, metersPerPx: Double, arrivedPx: Double) {
        val m = searchMark
        val off = if (m != null && cameraLat != null && cameraLon != null && metersPerPx > 0)
            Geo.distance(m.lat, m.lon, cameraLat, cameraLon) / metersPerPx else null
        val step = sharedMenu.check(m?.let { SharedPlaceMenu.key(it.lat, it.lon, it.name) }, off, arrivedPx, canEditMap,
            elsewhere = mapOverlayOpen || searchOpen || routesOpen || showSettings, loading = loadModalOpen)
        when (step) {
            SharedPlaceMenu.Step.OPEN -> {
                sharedMenuWaiting = false
                Problems.trace("plan", "Shared place: the map is there – its menu opens (plan ${waypoints.size} points)")
                if (m != null) host.openPoiMenu(m.toPoi())
            }
            SharedPlaceMenu.Step.DROP -> {
                sharedMenuWaiting = false
                Problems.trace("plan", "Shared place: menu not opened – the user went on (ring ${if (m == null) "gone" else "shown"}, " +
                    "map editable $canEditMap)")
            }
            SharedPlaceMenu.Step.WAIT, SharedPlaceMenu.Step.NONE -> Unit
        }
    }

    fun holdCameraForSharedPlace() {
        host.holdStartCamera()
        keepCameraUntil = android.os.SystemClock.elapsedRealtime() + 60_000L
    }
    // ---------------- end shared places ----------------
}
