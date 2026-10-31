package com.onpath.app.search

import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellStore
import com.onpath.app.data.AtomicFiles
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

/** Owner cells of points, packed into one Int (region areas leave out what an installed cell owns). */
object SearchArea {
    fun pack(c: CellId): Int = c.x * CellId.N + c.y
    fun pack(lat: Double, lon: Double): Int = CellId.at(lat, lon)?.let { pack(it) } ?: -1
}

/**
 * The offline search index as the union of per-area parts, kept in ONE SQLite file ([FILE] in [dir]; a full rebuild
 * after every map store generation takes close to a minute). Each area's names are extracted ONCE (from the joined map
 * files, only that area's tiles / points) into the file; a new generation only extracts the added or changed areas and
 * drops the removed ones. After an app start nothing is read into memory until a search runs ([DiskIndex]). Old
 * installs migrate: the per-area files of 0.12 (`<key>@<version hash>.tsv` in [dir]) are read instead of the map
 * files, then deleted. A damaged index file is deleted and built again (reported, never a crash). Thread-safe (one
 * build at a time; searches on the previous index go on meanwhile).
 */
class SearchAreas(
    val dir: File,
    private val legacyCache: File? = null,
    /** Opens (creates) the index file; JDBC in unit tests. */
    private val opener: (File) -> SearchDb = { AndroidSearchDb(it) },
) {
    val file: File get() = File(dir, FILE)

    @Volatile private var db: SearchDb? = null

    /** Areas (key → version) this process knows are in the file. */
    private var loaded: Map<String, String> = emptyMap()

    /** What the last [build] did (for the log and tests). [fromFiles]: areas already on disk (index or 0.12 file). */
    data class Stats(val added: Int, val removed: Int, val extracted: Int, val fromFiles: Int, val entries: Int, val ms: Long)

    @Volatile var lastStats: Stats? = null
        private set

    /** The last index built (kept for the whole process: re-opening the search is instant). */
    @Volatile var latest: PlaceIndex? = null
        private set

    /** Builds the index for [plan]: reuses unchanged areas, reads / extracts the others. Never throws. Off the main thread. */
    @Synchronized
    fun build(plan: AreaPlan): PlaceIndex {
        val t0 = System.currentTimeMillis()
        return try { buildOnce(plan, t0) } catch (e: Exception) {
            Problems.warn("search", "Offline search index damaged; built again from the map files", e)
            reset()
            try { buildOnce(plan, t0) } catch (e2: Exception) {
                Problems.error("search", "Offline search index could not be built; offline search finds no places", e2)
                reset()
                OfflineIndex(emptyList(), "")
            }
        }
    }

    private fun buildOnce(plan: AreaPlan, t0: Long): PlaceIndex {
        val d = open()
        val stored = HashMap<String, Pair<String, Boolean>>()
        d.query("SELECT key, version, complete FROM area") { r -> stored[r.str(0)!!] = r.str(1)!! to (r.long(2) != 0L) }
        val prev = loaded
        val need = plan.areas.filter { prev[it.key] != it.version }
        var fromFiles = 0
        val missing = ArrayList<AreaSpec>()
        for (s in need) {
            val st = stored[s.key]
            if (st != null && st.first == s.version && st.second) { fromFiles++; continue }
            val legacy = readLegacy(s)
            if (legacy != null) { write(d, s, legacy, complete = true); fromFiles++ } else missing += s
        }
        for (s in missing.filter { it.isRegion }) extractRegion(d, s)
        for ((dd, specs) in missing.filter { !it.isRegion }.groupBy { it.dir }) extractCells(d, dd, specs)
        val keep = plan.areas.map { it.key }.toSet()
        for (k in stored.keys - keep) d.transaction {
            d.exec("DELETE FROM entry WHERE area = ?", k); d.exec("DELETE FROM area WHERE key = ?", k)
        }
        val next = plan.areas.associate { it.key to it.version }
        val removed = prev.count { (k, v) -> next[k] != v }
        loaded = next
        cleanUp()
        val index = index(d, plan)
        val stats = Stats(need.size, removed, missing.size, fromFiles, index.size, System.currentTimeMillis() - t0)
        lastStats = stats
        latest = index
        entryCount = index.size
        Problems.trace("search", "Search index: +${areas(stats.added)} −${areas(stats.removed)} in ${stats.ms} ms " +
            "(${stats.entries} entries; ${stats.extracted} extracted, ${stats.fromFiles} from files)")
        return index
    }

    private fun areas(n: Int) = if (n == 1) "1 area" else "$n areas"

    // ------------------------------------------------------------------------------------------- the file

    /** The open index file (created with its tables when new; another schema = built again). */
    private fun open(): SearchDb {
        db?.let { return it }
        dir.mkdirs()
        var d = opener(file)
        try {
            d.exec("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)")
            var v: String? = null
            d.query("SELECT v FROM meta WHERE k = 'schema'") { v = it.str(0) }
            if (v != null && v != SCHEMA) {
                Problems.info("search", "Offline search index of another app version; built again")
                d.close(); deleteFiles(); d = opener(file)
                d.exec("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)")
                v = null
            }
            if (v == null) d.transaction {
                d.exec("CREATE TABLE IF NOT EXISTS area(key TEXT PRIMARY KEY, version TEXT NOT NULL, complete INTEGER NOT NULL)")
                // AUTOINCREMENT: a row id is never used twice, so an older index still answering during a rebuild never
                // reads another place under an id it knows
                d.exec("CREATE TABLE IF NOT EXISTS entry(rid INTEGER PRIMARY KEY AUTOINCREMENT, area TEXT NOT NULL, " +
                    "part INTEGER NOT NULL, cell INTEGER NOT NULL, pid TEXT, kind TEXT NOT NULL, sub TEXT, name TEXT, alt TEXT, " +
                    "lat REAL NOT NULL, lon REAL NOT NULL, ele INTEGER, len REAL, oh TEXT, phone TEXT, web TEXT, wiki TEXT, " +
                    "cuisine TEXT, full TEXT)")
                d.exec("CREATE INDEX IF NOT EXISTS entry_area ON entry(area, part)")
                d.exec("CREATE INDEX IF NOT EXISTS entry_pid ON entry(pid)")
                d.exec("CREATE INDEX IF NOT EXISTS entry_lat ON entry(lat)")
                d.exec("INSERT OR REPLACE INTO meta(k, v) VALUES ('schema', ?)", SCHEMA)
            }
        } catch (e: Exception) {
            try { d.close() } catch (c: Exception) { Problems.info("search", "Search index file not closed after an error", c) }
            throw e
        }
        db = d
        return d
    }

    /** Closes and deletes the index file; the next build starts from the map files. */
    private fun reset() {
        try { db?.close() } catch (e: Exception) { Problems.info("search", "Damaged search index not closed cleanly", e) }
        db = null
        loaded = emptyMap()
        latest = null
        deleteFiles()
    }

    private fun deleteFiles() {
        SearchDb.files(file).forEach { f -> if (f.exists() && !f.delete()) Problems.warn("search", "Search index file ${f.name} could not be deleted") }
    }

    /**
     * One area's content into the file, replacing what it held before (one transaction). Not [complete] (a source
     * file was unreadable): used now, extracted again after the next app start.
     */
    private fun write(d: SearchDb, s: AreaSpec, c: Content, complete: Boolean) = d.transaction {
        d.exec("DELETE FROM entry WHERE area = ?", s.key)
        d.exec("DELETE FROM area WHERE key = ?", s.key)
        d.prepare("INSERT INTO entry(area, part, cell, pid, kind, sub, name, alt, lat, lon, ele, len, oh, phone, web, wiki, cuisine, full) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use { ins ->
            for (p in c.pois) {
                val o = p.poi ?: p.toPoi()
                ins.run(s.key, DiskIndex.PART_POI, SearchArea.pack(o.lat, o.lon), o.id, o.kind, null, o.name, null, o.lat, o.lon, o.ele,
                    null, o.openingHours, o.phone, o.website, o.wikipedia, o.cuisine, DiskIndex.fullOf(o.name, emptyList()))
            }
            for (i in c.places) {
                val p = OfflineIndex.placeOf(i)
                ins.run(s.key, DiskIndex.PART_PLACE, SearchArea.pack(p.lat, p.lon), p.id, p.kind, p.sub, p.name,
                    p.alt.joinToString(DiskIndex.ALT_SEP.toString()), p.lat, p.lon, p.ele, null, null, null, null, null, null,
                    DiskIndex.fullOf(p.name, p.alt))
            }
            for (t in c.trails) {
                ins.run(s.key, DiskIndex.PART_TRAIL, SearchArea.pack(t.lat, t.lon), null, "trail", null, t.name, null, t.lat, t.lon,
                    null, t.len, null, null, null, null, null, DiskIndex.fullOf(t.name, emptyList()))
            }
        }
        d.exec("INSERT INTO area(key, version, complete) VALUES (?, ?, ?)", s.key, s.version, if (complete) 1 else 0)
    }

    /** The index over the plan's areas: trail pieces merged across areas (as in the joined map), entries counted. */
    private fun index(d: SearchDb, plan: AreaPlan): DiskIndex {
        val installed = plan.installed.map { SearchArea.pack(it) }.toHashSet()
        val regions = plan.areas.filter { it.isRegion }.map { it.key }.toSet()
        val keys = plan.areas.map { it.key }
        val parts = ArrayList<TrailPart>()
        val rids = ArrayList<Int>()
        var count = 0
        for (k in keys) {
            val region = k in regions
            d.query("SELECT rid, name, lat, lon, len, cell FROM entry WHERE area = ? AND part = ${DiskIndex.PART_TRAIL} ORDER BY rid", k) { r ->
                if (region && r.long(5).toInt() in installed) return@query
                parts += TrailPart(r.str(1)!!, r.double(2), r.double(3), r.double(4)); rids += r.long(0).toInt()
            }
            d.query("SELECT cell, COUNT(*) FROM entry WHERE area = ? AND part IN (${DiskIndex.PART_POI}, ${DiskIndex.PART_PLACE}) GROUP BY cell", k) { r ->
                if (!(region && r.long(0).toInt() in installed)) count += r.long(1).toInt()
            }
        }
        val kept = OfflineIndex.mergeTrailIndices(parts).map { rids[it] }.toIntArray()
        return DiskIndex({ db ?: throw java.io.IOException("search index closed") }, keys, regions, installed, kept,
            count + kept.size, plan.signature)
    }

    // ------------------------------------------------------------------------------------------- extraction

    /** What one area holds before it goes into the index. */
    class Content(val pois: List<Place>, val places: List<BaseMapPlaces.Item>, val trails: List<TrailPart>)

    private fun extractRegion(db: SearchDb, s: AreaSpec) {
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
        write(db, s, Content(pois, places, trails), complete)
    }

    /** All [specs] (cells of one store generation [d]) in ONE pass over each file; only their tiles are decoded. */
    private fun extractCells(db: SearchDb, d: File, specs: List<AreaSpec>) {
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
        for (s in specs) {
            val c = s.cell!!
            write(db, s, Content(pois[c].orEmpty(), places[c].orEmpty(), trails[c].orEmpty()), complete)
        }
    }

    // ------------------------------------------------------------------------------------------- 0.12 files

    /** The 0.12 per-area file name (`<key>@<version hash>.tsv`). */
    fun fileOf(s: AreaSpec) = File(dir, "${s.key.replace(Regex("[^A-Za-z0-9_-]"), "_")}@${hash(s.version)}.tsv")

    /** The area's content from its 0.12 file, or null (none, another version, damaged – then it is extracted). */
    private fun readLegacy(s: AreaSpec): Content? {
        val f = fileOf(s)
        if (!f.isFile) return null
        return try { read(f, s) } catch (e: Exception) {
            Problems.warn("search", "Search index file of ${s.key} unreadable; extracted again", e); null
        }
    }

    /** Deletes the 0.12 per-area files (and the single cache file of older versions): everything but the index. */
    private fun cleanUp() {
        val keep = SearchDb.files(file).map { it.name }.toSet()
        dir.listFiles().orEmpty().forEach { f ->
            if (f.name !in keep && !f.delete() && f.exists()) Problems.warn("search", "Old search index file ${f.name} could not be deleted")
        }
        legacyCache?.takeIf { it.exists() }?.let { if (!it.delete()) Problems.warn("search", "Old search place cache could not be deleted") }
    }

    companion object {
        private const val MAGIC = "onpath-search-area 1"

        /** The index file in [dir]; another [SCHEMA] = built again. */
        const val FILE = "places.sqlite"
        const val SCHEMA = "1"

        /** Entries of the last index built in this process (0 = none yet) – cheap, for the health line. */
        @Volatile var entryCount: Int = 0
            private set

        private fun hash(v: String) = MessageDigest.getInstance("SHA-1").digest(v.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

        private fun clean(s: String?) = s?.replace('\t', ' ')?.replace('\n', ' ')?.replace('\r', ' ') ?: ""
        private fun opt(s: String) = s.ifEmpty { null }

        /** 0.12 per-area file (now only read, for the migration – and written by tests). Compact TSV: header, one line per POI (P), base-map place (M), trail piece (T), end line with the count. */
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
            try { AtomicFiles.replace(tmp, f) } catch (e: java.io.IOException) { tmp.delete(); throw e } // a cache: rebuilt next time
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
