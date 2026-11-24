package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

class CellCardCallbacks(
    val onMobileYes: () -> Unit = {},
    /** Overlay "Cancel": stop loading these areas (installed ones stay; the next point set there asks again). */
    val onCancel: () -> Unit = {},
    val onWifiOnly: () -> Unit = {},
    val onRetry: () -> Unit = {},
    /** OK on "Map ready", Not now on a failure, OK on "not available", × while loading (downloads go on). */
    val onClose: () -> Unit = {},
    /** "Allow location" on the first-start card (system permission dialog). */
    val onAllowLocation: () -> Unit = {},
)

/**
 * "Nur Übersichtskarte · Detailkarte laden" (owner 2026-11-06, Pixel review: the old white bar "… die Karte gedrückt
 * halten" did not fit the style and covered the activity button): a paper pill as high as the round activity button,
 * beside it ([OverviewPillPlace]) – Walnuss text, the blue action. The whole pill is the button (48 dp target); it opens
 * the usual "load the map here?" question for the map centre ([onLoad]). Persistent while the view is overview only.
 */
@Composable
fun OverviewPill(onLoad: () -> Unit, modifier: Modifier = Modifier) {
    val text = str(Res.string.maps_overview_only)
    val action = str(Res.string.maps_overview_load)
    Surface(onClick = onLoad, shape = OnPathShapes.Pill,
        color = OnPathColors.Paper,
        modifier = modifier.heightIn(min = OverviewPillPlace.HEIGHT_DP.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$text. $action" }.warmShadow(OnPathShadow.LOW, OnPathShapes.Pill)) {
        // no map sign: text + action fit on one line at normal font on a 390 dp phone (both lines wrap at large font)
        Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = OnPathColors.Walnut,
                modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(10.dp))
            Text(action, style = MaterialTheme.typography.labelLarge, color = OnPathColors.ActionEdit)
        }
    }
}

/**
 * The quiet pill under the search bar: a status with nothing to decide ("No detailed map – loads when online",
 * "Waiting for GPS …"). An ANZEIGE (docs/design/ui-style.md): no button, no ×
 * – it goes when the state goes. Anything that needs an OK is a [MessageCard] instead.
 */
@Composable
fun QuietPill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    // the card corners (20 dp): on one line they are clamped to half the height – a pill; a text that wraps (large
    // font, a two-sentence note) gets a rounded box instead of an oval that cut off the first and last letters
    Surface(shape = OnPathShapes.Card,
        color = OnPathColors.Paper.copy(alpha = 0.94f), // over the map: paper (owner 2026-11-06)
        modifier = modifier.androidxSemantics(text).warmShadow(OnPathShadow.LOW, OnPathShapes.Card)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false))
        }
    }
}

private fun Modifier.androidxSemantics(text: String) =
    this.then(Modifier.semantics(mergeDescendants = true) { contentDescription = text })

