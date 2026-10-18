package com.onpath.app.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.onpath.app.routing.Activity

/** The app-level things that are rarely needed while planning: all in the ⋮ menu of the search bar. */
class PlanMenu(
    val onTours: () -> Unit = {},
    val onImportGpx: () -> Unit = {},
    val onSettings: () -> Unit = {},
    /** Null hides "Check for updates" (updates switched off / not available in this build). */
    val onUpdates: (() -> Unit)? = null,
)

/**
 * Plan mode top area, Google Maps style: one search pill ("Search places or addresses") with the ⋮
 * overflow menu (My tours, Import GPX, Settings, Updates) at its end, and the activity as a compact
 * chip below it. Route actions live in the plan sheet's own ⋯ menu.
 */
@Composable
fun PlanTopBar(
    activity: Activity, sac: Int,
    onActivity: () -> Unit,
    onSearch: () -> Unit,
    menu: PlanMenu,
    modifier: Modifier = Modifier,
    menuInitiallyOpen: Boolean = false,
) {
    Column(modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        SearchBarPill(onSearch, menu, menuInitiallyOpen)
        Spacer(Modifier.height(8.dp))
        ActivityChip(activity, sac, onClick = onActivity)
    }
}

@Composable
private fun SearchBarPill(onSearch: () -> Unit, menu: PlanMenu, menuInitiallyOpen: Boolean) {
    Surface(
        onClick = onSearch,
        modifier = Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = "Search places or addresses"; role = Role.Button },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text("Search places or addresses", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f))
            OverflowMenu(menu, menuInitiallyOpen)
        }
    }
}

@Composable
private fun OverflowMenu(menu: PlanMenu, initiallyOpen: Boolean) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = DpOffset(0.dp, 4.dp)) {
            PlanMenuItems(menu, close = { open = false })
        }
    }
}

/** The items of the ⋮ menu (also rendered on their own by the screenshot tests). */
@Composable
internal fun PlanMenuItems(menu: PlanMenu, close: () -> Unit) {
    MenuItem(Icons.Outlined.FormatListBulleted, "My tours") { close(); menu.onTours() }
    MenuItem(Icons.Outlined.FileUpload, "Import GPX") { close(); menu.onImportGpx() }
    HorizontalDivider()
    MenuItem(Icons.Outlined.Settings, "Settings") { close(); menu.onSettings() }
    menu.onUpdates?.let { u -> MenuItem(Icons.Outlined.SystemUpdate, "Check for updates") { close(); u() } }
}

@Composable
internal fun MenuItem(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) }, enabled = enabled, onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null) },
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

/**
 * The activity as an assist chip ("Hike · T3 ▾") under the search bar. It is an action (opens the
 * activity dialog), so it carries the drop-down arrow; status is never drawn like this.
 */
@Composable
fun ActivityChip(activity: Activity, sac: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = activity.shortLabel + if (activity == Activity.HIKE) " · T$sac" else ""
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp).semantics { contentDescription = "Activity: $label. Tap to change" },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Row(Modifier.padding(start = 10.dp, end = 6.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(activity.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Icon(Icons.Filled.ArrowDropDown, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
