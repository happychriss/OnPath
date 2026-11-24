package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ShareLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.onpath.strings.*

/**
 * SKETCH ONLY (owner 2026-11-24: "Standort teilen" more visible in Navigate) – where the share entry could stand.
 * Not wired to anything; read from [LocalSharePlaceSketch] (null = nothing changes, the default everywhere).
 */
enum class SharePlace { A_COLUMN, B_CARD, C_PILL, D_TWO_BUBBLES }
enum class SharePlaceState { OFF, LIVE, WAITING }
data class SharePlaceSketch(val place: SharePlace, val state: SharePlaceState,
                            val icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.ShareLocation)

val LocalSharePlaceSketch = compositionLocalOf<SharePlaceSketch?> { null }

/** A: the top button of the map column – same size and look as the other buttons; green + dot while sharing, grey waiting. */
@Composable
internal fun SharePlaceColumnButton(state: SharePlaceState, size: Dp,
                                    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.Share) {
    val tint = when (state) {
        SharePlaceState.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
        SharePlaceState.LIVE -> OnPathColors.Green
        SharePlaceState.WAITING -> OnPathColors.Grey
    }
    Box(Modifier.size(size).clip(CircleShape).clickable {}.semantics { contentDescription = Txt.s(Res.string.live_share) },
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
        if (state != SharePlaceState.OFF) Box(Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 9.dp)
            .size(8.dp).background(tint, CircleShape))
    }
}

/** B: at the right edge of the status card – an icon, or while sharing the dot + "Standort" (grey while waiting). */
@Composable
internal fun SharePlaceCardEnd(state: SharePlaceState) {
    val label = str(Res.string.live_share)
    if (state == SharePlaceState.OFF || compactAtLargeFont()) {
        // at the largest font the word would squeeze the card's numbers: the icon alone, green / grey with a dot
        val tint = when (state) {
            SharePlaceState.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
            SharePlaceState.LIVE -> OnPathColors.Green
            SharePlaceState.WAITING -> OnPathColors.Grey
        }
        Box(Modifier.size(48.dp).clip(CircleShape).clickable {}.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Share, null, tint = tint, modifier = Modifier.size(22.dp))
            if (state != SharePlaceState.OFF) Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 7.dp).size(9.dp).background(tint, CircleShape))
        }
    } else {
        val live = state == SharePlaceState.LIVE
        Row(Modifier.clip(MaterialTheme.shapes.small).clickable {}.heightIn(min = 48.dp).padding(horizontal = 8.dp)
            .semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(if (live) OnPathColors.Green else OnPathColors.Grey, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(str(Res.string.live_sign), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                color = if (live) MaterialTheme.colorScheme.onSurface else OnPathColors.Grey)
        }
    }
}

/** The width B takes in the card's row (dp), for the card's fit rule. */
@Composable
internal fun sharePlaceCardEndWidth(state: SharePlaceState): Float {
    if (state == SharePlaceState.OFF || compactAtLargeFont()) return 48f
    val tm = rememberTextMeasurer()
    val d = androidx.compose.ui.platform.LocalDensity.current
    val w = with(d) { tm.measure(str(Res.string.live_sign), MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false).size.width.toDp() }
    return 16f + 12f + 6f + w.value
}

/** C: a slim pill centred under the status card; while sharing "● Standort" with a green dot (grey waiting). */
@Composable
internal fun SharePlacePill(state: SharePlaceState) {
    val label = str(Res.string.live_share)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(onClick = {}, shape = CircleShape, color = OnPathColors.MapGround,
            modifier = Modifier.floatShadow(CircleShape).semantics { contentDescription = label }) {
            Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state == SharePlaceState.OFF) {
                    Icon(Icons.Outlined.Share, null, tint = OnPathColors.Green, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                } else {
                    val live = state == SharePlaceState.LIVE
                    Box(Modifier.size(12.dp).background(if (live) OnPathColors.Green else OnPathColors.Grey, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(str(Res.string.live_sign), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                        color = if (live) MaterialTheme.colorScheme.onSurface else OnPathColors.Grey)
                }
            }
        }
    }
}

/** Sketch rule: from this font scale on B shows only the icon while sharing. */
@Composable
private fun compactAtLargeFont(): Boolean = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f
