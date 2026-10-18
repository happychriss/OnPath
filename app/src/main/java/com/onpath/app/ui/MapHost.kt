package com.onpath.app.ui

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
                // In plan mode a tap adds a route point (snapped onto a POI if one was hit, so
                // "route to this hut" just works), placed by the same smart insertion as any tap.
                // Otherwise – or while the route is hidden – a tapped POI opens its details.
                val poi = com.onpath.app.poi.findPoiAt(map, map.projection.toScreenLocation(ll))
                when {
                    vm.mode == Mode.PLAN && !vm.routeHidden -> {
                        val p = if (poi != null) GeoPoint(poi.lat, poi.lon) else GeoPoint(ll.latitude, ll.longitude)
                        vm.addPoint(p, ctrlRef[0]?.let { PointDragger.snapMeters(it) } ?: 30.0, onPoi = poi != null); true
                    }
                    poi != null -> { vm.selectedPoi = poi; true }
                    else -> false
                }
            }
            val dragger = PointDragger(mapView, map, vm) { ctrlRef[0] }
            map.addOnMapLongClickListener { ll -> dragger.onLongPress(ll) }
            @Suppress("ClickableViewAccessibility")
            mapView.setOnTouchListener { _, ev -> dragger.onTouch(ev) }
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
    LaunchedEffect(vm.settings.mapTheme) {
        if (loadedTheme[0] != null && loadedTheme[0] != vm.settings.mapTheme) {
            loadedTheme[0] = vm.settings.mapTheme
            reloadRef[0]?.invoke()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Theme of the loaded style and a way to reload it (set once the map is ready). */
private val loadedTheme = arrayOfNulls<String>(1)
private val reloadRef = arrayOfNulls<() -> Unit>(1)

/** Latest controller for listeners created before it existed. */
internal val ctrlRef = arrayOfNulls<MapController>(1)
