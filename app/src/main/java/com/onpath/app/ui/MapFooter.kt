package com.onpath.app.ui

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
fun MapFooter(scale: Pair<String, Dp>?, fabs: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            scale?.let { (label, width) -> ScaleBar(label, width) }
            Text("© OpenStreetMap contributors, © OpenMapTiles, Terrarium (Mapzen/AWS)", fontSize = 9.sp, lineHeight = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                modifier = Modifier.padding(top = 4.dp).background(Color(0x99FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End, content = fabs)
    }
}

/** Size of one map control (48 dp = full touch target). */
val MAP_FAB = 48.dp

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
 * Compass / orientation button content. [headingUp] true/false = Navigate (walking direction up / north
 * up, tap toggles); null = Plan, always shown (needle turned by the map [bearing], tap = north up).
 */
data class CompassSpec(val headingUp: Boolean?, val bearing: Float, val onClick: () -> Unit)

/**
 * The map controls as one group (owner: "center, eye and compass grouped"):
 * compass on top (always), the eye in the middle (only when there is a route,
 * track or followed line – hold to hide them, position stays), locate at the bottom (always, GPS dot).
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
) {
    Surface(shape = RoundedCornerShape(MAP_FAB / 2), color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (compass != null) {
                CompassControl(compass)
                GroupDivider()
            }
            if (showEye) {
                EyeControl(onPeek, peeking)
                GroupDivider()
            }
            LocateControl(gps, following, onLocate)
        }
    }
}

@Composable
private fun GroupDivider() =
    HorizontalDivider(Modifier.width(28.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun CompassControl(c: CompassSpec) {
    val what = when (c.headingUp) {
        true -> "Map turns with your walking direction. Tap for north up"
        false -> "Map is north up. Tap to turn it with your walking direction"
        null -> if (kotlin.math.abs(c.bearing) > 1f) "Map is turned. Tap for north up" else "Compass. Map is north up"
    }
    Box(
        Modifier.size(MAP_FAB).clip(CircleShape).clickable(onClick = c.onClick).semantics { contentDescription = what },
        contentAlignment = Alignment.Center,
    ) {
        if (c.headingUp == true) Icon(Icons.Filled.Navigation, null, tint = OnPathColors.Route, modifier = Modifier.size(24.dp))
        else NorthNeedle(Modifier.size(34.dp).rotate(-c.bearing))
    }
}

/** Hold to hide route lines and markers; release shows them again. */
@Composable
private fun EyeControl(onPeek: (Boolean) -> Unit, peekingPreview: Boolean) {
    val peek by rememberUpdatedState(onPeek)
    var held by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (held) peek(false) } }
    val down = held || peekingPreview
    Box(
        Modifier.size(MAP_FAB).clip(CircleShape)
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun LocateControl(status: GpsStatus, following: Boolean, onClick: () -> Unit) {
    val what = when (status) {
        GpsStatus.OFF -> "Location is off, tap to turn it on"
        GpsStatus.NO_PERMISSION -> "Location not allowed, tap to allow"
        else -> "Centre on my position. GPS ${status.label}"
    }
    Box(Modifier.size(MAP_FAB).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = what },
        contentAlignment = Alignment.Center) {
        Icon(if (following) Icons.Filled.GpsFixed else Icons.Filled.GpsNotFixed, null,
            tint = if (following) OnPathColors.Route else MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 6.dp).size(12.dp)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp).background(gpsColor(status), CircleShape),
        )
    }
}
