package com.onpath.app.map

/**
 * The planned route's line on the map, in dp: one place for the map ([MapController] "planned-route") and the route
 * overview's map picture ([RouteSnapshot]), so both draw the route equally wide (owner 2026-11-07: the overview's line was
 * far too thin).
 */
object RouteLook {
    const val LINE_DP = 6f
    /** The white casing under it. */
    const val CASING_DP = 10f
}
