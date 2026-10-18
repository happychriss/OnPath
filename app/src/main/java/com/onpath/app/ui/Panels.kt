package com.onpath.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
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
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint

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
                Text(if (recording) "Follow this track" else "Start navigation")
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
