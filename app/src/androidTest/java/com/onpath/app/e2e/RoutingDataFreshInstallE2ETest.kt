package com.onpath.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onpath.app.data.RoutingDataNotice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * CORE FEATURE "routing data on a fresh install": no routing data on the phone → set points →
 * the 1° routing parts load by themselves (brouter.de, ~10 MB each) → the plan is computed again and is no longer a straight line;
 * the notice never shows a file name or path. Downloads for real, so it runs only when asked:
 * `-Pandroid.testInstrumentationRunnerArguments.routingDownload=true` (phone with internet; not in CI by default).
 */
@RunWith(AndroidJUnit4::class)
class RoutingDataFreshInstallE2ETest : E2EBase() {

    @Test fun freshInstallWithoutRoutingDataLoadsItAndRoutes() {
        assumeTrue("set routingDownload=true to run (downloads ~10–30 MB)",
            InstrumentationRegistry.getArguments().getString("routingDownload") == "true")
        launch(beforeLaunch = { File(dataDir, "segments").listFiles()?.forEach { it.delete() } })
        startPlanWithTwoPoints()

        // while it loads: straight line + the plain notice (no path, no file name)
        waitUntil("plan notice", 30_000) { error() != null }
        val notice = error()!!
        assertFalse("no file name/path in the notice: $notice", notice.contains(".rd5") || notice.contains("/"))
        assertTrue(notice, RoutingDataNotice.isNotice(notice))

        // routing data arrives (up to 15 min on a slow line), the plan is computed again: a real route
        waitUntil("routing data loaded and plan recalculated", 15 * 60_000L) {
            File(dataDir, "segments").listFiles { f -> f.name.endsWith(".rd5") && f.length() > 1_000_000 }.orEmpty().isNotEmpty() &&
                !routing() && straight() == false
        }
        assertFalse("notice gone after the routing data arrived", RoutingDataNotice.isNotice(error()))
    }

    private fun error(): String? { var e: String? = null; scenario.onActivity { e = vm(it).routeError }; return e }
    private fun routing(): Boolean { var r = false; scenario.onActivity { r = vm(it).routing }; return r }
    private fun straight(): Boolean? {
        var s: Boolean? = null
        scenario.onActivity { s = vm(it).route?.legs?.any { l -> l.straight } }
        return s
    }
}
