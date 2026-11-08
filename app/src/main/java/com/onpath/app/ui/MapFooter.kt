package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.PlainTooltip
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import org.jetbrains.compose.resources.StringResource
import androidx.compose.ui.unit.dp
import com.onpath.app.recording.GpsStatus

/**
 * Above the bottom sheet in both modes: small scale bar and attribution on the left, the map FABs
 * (locate; in Navigate also the orientation button above it) stacked on the right.
 */
@Composable
fun MapFooter(scale: Pair<String, Dp>?, onCredits: () -> Unit = {}, demo: Boolean = false,
              fabs: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                scale?.let { (label, width) -> ScaleBar(label, width) }
                // simulated GPS (Developer tools → Demo): said honestly, also in screenshots – beside the scale, so the
                // credit below keeps the whole width
                if (demo) Text("DEMO", style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1,
                    modifier = Modifier.padding(start = if (scale != null) 6.dp else 0.dp).clip(OnPathShapes.Small)
                        .background(OnPathColors.Warn).padding(horizontal = 5.dp, vertical = 3.dp))
            }
            // short credit on the map; a tap opens the full credits (Settings → About, terrain included). Map furniture:
            // it grows with the phone's font up to [CREDIT_MAX_FONT] only, so at the largest font it still reads
            // "© OpenStreetMap ·" / "OpenMapTiles" on two lines instead of cutting a name in half.
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, minOf(d.fontScale, CREDIT_MAX_FONT))) {
                Text(MAP_CREDIT, style = MaterialTheme.typography.labelSmall, color = Color(0xFF333333), maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp).clip(OnPathShapes.Small)
                        .clickable(onClickLabel = str(Res.string.plan_map_credits), onClick = onCredits)
                        .background(Color(0xB3FFFFFF)).padding(horizontal = 5.dp, vertical = 3.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End, content = fabs)
    }
}

/**
 * The credit on the map (docs/map-pipeline.md §3): the data (ODbL) and the tile schema (OpenMapTiles, CC-BY) – names,
 * the same in every language. Non-breaking spaces keep "© OpenStreetMap ·" together; a wrap goes before "OpenMapTiles".
 */
const val MAP_CREDIT = "©\u00A0OpenStreetMap\u00A0· OpenMapTiles"

/** The credit line's largest font scale (the phone's own scale up to this; the rest of the screen takes it all). */
const val CREDIT_MAX_FONT = 1.3f

/** Size of one map control (owner 2026-11-03: "trifft man kaum" – 56 dp in both modes, was 48 in Plan). */
val MAP_FAB = 56.dp

fun gpsColor(status: GpsStatus): Color = when (status) {
    GpsStatus.GOOD -> OnPathColors.Good
    GpsStatus.WEAK -> OnPathColors.Weak
    GpsStatus.SEARCHING -> OnPathColors.Grey
    GpsStatus.OFF, GpsStatus.NO_PERMISSION -> OnPathColors.Bad
}

@Composable
private fun ScaleBar(label: String, width: Dp) {
    Column(Modifier.background(Color(0x99FFFFFF), OnPathShapes.Small).padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF222222))
        Box(Modifier.width(width).height(2.dp).background(Color(0xFF222222)))
    }
}

/**
 * Compass / orientation button (both modes): [headingUp] true = the map turns with the walking direction (arrow);
 * otherwise the needle shows the map's [bearing]. Tap cycles turned → north up → walking direction up (AppViewModel.onCompass).
 * null is treated like false.
 */
data class CompassSpec(val headingUp: Boolean?, val bearing: Float, val onClick: () -> Unit)

/**
 * The map controls as one group (owner: "center, eye and compass grouped"):
 * compass on top (always), the eye in the middle (only when there is a route,
 * track or followed line – hold to hide them, position stays), "Show whole route" under it (same lines, see
 * [WholeRoute]; the browser planner's ⛶), locate at the bottom (always, GPS dot).
 * ONE column card with dividers in both modes (owner 2026-11-03, onpath.private#22 B – Navigate had separate round
 * buttons): Navigate ⚙ · compass · eye · ⛶ · locate (white). Plan (owner 2026-11-06, onpath.private#43 – the column
 * stands at one fixed place, [PlanControlsLayer], and only grows downwards, so no button changes its height): what is
 * always there first, compass · locate, then eye · ⛶ (with a route) – blue tint. Undo / redo are no longer in the column
 * (owner 2026-11-08, father's feedback: ↶ ↷ read as "turn the map"): their own pill with words, [UndoPill].
 */
@Composable
fun MapControls(
    compass: CompassSpec?,
    showEye: Boolean,
    onPeek: (Boolean) -> Unit,
    gps: GpsStatus,
    following: Boolean,
    onLocate: () -> Unit,
    peeking: Boolean = false,
    /** "Show whole route" (shown only when there is a line to show, [WholeRoute.shows]). */
    onFitRoute: (() -> Unit)? = null,
    /** Navigate: the navigation settings on the way – a gear on top of the controls. */
    onSettings: (() -> Unit)? = null,
    /** Plan is blue (owner 2026-11-03): light blue pill, blue icons ([OnPathColors.PlanTint] / [OnPathColors.PlanAccent]). */
    plan: Boolean = false,
    /** Plan without a position: locate looks greyed (still tappable – it follows the position when it comes). */
    locateGreyed: Boolean = false,
    /** Plan, my position off the map: the distance under the locate icon ([LocateDistance], owner 2026-11-07). */
    locateLabel: String? = null,
    /** Screenshot tests: the tooltip of this control (its index from the top) is open. */
    tipShown: Int = -1,
) {
    val size = MAP_FAB
    val tint = if (plan) OnPathColors.PlanAccent else MaterialTheme.colorScheme.onSurfaceVariant
    val bg = if (plan) OnPathColors.PlanTint else OnPathColors.Paper // Navigate: paper over the map (owner 2026-11-06)
    // every control says its name on a long press (owner 2026-11-03, novice fix: "what does the eye do?")
    val tips = buildList<Pair<String, @Composable () -> Unit>> {
        val locate: Pair<String, @Composable () -> Unit> = locateTip(gps) to { LocateControl(gps, following, onLocate, size, tint, dot = PlanGps.showDot(plan), greyed = locateGreyed, label = locateLabel) }
        if (onSettings != null) add(str(Res.string.nav_settings_cd) to { IconControl(Icons.Outlined.Settings, Res.string.nav_settings_cd, onSettings, size, tint) })
        if (compass != null) add(compassText(compass) to { CompassControl(compass, size) })
        if (plan) add(locate) // Plan: the always-there buttons first – the column grows only downwards
        // eye and whole route are always there (owner 2026-11-06: "ausgrauen statt ausblenden – dann springt nichts und
        // man sieht, was man tun könnte"): grey without a route, the long press still says what they do
        add(str(Res.string.plan_hold_hide_route) to { EyeControl(onPeek, peeking, size, tint, enabled = showEye) })
        add(str(Res.string.plan_show_whole_route) to { IconControl(Icons.Filled.FitScreen, Res.string.plan_show_whole_route,
            onFitRoute ?: {}, size, tint, enabled = onFitRoute != null) })
        if (!plan) add(locate) // Navigate: locate at the bottom, as before
    }
    val items = tips.mapIndexed { i, (text, control) -> @Composable { MapTip(text, i == tipShown) { control() } } }
    Surface(shape = RoundedCornerShape(MAP_FAB / 2), color = bg, modifier = Modifier.warmShadow(OnPathShadow.LOW, RoundedCornerShape(MAP_FAB / 2))) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            items.forEachIndexed { i, item ->
                if (i > 0) GroupDivider()
                item()
            }
        }
    }
}

private fun iconSize(size: Dp): Dp = 28.dp

/**
 * Plan's undo / redo in their own small pill (owner 2026-11-08, father's feedback: ↶ ↷ in the map column read as "turn
 * the map"): "↶ Rückgängig | ↷ Wiederholen" – sign and word together, in the plan blue like the column. Shown only while
 * there is something to undo or redo ([MapControlsEnabled.undoPill]); then both halves are there, the one that cannot act
 * greyed. It stands under the map column ([PlanControlsLayer] places it), so the column itself never moves.
 */
@Composable
fun UndoPill(onUndo: (() -> Unit)?, onRedo: (() -> Unit)?) {
    if (onUndo == null && onRedo == null) return
    val shape = RoundedCornerShape(MAP_FAB / 2)
    Surface(shape = shape, color = OnPathColors.PlanTint, modifier = Modifier.warmShadow(OnPathShadow.LOW, shape)) {
        // side by side when the room allows it, else one above the other (a 360 dp phone, a large font) – never cut
        androidx.compose.ui.layout.Layout(content = {
            UndoHalf(Icons.AutoMirrored.Filled.Undo, str(Res.string.plan_undo), onUndo)
            Box(Modifier.background(MaterialTheme.colorScheme.outlineVariant))
            UndoHalf(Icons.AutoMirrored.Filled.Redo, str(Res.string.plan_redo), onRedo)
        }) { m, c ->
            val loose = androidx.compose.ui.unit.Constraints(maxHeight = c.maxHeight)
            val undo = m[0].measure(loose)
            val redo = m[2].measure(loose)
            val line = 1.dp.roundToPx()
            val inset = 12.dp.roundToPx()
            if (UndoPillShape.sideBySide(undo.width, redo.width, line, c.maxWidth)) {
                val h = maxOf(undo.height, redo.height)
                val div = m[1].measure(androidx.compose.ui.unit.Constraints.fixed(line, (h - 2 * inset).coerceAtLeast(0)))
                layout(undo.width + line + redo.width, h) {
                    undo.place(0, (h - undo.height) / 2)
                    div.place(undo.width, inset)
                    redo.place(undo.width + line, (h - redo.height) / 2)
                }
            } else {
                val w = maxOf(undo.width, redo.width)
                val div = m[1].measure(androidx.compose.ui.unit.Constraints.fixed((w - 2 * inset).coerceAtLeast(0), line))
                layout(w, undo.height + line + redo.height) {
                    undo.place(0, 0)
                    div.place(inset, undo.height)
                    redo.place(0, undo.height + line)
                }
            }
        }
    }
}

/** The undo pill's shape: its two halves side by side when they fit [maxWidth], else one above the other. */
object UndoPillShape {
    fun sideBySide(undoWidth: Int, redoWidth: Int, line: Int, maxWidth: Int): Boolean = undoWidth + line + redoWidth <= maxWidth
}

@Composable
private fun UndoHalf(icon: ImageVector, label: String, onClick: (() -> Unit)?) {
    val tint = MapControlState.tint(OnPathColors.PlanAccent, onClick != null)
    Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(MAP_FAB / 2))
        .clickable(enabled = onClick != null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick ?: {})
        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * A map control's name on a long press (Material 3 plain tooltip, owner 2026-11-03): the signs alone (eye, ⛶, ↶ ↷)
 * do not explain themselves to a first-time user. A tap still does what it did; the eye's hold still hides the route.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun MapTip(text: String, initiallyVisible: Boolean = false, content: @Composable () -> Unit) {
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text, style = MaterialTheme.typography.bodyMedium) } },
        state = androidx.compose.material3.rememberTooltipState(initialIsVisible = initiallyVisible, isPersistent = initiallyVisible),
    ) { content() }
}

/** The compass's name: what it shows now and what a tap does (the same words TalkBack hears). */
@Composable
private fun compassText(c: CompassSpec): String = when {
    c.headingUp == true -> str(Res.string.plan_compass_heading)
    kotlin.math.abs(c.bearing) > 1f -> str(Res.string.plan_compass_turned)
    else -> str(Res.string.plan_compass_north)
}

/** The locate control's name: "Mein Standort" – or how to switch location on / allow it. */
@Composable
private fun locateTip(status: GpsStatus): String = when (status) {
    GpsStatus.OFF -> str(Res.string.plan_location_off_tap)
    GpsStatus.NO_PERMISSION -> str(Res.string.plan_location_denied_tap)
    else -> str(Res.string.map_tip_locate)
}

/** A plain round map control: [icon] in the control colour, [desc] for TalkBack. */
@Composable
private fun IconControl(icon: ImageVector, desc: StringResource, onClick: () -> Unit, size: Dp,
                        tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, enabled: Boolean = true) {
    Box(Modifier.size(size).clip(CircleShape).clickable(enabled = enabled, onClick = onClick)
        .semantics { contentDescription = Txt.s(desc); if (!enabled) disabled() },
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = MapControlState.tint(tint, enabled), modifier = Modifier.size(iconSize(size)))
    }
}

/** A map control that is there but cannot act now (no route yet): its sign greyed (owner 2026-11-06). */
object MapControlState {
    const val DISABLED_ALPHA = 0.38f
    fun tint(tint: Color, enabled: Boolean): Color = if (enabled) tint else tint.copy(alpha = DISABLED_ALPHA)
}

@Composable
private fun GroupDivider() =
    HorizontalDivider(Modifier.width(28.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun CompassControl(c: CompassSpec, size: Dp) {
    val what = when {
        c.headingUp == true -> str(Res.string.plan_compass_heading)
        kotlin.math.abs(c.bearing) > 1f -> str(Res.string.plan_compass_turned)
        else -> str(Res.string.plan_compass_north)
    }
    Box(
        Modifier.size(size).clip(CircleShape).clickable(onClick = c.onClick).semantics { contentDescription = what },
        contentAlignment = Alignment.Center,
    ) {
        if (c.headingUp == true) Icon(Icons.Filled.Navigation, null, tint = OnPathColors.Route, modifier = Modifier.size(iconSize(size)))
        else NorthNeedle(Modifier.size(40.dp).rotate(-c.bearing))
    }
}

/** Hold to hide route lines and markers; release shows them again. */
@Composable
private fun EyeControl(onPeek: (Boolean) -> Unit, peekingPreview: Boolean, size: Dp,
                       tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, enabled: Boolean = true) {
    val peek by rememberUpdatedState(onPeek)
    var held by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (held) peek(false) } }
    val down = held || peekingPreview
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (down) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .semantics { contentDescription = Txt.s(Res.string.plan_hold_hide_route); if (!enabled) disabled() }
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onPress = {
                    held = true; peek(true)
                    try { tryAwaitRelease() } finally { held = false; peek(false) }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (down) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null,
            tint = MapControlState.tint(tint, enabled), modifier = Modifier.size(iconSize(size)))
    }
}

@Composable
private fun LocateControl(status: GpsStatus, following: Boolean, onClick: () -> Unit, size: Dp,
                          tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
                          /** The GPS colour dot – Navigate only; Plan is quiet about GPS (owner 2026-11-06 23:35, [PlanGps]). */
                          dot: Boolean = true, greyed: Boolean = false,
                          /** The distance to me while I am off the map ([LocateDistance]); the icon steps up to make room. */
                          label: String? = null) {
    val what = when (status) {
        GpsStatus.OFF -> str(Res.string.plan_location_off_tap)
        GpsStatus.NO_PERMISSION -> str(Res.string.plan_location_denied_tap)
        else -> str(Res.string.plan_centre_on_me, status.label)
    }
    val said = label?.let { str(Res.string.plan_locate_far_cd, it) } ?: what
    Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = said },
        contentAlignment = Alignment.Center) {
        Icon(if (following) Icons.Filled.GpsFixed else Icons.Filled.GpsNotFixed, null,
            tint = when {
                greyed -> MapControlState.tint(tint, enabled = false)
                following -> OnPathColors.Route
                else -> tint
            },
            modifier = Modifier.size(iconSize(size) * (if (label != null) 0.8f else 1f))
                .offset(y = if (label != null) (-7).dp else 0.dp))
        label?.let { l ->
            Text(l, color = OnPathColors.Walnut, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp))
        }
        if (dot) Box(
            Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 6.dp).size(12.dp)
                .border(2.dp, OnPathColors.CardWhite, CircleShape).padding(2.dp).background(gpsColor(status), CircleShape),
        )
    }
}
