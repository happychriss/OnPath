package com.onpath.app.ui

import kotlin.math.abs

/**
 * Where a dragged step sheet settles (owner field test 0.8.9: "the swipe is jumping"; owner 2026-10-22: small → profile
 * → points). The sheet follows the finger; on release it goes to the nearest step, or – flung – to the next step in the
 * fling's direction. Pure, unit tested (SheetDragTest).
 */
object SheetDrag {
    /**
     * [shownPx] = sheet height under the finger, [heightsPx] = height of each step (ascending), [velocityPxS] = release
     * speed (negative = upwards), [flingPxS] = how fast counts as a fling. Returns the step index.
     */
    fun target(shownPx: Float, heightsPx: List<Float>, velocityPxS: Float, flingPxS: Float): Int {
        if (heightsPx.isEmpty()) return 0
        val nearest = heightsPx.indices.minBy { abs(heightsPx[it] - shownPx) }
        return when {
            // flung up: the first step above the finger (a step only a hair above counts as reached)
            velocityPxS < -flingPxS -> heightsPx.indices.firstOrNull { heightsPx[it] > shownPx + 1f } ?: heightsPx.lastIndex
            velocityPxS > flingPxS -> heightsPx.indices.lastOrNull { heightsPx[it] < shownPx - 1f } ?: 0
            else -> nearest
        }
    }

    /** Height while dragging: follows the finger, with resistance beyond [minPx] / [maxPx] (rubber band). */
    fun follow(rawPx: Float, minPx: Float, maxPx: Float): Float = when {
        rawPx > maxPx -> maxPx + (rawPx - maxPx) * 0.3f
        rawPx < minPx -> minPx - (minPx - rawPx) * 0.3f
        else -> rawPx
    }
}
