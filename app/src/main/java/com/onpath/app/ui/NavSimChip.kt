package com.onpath.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.recording.DemoLocation

/**
 * Developer tools only (owner 2026-11-01: "Tour-Sim" on the Navigate page, shown only after the unlock – testers get
 * the same debug builds): a small chip that rides the followed line with the demo location ([DemoLocation]).
 * Off: "▶ Sim". On: "Sim ×5" (tap → next speed of [DemoLocation.FACTORS]), "⏸" / "▶" (pause to look at a spot,
 * owner 2026-11-01) and "■" (back to real GPS). Developer tools stay English (CLAUDE.md).
 */
@Composable
fun NavSimChip(state: DemoLocation.State?, onStart: () -> Unit, onFactor: (Int) -> Unit, onStop: () -> Unit,
               modifier: Modifier = Modifier, onPause: (Boolean) -> Unit = {}) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state == null) SimPill("▶ Sim", "Start tour simulation", onStart)
        else {
            val all = DemoLocation.FACTORS
            val next = all[(all.indexOf(state.factor) + 1).mod(all.size)]
            SimPill("Sim ×${state.factor}" + if (state.ended) " · end" else "", "Simulation speed, tap for ×$next") { onFactor(next) }
            if (state.kind == DemoLocation.Kind.RIDE && !state.ended)
                SimPill(if (state.paused) "▶" else "⏸", if (state.paused) "Go on" else "Pause the simulation") { onPause(!state.paused) }
            SimPill("■", "Stop tour simulation", onStop)
        }
    }
}

@Composable
private fun SimPill(text: String, label: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(50), color = OnPathColors.TagContainer, contentColor = OnPathColors.TagInk,
            shadowElevation = 2.dp) {
            Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = OnPathColors.TagInk,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/**
 * Where Navigate's "▶ Sim" begins on the followed line (field 2026-11-06 23:35, Hilden round trip whose first and last
 * ~100 m are the same street: the Sim began at ① while the tour was 2.7 km along; the navigator kept its progress and
 * read the jump as the loop's last metres walked backwards – the way out's next left turn was never said, the turn card
 * showed the way back's "80 m Geradeaus"). During a tour on that line: where the tour is ([shownAlongM], else the
 * progress once the route was reached); otherwise the start. Pure.
 */
object NavSimStart {
    fun alongM(sameLine: Boolean, reached: Boolean, shownAlongM: Double?, progressM: Double?): Double = when {
        !sameLine -> 0.0
        shownAlongM != null -> shownAlongM.coerceAtLeast(0.0)
        reached && progressM != null -> progressM.coerceAtLeast(0.0)
        else -> 0.0
    }
}
