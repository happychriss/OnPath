package com.onpath.app.map

/** Position dot rules (owner). Pure, unit-tested. */
object PositionDot {
    /** No position for longer than this (screen locked, app in the background): the next one jumps, no glide. */
    const val JUMP_AFTER_MS = 3_000L
    /** Dot size against MapLibre's default (1.0 zoomed in, 0.6 zoomed out): a little larger. */
    const val MAX_SCALE = 1.3f
    const val MIN_SCALE = 0.9f

    /** Whether the dot is placed at once: the first position, or the first after a pause. */
    fun jumps(lastFeedElapsed: Long, nowElapsed: Long): Boolean =
        lastFeedElapsed == 0L || nowElapsed - lastFeedElapsed > JUMP_AFTER_MS

    /** The accuracy circle round the dot in Navigate: MapLibre's fill alpha. */
    const val ACCURACY_ALPHA = 0.18f

    /**
     * The GPS accuracy circle (owner 2026-11-06 23:05: "im Planmodus habe ich immer den GPS-Radius – das können wir
     * rausnehmen"): only in Navigate, where it says the GPS is weak; never in Plan (planning at home or changing the route
     * during a tour, where the position is the activity sign anyway).
     */
    fun accuracyAlpha(navigate: Boolean): Float = if (navigate) ACCURACY_ALPHA else 0f

    /** The image names MapLibre's native location layer (IndicatorLocationLayerRenderer) draws the dot from. */
    val INDICATOR_SIGN_IMAGES = listOf("mapbox-location-icon", "mapbox-location-stale-icon")
    val INDICATOR_OTHER_IMAGES = listOf("mapbox-location-bearing-icon", "mapbox-location-bearing-stale-icon",
        "mapbox-location-stroke-icon", "mapbox-location-background-stale-icon", "mapbox-location-shadow-icon")

    /**
     * The plan sign during a tour on the native location layer, which ignores custom image names: image name → true =
     * the sign, false = an empty image (no triangle, ring or shadow beside it); empty = MapLibre's own dot images. MapLibre
     * writes its images again on every style apply and every render mode change (Plan ⇄ following), so MapController
     * writes these after each of them – else the blue dot comes back while planning (emulator 2026-11-13).
     */
    fun planSignImages(planSign: Boolean): Map<String, Boolean> =
        if (!planSign) emptyMap()
        else INDICATOR_SIGN_IMAGES.associateWith { true } + INDICATOR_OTHER_IMAGES.associateWith { false }
}
