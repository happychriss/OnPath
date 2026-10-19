package com.onpath.app

import android.app.Application
import android.util.Log
import com.onpath.app.debug.Problems
import com.onpath.app.recording.RecordingService
import org.maplibre.android.MapLibre
import org.maplibre.android.log.Logger
import org.maplibre.android.log.LoggerDefinition

class OnPathApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Problems first: everything after this may report into it (debug/Problems.kt).
        Problems.init(filesDir)
        installCrashCapture()
        installMapLibreLogger()
        // Must happen before any MapView is created. No API key / network needed.
        MapLibre.getInstance(this)
        RecordingService.createChannels(this)
    }

    /** A crash is written to the problem log (synchronously) before Android ends the app – shown at the next start. */
    private fun installCrashCapture() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            Problems.report("app", "OnPath stopped unexpectedly (${thread.name})", e, Problems.Level.CRASH)
            previous?.uncaughtException(thread, e)
        }
    }

    /**
     * MapLibre's own warnings and errors – including those of the native map engine (missing glyphs, tiles that
     * cannot be parsed, style problems) – go to the problem log instead of only logcat. These used to be invisible.
     */
    private fun installMapLibreLogger() {
        Logger.setLoggerDefinition(object : LoggerDefinition {
            override fun v(tag: String, msg: String) {}
            override fun v(tag: String, msg: String, tr: Throwable?) {}
            override fun d(tag: String, msg: String) {}
            override fun d(tag: String, msg: String, tr: Throwable?) {}
            override fun i(tag: String, msg: String) { Log.i(tag, msg) }
            override fun i(tag: String, msg: String, tr: Throwable?) { Log.i(tag, msg, tr) }
            override fun w(tag: String, msg: String) = Problems.warn("map", "MapLibre ($tag): $msg")
            override fun w(tag: String, msg: String, tr: Throwable?) = Problems.warn("map", "MapLibre ($tag): $msg", tr)
            override fun e(tag: String, msg: String) = Problems.error("map", "MapLibre ($tag): $msg")
            override fun e(tag: String, msg: String, tr: Throwable?) = Problems.error("map", "MapLibre ($tag): $msg", tr)
        })
        Logger.setVerbosity(Logger.WARN)
    }
}
