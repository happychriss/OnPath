package com.onpath.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.data.Format
import com.onpath.app.recording.GpsStatus
import com.onpath.app.recording.RecState
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute

// ------------------------------------------------------------------ shared pieces

/** Rounded bottom sheet look used for every bottom panel. */
@Composable
fun BottomPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp)) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp)
                    .clip(CircleShape).background(Color(0x22000000))
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** Icon + label button, at least 56dp tall (glove friendly). */
@Composable
fun ActionButton(
    icon: ImageVector, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 60.dp).widthIn(min = 56.dp),
        shape = RoundedCornerShape(14.dp), contentPadding = ButtonDefaults.TextButtonContentPadding) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(24.dp))
            Text(label, fontSize = 12.sp, maxLines = 1, color = if (enabled) tint else tint.copy(alpha = 0.35f))
        }
    }
}

@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier, big: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = if (big) 28.sp else 18.sp, maxLines = 1)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatsRow(distance: Double, ascent: Double, descent: Double, durationS: Double) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat(Format.distance(distance), "Distance", Modifier.weight(1f))
        Stat("↑ " + Format.meters(ascent), "Ascent", Modifier.weight(1f))
        Stat("↓ " + Format.meters(descent), "Descent", Modifier.weight(1f))
        Stat(Format.duration(durationS), "Time", Modifier.weight(1f))
    }
}

// ------------------------------------------------------------------ top bar

@Composable
fun GpsChip(status: GpsStatus, detail: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = when (status) {
        GpsStatus.GOOD -> OnPathColors.Good
        GpsStatus.WEAK -> OnPathColors.Weak
        GpsStatus.SEARCHING -> OnPathColors.Grey
        GpsStatus.OFF, GpsStatus.NO_PERMISSION -> OnPathColors.Bad
    }
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp),
        color = Color.White, shadowElevation = 4.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(status.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                val sub = when (status) {
                    GpsStatus.OFF -> "Tap to turn on"
                    GpsStatus.NO_PERMISSION -> "Tap to allow"
                    else -> detail
                }
                if (sub != null) Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ActivityChip(activity: Activity, sac: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp),
        color = Color.White, shadowElevation = 4.dp) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(activity.icon, null, tint = OnPathColors.Green, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Text(activity.shortLabel + if (activity == Activity.HIKE) " · T$sac" else "", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Icon(Icons.Filled.ExpandMore, "Change activity", modifier = Modifier.size(20.dp))
        }
    }
}

// ------------------------------------------------------------------ browse

@Composable
fun BrowseBar(onPlan: () -> Unit, onRecord: () -> Unit, onTours: () -> Unit, followName: String?, onStopFollow: () -> Unit) {
    BottomPanel {
        if (followName != null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Navigation, null, tint = OnPathColors.Route, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Following: $followName", Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onStopFollow) { Text("Stop following") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(Icons.Filled.Route, "Plan", onPlan, Modifier.weight(1f), primary = false)
            BigButton(Icons.Filled.FiberManualRecord, "Record", onRecord, Modifier.weight(1f), primary = true)
            BigButton(Icons.Filled.FormatListBulleted, "My tours", onTours, Modifier.weight(1f), primary = false)
        }
    }
}

@Composable
fun BigButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean) {
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
    if (primary) Button(onClick, modifier.height(68.dp), shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = OnPathColors.Rec)) { content() }
    else FilledTonalButton(onClick, modifier.height(68.dp), shape = RoundedCornerShape(18.dp)) { content() }
}

// ------------------------------------------------------------------ planning

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlanPanel(
    pointCount: Int,
    route: PlannedRoute?,
    routing: Boolean,
    error: String?,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onFromMe: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onGo: () -> Unit,
    onClose: () -> Unit,
    onDeletePoint: (Int) -> Unit,
    onHighlight: (GeoPoint?) -> Unit,
) {
    BottomPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Plan a route", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            ActionButtonSmall(Icons.Filled.Close, "Close planning", onClose)
        }
        if (routing) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp)) else Spacer(Modifier.height(3.dp))
        Spacer(Modifier.height(6.dp))
        when {
            pointCount == 0 -> Hint("Tap the map to set the start, or use “From me”.")
            pointCount == 1 -> Hint("Tap the map to set the destination.")
            route != null -> {
                StatsRow(route.distanceM, route.ascentM, route.descentM, route.durationS)
                Spacer(Modifier.height(8.dp))
                ElevationProfile(route.points, onHighlight = onHighlight)
            }
        }
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFFFF4E0)).padding(10.dp)) {
                Icon(Icons.Filled.Warning, null, tint = Color(0xFFB26A00), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(error, fontSize = 13.sp, color = Color(0xFF5A3A00))
            }
        }
        if (pointCount > 0) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                repeat(pointCount) { i ->
                    val c = when { i == 0 -> OnPathColors.Good; i == pointCount - 1 && pointCount > 1 -> OnPathColors.Bad; else -> OnPathColors.Route }
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(c)
                            .combinedClickable(onClick = {}, onLongClick = { onDeletePoint(i) }, onLongClickLabel = "Delete point ${i + 1}"),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", color = Color.White, fontWeight = FontWeight.Bold) }
                }
                Text("Long-press a point to delete", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ActionButton(Icons.AutoMirrored.Filled.Undo, "Undo", onUndo, enabled = canUndo)
            ActionButton(Icons.Filled.MyLocation, "From me", onFromMe)
            ActionButton(Icons.Filled.DeleteSweep, "Clear", onClear, enabled = pointCount > 0)
            ActionButton(Icons.Filled.Save, "Save", onSave, enabled = route != null)
            ActionButton(Icons.Filled.Share, "GPX", onShare, enabled = route != null)
        }
        Button(onClick = onGo, enabled = route != null, modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Filled.Navigation, null); Spacer(Modifier.width(8.dp)); Text("Follow & record", fontSize = 16.sp)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.TripOrigin, null, tint = OnPathColors.Green, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 15.sp)
    }
}

@Composable
fun ActionButtonSmall(icon: ImageVector, desc: String, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { Icon(icon, desc) }
}

// ------------------------------------------------------------------ shown track

@Composable
fun TrackPanel(
    track: ShownTrack,
    isFollowed: Boolean,
    recording: Boolean,
    onFollow: () -> Unit,
    onExport: () -> Unit,
    onClose: () -> Unit,
    onHighlight: (GeoPoint?) -> Unit,
) {
    BottomPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(track.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            ActionButtonSmall(Icons.Filled.Close, "Close track", onClose)
        }
        Spacer(Modifier.height(6.dp))
        StatsRow(track.distanceM, track.ascentM, track.descentM, track.durationS)
        Spacer(Modifier.height(8.dp))
        ElevationProfile(track.points, color = OnPathColors.Gpx, onHighlight = onHighlight)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("Export GPX")
            }
            Button(onClick = onFollow, enabled = !isFollowed || !recording, modifier = Modifier.weight(1.4f).height(56.dp),
                shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Filled.Navigation, null); Spacer(Modifier.width(6.dp))
                Text(if (recording) "Follow this track" else "Follow & record")
            }
        }
    }
}

// ------------------------------------------------------------------ recording

@Composable
fun RecordPanel(rec: RecState, gps: GpsStatus, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    BottomPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (rec.paused) OnPathColors.Grey else OnPathColors.Rec))
            Spacer(Modifier.width(8.dp))
            val status = when {
                rec.paused -> "Paused"
                rec.gpsLost || gps != GpsStatus.GOOD && gps != GpsStatus.WEAK -> "Recording · waiting for GPS"
                else -> "Recording"
            }
            Text(status, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        }
        if (rec.followName != null) {
            val off = rec.offRouteM
            val (txt, col) = when {
                off == null -> "Following ${rec.followName}" to MaterialTheme.colorScheme.onSurfaceVariant
                off > 40 -> "Off route · ${off.toInt()} m away" to OnPathColors.Bad
                else -> "On route · ${rec.followName}" to OnPathColors.Good
            }
            Text(txt, color = col, fontSize = 14.sp, fontWeight = if (rec.offRouteAlert) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat(Format.distance(rec.distanceM), "Distance", Modifier.weight(1f), big = true)
            Stat(Format.clock(rec.elapsedS), "Time", Modifier.weight(1f), big = true)
            Stat(Format.meters(rec.ascentM), "Ascent", Modifier.weight(1f), big = true)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (rec.paused) {
                Button(onClick = onResume, modifier = Modifier.weight(1f).height(60.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Resume", fontSize = 16.sp)
                }
            } else {
                FilledTonalButton(onClick = onPause, modifier = Modifier.weight(1f).height(60.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Filled.Pause, null); Spacer(Modifier.width(6.dp)); Text("Pause", fontSize = 16.sp)
                }
            }
            Button(onClick = onStop, modifier = Modifier.weight(1f).height(60.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OnPathColors.Bad)) {
                Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop", fontSize = 16.sp)
            }
        }
    }
}

/** Placeholder for previews/screenshot tests where MapLibre cannot render. */
@Composable
fun MapPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xFFE8EEDC)), contentAlignment = Alignment.Center) {
        Text("map", color = Color(0x55000000), textAlign = TextAlign.Center)
    }
}
