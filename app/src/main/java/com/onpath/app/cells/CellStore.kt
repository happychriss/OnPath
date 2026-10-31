package com.onpath.app.cells

import com.onpath.app.data.AtomicFiles
import com.onpath.strings.*
import com.onpath.app.debug.Problems
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The local cell store (docs/design/map-cells.md §19): all installed cells – plus a format-2 region package, if one is
 * in the data folder – joined into ONE set of map files that MapLibre draws with the unchanged format-2 style:
 *
 * ```
 * <dataDir>/cells/store/g000007/   base.pmtiles overlays.pmtiles terrain.pmtiles contours.pmtiles pois.geojson pois.pmtiles store.json
 * <dataDir>/cells/download/10-543-361/2026-10-19.part/   while downloading (never used)
 * <dataDir>/cells/download/10-543-361/2026-10-19/        complete + verified, waiting to be joined
 * <dataDir>/addresses/10-543-361.sqlite                  the cell's addresses (search reads every file there)
 * ```
 *
 * Every change (add, replace, delete a cell, new region package) writes a new **generation** `gNNNNNN.tmp`, checks
 * every written file, then renames the folder to `gNNNNNN` – one atomic step. The highest complete generation is the
 * current one; a crash at any point leaves the previous generation untouched (R-16). New generation = new file paths,
 * so MapLibre (which keeps a PMTiles index per URL) sees the change after a style reload, without an app restart.
 * The previous generation is deleted once the map loaded the new one ([confirmLoaded]), or rolled back to
 * ([rollback]) when it does not load.
 *
 * Ownership decides where every tile / POI comes from: a tile at zoom ≥ 10 inside an installed cell, and a POI inside
 * it (half-open rule), come only from that cell; everything else from the region package. So cells override the
 * region package where both exist, and the region package fills the rest (and z0–9).
 * Region packages (z7 tiles, [CellId.isPackage], map-cells.md §20) are joined the same way: a package owns the tiles
 * z7–9 under it (base, overlays "routes", hillshade). With any package installed, the region package's tiles below z10
 * are left out – the map then draws z7–9 from the detailed sources (map/Overview.kt), and a region package outside the
 * packages would double the overview there.
 * Not thread-safe by itself: [MapCells] calls it from one worker; methods are synchronized as a guard.
 */
class CellStore(
    /** The data folder (/sdcard/Android/data/com.onpath.app/files/onpath). */
    val dataDir: File,
    /** Free bytes on the data folder's volume (replaced in tests). */
    private val freeSpace: (File) -> Long = { it.usableSpace },
) {
    val root = File(dataDir, "cells")
    val storeDir = File(root, "store")
    val downloadDir = File(root, "download")
    val addressesDir = File(dataDir, "addresses")

    data class InstalledCell(
        val id: CellId,
        val version: String,
        val built: String?,
        val format: Int,
        /** Bytes this cell holds in each store file ("base.pmtiles" → n). */
        val bytes: Map<String, Long>,
        val addressBytes: Long,
        val installedAt: Long,
        /** Profile hash of the cell's router classes (manifest "wayclass"); null = unknown (older store, no classes). */
        val wayclass: String? = null,
    ) {
        val totalBytes: Long get() = bytes.values.sum() + addressBytes
    }

    data class Generation(
        val number: Int,
        val dir: File,
        val cells: List<InstalledCell>,
        /** Region package files merged in (name → "size:mtime"), empty when none. */
        val region: Map<String, String>,
        val files: Map<String, Long>,
        val createdAt: Long,
        /** Building [PoiTiles.FILE] failed for this generation (no retry at start, [needsPoiTiles]). */
        val poiTilesFailed: Boolean = false,
    ) {
        val cellIds: Set<CellId> get() = cells.map { it.id }.toSet()
        fun cell(id: CellId) = cells.firstOrNull { it.id == id }
    }

    /** A complete, verified download waiting to be joined. */
    data class Download(val manifest: CellManifest, val dir: File)

    class StoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** Generation the loaded map style uses (set via [confirmLoaded]); never deleted. */
    @Volatile var inUse: Int? = null
        private set

    // ------------------------------------------------------------------------------------------- reading

    /** The current generation, null when none (no cells ever installed). Never throws. */
    @Synchronized fun current(): Generation? = generations().lastOrNull()

    /**
     * Folder the map, POI tap and search read from: the current generation when it holds at least one cell, else null
     * (then the data folder is used).
     */
    fun mapDir(): File? = current()?.takeIf { g -> g.cells.any { !it.id.isPackage } }?.dir

    fun installed(): List<InstalledCell> = current()?.cells.orEmpty()

    /**
     * Complete generations, oldest first; unreadable ones are skipped (and reported). Only `gNNNNNN` folders: a `.tmp`
     * may be half written by [apply] right now, a `.bad` was set aside.
     * [GEN] (with the suffixes) is only for numbering.
     */
    private fun generations(): List<Generation> =
        storeDir.listFiles { f -> f.isDirectory && GEN.matchEntire(f.name)?.groupValues?.get(2)?.isEmpty() == true }.orEmpty()
            .sortedBy { it.name }
            .mapNotNull { dir -> read(dir) }

    private fun read(dir: File): Generation? = try {
        val o = JSONObject(File(dir, STORE_JSON).readText())
        val cells = o.optJSONArray("cells") ?: JSONArray()
        Generation(
            o.getInt("generation"), dir,
            (0 until cells.length()).map { i ->
                val c = cells.getJSONObject(i)
                val b = c.optJSONObject("bytes") ?: JSONObject()
                InstalledCell(CellId.parse(c.getString("id")) ?: error("bad cell id"), c.getString("version"),
                    c.optString("built").ifBlank { null }, c.optInt("format", 2),
                    b.keys().asSequence().associateWith { b.getLong(it) }, c.optLong("addressBytes"), c.optLong("installedAt"),
                    c.optString("wayclass").ifBlank { null })
            },
            o.optJSONObject("region")?.let { r -> r.keys().asSequence().associateWith { r.getString(it) } }.orEmpty(),
            o.optJSONObject("files")?.let { r -> r.keys().asSequence().associateWith { r.getLong(it) } }.orEmpty(),
            o.optLong("createdAt"),
            o.optBoolean("poiTilesFailed"),
        )
    } catch (e: Exception) {
        Problems.error("cells", "Map store generation ${dir.name} unreadable; skipped (older one used)", e)
        null
    }

    // ------------------------------------------------------------------------------------------- region package

    /**
     * The region package files that can be joined with cells: only a format-2 package (overlays.pmtiles present),
     * name → "size:mtime". A format-1 package is not joined (GeoJSON overlays, other terrain encoding): once a cell is
     * installed the map shows cells only (format 1 is being phased out, map-cells.md Q9/Q11).
     */
    fun regionFiles(): Map<String, String> {
        if (!File(dataDir, "overlays.pmtiles").isFile) return emptyMap()
        return (LAYERS + POIS).mapNotNull { n -> File(dataDir, n).takeIf { it.isFile && it.length() > 0 }?.let { n to "${it.length()}:${it.lastModified()}" } }.toMap()
    }

    /** True when the current generation holds cells and the region package changed since it was written. */
    fun regionChanged(): Boolean = current()?.takeIf { it.cells.isNotEmpty() }?.let { it.region != regionFiles() } ?: false

    // ------------------------------------------------------------------------------------------- changing

    /**
     * Writes a new generation: the current cells minus [remove], plus / replaced by [add], joined with the region
     * package. Returns it (it is current from now on). Throws [StoreException] with a plain message (not enough space,
     * a broken file) – the previous generation then stays current and nothing changed.
     */
    @Synchronized
    fun apply(add: List<Download> = emptyList(), remove: Set<CellId> = emptySet(), now: Long = System.currentTimeMillis()): Generation {
        val t0 = System.currentTimeMillis()
        val cur = current()
        val addIds = add.map { it.manifest.cell }.toSet()
        val kept = cur?.cells.orEmpty().filter { it.id !in remove && it.id !in addIds }
        val keptIds = kept.map { it.id }.toSet()
        val allIds = keptIds + addIds
        val region = if (allIds.isEmpty()) emptyMap() else regionFiles()
        val hasPackages = allIds.any { it.isPackage }
        // above every folder number on disk (also unreadable ones), so the rename below never meets an old folder
        val number = maxOf(cur?.number ?: 0, storeDir.listFiles().orEmpty().maxOfOrNull { f ->
            GEN.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0 } ?: 0) + 1
        val tmp = File(storeDir, "%s%06d.tmp".format("g", number))
        val final = File(storeDir, "%s%06d".format("g", number))
        try {
            tmp.deleteRecursively()
            if (!tmp.mkdirs()) throw StoreException(Txt.s(Res.string.maps_err_store_not_writable),
                IOException("mkdirs failed: ${tmp.path}"))
            // space: the new generation is at most the sum of its inputs
            val need = LAYERS.sumOf { n ->
                (if (n in region) File(dataDir, n).length() else 0L) + (cur?.dir?.let { File(it, n).length() } ?: 0L) +
                    add.sumOf { File(it.dir, n).length() }
            } + (cur?.dir?.let { File(it, POIS).length() } ?: 0L) + add.sumOf { File(it.dir, POIS).length() } +
                (if (POIS in region) File(dataDir, POIS).length() else 0L) +
                // POI tiles: the previous ones + about the size of the new cells' GeoJSON
                (cur?.dir?.let { File(it, PoiTiles.FILE).length() } ?: 0L) + add.sumOf { File(it.dir, POIS).length() } + MARGIN
            val free = freeSpace(storeDir)
            if (free < need) throw StoreException(Txt.s(Res.string.maps_err_store_space, need / MB, free / MB))
            val bytes = HashMap<CellId, MutableMap<String, Long>>()
            val files = LinkedHashMap<String, Long>()
            for (n in LAYERS) {
                val inputs = ArrayList<PmTiles.Input>()
                val owners = ArrayList<CellId?>() // per input: the added cell it carries (null = region / previous store)
                if (n in region) {
                    inputs += PmTiles.Input(File(dataDir, n)) { z, x, y ->
                        if (hasPackages && z < CellId.Z) false else CellId.ofTile(z, x, y)?.let { it !in allIds } ?: true
                    }
                    owners += null
                }
                cur?.dir?.let { File(it, n) }?.takeIf { it.isFile && keptIds.isNotEmpty() }?.let { f ->
                    inputs += PmTiles.Input(f) { z, x, y -> CellId.ofTile(z, x, y)?.let { it in keptIds } ?: false }; owners += null
                }
                for (d in add) File(d.dir, n).takeIf { it.isFile }?.let { f ->
                    val c = d.manifest.cell
                    inputs += PmTiles.Input(f) { z, x, y -> c.ownsTile(z, x, y) }; owners += c
                }
                if (inputs.isEmpty()) continue
                val out = File(tmp, n)
                val bounds = boundsOf(allIds, inputs.zip(owners).filter { it.second == null && it.first.file.parentFile == dataDir }.map { it.first.file })
                val r = try { PmTiles.merge(inputs, out, bounds, mergedMetadata(inputs.map { it.file })) }
                catch (e: PmTiles.IncompatibleException) { throw StoreException(Txt.s(Res.string.maps_err_store_incompatible), IOException(n, e)) }
                if (r == null) continue
                files[n] = PmTiles.verify(out).let { out.length() }
                r.bytesPerInput.forEachIndexed { i, b -> owners[i]?.let { c -> bytes.getOrPut(c) { HashMap() }[n] = b } }
            }
            // POIs: same ownership, by point
            run {
                val inputs = ArrayList<GeoJsonMerge.Input>(); val owners = ArrayList<CellId?>()
                if (POIS in region) { inputs += GeoJsonMerge.Input(File(dataDir, POIS)) { lat, lon -> CellId.at(lat, lon)?.let { it !in allIds } ?: true }; owners += null }
                cur?.dir?.let { File(it, POIS) }?.takeIf { it.isFile && keptIds.isNotEmpty() }?.let { f ->
                    inputs += GeoJsonMerge.Input(f) { lat, lon -> CellId.at(lat, lon)?.let { it in keptIds } ?: false }; owners += null
                }
                for (d in add) File(d.dir, POIS).takeIf { it.isFile }?.let { f ->
                    val c = d.manifest.cell
                    inputs += GeoJsonMerge.Input(f) { lat, lon -> CellId.at(lat, lon) == c }; owners += c
                }
                if (inputs.isNotEmpty()) {
                    val out = File(tmp, POIS)
                    val r = GeoJsonMerge.merge(inputs, out)
                    if (r.skipped > 0) Problems.warn("cells", "${r.skipped} points of interest without coordinates left out of the map")
                    files[POIS] = out.length()
                    r.bytesPerInput.forEachIndexed { i, b -> owners[i]?.let { c -> bytes.getOrPut(c) { HashMap() }[POIS] = b } }
                }
            }
            // POIs as vector tiles (PoiTiles): kept cells' tiles copied from the previous generation, the rest encoded
            // here. Robust first: a failure leaves the map with the GeoJSON POIs (style fallback), never without a map.
            val poiTilesFailed = try {
                poiTiles(tmp, cur, region, keptIds, allIds, add)?.let { (size, per) ->
                    files[PoiTiles.FILE] = size
                    per.forEach { (c, b) -> bytes.getOrPut(c) { HashMap() }[PoiTiles.FILE] = b }
                }
                false
            } catch (e: Exception) {
                File(tmp, PoiTiles.FILE).delete()
                Problems.error("cells", "Map POIs could not be prepared as tiles; drawn the old way (more memory)", e)
                true
            }
            val cells = kept + add.map { d ->
                InstalledCell(d.manifest.cell, d.manifest.version, d.manifest.built, d.manifest.format,
                    bytes[d.manifest.cell].orEmpty(), addressFileOf(d)?.length() ?: 0L, now, d.manifest.wayclass)
            }.sortedBy { it.id }
            val gen = Generation(number, final, cells.sortedBy { it.id }, region, files, now, poiTilesFailed)
            File(tmp, STORE_JSON).writeText(toJson(gen).toString(1))
            if (!tmp.renameTo(final)) throw StoreException(Txt.s(Res.string.maps_err_store_failed), IOException("rename ${tmp.name} failed"))
            // the cells' addresses go where the search reads them; only after the generation is active
            for (d in add) installAddresses(d)
            for (c in remove - addIds) deleteAddresses(c)
            prune(keep = setOfNotNull(number, cur?.number, inUse))
            // the cost of each generation in the event log (a whole region is rewritten per generation)
            Problems.trace("cells", "Generation ${final.name}: +${add.size} −${(remove - addIds).size} cells (${cells.size} total), " +
                "${files.values.sum() / MB} MB written in ${System.currentTimeMillis() - t0} ms (POIs ${(files[POIS] ?: 0L) / MB} MB)")
            return gen
        } catch (e: StoreException) {
            tmp.deleteRecursively(); throw e
        } catch (e: Exception) {
            tmp.deleteRecursively()
            val low = freeSpace(storeDir) < MARGIN
            throw StoreException(if (low) Txt.s(Res.string.maps_err_store_low) else Txt.s(Res.string.maps_err_store_failed), e)
        }
    }

    /**
     * True when the current generation has its POIs only as GeoJSON (written by an app before [PoiTiles]) and building
     * the tiles did not fail for it before: [MapCells] then writes a new generation at start.
     */
    fun needsPoiTiles(): Boolean = current()?.let { g ->
        g.cells.isNotEmpty() && !g.poiTilesFailed && File(g.dir, POIS).isFile && !File(g.dir, PoiTiles.FILE).isFile
    } ?: false

    /**
     * Writes [tmp]/pois.pmtiles. Inputs like the tile layers: the region package's POIs outside every cell, the kept
     * cells' tiles of the previous generation (its GeoJSON when it has no tiles yet), each added cell's GeoJSON.
     * Returns (file size, bytes per added cell), null when there are no POIs.
     */
    private fun poiTiles(tmp: File, cur: Generation?, region: Map<String, String>, keptIds: Set<CellId>, allIds: Set<CellId>,
                         add: List<Download>): Pair<Long, Map<CellId, Long>>? {
        val work = File(tmp, ".poi-work")
        val inputs = ArrayList<PmTiles.Input>()
        val owners = ArrayList<CellId?>()
        val parts = ArrayList<File>()
        fun encode(src: File, keep: (Double, Double) -> Boolean): File? {
            val f = File(tmp, ".poi-part-${parts.size}.pmtiles").also { parts += it }
            return PoiTiles.build(listOf(PoiTiles.Input(src, keep)), f, work)?.let { f }
        }
        try {
            if (POIS in region) encode(File(dataDir, POIS)) { lat, lon -> CellId.at(lat, lon)?.let { it !in allIds } ?: false }
                ?.let { inputs += PmTiles.Input(it) { _, _, _ -> true }; owners += null }
            val prev = cur?.dir?.takeIf { keptIds.isNotEmpty() }
            val prevTiles = prev?.let { File(it, PoiTiles.FILE) }?.takeIf { it.isFile }
            if (prevTiles != null) {
                inputs += PmTiles.Input(prevTiles) { z, x, y -> CellId.ofTile(z, x, y)?.let { it in keptIds } ?: false }; owners += null
            } else prev?.let { File(it, POIS) }?.takeIf { it.isFile }?.let { f ->
                encode(f) { lat, lon -> CellId.at(lat, lon)?.let { it in keptIds } ?: false }
                    ?.let { inputs += PmTiles.Input(it) { _, _, _ -> true }; owners += null }
            }
            for (d in add) File(d.dir, POIS).takeIf { it.isFile }?.let { f ->
                val c = d.manifest.cell
                encode(f) { lat, lon -> CellId.at(lat, lon) == c }
                    ?.let { inputs += PmTiles.Input(it) { z, x, y -> c.ownsTile(z, x, y) }; owners += c }
            }
            if (inputs.isEmpty()) return null
            val out = File(tmp, PoiTiles.FILE)
            val r = PmTiles.merge(inputs, out, metadataJson = PoiTiles.METADATA) ?: return null
            val size = PmTiles.verify(out).let { out.length() }
            val per = HashMap<CellId, Long>()
            r.bytesPerInput.forEachIndexed { i, b -> owners[i]?.let { per[it] = b } }
            return size to per
        } finally {
            work.deleteRecursively()
            parts.forEach { it.delete() }
        }
    }

    /** Removes cells (their tiles, POIs and address files). */
    fun remove(ids: Set<CellId>): Generation = apply(remove = ids)

    /** The map loaded generation [number] fine: older generations are not needed any more. */
    @Synchronized fun confirmLoaded(number: Int) {
        inUse = number
        val cur = current()?.number ?: return
        prune(keep = setOfNotNull(number, cur))
    }

    /**
     * The map could not load generation [number] (R-16): it is set aside and the previous generation becomes current
     * again. Returns true when there was one to go back to (then reload the style).
     */
    @Synchronized fun rollback(number: Int): Boolean {
        val gens = generations()
        val bad = gens.lastOrNull()?.takeIf { it.number == number } ?: return false
        if (gens.size < 2) return false
        val aside = File(storeDir, bad.dir.name + ".bad")
        aside.deleteRecursively()
        if (!bad.dir.renameTo(aside)) { Problems.error("cells", "Broken map store ${bad.dir.name} could not be set aside"); return false }
        Problems.error("cells", "Map store ${bad.dir.name} did not load; went back to the previous one " +
            "(cells ${bad.cellIds - gens[gens.size - 2].cellIds} must be loaded again)")
        aside.deleteRecursively()
        return true
    }

    /** Deletes unfinished generations (crash leftovers) and generations not in [keep]. */
    @Synchronized fun prune(keep: Set<Int>) {
        storeDir.listFiles().orEmpty().forEach { f ->
            val n = GEN.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()
            val stale = f.name.endsWith(".tmp") || f.name.endsWith(".bad") || (n != null && n !in keep)
            if (stale && !f.deleteRecursively()) Problems.warn("cells", "Old map store ${f.name} could not be deleted (storage stays used)")
        }
    }

    /** Startup clean-up: unfinished generations go; every complete one stays until the map confirms one. */
    @Synchronized fun cleanTemp() {
        storeDir.listFiles { f -> f.name.endsWith(".tmp") || f.name.endsWith(".bad") }.orEmpty().forEach { it.deleteRecursively() }
    }

    // ------------------------------------------------------------------------------------------- downloads on disk

    /** Folder of a complete download of [m] (exists only after every file was verified). */
    fun completeDir(m: CellManifest) = File(File(downloadDir, m.cell.id), m.version)
    /** Folder a download of [m] is written to (never read by the map). */
    fun partDir(m: CellManifest) = File(File(downloadDir, m.cell.id), m.version + ".part")

    /** Complete downloads on disk (e.g. after a crash before they were joined). */
    fun pendingDownloads(): List<Download> = downloadDir.listFiles { f -> f.isDirectory }.orEmpty().flatMap { cellDir ->
        cellDir.listFiles { f -> f.isDirectory && !f.name.endsWith(".part") }.orEmpty().mapNotNull { dir ->
            try { Download(CellManifest.parse(File(dir, MANIFEST).readText()), dir) } catch (e: Exception) {
                Problems.warn("cells", "Downloaded map cell ${cellDir.name}/${dir.name} unreadable; deleted, loads again", e)
                dir.deleteRecursively(); null
            }
        }
    }

    /** Deletes download folders of [id] (after joining, or when a cell is deleted). */
    fun deleteDownloads(id: CellId) { File(downloadDir, id.id).deleteRecursively() }

    private fun addressFileOf(d: Download): File? =
        d.manifest.files.firstOrNull { it.name.startsWith("addresses/") }?.let { File(d.dir, it.name) }?.takeIf { it.isFile }

    private fun installAddresses(d: Download) {
        val src = addressFileOf(d) ?: return
        addressesDir.mkdirs()
        val dest = File(addressesDir, "${d.manifest.cell.id}.sqlite")
        // copy + rename (the download folder is deleted afterwards; a rename across folders works on one volume)
        try { AtomicFiles.replace(src, dest) } catch (e: IOException) {
            Problems.info("cells", "Addresses of cell ${d.manifest.cell} not moved; copied instead", e)
            val tmp = File(addressesDir, ".${dest.name}.tmp")
            try {
                src.copyTo(tmp, overwrite = true)
                AtomicFiles.replace(tmp, dest)
            } catch (e2: IOException) {
                tmp.delete()
                Problems.warn("cells", "Addresses of cell ${d.manifest.cell} could not be installed; address search there is missing", e2)
            }
        }
    }

    private fun deleteAddresses(c: CellId) {
        val f = File(addressesDir, "${c.id}.sqlite")
        if (f.exists() && !f.delete()) Problems.warn("cells", "Address file of deleted cell $c could not be removed")
    }

    // ------------------------------------------------------------------------------------------- helpers

    private fun boundsOf(cells: Set<CellId>, regionInputs: List<File>): DoubleArray? {
        var b: DoubleArray? = null
        fun add(x: DoubleArray) { b = b?.let { doubleArrayOf(minOf(it[0], x[0]), minOf(it[1], x[1]), maxOf(it[2], x[2]), maxOf(it[3], x[3])) } ?: x }
        for (c in cells) add(c.bbox)
        for (f in regionInputs) try {
            PmTiles.Reader(f).use { r -> val h = r.header; add(doubleArrayOf(h.minLonE7 / 1e7, h.minLatE7 / 1e7, h.maxLonE7 / 1e7, h.maxLatE7 / 1e7)) }
        } catch (e: Exception) { Problems.warn("cells", "Region map ${f.name} bounds unreadable; taken from the tiles", e) }
        return b
    }

    private fun toJson(g: Generation) = JSONObject()
        .put("generation", g.number).put("format", 2).put("createdAt", g.createdAt)
        .apply { if (g.poiTilesFailed) put("poiTilesFailed", true) }
        .put("region", JSONObject(g.region as Map<*, *>))
        .put("files", JSONObject(g.files as Map<*, *>))
        .put("cells", JSONArray(g.cells.map { c ->
            JSONObject().put("id", c.id.id).put("version", c.version).put("built", c.built ?: "").put("format", c.format)
                .put("bytes", JSONObject(c.bytes as Map<*, *>)).put("addressBytes", c.addressBytes).put("installedAt", c.installedAt)
                .apply { c.wayclass?.let { put("wayclass", it) } }
        }))

    companion object {
        /** Generation number of a store folder ("…/cells/store/g000007" → 7), null for any other folder (data folder). */
        fun generationOf(dir: File?): Int? =
            dir?.takeIf { it.parentFile?.name == "store" }?.let { GEN.matchEntire(it.name) }?.takeIf { it.groupValues[2].isEmpty() }
                ?.groupValues?.get(1)?.toIntOrNull()

        const val STORE_JSON = "store.json"
        const val MANIFEST = "manifest.json"
        const val POIS = "pois.geojson"
        /**
         * Tile layers of a format-2 map (style sources base, overlays, terrain, contours; region-shade = the region
         * packages' pre-rendered relief z7–9, map-cells.md §20).
         */
        val LAYERS = listOf("base.pmtiles", "overlays.pmtiles", "terrain.pmtiles", "contours.pmtiles", "hillshade.pmtiles")
        private val GEN = Regex("""^g(\d{6})(\.tmp|\.bad)?$""")
        private const val MB = 1_048_576L
        /** Kept free besides the new generation (the phone needs room to work). */
        const val MARGIN = 32L * MB

        /**
         * Metadata of the joined archive: the first input's, with `vector_layers` united by id (a cell may lack a
         * layer the region has). Never throws (falls back to the first input's metadata).
         */
        fun mergedMetadata(files: List<File>): String? {
            val metas = files.mapNotNull { f ->
                try { PmTiles.Reader(f).use { JSONObject(it.metadata()) } } catch (e: Exception) {
                    Problems.warn("cells", "Map file metadata of ${f.name} unreadable; left out", e); null
                }
            }
            val first = metas.firstOrNull() ?: return null
            val seen = LinkedHashMap<String, JSONObject>()
            for (m in metas) m.optJSONArray("vector_layers")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { l -> seen.putIfAbsent(l.optString("id"), l) }
            }
            if (seen.isNotEmpty()) first.put("vector_layers", JSONArray(seen.values.toList()))
            return first.toString()
        }
    }
}
