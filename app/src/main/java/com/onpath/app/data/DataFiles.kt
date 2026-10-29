package com.onpath.app.data

import com.onpath.strings.*
import android.content.Context
import com.onpath.app.debug.Problems
import java.io.File

/** Location and presence check of the big offline data files (pushed via adb). */
class DataFiles(context: Context) {

    /** = /sdcard/Android/data/com.onpath.app/files/onpath */
    val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "onpath")
    val segmentsDir: File get() = File(dir, "segments")

    data class Check(val name: String, val present: Boolean, val hint: String)

    init {
        // Create the folder so `adb push` has a target and the user can see where it is.
        try { dir.mkdirs() } catch (e: Exception) { Problems.warn("data", "Map data folder could not be created", e) }
    }

    /**
     * Folder the map files are read from: the current generation of the downloaded map cells
     * (docs/design/map-cells.md §19, [com.onpath.app.cells.CellStore]) when at least one cell is installed, else the
     * data folder (region package / adb push, as before cells existed). Never throws.
     */
    fun mapDir(): File = try { com.onpath.app.cells.CellStore(dir).mapDir() ?: dir } catch (e: Exception) {
        Problems.error("data", "Downloaded map areas unreadable; using the map package in the data folder", e); dir
    }

    fun check(): List<Check> {
        val map = mapDir()
        fun f(name: String, hint: String) = Check(name, File(map, name).let { it.isFile && it.length() > 0 }, hint)
        val rd5 = segmentsDir.listFiles { file -> file.name.endsWith(".rd5") && file.length() > 0 }.orEmpty()
        return listOf(
            f("base.pmtiles", Txt.s(Res.string.maps_check_base)),
            f("terrain.pmtiles", Txt.s(Res.string.maps_check_terrain)),
        ) + overlayChecks { name -> File(map, name).let { it.isFile && it.length() > 0 } } +
            Check("segments/*.rd5", rd5.isNotEmpty(), Txt.s(Res.string.maps_check_routing))
    }

    /** Map package format of the map folder: 2 = overlays as vector tiles ([OVERLAYS]), 1 = GeoJSON overlays. */
    fun format(): Int = mapDir().let { m -> formatOf { File(m, it).isFile } }

    fun has(name: String) = File(dir, name).isFile

    /**
     * The installed map package (package.json from the zip) and the files that do not belong to it: a file whose
     * size differs from the package's list came from another import (old package, laptop push) – the map then has
     * gaps at that package's edge. Null when there is no package.json (older data). Never throws.
     */
    fun packageCheck(): PackageCheck? = try {
        val f = File(dir, PACKAGE_FILE)
        if (!f.isFile) null else PackageCheck.parse(f.readText()) { name -> File(dir, name).let { if (it.isFile) it.length() else -1L } }
    } catch (e: Exception) { Problems.warn("data", "Map package list (package.json) unreadable, package check skipped", e); null }

    /** Pure part of [packageCheck] (unit-tested): [sizeOf] = file size on the phone, -1 = missing. */
    data class PackageCheck(val region: String, val bbox: List<Double>, val osmDate: String?, val mismatched: List<String>) {
        val ok: Boolean get() = mismatched.isEmpty()

        /** "10.70–11.60 E · 46.30–46.85 N" */
        val area: String get() = if (bbox.size == 4)
            "%.2f–%.2f E · %.2f–%.2f N".format(java.util.Locale.ROOT, bbox[0], bbox[2], bbox[1], bbox[3]) else ""

        companion object {
            fun parse(json: String, sizeOf: (String) -> Long): PackageCheck {
                val o = org.json.JSONObject(json)
                val files = o.optJSONObject("files")
                val bad = mutableListOf<String>()
                files?.keys()?.forEach { name ->
                    val size = sizeOf(name)
                    // missing optional files are fine (the missing-data check covers required ones)
                    if (size >= 0 && size != files.optLong(name, -2)) bad += name
                }
                val b = o.optJSONArray("bbox")
                return PackageCheck(
                    o.optString("region", "?"),
                    if (b == null) emptyList() else (0 until b.length()).map { b.optDouble(it) },
                    o.optString("osm_date").takeIf { it.isNotBlank() },
                    bad.sorted(),
                )
            }
        }
    }

    companion object {
        /** Format 2 (docs/design/map-data-size.md): routes, trails, paths, ways, trail names as one vector tile file. */
        const val OVERLAYS = "overlays.pmtiles"

        /** 2 when [OVERLAYS] exists (it wins over leftover format-1 GeoJSON files), else 1. Pure, unit-tested. */
        fun formatOf(exists: (String) -> Boolean): Int = if (exists(OVERLAYS)) 2 else 1

        /**
         * Map format the style is chosen for: [formatOf], except an empty map folder (no base map, no overlays of
         * either format) is format 2 – what downloaded map cells bring (map-cells.md Q11), and the style then shows the
         * overview from the APK until the first cell arrives. Pure, unit-tested.
         */
        fun styleFormatOf(exists: (String) -> Boolean): Int = when {
            exists(OVERLAYS) -> 2
            exists("base.pmtiles") || exists("routes.geojson") || exists("trails.geojson") -> 1
            else -> 2
        }

        /**
         * Of the [missing] files: those that keep the app on the "Offline map data is missing" screen. None when the APK
         * ships the overview map ([overview]) and this build can download map cells ([downloads]): the app then opens on
         * the overview and the map comes where the user is or plans (map-cells.md §9 T1/T3); missing routing data only
         * means straight lines until it is downloaded (Settings → Map data). Otherwise all of them, as before cells.
         * Pure, unit-tested.
         */
        fun blocking(missing: List<Check>, overview: Boolean, downloads: Boolean): List<Check> =
            if (overview && downloads) emptyList() else missing

        /**
         * Required overlay data: either the format-2 [OVERLAYS] file or the format-1 pair routes.geojson + trails.geojson.
         * With neither, a format-1 pair partly present lists that pair (what is missing); an empty folder asks for
         * [OVERLAYS] (what new packages contain). Pure, unit-tested.
         */
        fun overlayChecks(present: (String) -> Boolean): List<Check> = when {
            present(OVERLAYS) -> listOf(Check(OVERLAYS, true, Txt.s(Res.string.maps_check_overlays)))
            present("routes.geojson") || present("trails.geojson") -> listOf(
                Check("routes.geojson", present("routes.geojson"), Txt.s(Res.string.maps_check_routes)),
                Check("trails.geojson", present("trails.geojson"), Txt.s(Res.string.maps_check_trails)),
            )
            else -> listOf(Check(OVERLAYS, false, Txt.s(Res.string.maps_check_overlays)))
        }

        /** Written by tools/build_region.sh into the package: region, bbox, OSM date, size of every file. */
        const val PACKAGE_FILE = "package.json"
        const val ADB_COMMAND = "adb push onpath-data/. /sdcard/Android/data/com.onpath.app/files/onpath/"
    }
}
