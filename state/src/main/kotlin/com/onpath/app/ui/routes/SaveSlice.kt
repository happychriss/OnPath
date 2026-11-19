package com.onpath.app.ui.routes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.MultiDay
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.domain.PlanAutoSave
import com.onpath.app.domain.RouteRules
import com.onpath.app.domain.SaveAsk
import com.onpath.app.domain.SaveMode

import com.onpath.app.domain.plan.LivePlanRows
import com.onpath.app.domain.routes.SaveFlow
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.Say
import com.onpath.app.ui.plan.PlanDocument
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What [SaveSlice] asks of the rest of the app (all on the main thread unless said): what the plan is routed as, the names
 * and numbers that need the search index / the multi-day state / the time estimates, and the side effects of a save
 * (backup file, browser sync, the list read again, the plan's saved link). Implemented by the view model; tests pass a fake.
 */
interface SaveHost : Say {
    /** The plan's route line; null = none yet (calculating / failed). */
    val route: PlannedRoute?
    val activity: Activity
    /** The SAC level of the plan. */
    val sac: Int
    /** The plan's points as stored stops, with their place names (backup / restore keeps them). */
    fun stops(points: List<GeoPoint>): List<GpxWaypoint>
    /** The difficulty level stored with a saved route (null = not known). Also called on the write's thread. */
    fun difficulty(route: PlannedRoute?, activity: Activity): String?
    /** What Save stores as the days of [points]; null for a normal plan or a followed day. Also called on the write's thread. */
    fun days(points: List<GeoPoint>): MultiDay?
    /** A followed day: its whole multi-day tour goes under Zuletzt instead (true = taken care of). */
    fun keepWhole(): Boolean
    /** A followed day: Save writes the whole multi-day tour (true = taken care of). */
    fun overwriteWhole(name: String, then: (() -> Unit)?): Boolean
    /** The place name proposed for a plan ("Algund → Meran", a plan link's title); null = none. */
    suspend fun proposeName(points: List<GeoPoint>, route: PlannedRoute?): String?
    /** "Route 26 Oct, 10:12" in the app's language – the name when nothing else fits. */
    fun datedName(): String
    /** Keeps the model times of the saved route [id] for the route list (blocking; reports its own failures). */
    fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity)
    /** The stored model times of route [id] are old (its line was replaced). */
    fun forgetTimes(id: Long)
    /** A copy [to] of route [from] has the same model times ([distanceM] of the stored line). */
    fun copyTimes(from: Long, to: Long, distanceM: Double)
    /** The tour [id] changed (saved, renamed, starred): its backup file follows. */
    fun tourChanged(id: Long)
    /** Something the browser sync carries changed. */
    fun syncChanged(why: String)
    /** My routes' list is read again. */
    fun refreshTours()
    /** The plan was saved / loaded as [name] (route [id], [points]): the plan's link and a running tour's plan copy. */
    fun saved(name: String?, id: Long, points: List<GeoPoint>?)
    /** A shown track of route [id] is called [name] now. */
    fun shownRenamed(id: Long, name: String)
}

/**
 * Save (C18, cut out of `AppViewModel`, rebuild phase 2 piece 3): the name dialog ([SaveDialog]), the writes that follow
 * it – a new route, the overwrite of the saved one (sync changed it meanwhile → a copy; deleted meanwhile → a new route),
 * the copy from My routes –, Rename and the star in My routes, a plan that leaves the map unsaved ([keepAsDraft]) and the
 * save at "Los geht's" ([atTourStart]). The plan, My routes and Zuletzt are the other slices, the database is [store], the
 * rest of the app is [host]; the work runs on [scope] (the view model's) with the blocking calls on [io].
 */
class SaveSlice(
    private val plan: PlanDocument,
    private val routes: RoutesState,
    private val recent: RecentPlans,
    private val store: SaveStore,
    private val host: SaveHost,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** How long the dialog waits for the place proposal of a new route's name (it shows the date name meanwhile). */
    private val nameWaitMs: Long = RecentPlans.NAME_WAIT_MS,
) {
    private val dialog = SaveDialog()

    /** Save's name dialog while open (owner 2026-10-31: Save always asks for the name; [request], [requestCopy]). */
    val ask: SaveAsk? get() = dialog.ask

    /** A save runs (the DB write): a second tap on Save does not make a second route. */
    private var saving = false

    /** A save failed: shown in a dialog until OK (no message that disappears by itself). */
    var problem by mutableStateOf<String?>(null)

    /**
     * Save – the sheet's green Save, ☰ "Save route", sheet ⋯ → Save route – always opens the name dialog (owner
     * 2026-10-31, replaces "save at once"): a plan that belongs to a saved route that still exists ([RouteRules.saveMode])
     * shows that route's name and is overwritten on Save – renamed when the name changed, still ONE route; else the
     * proposed name ([RouteRules.newRouteName]: plan link title / "Vellau → Leiteralm", else "Route 31 Oct, 10:12") and
     * a new route. The sheet then shows "Saved as …" (no message).
     * Opens Save's dialog; [then] runs after a successful save ("Save first" before loading another route).
     */
    fun request(then: (() -> Unit)? = null) {
        val pts = plan.points.toList()
        if (pts.size < 2 || saving) return
        val dated = host.datedName()
        val taken = plannedNames()
        val ask = dialog.request(currentSaveMode(), plan.savedName, plan.savedId, dated, taken, then)
        if (ask.kind == SaveAsk.Kind.OVERWRITE) return
        // the proposal (plan link title / "Vellau → Leiteralm") replaces the date name in the field until the user types
        val r = host.route
        scope.launchSafe("app", "askSave") {
            val proposed = withTimeoutOrNull(nameWaitMs) { host.proposeName(pts, r) } ?: return@launchSafe
            dialog.proposed(ask, RouteRules.newRouteName(proposed, dated, taken))
        }
    }

    /**
     * My routes → ⋮ → Save as copy (planned routes; owner 2026-10-31, was the plan sheet's ⋯): the same dialog with
     * "<name> (2)" ([RouteRules.copyName]); Save copies the stored route ([copyRoute]) – the plan stays as it is.
     */
    fun requestCopy(t: Tour) {
        if (t.kind != TourKind.PLANNED || saving) return
        dialog.requestCopy(t, plannedNames())
    }

    /** Save's dialog → Cancel / Back / a tap outside: nothing is saved, "Save first" does not load. */
    fun cancel() { dialog.cancel() }

    /**
     * Save's dialog → Save with [input]: a blank name keeps the proposal / old name ([RouteRules.cleanName]). A new
     * route or a copy is inserted; the saved route is overwritten in place and renamed if the name changed.
     */
    fun confirm(input: String) {
        val done = dialog.confirm(input, plan.savedId) ?: return
        val then = done.then
        when (val w = done.write) {
            is SaveFlow.Write.Copy -> copyRoute(w.routeId, w.name)
            is SaveFlow.Write.Overwrite -> overwriteSaved(w.name, then)
            is SaveFlow.Write.New -> saveRoute(w.name, then)
        }
    }

    /** Save as copy from My routes: a new planned route [name] with the stored line, points and pins of route [id]. */
    private fun copyRoute(id: Long, name: String) {
        if (saving) return
        saving = true
        scope.launchSafe("app", "copyRoute") {
            try {
                val newId = withContext(io) {
                    runCatching {
                        val t = store.getTour(id) ?: return@runCatching null
                        store.insertPlanned(name, PlannedRow(t.activity, t.sac, t.distanceM, t.ascentM, t.descentM, t.durationS,
                            store.loadSegments(id), t.waypoints, pins = t.pinned, difficulty = t.difficulty))
                            .also { n -> host.copyTimes(id, n, t.distanceM) }
                    }.onFailure { Problems.error("db", "Copying a planned route failed", it) }.getOrNull()
                }
                if (newId == null) { problem = Txt.s(Res.string.msg_route_save_failed); return@launchSafe }
                host.tourChanged(newId)
                host.syncChanged("route copied")
                host.refreshTours()
            } finally { saving = false }
        }
    }

    // a plan from "Zuletzt" is no saved route: Save makes a new one (and the draft goes – [insertPlanned])
    private fun currentSaveMode() =
        RouteRules.saveMode(plan.savedId, plan.savedName, routes.tours.filter { it.kind == TourKind.PLANNED && !it.draft }.map { it.id })

    private fun plannedNames() = routes.tours.filter { it.kind == TourKind.PLANNED && !it.draft }.map { it.name }

    /** The plan on the map was loaded from "Zuletzt" (a draft) and still belongs to it. */
    private val linkedDraft: Tour? get() = if (plan.linked && plan.linkId >= 0) routes.tours.firstOrNull { it.id == plan.linkId && it.draft } else null

    // ---------------- "Zuletzt" (owner 2026-11-06: no "Nicht gespeichert", like Komoot + recent plans) ----------------

    /**
     * A plan worth keeping that leaves the map unsaved ([PlanDocument.unsaved]: never saved, or a saved plan changed – its original
     * stays as it is) goes under My routes → Zuletzt, no question: written at once, before it leaves the map
     * ([com.onpath.app.ui.routes.RecentPlans.keep]).
     */
    fun keepAsDraft(why: String) {
        val pts = plan.points.toList()
        if (pts.size < 2 || host.keepWhole()) return // a followed day: the whole multi-day tour goes under Zuletzt
        val r = host.route; val act = host.activity
        recent.keep(RecentPlans.Leaving(pts, plan.unsaved, plan.savedId, r, act, host.sac, host.stops(pts), plan.pinnedIndices(),
            host.datedName(), host.days(pts)) { host.difficulty(r, act) }, why)
    }

    /**
     * Saves the plan as a new planned route named [name]. The sheet then shows "Saved as …" (no message). Without a
     * route line yet (still calculating / failed) the points are stored as the line, so nothing is lost; opening it
     * re-routes. [then] runs on success only.
     */
    fun saveRoute(name: String, then: (() -> Unit)? = null) {
        val pts = plan.points.toList()
        if (pts.size < 2 || saving) return
        saving = true
        val r = host.route
        scope.launchSafe("app", "saveRoute") { try { insertPlanned(name, pts, r, then) } finally { saving = false } }
    }

    private suspend fun insertPlanned(name: String, pts: List<GeoPoint>, r: PlannedRoute?, then: (() -> Unit)?) {
        val title = name.trim().ifBlank { host.datedName() }
        // saved from a plan under "Zuletzt": the saved route takes its place there (no second entry)
        val fromDraft = linkedDraft
        val act = host.activity; val level = host.sac
        val stops = host.stops(pts); val pins = plan.pinnedIndices()
        val id = withContext(io) {
            runCatching {
                store.insertPlanned(title, PlannedRow(
                    act.name, level,
                    r?.distanceM ?: 0.0, r?.ascentM ?: 0.0, r?.descentM ?: 0.0, r?.durationS ?: 0.0,
                    listOf((r?.points ?: pts).map { TrackPoint(it.lat, it.lon, it.ele) }),
                    stops, pins, difficulty = host.difficulty(r, act), days = host.days(pts),
                )).also { host.storeTimes(it, r, act) }
            }.onFailure { Problems.error("db", "Saving a planned route failed", it) }.getOrNull()
        }
        if (id == null) { problem = Txt.s(Res.string.msg_route_save_failed); return }
        // the saved route takes the place of its draft and of any draft with the same points (never a second entry)
        withContext(io) {
            runCatching { routes.afterSaved(id, fromDraft?.id ?: -1L, pts.map { it.lat to it.lon }) }
                .onFailure { Problems.warn("db", "The plan under Zuletzt stays after it was saved", it) }
        }
        host.tourChanged(id)
        host.syncChanged("route saved")
        host.saved(title, id, pts)
        host.refreshTours()
        then?.invoke()
    }

    /**
     * Save of a plan that belongs to a saved route: overwrite it in place under [name] (renamed when it differs – still
     * one route). A new entry with that name if it is gone meanwhile.
     */
    private fun overwriteSaved(name: String, then: (() -> Unit)?) {
        val pts = plan.points.toList()
        val old = plan.savedName
        val id = plan.savedId
        if (pts.size < 2 || host.overwriteWhole(name, then) || saving) return // a followed day: the whole multi-day tour
        if (old == null || id < 0) { saveRoute(name, then); return }
        saving = true
        val r = host.route
        val act = host.activity; val level = host.sac
        val stops = host.stops(pts); val pins = plan.pinnedIndices()
        val opened = plan.savedPoints?.let { p -> com.onpath.app.sync.SaveRule.pointsKey(p.map { it.lat to it.lon }) }
        scope.launchSafe("app", "overwriteSaved") {
            try {
                // changed by sync since it was opened here (review sync H3): save as a copy, the other edit stays
                val stored = withContext(io) {
                    runCatching { store.getTour(id)?.waypoints?.map { it.lat to it.lon } }
                        .onFailure { Problems.warn("db", "Saved route not read before overwriting; overwritten as before", it) }.getOrNull()
                }
                if (com.onpath.app.sync.SaveRule.keepsOtherEdit(opened, stored?.let(com.onpath.app.sync.SaveRule::pointsKey))) {
                    val copy = RouteRules.copyName(name, plannedNames())
                    Problems.trace("plan", "Save over \"$old\": changed elsewhere since opened (browser sync) – saved as \"$copy\"")
                    host.say(Txt.s(Res.string.msg_route_changed_elsewhere, copy), NoticeKind.INFO, NoticePlace.MAP)
                    insertPlanned(copy, pts, r, then)
                    return@launchSafe
                }
                val ok = withContext(io) {
                    runCatching {
                        store.replacePlanned(
                            id, PlannedRow(
                                act.name, level,
                                r?.distanceM ?: 0.0, r?.ascentM ?: 0.0, r?.descentM ?: 0.0, r?.durationS ?: 0.0,
                                listOf((r?.points ?: pts).map { TrackPoint(it.lat, it.lon, it.ele) }),
                                stops, pins, difficulty = host.difficulty(r, act), days = host.days(pts),
                            ),
                        ).also {
                            if (it) { host.forgetTimes(id); host.storeTimes(id, r, act) }
                            if (it && name != old) store.rename(id, name)
                            // a draft with the route's new points goes (RoutesState: never a draft with a saved route's points)
                            if (it) routes.afterSaved(id, -1L, pts.map { p -> p.lat to p.lon })
                        }
                    }
                }
                val replaced = ok.getOrElse {
                    Problems.error("db", "Overwriting a saved route failed", it)
                    problem = Txt.s(Res.string.msg_route_save_failed)
                    return@launchSafe
                }
                if (replaced) {
                    host.tourChanged(id); host.syncChanged("route overwritten")
                    host.saved(name, id, pts)
                    host.shownRenamed(id, name)
                    host.refreshTours(); then?.invoke()
                } else insertPlanned(name, pts, r, then) // deleted meanwhile: keep the work as a new route with that name
            } finally { saving = false }
        }
    }

    /** Manage routes → Rename. The plan's "Saved as …" and a shown track follow the new name. */
    fun rename(t: Tour, input: String) {
        val name = RouteRules.cleanName(input, t.name)
        if (name == t.name) return
        scope.launchSafe("app", "renameTour") {
            val ok = withContext(io) { runCatching { store.rename(t.id, name) }
                .onFailure { Problems.error("db", "Renaming a route failed", it) }.isSuccess }
            if (!ok) { problem = Txt.s(Res.string.msg_route_rename_failed); return@launchSafe }
            host.tourChanged(t.id)
            host.syncChanged("renamed")
            if (plan.linkId == t.id) host.saved(name, plan.linkId, plan.savedPoints)
            host.shownRenamed(t.id, name)
            host.refreshTours()
        }
    }

    /**
     * My routes → ☆ on a row (owner 2026-11-13, favourites C): the star on or off at once in the list, then stored, the
     * backup file written again (its record keeps the star) and the browser told (sync field 9). A failure puts the
     * row back and says so in My routes.
     */
    fun setFavourite(t: Tour, favourite: Boolean) {
        if (t.id == LivePlanRows.LIVE_ID || t.favourite == favourite) return
        Problems.trace("routes", "Tour ${t.id} ${if (favourite) "starred" else "unstarred"}")
        routes.show(routes.tours.map { if (it.id == t.id) it.copy(favourite = favourite) else it })
        scope.launchSafe("app", "setFavourite") {
            val ok = withContext(io) { runCatching { store.setFavourite(t.id, favourite) }
                .onFailure { Problems.error("db", "Starring a route failed", it) }.getOrDefault(false) }
            if (!ok) problem = Txt.s(Res.string.msg_route_star_failed)
            else { host.tourChanged(t.id); host.syncChanged("starred") }
            host.refreshTours()
        }
    }

    /**
     * "Los geht's" with a plan that was never saved (owner 2026-11-06: the tour and the plan are two things – like Komoot,
     * the plan is in My routes → Planned from the start, so the end of the tour never has to ask about it): saved at once
     * under the name the Save dialog would propose, no dialog. A saved plan changed since updates that route in place, like
     * ☰ Plan speichern (owner 2026-11-07: no "Name (2)" copies) – its version before goes under Zuletzt
     * ([updateSavedAtStart]). Renaming / deleting: My routes.
     */
    fun atTourStart() {
        val pts = plan.points.toList()
        val draft = linkedDraft
        val how = PlanAutoSave.atStart(pts.size, plan.unsaved, currentSaveMode() == SaveMode.OVERWRITE, draft != null)
        if (how == PlanAutoSave.AtStart.NOTHING || saving) return
        if (how == PlanAutoSave.AtStart.SAVE_OVERWRITE) { updateSavedAtStart(pts); return }
        saving = true
        val r = host.route
        val dated = host.datedName()
        val taken = plannedNames()
        scope.launchSafe("app", "saveNewPlanAtStart") {
            try {
                // a plan from Zuletzt keeps its name
                val name = draft?.name?.let { RouteRules.newRouteName(it, dated, taken) }
                    ?: RouteRules.newRouteName(withTimeoutOrNull(nameWaitMs) { host.proposeName(pts, r) }, dated, taken)
                Problems.trace("plan", "Plan saved at the tour start as \"$name\" (${how.name.lowercase()}, ${pts.size} points)")
                insertPlanned(name, pts, r, null)
            } finally { saving = false }
        }
    }

    /**
     * "Los geht's" with a loaded saved route changed since (owner 2026-11-07): the route is updated in place under its
     * name ([overwriteSaved], the same write as ☰ Plan speichern), then its version before – read first – is kept under
     * Zuletzt ([com.onpath.app.ui.routes.RoutesState.keepReplaced]), so nothing is lost and no "Name (2)" appears.
     */
    private fun updateSavedAtStart(pts: List<GeoPoint>) {
        val id = plan.savedId
        val name = plan.savedName ?: return
        scope.launchSafe("app", "updateSavedAtStart") {
            val before = withContext(io) {
                runCatching { store.getTour(id)?.let { it to store.loadSegments(id) } }
                    .onFailure { Problems.warn("db", "The saved route's version before the tour start not read – not kept under Zuletzt", it) }
                    .getOrNull()
            }
            Problems.trace("plan", "Plan saved at the tour start as \"$name\" (save_overwrite, ${pts.size} points)")
            overwriteSaved(name) { before?.let { (old, segments) -> recent.keepReplaced(old, segments) } }
        }
    }
}
