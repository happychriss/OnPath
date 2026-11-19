package com.onpath.app.ui

/**
 * The plan sheet rises by itself once per plan (owner 2026-11-08, father's feedback: nobody found that the sheet pulls
 * up). Owner 2026-11-08 evening, on the Pixel: the profile step was too far – it rises only so far that the row
 * "✕ Neu planen · ⇅ Umkehren · ↻ Rundtour" is fully in view: the summary step ([TO]), the row on top, the route's facts
 * under it. A sheet that already stands there (the usual case: the summary step grows with the route's lines) or higher
 * stays; a rolled-up sheet comes up to it once for a new plan. Never on a re-route of the same plan, never during a tour.
 * A tap on the handle steps it up one step ([PlanSize.tapped]). Pure – `SheetRiseTest`.
 */
object SheetRise {
    val TO = PlanSize.MINI

    /** The first route's fit waits this much longer while the sheet rises (its height is heard once it rests). */
    const val FIT_WAIT_MS = 500L

    /** [risen]: this plan already had its first route; [plan]: the Plan screen shows; [tour]: a tour records. */
    fun rises(risen: Boolean, size: PlanSize, plan: Boolean, tour: Boolean): Boolean =
        !risen && plan && !tour && size.step < TO.step

    /** A new plan begins (no points any more, or a saved route replaces it): its first route may raise the sheet again. */
    fun resets(pointCount: Int): Boolean = pointCount == 0
}
