package com.onpath.app.ui

import com.onpath.strings.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * What the Navigate slider unlocks (owner 2026-11-01: "mit dem Schieber die anderen Buttons freischalten"; look
 * 2026-11-02, onpath.private#17 = C compact): in place of the slider a compact green bar (~1.5× the slider) with three
 * tiles in fixed colours to remember – **green** Pause / Resume ([OnPathColors.ActionGo]), **blue** Change route
 * ([OnPathColors.ActionEdit]), **red** End ([OnPathColors.ActionStop]) – white words and icons that carry the track
 * ([TrackIcon]: the line ends in ⏸ / branches off / ends in ■). The slider stays the guard against pocket and glove
 * touches. Change route opens the plan; End asks first ("Aufnahme beenden?"). A small ✕ above the bar on the right
 * closes it; the bar also closes with any of its tiles or by itself after [TourActions.OPEN_MS]. Paused it stays open,
 * without ✕ (Resume at once).
 */
@Composable
fun TourActionBar(paused: Boolean, onPause: (Boolean) -> Unit, onChangeRoute: () -> Unit, onEnd: () -> Unit,
                  onClose: () -> Unit, modifier: Modifier = Modifier, closable: Boolean = true) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (closable) {
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Box(Modifier.size(34.dp).shadow(3.dp, CircleShape).background(OnPathColors.CardWhite, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = str(Res.string.nav_actions_close), tint = OnPathColors.Ink,
                        modifier = Modifier.size(20.dp))
                }
            }
        }
        Surface(shape = RoundedCornerShape(TourActions.BAR_CORNER), color = OnPathColors.Green, shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(TourActions.BAR_PAD), horizontalArrangement = Arrangement.spacedBy(TourActions.BAR_PAD)) {
                ActionTile(if (paused) TrackIconKind.RESUME else TrackIconKind.PAUSE, str(if (paused) Res.string.common_resume else Res.string.nav_action_pause),
                    OnPathColors.ActionGo, Modifier.weight(1f)) { onClose(); onPause(!paused) }
                ActionTile(TrackIconKind.CHANGE, str(Res.string.plan_slide_change_route), OnPathColors.ActionEdit,
                    Modifier.weight(1f)) { onClose(); onChangeRoute() }
                ActionTile(TrackIconKind.END, str(Res.string.nav_end_short), OnPathColors.ActionStop, Modifier.weight(1f)) {
                    onClose(); onEnd()
                }
            }
        }
    }
}

@Composable
private fun ActionTile(icon: TrackIconKind, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(TourActions.TILE_CORNER), color = color,
        contentColor = OnPathColors.CardWhite, modifier = modifier.heightIn(min = TourActions.TILE_HEIGHT)) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            TrackIcon(icon, Modifier.size(width = 48.dp, height = 26.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, color = OnPathColors.CardWhite, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

/** The three tile icons: the ridden line and what happens to it. */
enum class TrackIconKind { PAUSE, RESUME, CHANGE, END }

/**
 * Icons that carry the track (owner's sketch 2026-11-02, viewBox 64 × 34): PAUSE = the line ends in ⏸ (RESUME: in ▶), CHANGE = it
 * branches (old way dashed, new way solid, a dot at the fork), END = it ends in ■. White, drawn – no icon font.
 */
@Composable
fun TrackIcon(kind: TrackIconKind, modifier: Modifier = Modifier, color: Color = OnPathColors.CardWhite) {
    Canvas(modifier) {
        scale(size.width / 64f, size.height / 34f, pivot = Offset.Zero) { drawTrackIcon(kind, color) }
    }
}

private fun DrawScope.drawTrackIcon(kind: TrackIconKind, color: Color) {
    val line = Stroke(width = 3f, cap = StrokeCap.Round)
    fun path(block: Path.() -> Unit) = Path().apply(block)
    when (kind) {
        TrackIconKind.PAUSE -> {
            drawPath(path { moveTo(3f, 28f); cubicTo(14f, 26f, 16f, 10f, 27f, 12f); cubicTo(38f, 14f, 38f, 24f, 44f, 18f) }, color, style = line)
            drawRoundRect(color, Offset(49f, 8f), Size(4.5f, 16f), CornerRadius(1.5f))
            drawRoundRect(color, Offset(57f, 8f), Size(4.5f, 16f), CornerRadius(1.5f))
        }
        TrackIconKind.RESUME -> {
            drawPath(path { moveTo(3f, 28f); cubicTo(14f, 26f, 16f, 10f, 27f, 12f); cubicTo(38f, 14f, 38f, 24f, 44f, 18f) }, color, style = line)
            drawPath(path { moveTo(50f, 7f); lineTo(62f, 16f); lineTo(50f, 25f); close() }, color)
        }
        TrackIconKind.CHANGE -> {
            drawPath(path { moveTo(3f, 28f); cubicTo(12f, 26f, 16f, 16f, 26f, 16f) }, color, style = line)
            drawPath(path { moveTo(26f, 16f); cubicTo(36f, 16f, 40f, 6f, 50f, 5f) }, color.copy(alpha = 0.7f),
                style = Stroke(width = 3f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))))
            drawPath(path { moveTo(26f, 16f); cubicTo(38f, 17f, 44f, 26f, 58f, 27f) }, color, style = line)
            drawCircle(color, 3.5f, Offset(26f, 16f))
        }
        TrackIconKind.END -> {
            drawPath(path { moveTo(3f, 28f); cubicTo(14f, 26f, 16f, 10f, 27f, 12f); cubicTo(38f, 14f, 40f, 22f, 48f, 18f) }, color, style = line)
            drawRoundRect(color, Offset(50f, 11f), Size(12f, 12f), CornerRadius(2f))
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
