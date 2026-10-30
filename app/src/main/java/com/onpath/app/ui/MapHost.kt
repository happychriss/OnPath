package com.onpath.app.ui

import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import android.view.MotionEvent
import kotlin.math.hypot
import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.onpath.app.map.MapController
import com.onpath.app.map.MapMemory
import com.onpath.app.map.StyleLoader
import com.onpath.app.routing.GeoPoint
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import com.onpath.app.debug.Problems
import org.maplibre.android.maps.Style

/**
 * MapLibre MapView inside Compose with correct lifecycle forwarding.
 * Calls [onReady] once the style is loaded and the app layers exist.
 */
@Composable
fun MapHost(
    vm: AppViewModel,
    topInsetPx: Int,
    onReady: (MapController) -> Unit,
    onCamera: (MapLibreMap) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    /** This host's own map state: what it shows, its reload (another activity's host may live at the same time). */
    val host = remember { MapHostView() }
    /** This host's controller (touch handling never uses another host's). */
    val myCtrl = remember { arrayOfNulls<MapController>(1) }
    /** Theme of this host's loaded style. */
    val myTheme = remember { arrayOfNulls<String>(1) }
    // No frame-rate cap (owner): the map runs at the display's full rate while you look at it.
    // Power is saved where nothing is seen – screen off, pocket, background (docs/design/battery-audit.md).
    // Plan mode: only a short hold (HOLD_MS) does something, so stray taps never change the plan.
    val hold = remember { HoldTracker() }
    val holdAt = remember { mutableStateOf<Offset?>(null) }
    val holdBuzz = remember { mutableStateOf(MapGestures.Buzz.NONE) }

    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { mapView.onStart(); started = true; vm.onReturnToApp() }
                Lifecycle.Event.ON_RESUME -> { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> { mapView.onStop(); started = false; vm.onLeaveApp() }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        // Android says the app is hidden or memory runs low: free the map's tile caches (MapMemory)
        val trim = object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (!MapMemory.freesCaches(level)) return
                Problems.trace("map", "Map tile caches freed (trim level $level), native heap " +
                    "${android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576} MB before")
                mapView.onLowMemory()
            }
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                Problems.trace("map", "Map tile caches freed (low memory)")
                mapView.onLowMemory()
            }
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
        }
        context.applicationContext.registerComponentCallbacks(trim)
        onDispose {
            context.applicationContext.unregisterComponentCallbacks(trim)
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    DisposableEffect(mapView) {
        mapView.getMapAsync { map ->
            val (lat, lon, zoom) = vm.initialCamera()
            map.cameraPosition = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).build()
            map.setMinZoomPreference(3.0)
            map.setMaxZoomPreference(18.0)
            map.uiSettings.apply {
                isCompassEnabled = false // our compass is in the map controls (MapFooter), always shown
                isLogoEnabled = false
                isAttributionEnabled = false
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = false
            }
            var fallbackUsed = false
            fun styleJson() = StyleLoader.load(context, vm.dataFiles.dir, vm.settings.mapTheme, vm.dataFiles.mapDir(),
                vm.detailedAreas(), vm.devToolsShown, vm.packageAreas())
            fun setStyle(json: String) {
                val loadedFrom = StyleLoader.lastMapDir
                host.loading(loadedFrom ?: vm.dataFiles.dir)
                val t0 = android.os.SystemClock.elapsedRealtime()
                val n = ++styleLoads
                // asked and done are both in the event log (a load that never finishes is visible there)
                com.onpath.app.debug.Problems.trace("map", "Style load #$n asked (${loadedFrom?.name ?: "data folder"})")
                map.setStyle(Style.Builder().fromJson(json)) { style ->
                    // each load in the event log with the native heap (it can grow per reload)
                    com.onpath.app.debug.Problems.trace("map", "Style load #$n (${loadedFrom?.name ?: "data folder"}): " +
                        "${android.os.SystemClock.elapsedRealtime() - t0} ms, native heap " +
                        "${android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576} MB")
                    com.onpath.app.debug.Health.noteStyleLoad(n, loadedFrom?.name, MapMemory.geoJsonBytes(style))
                    vm.onMapStyleLoaded(loadedFrom)
                    try {
                        val c = MapController(context, map, style)
                        myCtrl[0] = c
                        ctrlRef.set(host, c)
                        onReady(c)
                    } catch (e: Exception) {
                        com.onpath.app.debug.Problems.error("map", "Map setup failed after the style loaded (app layers missing)", e)
                    }
                }
            }
            // A reload: the old style's file GeoJSON (the POIs' native index) is emptied and freed with a frame or two
            // before the new style is set – else MapLibre holds it beside the new one (MapMemory). A newer load during
            // that wait wins: the older one is then dropped (it would replace the newer style).
            var loadSeq = 0
            fun load(json: String) {
                val seq = ++loadSeq
                val old = myCtrl[0]?.style
                val freed = old?.let { MapMemory.releaseFileGeoJson(it) } ?: 0
                if (freed == 0) setStyle(json)
                else MapMemory.afterFrames(mapView, map) { if (!host.isGone && seq == loadSeq) setStyle(json) }
            }
            map.addOnCameraIdleListener {
                val c = map.cameraPosition
                c.target?.let { vm.saveCamera(it.latitude, it.longitude, c.zoom) }
                vm.mapGesture(false)
                onCamera(map)
            }
            map.addOnCameraMoveListener { onCamera(map) }
            // The user moved the map: the start camera (StartCamera) must not jump to the position any more.
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) { vm.userMovedMap = true; vm.mapGesture(true) }
            }
            // One rule table for every press (MapGestures, hold only): marker > POI > route line / empty map.
            // The route point under the finger, decided at finger-down (what the user aimed at, before anything can
            // move the map – else holding a point can add a new one instead of opening its menu).
            var downMarker = -1
            fun handle(ll: LatLng, press: MapGestures.Press): Boolean {
                val c = myCtrl[0]
                val screen = try { map.projection.toScreenLocation(ll) } catch (e: Exception) {
                    com.onpath.app.debug.Problems.warn("map", "Map press ignored: screen position unknown", e); return false
                }
                if (vm.routePeek) return true // peeking at the map: no edits
                // A hold works on this spot: the start camera must not jump to a late first GPS fix any more – that
                // would close the menu just opened ("held spot left the visible map").
                if (press == MapGestures.Press.HOLD) vm.userMovedMap = true
                val edit = vm.canEditMap
                val pts = vm.waypoints.toList()
                val toScreen: (GeoPoint) -> Pair<Float, Float>? = { p -> c?.toScreen(p)?.let { it.x to it.y } }
                val marker = if (!edit || c == null) -1 else MapGestures.markerUnderFinger(pts, toScreen, screen.x, screen.y,
                    press, downMarker, c.dpToPx(MapGestures.MARKER_RADIUS_DP))
                val dpOf: (Int) -> Int? = { i -> pts.getOrNull(i)?.let { p -> c?.toScreen(p)?.let { (kotlin.math.hypot(it.x - screen.x, it.y - screen.y) / c.dpToPx(1f)).toInt() } } }
                if (press == MapGestures.Press.HOLD && edit && c != null) {
                    // every hold in Plan goes into Diagnostics, each as its own line (core feature C1)
                    val near = pts.indices.minByOrNull { i -> c.toScreen(pts[i])?.let { kotlin.math.hypot(it.x - screen.x, it.y - screen.y) } ?: Float.MAX_VALUE }
                    com.onpath.app.debug.Problems.trace("map", "Hold: marker ${marker + 1}, finger-down ${downMarker + 1}, nearest point " +
                        "${near?.plus(1)} at ${near?.let(dpOf)} dp, ${pts.size} points (${c.drawnWaypointCount()} drawn), " +
                        "follow ${vm.followMe}, bearing ${map.cameraPosition.bearing.toInt()}°, menu open ${vm.mapOverlayOpen}")
                }
                // A marked search result (pin) counts as a POI: holding it opens the POI menu.
                val mark = vm.searchMark?.takeIf { m ->
                    c?.toScreen(GeoPoint(m.lat, m.lon))?.let { s -> kotlin.math.hypot(s.x - screen.x, s.y - screen.y) <= c.dpToPx(26f) } == true
                }
                val poi = if (marker >= 0) null else mark?.toPoi() ?: com.onpath.app.poi.findPoiAt(map, screen)
                // ~24 dp around the planned line counts as "on the line" (smart insertion into that leg).
                val snap = c?.let { it.dpToPx(24f) * it.metersPerPixel() } ?: 30.0
                val p = GeoPoint(ll.latitude, ll.longitude)
                return when (val a = MapGestures.decide(edit, press, vm.mapOverlayOpen, marker, poi != null, vm.waypoints.isEmpty())) {
                    is MapGestures.Action.PointMenu -> { vm.openPointMenu(a.index, dpOf(a.index)); true }
                    MapGestures.Action.PoiMenu -> { poi?.let(vm::openPoiMenu); true }
                    MapGestures.Action.FirstPointMenu -> { vm.openFirstPointMenu(p); true }
                    MapGestures.Action.AddPoint -> {
                        vm.closeMapOverlays("hold on the map (new point)")
                        com.onpath.app.debug.Problems.trace("plan", "New point by a hold: plan ${pts.size} → ${pts.size + 1}")
                        vm.addPoint(p, snap); true
                    }
                    MapGestures.Action.CloseMenu -> { vm.closeMapOverlays(if (press == MapGestures.Press.TAP) "tap outside" else "hold outside"); true }
                    MapGestures.Action.PoiDetails -> { vm.selectedPoi = poi; true }
                    MapGestures.Action.None -> {
                        // a short tap on the world map where there is no detailed map: ask to load it
                        if (press == MapGestures.Press.TAP && poi == null) vm.mapTapped(p)
                        edit
                    }
                }
            }
            map.addOnMapClickListener { ll ->
                // A hold of HOLD_MS (ring under the finger, vibration) ends as a click with lastHoldMs ≥ HOLD_MS.
                handle(ll, if (hold.lastHoldMs >= HOLD_MS) MapGestures.Press.HOLD else MapGestures.Press.TAP)
            }
            map.addOnMapLongClickListener { ll ->
                // A longer press is just a hold (no dragging).
                holdAt.value = null
                handle(ll, MapGestures.Press.HOLD)
            }
            @Suppress("ClickableViewAccessibility")
            mapView.setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) vm.onMapTouch()
                hold.track(ev, slopPx = 8 * density)
                if (!hold.pressing) holdAt.value = null
                else if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    // Decide once per press what an armed hold feels like: 2× on a route point, 1× elsewhere.
                    val at = PointF(hold.downX, hold.downY)
                    val edit = vm.canEditMap && !vm.routePeek
                    val marker = if (edit) myCtrl[0]?.waypointAt(at, vm.waypoints.toList()) ?: -1 else -1
                    downMarker = marker
                    val poi = edit && marker < 0 && com.onpath.app.poi.findPoiAt(map, at) != null
                    holdBuzz.value = MapGestures.buzz(edit, vm.mapOverlayOpen, marker, poi)
                    holdAt.value = if (holdBuzz.value != MapGestures.Buzz.NONE) Offset(hold.downX, hold.downY) else null
                }
                false
            }
            mapView.addOnDidFailLoadingMapListener { msg ->
                // StyleLoader.noteFailure reports it as an ERROR (the map falls back to the minimal style); a rejected
                // developer test style (dev/style.json) is a WARN and the normal style loads again
                if (StyleLoader.noteFailure(msg ?: "?")) {
                    load(styleJson())
                // a new generation of downloaded map cells that does not load: back to the previous one (R-16)
                } else if (vm.rollbackMapCells(StyleLoader.lastMapDir)) {
                    load(styleJson())
                } else if (!fallbackUsed) { fallbackUsed = true; load(StyleLoader.fallback(vm.dataFiles.mapDir())) }
            }
            myTheme[0] = vm.settings.mapTheme
            // this host's own reload (MapReload: two activities' hosts may live at once)
            host.ready { load(styleJson()) }
            load(styleJson())
        }
        // only clear what is ours: another (short-lived) map host must never switch off this one
        onDispose {
            host.disposed()
            ctrlRef.clear(host)
        }
    }

    // This host's map shows an older map store than the one on the phone (new areas joined while the reload could not
    // run): load it now – on every return to the app and on every new generation. Per host: another
    // activity's map having loaded the new data says nothing about this one.
    val generation = vm.cells.generation.collectAsState().value
    LaunchedEffect(generation, vm.appOnScreen) {
        if (!vm.appOnScreen || !host.behind(vm.dataFiles.mapDir())) return@LaunchedEffect
        kotlinx.coroutines.delay(MAP_CATCH_UP_MS) // the normal path (styleVersion, quiet gate) usually comes first
        val shown = host.shown
        if (!host.behind(vm.dataFiles.mapDir())) return@LaunchedEffect
        Problems.info("map", "Map was behind the data on the phone (${shown?.name} → ${vm.dataFiles.mapDir().name}); loading it now")
        host.reload()
    }

    // Switching the map theme in Settings reloads the style; the app layers are re-added in onReady.
    // An imported style file (Settings → Map data) or a new map store generation bumps styleVersion and reloads too.
    LaunchedEffect(vm.settings.mapTheme, vm.styleVersion) {
        // null: this host's map is not ready yet – its first load takes the current theme and data
        if (myTheme[0] != null && (myTheme[0] != vm.settings.mapTheme || vm.styleVersion > 0)) {
            myTheme[0] = vm.settings.mapTheme
            if (!host.reload()) Problems.warn("map", "Map style reload skipped: the map was not ready (version ${vm.styleVersion})")
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        HoldRing(holdAt.value, onArmed = { Haptics.play(mapView.context, holdBuzz.value, vm.settings.planVibrate) })
    }
}

/** The catch-up reload waits this long for the normal reload path first. */
private const val MAP_CATCH_UP_MS = 20_000L

/** How long a press must last in plan mode before it does anything. */
const val HOLD_MS = 300L

/** Follows one finger on the map: how long it was held without moving (for the plan-mode hold). */
private class HoldTracker {
    var downX = 0f; var downY = 0f
    private var downTime = 0L
    var pressing = false; private set
    var lastHoldMs = 0L; private set

    fun track(ev: MotionEvent, slopPx: Float) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; downTime = ev.eventTime; pressing = true; lastHoldMs = 0 }
            MotionEvent.ACTION_POINTER_DOWN -> pressing = false
            MotionEvent.ACTION_MOVE -> if (pressing && hypot(ev.x - downX, ev.y - downY) > slopPx) pressing = false
            MotionEvent.ACTION_UP -> { lastHoldMs = if (pressing) ev.eventTime - downTime else 0; pressing = false }
            MotionEvent.ACTION_CANCEL -> { lastHoldMs = 0; pressing = false }
        }
    }
}

/** Ring that fills under the finger during the hold; [onArmed] fires (the hold's vibration, PlanHaptics) when it is full. */
@Composable
private fun HoldRing(at: Offset?, onArmed: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(at) {
        progress.snapTo(0f)
        if (at != null) {
            progress.animateTo(1f, tween(HOLD_MS.toInt(), easing = LinearEasing))
            onArmed()
        }
    }
    if (at == null) return
    // a pan or quick tap ends within the first third: show nothing then, fade in after it
    val a = ((progress.value - 0.33f) / 0.27f).coerceIn(0f, 1f)
    if (a <= 0f) return
    val color = OnPathColors.Route.copy(alpha = a)
    Canvas(Modifier.fillMaxSize()) {
        val r = 26.dp.toPx()
        drawCircle(color.copy(alpha = 0.15f * a), r, at)
        drawArc(color, -90f, 360f * progress.value, useCenter = false,
            topLeft = Offset(at.x - r, at.y - r), size = Size(2 * r, 2 * r), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Style loads in this process (for the event log). */
private var styleLoads = 0

/** Latest host's controller (Diagnostics export); cleared only by the host that set it ([OwnedRef]). */
internal val ctrlRef = OwnedRef<MapController>()
