package com.onpath.app.e2e

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onpath.app.BuildConfig
import com.onpath.app.MainActivity
import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellState
import com.onpath.app.cells.label
import com.onpath.app.debug.Problems
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.MapLoadUi
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * CORE FEATURE "the first map on a fresh install" end to end, with the real TEST map server: no map package, no map
 * area, no routing data on the phone; the position is in Meran; the app starts → the map for here loads from the server
 * and is joined into the map, its wait window's ONE bar (owner 2026-11-19: never back, never standing) is sampled every
 * 250 ms, the routing data loads by itself and a short plan routes. Nothing in the problem list.
 *
 * Downloads real map areas, so it needs a build WITH the TEST map key (`ONPATH_MAP_KEY_TEST` / `onpath.mapKey.test`):
 * without one it is skipped – the normal E2E runs have no key. On GitHub its own job in e2e.yml builds with the key and
 * the world overview and runs only this class.
 */
@RunWith(AndroidJUnit4::class)
class FirstMapDownloadE2ETest : E2EBase() {

    private val meran = GeoPoint(MERAN_LAT, MERAN_LON)

    private class Sample(val atMs: Long, val ui: MapLoadUi?, val sign: Boolean, val own: CellState, val generation: Int)

    @Test fun freshInstallLoadsTheMapForHereJoinsItAndRoutes() {
        assumeTrue("needs a build with the TEST map key (ONPATH_MAP_KEY_TEST) – downloads real map areas",
            BuildConfig.MAP_SERVER_KEY.isNotEmpty())
        val own = CellId.at(meran) ?: error("no map area at Meran")

        // a fresh install, through the app's own folders (never uninstall / pm clear): no map package, no map area, no routing data
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("onpath", 0).edit().clear().commit()
        com.onpath.app.data.Prefs(ctx).apply { setupWelcomeSeen = true; vibrationFelt = com.onpath.app.setup.Felt.YES }
        ctx.getSharedPreferences("backup", 0).edit().putBoolean("restoreOffered", true).commit()
        dataDir.deleteRecursively(); dataDir.mkdirs()
        Problems.clear()
        gpsAt(meran.lat, meran.lon)
        wakeScreen()
        dismissSystemDialogs()
        val t0 = SystemClock.uptimeMillis()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        showOverLockScreen()

        var blocking = true
        var gen0 = -1
        scenario.onActivity { a -> vm(a).let { blocking = it.blockingMissing.isNotEmpty(); gen0 = it.cells.generation.value } }
        if (blocking) { shot("missing-data"); fail("the missing-data screen shows: this build has no world overview (onpath.overviewAssets) – ${state()}") }
        var installed0 = -1
        scenario.onActivity { installed0 = vm(it).cells.installed.value.size }
        assertTrue("a fresh install has no map area yet ($installed0)", installed0 == 0)

        // the map for here: sampled every 250 ms until the area is there and joined (generation advanced)
        val samples = ArrayList<Sample>()
        var asked = false
        var shotFirst = false
        var shotHalf = false
        val end = t0 + 6 * 60_000L
        var tick = 0
        while (true) {
            // a GPS fix every second, like a real receiver: the one set before the start reaches the app only as an
            // old fix, and the map for here waits for a fresh one ("Waiting for GPS for the detailed map")
            if (tick++ % 4 == 0) device.executeShellCommand(
                "cmd location providers set-test-provider-location gps --location ${meran.lat},${meran.lon} --accuracy 4")
            var s: Sample? = null
            scenario.onActivity { a ->
                val v = vm(a)
                // the server must build it first: the question box asks – Load, as the user would
                if (v.mapData.hereAsk != null) { asked = true; v.mapData.answerHereAsk(true) }
                val now = SystemClock.uptimeMillis()
                val states = v.cells.states.value
                val st = states[own] ?: if (v.cells.installed.value.any { it.id == own }) CellState.Ready("") else CellState.None
                s = Sample(now - t0, v.mapData.mapLoadUi(states, now), v.mapData.backgroundDownloadUi(states) != null, st,
                    v.cells.generation.value)
            }
            val x = s!!
            samples += x
            x.ui?.let { ui ->
                if (ui.kind == MapLoadUi.Kind.FAILED) { shot("failed"); fail("the first map failed: ${ui.reason} – ${trail(samples)}; ${state()}") }
                if (!shotFirst) { shotFirst = true; shot("window-${ui.percent}") }
                if (!shotHalf && ui.percent >= 50) { shotHalf = true; shot("window-${ui.percent}") }
            }
            if (x.own is CellState.Ready && x.generation > gen0 && x.ui == null) break
            if (SystemClock.uptimeMillis() > end) {
                shot("timeout")
                fail("the map for here not there within 6 min: own area ${x.own.label()}, generation $gen0 → ${x.generation} – ${trail(samples)}; ${state()}")
            }
            pause(250, "sampling interval of the wait window's percent")
        }
        val readyMs = samples.last().atMs
        shot("map-ready")

        // the wait window (form A) showed, its ONE bar never went back and never stood longer than 15 s
        val shown = samples.filter { it.ui != null }
        assertTrue("the first-map wait window never showed (sign beside: ${samples.any { it.sign }}) – ${trail(samples)}", shown.isNotEmpty())
        val back = shown.zipWithNext().firstOrNull { (a, b) -> b.ui!!.percent < a.ui!!.percent }
        assertTrue("the percent went back: ${back?.let { (a, b) -> "${a.ui!!.percent} → ${b.ui!!.percent} at ${b.atMs} ms" }} – ${trail(samples)}", back == null)
        val stood = longestStand(shown)
        assertTrue("the percent stood ${stood / 1000} s (≤ 15 s) – ${trail(samples)}", stood <= 15_000)
        android.util.Log.i("FirstMapDownloadE2E", "map for here: ready after ${readyMs / 1000.0} s (asked: $asked), window " +
            "${shown.first().atMs}–${shown.last().atMs} ms, percent ${shown.first().ui!!.percent}→${shown.last().ui!!.percent}, " +
            "longest stand ${stood} ms, late ${shown.any { it.ui!!.late }}; ${trail(samples)}")

        // the routing data loads by itself (brouter.de) – part of the map for here
        val rd5 = File(dataDir, "segments/E10_N45.rd5")
        waitUntil("routing data for Meran loaded", 5 * 60_000L, onTimeout = { shot("no-routing-data"); state() }) {
            var loading = true
            scenario.onActivity { loading = vm(it).mapData.routingLoading }
            rd5.isFile && rd5.length() > 1_000_000 && !loading
        }
        android.util.Log.i("FirstMapDownloadE2E", "routing data there after ${(SystemClock.uptimeMillis() - t0) / 1000.0} s")

        // a short plan in the area routes along the ways (not a straight line)
        // (set through the app's own step: a hold on the real map can land on a path or a symbol, which is not this test's subject)
        val to = GeoPoint(46.6680, 11.1610) // ~800 m west, in the same map area
        assertTrue("the plan's end in the loaded map area", CellId.at(to) == own)
        scenario.onActivity { vm(it).placeAdd.addPoint(meran); vm(it).placeAdd.addPoint(to) }
        waitUntil("two plan points") { waypointCount() == 2 }
        waitNoLoadModal(6 * 60_000L)
        waitUntil("a short plan routes", 2 * 60_000L, onTimeout = { shot("no-route"); state() }) {
            var ok = false
            scenario.onActivity { a -> val v = vm(a); ok = !v.planRun.routing && v.route?.legs?.let { l -> l.isNotEmpty() && l.none { it.straight } } == true }
            ok
        }
        shot("planned")

        // "perf" (MainStall: the main thread blocked at a frame) is the emulator's speed, not the first map: GitHub's
        // software-rendered emulator stalls on the plan sheet's first frames (first run of the job, 2026-11-20)
        val bad = Problems.snapshot().filter { it.level != Problems.Level.INFO && it.area != "perf" }
        assertTrue("problems on the first map: ${bad.joinToString(" | ") { "${it.level} ${it.area}: ${it.message}" }}", bad.isEmpty())
    }

    /** Longest time the bar stood at one percent while it was not paused (offline / waiting for Wi-Fi stands on purpose). */
    private fun longestStand(shown: List<Sample>): Long {
        var longest = 0L
        var since: Sample? = null
        for (s in shown) {
            val ui = s.ui!!
            if (ui.paused) { since = null; continue }
            val start = since
            if (start == null || start.ui!!.percent != ui.percent) since = s
            else longest = maxOf(longest, s.atMs - start.atMs)
        }
        return longest
    }

    /** The samples where something changed (percent, kind, own area's state), for the messages and the log. */
    private fun trail(samples: List<Sample>): String = samples.filterIndexed { i, s ->
        val p = samples.getOrNull(i - 1)
        p == null || p.ui?.percent != s.ui?.percent || p.ui?.kind != s.ui?.kind || p.own.label() != s.own.label() || p.sign != s.sign
    }.takeLast(60).joinToString(" ") { s ->
        "${s.atMs / 100 / 10.0}s:${s.ui?.let { "${it.kind.name.lowercase()}${it.percent}%" } ?: "-"}${if (s.sign) "+sign" else ""}/${s.own.label()}"
    }
}
