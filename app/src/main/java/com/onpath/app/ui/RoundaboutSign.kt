package com.onpath.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the arms of the drawn roundabout go (a small roundabout with the roads, the one to take marked). Compass
 * degrees on the sign: 0 = up (straight on), clockwise; you come in from below (180°) and drive anticlockwise
 * (right-hand traffic) to the exit at [exitDeg]; [passedDeg] = the exits you pass before it, spread evenly – BRouter
 * gives the exit's number and the turn's angle, not the other roads' exact directions.
 */
object RoundaboutGeometry {
    data class Arms(val exitDeg: Float, val sweepDeg: Float, val passedDeg: List<Float>)

    /** [exit] 1, 2, …(0 = unknown: no passed exits drawn); [angle] = the turn (> 0 right, null = straight on). */
    fun arms(exit: Int, angle: Float?): Arms {
        var a = angle ?: 0f
        while (a <= -180f) a += 360f
        while (a > 180f) a -= 360f
        // from the entry (180°) anticlockwise = decreasing compass angle down to the exit; never a full circle
        val sweep = (180f - a).coerceIn(20f, 340f)
        val exitDeg = 180f - sweep
        val passed = (1 until exit.coerceAtLeast(1)).map { k -> 180f - sweep * k / exit }
        return Arms(exitDeg, sweep, passed)
    }
}

/** The roundabout sign of the turn card: ring, entry from below, the way through and out bold, passed exits as stubs. */
@Composable
fun RoundaboutSign(exit: Int, angle: Float?, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    val arms = RoundaboutGeometry.arms(exit, angle)
    Canvas(modifier) {
        val s = size.minDimension
        val c = Offset(size.width / 2, size.height / 2)
        val r = s * 0.22f
        val bold = s * 0.11f
        val thin = s * 0.06f
        fun at(deg: Float, radius: Float) = Offset(c.x + radius * sin(Math.toRadians(deg.toDouble())).toFloat(),
            c.y - radius * cos(Math.toRadians(deg.toDouble())).toFloat())
        // the whole ring, faint
        drawCircle(color.copy(alpha = 0.35f), r, c, style = Stroke(thin))
        // exits passed: short faint stubs
        for (d in arms.passedDeg) drawLine(color.copy(alpha = 0.45f), at(d, r), at(d, s * 0.44f), thin, StrokeCap.Round)
        // entry from below, the way round (anticlockwise), the exit taken – bold
        drawLine(color, Offset(c.x, size.height * 0.96f), at(180f, r), bold, StrokeCap.Round)
        val p = Path().apply {
            // Canvas arcs start at 3 o'clock and run clockwise: compass θ → θ − 90; exit → entry clockwise = same arc
            arcTo(androidx.compose.ui.geometry.Rect(c - Offset(r, r), Size(2 * r, 2 * r)), arms.exitDeg - 90f, arms.sweepDeg, true)
        }
        drawPath(p, color, style = Stroke(bold, cap = StrokeCap.Round))
        val tip = at(arms.exitDeg, s * 0.47f)
        drawLine(color, at(arms.exitDeg, r), at(arms.exitDeg, s * 0.38f), bold, StrokeCap.Round)
        // arrow head at the end of the exit
        val back = at(arms.exitDeg, s * 0.32f)
        val side = arms.exitDeg + 90f
        val w = s * 0.11f
        val head = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(back.x + w * sin(Math.toRadians(side.toDouble())).toFloat(), back.y - w * cos(Math.toRadians(side.toDouble())).toFloat())
            lineTo(back.x - w * sin(Math.toRadians(side.toDouble())).toFloat(), back.y + w * cos(Math.toRadians(side.toDouble())).toFloat())
            close()
        }
        drawPath(head, color)
    }
}
