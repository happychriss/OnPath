package com.onpath.app.recording

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A recorded tour keeps the T level it was recorded at – a fixed level would set the user's level when the tour is
 * reopened (a T2 hike → T3) and mislabel its GPX export ("mountaineering").
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
