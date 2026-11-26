package com.onpath.app.ui

// Layout numbers the pure rules (WholeRoute, MessagePlacement) need; the Composables turn them into dp
// (MAP_FAB = MAP_FAB_DP.dp in MapFooter.kt, TOP_BAR_BOTTOM = TOP_BAR_BOTTOM_DP.dp in SheetWithFooter.kt).

/** A round map button (the controls' column), in dp. */
const val MAP_FAB_DP = 56

/**
 * The bottom of Plan's top row below the status bar, in dp: 0 since the Daumen-Karte (owner 2026-11-26) – the search pill
 * and ☰ moved into the card at the bottom; the top element of Plan is the status bar.
 */
const val TOP_BAR_BOTTOM_DP = 0
