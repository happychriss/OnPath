package com.onpath.app.ui.routes

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.domain.plan.LivePlan
import com.onpath.app.domain.plan.LivePlanRows
import com.onpath.app.domain.plan.RecentRow
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.Drafts
import com.onpath.app.ui.OpenAsk
import com.onpath.app.ui.RoutesTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * My routes as seeded random flows (audit package 4 stage 3, owner 2026-11-07: "keine Fehler, saubere Struktur"):
 * plans are saved, edited, cleared (→ Zuletzt), others loaded, drafts saved or deleted, tours deleted, the overview
 * cancelled, tours started and ended, the app restarted – against [RoutesState] on an in-memory store. After every
 * step: a saved route is never lost without a delete (and a delete goes to the bin); at most [Drafts.MAX] = one draft
 * outside the bin, and it is the Zuletzt row whenever the map is empty (owner 2026-11-12: exactly one plan there – the
 * one worked on last); a draft a newer plan replaced is in the bin, never gone – gone for good only when a saved route
 * took its place; never a draft with the points of a saved route; the plan on the map is the one row; "Abbrechen"
 * changes nothing; opening something during a tour never ends the recording.
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
        override fun listDeleted() = rows.values.filter { it.deletedMs != null }.sortedByDescending { it.deletedMs }
        override fun setDraft(id: Long, draft: Boolean): Boolean {
            val t = rows[id] ?: return false; rows[id] = t.copy(draft = draft); return true
        }
        override fun setDeleted(id: Long, deletedMs: Long?): Boolean {
            val t = rows[id] ?: return false; rows[id] = t.copy(deletedMs = deletedMs); return true
        }
        override fun loadSegments(id: Long): List<List<TrackPoint>> =
            listOf(rows[id]?.waypoints?.map { TrackPoint(it.lat, it.lon, null) } ?: emptyList())
        override fun deleteForGood(id: Long) = removeForGood(id)
        override fun rename(id: Long, name: String) { rows[id]?.let { rows[id] = it.copy(name = name) } }
    }

    private enum class Op { EDIT, SAVE_PLAN, CLEAR, LOAD, SAVE_DRAFT, DELETE, RESTORE, OVERVIEW_CANCEL, TOUR, OPEN_CLOSE, RESTART }

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
        /** Every draft the flow ever wrote: in the store (the bin at worst) unless a saved route took its place. */
        val keptDrafts = mutableSetOf<Long>()
        val replacedBySaved = mutableSetOf<Long>()
        /** The plan that left the map last (≥ 2 points): the Zuletzt row shows it while the map is empty. */
        var lastLeft: List<Pair<Double, Double>>? = null
        var draftsMax = 0
        var overwrites = 0
        var displacedBack = 0

        init { routes.onClosed = { closedCalls++ } }

        val unsaved get() = live != linkedKey
        fun point() = (rnd.nextInt(6) * 0.01) to (rnd.nextInt(6) * 0.01) // few values: the same points come back

        /** The plan leaves the map (clear, load another): the Zuletzt place is its – as a draft, or as the route it is. */
        fun leave() {
            val wasBinned = store.rows[linkedId]?.deletedMs != null
            val id = routes.keepDraft(live, unsaved, linkedId) { store.insert(TourKind.PLANNED, live, draft = true) }
            if (id != null) keptDrafts += id
            if (live.size >= 2) lastLeft = live
            if (!unsaved && wasBinned && store.rows[linkedId]?.deletedMs == null) displacedBack++
        }

        /** The drafts a save with these points / from this draft removes for good (a saved route takes their place). */
        fun replaced(fromDraft: Long, key: List<Pair<Double, Double>>) {
            replacedBySaved += store.listTours().filter { it.draft && (it.id == fromDraft || RoutesState.keyOf(it) == key) }.map { it.id }
        }

        fun step(op: Op) {
            when (op) {
                Op.EDIT -> live = live + point()
                Op.SAVE_PLAN -> if (live.size >= 2) {
                    val fromDraft = if (store.rows[linkedId]?.draft == true) linkedId else -1L
                    replaced(fromDraft, live)
                    val id = store.insert(TourKind.PLANNED, live, draft = false)
                    routes.afterSaved(id, fromDraft, live) // insertPlanned: it takes the place of its draft(s)
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
                    assertEquals("saved from Zuletzt: it stays the Zuletzt row, now a saved route", d.id, routes.recentId)
                }
                Op.DELETE -> store.listTours().randomOrNull(rnd)?.let { t ->
                    assertTrue(routes.toBin(t.id)); binned += t.id; keptSaved -= t.id
                    if (linkedId == t.id) { linkedId = -1; linkedKey = null } // setSaved(null): saved nowhere any more
                    if (lastLeft == RoutesState.keyOf(t)) lastLeft = null
                }
                Op.RESTORE -> store.listDeleted().filter { t ->
                    // a draft whose points a saved route took meanwhile stays out here (the user may restore it, the
                    // rule "never a draft with a saved route's points" is about what the app writes by itself)
                    !t.draft || store.listTours().none { s -> s.kind == TourKind.PLANNED && !s.draft && RoutesState.keyOf(s) == RoutesState.keyOf(t) }
                }.randomOrNull(rnd)?.let { t ->
                    // recycle bin → Restore (review 2026-11-12): a draft becomes the Zuletzt plan, a saved route is back
                    // under Gespeichert as it was
                    val held = store.listTours().filter { it.draft }.map { it.id }
                    val pointer = routes.recentId
                    assertTrue(routes.restore(t.id)); binned -= t.id
                    assertNull("back from the bin", store.rows[t.id]!!.deletedMs)
                    if (t.draft) {
                        assertEquals("a restored draft is the Zuletzt plan", t.id, routes.recentId)
                        held.forEach { assertNotNull("the draft that held the place is in the bin", store.rows[it]!!.deletedMs) }
                        lastLeft = RoutesState.keyOf(t)
                    } else {
                        keptSaved += t.id
                        // a draft with its points goes to the bin; the route takes the place only when that draft held it
                        val twins = held.filter { RoutesState.keyOf(store.rows[it]!!) == RoutesState.keyOf(t) }
                        twins.forEach { assertNotNull("a draft with the restored route's points is in the bin", store.rows[it]!!.deletedMs) }
                        assertEquals("the route takes the place only from its twin", if (pointer in twins) t.id else pointer, routes.recentId)
                        if (pointer in twins) lastLeft = RoutesState.keyOf(t)
                        assertEquals("a restored saved route takes no other draft's place", held - twins.toSet(), store.listTours().filter { it.draft }.map { it.id })
                    }
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
                        replaced(-1L, live)
                        routes.afterSaved(linkedId, -1L, live) // overwriteSaved: no draft keeps the route's new points
                        routes.keepReplaced(old) { store.insert(TourKind.PLANNED, old, draft = true) }?.let { keptDrafts += it }
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
                Op.RESTART -> {
                    val recent = routes.recentId // the prefs keep it (AppViewModel: onRecent → prefs.zuletztId)
                    routes = RoutesState(store, clock = { 1_000L }, recent = recent).also { it.onClosed = { closedCalls++ } }
                }
            }
        }

        fun check(where: String) {
            val list = store.listTours()
            val drafts = list.filter { it.draft }
            val saved = list.filter { it.kind == TourKind.PLANNED && !it.draft }
            draftsMax = maxOf(draftsMax, drafts.size)
            assertTrue("$where: at most ${Drafts.MAX} draft outside the bin (${drafts.size})", drafts.size <= Drafts.MAX)
            val savedKeys = saved.map(RoutesState::keyOf).toSet()
            assertTrue("$where: never a draft with a saved route's points", drafts.none { RoutesState.keyOf(it) in savedKeys })
            keptSaved.forEach { id ->
                val t = list.firstOrNull { it.id == id }
                assertTrue("$where: saved route $id never lost without a delete", t != null && !t.draft)
            }
            binned.forEach { id ->
                assertTrue("$where: a delete goes to the bin, never gone ($id)", store.rows[id]?.deletedMs != null)
            }
            // a replaced draft is in the bin, never gone for good – gone only when a saved route took its place
            keptDrafts.forEach { id ->
                assertTrue("$where: draft $id is in the store (the bin at worst) or a saved route took its place",
                    id in store.rows || id in replacedBySaved)
            }
            assertTrue("$where: removed for good only where a saved route took the place", store.removedForGood.all { it in replacedBySaved })
            // the one Zuletzt row
            val lp = if (live.isEmpty()) null else LivePlan(null, live.size, 0.0, 0.0, 0.0, linkedId, live, unsaved)
            val row = LivePlanRows.of(lp, drafts, saved, routes.recentId)
            if (live.isNotEmpty()) assertTrue("$where: the plan on the map is the row", row.live)
            else {
                assertFalse(row.live)
                // a draft never hides: it is the row (or on the map), the rest is in the bin
                drafts.firstOrNull()?.let { assertEquals("$where: the draft is the Zuletzt row", it.id, row.tourId) }
                // the plan that left the map last is the row (it or the route it is), until deleted
                lastLeft?.let { key ->
                    val shown = list.firstOrNull { it.id == row.tourId }
                    assertNotNull("$where: the plan left last is under Zuletzt", shown)
                    assertEquals("$where: the plan left last is under Zuletzt", key, RoutesState.keyOf(shown!!))
                    assertEquals("$where: a saved route carries the mark, a draft not", !shown.draft, row.saved)
                }
                if (drafts.isEmpty() && saved.none { it.id == routes.recentId }) assertEquals(RecentRow.NONE, row)
            }
        }
    }

    @Test fun myRoutesHoldsItsRulesInRandomFlows() {
        val t0 = System.nanoTime()
        var maxDrafts = 0; var saves = 0; var loadsDuringTour = 0; var overwrites = 0; var displacedBack = 0
        repeat(300) { seed ->
            val m = Model(seed)
            repeat(150) { i ->
                val op = pick(m.rnd)
                if (op == Op.LOAD && m.recording) loadsDuringTour++
                if (op == Op.SAVE_DRAFT && m.store.listTours().any { it.draft }) saves++
                m.step(op)
                m.check("seed $seed step $i $op")
            }
            maxDrafts = maxOf(maxDrafts, m.draftsMax); overwrites += m.overwrites; displacedBack += m.displacedBack
        }
        // plans made and cleared over and over (owner 2026-11-12 "füllt sich schnell"): one draft, the newest, the rest in the bin
        repeat(20) { seed ->
            val m = Model(10_000 + seed)
            repeat(300) { i ->
                val op = if (m.rnd.nextInt(10) < 6) Op.EDIT else Op.CLEAR
                m.step(op); m.check("busy seed $seed step $i $op")
            }
            maxDrafts = maxOf(maxDrafts, m.draftsMax)
            assertTrue("the replaced plans wait in the bin", m.store.rows.values.count { it.draft && it.deletedMs != null } > 10)
        }
        // the flows really reach the cases: the draft limit, saving from Zuletzt, loading during a tour, a draft
        // loaded while another plan takes its place and back when it leaves unchanged
        assertEquals("the draft limit is reached", Drafts.MAX, maxDrafts)
        assertTrue(saves > 100); assertTrue(loadsDuringTour > 100)
        assertTrue("a changed saved route is started and updated in place ($overwrites)", overwrites > 50)
        assertTrue("a displaced draft comes back out of the bin ($displacedBack)", displacedBack > 20)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("fast ($ms ms)", ms < 5_000)
    }

    private fun pick(rnd: Random): Op = when (rnd.nextInt(100)) {
        in 0..34 -> Op.EDIT
        in 35..42 -> Op.SAVE_PLAN
        in 43..60 -> Op.CLEAR
        in 61..70 -> Op.LOAD
        in 71..75 -> Op.SAVE_DRAFT
        in 76..79 -> Op.DELETE
        80 -> Op.RESTORE
        in 81..84 -> Op.OVERVIEW_CANCEL
        in 85..89 -> Op.TOUR
        in 90..96 -> Op.OPEN_CLOSE
        else -> Op.RESTART
    }

    private val a = listOf(0.0 to 0.0, 0.01 to 0.01)
    private val b = listOf(0.02 to 0.0, 0.03 to 0.0)
    private val c = listOf(0.04 to 0.0, 0.05 to 0.0)

    /** Owner 2026-11-12: exactly one plan under Zuletzt – the next unsaved plan replaces it, the replaced one is in the bin. */
    @Test fun theNextPlanReplacesTheOneUnderZuletztIntoTheRecycleBin() {
        val store = FakeStore(); val r = RoutesState(store)
        val saved = store.insert(TourKind.PLANNED, c, draft = false)
        val first = r.keepDraft(a, unsaved = true) { store.insert(TourKind.PLANNED, a, draft = true) }!!
        assertEquals(first, r.recentId)
        val second = r.keepDraft(b, unsaved = true) { store.insert(TourKind.PLANNED, b, draft = true) }!!
        assertEquals("one draft: the newest", listOf(second), store.listTours().filter { it.draft }.map { it.id })
        assertNotNull("the replaced plan is in the bin, not gone", store.rows[first]!!.deletedMs)
        assertEquals(listOf(first), store.listDeleted().map { it.id })
        assertEquals(second, r.recentId)
        assertEquals("the saved route is untouched", store.rows[saved], store.listTours().first { it.id == saved })
        assertTrue(store.removedForGood.isEmpty())
        assertEquals(RecentRow(live = false, tourId = second, saved = false),
            LivePlanRows.of(null, store.listTours().filter { it.draft }, store.listTours().filter { !it.draft }, r.recentId))
    }

    /** A saved route left as it is takes the Zuletzt place (with the mark); it stays saved, the draft before goes to the bin. */
    @Test fun aSavedRouteLeftAsItIsTakesThePlaceAndStaysSaved() {
        val store = FakeStore(); val r = RoutesState(store)
        val saved = store.insert(TourKind.PLANNED, c, draft = false)
        val draft = r.keepDraft(a, unsaved = true) { store.insert(TourKind.PLANNED, a, draft = true) }!!
        assertNull(r.keepDraft(c, unsaved = false, savedId = saved) { error("no insert") })
        assertEquals(saved, r.recentId)
        assertNotNull("the draft before waits in the bin", store.rows[draft]!!.deletedMs)
        val row = LivePlanRows.of(null, store.listTours().filter { it.draft }, store.listTours().filter { !it.draft }, r.recentId)
        assertEquals(RecentRow(live = false, tourId = saved, saved = true), row)
        assertFalse(store.rows[saved]!!.draft); assertNull(store.rows[saved]!!.deletedMs)
        // the saved route deleted: the draft is still in the bin, nothing under Zuletzt
        assertTrue(r.toBin(saved))
        assertEquals(RecentRow.NONE, LivePlanRows.of(null, emptyList(), emptyList(), r.recentId))
    }

    /**
     * A plan under Zuletzt is loaded ("Planen") while the plan on the map leaves: the leaving plan takes the place, the
     * loaded draft waits in the bin behind the plan on the map – and comes back when it leaves the map unchanged.
     */
    @Test fun aDraftLoadedAndDisplacedComesBackWhenItLeavesUnchanged() {
        val store = FakeStore(); val r = RoutesState(store)
        val loaded = r.keepDraft(a, unsaved = true) { store.insert(TourKind.PLANNED, a, draft = true) }!!
        val leaving = r.keepDraft(b, unsaved = true) { store.insert(TourKind.PLANNED, b, draft = true) }!!
        assertNotNull(store.rows[loaded]!!.deletedMs)
        // the loaded plan leaves the map as it is
        assertNull(r.keepDraft(a, unsaved = false, savedId = loaded) { error("no insert") })
        assertNull("back out of the bin", store.rows[loaded]!!.deletedMs)
        assertNotNull("the other one waits there now", store.rows[leaving]!!.deletedMs)
        assertEquals(loaded, r.recentId)
        // changed instead: a new draft with the new points, the loaded one stays in the bin
        val changed = r.keepDraft(a + (0.02 to 0.02), unsaved = true, savedId = loaded) { store.insert(TourKind.PLANNED, a + (0.02 to 0.02), draft = true) }!!
        assertEquals(listOf(changed), store.listTours().filter { it.draft }.map { it.id })
        assertNotNull(store.rows[loaded]!!.deletedMs)
        // the draft gone for good meanwhile (recycle bin → Delete now): the plan is kept anew, never lost
        store.removeForGood(changed)
        val anew = r.keepDraft(a + (0.02 to 0.02), unsaved = false, savedId = changed) { store.insert(TourKind.PLANNED, a + (0.02 to 0.02), draft = true) }
        assertNotNull(anew)
    }

    /**
     * Review 2026-11-12: Recycle bin → Restore of a draft. D1 replaced by D2, D1 restored → D1 is the Zuletzt plan, D2 in
     * the bin; a saved route holding the place stays saved and under Gespeichert, the restored draft is the row.
     */
    @Test fun aRestoredDraftIsTheZuletztPlanAndTheOneThereGoesToTheBin() {
        val store = FakeStore(); val r = RoutesState(store)
        val d1 = r.keepDraft(a, unsaved = true) { store.insert(TourKind.PLANNED, a, draft = true) }!!
        val d2 = r.keepDraft(b, unsaved = true) { store.insert(TourKind.PLANNED, b, draft = true) }!!
        assertNotNull(store.rows[d1]!!.deletedMs)
        assertTrue(r.restore(d1))
        assertEquals(d1, r.recentId)
        assertNull(store.rows[d1]!!.deletedMs); assertNotNull("D2 waits in the bin now", store.rows[d2]!!.deletedMs)
        assertEquals(RecentRow(live = false, tourId = d1, saved = false),
            LivePlanRows.of(null, store.listTours().filter { it.draft }, store.listTours().filter { !it.draft }, r.recentId))
        // a saved route holds the place
        val x = store.insert(TourKind.PLANNED, c, draft = false)
        assertNull(r.keepDraft(c, unsaved = false, savedId = x) { error("no insert") })
        assertEquals(x, r.recentId); assertNotNull(store.rows[d1]!!.deletedMs)
        assertTrue(r.restore(d2))
        assertEquals(d2, r.recentId)
        assertEquals("the saved route stays as it was", store.rows[x]!!.copy(), store.listTours().single { !it.draft })
        assertEquals(RecentRow(live = false, tourId = d2, saved = false),
            LivePlanRows.of(null, store.listTours().filter { it.draft }, store.listTours().filter { !it.draft }, r.recentId))
        // a restored saved route takes no place
        assertTrue(r.toBin(x)); assertTrue(r.restore(x))
        assertEquals(d2, r.recentId); assertNull(store.rows[d2]!!.deletedMs)
        // unless the draft holding the place has its points (planned again while the route was in the bin): never twice
        assertTrue(r.toBin(x))
        val twin = r.keepDraft(c, unsaved = true) { store.insert(TourKind.PLANNED, c, draft = true) }!!
        assertTrue(r.restore(x))
        assertNotNull("the twin draft is in the bin", store.rows[twin]!!.deletedMs)
        assertEquals("the saved route is the Zuletzt plan", x, r.recentId)
        assertFalse("gone for good: nothing to restore", r.restore(999L))
    }

    /**
     * Review 2026-11-12: the app died between a draft's insert and its taking the place – the pointer is stale. The
     * newest draft outside the bin is the row anyway, and the next plan that takes the place bins it like any other.
     */
    @Test fun aDraftLeftBehindByAKillIsTheRowAndTheNextTakePlaceBinsIt() {
        val store = FakeStore(); val r = RoutesState(store)
        val x = store.insert(TourKind.PLANNED, c, draft = false)
        val d1 = r.keepDraft(a, unsaved = true) { store.insert(TourKind.PLANNED, a, draft = true) }!!
        val orphan = store.insert(TourKind.PLANNED, b, draft = true) // inserted, the kill came before takePlace
        assertEquals(d1, r.recentId)
        fun row() = LivePlanRows.of(null, store.listTours().filter { it.draft }, store.listTours().filter { !it.draft }, r.recentId)
        assertEquals("the newer draft shows, never hidden", RecentRow(live = false, tourId = orphan, saved = false), row())
        // a saved route holding the place gives way too
        assertNull(r.keepDraft(c, unsaved = false, savedId = x) { error("no insert") })
        assertEquals("taking the place bins the orphan", listOf<Long>(), store.listTours().filter { it.draft }.map { it.id })
        val orphan2 = store.insert(TourKind.PLANNED, b, draft = true)
        assertEquals(RecentRow(live = false, tourId = orphan2, saved = false), row())
        val d3 = r.keepDraft(a + (0.1 to 0.1), unsaved = true) { store.insert(TourKind.PLANNED, a + (0.1 to 0.1), draft = true) }!!
        assertEquals(listOf(d3), store.listTours().filter { it.draft }.map { it.id })
        assertNotNull(store.rows[orphan2]!!.deletedMs)
        assertTrue(store.removedForGood.isEmpty())
    }

    /** The plan's saved route sits in the recycle bin (deleted by the user): nothing is kept twice, the plan is in it. */
    @Test fun aPlanWhoseSavedRouteIsInTheBinMakesNoDraft() {
        val store = FakeStore(); val r = RoutesState(store)
        val x = store.insert(TourKind.PLANNED, c, draft = false)
        val d = r.keepDraft(a, unsaved = true) { store.insert(TourKind.PLANNED, a, draft = true) }!!
        assertTrue(r.toBin(x))
        assertNull(r.keepDraft(c, unsaved = false, savedId = x) { error("no insert") })
        assertEquals("the place stays where it was", d, r.recentId)
        assertNotNull("the route stays in the bin", store.rows[x]!!.deletedMs)
        assertEquals(listOf(d), store.listTours().filter { it.draft }.map { it.id })
    }

    @Test fun savedRoutesAreNeverTouchedByTheOnePlanRule() {
        val store = FakeStore(); val r = RoutesState(store)
        val saved = listOf(a, b, c).map { store.insert(TourKind.PLANNED, it, draft = false) }
        val before = saved.map { store.rows[it] }
        r.keepDraft(a + (0.1 to 0.1), unsaved = true) { store.insert(TourKind.PLANNED, a + (0.1 to 0.1), draft = true) }
        r.keepDraft(b + (0.1 to 0.1), unsaved = true) { store.insert(TourKind.PLANNED, b + (0.1 to 0.1), draft = true) }
        val newSaved = store.insert(TourKind.PLANNED, c + (0.1 to 0.1), draft = false)
        r.afterSaved(newSaved, -1L, c + (0.1 to 0.1))
        assertNull(r.keepDraft(a, unsaved = false, savedId = saved[0]) { error("no insert") })
        assertEquals(before, saved.map { store.rows[it] })
        assertEquals(saved + newSaved, store.listTours().filter { !it.draft }.map { it.id }.sorted())
        assertEquals(0, r.trimDrafts())
        assertEquals(before, saved.map { store.rows[it] })
    }

    /** The one-time clean-up at the first start with the rule: older drafts to the bin, the newest stays; idempotent. */
    @Test fun theStartCleanUpKeepsTheNewestDraftAndIsIdempotent() {
        val store = FakeStore(); val r = RoutesState(store)
        val saved = store.insert(TourKind.PLANNED, c, draft = false)
        val old = store.insert(TourKind.PLANNED, a, draft = true)
        val older = store.insert(TourKind.PLANNED, b, draft = true)
        val newest = store.insert(TourKind.PLANNED, a + (0.1 to 0.1), draft = true)
        assertEquals(2, r.trimDrafts())
        assertEquals(listOf(newest), store.listTours().filter { it.draft }.map { it.id })
        assertNotNull(store.rows[old]!!.deletedMs); assertNotNull(store.rows[older]!!.deletedMs)
        assertNull(store.rows[saved]!!.deletedMs); assertFalse(store.rows[saved]!!.draft)
        assertTrue(store.removedForGood.isEmpty())
        assertEquals("idempotent", 0, r.trimDrafts())
        assertEquals(listOf(newest), store.listTours().filter { it.draft }.map { it.id })
        // no place known yet (older install): the newest draft is the row
        assertEquals(RecentRow(live = false, tourId = newest, saved = false),
            LivePlanRows.of(null, store.listTours().filter { it.draft }, store.listTours().filter { !it.draft }, r.recentId))
    }

    /** Zuletzt → Speichern: the row stays, now a saved route with the mark – and under Gespeichert too. */
    @Test fun savingFromZuletztKeepsTheRowAsTheSavedRoute() {
        val store = FakeStore(); val r = RoutesState(store)
        val id = r.keepDraft(a, unsaved = true) { store.insert(TourKind.PLANNED, a, draft = true) }!!
        assertTrue(r.saveDraft(id)); r.markSaved(id)
        assertEquals(id, r.recentId)
        val saved = store.listTours().filter { !it.draft }
        assertEquals(listOf(id), saved.map { it.id })
        assertEquals(RecentRow(live = false, tourId = id, saved = true), LivePlanRows.of(null, emptyList(), saved, r.recentId))
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
        val saved = store.insert(TourKind.PLANNED, key, draft = false)
        assertNull(r.keepDraft(key, unsaved = true) { store.insert(TourKind.PLANNED, key, draft = true) })
        assertEquals(1, store.listTours().size)
        assertEquals("that route is the Zuletzt row then", saved, r.recentId)
    }

    /** Owner 2026-11-07: "Los geht's" with a changed saved route updates it; the version before is kept under Zuletzt. */
    @Test fun aChangedSavedRouteStartedKeepsItsVersionBeforeUnderZuletzt() {
        val store = FakeStore(); val r = RoutesState(store)
        val old = listOf(0.0 to 0.0, 0.01 to 0.01); val new = old + (0.02 to 0.02)
        val id = store.insert(TourKind.PLANNED, old, draft = false)
        assertNull("before the update the old points are the route's: no draft", r.keepReplaced(old) { error("no insert") })
        store.rows[id] = store.rows.getValue(id).copy(waypoints = new.map { GpxWaypoint(it.first, it.second, null) })
        r.afterSaved(id, -1L, new)
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
}
