package com.onpath.app.e2e

import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.StrictMode
import android.os.strictmode.Violation
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onpath.app.data.Prefs
import com.onpath.app.routing.Activity
import com.onpath.app.recording.DemoLocation
import com.onpath.app.recording.RecordingService
import com.onpath.app.routing.GeoPoint
import com.onpath.app.domain.AppMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Not a test of the app: measures what a GPS fix costs on the main thread while a tour is recorded and followed (code
 * audit 2026-11-12, point d – every fix runs on the main looper and `RecordingService.onFix` does the navigation maths
 * and `db.appendPoint` there). Nothing in the app changes for it: the instrumentation sits in the app's process and
 *  - times every main-looper message (`Looper.setMessageLogging`); the demo ride's one-a-second tick
 *    (`DemoLocation.ticker`) is the fix – it hands the fix to both trackers (the app's and the service's) like the two
 *    listener calls of a real fix;
 *  - counts disk reads / writes on the main thread with StrictMode (listener only, no penalty), split into "inside a
 *    fix" and "elsewhere", by the first OnPath frame;
 *  - reads the window's frame times (FrameMetrics) and which frames a fix overlapped, plus `dumpsys gfxinfo`.
 * Two phases: Navigate on screen ([FG_ARG] seconds, default 180), then the app left with Home ([BG_ARG], default 90 –
 * only the service's tracker takes the fix). The numbers go to logcat under the tag [TAG]; `tools/dev/main_thread_measure.sh`
 * runs this and prints them. Skipped unless the run asks for it with `-e onpathTool 1` (the script does): the E2E
 * workflow runs every class here and this one rides for minutes. Emulator numbers: the order of magnitude and the split count, not the milliseconds.
 */
@RunWith(AndroidJUnit4::class)
class MainThreadMeasure : E2EBase() {

    private class Msg(val startNs: Long, val endNs: Long)

    private class Phase(val name: String) {
        val fixWallMs = ArrayList<Double>()
        val fixCpuMs = ArrayList<Double>()
        val fixSpans = ArrayList<Msg>()
        var otherMessages = 0
        var otherWallMs = 0.0
        var otherOver16 = 0
        var otherOver100 = 0
        var otherMaxMs = 0.0
        var otherMaxWhat = ""
        val diskInFix = AtomicInteger()
        val diskElsewhere = AtomicInteger()
        val diskBy = ConcurrentHashMap<String, AtomicInteger>()
        /** intended vsync (ns), total, waiting for the main thread, UI-thread work – ms */
        val frames = ArrayList<DoubleArray>()
        var startNs = 0L
        var endNs = 0L
        var pointsAtStart = 0
        var pointsAtEnd = 0
    }

    @Volatile private var phase: Phase? = null

    /** Main thread only: the looper prints one line before and one after every message. */
    private val printer = object : android.util.Printer {
        private var t0 = 0L
        private var c0 = 0L
        private var fix = false
        private var what = ""
        override fun println(x: String) {
            if (x.startsWith(">")) {
                fix = "DemoLocation\$ticker" in x
                what = x
                t0 = System.nanoTime(); c0 = Debug.threadCpuTimeNanos()
                return
            }
            val p = phase ?: return
            val t1 = System.nanoTime()
            if (t0 < p.startNs) return
            val wall = (t1 - t0) / 1e6
            if (fix) {
                p.fixWallMs += wall; p.fixCpuMs += (Debug.threadCpuTimeNanos() - c0) / 1e6; p.fixSpans += Msg(t0, t1)
            } else {
                p.otherMessages++; p.otherWallMs += wall
                if (wall > 16) p.otherOver16++
                if (wall > 100) p.otherOver100++
                if (wall > p.otherMaxMs) { p.otherMaxMs = wall; p.otherMaxWhat = what.substringAfter("} ").take(90) }
            }
        }
    }

    private val strictExecutor = Executors.newSingleThreadExecutor()

    private fun onViolation(v: Violation) {
        val p = phase ?: return
        val stack = v.stackTrace
        val inFix = stack.any { it.className.startsWith("com.onpath.app.recording.DemoLocation") }
        (if (inFix) p.diskInFix else p.diskElsewhere).incrementAndGet()
        val own = stack.filter { it.className.startsWith("com.onpath.") }.take(2)
            .joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        val key = "${if (inFix) "fix" else "other"} | ${v.javaClass.simpleName} | ${own.ifEmpty { "(no OnPath frame)" }}"
        p.diskBy.getOrPut(key) { AtomicInteger() }.incrementAndGet()
    }

    private val frameThread = HandlerThread("measure-frames").apply { start() }
    private val frameListener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
        val p = phase ?: return@OnFrameMetricsAvailableListener
        val ui = m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) + m.getMetric(FrameMetrics.ANIMATION_DURATION) +
            m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) + m.getMetric(FrameMetrics.DRAW_DURATION)
        val row = doubleArrayOf(m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP).toDouble(),
            m.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6, m.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION) / 1e6, ui / 1e6)
        synchronized(p.frames) { p.frames += row }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun arg(name: String, default: Int) =
        InstrumentationRegistry.getArguments().getString(name)?.toIntOrNull() ?: default
    private fun storedPoints() = RecordingService.state.value?.track?.sumOf { it.size } ?: 0
    private fun say(line: String) { Log.i(TAG, line) }

    @Test fun measure() {
        org.junit.Assume.assumeTrue("a tool, not a test: only with -e onpathTool 1",
            InstrumentationRegistry.getArguments().getString("onpathTool") == "1")
        val fgS = arg(FG_ARG, 180)
        val bgS = arg(BG_ARG, 90)
        launch {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            Prefs(ctx).apply {
                activity = Activity.BIKE_TOURING // ~4 m/s: every fix becomes a stored point (walking pace drops some)
                planWaypoints = PLAN
            }
        }
        assertTrue("real routing data needed: push E10_N45.rd5 to /data/local/tmp (main_thread_measure.sh does)", realRouting)
        waitUntil("route calculated – ${state()}", 90_000) {
            var done = false; scenario.onActivity { done = vm(it).route != null && !vm(it).routing }; done
        }
        var routeM = 0.0
        scenario.onActivity { a -> routeM = vm(a).route?.distanceM ?: 0.0 }
        scenario.onActivity { vm(it).startDemoRide() }
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording)", 20_000) { var m: AppMode? = null; scenario.onActivity { m = vm(it).mode }; m == AppMode.NAVIGATE }
        waitUntil("the route is followed", 20_000) { RecordingService.state.value?.nav != null }
        pause(8_000, "the start (camera flight, first route state, notification) is not the steady tour")

        scenario.onActivity { a -> a.window.addOnFrameMetricsAvailableListener(frameListener, Handler(frameThread.looper)) }
        onMain {
            Looper.getMainLooper().setMessageLogging(printer)
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites()
                .penaltyListener(strictExecutor) { v -> onViolation(v) }.build())
        }

        val fg = run("Navigate on screen", fgS, gfx = true)
        device.pressHome()
        pause(3_000, "the app goes to the background")
        val bg = run("app left (Home), service only", bgS, gfx = false)

        onMain { Looper.getMainLooper().setMessageLogging(null); StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX) }
        val demo = DemoLocation.state.value
        say("== OnPath main-thread measurement: API ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MODEL}, " +
            "route ${routeM.toInt()} m by bike, demo ride 1× (one fix a second), demo at ${demo?.alongM?.toInt()} m" +
            if (demo?.ended != false) " – THE RIDE ENDED OR STOPPED: part of the run stood still, repeat with a shorter time" else "")
        report(fg); report(bg)
        say("== end")

        try { onMain { DemoLocation.stop("measurement ended") } } catch (_: Exception) {} // expected: nothing running
        try { scenario.onActivity { vm(it).endTourAndDelete() } } catch (_: Exception) {} // expected: app already closed
        val end = System.currentTimeMillis() + 10_000
        while (RecordingService.state.value != null && System.currentTimeMillis() < end) pause(200, "poll interval of the cleanup")
    }

    private var gfxText = ""

    private fun run(name: String, seconds: Int, gfx: Boolean): Phase {
        val p = Phase(name)
        if (gfx) device.executeShellCommand("dumpsys gfxinfo com.onpath.app reset")
        p.pointsAtStart = storedPoints()
        p.startNs = System.nanoTime()
        phase = p
        pause(seconds * 1000L, "the measured phase itself")
        phase = null
        p.endNs = System.nanoTime()
        p.pointsAtEnd = storedPoints()
        if (gfx) gfxText = device.executeShellCommand("dumpsys gfxinfo com.onpath.app")
        pause(500, "violations and frames still on their way to the listeners")
        return p
    }

    private fun report(p: Phase) {
        val secs = (p.endNs - p.startNs) / 1e9
        say("-- phase: ${p.name}, ${secs.toInt()} s")
        say("fixes on the main thread: ${p.fixWallMs.size}, points stored: ${p.pointsAtEnd - p.pointsAtStart}")
        say("main-thread time per fix, wall ms: ${dist(p.fixWallMs)}")
        say("main-thread time per fix, CPU ms:  ${dist(p.fixCpuMs)}")
        say("fixes over 8 ms: ${p.fixWallMs.count { it > 8 }}, over 16 ms: ${p.fixWallMs.count { it > 16 }}, " +
            "over 50 ms: ${p.fixWallMs.count { it > 50 }}, over 100 ms: ${p.fixWallMs.count { it > 100 }}")
        say("main thread busy: fixes ${pct(p.fixWallMs.sum(), secs)} of the time, everything else " +
            "${pct(p.otherWallMs, secs)} (${p.otherMessages} messages, ${p.otherOver16} over 16 ms, ${p.otherOver100} over " +
            "100 ms, longest ${f(p.otherMaxMs)} ms: ${p.otherMaxWhat})")
        val fixes = p.fixWallMs.size.coerceAtLeast(1)
        say("disk on the main thread (StrictMode, at most 10 counted per message): inside a fix ${p.diskInFix.get()} " +
            "(${f(p.diskInFix.get().toDouble() / fixes)} per fix), elsewhere ${p.diskElsewhere.get()}")
        p.diskBy.entries.sortedByDescending { it.value.get() }.take(14).forEach { say("   ${it.value.get()} × ${it.key}") }
        val frames = synchronized(p.frames) { p.frames.toList() }
        if (frames.isNotEmpty()) {
            val hit = frames.filter { fr -> p.fixSpans.any { it.startNs < fr[0] + fr[1] * 1e6 && it.endNs > fr[0] } }
            val free = frames - hit.toSet()
            say("frames (window, FrameMetrics): ${frames.size}, over 16.7 ms: ${frames.count { it[1] > 16.7 }}, " +
                "over 33 ms: ${frames.count { it[1] > 33 }}; total ms ${dist(frames.map { it[1] })}")
            say("   frames a fix overlapped: ${hit.size}, over 16.7 ms: ${hit.count { it[1] > 16.7 }}, " +
                "waiting for the main thread ms ${dist(hit.map { it[2] })}")
            say("   frames without a fix:    ${free.size}, over 16.7 ms: ${free.count { it[1] > 16.7 }}, " +
                "waiting for the main thread ms ${dist(free.map { it[2] })}")
        } else say("frames (window, FrameMetrics): none drawn")
        if (p.name.startsWith("Navigate")) gfxText.lineSequence().map { it.trim() }.filter { l ->
            GFX_LINES.any { l.startsWith(it) }
        }.take(GFX_LINES.size).forEach { say("gfxinfo: $it") }
    }

    private fun dist(v: List<Double>): String {
        if (v.isEmpty()) return "–"
        val s = v.sorted()
        fun q(x: Double) = s[(Math.ceil(x * s.size).toInt() - 1).coerceIn(0, s.lastIndex)]
        return "median ${f(q(0.5))}, p90 ${f(q(0.9))}, p99 ${f(q(0.99))}, max ${f(s.last())}, mean ${f(s.average())}"
    }

    private fun f(v: Double) = String.format(Locale.ROOT, "%.1f", v)
    private fun pct(ms: Double, secs: Double) = String.format(Locale.ROOT, "%.1f %%", ms / (secs * 1000) * 100)

    companion object {
        const val TAG = "OnPathMeasure"
        const val FG_ARG = "measureSeconds"
        const val BG_ARG = "backgroundSeconds"
        /** Meran → Dorf Tirol → Schenna side and back: long enough for ~8 min by bike at 1×. */
        val PLAN = listOf(GeoPoint(MERAN_LAT, MERAN_LON), GeoPoint(MERAN_LAT + 0.0046, MERAN_LON - 0.0100),
            GeoPoint(MERAN_LAT + 0.0086, MERAN_LON), GeoPoint(MERAN_LAT + 0.0186, MERAN_LON + 0.0080),
            GeoPoint(MERAN_LAT + 0.0040, MERAN_LON + 0.0150))
        val GFX_LINES = listOf("Total frames rendered", "Janky frames", "50th percentile", "90th percentile", "95th percentile",
            "99th percentile", "Number Missed Vsync", "Number High input latency", "Number Slow UI thread",
            "Number Slow bitmap uploads", "Number Slow issue draw commands", "Number Frame deadline missed")
    }
}
