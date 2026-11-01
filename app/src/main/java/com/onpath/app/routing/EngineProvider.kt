package com.onpath.app.routing

import android.content.Context
import com.onpath.app.debug.Problems
import java.io.File

/**
 * THE one place that picks the routing engine: the offline BRouter engine.
 * If it cannot be created (e.g. broken jars), falls back to the straight-line stub
 * (StraightLineEngine) so the app never crashes; the UI then says routing is unavailable.
 */
object EngineProvider {
    private const val BROUTER_CLASS = "btools.router.RoutingEngine"
    private const val BROUTER_CONTEXT = "btools.router.RoutingContext"

    /** True when the real offline router is active. */
    var isRealRouter = false
        private set

    fun create(context: Context, segmentsDir: File): RoutingEngine = try {
        // the BRouter classes load here, not first in a request (review routing L7: broken jars showed only per leg and
        // this fallback never ran)
        Class.forName(BROUTER_CLASS); Class.forName(BROUTER_CONTEXT)
        BRouterEngine(context.applicationContext, segmentsDir).also { isRealRouter = true }
    } catch (e: Throwable) {
        Problems.error("routing", "Offline router (BRouter) could not start; using straight lines", e)
        isRealRouter = false
        StraightLineEngine()
    }
}
