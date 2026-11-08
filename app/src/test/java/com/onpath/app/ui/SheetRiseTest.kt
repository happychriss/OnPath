package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-11-08 (father's feedback: nobody found that the plan sheet pulls up): the plan's first route raises the
 * sheet once to its middle step; never on a re-route, never during a tour, never against the user's own size.
 */
class SheetRiseTest {
    /** A plan's life: routes come one after the other; [SheetRise] decides, the caller remembers "risen". */
    private class Plan(var size: PlanSize = PlanSize.MINI, var risen: Boolean = false) {
        var rose = 0
        fun points(n: Int) { if (SheetRise.resets(n)) risen = false }
        fun routeReady(plan: Boolean = true, tour: Boolean = false) {
            if (SheetRise.rises(risen, size, plan, tour)) { size = SheetRise.TO; rose++ }
            risen = true
        }
    }

    @Test fun theFirstRouteRaisesTheSheetToTheMiddle() {
        val p = Plan()
        p.points(2); p.routeReady()
        assertEquals(PlanSize.COMPACT, p.size)
        assertEquals(1, p.rose)
    }

    @Test fun reRoutesOfTheSamePlanNeverRaiseItAgain() {
        val p = Plan()
        p.points(2); p.routeReady()
        p.size = PlanSize.MINI // the user pulled it down again
        p.points(3); p.routeReady() // a point added: a new route
        p.points(3); p.routeReady() // reverse / round trip / activity: again
        assertEquals(PlanSize.MINI, p.size)
        assertEquals(1, p.rose)
    }

    @Test fun aNewPlanRaisesItOnceMore() {
        val p = Plan()
        p.points(2); p.routeReady()
        p.size = PlanSize.MINI
        p.points(0) // ✕ Neu planen (or a saved route replaces the plan)
        p.points(2); p.routeReady()
        assertEquals(PlanSize.COMPACT, p.size)
        assertEquals(2, p.rose)
    }

    @Test fun theUsersOwnSizeWins() {
        for (s in listOf(PlanSize.COLLAPSED, PlanSize.COMPACT, PlanSize.FULL)) {
            val p = Plan(size = s)
            p.points(2); p.routeReady()
            assertEquals("rolled up / already up: stays $s", s, p.size)
            // and the plan counts as risen: a later re-route does not raise it either
            p.size = PlanSize.MINI; p.routeReady()
            assertEquals(PlanSize.MINI, p.size)
        }
    }

    @Test fun notDuringATourNorOutsidePlan() {
        assertFalse(SheetRise.rises(risen = false, size = PlanSize.MINI, plan = true, tour = true))
        assertFalse(SheetRise.rises(risen = false, size = PlanSize.MINI, plan = false, tour = false))
        assertTrue(SheetRise.rises(risen = false, size = PlanSize.MINI, plan = true, tour = false))
    }

    @Test fun aTapOnTheHandleStepsUpOneStep() {
        // Material 3: a tap on the drag handle grows the sheet one step; the top step goes back to the middle
        assertEquals(PlanSize.MINI, PlanSize.COLLAPSED.tapped())
        assertEquals(PlanSize.COMPACT, PlanSize.MINI.tapped())
        assertEquals(PlanSize.FULL, PlanSize.COMPACT.tapped())
        assertEquals(PlanSize.COMPACT, PlanSize.FULL.tapped())
    }
}
