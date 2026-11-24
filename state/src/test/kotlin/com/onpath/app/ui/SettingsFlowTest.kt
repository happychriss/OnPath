package com.onpath.app.ui

import com.onpath.app.data.AppSettings
import com.onpath.app.debug.FrameStats
import com.onpath.app.debug.MapJank
import com.onpath.app.map.ReliefAuto
import com.onpath.app.testkit.ManualExecutor
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import com.onpath.app.time.Pace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import com.onpath.strings.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Settings (rebuild phase 2 piece 4) as flows of the REAL [SettingsSlice] with a real [NoticeBoard] (the lines at the top of
 * a page): open / topic / back, the on-the-way list, a changed setting reaching the preferences, the cells and the recording
 * service, relief that switches itself off once and never overrides the user, developer tools after 7 taps and remembered,
 * the routing info of Diagnostics. Fakes only at the edges: the preferences ([SettingsStore] over a map-like holder that
 * survives a new slice = the process), the files, and the host that records what the view model would do. Each test asserts
 * what is STORED and the state the user is left in.
 *
 * Replaces the source pins of the view model's `updateSettings` / `mapGestureMeasured` wiring (ReliefAutoTest.wiring), of
 * the `showSettings` setter (NoticesTest.noDarkBarAnyMore) and of `devToolsShown` (SettingsPagesTest.developerTools).
 */
class SettingsFlowTest {
    @get:Rule val composeState = OwnComposeSnapshot()
    @get:Rule val problems = ProblemsRule()

    /** The preferences: what survives the process. */
    private class Prefs : SettingsStore {
        override var settings = AppSettings()
        override var reliefAuto = ReliefAuto.State()
        override var devToolsUnlocked = false
        override var diagnosticsSeenMs = 0L
        var files: List<File> = emptyList()
        var filesFail = false
        var fp = "r1-abcdef123456"
        /** The blocking reads must run on the io thread: false = called on the caller's. */
        var onIo: () -> Boolean = { true }
        override fun rd5Files(): List<File> { check(onIo()) { "rd5Files on the caller's thread" }; if (filesFail) throw java.io.IOException("storage gone"); return files }
        override fun fingerprint(): String { check(onIo()) { "fingerprint on the caller's thread" }; return fp }
    }

    /** The preferences with a record of each write (the real store writes one preference per call). */
    private class RecordingPrefs : SettingsStore {
        val inner = Prefs()
        val log = ArrayList<String>()
        override var settings: AppSettings get() = inner.settings; set(v) { log += "settings"; inner.settings = v }
        override var reliefAuto: ReliefAuto.State get() = inner.reliefAuto; set(v) { log += "relief $v"; inner.reliefAuto = v }
        override var devToolsUnlocked: Boolean get() = inner.devToolsUnlocked; set(v) { log += "devTools $v"; inner.devToolsUnlocked = v }
        override var diagnosticsSeenMs: Long get() = inner.diagnosticsSeenMs; set(v) { log += "seen $v"; inner.diagnosticsSeenMs = v }
        override fun rd5Files() = inner.rd5Files()
        override fun fingerprint() = inner.fingerprint()
    }

    private val prefs = RecordingPrefs()
    private var now = 1_000L

    private inner class App(val io: CoroutineDispatcher = Dispatchers.Unconfined) {
        val log = ArrayList<String>()
        /** What the preferences and the slice held at the moment the host was called (the new value must be there already). */
        val storedAtCall = ArrayList<String>()
        var mapShown = true
        var recordingRunning = false
        lateinit var slice: SettingsSlice
        val board = NoticeBoard(routesOpen = { false }, settingsOpen = { slice.showSettings }, mapShown = { mapShown })
        val host = object : SettingsHost {
            override val planMapShown: Boolean get() = mapShown
            override fun clearOfflineResult() { log += "offline result cleared" }
            override fun syncEndedSeen() { log += "sync ended seen" }
            override fun retime() { log += "retime"; storedAtCall += "retime:" + prefs.inner.settings.hikePace + "/" + prefs.inner.settings.bikePace + "/" + slice.settings.hikePace }
            override val tourRecording: Boolean get() = recordingRunning
            override fun recordingSettingsChanged() { log += "recording told"; storedAtCall += "recording:" + prefs.inner.settings.contours + "/" + slice.settings.contours }
            override fun mapsWifiOnly(on: Boolean) { log += "wifi only $on"; storedAtCall += "wifi:" + prefs.inner.settings.mapsWifiOnly + "/" + slice.settings.mapsWifiOnly }
            override fun officialRoutesChanged(strength: Int) { log += "official $strength"; storedAtCall += "official:" + prefs.inner.settings.officialRouteStrength + "/" + slice.settings.officialRouteStrength }
        }
        init { slice = SettingsSlice(board, prefs, host, CoroutineScope(Job() + Dispatchers.Unconfined), io) { now } }
    }

    /** Frames of one map gesture: [frames] drawn, [janky] of them over 16 ms. */
    private fun gesture(frames: Int, janky: Int) = FrameStats(frames, janky, 0, janky, 8.3, 10.0, 20.0, 120)

    /** The tally is the app's one (MapJank): smooth gestures push the earlier ones out of the window the relief rule reads. */
    private fun flushTally() { repeat(ReliefAuto.WINDOW) { MapJank.tally.add(ReliefAuto.Gesture(ReliefAuto.Kind.PAN, 100, 0, true)) } }

    // ---------------------------------------------------------------- open / page / back

    @Test fun settingsOpenAtTheListOrAtAPageAndBackWalksOutOneStep() {
        val app = App()
        assertFalse(app.slice.showSettings); assertNull(app.slice.settingsPage)
        app.slice.openSettings()
        assertTrue(app.slice.showSettings); assertNull(app.slice.settingsPage)
        app.slice.openSettings(SettingsPage.ABOUT)
        assertEquals(SettingsPage.ABOUT, app.slice.settingsPage)
        app.slice.settingsBack()
        assertNull("a topic page returns to the list", app.slice.settingsPage); assertTrue(app.slice.showSettings)
        app.slice.settingsBack()
        assertFalse("the list closes Settings", app.slice.showSettings)
    }

    @Test fun theLineAtTheTopLastsUntilThePageChangesOrSettingsIsLeft() {
        val app = App()
        app.slice.openSettings(SettingsPage.BACKUP)
        app.board.say("Backup done.", NoticeKind.INFO, NoticePlace.SETTINGS)
        assertEquals("Backup done.", app.board.settingsNote?.text)
        app.slice.settingsPage = SettingsPage.BACKUP
        assertNotNull("the same page again keeps the line", app.board.settingsNote)
        app.slice.settingsPage = SettingsPage.MAP
        assertNull("another page: the line goes", app.board.settingsNote)
        app.board.say("Wifi only.", NoticeKind.INFO, NoticePlace.SETTINGS)
        assertNotNull(app.board.settingsNote)
        app.slice.showSettings = false
        assertNull("closing Settings: the line goes", app.board.settingsNote)
        app.board.say("Late.", NoticeKind.INFO, NoticePlace.SETTINGS)
        assertNull("Settings is closed: a line for it is not shown", app.board.settingsNote)
    }

    @Test fun theOfflineMapsResultLastsUntilThePageIsLeft() {
        val app = App()
        app.slice.openSettings(SettingsPage.OFFLINE_MAPS)
        assertTrue("opening the offline maps page keeps its result", "offline result cleared" !in app.log)
        app.slice.settingsPage = SettingsPage.OFFLINE_MAPS
        assertTrue("the page again keeps it", "offline result cleared" !in app.log)
        app.slice.settingsPage = SettingsPage.MAP
        assertEquals("another page clears it", 1, app.log.count { it == "offline result cleared" })
        app.slice.settingsPage = SettingsPage.ABOUT
        assertEquals("a change between two OTHER pages clears it too", 2, app.log.count { it == "offline result cleared" })
        app.slice.settingsPage = SettingsPage.OFFLINE_MAPS
        app.slice.settingsPage = null
        assertEquals("back to the list clears it", 3, app.log.count { it == "offline result cleared" })
    }

    @Test fun theSyncPageMarksTheEndedMessageSeen() {
        val app = App()
        app.slice.openSettings(SettingsPage.SYNC)
        assertEquals(1, app.log.count { it == "sync ended seen" })
        app.slice.settingsPage = SettingsPage.MAP
        assertEquals("only entering the page counts", 1, app.log.count { it == "sync ended seen" })
    }

    @Test fun navigatesSettingsOnTheWayAndBackToNavigate() {
        val app = App()
        app.slice.openSettings(SettingsPage.MAP)
        app.slice.openSettingsOnTheWay()
        assertTrue(app.slice.showSettings && app.slice.settingsOnTheWay); assertNull("the on-the-way list starts at the list", app.slice.settingsPage)
        assertTrue(problems.traced("ui").any { "settings on the way" in it })
        app.slice.showAllSettings()
        assertFalse(app.slice.settingsOnTheWay); assertTrue("the whole list, still open", app.slice.showSettings)
        app.slice.openSettingsOnTheWay()
        app.slice.showSettings = false
        assertFalse("closing ends the on-the-way list", app.slice.settingsOnTheWay)
    }

    // ---------------------------------------------------------------- a changed setting

    @Test fun aChangedSettingIsStoredAndReachesTheCellsOnce() {
        val app = App()
        app.slice.updateSettings(app.slice.settings.copy(mapsWifiOnly = true))
        assertTrue(prefs.inner.settings.mapsWifiOnly); assertTrue(app.slice.settings.mapsWifiOnly)
        assertEquals(listOf("wifi only true"), app.log)
        assertEquals("stored before the cells are told", listOf("wifi:true/true"), app.storedAtCall)
        assertEquals(1, prefs.log.count { it == "settings" })
    }

    @Test fun theSettingsSurviveANewSliceOnTheSamePreferences() {
        App().slice.updateSettings(AppSettings(contours = false, mapsWifiOnly = true))
        val again = App()
        assertFalse(again.slice.settings.contours); assertTrue(again.slice.settings.mapsWifiOnly)
    }

    @Test fun aNewPaceRetimesAndOtherSettingsDoNot() {
        val app = App()
        app.slice.updateSettings(app.slice.settings.copy(contours = false))
        assertTrue("no pace change: no re-timing", "retime" !in app.log)
        app.slice.updateSettings(app.slice.settings.copy(hikePace = Pace.FIT))
        assertEquals(1, app.log.count { it == "retime" })
        assertEquals("the new pace is stored and in the slice when the times are worked out again", "retime:FIT/AVERAGE/FIT", app.storedAtCall.last { it.startsWith("retime") })
        app.slice.updateSettings(app.slice.settings.copy(bikePace = Pace.RELAXED))
        assertEquals(2, app.log.count { it == "retime" })
        app.slice.updateSettings(app.slice.settings)
        assertEquals("the same pace again: nothing", 2, app.log.count { it == "retime" })
    }

    @Test fun preferOfficialRoutesRoutesAgainOnlyWhenItChanges() {
        val app = App()
        assertEquals("off by default: the routes as before", AppSettings.OFFICIAL_OFF, app.slice.settings.officialRouteStrength)
        app.slice.updateSettings(app.slice.settings.copy(contours = false))
        assertTrue("another setting: not routed again", app.log.none { it.startsWith("official") })
        app.slice.updateSettings(app.slice.settings.copy(officialRouteStrength = AppSettings.OFFICIAL_STRONG))
        assertEquals(listOf("official 2"), app.log.filter { it.startsWith("official") })
        assertEquals("stored and in the slice when the router is told", "official:2/2", app.storedAtCall.last { it.startsWith("official") })
        assertTrue(problems.traced("routing").any { "Prefer official routes: 2" in it })
        app.slice.updateSettings(app.slice.settings)
        assertEquals("the same strength again: nothing", 1, app.log.count { it.startsWith("official") })
        app.slice.updateSettings(app.slice.settings.copy(officialRouteStrength = AppSettings.OFFICIAL_NORMAL))
        assertEquals(listOf("official 2", "official 1"), app.log.filter { it.startsWith("official") })
    }

    @Test fun aRunningRecordingIsToldAndNoRecordingIsNot() {
        val app = App()
        app.slice.updateSettings(app.slice.settings.copy(contours = false))
        assertTrue("recording told" !in app.log)
        app.recordingRunning = true
        app.slice.updateSettings(app.slice.settings.copy(contours = true))
        assertEquals(1, app.log.count { it == "recording told" })
        assertEquals("the service reads the new settings: stored first", "recording:true/true", app.storedAtCall.last { it.startsWith("recording") })
    }

    @Test fun theAppsOwnChangeStoresTheSettingsAndNothingElse() {
        val app = App()
        app.slice.put(app.slice.settings.copy(navHeadingUp = !app.slice.settings.navHeadingUp))
        assertEquals(prefs.inner.settings, app.slice.settings)
        assertTrue("no cells, no re-timing, no relief mark", app.log.isEmpty() && prefs.log.none { it.startsWith("relief") })
    }

    // ---------------------------------------------------------------- relief auto

    @Test fun reliefSwitchedOffByHandIsTheUsersChoiceAndTheAppNeverOverridesIt() {
        flushTally()
        val app = App()
        app.slice.updateSettings(app.slice.settings.copy(hillshade = false))
        assertTrue("marked as the user's", prefs.inner.reliefAuto.byUser)
        app.slice.updateSettings(app.slice.settings.copy(hillshade = true))
        assertTrue(app.slice.settings.hillshade)
        repeat(4) { app.slice.mapGestureMeasured(ReliefAuto.Kind.PAN, gesture(100, 90)) }
        assertTrue("a user who set it by hand: the app never switches it off", app.slice.settings.hillshade)
        assertNull(app.board.mapNote)
    }

    @Test fun anUnchangedReliefSettingDoesNotMarkTheUsersChoice() {
        val app = App()
        app.slice.updateSettings(app.slice.settings.copy(contours = false))
        assertFalse(prefs.inner.reliefAuto.byUser)
        assertTrue(prefs.log.none { it.startsWith("relief") })
    }

    @Test fun aPhoneThatStuttersSwitchesReliefOffOnceByItselfAndSaysSo() {
        flushTally()
        val app = App()
        repeat(ReliefAuto.WINDOW) { app.slice.mapGestureMeasured(ReliefAuto.Kind.PAN, gesture(100, 20)) }
        assertTrue("clearly below the limit (20 % of the frames): relief stays", app.slice.settings.hillshade)
        assertEquals(ReliefAuto.State(), prefs.inner.reliefAuto)
        repeat(ReliefAuto.WINDOW) { app.slice.mapGestureMeasured(ReliefAuto.Kind.ZOOM, gesture(100, 45)) }
        assertFalse("relief goes off", app.slice.settings.hillshade)
        assertFalse("stored", prefs.inner.settings.hillshade)
        assertTrue("the app's switch: marked, but not as the user's", prefs.inner.reliefAuto.autoOff && !prefs.inner.reliefAuto.byUser)
        assertEquals("a short line on the map", Txt_reliefOff, app.board.mapNote?.text)
        assertTrue("not the full update: no cells, no re-timing", app.log.isEmpty())
        assertTrue(problems.traced("map").any { "Relief switched off by the app" in it })
        // back on by hand in Settings → Map: the user's choice from then on; the app does not do it again
        app.slice.updateSettings(app.slice.settings.copy(hillshade = true))
        assertTrue(prefs.inner.reliefAuto.byUser)
        repeat(3) { app.slice.mapGestureMeasured(ReliefAuto.Kind.PAN, gesture(100, 90)) }
        assertTrue(app.slice.settings.hillshade)
    }

    @Test fun reliefIsNotSwitchedOffWhereTheLineCouldNotShow() {
        flushTally()
        val app = App(); app.mapShown = false
        repeat(3) { app.slice.mapGestureMeasured(ReliefAuto.Kind.PAN, gesture(100, 90)) }
        assertTrue("Navigate / search over the map: counted, not decided", app.slice.settings.hillshade)
        assertEquals(ReliefAuto.State(), prefs.inner.reliefAuto)
        app.mapShown = true
        app.slice.mapGestureMeasured(ReliefAuto.Kind.PAN, gesture(100, 90))
        assertFalse("the gestures of Navigate counted towards it", app.slice.settings.hillshade)
    }

    @Test fun aSmoothPhoneKeepsItsReliefAndEveryGestureIsInTheEventLog() {
        flushTally()
        val app = App()
        repeat(4) { app.slice.mapGestureMeasured(ReliefAuto.Kind.PAN, gesture(100, 5)) }
        assertTrue(app.slice.settings.hillshade)
        assertEquals(4, problems.traced("map").count { "Map pan: 100 frames" in it && "relief on" in it })
    }

    // ---------------------------------------------------------------- Diagnostics, developer tools

    @Test fun developerToolsAreHiddenUntilUnlockedAndStayUnlocked() {
        val app = App()
        assertFalse(app.slice.devToolsShown)
        app.slice.unlockDevTools()
        assertTrue(app.slice.devToolsShown); assertTrue("remembered", prefs.inner.devToolsUnlocked)
        assertTrue(problems.said("ui").any { "Developer tools unlocked" in it })
        assertTrue("after a restart", App().slice.devToolsShown)
    }

    @Test fun theLastVisitToDiagnosticsIsStampedOnEnteringAndOnLeaving() {
        val app = App()
        assertEquals(0L, app.slice.diagnosticsSeenMs)
        now = 5_000L
        app.slice.openSettings(SettingsPage.DIAGNOSTICS)
        assertEquals(5_000L, app.slice.diagnosticsSeenMs); assertEquals(5_000L, prefs.inner.diagnosticsSeenMs)
        now = 9_000L
        app.slice.settingsPage = null
        assertEquals("leaving the page is a visit too", 9_000L, prefs.inner.diagnosticsSeenMs)
        now = 12_000L
        app.slice.openSettings(SettingsPage.MAP)
        assertEquals("another page: not stamped", 9_000L, prefs.inner.diagnosticsSeenMs)
        assertEquals(9_000L, App().slice.diagnosticsSeenMs)
    }

    @Test fun theRoutingInfoIsReadOnEnteringDiagnosticsOnTheIoThreadAndOnlyThen() {
        val io = ManualExecutor("io"); val main = ManualExecutor("main")
        var inIo = false
        prefs.inner.onIo = { inIo }
        val app = App(java.util.concurrent.Executor { r -> io.execute { inIo = true; try { r.run() } finally { inIo = false } } }.asCoroutineDispatcher())
        app.slice.openSettings(SettingsPage.MAP)
        assertEquals(0, io.size)
        app.slice.settingsPage = SettingsPage.DIAGNOSTICS
        assertNull("not read on the calling thread", app.slice.routingInfo)
        assertEquals(1, io.size)
        io.runAll()
        assertEquals("r1-abcdef123456", app.slice.routingInfo?.substringBefore(" · "))
        assertTrue(app.slice.routingInfo!!.endsWith("no routing data"))
        app.slice.settingsPage = SettingsPage.DIAGNOSTICS
        assertEquals("the same page again does not read again", 0, io.size)
        main.runAll()
    }

    @Test fun theRoutingInfoNamesTheDataFilesAndAnUnreadableOneHasNoDateAndIsReported() {
        val dir = java.nio.file.Files.createTempDirectory("rd5").toFile()
        try {
            val short = File(dir, "E10_N45.rd5"); short.writeBytes(ByteArray(10))
            val locked = File(dir, "E5_N45.rd5"); locked.writeBytes(ByteArray(400)); locked.setReadable(false, false)
            org.junit.Assume.assumeFalse("running as a user who reads everything", locked.canRead())
            prefs.inner.files = listOf(short, locked)
            val app = App()
            app.slice.openSettings(SettingsPage.DIAGNOSTICS)
            assertTrue(app.slice.routingInfo!!.endsWith("data E10_N45 –, E5_N45 –"))
            assertTrue("the unreadable file is reported", problems.said("routing").any { "date of routing data E5_N45.rd5 not read" in it })
        } finally { dir.listFiles()?.forEach { it.setReadable(true, false) }; dir.deleteRecursively() }
    }

    @Test fun aFailingReadLeavesNoInfoAndSaysSo() {
        prefs.inner.filesFail = true
        val app = App()
        app.slice.openSettings(SettingsPage.DIAGNOSTICS)
        assertNull(app.slice.routingInfo)
        assertTrue(problems.said("routing", com.onpath.app.debug.Problems.Level.WARN).any { "routing fingerprint not computed" in it })
    }

    private val Txt_reliefOff get() = Txt.s(Res.string.maps_relief_auto_off)
}
