package com.onpath.app.ui

/**
 * Where [OverviewPill] sits (owner 2026-11-06: it must cover nothing): top left, 12 dp under the status bar at the left
 * edge ([EDGE_DP]; Plan has no top band and no activity button on the map since the Daumen-Karte, 2026-11-26); the map
 * controls on the right start lower ([ControlsPlacement]), so the row is free to the right edge. Below the
 * background-download strip while that shows. Pure, unit-tested.
 */
object OverviewPillPlace {
    const val HEIGHT_DP = 48
    const val EDGE_DP = 12

    /** [rowTopDp]: the row's top; [stripBottomDp]: the download strip's bottom while it shows, else null. */
    fun topDp(rowTopDp: Int, stripBottomDp: Int?): Int =
        if (stripBottomDp != null) maxOf(rowTopDp, stripBottomDp + MessagePlacement.GAP_DP) else rowTopDp
}
