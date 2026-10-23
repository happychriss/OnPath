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

    /**
     * Router classes (owner decisions 2026-10-22 (3) / (4), docs/map-style-notes.md "Router classes"): format 2 colours
     * green / forbidden per BRouter profile family from the class keys and keeps the tag rules for cells without them;
     * one forbidden-way layer set and one barrier layer per family (MapController.setOverlay); the hiking band above
     * the T level is its own layer (MapController.setSacLimit).
     */
    @Test fun format2RouterClassLayers() {
        val layers = f2.getJSONArray("layers")
        val byId = (0 until layers.length()).associate { layers.getJSONObject(it).let { l -> l.getString("id") to l } }
        for ((mode, key) in listOf("foot" to "ch", "bike" to "ct", "touring" to "cf", "mtb" to "cm")) {
            for (id in listOf("greenway-$mode", "access-no-tint-$mode", "access-no-dash-$mode", "access-no-sign-$mode",
                    "access-no-tint-$mode-service", "access-no-dash-$mode-service", "access-no-sign-$mode-service")) {
                val f = byId[id]?.optJSONArray("filter")?.toString()
                assertTrue("$id exists", f != null)
                assertTrue("$id reads $key", f!!.startsWith("[\"case\",[\"has\",\"$key\"]"))
            }
            assertTrue("barrier-blocked-$mode", byId["barrier-blocked-$mode"]?.optString("source-layer") == "barriers")
            // excluded only for difficulty (class 3): a grey line, never the red forbidden look (owner 2026-10-22)
            assertTrue("too-hard-$mode", byId["too-hard-$mode"]?.optJSONArray("filter")?.toString()?.contains("3") == true)
            assertTrue("forbidden is class 2 only", byId.getValue("access-no-dash-$mode").optJSONArray("filter")!!
                .toString().contains("[\"==\",[\"get\",\"$key\"],2]"))
        }
        // only the hiking layers are visible before the app switches the activity
        assertEquals("visible", byId.getValue("access-no-tint-foot").getJSONObject("layout").getString("visibility"))
        assertEquals("none", byId.getValue("access-no-tint-mtb").getJSONObject("layout").getString("visibility"))
        for (s in listOf(f1, f2)) {
            val ids = ids(s)
            assertTrue("routes-hike-over", "routes-hike-over" in ids)
            assertTrue("grey pieces above the band", ids.indexOf("routes-hike-over") > ids.indexOf("routes-hike"))
        }
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

    /**
     * Field report 0.10.1 (Bad Waldsee): restaurants swapped while zooming – all POIs of one kind had the same sort key,
     * so each tile's feature order decided. The key now ends in the POI's fixed priority "pr" (extract_pois.priority,
     * < 50000), below the named/unnamed band, and old data without "pr" still works (coalesce).
     */
    @Test fun poiSortKeyUsesFixedPriority() {
        for (s in listOf(f1, f2)) {
            val a = s.getJSONArray("layers")
            val pois = (0 until a.length()).map { a.getJSONObject(it) }
                .filter { it.getString("id").startsWith("poi-") && it.getString("id") != "poi-station" }
            assertTrue("POI layers found", pois.size >= 4)
            for (l in pois) {
                val key = l.getJSONObject("layout").get("symbol-sort-key").toString().replace(" ", "")
                assertTrue("${l.getString("id")}: pr in $key", key.contains("""["coalesce",["to-number",["get","pr"]],0]"""))
                assertTrue("${l.getString("id")}: unnamed band above pr", key.contains("""["case",["has","name"],0,50000]"""))
                assertTrue("${l.getString("id")}: one kind rank spans both bands", key.contains("100000"))
            }
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

    /**
     * Phone log 2026-10-22 (0.10.0): "[ParseStyle]: [4]: Expected array<color> but found string instead." MapLibre Native
     * 13.x types hillshade-shadow-color / -highlight-color as array<color> and cannot interpolate or step it – an
     * expression there is dropped and the layer draws opaque black / white. Only plain colour strings (or arrays of
     * them) are allowed; zoom-dependent shading uses zoom-band layers (make_style.py hillshade()). The bands of one
     * light must tile the zoom range without gaps or overlaps.
     */
    @Test fun hillshadeColoursAreConstantsNativeAccepts() {
        for (s in listOf(f1, f2)) {
            val layers = s.getJSONArray("layers")
            val hs = (0 until layers.length()).map { layers.getJSONObject(it) }.filter { it.optString("type") == "hillshade" }
            assertTrue(hs.isNotEmpty())
            for (l in hs) {
                val p = l.getJSONObject("paint")
                for (prop in listOf("hillshade-shadow-color", "hillshade-highlight-color")) {
                    val v = p.opt(prop) ?: continue
                    val ok = v is String || (v is org.json.JSONArray && v.length() > 0 &&
                        (0 until v.length()).all { v.get(it) is String } && v.getString(0) !in EXPRESSION_OPS)
                    assertTrue("${l.getString("id")} $prop must be a constant colour, got $v", ok)
                }
            }
            for (light in listOf("hillshade-nw", "hillshade-w")) {
                val bands = hs.filter { it.getString("id") == light || it.getString("id").startsWith("$light-z") }
                    .map { it.optDouble("minzoom", 0.0) to it.optDouble("maxzoom", 24.0) }.sortedBy { it.first }
                assertEquals(light, 0.0, bands.first().first, 0.0)
                bands.zipWithNext().forEach { (a, b) -> assertEquals("$light bands touch", a.second, b.first, 0.0) }
            }
        }
    }

    private companion object {
        val EXPRESSION_OPS = setOf("interpolate", "step", "match", "case", "literal", "coalesce", "get", "to-color", "rgba", "rgb")
    }
}
