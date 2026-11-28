package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.onpath.app.ui.live.LiveAction
import com.onpath.app.ui.live.LiveSign

/**
 * Live sharing in one place of its own (Chriss 2026-11-28 14:11, like „Mit Browser verbinden“): in Settings one row
 * „Standort teilen“ with its state in one line ([LiveShareRow]); behind it the page ([SettingsPage.LIVE], [LiveSharePage])
 * with exactly three points – Name auf der Seite · Standort teilen / Link nochmal teilen · Teilen beenden (only while
 * sharing). Every way in – the share button on Navigate's tour bubble, the waiting pill, the ⚙ row – opens that page.
 * Live sharing provides both ([LocalLiveShare], `LiveShareLayer`), so Settings does not know the sharing.
 */
class LiveShareEntry(val row: @Composable (onOpen: () -> Unit) -> Unit, val page: @Composable () -> Unit)

/** Live sharing's row and page for Settings; null = sharing not offered on this phone. */
val LocalLiveShare = compositionLocalOf<LiveShareEntry?> { null }

/** The state in one line under „Standort teilen“: aktiv / wartet auf Netz / nicht aktiv. */
fun liveStatusRes(sign: LiveSign) = when (sign) {
    LiveSign.LIVE -> Res.string.live_status_on
    LiveSign.WAITING -> Res.string.live_status_waiting
    LiveSign.NONE -> Res.string.live_status_off
}

/** The one row in Settings (on the way and the whole list): „Standort teilen“ + its state; a tap opens the page. */
@Composable
fun LiveShareRow(sign: LiveSign, onOpen: () -> Unit) =
    SettingsCard { LiveRow(Icons.Outlined.Share, str(Res.string.live_share), str(liveStatusRes(sign)), onOpen) }

/**
 * The page: the server's „ended“ line first when it happened; then Name auf der Seite (a tap asks the name) · Standort
 * teilen / Link nochmal teilen ([actions]) · Teilen beenden only while sharing; with no tour recording and nothing shared
 * one line says that sharing goes during a tour.
 */
@Composable
fun LiveSharePage(sign: LiveSign, actions: List<LiveAction>, onAction: (LiveAction) -> Unit, name: String, onName: () -> Unit,
                  ended: Boolean = false, onEndedShown: () -> Unit = {}) {
    if (ended) androidx.compose.runtime.LaunchedEffect(Unit) { onEndedShown() }
    Column {
        if (ended && sign == LiveSign.NONE) {
            Text(str(Res.string.live_ended_by_server), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        SettingsCard {
            LiveRow(Icons.Outlined.Person, str(Res.string.live_name_row), name.ifEmpty { null }, onName)
            val share = actions.firstOrNull { it == LiveAction.SHARE || it == LiveAction.SHARE_AGAIN }
            if (share != null) {
                HorizontalDivider(Modifier.padding(start = 64.dp))
                LiveRow(Icons.Outlined.Share, str(if (share == LiveAction.SHARE) Res.string.live_share else Res.string.live_share_again)) { onAction(share) }
            }
            if (LiveAction.STOP in actions) {
                HorizontalDivider(Modifier.padding(start = 64.dp))
                LiveRow(Icons.Outlined.Close, str(Res.string.live_stop)) { onAction(LiveAction.STOP) }
            }
        }
        if (actions.isEmpty()) Text(str(Res.string.live_only_on_tour), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Spacer(Modifier.height(12.dp))
    }
}
