package com.onpath.app

import com.onpath.app.IncomingIntent.Delivery
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After a reinstall the app showed "This plan link can't be read" again – Android started the
 * screen anew with the task's first intent (a VIEW plan link handled long ago). An incoming link / share / GPX is
 * handled once; a new one (onNewIntent, or another link on a cold start) still is.
 */
class IncomingIntentTest {
    private val view = "android.intent.action.VIEW"
    private val send = "android.intent.action.SEND"
    private val link = IncomingIntent.fingerprint(view, "https://plan.onpath.earth/#p=AQUDAsTc", null, null, null)!!
    private val other = IncomingIntent.fingerprint(view, "https://plan.onpath.earth/#p=BBBB", null, null, null)!!

    @Test fun onlyViewAndSendAreIncoming() {
        assertNull(IncomingIntent.fingerprint("android.intent.action.MAIN", null, null, null, null))
        assertNull(IncomingIntent.fingerprint(null, null, null, null, null))
        assertTrue(IncomingIntent.fingerprint(send, null, "text/plain", "Gasthof Taser https://maps.app.goo.gl/x", null) != null)
        assertNotEquals(link, other)
        assertEquals(link, IncomingIntent.fingerprint(view, "https://plan.onpath.earth/#p=AQUDAsTc", null, null, null))
        // shared text and shared files differ by their content
        assertNotEquals(IncomingIntent.fingerprint(send, null, "text/plain", "a", null), IncomingIntent.fingerprint(send, null, "text/plain", "b", null))
        assertNotEquals(IncomingIntent.fingerprint(send, null, "*/*", null, "content://x/1"), IncomingIntent.fingerprint(send, null, "*/*", null, "content://x/2"))
    }

    @Test fun theTasksFirstLinkIsNotHandledAgainAfterARestart() {
        // first time: handled
        assertTrue(IncomingIntent.shouldHandle(Delivery.CREATE, fromHistory = false, fingerprint = link, lastHandled = null))
        // reinstall / process restart: the screen starts again with the same first intent → not again
        assertFalse(IncomingIntent.shouldHandle(Delivery.CREATE, fromHistory = false, fingerprint = link, lastHandled = link))
        // the screen rebuilt with saved state (rotation, language) or reopened from recents → never
        assertFalse(IncomingIntent.shouldHandle(Delivery.RESTORED, fromHistory = false, fingerprint = other, lastHandled = link))
        assertFalse(IncomingIntent.shouldHandle(Delivery.CREATE, fromHistory = true, fingerprint = other, lastHandled = null))
    }

    @Test fun aNewLinkStillWorks() {
        // another link on a cold start
        assertTrue(IncomingIntent.shouldHandle(Delivery.CREATE, fromHistory = false, fingerprint = other, lastHandled = link))
        // a link while the app runs (singleTask → onNewIntent): always, even the same one tapped again
        assertTrue(IncomingIntent.shouldHandle(Delivery.NEW_INTENT, fromHistory = false, fingerprint = link, lastHandled = link))
        assertTrue(IncomingIntent.shouldHandle(Delivery.NEW_INTENT, fromHistory = false, fingerprint = other, lastHandled = link))
        // the launcher (MAIN) is nothing to handle
        assertFalse(IncomingIntent.shouldHandle(Delivery.NEW_INTENT, fromHistory = false, fingerprint = null, lastHandled = link))
    }

    /** The wiring: MainActivity asks the rule in onCreate and onNewIntent and remembers what it handled. */
    @Test fun mainActivityUsesTheRule() {
        val main = File("src/main/java/com/onpath/app/MainActivity.kt").readText()
        assertTrue(main.contains("incoming(intent, if (savedInstanceState == null) IncomingIntent.Delivery.CREATE else IncomingIntent.Delivery.RESTORED)"))
        assertTrue(main.contains("incoming(intent, IncomingIntent.Delivery.NEW_INTENT)"))
        assertTrue(main.contains("IncomingIntent.remember("))
    }
}
