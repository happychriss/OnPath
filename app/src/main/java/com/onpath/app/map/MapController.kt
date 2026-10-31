package com.onpath.app.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.location.Location
import com.onpath.app.debug.Problems
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
        /** Turn badge / U-turn sign: a soft blue, clearly lighter than the position dot (owner). */
        const val TURN_HINT_COLOR = "#7C8FD0"
        const val GPX_COLOR = "#8E24AA"       // purple
        const val REC_COLOR = "#EF6C00"       // orange
        const val NAV_ORIGINAL_COLOR = "#8A8F98" // grey: the original route while a new one is followed
        const val SRC_PLANNED = "planned-route"
        const val SRC_GPX = "gpx-track"
        /** Rides of a shown tour that are not counted (vehicle detection): grey dashed (bus / car), purple dashed (lift). */
        const val SRC_RIDES = "tour-rides"
        const val SRC_LIFTS = "tour-lifts"
        const val SRC_REC = "recorded-track"
        const val SRC_WPT = "waypoints"
        const val SRC_HL = "highlight"
        /** Route warnings: the spot shown from the list (amber). */
        const val SRC_SPOT = "route-spot"
        const val SPOT_COLOR = "#F2A000"
        const val LAYER_WPT = "waypoints-icons"
        /** Unreachable plan points: grey dashed missing piece from the route's end to the point. */
        const val SRC_GAPS = "plan-gaps"
        private const val GAP_COLOR = "#8A8A8A"
        // --- Navigate: orientation + next-turn arrow ---
        const val SRC_TURN = "nav-turn"
        const val LAYER_TURN = "nav-turn-arrow"
        /** U-turn sign where a way back starts by turning round. */
        const val SRC_UTURN = "nav-uturn"
        const val SRC_MARK = "search-mark"
        /** MapLibre's own position layers (dot, accuracy circle, bearing) all start with this. */
        const val POSITION_LAYER_PREFIX = "mapbox-location"
        // --- Re-routing while navigating (navigation/Navigator) ---
        /** Thin grey original line while a new route is followed (below the followed line). */
        const val SRC_NAV_ORIGINAL = "nav-original"
        /** Dashed preview of the proposed new route while the question is open. */
        const val SRC_NAV_PROPOSAL = "nav-proposal"
        /** Dashed straight guide from the position to the route (no way found / original kept). */
        const val SRC_NAV_GUIDE = "nav-guide"
        /** All re-routing layer ids (hidden by the eye button too). */
        val NAV_LAYERS = listOf(SRC_NAV_ORIGINAL, SRC_NAV_PROPOSAL, SRC_NAV_GUIDE)
        /**
         * App layers hidden while the eye button is held: everything route-like, but not the own
         * position or the recorded track. New route layers (e.g. a grey original route) belong here.
         */
        val ROUTE_OVERLAY_LAYERS = NAV_LAYERS + listOf(
            "planned-route-casing", "planned-route", "planned-straight-casing", "planned-straight",
            "$SRC_GPX-casing", SRC_GPX, SRC_RIDES, SRC_LIFTS, "highlight-dot", "waypoints-pin", LAYER_WPT, LAYER_TURN, SRC_UTURN,
            "planned-route-arrows", "$SRC_GPX-arrows", SRC_GAPS, SRC_SPOT,
        )
        private const val ARROW_IMAGE = "route-arrow"
        private const val IMG_TURN = "nav-turn-arrow"
        private const val IMG_UTURN = "nav-uturn-sign"
        /** Map tilt in heading-up Navigate (degrees). 0 = flat; try 30–40 for a "navigation" look. */
        const val NAV_TILT_DEG = 0.0
        /** Heading-up: the position sits lower on screen so more of the way ahead is visible (share of height). */
        const val NAV_TOP_PADDING = 0.30
        /** "Show whole route": never closer than this (as the browser planner). */
        const val FIT_MAX_ZOOM = 16.0
        /** Position puck in both modes: MapLibre's small blue dot + bearing triangle (not the big GPS puck). */
        private const val PUCK_MODE = RenderMode.COMPASS
    }

    /** Settings page → base style layers (POI filter, contours, hillshade). */
    val layers = LayerSettings(style)

    /**
     * True while [style] is the map's current, fully loaded style. While a new style loads (theme change, data
     * import) MapLibre throws on any access to the old one, and the app's effects can still hold this controller
     * for a moment – every update below is a no-op then instead of a crash (R-01).
     */
    // expected: the old style throws while a new one loads → "not live", updates skipped
    private fun live(): Boolean = try { style.isFullyLoaded } catch (_: Exception) { false }

    /** GeoJSON source [id], or null when the style is gone or being replaced (the caller then does nothing). */
    private fun source(id: String): GeoJsonSource? =
        if (!live()) null else try { style.getSourceAs<GeoJsonSource>(id) } catch (e: Exception) {
            Problems.warn("map", "Map source $id not readable, update skipped", e); null
        }

    /** Runs a style update only on a live style and never lets a MapLibre exception reach the UI. */
    private inline fun guarded(what: String, block: () -> Unit) {
        if (!live()) return
        try { block() } catch (e: Exception) { Problems.warn("map", "Map update '$what' failed and was skipped", e) }
    }

    private val density = context.resources.displayMetrics.density
    private var locationReady = false
    private var markerImages = 0
    /** Compass + GPS bearing for heading-up; the sensor runs only while MapLibre listens (Navigate). */
    private val heading = HeadingEngine(context)
    private var onTrackingDismissed: (() -> Unit)? = null
    /** Camera mode we asked for while following (to notice when a gesture changed it). */
    private var wantedCameraMode = CameraMode.NONE
    private var wasNavigating = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reemitJob: Runnable? = null

    /**
     * "Where I face" arrow drawn by the app (heading-up following only): while MapLibre tracks, the dot stays at the
     * camera target on screen, so the arrow is placed there and turned by the view direction – no map layer updates,
     * no camera moves. Compose snapshot state, read by ui/FacingArrow.
     */
    class Facing {
        var visible by androidx.compose.runtime.mutableStateOf(false)
        var x by androidx.compose.runtime.mutableFloatStateOf(0f)
        var y by androidx.compose.runtime.mutableFloatStateOf(0f)
        var viewHeading by androidx.compose.runtime.mutableFloatStateOf(0f)
        /** The compass is unsure: the arrow is grey (HeadingEngine.arrowGrey). */
        var unsure by androidx.compose.runtime.mutableStateOf(false)
    }
    val facing = Facing()

    private fun showFacing(on: Boolean) {
        if (!on || wantedCameraMode != CameraMode.TRACKING_COMPASS) return
        placeFacing()
        heading.viewHeading?.let { facing.viewHeading = it.toFloat() }
        facing.visible = true
    }

    private fun placeFacing() {
        try {
            val p = map.projection.toScreenLocation(map.cameraPosition.target ?: return)
            facing.x = p.x; facing.y = p.y
        } catch (e: Exception) { Problems.warn("map", "Facing arrow not placed", e) }
    }

    /** The user moved the map: stop following; the reason goes to Diagnostics. */
    private fun dismissed(why: String) {
        val cb = onTrackingDismissed ?: return
        MapEvents.followingStopped(why)
        cb()
    }

    init {
        addNavOriginal() // below the followed line
        addRideLine(SRC_RIDES, NAV_ORIGINAL_COLOR)
        addRideLine(SRC_LIFTS, GPX_COLOR)
        addLine(SRC_GPX, GPX_COLOR, 5f, casing = true)
        addLine(SRC_REC, REC_COLOR, 4.5f, casing = true)
        addPlannedLayers()
        addDirectionArrows()
        style.addSource(GeoJsonSource(SRC_HL))
        addBelowPosition(CircleLayer("highlight-dot", SRC_HL).withProperties(
            PF.circleRadius(7f), PF.circleColor("#FFFFFF"), PF.circleStrokeColor(ROUTE_COLOR), PF.circleStrokeWidth(4f),
        ))
        // the missing piece to an unreachable point: under the markers (its point is drawn grey and hollow, wp-g-N)
        style.addSource(GeoJsonSource(SRC_GAPS))
        addBelowPosition(LineLayer(SRC_GAPS, SRC_GAPS).withProperties(
            PF.lineColor(GAP_COLOR), PF.lineWidth(4f), PF.lineDasharray(arrayOf(1.2f, 1.2f)),
            PF.lineCap(Property.LINE_CAP_ROUND),
        ))
        style.addSource(GeoJsonSource(SRC_WPT))
        // No ring for POI-pinned stops (owner): the only orange ring is the search mark.
        addBelowPosition(SymbolLayer(LAYER_WPT, SRC_WPT).withProperties(
            PF.iconImage(Expression.get("icon")),
            PF.iconAllowOverlap(true), PF.iconIgnorePlacement(true),
        ))
        addNavOverlays()
        addTurnLayer()
        addUTurnLayer()
        activateLocation()
    }

    private fun addLine(id: String, color: String, width: Float, casing: Boolean) {
        style.addSource(GeoJsonSource(id))
        if (casing) addBelowPosition(LineLayer("$id-casing", id).withProperties(
            PF.lineColor("#FFFFFF"), PF.lineWidth(width + 4f),
            PF.lineJoin(Property.LINE_JOIN_ROUND), PF.lineCap(Property.LINE_CAP_ROUND),
        ))
        addBelowPosition(LineLayer(id, id).withProperties(
            PF.lineColor(color), PF.lineWidth(width),
            PF.lineJoin(Property.LINE_JOIN_ROUND), PF.lineCap(Property.LINE_CAP_ROUND),
        ))
    }

    private fun addRideLine(id: String, color: String) {
        style.addSource(GeoJsonSource(id))
        addBelowPosition(LineLayer(id, id).withProperties(
            PF.lineColor(color), PF.lineWidth(3.5f), PF.lineOpacity(0.9f), PF.lineDasharray(arrayOf(1.4f, 1.2f)),
            PF.lineJoin(Property.LINE_JOIN_ROUND),
        ))
    }

    private fun addNavOriginal() {
        style.addSource(GeoJsonSource(SRC_NAV_ORIGINAL))
        addBelowPosition(LineLayer(SRC_NAV_ORIGINAL, SRC_NAV_ORIGINAL).withProperties(
            PF.lineColor(NAV_ORIGINAL_COLOR), PF.lineWidth(3f), PF.lineOpacity(0.85f),
            PF.lineJoin(Property.LINE_JOIN_ROUND), PF.lineCap(Property.LINE_CAP_ROUND),
        ))
    }

    private fun addNavOverlays() {
        style.addSource(GeoJsonSource(SRC_NAV_PROPOSAL))
        addBelowPosition(LineLayer(SRC_NAV_PROPOSAL, SRC_NAV_PROPOSAL).withProperties(
            PF.lineColor(ROUTE_COLOR), PF.lineWidth(5f), PF.lineDasharray(arrayOf(1.6f, 1.2f)),
            PF.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        style.addSource(GeoJsonSource(SRC_NAV_GUIDE))
        addBelowPosition(LineLayer(SRC_NAV_GUIDE, SRC_NAV_GUIDE).withProperties(
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
        guarded("track look") {
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
        addBelowPosition(LineLayer("planned-route-casing", SRC_PLANNED).withFilter(routed).withProperties(
            PF.lineColor("#FFFFFF"), PF.lineWidth(10f),
            PF.lineJoin(Property.LINE_JOIN_ROUND), PF.lineCap(Property.LINE_CAP_ROUND),
        ))
        addBelowPosition(LineLayer("planned-route", SRC_PLANNED).withFilter(routed).withProperties(
            PF.lineColor(ROUTE_COLOR), PF.lineWidth(6f),
            PF.lineJoin(Property.LINE_JOIN_ROUND), PF.lineCap(Property.LINE_CAP_ROUND),
        ))
        addBelowPosition(LineLayer("planned-straight-casing", SRC_PLANNED).withFilter(straight).withProperties(
            PF.lineColor("#FFFFFF"), PF.lineWidth(7f), PF.lineCap(Property.LINE_CAP_ROUND),
        ))
        addBelowPosition(LineLayer("planned-straight", SRC_PLANNED).withFilter(straight).withProperties(
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
                addBelowPosition(l)
            }
        } catch (e: Exception) {
            Problems.warn("map", "Direction arrows on the route not added", e)
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

    /** Position dot look; [grey] = the "where I face" image grey (compass unsure, map/CompassTrust). */
    private fun puckOptions(grey: Boolean): LocationComponentOptions = LocationComponentOptions.builder(context)
        .accuracyAlpha(0.18f)
        .enableStaleState(true)
        .staleStateTimeout(30_000)
        // HeadingEngine turns smoothly at display rate itself
        .compassAnimationEnabled(false)
        // pinch zoom while following must not end direction-up (owner): zoom around the
        // dot and a higher move threshold for two fingers – only a real pan or a turn ends following
        .trackingGesturesManagement(true)
        // the dot a little larger (owner: tell it apart from the turn badge at a glance)
        .maxZoomIconScale(PositionDot.MAX_SCALE).minZoomIconScale(PositionDot.MIN_SCALE)
        // the "where I face" arrow (north-up / Plan) as large as the app's own in heading-up (owner)
        .bearingDrawable(if (grey) com.onpath.app.R.drawable.onpath_bearing_unsure else com.onpath.app.R.drawable.onpath_bearing)
        .build()

    @SuppressLint("MissingPermission")
    private fun activateLocation() {
        try {
            val lc = map.locationComponent
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(false)
                    .locationComponentOptions(puckOptions(heading.arrowGrey))
                    .build()
            )
            lc.setCompassEngine(heading)
            heading.onViewHeading = { h -> facing.viewHeading = h.toFloat() }
            facing.unsure = heading.arrowGrey
            // compass unsure → the arrow grey, never hidden (owner); in north-up / Plan it is MapLibre's bearing image
            heading.onArrowGrey = { grey ->
                facing.unsure = grey
                try { if (live()) map.locationComponent.applyStyle(puckOptions(grey)) } catch (e: Exception) {
                    Problems.warn("map", "Facing arrow colour on the position dot not changed", e)
                }
            }
            // the app's own "where I face" arrow sits on the dot; keep its screen position with every camera move
            map.addOnCameraMoveListener { if (facing.visible) placeFacing() }
            // No frame cap on the position dot (owner: while you look at the app it must run smooth;
            // power is saved with the screen off / in the pocket, where nothing is drawn).
            lc.isLocationComponentEnabled = true
            // Both modes: small blue dot with accuracy circle and a small direction triangle at its edge
            // (owner; not the big white GPS-mode navigation puck). The direction comes from
            // HeadingEngine (compass standing, GPS bearing while moving).
            lc.renderMode = PUCK_MODE
            lc.cameraMode = CameraMode.NONE
            lc.addOnCameraTrackingChangedListener(object : org.maplibre.android.location.OnCameraTrackingChangedListener {
                // MapLibre ends tracking only for gestures (pan, fling, two-finger turn) – that is "the user moved the map"
                override fun onCameraTrackingDismissed() { dismissed("map gesture (pan / fling / turn)") }
                override fun onCameraTrackingChanged(currentMode: Int) {
                    // Only noted: a mode change alone is never taken as a gesture.
                    if (wantedCameraMode != CameraMode.NONE && currentMode != wantedCameraMode && onTrackingDismissed != null)
                        MapEvents.cameraModeChanged(currentMode, wantedCameraMode)
                }
            })
            locationReady = true
        } catch (e: Exception) {
            // no location permission (yet) is expected-ish: INFO; anything else breaks the position dot: ERROR
            Problems.report("map", "Map position dot not available – own position not shown on the map", e,
                if (e is SecurityException) Problems.Level.INFO else Problems.Level.ERROR)
        }
    }

    fun updateLocation(loc: Location) {
        if (!locationReady) return
        val now = android.os.SystemClock.elapsedRealtime()
        val jump = PositionDot.jumps(lastFeedElapsed, now)
        lastFeedElapsed = now
        try {
            map.locationComponent.forceLocationUpdate(loc)
            // After a pause (screen locked, app in the background) MapLibre would glide the dot from the old place for
            // as long as the pause (max 2 s). Fed again at once, the glide time is ~0: the dot is there (owner).
            if (jump) map.locationComponent.forceLocationUpdate(loc)
        } catch (e: Exception) { Problems.warn("map", "Position dot not updated", e) }
        heading.onLocation(loc)
    }

    /** When the dot was last fed ([updateLocation]); 0 = never. */
    private var lastFeedElapsed = 0L

    /** Heading-up on a followed route: direction of the route ahead (null = compass / GPS bearing). Never throws. */
    fun setRouteHeading(bearingDeg: Double?) {
        try { heading.setRoute(bearingDeg) } catch (e: Exception) { Problems.warn("map", "Route direction not passed to the map", e) }
    }

    /**
     * Rest view while cycling (ui/RestView.kt covers the map): no compass / direction goes to the camera, so with no
     * fixes fed ([updateLocation] is not called meanwhile – ui/MapScreen.kt) the map draws no frames. The camera mode
     * (following, heading-up) is never touched; false = the current direction again at once. Never throws.
     */
    fun setQuiet(on: Boolean) {
        try { heading.quiet = on } catch (e: Exception) { Problems.warn("map", "Map not paused / resumed for the rest view", e) }
    }

    /** Plan mode follow (north stays where the user left it). */
    fun setFollow(follow: Boolean, onDismissed: () -> Unit) { setCamera(follow, navigating = false, headingUp = false, onDismissed = onDismissed) }

    /**
     * Camera and puck for both modes. Following: the camera tracks the position – walking direction up
     * ([headingUp], map turns with [HeadingEngine]; both modes, compass cycle) or north up (Navigate) / as the user
     * left it (Plan).
     * MapLibre ends following when the user pans ([onDismissed]); "centre on me" calls this again.
     * The puck is always the small dot with a direction triangle ([PUCK_MODE]); the compass sensor runs while
     * the map is visible (MapLibre releases it when the map stops).
     */
    fun setCamera(follow: Boolean, navigating: Boolean, headingUp: Boolean, startZoom: Double? = null,
                  onDismissed: () -> Unit): Boolean {
        if (!locationReady) return false
        var zoomed = false
        try {
            val lc = map.locationComponent
            onTrackingDismissed = null   // no callbacks for our own mode switches
            // our own compass in the map controls replaces MapLibre's in both modes
            map.uiSettings.isCompassEnabled = false
            val leavingNav = wasNavigating && !navigating
            wasNavigating = navigating
            if (follow) {
                val (mode, tilt, bearing) = when {
                    // walking direction up – Navigate and (owner, compass cycle) Plan
                    headingUp -> Triple(CameraMode.TRACKING_COMPASS, NAV_TILT_DEG, null)
                    navigating -> Triple(CameraMode.TRACKING_GPS_NORTH, 0.0, 0.0)
                    else -> Triple(CameraMode.TRACKING, if (leavingNav) 0.0 else null, if (leavingNav) 0.0 else null)
                }
                // heading-up: MapLibre turns camera + puck with the map direction, so its own arrow would show the
                // route; the dot gets no arrow and the app draws the "where I face" arrow (ui/FacingArrow)
                heading.mapToCompass = headingUp
                lc.renderMode = if (headingUp) RenderMode.NORMAL else PUCK_MODE
                facing.visible = false
                wantedCameraMode = mode
                // Navigate's start zoom (AppMode.navStartZoom) once; else only zoom in when far out
                val zoom = startZoom ?: if (map.cameraPosition.zoom < 13) 15.0 else null
                zoomed = true
                // a north-up animation from Plan must not run on into the walking-direction camera
                if (mode == CameraMode.TRACKING_COMPASS) map.cancelTransitions()
                val top = if (navigating && headingUp) context.resources.displayMetrics.heightPixels * NAV_TOP_PADDING else 0.0
                // MapLibre ignores paddingWhileTracking while the camera moves into tracking:
                // set it again when the transition ends, so the dot always sits at the same place
                lc.setCameraMode(mode, 750L, zoom, bearing, tilt, object : org.maplibre.android.location.OnLocationCameraTransitionListener {
                    override fun onLocationCameraTransitionFinished(cameraMode: Int) { pad(top); showFacing(headingUp); ensureStartZoom(startZoom) }
                    override fun onLocationCameraTransitionCanceled(cameraMode: Int) { pad(top); showFacing(headingUp); ensureStartZoom(startZoom) }
                })
                // the camera takes the walking direction at once, after the transition and a north-up animation
                // that may still run (Plan → Navigate); it must not wait for the next change of direction
                if (mode == CameraMode.TRACKING_COMPASS) {
                    reemitJob?.let { mainHandler.removeCallbacks(it) }
                    val r = Runnable { try { heading.reemit() } catch (e: Exception) { Problems.warn("map", "Heading not re-sent", e) } }
                    reemitJob = r
                    mainHandler.postDelayed(r, 800L)
                    heading.reemit()
                }
                // no pad(top) here: the transition has just started, MapLibre would ignore the call and log
                // "paddingWhileTracking … ignored because the camera mode is transitioning". The listener above
                // pads when it ends – MapLibre calls it also when there is nothing to animate (same mode, no fix).
            } else {
                heading.mapToCompass = false
                facing.visible = false
                wantedCameraMode = CameraMode.NONE
                if (lc.cameraMode != CameraMode.NONE) lc.cameraMode = CameraMode.NONE
                lc.renderMode = PUCK_MODE
                if (leavingNav && !headingUp) resetNorth() // the compass choice is kept across modes (owner)
            }
            onTrackingDismissed = onDismissed
        } catch (e: Exception) {
            Problems.warn("map", "Map camera mode not changed (follow / heading-up)", e)
        }
        return zoomed
    }

    /**
     * Navigate's start zoom, made sure (field A-019: after "Continue" the map kept Plan's 1 km zoom). MapLibre zooms in
     * setCameraMode only when tracking starts from none: from Plan's own following (same or another tracking mode) it
     * does not zoom at all, and a transition can be cancelled. So when the transition ends and the map still follows,
     * the missing zoom is animated while tracking ([com.onpath.app.ui.AppMode.zoomStill]).
     */
    private fun ensureStartZoom(wanted: Double?) {
        try {
            val lc = map.locationComponent
            if (lc.cameraMode == CameraMode.NONE) return // a gesture or a fit ended following: the user's / app's view stays
            val z = com.onpath.app.ui.AppMode.zoomStill(map.cameraPosition.zoom, wanted) ?: return
            lc.zoomWhileTracking(z, 600L)
        } catch (e: Exception) {
            Problems.warn("map", "Navigate start zoom not set", e)
        }
    }

    private fun pad(top: Double) {
        try { map.locationComponent.paddingWhileTracking(doubleArrayOf(0.0, top, 0.0, 0.0), 450L) } catch (e: Exception) {
            Problems.warn("map", "Map padding while following not set", e)
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
        for (id in ROUTE_OVERLAY_LAYERS) {
            runCatching { style.getLayer(id)?.setProperties(v) }
                .onFailure { if (live()) Problems.warn("map", "Route overlay layer $id not shown / hidden", it) }
        }
    }

    /**
     * App start in Plan: centre on the position once (no following, so the user can pan right away).
     * Zooms in only when the map is zoomed far out. Never throws.
     */
    fun centreOn(lat: Double, lon: Double) = runCatching {
        val zoom = map.cameraPosition.zoom.let { if (it < 13.0) 15.0 else it }
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom), 600)
    }.onFailure { Problems.warn("map", "Map not centred on the position", it) }

    /** Compass button in Plan: turn the map back to north up. */
    fun northUp() = runCatching { resetNorth() }.onFailure { Problems.warn("map", "Map not turned north up", it) }

    /** Back in Plan: north up, flat, no tracking padding (editing on a turned map is confusing). */
    private fun resetNorth() {
        val cp = map.cameraPosition
        if (cp.bearing == 0.0 && cp.tilt == 0.0 && (cp.padding?.all { it == 0.0 } != false)) return
        map.animateCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder(cp).bearing(0.0).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build()), 600)
    }

    /** Next-turn arrow at [turn], pointing in its outgoing direction; null hides it. */
    fun setTurnArrow(turn: Turn?) = guarded("turn arrow") {
        val src = source(SRC_TURN) ?: return
        if (turn == null) { src.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return }
        src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(turn.point.lon, turn.point.lat)).apply {
            addNumberProperty("rot", turn.bearingOut)
        })
    }

    /** The U-turn sign at [p] (where to turn round), or none. Never throws. */
    fun setUTurn(p: GeoPoint?) = guarded("U-turn sign") {
        val src = source(SRC_UTURN) ?: return
        src.setGeoJson(if (p == null) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)))))
    }

    private fun addUTurnLayer() {
        style.addImage(IMG_UTURN, uTurnSign())
        style.addSource(GeoJsonSource(SRC_UTURN))
        // upright on screen, just above the point (the dot sits on the point itself)
        addBelowPosition(SymbolLayer(SRC_UTURN, SRC_UTURN).withProperties(
            PF.iconImage(IMG_UTURN),
            PF.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PF.iconOffset(arrayOf(0f, -6f)),
            PF.iconAllowOverlap(true), PF.iconIgnorePlacement(true),
        ))
    }

    /** Round white badge with a soft blue ring and a U-turn arrow (up, round to the left, down with the head). */
    private fun uTurnSign(): Bitmap {
        val px = (34 * density).toInt()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        bmp.density = context.resources.displayMetrics.densityDpi
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = px / 2f
        val u = px / 34f
        p.color = android.graphics.Color.parseColor(TURN_HINT_COLOR)
        c.drawCircle(r, r, r, p)
        p.color = android.graphics.Color.WHITE
        c.drawCircle(r, r, r - 2.5f * density, p)
        p.color = android.graphics.Color.parseColor(ROUTE_COLOR)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3.2f * u
        p.strokeCap = Paint.Cap.ROUND
        // right leg up, half circle over the top to the left, left leg down
        val left = 11f * u; val right = 22f * u; val top = 9f * u; val bottom = 25f * u
        val path = android.graphics.Path().apply {
            moveTo(right, bottom)
            lineTo(right, top + (right - left) / 2)
            arcTo(android.graphics.RectF(left, top, right, top + (right - left)), 0f, -180f, false)
            lineTo(left, bottom - 4f * u)
        }
        c.drawPath(path, p)
        // arrow head at the bottom of the left leg
        p.style = Paint.Style.FILL
        val head = android.graphics.Path().apply {
            moveTo(left - 4.5f * u, bottom - 5f * u)
            lineTo(left + 4.5f * u, bottom - 5f * u)
            lineTo(left, bottom + 1f * u)
            close()
        }
        c.drawPath(head, p)
        return bmp
    }

    private fun addTurnLayer() {
        style.addImage(IMG_TURN, turnArrow())
        style.addSource(GeoJsonSource(SRC_TURN))
        addBelowPosition(SymbolLayer(LAYER_TURN, SRC_TURN).withProperties(
            PF.iconImage(IMG_TURN),
            PF.iconRotate(Expression.get("rot")),
            PF.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PF.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PF.iconAllowOverlap(true), PF.iconIgnorePlacement(true),
        ))
    }

    /**
     * Round white badge with a soft blue arrow pointing up (north); rotated by the layer. Soft and small (owner), so
     * the strong blue position dot stands out from it.
     */
    private fun turnArrow(): Bitmap {
        val px = (26 * density).toInt()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        bmp.density = context.resources.displayMetrics.densityDpi
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = px / 2f
        p.color = android.graphics.Color.parseColor(TURN_HINT_COLOR)
        c.drawCircle(r, r, r, p)
        p.color = android.graphics.Color.WHITE
        c.drawCircle(r, r, r - 2.5f * density, p)
        p.color = android.graphics.Color.parseColor(TURN_HINT_COLOR)
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

    /**
     * Hiking level (SAC T1–T5) the map was last set for: ways above it are marked for Hike.
     * One value, set with the activity in [setOverlay].
     */
    var sacLimit: Int = com.onpath.app.data.LastActivity.DEFAULT_SAC
        private set

    /**
     * The map look for [activity]: which layers show (forbidden ways, green ways, bike map, "-bikeview" / "-hikeview"
     * looks, route bands; [enabled] = route bands at all). The table is [ActivityLayers] – shared with the browser
     * planner, so both draw the same map; this only applies it to the loaded style.
     */
    fun setOverlay(activity: Activity, enabled: Boolean, sacLimit: Int = this.sacLimit) {
        this.sacLimit = sacLimit.coerceIn(1, 5)
        val all = runCatching { style.layers }.onFailure { if (live()) Problems.warn("map", "Map layers not listable; activity look not switched", it) }
            .getOrNull() ?: return
        val byId = all.associateBy { it.id }
        for ((id, on) in ActivityLayers.visibility(activity, all.map { it.id }, enabled)) {
            runCatching { byId[id]?.setProperties(PF.visibility(if (on) Property.VISIBLE else Property.NONE)) }
                .onFailure { if (live()) Problems.warn("map", "Map layer $id not switched for the activity", it) }
        }
    }

    /**
     * Hiking route band over trails above the user's T level (the router does not use them): a plain grey band instead
     * of the green one (owner). The filters are [ActivityLayers.hikeRouteFilters] (shared with the browser planner).
     */
    fun setSacLimit(limit: Int) {
        for ((id, json) in ActivityLayers.hikeRouteFilters(limit)) {
            runCatching { (style.getLayer(id) as? LineLayer)?.setFilter(Expression.Converter.convert(json)) }
                .onFailure { if (live()) Problems.warn("map", "Route layer $id not set to T$limit", it) }
        }
    }

    fun setPlanned(route: PlannedRoute?) = guarded("planned route") {
        val src = source(SRC_PLANNED) ?: return
        if (route == null) { src.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return }
        val features = route.legs.filter { it.points.size >= 2 }.map { leg ->
            Feature.fromGeometry(LineString.fromLngLats(leg.points.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                addBooleanProperty("straight", leg.straight)
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    fun setTrack(sourceId: String, segments: List<List<GeoPoint>>) = guarded("track $sourceId") {
        val src = source(sourceId) ?: return
        val lines = segments.filter { it.size >= 2 }.map { seg -> seg.map { Point.fromLngLat(it.lon, it.lat) } }
        if (lines.isEmpty()) { src.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return }
        src.setGeoJson(Feature.fromGeometry(MultiLineString.fromLngLats(lines)))
    }

    /** [unreached] = indices the route does not reach: drawn grey and hollow (see [setPlanGaps]). */
    fun setWaypoints(points: List<GeoPoint>, pinned: Set<Int> = emptySet(), unreached: Set<Int> = emptySet()) = guarded("waypoints") {
        val src = source(SRC_WPT) ?: return
        ensureMarkerImages(points.size)
        val features = points.mapIndexed { i, p ->
            val kind = when { i in unreached -> "g"; i == 0 -> "s"; i == points.lastIndex && points.size > 1 -> "e"; else -> "v" }
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                addStringProperty("icon", "wp-$kind-${i + 1}")
                addNumberProperty("idx", i)
                addBooleanProperty("pinned", i in pinned)
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(features))
        shownWaypoints = points.toList() // what is drawn now
    }

    /** Grey dashed lines from where the route ends to each point it does not reach; empty = none. Never throws. */
    fun setPlanGaps(gaps: List<com.onpath.app.ui.PlanGap>) = guarded("plan gaps") {
        val src = source(SRC_GAPS) ?: return
        src.setGeoJson(FeatureCollection.fromFeatures(gaps.map { g ->
            Feature.fromGeometry(LineString.fromLngLats(listOf(
                Point.fromLngLat(g.routeEnd.lon, g.routeEnd.lat), Point.fromLngLat(g.point.lon, g.point.lat))))
        }))
    }

    /** Metres per screen pixel at the camera centre (for "tap near the line" tolerances). */
    fun metersPerPixel(): Double = try {
        map.projection.getMetersPerPixelAtLatitude(map.cameraPosition.target?.latitude ?: 46.0)
    } catch (e: Exception) { Problems.warn("map", "Map scale unreadable, using 5 m per pixel", e); 5.0 }

    fun dpToPx(dp: Float) = dp * density

    /**
     * Adds every layer of the app (owner: the dot must never be under a line or a route point). On a style reload
     * MapLibre puts its position layers back before this controller adds its own, so a plain addLayer would land above
     * the dot. Directly below
     * MapLibre's location layers ("mapbox-location-*", LocationComponentConstants), or on top if there are none.
     */
    private fun addBelowPosition(l: org.maplibre.android.style.layers.Layer) {
        val firstPosition = style.layers.firstOrNull { it.id.startsWith(POSITION_LAYER_PREFIX) }?.id
        if (firstPosition != null) style.addLayerBelow(l, firstPosition) else style.addLayer(l)
    }

    /** Search result mark (orange ring around the place, with its name); null removes it. Never throws. */
    fun setSearchMark(p: GeoPoint?, name: String?) = guarded("search mark") {
        if (style.getSource(SRC_MARK) == null) {
            style.addSource(GeoJsonSource(SRC_MARK))
            // One orange ring around the place's icon (owner: not a red dot covering it), clearly outside the icon.
            addBelowPosition(CircleLayer("search-mark", SRC_MARK).withProperties(
                PF.circleRadius(22f), PF.circleColor("#00000000"), PF.circleStrokeColor("#F28C00"), PF.circleStrokeWidth(3.5f)))
            addBelowPosition(SymbolLayer("search-mark-label", SRC_MARK).withProperties(
                PF.textField(Expression.get("name")), PF.textFont(arrayOf("Noto Sans Medium")), PF.textSize(13f),
                PF.textOffset(arrayOf(0f, 2.2f)), PF.textAnchor(Property.TEXT_ANCHOR_TOP),
                PF.textColor("#B35C00"), PF.textHaloColor("#FFFFFF"), PF.textHaloWidth(1.6f),
                PF.textAllowOverlap(true), PF.textIgnorePlacement(true)))
        }
        val src = source(SRC_MARK) ?: return
        if (p == null) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        else src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply { addStringProperty("name", name ?: "") })
    }

    /** The spot tapped in the route warnings list: its piece of route drawn wide amber under the markers; null = none. */
    fun setSpotLine(points: List<GeoPoint>?) = guarded("spot line") {
        if (style.getSource(SRC_SPOT) == null) {
            style.addSource(GeoJsonSource(SRC_SPOT))
            val below = style.layers.firstOrNull { it.id == LAYER_WPT }?.id
            val l = LineLayer(SRC_SPOT, SRC_SPOT).withProperties(PF.lineColor(SPOT_COLOR), PF.lineWidth(10f),
                PF.lineOpacity(0.75f), PF.lineCap(Property.LINE_CAP_ROUND), PF.lineJoin(Property.LINE_JOIN_ROUND))
            if (below != null) style.addLayerBelow(l, below) else addBelowPosition(l)
            applyRouteOverlaysVisibility() // the eye button hides it too
        }
        val src = source(SRC_SPOT) ?: return
        if (points == null || points.size < 2) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        else src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })))
    }

    fun setHighlight(p: GeoPoint?) = guarded("highlight") {
        val src = source(SRC_HL) ?: return
        if (p == null) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        else src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)))
    }

    /** Markers currently drawn (last [setWaypoints]); used for zoom-independent hit-testing. */
    private var shownWaypoints: List<GeoPoint> = emptyList()

    /** How many route point markers are drawn now (diagnostics). */
    fun drawnWaypointCount(): Int = shownWaypoints.size

    /**
     * Index of the marker nearest to a screen point within ~26 dp (screen space, so the same at every
     * zoom; overlapping markers → the nearest one), or -1. Never throws.
     */
    fun waypointAt(screen: PointF, points: List<GeoPoint>? = null,
                   radiusDp: Float = com.onpath.app.ui.MapGestures.MARKER_RADIUS_DP, log: Boolean = true): Int = try {
        // the plan's own list when given: the list kept with the drawing can lag behind the plan (e.g. an update skipped
        // while the style reloads), and a hold on a point must not add a new one
        val pts = points ?: shownWaypoints
        val xs = FloatArray(pts.size); val ys = FloatArray(pts.size)
        pts.forEachIndexed { i, p -> map.projection.toScreenLocation(LatLng(p.lat, p.lon)).let { xs[i] = it.x; ys[i] = it.y } }
        val hit = com.onpath.app.ui.MapGestures.nearestMarker(xs, ys, screen.x, screen.y, radiusDp * density)
        if (hit < 0 && log) {
            // diagnostics: a press just outside a marker (each press its own line in the gesture log)
            val near = com.onpath.app.ui.MapGestures.nearestMarker(xs, ys, screen.x, screen.y, 2 * com.onpath.app.ui.MapGestures.NEAR_RADIUS_DP * density)
            if (near >= 0) Problems.trace("map", "Press %.0f dp from point ${near + 1} – outside the %.0f dp marker radius (near zone %.0f dp; drawn %d, plan %d)"
                .format(java.util.Locale.ROOT, kotlin.math.hypot(xs[near] - screen.x, ys[near] - screen.y) / density,
                    radiusDp, com.onpath.app.ui.MapGestures.NEAR_RADIUS_DP, shownWaypoints.size, pts.size))
        }
        hit
    } catch (e: Exception) { Problems.warn("map", "Marker tap test failed, tap treated as no marker", e); -1 }

    /** Screen position of [p] in pixels of the map view, or null (projection not ready). */
    // expected: the projection is not ready before the first layout
    fun toScreen(p: GeoPoint): PointF? = try { map.projection.toScreenLocation(LatLng(p.lat, p.lon)) } catch (_: Exception) { null }

    private var lastPanMs = 0L

    /** Profile scrub: the first move glides (no jump of several km), then the map follows the finger directly. */
    fun panTo(p: GeoPoint) = runCatching {
        val now = android.os.SystemClock.uptimeMillis()
        val update = CameraUpdateFactory.newLatLng(LatLng(p.lat, p.lon))
        if (now - lastPanMs > 400) map.easeCamera(update, 280) else map.moveCamera(update)
        lastPanMs = now
    }.onFailure { Problems.warn("map", "Map did not move to the profile point", it) }

    /** Search result: fly to [p] at [zoom]. Never throws. */
    fun flyTo(p: GeoPoint, zoom: Double) = runCatching {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), zoom), 700)
    }.onFailure { Problems.warn("map", "Map did not fly to the search result", it) }

    /**
     * Show all [points] in the free room [pad] leaves (ui/FitPaddingRule: clear of the top and bottom elements).
     * [northUp]: the overview north up and flat ("Show whole route", also from Navigate's turned, tilted camera);
     * else the current bearing / tilt stay. Following must be off before (CLAUDE.md "Map camera"). Never throws.
     */
    fun fitTo(points: List<GeoPoint>, pad: com.onpath.app.ui.FitPadding, northUp: Boolean = false) {
        if (points.isEmpty()) return
        try {
            if (points.size == 1) {
                // one place (search result): close enough to see the POI icon and the paths around it
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(points[0].lat, points[0].lon), 16.0), 700)
                return
            }
            val b = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.lat, it.lon)) } }.build()
            val update = if (northUp) {
                // a short route not closer than z16 (the browser planner's maxZoom)
                val cp = map.getCameraForLatLngBounds(b, intArrayOf(pad.left, pad.top, pad.right, pad.bottom), 0.0, 0.0)
                if (cp != null) CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(cp).zoom(minOf(cp.zoom, FIT_MAX_ZOOM)).build())
                else CameraUpdateFactory.newLatLngBounds(b, 0.0, 0.0, pad.left, pad.top, pad.right, pad.bottom)
            } else CameraUpdateFactory.newLatLngBounds(b, pad.left, pad.top, pad.right, pad.bottom)
            map.animateCamera(update, 800)
        } catch (e: Exception) {
            Problems.warn("map", "Map not zoomed to fit the route / places", e)
        }
    }

    /** Numbered round markers drawn once per index (no glyphs needed). */
    private fun ensureMarkerImages(count: Int) {
        while (markerImages < count) {
            val n = markerImages + 1
            style.addImage("wp-s-$n", marker(n, "#2E7D32"))
            style.addImage("wp-v-$n", marker(n, ROUTE_COLOR))
            style.addImage("wp-e-$n", marker(n, "#C62828"))
            style.addImage("wp-g-$n", marker(n, GAP_COLOR, hollow = true))
            markerImages++
        }
    }

    /** A numbered route point; [hollow] = white inside, ring and number in [color] (a point the route does not reach). */
    private fun marker(n: Int, color: String, hollow: Boolean = false): Bitmap {
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
        if (hollow) {
            p.color = android.graphics.Color.WHITE
            c.drawCircle(px / 2f, px / 2f, px / 2f - 6 * density, p)
        }
        p.color = if (hollow) android.graphics.Color.parseColor(color) else android.graphics.Color.WHITE
        p.textSize = 14 * density
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        val y = px / 2f - (p.descent() + p.ascent()) / 2
        c.drawText(n.toString(), px / 2f, y, p)
        return bmp
    }
}
