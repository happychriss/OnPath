package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-02: Settings → Diagnostics starts with the problem list folded to one line ("127 entries, 3 errors ▸"),
 * so the developer tools under it are reached without scrolling; a tap unfolds it. English like the whole page.
 */
class ProblemFoldTest {
    @Test fun oneLineWithEntriesAndErrors() {
        assertEquals("127 entries, 3 errors ▸", ProblemFold.summary(127, 3, open = false))
        assertEquals("127 entries, 3 errors ▾", ProblemFold.summary(127, 3, open = true))
        assertEquals("1 entry, 1 error ▸", ProblemFold.summary(1, 1, open = false))
        assertEquals("5 entries ▸", ProblemFold.summary(5, 0, open = false))
        assertEquals("No problems noticed.", ProblemFold.summary(0, 0, open = false))
    }

    @Test fun startsFoldedEveryVisit() {
        val src = File("src/main/java/com/onpath/app/ui/SettingsScreen.kt").readText()
        val list = src.substringAfter("private fun ProblemList(").substringBefore("\n}\n")
        assertTrue(list, list.contains("initiallyOpen: Boolean = false") && list.contains("remember { mutableStateOf(initiallyOpen) }"))
        assertTrue("the entries only when open", list.indexOf("if (!open) return") in 0 until list.indexOf("problems.take(15)"))
        assertTrue(src.contains("    problemsOpen: Boolean = false,"))
    }
}
