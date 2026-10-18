package com.onpath.app.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.location.Location
import android.util.Log
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.Turn
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory as PF
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

/** Owns the app's own sources/layers on top of the base style and the position puck. */
class MapController(private val context: Context, val map: MapLibreMap, val style: Style) {

    companion object {
        const val ROUTE_COLOR = "#1E3FAE"     // deep blue, high contrast on green/beige
        const val GPX_COLOR = "#8E24AA"       // purple
        const val REC_COLOR = "#EF6C00"       // orange
        const val NAV_ORIGINAL_COLOR = "#8A8F98" // grey: the original route while a new one is followed
        const val SRC_PLANNED = "planned-route"
        const val SRC_GPX = "gpx-track"
        const val SRC_REC = "recorded-track"
        const val SRC_WPT = "waypoints"
        const val SRC_HL = "highlight"
        const val LAYER_WPT = "waypoints-icons"
        // --- Navigate: orientation + next-turn arrow (navigation agent) ---
        const val SRC_TURN = "nav-turn"
        const val LAYER_TURN = "nav-turn-arrow"
        const val SRC_MARK = "search-mark"
        /**
         * App layers hidden while the eye button is held: everything route-like, but not the own
         * position or the recorded track. New route layers (e.g. a grey original route) belong here.
         */
        // --- Re-routing while navigating (navigation/Navigator) ---
        /** Thin grey original line while a new route is followed (below the followed line). */
        const val SRC_NAV_ORIGINAL = "nav-original"
        /** Dashed preview of the proposed new route while the question is open. */
        const val SRC_NAV_PROPOSAL = "nav-proposal"
        /** Dashed straight guide from the position to the route (no way found / original kept). */
        const val SRC_NAV_GUIDE = "nav-guide"
        /** All re-routing layer ids (hidden by the eye button too). */
        val NAV_LAYERS = listOf(SRC_NAV_ORIGINAL, SRC_NAV_PROPOSAL, SRC_NAV_GUIDE)
        val ROUTE_OVERLAY_LAYERS = NAV_LAYERS + listOf(
            "planned-route-casing", "planned-route", "planned-straight-casing", "planned-straight",
            "$SRC_GPX-casing", SRC_GPX, "highlight-dot", "waypoints-pin", LAYER_WPT, LAYER_TURN,
            "planned-route-arrows", "$SRC_GPX-arrows",
        )
        private const val ARROW_IMAGE = "route-arrow"
        private const val IMG_TURN = "nav-turn-arrow"
        /** Map tilt in heading-up Navigate (degrees). 0 = flat; try 30–40 for a "navigation" look. */
        const val NAV_TILT_DEG = 0.0
        /** Heading-up: the position sits lower on screen so more of the way ahead is visible (share of height). */
        const val NAV_TOP_PADDING = 0.30
        /** Position puck in both modes: MapLibre's small blue dot + bearing triangle (not the big GPS puck). */
        private const val PUCK_MODE = RenderMode.COMPASS
    }

    /** Settings page → base style layers (POI filter, contours, hillshade). */
    val layers = LayerSettings(style)

    private val density = context.resources.displayMetrics.density
    private var locationReady = false
    private var markerImages = 0
    /** Compass + GPS bearing for heading-up; the sensor runs only while MapLibre listens (Navigate). */
    private val heading = HeadingEngine(context)
    private var onTrackingDismissed: (() -> Unit)? = null
    /** Camera mode we asked for while following (to notice when a gesture changed it). */
    private var wantedCameraMode = CameraMode.NONE
    private var wasNavigating = false

    init {
        addNavOriginal() // below the followed line
        addLine(SRC_GPX, GPX_COLOR, 5f, casing = true)
        addLine(SRC_REC, REC_COLOR, 4.5f, casing = true)
        addPlannedLayers()
        addDirectionArrows()
        style.addSource(GeoJsonSource(SRC_HL))
        style.addLayer(CircleLayer("highlight-dot", SRC_HL).withProperties(
            PF.circleRadius(7f), PF.circleColor("#FFFFFF"), PF.circleStrokeColor(ROUTE_COLOR), PF.circleStrokeWidth(4f),
        ))
        style.addSource(GeoJsonSource(SRC_WPT))
        // No ring for POI-pinned stops any more (owner): the only orange ring is the search mark.
        style.addLayer(SymbolLayer(LAYER_WPT, SRC_WPT).withProperties(
            PF.iconImage(Expression.get("icon")),
            PF.iconAllowOverlap(true), PF.iconIgnorePlacement(true),
        ))
        addNavOverlays()
        addTurnLayer()
        activateLocation()
    }

    private fun addLine(id: String, color: String, width: Float, casing: Boolean) {
        style.addSource(GeoJsonSource(id))
        if (casing) style.addLayer(LineLayer("$id-casing", id).withProperties(
            PF.lineColor("#FFFFFF"), PF.lineWidth(width + 4f),
            PF.lineJoin(Property.LINE_JOIN_ROUND), PF.lineCap(Property.LINE_CAP_ROUND),
        ))
        style.addLayer(LineLayer(id, id).withProperties(
            PF.lineColor(color), PF.lineWidth(width),
            PF.lineJoin(Property.LINE_JOIN_ROUND), PF.lineCap(Property.LINE_CAP_ROUND),
        ))
    }

    private fun addNavOriginal() {
        style.addSource(GeoJsonSource(SRC_NAV_ORIGINAL))
        style.addLayer(LineLayer(SRC_NAV_ORIGINAL, SRC_NAV_ORIGINAL).withProperties(
            PF.lineColor(NAV_ORIGINAL_COLOR), PF.lineWidth(3f), PF.lineOpacity(0.85f),
            PF.lineJoin(Property.LINE_JOIN_ROUND), PF.lineCap(Property.LINE_CAP_ROUND),
        ))
    }

    private fun addNavOverlays() {
        style.addSource(GeoJsonSource(SRC_NAV_PROPOSAL))
        style.addLayer(LineLayer(SRC_NAV_PROPOSAL, SRC_NAV_PROPOSAL).withProperties(
            PF.lineColor(ROUTE_COLOR), PF.lineWidth(5f), PF.lineDasharray(arrayOf(1.6f, 1.2f)),
            PF.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        style.addSource(GeoJsonSource(SRC_NAV_GUIDE))
        style.addLayer(LineLayer(SRC_NAV_GUIDE, SRC_NAV_GUIDE).withProperties(
            PF.lineColor("#C62828"), PF.lineWidth(3f), PF.lineDasharray(arrayOf(1f, 1.5f)), PF.lineOpacity(0.9f),
        ))
    }

    fun setNavOriginal(points: List<GeoPoint>?) = setTrack(SRC_NAV_ORIGINAL, listOfNotNull(points))

    /** How the [SRC_GPX] line looks (owner: one blue line – blue is the route you follow or will follow). */
    enum class TrackLook { TRACK, FOLLOW, FOLLOW_BEHIND }

    /**
     * TRACK = a shown GPX track (own purple colour); FOLLOW = the followed route (route blue, like the plan);
     * FOLLOW_BEHIND = followed route while the plan is being edited during a tour (thin grey under the blue plan).
     */
    fun setTrackLook(look: TrackLook) {
        val (color, width, casing) = when (look) {
            TrackLook.TRACK -> Triple(GPX_COLOR, 5f, true)
            TrackLook.FOLLOW -> Triple(ROUTE_COLOR, 6f, true)
            TrackLook.FOLLOW_BEHIND -> Triple(NAV_ORIGINAL_COLOR, 3f, false)
        }
        runCatching {
            style.getLayer(SRC_GPX)?.setProperties(PF.lineColor(color), PF.lineWidth(width))
            style.getLayer("$SRC_GPX-casing")?.setProperties(PF.lineWidth(width + 4f),
                PF.visibility(if (casing) Property.VISIBLE else Property.NONE))
            style.getLayer("$SRC_GPX-arrows")?.setProperties(
                PF.visibility(if (look == TrackLook.FOLLOW_BEHIND) Property.NONE else Property.VISIBLE))
        }
        applyRouteOverlaysVisibility() // keep the eye button's state
    }
    fun setNavProposal(points: List<GeoPoint>?) = setTrack(SRC_NAV_PROPOSAL, listOfNotNull(points))
    fun setNavGuide(from: GeoPoint?, to: GeoPoint?) =
        setTrack(SRC_NAV_GUIDE, if (from != null && to != null) listOf(listOf(from, to)) else emptyList())

    /** Routed legs solid, failed legs (straight fallback) dashed. */
    private fun addPlannedLayers() {
        style.addSource(GeoJsonSource(SRC_PLANNED))
        val routed = Expression.eq(Expression.get("straight"), Expression.literal(false))
        val straight = Expression.eq(Expression.get("straight"), Expression.literal(true))
        style.addLayer(LineLayer("planned-route-casing", SRC_PLANNED).withFilter(routed).withProperties(
            PF.lineColor("#FFFFFF"), PF.lineWidth(10f),
            PF.lineJoin(Property.LINE_JOIN_ROUND), PF.lineCap(Property.LINE_CAP_ROUND),
        ))
        style.addLayer(LineLayer("planned-route", SRC_PLANNED).withFilter(routed).withProperties(
            PF.lineColor(ROUTE_COLOR), PF.lineWidth(6f),
            PF.lineJoin(Property.LINE_JOIN_ROUND), PF.lineCap(Property.LINE_CAP_ROUND),
        ))
        style.addLayer(LineLayer("planned-straight-casing", SRC_PLANNED).withFilter(straight).withProperties(
            PF.lineColor("#FFFFFF"), PF.lineWidth(7f), PF.lineCap(Property.LINE_CAP_ROUND),
        ))
        style.addLayer(LineLayer("planned-straight", SRC_PLANNED).withFilter(straight).withProperties(
            PF.lineColor(ROUTE_COLOR), PF.lineWidth(4f), PF.lineDasharray(arrayOf(1.5f, 1.5f)),
        ))
    }

    /**
     * Small white chevrons along the planned route and the shown/followed track, pointing in the
     * direction of travel – so an out-and-back (same way back) is visible at a glance.
     */
    private fun addDirectionArrows() {
        try {
            style.addImage(ARROW_IMAGE, arrowBitmap())
            for ((id, src, filter) in listOf(
                Triple("planned-route-arrows", SRC_PLANNED, Expression.eq(Expression.get("straight"), Expression.literal(false))),
                Triple("$SRC_GPX-arrows", SRC_GPX, null),
            )) {
                val l = SymbolLayer(id, src).withProperties(
                    PF.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                    PF.symbolSpacing(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(12, 60f), Expression.stop(16, 90f))),
                    PF.iconImage(ARROW_IMAGE),
                    PF.iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(12, 0.55f), Expression.stop(16, 0.8f))),
                    PF.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PF.iconKeepUpright(false),
                    PF.iconAllowOverlap(true), PF.iconIgnorePlacement(true),
                )
                if (filter != null) l.setFilter(filter)
                style.addLayer(l)
            }
        } catch (e: Exception) {
            Log.w("OnPath", "direction arrows not added: $e")
        }
    }

    /** White chevron ">" (points along the line), drawn at screen density. */
    private fun arrowBitmap(): android.graphics.Bitmap {
        val s = (16 * density).toInt().coerceAtLeast(16)
        val bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val path = android.graphics.Path().apply {
            moveTo(s * 0.32f, s * 0.2f); lineTo(s * 0.72f, s * 0.5f); lineTo(s * 0.32f, s * 0.8f)
        }
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND; color = android.graphics.Color.WHITE; strokeWidth = s * 0.16f
        }
        c.drawPath(path, p)
        return bmp
    }

    @SuppressLint("MissingPermission")
    private fun activateLocation() {
        try {
            val lc = map.locationComponent
            val options = LocationComponentOptions.builder(context)
                .accuracyAlpha(0.18f)
                .enableStaleState(true)
                .staleStateTimeout(30_000)
                .build()
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(false)
                    .locationComponentOptions(options)
                    .build()
            )
            lc.setCompassEngine(heading)
            lc.isLocationComponentEnabled = true
            // Both modes: small blue dot with accuracy circle and a small direction triangle at its edge
            // (owner: "as earlier"; GPS mode drew the big white navigation puck). The direction comes from
            // HeadingEngine (compass standing, GPS bearing while moving).
            lc.renderMode = PUCK_MODE
            lc.cameraMode = CameraMode.NONE
            lc.addOnCameraTrackingChangedListener(object : org.maplibre.android.location.OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() { onTrackingDismissed?.invoke() }
                override fun onCameraTrackingChanged(currentMode: Int) {
                    // A rotate gesture turns compass tracking into plain tracking: treat as "not following"
                    // so "centre on me" brings the chosen orientation back.
                    if (wantedCameraMode != CameraMode.NONE && currentMode != wantedCameraMode) onTrackingDismissed?.invoke()
                }
            })
            locationReady = true
        } catch (e: Exception) {
            Log.w("OnPath", "Location component not available: $e")
        }
    }

    fun updateLocation(loc: Location) {
        if (!locationReady) return
        try { map.locationComponent.forceLocationUpdate(loc) } catch (_: Exception) { }
        heading.onLocation(loc)
    }

    /** Plan mode follow (north stays where the user left it). */
    fun setFollow(follow: Boolean, onDismissed: () -> Unit) = setCamera(follow, navigating = false, headingUp = false, onDismissed)

    /**
     * Camera and puck for both modes. Following: the camera tracks the position – in Navigate heading-up
     * (map turns with the walking direction, [HeadingEngine]) or north-up; in Plan without turning.
     * MapLibre ends following when the user pans ([onDismissed]); "centre on me" calls this again.
     * The puck is always the small dot with a direction triangle ([PUCK_MODE]); the compass sensor runs while
     * the map is visible (MapLibre releases it when the map stops).
     */
    fun setCamera(follow: Boolean, navigating: Boolean, headingUp: Boolean, onDismissed: () -> Unit) {
        if (!locationReady) return
        try {
            val lc = map.locationComponent
            onTrackingDismissed = null   // no callbacks for our own mode switches
            // our own compass in the map controls replaces MapLibre's in both modes
            map.uiSettings.isCompassEnabled = false
            val leavingNav = wasNavigating && !navigating
            wasNavigating = navigating
            if (follow) {
                val (mode, tilt, bearing) = when {
                    navigating && headingUp -> Triple(CameraMode.TRACKING_COMPASS, NAV_TILT_DEG, null)
                    navigating -> Triple(CameraMode.TRACKING_GPS_NORTH, 0.0, 0.0)
                    else -> Triple(CameraMode.TRACKING, if (leavingNav) 0.0 else null, if (leavingNav) 0.0 else null)
                }
                lc.renderMode = PUCK_MODE
                wantedCameraMode = mode
                val zoom = if (map.cameraPosition.zoom < 13) 15.0 else null
                lc.setCameraMode(mode, 750L, zoom, bearing, tilt, null)
                val top = if (navigating && headingUp) context.resources.displayMetrics.heightPixels * NAV_TOP_PADDING else 0.0
                lc.paddingWhileTracking(doubleArrayOf(0.0, top, 0.0, 0.0), 750L)
            } else {
                wantedCameraMode = CameraMode.NONE
                if (lc.cameraMode != CameraMode.NONE) lc.cameraMode = CameraMode.NONE
                lc.renderMode = PUCK_MODE
                if (leavingNav) resetNorth()
            }
            onTrackingDismissed = onDismissed
        } catch (e: Exception) {
            Log.w("OnPath", "camera mode failed: $e")
        }
    }

    private var routeOverlaysVisible = true

    /** Eye button: hide / show all [ROUTE_OVERLAY_LAYERS]. Never throws. */
    fun setRouteOverlaysVisible(visible: Boolean) {
        routeOverlaysVisible = visible
        applyRouteOverlaysVisibility()
    }

    /** Re-apply the eye state, e.g. after adding a route layer. */
    fun applyRouteOverlaysVisibility() {
        val v = PF.visibility(if (routeOverlaysVisible) Property.VISIBLE else Property.NONE)
        for (id in ROUTE_OVERLAY_LAYERS) runCatching { style.getLayer(id)?.setProperties(v) }
    }

    /**
     * App start in Plan: centre on the position once (no following, so the user can pan right away).
     * Zooms in only when the map is zoomed far out. Never throws.
     */
    fun centreOn(lat: Double, lon: Double) = runCatching {
        val zoom = map.cameraPosition.zoom.let { if (it < 13.0) 15.0 else it }
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom), 600)
    }

    /** Compass button in Plan: turn the map back to north up. */
    fun northUp() = runCatching { resetNorth() }

    /** Back in Plan: north up, flat, no tracking padding (editing on a turned map is confusing). */
    private fun resetNorth() {
        val cp = map.cameraPosition
        if (cp.bearing == 0.0 && cp.tilt == 0.0 && (cp.padding?.all { it == 0.0 } != false)) return
        map.animateCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder(cp).bearing(0.0).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build()), 600)
    }

    /** True while the compass sensor is registered (for tests/diagnostics). */
    val compassListening: Boolean get() = heading.isListening

    /** Next-turn arrow at [turn], pointing in its outgoing direction; null hides it. */
    fun setTurnArrow(turn: Turn?) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_TURN) ?: return
        if (turn == null) { src.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return }
        src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(turn.point.lon, turn.point.lat)).apply {
            addNumberProperty("rot", turn.bearingOut)
        })
    }

    private fun addTurnLayer() {
        style.addImage(IMG_TURN, turnArrow())
        style.addSource(GeoJsonSource(SRC_TURN))
        style.addLayer(SymbolLayer(LAYER_TURN, SRC_TURN).withProperties(
            PF.iconImage(IMG_TURN),
            PF.iconRotate(Expression.get("rot")),
            PF.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PF.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PF.iconAllowOverlap(true), PF.iconIgnorePlacement(true),
        ))
    }

    /** Round white badge with a route-blue arrow pointing up (north); rotated by the layer. */
    private fun turnArrow(): Bitmap {
        val px = (30 * density).toInt()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        bmp.density = context.resources.displayMetrics.densityDpi
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = px / 2f
        p.color = android.graphics.Color.parseColor(ROUTE_COLOR)
        c.drawCircle(r, r, r, p)
        p.color = android.graphics.Color.WHITE
        c.drawCircle(r, r, r - 2.5f * density, p)
        p.color = android.graphics.Color.parseColor(ROUTE_COLOR)
        val path = android.graphics.Path().apply {
            val u = px / 30f
            moveTo(r, 5 * u)               // tip
            lineTo(r + 9 * u, 15 * u)
            lineTo(r + 3.5f * u, 15 * u)
            lineTo(r + 3.5f * u, 24 * u)
            lineTo(r - 3.5f * u, 24 * u)
            lineTo(r - 3.5f * u, 15 * u)
            lineTo(r - 9 * u, 15 * u)
            close()
        }
        c.drawPath(path, p)
        return bmp
    }

    fun setOverlay(activity: Activity, enabled: Boolean) {
        val want = when (activity) {
            Activity.HIKE -> "routes-hike"
            Activity.MTB, Activity.EMTB -> "routes-mtb"
            else -> "routes-bike"
        }
        // Blocked-way marking follows the activity: walkers may use "access=no + foot=yes" ways, bikers
        // "access=no + bicycle=yes" ones (see tools/make_style.py access_restrictions).
        val walking = activity == Activity.HIKE
        val mtb = activity == Activity.MTB || activity == Activity.EMTB
        // Way quality (tools/make_style.py): T-labels (SAC) for hiking only, S-labels (mtb:scale) for MTB /
        // MTB e-bike only, loose-surface edges for every bike activity.
        for ((id, on) in listOf("access-no-tint-foot" to walking, "access-no-sign-foot" to walking,
                "access-no-tint-bike" to !walking, "access-no-sign-bike" to !walking,
                "trails-sac-label" to walking, "trails-mtb-label" to mtb,
                "surface-loose-track" to !walking, "surface-loose-path" to !walking)) {
            runCatching { style.getLayer(id)?.setProperties(PF.visibility(if (on) Property.VISIBLE else Property.NONE)) }
        }
        for (id in listOf("routes-hike", "routes-bike", "routes-mtb")) {
            val vis = PF.visibility(if (enabled && id == want) Property.VISIBLE else Property.NONE)
            // the band plus its major-route highlight (pink edges + name)
            for (suffix in listOf("", "-major", "-major-label")) {
                runCatching { style.getLayer("$id$suffix")?.setProperties(vis) }
            }
        }
    }

    fun setPlanned(route: PlannedRoute?) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_PLANNED) ?: return
        if (route == null) { src.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return }
        val features = route.legs.filter { it.points.size >= 2 }.map { leg ->
            Feature.fromGeometry(LineString.fromLngLats(leg.points.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                addBooleanProperty("straight", leg.straight)
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    fun setTrack(sourceId: String, segments: List<List<GeoPoint>>) {
        val src = style.getSourceAs<GeoJsonSource>(sourceId) ?: return
        val lines = segments.filter { it.size >= 2 }.map { seg -> seg.map { Point.fromLngLat(it.lon, it.lat) } }
        if (lines.isEmpty()) { src.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return }
        src.setGeoJson(Feature.fromGeometry(MultiLineString.fromLngLats(lines)))
    }

    fun setWaypoints(points: List<GeoPoint>, pinned: Set<Int> = emptySet()) {
        shownWaypoints = points.toList()
        val src = style.getSourceAs<GeoJsonSource>(SRC_WPT) ?: return
        ensureMarkerImages(points.size)
        val features = points.mapIndexed { i, p ->
            val kind = when { i == 0 -> "s"; i == points.lastIndex && points.size > 1 -> "e"; else -> "v" }
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                addStringProperty("icon", "wp-$kind-${i + 1}")
                addNumberProperty("idx", i)
                addBooleanProperty("pinned", i in pinned)
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** Metres per screen pixel at the camera centre (for "tap near the line" tolerances). */
    fun metersPerPixel(): Double = try {
        map.projection.getMetersPerPixelAtLatitude(map.cameraPosition.target?.latitude ?: 46.0)
    } catch (_: Exception) { 5.0 }

    fun dpToPx(dp: Float) = dp * density

    /** Search result mark (orange ring around the place, with its name); null removes it. Never throws. */
    fun setSearchMark(p: GeoPoint?, name: String?) = runCatching {
        if (style.getSource(SRC_MARK) == null) {
            style.addSource(GeoJsonSource(SRC_MARK))
            // One orange ring around the place's icon (owner: not a red dot covering it), clearly outside the icon.
            style.addLayer(CircleLayer("search-mark", SRC_MARK).withProperties(
                PF.circleRadius(22f), PF.circleColor("#00000000"), PF.circleStrokeColor("#F28C00"), PF.circleStrokeWidth(3.5f)))
            style.addLayer(SymbolLayer("search-mark-label", SRC_MARK).withProperties(
                PF.textField(Expression.get("name")), PF.textFont(arrayOf("Noto Sans Medium")), PF.textSize(13f),
                PF.textOffset(arrayOf(0f, 2.2f)), PF.textAnchor(Property.TEXT_ANCHOR_TOP),
                PF.textColor("#B35C00"), PF.textHaloColor("#FFFFFF"), PF.textHaloWidth(1.6f),
                PF.textAllowOverlap(true), PF.textIgnorePlacement(true)))
        }
        val src = style.getSourceAs<GeoJsonSource>(SRC_MARK) ?: return@runCatching
        if (p == null) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        else src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply { addStringProperty("name", name ?: "") })
    }

    fun setHighlight(p: GeoPoint?) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_HL) ?: return
        if (p == null) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        else src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)))
    }

    /** Markers currently drawn (last [setWaypoints]); used for zoom-independent hit-testing. */
    private var shownWaypoints: List<GeoPoint> = emptyList()

    /**
     * Index of the marker nearest to a screen point within ~26 dp (screen space, so the same at every
     * zoom; overlapping markers → the nearest one), or -1. Never throws.
     */
    fun waypointAt(screen: PointF): Int = try {
        val pts = shownWaypoints
        val xs = FloatArray(pts.size); val ys = FloatArray(pts.size)
        pts.forEachIndexed { i, p -> map.projection.toScreenLocation(LatLng(p.lat, p.lon)).let { xs[i] = it.x; ys[i] = it.y } }
        com.onpath.app.ui.MapGestures.nearestMarker(xs, ys, screen.x, screen.y, com.onpath.app.ui.MapGestures.MARKER_RADIUS_DP * density)
    } catch (e: Exception) { Log.w("OnPath", "marker hit-test failed: $e"); -1 }

    /** Screen position of [p] in pixels of the map view, or null (projection not ready). */
    fun toScreen(p: GeoPoint): PointF? = try { map.projection.toScreenLocation(LatLng(p.lat, p.lon)) } catch (_: Exception) { null }

    fun fitTo(points: List<GeoPoint>, bottomPaddingPx: Int) {
        if (points.isEmpty()) return
        try {
            if (points.size == 1) {
                // one place (search result): close enough to see the POI icon and the paths around it
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(points[0].lat, points[0].lon), 16.0), 700)
                return
            }
            val b = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.lat, it.lon)) } }.build()
            val pad = (48 * density).toInt()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(b, pad, pad * 2, pad, bottomPaddingPx + pad), 800)
        } catch (e: Exception) {
            Log.w("OnPath", "fit failed: $e")
        }
    }

    /** Numbered round markers drawn once per index (no glyphs needed). */
    private fun ensureMarkerImages(count: Int) {
        while (markerImages < count) {
            val n = markerImages + 1
            style.addImage("wp-s-$n", marker(n, "#2E7D32"))
            style.addImage("wp-v-$n", marker(n, ROUTE_COLOR))
            style.addImage("wp-e-$n", marker(n, "#C62828"))
            markerImages++
        }
    }

    private fun marker(n: Int, color: String): Bitmap {
        val sizeDp = 30
        val px = (sizeDp * density).toInt()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        bmp.density = context.resources.displayMetrics.densityDpi
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = android.graphics.Color.WHITE
        c.drawCircle(px / 2f, px / 2f, px / 2f, p)
        p.color = android.graphics.Color.parseColor(color)
        c.drawCircle(px / 2f, px / 2f, px / 2f - 3 * density, p)
        p.color = android.graphics.Color.WHITE
        p.textSize = 14 * density
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        val y = px / 2f - (p.descent() + p.ascent()) / 2
        c.drawText(n.toString(), px / 2f, y, p)
        return bmp
    }
}
