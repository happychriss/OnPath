package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
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
            scale?.let { (label, width) -> ScaleBar(label, width) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // short credit on the map; a tap opens the full credits (Settings → About)
                Text("© OpenStreetMap", style = MaterialTheme.typography.labelSmall, color = Color(0xFF333333), maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(4.dp))
                        .clickable(onClickLabel = str(Res.string.plan_map_credits), onClick = onCredits)
                        .background(Color(0xB3FFFFFF)).padding(horizontal = 5.dp, vertical = 3.dp))
                // simulated GPS (Developer tools → Demo): said honestly, also in screenshots
                if (demo) Text("DEMO", style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp).clip(RoundedCornerShape(4.dp))
                        .background(OnPathColors.Warn).padding(horizontal = 5.dp, vertical = 3.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End, content = fabs)
    }
}

/** Size of one map control (48 dp = full touch target). */
val MAP_FAB = 48.dp
/** Navigate (owner): used while walking, so bigger and a little apart from each other. */
val MAP_FAB_LARGE = 56.dp
private val LARGE_GAP = 8.dp

fun gpsColor(status: GpsStatus): Color = when (status) {
    GpsStatus.GOOD -> OnPathColors.Good
    GpsStatus.WEAK -> OnPathColors.Weak
    GpsStatus.SEARCHING -> OnPathColors.Grey
    GpsStatus.OFF, GpsStatus.NO_PERMISSION -> OnPathColors.Bad
}

@Composable
private fun ScaleBar(label: String, width: Dp) {
    Column(Modifier.background(Color(0x99FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
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
 * track or followed line – hold to hide them, position stays), locate at the bottom (always, GPS dot).
 * [large] (Navigate): each control its own bigger round button with a small gap, so the wrong one is not hit
 * while walking; Plan keeps the compact pill.
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
    /** Plan: undo the last edit (shown only when there is something to undo). */
    onUndo: (() -> Unit)? = null,
    /** Plan: redo the last undone edit (shown only when there is something to redo). */
    onRedo: (() -> Unit)? = null,
    large: Boolean = false,
    /** Navigate: the navigation settings on the way – a gear on top of the controls. */
    onSettings: (() -> Unit)? = null,
) {
    val size = if (large) MAP_FAB_LARGE else MAP_FAB
    val items = buildList<@Composable () -> Unit> {
        if (onSettings != null) add { IconControl(Icons.Outlined.Settings, Res.string.nav_settings_cd, onSettings, size) }
        if (compass != null) add { CompassControl(compass, size) }
        if (showEye) add { EyeControl(onPeek, peeking, size) }
        if (onUndo != null) add { IconControl(Icons.AutoMirrored.Filled.Undo, Res.string.plan_undo, onUndo, size) }
        if (onRedo != null) add { IconControl(Icons.AutoMirrored.Filled.Redo, Res.string.plan_redo, onRedo, size) }
        add { LocateControl(gps, following, onLocate, size) }
    }
    if (large) {
        Column(verticalArrangement = Arrangement.spacedBy(LARGE_GAP), horizontalAlignment = Alignment.CenterHorizontally) {
            items.forEach { item ->
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) { item() }
            }
        }
        return
    }
    Surface(shape = RoundedCornerShape(MAP_FAB / 2), color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            items.forEachIndexed { i, item ->
                if (i > 0) GroupDivider()
                item()
            }
        }
    }
}

private fun iconSize(size: Dp): Dp = if (size > MAP_FAB) 30.dp else 24.dp

/** A plain round map control: [icon] in the control colour, [desc] for TalkBack. */
@Composable
private fun IconControl(icon: ImageVector, desc: StringResource, onClick: () -> Unit, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = Txt.s(desc) },
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(iconSize(size)))
    }
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
        else NorthNeedle(Modifier.size(if (size > MAP_FAB) 42.dp else 34.dp).rotate(-c.bearing))
    }
}

/** Hold to hide route lines and markers; release shows them again. */
@Composable
private fun EyeControl(onPeek: (Boolean) -> Unit, peekingPreview: Boolean, size: Dp) {
    val peek by rememberUpdatedState(onPeek)
    var held by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (held) peek(false) } }
    val down = held || peekingPreview
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (down) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .semantics { contentDescription = Txt.s(Res.string.plan_hold_hide_route) }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    held = true; peek(true)
                    try { tryAwaitRelease() } finally { held = false; peek(false) }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (down) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(iconSize(size)))
    }
}

@Composable
private fun LocateControl(status: GpsStatus, following: Boolean, onClick: () -> Unit, size: Dp) {
    val what = when (status) {
        GpsStatus.OFF -> str(Res.string.plan_location_off_tap)
        GpsStatus.NO_PERMISSION -> str(Res.string.plan_location_denied_tap)
        else -> str(Res.string.plan_centre_on_me, status.label)
    }
    Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = what },
        contentAlignment = Alignment.Center) {
        Icon(if (following) Icons.Filled.GpsFixed else Icons.Filled.GpsNotFixed, null,
            tint = if (following) OnPathColors.Route else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize(size)))
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 6.dp).size(12.dp)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp).background(gpsColor(status), CircleShape),
        )
    }
}
