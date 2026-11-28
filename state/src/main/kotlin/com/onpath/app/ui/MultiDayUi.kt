package com.onpath.app.ui

// The multi-day screen model of the plan sheet (rebuild phase 2 piece 20). Since 2026-11-28 (MD-1) the plan sheet shows no
// whole-trip overview any more – only the followed day's head.

/**
 * A followed day's head line in the plan sheet (MD-2 / MD-4 / MD-12): „Tag 4 von 6“ (plain information – no „‹ Alle Tage“ on the
 * phone, owner 2026-11-28 10:20), and [leavesTrip]: the day leaves its trip (start / goal, activity or T level changed) – the
 * line saying why before saving.
 */
data class DayHeadUi(val label: String, val leavesTrip: Boolean = false,
                     /** The line that says why it leaves the trip (MD-4 start / goal, MD-12 activity / T level); null = MD-4's. */
                     val leaveNote: String? = null)
