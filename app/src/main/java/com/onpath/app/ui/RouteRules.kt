package com.onpath.app.ui

import com.onpath.app.data.TourKind
import com.onpath.app.routing.GeoPoint

/** Which items of the ☰ menu can be used right now (disabled ones stay visible, greyed). */
data class RouteMenuState(
    val load: Boolean,
    val save: Boolean,
    val manage: Boolean = true,
    val startRecording: Boolean,
    val settings: Boolean = true,
    val updates: Boolean = true,
)

/** What sheet ⋯ → Save route does: ask for a name (new entry), or offer Save (overwrite) / Save as new…. */
enum class SaveMode { NAME_DIALOG, OVERWRITE_OR_NEW }

/**
 * "Enabled when" rules of the menus and route management (pure, unit tested; docs/ux-two-modes.md "Menus").
 * Navigating = Navigate mode or a tour recording (also when looked at from Plan).
 */
object RouteRules {
    /**
     * @param hasRoute a planned route line exists
     * @param pointCount planned points (two or more = a route is coming, even while it is calculated)
     * @param savedCount saved routes / tours in My routes
     */
    fun menu(hasRoute: Boolean, pointCount: Int, savedCount: Int, navigating: Boolean, recording: Boolean) = RouteMenuState(
        load = savedCount > 0 && !navigating && !recording,
        save = hasRoute,
        startRecording = !hasRoute && pointCount < 2 && !recording && !navigating,
    )

    /**
     * Sheet ⋯ → Save route: when the plan was loaded from (or last saved as) a planned route that still exists, offer to
     * overwrite it or save a new one; otherwise the name dialog (always a new entry).
     * @param savedId tour id the plan was last saved / loaded as (-1 = none)
     * @param plannedIds ids of the planned routes in My routes
     */
    fun saveMode(savedId: Long, savedName: String?, plannedIds: Collection<Long>): SaveMode =
        if (savedId >= 0 && savedName != null && savedId in plannedIds) SaveMode.OVERWRITE_OR_NEW else SaveMode.NAME_DIALOG

    /** A new name from the rename / save dialog: trimmed; blank keeps [old]. */
    fun cleanName(input: String, old: String): String = input.trim().ifBlank { old }

    /**
     * Clear in the sheet's ⋯: something to clear – also during a tour (owner Q2 2026-10-23): like deleting every point,
     * the route guidance ends and the recording goes on; Undo brings plan and guidance back (PlanGuidance).
     */
    fun canClear(pointCount: Int): Boolean = pointCount > 0

    /** Opening this item puts its points into the plan (else it is only shown as a track). */
    fun replacesPlan(kind: TourKind, waypointCount: Int): Boolean = kind == TourKind.PLANNED && waypointCount >= 2

    /** While navigating only tracks can be opened; loading into the plan waits for the end of the tour. */
    fun canOpen(kind: TourKind, waypointCount: Int, navigating: Boolean): Boolean =
        !navigating || !replacesPlan(kind, waypointCount)

    /**
     * The plan is worth keeping and differs from what was last saved or loaded ([saved], null = never).
     * One point is no route yet (like PlanClear.needsConfirm).
     */
    fun isUnsaved(points: List<GeoPoint>, saved: List<GeoPoint>?): Boolean =
        points.size >= 2 && (saved == null || points.size != saved.size ||
            points.indices.any { points[it].lat != saved[it].lat || points[it].lon != saved[it].lon }) // elevation does not count

    /** Ask "Replace current plan?" before loading this item. */
    fun askBeforeLoading(kind: TourKind, waypointCount: Int, points: List<GeoPoint>, saved: List<GeoPoint>?): Boolean =
        replacesPlan(kind, waypointCount) && isUnsaved(points, saved)
}
