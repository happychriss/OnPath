package com.onpath.app

import android.app.Application
import android.util.Log
import com.onpath.app.debug.MapLibreLogLevel
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
        com.onpath.app.debug.Health.start() // a health line every 10 min in the event log (perf report 2026-10-22)
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
     * MapLibre also reports harmless internal notes via Logger.e/w; [MapLibreLogLevel] decides the level, so only
     * errors that break the map are announced on screen.
     */
    private fun installMapLibreLogger() {
        Logger.setLoggerDefinition(object : LoggerDefinition {
            override fun v(tag: String, msg: String) {}
            override fun v(tag: String, msg: String, tr: Throwable?) {}
            override fun d(tag: String, msg: String) {}
            override fun d(tag: String, msg: String, tr: Throwable?) {}
            override fun i(tag: String, msg: String) { Log.i(tag, msg) }
            override fun i(tag: String, msg: String, tr: Throwable?) { Log.i(tag, msg, tr) }
            override fun w(tag: String, msg: String) = report(MapLibreLogLevel.Priority.WARN, tag, msg, null)
            override fun w(tag: String, msg: String, tr: Throwable?) = report(MapLibreLogLevel.Priority.WARN, tag, msg, tr)
            override fun e(tag: String, msg: String) = report(MapLibreLogLevel.Priority.ERROR, tag, msg, null)
            override fun e(tag: String, msg: String, tr: Throwable?) = report(MapLibreLogLevel.Priority.ERROR, tag, msg, tr)
        })
        Logger.setVerbosity(Logger.WARN)
    }

    /** Harmless internal notes are INFO, only map-breaking errors are ERROR (announced) – see [MapLibreLogLevel]. */
    private fun report(priority: MapLibreLogLevel.Priority, tag: String, msg: String, tr: Throwable?) =
        Problems.report("map", "MapLibre ($tag): $msg", tr, MapLibreLogLevel.of(priority, tag, msg, tr != null))
}
