package com.onpath.app.poi

import com.onpath.strings.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.onpath.app.ui.OnPathShapes

/** The place info's planning button (Chriss 2026-11-28): the hold menu's „Als Ziel“ of this place ([PinMenuItems.goal]). */
class PoiGoal(val label: String, val onClick: () -> Unit)

/**
 * The buttons at the end of the place info. Without [goal]: „Google Maps ↗“ alone, filled, as before. With it (picture
 * halten-lernen-abc (c)): „Als Ziel“ filled first, „Google Maps ↗“ outlined beside it – side by side when both fit in
 * half the width each, else one above the other at full width (a large font never cuts a word).
 */
@Composable
fun PoiButtons(goal: PoiGoal?, onGoogle: () -> Unit, modifier: Modifier = Modifier) {
    if (goal == null) {
        Button(onClick = onGoogle, modifier = modifier.heightIn(min = 56.dp), shape = OnPathShapes.Pill) {
            // „Google Maps ↗“ (owner 2026-11-27): the one Google link, no line under it
            Icon(Icons.Outlined.Map, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            GoogleText()
        }
        return
    }
    val gap = 10.dp
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp) // two side by side on a 360 dp phone
    Layout({
        Button(onClick = goal.onClick, modifier = Modifier.heightIn(min = 56.dp), shape = OnPathShapes.Pill, contentPadding = pad) {
            Icon(Icons.Outlined.Flag, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(goal.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(onClick = onGoogle, modifier = Modifier.heightIn(min = 56.dp), shape = OnPathShapes.Pill, contentPadding = pad) { GoogleText() }
    }, modifier) { m, c ->
        val g = gap.roundToPx()
        val half = (c.maxWidth - g) / 2
        val side = m.all { it.maxIntrinsicWidth(c.maxHeight) <= half }
        if (side) {
            val ps = m.map { it.measure(Constraints.fixedWidth(half)) }
            val h = ps.maxOf { it.height }
            layout(c.maxWidth, h) { ps[0].place(0, (h - ps[0].height) / 2); ps[1].place(half + g, (h - ps[1].height) / 2) }
        } else {
            val ps = m.map { it.measure(Constraints.fixedWidth(c.maxWidth)) }
            layout(c.maxWidth, ps[0].height + g + ps[1].height) { ps[0].place(0, 0); ps[1].place(0, ps[0].height + g) }
        }
    }
}

@Composable
private fun GoogleText() {
    Text(str(Res.string.poi_open_google), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.width(8.dp))
    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp))
}
