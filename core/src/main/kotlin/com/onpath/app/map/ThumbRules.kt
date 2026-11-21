package com.onpath.app.map

import com.onpath.app.data.Tour

/** Which pictures to make, and the key of a line (JVM tested). */
object ThumbRules {
    /** Raised when the picture's look changes (size, line width): every picture is made again once. */
    // 2: no route networks, the whole route with its ends inside (owner 2026-11-13); 3: the pictures up to 0.18.1 made
    // again – some were drawn while their map files were being replaced and showed the line on an empty map (field
    // report 2026-11-19; [plan] now really makes a picture of an older version again, it was only re-keyed)
    const val VERSION = 3

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
    fun key(t: Tour, points: String?): String = sig(t) + "|" + (points ?: "none") + rideTag(t)

    /**
     * What a ride choice ("Fahrten bearbeiten", 2026-11-21) does to the drawn line, part of every line key: the stored
     * points stay the same under every choice, the line drawn from them does not. Empty without a record and for "as
     * recorded" – those tours keep the keys (and pictures) they had.
     */
    fun rideTag(t: Tour): String {
        val r = t.rides ?: return ""
        if (r.choice == com.onpath.app.rides.RideChoice.AS_RECORDED) return ""
        return " r${r.choice.name}:${r.rides.size}:${r.rides.sumOf { it.from + it.to }}"
    }

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
            // the same line under a new signature: only the key – unless the picture is of an older look ([VERSION])
            if (have != null && have.startsWith("v$VERSION ") && have.substringAfter('|', "") == key.substringAfter('|')) rekey += t to key
            else make += t to key
        }
        make.sortWith(compareBy<Pair<Tour, String>>({ it.first.uuid != first }, { -it.first.createdMs }))
        return Plan(make, rekey, queried)
    }
}
