package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.9.1 crashed at start (CI E2E 2026-10-22): `init { recalc() }` routes the saved plan at once, and recalc() set a
 * Compose state declared further down – Kotlin had not created it yet (NullPointerException in the ViewModel's
 * constructor). Every property recalc() assigns before its background job must be declared above that init block.
 *
 * 0.9.0 field crash (owner phone 2026-10-22, 20:05): `init { refreshTours() }` launched a job on Dispatchers.IO that read
 * the `by lazy` property `planTimes` declared ~950 lines further down – the IO thread got there before the constructor
 * did ("Attempt to invoke interface method 'kotlin.Lazy.getValue()' on a null object reference"). A `by lazy` property
 * reachable from an init block (directly, through the class's own functions, or from a coroutine it launches) must be
 * declared above that init block.
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

    /** Index of the line closing the block opened on line [open] (string literals and // comments ignored). */
    private fun blockEnd(open: Int): Int {
        var depth = 0; var seen = false
        for (i in open until lines.size) {
            val code = lines[i].replace(Regex("\"(\\\\.|[^\"\\\\])*\""), "\"\"").substringBefore("//")
            for (c in code) {
                if (c == '{') { depth++; seen = true } else if (c == '}') depth--
            }
            if (seen && depth <= 0) return i
        }
        return lines.size - 1
    }

    @Test fun lazyPropertiesReachableFromAnInitBlockAreDeclaredAboveIt() {
        val lazies = lines.indices.mapNotNull { i ->
            Regex("""^    (?:private |internal )?val (\w+)\b.* by lazy""").find(lines[i])?.let { it.groupValues[1] to i }
        }.toMap()
        assertTrue("planTimes is a lazy (the 0.9.0 crash)", "planTimes" in lazies)
        // the class's own functions (4-space indent) with their bodies; overloads share one name (all bodies)
        val funs = HashMap<String, MutableList<IntRange>>()
        lines.indices.forEach { i ->
            Regex("""^    (?:private |internal |override |public )*fun (\w+)\(""").find(lines[i])?.let { m ->
                val end = if (lines[i].contains("{")) blockEnd(i) else
                    ((i + 1 until lines.size).firstOrNull { j -> Regex("""^    \S""").containsMatchIn(lines[j]) } ?: lines.size) - 1
                funs.getOrPut(m.groupValues[1]) { mutableListOf() } += i..end
            }
        }
        val ident = Regex("""(?<![.\w])([a-z]\w*)\b""")
        val late = mutableListOf<String>()
        lines.indices.filter { lines[it] == "    init {" }.forEach { initAt ->
            val seen = HashSet<String>()
            val queue = ArrayDeque(listOf(initAt..blockEnd(initAt)))
            val used = HashSet<String>()
            while (queue.isNotEmpty()) {
                val r = queue.removeFirst()
                for (i in r) for (m in ident.findAll(lines[i].substringBefore("//"))) {
                    val n = m.groupValues[1]
                    used += n
                    if (n in funs && seen.add(n)) funs[n]!!.forEach { queue += it }
                }
            }
            lazies.filter { (n, at) -> at > initAt && n in used }
                .forEach { (n, at) -> late += "$n (line ${at + 1}) reachable from init at line ${initAt + 1}" }
        }
        assertEquals("lazy declared below an init block that can reach it", emptyList<String>(), late)
    }
}
