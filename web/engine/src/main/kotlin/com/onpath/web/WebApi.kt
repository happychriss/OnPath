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
import com.onpath.app.routing.SharedPlan
import com.onpath.app.routing.RouteSummary
import com.onpath.app.routing.WaypointInsertion
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.RoutingException
import com.onpath.app.routing.Share
import com.onpath.app.time.RouteTimes
import com.onpath.app.poi.PoiLinks
import com.onpath.app.recording.GpsStatus
import com.onpath.app.ui.MapGestures
import com.onpath.app.ui.PlanHistory
import com.onpath.app.ui.RouteWarnings
import com.onpath.strings.*
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

    fun init(rootDir: String) {
        root = File(rootDir)
        android.content.res.AssetManager.root = File(root, "assets")
        engine = null
        fingerprint = null // of the profiles handed over to this root
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
    fun partsNeeded(points: String): String =
        Rd5Parts.forCorridor(parsePoints(points)).filter { it !in have }
            .joinToString(",", "[", "]") { "[" + str(it.tile) + "," + it.index + "]" }

    private val have = HashSet<Rd5Parts.Part>()
    private val layouts = HashMap<String, Pair<Rd5Parts.Index, ByteArray>>()
    private val partBytes = HashMap<String, MutableMap<Int, ByteArray>>()

    /** Remote layout of [tile]: its first 200 bytes and its tail (bytes after the last part). */
    fun putLayout(tile: String, index: ByteArray, tail: ByteArray) {
        val ix = Rd5Parts.Index(index)
        ix.check()
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
     * [names] = lines "index<TAB>name".
     */
    fun plan(points: String, activity: String, sac: Int, pinned: String = "", names: String = ""): String {
        val t0 = System.currentTimeMillis()
        return try {
            val wps = parsePoints(points)
            val act = Activity.valueOf(activity)
            val nm = parseNames(names)
            val res = PlanResult.plan(engine(), wps, act, sac, parseIndices(pinned), nm.filterValues { it.isNotBlank() }.keys,
                WebPace.factors(act))
            lastResult = res
            planJson(res, nm, System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            Problems.error("web", "Plan request failed", e)
            "{\"error\":" + str("Unexpected: $e") + ",\"legs\":[]}"
        }
    }

    /** The page's JSON of a [PlanResult] (texts from the app's Format, RouteWarnings and PlanGaps). */
    internal fun planJson(res: PlanResult, names: Map<Int, String>, ms: Long): String = buildString {
        val p = res.route
        val sac = res.sac
        append("{\"distanceM\":").append(num(p.distanceM))
        append(",\"ascentM\":").append(num(p.ascentM))
        append(",\"descentM\":").append(num(p.descentM))
        // the model time (PlanResult.withModelTime: TimeEstimate.planSeconds with the pace setting), not the router's
        append(",\"timeS\":").append(num(p.durationS))
        append(",\"difficulty\":").append(str(res.rating.text))
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
        append(",\"gaps\":").append(WebPlanRules.gapsJson(res, names))
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
        history.replacePoints(com.onpath.app.routing.WaypointSnap.moves(parsePoints(routed), parsePoints(snapped)))
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
            "reverse" -> PlanEdits.reverse(w)
            "roundTrip" -> PlanEdits.roundTrip(w)
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
                ",\"legM\":" + (r.legM?.let { num(it, 0) } ?: "null") + ",\"approx\":" + r.approx + ",\"loop\":" + r.loop + "}"
        } .let { "{\"roundTrip\":" + PlanEdits.isRoundTrip(w) + ",\"rows\":" + it + "}" }
    }

    /**
     * The place card's button for a search result (the app's [com.onpath.app.search.AddPreview]: what adding does –
     * "Set as destination", "Add as stop 2", … – with the same WaypointInsertion as a map click), its short reason, the
     * index it would take and whether it already sets the start / the destination (the app's `placeCardUi`). JSON.
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
        return "{\"index\":" + pr.decision.index + ",\"label\":" + str(pr.label) + ",\"subtitle\":" + str(pr.subtitle) +
            ",\"addIsStart\":" + (k == WaypointInsertion.Kind.START) +
            ",\"addIsDestination\":" + (k == WaypointInsertion.Kind.DESTINATION && w.size <= 1) + "}"
    }

    private fun parseNames(names: String): Map<Int, String> = names.split('\n').mapNotNull { l ->
        val i = l.substringBefore('\t').toIntOrNull() ?: return@mapNotNull null
        i to l.substringAfter('\t')
    }.toMap()

    private fun parseIndices(s: String): Set<Int> = s.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

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
            append('}')
        }
    }

    // ---- undo / redo: the app's PlanHistory (ui/PlanState.kt) on the page's point list ----
    private val history = PlanHistory()

    /** Before a change of the points: [points] as they are now (one Undo step). */
    fun historyRecord(points: String) = history.record(parsePoints(points))

    /** "undo" / "redo": the point list to show, or "null" when there is nothing to take back. */
    fun historyStep(op: String, points: String): String {
        val now = parsePoints(points)
        val out = (if (op == "redo") history.redo(now) else history.undo(now)) ?: return "null"
        return out.joinToString(";") { num(it.lat, 6) + "," + num(it.lon, 6) }
    }

    fun historyState(): String = "{\"canUndo\":" + history.canUndo + ",\"canRedo\":" + history.canRedo + "}"

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
    fun pointMenu(index: Int, count: Int, isPlace: Boolean, roundTrip: Boolean): String =
        MapGestures.pointMenu(index, count, isPlace, roundTrip).joinToString(",", "[", "]") { str(it.name) }

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
        return "{\"title\":" + str(place.title(false)) + ",\"sub\":" + str(place.kindInfo.label(false) + (place.ele?.let { " · $it m" } ?: "")) +
            ",\"color\":" + str("#" + color.toString(16).padStart(6, '0')) + ",\"google\":" + str(PoiLinks.googleMaps(poi).webUrl) + "}"
    }

    /**
     * "Export GPX" of the page's plan, the app's plan export ([com.onpath.app.gpx.PlanGpx]): the routed line, the plan
     * points as `<wpt>` with their names, the sport, "<name> - plan.gpx". [points] / [names] = the page's plan (the line
     * must be this plan's). JSON {file, gpx} or "null".
     */
    fun gpx(name: String, activity: String, sac: Int, points: String, names: String): String {
        val w = parsePoints(points)
        val line = lastPlanFor(w)?.points?.takeIf { it.size >= 2 } ?: return "null"
        val n = name.ifBlank { Txt.s(Res.string.msg_name_share) }
        val nm = parseNames(names)
        val f = com.onpath.app.gpx.PlanGpx.export(n, listOf(line.map { com.onpath.app.gpx.TrackPoint(it.lat, it.lon, it.ele) }),
            com.onpath.app.gpx.PlanGpx.stops(w) { nm[it]?.takeIf { s -> s.isNotBlank() } }, Activity.valueOf(activity), sac)
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
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
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

@JSExport fun init(rootDir: String) = Engine.init(rootDir)

/** The text language of everything the engine writes ("en", "de"; web-planner.md §18). Returns the one used. */
@JSExport fun setLanguage(lang: String): String = com.onpath.strings.Txt.setLanguage(lang)

@JSExport fun putFile(relPath: String, data: Int8Array) = Engine.putFile(relPath, data.copyToJavaArray())

@JSExport fun partsNeeded(points: String): String = Engine.partsNeeded(points)

@JSExport fun putLayout(tile: String, index: Int8Array, tail: Int8Array) =
    Engine.putLayout(tile, index.copyToJavaArray(), tail.copyToJavaArray())

@JSExport fun putPart(tile: String, index: Int, data: Int8Array): Boolean = Engine.putPart(tile, index, data.copyToJavaArray())

@JSExport fun writeParts() = Engine.writeParts()

@JSExport fun route(points: String, activity: String, sac: Int): String = Engine.route(points, activity, sac)

@JSExport fun plan(points: String, activity: String, sac: Int, pinned: String, names: String): String =
    Engine.plan(points, activity, sac, pinned, names)

@JSExport fun historySnap(routed: String, snapped: String) = Engine.historySnap(routed, snapped)

/** The routing fingerprint + loaded rd5 dates, the app's Diagnostics line (the page's window.__diag). */
@JSExport fun routingInfo(): String = Engine.routingInfo()

@JSExport fun planCode(points: String, activity: String, sac: Int, pinned: String, names: String, title: String): String =
    Engine.planCode(points, activity, sac, pinned, names, title)

@JSExport fun planParse(text: String): String = Engine.planParse(text)

@JSExport fun edit(op: String, points: String, index: Int, to: Int): String = Engine.edit(op, points, index, to)

@JSExport fun rows(points: String, names: String): String = Engine.rows(points, names)

@JSExport fun placePreview(points: String, lat: Double, lon: Double): String = Engine.placePreview(points, lat, lon)

@JSExport fun searchAddArea(key: String, dir: String): Int = WebSearch.addArea(key, dir)

@JSExport fun searchHasArea(key: String): Boolean = WebSearch.hasArea(key)

@JSExport fun search(text: String, lat: Double, lon: Double, route: String): String = WebSearch.search(text, lat, lon, route)

@JSExport fun searchOnline(text: String, photonJson: String): String = WebSearch.online(text, photonJson)

@JSExport fun insertIndex(points: String, lat: Double, lon: Double, lineSnapM: Double): Int =
    Engine.insertIndex(points, lat, lon, lineSnapM)

@JSExport fun historyRecord(points: String) = Engine.historyRecord(points)

@JSExport fun historyStep(op: String, points: String): String = Engine.historyStep(op, points)

@JSExport fun historyState(): String = Engine.historyState()

@JSExport fun gesture(menuOpen: Boolean, marker: Int, poi: Boolean, planEmpty: Boolean): String =
    Engine.gesture(menuOpen, marker, poi, planEmpty)

@JSExport fun pointMenu(index: Int, count: Int, isPlace: Boolean, roundTrip: Boolean): String =
    Engine.pointMenu(index, count, isPlace, roundTrip)

@JSExport fun fromMeBlocked(status: String, hasFix: Boolean): String = Engine.fromMeBlocked(status, hasFix)

@JSExport fun googleMaps(name: String, lat: Double, lon: Double, label: String): String = Engine.googleMaps(name, lat, lon, label)

@JSExport fun poiMenu(kind: String, name: String, ele: String, lat: Double, lon: Double): String =
    Engine.poiMenu(kind, name, ele, lat, lon)

@JSExport fun gpx(name: String, activity: String, sac: Int, points: String, names: String): String =
    Engine.gpx(name, activity, sac, points, names)

@JSExport fun activities(): String = Engine.activities()

@JSExport fun problems(): String = Engine.problems()

// plan rules before routing (WebPlanRules.kt) and GPX import (WebImport.kt)
@JSExport fun longRoute(points: String, activity: String): String = WebPlanRules.longRoute(points, activity)
@JSExport fun planCancel(cause: String, canUndo: Boolean, canRedo: Boolean): String = WebPlanRules.onCancel(cause, canUndo, canRedo)
@JSExport fun gpxRead(data: Int8Array, fileName: String, activity: String, sac: Int): String =
    WebImport.read(data.copyToJavaArray(), fileName, activity, sac)
@JSExport fun planFromTrack(track: String, activity: String, sac: Int): String = WebImport.planFromTrack(track, activity, sac)
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
@JSExport fun syncState(ledger: String): String = WebSync.state(ledger)
@JSExport fun syncSave(ledger: String, id: String, plan: String, title: String, now: Double, device: String,
                       routing: String, routingData: String): String =
    WebSync.save(ledger, id, plan, title, now, device, routing, routingData)
/** Save (Speichern) of the page's plan: with this engine's routing fingerprint and rd5 dates (fields 7 / 8, §5.2). */
@JSExport fun syncSaveRouted(ledger: String, id: String, plan: String, title: String, now: Double, device: String): String =
    WebSync.saveRouted(ledger, id, plan, title, now, device)
@JSExport fun syncRename(ledger: String, id: String, title: String, now: Double, device: String): String =
    WebSync.rename(ledger, id, title, now, device)
@JSExport fun syncDelete(ledger: String, id: String, now: Double, device: String): String = WebSync.delete(ledger, id, now, device)
@JSExport fun syncHello(ledger: String, device: String, name: String, now: Double): String = WebSync.hello(ledger, device, name, now)
@JSExport fun syncForget(ledger: String): String = WebSync.forget(ledger)
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
@JSExport fun syncSaveTrack(ledger: String, id: String, track: String, title: String, now: Double, device: String): String =
    WebSync.saveTrack(ledger, id, track, title, now, device)
@JSExport fun syncTrackFit(segments: String, marks: String, activity: String, sac: Int): String = WebSync.trackFit(segments, marks, activity, sac)
@JSExport fun syncTrackGpx(track: String, title: String): String = WebSync.trackGpx(track, title)

// ---- web UI rules (WebUi.kt): route name proposal, POI kind filters ----
@JSExport fun routeName(line: String, names: String): String = WebUi.routeName(line, names)
@JSExport fun poiKinds(): String = WebUi.poiKinds()
// pace setting (WebPace.kt – the app's Settings → Pace)
@JSExport fun paceSet(hike: String, bike: String): String = WebPace.set(hike, bike)
@JSExport fun paceChoices(activity: String): String = WebPace.choices(activity)
@JSExport fun planTime(points: String, activity: String, sac: Int): String = WebPace.planTime(points, activity, sac)
@JSExport fun poiFilter(original: String, hidden: String): String = WebUi.poiFilter(original, hidden)
/**
 * The map look for the activity (the app's map/ActivityLayers, as MapController.setOverlay / setSacLimit apply it):
 * `{"visibility":{"<layer id>":true|false,…},"filters":{"routes-hike":[…],"routes-hike-over":[…]}}` for the style's
 * layer ids [layerIds] (comma-separated, style order).
 */
@JSExport fun activityLayers(activity: String, sac: Int, layerIds: String): String =
    com.onpath.app.map.ActivityLayers.toJson(Activity.valueOf(activity), sac, layerIds.split(',').filter { it.isNotEmpty() })

fun main() {}

