@file:JvmName("WebApiKt")
@file:JvmMultifileClass

package com.onpath.web

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.domain.RouteRules
import com.onpath.app.domain.RoutesTab
import com.onpath.app.domain.SaveAsk
import com.onpath.app.domain.SaveMode
import com.onpath.app.domain.plan.LivePlan
import com.onpath.app.domain.plan.LivePlanRows
import com.onpath.app.domain.routes.RouteSort
import com.onpath.app.domain.routes.SaveFlow
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.PlanCode
import com.onpath.app.suggest.SuggestSettings
import com.onpath.app.ui.routes.RoutesState
import com.onpath.app.ui.routes.RoutesStore
import com.onpath.app.ui.routes.SaveDialog
import org.teavm.jso.JSExport

/*
 * The `:state` slices that live ON THE PAGE (docs/items/2026-11-19-how-we-split-web.md: in the second Wasm instance the
 * wait window loads, web/app/src/waitengine.ts – not in the routing worker) and their exports: the app's REAL
 * [RoutesState] and [SaveDialog] (state/web-ready.txt, compiled as they are – build.gradle.kts 4.), one instance each
 * per engine instance. A proposal (docs/items/2026-11-20-web-slices-proposal.md); the page does not call it yet.
 *
 * WHY THE TWO @file LINES ABOVE: TeaVM exports only the `@JSExport` static methods of ITS MAIN CLASS as module exports
 * (teavm-jso-impl 0.16.0, WasmGCJsoCommonGenerator: `entryPoint.equals(className)`), and the main class is `WebApiKt`
 * (build.gradle.kts `mainClass`). `@JvmMultifileClass` makes this file's top-level functions part of that same class, so
 * the exports can stand here and not in WebApi.kt. WebApi.kt carries the same two lines; a third file with exports needs
 * them too. Without them the functions below compile, pass the JVM tests and are silently NOT exported.
 *
 * STYLE as the other exports: strings / numbers / booleans in, a JSON string out; nothing calls from Kotlin into the page
 * (the wait window's pattern: the page calls, reads the answer, asks for the state – time is passed in, [now] in ms).
 * No export throws: a failure is reported to [Problems] and answered as `{"error":"…"}` (as WebSuggest / WebImport do).
 *
 * IDS. The app's tours have Long ids, the page's plans string ids in two stores: its drafts (`RecentPlan`, recent.ts,
 * localStorage) and its saved plans / tracks (`SavedPlan`, sync.ts, IndexedDB). A row is named here as the page's
 * `Place` does it: `kind` = "draft" | "saved" + `id`. [WebRoutesStore] gives each (kind, id) a Long handle for the slice
 * (stable while the engine instance lives; never shown to the page).
 *
 * ---- recent.ts → the export → the slice member it runs -----------------------------------------------------------
 *  recentPlans() / binnedPlans() / zuletztPlace()  → routesStateJson()            store lists, RoutesState.recentId
 *  recentRow(live, drafts, savedIds, place)        → routesRecentRow(…) / "row"   LivePlanRows.of (core, what the app shows)
 *  takePlace(place, now)                           → routesTakePlace(kind, id, now)                  RoutesState.takePlace
 *  keepRecent(code, title, points, replaces, now)  → routesKeepDraft(newId, code, title, points, unsaved, savedKind,
 *    + main.ts keepAsRecent's saved / twin checks      savedId, now)                                 RoutesState.keepDraft
 *  afterSaved(savedId, fromId, points, now)        → routesAfterSaved(savedId, fromDraftId, code, now)  RoutesState.afterSaved
 *  plans.ts saveRecent (Zuletzt → Speichern)       → routesSaveDraft(id, now) [+ routesStoreMoved]   RoutesState.saveDraft
 *  host.setJustSaved(id)                           → routesMarkSaved(kind, id)                       RoutesState.markSaved
 *  toBin(id, now)                                  → routesToBin(kind, id, now)                      RoutesState.toBin
 *  restoreRecent(id, now), savedRestored(id, twins, now) → routesRestore(kind, id, now)              RoutesState.restore
 *  renameRecentIf(id, from, to)                    → routesRenameDraft(id, dated, proposed)          RoutesState.renameDraft
 *  startUp(now), its trim part                     → routesTrimDrafts(now)                           RoutesState.trimDrafts
 *  (the list open / its tab / the sort / the saved box – page state today) → routesOpen(open), routesTab(tab),
 *    routesSortTap(key), routesShowSaved(kind, id)   RoutesState.open, .tab, .sort (RouteSort.tap), .showSaved
 * NOT here, they stay the page's own (no member of RoutesState does them): renameRecent (⋮ → Umbenennen: the app's
 * rename is RoutesSlice's, not web-ready), daysLeft / BIN_DAYS and startUp's 30-day removal (the app's RecycleBin, not
 * in a listed file), dropRecent (unused today), the storage cap. Not exported though RoutesState has them: load / beginLoad /
 * show (every export below re-reads the list itself), keepReplaced (= keepDraft with unsaved, the page has no "Los
 * geht's"), openAsk and line (the page has its own Planen flow and keeps the lines with its stats).
 *
 * ---- the save dialog of plans.ts → the export → the slice member ---------------------------------------------------
 *  saveRoute: NEW / OVERWRITE + nameDialog(false, …)   → saveDialogRequest(overwrite, savedName, savedId, dated, taken)  SaveDialog.request
 *  the `later` place proposal                          → saveDialogProposed(token, proposal, dated, taken)   SaveDialog.proposed
 *  copyRoute + nameDialog(true, …) (⋮ → Save as copy)  → saveDialogRequestCopy(savedId, name, taken)         SaveDialog.requestCopy
 *  Cancel / Escape                                     → saveDialogCancel()                                  SaveDialog.cancel
 *  Save / Enter + cleanName                            → saveDialogConfirm(input, savedId)                   SaveDialog.confirm
 * NOT here: the dialog's DOM, the "typed" rule (a proposal must not replace what the user typed – the page does not
 * call saveDialogProposed then), `then` (SaveAsk.then is a Kotlin lambda; the page keeps its own continuation – null is
 * passed), storedTitle (the copy marker of the sync item), roomFor (250 tours), "Speichern unter" (web only: the page
 * can use saveDialogRequest(false, …) with its own first name – see the hand-over file).
 */

// ---------------------------------------------------------------------------------------------------------------------
// JSON (the engine's own small reader – SuggestSettings' – and a writer; no library: org.json would come into the Wasm)

internal object WebJson {
    /** Object (LinkedHashMap, order kept), array (List), Double, String, Boolean or null; throws on text that is no JSON. */
    fun parse(text: String): Any? = SuggestSettings.parse(text).root.v

    fun str(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\t' -> append("\\t"); '\r' -> append("\\r")
            else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }

    /** A number as JSON: whole numbers without ".0" (ids, times in ms – exact up to 2^53), NaN / infinite = null. */
    fun num(d: Double): String = when {
        d.isNaN() || d.isInfinite() -> "null"
        d == Math.floor(d) && Math.abs(d) < 9.0e15 -> d.toLong().toString()
        else -> d.toString()
    }

    fun write(v: Any?): String = when (v) {
        null -> "null"
        is String -> str(v)
        is Boolean -> v.toString()
        is Double -> num(v)
        is Number -> num(v.toDouble())
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { str(it.key.toString()) + ":" + write(it.value) }
        is List<*> -> v.joinToString(",", "[", "]") { write(it) }
        else -> str(v.toString())
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// The store seam

/**
 * [RoutesStore] for the page: the page's stored plans as a PRELOADED list, and a queue of the writes the slice made.
 *
 * Why a seam and not the storage: [RoutesStore] is synchronous (the app's view model calls it on its IO thread), the
 * page's storage is not (saved plans: IndexedDB behind `PlanSync`, asynchronous; drafts: localStorage). So:
 *  - [load] – the page hands over what it stores: `{"saved":[SavedPlan…],"drafts":[RecentPlan…],"binned":[BinnedRoute…],
 *    "recent":{"kind","id"}|null}` – once at the start and again after every change of its storage;
 *  - the slice reads and writes THIS list (at once, synchronously);
 *  - each write is also queued; the page drains the queue ([takeWrites]) and does the same in its storage.
 *
 * What a row is read from (ONLY these fields; everything else in the object is kept as it came and handed back
 * untouched in the write's `row`):
 *   SavedPlan   (sync.ts)    id · kind ("plan" → PLANNED, "track" → IMPORTED) · title → name · plan (the plan code → the
 *                            points, [PlanCode.parse]) · updatedAt → createdMs;              draft = false, not in the bin
 *   RecentPlan  (recent.ts)  id · title → name · code (→ the points) · at → createdMs · deletedAt → deletedMs;  draft = true
 *   BinnedRoute (sync.ts)    id · kind · title · plan · at → createdMs · deletedAt → deletedMs;                 draft = false
 * and what [RoutesState] reads of the [Tour] made from it: id, kind, name, createdMs (the order "newest first"), state
 * (always "done"), waypoints (the Zuletzt key: lat / lon of the plan's points), draft, deletedMs. Every other Tour
 * field is its default (distance 0, no activity …) – nothing of RoutesState's exported part reads them. The points come
 * from the plan CODE on both sides (a draft's `points` text is not read), so "the same points" is the same rounding
 * everywhere – as the page's samePointsAs does it.
 *
 * The writes, in the order the slice made them (`routesStoreTakeWrites()` → a JSON array; `kind` + `id` name the row in
 * the page's stores; `row` = the page's own object for it with the change applied, unknown fields untouched):
 *   {"op":"insert","kind":"draft","id":…,"row":{id,code,title,points,at}}     a new draft (RoutesState.keepDraft)
 *   {"op":"setDeleted","kind":…,"id":…,"deletedAt":ms|null,"row":{…}}        into the recycle bin / back out of it
 *   {"op":"setDraft","kind":…,"id":…,"draft":false,"row":{…}}                a draft became a saved route (saveDraft)
 *   {"op":"delete","kind":…,"id":…}                                          gone for good (a saved route took its place)
 *   {"op":"rename","kind":…,"id":…,"title":…,"row":{…}}                      the place name for a draft (renameDraft)
 *   {"op":"recent","place":{"kind":…,"id":…}|null}                           the Zuletzt place changed hands (RoutesState.onRecent)
 *
 * GUARANTEES:
 *  - a write is visible to the slice at once (the next call reads it) – the slice never waits for the page's storage;
 *  - the queue keeps every write, in order, until it is taken; taking empties it (take once, apply all);
 *  - [load] never loses a queued write: while writes wait it is REFUSED (`{"error":…,"pending":n}`, nothing changes) –
 *    the page drains and applies first, then loads;
 *  - a row's handle (so the Zuletzt place and the "just saved" mark) survives a [load] as long as its (kind, id) is the
 *    same, and a change of them the page announces with [moved] (a draft saved by the page gets a new id in another store);
 *  - a row that cannot be read (no string id) is left out and reported, a plan code that cannot be read gives a row
 *    without points (reported) – a load never throws.
 * NOT GUARANTEED:
 *  - the page's storage has the write only after the page applied it: a page closed between a slice call and the drain
 *    loses that write (today's recent.ts writes localStorage in the same call). Drain and apply in the same task as the
 *    call for the drafts (localStorage is synchronous); the saved plans' IndexedDB cannot do that;
 *  - nothing checks that the page applied a write, or applied it right: a [load] simply replaces the list with what the
 *    page stores (the page's storage is the truth after every load);
 *  - a change of the page's storage the page does not [load] (the phone's sync, another tab) is not seen here;
 *  - the engine keeps nothing across a reload of the page: the page loads again at its start;
 *  - [loadSegments] gives no line (the page keeps the lines with its plan stats): RoutesState.line falls back to the points.
 */
internal class WebRoutesStore : RoutesStore {
    /** A row: the page's (kind, id), the page's own object for it (kept whole), and the Tour the slice reads. */
    class Row(var kind: String, var id: String, val obj: LinkedHashMap<String, Any?>, var tour: Tour)

    private val rows = LinkedHashMap<Long, Row>()
    private val handles = HashMap<String, Long>()
    private val names = HashMap<Long, Pair<String, String>>()
    private var next = 1L
    private val writes = ArrayList<String>()

    val pending: Int get() = writes.size

    /** The Long the slice knows (kind, id) by; a new one for a pair not seen yet (ids of rows not loaded are fine: no row). */
    fun handle(kind: String, id: String): Long = handles.getOrPut("$kind:$id") { next++.also { names[it] = kind to id } }

    /** The page's name of [handle]: (kind, id); null = none (-1, or never given out). */
    fun nameOf(handle: Long): Pair<String, String>? = names[handle]

    fun row(handle: Long): Row? = rows[handle]

    /** The page's object for a row as JSON – the page's fields untouched, the mapped ones as the slice left them. */
    private fun rowJson(r: Row) = WebJson.write(r.obj)

    private fun queue(op: String, r: Row?, kind: String, id: String, more: String = "") {
        writes += "{\"op\":" + WebJson.str(op) + ",\"kind\":" + WebJson.str(kind) + ",\"id\":" + WebJson.str(id) + more +
            (r?.let { ",\"row\":" + rowJson(it) } ?: "") + "}"
    }

    /** RoutesState.onRecent: the Zuletzt place is [handle]'s row now. */
    fun queueRecent(handle: Long) {
        val n = nameOf(handle)
        writes += "{\"op\":\"recent\",\"place\":" + (n?.let { place(it.first, it.second) } ?: "null") + "}"
    }

    /** The writes since the last call, oldest first, as a JSON array; the queue is empty afterwards. */
    fun takeWrites(): String = writes.joinToString(",", "[", "]").also { writes.clear() }

    private fun points(code: String?, what: String): List<GpxWaypoint> {
        if (code.isNullOrBlank()) return emptyList()
        val plan = try {
            PlanCode.parse(code) ?: null.also { Problems.warn("routes", "The plan code of $what is no plan – the row has no points") }
        } catch (e: Exception) {
            Problems.warn("routes", "The plan code of $what could not be read – the row has no points", e); null
        }
        return plan?.points?.map { GpxWaypoint(it.lat, it.lon, null) } ?: emptyList()
    }

    private fun long(v: Any?): Long? = (v as? Double)?.takeIf { !it.isNaN() && !it.isInfinite() }?.toLong()

    private fun tourOf(handle: Long, kind: String, obj: Map<String, Any?>, draft: Boolean, what: String): Tour {
        val track = obj["kind"] == "track"
        return Tour(
            id = handle, kind = if (track) TourKind.IMPORTED else TourKind.PLANNED, name = obj["title"] as? String ?: "",
            createdMs = long(obj[if (kind == DRAFT) "at" else if (obj.containsKey("updatedAt")) "updatedAt" else "at"]) ?: 0L,
            activity = null, sac = 2, distanceM = 0.0, ascentM = 0.0, descentM = 0.0, durationS = 0.0, state = "done",
            waypoints = if (track) emptyList() else points(obj[if (kind == DRAFT) "code" else "plan"] as? String, what),
            deletedMs = long(obj["deletedAt"]), draft = draft,
        )
    }

    /**
     * Replaces the list with what the page stores. Returns (rows read, rows left out). Call it only while no write waits
     * ([pending] 0) – [WebState.storeLoad] refuses otherwise.
     */
    fun load(saved: List<Any?>, drafts: List<Any?>, binned: List<Any?>): Pair<Int, Int> {
        rows.clear()
        var skipped = 0
        fun take(list: List<Any?>, kind: String, draft: Boolean, from: String) {
            for (o in list) {
                @Suppress("UNCHECKED_CAST") val m = o as? Map<String, Any?>
                val id = m?.get("id") as? String
                if (m == null || id.isNullOrEmpty()) {
                    skipped++
                    Problems.warn("routes", "A stored plan without an id ($from) – left out of My routes' state")
                    continue
                }
                val h = handle(kind, id)
                if (rows.containsKey(h)) {
                    skipped++
                    Problems.warn("routes", "The stored plan $kind $id came twice ($from) – the first one counts")
                    continue
                }
                rows[h] = Row(kind, id, LinkedHashMap(m), tourOf(h, kind, m, draft, "$kind $id"))
            }
        }
        take(saved, SAVED, false, "saved")
        take(drafts, DRAFT, true, "drafts")
        take(binned, SAVED, false, "binned")
        return rows.size to skipped
    }

    /**
     * The page moved a row to another store / id (a draft it saved): the handle stays, so place and mark follow.
     * Returns (the row's handle, the handle the new name had before – the row was loaded under it meanwhile – or null);
     * null = no such row name.
     */
    fun moved(fromKind: String, fromId: String, toKind: String, toId: String): Pair<Long, Long?>? {
        val h = handles.remove("$fromKind:$fromId") ?: return null
        val other = handles["$toKind:$toId"]?.takeIf { it != h }
        if (other != null) {
            // one row, not two: the one loaded under the new name is the page's truth, it lives on under this handle
            val loaded = rows.remove(other)
            names.remove(other)
            if (loaded != null) rows[h] = Row(toKind, toId, loaded.obj, loaded.tour.copy(id = h))
        }
        handles["$toKind:$toId"] = h
        names[h] = toKind to toId
        rows[h]?.let { it.kind = toKind; it.id = toId; it.obj["id"] = toId }
        return h to other
    }

    /** A new draft row (RoutesState.keepDraft's insert): the page's `RecentPlan` object, made here. */
    fun insertDraft(id: String, code: String, title: String, points: String, now: Long): Long {
        val h = handle(DRAFT, id)
        val obj = linkedMapOf<String, Any?>("id" to id, "code" to code, "title" to title, "points" to points, "at" to now.toDouble())
        val r = Row(DRAFT, id, obj, tourOf(h, DRAFT, obj, true, "draft $id"))
        rows[h] = r
        queue("insert", r, DRAFT, id)
        return h
    }

    // ---- RoutesStore ----
    override fun listTours(): List<Tour> = rows.values.map { it.tour }.filter { it.deletedMs == null }.sortedByDescending { it.createdMs }
    override fun listDeleted(): List<Tour> = rows.values.map { it.tour }.filter { it.deletedMs != null }.sortedByDescending { it.deletedMs }

    override fun setDraft(id: Long, draft: Boolean): Boolean {
        val r = rows[id] ?: return false
        r.tour = r.tour.copy(draft = draft)
        queue("setDraft", r, r.kind, r.id, ",\"draft\":$draft")
        return true
    }

    override fun setDeleted(id: Long, deletedMs: Long?): Boolean {
        val r = rows[id] ?: return false
        r.tour = r.tour.copy(deletedMs = deletedMs)
        if (deletedMs == null) r.obj.remove("deletedAt") else r.obj["deletedAt"] = deletedMs.toDouble()
        queue("setDeleted", r, r.kind, r.id, ",\"deletedAt\":" + (deletedMs?.toString() ?: "null"))
        return true
    }

    /** No lines here (see the class comment): RoutesState.line shows the plan's points instead. */
    override fun loadSegments(id: Long): List<List<TrackPoint>> = emptyList()

    override fun deleteForGood(id: Long) {
        val r = rows.remove(id) ?: return
        queue("delete", null, r.kind, r.id)
    }

    override fun rename(id: Long, name: String) {
        val r = rows[id] ?: return
        r.tour = r.tour.copy(name = name)
        r.obj["title"] = name
        queue("rename", r, r.kind, r.id, ",\"title\":" + WebJson.str(name))
    }

    companion object {
        const val DRAFT = "draft"
        const val SAVED = "saved"
        fun place(kind: String, id: String) = "{\"kind\":" + WebJson.str(kind) + ",\"id\":" + WebJson.str(id) + "}"
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// The slices on the page

/**
 * The page's [RoutesState] and [SaveDialog] (one each) over [WebRoutesStore], and what the exports below answer.
 * Time never comes from the system here: every call that can write takes the page's `now` (ms), and the slice's clock
 * is that value – the engine's tests are deterministic, and the page's time is its own.
 */
internal object WebState {
    private var store = WebRoutesStore()
    private var nowMs = 0L
    private var closed = 0
    private var routes = newRoutes(-1L)
    private var dialog = SaveDialog()
    /** The dialogs opened ([SaveDialog.request]) by their token: [SaveDialog.proposed] wants the very SaveAsk it answers. */
    private val asked = LinkedHashMap<Int, SaveAsk>()
    private var token = 0

    private fun newRoutes(recent: Long) = RoutesState(store, clock = { nowMs }, recent = recent).also { r ->
        r.onClosed = { closed++ }
        r.onRecent = { store.queueRecent(it) }
    }

    /** A new engine state (the tests; the page gets one per engine instance anyway). */
    fun reset() {
        store = WebRoutesStore(); nowMs = 0L; closed = 0; routes = newRoutes(-1L); dialog = SaveDialog(); asked.clear(); token = 0
    }

    private fun err(text: String) = "{\"error\":" + WebJson.str(text) + "}"

    /** No export throws: an unexpected failure is reported and answered as `{"error":…}`. */
    private inline fun guard(what: String, f: () -> String): String = try { f() } catch (e: Exception) {
        Problems.error("routes", "page state: $what failed", e)
        err("$what: ${e.message ?: e.toString()}")
    }

    /** A wrong argument from the page (an unknown kind, tab …): reported, answered, nothing changed. */
    private fun refuse(what: String, why: String): String {
        Problems.warn("routes", "page state: $what refused – $why")
        return err("$what: $why")
    }

    private fun kindOk(kind: String) = kind == WebRoutesStore.DRAFT || kind == WebRoutesStore.SAVED

    /** The tours the list shows are read again after every change (the app's view model does the same off the main thread). */
    private fun reread() = routes.show(routes.load())

    /**
     * The slice with another Zuletzt place ([RoutesState.recentId] can only be given at construction or by takePlace,
     * which also sends drafts to the bin): a new instance with everything else as it was. No hook fires.
     */
    private fun withRecent(recent: Long) {
        if (recent == routes.recentId) return
        val old = routes
        routes = newRoutes(recent)
        routes.tab = old.tab
        if (old.open) routes.open = true // opening sets the sort to its default: the sort is copied after it
        routes.sort = old.sort
        routes.justSavedId = old.justSavedId
    }

    // ---- the store's exports ----

    fun storeLoad(json: String): String = guard("routesStoreLoad") {
        if (store.pending > 0) return@guard "{\"error\":" + WebJson.str("routesStoreLoad: ${store.pending} write(s) wait – take and apply them first") +
            ",\"pending\":" + store.pending + "}"
        val parsed = try { WebJson.parse(json) } catch (e: Exception) {
            Problems.warn("routes", "page state: the stored plans handed over are no JSON – the list stays as it was", e)
            return@guard err("routesStoreLoad: no JSON – the list stays as it was")
        }
        val root = parsed as? Map<*, *> ?: return@guard refuse("routesStoreLoad", "not a JSON object – the list stays as it was")
        fun list(key: String): List<Any?> = root[key] as? List<Any?> ?: emptyList()
        val (n, skipped) = store.load(list("saved"), list("drafts"), list("binned"))
        if (root.containsKey("recent")) {
            val p = root["recent"] as? Map<*, *>
            val kind = p?.get("kind") as? String; val id = p?.get("id") as? String
            withRecent(if (kind != null && id != null && kindOk(kind)) store.handle(kind, id) else -1L)
        }
        reread()
        "{\"rows\":$n,\"skipped\":$skipped}"
    }

    fun storeTakeWrites(): String = guard("routesStoreTakeWrites") { store.takeWrites() }

    fun storeMoved(fromKind: String, fromId: String, toKind: String, toId: String): String = guard("routesStoreMoved") {
        if (!kindOk(fromKind) || !kindOk(toKind)) return@guard refuse("routesStoreMoved", "kind is \"draft\" or \"saved\"")
        val (h, before) = store.moved(fromKind, fromId, toKind, toId) ?: return@guard "{\"ok\":false}"
        // the row was loaded under its new name already: place and mark that pointed at that handle point at this one now
        if (before != null) {
            if (routes.justSavedId == before) routes.justSavedId = h
            if (routes.recentId == before) withRecent(h)
        }
        if (routes.recentId == h) store.queueRecent(h) // the page stores the place under the new name
        reread()
        "{\"ok\":true}"
    }

    // ---- RoutesState's exports ----

    private fun tourJson(t: Tour): String {
        val n = store.nameOf(t.id)
        return "{\"kind\":" + WebJson.str(n?.first ?: "") + ",\"id\":" + WebJson.str(n?.second ?: "") + ",\"title\":" + WebJson.str(t.name) +
            ",\"draft\":" + t.draft + ",\"tourKind\":" + WebJson.str(t.kind.name) + ",\"at\":" + t.createdMs +
            ",\"deletedAt\":" + (t.deletedMs?.toString() ?: "null") + ",\"points\":" + t.waypoints.size + "}"
    }

    private fun placeJson(handle: Long): String =
        store.nameOf(handle)?.let { WebRoutesStore.place(it.first, it.second) } ?: "null"

    private fun rowJson(live: LivePlan?): String {
        val all = store.listTours()
        val row = LivePlanRows.of(live, all.filter { it.draft }, all.filter { !it.draft }, routes.recentId)
        if (!row.shown) return "null"
        return "{\"live\":" + row.live + ",\"place\":" + placeJson(row.tourId) + ",\"saved\":" + row.saved + "}"
    }

    /**
     * The whole observable state of My routes, the same keys after every call:
     * ```
     * {"open":false,"tab":"PLANNED"|"DONE","sort":{"key":"NEWEST"|"OLDEST"|"LENGTH"|"DURATION"|"ASCENT","biggestFirst":true},
     *  "justSaved":{"kind","id"}|null,          // "✓ Gerade gespeichert" until My routes closes
     *  "recent":{"kind","id"}|null,             // who holds the Zuletzt place (RoutesState.recentId)
     *  "row":{"live":false,"place":{"kind","id"},"saved":true|false}|null,   // the ONE Zuletzt row while the map is empty
     *                                           //   (LivePlanRows.of; with a plan on the map: routesRecentRow)
     *  "tours":[{"kind","id","title","draft","tourKind":"PLANNED"|"IMPORTED","at","deletedAt":null,"points":n}…],
     *                                           // RoutesState.tours: everything outside the bin, newest first (not sorted by "sort")
     *  "bin":[…the same shape, "deletedAt" set, newest deletion first…],
     *  "closed":0,                              // how often My routes closed (RoutesState.onClosed) – the page drops its line then
     *  "pendingWrites":0}                       // writes the page has not taken yet
     * ```
     */
    fun routesJson(): String = guard("routesStateJson") {
        buildString {
            append("{\"open\":").append(routes.open).append(",\"tab\":").append(WebJson.str(routes.tab.name))
            append(",\"sort\":{\"key\":").append(WebJson.str(routes.sort.key.name)).append(",\"biggestFirst\":").append(routes.sort.biggestFirst).append('}')
            append(",\"justSaved\":").append(placeJson(routes.justSavedId))
            append(",\"recent\":").append(placeJson(routes.recentId))
            append(",\"row\":").append(rowJson(null))
            append(",\"tours\":").append(routes.tours.joinToString(",", "[", "]") { tourJson(it) })
            append(",\"bin\":").append(store.listDeleted().joinToString(",", "[", "]") { tourJson(it) })
            append(",\"closed\":").append(closed).append(",\"pendingWrites\":").append(store.pending).append('}')
        }
    }

    private fun key(code: String): List<Pair<Double, Double>> =
        (if (code.isBlank()) null else PlanCode.parse(code))?.points?.map { it.lat to it.lon } ?: emptyList()

    /**
     * The Zuletzt row with a plan on the map ([points] > 0; the page's recentRow with `live`): [code] = the plan on the
     * map, [linkedKind] / [linkedId] = the saved route or draft it belongs to ("" = none), [unsaved] = changed since its
     * save. The same JSON as "row" of [routesJson]; "place" null = a new plan on the map.
     */
    fun recentRow(points: Int, linkedKind: String, linkedId: String, code: String, unsaved: Boolean): String = guard("routesRecentRow") {
        val linked = if (linkedId.isNotEmpty() && kindOk(linkedKind)) store.handle(linkedKind, linkedId) else -1L
        rowJson(LivePlan(null, points, 0.0, 0.0, 0.0, linked, key(code), unsaved))
    }

    fun open(open: Boolean): String = guard("routesOpen") { routes.open = open; if (open) reread(); OK }

    fun tab(tab: String): String = guard("routesTab") {
        routes.tab = RoutesTab.entries.firstOrNull { it.name == tab } ?: return@guard refuse("routesTab", "no tab \"$tab\"")
        OK
    }

    fun sortTap(key: String): String = guard("routesSortTap") {
        val k = RouteSort.Key.entries.firstOrNull { it.name == key } ?: return@guard refuse("routesSortTap", "no sort key \"$key\"")
        routes.sort = RouteSort.tap(routes.sort, k)
        OK
    }

    fun showSaved(kind: String, id: String): String = guard("routesShowSaved") {
        if (!kindOk(kind)) return@guard refuse("routesShowSaved", "kind is \"draft\" or \"saved\"")
        routes.showSaved(store.handle(kind, id)); reread(); OK
    }

    fun markSaved(kind: String, id: String): String = guard("routesMarkSaved") {
        if (!kindOk(kind)) return@guard refuse("routesMarkSaved", "kind is \"draft\" or \"saved\"")
        routes.markSaved(store.handle(kind, id)); OK
    }

    fun takePlace(kind: String, id: String, now: Double): String = guard("routesTakePlace") {
        if (!kindOk(kind)) return@guard refuse("routesTakePlace", "kind is \"draft\" or \"saved\"")
        nowMs = now.toLong()
        val gone = routes.takePlace(store.handle(kind, id))
        reread()
        "{\"gone\":$gone}"
    }

    /**
     * A plan leaves the map (RoutesState.keepDraft): [code] = its plan code, [unsaved] = it differs from its save,
     * [savedKind] / [savedId] = the saved route or the draft it was loaded from ("" = none); a new draft – only when the
     * slice decides for one – gets the id [newId] (the page makes it), [title] and [points] (the page's `RecentPlan.points`
     * text, stored as given). `{"id":"<newId>"}` = a new draft was written, `{"id":null}` = none (one point, the saved
     * route / a draft / a twin took the place, or nothing to keep).
     */
    fun keepDraft(newId: String, code: String, title: String, points: String, unsaved: Boolean, savedKind: String, savedId: String, now: Double): String =
        guard("routesKeepDraft") {
            if (newId.isEmpty()) return@guard refuse("routesKeepDraft", "no id for a new draft")
            nowMs = now.toLong()
            val saved = if (savedId.isNotEmpty() && kindOk(savedKind)) store.handle(savedKind, savedId) else -1L
            val k = key(code)
            // robust first: a plan whose code cannot be read would look like "one point, nothing to keep" – say it, the page keeps it its own way
            if (k.isEmpty()) return@guard refuse("routesKeepDraft", "the plan code has no readable points – nothing decided")
            val made = routes.keepDraft(k, unsaved, saved) { store.insertDraft(newId, code, title, points, nowMs) }
            reread()
            "{\"id\":" + (if (made != null) WebJson.str(newId) else "null") + "}"
        }

    /** A plan was saved as the saved route [savedId] with the plan code [code]; [fromDraftId] = the draft it came from ("" = none). */
    fun afterSaved(savedId: String, fromDraftId: String, code: String, now: Double): String = guard("routesAfterSaved") {
        nowMs = now.toLong()
        val from = if (fromDraftId.isEmpty()) -1L else store.handle(WebRoutesStore.DRAFT, fromDraftId)
        val gone = routes.afterSaved(store.handle(WebRoutesStore.SAVED, savedId), from, key(code))
        reread()
        "{\"gone\":$gone}"
    }

    /** Zuletzt → Speichern on the draft [id]: `{"ok":false}` = it is gone. The page then stores it as a saved plan and says [storeMoved]. */
    fun saveDraft(id: String, now: Double): String = guard("routesSaveDraft") {
        nowMs = now.toLong()
        val h = store.handle(WebRoutesStore.DRAFT, id)
        val ok = routes.saveDraft(h)
        if (ok) routes.markSaved(h)
        reread()
        "{\"ok\":$ok}"
    }

    fun toBin(kind: String, id: String, now: Double): String = guard("routesToBin") {
        if (!kindOk(kind)) return@guard refuse("routesToBin", "kind is \"draft\" or \"saved\"")
        nowMs = now.toLong()
        val ok = routes.toBin(store.handle(kind, id))
        reread()
        "{\"ok\":$ok}"
    }

    fun restore(kind: String, id: String, now: Double): String = guard("routesRestore") {
        if (!kindOk(kind)) return@guard refuse("routesRestore", "kind is \"draft\" or \"saved\"")
        nowMs = now.toLong()
        val ok = routes.restore(store.handle(kind, id))
        reread()
        "{\"ok\":$ok}"
    }

    fun renameDraft(id: String, dated: String, proposed: String): String = guard("routesRenameDraft") {
        val ok = routes.renameDraft(store.handle(WebRoutesStore.DRAFT, id), dated, proposed.ifEmpty { null })
        reread()
        "{\"ok\":$ok}"
    }

    fun trimDrafts(now: Double): String = guard("routesTrimDrafts") {
        nowMs = now.toLong()
        val gone = routes.trimDrafts()
        reread()
        "{\"gone\":$gone}"
    }

    // ---- SaveDialog's exports ----

    private fun names(taken: String) = taken.split('\n').filter { it.isNotEmpty() }

    private fun savedHandle(id: String) = if (id.isEmpty()) -1L else store.handle(WebRoutesStore.SAVED, id)

    private fun idJson(handle: Long) = store.nameOf(handle)?.let { WebJson.str(it.second) } ?: "null"

    /**
     * Save's name dialog, the same keys after every call:
     * `{"open":false,"ask":null}` or
     * `{"open":true,"ask":{"kind":"NEW"|"OVERWRITE"|"COPY","name":"…","routeId":"<saved id>"|null,"token":n}}`
     * ([SaveAsk]; "name" = what the field is prefilled with, "routeId" = the route overwritten / copied, "token" = this
     * dialog for saveDialogProposed).
     */
    fun dialogJson(): String = guard("saveDialogJson") {
        val a = dialog.ask ?: return@guard "{\"open\":false,\"ask\":null}"
        val t = asked.entries.firstOrNull { it.value === a }?.key ?: -1
        "{\"open\":true,\"ask\":{\"kind\":" + WebJson.str(a.kind.name) + ",\"name\":" + WebJson.str(a.name) +
            ",\"routeId\":" + idJson(a.routeId) + ",\"token\":" + t + "}}"
    }

    private fun remember(a: SaveAsk): Int {
        asked.clear() // one dialog at a time: an older token answers nothing any more
        asked[++token] = a
        return token
    }

    /**
     * Save opens the dialog (SaveDialog.request): [overwrite] + [savedName] / [savedId] = the plan belongs to that saved
     * route (SaveMode.OVERWRITE), else a new route under the date name [dated] made free among [taken] (names, one per
     * line) until the proposal comes. `{"token":n}`.
     */
    fun dialogRequest(overwrite: Boolean, savedName: String, savedId: String, dated: String, taken: String): String = guard("saveDialogRequest") {
        val a = dialog.request(if (overwrite) SaveMode.OVERWRITE else SaveMode.NEW_ROUTE, savedName.ifEmpty { null }, savedHandle(savedId),
            dated, names(taken), then = null)
        "{\"token\":" + remember(a) + "}"
    }

    /** ⋮ → Save as copy of the saved route [savedId] named [name] (SaveDialog.requestCopy). `{"token":n}`. */
    fun dialogRequestCopy(savedId: String, name: String, taken: String): String = guard("saveDialogRequestCopy") {
        if (savedId.isEmpty()) return@guard refuse("saveDialogRequestCopy", "no route to copy")
        val h = savedHandle(savedId)
        val t = store.row(h)?.tour?.copy(name = name)
            ?: Tour(h, TourKind.PLANNED, name, 0L, null, 2, 0.0, 0.0, 0.0, 0.0, "done", emptyList())
        dialog.requestCopy(t, names(taken))
        "{\"token\":" + remember(dialog.ask!!) + "}"
    }

    /**
     * The place proposal [proposal] arrived for the dialog [token] (SaveSlice does the same: the name made free among
     * [taken], then SaveDialog.proposed): `{"ok":true}` = the field's name changed; false = that dialog is gone, another
     * one is open, or there is no proposal.
     */
    fun dialogProposed(token: Int, proposal: String, dated: String, taken: String): String = guard("saveDialogProposed") {
        val a = asked[token] ?: return@guard "{\"ok\":false}"
        if (proposal.isBlank() || dialog.ask !== a) return@guard "{\"ok\":false}"
        dialog.proposed(a, RouteRules.newRouteName(proposal, dated, names(taken)))
        asked[token] = dialog.ask!! // the dialog's SaveAsk is a new object with the new name: the token follows it
        "{\"ok\":true}"
    }

    fun dialogCancel(): String = guard("saveDialogCancel") { dialog.cancel(); asked.clear(); OK }

    /**
     * The dialog's Save with what the field holds (SaveDialog.confirm → SaveFlow.Write); [savedId] = the saved route the
     * plan is linked to NOW ("" = none). `{"write":{"kind":"NEW"|"OVERWRITE"|"COPY","name":"…","routeId":"…"|null}}`
     * – the page does that write –, `{"write":null}` = the dialog was not open.
     */
    fun dialogConfirm(input: String, savedId: String): String = guard("saveDialogConfirm") {
        val done = dialog.confirm(input, savedHandle(savedId)) ?: return@guard "{\"write\":null}"
        asked.clear()
        val (kind, route) = when (val w = done.write) {
            is SaveFlow.Write.Copy -> "COPY" to w.routeId
            is SaveFlow.Write.Overwrite -> "OVERWRITE" to w.routeId
            is SaveFlow.Write.New -> "NEW" to -1L
        }
        "{\"write\":{\"kind\":" + WebJson.str(kind) + ",\"name\":" + WebJson.str(done.write.name) + ",\"routeId\":" + idJson(route) + "}}"
    }

    private const val OK = "{\"ok\":true}"
}

// ---------------------------------------------------------------------------------------------------------------------
// The exports (see the file's comment for the page function each one replaces). Every one answers JSON; a failure is
// `{"error":"…"}`, never an exception.

/** The page's stored plans → the slice's list: `{"saved":[…],"drafts":[…],"binned":[…],"recent":{"kind","id"}|null}`. `{"rows":n,"skipped":k}`. */
@JSExport fun routesStoreLoad(json: String): String = WebState.storeLoad(json)
/** The writes the slice made since the last call, oldest first (a JSON array, see [WebRoutesStore]); the queue is empty afterwards. */
@JSExport fun routesStoreTakeWrites(): String = WebState.storeTakeWrites()
/** The page stored a row under another kind / id (a draft saved as a saved plan): place and mark follow. `{"ok":true|false}`. */
@JSExport fun routesStoreMoved(fromKind: String, fromId: String, toKind: String, toId: String): String = WebState.storeMoved(fromKind, fromId, toKind, toId)

@JSExport fun routesStateJson(): String = WebState.routesJson()
@JSExport fun routesRecentRow(points: Int, linkedKind: String, linkedId: String, code: String, unsaved: Boolean): String =
    WebState.recentRow(points, linkedKind, linkedId, code, unsaved)
@JSExport fun routesOpen(open: Boolean): String = WebState.open(open)
@JSExport fun routesTab(tab: String): String = WebState.tab(tab)
@JSExport fun routesSortTap(key: String): String = WebState.sortTap(key)
@JSExport fun routesShowSaved(kind: String, id: String): String = WebState.showSaved(kind, id)
@JSExport fun routesMarkSaved(kind: String, id: String): String = WebState.markSaved(kind, id)
@JSExport fun routesTakePlace(kind: String, id: String, now: Double): String = WebState.takePlace(kind, id, now)
@JSExport fun routesKeepDraft(newId: String, code: String, title: String, points: String, unsaved: Boolean, savedKind: String, savedId: String, now: Double): String =
    WebState.keepDraft(newId, code, title, points, unsaved, savedKind, savedId, now)
@JSExport fun routesAfterSaved(savedId: String, fromDraftId: String, code: String, now: Double): String = WebState.afterSaved(savedId, fromDraftId, code, now)
@JSExport fun routesSaveDraft(id: String, now: Double): String = WebState.saveDraft(id, now)
@JSExport fun routesToBin(kind: String, id: String, now: Double): String = WebState.toBin(kind, id, now)
@JSExport fun routesRestore(kind: String, id: String, now: Double): String = WebState.restore(kind, id, now)
@JSExport fun routesRenameDraft(id: String, dated: String, proposed: String): String = WebState.renameDraft(id, dated, proposed)
@JSExport fun routesTrimDrafts(now: Double): String = WebState.trimDrafts(now)

@JSExport fun saveDialogJson(): String = WebState.dialogJson()
@JSExport fun saveDialogRequest(overwrite: Boolean, savedName: String, savedId: String, dated: String, taken: String): String =
    WebState.dialogRequest(overwrite, savedName, savedId, dated, taken)
@JSExport fun saveDialogRequestCopy(savedId: String, name: String, taken: String): String = WebState.dialogRequestCopy(savedId, name, taken)
@JSExport fun saveDialogProposed(token: Int, proposal: String, dated: String, taken: String): String = WebState.dialogProposed(token, proposal, dated, taken)
@JSExport fun saveDialogCancel(): String = WebState.dialogCancel()
@JSExport fun saveDialogConfirm(input: String, savedId: String): String = WebState.dialogConfirm(input, savedId)
