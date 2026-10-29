package com.onpath.app.map

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fresh install without any map file: the style must not point MapLibre at a missing file ("Failed to load source
 * terrain … path not found" would be announced as a problem) – only the overview from the APK draws.
 */
class OverviewOnlyStyleTest {
    private val overview = setOf("base.pmtiles", "terrain.pmtiles")

    private fun f2(): String = File("src/main/assets/style/f2/onpath.json").readText().replace("{DATA_DIR}", "/nothing")

    private fun check(out: String) {
        val root = JSONObject(out)
        val sources = root.getJSONObject("sources")
        val ids = sources.keys().asSequence().toSet()
        // no source reads a local file
        ids.forEach { id -> assertEquals("source $id", null, OptionalSources.localFile(sources.getJSONObject(id))) }
        // every layer's source exists
        val layers = root.getJSONArray("layers")
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            val s = l.optString("source")
            if (s.isNotEmpty()) assertTrue("layer ${l.getString("id")} uses missing source $s", s in ids)
        }
        assertFalse("base" in ids); assertFalse("terrain" in ids)
    }

    @Test fun formatTwoStyleWithOnlyTheOverview() {
        val out = StyleLoader.withOverview(f2(), overview) { false }
        check(out)
        val sources = JSONObject(out).getJSONObject("sources")
        assertTrue(sources.has("overview")); assertTrue(sources.has("overview-terrain"))
        val layers = JSONObject(out).getJSONArray("layers")
        assertTrue((0 until layers.length()).any { layers.getJSONObject(it).optString("source") == "overview" })
    }

    @Test fun formatTwoStyleWithoutOverviewIsStillValid() = check(StyleLoader.withOverview(f2(), emptySet()) { false })

    @Test fun formatOneStyleWithOnlyTheOverview() = check(StyleLoader.withOverview(
        File("src/main/assets/style/onpath.json").readText().replace("{DATA_DIR}", "/nothing"), overview) { false })

    @Test fun presentFilesAreKept() {
        val out = StyleLoader.withOverview(f2(), overview) { true }
        val sources = JSONObject(out).getJSONObject("sources")
        assertTrue(sources.has("base")); assertTrue(sources.has("terrain")); assertTrue(sources.has("overlays"))
    }

    @Test fun fallbackLayerGoesWithItsOwnSource() {
        val style = """{"version":8,"sources":{
          "base":{"type":"vector","url":"pmtiles://file:///d/base.pmtiles"},
          "paths":{"type":"geojson","data":"file:///d/paths.geojson"}},
         "layers":[{"id":"path-base","type":"line","source":"base","metadata":{"onpath:fallback-for":"paths"}},
          {"id":"paths","type":"line","source":"paths"}]}"""
        val out = OptionalSources.strip(style, standIn = { true }) { false }
        assertEquals(0, JSONObject(out).getJSONArray("layers").length())
    }
}
