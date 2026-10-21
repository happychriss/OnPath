package com.onpath.app.debug

/**
 * Which [Problems.Level] a MapLibre log message gets (installed in `OnPathApp.installMapLibreLogger`).
 *
 * MapLibre logs plenty of harmless internal notes through `Logger.e` / `Logger.w` – e.g. "LocationComponent#
 * paddingWhileTracking method call is ignored because the camera mode is transitioning". Announcing those on the map
 * is exactly the disruptive message the owner does not want; only problems that break the map may appear on screen.
 * Everything still goes into the problem log (Settings → Diagnostics, export) – nothing is dropped.
 *
 * - known-harmless notes → INFO (logged, never announced)
 * - errors that break the map (style not loaded, file / source unreadable, native exception) → ERROR (announced once)
 * - every other MapLibre warning or error → WARN (Diagnostics only)
 */
object MapLibreLogLevel {

    enum class Priority { WARN, ERROR }

    /** Internal notes of MapLibre that need no action (lower case, matched as substrings). */
    private val HARMLESS = listOf(
        "is ignored because", "method call is ignored", "camera mode is transitioning", "transitioning",
        "can only be used when a camera mode", // e.g. padding/zoom/tilt while tracking, called with CameraMode.NONE
        "already exists", "already been added", "already added",
        "cancel", // tile / request cancellations ("canceled", "cancelled")
        "offline", "unable to resolve host", "no address associated", "failed to connect", "connection",
        "network", "timeout", "timed out", "unknownhost",
        "deprecated",
    )

    /** MapLibre errors after which (part of) the map is not shown / wrong. */
    private val BREAKING = listOf(
        "failed to load style", "style failed", "failed to parse style", "parse style", "style parse",
        "error loading style", "invalid style",
        "glyph", // missing glyph ranges make MapLibre drop whole tiles (owner, 2026-10-19)
        "unable to open", "unable to read", "could not open", "cannot open", "can't open", "no such file",
        "failed to open", "failed to read", "file not found", "permission denied",
        "sqlite", "database", "pmtiles",
        "source not found", "failed to load source",
        "out of memory", "egl", "opengl", "gl error", "render", "native crash", "abort",
    )

    fun of(priority: Priority, tag: String, message: String, hasThrowable: Boolean = false): Problems.Level {
        val m = message.lowercase()
        if (HARMLESS.any { it in m }) return Problems.Level.INFO
        if (priority == Priority.WARN) return Problems.Level.WARN
        if (hasThrowable || BREAKING.any { it in m }) return Problems.Level.ERROR
        return Problems.Level.WARN
    }
}
