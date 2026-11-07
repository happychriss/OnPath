package com.onpath.app.map

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every font a style uses must have every glyph range the styles need. A missing range (e.g. "▲" in front of peak
 * names for "Noto Sans Medium") makes MapLibre drop whole base-map tiles: paths, streams and lifts vanish.
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

    /**
     * Owner 2026-11-06 (Pixel, 17:59: "Failed to load glyph range 8448-8703" near Königswinter): map labels come from
     * OpenStreetMap and may hold ANY character – an arrow in a name was enough to drop tiles. So every font ships all 256
     * ranges: real glyphs for what European labels use, a small empty range (the font stack's name + the range, as the
     * glyph servers send for an empty range) for every other – then at worst one character is missing, never a tile.
     */
    @Test fun everyUsedFontHasAll256Ranges() {
        val missing = fontsUsed().flatMap { font ->
            (0 until 256).map { "${it * 256}-${it * 256 + 255}.pbf" }.filter { !File(fonts, "$font/$it").isFile }.map { "$font/$it" }
        }
        assertTrue("missing glyph ranges (${missing.size}): ${missing.take(10)}", missing.isEmpty())
    }

    /** The ranges map labels in Europe need carry real glyphs (Latin with accents, Greek, Cyrillic, arrows, ①). */
    @Test fun europeanRangesHaveRealGlyphs() {
        val real = listOf("0-255", "256-511", "512-767", "768-1023", "1024-1279", "7680-7935", "8192-8447", "8448-8703",
            "9216-9471", "9472-9727", "9728-9983")
        val empty = fontsUsed().flatMap { font -> real.filter { File(fonts, "$font/$it.pbf").length() < 1_000 }.map { "$font/$it" } }
        assertTrue("ranges without glyphs: $empty", empty.isEmpty())
    }

    @Test fun stylesParse() { styles.forEach { JSONObject(it.readText()) } }
}
