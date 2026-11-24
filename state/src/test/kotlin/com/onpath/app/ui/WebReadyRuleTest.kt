package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The files named in `state/web-ready.txt` are the ones the planner (TeaVM, no coroutines, no Compose runtime jar) may take
 * as they are, with a stand-in for the five Compose runtime names (docs/design/umbau-phase-2.md §2.2, rules 1 + 2; what the
 * planner's owner needs: docs/team/items/2026-11-19-how-we-split-web.md). Checks on every listed file:
 *  1. imports: only `com.onpath.app.*`, `com.onpath.strings.*`, `java.*` / `kotlin.*`, `org.json.*`, the two text-resource
 *     classes the engine has a stand-in for, and the five Compose runtime names; 2. no coroutine, no `suspend`, no thread /
 *     lock / file / wall clock used directly; 3. the closure: every `:state` file it uses is listed too; 4. every `:core`
 *     file it uses is one the planner compiles (not in `core-excludes.txt`, not replaced by a stand-in).
 * The checks work on the source text (names, not types). They CANNOT see: a reference inside a string template
 * (`"${'$'}{Foo.x}"`), a wildcard import of `:state` / `:core` (that is a failure: import by name), two top-level declarations
 * of one name in one package (the first wins), the type behind an inferred value (`x.foo()` counts as a member call; only
 * extension functions are matched after a dot), and whether the engine's stand-in really implements what a file calls
 * (only `Problems` is checked by member name). The test reads the list from this module's folder (a Gradle test runs there).
 */
class WebReadyRuleTest {
    private val runtimeNames = setOf("mutableStateOf", "getValue", "setValue", "mutableStateListOf", "mutableIntStateOf",
        "snapshots.SnapshotStateList", "toMutableStateList")
    // what the engine brings besides the JDK (web/engine/build.gradle.kts, read 2026-11-19): `org.json:json` as a dependency and the
    // stand-in web/engine/src/main/kotlin/org/jetbrains/compose/resources/StringResource.kt (StringResource + PluralStringResource).
    // SnapshotStateList / toMutableStateList: in the planner's Compose stand-in since d82eb227 (laptop 2026-11-20), so allowed.
    private val allowedPrefixes = listOf(
        "com.onpath.app.", "com.onpath.strings.", "java.", "kotlin.", "org.json.",
        "org.jetbrains.compose.resources.StringResource", "org.jetbrains.compose.resources.PluralStringResource",
    )

    private fun listed(): List<String> = File("web-ready.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test fun theListIsNotEmptyAndItsFilesExist() {
        val files = listed()
        assertTrue("state/web-ready.txt names no file", files.isNotEmpty())
        for (f in files) assertTrue("$f is listed but not in :state", File("src/main/kotlin/com/onpath/app/$f").isFile)
    }

    @Test fun listedFilesImportOnlyWhatThePlannerHas() {
        val bad = mutableListOf<String>()
        for (f in listed()) {
            File("src/main/kotlin/com/onpath/app/$f").readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("import ")) {
                    val name = code.removePrefix("import ").substringBefore(" as ").trim()
                    val ok = name.startsWith("androidx.compose.runtime.") && name.removePrefix("androidx.compose.runtime.") in runtimeNames ||
                        allowedPrefixes.any { name.startsWith(it) }
                    if (!ok) bad += "$f:${i + 1} import $name"
                }
                if ("kotlinx.coroutines" in code || ("androidx." in code && !code.startsWith("import androidx.compose.runtime."))) bad += "$f:${i + 1} $code"
            }
        }
        assertEquals("not web-ready (docs/design/umbau-phase-2.md §2.2): $bad", emptyList<String>(), bad)
    }

    // ---- bans ------------------------------------------------------------------------------------------------------

    /** What a listed file may not use, each with the reason (shown in the failure message). Matched on code without comments / strings. */
    private val bans = listOf(
        Regex("""\bsuspend\b""") to "suspend: a coroutine – TeaVM's coroutines are fragile and big (use a port the page implements, or plain state + a reducer)",
        Regex("""\bThread(Local)?\b""") to "Thread: the planner page is single-threaded",
        Regex("""java\.util\.concurrent(?!\.atomic)""") to "java.util.concurrent (not .atomic: core files the engine compiles already use AtomicInteger): no threads in the page, no executors / locks",
        Regex("""\bsynchronized\s*\(|@Synchronized|@Volatile""") to "synchronized / @Volatile: no threads in the page, a lock is dead weight there",
        Regex("""java\.io\.File|\bFile\(""") to "java.io.File: the page has no file system (a port reads / writes)",
        Regex("""System\.(currentTimeMillis|nanoTime)""") to "System clock used directly: a Clock port makes the state testable and the page's time its own",
    )

    @Test fun listedFilesUseNoThreadLockFileOrClockDirectly() {
        val bad = ArrayList<String>()
        for (f in listed()) code(File("src/main/kotlin/com/onpath/app/$f")).forEach { (n, line) ->
            for ((re, why) in bans) if (re.containsMatchIn(line)) bad += "$f:$n ${line.trim()}  <- $why"
        }
        assertEquals("a listed file may not use these (docs/design/umbau-phase-2.md §2.2):\n" + bad.joinToString("\n"), emptyList<String>(), bad)
    }

    // ---- the closure -----------------------------------------------------------------------------------------------

    /** The file's lines (number, text) without block comments, string / char literals and line comments. Import lines stay. */
    private fun code(file: File): List<Pair<Int, String>> {
        fun blank(m: MatchResult) = "\n".repeat(m.value.count { it == '\n' })
        val text = file.readText().replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), ::blank)
            .replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), ::blank)
        return text.lines().mapIndexed { i, l ->
            i + 1 to l.replace(Regex("\"(\\\\.|[^\"\\\\])*\""), "\"\"").replace(Regex("'(\\\\.|[^'\\\\])'"), "''").substringBefore("//")
        }
    }

    private class Decl(val pkg: String, val name: String, val rel: String, val ext: Boolean, val lower: Boolean)

    private val stateRoot = File("src/main/kotlin/com/onpath/app")
    private val coreRoot = File("../core/src/main/kotlin/com/onpath/app")

    private fun kt(dir: File): List<String> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()

    private fun packageOf(lines: List<String>) = lines.firstNotNullOfOrNull { Regex("""^package ([\w.]+)""").find(it)?.groupValues?.get(1) } ?: ""

    /** Top-level, non-private declarations of every file under [root] (type names, functions, properties; with the file's package). */
    private fun decls(root: File): List<Decl> = kt(root).flatMap { rel ->
        val lines = code(File(root, rel)).map { it.second }
        val pkg = packageOf(lines)
        val mods = """(?:(?:public|internal|data|sealed|enum|abstract|open|annotation|value|inline|const|fun|infix|operator|tailrec|lateinit)\s+)*"""
        val typeRe = Regex("^$mods(?:class|object|interface|typealias)\\s+(\\w+)")
        val memberRe = Regex("^$mods(?:fun|val|var)\\s+(?:<[^>]*>\\s*)?(?:([\\w.<>?]+)\\.)?(\\w+)\\s*[(:=<]")
        lines.mapNotNull { l ->
            if (l.isEmpty() || l[0].isWhitespace() || l.startsWith("private ") || l.startsWith("@")) null
            else typeRe.find(l)?.let { Decl(pkg, it.groupValues[1], rel, false, false) }
                ?: memberRe.find(l)?.let { Decl(pkg, it.groupValues[2], rel, it.groupValues[1].isNotEmpty(), true) }
        }
    }

    private val stateDecls by lazy { decls(stateRoot) }
    private val coreDecls by lazy { decls(coreRoot) }
    private val standIns by lazy { kt(File("../web/engine/src/main/kotlin/com/onpath/app")).toSet() }
    private val excludes by lazy {
        File("../web/engine/core-excludes.txt").readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private class Use(val line: Int, val what: String, val inState: Boolean, val decl: Decl)

    /** What the `:state` file [rel] uses of other `:state` / `:core` files: by its imports and by same-package names. */
    private fun uses(rel: String): List<Use> {
        val lines = code(File(stateRoot, rel))
        val pkg = packageOf(lines.map { it.second })
        val out = ArrayList<Use>()
        fun find(p: String, n: String): Use? =
            stateDecls.firstOrNull { it.pkg == p && it.name == n && it.rel != rel }?.let { Use(0, n, true, it) }
                ?: coreDecls.firstOrNull { it.pkg == p && it.name == n }?.let { Use(0, n, false, it) }
        val imported = HashSet<String>()
        for ((n, l) in lines) {
            val imp = Regex("""^\s*import ([\w.]+)(?: as (\w+))?""").find(l) ?: continue
            val segs = imp.groupValues[1].split('.')
            imported += imp.groupValues[2].ifEmpty { segs.last() }
            if (!imp.groupValues[1].startsWith("com.onpath.app.")) continue
            if (l.trim().endsWith(".*")) { out += Use(n, "wildcard import of ${imp.groupValues[1]}.* (cannot be resolved: import by name)", true, Decl("", "*", rel, false, false)); continue }
            // longest package first: `a.b.Outer.Inner` is the class Outer's nested one
            (segs.size - 1 downTo 1).firstNotNullOfOrNull { k -> find(segs.take(k).joinToString("."), segs[k]) }
                ?.let { out += Use(n, imp.groupValues[1], it.inState, it.decl) }
        }
        // a name written out in full in the code (`com.onpath.app.data.RoutingDataCheck.differs(…)` – no import): resolved like an
        // import (laptop 2026-11-20: PlanRecalcState used an unlisted :state file this way and only the planner's build saw it)
        for ((n, l) in lines) {
            if (l.trimStart().startsWith("import ") || l.trimStart().startsWith("package ")) continue
            for (m in Regex("""\bcom\.onpath\.app(?:\.\w+)+""").findAll(l)) {
                val segs = m.value.split('.')
                (segs.size - 1 downTo 3).firstNotNullOfOrNull { k -> find(segs.take(k).joinToString("."), segs[k]) }
                    ?.let { out += Use(n, m.value, it.inState, it.decl) }
            }
        }
        for ((n, l) in lines) {
            if (l.trimStart().startsWith("import ") || l.trimStart().startsWith("package ")) continue
            for (m in Regex("""[A-Za-z_]\w*""").findAll(l)) {
                val t = m.value
                if (t in imported) continue
                val dot = m.range.first > 0 && l[m.range.first - 1] == '.'
                val cands = stateDecls.filter { it.pkg == pkg && it.name == t && it.rel != rel }.map { true to it } +
                    coreDecls.filter { it.pkg == pkg && it.name == t }.map { false to it }
                // a type name anywhere; a function / property: a plain call, or an extension function after a dot
                cands.firstOrNull { (_, d) -> if (!d.lower) !dot else if (d.ext) dot else !dot }?.let { out += Use(n, t, it.first, it.second) }
            }
        }
        return out
    }

    @Test fun listedFilesNeedOnlyListedStateFiles() {
        val listed = listed().toSet()
        val bad = ArrayList<String>()
        for (f in listed) for (u in uses(f)) {
            if (u.decl.name == "*") bad += "$f:${u.line} ${u.what}"
            else if (u.inState && u.decl.rel !in listed) bad += "$f:${u.line} uses ${u.what} (declared in ${u.decl.rel}, which is not listed: a slice that needs an unlisted file is not takeable)"
        }
        assertEquals("closure of state/web-ready.txt (list that file too, or cut the dependency):\n" + bad.distinct().joinToString("\n"), emptyList<String>(), bad.distinct())
    }

    @Test fun listedFilesUseOnlyCoreFilesThePlannerCompiles() {
        val standIn = File("../web/engine/src/main/kotlin/com/onpath/app/debug/Problems.kt").readText()
        val declared = Regex("""\b(?:fun|val|var|class|object|interface)\s+(?:[\w.]+\.)?(\w+)""").findAll(standIn).map { it.groupValues[1] }.toSet() - "Problems"
        val bad = ArrayList<String>()
        for (f in listed()) {
            for (u in uses(f)) {
                if (u.inState) continue
                val rel = u.decl.rel
                when {
                    rel in excludes -> bad += "$f:${u.line} uses ${u.what}: core/$rel is in web/engine/core-excludes.txt (the planner does not compile it)"
                    // the one stand-in a slice may meet is Problems: it is smaller than core's, so its members are checked below
                    rel in standIns && rel != "debug/Problems.kt" -> bad += "$f:${u.line} uses ${u.what}: core/$rel is replaced by a stand-in in web/engine (the planner gets another class)"
                }
            }
            for ((n, l) in code(File(stateRoot, f))) for (m in Regex("""\bProblems\.(\w+)""").findAll(l))
                if (m.groupValues[1] !in declared) bad += "$f:$n Problems.${m.groupValues[1]}: the planner's Problems stand-in lacks it"
        }
        assertEquals("a listed file uses core code the planner does not compile as it is:\n" + bad.distinct().joinToString("\n"), emptyList<String>(), bad.distinct())
    }
}
