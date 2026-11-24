package com.onpath.app.live

import com.onpath.app.debug.Problems
import com.onpath.app.recording.RecState
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Tells the lane when Android reports the network back; [watch] / [unwatch] may be called again and again. */
interface LiveNetwork {
    fun watch(onBack: () -> Unit)
    fun unwatch()
}

/**
 * The live sharing's own lane (docs/design/live-tracking.md §7.3): ONE thread on which every call of the
 * [LiveUploader] runs – the uploader has no locks of its own. The recording service and the app only ever hand a small
 * task to it and return at once; a request that hangs (25 s at most, [HttpLiveBox]) holds up this thread and nothing
 * else. The lane takes no lock anybody else takes, holds no wake lock and writes nothing of the tour.
 *
 * The recording service does not know the lane at all: the lane reads the service's published state, and learns the
 * end of the shared tour from the tour's own row ([over]) when that state goes away.
 *
 * It calls [LiveUploader.step] when the uploader says it is due, when something happened (start, pause, resume,
 * off-route alarm, arrived, end) and when the network is back. It steps only while the shared tour is the one that
 * records, or while an end / a stop is still on its way to the server – a tour that was interrupted (the service gone,
 * "Continue tour" not chosen yet) is left alone: no upload pretends it is still going.
 *
 * Nothing here throws into its caller: every task is caught and reported.
 *
 * @param state the recording service's published state (null = no tour runs in this process)
 * @param over is this tour over for good (ended, deleted, closed by a newer one)? Read-only; may throw (= not known)
 * @param clock a clock that never goes back (elapsed real time) – the uploader's pacing
 * @param wall the wall clock – only for [LiveSnapshot.lastOkMs]
 */
class LiveLane(
    private val uploader: LiveUploader,
    private val state: () -> RecState?,
    private val over: (Long) -> Boolean,
    private val clock: () -> Long,
    private val wall: () -> Long,
    private val network: LiveNetwork,
    private val publish: (LiveSnapshot) -> Unit,
) {
    private val exec = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "onpath-live").apply { isDaemon = true } }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    // lane thread only
    private var next: ScheduledFuture<*>? = null
    private var watching = false
    private var lastStepMs: Long? = null

    /** Reads the remembered share (a killed process goes on with the same link) – on the lane, not on the caller. */
    fun attach() = run("start") { endIfOver(); step() }

    /** Share the tour [tourId] (the link comes with the next [LiveSnapshot]). */
    fun start(tourId: Long, name: String) = run("start sharing") {
        if (tourId < 0) return@run
        uploader.start(tourId, name)
        show() // the link reaches the share sheet now, not after the claim's answer (up to 25 s on a weak network)
        step(event = true)
    }

    /** "Stop sharing". */
    fun stop() = run("stop sharing") { uploader.stop(); show(); step() }

    /**
     * Something the viewer should see soon changed in the recording's state: pause, resume, alarm, arrived – or the
     * tour is not the running one any more (ended, interrupted, another tour).
     */
    fun stateChanged() = run("follow the tour") { endIfOver(); step(event = true) }

    /** The user was told that the server ended the sharing. */
    fun acknowledge() = run("acknowledge") { uploader.acknowledge(); settle() }

    /** For tests: waits until everything handed in so far has run; false = the lane is busy (a hanging request). */
    internal fun idle(timeoutMs: Long = 5_000): Boolean = try {
        exec.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS); true
    } catch (e: Exception) {
        false // expected: the lane's thread is held by a request – the caller asserts on it
    }

    internal fun shutdown() { exec.shutdownNow() }

    // ---- lane thread ----

    /**
     * The shared tour does not run here (any more): when its row says it is over, the share ends – the viewers get the
     * last way and "ended". An interrupted tour (still "active", Continue possible) keeps its share; a database that
     * cannot be read right now is "not known" and ends nothing.
     */
    private fun endIfOver() {
        val shared = uploader.tourId ?: return
        if (uploader.link == null || state()?.tourId == shared) return
        val isOver = try { over(shared) } catch (e: Exception) {
            Problems.warn("live", "Live sharing: the shared tour's state could not be read; the share is kept", e)
            false
        }
        if (isOver) uploader.end()
    }

    private fun step(event: Boolean = false, networkBack: Boolean = false) {
        if (active()) {
            val now = clock()
            lastStepMs = now
            uploader.step(now, event, networkBack)
        }
        settle()
    }

    /**
     * True while there is something to send: the shared tour records here, or its end / stop – or an older share's
     * stop ([LiveUploader.cleaning], whatever the running share's tour does) – still has to reach the server.
     */
    private fun active(): Boolean {
        if (uploader.cleaning) return true
        val shared = uploader.tourId ?: return false
        if (uploader.link == null) return true // ending or stopping
        return state()?.tourId == shared
    }

    /** After every action: say what is shown, watch the network only while sharing, plan the next step. */
    private fun settle() {
        val pending = uploader.pending
        // the reader of the snapshot and the network watch are neighbours: one that throws must not cost the next step
        show()
        if (pending != watching) {
            watching = pending
            guarded("watch the network") {
                if (pending) network.watch { run("use the network again") { step(networkBack = true) } } else network.unwatch()
            }
        }
        next?.cancel(false)
        next = null
        if (!active()) return
        val due = uploader.nextDueMs() ?: return
        val now = clock()
        // never spin: at least MIN_GAP_MS after the step before, whatever the uploader says
        val floor = lastStepMs?.let { it + MIN_GAP_MS - now } ?: 0L
        next = exec.schedule({ guarded("upload") { step() } }, maxOf(due - now, floor, 0L), TimeUnit.MILLISECONDS)
    }

    private fun show() = guarded("show the state") { publish(snapshot()) }

    private fun snapshot(): LiveSnapshot {
        val st = uploader.status
        val lastOk = (st as? LiveStatus.Live)?.lastOkMs?.let { wall() - (clock() - it) }
        val link = uploader.link
        return LiveSnapshot(
            sharing = st is LiveStatus.Live || st is LiveStatus.Waiting, waiting = st is LiveStatus.Waiting,
            link = link, tourId = if (link != null) uploader.tourId else null, lastOkMs = lastOk,
            endedByServer = st is LiveStatus.StoppedByServer,
        )
    }

    /** Hands [block] to the lane; returns at once. Never throws (a lane that was shut down drops the task, reported). */
    private fun run(what: String, block: () -> Unit) {
        try {
            exec.execute { guarded(what, block) }
        } catch (e: Exception) {
            Problems.warn("live", "Live sharing: could not $what (the lane does not take work)", e)
        }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { Problems.warn("live", "Live sharing: could not $what", e) }
    }

    companion object {
        /** The shortest time between two steps (a catching-up uploader asks for 1 s; nothing may spin faster than this). */
        const val MIN_GAP_MS = 500L
    }
}
