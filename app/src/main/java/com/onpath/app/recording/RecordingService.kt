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
import com.onpath.app.routing.GeoPoint
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
)

/**
 * Foreground service that records the track. Every accepted point goes straight into
 * SQLite, so nothing is lost if the process dies (R-07). Also does off-route checks.
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
    private var offSince = 0L
    private var offAlerted = false
    private var offDistance: Double? = null
    /** Off-route settings, re-read when the app sends ACTION_FOLLOW_CHANGED. */
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
        val control = intent?.action in setOf(ACTION_PAUSE, ACTION_RESUME, ACTION_STOP, ACTION_FOLLOW_CHANGED, ACTION_NAVIGATING)
        if (control && tourId < 0) { stopSelf(startId); return START_NOT_STICKY } // nothing is recording
        when (intent?.action) {
            ACTION_PAUSE -> { setPaused(true); return START_STICKY }
            ACTION_RESUME -> { setPaused(false); return START_STICKY }
            ACTION_STOP -> { finish(); return START_NOT_STICKY }
            ACTION_NAVIGATING -> {
                navigating = intent.getBooleanExtra(EXTRA_ON, true); offSince = 0
                if (!navigating && offAlerted) { offAlerted = false; nm().cancel(ALERT_ID) }
                publish(); return START_STICKY
            }
            ACTION_FOLLOW_CHANGED -> {
                follow = Prefs(this).loadFollow(); settings = null; offSince = 0; offAlerted = false; publish(); return START_STICKY
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
        follow = Prefs(this).loadFollow()
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
                if (gpsLost()) { offSince = 0; offDistance = null } // no alarms without GPS
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

        checkOffRoute(p)

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

    private fun checkOffRoute(p: TrackPoint) {
        val line = follow?.second
        if (line.isNullOrEmpty()) { offDistance = null; return }
        val d = Geo.distanceToLine(p.lat, p.lon, line)
        offDistance = d
        val now = SystemClock.elapsedRealtime()
        val cfg = settings ?: Prefs(this).settings.also { settings = it }
        if (navigating && cfg.offRouteWarning && d > cfg.offRouteM) {
            if (offSince == 0L) offSince = now
            if (!offAlerted && now - offSince >= OFF_ROUTE_MS) { offAlerted = true; alertOffRoute(d) }
        } else {
            offSince = 0;
            if (offAlerted) { offAlerted = false; nm().cancel(ALERT_ID) }
        }
    }

    private fun alertOffRoute(d: Double) {
        try {
            val vib = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 600), -1))
        } catch (_: Exception) { }
        try {
            val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("You left the route")
                .setContentText("${d.toInt()} m away from ${follow?.first ?: "the route"}")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
            nm().notify(ALERT_ID, n)
        } catch (_: SecurityException) { }
    }

    private fun setPaused(p: Boolean) {
        if (tourId < 0) return
        if (paused == p) return
        paused = p
        if (!p) { seg++; last = null } // resume = new segment, distance not bridged
        offSince = 0
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
        scope.cancel()
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
            offDistance, offAlerted, follow?.first, track.map { it.toList() },
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

    private fun actionIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(): Notification {
        val status = when {
            paused -> "Paused"
            tourId >= 0 && gpsLost() -> "Searching GPS…"
            offAlerted -> "Off route (${offDistance?.toInt() ?: 0} m)"
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
        const val EXTRA_CONTINUE_ID = "continueId"
        const val EXTRA_ACTIVITY = "activity"

        const val CHANNEL_REC = "recording"
        const val CHANNEL_ALERT = "offroute"
        private const val NOTIF_ID = 42
        private const val ALERT_ID = 43

        const val MAX_ACCURACY_M = 30f
        const val MAX_SPEED_MS = 35.0       // ~126 km/h: anything faster is a GPS jump
        const val GAP_MS = 30_000L          // no fix for 30 s -> new segment
        const val OFF_ROUTE_MS = 15_000L

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
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "Off-route warning", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Warns when you leave the route you follow"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
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
