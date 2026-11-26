package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onpath.app.routing.Activity

// The Daumen-Karte (owner 2026-11-26, docs/team/items/2026-11-26-daumen-karte.md): the pieces of the floating card at the
// bottom – Plan's search row and chips (PlanPanel's ThumbHead) and Navigate's card round Anhalten · Planen.

/**
 * Navigate's floating card round the bottom buttons: the same card as Plan's sheet, 12 dp from the edges, above the
 * navigation bar – no mode tab, no recording sign (owner: "die Navigation soll möglichst frei von irgendwas sein").
 */
@Composable
internal fun NavThumbCard(modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    Surface(shape = OnPathShapes.Thumb, color = OnPathColors.ThumbCard,
        modifier = modifier.navigationBarsPadding()
            .padding(start = OnPathShapes.THUMB_EDGE_DP.dp, end = OnPathShapes.THUMB_EDGE_DP.dp, bottom = OnPathShapes.THUMB_EDGE_DP.dp)
            .warmShadow(OnPathShadow.HIGH, OnPathShapes.Thumb)) {
        content(Modifier.padding(10.dp))
    }
}

/** The search field of the empty card: the owner's question "Wohin soll’s gehen?" in it, a tap opens the search. */
@Composable
internal fun ThumbSearchField(onSearch: () -> Unit, modifier: Modifier) {
    val ask = str(Res.string.plan_hint_first_point_question)
    Surface(onClick = onSearch, shape = ThumbInner, color = OnPathColors.ThumbField,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = ask; role = Role.Button }) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, tint = OnPathColors.PlanAccent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(ask, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = OnPathColors.Ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The inner corners of the thumb card (field, squares): 14 dp in the card's 22 dp. */
internal val ThumbInner = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)

/** A 48 dp square in the card's top row (🔍, ☰): the field's deeper sage, a plan-blue sign. */
@Composable
internal fun ThumbSquare(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = ThumbInner, color = OnPathColors.ThumbField,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = OnPathColors.PlanAccent) }
    }
}

/** A chip of the empty card ("Meine Touren"): paper with a fine line, 40 dp to see, 48 dp to touch. */
@Composable
internal fun ThumbChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = OnPathColors.Paper,
        border = androidx.compose.foundation.BorderStroke(1.dp, OnPathColors.Line),
        modifier = Modifier.heightIn(min = 40.dp).semantics { role = Role.Button }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = OnPathColors.Ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = OnPathColors.Ink, maxLines = 1)
        }
    }
}

/**
 * The activity in the card (replaces the round button on the map, owner 2026-11-26): the selection's light green, a
 * green sign; [words] "Wandern · T2" in the empty card, else the sign + "T2" only (the facts need the room). Tap = the
 * activity picker; TalkBack: "Aktivität: Wandern · T2. Tippen zum Ändern".
 */
@Composable
internal fun ThumbActivityChip(activity: Activity, sac: Int, onClick: () -> Unit, words: Boolean) {
    val name = com.onpath.app.data.LastActivity.chipText(activity, sac)
    val desc = str(Res.string.plan_activity_chip_desc, name)
    Surface(onClick = onClick, shape = CircleShape, color = OnPathColors.GreenLight,
        modifier = Modifier.heightIn(min = 40.dp).clearAndSetSemantics { contentDescription = desc; role = Role.Button }) {
        Row(Modifier.padding(horizontal = if (words) 12.dp else 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(activity.icon, null, tint = OnPathColors.Green, modifier = Modifier.size(20.dp))
            val text = if (words) name else if (activity == Activity.HIKE) "T$sac" else null
            if (text != null) {
                Spacer(Modifier.width(6.dp))
                Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = if (words) FontWeight.Medium else FontWeight.Bold,
                    color = OnPathColors.Ink, maxLines = 1, softWrap = false)
            }
        }
    }
}

