package com.onpath.app.ui.routes

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onpath.app.domain.RoutesTab
import com.onpath.app.ui.OnPathColors
import com.onpath.app.ui.OnPathShadow
import com.onpath.app.ui.warmShadow
import com.onpath.strings.*

/**
 * „Geplant 12 · Gemacht 8“ (owner 2026-11-27, variant 1 of the sketch): a sliding switch – a grey track, the chosen side a
 * white thumb with a soft shadow that glides over; each side with its number of routes / tours, no ✓. 48 dp tall, each half
 * a selectable (TalkBack: a tab). [counts] by tab.
 */
@Composable
fun RoutesTabSwitch(tab: RoutesTab, counts: Map<RoutesTab, Int>, onPick: (RoutesTab) -> Unit, modifier: Modifier = Modifier) {
    val track = RoundedCornerShape(14.dp)
    val thumb = RoundedCornerShape(11.dp)
    BoxWithConstraints(modifier.fillMaxWidth().heightIn(min = 48.dp).background(OnPathColors.PaperDeep, track).padding(3.dp)) {
        val half = maxWidth / RoutesTab.entries.size
        val x by animateDpAsState(half * RoutesTab.entries.indexOf(tab), label = "routesTab")
        Box(Modifier.offset(x = x).width(half).heightIn(min = 42.dp).warmShadow(OnPathShadow.LOW, thumb).background(OnPathColors.CardWhite, thumb))
        Row(Modifier.fillMaxWidth().heightIn(min = 42.dp).selectableGroup()) {
            for (t in RoutesTab.entries) {
                val on = t == tab
                val name = str(if (t == RoutesTab.PLANNED) Res.string.routes_tab_planned else Res.string.routes_tab_done)
                Box(Modifier.weight(1f).heightIn(min = 42.dp).selectable(on, role = Role.Tab, onClick = { onPick(t) }),
                    contentAlignment = Alignment.Center) {
                    Text(counts[t]?.let { "$name  $it" } ?: name, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) OnPathColors.Walnut else OnPathColors.Grey, modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
        }
    }
}
