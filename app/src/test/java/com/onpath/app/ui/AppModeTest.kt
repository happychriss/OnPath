package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppModeTest {
    @Test fun plainStartIsPlan() = assertEquals(AppMode.PLAN, AppMode.onStart(recordingRunning = false, unfinishedRecording = false))
    @Test fun runningTourOpensNavigate() = assertEquals(AppMode.NAVIGATE, AppMode.onStart(recordingRunning = true, unfinishedRecording = false))
    @Test fun unfinishedTourOpensNavigate() = assertEquals(AppMode.NAVIGATE, AppMode.onStart(recordingRunning = false, unfinishedRecording = true))
}
