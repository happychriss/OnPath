package com.onpath.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.onpath.app.domain.AppMode
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Live sharing on Navigate (owner 2026-11-24): the round "Standort teilen" button in the tour bubble, with no live name
 * stored yet, must show the name question on the first tap (release blocker 2026-11-25: the dialog was drawn only over
 * Settings, so Navigate showed nothing). Nothing is uploaded: the question comes first and is cancelled at the end.
 */
@RunWith(AndroidJUnit4::class)
class LiveShareNameE2ETest : E2EBase() {
    private fun sh(cmd: String) = device.executeShellCommand(cmd)

    @Before fun setUp() {
        sh("appops set com.android.shell android:mock_location allow")
        launch() // clears the prefs: no live name stored
    }

    @After fun stopRecording() {
        try { scenario.onActivity { if (vm(it).recState.value != null) vm(it).tourEnd.stopRecording() } } catch (_: Exception) {} // expected: app already closed
    }

    @Test fun shareButtonOnNavigateAsksTheNameFirst() {
        scenario.onActivity { assertNull("no live name stored", vm(it).prefs.liveName) }
        scenario.onActivity { vm(it).tourSession.startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { var m: AppMode? = null; scenario.onActivity { m = vm(it).tourSession.mode }; m == AppMode.NAVIGATE }
        val button = findFresh(By.desc(Pattern.compile("(?i)(share location|standort teilen)")), 20_000)
        if (button == null) { shot("no-share-button"); throw AssertionError("no share button on Navigate – ${seenDescriptions()}") }
        quickTap(button)
        val title = findFresh(By.text(Pattern.compile("(?i)(your name for the page|dein name für die seite)")), 8_000)
        if (title == null) { shot("no-name-dialog"); throw AssertionError("no name dialog after the first tap on the share button – ${seenDescriptions()}") }
        val shown = title.text
        // cancel: nothing is shared, nothing stored
        clickText(if (shown.startsWith("Your", true)) "Cancel" else "Abbrechen")
        assertTrue("name dialog closed", goneFresh(By.text(shown), 5_000))
        scenario.onActivity { assertNull("no name stored after cancel", vm(it).prefs.liveName) }
    }
}
