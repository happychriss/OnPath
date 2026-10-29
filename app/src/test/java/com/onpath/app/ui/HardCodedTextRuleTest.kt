package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Texts in English + German (owner, docs/i18n.md): a screen shows no hard-coded words – `Text("Save")` or
 * `contentDescription = "Close"` must be `str(Res.string.…)`. Allowed: Diagnostics and Developer tools (stay English
 * for bug reports), names and symbols that are the same in every language.
 */
class HardCodedTextRuleTest {
    private val sources = listOf("src/main/java/com/onpath/app/ui", "src/main/java/com/onpath/app/poi")
        .flatMap { File(it).walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }

    /** A literal with a word of 3+ letters as a shown text or an accessibility text. */
    private val literal = Regex("""(\bText\(|contentDescription\s*=\s*|\btext\s*=\s*)"([^"$]*[A-Za-z]{3,}[^"]*)"""")

    /** Functions whose texts stay English: Settings → Diagnostics and Developer tools. */
    private val englishFunctions = setOf("DiagnosticsSection", "LanguageRow", "DeveloperTools", "TestStyleRow",
        "MapCellsDebugList", "ProblemList")

    /** Same in every language: names, attribution, the placeholder of the map in screenshots. */
    private val sameEverywhere = setOf("OnPath", "Google", "© OpenStreetMap", "map")

    private fun hits() = sources.flatMap { f ->
        var fn = ""
        f.readLines().mapIndexedNotNull { i, line ->
            Regex("""\bfun\s+(\w+)\s*\(""").find(line)?.let { fn = it.groupValues[1] }
            val code = line.trim()
            if (code.startsWith("//") || code.startsWith("*")) return@mapIndexedNotNull null
            val m = literal.find(line.substringBefore(" // ")) ?: return@mapIndexedNotNull null
            val t = m.groupValues[2]
            if (fn in englishFunctions || t in sameEverywhere || t.startsWith("OnPath $")) null else "${f.name}:${i + 1}: $code"
        }
    }

    @Test fun noHardCodedTexts() {
        val h = hits()
        assertEquals("put the text in strings/…/strings_<area>.xml (EN + DE) and use str(Res.string.…):\n" +
            h.joinToString("\n"), emptyList<String>(), h)
    }
}
