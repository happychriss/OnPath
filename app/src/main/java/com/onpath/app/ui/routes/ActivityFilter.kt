package com.onpath.app.ui.routes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.onpath.app.routing.Activity
import com.onpath.app.ui.OnPathColors
import com.onpath.app.ui.icon
import com.onpath.strings.*

/** The chip and its menu ([ActivityFilter]); [chosen] null = "Alle". */
@Composable
fun ActivityFilterChip(chosen: Activity?, onPick: (Activity?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = chosen?.chip ?: str(Res.string.routes_act_all)
    val spoken = str(Res.string.routes_act_chip_desc, name)
    Box {
        // set (Chriss 2026-11-28): dark with × – × = „Alle“, the chip itself opens the list; none: light „Aktivität ▾“
        if (chosen != null) SetFilterChip(chosen.icon, name, onBody = { open = true }, onClear = { onPick(null) }, spokenBody = spoken)
        else FilterChip(selected = false, onClick = { open = true },
            label = { Text(str(Res.string.routes_act_chip_free), style = MaterialTheme.typography.labelMedium, maxLines = 1) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
            modifier = Modifier.semantics { contentDescription = spoken; role = Role.DropdownList })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ActivityFilterItems(chosen) { open = false; onPick(it) }
        }
    }
}

/** The menu's items: the six (single choice), a line, "Alle" (also drawn on their own by the screenshot tests). */
@Composable
fun ActivityFilterItems(chosen: Activity?, onPick: (Activity?) -> Unit) {
    for (a in ActivityFilter.MENU) {
        DropdownMenuItem(text = { Text(a.label) }, onClick = { onPick(a) }, leadingIcon = { Icon(a.icon, null) },
            trailingIcon = { RadioButton(selected = a == chosen, onClick = null) },
            modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.RadioButton })
    }
    HorizontalDivider()
    DropdownMenuItem(text = { Text(str(Res.string.routes_act_all)) }, onClick = { onPick(null) },
        leadingIcon = { Spacer(Modifier.size(24.dp)) },
        trailingIcon = { RadioButton(selected = chosen == null, onClick = null) },
        modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.RadioButton })
}

/**
 * The header over the saved routes / done tours (owner 2026-11-27, variant A): „GESPEICHERT“, and while the activity hides
 * tours „GESPEICHERT · NUR WANDERN“ with „1 weitere mit Rad“ on the right – a tap shows „Alle“. Shown with Zuletzt above
 * ([withRecent]) or whenever something is hidden. [scope]: the list's.
 */
fun savedHeader(scope: LazyListScope, withRecent: Boolean, label: org.jetbrains.compose.resources.StringResource, hidden: ActivityFilter.Hidden?, act: Activity?, onAll: () -> Unit) {
    if (!withRecent && hidden == null) return
    scope.item(key = "h-saved") {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val only = if (hidden != null && act != null) " · " + str(Res.string.routes_only, act.chip) else ""
            Text((str(label) + only).uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).semantics { heading() })
            if (hidden != null) {
                val more = hidden.activity?.let { plural(Res.plurals.routes_more_with, hidden.count, hidden.count, it.chip) }
                    ?: plural(Res.plurals.routes_more, hidden.count, hidden.count)
                Text(more, style = MaterialTheme.typography.labelLarge, color = OnPathColors.ActionEdit,
                    modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onAll).padding(horizontal = 8.dp, vertical = 14.dp))
            }
        }
    }
}
