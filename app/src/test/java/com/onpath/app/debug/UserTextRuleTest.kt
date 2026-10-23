package com.onpath.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Technical error texts do not belong in the app (owner 2026-10-23: "Update download failed: Unable to resolve host
 * api.github.com…", "Export failed: ENOSPC…"). A raw exception message or class name may only go to the problem log
 * (Problems / Log); the user gets one plain sentence – our own exception types through [UserText.of].
 *
 * Source scan: any line outside a Problems / Log call that reads an exception's message or class name fails, except
 * the few places in [allowed] (each with its reason).
 */
class UserTextRuleTest {
    /** The problem log itself: technical by design (Settings → Diagnostics, the export). */
    private val logFiles = setOf("Problems.kt")
    private val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()

    private val raw = Regex("""\b(e|it|t|ex|err|x|d|cause|throwable|error)\??\.(message|localizedMessage)\b|javaClass\.simpleName|localizedMessage""")

    /** file name → text on the line; none of them puts an exception's text in front of the user. */
    private val allowed = listOf(
        "UserText.kt" to "-> e.message", // the helper itself: only for our own exception types
        "AndroidBackupStore.kt" to """e.message?.contains("ENOSPC")""", // only tested, the text is not shown
        "RecordingService.kt" to "pr.javaClass.simpleName", // a dedupe key of a navigation prompt, not an exception
    )

    private fun hits() = sources.filter { it.name !in logFiles }.flatMap { f ->
        f.readLines().mapIndexedNotNull { i, line ->
            val l = line.trim()
            when {
                !raw.containsMatchIn(l) -> null
                l.startsWith("//") || l.startsWith("*") -> null
                l.contains("Problems.") || l.contains("Log.") -> null
                allowed.any { (file, text) -> f.name == file && l.contains(text) } -> null
                else -> "${f.name}:${i + 1}: $l"
            }
        }
    }

    @Test fun noExceptionTextReachesTheUser() {
        val h = hits()
        assertEquals("show a plain sentence (UserText.of for our own exceptions) and put the details in Problems.warn/error:\n" +
            h.joinToString("\n"), emptyList<String>(), h)
    }

    @Test fun everyAllowedPlaceStillExists() {
        allowed.forEach { (file, text) ->
            val f = sources.firstOrNull { it.name == file }
            assertTrue("$file is gone – drop it from the allow-list", f != null)
            assertTrue("$file no longer contains \"$text\" – drop it from the allow-list", f!!.readText().contains(text))
        }
    }

    @Test fun theScanFindsTheOldPatterns() {
        listOf(
            """message = "Export failed: ${'$'}{e.message ?: e.javaClass.simpleName}"""",
            """vm.message = "Diagnostics could not be created: ${'$'}{it.message}"""",
            """throw RoutingException("Cannot install routing profiles: ${'$'}{e.localizedMessage}")""",
        ).forEach { assertTrue(it, raw.containsMatchIn(it)) }
        assertTrue(!raw.containsMatchIn("""Text(state.message)"""))
    }
}
