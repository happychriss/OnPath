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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import org.jetbrains.compose.resources.StringResource
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

/** One row as the on-the-way Settings draw theirs: the symbol, the label (+ a small second line), the whole row tappable. */
@Composable
internal fun LiveRow(icon: ImageVector, label: String, sub: String? = null, onClick: () -> Unit) {
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

/** How the share button looks (owner 2026-11-24): plain, green filled (sharing), grey filled (waiting for the network). */
enum class ShareLook { PLAIN, GREEN, GREY }

/** What the share button and the pill show for a sharing state – one place, tested without a screen. */
object LiveShareLook {
    fun button(sign: LiveSign): ShareLook = when (sign) {
        LiveSign.NONE -> ShareLook.PLAIN
        LiveSign.LIVE -> ShareLook.GREEN
        LiveSign.WAITING -> ShareLook.GREY
    }

    /** The pill under the status card: only while waiting (live needs no word, the green button says it). */
    fun pill(sign: LiveSign): Boolean = sign == LiveSign.WAITING

    /** TalkBack's name of the button: the state when sharing, else what a tap does. */
    fun description(sign: LiveSign): StringResource = when (sign) {
        LiveSign.NONE -> Res.string.live_share
        LiveSign.LIVE -> Res.string.live_sign
        LiveSign.WAITING -> Res.string.live_sign_waiting
    }

    /** From this font scale on the pill keeps clear of the map column (it would reach under it on a narrow phone). */
    const val PILL_CLEAR_FROM_FONT = 1.5f
}

/**
 * The share button on top of Navigate's tour bubble (owner 2026-11-24): the share symbol, plain while not sharing; while
 * sharing a 40 dp filled circle – green (uploads get through) or grey (waiting for the network) – with a white symbol.
 * A tap: not sharing → [onAction] SHARE (name question / share sheet); sharing → a small menu "Link nochmal teilen" /
 * "Teilen beenden". [size] = the map controls' slot (56 dp).
 */
@Composable
fun LiveShareButton(sign: LiveSign, size: Dp, onOpen: () -> Unit) {
    val look = LiveShareLook.button(sign)
    val label = str(LiveShareLook.description(sign))
    Box {
        Box(Modifier.size(size).clip(CircleShape)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            when (look) {
                ShareLook.PLAIN -> Icon(Icons.Outlined.Share, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                else -> Box(Modifier.size(40.dp).background(if (look == ShareLook.GREEN) OnPathColors.Green else OnPathColors.Grey, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Share, null, tint = OnPathColors.CardWhite, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/**
 * The pill under Navigate's status card, only while sharing waits for the network: a grey dot and "Standort · wartet auf
 * Netz" (wraps, never "…"; at a large font it keeps clear of the map column). A tap opens the share button's menu.
 */
@Composable
fun LiveWaitingPill(sign: LiveSign, onOpen: () -> Unit) {
    if (!LiveShareLook.pill(sign)) return
    val label = str(Res.string.live_sign_waiting)
    val lane = LocalControlsLane.current
    val d = androidx.compose.ui.platform.LocalDensity.current
    // the map column stands beside the pill's top: the pill keeps clear of it (wider when its tour bubble lies beside the map
    // bubble). Read while measuring, so the tier the footer just chose counts in the same frame
    val clearSides = Modifier.layout { m, c ->
        val clear = if (lane.tier == ControlsTier.SIDE || d.fontScale >= LiveShareLook.PILL_CLEAR_FROM_FONT)
            ControlsFit.pillClear(lane.tier, 2).roundToPx() else 0
        val p = m.measure(c.copy(maxWidth = (c.maxWidth - clear).coerceAtLeast(0)))
        layout(c.maxWidth, p.height) { p.place(0, 0) }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { lane.pillDp = 0 } }
    val shape = RoundedCornerShape(24.dp)
    Box(clearSides.fillMaxWidth().onSizeChanged { lane.pillDp = with(d) { it.height.toDp().value.toInt() } },
        contentAlignment = Alignment.Center) {
        Box {
            androidx.compose.material3.Surface(onClick = onOpen, shape = shape, color = OnPathColors.MapGround,
                modifier = Modifier.floatShadow(shape).semantics { contentDescription = label; role = Role.Button }) {
                Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(OnPathColors.Grey, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
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
