package com.onpath.app.map

import com.onpath.app.testkit.Sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "I am here" dot must never end up under a line or a route point (owner) – except the numbered points in Plan
 * (owner 2026-11-26: point ① must be seen when you stand on it; PointsOverDot.LAYER).
 * MapLibre draws later layers on top; after a style reload its position layers are already there when the app adds
 * its own. So every layer the app adds must go through MapController.addBelowPosition – a plain addLayer anywhere
 * else puts it above the dot. The emulator test CorePlanE2ETest.positionDotStaysAboveRoutePointsAfterAReload checks
 * the real order on a device.
 */
class PositionDotOnTopTest {
    private val sources = Sources.allFiles()

    @Test fun noLayerIsAddedPastThePositionDot() {
        val call = Regex("""\.addLayer(Above|At)?\s*\(""")
        val hits = sources.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                // the one layer meant above the dot: the plan points in Plan (owner 2026-11-26, PointsOverDot)
                if (call.containsMatchIn(line) && !line.contains("firstPosition") && !line.contains("LAYER_WPT_OVER_DOT"))
                    "${f.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertEquals("add map layers with addBelowPosition (MapController), not addLayer:\n" + hits.joinToString("\n"),
            emptyList<String>(), hits)
    }

    @Test fun theOneAllowedAddLayerPutsLayersBelowThePosition() {
        val mc = File("src/main/java/com/onpath/app/map/MapController.kt").readText()
        assertTrue(mc.contains("style.addLayerBelow(l, firstPosition)"))
        assertTrue(mc.contains("POSITION_LAYER_PREFIX = \"mapbox-location\""))
    }
}
