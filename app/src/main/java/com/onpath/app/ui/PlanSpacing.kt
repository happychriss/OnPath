package com.onpath.app.ui

import androidx.compose.ui.unit.dp

/**
 * The plan sheet's two gaps (owner 2026-11-27, „die Abstände zwischen Plan, T2 und Neu planen ein bisschen größer“, picture
 * planplatz2 – layout, texts and pills unchanged): under the PLANEN tab before the activity row, and between that row and
 * the plan row („✕ Neu planen“). Read by PlanPanel.
 */
internal object PlanSpacing {
    val UNDER_TAB = 8.dp
    val ABOVE_PILLS = 10.dp
}
