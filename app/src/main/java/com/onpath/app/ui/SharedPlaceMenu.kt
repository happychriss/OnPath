package com.onpath.app.ui

import java.util.Locale

/**
 * Google Maps place → go there (owner 2026-10-30): a place shared into OnPath gets the orange ring and, once the map
 * has flown there, the ring's own hold menu ([PoiMenu]: "Start here · Go here" on an empty plan, else "Set as
 * destination" / "Add as stop N" / "Add as new destination", plus "Open in Google Maps") opens by itself – the very menu
 * a hold on the ring opens, under the same rules (Plan, no shown track: [AppViewModel.canEditMap]).
 *
 * Once per share: the same place shared again right after (no other shared place's menu in between) shows the ring
 * only – hold it for the menu. The menu does not open
 * when the user went on meanwhile – moved the map by hand, opened another menu or screen, the ring is gone.
 * Pure state; AppViewModel holds one, MapScreen asks [check] whenever the camera stops.
 */
class SharedPlaceMenu {
    enum class Step { NONE, WAIT, OPEN, DROP }

    /** Key of the shared place whose menu waits for the camera; null = nothing waits. */
    var waiting: String? = null
        private set
    private var lastOpened: String? = null

    /** A shared place is shown: its menu waits for the camera. False = its menu opened before (same share again). */
    fun arrived(key: String): Boolean {
        if (key == lastOpened) { waiting = null; return false }
        waiting = key
        return true
    }

    /** The user does something else (moved the map by hand …): the menu no longer opens by itself. */
    fun cancel(): Boolean = (waiting != null).also { waiting = null }

    /**
     * The camera stopped (or a screen state changed) – what to do with the waiting menu.
     * @param markKey key of the ring shown now (null: no ring – added, closed or replaced by a search)
     * @param offPx distance camera centre ↔ ring in pixels (null: unknown)
     * @param arrivedPx at most this far off counts as "the camera is there"
     * @param canEdit the map can be edited ([AppViewModel.canEditMap]); hold menus work only then
     * @param elsewhere another menu, the search, My routes or Settings is open – the user went on
     * @param loading the map-download modal for the place is open: wait until it is gone
     */
    fun check(markKey: String?, offPx: Double?, arrivedPx: Double, canEdit: Boolean, elsewhere: Boolean, loading: Boolean): Step {
        val w = waiting ?: return Step.NONE
        val step = when {
            markKey != w || !canEdit || elsewhere -> Step.DROP
            loading || offPx == null || offPx > arrivedPx -> Step.WAIT
            else -> Step.OPEN
        }
        if (step == Step.OPEN) lastOpened = w
        if (step != Step.WAIT) waiting = null
        return step
    }

    companion object {
        /** Camera centre within this of the ring = the flight arrived (flyTo centres exactly on it). */
        const val ARRIVED_DP = 24f
        /** Wait after a camera stop before checking: a follow-up move (fly after the cell modal) may start. */
        const val SETTLE_MS = 150L

        /** One shared place: position (~1 m) + name. */
        fun key(lat: Double, lon: Double, name: String?): String =
            String.format(Locale.ROOT, "%.5f,%.5f|%s", lat, lon, name ?: "")
    }
}
