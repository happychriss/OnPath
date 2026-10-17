package com.onpath.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.data.Format
import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint

/**
 * Simple elevation profile. Touching/dragging shows a cursor and reports the point so the
 * map can show a marker there; releasing clears it.
 */
@Composable
fun ElevationProfile(
    points: List<GeoPoint>,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
    color: Color = OnPathColors.Route,
    onHighlight: (GeoPoint?) -> Unit = {},
) {
    val data = remember(points) { profileData(points) }
    if (data == null) {
        Box(modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.CenterStart) {
            Text("No elevation data for this route", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    var cursor by remember(points) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = Color(0xFF555A55))
    val gridColor = Color(0x22000000)

    fun indexAt(x: Float, width: Float, left: Float): Int {
        val target = ((x - left) / (width - left)).coerceIn(0f, 1f) * data.total
        var lo = 0; var hi = data.dist.size - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (data.dist[mid] < target) lo = mid + 1 else hi = mid }
        return lo
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(data) {
                detectDragGestures(
                    onDragStart = { o -> cursor = indexAt(o.x, size.width.toFloat(), 40.dp.toPx()); onHighlight(points[data.idx[cursor!!]]) },
                    onDragEnd = {},
                    onDragCancel = {},
                    onDrag = { change, _ ->
                        cursor = indexAt(change.position.x, size.width.toFloat(), 40.dp.toPx())
                        onHighlight(points[data.idx[cursor!!]])
                    },
                )
            }
            .pointerInput(data) {
                detectTapGestures(onPress = { o ->
                    cursor = indexAt(o.x, size.width.toFloat(), 40.dp.toPx()); onHighlight(points[data.idx[cursor!!]])
                    tryAwaitRelease()
                })
            }
    ) {
        val left = 40.dp.toPx()
        val bottom = size.height - 16.dp.toPx()
        val top = 6.dp.toPx()
        val w = size.width - left
        val range = (data.max - data.min).coerceAtLeast(20.0)
        val minE = data.min - range * 0.08
        val maxE = data.max + range * 0.08
        fun x(d: Double) = left + (d / data.total * w).toFloat()
        fun y(e: Double) = (bottom - (e - minE) / (maxE - minE) * (bottom - top)).toFloat()

        // grid + labels
        for (e in listOf(data.min, data.max)) {
            drawLine(gridColor, Offset(left, y(e)), Offset(size.width, y(e)), 1f)
            val t = measurer.measure("${e.toInt()} m", labelStyle)
            drawText(t, topLeft = Offset(0f, (y(e) - t.size.height / 2).coerceIn(0f, bottom - t.size.height)))
        }
        val endLabel = measurer.measure(Format.distance(data.total), labelStyle)
        drawText(endLabel, topLeft = Offset(size.width - endLabel.size.width, bottom + 1.dp.toPx()))
        drawText(measurer.measure("0", labelStyle), topLeft = Offset(left, bottom + 1.dp.toPx()))

        // area + line
        val line = Path()
        data.dist.indices.forEach { i ->
            val px = x(data.dist[i]); val py = y(data.ele[i])
            if (i == 0) line.moveTo(px, py) else line.lineTo(px, py)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(x(data.total), bottom); lineTo(left, bottom); close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.05f)), top, bottom))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx()))

        cursor?.let { i ->
            val cx = x(data.dist[i]); val cy = y(data.ele[i])
            drawLine(Color(0xFF333333), Offset(cx, top), Offset(cx, bottom), 1.5f)
            drawCircle(Color.White, 6.dp.toPx(), Offset(cx, cy))
            drawCircle(color, 4.dp.toPx(), Offset(cx, cy))
            val t = measurer.measure("${data.ele[i].toInt()} m · ${Format.distance(data.dist[i])}", labelStyle)
            val tx = (cx - t.size.width / 2).coerceIn(left, size.width - t.size.width)
            drawRect(Color(0xEEFFFFFF), Offset(tx - 4, top), androidx.compose.ui.geometry.Size(t.size.width + 8f, t.size.height.toFloat()))
            drawText(t, topLeft = Offset(tx, top))
        }
    }
}

private class Profile(val dist: DoubleArray, val ele: DoubleArray, val idx: IntArray, val min: Double, val max: Double, val total: Double)

/** Keeps only points with elevation; null if there is not enough data. */
private fun profileData(points: List<GeoPoint>): Profile? {
    if (points.size < 2) return null
    val cum = Geo.cumulative(points)
    val keep = points.indices.filter { points[it].ele != null }
    if (keep.size < 2 || cum.last() <= 0) return null
    val d = DoubleArray(keep.size) { cum[keep[it]] }
    val e = DoubleArray(keep.size) { points[keep[it]].ele!! }
    return Profile(d, e, keep.toIntArray(), e.min(), e.max(), cum.last())
}
