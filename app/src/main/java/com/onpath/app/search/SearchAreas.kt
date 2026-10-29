package com.onpath.app.search

import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellStore
import com.onpath.app.debug.Problems
import com.onpath.app.search.OfflineIndex.Companion.TrailPart
import java.io.File
import java.security.MessageDigest

/**
 * One searchable map area: an installed map cell (key "cell-<id>", version = the cell's version, read from the
 * store generation [dir] – only what the cell owns) or the map package in the data folder (key "region", version =
 * size + mtime of its files, [cell] null – everything in [dir]).
 */
data class AreaSpec(val key: String, val version: String, val dir: File, val cell: CellId?) {
    val isRegion: Boolean get() = cell == null
}

/**
 * What the offline search covers now: the areas, and the installed cells (region entries inside an installed cell
 * are left out – the cell owns them, as in the joined map store).
 */
data class AreaPlan(val areas: List<AreaSpec>, val installed: Set<CellId> = emptySet()) {
    /** Same signature = same content: no rebuild (a new store generation with the same cells is not a change). */
    val signature: String get() = areas.joinToString(";") { "${it.key}@${it.version}" } + "|" + installed.sorted().joinToString(",")

    companion object {
        /**
         * The areas of [dataDir]: the current store generation's cells (+ the region package when it is joined),
         * or – without cells – the data folder. Never throws.
         */
        fun of(dataDir: File): AreaPlan {
            val region = AreaSpec(REGION, OfflineIndex.signature(dataDir), dataDir, null)
            val gen = try { CellStore(dataDir).current()?.takeIf { it.cells.isNotEmpty() } } catch (e: Exception) {
                Problems.warn("search", "Downloaded map areas unreadable; search uses the map package in the data folder", e); null
            } ?: return AreaPlan(listOf(region))
            val cells = gen.cells.sortedBy { it.id }.map { AreaSpec("cell-${it.id.id}", it.version, gen.dir, it.id) }
            return AreaPlan(cells + if (gen.region.isNotEmpty()) listOf(region) else emptyList(), gen.cellIds)
        }

        const val REGION = "region"
    }
}

/**
 * An area's searchable content in memory: entries (first [poiCount] POIs, then base-map peaks / places) and unmerged
 * trail pieces.
 */
class SearchArea(
    val key: String,
    val version: String,
    val isRegion: Boolean,
    val entries: List<OfflineIndex.Entry>,
    val poiCount: Int,
    val trails: List<TrailPart>,
) {
    /** Owner cell of each entry / trail piece (region areas only: to leave out what an installed cell owns). */
    private val entryCells: IntArray? = if (isRegion) IntArray(entries.size) { pack(entries[it].place.lat, entries[it].place.lon) } else null
    private val trailCells: IntArray? = if (isRegion) IntArray(trails.size) { pack(trails[it].lat, trails[it].lon) } else null

    /** The POIs ([pois] = true) or the base-map places, without those inside [installed] cells (region only). */
    fun entriesWithout(installed: Set<Int>, pois: Boolean): List<OfflineIndex.Entry> {
        val range = if (pois) 0 until poiCount else poiCount until entries.size
        val cells = entryCells
        if (cells == null || installed.isEmpty()) return entries.subList(range.first, range.last + 1)
        return range.filter { cells[it] !in installed }.map { entries[it] }
    }

    fun trailsWithout(installed: Set<Int>): List<TrailPart> =
        if (trailCells == null || installed.isEmpty()) trails else trails.filterIndexed { i, _ -> trailCells[i] !in installed }

    companion object {
        fun pack(c: CellId): Int = c.x * CellId.N + c.y
        fun pack(lat: Double, lon: Double): Int = CellId.at(lat, lon)?.let { pack(it) } ?: -1
    }
}

/**
 * The offline search index as the union of per-area parts (a full rebuild after every map store generation takes
 * close to a minute). Each area's names are extracted ONCE (from the joined map files, only that area's
 * tiles / points) into a small file in [dir] (`<key>@<version hash>.tsv`); a new generation only extracts the added
 * or changed areas and drops the removed ones – the others are reused from memory (or read from their file after an
 * app start). Old installs migrate lazily: the first build writes the files. Thread-safe (one build at a time).
 */
class SearchAreas(val dir: File, private val legacyCache: File? = null) {

    private var loaded: Map<String, SearchArea> = emptyMap()

    /** What the last [build] did (for the log and tests). */
    data class Stats(val added: Int, val removed: Int, val extracted: Int, val fromFiles: Int, val entries: Int, val ms: Long)

    @Volatile var lastStats: Stats? = null
        private set

    /** The last index built (kept for the whole process: re-opening the search is instant). */
    @Volatile var latest: OfflineIndex? = null
        private set

    /** Builds the index for [plan]: reuses unchanged areas, reads / extracts the others. Never throws. Off the main thread. */
    @Synchronized
    fun build(plan: AreaPlan): OfflineIndex {
        val t0 = System.currentTimeMillis()
        val prev = loaded
        val need = plan.areas.filter { prev[it.key]?.version != it.version }
        val fresh = HashMap<String, SearchArea>()
        var fromFiles = 0
        val missing = ArrayList<AreaSpec>()
        for (s in need) { val a = readFile(s); if (a != null) { fresh[s.key] = a; fromFiles++ } else missing += s }
        for (s in missing.filter { it.isRegion }) extractRegion(s).forEach { fresh[it.key] = it }
        for ((d, specs) in missing.filter { !it.isRegion }.groupBy { it.dir }) extractCells(d, specs).forEach { fresh[it.key] = it }
        val next = LinkedHashMap<String, SearchArea>()
        for (s in plan.areas) (fresh[s.key] ?: prev[s.key]?.takeIf { it.version == s.version })?.let { next[s.key] = it }
        val removed = prev.count { (k, a) -> next[k]?.version != a.version }
        loaded = next
        cleanUp(plan)
        val index = union(next.values, plan)
        val stats = Stats(need.size, removed, missing.size, fromFiles, index.entries.size, System.currentTimeMillis() - t0)
        lastStats = stats
        latest = index
        Problems.trace("search", "Search index: +${areas(stats.added)} −${areas(stats.removed)} in ${stats.ms} ms " +
            "(${stats.entries} entries; ${stats.extracted} extracted, ${stats.fromFiles} from files)")
        return index
    }

    private fun areas(n: Int) = if (n == 1) "1 area" else "$n areas"

    // ------------------------------------------------------------------------------------------- union

    private fun union(areas: Collection<SearchArea>, plan: AreaPlan): OfflineIndex {
        val installed = plan.installed.map { SearchArea.pack(it) }.toHashSet()
        val entries = ArrayList<OfflineIndex.Entry>(areas.sumOf { it.entries.size } + 64)
        val trails = ArrayList<TrailPart>()
        // same order as [OfflineIndex.load] (POIs, trails, base-map places): equal scores keep their order
        for (a in areas) { entries += a.entriesWithout(installed, pois = true); trails += a.trailsWithout(installed) }
        // trail pieces of one name merge across areas (as in the joined map); a few thousand – cheap
        OfflineIndex.mergeTrailParts(trails).forEach { entries += OfflineIndex.Entry(it) }
        for (a in areas) entries += a.entriesWithout(installed, pois = false)
        return OfflineIndex(entries, plan.signature)
    }

    // ------------------------------------------------------------------------------------------- extraction

    /** What one area holds before it becomes [SearchArea] entries. */
    class Content(val pois: List<Place>, val places: List<BaseMapPlaces.Item>, val trails: List<TrailPart>)

    private fun extractRegion(s: AreaSpec): List<SearchArea> {
        var complete = true
        fun <T> safe(what: String, block: () -> T?): T? = try { block() } catch (e: Throwable) {
            complete = false; Problems.warn("search", "Search index: $what skipped (file unreadable)", e); null
        }
        val d = s.dir
        val pois = safe("POIs") { OfflineIndex.readPois(File(d, OfflineIndex.POIS)) }.orEmpty()
        val trails = safe("trails") {
            val geojson = File(d, OfflineIndex.TRAILS)
            if (geojson.isFile) OfflineIndex.readTrailPartsGrouped(geojson) { _, _ -> Unit }[Unit].orEmpty()
            else BaseMapPlaces.readTrailNames(File(d, OfflineIndex.OVERLAYS)).map { TrailPart(it.name, it.lat, it.lon, it.len) }
        }.orEmpty()
        val places = safe("base map") { BaseMapPlaces.read(File(d, OfflineIndex.BASE)) }.orEmpty()
        return listOf(store(s, Content(pois, places, trails), complete))
    }

    /** All [specs] (cells of one store generation [d]) in ONE pass over each file; only their tiles are decoded. */
    private fun extractCells(d: File, specs: List<AreaSpec>): List<SearchArea> {
        var complete = true
        fun <T> safe(what: String, block: () -> T?): T? = try { block() } catch (e: Throwable) {
            complete = false; Problems.warn("search", "Search index: $what of downloaded map areas skipped (file unreadable)", e); null
        }
        val wanted = specs.mapNotNull { it.cell }.toHashSet()
        val tile = { z: Int, x: Int, y: Int -> CellId.ofTile(z, x, y)?.takeIf { it in wanted } }
        val point = { lat: Double, lon: Double -> CellId.at(lat, lon)?.takeIf { it in wanted } }
        val pois = safe("POIs") { OfflineIndex.readPoisGrouped(File(d, OfflineIndex.POIS), point) }.orEmpty()
        val trails = safe("trails") {
            val geojson = File(d, OfflineIndex.TRAILS)
            if (geojson.isFile) OfflineIndex.readTrailPartsGrouped(geojson, point)
            else BaseMapPlaces.readTrailNamesGrouped(File(d, OfflineIndex.OVERLAYS), tile)
                .mapValues { (_, l) -> l.map { TrailPart(it.name, it.lat, it.lon, it.len) } }
        }.orEmpty()
        val places = safe("base map") { BaseMapPlaces.readGrouped(File(d, OfflineIndex.BASE), tile) }.orEmpty()
        return specs.map { s ->
            val c = s.cell!!
            store(s, Content(pois[c].orEmpty(), places[c].orEmpty(), trails[c].orEmpty()), complete)
        }
    }

    /** Writes the area's file (only when every source file was readable – else it is extracted again next time). */
    private fun store(s: AreaSpec, c: Content, complete: Boolean): SearchArea {
        if (complete) try { write(fileOf(s), s, c) } catch (e: Exception) {
            Problems.warn("search", "Search index file of ${s.key} not written; extracted again on the next start", e)
        }
        return areaOf(s, c)
    }

    private fun areaOf(s: AreaSpec, c: Content) = SearchArea(s.key, s.version, s.isRegion,
        c.pois.map { OfflineIndex.Entry(it) } + c.places.map { OfflineIndex.Entry(OfflineIndex.placeOf(it)) }, c.pois.size, c.trails)

    // ------------------------------------------------------------------------------------------- files

    fun fileOf(s: AreaSpec) = File(dir, "${s.key.replace(Regex("[^A-Za-z0-9_-]"), "_")}@${hash(s.version)}.tsv")

    private fun readFile(s: AreaSpec): SearchArea? {
        val f = fileOf(s)
        if (!f.isFile) return null
        return try { read(f, s)?.let { areaOf(s, it) } } catch (e: Exception) {
            Problems.warn("search", "Search index file of ${s.key} unreadable; extracted again", e); null
        }
    }

    /** Deletes files of areas no longer in [plan] (and the single cache file of older versions). */
    private fun cleanUp(plan: AreaPlan) {
        val keep = plan.areas.map { fileOf(it).name }.toSet()
        dir.listFiles().orEmpty().forEach { f ->
            if (f.name !in keep && !f.delete() && f.exists()) Problems.warn("search", "Old search index file ${f.name} could not be deleted")
        }
        legacyCache?.takeIf { it.exists() }?.let { if (!it.delete()) Problems.warn("search", "Old search place cache could not be deleted") }
    }

    companion object {
        private const val MAGIC = "onpath-search-area 1"

        private fun hash(v: String) = MessageDigest.getInstance("SHA-1").digest(v.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

        private fun clean(s: String?) = s?.replace('\t', ' ')?.replace('\n', ' ')?.replace('\r', ' ') ?: ""
        private fun opt(s: String) = s.ifEmpty { null }

        /** Compact TSV: header, one line per POI (P), base-map place (M), trail piece (T), end line with the count. */
        fun write(f: File, s: AreaSpec, c: Content) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, ".${f.name}.tmp")
            var n = 0
            tmp.bufferedWriter().use { w ->
                w.write("$MAGIC\t${clean(s.key)}\t${clean(s.version)}\n")
                for (p in c.pois) {
                    val o = p.poi ?: p.toPoi()
                    w.write(listOf("P", o.id, o.kind, clean(o.name), o.lat.toString(), o.lon.toString(), o.ele?.toString() ?: "",
                        clean(o.openingHours), clean(o.phone), clean(o.website), clean(o.wikipedia), clean(o.cuisine)).joinToString("\t", transform = ::clean))
                    w.write("\n"); n++
                }
                for (i in c.places) { w.write("M\t"); w.write(OfflineIndex.cacheLine(i)); w.write("\n"); n++ }
                for (t in c.trails) { w.write("T\t${clean(t.name)}\t${t.lat}\t${t.lon}\t${t.len}\n"); n++ }
                w.write("END\t$n\n")
            }
            if (!tmp.renameTo(f)) { tmp.delete(); throw java.io.IOException("rename failed: ${f.name}") }
        }

        /** The file's content, or null when it belongs to another area / version. Throws when it is incomplete. */
        fun read(f: File, s: AreaSpec): Content? {
            val pois = ArrayList<Place>(); val places = ArrayList<BaseMapPlaces.Item>(); val trails = ArrayList<TrailPart>()
            var n = 0; var end = -1
            f.bufferedReader().useLines { lines ->
                val it = lines.iterator()
                if (!it.hasNext() || it.next() != "$MAGIC\t${clean(s.key)}\t${clean(s.version)}") return null
                for (l in it) {
                    val p = l.split('\t')
                    when (p[0]) {
                        "P" -> {
                            if (p.size < 12) continue
                            val poi = com.onpath.app.poi.Poi(p[1], p[2], opt(p[3]), p[4].toDoubleOrNull() ?: continue, p[5].toDoubleOrNull() ?: continue,
                                p[6].toIntOrNull(), opt(p[7]), opt(p[8]), opt(p[9]), opt(p[10]), opt(p[11]))
                            pois += Place.fromPoi(poi); n++
                        }
                        "M" -> { OfflineIndex.parseCacheLine(l.substring(2))?.let { places += it; n++ } }
                        "T" -> {
                            if (p.size < 5) continue
                            trails += TrailPart(p[1], p[2].toDoubleOrNull() ?: continue, p[3].toDoubleOrNull() ?: continue, p[4].toDoubleOrNull() ?: 0.0); n++
                        }
                        "END" -> end = p.getOrNull(1)?.toIntOrNull() ?: -1
                    }
                }
            }
            // cut short or damaged lines (should not happen: written via rename) → the caller reports it and extracts again
            if (end != n) throw java.io.IOException("search area file ${f.name} incomplete ($n of $end lines)")
            return Content(pois, places, trails)
        }
    }
}
