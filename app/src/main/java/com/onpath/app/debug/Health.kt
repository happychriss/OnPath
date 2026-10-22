package com.onpath.app.debug

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import java.util.Locale

/**
 * "Does the phone slow down over time?" in the event log (perf report 2026-10-22, owner: the log may be big):
 *
 * - every [INTERVAL_MS] while the process runs one line: memory (PSS, native / Java / graphics), native heap, CPU used
 *   since the last line, janky frames while the app was on screen, map store generation, waiting downloads;
 * - a main-thread watchdog while the app is on screen: a stall over [STALL_MS] → Problems.warn with where it was.
 *
 * Overhead: one sample every 10 min; one tiny message to the main thread per second while on screen; a counter per
 * drawn frame. Never throws.
 */
object Health {
    const val INTERVAL_MS = 10 * 60_000L
    const val STALL_MS = 500L
    private const val PING_MS = 1_000L

    /** What one health line says (pure: [line], HealthLineTest). Null = unknown. */
    data class Sample(
        val pssKb: Long?, val nativePssKb: Long?, val javaPssKb: Long?, val graphicsKb: Long?,
        val nativeHeapBytes: Long, val cpuMs: Long, val wallMs: Long,
        val frames: Long, val jankyFrames: Long,
        val storeChanges: Int?, val cells: Int?, val cellQueue: Int?, val routingQueue: Int?,
    )

    /** "Health: PSS 2.1 GB (native 1.5, java 0.1, gfx 0.2), native heap 1.47 GB, CPU 0.35 cores, janky 4.8 % of 12000 frames, …". */
    fun line(s: Sample): String = buildString {
        append("Health: PSS ").append(gb(s.pssKb?.times(1024)))
        append(" (native ").append(gb(s.nativePssKb?.times(1024), unit = false))
        append(", java ").append(gb(s.javaPssKb?.times(1024), unit = false))
        append(", gfx ").append(gb(s.graphicsKb?.times(1024), unit = false)).append(")")
        append(", native heap ").append(gb(s.nativeHeapBytes))
        val cores = if (s.wallMs > 0) s.cpuMs.toDouble() / s.wallMs else 0.0
        append(", CPU ").append(String.format(Locale.ROOT, "%.2f", cores)).append(" cores")
        append(" (").append(s.cpuMs / 1000).append(" s in ").append(s.wallMs / 60_000).append(" min)")
        if (s.frames > 0) append(", janky ").append(String.format(Locale.ROOT, "%.1f", 100.0 * s.jankyFrames / s.frames))
            .append(" % of ").append(s.frames).append(" frames")
        else append(", no frames drawn")
        append(", store changes ").append(s.storeChanges ?: "–").append(" / ").append(s.cells ?: "–").append(" cells")
        append(", queue ").append(s.cellQueue ?: 0).append(" regions + ").append(s.routingQueue ?: 0).append(" routing parts")
    }

    private fun gb(bytes: Long?, unit: Boolean = true): String =
        if (bytes == null) "?" else String.format(Locale.ROOT, "%.2f", bytes / 1_073_741_824.0) + if (unit) " GB" else ""

    // ------------------------------------------------------------------------------------------------ the ticker

    private val thread by lazy { HandlerThread("onpath-health").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }
    @Volatile private var started = false
    private var lastCpu = 0L
    private var lastWall = 0L

    /** Starts the 10-minute health line (Application.onCreate). */
    fun start() {
        if (started) return
        started = true
        try {
            lastCpu = android.os.Process.getElapsedCpuTime(); lastWall = SystemClock.elapsedRealtime()
            handler.postDelayed(::tick, INTERVAL_MS)
        } catch (e: Exception) {
            Problems.warn("perf", "Health line not started", e)
        }
    }

    private fun tick() {
        try {
            Problems.trace("perf", line(sample()))
        } catch (e: Exception) {
            Problems.warn("perf", "Health line not written", e)
        } finally {
            handler.postDelayed(::tick, INTERVAL_MS)
        }
    }

    private fun sample(): Sample {
        val mi = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(mi)
        fun stat(k: String) = mi.getMemoryStat(k)?.toLongOrNull()
        val cpu = android.os.Process.getElapsedCpuTime(); val wall = SystemClock.elapsedRealtime()
        val dCpu = cpu - lastCpu; val dWall = wall - lastWall
        lastCpu = cpu; lastWall = wall
        val f = frames.getAndSet(0); val j = janky.getAndSet(0)
        val cells = com.onpath.app.cells.AppCells.peek()
        val routing = com.onpath.app.data.AppRouting.peek()
        return Sample(
            mi.totalPss.toLong(), stat("summary.native-heap"), stat("summary.java-heap"), stat("summary.graphics"),
            android.os.Debug.getNativeHeapAllocatedSize(), dCpu, dWall, f, j,
            cells?.generation?.value, cells?.installed?.value?.size,
            try { cells?.waiting()?.regions } catch (e: Exception) { Problems.info("perf", "Waiting regions unknown", e); null },
            routing?.status?.names?.size,
        )
    }

    // ------------------------------------------------------------------------------------------------ frames

    private val frames = java.util.concurrent.atomic.AtomicLong()
    private val janky = java.util.concurrent.atomic.AtomicLong()
    private var frameListener: Window.OnFrameMetricsAvailableListener? = null

    /** The activity is on screen: count its frames (janky = over one refresh period) and watch the main thread. */
    fun attach(window: Window) {
        if (frameListener == null) try {
            val hz = (window.decorView.display?.refreshRate ?: 60f).coerceIn(30f, 240f)
            val budgetNs = (1_000_000_000.0 / hz + 500_000).toLong()
            val l = Window.OnFrameMetricsAvailableListener { _, m, _ ->
                frames.incrementAndGet()
                if (m.getMetric(FrameMetrics.TOTAL_DURATION) > budgetNs) janky.incrementAndGet()
            }
            window.addOnFrameMetricsAvailableListener(l, handler)
            frameListener = l
        } catch (e: Exception) {
            Problems.warn("perf", "Frame counter not available; health line without janky frames", e)
        }
        watchdogOn = true
        if (watchdog == null) startWatchdog()
    }

    /** The activity left the screen. */
    fun detach(window: Window) {
        watchdogOn = false
        val l = frameListener ?: return
        frameListener = null
        try { window.removeOnFrameMetricsAvailableListener(l) } catch (e: Exception) {
            Problems.warn("perf", "Frame counter could not be removed", e)
        }
    }

    // ------------------------------------------------------------------------------------------------ watchdog

    @Volatile private var watchdogOn = false
    private var watchdog: Thread? = null
    @Volatile private var answered = 0L

    private fun startWatchdog() {
        val main = Handler(Looper.getMainLooper())
        watchdog = Thread({
            while (true) {
                try {
                    if (!watchdogOn) { Thread.sleep(PING_MS); continue }
                    val posted = SystemClock.uptimeMillis()
                    answered = 0L
                    main.post { answered = SystemClock.uptimeMillis() }
                    Thread.sleep(STALL_MS)
                    if (answered == 0L) {
                        val where = mainThreadTop()
                        // wait for the answer (at most 30 s) to know how long it was blocked
                        var waited = 0
                        while (answered == 0L && waited < 30_000) { Thread.sleep(50); waited += 50 }
                        val blocked = (if (answered != 0L) answered else SystemClock.uptimeMillis()) - posted
                        if (blocked > STALL_MS) {
                            Problems.warn("perf", "Main thread blocked (in: $where)")
                            Problems.trace("perf", "Main thread blocked $blocked ms (in: $where)")
                        }
                    }
                    Thread.sleep(PING_MS - STALL_MS)
                } catch (e: InterruptedException) {
                    return@Thread // expected: never interrupted in practice; the watchdog just ends
                } catch (e: Exception) {
                    Problems.warn("perf", "Main thread watchdog stopped", e)
                    return@Thread
                }
            }
        }, "onpath-watchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    /** The main thread's top app frame (else its top frame) while it is blocked. */
    private fun mainThreadTop(): String = try {
        val st = Looper.getMainLooper().thread.stackTrace
        (st.firstOrNull { it.className.startsWith("com.onpath.") } ?: st.firstOrNull())?.let {
            "${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})" +
                (st.firstOrNull()?.takeIf { top -> top != it }?.let { top -> " ← ${top.className.substringAfterLast('.')}.${top.methodName}" } ?: "")
        } ?: "?"
    } catch (e: Exception) {
        Problems.info("perf", "Main thread stack not readable", e); "?"
    }
}
