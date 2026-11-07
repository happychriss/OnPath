package com.onpath.app.recording

import com.onpath.app.data.AtomicFiles
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
import com.onpath.app.navigation.offRoute
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The fixes a tour records and navigates on: GPS only (owner, robust first – a clean track, and no "off route" from a
 * ±100 m Wi-Fi fix). Network and fused fixes – the app's provisional indoor position included – never become a point.
 * Pure.
 */
internal fun tracksFix(provider: String?): Boolean = provider == LocationManager.GPS_PROVIDER

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
    /**
     * A usable GPS fix came since the service started (this tour or its continuation). False = started indoors: the app
     * shows its Wi-Fi / fused fix as a provisional position until then ([com.onpath.app.navigation.Provisional]).
     */
    val hadGpsFix: Boolean = true,
    /**
     * "No GPS" is a quiet grey state, not the amber warning (owner 2026-11-01): standing still ([MoveWatch]), or lost
     * for less than [com.onpath.app.navigation.VoiceGuide.GPS_LOST_MS] – the warning comes with the spoken one.
     */
    val gpsQuiet: Boolean = false,
    /**
     * No GPS and the phone lies still by its own motion sensor (owner 2026-11-02, phone on the table): nothing is shown
     * about GPS at all ([NoGpsShow]); false without a sensor or while it is not sure.
     */
    val gpsStill: Boolean = false,
    /**
     * The end of the followed line was reached (review H2: checked here, also with the phone in the pocket). Guidance
     * is quiet from then on (no off-route alarms on the way to the car park); recording goes on. The app asks once.
     */
    val arrived: Boolean = false,
    /**
     * When the pause began (wall clock, ms; null while recording): Navigate's pause band counts from here (owner
     * 2026-11-07, variant C of https://claude.ai/artifact/24XVQ4goVZixT53fnQDn7H) – the service holds it, so the band
     * keeps its time when the app is reopened.
     */
    val pausedSinceMs: Long? = null,
)

/**
 * Foreground service that records the track. Every accepted point goes straight into
 * SQLite, so nothing is lost if the process dies (R-07). Also owns navigation: off-route detection and the
 * re-routing question ([Navigator]), so it works with the screen off and the phone in the pocket (F-NAV-04).
 * Routing runs on its own single thread; a newer request cancels the older one. Never blocks recording.
 */
class RecordingService : Service() {

    /**
     * Main-thread work of the tour. An uncaught exception here used to kill the process and START_STICKY brought the
     * service back mid-tour (field 2026-11-01: "Los geht's" 3–4×): it is reported, the tour goes on. Recreated for a
     * tour started on an instance whose previous tour just ended ([finish] cancels it).
     */
    private var scope = newScope()

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
        Problems.error("recording", "Unexpected error in the recording service; the tour goes on", e)
    })
    private lateinit var db: TourDb
    private lateinit var tracker: LocationTracker
    private var wakeLock: PowerManager.WakeLock? = null

    private var tourId = -1L
    private var seq = 0
    private var seg = 0
    private var paused = false
    /** Wall-clock start of the current pause ([RecState.pausedSinceMs]); null while recording. */
    private var pausedSinceMs: Long? = null
    private var distance = 0.0
    private var ascent = 0.0
    private var descent = 0.0
    /** Ascent / descent from the terrain under the points, GPS altitude where there is none ([AscentCounter]). */
    private val climb = AscentCounter()
    private var terrain: com.onpath.app.data.TerrainHeights? = null
    private var elapsedS = 0L
    private var last: TrackPoint? = null
    private var lastGoodFixElapsed = 0L
    /** Fixes dropped for accuracy since the last good one: says why a GPS gap had no usable fix. */
    private val gapWatch = PointFilter.GapWatch()
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
    /** Vibration before junctions of the followed line + the next junction for the lock screen (owner). */
    private val junctions = com.onpath.app.navigation.JunctionCues()
    /** Spoken directions (docs/design/voice-guidance.md): what to say ([voice]), the phone's speech output ([speaker]). */
    private val voice = com.onpath.app.navigation.VoiceGuide()
    /** The voice memory last written to [TRIP_FILE] (written again when it changes). */
    private var voiceSaved: com.onpath.app.navigation.VoiceGuide.Memory? = null
    private var speaker: Speaker? = null
    private val junctionSpeeds = com.onpath.app.navigation.SpeedWindow()
    /**
     * The last GPS fix for the voice (owner field ride 2026-11-07): how old it was when it came, where it is and the
     * heading ridden over the last metres ([com.onpath.app.navigation.GoalSide.heading]; null while standing).
     */
    private var voiceFixAgeMs = 0L
    private var voiceHere: GeoPoint? = null
    private var voiceHeading: Double? = null
    /** Line version whose junction count was traced (a missing junction vibration is explained in the trace). */
    private var junctionsTracedVersion = Int.MIN_VALUE
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
    /** False while the app is in Plan mode ("Navigation Stopp"): the phone is quiet then (recording goes on). */
    private var navigating = true
    /** What the navigator was last told: Navigate or Plan ([StopQuiet]). */
    private var navigatingApplied = true
    /** "Navigation Stopp" = quiet, also with the screen off (owner 2026-11-01); only Continue ends it (owner 2026-11-03). */
    private val stopQuiet = StopQuiet()

    /** The app is in Plan during the tour: silent ([ModeQuiet]). */
    private fun planQuiet(): Boolean = tourId >= 0 && stopQuiet.quiet

    /** Guidance on in Navigate only; re-applied every second. */
    private fun applyNavigating() {
        val now = navigating
        if (now == navigatingApplied) return
        navigatingApplied = now
        if (now) voice.dropWaiting() // nothing from the time in Plan is said late (owner 2026-11-03)
        updateAlert()
        Problems.trace("navigation", if (now) "Guidance on (Navigate, or walked off from Navigation Stopp)" else
            "Navigation Stopp: quiet – no voice, no junction vibration, no off-route question")
        navigator?.let { handle(it.setNavigating(now)) }
    }
    private var lastNotify = 0L
    /** Moving time, breaks and pace learning of this tour (persisted in [TRIP_FILE], survives a restart). */
    private var trip = com.onpath.app.time.TripPace()
    /** Activity of the recording (the navigated session's activity wins for the time model). */
    private var recActivity = com.onpath.app.routing.Activity.HIKE
    private var tripSaved = 0L
    /** This tour carries the "Demo · " name (Developer tools → Demo fed it simulated fixes). */
    private var demoMarked = false
    /** The last fix was simulated: a switch between demo and real GPS starts a new track piece (never a jump). */
    private var lastFixDemo = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        db = TourDb.get(this)
        tracker = LocationTracker(this, 1000L, useNetwork = false) // the track is GPS only; no network fixes (battery)
        createChannels(this)
        routeChannelOk = Build.VERSION.SDK_INT < 26 || try {
            nm().getNotificationChannel(TourChannels.NAVIGATION) != null
        } catch (e: Exception) { Problems.warn("recording", "Navigation notification channel not readable; tour uses the recording channel", e); false }
        if (!routeChannelOk) Problems.warn("recording", "Navigation notification channel missing; the route notification stays off the lock screen")
    }

    /**
     * Review M3: an exception in an action (Navigator answers, a bad session file) killed the process – and with a sticky
     * restart the tour. Reported; a running tour stays in the foreground and goes on.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = try {
        startCommand(intent, startId)
    } catch (e: Exception) {
        Problems.error("recording", "Recording service action ${intent?.action ?: "restart"} failed; the tour goes on", e)
        if (tourId >= 0) { goForeground(); START_STICKY } else { stopSelf(startId); START_NOT_STICKY }
    }

    private fun startCommand(intent: Intent?, startId: Int): Int {
        val control = intent?.action in setOf(ACTION_PAUSE, ACTION_RESUME, ACTION_STOP, ACTION_FOLLOW_CHANGED, ACTION_NAVIGATING,
            ACTION_SETTINGS, ACTION_TAKE_ROUTE, ACTION_TAKE_VIA, ACTION_KEEP_ROUTE, ACTION_CHECK_NOW, ACTION_PROPOSE_SWITCH,
            ACTION_LEAD_THERE, ACTION_JUST_RECORD, ACTION_LEAD_FAILED_OK, ACTION_JOIN_HERE)
        if (control && tourId < 0) { stopSelf(startId); return START_NOT_STICKY } // nothing is recording
        when (intent?.action) {
            ACTION_PAUSE -> { setPaused(true); return START_STICKY }
            ACTION_RESUME -> { setPaused(false); return START_STICKY }
            ACTION_STOP -> { finish(); return START_NOT_STICKY }
            ACTION_NAVIGATING -> {
                // Plan mode: no new questions; a pending one stays (owner: nothing disappears by itself)
                navigating = intent.getBooleanExtra(EXTRA_ON, true)
                stopQuiet.set(stopped = !navigating)
                navigatingApplied = navigating
                // back in Navigate: sentences that waited are dropped – the state is checked afresh (owner 2026-11-03)
                if (navigating) voice.dropWaiting()
                navigator?.let { handle(it.setNavigating(navigatingApplied)) } ?: run { updateAlert(); publish() }
                publish(force = true) // the notification says at once whether the guidance is on (RecNotifText)
                return START_STICKY
            }
            ACTION_FOLLOW_CHANGED -> {
                settings = null
                loadFollow()
                if (intent.getBooleanExtra(EXTRA_CHECK_NOW, false)) navigator?.checkNow()
                publish(); return START_STICKY
            }
            ACTION_CHECK_NOW -> { navigator?.checkNow(); return START_STICKY }
            ACTION_SAY -> {
                // an app event (arrival, nothing ahead after an edit): said at once when spoken directions are on
                val phrase = when (intent.getStringExtra(EXTRA_SAY)) {
                    SAY_ARRIVED -> com.onpath.app.navigation.Phrase.Arrived
                    SAY_NO_DESTINATION -> com.onpath.app.navigation.Phrase.NoDestination
                    else -> null
                }
                // Plan is silent (owner 2026-11-03): "kein Ziel mehr" after an edit in Plan is not said
                if (phrase != null && GuidanceGate.appEventVoice(hasPhrase = true, voiceOn(), planQuiet())) {
                    voice.event(phrase, SystemClock.elapsedRealtime())?.let { voiceSpeaker()?.say(com.onpath.app.navigation.VoiceText.of(it)) }
                    saveVoice()
                }
                return START_STICKY
            }
            ACTION_SETTINGS -> {
                settings = null; applySettings(); trip.setPrior(priorFor(timeActivity())); updateAlert(); publish(); return START_STICKY
            }
            ACTION_TAKE_ROUTE -> {
                navigator?.let { handle(it.take(intent.getIntExtra(EXTRA_QUESTION, -1))) }
                return START_STICKY
            }
            ACTION_TAKE_VIA -> {
                // "Off route – re-plan?" – Yes: the new route via the stop in question
                navigator?.let { handle(it.takeVia(intent.getIntExtra(EXTRA_QUESTION, -1))) }
                return START_STICKY
            }
            ACTION_KEEP_ROUTE -> {
                navigator?.let { handle(it.keep(intent.getIntExtra(EXTRA_QUESTION, -1))) }
                return START_STICKY
            }
            ACTION_LEAD_THERE -> {
                navigator?.let { handle(it.leadThere(intent.getIntExtra(EXTRA_QUESTION, -1))) }
                return START_STICKY
            }
            ACTION_JUST_RECORD -> {
                navigator?.let { handle(it.justRecord(intent.getIntExtra(EXTRA_QUESTION, -1))) }
                return START_STICKY
            }
            ACTION_LEAD_FAILED_OK -> {
                navigator?.let { handle(it.leadFailedOk(intent.getIntExtra(EXTRA_QUESTION, -1))) }
                return START_STICKY
            }
            ACTION_JOIN_HERE -> {
                navigator?.let { handle(it.joinHere(intent.getIntExtra(EXTRA_QUESTION, -1))) }
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
        if (!scope.isActive) scope = newScope() // a tour ended on this instance a moment ago (finish cancelled it)

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
                tourMeta = Triple(existing.name, existing.createdMs, existing.sac)
                distance = existing.distanceM; ascent = existing.ascentM; descent = existing.descentM
                elapsedS = existing.durationS.toLong()
                val (nextSeq, lastSeg) = db.recordingCursor(tourId)
                seq = nextSeq; seg = lastSeg + 1 // resume in a new segment, no straight jump
                val segs = db.loadSegments(tourId)
                segs.forEach { s -> track += s.map { it.toGeo() }.toMutableList() }
                trackSeg = lastSeg
                climb.restart()
                last = null
                recActivity = activityOf(existing.activity)
                demoMarked = existing.name.startsWith(DemoLocation.TOUR_PREFIX)
                voice.newTour()
                arrivedToken = null
                loadTrip() // also the tour's voice memory: a sticky restart never says "Los geht's" again
            } else {
                // a tour started during a demo is marked as such (easy to find and delete)
                demoMarked = DemoLocation.active
                val name = (if (demoMarked) DemoLocation.TOUR_PREFIX else "") +
                    com.onpath.app.rides.RideApply.dateName(System.currentTimeMillis())
                // the tour keeps the T level it was recorded at: reopening it sets the user's level, and its GPX type
                // follows it
                val sac = newTourSac(intent?.getIntExtra(EXTRA_SAC, 0) ?: 0, Prefs(this).sac)
                val created = System.currentTimeMillis()
                tourId = db.insertTour(TourKind.RECORDED, name, intent?.getStringExtra(EXTRA_ACTIVITY), sac,
                    0.0, 0.0, 0.0, 0.0, emptyList(), state = "active", createdMs = created)
                tourMeta = Triple(name, created, sac)
                recActivity = activityOf(intent?.getStringExtra(EXTRA_ACTIVITY))
                firstFixElapsed = 0L // a new tour: its first heights settle again (SeaLevel)
                climb.restart()
                trip = com.onpath.app.time.TripPace(priorFor(recActivity))
                voice.newTour(); voiceSaved = null
                arrived = false; arrivedToken = null; arrivalGate.reset()
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
        // terrain for the ascent: opened on its own thread now, so the first points already have ground heights
        try {
            val t = terrain ?: com.onpath.app.data.TerrainHeights({ DataFiles(this).mapDir() }).also { terrain = it }
            t.prepare()
        } catch (e: Exception) { Problems.warn("elevation", "Terrain for the ascent not prepared; ascent from GPS altitude", e) }
        // T5 in the background (owner): map areas ahead load also with the screen off; joined after the tour
        try { com.onpath.app.cells.AppCells.get(this).apply { tourInBackground = true; holdUpdates = true } } catch (e: Exception) {
            Problems.warn("cells", "Map loading during the tour not prepared; the map ahead loads when the app is open", e)
        }
        acquireWakeLock()
        restWatch.reset(); tracker.setInterval(RestWatch.NORMAL_MS) // never start a tour at the standing-still rate
        moveWatch.start(SystemClock.elapsedRealtime())
        saverWarned = false
        if (!saverRegistered) try {
            ContextCompat.registerReceiver(this, saverReceiver,
                android.content.IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            saverRegistered = true
        } catch (e: Exception) { Problems.warn("recording", "Battery saver changes not watched during the tour", e) }
        // came back by itself (sticky restart): say so if no GPS comes (review H3)
        if (intent == null) gpsWatchdog.arm(SystemClock.elapsedRealtime()) else gpsWatchdog.disarm()
        tracker.onFix = { onFix(it) }
        tracker.start()
        startTicker()
        publish()
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this, tourPost().id, buildNotification(),
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
            lastTick = SystemClock.elapsedRealtime()
            lastStats = lastTick
            pausedBefore = paused
            while (isActive) {
                delay(1000)
                try { tick() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    Problems.error("recording", "Recording tick failed; the tour goes on", e)
                }
            }
        }
    }

    private var lastTick = 0L
    /** The part of a second not counted yet ([tick]). */
    private var tickRestMs = 0L
    private var lastStats = 0L
    private var pausedBefore = false

    /** One second of the tour: time, wake lock, GPS lost, stats, trip pace, state for the app. */
    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        applyNavigating()
        // real time since the last tick: a tick delayed by Doze or a busy phone must not lose minutes.
        // Not across a pause: without the wake lock a paused tick can come late (resume counts from here).
        // milliseconds, not whole seconds per tick (review M6: a 1.9 s tick counted 1 s – minutes lost over a day)
        if (!paused && !pausedBefore) {
            tickRestMs += (now - lastTick).coerceIn(1_000L, 600_000L)
            elapsedS += tickRestMs / 1000; tickRestMs %= 1000
        }
        pausedBefore = paused
        lastTick = now
        // the lock times out after 12 h: long days go on; paused = slow GPS (10 s), no lock (battery)
        if (!paused && wakeLock?.isHeld != true) acquireWakeLock()
        // the motion sensor only while recording without a fix (battery): "still" = nothing about GPS (owner 2026-11-02)
        motion.listen(tourId >= 0 && !paused && gpsLost())
        if (gpsLost()) navigator?.onGpsLost() // no alarms without GPS (R-04)
        if (gpsLost()) navigator?.state?.let { voiceStep(it, now) } // "No GPS signal" comes without fixes
        if (gpsWatchdog.due(now, paused)) notifyNoGpsAfterRestart()
        if (now - lastStats >= 10_000) {
            lastStats = now
            if (pointQueue.size > 0) writePoints() // storage back while standing still: the waiting points go in
            saveStats("active")
        }
        try { trip.tick(elapsedS.toDouble(), System.currentTimeMillis()) } catch (e: Exception) {
            Problems.error("time", "Trip pace tick failed; times shown may be wrong", e)
        }
        if (now - tripSaved > 30_000) saveTrip()
        publish()
    }

    private fun gpsLost() = SystemClock.elapsedRealtime() - lastGoodFixElapsed > RestWatch.lostAfterMs(restWatch.resting)

    /** Quiet while standing still (owner 2026-11-01): moving = a point accepted within the last 2 min ([MoveWatch]). */
    private val moveWatch = MoveWatch()

    /** The phone's motion sensor while there is no fix (owner 2026-11-02): lying still = nothing about GPS. */
    private val motion by lazy { MotionSense(this) }

    /** What the card says about a missing fix ([NoGpsShow]): nothing (still), a quiet grey line, or the amber warning. */
    private fun noGpsLine(): NoGpsShow.Line = NoGpsShow.line(motion.still(SystemClock.elapsedRealtime()), moveWatchMoving(),
        SystemClock.elapsedRealtime() - lastGoodFixElapsed < com.onpath.app.navigation.VoiceGuide.GPS_LOST_MS)

    /** "No GPS" not worth a warning (yet): standing still, or a short gap (owner 2026-11-01). */
    private fun gpsQuiet(): Boolean = noGpsLine() != NoGpsShow.Line.WARN

    /** Standing still now: the motion sensor's word while it has one, else [MoveWatch]. */
    private fun standingStill(): Boolean = NoGpsShow.standingStill(motion.still(SystemClock.elapsedRealtime()), moveWatchMoving())

    /** [MoveWatch] – after a GPS loss: moved already before it (a loss while walking is said, one at a table not). */
    private fun moveWatchMoving(): Boolean =
        moveWatch.moving(if (gpsLost() && lastGoodFixElapsed > 0) lastGoodFixElapsed else SystemClock.elapsedRealtime())

    /** Slower GPS while standing still (owner, battery); the paused rate is [PauseWatch]'s. */
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
        // the track, off-route and the time model take GPS only: a Wi-Fi / fused fix (±100 m) never gets here
        if (!tracksFix(loc.provider)) return
        val demoFix = DemoLocation.isDemo(loc)
        if (!demoMarked && demoFix) markDemoTour()
        if (!PointFilter.accuracyOk(loc.hasAccuracy(), loc.accuracy)) { gapWatch.onDropped(loc.hasAccuracy(), loc.accuracy); return }
        if (demoFix != lastFixDemo) {
            // the simulated place is far from the real one: without a new piece every later fix would be a "GPS jump"
            lastFixDemo = demoFix
            if (last != null) { last = null; seg++; climb.restart() }
            Problems.trace("gps", "Demo location ${if (demoFix) "started" else "ended"} during the tour: new track piece")
        }
        // before any filtering below: the first moving fix after a rest switches back to every second (and is stored)
        restRate(loc)

        val nowEl = SystemClock.elapsedRealtime()
        gpsWatchdog.disarm()
        val wasLost = lastGoodFixElapsed > 0 && nowEl - lastGoodFixElapsed > GAP_MS
        val lostSinceElapsed = lastGoodFixElapsed
        lastGoodFixElapsed = nowEl
        val gapWhy = gapWatch.why()
        gapWatch.reset()
        val p = TrackPoint(loc.latitude, loc.longitude, seaLevelHeight(loc, nowEl), loc.time)

        // navigation must never cost the track: a failure here is reported, the point below is still stored (R-07).
        // Standing still (owner 2026-11-01): no new off-route state, no reminder – the detector waits as without GPS
        try { if (GuidanceGate.offRouteCheck(moveWatch.moving(nowEl), arrived)) checkOffRoute(p, loc.accuracy.toDouble(), nowEl) else navigator?.onGpsLost() } catch (e: Exception) {
            Problems.error("navigation", "Off-route check failed on a GPS fix; the track is still recorded", e)
        }
        try { voiceFix(loc, p.toGeo()) } catch (e: Exception) {
            voiceFixAgeMs = 0; voiceHeading = null
            Problems.warn("voice", "Fix age / heading for the voice not read; turns timed without them this time", e)
        }
        try { checkArrived(p.toGeo(), loc.accuracy.toDouble()) } catch (e: Exception) {
            Problems.warn("navigation", "Arrival check failed on a GPS fix; asked again with the next one", e)
        }
        if (GuidanceGate.junctionCue(arrived)) try { junctionCue(loc, nowEl) } catch (e: Exception) {
            Problems.warn("navigation", "Junction check failed on a GPS fix; no junction vibration this time", e)
        } else if (voice.arrivalPending()) try {
            // arrived (the question is up), "Ziel erreicht." waits until the rider is there – turns before it still said
            junctionSpeeds.note(if (loc.hasSpeed()) loc.speed.toDouble() else null, nowEl)
            navigator?.state?.let { voiceStep(it, nowEl) }
        } catch (e: Exception) {
            Problems.warn("voice", "Arrival sentence not checked on a GPS fix; checked again with the next one", e)
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
        moveWatch.moved(nowEl)
        distance += moved
        if (wasLost && prev != null && !bridged) seg++                // long GPS gap: new segment
        // ascent from the terrain under the point (the point keeps its GPS altitude); never costs the track
        try {
            val ground = terrain?.heightAt(p.lat, p.lon)
            val (up, down) = climb.add(ground, p.ele, moved)
            ascent += up; descent += down
        } catch (e: Exception) { Problems.warn("recording", "Ascent not updated on a GPS fix; the track is still recorded", e) }
        // The point goes to SQLite first (R-07). A point that cannot be written now waits in order and is written with
        // the next one that can (review M2) – never dropped while the service runs.
        pointQueue.add(PointQueue.Pending(tourId, seq++, seg, p, loc.accuracy))
        if (writePoints()) {
            if (saveFailed) { saveFailed = false; nm().cancel(SAVE_FAIL_ID) }
        }
        last = p
        if (track.isEmpty() || trackSeg != seg) { track += mutableListOf<GeoPoint>(); trackSeg = seg }
        track.last() += p.toGeo()
        snapshotDirty = true
        publish()
    }

    private val gpsWatchdog = GpsWatchdog()

    /** Battery saver that stops GPS with the screen off, said once per tour (review M5). */
    private var saverWarned = false
    private var saverRegistered = false
    private val saverReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { checkBatterySaver() }
    }

    private fun checkBatterySaver() {
        if (tourId < 0) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val mode = if (Build.VERSION.SDK_INT >= 28) pm.locationPowerSaveMode else null
            if (!com.onpath.app.setup.LocationSaverMode.warnDuringTour(pm.isPowerSaveMode, mode, saverWarned)) return
            saverWarned = true
            Problems.warn("recording", "Battery saver came on during the tour and stops GPS with the screen off – user told")
            val text = Txt.s(Res.string.nav_saver_text)
            nm().notify(SAVE_FAIL_ID, NotificationCompat.Builder(this, CHANNEL_NAV)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(Txt.s(Res.string.nav_saver_title))
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build())
            vibrate()
        } catch (e: Exception) { Problems.warn("recording", "Battery saver state not checked during the tour", e) }
    }

    /** Arrival at the end of the followed line ([com.onpath.app.routing.ArrivalGate], review H2). */
    private val arrivalGate = com.onpath.app.routing.ArrivalGate()
    private var arrived = false
    /** The session token the arrival belongs to (kept in [TRIP_FILE]: a restart keeps "arrived", a new line clears it). */
    private var arrivedToken: Long? = null
    private var lineLength: Pair<Int, Double>? = null

    private fun checkArrived(pos: GeoPoint, accuracyM: Double) {
        val nav = navigator ?: return
        // Plan is silent (owner 2026-11-03): not checked there – the next fix after "Navigation fortsetzen" checks again
        if (!GuidanceGate.arrivalCheck(arrived, paused, planQuiet())) return
        val st = nav.state
        if (st.line.size < 2) return
        val len = lineLength?.takeIf { it.first == st.version }?.second
            ?: Geo.length(st.line).also { lineLength = st.version to it }
        // the progress, not just a match: off the line the furthest progress on it counts (never "near the goal" alone)
        val remaining = (len - (st.shownAlongM ?: st.progressM)).coerceAtLeast(0.0)
        val s = nav.session
        val open = (1 until s.stops.size - 1).count { it !in s.visited }
        if (!arrivalGate.onFix(pos, st.line.last(), remaining, accuracyM, openStops = open)) return
        arrived = true
        arrivedToken = nav.session.token
        saveTrip()
        Problems.trace("navigation", "Arrived at the end of \"${nav.session.name}\": guidance quiet, recording goes on")
        // on the bike "Ziel erreicht." waits until the rider is really there (field 2026-11-07, tour 217: said 31 m before
        // the goal, before its last turn); with the goal's side when it is not straight ahead
        if (voiceOn()) voice.arrive(voiceInput(st, SystemClock.elapsedRealtime()))
            ?.let { voiceSpeaker()?.say(com.onpath.app.navigation.VoiceText.of(it)) }
        publish(force = true)
    }

    private fun notifyNoGpsAfterRestart() {
        Problems.error("recording", "Service restarted by itself and got no GPS fix for 5 min – user told")
        try {
            val text = Txt.s(Res.string.nav_watchdog_text)
            nm().notify(SAVE_FAIL_ID, NotificationCompat.Builder(this, CHANNEL_NAV)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(Txt.s(Res.string.nav_watchdog_title))
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build())
        } catch (e: Exception) {
            Problems.warn("recording", "\"No GPS after restart\" notification could not be shown", e)
        }
        vibrate()
    }

    /** Name, created time and T level of the running tour – to put its row back after a corrupt database (M7). */
    private var tourMeta: Triple<String, Long, Int>? = null

    /**
     * Stats of the running tour; its row gone (review M7: a corrupt database was replaced by an empty one mid-tour) →
     * the tour is put back under its id with every point the service holds, and the user is told it was rescued.
     */
    private fun saveStats(state: String) {
        var gone = false
        safeDb("stats") { gone = db.updateStats(tourId, distance, ascent, descent, elapsedS.toDouble(), state) == 0 }
        if (!gone || tourId < 0) return
        val (name, created, sac) = tourMeta ?: Triple(com.onpath.app.rides.RideApply.dateName(System.currentTimeMillis()),
            System.currentTimeMillis(), Prefs(this).sac)
        val segments = track.map { seg -> seg.map { TrackPoint(it.lat, it.lon, it.ele, null) } }
        if (safeDb("rescue") {
                seq = db.rescueRecording(tourId, name, recActivity.name, sac, created, segments, distance, ascent, descent,
                    elapsedS.toDouble())
                seg = segments.size; last = null
                pointQueue.clear() // the waiting points are in the track just written
                db.updateStats(tourId, distance, ascent, descent, elapsedS.toDouble(), state)
            }) {
            Problems.error("db", "Tour row missing after a database problem – tour rescued with ${segments.sumOf { it.size }} points")
            notifyRescued()
        }
    }

    private fun notifyRescued() {
        try {
            val text = Txt.s(Res.string.nav_rec_rescued)
            nm().notify(SAVE_FAIL_ID, NotificationCompat.Builder(this, CHANNEL_NAV)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(Txt.s(Res.string.nav_rec_rescued_title))
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build())
        } catch (e: Exception) { Problems.warn("recording", "\"Tour rescued\" notification could not be shown", e) }
        vibrate()
    }

    /** Track points not yet written ([PointQueue]). */
    private val pointQueue = PointQueue()

    /** Writes the waiting points in order; false = some still wait (reported once, loud – [safeDb]). */
    private fun writePoints(): Boolean {
        val waited = pointQueue.size
        val ok = safeDb("point") { pointQueue.flush { db.appendPoint(it.tourId, it.seq, it.seg, it.point, it.accuracyM) }?.let { throw it } }
        if (ok && waited > 1) Problems.info("recording", "${waited - 1} track point(s) written late – none lost")
        if (pointQueue.dropped > 0 && pointQueue.dropped % 1000 == 1) {
            Problems.error("recording", "Storage failed for hours: ${pointQueue.dropped} oldest unwritten point(s) given up")
        }
        return ok
    }

    /** A demo started during this tour: its name gets the "Demo · " prefix (once). */
    private fun markDemoTour() {
        demoMarked = true
        safeDb("demo name") {
            val name = db.getTour(tourId)?.name ?: return@safeDb
            if (!name.startsWith(DemoLocation.TOUR_PREFIX)) db.rename(tourId, DemoLocation.TOUR_PREFIX + name)
        }
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
    /** [loadFollowUnguarded]; a broken session file means recording without guidance, never a crash on every restart. */
    private fun loadFollow() {
        try { loadFollowUnguarded() } catch (e: Exception) {
            Problems.error("navigation", "The followed route could not be loaded; recording goes on without guidance", e)
            navigator = null
            alertKey = null; nm().cancel(ALERT_ID)
        }
    }

    private fun loadFollowUnguarded() {
        cancelReroute()
        follow = Prefs(this).loadFollow()
        val line = follow?.second?.takeIf { it.size >= 2 }
        val stored = sessionStore.load()
        val old = navigator
        var session = when {
            line == null -> null
            stored != null && stored.active.size == line.size -> stored
            else -> {
                val prefs = Prefs(this)
                // the file's token: this session replaces it (never "an older session" for mayOverwrite)
                NavSession(NavKind.TRACK, follow?.first ?: Txt.s(Res.string.nav_route_fallback), prefs.activity, prefs.sac, line,
                    token = stored?.token ?: 0L)
            }
        }
        // the plan edited mid-tour ("Continue navigation"): the rider has been on the route already – off the edited
        // line is off route as before, never "the route starts 3 km away" (a newly followed track starts afresh);
        // never reached but led there ("Guide me", Bug 02): the edited plan is led to again at once
        if (session != null) {
            val carried = NavSession.afterPlanEdit(old?.session, session)
            if (carried != session) { session = carried; sessionStore.save(carried) }
        }
        // the walking direction carries over to the new line (plan edited mid-walk: the way back of a round trip)
        navigator = session?.let { Navigator(it).apply { setNavigating(navigatingApplied); old?.let { o -> detector.adoptHeading(o.detector) } } }
        applySettings()
        timeLine()
        // arrived belongs to one followed line: a re-route keeps the token, the app's new line clears it
        arrived = arrivedToken != null && navigator?.session?.token == arrivedToken
        if (!arrived) arrivalGate.reset()
        voice.lineFromApp() // a plan edited while off route: no false "back on the route" (review recording L1)
        alertKey = null; nm().cancel(ALERT_ID)
        navigator?.let { n ->
            if (n.session.needsLead) {
                Problems.trace("navigation", "Plan edited on the way there (\"Guide me\"): leading to the edited plan again")
                handle(n.leadAgain(old?.lastPosition))
            }
        }
    }

    private fun applySettings() {
        val cfg = settings ?: Prefs(this).settings.also { settings = it }
        navigator?.let {
            it.offRouteM = com.onpath.app.navigation.OffRouteDetector.offRouteFor(cfg.offRouteM.toDouble(),
                hike = it.session.activity == com.onpath.app.routing.Activity.HIKE)
            it.enabled = cfg.offRouteWarning
        }
    }

    private fun checkOffRoute(p: TrackPoint, accuracyM: Double, nowEl: Long) {
        val nav = navigator ?: return
        handle(nav.onFix(OffRouteDetector.Fix(p.lat, p.lon, accuracyM, nowEl)), publishNow = false)
    }

    /** Carries out what the navigator asks for. */
    private fun handle(step: Navigator.Step, publishNow: Boolean = true) {
        val nav = navigator ?: return
        // turn hints are either / or for every activity (owner 2026-11-01): with the voice on, the off-route warning is
        // spoken, not vibrated too (the vibration stays while the voice was never heard – the phone setup check)
        // a re-route that finishes while in Plan does not vibrate (owner 2026-11-03: Plan is silent)
        if (GuidanceGate.rerouteVibrates(step.vibrate, planQuiet()) &&
            (settings ?: Prefs(this).settings).offRouteVibrate && !voiceOn()) vibrate()
        if (step.cancelJob) cancelReroute()
        step.job?.let { startReroute(it) }
        // the app chose a new line meanwhile (its FOLLOW_CHANGED is on the way): this result belongs to the old session
        if ((step.lineChanged || step.sessionChanged) &&
            !NavSession.mayOverwrite(sessionStore.load()?.token, nav.session.token)) {
            Problems.trace("navigation", "Re-route result of an older line dropped: the app follows a new line")
            loadFollow()
            publish(force = true)
            return
        }
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
            // field 2026-11-01: an exception here (right after the start: far start / "Guide me" re-route) killed the
            // process mid-tour; reported, the tour goes on with the line it has
            try { navigator?.let { handle(it.onResult(job.id, out)) } } catch (e: Exception) {
                Problems.error("navigation", "Re-route result could not be applied; the tour goes on", e)
            }
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

    /**
     * Before a junction of the followed line: 1 pulse = left, 2 = right ([JunctionCues]), per the 3-way setting. Only
     * where one could go wrong, timed by the speed of [loc] ([com.onpath.app.navigation.SpeedWindow]; bike and on foot).
     */
    private fun junctionCue(loc: Location, nowEl: Long) {
        val st = navigator?.state ?: return
        junctionSpeeds.note(if (loc.hasSpeed()) loc.speed.toDouble() else null, nowEl)
        junctions.bike = navigator?.session?.activity != com.onpath.app.routing.Activity.HIKE
        // Plan is silent (owner 2026-11-03), also with the screen off: the voice follows without a word (stops passed,
        // off route, no GPS) so nothing is said late after "Navigation fortsetzen"; no junction vibration
        if (GuidanceGate.junction(planQuiet()) == GuidanceGate.Junction.FOLLOW_SILENTLY) { voiceStep(st, nowEl, speak = false); return }
        voiceStep(st, nowEl)
        if (st.version != junctionsTracedVersion) {
            junctionsTracedVersion = st.version
            val n = com.onpath.app.navigation.JunctionCues.count(st.line)
            val rb = st.line.count { it.turn == com.onpath.app.routing.JunctionCue.ROUNDABOUT }
            val worth = com.onpath.app.navigation.JogRule.needed(st.line, hike = !junctions.bike).count { it }
            Problems.trace("navigation", "Followed line v${st.version}: $n junctions, $rb roundabouts, $worth worth a " +
                (if (junctions.bike) "bike" else "hike") + " cue" +
                (if (n == 0) " (GPX track or straight line: no junction cues)" else ""))
        }
        val onRoute = st.prompt?.offRoute != true && st.shownAlongM != null // a switch question is no reason to be quiet
        val cue = junctions.update(st.line, st.version, st.shownAlongM, onRoute, junctionSpeeds.speedOrNull(nowEl)) ?: return
        // either – or (owner 2026-11-01): the voice tells the turn, else the vibration (also while the voice is not set up)
        val guide = (settings ?: Prefs(this).settings).junctionGuide
        val vib = guide == com.onpath.app.data.JunctionGuide.VIBRATION || (guide == com.onpath.app.data.JunctionGuide.VOICE && !voiceOn())
        if (vib) {
            val ok = vibrate(com.onpath.app.navigation.JunctionCues.pattern(cue.side), "junction")
            Problems.trace("navigation", "Junction cue: ${cue.name} – vibrated=$ok (turn hints $guide)")
        } else Problems.trace("navigation", "Junction cue: ${cue.name} – not vibrated (turn hints $guide)")
    }

    /** Spoken directions on for this tour's activity, and the setup's test sentence was heard. */
    private fun voiceOn(): Boolean {
        val cfg = settings ?: Prefs(this).settings
        return cfg.junctionGuide == com.onpath.app.data.JunctionGuide.VOICE && Prefs(this).voiceHeard == com.onpath.app.setup.Felt.YES
    }

    /** The speaker, started on first use (then the first sentence comes without delay); what it finds goes to the phone check. */
    private fun voiceSpeaker(): Speaker? = speaker ?: Speaker(this).also { s ->
        speaker = s
        s.onState = { st -> Prefs(this).voiceStatus = Speaker.status(st) }
        s.start()
    }

    /** One step of the spoken directions ([com.onpath.app.navigation.VoiceGuide]) for this fix / tick. */
    private fun voiceStep(st: NavState, nowEl: Long, speak: Boolean = GuidanceGate.voiceSpeaks(planQuiet())) {
        if (!GuidanceGate.voiceSteps(voiceOn(), arrived && !voice.arrivalPending())) return
        try {
            val phrase = voice.update(voiceInput(st, nowEl))
            saveVoice()
            if (phrase != null && speak) voiceSpeaker()?.say(com.onpath.app.navigation.VoiceText.of(phrase))
        } catch (e: Exception) {
            Problems.warn("voice", "Spoken direction skipped", e)
        }
    }

    /** What the voice gets for this fix / tick ([com.onpath.app.navigation.VoiceGuide.Input]). */
    private fun voiceInput(st: NavState, nowEl: Long): com.onpath.app.navigation.VoiceGuide.Input {
        val s = navigator?.session
        return com.onpath.app.navigation.VoiceGuide.Input(
            nowMs = nowEl, line = st.line, version = st.version, alongM = st.shownAlongM,
            onRoute = st.prompt?.offRoute != true && st.shownAlongM != null, speedMps = junctionSpeeds.speedOrNull(nowEl),
            bike = s?.activity != com.onpath.app.routing.Activity.HIKE, offRoute = st.prompt?.offRoute == true,
            rerouted = s?.rerouted == true, paused = paused, gpsLost = gpsLost(),
            stops = s?.stops.orEmpty(), visited = s?.visited.orEmpty(), firstNumber = s?.firstNumber ?: 1,
            skipped = s?.skipped.orEmpty(),
            toGoS = tripTime().toGoS, quiet = standingStill(),
            turnRound = st.uTurnAt != null,
            fixAgeMs = voiceFixAgeMs,
            speechStartMs = speaker?.startMs ?: com.onpath.app.navigation.VoiceGuide.SPEECH_START_MS,
            here = voiceHere, headingDeg = voiceHeading,
        )
    }

    /**
     * The fix as the voice needs it: its age when it came (the rider is that much further on – owner field ride
     * 2026-11-07, "Jetzt abbiegen" too late), the position and the heading of the last metres ridden (the goal's side).
     */
    private fun voiceFix(loc: Location, here: GeoPoint) {
        val fixNs = loc.elapsedRealtimeNanos
        voiceFixAgeMs = if (fixNs > 0) ((SystemClock.elapsedRealtimeNanos() - fixNs) / 1_000_000L).coerceAtLeast(0) else 0
        voiceHere = here
        voiceHeading = com.onpath.app.navigation.GoalSide.heading(track.lastOrNull().orEmpty(), here,
            if (loc.hasSpeed()) loc.speed.toDouble() else null)
    }

    /** What the voice said this tour goes with the tour ([TRIP_FILE]): written when it changed ("Los geht's" once). */
    private fun saveVoice() { if (voice.memory() != voiceSaved) saveTrip() }

    // ---- heights above sea level ([SeaLevel]) ----
    @Volatile private var geoid: SeaLevel.Offset? = null
    @Volatile private var geoidJob = false
    private var firstFixElapsed = 0L
    private val geoidExec by lazy { Executors.newSingleThreadExecutor() }

    private fun seaLevelHeight(loc: Location, nowEl: Long): Double? {
        if (firstFixElapsed == 0L) firstFixElapsed = nowEl
        val ell = if (loc.hasAltitude()) loc.altitude else null
        val msl = if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) loc.mslAltitudeMeters else null
        if (ell != null && msl == null && Build.VERSION.SDK_INT >= 34 && !geoidJob && SeaLevel.needsOffset(geoid, loc.latitude, loc.longitude)) {
            geoidJob = true
            val copy = Location(loc)
            // the converter reads its geoid data from disk: never on the main thread
            geoidExec.execute {
                try {
                    android.location.altitude.AltitudeConverter().addMslAltitudeToLocation(applicationContext, copy)
                    if (copy.hasMslAltitude()) {
                        geoid = SeaLevel.Offset(copy.latitude, copy.longitude, copy.altitude - copy.mslAltitudeMeters)
                        Problems.trace("gps", "Geoid offset here: ${"%.1f".format(geoid!!.metres)} m (heights now above sea level)")
                    }
                } catch (e: Exception) {
                    Problems.warn("gps", "Sea-level height not available; heights stay as the GPS gives them", e)
                } finally { geoidJob = false }
            }
        }
        return SeaLevel.height(ell, msl, geoid, loc.latitude, loc.longitude, nowEl - firstFixElapsed)
    }


    /** Plays [pattern] through [Cues]; false when the phone has no vibrator or it failed (reported). */
    private fun vibrate(pattern: LongArray = OFF_ROUTE_VIBRATION, what: String = "alert"): Boolean = Cues.vibrate(this, pattern, what)

    /**
     * The off-route notification carries the same question as the screen ("Take new route" / "Keep original").
     * Ongoing: it stays until answered or until the user is back on the route. Posted with sound once per
     * off-route event (our own vibration comes with it), then updated silently. No double question (owner 2026-10-31):
     * while the app is on screen its box asks – the notification then goes on the quiet navigation channel (lock
     * screen, no heads-up); a heads-up only when the app is not on screen ([TourChannels.forQuestion]). A change of
     * visibility re-posts it on the other channel (still only alerting once).
     */
    private fun updateAlert() {
        val nav = navigator
        // Plan is silent (owner 2026-11-03): an open question has no notification there; Navigate asks again
        val pr = nav?.prompt?.takeIf { GuidanceGate.offRouteNotification(planQuiet(), paused) }
        if (pr == null) {
            if (alertKey != null) { alertKey = null; nm().cancel(ALERT_ID) }
            return
        }
        val hint = NavText.toRoute((nav ?: return).toRoute)
        // never on the route yet (owner 2026-10-31): the far start is calm – always the quiet navigation channel
        val channel = if (pr.offRoute) TourChannels.forQuestion(AppVisibility.visible) else TourChannels.NAVIGATION
        val key = when (pr) {
            is NavPrompt.Question -> "q${pr.id}"
            is NavPrompt.FarStart -> "f${pr.id}${if (pr.searching) "s" else ""}"
            is NavPrompt.LeadFailed -> "e${pr.id}"
            is NavPrompt.MidStart -> "m${pr.id}${if (pr.searching) "s" else ""}"
            else -> pr.javaClass.simpleName
        } + "@" + channel
        val now = SystemClock.elapsedRealtime()
        if (key == alertKey && now - alertPosted < 15_000) return
        alertKey = key; alertPosted = now
        try {
            val text = when (pr) {
                is NavPrompt.Question -> NavText.question(pr)
                is NavPrompt.FarStart, is NavPrompt.LeadFailed, is NavPrompt.MidStart -> NavText.state(pr)
                else -> hint ?: follow?.first?.let { Txt.s(Res.string.nav_back_to, it) } ?: Txt.s(Res.string.nav_back_to_route)
            }
            val b = NotificationCompat.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(NavText.title(pr))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(if (channel == TourChannels.QUESTION) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // answerable on the lock screen
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent())
            // an activity switch was asked for on the screen: no sound (the user is looking), the question stays
            if (pr is NavPrompt.Question && pr.switch != null) b.setSilent(true)
            // never on the route yet (owner 2026-10-31): a calm question – no sound, no vibration, no popup
            if (!pr.offRoute) b.setSilent(true).setPriority(NotificationCompat.PRIORITY_DEFAULT)
            if (pr is NavPrompt.FarStart && !pr.searching) {
                b.addAction(0, NavText.LEAD, actionIntent(ACTION_LEAD_THERE, 13) { putExtra(EXTRA_QUESTION, pr.id) })
                b.addAction(0, NavText.JUST_RECORD, actionIntent(ACTION_JUST_RECORD, 14) { putExtra(EXTRA_QUESTION, pr.id) })
            } else if (pr is NavPrompt.MidStart && !pr.searching) {
                // "Zu ① führen" / "Hier einsteigen" (owner 2026-11-03), answerable on the lock screen like the far start
                b.addAction(0, NavText.MID_LEAD, actionIntent(ACTION_LEAD_THERE, 16) { putExtra(EXTRA_QUESTION, pr.id) })
                b.addAction(0, NavText.MID_JOIN, actionIntent(ACTION_JOIN_HERE, 17) { putExtra(EXTRA_QUESTION, pr.id) })
            } else if (pr is NavPrompt.LeadFailed) {
                b.addAction(0, Txt.s(Res.string.common_ok), actionIntent(ACTION_LEAD_FAILED_OK, 15) { putExtra(EXTRA_QUESTION, pr.id) })
            } else if (pr is NavPrompt.Question && pr.via != null) {
                // New route (via the stop) · Without stop · Keep original (one question shape, owner 2026-11-01). Three actions
                // share one row and Android cuts long labels: the short skip label, the text names the stop.
                b.addAction(0, NavText.TAKE, actionIntent(ACTION_TAKE_VIA, 12) { putExtra(EXTRA_QUESTION, pr.id) })
                b.addAction(0, NavText.SKIP_SHORT, actionIntent(ACTION_TAKE_ROUTE, 10) { putExtra(EXTRA_QUESTION, pr.id) })
                b.addAction(0, NavText.KEEP, actionIntent(ACTION_KEEP_ROUTE, 11) { putExtra(EXTRA_QUESTION, pr.id) })
            } else if (pr is NavPrompt.Question) {
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
        pausedSinceMs = if (p) System.currentTimeMillis() else null
        if (!p) { seg++; last = null; moveWatch.start(SystemClock.elapsedRealtime()); gpsWatchdog.resumed(SystemClock.elapsedRealtime()) } // resume = new segment, distance not bridged
        // Battery: a pause records nothing – GPS runs slowly (never off (owner): walking on with the pause still on
        // is noticed by PauseWatch), the wake lock rests until Resume.
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
        updateAlert() // "Pause heißt Ruhe": an open navigation question's notification goes now and comes back after Weiter
        safeDb("stats") { db.updateStats(tourId, distance, ascent, descent, elapsedS.toDouble(), "active") }
        publish(force = true)
    }

    private fun finish() {
        try { com.onpath.app.cells.AppCells.get(this).apply { tourInBackground = false; holdUpdates = false } } catch (e: Exception) {
            Problems.warn("cells", "Map areas loaded during the tour not released; they join at the next app start", e)
        }
        if (tourId >= 0) {
            if (pointQueue.size > 0) writePoints() // the last waiting points, if the storage is back
            climb.flush().let { (up, down) -> ascent += up; descent += down }
            saveStats("done")
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
                    .put(tourId, tourTimes().copy(startMs = tourMeta?.second, endMs = System.currentTimeMillis()))
                // learning per tour only (owner): this tour's pace ends with it
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
        if (saverRegistered) try { unregisterReceiver(saverReceiver); saverRegistered = false } catch (e: Exception) {
            Problems.warn("recording", "Battery saver watch not removed", e)
        }
        // Process stays consistent: points are already in the DB; state stays "active"
        // unless finish() ran, so the app offers Continue / Save next time.
        cancelReroute()
        scope.cancel()
        routeExec.shutdown()
        speaker?.stop(); speaker = null
        try { terrain?.close() } catch (e: Exception) { Problems.warn("elevation", "Terrain file not closed", e) }
        terrain = null
        tracker.stop()
        motion.listen(false)
        wakeLock?.let { if (it.isHeld) it.release() }
        if (tourId >= 0) {
            if (pointQueue.size > 0) writePoints()
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
            navigator?.state, tripTime(), saveFailed, hadGpsFix = lastGoodFixElapsed > 0, gpsQuiet = gpsQuiet(),
            gpsStill = gpsLost() && noGpsLine() == NoGpsShow.Line.NONE,
            arrived = arrived, pausedSinceMs = pausedSinceMs,
        )
        // Battery: the clock runs by itself in the notification (chronometer); re-post only when what it says
        // changes (status, shown distance), at most every 5 s, and once a minute to keep the clock in step.
        val now = SystemClock.elapsedRealtime()
        navText = if (RecNotifText.showRouteLines(planQuiet())) navNotification() else null
        val key = notifStatus() + Format.distance(distance) + (follow?.first ?: "") + (navText?.let { it.title + it.text } ?: "") +
            tourPost().channel + planQuiet()
        // following: what the lock screen shows (next junction, time to go) changes often – at most every 10 s
        val minGap = if (navText != null) 10_000 else 5000
        if (force || (now - lastNotify > minGap && key != lastNotifyKey) || now - lastNotify > 60_000) {
            lastNotify = now
            lastNotifyKey = key
            try { nm().notify(tourPost().id, buildNotification()) } catch (e: SecurityException) {
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

    /** "Estimate paused" is never silent (owner): reported once per pause in Settings → Diagnostics. */
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

    /** The tour's stored activity; nothing stored → Hike, an unknown name → Hike with a warning (audit 2026-11-07). */
    private fun activityOf(name: String?) =
        com.onpath.app.debug.enumOr(name, com.onpath.app.routing.Activity.HIKE, "recording")

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
            val m = voice.memory()
            val o = org.json.JSONObject().put("tour", tourId).put("trip", trip.toJson()).putOpt("arrivedToken", arrivedToken)
                .put("voice", org.json.JSONObject()
                .put("started", m.started).putOpt("offRoute", m.offRouteSaidMs).putOpt("noGps", m.noGpsSaidMs)
                .putOpt("arrived", m.arrivedSaidMs))
            val f = File(filesDir, TRIP_FILE)
            AtomicFiles.writeText(f, o.toString())
            voiceSaved = m
        } catch (e: Exception) { Problems.error("time", "Trip pace file could not be saved (break time may be lost on restart)", e) }
    }

    private fun loadTrip() {
        val prior = priorFor(recActivity)
        trip = try {
            val o = org.json.JSONObject(File(filesDir, TRIP_FILE).readText())
            if (o.optLong("tour", -1) == tourId) {
                arrivedToken = if (o.has("arrivedToken")) o.optLong("arrivedToken") else null
                o.optJSONObject("voice")?.let { v ->
                    fun ms(k: String) = if (v.has(k)) v.optLong(k) else null
                    val m = com.onpath.app.navigation.VoiceGuide.Memory(v.optBoolean("started"), ms("offRoute"), ms("noGps"), ms("arrived"))
                    voice.continueTour(m); voiceSaved = m
                    if (m.started) Problems.trace("voice", "Tour continued after a restart: the start sentence was said already")
                }
                com.onpath.app.time.TripPace.fromJson(o.optJSONObject("trip"), prior)
            } else com.onpath.app.time.TripPace(prior)
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
                junctions.nextAheadText(st.line, st.version, along), toGo,
                t?.toGoS?.takeIf { ok }, t?.arrivalMs?.takeIf { ok },
                distance = { Format.distance(it) }, duration = { Format.duration(it) })
        }
    } catch (e: Exception) {
        Problems.warn("recording", "Lock-screen route info not built; plain recording notification", e)
        null
    }

    /** The status word (app language); NavNotification compares it with nav_status_recording. */
    private fun notifStatus() = when {
        paused -> Txt.s(Res.string.nav_status_paused)
        // lying still by the motion sensor: nothing about GPS here either (owner 2026-11-02: "wenn das Handy still liegt
        // … sollte es auch nicht anzeigen") – the notification says "Recording" as usual
        // in Plan nothing about GPS either (owner 2026-11-06 23:35) – Navigate says it again
        tourId >= 0 && NoGpsShow.notifSearching(gpsLost(), noGpsLine(), planQuiet()) -> Txt.s(Res.string.nav_status_searching_gps)
        navigator?.prompt?.offRoute == true -> Txt.s(Res.string.nav_off_route)
        else -> Txt.s(Res.string.nav_status_recording)
    }

    /**
     * Following a route: the "navigation" channel (shown on the lock screen, still silent); a plain recording: the
     * quiet "recording" channel. Same id either way – a switch is an update, the service keeps running.
     */
    private fun tourPost() = TourChannels.forTour(followingRoute = navigator != null && routeChannelOk)
    /** The "navigation" channel exists (a missing channel would make startForeground throw). */
    private var routeChannelOk = false

    private fun buildNotification(): Notification {
        val status = notifStatus()
        val b = NotificationCompat.Builder(this, tourPost().channel)
            .setSmallIcon(R.drawable.ic_notification)
            // running: distance + a system-run clock (no re-post every second); paused: the clock stands still
            .setContentTitle(if (paused) "${Format.distance(distance)} · ${Format.clock(elapsedS)}" else Format.distance(distance))
            .setShowWhen(!paused)
            .setUsesChronometer(!paused)
            .setWhen(System.currentTimeMillis() - elapsedS * 1000)
            .setContentText(RecNotifText.line(status, follow?.first, planQuiet(),
                { s, name -> Txt.s(Res.string.nav_notif_following, s, name) }, Txt.s(Res.string.nav_notif_paused_in_plan)))
            // readable on the lock screen (owner); no button here ends a tour
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
        // No "Stop & save" here (owner: ending a tour must be deliberate) – one tap on the lock screen or
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
        const val ACTION_TAKE_VIA = "com.onpath.app.TAKE_VIA"
        /** Never on the route yet (owner 2026-10-31): "Route starts 31 km away" – Guide me / Just record; OK on "no way". */
        const val ACTION_LEAD_THERE = "com.onpath.app.LEAD_THERE"
        const val ACTION_JUST_RECORD = "com.onpath.app.JUST_RECORD"
        const val ACTION_LEAD_FAILED_OK = "com.onpath.app.LEAD_FAILED_OK"
        const val ACTION_JOIN_HERE = "com.onpath.app.JOIN_HERE"
        const val EXTRA_QUESTION = "question"
        /** Activity check: a proposed session for another activity is in ActivityCheck.PROPOSAL_FILE ([EXTRA_TIME_S] = its time). */
        const val ACTION_PROPOSE_SWITCH = "com.onpath.app.PROPOSE_SWITCH"
        const val EXTRA_TIME_S = "timeS"
        /** With ACTION_FOLLOW_CHANGED ("Continue navigation"): check the next fix at once and ask when off route. */
        const val EXTRA_CHECK_NOW = "checkNow"
        /** Speak an app event ([EXTRA_SAY] = [SAY_ARRIVED] / [SAY_NO_DESTINATION]) when spoken directions are on. */
        const val ACTION_SAY = "com.onpath.app.SAY"
        const val EXTRA_SAY = "say"
        const val SAY_ARRIVED = "arrived"
        const val SAY_NO_DESTINATION = "noDestination"
        /** "Continue navigation" with the same session: check the next fix at once. */
        const val ACTION_CHECK_NOW = "com.onpath.app.CHECK_NOW"
        const val EXTRA_CONTINUE_ID = "continueId"
        const val EXTRA_ACTIVITY = "activity"
        /** T level (SAC 1..5) the tour is recorded at; missing = the stored one ([Prefs.sac]). */
        const val EXTRA_SAC = "sac"

        /** T level of a new recorded tour: the one sent with the start ([extra] 1..5), else the [stored] one. */
        fun newTourSac(extra: Int, stored: Int): Int =
            com.onpath.app.data.LastActivity.sac(extra.takeIf { it in 1..5 }, stored)

        const val CHANNEL_REC = TourChannels.RECORDING
        /** Old off-route channel (vibrated by itself); deleted, see [CHANNEL_NAV]. */
        private const val CHANNEL_ALERT_OLD = "offroute"
        /** Off-route question: high importance with sound, no channel vibration (the service vibrates once itself). */
        const val CHANNEL_NAV = TourChannels.QUESTION
        val OFF_ROUTE_VIBRATION = longArrayOf(0, 400, 200, 400, 200, 600)
        internal const val ALERT_ID = 43
        internal const val PAUSE_ALERT_ID = 44
        /**
         * "Recording cannot be saved" / "Recording stopped" (one slot: the newer problem wins). Its own id (review M1: it
         * shared 44 with the pause reminder, so Pause / Resume cancelled the save warning for good).
         */
        internal const val SAVE_FAIL_ID = 46

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
                NotificationChannel(CHANNEL_REC, Txt.s(Res.string.nav_channel_recording), TourChannels.recordingSpec.importance).apply {
                    description = Txt.s(Res.string.nav_channel_recording_desc)
                }
            )
            // following a route: on the lock screen (importance DEFAULT, Android 14+ hides silent ones there) but
            // never a sound, buzz, light or heads-up popup – TourChannels.navigationSpec
            val nav = TourChannels.navigationSpec
            nm.createNotificationChannel(
                NotificationChannel(nav.id, Txt.s(Res.string.nav_channel_navigation), nav.importance).apply {
                    description = Txt.s(Res.string.nav_channel_navigation_desc)
                    if (!nav.sound) setSound(null, null)
                    enableVibration(nav.vibration)
                    if (!nav.vibration) vibrationPattern = null
                    enableLights(nav.lights)
                    if (nav.lockScreenPublic) lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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
