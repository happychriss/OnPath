package com.onpath.app.recording

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A recorded tour keeps the T level it was recorded at (bug found in the review 2026-10-26: every recorded tour was
 * stored as T3, so reopening a T2 hike set the user's level to T3 and its GPX export said "mountaineering").
 */
class NewTourSacTest {
    @Test fun theLevelSentWithTheStartIsStored() {
        assertEquals(2, RecordingService.newTourSac(extra = 2, stored = 4))
        assertEquals(5, RecordingService.newTourSac(extra = 5, stored = 2))
    }

    @Test fun withoutALevelTheStoredOneIsUsed() {
        assertEquals(2, RecordingService.newTourSac(extra = 0, stored = 2))
        assertEquals(1, RecordingService.newTourSac(extra = 9, stored = 1))
        assertEquals(5, RecordingService.newTourSac(extra = 0, stored = 8)) // kept in T1–T5
    }
}
