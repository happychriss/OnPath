package com.onpath.app.ui.tour

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.domain.plan.PlanSession
import com.onpath.app.domain.tour.EndSheetGuard
import com.onpath.app.domain.tour.ShortRecording
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.EndAsk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Random but seeded tour flows against [TourController] (audit package 4 stage 2): start, pause, resume, plan edits
 * during the tour ([PlanSession]), Undo / Redo, the end sheet with presses at random moments after it appeared
 * ([EndSheetGuard]), end-save, end-delete, arrival, the app killed and restarted (the service sticky or gone → the
 * "interrupted tour" question) – and after every step the rules that must always hold:
 * 1. a recording is never lost without an explicit delete (every tour started is in the store, in the bin only when
 *    the user chose Löschen – or when it ended with less than [ShortRecording.MIN_M] of track (owner 2026-11-12): then
 *    with the notice's Rückgängig, which brings it back as a normal tour; a tour still marked as recording with no
 *    service running is shown as interrupted);
 * 2. ending always leaves no running recording, and no end sheet, arrival box or "Neuer Plan" line of the old tour;
 * 3. the tour follows the plan shown or nothing (the stand-in follows what the view model would: the plan at the start
 *    and after every edit during the tour; nothing after the end);
 * 4. the end sheet never saves or deletes from a press within [EndSheetGuard.GUARD_MS] of appearing;
 * 5. pause / resume never changes the plan;
 * 6. after an end (save or delete) the map is back at the start – the plan empty (owner 2026-11-07, "Tour beenden" as
 *    "✕ Neu planen") – and the plan from before is in My routes: saved as it was (at "Los geht's") or under Zuletzt
 *    when it was changed on the way; one Undo brings it back on the map.
 * A failing flow prints its seed and steps, so it can be replayed.
 */
class TourFlowPropertyTest {

    private enum class Op { START, PAUSE, RESUME, EDIT, UNDO, REDO, ASK_END, PRESS, ARRIVE, ARRIVAL_KEEP, LEAVE_ARRIVAL, RESTART, UNFINISHED, NEW_PLAN, WALK, UNDO_SHORT }
    private enum class Press { SAVE, DELETE, KEEP }

    /** The recording service and the database as the controller sees them. */
    private class FakeService : TourRecorder {
        var nextId = 1L
        var running: Long? = null
        var paused = false
        val db = LinkedHashMap<Long, String>() // id → "active" / "done" / "bin"
        /** The track so far per tour (m) – a start by mistake stays at 0 m. */
        val distance = HashMap<Long, Double>()
        var finished: Long? = null
        override fun start(activity: String, sac: Int) {
            if (running != null) return // the service ignores a second start while recording
            val id = nextId++; db[id] = "active"; distance[id] = 0.0; running = id; paused = false
        }
        /** GPS fixes: the track grows while recording (never while paused). */
        fun walk(m: Double) { val id = running ?: return; if (!paused) distance[id] = distance.getValue(id) + m }
        override fun continueTour(tourId: Long) { if (running == null && db[tourId] == "active") { running = tourId; paused = false } }
        override fun pause(pause: Boolean) { if (running != null) paused = pause }
        override fun stop() { val id = running ?: return; db[id] = "done"; running = null; paused = false; finished = id }
    }


    private class Flow(seed: Int) {
        val rnd = Random(seed)
        val service = FakeService()
        var c = TourController(service)
        val session = PlanSession(mutableListOf())
        var followed: List<GeoPoint>? = null
        val explicitDeletes = HashSet<Long>()
        /** Tours in the bin by the short rule, each with its Rückgängig still on screen (the notice). */
        val shortBinned = LinkedHashSet<Long>()
        /** Tours brought back by Rückgängig. */
        val undone = HashSet<Long>()
        /** My routes → Planned (saved) and Zuletzt (drafts), as point lists. */
        val saved = ArrayList<List<GeoPoint>>()
        val drafts = ArrayList<List<GeoPoint>>()
        /** The plan saved / loaded last: the plan counts as unsaved while it differs (AppViewModel.planUnsaved). */
        var savedPoints: List<GeoPoint>? = null
        /** The plan on the map right before the end that happened in this step (null = no end in this step). */
        var planBeforeEnd: List<GeoPoint>? = null
        var shownAt = 0L
        var now = 0L
        var earlyActions = 0
        val log = ArrayList<String>()

        fun tour(id: Long) = Tour(id, TourKind.RECORDED, "Tour $id", 0L, "HIKE", 2, 0.0, 0.0, 0.0, 0.0, "active", emptyList())

        /** What the view model does when the service reports a finished tour (the RecordingService.finished collector). */
        fun serviceFinished() {
            val id = service.finished ?: return
            service.finished = null
            when (c.finishedOutcome(service.distance.getValue(id))) { // the user's press decides, else the track's length
                TourController.Finished.DELETED -> { service.db[id] = "bin"; c.closeFinished() }
                TourController.Finished.SHORT -> { service.db[id] = "bin"; shortBinned += id; c.closeFinished() }
                TourController.Finished.SAVED -> c.showFinished(tour(id).copy(state = "done"), null)
            }
            followed = null
            c.afterFinished()
            // afterTourEnded → clearPlan: an unsaved plan goes under Zuletzt first (keepAsDraft), then one undoable clear
            val pts = session.points.toList()
            planBeforeEnd = pts
            if (com.onpath.app.ui.Drafts.keeps(pts.size, pts != savedPoints)) drafts += pts
            if (pts.isNotEmpty()) session.edit { clear() }
        }

        fun step(op: Op) {
            now += rnd.nextLong(50, 3_000)
            when (op) {
                Op.START -> if (c.unfinished == null && service.running == null) {
                    c.start("HIKE", 2); followed = session.points.toList().ifEmpty { null }
                    // "Los geht's" saves a plan not saved as it is (PlanAutoSave)
                    if (session.points.size >= 2 && session.points.toList() != savedPoints) {
                        savedPoints = session.points.toList(); saved += session.points.toList()
                    }
                }
                Op.PAUSE -> { val before = session.points.toList(); c.pause(true); assertEquals("pause changed the plan", before, session.points.toList()) }
                Op.RESUME -> { val before = session.points.toList(); c.pause(false); assertEquals("resume changed the plan", before, session.points.toList()) }
                Op.EDIT -> {
                    session.edit { add(GeoPoint(51.0 + rnd.nextDouble() / 10, 6.9 + rnd.nextDouble() / 10)) }
                    if (service.running != null) followed = session.points.toList()
                }
                Op.UNDO -> if (session.undo() != null && service.running != null) followed = session.points.toList().ifEmpty { null }
                Op.REDO -> if (session.redo() != null && service.running != null) followed = session.points.toList().ifEmpty { null }
                Op.ASK_END -> if (service.running != null && c.endAsk == null) { c.endAsk = EndAsk.Plain; shownAt = now; now += rnd.nextLong(0, 900) }
                Op.PRESS -> if (c.endAsk != null) {
                    val pressAt = shownAt + rnd.nextLong(0, 1_500)
                    val press = Press.entries[rnd.nextInt(Press.entries.size)]
                    if (!EndSheetGuard.accepts(shownAt, pressAt)) { log += "press $press ignored"; return }
                    if (pressAt - shownAt < EndSheetGuard.GUARD_MS) earlyActions++
                    when (press) {
                        Press.SAVE -> { c.end(delete = false); service.stop(); serviceFinished() }
                        Press.DELETE -> { service.running?.let { explicitDeletes += it }; c.end(delete = true); service.stop(); serviceFinished() }
                        Press.KEEP -> c.dismissEndAsk()
                    }
                }
                Op.ARRIVE -> if (service.running != null) c.onArrived(true)
                Op.ARRIVAL_KEEP -> c.arrivalAsk = false
                Op.LEAVE_ARRIVAL -> c.onArrived(false)
                Op.RESTART -> {
                    // the app process dies; the service is sticky (keeps recording) or was killed with it
                    if (rnd.nextBoolean()) { service.running = null; service.paused = false }
                    c = TourController(service)
                    val active = service.db.entries.firstOrNull { it.value == "active" }?.key
                    if (active != null && service.running == null) c.showUnfinished(tour(active), emptyList())
                    if (service.running == null) followed = null
                }
                Op.UNFINISHED -> c.unfinished?.let { t ->
                    when (rnd.nextInt(3)) {
                        0 -> { c.continueTour(t); followed = session.points.toList().ifEmpty { null } }
                        1 -> { c.closeUnfinished(); service.db[t.id] = "done" }
                        else -> { c.closeUnfinished(); service.db[t.id] = "bin"; explicitDeletes += t.id }
                    }
                }
                Op.WALK -> service.walk(rnd.nextDouble(0.0, 60.0))
                Op.UNDO_SHORT -> shortBinned.firstOrNull()?.let { id -> // the notice's Rückgängig: back as a normal tour
                    shortBinned -= id; undone += id; service.db[id] = "done"
                    c.showFinished(tour(id).copy(state = "done"), null)
                }
                Op.NEW_PLAN -> if (service.running != null) {
                    c.noteNewPlan(undo = session.points.isNotEmpty())
                    val route = listOf(GeoPoint(51.2, 7.0), GeoPoint(51.3, 7.1)) // a saved route from My routes
                    session.points.toList().let { old -> if (com.onpath.app.ui.Drafts.keeps(old.size, old != savedPoints)) drafts += old }
                    session.replace(route); followed = session.points.toList()
                    savedPoints = route; if (route !in saved) saved += route
                }
            }
        }

        fun check(where: String) {
            // 1 – never lost without an explicit delete
            for ((id, state) in service.db) {
                if (state == "bin") assertTrue("$where: tour $id in the bin without a delete", id in explicitDeletes || id in shortBinned)
                if (id in shortBinned) assertTrue("$where: tour $id (${service.distance[id]} m) binned as short",
                    ShortRecording.bins(service.distance.getValue(id)))
                if (id in undone && id !in explicitDeletes) assertEquals("$where: tour $id brought back by Rückgängig is a tour", "done", state)
                if (state == "done" && id !in undone && id !in explicitDeletes && service.running != id && c.finishedTour?.id == id)
                    assertFalse("$where: tour $id (${service.distance[id]} m) saved though short", ShortRecording.bins(service.distance.getValue(id)))
                if (state == "active" && service.running != id)
                    assertEquals("$where: tour $id still recording, no service, no question", id, c.unfinished?.id)
            }
            // 2 – after an end nothing of the old tour stays
            if (service.running == null && c.unfinished == null) {
                assertNull("$where: end sheet without a tour", c.endAsk)
                assertFalse("$where: arrival box without a tour", c.arrivalAsk)
                assertNull("$where: followed line without a tour", followed)
            }
            if (service.running == null) assertNull("$where: 'Neuer Plan' line without a tour", c.newPlanNote)
            // 3 – the tour follows the plan shown or nothing
            if (service.running != null && followed != null) assertEquals("$where: tour follows another plan", session.points.toList(), followed)
            // 6 – after an end: the start screen, nothing of the plan lost
            planBeforeEnd?.let { before ->
                planBeforeEnd = null
                assertTrue("$where: the plan left on the map after the end", session.points.isEmpty())
                if (before.size >= 2) assertTrue("$where: the plan from before the end is not in My routes",
                    before in saved || before in drafts)
            }
            // 4 – no early save / delete
            assertEquals("$where: end sheet acted on a press within ${EndSheetGuard.GUARD_MS} ms", 0, earlyActions)
        }
    }

    @Test fun randomTourFlowsKeepTheRules() {
        val t0 = System.nanoTime()
        repeat(300) { seed ->
            val f = Flow(seed)
            val ops = Op.entries
            try {
                repeat(120) { i ->
                    val op = ops[f.rnd.nextInt(ops.size)]
                    f.log += op.name
                    f.step(op)
                    f.check("seed $seed step $i ($op)")
                }
            } catch (e: AssertionError) {
                throw AssertionError("${e.message}\nseed $seed steps: ${f.log.takeLast(25)}", e)
            }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("the property test must stay fast (< 2 s), took $ms ms", ms < 2_000)
    }

    /** The flows really reach the interesting places (else the rules above prove little). */
    @Test fun theFlowsReachEndsDeletesRestartsAndTheInterruptedQuestion() {
        var saves = 0; var deletes = 0; var questions = 0; var ignored = 0; var shorts = 0; var undos = 0
        repeat(300) { seed ->
            val f = Flow(seed)
            repeat(120) {
                val op = Op.entries[f.rnd.nextInt(Op.entries.size)]
                val bin = f.service.db.values.count { it == "bin" }; val done = f.service.db.values.count { it == "done" }
                val short = f.shortBinned.size; val undone = f.undone.size
                f.step(op)
                if (f.service.db.values.count { it == "bin" } > bin) deletes++
                if (f.service.db.values.count { it == "done" } > done) saves++
                if (f.shortBinned.size > short) shorts++
                if (f.undone.size > undone) undos++
                if (op == Op.RESTART && f.c.unfinished != null) questions++
            }
            ignored += f.log.count { it.endsWith("ignored") }
        }
        assertTrue("saves $saves", saves > 50); assertTrue("deletes $deletes", deletes > 50)
        assertTrue("short recordings binned $shorts", shorts > 20); assertTrue("Rückgängig on them $undos", undos > 10)
        assertTrue("interrupted questions $questions", questions > 20); assertTrue("ignored early presses $ignored", ignored > 20)
    }

    @Test fun endingClearsTheSheetTheArrivalAndTheNewPlanLine() {
        val s = FakeService(); val c = TourController(s)
        c.start("HIKE", 2); c.onArrived(true); c.noteNewPlan(undo = true); c.endAsk = EndAsk.Plain
        assertTrue(c.arrivalAsk); assertNotNull(c.newPlanNote)
        c.end(delete = true); s.stop()
        assertTrue("delete is taken once", c.takeDeleteFinished()); assertFalse("the next tour saves", c.takeDeleteFinished())
        c.afterFinished()
        assertNull(c.endAsk); assertFalse(c.arrivalAsk); assertNull(c.newPlanNote); assertNull(s.running)
    }

    /** Owner 2026-11-07: "Tour beenden" → the tour saved, the plan in My routes, the map empty – Undo brings the plan. */
    @Test fun endingGoesBackToTheStartAndKeepsThePlan() {
        for (delete in listOf(false, true)) for (changedOnTheWay in listOf(false, true)) {
            val f = Flow(0)
            f.session.edit { add(GeoPoint(51.0, 6.9)); add(GeoPoint(51.1, 7.0)) }
            f.step(Op.START)
            f.service.walk(1_200.0) // a real tour, not a short recording
            val atStart = f.session.points.toList()
            assertEquals("Los geht's saved the plan", listOf(atStart), f.saved)
            if (changedOnTheWay) f.session.edit { add(GeoPoint(51.2, 7.1)) }
            val before = f.session.points.toList()
            val id = f.service.running!!
            f.c.arrivalAsk = true // "Du bist angekommen." → Tour beenden / Löschen
            if (delete) f.explicitDeletes += id
            f.c.end(delete = delete); f.service.stop(); f.serviceFinished()
            assertEquals(if (delete) "bin" else "done", f.service.db[id])
            if (!delete) assertEquals("the saved box", id, f.c.finishedTour?.id)
            assertFalse(f.c.arrivalAsk)
            assertTrue("the map is back at the start", f.session.points.isEmpty())
            if (changedOnTheWay) assertEquals("changed on the way → Zuletzt", listOf(before), f.drafts)
            else assertTrue("saved as it was → no draft", f.drafts.isEmpty())
            assertTrue(before in f.saved || before in f.drafts)
            f.session.undo()?.let { f.session.replace(it) } // as AppViewModel.undoStep applies it
            assertEquals("one Undo brings the plan back", before, f.session.points.toList())
        }
    }

    /**
     * Owner 2026-11-12: a recording ended with < 50 m of track is no tour – into the recycle bin at the end (no saved box),
     * the notice's Rückgängig brings it back as a normal tour (the saved box then); the plan goes its way as always.
     */
    @Test fun aShortRecordingGoesToTheBinAndRueckgaengigBringsItBack() {
        for (m in listOf(0.0, 12.0, 49.9)) {
            val f = Flow(1)
            f.session.edit { add(GeoPoint(51.0, 6.9)); add(GeoPoint(51.1, 7.0)) }
            f.step(Op.START)
            f.service.walk(m)
            val id = f.service.running!!
            f.c.endAsk = EndAsk.Plain
            f.c.end(delete = false); f.service.stop(); f.serviceFinished()
            assertEquals("$m m: in the bin", "bin", f.service.db[id])
            assertTrue(id in f.shortBinned); assertNull("no saved box for a short recording", f.c.finishedTour)
            assertTrue("the map is back at the start", f.session.points.isEmpty())
            f.check("short end $m m")
            f.step(Op.UNDO_SHORT)
            assertEquals("Rückgängig: a normal tour", "done", f.service.db[id])
            assertEquals("… with its saved box", id, f.c.finishedTour?.id)
            f.check("after Rückgängig $m m")
        }
        // exactly 50 m is a tour
        val f = Flow(2); f.step(Op.START); f.service.walk(50.0); val id = f.service.running!!
        f.c.end(delete = false); f.service.stop(); f.serviceFinished()
        assertEquals("done", f.service.db[id]); assertEquals(id, f.c.finishedTour?.id)
    }

    @Test fun theArrivalBoxComesOncePerArrival() {
        val c = TourController(FakeService())
        c.onArrived(true); assertTrue(c.arrivalAsk)
        c.arrivalAsk = false; c.onArrived(true); assertFalse("same arrival: no second box", c.arrivalAsk)
        c.onArrived(false); c.onArrived(true); assertTrue("a new arrival asks again", c.arrivalAsk)
    }

    @Test fun backToTheInterruptedQuestionFromItsRideSummary() {
        val c = TourController(FakeService())
        val t = Tour(7, TourKind.RECORDED, "T", 0L, "HIKE", 2, 0.0, 0.0, 0.0, 0.0, "active", emptyList())
        c.showUnfinished(t, emptyList()); c.unfinished = null
        c.unfinishedRides = t to com.onpath.app.rides.RideView.Summary(emptyList(), emptyList(), emptyList(),
            com.onpath.app.rides.RideChoice.entries.first(), emptyMap())
        c.backToUnfinished()
        assertEquals(t, c.unfinished); assertNull(c.unfinishedRides)
    }
}
