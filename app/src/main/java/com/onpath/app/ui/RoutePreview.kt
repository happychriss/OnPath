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
import com.onpath.app.domain.RouteShape
import com.onpath.app.routing.GeoPoint
import com.onpath.strings.*

/**
 * The overview's map picture and where the route's start and goal lie on it (pixels): the overview draws the start dot
 * and the goal flag over it, the same signs as over the drawn shape.
 */
class OverviewPicture(val image: ImageBitmap, val start: Offset, val goal: Offset)

/**
 * Makes the overview's map picture for (the tour, segments, colour, width px, height px, the followed plan drawn thin under
 * them – empty = none) – the stored one when it fits (AppViewModel.overviewShot); null = none (the shape stays).
 */
typealias OverviewSnapshot = suspend (Tour, List<List<GeoPoint>>, RouteShape.Tint, Int, Int, List<GeoPoint>) -> OverviewPicture?

/**
 * The lines of My routes' rows, kept while the app runs (scrolling back must not read the database again). Keyed by the
 * tour and what changes its line ([key]); the plan on the map has its own changing key (points, distance).
 */
internal object RouteLineCache {
    private val lru = android.util.LruCache<String, List<List<GeoPoint>>>(64)
    fun key(t: Tour) = com.onpath.app.ui.routes.RouteLineKey.of(t)
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
                              onEnlarge: (() -> Unit)? = null,
                              /** The tile's edge: [PREVIEW_TILE], or the L1 row's map tile when there is no map picture. */
                              tile: androidx.compose.ui.unit.Dp = PREVIEW_TILE) {
    val label = str(if (onEnlarge != null) Res.string.routes_overview_enlarge else Res.string.routes_overview_show)
    // the badge sits on the tile's corner, a little outside it: the outer box is that much larger
    Box(modifier.size(tile + (if (onEnlarge != null || tile == PREVIEW_TILE) ENLARGE_OUT else 0.dp)).let { m ->
            if (onEnlarge != null) m.clickable(onClickLabel = label, role = Role.Button) { onEnlarge() } else m
        }.semantics { contentDescription = label }) {
        Box(Modifier.size(tile).clip(OnPathShapes.Small).background(OnPathColors.PaperDim), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val px = with(density) { tile.toPx() }
            // with the ⤢ badge: nothing is drawn under it (the line, start or goal fitted beside / above its corner)
            val corner = if (onEnlarge == null) 0f
                else with(density) { RouteShape.cornerFor((ENLARGE_BADGE - ENLARGE_OUT).toPx(), (3.dp * 1.6f).toPx()) }
            val shape = remember(segments, px, corner) { segments?.let { RouteShape.of(it, px, px, with(density) { 8.dp.toPx() }, corner = corner) } }
            if (shape == null) placeholder()
            else RouteCanvas(shape, tint.color().copy(alpha = if (pale) 0.45f else 1f), 2.5.dp, 3.dp, Modifier.size(tile))
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
    Canvas(modifier) {
        // a recorded tour's followed plan: thin, plan blue, faint, under the track (owner 2026-11-07)
        shape.under?.let { u -> drawLine(u, OnPathColors.ActionEdit.copy(alpha = com.onpath.app.map.RouteLook.PLAN_UNDER_OPACITY),
            com.onpath.app.map.RouteLook.PLAN_UNDER_DP.dp.toPx()) }
        drawShape(shape, color, width.toPx(), dot.toPx())
    }
}

private fun DrawScope.drawLine(l: FloatArray, color: Color, width: Float) {
    if (l.size < 4) return
    val path = Path().apply {
        moveTo(l[0], l[1])
        var i = 2
        while (i < l.size) { lineTo(l[i], l[i + 1]); i += 2 }
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
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
    /**
     * A recorded tour's followed plan, drawn thin in plan blue under the track (owner 2026-11-07); empty = none, null =
     * still being read (the map picture waits for it – one picture, not one without and one with the plan).
     */
    planLine: List<GeoPoint>? = emptyList(),
    /** „3 von 27“ – where this tour is in the list swiped through (owner 2026-11-15); null = no pill (a list of one). */
    position: String? = null,
    /**
     * The page dots under the picture (owner 2026-11-19: so one sees the overview can be swiped): this page (0-based) of
     * [pageCount]; none for a list of one.
     */
    page: Int = 0, pageCount: Int = 1,
    /**
     * A done tour's look back (owner 2026-11-15, round 3 of the tour analysis – [DoneTourOverview]); null = still read or
     * not known: the recorded tour's overview shows its stored numbers only. Ignored for planned / imported routes.
     */
    review: com.onpath.app.time.TourReview? = null,
    /** A done tour's share → "GPX exportieren" (the recorded way); null = no share icon. */
    onExportTrack: (() -> Unit)? = null,
    /** A done tour's share → "Plan exportieren" – only when it followed a plan; null = not offered. */
    onExportPlan: (() -> Unit)? = null,
) {
    val picture = @Composable { m: Modifier -> OverviewMapPicture(t, segments, snapshot, planLine, position, m) }
    val foot = @Composable {
        if (onGo != null) OverviewFoot(onPlan = onPrimary, onGo = onGo, onCancel = onCancel)
        else OverviewButtons(primary = str(if (touring) Res.string.routes_overview_follow else Res.string.routes_overview_plan),
            go = touring, onPrimary = onPrimary, onCancel = onCancel)
    }
    val dots = if (pageCount > 1) (@Composable { PageDots(page, pageCount, Modifier.fillMaxWidth()) }) else null
    Surface(Modifier.fillMaxSize(), color = OnPathColors.Paper) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
            // a done tour (owner 2026-11-15, round 3): its look back – the big values, the picture with the profile, the
            // highlights, „Mehr“ and share
            if (t.kind == com.onpath.app.data.TourKind.RECORDED) {
                DoneTourOverview(t, review, picture, foot, onExportTrack, onExportPlan, dots)
                return@Column
            }
            Text(t.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // a value never breaks inside ("6 h 06 min" whole) – the line wraps only between the values
            Text(instrument(listOf(Format.distance(t.distanceM), "↑${Format.meters(t.ascentM)}", Format.duration(t.durationS))
                .joinToString(" · ") { it.replace(' ', ' ') },
                MaterialTheme.typography.bodyLarge, MaterialTheme.colorScheme.onSurfaceVariant),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            picture(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp, bottom = if (dots != null) 8.dp else 12.dp).clip(OnPathShapes.Card))
            dots?.let { Box(Modifier.padding(bottom = 10.dp)) { it() } }
            // „Mehr ›“ (owner 2026-11-19): what the facts line does not say – the descent, the activity
            if (More.of(t, null) != null) Box(Modifier.padding(bottom = 12.dp)) { MoreEntry(t, null) }
            foot()
        }
    }
}

/**
 * The overview's map picture: the stored / snapshotted map with the route (the drawn shape at once while it is made, and
 * instead of it when there is none), start dot and goal flag, the followed plan thin under a done tour, the „3 von 27“ pill.
 */
@Composable
private fun OverviewMapPicture(t: Tour, segments: List<List<GeoPoint>>?, snapshot: OverviewSnapshot?, planLine: List<GeoPoint>?,
                               position: String?, modifier: Modifier) {
    val tint = RouteShape.tint(t.kind)
    BoxWithConstraints(modifier.background(OnPathColors.PaperDim), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.roundToPx() }; val hPx = with(density) { maxHeight.roundToPx() }
        val under = planLine.orEmpty()
        var image by remember(segments, under) { mutableStateOf<OverviewPicture?>(null) }
        var loading by remember(segments, under) { mutableStateOf(snapshot != null) }
        if (segments != null && snapshot != null && planLine != null) LaunchedEffect(segments, under, wPx, hPx) {
            image = snapshot(t, segments, tint, wPx, hPx, under); loading = false
        }
        val shape = remember(segments, under, wPx, hPx) {
            segments?.let { RouteShape.of(it, wPx.toFloat(), hPx.toFloat(), with(density) { FitPaddingRule.SIDE_DP.dp.toPx() },
                under = under) }
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
        // „3 von 27“ (owner 2026-11-15, the swipe sketch): a small pill top right over the picture
        position?.let { PositionPill(it, Modifier.align(Alignment.TopEnd).padding(10.dp)) }
    }
}

/**
 * The page dots of the swiped overview (owner 2026-11-19, "damit man sieht, dass man wischen kann"): small dots centred,
 * the shown page filled green, the others light; at most [DotWindow.MAX] at a time – a longer list shows a window around the
 * shown page, its edge dots smaller where more pages lie beyond (the „3 von 27“ pill says the number). TalkBack reads
 * „Seite 3 von 27“.
 */
@Composable
internal fun PageDots(page: Int, count: Int, modifier: Modifier = Modifier) {
    val desc = str(Res.string.routes_overview_page_dots, page + 1, count)
    Row(modifier.heightIn(min = 12.dp).semantics { contentDescription = desc },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        DotWindow.window(page, count).forEach { i ->
            val small = DotWindow.small(i, page, count)
            Box(Modifier.padding(horizontal = 4.dp).size(if (small) 5.dp else 8.dp).clip(CircleShape)
                .background(if (i == page) OnPathColors.Green else OnPathColors.InkMuted.copy(alpha = 0.35f)))
        }
    }
}

/** Which dots [PageDots] shows – pure, so a test can say it. */
internal object DotWindow {
    /** At most this many dots at a time. */
    const val MAX = 7

    /** The pages that get a dot: all up to [MAX], else [MAX] around [page] (kept inside the list). */
    fun window(page: Int, count: Int): IntRange {
        if (count <= MAX) return 0 until count
        val start = (page - MAX / 2).coerceIn(0, count - MAX)
        return start until start + MAX
    }

    /** A window's edge dot drawn smaller: more pages lie beyond it (never the shown page). */
    fun small(i: Int, page: Int, count: Int): Boolean {
        if (i == page) return false
        val w = window(page, count)
        return (i == w.first && w.first > 0) || (i == w.last && w.last < count - 1)
    }
}

/** Where the shown tour is in the list swiped through („3 von 27“): paper pill, small text, over the picture. */
@Composable
internal fun PositionPill(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
        modifier = modifier.clip(CircleShape).background(OnPathColors.Paper.copy(alpha = 0.92f))
            .padding(horizontal = 10.dp, vertical = 4.dp))
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
 * The overview's foot for a planned route (owner 2026-11-07): "▶ Los geht's" full width plan blue (2026-11-12) – the plan sheet's foot –
 * and under it Abbrechen (text) | Planen (tonal blue), equal halves; at a large font all three full width, one under the
 * other (Los geht's, Planen, Abbrechen – M3, never ragged).
 */
@Composable
private fun OverviewFoot(onPlan: () -> Unit, onGo: () -> Unit, onCancel: () -> Unit) {
    val stacked = LocalDensity.current.fontScale >= DraftLook.STACK_FONT_SCALE
    val go = @Composable { m: Modifier ->
        Button(onGo, colors = ActionButtons.planGo(), modifier = m.heightIn(min = 56.dp)) {
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
        // "Folgen" during a tour and "Planen": both plan blue (owner 2026-11-12: every big go button reached from Plan)
        Button(onPrimary, colors = if (go) ActionButtons.planGo() else ActionButtons.edit(), modifier = m.heightIn(min = 56.dp)) {
            Text(primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        main(Modifier.fillMaxWidth()); cancel(Modifier.fillMaxWidth())
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        cancel(Modifier.weight(1f)); main(Modifier.weight(1f))
    }
}
