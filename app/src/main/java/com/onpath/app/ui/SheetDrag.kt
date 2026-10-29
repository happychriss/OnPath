package com.onpath.app.ui

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where a dragged step sheet (small → profile → points) settles. The sheet follows the finger; on release it goes to
 * the nearest step, or – flung – to the next step in the fling's direction.
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

    /**
     * One events-trace line per release: where the finger left the sheet, the steps, the speed and the step chosen –
     * "Sheet let go at 1143 px (steps 536 / 938 / 1602 px, 0 px/s) → step 1" – explains a swipe that settles somewhere
     * unexpected.
     */
    fun describe(shownPx: Float, heightsPx: List<Float>, velocityPxS: Float, target: Int): String =
        "Sheet let go at ${shownPx.roundToInt()} px (steps ${heightsPx.joinToString(" / ") { "${it.roundToInt()}" }} px, " +
            "${velocityPxS.roundToInt()} px/s) → step $target"

    /** Height while dragging: follows the finger, with resistance beyond [minPx] / [maxPx] (rubber band). */
    fun follow(rawPx: Float, minPx: Float, maxPx: Float): Float = when {
        rawPx > maxPx -> maxPx + (rawPx - maxPx) * 0.3f
        rawPx < minPx -> minPx - (minPx - rawPx) * 0.3f
        else -> rawPx
    }
}
