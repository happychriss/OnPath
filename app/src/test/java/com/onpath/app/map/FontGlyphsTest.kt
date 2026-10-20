package com.onpath.app.map

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every font a style uses must have every glyph range the styles need. A missing range (e.g. "▲" in front of peak
 * names for "Noto Sans Medium") makes MapLibre drop whole base-map tiles: paths, streams and lifts vanished in the
 * high-contrast theme (field test 2026-10-19).
 */
class FontGlyphsTest {
    private val fonts = File("src/main/assets/fonts")
    private val styles = File("src/main/assets/style").walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.toList()

    private fun fontsUsed(): Set<String> {
        val re = Regex("\"Noto Sans [A-Za-z ]+\"")
        return styles.flatMap { f -> re.findAll(f.readText()).map { it.value.trim('"') }.toList() }.toSet()
    }

    /** Glyph ranges for every non-ASCII character that appears in the styles (labels, symbols like ▲). */
    private fun rangesNeeded(): Set<String> = styles.flatMap { f ->
        f.readText().codePoints().toArray().filter { it > 255 }.map { cp -> val s = cp / 256 * 256; "$s-${s + 255}.pbf" }
    }.toSet() + "0-255.pbf"

    @Test fun everyUsedFontHasEveryNeededRange() {
        val missing = fontsUsed().flatMap { font -> rangesNeeded().filter { !File(fonts, "$font/$it").isFile }.map { "$font/$it" } }
        assertTrue("missing glyph ranges: $missing", missing.isEmpty())
    }

    @Test fun stylesParse() { styles.forEach { JSONObject(it.readText()) } }
}
