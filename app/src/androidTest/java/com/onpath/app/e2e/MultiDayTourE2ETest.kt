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
import com.onpath.app.ui.AppMode
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
 * picked in the plan sheet's day rows and "Tag 2 fahren" makes it the plan; the tour starts, Navigate says "Day 2 of 3",
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

    private fun mode(): AppMode? { var m: AppMode? = null; scenario.onActivity { m = vm(it).mode }; return m }
    private fun recording(): Boolean { var r = false; scenario.onActivity { r = vm(it).recState.value != null }; return r }
    private fun <T> onVm(f: (AppViewModel) -> T): T { var out: T? = null; scenario.onActivity { out = f(vm(it)) }; @Suppress("UNCHECKED_CAST") return out as T }

    private fun routeSettled(maxMs: Long = 60_000) {
        Thread.sleep(300)
        waitUntil("route settled", maxMs) {
            onVm { v -> !v.routing && v.route != null && v.route!!.legs.size >= v.waypoints.size - 1 }
        }
    }

    @Test fun aSyncedThreeDayPlanDayTwoIsRiddenAndSaved() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = TourDb.get(ctx)
        // ~6 km north of Meran in steps: 7 points, nights on point 2 (Übernachtung) – N = 3, day 2 ends at a grey proposal
        val pts = (0..6).map { GeoPoint(MERAN_LAT + it * 0.009, MERAN_LON + (it % 2) * 0.002) }
        val plan = PlanContent(pts, Activity.HIKE, 2, emptySet(), emptyMap(), days = 3, nights = mapOf(2 to NightMark.OVERNIGHT))
        val t = DbTourStore(ctx, db) { com.onpath.app.time.Factors.ONE }.addPlan("E2E Drei Tage", plan)
        assertEquals(3, db.getTour(t.id)?.days?.days)
        try {
            // My routes: the row says "3 Tage · …" (and "Karten fehlen" while its map areas are not on the phone)
            scenario.onActivity { a -> vm(a).routesTab = com.onpath.app.ui.RoutesTab.PLANNED; vm(a).openRoutes() }
            assertNotNull("the multi-day route in My routes", findFresh(By.text("E2E Drei Tage"), 8_000))
            Thread.sleep(1_500)
            shot("0-my-routes")
            scenario.onActivity { a -> vm(a).routesOpen = false }
            scenario.onActivity { a -> vm(a).openTour(db.getTour(t.id)!!) }
            waitUntil("the tour is the plan", 15_000) { onVm { it.waypoints.size == pts.size } }
            routeSettled()
            waitUntil("the overview shows its days", 20_000) { onVm { it.daysUi()?.rows?.size == 3 } }
            // the maps question (first opening, once): shown, then "later" – the test map package has no server behind it
            try { waitUntil("the maps question", 10_000) { onVm { it.tourMapsAsk != null } }; Thread.sleep(800); shot("1-maps-question") }
            catch (_: AssertionError) {} // expected: every map area of the corridor already on the emulator – no question
            scenario.onActivity { a -> vm(a).answerTourMaps(AppViewModel.TourMapsAnswer.LATER); vm(a).planSize = PlanSize.FULL }
            Thread.sleep(1_000)
            shot("1-overview")
            // pick day 2 in the rows (its number), then "Tag 2 fahren"
            val two = findFresh(By.text("2"), 8_000)
            assertNotNull("day row 2 – ${state()}", two)
            two!!.click()
            waitUntil("day 2 picked", 5_000) { onVm { it.multiDay.selected == 2..2 } }
            clickText(Txt.s(Res.string.multiday_ride_day, "2"))
            waitUntil("day 2 is the plan", 15_000) { onVm { it.multiDay.following } }
            routeSettled()
            val dayPts = onVm { it.waypoints.toList() }
            assertTrue("day 2 starts at the night of day 1", dayPts.first().let { p -> kotlin.math.abs(p.lat - pts[2].lat) < 1e-4 })
            shot("2-day-two")
            scenario.onActivity { it.let { a -> vm(a).startNavigation(force = true) } }
            waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE && recording() }
            assertNotNull("Navigate says the day", device.wait(androidx.test.uiautomator.Until.findObject(By.textContains("2")), 5_000))
            assertEquals(Txt.s(Res.string.multiday_day_of, "2", "3"), onVm { it.multiDay.dayLabel() })
            shot("3-navigate")
            val tourId = onVm { it.recState.value!!.tourId }
            scenario.onActivity { vm(it).endTour() }
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
