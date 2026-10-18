package com.onpath.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.delay

/** How long the Google bubble stays when nothing is tapped. */
const val POI_BUBBLE_MS = 8000L

/** "Meraner Hütte", else "Start" / "Stop 2" / "Destination". */
fun stopLabel(i: Int, count: Int, name: String?): String = name?.takeIf { it.isNotBlank() } ?: when {
    i == 0 -> "Start"
    i == count - 1 && count > 1 -> "Destination"
    else -> "Stop ${i + 1}"
}

/**
 * The small map overlays of Plan mode (MapGestures): the Google bubble of a tapped POI and the delete overlay of
 * a tapped marker. They follow the map while it pans / zooms ([cameraTick] changes on every camera move) and close
 * when their anchor leaves the map. Nothing is drawn while the map isn't ready or in Navigate.
 */
@Composable
fun MapOverlayHost(vm: AppViewModel, controller: MapController?, cameraTick: Int, topPx: Int, bottomPx: Int) {
    val c = controller ?: return
    if (!vm.canEditMap) return
    val ctx = LocalContext.current
    vm.poiBubble?.let { poi ->
        LaunchedEffect(poi.id) { delay(POI_BUBBLE_MS); if (vm.poiBubble?.id == poi.id) vm.poiBubble = null }
        Anchored(c, GeoPoint(poi.lat, poi.lon), cameraTick, topPx, bottomPx, onGone = { vm.poiBubble = null }) {
            PoiGoogleBubble(poi, onGoogle = {
                vm.poiBubble = null
                com.onpath.app.ui.search.openGoogle(ctx, PoiLinks.googleMapsUrl(poi.name, poi.lat, poi.lon))
            })
        }
    }
    vm.pointMenu?.let { i ->
        val p = vm.waypoints.getOrNull(i)
        if (p == null) { LaunchedEffect(i) { vm.pointMenu = null }; return@let }
        Anchored(c, p, cameraTick, topPx, bottomPx, onGone = { vm.pointMenu = null }) {
            PointDeleteOverlay(i, vm.waypoints.size, vm.stopName(p),
                onDelete = { vm.deletePointFromOverlay(i) }, onCancel = { vm.pointMenu = null }, onEndHere = { vm.endRouteAt(i) })
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

/** Light bubble for a POI tapped on the map: its name + one action, "Open in Google Maps". */
@Composable
fun PoiGoogleBubble(poi: Poi, onGoogle: () -> Unit, modifier: Modifier = Modifier) {
    val place = Place.fromPoi(poi)
    val de = Poi.isGerman()
    OverlaySurface(modifier) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp)) {
            Text(place.title(de), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 8.dp))
            Text(place.kindInfo.label(de) + (place.ele?.let { " · $it m" } ?: ""), style = MaterialTheme.typography.bodySmall,
                color = Color(SearchKinds.argb(place.kind)), maxLines = 1)
            TextButton(onClick = onGoogle, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Map, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (de) "In Google Maps öffnen" else "Open in Google Maps", fontSize = 16.sp)
            }
        }
    }
}

/**
 * Small overlay next to a tapped marker: its number + name, "Delete point" and "Cancel"; for a stop in the middle
 * also "End route here" (fixes a wrong guess of the smart insertion later).
 */
@Composable
fun PointDeleteOverlay(index: Int, count: Int, name: String?, onDelete: () -> Unit, onCancel: () -> Unit,
                       onEndHere: () -> Unit = {}, modifier: Modifier = Modifier) {
    OverlaySurface(modifier) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(stopColor(index, count)), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Text(stopLabel(index, count, name), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp)) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel", fontSize = 16.sp) }
                FilledTonalButton(
                    onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                ) {
                    Icon(Icons.Outlined.Delete, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete point", fontSize = 16.sp)
                }
            }
            if (index in 1 until count - 1) {
                TextButton(onClick = onEndHere, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Flag, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("End route here", fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun OverlaySurface(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier.widthIn(min = 180.dp),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 8.dp,
        content = content,
    )
}
