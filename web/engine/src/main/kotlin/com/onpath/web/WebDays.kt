package com.onpath.web

import com.onpath.app.data.Format
import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import com.onpath.app.routing.Activity
import com.onpath.app.routing.DayEnd
import com.onpath.app.routing.DayLimits
import com.onpath.app.routing.DayLine
import com.onpath.app.routing.DayRowText
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.Hotel
import com.onpath.app.routing.NightMark
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.PlanDays
import com.onpath.app.routing.SharedPlan
import com.onpath.app.routing.TourDays
import com.onpath.app.routing.WaypointInsertion
import com.onpath.app.search.RouteNamer
import com.onpath.app.search.SharedPlaceParser
import com.onpath.app.time.TimeEstimate
import com.onpath.strings.*

/**
 * Multi-day tours in the browser planner (docs/design/multi-day-tours.md, stages 2 + 3, owner 2026-11-17): the page
 * only shows – the days, the grey proposals and the rules R1–R7 are the app's shared [PlanDays], the texts its strings
 * (strings_days.xml). The page holds the tour beside its points as a small text ([parseTour]: lines `d N`,
 * `l minutes ascent`, `n index O|H name address link`, `t description`, fields by tab, `\n` `\t` `\\` escaped) and gets
 * it back as JSON ([tourJson]); the routed line is the engine's last plan ([Engine.lastPlanPoints]).
 */
object WebDays {
    /** A tour as the page sends it ([parseTour]); [days] null = a normal plan. */
    class Tour(val days: Int?, val limits: DayLimits, val nights: Map<Int, NightMark>, val description: String?)

    private fun unesc(s: String): String {
        if ('\\' !in s) return s
        val b = StringBuilder(); var k = 0
        while (k < s.length) {
            val c = s[k]
            if (c == '\\' && k + 1 < s.length) {
                when (s[k + 1]) { 'n' -> b.append('\n'); 't' -> b.append('\t'); else -> b.append(s[k + 1]) }
                k += 2
            } else { b.append(c); k++ }
        }
        return b.toString()
    }

    /** The page's tour text; never throws (a broken line is left out). */
    fun parseTour(text: String): Tour {
        var days: Int? = null; var limits = DayLimits.NONE; var desc: String? = null
        val nights = HashMap<Int, NightMark>()
        for (line in text.split('\n')) {
            val f = line.split('\t')
            try {
                when (f[0]) {
                    "d" -> days = f[1].toInt().coerceIn(1, PlanCode.MAX_DAYS)
                    "l" -> limits = DayLimits(f.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }, f.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 })
                    "n" -> {
                        val i = f[1].toInt()
                        val txt = { k: Int -> f.getOrNull(k)?.let { unesc(it) }?.trim()?.ifEmpty { null } }
                        nights[i] = if (f.getOrNull(2) == "H") NightMark(Hotel(txt(3), txt(4), txt(5))) else NightMark.OVERNIGHT
                    }
                    "t" -> desc = f.getOrNull(1)?.let { unesc(it) }?.ifBlank { null }
                }
            } catch (e: Exception) {
                Problems.warn("days", "A line of the page's tour was not read: ${line.take(40)}", e)
            }
        }
        return Tour(days, limits, nights, desc)
    }

    /** The page's plan with its tour ([tour] = the tour text, "" = a normal plan). */
    fun plan(points: String, activity: String, sac: Int, pinned: String, names: String, title: String, tour: String): SharedPlan {
        val t = parseTour(tour)
        val pts = Engine.parsePoints(points)
        return SharedPlan(pts, Activity.valueOf(activity), sac, Engine.parseIndices(pinned), Engine.parseNames(names), title.ifBlank { null },
            t.days, t.limits, if (t.days == null) emptyMap() else PlanDays.clean(pts, t.nights), t.description)
    }

    /** The tour of [p] as JSON for the page, "null" for a normal plan. */
    fun tourJson(p: SharedPlan): String {
        val d = p.days ?: return "null"
        fun s(v: String?) = v?.let { Engine.str(it) } ?: "null"
        return buildString {
            append("{\"days\":").append(d)
            append(",\"limits\":{\"timeMin\":").append(p.limits.maxTimeMin ?: "null").append(",\"ascentM\":").append(p.limits.maxAscentM ?: "null").append('}')
            append(",\"nights\":").append(p.nights.toSortedMap().entries.joinToString(",", "{", "}") { (i, m) ->
                Engine.str(i.toString()) + ":{\"hotel\":" + (m.hotel?.let { h -> "{\"name\":" + s(h.name) + ",\"address\":" + s(h.address) + ",\"link\":" + s(h.link) + "}" } ?: "null") + "}"
            })
            append(",\"description\":").append(s(p.description))
            append('}')
        }
    }

    /** A rule's answer: the whole plan (points, pins, names, tour) – the page takes it as one undo step. */
    private fun planOut(p: SharedPlan, dayAdded: Boolean = false, index: Int = -1): String = buildString {
        append("{\"points\":"); append(p.points.joinToString(",", "[", "]") { "[" + Engine.num(it.lat, 6) + "," + Engine.num(it.lon, 6) + "]" })
        append(",\"pinned\":").append(p.pinned.sorted().joinToString(",", "[", "]"))
        append(",\"names\":").append(p.names.toSortedMap().entries.joinToString(",", "{", "}") { Engine.str(it.key.toString()) + ":" + Engine.str(it.value) })
        append(",\"tour\":").append(tourJson(p))
        append(",\"dayAdded\":").append(dayAdded)
        append(",\"index\":").append(index)
        append('}')
    }

    /** The last [DayLine] (one per routed line: its times are worked out once – the days box asks at every key). */
    private var lineMemo: Triple<List<GeoPoint>, String, DayLine>? = null

    private fun line(p: SharedPlan): DayLine? {
        val pts = Engine.lastPlanPoints(p.points, p.activity, p.sac)?.takeIf { it.size >= 2 } ?: return null
        val f = WebPace.factors(p.activity)
        val key = p.activity.name + (0 until com.onpath.app.time.PaceModel.GROUPS).joinToString(",") { f[it].toString() }
        lineMemo?.let { (l, k, d) -> if (l === pts && k == key) return d }
        return DayLine.of(pts, p.activity, f).also { lineMemo = Triple(pts, key, it) }
    }

    private fun pointAt(p: SharedPlan, l: DayLine) = WaypointInsertion.routeIndices(p.points, l.points)

    /** The line point nearest [lat] / [lon]. */
    private fun nearestLine(l: DayLine, lat: Double, lon: Double): Int =
        l.points.indices.minByOrNull { Geo.distance(lat, lon, l.points[it].lat, l.points[it].lon) } ?: 0

    private fun stats(dist: Double, up: Double, time: Double) =
        Format.distance(dist) + " · ↑ " + Format.meters(up) + " · " + Format.duration(TimeEstimate.round(time))

    private fun limitsText(l: DayLimits): String = listOfNotNull(
        l.maxTimeMin?.let { Format.duration(it * 60.0) }, l.maxAscentM?.let { "↑ " + Format.meters(it.toDouble()) }).joinToString(" · ")

    /**
     * The days of the page's plan for the overview, the days box and the map (rows, day ends, totals, per day, the
     * limits' suggestion, whether "−" may be used). `{"routed":false}` until the engine planned these points.
     */
    fun view(points: String, activity: String, sac: Int, pinned: String, names: String, tour: String): String = try {
        val p = plan(points, activity, sac, pinned, names, "", tour)
        val l = line(p)
        if (l == null) "{\"routed\":false}" else viewJson(p, l)
    } catch (e: Exception) {
        Problems.error("days", "The days of the tour were not worked out", e)
        "{\"routed\":false}"
    }

    /**
     * The day rows' grey „von – nach“ (owner 2026-11-21, variant A – the app's MultiDaySlice.daysUi): one per day of
     * [dayEnds] (null = the finish), null unless both ends are known. RAW names only – a plan point's own name, its
     * hotel's name (or address), the place near it ([nameNear]) –, never a km position or „Start“ / „Ziel“; the map's
     * night markers keep their own names (viewJson's endNames).
     */
    internal fun rowPlaces(p: SharedPlan, dayEnds: List<DayEnd?>, first: GeoPoint, finish: GeoPoint, nameNear: (GeoPoint) -> String?): List<String?> {
        fun named(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }
        fun rawAt(index: Int?, g: GeoPoint): String? =
            (index?.let { i -> named(p.names[i]) ?: p.nights[i]?.hotel?.let { h -> named(h.name) ?: named(h.address) } }) ?: named(nameNear(g))
        // a hotel with its own name, stop name or address (not a place found nearby) – the app's MultiDaySlice isHotel:
        // at the day's only known end it reads „ab …“ / „bis …“ (owner 2026-11-21)
        fun hotelAt(index: Int?): Boolean = index != null &&
            p.nights[index]?.hotel?.let { h -> named(p.names[index]) ?: named(h.name) ?: named(h.address) } != null
        val idx = listOf<Int?>(0) + dayEnds.map { e -> if (e == null) p.points.lastIndex else e.planIndex }
        val at = listOf(rawAt(0, first)) + dayEnds.map { e -> if (e == null) rawAt(p.points.lastIndex, finish) else rawAt(e.planIndex, e.point) }
        return dayEnds.indices.map { k -> DayRowText.places(at[k], at[k + 1], hotelAt(idx[k]), hotelAt(idx[k + 1])) }
    }

    private fun viewJson(p: SharedPlan, l: DayLine): String {
        val at = pointAt(p, l)
        val n = p.days ?: 1
        val td: TourDays = PlanDays.of(l, at, n, p.limits, p.nights)
        val places = try { WebSearch.places() } catch (e: Exception) { Problems.warn("days", "No place names for the day ends", e); emptyList() }
        fun placeName(g: GeoPoint): String? {
            val dLat = Geo.degLat(5000.0); val dLon = Geo.degLon(5000.0, g.lat.coerceIn(-80.0, 80.0))
            val near = places.filter { it.lat in g.lat - dLat..g.lat + dLat && it.lon in g.lon - dLon..g.lon + dLon }
            return RouteNamer.label(g, near)?.takeIf { !it.startsWith("near ") }
        }
        val last = p.points.lastIndex
        val ends = td.days.mapNotNull { it.end }
        val endNames = ends.map { e ->
            e.planIndex?.let { p.names[it] } ?: placeName(e.point) ?: Txt.s(Res.string.days_km_at, Format.number(e.alongM / 1000, if (e.alongM < 10_000) 1 else 0))
        }
        val rowPlaces = rowPlaces(p, td.days.map { it.end }, l.points.first(), l.points.last(), ::placeName)
        val total = l.distM.last(); val up = l.upM.last(); val time = l.timeS.last()
        val days = td.days.size.coerceAtLeast(1)
        return buildString {
            append("{\"routed\":true,\"days\":").append(td.days.size)
            append(",\"canRemove\":").append(PlanDays.canRemoveDay(p))
            append(",\"suggested\":").append(td.suggestedDays ?: "null")
            append(",\"suggestedText\":").append(td.suggestedDays?.let { Engine.str(Txt.s(Res.string.days_suggested, it)) } ?: "null")
            append(",\"countText\":").append(Engine.str(Txt.p(Res.plurals.days_count, td.days.size, td.days.size)))
            append(",\"totals\":").append(Engine.str(stats(total, up, time)))
            append(",\"perDay\":").append(Engine.str(Txt.s(Res.string.days_per_day, stats(total / days, up / days, time / days))))
            append(",\"limits\":").append(if (p.limits.isNone) "null" else Engine.str(Txt.s(Res.string.days_limits_line, limitsText(p.limits))))
            append(",\"totalM\":").append(Engine.num(total, 1))
            append(",\"rows\":[")
            td.days.forEachIndexed { k, d ->
                if (k > 0) append(',')
                append("{\"n\":").append(d.number)
                append(",\"day\":").append(Engine.str(DayRowText.day(d.number)))
                append(",\"places\":").append(rowPlaces[k]?.let { Engine.str(it) } ?: "null")
                append(",\"stats\":").append(Engine.str(stats(d.distanceM, d.ascentM, d.timeS)))
                append(",\"fromM\":").append(Engine.num(d.fromM, 1)).append(",\"toM\":").append(Engine.num(d.toM, 1))
                append(",\"kind\":").append(d.end?.let { Engine.str(it.kind.name) } ?: "null")
                append(",\"plan\":").append(d.end?.planIndex ?: "null")
                // R8: above a limit per day – the values orange (the symbol and the colour say it, no text line)
                append(",\"over\":").append(d.over.isNotEmpty())
                append('}')
            }
            append("],\"ends\":[")
            ends.forEachIndexed { k, e ->
                if (k > 0) append(',')
                append("{\"night\":").append(k + 1).append(",\"kind\":").append(Engine.str(e.kind.name))
                append(",\"lat\":").append(Engine.num(e.point.lat, 6)).append(",\"lon\":").append(Engine.num(e.point.lon, 6))
                append(",\"plan\":").append(e.planIndex ?: "null").append(",\"alongM\":").append(Engine.num(e.alongM, 1))
                append(",\"name\":").append(Engine.str(endNames[k]))
                append('}')
            }
            append("]}")
        }
    }

    /**
     * One rule on the page's plan (each one undo step on the page): `start` (N = [n]), `setDays` (N = [n]), `addNight`
     * (R1 / R7 at the line point nearest [lat] / [lon]), `addNightAt` (R1 on plan point [n]), `removeNight` (plan point [n]), `setHotel` (R3: plan point [n]
     * – or, [n] < 0, the grey proposal at the line point nearest [text]'s 4th / 5th field: R1 + R3 –, the hotel at
     * [lat] / [lon], [text] = "name\taddress\tlink\t…"), `removeHotel` (R4, plan point [n]), `afterEdit` (R6: the points
     * changed to [text] = "lat,lon;…"; marks follow, a dragged night stays one), `nearest` (the day end nearest
     * [lat] / [lon]: `{"night": k}`, scenario F). The whole new plan as JSON, or "null" when nothing changes (not
     * possible, not routed yet).
     */
    fun rule(op: String, points: String, activity: String, sac: Int, pinned: String, names: String, tour: String,
             n: Int, lat: Double, lon: Double, text: String): String = try {
        val p = plan(points, activity, sac, pinned, names, "", tour)
        when (op) {
            "start" -> planOut(PlanDays.start(p, n))
            "setDays" -> PlanDays.setDays(p, n)?.let { planOut(it) } ?: "null"
            "removeNight" -> if (p.nights.containsKey(n)) planOut(PlanDays.removeNight(p, n)) else "null"
            "afterEdit" -> planOut(PlanDays.afterEdit(p, Engine.parsePoints(text)))
            "addNight" -> line(p)?.let { l -> addNight(p, l, nearestLine(l, lat, lon)) } ?: "null"
            // „Übernachtung hier“ in a plan point's menu (owner 2026-11-18): R1 on that point itself – its own line
            // index, so a route that passes the spot twice still marks this point; never the start or the finish
            "addNightAt" -> if (n <= 0 || n >= p.points.lastIndex || p.nights.containsKey(n)) "null"
                else line(p)?.let { l -> pointAt(p, l).getOrNull(n)?.let { addNight(p, l, it) } } ?: "null"
            "removeHotel" -> {
                val l = line(p)
                if (l == null || p.nights[n]?.isHotel != true) "null"
                else planOut(PlanDays.removeHotel(p, n, l.points, pointAt(p, l)), index = n)
            }
            "setHotel" -> {
                val f = text.split('\t')
                val hotel = Hotel(f.getOrNull(0)?.trim()?.ifEmpty { null }?.take(PlanCode.MAX_NAME),
                    f.getOrNull(1)?.trim()?.ifEmpty { null }?.take(PlanCode.MAX_ADDRESS), f.getOrNull(2)?.trim()?.ifEmpty { null }?.take(PlanCode.MAX_LINK))
                var base = p; var index = n; var added = false
                if (n < 0) {
                    val l = line(p) ?: return "null"
                    val e = addNightPlan(p, l, nearestLine(l, f.getOrNull(3)?.toDoubleOrNull() ?: lat, f.getOrNull(4)?.toDoubleOrNull() ?: lon)) ?: return "null"
                    base = e.first; index = e.second; added = e.third
                }
                val r = PlanDays.setHotel(base, index, hotel, GeoPoint(lat, lon))
                planOut(r.plan, added || r.dayAdded, index)
            }
            "nearest" -> {
                val l = line(p) ?: return "null"
                val ends = PlanDays.of(l, pointAt(p, l), p.days ?: 1, p.limits, p.nights).days.mapNotNull { it.end }
                val k = ends.indices.minByOrNull { Geo.distance(lat, lon, ends[it].point.lat, ends[it].point.lon) } ?: return "null"
                "{\"night\":" + (k + 1) + "}"
            }
            else -> "null"
        }
    } catch (e: Exception) {
        Problems.error("days", "Tour rule $op failed", e)
        "null"
    }

    /** R1 at line point [at]: the plan, the marked point's index, whether a day was added; null = not possible. */
    private fun addNightPlan(p: SharedPlan, l: DayLine, at: Int): Triple<SharedPlan, Int, Boolean>? {
        val r = PlanDays.addNight(p, l.points, pointAt(p, l), at)
        if (r.plan === p || r.plan.nights == p.nights && r.plan.points == p.points) return null
        val spot = l.points[at]
        val index = r.plan.nights.keys.filter { it !in p.nights || r.plan.points.size != p.points.size }
            .minByOrNull { Geo.distance(r.plan.points[it], spot) } ?: return null
        return Triple(r.plan, index, r.dayAdded)
    }

    private fun addNight(p: SharedPlan, l: DayLine, at: Int): String =
        addNightPlan(p, l, at)?.let { (q, i, added) -> planOut(q, added, i) } ?: "null"

    /**
     * "Hotel hinterlegen" with a pasted text (a Google Maps link, a Booking link, an address – the app's shared-place
     * reader, [SharedPlaceParser]): `{"lat","lon"}` when the text holds the place, the name / address it says, the link
     * to keep, and `search` = what to look for in the planner's search when there is no position. "null" = nothing.
     */
    fun hotelText(text: String): String = try {
        val t = text.trim()
        val url = Regex("""(?i)\bhttps?://\S+""").find(t)?.value
        val booking = url?.takeIf { Regex("""(?i)^https?://([a-z0-9-]+\.)*booking\.com/""").containsMatchIn(it) }
        val sp = SharedPlaceParser.fromText(t)
        // a Booking link holds no position: its hotel's name is in the path (/hotel/it/gasthof-krone.de.html)
        val bookingName = booking?.let { u ->
            Regex("""/hotel/[a-z]{2}/([a-z0-9-]+)""", RegexOption.IGNORE_CASE).find(u)?.groupValues?.get(1)
                ?.split('-')?.filter { it.isNotEmpty() }?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        }
        val name = bookingName ?: sp?.name
        val address = sp?.address
        val search = when {
            sp?.hasPoint == true -> null
            bookingName != null -> bookingName
            sp != null -> sp.searchTexts().firstOrNull()
            url == null -> t.ifEmpty { null }
            else -> null
        }
        if (sp?.hasPoint != true && search == null) "null" else buildString {
            fun s(v: String?) = v?.let { Engine.str(it) } ?: "null"
            append("{\"lat\":").append(if (sp?.hasPoint == true) Engine.num(sp.lat!!, 6) else "null")
            append(",\"lon\":").append(if (sp?.hasPoint == true) Engine.num(sp.lon!!, 6) else "null")
            append(",\"name\":").append(s(name)).append(",\"address\":").append(s(address))
            append(",\"link\":").append(s(url?.take(PlanCode.MAX_LINK))).append(",\"search\":").append(s(search)).append('}')
        }
    } catch (e: Exception) {
        Problems.warn("days", "A pasted hotel text was not read", e)
        "null"
    }

    /** The plan code of the page's plan with its tour (Save, the link, Zuletzt – the app's [PlanCode]). */
    fun planCode(points: String, activity: String, sac: Int, pinned: String, names: String, title: String, tour: String): String =
        PlanCode.encode(plan(points, activity, sac, pinned, names, title, tour))
}
