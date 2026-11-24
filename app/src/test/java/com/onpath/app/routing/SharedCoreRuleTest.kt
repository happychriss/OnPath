package com.onpath.app.routing

import com.onpath.app.testkit.Sources
import com.onpath.app.testkit.sourceFile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Keeps the app's logic free of Android (owner: an iPhone version may come later, with one shared Kotlin
 * core – docs/design/multiplatform.md). The border of the module system guards what lives in `:core` (a plain JVM module:
 * the compiler and Animal Sniffer fail on android / maplibre; org.json and the BRouter jars are compile-only there), so the
 * old "no android / maplibre / btools / org.json in routing, navigation, time, rides, gpx, sync, setup" test is gone with the
 * move (umbau phase 1, piece D). What stays: the files of those packages (and the web-shared ones) that are still in `:app`.
 */
class SharedCoreRuleTest {
    /** Files of the shared-logic packages that still live in `:app` (package/name) – they must stay free of the platform. */
    private val appSide = listOf("map/ActivityLayers.kt", "sync/PlaceSync.kt", "rides/RideApply.kt")
    /** Files the old rule listed, in either module (the TourSplit test below reads them as code shared with the web). */
    private val files = appSide + listOf(
        "routing/PlanResult.kt", "routing/ProfileData.kt", "domain/PlanGaps.kt", "domain/RouteWarnings.kt", "data/Geo.kt",
        "data/RoutingDataNotice.kt", "routing/BreakdownRules.kt")
    private val platform = Regex("""\b(android|androidx|org\.maplibre|btools|org\.json)\.""")

    @Test fun sharedLogicStillInTheAppStaysFreeOfAndroid() {
        val hits = appSide.flatMap { key ->
            val f = sourceFile(key)
            if (!f.isFile) listOf("$key: missing – update SharedCoreRuleTest.appSide (moved to :core? then drop it)")
            else f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*") || !platform.containsMatchIn(code)) null else "$key:${i + 1}: $code"
            }
        }
        assertEquals("Android / MapLibre / BRouter / org.json in shared logic – put it behind an interface in the app " +
            "layer instead (docs/design/multiplatform.md):\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }

    /**
     * Shared with the web planner's engine (TeaVM, `web/engine/build.gradle.kts` compiles the :core folder + app/web-shared-files.txt – owner: plans
     * and routes identical in browser and app): besides no Android, no reflection and no java.security (TeaVM has
     * neither; hashes use sync/Sha256). The engine build fails on java.security in code it reaches (checked 2026-11-19) but not on reflection (TeaVM accepts
     * Class.forName / getDeclared* at build time) and not at all in a file it does not reach – so this list stays by name.
     * Add a file here when WebApi reaches it.
     */
    private val webShared = listOf("gpx/PlanGpx.kt", "gpx/GpxWriter.kt", "gpx/SportType.kt", "routing/PlanPoints.kt",
        "routing/PlanEdits.kt", "routing/RoutingFingerprint.kt", "sync/SyncCrypto.kt", "data/RoutingDataNotice.kt",
        "routing/BreakdownRules.kt", "routing/LegCache.kt",
        // the BRouter adapter and its helper in BRouter's package (island retry without reflection, docs/design/web-planner.md §22)
        "routing/BRouterEngine.kt", "../../../btools/router/OnPathHints.java")
    private val notInTeaVm = Regex(
        """::class\.java|\.getClass\(\)|Class\.forName|kotlin\.reflect|java\.lang\.reflect|getDeclared|[sg]etAccessible|isAccessible|java\.security""")

    @Test fun webSharedFilesStayTeaVmFriendly() {
        val hits = webShared.flatMap { key ->
            val f = sourceFile(key)
            if (!f.isFile) listOf("$key: missing – update SharedCoreRuleTest.webShared")
            else f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*") || !notInTeaVm.containsMatchIn(code)) null else "$key:${i + 1}: $code"
            }
        }
        assertEquals("reflection / java.security in a file the web planner compiles:\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }

    /**
     * The position split of a running tour (navigation/TourSplit, docs/design/tour-edit-position.md §6) is the app's
     * only: the code the web planner compiles must not reach it – plans are computed and edited the same in both.
     */
    @Test fun theTourSplitStaysOutOfTheCodeSharedWithTheWeb() {
        val shared = listOf("routing", "gpx", "sync", "time").flatMap { p ->
            Sources.kotlinFiles(p).map { "$p/${it.name}" }
        } + files + webShared + listOf("navigation/JunctionCues.kt", "navigation/CueRule.kt", "navigation/SpeedWindow.kt")
        val hits = shared.distinct().filter { k -> sourceFile(k).let { it.isFile && it.readText().contains("TourSplit") } }
        assertEquals("the tour's position split in code the web planner shares:\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }
}
