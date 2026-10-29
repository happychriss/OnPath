package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Navigate shows only the route line, no numbered plan points; Plan shows them. */
class PlanLayersTest {
    // canEditMap = Plan mode and no track shown (AppViewModel.canEditMap)
    @Test fun planShowsLineAndMarkers() = assertEquals(true to true, planLayers(canEditMap = true, planFollowed = false))
    @Test fun planDuringTourShowsMarkersOverFollowedLine() =
        assertEquals(false to true, planLayers(canEditMap = true, planFollowed = true))
    @Test fun navigateFollowingPlanShowsNoMarkers() =
        assertEquals(false to false, planLayers(canEditMap = false, planFollowed = true))
    @Test fun navigateWithoutPlanShowsNothing() =
        assertEquals(false to false, planLayers(canEditMap = false, planFollowed = false))
}
