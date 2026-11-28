package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.MapGestures
import com.onpath.app.recording.GpsStatus
import com.onpath.app.domain.PinMenuItems
import com.onpath.app.poi.Poi
import com.onpath.app.poi.PoiGoal
import com.onpath.app.ui.search.shownMenuRows

/**
 * „Als Ziel“ in the place info (Chriss 2026-11-28 09:41, picture halten-lernen-abc (c)): exactly the hold menu's „Als
 * Ziel“ of the same place, no own way – the rows are built as `MapOverlays`' POI menu builds them ([PinMenuItems.of]),
 * [PinMenuItems.goal] picks the row, and the row's own action runs (the sheet closes first). Only in Plan where the plan
 * can be edited (Navigate, a shown track: null – the info stays read-only, as before).
 */
@Composable
fun poiGoal(vm: AppViewModel, poi: Poi, gps: GpsStatus): PoiGoal? {
    if (vm.tourSession.mode != AppMode.PLAN || !vm.canEditMap) return null
    val emptyPlan = vm.plan.points.isEmpty()
    val startHere = MapGestures.offersStartHere(touring = vm.recState.collectAsState().value != null)
    val fromMe = MapGestures.fromMeBlocked(gps, vm.tracker.location.value != null) == null
    val add = vm.placeMenus.poiAdd(poi)
    val rows = shownMenuRows(PinMenuItems.of(emptyPlan, startHere, vm.placeMenus.placeMenu(poi), pin = false, fromMe = fromMe))
    fun run(action: () -> Unit): () -> Unit = {
        vm.selectedPoi = null
        com.onpath.app.debug.Problems.trace("plan", "Place info: „Als Ziel“ (${poi.name ?: poi.kind})")
        action()
    }
    return when (PinMenuItems.goal(rows, fromMe, add.stop)) {
        PinMenuItems.Row.GO_HERE -> PoiGoal(str(Res.string.plan_go_here), run { vm.placeAdd.poiFromMyLocation(poi) })
        PinMenuItems.Row.ADD -> PoiGoal(add.label, run { vm.placeAdd.addPoiPoint(poi) })
        PinMenuItems.Row.NEW_DESTINATION -> PoiGoal(str(Res.string.plan_make_destination), run { vm.placeAdd.poiAsNewDestination(poi) })
        else -> null
    }
}
