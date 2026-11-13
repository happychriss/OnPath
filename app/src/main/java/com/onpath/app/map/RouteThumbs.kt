package com.onpath.app.map

import com.onpath.app.data.AtomicFiles
import com.onpath.app.data.Tour
import com.onpath.app.debug.Problems
import java.io.File

/**
 * The small map pictures of My routes' rows (owner 2026-11-13, layout L1: a square map + the route left of the name).
 * One file per tour, by uuid, under the app's files (`files/thumbs/<uuid>.webp`, not in any backup – they are made
 * again), plus `<uuid>.key`: the line the picture shows ([ThumbRules.key]). A picture whose line changed (a route saved
 * over, routed again after a sync) is made anew; a tour deleted for good loses its picture. Reading never throws.
 */
class RouteThumbs(val dir: File) {
    fun file(uuid: String) = File(dir, "$uuid.webp")
    private fun keyFile(uuid: String) = File(dir, "$uuid.key")

    /** The line key the stored picture was made for; null = none made yet. */
    fun storedKey(uuid: String): String? = try {
        keyFile(uuid).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) { Problems.info("routes", "Thumbnail key of $uuid unreadable – made again", e); null }

    /** Stores the picture [bytes] (null = nothing to draw: the row keeps its drawn line) for line [key]. False on failure (reported). */
    fun write(uuid: String, key: String, bytes: ByteArray?): Boolean = try {
        dir.mkdirs()
        if (bytes == null) file(uuid).delete() else AtomicFiles.writeBytes(file(uuid), bytes)
        AtomicFiles.writeText(keyFile(uuid), key)
        true
    } catch (e: Exception) { Problems.info("routes", "Thumbnail of $uuid not stored – the row keeps its drawn line", e); false }

    /** Only the key changes (the line is the same, its signature moved on): the picture stays. False on failure (reported). */
    fun writeKey(uuid: String, key: String): Boolean = try {
        dir.mkdirs(); AtomicFiles.writeText(keyFile(uuid), key); true
    } catch (e: Exception) { Problems.info("routes", "Thumbnail key of $uuid not stored – looked at again next time", e); false }

    /** Removes the pictures of tours no longer on the phone ([keep] = uuids still there, recycle bin included). */
    fun retain(keep: Set<String>): Int {
        val files = dir.listFiles() ?: return 0
        var n = 0
        for (f in files) {
            val uuid = f.name.substringBeforeLast('.')
            if (uuid !in keep && (f.name.endsWith(".webp") || f.name.endsWith(".key") || f.name.endsWith(".tmp"))) { if (f.delete()) n++ }
        }
        return n
    }
}

/** Which pictures to make, and the key of a line (JVM tested). */
object ThumbRules {
    /** Raised when the picture's look changes (size, line width): every picture is made again once. */
    const val VERSION = 2 // 2: no route networks, the whole route with its ends inside (owner 2026-11-13)

    /** The edge of a picture (dp): the L1 tile. */
    const val TILE_DP = 84
    /**
     * The room around the route (owner 2026-11-13: always the WHOLE route with start and end): [MARGIN] of the picture on
     * each side plus a marker's radius ([MARKER_DP]) – [region] makes the map region square and that much larger.
     */
    const val MARGIN = 0.14
    const val MARKER_DP = 4.5f
    const val MARKER_RING_DP = 1.5f
    /** The route line on the small picture against the map's ([RouteLook]): 3.6 dp in a 6 dp casing on 84 dp. */
    const val LINE_SCALE = 0.6f

    /**
     * The map region for a picture of [points] (south, west, north, east): square in Web Mercator around the points'
     * middle, so a long thin route and a round trip fit whole, with [MARGIN] + a marker's radius free on every side.
     * Null without points. Pure.
     */
    fun region(points: List<com.onpath.app.routing.GeoPoint>): DoubleArray? {
        if (points.isEmpty()) return null
        val xs = points.map { mx(it.lon) }; val ys = points.map { my(it.lat) }
        val cx = (xs.min() + xs.max()) / 2; val cy = (ys.min() + ys.max()) / 2
        // a 30 m minimum (a point or a few metres): the picture never zooms past the map
        val span = maxOf(xs.max() - xs.min(), ys.max() - ys.min(), 30.0 / 40_075_016.0)
        val free = MARGIN + (MARKER_DP + MARKER_RING_DP) / TILE_DP
        val half = span / 2 / (1 - 2 * free)
        return doubleArrayOf(lat(cy + half), lon(cx - half), lat(cy - half), lon(cx + half))
    }

    /** A point on a square picture of [region] [size] px wide (x, y; Web Mercator) – for the markers and the tests. */
    fun project(p: com.onpath.app.routing.GeoPoint, region: DoubleArray, size: Double): Pair<Double, Double> {
        val x0 = mx(region[1]); val x1 = mx(region[3]); val y0 = my(region[2]); val y1 = my(region[0])
        return (mx(p.lon) - x0) / (x1 - x0) * size to (my(p.lat) - y0) / (y1 - y0) * size
    }

    // Web Mercator in 0..1 (y down)
    private fun mx(lon: Double) = (lon + 180.0) / 360.0
    private fun my(lat: Double): Double {
        val s = Math.sin(Math.toRadians(lat.coerceIn(-85.0, 85.0)))
        return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)
    }
    private fun lon(x: Double) = x * 360.0 - 180.0
    private fun lat(y: Double) = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y))))

    /**
     * The cheap signature of [t]'s line from its row alone (kind, distance, ascent, the via points / waypoints) – no
     * database work. A line that changes (a route saved over, routed again after a sync, a track replaced) changes it.
     */
    fun sig(t: Tour): String {
        val w = t.waypoints
        return "v$VERSION ${t.kind.name} " + "%.1f".format(java.util.Locale.ROOT, t.distanceM) + " " +
            "%.1f".format(java.util.Locale.ROOT, t.ascentM) + " w${w.size}:" +
            "%.6f".format(java.util.Locale.ROOT, w.sumOf { it.lat }) + ":" + "%.6f".format(java.util.Locale.ROOT, w.sumOf { it.lon })
    }

    /**
     * The key a picture is made for: "[sig]|line" – line = the stored points' summary ("count:sumLat:sumLon", TourDb.lineKey;
     * null = no stored points → its via points, as the list's line does).
     */
    fun key(t: Tour, points: String?): String = sig(t) + "|" + (points ?: "none")

    /** What a pass does: [make] these pictures (in this order), only [rekey] these (same line, new signature); [queried] = line keys read. */
    data class Plan(val make: List<Pair<Tour, String>>, val rekey: List<Pair<Tour, String>>, val queried: Int)

    /**
     * A pass over My routes' [tours]: a tour whose stored key ([stored], by uuid) has its current [sig] needs nothing – no
     * database work; only for the others the line key is read ([lineKey]) – a new line → a picture, the same line → the key
     * only. [first] (just saved / ended) first, then newest first. A tour being recorded, one without a uuid, and keys in
     * [skip] (failed in this run) wait.
     */
    fun plan(tours: List<Tour>, stored: (String) -> String?, lineKey: (Tour) -> String?, skip: Set<String> = emptySet(),
             first: String? = null): Plan {
        val make = ArrayList<Pair<Tour, String>>(); val rekey = ArrayList<Pair<Tour, String>>(); var queried = 0
        for (t in tours) {
            if (t.state != "done" || t.uuid.isEmpty()) continue
            val have = stored(t.uuid)
            val sig = sig(t)
            if (have != null && have.substringBefore('|') == sig) continue
            queried++
            val key = key(t, lineKey(t))
            if (key in skip) continue
            if (have != null && have.substringAfter('|', "") == key.substringAfter('|')) rekey += t to key else make += t to key
        }
        make.sortWith(compareBy<Pair<Tour, String>>({ it.first.uuid != first }, { -it.first.createdMs }))
        return Plan(make, rekey, queried)
    }
}
