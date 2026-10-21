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
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import android.graphics.PointF
import android.util.Log
import androidx.compose.runtime.Composable
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
    // No frame-rate cap (owner 2026-10-20): the map runs at the display's full rate while you look at it.
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
        onDispose {
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
            fun load(json: String) {
                val loadedFrom = StyleLoader.lastMapDir
                map.setStyle(Style.Builder().fromJson(json)) { style ->
                    vm.onMapStyleLoaded(loadedFrom)
                    try {
                        val c = MapController(context, map, style)
                        ctrlRef[0] = c
                        onReady(c)
                    } catch (e: Exception) {
                        com.onpath.app.debug.Problems.error("map", "Map setup failed after the style loaded (app layers missing)", e)
                    }
                }
            }
            map.addOnCameraIdleListener {
                val c = map.cameraPosition
                c.target?.let { vm.saveCamera(it.latitude, it.longitude, c.zoom) }
                onCamera(map)
            }
            map.addOnCameraMoveListener { onCamera(map) }
            // The user moved the map: the start camera (StartCamera) must not jump to the position any more.
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) vm.userMovedMap = true
            }
            // One rule table for every press (MapGestures, hold only): marker > POI > route line / empty map.
            // The route point under the finger, decided at finger-down (what the user aimed at, before anything can
            // move the map – field test 2026-10-20: holding a point added a new one instead of opening its menu).
            var downMarker = -1
            var downNear = -1
            fun handle(ll: LatLng, press: MapGestures.Press): Boolean {
                val c = ctrlRef[0]
                val screen = try { map.projection.toScreenLocation(ll) } catch (e: Exception) {
                    com.onpath.app.debug.Problems.warn("map", "Map press ignored: screen position unknown", e); return false
                }
                if (vm.routePeek) return true // peeking at the map: no edits
                val edit = vm.canEditMap
                val pts = vm.waypoints.toList()
                val toScreen: (GeoPoint) -> Pair<Float, Float>? = { p -> c?.toScreen(p)?.let { it.x to it.y } }
                val marker = if (!edit || c == null) -1 else MapGestures.markerUnderFinger(pts, toScreen, screen.x, screen.y,
                    press, downMarker, c.dpToPx(MapGestures.MARKER_RADIUS_DP))
                // 48–80 dp from a point: ask (its options / new point here) instead of adding a point next to it
                val nearMarker = if (!edit || c == null || marker >= 0) -1 else MapGestures.markerUnderFinger(pts, toScreen,
                    screen.x, screen.y, press, downNear, c.dpToPx(MapGestures.NEAR_RADIUS_DP))
                val dpOf: (Int) -> Int? = { i -> pts.getOrNull(i)?.let { p -> c?.toScreen(p)?.let { (kotlin.math.hypot(it.x - screen.x, it.y - screen.y) / c.dpToPx(1f)).toInt() } } }
                if (press == MapGestures.Press.HOLD && edit && c != null) {
                    // every hold in Plan goes into Diagnostics, each as its own line (core feature C1 – field 0.8.3 / 0.8.5)
                    val near = pts.indices.minByOrNull { i -> c.toScreen(pts[i])?.let { kotlin.math.hypot(it.x - screen.x, it.y - screen.y) } ?: Float.MAX_VALUE }
                    com.onpath.app.debug.Problems.trace("map", "Hold: marker ${marker + 1}, near ${nearMarker + 1}, finger-down ${downMarker + 1}, nearest point " +
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
                return when (val a = MapGestures.decide(edit, press, vm.mapOverlayOpen, marker, poi != null, vm.waypoints.isEmpty(), nearMarker)) {
                    is MapGestures.Action.PointMenu -> { vm.openPointMenu(a.index, dpOf(a.index)); true }
                    is MapGestures.Action.NearPointChoice -> { vm.openNearChoice(a.index, p, snap, dpOf(a.index)); true }
                    MapGestures.Action.PoiMenu -> { poi?.let(vm::openPoiMenu); true }
                    MapGestures.Action.FirstPointMenu -> { vm.openFirstPointMenu(p); true }
                    MapGestures.Action.AddPoint -> {
                        vm.closeMapOverlays("hold on the map (new point)")
                        com.onpath.app.debug.Problems.trace("plan", "New point by a hold: plan ${pts.size} → ${pts.size + 1}")
                        vm.addPoint(p, snap); true
                    }
                    MapGestures.Action.CloseMenu -> { vm.closeMapOverlays(if (press == MapGestures.Press.TAP) "tap outside" else "hold outside"); true }
                    MapGestures.Action.PoiDetails -> { vm.selectedPoi = poi; true }
                    MapGestures.Action.None -> edit
                }
            }
            map.addOnMapClickListener { ll ->
                // A hold of HOLD_MS (ring under the finger, vibration) ends as a click with lastHoldMs ≥ HOLD_MS.
                handle(ll, if (hold.lastHoldMs >= HOLD_MS) MapGestures.Press.HOLD else MapGestures.Press.TAP)
            }
            map.addOnMapLongClickListener { ll ->
                // A longer press is just a hold (no dragging any more).
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
                    val marker = if (edit) ctrlRef[0]?.waypointAt(at, vm.waypoints.toList()) ?: -1 else -1
                    downMarker = marker
                    downNear = if (edit && marker < 0) ctrlRef[0]?.waypointAt(at, vm.waypoints.toList(), MapGestures.NEAR_RADIUS_DP, log = false) ?: -1 else -1
                    val poi = edit && marker < 0 && com.onpath.app.poi.findPoiAt(map, at) != null
                    holdBuzz.value = MapGestures.buzz(edit, vm.mapOverlayOpen, marker, poi)
                    holdAt.value = if (holdBuzz.value != MapGestures.Buzz.NONE) Offset(hold.downX, hold.downY) else null
                }
                false
            }
            mapView.addOnDidFailLoadingMapListener { msg ->
                // StyleLoader.noteFailure reports it as an ERROR (the map falls back to the minimal style)
                StyleLoader.noteFailure(msg ?: "?")
                // a new generation of downloaded map cells that does not load: back to the previous one (R-16)
                if (vm.rollbackMapCells(StyleLoader.lastMapDir)) {
                    load(StyleLoader.load(context, vm.dataFiles.dir, vm.settings.mapTheme, vm.dataFiles.mapDir()))
                } else if (!fallbackUsed) { fallbackUsed = true; load(StyleLoader.fallback(vm.dataFiles.mapDir())) }
            }
            loadedTheme[0] = vm.settings.mapTheme
            reloadRef[0] = { load(StyleLoader.load(context, vm.dataFiles.dir, vm.settings.mapTheme, vm.dataFiles.mapDir())) }
            load(StyleLoader.load(context, vm.dataFiles.dir, vm.settings.mapTheme, vm.dataFiles.mapDir()))
        }
        onDispose { ctrlRef[0] = null; reloadRef[0] = null }
    }

    // Switching the map theme in Settings reloads the style; the app layers are re-added in onReady.
    // An imported style file (Settings → Map data) bumps styleVersion and reloads too.
    LaunchedEffect(vm.settings.mapTheme, vm.styleVersion) {
        if (loadedTheme[0] != null && (loadedTheme[0] != vm.settings.mapTheme || vm.styleVersion > 0)) {
            loadedTheme[0] = vm.settings.mapTheme
            reloadRef[0]?.invoke()
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        HoldRing(holdAt.value, onArmed = { buzz(mapView, holdBuzz.value) })
    }
}

/** How long a press must last in plan mode before it does anything. */
const val HOLD_MS = 300L

/**
 * Vibration when a hold is armed: [MapGestures.Buzz.ONE] one short pulse, [MapGestures.Buzz.TWO] a double pulse
 * (route point). Falls back to the view's haptic feedback when there is no vibrator. Never throws.
 */
private fun buzz(view: View, b: MapGestures.Buzz) {
    if (b == MapGestures.Buzz.NONE) return
    val done = runCatching {
        val ctx = view.context
        val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
        if (v == null || !v.hasVibrator()) false
        else { v.vibrate(VibrationEffect.createWaveform(MapGestures.pattern(b), -1)); true }
    }.onFailure { com.onpath.app.debug.Problems.info("ui", "Vibration failed, haptic feedback used instead", it) }.getOrDefault(false)
    if (!done) view.performHapticFeedback(
        if (b == MapGestures.Buzz.TWO) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CONTEXT_CLICK)
}

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

/** Ring that fills under the finger during the hold; [onArmed] fires (vibration) when it is full. */
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
    // a pan or quick tap ends within the first third: show nothing then, fade in after it (polish audit A1)
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

/** Theme of the loaded style and a way to reload it (set once the map is ready). */
private val loadedTheme = arrayOfNulls<String>(1)
private val reloadRef = arrayOfNulls<() -> Unit>(1)

/** Latest controller for listeners created before it existed. */
internal val ctrlRef = arrayOfNulls<MapController>(1)
