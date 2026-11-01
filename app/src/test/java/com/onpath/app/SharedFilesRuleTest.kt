package com.onpath.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review Arch 5: the files the web planner compiles from the app are ONE list on main (`app/web-shared-files.txt`),
 * read by the web build too. Every listed file must exist and import only what the web build provides – a shared file
 * that starts to use Android or another app class would break the planner unnoticed until cloud-plan merges.
 */
class SharedFilesRuleTest {
    private val list = File("web-shared-files.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    private val files = list.dropWhile { it != "[files]" }.drop(1).takeWhile { it != "[tests]" }
    private val tests = list.dropWhile { it != "[tests]" }.drop(1)
    private val main = File("src/main/java/com/onpath/app")

    /** Outside the app: what the web build stubs or brings (tools/routing/jvm-test/stubs, the web's stand-ins). */
    private val external = listOf(
        "kotlin", "java.", "javax.xml.parsers.", "org.xml.sax.", "org.json.", "com.google.gson.", "org.maplibre.geojson.",
        "btools.router.", "org.jetbrains.compose.resources.StringResource", "com.onpath.strings.",
        "android.content.Context", "android.content.Intent", "android.content.ActivityNotFoundException", "android.net.Uri",
    )
    /** App classes the web replaces with its own stand-ins. */
    private val standIns = setOf("debug.Problems", "debug.UserText", "recording.GpsStatus")

    @Test fun theListIsThere() {
        assertTrue(files.size > 50)
        assertTrue(tests.isNotEmpty())
    }

    @Test fun everySharedFileImportsOnlyWhatTheWebHas() {
        val listed = files.toSet()
        val bad = ArrayList<String>()
        for (f in files) {
            val src = File(main, f)
            if (!src.isFile) { bad += "$f: missing"; continue }
            for (line in src.readLines()) {
                val imp = Regex("""^import ([\w.]+)""").find(line)?.groupValues?.get(1) ?: continue
                if (imp.startsWith("com.onpath.app.")) {
                    val name = imp.removePrefix("com.onpath.app.")
                    if (name in standIns || name.startsWith("debug.")) continue
                    val pkg = name.substringBeforeLast('.').replace('.', '/'); val cls = name.substringAfterLast('.')
                    val dir = File(main, pkg)
                    val home = dir.listFiles { x -> x.isFile && x.name.endsWith(".kt") }?.firstOrNull { x ->
                        x.name == "$cls.kt" || Regex("""(class|object|interface|fun|val) $cls\b""").containsMatchIn(x.readText())
                    }
                    if (home == null || "$pkg/${home.name}" !in listed) bad += "$f: imports $imp (not shared)"
                } else if (external.none { imp.startsWith(it) }) bad += "$f: imports $imp (the web build has no such class)"
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun everySharedTestExists() {
        val missing = tests.filter { !File("src/test/java/com/onpath/app/$it").isFile }
        assertTrue("$missing", missing.isEmpty())
    }
}
