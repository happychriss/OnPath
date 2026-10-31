package com.onpath.app.ui

import com.onpath.app.navigation.NavSession
import com.onpath.app.routing.GeoPoint

/**
 * Bug 02 (owner 2026-10-31): Undo during a tour takes back the followed route exactly. Each followed session that a plan
 * edit replaced is kept with the plan points it followed; when the plan is back at those points (Undo, Redo, or the
 * same edit by hand) that session is followed again as it was – a way there ("Guide me") or a taken re-route included,
 * without routing it again. Only for the running tour ([clear] at its start and end); newest wins, at most [max].
 */
class FollowHistory(private val max: Int = 20) {
    private val items = ArrayList<Pair<List<Pair<Double, Double>>, NavSession>>()

    /** [session] (followed for the plan [points]) is about to be replaced by a plan edit. */
    fun remember(points: List<GeoPoint>, session: NavSession) {
        if (points.size < 2) return
        val k = key(points)
        items.removeAll { it.first == k }
        items += k to session
        while (items.size > max) items.removeAt(0)
    }

    /** The session once followed for exactly these plan [points], or null. */
    fun find(points: List<GeoPoint>): NavSession? { val k = key(points); return items.lastOrNull { it.first == k }?.second }

    /** By position only: a session's stops come back from nav-session.json without the plan points' heights. */
    private fun key(points: List<GeoPoint>) = points.map { it.lat to it.lon }

    fun clear() = items.clear()
}
