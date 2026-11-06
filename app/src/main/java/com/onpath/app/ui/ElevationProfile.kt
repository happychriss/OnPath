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
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.ProfileData
import com.onpath.app.routing.RouteSummary

/**
 * Simple elevation profile. Touching / sliding a finger sideways shows a cursor and reports the point so the map can
 * show a marker there; releasing clears it. A vertical drag is left to the sheet around it (the profile step is pulled
 * up and down over the profile too).
 *
 * Drawn from a cache (else the sheet stutters): the line, area and steepness paths are built once per route and size,
 * the canvas has its own layer – moving the sheet or the cursor does not rebuild them.
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
    /** Small stop signs on the line (Navigate, owner 2026-11-05): numbered dark circles, the goal dark red. */
    marks: List<ProfileMark> = emptyList(),
) {
    RecomposeProbe.hit("profile")
    val data = remember(points) { ProfileData.of(points) }
    if (data == null) {
        Box(modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.CenterStart) {
            Text(str(Res.string.plan_no_elevation), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val steep = remember(data, gradient) { if (gradient) data.steepness() else null }
    val cache = remember(data, steep, color) { ProfileCache(data, steep, color) }
    var cursor by remember { mutableStateOf<Int?>(null) }
    // the gesture survives new data (a GPS fix shortens the remaining profile mid-drag)
    val cur by androidx.compose.runtime.rememberUpdatedState(data)
    val pts by androidx.compose.runtime.rememberUpdatedState(points)
    val highlight by androidx.compose.runtime.rememberUpdatedState(onHighlight)
    val release by androidx.compose.runtime.rememberUpdatedState(onRelease)
    val measurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(fontSize = 12.sp, color = Color(0xFF555A55)) }
    // the sign's number is sized with the sign (map furniture), not with the font setting
    val fs = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val markStyle = remember(fs) { TextStyle(fontSize = (9f / fs).sp, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }

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
        // (a label range "0 .. negative" throws in coerceIn).
        if (bottom - top < 12.dp.toPx() || size.width - left < 24.dp.toPx()) return@Canvas
        val g = cache.geometry(size.width, left, top, bottom, 2.dp.toPx(), 2.5.dp.toPx())

        // grid + labels; on a flat profile the two heights lie on top of each other ("47 m" over "46 m", owner's Pixel
        // 2026-11-06): then only the top one is written
        val maxT = measurer.measure("${data.max.toInt()} m", labelStyle)
        val labelled = ProfileAxis.labels(data.min, data.max, g.y(data.min), g.y(data.max), maxT.size.height.toFloat())
        for (e in listOf(data.min, data.max)) {
            drawLine(GRID, Offset(left, g.y(e)), Offset(size.width, g.y(e)), 1f)
            if (e !in labelled) continue
            val t = if (e == data.max) maxT else measurer.measure("${e.toInt()} m", labelStyle)
            drawText(t, topLeft = Offset(0f, (g.y(e) - t.size.height / 2).coerceIn(0f, (bottom - t.size.height).coerceAtLeast(0f))))
        }
        val endLabel = measurer.measure(instrument(Format.distance(data.total), labelStyle), labelStyle)
        drawText(endLabel, topLeft = Offset(size.width - endLabel.size.width, bottom + 1.dp.toPx()))
        drawText(measurer.measure("0", labelStyle), topLeft = Offset(left, bottom + 1.dp.toPx()))

        // area + line (cached paths): the fill fades from the line's colour to nothing, steeper stretches a bit stronger
        // (owner 2026-11-06, polish 8 – smoothed over ~100 m, [ProfileFill])
        drawPath(g.area, g.areaBrush)
        g.steepFill?.forEachIndexed { k, p ->
            if (k > 0 && !p.isEmpty) drawPath(p, Brush.verticalGradient(
                listOf(color.copy(alpha = ProfileFill.EXTRA_ALPHA * k), color.copy(alpha = 0f)), top, bottom))
        }
        if (g.byClass == null) drawPath(g.line, color, style = g.lineStroke)
        else g.byClass.forEachIndexed { k, p -> if (!p.isEmpty) drawPath(p, steepColor(STEEP_ORDER[k], color), style = g.lineStroke) }

        // stop signs on the line: where each stop lies (distance from the profile's start), small, white ring
        for (m in marks) {
            if (m.distanceM < 0 || m.distanceM > data.total) continue
            val cx = g.x(m.distanceM); val cy = g.y(eleAt(data, m.distanceM))
            drawCircle(Color.White, 8.dp.toPx(), Offset(cx, cy))
            drawCircle(if (m.goal) OnPathColors.PointGoal else OnPathColors.PointStop, 6.5.dp.toPx(), Offset(cx, cy))
            // the goal: a small flag like on the map and in Navigate (owner 2026-11-06, polish 7)
            if (m.goal) drawPath(GoalFlag.path(cx, cy, 9.dp.toPx()), Color.White)
            else {
                val t = measurer.measure(m.number.toString(), markStyle)
                drawText(t, topLeft = Offset(cx - t.size.width / 2f, cy - t.size.height / 2f))
            }
        }

        cursor?.let { i ->
            val cx = g.x(data.dist[i]); val cy = g.y(data.ele[i])
            drawLine(Color(0xFF333333), Offset(cx, top), Offset(cx, bottom), 1.5f)
            drawCircle(Color.White, 6.dp.toPx(), Offset(cx, cy))
            drawCircle(color, 4.dp.toPx(), Offset(cx, cy))
            val t = measurer.measure(instrument("${data.ele[i].toInt()} m · ${Format.distance(data.dist[i])}", labelStyle), labelStyle)
            val tx = (cx - t.size.width / 2).coerceIn(left, (size.width - t.size.width).coerceAtLeast(left))
            drawRect(Color(0xEEFFFFFF), Offset(tx - 4, top), androidx.compose.ui.geometry.Size(t.size.width + 8f, t.size.height.toFloat()))
            drawText(t, topLeft = Offset(tx, top))
        }
    }
}

private val GRID = Color(0x22000000)
private val STEEP_ORDER = RouteSummary.Steepness.entries

/**
 * The profile's paths for one route, built once per drawing size. Unit tested (ProfileCacheTest): asking again for the
 * same size returns the same geometry.
 */
internal class ProfileCache(private val data: ProfileData, private val steep: List<RouteSummary.Steepness>?, private val color: Color) {
    class Geometry(
        val x: (Double) -> Float, val y: (Double) -> Float,
        val line: Path, val area: Path, val areaBrush: Brush,
        /** Steepness classes (FLAT, MODERATE, STEEP, VERY_STEEP) as one path each; null = one colour. */
        val byClass: List<Path>?, val lineStroke: Stroke,
        /** The fill under the line per [ProfileFill] level (0 = none extra); null without steepness. */
        val steepFill: List<Path>? = null,
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
        // about the pixel width: first / lowest / highest / last point per pixel column (a 250 km route would draw 10k+
        // vertices per frame); peaks and valleys stay exact
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
        // the fill's extra strength per kept span: the strongest smoothed level of the segments in it; equal neighbours
        // join into one shape (no seams, no stripes)
        val steepFill = steep?.let { s ->
            val level = ProfileFill.levels(s, data.dist)
            val paths = List(ProfileFill.LEVELS) { Path() }
            var j = 0
            while (j < keep.size - 1) {
                fun spanLevel(q: Int): Int {
                    var k = 0
                    for (i in keep[q] until minOf(keep[q + 1], level.size)) if (level[i] > k) k = level[i]
                    return k
                }
                val k = spanLevel(j)
                var e = j + 1
                while (e < keep.size - 1 && spanLevel(e) == k) e++
                if (k > 0) paths[k].apply {
                    moveTo(x(data.dist[keep[j]]), bottom)
                    for (q in j..e) lineTo(x(data.dist[keep[q]]), y(data.ele[keep[q]]))
                    lineTo(x(data.dist[keep[e]]), bottom); close()
                }
                j = e
            }
            paths
        }
        val g = Geometry(x, y, line, area,
            Brush.verticalGradient(listOf(color.copy(alpha = ProfileFill.BASE_ALPHA), color.copy(alpha = 0f)), top, bottom),
            byClass,
            if (byClass == null) Stroke(width = lineW) else Stroke(width = steepW, cap = StrokeCap.Round, join = StrokeJoin.Round),
            steepFill)
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

/** A stop on the profile: metres from the profile's start, its number, the goal. */
data class ProfileMark(val distanceM: Double, val number: Int, val goal: Boolean)

/** The profile's elevation at [d] metres from its start (linear between the kept points). */
internal fun eleAt(data: com.onpath.app.routing.ProfileData, d: Double): Double {
    val dist = data.dist
    if (d <= dist.first()) return data.ele.first()
    if (d >= dist.last()) return data.ele.last()
    var lo = 0; var hi = dist.size - 1
    while (hi - lo > 1) { val mid = (lo + hi) / 2; if (dist[mid] <= d) lo = mid else hi = mid }
    val t = (d - dist[lo]) / (dist[hi] - dist[lo]).coerceAtLeast(1e-9)
    return data.ele[lo] + t * (data.ele[hi] - data.ele[lo])
}

/** Which heights the profile's left axis writes (pure, `ProfileAxisTest`). */
internal object ProfileAxis {
    /**
     * [min] and [max] at the drawn heights [yMin] / [yMax] (pixels, y down) with labels [textH] high: both when they
     * keep a label's height and a bit apart and differ as written (whole metres), else only [max].
     */
    fun labels(min: Double, max: Double, yMin: Float, yMax: Float, textH: Float): List<Double> =
        if (min.toInt() != max.toInt() && kotlin.math.abs(yMin - yMax) >= textH * 1.2f) listOf(min, max) else listOf(max)
}

/**
 * The profile's fill (owner 2026-11-06, polish 8): [BASE_ALPHA] of the line's colour at the top fading to nothing, plus
 * [EXTRA_ALPHA] per steepness level (5–10 %, 10–15 %, > 15 %) where it is steep. The levels come from the 100 m windowed
 * steepness ([RouteSummary.steepness]) and a run shorter than [MIN_RUN_M] takes its neighbour's level – so a short
 * DEM bump never paints a stripe. Pure, unit-tested.
 */
internal object ProfileFill {
    const val BASE_ALPHA = 0.30f
    const val EXTRA_ALPHA = 0.08f
    const val LEVELS = 4
    const val MIN_RUN_M = 100.0

    /** Per segment (i → i + 1) its level 0..3, short runs smoothed away. */
    fun levels(steep: List<RouteSummary.Steepness>, dist: DoubleArray): IntArray {
        val n = minOf(steep.size, dist.size - 1).coerceAtLeast(0)
        val out = IntArray(n) { steep[it].ordinal }
        if (n == 0) return out
        // runs of equal level: [start, end)
        val runs = mutableListOf<IntArray>()
        var a = 0
        for (i in 1..n) if (i == n || out[i] != out[a]) { runs += intArrayOf(a, i); a = i }
        if (runs.size < 2) return out
        for ((r, run) in runs.withIndex()) {
            val len = dist[run[1]] - dist[run[0]]
            if (len >= MIN_RUN_M) continue
            // a short run takes the lower of its neighbours' levels (never invents steepness)
            val prev = runs.getOrNull(r - 1)?.let { out[it[0]] }
            val next = runs.getOrNull(r + 1)?.let { out[it[0]] }
            val to = listOfNotNull(prev, next).minOrNull() ?: continue
            for (i in run[0] until run[1]) out[i] = to
        }
        return out
    }
}

/** The goal's flag (Material "flag", 24-unit grid) centred on (cx, cy), [size] px high – profile and map draw the same. */
internal object GoalFlag {
    /** The outline as (x, y) pairs on the 24 grid. */
    val OUTLINE = floatArrayOf(14.4f, 6f, 14f, 4f, 5f, 4f, 5f, 21f, 7f, 21f, 7f, 14f, 12.6f, 14f, 13f, 16f, 20f, 16f, 20f, 6f)

    fun path(cx: Float, cy: Float, size: Float): Path {
        val k = size / 17f // the flag spans 4..21 on the grid
        val ox = cx - 12.5f * k; val oy = cy - 12.5f * k
        return Path().apply {
            for (i in OUTLINE.indices step 2) {
                val px = ox + OUTLINE[i] * k; val py = oy + OUTLINE[i + 1] * k
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }
    }
}
