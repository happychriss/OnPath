package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review routing H1: browser plans after a pairing and "Plan a route from it" routed on the user's own routing thread –
 * a plan edit waited behind all of them. They run on [AppViewModel]'s own background thread.
 */
class BackgroundRoutingRuleTest {
    private val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()

    @Test fun backgroundWorkHasItsOwnThread() {
        // browser plans after a pairing: routeSyncedPlans moved to SyncedRoutingSlice (piece 15); the flow
        // `SyncedRoutingFlowTest.aPlanFromTheBrowserIsRoutedOnTheBackgroundThreadAndStoredWithItsRealLine` runs it on the held background
        // thread (every router call is seen on "bg"), `SyncWiringTest.theBrowserPlansAreRoutedOnTheBackgroundRoutingThreadAndNeverTheUsersOwn` pins the slice
        // "Plan a route from it" / a track made a plan: planFromLine moved to RoutesSlice (piece 13); the flow
        // `RoutesFlowTest.aDoneTourWithNoPlanBecomesAPlanThroughTheRouterOnTheBackgroundThread` runs it on the held background thread
        val slice = com.onpath.app.testkit.sourceFile("ui/routes/RoutesSlice.kt").readText()
        assertTrue("anchor", slice.contains("TrackToPlan.fit("))
        assertTrue(slice.substringBefore("TrackToPlan.fit(").takeLast(400).contains("withContext(planRoute.threads.background)"))
        // the user's plan and the tour's position → ahead stay on the interactive thread
        assertEquals(2, Regex("withContext\\(routingDispatcher\\)").findAll(vm).count())
        // field bug 2026-11-07 (tour 207): the activity check's row request has its own thread – six 15 s checks on the
        // plan's thread held plan edits up to ~90 s
        // (moved with the check to ActivityCheckSlice, piece 16b; the flow `ActivityCheckFlowTest.aTapRoutesThatRowAloneFromThePositionOnTheCheckThread`
        // sees the engine called only when the held check thread runs; this pin is the field bug's)
        val checkSlice = com.onpath.app.testkit.sourceFile("ui/tour/ActivityCheckSlice.kt").readText()
        val check = checkSlice.substringAfter("private suspend fun checkRow(").substringBefore("\n    }\n")
        assertTrue(check.contains("withContext(checkRouting)"))
        assertTrue(checkSlice.contains("private val checkRouting: CoroutineDispatcher get() = planRoute.threads.check"))
    }

    /**
     * Review sync H3: Save over a saved route checks first whether sync changed it since it was opened. (The behaviour is
     * `SaveFlowTest.syncChangedTheStoredRouteSoSaveMakesACopyAndSaysSo` on the real slice; this pin is the review's, it
     * moved with the code to SaveSlice.)
     */
    @Test fun overwriteChecksTheStoredRouteFirst() {
        val slice = com.onpath.app.testkit.sourceFile("ui/routes/SaveSlice.kt").readText()
        val over = slice.substringAfter("private fun overwriteSaved(").substringBefore("\n    }\n")
        assertTrue(over.indexOf("SaveRule.keepsOtherEdit") in 0 until over.indexOf("store.replacePlanned("))
    }

    // Close plan without a tour takes every plan line along: ClearPlanDuringTourTest (ClearRule)
}
