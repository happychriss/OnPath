package com.onpath.app.plansync

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Review routing M5: a browser route is stored only as a real route – never as straight legs with ascent 0. */
class SyncedRoutingTest {
    private val a = GeoPoint(46.0, 11.0); private val b = GeoPoint(46.1, 11.1)
    private fun route(straight: Boolean, error: String? = null) =
        PlannedRoute(listOf(PlannedLeg(listOf(a, b), straight)), 12_000.0, 0.0, 0.0, 3_000.0, error)

    @Test fun onlyARealRouteIsStored() {
        assertTrue(SyncedRouting.storable(route(straight = false)))
        assertFalse("no routing data there: straight legs", SyncedRouting.storable(route(straight = true, error = "No detailed map here yet.")))
        assertFalse(SyncedRouting.storable(route(straight = true)))
        assertFalse(SyncedRouting.storable(route(straight = false, error = "x")))
    }

    @Test fun theAppRoutesThemAgainWhenTheDataArrives() {
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        assertTrue(vm.contains("SyncedRouting.storable(r)"))
        assertTrue(vm.substringAfter("private fun routingDataArrived").substringBefore("\n    }\n").contains("routeSyncedPlans(later)"))
    }
}
