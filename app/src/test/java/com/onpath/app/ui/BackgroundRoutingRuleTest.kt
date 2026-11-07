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
        // the user's plan, the activity check and the tour's position → ahead stay on the interactive thread
        assertEquals(3, Regex("withContext\\(routingDispatcher\\)").findAll(vm).count())
    }

    /** Review sync H3: Save over a saved route checks first whether sync changed it since it was opened. */
    @Test fun overwriteChecksTheStoredRouteFirst() {
        val over = vm.substringAfter("private fun overwriteSaved(").substringBefore("\n    }\n")
        assertTrue(over.indexOf("SaveRule.keepsOtherEdit") in 0 until over.indexOf("db.replacePlanned("))
    }

    // Close plan without a tour takes every plan line along: ClearPlanDuringTourTest (ClearRule)
}
