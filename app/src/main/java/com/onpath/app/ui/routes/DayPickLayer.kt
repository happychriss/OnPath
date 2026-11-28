package com.onpath.app.ui.routes

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.onpath.app.ui.AppViewModel
import com.onpath.app.ui.OverviewSnapshot
import com.onpath.strings.*

/**
 * The day sheet over My routes ([DayPickSlice], owner 2026-11-27): what MainActivity draws after the list. Back closes it.
 * The ⋮ keeps the route's actions of its card. (A day's own overview via › is gone, owner 2026-11-28 09:58.)
 */
@Composable
fun DayPickLayer(vm: AppViewModel, snapshot: OverviewSnapshot?) {
    val pick = vm.dayPick
    val ui = pick.ui ?: return
    var rename by remember(ui.tour.id) { mutableStateOf(false) }
    BackHandler { pick.close() }
    val t = ui.tour
    DayPickSheet(ui, pick.canRide, snapshot, onRide = { pick.ride(it) }, onClose = { pick.close() }) {
        com.onpath.app.ui.TourItemMenu(t, onRename = { rename = true }, onExport = { vm.importExport.exportTour(t) },
            onDelete = { pick.close(); vm.routesFlow.deleteTour(t) }, onCopy = { vm.save.requestCopy(t) }, onShare = { vm.routesGo.shareRoute(t) })
    }
    if (rename) com.onpath.app.ui.NameDialog(str(Res.string.routes_rename_planned), t.name, confirmLabel = str(Res.string.common_rename),
        onDone = { rename = false; vm.save.rename(t, it); pick.close() }, onDismiss = { rename = false })
}
