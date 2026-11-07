package com.onpath.app.ui.tour

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.domain.plan.PlanSession
import com.onpath.app.domain.tour.EndSheetGuard
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
 *    the user chose Löschen; a tour still marked as recording with no service running is shown as interrupted);
 * 2. ending always leaves no running recording, and no end sheet, arrival box or "Neuer Plan" line of the old tour;
 * 3. the tour follows the plan shown or nothing (the stand-in follows what the view model would: the plan at the start
 *    and after every edit during the tour; nothing after the end);
 * 4. the end sheet never saves or deletes from a press within [EndSheetGuard.GUARD_MS] of appearing;
 * 5. pause / resume never changes the plan.
 * A failing flow prints its seed and steps, so it can be replayed.
 */
class TourFlowPropertyTest {

    private enum class Op { START, PAUSE, RESUME, EDIT, UNDO, REDO, ASK_END, PRESS, ARRIVE, ARRIVAL_KEEP, LEAVE_ARRIVAL, RESTART, UNFINISHED, NEW_PLAN }
    private enum class Press { SAVE, DELETE, KEEP }

    /** The recording service and the database as the controller sees them. */
    private class FakeService : TourRecorder {
        var nextId = 1L
        var running: Long? = null
        var paused = false
        val db = LinkedHashMap<Long, String>() // id → "active" / "done" / "bin"
        var finished: Long? = null
        override fun start(activity: String, sac: Int) {
            if (running != null) return // the service ignores a second start while recording
            val id = nextId++; db[id] = "active"; running = id; paused = false
        }
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
        var shownAt = 0L
        var now = 0L
        var earlyActions = 0
        val log = ArrayList<String>()

        fun tour(id: Long) = Tour(id, TourKind.RECORDED, "Tour $id", 0L, "HIKE", 2, 0.0, 0.0, 0.0, 0.0, "active", emptyList())

        /** What the view model does when the service reports a finished tour (the RecordingService.finished collector). */
        fun serviceFinished() {
            val id = service.finished ?: return
            service.finished = null
            if (c.takeDeleteFinished()) { service.db[id] = "bin"; c.closeFinished() } // the user's press decides, not the controller
            else c.showFinished(tour(id).copy(state = "done"), null)
            followed = null
            c.afterFinished()
        }

        fun step(op: Op) {
            now += rnd.nextLong(50, 3_000)
            when (op) {
                Op.START -> if (c.unfinished == null && service.running == null) {
                    c.start("HIKE", 2); followed = session.points.toList().ifEmpty { null }
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
                Op.NEW_PLAN -> if (service.running != null) {
                    c.noteNewPlan(undo = session.points.isNotEmpty())
                    session.replace(listOf(GeoPoint(51.2, 7.0), GeoPoint(51.3, 7.1))); followed = session.points.toList()
                }
            }
        }

        fun check(where: String) {
            // 1 – never lost without an explicit delete
            for ((id, state) in service.db) {
                if (state == "bin") assertTrue("$where: tour $id in the bin without a delete", id in explicitDeletes)
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
        var saves = 0; var deletes = 0; var questions = 0; var ignored = 0
        repeat(300) { seed ->
            val f = Flow(seed)
            repeat(120) {
                val op = Op.entries[f.rnd.nextInt(Op.entries.size)]
                val bin = f.service.db.values.count { it == "bin" }; val done = f.service.db.values.count { it == "done" }
                f.step(op)
                if (f.service.db.values.count { it == "bin" } > bin) deletes++
                if (f.service.db.values.count { it == "done" } > done) saves++
                if (op == Op.RESTART && f.c.unfinished != null) questions++
            }
            ignored += f.log.count { it.endsWith("ignored") }
        }
        assertTrue("saves $saves", saves > 50); assertTrue("deletes $deletes", deletes > 50)
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
