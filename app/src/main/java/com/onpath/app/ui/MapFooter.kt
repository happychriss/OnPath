package com.onpath.app.ui

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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.recording.GpsStatus

/**
 * Above the bottom sheet in both modes: small scale bar and attribution on the left, the map FABs
 * (locate; in Navigate also the orientation button above it) stacked on the right.
 */
@Composable
fun MapFooter(scale: Pair<String, Dp>?, onCredits: () -> Unit = {}, fabs: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            scale?.let { (label, width) -> ScaleBar(label, width) }
            // short credit on the map (owner 2026-10-22); a tap opens the full credits (Settings → About)
            Text("© OpenStreetMap", fontSize = 10.sp, lineHeight = 12.sp, color = Color(0xFF333333), maxLines = 1,
                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(4.dp))
                    .clickable(onClickLabel = "Show map credits", onClick = onCredits)
                    .background(Color(0xB3FFFFFF)).padding(horizontal = 5.dp, vertical = 3.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End, content = fabs)
    }
}

/** Size of one map control (48 dp = full touch target). */
val MAP_FAB = 48.dp
/** Navigate (owner, 2026-10-20): used while walking, so bigger and a little apart from each other. */
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
        Text(label, fontSize = 9.sp, lineHeight = 10.sp, color = Color(0xFF222222))
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
    /** Plan: redo the last undone edit – shown only when there is something to redo (owner Q8 2026-10-23). */
    onRedo: (() -> Unit)? = null,
    large: Boolean = false,
) {
    val size = if (large) MAP_FAB_LARGE else MAP_FAB
    val items = buildList<@Composable () -> Unit> {
        if (compass != null) add { CompassControl(compass, size) }
        if (showEye) add { EyeControl(onPeek, peeking, size) }
        if (onUndo != null) add {
            Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onUndo).semantics { contentDescription = "Undo" },
                contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.Undo, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(iconSize(size)))
            }
        }
        if (onRedo != null) add {
            Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onRedo).semantics { contentDescription = "Redo" },
                contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.Redo, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(iconSize(size)))
            }
        }
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

@Composable
private fun GroupDivider() =
    HorizontalDivider(Modifier.width(28.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun CompassControl(c: CompassSpec, size: Dp) {
    val what = when {
        c.headingUp == true -> "Map turns with your direction of travel. Tap for north up"
        kotlin.math.abs(c.bearing) > 1f -> "Map is turned. Tap for north up"
        else -> "Map is north up. Tap to turn it with your direction of travel"
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
            .semantics { contentDescription = "Hold to hide the route" }
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
        GpsStatus.OFF -> "Location is off, tap to turn it on"
        GpsStatus.NO_PERMISSION -> "Location not allowed, tap to allow"
        else -> "Centre on my position. ${status.label}"
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
