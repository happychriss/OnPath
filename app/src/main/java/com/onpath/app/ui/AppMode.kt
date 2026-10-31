package com.onpath.app.ui

/**
 * The app has exactly two modes (docs/ux-two-modes.md):
 * PLAN – home: build and change the route, look around, tours and settings;
 * NAVIGATE – walk or ride: the map follows you and nothing on it can be changed by a tap.
 */
enum class AppMode {
    PLAN, NAVIGATE;

    companion object {
        /**
         * Mode on app start: Navigate while a tour is still running (service alive, or an
         * unfinished recording in the database, e.g. after a phone restart); otherwise Plan.
         */
        fun onStart(recordingRunning: Boolean, unfinishedRecording: Boolean): AppMode =
            if (recordingRunning || unfinishedRecording) NAVIGATE else PLAN

        /**
         * Map zoom when Navigate starts (owner): Hike 17 (paths readable), every bike 16 (more ahead).
         * Only on entering Navigate – after that the user's own zoom stays until the next start.
         */
        fun navStartZoom(activity: com.onpath.app.routing.Activity): Double =
            if (activity == com.onpath.app.routing.Activity.HIKE) 17.0 else 16.0

        /**
         * The start zoom [wanted] still to be applied when following has begun at [current]; null = nothing to do (no
         * start zoom, or the map is there already). MapController uses it after MapLibre's own transition, which skips
         * the zoom when the map was following already (Plan → Continue, field A-019).
         */
        fun zoomStill(current: Double, wanted: Double?): Double? =
            wanted?.takeIf { kotlin.math.abs(current - it) > 0.05 }
    }
}
