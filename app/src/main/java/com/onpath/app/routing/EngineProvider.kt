package com.onpath.app.routing

import android.content.Context
import android.util.Log
import java.io.File

/**
 * THE one place that picks the routing engine: the offline BRouter engine.
 * If it cannot be created (e.g. broken jars), falls back to the straight-line stub
 * (StraightLineEngine) so the app never crashes; the UI then says routing is unavailable.
 */
object EngineProvider {

    /** True when the real offline router is active. */
    var isRealRouter = false
        private set

    fun create(context: Context, segmentsDir: File): RoutingEngine = try {
        BRouterEngine(context.applicationContext, segmentsDir).also { isRealRouter = true }
    } catch (e: Throwable) {
        Log.e("OnPath", "BRouterEngine failed to start, using straight lines", e)
        isRealRouter = false
        StraightLineEngine()
    }
}
