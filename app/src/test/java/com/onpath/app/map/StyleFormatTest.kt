package com.onpath.app.map

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Format-1 and format-2 bundled styles: metadata, sources, and every layer id the app switches. */
class StyleFormatTest {
    private val f1 = JSONObject(File("src/main/assets/style/onpath.json").readText())
    private val f2 = JSONObject(File("src/main/assets/style/f2/onpath.json").readText())

    private fun ids(s: JSONObject): List<String> {
        val a = s.getJSONArray("layers")
        return (0 until a.length()).map { a.getJSONObject(it).getString("id") }
    }

    @Test fun formatFromMetadata() {
        assertEquals(1, StyleLoader.styleFormat(f1.toString()))
        assertEquals(2, StyleLoader.styleFormat(f2.toString()))
        assertEquals(1, StyleLoader.styleFormat("""{"version":8,"sources":{},"layers":[]}"""))
        assertEquals(1, StyleLoader.styleFormat("not json"))
    }

    @Test fun assetFolders() {
        assertEquals("style", StyleLoader.assetDir(1))
        assertEquals("style/f2", StyleLoader.assetDir(2))
    }

    @Test fun format2ReadsOneOverlaySource() {
        val src = f2.getJSONObject("sources")
        assertTrue(src.has("overlays"))
        for (gone in listOf("routes", "trails", "ways", "paths", "lowways", "trailnames")) assertTrue(gone, !src.has(gone))
        val layers = f2.getJSONArray("layers")
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            if (l.optString("source") == "overlays") assertTrue(l.getString("id"), l.has("source-layer"))
            assertTrue("no fallback layers in format 2", l.optJSONObject("metadata")?.has(OptionalSources.FALLBACK_KEY) != true)
        }
    }

    @Test fun layerIdsTheAppSwitchesExistInBoth() {
        val used = listOf("routes-hike", "routes-bike", "routes-hike-major", "routes-bike-major-label",
            "access-no-tint-foot", "access-no-sign-foot", "access-no-tint-bike", "access-no-sign-bike",
            "trails-sac-label", "trails-mtb-label", "surface-loose-track", "surface-loose-path",
            "greenway-foot", "greenway-bike", "greenway-touring", "greenway-mtb", "poi-station", "poi-tier1")
        for (s in listOf(f1, f2)) { val have = ids(s).toSet(); used.forEach { assertTrue(it, it in have) } }
        // no MTB route band: mtb.brf ignores route=mtb, MTB shows the cycle band (map vs router audit #7)
        for (s in listOf(f1, f2)) assertTrue(ids(s).none { it.startsWith("routes-mtb") })
        // every format-1 layer has a format-2 twin, except the base-map fallbacks
        assertEquals(setOf("track-base", "path-base"), ids(f1).toSet() - ids(f2).toSet())
        // format 2 only (MapController.setOverlay)
        val f2only = listOf("access-no-tint-foot-service", "access-no-sign-bike-service", "bike-lane-left",
            "bike-lane-right-shared", "bike-oneway", "bike-oneway-streets", "bike-oneway-paths", "bike-both-ways")
        f2only.forEach { assertTrue(it, it in ids(f2).toSet()) }
    }

    /** Field test 2026-10-22: an inn vanished at some zooms – a route number ("40") above it won the collision. */
    @Test fun poisArePlacedBeforeTrailNumbers() {
        for (s in listOf(f1, f2)) {
            val ids = ids(s)
            val lastTrailLabel = ids.indexOfLast { it.startsWith("trail-") && (it.contains("name") || it.contains("ref")) }
            val firstPoi = ids.indexOfFirst { it.startsWith("poi-") }
            assertTrue("trail labels found", lastTrailLabel >= 0)
            assertTrue("POIs above trail names/numbers: $firstPoi > $lastTrailLabel", firstPoi > lastTrailLabel)
        }
    }

    /** Format 2: small streets come from overlays "streets" from z13 – the trimmed base map has none there. */
    @Test fun format2StreetsFromOverlayFromZ13() {
        val layers = f2.getJSONArray("layers")
        val own = (0 until layers.length()).map { layers.getJSONObject(it) }
            .filter { it.optString("source-layer") == "streets" }.map { it.getString("id") }.toSet()
        for (id in listOf("road-minor-casing", "road-minor-lane", "road-service-casing", "road-service-lane",
                "road-minor-tunnel", "bridge-casing-streets", "bike-oneway-streets", "access-no-tint-foot-service"))
            assertTrue(id, id in own)
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            val f = l.optJSONArray("filter")?.toString() ?: continue
            if (l.optString("source-layer") == "transportation" && ("\"minor\"" in f || "\"service\"" in f))
                assertTrue(l.getString("id"), l.optDouble("maxzoom", 24.0) <= 13.0)
        }
    }
}
