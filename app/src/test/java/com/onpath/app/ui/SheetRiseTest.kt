package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-11-08 (father's feedback: nobody found that the plan sheet pulls up): the plan's first route raises the
 * sheet once – evening, on the Pixel: only so far that "✕ Neu planen · ⇅ Umkehren · ↻ Rundtour" is fully in view (the
 * summary step), not to the profile; never on a re-route, never during a tour, never down from a higher step.
 */
class SheetRiseTest {
    /** A plan's life: routes come one after the other; [SheetRise] decides, the caller remembers "risen". */
    private class Plan(var size: PlanSize = PlanSize.COLLAPSED, var risen: Boolean = false) {
        var rose = 0
        fun points(n: Int) { if (SheetRise.resets(n)) risen = false }
        fun routeReady(plan: Boolean = true, tour: Boolean = false) {
            if (SheetRise.rises(risen, size, plan, tour)) { size = SheetRise.TO; rose++ }
            risen = true
        }
    }

    @Test fun theFirstRouteRaisesTheSheetUntilThePlanRowIsInView() {
        val p = Plan()
        p.points(2); p.routeReady()
        assertEquals("the summary step: the plan row on top", PlanSize.MINI, p.size)
        assertEquals(1, p.rose)
        // not the profile step any more (owner 2026-11-08 evening: "zu weit")
        assertEquals(PlanSize.MINI, SheetRise.TO)
    }

    @Test fun aSheetAtTheSummaryStepStaysThere() {
        // the usual start: the empty plan's sheet stands at the summary step – it grows with the route's lines by itself
        val p = Plan(size = PlanSize.MINI)
        p.points(2); p.routeReady()
        assertEquals(PlanSize.MINI, p.size)
        assertEquals(0, p.rose)
    }

    @Test fun reRoutesOfTheSamePlanNeverRaiseItAgain() {
        val p = Plan()
        p.points(2); p.routeReady()
        p.size = PlanSize.COLLAPSED // the user rolled it up again
        p.points(3); p.routeReady() // a point added: a new route
        p.points(3); p.routeReady() // reverse / round trip / activity: again
        assertEquals(PlanSize.COLLAPSED, p.size)
        assertEquals(1, p.rose)
    }

    @Test fun aNewPlanRaisesItOnceMore() {
        val p = Plan()
        p.points(2); p.routeReady()
        p.size = PlanSize.COLLAPSED
        p.points(0) // ✕ Neu planen (or a saved route replaces the plan)
        p.points(2); p.routeReady()
        assertEquals(PlanSize.MINI, p.size)
        assertEquals(2, p.rose)
    }

    @Test fun aHigherSheetIsNeverPulledDown() {
        for (s in listOf(PlanSize.MINI, PlanSize.COMPACT, PlanSize.FULL)) {
            val p = Plan(size = s)
            p.points(2); p.routeReady()
            assertEquals("already up: stays $s", s, p.size)
            // and the plan counts as risen: a later re-route does not raise it either
            p.size = PlanSize.COLLAPSED; p.routeReady()
            assertEquals(PlanSize.COLLAPSED, p.size)
        }
    }

    @Test fun notDuringATourNorOutsidePlan() {
        assertFalse(SheetRise.rises(risen = false, size = PlanSize.COLLAPSED, plan = true, tour = true))
        assertFalse(SheetRise.rises(risen = false, size = PlanSize.COLLAPSED, plan = false, tour = false))
        assertTrue(SheetRise.rises(risen = false, size = PlanSize.COLLAPSED, plan = true, tour = false))
    }

    @Test fun aTapOnTheHandleStepsUpOneStep() {
        // Material 3: a tap on the drag handle grows the sheet one step; the top step goes back to the middle
        assertEquals(PlanSize.MINI, PlanSize.COLLAPSED.tapped())
        assertEquals(PlanSize.COMPACT, PlanSize.MINI.tapped())
        assertEquals(PlanSize.FULL, PlanSize.COMPACT.tapped())
        assertEquals(PlanSize.COMPACT, PlanSize.FULL.tapped())
    }
}
