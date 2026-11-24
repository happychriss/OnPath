package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellState
import com.onpath.app.cells.MapCells
import com.onpath.app.data.RoutingDataLoader
import com.onpath.app.domain.CellProgress
import com.onpath.app.domain.LoadStep
import com.onpath.app.domain.OneBar
import com.onpath.app.domain.PrepareProgress
import com.onpath.app.domain.StepClock
import com.onpath.app.domain.StepShares
import com.onpath.app.domain.TimeLeft
import com.onpath.app.domain.WaitStep
import com.onpath.app.domain.WaitSteps
import com.onpath.app.ui.progress.WaitButton
import com.onpath.app.ui.progress.WaitSign
import com.onpath.app.ui.progress.WaitWindowDialog
import com.onpath.app.ui.progress.WaitWindowUi
import com.onpath.app.ui.progress.WaitWindowView

/**
 * One wait for the map of a place, updated about every second (owner 2026-11-19 „each step measures itself“): the server
 * prepares the regions – its own time left ([PrepareProgress]) –, the bytes download – real bytes of the map and the
 * routing data –, the map is joined in – the bytes of the pieces in ([MapCells.joining]) – and the routing file is
 * rewritten – the bytes written ([RoutingDataLoader.Status.written]). Each step weighs with its learned share
 * ([shares]); the ONE bar follows the measurement and creeps on by time between two ([OneBar]). [StepClock] measures how
 * long each step really ran – what the shares learn from when the wait ends ([durations], only a wait without a pause:
 * [learnable]). When everything is there it shows one last picture at 100 %, then null. Pure (time passed in).
 */
class MapWait(private val shares: StepShares = StepShares.DEFAULT, defaultBuildS: Int = DEFAULT_BUILD_S) {
    private val bar = OneBar()
    private val clock = StepClock()
    private val prep = PrepareProgress(defaultBuildS)
    /** Routing data of this wait: its size and whether it was seen loading (gone = there). */
    private var routingBytes = 0L
    private var routingSeen = false
    /** Download rate (bytes / s), measured over the whole wait. */
    private var rateAt = -1L
    private var rateBytes = 0L
    private var rate: Double? = null
    /** Join pace (share / s) of the pieces reported. */
    private var joinAt = -1L
    private var joinDone = 0.0
    private var joinRate: Double? = null
    private var speedAt = -1L
    private var speedBytes = 0L
    private var lastMs = -1L
    private var last: MapLoadUi? = null
    private var ended = false

    /**
     * The window for [focus] (the regions waited for, [sizes] = installed sizes), [routing] (null = no routing data
     * waited for, or there), [joining] (what is joined into the map now) at [now] (uptime ms). Everything there: one
     * last picture at 100 %, then null.
     */
    fun update(
        focus: Collection<CellId>, states: Map<CellId, CellState>, sizes: Map<CellId, Long>,
        routing: RoutingDataLoader.Status?, joining: MapCells.Joining?, now: Long,
    ): MapLoadUi? {
        if (ended) return null
        // a gap (the screen was off, the window was hidden): the rates start their measuring afresh
        if (lastMs >= 0 && now - lastMs > OneBar.MAX_GAP_MS) { rateAt = -1L; joinAt = -1L; speedAt = -1L }
        lastMs = now
        if (routing != null) { routingSeen = true; routingBytes = maxOf(routingBytes, routing.totalBytes) }
        fun st(c: CellId): CellState = states[c] ?: if (c in sizes) CellState.Ready("") else CellState.Requested
        fun ready(s: CellState) = s is CellState.Ready || s == CellState.Downloaded
        val open = focus.filter { !ready(st(it)) }
        if (open.isEmpty() && routing == null) {
            ended = true
            // the end: the last picture at 100 % (the window does not linger at 97–99 %)
            return last?.copy(kind = MapLoadUi.Kind.FINISHING, percent = bar.next(1.0, 1.0, null, now, finished = true),
                secondsLeft = null, late = false, at = null)
        }
        val map = focus.isNotEmpty()
        fun size(c: CellId) = MapLoads.knownBytes(states[c], sizes[c]) ?: MapLoads.ESTIMATED_REGION_BYTES
        val mapBytes = focus.sumOf(::size)
        // prepare: per region the server's time left (re-anchored with each new estimate), never back
        val prepFrac = if (!map) 1.0 else focus.map { c ->
            val s = st(c)
            prep.fraction(c.id, s, now) ?: if (s is CellState.Offline || s is CellState.Failed) 0.0 else 1.0
        }.average()
        // download: the real bytes of the map and the routing data
        val mapGot = focus.sumOf { c ->
            when (val s = st(c)) {
                is CellState.Downloading -> s.doneBytes.coerceAtMost(size(c))
                CellState.Installing, CellState.Downloaded, is CellState.Ready -> size(c)
                else -> 0L
            }
        }
        val rTotal = if (routingSeen) maxOf(routingBytes, routing?.totalBytes ?: 0L) else 0L
        val rGot = if (routing == null) rTotal else routing.doneBytes.coerceAtMost(rTotal)
        val allBytes = (mapBytes + rTotal).coerceAtLeast(1L)
        val dlFrac = ((mapGot + rGot).toDouble() / allBytes).coerceIn(0.0, 1.0)
        // join: the pieces in of each region (map) and the routing file's bytes written
        val mapJoined = focus.sumOf { c ->
            when (val s = st(c)) {
                CellState.Downloaded, is CellState.Ready -> size(c).toDouble()
                CellState.Installing -> size(c) * (joining?.takeIf { c in it.cells }?.fraction ?: 0.0)
                else -> 0.0
            }
        }
        val rJoined = when {
            routing == null -> rTotal.toDouble()
            routing.writing -> rTotal * routing.written
            else -> 0.0
        }
        val joinFrac = ((mapJoined + rJoined) / allBytes).coerceIn(0.0, 1.0)
        // the steps there are (routing data only: no prepare) with their shares
        val steps = if (map) LoadStep.entries else listOf(LoadStep.DOWNLOAD, LoadStep.JOIN)
        val sum = steps.sumOf { shares.of(it) }
        fun share(s: LoadStep) = shares.of(s) / sum
        val frac = mapOf(LoadStep.PREPARE to prepFrac, LoadStep.DOWNLOAD to dlFrac, LoadStep.JOIN to joinFrac)
        val raw = steps.sumOf { share(it) * frac.getValue(it) }
        val step = steps.firstOrNull { frac.getValue(it) < DONE } ?: LoadStep.JOIN
        // where the running step's part ends – with what later steps already did (routing data loads while the map is prepared)
        val stepEnd = steps.takeWhile { it != step }.sumOf { share(it) } + share(step) +
            steps.dropWhile { it != step }.drop(1).sumOf { share(it) * frac.getValue(it) }
        // what happens: failed / offline / Wi-Fi first, else the running step
        val openStates = open.map(::st)
        val rp = routing?.phase
        val kind = when {
            (map && openStates.isNotEmpty() && openStates.all { it is CellState.Failed }) || (!map && rp == RoutingDataLoader.Phase.FAILED) ->
                MapLoadUi.Kind.FAILED
            openStates.any { it is CellState.Offline } || rp == RoutingDataLoader.Phase.OFFLINE -> MapLoadUi.Kind.OFFLINE
            openStates.none { it is CellState.Queued || it is CellState.Building || it == CellState.Requested || it == CellState.None ||
                it is CellState.Downloading || it == CellState.Installing } &&
                (openStates.any { it is CellState.WaitingForWifi } || rp == RoutingDataLoader.Phase.WIFI) &&
                rp != RoutingDataLoader.Phase.LOADING -> MapLoadUi.Kind.WIFI
            step == LoadStep.PREPARE -> MapLoadUi.Kind.PREPARING
            step == LoadStep.JOIN -> MapLoadUi.Kind.FINISHING
            else -> MapLoadUi.Kind.LOADING
        }
        val running = kind != MapLoadUi.Kind.FAILED && kind != MapLoadUi.Kind.OFFLINE && kind != MapLoadUi.Kind.WIFI
        // paused: no step time, and the rates measure afresh afterwards
        if (running) { measureRate(mapGot + rGot, now); measureJoin(joinFrac, now); joining?.let { j -> measureJoinSpeed(j.done, now) } }
        else { rateAt = -1L; joinAt = -1L; speedAt = -1L }
        clock.at(step, now, running)
        // the step's own time left: the server's estimate, the bytes at the measured rate, the pieces at their pace
        val stepLeftS: Int? = when (step) {
            LoadStep.PREPARE -> focus.mapNotNull { c -> prep.leftS(c.id, st(c), now) }.maxOrNull()
            LoadStep.DOWNLOAD -> rate?.takeIf { it > 0 }?.let { ((allBytes - mapGot - rGot) / it).toInt() }
            // the pieces at their pace; not reported (yet): the map's size – the store is rewritten with the new areas –
            // at this phone's measured join speed ([joinBytesPerS])
            LoadStep.JOIN -> joinRate?.takeIf { it > 0 }?.let { ((1 - joinFrac) / it).toInt() }
                ?: ((sizes.values.sum() + mapBytes) * (1 - joinFrac) / joinBytesPerS).toInt()
        }
        val pct = bar.next(raw, stepEnd, stepLeftS?.let { it * 1000L }, now, paused = !running,
            stepStart = steps.takeWhile { it != step }.sumOf { share(it) })
        val left = if (running) TimeLeft.seconds(bar.share, clock.elapsed, stepLeftS) else null
        val failed = openStates.filterIsInstance<CellState.Failed>()
        return MapLoadUi(kind, pct, left,
            reason = if (kind != MapLoadUi.Kind.FAILED) null else if (!map) RoutingLoads.FAILED_TEXT
                else if (failed.any { it.retryable }) Txt.s(Res.string.maps_load_failed_retry) else failed.firstOrNull()?.message,
            retryable = kind == MapLoadUi.Kind.FAILED && (!map || failed.any { it.retryable }),
            regions = focus.size, bytes = mapBytes + rTotal, routing = routingSeen, regionsDone = focus.size - open.size,
            at = step.takeIf { !running }, late = running && bar.late).also { last = it }
    }

    /** How long each step of this wait really ran – what [StepShares.learn] learns from. */
    fun durations(): Map<LoadStep, Long> = clock.durations()

    /** A step ran (the wait was real). */
    val started: Boolean get() = clock.started

    /** The wait ran without a pause or a gap: its times may teach the shares. */
    val learnable: Boolean get() = clock.started && !clock.hadPause

    /** The phone's join speed from the store's reports (bytes written / s), kept for the next join without reports. */
    private fun measureJoinSpeed(done: Long, now: Long) {
        if (speedAt < 0 || done < speedBytes) { speedAt = now; speedBytes = done; return }
        val dt = (now - speedAt) / 1000.0
        if (dt < 2 || done <= speedBytes) return
        joinBytesPerS = (joinBytesPerS * 0.7 + (done - speedBytes) / dt * 0.3).coerceIn(MIN_JOIN_BPS, MAX_JOIN_BPS)
        speedAt = now; speedBytes = done
    }

    private fun measureRate(bytes: Long, now: Long) {
        if (rateAt < 0 || bytes < rateBytes) { rateAt = now; rateBytes = bytes; return }
        val dt = (now - rateAt) / 1000.0
        if (dt < 2) return
        if (bytes > rateBytes) { val r = (bytes - rateBytes) / dt; rate = rate?.let { it * 0.7 + r * 0.3 } ?: r }
        rateAt = now; rateBytes = bytes
    }

    private fun measureJoin(f: Double, now: Long) {
        if (joinAt < 0 || f <= 0.0) { joinAt = now; joinDone = f; return }
        val dt = (now - joinAt) / 1000.0
        if (dt < 1 || f <= joinDone) return
        val r = (f - joinDone) / dt
        joinRate = joinRate?.let { it * 0.7 + r * 0.3 } ?: r
        joinAt = now; joinDone = f
    }

    companion object {
        /** Assumed preparing time when the server gives no estimate. */
        const val DEFAULT_BUILD_S = PrepareProgress.DEFAULT_S
        /** A step is done at this fraction (sums of bytes may miss a few). */
        const val DONE = 0.999

        /**
         * This phone's join speed, bytes written per second, measured from the store's reports (start: 20 MB/s – the
         * field's Galaxy S10 wrote 495 MB in about 20 s); bounded. For a join that cannot report: its time from the map's size.
         */
        @Volatile var joinBytesPerS: Double = 20.0 * 1_048_576
        private const val MIN_JOIN_BPS = 1.0 * 1_048_576
        private const val MAX_JOIN_BPS = 500.0 * 1_048_576
    }
}

class MapLoadCallbacks(
    val onCancel: () -> Unit = {}, val onRetry: () -> Unit = {}, val onClose: () -> Unit = {},
    /** "Load now over mobile data" while it waits for Wi-Fi (once; the setting stays). */
    val onLoadOnMobile: () -> Unit = {},
)


/**
 * The window over the map. [ui] null = nothing to wait for. No flash (owner rule): it appears only when the wait lasts
 * ≥ 1 s, a step shows ≥ 2.5 s (the bar keeps moving), a tap answers at once ([NoFlash]).
 */
@Composable
fun MapLoadModal(ui: MapLoadUi?, cb: MapLoadCallbacks) {
    val gate = remember { NoFlash<MapLoadUi.Kind>(urgent = { it == MapLoadUi.Kind.FAILED }) }
    val lastRef = remember { arrayOfNulls<MapLoadUi>(1) }
    if (ui != null) lastRef[0] = ui
    val kind = rememberNoFlash(ui?.kind, gate) ?: return
    val shown = (ui ?: lastRef[0])?.copy(kind = kind) ?: return
    fun acted(f: () -> Unit): () -> Unit = { gate.userActed(android.os.SystemClock.uptimeMillis()); f() }
    val c = MapLoadCallbacks(acted(cb.onCancel), acted(cb.onRetry), acted(cb.onClose), acted(cb.onLoadOnMobile))
    WaitWindowDialog(shown.window, mapLoadButtons(shown, c))
}

/** The window's content (also used by the screenshot tests, which cannot show a Dialog window). */
@Composable
fun MapLoadPanel(ui: MapLoadUi, cb: MapLoadCallbacks) = WaitWindowView(ui.window, mapLoadButtons(ui, cb))

/** Cancel (+ "Load now over mobile data" while it waits for Wi-Fi); failed: Close (+ Try again). */
@Composable
private fun mapLoadButtons(ui: MapLoadUi, cb: MapLoadCallbacks): List<WaitButton> = if (ui.kind == MapLoadUi.Kind.FAILED)
    listOfNotNull(WaitButton(str(Res.string.common_close), cb.onClose),
        if (ui.retryable) WaitButton(str(Res.string.common_try_again), cb.onRetry) else null)
else listOfNotNull(WaitButton(str(Res.string.common_cancel), cb.onCancel),
    if (ui.kind == MapLoadUi.Kind.WIFI) WaitButton(MapLoadUi.LOAD_ON_MOBILE, cb.onLoadOnMobile) else null)

/** A tap on the world map where there is no detailed map: never a silent download from a tap (owner). */
@Composable
fun LoadAskDialog(regions: Int, bytes: Long, onLoad: () -> Unit, onNotNow: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text(str(Res.string.maps_ask_title)) },
        text = { DialogText(MapLoads.askText(regions, bytes)) },
        confirmButton = { TextButton(onClick = onLoad, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.maps_btn_load)) } },
        dismissButton = { TextButton(onClick = onNotNow, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_not_now)) } },
    )
}
