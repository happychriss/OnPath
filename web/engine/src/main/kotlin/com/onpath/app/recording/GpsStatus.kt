package com.onpath.app.recording

/**
 * Web stand-in for the app's GpsStatus (recording/LocationTracker.kt – an Android file): the same five states, so the
 * app's ui/MapGestures.kt (hold menus, point menu) compiles into the engine unchanged. Keep in step with the app.
 */
enum class GpsStatus(val label: String) {
    NO_PERMISSION("No location permission"),
    OFF("Location is off"),
    SEARCHING("Searching GPS"),
    WEAK("GPS weak"),
    GOOD("GPS good"),
}
