package com.onpath.app.ui.live

import com.onpath.app.live.LiveSnapshot
import com.onpath.app.testkit.FakeLiveShareClient
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live sharing as Navigate shows it ([LiveShareSlice]): what a tap does, what the sign says, what is never asked twice. */
class LiveShareSliceTest {
    private val link = "https://plan.onpath.earth/live/#t=CODE"

    private class World(name: String? = null, recording: Boolean = true) {
        val client = FakeLiveShareClient()
        val prefs = object : LivePrefs { override var liveName: String? = name }
        val shared = ArrayList<String>()
        var now = 1_800_000_000_000L
        val slice = LiveShareSlice(client, prefs, object : LiveShareHost {
            override fun shareText(text: String) { shared += text }
            override fun nowMs() = now
        }).also { it.onRecording(recording) }

        /** The recording service's state, as the app's collector hands it in. */
        var recording: Boolean = recording
            set(v) { field = v; slice.onRecording(v) }

        /** The lane answers: the snapshot reaches the client's flow and the slice (the app's collector). */
        fun lane(s: LiveSnapshot) { client.publish(s); slice.onSnapshot(s) }
    }

    /** True when a screen that read [read] would be drawn again after [change] (Compose's own snapshot bookkeeping). */
    private fun redrawn(read: () -> Unit, change: () -> Unit): Boolean {
        Snapshot.sendApplyNotifications()
        val reads = HashSet<Any>()
        Snapshot.observe(readObserver = { reads += it }) { read() }
        var hit = false
        val h = Snapshot.registerApplyObserver { changed, _ -> if (changed.any { it in reads }) hit = true }
        try { change(); Snapshot.sendApplyNotifications() } finally { h.dispose() }
        return hit
    }

    /** Guards: the first share asks the name once – and never again, also when it was left empty. */
    @Test
    fun theFirstShareAsksTheNameOnce() {
        val w = World()
        w.slice.share()
        assertTrue(w.slice.askName)
        assertTrue("nothing starts before the answer", w.client.calls.isEmpty())
        w.slice.nameGiven("  ")
        assertFalse(w.slice.askName)
        assertEquals("an empty name is an answer", "", w.prefs.liveName)
        assertEquals(listOf("start:"), w.client.calls)
        w.lane(LiveSnapshot(sharing = true, link = link))
        w.slice.stop()
        w.lane(LiveSnapshot.OFF)
        w.slice.share()
        assertFalse("not asked a second time", w.slice.askName)
        assertEquals(listOf("start:", "stop", "start:"), w.client.calls)
    }

    /** Guards: a closed name question shares nothing and stores nothing (no tour goes out by a mis-tap). */
    @Test
    fun aCancelledNameQuestionSharesNothing() {
        val w = World()
        w.slice.share()
        w.slice.nameCancelled()
        assertTrue(w.client.calls.isEmpty()); assertNull(w.prefs.liveName); assertTrue(w.shared.isEmpty())
        w.slice.share()
        assertTrue("asked again next time", w.slice.askName)
    }

    /** Guards: the share sheet opens exactly once per tap, with the link the lane made – not before the link exists. */
    @Test
    fun theShareSheetOpensOnceWhenTheLinkIsThere() {
        val w = World(name = "Christian")
        w.slice.share()
        assertEquals(listOf("start:Christian"), w.client.calls)
        assertTrue("no link yet, no sheet", w.shared.isEmpty())
        w.lane(LiveSnapshot(sharing = true, link = link))
        w.lane(LiveSnapshot(sharing = true, link = link, lastOkMs = w.now)) // later snapshots open nothing
        assertEquals(1, w.shared.size)
        assertTrue(w.shared[0], w.shared[0].endsWith(link))
    }

    /** Guards: "Link nochmal teilen" gives the SAME link (a second person joins the same tour), and starts nothing new. */
    @Test
    fun shareAgainGivesTheSameLink() {
        val w = World(name = "")
        w.slice.share()
        w.lane(LiveSnapshot(sharing = true, link = link))
        w.slice.shareAgain()
        w.slice.share() // "Live teilen" while sharing is the same thing
        assertEquals(3, w.shared.size)
        assertEquals(1, w.shared.toSet().size)
        assertEquals(listOf("start:"), w.client.calls)
    }

    /** Guards: the menu offers only what makes sense – nothing without a tour, the two sharing actions while sharing. */
    @Test
    fun theActionsFollowTheState() {
        val w = World(name = "", recording = false)
        assertTrue(w.slice.actions.isEmpty())
        w.slice.share()
        assertTrue("no tour records: a tap does nothing", w.client.calls.isEmpty())
        w.recording = true
        assertEquals(listOf(LiveAction.SHARE), w.slice.actions)
        w.lane(LiveSnapshot(sharing = true, link = link))
        assertEquals(listOf(LiveAction.SHARE_AGAIN, LiveAction.STOP), w.slice.actions)
        w.recording = false // the tour ended, the final upload still pending: stop stays possible
        assertEquals(listOf(LiveAction.SHARE_AGAIN, LiveAction.STOP), w.slice.actions)
    }

    /** Guards (review 2026-11-20): the Settings card goes when the tour ends while Settings is open – not only on the next redraw. */
    @Test
    fun theToursEndRedrawsTheActions() {
        val w = World(name = "")
        assertEquals(listOf(LiveAction.SHARE), w.slice.actions)
        assertTrue(redrawn({ w.slice.actions }) { w.recording = false })
        assertTrue(w.slice.actions.isEmpty())
    }

    /**
     * Guards: the sign tells the truth – green only while uploads get through; grey when the lane says waiting or the
     * last good upload is old (the lane's snapshot does not tick by itself); a rest (uploads every 10 min) stays green.
     */
    @Test
    fun theSignIsGreenOnlyWhileUploadsGetThrough() {
        val w = World(name = "")
        assertEquals(LiveSign.NONE, w.slice.sign)
        w.slice.share()
        w.lane(LiveSnapshot(sharing = true, link = link))
        assertEquals("just started: live", LiveSign.LIVE, w.slice.sign)
        w.lane(LiveSnapshot(sharing = true, link = link, lastOkMs = w.now))
        w.now += 10 * 60_000L + 30_000
        assertEquals("a rest's ten minutes between uploads is still live", LiveSign.LIVE, w.slice.sign)
        assertTrue("the clock alone turns it grey on screen", redrawn({ w.slice.sign }) { w.now += 5 * 60_000L; w.slice.tick() })
        assertEquals(LiveSign.WAITING, w.slice.sign)
        w.lane(LiveSnapshot(sharing = true, waiting = true, link = link, lastOkMs = w.now))
        assertEquals(LiveSign.WAITING, w.slice.sign)
        w.lane(LiveSnapshot.OFF)
        assertEquals(LiveSign.NONE, w.slice.sign)
    }

    /**
     * Guards (review 2026-11-20): a share from before a killed app, with no good upload in this process yet, is not
     * green on trust – it waits for the first upload; one started here is green from the start.
     */
    @Test
    fun aShareFromAnEarlierProcessIsGreenOnlyAfterAnUpload() {
        val w = World(name = "")
        w.lane(LiveSnapshot(sharing = true, link = link, tourId = 7))
        assertEquals(LiveSign.WAITING, w.slice.sign)
        w.lane(LiveSnapshot(sharing = true, link = link, tourId = 7, lastOkMs = w.now))
        assertEquals(LiveSign.LIVE, w.slice.sign)
    }

    /**
     * Guards (review 2026-11-20): a sharing the server ended is shown in the sharing's own place – the Settings card –
     * and only marked seen once that card showed it (Navigate has no line for it); it goes when Settings closes and does
     * not come back from a snapshot from before the "seen". Never an error box during a tour.
     */
    @Test
    fun aSharingEndedByTheServerIsShownUntilSeen() {
        val w = World(name = "")
        w.slice.share()
        w.lane(LiveSnapshot(sharing = true, link = link))
        w.lane(LiveSnapshot(endedByServer = true))
        assertEquals(LiveSign.NONE, w.slice.sign)
        assertTrue(w.slice.ended)
        assertTrue("the card offers a new share", LiveAction.SHARE in w.slice.actions)
        assertFalse("not seen before it was shown", "seen" in w.client.calls)
        w.lane(LiveSnapshot(endedByServer = true)) // the lane says it again until seen
        w.slice.endedShown()
        assertEquals(1, w.client.calls.count { it == "seen" })
        assertTrue("still there while Settings is open", w.slice.ended)
        w.slice.settingsClosed()
        assertFalse(w.slice.ended)
        w.lane(LiveSnapshot(endedByServer = true)) // late, from before the "seen"
        assertFalse(w.slice.ended)
        w.lane(LiveSnapshot.OFF)
        w.lane(LiveSnapshot(endedByServer = true)) // a second end, later
        assertTrue(w.slice.ended)
    }

    /** Guards (review 2026-11-20): the name question closes with Settings – it never pops up again over Navigate. */
    @Test
    fun closingSettingsCancelsTheNameQuestion() {
        val w = World()
        w.slice.share()
        assertTrue(w.slice.askName)
        w.slice.settingsClosed()
        assertFalse(w.slice.askName)
        assertTrue(w.client.calls.isEmpty())
        assertNull(w.prefs.liveName)
    }

    /** Guards: "Teilen beenden" also cancels a share sheet that was still waiting for its link. */
    @Test
    fun stopCancelsAWaitingShareSheet() {
        val w = World(name = "")
        w.slice.share()
        w.slice.stop()
        w.lane(LiveSnapshot(sharing = true, link = link)) // a late snapshot from before the stop
        assertTrue(w.shared.isEmpty())
        assertEquals(listOf("start:", "stop"), w.client.calls)
    }

    /** Guards: changing the name in Settings never starts a share by itself and never opens the share sheet. */
    @Test
    fun editingTheNameSharesNothing() {
        val w = World(name = "Christian")
        assertTrue(w.slice.nameKnown)
        w.slice.editName()
        assertTrue(w.slice.askName); assertTrue(w.slice.editingName)
        w.slice.nameGiven("Chris")
        assertEquals("Chris", w.prefs.liveName); assertEquals("Chris", w.slice.name)
        assertTrue(w.client.calls.isEmpty()); assertTrue(w.shared.isEmpty())
        w.slice.editName(); w.slice.nameCancelled()
        assertEquals("Chris", w.prefs.liveName)
        assertFalse(w.slice.editingName)
        assertFalse("never asked: no Name row yet", World().slice.nameKnown)
    }

    /** Guards: a long name cannot blow up the viewer page's title. */
    @Test
    fun aLongNameIsCut() {
        val w = World()
        w.slice.share()
        w.slice.nameGiven("x".repeat(200))
        assertEquals(LiveShareSlice.MAX_NAME, w.prefs.liveName!!.length)
    }
}
