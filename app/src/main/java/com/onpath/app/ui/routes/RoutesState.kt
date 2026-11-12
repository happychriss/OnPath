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
    /** The recycle bin (deleted_at set), newest deletion first. */
    fun listDeleted(): List<Tour>
    fun setDraft(id: Long, draft: Boolean): Boolean
    fun setDeleted(id: Long, deletedMs: Long?): Boolean
    fun loadSegments(id: Long): List<List<TrackPoint>>
    /** Removes a tour for good (only drafts a saved route replaced – never a saved route, never the recycle bin). */
    fun deleteForGood(id: Long)
    fun rename(id: Long, name: String)
}

/**
 * My routes' state and decisions (audit package 4, stage 3 – out of AppViewModel, Google's "state holder"): whether the
 * list is open ([open]), its tab ([tab]), the tours shown ([tours]), the "✓ Gerade gespeichert" mark ([justSavedId]),
 * "Zuletzt" (one plan – the one worked on last – kept, saved or deleted; owner 2026-11-06 / 2026-11-12), the target of
 * the saved box's "In Meine Touren ›" and
 * what a row tap does ([openAsk]). Android-free: the database sits behind [RoutesStore], so the flows are testable on
 * the JVM (RoutesFlowPropertyTest). The store calls block – the view model runs them on its IO dispatcher and keeps
 * every side effect (backup, browser sync, messages) and the plan itself; it forwards its public names here.
 */
class RoutesState(private val store: RoutesStore, private val clock: () -> Long = System::currentTimeMillis,
                  /** The tour under Zuletzt from the last run ([recentId]; the view model reads it from the prefs). */
                  recent: Long = -1L) {

    /** Called when My routes closes (the view model drops its line at the top). */
    var onClosed: () -> Unit = {}

    /** The Zuletzt place changed hands ([recentId]): the view model writes it to the prefs. */
    var onRecent: (Long) -> Unit = {}

    private var shown by mutableStateOf(false)
    /** My routes list (☰ → My routes) is open; closing drops the line at the top and the "✓ Gerade gespeichert" mark. */
    var open: Boolean
        get() = shown
        set(v) { if (!v) { onClosed(); justSavedId = -1L }; shown = v }

    /** The last chosen tab (Planned / Done); kept while the app runs, Planned after a restart. */
    var tab by mutableStateOf(RoutesTab.PLANNED)

    /** The order of the saved routes / done tours ([RouteSort]); the view model reads it from and writes it to the prefs. */
    var sort by mutableStateOf(RouteSort.DEFAULT)

    /** The tours My routes shows (state "done", not in the recycle bin), newest first. */
    var tours by mutableStateOf<List<Tour>>(emptyList())
        private set

    /** The tour just saved (from Zuletzt or by the saved box): "✓ Gerade gespeichert" on it until My routes closes. */
    var justSavedId by mutableStateOf(-1L)

    /** Reads the list from the store (blocking – call it off the main thread); [map] adds the model times. */
    fun load(map: (Tour) -> Tour = { it }): List<Tour> = store.listTours().filter { it.state == "done" }.map(map)

    private val loads = java.util.concurrent.atomic.AtomicInteger()

    /** A read of the list starts: its number for [show]. */
    fun beginLoad(): Int = loads.incrementAndGet()

    /**
     * Shows a list read by [load] – unless a later read started meanwhile ([beginLoad]): two reads overlap (the tour
     * start's save, then My routes opened after a new row – E2E 2026-11-07) and the older one ending last must not hide
     * the newer rows.
     */
    fun show(list: List<Tour>, load: Int = loads.get()) { if (load == loads.get()) tours = list }

    /** The saved box → "In Meine Touren ›": My routes on Gemacht with that tour marked (owner 2026-11-06 22:30). */
    fun showSaved(tourId: Long) {
        tab = RoutesTab.DONE
        open = true
        justSavedId = tourId // after opening: opening clears nothing, closing My routes clears it
    }

    // ---------------- Zuletzt ----------------

    /**
     * The tour under "Zuletzt" while the map is empty (owner 2026-11-12: exactly one plan there – the one worked on last,
     * saved or not; [com.onpath.app.domain.plan.LivePlanRows]): the one draft, or the saved route last left, saved or
     * loaded; -1 = none known (the newest draft shows, if any). Kept across app starts ([onRecent]).
     */
    var recentId by mutableStateOf(recent)
        private set

    /**
     * The plan [id] (a draft or a saved route) takes the Zuletzt place: every other draft goes to the recycle bin – the
     * replaced plan stays recoverable, never gone for good (owner 2026-11-12); a saved route is never touched. A draft
     * that waits in the bin because another plan took the place meanwhile (loaded from Zuletzt, then left unchanged)
     * comes back out. Returns how many drafts went (blocking).
     */
    private fun takePlace(id: Long): Int {
        if (id < 0) return 0
        val gone = store.listTours().filter { it.draft && it.id != id }
        gone.forEach { store.setDeleted(it.id, clock()) }
        if (gone.isNotEmpty()) Problems.trace("routes", "Zuletzt: ${gone.size} plan(s) replaced by $id – into the recycle bin")
        recentId = id
        onRecent(id)
        return gone.size
    }

    /**
     * A plan with the points [key] leaves the map (cleared, replaced by a loaded route / link / track): what stays under
     * Zuletzt? One point is no plan – nothing. Saved as it is ([unsaved] false; [savedId] = the saved route or the draft
     * it was loaded from): that tour takes the place (a draft back out of the bin when needed; gone for good → kept
     * anew below). Else a draft with these points – unless one is there already, or a saved route with the same points
     * (never a second entry): that one takes the place. [insert] writes the new draft (the view model knows name, route
     * and times); the one draft before it goes to the recycle bin ([takePlace]). Returns the new id, null = no new
     * draft (blocking).
     */
    fun keepDraft(key: List<Pair<Double, Double>>, unsaved: Boolean, savedId: Long = -1L, insert: () -> Long): Long? {
        if (key.size < 2) return null
        if (!unsaved) {
            if (savedId < 0) return null // saved as it is, nowhere known: nothing to keep (as before)
            val own = store.listTours().firstOrNull { it.id == savedId }
                ?: store.listDeleted().firstOrNull { it.id == savedId && it.draft }?.also { store.setDeleted(it.id, null) }
            if (own != null) { takePlace(own.id); return null }
            Problems.trace("routes", "Zuletzt: the plan's tour $savedId is gone for good – kept as a new plan")
        }
        val same = store.listTours().firstOrNull { it.kind == TourKind.PLANNED && keyOf(it) == key }
        if (same != null) { takePlace(same.id); return null }
        if (!Drafts.keeps(key.size, unsaved = true)) return null
        val id = insert()
        takePlace(id)
        return id
    }

    /**
     * The place name of a draft kept under its date name [dated] came ([proposed], null = none in time): the draft takes
     * it – unless the user renamed, saved or deleted it meanwhile (the list holds only rows outside the bin). True when
     * renamed (blocking).
     */
    fun renameDraft(id: Long, dated: String, proposed: String?): Boolean {
        val name = proposed?.trim()?.takeIf { it.isNotEmpty() && it != dated } ?: return false
        if (store.listTours().none { it.id == id && it.draft && it.name == dated }) return false
        store.rename(id, name)
        return true
    }

    /**
     * "Los geht's" updated a saved route in place (owner 2026-11-07: no "Name (2)" copies): its version before, with the
     * points [oldKey], is kept under Zuletzt so nothing is lost. Call it AFTER the update – before it, [oldKey] is still
     * the route's own points and the draft rules refuse it (never a draft with a saved route's points). Blocking.
     */
    fun keepReplaced(oldKey: List<Pair<Double, Double>>, insert: () -> Long): Long? = keepDraft(oldKey, unsaved = true, insert = insert)

    /**
     * A plan was saved as the route [savedId] (new, or updated in place) with the points [key]: it takes the place of
     * the draft it came from ([fromDraftId], -1 = none) and of any draft with the same points – never a second entry –
     * and the Zuletzt place (blocking). Returns how many drafts went.
     */
    fun afterSaved(savedId: Long, fromDraftId: Long, key: List<Pair<Double, Double>>): Int {
        val same = store.listTours().filter { it.draft && (it.id == fromDraftId || keyOf(it) == key) }
        same.forEach { store.deleteForGood(it.id) }
        takePlace(savedId)
        return same.size
    }

    /**
     * Zuletzt → Speichern: the draft becomes a saved route and stays the Zuletzt row, now with the "Gespeichert" mark
     * (blocking). False when it is gone.
     */
    fun saveDraft(id: Long): Boolean {
        if (!store.setDraft(id, false)) return false
        takePlace(id)
        return true
    }

    /** Zuletzt → Löschen, or ⋮ → Löschen on any tour: into the recycle bin at once, no question (blocking). */
    fun toBin(id: Long): Boolean = store.setDeleted(id, clock())

    /**
     * Once at the first start with the one-plan rule (owner 2026-11-12; the view model guards it with a pref): the
     * drafts an older version collected beyond the newest [Drafts.MAX] go to the recycle bin – nothing is lost for good.
     * Idempotent; returns how many went (blocking).
     */
    fun trimDrafts(): Int {
        val gone = store.listTours().filter { it.draft }.drop(Drafts.MAX)
        gone.forEach { store.setDeleted(it.id, clock()) }
        return gone.size
    }

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
