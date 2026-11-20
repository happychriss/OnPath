package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onpath.app.ui.live.LiveAction
import com.onpath.app.ui.live.LiveSign
import com.onpath.strings.*

/**
 * Live sharing in the Settings opened from Navigate (owner 2026-11-20: "in der Navigation bei den Einstellungen, als
 * erster Eintrag mit Symbol") – the first card of that list, rows like the page's own (symbol, 24 dp gap, the label):
 * not sharing the one row "Live teilen" (a tap → the share sheet); sharing the state ("● Live", grey "● Live · wartet
 * auf Netz") and under it "Link nochmal teilen" and "Teilen beenden"; [name] non-null adds the row to change the name.
 * Nothing when [actions] is empty (no tour records).
 */
@Composable
fun LiveSettingsCard(sign: LiveSign, actions: List<LiveAction>, onAction: (LiveAction) -> Unit,
                     name: String? = null, onName: () -> Unit = {}) {
    if (actions.isEmpty()) return
    SettingsCard {
        if (sign != LiveSign.NONE) {
            val live = sign == LiveSign.LIVE
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp)
                .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(12.dp).background(if (live) OnPathColors.Green else OnPathColors.Grey, CircleShape))
                }
                Spacer(Modifier.width(24.dp))
                Text(str(if (live) Res.string.live_sign else Res.string.live_sign_waiting), style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold, color = if (live) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        actions.forEachIndexed { i, a ->
            if (i > 0 || sign != LiveSign.NONE) HorizontalDivider(Modifier.padding(start = 64.dp))
            when (a) {
                LiveAction.SHARE -> LiveRow(Icons.Outlined.Share, str(Res.string.live_share)) { onAction(a) }
                LiveAction.SHARE_AGAIN -> LiveRow(Icons.Outlined.Share, str(Res.string.live_share_again)) { onAction(a) }
                LiveAction.STOP -> LiveRow(Icons.Outlined.Close, str(Res.string.live_stop)) { onAction(a) }
            }
        }
        if (name != null) {
            HorizontalDivider(Modifier.padding(start = 64.dp))
            LiveRow(Icons.Outlined.Person, str(Res.string.live_name_row), name.ifEmpty { null }, onName)
        }
    }
    // the list's own gap to the next card (this card shares the Navigation section's list item)
    Spacer(Modifier.height(12.dp))
}

/** One row as the on-the-way Settings draw theirs: the symbol, the label (+ a small second line), the whole row tappable. */
@Composable
private fun LiveRow(icon: ImageVector, label: String, sub: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = OnPathColors.Green, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** How the sign on Navigate looks – the app carries [IN_TURN_CARD] (with the pill where no turn card is); the others are proposals for the pictures. */
enum class LiveSignLook {
    /** A: a small pill "● Live" at the top right, above the turn card. */
    PILL,
    /** B: only a dot in a small round chip at the top right. */
    DOT,
    /** C: "● Live" at the right edge inside the green turn card; a tour without a turn card gets the pill. */
    IN_TURN_CARD,
}

/**
 * The sign on Navigate that the tour is shared (owner 2026-11-20: "oben bei den Richtungen anzeigen") as a pill or a
 * dot on the map ([LiveSignLook.PILL] / [LiveSignLook.DOT]), at the right of the top column: green = uploads get
 * through, grey = "wartet auf Netz". Nothing when not sharing. A tap opens the two actions ([actions], as in Settings).
 */
@Composable
fun LiveSignOnMap(sign: LiveSign, look: LiveSignLook, actions: List<LiveAction> = emptyList(), onAction: (LiveAction) -> Unit = {}) {
    if (sign == LiveSign.NONE) return
    val live = sign == LiveSign.LIVE
    val label = str(if (live) Res.string.live_sign else Res.string.live_sign_waiting)
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box {
            androidx.compose.material3.Surface(onClick = { open = true }, shape = CircleShape, color = OnPathColors.MapGround,
                modifier = Modifier.floatShadow(CircleShape).semantics { contentDescription = label }) {
                Row(Modifier.heightIn(min = 32.dp).padding(horizontal = if (look == LiveSignLook.DOT) 10.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(if (live) OnPathColors.Green else OnPathColors.Grey, CircleShape))
                    if (look != LiveSignLook.DOT) {
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                            color = if (live) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            LiveSignMenu(open, actions, onAction) { open = false }
        }
    }
}

/**
 * The sign inside the green turn card ([LiveSignLook.IN_TURN_CARD]), at its right edge: a small dot with a white ring
 * and "Live" in the card's white – quiet beside the arrow and the distance; waiting the dot is hollow and the word dimmed.
 */
@Composable
fun LiveSignInTurnCard(sign: LiveSign, actions: List<LiveAction> = emptyList(), onAction: (LiveAction) -> Unit = {}) {
    if (sign == LiveSign.NONE) return
    val live = sign == LiveSign.LIVE
    val label = str(if (live) Res.string.live_sign else Res.string.live_sign_waiting)
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button) { open = true }
            .heightIn(min = 48.dp).padding(start = 8.dp, end = 2.dp).semantics(mergeDescendants = true) { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).border(2.dp, OnPathColors.CardWhite, CircleShape)
                .background(if (live) OnPathColors.CardWhite else androidx.compose.ui.graphics.Color.Transparent, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(str(Res.string.live_sign), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                color = OnPathColors.CardWhite.copy(alpha = if (live) 1f else 0.7f))
        }
        LiveSignMenu(open, actions, onAction) { open = false }
    }
}

/** The sign tapped: the sharing's actions as a drop-down (bare labels – owner: menus with minimal text). */
@Composable
private fun LiveSignMenu(open: Boolean, actions: List<LiveAction>, onAction: (LiveAction) -> Unit, close: () -> Unit) {
    androidx.compose.material3.DropdownMenu(expanded = open && actions.isNotEmpty(), onDismissRequest = close) {
        LiveMenuItems(actions) { close(); onAction(it) }
    }
}

/** The actions' rows of the sign's drop-down (also drawn on their own by the picture test). */
@Composable
fun LiveMenuItems(actions: List<LiveAction>, onAction: (LiveAction) -> Unit) = Column {
    actions.forEach { a ->
        androidx.compose.material3.DropdownMenuItem(text = { Text(str(when (a) {
            LiveAction.SHARE -> Res.string.live_share
            LiveAction.SHARE_AGAIN -> Res.string.live_share_again
            LiveAction.STOP -> Res.string.live_stop
        })) }, onClick = { onAction(a) })
    }
}

/**
 * The name on the viewer page ("Tour von …"); may stay empty. Asked once before the first share ("Teilen") and from the
 * "Name" row ([edit]: "Speichern"). A Material 3 dialog with its two actions in one row.
 */
@Composable
fun LiveNameDialog(name: String, onShare: (String) -> Unit, onCancel: () -> Unit, edit: Boolean = false) {
    var text by remember { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(str(Res.string.live_name_title)) },
        text = {
            Column {
                Text(str(Res.string.live_name_text), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, singleLine = true,
                    label = { Text(str(Res.string.live_name_field)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onShare(text) }))
            }
        },
        confirmButton = { TextButton(colors = ActionButtons.editText(), onClick = { onShare(text) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(str(if (edit) Res.string.common_save else Res.string.live_name_ok))
        } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_cancel)) } },
    )
}
