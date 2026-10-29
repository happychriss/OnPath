package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One type scale (owner): 12 caption / 14 small / 16 body / 22 title / 30 bold display, set once in
 * [OnPathType] (ui/Theme.kt). Screens take `style = MaterialTheme.typography.…`; a hand-set `fontSize =` is allowed only
 * for map furniture drawn on a canvas and for the glyphs / numbers inside round markers (they scale with the marker).
 */
class FontSizeRuleTest {
    private val sources = listOf("src/main/java/com/onpath/app/ui", "src/main/java/com/onpath/app/poi")
        .flatMap { File(it).walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }

    /** Text on the line that makes a literal font size fine (each with its reason). */
    private val allowed = listOf(
        "TextStyle(", // canvas text: profile axis labels, compass "N" (map furniture, 12 sp)
        "glyph", // the POI / search glyph inside its round marker, sized with the marker
        "index + 1", // the number inside a round plan-point marker (map overlay)
        "planIndex + 1", // the same number in the Points list
    )

    private fun hits() = sources.filter { it.name != "Theme.kt" }.flatMap { f ->
        f.readLines().mapIndexedNotNull { i, line ->
            val l = line.trim()
            if (!l.contains("fontSize =") || l.startsWith("//") || l.startsWith("*") || allowed.any { l.contains(it) }) null
            else "${f.name}:${i + 1}: $l"
        }
    }

    @Test fun noHandSetFontSizes() {
        val h = hits()
        assertEquals("use style = MaterialTheme.typography.… (OnPathType in ui/Theme.kt):\n" + h.joinToString("\n"), emptyList<String>(), h)
    }

    /** Nothing a hiker must read below 14 sp: only the caption step (map furniture) is 12. */
    @Test fun theScale() {
        val t = OnPathType
        assertEquals(12f, t.labelSmall.fontSize.value)
        listOf(t.bodySmall, t.bodyMedium, t.labelMedium).forEach { assertEquals(14f, it.fontSize.value) }
        listOf(t.bodyLarge, t.titleSmall, t.titleMedium, t.labelLarge).forEach { assertEquals(16f, it.fontSize.value) }
        assertEquals(22f, t.titleLarge.fontSize.value)
        assertEquals(30f, t.headlineMedium.fontSize.value)
        assertTrue(t.headlineMedium.fontWeight!!.weight >= 700)
    }
}
