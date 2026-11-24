package com.onpath.app.ui.routes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.Tour
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.ui.Background
import com.onpath.app.ui.NameProposal
import com.onpath.app.ui.ask
import com.onpath.strings.*

/** The row of a plan kept under Zuletzt ([RecentPlansPorts.insertDraft]). */
class DraftRow(
    val name: String, val activity: String?, val sac: Int,
    val distanceM: Double, val ascentM: Double, val descentM: Double, val durationS: Double,
    val segments: List<List<TrackPoint>>, val waypoints: List<GpxWaypoint>, val pinned: Set<Int>, val difficulty: String?,
    /** A multi-day tour's days; null = a normal plan. */
    val days: com.onpath.app.data.MultiDay? = null,
)

/**
 * What [RecentPlansState] asks of the view model (the Android-bound and the plan-bound parts): the draft row's write, the
 * model times of a kept plan, and the side effects of a change in My routes (backup file, browser
 * sync, the list read again, the line at the top). The view model passes a small adapter; tests pass a fake. The place
 * name is not here: it comes from the [NameProposal] port.
 */
interface RecentPlansPorts {
    /** Writes [row] as a plan under Zuletzt (TourDb.insertTour, kind PLANNED, draft); returns its id (blocking, may throw). */
    fun insertDraft(row: DraftRow): Long
    /**
     * Writes [row] over the draft [id] – points, line, stats; its name and date stay (TourDb.replacePlanned). False when
     * the draft is gone (blocking, may throw).
     */
    fun updateDraft(id: Long, row: DraftRow): Boolean
    /** Keeps the model times of the kept plan [id] for the route list (blocking; reports its own failures). */
    fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity)
    /** The tour [id] changed (saved, moved to / out of the recycle bin): its backup file follows. */
    fun tourChanged(id: Long)
    /** The tour with [uuid] is gone for good: its backup file too. */
    fun purged(uuid: String)
    /** Something the browser sync carries changed. */
    fun syncChanged(why: String)
    /** My routes' list is read again. */
    fun refreshTours()
    /** The line at the top of My routes ([text] = what failed). */
    fun problem(text: String)
    /** The one-plan rule's clean-up ran once ([RecentPlansState.trimOnce]; a pref). */
    var trimmed: Boolean
}

/**
 * "Zuletzt" and the recycle bin as the user's actions: a plan that leaves the map is kept under Zuletzt
 * ([keep]), the version of a saved route a tour start replaced too ([keepReplaced]); Zuletzt → Speichern / Löschen
 * ([saveDraft], [deleteDraft]); the recycle bin's list, Restore and Delete now ([bin], [openBin], [restore],
 * [deleteForever]); the one clean-up after the one-plan rule ([trimOnce]). The rules (which plan holds the place, what
 * goes to the bin) stay in [RoutesState]; here are the order of the writes, the background work and the side effects.
 * Android-free and without coroutines: the database sits behind [RoutesStore] / [RecentPlansPorts], the blocking calls
 * run through [background] (the app: on the view model's scope with the database on the io dispatcher; the planner: at
 * once), the place name comes from [names]. The web-ready cut of `RecentPlans` (docs/design/web-ready-status.md §7.1):
 * `RecentPlans` is this class with the coroutines. Tests: RecentPlansTest.
 */
open class RecentPlansState(
    private val routes: RoutesState,
    private val store: RoutesStore,
    private val host: RecentPlansPorts,
    private val background: Background,
    private val names: NameProposal,
    /** How long a kept plan waits for its place name (it has its date name meanwhile). */
    private val nameWaitMs: Long = NAME_WAIT_MS,
) : Recent {

    // ---------------- "Zuletzt" (owner 2026-11-06: no "Nicht gespeichert", like Komoot + recent plans) ----------------

    /**
     * A plan worth keeping that leaves the map unsaved ([LeavingPlan.unsaved]: never saved, or a saved plan changed – its
     * original stays as it is) goes under My routes → Zuletzt, no question ([com.onpath.app.domain.Drafts]). The same
     * points twice (Undo, clear again) make no second entry; exactly one plan stays under Zuletzt – this one, the one
     * worked on last, the draft before it goes to the recycle bin; a plan saved as it is takes the place as that saved
     * route (owner 2026-11-12, [RoutesState.keepDraft]). A plan opened from Zuletzt and changed updates its entry –
     * no new draft, nothing to the bin (owner 2026-11-20); returns that entry's id then (the caller links the plan to
     * what the entry holds now), else null.
     */
    override fun keep(plan: LeavingPlan, why: String): Long? {
        val pts = plan.points
        if (pts.size < 2) return null
        val r = plan.route
        val key = pts.map { it.lat to it.lon }
        // written NOW, under the date name, before the plan leaves the map (tester review 2026-11-07: it waited up to 5 s
        // for a place name in the background, and a kill in that window lost the plan). One row on the calling thread –
        // the plan points in the prefs go with the clear right after this, so the database must hold them first.
        var updated = false
        fun row() = DraftRow(plan.dated, plan.activity.name, plan.sac,
            r?.distanceM ?: 0.0, r?.ascentM ?: 0.0, r?.descentM ?: 0.0, r?.durationS ?: 0.0,
            listOf((r?.points ?: pts).map { TrackPoint(it.lat, it.lon, it.ele) }), plan.stops, plan.pins, plan.difficulty(), plan.days)
        val id = runCatching {
            // not twice: no second draft with these points, none for a saved route with them; a changed plan opened from
            // Zuletzt updates its entry (RoutesState)
            routes.keepDraft(key, plan.unsaved, plan.savedId, update = { own -> host.updateDraft(own, row()).also { updated = it } }) {
                host.insertDraft(row())
            }
        }.onFailure { Problems.error("db", "The plan could not be kept under Zuletzt", it) }.getOrNull()
        host.refreshTours() // the Zuletzt place changed hands either way (a draft replaced, a saved route there now)
        if (id == null) return null
        Problems.trace("plan", if (updated) "Plan under Zuletzt updated ($why): entry $id, ${pts.size} points"
            else "Plan kept under Zuletzt ($why): \"${plan.dated}\", ${pts.size} points")
        // then the model times and the place name ("Algund → Meran"); nothing fits in time → the date name stays. An
        // updated entry keeps the name it has (the user may have given it): only its times follow the new line
        background.run("nameDraft", { host.storeTimes(id, r, plan.activity) }) {
            if (updated) return@run
            names.ask("nameDraft", pts, r, nameWaitMs) { proposed ->
                background.run("nameDraft", {
                    runCatching { routes.renameDraft(id, plan.dated, proposed) }
                        .onFailure { Problems.warn("db", "The plan under Zuletzt keeps its date name (rename failed)", it) }.getOrDefault(false)
                }) { renamed ->
                    if (renamed) { Problems.trace("plan", "Zuletzt: \"${plan.dated}\" → \"$proposed\""); host.refreshTours() }
                }
            }
        }
        return id.takeIf { updated }
    }

    /** The version of a saved route that a tour start just replaced: under Zuletzt with its name, line and points. */
    override fun keepReplaced(old: Tour, segments: List<List<TrackPoint>>) {
        val key = RoutesState.keyOf(old)
        background.run("keepReplacedVersion", {
            runCatching {
                routes.keepReplaced(key) {
                    host.insertDraft(DraftRow(old.name, old.activity, old.sac, old.distanceM, old.ascentM, old.descentM,
                        old.durationS, segments, old.waypoints, old.pinned, old.difficulty, old.days))
                }
            }.onFailure { Problems.error("db", "The saved route's version before the tour start could not be kept under Zuletzt", it) }
                .getOrNull()
        }) { id ->
            if (id != null) { Problems.trace("plan", "Version before the tour start kept under Zuletzt: \"${old.name}\", ${key.size} points"); host.refreshTours() }
            else Problems.trace("plan", "Version before the tour start not added to Zuletzt: these points are there already (a saved route or a draft)")
        }
    }

    /** My routes → Zuletzt → Speichern: the plan becomes saved (backup, browser sync); it shows "✓ Gerade gespeichert". */
    fun saveDraft(t: Tour) {
        background.run("saveDraft", {
            runCatching { routes.saveDraft(t.id) }.onFailure { Problems.error("db", "A plan under Zuletzt could not be saved", it) }.getOrDefault(false)
        }) { ok ->
            if (!ok) { host.problem(Txt.s(Res.string.msg_route_save_failed)); return@run }
            Problems.trace("plan", "Zuletzt → saved: \"${t.name}\"")
            routes.markSaved(t.id)
            host.tourChanged(t.id)
            host.syncChanged("plan saved from Zuletzt")
            host.refreshTours()
        }
    }

    /** My routes → Zuletzt → Löschen: to the recycle bin at once, no question (owner 2026-11-06). */
    fun deleteDraft(t: Tour) {
        background.run("deleteDraft", {
            runCatching { routes.toBin(t.id) }
                .onFailure { Problems.error("db", "A plan under Zuletzt could not be moved to the recycle bin", it) }.getOrDefault(false)
        }) { ok ->
            if (ok) { Problems.trace("plan", "Zuletzt → recycle bin: \"${t.name}\""); host.refreshTours() }
        }
    }

    // ---------------- recycle bin (docs/core-features/C11-backup.md) ----------------

    /**
     * Once after the update to the one-plan rule (owner 2026-11-12): the drafts an older version collected under
     * Zuletzt go to the recycle bin, all but the newest – nothing is lost for good. Guarded by a pref, idempotent anyway.
     * The clean-up itself runs in the background; the pref and the list read again follow on the caller's thread.
     */
    fun trimOnce() {
        if (host.trimmed) return
        background.run("trimZuletztOnce", {
            runCatching { routes.trimDrafts() }
                .onFailure { Problems.warn("routes", "Zuletzt: older plans not moved to the recycle bin – tried again at the next start", it) }
                .getOrNull()
        }) { gone ->
            if (gone == null) return@run
            host.trimmed = true
            if (gone > 0) {
                Problems.info("routes", "Zuletzt keeps one plan now (owner 2026-11-12): $gone older plan(s) moved to the recycle bin")
                host.refreshTours()
            } else Problems.trace("routes", "Zuletzt keeps one plan now: nothing older to move")
        }
    }

    /** Recycle bin list (My routes → ⋮ → Recycle bin); null = closed. */
    var bin by mutableStateOf<List<Tour>?>(null)

    fun openBin() {
        background.run("openRecycleBin", {
            runCatching { store.listDeleted() }.onFailure { Problems.error("db", "Recycle bin could not be read", it) }.getOrDefault(emptyList())
        }) { bin = it }
    }

    /** Recycle bin → Restore: back into My routes (and its file out of Deleted/); a draft becomes the Zuletzt plan. */
    fun restore(t: Tour) {
        background.run("restoreFromBin", {
            runCatching { routes.restore(t.id) }.onFailure { Problems.error("db", "Restoring from the recycle bin failed", it) }.getOrDefault(false)
        }) { ok ->
            if (ok) { host.tourChanged(t.id); host.syncChanged("restored from the recycle bin") }
            else host.problem(Txt.s(Res.string.msg_tour_restore_failed, t.name))
            openBin(); host.refreshTours()
        }
    }

    /** Recycle bin → Delete now (asked first): gone for good, its backup file too. */
    fun deleteForever(t: Tour) {
        background.run("deleteForever", {
            runCatching { store.deleteForGood(t.id) }.onFailure { Problems.error("db", "Deleting a tour for good failed", it) }.isSuccess
        }) { ok ->
            if (ok) { host.purged(t.uuid); host.syncChanged("deleted for good") }
            openBin()
        }
    }

    companion object {
        /** How long a new route's name waits for its place proposal (it shows the date name meanwhile). */
        const val NAME_WAIT_MS = 5_000L
    }
}
