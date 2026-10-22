package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.9.1 crashed at start (CI E2E 2026-10-22): `init { recalc() }` routes the saved plan at once, and recalc() set a
 * Compose state declared further down – Kotlin had not created it yet (NullPointerException in the ViewModel's
 * constructor). Every property recalc() assigns before its background job must be declared above that init block.
 */
class ViewModelInitOrderTest {
    private val lines = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readLines()

    /** Class-level delegated property (`by mutableStateOf` …): null until its line has run – the crash kind. */
    private fun declLine(name: String): Int =
        lines.indexOfFirst { Regex("""^    (private |internal )?(var|val) $name\b.* by """).containsMatchIn(it) }

    @Test fun propertiesUsedByRecalcAtStartAreDeclaredBeforeTheInitThatCallsIt() {
        val initAt = lines.indices.first { lines[it].trim() == "init {" && lines.getOrNull(it + 1)?.trim() == "recalc()" }
        val start = lines.indexOfFirst { it.contains("private fun recalc()") }
        assertTrue("recalc() found", start >= 0)
        // the part that runs synchronously in the constructor: up to the background job
        val end = (start until lines.size).first { lines[it].contains("routeJob = viewModelScope.launch") }
        val assigned = (start..end).flatMap { i ->
            Regex("""(?<![.\w])([a-z][A-Za-z0-9]*)\s*=(?!=)""").findAll(lines[i]).map { it.groupValues[1] }.toList()
        }.toSet()
        val declaredLate = assigned.mapNotNull { n -> declLine(n).takeIf { it > initAt }?.let { "$n (line ${it + 1})" } }
        assertEquals("declared after the init block at line ${initAt + 1}", emptyList<String>(), declaredLate)
    }
}
