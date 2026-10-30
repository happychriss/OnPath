package com.onpath.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onpath.strings.*

/**
 * Navigate, under the status card while a tour runs with OnPath's notifications off ([com.onpath.app.navigation.NotificationsOff]):
 * small amber chip "⚠ Notifications off / No tour info on the lock screen" + OK. Tap on the text → the phone setup step
 * for notifications; OK → gone for this tour. Never goes by itself (owner: no self-dismissing messages).
 */
@Composable
fun NotificationsOffChip(onSetUp: () -> Unit, onOk: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.medium, color = OnPathColors.WarnContainer, contentColor = OnPathColors.OnWarnContainer,
        shadowElevation = 2.dp, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f, fill = false).heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = str(Res.string.nav_notif_off_click), onClick = onSetUp)
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Warning, null, tint = OnPathColors.Warn, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(str(Res.string.nav_notif_off), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        color = OnPathColors.Warn, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(str(Res.string.nav_notif_off_line), style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = onOk, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(str(Res.string.common_ok), color = OnPathColors.Warn, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
