package com.onpath.app.testkit

import com.onpath.app.navigation.FollowStore
import com.onpath.app.navigation.NavPrompt
import com.onpath.app.navigation.NavSession
import com.onpath.app.navigation.NavSessionStore
import com.onpath.app.navigation.Navigator
import com.onpath.app.navigation.OffRouteDetector
import com.onpath.app.navigation.Rerouter
import com.onpath.app.routing.RouteCancel
import com.onpath.app.routing.RoutingEngine
import java.io.File

/**
 * The recording service's navigation part with the real pieces – [Navigator], [Rerouter], [FollowStore] on the
 * real `nav-session.json` (the service's writes: session and, with a new line, `follow.json` as one step) – wired the way `RecordingService` wires them (`loadFollow`, `handle`, `startReroute`), on
 * two [ManualExecutor]s instead of the main looper and the route thread. A test plays the app (it writes the session
 * file with its own store and sends [followChanged]) and decides when the service's main work and its routing run.
 *
 * Until the service has an Android-free core of its own (cleanup: "service: one writer"), these ~40 lines mirror its
 * wiring; they must be kept like `RecordingService.handle` – when that core exists, this class wraps it instead.
 */
class FakeNavService(private val sessionFile: File, private val engine: RoutingEngine, val main: ManualExecutor = ManualExecutor("main"),
                     val routing: ManualExecutor = ManualExecutor("route")) {
    val store = NavSessionStore(sessionFile)
    private val files = FollowStore(sessionFile.absoluteFile.parentFile)
    var navigator: Navigator? = null
        private set
    /** Results that belonged to a session the app had replaced or ended meanwhile. */
    var dropped = 0
        private set
    private var cancel: RouteCancel? = null

    /** The app's FOLLOW_CHANGED (also the service's start): what is followed is read from the file again. */
    fun followChanged() = main.execute { load() }

    fun fix(f: OffRouteDetector.Fix) = main.execute { navigator?.let { handle(it.onFix(f)) } }

    /** A second without GPS (the service's tick, R-04) or a fix while standing still: no timers, no alarms. */
    fun gpsLost() = main.execute { navigator?.onGpsLost() }

    /** "Take new route" on the question that is open. */
    fun take() = main.execute { navigator?.let { n -> (n.prompt as? NavPrompt.Question)?.let { q -> handle(n.take(q.id)) } } }

    /** Runs the main and the routing work until nothing waits any more (results, and what they start). */
    fun settle() { while (main.runAll() + routing.runAll() > 0) Unit }

    /** A new process on the same files: nothing in memory, nothing queued. */
    fun restarted() = FakeNavService(sessionFile, engine).also { it.followChanged(); it.main.runAll() }

    private fun load() {
        cancel?.cancel(); cancel = null
        val stored = store.load()
        val old = navigator
        var session = stored
        if (session != null) {
            val carried = NavSession.afterPlanEdit(old?.session, session)
            if (carried != session) { session = carried; files.serviceSaves(carried) }
        }
        navigator = session?.let { Navigator(it) }
    }

    private fun handle(step: Navigator.Step) {
        val nav = navigator ?: return
        if (step.cancelJob) { cancel?.cancel(); cancel = null }
        step.job?.let { job ->
            cancel?.cancel()
            val c = RouteCancel().also { cancel = it }
            routing.execute {
                val out = Rerouter(engine).reroute(job.request, c)
                main.execute { if (cancel === c) cancel = null; navigator?.let { handle(it.onResult(job.id, out)) } }
            }
        }
        if ((step.lineChanged || step.sessionChanged) &&
            !files.serviceSaves(nav.session, lineAs = if (step.lineChanged) nav.session.name else null)) {
            dropped++
            load()
        }
    }
}
