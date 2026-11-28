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
import com.onpath.strings.multiday_all_days
import com.onpath.strings.multiday_day_of
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Multi-day tours, stage 4 (docs/design/multi-day-tours.md §8: "E2E: synced 3-day plan → pick day 2 → start → recording
 * saved", rebuilt to §0 2026-11-28): a 3-day plan arrives as the browser sync stores it (DbTourStore.addPlan, with its days),
 * My routes shows it, its day sheet opens, a tap on „Tag 3“ makes exactly that day the plan (MD-2, never the whole trip; the
 * Plan has no „‹ Alle Tage“, owner 2026-11-28 10:20), Meine Touren → the trip → „Tag 2“ switches to day 2; the
 * tour starts, Navigate says "Tag 2 von 3", the tour ends and is saved – its followed plan is day 2 with the route's uuid and
 * the day (MD-5), the multi-day route keeps its days.
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
        val night = pts[2]
        val plan = PlanContent(pts, Activity.HIKE, 2, emptySet(), emptyMap(), days = 3, nights = mapOf(2 to NightMark.OVERNIGHT))
        val t = DbTourStore(ctx, db) { com.onpath.app.time.Factors.ONE }.addPlan("E2E Drei Tage", plan)
        assertEquals(3, db.getTour(t.id)?.days?.days)
        try {
            // My routes: the row says "3 Tage · …" (and "Karten fehlen" while its map areas are not on the phone)
            scenario.onActivity { a -> vm(a).routes.tab = com.onpath.app.domain.RoutesTab.PLANNED; vm(a).routesGo.openRoutes() }
            assertNotNull("the multi-day route in My routes", findFresh(By.text("E2E Drei Tage"), 8_000))
            pause(1_500, "the rows are drawn")
            shot("0-my-routes")
            // a tap on the card opens its day sheet (MD-2) – here through the same call the card makes
            val uuid = db.getTour(t.id)!!.uuid
            scenario.onActivity { a -> vm(a).dayPick.open(uuid) }
            waitUntil("the day sheet shows its days", 20_000) { onVm { it.dayPick.ui?.rows?.size == 3 } }
            pause(1_000, "the sheet is drawn")
            shot("1-day-sheet")
            // first „Tag 3“: that day is the plan; the phone's Plan shows „Tag 3 von 3“ and no „‹ Alle Tage“ (owner 2026-11-28
            // 10:20) – another day comes from Meine Touren → the trip → the day (MD-2)
            val three = findFresh(By.text(com.onpath.app.routing.DayRowText.day(3)), 8_000)
            assertNotNull("day row 3 – ${state()}", three)
            three!!.click()
            waitUntil("day 3 is the plan", 20_000) { onVm { it.multiDay.flow.following && it.multiDay.flow.day?.part?.first == 3 } }
            routeSettled()
            assertEquals(Txt.s(Res.string.multiday_day_of, "3", "3"), onVm { it.multiDay.dayLabel() })
            assertNull("no „‹ Alle Tage“ in the phone's Plan", device.findObject(By.textContains(Txt.s(Res.string.multiday_all_days))))
            shot("2a-day-three")
            scenario.onActivity { a -> vm(a).routesGo.openRoutes(); vm(a).dayPick.open(uuid) }
            waitUntil("the day sheet again", 20_000) { onVm { it.dayPick.ui?.rows?.size == 3 } }
            pause(1_000, "the sheet is drawn")
            // one tap on „Tag 2“: that day alone becomes the plan
            val two = findFresh(By.text(com.onpath.app.routing.DayRowText.day(2)), 8_000)
            assertNotNull("day row 2 – ${state()}", two)
            two!!.click()
            waitUntil("day 2 is the plan", 20_000) { onVm { it.multiDay.flow.following && it.multiDay.flow.day?.part?.first == 2 } }
            assertTrue("never the whole trip as the plan", onVm { it.plan.points.size } < pts.size)
            routeSettled()
            scenario.onActivity { a -> vm(a).planEdit.planSize = PlanSize.FULL }
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
            assertEquals("the recording is tied to the day (MD-5)", 2, db.getTourPlan(tourId)?.day)
            assertEquals("the multi-day route keeps its days", 3, db.getTour(t.id)?.days?.days)
            db.deleteTour(tourId)
        } finally {
            db.deleteTour(t.id)
        }
    }
}
