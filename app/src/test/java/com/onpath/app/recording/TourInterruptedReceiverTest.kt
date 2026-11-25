package com.onpath.app.recording

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.onpath.app.data.TourDb
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/**
 * The phone restarted / OnPath was updated while a tour was recording (docs/core-features/C4-recording.md): the receiver
 * leaves the tour active in the database, shows one notification that opens OnPath (which offers Continue), says
 * nothing when no tour was recording, ignores other broadcasts and never throws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class) // plain Application: the app's own starts MapLibre (native) // highest level the pinned Robolectric supports; the app's compileSdk is 36
@LooperMode(LooperMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY) // native SQLite would load a native runtime that breaks Paparazzi (layoutlib) in the same test JVM
class TourInterruptedReceiverTest {
    private lateinit var app: Application
    private lateinit var nm: NotificationManager

    /** TourDb.get is a process-wide singleton; every test starts with a fresh database file and a fresh instance. */
    private fun forgetTourDb() {
        val f = TourDb::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (f.get(null) as TourDb?)?.close()
        f.set(null, null)
    }

    @Before fun setUp() {
        forgetTourDb()
        app = RuntimeEnvironment.getApplication()
        nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Problems.clear()
    }

    @After fun tearDown() { forgetTourDb(); app.deleteDatabase("onpath.db") }

    private fun record(state: String): Long = TourDb.get(app).insertTour(
        TourKind.RECORDED, "Meran loop", "hike", 1, 1200.0, 50.0, 40.0, 900.0, emptyList(), state = state)

    /** The receiver works on its own thread (goAsync); wait for its notification or its problem line. */
    private fun send(action: String?) {
        val intent = if (action == null) Intent() else Intent(action)
        TourInterruptedReceiver().onReceive(app, intent)
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("timeout: $what", cond())
    }

    private fun shown() = shadowOf(nm).allNotifications

    @Test fun anActiveTourAfterRebootShowsTheNotificationAndStaysActive() {
        val id = record("active")
        send(Intent.ACTION_BOOT_COMPLETED)
        waitFor("notification") { shown().isNotEmpty() }
        val n = shadowOf(nm).getNotification(TourInterruptedReceiver.INTERRUPTED_ID)
        assertNotNull(n)
        assertEquals(RecordingService.CHANNEL_NAV, n.channelId)
        assertNotNull("tapping opens OnPath", n.contentIntent)
        assertTrue(n.extras.getCharSequence("android.title")!!.isNotEmpty())
        assertTrue("the tour name is in the long text", n.extras.getCharSequence("android.bigText")!!.contains("Meran loop"))
        assertEquals("the tour is still active, Continue is offered", "active", TourDb.get(app).getTour(id)!!.state)
        waitFor("problem line") { Problems.snapshot().any { it.area == "recording" } }
    }

    @Test fun anActiveTourAfterAnUpdateShowsTheUpdateText() {
        record("active")
        send(Intent.ACTION_MY_PACKAGE_REPLACED)
        waitFor("notification") { shown().isNotEmpty() }
        val text = shadowOf(nm).getNotification(TourInterruptedReceiver.INTERRUPTED_ID).extras.getCharSequence("android.text")!!
        assertTrue(text.isNotEmpty())
    }

    @Test fun bootAndUpdateSayDifferentThings() {
        record("active")
        send(Intent.ACTION_BOOT_COMPLETED)
        waitFor("boot notification") { shown().isNotEmpty() }
        val bootText = shadowOf(nm).getNotification(TourInterruptedReceiver.INTERRUPTED_ID).extras.getCharSequence("android.text").toString()
        nm.cancelAll()
        send(Intent.ACTION_MY_PACKAGE_REPLACED)
        waitFor("update notification") { shown().isNotEmpty() }
        val updText = shadowOf(nm).getNotification(TourInterruptedReceiver.INTERRUPTED_ID).extras.getCharSequence("android.text").toString()
        assertTrue(bootText != updText)
    }

    @Test fun noActiveTourShowsNothing() {
        record("done") // a finished tour does not count
        send(Intent.ACTION_BOOT_COMPLETED)
        Thread.sleep(500)
        assertTrue(shown().isEmpty())
        assertTrue(Problems.snapshot().isEmpty())
    }

    @Test fun anEmptyDatabaseShowsNothing() {
        TourDb.get(app).readableDatabase
        send(Intent.ACTION_MY_PACKAGE_REPLACED)
        Thread.sleep(500)
        assertTrue(shown().isEmpty())
    }

    @Test fun anUnrelatedActionIsIgnored() {
        record("active")
        send(Intent.ACTION_BATTERY_LOW)
        send(null)
        Thread.sleep(500)
        assertTrue(shown().isEmpty())
        assertTrue(Problems.snapshot().isEmpty())
    }

    @Test fun anUnreadableDatabaseIsReportedAndDoesNotCrash() {
        record("active")
        TourDb.get(app).writableDatabase.execSQL("DROP TABLE tours") // the query now throws
        send(Intent.ACTION_BOOT_COMPLETED)
        waitFor("problem") { Problems.snapshot().any { it.level == Problems.Level.ERROR && it.area == "recording" } }
        assertTrue(shown().isEmpty())
    }
}
