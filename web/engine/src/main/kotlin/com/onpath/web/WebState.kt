package com.onpath.web

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.domain.RouteRules
import com.onpath.app.domain.SaveAsk
import com.onpath.app.domain.plan.LivePlan
import com.onpath.app.domain.plan.LivePlanRows
import com.onpath.app.domain.routes.RouteSort
import com.onpath.app.domain.routes.SaveFlow
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.ui.routes.RoutesState
import com.onpath.app.ui.routes.RoutesStore
import com.onpath.app.ui.routes.SaveDialog
import org.teavm.jso.JSExport

/**
 * The app's screen-state slices on the planner's page (rebuild phase 2, docs/design/umbau-phase-2.md §2.2): the
 * web-ready files of :state (state/web-ready.txt) run as they are in the wait window's Wasm instance on the page's own
 * thread (web/app/src/slices.ts) – never in the routing worker, which BRouter blocks while it routes. The page keeps
 * its data (localStorage, the saved routes in IndexedDB) and its drawing; the decisions are the app's.
 *
 * My routes ([RoutesState]): each call gets what the slice's store would read – the page's stored plans as lines – and
 * runs ONE action of the slice on a store in memory ([PageStore]); the store's writes come back as a list the page
 * applies to its own storage (the port, implemented by the page). Ids are numbers the page gives its plans for the call.
 * Save's name dialog ([SaveDialog]): one dialog on the page at a time, held here between the page's calls.
 */
object WebState {
    // ---------------- My routes: RoutesState ----------------

    /**
     * The page's plans as the store lists them: lines "id<TAB>draft 0/1<TAB>deletedMs (-1 = not in the bin)<TAB>
     * createdMs<TAB>name<TAB>points" – points "lat,lon;lat,lon" (the "same points" key; empty = never the same).
     * Order = the store's: the page lists the saved routes first, then the drafts newest first.
     */
    internal fun parseTours(text: String): List<Tour> = text.split('\n').filter { it.isNotBlank() }.mapNotNull { l ->
        val f = l.split('\t')
        if (f.size < 6) return@mapNotNull null
        val pts = f[5].split(';').filter { it.isNotBlank() }.mapNotNull { p ->
            val ll = p.split(',')
            val la = ll.getOrNull(0)?.toDoubleOrNull(); val lo = ll.getOrNull(1)?.toDoubleOrNull()
            if (la == null || lo == null) null else GpxWaypoint(la, lo, null)
        }
        val deleted = f[2].toLongOrNull()?.takeIf { it >= 0 }
        Tour(f[0].toLong(), TourKind.PLANNED, f[4], f[3].toLongOrNull() ?: 0L, null, 0, 0.0, 0.0, 0.0, 0.0, "done", pts,
            deletedMs = deleted, draft = f[1] == "1")
    }

    /** [RoutesStore] over the page's plans for one call: reads them, records every write for the page. */
    class PageStore(tours: List<Tour>) : RoutesStore {
        private val all = tours.toMutableList()
        val writes = ArrayList<String>()
        private fun at(id: Long) = all.indexOfFirst { it.id == id }
        override fun listTours() = all.filter { it.deletedMs == null }
        override fun listDeleted() = all.filter { it.deletedMs != null }.sortedByDescending { it.deletedMs }
        override fun setDraft(id: Long, draft: Boolean): Boolean {
            val i = at(id); if (i < 0) return false
            all[i] = all[i].copy(draft = draft); writes += "{\"op\":\"draft\",\"id\":$id,\"draft\":$draft}"; return true
        }
        override fun setDeleted(id: Long, deletedMs: Long?): Boolean {
            val i = at(id); if (i < 0) return false
            all[i] = all[i].copy(deletedMs = deletedMs)
            writes += if (deletedMs == null) "{\"op\":\"unbin\",\"id\":$id}" else "{\"op\":\"bin\",\"id\":$id,\"ms\":$deletedMs}"
            return true
        }
        override fun loadSegments(id: Long): List<List<TrackPoint>> = emptyList()
        override fun deleteForGood(id: Long) {
            val i = at(id); if (i < 0) return
            all.removeAt(i); writes += "{\"op\":\"gone\",\"id\":$id}"
        }
        override fun rename(id: Long, name: String) {
            val i = at(id); if (i < 0) return
            all[i] = all[i].copy(name = name); writes += "{\"op\":\"rename\",\"id\":$id,\"name\":${Engine.str(name)}}"
        }
        /** A new draft the page writes itself (it has the plan code); [id] given by the page – an existing one is its entry updated. */
        fun insertDraft(id: Long, now: Long): Long {
            val i = at(id)
            if (i >= 0) all[i] = all[i].copy(deletedMs = null, draft = true)
            else all.add(Tour(id, TourKind.PLANNED, "", now, null, 0, 0.0, 0.0, 0.0, 0.0, "done", emptyList(), draft = true))
            return id
        }
    }

    private fun key(points: String) = points.split(';').filter { it.isNotBlank() }.mapNotNull { p ->
        val ll = p.split(','); val la = ll.getOrNull(0)?.toDoubleOrNull(); val lo = ll.getOrNull(1)?.toDoubleOrNull()
        if (la == null || lo == null) null else la to lo
    }

    /** Runs [action] on a [RoutesState] over [tours] with the Zuletzt place [recent]: `{"result":…,"recent":id,"writes":[…]}`. */
    private fun routes(tours: String, recent: Double, now: Double, action: (RoutesState, PageStore) -> String): String {
        val store = PageStore(parseTours(tours))
        val s = RoutesState(store, clock = { now.toLong() }, recent = recent.toLong())
        val result = action(s, store)
        return "{\"result\":$result,\"recent\":${s.recentId},\"writes\":[${store.writes.joinToString(",")}]}"
    }

    /** RoutesState.keepDraft; [newId] = the id the page writes the draft under ([PageStore.insertDraft]); result = the new id or -1. */
    fun keep(tours: String, recent: Double, now: Double, points: String, unsaved: Boolean, savedId: Double, newId: Double): String =
        routes(tours, recent, now) { s, st -> (s.keepDraft(key(points), unsaved, savedId.toLong()) { st.insertDraft(newId.toLong(), now.toLong()) } ?: -1L).toString() }

    fun afterSaved(tours: String, recent: Double, now: Double, savedId: Double, fromId: Double, points: String): String =
        routes(tours, recent, now) { s, _ -> s.afterSaved(savedId.toLong(), fromId.toLong(), key(points)).toString() }

    fun takePlace(tours: String, recent: Double, now: Double, id: Double): String =
        routes(tours, recent, now) { s, _ -> s.takePlace(id.toLong()).toString() }

    fun toBin(tours: String, recent: Double, now: Double, id: Double): String =
        routes(tours, recent, now) { s, _ -> s.toBin(id.toLong()).toString() }

    fun restore(tours: String, recent: Double, now: Double, id: Double): String =
        routes(tours, recent, now) { s, _ -> s.restore(id.toLong()).toString() }

    fun trim(tours: String, recent: Double, now: Double): String =
        routes(tours, recent, now) { s, _ -> s.trimDrafts().toString() }

    fun renameDraft(tours: String, recent: Double, id: Double, dated: String, proposed: String): String =
        routes(tours, recent, 0.0) { s, _ -> s.renameDraft(id.toLong(), dated, proposed).toString() }

    /**
     * The one Zuletzt row ([LivePlanRows.of]): [liveLinked] = the saved route the plan on the map is (-1 = none),
     * [livePoints] 0 = an empty map; [tours] the drafts (outside the bin) and the saved routes shown.
     * `{"live":…,"id":…,"saved":…}` (id -1 = none / a new plan).
     */
    fun row(tours: String, recent: Double, livePoints: Int, liveLinked: Double, liveUnsaved: Boolean): String {
        val list = parseTours(tours).filter { it.deletedMs == null }
        val live = if (livePoints > 0) LivePlan(null, livePoints, 0.0, 0.0, 0.0, liveLinked.toLong(), emptyList(), liveUnsaved) else null
        val r = LivePlanRows.of(live, list.filter { it.draft }, list.filter { !it.draft }, recent.toLong())
        return "{\"live\":${r.live},\"id\":${r.tourId},\"saved\":${r.saved}}"
    }

    /** My routes open on the page (its sort while open; RoutesState.open: Neueste zuerst at each opening). */
    private val shown = RoutesState(PageStore(emptyList()), clock = { 0L })

    /** My routes opens ([fresh]) or comes back while open (a copy saved): the sort, RouteSort.encode as a JSON string. */
    fun open(fresh: Boolean): String {
        if (fresh) shown.open = false
        shown.open = true
        return Engine.str(RouteSort.encode(shown.sort))
    }

    /** A tap on the sort chip [key] (RouteSort.tap on the slice's sort): the sort now. */
    fun tapSort(key: String): String {
        val k = RouteSort.Key.entries.firstOrNull { it.name == key }
        if (k != null) shown.sort = RouteSort.tap(shown.sort, k)
        return Engine.str(RouteSort.encode(shown.sort))
    }

    // ---------------- Save's name dialog: SaveDialog ----------------

    private val dialog = SaveDialog()
    private var asked: SaveAsk? = null
    private var askNo = 0
    private var dated = ""
    private var taken: List<String> = emptyList()

    private fun askJson(a: SaveAsk) = "{\"no\":$askNo,\"kind\":${Engine.str(a.kind.name)},\"name\":${Engine.str(a.name)},\"routeId\":${a.routeId}}"
    private fun lines(s: String) = s.split('\n').filter { it.isNotEmpty() }

    /**
     * Save: the dialog opens ([RouteRules.saveMode]: the route [savedId] named [savedName] (empty = none) still among
     * [plannedIds] → overwrite it, else a new route under the date name [dated]); [taken] = the saved routes' names.
     */
    fun saveRequest(savedId: Double, savedName: String, plannedIds: String, dated: String, taken: String): String {
        val ids = lines(plannedIds).mapNotNull { it.toLongOrNull() }
        val name = savedName.takeIf { it.isNotEmpty() }
        this.dated = dated; this.taken = lines(taken); askNo++
        val a = dialog.request(RouteRules.saveMode(savedId.toLong(), name, ids), name, savedId.toLong(), dated, this.taken, null)
        asked = a
        return askJson(a)
    }

    /** Save as copy of the route [id] named [name]: "<name> (2)". */
    fun saveRequestCopy(name: String, id: Double, taken: String): String {
        askNo++
        dialog.requestCopy(Tour(id.toLong(), TourKind.PLANNED, name, 0L, null, 0, 0.0, 0.0, 0.0, 0.0, "done", emptyList()), lines(taken))
        val a = dialog.ask!!
        asked = a
        return askJson(a)
    }

    /** The name proposal [proposal] for the dialog [no] arrived (SaveSlice: RouteRules.newRouteName first): the ask now, or null when it is not open any more. */
    fun saveProposed(no: Int, proposal: String): String {
        val a = asked ?: return "null"
        if (no != askNo) return "null"
        dialog.proposed(a, RouteRules.newRouteName(proposal.takeIf { it.isNotBlank() }, dated, taken))
        val now = dialog.ask ?: return "null"
        asked = now
        return askJson(now)
    }

    fun saveCancel() { dialog.cancel(); asked = null }

    /** The dialog's Save with [input]; [savedId] = the route the plan is linked to now (-1: none). `{"write":"NEW|OVERWRITE|COPY","routeId":…,"name":…}` or null. */
    fun saveConfirm(input: String, savedId: Double): String {
        val c = dialog.confirm(input, savedId.toLong()) ?: return "null"
        asked = null
        val (kind, id) = when (val w = c.write) {
            is SaveFlow.Write.Copy -> "COPY" to w.routeId
            is SaveFlow.Write.Overwrite -> "OVERWRITE" to w.routeId
            is SaveFlow.Write.New -> "NEW" to -1L
        }
        return "{\"write\":\"$kind\",\"routeId\":$id,\"name\":${Engine.str(c.write.name)}}"
    }
}

// ---- the page's exports (web/app/src/slices.ts; WebApi.kt lists this class in @JSExportClasses) ----
object WebStateApi {
    @JvmStatic @JSExport fun routesKeep(tours: String, recent: Double, now: Double, points: String, unsaved: Boolean, savedId: Double, newId: Double): String =
        WebState.keep(tours, recent, now, points, unsaved, savedId, newId)
    @JvmStatic @JSExport fun routesAfterSaved(tours: String, recent: Double, now: Double, savedId: Double, fromId: Double, points: String): String =
        WebState.afterSaved(tours, recent, now, savedId, fromId, points)
    @JvmStatic @JSExport fun routesTakePlace(tours: String, recent: Double, now: Double, id: Double): String = WebState.takePlace(tours, recent, now, id)
    @JvmStatic @JSExport fun routesToBin(tours: String, recent: Double, now: Double, id: Double): String = WebState.toBin(tours, recent, now, id)
    @JvmStatic @JSExport fun routesRestore(tours: String, recent: Double, now: Double, id: Double): String = WebState.restore(tours, recent, now, id)
    @JvmStatic @JSExport fun routesTrim(tours: String, recent: Double, now: Double): String = WebState.trim(tours, recent, now)
    @JvmStatic @JSExport fun routesRenameDraft(tours: String, recent: Double, id: Double, dated: String, proposed: String): String =
        WebState.renameDraft(tours, recent, id, dated, proposed)
    @JvmStatic @JSExport fun routesRow(tours: String, recent: Double, livePoints: Int, liveLinked: Double, liveUnsaved: Boolean): String =
        WebState.row(tours, recent, livePoints, liveLinked, liveUnsaved)
    @JvmStatic @JSExport fun routesOpen(fresh: Boolean): String = WebState.open(fresh)
    @JvmStatic @JSExport fun routesTapSort(key: String): String = WebState.tapSort(key)
    @JvmStatic @JSExport fun saveRequest(savedId: Double, savedName: String, plannedIds: String, dated: String, taken: String): String =
        WebState.saveRequest(savedId, savedName, plannedIds, dated, taken)
    @JvmStatic @JSExport fun saveRequestCopy(name: String, id: Double, taken: String): String = WebState.saveRequestCopy(name, id, taken)
    @JvmStatic @JSExport fun saveProposed(no: Int, proposal: String): String = WebState.saveProposed(no, proposal)
    @JvmStatic @JSExport fun saveCancel() = WebState.saveCancel()
    @JvmStatic @JSExport fun saveConfirm(input: String, savedId: Double): String = WebState.saveConfirm(input, savedId)
}
