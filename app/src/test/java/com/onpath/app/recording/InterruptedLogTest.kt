package com.onpath.app.recording

import com.onpath.app.debug.Problems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/** A-015: the log said "user asked to continue" although the user chose Delete in the unfinished-tour dialog. */
class InterruptedLogTest {
    @Before fun setUp() {
        Problems.init(Files.createTempDirectory("problems").toFile())
        Problems.clear()
    }

    @Test fun restartLineClaimsNoChoice() {
        for (boot in listOf(true, false)) {
            val line = InterruptedLog.interruptedLine("Vellau", boot)
            assertFalse(line, line.contains("asked to continue"))
            assertFalse(line, line.contains("user chose"))
            assertTrue(line, line.contains(if (boot) "a restart" else "an update"))
        }
    }

    @Test fun deleteIsLoggedAsDelete() {
        InterruptedLog.chose("Vellau", InterruptedLog.Choice.DELETE)
        val e = Problems.snapshot().single()
        assertEquals("recording", e.area)
        assertEquals(Problems.Level.INFO, e.level)
        assertEquals("Interrupted recording of 'Vellau': user chose Delete (recycle bin)", e.message)
    }

    @Test fun everyChoiceSaysWhatWasChosen() {
        assertTrue(InterruptedLog.choiceLine("T", InterruptedLog.Choice.CONTINUE).endsWith("user chose Continue"))
        assertTrue(InterruptedLog.choiceLine("T", InterruptedLog.Choice.SAVE).endsWith("user chose Save"))
        assertTrue(InterruptedLog.choiceLine("T", InterruptedLog.Choice.DELETE).contains("user chose Delete"))
    }
}
