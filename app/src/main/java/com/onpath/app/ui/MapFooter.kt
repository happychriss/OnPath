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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.compose.foundation.layout.Spacer
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
              /** The OnPath logo left of the scale (owner 2026-11-27, variant C – Plan only): a tap opens About OnPath; null = none. */
              onLogo: (() -> Unit)? = null,
              /** Navigate: the map buttons sit this much higher than the scale, closer to the thumb (owner 2026-11-27). */
              fabsLift: Dp = 0.dp,
              fabs: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onLogo != null) {
                    val label = str(Res.string.settings_page_about)
                    OnPathLogo(MAP_LOGO_DP.dp, Modifier.clickable(onClickLabel = label, onClick = onLogo)
                        .semantics { contentDescription = label })
                    Spacer(Modifier.width(6.dp))
                }
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
        Column(Modifier.padding(bottom = fabsLift), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End, content = fabs)
    }
}

/**
 * The credit on the map (docs/map-pipeline.md §3): the data (ODbL) and the tile schema (OpenMapTiles, CC-BY) – names,
 * the same in every language. Non-breaking spaces keep "© OpenStreetMap ·" together; a wrap goes before "OpenMapTiles".
 */
const val MAP_CREDIT = "©\u00A0OpenStreetMap\u00A0· OpenMapTiles"

/** The map footer's OnPath logo (owner 2026-11-27, variant C): small, beside the scale bar. */
const val MAP_LOGO_DP = 26

/** The credit line's largest font scale (the phone's own scale up to this; the rest of the screen takes it all). */
const val CREDIT_MAX_FONT = 1.3f

/** Size of one map control (owner 2026-11-03: "trifft man kaum" – 56 dp in both modes, was 48 in Plan). */
val MAP_FAB = MAP_FAB_DP.dp

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
data class CompassSpec(val headingUp: Boolean?, val bearing: Float, val onClick: () -> Unit) {
    companion object {
        /**
         * The bearing the button needs: in walking-direction-up it shows the arrow, not the needle, so the map's bearing
         * (new on every frame while the map turns with you) is not passed then – the controls do not recompose per frame.
         */
        fun bearingShown(headingUp: Boolean, bearing: Float): Float = if (headingUp) 0f else bearing
    }
}

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
    /** Navigate: the share button on top of the tour bubble (live sharing, [LocalTourShare]); null = not offered, ⚙ alone. */
    tourShare: (@Composable (Dp) -> Unit)? = LocalTourShare.current,
) {
    // Navigate: the column gives way to a short screen ([ControlsRoom]); Plan stays as it is
    val tier = if (onSettings != null) LocalControlsTier.current else ControlsTier.FULL
    val size = tier.slot
    // Navigate is green (Daumen-Karte, owner 2026-11-26: "alle Zeichen grün"), Plan blue
    val tint = if (plan) OnPathColors.PlanAccent else OnPathColors.NavAccent
    val onTint = if (plan) OnPathColors.Route else OnPathColors.NavAccent
    // the light sage over the map in both modes (owner 2026-11-12, [OnPathColors.MapGround]); Plan differs by its blue icons
    val bg = OnPathColors.MapGround
    // every control says its name on a long press (owner 2026-11-03, novice fix: "what does the eye do?")
    val tips = buildList<Pair<String, @Composable () -> Unit>> {
        val locate: Pair<String, @Composable () -> Unit> = locateTip(gps) to { LocateControl(gps, following, onLocate, size, tint, onTint = onTint, dot = PlanGps.showDot(plan), greyed = locateGreyed, label = locateLabel) }
        // Navigate (owner 2026-11-24): the "tour" bubble on top – share (only while sharing is offered) and ⚙ – over the map bubble
        if (onSettings != null) {
            if (tourShare != null) add(str(Res.string.live_share) to { tourShare(size) })
            add(str(Res.string.nav_settings_cd) to { IconControl(Icons.Outlined.Settings, Res.string.nav_settings_cd, onSettings, size, tint) })
        }
        if (compass != null) add(compassText(compass) to { CompassControl(compass, size, onTint, north = if (plan) OnPathColors.CompassNorth else OnPathColors.NavAccent) })
        // one order in both modes (owner 2026-11-26, Daumen-Karte): compass · locate · whole route · eye – the eye always at
        // the bottom; eye and whole route are always there, greyed when they cannot act (owner 2026-11-06)
        add(locate)
        add(str(Res.string.plan_show_whole_route) to { IconControl(Icons.Filled.FitScreen, Res.string.plan_show_whole_route,
            onFitRoute ?: {}, size, tint, enabled = onFitRoute != null) })
        add(str(Res.string.plan_hold_hide_route) to { EyeControl(onPeek, peeking, size, tint, enabled = showEye) })
    }
    val items = tips.mapIndexed { i, (text, control) -> @Composable { MapTip(text, i == tipShown) { control() } } }
    // Navigate: two bubbles with a gap, the tour's (share, ⚙) above the map's (compass, eye, ⛶, locate); on a short screen
    // 48 dp slots and a 6 dp gap, then the tour bubble side by side to the left of the map bubble's top ([ControlsTier])
    val tourCount = if (onSettings != null) (if (tourShare != null) 2 else 1) else 0
    val bubble = RoundedCornerShape(size / 2)
    val parts = listOf(items.take(tourCount), items.drop(tourCount)).filter { it.isNotEmpty() }
    val bubbles = parts.map { part ->
        @Composable {
            Surface(shape = bubble, color = bg, modifier = Modifier.floatShadow(bubble)) {
                if (tier == ControlsTier.SIDE && part.size == tourCount && tourCount > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    part.forEachIndexed { i, item ->
                        if (i > 0) androidx.compose.material3.VerticalDivider(Modifier.height(28.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        item()
                    }
                } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    part.forEachIndexed { i, item ->
                        if (i > 0) GroupDivider()
                        item()
                    }
                }
            }
        }
    }
    if (tier == ControlsTier.SIDE && bubbles.size == 2) Row(horizontalArrangement = Arrangement.spacedBy(tier.gap), verticalAlignment = Alignment.Top) {
        bubbles.forEach { it() }
    } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(tier.gap)) {
        bubbles.forEach { it() }
    }
}

private fun iconSize(size: Dp): Dp = 28.dp

/**
 * Plan's undo / redo in their own small pill (owner 2026-11-08, father's feedback: ↶ ↷ in the map column read as "turn
 * the map"): "↶ Rückgängig | ↷ Wiederholen" – sign and word together, in the plan blue like the column. Shown only while
 * there is something to undo or redo ([MapControlsEnabled.undoPill]); then both halves are there, the one that cannot act
 * greyed. It stands right above the map column, right-aligned with it ([PlanControlsLayer], owner 2026-11-08 evening:
 * not "mitten im Bild"); always one row – where the room is short (360 dp, a large font) "↷" goes without its word
 * first, then "↶" ([UndoPillShape]); TalkBack hears both names in every case.
 *
 * [inSheet] (owner 2026-11-18, answer 4: "Undo auch im vollen Punkte-Blatt"): the full points sheet hides the map
 * controls, so the same two buttons stand side by side at the right of the sheet's "Punkte" title ([PointsSection]) –
 * 48 dp (the title row's height, nothing grows), on the plan row's deeper sage like its pills, no shadow (it lies in the
 * sheet, not over the map). Same actions, same rule: only while one can act, the other greyed.
 */
@Composable
fun UndoPill(onUndo: (() -> Unit)?, onRedo: (() -> Unit)?, inSheet: Boolean = false) {
    if (onUndo == null && onRedo == null) return
    // owner 2026-11-08 late: the worded pill took too much room – two plain round buttons stacked under the map column,
    // same look as the column, only while something can be undone or redone (the one that cannot act is greyed)
    val size = if (inSheet) SHEET_UNDO else MAP_FAB
    val shape = RoundedCornerShape(size / 2)
    val buttons = @Composable {
        IconControl(Icons.AutoMirrored.Filled.Undo, Res.string.plan_undo, onUndo ?: {}, size,
            tint = OnPathColors.PlanAccent, enabled = onUndo != null)
        IconControl(Icons.AutoMirrored.Filled.Redo, Res.string.plan_redo, onRedo ?: {}, size,
            tint = OnPathColors.PlanAccent, enabled = onRedo != null)
    }
    if (inSheet) Surface(shape = shape, color = OnPathColors.PaperDeep) {
        Row(verticalAlignment = Alignment.CenterVertically) { buttons() }
    }
    else Surface(shape = shape, color = OnPathColors.PlanTint, modifier = Modifier.floatShadow(shape)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { buttons() }
    }
}

/** One undo / redo button in the full points sheet: the 48 dp touch target, the height of the "Punkte" title row. */
val SHEET_UNDO = 48.dp

/**
 * The undo pill's words for the room it has: both ("↶ Rückgängig | ↷ Wiederholen"), only Undo's ("↶ Rückgängig | ↷"),
 * or signs only – Undo keeps its word longest (the father read the signs alone as "turn the map").
 */
object UndoPillShape {
    enum class Words { BOTH, UNDO, NONE }

    fun words(undoWord: Int, undoSign: Int, redoWord: Int, redoSign: Int, line: Int, maxWidth: Int): Words = when {
        undoWord + line + redoWord <= maxWidth -> Words.BOTH
        undoWord + line + redoSign <= maxWidth -> Words.UNDO
        else -> Words.NONE
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
private fun CompassControl(c: CompassSpec, size: Dp, onTint: Color = OnPathColors.Route, north: Color = OnPathColors.CompassNorth) {
    val what = when {
        c.headingUp == true -> str(Res.string.plan_compass_heading)
        kotlin.math.abs(c.bearing) > 1f -> str(Res.string.plan_compass_turned)
        else -> str(Res.string.plan_compass_north)
    }
    Box(
        Modifier.size(size).clip(CircleShape).clickable(onClick = c.onClick).semantics { contentDescription = what },
        contentAlignment = Alignment.Center,
    ) {
        if (c.headingUp == true) Icon(Icons.Filled.Navigation, null, tint = onTint, modifier = Modifier.size(iconSize(size)))
        else NorthNeedle(Modifier.size(40.dp).rotate(-c.bearing), north)
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
                          /** The icon's colour while following: the route blue in Plan, green in Navigate. */
                          onTint: Color = OnPathColors.Route,
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
                following -> onTint
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
