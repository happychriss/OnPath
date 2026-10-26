package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.StrokeCap
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
import com.onpath.app.routing.RouteSummary

/**
 * Simple elevation profile. Touching / sliding a finger sideways shows a cursor and reports the point so the map can
 * show a marker there; releasing clears it. A vertical drag is left to the sheet around it (owner 2026-10-22: the
 * profile step is pulled up and down over the profile too).
 *
 * Drawn from a cache (owner field report 0.9.3, the sheet stuttered): the line, area and steepness paths are built once
 * per route and size, the canvas has its own layer – moving the sheet or the cursor does not rebuild them.
 */
@Composable
fun ElevationProfile(
    points: List<GeoPoint>,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
    color: Color = OnPathColors.Route,
    /** Colour the line by steepness (< 5 % route colour, 5–10 amber, 10–15 orange, > 15 red). */
    gradient: Boolean = false,
    onHighlight: (GeoPoint?) -> Unit = {},
    /** The finger left the profile (drag end / tap release). */
    onRelease: () -> Unit = {},
) {
    RecomposeProbe.hit("profile")
    val data = remember(points) { profileData(points) }
    if (data == null) {
        Box(modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.CenterStart) {
            Text(str(Res.string.plan_no_elevation), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val steep = remember(data, gradient) { if (gradient) RouteSummary.steepness(data.dist, data.ele) else null }
    val cache = remember(data, steep, color) { ProfileCache(data, steep, color) }
    var cursor by remember { mutableStateOf<Int?>(null) }
    // the gesture survives new data (a GPS fix shortens the remaining profile mid-drag – polish audit A6)
    val cur by androidx.compose.runtime.rememberUpdatedState(data)
    val pts by androidx.compose.runtime.rememberUpdatedState(points)
    val highlight by androidx.compose.runtime.rememberUpdatedState(onHighlight)
    val release by androidx.compose.runtime.rememberUpdatedState(onRelease)
    val measurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(fontSize = 12.sp, color = Color(0xFF555A55)) }

    fun indexAt(x: Float, width: Float, left: Float): Int {
        val d = cur
        val target = ((x - left) / (width - left)).coerceIn(0f, 1f) * d.total
        var lo = 0; var hi = d.dist.size - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (d.dist[mid] < target) lo = mid + 1 else hi = mid }
        return lo
    }
    fun show(i: Int) { cursor = i; pts.getOrNull(cur.idx.getOrElse(i) { 0 })?.let { highlight(it) } }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> show(indexAt(o.x, size.width.toFloat(), 40.dp.toPx())) },
                    onDragEnd = { cursor = null; release() },
                    onDragCancel = { cursor = null; release() },
                    onHorizontalDrag = { change, _ -> change.consume(); show(indexAt(change.position.x, size.width.toFloat(), 40.dp.toPx())) },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = { o ->
                    show(indexAt(o.x, size.width.toFloat(), 40.dp.toPx()))
                    tryAwaitRelease()
                    cursor = null
                    release()
                })
            }
    ) {
        RecomposeProbe.hit("profile-draw")
        val left = 40.dp.toPx()
        val bottom = size.height - 16.dp.toPx()
        val top = 6.dp.toPx()
        // While the sheet opens / closes the profile is briefly only a few pixels high or narrow: draw nothing then
        // (field crash 2026-10-21: label range "0 .. negative" → IllegalArgumentException in coerceIn).
        if (bottom - top < 12.dp.toPx() || size.width - left < 24.dp.toPx()) return@Canvas
        val g = cache.geometry(size.width, left, top, bottom, 2.dp.toPx(), 2.5.dp.toPx())

        // grid + labels
        for (e in listOf(data.min, data.max)) {
            drawLine(GRID, Offset(left, g.y(e)), Offset(size.width, g.y(e)), 1f)
            val t = measurer.measure("${e.toInt()} m", labelStyle)
            drawText(t, topLeft = Offset(0f, (g.y(e) - t.size.height / 2).coerceIn(0f, (bottom - t.size.height).coerceAtLeast(0f))))
        }
        val endLabel = measurer.measure(Format.distance(data.total), labelStyle)
        drawText(endLabel, topLeft = Offset(size.width - endLabel.size.width, bottom + 1.dp.toPx()))
        drawText(measurer.measure("0", labelStyle), topLeft = Offset(left, bottom + 1.dp.toPx()))

        // area + line (cached paths)
        drawPath(g.area, g.areaBrush)
        if (g.byClass == null) drawPath(g.line, color, style = g.lineStroke)
        else g.byClass.forEachIndexed { k, p -> if (!p.isEmpty) drawPath(p, steepColor(STEEP_ORDER[k], color), style = g.lineStroke) }

        cursor?.let { i ->
            val cx = g.x(data.dist[i]); val cy = g.y(data.ele[i])
            drawLine(Color(0xFF333333), Offset(cx, top), Offset(cx, bottom), 1.5f)
            drawCircle(Color.White, 6.dp.toPx(), Offset(cx, cy))
            drawCircle(color, 4.dp.toPx(), Offset(cx, cy))
            val t = measurer.measure("${data.ele[i].toInt()} m · ${Format.distance(data.dist[i])}", labelStyle)
            val tx = (cx - t.size.width / 2).coerceIn(left, (size.width - t.size.width).coerceAtLeast(left))
            drawRect(Color(0xEEFFFFFF), Offset(tx - 4, top), androidx.compose.ui.geometry.Size(t.size.width + 8f, t.size.height.toFloat()))
            drawText(t, topLeft = Offset(tx, top))
        }
    }
}

private val GRID = Color(0x22000000)
private val STEEP_ORDER = RouteSummary.Steepness.entries

/**
 * The profile's paths for one route, built once per drawing size (owner field report 0.9.3). Unit tested
 * (ProfileCacheTest): asking again for the same size returns the same geometry.
 */
internal class ProfileCache(private val data: Profile, private val steep: List<RouteSummary.Steepness>?, private val color: Color) {
    class Geometry(
        val x: (Double) -> Float, val y: (Double) -> Float,
        val line: Path, val area: Path, val areaBrush: Brush,
        /** Steepness classes (FLAT, MODERATE, STEEP, VERY_STEEP) as one path each; null = one colour. */
        val byClass: List<Path>?, val lineStroke: Stroke,
    )
    private var key: List<Float>? = null
    private var geometry: Geometry? = null
    /** How often the paths were really built (tests). */
    var builds = 0
        private set

    fun geometry(width: Float, left: Float, top: Float, bottom: Float, lineW: Float, steepW: Float): Geometry {
        val k = listOf(width, left, top, bottom, lineW, steepW)
        geometry?.let { if (k == key) return it }
        builds++
        val w = width - left
        val range = (data.max - data.min).coerceAtLeast(20.0)
        val minE = data.min - range * 0.08
        val maxE = data.max + range * 0.08
        val x = { d: Double -> left + (d / data.total * w).toFloat() }
        val y = { e: Double -> (bottom - (e - minE) / (maxE - minE) * (bottom - top)).toFloat() }
        // about the pixel width: first / lowest / highest / last point per pixel column (perf report 2026-10-22: a
        // 250 km route drew 10k+ vertices per frame); peaks and valleys stay exact
        val keep = columnIndices(data.dist, data.ele, data.total, w.toInt().coerceAtLeast(1))
        val line = Path()
        for (k in keep.indices) {
            val i = keep[k]
            val px = x(data.dist[i]); val py = y(data.ele[i])
            if (k == 0) line.moveTo(px, py) else line.lineTo(px, py)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(x(data.total), bottom); lineTo(left, bottom); close()
        }
        // one path per steepness class: 4 draws instead of one per segment (thousands on a long route)
        val byClass = steep?.let { s ->
            val paths = List(STEEP_ORDER.size) { Path() }
            val lastEnd = IntArray(STEEP_ORDER.size) { -1 }
            // per kept span (keep[j] .. keep[j + 1]) the steepest class of the segments in it: short steep bits stay visible
            for (j in 0 until keep.size - 1) {
                val a = keep[j]; val b = keep[j + 1]
                var k = 0
                for (i in a until minOf(b, s.size)) if (s[i].ordinal > k) k = s[i].ordinal
                val p = paths[k]
                if (lastEnd[k] != a) p.moveTo(x(data.dist[a]), y(data.ele[a]))
                p.lineTo(x(data.dist[b]), y(data.ele[b]))
                lastEnd[k] = b
            }
            paths
        }
        val g = Geometry(x, y, line, area,
            Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.05f)), top, bottom),
            byClass,
            if (byClass == null) Stroke(width = lineW) else Stroke(width = steepW, cap = StrokeCap.Round, join = StrokeJoin.Round))
        key = k
        geometry = g
        return g
    }
}

private val STEEP_COLORS = listOf(Color(0xFFF2B33D), Color(0xFFEF7C1A), Color(0xFFD32F2F))

private fun steepColor(s: RouteSummary.Steepness, flat: Color) = when (s) {
    RouteSummary.Steepness.FLAT -> flat
    RouteSummary.Steepness.MODERATE -> STEEP_COLORS[0]
    RouteSummary.Steepness.STEEP -> STEEP_COLORS[1]
    RouteSummary.Steepness.VERY_STEEP -> STEEP_COLORS[2]
}

/** One-line legend for the gradient colours. */
@Composable
fun GradientLegend(flat: Color = OnPathColors.Route) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("< 5 %" to flat, "5–10 %" to STEEP_COLORS[0], "10–15 %" to STEEP_COLORS[1], "> 15 %" to STEEP_COLORS[2])
            .forEach { (t, c) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(12.dp).height(3.dp).background(c))
                    Spacer(Modifier.width(4.dp))
                    Text(t, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
    }
}

/**
 * Indices of the points to draw for [columns] pixel columns: all when there are few, else per column the first, the
 * lowest, the highest and the last point, in order (the line looks the same at that width). Pure, unit-tested.
 */
internal fun columnIndices(dist: DoubleArray, ele: DoubleArray, total: Double, columns: Int): IntArray {
    val n = dist.size
    if (n <= 4 * columns || total <= 0) return IntArray(n) { it }
    val out = ArrayList<Int>(4 * columns + 2)
    var i = 0
    while (i < n) {
        val col = (dist[i] / total * columns).toInt().coerceIn(0, columns - 1)
        var lo = i; var hi = i; var j = i
        while (j + 1 < n && (dist[j + 1] / total * columns).toInt().coerceIn(0, columns - 1) == col) {
            j++
            if (ele[j] < ele[lo]) lo = j
            if (ele[j] > ele[hi]) hi = j
        }
        for (k in listOf(i, minOf(lo, hi), maxOf(lo, hi), j)) if (out.isEmpty() || out.last() < k) out += k
        i = j + 1
    }
    return out.toIntArray()
}

internal class Profile(val dist: DoubleArray, val ele: DoubleArray, val idx: IntArray, val min: Double, val max: Double, val total: Double)

/** Keeps only points with elevation; null if there is not enough data. */
internal fun profileData(points: List<GeoPoint>): Profile? {
    if (points.size < 2) return null
    val cum = Geo.cumulative(points)
    val keep = points.indices.filter { points[it].ele != null }
    if (keep.size < 2 || cum.last() <= 0) return null
    val d = DoubleArray(keep.size) { cum[keep[it]] }
    val e = DoubleArray(keep.size) { points[keep[it]].ele!! }
    return Profile(d, e, keep.toIntArray(), e.min(), e.max(), cum.last())
}
