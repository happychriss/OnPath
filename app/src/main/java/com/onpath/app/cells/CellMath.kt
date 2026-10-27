package com.onpath.app.cells

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * A map cell (docs/design/map-cells.md §2): web-mercator tile at zoom 10. Id "10-543-361" in files and the API,
 * "10/543/361" in URLs. Same formulas as tools/cells/cells.py and the doorman (tools/cloud/worker/src/cells.js).
 */
data class CellId(val x: Int, val y: Int) : Comparable<CellId> {
    init { require(x in 0 until N && y in 0 until N) { "cell $x/$y outside the grid" } }

    /** "10-543-361" */
    val id: String get() = "$Z-$x-$y"
    /** "10/543/361" */
    val path: String get() = "$Z/$x/$y"

    /** [west, south, east, north] in degrees. */
    val bbox: DoubleArray get() = doubleArrayOf(lonOf(x), latOf(y + 1), lonOf(x + 1), latOf(y))

    val center: GeoPoint get() = bbox.let { GeoPoint((it[1] + it[3]) / 2, (it[0] + it[2]) / 2) }

    /** True if map tile z/x/y (z ≥ 10) lies inside this cell. Tiles below zoom 10 belong to no cell. */
    fun ownsTile(z: Int, tx: Int, ty: Int): Boolean = z >= Z && (tx shr (z - Z)) == x && (ty shr (z - Z)) == y

    override fun compareTo(other: CellId): Int = compareValuesBy(this, other, { it.y }, { it.x })
    override fun toString(): String = id

    companion object {
        const val Z = 10
        const val N = 1 shl Z

        private val PATTERN = Regex("""^(\d{1,2})[-/](\d{1,4})[-/](\d{1,4})$""")

        /** "10-543-361" or "10/543/361" → cell, null when not a zoom-10 cell id. */
        fun parse(s: String?): CellId? {
            val m = PATTERN.matchEntire(s?.trim() ?: return null) ?: return null
            val (z, x, y) = m.destructured
            if (z.toInt() != Z) return null
            val xi = x.toInt(); val yi = y.toInt()
            return if (xi < N && yi < N) CellId(xi, yi) else null
        }

        fun lonOf(x: Int): Double = x.toDouble() / N * 360.0 - 180.0
        fun latOf(y: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / N))))

        /**
         * Owner of a point (half-open rule, §2): `west ≤ lon < east`, `south < lat ≤ north`, so a point on a border
         * has exactly one owner. Null outside the web-mercator range (poles).
         */
        fun at(lat: Double, lon: Double): CellId? {
            if (lat.isNaN() || lon.isNaN() || lat <= -85.0511 || lat > 85.0511) return null
            val l = ((lon + 180.0) % 360.0 + 360.0) % 360.0
            val x = floor(l / 360.0 * N).toInt().coerceIn(0, N - 1)
            val r = Math.toRadians(lat)
            val fy = (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * N
            var y = floor(fy).toInt()
            // exact rule with the edge formula (rounding of fy, points on an edge), one step either way:
            // cell y holds latOf(y + 1) < lat ≤ latOf(y)
            if (y in 1 until N && lat > latOf(y)) y -= 1
            if (y in 0 until N - 1 && lat <= latOf(y + 1)) y += 1
            return if (y in 0 until N) CellId(x, y) else null
        }

        fun at(p: GeoPoint): CellId? = at(p.lat, p.lon)

        /** Cell owning tile z/x/y (z ≥ 10), null below zoom 10. */
        fun ofTile(z: Int, x: Int, y: Int): CellId? = if (z < Z) null else CellId(x shr (z - Z), y shr (z - Z))

        /** All cells touching the box (degrees); at most [max] (a guard against "whole world" boxes). */
        fun inBbox(south: Double, west: Double, north: Double, east: Double, max: Int = 400): List<CellId> {
            val sw = at(south.coerceIn(-85.0, 85.0), west) ?: return emptyList()
            val ne = at(north.coerceIn(-85.0, 85.0), east) ?: return emptyList()
            val xs = if (sw.x <= ne.x) (sw.x..ne.x).toList() else (sw.x until N).toList() + (0..ne.x).toList()
            val out = ArrayList<CellId>()
            for (y in ne.y..sw.y) for (x in xs) { out += CellId(x, y); if (out.size >= max) return out }
            return out
        }

        /**
         * The cell of [p] plus neighbours whose border is within [radiusM] of it (Q1: "close neighbours only",
         * usually 0–3). Own cell first.
         */
        fun near(p: GeoPoint, radiusM: Double = 5000.0): List<CellId> {
            val own = at(p) ?: return emptyList()
            val out = arrayListOf(own)
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val x = (own.x + dx + N) % N; val y = own.y + dy
                if (y !in 0 until N) continue
                val c = CellId(x, y)
                if (distanceToCell(p, c) <= radiusM) out += c
            }
            return out
        }

        /** Distance in metres from [p] to the nearest point of cell [c] (0 inside). */
        fun distanceToCell(p: GeoPoint, c: CellId): Double {
            val b = c.bbox
            val lat = p.lat.coerceIn(b[1], b[3]); val lon = p.lon.coerceIn(b[0], b[2])
            return Geo.distance(p.lat, p.lon, lat, lon)
        }

        /**
         * Cells within [corridorM] of the line (route corridor, F-OFF-02 / T4), in line order (cells near the start
         * first). Samples the line every ~1 km and takes every cell within the corridor of a sample.
         */
        fun alongLine(line: List<GeoPoint>, corridorM: Double = 10_000.0, max: Int = 200): List<CellId> {
            if (line.isEmpty()) return emptyList()
            val out = LinkedHashSet<CellId>()
            fun addAround(p: GeoPoint) {
                val dLat = corridorM / 111_320.0
                val dLon = corridorM / (111_320.0 * cos(Math.toRadians(p.lat)).coerceAtLeast(0.01))
                at(p)?.let { out += it }
                for (c in inBbox(p.lat - dLat, p.lon - dLon, p.lat + dLat, p.lon + dLon, 64)) {
                    if (c !in out && distanceToCell(p, c) <= corridorM) out += c
                }
            }
            addAround(line.first())
            for (i in 1 until line.size) {
                val a = line[i - 1]; val b = line[i]
                val d = Geo.distance(a.lat, a.lon, b.lat, b.lon)
                val steps = (d / 1000.0).toInt()
                for (s in 1..steps) {
                    val t = s.toDouble() / (steps + 1)
                    addAround(GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t))
                    if (out.size >= max) return out.toList().take(max)
                }
                addAround(b)
                if (out.size >= max) return out.toList().take(max)
            }
            return out.toList()
        }
    }
}
