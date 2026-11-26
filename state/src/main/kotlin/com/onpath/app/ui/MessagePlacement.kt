package com.onpath.app.ui

/**
 * Where a box sits (one rule for every screen): 12 dp under the top element, below the status bar. Plan: 12 dp under the
 * status bar since the Daumen-Karte (owner 2026-11-26; the plan card is drawn over the box, so it never covers the primary button); Navigate: under the
 * turn card / status column (measured) – the re-route question too; My routes: under the title + tabs, over the list;
 * Settings: under the title row. Pure, unit-tested.
 */
enum class MessageScreen { PLAN, NAVIGATE, ROUTES, SETTINGS }

object MessagePlacement {
    /** The gap between the top element and the box (and the box's side margin). */
    const val GAP_DP = 12
    /** Top of the small download chip below the status bar: top left, 12 dp under the status bar (Daumen-Karte). */
    const val CHIP_TOP_DP = 12
    /** My routes: title row (4 + 56 + 4 dp) + Planned / Done tabs (48 dp). */
    const val ROUTES_HEADER_DP = 112
    /** Settings: the title row (4 + 56 + 4 dp). */
    const val SETTINGS_HEADER_DP = 64

    /** Plan: the bottom of the top row (0 since the Daumen-Karte – the status bar is the top element). */
    val planTopRowDp: Int get() = TOP_BAR_BOTTOM_DP

    /** The box's top below the status bar. [navigateColumnDp]: Navigate's measured top column (turn + status cards). */
    fun topDp(screen: MessageScreen, navigateColumnDp: Int = 0, planTopDp: Int = planTopRowDp): Int = GAP_DP + when (screen) {
        MessageScreen.PLAN -> planTopDp
        MessageScreen.NAVIGATE -> navigateColumnDp.coerceAtLeast(0)
        MessageScreen.ROUTES -> ROUTES_HEADER_DP
        MessageScreen.SETTINGS -> SETTINGS_HEADER_DP
    }
}
