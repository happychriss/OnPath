package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One downloaded map area in Settings → Offline maps (F-OFF-05). */
data class OfflineArea(
    val id: String,
    /** "Meran" / "near Lana" from the offline index, else coordinates. */
    val name: String,
    val bytes: Long,
    /** "19 Oct 2026" – map data date (built), else install date. */
    val date: String,
    /** Plain state line while an update runs ("downloading 40 %"), else null. */
    val status: String? = null,
)

data class OfflineMapsUi(
    /** False: this build has no map server key – downloads are not possible. */
    val available: Boolean,
    val areas: List<OfflineArea>,
    val wifiOnly: Boolean,
    /** Areas still being fetched (not installed yet). */
    val loading: Int = 0,
    /** What waits to download (regions, routing parts); null = nothing. */
    val waiting: WaitingDownloads? = null,
    /** "Clean up" asked: what would go – shown as a question until Remove / Cancel; null = no question. */
    val cleanup: CleanupPreview? = null,
    /** "Clear all" not possible now (a tour runs): the short reason; null = possible. */
    val clearAllBlocked: String? = null,
    /** What the last Clean up / Clear all removed – a plain line on the page until it is left ([OfflineResult]). */
    val result: String? = null,
)

/** The result line of Clean up / Clear all: on the page, not a message box on the map. */
object OfflineResult {
    fun removed(areas: Int, bytes: Long, afterTour: Boolean): String = when {
        areas <= 0 -> Txt.s(Res.string.maps_result_none)
        else -> listOfNotNull(Txt.p(Res.plurals.maps_result_removed, areas, areas),
            if (bytes > 0) Txt.s(Res.string.maps_result_freed, mbText(bytes)) else null,
            if (afterTour) Txt.s(Res.string.maps_result_after_tour) else null).joinToString(" · ")
    }
}

/** Offline maps → "Clean up": the areas that would go, their size and what is kept. */
data class CleanupPreview(
    val remove: List<com.onpath.app.cells.CellId>,
    val bytes: Long,
    /** "your current plan", "saved routes", "running tour", "where you are now" – those that apply. */
    val keeps: List<String>,
) {
    /** "Remove 23 areas · 410 MB" */
    val title: String get() = if (remove.isEmpty()) Txt.s(Res.string.maps_cleanup_nothing)
        else Txt.p(Res.plurals.maps_cleanup_title, remove.size, remove.size, mbText(bytes))
    /** "Keeps: your current plan · saved routes · …" */
    val keepsLine: String get() = if (keeps.isEmpty()) Txt.s(Res.string.maps_cleanup_keeps_none)
        else Txt.s(Res.string.maps_cleanup_keeps, keeps.joinToString(" · "))
}

/** Everything still to download: map regions and routing data parts, about [bytes]. */
data class WaitingDownloads(val regions: Int, val routingParts: Int, val bytes: Long) {
    val any: Boolean get() = regions > 0 || routingParts > 0
    /** "Waiting to download: 3 areas · about 85 MB" (route planning data alone is not named: "Waiting to download · about 10 MB"). */
    val line: String get() {
        return when {
            regions > 0 && bytes > 0 -> Txt.p(Res.plurals.maps_waiting_areas_mb, regions, regions, mb(bytes))
            regions > 0 -> Txt.p(Res.plurals.maps_waiting_areas, regions, regions)
            bytes > 0 -> Txt.s(Res.string.maps_waiting_mb, mb(bytes))
            else -> Txt.s(Res.string.maps_waiting)
        }
    }
}

class OfflineMapsCallbacks(
    val onDelete: (String) -> Unit = {},
    val onWifiOnly: (Boolean) -> Unit = {},
    val onUpdateNow: () -> Unit = {},
    /** Drops every waiting download (installed maps stay). */
    val onCancelAll: () -> Unit = {},
    /** "Clean up": work out what would go ([OfflineMapsUi.cleanup] then shows the question). */
    val onCleanUp: () -> Unit = {},
    val onCleanUpConfirm: () -> Unit = {},
    val onCleanUpCancel: () -> Unit = {},
    /** "Clear all", after the second confirmation. */
    val onClearAll: () -> Unit = {},
)

/** Settings → Offline maps: installed areas, total size, delete (asked in the page), Wi-Fi only, update now. */
@Composable
fun OfflineMapsSection(ui: OfflineMapsUi, cb: OfflineMapsCallbacks, initialConfirm: String? = null,
                       initialClearAll: Boolean = false) {
    var confirm by remember { mutableStateOf(initialConfirm) }
    var askClearAll by remember { mutableStateOf(initialClearAll) }
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.bodySmall
    val body = MaterialTheme.typography.bodyLarge
    Section(str(Res.string.maps_offline_title)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // no intro: the page shows what is there
            if (!ui.available) Text(str(Res.string.maps_not_available), style = small, color = variant)
            ui.result?.let { Text(it, style = body, fontWeight = FontWeight.Medium) }
            if (ui.areas.isEmpty()) Text(str(Res.string.maps_no_areas), style = body)
            ui.areas.forEach { a ->
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = body)
                        Text("${mbText(a.bytes)} · ${a.date}" + (a.status?.let { " · $it" } ?: ""), style = small, color = variant)
                    }
                    if (confirm != a.id) TextButton(onClick = { confirm = a.id }, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_delete)) }
                }
                if (confirm == a.id) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(str(Res.string.maps_delete_question), style = small, modifier = Modifier.weight(1f))
                    TextButton(onClick = { confirm = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_cancel)) }
                    TextButton(onClick = { confirm = null; cb.onDelete(a.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(str(Res.string.common_delete), color = OnPathColors.Bad, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (ui.areas.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Text(plural(Res.plurals.maps_total, ui.areas.size, ui.areas.size, mbText(ui.areas.sumOf { it.bytes })),
                    style = small, fontWeight = FontWeight.SemiBold)
            }
            val w = ui.waiting
            if (w != null) {
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(w.line, style = small, modifier = Modifier.weight(1f))
                    TextButton(onClick = cb.onCancelAll, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(str(Res.string.common_cancel_all), color = OnPathColors.Bad, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else if (ui.loading > 0) Text(plural(Res.plurals.maps_more_loading, ui.loading, ui.loading),
                style = small, color = variant)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = ui.wifiOnly, role = Role.Switch, onValueChange = cb.onWifiOnly).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(str(Res.string.maps_wifi_only), style = body)
                Text(if (ui.wifiOnly) str(Res.string.maps_wifi_only_on)
                    else str(Res.string.maps_wifi_only_off), style = small, color = variant)
            }
            Switch(checked = ui.wifiOnly, onCheckedChange = null)
        }
        if (ui.available && ui.areas.isNotEmpty()) Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            OutlinedButton(onClick = cb.onUpdateNow, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(str(Res.string.maps_update_now)) }
            Text(str(Res.string.maps_update_now_note),
                style = small, color = variant)
        }
        if (ui.areas.isNotEmpty()) Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            OutlinedButton(onClick = cb.onCleanUp, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(str(Res.string.maps_clean_up)) }
            Text(str(Res.string.maps_clean_up_note), style = small, color = variant)
            OutlinedButton(onClick = { askClearAll = true }, enabled = ui.clearAllBlocked == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = OnPathColors.Bad)) {
                Text(str(Res.string.common_clear_all), fontWeight = FontWeight.SemiBold)
            }
            Text(ui.clearAllBlocked ?: str(Res.string.maps_clear_all_note),
                style = small, color = variant)
        }
    }
    ui.cleanup?.let { c ->
        AlertDialog(onDismissRequest = cb.onCleanUpCancel,
            title = { Text(c.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(c.keepsLine)
                    if (c.remove.isNotEmpty()) Text(str(Res.string.maps_cleanup_removed_note), style = small, color = variant)
                    else Text(str(Res.string.maps_cleanup_all_in_use), style = small, color = variant)
                }
            },
            confirmButton = {
                if (c.remove.isNotEmpty()) TextButton(onClick = cb.onCleanUpConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(str(Res.string.common_remove), color = OnPathColors.Bad, fontWeight = FontWeight.SemiBold)
                } else TextButton(onClick = cb.onCleanUpCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_ok)) }
            },
            dismissButton = {
                if (c.remove.isNotEmpty()) TextButton(onClick = cb.onCleanUpCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_cancel)) }
            })
    }
    if (askClearAll && ui.clearAllBlocked == null) {
        val total = ui.areas.sumOf { it.bytes }
        AlertDialog(onDismissRequest = { askClearAll = false },
            title = { Text(str(Res.string.maps_clear_all_title)) },
            text = {
                Text(plural(Res.plurals.maps_clear_all_text, ui.areas.size, ui.areas.size, mbText(total)))
            },
            confirmButton = {
                TextButton(onClick = { askClearAll = false; cb.onClearAll() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(str(Res.string.common_clear_all), color = OnPathColors.Bad, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { askClearAll = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_cancel)) } })
    }
}

internal fun mbText(bytes: Long): String =
    if (bytes < 10 * 1_048_576L) com.onpath.app.data.Format.number(bytes / 1_048_576.0, 1) + " MB" else "${mb(bytes)} MB"
