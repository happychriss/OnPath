package com.onpath.app.ui.tour

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.Tour
import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.domain.AppMode
import com.onpath.app.recording.RecordingClient
import com.onpath.app.routing.Activity
import com.onpath.app.time.TourTimes
import com.onpath.app.ui.EndAsk
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.PlanSize
import com.onpath.app.ui.Say
import com.onpath.app.ui.routes.RoutesState
import com.onpath.app.ui.routes.ToursStore
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/**
 * What [TourEndSlice] asks from the rest of the app. Every call is made at the point of the old code's sequence, with the
 * old arguments, on the thread the old code was on: the main thread, except [tourChanged] (called on io where the old code
 * told the backup inside `withContext(io)` / a launch on io, and on main in the Rückgängig), [tourTimes] (io) and the reads
 * of [activity] / [activityOrNull] in the ride checks and in the save of an unfinished tour (io, as before).
 */
interface TourEndHost {
    /** The plan's activity (the one the user chose; the fall-back of a tour that names none). */
    val activity: Activity
    /** The activity of a stored name, null = unknown. */
    fun activityOrNull(name: String?): Activity?
    /** Moving time / breaks / planned vs. needed of [t] (blocking – called inside `withContext(io)`). */
    fun tourTimes(t: Tour): TourTimes?
    /** A tour still called "Tour 20 Oct 2026, 10:12" gets a place name in the background (stays in the view model: search index, RouteNamer). */
    fun nameRecordedTour(t: Tour)
    /** The list of My routes is read again. */
    fun refreshTours()
    /** The followed line goes (follow state: piece 18a). */
    fun clearFollow()
    /** `enter(m)` (the mode: piece 19). */
    fun enterMode(m: AppMode)
    /** `guidanceEndedByEmptyPlan = false`: nothing of the ended tour is resumed by the next plan. */
    fun endGuidanceFlag()
    /** The ended tour's map picture is made in the background. */
    fun kickThumbs()
    /** A demo ride ends with its tour: "DEMO" / "Sim" leave the map (one call, the `if` is inside). */
    fun demoEndedWithTour()
    /** The backup (and the sync) is told that tour [id] changed. */
    fun tourChanged(id: Long)
    /** The "Tour interrupted" notification goes. */
    fun clearInterruptedNote()
    /** How many points the plan has. */
    val planPoints: Int
    /** The plan goes the one clear way ("Neu planen", [com.onpath.app.ui.plan.PlanEditSlice.clearPlan]). */
    fun clearPlan()
    /** "Route back to the start?" is asked ([com.onpath.app.ui.plan.PlanEditSlice.routeBackAsk]). */
    var routeBackAsk: Boolean
    /** The plan panel's size ([com.onpath.app.ui.plan.PlanEditSlice.planSize]). */
    var planSize: PlanSize
    /** The id of the track shown on the map (My routes), null = none. */
    val shownTourId: Long?
    /** My routes → a tour opened on the map (defaults of the view model's `openTour`). */
    fun reopenShownTour(t: Tour)
}

/**
 * The tour's end (rebuild phase 2 piece 17): the `finished` collector (what becomes of an ended tour – deleted, a short
 * recording, saved), the end sheet's questions and writes (the ride check, the ride choice stored BEFORE the service is told
 * to stop), the short recording's Rückgängig, Edit rides of a saved tour, and the interrupted tour after a restart
 * (Continue, Save, Delete). Moved out of the view model as it was; the view model forwards its old names to it.
 *
 * It talks to the service only through [recording] (the ONE client of the view model: `finishedSeen`, `takeFinishedParts`,
 * and through the controller `stop` / `continueTour`). The tour database is written through [rows] / [tours] on `io`, the
 * backup is told through [host] on the thread the old code told it. The follow state, the mode and the plan are the host's
 * (pieces 18a / 19). The tour paper's rules 7–12, 26–28, 32, 35 hold here; the latent bugs L1 (the unguarded reads in the
 * collector) and L8 (`saveUnfinished`'s unguarded `updateStats`) are kept as they were.
 *
 * [start] launches the `finished` collector: the view model calls it exactly where the collector was launched in its big
 * `init` (paper risk 1: on `Main.immediate` its first pass runs inline during construction).
 */
class TourEndSlice(
    private val tour: TourController,
    private val routes: RoutesState,
    private val recording: RecordingClient,
    private val tours: ToursStore,
    private val rows: TourRows,
    private val host: TourEndHost,
    private val say: Say,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val main: CoroutineDispatcher,
) {
    private val activity: Activity get() = host.activity
    private fun activityOrNull(name: String?): Activity? = host.activityOrNull(name)
    private fun sayError(text: String, place: NoticePlace) = say.say(text, NoticeKind.ERROR, place)

    /** The `finished` collector (moved from the view model's big `init`; see the class comment). */
    fun start() {
        scope.launchSafe("app", "start-up work") {
            recording.finished.collect { id ->
                if (id != null) {
                    recording.finishedSeen()
                    val done = withContext(io) { tours.getTour(id) }
                    // Löschen chosen → the bin; else a short recording (owner 2026-11-12: never got away from its start,
                    // no ride part) → the bin with the box "Kurze Aufzeichnung" and Rückgängig; else the saved box.
                    // A tour not found or not readable is never "short" – a doubt keeps the tour.
                    when (tour.finishedOutcome(short = done != null && isShortRecording(done))) {
                        TourController.Finished.DELETED -> {
                            tour.closeFinished()
                            if (!binFinishedTour(id)) sayError(Txt.s(Res.string.msg_track_delete_failed_here), NoticePlace.MAP)
                        }
                        TourController.Finished.SHORT -> {
                            tour.closeFinished()
                            val ok = binFinishedTour(id)
                            Problems.trace("tour", "Short recording (${done!!.distanceM.toInt()} m, tour $id) → " +
                                if (ok) "recycle bin; the box with Rückgängig" else "bin write failed; kept as a tour (saved box)")
                            tour.afterShortBin(done, binned = ok, times = if (ok) null else withContext(io) { host.tourTimes(done) })
                        }
                        TourController.Finished.SAVED -> {
                            tour.showFinished(done, withContext(io) { done?.let { host.tourTimes(it) } })
                            done?.let { host.nameRecordedTour(it) }
                            // "Split into two tours": the parts after the rides get a place name too
                            val parts = recording.takeFinishedParts()
                            parts.forEach { p -> withContext(io) { tours.getTour(p) }?.let { host.nameRecordedTour(it) } }
                        }
                    }
                    host.refreshTours()
                    host.clearFollow()
                    // a demo ride ends with its tour: no "DEMO" / "Sim" left on the map (the recording is closed – no jump into it)
                    host.demoEndedWithTour()
                    host.enterMode(AppMode.PLAN)
                    tour.afterFinished() // no arrival box, no "Neuer Plan" line of the old tour
                    afterTourEnded()
                }
            }
        }
    }

    /**
     * The tour has ended – saved or deleted, from the end sheet, the ride summary or the arrival box (owner 2026-11-07:
     * "Tour beenden" → the track is saved, the plan is saved as always, and the app is back at the start as after
     * "✕ Neu planen"): the plan goes the one clear way ([TourEndHost.clearPlan]) – a plan saved as it is (at "Los geht's" or by
     * Save) stays in My routes, one changed on the way goes under Zuletzt ([SaveSlice.keepAsDraft]), one Undo brings it back on the
     * map; the empty sheet shows its guide. The tour and the plan stay two things (owner 2026-11-06): nothing about the
     * plan is asked at the end.
     */
    private fun afterTourEnded() {
        Problems.trace("tour", "Tour ended: back to the start – the plan (${host.planPoints} points) goes the \"Neu planen\" way")
        host.clearPlan()
        host.endGuidanceFlag() // nothing of the ended tour is resumed by the next plan
        host.kickThumbs() // the ended tour's map picture: in the background, the tour is saved already
    }

    /** The ended tour [id] into the recycle bin (Löschen, or a short recording); false = it stays in My routes (said). */
    private suspend fun binFinishedTour(id: Long): Boolean = withContext(io) {
        runCatching { rows.setDeleted(id, System.currentTimeMillis()) }
            .onFailure { Problems.error("db", "The ended tour could not be moved to the recycle bin; it stays in My routes", it) }
            .getOrDefault(false).also { if (it) host.tourChanged(id) }
    }

    /**
     * [ShortRecording.bins] of the finished tour [t]: its points as stored (every piece), the distance as recorded
     * (before any ride choice) and whether a ride part was found. Read on IO; a read that fails keeps the tour (never short).
     */
    private suspend fun isShortRecording(t: Tour): Boolean = withContext(io) {
        runCatching {
            val pieces = tours.loadSegments(t.id)
            val recorded = t.rides?.recorded?.distanceM ?: t.distanceM
            com.onpath.app.domain.tour.ShortRecording.bins(recorded, pieces, rides = t.rides?.rides?.isNotEmpty() == true)
        }.onFailure { Problems.warn("db", "The ended tour's points could not be read for the short-recording check; kept as a tour", it) }
            .getOrDefault(false)
    }

    /**
     * The short box → Rückgängig (owner 2026-11-12): the tour comes back from the recycle bin as a normal tour – its
     * saved box "Tour gespeichert", a place name if there is a line – as if it had been saved at the end. The restore
     * fails → it stays in the bin (restorable there), said in red.
     */
    fun undoShortRecording() {
        val t = tour.takeShortBinned() ?: return
        Problems.trace("tour", "Rückgängig: short recording ${t.id} back from the recycle bin as a tour")
        scope.launchSafe("app", "undoShortRecording") {
            val ok = withContext(io) {
                runCatching { routes.restore(t.id) }
                    .onFailure { Problems.error("db", "The short recording could not be brought back from the recycle bin", it) }
                    .getOrDefault(false)
            }
            val done = withContext(io) { tours.getTour(t.id) }
            if (!ok || done == null) { sayError(Txt.s(Res.string.msg_tour_restore_failed, t.name), NoticePlace.MAP); host.refreshTours(); return@launchSafe }
            host.tourChanged(t.id)
            tour.showFinished(done, withContext(io) { host.tourTimes(done) })
            host.nameRecordedTour(done)
            host.refreshTours()
        }
    }

    /** The short box → OK: it stays in the recycle bin (30 days). */
    fun closeShortRecording() {
        tour.takeShortBinned()?.let { Problems.trace("tour", "Short recording ${it.id}: OK – stays in the recycle bin") }
    }

    /** The arrival box → "Weiter aufzeichnen": the box goes, the recording goes on (the guidance stays quiet). */
    fun keepRecordingAfterArrival() {
        Problems.trace("tour", "Arrival: keep recording")
        tour.arrivalAsk = false
    }

    /** "End tour" in Plan (after confirmation) or "Tour beenden" on arrival: stop and save. */
    fun endTour() {
        tour.end(delete = false)
        stopRecording() // the plan is not touched (owner 2026-11-06: two things)
    }

    /**
     * "End tour" tapped: rides in the track so far (docs/design/vehicle-detection.md) → the summary with four choices,
     * otherwise the short Cancel · Delete · Save question. Detection failing = the short question (reported).
     * [saveIfNoRides]: Save was already chosen ("You have arrived" → Save) – with
     * rides the summary shows, without them the tour is saved at once.
     */
    fun askEndTour(saveIfNoRides: Boolean = false) {
        tour.arrivalAsk = false
        val id = recording.state.value?.tourId ?: run { if (saveIfNoRides) endTour() else tour.endAsk = EndAsk.Plain; return }
        val navActivity = recording.state.value?.nav?.session?.activity
        scope.launchSafe("app", "askEndTour") {
            val s = withContext(io) {
                runCatching {
                    val segs = tours.loadSegments(id)
                    val a = navActivity ?: activityOrNull(tours.getTour(id)?.activity) ?: activity
                    com.onpath.app.rides.RideView.summary(segs, com.onpath.app.rides.RideDetector.detect(segs, a), onFoot = a == Activity.HIKE)
                }.onFailure { Problems.warn("rides", "Ride check at End tour failed – asked without it", it) }.getOrNull()
            }
            if (recording.state.value?.tourId != id) return@launchSafe // ended meanwhile (notification, arrival)
            when (com.onpath.app.domain.tour.TourEndFlow.afterRideCheck(s != null, saveIfNoRides)) {
                com.onpath.app.domain.tour.TourEndFlow.Next.RIDES -> tour.endAsk = EndAsk.Rides(id, s!!)
                com.onpath.app.domain.tour.TourEndFlow.Next.SAVE_NOW -> endTour()
                com.onpath.app.domain.tour.TourEndFlow.Next.ASK_PLAIN -> tour.endAsk = EndAsk.Plain
            }
        }
    }

    /** Save from the ride summary: the choice is stored on the tour first, the recording service applies it at the end. */
    fun endTourWith(a: EndAsk.Rides, choice: com.onpath.app.rides.RideChoice) {
        tour.endAsk = null
        scope.launchSafe("app", "endTourWith") {
            withContext(io) {
                runCatching { com.onpath.app.rides.RideApply.choose(rows.rides(), a.tourId, a.summary.rides, choice) }
                    .onFailure { Problems.error("rides", "The choice for the ride could not be stored – the tour is saved as recorded", it) }
            }
            endTour()
        }
    }

    /** My routes → ⋮ → Edit rides: the same summary for a saved tour (no split, no delete there). */
    var editRides by mutableStateOf<Pair<Tour, com.onpath.app.rides.RideView.Summary>?>(null)

    fun openEditRides(t: Tour) {
        val rec = t.rides ?: return
        scope.launchSafe("app", "openEditRides") {
            val s = withContext(io) {
                runCatching {
                    val onFoot = (t.activity ?: Activity.HIKE.name) == Activity.HIKE.name
                    com.onpath.app.rides.RideView.summary(tours.loadSegments(t.id), rec.rides, onFoot, allowSplit = false)
                }.onFailure { Problems.warn("rides", "Rides of a tour could not be shown", it) }.getOrNull()
            }
            if (s == null) { sayError(Txt.s(Res.string.msg_rides_read_failed), NoticePlace.ROUTES); return@launchSafe }
            editRides = t to s
        }
    }

    fun applyEditRides(t: Tour, choice: com.onpath.app.rides.RideChoice) {
        editRides = null
        scope.launchSafe("app", "applyEditRides") {
            val ok = withContext(io) {
                runCatching { com.onpath.app.rides.RideApply.edit(rows.rides(), t.id, choice) }
                    .onFailure { Problems.error("rides", "Changing the rides of a tour failed; it stays as it was", it) }.isSuccess
                    .also { if (it) host.tourChanged(t.id) }
            }
            if (!ok) sayError(Txt.s(Res.string.msg_rides_change_failed), NoticePlace.ROUTES)
            host.refreshTours()
            if (host.shownTourId == t.id) withContext(io) { tours.getTour(t.id) }?.let { host.reopenShownTour(it) }
        }
    }

    /**
     * End sheet / arrival → Löschen: the TOUR goes straight to the recycle bin, no summary. The plan stays on the map as
     * it is (owner 2026-11-06: the tour and the plan are two things – it was saved at the start).
     */
    fun endTourAndDelete() {
        tour.end(delete = true)
        stopRecording()
    }

    fun continueRecording(t: Tour) {
        com.onpath.app.recording.InterruptedLog.chose(t.name, com.onpath.app.recording.InterruptedLog.Choice.CONTINUE, host::clearInterruptedNote)
        tour.continueTour(t)
    }

    /**
     * "Unfinished tour" → Save: like End tour – rides found → the summary with the four
     * choices ([TourController.unfinishedRides]), else saved at once. Detection failing = saved as recorded (reported).
     */
    fun askSaveUnfinished(t: Tour) {
        tour.unfinished = null
        if (recording.state.value?.tourId == t.id) { host.enterMode(AppMode.NAVIGATE); return }
        scope.launchSafe("app", "askSaveUnfinished") {
            val s = withContext(io) {
                runCatching {
                    val segs = tours.loadSegments(t.id)
                    val a = activityOrNull(t.activity) ?: activity
                    com.onpath.app.rides.RideView.summary(segs, com.onpath.app.rides.RideDetector.detect(segs, a), onFoot = a == Activity.HIKE)
                }.onFailure { Problems.warn("rides", "Ride check of the unfinished tour failed – saved without it", it) }.getOrNull()
            }
            when (com.onpath.app.domain.tour.TourEndFlow.unfinishedSave(s != null)) {
                com.onpath.app.domain.tour.TourEndFlow.Next.RIDES -> tour.unfinishedRides = t to s!!
                else -> saveUnfinished(t)
            }
        }
    }

    /**
     * Saves the interrupted tour; [choice] from the ride summary is applied like at the end of a recording
     * (RideApply.finish – without a choice the rides are only marked, Edit rides can change it later).
     */
    fun saveUnfinished(t: Tour, choice: com.onpath.app.rides.RideChoice? = null) {
        val rides = tour.unfinishedRides?.second?.rides
        com.onpath.app.recording.InterruptedLog.chose(t.name, com.onpath.app.recording.InterruptedLog.Choice.SAVE, host::clearInterruptedNote)
        tour.closeUnfinished()
        // the sticky service came back meanwhile and records this tour: it is not unfinished, leave it alone
        if (recording.state.value?.tourId == t.id) { host.enterMode(AppMode.NAVIGATE); return }
        host.enterMode(AppMode.PLAN)
        scope.launchSafe("app", "saveUnfinished", io) {
            rows.updateStats(t.id, t.distanceM, t.ascentM, t.descentM, t.durationS, "done")
            val store = rows.rides()
            if (choice != null && rides != null) runCatching { com.onpath.app.rides.RideApply.choose(store, t.id, rides, choice) }
                .onFailure { Problems.error("rides", "The choice for the ride could not be stored – the tour is saved as recorded", it) }
            val a = activityOrNull(t.activity) ?: activity
            val parts = com.onpath.app.rides.RideApply.finish(store, t.id,
                com.onpath.app.rides.TourStats(t.distanceM, t.ascentM, t.descentM, t.durationS), System.currentTimeMillis(), a)
            host.tourChanged(t.id)
            parts.forEach { host.tourChanged(it) }
            // the track is in My routes – no message (owner: no "Track saved" bar)
            withContext(main) { host.refreshTours() }
        }
    }

    /** "Unfinished tour" → Delete (asked again in the dialog): saved as done, then into the recycle bin (30 days). */
    fun deleteUnfinished(t: Tour) {
        com.onpath.app.recording.InterruptedLog.chose(t.name, com.onpath.app.recording.InterruptedLog.Choice.DELETE, host::clearInterruptedNote)
        tour.closeUnfinished()
        if (recording.state.value?.tourId == t.id) { host.enterMode(AppMode.NAVIGATE); return }
        Problems.trace("tour", "Unfinished tour ${t.id} deleted (recycle bin)")
        host.enterMode(AppMode.PLAN)
        scope.launchSafe("app", "deleteUnfinished", io) {
            val ok = runCatching {
                // "done" first: restored from the recycle bin it is a normal tour, never "unfinished" again
                rows.updateStats(t.id, t.distanceM, t.ascentM, t.descentM, t.durationS, "done")
                rows.setDeleted(t.id, System.currentTimeMillis())
            }.onFailure { Problems.error("db", "The unfinished tour could not be moved to the recycle bin", it) }.getOrDefault(false)
            if (ok) host.tourChanged(t.id)
            withContext(main) {
                // the dialog closing is the result; only a failure is said
                if (!ok) sayError(Txt.s(Res.string.msg_track_delete_failed), NoticePlace.MAP)
                host.refreshTours()
            }
        }
    }

    fun pauseRecording(pause: Boolean) = tour.pause(pause)

    fun stopRecording() {
        host.routeBackAsk = false // a question about this tour's plan never reaches the next tour
        host.planSize = PlanSize.afterTour(host.planSize)
        tour.stop()
    }
}
