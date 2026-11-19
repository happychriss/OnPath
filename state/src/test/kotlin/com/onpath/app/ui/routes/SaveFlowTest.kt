package com.onpath.app.ui.routes

import com.onpath.app.data.MultiDay
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.domain.SaveAsk
import com.onpath.app.gpx.PlanGpx
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.testkit.JdbcTours
import com.onpath.app.testkit.ManualExecutor
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import com.onpath.app.ui.NoticeBoard
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.plan.PlanDocument
import com.onpath.app.ui.plan.PlanStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * CORE FEATURE C18 – Save asks for the name (owner 2026-10-31) – as flows of the REAL [SaveSlice]: real [SaveDialog],
 * [RoutesState] / [RecentPlans] / [PlanDocument], the rules of `:core` (SaveFlow, RouteRules, SaveRule, PlanAutoSave), a
 * [NoticeBoard] for what is said, and a [SaveStore] over the real tables ([JdbcTours]). Only the edges are fakes: the place
 * proposal, the multi-day state, the backup / sync / list-refresh calls (recorded in order), the model times.
 * Every test asserts what is STORED (re-read from the database) and the state the user is left in (dialog, saved link,
 * unsaved, the dialog that reports a failure, the line on the map). No step of the save is written again here: break
 * `SaveSlice` and these tests fail.
 *
 * Not here: a multi-day tour's overwrite (`MultiDaySlice.overwriteWhole` is its own code, only the hand-over is asserted),
 * the view model's wiring (SavePathsTest / PlanAutoSaveTest keep the pins on it).
 */
class SaveFlowTest {
    @get:Rule val composeState = OwnComposeSnapshot()
    @get:Rule val problems = ProblemsRule()

    private val db = JdbcTours()
    @After fun close() = db.close()

    private val a = GeoPoint(46.680, 11.140)
    private val b = GeoPoint(46.700, 11.120)
    private val c = GeoPoint(46.710, 11.100)
    private val d = GeoPoint(46.720, 11.090)
    private val dated = "Route 31 Oct, 10:12"
    private val proposalName = "Vellau → Leiteralm"

    private val routesStore = object : RoutesStore {
        override fun listTours() = db.listTours()
        override fun listDeleted() = db.listDeleted()
        override fun setDraft(id: Long, draft: Boolean) = db.setDraft(id, draft)
        override fun setDeleted(id: Long, deletedMs: Long?) = db.setDeleted(id, deletedMs)
        override fun loadSegments(id: Long) = db.loadSegments(id)
        override fun deleteForGood(id: Long) = db.deleteTour(id)
        override fun rename(id: Long, name: String) = db.rename(id, name)
    }

    /** The save's store as the app has it over TourDb – here over the same tables, same columns ([JdbcTours], TourDbMirrorTest). */
    private val saveStore = object : SaveStore {
        override fun getTour(id: Long) = db.getTour(id)
        override fun loadSegments(id: Long) = db.loadSegments(id)
        override fun insertPlanned(name: String, row: PlannedRow) =
            db.insertTour(TourKind.PLANNED, name, row.activity, row.sac, row.segments, row.stops, distance = row.distanceM,
                ascent = row.ascentM, descent = row.descentM, duration = row.durationS, pinned = row.pins, difficulty = row.difficulty, days = row.days)
        override fun replacePlanned(id: Long, row: PlannedRow) =
            db.replacePlanned(id, row.activity, row.sac, row.distanceM, row.ascentM, row.descentM, row.durationS, row.segments, row.stops,
                row.pins, difficulty = row.difficulty, days = row.days)
        override fun rename(id: Long, name: String) = db.rename(id, name)
        override fun setFavourite(id: Long, favourite: Boolean) = db.setFavourite(id, favourite)
    }

    /** What survives the process: the plan's preferences. */
    private class MemoryPlanStore : PlanStore {
        override var points: List<GeoPoint> = emptyList()
        override var pinned: Set<GeoPoint> = emptySet()
        override var savedName: String? = null
        override var savedId: Long = -1L
        override var savedPoints: List<GeoPoint>? = null
    }

    private val planStore = MemoryPlanStore()

    private fun route(vararg pts: GeoPoint, km: Double = 5.0, up: Double = 300.0) =
        PlannedRoute(listOf(PlannedLeg(pts.toList(), false)), km * 1000, up, up / 2, km * 720, null)

    /** The app, started (again) on what the preferences hold: the real slices and a host that records what the view model would do. */
    private inner class App(val io: CoroutineDispatcher = Dispatchers.Unconfined) {
        val plan = PlanDocument(planStore)
        val routes = RoutesState(routesStore, clock = { 5_000L })
        val board = NoticeBoard(routesOpen = { false }, settingsOpen = { false }, mapShown = { true })
        val log = ArrayList<String>()
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

        // the edges
        var routeNow: PlannedRoute? = null
        var proposal: String? = proposalName
        var proposalGate: CompletableDeferred<Unit>? = null
        var daysNow: MultiDay? = null
        var wholeKept = false
        var wholeTakesTheSave = false
        val stopNames = HashMap<GeoPoint, String>()
        var refreshes = 0
        var recentProblem: String? = null

        val recentHost = object : RecentPlansHost {
            var created = 1_000L
            override fun insertDraft(row: RecentPlans.DraftRow): Long =
                db.insertTour(TourKind.PLANNED, row.name, row.activity, row.sac, row.segments, row.waypoints, distance = row.distanceM,
                    createdMs = created++, draft = true)
            override fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity) { log += "draft times $id" }
            override suspend fun proposeName(points: List<GeoPoint>, route: PlannedRoute?): String? = proposal
            override fun tourChanged(id: Long) { log += "backup $id" }
            override fun purged(uuid: String) {}
            override fun syncChanged(why: String) { log += "sync: $why" }
            override fun refreshTours() { refresh() }
            override fun problem(text: String) { recentProblem = text }
            override var trimmed = false
        }
        val recent = RecentPlans(routes, routesStore, recentHost, scope, Dispatchers.Unconfined)

        val host = object : SaveHost {
            override val route: PlannedRoute? get() = routeNow
            override val activity: Activity get() = Activity.HIKE
            override val sac: Int get() = 2
            override fun stops(points: List<GeoPoint>) = PlanGpx.stops(points) { stopNames[points[it]] }
            override fun difficulty(route: PlannedRoute?, activity: Activity): String? = route?.let { "MODERATE" }
            override fun days(points: List<GeoPoint>): MultiDay? = daysNow
            override fun keepWhole(): Boolean { if (wholeKept) log += "whole kept"; return wholeKept }
            override fun overwriteWhole(name: String, then: (() -> Unit)?): Boolean {
                if (wholeTakesTheSave) log += "whole overwritten as \"$name\""
                return wholeTakesTheSave
            }
            override suspend fun proposeName(points: List<GeoPoint>, route: PlannedRoute?): String? { proposalGate?.await(); return proposal }
            override fun datedName() = dated
            override fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity) { log += "times $id" }
            override fun forgetTimes(id: Long) { log += "forget times $id" }
            override fun copyTimes(from: Long, to: Long, distanceM: Double) { log += "copy times $from→$to" }
            override fun tourChanged(id: Long) { log += "backup $id" }
            override fun syncChanged(why: String) { log += "sync: $why" }
            override fun refreshTours() { refresh() }
            override fun saved(name: String?, id: Long, points: List<GeoPoint>?) { log += "saved $name"; plan.setSaved(name, id, points) }
            override fun shownRenamed(id: Long, name: String) { log += "shown $id → $name" }
            override fun say(text: String, kind: NoticeKind, place: NoticePlace) = board.say(text, kind, place)
        }

        val save = SaveSlice(plan, routes, recent, saveStore, host, scope, io)

        init { refresh() }
        /** The view model's refreshTours: the list is read again. */
        fun refresh() { refreshes++; routes.show(routes.load()) }

        fun draw(vararg pts: GeoPoint) { plan.edit { clear(); addAll(pts) } }

        /** My routes → a planned route: its points become the plan, linked to it. */
        fun open(t: Tour) {
            val pts = t.points()
            plan.replace(pts); plan.setSaved(t.name, t.id, pts)
        }

        fun saveAs(name: String) { save.request(); save.confirm(name) }
    }

    private fun planned() = db.listTours().filter { it.kind == TourKind.PLANNED && !it.draft }.sortedBy { it.id }
    private fun drafts() = db.listTours().filter { it.draft }
    private fun Tour.points() = waypoints.map { GeoPoint(it.lat, it.lon) }
    private fun lineOf(id: Long) = db.loadSegments(id).single().map { GeoPoint(it.lat, it.lon) }

    // ---------------------------------------------------------------- a new plan

    @Test fun aNewPlanIsSavedUnderTheProposalAfterTheDialogsSave() {
        val app = App()
        app.draw(a, b); app.routeNow = route(a, b, km = 5.0, up = 300.0); app.plan.pin(b)
        app.stopNames[a] = "Vellau"; app.stopNames[b] = "Leiteralm"
        assertTrue("a plan never saved is unsaved", app.plan.unsaved)
        app.save.request()
        assertEquals(SaveAsk.Kind.NEW, app.save.ask?.kind)
        assertEquals(proposalName, app.save.ask?.name)
        assertTrue("nothing is stored before the dialog's Save", db.listTours().isEmpty())
        app.save.confirm(proposalName)
        val stored = planned().single()
        assertEquals(proposalName, stored.name)
        assertEquals(listOf(a, b), stored.points())
        assertEquals(listOf("Vellau", "Leiteralm"), stored.waypoints.map { it.name })
        assertEquals("the line is stored too", listOf(a, b), lineOf(stored.id))
        assertEquals("HIKE", stored.activity); assertEquals(2, stored.sac)
        assertEquals(5000.0, stored.distanceM, 0.0); assertEquals(300.0, stored.ascentM, 0.0); assertEquals(150.0, stored.descentM, 0.0)
        assertEquals("the pinned point", setOf(1), stored.pinned)
        assertEquals("the difficulty badge's level", "MODERATE", stored.difficulty)
        assertEquals("done", stored.state)
        assertNull("the dialog is closed", app.save.ask)
        assertEquals("the sheet says \"Saved as …\"", proposalName, app.plan.savedName)
        assertEquals(stored.id, app.plan.savedId)
        assertFalse(app.plan.unsaved)
        assertEquals("backup, sync, list – in this order", listOf("times ${stored.id}", "backup ${stored.id}", "sync: route saved",
            "saved $proposalName"), app.log)
        assertEquals("the list shows it", listOf(stored.id), app.routes.tours.map { it.id })
        assertNull(app.save.problem)
    }

    @Test fun withoutALineYetThePointsAreStoredAsTheLine() {
        val app = App()
        app.draw(a, b) // still calculating / failed: no route
        app.saveAs(proposalName)
        val stored = planned().single()
        assertEquals(listOf(a, b), lineOf(stored.id))
        assertEquals(0.0, stored.distanceM, 0.0)
        assertNull("no line, no difficulty", stored.difficulty)
    }

    @Test fun theProposalThatComesLaterReplacesTheDateNameUnlessTheDialogIsGone() {
        val app = App()
        app.draw(a, b); app.proposalGate = CompletableDeferred()
        app.save.request()
        assertEquals("the date name meanwhile", dated, app.save.ask?.name)
        app.proposalGate!!.complete(Unit)
        assertEquals(proposalName, app.save.ask?.name)
        // a proposal arriving after Cancel changes nothing, and does not open the dialog again
        app.save.cancel()
        app.proposalGate = CompletableDeferred()
        app.save.request(); app.save.cancel()
        app.proposalGate!!.complete(Unit)
        assertNull(app.save.ask)
    }

    @Test fun withoutAProposalTheDateNameAndATypedNameWins() {
        val app = App()
        app.proposal = null
        app.draw(a, b); app.save.request()
        assertEquals(dated, app.save.ask?.name)
        app.save.confirm("  Leiteralm loop ")
        assertEquals(listOf("Leiteralm loop"), planned().map { it.name })
    }

    @Test fun anEmptyNameKeepsTheProposal() {
        val app = App()
        app.draw(a, b); app.save.request(); app.save.confirm("   ")
        assertEquals(listOf(proposalName), planned().map { it.name })
    }

    @Test fun aNameThatExistsGetsANumberAndNeverTouchesTheOtherRoute() {
        val first = App()
        first.draw(a, b); first.saveAs(proposalName)
        val firstRoute = planned().single()
        // another plan, elsewhere, the same proposal
        val second = App()
        second.plan.replace(listOf(c, d)); second.plan.setSaved(null, -1L, null)
        second.save.request()
        assertEquals(SaveAsk.Kind.NEW, second.save.ask?.kind)
        assertEquals("$proposalName (2)", second.save.ask?.name)
        second.save.confirm("$proposalName (2)")
        assertEquals(listOf(proposalName, "$proposalName (2)"), planned().map { it.name })
        assertEquals("the first route is untouched", listOf(a, b), planned()[0].points())
        assertEquals(firstRoute.id, planned()[0].id)
        assertEquals(listOf(c, d), planned()[1].points())
    }

    @Test fun cancelSavesNothingAndSaveFirstLoadsNothing() {
        val app = App()
        app.draw(a, b)
        var loaded = false
        app.save.request { loaded = true } // "Save first" before loading another route
        app.save.cancel()
        assertNull(app.save.ask)
        assertTrue(planned().isEmpty())
        assertFalse("Cancel loads nothing", loaded)
        assertTrue("still unsaved, still there", app.plan.unsaved && app.plan.points.size == 2)
        assertTrue("nothing told the backup or the sync", app.log.isEmpty())
        app.save.request { loaded = true }; app.save.confirm(proposalName)
        assertTrue("Save first loads after the save", loaded)
        assertEquals(1, planned().size)
    }

    @Test fun aPlanOfOnePointCannotBeSaved() {
        val app = App()
        app.draw(a)
        app.save.request()
        assertNull(app.save.ask)
        app.save.saveRoute("x")
        assertTrue(db.listTours().isEmpty())
    }

    /** The slice's own guard: the write runs on the io thread (here held back), and nothing else saves meanwhile. */
    @Test fun aSecondTapOnSaveWhileTheWriteRunsMakesNoSecondRoute() {
        val io = ManualExecutor("io")
        val app = App(io.asCoroutineDispatcher())
        app.draw(a, b); app.save.request(); app.save.confirm(proposalName)
        assertEquals("the write waits on the io thread", 1, io.size)
        assertTrue(planned().isEmpty())
        app.save.request() // the second tap
        assertNull("no dialog while a save runs", app.save.ask)
        app.save.saveRoute("again")
        db.insertTour(TourKind.PLANNED, "x", "HIKE", 2); app.refresh()
        app.save.requestCopy(db.listTours().single()) // no dialog either
        assertNull(app.save.ask)
        assertEquals("still one write queued", 1, io.size)
        io.runAll()
        assertEquals("only the first save and the route that was there", listOf("x", proposalName), planned().map { it.name })
        app.save.request() // after the write the guard is released
        assertNotNull(app.save.ask)
    }

    @Test fun aFailedWriteStoresNothingSaysSoAndLeavesThePlanUnsaved() {
        val app = App()
        app.draw(a, b); app.save.request()
        db.failWrites = 1 // a full disk
        app.save.confirm(proposalName)
        assertTrue(planned().isEmpty())
        assertEquals("the dialog that says so", "The plan could not be saved. Your plan is still here – please try again.", app.save.problem)
        assertTrue("reported to the problem log", problems.said("db", Problems.Level.ERROR).any { it.contains("Saving a planned route failed") })
        assertTrue("the plan is still there and still unsaved", app.plan.unsaved && app.plan.points.size == 2)
        assertNull(app.plan.savedName)
        assertTrue("no backup, no sync, no 'saved'", app.log.isEmpty())
        app.save.problem = null // OK
        app.save.request(); app.save.confirm(proposalName) // the user tries again – the guard was released
        assertEquals(1, planned().size)
        assertNull(app.save.problem)
    }

    // ---------------------------------------------------------------- save again: overwrite

    @Test fun savingAgainAfterAnEditOverwritesTheSameRouteAndItsStats() {
        val app = App()
        app.draw(a, b); app.routeNow = route(a, b, km = 5.0, up = 300.0); app.plan.pin(b); app.saveAs(proposalName)
        val id = planned().single().id
        app.log.clear()
        app.plan.edit { add(c) }; app.routeNow = route(a, b, c, km = 9.0, up = 500.0); app.plan.pin(c)
        assertTrue("changed, not saved", app.plan.unsaved)
        app.save.request()
        assertEquals(SaveAsk.Kind.OVERWRITE, app.save.ask?.kind)
        assertEquals("the current name, not a proposal", proposalName, app.save.ask?.name)
        app.save.confirm(proposalName)
        val stored = planned().single()
        assertEquals("still one route, the same one", id, stored.id)
        assertEquals(listOf(a, b, c), stored.points())
        assertEquals("the line was replaced", listOf(a, b, c), lineOf(id))
        assertEquals(9000.0, stored.distanceM, 0.0); assertEquals(500.0, stored.ascentM, 0.0); assertEquals(250.0, stored.descentM, 0.0)
        assertEquals("the pins of the new version", setOf(1, 2), stored.pinned)
        assertEquals("MODERATE", stored.difficulty)
        assertFalse(app.plan.unsaved)
        assertEquals(id, app.plan.savedId)
        assertEquals("the old times go, the new ones are stored, then backup, sync, saved link, shown track",
            listOf("forget times $id", "times $id", "backup $id", "sync: route overwritten", "saved $proposalName", "shown $id → $proposalName"), app.log)
    }

    @Test fun overwritingKeepsTheStoredDaysWhenTheNewVersionDoesNotKnowThemAndReplacesThemWhenItDoes() {
        val app = App()
        app.draw(a, b); app.daysNow = MultiDay(2); app.saveAs(proposalName)
        assertEquals(2, planned().single().days?.days)
        app.plan.edit { add(c) }; app.daysNow = null
        app.saveAs(proposalName)
        assertEquals("a write that does not know the days never wipes them", 2, planned().single().days?.days)
        app.plan.edit { add(d) }; app.daysNow = MultiDay(3)
        app.saveAs(proposalName)
        assertEquals(3, planned().single().days?.days)
    }

    @Test fun overwriteWithAChangedNameRenamesTheSameRouteInTheSameWrite() {
        val app = App()
        app.draw(a, b); app.saveAs(proposalName)
        val id = planned().single().id
        app.plan.edit { add(c) }
        app.saveAs("Leiteralm loop")
        val stored = planned().single()
        assertEquals(id, stored.id)
        assertEquals("one route, renamed", "Leiteralm loop", stored.name)
        assertEquals(listOf(a, b, c), stored.points())
        assertEquals("\"Saved as\" follows", "Leiteralm loop", app.plan.savedName)
        assertEquals("the list follows", listOf("Leiteralm loop"), app.routes.tours.map { it.name })
    }

    @Test fun overwriteWithAnEmptyNameKeepsTheOldOne() {
        val app = App()
        app.draw(a, b); app.saveAs(proposalName)
        app.plan.edit { add(c) }
        app.saveAs("")
        assertEquals(listOf(proposalName), planned().map { it.name })
        assertEquals(listOf(a, b, c), planned().single().points())
    }

    @Test fun overwriteNeverHitsAnotherRoute() {
        val app = App()
        app.draw(a, b); app.saveAs("Erste")
        app.plan.replace(listOf(c, d)); app.plan.setSaved(null, -1L, null) // a different plan, never saved
        app.saveAs("Zweite")
        app.plan.edit { add(a) }
        app.saveAs("Zweite")
        assertEquals(listOf("Erste", "Zweite"), planned().map { it.name })
        assertEquals("the first route keeps its points", listOf(a, b), planned()[0].points())
        assertEquals(listOf(c, d, a), planned()[1].points())
    }

    /** Review sync H3: the route was changed by the browser sync since it was opened here – the other edit stays. */
    @Test fun syncChangedTheStoredRouteSoSaveMakesACopyAndSaysSo() {
        val app = App()
        app.draw(a, b); app.saveAs(proposalName)
        val id = planned().single().id
        // the browser's edit arrives (sync writes the stored route's points)
        db.exec("UPDATE tours SET waypoints=? WHERE id=?", com.onpath.app.data.TourJson.waypointsToJson(PlanGpx.stops(listOf(a, d)) { null }), id)
        app.plan.edit { add(c) }
        app.saveAs(proposalName)
        val all = planned()
        assertEquals(listOf(proposalName, "$proposalName (2)"), all.map { it.name })
        assertEquals("the other side's edit is kept as it is", listOf(a, d), all[0].points())
        assertEquals("my work is the copy", listOf(a, b, c), all[1].points())
        assertEquals("the line of the other route was not touched", listOf(a, b), lineOf(all[0].id))
        assertEquals("the map line says so", "Changed in the browser meanwhile – saved as “$proposalName (2)”, the other version stays.", app.board.mapNote?.text)
        assertEquals("the plan now belongs to the copy", all[1].id, app.plan.savedId)
        assertEquals("$proposalName (2)", app.plan.savedName)
    }

    @Test fun aRouteDeletedMeanwhileKeepsTheWorkAsANewRouteWithThatName() {
        val app = App()
        app.draw(a, b); app.saveAs(proposalName)
        val id = planned().single().id
        app.plan.edit { add(c) }
        app.save.request()
        db.setDeleted(id, 1L) // into the recycle bin before the dialog's Save
        app.save.confirm(proposalName)
        val stored = planned().single()
        assertTrue("a new route, not the binned one", stored.id != id)
        assertEquals(proposalName, stored.name)
        assertEquals(listOf(a, b, c), stored.points())
        assertEquals(stored.id, app.plan.savedId)
    }

    @Test fun aFailedOverwriteChangesNothingStoredSaysSoAndKeepsTheLink() {
        val app = App()
        app.draw(a, b); app.saveAs(proposalName)
        val id = planned().single().id
        app.plan.edit { add(c) }
        app.log.clear()
        app.save.request()
        db.failWrites = 1
        app.save.confirm(proposalName)
        assertEquals("the stored route is as it was", listOf(a, b), planned().single().points())
        assertEquals(listOf(a, b), lineOf(id))
        assertNotNull(app.save.problem)
        assertTrue(problems.said("db", Problems.Level.ERROR).any { it.contains("Overwriting a saved route failed") })
        assertTrue("still changed, still linked", app.plan.unsaved && app.plan.savedId == id)
        assertTrue(app.log.isEmpty())
        app.save.problem = null
        app.saveAs(proposalName) // again
        assertEquals(listOf(a, b, c), planned().single().points())
    }

    @Test fun aFollowedDayIsTheMultiDaySlicesToSave() {
        val app = App()
        app.draw(a, b); app.saveAs(proposalName)
        app.plan.edit { add(c) }
        app.wholeTakesTheSave = true
        app.saveAs("New name")
        assertTrue("handed over with the name", "whole overwritten as \"New name\"" in app.log)
        assertEquals("this slice wrote nothing", listOf(a, b), planned().single().points())
    }

    // ---------------------------------------------------------------- a route from My routes

    @Test fun aRouteLoadedFromMyRoutesIsOverwrittenInPlaceAndCopiedAsNameTwo() {
        val maker = App()
        maker.draw(a, b); maker.routeNow = route(a, b, km = 5.0, up = 300.0); maker.plan.pin(b)
        maker.daysNow = MultiDay(2)
        maker.saveAs(proposalName)
        val original = planned().single()
        val app = App()
        app.open(original)
        assertFalse("just loaded: not unsaved", app.plan.unsaved)
        app.plan.edit { add(c) }; app.routeNow = route(a, b, c, km = 7.0, up = 400.0)
        app.save.request()
        assertEquals(SaveAsk.Kind.OVERWRITE, app.save.ask?.kind)
        assertEquals(proposalName, app.save.ask?.name)
        app.save.confirm("")
        assertEquals(listOf(a, b, c), planned().single().points())
        assertEquals(7000.0, planned().single().distanceM, 0.0)
        assertEquals("the days of the route stay", 2, planned().single().days?.days)
        // My routes → ⋮ → Save as copy: the dialog proposes (2) and copies the STORED route; the plan stays as it is
        app.plan.edit { add(d) } // changed, not saved – the copy is of the stored route
        app.log.clear()
        app.save.requestCopy(planned().single())
        assertEquals(SaveAsk.Kind.COPY, app.save.ask?.kind)
        assertEquals("$proposalName (2)", app.save.ask?.name)
        app.save.confirm(" ") // blank keeps the proposal
        val all = planned()
        assertEquals(listOf(proposalName, "$proposalName (2)"), all.map { it.name })
        val copy = all[1]
        assertEquals("the copy is the stored route", listOf(a, b, c), copy.points())
        assertEquals("with its stored line", listOf(a, b, c), lineOf(copy.id))
        assertEquals("with its numbers", 7000.0, copy.distanceM, 0.0)
        assertEquals(400.0, copy.ascentM, 0.0); assertEquals(200.0, copy.descentM, 0.0)
        assertEquals(setOf(1), copy.pinned)
        assertEquals("MODERATE", copy.difficulty)
        assertEquals("HIKE", copy.activity); assertEquals(2, copy.sac)
        assertNull("a copy carries no days", copy.days)
        assertEquals(listOf("copy times ${all[0].id}→${copy.id}", "backup ${copy.id}", "sync: route copied"), app.log)
        assertEquals("the plan still belongs to the original", original.id, app.plan.savedId)
        assertTrue(app.plan.unsaved)
    }

    @Test fun saveAsCopyIsOnlyForPlannedRoutes() {
        val app = App()
        db.insertTour(TourKind.RECORDED, "Tour", "HIKE", 2); app.refresh()
        app.save.requestCopy(db.listTours().single())
        assertNull(app.save.ask)
    }

    @Test fun aFailedCopyStoresNothingAndSaysSo() {
        val app = App()
        app.draw(a, b); app.saveAs(proposalName); app.refresh()
        app.save.requestCopy(planned().single())
        db.failWrites = 1
        app.save.confirm("")
        assertEquals(1, planned().size)
        assertNotNull(app.save.problem)
        assertTrue(problems.said("db", Problems.Level.ERROR).any { it.contains("Copying a planned route failed") })
    }

    @Test fun aPlanFromZuletztIsSavedAsANewRouteAndTakesItsPlace() {
        val draftId = db.insertTour(TourKind.PLANNED, "Route 30 Oct, 09:00", "HIKE", 2, emptyList(), PlanGpx.stops(listOf(a, b)) { null }, draft = true)
        val app = App()
        app.proposal = null
        app.open(drafts().single())
        app.save.request()
        assertEquals("no saved route behind a draft: Save is a new route", SaveAsk.Kind.NEW, app.save.ask?.kind)
        app.save.confirm("Meine Runde")
        assertEquals(listOf("Meine Runde"), planned().map { it.name })
        assertTrue("the draft is gone: never a second entry", drafts().none { it.id == draftId })
        assertNull("gone for good, not in the recycle bin", db.getTour(draftId))
        assertEquals(planned().single().id, app.routes.recentId)
    }

    @Test fun overwritingTakesAwayADraftWithTheRoutesNewPoints() {
        val app = App()
        app.draw(a, b); app.saveAs(proposalName)
        val draftId = db.insertTour(TourKind.PLANNED, dated, "HIKE", 2, emptyList(), PlanGpx.stops(listOf(a, b, c)) { null }, draft = true)
        app.refresh()
        app.plan.edit { add(c) }
        app.saveAs(proposalName)
        assertEquals(listOf(a, b, c), planned().single().points())
        assertNull("never a draft with a saved route's points", db.getTour(draftId))
    }

    // ---------------------------------------------------------------- "Los geht's"

    @Test fun losGehtsSavesAPlanNeverSavedWithoutAskingAndNotTwice() {
        val app = App()
        app.draw(a, b); app.routeNow = route(a, b)
        app.save.atTourStart()
        assertNull("no dialog", app.save.ask)
        val stored = planned().single()
        assertEquals("the name the dialog would propose", proposalName, stored.name)
        assertEquals(listOf(a, b), stored.points())
        assertEquals(5000.0, stored.distanceM, 0.0)
        assertFalse(app.plan.unsaved)
        assertEquals(stored.id, app.plan.savedId)
        app.save.atTourStart() // saved as it is: nothing more
        assertEquals(1, planned().size)
        assertTrue(drafts().isEmpty())
    }

    @Test fun losGehtsWhileTheFirstSaveStillRunsMakesNoSecondRoute() {
        val io = ManualExecutor("io")
        val app = App(io.asCoroutineDispatcher())
        app.draw(a, b)
        app.save.atTourStart(); app.save.atTourStart()
        io.runAll()
        assertEquals(1, planned().size)
    }

    @Test fun losGehtsOnASavedPlanChangedSinceUpdatesTheOriginalAndKeepsTheVersionBefore() {
        val maker = App()
        maker.draw(a, b); maker.saveAs(proposalName)
        val original = planned().single()
        val app = App()
        app.open(original)
        app.plan.edit { add(c) }
        app.save.atTourStart()
        val stored = planned().single()
        assertEquals("the original route is updated – no \"Name (2)\"", listOf(proposalName), planned().map { it.name })
        assertEquals(original.id, stored.id)
        assertEquals(listOf(a, b, c), stored.points())
        assertEquals(listOf(a, b, c), lineOf(stored.id))
        val kept = drafts().single()
        assertEquals("the version before goes under Zuletzt", proposalName, kept.name)
        assertEquals(listOf(a, b), kept.points())
        assertFalse(app.plan.unsaved)
    }

    @Test fun losGehtsOnASavedPlanAsItIsDoesNothingAndOnNoPlanNothingEither() {
        val maker = App()
        maker.draw(a, b); maker.saveAs(proposalName)
        val before = db.listTours().map { it.id to it.name }
        val app = App()
        app.open(planned().single())
        app.log.clear()
        app.save.atTourStart()
        assertEquals(before, db.listTours().map { it.id to it.name })
        val empty = App()
        empty.plan.replace(listOf(a)); empty.plan.setSaved(null, -1L, null)
        empty.save.atTourStart()
        assertEquals(before, db.listTours().map { it.id to it.name })
        assertTrue(app.log.isEmpty() && empty.log.isEmpty())
    }

    @Test fun losGehtsOnAPlanFromZuletztSavesItUnderItsOwnName() {
        db.insertTour(TourKind.PLANNED, "Zuletzt-Runde", "HIKE", 2, emptyList(), PlanGpx.stops(listOf(a, b)) { null }, draft = true)
        val app = App()
        app.open(drafts().single())
        app.save.atTourStart() // changed or not
        assertEquals(listOf("Zuletzt-Runde"), planned().map { it.name })
        assertTrue(drafts().isEmpty())
    }

    @Test fun losGehtsWithAFailedWriteStoresNothingAndSaysSo() {
        val app = App()
        app.draw(a, b)
        db.failWrites = 1
        app.save.atTourStart()
        assertTrue(planned().isEmpty())
        assertNotNull(app.save.problem)
        assertTrue(app.plan.unsaved)
    }

    // ---------------------------------------------------------------- the plan leaves the map unsaved

    @Test fun aReplacedUnsavedPlanIsKeptUnderZuletztAtOnceAndASavedOneAsItIsIsNot() {
        val app = App()
        app.draw(a, b); app.routeNow = route(a, b); app.plan.pin(a)
        app.save.keepAsDraft("cleared")
        // written before keepAsDraft returned (the plan leaves the map right after): the place name follows
        val kept = drafts().single()
        assertEquals(listOf(a, b), kept.points())
        assertEquals("a place name replaced the date name", proposalName, kept.name)
        assertTrue("it is no saved route", planned().isEmpty())
        // a saved plan, as it is, stays in My routes only
        app.saveAs(proposalName)
        assertFalse(app.plan.unsaved)
        app.save.keepAsDraft("cleared again")
        assertTrue("the draft with the saved route's points went (never a second entry)", drafts().isEmpty())
        assertEquals(1, planned().size)
    }

    @Test fun aSavedPlanChangedSinceKeepsItsVersionUnderZuletztAndLeavesTheOriginal() {
        val maker = App()
        maker.draw(a, b); maker.saveAs(proposalName)
        val app = App()
        app.open(planned().single())
        app.plan.edit { add(c) }
        app.save.keepAsDraft("replaced")
        assertEquals("the original stays as it is", listOf(a, b), planned().single().points())
        assertEquals(listOf(a, b, c), drafts().single().points())
    }

    @Test fun aFollowedDayGoesAsTheWholeTourAndOnePointIsNoPlan() {
        val app = App()
        app.draw(a, b); app.wholeKept = true
        app.save.keepAsDraft("cleared")
        assertTrue(db.listTours().isEmpty())
        assertTrue("whole kept" in app.log)
        val one = App()
        one.draw(a)
        one.save.keepAsDraft("cleared")
        assertTrue(db.listTours().isEmpty())
    }

    // ---------------------------------------------------------------- the app is closed and started again

    @Test fun thePlanAndItsSavedLinkSurviveTheProcess() {
        val app = App()
        app.draw(a, b); app.saveAs(proposalName)
        app.plan.edit { add(c) }
        val id = planned().single().id
        val restarted = App() // process death / app update: a new plan document and slices on the same preferences and database
        assertEquals(listOf(a, b, c), restarted.plan.points.toList())
        assertEquals(proposalName, restarted.plan.savedName)
        assertEquals(id, restarted.plan.savedId)
        assertTrue("still changed, not saved", restarted.plan.unsaved)
        restarted.save.request()
        assertEquals("Save after the restart overwrites the same route", SaveAsk.Kind.OVERWRITE, restarted.save.ask?.kind)
        restarted.save.confirm(proposalName)
        assertEquals(listOf(a, b, c), planned().single().points())
        assertEquals(1, planned().size)
    }

    // ---------------------------------------------------------------- Rename and the star in My routes

    @Test fun renameIsStoredTheBackupAndSyncAreToldAndThePlanAndTheShownTrackFollow() {
        val app = App()
        app.draw(a, b); app.saveAs(proposalName)
        val t = planned().single()
        app.log.clear()
        app.save.rename(t, "  Leiteralm loop ")
        assertEquals("Leiteralm loop", planned().single().name)
        assertEquals(listOf("backup ${t.id}", "sync: renamed", "saved Leiteralm loop", "shown ${t.id} → Leiteralm loop"), app.log)
        assertEquals("the plan's \"Saved as …\" follows", "Leiteralm loop", app.plan.savedName)
        assertEquals(listOf("Leiteralm loop"), app.routes.tours.map { it.name })
        // blank / the same name: nothing
        app.log.clear()
        app.save.rename(planned().single(), "   "); app.save.rename(planned().single(), "Leiteralm loop")
        assertTrue(app.log.isEmpty())
    }

    @Test fun renamingAnotherRouteLeavesThePlansLinkAlone() {
        val app = App()
        app.draw(a, b); app.saveAs("Erste")
        db.insertTour(TourKind.PLANNED, "Andere", "HIKE", 2, emptyList(), PlanGpx.stops(listOf(c, d)) { null }); app.refresh()
        val other = planned().last()
        app.save.rename(other, "Zweite")
        assertEquals("Erste", app.plan.savedName)
        assertEquals(listOf("Erste", "Zweite"), planned().map { it.name })
    }

    @Test fun aFailedRenameSaysSoAndChangesNothing() {
        val app = App()
        app.draw(a, b); app.saveAs(proposalName); val t = planned().single(); app.log.clear()
        db.failWrites = 1
        app.save.rename(t, "Other")
        assertEquals(proposalName, planned().single().name)
        assertEquals("The plan could not be renamed. Please try again.", app.save.problem)
        assertTrue(problems.said("db", Problems.Level.ERROR).any { it.contains("Renaming a route failed") })
        assertTrue("no backup, no sync", app.log.isEmpty())
    }

    @Test fun theStarIsStoredTheBackupAndSyncAreTold() {
        val app = App()
        app.draw(a, b); app.saveAs(proposalName); val t = planned().single(); app.log.clear()
        app.save.setFavourite(t, true)
        assertTrue(planned().single().favourite)
        assertTrue("the list shows it", app.routes.tours.single().favourite)
        assertEquals(listOf("backup ${t.id}", "sync: starred"), app.log)
        app.log.clear()
        app.save.setFavourite(planned().single(), true) // already starred: nothing
        assertTrue(app.log.isEmpty())
        app.save.setFavourite(planned().single(), false)
        assertFalse(planned().single().favourite)
    }

    @Test fun aFailedStarPutsTheRowBackAndSaysSo() {
        val app = App()
        app.draw(a, b); app.saveAs(proposalName); val t = planned().single(); app.log.clear()
        db.failWrites = 1
        app.save.setFavourite(t, true)
        assertFalse("nothing stored", planned().single().favourite)
        assertFalse("the row is back as it was", app.routes.tours.single().favourite)
        assertEquals("The star could not be saved. Please try again.", app.save.problem)
        assertTrue("no backup, no sync", app.log.none { it.startsWith("backup") || it.startsWith("sync") })
    }
}
