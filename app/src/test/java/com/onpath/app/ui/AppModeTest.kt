package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppModeTest {
    @Test fun plainStartIsPlan() = assertEquals(AppMode.PLAN, AppMode.onStart(recordingRunning = false, unfinishedRecording = false))
    @Test fun runningTourOpensNavigate() = assertEquals(AppMode.NAVIGATE, AppMode.onStart(recordingRunning = true, unfinishedRecording = false))
    @Test fun unfinishedTourOpensNavigate() = assertEquals(AppMode.NAVIGATE, AppMode.onStart(recordingRunning = false, unfinishedRecording = true))

    @Test fun hikeStartsNavigateCloseIn() = assertEquals(17.0, AppMode.navStartZoom(com.onpath.app.routing.Activity.HIKE), 0.0)
    @Test fun everyBikeStartsNavigateAtSixteen() = com.onpath.app.routing.Activity.entries
        .filter { it != com.onpath.app.routing.Activity.HIKE }
        .forEach { assertEquals(it.name, 16.0, AppMode.navStartZoom(it), 0.0) }
}
