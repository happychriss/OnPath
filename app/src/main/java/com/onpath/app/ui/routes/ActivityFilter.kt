package com.onpath.app.ui.routes

import androidx.compose.foundation.layout.Box
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
import com.onpath.app.data.Tour
import com.onpath.app.routing.Activity
import com.onpath.app.ui.OnPathColors
import com.onpath.app.ui.icon
import com.onpath.strings.*

/**
 * My routes filtered by activity (owner 2026-11-13, after the sketches): a chip after "☆ Favoriten" always names one
 * choice – preset with the planning activity every time My routes opens ("🚶 Wandern ▾"), a pick holds until My routes
 * closes. Its menu: the six activities (single choice) and "Alle" under a line ("Alle ▾"). An activity shows exactly its
 * own tours (owner 2026-11-13 on the Pixel: „wenn ich E-Bike anklicke, will ich nur E-Bike sehen“). Combines with
 * Favoriten, the search and the sort, in both tabs; Zuletzt hides while one is chosen. Tours without a known activity
 * show only under "Alle". Pure rules here.
 */
object ActivityFilter {
    /** The menu's order. */
    val MENU = listOf(Activity.HIKE, Activity.BIKE_TOURING, Activity.BIKE_TREKKING, Activity.EBIKE, Activity.MTB, Activity.EMTB)

    /** [t] shows under [chosen] (null = "Alle"): exactly that activity. */
    fun keep(t: Tour, chosen: Activity?): Boolean = chosen == null || t.activity == chosen.name

    /**
     * The saved routes / done tours My routes shows (no drafts – they show only as the Zuletzt row): only favourites when
     * [onlyFavourites], only [act] (null = all), in the order [sort]. One function, so the screen recomputes it with every
     * input as a key (field bug 2026-11-13: the activity was not one – a choice did not always filter the list).
     */
    fun saved(shown: List<Tour>, sort: RouteSort.Choice, onlyFavourites: Boolean, act: Activity?): List<Tour> =
        RouteSort.sort(Favourites.shown(shown.filter { !it.draft }, onlyFavourites).filter { keep(it, act) }, sort)
}

/** The chip and its menu ([ActivityFilter]); [chosen] null = "Alle". */
@Composable
fun ActivityFilterChip(chosen: Activity?, onPick: (Activity?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = chosen?.chip ?: str(Res.string.routes_act_all)
    val spoken = str(Res.string.routes_act_chip_desc, name)
    Box {
        FilterChip(selected = chosen != null, onClick = { open = true },
            label = { Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
            leadingIcon = chosen?.let { a -> { Icon(a.icon, null, Modifier.size(FilterChipDefaults.IconSize)) } },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = OnPathColors.GreenLight),
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
