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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.TripOrigin
import androidx.compose.material.icons.outlined.WrongLocation
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.offset
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
    // the route for the point just added / moved is computed: a spinner around it (Bug 02), after a short delay
    RoutingSpot.shown(vm.routing, vm.routingSpot, vm.waypoints)?.let { p ->
        var late by remember(p) { mutableStateOf(false) }
        LaunchedEffect(p) { kotlinx.coroutines.delay(RoutingSpot.DELAY_MS); late = true }
        @Suppress("UNUSED_EXPRESSION") cameraTick // re-read the projection on every camera move
        if (late) c.toScreen(p)?.let { s -> RoutingRing(Offset(s.x, s.y)) }
    }
    vm.firstPointMenu?.let { p ->
        Anchored(c, p, cameraTick, topPx, bottomPx, onGone = { com.onpath.app.debug.Problems.trace("plan", "First-point menu closed: held spot left the visible map (onGone)"); vm.firstPointMenu = null }) {
            FirstPointMenu(MapGestures.fromMeBlocked(gps, vm.tracker.location.value != null),
                onStartHere = { vm.startHere(p) }, onFromMe = { vm.routeFromMyLocation(p) })
        }
    }
    // the found place's name chip under its pin (owner 2026-11-02): only where the plan can be edited – this host draws
    // nothing in Navigate or while a track is shown (the pin itself stays there, MapScreen hides it in Navigate)
    vm.searchMark?.let { m ->
        @Suppress("UNUSED_EXPRESSION") cameraTick // re-read the projection on every camera move
        c.toScreen(GeoPoint(m.lat, m.lon))?.let { s ->
            val empty = vm.waypoints.isEmpty()
            val blocked = MapGestures.fromMeBlocked(gps, vm.tracker.location.value != null)
            PinChipAt(Offset(s.x, s.y), topPx, bottomPx) {
                // "+ Hinzufügen" adds the place where it fits; on an empty plan "Hierhin" (from here) – without a position
                // the menu opens instead, it says why "Hierhin" waits (searching GPS)
                PinChip(m.title(), str(if (empty) Res.string.plan_go_here else Res.string.plan_pin_add), emptyPlan = empty,
                    onAction = { if (!empty) vm.addPoiPoint(m.toPoi()) else if (blocked == null) vm.poiFromMyLocation(m.toPoi()) else vm.openPinMenu() },
                    onMore = vm::openPinMenu)
            }
        }
    }
    vm.poiMenu?.let { poi ->
        val pin = vm.isCandidate(poi)
        // the pin's menu goes above the pin's head, not over it
        val lift = if (pin) c.dpToPx(com.onpath.app.map.CandidateMarker.PIN_HEIGHT_DP) else 0f
        // no room above: under the name chip, never over the pin (owner 2026-11-02, the empty plan's 4-line menu)
        val drop = if (pin) c.dpToPx(PIN_CHIP_BELOW_DP) else 0f
        Anchored(c, GeoPoint(poi.lat, poi.lon), cameraTick, topPx, bottomPx, liftPx = lift, dropPx = drop, onGone = { com.onpath.app.debug.Problems.trace("plan", "POI menu closed: POI left the visible map (onGone)"); vm.poiMenu = null }) {
            PoiMenu(poi,
                emptyPlan = vm.waypoints.isEmpty(),
                fromMeBlocked = MapGestures.fromMeBlocked(gps, vm.tracker.location.value != null),
                onFromMe = { vm.poiFromMyLocation(poi) },
                onAdd = { vm.addPoiPoint(poi) },
                addLabel = vm.poiAddLabel(poi),
                onGoogle = {
                    vm.closeMapOverlays()
                    com.onpath.app.poi.PoiIntents.openInGoogleMaps(ctx, poi)
                },
                onRemovePin = if (pin) { { vm.closeMapOverlays("pin removed"); vm.removePin("its menu: Remove pin") } } else null)
        }
    }
    vm.pointMenuTarget?.let { t ->
        // the point the menu was opened for, wherever it is in the plan now (PointMenuTarget)
        val i = vm.pointMenu
        val p = i?.let { vm.waypoints.getOrNull(it) }
        if (i == null || p == null) { LaunchedEffect(t) { vm.closePointMenu("its point is no longer in the plan") }; return@let }
        val name = vm.stopName(p)
        Anchored(c, p, cameraTick, topPx, bottomPx, onGone = { vm.closePointMenu("point left the visible map (onGone)") }) {
            PointMenu(i, vm.waypoints.size, name, roundTrip = vm.isRoundTrip(), loopStart = vm.loopStart() ?: 0) { a ->
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

/**
 * The spinner of [RoutingSpot]: a ring turning around the route point at [center] (pixels in this box) while its route
 * is computed. Not a touch target: map gestures pass through.
 */
@Composable
fun RoutingRing(center: Offset, stillProgress: Float? = null) {
    val d = LocalDensity.current
    val r = with(d) { 21.dp.roundToPx() }
    val label = com.onpath.app.ui.PlanStatus.CALCULATING
    val m = Modifier
        .offset { androidx.compose.ui.unit.IntOffset(center.x.toInt() - r, center.y.toInt() - r) }
        .size(42.dp)
        .semantics { contentDescription = label }
    val color = com.onpath.app.ui.OnPathColors.PlanAccent // computing a route is planning: blue
    val track = Color.White.copy(alpha = 0.85f)
    Box(Modifier.fillMaxSize()) {
        // [stillProgress]: a fixed arc for screenshots (the turning ring has no arc in its first frame)
        if (stillProgress != null) androidx.compose.material3.CircularProgressIndicator(progress = { stillProgress }, modifier = m,
            color = color, trackColor = track, strokeWidth = 4.dp)
        else androidx.compose.material3.CircularProgressIndicator(modifier = m, color = color, trackColor = track, strokeWidth = 4.dp)
    }
}

@Composable
private fun Anchored(
    c: MapController, at: GeoPoint, cameraTick: Int, topPx: Int, bottomPx: Int, onGone: () -> Unit, liftPx: Float = 0f,
    dropPx: Float = 0f, content: @Composable () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") cameraTick // re-read the projection on every camera move
    val s = c.toScreen(at) ?: return
    AnchoredOverlay(Offset(s.x, s.y - liftPx), topPx, bottomPx, onGone, belowY = s.y + dropPx, loose = dropPx > 0f, content = content)
}

/** The found place's name chip (owner 2026-11-02), pure rules. */
object PinChipRules {
    /** Longer names end in "…" after this many characters (the chip stays small on the map). */
    const val MAX_CHARS = 20
    /** Gap between the place (the pin's tip) and the chip's visible top, dp. */
    const val GAP_DP = 6

    fun label(name: String): String {
        val n = name.trim()
        return if (n.length <= MAX_CHARS) n else n.take(MAX_CHARS - 1).trimEnd() + "…"
    }

    /**
     * Where the chip box ([w] × [h], its visible pill centred in it, [touchPad] above and below) goes for a place at
     * ([ax], [ay]): centred under the tip, kept inside [left]..[right] and above [bottom]; below [top] or off the map it
     * is not shown (null) – the pin stays.
     */
    fun place(ax: Float, ay: Float, w: Int, h: Int, touchPad: Int, gap: Int, left: Int, right: Int, top: Int, bottom: Int): Pair<Int, Int>? {
        if (ax < left - w || ax > right + w) return null
        val y = (ay + gap - touchPad).toInt()
        if (y + h > bottom || y < top) return null
        val x = (ax - w / 2f).toInt().coerceIn(left, maxOf(left, right - w))
        return x to y
    }
}

/** Places the chip under the pin's tip at [anchor] (pixels in this box), following [PinChipRules.place]. */
@Composable
internal fun PinChipAt(anchor: Offset, topPx: Int, bottomPx: Int, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    val margin = with(d) { 12.dp.roundToPx() }
    val gap = with(d) { PinChipRules.GAP_DP.dp.roundToPx() }
    val pad = with(d) { PIN_CHIP_TOUCH_PAD.roundToPx() }
    Layout(content = { Box { content() } }, modifier = Modifier.fillMaxSize()) { ms, cs ->
        val w = cs.maxWidth; val h = cs.maxHeight
        val pl = ms.first().measure(cs.copy(minWidth = 0, minHeight = 0, maxWidth = w - 2 * margin))
        val at = PinChipRules.place(anchor.x, anchor.y, pl.width, pl.height, pad, gap, margin, w - margin, topPx, h - bottomPx)
        layout(w, h) { at?.let { (x, y) -> pl.place(x, y) } }
    }
}

/** From the pin's tip to below its card (gap + the card's height): where the menu goes when not above. */
internal const val PIN_CHIP_BELOW_DP = 112f

/** Room above and below the visible pill so the touch target is ≥ 48 dp high (the card has no outer pad). */
private val PIN_CHIP_TOUCH_PAD = 0.dp

/**
 * The found place under its pin (owner 2026-11-04, design B "Kleine Karte mit Aktion"): a small white card – the name,
 * below it the most common action as a blue button ([action]: "+ Hinzufügen" – owner 2026-11-05, #26; on an empty plan "Hierhin") and "⋯ Mehr"
 * ([onMore]: the pin's menu – the one a hold on the pin opens: start here / Google Maps / remove pin). One tap does it,
 * nothing to look for. Both buttons are ≥ 48 dp touch targets; long names end in "…" ([PinChipRules.label]).
 */
@Composable
fun PinChip(name: String, action: String, onAction: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier,
            emptyPlan: Boolean = false) {
    Surface(modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), color = OnPathColors.CardWhite,
        contentColor = OnPathColors.Ink, shadowElevation = 3.dp) {
        Column(Modifier.widthIn(min = 200.dp).width(IntrinsicSize.Max).padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 3.dp)) {
            Text(PinChipRules.label(name), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = OnPathColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp))
            Row(Modifier.fillMaxWidth()) {
                PinCardButton(action, if (emptyPlan) Icons.Outlined.MyLocation else Icons.Outlined.Add, OnPathColors.ActionEdit,
                    OnPathColors.CardWhite, str(Res.string.plan_pin_action_desc, action, name), onAction, Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                PinCardButton(str(Res.string.plan_pin_more), Icons.Outlined.MoreHoriz, OnPathColors.PlanTint, OnPathColors.Ink,
                    str(Res.string.plan_pin_chip_desc, name), onMore, Modifier.weight(1f))
            }
        }
    }
}

/** One of the card's two buttons: a 36 dp pill in a 48 dp touch box. */
@Composable
private fun PinCardButton(label: String, icon: ImageVector, bg: Color, fg: Color, desc: String, onClick: () -> Unit,
                          modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
        .clearAndSetSemantics { contentDescription = desc; role = Role.Button; onClick { onClick(); true } },
        contentAlignment = Alignment.Center) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(CircleShape).background(bg, CircleShape)
            .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = fg, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Places [content] next to [anchor] (pixels in this box): centred above it, below when there is no room, always
 * inside the map area between [topPx] and the bottom minus [bottomPx]. [onGone] when the anchor leaves the box.
 */
@Composable
fun AnchoredOverlay(anchor: Offset, topPx: Int, bottomPx: Int, onGone: () -> Unit = {}, belowY: Float = anchor.y,
                    /** The pin's menu: may reach over the top bar / the sheet rather than cover the pin. */
                    loose: Boolean = false, content: @Composable () -> Unit) {
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
            val (x, y) = MapGestures.placeOverlay(anchor.x, anchor.y, pl.width, pl.height, margin, topPx, w - margin, h - bottomPx, gap, belowY,
                looseTop = margin.takeIf { loose }, looseBottom = (h - margin).takeIf { loose })
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
 * Menu of a held POI that is not a route point: its name + what adding it does ("Set as destination", "Add as
 * stop 2", …) · "Open in Google Maps". On an empty plan
 * it asks like the first-point menu (owner): "Start here" · "Go here" · "Open in Google Maps".
 */
@Composable
fun PoiMenu(poi: Poi, onAdd: () -> Unit, onGoogle: () -> Unit, modifier: Modifier = Modifier,
            emptyPlan: Boolean = false, fromMeBlocked: String? = null, onFromMe: () -> Unit = {},
            addLabel: String = str(Res.string.search_card_set_destination),
            /** The found place's pin: its menu ends with "Nadel entfernen" (owner 2026-11-02); null = a map POI. */
            onRemovePin: (() -> Unit)? = null) {
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
            HoldMenuItem(Icons.Outlined.AddLocationAlt, addLabel, onClick = onAdd)
        }
        HoldMenuItem(Icons.Outlined.Map, str(Res.string.plan_open_google_maps), onClick = onGoogle)
        onRemovePin?.let { HoldMenuItem(Icons.Outlined.WrongLocation, str(Res.string.plan_remove_pin), onClick = it) }
    }
}

/**
 * Menu of a held route point: its number + name as a small title, then only the options that make sense
 * there ([MapGestures.pointMenu]): Make starting point · End route here · Open in Google Maps · Delete.
 */
@Composable
fun PointMenu(index: Int, count: Int, name: String?, modifier: Modifier = Modifier, roundTrip: Boolean = false,
              loopStart: Int = 0, onAction: (PointAction) -> Unit) {
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
        for (a in MapGestures.pointMenu(index, count, isPlace = !name.isNullOrBlank(), roundTrip = roundTrip, loopStart = loopStart)) when (a) {
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

