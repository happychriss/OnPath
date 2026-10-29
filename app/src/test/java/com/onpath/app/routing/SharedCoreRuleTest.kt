package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Keeps the app's logic free of Android (owner: an iPhone version may come later, with one shared Kotlin
 * core – docs/design/multiplatform.md). Planner, navigation, time, rides, GPX and the plan sync must not use Android, MapLibre,
 * BRouter or org.json, so they can later move into a shared module unchanged. The files below already do and are
 * the known exceptions (each needs an interface or a JSON helper when the split happens); the list may only shrink.
 */
class SharedCoreRuleTest {
    private val packages = listOf("routing", "navigation", "time", "rides", "gpx", "sync")
    private val platform = Regex("""\b(android|androidx|org\.maplibre|btools|org\.json)\.""")

    /** File (package/name) -> why it is allowed today. */
    private val known = mapOf(
        "routing/BRouterEngine.kt" to "the BRouter adapter (Context, btools)",
        "routing/EngineProvider.kt" to "chooses the engine (Context)",
        "navigation/NavSession.kt" to "org.json",
        "time/BreakDetector.kt" to "org.json",
        "time/PlanTimesStore.kt" to "org.json",
        "time/TourTimesStore.kt" to "org.json",
        "time/TripPace.kt" to "org.json",
        "rides/Rides.kt" to "org.json",
    )

    @Test fun coreLogicStaysFreeOfAndroid() {
        val root = File("src/main/java/com/onpath/app")
        val hits = packages.flatMap { p ->
            File(root, p).walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                val key = "$p/${f.name}"
                if (key in known) emptySequence() else f.readLines().asSequence().mapIndexedNotNull { i, line ->
                    val code = line.substringBefore("//").trim()
                    if (code.startsWith("*") || code.startsWith("/*")) null
                    else if (platform.containsMatchIn(code)) "$key:${i + 1}: $code" else null
                }
            }.toList()
        }
        assertEquals("Android / MapLibre / BRouter / org.json in shared logic – put it behind an interface in the app " +
            "layer instead (docs/design/multiplatform.md):\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }

    @Test fun theExceptionListOnlyShrinks() {
        val root = File("src/main/java/com/onpath/app")
        val stale = known.keys.filter { k ->
            val f = File(root, k)
            !f.exists() || f.readLines().none { platform.containsMatchIn(it.substringBefore("//")) }
        }
        assertEquals("these files are clean now – remove them from SharedCoreRuleTest.known", emptyList<String>(), stale)
    }
}
