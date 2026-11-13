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
import androidx.compose.material.icons.outlined.Flag
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
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
import com.onpath.app.domain.plan.RoutingSpot

/** "Meraner Hütte", else "Start" / "Stop 2" / "Destination". */
fun stopLabel(i: Int, count: Int, name: String?): String = name?.takeIf { it.isNotBlank() } ?: when {
    i == 0 -> Txt.s(Res.string.plan_point_start)
    i == count - 1 && count > 1 -> Txt.s(Res.string.plan_point_destination)
    else -> Txt.s(Res.string.plan_point_stop, i + 1)
}

/**
 * The hold menus of Plan mode (MapGestures): the POI menu of a held POI and the point menu of a held route point.
 * They follow the map while it pans / zooms ([cameraTick] changes on every camera move; read only where a menu, chip or
 * ring is anchored – nothing recomposes per camera frame while none shows) and close when their anchor
 * leaves the map, on a tap anywhere else or on Back – never by a timer. Nothing is drawn while the map isn't ready
 * or in Navigate.
 */
@Composable
fun MapOverlayHost(
    vm: AppViewModel, controller: MapController?, cameraTick: () -> Int, topPx: Int, bottomPx: Int,
    gps: com.onpath.app.recording.GpsStatus,
) {
    val c = controller ?: return
    if (!vm.canEditMap) return
    val ctx = LocalContext.current
    // during a tour a held spot is never a new start ("Hier starten"), only where to go (field 2026-11-07)
    val startHere = MapGestures.offersStartHere(touring = vm.recState.collectAsState().value != null)
    // the route for the point just added / moved is computed: a spinner around it (Bug 02), after a short delay
    RoutingSpot.shown(vm.routing, vm.routingSpot, vm.waypoints)?.let { p ->
        var late by remember(p) { mutableStateOf(false) }
        LaunchedEffect(p) { kotlinx.coroutines.delay(RoutingSpot.DELAY_MS); late = true }
        if (late) {
            cameraTick() // re-read the projection on every camera move
            c.toScreen(p)?.let { s -> RoutingRing(Offset(s.x, s.y)) }
        }
    }
    vm.firstPointMenu?.let { p ->
        Anchored(c, p, cameraTick, topPx, bottomPx, onGone = { com.onpath.app.debug.Problems.trace("plan", "First-point menu closed: held spot left the visible map (onGone)"); vm.firstPointMenu = null }) {
            FirstPointMenu(MapGestures.fromMeBlocked(gps, vm.tracker.location.value != null),
                onStartHere = { vm.startHere(p) }, onFromMe = { vm.routeFromMyLocation(p) }, startHere = startHere)
        }
    }
    // the found place's name chip "Name ›" under its pin (owner 2026-11-02; since 2026-11-13 the name only – the menu
    // itself opens with the pick): only where the plan can be edited – this host draws nothing in Navigate or while a
    // track is shown (the pin itself stays there, MapScreen hides it in Navigate); hidden while the pin's menu is open
    vm.searchMark?.takeIf { vm.poiMenu?.let(vm::isCandidate) != true }?.let { m ->
        cameraTick() // re-read the projection on every camera move
        c.toScreen(GeoPoint(m.lat, m.lon))?.let { s ->
            PinChipAt(Offset(s.x, s.y), topPx, bottomPx) { PinChip(m.title(), onClick = vm::openPinMenu) }
        }
    }
    vm.poiMenu?.let { poi ->
        val pin = vm.isCandidate(poi)
        val gone = { com.onpath.app.debug.Problems.trace("plan", "POI menu closed: POI left the visible map (onGone)"); vm.poiMenu = null }
        @Composable fun menu(card: Boolean) =
            PoiMenu(poi, card = card, address = if (card) vm.searchMark?.let { it.address ?: it.town } else null,
                modifier = if (card) Modifier.fillMaxWidth() else Modifier,
                emptyPlan = vm.waypoints.isEmpty(),
                startHere = startHere,
                fromMeBlocked = MapGestures.fromMeBlocked(gps, vm.tracker.location.value != null),
                onFromMe = { vm.poiFromMyLocation(poi) },
                onAdd = { vm.addPoiPoint(poi) },
                addLabel = vm.poiAddLabel(poi),
                menu = vm.placeMenu(poi),
                onAddStop = { vm.addPoiAsStop(poi) },
                onNewDestination = { vm.poiAsNewDestination(poi) },
                onGoogle = {
                    vm.closeMapOverlays()
                    com.onpath.app.poi.PoiIntents.openInGoogleMaps(ctx, poi)
                },
                onRemovePin = if (pin) { { vm.closeMapOverlays("pin removed"); vm.removePin("its menu: Remove pin") } } else null)
        if (pin) {
            // the found place (owner 2026-11-13, "A mit Ausweich B"): above the pin, the map moved down when needed – else
            // the same rows as a card at the bottom
            cameraTick() // re-read the projection on every camera move
            val s = c.toScreen(GeoPoint(poi.lat, poi.lon))
            if (s == null) LaunchedEffect(poi) { gone() }
            else PinMenuOverlay(poi, Offset(s.x, s.y), c.dpToPx(com.onpath.app.map.CandidateMarker.PIN_HEIGHT_DP), topPx, bottomPx,
                onGone = gone, onPan = c::panBy, mayPan = !vm.followMe) { menu(it) }
        } else {
            Anchored(c, GeoPoint(poi.lat, poi.lon), cameraTick, topPx, bottomPx, onGone = gone) { menu(false) }
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
    c: MapController, at: GeoPoint, cameraTick: () -> Int, topPx: Int, bottomPx: Int, onGone: () -> Unit,
    content: @Composable () -> Unit,
) {
    cameraTick() // re-read the projection on every camera move (only this menu recomposes)
    val s = c.toScreen(at) ?: return
    AnchoredOverlay(Offset(s.x, s.y), topPx, bottomPx, onGone, content = content)
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

/** Room above and below the visible pill so the touch target is ≥ 48 dp high (the card has no outer pad). */
private val PIN_CHIP_TOUCH_PAD = 0.dp

/**
 * The found place's name under its pin, "Name ›" (owner 2026-11-02; since 2026-11-13 the name only: the pick opens the
 * pin's menu itself, owner "A mit Ausweich B"): a small paper card, one ≥ 48 dp button that opens the pin's menu again
 * after Back / a tap elsewhere. Long names end in "…" ([PinChipRules.label]).
 */
@Composable
fun PinChip(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val desc = str(Res.string.plan_pin_chip_desc, name)
    Surface(modifier.warmShadow(OnPathShadow.LOW, OnPathShapes.Card), shape = OnPathShapes.Card, color = OnPathColors.Paper,
        contentColor = OnPathColors.Ink) {
        Row(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = desc; role = Role.Button; onClick { onClick(); true } }
            .padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(PinChipRules.label(name), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = OnPathColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnPathColors.Ink, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * The found place's menu ([PinMenuPlace], owner 2026-11-13): measured, then above the pin's head – the map moves down
 * first when that makes it fit ([onPan], once) – or under its tip, or, when it fits neither, the same rows as a card at
 * the bottom ([content] with `card = true`; the map moves up so the pin stays visible above it). Decided once per
 * opened menu ([key]): it never jumps between the two while the map moves. [tip] = the place (pixels in this box),
 * [pinPx] = the pin's height. [onGone] when the place leaves the box.
 */
@Composable
internal fun PinMenuOverlay(key: Any, tip: Offset, pinPx: Float, topPx: Int, bottomPx: Int, onGone: () -> Unit,
                            onPan: (Float) -> Unit, mayPan: Boolean = true, content: @Composable (card: Boolean) -> Unit) {
    val d = LocalDensity.current
    val margin = with(d) { 12.dp.roundToPx() }
    val gap = with(d) { PinMenuPlace.GAP_DP.dp.roundToPx() }
    val tipRoom = with(d) { PinMenuPlace.TIP_ROOM_DP.dp.roundToPx() }
    val maxW = with(d) { 320.dp.roundToPx() }
    // decided once per opened menu (a plain holder: written while measuring, never recomposes)
    val decided = remember(key) { arrayOfNulls<PinMenuPlace.Decision>(1) }
    var pan by remember(key) { mutableStateOf(0f) }
    LaunchedEffect(key, pan) { if (pan != 0f) onPan(pan) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth; val h = constraints.maxHeight
        if (!MapGestures.anchorVisible(tip.x, tip.y, w, h)) {
            LaunchedEffect(Unit) { onGone() }
            return@BoxWithConstraints
        }
        SubcomposeLayout(Modifier.fillMaxSize()) { cs ->
            val loose = cs.copy(minWidth = 0, minHeight = 0)
            val head = tip.y - pinPx
            val bottom = h - bottomPx
            fun measureMenu() = subcompose("menu") { Box { content(false) } }.first()
                .measure(loose.copy(maxWidth = minOf(maxW, w - 2 * margin)))
            // the menu's measured height decides – once per opened menu
            var menu: Placeable? = null
            val decision = decided[0] ?: measureMenu().let { m ->
                menu = m
                PinMenuPlace.decide(head, tip.y, m.height, topPx, bottom, gap, tipRoom, mayPan).also {
                    decided[0] = it
                    com.onpath.app.debug.Problems.trace("plan", "Found place menu: ${it.kind}" +
                        (if (it.panPx != 0) ", map moved ${it.panPx} px" else "") + " (menu ${m.height} px, map $topPx..$bottom px)")
                    if (it.panPx != 0) pan = it.panPx.toFloat()
                }
            }
            if (decision.kind == PinMenuPlace.Kind.CARD) {
                val card = subcompose("card") {
                    Box(Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) { content(true) }
                }.first().measure(loose)
                // the pin stays visible above the card (once)
                if (mayPan && pan == 0f) PinMenuPlace.cardPan(head, tip.y, h - card.height, topPx, tipRoom).takeIf { it != 0 }?.let { pan = it.toFloat() }
                return@SubcomposeLayout layout(w, h) { card.place((w - card.width) / 2, h - card.height) }
            }
            val m = menu ?: measureMenu()
            val x = (tip.x - m.width / 2f).toInt().coerceIn(margin, maxOf(margin, w - margin - m.width))
            val y = if (decision.kind == PinMenuPlace.Kind.BELOW) (tip.y + gap).toInt()
                // above the head; while the map still moves it may reach over the top bar, never over the pin
                else (head - gap - m.height).toInt().coerceAtLeast(margin)
            layout(w, h) { m.place(x, y) }
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
fun FirstPointMenu(fromMeBlocked: String?, onStartHere: () -> Unit, onFromMe: () -> Unit, modifier: Modifier = Modifier,
                   /** false during a tour ([MapGestures.offersStartHere]): only "Hierhin". */
                   startHere: Boolean = true) {
    MenuSurface(modifier) {
        Text(str(Res.string.plan_new_route), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (startHere) HoldMenuItem(Icons.Outlined.TripOrigin, str(Res.string.plan_start_here), onClick = onStartHere)
        HoldMenuItem(Icons.Outlined.MyLocation, str(Res.string.plan_go_here), enabled = fromMeBlocked == null,
            supporting = fromMeBlocked, onClick = onFromMe)
    }
}

/**
 * Menu of a held POI that is not a route point: its name + how to add it · "Open in Google Maps". With 2+ points the
 * entries of [menu] ([PlaceAddMenu], owner 2026-11-07): "Als Zwischenstopp" and/or "Als neues Ziel" in the order of the
 * geometry, a clear case's alternative behind "⋯ Mehr". With one point [addLabel] ("Set as destination"). On an empty
 * plan it asks like the first-point menu (owner): "Start here" · "Go here" · "Open in Google Maps".
 */
@Composable
fun PoiMenu(poi: Poi, onAdd: () -> Unit, onGoogle: () -> Unit, modifier: Modifier = Modifier,
            emptyPlan: Boolean = false, fromMeBlocked: String? = null, onFromMe: () -> Unit = {},
            /** false during a tour ([MapGestures.offersStartHere]): an empty plan offers only "Hierhin". */
            startHere: Boolean = true,
            addLabel: String = str(Res.string.search_card_set_destination),
            /** The found place's pin: its menu ends with "Nadel entfernen" (owner 2026-11-02); null = a map POI. */
            onRemovePin: (() -> Unit)? = null,
            menu: PlaceAddMenu.Menu? = null, onAddStop: () -> Unit = {}, onNewDestination: () -> Unit = {},
            /** The found place as a card at the bottom ([PinMenuOverlay], owner 2026-11-13): its address under the name. */
            address: String? = null,
            /** A full-width card at the bottom instead of a menu at the place. */
            card: Boolean = false) {
    val place = Place.fromPoi(poi)
    var more by remember(poi) { mutableStateOf(false) }
    MenuSurface(modifier, card) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
            Text(place.title(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(place.kindInfo.label + (place.ele?.let { " · $it m" } ?: ""), style = MaterialTheme.typography.bodySmall,
                color = Color(SearchKinds.argb(place.kind)), maxLines = 1)
            address?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // the rows in their order (PinMenuItems): the same for a hold and for a picked search result (owner 2026-11-13)
        for (r in PinMenuItems.of(emptyPlan, startHere, menu, pin = onRemovePin != null, more = more)) when (r) {
            PinMenuItems.Row.START_HERE -> HoldMenuItem(Icons.Outlined.TripOrigin, str(Res.string.plan_start_here), onClick = onAdd)
            PinMenuItems.Row.GO_HERE -> HoldMenuItem(Icons.Outlined.MyLocation, str(Res.string.plan_go_here), enabled = fromMeBlocked == null,
                supporting = fromMeBlocked, onClick = onFromMe)
            PinMenuItems.Row.ADD -> HoldMenuItem(Icons.Outlined.AddLocationAlt, addLabel, onClick = onAdd)
            PinMenuItems.Row.ADD_STOP -> HoldMenuItem(Icons.Outlined.AddLocationAlt, str(Res.string.plan_add_as_stop), onClick = onAddStop)
            PinMenuItems.Row.NEW_DESTINATION -> HoldMenuItem(Icons.Outlined.Flag, str(Res.string.plan_make_destination), onClick = onNewDestination)
            PinMenuItems.Row.GOOGLE -> HoldMenuItem(Icons.Outlined.Map, str(Res.string.plan_open_google_maps), onClick = onGoogle)
            PinMenuItems.Row.REMOVE_PIN -> HoldMenuItem(Icons.Outlined.WrongLocation, str(Res.string.plan_remove_pin), onClick = { onRemovePin?.invoke() })
            // the alternative a clear case hides is never lost (owner 11:30: "solange wir auf der sicheren Seite sind")
            PinMenuItems.Row.MORE -> HoldMenuItem(Icons.Outlined.MoreHoriz, str(Res.string.common_more), onClick = { more = true })
        }
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
            // two lines at most: a large font wraps "Als Zwischenstopp" / "In Google Maps öffnen", never cuts it (2026-11-13)
            Text(label, color = color, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            supporting?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MenuSurface(modifier: Modifier, card: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        modifier.widthIn(min = 220.dp).warmShadow(OnPathShadow.HIGH, MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column((if (card) Modifier.fillMaxWidth() else Modifier.width(IntrinsicSize.Max)).padding(bottom = 4.dp)) { content() }
    }
}

