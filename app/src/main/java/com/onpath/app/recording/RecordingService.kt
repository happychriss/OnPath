package com.onpath.app.recording

import com.onpath.strings.*

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.onpath.app.MainActivity
import com.onpath.app.R
import com.onpath.app.data.Format
import com.onpath.app.data.Geo
import com.onpath.app.data.Prefs
import com.onpath.app.data.TourDb
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.data.DataFiles
import com.onpath.app.debug.Problems
import com.onpath.app.navigation.NavKind
import com.onpath.app.navigation.NavPrompt
import com.onpath.app.navigation.NavSession
import com.onpath.app.navigation.NavSessionStore
import com.onpath.app.navigation.NavState
import com.onpath.app.navigation.NavText
import com.onpath.app.navigation.Navigator
import com.onpath.app.navigation.OffRouteDetector
import com.onpath.app.navigation.Rerouter
import com.onpath.app.routing.EngineProvider
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.RouteCancel
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.routing.StraightLineEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Live recording state for the UI. */
data class RecState(
    val tourId: Long,
    val paused: Boolean,
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val elapsedS: Long,
    val gpsLost: Boolean,
    /** Distance to the followed line, null when nothing is followed or no fix. */
    val offRouteM: Double?,
    val offRouteAlert: Boolean,
    val followName: String?,
    /** Segments of the recorded track (new segment after pause or GPS loss). */
    val track: List<List<GeoPoint>>,
    /** Navigation (followed line, re-routing question, grey original line); null for free recording. */
    val nav: NavState? = null,
    /** Moving time, breaks, to go and arrival (docs/plan-time-estimate.md); null before the first tick. */
    val time: com.onpath.app.time.TripTime? = null,
    /** The last track point could not be written (storage full, database broken): the UI must say so. */
    val saveFailed: Boolean = false,
)

/**
 * Foreground service that records the track. Every accepted point goes straight into
 * SQLite, so nothing is lost if the process dies (R-07). Also owns navigation: off-route detection and the
 * re-routing question ([Navigator]), so it works with the screen off and the phone in the pocket (F-NAV-04).
 * Routing runs on its own single thread; a newer request cancels the older one. Never blocks recording.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var db: TourDb
    private lateinit var tracker: LocationTracker
    private var wakeLock: PowerManager.WakeLock? = null

    private var tourId = -1L
    private var seq = 0
    private var seg = 0
    private var paused = false
    private var distance = 0.0
    private var ascent = 0.0
    private var descent = 0.0
    private var eleRef: Double? = null
    private var elapsedS = 0L
    private var last: TrackPoint? = null
    private var lastGoodFixElapsed = 0L
    /** Fixes dropped for accuracy since the last good one: says why a GPS gap had no usable fix (field 2026-10-22). */
    private val gapWatch = PointFilter.GapWatch()
    private val track =mutableListOf<MutableList<GeoPoint>>()
    private var trackSeg = -1
    /** Immutable copy of [track] for [RecState], rebuilt only when a point was added (not twice a second). */
    private var trackSnapshot: List<List<GeoPoint>> = emptyList()
    private var snapshotDirty = true
    /** A database write failed; cleared by the next successful point write. */
    private var saveFailed = false

    private var follow: Pair<String, List<GeoPoint>>? = null
    /** Navigation session + off-route flow; null for free recording. */
    private var navigator: Navigator? = null
    /** Vibration before junctions of the followed line + the next junction for the lock screen (owner 2026-10-22). */
    private val junctions = com.onpath.app.navigation.JunctionCues()
    private val sessionStore by lazy { NavSessionStore(File(filesDir, NavSession.FILE)) }
    private val routeExec = Executors.newSingleThreadExecutor()
    private val routeDispatcher = routeExec.asCoroutineDispatcher()
    private var engine: RoutingEngine? = null
    private var rerouteJob: Job? = null
    private var rerouteCancel: RouteCancel? = null
    /** What the off-route notification shows now (null = none), to update it only on changes. */
    private var alertKey: String? = null
    private var alertPosted = 0L
    /** Off-route settings, re-read when the app sends ACTION_SETTINGS / ACTION_FOLLOW_CHANGED. */
    private var settings: com.onpath.app.data.AppSettings? = null
    /** False while the app is in Plan mode: no off-route warnings then (recording goes on). */
    private var navigating = true
    private var lastNotify = 0L
    /** Moving time, breaks and pace learning of this tour (persisted in [TRIP_FILE], survives a restart). */
    private var trip = com.onpath.app.time.TripPace()
    /** Activity of the recording (the navigated session's activity wins for the time model). */
    private var recActivity = com.onpath.app.routing.Activity.HIKE
    private var tripSaved = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        db = TourDb.get(this)
        tracker = LocationTracker(this, 1000L, useNetwork = false) // network fixes were never recorded (battery audit)
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val control = intent?.action in setOf(ACTION_PAUSE, ACTION_RESUME, ACTION_STOP, ACTION_FOLLOW_CHANGED, ACTION_NAVIGATING,
            ACTION_SETTINGS, ACTION_TAKE_ROUTE, ACTION_KEEP_ROUTE, ACTION_CHECK_NOW, ACTION_PROPOSE_SWITCH)
        if (control && tourId < 0) { stopSelf(startId); return START_NOT_STICKY } // nothing is recording
        when (intent?.action) {
            ACTION_PAUSE -> { setPaused(true); return START_STICKY }
            ACTION_RESUME -> { setPaused(false); return START_STICKY }
            ACTION_STOP -> { finish(); return START_NOT_STICKY }
            ACTION_NAVIGATING -> {
                // Plan mode: no new questions; a pending one stays (owner: nothing disappears by itself)
                navigating = intent.getBooleanExtra(EXTRA_ON, true)
                navigator?.let { handle(it.setNavigating(navigating)) } ?: publish()
                return START_STICKY
            }
            ACTION_FOLLOW_CHANGED -> {
                settings = null
                loadFollow()
                if (intent.getBooleanExtra(EXTRA_CHECK_NOW, false)) navigator?.checkNow()
                publish(); return START_STICKY
            }
            ACTION_CHECK_NOW -> { navigator?.checkNow(); return START_STICKY }
            ACTION_SETTINGS -> {
                settings = null; applySettings(); trip.setPrior(priorFor(timeActivity())); updateAlert(); publish(); return START_STICKY
            }
            ACTION_TAKE_ROUTE -> {
                navigator?.let { handle(it.take(intent.getIntExtra(EXTRA_QUESTION, -1))) }
                return START_STICKY
            }
            ACTION_KEEP_ROUTE -> {
                navigator?.let { handle(it.keep(intent.getIntExtra(EXTRA_QUESTION, -1))) }
                return START_STICKY
            }
            ACTION_PROPOSE_SWITCH -> {
                // activity check sheet: the app routed the rest for another activity and wrote it to a file
                val f = File(filesDir, com.onpath.app.navigation.ActivityCheck.PROPOSAL_FILE)
                val proposed = NavSessionStore(f).load()
                f.delete()
                if (proposed != null) navigator?.let { handle(it.proposeSwitch(proposed, intent.getDoubleExtra(EXTRA_TIME_S, 0.0))) }
                return START_STICKY
            }
        }
        // Already recording: every startForegroundService() must be answered with startForeground(), or the
        // system kills the process ("did not call startForeground") – a double tap on Start must not do that.
        if (tourId >= 0) { goForeground(); return START_STICKY }

        // ACTION_START, or a sticky restart by the system (null intent): continue an active recording.
        val continueId = intent?.getLongExtra(EXTRA_CONTINUE_ID, -1L) ?: -1L
        val existing = try {
            if (continueId >= 0) db.getTour(continueId) else if (intent == null) db.findActiveRecording() else null
        } catch (e: Exception) {
            failLoud(Txt.s(Res.string.nav_rec_fail_read), "Recording could not start: your saved tracks cannot be read.", e)
            return START_NOT_STICKY
        }
        if (intent == null && existing == null) { stopSelf(); return START_NOT_STICKY }

        // Nothing silent (R-01): without precise location or GPS the user is told, not left "Searching GPS…" for hours.
        if (!tracker.hasFinePermission()) {
            failLoud(Txt.s(Res.string.nav_rec_fail_permission), "Recording stopped: OnPath needs precise location. Allow it in Settings → Apps → OnPath → Permissions.")
            return START_NOT_STICKY
        }
        if (!tracker.hasGps()) { failLoud(Txt.s(Res.string.nav_rec_fail_no_gps), "Recording stopped: this device has no GPS."); return START_NOT_STICKY }
        if (!goForeground()) {
            failLoud(Txt.s(Res.string.nav_rec_fail_blocked), "Recording could not start – Android blocked it. Open OnPath and try again.")
            return START_NOT_STICKY
        }

        try {
            if (existing != null) {
                tourId = existing.id
                distance = existing.distanceM; ascent = existing.ascentM; descent = existing.descentM
                elapsedS = existing.durationS.toLong()
                val (nextSeq, lastSeg) = db.recordingCursor(tourId)
                seq = nextSeq; seg = lastSeg + 1 // resume in a new segment, no straight jump
                val segs = db.loadSegments(tourId)
                segs.forEach { s -> track += s.map { it.toGeo() }.toMutableList() }
                trackSeg = lastSeg
                eleRef = segs.lastOrNull()?.lastOrNull()?.ele
                last = null
                recActivity = activityOf(existing.activity)
                loadTrip()
            } else {
                val name = com.onpath.app.rides.RideApply.dateName(System.currentTimeMillis())
                // the tour keeps the T level it was recorded at (was a fixed 3 until 2026-10-26: reopening a T2 hike set
                // the user's level to T3, and its GPX said "mountaineering")
                val sac = newTourSac(intent?.getIntExtra(EXTRA_SAC, 0) ?: 0, Prefs(this).sac)
                tourId = db.insertTour(TourKind.RECORDED, name, intent?.getStringExtra(EXTRA_ACTIVITY), sac,
                    0.0, 0.0, 0.0, 0.0, emptyList(), state = "active")
                recActivity = activityOf(intent?.getStringExtra(EXTRA_ACTIVITY))
                trip = com.onpath.app.time.TripPace(priorFor(recActivity))
                saveTrip()
            }
        } catch (e: Exception) {
            tourId = -1
            failLoud(Txt.s(Res.string.nav_rec_fail_storage), "Recording could not start: the track cannot be saved (phone storage full or damaged).", e)
            return START_NOT_STICKY
        }
        snapshotDirty = true
        safeDb("stats") { db.updateStats(tourId, distance, ascent, descent, elapsedS.toDouble(), "active") }
        safeDb("close older") { db.finishOtherActive(tourId) }
        loadFollow()
        // T5 in the background (owner 2026-10-22): map areas ahead load also with the screen off; joined after the tour
        try { com.onpath.app.cells.AppCells.get(this).apply { tourInBackground = true; holdUpdates = true } } catch (e: Exception) {
            Problems.warn("cells", "Map loading during the tour not prepared; the map ahead loads when the app is open", e)
        }
        acquireWakeLock()
        restWatch.reset(); tracker.setInterval(RestWatch.NORMAL_MS) // never start a tour at the standing-still rate
        tracker.onFix = { onFix(it) }
        tracker.start()
        startTicker()
        publish()
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        true
    } catch (e: Exception) {
        Problems.error("recording", "Foreground service could not start (startForeground refused)", e)
        false
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OnPath:recording").apply {
                setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Problems.warn("recording", "Wake lock could not be taken; recording may pause while the screen is off", e)
        }
    }

    private fun startTicker() {
        scope.launch {
            var lastTick = SystemClock.elapsedRealtime()
            var lastStats = lastTick
            var pausedBefore = paused
            while (isActive) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                // real time since the last tick: a tick delayed by Doze or a busy phone must not lose minutes.
                // Not across a pause: without the wake lock a paused tick can come late (resume counts from here).
                if (!paused && !pausedBefore) elapsedS += ((now - lastTick) / 1000.0).toLong().coerceIn(1, 600)
                pausedBefore = paused
                lastTick = now
                // the lock times out after 12 h: long days go on; paused = slow GPS (10 s), no lock (battery audit, 0.7.5)
                if (!paused && wakeLock?.isHeld != true) acquireWakeLock()
                if (gpsLost()) navigator?.onGpsLost() // no alarms without GPS (R-04)
                if (now - lastStats >= 10_000) {
                    lastStats = now
                    safeDb("stats") { db.updateStats(tourId, distance, ascent, descent, elapsedS.toDouble(), "active") }
                }
                try { trip.tick(elapsedS.toDouble(), System.currentTimeMillis()) } catch (e: Exception) {
                    Problems.error("time", "Trip pace tick failed; times shown may be wrong", e)
                }
                if (now - tripSaved > 30_000) saveTrip()
                publish()
            }
        }
    }

    private fun gpsLost() = SystemClock.elapsedRealtime() - lastGoodFixElapsed > RestWatch.lostAfterMs(restWatch.resting)

    /** Slower GPS while standing still (owner decision 10, battery); the paused rate is [PauseWatch]'s. */
    private val restWatch = RestWatch()

    /** Feeds [restWatch] with a usable fix of a running tour and applies the rate change; never costs the track. */
    private fun restRate(loc: Location) {
        try {
            val change = restWatch.onFix(loc.elapsedRealtimeNanos / 1_000_000L, loc.latitude, loc.longitude, loc.accuracy,
                if (loc.hasSpeed()) loc.speed else null) ?: return
            tracker.setInterval(restWatch.intervalMs)
            Problems.trace("gps", when (change) {
                RestWatch.Change.REST -> "Standing still for ${RestWatch.REST_AFTER_MS / 1000} s: GPS every ${RestWatch.REST_MS / 1000} s"
                RestWatch.Change.MOVING -> "Moving again: GPS every second"
            })
        } catch (e: Exception) {
            Problems.warn("gps", "GPS rate for standing still not changed; back to every second", e)
            restWatch.reset()
            tracker.setInterval(RestWatch.NORMAL_MS)
        }
    }

    private fun onFix(loc: Location) {
        if (tourId >= 0 && paused) {
            if (pauseWatch.onFix(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toDouble() else Double.NaN)) {
                notifyWalkingWhilePaused()
            }
            return
        }
        if (tourId < 0) return
        if (loc.provider != LocationManager.GPS_PROVIDER) return
        if (!PointFilter.accuracyOk(loc.hasAccuracy(), loc.accuracy)) { gapWatch.onDropped(loc.hasAccuracy(), loc.accuracy); return }
        // before any filtering below: the first moving fix after a rest switches back to every second (and is stored)
        restRate(loc)

        val nowEl = SystemClock.elapsedRealtime()
        val wasLost = lastGoodFixElapsed > 0 && nowEl - lastGoodFixElapsed > GAP_MS
        val lostSinceElapsed = lastGoodFixElapsed
        lastGoodFixElapsed = nowEl
        val gapWhy = gapWatch.why()
        gapWatch.reset()
        val p = TrackPoint(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else null, loc.time)

        // navigation must never cost the track: a failure here is reported, the point below is still stored (R-07)
        try { checkOffRoute(p, loc.accuracy.toDouble(), nowEl) } catch (e: Exception) {
            Problems.error("navigation", "Off-route check failed on a GPS fix; the track is still recorded", e)
        }
        try { junctionCue() } catch (e: Exception) {
            Problems.warn("navigation", "Junction check failed on a GPS fix; no junction vibration this time", e)
        }
        try { mapsAhead(p.toGeo()) } catch (e: Exception) {
            Problems.warn("cells", "Map ahead not requested on a GPS fix; asked again further on", e)
        }
        try {
            // position on the line (null = not on the route) and whether it may be learned (forwards, on the line)
            val det = navigator?.detector
            trip.onFix(elapsedS.toDouble(), System.currentTimeMillis(), p.lat, p.lon, det?.shownAlongM, det?.learnable == true)
        } catch (e: Exception) { Problems.error("time", "Trip pace update on a GPS fix failed; times shown may be wrong", e) }

        val prev = last
        // a short GPS gap (forest, gorge) is bridged: one connected line, the distance counts; a long / fast one splits
        val bridged = wasLost && prev != null && PointFilter.bridgesGap(prev, p)
        if (wasLost && prev != null) Problems.trace("gps", "No usable GPS fix for ${(nowEl - lostSinceElapsed) / 1000} s " +
            "($gapWhy); " +
            "${Geo.distance(prev.lat, prev.lon, p.lat, p.lon).toInt()} m since the last point – " +
            if (bridged) "line kept connected" else "new track piece")
        val (verdict, moved) = PointFilter.judge(prev, p, loc.accuracy.toDouble(), continuous = !wasLost || bridged)
        if (verdict != PointFilter.Verdict.ACCEPT) return             // GPS jump or standing still
        distance += moved
        if (wasLost && prev != null && !bridged) seg++                // long GPS gap: new segment
        p.ele?.let { e ->
            val r = eleRef
            if (r == null) eleRef = e
            else if (e - r >= 5) { ascent += e - r; eleRef = e }
            else if (r - e >= 5) { descent += r - e; eleRef = e }
        }
        // The point goes to SQLite first (R-07). seq advances only when it is really stored.
        if (safeDb("point") { db.appendPoint(tourId, seq, seg, p, loc.accuracy) }) {
            seq++
            if (saveFailed) { saveFailed = false; nm().cancel(SAVE_FAIL_ID) }
        }
        last = p
        if (track.isEmpty() || trackSeg != seg) { track += mutableListOf<GeoPoint>(); trackSeg = seg }
        track.last() += p.toGeo()
        snapshotDirty = true
        publish()
    }

    /**
     * Runs a database write; a failure (storage full, corrupt file) is logged, shown as a loud notification once
     * and reported in [RecState.saveFailed] – never a crash, never silent (R-01, R-07). Returns true on success.
     */
    private inline fun safeDb(what: String, block: () -> Unit): Boolean = try {
        block(); true
    } catch (e: Exception) {
        Problems.error("db", "Recording: database write failed ($what)", e)
        if (!saveFailed) { saveFailed = true; notifySaveFailed(); vibrate() }
        false
    }

    private fun notifySaveFailed() {
        try {
            val text = Txt.s(Res.string.nav_rec_save_failed)
            nm().notify(SAVE_FAIL_ID, NotificationCompat.Builder(this, CHANNEL_NAV)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(Txt.s(Res.string.nav_rec_save_failed_title))
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setOngoing(true)
                .setContentIntent(openAppIntent())
                .build())
        } catch (e: Exception) {
            Problems.warn("recording", "\"Recording cannot be saved\" notification could not be shown", e)
        }
    }

    /**
     * The service cannot (or can no longer) record: say so in a notification that stays, then stop. The tour keeps
     * its "active" state, so the app offers Continue / Save next time. Never a silent stopSelf().
     * [text] = what the user reads (app language), [log] = the English line for the problem log.
     */
    private fun failLoud(text: String, log: String, cause: Throwable? = null) {
        Problems.error("recording", log, cause)
        try {
            nm().notify(SAVE_FAIL_ID, NotificationCompat.Builder(this, CHANNEL_NAV)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(Txt.s(Res.string.nav_rec_stopped_title))
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build())
        } catch (e: Exception) {
            Problems.warn("recording", "\"Recording stopped\" notification could not be shown", e)
        }
        vibrate()
        _startFailed.value = text
        stopSelf()
    }

    /**
     * (Re)loads what is navigated: `nav-session.json` written by the app (or by a re-route), else an older plain
     * `follow.json` (followed as a track without stops). A running re-route is cancelled.
     */
    private fun loadFollow() {
        cancelReroute()
        follow = Prefs(this).loadFollow()
        val line = follow?.second?.takeIf { it.size >= 2 }
        val stored = sessionStore.load()
        val session = when {
            line == null -> null
            stored != null && stored.active.size == line.size -> stored
            else -> {
                val prefs = Prefs(this)
                NavSession(NavKind.TRACK, follow?.first ?: Txt.s(Res.string.nav_route_fallback), prefs.activity, prefs.sac, line)
            }
        }
        val old = navigator
        // the walking direction carries over to the new line (plan edited mid-walk: the way back of a round trip)
        navigator = session?.let { Navigator(it).apply { setNavigating(navigating); old?.let { o -> detector.adoptHeading(o.detector) } } }
        applySettings()
        timeLine()
        alertKey = null; nm().cancel(ALERT_ID)
    }

    private fun applySettings() {
        val cfg = settings ?: Prefs(this).settings.also { settings = it }
        navigator?.let { it.offRouteM = cfg.offRouteM.toDouble(); it.enabled = cfg.offRouteWarning }
    }

    private fun checkOffRoute(p: TrackPoint, accuracyM: Double, nowEl: Long) {
        val nav = navigator ?: return
        handle(nav.onFix(OffRouteDetector.Fix(p.lat, p.lon, accuracyM, nowEl)), publishNow = false)
    }

    /** Carries out what the navigator asks for. */
    private fun handle(step: Navigator.Step, publishNow: Boolean = true) {
        val nav = navigator ?: return
        if (step.vibrate && (settings ?: Prefs(this).settings).offRouteVibrate) vibrate()
        if (step.cancelJob) cancelReroute()
        step.job?.let { startReroute(it) }
        if (step.lineChanged) {
            // written before the new line is shown (crash recovery R-07)
            sessionStore.save(nav.session)
            Prefs(this).saveFollow(follow?.first ?: nav.session.name, nav.session.active)
            follow = (follow?.first ?: nav.session.name) to nav.session.active
            timeLine()
        } else if (step.sessionChanged) sessionStore.save(nav.session)
        if (step.activityChanged && tourId >= 0) {
            // the tour is now recorded as the new activity; the app follows via RecState.nav.session
            try { db.updateActivity(tourId, nav.session.activity.name, nav.session.sac) } catch (e: Exception) {
                Problems.error("db", "Activity switch could not be saved to the recorded tour", e)
            }
            Prefs(this).activity = nav.session.activity
        }
        updateAlert()
        if (publishNow || step.lineChanged) publish(force = step.lineChanged)
    }

    private fun startReroute(job: Navigator.Job) {
        cancelReroute()
        val cancel = RouteCancel()
        rerouteCancel = cancel
        rerouteJob = scope.launch {
            val out = withContext(routeDispatcher) {
                try {
                    val eng = engine ?: EngineProvider.create(this@RecordingService, DataFiles(this@RecordingService).segmentsDir)
                        .also { if (it !is StraightLineEngine) engine = it } // retry later if it failed
                    Rerouter(eng).reroute(job.request, cancel)
                } catch (t: Throwable) {
                    Problems.error("navigation", "Re-route threw unexpectedly; reported as unavailable", t)
                    Rerouter.Failed(Rerouter.Reason.UNAVAILABLE, "Re-route failed unexpectedly (details in the problem log)", 0)
                }
            }
            Log.i("OnPath", "reroute #${job.id}: " + when (out) {
                is Rerouter.Found -> "found in ${out.tookMs} ms (${out.tried} tried), leg ${out.legM.toInt()} m, extra ${out.extraM.toInt()} m"
                is Rerouter.Failed -> "failed in ${out.tookMs} ms: ${out.reason} ${out.detail}"
            })
            if (rerouteCancel === cancel) { rerouteCancel = null; rerouteJob = null }
            navigator?.let { handle(it.onResult(job.id, out)) }
        }
    }

    private fun cancelReroute() {
        rerouteCancel?.cancel(); rerouteCancel = null
        rerouteJob?.cancel(); rerouteJob = null
    }

    private var t5LastAt: GeoPoint? = null
    private val tourCells by lazy { com.onpath.app.cells.CellTriggers(com.onpath.app.cells.AppCells.get(this)) }

    /**
     * T5 in the background: every [T5_STEP_M] on a tour, the map areas within ~5 km of the edge of the detailed map
     * and the routing data around the position (re-routing past the edge). Quiet; map areas are joined after the tour.
     */
    private fun mapsAhead(g: GeoPoint) {
        val last = t5LastAt
        if (last != null && com.onpath.app.data.Geo.distance(last, g) < T5_STEP_M) return
        t5LastAt = g
        val asked = tourCells.onTour(g)
        if (asked.isNotEmpty()) Problems.info("cells", "Tour near the edge of the map: asked for ${asked.joinToString()}")
        com.onpath.app.data.AppRouting.get(this).ensure(com.onpath.app.data.RoutingTiles.forPoints(listOf(g), T5_ROUTING_MARGIN_M))
    }

    /** Before a junction of the followed line: 2 short pulses = left, 3 = right ([JunctionCues]). */
    private fun junctionCue() {
        val st = navigator?.state ?: return
        junctions.aheadM = if (navigator?.session?.activity == com.onpath.app.routing.Activity.HIKE)
            com.onpath.app.navigation.JunctionCues.HIKE_AHEAD_M else com.onpath.app.navigation.JunctionCues.BIKE_AHEAD_M
        val onRoute = st.prompt == null && st.shownAlongM != null
        val cue = junctions.update(st.line, st.version, st.shownAlongM, onRoute) ?: return
        if ((settings ?: Prefs(this).settings).junctionVibrate) {
            vibrate(com.onpath.app.navigation.JunctionCues.pattern(cue.side), "junction")
            Problems.trace("navigation", "Junction cue: ${cue.name}")
        }
    }

    private fun vibrate(pattern: LongArray = OFF_ROUTE_VIBRATION, what: String = "alert") {
        try {
            val vib = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            val effect = VibrationEffect.createWaveform(pattern, -1)
            // As an alarm: Android drops vibrations without a usage while the app is not on screen (phone in the
            // pocket – field test 2026-10-20: the warning only blinked). Alarm vibrations also pass the touch setting.
            if (Build.VERSION.SDK_INT >= 33) {
                vib.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(effect, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
        } catch (e: Exception) {
            Problems.warn("sensor", "Vibration failed ($what); given without vibration", e)
        }
    }

    /**
     * The off-route notification carries the same question as the screen ("Take new route" / "Keep original").
     * Ongoing: it stays until answered or until the user is back on the route. Posted with sound once per
     * off-route event (our own vibration comes with it), then updated silently.
     */
    private fun updateAlert() {
        val nav = navigator
        val pr = nav?.prompt
        if (pr == null) {
            if (alertKey != null) { alertKey = null; nm().cancel(ALERT_ID) }
            return
        }
        val hint = NavText.toRoute(nav.toRoute)
        val key = when (pr) { is NavPrompt.Question -> "q${pr.id}"; else -> pr.javaClass.simpleName }
        val now = SystemClock.elapsedRealtime()
        if (key == alertKey && now - alertPosted < 15_000) return
        alertKey = key; alertPosted = now
        try {
            val text = when (pr) {
                is NavPrompt.Question -> NavText.question(pr)
                else -> hint ?: follow?.first?.let { Txt.s(Res.string.nav_back_to, it) } ?: Txt.s(Res.string.nav_back_to_route)
            }
            val b = NotificationCompat.Builder(this, CHANNEL_NAV)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(NavText.title(pr))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // answerable on the lock screen
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent())
            // an activity switch was asked for on the screen: no sound (the user is looking), the question stays
            if (pr is NavPrompt.Question && pr.switch != null) b.setSilent(true)
            if (pr is NavPrompt.Question) {
                b.addAction(0, NavText.TAKE, actionIntent(ACTION_TAKE_ROUTE, 10) { putExtra(EXTRA_QUESTION, pr.id) })
                b.addAction(0, NavText.KEEP, actionIntent(ACTION_KEEP_ROUTE, 11) { putExtra(EXTRA_QUESTION, pr.id) })
            }
            nm().notify(ALERT_ID, b.build())
        } catch (e: SecurityException) {
            Problems.warn("navigation", "Off-route notification refused (notification permission?); question only shown in the app", e)
        }
    }

    private val pauseWatch = PauseWatch()

    /** Paused but walking away: vibrate and ask (Resume in the notification); never resumes by itself. */
    private fun notifyWalkingWhilePaused() {
        vibrate()
        try {
            val b = NotificationCompat.Builder(this, CHANNEL_NAV)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(Txt.s(Res.string.nav_rec_paused_title))
                .setContentText(Txt.s(Res.string.nav_rec_paused_moving))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent())
                .addAction(0, Txt.s(Res.string.common_resume), actionIntent(ACTION_RESUME, 2))
            nm().notify(PAUSE_ALERT_ID, b.build())
        } catch (e: SecurityException) {
            Problems.warn("recording", "Paused-but-moving reminder refused (notification permission?)", e)
        }
        Problems.info("recording", "Moved ${PauseWatch.MOVE_M.toInt()} m while paused – asked to resume")
    }

    private fun setPaused(p: Boolean) {
        if (tourId < 0) return
        if (paused == p) return
        paused = p
        if (!p) { seg++; last = null } // resume = new segment, distance not bridged
        // Battery: a pause records nothing – GPS runs slowly (never off: owner 2026-10-20, walking on with the pause
        // still on is noticed by PauseWatch), the wake lock rests until Resume.
        pauseWatch.reset()
        restWatch.reset() // the pause has its own rate; after Resume the standing-still minute starts anew
        nm().cancel(PAUSE_ALERT_ID)
        if (p) {
            tracker.setInterval(PauseWatch.GPS_MS)
            try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { Problems.warn("recording", "Wake lock not released on pause", e) }
        } else {
            tracker.setInterval(RestWatch.NORMAL_MS)
            acquireWakeLock()
        }
        trip.onPause() // a pause is not a break (its time is not in elapsedS anyway)
        saveTrip()
        navigator?.onGpsLost() // no fixes while paused: timers start from zero again
        safeDb("stats") { db.updateStats(tourId, distance, ascent, descent, elapsedS.toDouble(), "active") }
        publish(force = true)
    }

    private fun finish() {
        try { com.onpath.app.cells.AppCells.get(this).apply { tourInBackground = false; holdUpdates = false } } catch (e: Exception) {
            Problems.warn("cells", "Map areas loaded during the tour not released; they join at the next app start", e)
        }
        if (tourId >= 0) {
            safeDb("finish") { db.updateStats(tourId, distance, ascent, descent, elapsedS.toDouble(), "done") }
            // bus / car / lift (docs/design/vehicle-detection.md): the choice made at End tour is applied now – or the
            // rides are only marked. The points stay as recorded; never throws (failures reported, tour as recorded).
            val parts = com.onpath.app.rides.RideApply.finish(com.onpath.app.rides.TourDbRides(db), tourId,
                com.onpath.app.rides.TourStats(distance, ascent, descent, elapsedS.toDouble()), System.currentTimeMillis(),
                timeActivity())
            finishedParts = parts
            // GPX copy in Documents/OnPath (backup/Backups): on its own thread, never holds up the end of the tour
            com.onpath.app.backup.Backups.tourChanged(this, tourId)
            parts.forEach { com.onpath.app.backup.Backups.tourChanged(this, it) }
            try {
                com.onpath.app.time.TourTimesStore(File(filesDir, com.onpath.app.time.TourTimesStore.FILE))
                    .put(tourId, tourTimes())
                // learning per tour only (owner 2026-10-22): this tour's pace ends with it
                File(filesDir, TRIP_FILE).delete()
            } catch (e: Exception) { Problems.error("time", "Tour times could not be saved at tour end", e) }
            _finished.value = tourId
        }
        tourId = -1
        tracker.stop()
        restWatch.reset()
        tracker.setInterval(RestWatch.NORMAL_MS) // a tour ended while paused / resting must not start the next one slowly
        cancelReroute()
        scope.cancel()
        nm().cancel(ALERT_ID)
        nm().cancel(PAUSE_ALERT_ID)
        _state.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        wakeLock?.let { if (it.isHeld) it.release() }
        stopSelf()
    }

    override fun onDestroy() {
        // Process stays consistent: points are already in the DB; state stays "active"
        // unless finish() ran, so the app offers Continue / Save next time.
        cancelReroute()
        scope.cancel()
        routeExec.shutdown()
        tracker.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        if (tourId >= 0) {
            safeDb("stats") { db.updateStats(tourId, distance, ascent, descent, elapsedS.toDouble(), "active") }
            saveTrip()
        }
        _state.value = null
        super.onDestroy()
    }

    private fun publish(force: Boolean = false) {
        if (tourId < 0) return
        if (snapshotDirty) { trackSnapshot = track.map { it.toList() }; snapshotDirty = false }
        _state.value = RecState(
            tourId, paused, distance, ascent, descent, elapsedS, gpsLost(),
            navigator?.state?.offM, navigator?.prompt != null, follow?.first, trackSnapshot,
            navigator?.state, tripTime(), saveFailed,
        )
        // Battery: the clock runs by itself in the notification (chronometer); re-post only when what it says
        // changes (status, shown distance), at most every 5 s, and once a minute to keep the clock in step.
        val now = SystemClock.elapsedRealtime()
        navText = navNotification()
        val key = notifStatus() + Format.distance(distance) + (follow?.first ?: "") + (navText?.let { it.title + it.text } ?: "")
        // following: what the lock screen shows (next junction, time to go) changes often – at most every 10 s
        val minGap = if (navText != null) 10_000 else 5000
        if (force || (now - lastNotify > minGap && key != lastNotifyKey) || now - lastNotify > 60_000) {
            lastNotify = now
            lastNotifyKey = key
            try { nm().notify(NOTIF_ID, buildNotification()) } catch (e: SecurityException) {
                Problems.warn("recording", "Recording notification update refused (notification permission?)", e)
            }
        }
    }

    // ---- time estimate (docs/plan-time-estimate.md) ----

    private fun tripTime(): com.onpath.app.time.TripTime = try {
        trip.snapshot().also { reportEstimate(it) }
    } catch (e: Exception) {
        Problems.error("time", "Trip time snapshot failed; showing elapsed time only", e)
        com.onpath.app.time.TripTime(elapsedS.toDouble(), 0.0)
    }

    private var estimatePaused: com.onpath.app.time.PauseReason? = null

    /** "Estimate paused" is never silent (owner rule 8): reported once per pause in Settings → Diagnostics. */
    private fun reportEstimate(t: com.onpath.app.time.TripTime) {
        val why = t.pausedBecause.takeIf { t.state == com.onpath.app.time.EstimateState.PAUSED }
        if (why == estimatePaused) return
        estimatePaused = why
        when (why) {
            null -> Unit
            com.onpath.app.time.PauseReason.NO_GPS ->
                Problems.info("time", "Arrival estimate paused: no GPS fix for 2 min while recording")
            com.onpath.app.time.PauseReason.NO_PROGRESS ->
                Problems.warn("time", "Arrival estimate paused: moving for 2 min but the progress along the route did not change")
        }
    }

    private fun tourTimes(): com.onpath.app.time.TourTimes = trip.tourTimes(elapsedS.toDouble())

    private fun activityOf(name: String?) =
        runCatching { com.onpath.app.routing.Activity.valueOf(name!!) }.getOrDefault(com.onpath.app.routing.Activity.HIKE)

    /** Activity the times are computed for: the navigated session's, else the recording's. */
    private fun timeActivity() = navigator?.session?.activity ?: recActivity

    /** The pace setting for [a] (same as the plan sheet; learning per tour only, nothing stored across tours). */
    private fun priorFor(a: com.onpath.app.routing.Activity): com.onpath.app.time.Factors = try {
        com.onpath.app.time.TimeEstimate.planFactors(a, settings ?: Prefs(this).settings)
    } catch (e: Exception) {
        Problems.warn("time", "Pace factors could not be computed; using neutral pace", e)
        com.onpath.app.time.Factors.ONE
    }

    /** The navigated line changed (start, re-route, back on the original): new times, learned factors stay. */
    private fun timeLine() {
        try {
            val nav = navigator
            trip.setPrior(priorFor(timeActivity()))
            // a new line: "not on route" until the position is matched on it (never "the whole line from its start")
            trip.setLine(nav?.session?.active?.let { com.onpath.app.time.RouteTimes.of(it, nav.session.activity) },
                nav?.detector?.shownAlongM)
        } catch (e: Exception) { Problems.error("time", "Trip times could not be set for the navigated line", e) }
    }

    /** Trip state of the running tour, written atomically (crash / restart keeps the break time). */
    private fun saveTrip() {
        if (tourId < 0) return
        tripSaved = SystemClock.elapsedRealtime()
        try {
            val o = org.json.JSONObject().put("tour", tourId).put("trip", trip.toJson())
            val f = File(filesDir, TRIP_FILE); val tmp = File(f.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) Problems.error("time", "Trip pace file could not be saved (rename failed)")
            }
        } catch (e: Exception) { Problems.error("time", "Trip pace file could not be saved (break time may be lost on restart)", e) }
    }

    private fun loadTrip() {
        val prior = priorFor(recActivity)
        trip = try {
            val o = org.json.JSONObject(File(filesDir, TRIP_FILE).readText())
            if (o.optLong("tour", -1) == tourId) com.onpath.app.time.TripPace.fromJson(o.optJSONObject("trip"), prior)
            else com.onpath.app.time.TripPace(prior)
        } catch (_: java.io.FileNotFoundException) {
            com.onpath.app.time.TripPace(prior) // expected: no trip file yet (first start or older tour)
        } catch (e: Exception) {
            Problems.warn("time", "Trip pace file unreadable; trip times start fresh", e)
            com.onpath.app.time.TripPace(prior)
        }
        trip.onPause() // the service was gone for a while: start a new stop / piece
    }

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(action: String, code: Int, extras: Intent.() -> Unit = {}): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, RecordingService::class.java).setAction(action).apply(extras),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private var lastNotifyKey = ""
    /** Lock-screen text while a route is followed; null = plain recording notification. */
    private var navText: com.onpath.app.navigation.NavNotification.Text? = null

    private fun navNotification(): com.onpath.app.navigation.NavNotification.Text? = try {
        val st = navigator?.state
        if (st == null || st.line.size < 2) null else {
            val along = st.shownAlongM
            val toGo = along?.let { (com.onpath.app.data.Geo.length(st.line) - it).coerceAtLeast(0.0) }
            val t = _state.value?.time
            val ok = t?.state == com.onpath.app.time.EstimateState.OK
            com.onpath.app.navigation.NavNotification.of(notifStatus(),
                junctions.nextAhead(st.line, st.version, along), toGo,
                t?.toGoS?.takeIf { ok }, t?.arrivalMs?.takeIf { ok },
                distance = { Format.distance(it) }, duration = { Format.duration(it) },
                clock = { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) })
        }
    } catch (e: Exception) {
        Problems.warn("recording", "Lock-screen route info not built; plain recording notification", e)
        null
    }

    /** The status word (app language); NavNotification compares it with nav_status_recording. */
    private fun notifStatus() = when {
        paused -> Txt.s(Res.string.nav_status_paused)
        tourId >= 0 && gpsLost() -> Txt.s(Res.string.nav_status_searching_gps)
        navigator?.prompt != null -> Txt.s(Res.string.nav_off_route)
        else -> Txt.s(Res.string.nav_status_recording)
    }

    private fun buildNotification(): Notification {
        val status = notifStatus()
        val b = NotificationCompat.Builder(this, CHANNEL_REC)
            .setSmallIcon(R.drawable.ic_notification)
            // running: distance + a system-run clock (no re-post every second); paused: the clock stands still
            .setContentTitle(if (paused) "${Format.distance(distance)} · ${Format.clock(elapsedS)}" else Format.distance(distance))
            .setShowWhen(!paused)
            .setUsesChronometer(!paused)
            .setWhen(System.currentTimeMillis() - elapsedS * 1000)
            .setContentText(follow?.let { Txt.s(Res.string.nav_notif_following, status, it.first) } ?: status)
            // readable on the lock screen (owner 2026-10-22); no button here ends a tour
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        navText?.let { n ->
            // following a route: next junction / distance to go as the title, time to go · arrival · distance below
            b.setContentTitle(n.title).setContentText(n.text).setUsesChronometer(false).setShowWhen(false)
                .setStyle(NotificationCompat.BigTextStyle().bigText(n.text + "\n" +
                    Txt.s(Res.string.nav_notif_recorded, Format.distance(distance), Format.clock(elapsedS))))
        }
        if (paused) b.addAction(0, Txt.s(Res.string.common_resume), actionIntent(ACTION_RESUME, 2))
        else b.addAction(0, Txt.s(Res.string.nav_pause), actionIntent(ACTION_PAUSE, 1))
        // No "Stop & save" here (owner 2026-10-20: ending a tour must be deliberate) – one tap on the lock screen or
        // in the pocket would end the recording. Ending is in the app, with a question first.
        return b.build()
    }

    companion object {
        /** T5 in the background: checked every this many metres on a tour; routing data within this margin. */
        const val T5_STEP_M = 300.0
        const val T5_ROUTING_MARGIN_M = 5_000.0
        const val ACTION_START = "com.onpath.app.START"
        const val ACTION_PAUSE = "com.onpath.app.PAUSE"
        const val ACTION_RESUME = "com.onpath.app.RESUME"
        const val ACTION_STOP = "com.onpath.app.STOP"
        const val ACTION_FOLLOW_CHANGED = "com.onpath.app.FOLLOW_CHANGED"
        /** App switched mode: [EXTRA_ON] true = Navigate (off-route checks on), false = Plan. */
        const val ACTION_NAVIGATING = "com.onpath.app.NAVIGATING"
        const val EXTRA_ON = "on"
        /** Off-route / re-routing settings changed (no new session). */
        const val ACTION_SETTINGS = "com.onpath.app.SETTINGS"
        /** Answer to the off-route question [EXTRA_QUESTION] (app button or notification action). */
        const val ACTION_TAKE_ROUTE = "com.onpath.app.TAKE_ROUTE"
        const val ACTION_KEEP_ROUTE = "com.onpath.app.KEEP_ROUTE"
        const val EXTRA_QUESTION = "question"
        /** Activity check: a proposed session for another activity is in ActivityCheck.PROPOSAL_FILE ([EXTRA_TIME_S] = its time). */
        const val ACTION_PROPOSE_SWITCH = "com.onpath.app.PROPOSE_SWITCH"
        const val EXTRA_TIME_S = "timeS"
        /** With ACTION_FOLLOW_CHANGED ("Continue navigation"): check the next fix at once and ask when off route. */
        const val EXTRA_CHECK_NOW = "checkNow"
        /** "Continue navigation" with the same session: check the next fix at once. */
        const val ACTION_CHECK_NOW = "com.onpath.app.CHECK_NOW"
        const val EXTRA_CONTINUE_ID = "continueId"
        const val EXTRA_ACTIVITY = "activity"
        /** T level (SAC 1..5) the tour is recorded at; missing = the stored one ([Prefs.sac]). */
        const val EXTRA_SAC = "sac"

        /** T level of a new recorded tour: the one sent with the start ([extra] 1..5), else the [stored] one. */
        fun newTourSac(extra: Int, stored: Int): Int =
            com.onpath.app.data.LastActivity.sac(extra.takeIf { it in 1..5 }, stored)

        const val CHANNEL_REC = "recording"
        /** Old off-route channel (vibrated by itself); deleted, see [CHANNEL_NAV]. */
        private const val CHANNEL_ALERT_OLD = "offroute"
        /** Off-route question: high importance with sound, no channel vibration (the service vibrates once itself). */
        const val CHANNEL_NAV = "offroute-question"
        val OFF_ROUTE_VIBRATION = longArrayOf(0, 400, 200, 400, 200, 600)
        private const val NOTIF_ID = 42
        private const val ALERT_ID = 43
        private const val PAUSE_ALERT_ID = 44
        /** "Recording cannot be saved" / "Recording stopped" (one slot: the newer problem wins). */
        private const val SAVE_FAIL_ID = 44

        const val MAX_ACCURACY_M = PointFilter.MAX_ACCURACY_M
        const val MAX_SPEED_MS = PointFilter.MAX_SPEED_MS
        const val GAP_MS = 30_000L          // no fix for 30 s -> new segment
        /** Trip state (breaks, pace learning) of the running tour. */
        const val TRIP_FILE = "trip-pace.json"
        
        private val _state = MutableStateFlow<RecState?>(null)
        val state: StateFlow<RecState?> = _state
        private val _finished = MutableStateFlow<Long?>(null)
        /** Id of the last tour stopped (UI shows a summary, then clears it). */
        val finished: MutableStateFlow<Long?> = _finished
        /** Tours split off the last stopped tour ("Split into two tours"); set before [finished]. */
        @Volatile var finishedParts: List<Long> = emptyList()
        private val _startFailed = MutableStateFlow<String?>(null)
        /** Why the service stopped by itself (plain text; the app shows it and clears it). */
        val startFailed: MutableStateFlow<String?> = _startFailed

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_REC, Txt.s(Res.string.nav_channel_recording), NotificationManager.IMPORTANCE_LOW).apply {
                    description = Txt.s(Res.string.nav_channel_recording_desc)
                }
            )
            runCatching { nm.deleteNotificationChannel(CHANNEL_ALERT_OLD) } // expected to be harmless: old channel may not exist
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_NAV, Txt.s(Res.string.nav_channel_off_route), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = Txt.s(Res.string.nav_channel_off_route_desc)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }

        fun send(context: Context, action: String, configure: Intent.() -> Unit = {}) {
            val i = Intent(context, RecordingService::class.java).setAction(action).apply(configure)
            try {
                if (action == ACTION_START) ContextCompat.startForegroundService(context, i)
                else context.startService(i)
            } catch (e: Exception) {
                Problems.error("recording", "Recording service command could not be delivered ($action)", e)
            }
        }
    }
}
