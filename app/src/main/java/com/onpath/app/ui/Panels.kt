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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onpath.app.data.Format
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint

// ------------------------------------------------------------------ shared pieces

/**
 * Rounded bottom sheet look used for the fixed bottom panels (shown track, place card). The plan sheet, which can be
 * dragged between steps, is a StepSheet.
 */
@Composable
fun BottomPanel(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.animateContentSize().navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            // Material drag handle (32 × 4 dp)
            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(32.dp).height(4.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
            }
            content()
        }
    }
}
@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier, big: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Distance · ascent · descent · time; in a 2 × 2 grid when there is not room for four columns (dialogs). */
@Composable
fun StatsRow(distance: Double, ascent: Double, descent: Double, durationS: Double) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stats = listOf(Format.distance(distance) to "Distance", "↑ " + Format.meters(ascent) to "Ascent",
            "↓ " + Format.meters(descent) to "Descent", Format.duration(durationS) to "Time")
        val perRow = if (maxWidth < 340.dp) 2 else 4
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            stats.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth()) { row.forEach { (v, l) -> Stat(v, l, Modifier.weight(1f)) } }
            }
        }
    }
}

// ------------------------------------------------------------------ planning: see PlanPanel.kt

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
    /** The activity the track is navigated with (named on the button, UX review 2026-10-23 problem 5). */
    activity: com.onpath.app.routing.Activity,
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
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Format.distance(track.distanceM), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(Format.duration(track.durationS), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 1.dp))
        }
        Text("↑ ${Format.meters(track.ascentM)}  ↓ ${Format.meters(track.descentM)}", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        // "Activity set to MTB from the file": an import changed the activity – said, never silent (captain 2026-10-26)
        track.note?.let { InfoLine(it) }
        Spacer(Modifier.height(8.dp))
        ElevationProfile(track.points, color = OnPathColors.Gpx, onHighlight = onHighlight)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Export as a square icon button: at 16 sp "Start navigation · Trekking bike" needs the room
            OutlinedButton(onClick = onExport, modifier = Modifier.size(56.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Icon(Icons.Filled.Share, "Export GPX")
            }
            Button(onClick = onFollow, enabled = !isFollowed || !recording, modifier = Modifier.weight(1f).height(56.dp)) {
                Icon(Icons.Filled.Navigation, null); Spacer(Modifier.width(8.dp))
                Text(trackFollowLabel(recording, activity), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "Start navigation · Hike" / "Navigate this track · MTB": the button names the activity it navigates with. */
internal fun trackFollowLabel(recording: Boolean, activity: com.onpath.app.routing.Activity): String =
    (if (recording) "Navigate this track" else "Start navigation") + " · " + activity.navName

/** Placeholder for previews/screenshot tests where MapLibre cannot render. */
@Composable
fun MapPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xFFE8EEDC)), contentAlignment = Alignment.Center) {
        Text("map", color = Color(0x55000000), textAlign = TextAlign.Center)
    }
}
