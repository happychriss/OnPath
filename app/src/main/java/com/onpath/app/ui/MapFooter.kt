package com.onpath.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.SmallFloatingActionButton
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

/** Standard round map button (small FAB, 48dp = full touch target), surface coloured like Google Maps' controls. */
@Composable
fun MapFab(onClick: () -> Unit, modifier: Modifier = Modifier, contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
           content: @Composable () -> Unit) {
    SmallFloatingActionButton(
        onClick = onClick, modifier = modifier.size(MAP_FAB),
        shape = CircleShape, containerColor = MaterialTheme.colorScheme.surface, contentColor = contentColor,
        content = content,
    )
}

val MAP_FAB = 48.dp

fun gpsColor(status: GpsStatus): Color = when (status) {
    GpsStatus.GOOD -> OnPathColors.Good
    GpsStatus.WEAK -> OnPathColors.Weak
    GpsStatus.SEARCHING -> OnPathColors.Grey
    GpsStatus.OFF, GpsStatus.NO_PERMISSION -> OnPathColors.Bad
}

/**
 * Centre-on-me button with the GPS status as a small coloured dot (green good, amber weak,
 * grey searching, red off / not allowed). The caller decides what a tap does for each status.
 */
@Composable
fun LocateButton(status: GpsStatus, following: Boolean, onClick: () -> Unit) {
    val what = when (status) {
        GpsStatus.OFF -> "Location is off, tap to turn it on"
        GpsStatus.NO_PERMISSION -> "Location not allowed, tap to allow"
        else -> "Center on my position. GPS ${status.label}"
    }
    Box {
        MapFab(onClick, contentColor = if (following) OnPathColors.Route else MaterialTheme.colorScheme.onSurfaceVariant) {
            Icon(if (following) Icons.Filled.GpsFixed else Icons.Filled.GpsNotFixed, what)
        }
        // GPS status: a read-only dot on the button, not a separate control.
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 2.dp).size(12.dp)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp).background(gpsColor(status), CircleShape),
        )
    }
}

@Composable
private fun ScaleBar(label: String, width: Dp) {
    Column(Modifier.background(Color(0x99FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text(label, fontSize = 9.sp, lineHeight = 10.sp, color = Color(0xFF222222))
        Box(Modifier.width(width).height(2.dp).background(Color(0xFF222222)))
    }
}
