package com.onpath.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.plansync.PlanSync
import com.onpath.strings.*

/** Settings → Browser sync (plan sync with the web planner, A-007): what the page shows. */
data class SyncUi(
    val paired: Boolean = false,
    /** The browser's name ("Chrome on Linux"); null = it has not said it yet. */
    val peer: String? = null,
    val lastOkMs: Long = 0,
    val running: Boolean = false,
    val problem: PlanSync.Problem? = null,
    /** A scanned pairing code waits for Pair / Cancel: its six check digits ("395 514"). */
    val askCheck: String? = null,
) {
    companion object {
        fun of(s: PlanSync.State, askCheck: String?) = SyncUi(s.paired, s.peer, s.lastOkMs, s.running, s.problem, askCheck)
    }
}

class SyncCallbacks(
    val onOpenCamera: () -> Unit = {},
    val onPair: () -> Unit = {},
    val onCancelPair: () -> Unit = {},
    val onSyncNow: () -> Unit = {},
    /** After the question (it can lose something: the browser stops getting the routes). */
    val onUnpair: () -> Unit = {},
)

/** The list line under "Browser sync". */
fun SettingsSummary.sync(ui: SyncUi): Summary = when {
    ui.askCheck != null -> Summary(Txt.s(Res.string.sync_ask_title), Summary.Attention.ACCENT)
    !ui.paired -> Summary(Txt.s(Res.string.sync_sum_off))
    ui.problem != null && ui.problem != PlanSync.Problem.Offline ->
        Summary(peerLine(ui), Summary.Attention.PROBLEM)
    else -> Summary(peerLine(ui))
}

private fun peerLine(ui: SyncUi) = ui.peer?.let { Txt.s(Res.string.sync_paired_with, it) } ?: Txt.s(Res.string.sync_paired_browser)

/** The status line of the last pass: grey (offline), amber ⚠ (server refused, newer app, not saved), red (storage). */
fun syncProblemText(p: PlanSync.Problem): Pair<String, NoticeKind?> = when (p) {
    PlanSync.Problem.Offline -> Txt.s(Res.string.sync_offline) to NoticeKind.INFO
    is PlanSync.Problem.Refused -> (if (p.status == 507) Txt.s(Res.string.sync_full)
        else Txt.s(Res.string.sync_refused, "HTTP ${p.status}" + (p.error?.let { " – $it" } ?: ""))) to null
    is PlanSync.Problem.NewerApp -> Txt.p(Res.plurals.sync_newer_app, p.count, p.count) to null
    is PlanSync.Problem.NotSaved -> Txt.p(Res.plurals.sync_not_saved, p.count, p.count) to null
    PlanSync.Problem.Storage -> Txt.s(Res.string.sync_storage) to NoticeKind.ERROR
}

@Composable
fun SyncSection(ui: SyncUi, cb: SyncCallbacks, now: Long = System.currentTimeMillis()) {
    var askUnpair by remember { mutableStateOf(false) }
    Section(SettingsPage.SYNC.title) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                ui.askCheck != null -> {
                    // a scanned pairing code: the check digits, then Pair / Cancel (nothing is stored before Pair)
                    Text(str(Res.string.sync_ask_title), style = MaterialTheme.typography.titleMedium)
                    Text(str(Res.string.sync_ask_check, ui.askCheck), style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = cb.onPair, Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.sync_pair)) }
                        TextButton(onClick = cb.onCancelPair, Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_cancel)) }
                    }
                }
                !ui.paired -> {
                    Text(str(Res.string.sync_intro), style = MaterialTheme.typography.bodyMedium)
                    Text(str(Res.string.sync_steps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = cb.onOpenCamera, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(str(Res.string.sync_open_camera)) }
                }
                else -> {
                    Text(peerLine(ui), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(if (ui.lastOkMs > 0) str(Res.string.sync_last, SettingsSummary.whenText(ui.lastOkMs, now)) else str(Res.string.sync_never),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ui.problem?.let { p -> SyncStatusLine(syncProblemText(p)) }
                    OutlinedButton(onClick = { if (!ui.running) cb.onSyncNow() }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        if (ui.running) BusyContent(str(Res.string.sync_running)) else Text(str(Res.string.sync_now))
                    }
                    TextButton(onClick = { askUnpair = true }, Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.sync_unpair)) }
                }
            }
        }
    }
    if (askUnpair) AlertDialog(
        onDismissRequest = { askUnpair = false },
        title = { Text(str(Res.string.sync_unpair_title)) },
        text = { DialogText(str(Res.string.sync_unpair_text)) },
        confirmButton = { TextButton(onClick = { askUnpair = false; cb.onUnpair() }, Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.sync_unpair)) } },
        dismissButton = { TextButton(onClick = { askUnpair = false }, Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_cancel)) } },
    )
}

/** Grey ⓘ (INFO), amber ⚠ (null = WARNING) or red ⚠ (ERROR) – the Messages rules (CLAUDE.md). */
@Composable
private fun SyncStatusLine(line: Pair<String, NoticeKind?>) {
    val (text, kind) = line
    val colour = when (kind) {
        NoticeKind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        NoticeKind.ERROR -> OnPathColors.Error
        null -> OnPathColors.Warn
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (kind == NoticeKind.INFO) Icons.Outlined.Info else Icons.Outlined.Warning, null, tint = colour, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colour)
    }
}
