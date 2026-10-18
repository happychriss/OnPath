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
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
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
fun BottomPanel(
    modifier: Modifier = Modifier,
    /** Resizable panels: called with true for a swipe down (smaller), false for a swipe up (bigger). */
    onSwipe: ((down: Boolean) -> Unit)? = null,
    /** Tap on the grab handle. */
    onHandleTap: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    val swipe = if (onSwipe == null) Modifier else Modifier
        .graphicsLayer { translationY = drag.coerceAtLeast(0f) * 0.5f }  // follow the finger a little
        .draggable(
            state = rememberDraggableState { drag += it },
            orientation = Orientation.Vertical,
            onDragStarted = { drag = 0f },
            onDragStopped = {
                when {
                    drag > threshold -> onSwipe(true)
                    drag < -threshold -> onSwipe(false)
                }
                drag = 0f
            },
        )
    Surface(
        modifier = modifier.fillMaxWidth().then(swipe),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.navigationBarsPadding().animateContentSize()
            .padding(start = 16.dp, end = 16.dp, top = if (onHandleTap != null) 0.dp else 10.dp, bottom = 12.dp)) {
            if (onHandleTap != null) {
                // Bigger touch target around the handle when it does something.
                Box(
                    Modifier.fillMaxWidth().height(22.dp)
                        .clickable(onClickLabel = "Resize panel", onClick = onHandleTap),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(Color(0x33000000)))
                }
            } else {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp)
                        .clip(CircleShape).background(Color(0x22000000))
                )
                Spacer(Modifier.height(8.dp))
            }
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

// ------------------------------------------------------------------ planning: see PlanPanel.kt

@Composable
internal fun Hint(text: String) {
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
fun RecordPanel(
    rec: RecState, gps: GpsStatus, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit,
    /** Off-route threshold from Settings; null = warning switched off. */
    offRouteLimitM: Int? = 40,
) {
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
                offRouteLimitM != null && off > offRouteLimitM -> "Off route · ${off.toInt()} m away" to OnPathColors.Bad
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
