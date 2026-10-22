package com.onpath.app.ui

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
import androidx.compose.ui.unit.sp

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

/** The result line of Clean up / Clear all (owner 2026-10-22: on the page, not a message box on the map). Unit-tested. */
object OfflineResult {
    fun removed(areas: Int, bytes: Long, afterTour: Boolean): String = when {
        areas <= 0 -> "No map areas to remove"
        else -> listOfNotNull("$areas ${if (areas == 1) "area" else "areas"} removed", if (bytes > 0) "${mbText(bytes)} freed" else null,
            if (afterTour) "the map changes after the tour" else null).joinToString(" · ")
    }
}

/** Offline maps → "Clean up" (owner 2026-10-22): the areas that would go, their size and what is kept. */
data class CleanupPreview(
    val remove: List<com.onpath.app.cells.CellId>,
    val bytes: Long,
    /** "your current plan", "saved routes", "running tour", "where you are now" – those that apply. */
    val keeps: List<String>,
) {
    /** "Remove 23 areas · 410 MB" */
    val title: String get() = if (remove.isEmpty()) "Nothing to remove"
        else "Remove ${remove.size} ${if (remove.size == 1) "area" else "areas"} · ${mbText(bytes)}"
    /** "Keeps: your current plan · saved routes · …" */
    val keepsLine: String get() = if (keeps.isEmpty()) "Nothing is in use – every downloaded area goes."
        else "Keeps: " + keeps.joinToString(" · ")
}

/** Everything still to download: map regions and routing data parts, about [bytes]. */
data class WaitingDownloads(val regions: Int, val routingParts: Int, val bytes: Long) {
    val any: Boolean get() = regions > 0 || routingParts > 0
    /** "Waiting to download: 3 areas · about 85 MB" (routing data alone: "routing data"). */
    val line: String get() {
        val what = if (regions > 0) "$regions ${if (regions == 1) "area" else "areas"}" else "routing data"
        return "Waiting to download: $what" + (if (bytes > 0) " · about ${mb(bytes)} MB" else "")
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
    Section("Offline maps") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!ui.available) Text("Map downloads not available in this build. Import a map package in Diagnostics → Developer tools.", fontSize = 13.sp, color = variant)
            else Text("The detailed map downloads by itself where you set a point, plan a route or are on tour.",
                fontSize = 13.sp, color = variant)
            ui.result?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            if (ui.areas.isEmpty()) Text("No map areas downloaded yet.", fontSize = 14.sp)
            ui.areas.forEach { a ->
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontSize = 15.sp)
                        Text("${mbText(a.bytes)} · ${a.date}" + (a.status?.let { " · $it" } ?: ""), fontSize = 12.sp, color = variant)
                    }
                    if (confirm != a.id) TextButton(onClick = { confirm = a.id }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
                }
                if (confirm == a.id) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Delete this area? It downloads again when you plan there.", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { confirm = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                    TextButton(onClick = { confirm = null; cb.onDelete(a.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Delete", color = OnPathColors.Bad, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (ui.areas.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Text("${ui.areas.size} ${if (ui.areas.size == 1) "area" else "areas"} · ${mbText(ui.areas.sumOf { it.bytes })} in total",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            val w = ui.waiting
            if (w != null) {
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(w.line, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = cb.onCancelAll, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Cancel all", color = OnPathColors.Bad, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text("Cancel all stops these downloads; the maps you have stay.", fontSize = 12.sp, color = variant)
            } else if (ui.loading > 0) Text("${ui.loading} more ${if (ui.loading == 1) "area is" else "areas are"} loading – see the route sheet.",
                fontSize = 13.sp, color = variant)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = ui.wifiOnly, role = Role.Switch, onValueChange = cb.onWifiOnly).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Download maps only on Wi-Fi", fontSize = 16.sp)
                Text(if (ui.wifiOnly) "Nothing loads over mobile data, not even your own area"
                    else "Your own area loads on mobile data; more areas ask first", fontSize = 12.sp, color = variant)
            }
            Switch(checked = ui.wifiOnly, onCheckedChange = null)
        }
        if (ui.available && ui.areas.isNotEmpty()) Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            OutlinedButton(onClick = cb.onUpdateNow, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Update maps now") }
            Text("Newer maps download on Wi-Fi; the current ones keep working until then. Used areas also update monthly by themselves.",
                fontSize = 12.sp, color = variant)
        }
        if (ui.areas.isNotEmpty()) Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            OutlinedButton(onClick = cb.onCleanUp, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Clean up") }
            Text("Removes the areas you no longer need. Shows first what goes and what stays.", fontSize = 12.sp, color = variant)
            OutlinedButton(onClick = { askClearAll = true }, enabled = ui.clearAllBlocked == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = OnPathColors.Bad)) {
                Text("Clear all", fontWeight = FontWeight.SemiBold)
            }
            Text(ui.clearAllBlocked ?: "Removes every downloaded area. The world overview stays; areas load again when needed.",
                fontSize = 12.sp, color = variant)
        }
    }
    ui.cleanup?.let { c ->
        AlertDialog(onDismissRequest = cb.onCleanUpCancel,
            title = { Text(c.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(c.keepsLine)
                    if (c.remove.isNotEmpty()) Text("Removed areas load again when you plan or go there.", fontSize = 13.sp, color = variant)
                    else Text("Every downloaded area is in use.", fontSize = 13.sp, color = variant)
                }
            },
            confirmButton = {
                if (c.remove.isNotEmpty()) TextButton(onClick = cb.onCleanUpConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Remove", color = OnPathColors.Bad, fontWeight = FontWeight.SemiBold)
                } else TextButton(onClick = cb.onCleanUpCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
            },
            dismissButton = {
                if (c.remove.isNotEmpty()) TextButton(onClick = cb.onCleanUpCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            })
    }
    if (askClearAll && ui.clearAllBlocked == null) {
        val total = ui.areas.sumOf { it.bytes }
        AlertDialog(onDismissRequest = { askClearAll = false },
            title = { Text("Clear all map areas?") },
            text = {
                Text("All ${ui.areas.size} downloaded ${if (ui.areas.size == 1) "area" else "areas"} (${mbText(total)}) are removed, " +
                    "also those of your plan and saved routes. The world overview stays; areas load again when needed. " +
                    "Tours and routes are kept.")
            },
            confirmButton = {
                TextButton(onClick = { askClearAll = false; cb.onClearAll() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Clear all", color = OnPathColors.Bad, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { askClearAll = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
    }
}

internal fun mbText(bytes: Long): String =
    if (bytes < 10 * 1_048_576L) "%.1f MB".format(java.util.Locale.ROOT, bytes / 1_048_576.0) else "${mb(bytes)} MB"
