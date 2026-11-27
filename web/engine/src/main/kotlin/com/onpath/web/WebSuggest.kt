package com.onpath.web

import com.onpath.app.debug.Problems
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.suggest.Character
import com.onpath.app.suggest.Land
import com.onpath.app.suggest.NetReader
import com.onpath.app.suggest.ReaderNetSource
import com.onpath.app.suggest.RouterProfile
import com.onpath.app.suggest.SuggestAsk
import com.onpath.app.suggest.SuggestCard
import com.onpath.app.suggest.SuggestCards
import com.onpath.app.suggest.SuggestFile
import com.onpath.app.suggest.SuggestFiles
import com.onpath.app.suggest.SuggestSettings
import com.onpath.app.suggest.TourSuggester
import com.onpath.app.suggest.Wish
import com.onpath.strings.*
import java.io.File

/**
 * Round-trip suggestions in the browser planner (docs/design/tour-suggestions.md §7 "Stage 3 – the ready brief"): the
 * app's [TourSuggester], unchanged, on the routing data and the map cells' suggest files the page hands over. One entry
 * ([call], registered in WebApi as `suggest`) with two steps, so the page fetches only what a wish needs:
 *  - `need`: the wish → `{"parts":[["E6_N51.rd5",7],…],"cells":[[x,y],…],"settings":true|false}` – the rd5 parts within
 *    reach of the start and the zoom-10 cells whose `suggest.bin` the method reads ([cellsDir]/10-x-y/suggest.bin, a
 *    missing file = the map is being updated there);
 *  - `run`: the wish → the cards (`{"cards":[…],"notes":[…],"mapUpdating":…,"hours":[…],"ascent":[…],"ms":…}`) or
 *    `{"error":"…"}`.
 * Wish JSON: `{"lat","lon","activity":"HIKE"|…,"sac":2,"hours":[2,3],"ascent":[0,400]|null,"ziel":["gipfel",…]}`.
 * The cards and their sketch-7 pills are the shared core's ([SuggestCards], [com.onpath.app.suggest.SuggestPills]) – the
 * app shows the same; the words are the shared strings (strings_suggest.xml).
 */
object WebSuggest {
    /** Where the page puts the method's numbers (tools/suggest-poc/settings.json, one file for POC, app and planner). */
    const val SETTINGS = "assets/suggest/settings.json"
    /** Where the page puts the cells' suggest files: suggest/10-x-y/suggest.bin. */
    private const val CELLS = "suggest"

    private fun root(): File = android.content.res.AssetManager.root?.parentFile ?: File("/onpath")

    private var settings: SuggestSettings? = null
    private var settingsText: String? = null

    /** The parsed suggest files (null = not there), kept while their bytes stay the same file. */
    private val files = HashMap<String, Pair<Long, SuggestFile?>>()

    private val cellFiles = SuggestFiles { x, y ->
        val f = File(root(), "$CELLS/10-$x-$y/${SuggestFile.NAME}")
        val key = "$x-$y"
        val len = if (f.isFile) f.length() else -1L
        val have = files[key]
        if (have != null && have.first == len) have.second else {
            val read = if (len > 0) try { SuggestFile.read(f.readBytes()) } catch (e: Exception) {
                Problems.warn("suggest", "suggest file of cell $key not readable – treated as missing", e); null
            } else null
            files[key] = len to read
            read
        }
    }

    fun call(op: String, json: String): String = try {
        when (op) {
            "need" -> need(json)
            "run" -> run(json)
            else -> err("unknown op $op")
        }
    } catch (e: Exception) {
        Problems.error("suggest", "tour suggestions failed ($op)", e)
        err(e.message ?: e.toString())
    }

    private fun err(text: String) = "{\"error\":" + Engine.str(text) + "}"

    private fun loadSettings(): SuggestSettings? {
        val f = File(root(), SETTINGS)
        if (!f.isFile) return null
        val text = f.readText()
        if (text != settingsText) { settings = SuggestSettings.parse(text); settingsText = text }
        return settings
    }

    /** The planner's activity → the method's (settings.json activities): hike, bike, gravel, mtb ([SuggestAsk]). */
    internal fun methodActivity(a: Activity): String = SuggestAsk.methodActivity(a)

    internal class In(val wish: Wish, val activity: Activity, val sac: Int)

    internal fun parseWish(S: SuggestSettings, json: String): In {
        val j = SuggestSettings.parse(json).root
        val act = Activity.entries.firstOrNull { it.name == (j["activity"].v as? String) } ?: Activity.HIKE
        val sac = if (j["sac"].isNull) 2 else j["sac"].i.coerceIn(1, 5)
        val hours = j["hours"].list.map { it.d }.toDoubleArray().takeIf { it.isNotEmpty() } ?: doubleArrayOf(2.0, 3.0)
        val asc = if (j["ascent"].isNull) null else j["ascent"].list.map { it.v as? Double }.toTypedArray()
        val ziel = j["ziel"].list.mapNotNull { it.v as? String }
        return In(SuggestAsk(j["lat"].d, j["lon"].d, act, sac, hours, asc, ziel).wish(S), act, sac)
    }

    private fun need(json: String): String {
        val S = loadSettings() ?: return "{\"parts\":[],\"cells\":[],\"settings\":false}"
        val w = parseWish(S, json).wish
        val radius = Character(w, S).radius(S)
        // the network reader reaches the radius; a little more for ways leaving it at the rim
        val parts = Rd5Parts.forPoint(GeoPoint(w.lat, w.lon), radius + 2_000.0)
        val cells = Land.cellsFor(w.lon, w.lat, radius + 500.0)
        return buildString {
            append("{\"parts\":").append(parts.joinToString(",", "[", "]") { "[" + Engine.str(it.tile) + "," + it.index + "]" })
            append(",\"cells\":").append(cells.joinToString(",", "[", "]") { "[" + it[0] + "," + it[1] + "]" })
            append(",\"settings\":true}")
        }
    }

    private fun run(json: String): String {
        val S = loadSettings() ?: return err("no settings")
        val inp = parseWish(S, json)
        Engine.writeParts() // parts the page fetched for this wish into the local rd5 files
        val t0 = System.currentTimeMillis()
        val reader = NetReader(File(root(), "segments"))
        val profiles = File(root(), "assets/brouter")
        val source = ReaderNetSource(reader) { _ -> RouterProfile.of(profiles, inp.activity, inp.sac, Engine.markedTrails) }
        val r = try { TourSuggester.suggest(inp.wish, source, cellFiles, S) } catch (e: NetReader.ReadException) {
            Problems.warn("suggest", "no network around the start: ${e.message}", e)
            return buildString {
                append("{\"cards\":[],\"notes\":[").append(Engine.str(Txt.s(Res.string.suggest_note_none))).append("]")
                append(",\"mapUpdating\":false,\"noData\":true,\"ms\":").append(System.currentTimeMillis() - t0).append('}')
            }
        }
        val ms = System.currentTimeMillis() - t0
        val cards = SuggestCards.of(r)
        Problems.trace("suggest", "${cards.cards.size} cards of ${cards.loops} loops, $ms ms, ${cards.times}, network ${reader.stats}")
        return buildString {
            append("{\"cards\":[")
            cards.cards.forEachIndexed { i, c ->
                if (i > 0) append(',')
                card(this, c)
            }
            append("],\"notes\":").append(cards.notes.joinToString(",", "[", "]") { Engine.str(it) })
            append(",\"mapUpdating\":").append(cards.mapUpdating)
            append(",\"hours\":[").append(Engine.num(cards.hours[0])).append(',').append(Engine.num(cards.hours[1])).append(']')
            append(",\"ascent\":[").append(cards.ascent[0]).append(',').append(cards.ascent[1]).append(']')
            append(",\"loops\":").append(cards.loops)
            append(",\"ms\":").append(ms)
            append(",\"times\":{").append(cards.times.entries.joinToString(",") { Engine.str(it.key) + ":" + Engine.num(it.value, 3) }).append('}')
            append('}')
        }
    }

    private fun card(b: StringBuilder, c: SuggestCard) {
        b.append("{\"id\":").append(c.id)
        b.append(",\"name\":").append(Engine.str(c.name))
        b.append(",\"badge\":").append(c.badge?.let { Engine.str(it) } ?: "null")
        b.append(",\"loopKm\":").append(c.loopKm?.let { Engine.num(it, 1) } ?: "null")
        b.append(",\"km\":").append(Engine.num(c.km, 1))
        b.append(",\"hours\":").append(Engine.num(c.hours, 3))
        b.append(",\"ascent\":").append(c.ascent)
        b.append(",\"line\":").append(Engine.str(c.line))
        b.append(",\"pills\":").append(c.pills.joinToString(",", "[", "]") { "{\"t\":" + Engine.str(it.text) + ",\"c\":\"" + it.cls + "\"}" })
        val xy = c.coords
        b.append(",\"coords\":[")
        var k = 0
        while (k < xy.size) {
            if (k > 0) b.append(',')
            b.append('[').append(Engine.num(xy[k + 1], 5)).append(',').append(Engine.num(xy[k], 5)).append(']')
            k += 2
        }
        b.append("]}")
    }
}
