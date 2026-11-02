package com.onpath.app.ui

import com.onpath.strings.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
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
    Column(modifier.fillMaxWidth()) {
        Surface(shape = RoundedCornerShape(TourActions.BAR_CORNER), color = OnPathColors.Green, shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(TourActions.BAR_PAD), horizontalArrangement = Arrangement.spacedBy(TourActions.BAR_PAD)) {
                ActionTile(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, str(if (paused) Res.string.common_resume else Res.string.nav_action_pause),
                    OnPathColors.ActionGo, Modifier.weight(1f)) { onClose(); onPause(!paused) }
                ActionTile(Icons.Filled.Edit, str(Res.string.plan_slide_change_route), OnPathColors.ActionEdit,
                    Modifier.weight(1f)) { onClose(); onChangeRoute() }
                ActionTile(Icons.Filled.Stop, str(Res.string.nav_end_short), OnPathColors.ActionStop, Modifier.weight(1f)) {
                    onClose(); onEnd()
                }
            }
        }
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(TourActions.TILE_CORNER), color = color,
        contentColor = OnPathColors.CardWhite, modifier = modifier.heightIn(min = TourActions.TILE_HEIGHT)) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = OnPathColors.CardWhite, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, color = OnPathColors.CardWhite, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

/** Rules and sizes of the unlocked bar (pure). */
object TourActions {
    /** The unlocked bar closes by itself after this long without a tap (phone back in the pocket). */
    const val OPEN_MS = 30_000L
    /** Tile height: with the bar's padding ≈ 1.5× the slider's 64 dp (owner 2026-11-02: compact, not twice). */
    val TILE_HEIGHT = 84.dp
    val BAR_PAD = 4.dp
    val BAR_CORNER = 24.dp
    val TILE_CORNER = 18.dp
}
