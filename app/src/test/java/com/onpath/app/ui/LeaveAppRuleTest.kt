package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Flow map N4 (owner 2026-11-06): Android's back in Navigate while a tour records asks "App verlassen? Deine Tour wird
 * weiter aufgezeichnet." (Bleiben / Verlassen) instead of sending the app away – a novice took "app gone" for "tour gone".
 */
class LeaveAppRuleTest {
    @Test fun asksOnlyWhileRecordingAndNothingElseClosesFirst() {
        assertTrue(LeaveAppRule.backAsks(recording = true, barOpen = false, sheetOpen = false))
        assertFalse("no tour: back leaves as in every app", LeaveAppRule.backAsks(recording = false, barOpen = false, sheetOpen = false))
        assertFalse("the unlocked bar closes first", LeaveAppRule.backAsks(recording = true, barOpen = true, sheetOpen = false))
        assertFalse("the activity sheet closes first", LeaveAppRule.backAsks(recording = true, barOpen = false, sheetOpen = true))
    }

    @Test fun theQuestionIsAFrageWithStayAndLeave() {
        var stayed = false; var left = false
        val m = leaveAppMessage(onStay = { stayed = true }, onLeave = { left = true })
        assertEquals(MessageKind.FRAGE, m.kind)
        assertEquals(2, m.buttons.size)
        m.buttons[0].onClick(); m.buttons[1].onClick()
        assertTrue(stayed && left)
    }

    @Test fun leavingSendsTheAppToTheBackgroundAndKeepsTheTour() {
        val nav = File("src/main/java/com/onpath/app/ui/NavigateScreen.kt").readText()
        assertTrue(nav.contains("activity?.moveTaskToBack(true)"))
        assertFalse("never finish the app", nav.substringAfter("onLeaveApp = {").substringBefore("},").contains("finish"))
        assertTrue("the question comes first in Navigate's one box", nav.contains("Messages.pick(listOf(\n            if (leaveAsk) leaveAppMessage("))
        for (dir in listOf("values", "values-de")) {
            val xml = File("../strings/src/commonMain/composeResources/$dir/strings_navigate.xml").readText()
            for (k in listOf("nav_leave_title", "nav_leave_text", "nav_leave_stay", "nav_leave_go")) assertTrue("$dir $k", xml.contains("name=\"$k\""))
        }
    }
}
