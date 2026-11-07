package com.onpath.app.data

import android.content.SharedPreferences
import com.onpath.app.debug.Problems
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Settings that may reach the disk a little late (the last camera, "last seen" of the phone check): kept in memory at
 * once, written by ONE background thread with `commit()`, coalesced (the last value per key wins) at most every
 * [delayMs].
 *
 * Why not `apply()`: every pending `apply()` is a disk write Android waits for when the screen stops (QueuedWork in
 * Activity.onStop) and when a service command arrives – the camera is saved at every camera idle, during navigation about
 * once per GPS fix. The Pixel's watchdog saw the main thread blocked 0.5–0.8 s at onStop (2026-11-07). A `commit()` on
 * this thread never enters QueuedWork, so the screen never waits for it.
 *
 * Only for values whose loss on a process kill costs nothing (≤ [delayMs] old). The plan's points stay on `apply()`:
 * their value is visible in memory at once, and the plan must never be lost (R-08).
 */
internal class LazyPrefs(private val sp: SharedPreferences, private val delayMs: Long = DELAY_MS,
                         private val exec: ScheduledExecutorService = sharedExecutor) {
    /** Key → (value as written, how to write it). The value answers reads before it reached the disk. */
    private val pending = LinkedHashMap<String, Pair<Any?, (SharedPreferences.Editor) -> Unit>>()
    private var scheduled = false

    /** Writes this many times (tests, diagnostics). */
    @Volatile var commits = 0
        private set

    /** Keeps [value] for [key] and writes it with [write] within [delayMs] (together with the other pending keys). */
    fun put(key: String, value: Any?, write: (SharedPreferences.Editor) -> Unit) {
        synchronized(pending) {
            pending[key] = value to write
            if (!scheduled) {
                scheduled = true
                exec.schedule({ flushNow() }, delayMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** The value [put] for [key] and not written yet; [NONE] = nothing pending (read the preferences). */
    fun peek(key: String): Any? = synchronized(pending) { if (pending.containsKey(key)) pending[key]!!.first else NONE }

    /** Writes what is pending now – on the background thread; the caller (onStop) never waits. */
    fun flush() { exec.execute { flushNow() } }

    /** Writes what is pending on the calling thread (the background thread; tests). */
    fun flushNow() {
        val ops = synchronized(pending) {
            scheduled = false
            if (pending.isEmpty()) return
            pending.values.map { it.second }.also { pending.clear() }
        }
        try {
            val e = sp.edit()
            ops.forEach { it(e) }
            commits++
            if (!e.commit()) Problems.warn("settings", "Camera / last-seen settings not written (commit failed) – kept from before")
        } catch (e: Exception) {
            Problems.warn("settings", "Camera / last-seen settings not written – kept from before", e)
        }
    }

    companion object {
        const val DELAY_MS = 2_000L
        /** Marker for "nothing pending" ([peek]); null is a real value. */
        val NONE = Any()

        private val sharedExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "onpath-prefs").apply { isDaemon = true }
        }
        private val instances = java.util.WeakHashMap<SharedPreferences, LazyPrefs>()

        /** One writer per preferences file (several [Prefs] objects share Android's one SharedPreferences). */
        fun of(sp: SharedPreferences): LazyPrefs = synchronized(instances) { instances.getOrPut(sp) { LazyPrefs(sp) } }
    }
}
