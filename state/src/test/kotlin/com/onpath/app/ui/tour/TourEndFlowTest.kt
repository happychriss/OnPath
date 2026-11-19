package com.onpath.app.ui.tour

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.domain.AppMode
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.NavKind
import com.onpath.app.navigation.NavSession
import com.onpath.app.navigation.NavState
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.recording.RecState
import com.onpath.app.recording.RecordingWire
import com.onpath.app.rides.RideChoice
import com.onpath.app.rides.RideDetector
import com.onpath.app.rides.RideKind
import com.onpath.app.rides.RideView
import com.onpath.app.rides.TraceBuilder
import com.onpath.app.rides.record
import com.onpath.app.routing.Activity
import com.onpath.app.testkit.FakeRecordingClient
import com.onpath.app.testkit.JdbcTourRows
import com.onpath.app.testkit.JdbcTours
import com.onpath.app.testkit.ManualExecutor
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import com.onpath.app.testkit.VirtualDelayDispatcher
import com.onpath.app.testkit.held
import com.onpath.app.time.TourTimes
import com.onpath.app.ui.EndAsk
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.PlanSize
import com.onpath.app.ui.Say
import com.onpath.app.ui.routes.PlannedRow
import com.onpath.app.ui.routes.RoutesState
import com.onpath.app.ui.routes.RoutesStore
import com.onpath.app.ui.routes.ToursStore
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The tour's end (rebuild phase 2 piece 17) as flows of the REAL [TourEndSlice] with the real [TourController] and the real
 * [RoutesState] (the recycle bin's restore), over the real tables ([JdbcTours], the SQL of `TourDb`) – the tour paper's rules
 * 7–12, 26–28, 32 and 35 (docs/design/umbau-phase-2-tour.md). Fakes only at the edges:
 *  - the recording client ([FakeRecordingClient]) ends a tour in the SERVICE'S order (parts → `finished` → `state = null`)
 *    and records, at the moment each command arrives, what the app held ([atSend]);
 *  - `io` is HELD (the test steps it), the scope is main, inline, with virtual time; the tour database and the rows are
 *    reached only on `io`, the host only on main (each call checks the thread it is on);
 *  - the host logs every call with the state at the call: `rec` is what `recording.state` holds (rule 9: after the first io hop
 *    of the end it is `null` – the service sets `finished` before `state = null`).
 *
 * Test data tells things apart: three recorded tours (a hike of 40 min, a short recording of 12 m, a hike with a bus ride), an
 * interrupted tour whose stats the question carries (8,200 m) and the row does not, two split parts, the user's choices.
 * What the tests do NOT prove: the service itself (`RecordingService.finish` – pinned in `TourContractFlowTest`), the
 * view model's own members behind the host (`TourWiringTest` pins the adapters), the sheets' Compose code.
 */
class TourEndFlowTest {
    @get:Rule val problems = ProblemsRule()
    @get:Rule val composeState = OwnComposeSnapshot()

    private val client = FakeRecordingClient()
    private val db = JdbcTours()
    private val log = ArrayList<String>()
    private val said = ArrayList<Triple<String, NoticeKind, NoticePlace>>()

    // ---------------- threads: main (inline, virtual time), io (held) ----------------

    private val ioEx = ManualExecutor("io")
    private var onMain = 0
    private var inIo = false
    private val main = VirtualDelayDispatcher { r -> onMain++; try { r.run() } finally { onMain-- } }
    private val scope = CoroutineScope(main + SupervisorJob())
    private val io = held(ioEx)

    private fun stepIo(): Int { inIo = true; try { return ioEx.runAll() } finally { inIo = false } }
    /** A call the way the screens make it: on the main thread. */
    private fun <T> ui(f: () -> T): T { onMain++; try { return f() } finally { onMain-- } }
    private fun threadOf() = if (onMain > 0) "main" else if (inIo) "io" else "?"
    private fun assertIo(what: String) = check(onMain == 0 && inIo) { "$what must run on io (main=$onMain io=$inIo)" }
    private fun assertMain(what: String) = check(onMain > 0) { "$what must run on the main thread" }

    // ---------------- the data ----------------

    private val walk = record(TraceBuilder().walk(40.0).fixes)
    private val ridden = record(TraceBuilder().walk(40.0).stand(360).bus(legs = 14).walk(45.0, heading = 300.0).fixes)
    private val moving = TourTimes(movingS = 2400.0, breakS = 60.0)

    private fun recorded(name: String, activity: String, segments: List<List<TrackPoint>>, distance: Double, uuid: String, state: String = "done") =
        db.insertTour(TourKind.RECORDED, name, activity, 3, segments, state = state, distance = distance, ascent = 120.0, descent = 80.0,
            duration = 2400.0, uuid = uuid)

    private val hike = recorded("Tour 3 Nov 2026, 09:10", "HIKE", walk.segments, walk.distanceM, "uuid-hike")
    private val shortTour = recorded("Tour 3 Nov 2026, 11:40", "BIKE_TOURING",
        listOf(listOf(TrackPoint(46.6000, 11.1000, 600.0, 1_000L), TrackPoint(46.60005, 11.10005, 600.0, 2_000L), TrackPoint(46.6001, 11.1001, 600.0, 3_000L))),
        12.0, "uuid-short")
    private val busTour = recorded("Tour 4 Nov 2026, 08:00", "HIKE", ridden.segments, ridden.distanceM, "uuid-bus")
    private val partA = recorded("Tour 4 Nov 2026, 09:05", "HIKE", walk.segments, 2100.0, "uuid-part-a")
    private val partB = recorded("Tour 4 Nov 2026, 10:20", "HIKE", walk.segments, 900.0, "uuid-part-b")
    /** An interrupted recording: the row still says "active" and holds no stats; the question carries them. */
    private val interruptedId = recorded("Tour 5 Nov 2026, 07:30", "HIKE", ridden.segments, 0.0, "uuid-interrupted", state = "active")
    private fun interrupted(): Tour = db.getTour(interruptedId)!!.copy(distanceM = 8_200.0, ascentM = 410.0, descentM = 300.0, durationS = 9_000.0)

    private fun tour(id: Long) = db.getTour(id)!!

    // ---------------- the edges ----------------

    private val rows = JdbcTourRows(db) { name -> assertIo("TourRows.$name") }
    private var failGetTour = false
    private var failSegments = false
    private val tours = object : ToursStore {
        override fun getTour(id: Long): Tour? { assertIo("tours.getTour"); if (failGetTour) throw java.sql.SQLException("unreadable"); return db.getTour(id) }
        override fun loadSegments(id: Long): List<List<TrackPoint>> {
            assertIo("tours.loadSegments"); if (failSegments) throw java.sql.SQLException("unreadable"); return db.loadSegments(id)
        }
        override fun insertPlanned(name: String, row: PlannedRow): Long = error("the end does not save a plan")
        override fun replacePlanned(id: Long, row: PlannedRow): Boolean = error("the end does not save a plan")
        override fun rename(id: Long, name: String) = error("the end does not rename")
        override fun setFavourite(id: Long, favourite: Boolean): Boolean = error("the end does not star")
        override fun planPoints(id: Long) = error("not used")
        override fun tourTimes() = error("not used")
        override fun listTours(): List<Tour> = error("not used")
        override fun getTourPlan(id: Long) = error("not used")
    }
    private var failRestore = false
    private val routesStore = object : RoutesStore {
        override fun listTours() = db.listTours()
        override fun listDeleted() = db.listDeleted()
        override fun setDraft(id: Long, draft: Boolean) = db.setDraft(id, draft)
        override fun setDeleted(id: Long, deletedMs: Long?): Boolean {
            assertIo("routes.setDeleted"); if (failRestore) throw java.sql.SQLException("database or disk is full"); return db.setDeleted(id, deletedMs)
        }
        override fun loadSegments(id: Long) = db.loadSegments(id)
        override fun deleteForGood(id: Long) = db.deleteTour(id)
        override fun rename(id: Long, name: String) = db.rename(id, name)
    }
    private val routes = RoutesState(routesStore, clock = { 5_000L })
    private val ctl = TourController(tourRecorder(client))

    private var hostActivity = Activity.BIKE_TOURING
    private var planPoints = 3
    private var routeBack = false
    private var size = PlanSize.MINI
    private var shownId: Long? = null
    private var tourTimesCalls = 0

    private fun snap() = "rec=${client.state.value?.tourId}"

    private val host = object : TourEndHost {
        override val activity: Activity get() = hostActivity
        override fun activityOrNull(name: String?): Activity? = Activity.entries.firstOrNull { it.name == name }
        override fun tourTimes(t: Tour): TourTimes? { assertIo("host.tourTimes"); tourTimesCalls++; log += "tourTimes(${t.id}) io"; return moving }
        override fun nameRecordedTour(t: Tour) { assertMain("nameRecordedTour"); log += "nameRecordedTour(${t.id}) [${snap()} box=${ctl.finishedTour?.id}]" }
        override fun refreshTours() { assertMain("refreshTours"); log += "refreshTours [${snap()} box=${ctl.finishedTour?.id} short=${ctl.shortBinned?.id}]" }
        override fun clearFollow() { assertMain("clearFollow"); log += "clearFollow [${snap()}]" }
        override fun enterMode(m: AppMode) {
            assertMain("enterMode")
            log += "enterMode($m) [${snap()} arrival=${ctl.arrivalAsk} unfinished=${ctl.unfinished?.id} rides=${ctl.unfinishedRides != null}]"
        }
        override fun endGuidanceFlag() { assertMain("endGuidanceFlag"); log += "endGuidanceFlag" }
        override fun kickThumbs() { assertMain("kickThumbs"); log += "kickThumbs" }
        override fun demoEndedWithTour() { assertMain("demoEndedWithTour"); log += "demoEndedWithTour [${snap()}]" }
        override fun tourChanged(id: Long) { log += "tourChanged($id) ${threadOf()} [box=${ctl.finishedTour?.id}]" }
        override fun clearInterruptedNote() { assertMain("clearInterruptedNote"); log += "clearInterruptedNote [unfinished=${ctl.unfinished?.id}]" }
        override val planPoints: Int get() = this@TourEndFlowTest.planPoints
        override fun clearPlan() { assertMain("clearPlan"); log += "clearPlan [arrival=${ctl.arrivalAsk} note=${ctl.newPlanNote != null}]" }
        override var routeBackAsk: Boolean
            get() = routeBack
            set(v) { assertMain("routeBackAsk"); routeBack = v }
        override var planSize: PlanSize
            get() = size
            set(v) { assertMain("planSize"); size = v }
        override val shownTourId: Long? get() = shownId
        override fun reopenShownTour(t: Tour) { assertMain("reopenShownTour"); log += "openTour(${t.id}, d=${t.distanceM.toInt()}) [${snap()}]" }
    }

    private val slice = TourEndSlice(ctl, routes, client, tours, rows, host,
        Say { text, kind, place -> assertMain("say"); said += Triple(text, kind, place) }, scope, io, main)

    /** What the app held at the moment a command reached the service. */
    private class AtSend(
        val action: String, val extras: Map<String, Any>, val endAsk: EndAsk?, val arrival: Boolean, val routeBack: Boolean, val size: PlanSize,
        val row: Tour?, val ioWaiting: Int, val noteCleared: Int, val unfinished: Tour?,
    )
    private val atSend = ArrayList<AtSend>()
    private var notesCleared = 0
    private var rowOfTourAtSend: Long? = null

    init {
        client.onSend = { c ->
            atSend += AtSend(c.action.substringAfterLast('.'), c.extras, ctl.endAsk, ctl.arrivalAsk, routeBack, size,
                rowOfTourAtSend?.let { db.getTour(it) }, ioEx.size, log.count { it.startsWith("clearInterruptedNote") }, ctl.unfinished)
            log += "send ${c.action.substringAfterLast('.')}"
        }
    }

    @After fun close() { scope.cancel(); db.close() }

    private fun recState(id: Long, session: NavSession? = null) = RecState(
        tourId = id, paused = false, distanceM = 100.0, ascentM = 0.0, descentM = 0.0, elapsedS = 60, gpsLost = false, offRouteM = null,
        offRouteAlert = false, followName = session?.name, track = emptyList(),
        nav = session?.let { NavState(it.active, 1, null, null, null, null, it, 0.0) })

    private fun touring(id: Long, session: NavSession? = null) = client.publish(recState(id, session))

    private fun error(text: String, place: NoticePlace) = Triple(text, NoticeKind.ERROR, place)

    // ======================================================================================================================
    // the `finished` collector: rules 7, 8, 9
    // ======================================================================================================================

    /** The order of everything after the service's end (rule 8) and what it sees (rule 9), for a saved tour with two split parts. */
    @Test fun aSavedTourIsHandledInTheOrderOfTheRulesBehindTheIoHopWithNoTourRecording() {
        ctl.arrivalAsk = true; ctl.noteNewPlan(undo = true)
        touring(hike)
        ui { slice.start() }
        client.finish(hike, parts = listOf(partA, partB))
        // at once: the end is marked seen (rule 7) and the tour's row waits to be read on io – nothing else happened (rule 9)
        assertNull("finishedSeen first", client.finished.value)
        assertEquals(emptyList<String>(), log)
        assertEquals("the read waits on io", 1, ioEx.size)
        assertNull(ctl.finishedTour)
        stepIo()
        assertEquals(listOf(
            "tourTimes($hike) io",
            "nameRecordedTour($hike) [rec=null box=$hike]",
            "nameRecordedTour($partA) [rec=null box=$hike]",
            "nameRecordedTour($partB) [rec=null box=$hike]",
            "refreshTours [rec=null box=$hike short=null]",
            "clearFollow [rec=null]",
            "demoEndedWithTour [rec=null]",
            "enterMode(PLAN) [rec=null arrival=true unfinished=null rides=false]",
            "clearPlan [arrival=false note=false]",
            "endGuidanceFlag",
            "kickThumbs",
        ), log)
        assertEquals("the saved box shows the tour just read, with its times", hike, ctl.finishedTour!!.id)
        assertEquals(moving, ctl.finishedTimes)
        assertEquals("the parts were taken once", emptyList<Long>(), client.takeFinishedParts())
        assertNull("saved, not binned", tour(hike).deletedMs)
        assertNull(ctl.shortBinned)
        assertTrue(client.sent.isEmpty())
        assertTrue(problems.traced("tour").any { it == "Tour ended: back to the start – the plan (3 points) goes the \"Neu planen\" way" })
    }

    /** Rule 7 / 10: Löschen chosen → the saved box closed, then the bin write, whose backup is told once; no box, nothing said. */
    @Test fun aDeletedTourGoesToTheBinTheSavedBoxIsClosedBeforeTheWrite() {
        ctl.showFinished(tour(shortTour), null) // an older saved box is still open
        var boxAtWrite: Long? = -1L
        rows.beforeSetDeleted = { _, _ -> boxAtWrite = ctl.finishedTour?.id }
        touring(hike)
        ui { slice.start() }
        ui { slice.endTourAndDelete() }
        client.finish(hike)
        stepIo()
        assertNull("the saved box was closed before the bin write", boxAtWrite)
        assertNotNull("the row is in the bin", tour(hike).deletedMs)
        assertEquals(listOf("tourChanged($hike) io [box=null]"), log.filter { it.startsWith("tourChanged") })
        assertNull(ctl.finishedTour); assertNull(ctl.shortBinned)
        assertEquals("nothing said", emptyList<Triple<String, NoticeKind, NoticePlace>>(), said)
        assertEquals("the rest of the end still ran, in order", listOf("refreshTours", "clearFollow", "demoEndedWithTour", "enterMode(PLAN)", "clearPlan", "endGuidanceFlag", "kickThumbs"),
            log.filter { !it.startsWith("tourChanged") && !it.startsWith("send") }.map { it.substringBefore(" [").substringBefore(" ") })
    }

    /** Rule 7: a bin write that fails keeps the tour in My routes and says so – the rest of the end still runs. */
    @Test fun aDeletedTourWhoseBinWriteFailsStaysAndSaysSo() {
        rows.failOn += "setDeleted"
        touring(hike)
        ui { slice.start() }
        ui { slice.endTourAndDelete() }
        client.finish(hike)
        stepIo()
        assertNull("still in My routes", tour(hike).deletedMs)
        assertEquals(listOf(error(Txt.s(Res.string.msg_track_delete_failed_here), NoticePlace.MAP)), said)
        assertTrue("no backup of a failed write", log.none { it.startsWith("tourChanged") })
        assertTrue(problems.said("db").any { it == "The ended tour could not be moved to the recycle bin; it stays in My routes" })
        assertTrue("the end went on", log.any { it.startsWith("enterMode(PLAN)") } && log.any { it == "kickThumbs" })
    }

    /** Rule 7: a short recording goes to the bin with its box; the box closes before the bin write; the backup is told once. */
    @Test fun aShortRecordingIsBinnedWithItsBoxNotSaved() {
        ctl.showFinished(tour(hike), null)
        var boxAtWrite: Long? = -1L
        rows.beforeSetDeleted = { _, _ -> boxAtWrite = ctl.finishedTour?.id }
        touring(shortTour)
        ui { slice.start() }
        client.finish(shortTour)
        stepIo()
        assertNull(boxAtWrite)
        assertNotNull(tour(shortTour).deletedMs)
        assertEquals(shortTour, ctl.shortBinned!!.id)
        assertNull("no saved box", ctl.finishedTour)
        assertEquals(listOf("tourChanged($shortTour) io [box=null]"), log.filter { it.startsWith("tourChanged") })
        assertTrue(problems.traced("tour").contains("Short recording (12 m, tour $shortTour) → recycle bin; the box with Rückgängig"))
        assertTrue("names are not asked for a tour that is binned", log.none { it.startsWith("nameRecordedTour") })
        assertEquals(0, tourTimesCalls)
        assertTrue(log.any { it.startsWith("enterMode(PLAN)") })
    }

    /** Rule 7 / 27: the bin write's result decides – failed: the tour is kept and the SAVED box shows (with its times), nothing is binned. */
    @Test fun aShortRecordingWhoseBinWriteFailsIsKeptWithTheSavedBox() {
        rows.failOn += "setDeleted"
        touring(shortTour)
        ui { slice.start() }
        client.finish(shortTour)
        stepIo()
        assertNull(tour(shortTour).deletedMs)
        assertNull(ctl.shortBinned)
        assertEquals(shortTour, ctl.finishedTour!!.id)
        assertEquals("its times were read on io", moving, ctl.finishedTimes)
        assertTrue(problems.traced("tour").contains("Short recording (12 m, tour $shortTour) → bin write failed; kept as a tour (saved box)"))
        assertTrue(problems.said("db").any { it == "The ended tour could not be moved to the recycle bin; it stays in My routes" })
        assertTrue("no backup of a failed write", log.none { it.startsWith("tourChanged") })
        assertEquals("the failed short bin says nothing in red (the saved box is the answer)", emptyList<Triple<String, NoticeKind, NoticePlace>>(), said)
    }

    /** Rule 7 / 10: the user's Löschen wins over "short" and is taken ONCE – the next tour (also a short one is not here) is saved. */
    @Test fun theDeleteDecisionWinsOverShortAndIsTakenOnce() {
        touring(shortTour)
        ui { slice.start() }
        ui { slice.endTourAndDelete() }
        client.finish(shortTour)
        stepIo()
        assertNotNull(tour(shortTour).deletedMs)
        assertNull("deleted, not 'short'", ctl.shortBinned)
        assertNull(ctl.finishedTour)
        log.clear(); client.sent.clear()
        // the next tour ends without a decision: saved
        touring(hike)
        client.finish(hike)
        stepIo()
        assertNull(tour(hike).deletedMs)
        assertEquals(hike, ctl.finishedTour!!.id)
    }

    /** Rule 7: "a doubt keeps the tour" – a points read that fails is never short; the saved box shows. */
    @Test fun aReadThatFailsForTheShortCheckKeepsTheTour() {
        failSegments = true
        touring(shortTour)
        ui { slice.start() }
        client.finish(shortTour)
        stepIo()
        assertNull(tour(shortTour).deletedMs)
        assertEquals(shortTour, ctl.finishedTour!!.id)
        assertNull(ctl.shortBinned)
        assertTrue(problems.said("db").any { it == "The ended tour's points could not be read for the short-recording check; kept as a tour" })
    }

    private fun endsAsSaved(id: Long) {
        touring(id)
        ui { slice.start() }
        client.finish(id)
        stepIo()
        assertNull("not binned", tour(id).deletedMs)
        assertNull(ctl.shortBinned)
        assertEquals("the saved box", id, ctl.finishedTour!!.id)
    }

    /** A recording with a bus / car / lift part found is never short (core feature C12). */
    @Test fun aShortLookingTourWithAFoundRideIsNeverBinned() {
        val rec = com.onpath.app.rides.RideRecord(listOf(com.onpath.app.rides.Ride(0, 2, RideKind.VEHICLE)), RideChoice.AS_RECORDED,
            com.onpath.app.rides.TourStats(12.0, 0.0, 0.0, 2.0))
        db.setRides(shortTour, rec, 12.0, 0.0, 0.0, 2.0)
        endsAsSaved(shortTour)
    }

    /** The distance AS RECORDED decides, never the one after a ride choice (review 2026-11-13): the row says 12 m, the record says 4 km. */
    @Test fun theDistanceBeforeTheRideChoiceDecidesNotTheChosenOne() {
        val rec = com.onpath.app.rides.RideRecord(emptyList(), RideChoice.UNTIL, com.onpath.app.rides.TourStats(4_000.0, 0.0, 0.0, 2.0))
        db.setRides(shortTour, rec, 12.0, 0.0, 0.0, 2.0)
        assertEquals(12.0, tour(shortTour).distanceM, 0.0)
        endsAsSaved(shortTour)
    }

    /** Every piece's points count (review 2026-11-13): a GPS gap starts a piece that adds 0 m – a second piece far from the start is no short recording. */
    @Test fun aGapPieceFarAwayKeepsTheTour() {
        val far = recorded("Tour 7 Nov 2026, 12:00", "HIKE",
            listOf(listOf(TrackPoint(46.6000, 11.1000, 600.0, 1_000L), TrackPoint(46.6001, 11.1001, 600.0, 2_000L)),
                listOf(TrackPoint(46.6200, 11.1300, 700.0, 7_000_000L), TrackPoint(46.6201, 11.1301, 700.0, 7_001_000L))),
            12.0, "uuid-far")
        endsAsSaved(far)
    }

    /** Rule 7: a tour not found is never short and shows no saved box; the end still goes through (names are not asked). */
    @Test fun aTourThatIsNotFoundIsNeverShortAndShowsNoBox() {
        db.deleteTour(shortTour)
        ctl.showFinished(tour(hike), moving)
        touring(shortTour)
        ui { slice.start() }
        client.finish(shortTour)
        stepIo()
        assertNull(ctl.finishedTour); assertNull(ctl.finishedTimes); assertNull(ctl.shortBinned)
        assertTrue(log.none { it.startsWith("nameRecordedTour") })
        assertTrue(log.any { it.startsWith("enterMode(PLAN)") })
    }

    /** Latent bug L1, kept as it is: one failed read of the tour and the collector is over – no later end is handled. */
    @Test fun L1_aFailedReadEndsTheCollectorForGood() {
        failGetTour = true
        touring(hike)
        ui { slice.start() }
        client.finish(hike)
        stepIo()
        assertNull("finishedSeen was said", client.finished.value)
        assertEquals("nothing else happened: the app would stay in Navigate", emptyList<String>(), log)
        assertTrue(problems.said().any { it.contains("start-up work") || it.contains("failed") || it.contains("unreadable") })
        failGetTour = false
        client.finish(busTour)
        stepIo()
        assertEquals("no later tour end is handled", emptyList<String>(), log)
        assertEquals("…and the end is not even marked seen", busTour, client.finished.value)
    }

    /** The collector's first pass: an end the service already published is handled when the slice starts (a re-created screen). */
    @Test fun anEndPublishedBeforeStartIsHandledByTheFirstPassOfTheCollector() {
        client.finish(hike)
        ui { slice.start() }
        assertNull("seen at once, inline", client.finished.value)
        assertEquals("the read is held", 1, ioEx.size)
        stepIo()
        assertEquals(hike, ctl.finishedTour!!.id)
    }

    /** No end, nothing: a null `finished` does nothing. */
    @Test fun noEndNoWork() {
        ui { slice.start() }
        stepIo()
        assertEquals(emptyList<String>(), log)
        assertEquals(0, ioEx.size)
    }

    // ======================================================================================================================
    // ending: rules 10, 11, 32
    // ======================================================================================================================

    /** Rule 10 / 32: Save – the end sheet and the arrival box go and the question about the plan's route is closed BEFORE the service is told. */
    @Test fun endTourClosesTheSheetsThenStopsTheService() {
        touring(hike)
        ctl.endAsk = EndAsk.Plain; ctl.arrivalAsk = true; routeBack = true; size = PlanSize.MINI
        rowOfTourAtSend = hike
        ui { slice.endTour() }
        val s = atSend.single()
        assertEquals("STOP", s.action)
        assertNull("the decision (end(false)) was set before STOP: the sheet is gone", s.endAsk)
        assertFalse("…the arrival box too", s.arrival)
        assertFalse("the route-back question of this tour is closed before STOP", s.routeBack)
        assertEquals("the one-row sheet steps to the usual one before STOP", PlanSize.COMPACT, s.size)
    }

    @Test fun endTourAndDeleteClosesTheSheetsThenStopsTheService() {
        touring(hike)
        ctl.endAsk = EndAsk.Plain; ctl.arrivalAsk = true; routeBack = true; size = PlanSize.FULL
        ui { slice.start() }
        ui { slice.endTourAndDelete() }
        val s = atSend.single()
        assertEquals("STOP", s.action)
        assertNull(s.endAsk); assertFalse(s.arrival)
        assertFalse(s.routeBack)
        assertEquals("another size stays as it is", PlanSize.FULL, s.size)
        // …and what the service then reports is a DELETE (taken once): proven by aDeletedTourGoesToTheBin…
        client.finish(hike); stepIo()
        assertNotNull(tour(hike).deletedMs)
    }

    @Test fun stopRecordingClosesTheRouteQuestionThenStops() {
        touring(hike)
        routeBack = true; size = PlanSize.MINI
        ui { slice.stopRecording() }
        val s = atSend.single()
        assertEquals("STOP", s.action)
        assertFalse(s.routeBack)
        assertEquals(PlanSize.COMPACT, s.size)
        assertEquals(1, client.sent.size)
    }

    @Test fun pauseAndResumePassThrough() {
        touring(hike)
        ui { slice.pauseRecording(true); slice.pauseRecording(false) }
        assertEquals(listOf("PAUSE", "RESUME"), client.actions)
    }

    /** Rule 11: the ride choice is stored on the tour BEFORE STOP (the service applies it in `finish`); the sheet closes at once. */
    @Test fun theRideChoiceIsStoredOnTheTourBeforeTheServiceIsToldToStop() {
        touring(busTour)
        val s = summaryOf(busTour)
        ctl.endAsk = EndAsk.Rides(busTour, s)
        rowOfTourAtSend = busTour
        ui { slice.endTourWith(EndAsk.Rides(busTour, s), RideChoice.UNTIL) }
        assertNull("the sheet closed at once", ctl.endAsk)
        assertEquals("the write is on io: nothing stored and STOP not sent yet", emptyList<String>(), client.actions)
        assertNull(tour(busTour).rides)
        stepIo()
        val at = atSend.single()
        assertEquals("STOP", at.action)
        assertEquals("at STOP the tour row already holds the choice", RideChoice.UNTIL, at.row!!.rides!!.choice)
        assertEquals(s.rides, at.row.rides!!.rides)
        assertEquals("the stats stay as recorded until the service applies the choice at its end", ridden.distanceM, at.row.distanceM, 1e-6)
        assertEquals("the record keeps what was recorded", ridden.distanceM, at.row.rides!!.recorded.distanceM, 1e-6)
        assertFalse(at.arrival)
    }

    /** Rule 11: a failed write of the choice still ends the tour, as recorded, and is reported. */
    @Test fun aFailedChoiceWriteStillEndsTheTourAndIsReported() {
        touring(busTour)
        val s = summaryOf(busTour)
        rows.failOn += "rides.setRides"
        rowOfTourAtSend = busTour
        ui { slice.endTourWith(EndAsk.Rides(busTour, s), RideChoice.NOT_COUNTED) }
        stepIo()
        assertEquals(listOf("STOP"), client.actions)
        assertNull("nothing stored", atSend.single().row!!.rides)
        assertTrue(problems.said("rides").any { it == "The choice for the ride could not be stored – the tour is saved as recorded" })
    }

    // ======================================================================================================================
    // "End tour": rule 12
    // ======================================================================================================================

    private fun summaryOf(id: Long, onFoot: Boolean = true): RideView.Summary {
        val segs = db.loadSegments(id)
        return RideView.summary(segs, RideDetector.detect(segs, if (onFoot) Activity.HIKE else Activity.BIKE_TOURING), onFoot)!!
    }

    @Test fun endTourWithoutATourAsksPlainOrSavesAtOnce() {
        ctl.arrivalAsk = true
        ui { slice.askEndTour() }
        assertFalse("the arrival box goes first", ctl.arrivalAsk)
        assertEquals(EndAsk.Plain, ctl.endAsk)
        assertEquals(0, ioEx.size)
        ctl.endAsk = null
        ui { slice.askEndTour(saveIfNoRides = true) }
        assertNull("no question: the tour is ended", ctl.endAsk)
        assertEquals(listOf("STOP"), client.actions)
    }

    /** The ride check runs on io; the arrival box goes at once (before the hop); a tour with a ride gets the summary, with the user's activity rules below. */
    @Test fun endTourWithARideShowsTheSummaryAfterTheCheckOnIo() {
        touring(busTour)
        ctl.arrivalAsk = true
        ui { slice.askEndTour() }
        assertFalse("the arrival box went before the io hop", ctl.arrivalAsk)
        assertEquals(1, ioEx.size)
        assertNull(ctl.endAsk)
        stepIo()
        val ask = ctl.endAsk as EndAsk.Rides
        assertEquals(busTour, ask.tourId)
        assertEquals(listOf(RideKind.VEHICLE), ask.summary.rides.map { it.kind })
        assertEquals(emptyList<String>(), client.actions)
    }

    @Test fun endTourWithoutARideAsksPlainOrSavesAtOnceAsAskedFor() {
        touring(hike)
        ui { slice.askEndTour() }
        stepIo()
        assertEquals(EndAsk.Plain, ctl.endAsk)
        assertTrue(client.actions.isEmpty())
        ctl.endAsk = null
        ui { slice.askEndTour(saveIfNoRides = true) }
        stepIo()
        assertNull(ctl.endAsk)
        assertEquals(listOf("STOP"), client.actions)
    }

    /** Rule 12: the result of the ride check is dropped when that tour is no longer recording (ended meanwhile / another tour). */
    @Test fun aRideCheckOfATourThatEndedMeanwhileIsDropped() {
        touring(busTour)
        ui { slice.askEndTour() }
        client.publish(null) // the notification ended it
        stepIo()
        assertNull("no end sheet for a tour that already ended", ctl.endAsk)
        assertEquals(emptyList<String>(), client.actions)
        // another tour
        touring(busTour)
        ui { slice.askEndTour(saveIfNoRides = true) }
        touring(hike)
        stepIo()
        assertNull(ctl.endAsk)
        assertEquals("no STOP for the other tour either", emptyList<String>(), client.actions)
    }

    /** Rule 12: a failed check asks plainly (the failure is reported) – and with saveIfNoRides the tour is saved at once. */
    @Test fun aFailedRideCheckAsksPlainly() {
        touring(busTour)
        failSegments = true
        ui { slice.askEndTour() }
        stepIo()
        assertEquals(EndAsk.Plain, ctl.endAsk)
        assertTrue(problems.said("rides").any { it == "Ride check at End tour failed – asked without it" })
    }

    /**
     * Which activity the check reads the track as: the service's session first, else the tour's stored one, else the plan's.
     * The bus ride is found as a ride only when the track is read as a hike (a bike goes that fast itself): each case offers the
     * right source as the hike and the wrong ones as a bike, so the summary appears only when the right source was used.
     */
    @Test fun theRideCheckReadsTheTrackAsTheSessionsActivityThenTheToursThenThePlans() {
        val route = line(0.0 to 0.0, 1500.0 to 0.0)
        // 1. a session says hike; the tour row and the plan say bike
        db.exec("UPDATE tours SET activity='BIKE_TOURING' WHERE id=?", busTour)
        hostActivity = Activity.BIKE_TOURING
        touring(busTour, NavSession.start(NavKind.PLAN, "Plan", Activity.HIKE, 3, route))
        ui { slice.askEndTour() }; stepIo()
        assertTrue("the session's activity (hike) wins over the tour row and the plan (bike)", ctl.endAsk is EndAsk.Rides)
        // 2. no session; the tour row says hike, the plan says bike
        ctl.endAsk = null
        db.exec("UPDATE tours SET activity='HIKE' WHERE id=?", busTour)
        touring(busTour)
        ui { slice.askEndTour() }; stepIo()
        assertTrue("the tour's stored activity (hike) wins over the plan's (bike)", ctl.endAsk is EndAsk.Rides)
        // 3. no session, the tour row names none: the plan's activity - bike: no ride; hike: the ride
        ctl.endAsk = null
        db.exec("UPDATE tours SET activity=NULL WHERE id=?", busTour)
        ui { slice.askEndTour() }; stepIo()
        assertEquals("the plan's bike finds no ride", EndAsk.Plain, ctl.endAsk)
        ctl.endAsk = null
        hostActivity = Activity.HIKE
        ui { slice.askEndTour() }; stepIo()
        assertTrue("the plan's hike finds the ride", ctl.endAsk is EndAsk.Rides)
    }

    // ======================================================================================================================
    // the interrupted tour: rule 26
    // ======================================================================================================================

    @Test fun continueLogsAndClearsTheNoteThenTheServiceRecordsIntoTheSameTour() {
        val t = interrupted()
        ctl.showUnfinished(t, emptyList())
        ui { slice.continueRecording(t) }
        val s = atSend.single()
        assertEquals("START", s.action)
        assertEquals(mapOf<String, Any>(RecordingWire.EXTRA_CONTINUE_ID to interruptedId), s.extras)
        assertEquals("the notification was cleared before the command", 1, s.noteCleared)
        assertNull("the question was gone at the command", s.unfinished)
        assertTrue(problems.said("recording").any { it == "Interrupted recording of '${t.name}': user chose Continue" })
        assertEquals(0, ioEx.size)
        assertEquals("nothing written", "active", tour(interruptedId).state)
    }

    /** The service came back meanwhile and records this very tour: nothing is written, the mode is Navigate. */
    @Test fun saveOfATourTheServiceRecordsAgainWritesNothingAndGoesToNavigate() {
        val t = interrupted()
        ctl.showUnfinished(t, emptyList())
        touring(interruptedId)
        ui { slice.saveUnfinished(t) }
        assertEquals(listOf("clearInterruptedNote [unfinished=${interruptedId}]", "enterMode(NAVIGATE) [rec=$interruptedId arrival=false unfinished=null rides=false]"), log)
        assertEquals(0, ioEx.size)
        assertEquals("active", tour(interruptedId).state)
        assertEquals(0.0, tour(interruptedId).distanceM, 0.0)
        assertTrue(problems.said("recording").any { it == "Interrupted recording of '${t.name}': user chose Save" })
    }

    /** Another tour recording is not "the same tour": the interrupted one is saved. */
    @Test fun saveWhileAnotherTourRecordsSavesTheInterruptedOne() {
        val t = interrupted()
        touring(hike)
        ui { slice.saveUnfinished(t) }
        assertTrue(log.contains("enterMode(PLAN) [rec=$hike arrival=false unfinished=null rides=false]"))
        stepIo()
        assertEquals("done", tour(interruptedId).state)
    }

    /** Save without a ride choice: the question closes, the mode is Plan, then on io the stats come from the question (not the row), rides are only marked, the backups, the list on main. */
    @Test fun saveStoresTheQuestionsStatsFinishesAndTellsTheBackupThenTheList() {
        val t = interrupted()
        ctl.showUnfinished(t, emptyList())
        ui { slice.saveUnfinished(t) }
        // synchronously: log, the note, the question closed, the mode – all before io
        assertEquals(listOf(
            "clearInterruptedNote [unfinished=$interruptedId]",
            "enterMode(PLAN) [rec=null arrival=false unfinished=null rides=false]",
        ), log)
        assertEquals(1, ioEx.size)
        assertEquals("active", tour(interruptedId).state)
        stepIo()
        val row = tour(interruptedId)
        assertEquals("done", row.state)
        assertEquals(8_200.0, row.distanceM, 0.0); assertEquals(410.0, row.ascentM, 0.0); assertEquals(300.0, row.descentM, 0.0); assertEquals(9_000.0, row.durationS, 0.0)
        assertNotNull("the ride found is marked, as recorded", row.rides)
        assertEquals(RideChoice.AS_RECORDED, row.rides!!.choice)
        assertEquals("the stats of the question are what was recorded", 8_200.0, row.rides!!.recorded.distanceM, 0.0)
        assertEquals(listOf("tourChanged($interruptedId) io [box=null]"), log.filter { it.startsWith("tourChanged") })
        assertEquals("the list is read again, on main, last", "refreshTours [rec=null box=null short=null]", log.last())
        assertEquals(emptyList<Triple<String, NoticeKind, NoticePlace>>(), said)
    }

    /** Save with the summary's choice: stored first, then applied by `finish` (split: the new tours are backed up after the tour, the list is last). */
    @Test fun saveWithASplitChoiceStoresItAppliesItAndBacksUpTheTourAndItsParts() {
        val t = interrupted()
        val s = summaryOf(interruptedId)
        ctl.showUnfinished(t, emptyList()); ctl.unfinishedRides = t to s
        ui { slice.saveUnfinished(t, RideChoice.SPLIT) }
        assertNull("the summary closed with the question", ctl.unfinishedRides)
        assertTrue(log.first().startsWith("clearInterruptedNote"))
        assertTrue(log[1].contains("rides=false"))
        stepIo()
        val row = tour(interruptedId)
        assertEquals(RideChoice.SPLIT, row.rides!!.choice)
        assertEquals(s.rides, row.rides!!.rides)
        val parts = db.listTours().filter { it.id != interruptedId && it.createdMs > 0 && it.uuid.isNotEmpty() && it.id !in listOf(hike, shortTour, busTour, partA, partB) }
        assertEquals("one part after the ride", 1, parts.size)
        assertEquals(listOf("tourChanged($interruptedId) io [box=null]", "tourChanged(${parts.single().id}) io [box=null]"), log.filter { it.startsWith("tourChanged") })
        assertEquals("refreshTours [rec=null box=null short=null]", log.last())
        assertTrue("the backup calls come before the list", log.indexOfLast { it.startsWith("tourChanged") } < log.lastIndex)
    }

    /** A failed write of the choice is reported; the tour is still saved as recorded. */
    @Test fun saveWithAFailedChoiceWriteIsReportedAndStillSaved() {
        val t = interrupted()
        val s = summaryOf(interruptedId)
        ctl.unfinishedRides = t to s
        rows.failOn += "rides.setRides"
        ui { slice.saveUnfinished(t, RideChoice.UNTIL) }
        stepIo()
        assertEquals("done", tour(interruptedId).state)
        assertTrue(problems.said("rides").any { it == "The choice for the ride could not be stored – the tour is saved as recorded" })
        assertEquals(RideChoice.AS_RECORDED, tour(interruptedId).rides!!.choice)
        assertEquals("tourChanged($interruptedId) io [box=null]", log.first { it.startsWith("tourChanged") })
    }

    /** Latent bug L8, kept: the stats write is unguarded – a failure is only in the problem log, nothing else follows. */
    @Test fun L8_aFailedStatsWriteIsOnlyInTheProblemLogAndNothingFollows() {
        val t = interrupted()
        rows.failOn += "updateStats"
        ui { slice.saveUnfinished(t) }
        stepIo()
        assertEquals("active", tour(interruptedId).state)
        assertTrue("no backup, no list", log.none { it.startsWith("tourChanged") || it.startsWith("refreshTours") })
        assertTrue(problems.said().any { it.contains("saveUnfinished") || it.contains("disk is full") })
    }

    @Test fun askSaveOfAnInterruptedTourWithARideShowsTheSummaryAndWritesNothing() {
        val t = interrupted()
        ctl.showUnfinished(t, emptyList())
        ui { slice.askSaveUnfinished(t) }
        assertNull("the question closed", ctl.unfinished)
        stepIo()
        val s = ctl.unfinishedRides!!
        assertEquals(interruptedId, s.first.id)
        assertEquals(listOf(RideKind.VEHICLE), s.second.rides.map { it.kind })
        assertEquals("active", tour(interruptedId).state)
        assertTrue(log.none { it.startsWith("enterMode") })
    }

    @Test fun askSaveOfAnInterruptedTourWithoutARideSavesAtOnce() {
        val flat = recorded("Tour 6 Nov 2026, 07:30", "HIKE", walk.segments, 0.0, "uuid-flat", state = "active")
        val t = tour(flat).copy(distanceM = 3_000.0)
        ui { slice.askSaveUnfinished(t) }
        stepIo()
        assertEquals("done", tour(flat).state)
        assertEquals(3_000.0, tour(flat).distanceM, 0.0)
        assertNull(ctl.unfinishedRides)
        assertTrue(log.any { it.startsWith("enterMode(PLAN)") })
    }

    @Test fun askSaveOfATourTheServiceRecordsAgainGoesToNavigateAndWritesNothing() {
        val t = interrupted()
        touring(interruptedId)
        ui { slice.askSaveUnfinished(t) }
        assertEquals(listOf("enterMode(NAVIGATE) [rec=$interruptedId arrival=false unfinished=null rides=false]"), log)
        assertEquals(0, ioEx.size)
    }

    @Test fun askSaveWithAFailedRideCheckSavesAsRecorded() {
        val t = interrupted()
        failSegments = true
        ui { slice.askSaveUnfinished(t) }
        stepIo()
        assertTrue(problems.said("rides").any { it == "Ride check of the unfinished tour failed – saved without it" })
        // saved as recorded: the write path needs the segments through the rides store, not through tours – the row is done
        assertEquals("done", tour(interruptedId).state)
    }

    /** Delete: "done" first (restored from the bin it is a normal tour), then the bin; the backup only on success; the list on main. */
    @Test fun deleteMarksTheTourDoneThenBinsItThenTellsTheBackupAndTheList() {
        val t = interrupted()
        ctl.showUnfinished(t, emptyList())
        var stateAtBin: String? = null
        rows.beforeSetDeleted = { id, _ -> stateAtBin = tour(id).state }
        ui { slice.deleteUnfinished(t) }
        assertEquals(listOf(
            "clearInterruptedNote [unfinished=$interruptedId]",
            "enterMode(PLAN) [rec=null arrival=false unfinished=null rides=false]",
        ), log)
        assertTrue(problems.traced("tour").contains("Unfinished tour $interruptedId deleted (recycle bin)"))
        stepIo()
        assertEquals("the bin write saw the tour already 'done'", "done", stateAtBin)
        val row = tour(interruptedId)
        assertEquals("done", row.state); assertEquals(8_200.0, row.distanceM, 0.0)
        assertNotNull(row.deletedMs)
        assertEquals(listOf("tourChanged($interruptedId) io [box=null]", "refreshTours [rec=null box=null short=null]"), log.drop(2))
        assertEquals(emptyList<Triple<String, NoticeKind, NoticePlace>>(), said)
        assertTrue(problems.said("recording").any { it == "Interrupted recording of '${t.name}': user chose Delete (recycle bin)" })
    }

    @Test fun deleteWhoseWriteFailsSaysSoAndTellsNoBackup() {
        val t = interrupted()
        rows.failOn += "setDeleted"
        ui { slice.deleteUnfinished(t) }
        stepIo()
        assertNull(tour(interruptedId).deletedMs)
        assertEquals(listOf(error(Txt.s(Res.string.msg_track_delete_failed), NoticePlace.MAP)), said)
        assertTrue(log.none { it.startsWith("tourChanged") })
        assertEquals("the list is read again either way", "refreshTours [rec=null box=null short=null]", log.last())
        assertTrue(problems.said("db").any { it == "The unfinished tour could not be moved to the recycle bin" })
    }

    @Test fun deleteWhenTheStatsWriteFailsDoesNotBin() {
        val t = interrupted()
        rows.failOn += "updateStats"
        ui { slice.deleteUnfinished(t) }
        stepIo()
        assertNull("not binned: 'done' could not be written first", tour(interruptedId).deletedMs)
        assertEquals(1, said.size)
    }

    @Test fun deleteOfATourTheServiceRecordsAgainWritesNothingAndGoesToNavigate() {
        val t = interrupted()
        ctl.showUnfinished(t, emptyList())
        touring(interruptedId)
        ui { slice.deleteUnfinished(t) }
        assertEquals(listOf("clearInterruptedNote [unfinished=$interruptedId]", "enterMode(NAVIGATE) [rec=$interruptedId arrival=false unfinished=null rides=false]"), log)
        assertEquals(0, ioEx.size)
        assertNull(tour(interruptedId).deletedMs)
        assertEquals("active", tour(interruptedId).state)
        assertTrue("no trace of a deletion", problems.traced("tour").none { it.contains("deleted (recycle bin)") })
    }

    // ======================================================================================================================
    // the short recording: rule 27
    // ======================================================================================================================

    private fun binShort() {
        touring(shortTour); ui { slice.start() }; client.finish(shortTour); stepIo(); log.clear()
        assertEquals(shortTour, ctl.shortBinned!!.id)
    }

    /** Rückgängig: restore → the backup → the saved box → the name → the list (last), the box is the tour read again. */
    @Test fun undoBringsTheShortTourBackAsANormalTourInTheOrderOfTheRule() {
        binShort()
        ui { slice.undoShortRecording() }
        assertNull("the short box closed at once", ctl.shortBinned)
        assertEquals("the restore waits on io", 1, ioEx.size)
        stepIo()
        assertNull("restored", tour(shortTour).deletedMs)
        assertEquals(listOf(
            "tourChanged($shortTour) main [box=null]",
            "nameRecordedTour($shortTour) [rec=null box=$shortTour]",
            "refreshTours [rec=null box=$shortTour short=null]",
        ), log.filter { !it.startsWith("tourTimes") })
        assertEquals("the box carries its times", moving, ctl.finishedTimes)
        assertTrue(log.indexOf("tourTimes($shortTour) io") < log.indexOfFirst { it.startsWith("nameRecordedTour") })
        assertTrue(problems.traced("tour").contains("Rückgängig: short recording $shortTour back from the recycle bin as a tour"))
    }

    @Test fun aFailedRestoreSaysSoKeepsTheTourInTheBinAndReadsTheList() {
        binShort()
        failRestore = true
        val name = ctl.shortBinned!!.name
        ui { slice.undoShortRecording() }
        stepIo()
        assertNotNull("still in the bin", tour(shortTour).deletedMs)
        assertEquals(listOf(error(Txt.s(Res.string.msg_tour_restore_failed, name), NoticePlace.MAP)), said)
        assertEquals(listOf("refreshTours [rec=null box=null short=null]"), log)
        assertNull(ctl.finishedTour)
        assertTrue(problems.said("db").any { it == "The short recording could not be brought back from the recycle bin" })
    }

    @Test fun aTourGoneFromTheBinSaysSoToo() {
        binShort()
        val name = ctl.shortBinned!!.name
        db.deleteTour(shortTour)
        ui { slice.undoShortRecording() }
        stepIo()
        assertEquals(listOf(error(Txt.s(Res.string.msg_tour_restore_failed, name), NoticePlace.MAP)), said)
        assertEquals(listOf("refreshTours [rec=null box=null short=null]"), log)
        assertTrue(log.none { it.startsWith("tourChanged") })
    }

    @Test fun undoWithNoShortBoxDoesNothing() {
        ui { slice.undoShortRecording() }
        assertEquals(0, ioEx.size)
        assertEquals(emptyList<String>(), log)
    }

    /** OK on the short box: it stays in the bin – nothing is restored, written or told. */
    @Test fun okOnTheShortBoxLeavesTheTourInTheBin() {
        binShort()
        ui { slice.closeShortRecording() }
        assertNull(ctl.shortBinned)
        assertNotNull(tour(shortTour).deletedMs)
        assertEquals(0, ioEx.size)
        assertEquals(emptyList<String>(), log)
        assertTrue(problems.traced("tour").contains("Short recording $shortTour: OK – stays in the recycle bin"))
    }

    // ======================================================================================================================
    // the arrival box, Edit rides: rule 35
    // ======================================================================================================================

    @Test fun keepRecordingAfterArrivalClosesTheBoxAndTouchesNothingElse() {
        touring(hike)
        ctl.arrivalAsk = true
        ui { slice.keepRecordingAfterArrival() }
        assertFalse(ctl.arrivalAsk)
        assertEquals(emptyList<String>(), client.actions)
        assertEquals(emptyList<String>(), log)
        assertEquals(0, ioEx.size)
        assertTrue(problems.traced("tour").contains("Arrival: keep recording"))
    }

    private fun savedWithRides(choice: RideChoice = RideChoice.AS_RECORDED): Tour {
        val rides = RideDetector.detect(db.loadSegments(busTour), Activity.HIKE)
        val rec = com.onpath.app.rides.RideRecord(rides, choice, com.onpath.app.rides.TourStats(ridden.distanceM, ridden.ascentM, ridden.descentM, ridden.durationS))
        db.setRides(busTour, rec, ridden.distanceM, ridden.ascentM, ridden.descentM, ridden.durationS)
        return tour(busTour)
    }

    @Test fun openEditRidesShowsTheSummaryOfASavedTourAndAnUnreadableOneSaysSo() {
        val t = savedWithRides()
        ui { slice.openEditRides(t) }
        stepIo()
        val (shownTour, s) = slice.editRides!!
        assertEquals(busTour, shownTour.id)
        assertEquals(t.rides!!.rides, s.rides)
        assertTrue("no split in Edit rides", s.options.none { it.choice == RideChoice.SPLIT })
        slice.editRides = null
        failSegments = true
        ui { slice.openEditRides(t) }
        stepIo()
        assertNull(slice.editRides)
        assertEquals(listOf(error(Txt.s(Res.string.msg_rides_read_failed), NoticePlace.ROUTES)), said)
        assertTrue(problems.said("rides").any { it == "Rides of a tour could not be shown" })
        // a tour without rides: nothing
        said.clear()
        ui { slice.openEditRides(tour(hike)) }
        assertEquals(0, ioEx.size)
        assertNull(slice.editRides)
    }

    /** The edit: the sheet closes at once, the edit is written on io, the backup told after the success, the list read, the shown tour re-opened (read again). */
    @Test fun applyEditRidesClosesEditsBacksUpListsAndReopensTheShownTour() {
        val t = savedWithRides()
        slice.editRides = t to summaryOf(busTour)
        shownId = busTour
        ui { slice.applyEditRides(t, RideChoice.UNTIL) }
        assertNull("closed at once", slice.editRides)
        assertEquals(RideChoice.AS_RECORDED, tour(busTour).rides!!.choice)
        stepIo()
        val row = tour(busTour)
        assertEquals(RideChoice.UNTIL, row.rides!!.choice)
        assertEquals(listOf(
            "tourChanged($busTour) io [box=null]",
            "refreshTours [rec=null box=null short=null]",
            "openTour($busTour, d=${row.distanceM.toInt()}) [rec=null]",
        ), log)
        assertTrue("the tour reopened is the edited one, read again", row.distanceM < ridden.distanceM - 5_000)
        assertEquals(emptyList<Triple<String, NoticeKind, NoticePlace>>(), said)
    }

    @Test fun applyEditRidesOfATourThatIsNotShownDoesNotReopenIt() {
        val t = savedWithRides()
        shownId = hike
        ui { slice.applyEditRides(t, RideChoice.UNTIL) }
        stepIo()
        assertEquals(listOf("tourChanged($busTour) io [box=null]", "refreshTours [rec=null box=null short=null]"), log)
        shownId = null
        log.clear()
        ui { slice.applyEditRides(tour(busTour), RideChoice.NOT_COUNTED) }
        stepIo()
        assertTrue(log.none { it.startsWith("openTour") })
    }

    /** A failed edit: no backup, the error on My routes, the list read again; the tour stays as it was. */
    @Test fun aFailedEditSaysSoTellsNoBackupAndStillReadsTheList() {
        val t = savedWithRides()
        rows.failOn += "rides.setRides"
        shownId = busTour
        ui { slice.applyEditRides(t, RideChoice.UNTIL) }
        stepIo()
        assertEquals(RideChoice.AS_RECORDED, tour(busTour).rides!!.choice)
        assertEquals(listOf(error(Txt.s(Res.string.msg_rides_change_failed), NoticePlace.ROUTES)), said)
        assertTrue("no backup of a failed edit", log.none { it.startsWith("tourChanged") })
        assertEquals("refreshTours [rec=null box=null short=null]", log.first())
        assertTrue(problems.said("rides").any { it == "Changing the rides of a tour failed; it stays as it was" })
    }
}
