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

    /** Meran pictures bug 2: after "Weiter" on "Unterbrochene Tour" the "Tour interrupted" notification stayed. */
    @Test fun everyAnswerClearsTheInterruptedNotification() {
        for (c in InterruptedLog.Choice.values()) {
            var cleared = 0
            InterruptedLog.chose("Vellau", c) { cleared++ }
            assertEquals(c.name, 1, cleared)
        }
        // the system restarting the service (background location) also ends it
        val svc = java.io.File("src/main/java/com/onpath/app/recording/RecordingService.kt").readText()
        assertTrue(svc.substringAfter("if (existing != null) {").substringBefore("} else {")
            .contains("TourInterruptedReceiver.cancelNote(this)"))
        val vm = java.io.File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        assertEquals(3, Regex("InterruptedLog\\.Choice\\.[A-Z]+, ::clearInterruptedNote\\)").findAll(vm).count())
    }

    @Test fun aFailingClearIsReportedNotThrown() {
        InterruptedLog.chose("Vellau", InterruptedLog.Choice.CONTINUE) { throw SecurityException("no") }
        assertTrue(Problems.snapshot().any { it.level == Problems.Level.WARN && it.message.contains("Tour interrupted") })
    }

    @Test fun deleteIsLoggedAsDelete() {
        InterruptedLog.chose("Vellau", InterruptedLog.Choice.DELETE) {}
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
