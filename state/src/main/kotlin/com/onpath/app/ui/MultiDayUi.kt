package com.onpath.app.ui

// The multi-day screen model of the plan sheet (rebuild phase 2 piece 20). Since 2026-11-28 (MD-1) the plan sheet shows no
// whole-trip overview any more – only the followed day's head.

/**
 * A followed day's head line in the plan sheet (MD-2 / MD-4): "Tag 4 von 6", "‹ Alle Tage" back to the trip's day sheet in
 * My routes ([canLeave]: no tour running), and [leavesTrip]: the day's start or goal moved – the line „Start und Ziel geändert –
 * wird als eigene Tour gespeichert“ before saving.
 */
data class DayHeadUi(val label: String, val canLeave: Boolean, val leavesTrip: Boolean = false)
