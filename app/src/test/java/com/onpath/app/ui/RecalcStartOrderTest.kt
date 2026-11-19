package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The start-crash guard for `recalc`'s synchronous head in its new home (rebuild phase 2 piece 9b; the old guard,
 * `ViewModelInitOrderTest`, read the body in the view model). The big `init`'s first statement is `recalc()`: the head reads and
 * writes the view model's state through `PlanRecalcHost`. A delegated (`by mutableStateOf`) property declared BELOW that init has
 * a null `$delegate` while it runs – an NPE at app start (0.9.1). So every view-model member the head reaches must be declared
 * above that init, and the slice must read through at call time (a getter / setter pair, never a copy made at construction).
 */
class RecalcStartOrderTest {
    private val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readLines()
    private val slice = File("../state/src/main/kotlin/com/onpath/app/ui/plan/PlanRecalcSlice.kt").readLines()

    private val initAt = vm.indices.first { vm[it] == "    init {" && vm.getOrNull(it + 1)?.trim() == "recalc()" }
    private val declRe = Regex("""^    (?:private |internal |public )?(?:val|var) (\w+)\b""")

    /** First declaration line of every class-level property of the view model. */
    private val decl: Map<String, Int> = HashMap<String, Int>().also { m ->
        vm.forEachIndexed { i, l -> declRe.find(l)?.let { m.putIfAbsent(it.groupValues[1], i) } }
    }

    private val headRange: IntRange get() {
        val s = slice.indexOfFirst { it.trim() == "fun recalc() {" }
        val e = slice.indexOfFirst { it.contains("routeJob = scope.launchSafe") }
        assertTrue("head found in the slice", s in 0 until e)
        return s..e
    }

    /** slice forwarder name -> host member X, from `private var/val NAME …` followed by `get() = host.X` (own line or the next two). */
    private val forwarders: Map<String, String> get() = HashMap<String, String>().also { m ->
        slice.indices.forEach { i ->
            val n = Regex("""^    private (?:val|var) (\w+)\b""").find(slice[i])?.groupValues?.get(1) ?: return@forEach
            val text = slice.subList(i, minOf(i + 3, slice.size)).joinToString(" ")
            Regex("""get\(\)\s*=\s*host\.(\w+)""").find(text)?.let { m[n] = it.groupValues[1] }
        }
    }

    private fun hostAdapter(marker: String): List<String> {
        val a = vm.indexOfFirst { it.contains(marker) }
        assertTrue("adapter found: $marker", a >= 0)
        val b = (a + 1 until vm.size).first { vm[it] == "        }," }
        return vm.subList(a, b)
    }

    private fun above(what: String, name: String, via: String) {
        val at = decl[name] ?: return // a function: nothing delegated to be null
        assertTrue("$name (AppViewModel.kt line ${at + 1}, $via) must be declared above the init at line ${initAt + 1}: $what", at < initAt)
    }

    @Test fun everyViewModelMemberTheHeadReachesThroughTheHostIsDeclaredAboveTheInitThatCallsRecalc() {
        val head = headRange.map { slice[it] }.joinToString("\n")
        val fw = forwarders
        val used = HashSet<String>()
        fw.forEach { (name, x) -> if (Regex("""(?<![.\w])$name\b""").containsMatchIn(head)) used += x }
        Regex("""host\.(\w+)""").findAll(head).forEach { used += it.groupValues[1] }
        assertTrue("the head reads / writes the host's route, highlight and planReach: $used", used.containsAll(listOf("route", "highlight", "planReach", "mode")))
        val adapter = hostAdapter("host = object : com.onpath.app.ui.plan.PlanRecalcHost {")
        for (x in used) {
            val i = adapter.indexOfFirst { Regex("""override (?:var|val|fun) $x\b""").containsMatchIn(it) }
            assertTrue("host member $x has an adapter line", i >= 0)
            val body = adapter.subList(i, (i + 1 until adapter.size).firstOrNull { adapter[it].contains("override ") } ?: adapter.size).joinToString(" ")
            val y = Regex("""this@AppViewModel\.(\w+)""").find(body)?.groupValues?.get(1)
                ?: Regex("""(?:get\(\)\s*)?=\s*(\w+)\.""").find(body)?.groupValues?.get(1)
                ?: error("no view-model member found in the adapter of $x: $body")
            above("host.$x", y, "host.$x")
            // a member that is itself a getter onto another slice (`val mode get() = tourSession.mode`): that slice too
            val own = vm.getOrNull(decl[y] ?: -1)
            Regex("""get\(\)\s*=\s*(\w+)\.""").find(own.orEmpty())?.groupValues?.get(1)?.let { z -> above("$y reads $z", z, "via $y") }
        }
    }

    @Test fun whatPerformReachesSynchronouslyFromTheHeadIsDeclaredAboveTheInitToo() {
        // perform(effects) in the head: ClearRoute / NoCorridor through PlanRunHost (the other effects are not run by the head)
        val run = hostAdapter("host = object : com.onpath.app.ui.plan.PlanRunHost {")
        for (m in listOf("clearRoute", "noCorridor")) {
            val line = run.first { it.contains("override fun $m(") }
            val names = Regex("""\b([a-z]\w*)\b""").findAll(line.substringAfter("$m()")).map { it.groupValues[1] }.toSet()
            val props = names.filter { it in decl }
            assertTrue("$m touches view-model properties: $line", props.isNotEmpty())
            props.forEach { above("PlanRunHost.$m", it, "PlanRunHost.$m") }
        }
    }

    @Test fun theSliceReadsThroughAtCallTimeNeverACopyMadeAtConstruction() {
        val fw = forwarders
        assertTrue("forwarders found: $fw", fw.keys.containsAll(listOf("route", "routeError", "highlight", "planReach", "planResult", "mode")))
        for (n in fw.keys) {
            val i = slice.indexOfFirst { Regex("""^    private (?:val|var) $n\b""").containsMatchIn(it) }
            assertFalse("slice line ${i + 1}: $n is a copy made at construction (`= host.`)", !slice[i].contains("get()") && Regex("""^    private (?:val|var) $n\b[^=]*=\s*host\.""").containsMatchIn(slice[i]))
        }
        slice.forEachIndexed { i, l ->
            assertFalse("slice line ${i + 1}: a constructor-time copy of the host: $l", !l.contains("get()") && Regex("""^    (?:private )?(?:val|var) \w+(?::[^=]+)? = host\.""").containsMatchIn(l))
        }
        // the writable ones are getter / setter pairs
        for (n in listOf("route", "routeError", "highlight", "planReach", "planResult")) {
            val i = slice.indexOfFirst { Regex("""^    private var $n\b""").containsMatchIn(it) }
            assertTrue("$n is a var with get() and set() reading through", i >= 0 && slice[i + 1].contains("get() = host.$n") && slice[i + 2].contains("set(v) { host.$n = v }"))
        }
    }

    @Test fun theSliceIsDeclaredAboveTheInitAndRecalcIsItsFirstStatement() {
        val at = vm.indexOfFirst { it.contains("private val planRecalc: com.onpath.app.ui.plan.PlanRecalcSlice") }
        assertTrue("planRecalc (line ${at + 1}) above the init at line ${initAt + 1}", at in 0 until initAt)
        assertEquals("recalc() is the init's first statement", "recalc()", vm[initAt + 1].trim())
    }
}
