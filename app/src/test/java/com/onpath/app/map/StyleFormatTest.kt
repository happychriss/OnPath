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
        val used = listOf("routes-hike", "routes-bike", "routes-mtb", "routes-hike-major", "routes-bike-major-label",
            "access-no-tint-foot", "access-no-sign-foot", "access-no-tint-bike", "access-no-sign-bike",
            "trails-sac-label", "trails-mtb-label", "surface-loose-track", "surface-loose-path",
            "greenway-foot", "greenway-bike", "poi-station", "poi-tier1")
        for (s in listOf(f1, f2)) { val have = ids(s).toSet(); used.forEach { assertTrue(it, it in have) } }
        // every format-1 layer has a format-2 twin, except the base-map fallbacks
        assertEquals(setOf("track-base", "path-base"), ids(f1).toSet() - ids(f2).toSet())
    }
}
