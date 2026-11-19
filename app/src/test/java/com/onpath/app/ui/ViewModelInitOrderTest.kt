package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Declaration order in AppViewModel (a property declared below an init block is still null while it runs):
 * - `init { recalc() }` routes the saved plan at once, so every property recalc()'s head touches before its background job
 *   must be declared above that init block (else a NullPointerException in the constructor): since the route run lives
 *   in `PlanRecalcSlice` (rebuild phase 2 piece 9b) that guard is [RecalcStartOrderTest] (the owner's yes 2026-11-19);
 * - a `by lazy` property reachable from an init block (directly, through the class's own functions, or from a coroutine
 *   it launches, which may run before the constructor got there) must be declared above that init block.
 */
class ViewModelInitOrderTest {
    private val lines = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readLines()

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

    /** Like [blockEnd], but a block opens with `(` or `{` – a slice's constructor call with its port / host `object :` literals. */
    private fun initialiserEnd(open: Int): Int {
        var depth = 0; var seen = false
        for (i in open until lines.size) {
            val code = lines[i].replace(Regex("\"(\\\\.|[^\"\\\\])*\""), "\"\"").substringBefore("//")
            for (c in code) {
                if (c == '{' || c == '(') { depth++; seen = true } else if (c == '}' || c == ')') depth--
            }
            if (seen && depth <= 0) return i
        }
        return lines.size - 1
    }

    /**
     * Everything the construction can reach must exist when an init block runs: a `by lazy` and a plain `val x = …` (a slice,
     * its store / host port, `notices` …) reachable from an init block – directly, through the class's own functions, through
     * the bodies of other such properties (the port and host `object :` literals read the lazies and properties they use
     * when called) – is declared above that init block. `recalc()` at construction reaches `planRoute` through the
     * forwarders (`runCauses`, `activity`, `planFactors` …) and so its ports' lazies (`planTimes`, `mapData` …).
     */
    @Test fun lazyPropertiesAndSlicesReachableFromAnInitBlockAreDeclaredAboveIt() {
        val lazies = lines.indices.mapNotNull { i ->
            Regex("""^    (?:private |internal )?val (\w+)\b.* by lazy""").find(lines[i])?.let { it.groupValues[1] to i }
        }.toMap()
        assertTrue("planTimes is a lazy (the 0.9.0 crash)", "planTimes" in lazies)
        // plain initialised properties (not delegated, not a getter forwarder): the slices and their port / host objects
        val plains = lines.indices.mapNotNull { i ->
            val m = Regex("""^    (?:private |internal |public )?(?:val|var) (\w+)(?::[^=]+)? = """).find(lines[i]) ?: return@mapNotNull null
            if (" by " in lines[i] || "get()" in lines[i]) return@mapNotNull null
            // only the slices and their ports / hosts: built by a call with `object :` literals or named `…Slice(` / `…Flow(`
            val text = lines.subList(i, minOf(initialiserEnd(i), i + 80) + 1).joinToString("\n")
            if ("object :" in text || Regex("""= [\w.]*(Slice|Flow|NoticeBoard|RecentPlans)\(""").containsMatchIn(lines[i])) m.groupValues[1] to i else null
        }.toMap()
        assertTrue("planRoute is a plain property (the slice of piece 8)", "planRoute" in plains)
        assertTrue("planRun is a plain property (the slice of piece 9)", "planRun" in plains)
        val props = lazies + plains
        val propRange = HashMap<String, IntRange>()
        props.forEach { (n, at) -> propRange[n] = at..(if (n in lazies) blockEnd(at) else initialiserEnd(at)) }
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
        // The walk over-approximates (a whole port object, not the one method that is called; a coroutine's Main part). Named
        // exceptions, each with why it is safe: `notices` – the start-up check says its note only inside withContext(Main), which
        // runs after the constructor; `pairing` – read by a host object's method that no construction-time code calls.
        // One EDGE, not a name: the walk skips the single line of `routesHost.syncMarkSeen` (it reads `syncDriver`, declared below the
        // inits): only `openTour` calls it (a tap in My routes) and no init reaches `openTour` – asserted per init below.
        val notReachedAtConstruction = setOf("notices", "pairing")
        val skippedEdge = "override fun syncMarkSeen(t: Tour) = this@AppViewModel.syncMarkSeen(t)"
        assertTrue("the skipped edge exists", lines.any { it.trim() == skippedEdge })
        val late = mutableListOf<String>()
        lines.indices.filter { lines[it] == "    init {" }.forEach { initAt ->
            val seen = HashSet<String>()
            val queue = ArrayDeque(listOf(initAt..blockEnd(initAt)))
            val used = HashSet<String>()
            while (queue.isNotEmpty()) {
                val r = queue.removeFirst()
                for (i in r) for (m in ident.findAll(lines[i].substringBefore("//").takeUnless { it.trim() == skippedEdge } ?: "")) {
                    val n = m.groupValues[1]
                    used += n
                    if (n in funs && seen.add("f$n")) funs[n]!!.forEach { queue += it }
                    if (n in propRange && seen.add("p$n")) queue += propRange[n]!!
                }
            }
            assertTrue("no init reaches openTour (init at line ${initAt + 1})", "fopenTour" !in seen)
            props.filter { (n, at) -> at > initAt && n in used && n !in notReachedAtConstruction }
                .forEach { (n, at) -> late += "$n (line ${at + 1}) reachable from init at line ${initAt + 1}" }
        }
        assertEquals("lazy / slice declared below an init block that can reach it", emptyList<String>(), late)
    }
}
