package com.onpath.app.ui.routes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.domain.plan.LivePlanRows
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.Drafts
import com.onpath.app.ui.OpenAsk
import com.onpath.app.ui.RouteRules
import com.onpath.app.ui.RoutesTab

/**
 * What My routes needs of the tour database (TourDb in the app, a map in tests): the list (everything not in the
 * recycle bin), Zuletzt ↔ saved, the recycle bin, the draft limit and a tour's line.
 */
interface RoutesStore {
    fun listTours(): List<Tour>
    fun setDraft(id: Long, draft: Boolean): Boolean
    fun setDeleted(id: Long, deletedMs: Long?): Boolean
    fun pruneDrafts(max: Int): Int
    fun loadSegments(id: Long): List<List<TrackPoint>>
    /** Removes a tour for good (only drafts a saved route replaced – never a saved route, never the recycle bin). */
    fun deleteForGood(id: Long)
}

/**
 * My routes' state and decisions (audit package 4, stage 3 – out of AppViewModel, Google's "state holder"): whether the
 * list is open ([open]), its tab ([tab]), the tours shown ([tours]), the "✓ Gerade gespeichert" mark ([justSavedId]),
 * "Zuletzt" (keep / save / delete a draft – owner 2026-11-06), the target of the saved box's "In Meine Touren ›" and
 * what a row tap does ([openAsk]). Android-free: the database sits behind [RoutesStore], so the flows are testable on
 * the JVM (RoutesFlowPropertyTest). The store calls block – the view model runs them on its IO dispatcher and keeps
 * every side effect (backup, browser sync, messages) and the plan itself; it forwards its public names here.
 */
class RoutesState(private val store: RoutesStore, private val clock: () -> Long = System::currentTimeMillis) {

    /** Called when My routes closes (the view model drops its line at the top). */
    var onClosed: () -> Unit = {}

    private var shown by mutableStateOf(false)
    /** My routes list (☰ → My routes) is open; closing drops the line at the top and the "✓ Gerade gespeichert" mark. */
    var open: Boolean
        get() = shown
        set(v) { if (!v) { onClosed(); justSavedId = -1L }; shown = v }

    /** The last chosen tab (Planned / Done); kept while the app runs, Planned after a restart. */
    var tab by mutableStateOf(RoutesTab.PLANNED)

    /** The tours My routes shows (state "done", not in the recycle bin), newest first. */
    var tours by mutableStateOf<List<Tour>>(emptyList())
        private set

    /** The tour just saved (from Zuletzt or by the saved box): "✓ Gerade gespeichert" on it until My routes closes. */
    var justSavedId by mutableStateOf(-1L)

    /** Reads the list from the store (blocking – call it off the main thread); [map] adds the model times. */
    fun load(map: (Tour) -> Tour = { it }): Loaded {
        val seq = loads.incrementAndGet() // before the read: a higher number saw every write made before it was asked
        return Loaded(store.listTours().filter { it.state == "done" }.map(map), seq)
    }

    /**
     * Shows a list read by [load] – unless a list read later is shown already (false). The loads run in parallel on the
     * IO threads and finish in any order: a slow one read before a save must never put its older list over the newer
     * one (E2E 2026-11-07: a route saved during a tour missing from My routes).
     */
    fun show(loaded: Loaded): Boolean {
        if (loaded.seq < shownSeq) return false
        shownSeq = loaded.seq
        tours = loaded.tours
        return true
    }

    /** A list of My routes read by [load], numbered in the order the reads began. */
    class Loaded(val tours: List<Tour>, val seq: Long)

    private val loads = java.util.concurrent.atomic.AtomicLong()
    private var shownSeq = 0L

    /** The saved box → "In Meine Touren ›": My routes on Gemacht with that tour marked (owner 2026-11-06 22:30). */
    fun showSaved(tourId: Long) {
        tab = RoutesTab.DONE
        open = true
        justSavedId = tourId // after opening: opening clears nothing, closing My routes clears it
    }

    // ---------------- Zuletzt ----------------

    /**
     * A plan with the points [key] leaves the map unsaved ([unsaved]): is it worth a row under Zuletzt? Two points at
     * least ([Drafts.keeps]) and not already there – neither as a draft with the same points (Undo, clear again) nor as a
     * saved route with the same points (blocking – reads the store).
     */
    fun keepsDraft(key: List<Pair<Double, Double>>, unsaved: Boolean): Boolean {
        if (!Drafts.keeps(key.size, unsaved)) return false
        return store.listTours().none { it.kind == TourKind.PLANNED && keyOf(it) == key }
    }

    /**
     * Keeps a plan under Zuletzt: [insert] writes it (the view model knows name, route and times) when [keepsDraft] still
     * holds; then only the newest [Drafts.MAX] drafts stay. Returns the new id, null = not kept (blocking).
     */
    fun keepDraft(key: List<Pair<Double, Double>>, unsaved: Boolean, insert: () -> Long): Long? {
        if (!keepsDraft(key, unsaved)) return null
        val id = insert()
        val gone = store.pruneDrafts(Drafts.MAX)
        if (gone > 0) Problems.trace("routes", "Zuletzt: $gone oldest plan(s) over ${Drafts.MAX} removed")
        return id
    }

    /**
     * "Los geht's" updated a saved route in place (owner 2026-11-07: no "Name (2)" copies): its version before, with the
     * points [oldKey], is kept under Zuletzt so nothing is lost. Call it AFTER the update – before it, [oldKey] is still
     * the route's own points and the draft rules refuse it (never a draft with a saved route's points). Blocking.
     */
    fun keepReplaced(oldKey: List<Pair<Double, Double>>, insert: () -> Long): Long? = keepDraft(oldKey, unsaved = true, insert)

    /**
     * A plan was saved as a new route with the points [key]: it takes the place of the draft it came from
     * ([fromDraftId], -1 = none) and of any draft with the same points – never a second entry (blocking). Returns how
     * many drafts went.
     */
    fun afterSaved(fromDraftId: Long, key: List<Pair<Double, Double>>): Int {
        val same = store.listTours().filter { it.draft && (it.id == fromDraftId || keyOf(it) == key) }
        same.forEach { store.deleteForGood(it.id) }
        return same.size
    }

    /** Zuletzt → Speichern: the draft becomes a saved route (blocking). False when it is gone. */
    fun saveDraft(id: Long): Boolean = store.setDraft(id, false)

    /** Zuletzt → Löschen, or ⋮ → Löschen on any tour: into the recycle bin at once, no question (blocking). */
    fun toBin(id: Long): Boolean = store.setDeleted(id, clock())

    /** After [saveDraft] succeeded: the mark on the row (main thread). */
    fun markSaved(id: Long) { justSavedId = id }

    // ---------------- opening ----------------

    /**
     * What a tap on [t] (after the overview's "Planen" / "Folgen") does ([RouteRules.openAsk]): during a tour it is
     * followed at once and the recording goes on (owner 2026-11-06 22:05) – nothing here ever ends a tour.
     */
    fun openAsk(t: Tour, navigating: Boolean, points: List<GeoPoint>, saved: List<GeoPoint>?): OpenAsk =
        RouteRules.openAsk(t.kind, t.waypoints.size, navigating, points, saved, hasLine = t.distanceM > 0.0)

    /**
     * The line of [t] for its preview and overview (owner 2026-11-07): the stored points, else its via points / GPX
     * waypoints; the live row ([LivePlanRows.LIVE_ID]) shows [live]. Blocking; a store failure is reported, never thrown.
     */
    fun line(t: Tour, live: () -> List<GeoPoint>): List<List<GeoPoint>> {
        if (t.id == LivePlanRows.LIVE_ID) return listOf(live())
        val segs = runCatching { store.loadSegments(t.id) }
            .onFailure { Problems.warn("routes", "The line of tour ${t.id} for its preview could not be read", it) }
            .getOrDefault(emptyList())
            .map { s -> s.map { GeoPoint(it.lat, it.lon) } }.filter { it.isNotEmpty() }
        return segs.ifEmpty { listOf(t.waypoints.map { GeoPoint(it.lat, it.lon) }) }
    }

    companion object {
        /** A tour's points as the key Zuletzt compares (lat, lon). */
        fun keyOf(t: Tour): List<Pair<Double, Double>> = t.waypoints.map { it.lat to it.lon }
    }
}
