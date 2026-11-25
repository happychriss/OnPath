package com.onpath.app.ui.routes

import com.onpath.app.data.Tour
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint

/**
 * "Deinen alten Plan wiederherstellen?" (owner 2026-11-24, approved from a picture): a new plan REPLACED the plan on the map
 * outside a tour (a saved route or a Zuletzt plan opened, a plan link, a track's plan, a round trip taken) – the old one went
 * under Zuletzt ([SaveState.keepAsDraft]), or it was a saved route as it is. FRAGE "Behalten · Wiederherstellen":
 * Wiederherstellen swaps – the old plan comes back as it was ([RoutesOpenState.answerRestore]), the plan that replaced it goes
 * under Zuletzt with its own activity / T level. Set by a replacement ([SaveState.keepAsDraft] with `asks`), a newer
 * replacement sets its own (or none), a tour start drops it; the box shows only while [entryId] is still in My routes
 * ([box]). Stays until answered.
 */
class RestoreAsk(
    /** The tour that holds the old plan now: its Zuletzt plan, or the saved route it was as it is ([RoutesState.keptAs]). */
    val entryId: Long,
    /** The old plan's activity and T level as it was routed (a saved route may have been switched after loading). */
    val activity: Activity,
    val sac: Int,
    /** The saved route the old plan belonged to while it was changed (Save overwrote that route); null = none, or the entry itself. */
    val link: SavedLink?,
) {
    companion object {
        /**
         * What the box shows: the entry as My routes lists it now ([tours]; its name follows a rename, e.g. the place name a
         * date-named plan gets a moment later). Null = no box: no question, a tour runs, or the entry is gone (deleted, or
         * in the recycle bin because another plan took the Zuletzt place) – its question is gone with it.
         */
        fun box(ask: RestoreAsk?, tours: List<Tour>, touring: Boolean): RestoreBox? {
            if (ask == null || touring) return null
            val t = tours.firstOrNull { it.id == ask.entryId && it.deletedMs == null } ?: return null
            return RestoreBox(t.name, underRecent = t.draft)
        }
    }
}

/** The plan's link to a saved route as [com.onpath.app.ui.plan.PlanDocument.setSaved] takes it. */
class SavedLink(val name: String?, val id: Long, val points: List<GeoPoint>?)

/** The box's facts: the old plan's [name]; [underRecent] = a plan under Zuletzt (else a saved route as it is). */
data class RestoreBox(val name: String, val underRecent: Boolean)
