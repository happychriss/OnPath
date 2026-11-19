package com.onpath.web

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.domain.RoutesTab
import com.onpath.app.domain.SaveAsk
import com.onpath.app.domain.SaveMode
import com.onpath.app.domain.plan.LivePlanRows
import com.onpath.app.domain.plan.RecentRow
import com.onpath.app.domain.routes.SaveFlow
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.ui.routes.RoutesState
import com.onpath.app.ui.routes.RoutesStore
import com.onpath.app.ui.routes.SaveDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine compiles the `:state` files of state/web-ready.txt as they are (build.gradle.kts, 4.) with the Compose
 * stand-in (androidx/compose/runtime/SnapshotState.kt): the REAL [RoutesState] and [SaveDialog] run here, on an
 * in-memory [RoutesStore]. One behaviour each that their `:state` flow tests prove too, with the same expected values:
 *  - RoutesFlowPropertyTest.theNextPlanReplacesTheOneUnderZuletztIntoTheRecycleBin and
 *    .theSavedBoxOpensDoneWithTheTourMarkedAndClosingClearsIt;
 *  - SaveFlowTest.theProposalThatComesLaterReplacesTheDateNameUnlessTheDialogIsGone and
 *    .withoutAProposalTheDateNameAndATypedNameWins (there through SaveSlice, which waits for the proposal in a coroutine;
 *    here the dialog's own calls, which is what the planner takes).
 */
class StateSlicesTest {
    /** RoutesFlowPropertyTest's FakeStore: the tour database in memory, ids grow, newest first. */
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
        override fun deleteForGood(id: Long) { rows.remove(id); removedForGood += id }
        override fun rename(id: Long, name: String) { rows[id]?.let { rows[id] = it.copy(name = name) } }
    }

    private val a = listOf(0.0 to 0.0, 0.01 to 0.01)
    private val b = listOf(0.02 to 0.0, 0.03 to 0.0)
    private val c = listOf(0.04 to 0.0, 0.05 to 0.0)

    @Test fun routesState_theNextPlanReplacesTheOneUnderZuletztIntoTheRecycleBin() {
        val store = FakeStore(); val r = RoutesState(store, clock = { 1_000L })
        val saved = store.insert(TourKind.PLANNED, c, draft = false)
        val first = r.keepDraft(a, unsaved = true) { store.insert(TourKind.PLANNED, a, draft = true) }!!
        assertEquals(first, r.recentId)
        val second = r.keepDraft(b, unsaved = true) { store.insert(TourKind.PLANNED, b, draft = true) }!!
        assertEquals("one draft: the newest", listOf(second), store.listTours().filter { it.draft }.map { it.id })
        assertNotNull("the replaced plan is in the bin, not gone", store.rows[first]!!.deletedMs)
        assertEquals("the slice's clock, not the system's", 1_000L, store.rows[first]!!.deletedMs)
        assertEquals(listOf(first), store.listDeleted().map { it.id })
        assertEquals(second, r.recentId)
        assertEquals("the saved route is untouched", store.rows[saved], store.listTours().first { it.id == saved })
        assertTrue(store.removedForGood.isEmpty())
        assertEquals(RecentRow(live = false, tourId = second, saved = false),
            LivePlanRows.of(null, store.listTours().filter { it.draft }, store.listTours().filter { !it.draft }, r.recentId))
    }

    @Test fun routesState_theSavedBoxOpensDoneWithTheTourMarkedAndClosingClearsIt() {
        val r = RoutesState(FakeStore()); var closed = 0; r.onClosed = { closed++ }
        r.showSaved(7L)
        assertEquals(RoutesTab.DONE, r.tab); assertTrue(r.open); assertEquals(7L, r.justSavedId)
        r.open = false
        assertEquals(1, closed); assertEquals(-1L, r.justSavedId)
    }

    private val dated = "Route 31 Oct, 10:12"
    private val proposalName = "Vellau → Leiteralm"

    @Test fun saveDialog_theProposalThatComesLaterReplacesTheDateNameUnlessTheDialogIsGone() {
        val d = SaveDialog()
        val asked = d.request(SaveMode.NEW_ROUTE, savedName = null, savedId = -1L, dated = dated, taken = emptyList(), then = null)
        assertEquals(SaveAsk.Kind.NEW, d.ask?.kind)
        assertEquals("the date name meanwhile", dated, d.ask?.name)
        d.proposed(asked, proposalName)
        assertEquals(proposalName, d.ask?.name)
        // a proposal arriving after Cancel changes nothing, and does not open the dialog again
        d.cancel()
        val second = d.request(SaveMode.NEW_ROUTE, null, -1L, dated, emptyList(), null); d.cancel()
        d.proposed(second, proposalName)
        assertNull(d.ask)
        // … nor one meant for a dialog that another one replaced
        val third = d.request(SaveMode.NEW_ROUTE, null, -1L, dated, emptyList(), null)
        d.proposed(second, proposalName)
        assertSame(third, d.ask)
    }

    @Test fun saveDialog_withoutAProposalTheDateNameAndATypedNameWins() {
        val d = SaveDialog()
        d.request(SaveMode.NEW_ROUTE, null, -1L, dated, emptyList(), null)
        assertEquals(dated, d.ask?.name)
        val done = d.confirm("  Leiteralm loop ", savedId = -1L)!!
        assertEquals(SaveFlow.Write.New("Leiteralm loop"), done.write)
        assertNull("the dialog is closed", d.ask)
        assertNull("Save on a closed dialog decides nothing", d.confirm("x", -1L))
    }
}
