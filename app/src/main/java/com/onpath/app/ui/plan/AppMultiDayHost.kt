package com.onpath.app.ui.plan

import com.onpath.app.backup.Backups
import com.onpath.app.cells.CellId
import com.onpath.app.cells.MapCells
import com.onpath.app.data.Tour
import com.onpath.app.domain.AppMode
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.search.RouteNamer
import com.onpath.app.ui.AppViewModel
import com.onpath.app.ui.EndNamer
import com.onpath.app.ui.MapDataFlow
import com.onpath.app.ui.TourMapAreas

/**
 * The app's [MultiDayHost] (rebuild phase 2 piece 20): each member hands the call to the view model member (or the map data flow
 * [mapData], a lazy the view model keeps private) that the slice's old body called – the Android edge of the multi-day slice
 * (`Backups`, the offline index, the map data). The slice itself knows neither this class nor the view model.
 */
class AppMultiDayHost(private val vm: AppViewModel, private val mapData: () -> MapDataFlow) : MultiDayHost {
    override val mode: AppMode get() = vm.tourSession.mode
    override val route: PlannedRoute? get() = vm.route
    override val touring: Boolean get() = vm.recState.value != null
    override val cells: MapCells get() = vm.cells
    override fun placeNameOf(p: GeoPoint): String? = vm.searchFlow.stopName(p)
    override fun reopenTour(t: Tour) = vm.openTour(t)
    override fun reloadTourList() = vm.routesFlow.refreshTours()
    override fun showSaveProblem(text: String) { vm.save.problem = text }
    override fun tourChanged(id: Long) = Backups.tourChanged(vm.getApplication(), id)
    override fun syncChanged(why: String) = vm.sync.changed(why)
    override suspend fun endNamer(): EndNamer {
        val index = vm.search.offlineIndex()
        return EndNamer { p -> RouteNamer.label(p, RouteNamer.placesNear(index, listOf(p))) }
    }
    override fun tourMaps(line: List<GeoPoint>): TourMapAreas? = mapData().tourMaps(line)?.let { TourMapAreas(it.all, it.missing, it.bytes) }
    override fun loadTourMaps(want: List<CellId>, line: List<GeoPoint>, wifiWait: Boolean) { mapData().loadTourMaps(want, line, wifiWait) }
}
