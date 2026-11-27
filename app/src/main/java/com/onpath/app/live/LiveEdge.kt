package com.onpath.app.live

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.SystemClock
import com.onpath.app.data.TourDb
import com.onpath.app.debug.Problems
import com.onpath.app.plansync.PlanSync
import com.onpath.app.recording.RecordingService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom

/**
 * The live sharing's Android side put together (docs/design/live-tracking.md §14.3): one [LiveLane] per process, made
 * when the process starts (OnPathApp) – so a process the system restarted for a running tour goes on with the stored
 * share, and a tour's last "ended" upload or a "stop sharing" can still go out when the recording service is gone.
 * The recording service itself is not touched and does not know any of this.
 *
 * Everything here is beside the recording: it reads the service's published state and the tour database, and hands
 * work to the lane's own thread. No wake lock, no new permission (`INTERNET` and `ACCESS_NETWORK_STATE` are there).
 */
object LiveEdge {
    private val _snapshot = MutableStateFlow(LiveSnapshot.OFF)
    /** What the app may show of the sharing; only the lane writes it. */
    val snapshot: StateFlow<LiveSnapshot> = _snapshot

    @Volatile private var lane: LiveLane? = null

    /** The process's lane, made at the first call. Never throws: null = live sharing is not available (reported). */
    fun attach(context: Context): LiveLane? = try {
        // cheap on the caller's thread: objects only; the stored share is read on the lane's own thread
        lane ?: synchronized(this) { lane ?: build(context.applicationContext).also { lane = it } }
    } catch (e: Exception) {
        Problems.warn("live", "Live sharing could not be set up; the tour records as always", e)
        null
    }

    private fun build(app: Context): LiveLane {
        val db = TourDb.get(app)
        val random = SecureRandom()
        val track = DbLiveTrack(
            points = object : LivePoints {
                override fun after(tourId: Long, seq: Int, max: Int) = db.livePointsAfter(tourId, seq, max)
                override fun last(tourId: Long) = db.liveLastPoint(tourId)
                override fun tour(tourId: Long) = db.getTour(tourId)?.let { LiveTourInfo(it.createdMs, it.activity, over = it.state != "active" || it.deletedMs != null) }
            },
            state = { RecordingService.state.value },
            batteryPct = { battery(app) },
            now = System::currentTimeMillis,
            plannedStore = LiveFiles(File(app.filesDir, DIR), LiveFiles.PLANNED),
        )
        val uploader = LiveUploader(HttpLiveBoxes(PlanSync.SERVER), track, LiveFiles(File(app.filesDir, DIR)),
            random = { n -> ByteArray(n).also(random::nextBytes) })
        val made = LiveLane(uploader, { RecordingService.state.value }, track::over, SystemClock::elapsedRealtime, System::currentTimeMillis,
            AndroidLiveNetwork(app)) { _snapshot.value = it }
        made.attach()
        // the events the viewer should see soon: only when one of these four changes, never per fix
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Problems.warn("live", "Live sharing: the tour's state is not followed any more", e)
        })
        scope.launch {
            RecordingService.state.map { s -> s?.let { listOf(it.tourId, it.paused, it.offRouteAlert, it.arrived) } }
                .distinctUntilChanged().collect { made.stateChanged() }
        }
        return made
    }

    /** The phone's battery in percent from the sticky broadcast (no receiver stays registered); null = not known. */
    private fun battery(app: Context): Int? = try {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) level * 100 / scale else null
    } catch (e: Exception) {
        Problems.warn("live", "Live sharing: battery level not readable", e)
        null
    }

    const val DIR = "live"
}

/**
 * The app's [LiveShareClient]: straight to the process's live lane – each call only hands a task to the lane's thread.
 * Sharing starts for the tour that records right now; without one nothing happens.
 */
class EdgeLiveShareClient(context: Context) : LiveShareClient {
    private val app = context.applicationContext
    override val live: StateFlow<LiveSnapshot> get() = LiveEdge.snapshot
    override fun liveStart(name: String) { RecordingService.state.value?.tourId?.let { LiveEdge.attach(app)?.start(it, name) } }
    override fun liveStop() { LiveEdge.attach(app)?.stop() }
    override fun liveSeen() { LiveEdge.attach(app)?.acknowledge() }
}

/** "The network is back" from Android's `ConnectivityManager` (no Play Services). */
private class AndroidLiveNetwork(private val app: Context) : LiveNetwork {
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun watch(onBack: () -> Unit) {
        if (callback != null) return
        try {
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onBack()
            }
            cm.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (e: Exception) {
            Problems.warn("live", "Live sharing: network changes not watched; uploads retry by the clock", e)
        }
    }

    override fun unwatch() {
        val cb = callback ?: return
        callback = null
        try { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (e: Exception) {
            Problems.warn("live", "Live sharing: network watch not removed", e)
        }
    }
}
