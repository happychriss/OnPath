package com.onpath.app.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.onpath.app.debug.Problems
import com.onpath.app.domain.tour.BatteryEstimate
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The battery during a navigated tour (Chriss 2026-11-28, R-10): while a tour follows a route, every battery change goes
 * into a [BatteryEstimate] (a receiver of ACTION_BATTERY_CHANGED – Android sends it at each 1 % step); the estimate's state
 * is [now]. Outside a navigated tour nothing is registered. [askedTour] = the tour whose question was answered (once per
 * tour, kept while the app runs). Never throws: a failure leaves the tour as it is, reported.
 */
object BatteryWatch {
    /** What the question needs: the tour, the level now and the use per hour (null = no estimate yet). */
    data class Now(val tourId: Long?, val level: Int?, val usePerHour: Double?)

    private val _now = MutableStateFlow(Now(null, null, null))
    val now: StateFlow<Now> = _now

    @Volatile var askedTour: Long? = null

    /** „Akku sparen“'s mark ([com.onpath.app.domain.tour.BatterySave.mark]) for the screens. */
    private val _saveMark = MutableStateFlow<Long?>(null)
    val saveMark: StateFlow<Long?> = _saveMark
    private var prefs: com.onpath.app.data.Prefs? = null

    /** The switch in ⚙ (by hand or after the question): for the running tour, or the next one (Chriss 2026-11-28 12:55). */
    fun setSaving(on: Boolean) = store(com.onpath.app.domain.tour.BatterySave.switchTo(on, com.onpath.app.domain.tour.BatterySave.runningTour))

    private fun store(mark: Long?) {
        com.onpath.app.domain.tour.BatterySave.mark = mark
        _saveMark.value = mark
        try { prefs?.batterySaveMark = mark } catch (e: Exception) { Problems.warn("battery", "Akku sparen not stored", e) }
        Problems.trace("battery", "Akku sparen: ${mark?.let { if (it == com.onpath.app.domain.tour.BatterySave.NEXT) "next tour" else "tour $it" } ?: "off"}")
    }

    private var estimate = BatteryEstimate()
    private var receiver: BroadcastReceiver? = null

    fun attach(app: Context) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
            Problems.warn("battery", "Battery estimate: the tour is not followed any more", e)
        })
        // „Akku sparen“ holds for one tour (Chriss 2026-11-28 12:55): stored mark; the running tour binds and ends it – the
        // app's first, empty state is no end (a restart mid-tour finds the same id, R-07)
        try { prefs = com.onpath.app.data.Prefs(app); com.onpath.app.domain.tour.BatterySave.mark = prefs?.batterySaveMark } catch (e: Exception) { Problems.warn("battery", "Akku sparen not read; off", e) }
        _saveMark.value = com.onpath.app.domain.tour.BatterySave.mark
        var prev: Long? = null
        scope.launch { RecordingService.state.map { it?.tourId }.distinctUntilChanged().collect { now ->
            com.onpath.app.domain.tour.BatterySave.runningTour = now
            val m = com.onpath.app.domain.tour.BatterySave.mark
            val next = com.onpath.app.domain.tour.BatterySave.afterTourChange(m, prev, now)
            if (next != m) store(next)
            prev = now
        } }
        scope.launch {
            RecordingService.state.map { s -> s?.takeIf { it.nav != null }?.tourId }.distinctUntilChanged().collect { tour ->
                stop(app)
                if (tour != null) start(app, tour)
            }
        }
    }

    private fun start(app: Context, tour: Long) {
        estimate = BatteryEstimate()
        _now.value = Now(tour, null, null)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = read(i, tour)
        }
        try {
            // the sticky broadcast answers at once with the level now; then one call per change
            read(app.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED)), tour)
            receiver = r
        } catch (e: Exception) {
            Problems.warn("battery", "Battery level not readable – no estimate for this tour", e)
        }
    }

    private fun stop(app: Context) {
        receiver?.let { r -> try { app.unregisterReceiver(r) } catch (e: Exception) { Problems.warn("battery", "Battery receiver not removed", e) } }
        receiver = null
        _now.value = Now(null, null, null)
    }

    private fun read(i: Intent?, tour: Long) {
        try {
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level < 0 || scale <= 0) return
            val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            estimate.level(SystemClock.elapsedRealtime(), level * 100 / scale, charging = plugged)
            _now.value = Now(tour, estimate.level, estimate.usePerHour())
        } catch (e: Exception) {
            Problems.warn("battery", "Battery reading skipped", e)
        }
    }
}
