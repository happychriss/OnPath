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

    /**
     * „Markierten Wegen folgen“ for an engine (Settings → Plan): read from the stored setting at every route, so planning
     * (AppViewModel) and a tour's re-routing (RecordingService) – both engines come from [create] – always route with the
     * same level, and a change counts from the next route on.
     */
    fun markedTrails(context: Context): () -> Int {
        val prefs = com.onpath.app.data.Prefs(context)
        return { prefs.markedTrails }
    }

    private val fingerprints = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /**
     * The routing fingerprint the engines from [create] route with: the shipped profiles ([RoutingFingerprint]) and the
     * stored „Markierten Wegen folgen“ level – the same reader as the engines ([markedTrails]). Read once per level.
     */
    fun fingerprint(context: Context): String = markedTrails(context)().let { t ->
        fingerprints.getOrPut(t) {
            RoutingFingerprint.of(read = { n -> context.applicationContext.assets.open("brouter/$n").use { it.readBytes() } }, trails = t)
        }
    }

    /** The phone's learned router speed ([RouteCost], kept with the wait window's learned shares). */
    fun searchCost(context: Context): SearchCost {
        val memory = com.onpath.app.data.progressMemoryOf(context)
        return SearchCost({ memory.phoneFactor }, { memory.phoneFactor = it })
    }

    fun create(context: Context, segmentsDir: File): RoutingEngine = try {
        // the BRouter classes load here, not first in a request (review routing L7: broken jars showed only per leg and
        // this fallback never ran)
        Class.forName(BROUTER_CLASS); Class.forName(BROUTER_CONTEXT)
        BRouterEngine(context.applicationContext, segmentsDir, markedTrails(context), searchCost(context)).also { isRealRouter = true }
    } catch (e: Throwable) {
        Problems.error("routing", "Offline router (BRouter) could not start; using straight lines", e)
        isRealRouter = false
        StraightLineEngine()
    }
}
