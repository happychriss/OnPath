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
        val sync = vm.substringAfter("private fun routeSyncedPlans").substringBefore("\n    }\n")
        assertTrue(sync.contains("withContext(backgroundRouting)"))
        val fit = vm.substringAfter("TrackToPlan.fit(").let { vm.substringBefore("TrackToPlan.fit(").takeLast(400) }
        assertTrue(fit.contains("withContext(backgroundRouting)"))
        // the user's plan and the tour's position → ahead stay on the interactive thread
        assertEquals(2, Regex("withContext\\(routingDispatcher\\)").findAll(vm).count())
        // field bug 2026-11-07 (tour 207): the activity check's row request has its own thread – six 15 s checks on the
        // plan's thread held plan edits up to ~90 s
        val check = vm.substringAfter("private suspend fun checkRow(").substringBefore("\n    }\n")
        assertTrue(check.contains("withContext(checkRouting)"))
    }

    /** Review sync H3: Save over a saved route checks first whether sync changed it since it was opened. */
    @Test fun overwriteChecksTheStoredRouteFirst() {
        val over = vm.substringAfter("private fun overwriteSaved(").substringBefore("\n    }\n")
        assertTrue(over.indexOf("SaveRule.keepsOtherEdit") in 0 until over.indexOf("db.replacePlanned("))
    }

    // Close plan without a tour takes every plan line along: ClearPlanDuringTourTest (ClearRule)
}
