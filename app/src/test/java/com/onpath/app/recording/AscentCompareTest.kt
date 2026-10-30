package com.onpath.app.recording

import com.onpath.app.data.Dem
import com.onpath.app.data.DemTile
import com.onpath.app.data.DemTiles
import com.onpath.app.data.Geo
import com.onpath.app.data.Terrarium
import com.onpath.app.gpx.GpxParser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Old (GPS altitude, 5 m hysteresis) vs new (terrain, [AscentCounter]) ascent on the owner's recorded tours – a report,
 * run only with real terrain tiles at hand (not in the repo, too big): AWS Terrarium PNGs as `<dir>/13/<x>/<y>.png`
 * (`tools/build_terrain.py … --cache <dir>` keeps them so), e.g.
 * `ONPATH_DEM_TILES=<dir> ./gradlew :app:testDebugUnitTest --tests '*AscentCompareTest*' -i`.
 * 2026-10-30 (z13): tour3 GPS 77/251 → terrain 121/291 m up/down; tour4 245/140 → 287/196; Taser 12/838 → 32/858.
 */
class AscentCompareTest {
    private class PngTiles(val dir: File) : DemTiles {
        override val minZoom = 13; override val maxZoom = 13
        override fun heights(z: Int, x: Int, y: Int): DemTile? {
            val f = File(dir, "$z/$x/$y.png")
            if (!f.isFile) return null
            // javax.imageio is in the test JVM but not in the Android compile classpath: by reflection
            val img = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, f)
            val c = img.javaClass
            val w = c.getMethod("getWidth").invoke(img) as Int; val h = c.getMethod("getHeight").invoke(img) as Int
            val rgb = c.getMethod("getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java,
                IntArray::class.java, Int::class.java, Int::class.java).invoke(img, 0, 0, w, h, null, 0, w) as IntArray
            return Terrarium.tile(rgb)
        }
    }

    @Test fun ownerTours() {
        val dir = System.getenv("ONPATH_DEM_TILES")?.let(::File)
        assumeTrue("ONPATH_DEM_TILES not set", dir?.isDirectory == true)
        val dem = Dem(PngTiles(dir!!))
        for (f in listOf("owner-2026-10-19-tour3/track.gpx", "owner-2026-10-19-tour4/track.gpx",
                "owner-2026-10-22-taser/verdins-schenna.gpx", "owner-2026-10-19-tour3/plan.gpx", "owner-2026-10-19-tour4/plan.gpx")) {
            val segs = GpxParser.parse(javaClass.classLoader!!.getResource("tracks/$f")!!.readText()).allSegments
            val pts = segs.flatten()
            val old = Geo.climb(pts.map { it.ele }, AscentCounter.GPS_THRESHOLD_M)
            val c = AscentCounter()
            var up = 0.0; var down = 0.0; var withDem = 0
            for (i in pts.indices) {
                val p = pts[i]
                val h = dem.heightAt(p.lat, p.lon)
                if (h != null) withDem++
                val moved = if (i == 0) 0.0 else Geo.distance(pts[i - 1].lat, pts[i - 1].lon, p.lat, p.lon)
                val (u, d) = c.add(h, p.ele, moved); up += u; down += d
            }
            c.flush().let { (u, d) -> up += u; down += d }
            val km = segs.sumOf { s -> Geo.length(s.map { it.toGeo() }) } / 1000
            println("ASCENT $f: ${"%.2f".format(km)} km, ${pts.size} pts ($withDem with terrain) – " +
                "GPS 5 m: ${old.first.toInt()} up / ${old.second.toInt()} down; terrain: ${up.toInt()} up / ${down.toInt()} down")
        }
    }
}
