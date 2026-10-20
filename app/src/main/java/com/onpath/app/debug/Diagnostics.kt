package com.onpath.app.debug

import android.content.Context
import android.graphics.RectF
import android.os.Build
import android.os.Process
import com.onpath.app.BuildConfig
import com.onpath.app.data.DataFiles
import com.onpath.app.data.Prefs
import com.onpath.app.map.StyleLoader
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.maps.MapLibreMap
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Diagnostics export (Settings → Diagnostics → Export): one zip the owner can send to a developer when something
 * looks wrong on the phone. Read-only, nothing leaves the phone unless the user shares the file. Can be switched off.
 *
 * Contents: `diagnostics.json` (app, device, settings, every data file with size / date and the PMTiles header,
 * package.json + consistency check, which style was loaded and style overrides, the live map: camera, layers with
 * visibility, features rendered on screen per layer, recording / navigation state), `logcat.txt` (this app's own log
 * incl. MapLibre's native warnings) and `style-loaded.json` (the style as MapLibre has it now).
 *
 * Never throws: every part catches its own errors and writes them into the report instead.
 */
object Diagnostics {

    const val PREF_KEY = "diagnosticsEnabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("onpath", 0).getBoolean(PREF_KEY, true)

    fun setEnabled(context: Context, on: Boolean) =
        context.getSharedPreferences("onpath", 0).edit().putBoolean(PREF_KEY, on).apply()

    /** Live map part – call on the main thread (MapLibre). */
    fun mapSnapshot(map: MapLibreMap?, widthPx: Int, heightPx: Int): JSONObject = safe {
        if (map == null) return@safe JSONObject().put("map", "not ready")
        val o = JSONObject()
        val cam = map.cameraPosition
        o.put("camera", JSONObject().put("lat", cam.target?.latitude).put("lon", cam.target?.longitude)
            .put("zoom", cam.zoom).put("bearing", cam.bearing).put("tilt", cam.tilt))
        val style = map.style
        if (style == null) return@safe o.put("style", "not loaded")
        o.put("styleFullyLoaded", style.isFullyLoaded)
        o.put("sources", JSONArray(style.sources.map { it.id }))
        val layers = JSONArray()
        for (l in style.layers) {
            layers.put(JSONObject().put("id", l.id).put("visibility", runCatching { l.visibility.value }.getOrNull())
                .put("minzoom", finite(l.minZoom)).put("maxzoom", finite(l.maxZoom)))
        }
        o.put("layers", layers)
        o.put("layerCount", style.layers.size)
        // what is actually drawn on screen right now, per layer – shows at once whether e.g. "path" renders
        val counts = sortedMapOf<String, Int>()
        runCatching {
            map.queryRenderedFeatures(RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())).forEach { f ->
                // MapLibre does not expose the layer of a rendered feature on Android: count by source-layer + class
                val key = (f.getStringProperty("class") ?: "-") + "/" + (f.getStringProperty("subclass") ?: "-")
                counts[key] = (counts[key] ?: 0) + 1
            }
        }.onFailure { o.put("renderedError", it.toString()) }
        o.put("renderedByClass", JSONObject(counts as Map<*, *>))
        val perLayer = sortedMapOf<String, Int>()
        for (l in style.layers) {
            val n = runCatching {
                map.queryRenderedFeatures(RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()), l.id).size
            }.getOrDefault(-1)
            if (n != 0) perLayer[l.id] = n
        }
        o.put("renderedPerLayer", JSONObject(perLayer as Map<*, *>))
        o
    }

    /** Builds the zip in cache/export (IO thread). [extra] = app state added by the caller. */
    fun export(context: Context, mapPart: JSONObject, styleJson: String?, extra: JSONObject): File {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val out = File(dir, "onpath-diagnostics-$stamp.zip")
        val report = JSONObject()
            .put("format", 1)
            .put("created", iso(System.currentTimeMillis()))
            .put("timezone", TimeZone.getDefault().id)
            .put("app", safe { app() })
            .put("device", safe { device() })
            .put("settings", safe { settings(context) })
            .put("data", safe { data(context) })
            .put("style", safe { style(context) })
            .put("map", mapPart)
            .put("state", extra)
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            fun put(name: String, text: String) {
                z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry()
            }
            put("diagnostics.json", report.toString(1))
            put("problems.json", Problems.toJson().toString(1))
            put("logcat.txt", logcat())
            if (styleJson != null) put("style-loaded.json", styleJson)
        }
        // keep only the last few exports
        dir.listFiles { f -> f.name.startsWith("onpath-diagnostics-") }?.sortedByDescending { it.lastModified() }
            ?.drop(3)?.forEach { it.delete() }
        return out
    }

    private fun app() = JSONObject().put("version", BuildConfig.VERSION_NAME).put("build", BuildConfig.VERSION_CODE)
        .put("debug", BuildConfig.DEBUG).put("package", BuildConfig.APPLICATION_ID)
        .put("map_server", com.onpath.app.data.MapServer.describe()) // masked key only

    private fun device() = JSONObject().put("model", Build.MODEL).put("manufacturer", Build.MANUFACTURER)
        .put("sdk", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
        .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))

    private fun settings(context: Context): JSONObject {
        val s = Prefs(context).settings
        return JSONObject().put("all", s.toString())
            .put("hiddenPoiKinds", JSONArray(s.hiddenPoiKinds.toList()))
            .put("mapTheme", s.mapTheme).put("contours", s.contours).put("hillshade", s.hillshade)
    }

    private fun data(context: Context): JSONObject {
        val df = DataFiles(context)
        val o = JSONObject().put("dir", df.dir.absolutePath)
        val files = JSONArray()
        df.dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
            val e = JSONObject().put("path", f.relativeTo(df.dir).path).put("bytes", f.length())
                .put("modified", iso(f.lastModified()))
            if (f.name.endsWith(".pmtiles")) e.put("pmtiles", safe { pmtilesHeader(f) })
            files.put(e)
        }
        o.put("files", files)
        o.put("checks", JSONArray(df.check().map { "${it.name}: ${if (it.present) "ok" else "MISSING"}" }))
        o.put("packageJson", try {
            File(df.dir, DataFiles.PACKAGE_FILE).takeIf { it.isFile }?.let { JSONObject(it.readText()) } ?: JSONObject.NULL
        } catch (e: Exception) { e.toString() })
        val pc = df.packageCheck()
        o.put("packageCheck", if (pc == null) "no package.json" else
            JSONObject().put("region", pc.region).put("area", pc.area).put("ok", pc.ok).put("mismatched", JSONArray(pc.mismatched)))
        return o
    }

    private fun style(context: Context): JSONObject {
        val df = DataFiles(context)
        val o = JSONObject().put("loaded", StyleLoader.lastLoaded ?: "unknown")
        val overrides = File(df.dir, StyleLoader.OVERRIDE_DIR).listFiles()?.map {
            JSONObject().put("name", it.name).put("bytes", it.length()).put("modified", iso(it.lastModified()))
        }.orEmpty()
        o.put("overrides", JSONArray(overrides))
        o.put("bundledThemes", JSONArray(StyleLoader.themes(context, df.dir)))
        return o
    }

    /** PMTiles v3 header (127 bytes, little endian). */
    fun pmtilesHeader(f: File): JSONObject {
        val b = ByteArray(127)
        RandomAccessFile(f, "r").use { it.readFully(b) }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val magic = String(b, 0, 7)
        if (magic != "PMTiles") return JSONObject().put("error", "not a PMTiles file (magic '$magic')")
        return JSONObject().put("version", b[7].toInt())
            .put("addressedTiles", bb.getLong(72)).put("tileEntries", bb.getLong(80)).put("tileContents", bb.getLong(88))
            .put("tileCompression", b[98].toInt()).put("tileType", b[99].toInt())
            .put("minZoom", b[100].toInt()).put("maxZoom", b[101].toInt())
            .put("bounds", JSONArray(listOf(bb.getInt(102) / 1e7, bb.getInt(106) / 1e7, bb.getInt(110) / 1e7, bb.getInt(114) / 1e7)))
            .put("tileDataBytes", bb.getLong(64))
            .put("fileBytes", f.length())
            .put("complete", bb.getLong(56) + bb.getLong(64) <= f.length())
    }

    /** This app's own log (Android lets an app read its own lines without any permission). */
    private fun logcat(): String = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "3000", "--pid=${Process.myPid()}")
            .redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }.ifBlank { "(empty)" }
    } catch (e: Exception) { "logcat not readable: $e" }

    /** JSON has no ±Infinity / NaN (MapLibre reports "no zoom limit" as ±Infinity). */
    private fun finite(v: Float): Any = if (v.isFinite()) v.toDouble() else JSONObject.NULL

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(Date(ms))

    private inline fun safe(block: () -> JSONObject): JSONObject = try { block() } catch (e: Throwable) {
        JSONObject().put("error", e.toString())
    }
}
