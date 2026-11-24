package com.onpath.app.recording

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wire strings of the service's Intents, written out as literals – copied from `RecordingService` as it was before
 * they moved to `:core` (`RecordingWire`): the 18 actions, 8 extras and 2 SAY values. A pending notification button of an
 * older build and a sticky restart carry exactly these; a changed value is a changed contract. Both the new home and the
 * service's alias are compared, so neither can drift.
 */
class RecordingWireTest {
    private class Wire(val name: String, val literal: String, val inWire: String, val inService: String)

    private val all = listOf(
        Wire("ACTION_START", "com.onpath.app.START", RecordingWire.ACTION_START, RecordingService.ACTION_START),
        Wire("ACTION_PAUSE", "com.onpath.app.PAUSE", RecordingWire.ACTION_PAUSE, RecordingService.ACTION_PAUSE),
        Wire("ACTION_RESUME", "com.onpath.app.RESUME", RecordingWire.ACTION_RESUME, RecordingService.ACTION_RESUME),
        Wire("ACTION_STOP", "com.onpath.app.STOP", RecordingWire.ACTION_STOP, RecordingService.ACTION_STOP),
        Wire("ACTION_FOLLOW_CHANGED", "com.onpath.app.FOLLOW_CHANGED", RecordingWire.ACTION_FOLLOW_CHANGED, RecordingService.ACTION_FOLLOW_CHANGED),
        Wire("ACTION_NAVIGATING", "com.onpath.app.NAVIGATING", RecordingWire.ACTION_NAVIGATING, RecordingService.ACTION_NAVIGATING),
        Wire("EXTRA_ON", "on", RecordingWire.EXTRA_ON, RecordingService.EXTRA_ON),
        Wire("ACTION_SETTINGS", "com.onpath.app.SETTINGS", RecordingWire.ACTION_SETTINGS, RecordingService.ACTION_SETTINGS),
        Wire("ACTION_TAKE_ROUTE", "com.onpath.app.TAKE_ROUTE", RecordingWire.ACTION_TAKE_ROUTE, RecordingService.ACTION_TAKE_ROUTE),
        Wire("ACTION_KEEP_ROUTE", "com.onpath.app.KEEP_ROUTE", RecordingWire.ACTION_KEEP_ROUTE, RecordingService.ACTION_KEEP_ROUTE),
        Wire("ACTION_TAKE_VIA", "com.onpath.app.TAKE_VIA", RecordingWire.ACTION_TAKE_VIA, RecordingService.ACTION_TAKE_VIA),
        Wire("ACTION_LEAD_THERE", "com.onpath.app.LEAD_THERE", RecordingWire.ACTION_LEAD_THERE, RecordingService.ACTION_LEAD_THERE),
        Wire("ACTION_JUST_RECORD", "com.onpath.app.JUST_RECORD", RecordingWire.ACTION_JUST_RECORD, RecordingService.ACTION_JUST_RECORD),
        Wire("ACTION_CANCEL_LEAD", "com.onpath.app.CANCEL_LEAD", RecordingWire.ACTION_CANCEL_LEAD, RecordingService.ACTION_CANCEL_LEAD),
        Wire("ACTION_LEAD_FAILED_OK", "com.onpath.app.LEAD_FAILED_OK", RecordingWire.ACTION_LEAD_FAILED_OK, RecordingService.ACTION_LEAD_FAILED_OK),
        Wire("ACTION_JOIN_HERE", "com.onpath.app.JOIN_HERE", RecordingWire.ACTION_JOIN_HERE, RecordingService.ACTION_JOIN_HERE),
        Wire("EXTRA_QUESTION", "question", RecordingWire.EXTRA_QUESTION, RecordingService.EXTRA_QUESTION),
        Wire("ACTION_PROPOSE_SWITCH", "com.onpath.app.PROPOSE_SWITCH", RecordingWire.ACTION_PROPOSE_SWITCH, RecordingService.ACTION_PROPOSE_SWITCH),
        Wire("EXTRA_TIME_S", "timeS", RecordingWire.EXTRA_TIME_S, RecordingService.EXTRA_TIME_S),
        Wire("EXTRA_CHECK_NOW", "checkNow", RecordingWire.EXTRA_CHECK_NOW, RecordingService.EXTRA_CHECK_NOW),
        Wire("ACTION_SAY", "com.onpath.app.SAY", RecordingWire.ACTION_SAY, RecordingService.ACTION_SAY),
        Wire("EXTRA_SAY", "say", RecordingWire.EXTRA_SAY, RecordingService.EXTRA_SAY),
        Wire("SAY_ARRIVED", "arrived", RecordingWire.SAY_ARRIVED, RecordingService.SAY_ARRIVED),
        Wire("SAY_NO_DESTINATION", "noDestination", RecordingWire.SAY_NO_DESTINATION, RecordingService.SAY_NO_DESTINATION),
        Wire("ACTION_CHECK_NOW", "com.onpath.app.CHECK_NOW", RecordingWire.ACTION_CHECK_NOW, RecordingService.ACTION_CHECK_NOW),
        Wire("EXTRA_CONTINUE_ID", "continueId", RecordingWire.EXTRA_CONTINUE_ID, RecordingService.EXTRA_CONTINUE_ID),
        Wire("EXTRA_ACTIVITY", "activity", RecordingWire.EXTRA_ACTIVITY, RecordingService.EXTRA_ACTIVITY),
        Wire("EXTRA_SAC", "sac", RecordingWire.EXTRA_SAC, RecordingService.EXTRA_SAC),
        Wire("ACTION_ACTIVITY_CHANGED", "com.onpath.app.ACTIVITY_CHANGED", RecordingWire.ACTION_ACTIVITY_CHANGED, RecordingService.ACTION_ACTIVITY_CHANGED),
    )

    @Test fun everyActionAndExtraStringIsWhatItAlwaysWas() {
        assertEquals("19 actions + 8 extras + 2 SAY values", 29, all.size)
        for (w in all) {
            assertEquals("${w.name} in RecordingWire", w.literal, w.inWire)
            assertEquals("${w.name} on RecordingService", w.literal, w.inService)
        }
        assertEquals("every name once", 29, all.map { it.name }.toSet().size)
    }
}
