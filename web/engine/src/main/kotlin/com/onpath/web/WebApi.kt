// the page's slice calls live in WebState.kt (WebStateApi): exported beside these as the module's "WebStateApi"
@file:org.teavm.jso.JSExportClasses(WebStateApi::class)
package com.onpath.web

import com.onpath.app.data.Format
import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import com.onpath.app.routing.Activity
import com.onpath.app.routing.BRouterEngine
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.PlanEdits
import com.onpath.app.routing.PlanPoints
import com.onpath.app.routing.PlanResult
import com.onpath.app.routing.Planner
import com.onpath.app.routing.RouteCancel
import com.onpath.app.ui.plan.PlanRecalcState
import com.onpath.app.routing.SharedPlan
import com.onpath.app.routing.RouteSummary
import com.onpath.app.routing.WaypointInsertion
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.RoutingException
import com.onpath.app.routing.Share
import com.onpath.app.routing.SlowPlan
import com.onpath.app.routing.LegCache
import com.onpath.app.routing.LegSplit
import com.onpath.app.routing.OfflineCorridor
import com.onpath.app.domain.PlanStatus
import com.onpath.app.time.RouteTimes
import com.onpath.app.poi.PoiLinks
import com.onpath.app.recording.GpsStatus
import com.onpath.app.domain.MapGestures
import com.onpath.app.domain.PlanHistory
import com.onpath.app.domain.RouteWarnings
import com.onpath.strings.*
import org.teavm.jso.JSBody
import org.teavm.jso.JSExport
import org.teavm.jso.typedarrays.Int8Array
import java.io.File
import java.io.FileOutputStream

/**
 * The web engine's whole API (docs/design/web-planner.md §6). The page (in a Web Worker) first hands over the files
 * BRouter reads – profiles under `<root>/assets/brouter/`, rd5 data under `<root>/segments/` – then asks for routes.
 * Everything behind it is the app's own code ([BRouterEngine], [RouteTimes]), unchanged. The same functions run on
 * the JVM for the comparison test (web/test), so both sides produce the same JSON from the same code.
 */
object Engine {
    private var root = File("/onpath")
    private var engine: BRouterEngine? = null

    /**
     * The legs of earlier plan runs (the app's [LegCache], PLANNER_VERSION 4, docs/design/long-bike-routes.md): one per
     * engine, as the app keeps one beside its router – an edit routes only the legs it changed. Only routed legs are kept,
     * so a leg that failed while its routing data was still loading is routed again.
     */
    private val legCache = LegCache(store = WebLegStore({ routingFingerprint() }, ::partVersion, ::partMissing).also { legStore = it })
    /** The legs kept in the browser (IndexedDB, carried in and out by the worker – [WebLegStore], owner 2026-11-18). */
    internal lateinit var legStore: WebLegStore
        private set

    /** A part's routing data version for the leg store: its tile's creation time; "-" = no such tile (or not known). */
    private fun partVersion(p: Rd5Parts.Part): String = tileCreated(p.tile)?.toString() ?: "-"

    /** The part has data in its tile but it is not loaded (its download failed) – a leg routed now went round it. */
    private fun partMissing(p: Rd5Parts.Part): Boolean =
        p !in have && (layouts[p.tile]?.first?.let { it.size(p.index) > 0 } ?: false)

    /**
     * The legs of [points] the worker should read from the browser's store before planning them (names, JSON): the plan's
     * legs not in memory now. What was handed over for an earlier plan is dropped.
     */
    fun legsWanted(points: String, activity: String, sac: Int): String = try {
        val wps = parsePoints(points)
        val act = Activity.valueOf(activity)
        legStore.wanted(emptyList())
        LegSplit.seed(legCache, wps, act, sac) // a point put in on a routed leg: its two halves, no store read
        val keys = (0 until wps.size - 1).filter { legCache.lookup(wps[it], wps[it + 1], act, sac) == null }
            .map { LegCache.key(wps[it], wps[it + 1], act, sac) }
        legStore.wanted(keys).joinToString(",", "[", "]") { str(it) }
    } catch (e: Exception) {
        Problems.warn("routing", "Stored legs not looked up – the plan routes them", e); "[]"
    }

    /** The last plan's legs: routed by the router, taken from memory, taken from the browser's store (the smokes' hook). */
    internal var lastLegs = "{}"

    /** Where a plan's progress text goes: the page in the Wasm build ([planProgress]); null on the JVM (JvmRun, tests). */
    internal var progressSink: ((String) -> Unit)? = null

    fun init(rootDir: String) {
        root = File(rootDir)
        android.content.res.AssetManager.root = File(root, "assets")
        engine = null
        fingerprint = null // of the profiles handed over to this root
        legCache.clear() // other profiles / routing data: every leg is routed again
    }

    fun putFile(relPath: String, bytes: ByteArray) {
        val f = File(root, relPath)
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { it.write(bytes) }
    }

    internal fun engine(): BRouterEngine {
        if (dirty.isNotEmpty()) writeParts()
        return engine ?: BRouterEngine(WebContext(File(root, "files")), File(root, "segments")).also { engine = it }
    }

    internal fun parsePoints(points: String): List<GeoPoint> = points.split(';').filter { it.isNotBlank() }.map { p ->
        val (la, lo) = p.split(',').map { it.trim().toDouble() }
        GeoPoint(la, lo)
    }

    /**
     * The 1° parts a plan needs – the phone's rule ([Rd5Parts.forCorridor]): JSON `[["E10_N45.rd5", 6], …]`.
     * Parts already in place are left out.
     */
    /**
     * The routing parts [points] need that are not loaded yet – in the phone's corridor for [activity] (an Activity name;
     * "" = not known → the largest corridor): OfflineCorridor, 10 km on foot, 20 km by bike (owner 2026-11-07, app 501db30d).
     */
    fun partsNeeded(points: String, activity: String = ""): String =
        Rd5Parts.forCorridor(parsePoints(points), OfflineCorridor.marginM(activityOrNull(activity))).filter { it !in have }
            .joinToString(",", "[", "]") { "[" + str(it.tile) + "," + it.index + "]" }

    /** An Activity name, or null when empty / unknown (never throws: a wrong name means "not known"). */
    private fun activityOrNull(name: String): Activity? = Activity.entries.firstOrNull { it.name == name }

    private val have = HashSet<Rd5Parts.Part>()
    private val layouts = HashMap<String, Pair<Rd5Parts.Index, ByteArray>>()
    private val partBytes = HashMap<String, MutableMap<Int, ByteArray>>()

    /** Remote layout of [tile]: its first 200 bytes and its tail (bytes after the last part). */
    fun putLayout(tile: String, index: ByteArray, tail: ByteArray) {
        val ix = Rd5Parts.Index(index)
        ix.check()
        val before = layouts[tile]?.second
        // a newer build of the tile (the page's copy is at most a day old): the same points may route differently now
        if (before != null && Rd5Parts.creationTime(before) != Rd5Parts.creationTime(tail)) legCache.clear()
        layouts[tile] = ix to tail
    }

    /**
     * One downloaded part. The local rd5 for [tile] is rewritten with every part we have (the phone does the same with
     * [Rd5Parts.buildIndex] / [Rd5Parts.buildTail]) once, before the next route ([writeParts]) – not once per part.
     * Returns false when the bytes do not fit the layout.
     */
    fun putPart(tile: String, index: Int, bytes: ByteArray): Boolean {
        val (ix, _) = layouts[tile] ?: return false
        if (bytes.size.toLong() != ix.size(index)) return false
        partBytes.getOrPut(tile) { HashMap() }[index] = bytes
        have += Rd5Parts.Part.of(tile, index)
        dirty += tile
        return true
    }

    private val dirty = HashSet<String>()

    // ---- routing fingerprint + routing data dates (routing/RoutingFingerprint, sync fields 7 / 8, web-sync.md §5.2) ----
    private var fingerprint: String? = null

    /**
     * This engine's routing fingerprint (the app's [com.onpath.app.routing.RoutingFingerprint] of the BRouter profiles
     * and lookups the page handed over – the same files as the app's assets → the same text as the app's Diagnostics).
     * "" = not readable (then nothing is compared, never a guess).
     */
    fun routingFingerprint(): String = fingerprint ?: try {
        com.onpath.app.routing.RoutingFingerprint.of({ n -> File(root, "assets/brouter/$n").readBytes() }).also { fingerprint = it }
    } catch (e: Exception) {
        Problems.warn("routing", "Routing fingerprint not computed (profiles not readable)", e); ""
    }

    /** BRouter's creation time (ms) of a tile the engine loaded in parts – from its tail; null = not known. */
    private fun tileCreated(tile: String): Long? = layouts[tile]?.second?.let { Rd5Parts.creationTime(it) }

    /**
     * Sync field 8 for a plan through [points]: the creation day of each loaded tile under its points
     * ([com.onpath.app.sync.SyncRoutingData], "E10_N45:20752,…"); null = none known (no field).
     */
    internal fun routingData(points: List<GeoPoint>): String? =
        com.onpath.app.sync.SyncRoutingData.encode(points.map { Rd5Parts.tileName(it.lat, it.lon) }.toSet()
            .mapNotNull { t -> tileCreated(t)?.let { t to com.onpath.app.sync.SyncRoutingData.day(it) } }.toMap())

    /** The diagnostics line, as the app's Settings → Diagnostics: "r1-… · BRouter … · planner 1 · data E10_N45 2026-10-26". */
    fun routingInfo(): String {
        val fp = routingFingerprint().ifEmpty { return "routing fingerprint unknown" }
        return com.onpath.app.routing.RoutingFingerprint.describe(fp, layouts.keys.sorted().map { it to tileCreated(it) })
    }

    /**
     * Writes the rd5 files that got new parts. The engine (and its result cache) stays: BRouter opens the segment
     * files anew for every route, and a cached leg was routed with every part its corridor needs.
     */
    fun writeParts() {
        for (tile in dirty) {
            val (ix, tail) = layouts[tile] ?: continue
            val parts = partBytes[tile] ?: continue
            val present = parts.keys.filter { ix.size(it) > 0 }.toSortedSet()
            val newIndex = Rd5Parts.buildIndex(ix, present)
            val newTail = Rd5Parts.buildTail(ix, tail, newIndex)
            val f = File(root, "segments/$tile")
            f.parentFile?.mkdirs()
            FileOutputStream(f).use { out ->
                out.write(newIndex)
                for (i in present) out.write(parts.getValue(i))
                out.write(newTail)
            }
        }
        dirty.clear()
    }

    /** [points] = "lat,lon;lat,lon;…", [activity] = Activity name. Returns JSON (route or {"error": …}). */
    fun route(points: String, activity: String, sac: Int): String {
        val t0 = System.currentTimeMillis()
        return try {
            val wps = parsePoints(points)
            val act = Activity.valueOf(activity)
            val r = engine().route(wps, act, sac)
            val ms = System.currentTimeMillis() - t0
            val times = RouteTimes.of(r.points, act)
            buildString {
                append("{\"distanceM\":").append(num(r.distanceM))
                append(",\"ascentM\":").append(num(r.ascentM))
                append(",\"descentM\":").append(num(r.descentM))
                append(",\"durationS\":").append(num(r.durationS))
                append(",\"timeS\":").append(num(times.total()))
                append(",\"ms\":").append(ms)
                append(",\"wayTypes\":").append(shares(r.wayTypes))
                append(",\"surfaces\":").append(shares(r.surfaces))
                append(",\"points\":[")
                r.points.forEachIndexed { i, p ->
                    if (i > 0) append(',')
                    append('[').append(num(p.lat, 6)).append(',').append(num(p.lon, 6)).append(',')
                        .append(p.ele?.let { num(it, 1) } ?: "null").append(']')
                }
                append("]}")
            }
        } catch (e: RoutingException) {
            "{\"error\":" + str(e.message ?: "") + ",\"ms\":" + (System.currentTimeMillis() - t0) + "}"
        } catch (e: Exception) {
            Problems.error("web", "Route request failed", e)
            "{\"error\":" + str("Unexpected: $e") + "}"
        }
    }

    /**
     * The last plan result (the app's [PlanResult] – route, model time, snapped points, gaps, warnings, rating, profile),
     * one for every page call that needs "the plan of these points" (rows, gaps, insert, GPX, pace).
     */
    private var lastResult: PlanResult? = null

    /** The same points at the page's 6 decimals (the page sends them rounded; a snapped point comes back rounded). */
    private fun same6(a: List<GeoPoint>, b: List<GeoPoint>): Boolean = a.size == b.size &&
        a.indices.all { num(a[it].lat, 6) == num(b[it].lat, 6) && num(a[it].lon, 6) == num(b[it].lon, 6) }

    /** The last result when it was made for [wps] – as routed or as snapped (the page then sends the snapped points). */
    internal fun lastResultFor(wps: List<GeoPoint>): PlanResult? =
        lastResult?.takeIf { same6(it.routed, wps) || same6(it.points, wps) }

    /** The last plan when it was made for [wps] (as routed or snapped). */
    internal fun lastPlanFor(wps: List<GeoPoint>): PlannedRoute? = lastResultFor(wps)?.route

    /** The last plan's line when it was made for [wps], [a] and [sac] (WebPace.planTime), else null. */
    internal fun lastPlanPoints(wps: List<GeoPoint>, a: Activity, sac: Int): List<GeoPoint>? =
        lastResultFor(wps)?.takeIf { it.activity == a && it.sac == sac }?.route?.points

    /**
     * Plans like the phone's plan sheet – the app's one plan computation ([PlanResult.plan], the same call as
     * AppViewModel.recalc): the [Planner] (leg by leg, a failed leg a straight dashed line with a plain message), OnPath's
     * time model with the page's pace setting ([WebPace.factors]), free points snapped onto the way the router used
     * ([pinned] = POI-pinned and [names] = named stops keep their place, so does a stop the route does not reach or
     * skips), the gaps, the route warnings, the difficulty and the profile. Only the JSON is the web's.
     * [names] = lines "index<TAB>name". [rounds] = time-limit periods of this run ([SlowPlan], owner 2026-11-03,
     * onpath.private#21 B): ≥ 1 = a plan the user started – at the limit the JSON is {"slow":rounds} and the page asks
     * "Weiter warten?" (the app's AppViewModel.answerSlowPlan); 0 = no question, straight lines as before (compare page).
     */
    fun plan(points: String, activity: String, sac: Int, pinned: String = "", names: String = "", rounds: Int = 0): String {
        val t0 = System.currentTimeMillis()
        return try {
            val wps = parsePoints(points)
            val act = Activity.valueOf(activity)
            val nm = parseNames(names)
            // the same points planned before with another activity / T level: the app's "New route for … – check it" –
            // never here (owner 2026-11-07, RouteWarnings.saysChanged): in the planner the change is always the user's
            // own, in Plan, with no tour running
            val before = lastResultFor(wps)?.takeIf { (it.activity != act || it.sac != sac) &&
                RouteWarnings.saysChanged(own = true, inPlan = true, touring = false) }
            // the run itself is the app's stage 2 (PlanRecalcState.route, umbau's cut 2026-11-20): a point put in on the
            // routed line cut there from the known line (LegSplit, a night on the route), the Planner with its time limit
            // (askAtLimit: "Weiter warten?"), PlanResult.plan – the one computation of app and planner. The page's Cancel is
            // a fresh worker (client.ts dropPlans), so the cancel signal handed in here never fires.
            // the wait window (WebWait, owner 2026-11-19 „Route berechnet ist auch A“): the legs the router works on are its
            // steps – each time a leg is done, {"done":2,"total":5} (the app's RouteWait reads the same Planner report)
            val run = PlanRecalcState.Run(gen = 0, pts = wps, pins = parseIndices(pinned), act = act, level = sac,
                rounds = rounds.coerceAtLeast(1), askAtLimit = rounds >= 1, f = WebPace.factors(act), started = t0,
                changeBefore = null, standBefore = null)
            var planner: Planner? = null
            val res = try {
                PlanRecalcState.route(run, engine(), legCache, RouteCancel(),
                    progress = { done, total -> progressSink?.invoke("{\"done\":$done,\"total\":$total}") },
                    onPlanner = { planner = it }).result
                    // the named stops keep their place (the app reads them when the result is taken: PlanResult.withNamed)
                    .withNamed(nm.filterValues { it.isNotBlank() }.keys)
            } catch (e: SlowPlan.TimeLimit) {
                // the time limit: the page asks "Weiter warten?" – the points and the last line stay as they are
                Problems.info("routing", "Plan reached its time limit after ${e.rounds} period(s) – asking to wait on")
                return "{\"slow\":${e.rounds},\"legs\":[]}"
            }
            lastResult = res
            val n = wps.size - 1
            val cached = planner?.cachedLegs ?: 0
            lastLegs = "{\"routed\":${n - cached},\"cached\":$cached,\"stored\":${legStore.hits}}"
            // a stop the route does not reach: what does reach it ("② erst ab T2 erreichbar", the app's PlanReach, owner
            // 2026-11-07) – only the missing piece is routed (~40 ms a try): the app's PlanRecalcState.findReach
            val reach = res.gaps.firstOrNull()?.takeIf { !it.skipped }?.let { g ->
                PlanRecalcState.findReach(g, act, sac, RouteCancel(), ::engine, System::currentTimeMillis)
            }
            val changed = before?.route?.takeIf { b ->
                RouteWarnings.changedNoticeably(b.points, b.distanceM, b.ascentM, res.route.points, res.route.distanceM, res.route.ascentM)
            }?.let { RouteWarnings.changedText(act, sac) }
            planJson(res, nm, System.currentTimeMillis() - t0, changed, reach)
        } catch (e: Exception) {
            Problems.error("web", "Plan request failed", e)
            // never the exception text for the user (CLAUDE.md robustness): the page shows its plain line, the detail
            // goes to the page's diagnostics
            "{\"error\":null,\"failed\":true,\"detail\":" + str("Unexpected: $e") + ",\"legs\":[]}"
        }
    }

    /** The page's JSON of a [PlanResult] (texts from the app's Format, RouteWarnings and PlanGaps). */
    internal fun planJson(res: PlanResult, names: Map<Int, String>, ms: Long, changed: String? = null,
                           reach: com.onpath.app.routing.PlanReach? = null): String = buildString {
        val p = res.route
        val sac = res.sac
        append("{\"distanceM\":").append(num(p.distanceM))
        append(",\"ascentM\":").append(num(p.ascentM))
        append(",\"descentM\":").append(num(p.descentM))
        // the model time (PlanResult.withModelTime: TimeEstimate.planSeconds with the pace setting), not the router's
        append(",\"timeS\":").append(num(p.durationS))
        append(",\"difficulty\":").append(str(res.rating.text))
        // the badge colour by level, not by the (translated) word – "Mittel" was red (easy / moderate / difficult)
        append(",\"level\":").append(str(res.rating.level.name.lowercase()))
        append(",\"because\":").append(res.rating.because?.let { str(it) } ?: "null")
        val top = p.points.mapNotNull { it.ele }.maxOrNull()
        append(",\"texts\":{\"time\":").append(str(Format.duration(p.durationS)))
        append(",\"distance\":").append(str(Format.distance(p.distanceM)))
        append(",\"climb\":").append(str("↑ ${Format.meters(p.ascentM)}  ↓ ${Format.meters(p.descentM)}" +
            (top?.let { "  ·  " + Txt.s(Res.string.plan_max_height, Format.meters(it)) } ?: ""))).append('}')
        append(",\"error\":").append(p.error?.let { str(it) } ?: "null")
        // route warnings and hints, the app's amber line and its "On this route" rows (ui/RouteWarnings)
        append(",\"warning\":").append(RouteWarnings.text(res.spots, sac)?.let { str(it) } ?: "null")
        append(",\"spots\":").append(res.spots.joinToString(",", "[", "]") { sp ->
            val piece = RouteWarnings.showPoints(p.points, sp)
            val c = RouteWarnings.centerFor(p.points, sp)
            "{\"title\":" + str(RouteWarnings.rowTitle(sp, sac)) + ",\"detail\":" + str(RouteWarnings.rowDetail(sp)) +
                ",\"zoom\":" + (c?.second?.let { num(it, 1) } ?: "null") + ",\"line\":" +
                piece.joinToString(",", "[", "]") { "[" + num(it.lat, 6) + "," + num(it.lon, 6) + "]" } + "}"
        })
        append(",\"ms\":").append(ms)
        append(",\"wayTypes\":").append(shares(p.wayTypes))
        append(",\"surfaces\":").append(shares(p.surfaces))
        // the plan points after snapping (same order as the request; the page takes them when moved)
        append(",\"moved\":").append(res.moved)
        append(",\"points\":").append(res.points.joinToString(",", "[", "]") { "[" + num(it.lat, 6) + "," + num(it.lon, 6) + "]" })
        append(",\"gaps\":").append(WebPlanRules.gapsJson(res, names, reach))
        // after an activity / T level change the route differs noticeably (RouteWarnings.changedNoticeably): the grey line
        append(",\"changed\":").append(changed?.let { str(it) } ?: "null")
        // the routing-data notice (the app's data/RoutingDataNotice): a part under the plan still missing (fetch failed –
        // offline) or the router's "no routing data" → the plain grey line instead of the router's text
        val missing = partsNeeded(res.routed.joinToString(";") { num(it.lat, 6) + "," + num(it.lon, 6) }, res.activity.name) != "[]"
        val notice = com.onpath.app.data.RoutingDataNotice.kindForRoute(p.error, missing, loading = false)
        append(",\"notice\":").append(notice?.let { str(it.name) } ?: "null")
        append(",\"noticeText\":").append(notice?.let { str(it.text) } ?: "null")
        // way types and surfaces (the app's routing/BreakdownRules): the summary line and the two bars' parts
        append(",\"breakdown\":").append(breakdownJson(p.wayTypes, p.surfaces))
        append(",\"profile\":"); profileJson(res.profile)
        append(",\"legs\":[")
        p.legs.forEachIndexed { li, leg ->
            if (li > 0) append(',')
            append("{\"straight\":").append(leg.straight).append(",\"points\":")
            pointsJson(leg.points)
            append('}')
        }
        append("]}")
    }

    /**
     * The page took the snapped points of a plan ([routed] → [snapped], the plan's "points"): every undo / redo
     * snapshot gets the same values, without a step (the app's PlanHistory.replacePoints with WaypointSnap.moves).
     */
    fun historySnap(routed: String, snapped: String) {
        val r = parsePoints(routed); val s = parsePoints(snapped)
        history.replacePoints(com.onpath.app.routing.WaypointSnap.moves(r, s))
        aliasSnapped(r, s)
    }

    /**
     * The points were moved onto the line (the page took the plan's snapped points): the run's legs under the moved
     * points too ([LegCache.alias], the app's AppViewModel.aliasSnappedLegs) – the plan as saved now opens with its legs
     * (memory and the browser's store), and the next edit routes only its new legs.
     */
    private fun aliasSnapped(routed: List<GeoPoint>, snapped: List<GeoPoint>) {
        if (routed.size != snapped.size) return
        val res = lastResultFor(routed) ?: return
        val moved = (0 until routed.size - 1).filter { snapped[it] != routed[it] || snapped[it + 1] != routed[it + 1] }
        // no trace line (the app writes one): the engine's log reaches the page as console errors, which smokes count
        for (i in moved) legCache.alias(routed[i], routed[i + 1], snapped[i], snapped[i + 1], res.activity, res.sac)
    }

    /**
     * Where a clicked point goes – the phone's rule ([WaypointInsertion.decide]): on the line → via in that leg,
     * else cheapest insertion or new destination. Returns the index in the point list.
     */
    fun insertIndex(points: String, lat: Double, lon: Double, lineSnapM: Double): Int {
        val wps = parsePoints(points)
        val route = lastPlanFor(wps)?.points
        return WaypointInsertion.decide(wps, GeoPoint(lat, lon), route, lineSnapM).index
    }

    /** `{"line": "Radweg 92 % · Asphalt 98 %", "ways": [[key, text, "#hex", m], …], "surfaces": […]}` (BreakdownRules). */
    internal fun breakdownJson(ways: List<Share>, surfaces: List<Share>): String {
        val r = com.onpath.app.routing.BreakdownRules
        fun parts(kind: com.onpath.app.routing.BreakdownRules.Kind, s: List<Share>) = r.foldSmall(s).joinToString(",", "[", "]") { sh ->
            val e = r.entry(kind, sh.key)
            "[" + str(sh.key) + "," + str(e.text) + "," + str(e.hex) + "," + num(sh.meters, 0) + "]"
        }
        return "{\"line\":" + str(r.summaryLine(ways, surfaces)) + ",\"ways\":" + parts(com.onpath.app.routing.BreakdownRules.Kind.WAY, ways) +
            ",\"surfaces\":" + parts(com.onpath.app.routing.BreakdownRules.Kind.SURFACE, surfaces) + "}"
    }

    /**
     * The elevation profile JSON of the app's [ProfileData] (points with elevation, distance along the whole line) with
     * its gradient classes ([ProfileData.steepness]): `{"dist":[…],"ele":[…],"steep":[…]}`, or `null` = not enough
     * elevation (the app's "No elevation data").
     */
    internal fun StringBuilder.profileJson(d: com.onpath.app.routing.ProfileData?) {
        if (d == null) { append("null"); return }
        append("{\"dist\":").append(d.dist.joinToString(",", "[", "]") { num(it, 1) })
        append(",\"ele\":").append(d.ele.joinToString(",", "[", "]") { num(it, 1) })
        append(",\"steep\":").append(d.steepness().joinToString(",", "[", "]") { it.ordinal.toString() }).append('}')
    }

    internal fun StringBuilder.pointsJson(pts: List<GeoPoint>) {
        append('[')
        pts.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append('[').append(num(p.lat, 6)).append(',').append(num(p.lon, 6)).append(',')
                .append(p.ele?.let { num(it, 1) } ?: "null").append(']')
        }
        append(']')
    }

    /**
     * The app's plan edits ([PlanEdits]) on the page's points: "reverse", "roundTrip", "startHere" (point menu "Start
     * here" / round trip "Start & finish here"), "delete", "move" ([index] → [to]). Returns the new point list in the
     * same text form (coordinates unchanged, so the page keeps names and pins by value).
     */
    fun edit(op: String, points: String, index: Int, to: Int): String {
        val w = parsePoints(points)
        val out = when (op) {
            // the app's AppViewModel.reverseRoute / roundTrip (owner 2026-11-06, onpath.private#40): Reverse only when it
            // changes the plan; Round trip is a switch – on a round trip it takes the way back out (A → B → A → A → B)
            "reverse" -> if (PlanEdits.reverseChanges(w)) PlanEdits.reverse(w) else w
            "roundTrip" -> if (PlanEdits.isRoundTrip(w)) PlanEdits.unRoundTrip(w) else PlanEdits.roundTrip(w)
            "startHere" -> PlanEdits.startHere(w, index)
            // the app's loop-aware edits (routing/PlanPoints): a round trip stays closed – map menu Delete and the
            // Points list ✕ ([index] = plan index; the closing point is the start's row), the Points list drag (rows)
            "delete" -> PlanPoints.deleteAt(w, index)
            "move" -> PlanPoints.move(w, index, to)
            else -> w
        }
        return out.joinToString(";") { num(it.lat, 6) + "," + num(it.lon, 6) }
    }

    /**
     * The plan sheet's Points rows ([PlanPoints.rows]: names, leg distances along the route, round trip shown once
     * with ↺) as JSON. [names] = lines "index<TAB>name". The line is the last plan's when it belongs to these points.
     */
    fun rows(points: String, names: String): String {
        val w = parsePoints(points)
        val n = parseNames(names)
        val line = lastPlanFor(w)?.points
        return PlanPoints.rows(w, line) { n[it] }.joinToString(",", "[", "]") { r ->
            "{\"i\":" + r.planIndex + ",\"role\":" + str(r.role.name) + ",\"name\":" + str(r.name) +
                ",\"legM\":" + (r.legM?.let { num(it, 0) } ?: "null") + ",\"approx\":" + r.approx + ",\"loop\":" + r.loop +
                // the row texts (the app's PointsSection, shared since 1d35cc5b – web review M15): the page only renders them
                ",\"roleText\":" + (PlanPoints.roleText(r)?.let { str(it) } ?: "null") +
                ",\"legText\":" + (PlanPoints.legText(r)?.let { str(it) } ?: "null") + "}"
        } .let {
            // the plan row's switches as the app's PlanUi (#40): canRoundTrip = on, or ≥ 2 points not ending in a loop yet;
            // canReverse = backwards is a different plan (not A → B → A)
            "{\"roundTrip\":" + PlanEdits.isRoundTrip(w) + ",\"canRoundTrip\":" + (PlanEdits.isRoundTrip(w) || PlanEdits.canRoundTrip(w)) +
                ",\"canReverse\":" + PlanEdits.reverseChanges(w) + ",\"rows\":" + it + "}"
        }
    }

    /**
     * The place card's button for a search result (the app's [com.onpath.app.search.AddPreview]: what adding does –
     * "Set as destination", "Add as stop 2", … – with the same WaypointInsertion as a map click), its short reason, the
     * index it would take, whether it already sets the start / the destination (the app's `placeCardUi`) and where it
     * would join the route ([WaypointInsertion.joinPoint], null without a route). JSON.
     */
    fun placePreview(points: String, lat: Double, lon: Double): String {
        val w = parsePoints(points)
        val p = GeoPoint(lat, lon)
        val idx = w.indexOfFirst { Geo.distance(it, p) < 1.0 }
        if (idx >= 0) return "{\"existing\":" + idx + ",\"subtitle\":" + str(com.onpath.app.search.AddPreview.existingRole(idx, w.size)) + "}"
        val plan = lastPlanFor(w)
        val speed = plan?.takeIf { it.durationS > 0 }?.let { it.distanceM / it.durationS }
        val pr = com.onpath.app.search.AddPreview.of(w, p, plan?.points, speed)
        val k = pr.decision.kind
        // where it would join the route – the candidate marker's thin dashed preview line (the app's MapController.setSearchMark)
        val join = WaypointInsertion.joinPoint(w, p, plan?.points, pr.decision)
        return "{\"index\":" + pr.decision.index + ",\"label\":" + str(pr.label) + ",\"subtitle\":" + str(pr.subtitle) +
            ",\"addIsStart\":" + (k == WaypointInsertion.Kind.START) +
            ",\"addIsDestination\":" + (k == WaypointInsertion.Kind.DESTINATION && w.size <= 1) +
            ",\"join\":" + (join?.let { "[" + num(it.lat, 6) + "," + num(it.lon, 6) + "]" } ?: "null") + "}"
    }

    internal fun parseNames(names: String): Map<Int, String> = names.split('\n').mapNotNull { l ->
        val i = l.substringBefore('\t').toIntOrNull() ?: return@mapNotNull null
        i to l.substringAfter('\t')
    }.toMap()

    internal fun parseIndices(s: String): Set<Int> = s.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

    /**
     * The plan code ([PlanCode], the app's own) for the page's plan. [names] = lines "index<TAB>name" (names never
     * hold control characters, PlanCode drops them).
     */
    fun planCode(points: String, activity: String, sac: Int, pinned: String, names: String, title: String): String =
        PlanCode.encode(SharedPlan(
            parsePoints(points), Activity.valueOf(activity), sac, parseIndices(pinned),
            parseNames(names),
            title.ifBlank { null },
        ))

    /** The plan in a link or code as JSON (points, activity, sac, pinned, names, title), or "null". */
    fun planParse(text: String): String {
        val p = PlanCode.parse(text) ?: return "null"
        return buildString {
            append("{\"points\":[")
            p.points.forEachIndexed { i, q -> if (i > 0) append(','); append('[').append(num(q.lat, 5)).append(',').append(num(q.lon, 5)).append(']') }
            append("],\"activity\":").append(str(p.activity.name))
            append(",\"sac\":").append(p.sac)
            append(",\"pinned\":").append(p.pinned.sorted().joinToString(",", "[", "]"))
            append(",\"names\":").append(p.names.toSortedMap().entries.joinToString(",", "{", "}") { str(it.key.toString()) + ":" + str(it.value) })
            append(",\"title\":").append(p.title?.let { str(it) } ?: "null")
            // multi-day tours (WebDays.kt, 2026-11-17): days, limits, night marks, description – null for a normal plan
            append(",\"tour\":").append(WebDays.tourJson(p))
            append('}')
        }
    }

    // ---- undo / redo: the app's PlanDocument + PlanEditState (WebPlanChain) around one PlanHistory ----
    private val history = PlanHistory()
    /** The app's plan-editing chain on this engine's history (rebuilt after an import: its Undo / Redo flags are read at construction). */
    private var chain = WebPlanChain(history = history)

    /** One undoable change of the points [before] → [after] (PlanEditState.change). */
    fun historyRecord(before: String, after: String) = chain.record(parsePoints(before), parsePoints(after))

    /** "undo" / "redo" on the plan [points] (PlanEditState.undoStep / redoStep): the point list to show, or "null". */
    fun historyStep(op: String, points: String): String {
        val out = chain.step(op != "redo", parsePoints(points)) ?: return "null"
        return out.joinToString(";") { num(it.lat, 6) + "," + num(it.lon, 6) }
    }

    fun historyState(): String = "{\"canUndo\":" + history.canUndo + ",\"canRedo\":" + history.canRedo + "}"

    /**
     * Cancel of the long-route question ([why] "LONG") / of the wait ("STOPPED") – PlanEditState.cancelRun – for the run started
     * by [cause] on the plan [points]; [activity] / [sac] = the activity before an activity change. `{"what":"UNDO","points":"…"}`
     * ("UNDO" / "REDO" with the points to show, "RESTORE_ACTIVITY", "KEEP_POINTS").
     */
    fun planCancel(cause: String, points: String, activity: String, sac: Int, why: String = "STOPPED"): String {
        val (what, pts) = chain.cancel(cause, parsePoints(points), activityOrNull(activity), sac,
            com.onpath.app.domain.NotRouted.entries.firstOrNull { it.name == why } ?: com.onpath.app.domain.NotRouted.STOPPED)
        return "{\"what\":" + str(what) + ",\"points\":" +
            (pts?.let { p -> str(p.joinToString(";") { num(it.lat, 6) + "," + num(it.lon, 6) }) } ?: "null") + "}"
    }

    /**
     * The undo / redo snapshots as text, for a fresh engine worker (web-planner.md §26: the page stops a long plan that
     * blocks the one worker by starting a new one): "undo#redo", steps split by "|", an empty point list as "~".
     */
    fun historyExport(): String {
        val (u, r) = history.snapshot()
        fun list(l: List<List<GeoPoint>>) = l.joinToString("|") { s -> if (s.isEmpty()) "~" else s.joinToString(";") { num(it.lat, 6) + "," + num(it.lon, 6) } }
        // + what the route run keeps (WebPlanChain.runExport: "Plan anyway", why not routed, an open "Weiter warten?")
        return list(u) + "#" + list(r) + "#" + chain.runExport() + "#" + epoch
    }

    /**
     * Which chain a run's number belongs to: every [historyImport] starts a fresh chain whose RouteRun counts from 0 again,
     * so a number alone could name a run of the chain before (review 2026-11-20: a late result ending the newer run). The
     * page gets `epoch * EPOCH + gen`; [epoch] grows across fresh chains and fresh workers (it rides in the export).
     */
    private var epoch = 0
    private const val EPOCH = 1_000_000

    /** [historyExport]'s text back into this engine's history (replaces it) and into a fresh chain (its route run too). */
    fun historyImport(text: String) {
        val parts = text.split("#")
        fun list(s: String?) = if (s.isNullOrEmpty()) emptyList() else s.split("|").map { if (it == "~") emptyList() else parsePoints(it) }
        history.restore(list(parts.getOrNull(0)), list(parts.getOrNull(1)))
        chain = WebPlanChain(history = history)
        parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { chain.runImport(it) }
        epoch = (maxOf(epoch, parts.getOrNull(3)?.toIntOrNull() ?: 0) + 1) % 2000
    }

    // ---- the route run's head (the app's PlanRecalcState.begin + RouteRun, WebPlanChain): the page asks before each run

    /**
     * The head of a run of the plan [points] for [activity] / [sac]: `{"what":"ROUTE","gen":3,"rounds":1}`, the long-plan
     * question first `{"what":"ASK","seconds":…,"title":…,"text":…,"plan":…,"cancel":…,"notRouted":…,"again":…}` (the app's
     * LongRouteDialog texts), or `{"what":"NONE"}`. [askLong] false: a multi-day tour is never asked (owner 2026-11-18).
     */
    fun runHead(points: String, activity: String, sac: Int, askLong: Boolean): String {
        val act = Activity.valueOf(activity)
        return when (val h = chain.head(parsePoints(points), act, sac, askLong)) {
            is WebPlanChain.Head.Route -> "{\"what\":\"ROUTE\",\"gen\":" + (epoch * EPOCH + h.gen) + ",\"rounds\":" + h.rounds + "}"
            is WebPlanChain.Head.Ask -> "{\"what\":\"ASK\"" + WebPlanRules.longAsk(h.estimateS, act) + "}"
            WebPlanChain.Head.None -> "{\"what\":\"NONE\"}"
        }
    }

    /** The answer to the long-plan question for the plan [points] (RouteRun.LongAnswer): yes = not asked again for them. */
    fun runLongAnswer(yes: Boolean, points: String) = chain.longAnswer(yes, parsePoints(points))

    /** The run [gen] came back: [outcome] "ok", "failed", "slow" (the time limit after [rounds] periods – the question is open). */
    fun runEnd(gen: Int, outcome: String, rounds: Int) {
        if (gen / EPOCH != epoch) { Problems.trace("plan", "the end of a run of an earlier chain – ignored ($outcome)"); return }
        chain.end(gen % EPOCH, outcome, rounds)
    }

    /** The plan [points] was said yes to earlier on this page (RouteRun.RoutedBefore): not asked again. */
    fun runRoutedBefore(points: String) = chain.routedBefore(parsePoints(points))

    /** The answer to "Weiter warten?" (PlanRunSlice.answerSlowPlan): the next [runHead] has one period more. */
    fun runSlowAnswer(wait: Boolean) = chain.slowAnswer(wait)

    /** "Plan anyway" / "Calculate" of the not-routed line (PlanRunSlice.planAnyway) on the plan [points]. */
    fun runPlanAnyway(points: String) = chain.planAnyway(parsePoints(points))

    // ---- map gestures: the app's MapGestures (hold rules, point menu) ----

    /**
     * What a press on the map does ([MapGestures.decide], Plan mode, the page treats a click as the app's hold):
     * "PointMenu:<i>", "PoiMenu", "AddPoint", "FirstPointMenu", "CloseMenu" or "None".
     */
    fun gesture(menuOpen: Boolean, marker: Int, poi: Boolean, planEmpty: Boolean): String =
        when (val a = MapGestures.decide(true, MapGestures.Press.HOLD, menuOpen, marker, poi, planEmpty)) {
            is MapGestures.Action.PointMenu -> "PointMenu:" + a.index
            else -> a.toString()
        }

    /** The point menu's entries ([MapGestures.pointMenu]) as JSON names: MAKE_START, LOOP_START, GOOGLE, DELETE. */
    /**
     * A held place's add entries – the app's PlaceAddMenu (owner 2026-11-07 / 2026-11-15 / 2026-11-17): `{"main":[…],"more":[],
     * "stop":i,"why":…}`, items START_HERE / GO_HERE / SET_DESTINATION / ADD_STOP / NEW_DESTINATION; [stop] = where
     * „Einfügen“ puts it (WaypointInsertion stopOnly: the best place on the route, never behind the goal); [keepOld] =
     * whether „Neues Ziel“ keeps the old goal as the last stop ([PlaceAddMenu.keepsOldGoal], owner 2026-11-14 „beim Planen
     * anhängen, unterwegs ersetzen“). The planner has no tour: the route ahead is the whole plan, the new goal appends.
     * While planning (2+ points, no round trip) the rows are decided as a hold on the empty map – the app's openPoiMenu /
     * PlaceMenuMeasure (owner 2026-11-17: „wenn ich da nicht einfügen muss, dann braucht das auch im Menü nicht angezeigt zu
     * werden“): [PlaceAddMenu.planningPlaceMenu] with the place's distance to the drawn line ([lineSnapM] = 24 dp in metres)
     * and, off the line, the detours on the real paths for [activity] / [sac] ([measurePaths]; [activity] "" = straight
     * lines only – a caller that wants [stop] alone). One row in a clear case, both in doubt.
     */
    fun placeMenu(points: String, lat: Double, lon: Double, roundTrip: Boolean, lineSnapM: Double = 30.0,
                  activity: String = "", sac: Int = 2): String {
        val w = parsePoints(points)
        val p = GeoPoint(lat, lon)
        // the app's PlaceMenusState.openPoiMenu → placeMenu: the rows decided (measured on the paths while planning)
        val q = WebPlaceAdd(w, lastPlanFor(w), activityOrNull(activity), sac)
        val poi = WebPlaceAdd.poi(lat, lon, "", "")
        val why = q.traced { q.menus.openPoiMenu(poi, lineSnapM) }
        val m = q.menus.placeMenu(poi)
        // where „Einfügen“ puts it: the app's addPoiAsStop (its default 30 m on the line)
        val stop = if (w.size < 2) w.size else q.add.insertionFor(p, 30.0, stopOnly = true).index
        fun list(items: List<com.onpath.app.domain.PlaceAddMenu.Item>) = items.joinToString(",", "[", "]") { str(it.name) }
        val keepOld = com.onpath.app.domain.PlaceAddMenu.keepsOldGoal(touring = false)
        // "more" stays empty (owner 2026-11-15: every entry in "main") – kept for the planner's reader
        return "{\"main\":" + list(m.main) + ",\"more\":[],\"stop\":" + stop + ",\"keepOld\":" + keepOld + ",\"why\":" + str(why) + "}"
    }

    /**
     * An add from a menu or the place card, decided by the app's PlaceAddState on the plan [points] shown now: [op] =
     * "holdStop" / "holdGoal" (the hold menu's „Einfügen“ / „Neues Ziel“ – holdAsStop / holdAsNewDestination), "poiStop" /
     * "poiGoal" (a held place's rows – addPoiAsStop / poiAsNewDestination), "SMART" / "START" / "DESTINATION" (the place
     * card – addPlace). `{"points":"…"}` = the plan to commit (one Undo step), `{"pointMenu":i}` = the place is point i
     * already (its menu opens instead), `{"points":null}` = nothing changed.
     */
    fun placeEdit(op: String, points: String, lat: Double, lon: Double, lineSnapM: Double, title: String, kind: String): String {
        val w = parsePoints(points)
        val p = GeoPoint(lat, lon)
        val q = WebPlaceAdd(w, lastPlanFor(w), null, 2)
        when (op) {
            "holdStop", "holdGoal" -> {
                q.add.holdAddMenu = com.onpath.app.ui.plan.PlaceAddState.HoldAdd(p, com.onpath.app.domain.PlaceAddMenu.Menu(emptyList()), lineSnapM)
                if (op == "holdStop") q.add.holdAsStop() else q.add.holdAsNewDestination()
            }
            "poiStop" -> q.add.addPoiAsStop(WebPlaceAdd.poi(lat, lon, title, kind))
            "poiGoal" -> q.add.poiAsNewDestination(WebPlaceAdd.poi(lat, lon, title, kind))
            else -> q.add.addPlace(WebPlaceAdd.place(lat, lon, title, kind), WebPlaceAdd.role(op))
        }
        q.pointMenu?.let { return "{\"pointMenu\":$it}" }
        val after = q.points
        return "{\"points\":" + (if (after == w) "null" else str(after.joinToString(";") { num(it.lat, 6) + "," + num(it.lon, 6) })) + "}"
    }

    /** How far [p] is from the drawn line in dp ([lineSnapM] = metres in 24 dp): the last route for [w], else the straight legs. */
    private fun lineDp(w: List<GeoPoint>, p: GeoPoint, route: List<GeoPoint>?, lineSnapM: Double): Double {
        val mPerDp = lineSnapM / com.onpath.app.domain.PlaceAddMenu.LINE_SNAP_DP
        return Geo.distanceToLine(p, route?.takeIf { it.size >= 2 } ?: w) / mPerDp
    }

    /** The whole measure of a hold on the paths (the app's HoldPaths.TIMEOUT_MS, owner ~1.5 s): after it the straight lines decide. */
    internal const val HOLD_PATHS_MS = 1_500L

    /**
     * A leg the hold would measure ([PlaceAddMenu.pathCandidates]) longer than this, straight → no path measure, the
     * straight lines decide at once (owner 2026-11-20, web only for now: on legs of 200–300 km the measure always ran into
     * [HOLD_PATHS_MS] and then took the straight lines anyway – 1.5 s lost on every click; measured in
     * docs/team/items/2026-11-20-add-point-speed.md: routing 25 km takes 0.05–0.1 s, 50 km already 0.15–0.3 s per leg).
     * Straight-line, not the routed length: it is there for every leg before anything is routed, and a routed leg is never
     * shorter than its straight line, so no leg the measure could finish in time is skipped by mistake.
     */
    internal const val LONG_LEG_M = 30_000.0

    /** How many times [measurePaths] started (for the tests: a long leg starts none). */
    internal var pathMeasures = 0
        private set

    /** [measurePaths], unless a candidate leg is over [LONG_LEG_M] – then the [straight] lines at once, nothing routed. */
    internal fun measureOrStraight(w: List<GeoPoint>, p: GeoPoint, straight: com.onpath.app.domain.PlaceAddMenu.Detours,
                                   act: Activity, sac: Int): Measured {
        val longest = com.onpath.app.domain.PlaceAddMenu.pathCandidates(w, p).maxOfOrNull { Geo.distance(w[it], w[it + 1]) } ?: 0.0
        return if (longest > LONG_LEG_M) Measured(straight, false, 0, "leg over ${(LONG_LEG_M / 1000).toInt()} km (${(longest / 1000).toInt()} km), not measured")
        else measurePaths(w, p, straight, act, sac)
    }

    /** What [measurePaths] found: the detours on the paths, or the straight ones after [failure] (the app's HoldPaths.Outcome). */
    internal class Measured(val detours: com.onpath.app.domain.PlaceAddMenu.Detours, val onPaths: Boolean, val ms: Long, val failure: String? = null) {
        /** For the trace: "paths: insert +X m, append Y m (Z ms)" or "paths failed: … – straight lines" (HoldPaths' words). */
        fun describe(): String =
            if (onPaths) "paths: insert +${detours.insertM.toInt()} m" +
                (detours.earlyInsertM?.let { " (earlier legs +${it.toInt()} m)" } ?: "") +
                ", append ${detours.appendM.toInt()} m ($ms ms)"
            else if (ms == 0L && failure?.startsWith("leg over") == true) "$failure – straight lines"
            else "paths failed: $failure ($ms ms) – straight lines"
    }

    /**
     * The detours of [p] beside the plan [w] (2+) on the real paths (owner 2026-11-17 „Always real paths“ – the app's
     * HoldPaths + AppViewModel.routedLength): [PlaceAddMenu.pathDetours] with each length from the [legCache], else one leg
     * by the [Planner] (as a plan run routes it – then in the cache for the plan run after the decision); a straight leg
     * (no routing data, no way near the spot, an island) is a failure. At most [limitMs] in all: every router request gets
     * only the time left (the worker has one thread – no [RouteCancel] can come from outside, so the time left is the cap),
     * then the [straight] lines decide. Never throws.
     */
    internal fun measurePaths(w: List<GeoPoint>, p: GeoPoint, straight: com.onpath.app.domain.PlaceAddMenu.Detours, act: Activity,
                              sac: Int, limitMs: Long = HOLD_PATHS_MS): Measured {
        pathMeasures++
        val t0 = System.currentTimeMillis()
        val deadline = t0 + limitMs
        val cancel = com.onpath.app.routing.RouteCancel()
        return try {
            val eng = engine()
            val capped = object : com.onpath.app.routing.RoutingEngine {
                override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int) =
                    route(waypoints, activity, sacLimit, com.onpath.app.routing.RouteLimits(limitMs, cancel))
                override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int,
                                   limits: com.onpath.app.routing.RouteLimits): com.onpath.app.routing.RouteResult {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) {
                        cancel.cancel()
                        throw RoutingException("measure over its time limit", timedOut = true)
                    }
                    return eng.route(waypoints, activity, sacLimit,
                        com.onpath.app.routing.RouteLimits(minOf(limits.timeoutMs, left), cancel, limits.rounds))
                }
            }
            val d = com.onpath.app.domain.PlaceAddMenu.pathDetours(w, p, straight) { a, b ->
                if (System.currentTimeMillis() >= deadline) {
                    cancel.cancel()
                    throw RoutingException("measure over its time limit", timedOut = true)
                }
                val m = legCache.lookup(a, b, act, sac)?.leg?.distanceM ?: run {
                    val r = Planner(capped, cancel, legs = legCache).plan(listOf(a, b), act, sac)
                    if (r.legs.any { it.straight }) throw RoutingException(r.error ?: "no route for the leg")
                    r.distanceM
                }
                if (!m.isFinite() || m < 0) throw RoutingException("no length ($m)")
                m
            }
            Measured(d, true, System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - t0
            // expected: no routing data / no way near the spot / an island / the time limit → info; anything else is a bug → warn
            val why = if (e !is RoutingException) "engine error"
                else if (e.timedOut || cancel.isCancelled || ms >= limitMs) "timeout after $limitMs ms" else e.kind.name.lowercase()
            if (e is RoutingException) Problems.info("plan", "Hold beside the plan: paths not measured ($why) – straight lines", e)
            else Problems.warn("plan", "Hold beside the plan: measuring the paths failed – straight lines", e)
            Measured(straight, false, ms, why)
        }
    }

    /**
     * A click (the app's hold) on the empty map in Plan – the app's AppViewModel.holdOnMap / measureHold (F-PLAN-15, owner
     * 2026-11-15 / 2026-11-17). `{"action":"ADD","index":i}` = the point goes in at [i] at once;
     * `{"action":"ASK","main":[…],"stop":i}` = the small menu „Einfügen“ / „Neues Ziel“ ([main] in the app's order, the
     * cheaper first; [stop] = where „Einfügen“ puts it, never behind the goal). Fewer than 2 points: the smart insertion; a
     * round trip: the zone decides ([PlaceAddMenu.asksOnHold]); planning: [PlaceAddMenu.planningHold] – on the line
     * ([lineSnapM] = 24 dp on screen in metres) the smart insertion; else appending and inserting on the real paths for
     * [activity] / [sac] ([measurePaths], at most ~1.5 s, straight lines on any failure; owner „Always real paths“): the
     * shorter at once (appending → the new goal, inserting → a stop), within 20 % of each other → ask. [why] goes to the
     * diagnostics.
     */
    fun holdOnMap(points: String, lat: Double, lon: Double, roundTrip: Boolean, lineSnapM: Double, activity: String = "",
                  sac: Int = 2): String {
        val w = parsePoints(points)
        val p = GeoPoint(lat, lon)
        val q = WebPlaceAdd(w, lastPlanFor(w), activityOrNull(activity), sac)
        val why = q.traced { q.add.holdOnMap(p, lineSnapM) } // the app's PlaceAddState: a point at once or the small menu
        q.add.holdAddMenu?.let { a ->
            val stop = q.add.insertionFor(p, lineSnapM, stopOnly = true).index
            return "{\"action\":\"ASK\",\"main\":" + a.menu.main.joinToString(",", "[", "]") { str(it.name) } +
                ",\"stop\":" + stop + ",\"why\":" + str(why) + "}"
        }
        // the add the shared code made: [p] went in at one place (a new goal is appended while planning)
        val after = q.points
        val i = after.indices.firstOrNull { after[it] == p && w.getOrNull(it) != p } ?: w.size
        return "{\"action\":\"ADD\",\"index\":" + i + ",\"why\":" + str(why) + "}"
    }

    /**
     * Where the hold menu goes beside the held spot (the app's MapGestures.placeBeside, owner 2026-11-15): "x,y" of its
     * top-left corner – never over the spot, clear of the right control column when it fits.
     */
    fun placeBeside(ax: Double, ay: Double, w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int, gap: Int, column: Int): String =
        MapGestures.placeBeside(ax.toFloat(), ay.toFloat(), w, h, left, top, right, bottom, gap, column).let { (x, y) -> "$x,$y" }

    /**
     * The locate button's distance while my position is off the visible map (the app's LocateDistance, owner 2026-11-07):
     * "790 km", or "" (in view / no position). The planner is always "Plan".
     */
    fun locateLabel(located: Boolean, lat: Double, lon: Double, south: Double, west: Double, north: Double, east: Double): String =
        com.onpath.app.domain.LocateDistance.label(plan = true, located = located, me = if (located) GeoPoint(lat, lon) else null,
            view = com.onpath.app.domain.LocateDistance.View(south, west, north, east)) ?: ""

    fun pointMenu(index: Int, count: Int, isPlace: Boolean, roundTrip: Boolean, points: String = ""): String {
        // the loop's first point ([PlanEdits.loopStart]): with stops on the way before a round trip, ① of the loop reads
        // "Start here" (drops the stops) and the later loop points "Start & finish here" – the app's MapOverlays call
        val loopStart = if (points.isBlank()) 0 else PlanEdits.loopStart(parsePoints(points)) ?: 0
        return MapGestures.pointMenu(index, count, isPlace, roundTrip, loopStart).joinToString(",", "[", "]") { str(it.name) }
    }

    /** Why "Go here" is disabled ([MapGestures.fromMeBlocked]), or "" when the position can be used. */
    fun fromMeBlocked(status: String, hasFix: Boolean): String = MapGestures.fromMeBlocked(GpsStatus.valueOf(status), hasFix) ?: ""

    /** "Open in Google Maps" – the app's link ([PoiLinks.googleMaps], web form): a link-out, nothing is fetched. */
    fun googleMaps(name: String, lat: Double, lon: Double, label: String): String =
        PoiLinks.googleMaps(name.ifBlank { null }, lat, lon, label.ifBlank { null }).webUrl

    /**
     * The POI menu's head and link for a POI on the map (the app's ui/MapOverlays.PoiMenu: title, kind label · elevation
     * in the kind's colour; [PoiLinks.googleMaps] for the POI). [ele] = "" when unknown. JSON or "null".
     */
    fun poiMenu(kind: String, name: String, ele: String, lat: Double, lon: Double): String {
        val poi = com.onpath.app.poi.Poi.fromProperties(mapOf("kind" to kind, "name" to name, "ele" to ele), lat, lon) ?: return "null"
        val place = com.onpath.app.search.Place.fromPoi(poi)
        val color = com.onpath.app.search.SearchKinds.argb(place.kind) and 0xFFFFFFL
        return "{\"title\":" + str(place.title()) + ",\"sub\":" + str(place.kindInfo.label + (place.ele?.let { " · $it m" } ?: "")) +
            ",\"color\":" + str("#" + color.toString(16).padStart(6, '0')) + ",\"google\":" + str(PoiLinks.googleMaps(poi).webUrl) + ",\"code\":" + str(WebKept.encode(place)) + "}"
    }

    /**
     * "Export GPX" of the page's plan, the app's plan export ([com.onpath.app.gpx.PlanGpx]): the routed line, the plan
     * points as `<wpt>` with their names, the sport, "<name> - plan.gpx". [points] / [names] = the page's plan (the line
     * must be this plan's). JSON {file, gpx} or "null".
     */
    fun gpx(name: String, activity: String, sac: Int, points: String, names: String, pinned: String = ""): String {
        val w = parsePoints(points)
        val line = lastPlanFor(w)?.points?.takeIf { it.size >= 2 } ?: return "null"
        val n = name.ifBlank { Txt.s(Res.string.msg_name_route) } // the page sends its own name; this only if none
        val nm = parseNames(names)
        val f = com.onpath.app.gpx.PlanGpx.export(n, listOf(line.map { com.onpath.app.gpx.TrackPoint(it.lat, it.lon, it.ele) }),
            // pinned points (POI / place, passed exactly) as <wpt><type>pinned</type> – a re-import pins them again (23f64bc3)
            com.onpath.app.gpx.PlanGpx.stops(w, parseIndices(pinned)) { nm[it]?.takeIf { s -> s.isNotBlank() } }, Activity.valueOf(activity), sac)
        return "{\"file\":" + str(f.fileName) + ",\"gpx\":" + str(f.text) + "}"
    }

    /** The activity picker's rows from the app's [Activity] (label, the line under it, chip name). JSON. */
    fun activities(): String = Activity.entries.joinToString(",", "[", "]") {
        "{\"key\":" + str(it.name) + ",\"label\":" + str(it.label) + ",\"line\":" + str(it.line) + ",\"chip\":" + str(it.chip) + "}"
    }

    fun problems(): String = Problems.drain().joinToString(",", "[", "]") { str(it) }

    private fun shares(s: List<Share>) = s.joinToString(",", "[", "]") { "[" + str(it.key) + "," + num(it.meters, 1) + "]" }

    /** Fixed-decimals number without String.format (same text on the JVM and in the browser). */
    internal fun num(v: Double, decimals: Int = 2): String {
        if (v.isNaN() || v.isInfinite()) return "null"
        var f = 1L
        repeat(decimals) { f *= 10 }
        val n = Math.round(v * f)
        val sign = if (n < 0) "-" else ""
        val a = Math.abs(n)
        val whole = a / f
        val frac = (a % f).toString().padStart(decimals, '0')
        return if (decimals == 0) "$sign$whole" else "$sign$whole.$frac"
    }

    internal fun str(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\t' -> append("\\t")
            else -> if (c < ' ') append(' ') else append(c)
        }
        append('"')
    }
}

/** Context stand-in: BRouterEngine only needs filesDir and assets (stub classes from tools/routing/jvm-test/stubs). */
class WebContext(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

// ---- Exports for the browser (TeaVM) ----

/**
 * A plan's progress text to the page, while [Engine.plan] runs: the worker sets `onpathPlanProgress` for the request it
 * answers (worker.ts) – the engine runs on the worker's thread, so this is the only way out before the answer.
 */
@JSBody(params = ["text"], script = "if (typeof globalThis.onpathPlanProgress === 'function') globalThis.onpathPlanProgress(text);")
external fun planProgress(text: String)

@JSExport fun init(rootDir: String) = Engine.init(rootDir)

/** The text language of everything the engine writes ("en", "de"; web-planner.md §18). Returns the one used. */
@JSExport fun setLanguage(lang: String): String = com.onpath.strings.Txt.setLanguage(lang)

@JSExport fun putFile(relPath: String, data: Int8Array) = Engine.putFile(relPath, data.copyToJavaArray())

@JSExport fun partsNeeded(points: String, activity: String): String = Engine.partsNeeded(points, activity)

@JSExport fun putLayout(tile: String, index: Int8Array, tail: Int8Array) =
    Engine.putLayout(tile, index.copyToJavaArray(), tail.copyToJavaArray())

@JSExport fun putPart(tile: String, index: Int, data: Int8Array): Boolean = Engine.putPart(tile, index, data.copyToJavaArray())

@JSExport fun writeParts() = Engine.writeParts()

@JSExport fun route(points: String, activity: String, sac: Int): String = Engine.route(points, activity, sac)

@JSExport fun plan(points: String, activity: String, sac: Int, pinned: String, names: String, rounds: Int): String {
    // only the Wasm build reaches this export – the JVM run calls Engine.plan, where planProgress does not exist
    Engine.progressSink = ::planProgress
    return try { Engine.plan(points, activity, sac, pinned, names, rounds) } finally { Engine.progressSink = null }
}

@JSExport fun historySnap(routed: String, snapped: String) = Engine.historySnap(routed, snapped)

// the browser's leg store (WebLegStore, worker.ts legs*): which legs to read, hand them over, take the new ones
@JSExport fun legsWanted(points: String, activity: String, sac: Int): String = Engine.legsWanted(points, activity, sac)
@JSExport fun legGive(name: String, text: String) = Engine.legStore.give(name, text)
@JSExport fun legsTaken(): String = Engine.legStore.taken().joinToString(",", "[", "]") { Engine.str(it) }
@JSExport fun legText(name: String): String = Engine.legStore.text(name)
@JSExport fun legsLast(): String = Engine.lastLegs

/** The routing fingerprint + loaded rd5 dates, the app's Diagnostics line (the page's window.__diag). */
@JSExport fun routingInfo(): String = Engine.routingInfo()

@JSExport fun planCode(points: String, activity: String, sac: Int, pinned: String, names: String, title: String): String =
    Engine.planCode(points, activity, sac, pinned, names, title)

@JSExport fun planParse(text: String): String = Engine.planParse(text)

@JSExport fun edit(op: String, points: String, index: Int, to: Int): String = Engine.edit(op, points, index, to)

@JSExport fun rows(points: String, names: String): String = Engine.rows(points, names)

@JSExport fun placePreview(points: String, lat: Double, lon: Double): String = Engine.placePreview(points, lat, lon)
@JSExport fun placeMenu(points: String, lat: Double, lon: Double, roundTrip: Boolean, lineSnapM: Double, activity: String, sac: Int): String =
    Engine.placeMenu(points, lat, lon, roundTrip, lineSnapM, activity, sac)
@JSExport fun holdOnMap(points: String, lat: Double, lon: Double, roundTrip: Boolean, lineSnapM: Double, activity: String, sac: Int): String =
    Engine.holdOnMap(points, lat, lon, roundTrip, lineSnapM, activity, sac)
@JSExport fun placeEdit(op: String, points: String, lat: Double, lon: Double, lineSnapM: Double, title: String, kind: String): String =
    Engine.placeEdit(op, points, lat, lon, lineSnapM, title, kind)
@JSExport fun placeBeside(ax: Double, ay: Double, w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int, gap: Int, column: Int): String =
    Engine.placeBeside(ax, ay, w, h, left, top, right, bottom, gap, column)
@JSExport fun locateLabel(located: Boolean, lat: Double, lon: Double, south: Double, west: Double, north: Double, east: Double): String = Engine.locateLabel(located, lat, lon, south, west, north, east)

@JSExport fun searchAddArea(key: String, dir: String): Int = WebSearch.addArea(key, dir)

@JSExport fun searchHasArea(key: String): Boolean = WebSearch.hasArea(key)

@JSExport fun search(text: String, lat: Double, lon: Double, route: String, category: String): String =
    WebSearch.search(text, lat, lon, route, category)

@JSExport fun searchOnline(text: String, photonJson: String, category: String): String = WebSearch.online(text, photonJson, category)

/** The six search chips (the app's SearchKinds.Category): [[name, label], …]. */
@JSExport fun searchCategories(): String = WebSearch.categories()

@JSExport fun searchReset() = WebSearch.reset()

// kept places (WebKept.kt – the app's search/SavedPlaces, owner 2026-11-15 „Orte merken“) and „Zuletzt gesucht“
@JSExport fun keptState(home: String, favourites: String, code: String): String = WebKept.state(home, favourites, code)
@JSExport fun keptApply(home: String, favourites: String, op: String, code: String): String = WebKept.apply(home, favourites, op, code)
@JSExport fun keptStart(home: String, favourites: String, recent: String, all: Boolean, lat: Double, lon: Double): String =
    WebKept.start(home, favourites, recent, all, lat, lon)
@JSExport fun recentAdd(recent: String, code: String): String = WebKept.addRecent(recent, code)

@JSExport fun insertIndex(points: String, lat: Double, lon: Double, lineSnapM: Double): Int =
    Engine.insertIndex(points, lat, lon, lineSnapM)

@JSExport fun historyRecord(before: String, after: String) = Engine.historyRecord(before, after)

@JSExport fun historyStep(op: String, points: String): String = Engine.historyStep(op, points)

@JSExport fun historyState(): String = Engine.historyState()

@JSExport fun historyExport(): String = Engine.historyExport()

@JSExport fun historyImport(text: String) = Engine.historyImport(text)

@JSExport fun gesture(menuOpen: Boolean, marker: Int, poi: Boolean, planEmpty: Boolean): String =
    Engine.gesture(menuOpen, marker, poi, planEmpty)

@JSExport fun pointMenu(index: Int, count: Int, isPlace: Boolean, roundTrip: Boolean, points: String): String =
    Engine.pointMenu(index, count, isPlace, roundTrip, points)

@JSExport fun fromMeBlocked(status: String, hasFix: Boolean): String = Engine.fromMeBlocked(status, hasFix)

@JSExport fun googleMaps(name: String, lat: Double, lon: Double, label: String): String = Engine.googleMaps(name, lat, lon, label)

@JSExport fun poiMenu(kind: String, name: String, ele: String, lat: Double, lon: Double): String =
    Engine.poiMenu(kind, name, ele, lat, lon)

@JSExport fun gpx(name: String, activity: String, sac: Int, points: String, names: String, pinned: String): String =
    Engine.gpx(name, activity, sac, points, names, pinned)

@JSExport fun activities(): String = Engine.activities()

@JSExport fun problems(): String = Engine.problems()

// plan rules before routing (WebPlanRules.kt) and GPX import (WebImport.kt)
@JSExport fun longRoute(points: String, activity: String, multiDay: Boolean): String = WebPlanRules.longRoute(points, activity, multiDay)
@JSExport fun planCancel(cause: String, points: String, activity: String, sac: Int, why: String): String = Engine.planCancel(cause, points, activity, sac, why)
@JSExport fun runHead(points: String, activity: String, sac: Int, askLong: Boolean): String = Engine.runHead(points, activity, sac, askLong)
@JSExport fun runLongAnswer(yes: Boolean, points: String) = Engine.runLongAnswer(yes, points)
@JSExport fun runEnd(gen: Int, outcome: String, rounds: Int) = Engine.runEnd(gen, outcome, rounds)
@JSExport fun runSlowAnswer(wait: Boolean) = Engine.runSlowAnswer(wait)
@JSExport fun runPlanAnyway(points: String) = Engine.runPlanAnyway(points)
@JSExport fun runRoutedBefore(points: String) = Engine.runRoutedBefore(points)
/** Builds the app's plan-editing chain once (WebPlanChain.probe – TeaVM check of PlanEditState / PlaceAddState / HoldPaths). */
@JSExport fun planChainProbe(): String = WebPlanChain.probe()
@JSExport fun gpxRead(data: Int8Array, fileName: String, activity: String, sac: Int): String =
    WebImport.read(data.copyToJavaArray(), fileName, activity, sac)
@JSExport fun planFromTrack(track: String, activity: String, sac: Int): String =
    // the wait box's share done (TrackToPlan's calls of its most calls, multi-day tours stage 3): "37 %" via planProgress
    WebImport.planFromTrack(track, activity, sac) { done, max -> planProgress("${(100 * done / max.coerceAtLeast(1)).coerceIn(0, 100)} %") }
// the track panel's „Als Plan übernehmen“: the app's TrackToPlanState on the page's points (WebTrackToPlan.kt)
@JSExport fun trackToPlan(plan: String, segments: String, waypoints: String, activity: String, sac: Int): String =
    WebTrackToPlan.plan(plan, segments, waypoints, activity, sac) { done, max -> planProgress("${(100 * done / max.coerceAtLeast(1)).coerceIn(0, 100)} %") }
// the app's texts for map areas on demand and waits (WebMaps.kt, web-planner.md §17)
@JSExport fun mapTexts(): String = WebMaps.texts()
// ---- plan sync (WebSync.kt, docs/design/web-sync.md) ----
@JSExport fun syncPairNew(random: String): String = WebSync.pairNew(random)
@JSExport fun syncPairParse(text: String): String = WebSync.pairParse(text)
@JSExport fun syncKeys(pair: String): String = WebSync.keys(pair)
@JSExport fun syncNewId(random: String): String = WebSync.newId(random)
@JSExport fun syncNewDevice(random: String): String = WebSync.newDevice(random)
@JSExport fun syncList(ledger: String, device: String): String = WebSync.list(ledger, device)
@JSExport fun syncDevices(ledger: String, device: String): String = WebSync.devices(ledger, device)
@JSExport fun syncPhoneGone(ledger: String, phone: String): Boolean = WebSync.phoneGone(ledger, phone)
@JSExport fun syncState(ledger: String): String = WebSync.state(ledger)
@JSExport fun syncLimits(): String = WebSync.limits()
@JSExport fun syncSave(ledger: String, id: String, plan: String, title: String, now: Double, device: String,
                       routing: String, routingData: String): String =
    WebSync.save(ledger, id, plan, title, now, device, routing, routingData)
/** Save (Speichern) of the page's plan: with this engine's routing fingerprint and rd5 dates (fields 7 / 8, §5.2). */
@JSExport fun syncSaveRouted(ledger: String, id: String, plan: String, title: String, now: Double, device: String): String =
    WebSync.saveRouted(ledger, id, plan, title, now, device)
@JSExport fun syncRename(ledger: String, id: String, title: String, now: Double, device: String): String =
    WebSync.rename(ledger, id, title, now, device)
@JSExport fun syncSetFavourite(ledger: String, id: String, favourite: Int, now: Double, device: String): String =
    WebSync.setFavourite(ledger, id, favourite != 0, now, device)
@JSExport fun syncPlanFacts(plan: String): String = WebSync.planFacts(plan)
@JSExport fun syncDelete(ledger: String, id: String, now: Double, device: String): String = WebSync.delete(ledger, id, now, device)
@JSExport fun syncHello(ledger: String, device: String, name: String, now: Double): String = WebSync.hello(ledger, device, name, now)
@JSExport fun syncForget(ledger: String): String = WebSync.forget(ledger)
/** The mailbox lost its history (review sync M4, the app's SyncRound): read from 0, every live item to send again. */
@JSExport fun syncRestart(ledger: String): String = com.onpath.app.sync.SyncLedger.read(ledger).also { it.restart() }.write()
/** Save over a saved route that sync changed meanwhile → a copy (review sync H3, the app's sync/SaveRule). */
@JSExport fun saveKeepsOtherEdit(opened: String, stored: String): Boolean =
    com.onpath.app.sync.SaveRule.keepsOtherEdit(opened.ifEmpty { null }, stored.ifEmpty { null })
// names of saved routes and when a route is fitted (the app's routing/PlanNames.kt – was mirrored in plans.ts / main.ts);
// [taken] = names, one per line
@JSExport fun planNewRouteName(proposed: String, dated: String, taken: String): String =
    com.onpath.app.routing.PlanNames.newRouteName(proposed, dated, taken.split('\n').filter { it.isNotEmpty() })
@JSExport fun planCopyName(name: String, taken: String): String =
    com.onpath.app.routing.PlanNames.copyName(name, taken.split('\n').filter { it.isNotEmpty() })
@JSExport fun routeFirst(before: Int, after: Int): Boolean = com.onpath.app.routing.RouteFit.firstRoute(before, after)
@JSExport fun routeFitAfterPlace(before: String, after: String): Boolean =
    com.onpath.app.routing.RouteFit.fitAfterPlace(Engine.parsePoints(before), Engine.parsePoints(after))
@JSExport fun syncApply(ledger: String, pair: String, page: String, upTo: Double): String = WebSync.apply(ledger, pair, page, upTo)
@JSExport fun syncOutbox(ledger: String, pair: String, random: String): String = WebSync.outbox(ledger, pair, random)
@JSExport fun syncAcked(ledger: String, sent: String, rev: Double): String = WebSync.acked(ledger, sent, rev)
// A-017 (web-sync.md §5.1, §14 folders): JOIN redirect, device role, tracks, push only on Save, read again from the start
@JSExport fun syncOutboxOf(ledger: String, pair: String, random: String, only: String): String =
    WebSync.outbox(ledger, pair, random, only)
@JSExport fun syncHelloAs(ledger: String, device: String, name: String, now: Double, role: Int): String =
    WebSync.hello(ledger, device, name, now, role)
@JSExport fun syncPeek(pair: String, page: String): String = WebSync.peek(pair, page)
@JSExport fun syncJoinBlob(pair: String, device: String, phoneCode: String, now: Double, nonce: String): String =
    WebSync.joinBlob(pair, device, phoneCode, now, nonce)
@JSExport fun syncHelloId(device: String): String = WebSync.helloId(device)
@JSExport fun syncRebase(ledger: String, sameMailbox: Int): String = WebSync.rebase(ledger, sameMailbox != 0)
/** Tours of folder [from] moved into folder [into] – never stamped newer, nothing in [into] pushed away (owner 2026-11-12). */
@JSExport fun syncTake(into: String, from: String): String = WebSync.take(into, from)
@JSExport fun syncSaveTrack(ledger: String, id: String, track: String, title: String, now: Double, device: String): String =
    WebSync.saveTrack(ledger, id, track, title, now, device)
@JSExport fun syncTrackFit(segments: String, marks: String, activity: String, sac: Int): String = WebSync.trackFit(segments, marks, activity, sac)
@JSExport fun syncTrackGpx(track: String, title: String): String = WebSync.trackGpx(track, title)
// kept places ⇄ the sync (web-sync.md §5.4: kind 5, the shared PlaceSync with WebKept's line codec)
@JSExport fun syncPlacesExport(ledger: String, home: String, favourites: String, now: Double, device: String, waiting: String): String =
    WebSync.placesExport(ledger, home, favourites, now, device, waiting)
@JSExport fun syncPlacesImport(ledger: String, ids: String, home: String, favourites: String, seenHome: String, seenFavourites: String,
    seenKnown: Int): String = WebSync.placesImport(ledger, ids, home, favourites, seenHome, seenFavourites, seenKnown != 0)
@JSExport fun syncPlacesChanged(before: String, after: String): String = WebSync.placesChanged(before, after)
@JSExport fun syncPlaceItems(ledger: String): String = WebSync.placeItems(ledger)
@JSExport fun syncPlacesForget(ledger: String): String = WebSync.placesForget(ledger)

// ---- web UI rules (WebUi.kt): route name proposal, POI kind filters ----
@JSExport fun routeName(line: String, names: String): String = WebUi.routeName(line, names)
@JSExport fun poiKinds(): String = WebUi.poiKinds()
// pace setting (WebPace.kt – the app's Settings → Pace)
@JSExport fun paceSet(hike: String, bike: String): String = WebPace.set(hike, bike)
@JSExport fun paceChoices(activity: String): String = WebPace.choices(activity)
@JSExport fun planTime(points: String, activity: String, sac: Int): String = WebPace.planTime(points, activity, sac)
/** The big profile's marked range: length, time and climb of the last plan between two distances (WebPace.rangeStats). */
@JSExport fun rangeStats(points: String, activity: String, sac: Int, fromM: Double, toM: Double): String = WebPace.rangeStats(points, activity, sac, fromM, toM)
@JSExport fun poiFilter(original: String, hidden: String): String = WebUi.poiFilter(original, hidden)
/**
 * The map look for the activity (the app's map/ActivityLayers, as MapController.setOverlay / setSacLimit apply it):
 * `{"visibility":{"<layer id>":true|false,…},"filters":{"routes-hike":[…],"routes-hike-over":[…]}}` for the style's
 * layer ids [layerIds] (comma-separated, style order).
 */
@JSExport fun activityLayers(activity: String, sac: Int, layerIds: String): String =
    com.onpath.app.map.ActivityLayers.toJson(Activity.valueOf(activity), sac, layerIds.split(',').filter { it.isNotEmpty() })

// round-trip suggestions (WebSuggest.kt, docs/design/tour-suggestions.md §7 stage 3): op need | run, the wish as JSON
@JSExport fun suggest(op: String, json: String): String = WebSuggest.call(op, json)

// ---- multi-day tours (WebDays.kt, docs/design/multi-day-tours.md stages 2 + 3, session „mehrtage“ 2026-11-17) ----
// [tour] = the page's tour text (WebDays.parseTour), "" = a normal plan
@JSExport fun planCodeTour(points: String, activity: String, sac: Int, pinned: String, names: String, title: String, tour: String): String =
    WebDays.planCode(points, activity, sac, pinned, names, title, tour)
@JSExport fun daysView(points: String, activity: String, sac: Int, pinned: String, names: String, tour: String): String =
    WebDays.view(points, activity, sac, pinned, names, tour)
@JSExport fun daysRule(op: String, points: String, activity: String, sac: Int, pinned: String, names: String, tour: String,
                       n: Int, lat: Double, lon: Double, text: String): String =
    WebDays.rule(op, points, activity, sac, pinned, names, tour, n, lat, lon, text)
@JSExport fun hotelText(text: String): String = WebDays.hotelText(text)
/** TrackToPlan's range (the trim handles): the track between [fromM] and [toM] along it, "lat,lon;…". */
@JSExport fun trackCut(track: String, fromM: Double, toM: Double): String =
    com.onpath.app.routing.TrackToPlan.cut(Engine.parsePoints(track), fromM, toM).joinToString(";") { Engine.num(it.lat, 6) + "," + Engine.num(it.lon, 6) }
/** "An die Route anhängen": [add]'s route points after [points] (turned round when its end is nearer). */
@JSExport fun trackAppend(points: String, add: String): String =
    com.onpath.app.routing.TrackToPlan.append(Engine.parsePoints(points), Engine.parsePoints(add)).joinToString(";") { Engine.num(it.lat, 6) + "," + Engine.num(it.lon, 6) }

fun main() {}

// ---- the wait window (form A) and the sign beside (form B): WebWait.kt, the page's second engine (waitengine.ts) ----
@JSExport fun waitStart(shares: String, check: String, part: String) = WebWait.start(shares, check, part)
@JSExport fun waitTick(now: Double, areas: String, dlDone: Double, dlTotal: Double, map: Boolean, routing: Boolean, join: Double,
                       routeDone: Int, routeTotal: Int, checking: Boolean, names: String, points: Int, paused: Boolean): String =
    WebWait.tick(now, areas, dlDone, dlTotal, map, routing, join, routeDone, routeTotal, checking, names, points, paused)
@JSExport fun waitEnd(now: Double, finished: Boolean): String = WebWait.end(now, finished)
@JSExport fun signTick(now: Double, areas: String): Int = WebSign.tick(now, areas)
@JSExport fun signSeed(percent: Int) = WebSign.seed(percent)

// ---- live tracking, the viewer page (WebLive.kt, docs/design/live-tracking.md §14.5) ----
@JSExport fun liveKeys(link: String): String = WebLive.keys(link)
@JSExport fun liveOpen(link: String, name: String, blob: String): String = WebLive.open(link, name, blob)
@JSExport fun liveStatus(link: String, headBlob: String, gone: Boolean, broken: Boolean, sinceLastWriteMs: Double, endedMs: Double): String =
    WebLive.status(link, headBlob, gone, broken, sinceLastWriteMs, endedMs)
@JSExport fun liveNext(sinceLastWriteMs: Double, nextS: Int, emptyAsks: Int, over: Boolean, retryAfterMs: Double): Double =
    WebLive.next(sinceLastWriteMs, nextS, emptyAsks, over, retryAfterMs)
// the phone's side for live-smoke.mjs (a fake phone) – the page never calls these
@JSExport fun liveLink(random: String, base: String): String = WebLive.link(random, base)
@JSExport fun liveSealHead(link: String, fields: String, nonce: String): String = WebLive.sealHead(link, fields, nonce)
@JSExport fun liveSealRoute(link: String, line: String, nonce: String): String = WebLive.sealRoute(link, line, nonce)
@JSExport fun liveSealChunk(link: String, n: Int, points: String, nonce: String): String = WebLive.sealChunk(link, n, points, nonce)
