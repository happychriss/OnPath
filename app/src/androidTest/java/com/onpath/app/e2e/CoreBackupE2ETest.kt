package com.onpath.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.onpath.app.backup.Backups
import com.onpath.app.data.TourDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern
import kotlin.concurrent.thread

/**
 * CORE FEATURE C11 end to end (owner 2026-10-21): record a short tour with a mock GPS → end it → its GPX appears in
 * Documents/OnPath → the app forgets everything like a reinstall (tours, backup index, folder settings) → restore
 * through the real folder picker ("Use this folder" → "Allow", UiAutomator) → the tour is back, same id and points.
 * (A real `pm clear` would end this test process too, so the wipe is done from inside the app.)
 */
@RunWith(AndroidJUnit4::class)
class CoreBackupE2ETest : E2EBase() {
    @Volatile private var walking = true
    @Volatile private var lat = 46.6714
    @Volatile private var lon = 11.1700
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sh(cmd: String): String = device.executeShellCommand(cmd)

    @Before fun setUp() {
        sh("appops set com.android.shell android:mock_location allow")
        for (p in PROVIDERS) {
            sh("cmd location providers add-test-provider $p")
            sh("cmd location providers set-test-provider-enabled $p true")
        }
        fix()
        thread(isDaemon = true) { while (walking) { lat += 0.00002; lon += 0.00003; fix(); Thread.sleep(500) } }
        launch()
        waitUntil("GPS fix in the app", 20_000) { var ok = false; scenario.onActivity { ok = vm(it).tracker.location.value != null }; ok }
    }

    @After fun stopGps() {
        walking = false
        try { scenario.onActivity { if (vm(it).recState.value != null) vm(it).stopRecording() } } catch (_: Exception) {} // expected: app already closed
        for (p in PROVIDERS) sh("cmd location providers remove-test-provider $p")
    }

    private fun fix() { for (p in PROVIDERS) sh("cmd location providers set-test-provider-location $p --location $lat,$lon --accuracy 4") }

    private companion object { val PROVIDERS = listOf("gps", "network") }

    @Test fun finishedTourIsBackedUpAndRestoredAfterAWipe() {
        // 1. record a short tour and end it
        scenario.onActivity { vm(it).startRecording() }
        waitUntil("recording", 20_000) { var r = false; scenario.onActivity { r = vm(it).recState.value != null }; r }
        Thread.sleep(12_000) // ~20 fixes, a few hundred metres
        scenario.onActivity { vm(it).stopRecording() }
        var tourId = -1L
        waitUntil("tour finished", 20_000) { scenario.onActivity { tourId = vm(it).finishedTour?.id ?: -1L }; tourId >= 0 }
        scenario.onActivity { vm(it).finishedTour = null }
        val db = TourDb.get(ctx)
        val tour = db.getTour(tourId)!!
        val points = db.loadSegments(tourId).sumOf { it.size }
        assertTrue("points recorded: $points", points >= 2)

        // 2. the GPX appears in Documents/OnPath (found by the tour's uuid inside the file)
        var file = ""
        waitUntil("backup file in Documents/OnPath", 20_000) {
            file = sh("grep -rl ${tour.uuid} /sdcard/Documents/OnPath").trim() // no shell globbing here: recursive grep
            file.lines().any { it.endsWith(".gpx") && "/Deleted/" !in it }
        }
        Thread.sleep(3_000) // a place name may still rename the tour – and its file

        // 3. "wipe": no tours, no backup index, no folder settings (like a reinstall)
        (db.listTours() + db.listDeleted()).forEach { db.deleteTour(it.id) }
        Backups.forgetLikeReinstall(ctx)
        scenario.onActivity { vm(it).refreshTours() }

        // 4. restore through the folder picker
        scenario.onActivity { vm(it).restoreOffer = true }
        clickText("Restore")
        val use = device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder|diesen ordner verwenden")) /* a German phone (owner's Pixel, 2026-10-22) */), 15_000)
        if (use == null) { shot("picker"); fail("folder picker did not show \"Use this folder\" – ${state()}") }
        use!!.click()
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow|zulassen"))), 10_000)?.click()
        waitUntil("restore finished", 60_000) {
            var busy = true; scenario.onActivity { busy = vm(it).restoreProgress != null }
            !busy && db.allUuids().contains(tour.uuid)
        }

        // 5. the tour is back – same id, date, kind and points (files of earlier runs may come back too)
        val back = db.listTours().single { it.uuid == tour.uuid }
        val now = db.getTour(back.id)!!
        assertEquals(tour.kind, now.kind)
        assertEquals(tour.createdMs, now.createdMs)
        assertEquals(points, db.loadSegments(now.id).sumOf { it.size })
        assertNotNull("result message", device.wait(Until.findObject(By.textContains("restored")), 5_000))
    }
}
