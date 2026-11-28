package com.onpath.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.MapGestures
import com.onpath.app.poi.Poi
import com.onpath.app.recording.GpsStatus
import com.onpath.app.routing.GeoPoint

/**
 * A place's planning rows ([PoiMenu], [com.onpath.app.domain.PinMenuItems.of]) with their actions – the one builder for
 * the hold menu on the map and the place info's list (Chriss 2026-11-28 10:02, picture ort-info-aktionen): every row does
 * exactly the same in both. [rowsOnly] = the info's list (no menu surface / head, the first row tinted); [before] runs
 * first (the info closes its sheet).
 */
@Composable
fun PlaceMenuFor(vm: AppViewModel, poi: Poi, gps: GpsStatus, startHere: Boolean, card: Boolean = false,
                 onRemovePin: (() -> Unit)? = null, rowsOnly: Boolean = false, before: () -> Unit = {}) {
    val ctx = LocalContext.current
    val add = vm.placeMenus.poiAdd(poi)
    fun act(f: () -> Unit): () -> Unit = { before(); f() }
    PoiMenu(poi, card = card, address = if (card) vm.searchFlow.searchMark?.let { it.address ?: it.town } else null,
        modifier = if (card || rowsOnly) Modifier.fillMaxWidth() else Modifier,
        emptyPlan = vm.plan.points.isEmpty(),
        startHere = startHere,
        fromMeBlocked = MapGestures.fromMeBlocked(gps, vm.tracker.location.value != null),
        onFromMe = act { vm.placeAdd.poiFromMyLocation(poi) },
        onAdd = act { vm.placeAdd.addPoiPoint(poi) },
        addLabel = add.label, addIsStop = add.stop,
        menu = vm.placeMenus.placeMenu(poi),
        onAddStop = act { vm.placeAdd.addPoiAsStop(poi) },
        onNewDestination = act { vm.placeAdd.poiAsNewDestination(poi) },
        favourite = vm.keptPlaces.isFavouritePlace(poi),
        onFavourite = { vm.keptPlaces.toggleFavouritePlace(poi) }, // the star stays where it was tapped
        onGoogle = act {
            vm.placeMenus.closeMapOverlays()
            com.onpath.app.poi.PoiIntents.openInGoogleMaps(ctx, poi)
        },
        onRemovePin = onRemovePin?.let { act(it) },
        onRoundTrip = if (vm.suggest.on) act { vm.placeMenus.closeMapOverlays("round trip from a place"); vm.suggest.open(GeoPoint(poi.lat, poi.lon), vm.planRoute.activity, vm.planRoute.sac) } else null,
        rowsOnly = rowsOnly, tintFirst = rowsOnly)
}

/**
 * The place info's planning list (Chriss 2026-11-28 10:02): in Plan where the plan can be edited, the hold menu's rows of
 * this place ([PlaceMenuFor]); Navigate / a shown track: null – the info stays read-only with Google Maps alone.
 */
@Composable
fun placeInfoActions(vm: AppViewModel, poi: Poi, gps: GpsStatus): (@Composable () -> Unit)? {
    if (vm.tourSession.mode != AppMode.PLAN || !vm.canEditMap) return null
    val startHere = MapGestures.offersStartHere(touring = vm.recState.collectAsState().value != null)
    return { PlaceMenuFor(vm, poi, gps, startHere, rowsOnly = true, before = {
        vm.selectedPoi = null
        com.onpath.app.debug.Problems.trace("plan", "Place info: a planning row (${poi.name ?: poi.kind})")
    }) }
}
