package com.onpath.app.ui

/**
 * Opening and closing by tap AND drag (owner 2026-11-06 23:42): the plan sheet keeps its drag handle, Navigate's top
 * status card keeps its chevron ⌄, but both react to a tap and to a drag – whatever one tries, it works. Pure.
 */
object ExpandGesture {
    /** How far (dp) a drag on Navigate's card must go before it opens / closes – shorter is a wobble, not a pull. */
    const val CARD_DRAG_DP = 24f

    /**
     * Navigate's card after a vertical drag of [dyPx] (> 0 = finger down): pulled down past [thresholdPx] → open,
     * pushed up past it → closed, less → as it was. A drag that ends where it started never toggles.
     */
    fun cardAfterDrag(open: Boolean, dyPx: Float, thresholdPx: Float): Boolean = when {
        dyPx > thresholdPx -> true
        dyPx < -thresholdPx -> false
        else -> open
    }

    /** A tap anywhere on the card (not only the chevron) toggles it. */
    fun cardAfterTap(open: Boolean): Boolean = !open

    /** The sheet handle's tap makes the sheet bigger – except on the largest step, where it goes one back ([PlanSize.tapped]). */
    fun handleGrows(size: PlanSize): Boolean = size.tapped().step > size.step
}
