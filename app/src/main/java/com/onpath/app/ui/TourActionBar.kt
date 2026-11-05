package com.onpath.app.ui

import com.onpath.strings.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * What the Navigate slider unlocks (owner 2026-11-01: "mit dem Schieber die anderen Buttons freischalten"; look
 * 2026-11-02, onpath.private#17 = C compact): in place of the slider a compact green bar (~1.5× the slider) with three
 * tiles in fixed colours to remember – **green** Pause / Resume ([OnPathColors.ActionGo]), **blue** Change route
 * ([OnPathColors.ActionEdit]), **red** End ([OnPathColors.ActionStop]) – white words and the plain signs ⏸ (▶ while
 * paused), ✎ and ■ (owner 2026-11-02: the track-line icons did not please – "einfach ein Stoppzeichen"). The slider stays the guard against pocket and glove
 * touches. Change route opens the plan; End asks first ("Aufnahme beenden?"). No ✕ (owner 2026-11-02): a tap on the
 * map or Android's back closes it (NavigateOverlay), so do any of its tiles and the time-out [TourActions.OPEN_MS].
 * Paused it stays open (Resume at once).
 */
@Composable
fun TourActionBar(paused: Boolean, onPause: (Boolean) -> Unit, onChangeRoute: () -> Unit, onEnd: () -> Unit,
                  onClose: () -> Unit, modifier: Modifier = Modifier) {
    ActionTiles(listOf(
        ActionTileSpec(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, str(if (paused) Res.string.common_resume else Res.string.nav_action_pause),
            OnPathColors.ActionGo) { onClose(); onPause(!paused) },
        ActionTileSpec(Icons.Filled.Edit, str(Res.string.plan_slide_change_route), OnPathColors.ActionEdit) { onClose(); onChangeRoute() },
        ActionTileSpec(Icons.Filled.Stop, str(Res.string.nav_end_short), OnPathColors.ActionStop) { onClose(); onEnd() },
    ), modifier)
}

/** One tile of [ActionTiles]: icon, word, its fixed colour (green / blue / red, [OnPathColors]), greyed when not [enabled]. */
class ActionTileSpec(val icon: ImageVector, val label: String, val color: Color, val enabled: Boolean = true, val onClick: () -> Unit)

/** The green bar with its tiles – ONE look for Navigate's slider bar and the plan during a tour (owner 2026-11-02). */
@Composable
fun ActionTiles(tiles: List<ActionTileSpec>, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(TourActions.BAR_CORNER), color = OnPathColors.Green, shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(TourActions.BAR_PAD), horizontalArrangement = Arrangement.spacedBy(TourActions.BAR_PAD)) {
            tiles.forEach { ActionTile(it, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ActionTile(t: ActionTileSpec, modifier: Modifier) {
    // greyed: a faint white tile on the green bar, the word dimmed – nothing to do here now
    val bg = if (t.enabled) t.color else OnPathColors.CardWhite.copy(alpha = TourActions.OFF_TILE_ALPHA)
    val ink = if (t.enabled) OnPathColors.CardWhite else OnPathColors.CardWhite.copy(alpha = TourActions.OFF_INK_ALPHA)
    // compact (owner 2026-11-03: "echt riesig"): the sign left of the word in one row, about half the old height
    Surface(onClick = t.onClick, enabled = t.enabled, shape = RoundedCornerShape(TourActions.TILE_CORNER), color = bg,
        contentColor = ink, modifier = modifier.heightIn(min = TourActions.TILE_HEIGHT)) {
        // little side room for the sign so "Route speichern" keeps its whole words on two lines
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            Icon(t.icon, null, tint = ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(4.dp))
            Text(t.label, color = ink, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start, maxLines = 2)
        }
    }
}

/** Rules and sizes of the unlocked bar (pure). */
object TourActions {
    /** The unlocked bar closes by itself after this long without a tap (phone back in the pocket). */
    const val OPEN_MS = 30_000L
    /** Tile height (owner 2026-11-03: the tiles were "echt riesig" – sign and word in one row, about the slider's size). */
    val TILE_HEIGHT = 52.dp
    val BAR_PAD = 4.dp
    val BAR_CORNER = 24.dp
    val TILE_CORNER = 18.dp
    /** A greyed tile: white at this alpha on the green bar, its word and icon at [OFF_INK_ALPHA]. */
    const val OFF_TILE_ALPHA = 0.14f
    const val OFF_INK_ALPHA = 0.55f
}
