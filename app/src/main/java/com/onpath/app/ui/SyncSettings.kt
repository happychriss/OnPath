package com.onpath.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.plansync.PlanSync
import com.onpath.strings.*
import kotlinx.coroutines.delay

/** Settings → Browser sync (plan sync with the web planner, A-007): what the page shows. */
data class SyncUi(
    val paired: Boolean = false,
    /** The browser's name ("Chrome on Linux"); null = it has not said it yet. */
    val peer: String? = null,
    val lastOkMs: Long = 0,
    val problem: PlanSync.Problem? = null,
    /** A scanned pairing code waits for Pair / Cancel: its six check digits ("395 514"). */
    val askCheck: String? = null,
    /** Right after Pair (owner 2026-10-30): "Paired – … They're kept on your phone." with OK, before the paired page. */
    val pairedNote: Boolean = false,
    /** Pair was tapped for another browser while paired (A-017) and its one write runs: a spinner instead of the buttons. */
    val joining: Boolean = false,
) {
    companion object {
        /**
         * [pairedNote]: a pairing made here was stored and its OK not tapped yet. Shown only while the state says paired
         * and no new code waits for Pair / Cancel ("already paired" never sets it).
         */
        fun of(s: PlanSync.State, askCheck: String?, pairedNote: Boolean = false, joining: Boolean = false) =
            SyncUi(s.paired, s.peer, s.lastOkMs, s.problem, askCheck, pairedNote = pairedNote && s.paired && askCheck == null,
                joining = joining && askCheck != null)
    }
}

class SyncCallbacks(
    val onOpenCamera: () -> Unit = {},
    val onPair: () -> Unit = {},
    val onCancelPair: () -> Unit = {},
    /** OK under the "Paired – …" confirmation: the normal paired page. */
    val onPairedOk: () -> Unit = {},
    /** After the question (it can lose something: the browser stops getting the routes). */
    val onUnpair: () -> Unit = {},
)

/** Why the ☰ button carries an amber dot (owner 2026-10-30): only what needs the user – never plain offline. */
enum class SyncAttention { FULL, REFUSED, STORAGE, ENDED, STALE }

/**
 * The ☰ item for the browser sync (owner 2026-10-30): not paired "Pair with browser"; paired "Paired with browser ✓"
 * with a grey line "Pixel 8 ↔ Chrome on Linux · 1 min ago"; a problem worth attention ([warning]) makes the item amber
 * with the reason as its line, and an amber dot on the ☰ button. Tap → Settings → Browser sync.
 */
data class SyncMenuUi(
    val paired: Boolean = false,
    /** Second line: "<device> ↔ <browser> · <ago>" (paired), the reason (problem); null = none. */
    val line: String? = null,
    /** Why the item is amber and ☰ has the dot; null = all well (or not paired). */
    val warning: SyncAttention? = null,
) {
    companion object {
        /** Paired and online, passes run, but none ended well for this long: worth a look. */
        const val STALE_MS = 6 * 3_600_000L

        /**
         * The rule for the amber dot: mailbox full, server refused, sync files not written, the pairing ended in the
         * browser (until Browser sync is opened), or paired and online with no good pass for > [STALE_MS]: the last pass
         * (not offline) ended more than that after the last good one (or the pairing). No pass meanwhile – the app in
         * the background, a tour – is no reason. Never for offline, never when not paired (apart from "ended").
         */
        fun attention(s: PlanSync.State): SyncAttention? {
            if (!s.paired) return if (s.ended) SyncAttention.ENDED else null
            return when (val p = s.problem) {
                PlanSync.Problem.Offline -> null
                is PlanSync.Problem.Refused -> if (p.status == 507) SyncAttention.FULL else SyncAttention.REFUSED
                PlanSync.Problem.Storage -> SyncAttention.STORAGE
                else -> if (s.checkedMs - maxOf(s.lastOkMs, s.sinceMs) > STALE_MS) SyncAttention.STALE else null
            }
        }

        fun of(s: PlanSync.State, device: String, now: Long): SyncMenuUi {
            val warning = attention(s)
            val line = when {
                warning != null -> reason(warning)
                !s.paired -> null
                else -> "$device ↔ ${s.peer ?: Txt.s(Res.string.sync_menu_browser)}" +
                    (if (s.lastOkMs > 0) " · " + syncAgo(s.lastOkMs, now) else "")
            }
            return SyncMenuUi(s.paired, line, warning)
        }

        fun reason(a: SyncAttention): String = Txt.s(when (a) {
            SyncAttention.FULL -> Res.string.sync_menu_full
            SyncAttention.REFUSED -> Res.string.sync_menu_refused
            SyncAttention.STORAGE -> Res.string.sync_menu_storage
            SyncAttention.ENDED -> Res.string.sync_menu_ended
            SyncAttention.STALE -> Res.string.sync_menu_stale
        })
    }
}

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

/** When the last good pass was: "just now", "5 min ago", then "today 18:40" / "yesterday 09:12" / "21 Oct 18:40". */
fun syncAgo(lastMs: Long, now: Long): String {
    val min = (now - lastMs).coerceAtLeast(0) / 60_000
    return when {
        min < 1 -> Txt.s(Res.string.sync_ago_now)
        min < 60 -> Txt.s(Res.string.sync_ago_min, min.toInt())
        else -> SettingsSummary.whenText(lastMs, now)
    }
}

/**
 * Settings → Browser sync. No "Sync now" (owner 2026-10-30: automatic like WhatsApp Web – the app syncs about every
 * minute while it is open, plansync/SyncPoll); the status line "Last synced: 1 min ago" moves on by itself.
 */
@Composable
fun SyncSection(ui: SyncUi, cb: SyncCallbacks, now: Long = System.currentTimeMillis()) {
    var askUnpair by remember { mutableStateOf(false) }
    // the relative time moves on while the page is open ([now] + the time since; screenshots keep [now])
    val shownNow by produceState(now, now) {
        val start = System.currentTimeMillis()
        while (true) { delay(AGO_TICK_MS); value = now + (System.currentTimeMillis() - start) }
    }
    Section(SettingsPage.SYNC.title) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                ui.askCheck != null -> {
                    // a scanned pairing code: the check digits, then Pair / Cancel (nothing is stored before Pair)
                    Text(str(Res.string.sync_ask_title), style = MaterialTheme.typography.titleMedium)
                    Text(str(Res.string.sync_ask_check, ui.askCheck), style = MaterialTheme.typography.bodyLarge)
                    if (ui.joining) {
                        // another browser is being sent into this phone's mailbox (A-017): one write, then "Paired – …"
                        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = cb.onPair, Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.sync_pair)) }
                        TextButton(onClick = cb.onCancelPair, Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_cancel)) }
                    }
                }
                ui.pairedNote -> {
                    // the pairing just made (owner 2026-10-30): what it means, then OK → the paired page
                    Text(str(Res.string.sync_paired_note), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = cb.onPairedOk, Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_ok)) }
                }
                !ui.paired -> {
                    Text(str(Res.string.sync_intro), style = MaterialTheme.typography.bodyMedium)
                    Text(str(Res.string.sync_steps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = cb.onOpenCamera, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(str(Res.string.sync_open_camera)) }
                }
                else -> {
                    Text(peerLine(ui), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(if (ui.lastOkMs > 0) str(Res.string.sync_last, syncAgo(ui.lastOkMs, shownNow)) else str(Res.string.sync_never),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ui.problem?.let { p -> SyncStatusLine(syncProblemText(p)) }
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

private const val AGO_TICK_MS = 20_000L

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
