package com.onpath.app.suggest.app

import com.onpath.app.debug.Problems
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.suggest.Character
import com.onpath.app.suggest.Land
import com.onpath.app.suggest.NetReader
import com.onpath.app.suggest.ReaderNetSource
import com.onpath.app.suggest.RouterProfile
import com.onpath.app.suggest.SuggestAsk
import com.onpath.app.suggest.SuggestCards
import com.onpath.app.suggest.SuggestFile
import com.onpath.app.suggest.SuggestFiles
import com.onpath.app.suggest.SuggestSettings
import com.onpath.app.suggest.TourSuggester
import java.io.File

/**
 * The app's entry to the tour suggestions (docs/design/tour-suggestions.md §7, stage 4 – the part without UI): a wish
 * ([SuggestAsk]) in, light cards ([SuggestCards]) out. Plain JVM, no Android: [AppSuggest] gives it the app's folders.
 *
 *  - the network: the app's rd5 files ([segmentsDir]) through [NetReader] with the router's own profile and parameters
 *    ([RouterProfile], .brf + lookups.dat in [profileDir]);
 *  - the cells' suggest files: [suggestFileOf] (the cell store's `suggest/10-x-y.bin`); a cell in reach without one or
 *    with one older than [SuggestFile.CONTENT] → [SuggestCards.mapUpdating] and [SuggestCards.cellsToFetch];
 *  - the method's numbers: [settingsText] (assets/suggest/settings.json – a copy of tools/suggest-poc/settings.json).
 *
 * Memory: everything a run reads (network, place, parsed suggest files ~45 MB for a 3 h bike wish) is local to [suggest]
 * and gone when it returns – only the cards leave. Blocking; the app calls it through [SuggestRunner] (background
 * thread, one run at a time, a new wish stops the old one).
 */
class SuggestService(
    private val segmentsDir: File,
    private val profileDir: File,
    private val suggestFileOf: (x: Int, y: Int) -> File,
    private val settingsText: () -> String,
    /** Of the given rd5 part keys ([Rd5Parts.Part.key]) the ones not on the phone (the app: RoutingDataLoader.missing). */
    private val routingMissing: (Collection<String>) -> List<String> = { emptyList() },
    /** „Markierten Wegen folgen“ (Settings → Plan): the same profile parameters the routing engines use. */
    private val markedTrails: () -> Int = { com.onpath.app.routing.MarkedTrails.STANDARD },
) {
    /** What a wish needs: the rd5 parts within reach (keys for RoutingDataLoader.ensure) and the zoom-10 cells [x, y]. */
    class Need(val routingParts: List<String>, val cells: List<IntArray>)

    @Volatile private var settings: Pair<String, SuggestSettings>? = null

    fun settings(): SuggestSettings {
        val text = settingsText()
        settings?.takeIf { it.first == text }?.let { return it.second }
        return SuggestSettings.parse(text).also { settings = text to it }
    }

    fun need(ask: SuggestAsk): Need {
        val S = settings()
        val w = ask.wish(S)
        val radius = Character(w, S).radius(S)
        // the network reader reaches the radius; a little more for ways leaving it at the rim (as the planner's `need`)
        val parts = Rd5Parts.forPoint(GeoPoint(w.lat, w.lon), radius + 2_000.0).map { it.key }
        return Need(parts, Land.cellsFor(w.lon, w.lat, radius))
    }

    /**
     * Runs the method for [ask]. [check] is called between the phases (and before the work starts); throwing there
     * (the runner: a newer wish came) stops the run; [lapped] hears each of the method's phases as it ends (key,
     * seconds – TourSuggester). Missing routing data → no run, [SuggestCards.routingToLoad] names
     * the parts; no way near the start → [SuggestCards.none].
     */
    fun suggest(ask: SuggestAsk, check: () -> Unit = {}, lapped: (String, Double) -> Unit = { _, _ -> }): SuggestCards {
        val S = settings()
        val wish = ask.wish(S)
        val need = need(ask)
        val missing = routingMissing(need.routingParts)
        if (missing.isNotEmpty()) return SuggestCards.loadRouting(missing)
        check()
        // the cells' files, read once per run (the method asks for a cell several times) and dropped with the run
        val read = HashMap<Long, SuggestFile?>()
        val files = SuggestFiles { x, y ->
            val key = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
            if (read.containsKey(key)) read[key] else readCell(x, y).also { read[key] = it }
        }
        val reader = NetReader(segmentsDir)
        val source = ReaderNetSource(reader) { _ -> RouterProfile.of(profileDir, ask.activity, ask.sac, markedTrails()) }
        val t0 = System.currentTimeMillis()
        val r = try {
            TourSuggester.suggest(wish, source, files, S, check, lapped)
        } catch (e: NetReader.ReadException) {
            Problems.warn("suggest", "no network around the start: ${e.message}", e)
            return SuggestCards.none()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e // a newer wish: not a problem
            // robust first: a broken profile / file / method error gives "nothing found", never a crash
            Problems.error("suggest", "tour suggestions failed", e)
            return SuggestCards.none()
        }
        val fetch = need.cells.filter { files.cell(it[0], it[1]).let { f -> f == null || f.content < SuggestFile.CONTENT } }
        val cards = SuggestCards.of(r, fetch)
        Problems.trace("suggest", "${cards.cards.size} cards of ${cards.loops} loops, ${System.currentTimeMillis() - t0} ms, " +
            "${cards.times}, network ${reader.stats}" + (if (fetch.isNotEmpty()) ", ${fetch.size} cells without a current suggest file" else ""))
        return cards
    }

    private fun readCell(x: Int, y: Int): SuggestFile? {
        val f = suggestFileOf(x, y)
        if (!f.isFile || f.length() == 0L) return null
        return try {
            SuggestFile.read(f.readBytes())
        } catch (e: Exception) {
            Problems.warn("suggest", "suggest file of cell 10-$x-$y not readable – treated as missing", e)
            null
        }
    }
}
