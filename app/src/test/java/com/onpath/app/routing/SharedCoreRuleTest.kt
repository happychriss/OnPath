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
    private val packages = listOf("routing", "navigation", "time", "rides", "gpx", "sync", "setup")
    /** Single shared files in packages that also hold platform code (package/name; the web planner compiles them). */
    private val files = listOf("search/PlaceIndex.kt", "map/ActivityLayers.kt",
        // the plan result of app and web (routing/PlanResult, docs/handoff-web-planresult.md) and the ui/ rules it calls
        "routing/PlanResult.kt", "routing/ProfileData.kt", "ui/PlanGaps.kt", "ui/RouteWarnings.kt", "data/Geo.kt",
        // the plan sheet's routing-data notice and way-type / surface line (docs/handoff-web-notice-breakdown.md)
        "data/RoutingDataNotice.kt", "routing/BreakdownRules.kt")
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
        fun platformLines(key: String, f: File) = f.readLines().mapIndexedNotNull { i, line ->
            val code = line.substringBefore("//").trim()
            if (code.startsWith("*") || code.startsWith("/*")) null
            else if (platform.containsMatchIn(code)) "$key:${i + 1}: $code" else null
        }
        val hits = packages.flatMap { p ->
            File(root, p).walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                val key = "$p/${f.name}"
                if (key in known) emptyList() else platformLines(key, f)
            }.toList()
        } + files.flatMap { key ->
            val f = File(root, key)
            if (f.isFile) platformLines(key, f) else listOf("$key: missing – update SharedCoreRuleTest.files")
        }
        assertEquals("Android / MapLibre / BRouter / org.json in shared logic – put it behind an interface in the app " +
            "layer instead (docs/design/multiplatform.md):\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }

    /**
     * Shared with the web planner's engine (TeaVM, `web/engine/build.gradle.kts` appFiles on web-spike – owner: plans
     * and routes identical in browser and app): besides no Android, no reflection and no java.security (TeaVM has
     * neither; hashes use sync/Sha256). Add a file here when the web is to compile it.
     */
    private val webShared = listOf("gpx/PlanGpx.kt", "gpx/GpxWriter.kt", "gpx/SportType.kt", "routing/PlanPoints.kt",
        "routing/PlanEdits.kt", "routing/RoutingFingerprint.kt", "sync/SyncCrypto.kt", "data/RoutingDataNotice.kt",
        "routing/BreakdownRules.kt")
    private val notInTeaVm = Regex("""::class\.java|Class\.forName|kotlin\.reflect|java\.lang\.reflect|java\.security""")

    @Test fun webSharedFilesStayTeaVmFriendly() {
        val root = File("src/main/java/com/onpath/app")
        val hits = webShared.flatMap { key ->
            val f = File(root, key)
            if (!f.isFile) listOf("$key: missing – update SharedCoreRuleTest.webShared")
            else f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*") || !notInTeaVm.containsMatchIn(code)) null else "$key:${i + 1}: $code"
            }
        }
        assertEquals("reflection / java.security in a file the web planner compiles:\n" + hits.joinToString("\n"), emptyList<String>(), hits)
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
