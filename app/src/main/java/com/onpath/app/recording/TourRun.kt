package com.onpath.app.recording

import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.GeoPoint

/**
 * Everything [RecordingService] counts and remembers for one tour: the track and its totals (written back to the
 * database by `updateStats` / `saveStats`), the numbering of the points, the pause, the arrival, the junction and
 * standing-still memories, "Navigation Stopp". Pure, unit-tested (`TourRunTest`).
 *
 * The service makes a new one at every tour start – a new tour gets [TourRun] (), a continued one [resumed] – so
 * nothing of the tour before carries over, also when Android hands the next "Los geht's" to the same service
 * instance (a start delivered before the old instance was destroyed). Before 2026-11-07 these were fields of the
 * service set once in `onCreate`; the start of a new tour reset only some of them, and a reused instance would have
 * begun the new tour with the old distance, time, track and point numbers – and written them to the new tour's row.
 *
 * Kept out on purpose: the voice memory ([com.onpath.app.navigation.VoiceGuide]) and the pace ([com.onpath.app.time.TripPace])
 * – both have their own new-tour / continue path (`trip-pace.json`); the point queue – its waiting points carry their
 * own tour id and are still written after the tour ended.
 */
internal class TourRun {
    /** Number of the next point in the database ([com.onpath.app.data.TourDb]); a new tour starts at 0. */
    var seq = 0
    /** Track piece of the next point; a new piece after a pause, a long GPS gap, a switch demo ↔ real GPS. */
    var seg = 0
    var paused = false
    /** Wall-clock start of the current pause ([RecState.pausedSinceMs]); null while recording. */
    var pausedSinceMs: Long? = null
    var distance = 0.0
    var ascent = 0.0
    var descent = 0.0
    var elapsedS = 0L
    /** The part of a second not counted yet (the service's tick). */
    var tickRestMs = 0L
    /** The last accepted point (null = the next one starts fresh: no distance, no jump check against it). */
    var last: TrackPoint? = null
    var lastGoodFixElapsed = 0L
    /** Append-only; its snapshot for [RecState] costs O(pieces) ([GrowingTrack]). */
    val track = GrowingTrack()
    /** The piece of [track] the last point went into. */
    var trackSeg = -1
    /** A database write failed; cleared by the next successful point write. */
    var saveFailed = false
    /** Elapsed-realtime of the tour's first fix: its first heights settle again ([SeaLevel]). */
    var firstFixElapsed = 0L
    /** The last fix was simulated (Developer tools → Demo). */
    var lastFixDemo = false
    /** Battery saver said once per tour. */
    var saverWarned = false

    /** Ascent / descent from the terrain under the points, GPS altitude where there is none. */
    val climb = AscentCounter()
    /** Fixes dropped for accuracy since the last good one. */
    val gapWatch = PointFilter.GapWatch()
    val moveWatch = MoveWatch()
    val pauseWatch = PauseWatch()

    /** Arrival at the end of the followed line (review H2). */
    val arrivalGate = com.onpath.app.routing.ArrivalGate()
    var arrived = false
    /** The session token the arrival belongs to. */
    var arrivedToken: Long? = null
    var lineLength: Pair<Int, Double>? = null

    /** Vibration before junctions of the followed line + the next junction for the lock screen. */
    val junctions = com.onpath.app.navigation.JunctionCues()
    val junctionSpeeds = com.onpath.app.navigation.SpeedWindow()
    /** Line version whose junction count was traced. */
    var junctionsTracedVersion = Int.MIN_VALUE

    /** Where the map areas ahead were last asked for (T5). */
    var t5LastAt: GeoPoint? = null
    var estimatePaused: com.onpath.app.time.PauseReason? = null

    /** False while the app is in Plan during the tour; a tour starts in Navigate. */
    var navigating = true
    var navigatingApplied = true
    val stopQuiet = StopQuiet()

    /** An accepted point that [movedM] metres on: counted; [newPiece] = a long GPS gap before it starts a new piece. */
    fun count(movedM: Double, newPiece: Boolean) {
        distance += movedM
        if (newPiece) seg++
    }

    /** The point the database got goes on the shown track (a new piece when [seg] changed). */
    fun show(p: TrackPoint) {
        last = p
        if (track.isEmpty() || trackSeg != seg) { track.newSegment(); trackSeg = seg }
        track.add(p.toGeo())
    }

    companion object {
        /**
         * A tour continued from its saved row: its totals and points, the next point in a new piece (no straight jump
         * from where it stopped). Everything else starts fresh, as for a new tour.
         */
        fun resumed(distanceM: Double, ascentM: Double, descentM: Double, durationS: Long, nextSeq: Int, lastSeg: Int,
                    segments: List<List<GeoPoint>>): TourRun = TourRun().apply {
            distance = distanceM; ascent = ascentM; descent = descentM
            elapsedS = durationS
            seq = nextSeq; seg = lastSeg + 1
            segments.forEach { s -> track.newSegment(); s.forEach { track.add(it) } }
            trackSeg = lastSeg
        }
    }
}
