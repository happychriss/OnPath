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
import androidx.compose.material3.FloatingActionButton
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

/** Above the bottom panel in both modes: scale bar and attribution on the left, locate button right. */
@Composable
fun MapFooter(scale: Pair<String, Dp>?, locate: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            scale?.let { (label, width) -> ScaleBar(label, width) }
            Text("© OpenStreetMap contributors, © OpenMapTiles, Terrarium (Mapzen/AWS)", fontSize = 10.sp, color = Color(0xFF333333),
                modifier = Modifier.padding(top = 4.dp).background(Color(0xB3FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
        }
        locate()
    }
}

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
    Box {
        FloatingActionButton(
            onClick = onClick,
            containerColor = Color.White,
            contentColor = if (following) OnPathColors.Route else Color(0xFF333333),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.size(60.dp),
        ) {
            val what = when (status) {
                GpsStatus.OFF -> "Location is off, tap to turn it on"
                GpsStatus.NO_PERMISSION -> "Location not allowed, tap to allow"
                else -> "Center on my position. GPS ${status.label}"
            }
            Icon(if (following) Icons.Filled.GpsFixed else Icons.Filled.GpsNotFixed, what)
        }
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = (-7).dp, y = 7.dp).size(13.dp)
                .border(2.dp, Color.White, CircleShape).padding(2.dp).background(gpsColor(status), CircleShape),
        )
    }
}

@Composable
private fun ScaleBar(label: String, width: Dp) {
    Column(Modifier.background(Color(0xB3FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text(label, fontSize = 10.sp, color = Color(0xFF222222))
        Box(Modifier.width(width).height(3.dp).background(Color(0xFF222222)))
    }
}
