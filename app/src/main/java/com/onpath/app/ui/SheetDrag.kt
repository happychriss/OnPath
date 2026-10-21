package com.onpath.app.ui

import kotlin.math.abs

/**
 * Where a dragged bottom sheet settles (owner field test 0.8.9: "the swipe is jumping"). The sheet follows the finger;
 * on release it goes one size up / down when it was pulled far enough or flung, otherwise back to its size. Pure,
 * unit tested (SheetDragTest).
 */
object SheetDrag {
    enum class Settle { SMALLER, SAME, BIGGER }

    /**
     * [shownPx] = sheet height under the finger, [naturalPx] = height of its current size, [velocityPxS] = release
     * speed (negative = upwards), [distancePx] / [flingPxS] = how far / how fast counts as a decision.
     */
    fun settle(shownPx: Float, naturalPx: Float, velocityPxS: Float, distancePx: Float, flingPxS: Float): Settle {
        val d = shownPx - naturalPx  // > 0 = pulled up (bigger)
        return when {
            // a fling decides, unless it goes against a long pull (finger moved back at the end)
            velocityPxS > flingPxS && d < distancePx -> Settle.SMALLER
            velocityPxS < -flingPxS && d > -distancePx -> Settle.BIGGER
            abs(velocityPxS) <= flingPxS && d <= -distancePx -> Settle.SMALLER
            abs(velocityPxS) <= flingPxS && d >= distancePx -> Settle.BIGGER
            else -> Settle.SAME
        }
    }

    /** Height while dragging: follows the finger, with resistance beyond [minPx] / [maxPx] (rubber band). */
    fun follow(rawPx: Float, minPx: Float, maxPx: Float): Float = when {
        rawPx > maxPx -> maxPx + (rawPx - maxPx) * 0.3f
        rawPx < minPx -> minPx - (minPx - rawPx) * 0.3f
        else -> rawPx
    }
}
