package com.onpath.app.cells

/**
 * The density limit for city POIs (owner 2026-11-13, central London: „Vielleicht ab einer bestimmten Zahl Restaurants
 * ausblenden – in einer Riesenstadt sucht man mit der App keine Restaurants.“).
 *
 * Restaurants, cafés, bars, snack bars and shops ([KINDS]) are counted per map tile of the zoom shown (512 px – about a
 * third of a phone screen): where more than [CAP] of them share one tile, only the [CAP] most notable (named first,
 * then the fixed priority "pr" of tools/extract_pois.py) are drawn at that zoom; the others come one zoom closer, all
 * of them from [ALL_FROM]. A POI hidden at a zoom stays hidden at every lower zoom (never pops in and out while
 * zooming). In the Alps, a village or a valley nothing changes; huts, water, toilets, transport, viewpoints, peaks and
 * every other kind are never limited.
 *
 * Built into the POI tiles ([PoiTiles]): a POI drawn later than its kind gets the property [PROP] = its first zoom and
 * is left out of the tiles below it; the style's POI layers draw a feature only from `zoom >= dz`
 * (tools/make_style.py pois()). Pure – unit tested.
 */
object PoiDensity {
    /** Limited kinds → the zoom the style starts drawing them (tools/make_style.py POI_TIERS; PoiTilesStyleTest). */
    val KINDS: Map<String, Int> = mapOf("restaurant" to 12, "cafe" to 12, "bar" to 14, "fast_food" to 14, "shop" to 15)

    /** Most limited POIs drawn per 512 px tile of the zoom shown. */
    const val CAP = 24

    /** From this zoom every POI is drawn (no limit). */
    const val ALL_FROM = 17

    /** Tile property: first zoom the POI is drawn at (only when later than its kind's own zoom). */
    const val PROP = "dz"

    /** One limited POI: Web Mercator position (0..1), kind, named or not, fixed priority (higher = more notable). */
    class Point(val mx: Double, val my: Double, val kind: String, val named: Boolean, val pr: Long)

    /** First zoom each of [points] is drawn at (same order); a kind outside [KINDS] keeps zoom 0 (never limited). */
    fun firstZooms(points: List<Point>): IntArray {
        val out = IntArray(points.size) { i -> if (points[i].kind in KINDS) ALL_FROM else 0 }
        val limited = points.indices.filter { points[it].kind in KINDS }
        if (limited.isEmpty()) return out
        // more notable first: named, higher "pr", then a fixed position order (same result on every build)
        val order = compareByDescending<Int> { points[it].named }.thenByDescending { points[it].pr }
            .thenBy { points[it].my }.thenBy { points[it].mx }
        val stillShown = HashSet<Int>(limited) // shown at every zoom from the one checked up to ALL_FROM - 1
        val lowest = KINDS.values.min()
        for (z in ALL_FROM - 1 downTo lowest) {
            val n = (1L shl z).toDouble()
            val byTile = HashMap<Long, MutableList<Int>>()
            for (i in limited) {
                if (KINDS.getValue(points[i].kind) > z) continue
                val tx = (points[i].mx * n).toLong(); val ty = (points[i].my * n).toLong()
                byTile.getOrPut((tx shl 32) or ty) { ArrayList() } += i
            }
            val shownHere = HashSet<Int>()
            for (group in byTile.values) group.sortedWith(order).take(CAP).forEach { shownHere += it }
            for (i in limited) {
                if (i !in stillShown || KINDS.getValue(points[i].kind) > z) continue
                if (i in shownHere) out[i] = z else stillShown -= i
            }
        }
        return out
    }
}
