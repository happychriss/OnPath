package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.TripOrigin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.map.MapController
import com.onpath.app.poi.Poi
import com.onpath.app.poi.PoiLinks
import com.onpath.app.routing.GeoPoint
import com.onpath.app.search.Place
import com.onpath.app.search.SearchKinds
import com.onpath.app.ui.MapGestures.PointAction

/** "Meraner Hütte", else "Start" / "Stop 2" / "Destination". */
fun stopLabel(i: Int, count: Int, name: String?): String = name?.takeIf { it.isNotBlank() } ?: when {
    i == 0 -> Txt.s(Res.string.plan_point_start)
    i == count - 1 && count > 1 -> Txt.s(Res.string.plan_point_destination)
    else -> Txt.s(Res.string.plan_point_stop, i + 1)
}

/**
 * The hold menus of Plan mode (MapGestures): the POI menu of a held POI and the point menu of a held route point.
 * They follow the map while it pans / zooms ([cameraTick] changes on every camera move) and close when their anchor
 * leaves the map, on a tap anywhere else or on Back – never by a timer. Nothing is drawn while the map isn't ready
 * or in Navigate.
 */
@Composable
fun MapOverlayHost(
    vm: AppViewModel, controller: MapController?, cameraTick: Int, topPx: Int, bottomPx: Int,
    gps: com.onpath.app.recording.GpsStatus,
) {
    val c = controller ?: return
    if (!vm.canEditMap) return
    val ctx = LocalContext.current
    vm.firstPointMenu?.let { p ->
        Anchored(c, p, cameraTick, topPx, bottomPx, onGone = { com.onpath.app.debug.Problems.trace("plan", "First-point menu closed: held spot left the visible map (onGone)"); vm.firstPointMenu = null }) {
            FirstPointMenu(MapGestures.fromMeBlocked(gps, vm.tracker.location.value != null),
                onStartHere = { vm.startHere(p) }, onFromMe = { vm.routeFromMyLocation(p) })
        }
    }
    vm.poiMenu?.let { poi ->
        Anchored(c, GeoPoint(poi.lat, poi.lon), cameraTick, topPx, bottomPx, onGone = { com.onpath.app.debug.Problems.trace("plan", "POI menu closed: POI left the visible map (onGone)"); vm.poiMenu = null }) {
            PoiMenu(poi,
                emptyPlan = vm.waypoints.isEmpty(),
                fromMeBlocked = MapGestures.fromMeBlocked(gps, vm.tracker.location.value != null),
                onFromMe = { vm.poiFromMyLocation(poi) },
                onAdd = { vm.addPoiPoint(poi) },
                onGoogle = {
                    vm.closeMapOverlays()
                    com.onpath.app.poi.PoiIntents.openInGoogleMaps(ctx, poi)
                })
        }
    }
    vm.pointMenuTarget?.let { t ->
        // the point the menu was opened for, wherever it is in the plan now (PointMenuTarget)
        val i = vm.pointMenu
        val p = i?.let { vm.waypoints.getOrNull(it) }
        if (i == null || p == null) { LaunchedEffect(t) { vm.closePointMenu("its point is no longer in the plan") }; return@let }
        val name = vm.stopName(p)
        Anchored(c, p, cameraTick, topPx, bottomPx, onGone = { vm.closePointMenu("point left the visible map (onGone)") }) {
            PointMenu(i, vm.waypoints.size, name, roundTrip = vm.isRoundTrip()) { a ->
                when (a) {
                    PointAction.MAKE_START, PointAction.LOOP_START -> vm.makeStartFromMenu()
                    PointAction.GOOGLE -> vm.googleFromMenu()?.let { g ->
                        com.onpath.app.poi.PoiIntents.openGoogleMaps(ctx, PoiLinks.googleMaps(name, g.lat, g.lon))
                    }
                    PointAction.DELETE -> vm.deletePointFromMenu()
                }
            }
        }
    }
}

@Composable
private fun Anchored(
    c: MapController, at: GeoPoint, cameraTick: Int, topPx: Int, bottomPx: Int, onGone: () -> Unit,
    content: @Composable () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") cameraTick // re-read the projection on every camera move
    val s = c.toScreen(at) ?: return
    AnchoredOverlay(Offset(s.x, s.y), topPx, bottomPx, onGone, content)
}

/**
 * Places [content] next to [anchor] (pixels in this box): centred above it, below when there is no room, always
 * inside the map area between [topPx] and the bottom minus [bottomPx]. [onGone] when the anchor leaves the box.
 */
@Composable
fun AnchoredOverlay(anchor: Offset, topPx: Int, bottomPx: Int, onGone: () -> Unit = {}, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    val margin = with(d) { 12.dp.roundToPx() }
    val gap = with(d) { 22.dp.roundToPx() }
    val maxW = with(d) { 320.dp.roundToPx() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth; val h = constraints.maxHeight
        if (!MapGestures.anchorVisible(anchor.x, anchor.y, w, h)) {
            LaunchedEffect(Unit) { onGone() }
            return@BoxWithConstraints
        }
        Layout(content = { Box { content() } }, modifier = Modifier.fillMaxSize()) { measurables, cs ->
            val pl = measurables.first().measure(cs.copy(minWidth = 0, minHeight = 0, maxWidth = minOf(maxW, w - 2 * margin)))
            val (x, y) = MapGestures.placeOverlay(anchor.x, anchor.y, pl.width, pl.height, margin, topPx, w - margin, h - bottomPx, gap)
            layout(w, h) { pl.place(x, y) }
        }
    }
}

/**
 * First hold on an empty plan: "Start here" or "Go here" (this spot = destination). The second
 * one is disabled with a short reason ([fromMeBlocked], e.g. "Searching GPS…") while there is no usable position.
 */
@Composable
fun FirstPointMenu(fromMeBlocked: String?, onStartHere: () -> Unit, onFromMe: () -> Unit, modifier: Modifier = Modifier) {
    MenuSurface(modifier) {
        Text(str(Res.string.plan_new_route), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        HoldMenuItem(Icons.Outlined.TripOrigin, str(Res.string.plan_start_here), onClick = onStartHere)
        HoldMenuItem(Icons.Outlined.MyLocation, str(Res.string.plan_go_here), enabled = fromMeBlocked == null,
            supporting = fromMeBlocked, onClick = onFromMe)
    }
}

/**
 * Menu of a held POI that is not a route point: its name + "Add to route" · "Open in Google Maps". On an empty plan
 * it asks like the first-point menu (owner): "Start here" · "Go here" · "Open in Google Maps".
 */
@Composable
fun PoiMenu(poi: Poi, onAdd: () -> Unit, onGoogle: () -> Unit, modifier: Modifier = Modifier,
            emptyPlan: Boolean = false, fromMeBlocked: String? = null, onFromMe: () -> Unit = {}) {
    val place = Place.fromPoi(poi)
    val de = Poi.isGerman()
    MenuSurface(modifier) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
            Text(place.title(de), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(place.kindInfo.label(de) + (place.ele?.let { " · $it m" } ?: ""), style = MaterialTheme.typography.bodySmall,
                color = Color(SearchKinds.argb(place.kind)), maxLines = 1)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (emptyPlan) {
            HoldMenuItem(Icons.Outlined.TripOrigin, str(Res.string.plan_start_here), onClick = onAdd)
            HoldMenuItem(Icons.Outlined.MyLocation, str(Res.string.plan_go_here), enabled = fromMeBlocked == null,
                supporting = fromMeBlocked, onClick = onFromMe)
        } else {
            HoldMenuItem(Icons.Outlined.AddLocationAlt, str(Res.string.plan_add_to_route), onClick = onAdd)
        }
        HoldMenuItem(Icons.Outlined.Map, str(Res.string.plan_open_google_maps), onClick = onGoogle)
    }
}

/**
 * Menu of a held route point: its number + name as a small title, then only the options that make sense
 * there ([MapGestures.pointMenu]): Make starting point · End route here · Open in Google Maps · Delete.
 */
@Composable
fun PointMenu(index: Int, count: Int, name: String?, modifier: Modifier = Modifier, roundTrip: Boolean = false,
              onAction: (PointAction) -> Unit) {
    MenuSurface(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(stopColor(index, count)), contentAlignment = Alignment.Center) {
                Text("${index + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(stopLabel(index, count, name), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        for (a in MapGestures.pointMenu(index, count, isPlace = !name.isNullOrBlank(), roundTrip = roundTrip)) when (a) {
            // Say what happens: the dropped points come back with Undo.
            PointAction.MAKE_START -> HoldMenuItem(Icons.Outlined.TripOrigin, str(Res.string.plan_start_here),
                supporting = plural(Res.plurals.plan_removes_points, index, index)) { onAction(a) }
            PointAction.LOOP_START -> HoldMenuItem(Icons.Outlined.TripOrigin, str(Res.string.plan_start_finish_here),
                supporting = str(Res.string.plan_same_round_trip)) { onAction(a) }
            PointAction.GOOGLE -> HoldMenuItem(Icons.Outlined.Map, str(Res.string.plan_open_google_maps)) { onAction(a) }
            PointAction.DELETE -> HoldMenuItem(Icons.Outlined.Delete, str(Res.string.common_delete), danger = true) { onAction(a) }
        }
    }
}

/** One menu row: icon + label, ≥ 48 dp high, the whole row is the touch target. */
@Composable
private fun HoldMenuItem(
    icon: ImageVector, label: String, danger: Boolean = false, enabled: Boolean = true, supporting: String? = null,
    onClick: () -> Unit,
) {
    val base = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val color = if (enabled) base else base.copy(alpha = 0.38f)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(label, color = color, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            supporting?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MenuSurface(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier.widthIn(min = 220.dp),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 8.dp,
    ) {
        Column(Modifier.width(IntrinsicSize.Max).padding(bottom = 4.dp)) { content() }
    }
}

