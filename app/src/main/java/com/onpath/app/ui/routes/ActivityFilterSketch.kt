package com.onpath.app.ui.routes

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.onpath.app.data.Tour
import com.onpath.app.routing.Activity
import com.onpath.app.ui.OnPathColors
import com.onpath.app.ui.icon
import com.onpath.strings.*

/**
 * SKETCH (owner 2026-11-13 „man kann nicht nach Art filtern, Wandern oder Fahrradfahren“), not for main as it is: set only
 * by ActivityFilterSketchTest. NONE = the chip row as it is.
 * - CHIPS (1): toggle chips right after ☆ Favoriten – "Wandern", "Rad" (Fahrrad, Gravel, E-Bike), "MTB" (MTB, E-MTB) with
 *   the activity signs; none on = all, several may be on.
 * - MENU (2): one chip "Alle Arten ▾" (one chosen: its name, several: "2 Arten") opening a small menu with the six
 *   activities as checkboxes.
 * Both combine with Favoriten, the search and the sort, in both tabs.
 */
object ActivityFilterSketch {
    enum class Mode { NONE, CHIPS, MENU }
    enum class Group(val members: Set<Activity>) {
        HIKE(setOf(Activity.HIKE)),
        BIKE(setOf(Activity.BIKE_TOURING, Activity.BIKE_TREKKING, Activity.EBIKE)),
        MTB(setOf(Activity.MTB, Activity.EMTB)),
    }
    var mode: Mode = Mode.NONE
    /** The activities shown (empty = all). */
    var chosen: Set<Activity> = emptySet()

    val filtering: Boolean get() = mode != Mode.NONE && chosen.isNotEmpty()

    fun keep(t: Tour): Boolean = mode == Mode.NONE || chosen.isEmpty() ||
        Activity.entries.firstOrNull { it.name == t.activity } in chosen

    @Composable
    fun Chips() {
        when (mode) {
            Mode.NONE -> Unit
            Mode.CHIPS -> Row(verticalAlignment = Alignment.CenterVertically) {
                for (g in Group.entries) {
                    val on = g.members.all { it in chosen } && chosen.isNotEmpty()
                    val sign = when (g) { Group.HIKE -> Activity.HIKE; Group.BIKE -> Activity.BIKE_TOURING; Group.MTB -> Activity.MTB }
                    val label = when (g) { Group.HIKE -> Activity.HIKE.chip; Group.BIKE -> str(Res.string.sketch_act_bike); Group.MTB -> Activity.MTB.chip }
                    FilterChip(selected = on, onClick = {}, label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                        leadingIcon = { Icon(if (on) Icons.Filled.Check else sign.icon, null, Modifier.size(FilterChipDefaults.IconSize)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = OnPathColors.GreenLight))
                    Spacer(Modifier.width(8.dp))
                }
            }
            Mode.MENU -> {
                val label = when (chosen.size) {
                    0 -> str(Res.string.sketch_act_all)
                    1 -> chosen.single().chip
                    else -> str(Res.string.sketch_act_n, chosen.size)
                }
                FilterChip(selected = chosen.isNotEmpty(), onClick = {},
                    label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                    leadingIcon = chosen.singleOrNull()?.let { a -> { Icon(a.icon, null, Modifier.size(FilterChipDefaults.IconSize)) } },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = OnPathColors.GreenLight))
            }
        }
    }

    /** MENU's items (the test draws them where the menu opens – popups are not drawn by Paparazzi). */
    @Composable
    fun MenuItems() {
        for (a in listOf(Activity.HIKE, Activity.BIKE_TOURING, Activity.BIKE_TREKKING, Activity.EBIKE, Activity.MTB, Activity.EMTB)) {
            DropdownMenuItem(text = { Text(a.label) }, onClick = {},
                leadingIcon = { Icon(a.icon, null) },
                trailingIcon = { Checkbox(checked = a in chosen, onCheckedChange = null) })
        }
    }
}
