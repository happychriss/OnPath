package com.onpath.web

import com.onpath.app.cells.CellState
import com.onpath.app.domain.ActionPercent
import com.onpath.app.domain.LoadStep
import com.onpath.app.domain.OneBar
import com.onpath.app.domain.PrepareProgress
import com.onpath.app.domain.RouteShares
import com.onpath.app.domain.StepClock
import com.onpath.app.domain.StepShares
import com.onpath.app.domain.TimeLeft
import com.onpath.app.domain.WaitStep
import com.onpath.app.domain.WaitStepState
import com.onpath.app.domain.WaitSteps
import com.onpath.strings.*

/**
 * The planner's wait window (form A, owner 2026-11-19 „A, aber nie mehr als 3 Häkchen“) – the app's MapWait + RouteWait
 * (ui/MapLoad.kt, ui/progress/RouteWait.kt, mirrored: web/parity.json) on the app's shared math ([OneBar], [StepShares],
 * [RouteShares], [WaitSteps], [TimeLeft], [PrepareProgress]). In the planner a new place's map, its routing data and the
 * route calculation are steps of ONE window: the map part (prepare – the server builds a map area –, load – map tiles,
 * routing data and the engine, by bytes –, join – the routing data written into the engine's files, only when routing
 * data came) and the route part (each leg the router works on, then „prüfen“). Each step owns its learned share of the
 * ONE bar 0–100: the map steps [StepShares], the check [RouteShares], and – the planner's own rule – the route part's
 * share of a wait that has both ([routePart], [ROUTE_PART]). Never back, never standing, at most 3 rows. The page ticks
 * it about every half second (a second Wasm instance on the page's thread – the engine worker is blocked while BRouter
 * routes); the learned shares go to the browser's storage (progressmemory.ts) when a wait ends. Pure (time passed in).
 */
class WebWait(private var shares: StepShares, private var check: Double, private var routePart: Double) {
    /** One map area the server prepares for this wait: [state] as areas.ts names it. */
    data class Area(val id: String, val state: String, val progress: Double, val etaS: Int?)

    /** The route run of this wait: [done] of [total] legs to route, [checking] = every leg routed, the result worked out. */
    data class Route(val done: Int, val total: Int, val checking: Boolean, val labels: List<String>?)

    /**
     * What is measured now. [dlTotal] 0 = nothing downloads; [join] = the routing data written into the engine's files: 0
     * before (while it downloads), 0..1 while it is written, null = no join step (no routing data) or done; [route] = the
     * route run of this wait, from its start ([Route.total] 0 = its legs not known yet); [paused] = offline.
     */
    data class Tick(
        val now: Long, val areas: List<Area> = emptyList(), val dlDone: Long = 0, val dlTotal: Long = 0,
        val map: Boolean = false, val routing: Boolean = false, val join: Double? = null, val route: Route? = null,
        val paused: Boolean = false,
    )

    /** The window: title, sign (map / route), the bar's percent, at most 3 rows, the line under them. */
    data class Ui(val title: String, val route: Boolean, val percent: Int, val steps: List<WaitStep>, val line: String?,
                  val paused: Boolean, val late: Boolean)

    private val bar = OneBar()
    private val clock = StepClock()
    private val prep = PrepareProgress()
    // what this wait had (it never forgets a part: a done part counts as done)
    private var hadPrepare = false
    private var hadMap = false
    private var hadRouting = false
    private var hadJoin = false
    private var hadRoute = false
    private var dlTotal = 0L
    private var dlDone = 0L
    /** Something downloaded in this wait; the load step's fraction so far (a placeholder's size replaced by the real one never moves it back). */
    private var hadDl = false
    private var dlFracMax = 0.0
    private var lastJoin = 0.0
    private var areaIds = LinkedHashSet<String>()
    /** The areas the server gave a time estimate for in this wait (the others' prepare time is only the assumed default). */
    private val etaSeen = HashSet<String>()
    // the download rate (bytes / s) for the load step's own time left
    private var rateAt = -1L
    private var rateBytes = 0L
    private var rate: Double? = null
    // the route part's clock: when it started, when the check started; when the map part ended
    private var routeFromMs = -1L
    // the route part's legs: how many were done at the last tick, when the last one was done
    private var legsDone = 0
    private var legAtMs = -1L
    private var checkFromMs = -1L
    private var mapEndMs = -1L
    private var startMs = -1L
    private var lastMs = -1L
    private var paused = false
    private var last: Ui? = null

    /** The window now (call about every half second while it waits). */
    fun tick(t: Tick): Ui {
        val now = t.now
        if (startMs < 0) startMs = now
        if (lastMs >= 0 && now - lastMs > OneBar.MAX_GAP_MS) rateAt = -1L
        lastMs = now
        if (t.paused) paused = true
        if (t.areas.isNotEmpty()) hadPrepare = true
        t.areas.forEach { areaIds += it.id; if (it.etaS != null) etaSeen += it.id }
        if (t.map) hadMap = true
        if (t.routing) hadRouting = true
        if (t.join != null) { hadJoin = true; lastJoin = maxOf(lastJoin, t.join.coerceIn(0.0, 1.0)) }
        dlTotal = t.dlTotal.coerceAtLeast(0)
        dlDone = t.dlDone.coerceIn(0, dlTotal)
        if (dlTotal > 0) hadDl = true
        val route = t.route
        if (route != null) hadRoute = true
        // nothing measured yet and no route: a map wait that has not begun (its load step at 0)
        val mapPart = hadPrepare || hadMap || hadRouting || hadDl || !hadRoute
        // the map steps there are, their fractions
        val prepFrac = if (!hadPrepare) 1.0 else {
            val byId = t.areas.associateBy { it.id }
            areaIds.map { id -> byId[id]?.let { prepareFraction(it, now) } ?: 1.0 }.average()
        }
        val dlNow = if (dlTotal <= 0) (if (hadDl || route != null || hadJoin) 1.0 else 0.0) else (dlDone.toDouble() / dlTotal).coerceIn(0.0, 1.0)
        dlFracMax = maxOf(dlFracMax, dlNow)
        val dlFrac = dlFracMax
        // the join step: 0 before, between while it runs ([Tick.join]); not reported any more = done
        val joinFrac = if (t.join == null) 1.0 else lastJoin
        val mapSteps = if (!mapPart) emptyList() else listOfNotNull(LoadStep.PREPARE.takeIf { hadPrepare }, LoadStep.DOWNLOAD,
            LoadStep.JOIN.takeIf { hadJoin })
        val mapFrac = mapOf(LoadStep.PREPARE to prepFrac, LoadStep.DOWNLOAD to dlFrac, LoadStep.JOIN to joinFrac)
        // the parts' shares of the bar
        val r = when { !hadRoute -> 0.0; !mapPart -> 1.0; else -> boundedPart(routePart) }
        val m = 1.0 - r
        val mapSum = mapSteps.sumOf { shares.of(it) }.takeIf { it > 0 } ?: 1.0
        // every step of the wait in its order: (share of the bar, fraction done)
        val legs = route?.total?.coerceAtLeast(1) ?: 1
        val done = (route?.done ?: 0).coerceIn(0, legs)
        val checking = route != null && (route.checking || (route.total > 0 && route.done >= route.total))
        if (checking && checkFromMs < 0) checkFromMs = now
        val all = ArrayList<Pair<Double, Double>>()
        mapSteps.forEach { all += m * shares.of(it) / mapSum to mapFrac.getValue(it) }
        if (hadRoute) {
            val c = RouteShares.bounded(check)
            repeat(legs) { i -> all += r * (1 - c) / legs to (if (checking || i < done) 1.0 else 0.0) }
            all += r * c to 0.0
        }
        val raw = all.sumOf { it.first * it.second }
        val cur = all.indexOfFirst { it.second < DONE }.let { if (it < 0) all.lastIndex else it }
        val stepStart = all.take(cur).sumOf { it.first }
        val stepEnd = stepStart + all[cur].first + all.drop(cur + 1).sumOf { it.first * it.second }
        val inMap = cur < mapSteps.size
        val mapStep = if (inMap) mapSteps[cur] else null
        if (mapPart && !inMap && mapEndMs < 0) mapEndMs = now
        if (!inMap && routeFromMs < 0) routeFromMs = now
        if (!inMap && legAtMs < 0) legAtMs = routeFromMs
        if (!inMap && done > legsDone) { legsDone = done; legAtMs = now }
        val running = !t.paused
        if (running && mapStep == LoadStep.DOWNLOAD) measureRate(dlDone, now) else if (!running) rateAt = -1L
        if (mapStep != null) clock.at(mapStep, now, running)
        // the running step's own time left
        val stepLeftS: Int? = when (mapStep) {
            LoadStep.PREPARE -> t.areas.mapNotNull { prep.leftS(it.id, cellState(it), now) }.maxOrNull()
            LoadStep.DOWNLOAD -> rate?.takeIf { it > 0 }?.let { ((dlTotal - dlDone) / it).toInt() }
            LoadStep.JOIN -> null
            null -> if (checking) {
                val legsMs = (checkFromMs - routeFromMs).coerceAtLeast(0)
                val expected = legsMs * RouteShares.bounded(check) / (1 - RouteShares.bounded(check))
                (maxOf(expected - (now - checkFromMs), expected / 2) / 1000).toInt()
            } else if (done > 0) {
                val per = (now - routeFromMs).toDouble() / done
                (per / 1000).toInt()
            } else null
        }
        val pct = bar.next(raw, stepEnd, stepLeftS?.let { it * 1000L }, now, paused = t.paused, stepStart = stepStart)
        // the time left (owner 2026-11-19, München → Hilden: „bereit in 5 s“ – then the one 600 km leg took minutes): a
        // number only when the rest of the wait is measured – never the bar's pace over steps that are no measure of
        // what is left (the map part's quick steps, the bar's creep inside a leg the router says nothing about)
        val left: Int? = when {
            !running -> null
            // a route lies ahead: its legs are not measured yet
            inMap && hadRoute -> null
            // the prepare step without the server's estimate for every open area: its time is only the assumed
            // default (the „3 minutes“ flash before the worker's first report) – no number before a measurement
            mapStep == LoadStep.PREPARE && t.areas.any { it.state != "ready" && it.state != "failed" && it.id !in etaSeen } -> null
            // the load step without routing data: map tiles (and the engine) are counted at an assumed size, their
            // number grows as the map asks for more – no measure of what is left (CI 2026-11-20: the page's first map
            // said „about 5 seconds left“ before the map server's answer). Routing data comes by its real bytes.
            mapStep == LoadStep.DOWNLOAD && !t.routing -> null
            inMap -> TimeLeft.seconds(raw, now - startMs, stepLeftS)
            checking -> TimeLeft.seconds(raw, now - startMs, stepLeftS)
            else -> legsLeftS(legs, done, now)
        }
        val overLeg = running && !inMap && !checking && done > 0 && left == null
        // the rows: the map steps' words as the app's MapLoadUi, the legs as the app's RouteWait
        val load = when {
            !hadMap && !hadPrepare && hadRouting -> Txt.s(Res.string.wait_step_loading_routing)
            hadRouting -> Txt.s(Res.string.wait_step_loading_map_routing)
            else -> Txt.s(Res.string.wait_step_loading_map)
        }
        val labels = ArrayList<Pair<String, String?>>()
        mapSteps.forEachIndexed { i, s ->
            val st = if (i < cur) WaitStepState.DONE else if (i == cur) WaitStepState.NOW else WaitStepState.LATER
            labels += when (s) {
                LoadStep.PREPARE -> when (st) {
                    WaitStepState.NOW -> Txt.s(Res.string.maps_phase_preparing); WaitStepState.DONE -> Txt.s(Res.string.wait_step_prepared)
                    else -> Txt.s(Res.string.wait_step_prepare)
                } to null
                LoadStep.DOWNLOAD -> when (st) {
                    WaitStepState.NOW -> load to Txt.s(Res.string.maps_phase_loading); WaitStepState.DONE -> Txt.s(Res.string.wait_step_loaded) to null
                    else -> Txt.s(Res.string.wait_step_load) to null
                }
                LoadStep.JOIN -> (if (st == WaitStepState.NOW) Txt.s(Res.string.maps_phase_finishing) else Txt.s(Res.string.wait_step_join)) to null
            }
        }
        if (hadRoute) {
            val names = route?.labels?.takeIf { it.size == legs } ?: (1..legs).map { Txt.s(Res.string.wait_leg, it) }
            names.forEach { labels += it to null }
            labels += (if (checking) Txt.s(Res.string.wait_step_checking) else Txt.s(Res.string.wait_step_check)) to null
        }
        val late = running && bar.late
        val line = when {
            t.paused -> Txt.s(Res.string.maps_offline_text)
            late || overLeg -> TimeLeft.LATE
            else -> left?.let(TimeLeft::text)
        }
        val title = if (inMap) Txt.s(Res.string.wait_title_map) else Txt.s(Res.string.wait_title_route)
        return Ui(title, !inMap, pct, WaitSteps.shown(WaitSteps.of(labels, cur)), line, t.paused, late || overLeg).also { last = it }
    }

    /**
     * The route part's time left while the router works on a leg: the legs done so far in this wait give the pace (the
     * router says nothing within a leg) – the rest of this leg, the legs after it, the check by its share. Null = not
     * measurable: no leg done yet (one long leg: no number at all), or this leg already took longer than the legs before
     * it (a longer leg – no number it cannot keep).
     */
    private fun legsLeftS(legs: Int, done: Int, now: Long): Int? {
        if (done <= 0 || routeFromMs < 0 || legAtMs < routeFromMs) return null
        val per = (legAtMs - routeFromMs).toDouble() / done
        val inLeg = (now - legAtMs).toDouble()
        if (per <= 0 || inLeg > per) return null
        val c = RouteShares.bounded(check)
        val ms = (per - inLeg) + per * (legs - done - 1).coerceAtLeast(0) + per * legs * c / (1 - c)
        return (ms / 1000).toInt()
    }

    /**
     * The wait ended at [now]: [finished] = everything is there (a last picture at 100 %, and – when it ran without a
     * pause – what it teaches: the map steps' shares, the check's share, the route part's share). Cancelled: nothing learned.
     */
    fun end(now: Long, finished: Boolean): Learned {
        if (!finished || paused) return Learned(shares, check, routePart)
        val mapPart = hadPrepare || hadMap || hadRouting || hadDl
        if (clock.started && !clock.hadPause) shares = shares.learn(clock.durations())
        if (hadRoute && checkFromMs > routeFromMs && routeFromMs >= 0) check = RouteShares.learn(check, checkFromMs - routeFromMs, now - checkFromMs)
        if (hadRoute && mapPart && startMs >= 0) {
            // the map part ran from the start until its last step was done (or the route began); the route part to the end
            val mapMs = ((if (mapEndMs > 0) mapEndMs else routeFromMs) - startMs).coerceAtLeast(0)
            val routeMs = (now - maxOf(routeFromMs, startMs + mapMs)).coerceAtLeast(0)
            routePart = learnPart(routePart, mapMs, routeMs)
        }
        return Learned(shares, check, routePart)
    }

    /** The last window shown, at 100 % (the wait is over: a moment of the full bar before it goes). */
    fun full(now: Long): Ui? = last?.let { it.copy(percent = bar.next(1.0, 1.0, null, now, finished = true), line = null, late = false,
        steps = it.steps.map { s -> s.copy(state = WaitStepState.DONE) }) }

    data class Learned(val shares: StepShares, val check: Double, val routePart: Double)

    private fun cellState(a: Area): CellState = when (a.state) {
        "queued" -> CellState.Queued(null, a.etaS)
        "building" -> CellState.Building("", a.progress, a.etaS)
        "ready" -> CellState.Ready("")
        "failed" -> CellState.Failed("")
        "offline" -> CellState.Offline("")
        else -> CellState.Requested
    }

    /** One area's prepare progress 0..1: the server's own progress or its time left, whichever is further (the app's CellProgress). */
    private fun prepareFraction(a: Area, now: Long): Double {
        val s = cellState(a)
        if (s is CellState.Ready || s is CellState.Failed) return 1.0
        val byTime = prep.fraction(a.id, s, now) ?: 0.0
        return maxOf(byTime, if (s is CellState.Building) a.progress.coerceIn(0.0, 1.0) else 0.0).coerceAtMost(0.99)
    }

    private fun measureRate(bytes: Long, now: Long) {
        if (rateAt < 0 || bytes < rateBytes) { rateAt = now; rateBytes = bytes; return }
        val dt = (now - rateAt) / 1000.0
        if (dt < 2) return
        if (bytes > rateBytes) { val v = (bytes - rateBytes) / dt; rate = rate?.let { it * 0.7 + v * 0.3 } ?: v }
        rateAt = now; rateBytes = bytes
    }

    companion object {
        /** A step is done at this fraction (sums of bytes may miss a few) – the app's MapWait.DONE. */
        const val DONE = 0.999
        /** The route part's share of a wait with a map part too: start value, bounds. */
        const val ROUTE_PART = 0.30
        const val MIN_PART = 0.10
        const val MAX_PART = 0.70

        fun boundedPart(p: Double): Double = if (p.isNaN()) ROUTE_PART else p.coerceIn(MIN_PART, MAX_PART)

        /** The route part's share after a wait whose map part took [mapMs] and route part [routeMs] (StepShares' weight). */
        fun learnPart(p: Double, mapMs: Long, routeMs: Long): Double {
            if (mapMs + routeMs < StepShares.MIN_LEARN_MS || routeMs <= 0 || mapMs <= 0) return boundedPart(p)
            return boundedPart(p * (1 - StepShares.WEIGHT) + routeMs.toDouble() / (mapMs + routeMs) * StepShares.WEIGHT)
        }

        /** "p1:0.3000" for storage; [decodePart] gives [ROUTE_PART] for anything else. */
        fun encodePart(p: Double): String = "p1:" + fixed4(boundedPart(p))
        fun decodePart(s: String?): Double = s?.takeIf { it.startsWith("p1:") }?.removePrefix("p1:")?.toDoubleOrNull()?.let(::boundedPart) ?: ROUTE_PART

        /** 4 decimals without String.format (as the stored forms of StepShares / RouteShares read them back). */
        internal fun fixed4(v: Double): String {
            val n = Math.round(v * 10_000)
            val s = n.toString().padStart(5, '0')
            return s.dropLast(4) + "." + s.takeLast(4)
        }

        /** The stored forms: StepShares "v1:…", RouteShares "r1:…" – written here without String.format (TeaVM). */
        fun encodeShares(s: StepShares): String = "v1:" + listOf(s.prepare, s.download, s.join).joinToString(",") { fixed4(it) }
        fun encodeCheck(c: Double): String = "r1:" + fixed4(RouteShares.bounded(c))

        /**
         * The legs' names "Algund → Leiter Alm" (the app's RouteWait.legLabels): from the Points list's names ([names]: plan
         * index → name) when the router works on every leg of the [points] ([total] = points − 1); else null (counted).
         */
        fun legLabels(names: Map<Int, String>, points: Int, total: Int): List<String>? =
            // the app's own rule (web-ready ui/progress/WaitRules.kt): the names as Points list rows (role / length unused)
            com.onpath.app.ui.progress.RouteWait.legLabels(names.map { (i, n) ->
                com.onpath.app.routing.PlanPointRow(i, com.onpath.app.routing.PointRole.STOP, n, null, false)
            }, points, total)

        // ---- the page's one wait (one window at a time) ----
        private var current: WebWait? = null

        /** A new wait with the learned values as stored ("v1:…", "r1:…", "p1:…"; anything else = the start values). */
        fun start(shares: String, check: String, part: String) {
            current = WebWait(StepShares.decode(shares), RouteShares.decode(check), decodePart(part))
        }

        /**
         * The window now, JSON. [areas] lines "id<TAB>state<TAB>progress<TAB>eta" (eta −1 = none); [dlTotal] 0 = nothing
         * downloads; [join] < 0 = no join step; [routeTotal] < 0 = no route run; [names] = lines "index<TAB>name" of the
         * Points list; [points] = the plan's point count.
         */
        fun tick(now: Double, areas: String, dlDone: Double, dlTotal: Double, map: Boolean, routing: Boolean, join: Double,
                 routeDone: Int, routeTotal: Int, checking: Boolean, names: String, points: Int, paused: Boolean): String {
            val w = current ?: WebWait(StepShares.DEFAULT, RouteShares.DEFAULT_CHECK, ROUTE_PART).also { current = it }
            val list = areas.split('\n').filter { it.isNotBlank() }.mapNotNull { l ->
                val f = l.split('\t')
                if (f.size < 4) null else Area(f[0], f[1], f[2].toDoubleOrNull() ?: 0.0, f[3].toIntOrNull()?.takeIf { it >= 0 })
            }
            val route = if (routeTotal < 0) null else Route(routeDone, routeTotal, checking,
                legLabels(Engine.parseNames(names), points, routeTotal))
            return json(w.tick(Tick(now.toLong(), list, dlDone.toLong(), dlTotal.toLong(), map, routing, join.takeIf { it >= 0 }, route, paused)))
        }

        /** The wait ended: `{"full": <the last window at 100 % or null>, "shares": "v1:…", "check": "r1:…", "part": "p1:…"}`. */
        fun end(now: Double, finished: Boolean): String {
            val w = current ?: return "{\"full\":null}"
            current = null
            val l = w.end(now.toLong(), finished)
            val full = if (finished) w.full(now.toLong())?.let(::json) ?: "null" else "null"
            return "{\"full\":$full,\"shares\":${Engine.str(encodeShares(l.shares))},\"check\":${Engine.str(encodeCheck(l.check))}," +
                "\"part\":${Engine.str(encodePart(l.routePart))}}"
        }

        fun json(u: Ui): String = buildString {
            append("{\"title\":").append(Engine.str(u.title)).append(",\"route\":").append(u.route)
            append(",\"percent\":").append(u.percent).append(",\"steps\":[")
            u.steps.forEachIndexed { i, s ->
                if (i > 0) append(',')
                append("{\"label\":").append(Engine.str(s.label)).append(",\"state\":").append(Engine.str(s.state.name.lowercase())).append('}')
            }
            append("],\"line\":").append(u.line?.let { Engine.str(it) } ?: "null")
            append(",\"paused\":").append(u.paused).append(",\"late\":").append(u.late).append('}')
        }
    }
}

/**
 * Form B in the planner (owner 2026-11-19 „B: einfach nur Prozent, muss aber passen für die gesamte Aktion“): the map
 * areas the server prepares while the user plans on (along the route, around the position – the app's background
 * loads) – ONE percent over all of them ([ActionPercent]; each area's progress the server's own or its time left,
 * [PrepareProgress], as the app's sign). Null = nothing loads (the sign goes).
 */
object WebSign {
    private val action = ActionPercent()
    private val prep = PrepareProgress()

    /** [areas] lines "id<TAB>state<TAB>progress<TAB>eta" of the areas loading in the background; −1 = none. */
    fun tick(now: Double, areas: String): Int {
        val items = areas.split('\n').filter { it.isNotBlank() }.mapNotNull { l ->
            val f = l.split('\t')
            if (f.size < 4) return@mapNotNull null
            val a = WebWait.Area(f[0], f[1], f[2].toDoubleOrNull() ?: 0.0, f[3].toIntOrNull()?.takeIf { it >= 0 })
            val s = when (a.state) {
                "queued" -> CellState.Queued(null, a.etaS); "building" -> CellState.Building("", a.progress, a.etaS)
                "ready" -> CellState.Ready(""); "failed" -> CellState.Failed(""); "offline" -> CellState.Offline("")
                else -> CellState.Requested
            }
            val frac = when (s) {
                is CellState.Ready, is CellState.Failed -> 1.0
                else -> maxOf(prep.fraction(a.id, s, now.toLong()) ?: 0.0, if (s is CellState.Building) a.progress else 0.0).coerceIn(0.0, 0.99)
            }
            ActionPercent.Item(a.id, 0L, frac)
        }
        prep.keep(items.map { it.key }.toSet())
        // every area done: the action is over
        val open = items.filter { it.fraction < 1.0 }
        return action.update(if (open.isEmpty()) emptyList() else items) ?: -1
    }

    /** The window handed over at [percent] (a wait turned into a background load). */
    fun seed(percent: Int) = action.seed(percent)
}
