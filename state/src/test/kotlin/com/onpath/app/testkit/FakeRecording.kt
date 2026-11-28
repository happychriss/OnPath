package com.onpath.app.testkit

import com.onpath.app.data.Geo
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.OffRouteDetector
import com.onpath.app.navigation.VoiceGuide
import com.onpath.app.recording.GpsWatchdog
import com.onpath.app.recording.GuidanceGate
import com.onpath.app.recording.PointFilter
import com.onpath.app.recording.PointQueue
import com.onpath.app.recording.RecState
import com.onpath.app.recording.RestWatch
import com.onpath.app.recording.TourRun
import com.onpath.app.routing.GeoPoint
import com.onpath.app.setup.LocationSaverMode
import com.onpath.app.ui.tour.TourRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The recording service as a state holder sees it (test kit): the commands it is sent ([TourRecorder] – what
 * `RecordingService.send` carries) and the state it publishes ([state], what `RecordingService.state` gives: null =
 * no tour runs). Behind it the real tables ([JdbcTours]): a start writes the tour row as "active", every fix a point at
 * once, the end marks it "done" – so a test can kill the process mid-tour ([kill]) and look at what a restart finds.
 *
 * A fix and a second of the tour ([fix], [tick]) go through the service's REAL core pieces, wired as `RecordingService`
 * `onFix` / `tick` / `setPaused` / `finish` wire them: [TourRun] (numbers, pieces, distance; a continued tour is
 * [TourRun.resumed] from the row and `recordingCursor`), [PointFilter] (accuracy, jumps, standing still, a gap bridged or
 * a new piece), [PointQueue] (a point that cannot be written waits and goes in with the next one – `writePoints` /
 * `safeDb`: logged, the notification once per failure [saveFailNotices]; [queueMax] = its cap), [GpsWatchdog], and
 * "GPS lost" by [RestWatch.lostAfterMs]. The time is [clock] (the service's elapsed realtime and the fix time in one):
 * no fix for [GAP_MS] = a gap, the stats written every [STATS_MS] by the tick – so a [kill] between two ticks loses
 * what the phone loses. With [nav] the fixes go on to the service's navigation ([FakeNavService]: the real Navigator),
 * and a tick without GPS tells it so, as the service does; the arrival at the line's end is checked on every fix as
 * `RecordingService.checkArrived` checks it (guidance quiet from then on, [RecState.arrived]).
 *
 * Two clocks, as on the phone: [clock] is the elapsed realtime (ticks, gaps, navigation), [wall] the wall clock
 * (`System.currentTimeMillis`: the tour's start, the pause's start, a fix's time unless [fix] is given one – `loc.time`).
 * [permitted] is the precise-location grant, read at every start as the service reads it: without it the start is
 * refused loudly ([startFailed], the service's `failLoud`) and the tour row is left as it is.
 *
 * Until the service has an Android-free core of its own, these lines mirror its wiring (`TestKitTest` pins the lines
 * they copy). Not here: the motion sensor (no sensor = [MoveWatch][com.onpath.app.recording.MoveWatch] decides), the
 * standing-still GPS rate, ascent, the trip pace, the voice, notifications.
 */
class FakeRecording(val tours: JdbcTours, private val clock: () -> Long = System::currentTimeMillis,
                    private val nav: FakeNavService? = null, private val wall: () -> Long = clock,
                    private val permitted: () -> Boolean = { true }, private val queueMax: Int = PointQueue.MAX) : TourRecorder {
    companion object {
        /** `RecordingService.GAP_MS`: no fix for longer = the next one comes after a gap. */
        const val GAP_MS = 30_000L
        /** `RecordingService.tick`: the stats go to the tour row every 10 s. */
        const val STATS_MS = 10_000L
        /** `RecordingService`'s log line when it refuses to record without precise location (`failLoud`). */
        const val NO_PERMISSION = "Recording stopped: OnPath needs precise location. Allow it in Settings → Apps → OnPath → Permissions."
    }

    private val _state = MutableStateFlow<RecState?>(null)
    val state: StateFlow<RecState?> get() = _state

    /** Every command received, in order ("start HIKE 2", "continue 7", "pause", "resume", "stop"). */
    val commands = ArrayList<String>()
    /** The tour the last [stop] finished (the service's "finished" event); null = none yet. */
    var finished: Long? = null
        private set
    /** "No GPS after a restart" notifications ([GpsWatchdog]) so far. */
    var watchdogAlarms = 0
        private set
    /** Why the service stopped by itself (`RecordingService.startFailed`: the app says it); null = nothing. */
    var startFailed: String? = null
        private set
    /** "Recording cannot be saved" notifications shown so far (`notifySaveFailed`: once per failure, not per point). */
    var saveFailNotices = 0
        private set
    /** That notification is up (it stays until a point is written again – `nm().cancel(SAVE_FAIL_ID)`). */
    var saveFailNoticeUp = false
        private set
    /** "Battery saver stops GPS with the screen off" notifications so far (`checkBatterySaver`: once per tour). */
    var saverNotices = 0
        private set
    /** Points waiting for the database ([PointQueue]); [droppedPoints] given up at its cap. */
    val waitingPoints: Int get() = queue.size
    val droppedPoints: Int get() = queue.dropped

    private var tourId = -1L
    private var run = TourRun()
    private var queue = PointQueue(queueMax) // one per service, not per tour: its points carry their own tour id
    private val watchdog = GpsWatchdog()
    private var lastTick = 0L
    private var lastStats = 0L

    val running: Boolean get() = tourId >= 0

    override fun start(activity: String, sac: Int) {
        commands += "start $activity $sac"
        if (running) return // the service ignores a second start while recording
        if (refused()) return
        val now = clock()
        val id = tours.insertTour(TourKind.RECORDED, "Tour", activity, sac, state = "active", createdMs = wall())
        begin(id, TourRun(), now, sticky = false)
        tours.finishOtherActive(tourId)
    }

    override fun continueTour(tourId: Long) {
        commands += "continue $tourId"
        if (running) return
        if (refused()) return
        resume(tourId, sticky = false)
    }

    /** The system brought the service back by itself after the process died (START_STICKY): it continues the active tour. */
    fun restartSticky() {
        commands += "sticky"
        if (running) return
        val active = tours.findActiveRecording() ?: return
        if (refused()) return
        resume(active.id, sticky = true)
    }

    /** `RecordingService.onStartCommand`: no precise location = told, nothing started, the tour row untouched (R-01). */
    private fun refused(): Boolean {
        if (permitted()) return false
        Problems.error("recording", NO_PERMISSION)
        startFailed = NO_PERMISSION
        return true
    }

    /** The app said it ([com.onpath.app.recording.RecordingClient.startFailedSeen]). */
    fun startFailedSeen() { startFailed = null }

    private fun resume(id: Long, sticky: Boolean) {
        val t = tours.getTour(id) ?: return
        val (nextSeq, lastSeg) = tours.recordingCursor(id)
        val segs = tours.loadSegments(id).map { s -> s.map { it.toGeo() } }
        begin(id, TourRun.resumed(t.distanceM, t.ascentM, t.descentM, t.durationS.toLong(), nextSeq, lastSeg, segs), clock(), sticky)
    }

    private fun begin(id: Long, r: TourRun, now: Long, sticky: Boolean) {
        tourId = id; run = r
        safe("stats") { tours.updateStats(tourId, run.distance, run.ascent, run.descent, run.elapsedS.toDouble(), "active") }
        run.moveWatch.start(now)
        if (sticky) watchdog.arm(now) else watchdog.disarm()
        nav?.followChanged()
        lastTick = now; lastStats = now
        publish(now)
    }

    override fun pause(pause: Boolean) {
        commands += if (pause) "pause" else "resume"
        if (!running || run.paused == pause) return
        val now = clock()
        run.paused = pause
        run.pausedSinceMs = if (pause) wall() else null
        if (!pause) { run.seg++; run.last = null; run.moveWatch.start(now); watchdog.resumed(now) } // a new piece, not bridged
        nav?.gpsLost()
        safe("stats") { tours.updateStats(tourId, run.distance, run.ascent, run.descent, run.elapsedS.toDouble(), "active") }
        publish(now)
    }

    override fun stop() {
        commands += "stop"
        if (!running) return
        if (queue.size > 0) writePoints()
        safe("stats") { tours.updateStats(tourId, run.distance, run.ascent, run.descent, run.elapsedS.toDouble(), "done") }
        finished = tourId
        tourId = -1
        _state.value = null
    }

    /**
     * A GPS fix (`RecordingService.onFix`): judged, stored at once and counted – or dropped; nothing while paused.
     * [timeMs] = the fix's own time (`loc.time`), by default the [wall] clock.
     */
    private val gapSplit = com.onpath.app.recording.GapSplit()

    fun fix(p: GeoPoint, accuracyM: Float = 5f, timeMs: Long = wall()) {
        if (!running || run.paused) return
        if (!PointFilter.accuracyOk(true, accuracyM)) { run.gapWatch.onDropped(true, accuracyM); return }
        val now = clock()
        watchdog.disarm()
        val wasLost = run.lastGoodFixElapsed > 0 && now - run.lastGoodFixElapsed > maxOf(GAP_MS, com.onpath.app.recording.RestWatch.normalMs() * 2 + 5_000L)
        run.lastGoodFixElapsed = now
        run.gapWatch.reset()
        val tp = TrackPoint(p.lat, p.lon, p.ele, timeMs)
        val prev = run.last
        val bridged = wasLost && prev != null && PointFilter.bridgesGap(prev, tp)
        val split = gapSplit.split(wasLost, bridged, prev != null)
        val (verdict, moved) = PointFilter.judge(prev, tp, accuracyM.toDouble(), continuous = !split)
        gapSplit.judged(split, verdict == PointFilter.Verdict.ACCEPT)
        if (verdict == PointFilter.Verdict.JUMP) return // a jump is no position: not for the track, the navigation, the arrival
        // standing still or arrived: no off-route check, the detector waits as without GPS (GuidanceGate.offRouteCheck);
        // then the arrival, on the navigator's state after this fix – both after the jump rule, as in the service
        nav?.let {
            if (GuidanceGate.offRouteCheck(run.moveWatch.moving(now), run.arrived)) it.fix(OffRouteDetector.Fix(p.lat, p.lon, accuracyM.toDouble(), now))
            else it.gpsLost()
            it.main.execute { checkArrived(p, accuracyM.toDouble()) }
        }
        if (verdict != PointFilter.Verdict.ACCEPT) return // standing still
        run.moveWatch.moved(now)
        run.count(moved, newPiece = split)
        queue.add(PointQueue.Pending(tourId, run.seq++, run.seg, tp, accuracyM))
        writePoints(withNewPoint = true)
        run.show(tp)
        publish(now)
    }

    /** One second of the tour (`RecordingService.tick`): time, GPS lost, the watchdog, the stats every [STATS_MS]. */
    fun tick() {
        if (!running) return
        val now = clock()
        if (!run.paused) {
            run.tickRestMs += (now - lastTick).coerceIn(1_000L, 600_000L)
            run.elapsedS += run.tickRestMs / 1000; run.tickRestMs %= 1000
        }
        lastTick = now
        if (gpsLost(now)) nav?.gpsLost() // no alarms without GPS (R-04)
        if (watchdog.due(now, run.paused)) watchdogAlarms++
        if (now - lastStats >= STATS_MS) {
            lastStats = now
            if (queue.size > 0) writePoints()
            safe("stats") { tours.updateStats(tourId, run.distance, run.ascent, run.descent, run.elapsedS.toDouble(), "active") }
        }
        publish(now)
    }

    /**
     * `RecordingService.checkBatterySaver`, called by its receiver when the phone's saver changes ([powerSave],
     * [mode] = `locationPowerSaveMode`, null below API 28): said once per tour, loud, nothing else changes.
     */
    fun batterySaverChanged(powerSave: Boolean, mode: Int?) {
        if (!running) return
        if (!LocationSaverMode.warnDuringTour(powerSave, mode, run.saverWarned)) return
        run.saverWarned = true
        Problems.warn("recording", "Battery saver came on during the tour and stops GPS with the screen off – user told")
        saverNotices++
    }

    /** `RecordingService.checkArrived` (in Navigate; the line's length not cached): the arrival once per line. */
    private fun checkArrived(pos: GeoPoint, accuracyM: Double) {
        val n = nav?.navigator ?: return
        if (!running || !GuidanceGate.arrivalCheck(run.arrived, run.paused, planQuiet = false)) return
        val st = n.state
        if (st.line.size < 2) return
        val remaining = (Geo.length(st.line) - (st.shownAlongM ?: st.progressM)).coerceAtLeast(0.0)
        if (!run.arrivalGate.onFix(pos, st.line.last(), remaining, accuracyM, openStops = n.session.openStops)) return
        run.arrived = true
        run.arrivedToken = n.session.token
        publish(clock())
    }

    /** The process dies: no state any more, nothing finished, what waited in memory is gone – the row stays "active". */
    fun kill() { tourId = -1; queue = PointQueue(queueMax); _state.value = null }

    /** Standing still for the voice and the off-route checks (`RecordingService.standingStill`, no motion sensor). */
    fun standingStill(): Boolean = !moveWatchMoving(clock())

    private fun gpsLost(now: Long) = now - run.lastGoodFixElapsed > RestWatch.lostAfterMs(false)

    private fun moveWatchMoving(now: Long) =
        run.moveWatch.moving(if (gpsLost(now) && run.lastGoodFixElapsed > 0) run.lastGoodFixElapsed else now)

    /** `RecordingService.gpsQuiet`: [com.onpath.app.recording.NoGpsShow.line] without a motion sensor is not the warning. */
    private fun gpsQuiet(now: Long): Boolean {
        val shortGap = now - run.lastGoodFixElapsed < VoiceGuide.GPS_LOST_MS
        return shortGap || !moveWatchMoving(now)
    }

    /** `RecordingService.writePoints`: the waiting points in order; false = some still wait (said once, loud – [safe]). */
    private fun writePoints(withNewPoint: Boolean = false): Boolean {
        val waited = queue.size
        val ok = safe("point") { queue.flush { tours.appendPoint(it.tourId, it.seq, it.seg, it.point, it.accuracyM) }?.let { throw it } }
        val late = if (withNewPoint) waited - 1 else waited
        if (ok) queue.lateLine(late)?.let { Problems.info("recording", it) }
        if (ok && run.saveFailed) { run.saveFailed = false; saveFailNoticeUp = false }
        if (queue.dropped > 0 && queue.dropped % 1000 == 1) {
            Problems.error("recording", "Storage failed for hours: ${queue.dropped} oldest unwritten point(s) given up")
        }
        return ok
    }

    /** `RecordingService.safeDb`: a failed write is logged, the notification once per failure, never thrown. */
    private inline fun safe(what: String, block: () -> Unit): Boolean = try {
        block(); true
    } catch (e: Exception) {
        Problems.error("db", "Recording: database write failed ($what)", e)
        if (!run.saveFailed) { run.saveFailed = true; saveFailNotices++; saveFailNoticeUp = true }
        false
    }

    private fun publish(now: Long) {
        if (!running) return
        val n = nav?.navigator
        _state.value = RecState(tourId = tourId, paused = run.paused, distanceM = run.distance, ascentM = run.ascent,
            descentM = run.descent, elapsedS = run.elapsedS, gpsLost = gpsLost(now), offRouteM = n?.state?.offM,
            offRouteAlert = n?.prompt != null, followName = null, track = run.track.snapshot(), nav = n?.state,
            saveFailed = run.saveFailed, hadGpsFix = run.lastGoodFixElapsed > 0, gpsQuiet = gpsQuiet(now),
            arrived = run.arrived, pausedSinceMs = run.pausedSinceMs)
    }
}
