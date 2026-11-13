package com.onpath.app.ui.tour

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.Tour
import com.onpath.app.debug.Problems
import com.onpath.app.domain.tour.ShortRecording
import com.onpath.app.rides.RideView
import com.onpath.app.routing.GeoPoint
import com.onpath.app.time.TourTimes
import com.onpath.app.ui.EndAsk
import com.onpath.app.ui.NewPlanNote

/**
 * What the tour controller asks of the recording service (the only Android-bound part): start / continue / pause /
 * stop. The view model passes a small adapter around `RecordingService.send`; tests pass a fake.
 */
interface TourRecorder {
    fun start(activity: String, sac: Int)
    fun continueTour(tourId: Long)
    fun pause(pause: Boolean)
    fun stop()
}

/**
 * The tour's lifecycle state in the UI (audit package 4, stage 2 – out of AppViewModel, Google's "state holder"):
 * the end sheet ([endAsk]), the arrival box ([arrivalAsk]), the saved box ([finishedTour]), the interrupted tour after
 * a restart ([unfinished], its track and its ride summary), the "Neuer Plan" line ([newPlanNote]) and whether the tour that is ending goes to the recycle bin. Android-free: the service
 * sits behind [TourRecorder], so the whole flow is testable on the JVM (TourFlowPropertyTest).
 *
 * The plan is not here: the tour and the plan are two things (owner 2026-11-06); the view model joins them
 * (followEditedPlan, the guidance end when the plan empties) and keeps its public names as thin forwarders.
 */
class TourController(private val recorder: TourRecorder) {

    /** What "End tour" shows now (null = nothing): the plain end sheet or the ride summary. */
    var endAsk by mutableStateOf<EndAsk?>(null)
    /** "You have arrived – end tour?" is open. */
    var arrivalAsk by mutableStateOf(false)
    /** The tour just saved: the slim box "Tour gespeichert" while it is set (owner 2026-11-06, onpath.private#35). */
    var finishedTour by mutableStateOf<Tour?>(null)
    /** Moving time / breaks / planned vs. needed of [finishedTour] (null = not known). */
    var finishedTimes by mutableStateOf<TourTimes?>(null)
    /** A tour still marked as recording after a restart, with no service running: its question (Continue · Delete · Save). */
    var unfinished by mutableStateOf<Tour?>(null)
    /** The track of [unfinished], drawn on the map while its question shows (no service runs then). */
    var unfinishedTrack by mutableStateOf<List<List<GeoPoint>>>(emptyList())
    /** "Unfinished tour" → Save with a bus / car / lift found: the ride summary for it (null = none). */
    var unfinishedRides by mutableStateOf<Pair<Tour, RideView.Summary>?>(null)
    /**
     * The start-up check for a tour still marked as recording has not decided yet (the database read, then ~1.5 s for
     * a sticky service to come back). Set by the view model at start, cleared when the check ends.
     */
    var unfinishedPending by mutableStateOf(false)
    /**
     * A new recording may start now: the start-up check has run and no interrupted tour waits for its answer (review
     * 2026-11-12: ☰ → start recording in that window, the phone setup guide open, the question arriving behind it –
     * a new tour would have started beside the old one still marked active). Continue ([continueTour]) is never held.
     */
    val canStartNew: Boolean get() = !unfinishedPending && unfinished == null && unfinishedRides == null
    /**
     * A plan or a done tour was tapped in My routes during a tour and is followed now – no question (owner 2026-11-06
     * 22:05): "Neuer Plan – deine Aufnahme läuft weiter" with "Rückgängig" when a plan was replaced. Null = no line.
     */
    var newPlanNote by mutableStateOf<NewPlanNote?>(null)
        private set

    private var newPlanNotes = 0L
    /** The tour that is ending goes to the recycle bin (end sheet → Löschen), not into "Tour gespeichert". */
    private var deleteFinished = false
    private var arrivalSeen = false

    // ---- the service ----

    /** Starts a new recording – not while an interrupted tour is still checked or asked about ([canStartNew]). */
    fun start(activity: String, sac: Int) {
        if (!canStartNew) { Problems.trace("tour", "Tour start held: an interrupted tour is still checked / asked about"); return }
        recorder.start(activity, sac)
    }
    fun pause(pause: Boolean) = recorder.pause(pause)
    fun stop() = recorder.stop()

    /** "Interrupted tour" → Continue: its question goes, the service records into the same tour. */
    fun continueTour(t: Tour) {
        unfinished = null
        recorder.continueTour(t.id)
    }

    // ---- ending ----

    /**
     * End tour with [delete] = false (Save) or true (Löschen – straight to the recycle bin, no summary): the sheet and the
     * arrival box go, the service is asked to stop. The plan is not touched (owner 2026-11-06: two things).
     */
    fun end(delete: Boolean) {
        Problems.trace("tour", if (delete) "End tour (delete)" else "End tour (save)")
        endAsk = null; arrivalAsk = false; deleteFinished = delete
    }

    fun dismissEndAsk() { endAsk = null }

    /** The service reported the tour as finished: whether it goes to the recycle bin (taken once – the next tour saves). */
    fun takeDeleteFinished(): Boolean = deleteFinished.also { deleteFinished = false }

    /** What becomes of the finished tour ([finishedOutcome]). */
    enum class Finished {
        /** The end sheet's Löschen: the recycle bin, nothing said. */
        DELETED,
        /** Less than [ShortRecording.MIN_M] of track (owner 2026-11-12): the recycle bin with the notice and Rückgängig. */
        SHORT,
        /** The saved box "Tour gespeichert". */
        SAVED,
    }

    /**
     * The service reported the tour as finished with [distanceM] of track: the user's Löschen first (taken once), else
     * a short recording goes to the bin ([ShortRecording]), else it is saved.
     */
    fun finishedOutcome(distanceM: Double): Finished = when {
        takeDeleteFinished() -> Finished.DELETED
        ShortRecording.bins(distanceM) -> Finished.SHORT
        else -> Finished.SAVED
    }

    /** After the tour ended (saved or deleted): no arrival box, no "Neuer Plan" line of the old tour. */
    fun afterFinished() { arrivalAsk = false; newPlanNote = null }

    /** The saved box for [t] ("Tour gespeichert"), or none ([t] = null). */
    fun showFinished(t: Tour?, times: TourTimes?) { finishedTour = t; finishedTimes = times }

    /** The saved box goes (OK, "In Meine Touren ›", the tour was deleted); returns the tour it showed. */
    fun closeFinished(): Tour? = finishedTour.also { finishedTour = null; finishedTimes = null }

    /**
     * The service found the end of the followed line (RecState.arrived): the arrival question once per arrival this app
     * instance sees.
     */
    fun onArrived(arrived: Boolean) {
        if (arrived && !arrivalSeen) { arrivalSeen = true; arrivalAsk = true }
        if (!arrived) arrivalSeen = false
    }

    // ---- the interrupted tour ----

    fun showUnfinished(t: Tour, track: List<List<GeoPoint>>) { unfinishedTrack = track; unfinished = t }

    /** Its question goes (Save / Delete chosen) – the ride summary too. */
    fun closeUnfinished() { unfinished = null; unfinishedRides = null }

    /** Ride summary of the unfinished tour → Cancel: back to its question (Continue · Delete · Save). */
    fun backToUnfinished() { unfinishedRides?.let { unfinished = it.first }; unfinishedRides = null }

    // ---- "Neuer Plan" line ----

    /** A plan / done tour is followed now during the tour; [undo] = a plan was replaced, "Rückgängig" brings it back. */
    fun noteNewPlan(undo: Boolean) { newPlanNote = NewPlanNote(undo = undo, id = ++newPlanNotes) }

    fun dropNewPlanNote() { newPlanNote = null }
}
