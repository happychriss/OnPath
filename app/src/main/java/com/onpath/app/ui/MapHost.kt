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
import android.util.Log
import android.view.Gravity
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
    // Plan mode: a point is only added after a short hold (HOLD_MS), so stray taps don't add points.
    val hold = remember { HoldTracker() }
    val holdAt = remember { mutableStateOf<Offset?>(null) }

    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> { mapView.onStop(); started = false }
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
                isCompassEnabled = true
                compassGravity = Gravity.TOP or Gravity.END
                setCompassMargins(0, topInsetPx + (68 * density).toInt(), (14 * density).toInt(), 0)
                isLogoEnabled = false
                isAttributionEnabled = false
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = false
            }
            var fallbackUsed = false
            fun load(json: String) {
                map.setStyle(Style.Builder().fromJson(json)) { style ->
                    try {
                        val c = MapController(context, map, style)
                        ctrlRef[0] = c
                        onReady(c)
                    } catch (e: Exception) {
                        Log.e("OnPath", "map setup failed", e)
                    }
                }
            }
            map.addOnCameraIdleListener {
                val c = map.cameraPosition
                c.target?.let { vm.saveCamera(it.latitude, it.longitude, c.zoom) }
                onCamera(map)
            }
            map.addOnCameraMoveListener { onCamera(map) }
            map.addOnMapClickListener { ll ->
                // In plan mode a short hold (HOLD_MS, ring under the finger) adds a route point (smart
                // insertion); a quick tap does not, so stray taps don't add points. A tap on a POI opens its
                // card (add to route / Google Maps) without leaving planning; while a card is open, a tap on
                // the map only closes it. Otherwise – or while the route is hidden – a tapped POI opens its
                // details.
                val poi = com.onpath.app.poi.findPoiAt(map, map.projection.toScreenLocation(ll))
                when {
                    vm.mode == Mode.PLAN && !vm.routeHidden -> {
                        val snap = ctrlRef[0]?.let { PointDragger.snapMeters(it) } ?: 30.0
                        when {
                            poi != null -> vm.planPoi = poi
                            vm.planPoi != null -> vm.planPoi = null
                            hold.lastHoldMs >= HOLD_MS -> vm.addPoint(GeoPoint(ll.latitude, ll.longitude), snap)
                            !vm.holdHintShown -> { vm.holdHintShown = true; vm.message = "Hold briefly to add a point" }
                        }
                        true
                    }
                    poi != null -> { vm.selectedPoi = poi; true }
                    else -> false
                }
            }
            val dragger = PointDragger(mapView, map, vm) { ctrlRef[0] }
            map.addOnMapLongClickListener { ll ->
                holdAt.value = null
                // Long press drags a point or the route line; on empty map it adds a point like a short hold.
                dragger.onLongPress(ll) || (vm.mode == Mode.PLAN && !vm.routeHidden && vm.planPoi == null &&
                    com.onpath.app.poi.findPoiAt(map, map.projection.toScreenLocation(ll)) == null).also { add ->
                    if (add) vm.addPoint(GeoPoint(ll.latitude, ll.longitude), ctrlRef[0]?.let { PointDragger.snapMeters(it) } ?: 30.0)
                }
            }
            @Suppress("ClickableViewAccessibility")
            mapView.setOnTouchListener { _, ev ->
                val armable = vm.mode == Mode.PLAN && !vm.routeHidden && vm.planPoi == null
                hold.track(ev, slopPx = 8 * density)
                holdAt.value = when {
                    !armable || !hold.pressing -> null
                    // Decide once per press: no ring when the finger is on a POI (a tap opens its card).
                    ev.actionMasked == MotionEvent.ACTION_DOWN ->
                        if (com.onpath.app.poi.findPoiAt(map, PointF(hold.downX, hold.downY)) == null) Offset(hold.downX, hold.downY) else null
                    else -> holdAt.value
                }
                dragger.onTouch(ev)
            }
            mapView.addOnDidFailLoadingMapListener { msg ->
                Log.w("OnPath", "style failed: $msg")
                if (!fallbackUsed) { fallbackUsed = true; load(StyleLoader.fallback(vm.dataFiles.dir)) }
            }
            loadedTheme[0] = vm.settings.mapTheme
            reloadRef[0] = { load(StyleLoader.load(context, vm.dataFiles.dir, vm.settings.mapTheme)) }
            load(StyleLoader.load(context, vm.dataFiles.dir, vm.settings.mapTheme))
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
        HoldRing(holdAt.value, onArmed = {
            mapView.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        })
    }
}

/** How long a press must last in plan mode before it adds a point. */
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
    val color = OnPathColors.Route
    Canvas(Modifier.fillMaxSize()) {
        val r = 26.dp.toPx()
        drawCircle(color.copy(alpha = 0.15f), r, at)
        drawArc(color, -90f, 360f * progress.value, useCenter = false,
            topLeft = Offset(at.x - r, at.y - r), size = Size(2 * r, 2 * r), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Theme of the loaded style and a way to reload it (set once the map is ready). */
private val loadedTheme = arrayOfNulls<String>(1)
private val reloadRef = arrayOfNulls<() -> Unit>(1)

/** Latest controller for listeners created before it existed. */
internal val ctrlRef = arrayOfNulls<MapController>(1)
