package com.onpath.strings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The string files stay usable (EN + DE): every English text has a German one with the same
 * placeholders, plurals have their forms, and nothing uses Android-only syntax that Compose resources would show
 * literally (`\'`, `%%`, `%s` without a number, `%.1f`).
 */
class StringFilesTest {
    private val dir = File(System.getProperty("onpath.stringsDir")!!)
    private val en = XmlStrings.read(File(dir, "values"))
    private val de = XmlStrings.read(File(dir, "values-de"))

    private val placeholder = Regex("""%(\d)\$([ds])""")
    private fun holders(s: String) = placeholder.findAll(s).map { it.value }.toSortedSet()

    @Test fun everyEnglishTextHasAGermanOne() {
        assertEquals("German missing", emptySet<String>(), en.strings.keys - de.strings.keys)
        assertEquals("German plural missing", emptySet<String>(), en.plurals.keys - de.plurals.keys)
        assertEquals("German text without English", emptySet<String>(), de.strings.keys - en.strings.keys)
        assertEquals("German plural without English", emptySet<String>(), de.plurals.keys - en.plurals.keys)
    }

    @Test fun placeholdersMatch() {
        val bad = en.strings.mapNotNull { (k, v) ->
            val d = de.strings[k] ?: return@mapNotNull null
            if (holders(v) != holders(d)) "$k: '$v' vs '$d'" else null
        } + en.plurals.flatMap { (k, forms) ->
            val all = forms.values.flatMap { holders(it) }.toSortedSet()
            (de.plurals[k] ?: emptyMap()).mapNotNull { (q, d) -> if (holders(d) - all != emptySet<String>()) "$k[$q]: '$d'" else null } +
                listOfNotNull((de.plurals[k]?.get("other"))?.let { d -> if (holders(d) != holders(forms["other"]!!)) "$k[other]: '$d'" else null })
        }
        assertEquals(bad.joinToString("\n"), emptyList<String>(), bad)
    }

    @Test fun pluralsHaveOneAndOther() {
        val bad = (en.plurals.map { "en ${it.key}" to it.value } + de.plurals.map { "de ${it.key}" to it.value })
            .filter { (_, f) -> "one" !in f || "other" !in f }.map { it.first }
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun placeholdersAreNumbered() {
        val unsupported = Regex("""%(?!\d\$[ds])[-+#0-9.]*[a-zA-Z%]""")
        val bad = listOf("en" to en, "de" to de).flatMap { (l, s) ->
            (s.strings + s.plurals.flatMap { (k, f) -> f.map { "$k[${it.key}]" to it.value } }).filter { (_, v) ->
                unsupported.containsMatchIn(v) || v.contains("\\'") || v.contains("\\\"")
            }.map { "$l ${it.key}: ${it.value}" }
        }
        assertEquals("use %1\$s / %1\$d; a % needs no escaping; ' and \" are written plainly:\n" + bad.joinToString("\n"),
            emptyList<String>(), bad)
    }

    @Test fun keysAreLowerSnakeCase() {
        val bad = (en.strings.keys + en.plurals.keys).filterNot { Regex("[a-z][a-z0-9_]*").matches(it) }
        assertTrue("keys: $bad", bad.isEmpty())
    }

    @Test fun formatLikeComposeResources() {
        assertEquals("Hike T3 – 12,5 km", XmlStrings.format("%1\$s – %2\$s", arrayOf("Hike T3", "12,5 km")))
        assertEquals("up to 28 %", XmlStrings.format("up to %1\$d %", arrayOf(28)))
        assertEquals("a\nb \\n", XmlStrings.unescape("a\\nb \\\\n"))
    }
}
