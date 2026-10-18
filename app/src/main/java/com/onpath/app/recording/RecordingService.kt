package com.onpath.app.recording

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private val track = mutableListOf<MutableList<GeoPoint>>()
    private var trackSeg = -1
    /** Immutable copy of [track] for [RecState], rebuilt only when a point was added (not twice a second). */
    private var trackSnapshot: List<List<GeoPoint>> = emptyList()
    private var snapshotDirty = true
    /** A database write failed; cleared by the next successful point write. */
    private var saveFailed = false

    private var follow: Pair<String, List<GeoPoint>>? = null
    /** Navigation session + off-route flow; null for free recording. */
    private var navigator: Navigator? = null
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
        tracker = LocationTracker(this, 1000L)
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
            Log.e("OnPath", "read tour", e)
            failLoud("Recording could not start: the tour database cannot be read.")
            return START_NOT_STICKY
        }
        if (intent == null && existing == null) { stopSelf(); return START_NOT_STICKY }

        // Nothing silent (R-01): without precise location or GPS the user is told, not left "Searching GPS…" for hours.
        if (!tracker.hasFinePermission()) {
            failLoud("Recording stopped: OnPath needs precise location. Allow it in Settings → Apps → OnPath → Permissions.")
            return START_NOT_STICKY
        }
        if (!tracker.hasGps()) { failLoud("Recording stopped: this device has no GPS."); return START_NOT_STICKY }
        if (!goForeground()) {
            failLoud("Recording could not start (Android did not allow the background service). Open OnPath and try again.")
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
                val name = "Tour " + SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date())
                tourId = db.insertTour(TourKind.RECORDED, name, intent?.getStringExtra(EXTRA_ACTIVITY), 3,
                    0.0, 0.0, 0.0, 0.0, emptyList(), state = "active")
                recActivity = activityOf(intent?.getStringExtra(EXTRA_ACTIVITY))
                trip = com.onpath.app.time.TripPace(priorFor(recActivity))
                saveTrip()
            }
        } catch (e: Exception) {
            Log.e("OnPath", "start recording", e)
            tourId = -1
            failLoud("Recording could not start: the tour cannot be saved (storage full or database broken).")
            return START_NOT_STICKY
        }
        snapshotDirty = true
        safeDb("stats") { db.updateStats(tourId, distance, ascent, descent, elapsedS.toDouble(), "active") }
        safeDb("close older") { db.finishOtherActive(tourId) }
        loadFollow()
        acquireWakeLock()
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
        Log.e("OnPath", "startForeground failed", e)
        false
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OnPath:recording").apply {
                setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) { }
    }

    private fun startTicker() {
        scope.launch {
            var lastTick = SystemClock.elapsedRealtime()
            var lastStats = lastTick
            while (isActive) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                // real time since the last tick: a tick delayed by Doze or a busy phone must not lose minutes
                if (!paused) elapsedS += ((now - lastTick) / 1000.0).toLong().coerceIn(1, 600)
                lastTick = now
                if (wakeLock?.isHeld != true) acquireWakeLock() // the lock times out after 12 h: long days go on
                if (gpsLost()) navigator?.onGpsLost() // no alarms without GPS (R-04)
                if (now - lastStats >= 10_000) {
                    lastStats = now
                    safeDb("stats") { db.updateStats(tourId, distance, ascent, descent, elapsedS.toDouble(), "active") }
                }
                try { trip.tick(elapsedS.toDouble(), System.currentTimeMillis()) } catch (_: Exception) { }
                if (now - tripSaved > 30_000) saveTrip()
                publish()
            }
        }
    }

    private fun gpsLost() = SystemClock.elapsedRealtime() - lastGoodFixElapsed > 8_000

    private fun onFix(loc: Location) {
        if (tourId < 0 || paused) return
        if (loc.provider != LocationManager.GPS_PROVIDER) return
        if (!PointFilter.accuracyOk(loc.hasAccuracy(), loc.accuracy)) return

        val nowEl = SystemClock.elapsedRealtime()
        val wasLost = lastGoodFixElapsed > 0 && nowEl - lastGoodFixElapsed > GAP_MS
        lastGoodFixElapsed = nowEl
        val p = TrackPoint(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else null, loc.time)

        checkOffRoute(p, loc.accuracy.toDouble(), nowEl)
        try {
            val nav = navigator
            val onRoute = nav != null && nav.detector.state == OffRouteDetector.State.ON_ROUTE
            trip.onFix(elapsedS.toDouble(), System.currentTimeMillis(), p.lat, p.lon, nav?.detector?.progressM, onRoute)
        } catch (e: Exception) { Log.e("OnPath", "trip pace", e) }

        val prev = last
        val (verdict, moved) = PointFilter.judge(prev, p, loc.accuracy.toDouble(), continuous = !wasLost)
        if (verdict != PointFilter.Verdict.ACCEPT) return             // GPS jump or standing still
        distance += moved
        if (wasLost && prev != null) seg++                            // GPS gap: new segment
        p.ele?.let { e ->
            val r = eleRef
            if (r == null) eleRef = e
            else if (e - r >= 5) { ascent += e - r; eleRef = e }
            else if (r - e >= 5) { descent += r - e; eleRef = e }
        }
        // The point goes to SQLite first (R-07). seq advances only when it is really stored.
        if (safeDb("point") { db.appendPoint(tourId, seq, seg, p) }) {
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
        Log.e("OnPath", "db $what failed", e)
        if (!saveFailed) { saveFailed = true; notifySaveFailed(); vibrate() }
        false
    }

    private fun notifySaveFailed() {
        try {
            val text = "The track cannot be saved any more (storage full or database broken). Free up space; recording keeps trying."
            nm().notify(SAVE_FAIL_ID, NotificationCompat.Builder(this, CHANNEL_NAV)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Recording cannot be saved")
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setOngoing(true)
                .setContentIntent(openAppIntent())
                .build())
        } catch (_: Exception) { }
    }

    /**
     * The service cannot (or can no longer) record: say so in a notification that stays, then stop. The tour keeps
     * its "active" state, so the app offers Continue / Save next time. Never a silent stopSelf().
     */
    private fun failLoud(text: String) {
        Log.e("OnPath", "recording stopped: $text")
        try {
            nm().notify(SAVE_FAIL_ID, NotificationCompat.Builder(this, CHANNEL_NAV)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Recording stopped")
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build())
        } catch (_: Exception) { }
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
                NavSession(NavKind.TRACK, follow?.first ?: "Route", prefs.activity, prefs.sac, line)
            }
        }
        navigator = session?.let { Navigator(it).apply { setNavigating(navigating) } }
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
        if (step.vibrate) vibrate()
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
            try { db.updateActivity(tourId, nav.session.activity.name, nav.session.sac) } catch (e: Exception) { Log.e("OnPath", "activity", e) }
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
                    Log.e("OnPath", "reroute failed", t)
                    Rerouter.Failed(Rerouter.Reason.UNAVAILABLE, t.message ?: t.javaClass.simpleName, 0)
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

    private fun vibrate() {
        try {
            val vib = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            vib.vibrate(VibrationEffect.createWaveform(OFF_ROUTE_VIBRATION, -1))
        } catch (_: Exception) { }
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
                else -> hint ?: "Back to ${follow?.first ?: "the route"}"
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
        } catch (_: SecurityException) { }
    }

    private fun setPaused(p: Boolean) {
        if (tourId < 0) return
        if (paused == p) return
        paused = p
        if (!p) { seg++; last = null } // resume = new segment, distance not bridged
        trip.onPause() // a pause is not a break (its time is not in elapsedS anyway)
        saveTrip()
        navigator?.onGpsLost() // no fixes while paused: timers start from zero again
        safeDb("stats") { db.updateStats(tourId, distance, ascent, descent, elapsedS.toDouble(), "active") }
        publish(force = true)
    }

    private fun finish() {
        if (tourId >= 0) {
            safeDb("finish") { db.updateStats(tourId, distance, ascent, descent, elapsedS.toDouble(), "done") }
            try {
                com.onpath.app.time.TourTimesStore(File(filesDir, com.onpath.app.time.TourTimesStore.FILE))
                    .put(tourId, tourTimes())
                learnAcrossTours()
                File(filesDir, TRIP_FILE).delete()
            } catch (e: Exception) { Log.e("OnPath", "tour times", e) }
            _finished.value = tourId
        }
        tourId = -1
        tracker.stop()
        cancelReroute()
        scope.cancel()
        nm().cancel(ALERT_ID)
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
        val now = SystemClock.elapsedRealtime()
        if (force || now - lastNotify > 5000) {
            lastNotify = now
            try { nm().notify(NOTIF_ID, buildNotification()) } catch (_: SecurityException) { }
        }
    }

    // ---- time estimate (docs/plan-time-estimate.md) ----

    private fun tripTime(): com.onpath.app.time.TripTime = try {
        trip.snapshot()
    } catch (_: Exception) { com.onpath.app.time.TripTime(elapsedS.toDouble(), 0.0) }

    private fun tourTimes(): com.onpath.app.time.TourTimes = trip.tourTimes(elapsedS.toDouble())

    /** Learning across tours (owner decision Q4): stores this tour's pace when it followed a route long enough. */
    private fun learnAcrossTours() {
        try {
            val a = timeActivity()
            val prefs = Prefs(this)
            val store = com.onpath.app.time.PaceStore.fromJson(prefs.paceStore)
            val pace = com.onpath.app.time.TimeEstimate.pace(a, settings ?: prefs.settings).factor
            val (p, obs) = trip.observed()
            val t = tourTimes()
            val next = store.update(a, trip.factors(), p, obs, trip.prior * (1 / pace), t.movingS, t.breakS)
            if (next !== store) { prefs.paceStore = next.toJson(); Log.i("OnPath", "pace learned for $a: ${next.factors(a)}") }
        } catch (e: Exception) { Log.e("OnPath", "learn pace", e) }
    }

    private fun activityOf(name: String?) =
        runCatching { com.onpath.app.routing.Activity.valueOf(name!!) }.getOrDefault(com.onpath.app.routing.Activity.HIKE)

    /** Activity the times are computed for: the navigated session's, else the recording's. */
    private fun timeActivity() = navigator?.session?.activity ?: recActivity

    /** Pace setting × learned factors for [a] (same as the plan sheet). */
    private fun priorFor(a: com.onpath.app.routing.Activity): com.onpath.app.time.Factors = try {
        com.onpath.app.time.TimeEstimate.planFactors(a, settings ?: Prefs(this).settings,
            com.onpath.app.time.PaceStore.fromJson(Prefs(this).paceStore).factors(a))
    } catch (_: Exception) { com.onpath.app.time.Factors.ONE }

    /** The navigated line changed (start, re-route, back on the original): new times, learned factors stay. */
    private fun timeLine() {
        try {
            val nav = navigator
            trip.setPrior(priorFor(timeActivity()))
            trip.setLine(nav?.session?.active?.let { com.onpath.app.time.RouteTimes.of(it, nav.session.activity) },
                nav?.detector?.progressM ?: 0.0)
        } catch (e: Exception) { Log.e("OnPath", "trip line", e) }
    }

    /** Trip state of the running tour, written atomically (crash / restart keeps the break time). */
    private fun saveTrip() {
        if (tourId < 0) return
        tripSaved = SystemClock.elapsedRealtime()
        try {
            val o = org.json.JSONObject().put("tour", tourId).put("trip", trip.toJson())
            val f = File(filesDir, TRIP_FILE); val tmp = File(f.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { Log.e("OnPath", "save trip", e) }
    }

    private fun loadTrip() {
        val prior = priorFor(recActivity)
        trip = try {
            val o = org.json.JSONObject(File(filesDir, TRIP_FILE).readText())
            if (o.optLong("tour", -1) == tourId) com.onpath.app.time.TripPace.fromJson(o.optJSONObject("trip"), prior)
            else com.onpath.app.time.TripPace(prior)
        } catch (_: Exception) { com.onpath.app.time.TripPace(prior) }
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

    private fun buildNotification(): Notification {
        val status = when {
            paused -> "Paused"
            tourId >= 0 && gpsLost() -> "Searching GPS…"
            navigator?.prompt != null -> "Off route"
            else -> "Recording"
        }
        val b = NotificationCompat.Builder(this, CHANNEL_REC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${Format.distance(distance)} · ${Format.clock(elapsedS)}")
            .setContentText(status + (follow?.let { " · following ${it.first}" } ?: ""))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
        if (paused) b.addAction(0, "Resume", actionIntent(ACTION_RESUME, 2))
        else b.addAction(0, "Pause", actionIntent(ACTION_PAUSE, 1))
        b.addAction(0, "Stop & save", actionIntent(ACTION_STOP, 3))
        return b.build()
    }

    companion object {
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

        const val CHANNEL_REC = "recording"
        /** Old off-route channel (vibrated by itself); deleted, see [CHANNEL_NAV]. */
        private const val CHANNEL_ALERT_OLD = "offroute"
        /** Off-route question: high importance with sound, no channel vibration (the service vibrates once itself). */
        const val CHANNEL_NAV = "offroute-question"
        val OFF_ROUTE_VIBRATION = longArrayOf(0, 400, 200, 400, 200, 600)
        private const val NOTIF_ID = 42
        private const val ALERT_ID = 43
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
        private val _startFailed = MutableStateFlow<String?>(null)
        /** Why the service stopped by itself (plain text; the app shows it and clears it). */
        val startFailed: MutableStateFlow<String?> = _startFailed

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_REC, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows distance and time while recording"
                }
            )
            runCatching { nm.deleteNotificationChannel(CHANNEL_ALERT_OLD) }
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_NAV, "Off route", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Asks when you leave the route you follow: take a new route or keep the original"
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
                Log.e("OnPath", "service $action failed", e)
            }
        }
    }
}
