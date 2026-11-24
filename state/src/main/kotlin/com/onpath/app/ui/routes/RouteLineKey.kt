package com.onpath.app.ui.routes

import com.onpath.app.data.Tour
import com.onpath.app.map.ThumbRules

/**
 * The key of the line cache My routes' rows and the overview keep (`RouteLineCache`): a tour's line as read last. It
 * follows what changes the line – the distance, the via points and (2026-11-21, "Fahrten bearbeiten") the ride choice
 * ([ThumbRules.rideTag]): the same points under another choice draw another line.
 */
object RouteLineKey {
    fun of(t: Tour): String = "${t.id}:${t.uuid}:${t.distanceM}:${t.waypoints.size}:${t.createdMs}" + ThumbRules.rideTag(t)
}
