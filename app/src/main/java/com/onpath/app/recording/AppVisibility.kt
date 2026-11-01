package com.onpath.app.recording

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Is OnPath on screen (an activity started)? Fed by [OnPathApp]'s activity lifecycle callbacks ([callbacks]); read by
 * the recording service, which must not post the off-route question as a heads-up while the same question is on the
 * screen (owner 2026-10-31: no double question – [TourChannels.forQuestion]). The counting is pure ([Counter], tested).
 */
object AppVisibility {
    /** Started minus stopped activities; on screen while > 0 (a rotation stops after the new one started). */
    class Counter {
        private var started = 0
        val visible: Boolean get() = started > 0
        fun started() { started++ }
        fun stopped() { started = (started - 1).coerceAtLeast(0) }
    }

    private val counter = Counter()

    /** True while one of OnPath's activities is started (on screen, also above the lock screen). */
    @Volatile var visible: Boolean = false
        private set

    val callbacks: Application.ActivityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) { synchronized(counter) { counter.started(); visible = counter.visible } }
        override fun onActivityStopped(activity: Activity) { synchronized(counter) { counter.stopped(); visible = counter.visible } }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
