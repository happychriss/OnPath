package com.onpath.app.ui.routes

import com.onpath.app.data.MultiDay
import com.onpath.app.data.TourKind
import com.onpath.app.routing.Activity
import com.onpath.app.routing.NightMark
import com.onpath.app.routing.SharedPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MD-2 (docs/design/multi-day-tours.md §0): a plan link that carries days (the browser planner's multi-day tour) lands as
 * the whole tour in Meine Touren – saved with its days, its points as the line until it is routed, then its day sheet opens –
 * never as one long route loaded into the Plan. A plan link without days still opens as the plan (C15, unchanged).
 */
class SharedTripLinkTest : RoutesFlowBase() {
    private val trip = SharedPlan(listOf(a, b, c), Activity.BIKE_TOURING, sac = 2, pinned = setOf(1),
        names = mapOf(0 to "Hilden", 2 to "München"), title = "Von Hilden nach München", days = 2, nights = mapOf(1 to NightMark()))

    @Test fun aPlanLinkWithDaysIsSavedAsTheWholeTourAndItsDaySheetOpens() {
        val app = App()
        app.go.openSharedPlan(trip, "tapped"); app.settle()
        val saved = db.listTours().single()
        assertEquals(TourKind.PLANNED, saved.kind)
        assertFalse("a saved route, not a Zuletzt entry", saved.draft)
        assertEquals("Von Hilden nach München", saved.name)
        assertEquals(MultiDay(2, nights = mapOf(1 to NightMark())), saved.days)
        assertEquals(listOf(a, b, c).map { it.lat to it.lon }, saved.waypoints.map { it.lat to it.lon })
        assertEquals(listOf("Hilden", null, "München"), saved.waypoints.map { it.name })
        assertEquals(setOf(1), saved.pinned)
        assertEquals("BIKE_TOURING", saved.activity)
        assertEquals("its line is routed afterwards", 1, app.said("route saved plan ${saved.id}").size)
        assertEquals("the day sheet opens", 1, app.said("days of ${saved.id}").size)
        assertTrue("never the whole trip as the plan", app.plan.points.isEmpty())
    }

    @Test fun aPlanLinkWithoutDaysStillOpensAsThePlan() {
        val app = App()
        app.go.openSharedPlan(trip.copy(days = null, nights = emptyMap()), "tapped"); app.settle()
        assertEquals(listOf(a, b, c), app.plan.points.toList())
        assertTrue("no route saved", db.listTours().none { !it.draft })
        assertTrue(app.log.none { it.contains("days of") })
    }
}
