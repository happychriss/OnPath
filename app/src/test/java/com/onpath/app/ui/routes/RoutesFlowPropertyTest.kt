package com.onpath.app.ui.routes

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.domain.plan.LivePlan
import com.onpath.app.domain.plan.LivePlanRows
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.Drafts
import com.onpath.app.ui.OpenAsk
import com.onpath.app.ui.RoutesTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * My routes as seeded random flows (audit package 4 stage 3, owner 2026-11-07: "keine Fehler, saubere Struktur"):
 * plans are saved, edited, cleared (→ Zuletzt), others loaded, drafts saved or deleted, tours deleted, the overview
 * cancelled, tours started and ended, the app restarted – against [RoutesState] on an in-memory store. After every
 * step: a saved route is never lost without a delete (and a delete goes to the bin); at most [Drafts.MAX] drafts, never
 * two drafts with the same points, never a draft with the points of a saved route; the plan on the map shows exactly
 * once in My routes; "Abbrechen" changes nothing; opening something during a tour never ends the recording.
 */
class RoutesFlowPropertyTest {

    /** TourDb in memory: ids grow, [created] grows with them (newest first like the real list). */
    private class FakeStore : RoutesStore {
        val rows = linkedMapOf<Long, Tour>()
        val removedForGood = mutableSetOf<Long>()
        private var next = 1L
        fun insert(kind: TourKind, key: List<Pair<Double, Double>>, draft: Boolean, name: String? = null): Long {
            val id = next++
            rows[id] = Tour(id, kind, name ?: "t$id", createdMs = id, activity = "HIKE", sac = 2, distanceM = 100.0 * key.size,
                ascentM = 0.0, descentM = 0.0, durationS = 0.0, state = "done",
                waypoints = key.map { GpxWaypoint(it.first, it.second, null) }, draft = draft)
            return id
        }
        fun removeForGood(id: Long) { rows.remove(id); removedForGood += id }
        override fun listTours() = rows.values.filter { it.deletedMs == null }.sortedByDescending { it.createdMs }
        override fun setDraft(id: Long, draft: Boolean): Boolean {
            val t = rows[id] ?: return false; rows[id] = t.copy(draft = draft); return true
        }
        override fun setDeleted(id: Long, deletedMs: Long?): Boolean {
            val t = rows[id] ?: return false; rows[id] = t.copy(deletedMs = deletedMs); return true
        }
        override fun pruneDrafts(max: Int): Int {
            val old = listTours().filter { it.draft }.drop(max)
            old.forEach { removeForGood(it.id) }
            return old.size
        }
        override fun loadSegments(id: Long): List<List<TrackPoint>> =
            listOf(rows[id]?.waypoints?.map { TrackPoint(it.lat, it.lon, null) } ?: emptyList())
        override fun deleteForGood(id: Long) = removeForGood(id)
        override fun rename(id: Long, name: String) { rows[id]?.let { rows[id] = it.copy(name = name) } }
    }

    private enum class Op { EDIT, SAVE_PLAN, CLEAR, LOAD, SAVE_DRAFT, DELETE, OVERVIEW_CANCEL, TOUR, OPEN_CLOSE, RESTART }

    private class Model(seed: Int) {
        val rnd = Random(seed)
        val store = FakeStore()
        var routes = RoutesState(store, clock = { 1_000L })
        var closedCalls = 0
        var live = listOf<Pair<Double, Double>>()
        var linkedId = -1L
        var linkedKey: List<Pair<Double, Double>>? = null
        var recording = false
        /** Saved routes the flow made and never deleted: they must stay. */
        val keptSaved = mutableSetOf<Long>()
        /** Tours the flow sent to the bin: they must be in the bin, not gone. */
        val binned = mutableSetOf<Long>()
        var draftsMax = 0
        var overwrites = 0

        init { routes.onClosed = { closedCalls++ } }

        val unsaved get() = live != linkedKey
        fun point() = (rnd.nextInt(6) * 0.01) to (rnd.nextInt(6) * 0.01) // few values: the same points come back

        /** The plan leaves the map (clear, load another): unsaved and worth it → under Zuletzt. */
        fun leave() { routes.keepDraft(live, unsaved) { store.insert(TourKind.PLANNED, live, draft = true) } }

        fun step(op: Op) {
            when (op) {
                Op.EDIT -> live = live + point()
                Op.SAVE_PLAN -> if (live.size >= 2) {
                    val fromDraft = if (store.rows[linkedId]?.draft == true) linkedId else -1L
                    val id = store.insert(TourKind.PLANNED, live, draft = false)
                    routes.afterSaved(fromDraft, live) // insertPlanned: it takes the place of its draft(s)
                    keptSaved += id; linkedId = id; linkedKey = live
                }
                Op.CLEAR -> { leave(); live = emptyList(); linkedId = -1; linkedKey = null }
                Op.LOAD -> {
                    val list = store.listTours().filter { it.kind == TourKind.PLANNED }
                    if (list.isNotEmpty()) {
                        val t = list[rnd.nextInt(list.size)]
                        val before = recording
                        val ask = routes.openAsk(t, navigating = recording, points = live.map { GeoPoint(it.first, it.second) }, saved = null)
                        if (recording) assertEquals("during a tour a plan is followed at once", OpenAsk.FOLLOW_PLAN_DURING_TOUR, ask)
                        leave()
                        live = RoutesState.keyOf(t); linkedId = t.id; linkedKey = live
                        assertEquals("opening never ends the recording", before, recording)
                    }
                }
                Op.SAVE_DRAFT -> store.listTours().filter { it.draft }.randomOrNull(rnd)?.let { d ->
                    assertTrue(routes.saveDraft(d.id)); routes.markSaved(d.id); keptSaved += d.id
                    assertEquals(d.id, routes.justSavedId)
                }
                Op.DELETE -> store.listTours().randomOrNull(rnd)?.let { t ->
                    assertTrue(routes.toBin(t.id)); binned += t.id; keptSaved -= t.id
                    if (linkedId == t.id) { linkedId = -1; linkedKey = null } // setSaved(null): saved nowhere any more
                }
                Op.OVERVIEW_CANCEL -> {
                    // a row tap opens the overview; "Abbrechen" calls nothing: store and My routes stay as they were
                    val rows = store.rows.toMap(); val tab = routes.tab; val open = routes.open; val mark = routes.justSavedId
                    assertEquals(rows, store.rows.toMap()); assertEquals(tab, routes.tab)
                    assertEquals(open, routes.open); assertEquals(mark, routes.justSavedId)
                }
                Op.TOUR -> {
                    recording = !recording
                    // "Los geht's" with a loaded saved route changed since (owner 2026-11-07): the route is updated in
                    // place, its version before goes under Zuletzt – no "Name (2)"
                    val saved = store.rows[linkedId]
                    if (recording && saved != null && !saved.draft && saved.deletedMs == null && live.size >= 2 && unsaved) {
                        val old = RoutesState.keyOf(saved)
                        store.rows[linkedId] = saved.copy(waypoints = live.map { GpxWaypoint(it.first, it.second, null) })
                        routes.afterSaved(-1L, live) // overwriteSaved: no draft keeps the route's new points
                        routes.keepReplaced(old) { store.insert(TourKind.PLANNED, old, draft = true) }
                        assertTrue("the version before is under Zuletzt",
                            store.listTours().any { RoutesState.keyOf(it) == old })
                        linkedKey = live; overwrites++
                    }
                }
                Op.OPEN_CLOSE -> if (routes.open) {
                    val calls = closedCalls
                    routes.open = false
                    assertEquals("closing drops the line at the top", calls + 1, closedCalls)
                    assertEquals("closing drops the ✓ mark", -1L, routes.justSavedId)
                } else { routes.show(routes.load()); routes.open = true }
                Op.RESTART -> { routes = RoutesState(store, clock = { 1_000L }).also { it.onClosed = { closedCalls++ } } }
            }
        }

        fun check(where: String) {
            val list = store.listTours()
            val drafts = list.filter { it.draft }
            val saved = list.filter { it.kind == TourKind.PLANNED && !it.draft }
            draftsMax = maxOf(draftsMax, drafts.size)
            assertTrue("$where: at most ${Drafts.MAX} drafts (${drafts.size})", drafts.size <= Drafts.MAX)
            assertEquals("$where: never two drafts with the same points", drafts.size, drafts.map(RoutesState::keyOf).toSet().size)
            val savedKeys = saved.map(RoutesState::keyOf).toSet()
            assertTrue("$where: never a draft with a saved route's points", drafts.none { RoutesState.keyOf(it) in savedKeys })
            keptSaved.forEach { id ->
                val t = list.firstOrNull { it.id == id }
                assertTrue("$where: saved route $id never lost without a delete", t != null && !t.draft)
            }
            binned.forEach { id ->
                assertTrue("$where: a delete goes to the bin, never gone ($id)", store.rows[id]?.deletedMs != null)
            }
            // the plan on the map shows exactly once
            if (live.isNotEmpty()) {
                val lp = LivePlan(null, live.size, 0.0, 0.0, 0.0, linkedId, live, unsaved)
                val rows = LivePlanRows.of(lp, drafts, saved)
                val liveRows = (if (rows.onTop) 1 else 0) + (if (rows.markedSavedId >= 0) 1 else 0)
                assertEquals("$where: the live plan is one row", 1, liveRows)
                val visibleSame = drafts.count { (it.id == linkedId || RoutesState.keyOf(it) == live) && it.id !in rows.hiddenDraftIds }
                assertEquals("$where: no draft shows the live plan a second time", 0, visibleSame)
            } else assertEquals("$where: an empty map has no row", LiveRowsNone, LivePlanRows.of(null, drafts, saved))
        }
    }

    @Test fun myRoutesHoldsItsRulesInRandomFlows() {
        val t0 = System.nanoTime()
        var maxDrafts = 0; var saves = 0; var loadsDuringTour = 0; var overwrites = 0
        repeat(300) { seed ->
            val m = Model(seed)
            repeat(150) { i ->
                val op = pick(m.rnd)
                if (op == Op.LOAD && m.recording) loadsDuringTour++
                if (op == Op.SAVE_DRAFT && m.store.listTours().any { it.draft }) saves++
                m.step(op)
                m.check("seed $seed step $i $op")
            }
            maxDrafts = maxOf(maxDrafts, m.draftsMax); overwrites += m.overwrites
        }
        // plans made and cleared over and over: Zuletzt fills up to its limit and stays there
        repeat(20) { seed ->
            val m = Model(10_000 + seed)
            repeat(300) { i ->
                val op = if (m.rnd.nextInt(10) < 6) Op.EDIT else Op.CLEAR
                m.step(op); m.check("busy seed $seed step $i $op")
            }
            maxDrafts = maxOf(maxDrafts, m.draftsMax)
        }
        // the flows really reach the cases: the draft limit, saving from Zuletzt, loading during a tour
        assertEquals("the draft limit is reached", Drafts.MAX, maxDrafts)
        assertTrue(saves > 100); assertTrue(loadsDuringTour > 100)
        assertTrue("a changed saved route is started and updated in place ($overwrites)", overwrites > 50)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("fast ($ms ms)", ms < 5_000)
    }

    private fun pick(rnd: Random): Op = when (rnd.nextInt(100)) {
        in 0..34 -> Op.EDIT
        in 35..42 -> Op.SAVE_PLAN
        in 43..60 -> Op.CLEAR
        in 61..70 -> Op.LOAD
        in 71..75 -> Op.SAVE_DRAFT
        in 76..80 -> Op.DELETE
        in 81..84 -> Op.OVERVIEW_CANCEL
        in 85..89 -> Op.TOUR
        in 90..96 -> Op.OPEN_CLOSE
        else -> Op.RESTART
    }

    /**
     * E2E 2026-11-07 (CoreFieldE2ETest on GitHub: "E2E saved route" not in My routes): two reads of the list overlap –
     * the tour start's save refreshes, then My routes opens after a new row; the older read finishing last must not
     * hide the newer row.
     */
    @Test fun anOlderReadFinishingLateNeverHidesANewerRow() {
        val store = FakeStore(); val r = RoutesState(store)
        store.insert(TourKind.PLANNED, listOf(0.0 to 0.0, 0.01 to 0.01), draft = false)
        val first = r.beginLoad(); val before = r.load()
        store.insert(TourKind.PLANNED, listOf(0.1 to 0.1, 0.11 to 0.11), draft = false)
        val second = r.beginLoad(); val after = r.load()
        r.show(after, second)
        r.show(before, first) // the older read ends last
        assertEquals("the newer read stays", 2, r.tours.size)
    }

    @Test fun aPlanWithASavedRoutesPointsIsNoDraft() {
        val store = FakeStore(); val r = RoutesState(store)
        val key = listOf(0.0 to 0.0, 0.01 to 0.01)
        store.insert(TourKind.PLANNED, key, draft = false)
        assertNull(r.keepDraft(key, unsaved = true) { store.insert(TourKind.PLANNED, key, draft = true) })
        assertEquals(1, store.listTours().size)
    }

    /** Owner 2026-11-07: "Los geht's" with a changed saved route updates it; the version before is kept under Zuletzt. */
    @Test fun aChangedSavedRouteStartedKeepsItsVersionBeforeUnderZuletzt() {
        val store = FakeStore(); val r = RoutesState(store)
        val old = listOf(0.0 to 0.0, 0.01 to 0.01); val new = old + (0.02 to 0.02)
        val id = store.insert(TourKind.PLANNED, old, draft = false)
        assertNull("before the update the old points are the route's: no draft", r.keepReplaced(old) { error("no insert") })
        store.rows[id] = store.rows.getValue(id).copy(waypoints = new.map { GpxWaypoint(it.first, it.second, null) })
        r.afterSaved(-1L, new)
        assertTrue(r.keepReplaced(old) { store.insert(TourKind.PLANNED, old, draft = true) } != null)
        val list = store.listTours()
        assertEquals("one route, updated in place", listOf(new), list.filter { !it.draft }.map(RoutesState::keyOf))
        assertEquals("its version before under Zuletzt", listOf(old), list.filter { it.draft }.map(RoutesState::keyOf))
    }

    @Test fun theSamePointsTwiceMakeOneDraft() {
        val store = FakeStore(); val r = RoutesState(store)
        val key = listOf(0.0 to 0.0, 0.01 to 0.01)
        assertTrue(r.keepDraft(key, unsaved = true) { store.insert(TourKind.PLANNED, key, draft = true) } != null)
        assertNull(r.keepDraft(key, unsaved = true) { store.insert(TourKind.PLANNED, key, draft = true) })
        assertNull("one point is no plan", r.keepDraft(listOf(0.0 to 0.0), unsaved = true) { error("no insert") })
        assertNull("a saved plan unchanged is no draft", r.keepDraft(listOf(0.02 to 0.0, 0.03 to 0.0), unsaved = false) { error("no insert") })
    }

    /**
     * Tester review 2026-11-07: "✕ Neu planen" / the tour end waited up to 5 s for a place name before the draft was
     * written – a kill in that window lost the plan. Now the row is written at once under the date name; the place name
     * follows ([RoutesState.renameDraft]) – and a lookup that never returns leaves the draft as it is.
     */
    @Test fun theDraftIsWrittenBeforeTheNameAndANameThatNeverComesLosesNothing() {
        val store = FakeStore(); val r = RoutesState(store)
        val key = listOf(0.0 to 0.0, 0.01 to 0.01)
        val dated = "Route 7 Nov 2026, 10:12"
        val id = r.keepDraft(key, unsaved = true) { store.insert(TourKind.PLANNED, key, draft = true, name = dated) }!!
        // the row is there now – before any name lookup started (the map is cleared after this call)
        assertEquals(listOf(dated), store.listTours().filter { it.draft }.map { it.name })
        // the lookup never returns: the wait ends, nothing is renamed, the draft stays
        val proposed = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull<String>(50) { kotlinx.coroutines.awaitCancellation() }
        }
        assertFalse(r.renameDraft(id, dated, proposed))
        assertEquals(dated, store.rows[id]!!.name); assertTrue(store.rows[id]!!.draft)
        // a name that comes: the draft takes it
        assertTrue(r.renameDraft(id, dated, "Algund → Meran"))
        assertEquals("Algund → Meran", store.rows[id]!!.name)
    }

    /** The place name comes late: a draft the user renamed, saved or deleted meanwhile keeps what the user did. */
    @Test fun aLateNameNeverOverwritesWhatTheUserDid() {
        val store = FakeStore(); val r = RoutesState(store)
        val dated = "Route 7 Nov 2026, 10:12"
        val a = r.keepDraft(listOf(0.0 to 0.0, 0.01 to 0.01), unsaved = true) {
            store.insert(TourKind.PLANNED, listOf(0.0 to 0.0, 0.01 to 0.01), draft = true, name = dated) }!!
        store.rename(a, "Mine")
        assertFalse(r.renameDraft(a, dated, "Algund → Meran")); assertEquals("Mine", store.rows[a]!!.name)
        val b = r.keepDraft(listOf(0.02 to 0.0, 0.03 to 0.0), unsaved = true) {
            store.insert(TourKind.PLANNED, listOf(0.02 to 0.0, 0.03 to 0.0), draft = true, name = dated) }!!
        r.saveDraft(b)
        assertFalse("saved meanwhile: the user's route now", r.renameDraft(b, dated, "Algund → Meran"))
        val c = r.keepDraft(listOf(0.04 to 0.0, 0.05 to 0.0), unsaved = true) {
            store.insert(TourKind.PLANNED, listOf(0.04 to 0.0, 0.05 to 0.0), draft = true, name = dated) }!!
        r.toBin(c)
        assertFalse("in the bin: not touched", r.renameDraft(c, dated, "Algund → Meran"))
        assertFalse("blank / same name: nothing to do", r.renameDraft(a, "Mine", " "))
    }

    @Test fun theSavedBoxOpensDoneWithTheTourMarkedAndClosingClearsIt() {
        val r = RoutesState(FakeStore()); var closed = 0; r.onClosed = { closed++ }
        r.showSaved(7L)
        assertEquals(RoutesTab.DONE, r.tab); assertTrue(r.open); assertEquals(7L, r.justSavedId)
        r.open = false
        assertEquals(1, closed); assertEquals(-1L, r.justSavedId)
    }

    @Test fun theLiveRowShowsTheLiveLineAndAnUnreadableTourItsPoints() {
        val store = object : RoutesStore by FakeStore() {
            override fun loadSegments(id: Long): List<List<TrackPoint>> = error("disk")
        }
        val r = RoutesState(store)
        val live = listOf(GeoPoint(1.0, 2.0))
        val liveTour = Tour(LivePlanRows.LIVE_ID, TourKind.PLANNED, "", 0, null, 2, 0.0, 0.0, 0.0, 0.0, "done", emptyList())
        assertEquals(listOf(live), r.line(liveTour) { live })
        val t = Tour(5, TourKind.PLANNED, "", 0, null, 2, 0.0, 0.0, 0.0, 0.0, "done", listOf(GpxWaypoint(3.0, 4.0, null)))
        assertEquals(listOf(listOf(GeoPoint(3.0, 4.0))), r.line(t) { live })
    }

    private companion object { val LiveRowsNone = com.onpath.app.domain.plan.LiveRows.NONE }
}
