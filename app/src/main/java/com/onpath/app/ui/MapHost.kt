package com.onpath.app.ui

import android.graphics.PointF
import android.util.Log
import android.view.Gravity
import androidx.compose.runtime.Composable
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
                if (vm.mode == Mode.PLAN) { vm.addPoint(GeoPoint(ll.latitude, ll.longitude)); true } else false
            }
            map.addOnMapLongClickListener { ll ->
                val screen: PointF = map.projection.toScreenLocation(ll)
                val idx = ctrlRef[0]?.waypointAt(screen) ?: -1
                if (idx >= 0) { vm.removePoint(idx); true } else false
            }
            mapView.addOnDidFailLoadingMapListener { msg ->
                Log.w("OnPath", "style failed: $msg")
                if (!fallbackUsed) { fallbackUsed = true; load(StyleLoader.fallback(vm.dataFiles.dir)) }
            }
            load(StyleLoader.load(context, vm.dataFiles.dir))
        }
        onDispose { ctrlRef[0] = null }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Latest controller for listeners created before it existed. */
internal val ctrlRef = arrayOfNulls<MapController>(1)
