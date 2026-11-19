package com.onpath.app.cells

import com.onpath.strings.*
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.onpath.app.MainActivity
import com.onpath.app.R
import com.onpath.app.data.AppRouting
import com.onpath.app.debug.Problems
import com.onpath.app.recording.RecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Map loading goes on with the screen off or the app in the background (owner: putting the phone aside must not stop
 * it). While map areas are requested / built / downloaded (or routing data loads) and
 * no tour runs (RecordingService already keeps the app alive and asks for the map ahead), this small foreground service
 * keeps the process and the server polling going ([MapCells.loadInBackground]) and shows one quiet notification with
 * the progress. It ends by itself when nothing is loading any more, after [MAX_MS], or when a tour starts.
 */
class MapLoadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
        com.onpath.app.debug.problemsHandler("cells", "Unexpected error in the map loading service"))
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // every startForegroundService() must be answered with startForeground()
        try {
            ServiceCompat.startForeground(this, NOTE_ID, note(emptyList()),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: Exception) {
            Problems.warn("cells", "Map loading in the background could not start; it goes on while the app is open", e)
            stopSelf(); return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true
        val cells = AppCells.get(this)
        cells.loadInBackground = true
        try {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OnPath:mapload").apply { acquire(MAX_MS) }
        } catch (e: Exception) { Problems.warn("cells", "No wake lock for map loading; it may pause with the screen off", e) }
        Problems.trace("cells", "Map loading continues in the background (service started)")
        scope.launch {
            val until = System.currentTimeMillis() + MAX_MS
            var idleSince = 0L
            while (isActive) {
                val states = cells.states.value.values
                val busy = states.any(::loading) || routingLoading()
                val now = System.currentTimeMillis()
                if (RecordingService.state.value != null) break // the tour keeps the app alive now
                if (!busy) { if (idleSince == 0L) idleSince = now; if (now - idleSince >= IDLE_STOP_MS) break } else idleSince = 0L
                if (now > until) { Problems.info("cells", "Map loading in the background stopped after ${MAX_MS / 60_000} min"); break }
                try { nm().notify(NOTE_ID, note(states)) } catch (e: Exception) { Problems.warn("cells", "Map loading notification not updated", e) }
                delay(TICK_MS)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) { // Android 15: daily limit for data sync services reached
        Problems.info("cells", "Android stopped map loading in the background (time limit); it goes on when the app is open")
        stopSelf()
    }

    override fun onDestroy() {
        AppCells.peek()?.loadInBackground = false
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        Problems.trace("cells", "Map loading service ended")
        super.onDestroy()
    }

    private fun routingLoading(): Boolean = try { AppRouting.get(this).loading } catch (e: Exception) {
        Problems.warn("cells", "Routing data state unreadable for the loading notification", e); false
    }

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun note(states: Collection<CellState>): Notification {
        val percent = percent(states)
        if (Build.VERSION.SDK_INT >= 26) nm().createNotificationChannel(
            NotificationChannel(CHANNEL, Txt.s(Res.string.maps_notif_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Txt.s(Res.string.maps_load_loading))
            .setContentText(text(states))
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "maps"
        internal const val NOTE_ID = 4711
        /** At most this long in one go (a big area on a slow line). */
        const val MAX_MS = 45 * 60_000L
        private const val TICK_MS = 3_000L
        private const val IDLE_STOP_MS = 6_000L

        /** States that mean "still on its way to the phone". */
        fun loading(s: CellState) = s is CellState.Requested || s is CellState.Queued || s is CellState.Building ||
            s is CellState.Downloading || s is CellState.Installing

        /**
         * The notification's number: the real bytes of the areas downloading now, 0..100 – null while nothing
         * downloads (areas prepared on the server, the downloaded map joined in: [finishing] tells which; the bar then
         * runs without a value – owner 2026-11-18: no number that stands at 99). Pure, unit tested.
         */
        fun percent(states: Collection<CellState>): Int? {
            val dl = states.filterIsInstance<CellState.Downloading>().filter { it.totalBytes > 0 }
            if (dl.isEmpty()) return null
            return (dl.sumOf { it.doneBytes } * 100 / dl.sumOf { it.totalBytes }).toInt().coerceIn(0, 100)
        }

        /** Everything on its way is downloaded and joined into the map now ("almost ready"). */
        fun finishing(states: Collection<CellState>): Boolean =
            states.filter(::loading).let { on -> on.isNotEmpty() && on.all { it is CellState.Installing } }

        /** The notification's line: "34 % – …" while it downloads, else what happens. */
        fun text(states: Collection<CellState>): String = percent(states)?.let { Txt.s(Res.string.maps_notif_progress, it) }
            ?: Txt.s(if (finishing(states)) Res.string.maps_notif_finishing else Res.string.maps_notif_preparing)

        /**
         * Start it when map areas start loading (called from the app while it is on screen – Android does not allow
         * starting it from the background). Not during a tour. Never throws.
         */
        fun ensure(context: Context) {
            if (RecordingService.state.value != null) return
            try {
                val i = Intent(context, MapLoadService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (e: Exception) {
                Problems.info("cells", "Map loading in the background not started (${e.javaClass.simpleName}); it goes on while the app is open")
            }
        }
    }
}
