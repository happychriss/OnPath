package com.onpath.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.onpath.app.data.Format
import com.onpath.app.data.Tour
import com.onpath.app.routing.GeoPoint
import com.onpath.strings.*

/**
 * The overview's map picture and where the route's start and goal lie on it (pixels): the overview draws the start dot
 * and the goal flag over it, the same signs as over the drawn shape.
 */
class OverviewPicture(val image: ImageBitmap, val start: Offset, val goal: Offset)

/** Makes the overview's map picture for (segments, colour, width px, height px); null = none (the shape stays). */
typealias OverviewSnapshot = suspend (List<List<GeoPoint>>, RouteShape.Tint, Int, Int) -> OverviewPicture?

/**
 * The lines of My routes' rows, kept while the app runs (scrolling back must not read the database again). Keyed by the
 * tour and what changes its line ([key]); the plan on the map has its own changing key (points, distance).
 */
internal object RouteLineCache {
    private val lru = android.util.LruCache<String, List<List<GeoPoint>>>(64)
    fun key(t: Tour) = "${t.id}:${t.uuid}:${t.distanceM}:${t.waypoints.size}:${t.createdMs}"
    fun get(t: Tour): List<List<GeoPoint>>? = lru.get(key(t))
    fun put(t: Tour, line: List<List<GeoPoint>>) { lru.put(key(t), line) }
}

/** A row's line: from [RouteLineCache] at once, else read once by [load] (off the main thread there); null meanwhile. */
@Composable
internal fun rememberRouteLine(t: Tour, load: suspend (Tour) -> List<List<GeoPoint>>): List<List<GeoPoint>>? {
    val key = RouteLineCache.key(t)
    var line by remember(key) { mutableStateOf(RouteLineCache.get(t)) }
    if (line == null) LaunchedEffect(key) { line = load(t).also { RouteLineCache.put(t, it) } }
    return line
}

/** The line's colour of a [RouteShape.Tint]. */
internal fun RouteShape.Tint.color(): Color = if (this == RouteShape.Tint.DONE) OnPathColors.ActionGo else OnPathColors.ActionEdit

/** The tile's size in My routes (the row keeps its height: the old 44 dp sign + air). */
internal val PREVIEW_TILE = 52.dp

/**
 * The small route preview at the start of a My routes row: the shape on paper, a start dot and the goal flag. [segments]
 * null = still loading or nothing to draw → [placeholder] (the kind's sign as before), quietly. Not a button of its own:
 * the whole row opens the overview (owner 2026-11-07, 00:30).
 */
@Composable
internal fun RoutePreviewTile(segments: List<List<GeoPoint>>?, tint: RouteShape.Tint, pale: Boolean,
                              placeholder: @Composable () -> Unit, modifier: Modifier = Modifier,
                              /** A tap on the tile: the large overview; non-null = the ⤢ badge (owner 2026-11-07 09:40). */
                              onEnlarge: (() -> Unit)? = null) {
    val label = str(if (onEnlarge != null) Res.string.routes_overview_enlarge else Res.string.routes_overview_show)
    // the badge sits on the tile's corner, a little outside it: the outer box is that much larger
    Box(modifier.size(PREVIEW_TILE + ENLARGE_OUT).let { m ->
            if (onEnlarge != null) m.clickable(onClickLabel = label, role = Role.Button) { onEnlarge() } else m
        }.semantics { contentDescription = label }) {
        Box(Modifier.size(PREVIEW_TILE).clip(OnPathShapes.Small).background(OnPathColors.PaperDim), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val px = with(density) { PREVIEW_TILE.toPx() }
            // with the ⤢ badge: nothing is drawn under it (the line, start or goal fitted beside / above its corner)
            val corner = if (onEnlarge == null) 0f
                else with(density) { RouteShape.cornerFor((ENLARGE_BADGE - ENLARGE_OUT).toPx(), (3.dp * 1.6f).toPx()) }
            val shape = remember(segments, px, corner) { segments?.let { RouteShape.of(it, px, px, with(density) { 8.dp.toPx() }, corner = corner) } }
            if (shape == null) placeholder()
            else RouteCanvas(shape, tint.color().copy(alpha = if (pale) 0.45f else 1f), 2.5.dp, 3.dp, Modifier.size(PREVIEW_TILE))
        }
        // ⤢ "vergrößern": a paper ring round an ink-blue disc with a 16 dp paper sign – plain on paper, and on the line
        if (onEnlarge != null) Box(Modifier.align(Alignment.BottomEnd).size(ENLARGE_BADGE).clip(CircleShape)
            .background(OnPathColors.Paper).padding(2.dp).clip(CircleShape).background(OnPathColors.ActionEdit),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.OpenInFull, null, tint = OnPathColors.Paper, modifier = Modifier.size(ENLARGE_ICON))
        }
    }
}

/** The ⤢ badge on a preview tile: its size, its sign (≥ 16 dp, owner 2026-11-07), how far it reaches out of the tile. */
internal val ENLARGE_BADGE = 26.dp
internal val ENLARGE_ICON = 16.dp
internal val ENLARGE_OUT = 6.dp

@Composable
private fun RouteCanvas(shape: RouteShape.Shape, color: Color, width: Dp, dot: Dp, modifier: Modifier) {
    Canvas(modifier) { drawShape(shape, color, width.toPx(), dot.toPx()) }
}

/** The route's start: a dot in the line's colour on a paper ring (preview, drawn shape, map picture alike). */
private fun DrawScope.drawStart(at: Offset, color: Color, dot: Float) {
    drawCircle(OnPathColors.Paper, dot * 1.6f, at)
    drawCircle(color, dot, at)
}

private fun DrawScope.drawShape(shape: RouteShape.Shape, color: Color, width: Float, dot: Float) {
    val stroke = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    for (l in shape.lines) {
        val path = Path().apply {
            moveTo(l[0], l[1])
            var i = 2
            while (i < l.size) { lineTo(l[i], l[i + 1]); i += 2 }
        }
        drawPath(path, color, style = stroke)
    }
    // start: a dot in the line's colour; goal: dark red (the map's goal colour) – the flag is drawn by the caller in large
    drawStart(Offset(shape.startX, shape.startY), color, dot)
    drawCircle(OnPathColors.Paper, dot * 1.6f, Offset(shape.endX, shape.endY))
    drawCircle(OnPathColors.PointGoal, dot, Offset(shape.endX, shape.endY))
}

/**
 * The large overview of a route (owner 2026-11-07, 00:30: a row tap in My routes opens this first, not Plan): the real
 * map fitted to the whole route ([snapshot], MapLibre's snapshotter – a still picture: light, no second live map), the
 * route's shape drawn at once while the map picture is made (and instead of it when there is none), name + facts, and
 * two buttons: [primary] ("Planen", or "Folgen" during a tour) and "Abbrechen" (nothing changed) – for a planned route
 * without a tour three ([onGo], owner 2026-11-07): "Los geht's", "Planen", "Abbrechen" ([OverviewFoot]). Never touches the plan
 * on the map or a running tour by itself. Drawn in place over My routes (full screen, system bars kept free) – a
 * full-screen Dialog window got no navigation-bar inset (its buttons lay under the gesture bar, emulator 2026-11-07).
 */
@Composable
internal fun RouteOverview(
    t: Tour, segments: List<List<GeoPoint>>?, touring: Boolean,
    snapshot: OverviewSnapshot?,
    onPrimary: () -> Unit, onCancel: () -> Unit,
    /** "Los geht's" (load and start the tour); null = not offered (a done tour, or during a tour: "Folgen" is the primary). */
    onGo: (() -> Unit)? = null,
) {
    val tint = RouteShape.tint(t.kind)
    run {
        Surface(Modifier.fillMaxSize(), color = OnPathColors.Paper) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                Text(t.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // a value never breaks inside ("6 h 06 min" whole) – the line wraps only between the values
                Text(instrument(listOf(Format.distance(t.distanceM), "↑${Format.meters(t.ascentM)}", Format.duration(t.durationS))
                    .joinToString(" · ") { it.replace(' ', '\u00A0') },
                    MaterialTheme.typography.bodyLarge, MaterialTheme.colorScheme.onSurfaceVariant),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp).clip(OnPathShapes.Card)
                    .background(OnPathColors.PaperDim), contentAlignment = Alignment.Center) {
                    val density = LocalDensity.current
                    val wPx = with(density) { maxWidth.roundToPx() }; val hPx = with(density) { maxHeight.roundToPx() }
                    var image by remember(segments) { mutableStateOf<OverviewPicture?>(null) }
                    var loading by remember(segments) { mutableStateOf(snapshot != null) }
                    if (segments != null && snapshot != null) LaunchedEffect(segments, wPx, hPx) {
                        image = snapshot(segments, tint, wPx, hPx); loading = false
                    }
                    val shape = remember(segments, wPx, hPx) {
                        segments?.let { RouteShape.of(it, wPx.toFloat(), hPx.toFloat(), with(density) { FitPaddingRule.SIDE_DP.dp.toPx() }) }
                    }
                    image?.let { pic ->
                        Image(pic.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        // the same signs as over the drawn shape: start dot, goal flag (the map picture has no markers)
                        Canvas(Modifier.fillMaxSize()) { drawStart(pic.start, tint.color(), 6.dp.toPx()) }
                        GoalFlag(pic.goal.x, pic.goal.y, density)
                    } ?: shape?.let { s ->
                        RouteCanvas(s, tint.color(), 4.dp, 6.dp, Modifier.fillMaxSize())
                        GoalFlag(s.endX, s.endY, density)
                    }
                    if (image == null && loading) Text(str(Res.string.routes_overview_loading), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp))
                    if (shape == null && image == null && !loading) Text(str(Res.string.plan_no_track_recorded),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onGo != null) OverviewFoot(onPlan = onPrimary, onGo = onGo, onCancel = onCancel)
                else OverviewButtons(primary = str(if (touring) Res.string.routes_overview_follow else Res.string.routes_overview_plan),
                    go = touring, onPrimary = onPrimary, onCancel = onCancel)
            }
        }
    }
}

/** The goal flag at ([x], [y]) px – the route's last point (the map's goal sign: white flag on dark red). */
@Composable
private fun GoalFlag(x: Float, y: Float, density: androidx.compose.ui.unit.Density) {
    val size = 24.dp
    val half = with(density) { (size / 2).roundToPx() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = with(density) { (x.toInt() - half).coerceAtLeast(0).toDp() },
            top = with(density) { (y.toInt() - half).coerceAtLeast(0).toDp() })
            .size(size).clip(CircleShape).background(OnPathColors.PointGoal), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Flag, null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
    }
}

/**
 * The overview's foot for a planned route (owner 2026-11-07): "▶ Los geht's" full width green – the plan sheet's foot –
 * and under it Abbrechen (text) | Planen (tonal blue), equal halves; at a large font all three full width, one under the
 * other (Los geht's, Planen, Abbrechen – M3, never ragged).
 */
@Composable
private fun OverviewFoot(onPlan: () -> Unit, onGo: () -> Unit, onCancel: () -> Unit) {
    val stacked = LocalDensity.current.fontScale >= DraftLook.STACK_FONT_SCALE
    val go = @Composable { m: Modifier ->
        Button(onGo, colors = ActionButtons.go(), modifier = m.heightIn(min = 56.dp)) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(22.dp)); androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            Text(str(Res.string.plan_bar_go), style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
        }
    }
    val plan = @Composable { m: Modifier ->
        androidx.compose.material3.FilledTonalButton(onPlan, modifier = m.heightIn(min = 56.dp),
            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = OnPathColors.EditTonal,
                contentColor = OnPathColors.OnEditTonal)) {
            Icon(Icons.Filled.Route, null, Modifier.size(20.dp)); androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            Text(str(Res.string.routes_overview_plan), fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
    }
    val cancel = @Composable { m: Modifier ->
        androidx.compose.material3.TextButton(onCancel, modifier = m.heightIn(min = 56.dp),
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = OnPathColors.Ink)) {
            Text(str(Res.string.common_cancel), maxLines = 1, softWrap = false) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        go(Modifier.fillMaxWidth())
        if (stacked) { plan(Modifier.fillMaxWidth()); cancel(Modifier.fillMaxWidth()) }
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            cancel(Modifier.weight(1f)); plan(Modifier.weight(1f))
        }
    }
}

/** "Abbrechen" | primary, equal and side by side; stacked at a large font (M3, never ragged). */
@Composable
private fun OverviewButtons(primary: String, go: Boolean, onPrimary: () -> Unit, onCancel: () -> Unit) {
    val stacked = LocalDensity.current.fontScale >= DraftLook.STACK_FONT_SCALE
    val cancel = @Composable { m: Modifier ->
        OutlinedButton(onCancel, modifier = m.heightIn(min = 56.dp)) { Text(str(Res.string.common_cancel), maxLines = 1) }
    }
    val main = @Composable { m: Modifier ->
        Button(onPrimary, colors = if (go) ActionButtons.go() else ActionButtons.edit(), modifier = m.heightIn(min = 56.dp)) {
            Text(primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        main(Modifier.fillMaxWidth()); cancel(Modifier.fillMaxWidth())
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        cancel(Modifier.weight(1f)); main(Modifier.weight(1f))
    }
}
