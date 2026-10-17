package com.onpath.app.ui

import android.content.Intent
import android.location.Location
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.map.MapController
import com.onpath.app.recording.GpsStatus
import com.onpath.app.recording.LocationTracker
import com.onpath.app.recording.RecState
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** The home screen: full-screen map, status on top, one bottom panel. */
@Composable
fun MapScreen(
    vm: AppViewModel,
    onRequestLocation: () -> Unit,
    onRecord: () -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val topInsetPx = WindowInsets.statusBars.getTop(density)
    var controller by remember { mutableStateOf<MapController?>(null) }
    var panelHeightPx by remember { mutableIntStateOf(0) }
    var scale by remember { mutableStateOf<Pair<String, Dp>?>(null) }
    var showActivity by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }

    val rec by vm.recState.collectAsState()
    val location by vm.tracker.location.collectAsState()

    // GPS chip status, refreshed every second (age of the last fix matters).
    val gps by produceState(GpsStatus.SEARCHING to null as String?, vm.tracker) {
        while (true) {
            val st = vm.tracker.status()
            val loc = vm.tracker.location.value
            val detail = when {
                loc == null -> null
                st == GpsStatus.SEARCHING -> "Last fix ${ageText(loc)}"
                loc.hasAccuracy() -> "± ${loc.accuracy.toInt()} m"
                else -> null
            }
            value = st to detail
            delay(1000)
        }
    }

    Box(Modifier.fillMaxSize()) {
        MapHost(
            vm = vm,
            topInsetPx = topInsetPx,
            onReady = { controller = it },
            onCamera = { map -> scale = computeScale(map, density.density) },
            modifier = Modifier.fillMaxSize(),
        )

        // ---- sync state -> map ----
        val c = controller
        if (c != null) {
            LaunchedEffect(c, vm.route) { c.setPlanned(if (vm.mode == Mode.PLAN) vm.route else null) }
            LaunchedEffect(c) { snapshotFlow { vm.waypoints.toList() to vm.mode }.collect { (w, m) -> c.setWaypoints(if (m == Mode.PLAN) w else emptyList()) } }
            LaunchedEffect(c) { snapshotFlow { vm.mode }.collect { c.setPlanned(if (it == Mode.PLAN) vm.route else null) } }
            LaunchedEffect(c, vm.shown, vm.followLine) {
                c.setTrack(MapController.SRC_GPX, vm.shown?.segments ?: listOfNotNull(vm.followLine))
            }
            LaunchedEffect(c, rec?.track?.sumOf { it.size }) { c.setTrack(MapController.SRC_REC, rec?.track ?: emptyList()) }
            LaunchedEffect(c, vm.highlight) { c.setHighlight(vm.highlight) }
            LaunchedEffect(c, vm.activity, vm.overlayOn) { c.setOverlay(vm.activity, vm.overlayOn) }
            LaunchedEffect(c, location) { location?.let { c.updateLocation(it) } }
            LaunchedEffect(c, vm.followMe) { c.setFollow(vm.followMe) { vm.followMe = false } }
            LaunchedEffect(c, vm.events.size) {
                val pending = vm.events.toList()
                vm.events.clear()
                pending.forEach { e ->
                    when (e) {
                        is UiEvent.Fit -> c.fitTo(e.points, panelHeightPx)
                        is UiEvent.Share -> runCatching { context.startActivity(e.intent) }
                    }
                }
            }
        } else {
            // share events must also work before the map is ready
            LaunchedEffect(vm.events.size) {
                vm.events.filterIsInstance<UiEvent.Share>().forEach { runCatching { context.startActivity(it.intent) } }
                vm.events.removeAll { it is UiEvent.Share }
            }
        }

        // ---- top: status + activity ----
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            GpsChip(gps.first, gps.second, onClick = {
                when (gps.first) {
                    GpsStatus.OFF -> runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    GpsStatus.NO_PERMISSION -> onRequestLocation()
                    else -> vm.followMe = true
                }
            })
            Spacer(Modifier.weight(1f))
            ActivityChip(vm.activity, vm.sac, onClick = { showActivity = true })
        }

        // ---- bottom: scale/attribution/FAB + panel ----
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    scale?.let { (label, width) -> ScaleBar(label, width) }
                    Text("© OpenStreetMap contributors, © OpenMapTiles, Terrarium (Mapzen/AWS)", fontSize = 10.sp, color = Color(0xFF333333),
                        modifier = Modifier.padding(top = 4.dp).background(Color(0xB3FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
                }
                FloatingActionButton(
                    onClick = {
                        if (vm.tracker.location.value == null) {
                            if (gps.first == GpsStatus.NO_PERMISSION) onRequestLocation() else vm.message = "Searching GPS…"
                        } else vm.followMe = true
                    },
                    containerColor = Color.White,
                    contentColor = if (vm.followMe) OnPathColors.Route else Color(0xFF333333),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.size(60.dp),
                ) { Icon(if (vm.followMe) Icons.Filled.GpsFixed else Icons.Filled.GpsNotFixed, "Center on my position") }
            }
            Box(Modifier.onSizeChanged { panelHeightPx = it.height }) {
                BottomContent(vm, rec, gps.first, onRecord, onImport,
                    onSave = { showSave = true }, onStop = { confirmStop = true })
            }
        }
    }

    if (showActivity) ActivityDialog(
        vm.activity, vm.sac, vm.overlayOn,
        onSelect = { vm.selectActivity(it) }, onSac = { vm.selectSac(it) }, onOverlay = { vm.overlayOn = it },
        onDismiss = { showActivity = false },
    )
    if (showSave) NameDialog("Save route", vm.defaultName("Route"), onDone = { vm.saveRoute(it); showSave = false }, onDismiss = { showSave = false })
    if (confirmStop) ConfirmStopDialog(onStop = { confirmStop = false; vm.stopRecording() }, onDismiss = { confirmStop = false })
}

@Composable
private fun BottomContent(
    vm: AppViewModel, rec: RecState?, gps: GpsStatus,
    onRecord: () -> Unit, onImport: () -> Unit, onSave: () -> Unit, onStop: () -> Unit,
) {
    val shown = vm.shown
    when {
        rec != null && vm.mode != Mode.PLAN && shown == null -> RecordPanel(
            rec, gps, onPause = { vm.pauseRecording(true) }, onResume = { vm.pauseRecording(false) }, onStop = onStop,
        )
        vm.mode == Mode.PLAN -> PlanPanel(
            pointCount = vm.waypoints.size, route = vm.route, routing = vm.routing, error = vm.routeError,
            canUndo = vm.canUndo, onUndo = vm::undoStep, onClear = vm::clearPlan, onFromMe = vm::startAtMyPosition,
            onSave = onSave, onShare = vm::exportPlanned,
            onGo = {
                vm.followPlanned(); vm.closePlanning()
                if (rec == null) onRecord()
            },
            onClose = vm::closePlanning, onDeletePoint = vm::removePoint, onHighlight = { vm.highlight = it },
        )
        shown != null -> TrackPanel(
            shown, isFollowed = vm.followName == shown.name, recording = rec != null,
            onFollow = {
                vm.followShown()
                if (rec == null) onRecord()
                vm.closeShown()
            },
            onExport = vm::exportShown,
            onClose = vm::closeShown, onHighlight = { vm.highlight = it },
        )
        else -> BrowseBar(
            onPlan = vm::startPlanning, onRecord = onRecord,
            onTours = { vm.refreshTours(); vm.showTours = true },
            followName = vm.followName, onStopFollow = vm::clearFollow,
        )
    }
}

@Composable
private fun ScaleBar(label: String, width: Dp) {
    Column(Modifier.background(Color(0xB3FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text(label, fontSize = 10.sp, color = Color(0xFF222222))
        Box(Modifier.width(width).height(3.dp).background(Color(0xFF222222)))
    }
}

private fun ageText(loc: Location): String {
    val s = LocationTracker.ageSeconds(loc)
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60} min ago"
        else -> "${s / 3600} h ago"
    }
}

/** Nice round scale bar length (max ~100dp). */
private fun computeScale(map: org.maplibre.android.maps.MapLibreMap, density: Float): Pair<String, Dp>? = try {
    val lat = map.cameraPosition.target?.latitude ?: 0.0
    val mPerPx = map.projection.getMetersPerPixelAtLatitude(lat)
    val maxM = mPerPx * 100 * density
    val pow10 = 10.0.pow(floor(log10(maxM)))
    val nice = listOf(5.0, 2.0, 1.0).map { it * pow10 }.first { it <= maxM }
    val label = if (nice >= 1000) "${(nice / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }} km" else "${nice.toInt()} m"
    label to (nice / mPerPx / density).dp
} catch (_: Exception) { null }
