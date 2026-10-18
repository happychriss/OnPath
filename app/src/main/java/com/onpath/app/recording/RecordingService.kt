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
                settings = null; applySettings(); updateAlert(); publish(); return START_STICKY
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
        if (tourId >= 0) return START_STICKY // already recording

        // ACTION_START, or a sticky restart by the system (null intent): continue an active recording.
        val continueId = intent?.getLongExtra(EXTRA_CONTINUE_ID, -1L) ?: -1L
        val existing = if (continueId >= 0) db.getTour(continueId) else if (intent == null) db.findActiveRecording() else null
        if (intent == null && existing == null) { stopSelf(); return START_NOT_STICKY }

        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }

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
            db.updateStats(tourId, distance, ascent, descent, elapsedS.toDouble(), "active")
        } else {
            val name = "Tour " + SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date())
            tourId = db.insertTour(TourKind.RECORDED, name, intent?.getStringExtra(EXTRA_ACTIVITY), 3,
                0.0, 0.0, 0.0, 0.0, emptyList(), state = "active")
        }
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
            while (isActive) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                if (!paused) elapsedS += ((now - lastTick) / 1000.0).toLong().coerceAtLeast(1).coerceAtMost(5)
                lastTick = now
                if (gpsLost()) navigator?.onGpsLost() // no alarms without GPS (R-04)
                if (elapsedS % 10 == 0L) db.updateStats(tourId, distance, ascent, descent, elapsedS.toDouble(), "active")
                publish()
            }
        }
    }

    private fun gpsLost() = SystemClock.elapsedRealtime() - lastGoodFixElapsed > 8_000

    private fun onFix(loc: Location) {
        if (tourId < 0 || paused) return
        if (loc.provider != LocationManager.GPS_PROVIDER) return
        if (!loc.hasAccuracy() || loc.accuracy > MAX_ACCURACY_M) return

        val nowEl = SystemClock.elapsedRealtime()
        val wasLost = lastGoodFixElapsed > 0 && nowEl - lastGoodFixElapsed > GAP_MS
        lastGoodFixElapsed = nowEl
        val p = TrackPoint(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else null, loc.time)

        checkOffRoute(p, loc.accuracy.toDouble(), nowEl)

        val prev = last
        if (prev != null && !wasLost) {
            val d = Geo.distance(prev.lat, prev.lon, p.lat, p.lon)
            val dt = ((p.timeMs ?: 0) - (prev.timeMs ?: 0)) / 1000.0
            if (dt > 0 && d / dt > MAX_SPEED_MS) return              // impossible jump
            val minMove = maxOf(3.0, minOf(loc.accuracy.toDouble(), 15.0) / 2)
            if (d < minMove && dt < 30) return                         // standing still: ignore jitter
            distance += d
        }
        if (wasLost && prev != null) seg++                            // GPS gap: new segment
        p.ele?.let { e ->
            val r = eleRef
            if (r == null) eleRef = e
            else if (e - r >= 5) { ascent += e - r; eleRef = e }
            else if (r - e >= 5) { descent += r - e; eleRef = e }
        }
        try { db.appendPoint(tourId, seq++, seg, p) } catch (e: Exception) { Log.e("OnPath", "write point", e) }
        last = p
        if (track.isEmpty() || trackSeg != seg) { track += mutableListOf<GeoPoint>(); trackSeg = seg }
        track.last() += p.toGeo()
        publish()
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
        navigator?.onGpsLost() // no fixes while paused: timers start from zero again
        db.updateStats(tourId, distance, ascent, descent, elapsedS.toDouble(), "active")
        publish(force = true)
    }

    private fun finish() {
        if (tourId >= 0) {
            db.updateStats(tourId, distance, ascent, descent, elapsedS.toDouble(), "done")
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
        if (tourId >= 0) db.updateStats(tourId, distance, ascent, descent, elapsedS.toDouble(), "active")
        _state.value = null
        super.onDestroy()
    }

    private fun publish(force: Boolean = false) {
        if (tourId < 0) return
        _state.value = RecState(
            tourId, paused, distance, ascent, descent, elapsedS, gpsLost(),
            navigator?.state?.offM, navigator?.prompt != null, follow?.first, track.map { it.toList() },
            navigator?.state,
        )
        val now = SystemClock.elapsedRealtime()
        if (force || now - lastNotify > 5000) {
            lastNotify = now
            try { nm().notify(NOTIF_ID, buildNotification()) } catch (_: SecurityException) { }
        }
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

        const val MAX_ACCURACY_M = 30f
        const val MAX_SPEED_MS = 35.0       // ~126 km/h: anything faster is a GPS jump
        const val GAP_MS = 30_000L          // no fix for 30 s -> new segment
        
        private val _state = MutableStateFlow<RecState?>(null)
        val state: StateFlow<RecState?> = _state
        private val _finished = MutableStateFlow<Long?>(null)
        /** Id of the last tour stopped (UI shows a summary, then clears it). */
        val finished: MutableStateFlow<Long?> = _finished

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
