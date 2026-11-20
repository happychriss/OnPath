package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
 * Live sharing on Navigate's status card (docs/design/live-tracking.md §2) – one row under the route numbers:
 * not sharing it is the quiet entry "Live teilen" (a tap → the share sheet); sharing it is the sign "● Live" (green:
 * uploads get through; grey + "wartet auf Netz": they wait) and a tap on it opens the two actions. Nothing when
 * [actions] is empty (no tour records). [menuInitiallyOpen] is for pictures.
 */
@Composable
fun LiveShareRow(sign: LiveSign, actions: List<LiveAction>, onAction: (LiveAction) -> Unit, modifier: Modifier = Modifier,
                 menuInitiallyOpen: Boolean = false) {
    if (actions.isEmpty()) return
    Box(modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp)) {
        if (sign == LiveSign.NONE) {
            TextButton(onClick = { onAction(LiveAction.SHARE) }, colors = ActionButtons.editText(), modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Share, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(str(Res.string.live_share))
            }
        } else {
            var open by remember { mutableStateOf(menuInitiallyOpen) }
            val label = str(if (sign == LiveSign.LIVE) Res.string.live_sign else Res.string.live_sign_waiting)
            val said = str(Res.string.live_sign_cd, label)
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button) { open = true }
                    .heightIn(min = 48.dp).padding(horizontal = 12.dp)
                    .semantics(mergeDescendants = true) { contentDescription = said }) {
                Box(Modifier.size(10.dp).background(if (sign == LiveSign.LIVE) OnPathColors.Green else OnPathColors.Grey, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (sign == LiveSign.LIVE) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                LiveMenuItems(actions) { open = false; onAction(it) }
            }
        }
    }
}

/** The sharing's actions as menu rows (bare labels – owner: menus with minimal text). */
@Composable
fun LiveMenuItems(actions: List<LiveAction>, onAction: (LiveAction) -> Unit) = Column {
    actions.forEach { a ->
        DropdownMenuItem(text = { Text(str(when (a) {
            LiveAction.SHARE -> Res.string.live_share
            LiveAction.SHARE_AGAIN -> Res.string.live_share_again
            LiveAction.STOP -> Res.string.live_stop
        })) }, onClick = { onAction(a) })
    }
}

/**
 * The one-time question before the first share: the name on the viewer page ("Tour von …"); may stay empty. A Material 3
 * dialog with its two actions in one row.
 */
@Composable
fun LiveNameDialog(name: String, onShare: (String) -> Unit, onCancel: () -> Unit) {
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
        confirmButton = { TextButton(colors = ActionButtons.editText(), onClick = { onShare(text) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.live_name_ok)) } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_cancel)) } },
    )
}
