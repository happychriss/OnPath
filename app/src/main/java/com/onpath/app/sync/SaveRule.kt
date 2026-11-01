package com.onpath.app.sync

/**
 * Saving over a saved route (review sync H3, shared by the app and the web planner): when the stored route changed since
 * it was opened here – sync brought the other side's edit meanwhile – an overwrite would lose that edit without a
 * trace. Then the work is saved as a copy ("‹name› (2)") instead, no question (A-017: "the older stays as a copy").
 * Each side compares a key of the stored content: the app the route points ([pointsKey]), the web its plan code.
 */
object SaveRule {
    /** True = save as a copy: [opened] (the stored content when it was opened / last saved here) differs from [stored] now. */
    fun keepsOtherEdit(opened: String?, stored: String?): Boolean = opened != null && stored != null && opened != stored

    /** A key of route points (lat, lon) for [keepsOtherEdit]; 6 decimals (~0.1 m) so stored doubles compare alike. */
    fun pointsKey(points: List<Pair<Double, Double>>): String =
        points.joinToString(";") { (lat, lon) -> "${Math.round(lat * 1e6)},${Math.round(lon * 1e6)}" }
}
