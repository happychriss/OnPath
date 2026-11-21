package com.onpath.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import com.onpath.app.data.TourDb
import com.onpath.app.data.TourKind
import com.onpath.app.plansync.DbTourStore
import com.onpath.app.plansync.PlanContent
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.NightMark
import com.onpath.app.domain.AppMode
import com.onpath.app.ui.AppViewModel
import com.onpath.app.ui.PlanSize
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.multiday_day_of
import com.onpath.strings.multiday_ride_day
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Multi-day tours, stage 4 (docs/design/multi-day-tours.md §8: "E2E: synced 3-day plan → pick day 2 → start → recording
 * saved"): a 3-day plan arrives as the browser sync stores it (DbTourStore.addPlan, with its days), it is opened, day 2 is
 * picked in the plan sheet's day rows and "Tag 2 starten" makes it the plan; the tour starts, Navigate says "Day 2 of 3",
 * the tour ends and is saved – its followed plan is day 2, the multi-day route keeps its days.
 */
@RunWith(AndroidJUnit4::class)
class MultiDayTourE2ETest : E2EBase() {
    // German (the screenshots for the owner); the language goes back to the phone's after the test
    @Before fun setUp() = launch(beforeLaunch = {
        com.onpath.app.data.AppLanguage.set(InstrumentationRegistry.getInstrumentation().targetContext, com.onpath.app.data.LanguageChoice.GERMAN)
    })
    @org.junit.After fun language() =
        com.onpath.app.data.AppLanguage.set(InstrumentationRegistry.getInstrumentation().targetContext, com.onpath.app.data.LanguageChoice.SYSTEM)

    private fun mode(): AppMode? { var m: AppMode? = null; scenario.onActivity { m = vm(it).tourSession.mode }; return m }
    private fun recording(): Boolean { var r = false; scenario.onActivity { r = vm(it).recState.value != null }; return r }
    private fun <T> onVm(f: (AppViewModel) -> T): T { var out: T? = null; scenario.onActivity { out = f(vm(it)) }; @Suppress("UNCHECKED_CAST") return out as T }

    private fun routeSettled(maxMs: Long = 60_000) {
        pause(300, "the edit's routing starts")
        waitUntil("route settled", maxMs) {
            onVm { v -> !v.planRun.routing && v.route != null && v.route!!.legs.size >= v.plan.points.size - 1 }
        }
    }

    @Test fun aSyncedThreeDayPlanDayTwoIsRiddenAndSaved() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = TourDb.get(ctx)
        // 7 points, a night on point 2 (Übernachtung) – N = 3, day 2 ends at a grey proposal
        // down the Etsch valley from Meran (paths a hike reaches; a climb north left a point unreachable on the emulator)
        val pts = (0..6).map { GeoPoint(MERAN_LAT - it * 0.008, MERAN_LON + it * 0.004) }
        var night = pts[2]
        val plan = PlanContent(pts, Activity.HIKE, 2, emptySet(), emptyMap(), days = 3, nights = mapOf(2 to NightMark.OVERNIGHT))
        val t = DbTourStore(ctx, db) { com.onpath.app.time.Factors.ONE }.addPlan("E2E Drei Tage", plan)
        assertEquals(3, db.getTour(t.id)?.days?.days)
        try {
            // My routes: the row says "3 Tage · …" (and "Karten fehlen" while its map areas are not on the phone)
            scenario.onActivity { a -> vm(a).routes.tab = com.onpath.app.domain.RoutesTab.PLANNED; vm(a).routesGo.openRoutes() }
            assertNotNull("the multi-day route in My routes", findFresh(By.text("E2E Drei Tage"), 8_000))
            pause(1_500, "the rows are drawn")
            shot("0-my-routes")
            scenario.onActivity { a -> vm(a).routes.open = false }
            scenario.onActivity { a -> vm(a).openTour(db.getTour(t.id)!!) }
            waitUntil("the tour is the plan", 15_000) { onVm { it.plan.points.size == pts.size } }
            routeSettled(); night = onVm { it.plan.points.getOrNull(2) } ?: night // the router may move the points onto their ways
            waitUntil("the overview shows its days", 20_000) { onVm { it.multiDay.daysUi()?.rows?.size == 3 } }
            // the maps question (first opening, once): shown, then "later" – the test map package has no server behind it
            try { waitUntil("the maps question", 10_000) { onVm { it.multiDay.tourMapsAsk != null } }; pause(800, "the question is drawn"); shot("1-maps-question") }
            catch (_: AssertionError) {} // expected: every map area of the corridor already on the emulator – no question
            scenario.onActivity { a -> vm(a).multiDay.answerTourMaps(com.onpath.app.ui.plan.MultiDaySlice.TourMapsAnswer.LATER); vm(a).planEdit.planSize = PlanSize.FULL }
            pause(1_000, "the sheet grows to full")
            shot("1-overview")
            // pick day 2 in the rows (its "Tag 2" line), then "Tag 2 starten"
            val two = findFresh(By.text(com.onpath.app.routing.DayRowText.day(2)), 8_000)
            assertNotNull("day row 2 – ${state()}", two)
            two!!.click()
            waitUntil("day 2 picked", 5_000) { onVm { it.multiDay.flow.selected == 2..2 } }
            clickText(Txt.s(Res.string.multiday_ride_day, "2"))
            waitUntil("day 2 is the plan", 15_000) { onVm { it.multiDay.flow.following } }
            routeSettled()
            val dayPts = onVm { it.plan.points.toList() }
            assertTrue("day 2 starts at the night of day 1: ${dayPts.first()} vs $night", com.onpath.app.data.Geo.distance(dayPts.first(), night) < 50.0)
            shot("2-day-two")
            scenario.onActivity { it.let { a -> vm(a).tourSession.startNavigation(force = true) } }
            waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE && recording() }
            assertNotNull("Navigate says the day", device.wait(androidx.test.uiautomator.Until.findObject(By.textContains("2")), 5_000))
            assertEquals(Txt.s(Res.string.multiday_day_of, "2", "3"), onVm { it.multiDay.dayLabel() })
            shot("3-navigate")
            val tourId = onVm { it.recState.value!!.tourId }
            scenario.onActivity { vm(it).tourEnd.endTour() }
            waitUntil("the tour has ended", 20_000) { !recording() }
            assertEquals("the tour is saved", "done", db.getTour(tourId)?.state)
            assertEquals(TourKind.RECORDED, db.getTour(tourId)?.kind)
            val followed = db.planPoints(tourId)
            assertNotNull("the followed plan is kept", followed)
            assertEquals("it is day 2", dayPts.size, followed!!.size)
            assertEquals("the multi-day route keeps its days", 3, db.getTour(t.id)?.days?.days)
            db.deleteTour(tourId)
        } finally {
            db.deleteTour(t.id)
        }
    }
}
