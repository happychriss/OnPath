package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

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

}
